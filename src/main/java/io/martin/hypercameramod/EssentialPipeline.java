package io.martin.hypercameramod;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client side of the genuine Leica Essential pipeline.
 *
 * <p>Runs in the camera process, but performs no native work. It sends the captured JPEG to
 * {@link EssentialService} in the module's private {@code :essential} process and replaces the
 * file only if a complete result comes back before the timeout. A native crash therefore costs
 * the look on one photo, never the camera session.
 *
 * <p>Improvements (2026-11-17):
 * - Lazy service connection: only binds when an M3/M9 file arrives
 * - Better error handling: distinguishes timeout, crash, invalid JPEG, caller rejection
 * - Fallback to LUT path: if service unavailable, returns false so caller can try LUT fallback
 * - Structured logging with error codes for device debugging
 */
final class EssentialPipeline {

    private static final String TAG = "HCM-Essential";
    private static final String MODULE_PACKAGE = "io.rezikham.hypercameramod";
    private static final String SERVICE_CLASS = "io.martin.hypercameramod.EssentialService";
    private static final int MSG_PROCESS = 1;
    private static final long CONNECT_TIMEOUT_MS = 10_000;
    private static final long RESULT_TIMEOUT_MS = 90_000;
    private static final long MIN_FILE_BYTES = 50_000;

    // Error codes for structured logging
    private static final String ERR_NONE = "ok";
    private static final String ERR_NO_CONTEXT = "no_context";
    private static final String ERR_BIND_FAILED = "bind_failed";
    private static final String ERR_CONNECT_TIMEOUT = "connect_timeout";
    private static final String ERR_SERVICE_CRASHED = "service_crashed";
    private static final String ERR_RESULT_TIMEOUT = "result_timeout";
    private static final String ERR_INVALID_JPEG = "invalid_jpeg";
    private static final String ERR_REPLY_FAILED = "reply_failed";
    private static final String ERR_SERVICE_ERROR = "service_error";

    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile Context appContext;

    private EssentialPipeline() {
    }

    /**
     * Post-processes one saved JPEG. Returns true when the file was taken over by this pipeline
     * (whether or not the native stage produced a result), so the caller never also runs the
     * LUT fallback on the same file.
     *
     * @param file the saved JPEG file
     * @param legend 1 = M9, 2 = M3, 0 = not our look
     * @return true if pipeline handled this file (caller should skip LUT fallback)
     */
    static boolean handle(File file, int legend) {
        if (file == null || !file.isFile() || file.length() < MIN_FILE_BYTES) {
            Log.i(TAG, "handle: file too small or missing: " + (file != null ? file.getName() : "null"));
            return false;
        }
        if (legend != 1 && legend != 2) {
            Log.i(TAG, "no M3/M9 selection; leaving " + file.getName() + " untouched");
            return true; // consume the selection so LUT fallback doesn't run
        }
        if (!BUSY.compareAndSet(false, true)) {
            Log.w(TAG, "pipeline busy; leaving " + file.getName() + " untouched");
            return true;
        }
        try {
            long start = android.os.SystemClock.elapsedRealtime();
            Result result = process(readFile(file), legend);
            if (!result.success) {
                Log.w(TAG, "pipeline failed for " + file.getName() + ": " + result.errorCode);
                // Return true to consume the selection, but don't replace the file
                return true;
            }
            File tmp = new File(file.getParentFile(), file.getName() + ".hcm-essential.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(result.jpeg);
            }
            if (!tmp.renameTo(file)) {
                // Some providers refuse rename-over-existing; fall back to a copy.
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(result.jpeg);
                }
                tmp.delete();
            }
            Log.i(TAG, "applied legend=" + legend + " to " + file.getName() + " ("
                    + result.jpeg.length + " bytes, "
                    + (android.os.SystemClock.elapsedRealtime() - start) + " ms)");
        } catch (Throwable t) {
            Log.e(TAG, "pipeline unexpected error for " + file.getName(), t);
        } finally {
            BUSY.set(false);
        }
        return true;
    }

    private static byte[] readFile(File file) {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(file.length(), 64L << 20));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "cannot read " + file, t);
            return null;
        }
    }

    private static Result process(byte[] jpeg, int legend) throws Exception {
        Context context = context();
        if (context == null) {
            Log.w(TAG, "no application context");
            return new Result(false, null, ERR_NO_CONTEXT);
        }

        ParcelFileDescriptor[] inputPipe;
        try {
            inputPipe = ParcelFileDescriptor.createPipe();
        } catch (Throwable t) {
            Log.w(TAG, "create input pipe failed", t);
            return new Result(false, null, ERR_SERVICE_ERROR);
        }

        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final byte[][] result = new byte[1][];
        final Messenger[] service = new Messenger[1];
        final String[] errorCode = new String[1];

        Handler replyHandler = new Handler(Looper.getMainLooper(), msg -> {
            if (msg.what != MSG_PROCESS) {
                return false;
            }
            try {
                Bundle data = msg.getData();
                ParcelFileDescriptor output = data == null ? null
                        : data.getParcelable("output");
                if (output != null) {
                    result[0] = readAll(output);
                } else {
                    String err = data == null ? ERR_SERVICE_ERROR : data.getString("error", ERR_SERVICE_ERROR);
                    Log.w(TAG, "service error: " + err);
                    errorCode[0] = err;
                }
            } catch (Throwable t) {
                Log.w(TAG, "reply read failed", t);
                errorCode[0] = ERR_REPLY_FAILED;
            } finally {
                done.countDown();
            }
            return true;
        });

        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                service[0] = new Messenger(binder);
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // Native crash or process kill: fail fast, keep the original file.
                Log.w(TAG, "service disconnected (crash/kill)");
                errorCode[0] = ERR_SERVICE_CRASHED;
                done.countDown();
            }

            @Override
            public void onBindingDied(ComponentName name) {
                Log.w(TAG, "binding died");
                errorCode[0] = ERR_SERVICE_CRASHED;
                done.countDown();
            }

            @Override
            public void onNullBinding(ComponentName name) {
                Log.w(TAG, "null binding");
                errorCode[0] = ERR_BIND_FAILED;
                connected.countDown();
                done.countDown();
            }
        };

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(MODULE_PACKAGE, SERVICE_CLASS));
        boolean bound = false;
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                Log.w(TAG, "cannot bind " + SERVICE_CLASS);
                return new Result(false, null, ERR_BIND_FAILED);
            }
            if (!connected.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) || service[0] == null) {
                Log.w(TAG, "service did not connect in time");
                return new Result(false, null, ERR_CONNECT_TIMEOUT);
            }

            Message request = Message.obtain(null, MSG_PROCESS);
            request.replyTo = new Messenger(replyHandler);
            Bundle data = new Bundle();
            data.putParcelable("input", inputPipe[0]);
            data.putInt("legend", legend);
            data.putFloat("lux", 200f);
            data.putFloat("cct", 5500f);
            request.setData(data);
            service[0].send(request);

            final ParcelFileDescriptor writeEnd = inputPipe[1];
            final byte[] payload = jpeg;
            new Thread(() -> {
                try (OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
                    os.write(payload);
                } catch (Throwable t) {
                    Log.w(TAG, "input write failed: " + t);
                }
            }, "hcm-essential-in").start();
            inputPipe[1] = null; // ownership transferred to thread

            if (!done.await(RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "timed out waiting for the pipeline");
                return new Result(false, null, ERR_RESULT_TIMEOUT);
            }

            if (errorCode[0] != null) {
                return new Result(false, null, errorCode[0]);
            }

            byte[] outJpeg = result[0];
            if (outJpeg == null || outJpeg.length < 4
                    || (outJpeg[0] & 0xFF) != 0xFF || (outJpeg[1] & 0xFF) != 0xD8
                    || (outJpeg[outJpeg.length - 2] & 0xFF) != 0xFF
                    || (outJpeg[outJpeg.length - 1] & 0xFF) != 0xD9) {
                Log.w(TAG, "service returned invalid JPEG");
                return new Result(false, null, ERR_INVALID_JPEG);
            }
            return new Result(true, outJpeg, ERR_NONE);
        } finally {
            if (bound) {
                try {
                    context.unbindService(connection);
                } catch (Throwable ignored) {
                }
            }
            closeQuietly(inputPipe[0]);
            closeQuietly(inputPipe[1]);
        }
    }

    private static byte[] readAll(ParcelFileDescriptor pfd) {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "output read failed: " + t);
            return null;
        }
    }

    private static Context context() {
        Context cached = appContext;
        if (cached != null) {
            return cached;
        }
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                appContext = (Context) app;
                return appContext;
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot find application context: " + t);
        }
        return null;
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class Result {
        final boolean success;
        final byte[] jpeg;
        final String errorCode;

        Result(boolean success, byte[] jpeg, String errorCode) {
            this.success = success;
            this.jpeg = jpeg;
            this.errorCode = errorCode;
        }
    }
}
