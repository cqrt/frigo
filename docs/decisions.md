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

## Three camera layouts, one button

The home page has three looks behind one button: two to a row, one to a line, and
one picture filling the width with its name on a dark fade. The fade is there
because a camera pointed at a bright wall would leave white text unreadable.

The icon shows the look you get next, not the one you're in, so a tap always
moves you somewhere you can see coming. The choice is kept on the phone.

Material's own icon set has no play mark in a box, no magnifying glass with room
for a label, and no wide frame, so Reviews, Explore, the grid and the list marks
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

So the live picture is going to come from go2rtc instead, which holds the main streams
already and hands them out without decoding: the camera's own 2560x1440 H.264 at 190 KB a
second, about a second behind, with the sound included. The blocker is reach, not picture:
the Home Assistant add-on publishes only Frigate's ports, so `/api/go2rtc/...` answers 404
from outside and `/live/...` falls through to the web page. That needs a VPN on the phone or
a go2rtc of its own behind the tunnel before any app work is worth doing.

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
nothing was going to tell you one existed. Settings now asks GitHub for the newest release
each time it opens, and offers to fetch it.

The check is on opening Settings rather than at launch, which was a choice: a launch check
would want a notification or a prompt on a screen you opened to look at a camera, and the
one place you go when you want to change something is Settings. A check also costs nothing
until you look.

GitHub's unauthenticated allowance is 60 asks an hour per address, and a tab switch would
otherwise be another ask, so an answer stands for a minute. The button that says Check
again always asks, because it is you asking.

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
