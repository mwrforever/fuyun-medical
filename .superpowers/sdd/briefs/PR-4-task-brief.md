# PR-4（M14 IoT 通路技术验证）任务简报

| 属性 | 内容 |
| --- | --- |
| 编号 | BRIEF-PR4-01 |
| 日期 | 2026-09-10 |
| 性质 | 实现专员执行输入：PR-4 全部批次（B4.1~B4.4）的逐文件实现规格、TDD 验收指令与红线清单 |
| 唯一 spec | `docs/plans/2026-09-08-P0实施计划.md` §1-PR-4（范围与验收）+ `docs/specs/modules/14-iot.md`（业务口径） |
| 上游状态 | PR-3 已合入 dev（a93179a）：M20 治理构件与 M01 认证/字典/审计全套就位；基线 = mvn verify 全绿 203 单测 + 21 IT，JaCoCo 双核心包（com.fuyun.system.service.impl / com.fuyun.integration.service.impl）LINE=1.00 |
| 执行依据 | `docs/prompt/2026-09-09-loop-P0工程骨架.md` §3-P4（批次 B4.1→B4.4、P4 出口门禁：计划 §1-PR-4 验收全过或延后演示且合入 dev） |

**上游构件速查（实现专员必读的既有代码，禁止重复造轮子）**：

| 构件 | 位置 | 复用方式 |
| --- | --- | --- |
| CF-7 标准遥测消息 | `fuyun-common` `com.fuyun.common.messaging.StandardTelemetryMessage` | 七字段 record（deviceId/metricCode/value/unit/occurredAt/quality/source）；AMQP 主链路解析目标与 HTTP 兜底载荷的统一语言；禁止改字段（V5 已冻结契约行 iot.telemetry.message） |
| 事件信封/编解码 | `com.fuyun.common.messaging.EventEnvelope` / `EventEnvelopeCodec` | 注入 codec：`create(clock, producer, eventType, traceId, payload)` 发布、`fromJson` 消费解析（含合规校验，毒丸抛 IllegalArgumentException） |
| Long→String | `com.fuyun.common.config.JacksonLongToStringConfig` | 经 fuyun-app MessagingConfig 已全局生效，禁止零散处理 |
| 幂等构件 | `com.fuyun.common.messaging.MessageIdempotencyService`（接口 common、实现 integration，D-7 回查语义已落码） | **仅 RabbitMQ 消费（自事件扇出监听器）按接口 javadoc 标准范式调用**；AMQP 主链路（Qpid 客户端确认）不用此构件——其幂等由 iot_telemetry 唯一约束冲突忽略承担 |
| 队列声明构件 | `com.fuyun.integration.api.MessagingGovernance` + `ConsumerQueueSpec` | `@Bean Declarables xxx(governance){ return governance.declareConsumerQueue(new ConsumerQueueSpec("iot", 事件类型)); }`，订阅自动登记 event_registry |
| 发布确认回调先例 | `fuyun-system/internal/SystemEventPublisher`（AFTER_COMMIT + Confirm/Returns 回调 error 告警） | IotEventPublisher 照此模式实现（nack/不可路由 error 日志告警，P0 不自动重发） |
| 标准消费先例 | `fuyun-system/internal/DictPublishedListener`、`fuyun-integration/internal/DeadLetterListener` | @RabbitListener + raw Message 承接 + UTF-8 解码 + codec 解析 + 标准幂等范式；IotFanoutListener 照此实现 |
| 装配惯例 | `fuyun-app/config/MessagingConfig`、`SystemConfig`、`MybatisPlusConfig` | iot 包不在扫描范围，新增 `config/IotConfig` @Import 装配；mapper 加 @Mapper 即被既有 @MapperScan 扫到 |
| IT 模式 | `fuyun-app/src/test/.../SmokeStackIT`、`MessagingGovernanceIT`、`AuthFlowIT` | 三容器（timescale/redis/rabbit，tag 与 compose 一致）+ static @Container + @ServiceConnection + `@ActiveProfiles("test")` + 假 HMAC 密钥经 @DynamicPropertySource 注入 |
| Qpid 依赖 | 父 POM 已锁 `org.apache.qpid:qpid-jms-client:2.11.0`（properties qpid-jms.version），fuyun-app pom 已引入 | fuyun-iot pom 直接引同一 managed 依赖，禁自带版本号 |
| 异常基座 | `com.fuyun.common.exception.BizException` / `ErrorCode` / `GlobalExceptionHandler` | 业务异常抛 `BizException(IotErrorCode.XXX, HttpStatus, message)`，ProblemDetail 自动渲染 |
| traceId / 操作人 | `com.fuyun.common.context.TraceIdFilter` / `OperatorContextHolder` | MDC 键 traceId；STOMP 握手鉴权与兜底端点日志携带 |

---

## 0. 范围与切片口径（P0 切片边界，禁止越界）

PR-4 交付（计划 §1-PR-4 原文六项）：① iot 迁移（设备/绑定表 + 遥测超表 + 压缩/保留策略）；② Qpid JMS 消费链路（工厂 + SmartLifecycle 消费者 + 四路同构解析 + 攒批批量写 + 客户端确认 + 解析失败落 iot_consume_error_log）；③ 上行扇出（遥测入库后业务事件经 fy.topic 发布 + /ws/iot STOMP 端点，P0 至少"遥测摘要"主题）；④ HTTP 兜底端点 `POST /ingest/iotda-fallback`（独立鉴权）+ 消费积压水位/断链时长双指标（Micrometer + actuator 暴露）；⑤ iot-simulator 模拟设备端（D-3 裁决 Java + `backend/iot-simulator`）；⑥ images job 第三镜像构建步骤。IOTDA 真实端到端演示按延后条款处理（本地 `.env` 无 IOTDA_* 六变量时交付代码 + 模拟信封注入 IT，`--profile sim` 演示延后并登记 TASK.md，loop §3-P4 出口门禁原文）。

**明确不在 P0（做了即越界，M14 Spec §6 与计划 §4 对照推导）**：设备/绑定/产品管理 CRUD API 与管理台 UI（FU-M14-02/03 的管理界面部分）、时序查询 REST 与两级连续聚合物化（FU-M14-06/§3.2 P1）、WebSocket 主题订阅数据范围校验与 2 秒窗口按床位合并节流（FU-M14-07 P1 部分）、告警引擎全套（规则/风暴抑制/升级，FU-M14-08 P1）、命令下发（FU-M14-09 P1）、联动规则（FU-M14-10 P1）、数据质量统计（FU-M14-11 P1）、边缘网关管理（FU-M14-12 P1）、运营大屏接口（FU-M14-13 P1）、波形白名单通道（总 Spec D3 默认不落库）、`iot_consumer_stat` 表与消费速率采样落库（P0 双指标由 Micrometer 承载，时序快照表 P1）、consume-errors 查询/重放/放弃管理端点（P0 只建错误落库写路径）、M14 Spec §7 的订阅清单全套（system.dict.published / M04 住院 / M05 输液 / M15 资产 / M02 患者事件消费队列，P0 一律不建——P0 只消费本模块自事件 iot.device.status-changed 演示治理链）、iot_product / iot_metric_dict / iot_gateway / iot_alarm_rule / iot_alarm / iot_command_log / iot_linkage_rule / iot_linkage_log / iot_data_quality_stat 九表（随各 FU P1 交付）、真实 IoTDA 对接联调与 `--profile sim` 全链路演示（延后条款）、NTP 对时前置检查运维项（A.5-9 属部署规范，代码只做告警日志）。

**前端不动**：B4.1~B4.4 无任何 web/ 变更（bigscreen 遥测页属 PR-5 B5.1）；PR-4 的 CI frontend job 应零触发或空跑。

---

## 1. 设计规格

### 1.1 iot 号段裁决与核对结论

- **号段 = V400–V499**（TASK.md W-4 建议值原文"物联建议 V400–V499"；W-4 载体 = 各 PR 简报 + CHANGELOG）。核对结论：现存迁移仅 `fuyun-integration/.../integration/V1~V5` 与 `fuyun-system/.../system/V300~V303`（实测 `find -name "V*.sql"`），其余 18 模块迁移目录均为空占位——**V400–V499 无冲突**。B4.1 首个提交先在 CHANGELOG 登记号段占用（先记再改，W-4 载体要求），迁移文件头注释同步登记（V300 先例）。
- 迁移目录：`backend/fuyun-iot/src/main/resources/db/migration/iot/`（目录与 .gitkeep 已存在；首个 SQL 落地时删除 .gitkeep，PR-2/PR-3 先例）。`spring.flyway.locations` 已含 `classpath:db/migration/iot`、`schemas` 已含 `iot`（application.yml 实测第 33/54 行），无需改配置。
- **禁止 `CREATE INDEX CONCURRENTLY`**（TASK.md T-R3-1 待调研项规避，与 PR-3 §2.1 同口径）；禁修改已应用迁移；索引显式命名 `uk_`/`idx_` 前缀。

### 1.2 超表 DDL 设计（B4.1）

顺序严格按宪法 A.4.1-5："CREATE TABLE → create_hypertable（按天分区）→ 压缩开启与策略 → 保留策略"，**分区列必须入主键/唯一约束**：

1. `CREATE TABLE iot.iot_telemetry (...)`：device_id VARCHAR(64) NOT NULL、patient_id BIGINT NULL、visit_id BIGINT NULL、metric_code VARCHAR(128) NOT NULL（P0 未建 iot_metric_dict，原生编码直传）、value NUMERIC NOT NULL（CF-7 value 字符串解析定型；非法数值按 §1.3 标 BAD 不阻断）、unit VARCHAR(32) NULL、occurred_at TIMESTAMPTZ NOT NULL（分区键）、quality VARCHAR(16) NOT NULL（GOOD/SUSPECT/BAD，值域 = CF-7）、source VARCHAR(16) NOT NULL（IOTDA/HL7，P0 全部 IOTDA）。遥测明细只增：无审计列、无 updated_at 触发器、无 deleted（V302 audit_log 只增先例）。
2. `SELECT create_hypertable('iot.iot_telemetry', 'occurred_at', chunk_time_interval => INTERVAL '1 day');`（按天分区，14-iot Spec §3.2 + 计划 §1-PR-4）。
3. `CREATE UNIQUE INDEX uk_iot_telemetry_device_metric_time ON iot.iot_telemetry (device_id, metric_code, occurred_at);`——三列含分区列 occurred_at，满足 TimescaleDB 唯一约束必须含分区列的硬约束；该索引即写入幂等载体（14-iot §4 遥测行"唯一约束承担写入幂等"）。
4. 压缩：`ALTER TABLE iot.iot_telemetry SET (timescaledb.compress, timescaledb.compress_segmentby = 'device_id, metric_code', timescaledb.compress_orderby = 'occurred_at DESC');` + 策略函数（7 天后压缩）——**函数名 = T-R3-2 实测胜者**（§1.6）。
5. 保留：策略函数（明细 90 天删除）。
6. 索引补充：`CREATE INDEX idx_iot_telemetry_patient ON iot.iot_telemetry (patient_id, occurred_at DESC);`（P1 患者维度查询前置，属建表内含索引非越界）。

**iot_device / iot_binding / iot_consume_error_log** 三表按 V300 DDL 公共约定（雪花 BIGINT id + 审计列 + 触发器复用 `public.fuyun_set_updated_at()`、不建外键、部分唯一索引 WHERE deleted=0），逐字段见 §2。唯一偏离：**iot_device 主键 = device_id VARCHAR(64)（IoTDA 设备标识自然键）**，不用雪花 id——设备身份由外部系统分配，本地代理 id 无消费方（P0 无管理 API），且遥测/绑定/事件 payload 全部以 device_id 关联；偏离理由写入迁移注释与 PR 描述（宪法 A.4.3-16 的 ASSIGN_ID 约束针对代理主键实体，自然键实体不适用）。iot_binding 用雪花 id + `uk_iot_binding_device_bound (device_id) WHERE status = 'BOUND' AND deleted = 0`（"同一设备同一时刻至多一条绑定中"，14-iot §4）。

### 1.3 Qpid 连接与消费者架构（B4.2，宪法 A.5-9/A.5-10 全条款落地）

- **连接工厂**：自建 `JmsConnectionFactory`（org.apache.qpid.qpid.jms.JmsConnectionFactory），与 Spring AMQP RabbitMQ ConnectionFactory 完全隔离（A.5-9"两套连接工厂隔离"）；URI 显式写全 failover 参数：`failover:(amqps://...?...)`——P0 对接本地 broker 为 `amqp://`，真实 IoTDA 为 `amqps://host:5671`（TLS 1.2+，14-iot 调研依据 1）；failover 子参数 **initialReconnectDelay=3000 / reconnectDelay=3000 / maxReconnectDelay=30000**（宪法 A.5-9 原文值），并设 `failover.maxReconnectAttempts=-1`（无限次，官方语义）。`queuePrefetch=1000`（IoTDA 默认，14-iot 调研依据 2）。
- **凭证**：username = accessKey 明文，password = **accessCode + 13 位毫秒时间戳拼接**（IoTDA 服务端校验偏差 >5 分钟拒绝建链，14-iot 调研依据 1）——时间戳在**每次建立连接时刷新**；凭证经 `fuyun.iot.amqp.*` 配置（env `IOTDA_AMQP_ACCESS_KEY/SECRET`）注入，accessSecret 禁入日志。
- **消费者**：每个配置队列一个消费线程，包装为 **SmartLifecycle**（宪法 A.5-9 原文），receive() 阻塞循环 + **JMS Session.CLIENT_ACKNOWLEDGE 客户端确认**。代码注释必须写明两套确认机制的区别（锁定决策 6）：Qpid 客户端确认 = 调用 `message.acknowledge()` 时对其会话内此前全部已消费消息统一确认（批量处理成功后确认，IoTDA 不丢语义）；RabbitMQ @RabbitListener 容器 AUTO 确认 = 监听方法成功返回即由容器确认（宪法 A.5-5）——两套互不相干，iot 消费链路用前者。
- **断链重连 supervisor（T-R3-3 的代码对应物）**：transport 级 failover 重建连接时**不会刷新凭证内的时间戳**，断链超过 5 分钟后 IoTDA 将以时间戳过期拒绝重连——因此不能只依赖 failover 透明重连。消费者实现 JMS ExceptionListener：连接异常 → 记录断链起点（Micrometer gauge）→ supervisor 以 initialReconnectDelay=3s 起步、指数退避至 maxReconnectDelay=30s 的节奏**销毁旧连接并以新时间戳凭证重建连接与 consumer**，恢复后继续消费。单测以 fake ConnectionFactory 覆盖（§3 单测清单）。
- **连接数预算**：实例数 × 每实例连接数 ≤ 32（IoTDA 单凭证上限，宪法 A.5-9 / 14-iot §9）；P0 单实例、连接数 = 配置队列数（≤4），javadoc 声明预算公式与扩容上界。
- **消费处理流程**（四路同构）：receive 帧 → UTF-8 解码 → JSON 解析（遥测形态 = CF-7 七字段 / 状态形态 = §1.5 契约）→ 遥测帧入攒批队列，状态帧即时处理（§1.5）。解析失败（非 JSON / 缺必填字段）→ 落 `iot_consume_error_log`（stage=PARSE，raw 摘要 SHA-256 + 载荷引用，参照 DeadLetterListener 摘要口径）→ **确认抛弃**（acknowledge 该毒丸，不阻塞队列，14-iot FU-M14-01"毒丸隔离"）。
- **攒批写库**：有界内存队列（容量 IotProperties.batch-queue-capacity，默认 5000，满则 receive 侧等待背压）+ 单写线程，触发条件 = 攒够 batch-size（默认 500）或距上次落库超过 batch-flush-interval（默认 2s）；批量写 = mapper XML `INSERT ... ON CONFLICT (device_id, metric_code, occurred_at) DO NOTHING` 多值 INSERT（唯一约束冲突忽略 = 明细层幂等，14-iot §3.1），方法级独立事务（宪法 A.4.2-7"遥测批量落库按 500-5000 条/批独立事务"）；**批量写成功后对最后一条消息 acknowledge()（统一确认本批全部）**，写失败不确认（IoTDA 重推，at-least-once 由唯一约束兜底幂等）。P0 单写线程即天然保序（同设备帧按接收序落库），设备哈希多线程分片 P1 随压测引入（宪法 A.5-8 并发分级压测校准口径）——简化理由写入 javadoc。
- **P0 线格式契约**：解析器以 CF-7 JSON 为线格式（真实 IoTDA 规则引擎转发报文的字段映射——messageId/properties/services 结构——随 IOTDA_* 联调批次冻结，属延后条款关联项，P0 不做猜测性兼容）。
- **enabled 开关**：`fuyun.iot.amqp.enabled` 默认 false（application.yml 公共配置安全默认）——false 时不建连接工厂与消费者 Bean（@ConditionalOnProperty），IOTDA_* 缺失不阻塞应用启动（宪法 B.4-4"网关不可用不得阻塞应用启动"；fail-fast 仅限 DB/Redis/RabbitMQ）；true 时 endpoint/accessKey/accessSecret/queue 列表任一缺失启动即 fail-fast（@Validated JSR-303 + @PostConstruct 校验）。
- **metrics（与 B4.3 交汇）**：Micrometer gauges——`iot.amqp.connected`（0/1）、`iot.amqp.disconnect.duration.seconds`（当前断链持续秒数，连接正常=0）；写入端 counters（processed/error/ack 批次大小）。真实"积压水位=最旧未消费消息年龄"需 IoTDA 侧数据，本地不可得——P0 以内存攒批队列填充率 gauge（`iot.amqp.batch.queue.fill.ratio`）+ 上述断链指标承载"积压水位+断链时长双指标"的本地可测部分，真实积压指标随 IOTDA 联调补全（登记 §10/附）。

### 1.4 STOMP 端点架构（B4.3）

- 依赖 `spring-boot-starter-websocket`（Boot BOM 托管，PR 描述申报），fuyun-iot `config/IotWebSocketConfig`：`@EnableWebSocketMessageBroker` + 内存 SimpleBroker（`/topic`）+ `registry.addEndpoint("/ws/iot")`（根定位层 §7 统一 `/ws/**` 前缀；nginx `/ws/` 升级路由已就绪，deploy/nginx/fuyun.conf 第 30-39 行实测）；纯 WebSocket 传输不做 SockJS fallback（P0 客户端仅 PR-5 bigscreen）。
- **握手鉴权**：HandshakeInterceptor 从 `Authorization: Bearer {token}` 头校验 M01 令牌，失败拒绝握手（401）。校验能力经 **fuyun-system api 新增 `TokenVerifier` 接口**（§4 跨模块小改：`boolean verifyAccessToken(String rawToken)`，TokenServiceImpl 实现、SystemWebConfig 注册 Bean；fuyun-iot 仅依赖 system api 包，宪法 B.2-2 合规）。订阅级数据范围校验 P1（§0 负面清单）。
- **推送语义**：P0 直推——遥测批量落库成功后推一帧汇总（`/topic/iot/telemetry/{wardId}`，wardId 取本批绑定快照；无绑定快照的帧不推送，仅落库）；设备状态事件经 fy.topic 往返（发布→自消费）后推 `/topic/iot/device-status/{wardId}`。多实例经 MQ 扇出推送网关的完整化 P1（14-iot FU-M14-07），P0 单实例直推已满足"至少遥测摘要主题"计划口径；2 秒窗口节流 P1，P0 每批一帧（频率 = 落库频率，非逐帧）。

### 1.5 扇出与状态事件（B4.3）

- P0 唯一发布事件 **`iot.device.status-changed`**（载荷：deviceId、status、occurredAt、wardId 可空）；经 `IotEventPublisher`（信封 codec + RabbitTemplate + Confirm/Returns 回调，SystemEventPublisher 同模式）发 `fy.topic`。V403 迁移在 event_registry 登记（producer=iot、status=ACTIVE、subscriber 置空），消费队列 `q.iot.iot.device.status-changed` 经治理构件声明（先登记后订阅）。
- **状态帧处理**：AMQP 状态帧解析（P0 契约形态：`{"deviceId","status","occurredAt"}`，status ∈ 设备状态枚举值）→ iot_device 存在该设备则更新 status 与 last_online_at/last_offline_at + 发布事件；不存在则 info 日志跳过（P0 无设备注册 API，档案由数据种子提供，14-iot §5 状态机发布点语义）。
- **自事件消费**：`IotFanoutListener`（@RabbitListener，**RabbitMQ 侧走 AUTO 确认 + MessageIdempotencyService 标准范式**——与 AMQP 客户端确认并存，注释写明两套机制）→ recordProcessed 后推 STOMP 设备状态主题。
- **HTTP 兜底**：`POST /ingest/iotda-fallback`（计划 §1-PR-4 路径原样，**不在 /api/v1 前缀下**，14-iot §7 R5-09 修订同口径）——system 认证拦截器挂 `/api/v1/**` 天然不拦截此路径，独立鉴权自建。鉴权 = `X-Iot-Fallback-Token` 请求头与 `FUYUN_IOT_FALLBACK_TOKEN` 环境变量常量时间比对（MessageDigest.isEqual，参考 §1.1 令牌校验先例；P0 不实现 IoTDA 联动规则专用凭证体系，共享密钥头为 P0 等价物，PR 描述申报）。载荷 = CF-7 同形 JSON → 走与 AMQP 同一解析落库管道（source=IOTDA）→ 202；鉴权失败 401（IOT-1001）；解析失败 400 ProblemDetail。**nginx 路由缺口见附：待裁决 1**。

### 1.6 T-R3-2 实测方案（非用户决策，B4.1 内完成并回填）

**结论预判（外部核实，2026-09-10）**：TimescaleDB 自 2.18.0 起 `add_compression_policy()` 已弃用、由 `add_columnstore_policy()` 取代（来源：Tiger Data 官方文档 tigerdata.com/docs/reference/timescaledb/hypercore/add_columnstore_policy——"replaces the deprecated add_compression_policy() from 2.18.0"；GitHub timescale/docs latest 版 add_compression_policy 页面已交叉引用 columnstore）。2.29.2 下预期 **add_columnstore_policy 可用且为非弃用正选**。

**实测协议（以实测为准，预判不豁免实测）**：
1. 落迁移前，在 Testcontainers `timescale/timescaledb:2.29.2-pg16` 实例（与既有 IT 同款容器）跑探针 SQL 并留存输出：`SELECT proname FROM pg_proc WHERE proname IN ('add_columnstore_policy','add_compression_policy') ORDER BY 1;`——两函数均存在时以**非弃用者 add_columnstore_policy 为胜者**；仅存在其一则以存在者胜出；均不可用（意外情形）→ BLOCKED 升级主控，禁止自造函数。
2. V402 迁移以胜者函数落盘（7 天压缩 + 90 天保留两个策略各一调用）。
3. `IotMigrationIT` 断言策略作业真实存在（`timescaledb_information.jobs` 中压缩/保留 policy 行各一、hypertable 行存在、uk 索引 indexdef 含三列）——证据持久化进 CI，防止"本机实测后人走茶凉"。
4. 结论登记：B4.1 收口提交回填 TASK.md T-R3-2 行（删除该登记条目按登记台规则执行）+ CHANGELOG 记录胜者函数与探针证据摘要。视图列名以 2.29.2 实际输出为准（断言意图固定，列名实现期核对，禁止凭文档臆写后不了了之）。

### 1.7 T-R3-3 处置结论（二选一，已定：本地可测边界内实测 + 真实演示延后登记）

**结论：断链重连做两级实测，"IoTDA 真实端点 10 分钟断链"部分延后登记。**

- **代码级（必做，B4.2）**：§1.3 的 supervisor（异常监听 → 新时间戳凭证重建连接）单测全覆盖——fake ConnectionFactory 注入断链异常后按退避节奏重建、恢复后继续消费、重建次数指标递增。
- **本地 broker 实测（必做，B4.4 `IotAmqpReconnectIT`）**：复用三容器模式，测试中经 `execInContainer("rabbitmqctl", "stop_app")` 制造断链 → 断言 `iot.amqp.connected` gauge 归 0 且不雪崩重试（日志/重建计数可控）→ `start_app` 恢复 → 轮询断言 gauge 回 1 且新锚点帧被消费落库。独立成类（stop_app 会中断同容器其他链路，避免与 IotTelemetryPipelineIT 互相污染）。本地 broker 的 failover 参数（3s/30s）行为由此验证。
- **延后部分**：真实 IoTDA 端点 + 10 分钟断链窗口演示，与 `--profile sim` 全链路演示同一时点执行，回填 TASK.md T-R3-3。**理由**：本地无 IOTDA_* 六变量（计划延后条款前提成立）；且本地 broker 无法复现 IoTDA"凭证内嵌 13 位时间戳超 5 分钟拒绝建链"的服务端语义——该语义的代码对策（supervisor 重建凭证）已由单测 + 本地 broker IT 覆盖，真实端到端证据只能在联调时点产生，强行伪测即违反验证纪律。

---

## 2. B4.1 逐文件规格：iot 迁移 + 超表 + 策略实测锁定

**前置动作**：`CHANGELOG.md` 顶部追加条目（先记再改）：① iot 号段 V400–V499 占用登记（W-4 载体）；② 本 PR 表外申报预告（§10 清单）。

**fuyun-iot/pom.xml 本批次无需改**（迁移为纯 resources）。

| 文件 | 规格 |
| --- | --- |
| `fuyun-iot/src/main/resources/db/migration/iot/V400__create_iot_device_and_binding.sql` | 删该目录 .gitkeep。建 `iot.iot_device`：device_id VARCHAR(64) PRIMARY KEY（自然键，§1.2 偏离声明入注释）、node_id VARCHAR(64) NULL、product_id VARCHAR(64) NULL、device_name VARCHAR(128) NOT NULL、device_type VARCHAR(32) NOT NULL、access_mode VARCHAR(16) NOT NULL（A/B/C/D）、gateway_id VARCHAR(64) NULL、ward_id BIGINT NULL、bed_id BIGINT NULL、asset_ref VARCHAR(64) NULL、credential_ref VARCHAR(128) NULL（**密钥明文禁入此表及任何表**，14-iot 红线 4）、status VARCHAR(16) NOT NULL、last_online_at/last_offline_at TIMESTAMPTZ NULL、审计四列 + deleted + updated_at 触发器（复用 V1 公共函数）。索引：idx (ward_id)、idx (status)。建 `iot.iot_binding`：id BIGINT PRIMARY KEY、device_id VARCHAR(64) NOT NULL、patient_id BIGINT NOT NULL、visit_id BIGINT NOT NULL、bed_id BIGINT NULL、ward_id BIGINT NOT NULL、bind_type VARCHAR(16) NOT NULL（FIXED 固定式/MOBILE 移动式）、status VARCHAR(16) NOT NULL（BOUND/UNBINDING/UNBOUND）、bind_reason VARCHAR(255) NULL、unbind_reason VARCHAR(255) NULL、bound_by VARCHAR(64) NOT NULL DEFAULT 'system'、bound_at TIMESTAMPTZ NOT NULL DEFAULT now()、unbound_at TIMESTAMPTZ NULL、审计四列 + deleted + 触发器。唯一：`uk_iot_binding_device_bound (device_id) WHERE status = 'BOUND' AND deleted = 0`；历史只增（UNBOUND 行不物理删，解绑走状态迁移 UPDATE）。索引：idx (patient_id)、idx (bed_id) |
| `fuyun-iot/src/main/resources/db/migration/iot/V401__create_iot_consume_error_log.sql` | 建 `iot.iot_consume_error_log`：error_id BIGINT PRIMARY KEY（雪花）、queue_name VARCHAR(128) NOT NULL、raw_digest VARCHAR(64) NOT NULL（SHA-256 十六进制，DeadLetterListener 同口径）、raw_payload VARCHAR(4000) NULL（载荷引用截断，脱敏后）、error_stage VARCHAR(16) NOT NULL（PARSE/VALIDATE/PERSIST）、error_msg VARCHAR(500) NULL（摘要不带原文敏感值）、status VARCHAR(16) NOT NULL DEFAULT 'PENDING'（PENDING/REPLAYED/ABANDONED）、replay_count INT NOT NULL DEFAULT 0、handled_by VARCHAR(64) NULL、handled_at TIMESTAMPTZ NULL、created_at TIMESTAMPTZ NOT NULL DEFAULT now()、updated_at TIMESTAMPTZ NOT NULL DEFAULT now()、created_by/updated_by + 触发器（有状态迁移生命周期）。索引：idx (status, created_at)、idx (queue_name, created_at)。保留 180 天为容量红线口径（14-iot §9），清理策略 P1 不做 |
| `fuyun-iot/src/main/resources/db/migration/iot/V402__create_iot_telemetry_hypertable.sql` | §1.2 全序列（CREATE TABLE → create_hypertable 按天 → uk 三列唯一索引（含分区列）→ compress 开关 + segmentby/orderby → 压缩策略（T-R3-2 实测胜者函数，after=7 days）→ 保留策略（90 days）→ idx_iot_telemetry_patient）。文件头注释登记 T-R3-2 实测结论与探针证据摘要 |
| `fuyun-iot/src/main/resources/db/migration/iot/V403__seed_iot_event_registry.sql` | 幂等 `INSERT ... WHERE NOT EXISTS`（V5 先例）：`iot.device.status-changed`，producer_module='iot'，payload_desc="设备状态变更（P0 占位载荷：deviceId/status/occurredAt/wardId，正式契约随 P1 设备状态管理冻结）"，subscriber_modules=''，status='ACTIVE' |
| `fuyun-app/src/test/java/com/fuyun/app/IotMigrationIT.java` | failsafe IT，容器三件套同既有模式（§6 基座）：① `timescaledb_information.hypertables` 含 iot_telemetry；② `timescaledb_information.jobs` 含压缩策略与保留策略作业各一（proc_name 按实测视图实际值断言）；③ pg_indexes 中 uk_iot_telemetry_device_metric_time 的 indexdef 同时含 device_id/metric_code/occurred_at（分区列入唯一键红线断言）；④ V400–V401 表与部分唯一索引存在（information_schema/indexdef 断言）；⑤ `integration.event_registry` 含 iot.device.status-changed 行且 producer=iot、ACTIVE（回归 V5 断言口径） |

**B4.1 完成标准**：`mvn -B -ntp verify` 全绿（IotMigrationIT failsafe 过）；TASK.md T-R3-2 行回填 + CHANGELOG 补记实测结论。

---

## 3. B4.2 逐文件规格：Qpid 工厂 + 消费者 + 四路解析 + 攒批写 + 客户端确认 + 错误落库

**fuyun-iot/pom.xml 新增依赖（版本全托管/父 POM 承接，PR 描述申报理由）**：`org.apache.qpid:qpid-jms-client`（父 POM dependencyManagement 已锁 2.11.0，含 jakarta.jms API 传递）、`org.springframework.boot:spring-boot-starter-validation`、`com.baomidou:mybatis-plus-spring-boot3-starter` + `mybatis-plus-jsqlparser`、`org.projectlombok:lombok`（provided）、`com.fuyun:fuyun-integration`（仅消费 api 包 MessagingGovernance/ConsumerQueueSpec，B.2-2 允许，system pom 先例）。

**逐文件（包路径 `com.fuyun.iot`）**：

| 文件 | 规格 |
| --- | --- |
| `enums/`（9 个枚举） | DeviceStatus(INACTIVE/ONLINE/OFFLINE/ABNORMAL/DISABLED，14-iot §5 状态机)、DeviceAccessMode(A/B/C/D)、BindType(FIXED/MOBILE)、BindingStatus(BOUND/UNBINDING/UNBOUND)、ConsumeErrorStage(PARSE/VALIDATE/PERSIST)、ConsumeErrorStatus(PENDING/REPLAYED/ABANDONED)、TelemetryQuality(GOOD/SUSPECT/BAD，code 与 CF-7 字符串一致)、TelemetrySource(IOTDA/HL7，code 与 CF-7 一致)。规范（宪法 A.2-7 / PR-3 先例）：code 字段 + @EnumValue + @JsonValue + static fromCode 双向映射 |
| `api/IotErrorCode.java` | 错误码枚举 implements common ErrorCode：IOT-1001（401，兜底通道鉴权失败）。B4.2 仅登记，B4.3 使用 |
| `properties/IotProperties.java` | record + `@Validated @ConfigurationProperties(prefix="fuyun.iot")`，嵌套 record Amqp：`enabled`（默认 false）、`endpoint`（`@NotBlank(groups=启用组)`——启用时必填的组校验或 @PostConstruct fail-fast，二选一定稿后全类一致）、`accessKey`、`accessSecret`、`queues`（`List<String>`，至少 1 条）、`queuePrefetch`（默认 1000）、`batchSize`（默认 500，约束 500–5000 区间）、`batchFlushInterval`（默认 2s）、`batchQueueCapacity`（默认 5000）、`reconnectInitialDelay`（默认 3s）、`reconnectMaxDelay`（默认 30s）；嵌套 record Fallback：`token`（`${FUYUN_IOT_FALLBACK_TOKEN:}`，B4.3 使用）。fuyun-app application.yml 增 `fuyun.iot.amqp.*` 占位（endpoint = `${IOTDA_AMQP_ENDPOINT:}` 等三变量映射既有 env 名，默认全空 + enabled:false 安全默认；禁止任何 profile 出现明文凭据默认值）。**禁 @Value 散落** |
| `constants/IotMessagingConstants.java` | MODULE="iot"、EVENT_DEVICE_STATUS="iot.device.status-changed"、QUEUE_DEVICE_STATUS="q.iot.iot.device.status-changed"、TOPIC_EXCHANGE="fy.topic"、SHA-256 摘要算法名、遥测/状态帧形态判别键等词表（禁魔法值散落） |
| `entity/` 4 个 | IotDeviceEntity（@TableName("iot.iot_device")，@TableId(value="device_id", type=INPUT)——自然键非 ASSIGN_ID，偏离理由入注释）、IotBindingEntity、IotTelemetryEntity（@TableName("iot.iot_telemetry")，@TableId 无自增——复合唯一由 uk 索引承载，批量写不经 MP 主键）、IotConsumeErrorLogEntity（ASSIGN_ID）；状态/枚举字段用 §3 枚举类型，时间 OffsetDateTime，@Getter/@Setter 禁 @Data |
| `mapper/` 4 个 | 接口 + @Mapper（既有 @MapperScan 覆盖 com.fuyun，勿再扫） |
| `resources/mapper/IotTelemetryMapper.xml` | `insertBatchIgnoreConflict`：`INSERT INTO iot.iot_telemetry (device_id, patient_id, visit_id, metric_code, value, unit, occurred_at, quality, source) VALUES <foreach>(...)</foreach> ON CONFLICT (device_id, metric_code, occurred_at) DO NOTHING`（复杂 SQL 必须 mapper+XML，宪法 A.4.3-15；冲突忽略 = 明细幂等） |
| `internal/TelemetryFrameParser.java` | 帧解析器（四路同构的"P0 同构"落点）：byte[]→UTF-8→JsonNode；含 deviceId+metricCode+value+occurredAt 判为遥测帧 → 映射 StandardTelemetryMessage（value 非数值 → quality=BAD 保留原文；occurredAt 缺失 → 解析失败）；含 deviceId+status+occurredAt 判为状态帧 → 状态 record；均不匹配 → 抛 FrameParseException（毒丸，交调用方落错误日志）。static 纯函数式，无状态 |
| `service/ITelemetryIngestService.java` + `impl/TelemetryIngestServiceImpl.java` | `int ingest(List<StandardTelemetryMessage> batch)`：绑定快照查询（按 deviceId 查 BOUND 绑定，单次批量 in 查询拒 N+1）→ 冗余 patient_id/visit_id → mapper.insertBatchIgnoreConflict（方法级 @Transactional 独立事务）→ 返回实际落库行数（冲突忽略后不影响确认语义）。**本类属数据落库核心：单测全覆盖** |
| `service/IConsumeErrorLogService.java` + `impl/ConsumeErrorLogServiceImpl.java` | `void recordParseFailure(String queueName, String rawText, String stage, String errorMsg)`：SHA-256 摘要 + 载荷 4000 截断 + PENDING 行落库；落库失败 catch 全吞 + error 告警（毒丸隔离优先于留痕失败，DeadLetterListener §5 口径同源，javadoc 声明） |
| `service/IDeviceStatusService.java` + `impl/DeviceStatusServiceImpl.java` | `void apply(DeviceStatusEvent event)`：iot_device 按 device_id 查 → 存在则更新 status + last_online_at/last_offline_at（lambdaUpdate 精确投影）→ 否则 info 跳过；返回 boolean（是否有效设备）供发布器决定是否发事件 |
| `internal/IotAmqpTelemetryConsumer.java` | **SmartLifecycle**（A.5-9）：start = 按队列清单各起一个消费线程（命名线程池，有界，随上下文关闭——B.3-4）；每线程：连接（新时间戳凭证）→ session（CLIENT_ACKNOWLEDGE）→ consumer（queuePrefetch）→ receive() 循环：帧 → parser → 遥测帧入 assembler 有界队列（满则等待背压）；状态帧即时交 IDeviceStatusService + IotEventPublisher；FrameParseException → IConsumeErrorLogService.recordParseFailure → `message.acknowledge()` 抛弃（毒丸隔离）。实现 ExceptionListener：断链 → connected gauge=0 + disconnect 起点 → supervisor 循环按退避（3s→30s 上限）销毁重建连接（新时间戳）→ 恢复后重建 consumer 继续。stop = 停线程 + 关连接（优雅停机顺序符合 A.5-15：先停拉取再排空在途批）。类注释必须写明 CLIENT_ACK 与 RabbitMQ AUTO 的机制区别（锁定决策 6）+ 连接数预算公式（≤32）。**落 internal/（容器驱动入口不外引），JaCoCo 按 BUNDLE 0.80 覆盖（fake JMS 单测承底）** |
| `internal/TelemetryBatchAssembler.java` | 有界 ArrayBlockingQueue + 单 flush 线程：满 batchSize 或距上次落库超 flushInterval → 取出整批交 ITelemetryIngestService → 成功后回调 `ackRunner.run()`（对批末消息 acknowledge 统一确认本批）；失败不 ack（不抛出消费线程——连接层由 supervisor 管，失败计数 metric + error 日志）。同批按接收序提交（单写线程天然保序，javadoc 写明多线程分片 P1） |
| `config/IotAmqpConfig.java` | @ConditionalOnProperty(fuyun.iot.amqp.enabled=true) 下装配：JmsConnectionFactory Bean（URI 显式 failover 三参数 §1.3 + queuePrefetch）、IotProperties 启用（经 fuyun-app IotConfig @EnableConfigurationProperties 统一）、@Import({IotAmqpTelemetryConsumer.class, TelemetryBatchAssembler.class, TelemetryFrameParser 静态无需})；RemoteIpVerifier 无——净配置类 |
| fuyun-app | `config/IotConfig.java` 新增：@Configuration + @EnableConfigurationProperties(IotProperties.class) + @Import(IotAmqpConfig.class)；**fuyun-app/pom.xml 增 fuyun-iot 依赖**（装配刚需，system 先例，PR 描述申报） |
| `application.yml`（fuyun-app） | 增 `fuyun.iot` 配置块（§ properties 行），三 IOTDA env 变量映射 + fallback token 占位 |

**B4.2 单测（surefire，中文 @DisplayName 表达业务意图）**：TelemetryFrameParserTest（遥测帧/状态帧/非数值 BAD/缺字段毒丸/非 JSON 毒丸）；TelemetryIngestServiceImplTest（快照注入正确、无绑定落 NULL、冲突忽略行数、批量 in 查询一次）；ConsumeErrorLogServiceImplTest（摘要/截断/PENDING、落库失败吞并告警）；DeviceStatusServiceImplTest（存在更新/不存在跳过/在线离线时间字段分派）；TelemetryBatchAssemblerTest（条数触发/时间窗触发/满容量背压/成功 ack 回调/失败不 ack）；IotAmqpTelemetryConsumerTest（fake ConnectionFactory/JMS 对象：正常批确认、毒丸落错误日志且 ack、异常触发 supervisor 重建、退避节奏、stop 排空）；IotPropertiesTest（启用时缺参 fail-fast、batchSize 越界拒绝）。

**B4.2 IT（failsafe）**：`IotTelemetryPipelineIT` 骨架（§6.2 步骤 1–4）。

---

## 4. B4.3 逐文件规格：扇出 fy.topic + /ws/iot STOMP + HTTP 兜底 + 双指标

**fuyun-iot/pom.xml 追加**：`spring-boot-starter-websocket`（BOM）、`spring-boot-starter-amqp`（RabbitTemplate/@RabbitListener，BOM）、`io.micrometer:micrometer-core`（BOM，MeterRegistry 类型编译依赖）、`com.fuyun:fuyun-system`（仅 api 包 TokenVerifier，B.2-2 允许）。

**跨模块小改（fuyun-system，PR 描述申报，D-7 先例）**：

| 文件 | 规格 |
| --- | --- |
| `fuyun-system/src/main/java/com/fuyun/system/api/TokenVerifier.java` | 接口（api 包=对外契约唯一出口，B.1）：`boolean verifyAccessToken(String rawToken)`——校验 access 令牌全链（签名/过期/typ/会话存在），通过 true、任何失败 false（不抛异常——WebSocket 握手与 MQ 线程无 ProblemDetail 出口，布尔语义足够；javadoc 声明失败不区分原因防枚举） |
| `fuyun-system/.../service/impl/TokenServiceImpl.java`（存量微改） | implements TokenVerifier 增补该方法（内部委托既有 verify(ACCESS) 捕获业务异常返回 false）；`fuyun-system/.../config/SystemWebConfig.java` 增该 Bean 的接口暴露（既有 ITokenService Bean 声明改多接口或补一行 @Bean，最小改动） |
| `fuyun-system` 单测 | TokenVerifierTest：有效令牌 true、篡改/过期/typ 错/会话删 false |

**fuyun-iot 逐文件**：

| 文件 | 规格 |
| --- | --- |
| `service/ITelemetryPushService.java` + `impl/TelemetryPushServiceImpl.java` | `void pushSummary(List<IotTelemetryEntity> written, Long wardId)`：wardId 为 null 跳过；否则 `messagingTemplate.convertAndSend("/topic/iot/telemetry/" + wardId, 汇总 record)`（载荷轻量 record：deviceId 列表/条数/occurredAt 上界，防 1009 大消息——14-iot §9 载荷轻量化）。**核心包成员：单测全覆盖** |
| `internal/IotEventPublisher.java` | `void publishDeviceStatus(DeviceStatusEvent event)`：codec.create(Clock.systemUTC(), "iot", EVENT_DEVICE_STATUS, MDC traceId, payload record) → rabbitTemplate.convertAndSend("fy.topic", eventType, envelope, CorrelationData)；实现 Confirm/Returns 回调（nack/不可路由 error 告警含 eventId，P0 不自动重发——SystemEventPublisher 同模式）。仅 IDeviceStatusService 返回 true 后调用 |
| `config/IotMessagingConfig.java` | `@Bean Declarables deviceStatusConsumerQueue(MessagingGovernance governance)`（declareConsumerQueue(new ConsumerQueueSpec("iot", "iot.device.status-changed"))，事件已 V403 登记）+ @Import({IotEventPublisher.class, IotFanoutListener.class}) |
| `internal/IotFanoutListener.java` | `@RabbitListener(queues = QUEUE_DEVICE_STATUS)`：raw Message → UTF-8 → codec.fromJson → **标准幂等范式**（tryAcquire(eventId, "iot") false 即 return；业务 = 推 STOMP /topic/iot/device-status/{wardId} + recordProcessed；catch release + rethrow）。注释写明：本监听器走 RabbitMQ AUTO 确认（A.5-5），与 AMQP 主链路客户端确认是两套机制。DictPublishedListener 同构先例 |
| `config/IotWebSocketConfig.java` | @EnableWebSocketMessageBroker：enableSimpleBroker("/topic")、registry.addEndpoint("/ws/iot").addInterceptors(stompHandshakeAuthInterceptor)（无 SockJS）；messageBroker 参数默认 |
| `internal/StompHandshakeAuthInterceptor.java` | HandshakeInterceptor：beforeHandshake 取 `Authorization` 头 Bearer 令牌 → TokenVerifier.verifyAccessToken → false 时 response.setStatusCode(UNAUTHORIZED) 拒绝握手（warn 日志不含令牌值）；afterHandshake 空实现 |
| `internal/IotFallbackAuthService.java` | `boolean isAuthorized(String headerToken)`：头缺失/不匹配 false；MessageDigest.isEqual 常量时间比对 IotProperties.Fallback.token；未配置 token（空串）时一律 false（fail-closed）。单测全覆盖（B4.2 已建 IotErrorCode.IOT-1001） |
| `controller/IotFallbackIngestController.java` | `POST /ingest/iotda-fallback`：鉴权失败抛 BizException(IOT-1001, 401)；成功 = FallbackIngestRequest → StandardTelemetryMessage（source=IOTDA）→ 单帧走 ingestService.ingest(List.of(...)) 同一管道 → 202 + `{accepted: true}` record。参数 @Valid；禁业务逻辑禁 @Transactional |
| `dto/FallbackIngestRequest.java` + `vo/FallbackIngestResponse.java` | record：deviceId/@NotBlank、metricCode/@NotBlank、value/@NotBlank、unit、occurredAt/@NotNull(Instant)、quality（默认 GOOD）；响应 record：accepted boolean。请求与响应分建（A.7-2） |
| `internal/IotAmqpMetrics.java` | Micrometer：gauge `iot.amqp.connected`、`iot.amqp.disconnect.duration.seconds`、`iot.amqp.batch.queue.fill.ratio`（§1.3 尾段）；由 consumer/assembler 状态回写（AtomicReference/AtomicLong 载体，gauge 弱引用注册）。双指标 = 本地可测部分，真实积压指标延后声明见附 |
| fuyun-app `config/IotConfig.java` | @Import 扩展 IotMessagingConfig、IotWebSocketConfig |

**B4.3 单测**：IotEventPublisherTest（信封字段/traceId 透传/nack-不可路由 error——Mockito 验证 convertAndSend）；IotFanoutListenerTest（范式三分支 + 不合规信封上抛）；IotFallbackAuthServiceTest（正确/错误/缺失/未配置四态）；TelemetryPushServiceImplTest（null ward 跳过、主题路径与载荷字段）；StompHandshakeAuthInterceptorTest（通过/拒绝 401）；存量 IT 回归（见 §7 风险行）。

---

## 5. B4.4 逐文件规格：iot-simulator（D-3）+ images 第三构建步骤 + 断链重连实测

**Dockerfile 结论（D-3 要求调研后定）**：**独立 `backend/iot-simulator/Dockerfile`，不扩展 backend/Dockerfile 承载双镜像**——backend/Dockerfile 运行层固定产出 fuyun-app exec jar 单入口，双镜像须两文件；CI 第三构建步骤复用同一 context=backend、file 指向子 Dockerfile。backend/Dockerfile 仅两处小改：pom COPY 清单追加 `COPY iot-simulator/pom.xml iot-simulator/`（依赖预热层命中）+ 删除文件尾 `# TODO(iot-simulator)` 注释行（第 53 行实测）。

**backend/pom.xml**：`<modules>` 增 `<module>iot-simulator</module>`（fuyun-app 之后）。

**backend/iot-simulator/pom.xml**：parent=fuyun-backend；artifactId=iot-simulator；依赖 `org.eclipse.paho:org.eclipse.paho.client.mqttv3`（**表外申报 1.2.5**——技术栈定稿未收录 MQTT 客户端；1.2.5 为 Maven Central 当前稳定行，Eclipse Paho 官方仓库 eclipse-paho/paho.mqtt.java 最新 release，IoTDA 官方设备接入示例的同类 MQTT 3.1.1 客户端）、`ch.qos.logback:logback-classic`（Boot BOM 托管，中文日志 SLF4J 基座）、`com.fasterxml.jackson.core:jackson-databind`（BOM）、测试 `spring-boot-starter-test`（BOM，仅用 JUnit/AssertJ）。插件：maven-jar-plugin（Main-Class 清单）与 maven-dependency-plugin（copy-dependencies 到 target/libs）版本在模块内显式锁定（表外申报：3.4.2 / 3.8.1，Maven Central 稳定行，实现专员落码前以 Central 元数据核实并把核实结果记入 PR 描述）。打包形态 = 薄 jar + libs 目录，ENTRYPOINT `java -cp app.jar:libs/*`（非 Spring 应用，D-3 仅承诺复用父 POM 工具链）。

**simulator 逐文件（包 `com.fuyun.iotsimulator`，纯 Java 零 Spring）**：

| 文件 | 规格 |
| --- | --- |
| `config/SimulatorConfig.java` | record：mqttHost（IOTDA_MQTT_HOST，必填校验）、deviceId、deviceSecret、reportIntervalSeconds（env 可覆盖，默认 5）。static fromEnv(Map<String,String>) 便于单测；缺失必填 → 启动失败并以中文 error 说明缺哪个变量 |
| `telemetry/DeviceCredentialEncoder.java` | 一机一密连接三元组组装：clientId = `{deviceId}_0_0_{timestamp}`、username = deviceId、password = HmacSHA256(deviceSecret, timestamp) 小写十六进制——**算法以华为云官方"MQTT 设备连接鉴权"文档为准**（调研依据未载公式细节，实现时核对官方页并把核对结论写进 javadoc；若联调发现算法出入，改动面仅本类 + 单测）。时间戳 13 位毫秒 |
| `telemetry/TelemetryPayloadBuilder.java` | 物模型 properties/report 上行 JSON：`{"services":[{"service_id":"Monitor","properties":{"heartRate":72,"spo2":98}}]}` 形态（字面量经常量类承载）；确定性伪随机体征序列（同 seed 可回放，便于联调比对） |
| `mqtt/IotdaMqttClient.java` | Paho 封装：ssl/tls（mqttHost 为 ssl:// 时）、automaticReconnect=true、cleanSession=false、connectLostCallback 中文 error 日志、publish(topic, payload, qos=1)；topic 常量 `$oc/devices/{deviceId}/sys/properties/report` |
| `IotSimulatorApplication.java` | main：读 env → SimulatorConfig → ScheduledExecutorService（单线程、命名、有界）周期上行 → JVM shutdownHook 优雅断连（B.3-4 线程池纪律同等适用）；info 日志含 deviceId（**禁打 deviceSecret**） |
| 单测 | SimulatorConfigTest（缺失必填失败/默认值/超参拒绝）、DeviceCredentialEncoderTest（三元组格式/签名确定性/时间戳位数）、TelemetryPayloadBuilderTest（JSON 结构与 service_id/属性键、同 seed 回放一致） |

**backend/iot-simulator/Dockerfile**：多阶段——构建层 maven:3.9-eclipse-temurin-17，COPY 全部模块 pom（与 backend/Dockerfile 同清单含 iot-simulator 自身）→ `RUN mvn -B -ntp dependency:go-offline` → `COPY . .` → `RUN mvn -B -ntp -pl iot-simulator -am package -DskipTests`；运行层 eclipse-temurin:17-jre + curl 安装 + useradd -r -u 1001 fuyun + USER fuyun + COPY jar 与 libs + ENTRYPOINT `java -cp`（与 backend/Dockerfile 七条规范逐条对齐：多阶段/curl/非 root/pom 先拷/跳测试/零密钥）。

**.github/workflows/ci.yml**（锁定决策 2，禁改回 matrix）：images job 在"构建镜像（web）"步骤后追加第三步骤"构建镜像（iot-simulator）"——同构 docker/build-push-action@v7：`context: backend`、`file: backend/iot-simulator/Dockerfile`、`push: ${{ github.event_name == 'push' }}`、tags `ghcr.io/${{ github.repository }}/iot-simulator:${{ github.sha }}` + `:${{ steps.branch.outputs.name }}-latest`、`cache-from/to: type=gha,scope=iot-simulator`、step 级 if 与 backend 步骤同条件（`needs.changes.outputs.backend == 'true' && needs.backend.result == 'success'`——simulator 在 backend/** 下，changes 过滤器天然覆盖，无需改过滤器）；同时删除第 149 行 `# TODO(iot-simulator)` 注释行。job 名仍为 `images`，required check 对齐不动。

**deploy/docker-compose.yml**：**核对结论 = 零必改**——iot-simulator 服务占位（image `fuyun/iot-simulator:${SIMULATOR_TAG:-dev}`、profiles ["sim"]、env 三变量、depends_on backend healthy）与 .env.example 六 IOTDA 变量在 PR-1 已落盘（实测第 154-166 行），本地镜像构建命令（`docker build -f backend/iot-simulator/Dockerfile -t fuyun/iot-simulator:dev backend`）写入 PR 描述即可；唯一可选项 = backend environment 显式透传 `FUYUN_IOT_FALLBACK_TOKEN`（见 §10 变更面）。

**断链重连实测**：`fuyun-app/src/test/java/com/fuyun/app/IotAmqpReconnectIT.java`（§6.3 步骤化规格）。

---

## 6. 端到端 IT 规格（模拟信封注入：AMQP 注入 → 消费 → 落库 → WebSocket 断言）

### 6.1 容器基座（三个 IT 类共用，与既有 IT 完全同款）

三容器 static @Container + @ServiceConnection：`timescale/timescaledb:2.29.2-pg16`（asCompatibleSubstituteFor postgres）+ `redis:8.10.1` + `rabbitmq:4.3.5-management`（挂 `it/rabbitmq.conf`）；`@ActiveProfiles("test")`；`@DynamicPropertySource` 注入：测试资产假 HMAC 密钥（MessagingGovernanceIT 第 99 行先例）+ `fuyun.iot.amqp.enabled=true` + `fuyun.iot.amqp.endpoint=amqp://{rabbitHost}:{amqpPort}`（AMQP 1.0 端口 5672 经 getAmqpUrl 换算 host/port）+ accessKey/accessSecret 测试假值（注释声明"测试资产假凭证"）+ `fuyun.iot.amqp.queues[0]=it.iot.telemetry` + `fuyun.iot.fallback-token` 测试值。**AMQP 1.0 broker 复用既有 RabbitMQ 容器**：RabbitMQ 4.0 起 AMQP 1.0 为核心协议默认启用（来源：rabbitmq.com/blog/2024/08/05/native-amqp、rabbitmq.com/docs/amqp——4.x 无需启用任何插件），Qpid JMS 直连 5672 即可，**不新增容器、不新增镜像**。IT 内以 Qpid JMS 客户端直建 JMS 上下文向 `/queues/{queueName}` 地址投帧（RabbitMQ address-v2 语法，队列按服务端默认类型 quorum 创建）。STOMP 客户端用 spring-websocket `WebSocketStompClient(StandardWebSocketClient)`（tomcat-embed-websocket 已在 starter-web 测试 classpath，零新依赖）连 `ws://localhost:{port}/ws/iot`。

### 6.2 IotTelemetryPipelineIT（B4.2 骨架 + B4.3 扩展，断言链按 @Order 串联）

1. **种子**：经 service 层（代理调用）写 iot_device 一行（device_id=it-dev-001，ONLINE，ward_id=1001）+ iot_binding 一行（BOUND，patient/visit/ward 快照）。
2. **AMQP 注入 → 落库幂等**：Qpid 生产端向 `/queues/it.iot.telemetry` 发 4 帧 CF-7 JSON（两两重复：device+metric+occurredAt 相同）→ 轮询断言 `iot.iot_telemetry` 恰 2 行、patient_id/visit_id 为绑定快照值（消费→解析→快照→批量 ON CONFLICT DO NOTHING→客户端确认全链真实走通）。
3. **STOMP 摘要断言**：先订阅 `/topic/iot/telemetry/1001` → 再发 1 帧新遥测 → 断言收到摘要帧（含 deviceId/metricCode）。
4. **毒丸隔离**：发 1 帧非 JSON + 1 帧缺字段 → 轮询断言 `iot_consume_error_log` 两行 PENDING（stage=PARSE、raw_digest 64 位）→ 发 1 帧合法锚点帧证明消费未阻塞 → 锚点落库。
5. **状态扇出全链（B4.3）**：发状态帧 `{"deviceId":"it-dev-001","status":"OFFLINE","occurredAt":...}` → 轮询断言：iot_device.status=OFFLINE 且 last_offline_at 非空；`integration.received_event` 出现 consumer_module='iot' 且 status=PROCESSED 行（自事件经 fy.topic→治理队列→AUTO+幂等消费）；STOMP 订阅 `/topic/iot/device-status/1001` 收到状态帧。
6. **幂等重投（MQ 侧）**：以已消费 eventId 手工重发同信封至 fy.topic → received_event 该 (event_id, consumer_module) 行数仍 1（标准范式 D-7 语义回归）。
7. **HTTP 兜底（B4.3）**：无 token POST /ingest/iotda-fallback → 401 errorCode=IOT-1001；错 token → 401；对 token + 与步骤 2 同 (device,metric,occurredAt) 键的合法载荷 → 202 → 轮询断言 iot_telemetry 行数不增（兜底与 AMQP 同键唯一约束去重，14-iot §3.1 双通道去重口径）。

### 6.3 IotAmqpReconnectIT（B4.4，T-R3-3 本地实测载体，独立成类防 stop_app 污染）

1. 上下文启动（enabled=true 指向本类容器）→ 发锚点帧 → 断言落库 + `iot.amqp.connected`=1。
2. `rabbitMQContainer.execInContainer("rabbitmqctl", "stop_app")` → 轮询断言 connected=0 且 disconnect.duration.seconds 增长（断链时长双指标之真实验证）。
3. sleep ≥3 秒覆盖 initialReconnectDelay 节奏窗口 → 断言重建计数 metric 有限（不雪崩）。
4. `execInContainer("rabbitmqctl", "start_app")` → 发新锚点帧 → 轮询断言 connected 回 1 且新帧落库（supervisor 新时间戳凭证重建成功）。
5. 稳定性护栏：CI 中若该类抖动，允许放宽轮询上限（≤90s）一次；仍失败按修复循环处理，禁止静默删除或 @Disabled（失效测试零容忍）。

---

## 7. TDD 与验收指令

**TDD 铁律**（loop §7）：每任务先写失败测试（RED）再实现（GREEN）再重构；测试与实现同一次提交；禁止无失败测试先写生产代码。

**父 POM JaCoCo 核心包核对结论与处置**：现行父 POM PACKAGE 级 1.00 规则 include 清单 = `com.fuyun.integration.service.impl` / `com.fuyun.billing.service.impl` / `com.fuyun.system.service.impl`（pom.xml 第 242-246 行实测），**不含 com.fuyun.iot.\***。处置：**B4.2 起（首个 iot service.impl 类落地同一提交）在父 POM 规则增补 `<include>com.fuyun.iot.service.impl</include>`**——iot 消费落库链属"对外服务接口（第三方对接）"核心功能（全局 §四核心界定 + 宪法 C.5-2"核心包 rule 随模块实装逐步声明"既有模式）；PR 描述申报该 POM 改动；SmartLifecycle 消费器/监听器/解析器落 internal/ 包按 BUNDLE 0.80 承载（1.00 规则不误伤难测基础设施类）。iot-simulator 独立 BUNDLE：LINE ≥0.80（payload builder/凭据编码器单测承底）。

| 批次 | 完成标准 |
| --- | --- |
| B4.1 | `cd backend && mvn -B -ntp verify` 全绿（IotMigrationIT failsafe 过、既有 203 单测 + 21 IT 回归绿）；TASK.md T-R3-2 回填 + CHANGELOG 号段/实测结论条目 |
| B4.2 | 同上门禁全绿 + 新增 iot 单测绿 + **父 POM 核心包增补后 `com.fuyun.iot.service.impl` PACKAGE LINE=1.00 生效且达标**；存量 IT 回归绿（fuyun-app 引入 IotConfig 后全部 IT 上下文新增 iot Bean——enabled 默认 false 下必须零连接尝试、零行为差异，AuthFlowIT/DictBroadcastIT/MessagingGovernanceIT/SmokeStackIT 全绿即回归证据） |
| B4.3 | 同上 + IotTelemetryPipelineIT 全 7 步过；fuyun-system TokenVerifier 单测绿 |
| B4.4 | 同上 + IotAmqpReconnectIT 过 + simulator 单测绿 + images job 三构建步骤在 PR checks 真跑绿（`images` 名对齐）+ backend/Dockerfile 变更后 backend 镜像构建仍绿 |
| PR-4 整体 | loop §3-P4 出口门禁：五 checks（backend/verify、frontend/verify、images、commitlint、hygiene）绿 + /code-review 无 findings + 合入 dev；`.env` 有 IOTDA_* 六变量 → `--profile sim` 全链路演示记录；缺失 → 代码 + 模拟信封 IT 已交付且**延后事项登记 TASK.md**（profile sim 演示 + T-R3-3 真实 10 分钟断链 + 真实 IoTDA 报文映射冻结，三条一并登记） |

调试单 IT 命令（宪法 C.4）：`mvn -B verify -Dit.test=IotTelemetryPipelineIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`。

---

## 8. 提交切分建议（conventional commits，中文 subject，逻辑顺序）

1. `docs(changelog): 登记 iot 号段 V400–V499 占用与 PR-4 表外申报预告（先记再改）`
2. `feat(iot): 设备档案绑定表与消费错误日志迁移（V400–V401）`
3. `feat(iot): 遥测超表与压缩保留策略迁移（V402，T-R3-2 实测锁定）`
4. `feat(iot): IoT 设备状态事件种子登记迁移（V403）`
5. `test(iot): 迁移与策略作业断言 IT`
6. `feat(iot): Qpid 连接工厂与 AMQP 遥测消费者（客户端确认与断链 supervisor）`
7. `feat(iot): 遥测解析校验与攒批冲突忽略落库`
8. `feat(iot): 消费错误日志落库与毒丸隔离`
9. `chore(backend): JaCoCo 核心包增补 iot service.impl（随模块实装声明）`
10. `feat(iot): 设备状态事件发布器与自事件幂等消费`
11. `feat(system): TokenVerifier 接口暴露（跨模块握手鉴权契约，申报项）`
12. `feat(iot): /ws/iot STOMP 端点与遥测摘要推送`
13. `feat(iot): HTTP 兜底端点独立鉴权与积压断链双指标`
14. `test(iot): 遥测管道端到端 IT（AMQP 注入至 WebSocket 断言）`
15. `test(iot): AMQP 断链重连 IT（T-R3-3 本地实测）`
16. `feat(iot-simulator): 模拟设备 Maven 子模块与独立 Dockerfile`
17. `chore(ci): images job 追加 iot-simulator 第三构建步骤（保持非 matrix）`
18. `chore(deploy): fallback 凭证变量透传（如裁决纳入，见附 1）`
19. `chore(changelog): 登记 PR-4 M14 IoT 通路变更条目与 T-R3-3 回填`

---

## 9. 红线清单（实现与审核双用，违者不得合入）

1. **遥测不经 RabbitMQ**（总 Spec D2 / 14-iot 模块红线 2）：AMQP 主链路（Qpid→TimescaleDB）与 MQ 事件总线物理隔离；本模块业务事件只走 fy.topic 三件套，**禁止私建交换机**、消费队列声明一律走 MessagingGovernance 构件（先登记后订阅）。
2. **宪法 A.5-9 全条款**：与 Spring AMQP 完全连接隔离（自建 ConnectionFactory + 独立 `iot.amqp.*` 配置前缀 + 独立生命周期）；URI 显式 failover 三参数 initialReconnectDelay=3000/reconnectDelay=3000/maxReconnectDelay=30000；消费者包装为 SmartLifecycle；连接数预算"实例数 × 每实例连接数 ≤ 32"（javadoc 声明公式与上界）；凭证仅 env 注入；NTP 对时为部署前置（代码侧仅时间偏差告警日志）。
3. **宪法 A.5-10 全条款**：IoTDA 服务端仅缓存 24h/1GB——消费侧批量化 + 快速落库；"积压水位 + 断链时长"双指标 Micrometer 暴露；HTTP 兜底通道保留（独立鉴权，禁走 M01 令牌）。
4. **确认语义注释强制**：Qpid 客户端确认（批处理成功统一 acknowledge；解析失败落 iot_consume_error_log 后确认抛弃）与 RabbitMQ 容器 AUTO 确认（A.5-5）是两套机制，IotAmqpTelemetryConsumer 与 IotFanoutListener 类注释必须各写明本方语义；iot 消费链路一律客户端确认。
5. **迁移红线**：禁修改已应用迁移；禁 CREATE INDEX CONCURRENTLY（T-R3-1 规避）；号段 iot=V400–V499 先登记（CHANGELOG）后使用；超表顺序 CREATE TABLE→create_hypertable→压缩→保留；**分区列 occurred_at 必须在唯一键三列内**（IT 断言兜底）；策略函数以 T-R3-2 实测胜者落盘；CREATE EXTENSION 不在 Flyway（deploy/initdb 承担，A.4.1-5）。
6. **敏感信息**：accessSecret/deviceSecret/兜底 token 禁入日志、异常消息与测试断言（测试假凭证须注释声明为测试资产）；iot_consume_error_log.raw_payload 脱敏截断；日志中文且含 deviceId 等业务标识。
7. **配置纪律**：禁 @Value 散落——全部走 IotProperties（@ConfigurationProperties + @Validated，fuyun.* 前缀）；enabled=false 默认安全姿态，任何 profile 禁明文凭据默认值。
8. **注入与事务**：构造器注入强制（禁 @Autowired 字段）；@Transactional 仅 service impl 方法级（批量落库独立事务），controller 禁加；事务内禁 MQ 发送（发布器 AFTER_COMMIT 或事务外调用）。
9. **幂等范式分域**：RabbitMQ 消费（IotFanoutListener）必须走 MessageIdempotencyService 标准范式（tryAcquire/recordProcessed/release，D-7 语义）；AMQP 主链路明细幂等 = iot_telemetry 唯一约束 ON CONFLICT DO NOTHING——两域不得混用、不得在 AMQP 链路误用 Redis 前置键。
10. **MP 纪律**：三大插件已注册勿重复；单表 lambdaQuery 链式 + select() 精确投影；批量 ON CONFLICT 写走 mapper XML（A.4.3-15）；实体 @Getter/@Setter 禁 @Data（自然键 @TableId(type=INPUT) 偏离已申报）。
11. **覆盖门禁**：`com.fuyun.iot.service.impl` PACKAGE LINE=1.00（B4.2 起生效）；各模块 BUNDLE ≥0.80 含 iot-simulator；测试命名表达业务意图、断言针对业务结果；因本 PR 失效的存量测试必须同步改造。
12. **装配纪律**：iot 包不进组件扫描——经 fuyun-app IotConfig @Import；Bean 装配缺失=启动失败而非静默不生效；存量 IT 回归绿为引入 IotConfig 的前置验收。
13. **流程红线**：先记再改（CHANGELOG：号段、T-R3-2 结论、POM 核心包增补、跨模块小改、表外依赖）；TDD 先行；批次过审后推进；实现专员严禁并行；前端零变更。
14. **范围红线**：§0 负面清单越界即打回（尤其：禁止顺手实现告警引擎/绑定 CRUD/连续聚合/订阅清单全套）。

---

## 10. 决策记录与表外申报清单

**已锁定决策（原样贯彻，禁止重开）**：

| 决策 | 状态与内容 | 推翻改动面 |
| --- | --- | --- |
| D-3（TASK.md 待决策表已登记 2026-09-10 默认裁决生效） | iot-simulator = Java + Maven 子模块 `backend/iot-simulator`，复用父 POM 工具链；Dockerfile 结论 = 独立子模块 Dockerfile（§5） | simulator 模块位置 + 独立 Dockerfile + CI 构建步骤 + compose 镜像名 |
| CI images job | 第三镜像 = 既有 images job 追加第三个同构显式构建步骤，禁改回 matrix（required check 名 `images` 对齐）；changes 过滤器不动（backend/** 天然覆盖） | ci.yml 单文件 |
| Qpid 全约束 | 宪法 A.5-9/10 原文贯彻（§1.3） | 连接工厂/消费者/配置三处 |
| 超表顺序与唯一键 | §1.2 定稿 | V402 单文件 |
| T-R3-2 | 非用户决策；实测协议 §1.6，预判 add_columnstore_policy 胜出 | V402 单文件 + TASK.md 回填 |
| T-R3-3 | §1.7 结论：supervisor 单测 + 本地 broker IT 实测；真实 IoTDA 10 分钟演示延后登记 | IotAmqpReconnectIT + TASK.md 回填 |
| AMQP 确认语义 | 客户端确认（CLIENT_ACK），与 RabbitMQ AUTO 两套并存、注释写明（锁定决策 6） | 消费者/监听器注释 |
| STOMP 依赖 | spring-boot-starter-websocket（BOM 托管），PR 描述申报（锁定决策 7） | fuyun-iot pom + IotWebSocketConfig |
| IOTDA_* 延后条款 | 代码 + 模拟信封注入 IT 交付；profile sim 演示延后登记 TASK.md（锁定决策 9） | TASK.md 三条登记 |

**表外申报清单（全部写入 PR 描述，按首次出现批次标注）**：

| 类别 | 项 | 版本与来源 | 批次 |
| --- | --- | --- | --- |
| 新依赖（BOM 托管零版本声明） | fuyun-iot：starter-validation / starter-websocket / starter-amqp / micrometer-core / mybatis-plus-spring-boot3-starter / mybatis-plus-jsqlparser / mapstruct / lombok / logback-classic（simulator）/ jackson-databind（simulator）/ spring-boot-starter-test（simulator） | Boot BOM 3.5.16 托管 | B4.2/4.3/4.4 |
| 新依赖（父 POM 已锁） | qpid-jms-client 2.11.0（技术栈定稿 §3.2 表内，fuyun-iot 直接引用） | 父 POM properties | B4.2 |
| 新依赖（表外） | org.eclipse.paho:org.eclipse.paho.client.mqttv3 **1.2.5** | Maven Central 稳定行（Eclipse Paho 官方最新 release，2026-09-10 核实）；定稿文档未收录 MQTT 客户端 | B4.4 |
| 构建插件（表外） | maven-jar-plugin 3.4.2 / maven-dependency-plugin 3.8.1（simulator 模块锁定） | Maven Central 稳定行，实现期核实后记 PR 描述 | B4.4 |
| POM 改动 | 父 POM：modules 增 iot-simulator + JaCoCo 核心包 include 增 com.fuyun.iot.service.impl；fuyun-app pom：增 fuyun-iot 依赖；fuyun-system：api 增 TokenVerifier + TokenServiceImpl 微改（跨模块小改，D-7 先例） | — | B4.2/4.3/4.4 |
| Dockerfile | 新增 backend/iot-simulator/Dockerfile；backend/Dockerfile 增 1 行 pom COPY + 删尾部 TODO 注释 | — | B4.4 |
| CI | ci.yml images job 增第三构建步骤 + 删 TODO 注释行（job 名/过滤器/其余 job 零改动） | — | B4.4 |
| deploy | .env.example 增 `FUYUN_IOT_FALLBACK_TOKEN=`（空占位 + 独立行中文注释）；compose backend environment 增同名透传一行；nginx fuyun.conf /ingest 路由（**待裁决附 1，默认纳入**）；docker-compose iot-simulator 服务与六 IOTDA 变量核对结论=已就绪零必改 | — | B4.3/4.4 |
| CHANGELOG | 号段 V400–V499 登记；T-R3-2 实测结论；核心包增补；跨模块小改；收尾 PR-4 总条目 | — | 各批次 |
| TASK.md | T-R3-2 回填（B4.1）；T-R3-3 回填 + 延后三条登记（B4.4/收口） | — | B4.1/4.4 |

---

## 附：待裁决 / 关注项汇总（回报主控）

1. **nginx /ingest 路由缺口**：spec 定稿的兜底端点路径为 `/ingest/iotda-fallback`（不在 `/api/v1` 下），而 deploy/nginx/fuyun.conf 仅有 `/api/` 与 `/ws/` 两条反代——唯一公网入口原则下 IoTDA 联动规则推送将 404。简报推荐方案：PR-4 在 fuyun.conf 增 `location /ingest/` 反代（3 行，表外 deploy 变更）；备选：改端点路径入 `/api/v1/iot/`（偏离 spec 与计划原文，不推荐）或延后至 IoTDA 联调批次（登记 TASK.md）。**简报按"本 PR 纳入 nginx 变更"编排（§8 第 18 条），主控可改判。**
2. **JaCoCo 核心包增补为父 POM 修改**：把 `com.fuyun.iot.service.impl` 纳入 1.00 规则（§7）是宪法 C.5-2"随模块实装逐步声明"的既有模式延伸，但属父 POM 门禁变更——若主控裁定 P0 不纳入，需同步下调 B4.2 验收口径并在 P1 M14 完整化时补门禁；简报默认纳入。
3. **STOMP 握手鉴权跨模块小改**：fuyun-system api 增 TokenVerifier 接口（§4）——新增对外契约方法，PR 描述申报；若主控裁定 P0 免握手鉴权，违反 14-iot §9 安全红线（握手鉴权），简报不支持该方向，仅备案。
4. **P0 线格式 = CF-7 JSON**：真实 IoTDA 规则引擎转发报文的字段映射（messageId/properties/services 解析）未实现，随 IOTDA_* 联调批次冻结——属延后条款的自然延伸，需在 TASK.md 延后登记中与 sim 演示并列。
5. **一机一密 password 算法待官方核对**：HmacSHA256(secret, timestamp) 为调研预判，简报已要求实现专员以华为云官方 MQTT 连接鉴权文档核对并在 javadoc 记录核对结论（§5 DeviceCredentialEncoder 行）；真实联调前无法二次验证，与延后条款绑定。
6. **ledger 状态滞后**：`.superpowers/sdd/ledger.md` 阶段总表 P2=in_progress、P3=pending 与实际（PR-3 已合入 dev@a93179a、P4 分支已建）不一致——非本简报职责，主控续传台账时修正。
7. **loop 文档 B4.4 表述"images 第三 matrix"** 与 ci.yml 现状"禁用 strategy.matrix"注释冲突——已按锁定决策 2（第三显式构建步骤）执行，loop 表述判定为历史概称，无行动项。
8. **双指标的真实积压水位局部缺失**：IoTDA 侧"最旧未消费消息年龄"在本地不可测，P0 以断链时长 + 内存批队列填充率 + 消费计数承载（§1.3 尾段）；真实积压指标随 IOTDA 联调补全，建议并入 TASK.md 延后登记第四条。
