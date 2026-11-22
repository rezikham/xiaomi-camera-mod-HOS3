#!/usr/bin/env python3
"""Build the separate Madrid camera-plugin port module.

The Madrid 6.8 jpegrAggr/heifedit plugins are copied to a private
libhcm_* namespace. Their Xiaomi camera dependencies are renamed the same
way, so Aurora's stock /odm and /vendor libraries are never replaced.
Platform libraries (libc, liblog, libbinder, ...) keep their normal names.

Usage:
    python3 tools/prepare-plugin-port.py [--madrid DIR] [--patchelf PATH]
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MADRID = Path("/run/media/rezikham/970/PoC/extracted_madrid")
DEFAULT_PATCHELF = Path("/tmp/opencode/patchelf/bin/patchelf")
OUT = REPO_ROOT / "camera-mod-ksu-plugin"

# source relative to Madrid -> renamed module file
LIBS = {
    "vendor/lib64/libcom.xiaomi.pluginutils.so": "libhcm_pluginutils.so",
    "vendor/lib64/libcom.xiaomi.metadatautils.so": "libhcm_metadatautils.so",
    "vendor/lib64/libcom.xiaomi.debug.so": "libhcm_debug.so",
    "odm/lib64/com.xiaomi.camdfx.so": "libhcm_camdfx.so",
    "vendor/lib64/libcamlog.so": "libhcm_camlog.so",
    "vendor/lib64/libmetatagwrapper.so": "libhcm_metatagwrapper.so",
    "vendor/lib64/libcom.xiaomi.cam.miexifmgr.so": "libhcm_miexifmgr.so",
    "vendor/lib64/libcom.xiaomi.cam.oisexif.so": "libhcm_oisexif.so",
    "vendor/lib64/libcom.xiaomi.camera.configmanager.so": "libhcm_configmanager.so",
    "vendor/lib64/libcom.xiaomi.threadpool.so": "libhcm_threadpool.so",
    "odm/lib64/libabnormal_img_dectect.so": "libhcm_abnormal.so",
    "vendor/lib64/libcamera.xiaomi.catchlog.so": "libhcm_catchlog.so",
    "vendor/lib64/libcameraopt_stub.so": "libhcm_cameraopt.so",
    "vendor/lib64/libopbeanmsg.so": "libhcm_opbeanmsg.so",
    "vendor/lib64/liblegendmsg.so": "libhcm_legendmsg.so",
    "vendor/lib64/libprotobuf-cpp-full-6.33.5.so": "libhcm_protobuf_full.so",
    "vendor/lib64/libprotobuf-cpp-lite-6.33.5.so": "libhcm_protobuf_lite.so",
    "vendor/lib64/libimage_io.so": "libhcm_image_io.so",
    "vendor/lib64/libultrahdr.so": "libhcm_ultrahdr.so",
    "vendor/lib64/libcameraheif.so": "libhcm_cameraheif.so",
    "vendor/lib64/libexif.so": "libhcm_exif.so",
    "vendor/lib64/libmicamGlobalPool.so": "libhcm_globalpool.so",
    "vendor/lib64/libjpegencoder.so": "libhcm_jpegencoder.so",
    "vendor/lib64/libjpegdecoder.so": "libhcm_jpegdecoder.so",
    "vendor/lib64/libjpeg.so": "libhcm_jpeg.so",
    "vendor/lib64/libc++_shared.so": "libhcm_cxx_shared.so",
    "vendor/lib64/libcrypto.so": "libhcm_crypto.so",
    "vendor/lib64/libssl.so": "libhcm_ssl.so",
    "vendor/lib64/liblz4.so": "libhcm_lz4.so",
}

PLUGINS = {
    "odm/lib64/camera/plugins/com.xiaomi.plugin.jpegrAggr.so":
        "com.xiaomi.plugin.jpegrAggr.so",
    "odm/lib64/camera/plugins/com.xiaomi.plugin.heifedit.so":
        "com.xiaomi.plugin.heifedit.so",
}


def run(cmd: list[str]) -> None:
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)


def needed(lib: Path) -> list[str]:
    out = subprocess.run(
        ["readelf", "-d", str(lib)], check=True, capture_output=True, text=True
    ).stdout
    return [
        line.split("[", 1)[1].split("]", 1)[0]
        for line in out.splitlines()
        if "NEEDED" in line and "[" in line
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--madrid", type=Path, default=DEFAULT_MADRID)
    parser.add_argument("--patchelf", type=Path, default=DEFAULT_PATCHELF)
    args = parser.parse_args()

    if not args.patchelf.is_file():
        print(f"error: patchelf not found: {args.patchelf}", file=sys.stderr)
        return 1

    plugin_dir = OUT / "data/plugins"
    lib_dir = OUT / "odm/lib64"
    if (OUT / "odm").exists():
        shutil.rmtree(OUT / "odm")
    if plugin_dir.exists():
        shutil.rmtree(plugin_dir)
    plugin_dir.mkdir(parents=True)
    lib_dir.mkdir(parents=True, exist_ok=True)

    rename = {src.rsplit("/", 1)[-1]: dst for src, dst in LIBS.items()}
    rename.update({src.rsplit("/", 1)[-1]: dst for src, dst in PLUGINS.items()})

    sources: dict[str, Path] = {}
    for src, dst in PLUGINS.items():
        p = args.madrid / src
        if not p.is_file():
            print(f"error: missing {p}", file=sys.stderr)
            return 1
        shutil.copy2(p, plugin_dir / dst)
        sources[dst] = plugin_dir / dst

    for src, dst in LIBS.items():
        p = args.madrid / src
        if not p.is_file():
            print(f"error: missing {p}", file=sys.stderr)
            return 1
        shutil.copy2(p, lib_dir / dst)
        sources[dst] = lib_dir / dst

    # The Madrid MiExifMgr reads its own config by name; Aurora does not ship it.
    config_dir = OUT / "odm/etc/camera/xiaomi"
    config_dir.mkdir(parents=True, exist_ok=True)
    for name in ("MiExif.json", "videoexif.jsonc"):
        src = args.madrid / "odm/etc/camera/xiaomi" / name
        if src.is_file():
            shutil.copy2(src, config_dir / name)
        else:
            print(f"warning: missing config {src}", file=sys.stderr)

    for dst, path in sources.items():
        if dst.endswith(".so"):
            run([str(args.patchelf), "--set-soname", dst, str(path)])
        for dep in needed(path):
            if dep in rename and rename[dep] != dst:
                run([
                    str(args.patchelf),
                    "--replace-needed", dep, rename[dep], str(path),
                ])

    # Also expose the private namespace through /vendor/lib64. Some camera
    # linker namespaces search vendor before odm; duplicate names are harmless.
    vendor_dir = OUT / "vendor/lib64"
    vendor_dir.mkdir(parents=True, exist_ok=True)
    for lib in lib_dir.glob("*.so"):
        shutil.copy2(lib, vendor_dir / lib.name)

    # Verify no ported object still asks for a stock Xiaomi lib by name.
    bad: list[str] = []
    for dst, path in sources.items():
        for dep in needed(path):
            if dep in rename and rename[dep] != dst:
                bad.append(f"{dst} -> {dep}")
    if bad:
        print("error: unrenamed dependencies:", file=sys.stderr)
        for line in bad:
            print(f"  {line}", file=sys.stderr)
        return 1

    print(f"plugins: {len(PLUGINS)}")
    print(f"libraries: {len(LIBS)}")
    print(f"output: {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
