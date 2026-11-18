import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import Request, urlopen

from server import Relay, Server


QR_PROOF = "examplekey.9999999999999." + "a" * 43
SESSION = "example-session-cookie"


class MockUpstream(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlsplit(self.path)
        if url.path == "/api/cloudsearch":
            result = {"result": {"query": parse_qs(url.query)["keywords"][0]}, "cookie": "private"}
        elif url.path == "/api/song/url/v1":
            authenticated = self.headers.get("X-Netease-Session") == SESSION
            result = {"data": [{"id": 42, "url": "https://example.org/song.flac",
                                "freeTrialInfo": None if authenticated else {"end": 30}}]}
        elif url.path == "/api/top/playlist":
            result = {"playlists": [{"id": 77, "name": parse_qs(url.query)["cat"][0]}], "more": True}
        elif url.path == "/api/user/account":
            authenticated = self.headers.get("X-Netease-Session") == SESSION
            result = {"account": {"id": 55, "password": "private"} if authenticated else None}
        elif url.path == "/api/user/playlist":
            authenticated = self.headers.get("X-Netease-Session") == SESSION
            result = {"playlist": [{"id": 77, "subscribed": True}] if authenticated else [], "more": False}
        elif url.path == "/api/comment/music":
            comment = {"commentId": 123, "likedCount": 4, "content": "试听留言", "time": 1700000000000,
                       "user": {"nickname": "听众", "userId": 999}}
            result = {"comments": [comment], "hotComments": [comment], "more": True, "total": 50}
        elif url.path == "/api/song/red/count":
            result = {"data": {"count": 120}}
        elif url.path == "/api/login/qr/start":
            result = {"proof": QR_PROOF, "qr": "data:image/png;base64,example"}
        elif url.path == "/api/login/qr/poll":
            proof = self.headers.get("X-Netease-QR-Proof")
            result = {"code": 803, "session": SESSION, "cookie": "private"} if proof == QR_PROOF else {"code": 800}
        else:
            self.send_response(302)
            self.send_header("Location", "/api/cloudsearch?keywords=redirected&type=1")
            self.end_headers()
            return
        self.send_response(200)
        self.end_headers()
        self.wfile.write(json.dumps({"code": 200, **result}).encode())

    def log_message(self, *_):
        pass


class GatewayTest(unittest.TestCase):
    def start_server(self, server):
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join)
        self.addCleanup(server.shutdown)
        return f"http://127.0.0.1:{server.server_port}"

    def test_android_routes_forward_only_catalogue_queries(self):
        upstream = self.start_server(ThreadingHTTPServer(("127.0.0.1", 0), MockUpstream))
        with tempfile.TemporaryDirectory() as releases:
            relay = Relay({
                "origin": "https://example.org",
                "admin_token": "a" * 32,
                "releases": releases,
                "devices": {},
                "netease_api_url": upstream + "/api",
            })
            base = self.start_server(Server(("127.0.0.1", 0), relay))
            query = urlencode({"keywords": "试音 + &", "type": "1", "offset": 0, "limit": 50})
            with urlopen(base + "/netease/cloudsearch?" + query, timeout=3) as response:
                self.assertEqual(json.load(response), {"code": 200, "result": {"query": "试音 + &"}})
                self.assertEqual(response.headers["Cache-Control"], "no-store")
            with urlopen(base + "/netease/song/url/v1?id=42&level=hires", timeout=3) as response:
                self.assertEqual(json.load(response)["data"][0]["freeTrialInfo"], {"end": 30})
            with urlopen(base + "/netease/song/url/v1?id=42&level=jymaster", timeout=3) as response:
                self.assertEqual(response.status, 200, "超清母带 is one of 网易云's levels")
            with urlopen(base + "/netease/login/qr/start", timeout=3) as response:
                self.assertEqual(json.load(response)["proof"], QR_PROOF)
            poll = Request(base + "/netease/login/qr/poll", headers={"X-Netease-QR-Proof": QR_PROOF})
            with urlopen(poll, timeout=3) as response:
                self.assertEqual(json.load(response), {"code": 803, "session": SESSION})
            song = Request(base + "/netease/song/url/v1?id=42&level=hires", headers={"X-Netease-Session": SESSION})
            with urlopen(song, timeout=3) as response:
                self.assertIsNone(json.load(response)["data"][0]["freeTrialInfo"])
            with urlopen(base + "/netease/comment/music?id=42&offset=5000&limit=20&before=1700000000000", timeout=3) as response:
                page = json.load(response)
                self.assertEqual(page["total"], 50)
                self.assertTrue(page["more"])
                self.assertEqual(page["comments"][0]["likedCount"], 4)
                self.assertEqual(page["hotComments"][0]["user"], {"nickname": "听众"})
            with urlopen(base + "/netease/song/red/count?id=42", timeout=3) as response:
                self.assertEqual(json.load(response)["data"]["count"], 120)
            category = urlencode({"cat": "摇滚", "limit": 30, "offset": 30})
            with urlopen(base + "/netease/top/playlist?" + category, timeout=3) as response:
                self.assertEqual(json.load(response)["playlists"][0]["name"], "摇滚")
            session_headers = {"X-Netease-Session": SESSION}
            with urlopen(Request(base + "/netease/user/account", headers=session_headers), timeout=3) as response:
                self.assertEqual(json.load(response)["account"], {"id": 55})
            with urlopen(Request(base + "/netease/user/playlist?uid=55", headers=session_headers), timeout=3) as response:
                self.assertTrue(json.load(response)["playlist"][0]["subscribed"])
            for path, status in (
                ("/netease/cloudsearch?" + query + "&type=1", 400),
                ("/netease/song/url/v1?id=42&level=hires&cookie=test", 400),
                ("/netease/song/url/v1?id=42&level=dolby", 400),
                ("/netease/login", 404),
                ("/netease/user/account", 401),
                ("/netease/top/playlist?cat=invalid", 400),
                ("/netease/login/qr/poll", 400),
                ("/netease/album?id=42", 502),
            ):
                with self.assertRaises(HTTPError) as failure:
                    urlopen(base + path, timeout=3)
                self.assertEqual(failure.exception.code, status)
                failure.exception.close()
            relay.netease.base = ""
            with self.assertRaises(HTTPError) as failure:
                urlopen(base + "/netease/lyric?id=42", timeout=3)
            self.assertEqual(failure.exception.code, 503)
            failure.exception.close()
            with urlopen(base + "/health", timeout=3) as response:
                self.assertEqual(json.load(response), {"ok": True})


if __name__ == "__main__":
    unittest.main()
