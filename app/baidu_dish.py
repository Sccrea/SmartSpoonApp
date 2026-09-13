"""百度 AI 开放平台「菜品识别」客户端（由 test.py 重构而来，增加 token 缓存与错误处理）。"""

from __future__ import annotations

import base64
import threading
import time
from typing import Any, Dict, List

import requests

TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
DISH_URL = "https://aip.baidubce.com/rest/2.0/image-classify/v2/dish"

# 单张图片最大 4MB（百度接口限制）
MAX_IMAGE_BYTES = 4 * 1024 * 1024
# token 提前 60 秒过期
TOKEN_SAFETY_WINDOW = 60


class BaiduError(RuntimeError):
    """百度接口返回的业务错误。"""

    def __init__(self, code: Any, message: str, raw: Dict[str, Any] | None = None) -> None:
        super().__init__(f"[{code}] {message}")
        self.code = code
        self.message = message
        self.raw = raw or {}


class DishRecognizer:
    """线程安全的菜品识别客户端，内部缓存 access_token。"""

    def __init__(
        self,
        api_key: str,
        secret_key: str,
        top_num: int = 5,
        timeout: int = 15,
        session: requests.Session | None = None,
    ) -> None:
        if not api_key or not secret_key:
            raise ValueError("缺少百度 AI 的 API_KEY / SECRET_KEY 配置")
        self.api_key = api_key
        self.secret_key = secret_key
        self.top_num = max(1, min(int(top_num), 10))
        self.timeout = timeout
        self.session = session or requests.Session()
        self._token: str | None = None
        self._token_expire_at: float = 0.0
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ token
    def get_access_token(self, force: bool = False) -> str:
        with self._lock:
            now = time.time()
            if not force and self._token and now < self._token_expire_at:
                return self._token

            response = self.session.post(
                TOKEN_URL,
                params={
                    "grant_type": "client_credentials",
                    "client_id": self.api_key,
                    "client_secret": self.secret_key,
                },
                timeout=self.timeout,
            )
            payload = self._parse_json(response, "获取 access_token 失败")

            if "access_token" not in payload:
                raise BaiduError(
                    payload.get("error", "token_error"),
                    payload.get("error_description", "获取 access_token 失败"),
                    payload,
                )

            expires_in = int(payload.get("expires_in", 2592000))
            self._token = payload["access_token"]
            self._token_expire_at = now + max(expires_in - TOKEN_SAFETY_WINDOW, 60)
            return self._token

    # -------------------------------------------------------------- recognise
    def recognize_bytes(self, image_bytes: bytes) -> Dict[str, Any]:
        """识别内存中的图片字节，返回归一化结果。"""
        if not image_bytes:
            raise BaiduError("empty_image", "图片内容为空")
        if len(image_bytes) > MAX_IMAGE_BYTES:
            raise BaiduError("image_too_large", "图片超过 4MB，请压缩后重试")

        token = self.get_access_token()
        image_base64 = base64.b64encode(image_bytes).decode("utf-8")

        payload = self._request_dish(token, image_base64)
        # token 失效时刷新一次并重试
        if self._is_token_invalid(payload):
            payload = self._request_dish(self.get_access_token(force=True), image_base64)

        if "result" not in payload:
            raise BaiduError(
                payload.get("error_code", "unknown"),
                payload.get("error_msg", "识别失败"),
                payload,
            )

        return self.normalize(payload)

    def recognize_file(self, image_path) -> Dict[str, Any]:
        with open(image_path, "rb") as handle:
            return self.recognize_bytes(handle.read())

    # ----------------------------------------------------------------- helpers
    def _request_dish(self, token: str, image_base64: str) -> Dict[str, Any]:
        response = self.session.post(
            DISH_URL,
            params={"access_token": token},
            data={"image": image_base64, "top_num": self.top_num},
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            timeout=self.timeout,
        )
        return self._parse_json(response, "菜品识别请求失败")

    @staticmethod
    def _parse_json(response: requests.Response, hint: str) -> Dict[str, Any]:
        try:
            return response.json()
        except ValueError as exc:  # 非 JSON 响应
            raise BaiduError(
                response.status_code,
                f"{hint}（HTTP {response.status_code}）",
            ) from exc

    @staticmethod
    def _is_token_invalid(payload: Dict[str, Any]) -> bool:
        return payload.get("error_code") in (110, 111)

    def normalize(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """把百度原始响应整理成前端友好的结构。"""
        items: List[Dict[str, Any]] = []
        for rank, item in enumerate(payload.get("result", []), start=1):
            probability = self._to_float(item.get("probability"))
            calorie = self._to_float(item.get("calorie"))
            items.append(
                {
                    "rank": rank,
                    "name": item.get("name", "未知菜品"),
                    "probability": probability,
                    "percent": round(probability * 100, 1) if probability is not None else None,
                    "calorie": calorie,
                    "has_calorie": bool(item.get("has_calorie")),
                }
            )

        return {
            "items": items,
            "top": items[0] if items else None,
            "count": len(items),
            "raw": payload,
        }

    @staticmethod
    def _to_float(value: Any) -> float | None:
        if value in (None, ""):
            return None
        try:
            return float(value)
        except (TypeError, ValueError):
            return None
