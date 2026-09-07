# 实现顺序与依赖说明（模块编排总纲）

| 文档属性 | 内容 |
| --- | --- |
| 文档编号 | SPEC-MOD-00 |
| 版本 / 状态 | v1.1 / 统一审查修订（修订记录见 §9） |
| 日期 | 2026-09-06 |
| 上游文档 | `docs/specs/00-master-spec.md`（总 Spec v1.0 第 3 章模块地图、第 9 章分期路线图）、`docs/specs/modules/README.md`（跨模块约定）、20 份模块 Spec（01~20） |
| 性质 | 编排说明：仅汇总与排序，不新增/修改任何功能需求；与任一模块 Spec 冲突时，以该模块 Spec 原文及统一审查裁决为准 |
| 引用规则 | 本文所有结论均标注依据来源，格式为（MM Spec §节号）或（总 Spec §节号）；事件名/API 以各模块 Spec 第 7 节在 `event_registry` 的登记为准 |

---

## 1. 编制说明

本文档通读全部 20 份模块 Spec 的【文档头上游依赖/下游被依赖】【集成点（§8）】【对外接口（§7，REST/MQ 事件/WebSocket）】三节，结合总 Spec 第 9 章 P0~P6 路线图，产出：

1. 依赖矩阵总表（§2）：模块 × 依赖模块 + 契约内容摘要；
2. 拓扑排序与分层（§3）：L0~L6 分层、层内并行组；
3. 推荐实现顺序（§4）：与总 Spec 路线图逐阶段对齐，含进入条件与交付验证物；
4. 关键契约冻结点清单（§5）：必须先于下游开发冻结的跨模块事件/API 契约；
5. 风险提示（§6）与依赖异常清单（§7，提交统一审查核实）。

阅读前提（来自 README §3 的全局约定）：模块间禁止跨模块读表，仅经服务接口与事件交互；一切事件先登记 `event_registry` 后发布、消费一律经 `integration.received_event` 幂等。因此本文所称"依赖"指**契约依赖**（调用其 API、订阅其事件、遵守其结构规范），不代表编译期实现依赖。

---

## 2. 依赖矩阵总表

依据：各模块 Spec 文档头"上游依赖"与 §8 集成点。括号内为关键契约（事件名 / API / 结构规范）。

| 模块 | 上游依赖（含契约内容摘要） |
| --- | --- |
| M01 系统与权限 | 无（全系统根模块）。被全部模块依赖：认证令牌、RBAC、`POST /api/v1/system/practice/check` 执业授权校验、字典 code、组织/员工主数据、通知中心、审计切面、打印模板、CA 电子签名（M01 Spec 文档头/§7/§8） |
| M20 集成平台 | M01（接口账号/权限/审计/主数据源）；M02（报文患者匹配查询）；M14（设备匹配、绑定查询、遥测管道移交接口——仅模式 D/FHIR 功能切片）。对全体模块提供：事件总线治理（`fy.topic`/`fy.dlx`/`fy.delay`、信封 eventId/occurredAt/producer）、幂等构件、死信处理、出站留痕、`push_task` 上报骨架（M20 Spec 文档头/§8） |
| M02 患者 EMPI | M01（认证/RBAC/字典/审计/通知/CA）；M20（总线治理、幂等、出站留痕）。对外核心契约：`patient_id` 发号、**visit_id 结构规范**（`O\|I+yyyyMMdd+5 位流水` 14 位定长，签发主体唯一：M03 签 O、M04 签 I，M02 Spec §3.4）、患者上下文解析与 FROZEN/MERGED 拦截、"在途就诊查询"SPI 扩展点（M03/M04 注册实现，M02 Spec §8） |
| M13 收费医保 | 实现前置仅 M01/M02；对 M03/M04/M06/M07/M08/M09/M10 的关系为**订阅其事件与提供 API 的被依赖侧**。核心契约：事件开单→PENDING 费用、预结算/结算/退费/押金/一日清单同步 API、`billing.fee.created/confirmed`、`billing.settlement.completed`（放行）、`billing.refund.approved`（退费放行）、`billing.deposit.changed`（欠费/出院校验）、`billing.charge-item-price.published`（调价广播）、价格版本化+计费快照（M13 Spec §3.4/§7/§8） |
| M03 门诊服务 | M01（含执业授权强校验）；M02（解析/冻结拦截/过敏项/visit_id 签发 O 型/SPI 注册）；M13（划价/预结算/结算/退费 API + `billing.settlement.completed`/`billing.refund.approved` 放行回流，混合时序见 M03 Spec §3.4）；M06（开方同步 API、`pharmacy.prescription.created/cancelled`、`pharmacy.dispense.completed/returned` 回执）；M07/M08（`lab.report.published`/`imaging.report.published` 报告放行回执）；M09（门诊病历文书嵌入、诊疗段数据集）；M20（总线/幂等/延迟队列 `delay.appointment-timeout`）（M03 Spec 文档头/§8） |
| M04 住院管理 | M01；M02（visit_id 签发 I 型、SPI 注册）；M09（病历编辑嵌入、文书时限素材）；M13（住院计价/停费/押金/出院结算 API + `billing.deposit.changed`/`billing.settlement.completed`）；M06（`pharmacy.medication-order.audit-completed/rejected` 审方回执、摆药）；M20。对外核心契约：**医嘱状态机事件族** `inpatient.order.audited`（携带类型子键 lab/exam/surgery/blood/drug… 分发）、`order.transferred`、`order-plan.generated`、`order.stopped/cancelled/revoked/executed`、`POST /order-plans/{no}/execute-confirm` 执行回签、visit 事件族 `inpatient.visit.admitted/transferred/discharged`（M04 Spec §3.3/§3.4/§7/§8） |
| M05 护理管理 | M01；M02；M04（`inpatient.order.transferred/order-plan.generated/stopped/cancelled/revoked` 订阅、`execute-confirm` 回签、病区/床位/护理级别）；M06（`pharmacy.dispense.completed` 摆药签收、药品查询 API、退药受理）；M14（遥测查询/最新值、`iot.alarm.*`、`/ws/iot` 三主题复用、联动规则回调 `POST /tasks`）；M20。对外核心契约：`nursing.order-execution.completed`（M04 双路对账）、`nursing.infusion.started/completed`（M14 建立/解除关联）、`nursing.vital-sign.recorded`（M09/M11）、执行占用查询 API（M13 退费硬前置）（M05 Spec §3.2/§7/§8） |
| M06 药事管理 | M01；M02（过敏项嵌查+`patient.health-summary.updated`）；M03（`outpatient.order.charged` 发药放行、退药指令终态、开立入口编排）；M04（医嘱审核/变更/停止事件、出院带药开方调用）；M13（药品收费项目与价格权威、`billing.settlement.completed`/`refund.approved`、执行占用查询被调）。对外核心契约：开方 API（处方主数据权威）、`pharmacy.prescription.created` **携带药品计费行**、`pharmacy.dispense.completed/returned`、`pharmacy.narcotic.alerted`、毒麻借用/核销 API（M06 Spec §3.5/§7/§8） |
| M07 检验 LIS | M01；M02；M03（`outpatient.order.charged` 受理放行）；M04（`inpatient.order.audited` lab 子键）；M13（计费订阅被对齐：`lab.specimen.received` 计费触发、`lab.result.uploaded` 执行占用、`GET /execution-status` 退费硬拦截、`billing.refund.approved` 回流）；M20（仪器 ASTM/HL7 统一通道）。对外核心契约：**危急值闭环引擎**（`POST /api/v1/lab/critical-values` 供 M08 登记 EXAM 域实例、`lab.critical-value.notified/accepted/closed`）、`lab.apply.accepted`、`lab.report.published/corrected`（M07 Spec §3.5/§7/§8） |
| M08 检查影像 | M01；M02；M03（`outpatient.order.charged`）；M04（`inpatient.order.audited` exam 子键）；**M07（危急值引擎单向 API 调用，M07 不依赖 M08，M08 Spec §8）**；M13（`imaging.exam.registered/completed` 计费双时点、`GET /execution-status`）；M20（DICOM 通讯不经 M20，M08 Spec §8）。对外核心契约：`imaging.apply.accepted`、`imaging.exam.registered/completed/cancelled`、`imaging.report.published/corrected`、DICOM C-STORE/MWL 内建组件（M08 Spec §3.1/§7） |
| M09 电子病历 | M01（含 CA）；M02（脱敏/明文查阅/合并事件）；M03（visit 上下文、`outpatient.visit.finished`）；M04（`inpatient.visit.*`、`inpatient.consultation.completed`）；M05（护理取数 API、`nursing.vital-sign.recorded`）；M06/M07/M08（取数 API 与发布事件订阅）；M10（手术事件时钟源）；M12（输血取数，P2）；M13（费用汇总 API、清单契约）；M14（`GET /telemetry/query` 遥测取数）；M20。对外核心契约：文书编辑器嵌入（供 M03/M04/M10/M18）、`GET /visit-segments/{visitId}` 诊疗段、`emr.homepage.submitted` + `GET /homepages/by-visit/{visitId}` + `POST /homepages/{no}/return` 首页↔结算清单契约（M09 Spec §3.4/§7/§8） |
| M10 手术麻醉 | M01（手术分级授权）；M02；M04（surgery 子键分发、`discharge-requested`）；M05（术前准备执行回执）；M06（`POST /narcotic-cabinets/{id}/withdrawals` 借用、`POST /ampoule-returns` 核销、`pharmacy.narcotic.alerted`）；M07/M08（结果查询）；M09（文书编辑嵌入、手术事件时钟源契约）；M13（计价权威）；M14（遥测查询、`/telemetry/waveform` 白名单通道、`iot.telemetry.anomaly`/`iot.alarm.triggered`）；M15（设备档案查询）；M20。对外核心契约：`surgery.surgery.started`（携术式计费行）、`surgery.surgery.finished`（M09 首页手术行权威源+M13 耗材计费截止）、`surgery.charge-line.registered`（携 UDI）、`surgery.pacu.discharged`（M05 回病区/M11 转入衔接）、`GET /surgery-execution-status`（M10 Spec §7/§8） |
| M11 重症 ICU | M01；M02；M04（转科编排事件、医嘱语义权威）；M05（点测体征仲裁权威、出入量明细账 io_record/小结 io_summary 落点、ICU 病区通道开关成对配置）；M07（评分取数、危急值订阅）；M10（`surgery.pacu.discharged` 去向=ICU）；M14（遥测/绑定/告警引擎复用）；M20。对外核心契约：`icu.patient.admitted`（M10 闭环回签）、`icu.score.completed`、`icu.fluid-balance.closed`（M11 Spec §8） |
| M12 输血管理 | M01；M02；M04（blood 子键、停嘱联动、回执聚合）；M05（PDA 三向扫码组件复用[组件级，非服务依赖]、`POST /vital-signs` 体征权威）；M07（常规检验链路、血型初检引用、危急值登记）；M09（知情同意文书校验）；M13（计费触发订阅、`GET /irreversible-check` 发血不可逆）；M16（储血冰箱冷链告警订阅）；M20。对外核心契约：`transfusion.apply.created`、`transfusion.specimen.received`（检测费计费）、`transfusion.blood.issued`（血费计费）、`transfusion.infusion.started/completed`（M04 回执/M05 出入量）、`transfusion.inventory.changed`（M12 Spec §7/§8） |
| M14 设备物联网 | M01；M02（patient_id 归一）；M04（visit_id/床位校验、`inpatient.visit.admitted/transferred/discharged` 订阅驱动绑定/解绑）；M20（event_registry/幂等/死信；模式 D 报文移交对端）。对外核心契约：**标准遥测消息模型**（模式 A/B/C/D 四路同构，M14 Spec §3.1/§7）、`iot.alarm.triggered/escalated/closed`、`iot.device.status-changed`、`iot.binding.changed`（绑定五元组）、`iot.telemetry.anomaly`、`iot.command.completed`、`iot.linkage.executed`、`/ws/iot` 三主题（告警/遥测摘要/设备状态）、`GET /quality/device-usage` 利用率日统计（M14 Spec §7） |
| M15 设备资产 | M01；M02；M13（收入统计只读 API 日终拉取，不订阅 `billing.*`）；M14（`GET /quality/device-usage`、`iot.device.status-changed` 订阅）；M07/M08（工作量/利用率统计 API）；M10（手术设备使用事实查询）；M16（`ward.cold-chain.alert-archived` 订阅、RTLS 位置查询）；M20。对外核心契约：资产权威主数据（`asset.asset.created/changed/scrapped` 驱动 M14/M16 引用维护）、`asset.metrology.overdue` 合规标识、`GET /usage-eligibility` 可用性校验（两处为对 M14 的增补请求，见 §7 异常）（M15 Spec §8/§12） |
| M16 智慧病房 | M01；M02；M04（visit/床位事件、`inpatient.bed.changed`、主管医生与医嘱摘要查询）；M05（`POST /tasks` 任务创建、在途输液执行单查询、护理级别/责任护士、体征落卡权威在 M05）；M13（一日清单/费用/押金/支付 API、`billing.deposit.changed`）；M07/M08（已发布报告查询）；M14（遥测/告警引擎/绑定/联动动作承接/命令通道/WebSocket 复用、`iot.call.triggered`/`iot.access.passed` 订阅（M14 v1.1 已登记））；M15（冷链/定位资产号引用）；M20。对外核心契约：`ward.call.created/answered/completed/transferred/escalated`（M05 大屏聚合订阅）、`ward.baby-guard.*`、`ward.cold-chain.alert-archived`、`ward.rtls.geofence-alerted`（M16 Spec §7/§8/§12） |
| M17 体检 PEIS | M01；M02；M07/M08（`peis.checkin.registered` 按 lab/exam 子键分发、报告事件订阅、危急值引擎复用）；M13（预计价/结算/退费 API、`billing.settlement.completed/refund.approved` 订阅、体检来源幂等豁免[偏差 2/3/4 已申报]）；M14（遥测取数，经 FU-M14-06）；M20（M17 Spec §8/§12） |
| M18 互联网医院 | M01；M02（建档/实人认证）；M03（线上 visit 签发权威、号源/预约/开单 API、visit 事件）；M06（INTERNET 开方 API、审方引擎、发药管线）；M09（文书模型/编辑器复用）；M13（线上收银台/医保移动支付、`billing.settlement.completed/refund.approved`）；M07/M08（报告查询）；M20（流转/监管/配送/媒体全部经其通道）。对外核心契约：`internet.consultation.*`、`internet.prescription.flowed/accepted/dispensed/delivered/signed/returned`（M18 Spec §8） |
| M19 运营决策 | M01；M03/M04（工作量统计 API，接口位）；M05（`GET /stats/nursing-workload` 等）；M07（危急值/TAT/工作量 API）；M08（影像报告/检查工作量统计 API）；M09（质控/首页/编码统计 API）；M10（手术工作量统计 API）；M13（收入统计只读 API，仅 `billing.daily-statement.closed` 作装配触发不作取数）；M15（装备指标/效益分析 API）；M16/M17/M18（呼叫响应时长/体检人次与随访率/互联网诊疗量 stats API，P6 启用接口位）；M20（`push_task` 传输骨架）。对全模块零写接口（红线 1）（M19 Spec §8） |

---

## 3. 拓扑排序与分层

分层判据：**实现前置**——该模块开工/联调所必需的契约是否已由更低层冻结。对 M13/M20 这类"上游业务依赖均为订阅其事件或被其调用"的模块，按被依赖侧归入低层。

### L0 地基层

| 模块/项 | 理由（依据） |
| --- | --- |
| 工程基座 | Maven 多模块 + `fuyun-common` + CI + Flyway + RabbitMQ + PostgreSQL/TimescaleDB/Redis + 前端 monorepo（总 Spec §1.2/§4.1/§9 P0） |
| M01 系统与权限 | 文档头"上游依赖：无（全系统根模块）"；被全部模块依赖认证/权限/字典/审计/通知（M01 Spec 文档头）。README §3 规定字典由 M01 统一维护分发、业务模块只存 code |

### L1 契约与治理层（仅依赖 L0）

| 模块 | 归层理由（依据） |
| --- | --- |
| M20 集成平台（治理切片） | 被依赖声明"全部模块：事件总线治理、幂等构件、死信处理、出站留痕"（M20 Spec 文档头）；README §3 全部事件/交换机/幂等约定以 M20 为出处。其 M01 之外的依赖（M02 患者匹配、M14 遥测移交）仅属模式 D/FHIR 业务切片，分别后置到 P1/P6（M20 Spec §6/§8） |
| M02 患者 EMPI | 上游仅 M01/M20（文档头）；`patient_id` + visit_id 结构规范（§3.4）+ 解析/冻结/合并事件是 M03/M04/M05/M14/M16 等一切临床数据关联的前提（README §3"患者关联"条款） |
| M13 收费物价（计价切片） | 文档头对其余上游的关系均为"订阅其开单/执行事件 + 提供收退费 API"，实现前置仅 M01/M02；计价权威与价格版本化是门诊线（P1）与住院线（P2）放行链路的共同前提（M13 Spec §3.1/§3.4） |
| M14 物联网（P0 骨架切片） | 总 Spec §9 P0 含"IoT 通路技术验证"：FU-M14-01/03/04/05（IoTDA 对接底座、设备注册绑定、状态、遥测管道，均 P0，M14 Spec §6）；P0 验证用模拟设备，M02 依赖以模拟患者绕过。**模块整体跨层：骨架在 P0，完整化归 L2/P2** |

**层内并行组**：M20 ∥ M02 ∥ M13 ∥ M14 骨架，四者互不阻塞（M20 提供治理构件的最小集须最先合入，供其余三者登记事件）。

### L2 业务主层（P1 门诊线 + P2 住院线）

| 子层 | 模块 | 归层理由（依据） |
| --- | --- | --- |
| L2a 门诊线（P1） | M03、M06 基础（药品字典 FU-M06-01 + 门诊发药 FU-M06-04，总 Spec §6 M06 注）、M05 基础（护士站/PDA 基础能力，总 Spec §9 P1） | 三者构成"挂号→就诊→收费→发药"主线（总 Spec §9 P1 验证物）；前置 = CF-3（visit_id）、CF-4（M13 计费放行）、CF-5（M03↔M06 处方边界）。M05 基础不含住院医嘱执行闭环——该闭环依赖 M04 事件（M05 Spec §8），M04 在 P2 |
| L2b 住院与 IoT 线（P2） | M04、M05 完整（住院执行闭环）、M14 完整（告警/联动/命令/大屏/时序查询）、M16 核心（输液监控/体征采集/冷链/呼叫，FU-M16-01/02/03/07 均 P1 优先级、随 P2 交付，总 Spec §3/§9）、M06 住院摆药衔接 | 前置 = CF-6（M04 医嘱状态机与分发契约）、CF-7（M14 遥测/告警/绑定契约）、M13 住院计价停费契约（M13 Spec §8 M04 条目）。M04 是住院线编排核心：医嘱子键路由是 M06/M07/M08/M10/M12 的分发依据（M04 Spec §3.4） |

**层内并行组**：
- L2a：M03 ∥ M06 基础（CF-5 三方契约冻结后可并行，双方 Spec 已互为镜像声明，M03 Spec §8/M06 Spec §8）；M05 基础 ∥ 上述两者（其 P1 范围仅依赖 M01/M02/M06 药品查询）。
- L2b：M04 ∥ M14 完整 ∥ M16 核心（CF-6/CF-7 冻结后三线并行；M16 对 M14 为纯订阅+配置向导关系，M16 Spec §3.1/§8）；M05 完整与 M04 需紧密联调（执行回签双路对账，M05 Spec §3.1/§8）。

### L3 医技层（P3）

| 模块 | 归层理由（依据） |
| --- | --- |
| M07 检验 LIS | 依赖 M03/M04 放行与分发事件（P1/P2 已就绪）、M13 计费契约（CF-9）、M20 仪器通道；提供危急值引擎给 M08（M07 Spec §3.3/§8，总 Spec FU-M08-06） |
| M08 检查影像 | 主干（申请/影像/报告）与 M07 并行开发；危急值闭环接入依赖 CF-8（M07 引擎契约）；DICOM 组件内建不经 M20（M08 Spec §3.1/§8） |
| M06 完整（审方/毒麻/库存/抗菌药） | 与 M07/M08 无相互依赖，独立并行；毒麻借用 API 契约（CF-13）供 P4 的 M10 调用（M06 Spec §3.3/§8） |

**层内并行组**：M07 主干 ∥ M08 主干 ∥ M06 完整；M08 危急值接入与 M07 引擎联调串行于 CF-8 冻结之后。

### L4 病历与专科层（P4）

| 模块 | 归层理由（依据） |
| --- | --- |
| M09 电子病历病案 | 上游取数类依赖（M05/M06/M07/M08/M12/M14）至此均已交付；文书编辑器反向被 M03/M04 嵌入（P1/P2 即有需求）——跨阶段张力见 §6-R4；首页契约 CF-10 供 M13 结算清单（M09 Spec §3.4/§8） |
| M10 手术麻醉 | 依赖 M04 分发（P2）、M06 毒麻（P3）、M09 文书（同期联调）、M14 遥测/波形（P2）；设备档案查询依赖 M15（P5）——跨阶段张力见 §6-R4（M10 Spec §8） |
| M11 重症 ICU | 依赖 M05 边界协同（P2）、M14 告警引擎复用（P2）、M07 评分取数（P3）、M10 PACU 事件（CF-11）（M11 Spec §8） |
| M12 输血管理 | 依赖 M04 分发（P2）、M05 组件复用（P1）、M07 链路（P3）、M09 同意书（同期）、M16 冷链告警（P2 核心已含 FU-M16-07）（M12 Spec §8） |

**层内并行组**：M09 ∥ M10 ∥ M11 ∥ M12 四线并行；联调耦合点为 M09↔M10（编辑器嵌入+手术事件时钟源）与 M10→M11（`surgery.pacu.discharged`）。

### L5 资产与智慧病房全量层（P5）

| 模块 | 归层理由（依据） |
| --- | --- |
| M15 设备资产 | 效益分析需 M14 利用率（P2）、M07/M08 工作量（P3）、M10 使用事实（P4）、M13 收入 API（P1）——全部前序就绪后收口（总 Spec §9 P5、FU-M15-05 依赖 M14，M15 Spec §3.4/§8） |
| M16 全量（床旁屏/门口屏/婴儿防盗/RTLS/环境/门禁/医废） | 全量条目多为 P2（总 Spec §3），随 M15 资产引用契约（CF-12）冻结后收口（M16 Spec §8） |
| M13 医保结算完整 | 基线接口完整版+对账，依赖 M09 首页契约（CF-10，P4）与 M20 出站通道（总 Spec §9 P5） |

**层内并行组**：M15 ∥ M16 全量（CF-12 冻结后）；M13 医保切片独立并行。

### L6 增值层（P6）

| 模块 | 归层理由（依据） |
| --- | --- |
| M17 体检 | 依赖 M07/M08 适配位启用（其预留位兑现，M17 Spec §12-4）、M13 体检类目扩展（§12-2/3）、M14 遥测（P2） |
| M18 互联网医院 | 依赖 M03 号源/visit 签发（P1）、M06 开方/审方（P1/P3）、M09 编辑器（P4）、M13 收银台/线上医保（P1/P5）（M18 Spec §8） |
| M19 运营决策 | 依赖 M03/M04/M05/M07/M09/M13/M15 统计 API 与 `push_task` 骨架——全部前序就绪后收口（M19 Spec §8） |
| M20 FHIR/区域上报切片 | FU-M20-03/05（总 Spec §9 P6） |

**层内并行组**：M17 ∥ M18 ∥ M19 ∥ M20 增值切片，互不依赖、仅共享上游。

---

## 4. 推荐实现顺序（与总 Spec §9 路线图对齐）

### P0 骨架

- **模块组合**：工程基座（Maven 多模块 + CI + Flyway + 前端 monorepo）→ M01 基础 → M20 最小治理构件（信封/`event_registry`/`received_event`，完整治理 P1 续建）→ **M14 骨架（IoT 通路技术验证）**：IoTDA 测试产品 → AMQP 消费（FU-M14-01）→ 设备注册绑定与状态（FU-M14-03/04）→ 遥测管道落 TimescaleDB（FU-M14-05）→ WebSocket 展示（总 Spec §9 P0；四条目优先级 P0，M14 Spec §6）。
- **进入条件**：无（项目起点）。M14 骨架仅依赖 M01 与基础设施，患者关联以模拟数据绕过（M02 未交付）。
- **随 P0 冻结的契约**：CF-1（事件信封与交换机/队列约定）、CF-2（M01 主数据广播事件名）、CF-7 的消息模型部分（标准遥测消息模型四路同构）。
- **交付验证物**（总 Spec §9）：模拟设备端到端数据链路跑通（IoTDA→AMQP→TimescaleDB→WebSocket）；CI 全绿。
- **编排要点**：M14 消费高可用与积压监控（24h/1GB 上限对策）与告警双通道兜底必须随骨架交付，不得推迟到 P2（总 Spec §5.3-3、决策 D2）。

### P1 基础业务（门诊线）

- **模块组合**：M02、M03（门诊主流程）、M05 基础（护士站/PDA）、M13（收费）、M06 基础（药品字典+门诊发药）、M20 基础（事件总线治理完整化）（总 Spec §9 P1）。
- **进入条件（前置契约冻结）**：CF-3（M02 visit_id 结构与解析服务）、CF-4（M13 计费放行契约与同步 API）、CF-5（M03/M06/M13 处方计费行归属与退药退费时序三方契约）、CF-2（字典/执业授权广播）。M05 基础另需 CF-6 的**提前冻结**（其全量依赖 M04 事件，而 M04 在 P2，见 §6-R4）。
- **交付验证物**（总 Spec §9）：门诊挂号→就诊→收费→发药全流程演示。
- **编排要点**：M03 与 M06 按 CF-5 冻结后并行，发药回执/放行事件先行以 stub 联调；M05 P1 范围限定护士站/PDA 基础，不启住院执行闭环。

### P2 住院与 IoT 完整化

- **模块组合**：M04（住院/医嘱闭环）、M14 完整（告警/联动/命令/大屏/时序查询）、M16 核心（输液监控、体征采集、冷链、呼叫）、M05 完整（住院执行闭环）、M06 住院摆药衔接（总 Spec §9 P2）。
- **进入条件**：CF-6（M04 医嘱状态机+类型子键分发+执行回签 API，先于 M05 执行域、M06 摆药、M13 住院计价停费联调）；CF-7（M14 遥测模型/绑定五元组/`iot.alarm.*`/`/ws/iot` 主题，先于 M05/M16 消费端）；M13 住院侧事件契约（`inpatient.order.audited/executed/stopped/transferred/discharge-requested` → 计价/停费/归属切分，M13 Spec §8 M04 条目）。
- **交付验证物**（总 Spec §9）：医嘱五环节闭环（收药-摆药-核对-执行-拔针）+ 真实/演示设备输液告警联动演示。
- **编排要点**：M04 ∥ M14 ∥ M16 三线并行，M05 与 M04 双周联调（执行回签双路对账+转科执行单重定向，M05 Spec §8 M04 条目）。

### P3 药事与医技

- **模块组合**：M06 完整（审方/毒麻）、M07（LIS + 仪器双向通讯）、M08（RIS/PACS 主干）（总 Spec §9 P3）。
- **进入条件**：CF-8（M07 危急值引擎契约，先于 M08 接入）；CF-9（医技计费触发双时点契约：`lab.specimen.received`/`lab.result.uploaded`、`imaging.exam.registered`/`imaging.exam.completed` + 执行占用不重复计费，M07/M08 Spec §3.5/§8）；M20 仪器 ASTM/HL7 通道契约（进程内移交模式，M20 Spec §3.4）；`inpatient.order.audited` 类型子键路由表（M04 Spec §3.4）。
- **交付验证物**（总 Spec §9）：检验仪器双向通讯联调；危急值闭环演示。
- **编排要点**：M07 主干 ∥ M08 主干 ∥ M06 完整；毒麻 API（CF-13）在本阶段末冻结，为 P4 的 M10 铺路。

### P4 病历与专科

- **模块组合**：M09（EMR/质控/病案首页）、M10（手麻）、M11（ICU）、M12（输血）（总 Spec §9 P4）。
- **进入条件**：CF-10（M09 首页契约：`emr.homepage.submitted`、`GET /homepages/by-visit/{visitId}`、`POST /homepages/{no}/return`——先于 M13 结算清单联通）；CF-11（M10 手术事件契约：`surgery.surgery.started/finished`、`surgery.charge-line.registered`、`surgery.pacu.discharged`、`surgery.anesthesia-record.finalized`——先于 M09 时钟源/首页手术行、M13 术中计费、M05 体温单、M11 转入衔接）；CF-12 输血计费契约（`transfusion.specimen.received`/`transfusion.blood.issued` + `GET /irreversible-check`，先于 M13 血费联调）；M05 出入量落点契约（M11 回写 io_summary 的 API，M11 Spec §8）。
- **交付验证物**（总 Spec §9）：电子病历分级评价 4 级功能自评；麻醉记录单自动采集演示。
- **编排要点**：M09↔M10 编辑器嵌入与手术事件为同期双向联调（M09 Spec §8 M10 条目 / M10 Spec §8 M09 条目）；M11/M12 相对独立可后置半程。

### P5 资产与智慧病房全量

- **模块组合**：M15、M16 全量、M13 医保结算完整（总 Spec §9 P5）。
- **进入条件**：CF-14（M15↔M14 资产互引契约：`iot_device.asset_ref` 列增补[M-20 已裁决采纳]、`asset.asset.created/changed/scrapped`、`GET /quality/device-usage`、`GET /usage-eligibility`，M15 Spec §8/§12-3）；M16 冷链/定位资产号引用字段对齐（M15 Spec 定稿后兑现，M16 Spec §8 M15 条目）；M13 医保出站通道（M20 `interface_channel`/留痕监控，M13 Spec §8）。
- **交付验证物**（总 Spec §9）：设备效益分析（IoT 数据）演示。

### P6 增值

- **模块组合**：M17、M18、M19、M20 FHIR/区域上报（总 Spec §9 P6）。
- **进入条件**：M07/M08 预留适配位启用确认（`peis.checkin.registered` 子键分发，M17 Spec §12-4）；M13 体检类目扩展裁决落地（M17 Spec §12-2/3）；M03 `visit_type` 补"互联网诊疗"取值对照（M18 Spec §8 M03 条目）；M19 统计 API 接口位（M03/M04）与 `push_task` 骨架就绪（M19 Spec §8）。
- **交付验证物**（总 Spec §9）：对外接口联调。

---

## 5. 关键契约冻结点清单

"冻结"= 该契约的事件名、载荷语义、API 签名在 `event_registry` 登记并被消费方 Spec 对齐确认后，进入受控变更（变更须双向评审）。

| 编号 | 冻结点 | 契约内容 | 定义方 | 等待方（下游） | 最晚冻结时点 | 依据 |
| --- | --- | --- | --- | --- | --- | --- |
| CF-1 | 事件总线治理约定 | 信封 `eventId/occurredAt/producer`、`fy.topic`/`fy.dlx`/`fy.delay`、队列命名 `q.<消费者>.<事件>`、`event_registry` 登记、`received_event` 幂等 | M20 | 全部模块 | P0 事件总线打通前 | README §3；M20 Spec 文档头/§7 |
| CF-2 | M01 主数据广播 | `system.dict.published`/`system.org.changed`/`system.user.changed`/`system.param.changed`/`system.practice.changed`；`POST /api/v1/system/practice/check` | M01 | 全部业务模块 | P0 末 | M01 Spec §7；M03/M04 Spec §8 订阅声明 |
| CF-3 | visit_id 结构规范与 EMPI 契约 | `O\|I+yyyyMMdd+5 位流水` 14 位定长、签发主体唯一（M03 签 O/M04 签 I）、`patient.created/merged/split/frozen/identifier.changed/health-summary.updated`、"在途就诊查询"SPI 契约 | M02 | M03/M04/M05/M14/M16/M17 及全部临床模块 | P1 开工前 | M02 Spec §3.4/§8；README §3 |
| CF-4 | 计费放行契约（事件开单+同步收付+事件放行） | `billing.fee.created/confirmed`、`billing.settlement.completed`、`billing.refund.approved`、`billing.deposit.changed`、`billing.charge-item-price.published`、`billing.charge.guaranteed`（挂账放行回执，M-5 新增）、`billing.arrears.approved`（挂账审批通过，M-10 新增）；预结算/结算/退费/押金/一日清单 API；退费执行占用硬前置 | M13 | M03/M04/M06/M07/M08/M10/M12/M17/M18 | 门诊线 P1 前；住院停费/转科归属切分 P2 前 | M03 Spec §3.4；M13 Spec §3.1/§3.4/§7/§8；90 号文档 M-5/M-10 |
| CF-5 | 处方边界与计费行归属（三方契约） | 非药品计费行由 `outpatient.order.created` 携带、药品计费行由 `pharmacy.prescription.created` 携带（M-4 裁决落定，M03 澄清 3 备选表述已删除，§7-A3-2 闭环）；开方同步 API；`pharmacy.prescription.rejected`（审方驳回提醒）与 `pharmacy.dispense.returned`（退药受理）订阅对齐（M-6）；`outpatient.order.charged` 放行；"退药受理→退费审批→终态收敛"单向时序 | M03+M06+M13 | M05/M07/M08（放行扇出间接依赖） | P1 开工前 | M03 Spec §3.4/§8/§12-3；M06 Spec §3.5/§8；90 号文档 M-4/M-6 |
| CF-6 | 医嘱状态机与分发契约 | `inpatient.order.audited`（类型子键 lab/exam/surgery/blood/drug 路由）、`order.transferred`、`order-plan.generated`、`order.stopped/cancelled/revoked/executed`；`POST /order-plans/{no}/execute-confirm`；住院医嘱审方结论回流 `pharmacy.medication-order.audit-completed/rejected`（B-6 新增，载荷 target=m04_order_no）；执行回执双路对账 | M04 | M05（执行域）、M06（摆药/审方回流）、M07/M08/M10/M12（受理）、M13（计价/停费） | **P1 期间提前冻结**（M05 P1 基础先行、全量在 P2） | M04 Spec §3.3/§3.4/§7/§8；M05 Spec §8；90 号文档 B-6 |
| CF-7 | 遥测与告警契约 | 标准遥测消息模型（四路同构）、绑定五元组、`iot.alarm.triggered/escalated/closed`、`iot.device.status-changed`、`iot.binding.changed`、`iot.telemetry.anomaly`、`iot.call.triggered`（呼叫信令上行，M-19 新增）、`iot.access.passed`（门禁通行结果，M-19 新增）、`/ws/iot` 三主题 | M14 | M05（体征双通道/输液/大屏）、M16（全部感知场景）、M10/M11（记录采集）、M15（利用率）、M20（模式 D 同构转换） | 消息模型 P0 定型；告警/绑定/主题 P2 完整化前复审冻结 | M14 Spec §3.1/§7；M05/M16 Spec §8；90 号文档 M-19 |
| CF-8 | 危急值引擎契约 | `POST /api/v1/lab/critical-values`（source_domain=EXAM 域登记）、`lab.critical-value.notified/accepted/closed`、界限值字典按域分离 | M07 | M08（共用引擎）、M05（大屏/PDA 展示）、M12（异常登记）、M19（指标） | P3 前 | M07 Spec §3.3/§8；M08 Spec §8 |
| CF-9 | 医技计费触发双时点契约 | `lab.specimen.received`（签收核收计费）/`lab.result.uploaded`（上机占用）；`imaging.exam.registered`（登记计费）/`imaging.exam.completed`（执行占用，不重复生成费用行）；`GET /execution-status` 退费硬拦截 | M07/M08 ↔ M13 | M13（计价引擎消费侧） | P3 前 | M07 Spec §3.5/§8；M08 Spec §3.6/§8 |
| CF-10 | 病案首页↔结算清单契约 | `emr.homepage.submitted`（上传任务触发）、`GET /homepages/by-visit/{visitId}`（诊疗段快照）、`POST /homepages/{no}/return`（质控退回） | M09 ↔ M13 | M13（结算清单上传） | P4 前 | M09 Spec §3.4/§8；M13 Spec §8 M09 条目 |
| CF-11 | 手术事件契约 | `surgery.surgery.started`（携术式计费行）、`surgery.surgery.finished`（首页手术行权威源+耗材计费截止）、`surgery.charge-line.registered`（携 UDI）、`surgery.pacu.discharged`（去向路由）、`GET /surgery-execution-status` | M10 | M09（时钟源/首页）、M13（术中计费）、M05（体温单/接收）、M11（转入衔接） | P4 前 | M10 Spec §7/§8 |
| CF-12 | 输血计费与出入量契约 | `transfusion.specimen.received`（检测费）、`transfusion.blood.issued`（血费）、`GET /irreversible-check`、`transfusion.infusion.started/completed`（M05 出入量行） | M12 | M13、M05、M04 | P4 前 | M12 Spec §7/§8 |
| CF-13 | 毒麻借用闭环契约 | `POST /narcotic-cabinets/{id}/withdrawals`（双人会签）、`POST /ampoule-returns`（勾稽核销）、`pharmacy.narcotic.alerted`（待清阻断） | M06 | M10 | P3 末（M10 P4 开工前） | M06 Spec §3.3/§8；M10 Spec §8 |
| CF-14 | 资产↔设备互引契约 | `iot_device.asset_ref` 引用列（M-20 已裁决采纳：展示级冗余，权威在 asset.iot_device_ref）、`asset.asset.created/changed/scrapped`（M14 订阅 scrapped 必选、置 DISABLED）、`GET /quality/device-usage`、`GET /usage-eligibility`（M-20 收窄：命令下发前同步调用，绑定走 overdue 事件+本地标识）、`asset.metrology.overdue` | M15 ↔ M14 | M14、M16、M07/M08（合规标识）、M19 | P5 前 | M15 Spec §8/§12-3；90 号文档 M-20 |

---

## 6. 风险提示

1. **M13 是被依赖面最大的单点契约源**：M03/M04/M06/M07/M08/M10/M12/M15/M17/M18/M19 均消费其 API 或事件（各 Spec 文档头），其契约稳定性决定全系统收费链路。缓解：价格版本化+计费时点快照（M13 Spec §3.4）隔离调价影响；枚举扩展走统一审查偏差裁决先例（M17 Spec §12-2/3/4）；禁止下游复制价格/金额（各执行域模块"零金额字段"声明，M07/M08/M12 Spec §8）。
2. **M20 治理构件是全体前置但完整交付在 P1**：P0 就要打通事件总线，若治理约定缺位，各模块将自建交换机/幂等，违反 README §3 与 M14 边界"禁止私建交换机"（M14 Spec §2）。对策：P0 交付"约定+最小构件"（CF-1），P1 完整化死信界面/失败重推/主数据分发治理。
3. **双向契约耦合组（非编译期环，但契约必须成对冻结、联调需双端 stub）**：M03↔M06、M03↔M13、M04↔M13、M04↔M05、M05↔M14、M05↔M16、M14↔M20、M14↔M15、M15↔M16、M15↔M10、M15↔M07/M08。各组均已按"API 单向调用 + 事件单向回流"在双方 Spec 对称声明（如 M03↔M06 见 M03 Spec §8/M06 Spec §8；M04↔M05 见 M04 Spec §8/M05 Spec §8）。统一审查应将其登记为"成对冻结清单"，避免任一侧单方面改契约。**v1.1 注**：M14↔M20 的 Maven 循环依赖已经 M-3 裁决解除（幂等构件接口下沉 `fuyun-common`、模式 D 移交改 SPI，20-integration v1.1 已落地），本组不再含编译期环。
4. **跨阶段前向依赖（阶段顺序与依赖方向存在张力）**（v1.1 注：逐项标注处置状态——有裁决的标注裁决结论，无裁决的保留并注明"待实施前冻结最小契约"）：
   - M05（P1）依赖 M04（P2）医嘱事件 → CF-6 必须提前至 P1 冻结，且 M05 P1 交付范围明确排除住院执行闭环（M05 Spec 文档头上游声明 vs 总 Spec §9 P1/P2 划分）——**维持既有处置**（CF-6 提前冻结已是本文档 §4/§5 编排定案，本轮无新增裁决）；
   - M03（P1）病历书写依赖 M09（P4）编辑器（M03/M09 Spec §8）→ P1 的全流程演示不含病历（总 Spec §9），但门诊医生站上线需病历能力，建议统一审查裁决过渡方案（M09 编辑器骨架前置或 P1 临时文书能力）——**本轮无对应裁决，待实施前冻结最小契约**（P1 开工前冻结 M09 编辑器组件最小契约或落定临时文书能力过渡方案）；
   - M10（P4）设备档案查询依赖 M15（P5）（M10 Spec §8 M15 条目）→ 建议资产档案最小查询接口位随 M10 前置，或 M15 台账切片提前——**本轮无对应裁决，待实施前冻结最小契约**（P4 开工前冻结 M15 资产档案最小查询接口位）；
   - M16 核心（P2）冷链/定位引用 M15 资产号，而 M15 在 P5，双方已声明"M15 Spec 定稿后对齐"（M16 Spec §8）→ 资产号引用字段契约须在 P2 前先行对齐，否则冷链台账返工——**CF-14 契约内容已随 M-20 裁决扩充（v1.1），资产号引用字段的最小契约仍须在 P2 前冻结对齐**。
5. **M14 IoT 通路的可用性风险前置于 P0**：IoTDA AMQP 仅缓存 24h/1GB，公网转发 1 MB/s 限制（总 Spec §5.3）→ 消费高可用、积压监控、告警 HTTP 双通道兜底必须随 P0 骨架交付而非 P2 补课；波形默认边缘降采样（决策 D3）须在 P0 验证链路中固化，防止 P2 完整化时数据量失控。
6. **事件名"以对方 Spec 登记为准"的悬置表述**：M05↔M14 输液关联、M12→M05 出入量回写、M09←M08/M10/M12 报告与手术事件等多处存在该表述（各 Spec §8）→ 统一审查须逐条落实 `event_registry` 登记后消除，否则联调阶段会出现事件名漂移。**v1.1 注**：Round 1 审查已逐条裁决对齐（M-18 输液事件订阅、M-26 术中输血改 API 关联、B-6 审方结论事件名对齐等），各 Spec v1.1 修订后订阅方事件名已与发布方登记一致，剩余一律以 event_registry 登记为准。
7. **M19 与 M14/M15 的大屏/数据口径边界**：设备"运行视图"在 M14、"运营视图"在 M19 经 M15 取数，前端双端点订阅（M19 Spec §8 M14 条目）→ 若口径混淆会导致 M14/M15/M19 三方重复建设，冻结期应把该边界写入视图验收标准。

---

## 7. 依赖异常清单（提交统一审查核实）

以下为本次通读发现的声明缺口与待裁决项。均为**声明层面异常**，未发现真正的编译期依赖环（合法反向形态共两处：M02"在途就诊查询"SPI 与 M01"组织停用前置校验"SPI[B-1 裁决，M04 注册床位占用查询实现]，依赖倒置均由定义方 Spec 声明为合法例外，M03/M04 已注册实现）。v1.1 注：已依据 90 号文档 Round 1 裁决逐项标注处理状态——已裁决修复的标注"v1.1 已闭环"（部分闭环/待补登记的逐项注明），无对应裁决的保留并注明待实施前冻结。

### 7.1 缺失声明（依赖方已声明依赖，被依赖方文档头未登记）

| 编号 | 异常 | 依据 | 处理状态（v1.1） |
| --- | --- | --- | --- |
| A1-1 | M14 文档头"下游被依赖"未列 M09（患者全景遥测查询 `GET /telemetry/query`）与 M17（体检遥测取数） | M09 Spec §8 M14 条目；M17 Spec §8 M14 条目；M14 Spec 文档头 | 待补登记（本轮无对应裁决；M14 Spec 修订时一并补 M09/M17，实施前冻结最小契约） |
| A1-2 | M07 文档头"下游被依赖"未列 M11（评分取数/危急值事件订阅）与 M18（报告查询，P2 预留；M08 已列 M18 而 M07 未列，两医技不对称） | M11 Spec §8 M07 条目；M18 Spec §8 M07/M08 条目；M07 Spec 文档头 | 部分闭环：M11 侧 v1.1 已闭环（R4-15，07-lab v1.1 下游被依赖已补 M11/M12）；M18 报告查询声明待补登记（本轮无对应裁决） |
| A1-3 | M13 文档头"下游被依赖"未列 M15（收入统计只读 API 日终拉取）与 M17（体检结算，M17 偏差 2/3/4 已申报扩展） | M15 Spec §8/§12-3③；M17 Spec §12-2/3/4；M13 Spec 文档头 | v1.1 已闭环：M17 侧已闭环（M-27，13-billing v1.1 上游依赖已列 M17、订阅 `peis.checkin.registered/cancelled` 并登记体检预留枚举）；M15 被依赖声明已经终审 #3 采纳（"文档头补被依赖 M15"），13-billing v1.1 文档头已列 M15，已闭环 |
| A1-4 | M16 文档头"下游被依赖"未列 M12（储血冰箱冷链告警订阅） | M12 Spec 文档头/§8 M16 条目；M16 Spec 文档头 | v1.1 已闭环（M-24：M12 改订 `iot.alarm.triggered`（M14 冷链规则命中），M16 文档头被依赖清单相应修正；M15 对 `alert-archived` 的订阅不变） |
| A1-5 | M05 未登记对 `transfusion.infusion.started/completed` 的订阅（M12 声明该事件"供 M05 生成输血入量行"，并自注"订阅登记统一审查对照补充"） | M12 Spec §8 M05 条目⑤；M05 Spec §7 订阅清单 | v1.1 已闭环（M-18，05-nursing v1.1 §7 已补订阅两事件并增补 io_record.source 枚举） |
| A1-6 | M04 文档头"下游被依赖"未列 M16，而 M16 文档头声称"M04 Spec 已声明"——M04 正文仅 FU-M04-08 行文提及"患者端经 M16/M01 通道取数"，文档头与正文、两 Spec 之间三处不一致 | M04 Spec 文档头/FU-M04-08；M16 Spec 文档头 | v1.1 已闭环（M04/M16 文档头一致性随 M-19/M-24 相关侧修订统一） |

### 7.2 事件/模型未登记（依赖方订阅或引用的契约在定义方 Spec 中不存在）

| 编号 | 异常 | 依据 | 处理状态（v1.1） |
| --- | --- | --- | --- |
| A2-1 | M16 订阅 `iot.device.event`（呼叫主机/门禁/出口监视器/医废终端事件上行），M14 发布事件清单无此事件——M16 偏差 1 已提请在 M14 `event_registry` 补登记 | M16 Spec §7/§12-1；M14 Spec §7 | v1.1 已闭环（M-19：否决泛化事件，M14 新增登记 `iot.call.triggered`/`iot.access.passed`，M16 订阅两者；婴儿防盗走 `iot.alarm.triggered` 透传、医废称重走遥测+扫码建档） |
| A2-2 | M15 请求 M14 领域模型 `iot_device` 增补 `asset_ref` 展示级冗余引用列——M14 Spec 领域模型无此列，属"改上游已定稿 Spec"的增补请求 | M15 Spec §8"与 M14 的关系"/§12-3①；M14 Spec §4 | v1.1 已闭环（M-20：采纳双实体互引，M14 增补 `asset_ref` 与 `asset.asset.scrapped/created/changed`、`asset.metrology.overdue` 订阅） |
| A2-3 | M15 请求 M13 收入统计 API 的被依赖方由"M03/M04/M19"扩展至 M15；M15 请求 M14/M07/M08 可选调用 `GET /usage-eligibility` 可用性校验——均待裁决 | M15 Spec §8/§12-3②③ | 部分闭环：usage-eligibility 已裁决采纳收窄版（M-20/终审 #5：命令下发前同步调用，绑定走 overdue 事件+本地标识）；M13 被依赖方扩展至 M15 已经终审 #3 采纳，13-billing v1.1 文档头已列 M15，已闭环 |

### 7.3 契约口径待对照（非缺失，需统一审查确认一致性）

| 编号 | 事项 | 依据 | 处理状态（v1.1） |
| --- | --- | --- | --- |
| A3-1 | M18 要求 M03 `visit_type` 枚举补"互联网诊疗"取值（信息页代码 4），M03 Spec 未显式列出该枚举项 | M18 Spec §8 M03 条目"统一审查对照点" | v1.1 已闭环（R2-08，M03 v1.1 §4 visit_type 已补"互联网诊疗"代码 4；号别表达按 R5-16 落定为"复诊号别+线上渠道配额"，M18 §12 偏差 1 记录） |
| A3-2 | 处方计费事件来源存在备选裁决项：M03 澄清 3 声明药品计费行由 M06 处方事件携带；若裁决改由 M03 承载，事件名不变但载荷归属变化——CF-5 冻结时须一并裁决 | M03 Spec §12-3；M06 Spec §8 M13 条目 | v1.1 已闭环（M-4：药品计费行归属统一由 `pharmacy.prescription.created` 携带（处方生效时点），备选表述删除，CF-5 契约内容已按裁决更新） |
| A3-3 | M17 体检不签发 visit_id（以 `checkin_no` 为锚点）与 README §3"一切临床数据引用 patient_id + visit_id"的表述存在解释空间，M17 偏差 1 已提请裁决 | M17 Spec §12-1；README §3 | 已裁决采纳（终审 #10：体检以 `peis_checkin_no` 为锚点、不进 visit 域；README §3 表述按此裁决解释） |
| A3-4 | M20 模式 D"进程内移交 iot 管道"与 M14"两模块 Spec 3.4 方案互为镜像"已双方声明一致，但移交接口签名未在任一方 §7 显式登记 | M20 Spec §3.4/§8；M14 Spec §8 | v1.1 已闭环（M-3：模式 D 移交改为 SPI——接口在 `fuyun-common` 定义、M14 注册实现、M20 调用，20-integration v1.1 已落地） |

---

## 8. 自审记录

- [x] 覆盖全部 20 份模块 Spec，依赖关系均标注 Spec 出处，无凭空推断
- [x] 分层与顺序与总 Spec §9 P0~P6 路线图逐阶段对齐，P0 含 M14 骨架（IoT 通路技术验证）
- [x] 事件名/API 与各 Spec §7 登记一致；未新增任何事件、API 或功能需求
- [x] 依赖异常单列成节（§7），区分缺失声明/未登记契约/口径待对照三类，供统一审查核实
- [x] 中文、UTF-8 无 BOM、LF 行尾

---

## 9. v1.1 修订记录（Round 1，依据 90-cross-review.md 裁决）

本文档为编排说明，本轮修订不新增/修改任何功能需求，仅按统一审查裁决更新状态与契约清单：

1. **§7 依赖异常清单逐项标注处理状态（v1.1）**：三表均增列"处理状态（v1.1）"——已裁决修复项标注"v1.1 已闭环"（A1-4/M-24、A1-5/M-18、A1-6/M-19·M-24 侧修订、A2-1/M-19、A2-2/M-20、A2-3 usage-eligibility 部分/M-20·终审 #5、A3-1/R2-08、A3-2/M-4、A3-3/终审 #10、A3-4/M-3）；M13 侧登记项含 M12 三事件（M-17）、`emr.homepage.submitted`（M-13）、`imaging.exam.completed`（R4-09）、`peis.checkin.registered/cancelled` 与体检枚举（M-27）均已随 13-billing v1.1 落地（体现在 A1-3/A2-3 的 M17 部分闭环标注）；部分闭环/待补登记项（A1-1、A1-2 M18 部分）逐项注明原因；A1-3/A2-3 的 M15 登记部分已闭环（13-billing v1.1 文档头已列 M15）；无裁决项注明"待实施前冻结最小契约"。
2. **§5 契约冻结点清单扩充**：CF-4 补 `billing.charge.guaranteed`（M-5）、`billing.arrears.approved`（M-10）；CF-5 补 M-4 计费行归属裁决注记与 M-6 订阅对齐（`pharmacy.prescription.rejected`/`pharmacy.dispense.returned`）；CF-6 补 `pharmacy.medication-order.audit-completed/rejected`（B-6 住院医嘱审方结论回流）；CF-7 补 `iot.call.triggered`/`iot.access.passed`（M-19 具体事件替代泛化 `iot.device.event`）；CF-14"待裁决"标注更新为 M-20 裁决结论（§4 P5 进入条件的失实括号同步修正）。
3. **§6 处置记录标注**：前向依赖四子项逐项标注处置状态（M05→M04 维持 CF-6 提前冻结既有处置；M03→M09 编辑器、M10→M15 设备档案查询无裁决，注明待实施前冻结最小契约；M16→M15 资产号引用注明 CF-14 已按 M-20 扩充、字段对齐仍须 P2 前冻结）；§6-3 补注 M14↔M20 Maven 循环依赖已经 M-3 解除；§6-6 补注事件名悬置表述已经 Round 1 逐条裁决对齐。
4. **§2 依赖矩阵 M19 行同步 19-ops v1.1 文档头（收尾补登）**：补 M08（影像报告/检查工作量统计 API）、M10（手术工作量统计 API）统计取数依赖；补 M16/M17/M18 stats API（P6 启用接口位：M16 呼叫响应时长与医废重量、M17 体检人次与随访率、M18 互联网诊疗量与配送时效）——依据 90 号文档 M-21 裁决，与 19-ops.md v1.1 文档头及偏差 5 对齐。
