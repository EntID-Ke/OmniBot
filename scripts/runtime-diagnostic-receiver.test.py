import importlib.util
import json
import hashlib
import re
import base64
import socket
import ssl
import struct
import subprocess
import tempfile
import threading
import unittest
import sys
sys.dont_write_bytecode = True
import urllib.error
import urllib.request
from pathlib import Path

spec = importlib.util.spec_from_file_location("receiver", Path(__file__).with_name("runtime-diagnostic-receiver.py"))
receiver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(receiver)


class ReceiverTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.code = "fixture-diagnostic-code-32-characters"
        self.server = receiver.make_server("127.0.0.1", 0, self.code, self.temp.name)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join()
        self.temp.cleanup()

    def send(self, body=None, path="/v1/runtime-logs", code=None, extra=None, method="POST"):
        headers = {"Authorization": "Bearer " + (code or self.code), "Content-Type": "application/json"}
        headers.update(extra or {})
        request = urllib.request.Request(self.url + path, data=body, headers=headers, method=method)
        try:
            # Fixture traffic must never go through a user's HTTP proxy.
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=5) as response:
                return response.status
        except urllib.error.HTTPError as error:
            return error.code

    def report(self):
        return json.dumps({"schemaVersion": 1, "sessionId": "fixture", "device": {}, "logs": []}).encode()

    def test_authenticated_report_is_saved_locally_but_not_readable_over_http(self):
        self.assertEqual(204, self.send(self.report()))
        self.assertEqual(json.loads(self.report()), json.loads((Path(self.temp.name) / "latest.json").read_text()))
        self.assertEqual(0o600, (Path(self.temp.name) / "latest.json").stat().st_mode & 0o777)
        self.assertEqual(404, self.send(method="GET"))

    def test_wrong_code_browser_origin_and_query_are_rejected(self):
        self.assertEqual(401, self.send(self.report(), code="wrong"))
        self.assertEqual(403, self.send(self.report(), extra={"Origin": "https://elsewhere.example"}))
        self.assertEqual(404, self.send(self.report(), path="/v1/runtime-logs?code=anything"))
        self.assertFalse((Path(self.temp.name) / "latest.json").exists())

    def test_invalid_and_oversize_reports_are_rejected(self):
        self.assertEqual(400, self.send(b"not json"))
        self.assertEqual(400, self.send(b"{}"))
        self.assertEqual(413, self.send(b"x" * (receiver.MAX_BYTES + 1)))

    def test_private_connection_page_delivers_only_reviewed_apk_and_pairing(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join()
        apk = Path(self.temp.name) / "reviewed.apk"
        apk.write_bytes(b"reviewed fixture APK")
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        landing = {"ticket": "fixture-private-ticket", "public_url": "https://receiver.example",
                   "apk": apk, "sha256": digest}
        self.server = receiver.make_server("127.0.0.1", 0, self.code, self.temp.name, landing=landing)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(self.url + "/connect/fixture-private-ticket") as response:
            body = response.read().decode()
            self.assertEqual("no-store", response.headers["Cache-Control"])
            self.assertEqual("no-referrer", response.headers["Referrer-Policy"])
        fragment = re.search(r"omnibotdiagnostics://connect#([A-Za-z0-9_-]+)", body).group(1)
        payload = json.loads(base64.urlsafe_b64decode(fragment + "=" * (-len(fragment) % 4)))
        self.assertEqual(self.code, payload["code"])
        self.assertEqual("https://receiver.example/v1/runtime-logs", payload["endpoint"])
        with opener.open(self.url + "/connect/fixture-private-ticket/app.apk") as response:
            self.assertEqual(digest, hashlib.sha256(response.read()).hexdigest())
        for path in ["/", "/latest.json", "/connect/wrong-ticket", "/connect/fixture-private-ticket/../latest.json"]:
            self.assertEqual(404, self.send(path=path, method="GET"))


class TcpReceiverTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.certs = tempfile.TemporaryDirectory()
        cls.cert = Path(cls.certs.name) / "fixture.pem"
        cls.key = Path(cls.certs.name) / "fixture.key"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
                        "-nodes", "-days", "1", "-subj", "/CN=diagnostic-fixture", "-keyout", str(cls.key),
                        "-out", str(cls.cert)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    @classmethod
    def tearDownClass(cls):
        cls.certs.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.code = "a" * 43
        self.server = receiver.make_tcp_server("127.0.0.1", 0, self.code, self.temp.name, self.cert, self.key)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join()
        self.temp.cleanup()

    def send(self, body, code=None, size=None, magic=b"OBD1"):
        context = ssl.create_default_context(cafile=str(self.cert))
        with socket.create_connection(self.server.server_address, timeout=5) as raw:
            with context.wrap_socket(raw, server_hostname="diagnostic-fixture") as connection:
                access = (code or self.code).encode()
                connection.sendall(magic + struct.pack("!H", len(access)) + access + struct.pack("!I", size or len(body)) + body)
                return connection.recv(1)

    def test_tls_frame_is_authenticated_and_saved_with_private_permissions(self):
        body = b'{"schemaVersion":1,"sessionId":"fixture","device":{},"logs":[]}'
        self.assertEqual(b"\x00", self.send(body))
        saved = Path(self.temp.name) / "latest.json"
        self.assertEqual(body, saved.read_bytes())
        self.assertEqual(0o600, saved.stat().st_mode & 0o777)

    def test_wrong_code_invalid_report_and_oversize_frame_cannot_write(self):
        self.assertEqual(b"\x01", self.send(b"{}", code="b" * 43))
        self.assertEqual(b"\x02", self.send(b"{}", size=receiver.MAX_BYTES + 1))
        self.assertEqual(b"", self.send(b"{}"))
        self.assertEqual(b"", self.send(b"{}", magic=b"GET "))
        self.assertFalse((Path(self.temp.name) / "latest.json").exists())

    def test_plaintext_and_expired_session_cannot_write(self):
        with socket.create_connection(self.server.server_address, timeout=5) as connection:
            connection.sendall(b"OBD1plaintext-is-rejected")
            try:
                self.assertEqual(b"", connection.recv(32))
            except ConnectionResetError:
                pass
        self.server.shutdown()
        self.server.server_close()
        self.worker.join()
        self.server = receiver.make_tcp_server("127.0.0.1", 0, self.code, self.temp.name, self.cert, self.key, lifetime=-1)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        context = ssl.create_default_context(cafile=str(self.cert))
        with socket.create_connection(self.server.server_address, timeout=5) as raw:
            with context.wrap_socket(raw, server_hostname="diagnostic-fixture") as connection:
                self.assertEqual(b"\x03", connection.recv(1))
        self.assertFalse((Path(self.temp.name) / "latest.json").exists())


if __name__ == "__main__":
    unittest.main()
