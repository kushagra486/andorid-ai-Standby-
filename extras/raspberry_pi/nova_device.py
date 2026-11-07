#!/usr/bin/env python3
"""NØVA smart-home device for Raspberry Pi (standard library only).

POST /command {"command": "on|off|toggle"} with "Authorization: Bearer <NOVA_TOKEN>".
Run:  NOVA_TOKEN=... GPIO_PIN=17 python3 nova_device.py
For HTTPS put it behind a reverse proxy (e.g. Caddy) or keep it on your home LAN only.
"""
import json, os, hmac
from http.server import BaseHTTPRequestHandler, HTTPServer

TOKEN = os.environ.get("NOVA_TOKEN", "")
PIN = int(os.environ.get("GPIO_PIN", "17"))
state = {"on": False}

try:
    import RPi.GPIO as GPIO  # type: ignore
    GPIO.setmode(GPIO.BCM); GPIO.setup(PIN, GPIO.OUT)
    def apply(): GPIO.output(PIN, GPIO.HIGH if state["on"] else GPIO.LOW)
except ImportError:
    def apply(): print("state ->", "on" if state["on"] else "off")

class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def do_POST(self):
        if self.path != "/command":
            return self._send(404, {"error": "not found"})
        auth = self.headers.get("Authorization", "")
        if not TOKEN or not hmac.compare_digest(auth, "Bearer " + TOKEN):
            return self._send(401, {"error": "unauthorized"})
        try:
            cmd = json.loads(self.rfile.read(min(int(self.headers.get("Content-Length", 0)), 1024))).get("command")
        except Exception:
            return self._send(400, {"error": "bad json"})
        if cmd == "toggle": state["on"] = not state["on"]
        elif cmd in ("on", "off"): state["on"] = cmd == "on"
        else: return self._send(400, {"error": "unknown command"})
        apply()
        self._send(200, {"state": "on" if state["on"] else "off"})

    def log_message(self, *a): pass

if __name__ == "__main__":
    if not TOKEN: raise SystemExit("Set NOVA_TOKEN")
    HTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
