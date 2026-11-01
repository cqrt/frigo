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

It serves five cameras, one of them disabled, three events, thumbnails, snapshots,
an endless MJPEG stream that cycles three frames, and a four second clip for two
of the three events. The third event has no clip, so the still-only path is
reachable too. The clip is a test pattern, made with ffmpeg, so a clip that plays
is obviously playing.

`/api/labels` answers with the objects the app can tick in Settings. `/api/review`
answers with three alerts and three detections, newest first, and a clip for each.
It filters on `severity` the way the real endpoint does. `/ws` is a
websocket that behaves like Frigate's message feed: it takes the same bearer
token, and every twenty seconds it publishes a new event, alternating a person on
the door camera with a car on the north camera. That alternation is the point.
Tick `person` only and the car never buzzes; untick the door camera and the person
never does. It answers pings and closes when the app closes, which is what a real
server does when it goes away.

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
request for a moment by the whole second lands inside a file, not on its edge.

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
