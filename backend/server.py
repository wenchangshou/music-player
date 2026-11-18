#!/usr/bin/env python3
"""TIDAL GUI device relay. Run behind an HTTPS reverse proxy."""
import hmac
import json
import os
import re
import secrets
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from accounts import Accounts
from netease_gateway import GatewayError, NeteaseGateway, PATH_PREFIX
from tidal_auth import TidalBroker, TidalError
from tidal_browser import CALLBACK, TidalBrowser

ROOT = Path(__file__).resolve().parent
ASSETS = Path(os.environ.get("TIDAL_REMOTE_ASSETS", ROOT.parent / "android/app/src/main/assets/remote"))
ID = re.compile(r"[a-zA-Z0-9_-]{1,64}\Z")
COMMANDS = {"library", "search", "add", "toggle", "next", "previous", "seek", "shuffle", "repeat", "play", "remove", "move"}
MAX_JSON = 1024 * 1024


class APIError(Exception):
    def __init__(self, status, message):
        self.status = status
        super().__init__(message)


class Device:
    def __init__(self, identifier, config):
        self.id = identifier
        self.name = config["name"]
        self.platform = config["platform"]
        self.token = config.get("token")
        self.owner = config.get("username")
        self.revoked = False
        self.last_seen = None
        self.state = {}
        self.pending = []
        self.inflight = {}
        self.completed = {}
        self.pairing = None


class Relay:
    def __init__(self, config):
        self.admin = config["admin_token"]
        self.devices = {key: Device(key, value) for key, value in config["devices"].items()}
        keys = [self.admin, *(item.token for item in self.devices.values())]
        if len(set(keys)) != len(keys) or any(not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", key) for key in keys):
            raise ValueError("Use a different random URL-safe token of at least 32 characters for each role")
        if any(not ID.fullmatch(key) or not item.name or len(item.name) > 80
               or item.platform not in ("android", "desktop") for key, item in self.devices.items()):
            raise ValueError("Invalid device id, label or platform")
        self.origin = config["origin"].rstrip("/")
        if not re.fullmatch(r"https://[A-Za-z0-9.\-]+(?::[0-9]+)?", self.origin):
            raise ValueError("origin must be the public HTTPS origin")
        self.releases = Path(config["releases"])
        self.lock = threading.Lock()
        self.desktop_sessions = {}
        self.account_devices = {}
        account_db = config.get("account_db") or os.environ.get("TIDAL_ACCOUNT_DB")
        self.accounts = Accounts(account_db) if account_db else None
        self.netease = NeteaseGateway(config.get("netease_api_url", ""))
        self.tidal = TidalBroker(config.get("tidal_device"), config.get("tidal_hires"))
        self.browser = TidalBrowser(config.get("tidal_browser"), self.origin)

    def admin_access(self, auth):
        if not hmac.compare_digest(auth.encode(), ("Bearer " + self.admin).encode()):
            raise APIError(401, "后台管理密钥无效")

    def account_access(self, auth):
        session = self.accounts.session(auth) if self.accounts else None
        if session is None:
            raise APIError(401, "请登录播放器账号")
        return session

    def account_device(self, session):
        identifier = session["id"]
        with self.lock:
            if identifier not in self.account_devices:
                self.account_devices[identifier] = Device(identifier, dict(session))
            return self.account_devices[identifier]

    def revoke_account_device(self, username, identifier):
        with self.lock:
            if not self.accounts.revoke(username, identifier):
                raise APIError(404, "设备不存在")
            device = self.account_devices.pop(identifier, None)
            if device is not None:
                device.revoked = True
            published = self.desktop_sessions.get(username)
            if published and published[0].id == identifier:
                del self.desktop_sessions[username]

    def device_access(self, identifier, auth):
        entry = self.devices.get(identifier)
        if entry is None:
            session = self.account_access(auth)
            if session["id"] != identifier or session["platform"] == "web":
                raise APIError(401, "设备会话无效")
            return self.account_device(session)
        if not hmac.compare_digest(auth.encode(), ("Bearer " + entry.token).encode()):
            raise APIError(401, "设备标识或配对密钥无效")
        return entry

    @staticmethod
    def online(device):
        return device.last_seen is not None and time.monotonic() - device.last_seen < 12

    def list_devices(self, owner=None):
        devices = (list(self.devices.values()) if owner is None else
                   [self.account_device(session) for session in self.accounts.devices(owner)])
        with self.lock:
            return {"devices": [{"id": device.id, "name": device.name, "platform": device.platform,
                                 "online": self.online(device), "version": device.state.get("version", "")} for device in devices]}

    def issue_pairing(self, device):
        if device.platform != "android":
            raise APIError(400, "请选择 Android 设备")
        with self.lock:
            code = f"{secrets.randbelow(1000000):06d}"
            device.pairing = (code, time.monotonic() + 300, 5)
            return {"code": code, "expiresIn": 300}

    def exchange_pairing(self, device, code):
        with self.lock:
            active = device.pairing
            if device.platform != "android" or active is None or active[1] <= time.monotonic():
                raise APIError(401, "配对码无效或已过期")
            expected, expiry, attempts = active
            if not hmac.compare_digest(expected, code):
                device.pairing = (expected, expiry, attempts - 1) if attempts > 1 else None
                raise APIError(401, "配对码无效或已过期")
            device.pairing = None
            return {"key": device.token}

    def get(self, identifier):
        device = self.devices.get(identifier)
        if device is None:
            raise APIError(404, "设备不存在")
        return device

    def snapshot(self, device):
        with self.lock:
            if not self.online(device):
                raise APIError(409, "设备离线")
            return device.state

    def control(self, device, command):
        if command.get("op") not in COMMANDS:
            raise APIError(400, "未知遥控操作")
        with self.lock:
            if not self.online(device):
                raise APIError(409, "设备离线")
            now = time.monotonic()
            self.cleanup(device, now)
            if len(device.pending) + len(device.inflight) >= 16:
                raise APIError(429, "设备命令过多，请稍后重试")
            command_id = uuid.uuid4().hex
            device.pending.append((now + 12, command_id, command))
        return {"command": command_id}

    @staticmethod
    def cleanup(device, now):
        device.pending = [item for item in device.pending if item[0] > now]
        device.inflight = {key: expiry for key, expiry in device.inflight.items() if expiry > now}
        device.completed = {key: item for key, item in device.completed.items() if item[0] > now}

    def receipt(self, device, command):
        with self.lock:
            self.cleanup(device, time.monotonic())
            if command in device.completed:
                return {"result": device.completed[command][1]}
            if command in device.inflight or any(item[1] == command for item in device.pending):
                return {"pending": True}
            raise APIError(404, "命令已过期或设备未返回结果")

    def sync_session(self, device, message):
        now = int(time.time())
        owner = device.owner
        published = self.desktop_sessions.get(owner)
        if device.platform == "desktop" and "desktop_session" in message:
            session = message["desktop_session"]
            if session is None:
                if published and published[0] is device:
                    self.desktop_sessions.pop(owner, None)
            elif isinstance(session, dict):
                token, user, country, expires = (session.get(key) for key in
                                                  ("token", "user_id", "country_code", "expires_at"))
                if (isinstance(token, str) and 0 < len(token) <= 8192 and token.isascii()
                        and not any(character.isspace() for character in token)
                        and isinstance(user, str) and re.fullmatch(r"[0-9]{1,24}", user)
                        and isinstance(country, str) and re.fullmatch(r"[A-Za-z]{2}", country)
                        and type(expires) is int and expires > now):
                    payload = {"token": token, "user_id": user, "country_code": country, "expires_at": expires}
                    hires = session.get("hires_token")
                    hires_expiry = session.get("hires_expires_at")
                    if (owner and isinstance(hires, str) and 0 < len(hires) <= 8192 and hires.isascii()
                            and not any(character.isspace() for character in hires)
                            and type(hires_expiry) is int and hires_expiry > now):
                        payload.update(hires_token=hires, hires_expires_at=hires_expiry)
                    elif (owner and published and published[0] is device and published[1]["user_id"] == user
                          and published[1].get("hires_expires_at", 0) > now):
                        payload.update(hires_token=published[1]["hires_token"],
                                       hires_expires_at=published[1]["hires_expires_at"])
                    self.desktop_sessions[owner] = (device, payload)
        published = self.desktop_sessions.get(owner)
        if published and published[1]["expires_at"] <= now:
            self.desktop_sessions.pop(owner, None)
            published = None
        if device.platform == "android" and message.get("sync_desktop_session") is True:
            payload = dict(published[1]) if published else None
            if payload and payload.get("hires_expires_at", 0) <= now:
                payload.pop("hires_token", None)
                payload.pop("hires_expires_at", None)
            return {"desktop_session": payload}
        return {}

    def poll(self, device, message):
        state = message.get("state")
        if not isinstance(state, dict):
            raise APIError(400, "设备状态无效")
        with self.lock:
            if device.revoked:
                raise APIError(401, "设备已移除，请重新登录")
            now = time.monotonic()
            self.cleanup(device, now)
            device.last_seen = now
            device.state = state
            reply = self.sync_session(device, message)
            result = message.get("result")
            if isinstance(result, dict):
                identifier = result.get("id")
                if isinstance(identifier, str) and identifier in device.inflight:
                    del device.inflight[identifier]
                    device.completed[identifier] = (now + 30, result.get("payload", {}))
            # A receipt heartbeat only acknowledges the last command. The next heartbeat requests work.
            if result is not None or not device.pending:
                return reply
            _, identifier, command = device.pending.pop(0)
            device.inflight[identifier] = now + 60
            return {**reply, "id": identifier, "command": command}


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, relay):
        super().__init__(address, Handler)
        self.relay = relay
        self.slots = threading.BoundedSemaphore(32)

    def service_actions(self):
        self.relay.browser.expire()

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        self.request.settimeout(10)
        super().setup()

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.handle_api(False)

    def do_POST(self):
        self.handle_api(True)

    def payload(self, limit=MAX_JSON):
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 < length <= limit or self.headers.get("Transfer-Encoding") or self.headers.get_content_type() != "application/json":
            raise APIError(400, "请求格式无效")
        data = json.loads(self.rfile.read(length))
        if not isinstance(data, dict):
            raise APIError(400, "请求格式无效")
        return data

    def headers_for(self, mime, size, status=200):
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(size))
        if mime == "application/vnd.android.package-archive":
            self.send_header("Content-Disposition", 'attachment; filename="listening-room-release.apk"')
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'self'; connect-src 'self'; img-src 'self' https://resources.tidal.com; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")
        self.end_headers()

    def respond(self, body, mime="application/json; charset=utf-8", status=200):
        if isinstance(body, dict):
            body = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
        self.headers_for(mime, len(body), status)
        self.wfile.write(body)

    def handle_api(self, post):
        relay = self.server.relay
        parsed = urlsplit(self.path)
        path = parsed.path
        auth = self.headers.get("Authorization", "")
        try:
            origin = self.headers.get("Origin")
            if origin and origin != relay.origin:
                raise APIError(403, "请求来源无效")
            if not post and path == "/":
                html = (ASSETS / "index.html").read_bytes()
                html = html.replace(b'<html lang="zh-CN">', b'<html lang="zh-CN" data-central="1">')
                html = html.replace("局域网遥控".encode(), "多端遥控".encode())
                self.respond(html, "text/html; charset=utf-8")
                return
            if not post and path == "/room.webp":
                photo = ASSETS / "room.webp"
                if not photo.is_file():
                    photo = ASSETS.parent.parent / "res/drawable-nodpi/listening_room.webp"
                if photo.is_file():
                    self.respond(photo.read_bytes(), "image/webp")
                    return
            if not post and path == "/app.js":
                self.respond((ASSETS / "app.js").read_bytes(), "text/javascript; charset=utf-8")
                return
            if path == "/health" and not post:
                self.respond({"ok": True})
                return
            if not post and path.startswith(PATH_PREFIX):
                try:
                    self.respond(relay.netease.fetch(path, parsed.query, self.headers))
                except GatewayError as error:
                    raise APIError(error.status, str(error)) from error
                return
            if not post and path in ("/updates/android/latest.json", "/updates/android/apk"):
                self.release(path, parse_qs(parsed.query))
                return
            if path.startswith("/auth/tidal/browser/"):
                self.browser_auth(relay.browser, path, post, parse_qs(parsed.query))
                return
            if post and path in ("/auth/tidal/start", "/auth/tidal/poll", "/auth/tidal/refresh", "/auth/tidal/hires"):
                request = self.payload(8192)
                if path.endswith("/start"):
                    self.respond(relay.tidal.start())
                elif path.endswith("/poll"):
                    self.respond(relay.tidal.poll(request.get("device_code")))
                elif path.endswith("/hires"):
                    self.respond(relay.tidal.hires(request.get("refresh_token")))
                else:
                    self.respond(relay.tidal.refresh(request.get("refresh_token")))
                return
            if path.startswith("/account/"):
                if relay.accounts is None:
                    raise APIError(503, "播放器账号服务尚未启用")
                if post and path == "/account/login":
                    try:
                        result = relay.accounts.login(self.payload(4096), self.client_address[0])
                    except PermissionError as error:
                        raise APIError(401, str(error)) from error
                    except BlockingIOError as error:
                        raise APIError(429, str(error)) from error
                    self.respond(result)
                    return
                session = relay.account_access(auth)
                if not post and path == "/account/me":
                    self.respond({"username": session["username"], "id": session["id"]})
                    return
                if post and path in ("/account/logout", "/account/remove-device"):
                    identifier = session["id"]
                    if path == "/account/remove-device":
                        identifier = self.payload(1024).get("id")
                        if not isinstance(identifier, str) or not ID.fullmatch(identifier):
                            raise APIError(400, "设备标识无效")
                    relay.revoke_account_device(session["username"], identifier)
                    self.respond({})
                    return
                raise APIError(404, "接口不存在")
            if post and path == "/device/pair":
                request = self.payload(1024)
                identifier, code = request.get("device"), request.get("code")
                if not isinstance(identifier, str) or not isinstance(code, str) or not re.fullmatch(r"[0-9]{6}", code):
                    raise APIError(400, "请输入设备标识和六位配对码")
                self.respond(relay.exchange_pairing(relay.get(identifier), code))
                return
            if path.startswith("/device/"):
                identifier = self.headers.get("X-Device-ID", "")
                device = relay.device_access(identifier, auth)
                if post and path == "/device/poll":
                    self.respond(relay.poll(device, self.payload()))
                    return
                if not post and device.platform == "android" and path in ("/device/android/latest", "/device/android/apk"):
                    self.release(path)
                    return
                raise APIError(404, "接口不存在")
            if path.startswith("/api/"):
                account = None
                if not hmac.compare_digest(auth.encode(), ("Bearer " + relay.admin).encode()):
                    account = relay.account_access(auth)
                owner = account["username"] if account else None
                query = parse_qs(parsed.query)
                if not post and path == "/api/devices":
                    self.respond(relay.list_devices(owner))
                    return
                identifier = query.get("device", [""])[0]
                if account:
                    selected = next((item for item in relay.accounts.devices(owner)
                                     if item["id"] == identifier), None)
                    if selected is None:
                        raise APIError(404, "设备不存在")
                    device = relay.account_device(selected)
                else:
                    device = relay.get(identifier)
                if not post and path == "/api/state":
                    self.respond(relay.snapshot(device))
                    return
                if post and path == "/api/pairing" and not account:
                    self.respond(relay.issue_pairing(device))
                    return
                if post and path == "/api/control":
                    self.respond(relay.control(device, self.payload(4096)))
                    return
                if not post and path == "/api/receipt":
                    self.respond(relay.receipt(device, query.get("command", [""])[0]))
                    return
            raise APIError(404, "接口不存在")
        except TidalError as error:
            self.respond({"error": error.code, "message": str(error)}, status=error.status)
        except APIError as error:
            self.respond({"error": str(error)}, status=error.status)
        except (ValueError, TypeError):
            self.respond({"error": "请求格式无效"}, status=400)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass

    def browser_auth(self, browser, path, post, query):
        if post:
            request = self.payload(8192)
            if path == "/auth/tidal/browser/start":
                self.respond(browser.start())
                return
            if path == "/auth/tidal/browser/claim":
                self.respond(browser.claim(request.get("ticket")))
                return
            if path == "/auth/tidal/browser/refresh":
                self.respond(browser.refresh(request.get("refresh_token")))
                return
        elif path == CALLBACK:
            def one(key):
                values = query.get(key, [])
                return values[0] if len(values) == 1 else None
            success = browser.callback(one("state"), one("code"), bool(one("error")))
            self.send_response(303)
            self.send_header("Location", "/auth/tidal/browser/complete?result=" + ("ok" if success else "error"))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        elif path == "/auth/tidal/browser/complete":
            title = "授权已完成" if query.get("result") == ["ok"] else "授权未完成"
            detail = "请返回听音室继续。" if title == "授权已完成" else "请返回听音室查看原因或重新登录。"
            self.respond(("<!doctype html><html lang=zh-CN><meta charset=utf-8>"
                          "<meta name=viewport content='width=device-width,initial-scale=1'>"
                          "<title>听音室 · TIDAL</title><body style='background:#10161a;color:#eef6f3;"
                          "font-family:system-ui;padding:2rem'><main><h1>" + title + "</h1><p>"
                          + detail + "</p></main></body></html>").encode(), "text/html; charset=utf-8")
            return
        raise APIError(404, "接口不存在")

    def release(self, path, query=None):
        directory = self.server.relay.releases
        try:
            manifest = json.loads((directory / "latest.json").read_text())
            version = manifest["versionCode"]
            digest = manifest["sha256"]
            if type(version) is not int or version <= 0 or not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ValueError()
            apk = directory / f"tidal-{version}-{digest[:16]}.apk"
            size = apk.stat().st_size
        except (OSError, KeyError, TypeError, ValueError):
            raise APIError(404, "暂无 Android 更新")
        if path.endswith(("/latest", "/latest.json")):
            result = {**manifest, "size": size}
            if path.startswith("/updates/"):
                result.pop("signers", None)
            self.respond(result)
        else:
            if query and (query.get("version") != [str(version)] or query.get("sha256") != [digest]):
                raise APIError(409, "发布版本已更新，请重新检查")
            with apk.open("rb") as stream:
                self.headers_for("application/vnd.android.package-archive", size)
                import shutil
                shutil.copyfileobj(stream, self.wfile, 65536)


def main():
    credentials = os.environ.get("CREDENTIALS_DIRECTORY")
    default = f"{credentials}/relay.json" if credentials else "/etc/tidal-relay/config.json"
    path = Path(os.environ.get("TIDAL_RELAY_CONFIG") or default)
    config = json.loads(path.read_text())
    server = Server(("127.0.0.1", int(config.get("port", 8765))), Relay(config))
    server.serve_forever()


if __name__ == "__main__":
    main()
