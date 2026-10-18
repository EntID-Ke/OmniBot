#!/usr/bin/env python3
"""Temporary write-only receiver. Put behind an HTTPS tunnel for phone access.

The access code and received reports are local files, never repository content.
No app control, file download, shell, or public log-reading endpoint is exposed.
"""
import argparse
import hmac
import json
import os
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_BYTES = 1_048_576


def make_server(bind, port, code, output, lifetime=3600):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    deadline = time.monotonic() + lifetime
    write_lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # Never log the Authorization header or request content.

        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def reply(self, status):
            self.send_response(status)
            self.send_header("Content-Length", "0")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True

        def do_GET(self):
            self.reply(404)  # Reports are read only from the receiver's local disk.

        def do_POST(self):
            if time.monotonic() >= deadline:
                self.reply(410)
                return
            if self.path != "/v1/runtime-logs":
                self.reply(404)
                return
            supplied = self.headers.get("Authorization", "")
            if not hmac.compare_digest(supplied.encode(), ("Bearer " + code).encode()):
                self.reply(401)
                return
            if self.headers.get("Origin") or self.headers.get("Transfer-Encoding"):
                self.reply(403)
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = 0
            if not 0 < length <= MAX_BYTES:
                self.reply(413)
                return
            if self.headers.get_content_type() != "application/json":
                self.reply(415)
                return
            try:
                raw = self.rfile.read(length)
                if len(raw) != length:
                    raise ValueError("incomplete body")
                report = json.loads(raw)
                if (not isinstance(report, dict) or report.get("schemaVersion") != 1
                        or not isinstance(report.get("sessionId"), str)
                        or not isinstance(report.get("device"), dict)
                        or not isinstance(report.get("logs"), list)
                        or len(report["logs"]) > 100):
                    raise ValueError("invalid report")
            except (ValueError, UnicodeError, TimeoutError):
                self.reply(400)
                return
            # A bounded snapshot retains the app's recent log window. User content
            # never selects a filename, and concurrent uploads replace atomically.
            with write_lock:
                temp = output / "latest.json.tmp"
                fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                with os.fdopen(fd, "wb") as stream:
                    stream.write(raw)
                temp.replace(output / "latest.json")
                (output / "last-received.txt").write_text(str(time.time()) + "\n")
            self.reply(204)

    return ThreadingHTTPServer((bind, port), Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8767)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--code-file", type=Path, required=True)
    parser.add_argument("--lifetime", type=int, default=3600)
    args = parser.parse_args()
    os.umask(0o077)
    if not 60 <= args.lifetime <= 86400:
        parser.error("lifetime must be between 60 seconds and 24 hours")
    args.code_file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    # Exclusive creation avoids accidentally reviving an old diagnostic session.
    with args.code_file.open("x") as stream:
        code = secrets.token_urlsafe(32)
        stream.write(code + "\n")
    server = make_server(args.bind, args.port, code, args.output, args.lifetime)
    timer = threading.Timer(args.lifetime, server.shutdown)
    timer.daemon = True
    timer.start()
    print(f"Receiver listening on {args.bind}:{server.server_port}; code saved locally", flush=True)
    try:
        server.serve_forever()
    finally:
        timer.cancel()
        server.server_close()


if __name__ == "__main__":
    main()
