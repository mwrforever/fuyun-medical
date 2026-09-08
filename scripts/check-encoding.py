#!/usr/bin/env python3
"""编码硬约束校验：UTF-8 无 BOM、内容可按 UTF-8 解码（无乱码）、行尾 LF（无 CRLF）。

三条硬约束来源：根 AGENTS.md §7 编码红线（所有文本文件 UTF-8 无 BOM、LF 行尾、发现中文乱码立即修复），
经 pre-commit 本地钩子（check-utf8-no-bom）与 CI hygiene job 同源执行。
只读校验、不修改文件：任一文件命中任一违规即退出码 1，并逐条打印中文报错（文件路径 + 违规类型）。
"""

import argparse
import sys
from pathlib import Path

# UTF-8 BOM 的字节特征（文件头 EF BB BF）
_BOM = b"\xef\xbb\xbf"


def check_file(path: Path) -> list[str]:
    """对单个文件执行三项编码检查，返回违规描述列表。

    参数 path：待检文件路径（相对/绝对均可），调用方保证文件真实存在。
    返回值：中文违规描述列表，每条含违规类型；空列表表示通过；文件读取失败也生成违规条目。
    异常：不向外抛出——OSError 转为违规描述，保证批量校验不因单文件中断。
    """
    problems: list[str] = []
    try:
        data = path.read_bytes()
    except OSError as exc:  # 文件被并发删除 / 权限受限等读取失败场景
        problems.append(f"无法读取文件：{exc}")
        return problems
    # 检查一：UTF-8 BOM（入库文本文件禁止携带 BOM 头）
    if data.startswith(_BOM):
        problems.append("含 UTF-8 BOM（文件以 EF BB BF 开头）")
    # 检查二：非 UTF-8 编码——按 UTF-8 解码失败即中文乱码或 GBK 等其他编码
    try:
        data.decode("utf-8")
    except UnicodeDecodeError as exc:
        problems.append(f"非 UTF-8 编码（乱码）：偏移 {exc.start} 字节附近无法解码（{exc.reason}）")
    # 检查三：CRLF 行尾（全局规范强制 LF，Windows 惯性换行在此拦截）
    if b"\r\n" in data:
        problems.append("含 CRLF 行尾（应统一为 LF）")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "校验文件编码硬约束：UTF-8 无 BOM、可按 UTF-8 解码、LF 行尾"
            "（根 AGENTS.md §7 编码红线；pre-commit 钩子 check-utf8-no-bom 的实现）"
        )
    )
    parser.add_argument(
        "files",
        nargs="*",
        help="待校验的文件路径列表（pre-commit 会把命中的文件追加传入；无参数时直接通过）",
    )
    args = parser.parse_args()

    failed = 0
    for name in args.files:
        path = Path(name)
        # 跳过不存在或非常规文件（如已被删除的暂存条目），不视为违规
        if not path.is_file():
            continue
        for problem in check_file(path):
            failed += 1
            print(f"[编码违规] {path}: {problem}")

    if failed:
        print(
            f"共发现 {failed} 处编码违规，请修复后重试"
            "（BOM/CRLF 由编辑器或 pre-commit 钩子自动修复；乱码文件请人工核查后恢复内容）"
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
