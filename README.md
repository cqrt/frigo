# Frigo

Frigo puts your Frigate cameras on your phone. It talks to the Frigate server you
already run, so there is nothing to install on the server side.

## What it does

**Cameras.** Your cameras, each picture updating about once a second. One button
changes how they're laid out: two to a row, one to a line, or one picture filling
the width with its name on it. Pull the list down and every camera fetches a
fresh picture. Tap one and it opens as a live view. Pinch to zoom in, drag to
move around, and double tap to go back to the whole picture.

The frame button in the corner turns the phone on its side and takes the bars
away, so the picture gets the whole screen. The same mark brings them back, and
so does Back.

**Review.** The moments Frigate thought were worth a look, newest first. Frigate
groups what it saw into these, so a visitor who stays a while is one review
rather than twenty events. Tap one to watch the clip of that time. Alerts and
detections are separate halves of the list. Watch past halfway and the review
counts as watched, here and on the web page, so it leaves the list. Show reviewed
brings the watched ones back. Pull the list down for what has happened since.

**Explore.** What Frigate has recorded, newest first, with thumbnails, the way
the Explore page on the server has it. Narrow it by camera, by what was seen, by
the person Frigate recognised, and by day: today, yesterday, or a day you pick
from a calendar. Pull the list down to run the search again. Tap an event to
watch it. An event with a play mark on its thumbnail has a clip, and the clip
plays with a scrub bar. In fullscreen it keeps the whole screen until you leave
it. One without a play mark shows the still Frigate kept.

**Alerts.** Your phone tells you when Frigate sees one of the things you picked,
with a picture. Tap the alert and that camera opens. The back arrow goes to the
camera grid.

**Settings.** Shows which server you are signed in to, what to tell you about,
and signs you out. A switch here plays the home page tiles live, which costs more
data and battery.

## Signing in

You enter the same address you use in a browser, your username, and your
password. If you reach Frigate through a reverse proxy, that is the proxy address,
port included. The app keeps the sign-in on the phone.

If your Frigate has roles set up, you see the cameras your role allows and no
others.

Frigate's own certificate is one it made for itself, and a phone refuses those. If
the app says it doesn't trust the certificate, sign in through the address your
proxy serves, which has a certificate from a real authority.

Frigate's session lasts a day, and it is lengthened every time you open the app.
Leave it longer than that and you sign in again, with your address and username
already filled in.

## How live view works

The full screen player asks the server for a stream of still frames over the same
HTTPS connection as everything else (`/api/<camera>`). The tiles on the home page
ask for one picture a second instead, which is cheap. Switch live pictures on in
Settings and each tile keeps the same stream open at five frames a second and
360p, which is smoother and costs more data and battery.

That choice follows Frigate's own reasoning. Frigate deliberately serves its web
live view as MSE rather than WebRTC, because MSE runs over TCP and every frame
arrives, while WebRTC drops late frames on a poor connection. An Android app
cannot use MSE, so the closest equivalent is this JPEG stream: same TCP, same
guarantee, and it needs no extra ports or router changes.

The trade is bandwidth, since every frame is a whole picture. The player's menu
sets the height and frame rate, and the label in the corner tells you which you
are on.

Zooming scales the picture the phone already has, so the stream keeps asking for
the same frames. At 720p and 5x it is soft, which is why the menu offers 1080p.

WebRTC through go2rtc is planned as an option for low latency. It needs port 8555
forwarded to your Frigate machine, both TCP and UDP. Without that forward, WebRTC
connects and then shows nothing, which is why it is not the default.

## How alerts reach your phone

Frigate publishes each new event to a message feed, the same one its own web page
listens to (`/ws`). The app keeps a single connection open to that feed, so an
alert arrives as Frigate sees something rather than at the next poll.

The connection lives in a background service, because Android will not let an app
hold one otherwise. Android shows a notification for any such service, so there is
a quiet "Watching for alerts" one in the shade. It says how many cameras are
ticked and whether the connection is up, and it is the app's way of saying it is
still there. Switching alerts off in Settings stops the service and takes the
notification away.

You choose what buzzes: the cameras, the objects, or both. The first time you
switch alerts on, every camera is ticked and `person` is picked if your server
looks for it. A camera that has just buzzed stays quiet for twenty seconds, so one
visitor walking past is one notification.

Android can stop a background app to save battery, and it will do that after a
while on its own. If alerts go quiet, open the app once and they start again.
"Battery settings" in Settings is where you make that less likely.

The app does not use Frigate's own web push. That needs a browser's push service,
which a native app cannot register with. The message feed is the same source, and
it works over the address you already have.

## How clips play

Frigate keeps no file per event. When you tap an event, it cuts that stretch out
of your camera's recordings and sends it as it goes. So the clip needs recordings
switched on, and it lasts as long as they do: once the recordings have aged out,
the event keeps its still and its clip is gone.

The app fetches the whole clip first, showing how much has arrived, then plays it
from the phone. A clip sent straight into the player stalls on a slow connection,
and the player gives up on it after ten seconds without progress. Fetched first, it
starts at once and you can scrub through it. A clip you've watched is kept for a
day, so watching it again costs nothing.

Some clips arrive from Frigate saying the wrong thing about their own timing. A
player reads that as a wait of hours and sits on the first frame. The app corrects
the timing before it plays, and the picture and sound themselves are untouched.

## What counts as a review

A review is a stretch of time, not a single object. While something stays in a
zone, Frigate keeps one review open and adds to it. When it leaves, the review
closes. A visitor who stands at the door for two minutes is one review, not
twenty events.

Reviews come in two kinds, and the list has a button for each. An alert is
something Frigate saw in a zone you set alerts on. A detection is everything
else, which is the longer list.

A review's picture is the first thing it saw. Frigate keeps a separate thumbnail
for the review, but it sits behind the web server rather than the API, so the app
takes the event's picture instead, which is the same one.

## Building it

You need JDK 17 to 23, the Android SDK with platform 36, and nothing else.

```
./gradlew :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. For a release build, put
your keystore details in a gitignored `keystore.properties`, or set the
`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` environment
variables:

```
./gradlew :app:assembleRelease
```

## Releasing

You cut a release on the GitHub website. Open Actions, pick Release, run the
workflow, and type the version as `0.7.3`. It builds the signed APK, tags the
commit, and puts the APK on the release page. Pushing a `v0.7.3` tag does the
same thing if you'd rather do that from the command line.

The last number counts up for each release, from `0.7.0` to `0.7.99`, and only
then does the middle number move on to `0.8.0`. So a fix or a small change is
`0.7.4`, and the releases stay in order without a new number every time.

The version code comes from the version in the tag, so `0.7.3` becomes 703. A
phone with 0.7.3 already installed will take 0.7.4 as an update without you
uninstalling anything.

The key that signs the APK lives in the repository's secrets, so no key material
is ever committed. Backup: there's a copy of the key and its password at
`~/.frigo/frigo-release.jks`, and the password in the gitignored
`keystore.properties`. Lose that and no future build will install over this one.

## Where things live

- `data/FrigateApi.kt` - the Frigate endpoints this app uses, written against OkHttp.
- `data/FrigoRepository.kt` - tokens, renewal, and the calls the screens make.
- `data/MjpegReader.kt` - pulls JPEG frames out of the live stream.
- `alerts/` - the service that holds the message feed and the notifications it posts.
- `ui/` - one folder per screen, Compose throughout.
- `ui/components/ClipViewer.kt` - the full screen clip player that an event or a review opens.
