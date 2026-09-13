"""菜品信息的服务器端存储。

首次运行用 `app/data.py` 里的示例数据初始化，之后读写 `data/dishes.json`，
所以应用（Android 端）添加/修改的菜品在服务器重启后依然存在。
"""

from __future__ import annotations

import json
import threading
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

from . import data as sample

BASE_DIR = Path(__file__).resolve().parent.parent
DEFAULT_PATH = BASE_DIR / "data" / "dishes.json"


class DishStore:
    """线程安全的菜品库（含菜单），落盘为 JSON。"""

    def __init__(self, path: Path | None = None) -> None:
        self.path = Path(path) if path else DEFAULT_PATH
        # 可重入锁：upsert_food 内部会再调用 next_food_id()
        self._lock = threading.RLock()
        self._payload = self._read()

    # ------------------------------------------------------------------ 读写
    def _seed(self) -> Dict[str, Any]:
        return {
            "foods": [dict(food) for food in sample.FOODS],
            "menus": [dict(menu) for menu in sample.MENUS],
            "favoriteTimes": dict(sample.FAVORITE_TIMES),
            "updated": datetime.now().isoformat(timespec="seconds"),
        }

    def _read(self) -> Dict[str, Any]:
        try:
            with self.path.open("r", encoding="utf-8") as handle:
                payload = json.load(handle)
        except (OSError, ValueError):
            payload = self._seed()
            self._write(payload)
            return payload
        if not isinstance(payload, dict) or "foods" not in payload:
            payload = self._seed()
            self._write(payload)
        payload.setdefault("menus", [dict(menu) for menu in sample.MENUS])
        payload.setdefault("favoriteTimes", dict(sample.FAVORITE_TIMES))
        return payload

    def _write(self, payload: Dict[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload["updated"] = datetime.now().isoformat(timespec="seconds")
        tmp = self.path.with_suffix(".json.tmp")
        with tmp.open("w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
        tmp.replace(self.path)

    # ------------------------------------------------------------------ 查询
    def snapshot(self) -> Dict[str, Any]:
        with self._lock:
            return json.loads(json.dumps(self._payload, ensure_ascii=False))

    def foods(self) -> List[Dict[str, Any]]:
        return self.snapshot()["foods"]

    def menus(self) -> List[Dict[str, Any]]:
        return self.snapshot()["menus"]

    def next_food_id(self) -> str:
        with self._lock:
            used = {food.get("id") for food in self._payload["foods"]}
        index = 1
        while f"f{index:02d}" in used:
            index += 1
        return f"f{index:02d}"

    # ------------------------------------------------------------------ 写入
    def upsert_food(self, food: Dict[str, Any]) -> Dict[str, Any]:
        """按 id 或名称新增/更新一道菜，返回入库后的记录。"""
        with self._lock:
            foods = self._payload["foods"]
            name = str(food.get("name") or "").strip()
            if not name:
                raise ValueError("菜品名称不能为空")

            existing = None
            food_id = str(food.get("id") or "").strip()
            if food_id:
                existing = next((item for item in foods if item.get("id") == food_id), None)
            if existing is None:
                existing = next((item for item in foods if item.get("name") == name), None)

            record = existing if existing is not None else {"id": food_id or self.next_food_id()}
            record["name"] = name
            record["category"] = str(food.get("category") or record.get("category") or "其他")
            for key, caster in (("density", float), ("times", int)):
                if food.get(key) not in (None, ""):
                    try:
                        record[key] = caster(food[key])
                    except (TypeError, ValueError):
                        pass
            record.setdefault("density", 0.0)
            record.setdefault("times", 0)
            if food.get("img"):
                record["img"] = food["img"]
            record.setdefault("img", "congee.svg")

            if existing is None:
                foods.append(record)
            self._write(self._payload)
            return dict(record)

    def delete_food(self, food_id: str) -> bool:
        with self._lock:
            before = len(self._payload["foods"])
            self._payload["foods"] = [
                food for food in self._payload["foods"] if food.get("id") != food_id
            ]
            changed = len(self._payload["foods"]) != before
            for menu in self._payload["menus"]:
                menu["foods"] = [fid for fid in menu.get("foods", []) if fid != food_id]
            if changed:
                self._write(self._payload)
            return changed

    def bump_times(self, food_id: str, delta: int = 1) -> Optional[Dict[str, Any]]:
        """记录一次食用（应用里「加入菜单/开始用餐」时调用）。"""
        with self._lock:
            for food in self._payload["foods"]:
                if food.get("id") == food_id:
                    food["times"] = int(food.get("times", 0)) + delta
                    self._write(self._payload)
                    return dict(food)
        return None
