import importlib.util
import json
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


if __name__ == "__main__":
    unittest.main()
