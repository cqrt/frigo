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
"No sound came through."

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
Now a swipe left or right in the player moves on, in the same order the grid
shows, and the last camera leads round to the first so a swipe is never a dead
end.

The player already has a pinch to zoom and a drag to move around the picture, and
a sideways drag is exactly what you do while zoomed in. So the swipe keeps out of
the way when the picture is zoomed: the zoom state is lifted out of the picture
and handed back to the screen, and the swipe only listens while it is 1x. It also
only takes the events once a drag is clearly sideways and longer than the touch
slop, so taps and double taps reach the picture as before.

Swiping changes which camera the screen is showing rather than navigating to a new
screen, so Back still lands on the grid you came from, and each camera keeps its
own zoom and its own sound switch. The sound goes off with the camera, because the
next one may have none and a switch that says on while nothing plays is a lie.

Checked on the emulator against the mock: swipe left goes 3d printer, door, north,
south and round again; swipe right goes back; the same in landscape, with the bars
hidden. Sound was on for 3d printer when a swipe opened door cam, and the speaker
went off with it.

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
