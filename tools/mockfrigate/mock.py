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

    python tools/mockfrigate/mock.py

Then point the app at:

    http://10.0.2.2:8099       from an emulator on this machine
    http://<this machine>:8099 from a phone on the same network
"""
import base64
import hashlib
import itertools
import json
import os
import select
import socket
import struct
import sys
import time
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

CAMERAS = {
    "north_cam": {"enabled": True, "friendly_name": "North cam"},
    "south_cam": {"enabled": True, "friendly_name": "South cam"},
    "door_cam": {"enabled": True, "friendly_name": "Door cam"},
    "3d_printer": {"enabled": True},
    "offline_cam": {"enabled": False},
}

# Shaped like a real JWT: three dot-separated parts. Restart with MOCK_TOKEN=...
# to make the running app's saved token stop working, which is how the session
# expiry path gets tested.
TOKEN = os.environ.get("MOCK_TOKEN", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0Iiwicm9sZSI6ImFkbWluIn0.c2lnbmF0dXJl")
COOKIE_NAME = "frigate_token"

# What the mock says it can look for. The settings screen lists these.
LABELS = ["glass", "person", "car", "dog", "cat"]

# The names Frigate has learned to recognise, which filter events as sub labels.
SUB_LABELS = ["Angie", "Fay"]

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
            self.send_header("remote-role", "admin")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        if not self.authorised():
            return self.send_json({"message": "unauthorized"}, status=401)

        if path == "/api/config":
            return self.send_json({"cameras": CAMERAS})

        if path == "/api/labels":
            return self.send_json(LABELS)

        if path == "/api/sub_labels":
            return self.send_json(SUB_LABELS)

        if path == "/api/profile":
            return self.send_json({"username": "test", "role": "admin", "allowed_cameras": list(CAMERAS)})

        if path == "/api/events":
            return self.send_json(events(urlparse(self.path).query))

        if path.startswith("/api/events/") and path.endswith("/clip.mp4"):
            # Frigate cuts the clip out of recordings on request. Here it is one
            # short file, sent whole.
            return self.send_bytes(CLIP, "video/mp4")

        if path.startswith("/api/events/") and path.endswith(".jpg"):
            return self.send_bytes(FRAMES[0], "image/jpeg")

        if path == "/api/review":
            # Frigate's endpoint is singular. It answers alerts first, each
            # newest first, the way the real one orders them by severity.
            query = parse_qs(urlparse(self.path).query)
            severity = (query.get("severity") or ["alert"])[0]
            reviewed = (query.get("reviewed") or [None])[0]
            return self.send_json(reviews(severity, reviewed))

        if path.startswith("/api/review/") and path.endswith("/clip.mp4"):
            # Cut from recordings on a real server; here it is one short file.
            return self.send_bytes(CLIP, "video/mp4")

        if path.endswith("/latest.jpg"):
            # A new frame for each request, so a tile that stops asking for
            # pictures shows up as one that stops changing.
            return self.send_bytes(FRAMES[next(STILL) % len(FRAMES)], "image/jpeg")

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
        # so an alert is never a long wait while trying this out.
        next_alert = time.time() + 8
        try:
            while True:
                ready, _, _ = select.select([self.connection], [], [], 1)
                if ready and not self.read_frame():
                    return
                if time.time() >= next_alert:
                    self.send_alert()
                    next_alert = time.time() + 20
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


def reviews(severity, reviewed=None):
    """Reviews shaped like 0.18's, newest first, with both kinds served.

    `reviewed` is what the app asks for: "0" for the ones still to watch, "1"
    for the ones watched, and None for both, which is what Frigate does.
    """
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
        review_id = f"1700000{index}00.000000-{severity}"
        watched = review_id in REVIEWED
        if reviewed == "0" and watched:
            continue
        if reviewed == "1" and not watched:
            continue
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
                "data": {
                    "audio": [],
                    "detections": [f"{started:.6f}-{severity}{index}"],
                    "objects": objects,
                    "sub_labels": [],
                    "significant_motion_areas": [],
                    "zones": zones,
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
