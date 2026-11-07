#!/usr/bin/env python3
"""A stand-in Frigate server.

Serves the handful of endpoints the app calls, so the app can be exercised
without pointing it at a real Frigate. The MJPEG stream cycles three frames, so
there is something moving to look at.

The sign-in here behaves like Frigate 0.18: a JSON body of `user` and
`password`, and a JWT in a cookie header. Anything else gets a 422, which is
exactly what a client with the wrong request shape should get.

Sign in as `test` with the password `test`. Any other password is refused, on
purpose, so the error message can be tried too.

Set MOCK_ROLE=viewer to sign in as an account that isn't an admin. The server's
own pages and Add a server then come off Settings, a saved server can't be
switched to, Review loses its tune icon, and a settings write answers the 403 a
real server gives.

    MOCK_ROLE=viewer python tools/mockfrigate/mock.py

    python tools/mockfrigate/mock.py

Then point the app at:

    http://10.0.2.2:8099       from an emulator on this machine
    http://<this machine>:8099 from a phone on the same network
"""
import base64
import datetime
import hashlib
import itertools
import json
import math
import os
import select
import socket
import struct
import subprocess
import sys
import tempfile
import time
import zoneinfo
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
# Every request on the console, for when you want to see what the app asks for.
VERBOSE = os.environ.get("MOCK_VERBOSE") == "1"
FRAMES = [open(os.path.join(HERE, f"frame{i}.jpg"), "rb").read() for i in range(3)]
# Which frame the next still request gets. See the latest.jpg route.
STILL = itertools.count()
# A test pattern, so a clip that plays is obviously playing.
CLIP = open(os.path.join(HERE, "clip.mp4"), "rb").read()

# What account the mock answers as. Set MOCK_ROLE=viewer to sign in as someone who
# isn't an admin: the app then hides the server's own pages, takes the tune icon off
# Review, and the config write below answers the 403 a real server gives.
ROLE = os.environ.get("MOCK_ROLE", "admin")

# A long recording to cut clips from the way Frigate cuts them, and to hang the
# day's stretches of recording off in ten second rows, as its database does. Set
# MOCK_RECORDING to a file to have every clip cut from it, so the joins between
# one clip and the next can be looked at as they would be on the real server.
# MOCK_KEYFRAME is how often that recording has a keyframe; a clip cut from it
# starts at the keyframe at or before the moment asked for, which is what puts a
# second of picture twice at the join between two clips.
RECORDING = os.environ.get("MOCK_RECORDING")
RECORDING_SECONDS = float(os.environ.get("MOCK_RECORDING_SECONDS", "1200"))
KEYFRAME_SECONDS = float(os.environ.get("MOCK_KEYFRAME", "1.0"))
FFMPEG = os.environ.get("MOCK_FFMPEG")

# The rows Frigate's database keeps a stretch of recording in. Its segments are
# ten seconds long and start where the camera's own keyframes do, which is not on
# a whole second, so asking for a moment by the second lands inside one.
SEGMENT_SECONDS = 10.0
SEGMENT_SKEW = 0.37

# How often the mock flags a moment, on fixed slots of the clock, so the same moment
# comes back with the same id however it is asked about and a later ask has the slots
# that have gone by since. Ten minutes apart by default; set MOCK_REVIEW_EVERY to
# something short to watch the bar fill in as the clock moves.
REVIEW_EVERY = float(os.environ.get("MOCK_REVIEW_EVERY", "600"))

CAMERAS = {
    "north_cam": {"enabled": True, "friendly_name": "North cam"},
    "south_cam": {"enabled": True, "friendly_name": "South cam"},
    "door_cam": {"enabled": True, "friendly_name": "Door cam"},
    "3d_printer": {"enabled": True},
    "quiet_cam": {"enabled": True, "friendly_name": "Quiet cam"},
    "offline_cam": {"enabled": False},
}

# The cameras whose switch is on, on the server's notifications page. The others are
# off, which is what a camera nobody has touched says. Turn one on in the app and its
# alerts start buzzing, because the app reads these and has no list of its own.
NOTIFYING = {"door_cam", "north_cam"}

# What the server flags as an alert, server-wide. A review's object is checked
# against this, and against the camera's own list when it has one.
ALERT_LABELS = ["person", "car"]

# One camera with alert objects of its own, so the phone's Only these objects page
# has something to narrow by: the others follow the shared list, and the garden
# alerts on dogs alone.
OWN_ALERT_LABELS = {"south_cam": ["dog"]}

# The settings file the app reads, with only the keys it uses. The notifications
# page and the alerts page are both here, because the app follows them rather than
# keeping a list of its own.
CONFIG = {
    "notifications": {"enabled": True},
    "review": {
        "alerts": {"enabled": True, "labels": ALERT_LABELS},
        "detections": {"enabled": True},
        "genai": {"enabled": True, "alerts": True},
    },
    "objects": {"track": ["person", "car", "dog", "cat"]},
    "audio": {"enabled": True, "listen": ["bark", "speech"]},
    "cameras": {
        name: {
            **settings,
            "notifications": {"enabled": name in NOTIFYING},
            **(
                {"review": {"alerts": {"enabled": True, "labels": OWN_ALERT_LABELS[name]}}}
                if name in OWN_ALERT_LABELS
                else {}
            ),
        }
        for name, settings in CAMERAS.items()
    },
}

# One camera with its Alerts switch off, as Alerts and detections can turn it off.
# A camera that never makes an alert has nothing for the phone's own list to narrow,
# so that list does not offer it.
CONFIG["cameras"]["quiet_cam"]["review"] = {"alerts": {"enabled": False}}

# Cameras with nothing arriving. Frigate has no flag that says a camera is offline:
# it counts the frames each one produces, and one that has stopped produces none, so
# this is what its stats say about it. The stream still hands back the last frame it
# sent, which is exactly why the app has to ask.
SILENT = {"quiet_cam"}

# When this mock started, so its uptime reads like a server that has been up a while.
STARTED = time.time()

# Shaped like a real JWT: three dot-separated parts. Restart with MOCK_TOKEN=...
# to make the running app's saved token stop working, which is how the session
# expiry path gets tested.
TOKEN = os.environ.get("MOCK_TOKEN", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0Iiwicm9sZSI6ImFkbWluIn0.c2lnbmF0dXJl")
COOKIE_NAME = "frigate_token"

# What the mock says it can look for. The settings screen lists these.
LABELS = ["glass", "person", "car", "dog", "cat"]

# The names Frigate has learned to recognise, which filter events as sub labels.
SUB_LABELS = ["Angie", "Fay"]

# What each service's log says, written the way the server writes it: Frigate's
# own prefix with the date and second, a line that repeats, a line the container
# writes with no prefix, a Python line with the date in a second bracket, and an
# nginx line with no level at all.
LOG_SAMPLES = {
    "frigate": [
        "2026-11-08 08:29:43.777006  [INFO] Preparing Frigate...",
        "2026-11-08 08:29:44.767285  [INFO] Starting Frigate...",
        "2026-11-08 08:29:49.695709  [2026-11-08 08:29:49] frigate.util.config            INFO    : Checking if frigate config needs migration...",
        "2026-11-08 08:29:52.196213  [2026-11-08 08:29:52] frigate.app                    INFO    : Starting Frigate (0.18.2-)",
        "2026-11-08 08:29:54.461891  [2026-11-08 08:29:54] frigate.comms.webpush          WARNING : Email must be provided for push notifications to be sent.",
        "2026-11-08 08:30:14.175109  [2026-11-08 08:30:14] frigate.record.maintainer      DEBUG   : Copied /media/frigate/recordings/2026-11-07/19/door_cam/29.58.mp4 in 0.030995845794677734 seconds.",
        "2026-11-08 08:30:20.100000  [2026-11-08 08:30:20] frigate.record.maintainer      ERROR   : Failed to copy /media/frigate/recordings/2026-11-07/19/door_cam/30.00.mp4",
        "DEBUG   : Copied a recording with no server prefix.",
        "2026-11-08 08:30:21.200000  [INFO] Waiting for the detector to come up...",
        "2026-11-08 08:30:21.200000  [INFO] Waiting for the detector to come up...",
    ],
    "go2rtc": [
        "2026-11-08 08:29:44.767157  [INFO] Preparing new go2rtc config...",
        "2026-11-08 08:29:46.329006  [INFO] Starting go2rtc...",
        "2026-11-08 08:29:46.864775  08:29:46.864 INF go2rtc platform=linux/amd64 revision=b5948cf version=1.9.14",
        "2026-11-08 08:29:46.865530  08:29:46.865 INF [api] listen addr=:1984",
        "2026-11-08 08:29:46.865563  08:29:46.865 INF [rtsp] listen addr=:8554",
        "2026-11-08 08:30:02.100000  08:30:02.100 WRN [rtsp] client disconnected",
        "2026-11-08 08:30:02.100000  08:30:02.100 WRN [rtsp] client disconnected",
    ],
    "nginx": [
        "2026-11-08 08:29:43.634511  [INFO] Starting NGINX...",
        "2026-11-08 08:29:45.264397  2026/11/08 08:29:45 [warn] 422#422: audio_encoder_process_init: failed to get AAC encoder",
        "2026-11-08 08:29:48.486806  2026/11/08 08:29:48 [error] 387#387: *1 connect() failed (111: Connection refused) while connecting to upstream",
        "2026-11-08 08:33:08.051580  192.168.0.103 - - [08/Nov/2026:08:33:08 +1300] \"GET /api/profile HTTP/1.1\" 200 102 \"-\" \"okhttp/4.12.0\"",
        "2026-11-08 08:33:08.051580  192.168.0.103 - - [08/Nov/2026:08:33:08 +1300] \"GET /api/profile HTTP/1.1\" 200 102 \"-\" \"okhttp/4.12.0\"",
    ],
}

# The lines the feed adds, one every two seconds, so a page left open fills in.
LOG_STREAM = [
    "[INFO] Detector: inference 7.9 ms",
    "[DEBUG] Copied /media/frigate/recordings/2026-11-08/09/door_cam/00.01.mp4",
    "[WARNING] Camera north_cam is behind by 0.8 seconds",
    "[ERROR] Lost connection to 3d_printer",
]


def log_stream_line(number):
    """One line, stamped now and shaped like the top of a Frigate log."""
    now = datetime.datetime.now()
    stamp = now.strftime("%Y-%m-%d %H:%M:%S.") + f"{now.microsecond:06d}"
    return f"{stamp}  {LOG_STREAM[number % len(LOG_STREAM)]}"

# Events the Explore list is made of: label, sub label, camera, minutes ago.
EVENT_SEEDS = [
    ("person", "Angie", "door_cam", 2),
    ("car", None, "north_cam", 9),
    ("dog", None, "south_cam", 26),
    ("glass", None, "door_cam", 51),
    ("person", "Fay", "door_cam", 26 * 60),
    ("person", None, "north_cam", 30 * 60),
    ("car", None, "north_cam", 52 * 60),
    ("dog", None, "south_cam", 3 * 24 * 60),
]

# Reviews the app has marked watched, by id. The ones it lists are made up per
# request, so this is the only thing that remembers.
REVIEWED = set()

# The websocket handshake's fixed magic string, from RFC 6455.
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

# The reviews the socket publishes, alternating between the two cameras whose
# notifications are on. `person` on door_cam and `car` on north_cam, which is what
# the alerts page calls an alert. Each is given a name Frigate recognised, since a
# phone can stay quiet about a person by name and the name is what it needs. The
# door camera reports its person as `person-verified`, the way Frigate marks an
# object it has matched to a known face, while the camera's alert list still says
# `person`; the two have to mean the same thing.
SOCKET_REVIEWS = [
    ("door_cam", ["person-verified"], ["driveway"], "Angie"),
    ("north_cam", ["car"], ["driveway"], "Fay"),
]


def review_body(number, severity, start_time=None, named=True):
    """One review, shaped like 0.18's, with the one detection its picture comes from."""
    camera, objects, zones, name = SOCKET_REVIEWS[number % len(SOCKET_REVIEWS)]
    started = time.time() if start_time is None else start_time
    return {
        "id": f"{started:.6f}-socket{number}",
        "camera": camera,
        "start_time": started,
        "end_time": None,
        "severity": severity,
        "has_been_reviewed": False,
        "data": {
            "audio": [],
            "detections": [f"{started:.6f}-socket{number}"],
            "objects": objects,
            "sub_labels": [name] if named else [],
            "significant_motion_areas": [],
            "zones": zones,
        },
    }


def review_message(change, body):
    """The envelope the socket sends: a topic, and the change as a JSON string."""
    return {
        "topic": "reviews",
        "payload": json.dumps({"type": change, "before": {}, "after": body}),
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        # Quiet by default: the app asks for a still a second per camera, and the
        # console would be nothing else. Set MOCK_VERBOSE=1 to see every request.
        if VERBOSE:
            super().log_message(*args)

    # --- plumbing ---------------------------------------------------------
    def send_json(self, payload, status=200, headers=None):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def send_bytes(self, body, content_type):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def bearer(self):
        header = self.headers.get("Authorization", "")
        return header[len("Bearer "):] if header.startswith("Bearer ") else None

    def cookie_token(self):
        for part in (self.headers.get("Cookie") or "").split(";"):
            name, _, value = part.strip().partition("=")
            if name == COOKIE_NAME:
                return value
        return None

    def authorised(self):
        return TOKEN in (self.bearer(), self.cookie_token())

    # --- endpoints --------------------------------------------------------
    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        path = urlparse(self.path).path

        if path == "/api/login":
            try:
                payload = json.loads(body.decode() or "{}")
            except ValueError:
                payload = None
            if not isinstance(payload, dict) or "user" not in payload or "password" not in payload:
                return self.send_json(missing_fields(payload), status=422)
            if payload["user"] == "test" and payload["password"] == "test":
                return self.send_json(
                    {},
                    status=200,
                    headers={"Set-Cookie": f"{COOKIE_NAME}={TOKEN}; Path=/; HttpOnly; Max-Age=86400"},
                )
            return self.send_json({"message": "Login failed"}, status=401)

        if path == "/api/reviews/viewed":
            if not self.authorised():
                return self.send_json({"message": "unauthorized"}, status=401)
            try:
                payload = json.loads(body.decode() or "{}")
            except ValueError:
                payload = None
            if not isinstance(payload, dict) or "ids" not in payload:
                return self.send_json({"message": "ids required"}, status=422)
            watched = bool(payload.get("reviewed", True))
            for review_id in payload["ids"]:
                REVIEWED.add(review_id) if watched else REVIEWED.discard(review_id)
            return self.send_json({"success": True})

        return self.send_json({"message": "not found"}, status=404)

    def do_PUT(self):
        # A settings write. The body is the change, and the mock only needs to know
        # whether this account is allowed to send one.
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)

        path = urlparse(self.path).path

        if path == "/api/config/set":
            if not self.authorised():
                return self.send_json({"message": "unauthorized"}, status=401)
            # Frigate answers a 403 rather than a 401 here, so the app can tell an
            # account that isn't an admin from a session that has aged out.
            if ROLE != "admin":
                return self.send_json(
                    {"success": False, "message": "Only admins can edit config."},
                    status=403,
                )
            return self.send_json({"success": True})

        return self.send_json({"message": "not found"}, status=404)

    def do_GET(self):
        path = urlparse(self.path).path

        if path == "/ws":
            # Frigate's message feed. Nginx checks the token before this point on
            # a real server, and the mock checks it itself.
            if not self.authorised():
                return self.send_json({"message": "unauthorized"}, status=401)
            return self.serve_alerts()

        if path == "/api/auth":
            # 202, not 200, and no body. Frigate refreshes the cookie here.
            if not self.authorised():
                return self.send_json({"message": "Authentication failed"}, status=401)
            self.send_response(202)
            self.send_header("remote-user", "test")
            self.send_header("remote-role", ROLE)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        if not self.authorised():
            return self.send_json({"message": "unauthorized"}, status=401)

        if path == "/api/config":
            return self.send_json(CONFIG)

        if path == "/api/labels":
            return self.send_json(LABELS)

        if path == "/api/sub_labels":
            return self.send_json(SUB_LABELS)

        if path == "/api/profile":
            return self.send_json({"username": "test", "role": ROLE, "allowed_cameras": list(CAMERAS)})

        if path == "/api/stats":
            return self.send_json(stats())

        if path.startswith("/api/logs/"):
            # A service's whole log, or the feed of what it writes from now on.
            # A real server keeps this to an admin, and answers 403 for anyone else.
            service = path[len("/api/logs/"):]
            if service not in LOG_SAMPLES:
                return self.send_json({"detail": f"Invalid service. {service}"}, status=500)
            if ROLE != "admin":
                return self.send_json({"message": "Only admins can view logs."}, status=403)
            query = parse_qs(urlparse(self.path).query)
            if (query.get("stream") or ["false"])[0].lower() == "true":
                return self.stream_log()
            return self.send_json(
                {"totalLines": len(LOG_SAMPLES[service]), "lines": LOG_SAMPLES[service]},
            )

        if path == "/api/events":
            return self.send_json(events(urlparse(self.path).query))

        if path.startswith("/api/events/") and path.endswith("/clip.mp4"):
            # Frigate cuts the clip out of recordings on request. Here it is one
            # short file, sent whole.
            return self.send_bytes(CLIP, "video/mp4")

        if path.startswith("/api/events/") and path.endswith(".jpg"):
            return self.send_bytes(FRAMES[0], "image/jpeg")

        if path == "/api/review/summary":
            # What each day held, and how much of it has been watched. The mock's
            # reviews all sit within the last two hours, so they are all today's.
            return self.send_json(review_summary())

        if path == "/api/review":
            # Frigate's endpoint is singular. Without a severity it sends alerts
            # and detections together, which is what the timeline's spikes ask for.
            query = parse_qs(urlparse(self.path).query)
            severity = (query.get("severity") or [None])[0]
            reviewed = (query.get("reviewed") or [None])[0]
            cameras = listed(query, "cameras") or listed(query, "camera")
            after = float(query["after"][0]) if query.get("after") else None
            before = float(query["before"][0]) if query.get("before") else None
            return self.send_json(reviews(severity, reviewed, cameras, after, before))

        if path.startswith("/api/review/") and path.endswith("/clip.mp4"):
            # Cut from recordings on a real server; here it is one short file.
            return self.send_bytes(CLIP, "video/mp4")

        if path.startswith("/api/review/"):
            # One review by id, which is what opening a review asks for so that a
            # description written since the list was fetched comes with it.
            rest = path[len("/api/review/"):]
            if rest and "/" not in rest and rest != "summary":
                found = one_review(rest)
                if found is None:
                    return self.send_json({"message": "Review not found"}, status=404)
                return self.send_json(found)

        if path.endswith("/recordings/summary"):
            # Which days and hours a camera has footage for. The mock keeps the
            # last three days, most of the day, with a couple of hours missing so
            # the gaps show. The days are worked out in the timezone the app asks
            # for, so Today is today where the phone is rather than where the mock
            # runs. Windows has no time zone database of its own, so without the
            # tzdata package this falls back to the machine's own clock, which is
            # right whenever the two agree.
            asked = (parse_qs(urlparse(self.path).query).get("timezone") or ["utc"])[0]
            try:
                where = zoneinfo.ZoneInfo(asked)
            except (zoneinfo.ZoneInfoNotFoundError, ValueError):
                where = datetime.datetime.now().astimezone().tzinfo
            today = datetime.datetime.now(where).date()
            days = []
            for back in range(3):
                day = today - datetime.timedelta(days=back)
                hours = [
                    {"hour": f"{hour:02d}", "duration": 3000, "motion": 400, "objects": 200, "events": 1}
                    for hour in range(24)
                    if not (back == 0 and hour in (3, 4)) and not (back == 1 and hour < 6)
                ]
                days.append({"day": day.isoformat(), "events": len(hours), "hours": hours})
            return self.send_json(days)

        if path.endswith("/recordings") and not path.endswith("/summary"):
            # The stretches recorded between two moments, in the ten second rows
            # Frigate keeps them in, for the hours the summary above says it has
            # footage for. Worked out from the clock, so a bar asked for a moment
            # later has the one more file that arrived by then.
            query = parse_qs(urlparse(self.path).query)
            after = float((query.get("after") or ["0"])[0])
            before = float((query.get("before") or [str(after + 86400)])[0])
            return self.send_json(segments(after, before))

        if "/recordings/" in path and path.endswith("/snapshot.jpg"):
            # A frame cut from the recording at that moment.
            return self.send_bytes(FRAMES[next(STILL) % len(FRAMES)], "image/jpeg")

        if "/start/" in path and path.endswith("/clip.mp4"):
            # A stretch of recording, cut by ffmpeg on a real server. With
            # MOCK_RECORDING it is cut the way Frigate cuts one: the moment asked
            # for is turned into a whole number of seconds into the segment of
            # recording holding it, and the cut begins at the keyframe at or
            # before there. Ask for a moment in the segment's first second and the
            # cut begins exactly at the segment, which is what the app does; ask
            # for anything later and it begins up to a keyframe early, which is
            # what puts a second of picture twice at the join between two pieces.
            #
            # The cut is re-encoded rather than copied, because a copy would snap to
            # the recording's own keyframes a second time and put a repeat at the
            # join however the app asked. A segment on a real server begins with a
            # keyframe, so landing exactly on [began] is what one looks like.
            if RECORDING and FFMPEG:
                try:
                    start = float(path.split("/start/")[1].split("/")[0])
                    end = float(path.split("/end/")[1].split("/")[0])
                except (IndexError, ValueError):
                    start, end = 0.0, 0.0
                if end > start:
                    at = start % RECORDING_SECONDS
                    rows = at - SEGMENT_SKEW
                    row = math.floor(rows / SEGMENT_SECONDS) * SEGMENT_SECONDS + SEGMENT_SKEW
                    into = int(at - row)
                    began = row if into == 0 else math.floor((row + into) / KEYFRAME_SECONDS) * KEYFRAME_SECONDS
                    length = min(end - start + (at - began), RECORDING_SECONDS - began)
                    cut = os.path.join(
                        tempfile.gettempdir(),
                        f"clip-{int(start * 1000)}-{int(end * 1000)}.mp4",
                    )
                    subprocess.run(
                        [
                            FFMPEG, "-y", "-ss", f"{began:.3f}", "-t", f"{length:.3f}",
                            "-i", RECORDING, "-an", "-c:v", "libx264", "-preset", "veryfast",
                            "-crf", "20", "-pix_fmt", "yuv420p", "-g", "17", "-keyint_min",
                            "17", "-sc_threshold", "0", "-movflags", "+faststart", cut,
                        ],
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                        check=False,
                    )
                    if os.path.exists(cut):
                        with open(cut, "rb") as body:
                            return self.send_bytes(body.read(), "video/mp4")
            return self.send_bytes(CLIP, "video/mp4")

        if path.endswith("/latest.jpg"):
            # A new frame for each request, so a tile that stops asking for
            # pictures shows up as one that stops changing.
            return self.send_bytes(FRAMES[next(STILL) % len(FRAMES)], "image/jpeg")

        if path == "/api/stream.mp4":
            # On a real server this path is handed to go2rtc, which sends the
            # camera's own H.264 with the sound on it. The mock sends its test
            # clip: four seconds of picture at a time, and the player opens it
            # again when it ends, which is what a stream that stops looks like.
            return self.send_bytes(CLIP, "video/mp4")

        if path == "/api/stream.aac":
            # Sound, which on a real server comes from go2rtc's own port rather
            # than from this API. Point MOCK_SOUND at a file of ADTS AAC to serve
            # it; without one, this answers the way a server with no such path
            # does.
            sound = os.environ.get("MOCK_SOUND")
            if not sound or not os.path.exists(sound):
                return self.send_json({"detail": "Not Found"}, status=404)
            return self.stream_audio(sound)

        if path.startswith("/api/"):
            return self.stream_mjpeg()

        return self.send_json({"message": "not found"}, status=404)

    # --- the message feed -------------------------------------------------
    # Frigate's `/ws` is the same feed its web interface listens to. Every
    # message is JSON with a topic and a payload, and the payload is itself
    # JSON for an event.

    alerts_sent = 0
    reviews_sent = 0

    def serve_alerts(self):
        key = self.headers.get("Sec-WebSocket-Key")
        if not key:
            return self.send_json({"message": "not a websocket request"}, status=400)

        accept = base64.b64encode(
            hashlib.sha1((key + WS_GUID).encode()).digest()
        ).decode()
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.wfile.flush()
        self.close_connection = True

        # Something arrives shortly after connecting, then every twenty seconds,
        # so an alert is never a long wait while trying this out. Reviews arrive on
        # their own timer: one that is an alert already, then one that starts as a
        # detection and turns into an alert a few seconds later, and so on. Both
        # topics are sent, because which one the app listens to is its own switch.
        next_alert = time.time() + 8
        next_review = time.time() + 14
        pending = []  # (when, message) for the review that turns into an alert
        try:
            while True:
                ready, _, _ = select.select([self.connection], [], [], 1)
                if ready and not self.read_frame():
                    return
                now = time.time()
                while pending and pending[0][0] <= now:
                    self.send_frame(json.dumps(pending.pop(0)[1]).encode())
                if now >= next_alert:
                    self.send_alert()
                    next_alert = now + 20
                if now >= next_review:
                    self.send_review(pending)
                    next_review = now + 20
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            return

    def send_alert(self):
        camera, label = [("door_cam", "person"), ("north_cam", "car")][
            Handler.alerts_sent % 2
        ]
        Handler.alerts_sent += 1
        now = time.time()
        event = {
            "id": f"{now:.6f}-mock{Handler.alerts_sent}",
            "camera": camera,
            "label": label,
            "sub_label": None,
            "start_time": now,
            "end_time": None,
            "has_clip": True,
            "has_snapshot": True,
            "zones": ["driveway"],
        }
        message = {
            # Frigate puts the topic out on the socket without the `frigate/`
            # prefix its MQTT topics carry. Its own page listens for "events".
            "topic": "events",
            "payload": json.dumps({"type": "new", "before": {}, "after": event}),
        }
        self.send_frame(json.dumps(message).encode())

    def send_review(self, pending):
        """One review, and every other one turns into an alert three seconds in.

        A review that starts as a detection is the ordinary case for a zone: the
        object has to step into the driveway before the server calls it an alert.
        The app has a window for exactly that, so the second message is what tries
        it.

        Every review then ends, and Frigate writes a description shortly after,
        which is where a recognised person's name is. A phone that holds an alert
        so it can leave somebody out is waiting for that.
        """
        Handler.reviews_sent += 1
        number = Handler.reviews_sent
        if number % 2:
            body = review_body(number, "alert")
            self.send_frame(json.dumps(review_message("new", body)).encode())
        else:
            # The name arrives with the turn into an alert and not before, which is
            # the case a phone that waits for it is built for.
            started = review_body(number, "detection", named=False)
            self.send_frame(json.dumps(review_message("new", started)).encode())
            body = review_body(number, "alert")
            body["start_time"] = started["start_time"]
            body["id"] = started["id"]
            pending.append((time.time() + 3, review_message("update", body)))

        ended = {**body, "end_time": body["start_time"] + 8}
        pending.append((time.time() + 8, review_message("end", ended)))

        name = SOCKET_REVIEWS[number % len(SOCKET_REVIEWS)][3]
        described = {
            **body,
            "end_time": body["start_time"] + 8,
            "data": {
                **body["data"],
                "metadata": {
                    "title": f"{name} walking",
                    "shortSummary": f"{name} is walking up the driveway.",
                    "scene": f"{name} walked up the driveway towards the door, paused at "
                    "the top, and then turned and headed back down the path.",
                    "potential_threat_level": 0,
                },
            },
        }
        pending.append((time.time() + 10, review_message("genai", described)))

    def send_frame(self, payload):
        length = len(payload)
        if length < 126:
            header = bytes([0x81, length])
        elif length < 65536:
            header = bytes([0x81, 126]) + struct.pack(">H", length)
        else:
            header = bytes([0x81, 127]) + struct.pack(">Q", length)
        self.connection.sendall(header + payload)

    def read_frame(self):
        """Reads one frame from the app. False means it has gone away."""
        head = self.connection.recv(2)
        if len(head) < 2:
            return False

        opcode = head[0] & 0x0F
        masked = bool(head[1] & 0x80)
        length = head[1] & 0x7F
        if length == 126:
            length = struct.unpack(">H", self.connection.recv(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self.connection.recv(8))[0]

        mask = self.connection.recv(4) if masked else b""
        body = b""
        while len(body) < length:
            chunk = self.connection.recv(length - len(body))
            if not chunk:
                return False
            body += chunk
        if masked:
            body = bytes(byte ^ mask[i % 4] for i, byte in enumerate(body))

        if opcode == 0x8:
            return False
        if opcode == 0x9:
            # A ping has to be answered, or the app decides the connection died.
            self.connection.sendall(bytes([0x8A, len(body)]) + body)
        return True

    def stream_mjpeg(self):
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=ffmpeg")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        index = 0
        try:
            while True:
                frame = FRAMES[index % len(FRAMES)]
                index += 1
                self.wfile.write(
                    b"--ffmpeg\r\nContent-Type: image/jpeg\r\nContent-Length: "
                    + str(len(frame)).encode()
                    + b"\r\n\r\n"
                )
                self.wfile.write(frame)
                self.wfile.write(b"\r\n")
                self.wfile.flush()
                time.sleep(0.15)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            # The player closed the stream, which is what leaving the screen does.
            return

    def stream_log(self):
        """What a log's feed looks like: text, one line every two seconds, until
        the app closes the response."""
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        number = 0
        try:
            while True:
                self.wfile.write((log_stream_line(number) + "\n").encode())
                self.wfile.flush()
                number += 1
                time.sleep(2)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            # The page was left, or the app reopened the feed for a fresher file.
            return

    def stream_audio(self, path):
        with open(path, "rb") as sound:
            body = sound.read()
        self.send_response(200)
        self.send_header("Content-Type", "audio/aac")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        try:
            # Round and round: a real one never ends, and a short file played
            # once would look like the server dropping the stream.
            while True:
                self.wfile.write(body)
                self.wfile.flush()
                time.sleep(1)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            return


def listed(query, name):
    """A comma-separated filter. Frigate keeps one value, so repeats don't add up."""
    value = (query.get(name) or [""])[0]
    return [part for part in (piece.strip() for piece in value.split(",")) if part]


def stats():
    """What /api/stats says, as far as the app reads it: how long the server has been
    up and how many frames a second each camera is producing."""
    return {
        "service": {"version": "0.18.2-mock", "uptime": time.time() - STARTED},
        "cameras": {
            name: {
                "camera_fps": 0.0 if name in SILENT else 5.0,
                "process_fps": 0.0 if name in SILENT else 5.0,
                # The rate the camera is set to send at. The app offers nothing faster,
                # so the mock's five is what its menu shows.
                "expected_fps": 5,
                "detection_fps": 0.0 if name in SILENT else 1.2,
                "skipped_fps": 0.0,
                "detection_enabled": True,
                "audio_dBFS": -45.0,
                "audio_rms": 0.001,
            }
            for name, camera in CAMERAS.items()
            if camera["enabled"]
        },
    }


def events(raw_query):
    """What Frigate has recorded, filtered the way its Explore page filters."""
    query = parse_qs(raw_query or "")
    now = time.time()

    cameras = listed(query, "cameras") or listed(query, "camera")
    labels = set(listed(query, "labels"))
    subs = set(listed(query, "sub_labels"))
    after = float(query["after"][0]) if query.get("after") else None
    before = float(query["before"][0]) if query.get("before") else None
    limit = int((query.get("limit") or ["100"])[0])

    answer = []
    for index, (label, sub, camera, minutes) in enumerate(EVENT_SEEDS):
        started = now - minutes * 60
        if cameras and camera not in cameras:
            continue
        if labels and label not in labels:
            continue
        if subs and (sub or "") not in subs:
            continue
        if after is not None and started < after:
            continue
        if before is not None and started >= before:
            continue
        answer.append(
            {
                "id": f"170000{index:03d}.000000-mock",
                "camera": camera,
                "label": label,
                "sub_label": sub,
                "start_time": started,
                "end_time": started + 30,
                "top_score": 0.82,
                "has_clip": index != 3,
                "has_snapshot": True,
                "zones": ["driveway"],
                "false_positive": None,
            }
        )

    answer.sort(key=lambda event: event["start_time"], reverse=True)
    return answer[:limit]


# What the server made of its first alert and its first detection. Frigate writes
# these a minute or two after a review ends, so the other rows carry none, and a
# review that has no words is the case the app is built to open too.
REVIEW_WORDS = {
    "alert": {
        "title": "Person at the door",
        "shortSummary": "A person is walking up the driveway.",
        "scene": "A person walked up the driveway towards the door, stood there for a "
        "moment, and then headed back down the path.",
        "potential_threat_level": 1,
        "other_concerns": ["a car passing on the street"],
    },
    "detection": {
        "title": "Dog in the garden",
        "shortSummary": "A dog is moving about the garden.",
        "scene": "A dog wandered around the garden for a while, stopping to sniff along "
        "the fence before moving out of the picture.",
        "potential_threat_level": 0,
        "other_concerns": [],
    },
}


def reviews(severity, reviewed=None, cameras=None, after=None, before=None):
    """Reviews shaped like 0.18's, newest first, with both kinds served.

    `reviewed` is what the app asks for: "0" for the ones still to watch, "1"
    for the ones watched, and None for both, which is what Frigate does.

    With no `severity` this answers every moment flagged in the window asked for,
    which is the timeline's question. They sit on fixed slots of the clock rather
    than on places in the window, so asking twice gives the same moments in the
    same places, and asking a minute later has the one that has just been flagged.
    A window a day wide then has a spike every ten minutes, which is what the bar
    is for.
    """
    if severity is None:
        return spread(after, before, cameras)

    now = time.time()
    seen = {
        "alert": [
            ("door_cam", ["person"], ["driveway"], 40, 120),
            ("north_cam", ["car"], ["driveway"], 900, 60),
            ("door_cam", ["person"], ["driveway"], 5400, 75),
        ],
        "detection": [
            ("south_cam", ["dog"], ["garden"], 200, 30),
            ("3d_printer", ["person"], [], 3600, 20),
            ("north_cam", ["car"], ["street"], 7200, 45),
        ],
    }.get(severity, [])

    answer = []
    for index, (camera, objects, zones, ago, length) in enumerate(seen):
        started = now - ago
        if cameras and camera not in cameras:
            continue
        if after is not None and started + length < after:
            continue
        if before is not None and started > before:
            continue
        review_id = f"1700000{index}00.000000-{severity}"
        watched = review_id in REVIEWED
        if reviewed == "0" and watched:
            continue
        if reviewed == "1" and not watched:
            continue
        data = {
            "audio": [],
            "detections": [f"{started:.6f}-{severity}{index}"],
            "objects": objects,
            "sub_labels": [],
            "significant_motion_areas": [],
            "zones": zones,
        }
        if index == 0:
            data["metadata"] = dict(REVIEW_WORDS[severity])
        answer.append(
            {
                "id": review_id,
                "camera": camera,
                "start_time": started,
                "end_time": started + length,
                "severity": severity,
                "has_been_reviewed": watched,
                # A path on the server's disk on a real server. The app takes its
                # picture from the first detection, so this only has to be there.
                "thumb_path": f"/clips/{camera}/review-{severity}-{index}-thumb.webp",
                "data": data,
            }
        )
    return answer


def one_review(review_id):
    """The review the list would have sent for this id, or None when no such row.

    Built by the same function the list uses, because on a real server the two
    answers are the same row, joined with what the user has watched.
    """
    severity = review_id.rsplit("-", 1)[-1]
    if severity not in ("alert", "detection"):
        return None
    return next((review for review in reviews(severity) if review["id"] == review_id), None)


def review_summary():
    """What each day held, as /api/review/summary answers it. The mock's reviews all
    sit within the last two hours, so the only day it has is today."""
    counts = {}
    for severity in ("alert", "detection"):
        rows = reviews(severity)
        counts[f"total_{severity}"] = len(rows)
        counts[f"reviewed_{severity}"] = sum(1 for row in rows if row["has_been_reviewed"])
    return {datetime.date.today().isoformat(): counts}


def segments(after, before):
    """The ten second rows Frigate keeps a camera's recordings in, between two
    moments, for the hours the summary says it has footage for. Nothing after now:
    a camera cannot have recorded a file that has not been closed yet."""
    rows = []
    now = time.time()
    at = math.floor(after / SEGMENT_SECONDS) * SEGMENT_SECONDS + SEGMENT_SKEW
    while at < min(before, now):
        when = datetime.datetime.fromtimestamp(at)
        if when.hour in recorded_hours(when.date()):
            rows.append(
                {
                    "id": f"r{int(at)}",
                    "start_time": round(at, 3),
                    "end_time": round(at + SEGMENT_SECONDS, 3),
                    "duration": SEGMENT_SECONDS,
                    "path": f"/media/r{int(at)}.mp4",
                }
            )
        at += SEGMENT_SECONDS
    return rows


def recorded_hours(day):
    """The hours of a day this camera has footage for, the way the summary says: three
    days back, with a couple of hours missing today and a night missing yesterday."""
    back = (datetime.date.today() - day).days
    return {
        hour
        for hour in range(24)
        if not (back == 0 and hour in (3, 4)) and not (back == 1 and hour < 6)
    }


def spread(after, before, cameras):
    """The moments flagged across a window, on fixed slots of the clock: the same
    moment is flagged the same however it is asked about, and a later ask has the
    slots that have gone by since."""
    if after is None or before is None or before <= after:
        return []

    # Nothing after now, for the same reason the recordings stop there: a moment
    # that has not happened cannot have been flagged.
    first = math.ceil(after / REVIEW_EVERY)
    last = math.ceil(min(before, time.time()) / REVIEW_EVERY)
    answer = []
    for slot in range(first, last):
        started = slot * REVIEW_EVERY
        # One in seven is an alert, the rest detections, so both colours show. The
        # camera changes on five and the kind on seven, so every camera gets both.
        severity = "alert" if slot % 7 == 3 else "detection"
        camera = list(CAMERAS)[slot % len(CAMERAS)]
        if cameras and camera not in cameras:
            continue
        answer.append(
            {
                "id": f"spread-{slot}-{severity}",
                "camera": camera,
                "start_time": started,
                "end_time": started + (30 if severity == "alert" else 10),
                "severity": severity,
                "has_been_reviewed": slot % 4 == 0,
                "thumb_path": f"/clips/{camera}/spread-{slot}.webp",
                "data": {
                    "audio": [],
                    "detections": [f"{started:.6f}-spread{slot}"],
                    "objects": ["person"],
                    "sub_labels": [],
                    "significant_motion_areas": [],
                    "zones": [],
                },
            }
        )
    return answer


def missing_fields(payload):
    """The same complaint FastAPI makes when a required body field is absent."""
    detail = []
    for field in ("user", "password"):
        if not isinstance(payload, dict) or field not in payload:
            detail.append(
                {
                    "type": "missing",
                    "loc": ["body", field],
                    "msg": "Field required",
                    "input": payload,
                }
            )
    return {"detail": detail} if detail else {"detail": [{"type": "invalid", "loc": ["body"], "msg": "Bad request body"}]}


class QuietServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        # A client hanging up mid-response is normal here. Staying silent keeps
        # the interesting lines visible.
        if not isinstance(sys.exc_info()[1], (ConnectionError, socket.timeout)):
            super().handle_error(request, client_address)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
    server = QuietServer(("0.0.0.0", port), Handler)
    print(f"mock frigate on http://127.0.0.1:{port}", flush=True)
    server.serve_forever()
