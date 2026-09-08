# SDD ledger — plan: docs/plans/2026-09-08-P0实施计划.md

> 本台账为 P0 交付 loop（执行依据：`docs/prompt/2026-09-09-loop-P0工程骨架.md`）的状态续传载体：每过一个门禁即更新，中断后从台账 + git log 恢复；完成声明必附证据。

## 阶段状态总表

| 阶段 | 内容 | 状态 | 证据 / 说明 |
| --- | --- | --- | --- |
| P0 | dev 分支与门禁准备 | complete | dev 自 origin/main(a10369e) 建立；gh api 证实 dev 保护 = main 五 checks（strict=true、enforce_admins=true、禁 force push/删除），见下「门禁证据」 |
| P1 | PR-1 工程骨架（B1.1~B1.4） | pending | — |
| P2 | PR-2 M20 治理构件（B2.1~B2.3） | pending | 前置：PR-1 合入 dev |
| P3 | PR-3 M01 系统与权限（B3.1~B3.4） | pending | 前置：PR-2 合入 dev；开工前 D-2 令牌方案 ask_question（默认轻量 HMAC+Redis 会话） |
| P4 | PR-4 M14 IoT 通路（B4.1~B4.4） | pending | 前置：PR-3 合入 dev；开工前 D-3 simulator ask_question（默认 Java+backend/iot-simulator）；T-R3-2 实测锁定 |
| P5 | PR-5 收口（B5.1~B5.2） | pending | 前置：PR-4 合入 dev |
| P6 | 终验报告 | pending | DoD 逐项附证据，docs PR 合入 dev |

## 冲突扫描记录（P0，2026-09-09）

逐项核对 loop 文档 / PLAN-P0-01 / 根 AGENTS.md / backend·web 宪法 / 技术栈定稿：

1. **nginx 静态资源 bind mount 路径**：定稿 §6.6 片段写 `../../web/workstation/dist`（且片段缺 portal、路径缺 `apps/` 段）；web 宪法 C.3 与 TASK.md W-3 对齐项②已裁决「产物路径以 `web/apps/<app>/dist` 为准」。→ 按 W-3 既有裁决执行：compose 内写 `../web/apps/<app>/dist`（相对 deploy/），portal 一并挂载，无需用户再裁决。
2. **前端门禁步数**：loop 文档称「前端五门禁」，web 宪法 C.5-1 为六步（lint / format:check / type-check / test / build / audit）。→ 以宪法与 CI 为准六步全跑，loop 五门禁为概称，不构成冲突。
3. **版本口径**：Boot 3.5.16、MP 3.5.17（含 jsqlparser 3.5.17）、jacoco 0.8.15、spotless 3.4.0、ArchUnit 1.5.0、ShedLock 6.10.0、qpid-jms 2.11.0、TimescaleDB 镜像 2.29.2-pg16、redis 8.10.1、rabbitmq 4.3.5-management、minio RELEASE.2025-09-07T16-13-09Z、nginx 1.30.4、pnpm 12.3.4、Node 24、Vitest 4.1.11——loop 文档 / 计划 / 双宪法 / 定稿四方一致，无表外依赖。
4. **CI 五 checks 名称**：`backend / verify`、`frontend / verify`、`images`、`commitlint`、`hygiene` 与 main 分支保护（gh api 实测）一致。
5. **决策点**：D-2（P3 前）、D-3（P4 前）与 TASK.md 待决策表一致；T-R3-2 为实测项非用户决策。

结论：无遗留分歧，无需阻塞问询。

## 门禁证据（P0）

- dev 分支：`git push -u origin dev` 成功（origin/dev 已建）。
- dev 保护 gh api GET 输出：`contexts=["backend / verify","frontend / verify","images","commitlint","hygiene"], strict=true, enforce_admins=true, pr_reviews=0, allow_force_pushes=false, allow_deletions=false`——与 main 逐字段一致。

## 批次明细（P1~P5 推进时逐批回填）

| PR | 批次 | 状态 | 备注（专员 / 测试 / 审核结论） |
| --- | --- | --- | --- |
| PR-1 | B1.1 后端骨架：父 POM/插件管理、common/app、20 域占位 | complete | 462 文件 3 提交；mvn test 10/10 绿+spotless 绿；审核 SPEC/QUALITY 双 PASS（3 Minor 非阻断） |
| PR-1 | B1.2 冒烟 IT + Dockerfile COPY 重写 | complete | SmokeStackIT 3 断言 + verify 全绿 + 镜像构建成功；审核双 PASS（classifier=exec 与构造器 @Autowired 经实证必需） |
| PR-1 | B1.3 deploy/ 全量 + .env.example + ci.yml 移除排除项 | complete | 五服务两次起停全 healthy+nginx -t 过；审核双 PASS（4 项申报偏差全部成立） |
| PR-1 | B1.4 web monorepo + 三 app + shared/ui + 冒烟单测 | in_progress | — |
