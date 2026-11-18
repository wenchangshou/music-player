"""Persistent player accounts and per-installation sessions."""
import argparse
import getpass
import hashlib
import hmac
import os
import re
import secrets
import sqlite3
import threading
import time
from collections import deque
from contextlib import closing

NAME = re.compile(r"[a-zA-Z0-9_.-]{1,48}")
SESSION_AGE = 90 * 86400


def password_hash(password, salt):
    return hashlib.scrypt(password.encode(), salt=salt, n=16384, r=8, p=1)


class Accounts:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        self.attempts = deque()
        self.slots = threading.BoundedSemaphore(4)
        with closing(self.connect()) as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS users (
                    username TEXT PRIMARY KEY, salt BLOB NOT NULL, digest BLOB NOT NULL
                );
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY, username TEXT NOT NULL REFERENCES users(username),
                    digest BLOB NOT NULL UNIQUE, platform TEXT NOT NULL, name TEXT NOT NULL,
                    expires INTEGER NOT NULL
                );
            """)

    def connect(self):
        db = sqlite3.connect(self.path)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        return db

    def create(self, username, password):
        if not NAME.fullmatch(username) or not 7 <= len(password) <= 256:
            raise ValueError("用户名或密码格式无效")
        salt = secrets.token_bytes(16)
        with closing(self.connect()) as db, db:
            db.execute("INSERT INTO users VALUES (?, ?, ?)",
                       (username.lower(), salt, password_hash(password, salt)))

    def login(self, data, address):
        username, password, platform, name = (data.get(field) for field in
                                               ("username", "password", "platform", "name"))
        if not (isinstance(username, str) and NAME.fullmatch(username)
                and isinstance(password, str) and 1 <= len(password) <= 256
                and platform in ("desktop", "android", "web")
                and isinstance(name, str) and 1 <= len(name.strip()) <= 80):
            raise ValueError("登录资料无效")
        username = username.lower()
        now = time.monotonic()
        with self.lock:
            while self.attempts and self.attempts[0][0] <= now - 60:
                self.attempts.popleft()
            if (len(self.attempts) >= 60
                    or sum(1 for _, user, ip in self.attempts if user == username or ip == address) >= 10):
                raise BlockingIOError("登录尝试过多，请稍后再试")
            self.attempts.append((now, username, address))
        if not self.slots.acquire(blocking=False):
            raise BlockingIOError("登录请求过多，请稍后再试")
        try:
            with closing(self.connect()) as db:
                user = db.execute("SELECT salt, digest FROM users WHERE username=?", (username,)).fetchone()
            candidate = password_hash(password, user["salt"] if user else bytes(16))
            if user is None or not hmac.compare_digest(candidate, user["digest"]):
                raise PermissionError("用户名或密码错误")
        finally:
            self.slots.release()
        identifier = secrets.token_hex(16)
        key = secrets.token_urlsafe(32)
        expiry = int(time.time()) + SESSION_AGE
        with closing(self.connect()) as db, db:
            db.execute("DELETE FROM sessions WHERE expires<=?", (int(time.time()),))
            db.execute("INSERT INTO sessions VALUES (?, ?, ?, ?, ?, ?)",
                       (identifier, username, hashlib.sha256(key.encode()).digest(),
                        platform, name.strip(), expiry))
        return {"username": username, "id": identifier, "key": key}

    def session(self, auth):
        if not re.fullmatch(r"Bearer [A-Za-z0-9_-]{43}", auth):
            return None
        digest = hashlib.sha256(auth[7:].encode()).digest()
        with closing(self.connect()) as db:
            return db.execute("SELECT id, username, platform, name FROM sessions "
                              "WHERE digest=? AND expires>?", (digest, int(time.time()))).fetchone()

    def devices(self, username):
        with closing(self.connect()) as db:
            return db.execute("SELECT id, username, platform, name FROM sessions "
                              "WHERE username=? AND platform!='web' AND expires>? ORDER BY name,id",
                              (username, int(time.time()))).fetchall()

    def revoke(self, username, identifier):
        with closing(self.connect()) as db, db:
            return db.execute("DELETE FROM sessions WHERE username=? AND id=?",
                              (username, identifier)).rowcount > 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Create a player account")
    parser.add_argument("database")
    parser.add_argument("username")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        Accounts(args.database).create(args.username, getpass.getpass("Password: "))
    except (ValueError, sqlite3.IntegrityError) as error:
        parser.exit(1, f"Could not create account: {error}\n")
