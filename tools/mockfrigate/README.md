# Mock Frigate

A fake Frigate server for working on the app without touching the real one.

```
python tools/mockfrigate/mock.py
```

Then point the app at it:

- From an emulator on the same machine: `http://10.0.2.2:8099`
- From a phone on the same network: `http://<this machine>:8099`

Sign in as `test` with the password `test`. Any other password is refused, on
purpose, so the error message can be tried too.

To try the app as someone who isn't an admin, start it with a role:

```
MOCK_ROLE=viewer python tools/mockfrigate/mock.py
```

`/api/profile` then answers `viewer`. The server's own pages come off Settings,
Review loses its tune icon, and a settings write answers the 403 a real server
gives. Settings, Only these objects is where a phone like that narrows its alerts.

It serves six cameras, one of them disabled, three events, thumbnails, snapshots,
an endless MJPEG stream that cycles three frames, and a four second clip for two
of the three events. The third event has no clip, so the still-only path is
reachable too. The clip is a test pattern, made with ffmpeg, so a clip that plays
is obviously playing.

`/api/stream.mp4` is the camera's own video on a real server, sent by go2rtc. The
mock sends the test clip whole, so the television's big picture plays the pattern
for four seconds and opens it again when it ends. The picture is the clip, not a
live camera, which is what the bar at the bottom of the pattern is there to show.

One of the five, Quiet cam, reports no frames in `/api/stats` while its stream goes
on handing back a picture. That's a camera that has stopped sending, as a real
server reports one, and it's what the app's grey Offline pill is for.

Its cameras say they send 800x450 at five frames a second, and its stills ignore
`height`, so the player opens on what the real app would open on against them: a
720p stream, with 720p and 480p in the menu and 5 and 2 frames a second. The real
server scales a still up to whatever `height` it is given, which is why the app
reads a still's own size instead of asking for a bigger one.

`/api/labels` answers with the objects the app can tick in Settings. `/api/review`
answers with three alerts and three detections, newest first, and a clip for each.It filters on `severity` the way the real endpoint does. `/api/review/summary`
counts those six as today's, so the numbers beside the two tabs and the dots in the
calendar have something to show.

The first alert and the first detection carry what the server made of them: a
title, a short summary and a whole scene, the way Frigate writes them a minute or
two after a review ends. The alert is also marked suspicious and carries an aside
about a car, so the card's badge and its last line have something to show. The
other four carry none, so the list shows a row named the way Frigate named it and
a row named after the objects. `/api/review/<id>` answers one review by id, built
the same way, which is what opening one asks for.

`/api/config` carries the two pages the app follows: notifications are on for the
whole server and for the door and north cameras, and the server-wide alert list is
`person` and `car`. South camera has alert objects of its own, `dog`, so Settings,
Only these objects has something to narrow by. Quiet cam has its Alerts switch off,
so it never makes an alert and is not offered there. It tracks person, car, dog and
cat, and listens for `bark` and `speech`, so Alerts and detections offers exactly
those six labels and no others. Every other camera has its
notifications off, which is what a camera nobody has touched says. Turn one of
them on in the app and it starts buzzing, because the app has no list of its own
unless you give it one.

`/api/logs/frigate`, `/api/logs/go2rtc` and `/api/logs/nginx` answer the whole of
that service's current log. The lines carry every level the app reads, a line the
container writes with no prefix, a line with the date repeated in brackets, an
nginx line with no level, and one line twice. `?stream=true` answers `text/plain`
and adds a line every two seconds until the app closes it, which is what Settings,
System logs sits on. A viewer account gets the 403 a real server gives. Any other
service name answers the 500 the real endpoint does.

`/ws` is a websocket that behaves like Frigate's message feed: it takes the same
bearer token, and it publishes on both of the topics the app can listen to.

- On `events`, every twenty seconds, a new event: a person on the door camera, then
  a car on the north camera, and so on.
- On `reviews`, every twenty seconds starting fourteen seconds in: a review that is
  already an alert, then one that starts as a detection and turns into an alert
  three seconds later with the same id and the same start time.
- Each review carries a name Frigate recognised: Angie on the door, Fay on the north
  camera. The door's person is reported as `person-verified`, the way Frigate marks an
  object under a known face, while the camera's alert list still says `person`. The one
  that starts as a detection is named only when it turns into an alert three seconds
  in. Every review then ends at eight seconds, and a description with the name in it
  follows at ten: a title, a short summary and a whole scene, which is what a phone
  waiting to leave somebody out is waiting for. Leave Angie out in Settings, People
  to leave out and the door goes quiet while "Fay walking" still buzzes, about ten
  seconds in.

The app's trial switch chooses which of the two topics it listens to, so both are
sent and the switch can be flipped without restarting anything. The turn from
detection to alert is the case a zone makes: the object has to step into the
driveway before the server calls it an alert, and the app only buzzes inside a few
seconds of the review's start. It answers pings and closes when the app closes,
which is what a real server does when it goes away.

The recordings and the reviews follow the clock. Recordings are ten second files
worked out from the moment asked for, up to now and no further, which is how the
app's bar keeps up as it watches. Reviews sit on fixed slots of the clock rather
than on places in the window, so asking twice gives the same moments in the same
places and asking a minute later has the ones that have gone by since. They are
ten minutes apart, which fills a day's bar; set `MOCK_REVIEW_EVERY` to something
short to watch the spikes arrive while you watch:

```
MOCK_REVIEW_EVERY=20 python tools/mockfrigate/mock.py
```

Sign-in behaves like Frigate 0.18: a JSON body of `user` and `password`, and the
session as a JWT in a `Set-Cookie` header. Send it a form body and it answers
with the same 422 a real server gives, which is the point of the exercise.

To watch the app notice an expired session, restart the server with a different
token, then open the app:

```
MOCK_TOKEN=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.b3RoZXI python tools/mockfrigate/mock.py
```

The app assumes HTTPS unless you type a scheme, so include `http://` when you
point it at this.

To see every request the app makes, which is how the refresh button was
checked, start it loud:

```
MOCK_VERBOSE=1 python tools/mockfrigate/mock.py
```

Each line then carries the path, so you can count what a tap costs.

The sound button in the player asks for `/api/stream.aac`, which a real server
answers from go2rtc's own port rather than from the API. Point the mock at a file
of ADTS AAC and it serves it round and round:

```
MOCK_SOUND=/path/to/sound.aac python tools/mockfrigate/mock.py
```

Without that variable the path answers 404, which is how a server with no second
path set up behaves, and the app's "No sound came through." can be seen.

## The frames

`frame0.jpg` to `frame2.jpg` are generated, not photographed. To make new ones on
Windows, see `make_frames.ps1`. They only need to be valid JPEGs of different
sizes, so any replacement will do.

## Clips cut from a recording

Frigate keeps a camera's recordings as ten second files, and cuts a clip out of them
when it's asked. Point the mock at a long recording and it does the same:

```
MOCK_RECORDING=/path/to/recording.mp4 \
MOCK_KEYFRAME=1.42 \
MOCK_FFMPEG=/path/to/ffmpeg \
python tools/mockfrigate/mock.py
```

`MOCK_RECORDING_SECONDS` is how long that recording is, for wrapping a moment round
to a place in it; it's 1200 unless you say otherwise. `MOCK_KEYFRAME` is how far apart
its keyframes are, which decides how far a cut overshoots when it's asked for from
somewhere past the first second of a file.

`/api/<camera>/recordings` then answers the way the real endpoint does, a row for every
ten second file, starting a third of a second past the whole second, because a camera's
files start on its own frames rather than on the clock. That offset is the point: a
request for a moment by the whole second lands inside a file, not on its edge. Rows stop
at now, for the hours `/recordings/summary` says it has footage for, so three days back
with a couple of hours missing to leave gaps.

A clip request is cut the way Frigate cuts one. The moment is turned into a whole
number of seconds into the file holding it, and the cut begins at the keyframe at or
before there, so a clip asked for from the middle of a file comes back early, with a
second of picture the clip before it already showed. Watch the joins with that on and
you're watching what the phone does against the real server. The cut is re-encoded to
land exactly there, because a copy would snap back to the recording's own keyframes a
second time and put a repeat at every join however the app asked.

The recording this was made against is 480 by 270 at 12 frames a second, with a
counter burned in, so a picture that plays is obviously playing and a picture that
goes back is obviously going back.
