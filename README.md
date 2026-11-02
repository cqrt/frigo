# Frigo

Frigo puts your Frigate cameras on your phone. It talks to the Frigate server you
already run, so there is nothing to install on the server side.

## What it does

**Cameras.** Your cameras, each picture updating about once a second. One button
changes how they're laid out: two to a row, or one picture filling the width with
its name on it. Pull the list down and every camera fetches a fresh picture. Tap one and it opens as a live view. Pinch to zoom in, drag to
move around, and double tap to go back to the whole picture. The other cameras sit
underneath, a fresh picture of each; tap one to bring it up without going back to
the grid.

Turn the phone on its side and the picture gets the whole screen, with no bars
around it. Turn it back and the bar comes with it. There's no button for it.

A camera opens with the day's bar under the picture. An hour fits across the
screen, the hours Frigate kept are filled in, the gaps are left grey, and a spike
runs along the bottom for everything it flagged: red for an alert, amber for a
detection. Pick the day from the buttons along the top, then drag the bar. The
hour slides past, a step every thirty seconds, and the picture follows it. The
line under the bar says where you are, day and all: **Sun 1 Nov 18:34**.

While you watch the stream the bar keeps up with the clock, so the filled hours
and the spikes run on to the moment you're at. They're fetched again every
twenty seconds for the last few minutes and joined onto what the bar already
has, rather than asking for the whole day again.

Let go and the recording plays from where the bar stopped, and the playhead walks
along as it plays. The button in the corner cycles the speed: 1, 2, 4, 8 and 16
times. When the run reaches a stretch with nothing recorded, it carries on at the
next stretch. Touch the bar and the picture stops and goes back to the still. The
speaker in the corner turns the sound on and off. Return to Live puts the bar back
on today and on now, and takes you back to the stream.

The corner of the picture says which you're looking at: **Live** with a red dot, or
**Playback** with a blue one. The dot brightens and dims, slowly, so it doesn't
nag. On its side there's no bar, and the pill is what tells you where you are. When
a camera stops sending, the pill turns grey and reads **Offline**: what's on the
picture is the last frame it sent, and it would otherwise pass for a live one.

The thin strip above the bar is the whole day. Tap it to go anywhere in it, which
is quicker than scrolling an hour at a time.

The other cameras sit underneath at the same moment, each with its name. Tap one
and it comes up in the picture at the same moment, so you can follow something
from camera to camera. If the recording is playing, it plays on there from where the
picture had reached. Over a gap, the picture goes blank and the line underneath
says nothing was recorded then. On its side the timeline goes and the picture fills
the screen.

On a recording, **Save** in the bottom corner asks where the stretch named under the
time should go. Save to this phone keeps it as a file: it asks where to put it
first, fetches nothing until you've chosen, then shows how much has arrived.
Nothing lands in your photos and there's no permission to grant. Keep on the
server has Frigate cut the stretch out itself and hold it, so it's still there
after the recordings it came from have aged out. The sheet says what happened
before it closes, and its last line goes to the clips you've kept already.

**Review.** The moments Frigate thought were worth a look, newest first. Frigate
groups what it saw into these, so a visitor who stays a while is one review
rather than twenty events. Tap one to watch the clip of that time. Alerts and
detections are separate halves of the list, and each half says how many are left.
Watch past halfway and the review counts as watched, here and on the web page, so
it leaves the list. Show reviewed brings the watched ones back. When you've seen
enough of a day, Mark all as reviewed clears the rest without playing any of it,
and Put them back undoes that. Pull the list down for what has happened since. The
tune icon in the corner opens Alerts and detections, where you choose the objects
behind each.

**Explore.** What Frigate has recorded, newest first, with thumbnails, the way
the Explore page on the server has it. Narrow it by camera, by what was seen, by
the person Frigate recognised, and by day: today, yesterday, or a day you pick
from a calendar. Pull the list down to run the search again. Tap an event to
watch it. An event with a play mark on its thumbnail has a clip, and the clip
plays with a scrub bar. On its side the clip fills the screen and the close mark
sits on the picture. One without a play mark shows the still Frigate kept.

**Clips.** The stretches you've kept on the server, newest first, each with the
picture Frigate made of it and the moment it starts. Tap one to watch it. The three
dots offer the rest: save a copy to your phone, give it another name, or delete it
from the server. One that Frigate is still cutting out says so, and fills itself in
when it's done. Pull the list down to ask again.

**Alerts.** Your phone tells you when Frigate sees one of the things you picked,
with a picture. Tap the alert and that camera opens. The back arrow goes to the
camera grid.

**Settings.** Shows which server you are signed in to, signs you out, and holds
two switches: one for notifications, and one that plays the home page tiles live.
You pick the cameras and objects that buzz. There's a link to the phone's battery
settings, because that's where you stop Android shutting Frigo down. Under
Appearance you choose the colours: System follows the phone, Day stays light, and
Night stays dark. Under Updates it says which version you're on, and whether
there's a newer one to install.

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

The full screen player asks for the camera's own video (`/api/stream.mp4?src=<camera>`).
Frigate ships a stream server called go2rtc, which already holds your cameras, and your
proxy hands that path to it the same way it hands over the sound. So the picture is the
2K one Frigate records, about a second behind the clock, with the sound arriving on the
same connection. H.264 sends only what changed between frames, so the whole picture costs
about a fifteenth of the small one.

It comes over HTTP rather than WebRTC. That runs over TCP, so frames arrive rather than
late ones being dropped, and it needs no extra port on your router.

When the video is slow to arrive, Frigate's small picture is drawn in the same place until
it does, and the video takes over the moment it has a frame. If it never comes, the stills
stay and a line under the picture says so. Zooming scales the picture the phone already has
either way, so the stream keeps its own size and rate.

Leave the app and come back, and the stream is opened again rather than carried on from
where it stopped. Picked up where it stopped, a picture paused for two minutes would come
back two minutes behind the clock, still saying Live.

The stills are one whole frame at a time, kept at the size Frigate decodes for detection:
640x360 on the two outdoor cameras, 1280x960 on the door, 1280x720 on the printer. Frigate
scales each frame up to whatever it's asked for, so asking for more than the camera sends
only buys a bigger copy of the same pixels. The player's menu sets the height and frame rate
for that stream, offers only what the camera has, and ticks what's on. Each camera keeps its
own choice, and both keep their setting after the app is closed. The menu is only there
while the stills are what's playing, since it changes nothing about a recording and the
camera's own stream decides its own picture.

The tiles on the home page are separate: they ask for one still a second, which is cheap.
Switch live pictures on in Settings and each tile keeps a stills stream open at five frames
a second and 360p, which is smoother and costs more data and battery.

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
starts at once and you can scrub through it. A clip you've watched is kept, so
watching it again costs nothing. The folder is trimmed to 600 MB when it grows past
that, oldest first.

Some clips arrive from Frigate saying the wrong thing about their own timing. A
player reads that as a wait of hours and sits on the first frame. The app corrects
the timing before it plays, and the picture and sound themselves are untouched.

Save uses the same fetched clip. A stretch you've just watched is copied off the
phone rather than asked for again, so saving it is instant. A stretch you haven't
watched is fetched first, which is why the button shows how much has arrived.

Keeping one on the server asks Frigate to cut it out instead, and the server does
the work, so you can put the phone down. The file goes into Frigate's own exports
folder, which its clean-up leaves alone, and the list of them is what the Clips tab
shows. A camera with nothing recorded across the stretch is turned down with a
reason, and the sheet says which: Frigate has no recording of that stretch.

Frigate cuts from the nearest keyframe, so the file can start up to a second before
the moment you picked. In a file you keep, that's harmless.

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

The chips above the list narrow it. The first names a camera, or all of them.
Labels picks what was seen, and the day chip covers all time, today, yesterday, or
a single day from the calendar. A dot sits under each day in the calendar that
still has something to look at on this tab. Show reviewed sits beside them. Clear
puts the narrowing back, and Load older keeps it.

The number beside each half is what's left to look at over the days the day chip
covers, so it moves when you change the day or the camera. Nothing is shown when
there's nothing left. Mark all as reviewed sits under the list, and what it
cleared can go back from the empty list it leaves.

The labels offered are the ones turned on for the tab: the alert labels on Alerts,
the detection labels on Detections. A detection list left empty means every other
object, so the chip offers the rest of what Frigate tracks. A camera with lists of
its own is offered its own.

## Alerts and detections

The tune icon on Review opens a page for the two lists. Alerts has a switch and the
objects that make one. Detections has the same, and nothing picked there means
every other object. Tap Objects to pick them, with a search box and the labels your
cameras track marked.

The labels are Frigate's own: bark, car, cat, fire alarm, glass, person, scream,
smash, smoke detector, speech and yell, plus anything already in use on your
server. A change goes to the server as you make it, so the next thing Frigate sees
follows it and nothing restarts. The chip along the top picks the scope: all
cameras, or one of them. A camera you never pick follows the shared settings.

These are Frigate's settings rather than the app's. They live in its own settings
file and the server's web page shows the same values. An admin account can change
them and anyone signed in can look.

## What each camera does

Settings lists your cameras. Tap one for eight switches: object detection, motion
detection, recording, snapshots, audio detection, birdseye, face recognition and
license plate recognition. Each one belongs to that camera alone.

Under each name it says what those switches come to, either **Everything on** or the
names of what's off. A camera with recording off doesn't need opening to find out.

They're Frigate's settings rather than the app's, so its own web page shows the
same values. A change goes to the server as you make it and is applied without a
restart. Recording asks first, because nothing new is saved once it stops.

Motion detection is worth knowing about. Turning it off doesn't quiet a camera:
Frigate then looks for objects on every frame, which is more work, not less.

Face recognition and license plate recognition only run when your server has them
on for everything. While one doesn't, its row says so and can't be turned on.

An admin account can change these and anyone signed in can look.

## Updating

Frigo isn't in a store. Each release is an APK on the GitHub release page, and the
app fetches the next one itself.

Open Settings and the Updates section asks GitHub what the newest release is. If
there's a newer one it says so and offers **Download**, then **Install** once the
file has arrived. Android asks once whether Frigo may install an app; turn on Allow
from this source and the installer opens. From there it's the same install screen
as any APK. Your server address, sign-in and settings stay where they are, because
an update replaces the app and not its data.

The APK is the same file as on the release page, so nothing is trusted that you
couldn't have downloaded and checked yourself. A check happens when you open
Settings and not in the background, and GitHub is asked at most once a minute, so
flicking between tabs doesn't spend the hourly allowance GitHub gives an
unauthenticated caller.

**What's new** opens the release page in a browser, which is where the notes are.

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
- `data/Updates.kt` - asks GitHub for the newest release, fetches its APK, and hands it to Android's installer.
- `alerts/` - the service that holds the message feed and the notifications it posts.
- `ui/` - one folder per screen, Compose throughout.
- `ui/components/ClipViewer.kt` - the full screen clip player that an event, a review or a kept clip opens.
- `ui/components/LiveSound.kt` - the camera's sound for the speaker button, when the picture is the stills stream.
- `ui/cameras/LiveVideo.kt` - the camera's own video, its sound, and keeping the picture up with the clock.
- `ui/cameras/Playback.kt` - the day's bar under a camera, its spikes, the moment's picture, and the other cameras.
- `ui/clips/` - the tab of clips kept on the server: the list, the rename and the delete, and the copy saved to the phone.
