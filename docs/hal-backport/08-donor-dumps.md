# Donor dumps available on the lab drive

The `970` path in this workspace is a symlink to the external drive
(`/run/media/rezikham/970/PoC`). These extractions are kept there for future
porting work; nothing below is wired into the current Aurora build.

| Path | Device | OS | Contents |
| --- | --- | --- | --- |
| `970/extracted_aurora` | Xiaomi 14 Ultra (`aurora`) | OS3.0.304.0.WNAMIXM | full `/odm` + camera plugins |
| `970/extracted_madrid` | Xiaomi 18 Pro Max (`madrid`) | OS4.0.29.0.XEOCNXM | full ODM/vendor images |
| `970/extracted_nezha` | Xiaomi 17 Ultra (`nezha`) | — | ODM config + plugins |
| `970/extracted_byron` | Xiaomi 17 Max (`byron`, `2605EPN8EC`) | OS4.0.3.0.XAFCNXM | camera config JSONs, no plugin `.so` |

`extracted_byron` was unpacked from:

```text
970/PoC/hal-byron-2605EPN8EC-OS4.0.3.0.XAFCNXM-20261115-151636.tar
sha256 6347eab7aeced22f1034337606b243734fc58f1ff32fc7efbd89c7e88e209de1
```

The archive uses absolute symlinks for some `vendor/lib64` entries; those fail
to extract on the exFAT drive and are not needed because the corresponding
`odm` files are present. All 187 `odm/etc/camera` files, including
`satsnapshot.json` (which contains a `com.xiaomi.plugin.mileicafilter` node),
unpacked successfully.
