# TASK.md（登记台）

> 登记规则（根 AGENTS.md §7）：`{待调研项}`（检索不可得，注明原因与回填时点）、`{待决策项}`（需用户 / 总 Spec 裁决）、TODO 工单。条目回填后即删除；来源编号对应 `docs/agmds-research/` 调研报告（R1=CI链方案、R2=Java与SpringBoot栈、R3=数据与集成基础设施、R4=前端栈、R5=构建测试与CI落地细则）。

## 待决策项（阻塞对应工作，须先裁决）

| 编号 | 事项 | 背景 / 调研建议 | 影响范围 |
| --- | --- | --- | --- |
| D-2 | Spring Modulith 引入与否 | R2 §7.2：ArchUnit 1.5.0 已定为边界守护第一步；Modulith 1.4.13（Boot 3.5 世代）为增量框架候选，提供事件发布注册表等能力；引入属增量框架决策，须总 Spec 修订裁决，若引入必须锁 1.4.x | backend Part B.3 可靠事件投递实现成本 |
| D-3 | iot-simulator 实现语言与代码位置 | **已按默认裁决执行（2026-09-10，用户未响应 ask_question，取计划默认项，可推翻）**：Java + Maven 子模块 `backend/iot-simulator`，复用父 POM 工具链与 Dockerfile 策略，images job 第三构建步骤同构追加；若用户改判 Node 顶层目录，改动面=simulator 模块位置+独立 Dockerfile+CI 构建步骤+compose 镜像名 | CI images job、deploy compose、PR-4 B4.4 |
| D-4 | OWASP 周审失败处置流程 | R5 §5-4：fail 后开 issue 还是仅通知，属流程决策 | security.yml |
| D-5 | 方案 C 二期演进（CodeQL / Trivy / dependency-review / Renovate/Dependabot / SonarQube） | R1 §3.3/§4：用户已定方案 B 上线基线、C 为二期；SonarQube 若引入需 CI 双 JDK（17 构建 + 21 扫描）；Renovate vs Dependabot 二选一（倾向 Renovate 的 monorepo 分组能力） | security.yml 扩展、仓库设置 |
| D-6 | 宪法 `enum/` 包目录命名与 Java 保留字冲突 | **已按默认裁决执行（2026-09-09，用户未响应 ask_question，取推荐项，可推翻）**：枚举包目录改 `enums/`——B.1/C.3 正文随 PR-3 修宪提交更新，已建的 `enum/.gitkeep` 目录同步改名；若用户改判 enumeration/ 或其他，改动面=目录名+包名+宪法正文，一次替换可回收 | backend 全部 20 模块目录结构、PR-3 枚举类落位 |
| D-7 | 幂等前置去重 NX 误判丢消息窗口的补救策略 | **已按默认裁决执行（2026-09-09，用户未响应 ask_question，取推荐项，可推翻）**：消费范式改为「Redis NX 失败时回查 received_event 表（唯一索引查询），确认已处理才跳过」——彻底消除丢消息窗口、保持 at-least-once；代价为每条重复消息一次 DB 点查。若用户改判缩短 TTL 或维持现状，改动面=MessageIdempotencyServiceImpl 单类+单测 | M20 幂等构件消费范式、PR-3 起全部 @RabbitListener 消费者 |
| D-8 | 宪法 A.5-9 failover 参数语法与实测落码的正文同步 | PR-4 B4.4 实测（IotAmqpReconnectIT 两次 RED 留证）：qpid-jms 2.11 官方语法要求 failover 参数带 `failover.` 前缀（宪法正文裸名写法不可被识别）；`failover.maxReconnectAttempts=-1`（简报预判值，非宪法条文）在 IoTDA 时间戳凭证语义下会永续透明重连，已改 3 次+supervisor 移交（无限重建语义上移消费者层）。代码与 CHANGELOG 已登记（2026-09-11 条目），**宪法正文修订待用户裁决后随 P1 执行**（先记再改流程，正文同步 = 三参数补前缀语法说明 + 有限重试移交语义 + CHANGELOG 该条目归源由"宪法文字"修正为"简报 §1.3 预判"）；若用户裁决不改正文，改动面=零（代码不动，正文保持概称） | backend/AGENTS.md A.5-9、CHANGELOG 2026-09-11 条目 |

## 待调研项（检索不可得 / 需实测，回填后删除）

| 编号 | 事项 | 来源 | 回填时点 |
| --- | --- | --- | --- |
| T-R2-1 | 国内 HIS 行业 ORM 主流度权威统计 | R2 T-1 | 已无决策影响（ORM 已定稿 MyBatis-Plus），仅存档 |
| T-R2-3 | 统一 envelope 响应模式权威出处（本宪法已裁决走 ProblemDetail 路线，仅存档） | R2 T-3 | 已闭环 |
| T-R2-5 | MyBatis-Plus SQL 日志配置细节（MP 配置项承载，替代裸 MyBatis log-impl） | R2 T-5 | P0 实施期 |
| T-R3-1 | `CREATE INDEX CONCURRENTLY` 在 Flyway 11.7.2 的事务外执行兼容性 | R3 T1 | P0 实测 |
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

## 延后事项（IOTDA 联调时点回填，回填后删除）

> 来源：PR-4 延后条款（计划 §1-PR-4 + BRIEF-PR4-01 §1.7/§10 附 8，B4.4 收口登记 2026-09-10）。前提 = 本地 `.env` 无 IOTDA_* 六变量（六变量就绪并完成 `--profile sim` 全链路演示时一并回填删除）；编号 L = 延后（Linkage 联调）。

| 编号 | 事项 | 说明（来源） | 回填时点 |
| --- | --- | --- | --- |
| L-1 | `--profile sim` 全链路演示 | IOTDA_MQTT_HOST / IOTDA_DEVICE_ID / IOTDA_DEVICE_SECRET 三变量用户侧准备就绪后，以 iot-simulator 镜像（PR-4 B4.4 已交付，本地 `docker build -f backend/iot-simulator/Dockerfile -t fuyun/iot-simulator:dev backend`）走通 IoTDA→AMQP→库→WebSocket 演示链路并留演示记录；backend 侧需同步置 FUYUN_IOT_AMQP_ENABLED=true 且 FUYUN_IOT_AMQP_QUEUES 与 IoTDA 推送队列一致（AMQP 消费链默认关闭，终审修复 2026-09-11 已打通 env 透传） | IOTDA 六变量就绪后 |
| L-2 | T-R3-3 真实 IoTDA 端点 10 分钟断链演示（回填结论：本地两级实测已完成） | 本地两级实测已于 PR-4 B4.2/B4.4 完成——supervisor 单测（fake ConnectionFactory 覆盖断链异常→退避重建→恢复续费）+ 本地 broker 断链恢复 IT（IotAmqpReconnectIT：stop_app 断链→connected=0/断链时长增长→退避不雪崩→start_app 重连→新锚点帧落库）全绿；真实端点 + 10 分钟断链窗口演示只能产生于联调时点（本地 broker 无法复现 IoTDA「凭证内嵌时间戳超 5 分钟拒绝建链」服务端语义，强行伪测违反验证纪律），原 T-R3-3 行按登记台规则回填删除 | 与 L-1 同一时点 |
| L-3 | 真实 IoTDA 规则引擎报文映射冻结 | P0 线格式 = CF-7 JSON（AMQP 解析器与 HTTP 兜底同构）；真实 IoTDA 转发报文（messageId/properties/services 结构）到 CF-7 的字段映射随联调批次冻结（BRIEF-PR4-01 §1.3，禁猜测性兼容）；L-3 落地时 iot_consume_error_log.raw_payload 原文接 SensitiveMasker 脱敏或白名单字段提取（终审 Minor 2026-09-11：P0 靠 CF-7 线格式无 PHI 前提实质合规，真实报文映射后必须显式脱敏） | 与 L-1 同一时点 |
| L-4 | 真实积压水位指标（IoTDA 侧最旧未消费消息年龄） | 本地不可测，P0 以断链时长 + 攒批队列填充率 + 消费/重建计数承载（iot.amqp.* 指标词表，B4.3 交付）；真实积压指标随 IOTDA 联调补全（BRIEF-PR4-01 §10 附 8） | 与 L-1 同一时点 |

## TODO 工单

| 编号 | 事项 | 说明 |
| --- | --- | --- |
| W-4 | Flyway 迁移号段归属与版本唯一的 CI 自动校验 | 宪法 A.4.1-2 要求 CI 校验号段归属；PR-2 起号段登记生效（integration=V1–V99、患者 V100–V199、医嘱 V200–V299、系统建议 V300–V399、物联建议 V400–V499，V500 起先登记先占，载体 = 各 PR 简报 + CHANGELOG），校验脚本随 CI 完整化补建（BRIEF-PR2-01 §8-3 建议项） |
| W-5 | 配置 properties record 的 toString 脱敏覆写兜底 | PR-4 B4.2 审核 Minor（2026-09-10）：IotProperties/SecurityProperties 等 record 默认 toString 含 accessSecret/tokenHmacSecret 等敏感字段值——当前全链路无日志调用点、非泄露路径，但作为等保三级纵深防御建议统一覆写 toString 脱敏（敏感字段打码）；随 P1 配置面扩展一并落地，改动面=各 properties 类覆写方法+单测 | backend 各模块 properties/ 包 |
