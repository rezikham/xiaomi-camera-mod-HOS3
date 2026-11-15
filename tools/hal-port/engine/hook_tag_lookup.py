#!/usr/bin/env python3
"""Hook the Xiaomi/ChiX metadata tag lookup in the camera provider.

Logs every string-keyed lookup whose name matches --match (default
"mivi|filter|render"), with a short backtrace, so the runtime consumer of
`com.xiaomi.mivi2.filterId` / `com.xiaomi.mivi2.render` can be identified.

Usage:
    PYTHONPATH=/tmp/opencode/pylib python3 hook_tag_lookup.py \
        --serial 1c4e4dec [--pid 2140] [--match 'mivi|filter|render'] [--seconds 40]

Run a capture while the hook is attached; the script detaches by itself after
--seconds and prints a summary.

NOTE (aurora, OS3.0.304.0): attaching frida to the camera provider reboots this
unit (persist.vendor.camera.immunesystem.protection.switch=true). The tag chain
was answered with the behavioural A/B test in
docs/hal-backport/09-engine-lut-injection.md instead; this script is unverified
on aurora and kept for units where provider instrumentation is allowed.
"""
import argparse
import json
import sys
import time

import frida

SCRIPT = r"""
const MATCH = new RegExp(__MATCH__, 'i');
const interesting = [];
let count = 0;

function backtrace(ctx) {
  try {
    return Thread.backtrace(ctx, Backtracer.ACCURATE).slice(0, 8).map(function (a) {
      const s = DebugSymbol.fromAddress(a);
      const mod = (s.moduleName || '?').split('/').pop();
      return mod + '!' + (s.name || ('0x' + a.toString(16)));
    }).join(' <- ');
  } catch (e) { return 'bt-error:' + e; }
}

function findExport(modName, symbol) {
  // Frida >= 17 removed Module.findExportByName; use the module object.
  try {
    const m = Process.findModuleByName(modName);
    if (m !== null) {
      const a = m.findExportByName(symbol);
      if (a !== null) return a;
    }
  } catch (e) { }
  try { return Module.getGlobalExportByName(symbol); } catch (e) { return null; }
}

function hookStringLookup(modName, symbol, nameArg, isSectionPair) {
  const addr = findExport(modName, symbol);
  if (addr === null) { send({type: 'missing', mod: modName, sym: symbol}); return; }
  send({type: 'hooked', mod: modName, sym: symbol, addr: addr.toString()});
  Interceptor.attach(addr, {
    onEnter: function (args) {
      try {
        let name;
        if (isSectionPair) {
          const sec = args[1].readUtf8String();
          const nm = args[2].readUtf8String();
          name = (sec || '') + '.' + (nm || '');
        } else {
          name = args[nameArg].readUtf8String();
        }
        if (!name || !MATCH.test(name)) return;
        this.name = name;
        this.sret = this.context.x8;
        count++;
        send({type: 'lookup', name: name, bt: backtrace(this.context)});
      } catch (e) { }
    },
    onLeave: function () {
      if (!this.name) return;
      if (!this.sret || this.sret.isNull()) return;
      let dump = null, str = null, i32 = null;
      try { dump = this.sret.readByteArray(32); } catch (e) { }
      try { str = this.sret.add(0x18).readPointer().readCString(); } catch (e) { }
      try { i32 = this.sret.add(0x18).readPointer().readS32(); } catch (e) { }
      if (str !== null || i32 !== null) {
        send({type: 'value', name: this.name, str: str, i32: i32});
      }
    }
  });
}

hookStringLookup('libcom.xiaomi.metadatautils.so', '_ZNK10MiMetadata4findEPKc', 1, false);
hookStringLookup('libcom.xiaomi.metadatautils.so', '_ZN10MiMetadata14getTagFromNameEPKcPj', 1, false);
hookStringLookup('com.qti.chi.override.so', '_ZN11ChiMetadata6GetTagEPKcS1_j', 1, true);
hookStringLookup('libchifeature2.so', '_ZN19ChiFeature2DataPool6GetTagEPKcS1_jRjPj', 1, true);

rpc.exports = { summary: function () { return {count: count}; } };
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', default='1c4e4dec')
    ap.add_argument('--pid', type=int, default=0)
    ap.add_argument('--match', default='mivi|filter|render')
    ap.add_argument('--seconds', type=float, default=40.0)
    ap.add_argument('--out', default='')
    args = ap.parse_args()

    dev = frida.get_device_manager().add_remote_device(args.serial) if False else frida.get_usb_device()
    pid = args.pid
    if not pid:
        for p in dev.enumerate_processes():
            if p.name == 'vendor.qti.camera.provider-service_64':
                pid = p.pid
    if not pid:
        print('provider process not found', file=sys.stderr)
        return 2
    print('attaching to pid', pid, flush=True)
    session = dev.attach(pid)
    src = SCRIPT.replace('__MATCH__', json.dumps(args.match))
    script = session.create_script(src)

    seen = {}
    values = []

    def on_message(msg, data):
        if msg['type'] == 'error':
            print('[script error]', msg.get('stack') or msg.get('description'), flush=True)
            return
        p = msg.get('payload') or {}
        t = p.get('type')
        if t == 'hooked':
            print('hooked %s!%s @ %s' % (p['mod'], p['sym'], p['addr']), flush=True)
        elif t == 'missing':
            print('missing %s!%s' % (p['mod'], p['sym']), flush=True)
        elif t == 'lookup':
            n = p['name']
            seen[n] = seen.get(n, 0) + 1
            print('[lookup] %s\n          %s' % (n, p['bt']), flush=True)
        elif t == 'value':
            v = p.get('str') if p.get('str') is not None else p.get('i32')
            if v is not None:
                values.append((p['name'], v))
                print('[value ] %s = %r' % (p['name'], v), flush=True)

    script.on('message', on_message)
    script.load()
    try:
        time.sleep(args.seconds)
    except KeyboardInterrupt:
        pass
    try:
        print('summary:', script.exports_sync.summary(), flush=True)
    except Exception as e:
        print('summary failed:', e, flush=True)
    session.detach()

    print('\n--- distinct tag names ---', flush=True)
    for k, v in sorted(seen.items(), key=lambda kv: -kv[1]):
        print('%6d  %s' % (v, k), flush=True)
    print('--- values ---', flush=True)
    for k, v in values:
        print('%s = %r' % (k, v), flush=True)

    if args.out:
        with open(args.out, 'w') as f:
            json.dump({'seen': seen, 'values': values}, f, indent=2)
    return 0


if __name__ == '__main__':
    sys.exit(main())
