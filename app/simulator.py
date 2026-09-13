"""服务器端的「智味勺」模拟器。

没有真实蓝牙设备，所以勺子的读数由服务器模拟：Web 控制台可以修改当前勺中食物/重量/热量、
推送一口、开关自动模拟；App 在用餐过程中轮询这里的数据并记录。
"""

from __future__ import annotations

import random
import threading
import time
from datetime import datetime
from typing import Any, Dict, List

LOCK = threading.RLock()


class SpoonSimulator:
    def __init__(self) -> None:
        self._state: Dict[str, Any] = {}
        self._seq = 0
        self._auto = False
        self._interval = 8.0
        self._thread: threading.Thread | None = None
        self.reset()

    # ------------------------------------------------------------------ 状态
    def reset(self) -> Dict[str, Any]:
        with LOCK:
            self._state = {
                "connected": True,
                "device": "张三的智味勺",
                "battery": 60,
                "recording": False,
                "food": "孜然羊肉",
                "weight": 0.0,
                "energy": 0.0,
                "bites": [],
                "auto": self._auto,
                "interval": self._interval,
                "updated": datetime.now().isoformat(timespec="seconds"),
            }
            self._bump()
            return self.snapshot()

    def snapshot(self, since: int = 0) -> Dict[str, Any]:
        with LOCK:
            state = dict(self._state)
            bites: List[Dict[str, Any]] = state["bites"]
            state["seq"] = self._seq
            state["newBites"] = [b for b in bites if b["seq"] > since]
            state["biteCount"] = len(bites)
            state["serverTime"] = datetime.now().isoformat(timespec="seconds")
            return state

    def _bump(self) -> None:
        self._seq += 1
        self._state["updated"] = datetime.now().isoformat(timespec="seconds")

    # ------------------------------------------------------------------ 修改
    def update(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        with LOCK:
            for key in ("connected", "recording"):
                if key in payload:
                    self._state[key] = bool(payload[key])
            if "device" in payload and payload["device"]:
                self._state["device"] = str(payload["device"])
            if "battery" in payload:
                try:
                    self._state["battery"] = max(0, min(100, int(payload["battery"])))
                except (TypeError, ValueError):
                    pass
            if "food" in payload and payload["food"]:
                self._state["food"] = str(payload["food"])
            for key in ("weight", "energy"):
                if key in payload and payload[key] not in (None, ""):
                    try:
                        self._state[key] = round(float(payload[key]), 1)
                    except (TypeError, ValueError):
                        pass
            # 只给重量时按能量密度粗算热量（2.4 kJ/g），方便控制台只用滑块
            if "weight" in payload and "energy" not in payload:
                self._state["energy"] = round(float(self._state["weight"]) * 2.4, 1)
            if "interval" in payload:
                try:
                    self._interval = max(2.0, min(60.0, float(payload["interval"])))
                    self._state["interval"] = self._interval
                except (TypeError, ValueError):
                    pass
            if "auto" in payload:
                self.set_auto(bool(payload["auto"]))
            self._bump()
            return self.snapshot()

    def push_bite(self, food: str | None = None, weight: float | None = None,
                  energy: float | None = None) -> Dict[str, Any]:
        with LOCK:
            food = food or self._state["food"]
            if weight is None:
                weight = self._state["weight"] if self._state["weight"] > 0 else round(random.uniform(12, 45), 1)
            if energy is None:
                energy = round(float(weight) * 2.4, 1)
            bite = {
                "no": len(self._state["bites"]) + 1,
                "food": food,
                "weight": round(float(weight), 1),
                "energy": round(float(energy), 1),
                "at": datetime.now().isoformat(timespec="seconds"),
                "seq": self._seq + 1,
            }
            self._state["bites"].append(bite)
            self._state["weight"] = round(float(weight), 1)
            self._state["energy"] = round(float(energy), 1)
            self._bump()
            return bite

    def clear_bites(self) -> Dict[str, Any]:
        with LOCK:
            self._state["bites"] = []
            self._state["weight"] = 0.0
            self._state["energy"] = 0.0
            self._bump()
            return self.snapshot()

    # -------------------------------------------------------------- 自动模拟
    def set_auto(self, enabled: bool) -> None:
        with LOCK:
            self._auto = enabled
            self._state["auto"] = enabled
            if enabled and (self._thread is None or not self._thread.is_alive()):
                self._thread = threading.Thread(target=self._loop, name="spoon-auto", daemon=True)
                self._thread.start()
            self._bump()

    def _loop(self) -> None:
        while True:
            time.sleep(1.0)
            with LOCK:
                enabled = self._auto
                interval = self._interval
                recording = self._state["recording"]
                last = self._state["bites"][-1]["at"] if self._state["bites"] else None
            if not enabled or not recording:
                continue
            due = True
            if last:
                try:
                    due = (datetime.now() - datetime.fromisoformat(last)).total_seconds() >= interval
                except ValueError:
                    due = True
            if due:
                self.push_bite()


SIMULATOR = SpoonSimulator()
