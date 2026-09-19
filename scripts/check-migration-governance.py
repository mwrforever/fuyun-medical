#!/usr/bin/env python3
"""Flyway 迁移治理校验：号段归属 + 版本唯一 + 乱序守卫（backend 宪法 A.4.1-2/A.4.1-3，TASK.md W-4）。

三项校验：
  ① 号段归属——每个迁移文件所在 schema 目录须落在其登记号段内（含 V500+「先登记先占」通用段）；
  ② 版本唯一——全部 locations（各模块 db/migration/<schema>/）内版本号不得重复；
  ③ 乱序守卫——相对基线版本新增的迁移文件，版本号必须大于基线中全部 locations 已有最大版本号
     （真库已应用 500 段后，低版本号新文件会被 Flyway 以 outOfOrder=false 拒绝，2026-09-14 真栈实证）；
     例外：schema 在基线中零迁移时视为「号段初始化」，其首个批次放行——全新库按版本升序一次应用
     是 Flyway 唯一事实（PR-2 patient 号段 V100–V105 先例，CHANGELOG 2026-09-16 条目）；豁免仅承载
     初始化批次——批次合入后该 schema 后续迁移一律走 V500+ 通用段（全局规则恢复约束，TASK.md W-12）。

只读校验、不修改文件：任一违规即退出码 1，逐条打印中文报错（文件路径 + 违规类型）。
"""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

# 迁移文件路径形态：backend/<模块目录>/src/main/resources/db/migration/<schema>/V<版本>__<描述>.sql
_MIGRATION_FILE_RE = re.compile(r"^V(?P<version>\d+)__[a-z0-9_]+\.sql$")
_MIGRATION_DIR_GLOB = "backend/*/src/main/resources/db/migration/*"

# 号段登记表（载体 = CHANGELOG.md 2026-09-08 条目「Flyway 号段登记」，新增登记须同步本表）
# 值为号段区间元组列表，None 表示无上界；各模块一律允许 V500+ 通用段（先登记先占）
_SEGMENTS = {
    "integration": ((1, 99), (500, None)),
    "patient": ((100, 199), (500, None)),
    "outpatient": ((200, 299), (500, None)),
    "system": ((300, 399), (500, None)),
    "iot": ((400, 499), (500, None)),
    # billing 固定百位段（PR-3 首批 V600–V605，CHANGELOG 2026-09-17 条目先记再改）
    "billing": ((600, 699), (500, None)),
    # pharmacy 固定百位段（PR-4 首批 V700–V703，CHANGELOG 2026-09-18 条目先记再改）
    "pharmacy": ((700, 799), (500, None)),
}

# 未登记号段模块的唯一合法区间（V500+ 通用段）
_UNREGISTERED_SEGMENT = ((500, None),)


def _git(root: Path, *args: str) -> str | None:
    """执行只读 git 命令；仓库不可用或命令失败返回 None（由调用方决定告警语义）。"""
    try:
        result = subprocess.run(
            ["git", "-C", str(root), *args], capture_output=True, text=True, check=True
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    return result.stdout


def _iter_migration_files(root: Path) -> list[tuple[str, int, str]]:
    """扫描全部迁移文件，返回（schema, 版本号, 相对仓库根路径）三元组列表，按路径排序。

    只扫 backend/*/src/main/resources/db/migration/ 下的 src 路径：构建产物
    （target/classes/db/migration/…，PR-1a 改名前的 V6/V7 旧产物仍在）天然不在扫描面内。
    """
    found: list[tuple[str, int, str]] = []
    for directory in sorted(root.glob(_MIGRATION_DIR_GLOB)):
        if not directory.is_dir():
            continue
        for file in sorted(directory.glob("*.sql")):
            match = _MIGRATION_FILE_RE.match(file.name)
            if match is None:
                found.append((directory.name, -1, file.relative_to(root).as_posix()))
                continue
            found.append((directory.name, int(match.group("version")), file.relative_to(root).as_posix()))
    return found


def _in_segments(version: int, segments: tuple) -> bool:
    """版本号是否落在任一登记号段内（区间闭区间，None 表示无上界）。"""
    for lower, upper in segments:
        if version >= lower and (upper is None or version <= upper):
            return True
    return False


def check_segments(files: list[tuple[str, int, str]]) -> list[str]:
    """校验一：号段归属——schema 目录须与其登记号段匹配。"""
    problems: list[str] = []
    for schema, version, path in files:
        if version < 0:
            problems.append(f"[号段归属] {path}：文件名不符合 V<版本>__<全小写下划线描述>.sql 规范")
            continue
        segments = _SEGMENTS.get(schema, _UNREGISTERED_SEGMENT)
        if not _in_segments(version, segments):
            hint = "、".join(
                f"V{lower}-{'∞' if upper is None else 'V' + str(upper)}" for lower, upper in segments
            )
            problems.append(f"[号段归属] {path}：V{version} 不在 schema={schema} 的登记号段（{hint}）内")
    return problems


def check_duplicates(files: list[tuple[str, int, str]]) -> list[str]:
    """校验二：版本唯一——全部 locations 内版本号不得重复（Flyway 迁移历史全局唯一）。"""
    seen: dict[int, str] = {}
    problems: list[str] = []
    for _schema, version, path in files:
        if version < 0:
            continue
        if version in seen:
            problems.append(f"[版本重复] {path}：V{version} 已被 {seen[version]} 占用")
            continue
        seen[version] = path
    return problems


def _base_migration_versions(root: Path, base_ref: str) -> list[tuple[str, int, str]] | None:
    """读取基线版本中全部迁移文件三元组；基线不可解析（非 git 仓库/引用不存在）返回 None。"""
    listing = _git(root, "ls-tree", "-r", "--name-only", base_ref)
    if listing is None:
        return None
    prefix = "backend/"
    suffix = "/src/main/resources/db/migration/"
    base_files: list[tuple[str, int, str]] = []
    for line in listing.splitlines():
        if not line.startswith(prefix) or suffix not in line or not line.endswith(".sql"):
            continue
        schema_part = line.split(suffix, 1)[1]
        if "/" not in schema_part:
            continue
        schema, name = schema_part.split("/", 1)
        match = _MIGRATION_FILE_RE.match(name)
        if match is not None:
            base_files.append((schema, int(match.group("version")), line))
    return base_files


def check_out_of_order(
    root: Path, files: list[tuple[str, int, str]], base_ref: str
) -> list[str]:
    """校验三：乱序守卫——新增迁移版本号必须大于基线中已有最大版本号；号段初始化豁免。"""
    base_files = _base_migration_versions(root, base_ref)
    if base_files is None:
        return [
            f"[乱序守卫] 基线版本 {base_ref} 无法解析：请先 git fetch（CI 需 fetch-depth: 0 与 "
            f"MIGRATION_BASE_REF 注入，见 .github/workflows/ci.yml hygiene job）"
        ]
    base_paths = {path for _schema, _version, path in base_files}
    # 号段初始化判定依据：基线中该 schema 已有迁移清单（零迁移 = 首个批次放行）
    base_schemas = {schema for schema, _version, _path in base_files}
    max_base_version = max((version for _schema, version, _path in base_files), default=0)
    problems: list[str] = []
    for schema, version, path in files:
        if path in base_paths or version < 0:
            continue
        # 号段初始化豁免：全新库升序应用合法；存量环境经 CHANGELOG 登记的一次性重置承接
        if schema not in base_schemas:
            continue
        if version <= max_base_version:
            problems.append(
                f"[乱序守卫] {path}：新增迁移 V{version} 不大于基线已有最大版本 V{max_base_version}"
                f"（Flyway outOfOrder=false 会拒绝执行，PR-1a 真栈实证）"
            )
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "校验 Flyway 迁移治理：号段归属 + 版本唯一 + 乱序守卫"
            "（backend 宪法 A.4.1-2/A.4.1-3；pre-commit 钩子 check-migration-governance 的实现）"
        )
    )
    parser.add_argument(
        "--root",
        default=None,
        help="仓库根目录（默认由本脚本位置推导：scripts/ 的上一级）；自测时可指向夹具目录",
    )
    parser.add_argument(
        "--base-ref",
        default=None,
        help="乱序守卫基线版本（默认取环境变量 MIGRATION_BASE_REF，未设时用 HEAD）",
    )
    args = parser.parse_args()

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parents[1]
    base_ref = args.base_ref or os.environ.get("MIGRATION_BASE_REF") or "HEAD"
    files = _iter_migration_files(root)
    problems = check_segments(files) + check_duplicates(files) + check_out_of_order(root, files, base_ref)

    if problems:
        for problem in problems:
            print(f"[迁移治理违规] {problem}")
        print(f"共发现 {len(problems)} 处迁移治理违规，请修复后重试（号段登记载体：CHANGELOG.md）")
        return 1
    print(f"迁移治理校验通过：{len(files)} 个迁移文件，基线 {base_ref}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
