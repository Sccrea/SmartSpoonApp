"""开发入口：python run.py

默认监听 0.0.0.0，方便局域网中的手机 / 模拟器（Android 应用）访问；
本机访问 http://127.0.0.1:5000/ ，同一局域网的设备访问 http://<本机IP>:5000/ 。
用 HOST/PORT 环境变量可覆盖。
"""

from __future__ import annotations

import os
import socket

from app import create_app

app = create_app(os.getenv("FLASK_CONFIG", "dev"))


def lan_addresses() -> list:
    """列出本机可被局域网访问的 IPv4 地址。"""
    addresses = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            address = info[4][0]
            if address not in addresses and not address.startswith("127."):
                addresses.append(address)
    except OSError:
        pass
    return addresses


if __name__ == "__main__":
    host = os.getenv("HOST", "0.0.0.0")
    port = int(os.getenv("PORT", "5000"))
    print("智味勺 L2 · Flask 原型服务器")
    print(f"  本机： http://127.0.0.1:{port}/")
    for address in lan_addresses():
        print(f"  局域网：http://{address}:{port}/")
    print("  Android 应用可在长按屏幕后把上面的局域网地址填进「服务器地址」。")
    app.run(host=host, port=port, debug=app.config.get("DEBUG", False), threaded=True)
