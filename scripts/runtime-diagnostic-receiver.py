#!/usr/bin/env python3
"""Temporary write-only receiver. Put behind an HTTPS tunnel for phone access.

The access code and received reports are local files, never repository content.
No app control, shell, or public log-reading endpoint is exposed. An optional
private connection page serves only the explicitly reviewed APK.
"""
import argparse
import base64
import hashlib
import html
import hmac
import json
import os
import secrets
import shutil
import socketserver
import sqlite3
import ssl
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

MAX_BYTES = 1_048_576


def validate_report(raw):
    report = json.loads(raw)
    if (not isinstance(report, dict) or report.get("schemaVersion") != 1
            or not isinstance(report.get("sessionId"), str)
            or not isinstance(report.get("device"), dict)
            or not isinstance(report.get("logs"), list) or len(report["logs"]) > 100):
        raise ValueError("invalid report")
    if any(not isinstance(entry, dict) or not isinstance(entry.get("id"), str) or not 1 <= len(entry["id"]) <= 256 for entry in report["logs"]):
        raise ValueError("invalid record identity")


def save_report(output, raw, lock):
    """Commit a batch and its new records before acknowledging; replays are idempotent."""
    report = json.loads(raw)
    with lock:
        database = output / "logs.sqlite3"
        with sqlite3.connect(database) as connection:
            os.chmod(database, 0o600)
            connection.execute("PRAGMA synchronous=FULL")
            connection.execute("CREATE TABLE IF NOT EXISTS batches (digest TEXT PRIMARY KEY, received REAL, report TEXT)")
            connection.execute("CREATE TABLE IF NOT EXISTS logs (sequence INTEGER PRIMARY KEY AUTOINCREMENT, stream TEXT, id TEXT, received REAL, entry TEXT, UNIQUE(stream,id))")
            received = time.time()
            connection.execute("INSERT OR IGNORE INTO batches VALUES (?,?,?)", (hashlib.sha256(raw).hexdigest(), received, raw.decode()))
            for entry in report["logs"]:
                connection.execute("INSERT OR IGNORE INTO logs(stream,id,received,entry) VALUES (?,?,?,?)",
                                   (report["sessionId"], entry["id"], received, json.dumps(entry, ensure_ascii=False)))
        temp = output / "latest.json.tmp"
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        temp.replace(output / "latest.json")
        (output / "last-received.txt").write_text(str(time.time()) + "\n")


def read_new_logs(output, after=0, limit=1000):
    """Local inspection only: persist the returned cursor to read just the next records."""
    database = Path(output) / "logs.sqlite3"
    if not database.is_file():
        return []
    with sqlite3.connect(database) as connection:
        return [{"sequence": row[0], "streamId": row[1], "receivedAt": row[2], "log": json.loads(row[3])}
                for row in connection.execute("SELECT sequence,stream,received,entry FROM logs WHERE sequence>? ORDER BY sequence LIMIT ?",
                                              (after, min(max(limit, 1), 10000)))]


def make_tcp_server(bind, port, code, output, cert, key, lifetime=3600):
    """Pinned TLS carrying OBD1 frames, never HTTP. Only an acknowledgement returns."""
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert, key)
    deadline = time.monotonic() + lifetime if lifetime else float("inf")
    write_lock = threading.Lock()
    slots = threading.BoundedSemaphore(8)

    class Handler(socketserver.BaseRequestHandler):
        def handle(self):
            self.request.settimeout(10)
            try:
                # This is a dedicated log protocol, not an HTTPS listener. Never
                # answer unrelated HTTP/TLS probes or accept plaintext reports.
                prefix = bytearray()
                while len(prefix) < 4:
                    part = self.request.recv(4 - len(prefix))
                    if not part:
                        return
                    prefix.extend(part)
                if prefix != b"OBD1":
                    return
                with context.wrap_socket(self.request, server_side=True) as connection:
                    if time.monotonic() >= deadline:
                        connection.sendall(b"\x03")
                        return
                    def read_exact(size):
                        data = bytearray()
                        while len(data) < size:
                            part = connection.recv(size - len(data))
                            if not part:
                                raise ValueError("incomplete frame")
                            data.extend(part)
                        return bytes(data)
                    if read_exact(4) != b"OBD1":
                        return
                    code_size = struct.unpack("!H", read_exact(2))[0]
                    if not 32 <= code_size <= 128:
                        return
                    if not hmac.compare_digest(read_exact(code_size), code.encode("ascii")):
                        connection.sendall(b"\x01")
                        return
                    size = struct.unpack("!I", read_exact(4))[0]
                    if not 0 < size <= MAX_BYTES:
                        connection.sendall(b"\x02")
                        return
                    raw = read_exact(size)
                    validate_report(raw)
                    save_report(output, raw, write_lock)
                    connection.sendall(b"\x00")
            except (OSError, ValueError, UnicodeError):
                pass  # Never print credentials, reports, or a peer's supplied bytes.

    class Server(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True

        def process_request(self, request, client_address):
            if not slots.acquire(blocking=False):
                self.shutdown_request(request)
                return
            try:
                super().process_request(request, client_address)
            except Exception:
                slots.release()
                raise

        def process_request_thread(self, request, client_address):
            try:
                super().process_request_thread(request, client_address)
            finally:
                slots.release()

        def handle_error(self, request, client_address):
            pass

    return Server((bind, port), Handler)


def make_server(bind, port, code, output, lifetime=3600, landing=None):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    deadline = time.monotonic() + lifetime if lifetime else float("inf")
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
            if not landing or time.monotonic() >= deadline:
                self.reply(404)
                return
            entry = "/connect/" + landing["ticket"]
            if self.path == entry + "/app.apk":
                apk = landing["apk"]
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Disposition", 'attachment; filename="' + apk.name + '"')
                self.send_header("Content-Length", str(apk.stat().st_size))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.end_headers()
                with apk.open("rb") as stream:
                    shutil.copyfileobj(stream, self.wfile)
                return
            if self.path != entry:
                self.reply(404)  # Reports are never available over HTTP.
                return
            payload = json.dumps({"schemaVersion": 1,
                                  "endpoint": landing["public_url"] + "/v1/runtime-logs",
                                  "code": code}, separators=(",", ":")).encode()
            link = "omnibotdiagnostics://connect#" + base64.urlsafe_b64encode(payload).decode().rstrip("=")
            body = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>OmniBot 真机诊断</title><style>
body{{font:17px system-ui;max-width:620px;margin:48px auto;padding:0 24px;color:#16273b;line-height:1.7}}
a{{display:block;background:#2c7feb;color:white;padding:14px 20px;border-radius:12px;text-decoration:none;margin:18px 0;text-align:center}}
code{{overflow-wrap:anywhere;font-size:12px}}small{{color:#536475}}
</style><h1>OmniBot 真机诊断</h1>
<p>先安装更新，再打开诊断连接。地址和诊断码会自动填好。</p>
<a href="{entry}/app.apk">1 · 下载已检查的 APK</a>
<a href="{html.escape(link, quote=True)}">2 · 在 OmniBot 中连接诊断</a>
<p>连接后自动启用上报，重开应用会自动恢复，然后复现选模、Agent 或插件问题。
发送经过凭证过滤的本应用各级别日志、运行耗时和设备版本信息，可在应用内停止。</p>
<small>此链接请勿公开转发。接收器不提供日志读取或远程控制功能。</small>
<p><small>APK SHA-256</small><br><code>{landing["sha256"]}</code></p></html>'''.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
            self.end_headers()
            self.wfile.write(body)

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
                validate_report(raw)
            except (ValueError, UnicodeError, TimeoutError):
                self.reply(400)
                return
            # Only acknowledge after durable, idempotent local retention.
            save_report(output, raw, write_lock)
            self.reply(204)

    return ThreadingHTTPServer((bind, port), Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8767)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--code-file", type=Path, required=True)
    parser.add_argument("--resume-code", action="store_true", help="Reuse this receiver's private access code after a restart")
    parser.add_argument("--lifetime", type=int, default=3600)
    parser.add_argument("--public-url", help="HTTPS origin used by the private connection page")
    parser.add_argument("--apk", type=Path, help="Only an already-reviewed public APK")
    parser.add_argument("--apk-sha256", help="Approved APK SHA-256, checked before serving")
    parser.add_argument("--link-file", type=Path, help="Save the private connection URL locally")
    parser.add_argument("--tcp-tls", action="store_true", help="Receive raw diagnostic frames over pinned TLS")
    parser.add_argument("--tls-cert", type=Path)
    parser.add_argument("--tls-key", type=Path)
    parser.add_argument("--public-address", help="TCP tunnel host:port, including a literal IP if desired")
    args = parser.parse_args()
    os.umask(0o077)
    if args.lifetime != 0 and not 60 <= args.lifetime <= 86400:
        parser.error("lifetime must be 0 (until stopped) or between 60 seconds and 24 hours")
    args.code_file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if args.resume_code:
        code = args.code_file.read_text().strip()
        if not 32 <= len(code) <= 128 or any(c not in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-" for c in code):
            parser.error("invalid saved access code")
    else:
        with args.code_file.open("x") as stream:
            code = secrets.token_urlsafe(32)
            stream.write(code + "\n")
    landing = None
    if args.tcp_tls:
        if not all([args.tls_cert, args.tls_key, args.public_address, args.link_file]):
            parser.error("TCP TLS requires --tls-cert, --tls-key, --public-address and --link-file")
        if any([args.public_url, args.apk, args.apk_sha256]):
            parser.error("TCP diagnostic transport does not serve web pages or APKs")
        address = urlsplit("tls://" + args.public_address)
        if (not address.hostname or not address.port or address.username or address.password
                or address.path or address.query or address.fragment):
            parser.error("--public-address must be host:port")
        der = ssl.PEM_cert_to_DER_cert(args.tls_cert.read_text())
        endpoint = "tls://" + args.public_address + "/" + hashlib.sha256(der).hexdigest()
        payload = json.dumps({"schemaVersion": 1, "endpoint": endpoint, "code": code}, separators=(",", ":")).encode()
        args.link_file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with args.link_file.open("x") as stream:
            stream.write("omnibotdiagnostics://connect#" + base64.urlsafe_b64encode(payload).decode().rstrip("=") + "\n")
    elif any([args.public_url, args.apk, args.apk_sha256, args.link_file]):
        if not all([args.public_url, args.apk, args.apk_sha256, args.link_file]):
            parser.error("connection pages require --public-url, --apk, --apk-sha256 and --link-file")
        origin = urlsplit(args.public_url)
        if (origin.scheme != "https" or not origin.hostname or origin.username or origin.password
                or origin.path not in ("", "/") or origin.query or origin.fragment):
            parser.error("--public-url must be an HTTPS origin")
        with args.apk.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if not hmac.compare_digest(actual, args.apk_sha256):
            parser.error("APK does not match its reviewed SHA-256")
        landing = {"public_url": args.public_url.rstrip("/"), "apk": args.apk.resolve(),
                   "sha256": actual, "ticket": secrets.token_urlsafe(24)}
        args.link_file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with args.link_file.open("x") as stream:
            stream.write(landing["public_url"] + "/connect/" + landing["ticket"] + "\n")
    server = (make_tcp_server(args.bind, args.port, code, args.output, args.tls_cert, args.tls_key, args.lifetime)
              if args.tcp_tls else make_server(args.bind, args.port, code, args.output, args.lifetime, landing))
    timer = threading.Timer(args.lifetime, server.shutdown)
    timer.daemon = True
    if args.lifetime:
        timer.start()
    print(f"Receiver listening on {args.bind}:{server.server_address[1]}; code saved locally", flush=True)
    try:
        server.serve_forever()
    finally:
        timer.cancel()
        server.server_close()


if __name__ == "__main__":
    main()
