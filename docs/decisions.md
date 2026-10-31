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

