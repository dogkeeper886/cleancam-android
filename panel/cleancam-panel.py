#!/usr/bin/env python3
"""
The CleanCam control panel: a page at http://127.0.0.1:5080 that starts and stops the receiver and the phone's app,
shows the stream, and changes the app's settings through its debug port, over the USB cable with adb.
The CleanCam icon runs it, the user's one way in: each start starts the receiver unless it runs, and opens the page;
while the panel runs, a start only asks it for the receiver and opens the page again.
  cleancam-panel.py               as the icon does
  cleancam-panel.py --no-browser  without opening the page
The page and this server keep running after the browser closes, and so does the receiver.
"""
import json
import os
import signal
import socket
import socketserver
import subprocess
import sys
import threading
import time
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer

PANEL_PORT = 5080
DEBUG_PORT = 5003
PACKAGE = "io.github.dogkeeper886.cleancam"
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
RECEIVER = os.path.join(REPO, "receiver", "cleancam-receive.sh")
RECEIVER_PATTERN = r"bash [^ ]*cleancam-receive\.sh"
RECEIVER_LOG = os.path.join(os.environ.get("XDG_RUNTIME_DIR") or "/tmp", "cleancam-receiver.log")
FILES = {"/": ("index.html", "text/html; charset=utf-8"), "/cleancam.svg": ("cleancam.svg", "image/svg+xml")}
HOSTS = {"127.0.0.1:%d" % PANEL_PORT, "localhost:%d" % PANEL_PORT}
# The app sends a `second` line each second while it is open; a quiet second or two means it closed.
APP_QUIET_S = 3

# The SDK's adb comes before the distribution's older one, as the setup skill sets up; the receiver inherits this PATH.
SDK = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Android/Sdk")
ENV = dict(os.environ, PATH=os.path.join(SDK, "platform-tools") + os.pathsep + os.environ.get("PATH", ""))


def run(*args, merge=False, timeout=5):
    """Runs a command and returns its output, or "" when it fails to start or hangs."""
    try:
        return subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT if merge else subprocess.DEVNULL,
                              universal_newlines=True, env=ENV, timeout=timeout).stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        return ""


def adb(*args, **kwargs):
    return run("adb", *args, **kwargs)


class State:
    """What the page shows, filled by the threads below."""

    def __init__(self):
        self.lock = threading.Lock()
        self.phone = "none"  # "device", "unauthorized" or "none"
        self.model = ""
        self.battery = None
        self.keyguard = False
        self.events = {}  # the debug port's last `second`, `mode` and `camera` lines
        self.second_at = 0.0
        self.debug = None  # the debug port's connection, for commands
        self.receiver = None  # the receiver this server started, until it is stopped on purpose
        self.low_battery_stop = None  # the app's low-battery level, kept after it closes, to tell why it closes

    def snapshot(self):
        with self.lock:
            app = time.monotonic() - self.second_at < APP_QUIET_S
            return {
                "phone": self.phone, "model": self.model, "battery": self.battery, "keyguard": self.keyguard,
                "app": app, "receiver": receiver_state(), "lowBatteryStop": self.low_battery_stop,
                "second": self.events.get("second") if app else None,
                "mode": self.events.get("mode") if app else None,
                "camera": self.events.get("camera") if app else None,
            }


state = State()


def battery():
    """The phone's battery level and whether it charges, from Android's battery service."""
    fields = {}
    for line in adb("shell", "dumpsys", "battery").splitlines():
        key, _, value = line.strip().partition(": ")
        fields[key] = value
    if "level" not in fields:
        return None
    # status 2 is charging and 5 full; a phone on a weak USB port can report 4, not charging, while plugged in.
    return {"level": int(fields["level"]), "charging": fields.get("status") in ("2", "5")}


def watch_phone():
    """Checks the phone once a second, and its battery every 5 s."""
    battery_at = 0.0
    while True:
        answer = adb("get-state", merge=True)
        phone = "device" if answer == "device" else "unauthorized" if "unauthorized" in answer else "none"
        model, level, keyguard = "", None, False
        if phone == "device":
            model = state.model or adb("shell", "getprop", "ro.product.model")
            keyguard = "isKeyguardShowing=true" in adb("shell", "dumpsys", "window")
            if time.monotonic() - battery_at > 5:
                level, battery_at = battery(), time.monotonic()
            else:
                level = state.battery
        with state.lock:
            state.phone, state.model, state.keyguard, state.battery = phone, model, keyguard, level
        time.sleep(1)


def watch_debug():
    """Keeps a connection to the app's debug port through adb, and keeps its last line of each kind."""
    while True:
        if state.phone != "device":
            time.sleep(1)
            continue
        # The receiver forwards the debug port too, and removes its forwards when it stops.
        if "tcp:%d tcp:%d" % (DEBUG_PORT, DEBUG_PORT) not in adb("forward", "--list"):
            adb("forward", "tcp:%d" % DEBUG_PORT, "tcp:%d" % DEBUG_PORT)
        try:
            connection = socket.create_connection(("127.0.0.1", DEBUG_PORT), timeout=3)
        except OSError:
            time.sleep(1)
            continue
        connection.settimeout(None)
        with state.lock:
            state.debug = connection
        # adb accepts the connection even while the app is closed, then ends it at once.
        for line in connection.makefile("r", encoding="utf-8", errors="replace"):
            try:
                event = json.loads(line)
            except ValueError:
                continue
            kind = event.get("event")
            # The `second` line carries the low-battery level, and the `lowBattery` event the level that closed the app.
            stop_at = (event.get("battery") or {}).get("stopAt") if kind == "second" else event.get("stopAt")
            if kind in ("second", "lowBattery") and stop_at is not None:
                with state.lock:
                    state.low_battery_stop = stop_at
            if kind in ("second", "mode", "camera"):
                with state.lock:
                    state.events[kind] = event
                    if kind == "second":
                        state.second_at = time.monotonic()
        with state.lock:
            state.debug = None
        connection.close()
        time.sleep(1)


def send_command(line):
    with state.lock:
        connection = state.debug
    if connection is None:
        return False
    try:
        connection.sendall((line + "\n").encode())
        return True
    except OSError:
        return False


def receiver_pid():
    pid = run("pgrep", "-o", "-f", RECEIVER_PATTERN)
    return int(pid) if pid.isdigit() else None


def receiver_state():
    """Whether the receiver runs, started here or in a terminal, and its last line when it stopped by itself."""
    if receiver_pid():
        return {"running": True, "error": ""}
    started = state.receiver
    if started is None or started.poll() is None:  # poll() also reaps it
        return {"running": False, "error": ""}
    try:
        with open(RECEIVER_LOG) as log:
            lines = [line.strip() for line in log if line.strip()]
    except OSError:
        lines = []
    return {"running": False, "error": lines[-1] if lines else "The receiver stopped."}


def start_receiver():
    if receiver_pid():
        return
    with open(RECEIVER_LOG, "w") as log:
        # Its own session, so it outlives this server and stops as a group, as Ctrl-C stops it in a terminal.
        state.receiver = subprocess.Popen([RECEIVER], stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                          env=ENV, cwd=REPO, start_new_session=True)


def stop_receiver():
    pid = receiver_pid()
    if not pid:
        return
    # SIGINT, as Ctrl-C sends, runs the receiver's cleanup: it removes the adb forwards and the CleanCam sound devices.
    group = os.getpgid(pid)
    if group != os.getpgrp():
        os.killpg(group, signal.SIGINT)
    else:
        os.kill(pid, signal.SIGINT)
    started, state.receiver = state.receiver, None  # a stop on purpose leaves no error to show
    if started:
        threading.Thread(target=started.wait, daemon=True).start()


def set_app(on):
    if on:
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
    else:
        adb("shell", "am", "force-stop", PACKAGE)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if not self.trusted():
            return
        if self.path == "/api/state":
            return self.reply(200, "application/json", json.dumps(state.snapshot()).encode())
        if self.path in FILES:
            name, kind = FILES[self.path]
            with open(os.path.join(HERE, name), "rb") as page:
                return self.reply(200, kind, page.read())
        self.reply(404, "text/plain", b"Not found")

    def do_POST(self):
        # The custom header makes a browser ask first before another site's page posts here, and this server never
        # allows it, so only the panel's own page changes the phone.
        if not self.trusted() or self.headers.get("X-CleanCam") != "1":
            return self.reply(403, "text/plain", b"Forbidden")
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        except ValueError:
            return self.reply(400, "text/plain", b"Bad JSON")
        ok = True
        if self.path == "/api/receiver":
            start_receiver() if body.get("on") else stop_receiver()
        elif self.path == "/api/app":
            set_app(bool(body.get("on")))
        elif self.path == "/api/command":
            ok = send_command(str(body.get("line", ""))[:100])
        else:
            return self.reply(404, "text/plain", b"Not found")
        self.reply(200 if ok else 503, "application/json", json.dumps({"ok": ok}).encode())

    def trusted(self):
        """Refuses a Host other than the panel's, so a web page that renames itself to 127.0.0.1 can't reach it."""
        if self.headers.get("Host") in HOSTS:
            return True
        self.reply(403, "text/plain", b"Forbidden")
        return False

    def reply(self, code, kind, data):
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


class Server(socketserver.ThreadingMixIn, HTTPServer):
    daemon_threads = True


def main():
    url = "http://127.0.0.1:%d/" % PANEL_PORT
    open_browser = "--no-browser" not in sys.argv[1:]
    try:
        server = Server(("127.0.0.1", PANEL_PORT), Handler)
    except OSError:
        # The panel already runs: it starts the receiver, so the receiver stays its own, and the page opens again.
        request = urllib.request.Request(url + "api/receiver", data=b'{"on": true}', method="POST",
                                         headers={"Content-Type": "application/json", "X-CleanCam": "1"})
        try:
            urllib.request.urlopen(request, timeout=5).close()
        except OSError as e:
            print("The panel at %s didn't start the receiver: %s" % (url, e), file=sys.stderr)
        if open_browser:
            webbrowser.open(url)
        return
    start_receiver()
    for watch in (watch_phone, watch_debug):
        threading.Thread(target=watch, daemon=True).start()
    if open_browser:
        threading.Timer(0.5, webbrowser.open, (url,)).start()
    print("CleanCam control panel at " + url, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
