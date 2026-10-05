"""不需要编译器的粗粒度语法体检（针对 .ino / .c / .cpp / .h）。

    python tools/check_ino_syntax.py <文件或目录> [...]

为什么需要它：这些固件源码在**没有 Arduino 环境**的机器上（比如我这台）根本编译不了，
而最常犯的错恰恰是**注释块**那一类 —— 一个多余的 `*/`、或者注释里写了一个 `*/` 片段，
都会让编译器从那里开始把代码当成注释（或反过来），报出一堆与真实原因无关的错误。
这类问题用几十行状态机就能查出来，不用装工具链。

检查项：
1. 块注释 `/* */` 是否配对；**文件末尾还有没闭合的块注释** → 报错；
2. 在**块注释内部**又出现 `*/`（多打了一个）→ 报错；
3. 在**行注释/字符串之外**出现的孤立 `*/`（没有对应的 `/*`）→ 报错；
4. 圆括号 `()`、花括号 `{}`、方括号 `[]` 是否配对（忽略注释与字符串）。

退出码 0 = 没发现问题；1 = 发现问题。

**它不替代编译**：只能说"没有这类低级错误"，语法本身的错误仍然要编译器来抓。
"""

from __future__ import annotations

import sys
from pathlib import Path

SUFFIXES = {".ino", ".c", ".cc", ".cpp", ".h", ".hpp"}
PAIRS = {")": "(", "]": "[", "}": "{"}


def check_text(text: str) -> list[str]:
    """返回问题列表（每项一条人话）。"""
    problems: list[str] = []

    depth = 0
    line = 1
    i = 0
    n = len(text)
    stack: list[tuple[str, int]] = []

    while i < n:
        ch = text[i]

        if ch == "\n":
            line += 1
            i += 1
            continue

        # ---- 字符串 / 字符字面量：整体跳过 ----
        if ch == '"' or ch == "'":
            quote = ch
            start_line = line
            i += 1
            closed = False
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == quote:
                    i += 1
                    closed = True
                    break
                if text[i] == "\n":
                    line += 1
                    # 字符串不该跨行（除非续行），这里只提醒，不当错误
                    break
                i += 1
            if not closed:
                problems.append(f"第 {start_line} 行：{quote} 引号看起来没有闭合")
            continue

        # ---- 行注释 ----
        if text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
            continue

        # ---- 块注释开始 ----
        if text.startswith("/*", i):
            if depth > 0:
                problems.append(f"第 {line} 行：块注释里又出现了 /*（嵌套注释，很容易算错层次）")
            depth += 1
            i += 2
            continue

        # ---- 块注释结束 ----
        if text.startswith("*/", i):
            if depth == 0:
                problems.append(f"第 {line} 行：出现孤立的 */（没有对应的 /*，多半是多打了一个）")
            else:
                depth -= 1
            i += 2
            continue

        # ---- 注释里的内容一律不算结构 ----
        if depth > 0:
            i += 1
            continue

        # ---- 括号配对 ----
        if ch in "([{":
            stack.append((ch, line))
            i += 1
            continue
        if ch in ")]}":
            if not stack:
                problems.append(f"第 {line} 行：多了一个 {ch}")
            else:
                open_ch, open_line = stack.pop()
                if open_ch != PAIRS[ch]:
                    problems.append(
                        f"第 {line} 行：{ch} 与第 {open_line} 行的 {open_ch} 不匹配"
                    )
            i += 1
            continue

        i += 1

    if depth > 0:
        problems.append(f"块注释没有闭合：文件结束时还有 {depth} 层 /* 没关")
    for open_ch, open_line in stack:
        problems.append(f"第 {open_line} 行的 {open_ch} 没有对应的收尾括号")

    return problems


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    targets: list[Path] = []
    for raw in sys.argv[1:]:
        p = Path(raw)
        if p.is_dir():
            targets.extend(sorted(f for f in p.rglob("*") if f.suffix.lower() in SUFFIXES))
        elif p.exists():
            targets.append(p)
        else:
            print(f"路径不存在：{p}")

    if not targets:
        print("没有找到要检查的文件")
        return 2

    bad = 0
    for path in targets:
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            text = path.read_text(encoding="utf-8", errors="replace")
            print(f"[注意] {path.name} 不是合法 UTF-8，按替换字符读的")
        problems = check_text(text)
        if problems:
            bad += 1
            print(f"[BAD] {path}")
            for item in problems:
                print(f"      {item}")
        else:
            print(f"[ok]  {path.name}  注释与括号配对正常")
    print(f"\n检查 {len(targets)} 个文件，有问题 {bad} 个")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
