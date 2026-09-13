"""纯 JSON 接口：Android 应用在启动时读取菜品/菜单/记录数据，识别接口照旧。

网页端已废弃——这里不再渲染任何 HTML 页面。
"""

from __future__ import annotations

import base64
import uuid
from datetime import datetime
from pathlib import Path

from flask import (
    Blueprint,
    current_app,
    jsonify,
    request,
    send_from_directory,
)

from flask import render_template

from . import data as sample
from .baidu_dish import BaiduError, DishRecognizer
from .dishes import DishStore
from .simulator import SIMULATOR

main = Blueprint("api", __name__)

# 菜品库（落盘到 data/dishes.json），由 create_app 按配置初始化
STORE = DishStore()


def configure_store(path) -> None:
    """按配置的文件路径重建菜品库。"""
    global STORE
    STORE = DishStore(path)

# 内存中的识别历史（最新在前），足够原型演示使用
HISTORY: list = []
HISTORY_LIMIT = 30


# --------------------------------------------------------------------- 数据
def build_bootstrap() -> dict:
    """应用启动时一次性读取的全部数据。"""
    dishes = STORE.snapshot()
    return {
        "server": {
            "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "version": current_app.config.get("APP_VERSION", "1.0"),
        },
        "foods": dishes["foods"],
        "menus": dishes["menus"],
        "favoriteTimes": dishes["favoriteTimes"],
        "categories": sample.CATEGORIES,
        "folders": sample.FOLDERS,
        "records": sample.RECORDS,
        "stats": sample.STATS,
        "chart": sample.CHART,
        "devices": sample.DEVICES,
        "user": sample.USER,
        "live": sample.LIVE_DEMO,
        "result": sample.RESULT_DEMO,
        "preselect": ["f04", "f05", "f06", "f07"],
        "dishesUpdated": dishes.get("updated"),
    }


def _build_recognizer() -> DishRecognizer:
    return DishRecognizer(
        api_key=current_app.config["BAIDU_API_KEY"],
        secret_key=current_app.config["BAIDU_SECRET_KEY"],
        top_num=current_app.config["TOP_NUM"],
        timeout=current_app.config["REQUEST_TIMEOUT"],
    )


def _allowed(filename: str) -> bool:
    return (
        "." in filename
        and filename.rsplit(".", 1)[1].lower() in current_app.config["ALLOWED_EXTENSIONS"]
    )


def _save_upload(image_bytes: bytes, source_name: str):
    """把上传的图片存到 uploads/，失败时返回 None（不影响识别）。"""
    if not image_bytes:
        return None
    suffix = Path(source_name).suffix.lower() or ".jpg"
    target = current_app.config["UPLOAD_FOLDER"] / f"{uuid.uuid4().hex}{suffix}"
    try:
        target.write_bytes(image_bytes)
    except OSError:
        current_app.logger.warning("图片保存失败：%s", target)
        return None
    return target


def _extract_image():
    """从请求中取出图片字节。返回 (字节, 原始文件名, 错误信息)。"""
    storage = request.files.get("image")
    if storage is not None and storage.filename:
        if not _allowed(storage.filename):
            return None, storage.filename, "仅支持 jpg / jpeg / png / bmp / webp 图片"
        return storage.read(), storage.filename, None

    payload = request.get_json(silent=True) or {}
    data_url = payload.get("image_base64") or ""
    if not data_url:
        return None, "camera.jpg", "没有收到图片，请先拍照或选择图片"

    raw = data_url.split(",", 1)[1] if "," in data_url else data_url
    try:
        return base64.b64decode(raw, validate=False), "camera.jpg", None
    except Exception:  # noqa: BLE001 - base64 非法
        return None, "camera.jpg", "图片数据格式不正确"


# --------------------------------------------------------------------- 路由
@main.get("/")
def index():
    """没有网页端，根路径返回接口清单。"""
    return jsonify(
        {
            "service": "智味勺 L2 API",
            "note": "应用界面在 Android 端；这里提供数据接口，/spoon 是勺子模拟控制台",
            "endpoints": [
                "GET    /spoon                  勺子模拟控制台（网页）",
                "GET    /api/device/state       勺子模拟读数（App 轮询用，?since=<seq> 取新增的口）",
                "POST   /api/device/state       修改模拟读数 {connected, recording, food, weight, energy}",
                "POST   /api/device/bite        推送一口 {food, weight, energy}",
                "POST   /api/device/clear       清空已推送的口",
                "GET    /api/health",
                "GET    /api/bootstrap          应用启动时读取的全部数据",
                "GET    /api/dishes             菜品库 + 菜单",
                "POST   /api/dishes             新增/更新一道菜 {name, category, density, times, img}",
                "POST   /api/dishes/<id>/eat    记录一次食用（times +1）",
                "DELETE /api/dishes/<id>        删除一道菜",
                "POST   /api/recognize          上传图片识别菜品",
                "GET    /api/history            本次运行内的识别历史",
            ],
        }
    )


@main.get("/uploads/<path:filename>")
def uploaded_file(filename: str):
    return send_from_directory(current_app.config["UPLOAD_FOLDER"], filename)


# ------------------------------------------------- 智味勺模拟器（无真机时的数据源）
@main.get("/spoon")
def console():
    """勺子模拟控制台（网页）：修改读数、推送一口、开关自动模拟。

注意：路径不能叫 /console —— Werkzeug 调试器自己占用 /console。
"""
    return render_template("console.html")


@main.get("/api/device/state")
def device_state():
    since = request.args.get("since", "0")
    try:
        since_seq = int(since)
    except ValueError:
        since_seq = 0
    return jsonify({"ok": True, "state": SIMULATOR.snapshot(since_seq)})


@main.post("/api/device/state")
def device_update():
    payload = request.get_json(silent=True) or {}
    return jsonify({"ok": True, "state": SIMULATOR.update(payload)})


@main.post("/api/device/bite")
def device_bite():
    payload = request.get_json(silent=True) or {}
    weight = payload.get("weight")
    energy = payload.get("energy")
    bite = SIMULATOR.push_bite(
        food=payload.get("food"),
        weight=float(weight) if weight not in (None, "") else None,
        energy=float(energy) if energy not in (None, "") else None,
    )
    return jsonify({"ok": True, "bite": bite, "state": SIMULATOR.snapshot()})


@main.post("/api/device/clear")
def device_clear():
    return jsonify({"ok": True, "state": SIMULATOR.clear_bites()})


@main.post("/api/device/reset")
def device_reset():
    return jsonify({"ok": True, "state": SIMULATOR.reset()})


@main.get("/api/health")
def health():
    return jsonify(
        {
            "status": "ok",
            "service": "智味勺 L2",
            "time": datetime.now().isoformat(timespec="seconds"),
            "top_num": current_app.config["TOP_NUM"],
            "dishes": len(STORE.foods()),
            "dishesUpdated": STORE.snapshot().get("updated"),
        }
    )


@main.get("/api/bootstrap")
def api_bootstrap():
    return jsonify({"ok": True, "data": build_bootstrap()})


# ------------------------------------------------------------------ 菜品库
@main.get("/api/dishes")
def api_dishes():
    payload = STORE.snapshot()
    return jsonify(
        {
            "ok": True,
            "foods": payload["foods"],
            "menus": payload["menus"],
            "categories": sample.CATEGORIES,
            "updated": payload.get("updated"),
        }
    )


@main.post("/api/dishes")
def api_dish_save():
    food = request.get_json(silent=True)
    if not isinstance(food, dict):
        return jsonify({"ok": False, "error": "请求体必须是 JSON 对象"}), 400
    try:
        record = STORE.upsert_food(food)
    except ValueError as exc:
        return jsonify({"ok": False, "error": str(exc)}), 400
    return jsonify({"ok": True, "food": record, "count": len(STORE.foods())})


@main.delete("/api/dishes/<food_id>")
def api_dish_delete(food_id: str):
    removed = STORE.delete_food(food_id)
    if not removed:
        return jsonify({"ok": False, "error": "没有这道菜"}), 404
    return jsonify({"ok": True, "count": len(STORE.foods())})


@main.post("/api/dishes/<food_id>/eat")
def api_dish_eat(food_id: str):
    record = STORE.bump_times(food_id)
    if record is None:
        return jsonify({"ok": False, "error": "没有这道菜"}), 404
    return jsonify({"ok": True, "food": record})


# ------------------------------------------------------------------ 识别
@main.get("/api/history")
def api_history():
    return jsonify({"items": HISTORY, "count": len(HISTORY)})


@main.post("/api/history/clear")
def api_history_clear():
    HISTORY.clear()
    return jsonify({"ok": True, "items": []})


@main.post("/api/recognize")
def api_recognize():
    """接收图片（multipart 或 base64）并返回菜品识别结果。"""
    image_bytes, source_name, error = _extract_image()
    if error:
        return jsonify({"ok": False, "error": error}), 400
    if not image_bytes:
        return jsonify({"ok": False, "error": "图片内容为空"}), 400

    saved = _save_upload(image_bytes, source_name)

    try:
        result = _build_recognizer().recognize_bytes(image_bytes)
    except BaiduError as exc:
        return jsonify({"ok": False, "error": exc.message, "code": exc.code}), 502
    except Exception as exc:  # noqa: BLE001 - 网络异常等
        current_app.logger.exception("识别失败")
        return jsonify({"ok": False, "error": f"识别服务异常：{exc}"}), 502

    record = {
        "id": uuid.uuid4().hex,
        "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "source": source_name,
        "image_url": f"/uploads/{saved.name}" if saved else None,
        "results": result["items"],
        "top": result["top"],
        "count": result["count"],
    }
    HISTORY.insert(0, record)
    del HISTORY[HISTORY_LIMIT:]

    return jsonify({"ok": True, "data": record})


@main.errorhandler(413)
def too_large(_error):
    return jsonify({"ok": False, "error": "图片太大（上限 8MB）"}), 413
