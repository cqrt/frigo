# Mock Frigate

A fake Frigate server for working on the app without touching the real one.

```
python tools/mockfrigate/mock.py
```

Then point the app at it:

- From an emulator on the same machine: `http://10.0.2.2:8099`
- From a phone on the same network: `http://<this machine>:8099`

Sign in as `test` with the password `test`. Any other password is refused, on
purpose, so the error message can be tried too.

It serves five cameras, one of them disabled, three events, thumbnails, snapshots
and an endless MJPEG stream that cycles three frames.

The app assumes HTTPS unless you type a scheme, so include `http://` when you
point it at this.

## The frames

`frame0.jpg` to `frame2.jpg` are generated, not photographed. To make new ones on
Windows, see `make_frames.ps1`. They only need to be valid JPEGs of different
sizes, so any replacement will do.
