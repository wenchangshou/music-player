"""Browser-based TIDAL authorization code flow with a private Android claim ticket."""
import base64
import hashlib
import json
import re
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request

from tidal_auth import TidalBroker, TidalError

LOGIN_URL = "https://login.tidal.com/authorize"
SESSION_URL = "https://api.tidal.com/v1/sessions"
CALLBACK = "/auth/tidal/browser/callback"
TICKET = re.compile(r"[A-Za-z0-9_-]{43}\Z")
TTL = 600


class BrowserGrant:
    def __init__(self):
        self.ticket = secrets.token_urlsafe(32)
        self.state = secrets.token_urlsafe(32)
        self.verifier = secrets.token_urlsafe(48)
        self.deadline = time.monotonic() + TTL
        self.result = None
        self.error = None


class TidalBrowser(TidalBroker):
    def __init__(self, config, origin):
        super().__init__(config)
        self.redirect = origin + CALLBACK
        self.scope = config.get("scope", "user.read collection.read collection.write playlists.read search.read") if config else ""
        if config and (not isinstance(self.scope, str)
                       or not re.fullmatch(r"[A-Za-z0-9_. +\-]{1,512}", self.scope)):
            raise ValueError("TIDAL browser authorization scopes are invalid")
        self.tickets = {}
        self.states = {}
        self.next_cleanup = 0

    def _configured(self):
        if not self.client_id:
            raise TidalError(503, "browser_unconfigured", "后台尚未配置获准的 TIDAL 浏览器登录客户端")

    def expire(self):
        now = time.monotonic()
        with self.lock:
            if now < self.next_cleanup:
                return
            self.next_cleanup = now + 30
            self.tickets = {key: grant for key, grant in self.tickets.items() if grant.deadline > now}
            self.states = {key: grant for key, grant in self.states.items() if grant.deadline > now}

    def start(self):
        self._configured()
        with self.lock:
            now = time.monotonic()
            self._limit(self.starts, 20, now)
            self.tickets = {key: grant for key, grant in self.tickets.items() if grant.deadline > now}
            self.states = {key: grant for key, grant in self.states.items() if grant.deadline > now}
            if len(self.tickets) >= 128:
                raise TidalError(429, "rate_limited", "登录请求过多，请稍后重试")
            grant = BrowserGrant()
            self.tickets[grant.ticket] = grant
            self.states[grant.state] = grant
        challenge = base64.urlsafe_b64encode(hashlib.sha256(grant.verifier.encode()).digest()).rstrip(b"=").decode()
        query = urllib.parse.urlencode({
            "response_type": "code", "client_id": self.client_id, "redirect_uri": self.redirect,
            "scope": self.scope, "code_challenge_method": "S256", "code_challenge": challenge,
            "state": grant.state,
        })
        return {"authorization_url": LOGIN_URL + "?" + query, "ticket": grant.ticket, "expires_in": TTL}

    def callback(self, state, code, denied):
        self._configured()
        if not isinstance(state, str) or not TICKET.fullmatch(state):
            return False
        with self.lock:
            grant = self.states.pop(state, None)
            if grant is None or grant.deadline <= time.monotonic():
                return False
        try:
            if denied or not isinstance(code, str) or not 0 < len(code) <= 4096:
                raise TidalError(400, "access_denied", "TIDAL 授权已取消")
            token = self._post("token", {
                "grant_type": "authorization_code", "client_id": self.client_id, "code": code,
                "redirect_uri": self.redirect, "code_verifier": grant.verifier,
            })
            result = self._token(token)
            result["user"] = self._account(result["access_token"])
        except TidalError as error:
            with self.lock:
                grant.error = error
            return False
        with self.lock:
            if grant.deadline <= time.monotonic():
                self.tickets.pop(grant.ticket, None)
                return False
            grant.result = result
        return True

    def claim(self, ticket):
        self._configured()
        if not isinstance(ticket, str) or not TICKET.fullmatch(ticket):
            raise TidalError(400, "expired_token", "浏览器授权已过期，请重新登录")
        with self.lock:
            grant = self.tickets.get(ticket)
            if grant is None or grant.deadline <= time.monotonic():
                self.tickets.pop(ticket, None)
                raise TidalError(400, "expired_token", "浏览器授权已过期，请重新登录")
            if grant.error is not None:
                del self.tickets[ticket]
                raise grant.error
            if grant.result is None:
                return {"pending": True}
            del self.tickets[ticket]
            return grant.result

    def refresh(self, refresh_token):
        self._configured()
        if not isinstance(refresh_token, str) or not 0 < len(refresh_token) <= 4096:
            raise TidalError(400, "invalid_grant", "续期凭据无效")
        with self.lock:
            self._limit(self.refreshes, 60, time.monotonic())
        result = self._post("token", {
            "grant_type": "refresh_token", "client_id": self.client_id, "refresh_token": refresh_token,
        })
        return self._token(result)

    def _account(self, token):
        request = urllib.request.Request(SESSION_URL, headers={"Authorization": "Bearer " + token,
                                                               "Accept": "application/json"})
        try:
            with self.opener.open(request, timeout=12) as response:
                profile = json.loads(response.read(65536))
        except urllib.error.HTTPError as error:
            if error.code in (401, 403):
                raise TidalError(403, "playback_access_required",
                                 "浏览器已授权，但该客户端没有播放器所需的 TIDAL 接口权限") from None
            raise TidalError(502, "upstream_error", "无法读取 TIDAL 账号信息") from None
        except (OSError, ValueError):
            raise TidalError(502, "upstream_error", "无法读取 TIDAL 账号信息") from None
        return self._token({"access_token": token, "expires_in": 1, "user": profile}, include_user=True)["user"]
