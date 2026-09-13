"""应用配置：优先读取环境变量，其次读取 .env，最后回退到默认值。"""

from __future__ import annotations

import os
from pathlib import Path

try:  # python-dotenv 可选
    from dotenv import load_dotenv
except ImportError:  # pragma: no cover
    load_dotenv = None

BASE_DIR = Path(__file__).resolve().parent.parent

if load_dotenv is not None:
    load_dotenv(BASE_DIR / ".env")

# 百度 AI 开放平台的密钥：**只从环境变量 / .env 读取，源码里不留默认值**
# （本仓库是公开的，硬编码密钥等于把凭据公开；在仓库根目录建 .env 写入真实密钥，
#  格式见 .env.example —— .env 已被 .gitignore 忽略，不会上传）
# 注意：这两个名字必须与 Config.SECRET_KEY（Flask 会话密钥）区分开，
# 否则在类体内会解析到同名类属性，导致识别接口拿到错误的密钥。
DISH_API_KEY = os.getenv("BAIDU_API_KEY", "")
DISH_SECRET_KEY = os.getenv("BAIDU_SECRET_KEY", "")

# 兼容旧引用（_design/live_api.py 等）
API_KEY = DISH_API_KEY
SECRET_KEY = DISH_SECRET_KEY


class Config:
    SECRET_KEY = os.getenv("FLASK_SECRET_KEY", "smart-spoon-l2-dev-secret")

    BAIDU_API_KEY = DISH_API_KEY
    BAIDU_SECRET_KEY = DISH_SECRET_KEY
    TOP_NUM = int(os.getenv("DISH_TOP_NUM", "5"))
    REQUEST_TIMEOUT = int(os.getenv("BAIDU_TIMEOUT", "15"))

    UPLOAD_FOLDER = Path(os.getenv("UPLOAD_FOLDER", str(BASE_DIR / "uploads")))
    ALLOWED_EXTENSIONS = {"jpg", "jpeg", "png", "bmp", "webp"}
    MAX_CONTENT_LENGTH = 8 * 1024 * 1024  # 8MB 请求上限（接口本身限 4MB）

    APP_VERSION = "1.0"
    # 菜品库落盘位置：应用添加的菜品在这里持久化
    DISH_DATA_FILE = Path(os.getenv("DISH_DATA_FILE", str(BASE_DIR / "data" / "dishes.json")))


class DevConfig(Config):
    DEBUG = True


class ProdConfig(Config):
    DEBUG = False


CONFIG_MAP = {"dev": DevConfig, "prod": ProdConfig, "default": DevConfig}
