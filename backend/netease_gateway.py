"""NetEase catalogue and QR authorization gateway for Android."""
import json
import re
import threading
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

PATH_PREFIX = "/netease/"
MAX_BODY = 8 * 1024 * 1024
ENDPOINTS = {
    "cloudsearch": ({"keywords", "type"}, {"offset", "limit"}),
    "album": ({"id"}, set()),
    "artists": ({"id"}, set()),
    "artist/album": ({"id"}, {"offset", "limit"}),
    "playlist/track/all": ({"id"}, {"offset", "limit"}),
    "song/url/v1": ({"id", "level"}, set()),
    "lyric": ({"id"}, set()),
    "comment/music": ({"id"}, {"offset", "limit", "before"}),
    "song/red/count": ({"id"}, set()),
    "recommend/songs": (set(), set()),
    "recommend/resource": (set(), set()),
    "personalized": (set(), {"limit"}),
    "top/playlist": (set(), {"cat", "limit", "offset"}),
    "user/account": (set(), set()),
    "user/playlist": ({"uid"}, {"offset", "limit"}),
    "login/qr/start": (set(), set()),
    "login/qr/poll": (set(), set()),
}


class GatewayError(Exception):
    def __init__(self, status, message):
        self.status = status
        super().__init__(message)


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class NeteaseGateway:
    def __init__(self, base):
        self.base = base.rstrip("/")
        if self.base:
            url = urlsplit(self.base)
            if (not url.hostname or url.username is not None or url.password is not None
                    or url.query or url.fragment or (url.scheme != "https"
                    and not (url.scheme == "http" and url.hostname in {"127.0.0.1", "::1"}))):
                raise ValueError("netease_api_url must be an HTTPS or loopback HTTP base URL")
        self.slots = threading.BoundedSemaphore(4)

    def fetch(self, path, query, headers=None):
        endpoint = path.removeprefix(PATH_PREFIX)
        if endpoint not in ENDPOINTS:
            raise GatewayError(404, "网易云接口不存在")
        params = self.parse(endpoint, query)
        headers = headers or {}
        upstream_headers = {}
        if endpoint in {"user/account", "user/playlist"} and not headers.get("X-Netease-Session"):
            raise GatewayError(401, "请先登录网易云")
        if endpoint == "login/qr/poll":
            proof = headers.get("X-Netease-QR-Proof", "")
            if not re.fullmatch(r"[A-Za-z0-9_-]{8,128}\.[0-9]{13}\.[A-Za-z0-9_-]{43}", proof):
                raise GatewayError(400, "网易云登录凭据无效")
            upstream_headers["X-Netease-QR-Proof"] = proof
        session_endpoints = {"song/url/v1", "recommend/songs", "recommend/resource",
                             "user/account", "user/playlist", "playlist/track/all"}
        if endpoint in session_endpoints and headers.get("X-Netease-Session"):
            session = headers["X-Netease-Session"]
            if not re.fullmatch(r"[A-Za-z0-9._~%+-]{16,4096}", session):
                raise GatewayError(400, "网易云会话格式无效")
            upstream_headers["X-Netease-Session"] = session
        if not self.base:
            raise GatewayError(503, "网易云服务暂未启用")
        if not self.slots.acquire(blocking=False):
            raise GatewayError(503, "网易云服务繁忙，请稍后重试")
        try:
            return self.request(endpoint, params, upstream_headers)
        finally:
            self.slots.release()

    @staticmethod
    def parse(endpoint, query):
        required, optional = ENDPOINTS[endpoint]
        try:
            values = parse_qs(query, keep_blank_values=True, strict_parsing=True,
                              encoding="utf-8", errors="strict", max_num_fields=4) if query else {}
            if not required <= values.keys() <= (required | optional) or any(len(v) != 1 for v in values.values()):
                raise ValueError()
            params = {key: items[0] for key, items in values.items()}
            for key in ("id", "uid"):
                if key in params and (not re.fullmatch(r"[1-9][0-9]{0,15}", params[key])
                                      or int(params[key]) >= 2**52):
                    raise ValueError()
            if "cat" in params and params["cat"] not in {"全部", "华语", "欧美", "流行", "摇滚", "民谣", "电子", "轻音乐"}:
                raise ValueError()
            if "keywords" in params and not 1 <= len(params["keywords"].strip()) <= 160:
                raise ValueError()
            if "type" in params and params["type"] not in {"1", "10", "100", "1000"}:
                raise ValueError()
            if "level" in params and params["level"] not in {"standard", "exhigh", "lossless", "hires"}:
                raise ValueError()
            for key, minimum, maximum in (("limit", 1, 50), ("offset", 0, 10000)):
                if key in params and (not re.fullmatch(r"[0-9]{1,5}", params[key])
                                      or not minimum <= int(params[key]) <= maximum):
                    raise ValueError()
            if "before" in params and not re.fullmatch(r"[1-9][0-9]{0,12}", params["before"]):
                raise ValueError()
            return params
        except ValueError as error:
            raise GatewayError(400, "网易云请求参数无效") from error

    def request(self, endpoint, params, upstream_headers):
        url = f"{self.base}/{endpoint}?{urlencode(params)}"
        request = Request(url, headers={"Accept": "application/json", **upstream_headers})
        try:
            with build_opener(NoRedirects()).open(request, timeout=8) as response:
                body = response.read(MAX_BODY + 1)
            if len(body) > MAX_BODY:
                raise ValueError()
            data = json.loads(body)
            if not isinstance(data, dict):
                raise ValueError()
            # Keep upstream account or internal API metadata out of the public response.
            keys = {
                "cloudsearch": ("result",),
                "album": ("album", "songs"),
                "artists": ("artist", "hotSongs"),
                "artist/album": ("hotAlbums",),
                "playlist/track/all": ("songs",),
                "song/url/v1": ("data",),
                "lyric": ("lrc",),
                "comment/music": ("comments", "hotComments", "more", "total"),
                "song/red/count": ("data",),
                "recommend/songs": ("data",),
                "recommend/resource": ("recommend",),
                "personalized": ("result",),
                "top/playlist": ("playlists", "more"),
                "user/account": ("account",),
                "user/playlist": ("playlist", "more"),
                "login/qr/start": ("proof", "qr"),
                "login/qr/poll": ("session",),
            }[endpoint]
            if endpoint == "user/account":
                account = data.get("account")
                data["account"] = {"id": account.get("id")} if isinstance(account, dict) else None
            if endpoint == "comment/music":
                for key in ("comments", "hotComments"):
                    if isinstance(data.get(key), list):
                        data[key] = [
                            {
                                "commentId": comment.get("commentId"),
                                "content": comment.get("content"),
                                "likedCount": comment.get("likedCount"),
                                "time": comment.get("time"),
                                "user": {"nickname": (comment.get("user") or {}).get("nickname")},
                            }
                            for comment in data[key] if isinstance(comment, dict)
                        ]
            code = data.get("code", 200)
            return {"code": code, **{key: data[key] for key in keys if key in data and code in (200, 803)}}
        except HTTPError as error:
            error.close()
            raise GatewayError(502, "网易云上游服务暂不可用") from error
        except (URLError, OSError, ValueError) as error:
            raise GatewayError(502, "网易云上游服务暂不可用") from error
