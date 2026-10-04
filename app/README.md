# 这个目录里的服务器代码已经删除（它是一份过期副本）

这里原本有一份 Flask 服务器的拷贝（`app/__init__.py`、`routes.py`、`dishes.py`…），
它是某次调试时复制过来的，**从 2026-10-03 起就与真正的服务器分叉了**。

留着它的代价是真实发生过的：`tools/smoke.py` 通过 `sys.path` 导入时优先级高于服务器仓库，
于是一次"去掉模板数据"的改动改在了真正的服务器上，冒烟测试读到的却仍是这份旧副本里的
模板菜品与演示记录 —— **测试显示通过的是旧代码，很容易得出完全错误的结论**。

## 真正的服务器在这里

```
C:\Users\Administrator\Desktop\smartspoon\smartspoon\
├─ app/                 服务器源码（accounts.py / routes.py / dishes.py / data.py …）
├─ data/dishes.json     菜品库（首次运行是空的）
├─ data/users.json      账号库（注册用户）
├─ run.py               启动入口（带自签 TLS，默认 0.0.0.0:5000）
└─ tools/
   ├─ smoke.py          接口冒烟测试
   └─ test_accounts.py  账号接口自测（40 项断言）
```

启动：`C:\Users\Administrator\Desktop\SmartSpoon-Server.bat`
公网入口：`https://sccrea64.cc.cd:16384`（由 `frpsmartspoon.bat` 做内网穿透）

## 客户端在这里

```
C:\Users\Administrator\Desktop\SmartSpoonApp\
└─ android/             Jetpack Compose 应用（与服务器通过 JSON 接口通信）
```

客户端的 Python 脚本（`tools/smoke.py`）也已挪到服务器仓库的 `tools/`，
这样"服务器代码"和"验证服务器代码的脚本"始终在一起，不会再出现两份分叉的源码。
