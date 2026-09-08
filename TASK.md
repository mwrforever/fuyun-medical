# TASK.md（登记台）

> 登记规则（根 AGENTS.md §7）：`{待调研项}`（检索不可得，注明原因与回填时点）、`{待决策项}`（需用户 / 总 Spec 裁决）、TODO 工单。条目回填后即删除；来源编号对应 `docs/agmds-research/` 调研报告（R1=CI链方案、R2=Java与SpringBoot栈、R3=数据与集成基础设施、R4=前端栈、R5=构建测试与CI落地细则）。

## 待决策项（阻塞对应工作，须先裁决）

| 编号 | 事项 | 背景 / 调研建议 | 影响范围 |
| --- | --- | --- | --- |
| D-2 | Spring Modulith 引入与否 | R2 §7.2：ArchUnit 1.5.0 已定为边界守护第一步；Modulith 1.4.13（Boot 3.5 世代）为增量框架候选，提供事件发布注册表等能力；引入属增量框架决策，须总 Spec 修订裁决，若引入必须锁 1.4.x | backend Part B.3 可靠事件投递实现成本 |
| D-3 | iot-simulator 实现语言与代码位置 | R5 §5-3：未定稿；若 Java 建议放 `backend/iot-simulator/`（Maven 子模块），若 Node 建议顶层目录；影响 images job 的第三条 matrix | CI images job、deploy compose |
| D-4 | OWASP 周审失败处置流程 | R5 §5-4：fail 后开 issue 还是仅通知，属流程决策 | security.yml |
| D-5 | 方案 C 二期演进（CodeQL / Trivy / dependency-review / Renovate/Dependabot / SonarQube） | R1 §3.3/§4：用户已定方案 B 上线基线、C 为二期；SonarQube 若引入需 CI 双 JDK（17 构建 + 21 扫描）；Renovate vs Dependabot 二选一（倾向 Renovate 的 monorepo 分组能力） | security.yml 扩展、仓库设置 |
| D-6 | 宪法 `enum/` 包目录命名与 Java 保留字冲突 | P0 PR-1 审核发现（2026-09-09）：backend 宪法 B.1/C.3 规定枚举包目录为 `enum/`，但 `enum` 是 Java 保留字，`package com.fuyun.{domain}.enum` 无法编译——目前仅 .gitkeep 占位无碍，首个枚举类落地（PR-2/PR-3）前必须裁决（如改 `enums/`、`enumeration/` 或 `constant/`+枚举混放，需修宪：先记 CHANGELOG） | backend 全部 20 模块目录结构、PR-2/PR-3 枚举类落位 |

## 待调研项（检索不可得 / 需实测，回填后删除）

| 编号 | 事项 | 来源 | 回填时点 |
| --- | --- | --- | --- |
| T-R2-1 | 国内 HIS 行业 ORM 主流度权威统计 | R2 T-1 | 已无决策影响（ORM 已定稿 MyBatis-Plus），仅存档 |
| T-R2-3 | 统一 envelope 响应模式权威出处（本宪法已裁决走 ProblemDetail 路线，仅存档） | R2 T-3 | 已闭环 |
| T-R2-5 | MyBatis-Plus SQL 日志配置细节（MP 配置项承载，替代裸 MyBatis log-impl） | R2 T-5 | P0 实施期 |
| T-R3-1 | `CREATE INDEX CONCURRENTLY` 在 Flyway 11.7.2 的事务外执行兼容性 | R3 T1 | P0 实测 |
| T-R3-2 | TimescaleDB 2.29.2 `add_columnstore_policy` vs `add_compression_policy` 实测差异 | R3 T2 | P0 实测后锁定压缩策略函数 |
| T-R3-3 | Qpid failover 重连后 Session/Consumer 自动重建行为（IoTDA 断链 10 分钟实测） | R3 T3 | P0 实测 |
| T-R3-4 | `fy.delay` quorum 队列 TTL+DLX 到期转发时延压测 | R3 T4 | P0 实测 |
| T-R3-5 | IoTDA 单消息 ≤0.5KB 限制对遥测报文分片的影响 | R3 T5 | 与 14-iot Spec 联动 |
| T-R3-6 | HAPI MLLP `stop()` 与 `stopAndShutdown()` 排空语义差异 | R3 T6 | 联调期实测 |
| T-R4-1 | Axios 新官方站（axios.rest）实例/默认配置页稳定 URL | R4 §8 | A.3 引用归位 |
| T-R4-2 | stompjs 7.3.0 断线自动重订阅语义官方文档位置 | R4 §8 | STOMP 封装定稿前 |
| T-R4-3 | openapi-typescript 对 Springdoc 2.8.17 产出端到端兼容性 | R4 §8 | 后端首个 OpenAPI 端点可用后演练 |
| T-R4-4 | Element Plus 在 pnpm 12 workspace 下 dayjs 最小显式依赖集 | R4 §8 | workstation 脚手架初始化 |
| T-R5-1 | Testcontainers 官方无 GHA 专页（以 runner-images 预装 Docker 为依据），首跑 verify 实测 | R5 §5-1 | CI 首跑 |
| T-R5-2 | palantir-java-format 在 spotless 3.4.0 的内置默认版本号 | R5 §5-2 | 本地首跑 spotless:check 确认 |
| T-R5-3 | pre-commit-hooks 官方钩子具体 tag（当前 v6.0.0 已核实，后续 autoupdate 锁定） | R1 §5 | 实施期 `pre-commit autoupdate` |

## TODO 工单

| 编号 | 事项 | 说明 |
| --- | --- | --- |
| W-3 | P0 工程骨架落盘 | **实施计划见 `docs/plans/2026-09-08-P0实施计划.md`（PLAN-P0-01，PR-1 即本工单，新会话以该计划为执行输入）**：Maven 多模块骨架 + `deploy/` 全量编排 + web monorepo 脚手架，按计划 PR-1 交付物清单与验收标准执行；落地时对齐三项：Dockerfile COPY glob 拍平修正、web 产物路径以宪法 `web/apps/<app>/dist` 为准、移除 ci.yml 骨架期排除项 |
