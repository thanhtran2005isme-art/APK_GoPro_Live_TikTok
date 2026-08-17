# APK_GoPro_Live_TikTok

Android app prototype focused on one job: receive the highest-quality low-latency GoPro HERO8 preview possible, show it as a clean full-screen surface, then let TikTok Mobile Gaming / screen sharing capture that surface.

This project is **preview-first, not recording-first**. Recording, media download, editing and cloud features are intentionally out of scope for the MVP.

## Current milestone

The `agent/gopro-foundation` branch implements the debug foundation and stream inspection gate:

- Android Studio template cleanup
- Android 10+ (`minSdk 29`) network foundation
- runtime Wi-Fi permissions
- local-only GoPro Wi-Fi connection using `WifiNetworkSpecifier`
- network-scoped HTTP calls to the legacy HERO8 address `10.5.5.9`
- `/gp/gpControl/status` verification
- legacy `gpStream` preview start/stop commands
- UDP/8554 preview receive probe
- periodic legacy GoPro keep-alive packets
- MPEG-TS PAT/PMT inspection to discover the video PID and codec
- H.264 SPS parsing to report the actual encoded preview resolution
- frame-start sampling to estimate preview FPS
- live UDP bitrate reporting in KiB/s and Mbit/s

The app intentionally does **not** bind the whole Android process to GoPro Wi-Fi. GoPro HTTP and UDP traffic use the `Network` returned by Android, leaving normal internet traffic available for TikTok/cellular routing later.

## Test on a real HERO8

1. Enable wireless connections on the HERO8 and note its Wi-Fi SSID/password.
2. Install/run the debug app on an Android phone.
3. Enter the GoPro SSID and password.
4. Tap **Kết nối HERO8** and approve Android's Wi-Fi connection dialog.
5. Tap **Kiểm tra HTTP 10.5.5.9**.
6. Confirm that the `gpControl` JSON response appears.
7. Tap **Start preview + UDP probe**.
8. Watch the stream diagnostics section.

A useful capture should report values similar to:

```text
packets=...
bytes=...
rate=... KiB/s (... Mbit/s)
container=MPEG-TS
codec=H.264/AVC
videoPid=0x....
resolution=1280x720    # example only; do not assume this value
fps≈29.9              # example only
```

The resolution/FPS shown by the app are derived from the actual incoming stream. They are not copied from the HERO8 recording setting. This is the quality gate before selecting the real rendering pipeline.

## Decision gate for preview quality

After testing a physical HERO8:

- If the legacy `gpStream` preview is high enough quality for TikTok, continue directly to hardware decoding/rendering.
- If it is limited to an unacceptable resolution (for example 720p), stop before building the final UI and investigate whether HERO8 exposes a higher-quality live-stream pipeline distinct from the normal viewfinder stream.
- Do not upscale a lower-resolution preview and call it 1080p; the app should report source resolution separately from display resolution.

## Roadmap

1. ✅ Cleanup Android template
2. ✅ Permissions + `GoProNetworkManager`
3. ✅ Connect HERO8 Wi-Fi
4. ✅ Verify HTTP communication (implementation; requires real-camera test)
5. ✅ Start HERO8 preview command (implementation; requires real-camera test)
6. ✅ Receive/probe UDP packets (implementation; requires real-camera test)
6.5. ✅ Inspect real preview container/codec/resolution/FPS/bitrate (implementation; requires real-camera test)
7. ⏳ Render MPEG-TS/H.264 with hardware decoding
8. ⏳ Fullscreen `BroadcastActivity`
9. ⏳ Test TikTok Mobile Gaming capture
10. ⏳ Auto reconnect + latency/stability tuning
11. ⏳ Minimal camera controls needed for preview operation
12. ⏳ Additional GoPro models

## Source layout

```text
app/src/main/java/com/example/gopro/
├── MainActivity.java
├── network/
│   └── GoProNetworkManager.java
└── camera/hero8/
    ├── GoProHttpClient.java
    ├── Hero8UdpPreviewProbe.java
    └── MpegTsStreamInspector.java
```

## Important

HERO8 uses a legacy control/preview path and is not treated as an Open GoPro camera in this project. The protocol assumptions and the actual preview quality must be verified against a physical HERO8 before the rendering layer is built on top of them.
