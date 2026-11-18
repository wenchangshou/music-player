"""Print a capability-specific TIDAL access token as JSON.

TIDAL only serves Hi-Res streams to certain client profiles. tiddl (PR #427) derives such a
token from the normal login's refresh token. We reuse tiddl's own broker so a rotated refresh
token is persisted to auth.json exactly the way tiddl does it.

Usage: python playback_token.py [hires|atmos|--android] (set TIDDL_PATH to the tiddl config)
"""
import json
import sys

from tiddl.cli.utils.auth.core import load_auth_data, save_auth_data
from tiddl.core.auth import ProfileTokenManager


def persist(refresh_token: str) -> None:
    data = load_auth_data()
    data.refresh_token = refresh_token
    save_auth_data(auth_data=data)


def main() -> None:
    export_android = sys.argv[1:] == ["--android"]
    profile = "hires" if export_android or len(sys.argv) == 1 else sys.argv[1]
    auth = load_auth_data()
    if not auth.refresh_token:
        sys.exit("refresh token missing; run `tiddl auth login`")
    token = ProfileTokenManager(refresh_token=auth.refresh_token, on_refresh_token=persist).get_token(profile)
    if export_android:
        if not auth.token or not auth.user_id:
            sys.exit("main session missing; run `tiddl auth login`")
        print(json.dumps({
            "token": auth.token,
            "user_id": auth.user_id,
            "country_code": auth.country_code,
            "expires_at": auth.expires_at,
            "hires_token": token.access_token,
            "hires_expires_at": int(token.expires_at),
        }))
    else:
        print(json.dumps({"access_token": token.access_token, "expires_at": int(token.expires_at)}))


if __name__ == "__main__":
    main()
