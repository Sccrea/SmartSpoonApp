"""查手机上传回来的 SQLite 库，验证「登录时云端覆盖本地」真的落库了。

    python tools/inspect_phone_db.py <库文件路径>

手机上没有 sqlite3 命令，`run-as` 又写不进 /data/local/tmp，
所以走 `adb exec-out run-as <包> cat databases/<库>` 把字节拉回电脑再查。
"""

from __future__ import annotations

import sqlite3
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    path = Path(sys.argv[1])
    if not path.exists():
        print(f"没有这个文件：{path}")
        return 2

    connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        print(f"== {path.name} ==")

        meals = connection.execute(
            "SELECT id, menu_name, started_at, ended_at, bites, weight, energy "
            "FROM meals ORDER BY ended_at"
        ).fetchall()
        print(f"用餐记录 {len(meals)} 条：")
        for row in meals:
            print(f"  #{row[0]} {row[1]}  口数={row[4]} 重量={row[5]}g 热量={row[6]}kJ  "
                  f"结束={row[3]}")

        bites = connection.execute(
            "SELECT dish, weight, energy, at FROM bites ORDER BY at"
        ).fetchall()
        print(f"每一口明细 {len(bites)} 条：")
        for row in bites:
            print(f"  {row[0]}  {row[1]}g  {row[2]}kJ  at={row[3]}")

        dishes = connection.execute(
            "SELECT name, times FROM dishes ORDER BY times DESC, name"
        ).fetchall()
        print(f"菜品 {len(dishes)} 道（按食用次数）：")
        for row in dishes:
            print(f"  {row[0]}  食用 {row[1]} 次")
    finally:
        connection.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
