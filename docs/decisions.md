# Decisions

Why the app is built the way it is. Things that were tried, rejected, or measured.

## Sign-in is a JSON body and a cookie, which is what 0.18 wants

Frigate 0.18 takes the sign-in as JSON:

```
POST /api/login
{"user": "...", "password": "..."}
```

It answers with an empty body and the session as a JWT in a `Set-Cookie` header.
There is no `/api/refresh` endpoint; the token lasts a day, and `/api/auth`
lengthens it when a request arrives inside the last half hour.

Rejected: a form post with `username` and `password`, which is what the app did
first. Frigate validates the body against a model, so a form posted to 0.18 comes
back as `422 Unprocessable Entity` with no hint about which field was wrong. That
is the error that showed up on a real server and could not show up against the
mock, because the mock had been written to match the client.

The JWT is read out of the cookie rather than from a field in the body, and its
name is left to the server, since `auth.cookie_name` is configurable. Any cookie
value shaped like a JWT (three parts, dots between) is taken.

That token then goes in an `Authorization: Bearer` header on every request.
Frigate's own nginx forwards that header to its auth subrequest, so the bearer
token works for the API, the stills and the MJPEG stream alike, without the app
holding a cookie jar.

## Alerts come from Frigate's message feed, not from polling

Frigate publishes every new event to a websocket at `/ws`, and its own web page
listens there. The app does the same: one socket, opened with the bearer token,
and each message is a JSON envelope of `topic` and `payload`. New events arrive on
`events` with `type` of `new`, `update` or `end`, and only `new` sets the phone
off, because `update` follows every time a detail changes and `end` is the object
leaving.

The topic name cost a bug. Frigate's MQTT topics carry a `frigate/` prefix, and
the socket sends the bare name: `events`, `reviews`, `stats`. The app looked for
`frigate/events`, and the mock sent that, so the mock agreed with the app and
neither agreed with the server. Nothing arrived, ever. Both names are accepted
now, and the mock sends what 0.18 sends. That is the second time a mock written
from the client's assumption hid a real incompatibility, after the login body.

Rejected: asking `/api/events` every few seconds. It would need the app awake to
poll, it would use battery all night, and it would arrive up to a poll interval
late. The socket is silent until something happens.

Rejected: Frigate's own notifications. 0.18 sends those over web push, which needs
a browser vendor's push service and a service worker; a native app cannot register
with it (`frigate/comms/webpush.py` builds those pushers from browser
subscriptions). The feed is the same source the web page reads, so the phone gets
the same thing without a vendor in between.

Events rather than reviews: a review carries `severity`, but an event carries
`label` and `camera` directly, which is what the filter needs.

## The watcher is a foreground service with the specialUse type

Android stops an app's network work once it goes into the background, so the
socket can only be held from a service, and a service can only run that long with
a notification on screen. That is the quiet "Watching for alerts" one.

The type is `specialUse`, with the reason written into the manifest: the app holds
a connection to a server the user runs. `dataSync` would have fit the shape of the
work, but on Android 15 and later it is capped at six hours a day, and an alert
that stops at six hours is not an alert.

Two consequences are handled: the service reads the saved sign-in from DataStore
itself, because after a phone restart it starts before any screen has run and the
in-memory copy is empty; and a `BOOT_COMPLETED` receiver starts it again, so the
switch means alerts keep working after a restart without opening the app.

## Twenty seconds of quiet per camera

Frigate starts a new event for the same visitor when they move between zones or
reappear after a gap, so a naive notification per event buzzes three times for one
person walking to the door. Twenty seconds per camera is long enough to swallow
those, short enough that a second person a moment later still gets through.

The cost is that a genuinely different person within twenty seconds of the first
on the same camera is silent. Sounding once for a visitor is better than sounding
three times.

## Each alert carries its own tap

`PendingIntent` identity ignores extras: two intents with the same action and
component are the same pending intent, and `FLAG_UPDATE_CURRENT` overwrites the
extras. Sharing one request code across alerts meant every alert in the shade
opened the newest camera. Each notification now takes the next id and uses it as
the request code too.

## An alert's camera sits on top of the grid

The app used to make the tapped camera the first screen, so there was nothing
behind it. The back arrow called `popBackStack()`, which had nothing to pop and
did nothing, and the only way out was to close the app. The grid is now always
the first screen and the camera is pushed on top, so back lands on the grid with
the tabs back.

Tapping an alert while the app is already open used to make a second copy of the
screen, which is the same bug wearing a different hat: the new copy also had
nothing behind it. `MainActivity` is `singleTop` and reads the camera in
`onNewIntent`, so the tap arrives as a new intent at the screen that is already
there. The camera is state rather than a value read once at startup, and it clears
itself once the app has opened it, because a leftover value would send you back to
that camera every time the screen was rebuilt.

## An event's clip is cut from recordings, not stored per event

Frigate 0.18's `/api/events/{id}/clip.mp4` looks up the recordings that cover the
event and cuts them with ffmpeg while it streams the result. It is not a file per
event, so three things follow:

- A clip needs recordings switched on in Frigate, and it disappears when the
  recordings age out. The still is kept separately and outlives it.
- An event with `has_clip` false answers 404 "Clip not available". A range with no
  recordings under it answers 400 "No recordings found for the specified time
  range". The app treats both as "no clip for this one" and shows the still, since
  nothing you could do in the app would bring it back.
- The encode happens on request, so the first frame takes a moment to arrive.

## The clip plays as it arrives, which is why Media3 is in the build

The player is ExoPlayer with a `DefaultHttpDataSource` whose default request
properties carry the bearer token. The clip endpoint sits behind Frigate's auth
like everything else, and the player has to make its own requests, so the token
goes on every one of them rather than being attached to a single call.

Rejected: downloading the clip to a temp file and then playing it. Frigate has to
encode the clip before it sends anything, so the wait would sit in front of the
player with nothing to show, and the file would need cleaning up afterwards.

Rejected: the platform `MediaPlayer` and `VideoView`. `VideoView` cannot set a
request header, and `MediaPlayer` only reports failure as an integer, which is not
enough to tell "your session expired" from "the clip is gone".

Two settings are there because of how the endpoint behaves. Load errors are not
retried: with the default policy a server that is switched off spun for about
eighteen seconds before saying so. And the connect timeout is eight seconds,
because Frigate can take a while to produce the first byte, but a host that is not
answering should not hold the screen.

Seeking is not free. Frigate streams a file it is writing, with no range support,
so a jump back or forward asks again and the encode starts over from the seek
point. That is fine over a phone on the same network as the server, and it is why
the player is a plain scrub bar rather than a frame-accurate timeline.

Cost: Media3 takes the release APK from 1.6 MB to 2.4 MB.

## Zoom scales the frame the phone already has

The pinch is a scale and a translation on the picture, done by the GPU. The
stream is untouched: it keeps sending the same frames at the size the menu asked
for.

Rejected: asking the server for a cropped region of the camera as you pinch. It
would mean a request per gesture, the stream would restart at each one, and
Frigate's MJPEG endpoint has no crop parameter anyway.

The zoom belongs to the screen rather than to a frame. A new frame arrives every
moment, so state held per frame would be thrown away once a second, and the view
would jump back to the whole picture while you were still pinching.

The picture can be dragged only until its edge meets the edge of the screen. Any
further and you are looking at black, which looks like the app broke rather than
like the edge of the camera.

The cost is softness: these are 720p frames, so a 4x zoom is 180 pixels stretched
across the screen. That is what the player's 1080p option is for.

## The stream takes `height`, not `h`

Frigate 0.18 names the query parameter `height` on both `/{camera}/latest.jpg`
and `/{camera}`. The app used `h`, which FastAPI ignores, so the server quietly
served 360 pixels at 3 frames a second whatever the menu said. Found by reading
the query models rather than by noticing the picture looked soft.

## Live view is MJPEG, not WebRTC as the default

Frigate's own docs make the argument: MSE is their default because it runs over
TCP and every frame arrives, while WebRTC drops late frames, which on cellular
shows up as frozen or corrupt video. An Android app cannot use MSE, so the
closest thing with the same guarantee is Frigate's JPEG stream on
`/api/<camera>`.

Rejected: WebRTC first, MJPEG as the fallback. It would have needed port 8555
forwarded before the app showed anything, and the failure mode is a black screen
that looks like a bug.

Open: a WebRTC toggle in the player. The server side is already prepared
(`webrtc.candidates: stun:8555` in the user's config), so it is only the client
that is missing.

## Live view goes through the existing HTTPS proxy

Measured on the user's setup: `/api/north_cam?h=720&fps=5` plays through Nginx
Proxy Manager and Cloudflare on mobile data. That means no second entry point
into the house, no extra DNS record, and nothing to configure in Cloudflare.

The cost is bandwidth, since every frame is a whole JPEG. The player offers 1080p
down to 480p and 2 to 15 frames a second, and the grid asks for 360p stills once
a second.

## OkHttp 4, not 5

OkHttp 5.5.1 requires compileSdk 37. This project is on AGP 8.12.0, which tops
out at 36. The API differences did not matter for four endpoints.

## No image loading library

Coil would be the usual choice. Instead `ImageCache` is an `LruCache` of decoded
bitmaps, filled by a coroutine, because the app also hand-writes the MJPEG frame
reader and the JPEG decode path either way. One less dependency and no version
guess.

## Polling stops when the app is not in front

The camera grid and the player run their loops inside
`repeatOnLifecycle(RESUMED)`. Without that, a grid of four cameras would keep
requesting a frame a second while the app sits in the background.

## Toolchain is pinned to the one on this machine

Gradle 8.13, AGP 8.12.0, Kotlin 2.2.10, compileSdk 36, and the Gradle daemon on
JDK 23 (set in `~/.gradle/gradle.properties`, not in the repo). The JDK this
machine has on `PATH` is newer than Gradle 8.13 supports.

## Package name

`nz.mckenzie.frigo`, matching the other Android project on this machine. The
repository is `cqrt/frigo`.

## Releases are built on GitHub, from a tag

The phone has no Android SDK on it, and a release you can install has to be
signed with the same key every time or Android refuses to update it. Both point
the same way: the build belongs on a runner, and the key belongs in a secret.

Rejected: building releases on this machine and uploading the APK by hand. It
works, but the version code is then whatever was typed, and the release notes
come from nowhere.

The version code is derived from the version in the tag (`0.2.1` -> 201) rather
than from the workflow's run number. Run numbers start at 1 and climb, so a
runner number would sit below APKs already built locally, which Android treats
as a downgrade.

The signing key was generated on this machine and copied to the repository's
secrets. `~/.frigo/frigo-release.jks` and the gitignored `keystore.properties`
are the only copies outside GitHub. There is no second chance to sign an update
with a lost key: an APK signed with a new key will not install over the old one.

## CI builds both APKs

`ci.yml` runs `assembleDebug assembleRelease` on every push and pull request. The
release build is minified, so the shrinker rules are tested on a runner rather
than on the phone.

