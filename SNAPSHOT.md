# Latest-frame snapshot endpoint (version 1.1)

While the existing live stream is running, request:

`http://127.0.0.1:8080/snapshot`

The response is the latest complete JPEG already published to the MJPEG server,
with `Content-Type: image/jpeg`, an exact `Content-Length`, and
`Cache-Control: no-store`. Before the first frame, it returns HTTP 503 with a
readable explanation. A snapshot neither starts capture nor changes an MJPEG
consumer's frame position. Network writes happen outside the shared frame lock.
The existing loopback binding, four-client limit and MJPEG endpoints are retained.

CORS allows the exact origins `https://blindza.com` and `http://localhost`.
Other origins, including localhost with a port suffix, receive no CORS permission.
Responses include `Vary: Origin`, and OPTIONS supports GET, Content-Type and an
allowed origin's Private Network Access preflight when requested. Browser local
network permission prompts are controlled by the browser.

From either allowed origin:

```javascript
const response = await fetch('http://127.0.0.1:8080/snapshot', {
  cache: 'no-store'
});
if (!response.ok) throw new Error(await response.text());
const image = await response.blob();
// Use this JPEG Blob as needed; revoke any object URLs after use.
```

Desktop socket tests performed against the actual server class:

- Valid JPEG bytes and dimensions, repeated snapshots and latest-frame changes.
- 48 snapshot requests alongside two active MJPEG connections; both consumers
  received all 12 published frames with their original sequence numbers.
- Immediate 503 before the first frame; CORS also present on that error response.
- Both exact allowed origins, denied unrelated/lookalike origins, OPTIONS,
  no-store and Private Network Access response headers.
- Existing stream, compatibility repetition, vOICe-style JPEG reader,
  simultaneous consumers, reconnect and stop/restart regression tests.

These simulate browser HTTP requests; they do not execute browser JavaScript.
The new endpoint still needs verification on the handset with the real primary
consumer and a browser open at an allowed origin. The original streaming hardware
tests described in README.md predate this endpoint.

Updated release: `dist/heycyan_live.apk`. The previous release is preserved as
`dist/heycyan_live_before_snapshot.apk`. No capture code, Android permissions or
dependencies were changed.
