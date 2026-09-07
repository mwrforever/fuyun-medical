# M14 医疗设备物联网平台 · 功能实现 Spec

| 属性 | 内容 |
| --- | --- |
| 模块编号 | M14 |
| Maven 模块 | `fuyun-iot`（schema：`iot`；遥测超表位于同 schema，由 TimescaleDB 扩展管理） |
| 版本 / 状态 | v1.1 / 统一审查修订 |
| 上游依赖 | M01（认证/权限/审计/通知/字典与参数广播）、M02（patient_id 归一与解析服务——绑定患者档案状态校验、合并/冻结事件订阅）、M04（visit_id 签发、床位、入科/转床/出院事件）、M05（输液任务/输注事件订阅 + 联动动作经 `POST /api/v1/nursing/tasks` 创建护理任务）、M15（设备资产互引 asset_ref 与 asset.* 事件订阅、命令下发前可用性校验）、M16（呼叫转发/门禁联动承接——联动动作出向调用其播报/呼叫与门禁闭锁接口）、M20（模式 D 报文移交对端、事件总线治理；幂等构件经 fuyun-common 接口使用、实现由 M20 运行时装配） |
| 下游被依赖 | M05（体征双通道/输液联动/护士站大屏）、M16（输液监控/冷链/呼叫/定位消费方）、M10/M11（麻醉/重症记录数据采集）、M15（设备利用率与效益分析）、M20（模式 D 遥测移交 SPI 的实现注册方——M20 经 fuyun-common 接口调用、零编译期耦合）、web-workstation / web-bigscreen（WebSocket 推送） |
| 对应总 Spec | FU-M14-01 ~ FU-M14-13 |

---

## 1. 模块定位与边界

**职责**：本模块是全院医疗设备的统一物联底座与数据枢纽，六项核心职责：① IoTDA 对接底座——内嵌 AMQP 订阅消费者集群，承接华为云 IoTDA 转发的全部设备数据，并管理积压风险与双通道兜底；② 设备元数据中心——产品/物模型镜像、跨品牌术语归一（MDC 映射）、设备档案与一机一密凭证管理；③ 设备-患者-床位动态绑定——遥测数据归属判定的唯一权威源；④ 遥测接入管道与时序服务——解析/校验/患者关联后批量落 TimescaleDB，对外提供查询与实时推送；⑤ 告警与联动引擎——设备报警透传、平台侧阈值评估、分级/确认/升级闭环、业务联动编排；⑥ 命令下发与边缘网关管理——受控远程控制（白名单+二次确认+审计）与模式 B/C 网关拓扑管理。总 Spec 5.2 接入矩阵中模式 A/B/C 的数据最终都归宿于本模块（经 IoTDA AMQP 主链路），模式 D 的 HL7 报文由 M20 转换后经 fuyun-common 定义的遥测移交 SPI（本模块注册实现、M20 调用，零编译期耦合）进入本模块遥测管道（辅链路）。

**非职责**：HL7 v2 报文的协议收发与解析转换（归 M20，本模块只接收标准遥测/报警消息）；设备资产台账、计量检定、维修保养（归 M15，本模块只提供设备在线率与利用率数据）；输液监控业务界面与冷链监控业务视图（归 M16，本模块提供告警事件与遥测查询）；护理任务的业务执行与护理记录书写（归 M05，本模块只触发联动）；患者身份归一（归 M02）；床位主数据（归 M01/M04，本模块仅引用）。

**模块红线**：
1. 本模块是设备数据归属（哪条遥测属于哪个患者/就诊）的唯一判定方；其他模块不得自行维护设备-患者关系，一律经绑定查询接口获取。
2. 遥测数据不经 RabbitMQ 中转（总 Spec D2），AMQP 主链路与 MQ 事件总线物理隔离；本模块发布的业务事件（告警/状态/绑定）必须走 M20 治理的 `fy.topic` 三交换机体系，禁止私建交换机。
3. 治疗类设备参数（给药速率、通气模式、麻醉深度等直接影响治疗结果的参数）默认禁止远程下发；白名单放行与豁免必须留痕可审计（总 Spec D8）。
4. 禁止直接读写其他模块 schema；患者/床位/就诊信息一律经接口或事件；设备密钥明文不得入库。
5. 波形数据默认不落库（总 Spec D3）；开启波形存储仅限抢救/麻醉场景白名单设备，且必须独立配额，超配额自动降级为边缘降采样。

## 2. 调研依据

1. 华为云 IoTDA AMQP 客户端接入说明：仅支持 AMQP 1.0，接入地址 `amqps://host:5671` 强制 TLS（1.2+）；鉴权用接入凭证（accessKey/accessCode），userName 需拼接 13 位毫秒时间戳且服务端校验偏差超过 5 分钟即拒绝；单个接入凭证键值最多 32 个客户端同时建链、一条连接最多 10 个 Receiver Link、单租户最大连接数 32、单用户最大队列数 100、单条消息缓存 1 天；连接建立后须 15 秒内完成 Receiver Link，仅支持平台到客户端单向推送。（来源：https://support.huaweicloud.com/intl/zh-cn/usermanual-iothub/iot_01_00100_2.html ）
2. 华为云 IoTDA AMQP Java SDK 示例：推荐 Apache Qpid JMS 客户端；failover 连接串内置断线重连（初始 3s、上限 30s、无限次）；`queuePrefetch` 默认 1000 控制客户端预取；确认模式支持自动确认（官方 Demo 默认）与客户端确认两种；消息处理耗时较长时需另开处理线程，否则心跳超时断连。（来源：https://support.huaweicloud.com/usermanual-iothub/iot_01_00100_3.html ）
3. 华为云 IoTDA 规则引擎：数据转发 SQL 仅由 SELECT/WHERE 组成，子句各限 500 字符、AS 别名最多 10 个，数据来源含设备属性上报/设备消息上报/设备状态/异步命令状态等资源类型，可用函数含 GET_SERVICE_PROPERTY 等；转发目标矩阵覆盖 AMQP/MQTT/HTTP/Kafka/DMS 等；公网转发流量限制 1 MB/s。（来源：https://support.huaweicloud.com/usermanual-iothub/iot_01_0025.html 、https://support.huaweicloud.com/usermanual-iothub/iot_01_0024.html ）
4. 华为云 IoTDA 设备影子与物模型：影子含 desired（期望值）与 reported（上报值）两区，支持查询/删除/配置期望数据三类 API；物模型按"服务→属性/命令/事件"组织，属性/事件用于上行，命令用于下行控制，同类设备统一建模。（来源：https://support.huaweicloud.com/usermanual-iothub/iot_01_0049.html 、https://support.huaweicloud.com/api-iothub/iot_06_v5_0079.html 、https://support.huaweicloud.com/devg-iothub/iot_01_0017.html ）
5. 华为云 IoTDA 网关与子设备、命令下发：网关代子设备维护拓扑与上下线（支持三种方式切换子设备网关）；命令支持同步（设备在线即时应答）与异步（平台缓存、设备上线送达、执行结果经命令状态事件异步回推）两种模式；OTA 软固件升级为平台原生能力。（来源：https://support.huaweicloud.com/intl/zh-cn/usermanual-iothub/iot_01_0052.html 、https://support.huaweicloud.com/intl/zh-cn/api-iothub/iot_06_v5_0038.html 、https://support.huaweicloud.com/function-iothub/index.html ）
6. 解放军总医院 ICU 医疗设备物联网（25 个月生产运行实证）：三种接入模式风险对比（网关/中央站集中式"中心故障全病区掉线"，需双网关热备；边缘适配器去中心化稳定性最好）；术语用 Rosetta 映射表（如 MDC_ECG_HEART_RATE=147842）；4 病区日均 15,531 MB、25 个月累计 217.6 亿条/12.5 TB、波形占 86.64%、体征 7.92%、报警 5.44%；论文自述不足含"物联网数据质量参差不齐，缺乏系统性质控与治理策略"、有线网口接触不良致数据中断——构成本模块数据质量监控（FU-M14-11）的直接依据；护理集成自动导入省 0.8h/床/天。（来源：https://pmc.ncbi.nlm.nih.gov/articles/PMC11955343/ ）
7. MDC/Rosetta 术语体系：MDC（ISO/IEEE 11073-10101 医疗设备通信命名体系）经 Rosetta 术语映射管理系统（RTMMS）维护，HL7 官方术语服务器收录其编码体系；FHIR 点照护设备（POCD）实现指南的 MDC 指标值集含 21,124 个编码，覆盖监护/呼吸/输液等设备指标——构成跨品牌术语归一的字典基准。（来源：https://terminology.hl7.org/MDC.html 、https://build.fhir.org/ig/HL7/uv-pocd/ValueSet-11073MDC-metric.html ）
8. 告警降噪生产实践（运维监控领域成熟方法论，同样适用于医疗设备告警风暴）：降噪手段分为分组聚合（同维度窗口内只通知一次）、静默（维护窗口/已知告警暂不通知）、抑制（上游告警抑制下游衍生告警）、抖动检测（阈值边界反复触发需恢复条件+持续时间条件确认）四类；告警风暴预警通过"单位时间告警量突增检测"提前介入。（来源：https://flashcat.cloud/blog/alert-storm-noise-reduction-practice/ 、https://time.geekbang.org/column/article/632433 、https://help.aliyun.com/zh/sls/alert-management/ ）
9. Spring WebSocket/STOMP 推送中心生产实践：STOMP 提供主题订阅/点对点语义，服务端默认 25s 心跳检测断连；握手拦截器完成令牌鉴权；多实例部署经 MQ 扇出保证各实例会话均收到消息；大消息（超 100KB）触发 1009 断连，推送载荷需轻量化。（来源：https://shibd.github.io/message-center-2/ 、https://juejin.cn/post/7515242529656422441 、https://cloud.tencent.com.cn/developer/information/Spring%2520Stomp%20Websocket%20-%E5%A4%AA%E5%A4%A7%E7%9A%84%E5%8F%91%E9%80%81%E6%B6%88%E6%81%AF%E7%94%9F%E6%88%901009%E9%94%99%E8%AF%AF%E5%B9%B6%E6%96%AD%E5%BC%80%E8%BF%9E%E6%8E%A5 ）
10. TimescaleDB 生产实践：压缩以 chunk 为单位由后台作业执行（新数据先以行存写入），配合保留策略与连续聚合可实现"降采样后删除原始数据"的分层存储；连续聚合刷新策略由 bucket_width（桶宽）、start_offset/end_offset（刷新窗口）、schedule_interval（调度周期）参数定义，end_offset 预留近期未定形数据避免反复重算；官方与社区实践报告压缩后查询性能提升一个数量级、存储节省 90% 以上。（来源：https://docs.timescaledb.cn/use-timescale/latest/compression/ 、https://www.tigerdata.com/docs/reference/timescaledb/continuous-aggregates/add_continuous_aggregate_policy 、https://www.modb.pro/db/507854 ）

## 3. 方案推导（关键设计点选型）

### 3.1 遥测消费架构：单实例逐条消费 vs 多实例批量消费 vs MQ 中转削峰

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 单实例逐条消费 | 一台消费者实例、收到即处理即落库 | 实现最简，但吞吐受单条往返限制；实例故障即整条链路中断，与"必须应对 24h/1GB 积压上限"（总 Spec 5.3-3）矛盾 |
| MQ 中转削峰 | AMQP 消费后先写 RabbitMQ，再由落库 worker 消费 | 违背总 Spec D2（遥测不经 MQ）；波形/高频属性会使 MQ 消息量放大数倍，1GB 积压风险从 IoTDA 转移到 RabbitMQ 而非消除 |
| **多实例分类队列批量消费（选定）** | IoTDA 侧按数据类型配置四条订阅队列（遥测属性/告警事件/设备状态/命令状态）分流至本模块四组消费者；消费者多实例部署（受单凭证 32 连接上限约束，实例数×连接数预留余量）；同设备消息按设备号哈希到固定处理线程保证时序；内存有界队列攒批（条数或时间窗任一到达即批量写入），写库成功后统一确认；处理失败的消息落本模块消费错误日志（可查可重放）不阻塞队列 | 分类队列隔离保证告警不被遥测洪峰淹没（告警通道时延独立可控）；批量落库把常态 50 msg/s、峰值 500 msg/s（总 Spec 5.4，v1.1 统一口径）的写放大压到每秒个位数批次；有界队列+prefetch 形成天然背压；积压以"最旧未消费消息年龄"为核心指标监控（>5 分钟告警、>30 分钟触发扩容预案），远早于 24h/1GB 清理线 |

**幂等与重复投递设计**：AMQP 断线重连后未确认消息会重推（调研依据 2），叠加批量确认粒度，重复投递不可避免，分两层消化：① 遥测明细以（设备号+指标编码+发生时间）唯一约束做冲突忽略写入，天然幂等，同时防设备侧重复上报；② 告警/联动/状态类业务消息以事件键（设备号+事件类型+触发时间窗）唯一约束建告警实例与状态记录，重复消息只累加计数不产生新业务。兜底通道（IoTDA 联动规则 HTTP 推送）与 AMQP 主通道双写同一告警键，靠同一唯一约束去重（总 Spec 5.3-3 双通道要求的落点）。

**结论**：多实例分类队列批量消费 + 双层幂等 + 积压年龄监控 + HTTP 兜底通道。

### 3.2 TimescaleDB 建模：明细+连续聚合+压缩+保留的分层策略

| 设计项 | 选定策略 | 参数依据 |
| --- | --- | --- |
| 超表分区 | 遥测超表以发生时间为分区键，chunk 间隔 1 天 | 对齐总 Spec 4.4"按天分区"；仅体征/报警级数据入库（波形默认边缘降采样，总 Spec D3），常态约 432 万行/天（50 msg/s 外推），1 天 chunk 大小适中，压缩与保留均以 chunk 为单位批量执行（调研依据 10） |
| 压缩策略 | 发生时间超过 7 天的 chunk 由后台作业自动压缩；压缩按设备号+指标编码分段、按发生时间排序 | 对齐总 Spec 4.4"7 天后压缩"；分段/排序键与最高频查询模式（查单设备单指标时间段）一致，压缩后此类查询反而更快（调研依据 10） |
| 保留策略 | 原始明细保留 90 天自动删除；1 分钟与 1 小时两级连续聚合保留 1 年（参数可配置） | 对齐总 Spec 4.4"明细 90 天/降采样 1 年"；波形不在此策略内（默认不落库） |
| 连续聚合 | 按设备×指标×时间桶（1 分钟/1 小时）物化最小值/最大值/平均值/计数；刷新窗口起点 3 天、终点预留 10 分钟、调度周期 10 分钟 | 终点预留避免对仍在写入的当前桶反复重算（调研依据 10）；实时查询 = 物化聚合 + 原始数据实时联合，兼顾正确性与性能 |
| 查询路由 | 查询窗 ≤24h 且点数受控走明细；超过 24h 或要求降采样走连续聚合；超 90 天强制走聚合 | 保护数据库：大范围明细扫描是唯一需要主动防御的查询形态；聚合分层使"患者 7 天体温曲线"类页面稳定亚秒 |

**结论**：1 天 chunk + 7 天压缩 + 明细 90 天/聚合 1 年 + 两级连续聚合实时联合查询；波形按总 Spec D3 默认边缘降采样不落库，抢救/麻醉白名单设备按需开启并独立配额。

### 3.3 设备-患者-床位绑定模型：直绑患者 vs 经床位推导 vs 显式绑定快照

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 设备直绑患者 | 绑定表只建设备→患者关系 | 固定安装设备（智能床垫、床旁一体机）换患者频率与转床频率一样高，绑定操作频繁且漏绑即错归属；无法表达"设备在 3 床但患者未确定"的中间态 |
| 经床位推导 | 设备绑床位，患者经 M04 床位占用关系间接推导 | 转运监护仪、随患者移动的输液泵/注射泵跨床位使用，按床位推导必然错归属；推导链依赖 M04 实时数据，绑定语义被稀释，审计回溯困难 |
| **显式绑定快照 + 双绑定模式（选定）** | 绑定实体记录设备×患者×就诊×床位×病区五元组及生效时间；固定式绑定（设备↔床位，患者随床位推导快照）与移动式绑定（设备↔患者直绑）两类；每条遥测写入时快照当时绑定关系（冗余患者/就诊列）；绑定历史表只增不解绑覆盖，转床/消毒/出院各带解绑原因 | 数据归属判定 O(1)（遥测行自带患者号，不依赖运行时推导）；历史可回溯（任何时点设备归属可由绑定历史还原，满足医疗审计）；两类模式覆盖固定与移动设备真实形态；消毒/转床流程有显式解绑动作与原因，杜绝"设备还挂着上周患者"的脏归属 |

**绑定生命周期驱动**：M04 入科确认事件触发待绑定提醒（护士确认后生效，避免系统静默错绑）；转床/出院事件强制解绑（解绑中状态保证解绑期间数据不挂到新患者）；消毒/维修走人工解绑并留原因。绑定变更即发布 `iot.binding.changed`，M05/M16 缓存刷新。

**结论**：显式绑定快照 + 固定式/移动式双模式 + 事件驱动强制解绑 + 遥测行冗余快照。

### 3.4 告警引擎架构：流式同步评估 vs 独立评估组件 vs 平台联动规则全托管

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 流式消费中同步评估 | 遥测消费线程内直接跑规则评估 | 延迟最低，但规则引擎异常会阻塞遥测落库主链路；规则数量增长后消费吞吐不可控——告警引擎故障不应波及数据管道 |
| 平台联动规则全托管 | 全部用 IoTDA 平台侧联动规则判定并推送 | 平台联动规则面向简单条件，难以表达"阈值+持续时间+恢复条件"复合逻辑与风暴抑制；规则散落云端与本端两处，审计与版本管理割裂；命令/查询受限 |
| **进程内独立评估组件（选定）** | 告警评估作为独立组件在消费管道旁路运行：遥测落库与规则评估并行（各自独立线程池、互不阻塞、互不影响确认）；设备本地报警（物模型事件/模式 D 的 ORU^R40）与设备离线告警直接入引擎；平台侧 IoTDA 联动规则仅保留两个职责——HTTP 兜底通道与轻量即时联动 | 评估异常不影响落库与确认（管道稳定性与告警及时性解耦）；规则库本地化统一管理（配置版本、审计、测试）；秒级延迟满足"大屏刷新 ≤2s"（总 Spec 8） |

**风暴抑制与升级设计**（调研依据 8 的医疗化落地）：① 同源聚合——同设备+同规则在静默窗口内的重复触发不新建实例，累计到原实例的触发次数；② 抖动防护——阈值类规则必须配置"持续时间"（连续超阈值 N 秒才触发）与"恢复条件"（回到阈值带内才允许再次触发），杜绝临界值反复告警；③ 抑制——设备离线告警自动抑制该设备衍生的高频遥测告警（离线本身就是根因）；④ 风暴预警——单位时间告警产生量超基线阈值时进入风暴态（非危急告警只入库不通知，大屏横幅提示），风暴解除后补通知汇总；⑤ 分级升级——危急告警超时未确认按"责任护士→护士长→科室主任"链路逐级升级（升级为动作：重复通知并累加升级次数，不新增状态），确认前升级持续。

**结论**：进程内独立评估组件 + 设备报警透传/平台阈值/离线三类规则源 + 五项风暴抑制 + 动作式超时升级。

### 3.5 命令下发安全：直接透传 vs 白名单+二次确认+审计（总 Spec D8 落地）

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 直接透传 | 应用侧仅做登录校验，命令直接经 IoTDA 下发 | 医疗设备远程控制直接作用于患者身边设备（输液泵速率、呼吸机参数），误触/越权即患者安全事件；等保三级"操作可审计、责任可追溯"无法满足 |
| 全部禁用远程命令 | 只允许床旁手工操作 | 丧失 IoT 核心价值（远程调参、批量配置、设备运维）；总 Spec FU-M14-09 明确要求此能力 |
| **白名单 + 二次确认 + 全程审计（选定）** | 命令三级管控：物模型命令注册时即标注安全等级（查询类/展示类设置=安全级，给药速率/通气参数等治疗级）；白名单默认仅放行安全级，治疗级命令默认禁用，豁免需系统参数显式开启且操作升级审批；下发强制"权限校验（RBAC+数据范围）→ 设备在线预检 → 二次确认（前端发起确认挑战、服务端签发短时效确认凭证、下发请求必须携带，防误触与重放）→ IoTDA 下发（在线同步/离线异步）→ 结果回推更新命令日志"五步；命令日志记录操作者/设备/命令/参数/结果/耗时/追踪号全要素 | 安全级/治疗级分级精准对应 D8"默认仅允许非治疗性参数"的要求；确认凭证机制把"二次确认"从 UI 约定提升为服务端强制；异步命令状态回推（调研依据 5）补全"下发→送达→成功/失败/超时"闭环，超时自动置超时态并告警 |

**结论**：安全等级标注 + 默认白名单 + 确认凭证 + 五步下发流程 + 全要素命令日志。

## 4. 领域模型

| 实体 | 关键字段 | 说明 |
| --- | --- | --- |
| iot_product 产品/物模型镜像 | product_id(IoTDA 产品标识)、product_code、product_name、device_type、model_definition(物模型 JSON 快照：服务/属性/命令/事件)、command_safety(命令安全等级映射)、sync_status(同步中/已同步/失配)、synced_at、iot_resource_space(资源空间) | 与 IoTDA 产品的本地镜像；物模型变更经同步任务对齐，失配即告警；命令安全等级在此登记（方案 3.5） |
| iot_metric_dict 指标术语字典 | metric_code(MDC 编码，如 MDC_ECG_HEART_RATE)、metric_name、category(体征/波形/报警/设备状态)、data_type、unit、physio_min/physio_max(生理极限范围)、default_level | 跨品牌术语归一基准（调研依据 7）；模块专业字典，非 M01 国标字典管辖；设备物模型属性经映射表关联到本字典 |
| iot_device 设备档案 | device_id(IoTDA 设备标识)、node_id(设备侧标识)、product_id、device_name、device_type(总 Spec 5.1 矩阵 15 类)、access_mode(A 直连/B 串口服务器/C 边缘适配器/D HL7 引擎)、gateway_id(所属网关，直连为空)、ward_id、bed_id(固定安装设备当前位置)、asset_ref(M15 资产号引用，展示级冗余，权威在 asset.iot_device_ref，经事件幂等维护)、credential_ref(一机一密凭证引用，密钥明文不入库)、status(见状态机)、last_online_at、last_offline_at | 设备唯一档案；模式 D 接入的设备也在此登记（access_mode=D），供 M20 报文匹配（FU-M20-02 依赖）；资产引用与报废停用联动经 asset.asset.created/changed/scrapped 事件幂等维护（双实体互引已与 M15 v1.1 对齐落地） |
| iot_gateway 边缘网关 | gateway_id、gateway_name、mode(B/C)、ward_id、status(在线/离线/维护)、standby_of(热备对端网关)、software_version、child_count(子设备数) | 模式 B/C 网关档案；热备对端字段落实总 Spec 5.2"双网关热备"要求；OTA 版本管理预留（FU-M14-12） |
| iot_device_patient_binding 设备患者绑定 | binding_id、device_id、patient_id、visit_id、bed_id、ward_id、bind_type(固定式/移动式)、status(绑定中/解绑中/已解绑)、bind_reason、unbind_reason(转床/消毒/维修/出院/调拨)、bound_by、bound_at、unbound_at | 方案 3.3 权威绑定源；同一设备同一时刻至多一条"绑定中"记录（部分唯一约束）；历史记录只增不改；患者号引用 M02、就诊号引用 M04 签发 |
| iot_telemetry 遥测超表 | device_id、patient_id、visit_id、metric_code(MDC)、value(NUMERIC)、unit、occurred_at(分区键)、quality(GOOD/SUSPECT/BAD)、source(IOTDA——AMQP 主链路，含模式 A/B/C/HL7——模式 D 辅链路) | TimescaleDB 超表，策略见方案 3.2；(device_id, metric_code, occurred_at) 唯一约束承担写入幂等；患者/就诊列为写入时快照（方案 3.3） |
| iot_alarm_rule 告警规则 | rule_id、rule_name、rule_type(threshold 阈值/device-alarm 设备报警透传/offline 离线)、metric_code、device_scope(设备类型/设备组/单设备)、compare_op、threshold_value、duration_secs(持续时间)、recovery_band(恢复带)、level(INFO/WARNING/CRITICAL)、silence_window_secs(静默窗口)、notify_targets、enabled | 阈值规则必须同时配置持续时间与恢复带（方案 3.4 抖动防护）；规则变更全程审计 |
| iot_alarm 告警实例 | alarm_id、alarm_key(幂等键=设备+规则+触发时间窗，唯一约束)、rule_id、device_id、patient_id、metric_code、trigger_value、level、status(见状态机)、triggered_at、acked_by/acked_at、closed_by/closed_at/handle_note、trigger_count(风暴聚合计数)、escalation_count、suppress_flag(风暴态抑制标记) | 告警闭环主体；alarm_key 唯一约束承担双通道（AMQP+HTTP 兜底）与重复投递去重 |
| iot_command_log 命令日志 | command_id、device_id、command_name、command_params、safety_level(安全级/治疗级)、operator、confirm_ref(二次确认凭证引用)、deliver_mode(同步/异步)、status(见状态机)、issued_at、result_at、error_msg、trace_id | 高风险操作全要素留痕（方案 3.5）；治疗级命令豁免开关的每次使用在此表可完整审计 |
| iot_linkage_rule 联动规则 | rule_id、rule_name、trigger_type(alarm-triggered/telemetry-anomaly/device-status)、trigger_condition、action_type(创建护理任务/呼叫转发/发送通知/门禁联动)、action_config、target_module、enabled | 设备事件→业务联动的编排配置；动作执行经目标模块接口，执行结果落联动日志 |
| iot_linkage_log 联动执行日志 | log_id、rule_id、source_alarm_id、action_type、target_ref(目标业务单据号)、status(成功/失败/重试中)、fail_reason、executed_at | 联动可追溯；失败进本地重试，耗尽后告警运维 |
| iot_data_quality_stat 数据质量统计 | device_id、stat_date、expected_count(按设备标称频率×在线时长推算)、received_count、missing_rate、anomaly_count(异常值数)、quality_score | FU-M14-11 统计主体；按日聚合供 M15 利用率分析（有数据时长占比） |
| iot_consume_error_log 消费错误日志 | error_id、queue_name(来源订阅队列)、raw_digest(原文摘要与载荷引用)、error_stage(解析/校验/落库/评估)、error_msg、status(待处理/已重放/已放弃)、replay_count、handled_by、handled_at | AMQP 主链路失败消息的落点（不经 fy.dlx，因遥测不走 MQ 总线）；管理界面可查可重放 |
| iot_consumer_stat 消费积压监控 | consumer_group(消费组/队列)、oldest_msg_age_secs(最旧消息年龄)、consume_rate、arrive_rate、backlog_estimate、sampled_at | 积压监控时序快照；>5 分钟告警、>30 分钟触发扩容预案（方案 3.1） |

关系要点：产品 1:N 设备；网关 1:N 子设备（设备.gateway_id）；设备 1:N 绑定历史（至多一条生效）；设备 1:N 遥测（超表）；规则 1:N 告警；告警 1:N 联动执行；产品/命令 1:N 命令日志。

## 5. 状态机与业务流程

- **iot_device**：`INACTIVE(未激活，已注册未首次上线) → ONLINE ⇄ OFFLINE`（在线/离线由 IoTDA 设备状态数据源驱动，网关代子设备上下线）；`ONLINE/OFFLINE → ABNORMAL(异常：频繁断连/数据断流/异常值超标，规则判定或人工标记) → 恢复回 ONLINE/OFFLINE`；任意状态 `→ DISABLED(停用，保留档案与历史数据)`，停用可逆回 `INACTIVE`。状态每次迁移发布 `iot.device.status-changed` 并留迁移日志。OFFLINE 持续超阈值由离线告警规则自动升级为告警。
- **iot_device_patient_binding**：`BOUND(绑定中) → UNBINDING(解绑中，解绑动作已受理但未确认，期间遥测不挂新归属) → UNBOUND(已解绑，历史留档终态)`；`UNBOUND` 数据永不删除（数据归属回溯依据）；新绑定必须由 `BOUND` 之外状态新建记录，禁止复用历史记录。
- **iot_alarm**：`TRIGGERED(触发) → NOTIFIED(已通知，通知扇出完成) → ACKNOWLEDGED(已确认，护士/医生认领) → PROCESSING(处理中) → CLOSED(已关闭，终态，必填处理记录)`；`TRIGGERED/NOTIFIED/ACKNOWLEDGED 停留超时 → 触发升级动作`（escalation_count 递增、向更高层级重复通知，状态不变，危急告警默认 5 分钟未确认即首次升级，可配置）；风暴态下 `TRIGGERED` 直接置 `suppress_flag` 且不发通知，人工可解除。
- **iot_command_log**：`ISSUED(已下发) → DELIVERED(已送达，设备确认收到) → SUCCESS(执行成功) / FAILED(执行失败，含设备返回错误)`；`ISSUED 停留超时 → TIMEOUT(超时，终态，触发告警)`；`DELIVERED → FAILED` 由异步命令状态回推驱动；全状态迁移留日志，终态不可变更。
- **iot_consume_error_log**：`PENDING(待处理) → REPLAYED(重放成功) / ABANDONED(放弃，必填原因)`；`REPLAYED` 前可多次重放（累加计数）。

主流程时序：
1. **遥测端到端（AMQP 主链路）**：设备经模式 A/B/C 上报 → IoTDA 规则引擎 SQL 过滤（按资源空间/产品/数据类型分流四条订阅队列）→ 本模块对应消费者组接收（客户端确认模式）→ 解析校验（设备档案匹配、物模型属性映射到 MDC 指标、生理极限粗校验、时间合理性标注）→ 患者关联（查绑定缓存快照患者/就诊号）→ 同设备哈希线程内攒批 → 批量写 TimescaleDB（唯一约束冲突忽略）→ 批量确认 → 并行旁路：告警评估组件消费同一标准消息评估阈值规则 → 命中即建告警实例（alarm_key 去重）。
2. **告警闭环**：告警实例建立 → 通知扇出（`iot.alarm.triggered` 经 fy.topic 扇出至大屏 WebSocket/PDA/手环，危急叠加 M01 通知中心短信）→ 护士确认（工作台/PDA，调本模块确认接口）→ 处理记录 → 关闭；超时未确认升级动作循环执行直至确认。
3. **绑定生命周期**：M04 入科/转床/出院事件（或护士手工操作）→ 校验设备与床位/患者有效性 → 绑定生效（BOUND，快照五元组）→ 发布 `iot.binding.changed` → M05/M16 刷新缓存；解绑受理置 UNBINDING → 确认后 UNBOUND 留档。
4. **模式 D 辅链路**：M20 解析 ORU^R01/R40 → 设备匹配（iot_device）→ 当前绑定查询 → 经 fuyun-common 定义的遥测移交 SPI 进入本模块遥测管道（接口在 common、本模块注册实现、M20 调用，与主链路共用解析校验/落库/告警评估，source 标记 HL7）——契约详见 M20 Spec 方案 3.4。
5. **命令下发**：操作者发起 → 权限校验 → 在线预检 → 二次确认（确认凭证）→ IoTDA 下发 → 结果回推/超时判定 → 命令日志闭环 → 审计留痕（同步落 M01 审计切面）。

## 6. 功能实现设计（逐 FU）

| FU | 实现设计要点 |
| --- | --- |
| FU-M14-01 IoTDA 对接底座（P0） | 接入凭证管理（键值对配置于环境变量，密钥明文不入库；凭证重置的热更新流程）；四条分类订阅队列与消费者组（方案 3.1）；TLS 强制、failover 断线重连（初始 3s 上限 30s 无限次，官方参数）；客户端确认模式：批量处理成功后统一确认，失败消息落 iot_consume_error_log 后确认抛弃（毒丸隔离），管理界面可查可重放；积压监控：消费端自采样最旧消息年龄/消费速率/到达速率入 iot_consumer_stat，超阈分级告警与扩容预案；HTTP 兜底通道：IoTDA 联动规则推送告警类消息至本模块独立端点 `POST /ingest/iotda-fallback`（独立鉴权，走 IoTDA 联动规则专用凭证而非 M01 令牌），与 AMQP 双写同一告警键去重（总 Spec 5.3-3）；时间戳鉴权依赖全院 NTP 对时（总 Spec 8 约定），偏差校验失败告警（时钟漂移会直接导致建链被拒） |
| FU-M14-02 产品与物模型管理（P1） | 按 IoTDA 产品维度建镜像（iot_product），同步任务经 IoTDA 开放 API 拉取产品与物模型定义存 JSON 快照；物模型属性/事件经映射配置关联 iot_metric_dict 的 MDC 编码实现跨品牌归一（如不同品牌监护仪的心率属性映射到 MDC_ECG_HEART_RATE）；命令登记安全等级（安全级/治疗级）作为 FU-M14-09 白名单数据源；镜像与云端失配检测（每日对账+变更即对齐），失配期间遥测按未映射属性原样入库（metric_code 记原生属性名）并告警，不静默丢弃；新设备类型上架流程：建产品→定义物模型→映射术语→标注命令等级→设备注册，全程管理台操作留痕 |
| FU-M14-03 设备注册与绑定（P0） | 设备档案管理台（单个注册/批量导入/二维码标签打印，标签含设备标识供 PDA 扫码）；注册流程经 IoTDA 设备管理 API 创建设备并获取一机一密凭证（凭证密文托管，档案只存引用）；绑定生命周期按方案 3.3：固定式/移动式双模式、绑定校验（设备未停用、患者在院且患者档案状态有效[非冻结/非合并，经 M02 解析服务校验]、床位有效）、解绑原因强制、绑定历史只增；M04 事件驱动的待绑定提醒与强制解绑；绑定查询接口供 M20/M05/M16 实时获取设备当前归属；消毒场景：解绑（原因=消毒）→ 消毒完成再绑定新床位，期间设备数据标"未关联"仍入库 |
| FU-M14-04 设备状态管理（P0） | 订阅 IoTDA 设备状态数据源（规则引擎资源类型=设备状态变更），驱动 iot_device 在线/离线状态机（网关代子设备上下线一并处理）；状态变更即发布 `iot.device.status-changed` 并刷新 Redis 实时快照（大屏/工作台在线率数据源）；设备影子：经 IoTDA 影子 API 查询 desired/reported 两区，护理/大屏展示设备最后上报状态与"最后上报时间"；离线告警：OFFLINE 持续超规则阈值自动生成离线告警（区分设备级与网关级——网关离线合并生成一条网关级告警并抑制子设备衍生告警，防风暴） |
| FU-M14-05 遥测接入管道（P0） | 标准遥测消息模型（设备号/指标编码/值/单位/发生时间/质量/来源）为管道统一语言（模式 A/B/C/D 四路同构）；解析校验五步（档案匹配→术语映射→数值粗校验→时间合理性→患者关联），时间偏差超阈值标 SUSPECT 不丢弃（数据质量参差是论文实证不足，调研依据 6）；攒批落库（条数阈值或时间窗触发、同设备哈希保序、唯一约束幂等）；背压：内存有界队列满则暂停拉取，prefetch 自然限流；波形白名单通道：抢救/麻醉场景白名单设备波形经边缘降采样后入库（独立配额，超配额自动降级并告警） |
| FU-M14-06 时序查询服务（P1） | 查询维度：按设备/按患者（经绑定历史展开其使用过的设备集合）/按病区聚合；查询路由与两级连续聚合实时联合见方案 3.2；降采样接口（1min/1h 桶，min/max/avg/first/last）；最新值接口（Redis 快照兜底，P99 亚秒，供护理体征双通道与床旁屏）；大范围查询强制聚合路由保护；患者维度查询隐含数据权限校验（数据范围注入，对齐 M01 约定），查询出口按 patient_id 先经 M02 EMPI 解析归一后再展开绑定历史（合并/拆分后归属收敛） |
| FU-M14-07 实时推送（P1） | WebSocket/STOMP：端点握手携带 M01 令牌鉴权；主题设计见第 8 节（告警与遥测主题分离、按病区订阅）；推送链路：本模块业务事件经 RabbitMQ 扇出队列到各应用实例推送网关，多实例会话全覆盖（调研依据 9）；遥测摘要主题 2 秒窗口按床位合并节流（对齐大屏刷新 ≤2s 且防 1009 大消息断连）；心跳检测断连（服务端 25s 级）+ 客户端指数退避重连 + 重连后按 REST 增量拉取快照补齐断连窗口（大屏不丢告警） |
| FU-M14-08 告警引擎（P1） | 三类规则源（设备报警透传/平台阈值/离线）+ 独立评估组件 + 五项风暴抑制 + 动作式超时升级（方案 3.4 全套）；规则管理台（规则 CRUD/启停/模拟测试——用历史遥测回放验证规则效果后上线）；分级通知策略：INFO 入库+大屏，WARNING 叠加 PDA/手环，CRITICAL 叠加 M01 通知中心短信；告警闭环接口（确认/处理/关闭）供 M05 工作台与 PDA 调用；告警统计（按病区/类型/等级/响应时长）供大屏与 M19 |
| FU-M14-09 命令下发（P1） | 方案 3.5 五步流程全套；命令白名单管理台（按产品/命令维护安全等级与放行状态，治疗级默认禁用、豁免开关为模块参数且每次使用告警审计）；下发通道按设备在线状态自动选同步/异步（异步结果经命令状态数据源回推更新日志）；下发前设备在线预检（离线设备走异步并提示送达时间不可控）；超时判定（同步命令平台超时/异步命令送达后执行超时）自动置 TIMEOUT 并告警；全要素命令日志与 M01 审计切面双留痕 |
| FU-M14-10 联动规则（P1） | 触发-条件-动作三段式规则配置（触发源：告警触发/遥测异常/设备状态变更；动作：调 M05 `POST /api/v1/nursing/tasks` 创建护理任务[适用场景=离床确认/设备断流确认]、转发 M16 呼叫与分级播报、经 M01 通知中心发通知、M16 门禁联动闭锁）；典型场景预置模板（输液告急→PDA 强提醒[经 M01 通知中心]+（病区配置）M16 分级播报/呼叫联动——不创建护理任务，护理侧按 M05 契约对在途执行单做升级动作，避免双路径任务风暴；离床→M05 创建护理任务+M16 呼叫转发；设备断流→M05 创建护理任务[护理确认设备状态]；冷链超标→双通道通知）；动作执行进程内调用目标模块接口，失败本地重试（指数退避），耗尽落 iot_linkage_log 告警（可人工重推）；执行结果发布 `iot.linkage.executed` 供审计订阅；联动规则与告警规则解耦（同一告警可挂多条联动） |
| FU-M14-11 数据质量监控（P1） | 断流检测：设备在线但超过标称频率 N 倍时长无数据 → `iot.telemetry.anomaly` 事件（联动护理确认设备状态）；缺数统计：按日比对标称频率×在线时长与实际接收量入 iot_data_quality_stat（直接回应论文"数据质量参差不齐"不足，调研依据 6）；异常值检测：生理极限硬校验（标 BAD）+ 同设备历史基线偏离统计（标 SUSPECT），均标注不阻断；质量看板（按病区/设备类型的缺失率/异常率 TopN）；设备利用率输出：有数据时长/在院时长，按日聚合供 M15 效益分析（FU-M15-05 数据源） |
| FU-M14-12 边缘网关管理（P1） | 网关档案（iot_gateway：模式 B/C、所属病区、版本、热备对端）；网关经 IoTDA 网关设备类型注册，子设备拓扑经 IoTDA 网关与子设备关系维护（子设备注册时必填所属网关，切换网关走 IoTDA 切换流程并在本模块同步档案）；网关健康监控（在线状态、子设备掉线率，网关离线生成网关级告警并抑制子设备告警风暴）；双网关热备要求写入部署规范（总 Spec 5.2 网关单点风险对策），主备切换检测告警；网关程序 OTA 预留（IoTDA 软固件升级能力接口位，本期不实现） |
| FU-M14-13 IoT 运营大屏（P1） | 大屏数据源：Redis 实时快照（设备在线率/消费积压/告警未确认数）+ 连续聚合查询（告警趋势/遥测吞吐）+ iot_consumer_stat（积压年龄/速率）；视图：全院视图（在线率、设备类型分布、告警分级分布、遥测吞吐曲线、积压水位）与病区视图（床位设备状态墙、未确认告警列表）；WebSocket 主题实时刷新（≤2s，全院视图经 `/topic/iot/dashboard/global` 全院主题、病区视图经各病区主题）+ REST 快照兜底；积压水位、风暴态横幅提示 |

## 7. 对外接口

**REST（`/api/v1/iot/` 前缀，响应统一 `{code, message, data, traceId}`）**：
- 设备档案：`GET/POST/PUT /devices`、`POST /devices/{id}/enable|disable`、`GET /devices/{id}/shadow`（影子最后状态）
- 产品物模型：`GET/POST /products`、`POST /products/{id}/sync`（镜像同步）、`GET/POST /metrics`（术语字典与映射）
- 绑定：`GET /bindings/current?deviceId=|patientId=|bedId=`、`POST /bindings`（绑定）、`POST /bindings/{id}/unbind`（解绑，必填原因）
- 遥测查询：`GET /telemetry/query`（设备/患者/指标/时间窗/降采样间隔）、`GET /telemetry/latest`、`GET /telemetry/waveform`（波形白名单通道，P1 按需）
- 告警：`GET /alarms`（按病区/状态/等级）、`POST /alarms/{id}/ack|processing|close`（闭环操作，close 必填处理记录）、`GET/POST/PUT /alarm-rules`、`POST /alarm-rules/{id}/simulate`（历史回放测试）
- 命令：`POST /commands/confirm-challenge`（获取二次确认凭证）、`POST /commands`（下发，须携带凭证）、`GET /command-logs`、`GET/PUT /command-whitelist`
- 联动：`GET/POST/PUT /linkage-rules`、`GET /linkage-logs`
- 质量与网关：`GET /quality/stats`、`GET /quality/device-usage`（供 M15）、`GET/POST /gateways`、`POST /gateways/{id}/sync-topology`
- 运维：`POST /ingest/iotda-fallback`（IoTDA HTTP 兜底通道接收端点，独立鉴权[联动规则专用凭证]）、`GET /monitor/consumer-lag`（积压）、`GET /consume-errors`、`POST /consume-errors/{id}/replay|abandon`（重放/放弃，放弃必填原因）、`GET /dashboard/summary`（大屏快照）

**内部服务接口（进程内）**：遥测移交接口（供 M20 模式 D，入参为标准遥测/报警消息，含设备匹配结果校验）；当前绑定查询接口（供 M20/M05/M16）；设备档案匹配接口（供 M20 报文设备识别）。

**MQ 事件（`fy.topic`，信封遵循 README 约定，全量登记 event_registry）**：
- 发布：`iot.alarm.triggered`（告警触发，payload 含告警号/设备/患者/病区/等级/指标与触发值）、`iot.alarm.escalated`（超时升级）、`iot.alarm.closed`（闭环完成，供 M05/M16 复位与统计）、`iot.device.status-changed`（设备上下线/异常）、`iot.binding.changed`（绑定/解绑，payload 含五元组与变更类型）、`iot.telemetry.anomaly`（断流/异常值，联动业务用）、`iot.command.completed`（命令终态，供审计与运维订阅）、`iot.linkage.executed`（联动执行结果）、`iot.call.triggered`（呼叫信令上行：设备号/呼叫类型/床位/触发时刻，供 M16 路由建 ward_call）、`iot.access.passed`（门禁通行结果：门点/方向/结果/介质摘要，供 M16 落通行台账）。事件型设备上行按业务语义拆分为上述具体事件，不设泛化 `iot.device.event`（统一审查裁决）；本模块全部发布事件走 outbox + 发布确认，遵循 M20 事件总线治理约定
- 订阅：`system.dict.published`（M01 国标字典引用刷新——患者上下文/单位等展示字典；MDC 指标术语字典为本模块自管专业字典 iot_metric_dict，不经 M01）、`system.org.changed` / `system.param.changed`（M01 主数据：病区组织、模块参数热刷新）；M04 住院事件 `inpatient.visit.admitted` / `inpatient.visit.transferred` / `inpatient.visit.discharged`（驱动绑定提醒与强制解绑，事件名已经 M04 Spec 在 event_registry 登记核实）；M05 事件 `nursing.task.created` / `nursing.infusion.started` / `nursing.infusion.completed`（输液告警↔任务↔传感器关联建立与输注结束后的监测复位）；M15 资产事件 `asset.asset.scrapped`（必选：驱动 iot_device 置 DISABLED）、`asset.asset.created` / `asset.asset.changed`（asset_ref 冗余引用维护）、`asset.metrology.overdue`（绑定/命令下发界面合规标识，最低保障）；M02 患者事件 `patient.merged` / `patient.split`（成对订阅：绑定校验与遥测/告警归属经 EMPI 归一刷新）、`patient.frozen` / `patient.unfrozen`（成对订阅：冻结档案拒绝新绑定、解冻恢复）。消费队列按 README 约定命名（`q.iot.<事件名>`，如 `q.iot.system.dict.published`、`q.iot.inpatient.visit.transferred`）；消费一律手动确认 + `integration.received_event` 幂等。

**WebSocket 主题（STOMP，端点 `/ws/iot`，握手鉴权）**：
- `/topic/iot/alarm/{wardId}`：病区告警主题（触发/升级/关闭事件，独立主题保证告警低延迟不与遥测争抢）
- `/topic/iot/telemetry/{wardId}`：病区遥测摘要主题（2 秒窗口按床位合并节流）
- `/topic/iot/device-status/{wardId}`：病区设备状态主题（上下线/离线告警提示）
- `/topic/iot/dashboard/global`：全院运营视图主题（设备在线率/告警分级分布/遥测吞吐/消费积压水位，供全院 IoT 运营大屏）
- 消费方：web-bigscreen（护士站大屏/运营大屏）、web-workstation（护士站/医生站）、床旁屏（经 M16 服务端聚合转发，不直订本模块 WebSocket——对齐 M16 终端聚合红线）；PDA 告警推送经 M01 通知通道（站内/短信）而非直连本模块 WebSocket。

## 8. 集成点

- **依赖上游**：M01（令牌鉴权、RBAC+数据范围权限、审计切面、通知中心发送、系统参数、国标字典/组织广播）；M02（patient_id 归一与解析服务——绑定校验含患者档案状态有效[非冻结/非合并]）；M04（visit_id、床位有效性校验、入科/转床/出院事件订阅）；M05（`nursing.task.created`/`nursing.infusion.started`/`nursing.infusion.completed` 事件订阅；联动动作"创建护理任务"出向调 `POST /api/v1/nursing/tasks`[适用场景=离床确认/设备断流确认]）；M15（asset_ref 互引与 `asset.asset.created/changed/scrapped`、`asset.metrology.overdue` 订阅；`GET /api/v1/asset/usage-eligibility` 可用性校验——收窄口径：仅命令下发前同步调用，绑定场景走 overdue 事件+本地缓存合规标识）；M16（联动动作出向调用：分级播报与呼叫承接接口、门禁闭锁/恢复接口）；M20（event_registry 登记、幂等构件经 fuyun-common 接口使用[实现由 M20 运行时装配、表在 integration schema]、死信治理对本模块 MQ 事件的覆盖）。
- **联动动作出向调用声明**：联动规则动作（创建护理任务/呼叫转发/发送通知/门禁联动）为进程内出向调用目标模块接口；失败按指数退避本地重试，重试状态与耗尽告警由 iot_linkage_log 承载（status=重试中/失败、fail_reason 留痕、支持人工重推），重试不阻塞告警主链路。
- **被下游依赖**：M05（FU-M05-02 体征自动采集双通道的最新值与历史查询；FU-M05-06 输液告急事件；FU-M05-08 大屏的告警与遥测主题）；M16（FU-M16-01/02/03/06/07/08/09 的告警事件、遥测查询、绑定查询与 WebSocket 推送；FU-M16-04 床旁终端远程管理经本模块命令通道[白名单安全级]）；M10（FU-M10-04 麻醉记录单按手术时段拉取监护/麻醉机遥测，含波形白名单通道）；M11（FU-M11-01/04 重症护理记录采集与统一报警，复用本模块告警引擎与查询接口）；M15（FU-M15-05 设备利用率/开机率统计数据源）；M20（模式 D 遥测移交目标、设备匹配与绑定查询的被调方）。
- **与 M20 的关系**：AMQP 主链路不经 M20（总 Spec 4.5）；模式 D 辅链路由 M20 转换后经 fuyun-common 定义的遥测移交 SPI 进程内进入本模块管道（接口在 common、本模块注册实现、M20 调用，integration 与 iot 零编译期依赖、无 Maven 循环）；幂等构件经 fuyun-common 接口使用（实现由 M20 运行时装配，表仍在 integration schema）；本模块全部 MQ 事件受 M20 事件总线治理约束（登记、outbox+发布确认、幂等、死信）。
- **与 M01 的关系**：MDC 指标术语字典为本模块专业字典（iot schema 内自管 iot_metric_dict），不经 M01 字典体系；患者上下文/单位等国标字典引用 M01 并订阅 `system.dict.published` 刷新；病区/组织引用 M01 主数据并订阅广播。
- **与 M05/M16 的联动关系**：事件侧——订阅 M05 输液任务/输注事件建立告警↔任务↔传感器关联与监测复位（事件订阅不构成编译期反向依赖，按 README v1.1 判定口径申报）；动作侧——联动动作"创建护理任务"调 M05 `POST /api/v1/nursing/tasks`（仅离床确认/设备断流确认场景），"呼叫转发/分级播报"与"门禁联动"调 M16 承接接口；出向调用失败重试语义见上（iot_linkage_log 承载）。
- **与 M15 的关系（双实体互引）**：iot_device.asset_ref 为展示级冗余引用（资产业务属性权威在 M15，反向引用列为其 iot_device_ref），经 `asset.asset.created/changed/scrapped` 幂等消费维护引用与停用联动（scrapped→iot_device 置 DISABLED）；`asset.metrology.overdue` 驱动绑定/命令下发界面合规标识；`GET /usage-eligibility` 仅命令下发前同步调用（收窄口径）。

## 9. 非功能与安全

- 性能：遥测管道常态 50 msg/s、峰值 500 msg/s（总 Spec 8），批量落库下单批万级行写入 P95 < 1s；最新值查询 P99 < 100ms（Redis 快照）；历史查询（聚合路由）P95 < 1s；告警端到端时延（数据到达→通知发出）P95 < 2s；大屏刷新 ≤2s（总 Spec 8）。
- 容量：遥测明细 90 天在线（按 432 万行/天外推约 3.9 亿行，压缩后存储可控，社区实践压缩率 90%+，调研依据 10）；两级聚合 1 年；告警/命令/绑定历史长期保留（医疗审计要求）；消费错误日志保留 180 天。
- 可用性：消费者多实例（实例数×连接数 ≤ 单凭证 32 连接上限并预留余量）；单实例故障 failover 自动重连不丢消息（客户端确认未完成即重推）；积压分级预案（>5 分钟告警人工介入、>30 分钟扩容实例）；HTTP 兜底通道独立于 AMQP 进程；TimescaleDB 随 PostgreSQL 主备流复制容灾（总 Spec 8：RPO ≤15min、RTO ≤4h）。
- 安全：设备凭证密文托管（明文不入库、不入日志）；命令三级管控（白名单/二次确认/审计，方案 3.5）；遥测与告警中的患者身份字段脱敏展示（对齐 M01/M02 脱敏规则）；患者维度查询强制数据范围校验（病区/科室范围外 403 并审计）；WebSocket 握手鉴权与主题订阅数据范围校验（不得跨病区订阅）；全接口审计切面覆盖（等保三级安全审计）。
- 合规映射：等保三级（身份鉴别/访问控制/审计/TLS 传输加密）；数据安全法/个保法（患者数据脱敏、查阅留痕）；医学装备管理规范（设备档案、利用率数据支撑质控考核）；数据质量治理（回应 ICU 论文实证不足，调研依据 6）。

## 10. 测试要点

- 正常：模式 A 直连设备端到端（注册→绑定→上报→落库→查询→推送全链路）；阈值规则触发→通知→确认→处理→关闭闭环全流程；模式 D 报文经 M20 移交后与主链路行为一致（落库/告警/事件）；设备影子离线可读最后状态；绑定历史按时间回溯正确还原任一时点归属；两级连续聚合与明细实时联合查询结果一致。
- 边界：消费者断线重连后重复投递同一批消息，遥测/告警均不重复（唯一约束兜底）；AMQP 积压达 5 分钟/30 分钟阈值分级告警触发；HTTP 兜底通道与 AMQP 双写同键告警仅产生一个实例；转床瞬间（解绑中）到达的遥测不挂任何患者（标未关联）；出院强制解绑后设备数据不再关联该患者；查询窗恰好 24h/90 天边界的路由正确；临界值抖动（在阈值带内反复穿越）不产生重复告警；网关离线只产生网关级告警且子设备告警被抑制；同一凭证 32 连接上限内多实例正常建链，时钟偏差超 5 分钟建链失败有明确告警。
- 异常：IoTDA 不可达时消费者重连不崩溃且恢复后继续消费；毒丸消息（格式损坏）落消费错误日志不阻塞队列，重放成功；告警评估组件故障不影响遥测落库与确认；落库失败不确认触发重推且不产生重复业务；命令下发设备离线走异步并在超时后置 TIMEOUT 告警；治疗级命令默认被拒且留审计；联动动作目标模块失败重试耗尽后告警可人工重推；风暴态自动进入与解除、抑制告警解除后汇总补通知。
- 安全：跨病区用户查询患者遥测/订阅告警主题 403 且留审计；凭证泄露演练（凭证重置热更新后旧凭证失效）；命令确认凭证过期或重放被拒；日志与接口响应中患者敏感字段脱敏有效性验证。

## 11. 自审记录

- [x] 无 TBD/TODO/占位符，文档头 + 12 节内容完整
- [x] 覆盖 FU-M14-01~13 全部条目，无遗漏、无私增（FU-M14-12 的 OTA 按 P1 预留接口位定位细化，未提前实现；FU-M14-06 波形查询属总 Spec 5.4"按需开启"的配套细化）
- [x] 内部一致：领域模型 ↔ 状态机 ↔ 接口 ↔ 测试一一对应（iot_device/binding/alarm/command_log/consume_error_log 五个状态机均有对应 API、流程与测试项；iot_consumer_stat/iot_data_quality_stat 有查询接口与测试场景）
- [x] 符合跨模块约定：schema=iot；事件命名 `<模块>.<实体>.<动作>`；信封含 eventId/occurredAt/producer；MQ 消费幂等统一落 integration.received_event；交换机只用 fy.topic 三件套；患者关联引用 patient_id+visit_id；无跨模块读表
- [x] 依赖方向正确：按 README v1.1 判定口径（同步 API 调用方=依赖方；事件订阅不构成依赖方向）——同步依赖 M01/M02/M04/M20[幂等构件经 fuyun-common 接口]与联动动作出向调用的 M05/M16 接口、M15 可用性校验接口；对 M05 输液事件、M15 asset.*、M02 patient.* 的事件订阅不构成编译期反向依赖，已在文档头与第 8 节申报；M05/M16/M10/M11/M15/M20 经本模块接口与事件消费
- [x] 方案推导 5 个关键点均有备选对比与依据（含任务要求的遥测消费架构、TimescaleDB 建模、绑定模型、告警引擎、命令下发安全五点），每个结论附调研来源
- [x] 无代码级实现（无类名/方法体/SQL DDL 全文；表设计为"表-关键字段-约束"粒度；TimescaleDB 策略以文字描述参数与理由；Qpid JMS/failover/prefetch 为产品与协议参数名，非代码实现）
- [x] 歧义消除：AMQP 消费确认模式（客户端确认+批处理确认）、双层幂等（明细唯一约束/业务事件键）、双通道去重（同一告警键）、固定式与移动式绑定语义、解绑中归属判定、升级为动作非状态、波形白名单配额策略均已显式定义
- [x] 术语与总 Spec 一致（物模型/资源空间/网关与子设备/遥测/波形/边缘降采样/模式 A-D/双网关热备/ Rosetta/MDC）

## 12. 与总 Spec 的偏差

两处细化澄清（统一审查终审均已采纳）：
1. **AMQP 消费确认模式（已闭环）**：总 Spec 5.3-3 原表述为"自动确认"，本 Spec 细化为告警/状态/命令队列采用**客户端确认**（批量处理成功后确认）+ 幂等。理由：官方 Demo 默认的自动确认在客户端处理中崩溃时消息已确认无法重推，医疗告警"不丢"优先级高于消费吞吐；华为接入文档明确两种模式均受支持且"中断未确认数据重连后重推"的约束在客户端确认下更可靠地成立（调研依据 1、2）。遥测队列同用客户端确认以统一语义。统一审查终审采纳，总 Spec 5.3-3 已同步修订为"关键队列客户端确认+幂等"。
2. **订阅 M04 住院事件（已闭环）**：总 Spec 未显式列出 M14 订阅 M04 事件，本模块按绑定生命周期（FU-M14-03）声明对入科/转床/出院事件的订阅。统一审查终审采纳，事件名已经 M04 Spec 在 event_registry 登记（`inpatient.visit.admitted/transferred/discharged`）核实一致。

其余无偏差：设备接入矩阵（15 类）、边缘模式 A/B/C/D 归宿、遥测直连不经 MQ（D2）、波形默认边缘降采样（D3）、命令白名单+二次确认+审计（D8）、告警双通道兜底均与总 Spec 第 4/5 章一致（数据量估算常态值已按总 Spec v1.1 统一为 50 msg/s）。

### v1.1 统一审查修订记录

按统一交叉审查报告 Round 1 裁决执行，本版落地项：
- **B-5**：FU-M14-10 预置联动模板"输液告急"改为"PDA 强提醒（经 M01 通知中心）+（病区配置）M16 分级播报/呼叫联动"；"创建护理任务"动作收窄为离床确认/设备断流确认场景（调 M05 `POST /api/v1/nursing/tasks`），消除与 M05"告警升级挂执行单、不新建任务"契约的双路径任务风暴冲突。
- **M-2/M-23**：文档头上游依赖补 M05/M16（事件与联动动作级）；§7 显式点名订阅 `nursing.task.created`/`nursing.infusion.started`/`nursing.infusion.completed`；§8 补联动动作出向调用声明与失败重试语义（iot_linkage_log 承载）；§11 自审按 README v1.1 判定口径修正（事件订阅与联动动作调用不构成编译期反向依赖，但须申报）。
- **M-3**：与 M20 的 Maven 循环依赖消除——幂等构件改为经 fuyun-common 接口使用（实现由 M20 运行时装配、表在 integration schema）；模式 D 移交改为 SPI（接口在 common、本模块注册实现、M20 调用）；文档头、§1、§5 主流程 4、§8 与 M20 关系同步改写。
- **M-19**：新增发布 `iot.call.triggered`（呼叫信令上行）与 `iot.access.passed`（门禁通行结果）供 M16 订阅；不设泛化 `iot.device.event`。
- **M-20**：iot_device 增补 asset_ref 展示级冗余列（权威在 M15 asset.iot_device_ref）；订阅补 `asset.asset.scrapped`（必选，置 DISABLED）、`asset.asset.created/changed`（引用维护）、`asset.metrology.overdue`（合规标识）；`GET /usage-eligibility` 采用收窄口径（仅命令下发前同步调用）。
- **M-22**：WebSocket 消费方"床旁屏"改为经 M16 服务端聚合转发、不直订。
- **M-25/R1-14**：订阅补 `patient.merged`/`patient.split`（成对）与 `patient.frozen`/`patient.unfrozen`（成对）；绑定校验补患者档案状态校验（经 M02 解析服务）；遥测/告警查询出口按 patient_id 先经 EMPI 归一。
- **R5-08**：WebSocket 增补 `/topic/iot/dashboard/global` 全院运营主题（FU-M14-13 同步）。
- **R5-09**：REST 补 `POST /ingest/iotda-fallback`（HTTP 兜底通道接收端点，独立鉴权）。
- **R5-10**：§8 被下游依赖 M16 条目补 FU-M16-04（床旁终端远程管理经命令通道）。
- **R1-13**：全部发布事件走 outbox + 发布确认声明补齐。
- **R1-15**：telemetry source 枚举改为 `IOTDA`（AMQP 主链路，含模式 A/B/C）/`HL7`（模式 D 辅链路）。
- **R1-16**：`system.dict.published` 订阅用途澄清（国标字典引用刷新；MDC 指标术语字典为本模块自管 iot_metric_dict，不经 M01）。
