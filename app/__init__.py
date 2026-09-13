"""智味勺 L2 · Flask 应用工厂（纯 API，无网页端）。"""

from __future__ import annotations

from pathlib import Path

from flask import Flask

from .config import CONFIG_MAP, Config


def create_app(config_name: str = "dev", overrides: dict | None = None) -> Flask:
    app = Flask(__name__, template_folder=str(Path(__file__).resolve().parent.parent / "templates"))

    config_class = CONFIG_MAP.get(config_name, CONFIG_MAP["default"])
    app.config.from_object(config_class)
    if overrides:
        app.config.update(overrides)

    app.config["UPLOAD_FOLDER"] = Path(app.config["UPLOAD_FOLDER"])
    app.config["UPLOAD_FOLDER"].mkdir(parents=True, exist_ok=True)
    # 菜品库落盘目录
    Path(app.config["DISH_DATA_FILE"]).parent.mkdir(parents=True, exist_ok=True)

    from . import routes
    from .routes import main as api_blueprint

    routes.configure_store(app.config["DISH_DATA_FILE"])
    app.register_blueprint(api_blueprint)

    @app.after_request
    def add_no_cache_headers(response):  # pragma: no cover - 便于调试
        if app.debug:
            response.headers["Cache-Control"] = "no-store"
        return response

    return app
