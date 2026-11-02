# Sound on the live view

The speaker button in the player turns a camera's sound on, and off again. Nothing is
downloaded while it is off.

Frigate's API has no sound in it. The sound comes from go2rtc, the stream server inside
Frigate, which listens on port 1984, and your proxy has to hand the app two paths on that
port.

In Nginx Proxy Manager, open the proxy host for your Frigate address, go to Custom
Locations, and add two of them:

| Location | Scheme | Forward Hostname / IP | Forward Port | Access List |
| --- | --- | --- | --- | --- |
| `/api/stream.mp4` | `http` | the same host the proxy host already uses | `1984` | Inherit from Proxy Host |
| `/api/stream.aac` | `http` | the same host the proxy host already uses | `1984` | Inherit from Proxy Host |

Those paths rather than something tidier because they need no rewriting. The app asks for
`/api/stream.mp4?src=door_cam`, and your proxy passes the same path straight to go2rtc.

**`/api/stream.mp4`** is the camera's own video with its sound in the same stream. It is
what the player shows, and it is why the live picture is sharp.

**`/api/stream.aac`** is the sound on its own. The app uses it only when the picture has
fallen back to Frigate's stills, which carry no sound.

Three things to know:

- Port 1984 has to be reachable from the proxy. As a Home Assistant add-on, Frigate
  publishes it with its other ports.
- go2rtc ignores the sign-in the app sends with these two paths. Anyone who finds them can
  watch and listen without signing in. Put an Access List on them if that matters to you.
- Frigate has no routes called `stream.mp4` or `stream.aac`. Asked for one, it answers
  "Access denied. A user with the admin role is required", which is what it says about any
  path it doesn't know.

Without the video path the player falls back to Frigate's stills after a few seconds and
says so under the picture. Without the sound path, the speaker button answers "No sound
came through" while the small picture is up.

The cameras send 8 kHz mono sound, and it arrives with the picture now rather than a moment
behind it.

The decisions behind this are in `docs/decisions.md`, under "There is no live sound through
Frigate's API" and "The live picture is the camera's own video".
