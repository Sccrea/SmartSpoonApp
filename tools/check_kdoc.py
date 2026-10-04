"""检查 Kotlin 源码里的**块注释是否配对**。

    python tools/check_kdoc.py            # 检查 android/app/src/main/java
    python tools/check_kdoc.py <目录>      # 检查指定目录

退出码 0 = 全部配对；1 = 有文件不配对（会指出行号）。

## 为什么需要这个小工具

Kotlin 的块注释**可以嵌套**。所以 KDoc 里随手写一个 `/*` —— 比如描述接口路径
`` `/api/device/*` `` —— 就会把后面**整个文件**吞进注释里。编译器随后报的是一堆
"Unresolved reference: xxx"，指向别的文件，看上去像是重构搞坏了类型，
实际上只是少了一个 `*/`。这个坑真的踩过一次，所以留一个检查脚本。

（脚本会跳过字符串字面量与 `//` 行注释，因为 `type = "image/*"` 里的 `/*` 不是注释。）
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ROOT = ROOT / "android" / "app" / "src" / "main" / "java"


def scan(text: str) -> tuple[int, list[int]]:
    """返回 (EOF 时的注释深度, 嵌套注释出现的行号列表)。"""
    depth = 0
    nested: list[int] = []
    line = 1
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "\n":
            line += 1
            i += 1
            continue
        # 字符串字面量：整体跳过（含转义）
        if ch == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                if text[i] == "\n":
                    line += 1
                i += 1
            continue
        # 行注释
        if text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
            continue
        if text.startswith("/*", i):
            depth += 1
            if depth > 1:
                nested.append(line)
            i += 2
            continue
        if text.startswith("*/", i):
            depth -= 1
            i += 2
            continue
        i += 1
    return depth, nested


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_ROOT
    if not root.exists():
        print(f"目录不存在：{root}")
        return 2

    files = sorted(root.rglob("*.kt"))
    bad = 0
    for path in files:
        depth, nested = scan(path.read_text(encoding="utf-8"))
        if depth != 0 or nested:
            bad += 1
            print(f"[BAD] {path.relative_to(ROOT)}")
            if nested:
                print(f"      第 {nested[:5]} 行出现嵌套块注释（KDoc 里写了 /* ？）")
            print(f"      文件末尾注释深度 = {depth}（应为 0，说明有 /* 没闭合）")
    print(f"检查 {len(files)} 个 .kt 文件，问题文件 {bad} 个")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
