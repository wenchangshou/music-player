"""Relay tests: account devices, remote control between devices and session isolation."""
import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

import server
from accounts import Accounts


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        database = os.path.join(self.tmp.name, "accounts.db")
        accounts = Accounts(database)
        accounts.create("alice", "password1")
        accounts.create("bob", "password2")
        relay = server.Relay({"admin_token": "A" * 40, "devices": {}, "origin": "https://relay.example.com",
                              "releases": self.tmp.name, "account_db": database})
        self.server = server.Server(("127.0.0.1", 0), relay)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.desktop = self.login("alice", "password1", "desktop", "Linux 桌面播放器")
        self.phone = self.login("alice", "password1", "android", "Pixel")
        self.state = {"queue": [{"id": 1, "title": "Song A", "artist": "X", "kind": "tidal",
                                 "cover": "https://resources.tidal.com/a.jpg"}],
                      "index": 0, "playing": True, "revision": 1, "repeat": 0, "volume": 40}

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def call(self, path, body=None, session=None, device=False):
        request = urllib.request.Request(self.base + path, method="GET" if body is None else "POST",
                                         data=None if body is None else json.dumps(body).encode())
        if body is not None:
            request.add_header("Content-Type", "application/json")
        if session:
            request.add_header("Authorization", "Bearer " + session["key"])
            if device:
                request.add_header("X-Device-ID", session["id"])
        try:
            with urllib.request.urlopen(request) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            with error:
                return error.code, json.loads(error.read())

    def login(self, username, password, platform, name):
        status, body = self.call("/account/login", {"username": username, "password": password,
                                                     "platform": platform, "name": name})
        self.assertEqual(status, 200)
        return body

    def poll(self, session, state=None, result=None):
        message = {"state": state or self.state}
        if result is not None:
            message["result"] = result
        return self.call("/device/poll", message, session, device=True)

    def test_device_list_marks_self_and_now_playing(self):
        self.poll(self.phone)
        status, body = self.call("/api/devices", session=self.desktop)
        self.assertEqual(status, 200)
        devices = {device["name"]: device for device in body["devices"]}
        self.assertTrue(devices["Linux 桌面播放器"]["self"])
        self.assertFalse(devices["Linux 桌面播放器"]["online"])
        self.assertIsNone(devices["Linux 桌面播放器"]["nowPlaying"])
        phone = devices["Pixel"]
        self.assertFalse(phone["self"])
        self.assertTrue(phone["online"])
        self.assertEqual(phone["nowPlaying"], {"title": "Song A", "artist": "X",
                                               "cover": "https://resources.tidal.com/a.jpg", "playing": True})

    def test_now_playing_ignores_malformed_state(self):
        self.poll(self.phone, {"queue": "nope", "index": 3})
        _, body = self.call("/api/devices", session=self.desktop)
        self.assertIsNone(next(d for d in body["devices"] if d["name"] == "Pixel")["nowPlaying"])

    def test_desktop_controls_phone_through_receipts(self):
        self.poll(self.phone)
        phone = self.phone["id"]
        for command in ({"op": "toggle"}, {"op": "tracks", "ids": [5, 6], "index": 1, "mode": "play", "position": 9000},
                        {"op": "volume", "value": 30}, {"op": "clear"}, {"op": "quality", "value": "LOSSLESS"}):
            status, sent = self.call("/api/control?device=" + phone, command, self.desktop)
            self.assertEqual(status, 200, command)
            _, pending = self.call(f"/api/receipt?device={phone}&command={sent['command']}", session=self.desktop)
            self.assertEqual(pending, {"pending": True})
            _, reply = self.poll(self.phone)
            self.assertEqual(reply["command"], command)
            reply_state = {**self.state, "playing": False}
            self.poll(self.phone, reply_state, {"id": reply["id"], "payload": reply_state})
            status, receipt = self.call(f"/api/receipt?device={phone}&command={sent['command']}", session=self.desktop)
            self.assertEqual((status, receipt), (200, {"result": reply_state}))

    def test_long_poll_delivers_command_without_delay(self):
        import time
        self.poll(self.phone)
        replies = []
        holder = threading.Thread(target=lambda: replies.append(
            self.call("/device/poll", {"state": self.state, "wait": True}, self.phone, device=True)))
        started = time.monotonic()
        holder.start()
        time.sleep(0.3)
        _, sent = self.call("/api/control?device=" + self.phone["id"], {"op": "toggle"}, self.desktop)
        waiting = []
        receipt = threading.Thread(target=lambda: waiting.append(self.call(
            f"/api/receipt?device={self.phone['id']}&command={sent['command']}&wait=1", session=self.desktop)))
        receipt.start()
        holder.join(3)
        status, reply = replies[0]
        self.assertEqual(status, 200)
        self.assertEqual(reply["command"], {"op": "toggle"})
        self.assertLess(time.monotonic() - started, 1.5, "the held poll should return as soon as the command arrives")
        answered = time.monotonic()
        status, ack = self.call("/device/poll", {"state": self.state, "wait": True,
                                                 "result": {"id": reply["id"], "payload": {"ok": True}}},
                                self.phone, device=True)
        self.assertEqual((status, ack), (200, {"wait": True}), "the receipt poll returns at once, marked for re-polling")
        receipt.join(3)
        self.assertEqual(waiting[0], (200, {"result": {"ok": True}}))
        self.assertLess(time.monotonic() - answered, 1.0)

    def test_idle_long_poll_times_out_with_wait_flag(self):
        import time
        started = time.monotonic()
        status, reply = self.call("/device/poll", {"state": self.state, "wait": True}, self.phone, device=True)
        self.assertEqual((status, reply), (200, {"wait": True}))
        self.assertGreaterEqual(time.monotonic() - started, server.POLL_HOLD - 0.1)
        status, reply = self.poll(self.phone)
        self.assertEqual((status, reply), (200, {}), "polls without wait still answer at once")

    def test_removed_device_is_released_from_long_poll(self):
        replies = []
        holder = threading.Thread(target=lambda: replies.append(
            self.call("/device/poll", {"state": self.state, "wait": True}, self.phone, device=True)))
        holder.start()
        import time
        time.sleep(0.3)
        self.call("/account/remove-device", {"id": self.phone["id"]}, self.desktop)
        holder.join(3)
        self.assertEqual(replies[0][0], 401)

    def test_unknown_command_is_rejected(self):
        self.poll(self.phone)
        status, _ = self.call("/api/control?device=" + self.phone["id"], {"op": "rm -rf"}, self.desktop)
        self.assertEqual(status, 400)

    def test_offline_device_cannot_be_controlled(self):
        status, body = self.call("/api/control?device=" + self.phone["id"], {"op": "toggle"}, self.desktop)
        self.assertEqual(status, 409)
        self.assertEqual(body["error"], "设备离线")

    def test_other_accounts_devices_are_invisible(self):
        self.poll(self.phone)
        bob = self.login("bob", "password2", "desktop", "Bob")
        _, body = self.call("/api/devices", session=bob)
        self.assertEqual([device["name"] for device in body["devices"]], ["Bob"])
        status, _ = self.call("/api/control?device=" + self.phone["id"], {"op": "toggle"}, bob)
        self.assertEqual(status, 404)
        status, _ = self.call("/api/state?device=" + self.phone["id"], session=bob)
        self.assertEqual(status, 404)

    def test_removed_device_must_sign_in_again(self):
        self.poll(self.phone)
        status, _ = self.call("/account/remove-device", {"id": self.phone["id"]}, self.desktop)
        self.assertEqual(status, 200)
        status, _ = self.poll(self.phone)
        self.assertEqual(status, 401)
        _, body = self.call("/api/devices", session=self.desktop)
        self.assertNotIn("Pixel", [device["name"] for device in body["devices"]])

    def test_last_seen_is_reported_in_seconds(self):
        self.poll(self.phone)
        _, body = self.call("/api/devices", session=self.desktop)
        devices = {device["name"]: device for device in body["devices"]}
        self.assertEqual(devices["Pixel"]["lastSeenAgo"], 0)
        self.assertIsNone(devices["Linux 桌面播放器"]["lastSeenAgo"], "never polled since the relay started")

    def test_sign_out_other_devices_keeps_this_one(self):
        tablet = self.login("alice", "password1", "android", "Tablet")
        bob = self.login("bob", "password2", "desktop", "Bob")
        self.poll(self.phone)
        status, _ = self.call("/account/logout-others", {}, self.desktop)
        self.assertEqual(status, 200)
        self.assertEqual(self.poll(self.phone)[0], 401)
        self.assertEqual(self.poll(tablet)[0], 401)
        _, body = self.call("/api/devices", session=self.desktop)
        self.assertEqual([device["name"] for device in body["devices"]], ["Linux 桌面播放器"])
        self.assertEqual(self.poll(self.desktop)[0], 200, "the requesting device stays signed in")
        self.assertEqual(self.call("/api/devices", session=bob)[0], 200, "other accounts are untouched")

    def test_local_cover_is_uploaded_once_and_linked(self):
        import base64
        jpeg = b"\xff\xd8\xff\xe0" + b"cover" * 10
        local = {**self.state, "queue": [{"id": -5, "title": "Local", "artist": "X", "cover": "", "kind": "local"}]}
        message = {"state": local, "artwork_key": "-5:100:200"}
        status, reply = self.call("/device/poll", message, self.phone, device=True)
        self.assertEqual(reply.get("artwork_needed"), "-5:100:200")
        upload = {**message, "artwork": {"key": "-5:100:200", "jpeg": base64.b64encode(jpeg).decode()}}
        status, reply = self.call("/device/poll", upload, self.phone, device=True)
        self.assertNotIn("artwork_needed", reply)
        _, body = self.call("/api/devices", session=self.desktop)
        cover = next(d for d in body["devices"] if d["name"] == "Pixel")["nowPlaying"]["cover"]
        self.assertRegex(cover, r"^https://relay\.example\.com/artwork/[A-Za-z0-9_-]{24}\.jpg$")
        _, state = self.call("/api/state?device=" + self.phone["id"], session=self.desktop)
        self.assertEqual(state["queue"][0]["cover"], cover)
        path = cover.removeprefix("https://relay.example.com")
        with urllib.request.urlopen(self.base + path) as response:
            self.assertEqual((response.headers["Content-Type"], response.read()), ("image/jpeg", jpeg))
        # The next track replaces the cover; the old link stops working.
        self.call("/device/poll", {"state": local, "artwork_key": "-6:1:1"}, self.phone, device=True)
        self.call("/device/poll", {"state": local, "artwork_key": "-6:1:1",
                                   "artwork": {"key": "-6:1:1", "none": True}}, self.phone, device=True)
        self.assertEqual(self.call(path)[0], 404)
        _, reply = self.call("/device/poll", {"state": local, "artwork_key": "-6:1:1"}, self.phone, device=True)
        self.assertNotIn("artwork_needed", reply, "a track without a cover is not asked for again")

    def test_cover_upload_rejects_non_jpeg(self):
        import base64
        local = {**self.state, "queue": [{"id": -5, "title": "Local", "cover": ""}]}
        self.call("/device/poll", {"state": local, "artwork_key": "k", "artwork": {
            "key": "k", "jpeg": base64.b64encode(b"<svg onload=alert(1)>").decode()}}, self.phone, device=True)
        _, state = self.call("/api/state?device=" + self.phone["id"], session=self.desktop)
        self.assertEqual(state["queue"][0]["cover"], "")

    def test_device_cannot_poll_as_another_device(self):
        status, _ = self.call("/device/poll", {"state": self.state},
                              {"key": self.desktop["key"], "id": self.phone["id"]}, device=True)
        self.assertEqual(status, 401)


if __name__ == "__main__":
    unittest.main()
