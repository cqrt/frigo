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
import json
import os
import select
import socket
import struct
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
FRAMES = [open(os.path.join(HERE, f"frame{i}.jpg"), "rb").read() for i in range(3)]
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
LABELS = ["person", "car", "dog", "cat"]

# The websocket handshake's fixed magic string, from RFC 6455.
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

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

        if path == "/api/profile":
            return self.send_json({"username": "test", "role": "admin", "allowed_cameras": list(CAMERAS)})

        if path == "/api/events":
            now = time.time()
            events = [
                {
                    "id": f"1700000000.00{i}-abcdef",
                    "camera": ["north_cam", "door_cam", "south_cam"][i % 3],
                    "label": ["person", "car", "dog"][i % 3],
                    "sub_label": None,
                    "start_time": now - 300 * i,
                    "end_time": now - 290 * i,
                    "has_clip": i != 2,
                    "has_snapshot": True,
                    "zones": ["North_driveway"],
                }
                for i in range(3)
            ]
            return self.send_json(events)

        if path.startswith("/api/events/") and path.endswith("/clip.mp4"):
            # Frigate cuts the clip out of recordings on request. Here it is one
            # short file, sent whole.
            return self.send_bytes(CLIP, "video/mp4")

        if path.startswith("/api/events/") and path.endswith(".jpg"):
            return self.send_bytes(FRAMES[0], "image/jpeg")

        if path.endswith("/latest.jpg"):
            return self.send_bytes(FRAMES[0], "image/jpeg")

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
            "topic": "frigate/events",
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
