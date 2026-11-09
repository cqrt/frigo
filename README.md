# Frigo

Frigo puts your Frigate cameras on your phone, and on your television. It talks
to the Frigate server you already run, so there is nothing to install on the
server side.

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
rather than twenty events. Tap one to watch the clip of that time, in a frame
clear of the status bar. What Frigate made of it sits under the clip: a name for
what happened, and the whole story under the name. Under the clip are the other
cameras at that same moment, and a tap on one opens it there. The card also says
what was seen, where, how long it ran, and anything else Frigate noticed. In the
list, a review it has described is named by its own title rather than by the
objects it saw. Frigate writes that a minute or two after a review ends, so one
that has no words yet opens on the clip and the other cameras alone. Alerts
and detections are separate halves of the list, and each half says how many are
left. Watch past halfway and the review counts as watched, here and on the web
page, so it leaves the list. Show reviewed brings the watched ones back. When
you've seen enough of a day, Mark all as reviewed clears the rest without playing
any of it, and Put them back undoes that. Pull the list down for what has happened
since. The tune icon in the corner opens Alerts and detections, which is the
server's page and only an admin can change it. On any other account, Settings,
Only these objects is where you narrow what buzzes this phone.

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

**Alerts.** Your phone tells you when your server reports an alert, with a picture
of what it saw. Tap the alert and that camera opens. The back arrow goes to the
camera grid.

**Settings.** Holds the servers you've signed in to, one under the other, with the
one you're on ticked. On an admin account, tap another and the app switches to
it, with its own sign-in, its own alerts switch and its own camera layout, and
**Add a server** signs in to one more. **Sign out** takes you off the server you're
on, and the next one takes over. **Link a TV** puts your cameras on an Android TV:
point the phone at the code on the television's screen, and type your Frigate
password once. Under Servers, an admin finds **Frigate**: Alerts
and detections, AI descriptions, and System, which is how the box is doing.
**System logs** under System shows what frigate, go2rtc and nginx have been saying,
newest at the bottom.
Then come two switches: one for notifications, and one that plays the home page
tiles live. Under notifications sit **Only these objects**, the list this phone
narrows its alerts by, then **People to leave out**, the names it stays quiet
about, then **Quiet times**, the hours it keeps quiet and the cameras it keeps
quiet.
Under **Cameras** is every camera your server has, in the order
this phone shows them. A switch there turns one off on this phone. Hold a row and
drag it to move it, and tap one for its own page, where its name here is set.
There's a link to the phone's battery settings, because that's
where you stop Android shutting Frigo down. Under Privacy, **Lock Frigo** asks
for your fingerprint, or your phone's PIN, before the app opens. It asks each
time the app starts, and again after you close it from the recent apps list.
Coming back from another app doesn't ask. While it's on, the app is
a blank card in the recent apps list, and a screenshot comes out blank. Under
Appearance you choose the
colours: System follows the phone, Day stays light, and Night stays dark. The
language sits there too: English, Spanish, French, German and Portuguese, or the
phone's own. Under
Updates it says which version you're on, and whether there's a newer one to
install. A small red dot on the Settings icon in the bar means a newer version is
waiting.

## On the television

There's a second app for Android TV, and one screen in it. A camera fills the
screen, and the others sit live down the right, moving at two frames a second.
Press an arrow to choose one and Select to bring it into the big picture. The
camera it replaces goes back into the strip in its own place.

The first time, the television shows a code. On your phone, open Settings, tap
**Link a TV**, point the camera at the code, and type your Frigate password. The
phone hands the television the server's address and your sign-in over your home
network, and the television signs in to Frigate itself from then on.

The television keeps that sign-in, so it stays signed in after Frigate's day-long
session runs out. It signs in again with the password you gave it, and the phone
is not involved.

The big picture is the camera's own video, the same stream the phone plays from
live view, with its sound. The strip is a smaller feed, cheap enough for several
at once. The television says **Live** over the big picture while it is arriving,
and **Waiting for a picture** until the first frame does.

Settings in the corner, one press up from the first camera, is a list: **Default
camera**, the camera the television opens on when it starts; **Camera Layout**;
**Camera auto switch**; and **Pair again** for another server or account. Back
closes it.

Press left from a camera in the strip and Select, and that camera fills the
screen. Its name and **Live** stay on the picture; Select or Back brings the strip
back.

**Camera Layout** is where the wall is turned on: **Default**, or **Grid**. A grid
is every camera at the same size, fitted to how many you have: four make a 2×2,
six make two rows of three. Select on a tile fills the screen with that camera,
and Back brings the wall back with the arrows on the camera you left.

**Doorbell** shows an address for Home Assistant. Post to it when someone presses
the bell, and that camera fills the screen for half a minute. The address carries
a key the television made for itself, so it only answers its own house
([how to wire it up](docs/doorbell.md)).

A camera that sees a person or a car takes the big picture for half a minute, and
the screen then goes back to the camera it was on. **Camera auto switch** under
Settings is where it is turned off, and picking a camera by hand ends it early.

**Sound** in the corner mutes the live picture, and past that it says **Muted**.
It remembers which one you left it on.

**Alerts** is a page of its own, not a strip on the cameras. The button beside
Settings opens it, with a dot when something has come in since you last looked.
The alerts Frigate flagged are down the left, newest first; the one you've chosen
is on the right, with its picture and Frigate's own words. Select plays the clip
full screen with the sound, Select pauses, the arrows step ten seconds, and Back
returns to the list. Watching a clip marks it watched on your server, so the list
is what's still to see.

The TV app is `frigo-tv-<version>.apk` on each release page. It installs beside
the phone app under its own name, and updates on its own.

## The first time you open it

Frigo opens on a welcome, then takes you through what it needs:

1. **Your server** is the address you use in a browser and your Frigate sign-in.
2. **Alerts** turns notifications on. Which cameras and objects buzz is set on your server, so there is nothing to tick here. Android asks about notifications once, and the page says so if you turned them down.
3. **Battery** says whether Android is free to shut Frigo down, and opens the phone's settings if it isn't. Come back and the page checks again.
4. **Lock** offers to ask for your fingerprint each time Frigo starts. Not now leaves the app opening as it does now.
5. **The tabs** is a page for each of the five, with a drawn picture of what it holds and the bar underneath lit on the one it's describing. Skip leaves at any point.
6. **You're set** is the end of it, with the address you're signed in to.

The walkthrough doesn't come back on its own. Signing out leaves it alone, and the way back in is the plain sign-in page. If you want to see it again, **Show the welcome again** under About in Settings puts it back on the list.

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

## More than one server

On an admin account you add a server from Settings, under Servers. It asks for
an address, a username and a password, the same as the first one, and then
becomes the server you're on. Without an admin account you still see the servers
the phone holds, but you stay on the one it's set to.

Each server keeps its own sign-in, so switching is one tap and nothing to type.
Its alert switch and its camera layout come back with it. The colours, the
walkthrough and the picture size you picked for a camera stay with the phone,
because they're about how you use it rather than which box it's looking at.

Sign out takes you off the server you're on and leaves what you set on it, under
your username. Sign in again and the alerts switch, the quiet hours and the
cameras are as you left them. Another server takes over if you have one.
Otherwise you land on the sign-in page with that address filled in.

## In another language

Frigo speaks English, Spanish, French, German and Portuguese. Under Settings,
Appearance, **Language** picks one, and Follow the phone is what the app does
until you do.

The whole app changes: the screens, the walkthrough, the days and months under a
camera, the line when something fails, and the notification that says an alert
arrived.

Two things stay as they are. Your cameras are named by your server, and so are the
objects Frigate reports, so those read the same whatever the app is set to. And a
refusal from Frigate comes back in the words the server wrote it in.

On Android 13 and later, Frigo is in the phone's own per-app language list as
well, so the two agree whichever one you use.

## When the server isn't answering

Your phone keeps a copy of the server's settings the last time it read them. If
the server is away, Alerts and detections and each camera's page still open and
show that copy. A line at the top says when it was read, and nothing on the page
can be changed until the server is back.

The settings that belong to the phone work either way: the colours, the live
tiles, the list of objects, the list of people, the walkthrough and the list of
servers.

The rest of the app needs the server. Cameras, Review, Explore and Clips say it
isn't answering.

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
a quiet "Watching for alerts" one in the shade. It says how many cameras will buzz
and whether the connection is up, and it is the app's way of saying it is still
there. Switching alerts off in Settings stops the service and takes the
notification away.

Your server decides what buzzes, not the phone. Two things have to agree:

1. Notifications are on for that camera.
2. Its alerts are on, and the thing it saw is in its alert objects.

Both are on your server's own pages: the cameras on the notifications page, the
lists on the alerts page. Turn either off and the phone stays quiet.

The switch at the top of the notifications page turns the cameras on in a batch.
You can leave it off and turn cameras on one at a time, and those cameras still
buzz this phone.

This phone can narrow that further. **Only these objects**, under Notifications
in Settings, keeps a list of its own, and an alert about an object that isn't on
it stays quiet. The list only ever takes alerts away: nothing on your server
changes, and its other phones hear what they always heard. An alert for the
objects you've ticked still depends on the server's own two answers above.

The chip at the top picks the camera you're setting, one at a time. A camera opens
with every object it alerts on ticked, and you untick the ones you don't want, so
the drive can tell you about people while the street tells you about cars. Leave
every object ticked and the camera stays unfiltered, which also covers an object
your server starts alerting on later.

The objects offered are the ones that camera can actually report, so a label your
server doesn't track there isn't there to pick. Anything already on that camera's
list stays listed, so it can be taken off.

The cameras offered are the ones that make an alert. One whose Alerts switch is off
in Alerts and detections never makes one, and one with no alert objects has nothing
to make one from, so neither is there to set.

**People to leave out** sits under it. Frigate works out who a person is, and puts
the name on the review a second or so after the alert starts, or in the sentence it
writes when the review ends. Pick someone and their alerts never buzz here, while
everyone else still does.
Frigate can't do this itself: its alerts are chosen by object and by zone, and its
own push sends every alert. Nothing on your server or its other phones changes.

With someone on that list, an alert waits until the name is in hand, so the phone
knows who it is looking at before it decides. A person Frigate names straight away
is decided within a second or two. One it doesn't name only turns up in the
sentence, so that alert waits for the review to end, about twenty seconds later. A
review still open after a minute is decided without either, so an alert is never
lost. With nobody on the list, nothing waits.

The list needs your server's descriptions. Turn them off and the phone has only the
name on the review to go on, and the page says so.

Frigate's own web page shows the same values, so a change made in a browser is
picked up the next time the app opens or reconnects.

The app keeps a copy of those answers on the phone, so a restart with no signal
still knows what to watch. The quiet notification says which of them is keeping
everything quiet, so it never goes silent without saying why.

A camera that has just buzzed stays quiet for twenty seconds, so one visitor
walking past is one notification.

Quiet times is this phone's own. Turn it on, pick two times, and pick the cameras
to leave out, and none of them buzz between those times. A window from ten at
night to seven in the morning runs through midnight, and the same two times keeps
the whole day quiet. Nothing arrives while it runs: the review is still on your
server, so it's in Review when you look in the morning. The "Watching for alerts"
line says when it comes back, so silence never looks like a fault.

**Quiet for an hour** on the same page is the one-off: every camera, from now,
with the two times left as they are.

None of this reaches your server. Its notifications page, and any other phone,
are unchanged.

A camera turned off in Settings, Cameras, doesn't buzz here either. That list is
this phone's own, so your server and its other phones still hear it. Turn every
camera off and the watching line says why nothing will buzz, rather than counting
cameras that can't reach you.

When something buzzes, the phone asks your server's model what it is looking at and
fills the notification in about four seconds later. It takes three pictures a second
apart and asks for one short sentence. This is the quick answer, and it takes the
place of the bare "something was seen" on the notification already in the shade. The
buzz has already happened, so it stays quiet.

The question carries any name your server has already recognised, so the sentence can
say Matt rather than a man. Frigate's own description names people because the name
is in the text it gets; pictures alone are all strangers.

Frigate writes its own description of a review once the review has finished, which
can be half a minute later. That one has the whole clip to go on, so it replaces the
quick sentence on the same notification.

AI descriptions in Settings is where this is set up. The page says whether your server
has a model to describe with, turns it on and off, and points it at an Ollama model on
your own network without leaving the app. Turn it off and the pictures stop going to
the model, from the server and from the phone.

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

An admin finds this page behind the tune icon on Review, and under Server in
Settings. Alerts has a switch and the objects that make one. Detections has the
same, and nothing picked there means every other object. Tap Objects to pick
them, with a search box and the labels your cameras track marked.

These two lists decide what your server calls an alert. An admin changes them,
and anyone signed in can look. Frigate turns anyone else's change down, so a phone
on another account narrows alerts with a list of its own instead: Only these
objects, under Notifications in Settings. The notifications page, which says which
cameras may tell you anything at all, is on your server's own web page.

The labels offered are the ones your server can actually report: the objects it
tracks, and the sounds the cameras listen for. Naming a label does not make Frigate
detect it, so an object nobody tracks isn't offered and can't be set as an alert
that never fires. Anything already chosen stays on the list, so a label you've
stopped tracking can be taken off. A change goes to the server as you make it, so
the next thing Frigate sees follows it and nothing restarts.

One camera is set at a time, as Frigate's own settings do it. The chip at the top
picks it. A camera with no list of its own follows the server's shared list, and the
page shows that list; changing anything there gives that camera a list of its own.

A camera turned off on this phone isn't in that list. It's one you've stopped
watching, so it's not one to set up either, and your server keeps its settings for
the day you turn it back on. With all of them off the page says so and points at
Settings, Cameras.

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

## Turning a camera off, and naming it

The Cameras list in Settings has a switch on every camera. Turn one off and it
leaves this phone: the Cameras tab, the strip of other cameras under a picture, the
chips on Review and Explore, and the camera list on Alerts and detections. It stops
alerting you, too. It's marked **Hidden** on the list, and the switch is the way
back.

Your server is untouched. It records what it always recorded, and your other phones
are told what they were always told. The eight switches on the camera's page are
still Frigate's, so turning a camera off here is never a way to change what the
server does. A hidden camera opened from an alert still works, picture and bar.

Hold a row and drag it, and the cameras sit in that order from then on. Tap one for
its own page, where **Name** sets what this phone calls it. That name is used here
and nowhere else: your server keeps its own, and every address behind the screen is
still built from it. **Use the server's name** puts it back.

All of it belongs to the server you're signed in to, and to your username on it. So
two servers on the phone keep two lists, and two people on one server keep their
own.

## How the server is doing

Settings has a System row for an admin. It shows what the server is running and how
hard the box is working: the processor and memory as two bars, any GPU, each camera's
frames a second and the health of its stream, the disks with what is left on them, and
Frigate's own processes with the detector's time to think.

The numbers are read again every five seconds while the page is open, and the asking
stops when you leave it. What is worth noticing turns amber: frames being dropped, a
poor stream, a disk nearly full, shared memory below what Frigate wants.

The same page restarts Frigate. It asks first, and everything stops for about half a
minute, alerts included. Recordings and settings are kept.

## Updating

Frigo isn't in a store. Each release is an APK on the GitHub release page, and the
app fetches the next one itself.

Frigo asks GitHub what the newest release is when it opens, and again when you come
back to it after an hour. When there's a newer one, the Settings icon in the bar
carries a small red dot. Open Settings and the dot goes.

The Updates section there says which version you're on and offers **Download**, then
**Install** once the file has arrived. Android asks once whether Frigo may install an
app; turn on Allow from this source and the installer opens. From there it's the same
install screen as any APK. Your server address, sign-in and settings stay where they
are, because an update replaces the app and not its data.

The APK is the same file as on the release page, so nothing is trusted that you
couldn't have downloaded and checked yourself. Frigo asks at most once an hour on its
own, and keeps the answer on the phone, so opening it ten times in a morning is one
ask on the hourly allowance GitHub gives an unauthenticated caller. **Check again**
asks whatever the hour says, because that's you asking. Nothing runs in the
background.

**What's new** opens the release page in a browser, which is where the notes are.

## Building it

You need JDK 17 to 23, the Android SDK with platform 36, and nothing else.

```
./gradlew :app:assembleDebug
./gradlew :tv:assembleDebug
```

The phone's debug APK lands in `app/build/outputs/apk/debug/`, and the
television's in `tv/build/outputs/apk/debug/`. For a release build, put your
keystore details in a gitignored `keystore.properties`, or set the
`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` environment
variables:

```
./gradlew :app:assembleRelease :tv:assembleRelease
```

The television app installs with adb, over the network or a cable:

```
adb install -r tv/build/outputs/apk/release/tv-release.apk
```

## Releasing

You cut a release on the GitHub website. Open Actions, pick Release, run the
workflow, and type the version as `0.7.3`. It builds the signed APKs, tags the
commit, and puts both on the release page. Pushing a `v0.7.3` tag does the same
thing if you'd rather do that from the command line.

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
- `data/Language.kt` - the languages the app speaks, and the words for the places that aren't a screen.
- `data/ServerPrefs.kt` - what the phone keeps: the servers and their sign-ins, each one's choices, and the copy of its settings.
- `data/MjpegReader.kt` - pulls JPEG frames out of the live stream.
- `data/Updates.kt` - asks GitHub for the newest release, fetches its APK, and hands it to Android's installer.
- `data/UpdateWatch.kt` - keeps the answer across launches, decides when to ask again, and whether the Settings tab shows its dot.
- `alerts/` - the service that holds the message feed and the notifications it posts.
- `ui/` - one folder per screen, Compose throughout.
- `ui/components/ClipViewer.kt` - the full screen clip player that an event, a review or a kept clip opens.
- `ui/components/LiveSound.kt` - the camera's sound for the speaker button, when the picture is the stills stream.
- `ui/cameras/LiveVideo.kt` - the camera's own video, its sound, and keeping the picture up with the clock.
- `ui/cameras/Playback.kt` - the day's bar under a camera, its spikes, the moment's picture, and the other cameras.
- `ui/clips/` - the tab of clips kept on the server: the list, the rename and the delete, and the copy saved to the phone.
- `tv/` - the television app: the pairing code, the strip of live cameras, and the sign-in it keeps.
- `tools/mockfrigate/mock.py` - a stand-in Frigate for working on either app without touching the real one.
