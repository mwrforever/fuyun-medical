# AGENTS.md

> 本文件为 AI 编码代理提供仓库级指引，是**定位层**：只描述项目定位、根目录一层结构与子项目宪法索引，不承载子项目内部深度约束。
> **强制阅读路由**：写任一子项目代码前必读其宪法（见 §5 子项目宪法索引）；子项目内的深度约束以子宪法为准。
> **约束效力**：本定位层（含跨切总则）与全部子项目宪法对所有开发行为（编码 / 设计 / 脚本 / CI / 文档）强制生效，冲突产物不得合入 main 分支（总则见 §6）。
> 注释 / 日志 / 测试与死代码规范见全局 `~/.zcode/AGENTS.md`（§一 注释规范、§二 日志规范、§四 测试与死代码、§六 CI/CD 约束），同样强制生效，本文与子宪法只引用不复制正文。

## 1. 项目定位

fuyun-medical：大型医院管理系统（HIS），按业务域划分为 20 个模块（门诊、住院、护理、药房、检验、影像、电子病历、手术、ICU、输血、计费、物联网设备接入、资产、病房、体检、互联网医院、运维、集成平台等），面向三级医院全院区业务闭环。后端为 Spring Boot 模块化单体（`backend/`，Maven 多模块），前端为 pnpm monorepo 三应用（`web/`：医护工作站 workstation、患者门户 portal、数据大屏 bigscreen）。

## 2. 运行形态

| 服务 | 端口 / 入口 | 职责 |
| --- | --- | --- |
| nginx | :80（唯一入口） | 三前端静态资源 + `/api` 反向代理 + `/ws` WebSocket 升级 |
| backend | :8080（不发布宿主端口） | Spring Boot 模块化单体，无状态，可 `--scale` 多实例 |
| postgres | :5432 | PostgreSQL 16 + TimescaleDB 单容器：业务库（多 schema）+ 遥测超表 |
| redis | :6379 | 会话 / 字典缓存 / 号源床位并发锁 / 大屏实时快照 |
| rabbitmq | :5672、:15672 | 领域事件总线 / 告警分发 / TTL+DLX 延迟消息；管理台仅 dev/test |
| minio | :9003（宿主映射，容器内 9000；控制台预留 9004） | 对象存储（仅 dev/test；生产为华为云 OBS，应用层 S3 SDK 不感知差异） |
| iot-simulator | 无 | 模拟设备端（compose profile `sim`），打通 IoTDA→AMQP→库→WebSocket 链路 |

原则：nginx 是唯一公网入口；backend 不暴露端口（联调经 debug profile override）；一切凭据经环境变量注入，禁止硬编码；dev/test 容器镜像版本与 prod 托管服务版本严格对齐。编排查详见 `docs/language/2026-09-07-技术栈选型.md` §6（compose 与 `.env.example` 随 P0 工程骨架落盘于 `deploy/`）。

## 3. 仓库地图（仅一层）

```
fuyun-medical/
├── AGENTS.md / CLAUDE.md       # 定位层 / 根索引（索引全项目仅此一份）
├── TASK.md                     # 登记台：{待调研项} / {待决策项} / TODO 工单
├── CHANGELOG.md                # 工程变更记录（先记再改，含宪法修订）
├── .github/workflows/          # CI 主链 ci.yml 与审计 security.yml（§7 CI 链总则）
├── .pre-commit-config.yaml     # 本地钩子（与 CI hygiene job 同配置同源）
├── commitlint.config.mjs       # 提交规范（conventional commits）
├── scripts/                    # 根级门禁脚本（编码校验等 pre-commit 本地钩子依赖）
├── backend/                    # Java 17 + Spring Boot 3.5 模块化单体 —— 规范见 backend/AGENTS.md
├── web/                        # Vue 3 + TS + Vite 三应用 monorepo —— 规范见 web/AGENTS.md
├── deploy/                     # docker-compose 编排、nginx 配置、中间件初始化（随 P0 落盘）
└── docs/
    ├── specs/                  # 总 Spec 与 20 模块功能设计 / 业务契约（宪法只引用不承载）
    ├── language/               # 技术栈选型定稿（版本唯一权威来源）
    ├── plans/                  # 阶段实施计划（P0~P6 执行编排、PR 序列与验收标准）
    ├── prompt/                 # 交付执行文档（loop/graph 执行提示词，执行会话唯一依据）
    └── agmds-research/         # 宪法调研报告（条款依据，5 份，2026-09-08）
```

## 4. 命令总览（根 vs 子项目）

```bash
pre-commit install                      # 首次克隆后安装本地钩子（husky 由 web/ 侧 setup 承载）
pre-commit run --all-files             # 本地全量文件卫生校验（与 CI hygiene job 同源）
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d   # 起全栈（P0 骨架交付后生效）
docker compose -f deploy/docker-compose.yml --profile sim up -d            # 含模拟设备的 IoT 链路验证
```

口诀：**根命令 = 整个应用（编排与跨栈门禁）；子项目内命令 = 单模块工作**。子项目命令各举一例：后端 `cd backend && mvn -B verify`（全量门禁），前端 `cd web && pnpm test`（全量单测）——全量清单见各自宪法 C.4。

## 5. 子项目宪法索引（必读路由）

| 子项目 | 宪法 | 深度范围（一句话） |
| --- | --- | --- |
| `backend/` | [backend/AGENTS.md](backend/AGENTS.md) | Java 17 + Spring Boot 3.5 编码 / API / 数据库 / 中间件基础设施 / 模块化单体分层 / Maven 构建与 CI 后端细则 |
| `web/` | [web/AGENTS.md](web/AGENTS.md) | Vue 3 + TS + Vite 编码 / 配置 / API 层与类型契约 / monorepo 分层 / pnpm 构建与 CI 前端细则 |

## 6. 约束效力与遵从总则（必含）

1. **强制生效**：本定位层（含跨切总则）与全部子项目宪法对一切开发行为强制生效；全局 `~/.zcode/AGENTS.md` 的注释 / 日志 / 测试与死代码 / CI/CD 约束同样强制生效。
2. **不得违背**：冲突以宪法为准，冲突产物不得合入 main；禁止「临时 / 紧急」绕过——修宪先记 `CHANGELOG.md` 再改正文，一次性事项登记 `TASK.md` 待决策并限定范围。
3. **遵从路径**：开发任一子项目前必读其宪法；跨子项目行为同时遵守 §7 跨切总则。
4. **裁决顺序**：单子项目事项以其子宪法为准；跨子项目事项以本文档为准；未规定事项遵全局 `~/.zcode/AGENTS.md`；全局与本宪法冲突时以本宪法（更特化）为准。

## 7. 跨切约定（repo 级总则，细节归对应子宪法或专项文档）

- **配套文件职责**（全体系唯一声明）：`TASK.md` 登记台（待调研 / 待决策 / TODO，回填后删除条目）；`CHANGELOG.md` 变更记录（**先记再改**，含宪法修订与裁决记录）；`docs/` 过程文档（specs 功能设计、language 技术定稿、agmds-research 调研依据）。
- **文档同步策略**：改接口契约必同步 `docs/specs/` 对应模块 Spec；改技术版本必同步 `docs/language/` 定稿报告并过 CI 全量验证。
- **编码红线**：所有文本文件 UTF-8 无 BOM、LF 行尾（`.gitattributes` + pre-commit 钩子 + CI hygiene 三层强制）；注释 / 日志 / 文档全中文，标识符英文；禁止提交密钥、`.env` 真实值、大文件（>1MB）。
- **版本红线**：依赖与镜像版本一律锁定（无 `latest` 漂移 tag）；Boot BOM 托管依赖禁止自行覆盖版本；升级走 Renovate/Dependabot 提案 + CI 全量验证，人工不擅自升级。
- **安全红线**：等保三级背景——敏感字段（身份证 / 手机号 / 住址等）应用层加密后落库；审计日志留存 ≥6 个月；Swagger UI 与中间件管理台仅 dev/test 暴露。
- **跨子项目协作契约**：API 统一前缀 `/api/v1`（资源复数、小写连字符）；前后端类型契约以 Springdoc OpenAPI 生成物为唯一来源（前端经 openapi-typescript 生成，生成物入库）；WebSocket 统一 `/ws/**` 前缀；traceId 全链路贯穿（前端拦截器注入 → 后端日志输出）。
- **CI 链总则**（方案 B「严格门禁」，用户 2026-09-08 选定）：GitHub Actions ubuntu-latest；主链单文件 `.github/workflows/ci.yml`（changes 路径过滤 → backend / frontend / images / commitlint / hygiene 六 job，任一失败阻断合入）+ 独立 `security.yml`（OWASP dependency-check 每周审计）；门禁全机器化：后端 `mvn verify`（Spotless 格式 + 单测 + Testcontainers 集成测试 + JaCoCo 覆盖率双阈值）与前端 `pnpm lint/type-check/test/build`（ESLint `--max-warnings=0` + Prettier + vue-tsc）+ 提交规范 commitlint + workflow 语法 actionlint + 文件卫生 pre-commit 同源校验 + 依赖审计（前端 `pnpm audit` 高危阻断进主链、后端 OWASP 周审）；最终强制点为 main 分支保护（required status checks 与 job 名精确对齐 + 禁止绕过）；本地 pre-commit 钩子与 CI 同命令、分时机（轻量钩子 pre-commit、重型校验 pre-push / CI 兜底）。流水线细则归各子宪法 C.5；安全增强（CodeQL / Trivy / dependency-review）为二期演进（方案 C），在 security.yml 扩展。

## 8. 去哪里深入

- 各子项目开发 → §5 索引（写代码前必读其宪法）；
- 功能设计与业务数据契约 → `docs/specs/`（总 Spec + 模块 Spec）；阶段实施计划 → `docs/plans/`；
- 技术版本依据 → `docs/language/2026-09-07-技术栈选型.md`；条款调研依据 → `docs/agmds-research/`；
- 登记 → `TASK.md`；变更 → `CHANGELOG.md`；CI 机制与门禁语义 → `.github/workflows/` 与各子宪法 C.5。
