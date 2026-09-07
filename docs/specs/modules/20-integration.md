# M20 集成平台与数据服务 · 功能实现 Spec

| 属性 | 内容 |
| --- | --- |
| 模块编号 | M20 |
| Maven 模块 | `fuyun-integration`（schema：`integration`） |
| 版本 / 状态 | v1.1 / 统一审查修订（2026-09-07） |
| 上游依赖 | M01（接口账号/权限/审计/通知/主数据源）、M02（患者匹配查询）、M14（设备档案匹配与设备-患者-床位绑定查询——同步 API）；模式 D 遥测移交经 fuyun-common 定义的 SPI 接口调用（M14 注册实现，本模块对 M14 零编译期依赖） |
| 下游被依赖 | 全部模块（事件总线治理、死信处理、出站留痕；幂等构件接口下沉 fuyun-common，实现由本模块运行时装配，各模块零编译期依赖）；M18/M19（上报任务骨架） |
| 对应总 Spec | FU-M20-01 ~ FU-M20-07 |

---

## 1. 模块定位与边界

**职责**：本模块是全系统对外的"协议门户"与消息治理中枢。四项核心职责：① 集成引擎——以通道（channel）为单位统一承接 HL7 v2 MLLP、Web Service、REST、文件交换四类对外协议的收发、解析、转换、路由与应答；② 事件总线治理——RabbitMQ 交换机/队列/事件的命名治理、事件信封规范、幂等消费基建、死信统一处理、失败重推与事件流转可追溯；③ 主数据分发治理——M01 主数据广播事件的订阅登记、全量初始化、版本对账与补偿重发；④ 接口监控对账——对外接口调用留痕、成功率/耗时监控、失败重推。

**非职责**：业务事件的生产与消费语义（哪个模块发什么事件、收到事件后做什么业务，归各业务模块自己）；设备遥测的解析、落库与业务化（归 M14，本模块在模式 D 仅做协议转换后移交）；主数据本身的定义与维护（归 M01，本模块不复制、不存主数据业务值）；医保/财务的业务对账（归 M13）；区域平台上报的数据集内容加工（归 M19/M09 等，本模块提供任务与传输骨架）。

**模块红线**：
1. 本模块治理事件"信封与路由"，不解读事件载荷的业务含义；不合规信封的消息拒收并留痕，不得放行。
2. 禁止直接读写其他模块 schema；跨模块交互一律走服务接口或事件（幂等表等治理表归 `integration` schema；幂等构件接口下沉 `fuyun-common`，实现由本模块运行时装配，各模块对本模块零编译期依赖）。
3. 主数据权威源唯一（M01），本模块分发治理动作（对账/重发）不得修改主数据本身。
4. 全系统只允许存在治理约定的三个交换机（见第 8 节治理约定），任何模块不得私建交换机。

## 2. 调研依据

1. 医院集成平台由 **ESB（同步服务路由）、集成引擎 IE（异步消息、支持 HL7/CDA 等医疗标准）、ETL（数据集成）** 三类技术组合构成；大型三甲平台日均消息量可达 9000 万条、峰值 1000 TPS，各角色必须冗余高可用。（来源：https://www.smartx.com/blog/2021/06/hospital-integration-platform/ ）
2. 集成平台建设实践：以集成总线承载系统间交互，主数据（科室、病区、员工、床位、诊断、药品、项目、耗材）由主数据服务平台统一规则管理，各业务系统数据视为"主数据的映像"；平台配套实时监控（服务数据、消息路由、性能数据可视化）。（来源：https://www.cnblogs.com/Javame/p/12160752.html 、https://www.cnblogs.com/Javame/p/14010268.html ）
3. 主数据管理实践（巴彦淖尔市医院）：字典分类集中管理、由平台统一分发解决系统间数据一致性。（来源：https://www.chima.org.cn/Html/News/Articles/6196.html ）
4. 集成平台运维与问题管理（俞磊/CHIMA）：运维六方面（基础设施/集成引擎/应用/数据/用户行为/环境变化管控）；接口问题常见类型为适配器故障、消息过大、异常消息处理不当；问题管理五环节"发现→登记→排查→处理→跟踪"，监控预警经短信/企业微信推送。（来源：https://www.chima.org.cn/Html/News/Articles/16719.html ）
5. Mirth Connect 是 Java 实现的开源 HL7 接口网关（行业事实标准），核心抽象为"通道 = 单一源连接器 + 多目标连接器"的消息管道，支持转换（HL7→XML 等）、过滤与脚本；生产部署需外置元数据库、日志清理与安全加固；被 NextGen 收购后开源许可持续收紧，社区已出现"不再开源"的迁移服务。（来源：https://github.com/nextgenhealthcare/connect 、https://blog.csdn.net/gitblog_00708/article/details/148575973 、https://www.cnblogs.com/runqinshiye/p/10791939.html 、https://www.meditecs.com/mirth-connect-migration/ ）
6. HAPI 是 Java 生态的成熟开源 HL7 v2 解析库（解析/编辑/校验/传输），可作为自研通道的解析底座；HL7 v2 至今仍是医院间与设备对接的主力交换标准。（来源：https://hapifhir.github.io/hapi-hl7v2/ 、https://www.linkedin.com/pulse/hl7-v2-isnt-dead-why-hospitals-still-run-1989-standards-2025-tkbxc ）
7. IHE-PCD 设备报文：监护设备体征/波形用 ORU^R01（PCD-01），报警用 ORU^R40（PCD-04，"需要及时人工干预的可报警观察"），报文经 MLLP/AMQP 传输，术语对齐 Rosetta/MDC；"医疗设备到 HL7"模式即设备读数变换为 ORU^R01 后经 MLLP 发往目标系统。（来源：https://pmc.ncbi.nlm.nih.gov/articles/PMC11955343/ 、https://hl7-definition.caristix.com/v2/HL7v2.8/TriggerEvents/ORU_R40 、https://www.ibm.com/docs/zh/SSMKHH_9.0.0/com.ibm.healthcare.pattern.devices.doc/pattern/design.htm ）
8. RabbitMQ 消费可靠性最佳实践：消费端必须幂等（以唯一业务键/消息 ID 去重），手动确认，失败有限重试后进死信队列人工/补偿处理；网络抖动决定 MQ 只能保证至少一次，Exactly-Once 靠业务幂等实现。（来源：https://support.huaweicloud.com/bestpractice-rabbitmq/bp-0015.html 、https://help.aliyun.com/zh/apsaramq-for-rabbitmq/user-guide/retry-policies-and-dead-letter-queues 、https://interview.javaguide.cn/high-performance/message-queue-interview-questions.html ）
9. 互联互通测评：四级甲等要求医院信息集成平台接入至少 31 个系统、数据资源标准化；基于 FHIR 的互联互通研究显示已通过四甲的平台大多以 CDA/数据集改造完成，FHIR 为演进方向。（来源：https://www.hit180.com/54311.html 、https://www.chima.org.cn/Html/News/Articles/4536.html 、https://www.cnblogs.com/Javame/p/17633003.html ）
10. 区域平台上报：国家要求区域全民健康信息平台与医院信息平台使用统一数据接口实现共享交换，医院侧按数据集标准向区域平台上报。（来源：https://www.ndcpa.gov.cn/jbkzzx/c100030/common/content/content_1658745812574605312.html 、https://www.nhc.gov.cn/wjw/c100175/202010/14895c341c2b42e492638067c4d09415.shtml ）

## 3. 方案推导（关键设计点选型）

### 3.1 HL7 引擎路线：嵌入 Mirth Connect vs 自研轻量通道（HAPI 解析 + 配置化管道）vs 商业引擎

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 嵌入 Mirth Connect | 独立部署开源集成引擎，通道可视化编排现成 | 优势：HL7 生态成熟、源/目标连接器模型与转换脚本开箱即用。风险：独立进程 + 独立元数据库，运维面扩大；其管理台与本系统权限/审计体系割裂；转换脚本游离于本项目 CI 之外、质量与回归不可控；开源许可收紧存在断供风险（调研依据 5） |
| 商业引擎（Rhapsody 等） | 商业集成引擎 | 许可成本高、按"接口费"模式计价，与模块化单体自研基线冲突（调研依据 1、4） |
| **自研轻量通道（选定）** | 以成熟开源 HL7 v2 解析库 HAPI 为解析底座，通道抽象为"接入端点 → 解析 → 映射 → 路由 → 应答"的配置化管道（interface_channel + 映射模板），通道管理台并入本系统管理端 | 本系统 HL7 场景收窄：入站以 ORU^R01/R40、ADT、ACK 为主，出站以申请/结果类报文为主，无需全量 HL7 事件编排；全部纳入统一 CI、权限、审计；开发量集中于映射模板引擎 |

**结论**：自研轻量通道。**演进路径**：当通道数超过 50 条、或出现复杂多步编排/大量异构协议适配需求时，独立部署 Mirth Connect 作为旁路引擎，其通道以 interface_channel 注册纳入本模块统一监控、死信与幂等治理——业务系统只认通道配置，不感知底层引擎切换。

### 3.2 事件总线幂等与可靠投递：依赖 MQ 自身 vs 消费幂等 + 手动确认 + 死信 vs 事务消息

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 依赖 MQ 自身可靠性 | 至少一次投递 + 自动确认 | 至少一次语义下网络抖动/重连必然重复投递；自动确认在消费失败时直接丢消息，医疗闭环不可接受（调研依据 8） |
| 事务消息/分布式事务 | 发送侧与业务库强一致 | RabbitMQ 无原生事务消息；本系统为模块化单体，发送侧用"本地消息表（outbox）+ 发布确认 + 定时补偿"即可达到等价效果，复杂度不匹配 |
| **消费幂等 + 手动确认 + 死信（选定）** | 以事件信封中的 eventId 为幂等键，`integration.received_event` 表统一去重；业务成功才手动确认；失败有限重试后转死信交换机落 `dead_letter` 表，管理界面重推/关闭 | 与华为云/阿里云最佳实践同款组合；幂等表以唯一约束兜底并发重复；死信可视化管理对齐"异常消息必须规范处理"的运维实践（调研依据 4、8） |

**设计要点**：
- **事件信封**（全系统强制）：`eventId`（UUID，全局唯一）、`eventType`（`<模块>.<实体>.<动作>`）、`occurredAt`（服务器时间）、`producer`（生产模块）、`payloadVersion`（载荷结构版本）、`traceId`（全链路追踪号）、`payload`（业务载荷）。
- **发送侧可靠**：事件先写本模块 outbox（与业务同事务），异步投递到 `fy.topic` 并开启发布确认；确认超时/NACK 由 outbox 定时补偿重发；消费侧幂等保证补偿重发不产生重复业务。
- **消费侧流程**：① 校验信封必填字段，不合规直接转死信（附原因），不进业务；② 以 `eventId + 消费者模块` 登记幂等表（唯一约束防并发重复投递）——已存在且状态为已处理则直接确认跳过；③ 执行业务（业务内部再以业务键二次防重，如单据号唯一约束）；④ 成功则幂等表置已处理并手动确认；⑤ 失败记录原因并本地重试（默认 3 次指数退避），仍失败则拒绝投递进 `fy.dlx`。
- **死信处理流程**：死信消费者将消息落 `dead_letter` 表并发布 `integration.dead-letter.created` 事件通知运维；管理界面提供诊断、重放（保留原 eventId 重新入队，靠幂等机制防重复）、关闭（必须填写原因，如脏数据放弃）；重放成功联动置已重放，重放失败回到待处理并累加重放次数。

### 3.3 主数据分发模式：集中实时查询 vs 广播 + 本地缓存 vs 集中缓存服务

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 集中实时查询 | 各模块每次运行时调 M01/M20 接口查字典/组织 | M01 成为全系统读热点；M01 故障放大为全系统故障；工作站高频读（字典、组织树）延迟不可控 |
| 集中缓存服务 | 独立缓存集群统一供读 | 引入新故障域，仍是运行时强依赖，收益低于广播 |
| **广播 + 本地缓存（选定）** | M01 主数据变更发事件 → `fy.topic` 广播 → 各模块订阅并刷新本地两级缓存（进程内 + Redis）；M20 负责订阅登记、新模块全量初始化、每日版本对账与不一致补偿重发 | 主数据读多写少，广播缓存命中率与读延迟最优；"平台统一规则、各系统存映像"是医院主数据平台成熟实践（调研依据 2、3）；兜底：缓存携带版本号 + 每日版本比对，落后自动全量重发 |

**结论**：广播 + 本地缓存。M01 是唯一权威源；M20 只做"分发治理"（谁订了什么、同步到哪个版本、要不要补发），不存任何主数据业务值。

### 3.4 模式 D 设备 HL7 报文进入 IoT 数据链路的路径：M20 自行落库 vs 经 MQ 转发 vs 进程内移交 iot 管道

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| M20 解析后自行写遥测库 | 集成引擎直写 TimescaleDB | 与总 Spec D2"遥测单管道"冲突；设备匹配、患者关联、术语归一逻辑与 M14 重复实现，两处维护必然漂移 |
| M20 转换后发 MQ 由 iot 消费 | 事件解耦 | 与 D2"遥测不经 MQ"的精神冲突；含波形段的 ORU^R01 报文体积大，MQ 中转放大消息量与积压风险 |
| **进程内移交 iot 统一管道（选定，经 SPI）** | M20 将 ORU^R01/R40 解析映射为与 IoTDA 物模型同构的标准遥测/报警消息（属性名对齐 MDC 术语），经 fuyun-common 定义的“遥测移交”SPI 接口（M14 注册实现、本模块调用）进程内移交 M14 遥测管道，后续落库、患者关联、业务事件发布与 AMQP 主链路完全共用 | 一份数据语义、两条接入路径（IoTDA 主链路与 HL7 直连辅链路）；进程内调用无 MQ 放大；接口下沉 fuyun-common，integration 与 iot 模块零编译期依赖、无 Maven 循环；报文原文在 hl7_message_log 留存审计（总 Spec 4.5/5.2 一致） |

### 3.5 FHIR R4 预留形态：整体引入 HAPI FHIR Server vs 轻量自建 FHIR 门面

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 整体引入 HAPI FHIR JPA Server | 全功能 FHIR 服务器 | 自带独立存储与检索体系，与业务库双写一致性负担重；本期无 FHIR 消费方，属推测性建设 |
| **轻量自建 FHIR 门面（选定，P2 预留）** | 预留 REST 路径与资源映射规范（患者→M02、体征/检验观察、处方/用药请求、就诊等核心资源），按需把内部模型映射为 FHIR 资源，零新增存储 | 互联互通四级核心是数据集标准化与共享文档而非全量 FHIR 服务（调研依据 9）；门面式渐进引入风险最小；未来若需全量 FHIR 检索/订阅能力，再独立部署 HAPI FHIR Server 并经本模块通道纳管 |

## 4. 领域模型

| 实体 | 关键字段 | 说明 |
| --- | --- | --- |
| interface_channel 接口通道 | channel_code、channel_name、protocol(MLLP/WebService/REST/FILE/ASTM/FHIR 预留)、direction(入/出)、listen_addr 或 target_url、peer_system(对端系统)、msg_types(报文/数据类型清单)、mapping_template(映射模板引用)、charset、并发/超时/重试参数、ack_mode、ip_whitelist、status(ENABLED/DISABLED) | 一切对外协议接入的配置单元；启停与变更全程审计 |
| hl7_message_log HL7 报文日志 | channel_id、direction、msg_type(ORU^R01/ORU^R40/ADT^A08/ACK 等)、msg_control_id(MSH-10)、sending/receiving_app+facility、version_id、raw_content(大字段)、parse_summary(解析摘要)、status(见状态机)、error_msg、device_ref、patient_ref/visit_ref、ack_content | 只增表；原文在线 90 天、归档 ≥1 年，状态与索引元数据长期保留；通道+控制号用于重复报文识别 |
| received_event 幂等去重表 | event_id(UUID)、event_type、producer、occurred_at、consumer_module、status(PROCESSED/FAILED)、fail_reason、retry_count、received_at、processed_at | event_id+consumer_module 唯一约束；全系统统一幂等落点（README 跨模块约定），表归本模块，构件接口下沉 fuyun-common、实现由本模块运行时装配（各模块零编译期依赖）；保留 180 天后归档清理 |
| dead_letter 死信 | source_queue、routing_key、event_type、event_id、payload 引用与摘要、fail_reason、first_dead_at、status(PENDING/REPLAYED/CLOSED)、replay_count、handler、handle_note、handled_at | 与队列死信一一对应；重放/关闭全留痕 |
| event_registry 事件注册表 | event_type、producer_module、payload_desc、subscriber_modules、status(生效/废止)、registered_at | 事件契约台账：命名审查、生产/订阅关系唯一事实来源 |
| mdm_subscription 主数据订阅 | topic(dict/org/user/param/practice)、subscriber_module、sync_mode(事件订阅/接口拉取)、last_version、last_sync_at、last_recon_at、recon_status | 分发关系台账与对账依据 |
| mdm_dispatch_log 分发流水 | topic、version、dispatch_mode(广播/全量重发)、dispatched_at、target_modules | 补偿重发与分发审计依据 |
| push_task 上报任务 | target_type(区域平台/监管平台/互联互通数据集，均预留)、dataset_code、biz_date、payload_snapshot、status(见状态机)、retry_count、next_retry_at、ack_receipt | FU-M20-05 的接口位实体，M18 监管上报/M19 统计上报复用同一骨架 |
| interface_call_log 出站调用日志 | channel_id/peer_system、direction、request_digest(脱敏摘要)、result_code、duration_ms、trace_id、retry_of、occurred_at | 只增表；成功率/耗时监控统计源；各模块经本模块构件批量异步登记 |
| monitor_rule 监控规则 | rule_type(成功率阈值/耗时阈值/死信积压/通道静默)、scope(通道/事件/全局)、threshold、notify_target、enabled | 告警经 M01 通知中心发送，不自建通知通道 |

关系要点：通道 1:N 报文日志；事件类型 1:N 幂等记录/死信；订阅主题 1:N 分发流水；通道 1:N 出站调用日志。

协议口径：ASTM（E1394）协议供 M07 检验仪器双向通讯通道使用；DICOM 网络服务（C-STORE/MWL/MPPS/存储确认）由 M08 内建组件承载、不经本模块通道，DICOMweb 外部调用经 REST 通道治理。

## 5. 状态机与业务流程

- **hl7_message**：`RECEIVED(接收落库) → PARSED(解析转换成功) → ROUTED(已移交目标/投递成功) → COMPLETED`；接收/解析/路由任一环节失败 → `FAILED`；`FAILED → (人工重放) → RECEIVED` 重新处理。状态迁移经状态机服务校验并留迁移日志。
- **dead_letter**：`PENDING → REPLAYED(重放且消费成功)`；重放失败回到 `PENDING` 并累加重放次数；`PENDING → CLOSED(关闭，必填原因)`。`CLOSED` 为终态，不得再重放。
- **push_task**：`PENDING → SENDING → SENT(收到对端回执，终态) / FAILED(重试耗尽转人工)`；`FAILED → (人工重推) → PENDING`；重试按指数退避并受 next_retry_at 控制。
- **interface_channel**：`ENABLED ⇄ DISABLED`（启停即时生效并广播 `integration.channel.status-changed`）。

主流程时序：
1. **模式 D 设备报文端到端**：中央站/设备经 MLLP 发 ORU^R01（体征/波形）或 ORU^R40（报警）→ 通道校验白名单并接收落 hl7_message_log(`RECEIVED`) → 解析校验与映射转换（MDC 术语、与 IoTDA 物模型同构）→ 设备匹配（M14 设备档案）与患者/床位关联（当前绑定关系）→ 经遥测移交 SPI（fuyun-common 定义，M14 注册实现）进程内移交 M14 遥测管道 → 成功置 `ROUTED→COMPLETED` 并回应用答（AA）；失败置 `FAILED` 并回应答（AE/AR），原文留存待人工重放。未匹配设备的报文进入待处理清单并告警，不静默丢弃。
2. **死信处理**：消费失败 → `fy.dlx` → 死信消费者落 `dead_letter`(`PENDING`) + 发布 `integration.dead-letter.created` → 运维在界面诊断（看载荷、失败原因、源队列）→ 重放或关闭；处理过程符合"发现→登记→排查→处理→跟踪"五环节（调研依据 4）。
3. **主数据分发**：M01 发布变更事件（带版本）→ `fy.topic` 广播 → 各业务模块订阅刷新本地缓存 → 本模块同步记分发流水；新模块上线时经 M01 全量初始化接口回源并登记订阅；每日版本对账发现落后 → 自动全量重发对应版本事件（订阅方幂等刷新）。

## 6. 功能实现设计（逐 FU）

| FU | 实现设计要点 |
| --- | --- |
| FU-M20-01 集成引擎（P1） | 通道配置化管理界面（协议/方向/端点/映射模板/并发与超时/白名单/启停）；入站：MLLP 服务端多通道监听、Web Service 与 REST 入站端点、文件目录监听（到达即处理，处理后归档）；出站：统一出站适配（目标地址、重试、确认）；解析统一走 HAPI 解析底座，映射以模板配置（段-字段到内部模型的绑定）为主，复杂转换走受控扩展点并纳入版本管理；每次收发落 hl7_message_log 并按 ack_mode 自动/手动应答；连通性测试与通道健康探测内建 |
| FU-M20-02 HL7 设备报文接入（P1） | 报文范围：ORU^R01（IHE-PCD PCD-01 体征/波形）与 ORU^R40（PCD-04 报警），补齐总 Spec 5.2 模式 D 所指设备报文族；术语映射对齐 MDC/Rosetta（与 M14 物模型命名一致）；设备识别优先用 M14 设备档案登记的设备标识，匹配不到进待处理清单告警；转换结果为与 IoTDA 物模型同构的标准遥测/报警消息，经遥测移交 SPI 进程内移交 M14 管道（方案 3.4，SPI 接口下沉 fuyun-common），本模块不落遥测库；应答与重放见第 5 节流程 1 |
| FU-M20-03 FHIR R4 服务（P2，预留） | 本期不实现；预留内容：REST 路径前缀、核心资源映射规范（患者/就诊/观察/处方用药请求 ↔ M02/M05/M06 内部模型）、资源操作范围（读为主、写受控）；技术演进按方案 3.5 结论执行；通道协议枚举中预留 FHIR 取值，通道模型无需变更即可启用 |
| FU-M20-04 主数据分发（P1） | 订阅登记（mdm_subscription：模块×主题×同步方式）；广播链路 = M01 事件 → fy.topic → 各模块缓存刷新，本模块记分发流水；全量初始化（新订阅方经 M01 回源拉取指定版本）；每日版本对账 + 落后自动全量重发；管理界面展示"主题 × 订阅方 × 版本 × 对账状态"矩阵 |
| FU-M20-05 区域平台上报（P2，接口位） | 只建骨架不实现具体平台协议：push_task 任务表 + 上报适配器扩展位 + 管理界面（任务列表/失败重推/回执查看）；数据集内容加工归 M19/M09 等业务方，本模块负责打包、传输、重试、回执与留痕；协议接入时按对端规范新增适配器并登记通道 |
| FU-M20-06 事件总线治理（P1） | 治理对象：交换机三件套（fy.topic/fy.dlx/fy.delay）、队列命名、事件信封、幂等、死信（方案 3.2 全套）；事件注册表（event_registry）作为全系统事件契约台账，新增事件先登记后发布；提供幂等构件（接口下沉 fuyun-common，实现由本模块运行时装配）、死信管理界面、失败重推、事件查询台（按事件类型/时间/状态检索消费记录）；队列深度/消费速率经 RabbitMQ 管理 API 采集，不跨模块读表；生产侧可靠性约定（outbox + 发布确认）写入治理规范供各模块遵循 |
| FU-M20-07 接口监控对账（P1） | 全部出站外部调用（含各模块经本模块构件发起的）落 interface_call_log（脱敏摘要 + traceId）；监控维度：通道/对端/事件类型的成功率、耗时、失败数，按分钟聚合；monitor_rule 触发阈值告警（经 M01 通知中心）；失败调用支持界面重推（复用幂等与重推机制）；通道维度按日对账（收发量、成功/失败、应答分布）出具对账结果供运维核查 |

## 7. 对外接口

**REST（管理台，`/api/v1/integration/` 前缀）**：
- `GET/POST/PUT /channels`、`POST /channels/{id}/enable|disable|test`（通道管理）
- `GET /hl7-messages`、`POST /hl7-messages/{id}/replay`（报文日志与重放）
- `GET /dead-letters`、`POST /dead-letters/{id}/replay|close`（死信管理）
- `GET /received-events`（幂等/消费记录查询）
- `GET/POST/DELETE /event-registry`、`GET/POST/DELETE /mdm-subscriptions`、`POST /mdm/redispatch`（事件契约与主数据分发治理）
- `GET/POST /push-tasks`、`POST /push-tasks/{id}/retry`（上报任务）
- `GET /api-call-logs`、`GET /monitor/summary`（调用日志与监控汇总）
- 入站业务端点：`POST /inbound/rest/{channelCode}`（REST 通道入站的统一承接点，按通道配置路由）

**内部服务接口（进程内，供各模块调用）**：幂等构件（校验/登记/置结果，接口定义于 fuyun-common、实现由本模块运行时装配）、出站调用留痕登记（批量异步）、主数据订阅登记；同时本模块经 fuyun-common 的遥测移交 SPI 调用 M14 注册的实现（模式 D）。

**MQ 事件（遵循 README 第 3 节命名与信封约定）**：
- 发布：`integration.channel.status-changed`（通道启停/健康变更）、`integration.dead-letter.created`（死信产生，运维通知依据）、`integration.interface.alerted`（监控阈值触发）
- 订阅：`system.dict.published`、`system.org.changed`、`system.user.changed`、`system.param.changed`、`system.practice.changed`（主数据分发治理链路，M01 发布）

**治理约定（全系统约束，本模块为执行审查方）**：
- 交换机全集固定：`fy.topic`（领域事件 Topic）、`fy.dlx`（死信）、`fy.delay`（TTL+DLX 延迟，队列 `delay.<业务>`）；私建交换机禁止。
- 队列命名 `q.<消费者模块>.<事件>`（如 `q.integration.dead-letter` 为本模块死信统一队列）；消费一律手动确认 + 幂等（received_event）。
- 新增/变更事件类型须先在 event_registry 登记（生产者、载荷说明、订阅方），由统一审查对照各模块 Spec 声明；零订阅的广播类事件（全院广播/模块内闭环）须在登记时标注 broadcast 语义。
- 本模块无 WebSocket 主题（监控台经 REST 轮询，秒级刷新满足运维场景）。

## 8. 集成点

- **依赖上游**：M01（接口账号与网关鉴权、通道管理权限点、审计、通知中心发送、主数据权威源与全量初始化接口）；M02（报文患者匹配的身份标识查询）；M14（设备档案匹配、设备-患者-床位当前绑定查询——同步 API，按“调用方=依赖方”口径计）；模式 D 遥测移交经 fuyun-common 定义的 SPI 接口调用（M14 注册实现），本模块对 M14 无编译期依赖。
- **被下游依赖（全模块）**：事件总线治理（信封/交换机/队列约定）、幂等构件（接口下沉 fuyun-common，各模块零编译期依赖，实现由本模块运行时装配）、死信界面、失败重推、出站留痕构件、上报任务骨架（M18 监管上报、M19 统计上报复用 push_task）。
- **与 M14 的关系**：模式 D（总 Spec 5.2）设备/中央站 HL7 报文 → 本模块转换 → 经 fuyun-common 定义的遥测移交 SPI 进程内移交 M14 统一遥测管道（总 Spec 4.5 链路的辅路径；SPI 接口在 common、M14 注册实现、本模块调用，integration 与 iot 模块零编译期依赖、无 Maven 循环）；设备档案匹配为 M20→M14 的同步 API 调用（调用方=依赖方）；M14 的 IoTDA AMQP 主链路不经过本模块；M14 遥测管道产出的业务事件（告警/状态变更）仍走 fy.topic 由各模块订阅，本模块只治理不消费。
- **与 M01 的关系**：字典/组织/人员/参数/执业授权主数据由 M01 唯一维护并发广播事件，本模块做分发治理（订阅登记、对账、补偿重发），两模块 Spec 的事件名一一对应，统一审查时对照。

## 9. 非功能与安全

- 性能：HL7/ASTM 通道按峰值 200 msg/s 设计——口径为设备报文（ORU^R01/R40，设备/中央站直连辅路径）与仪器结果报文（M07 检验仪器双向通讯）合并估算的通道总容量，两类报文共用该峰值额度、不做叠加（余量高于总 Spec 5.4 体征峰值的外推）；单报文“接收→移交”P95 < 200ms；幂等判定走唯一索引，P99 < 5ms；监控聚合分钟级完成；outbox 补偿扫描不阻塞业务事务。
- 可用性：通道接入无状态可多实例（MLLP 多实例经负载均衡分发 TCP 连接）；RabbitMQ 使用 quorum 队列；死信积压、通道静默、消费滞后均入监控规则告警；本模块不可用不阻断业务模块间已建链路的事件收发（Broker 独立部署）。
- 容量与留存：hl7_message_log 原文在线 90 天、归档 ≥ 1 年，状态/索引元数据长期保留；received_event 保留 180 天（幂等窗口远大于 MQ 可能的重投窗口）；interface_call_log 在线 180 天后归档；归档策略可配置。
- 安全：MLLP 通道部署于内网隔离区并启用 IP 白名单（HL7 v2 MLLP 无内建认证，靠网络边界控制）；Web Service/REST 入站走 API 网关 + M01 接口账号鉴权；出站强制 TLS，密钥走环境变量不入库；报文日志与调用日志中患者身份字段（姓名/证件号）脱敏展示、导出留审计；通道配置与死信处理操作全量审计；对齐等保三级审计与传输加密要求。
- 时间：报文头内的对端时间仅作参考值，业务时间一律取服务器接收时间（对齐全局"禁止前端/外部传入业务时间"约定）。

## 10. 测试要点

- 正常：ORU^R01 体征报文端到端（接收→转换→移交遥测管道→应答 AA→日志 COMPLETED）；ORU^R40 报警报文进入 M14 告警链路；主数据发布事件后订阅模块缓存版本刷新；出站调用留痕与成功率统计口径正确；死信重放成功后业务仅执行一次。
- 边界：同一 eventId 并发投递两次仅一次业务生效（唯一约束兜底）；重复 msg_control_id 报文被标记重复且不重复移交；含波形段的大报文不阻塞同通道其他报文；通道重启后 MLLP 连接自动恢复且未应答报文按对端重发幂等处理；版本对账发现缓存落后自动触发全量重发。
- 异常：信封不合规消息直接进死信且附原因（不进业务）；解析/映射失败回 AE/AR 应答且 FAILED 可重放；对端断连/超时产生通道告警，已接收报文不丢失；死信重放失败回 PENDING 并累加计数；上报对端不可用按退避重试后转 FAILED 可人工重推；审计/留痕写入失败不阻断通道收发但必须告警（对齐 M01 审计红线）。
- 安全：白名单外来源拒连并告警；越权访问通道配置/死信操作 403 且留审计；日志脱敏有效性验证；禁用通道不再接收且入站请求被明确拒绝。

## 11. 自审记录

- [x] 无 TBD/TODO/占位符，13 项内容完整（文档头 + 12 节）
- [x] 覆盖 FU-M20-01~07 全部条目，无遗漏、无私增（FU-M20-03/05 按 P2 预留定位细化，未提前实现）
- [x] 内部一致：领域模型 ↔ 状态机 ↔ 接口 ↔ 测试一一对应（hl7_message/dead_letter/push_task/interface_channel 四个状态机均有对应 API、流程与测试项；received_event、mdm_subscription、monitor_rule 均有查询/管理接口与测试场景）
- [x] 符合跨模块约定：schema=integration；事件命名 `<模块>.<实体>.<动作>`；信封含 eventId/occurredAt/producer；幂等统一落 integration.received_event；交换机/队列命名与 README 完全一致；无跨模块读表（遥测/主数据均经接口）
- [x] 依赖方向正确：依赖 M01/M02 对外接口与 M14 设备档案同步查询；模式 D 遥测移交经 fuyun-common SPI（M14 注册实现），integration 与 iot 零编译期依赖、无 Maven 循环；幂等构件接口下沉 fuyun-common，各模块对本模块零编译期依赖；无反向依赖、无跨模块读表
- [x] 方案推导 5 个关键点均有备选对比与依据，含任务要求的三个必选点（HL7 引擎路线、幂等与可靠投递、主数据分发），每个结论附调研来源
- [x] 无代码级实现（无类名/方法体/SQL DDL；表设计为"表-关键字段-约束"粒度；HAPI/Mirth 为库名/产品名，非代码实现）
- [x] 歧义消除：模式 D 移交路径（进程内 SPI 而非 MQ，保持 D2 决策一致）、幂等窗口与状态语义（PROCESSED 才视为已消费）、死信重放保留原 eventId 等均已显式定义
- [x] 术语与总 Spec 一致（通道/死信/重推/幂等/主数据/模式 D/互联互通）

## 12. 与总 Spec 的偏差

无。两处细化澄清（非偏差）：① FU-M20-02 的设备报文族按总 Spec 2.3 的 IHE-PCD 定义扩展覆盖 ORU^R40（PCD-04 报警），与总 Spec 一致；② FU-M20-01 的“通道可视化配置”在本系统管理端（web-workstation 管理区）实现，不引入独立引擎管理台，与方案 3.1 选型一致。

### v1.1 统一审查修订记录（2026-09-07）

依据 `docs/specs/modules/90-cross-review.md` 裁决执行，本次修订点如下（均为依赖方向与登记口径修正，不构成对总 Spec 功能点的偏差）：

- **M-3**：依赖方向重构，消除 M20↔M14 互为上游的 Maven 循环依赖——①幂等构件接口下沉 `fuyun-common`（接口在 common，实现由本模块运行时装配，表仍在 integration schema，各模块零编译期依赖）；②模式 D 遥测移交改为 SPI（接口在 common，M14 注册实现，本模块调用）；文档头依赖声明、§3.4/§5 相关表述、§8 与 M14 关系、自审记录同步改写。
- **M-15**：§4 通道协议枚举扩展 `ASTM`（注明 M07 仪器通道使用）；显式声明 DICOM 网络服务（C-STORE/MWL/MPPS/存储确认）由 M08 内建组件承载、不经通道，DICOMweb 外部调用经 REST 通道治理。
- **R4-14**：§9 通道容量改为按“设备报文+仪器结果报文合并估算”口径声明，消除两处 200 msg/s 叠加矛盾。
- **R6-13**：event_registry 治理约定补充“零订阅广播类事件须标注 broadcast 语义”。
