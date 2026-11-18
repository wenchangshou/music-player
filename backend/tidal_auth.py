"""Device authorization broker. App secrets stay in the server configuration."""
import base64
import collections
import json
import re
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ORIGIN = "https://auth.tidal.com/v1/oauth2/"
SCOPE = "r_usr+w_usr+w_sub"
# Hi-Res stereo streams are only served to certain client profiles. Like tiddl, a token for
# that profile is derived from the refresh token of the normal device login.
HIRES_SCOPE = "r_usr+w_usr"
VERIFICATION_HOSTS = {"link.tidal.com", "login.tidal.com", "activate.tidal.com"}


class TidalError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class DeviceGrant:
    def __init__(self, code, expires, interval):
        self.code = code
        now = time.monotonic()
        self.deadline = now + expires
        self.next_poll = now + interval
        self.interval = interval
        self.in_progress = False


class TidalBroker:
    def __init__(self, config, hires=None):
        self.client_id = config.get("client_id", "") if config else ""
        self.secret = config.get("client_secret", "") if config else ""
        if config and (not isinstance(self.client_id, str) or not isinstance(self.secret, str)
                       or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", self.client_id)
                       or len(self.secret) > 512):
            raise ValueError("TIDAL device client configuration is invalid")
        self.hires_id = hires.get("client_id", "") if hires else ""
        self.hires_secret = hires.get("client_secret", "") if hires else ""
        if hires and (not isinstance(self.hires_id, str) or not isinstance(self.hires_secret, str)
                      or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", self.hires_id)
                      or not 0 < len(self.hires_secret) <= 512):
            raise ValueError("TIDAL Hi-Res client configuration is invalid")
        self.lock = threading.Lock()
        self.grants = {}
        self.starts = collections.deque()
        self.refreshes = collections.deque()
        self.opener = urllib.request.build_opener(NoRedirect())

    @staticmethod
    def _limit(bucket, limit, now):
        while bucket and now - bucket[0] >= 60:
            bucket.popleft()
        if len(bucket) >= limit:
            raise TidalError(429, "rate_limited", "设备授权请求过多，请稍后重试")
        bucket.append(now)

    def _configured(self):
        if not self.client_id:
            raise TidalError(503, "client_unconfigured", "后台尚未配置获准的 TIDAL 设备客户端")

    def _post(self, path, fields, authenticated=False, configured=True):
        if configured:
            self._configured()
        headers = {"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"}
        if authenticated and self.secret:
            raw = f"{self.client_id}:{self.secret}".encode()
            headers["Authorization"] = "Basic " + base64.b64encode(raw).decode("ascii")
        request = urllib.request.Request(ORIGIN + path, headers=headers, method="POST",
                                        data=urllib.parse.urlencode(fields).encode("ascii"))
        try:
            with self.opener.open(request, timeout=12) as response:
                data = json.loads(response.read(65536))
            if not isinstance(data, dict):
                raise ValueError()
            return data
        except urllib.error.HTTPError as error:
            try:
                with error:
                    data = json.loads(error.read(16384))
                code = data.get("error") if isinstance(data, dict) else None
            except (ValueError, OSError):
                code = None
            if isinstance(code, str) and code in {"authorization_pending", "slow_down", "expired_token", "access_denied", "invalid_grant"}:
                raise TidalError(400, code, "TIDAL 授权未完成或已失效") from None
            if error.code in (400, 401, 403):
                raise TidalError(502, "invalid_client", "TIDAL 未接受后台客户端授权，请检查客户端权限") from None
            raise TidalError(502, "upstream_error", "TIDAL 授权服务暂时不可用") from None
        except (OSError, ValueError):
            raise TidalError(503, "upstream_timeout", "TIDAL 授权服务暂时不可用") from None

    @staticmethod
    def _link(value):
        if not isinstance(value, str) or not value:
            return ""
        try:
            url = urllib.parse.urlsplit(value if "://" in value else "https://" + value)
            if url.scheme == "https" and url.netloc.lower() in VERIFICATION_HOSTS:
                return url.geturl()
        except ValueError:
            pass
        raise TidalError(502, "invalid_response", "TIDAL 返回了无效的授权网址")

    def start(self):
        self._configured()
        with self.lock:
            now = time.monotonic()
            self._limit(self.starts, 20, now)
            self.grants = {key: grant for key, grant in self.grants.items() if grant.deadline > now}
            if len(self.grants) >= 128:
                raise TidalError(429, "rate_limited", "设备授权请求过多，请稍后重试")
        data = self._post("device_authorization", {"client_id": self.client_id, "scope": SCOPE})
        device = data.get("device_code") or data.get("deviceCode")
        user = data.get("user_code") or data.get("userCode")
        page = self._link(data.get("verification_uri") or data.get("verificationUri"))
        direct = self._link(data.get("verification_uri_complete") or data.get("verificationUriComplete"))
        expires = data.get("expires_in", data.get("expiresIn"))
        interval = data.get("interval", 5)
        if (not isinstance(device, str) or not 0 < len(device) <= 4096 or not isinstance(user, str)
                or not 0 < len(user) <= 64 or not (page or direct) or type(expires) is not int
                or not 0 < expires <= 86400 or type(interval) is not int or not 0 < interval <= 3600):
            raise TidalError(502, "invalid_response", "TIDAL 返回了无效的设备授权信息")
        handle = secrets.token_urlsafe(32)
        with self.lock:
            if len(self.grants) >= 128:
                raise TidalError(429, "rate_limited", "设备授权请求过多，请稍后重试")
            self.grants[handle] = DeviceGrant(device, expires, interval)
        return {"device_code": handle, "user_code": user, "verification_uri": page or direct,
                "verification_uri_complete": direct, "expires_in": expires, "interval": interval}

    def poll(self, handle):
        self._configured()
        if not isinstance(handle, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", handle):
            raise TidalError(400, "expired_token", "设备授权已过期，请重新获取设备码")
        with self.lock:
            grant = self.grants.get(handle)
            now = time.monotonic()
            if grant is None or grant.deadline <= now:
                self.grants.pop(handle, None)
                raise TidalError(400, "expired_token", "设备授权已过期，请重新获取设备码")
            if grant.in_progress or grant.next_poll > now:
                raise TidalError(400, "authorization_pending", "等待 TIDAL 网页授权")
            grant.in_progress = True
            grant.next_poll = now + grant.interval
        try:
            token = self._post("token", {"client_id": self.client_id, "device_code": grant.code,
                                        "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                                        "scope": SCOPE}, authenticated=True)
            response = self._token(token, include_user=True)
        except TidalError as error:
            with self.lock:
                if error.code in {"slow_down", "upstream_timeout"}:
                    grant.interval = grant.interval + 5 if error.code == "slow_down" else grant.interval * 2
                    grant.next_poll = time.monotonic() + grant.interval
                elif error.code != "authorization_pending":
                    self.grants.pop(handle, None)
                grant.in_progress = False
            raise
        with self.lock:
            self.grants.pop(handle, None)
        return response

    def refresh(self, token):
        self._configured()
        if not isinstance(token, str) or not 0 < len(token) <= 4096:
            raise TidalError(400, "invalid_grant", "续期凭据无效")
        with self.lock:
            self._limit(self.refreshes, 60, time.monotonic())
        result = self._post("token", {"client_id": self.client_id, "refresh_token": token,
                                      "grant_type": "refresh_token", "scope": SCOPE}, authenticated=True)
        return self._token(result)

    def hires(self, token):
        """Exchange a device-login refresh token for a Hi-Res profile access token."""
        if not self.hires_id:
            raise TidalError(503, "hires_unconfigured", "后台尚未配置 Hi-Res 播放客户端")
        if not isinstance(token, str) or not 0 < len(token) <= 4096:
            raise TidalError(400, "invalid_grant", "续期凭据无效")
        with self.lock:
            self._limit(self.refreshes, 60, time.monotonic())
        result = self._post("token", {"client_id": self.hires_id, "client_secret": self.hires_secret,
                                      "refresh_token": token, "grant_type": "refresh_token",
                                      "scope": HIRES_SCOPE}, configured=False)
        return self._token(result)

    @staticmethod
    def _token(data, include_user=False):
        access = data.get("access_token")
        expires = data.get("expires_in")
        if not isinstance(access, str) or not access or type(expires) is not int or expires <= 0:
            raise TidalError(502, "invalid_response", "TIDAL 返回了无效的会话")
        result = {"access_token": access, "expires_in": expires}
        refresh = data.get("refresh_token")
        if isinstance(refresh, str) and refresh:
            result["refresh_token"] = refresh
        if include_user:
            user = data.get("user")
            if not isinstance(user, dict):
                raise TidalError(502, "invalid_response", "TIDAL 返回了无效的账号信息")
            identifier, country = str(user.get("userId", "")), user.get("countryCode")
            if not re.fullmatch(r"[0-9]+", identifier) or not isinstance(country, str) or not re.fullmatch(r"[A-Za-z]{2}", country):
                raise TidalError(502, "invalid_response", "TIDAL 返回了无效的账号信息")
            result["user"] = {"userId": identifier, "countryCode": country.upper()}
        return result
