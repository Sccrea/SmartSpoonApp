"""服务器接口冒烟测试（ASCII 输出）。运行：.venv\\Scripts\\python.exe tools\\smoke.py

不需要真的起服务器：内部用 Flask 的 test_client 直接打接口，临时菜品库落在临时目录里。
网页端已废弃，这里只验证 JSON 接口：菜品库读写、应用启动时的 bootstrap、
百度菜品识别（真实上传，需要 .env 里配好密钥）。

退出码 0 = 全部通过，1 = 有失败项。
"""

import base64
import io
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import create_app  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SAMPLE = ROOT / "2026041117758876477960001.jpg"

failures = []


def check(label, condition, detail=""):
    if not condition:
        failures.append(label)
    print(f"[{'PASS' if condition else 'FAIL'}] {label} {detail}")


tmpdir = tempfile.mkdtemp(prefix="dishes-")
data_file = Path(tmpdir) / "dishes.json"
app = create_app("dev", overrides={"DISH_DATA_FILE": data_file})
client = app.test_client()

# ---------------------------------------------------------------- 基础接口
resp = client.get("/")
check("GET / 只返回接口清单（无网页）",
      resp.is_json and "endpoints" in (resp.get_json() or {}), resp.status_code)

resp = client.get("/api/health")
health = resp.get_json() or {}
check("GET /api/health", resp.status_code == 200 and health.get("status") == "ok",
      f"dishes={health.get('dishes')}")

# ----------------------------------------------------------------- bootstrap
resp = client.get("/api/bootstrap")
data = (resp.get_json() or {}).get("data") or {}
required = {"foods", "menus", "categories", "folders", "records", "stats", "chart", "devices", "user", "result"}
check("GET /api/bootstrap", resp.status_code == 200 and required <= set(data))
check("bootstrap 含菜品库", len(data.get("foods", [])) >= 12, f"count={len(data.get('foods', []))}")
check("bootstrap result 字段完整",
      {"savedNo", "duration", "weight", "energy", "avgWeight", "avgEnergy"} <= set(data.get("result", {})))
check("bootstrap 带 dishesUpdated", bool(data.get("dishesUpdated")), data.get("dishesUpdated"))

# --------------------------------------------------------------- 菜品库 CRUD
dishes = client.get("/api/dishes").get_json() or {}
check("GET /api/dishes", len(dishes.get("foods", [])) >= 12)

resp = client.post("/api/dishes",
                   json={"name": "服务器测试菜", "category": "肉类", "density": 3.3, "times": 1})
created = (resp.get_json() or {}).get("food") or {}
check("POST /api/dishes 新增", resp.status_code == 200 and created.get("name") == "服务器测试菜",
      created.get("id"))
food_id = created.get("id")

updated = (client.post("/api/dishes", json={"name": "服务器测试菜", "density": 9.9}).get_json() or {}).get("food") or {}
check("POST /api/dishes 同名更新", updated.get("id") == food_id and updated.get("density") == 9.9)

eaten = (client.post(f"/api/dishes/{food_id}/eat").get_json() or {}).get("food") or {}
check("POST /api/dishes/<id>/eat", eaten.get("times") == 2)

app2 = create_app("dev", overrides={"DISH_DATA_FILE": data_file})
foods2 = (app2.test_client().get("/api/dishes").get_json() or {}).get("foods", [])
check("菜品已落盘（重启后仍在）", any(f.get("id") == food_id for f in foods2), f"count={len(foods2)}")

check("DELETE /api/dishes/<id>", client.delete(f"/api/dishes/{food_id}").status_code == 200)
left = (client.get("/api/dishes").get_json() or {}).get("foods", [])
check("删除后不存在", not any(f.get("id") == food_id for f in left))

check("POST 空名称 -> 400", client.post("/api/dishes", json={"name": ""}).status_code == 400)

# ------------------------------------------------------------------- 识别
check("recognize 空请求 -> 400", client.post("/api/recognize", data={}).status_code == 400)
check("recognize 非法后缀 -> 400",
      client.post("/api/recognize", data={"image": (io.BytesIO(b"x"), "x.txt")},
                  content_type="multipart/form-data").status_code == 400)

check("示例图片存在", SAMPLE.exists(), SAMPLE.name)
if SAMPLE.exists():
    raw = SAMPLE.read_bytes()
    resp = client.post("/api/recognize",
                       data={"image": (io.BytesIO(raw), SAMPLE.name)},
                       content_type="multipart/form-data")
    result = resp.get_json() or {}
    check("recognize 上传 -> 200", resp.status_code == 200)
    check("recognize 返回结果", bool((result.get("data") or {}).get("results")))
    top = (result.get("data") or {}).get("top") or {}
    print("       top =", top.get("name"), top.get("percent"))
    resp = client.post("/api/recognize",
                       json={"image_base64": "data:image/jpeg;base64," + base64.b64encode(raw).decode()})
    check("recognize base64 -> 200", resp.status_code == 200)

check("识别历史非空", (client.get("/api/history").get_json() or {}).get("count", 0) > 0)
check("清空识别历史", client.post("/api/history/clear").status_code == 200)

print()
print("FAILURES:", failures if failures else "none")
sys.exit(1 if failures else 0)
