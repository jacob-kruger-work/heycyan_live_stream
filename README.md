# HeyCyan Live

Android app using the HeyCyan SDK and the live-preview commands documented in
https://gist.github.com/vortex1024/2a9e0bada7692900a3bb4285362d8a2a.
SDK source: https://github.com/ebowwa/HeyCyanSmartGlassesSDK.

Select paired glasses, then press **Start live stream**. Bluetooth controls the
glasses; Wi-Fi Direct carries the video. Keep Bluetooth, Wi-Fi and Location on,
and close other glasses apps. Approve any Android connection prompt. The app
requests precise Location because Wi-Fi Direct discovery can require it.

The foreground service continues after pressing Home. Its notification includes
**Stop live stream**. The local endpoint is `http://127.0.0.1:8080/mjpeg`.
Only apps on this handset can access it. The consumer must allow local HTTP.

The decoder handles RTSP/TCP H.264, including in-band SPS/PPS, single NAL units,
STAP-A and FU-A. It publishes at most one fresh JPEG per second, resized to
1600 x 1200 if necessary and compressed below 1,000,000 bytes. It holds only the
latest frame in memory. It does not save camera images to the gallery. The local
server supports up to four simultaneous consumers.

The interface uses native labeled Android controls, a heading, a selectable URL,
a copy-address button, and a read-only status region. Status announcements can
be switched off to avoid interfering with a consuming accessibility app. Frame
counters update separately without automatic announcements.

## Build on this desktop

Run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/build-release.ps1`.
This runs Gradle `assembleRelease lintRelease`, then writes `dist/heycyan_live.apk`.
The script uses the existing JDK 17 and Android SDK under
`C:/heycyan/tools/android-build/`, and a workspace-local Gradle cache. Adjust
`local.properties` and the script to use another toolchain.

The release APK is signed with a locally generated key in `tools/release.jks`.
Preserve that file for future upgrades. This local development key is not a
production publishing credential; its password is in `app/build.gradle`.

## Verification

`tools/MjpegServerTest.java` checks complete multipart frames, simultaneous
consumers, no fabricated frames, immutable frame data, reconnects, invalid
paths, shutdown and restarting the endpoint. Compile it alongside
`app/src/main/java/org/heycyan/live/MjpegServer.java` with JDK 17 and run
`org.heycyan.live.MjpegServerTest`.

On the connected S24 Ultra, the app is installed as `org.heycyan.live` and the
paired glasses identify as `M02_C28F`. Hardware tests received eight changing
1600 x 1200 JPEGs in the foreground and another eight after pressing Home.
The measured average intervals were 0.996 and 0.976 seconds, with JPEG sizes
between 104,186 and 132,399 bytes. These are short functional tests, not a
long-duration or exhaustive TalkBack audit.

Prefer the phone as Wi-Fi Direct group owner (`groupOwnerIntent = 15`). In the
tested M02 firmware, joining a group owned by the glasses connected successfully
but left RTSP port 8554 closed. With the phone as group owner, the glasses report
their IP and serve `rtsp://<glasses-ip>:8554/ch0`. RTSP OPTIONS and compound RTCP
receiver reports are sent every two seconds; this resolved the initial stream
stall seen after three frames.

`tools/check-stream.py` verifies eight actual frames, JPEG dimensions and sizes,
changing content, advancing sequence numbers and average delivery interval.
Use it with `adb forward tcp:18080 tcp:8080` while the app is streaming.

App diagnostics use Android log tag `HeyCyanLive`. Failures stop capture and
release resources; the app retains an accessible error message. Stop sends
`02 01 15 01` and removes the Wi-Fi Direct group. Start sends `02 01 14 01`, and
the service sends `syncHeartBeat(4)` every five seconds.
