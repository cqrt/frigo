#!/usr/bin/env python3
"""A stand-in Frigate server.

Serves the handful of endpoints the app calls, so the app can be exercised
without pointing it at a real Frigate. The MJPEG stream cycles three frames, so
there is something moving to look at.

Sign in with any username and password when using this: it says yes to `test`
and `test`, and no to everything else, which is enough to try the error path too.

    python tools/mockfrigate/mock.py

Then point the app at:
    http://10.0.2.2:8099      from an emulator on this machine
    http://<this machine>:8099 from a phone on the same network
"""
import json
import os
import socket
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
FRAMES = [open(os.path.join(HERE, f"frame{i}.jpg"), "rb").read() for i in range(3)]

CAMERAS = {
    "north_cam": {"enabled": True, "friendly_name": "North cam"},
    "south_cam": {"enabled": True, "friendly_name": "South cam"},
    "door_cam": {"enabled": True, "friendly_name": "Door cam"},
    "3d_printer": {"enabled": True},
    "offline_cam": {"enabled": False},
}

TOKENS = {"access_token": "access-token", "refresh_token": "refresh-token"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    # --- plumbing ---------------------------------------------------------
    def send_json(self, payload, status=200):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_bytes(self, body, content_type):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorised(self):
        return self.headers.get("Authorization") == "Bearer " + TOKENS["access_token"]

    # --- endpoints --------------------------------------------------------
    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        path = urlparse(self.path).path

        if path == "/api/login":
            form = dict(pair.split("=", 1) for pair in body.decode().split("&") if "=" in pair)
            if form.get("username") == "test" and form.get("password") == "test":
                return self.send_json(TOKENS)
            return self.send_json({"message": "Invalid credentials"}, status=401)

        if path == "/api/refresh":
            return self.send_json(TOKENS)

        return self.send_json({"message": "not found"}, status=404)

    def do_GET(self):
        path = urlparse(self.path).path

        if path == "/api/auth":
            if not self.authorised():
                return self.send_json({"message": "unauthorized"}, status=401)
            return self.send_json({"username": "test", "role": "admin"})

        if not self.authorised():
            return self.send_json({"message": "unauthorized"}, status=401)

        if path == "/api/config":
            return self.send_json({"cameras": CAMERAS})

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
                    "top_score": 0.87,
                    "has_clip": True,
                    "has_snapshot": True,
                    "zones": ["North_driveway"],
                }
                for i in range(3)
            ]
            return self.send_json(events)

        if path.startswith("/api/events/") and path.endswith(".jpg"):
            return self.send_bytes(FRAMES[0], "image/jpeg")

        if path.endswith("/latest.jpg"):
            return self.send_bytes(FRAMES[0], "image/jpeg")

        if path.startswith("/api/"):
            return self.stream_mjpeg()

        return self.send_json({"message": "not found"}, status=404)

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
