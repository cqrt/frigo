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

## Reviews come from `/api/review`, and the list is asked for one kind at a time

Frigate 0.18 groups detections into a review: one stretch of time, from the moment
an object appears in a zone until it leaves, with everything it saw written in the
row. `GET /api/review` returns them, singular, while events live next door at
`/api/events`. That mismatch is not a typo in this file.

The app asks for alerts and for detections separately rather than fetching a mixed
list. The endpoint orders by severity first and time second (`ORDER BY severity,
start_time DESC`), so a mixed page is all the alerts followed by all the
detections. Paging that by `before = oldest start_time` skips every detection
newer than the oldest alert, which is the sort of hole nobody reports and everybody
half-notices. One severity per request keeps the order honest and gives the list
its two buttons.

`after` goes out as `0` on every request. Left out, the server fills it with a day
ago (`params.after or now - 24h`), so the window never moves and "load older"
brings back nothing at all.

A review's picture comes from its first detection's event thumbnail. Frigate keeps
`thumb_path` for the review as well, but that is a path under `/media/frigate`
served by its web server, not an API route, so it is not something the app can ask
for with a token. The event thumbnail is the same picture, and it goes through an
endpoint that is already known to work.

The event player and the review player share `ui/components/ClipViewer.kt`. Both
screens were showing the same dialog with different titles, and the second copy
would have drifted from the first.

## Reviews, checked against a real 0.18.2 server

The Reviews tab was built on the mock and then run against a real Frigate 0.18.2
through a proxy, signed in as a restricted user with two cameras. What it answered:

- `start_time` and `end_time` arrive as epoch seconds with fractions
  (`1793418289.280371`), the same as events, not as datetime text. The app's
  numeric parse is right.
- `data` carries more than the app reads: `verified_objects`, `thumb_time` and
  `metadata`, which is null here. Parsing is lenient, so those are ignored.
- A sub label turns up as a person's name in `data.sub_labels`, which is where
  "Person, Angie" in the list comes from.
- The first detection's event thumbnail answers with a JPEG, and
  `/api/review/<id>/clip.mp4` answers `200 video/mp4`. Both work with the bearer
  token the app already sends.
- A review stays open for as long as something keeps being seen, so one can cover
  three hours. Its clip is the whole stretch, cut from recordings on request, and
  the app fetches the whole thing, which is why it stops at 400 MB. That is
  Frigate's answer, not a bug in the app, but it is worth knowing before tapping an
  old review on a phone plan.

The mock now follows what the real server does here.

## A restricted user only sees their cameras

`/api/config` answers with every camera on the server whatever role is asking, so
the first build showed a restricted user the whole house and a grid of tiles that
answered 403. `/api/profile` is the endpoint that knows better: it carries
`allowed_cameras`, the list the role permits. The camera list is now the enabled
cameras from the config, narrowed to that list. A user who may see everything gets
no list, which is read as all of them.

Alerts come from the same feed for every user, so the app listens to everything it
is shown and filters as before, by the cameras and objects ticked in Settings.

## A certificate the phone doesn't trust says so

Frigate serves its own self-signed certificate on 8971 unless it has been given a
real one, and a phone refuses it. That used to come out as "Can't reach the server.
Check your connection", which sends you looking at the wrong thing.

`SSLHandshakeException` and `SSLPeerUnverifiedException` are both `IOException`s,
so they have to be caught before the general case. A handshake failure now says the
phone doesn't trust the certificate and points at the proxy's address; a name
mismatch says the address doesn't match the certificate.

The proxy's address is the one to type when the server's own address is refused:
the certificate there is issued by a real authority, and the app reaches the same
server through it.

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

## The clip is fetched into a file, then played

Frigate cuts a clip out of your recordings while it sends it: no length, no support
for jumping about inside it, and the connection can go quiet for a while. Media3
calls a player that makes no progress for ten seconds stuck and stops it, with
`ERROR_CODE_TIMEOUT`. Over a home connection on a phone that was most clips.

So the clip is copied into `cacheDir/clips` by the app's own client and then handed
to ExoPlayer as a file. A download has no ten second watchdog, it reports how much
has arrived, closing the viewer stops it, and the player reads something complete:
it starts at once, seeking works, and a bad minute on the network costs a retry
rather than the clip. Files are kept for a day and reused, so watching one twice
costs one download.

Frigate's ffmpeg can leave a response open long after the last byte has gone, a
minute or more, so a quiet response ends the copy rather than failing it. What
arrived is a clip and it plays to where the server got to. A clip Frigate never
finished writing plays to that point and then says so, without offering a retry:
fetching it again gives the same short file.

Measured on the real server: thirteen of the fourteen newest alert clips came down
in 0.2 to 3 seconds over both the local network and the proxy, sizes from 0.3 MB to
32 MB, at up to 10 MB a second. The fourteenth, `1793420629.779106-0xx4wx`, stops
at 278 KB every time and holds the connection for another 90 seconds without
sending anything. Its first fragment declares 289 KB, so the file it would have been
is short by that much. It reads the same over the local network, so the proxy is not
the cause. The player gets one decoded frame out of it and then reaches the end.

Rejected: playing the stream as it arrives. That was the first version, and the ten
second watchdog is what it cost. It cannot be turned off, and it is the right rule
for a live stream that has gone quiet.

Rejected: the platform `MediaPlayer` and `VideoView`. `VideoView` cannot set a
request header, and `MediaPlayer` only reports failure as an integer, which is not
enough to tell "your session expired" from "the clip is gone".

The client used for the download has its own timeouts: fifteen seconds to connect
and thirty for a read. The read timeout is what ends a response that has gone quiet,
and thirty seconds is long enough that a slow fragment is not mistaken for the end.

Seeking is free now. The player has the whole file, so a jump is a jump inside it
rather than a new request that starts the encode over.

## A clip that fails says why

"That clip wouldn't play" was the branch the player fell into whenever it didn't
recognise the reason, and a report of that sentence alone can't be acted on. The
phone a clip fails on is not the one the app is developed against, so the failure
now carries its reason on screen.

The player looks down the whole chain of causes rather than at the first one, since
a proxy's answer and a decoder's complaint sit at different depths. What shows is
the player's own name for the failure, the status a server answered if there was
one, and the first thing said further down the chain, cut to ninety characters. It
sits under the sentence in small grey text, to be read out in a report.

The failures are also named rather than lumped together now: a decoder that won't
take the video, a proxy that answered a request with a page instead of a clip, a
request for the middle of a file that came back as the whole file, and a clip that
took too long to start.

The video matters here. A clip keeps whatever the camera recorded, because Frigate
copies the stream instead of converting it, so an H.265 camera gives H.265 clips and
a phone without a decoder for them can't play any of them. Every clip checked on
this server is H.265, Main profile, level 5.0.

Cost: Media3 takes the release APK from 1.6 MB to 2.4 MB.

## A clip's timings are rewritten so it plays

A clip Frigate cuts with `-c copy` keeps the recordings' timing rather than getting
new timing. On this server that timing is broken in three ways, and together they
stop a player.

- A sample can declare a length that is not a frame. One clip's first video sample
  claims 842,800,096 ticks, which at 90,000 a second is two hours and thirty-six
  minutes for a clip with five seconds of picture in it.
- Every fragment restarts its decode time at zero, so a player reading the file in
  order sees the same time arrive again and again.
- A track that declares no sample lengths, here the audio, leaves the length to its
  `tfhd` default, and that default stands for the whole clip. One is 74,915,565 at
  8,000 a second, which is two hours and thirty-six minutes.

The player takes the movie's length from its longest track, so the audio default
alone was enough to make a five second clip a two and a half hour one. ExoPlayer
held the first frame, then the phone gave up waiting: `Input dispatching timed out`.

So the app repairs the file after the download and before the player sees it. A
sample claiming more than two seconds, or more than four times the track's median
sample, is given the median instead. Each fragment is laid at the end of the one
before it on its own track. A `traf` that leaves every length to its default gets
that default set to the span of the other tracks in the same fragment, and any
default that claims more than two seconds is replaced. Then `mdhd`, `tkhd`, `mvhd`
and `mehd` are given the lengths that result, so the scrub bar tells the truth.

No picture or sound is touched: only the numbers that say when to show it. The
whole file is read and understood before anything is written, so a clip the walk
does not follow, such as one cut short mid-fragment, is left exactly as it arrived.
Reading a 25 MB clip takes about ten milliseconds.

Measured over nine clips from this server: five needed the repair. `beeeva`
declared 2:36:09 for 5.05 seconds of content and now says 00:00:05. `7zytxv`
declared 1:04:41 and now says 1:53. `9sopxg` declared 17:01:51 and now says 00:57,
which is the review it came from. All three play in the app. The four that needed
nothing are unchanged, byte for byte.

Rejected: remuxing the clip with ffmpeg. It writes a correct file, but ffmpeg is
between 15 and 40 MB in an APK and takes seconds per clip on a phone. Rewriting the
numbers in place does the same job to the same file in milliseconds.

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

