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

## The frames

`frame0.jpg` to `frame2.jpg` are generated, not photographed. To make new ones on
Windows, see `make_frames.ps1`. They only need to be valid JPEGs of different
sizes, so any replacement will do.
