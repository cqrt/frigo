# Sound on the live view

The speaker button in the player turns a camera's sound on, and off again.
Nothing is downloaded while it is off.

Frigate's API has no sound in it. The sound comes from go2rtc, the stream server
inside Frigate, which listens on port 1984, and your proxy has to hand the app one
path on that port.

In Nginx Proxy Manager, open the proxy host for your Frigate address, go to
Custom Locations, and add one:

| Field | Value |
| --- | --- |
| Location | `/api/stream.aac` |
| Scheme | `http` |
| Forward Hostname / IP | the same host the proxy host already uses |
| Forward Port | `1984` |
| Access List | Inherit from Proxy Host |

That path rather than something tidier because it needs no rewriting. The app
asks for `/api/stream.aac?src=door_cam`, and your proxy passes the same path
straight to go2rtc.

Two things to know:

- Port 1984 has to be reachable from the proxy. As a Home Assistant add-on,
  Frigate publishes it with its other ports.
- Anyone who finds that path can listen without signing in. Put an Access List on
  the location if that matters to you.

Without the location the speaker button answers "No sound came through." and the
picture carries on as before.

The sound is 8 kHz mono, which is what these cameras send. It is a separate
stream from the picture, so it can sit a moment behind it.

The decisions behind this are in `docs/decisions.md`, under "There is no live
sound through Frigate's API".
