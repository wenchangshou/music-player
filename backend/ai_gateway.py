"""OpenAI-compatible chat proxy for signed-in web remotes.

The endpoint, model and API key come from the relay's own configuration, so browsers never see
the key and cannot point the relay at arbitrary hosts. Callers send only the conversation.
"""
import json
import os
import threading
import time
import urllib.error
import urllib.request

ROLES = {"system", "user", "assistant"}
MAX_MESSAGES = 32
MAX_CHARS = 48_000
MAX_TOKENS = 4096
HOURLY_LIMIT = 120
CONCURRENT = 4
TIMEOUT = 45


class AiError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def reply_text(data):
    """The assistant text of an OpenAI-compatible chat completion, or ''."""
    choice = (data.get("choices") or [{}])[0] if isinstance(data, dict) else {}
    message = choice.get("message") or {}
    content = message.get("content")
    if isinstance(content, list):
        content = "".join(part if isinstance(part, str) else str(part.get("text", "")) for part in content)
    text = content.strip() if isinstance(content, str) else ""
    if not text and isinstance(message.get("reasoning_content"), str):
        text = message["reasoning_content"].strip()
    if not text and isinstance(choice.get("text"), str):
        text = choice["text"].strip()
    return text


class AiGateway:
    def __init__(self, config=None):
        config = config or {}
        self.endpoint = (config.get("endpoint") or os.environ.get("TIDAL_AI_ENDPOINT", "")).strip().rstrip("/")
        self.key = (config.get("api_key") or os.environ.get("TIDAL_AI_KEY", "")).strip()
        self.model = (config.get("model") or os.environ.get("TIDAL_AI_MODEL", "")).strip()
        if self.endpoint and not self.endpoint.startswith("https://"):
            raise ValueError("AI endpoint must use HTTPS")
        if self.endpoint and not self.endpoint.endswith("/chat/completions"):
            self.endpoint += "/chat/completions"
        self.lock = threading.Lock()
        self.calls = {}
        self.slots = threading.BoundedSemaphore(CONCURRENT)

    @property
    def configured(self):
        return bool(self.endpoint and self.model)

    def status(self):
        return {"configured": self.configured, "managed": True, "model": self.model if self.configured else ""}

    def take(self, owner):
        now = time.monotonic()
        with self.lock:
            recent = [stamp for stamp in self.calls.get(owner, []) if now - stamp < 3600]
            if len(recent) >= HOURLY_LIMIT:
                self.calls[owner] = recent
                raise AiError(429, "AI 调用过于频繁，请稍后再试")
            recent.append(now)
            self.calls[owner] = recent

    @staticmethod
    def request_body(payload, model, endpoint):
        messages = payload.get("messages")
        if not isinstance(messages, list) or not 0 < len(messages) <= MAX_MESSAGES:
            raise AiError(400, "AI 请求内容无效")
        clean, total = [], 0
        for item in messages:
            if not isinstance(item, dict) or item.get("role") not in ROLES or not isinstance(item.get("content"), str):
                raise AiError(400, "AI 请求内容无效")
            total += len(item["content"])
            clean.append({"role": item["role"], "content": item["content"]})
        if total > MAX_CHARS:
            raise AiError(413, "AI 请求内容过长")
        body = {"model": model, "messages": clean}
        tokens = payload.get("max_tokens", 2048)
        body["max_tokens"] = min(MAX_TOKENS, tokens) if isinstance(tokens, int) and tokens > 0 else 2048
        temperature = payload.get("temperature")
        reasoner = any(mark in model for mark in ("reasoner", "r1", "o1", "o3"))
        if isinstance(temperature, (int, float)) and not isinstance(temperature, bool) and not reasoner:
            body["temperature"] = max(0.0, min(1.5, float(temperature)))
        if "volces.com" in endpoint and not reasoner:
            body["thinking"] = {"type": "disabled"}
        return body

    def chat(self, owner, payload):
        if not self.configured:
            raise AiError(503, "服务器未配置 AI 服务")
        body = self.request_body(payload, self.model, self.endpoint)
        self.take(owner)
        if not self.slots.acquire(blocking=False):
            raise AiError(429, "AI 服务繁忙，请稍后再试")
        try:
            headers = {"Content-Type": "application/json"}
            if self.key:
                headers["Authorization"] = "Bearer " + self.key
            request = urllib.request.Request(self.endpoint, data=json.dumps(body).encode(), headers=headers)
            try:
                with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
                    data = json.loads(response.read(2 * 1024 * 1024))
            except urllib.error.HTTPError as error:
                with error:
                    detail = ""
                    try:
                        parsed = json.loads(error.read(64 * 1024))
                        detail = (parsed.get("error") or {}).get("message", "") if isinstance(parsed, dict) else ""
                    except (ValueError, AttributeError):
                        pass
                raise AiError(502, f"AI 服务返回错误 ({error.code}) {detail}".strip()) from error
            except (OSError, ValueError) as error:
                raise AiError(502, "AI 服务暂时不可用") from error
        finally:
            self.slots.release()
        text = reply_text(data)
        if not text:
            raise AiError(502, "AI 返回内容为空")
        finish = ((data.get("choices") or [{}])[0] or {}).get("finish_reason", "")
        return {"content": text, "finish": finish if isinstance(finish, str) else ""}
