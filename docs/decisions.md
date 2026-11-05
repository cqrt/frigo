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

## A watched review is marked on the server, and hidden by default

Frigate keeps the watched flag itself, so the phone sends it rather than keeping a
copy: a post to `/api/reviews/viewed` with the id and `reviewed: true` answers 200
and the web page drops the review from its list too. Checked against a real
0.18.2 server:

- `reviewed=0` returns only the reviews still to watch, `reviewed=1` only the
  watched ones, and leaving the parameter out returns both, newest first.
- A review can be marked and unmarked again, so the flag is a switch rather than
  one way.

Past halfway is when Frigate's own page counts a review as watched, so the app
does the same. The clip player asks its position twice a second and marks once
the position is half the reported length, which means a clip watched for a few
seconds stays in the list.

The list asks for `reviewed=0` and leaves the parameter out when the switch is
on, so the server does the filtering and the paged list stays right as older
pages arrive.

Rejected: marking as soon as the clip opens, which would clear a review from the
list before it had been watched; and a separate Reviewed button, which asks for a
tap for something the server already counts.

## Explore filters, checked against a real 0.18.2 server

The events tab is called Explore now, which is the name the server's page has,
and it filters the way that page does. What the API accepts:

- `after` and `before` are epoch seconds, and either end cuts the list. A day is
  the first second of that day on the phone's calendar to the first second of the
  next.
- `labels` and `sub_labels` are sent as one comma-separated value. Repeating the
  parameter does not add up: `labels=person&labels=glass` answers with glass
  alone, because the server keeps the last one. `labels=glass,speech` answers
  with both.
- `/api/sub_labels` answers the names the model has learned: "Angie", "Diandra",
  "Fay" and "Matt" on this server. That is what the People filter lists.
- `/api/labels` includes the audio labels ("glass", "speech") beside "person", so
  the label list has things in it that no event of a camera will ever carry.

Rejected: a from and to date the way the server's page has them, which is two
calendar trips to look at one day. The chips offer today, yesterday, and a
calendar for anything else.

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
costs one download. A file already on the phone is checked and repaired the same
way when it is opened, so a clip downloaded by an older version is fixed rather
than fetched again.

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
before it on its own track.

A `trun` can leave the lengths out and leave them to the `tfhd` default. That
default is worth believing only when it is a believable length: the sound track's
1024 ticks at 8,000 a second is right, and the two-hour ones are not. A default
that is believable is used as it stands. One that isn't gets the span of the other
tracks in the same fragment, and the default is rewritten to match. Then `mdhd`,
`tkhd`, `mvhd` and `mehd` are given the lengths that result, so the scrub bar tells
the truth. A clip whose samples already say the right thing but whose header says
it is no length at all gets only those header numbers filled in.

No picture or sound is touched: only the numbers that say when to show it. The
whole file is read and understood before anything is written, so a clip the walk
does not follow, such as one cut short mid-fragment, is left exactly as it arrived.
Reading a 25 MB clip takes about ten milliseconds.

Measured over eleven clips from this server: four had sample lengths and clocks that
needed changing, all eleven said nothing about their own length, and every one kept
its picture and sound byte for byte. `beeeva` declared 2:36:09 for 5.05 seconds of
content and now says 00:00:05. `7zytxv` declared 1:04:41 and now says 1:53.
`9sopxg` declared 17:01:51 and now says 00:57, which is the review it came from.
`opxb12` is 40 seconds long and plays as 00:40.

The first version of this got `opxb12` wrong, and it is worth recording why. Three
of its sound fragments leave their sample lengths to the default of 1024 ticks. The
repair read those as unknown, fell back to the video's span of two seconds, and
gave every sample in them two seconds: 39.8 seconds of sound became 104.4, and the
fragments after the third were laid thirty seconds into the future. The sound's
clock jumped ahead of the picture's, so the player dropped every frame whose time
had passed: the picture stopped while the sound went on. The clip plays correctly
on a desktop because a desktop never sees that file; it is ours.

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

The version code is derived from the version in the tag (`0.7.3` -> 703) rather
than from the workflow's run number. Run numbers start at 1 and climb, so a
runner number would sit below APKs already built locally, which Android treats
as a downgrade.

The last number counts up to 99 before the middle one moves, so a run of small
releases stays inside one minor version. The code gives each part two decimal
places, so `0.7.100` and `0.8.0` are both 800, and the workflow refuses a part
above 99 rather than publish a release with another one's code.

The signing key was generated on this machine and copied to the repository's
secrets. `~/.frigo/frigo-release.jks` and the gitignored `keystore.properties`
are the only copies outside GitHub. There is no second chance to sign an update
with a lost key: an APK signed with a new key will not install over the old one.

## CI builds both APKs

`ci.yml` runs `assembleDebug assembleRelease` on every push and pull request. The
release build is minified, so the shrinker rules are tested on a runner rather
than on the phone.

## The quiet notification follows what you tick

The notification under "Watching for alerts" counts the cameras you ticked. Two
things were wrong with it. It only changed when the connection changed, so
ticking a camera left the old words up. And after a phone restart the service
started before any screen had run, so it had no choices in memory at all: it said
nothing was ticked and nothing buzzed.

It now reads the saved choices when it starts and redraws itself whenever they
change. It also names the list that is empty, cameras or objects, rather than
blaming the cameras every time.

## Two camera layouts, one button

The home page has two looks behind one button: two to a row, and one picture
filling the width with its name on a dark fade. The fade is there because a
camera pointed at a bright wall would leave white text unreadable.

The icon shows the look you get next, not the one you're in, so a tap always
moves you somewhere you can see coming. The choice is kept on the phone.

There was a third look, one camera to a line with a small picture and its name
beside it. It went because nobody used it: the line spent the width on the name
and left the picture the size of a thumbnail, and the grid already reads the
same at a glance. With two looks the button is a plain swap, so a tap never
goes through a look you didn't want.

The wide look keeps the number 2 that it was saved under, so a phone that was
on it stays on it. Anything else saved reads as the grid.

Material's own icon set has no play mark in a box, no magnifying glass with room
for a label, and no wide frame, so Reviews, Explore, the grid and the wide marks
are vectors drawn in `ui/Icons.kt`. The depth in the Reviews mark is one path at
22% opacity, tinted along with everything else.

## Pulling the list down instead of a refresh button

The pictures already come in about once a second, so the button had almost
nothing to do while it only reloaded the list of cameras. It now makes every
camera fetch a picture at once, which matters on a slow link where the one on
screen is a while old. Counted against the mock: four cameras, one request each,
as the button is pressed.

That button is gone. Pulling the list down does the same thing, and the same
gesture is used for the same purpose on Review and Explore, where there was a
button that did it too. The bar keeps only what changes the screen you are
looking at, which on Cameras is the layout button.

Counted against the mock after the change: one pull on Cameras costs one
`/api/config` and one `/api/profile`; one pull on Review costs one `/api/review`;
one pull on Explore costs one `/api/events`. Nothing else goes out, so the
gesture refreshes the list and does not restart the pictures' own second.

"Try again" buttons stay where they are. They appear when there is nothing on
screen and the request failed, which is a different job from pulling a list that
is already there.

## Fullscreen means the phone on its side

Filling the screen first meant hiding the bars in portrait, which leaves the
picture in a letterbox with black above and below it. A camera picture is wide,
so fullscreen now turns the phone to landscape as well: the bars go, the picture
reaches every edge, and the whole screen is the camera.

Two calls do it. `HideSystemBars` takes the status and navigation bars away and
puts them back on the way out. `Landscape` asks for a landscape orientation and
hands the phone back afterwards.

The inset controller comes from the view rather than the activity, because a clip
plays in a dialog with a window of its own and the activity's controller would
hide the wrong bars. `ViewCompat.getWindowInsetsController(view)` is deprecated in
core 1.17 in favour of a call that takes the window, and it is kept on purpose
with a note. Below API 30 it falls back to the activity's bars, which leaves a
dialog's bars up rather than breaking anything.

The manifest already declares `configChanges="orientation|screenSize|screenLayout|
keyboardHidden|uiMode"`, so the rotation is handled in place. The screen is not
rebuilt, the stream is not reopened, and nothing flickers.

The exit mark floats in the top right over the picture, because the bar it would
have sat in is gone. Back leaves fullscreen first on the live player and closes
the clip on a clip, so Back never does two things at once.

Checked on the emulator both ways, portrait to landscape and back, on the live
player and on a clip: bars away, picture edge to edge, exit mark reachable, Back
returning to portrait. The system's "Viewing full screen" notice appeared once
and was dismissed; it shows again only after the app has been away for a while.

## Live pictures on the home page

The tiles take one picture a second, which is cheap enough to leave on all day.
That is also slow: someone walking past appears a second late, and the picture
jumps.

There is now a switch in Settings, off to begin with, that plays each tile from
the same MJPEG stream the player uses, at five frames a second and 360p. Five
frames a second is enough to follow a person and far short of what a tile a
quarter of the screen wide can show at that height.

The choice is a boolean in DataStore read as a flow, so the tiles change as soon
as the switch moves rather than at the next launch.

A camera the server will not stream falls back to a picture a second. Two failed
attempts in a row is enough to know, and a still always works, so a tile that
cannot stream shows something rather than staying grey.

It is a switch rather than the default because four open streams cost a great
deal more of both the phone's data and its battery than four pictures a second.
It sits under "Live View" in Settings, which says one sentence about the cost.
Measured on the emulator: with the
switch on, all four tiles open `/api/<camera>?height=360&fps=5` and stop asking
for `latest.jpg`; with it off, `latest.jpg` comes round again.

## There is no live sound through Frigate's API

The web page has sound, so the assumption was that the app could have it too.
It cannot, and the reason is where the sound comes from.

`/api/openapi.json` lists all 167 routes on the server. Two of them mention
go2rtc, `/go2rtc/streams` and `/go2rtc/streams/{stream_name}`, and both report
stream state rather than carrying media. There is no audio, no talk, and no
media route at all. The web page gets its sound from go2rtc's own port (1984),
which Frigate's API does not proxy.

Making the account an admin does not change this. The admin role opens
`/api/go2rtc` itself, which turns out to be Frigate's path for a camera named
go2rtc, and it answers "Camera not found". Every media path under it, with or
without a dot, comes back `{"detail":"Not Found"}`, including `stream.mp4`,
`stream.aac`, `stream.m3u8` and `frame.jpeg`. The reverse proxy passes only
dot-free paths under `/api/go2rtc/`, so nothing on the go2rtc port is reachable
through the address the app uses.

So a sound toggle would be a switch that sometimes does nothing, which is worse
than no switch. Either the go2rtc port gets its own hostname through the proxy,
or live view stays silent and the app says nothing about sound.

## The sound reaches the app under one more path

The proxy now hands one path to go2rtc's port: `/api/stream.aac`. The app asks
for `/api/stream.aac?src=door_cam` on the address it already uses, and that path
goes to port 1984 untouched. Everything else still goes to Frigate.

That path rather than a tidier `/audio/` because a custom location in Nginx Proxy
Manager sends the path as it arrives. `/audio/api/stream.aac` would reach go2rtc
as a route it does not have. `/api/stream.aac` arrives as itself, needs no
rewrite, and cannot collide with a Frigate route because there is no such one.

Measured against the live server: `200`, `audio/aac`, 26 kB in six seconds, which
is about 34 kbit/s at 8 kHz mono. All four cameras answer, and the three real ones
carry sound; the printer has none, so its stream is silent.

The sound is played by a second ExoPlayer of its own, made when the switch goes on
and released when it goes off, so nothing is downloaded while it is silent. It
carries the token as well as the URL: go2rtc ignores it, but a proxy access list
later would not.

Checked on the emulator: the switch opens the stream once, an audio track goes
active in the mixer, the caption reads "MJPEG · 720p · 5 fps · sound", the switch
survives going into fullscreen, and turning it off logs the player's release.
Mocked as a server without the path, the switch falls back on its own and says
"No sound came through." That caption has since gone from the picture.

The sound sits behind the picture by however long go2rtc takes to fill its
buffer, which is small but not nothing, and the two streams drift apart for as
long as the view is open. Lip sync was never going to be right on a doorway
camera at 8 kHz, so the app does not try to correct it.




## Turning the phone is the way into fullscreen

The player had a frame button that turned the screen on its side. It was a button
for something the phone already does, and it forced the rotation the other way
when you left, which could leave the phone on its side while the rest of the app
was upright.

Now the layout follows the phone. On its side, the camera or the clip fills the
screen and the bars are gone; upright, the bar comes back. The button, the two
vectors it used and the back handler that left fullscreen are all deleted.

The first time it happens on a phone, Android itself says "Viewing full screen,
to exit swipe down from the top". That is the system's own notice about hidden
bars, shown once, and the app does not repeat it.

On its side there is no bar to hold the sound switch or the way out, so both sit
on the picture: sound at the top right for the camera, a close mark at the top
right for a clip. The caption about the picture stays portrait only, because it
is the sort of thing you read before you turn the phone, not after.

## Settings says what it does, in one sentence

Settings had paragraphs under its switches: three about battery, three about live
view, and a switch labelled "Tell me when something is seen". Clearing all of it
went too far the other way, so the page now gives each section one sentence and
nothing more.

"Notifications" and "Live View" are the two headings. Under each, a sentence says
what the switch does, then "Enable" and its switch. Cameras and Objects come as
chips, not a list of switches, because there are few of each and a chip reads at a
glance. The battery paragraph is about the phone's settings rather than this
screen, so it has to point somewhere, and the button under it does the pointing.

The words are the user's own, with "tap" where they had written "click", because
there is no mouse.

## Every still carries a different address

The home page tiles take a picture a second, and on the real server they stayed
on the same picture for as long as the page was open. Pulling made no difference,
which was the clue: the app was asking again, and getting the same bytes back.

Nothing in the app caches them. The address it asked for never changed, and the
server sits behind Cloudflare, which caches a .jpg under its address. The first
picture answered for every request after it.

So each still now carries a timestamp in its query string, ?height=360&t=....
Frigate ignores the parameter. Anything caching the picture no longer has an
address to cache it under, and the tile shows what the camera sees now.

## The colours follow a choice, not the wallpaper

The app used to take its colours from the phone and only from the phone. System,
Day and Night now sit under Appearance in Settings, and System is what it does
until you pick something else, so nothing changes for anyone who leaves it alone.

The choice is an ordinal in the existing preferences file, so it outlives a
restart and needs no migration: no key means System. The value is read in
Activity's setContent, above everything, because the sign-in page is themed too
and there is no point in a dark app with a white door.

Choosing Day while the phone is in night mode keeps the app light, which is the
whole reason the choice exists: a phone that dims with the sun is not much use
with a camera view you want lit.

## A sideways swipe moves to the next camera

Standing in one camera and wanting the next meant going back to the grid first.
So a swipe left or right in the player moved on, in the same order the grid shows,
and the last camera led round to the first so a swipe was never a dead end.

The player already has a pinch to zoom and a drag to move around the picture, and
a sideways drag is exactly what you do while zoomed in, so the swipe kept out of
the way when the picture was zoomed and only took the events once a drag was
clearly sideways and longer than the touch slop.

**It has gone.** The other cameras now sit under the picture, a fresh still of each,
on the live view the same as on a recording. A tap does the job the swipe did, and
does it better: you can see which camera you are going to before you go, you can
skip two at once, and on a recording you take the moment you are watching with you
rather than being dropped into the stream. One gesture on both screens also stops
the two from meaning different things, which the swipe did: a swipe went to the
stream, and a tap on a tile stayed on the recording.

A still rather than a small stream per camera: the stream you are watching is most
of what the connection carries, three more would have to share the rest, and a
picture that changes every couple of seconds is enough to see someone walk in.

Picking a camera still changes what the screen is showing rather than navigating, so
Back lands on the grid you came from, and each camera keeps its own zoom and its own
sound switch. The sound goes off with the camera, because the next one may have none
and a switch that says on while nothing plays is a lie.

## A day's recordings as a bar you drag along

Frigate keeps the footage and hands out any stretch of it, so the app does not
store or index recordings of its own. The clock in a camera's bar opens three
things: the days that camera kept, the stretches within a day, and a still of the
moment under your finger.

The days come from /{camera}/recordings/summary with the phone's timezone, so an
hour of 10 in the answer means ten o'clock where you are. The stretches come
from /{camera}/recordings?after=&before=, and only the stretches are drawn, so
gaps in the footage show as grey rather than a bar that claims to cover the day.
Frigate reports the hours in the summary too, with how much motion and how many
objects were in each, but drawing the stretches is the honest shape of it: a
summary hour with 300 seconds of recording in it is a nearly empty hour, not a
full one.

The marker snaps to fifteen seconds. A day across the width of a phone is about
four minutes a pixel, so finer steps would be a lie about what a finger can pick,
and the time underneath says exactly where it landed.

Play asks for two minutes from where the marker is, less fifteen seconds of
lead-in, clamped to the day and to now, and hands it to the event viewer, which
already fetches a clip before playing it. Frigate cuts that clip on request, so
the first minute of a request is ffmpeg working: the still of that moment goes up
first, which is also what you look at while you drag.

The marker can sit in a gap. Rather than ask for a clip that comes back empty, the
screen says nothing was recorded then and leaves Play greyed out. The marker
starts at the last moment recorded that day, not at midnight, because the end of a
day reads as 00:00 on the clock and looks like tomorrow.

Checked on the emulator against the mock: three days of chips, stretches with two
gaps, the marker following a tap and a drag, the still arriving after the finger
settles rather than on every step, playback of a stretch, Play greyed over a gap,
Back returning to the live view with the tabs still hidden, and the screen's own
Try again recovering once the mock was back.

## The picture follows the finger, and so do the other cameras

A still that appears when the finger stops is not what the server's own page does:
there, dragging plays the day back like a film sped up. The screen now asks for a
new picture on every step, with the requests conflated: one is in flight per
camera, and when it lands the next asks for wherever the finger has got to. A slow
server therefore sets the pace, and a fast one cannot run away with it.

It can run away without a floor, which it did. The first version asked for 497
pictures in eight seconds against a mock that answers in a millisecond, and every
one of those is an ffmpeg job cutting a frame out of the recording on the server.
So there is now a gap of 120 ms under the big picture, eight a second at most, and
400 ms under the small ones, two and a half. On a real box the round trip is the
slower of the two anyway; the floor only bites when the answer is instant.

The pictures are not cached. A frame belongs to one moment, and the 16 MB image
cache is better spent on thumbnails you scroll back past.

The flow that feeds it took two goes to get right. Handing `rememberUpdatedState`
a lambda and reading it inside `snapshotFlow` re-emits on every recomposition,
because the lambda is a new object each time and a state holding it always looks
changed. Each emission started another fetch, each fetch recomposed, and the loop
fed itself. Holding the moment in the state instead means a value that only looks
new when it is new: nought requests while sitting still, ten a second while
dragging.

The three other cameras are drawn under the big one, a third of the width each,
with their names under them, all four at the same moment. They ask for a 360 line
rather than 720, since nobody is looking at them closely. A camera with nothing
recorded then comes back empty and shows as a blank tile, which is useful in
itself: it says which camera caught the thing.

The big picture is also blank when the moment falls in a gap of the camera you
opened, and it is not asked for at all then. The stretches for that one camera are
already known, so there is no reason to spend a request finding out what the
answer is, and no chance of an old frame sitting under a new time.

Checked on the emulator against the mock: the time and all four pictures changing
step by step through a drag, nought requests over ten idle seconds, a blank big
picture over a gap with the three small ones still showing, and Play greyed there.

## The day's bar moves onto the camera screen

The bar was a screen of its own, opened by a clock in the camera's bar. Every look
back meant leaving the live picture, and the live picture is where you start when
you want to see what just happened. So the bar now sits under the live view on the
camera screen, and the screen, the route and the clock icon are gone.

The picture the bar draws is the one the screen is about. Live video while you are
live, and the recording at the marker once you touch the bar. A pill on the picture
says Live and takes you back to the stream, which is the only way to get there
again. On its side the bar goes and the stream keeps running: there is nothing to
drag with the phone sideways, and leaving a still up that you could not move would
be worse than showing the camera.

While live the marker sits at now, stepped every fifteen seconds, and Play offers
the two minutes up to now. That keeps the time under the bar true and gives Play a
meaning in both states: what just happened while live, what led up to the marker
once you have dragged.

The height of the picture is whatever is left after the bar and the strip of other
cameras, and the picture is centred in it rather than top-aligned, which is what it
was doing before the bar arrived to make the leftover space obvious.

## Spikes, from the reviews the server already keeps

Frigate's own page marks a day with a coloured strip of what it flagged, and the
bar was saying only where the footage was, not where anything happened. The spikes
are Frigate's reviews for that one camera and that one day: /api/review with
cameras, after and before. Alerts are red and stand taller; detections are amber and
sit low, so a quiet hour of detections cannot bury an alert in it.

Reviewed and unreviewed both come back. The bar is a record of the day, not a list
of things still to watch, so marking a review watched does not rub it out of the
history.

No limit is sent. The endpoint takes one, and Frigate orders alert kinds first and
newest first inside each, so a limit would quietly drop part of the day rather than
the end of the list. A day's reviews on one camera is a small answer anyway.

The request sends whole seconds. A double put straight into a URL comes out as
1.7934444E9, and the digits that fall off are the edges of the day.

## A camera you tap into keeps the moment

Tapping one of the other cameras under the picture brings it up in the picture at
the same moment, so you can follow a person from the drive to the door without
going back to the grid and hunting for the time again. It is the only way to change
camera now: the sideways swipe that used to sit on the picture went with the arrival
of the tiles, so a tap means the same thing whether the picture is the stream or a
recording.

This needed the day, the marker and the live flag to stop being keyed on the
camera. Keyed, they were thrown away the moment the camera changed and came back at
their starting values, which put the marker at that camera's latest recording and
the picture back on the stream. They now live on the screen and are reset by the
fetch that follows a camera change, unless the change came from a tile.

A moment after now counts as a gap. The stretches can only be trusted as far as the
clock, and a marker dragged past it would otherwise offer a clip whose end is
before its start.

Checked on the emulator against the mock: the bar under the live picture with Today
on it, red and amber spikes, a drag that moved the marker and the picture together,
Live taking the picture back to the stream, the strip appearing with three named
cameras, a tap on one moving the main picture to that camera at the same moment,
another day's spikes loading on its chip, Play opening the clip of the stretch, the
phone on its side showing the stream with no bar, and nought requests over ten idle
seconds. The row is checked again on the live view: three named cameras under the
stream, a tap moving the picture to that camera and leaving it live, and no swipe
left on the picture.


## The bar scrolls an hour at a time

A day squeezed across the width of a phone is four minutes a point, so a half
minute detection is a tenth of a point: a spike you cannot see, which is the whole
reason for a bar with spikes on it. The day now runs off the edge of the screen and
the bar scrolls, with an hour across the width. That is twenty-four times closer,
which puts a half minute review at about three points, and a nine minute alert at
the width of a fingernail.

The playhead is fixed in the middle and the day slides under it, half an hour
either side, so you can see what is coming as well as what has passed. The bar
moves with your finger: drag left and later comes into the middle, drag right and
earlier does. A step is thirty seconds, the same as Frigate's own timeline, which
on this scale is three points of bar: fine enough that the picture looks like it is
following the drag rather than jumping, coarse enough that the server is not asked
for pictures nobody can tell apart.

The step has a second purpose. Frigate cuts every still out of the recording with
ffmpeg as it is asked, so the rate of requests during a drag is the load on the
server. A thirty second step means the number of requests a drag can ask for is
the number of steps it covers, whatever the speed of the finger.

Twenty-four screens of scrolling is too much for reaching a moment in the middle of
the day, so the whole day sits in a thin strip above the bar, with the hour on show
drawn as a box. Tap or drag along the strip to go anywhere at once. The two bars
share one moment, so a tap on the strip moves the hour, and a drag on the hour
moves the box.

Nothing is drawn that is not on screen: the ticks, the labels, the stretches and
the spikes are culled to the seconds the bar is showing, and the scroll position is
read inside the drawing rather than in the composition, so a drag redraws this one
bar instead of recomposing the screen.

## The picture holds instead of blinking

The blanks between pictures were the picture being set to nothing when a still did
not arrive. Frigate answers from the recording, so a server busy enough to miss one
has not unsaid the last one: the last picture now stays up until a new one replaces
it. A moment that is known to be in a gap still blanks, because that is the answer,
and an old frame under a new time would be a lie about when it was taken.

The last dozen stills are also kept, as the bytes Frigate sent rather than decoded.
A dozen is a couple of megabytes, where the same dozen pictures decoded would be
tens, and it makes going back over ground you have just dragged across instant
instead of another run of ffmpeg. Only the scrub stills use it, and only from the
thread the picture is drawn on.

The height the stills are cut at is one constant, ScrubLines, in Playback.kt. 720
is sharp on a phone. 480 is soft but comes back sooner and costs the server less,
and that is the first thing to turn if a slow server leaves the picture behind your
finger.

Checked on the emulator against the mock: an hour across the bar with a tick every
minute and the time every ten, the day's stretches and spikes in both bars, a drag
that slid the day under the playhead in thirty second steps with the picture
following it, the fixed playhead landing where the strip was tapped, a gap
blanking the picture with Play greyed out, Live putting the stream and the playhead
back at now, a tap between cameras keeping the moment and the bar, Yesterday
loading at its last recorded moment, the ends of the day stopping at the ends of
the day, Play opening the clip, the phone on its side and back, and nought requests
over ten idle seconds.

## The bar stops when the finger stops

The bar first coasted on after a drag, the way a scroll view does. On a list that
is helpful: the finger leaves and the list carries on to where you were heading. On
this bar it is not. You are reading a still off a particular second, and a bar that
moves under a finger that has stopped gives you a different second from the one you
stopped on.

The bar now uses a plain drag rather than a scroll. What the finger does is the only
thing that moves it: stop, and it stops. Crossing a day is what the strip above is
for, not a flick.

## The bar's own voice is not a request to move

Taking the coasting out did not stop the bar running away, because the coasting was
not the whole of it. The bar tells the screen where it has got to as a step: the
moment rounded down to thirty seconds. The screen holds that value and hands it back
to the bar, and the bar was treating any value that did not match its own position
as somebody else moving it, and moving to match. But the rounded value always lands
behind the playhead, by anywhere up to a step. So every step back dragged the bar
after it, which is a jump of nine points of bar for the thirty seconds.

At a step boundary the effect compounds: the bar lands exactly on a boundary, a
pixel of movement back rounds down another step, and that drags the bar another nine
points back, once for every event the finger sends. A slow drag back became a
stampede, and a drag forward was only quiet because the same pull was working
against the finger.

The bar now remembers the last moment it said, and the screen's value coming back
matching it is the bar's own voice, not an instruction. Anything else, from the
strip, a day chip, another camera or the clock while live, still moves it.

Checked on the emulator: a drag left and a drag right of the same distance moving the
same number of minutes, four ten point nudges back moving a couple of minutes and
stopping there, the strip still jumping the bar to the moment tapped, and Live still
putting the playhead back at now.


## A flick carries, and a finger stops it

The bar coasts again after a flick, because throwing the day along and watching it
settle is worth having, and because the runaways were never the coasting: they were
the bar reading its own rounded moment back as an instruction, and a finger landing
without stopping the coast. Both are fixed underneath, so the coast on top is safe.

A flick is a release with speed behind it. Let go with the finger still moving and
the day carries on: a hard throw is capped and covers about twenty minutes, a gentle
one a few. Let go with the hand at rest and nothing happens, whatever the last few
points of the drag looked like, so stopping on a second and lifting off keeps you on
that second. The coast dies out inside a second.

A finger anywhere on the bar takes the coast away at once, before it has moved far
enough to count as a drag, so a hand landing on a bar that is still gliding stops it
dead rather than joining in.

The speed of the drag is measured from the touch events, not from the clock, and the
emulator's injected move events carry no speed at all, so a flick has to be tried
with a swipe rather than with a held drag.

Checked on the emulator: a hard swipe forward carrying from twenty nine minutes past
one to forty nine minutes past one and settling there within a second, the same swipe
with a finger landing put down at thirteen oh one and holding there through two
seconds of waiting, a slow drag and release staying where it was let go, four ten
point nudges back moving a minute and no more, the strip still jumping the bar to the
moment tapped, and Live still putting the playhead back at now.

## The bar comes to rest and the recording plays

Letting go of the bar starts the recording from where it stopped, rather than showing
a still until Play is pressed. The still is still what you see while a piece is on its
way, and touching the bar again goes back to it.

Playback is downloaded pieces, not a stream. Frigate cuts a recording with ffmpeg
while it sends it, with no length and no support for ranges, so a player reading it
directly gives up after ten seconds of dribs. The app fetches a piece to a file first,
which is the same route an event's clip already takes, and hands the player the file.
Seeking inside a piece is free then, and the next piece is asked for while the current
one plays, so a join passes without a stop.

A piece is two minutes of what is left of a stretch. Frigate lists its recordings as
the ten second files they are, one touching the next while a camera records, so a
piece is a dozen of them, taken as one stretch where they touch and ended by a gap.
That is also why a request every ten seconds would not do: each piece is a request,
and at eight or sixteen times a piece that short cannot be cut and fetched fast enough,
which shows as a picture that stops and waits at every join. One piece is kept in hand,
not a pile: the next is only asked for once the player has started the one before, so
nothing downloads an afternoon that no finger ever reaches.

Both ends of a piece sit on the edges of those files. The end, so a piece stops where
a file stops; the start, so the next piece begins on a file's first frame and the
player can play it without seeking into the middle of a file to find where to begin.

An empty stretch is hopped rather than played as black. The next piece starts wherever
recording starts again, and the playhead jumps with it. An hour of a camera that
records on motion is mostly gaps, and stopping at every one would make the run useless.

The sound follows the speaker on the screen. On, and a recording is heard; off, and it
is silent, the same button that turns the live sound on and off. The stream's sound
and a recording's sound never run together: the stream is only asked for sound while
the stream is what you are watching. At sixteen times the sound is a chirp, which is
what the speaker is for.

Live puts the bar back on today and on now. Before, pressing Live over yesterday
left the bar sitting on yesterday while the picture showed the stream, which read as
the timeline being stuck.

Playback starts only for a moment you moved to. Opening a camera, picking a day or
coming back to it leaves the still up, and there is a wait of under half a second
after the bar is still before anything is fetched, so a drag on its way somewhere does
not start a download it will not use.

Two things went wrong on the way there. At sixteen times, the player can reach the end
of its list before the next piece has arrived, and a player left at the end of its
list does not pick up again when an item is added: it sat there while the screen
showed a moment that never moved. It is now moved on to the piece it has not played.
And a run that ran out of list was reporting the end of the last piece as its end
rather than the last moment actually shown, which put the bar back on a moment you had
already passed.

A third came from the join itself, and it was not only the number. Frigate does not
ask ffmpeg to seek to the moment it was given. It writes a list of the files it
recorded over the stretch and puts `inpoint` on the first, in whole seconds, and ffmpeg
begins the copy at the keyframe at or before that inpoint. A cut asked for from
anywhere past the first second of a file therefore comes back up to a keyframe early,
and that is a moment the piece before has just played: the picture and the clock burned
into it both go back a second at every join. Measured on a recording whose keyframes
are 1.42 s apart, `inpoint 4` came back 1.17 s early and `inpoint 9` about 1.4 s.

The app now asks for a moment inside the first second of the file a piece starts in, so
the inpoint is zero and the cut begins on that file's first frame. The player is then
given the piece rather than the whole cut: it begins at the piece's own moment inside
that file and stops at the piece's end, so the second and a half that was asked for
past the end is never played either.

The moment is sent to the millisecond for the same reason. Rounding it down to a whole
second pushed the start below the edge of the file the piece began at, and the cut then
began with the file before it.

The moment readout still only goes on within a run, because a reading four times a
second can come back a sample short across a join. That was a separate fault, though,
and fixing only the readout is what left the picture stepping back.

Tapping another camera while a run is playing carries the run on there, from the moment
the picture had reached. It used to come up on the moment the run had started at, held
still, which read as the timeline going back several minutes. The run on the new camera
starts once that camera's stretches have arrived, because those are the files its pieces
are cut from; until then the moment is on the bar with a still under it. The other
cameras' tiles follow the picture as it plays, in whole minutes: each tile is a still the
server cuts when it is asked, and the moment four times a second would be asked for far
more often than one a minute is worth.

The clips are the biggest thing the app keeps, so the folder is trimmed to 600 MB,
oldest first, on top of yesterday's going altogether.

Checked on the emulator: a drag and release starting the picture a moment later with
the still up until the first frame, the playhead walking along the bar, the speed
button stepping one, two, four, eight, sixteen, a run crossing the end of a recorded
stretch and carrying on at the next one two hours later, and Live stopping the picture
and returning to the stream. For the join: a run at normal speed and a run at eight
times each crossing five joins with the clock burned into the picture never going back,
and the first frame of a run showing the moment picked rather than the start of the
file it fell in. For the camera: a tap on another camera part way through a run, with
the clock burned into the picture carrying on unbroken across the change and the moment
on the bar holding where it was rather than going back to where the run began.


## What the picture is, said on the picture

A recording and the stream can look alike on a phone: both are a camera pointed at the
same thing. The bar was the only thing that said which, and on its side there is no bar.
So a pill sits in the bottom left of the picture, **Live** in red and **Playback** in
blue. Red because that is what says something is happening now, and blue because it is
the colour of everything you can go back over.

The dot brightens and dims rather than switching on and off. Hard blinking in the corner
of a picture you are watching for ten minutes is tiring, and a slow pulse still says the
phone is awake.

Bottom left, because the top left is the way back to the stream and the top right is the
speed. The `MJPEG · 720p · 5 fps` line that used to sit in that corner has gone. The menu
is where you set those two numbers, and it was there for the times you wanted to know
what you were asking for, not for every minute of watching. In the corner it was in the
way of the pill.

## The Play slot keeps the stretch instead of playing it

Play opened the two minutes around the bar in the viewer, back when the picture only
moved while you dragged the bar. Now the picture plays from wherever the bar stops, so on
a recording Play opened the same two minutes the picture was already showing: a button
that did nothing you could not do by waiting.

It still does something on the stream. There, Play opens the last two minutes with a
scrub bar, which is the one thing the live picture cannot do, and a viewer is what an
event opens in anyway.

So the slot keeps two jobs, one per mode: **Play** on the stream, **Save** on a
recording. Save takes the two minutes the caption beside it names, taken from the same
numbers that draw the caption, so the file and the words can never describe different
stretches.

The button is a pill with an arrow and the word rather than a round icon. A round icon
beside a caption about a stretch of time reads as another way to play it.

## Save asks first and fetches second

The phone's own save screen opens, which is the Storage Access Framework's
`CreateDocument("video/mp4")`, and you choose the folder. So the app asks for no storage
permission, nothing appears in the gallery, and the file lands wherever you said,
including a cloud folder the phone knows about. There is nothing to grant and nothing to
explain in Settings.

Nothing is fetched until a place is chosen. Backing out of the save screen costs a tap
and no data, which matters more on a metered connection than on a home one.

The name offered is the camera and the moment it starts, as
`frigo-door_cam-2026-10-31-12.50.mp4`. Dashes and a dot rather than colons, which a file
system will not have, and in an order that sorts by date and time in a folder listing.

Save asks for the same clip under the same name as the viewer, so a stretch just watched
is copied off the phone rather than downloaded a second time. A stretch you have not
watched is fetched, which is what the progress is for. Frigate sends no length with a
clip, so progress is bytes arrived and nothing else: a spinner, the word Saving, and the
megabytes, the same shape as the viewer's own.

A save that fails says so in the button rather than in the caption, for the same reason
the caption is left alone: it is busy naming the stretch, and a message that replaces it
takes the times away with it.

Frigate cuts from the nearest keyframe before the moment asked for, so a saved file can
begin up to a second early. In a file you keep, that is nothing to mind. The app already
works around the same fault in the picture by asking for a moment inside the file's first
second, which a saved file has no use for.

## The stream menu belongs to the stream

The menu sets the height and frame rate of the stream the app asks for. On a recording it
changed nothing at all, and a menu that does nothing is a puzzle. It is gone while a
recording is on screen. The speaker stays, because recordings have sound.

## Return to Live says what it does

The button over a recording carried a green dot and the word Live, which read as a
statement about where you were rather than a way to go somewhere. The pill now makes that
statement, and a second dot saying something slightly different is one too many. The dot
went, and the words say what a tap does.

## The stream has nothing left to play

Play on the stream opened the two minutes up to now in the viewer, which was the only way
to scrub through a stretch back when the picture only moved while you dragged the bar.
Now the picture plays from wherever the bar stops, so the button was a second way to do
the one thing the screen already does. It has gone.

The viewer keeps its job. An event and a review open in it, and neither has a bar of its
own to scrub along.

The line under the time went with it on the stream. There it named the two minutes up to
now, the same pair Play would have opened, so with Play gone it named a window that leads
nowhere. On a recording the line stays, because Save writes exactly what it names.

That leaves the live screen with the day's bar, the other cameras and the big time, and
nothing in the bottom corner at all.

## The big readout carries the day

Dragging the bar across a day only told you the time, and the only other clock on screen
was the one the camera burns into its own picture: small, in a corner the camera chose,
and missing altogether on a camera whose overlay is turned off. It is also a picture of a
moment, so it lags the bar while a still is on its way.

So the readout under the bar now reads the day as well as the time, `Sun 1 Nov 18:34`,
drawn by the phone as the bar moves rather than read off the picture. The pattern follows
the phone's own language, so a phone set to another language reads its own day and month
names.

## The camera screen is painted, not black

The camera screen was black whatever the theme: a video player's habit, and a page that
stopped matching the rest of the app the moment you opened a camera. In Night it read as
a hole beside the navy the other pages are made of, and in Day it stayed black while
everything around it went light.

It now takes the theme's own background and ink, in both the stream and a playback: the
top bar, the day pills, the hour bar and its ticks, the strip of the whole day, the other
camera's names, the line under the time and the Save button. The status bar's own icons
follow, dark on the light page and light on the dark one.

The pills that sit on the picture stay dark with white text, because they sit on a
picture: a light pill would vanish against a bright frame, and which mode you are in is
the one thing that has to read whatever the camera is looking at. The full screen clip
viewer stays black for the same reason, and because it is a clip and nothing else.

## Day is a grey blue, not white

The day theme was white with a hint of blue in it, which made the brightest thing on the
screen the page behind everything else. Outdoors in daylight that reads as a glare rather
than a surface, and the night theme had already shown that Frigo's colours are a grey
blue; the day side just went too far the other way.

The day page is now `#E5EAF1`, a light grey blue, with the ink a near-black blue at
`#19222E`. Cards and tiles lift out of the page rather than sink into it, a shade lighter,
which is how the Material colours were already arranged. The accent stays the app's own
blue, darkened to `#1E5C99` so a link still reads on a page that is no longer white.

The wash over the hours Frigate kept needed a value of its own. A light band on the night
page reads as a solid block on a light one, so the kept hours are drawn lighter in Day and
left as they were at night.

Warm off-white and a green-grey were drawn as alternatives. The grey blue won because it
is the night page's own colour taken up to daylight, so the two themes stay one app.

## The stream menu says what is on

The menu was seven identical lines, heights in a row and then rates in a row, with nothing
to say which two the stream was using, and "15 frames a second" written out under "1080p"
with no heading over either group. A menu you choose from should also be the place that
tells you where you are.

So it has two headings, Resolution and Frames per second, and the choice in use is tinted
and ticked. The panel has rounded corners, a hairline border and a shadow, and it is built
from the theme's own surfaces, so it follows Day and Night like everything else.

The list goes to 2160p and 30 frames a second, because a camera that sends more than 1080p
should not be capped by the app. Frigate cuts the picture down from what the camera sends,
so asking for more than the camera has simply gives the camera's own picture.

## The mode pill sits on the picture

The pill was pinned to the bottom of the box the picture lives in, and the picture is
fitted rather than stretched, so on a tall phone the box has a band above and below the
video. The pill sat in the bottom band, a little under the picture, looking like a label
for the page rather than a statement about what you are watching. Return to Live floated
above the video the same way.

Both now sit in a frame of the camera's own shape, 16:9, centred in that box, which is
exactly where a fitted picture is drawn. The pill takes the bottom left corner, Return to
Live the top left, and both are only there when they mean something: Return to Live is not
on the stream, where there is nowhere to go back to.

The pill's own black went from 60% to 35%, so the corner of the picture shows through it.
It still has a coloured ring and white text, which is what carries it over a bright frame.

## The picture gets no block behind it

A still arriving had a faint block behind it to show where the picture would be. On the
other cameras' tiles the block is the tile, so it stays. On the full screen picture the
block filled the whole box rather than the picture, which left a slab of nothing above and
below the video and made the mode pill look off centre. The full screen picture now sits
on the page with nothing behind it, and a stretch with no recording shows the page and the
line that says so.

## The date is the size of the day buttons

The line under the bar was set large, from when it was the only thing saying where you
were. With the day buttons right above it and the same kind of information in both, two
different sizes for one idea read as a mistake, so the date is now the same size as the
buttons. The gap between those buttons and the bar also grew: choosing a day and reading
the day's bar are two different things, and the space says so.

## Only the pill is on the picture

The mode pill moved onto the video's bottom corner, and Return to Live and the speed
button went with it. They don't belong there. The pill says what you are watching, which
is a fact about the picture itself; the two buttons are things to do, and they were
sitting over the part of the frame the camera is pointed at. They are back on the page,
in the top corners of the picture's area, where they were before.

## A camera that stops is called offline

Frigate keeps no flag that says a camera is offline. It counts the frames each camera
produces, so a camera that has stopped produces none, and its stream carries on handing
back the last frame it sent. That picture looks exactly like a live one, and it goes on
looking like one for as long as you watch.

So the app asks `/api/stats` every eight seconds while the stream is up and reads the
frame count for the camera in front of you. Two readings in a row with nothing arriving
turns the pill grey and it says Offline. The last frame stays where it is, because that
is still the last thing the camera saw. One reading alone is not enough: a camera can
drop a second of frames and carry on.

Nothing is said for the first two minutes after the server starts, when its counts mean
nothing yet. Nothing is said when the stats call itself fails either. That is about the
server rather than the camera, and calling a working camera offline on a guess is worse
than saying nothing.

The home page tiles are left alone: a tile is a still taken a moment ago anyway, so an
offline camera there is a still that happens to be old.

## Each camera keeps its own size and rate

The menu used to set the stream's height and frame rate for the session and forget it.
The choice is now written to the phone as each camera picks it, in the same place the
server and theme live, and read back when that camera opens. It is kept per camera
because a camera watched closely and one glanced at are not the same thing to spend
data on, and it is kept at all because a menu that forgets is a menu you set every day.

The stream is not opened until the saved choice has been read. Otherwise a camera would
start at the default and be replaced a moment later by the one you actually asked for,
which is two connections where one will do.

A size the camera was left at stays in the menu even after the camera grows past it. It was
left out, so a camera whose picture got bigger kept opening at the old small size with
nothing ticked in the menu, and there was no way back up. That happened for real when South
cam's detect stream was moved to the main stream: the menu read 1080p, 720p and 480p, none
of them ticked, while the picture was still 360p.

## The menu offers what the camera has, and no more

The menu offered 480p to 2160p and 2 to 30 frames a second whatever camera it was on, and
Frigate answers any of it. Asked for more than the camera sends, it scales each frame up
and sends it as often as asked. Nothing in the picture is new.

Measured on this server: North cam sends 640x360, and `/api/<camera>/latest.jpg?height=2160`
answers with a 3840x2160 picture. The stream at 2160p and 30 frames a second then delivered
11.4 frames a second of 402 KB frames, about 4.6 MB/s, and every other camera on the box
stalled while it did, because the machine was spending itself making pixels that came from
a 640 wide camera. The same camera at 360p and 5 frames a second delivered 4.7 frames a
second of 38 KB frames: a tenth of the data and a picture with the same detail in it.

So the app now asks the camera how big its picture is, by reading the height out of the
header of one still rather than decoding it, and asks `/api/stats` for the rate the camera
is set to send at (`expected_fps`, falling back to `camera_fps`). Those two numbers are
what the menu offers, and a saved choice outside them is brought inside before the stream
opens. A camera that cannot be asked opens at 1080p at most, which is about a phone screen
tall; 4K is four times the data for a picture shown at the same size. Both answers are
kept for thirty minutes per camera, and the stats answer is shared with the offline check
that asks the same endpoint every eight seconds, so opening a camera costs one small
request rather than two.

The stream effect is keyed on the choice itself rather than on the two numbers inside it.
Keyed on the numbers, a camera whose saved choice happened to equal the defaults it opens
at, 720p at five frames a second, never opened its stream and sat on Connecting.

## The live picture is the detect stream's picture

Frigate keeps one frame per camera, and it keeps it at the size of the stream it decodes
for detection, the one with the `detect` role. The MJPEG endpoint hands that frame out,
scaled to whatever height is asked for, and there is no parameter that picks another
source. So the app's live picture is the detect picture whoever asks, and the app has no
say in it. On this server that means North cam and South cam live at 640x360, Door cam at
1280x960 and Printer cam at 1280x720.

The two Tapo C320WS cameras fix their second stream at 640x360 and give no setting for it,
so nothing on the camera side raises those two. The only lever on the server side is
pointing the `detect` role at the main stream, which puts 2K frames through detection and
is the kind of cost the limits exist to keep the app from adding by accident.

That lever was measured rather than argued. South cam's detect role was moved to its main
stream for ten minutes and then put back. The whole box went from 29% to 52%, South cam's
detect process from 3.7% to 26.6% and its decoder from 1.2% to 10.1%, the detector ran 3.6
to 16.4 times a second because a sharp frame has more motion in it, and the phone's picture
at 1080 cost 2.8 MB/s. Doing the same to North cam would put the box near three quarters,
which is where the streams fell apart before.

So the live picture comes from go2rtc instead, which holds the main streams already and
hands them out without decoding: the camera's own 2560x1440 H.264 at 190 KB a second, with
the sound in the same stream.

The way in was half built. The proxy already handed `/api/stream.aac` to go2rtc's own port
for the sound, and go2rtc answered it without a Frigate login. Video needed one more
location of the same kind, for `/api/stream.mp4`, at the same port. Frigate could not have
been serving that path: asked for it with an admin token, it answered "Access denied. A
user with the admin role is required", which is what it says about a path it has no route
for.

That location was added and it works. `/api/stream.mp4?src=north_cam` answers `200` with
`video/mp4; codecs="avc1.4D0029,mp4a.40.2"`, chunked, fragmented MP4, one video track at
2560x1440 and one AAC track, at about 160 KB a second. No sign-in is needed or looked at;
the app sends its token anyway, because a proxy access list may want it.

## The live picture is the camera's own video

The player asks go2rtc first, over that one connection, and plays it with the media3
player it already had for recordings. Go2rtc's video is a texture view rather than a
surface view, because a surface is drawn over the page by the system and cannot be moved,
scaled or clipped by the app, and this picture is pinched and dragged like the stills were.

Two things about a stream with no end had to be dealt with, and both were found by
watching it rather than by reasoning.

**The buffer.** The player's default keeps tens of seconds of media in hand, which on a
live stream is tens of seconds of lag that never comes back. It now keeps three seconds
and starts with half a second, with the live offset set to half a second as well.

**Falling behind.** A stream with no end never says it has fallen behind. The player
watches the gap between what it is showing and now, and opens the stream again when the gap
has grown fifteen seconds since the best it managed. A picture that sits waiting for more
than twelve seconds is reopened the same way, and a stream that ends sends the screen back
to the stills.

On the emulator the picture falls behind steadily, because its H.264 decoder runs in
software: its position advanced 11 seconds over 18 seconds of clock, so the gap reached the
fifteen seconds and the stream was reopened, which is what that check is for. A phone
decodes this in hardware, so the gap stays where it started; that is worth checking on a
phone rather than taking on trust.

**The fallback.** The camera's own stream gets twelve seconds to put a picture up. Without
one, the screen switches to Frigate's stills and a line under the picture says so, because
the picture drops from 2K to 640x360 and that should not be left to be noticed. The size
and rate menu is only offered while the stills are playing, since it changes nothing about
the camera's own stream. A player error, a stream that ends, or a picture that never
arrives all end on the stills rather than on a spinner.

**The sound.** Go2rtc's stream carries its own AAC track, so the speaker button turns the
volume of the video up and down, and the separate sound player on `/api/stream.aac` is only
used with the stills. That also means sound and picture arrive together rather than a
moment apart.

The measuring left to do on a phone: the picture's lag against the camera's own clock, an
hour of watching on mobile data, and battery. On the emulator the audio track reported
write errors, which is the emulator's audio hardware rather than the stream.

## A slow camera shows a still while it starts

Opening a camera could sit on "Connecting…" for ten seconds and then settle on the stills,
which is the one thing the change was meant to stop. Two faults were behind it, and both
are about a stream that is still on its way being treated as one that has failed.

**The player restarted a stream that was still filling.** A player that has not drawn a
frame yet reports itself as waiting for more, which is exactly what a stalled picture
reports, so the twelve second check reopened the stream and pushed the picture further
away. Nothing is measured now until the first frame is up. The stream still has the
twelve seconds before the screen settles for the stills, so a route that has stopped
working is caught the same as before.

**Nothing stood in for it.** The video can take a moment to arrive, and the screen was
empty until it did. After two and a half seconds Frigate's still is drawn in the same
place, and the video takes over the moment its first frame is drawn. The stills stop
there, so a camera that answers quickly never opens the second stream at all, and the
line about the small picture only appears once the stills are what the screen has settled
on rather than what it is waiting with.

The still had to be faded in rather than drawn on top: the player is a view, and a view is
drawn over everything the page draws around it, so a still placed beside it sits
underneath it and is never seen. The video is transparent until it has a frame instead,
which has the same effect as drawing the still over it and costs nothing.

Measured from the server, a cold stream hands over its first frame in one to two seconds.
On the emulator the first frame was drawn between one and four seconds after the camera
opened. One open of north took longer than the twelve seconds on an evening when the whole
emulator was slow, and the stills stayed with the line under them, which is what that
grace is for.

## The bar keeps up with the clock

While you watch the stream the day goes on being written: Frigate closes a recording file
every ten seconds and flags what it sees as it sees it. The bar was drawn once when the
camera opened and never asked again, so the filled hours stopped at that moment and the
spikes with them, with a growing grey gap between them and the playhead.

The stretches and the flags are now asked for again every twenty seconds, over the last
ten minutes, and joined onto what the bar already has. Only the tail is asked for: a day
of ten second files is thousands of rows, and fetching all of them again every twenty
seconds to learn about the last one is a large answer for very little. Stretches are
joined by their start, so a fresh answer replaces what the bar had at that moment; flags
are joined by their id, because one that is already on the bar can grow as it goes on.

The playhead follows now while the stream is up, so the bar shows what is happening.

## The app updates itself from the release page

There is no store listing, so a new version is an APK on the GitHub release page and
nothing was going to tell you one existed. Settings asks GitHub for the newest release,
offers to fetch it, and the Settings icon in the bar carries a small red dot while there
is something newer to install.

The dot is the reason the check moved. It used to run inside the Settings page, which was
fine when the page was the only thing that cared: the answer was wanted only while you
were looking at it. A dot on the bar is wanted everywhere, and a screen can only draw
what it can read, so the answer now belongs to the app. `UpdateWatch` holds it, the bar
draws the dot from it, and the Settings page reads the same answer instead of asking on
its own.

The check happens when the app opens, and again when you come back to it. Nothing runs in
the background. That is what the earlier version of this note argued against, and the
dot is what changed the argument: a release nobody mentions is a release nobody installs,
and a bar with a mark on it is quiet in a way a notification is not. There is no buzz and
no prompt, so a screen you opened to watch a camera is still a screen you opened to watch
a camera.

GitHub's unauthenticated allowance is 60 asks an hour for the address you are on, and a
launch check on every open would spend it. An answer therefore stands for an hour and is
kept on the phone, so ten opens in a morning is one ask and the dot is already right on
the first screen. A launch with no signal keeps the last answer rather than clearing it:
silence is not news, and a dot that comes and goes with the wind is worse than one that
lags a day. The button that says Check again always asks, because it is you asking.

Opening Settings clears the dot, and the version you saw is what is remembered. A later
release brings it back, because the dot means "there is something you have not seen".
Tapping Download does not clear it: fetching a file is not the same as being rid of the
news. Neither does looking at the page during a check, so a release that arrives while
you are on it never puts a dot on the tab under it.

The dot is drawn here rather than taken from Material's badge, which is fixed at 6dp. At
12dp it reads as a mark on the tab rather than a speck, and its middle sits on the corner
of the gear. It uses the theme's error red, which is the app's own red; in the night
colours that is a pale red, which is what Material's dark scheme calls for.

The APK is downloaded into the app's cache, beside the real name and renamed at the end, so
a download that stops half way cannot be mistaken for a whole one. A file already there is
kept rather than fetched again. It is handed to Android's installer through a file
provider, and the installer copies what it needs, so a cleared cache cannot break an
install that has already started.

Android keeps the switch that allows installing apps from a source, and no app can turn it
on itself. Tapping Install with it off opens that screen and says what to do; the note goes
away when you come back with the switch on.

Versions are compared as numbers rather than as text, so 0.7.100 counts as later than
0.7.99 instead of earlier. The release workflow derives the version code the same way, so
the phone agrees with the app about which version is newer.

## Coming back to the app is not carrying on

Leaving the app pauses the stream, which is right: watching is no reason to play on in the
background. Coming back resumed it from the paused moment, and the camera had gone on
without it. Two minutes away came back as a picture two minutes old, with the pill still
saying Live, and nothing to catch it up.

The watchdog should have caught it, and did not: it measures how far the picture is behind
the clock against the best the picture has managed since it was opened, and it starts again
every time the screen comes back. So the two minutes were taken as the new normal, and the
picture stayed put.

Coming back is now handled where the pause is: a stream left for more than three seconds is
opened again rather than played on. The best the picture has managed is also kept across the
pause, so anything lost while the screen was away still counts as lost. Reopening costs
about a second of picture, which is the same thing the watchdog already does when a stream
falls behind on its own.

## The bar draws a run, not its segments

The bar showed hundreds of hairlines across an hour of a camera that never stopped
recording. They were there in the data: over three hours, South cam came back as 1,073
rows, 915 of them with 0.03 seconds between one row's end and the next one's start, and 153
with about a second. North cam did the same, 785 slivers and 126 second-long ones. Every
segment ends a fraction of a second either side of the next one's start, so nothing is
missing from the recording; a segment of ten seconds is simply not cut on the same tick as
the one before it.

Two things then turn that into something you can see. Each row was drawn as its own block,
and an edge drawn with antialiasing is a partly covered pixel, so two blocks that touch
leave a lighter seam even with no gap at all. A second of gap at hour scale is a third of a
pixel, which is enough to read as a line.

So the rows are joined before they are drawn: anything less than two seconds apart is one
run. A second of nothing is not something you can watch, and neither the play button nor the
scrubber could land in it, so the bar saying the run is unbroken is the truthful reading.
Real holes, minutes of a camera that stopped, are wider than that and stay grey. The join
happens where the stretches are read, so the bar, the day strip and the pieces a playback is
cut from all agree, and it is done again after the twenty-second tail is stitched on,
because the two halves meet at a moment neither of them chose.

## Alerts and detections are edited through Frigate's settings API

The four settings behind Review are Frigate's own: whether alerts are made, which objects make
them, the same for detections. They are read from `/api/config`, which any signed-in account may
read, and written with `PUT /api/config/set`, which only an admin may call. The body is
`{requires_restart: 0, update_topic, config_data}`.

The topic chooses who hears about it. The review maintainer subscribes to
`CameraConfigUpdateEnum.review`, which is `config/cameras/<name>/review`, so a write to
`config/cameras/*/review` or to one camera's path is applied as Frigate saves it and nothing
restarts. `requires_restart` is left at 0 for the same reason; this is the one place the app writes
to the server at all.

Only the fields that changed go in the body. A camera with no review settings of its own follows the
top-level ones, and writing those same values back under the camera would end that: from then on the
camera would keep that copy and stop following. So flipping a switch sends `{"enabled": false}` and
nothing else, and the labels are written only when the labels changed.

A detection list sent as JSON `null` is a key deleted, which is how "everything else" is put back.
Frigate reads a missing `labels` under detections as every other object, and under alerts as the two
it starts with, so null is the right value there and an empty array is not.

A 403 is thrown as `NotAnAdminException`, not as `FrigateException`. The repository reads a 403 from
`FrigateException` as an aged-out token and retries after fetching a fresh one, which does nothing
for an account that simply isn't allowed to change anything.

The picker offers Frigate's own labels: bark, car, cat, fire_alarm, glass, person, scream, smash,
smoke_detector, speech and yell. Anything already tracked or already chosen is added to them, so a
label in use never falls out of the picker.

The two other places a list could come from both read wrong. `/api/labels` is the labels the server
has seen events for, four of them here, so most of what can be chosen would be missing from it. The
detector's `model.labelmap` is the ninety COCO names the model was trained on, which is what the
model can recognise rather than what Frigate acts on: it offered `airplan`, a misspelling in the
server's own map, `background`, the class numbered zero, and no sounds at all. Read on the emulator,
eleven names a person recognises against ninety they don't.

The picker will not let the alert list be emptied, because Frigate reads an empty list as no alert
labels and would stop making alerts without saying so; turning Alerts off is the honest way to do
that and it says so. An empty detection list is allowed, and means every other object.

What a change costs the server's file: the write leaves a block in `config.yml` where there was none
before, and the app has no way to take it back. Deleting the whole block with `{"review": null}` is
what restores the inherited values, and that also drops any other review setting under it, so the app
does not offer it. The settings page shows what Frigate resolved, which means a camera with its own
copy reads the same as one following the shared settings, and the app cannot tell which it is.

Both shapes were written against the real 0.18.2 server: a global write and a per-camera write each
answered 200 `{"success": true}`, the global one added a `review` block and no camera block, the
camera one left that camera's settings alone and its neighbours' unchanged, the detection labels came
back still absent, and the file was 331 lines again after the test block was deleted.

## A camera's switches are saved settings, not Frigate's quick toggles

Frigate has two ways to turn a camera's features off. `PUT /api/camera/<name>/set/<feature>` changes a
runtime toggle without touching the settings file, and `PUT /api/config/set` writes the setting down.

The quick toggle was the first choice and had to be given up: the server never says what one is set
to. `/api/config` goes on answering with the file's value. Birdseye was turned off through the runtime
endpoint and the config read three seconds and eight seconds later: `enabled` was still true, and the
same for motion. `/api/stats` carries `detection_enabled`, which covers object detection alone; the
other five have no read-back anywhere. A page of switches built on those would be showing guesses, and
a camera turned off in Frigate's own web page could show as on here.

So all eight are settings writes, which is also what Frigate's own camera page does. `requires_restart:
0` with the topic `config/cameras/<name>/<section>` publishes the change to whoever listens, and Frigate
applies it as it saves. Checked against the 0.18.2 source: all eight `enabled` fields are per-camera
fields Frigate itself marks as needing no restart, `detect` needing one only for its resolution and
tracking numbers.

The cost is a line in `config.yml` where the camera used to inherit, and `enabled: true` written where
there was nothing before. That is what makes the page honest: the value it shows is the value the
server will use. The write also drops any runtime override for the same key, so a stale override
cannot outlive a visit to the page.

Face recognition and license plate recognition have a switch for the whole server as well
(`face_recognition.enabled`, `lpr.enabled`). While that is off a camera's own switch does nothing,
which is why the row reads off, says so, and cannot be moved. This server has LPR off for everything
and face recognition on.

There is no "all cameras" scope. The four cameras here are not set the same, and one tap that stopped
recording everywhere would be a long way from what was meant.

Recording asks before it stops: it is the one switch where the next minutes are lost rather than the
next few events.

## Checked on the emulator


Against the mock: the Live pill under the stream with its dot dimming and brightening, no
MJPEG line, the menu on the stream; a tap on the bar turning the picture into a playback
with the Playback pill and no menu, Return to Live back to the stream, Save opening the
phone's save screen in Downloads with the camera and the moment filled in, the button
saying Saved and returning to Save, and the file on the phone playing for two minutes with
the clock burned into it starting where the caption said. Over a gap the caption says
nothing was recorded then and Save greys out. On its side, both pills show with the
picture filling the screen.

With Play gone: nothing in the bottom corner of the live screen, no line under the time
there, and `Sun 1 Nov 19:24` under the bar on the stream and `Sun 1 Nov 13:55` with
`13:55 to 13:57` under it once the bar has been dragged and Save is in the corner.

With the pill on the picture: it sits in the video's bottom corner on the stream and over a recording, in portrait, on its side and in both themes, Return to Live in the top corner only over a recording, the date under the bar the size of the day buttons and a wider gap below them, and no block behind the picture over a stretch with nothing recorded.

The stream menu, in both themes: two headings, the tick on 720p and 5, a tap on 1080p moving the tick and closing it, and the panel opening again with 1080p ticked.

In Day, the home grid, Review, Explore, Settings and the camera screen all read on the grey-blue page, with no white left in it, and the kept hours a light wash rather than a block.

With the two buttons off the picture: Return to Live back at the top left of the page and the speed button at the top right, both clear of the video, with only the mode pill on the picture.

With a camera that stops: the mock's Quiet cam reports no frames, and its pill reads Offline in grey with the dot holding still while the last frame it sent stays on the picture, in portrait and on its side, while a camera that is sending keeps its red Live pill.

With the choice kept: North cam on 1080p and 15 and Door cam on 720p and 5, both still ticked that way after `am force-stop` and a fresh launch, and each camera opened at its own.

With the bar keeping up: on the stream, screenshots a hundred seconds apart with the playhead on now, the filled hours reaching the playhead both times rather than stopping where the camera was opened, the day labels shifting with it, and the mock set to flag a moment every twenty seconds putting new spikes on the bar as they go by.

With updates: Settings opened on a build older than the newest release showing it as out, Download counting up to 100%, then Install opening Android's screen for allowing installs from Frigo, then the installer's own "Do you want to update this app?", with the fetched file the same size byte for byte as the one on the release page. On a build at the newest version it says so, with Check again beside it. With the airplane mode on, Check again says GitHub couldn't be reached, and turning the network back on and tapping it again settles the page. Opening Settings a second time offered the file already downloaded rather than fetching it again.

On the colours: both the stream and a playback opened in Day and in Night, and on its
side in both, with the page, the top bar, the day pills, the bar, the built-in day strip,
the other camera's names, the line under the time and Save all following the theme, the
status bar icons dark in Day and light in Night, and the Live and Playback pills still
dark on the picture either way.

With the menu inside the camera's limits: against the real server, North cam opened at
360p and 5 frames a second, having been saved at 720p by an older build, and its menu
offered 360p alone and 5 and 2 alone. The OSD clock in the picture went from 10:42:15 to
10:45:18 over three minutes of wall clock, which is the server's own four second buffer
and no drift on top of it, and the frame gaps sat at about 200 ms with 422 ms the worst,
over one stream opened and kept for four and a half minutes. Switching North cam to
Printer cam to North cam to South cam to Printer cam opened each in 510 to 720 ms.

With the app left: South cam opened and playing, the phone's home pressed at 16:06:48, the app
brought back at 16:09:18. The picture was on 16:09:33 against a clock at 16:09:43 rather than
the 16:06:31 it had paused on, the Live pill still up, and the bar and the line under it on
today and on now. The emulator decodes 1440p in software at about two thirds of real time, so
ten seconds behind is what it does while playing; the phone is a second to two.

With Alerts & Detections: the tune icon on Review opened the page on All cameras with Alerts
and Detections both on, Person and Car under Alerts, and Everything else under Detections, the
camera row running off the right edge and scrolling, and a sentence under each card saying what
it sets. The alert picker offered the eleven Frigate labels and nothing else, Car, Cat and Person
marked as tracked and in that order above the rest, Car and Person ticked, the search box
narrowing the list, and Done greyed out with a line saying to pick one or turn Alerts off once
nothing was ticked. Flipping Alerts off from the app took effect on the server with no error and
no restart, `/api/config` showing alerts off for every camera; flipping it back on did the same
in reverse.

With the camera switches, against the real server: Settings listed the four cameras under a
Cameras heading with a chevron each, and 3d printer opened with object detection, recording and
audio detection off, the other five on, and license plate recognition greyed out with "Off for
the whole server." under it. Turning Snapshots off showed it off at once and `/api/config` had
it false, with nothing else on the camera changed. Turning Recording on for 3d printer was refused
by the server with "Camera 3d_printer has record enabled, but record is not assigned to an
input." and that switch went back to off while the rest of the page stayed put. On North cam,
Recording off asked "Stop recording?", and Stop put it off on the server and back on again
without asking. Every camera read as it did before the test once the switches were put back.

## The Review list is narrowed by the server, not by the page

The three chips send their choices to `/api/review` as query parameters rather than filtering
the rows already on screen. Filtering the page would only cover what has been fetched so far,
and **Load older** would keep filling the list with the things the filter is meant to drop.

`cameras` and `labels` are each one comma-separated value. A repeated parameter is answered with
the last one alone, which was checked against 0.18.2 for `/api/events`; `/api/review` reads the
same way. A label matches a review that saw it anywhere in its data, not only the label that made
it an alert, so narrowing Alerts by car keeps a review where a person was seen beside one.

The labels on offer come from Frigate's own review settings, read from `/api/config`, because
Frigate alone decides what can make an alert or a detection. A camera that names no lists of its
own follows the shared ones. A detection list that names none means every other object, so the
chip offers the rest of the names Frigate has: the tracked ones first, then the eleven it flags
by itself. That is wider than a camera can strictly produce, and the empty state says so plainly
when a choice brings nothing back.

The day chip is the one Explore already had, moved to `ui/Period.kt` with its chips in
`ui/components/Filters.kt`, so both pages ask for a day the same way. A day picked in the
calendar is that day on the phone, and the two ends go to the server as whole seconds.

With the Review filters, against the real server: the chips sat under the tabs, and the Labels
chip on Alerts offered Person and Car, which is what that list holds. Picking Car left the one
car alert and put a Clear beside the chips. Picking North cam with Car left nothing and said
"Nothing for these filters." with a Clear filters button. On Detections the chip offered Cat,
Bark, Fire alarm, Glass, Scream, Smash, Smoke detector, Speech and Yell, Cat first because that
camera tracks it. Switching tabs dropped the label already chosen. Today, Yesterday and a day
from the calendar each came back with only that day's reviews, and a day in the future came back
empty. Clear put the list back as it started.

## Settings says what sits behind each camera

The camera rows were a name and a chevron, which gave no sign that eight switches were behind
them, and nothing to tell a camera that had recording off from one with everything on. Three
layouts were drawn: grouped cards, a two by two grid of camera tiles, and a flat list with no
cards. Grouped cards was picked.

Each group is a small heading over a card: Server, Notifications, Live view, Cameras, Appearance,
Updates, About. Rows carry a leading icon, and a divider between rows starts clear of it. The
camera row reads **Everything on**, or names what is off, in amber: "Recording off", "Object
detection and recording off", or "Object detection, recording and 2 more off". Past two the rest
are counted, so the line stays a line.

A switch the server holds off is left out of that line. License plate recognition is off for the
whole server here, so every camera would otherwise read as having something wrong with it. That is
why a camera with eight switches on can say **Everything on** and not **All eight on**: the row
counts only the switches that camera can actually move.

The summary comes from `/api/config` through `cameraSwitchPage()`, the same call the switches page
makes, so the two pages cannot disagree. The camera list and the switches now come from that one
trip rather than two.

Appearance is a single-choice segmented button instead of three filter chips, which is what
Material 3 uses for a choice of three that applies at once.

The icons are drawn in `ui/Icons.kt` beside the rest, because Material's core set has no server,
camera or bell that match the app's stroked weight.

With the new Settings, against the real server: Server showed the address and dev. Notifications
held the switch, the four cameras and the objects, with Fire alarm read as two words rather than an
underscore. Live view sat under it. Cameras listed 3d printer as "Object detection, recording and 1
more off" in amber, with the other three saying "Everything on", because license plate recognition
is off for the whole server and so is not counted. Appearance switched the whole app to Night, and
the amber line turned to a lighter orange that still read against the dark card. Updates said 0.9.2
was the newest, and About sat under it.

## The cards reach the rest of the app

The grouped cards were picked for Settings first, and the pages behind it were still a stack of
panels: the eight switches on a bare page, Alerts and Detections as two wide cards, Review and
Explore as a list under two rows of chips. They now use the same pieces, lifted into
`ui/components/Cards.kt` so one group of rows looks the same everywhere: a small heading, a card,
a leading icon per row, and a divider that starts clear of the icon.

The eight switches are grouped into detection, recording, live view and recognition. That is the
order Frigate itself uses, and it means the page can be read in four parts rather than eight lines.
A switch the server holds off still says **Off for the whole server.** under its name, with its
switch greyed.

Alerts and detections became two cards of two rows: the switch, then the objects with their names
underneath and a chevron. The **Change** button is gone, because the row that says what is picked is
the row you tap to pick it.

Review and Explore were first drawn with the filters as a card of rows, which took about 200 dp of a
700 dp screen and pushed four reviews off the bottom. Two smaller ways were drawn: one row of chips,
or a single Filters row opening a sheet. The row of chips was picked. It keeps every filter one tap
away, and the camera choice becomes a chip that names the camera instead of a chip per camera, which
is where the second row went. Show reviewed became a chip in the same row, so it stopped needing a
row of its own. The sheet was dropped because it would add a second tap and a pattern the rest of
the app doesn't use.

The chips now sit in one row: the camera, the labels, the person on Explore, the day, Show reviewed,
and Clear once anything is narrowed. The row scrolls sideways when it has to, the same as the chip
rows before it.

With the new pages, against the real server: North cam showed its eight switches in four cards, face
recognition on and license plate recognition saying it was off for the whole server. Alerts and
detections showed the camera chip, Person and Car under Alerts, and Everything else under Detections.
Review put All cameras, Labels, All time and Show reviewed on one line with the list filling the rest
of the screen, and Explore put People between Labels and All time.

## A clip is cut from a file, not from a run

The bar draws a spell of recording as one run. In 0.8.2 that joining moved into
`FrigoRepository.recordingSpans`, so it is joined before it reaches the screen and the bar is handed
runs and nothing else. The bar wanted that. Playback did not. A cut is asked for from the start of the
stretch the piece begins in, and a joined run starts at the beginning of the whole spell, so a camera
that had been recording since the evening was asked for a clip from then to now.

It showed as a bar that stopped the picture. The stretch asked for was 23:59:55 to 02:21:01, 8,466
seconds, and the cut was still arriving at 49 MB with no first frame, so the player had nothing to put
there and sat on the still.

The repository hands over Frigate's own rows again: the ten second files a cut is actually made from.
`PlayerScreen` keeps both, joins the rows into runs for the bar and for the caption, and gives playback
the rows. The same scrub now asks for 120 seconds, and the file arrives in a moment.

On the emulator, dragging the bar back to 02:22 and letting go fills the picture with the recording from
there, and it carries on at 1×: the clock burned into the picture reads 02:22:37 on the first frame and
02:23:22 forty-five seconds later. `stamp()` shows minutes, so that is as close as the readout and the
picture can be held together, and the picture is not behind the bar by more than the minute it cannot
show.


## The count comes from the summary, and the list has to reach as far back

Review now says what is left, and one button clears it. Three things about the server had to be
settled first.

`/api/review/summary` answers a day at a time: `total_alert`, `reviewed_alert`, `total_detection`,
`reviewed_detection`, one entry per day plus `last24Hours`, which is not a day and is dropped. What is
left of a day is total minus reviewed. The period on screen decides which days are added up: one day,
yesterday, the day you picked, or all of them. It is asked with `timezone`, the phone's own, because
without it the server cuts days somewhere else and the numbers do not match the day buttons.

The two halves are counted separately, and only the summary can do that in one call. The tab on screen
is asked with its labels and its camera, so its number matches its list. The other tab is asked with
the camera alone, because its labels are its own. With no labels chosen the two answers are the same,
so one call serves both.

Then the numbers did not match the lists, and that turned out to be a bug of mine. The review list had
`after=0` sent for all time, on the reading that zero is the beginning. It is not: the endpoint treats
a falsy `after` as not given and falls back to the last day, so "All time" was only ever the last
twenty-four hours, and `Load older` could not reach past it either. The summary said 1,471 alerts were
left and the list showed fifteen. Sending one, the first second of 1970, lists all 1,471. A day's
alerts, and the count beside them, now agree.

Clearing happens in two requests' worth of work, however many reviews there are. The ids are collected
first, walking the same filters the list uses, two hundred at a time; then the whole set goes to
`/api/reviews/viewed` once. On this server that is eight small calls and 0.06 s each. The walk stops at
two thousand, which is more than a phone should ever clear in one tap.

There is no confirmation dialog. The list empties, the number goes, and `Put them back` sits in the
empty state with the ids that were cleared, so the mistake is one tap to undo. A dialog before every
clear would be noise on the days when clearing a morning's alerts is the whole point.

Checked on the emulator against the real server: 15 alerts for today, `Mark all as reviewed`, the two
numbers falling to nothing, `No alerts left to review.`, and `Put them back` returning all fifteen. The
four detections marked while testing were put back on the server afterwards.

## The calendar is one I drew, because the dots had to live on it

The day chip opened Material's own month. It draws a month well and there is no way to hang anything
off a day: `DatePicker` takes a selected day and nothing else, so a dot under the days with something
left was not going to happen inside it. Writing the month out is about a hundred lines: a grid of
seven columns, a header, two arrows, and a cell per day. In exchange each cell is mine to mark, to dim
and to close.

What is marked comes from the same summary the numbers use. `daysWithLeft` walks the day counts and
keeps the days where total minus reviewed is above nothing, so the dot under a day and the number
beside the tab are the same answer twice. The dots follow the tab: a day with three alerts and no
detections gets a dot on Alerts and none on Detections. Explore has no summary behind it, so it passes
no days and the calendar is only a calendar.

The weeks start where the phone says they start, through `WeekFields.of(Locale.getDefault())`, and
the letters along the top follow. Material's calendar does the same, and a calendar that begins on a
different day than the phone's own would read as a mistake.

Days ahead of today are dimmed and cannot be picked, and the arrow to the next month stops at this
one. Nothing has been recorded in a month that has not begun, so there is nothing to reach.

The month is worked out by `monthWeeks`, which turns a `YearMonth` into weeks of seven cells with
blanks at the edges. Seven unit tests cover it: a month starting on a Sunday leaves six blanks, a
month starting on the first day of the week starts at the first cell, a Sunday-first week puts Sunday
first, every day appears once and in order, every week is seven cells, a leap February keeps its
twenty-ninth, and the blanks never sit in the middle. Without those the grid is off by a day at some
month somewhere and only the eye would catch it.

One smaller thing on the same screen. The two numbers on the halves came out stacked on top of each
other inside `SegmentedButton`, so a button laid out its own children on top of the label it was
given. They are one `Text` holding an `AnnotatedString` now, which draws in one line and reads to a
screen reader as one label rather than two.

## A stretch goes to the phone or stays on the server

Save used to mean one thing: a file on the phone. It means two now, and the two are close enough in
sound that a pair of buttons side by side would need a sentence each for the difference to land.
One pill opens a sheet with two lines, each with its own sentence, and the sheet is where the answer
belongs. A second button under the picture would also have pushed the bar's own row about.

The export endpoints, checked against 0.18.2 with a real sixty second clip of `door_cam`:

- `POST /api/export/{camera}/start/{start}/end/{end}` with `{"source":"recordings","name":…,
  "image_path":""}` answers `{success, message, export_id, status}` and queues the work. A stretch
  the camera did not record is HTTP 400 with `{"success":false,"message":"No recordings found for time
  range"}`, which is put into words as "Frigate has no recording of that stretch."
- `GET /api/exports` lists them newest first, with `id, camera, name, date, video_path, thumb_path,
  in_progress, export_case`.
- `PATCH /api/export/{id}/rename` with `{"name":…}`, and `POST /api/exports/delete` with `{"ids":[…]}`.

The files sit under `/media/frigate`, and the addresses that serve them leave that folder off: the
video at `/exports/<basename>.mp4` (a real length, `Accept-Ranges: bytes`) and the picture at
`/clips/export/<id>.webp`. `servedPath` cuts the prefix where it is there and falls back to the folder
the file belongs in, so a server that reports the path differently still gets a working address.

`date` is when the stretch starts, not when it was kept. The field is documented as the time the
export was created, and the times say otherwise: a clip asked for at ten in the morning with a start
of 08:54 came back carrying 08:54, as did both of the server's own older exports against their file
names. So the list says the moment the footage is from. The first version of the row said "Kept 1 hr.
ago" for a clip kept a minute earlier, which is how the difference was found.

A clip the app kept is named after its camera and its moment, `Door cam Tue 3 Nov 08:54`, so the row
drops its own line for the moment when the name already ends with it. A clip named by hand keeps it.

Kept clips are fetched into the cache and played the way every other clip is, under `kept-<id>`. The
endpoint does support ranges, but a player on a home connection still benefits from the whole file,
and the code, the repair pass and the trim already exist.

## The clips list has its own tab

Five tabs is more than four, and the alternative was worse. Settings would have hidden a library
behind a page you go to for the switches, Explore mixes searching with a library, and reaching the
list only through the sheet means you can only look at what you have kept when you are keeping
something new. On the bottom bar it is one tap from anywhere, and the filmstrip says what is behind
it.

A row keeps the shape the rest of the app uses: `surfaceContainerLow`, sixteen corners, the picture
on the left. A clip still being cut has a spinner over its picture and does not open. The list asks
again every three seconds while one is in progress and stops when none is, so a clip is never left
saying it is being cut when it is done.

Delete takes the row away first and puts it back if the server turns it down, so a wrong tap is
visible rather than lost. Rename and delete both wait behind a dialog, delete because the file is
gone for good, rename because the name is typed.


## Sign out belongs to the server it signs out of

Sign out was a filled button across the width at the bottom of Settings. It read as the most
important thing on the page, and it sat right where a thumb comes to rest when the page is scrolled
to the end, so a wrong tap was easy.

It is a text button at the foot of the Server card instead, under the name it's signed in as. That
card already says which server you're on and who you are, so the way out of it belongs there, and a
text button is the size the rest of the cards use for a plain action.

## A first run that explains the two Android permissions

Frigo needs two things from Android that it cannot grant itself: permission to post a notification, and
being left out of battery optimisation. Both are easy to skip, and skipping them leaves an app that
looks broken. The first run says what each one is for, and opens the page that owns it.

The alerts page has four states, because the answer comes from Android and not from the app:

- Nothing ticked: the button is dim, and a line under it says a camera and an object are what it needs.
  Alerts start with nothing ticked, so nobody gets a buzz they did not ask for.
- Ticked: the button turns on, and a line says Android asks about notifications once.
- Allowed: a card with a tick and the picks, no camera list. It is done, so the page stops asking.
- Refused: an error card that says the phone's settings are where to change it, and a text button that
  opens them. The picks are kept, so allowing it there costs nothing but the one switch, and coming back
  to the app finds the new answer and moves the page on by itself.

The battery page is read again on every return for the same reason. Android's screen belongs to Android,
so a page that assumed its own answer would sit there saying On for Frigo after the fix.

## The walkthrough is remembered, and one flag decides

`onboarded` is a boolean in the same preferences as the server. Signing out does not clear it, so a phone
that signs out lands on the plain sign-in page rather than the tour again.

An install that predates the walkthrough never sees it. The flag starts absent, and the first launch
after the update reads it as "asked already" when a server is saved on the phone. The launch writes that
answer down, so the question is settled once instead of guessed at every start. A phone with nothing
saved writes false and keeps the walkthrough, including after it signs in half way through: the sign-in
happens inside the tour, so the saved server is no evidence of an old install.

The app holds the answer as a nullable flow and shows its own spinner until it is read. A new phone would
otherwise flash the tabs before the welcome, and an old one the welcome before the tabs.

Settings can ask for the walkthrough again with a text button in the About card. It is a text button
because it is a small occasional thing, which is the shape the rest of the cards use for one.

## The walkthrough draws its pictures instead of showing yours

The first run shows what a tab looks like, and the honest picture would be your own cameras. It is not
yours, so the pictures are drawn: a porch, a driveway, a back yard and a garage, in `SceneArt.kt`.

They are Compose vectors on a 160 by 100 box, scaled to whatever box they land in. Nothing to ship as an
image file, and the same drawing serves the welcome, the four tiles, the rows and the clip frame.

The box is wider or narrower than the drawing on most pages, so the drawing is cropped at the edges
rather than squashed, the way a camera picture is. That is why the clip frame is 16:9: a wider frame
crops a 160 by 100 drawing down to its middle, and the car fell off the bottom of it.

Daylight scenes, in both themes. A night version was drawn first and dropped: it is near black on a light
card, and at thumbnail size the four scenes look alike once the only light left is a lamp. A drawing does
not change with the theme, so one set reads the same in Day and Night, and the page around it is what
changes.

The bar under the Cameras page copies the real strip rather than approximating it: the kept hours as a
wash of the page ink, and the flags as slivers inside the band, in the same red and amber the timeline
uses.

## The sign-in form is a card of rows, so the first thing you see looks like the app

The three fields were outlined boxes on the page, which is a shape nothing else in the app uses. Every
other screen is filled cards with a low-contrast block for each line, so the form is now the same: one
filled block per line, an icon at the left of each, and the name of the field above what you type.

That costs the material text field's own container, so `SignInForm.kt` builds the line from a
`BasicTextField` inside a `Row`. The focused ring is two dp wide and always there, clear until the field
has the cursor, because a ring that appears on focus moves the text two dp sideways otherwise.

The password carries an eye at its end. It is the one field where what you typed is hidden from you,
which is what makes typing it wrong easy to do twice.

The words are the same as before, including the port hint, which sits under the address it belongs to
rather than loose between two boxes.

## The walkthrough splits the Cameras tab in two

One page was trying to describe two screens: the grid of four cameras, and one camera opened with the
day under it. The words had to serve both, so they served neither well.

The walkthrough is six pages now. The first is the grid on its own. The second is a camera open: the
picture with its Live tag, the day strip, and an hour of the bar with the playhead in the middle. Both
keep Cameras lit in the bar at the bottom, because the second is reached from the first. The dots go to
six, and the last page's button says Done.

The figures are drawn at the real bar's own heights, a minute a line and the bands at 20 to 36 dp and
the slivers to 52, so the small copy reads as the same thing as the bar it stands in for.

## The live picture keeps a camera's shape

The walkthrough's frame was one height for all six pages, 246 dp, so the page with the day strip and the
hour bar under the picture left the picture the rest of the room: about 2.5 to 1 on a wide phone. A
camera is not that shape, so the page taught the wrong picture.

The frame on that page is now worked out from the width: 16:9 inside the 12 dp of padding, then the strip,
the hour bar and the two gaps added on. About 305 dp on a 411 dp phone. The other pages keep the fixed
height, because a clip frame and a list of rows are the shapes they are.

The trade was one page with its own height. The alternative was dropping the hour bar so the frame stayed
short, which would have left the strip as the only thing under the picture.

## Every thumbnail wears the same corners

The 112 by 63 stills on the Review, Explore and Clips pages were square, while the small camera tiles
under a live picture have an 8 dp corner. Two shapes for the same kind of thing reads as an oversight,
so the three pages now use the 8 dp corner too: on the picture alone where there is no overlay, and on
the box around it where a play mark sits on top, so the mark cannot spill past the corner.

The Clips page is titled Saved clips. A clip there is on the server, and saving one to the phone is the
row's own action, so the title names where the clips are rather than what you did. The empty state
follows it: nothing saved yet.

## Alerts follow the server, not a list on the phone

The phone kept its own two lists: which cameras buzz, and which objects. They were convenient and they
were wrong. Frigate already knows which cameras may tell anyone anything, on its notifications page, and
which objects make an alert, on its alerts page. A phone with a third opinion needed both kept in step by
hand, and the usual failure was quiet: a camera turned on in a browser, the phone still silent, nothing
saying why.

Three answers now decide a buzz, all on the server: notifications on for the server, notifications on for
that camera, and that camera's alerts on with the object in its list. Turning any one of them off silences
the phone, which is the same rule Frigate applies to its own notifications.

The app keeps a copy of those answers rather than an opinion. The copy is refreshed when the app opens,
when the socket reconnects, and after a change made on the Review page. It is stored on the phone so a
restart with no signal still knows what to watch, and so the quiet notification can say what is happening
without asking first. A change made in a browser lands the next time the app looks, which is when you open
it.

The cost is that the phone cannot be quieter than the server. There is no per-phone narrowing any more: a
camera that buzzes in Frigate's own app buzzes here too, and the only place to change it is the server.
That is the trade the plan took, and it is worth saying out loud.

A camera with no alert list of its own follows the server-wide list, because that is what Frigate's own
config merge does. Reading a hardcoded `person, car` in that gap, which is what the code did before, meant
the phone could disagree with the server about a camera nobody had touched.

Two ways of buzzing are tried behind a switch in Settings, and one of them will be kept:

- Per event, on the `events` topic: a buzz for each thing the server tracks, held to one per camera every
  twenty seconds.
- Per happening, on the `reviews` topic: a buzz for each moment the server calls an alert. A review that
  starts as a detection buzzes when it turns into an alert, but only inside ten seconds of its own start,
  which is the window a zone needs. A review that changes twice buzzes once, because its id is kept for
  three minutes.

The review topic is the one that matches what the alerts page means by an alert. The event topic is what
the app shipped with, and it is cheaper to reason about, so both run until one has been tried on real
cameras.

Turning notifications off on the server, or off on every camera, would otherwise look like a broken app.
The quiet notification and the Settings row both say which switch is doing it.

## The settings pages read a copy when the server is away, and one phone holds several servers

Two things that arrived together. The settings pages had to open while a server wasn't answering, and a
phone with two servers needs that twice over.

Every settings page used to be a round trip: `/api/config` and `/api/profile`, or no page. The pages are
now built from those two answers, and the answers are kept on the phone as one block whenever they arrive.
A live answer is used as it is. The copy only stands in when the call failed in a way that means the server
was never reached: no network, a name that doesn't resolve, or a proxy saying 502, 503 or 504. Anything the
server actually answered is passed on as before, so an aged-out session still lands on the sign-in page and
a certificate the phone won't take still says what it is. Reading a copy in those two cases would hide a
problem with a stale page.

The copy is read-only. Each page says when it was read and turns its switches and rows off. Changing a
setting against a stale answer would be a guess about what the server has now, and sending that guess when
the server came back is how a phone ends up disagreeing with the box. Queuing the changes was the other
option and it was turned down for the same reason as the last release's per-phone alert list: a second
opinion about settings is the thing being removed, not added.

The page shapes are not new. `CameraSwitchPage` and `ReviewPage` are still what the screens read, built from
the copy instead of from a call, and each carries a `readAt` that is null when the answer is live. That is
what the banner and the disabled switches turn on, and it keeps the screens free of any knowledge of where
the answer came from.

Servers are a list with one active. Each entry keeps its own token, so a switch is one tap and no signing in
again. What a server decides for itself is keyed by its address in DataStore: its alerts switch, the copy of
its rules, its camera layout, and the cached settings. The theme, the live tile switch, the walkthrough and
the per-camera picture sizes stay with the phone, because they are about how this phone is used rather than
whose box it is looking at.

A phone from 0.10.4 has one server under keys of its own. The first read turns those into a list of one,
hands its alerts switch, rules and layout over to it, and removes the old keys.

Signing out forgets the server rather than only dropping its token. The alternative, leaving a row behind
for every server you signed out of, fills the list with servers you said goodbye to, and there is no second
use for that row.

Switching resets the tabs. `MainScaffold` is keyed on the active address, so the navigation controller and
the view models above it start again rather than showing what the last server answered. The alert socket is
restarted with an action of its own, because leaving a connection to the old server open while the settings
copy is the new one is the one state that would buzz about the wrong cameras.

## All Cameras is a batch, not a master switch

The app read the top switch on the server's notifications page as a gate: with it off, nothing on any
camera could buzz, and Settings said so in red. Someone running a server with that switch off and every
camera on got a phone that reported itself silent and a page telling them nothing would buzz.

The switch is a batch over the cameras. It turns them on and off together, and turning a camera on by
itself afterwards is the state the server keeps, which is how Frigate's own page describes it: enable
notifications for the desired cameras. So each camera's own answer decides a buzz, and the server-wide
value is no longer asked. It is still read from `/api/config`, because the file has it, but nothing
branches on it.

The two places that said "Notifications are off on your server" are gone. What is left is the one state
that really does silence every camera: every camera off. Both the banner on the settings page and the
quiet notification say that, and the copy is unchanged, because a buzz still follows the server.

## Quiet hours live on the phone

Everything else about a buzz is the server's. Quiet hours cannot be, because there is nothing for the server to
hold: Frigate publishes reviews whether or not any phone is listening, and what has to go quiet is one phone's
notifications. A Frigo-only key in Frigate's config would make the server the owner of a setting it cannot act
on, and Frigate's own page would neither show it nor keep it. So the window is kept in DataStore under the
server's address, the way that server's alerts switch is, and the server is never asked about it.

The rule that the phone cannot be louder than the server still holds. Quiet hours only ever take a buzz away.

An alert inside the window is dropped rather than posted silently. A silent notification still lights some
screens, still fills the shade, and still costs a thumbnail and a model call, and half the point is the night
going by without a pile of them. Review keeps what the server saw, so nothing is lost beyond what the server
had already lost. The review is marked as buzzed when it is dropped, so one that changes again does not get a
second chance the moment the window ends.

The window is half open: from the start minute up to it, and not the end minute itself. It wraps when the
start is later than the end, which is the usual night. Two equal times mean all day, the one reading that
makes a single pair of times mean a single stretch. A window with no camera picked silences nothing, so a
half-finished setup fails towards still buzzing.

The quiet notification has to say when it ends, for the same reason every other silence in this app says why:
going quiet without a word reads as a fault. It is redrawn from a wait inside the service rather than from an
alarm, because the service is already alive and a service Android has killed has nothing to redraw anyway. The
wait is until the next boundary - the window opening or closing, or a hush running out - which also lets a
clock change or a flight to another time zone correct itself within one boundary.

The hush is an hour over every camera. It is the "be quiet now" that does not edit the times, and keeping it
separate means a quick silence cannot quietly become the new schedule.

Coverage is per camera because that is the case worth a setting: the street going quiet overnight while the
front door still tells you. It also means a camera renamed on the server drops out of the set, which is why
the page lists every camera rather than only the picked ones.

## The app speaks five languages

Every word the app says now lives in `values/strings.xml`, with Spanish, French,
German and Portuguese beside it in `values-es`, `values-fr`, `values-de` and
`values-pt`. Nothing in the Kotlin says anything to a person.

**Why the standard files rather than a word list in the code.** A string file is
what Android, translators and every tool already understand. The picker reads the
same place it writes, so it agrees with Android's own per-app language screen on a
phone that has one.

**Why AppCompat.** Android 13 keeps a per-app language itself, and this app still
runs on Android 8. `AppCompatDelegate` does the same job from API 21 up, and on 13
and later it hands the choice to the platform rather than keeping a second copy.
That is the only reason the app depends on it; `MainActivity` is an
`AppCompatActivity` and the window theme is AppCompat's so it can be one.

AppCompat stores the choice (`autoStoreLocales`) and puts it on before the first
screen is built. Without that, a phone in French would open in English and turn
over a moment later.

`res/xml/locales_config.xml` is what puts Frigo in Android's own language list. It
is the same list the picker shows, in the same order.

**Dates follow the app, not the phone.** A screen in German with "Sun 1 Nov" under
it is a mistake you notice at once. The readout under a camera's bar, the day
chips, the calendar's month and weekday names, and "14 min ago" are all built with
the app's language. The 12- or 24-hour clock stays the phone's, because that is a
habit rather than a language.

**What stays in English.** Frigate's own object labels (person, car), the names of
your cameras, and anything the server sends back as a refusal. Frigate's web page
shows the same words, and a phone that invented its own names for them would
disagree with the server it is looking at. The question the app asks a model about
a picture stays English too: it is written for the model, not for a person, and
the sentence that comes back is the server's.

**The words that aren't on a screen.** The alert notification, the "Watching for
alerts" line and the messages the data layer builds are posted from a service,
which is handed the phone's context rather than the app's. `Context.words(...)`
and `Context.counted(...)` resolve a string in the app's language for those three
places. Without them a phone in French would have a French app and an English
buzz.

**The cost.** AppCompat is in the APK for one job. The translations were written
by the same hand as the English, so a wrong word is wrong in four languages at
once; the glossary of Frigate's terms is the thing to keep consistent.

**The tests.** Five of them read sentences that are now resources, so they run
under Robolectric with the real strings. That also means a missing key fails a
test rather than showing a blank line on a phone. It is a test-only dependency:
none of it is in the APK.

## The lock asks on a fresh start, not on every return

A camera app is something you flick to for a look at the drive. Asking for a
fingerprint every time you come back is the thing people switch off after a week,
and then it is off for good. A fresh start is the moment someone else has the
phone in their hand.

So the answer lives for as long as the process does, on the container rather than
in the activity. Coming back to the app is the same process, so it stays open; a
cold start is a new one, so it asks again. A turn of the phone never reaches it,
because the manifest hands that to the activity rather than rebuilding it.

Rejected: locking on every resume, and locking after a few minutes away. Both ask
at a moment you did not choose.

## The switch only turns on through the phone's own prompt

Turning it on runs the prompt first, and only a success writes the switch. A lock
you can switch on without being able to get back through is a brick with a
switch on it.

A phone with no screen lock at all cannot turn it on, and the row says why. If the
screen lock is removed later, the cover opens on a tap and says so. Trapping
someone out of their own app is worse than being unlocked for a while.

Turning it off never asks. You are already inside, which means you already got
through it.

## Android 11 draws one sheet, and older versions need two paths

The platform will not put a weak sensor and the PIN in one sheet before Android
11, and asking for `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` on Android 9 would
refuse a phone whose face unlock is weak. So there are two shapes:

- Android 11 and later: one sheet, `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`. It
  brings its own cancel and refuses a negative button, which is why there is none.
- Android 8 to 10: `BIOMETRIC_WEAK` alone, with "Use my PIN" as the negative
  button, opening `KeyguardManager`'s own credential screen.

Both end in the same place, so the cover does not care which one ran.

## The cover is drawn over the app, not instead of it

The app is built under the cover rather than after it, so unlocking lands on
cameras that have already loaded rather than on a spinner. The cost is a second
or two of camera traffic while the cover is up, which is the time it takes to
unlock.

The cover itself is opaque. The first version was the app's background at 88%,
which let the screen underneath read through: an error line and a Try again
button sat across the padlock, and a camera picture would have been a smudge of
the thing the lock exists to hide. What is left is the tab bar, drawn rather than
shown, at 30%, which says where you are about to land without saying anything
about what is behind.

Rejected: pausing the screens' loops while covered. It would mean threading a
flag through all five screens to save a second of traffic.

## FLAG_SECURE for as long as the switch is on

A lock that leaves the last camera picture in the recent apps card is half a
lock. With the switch on, the app is a blank card there and a screenshot comes
out blank.

It reaches further than that: no screen recording, no casting, and no screenshot
of anything in the app, camera views included. That is the same rule seen from
another angle, and it is worth knowing before it is reported as a bug.

Notifications keep their pictures. An alert's picture is the whole reason it is
useful, and it is already on the lock screen. Hiding it would mean the
notification says something happened without saying what.
