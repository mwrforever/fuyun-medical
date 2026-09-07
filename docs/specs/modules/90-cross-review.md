# 模块 Spec 统一交叉审查报告（Round 1）

| 属性 | 内容 |
| --- | --- |
| 审查对象 | docs/specs/modules/ 全部 20 份模块 Spec + README + 00-implementation-order + 00-master-spec |
| 审查方式 | 5 个分组审查（基础契约/门诊药事/住院临床/医技/IoT 增值）+ 1 个全局事件契约矩阵核对 + 依赖顺序核对 |
| 问题统计 | BLOCKER 6 / MAJOR 28 / MINOR 65（去重后） |
| 裁决状态 | 全部问题已裁决（见各条"裁决"列）；修复由 fix subagent 按 90 号文档执行 |
| 闭环状态 | Round 1 + Round 2 已闭环（2026-09-07）：Round 1 裁决 99 项全部修复并验证（BLOCKER 6/6、MAJOR 28/28、MINOR 抽验 54/55）；Round 2 点状修复 13 处，复核 13/13 通过 |

---

## 1. BLOCKER（6 项，全部必须修复）

| ID | 位置 | 问题 | 裁决与修复指令 |
| --- | --- | --- | --- |
| B-1 | 01-system §6 FU-M01-01 | 组织停用校验"M04 回调"构成 M01 反向依赖，与红线/自审矛盾 | M01 定义"组织停用前置校验"SPI 扩展点（接口在 common 或 M01 声明），M04 注册"床位占用查询"实现；§8 集成点显式声明；修正自审记录 |
| B-2 | 13-billing §8 ↔ 02-patient 红线 2 | M13"迁移历史费用归属"违反 M02"历史行不改写、读侧归一"契约 | M13 改写为"订阅 patient.merged 刷新费用域归一映射/宽表，历史费用行不改写，统计读侧经 EMPI 解析收敛" |
| B-3 | 03-outpatient §3.4/FU-M03-08 ↔ 13/06 | 退药退费时序：M03 写"先退费后退药"，与 M13/M06"先退药后退费"硬前置矛盾（链路不可实现） | 统一为：患者到药房退药受理（M06 核验+批次回补，发 `pharmacy.dispense.returned`）→ M13 解除占用并退费审批 → `billing.refund.approved` → M03 扇出 `outpatient.order.cancelled` 仅作终态确认；修订 M03 §3.4 与 FU-M03-08；M06 §8 待决项落定 |
| B-4 | 11-icu §7 ↔ 07-lab §7 | M11 订阅 `lab.result.reported` 悬空（M07 无此事件），评分取数链断裂 | M11 改订 `lab.result.audited`（审核完成触发参数刷新） |
| B-5 | 14-iot §6 FU-M14-10 ↔ 05-nursing §3.4 | M14 预置联动模板"输液告急→创建护理任务"与 M05"告警升级挂执行单、不新建任务"契约冲突（双路径任务风暴） | M14 模板改为"输液告急→PDA 强提醒（经 M01 通知）+（病区配置）M16 分级播报/呼叫联动"；创建护理任务动作保留给离床确认/设备断流确认场景 |
| B-6 | 04-inpatient §7 ↔ 06-pharmacy §7 | M04 订阅 `pharmacy.audit.completed` 悬空——住院医嘱药师审核结论无回流事件，医嘱闭环主链断裂 | M06 新增住院医嘱审方结论事件 `pharmacy.medication-order.audit-completed` / `pharmacy.medication-order.audit-rejected`（载荷 target=m04_order_no），M04 订阅名对齐；纳入 CF-6 契约冻结 |

## 2. MAJOR（28 项）

| ID | 位置 | 问题 | 裁决与修复指令 |
| --- | --- | --- | --- |
| M-1 | 02-patient §4/§5 | merge_record 唯一约束使"拆分后再合并"永久阻断 | 唯一约束改为部分唯一（仅 PROCESSING/COMPLETED 生效）或加合并序号；测试补"拆分后再次合并"用例 |
| M-2 | 14-iot §7 ↔ 文档头/自审 | 订阅 M05 输液任务事件未申报反向依赖，自审"无反向依赖"失实 | 与 M-8/M-9/M-10 合并处理：M14 文档头上游依赖补 M05/M16（事件与联动动作级），§7 显式点名 `nursing.task.created`、`nursing.infusion.started`、`nursing.infusion.completed` 三事件，修正自审 |
| M-3 | 20-integration ↔ 14-iot | M20↔M14 互为上游（模式 D 移交 + 幂等构件），Maven 循环依赖 | 组合裁决：①幂等构件接口下沉 `fuyun-common`（接口在 common、实现由 M20 运行时装配、表仍在 integration schema）；②模式 D 移交改为 SPI（接口在 common，M14 注册实现，M20 调用）；两份 Spec 依赖声明同步改写并记偏差节 |
| M-4 | 03/06/13 处方链 | 药品计费时点三方冲突（审核时/处方生效时/发药时） | 统一裁决：门诊药费=`pharmacy.prescription.created` 生成 PENDING；住院药费=`inpatient.order.audited` 即时计价；发药/摆药事件仅为执行占用与确认标记。M13 §3.1 将"药品发药"移出后计费类、FU-M13-02 同步；M06 §3.5 转述对齐；M04 §3.3 住院口径保留 |
| M-5 | 03/06/13/18 | 急诊绿通发药放行链路缺事件（M13 无挂账放行事件、M03 放行触发未定义） | M13 新增发布 `billing.charge.guaranteed`（挂账放行回执）+ 订阅 `outpatient.green-channel.opened/closed` 维护挂账态；M03 订阅 `billing.charge.guaranteed` 后置 CHARGED 并扇出含绿通标记的 `outpatient.order.charged` |
| M-6 | 03-outpatient §7 | M03 缺订阅 `pharmacy.prescription.rejected`（医生站驳回提醒）与 `pharmacy.dispense.returned`（退药聚合） | M03 订阅清单补两事件（approved 按需补或声明为通知类）；修正 pharmacy 事件组中文注记；M06 自审表述更正 |
| M-7 | 06-pharmacy §5/§7 | 处方状态机缺"已缴费未发药→作废"迁移（PENDING_DISPENSE/DISPENSING 不可达 CANCELLED） | 补迁移 `PENDING_DISPENSE/DISPENSING → CANCELLED`（必填原因、联动 M13 退费、释放批次锁定）；补"已缴费未发药退费"测试用例 |
| M-8 | 06-pharmacy §3.1/§7/§8 | 出院带药 DISCHARGE 处方无法被 `outpatient.order.charged` 放行（住院 visit 无此事件） | M06 增订 `billing.settlement.completed`（结算类型=出院结算分支）驱动 DISCHARGE 处方转 PENDING_DISPENSE；§7"唯一权威通道"注记限定为"门诊/急诊处方" |
| M-9 | 06-pharmacy ↔ 18-internet | rx_type=INTERNET 兼容缺口：外配场景处方终态缺失、开方 API 调用方未含 M18 | M06 预留条款定义 `FLOWED_EXTERNAL` 终态（由 M18 流转回执事件驱动，事件名 P2 登记）+ "费用作废≠处方作废"口径；`POST /prescriptions` 调用方补"M18（P2）"；M18 澄清 2 措辞收敛 |
| M-10 | 04-inpatient §5/§8 ↔ 13-billing | 出院"挂账审批"在 M13 无实体/状态机/事件（BLOCKED→READY 悬空） | M13 新增挂账审批实体 arrears_approval + 状态机 + 发布 `billing.arrears.approved`；M04 BLOCKED→READY 改由该事件驱动；双向 §7/§8 对齐 |
| M-11 | 07-lab §5 vs §6 | 条码状态机缺 `COLLECTED/IN_TRANSIT → CANCELLED` 迁移，未签收退费闭环断裂 | 状态机补该迁移（未签收退费作废：标本处置留痕）；同步边界测试项 |
| M-12 | 08-imaging §5 vs §6 | 检查中作废迁移与"PERFORMING 起不可退"计费硬规则矛盾 | 明确医学终止费用语义：PERFORMING 中医学终止→费用照收（走协商/挂账），状态机迁移改名 MEDICAL_ABORT 区别于未开始 CANCELLED；`GET /execution-status` 对该态返回"已执行"；删除"M13 联动退费"歧义表述 |
| M-13 | 09-emr ↔ 13-billing | `emr.homepage.submitted` 单边悬空（M13 未订阅，清单上传无触发） | M13 §7 订阅清单补 `emr.homepage.submitted` |
| M-14 | 09-emr §7 ↔ 08-imaging | M09 订阅 lab.corrected 但缺 imaging.report.corrected（同族不对称） | M09 补登记 `imaging.report.corrected` |
| M-15 | 20-integration §4 | 通道协议枚举未含 ASTM/TCP；DICOM 例外未登记（M07 退化方案落空） | M20 §4 补登记：协议枚举扩展 `ASTM`（M07 仪器通道）；显式声明 DICOM 网络服务由 M08 内建不经通道、DICOMweb 外呼经 REST 通道治理 |
| M-16 | 13-billing §7 | 订阅清单缺 5 类上游指名事件（visit.registered×2、green-channel、outpatient.order.executed、internet.prescription.returned） | M13 §7 全部补登记 |
| M-17 | 13-billing §7/§8 | 缺 M12 输血计费事件订阅与退费前置校验调用方 | M13 订阅补 `transfusion.specimen.received`/`transfusion.blood.issued`/`transfusion.inventory.changed`(RETURN_BACK)；§8 补 M12 调 `GET /api/v1/transfusion/irreversible-check` |
| M-18 | 05-nursing §7/§4 | M05 缺订阅 `transfusion.infusion.started/completed`；io_record.source 缺输血/ICU 取值 | 补订阅两事件；source 枚举增 `TRANSFUSION_AUTO`、`ICU_AUTO`、`ICU_MANUAL`；明确输血自动行不受 ICU 病区手工出入量关闭开关影响 |
| M-19 | 16-ward §7/§12 ↔ 14-iot | M16 订阅 `iot.device.event` 悬空（泛化事件被否决） | 否决泛化事件；M14 新增登记 `iot.call.triggered`（呼叫信令上行）与 `iot.access.passed`（门禁通行结果），M16 订阅两者；婴儿防盗走 `iot.alarm.triggered` 透传、医废称重走遥测+扫码建档；M16 §3.1/偏差 1 按此改写（呼叫=事件订阅、门禁闭锁/播报=M14 联动动作） |
| M-20 | 15-asset ↔ 14-iot | 双实体互引落地条件未满足：M14 无 asset_ref 列、无 asset.* 订阅 | 采纳双实体互引：M14 领域模型增补 `asset_ref`（展示级冗余，权威在 asset.iot_device_ref）；M14 订阅补 `asset.asset.scrapped`（必选，置 DISABLED）、`asset.asset.created/changed`（引用维护）、`asset.metrology.overdue`（合规标识）；M14 偏差节申报；usage-eligibility 收窄为"命令下发前同步调用，绑定走 overdue 事件+本地标识" |
| M-21 | 19-ops 文档头/自审 | 依赖声明与正文不符（缺 M08/M10）；M16/M17/M18 下游声明未接 | M19 文档头/自审补 M08/M10；对 M16/M17/M18 采纳"在本期 data_sources 登记三者 stats API"（呼叫响应时长/体检人次与随访率/互联网诊疗量与配送时效） |
| M-22 | 14-iot §7 ↔ 16-ward 红线 3 | M14 将床旁屏列为 WebSocket 直连消费方，违反 M16 终端聚合红线 | M14 消费方清单改为"床旁屏（经 M16 聚合转发，不直订）"；M16 明确由其后端订阅 M14 主题经 `/ws/ward` 下发 |
| M-23 | 14-iot 文档头/FU-M14-10/自审 | 联动动作出向依赖（调 M05/M16 接口）未纳入依赖声明，自审失真 | 并入 M-2 处理：文档头补依赖、§8 补出向调用声明与失败重试语义（iot_linkage_log 承载）、修正自审 |
| M-24 | 12-transfusion §7 ↔ 16-ward §7 | M12 冷链订阅语义错配：`ward.cold-chain.alert-archived` 是处置归档事件，实时拦截无事件可订 | M12 改订 `iot.alarm.triggered`（M14 冷链规则命中，按设备用途过滤储血设备）；M16 文档头被依赖清单相应修正；M15 对 alert-archived 的订阅（可靠性记录用途）不变 |
| M-25 | 02-patient §7 ↔ 全局 | `patient.split` 无任何订阅方（拆分逆映射无人消费）；`patient.unfrozen` 零订阅（冻结解除无刷新） | 裁决"成对登记"：凡订阅 `patient.merged` 的模块（M13/M14/M05/M06/M07/M08/M09/M10/M11/M12/M16/M17/M18）成对登记 `patient.split`；凡订阅 `patient.frozen` 的模块（M03/M07/M08/M09/M12/M17/M18）成对登记 `patient.unfrozen`；M02 偏差节注明成对语义 |
| M-26 | 10-surgery §7 ↔ 12-transfusion §8 | 术中输血"M12 回执"无可指认事件（悬置表述） | 裁决：术中输血不走 infusion 事件链，M10 调 M12 发血单查询 API 关联；M10 §7 删除悬置表述改为 API 关联声明；M12 §8 相应补充 API 名称 |
| M-27 | 17-peis ↔ 13-billing | 体检计费契约单向申报：M13 无 peis.* 订阅、无体检枚举类目 | M13：订阅补 `peis.checkin.registered/cancelled`；settle_type/charge_source/visit_type 登记"体检"预留枚举（P6 启用）；体检执行域事件（lab.specimen.received/imaging.exam.registered）对体检来源"仅回写执行占用、不生成费用行"以 pricing_rule 规则配置表达（否决运行时豁免）；M17 偏差 3 措辞由"豁免分支"改为"计价规则配置" |
| M-28 | 07-lab ↔ 12-transfusion | M12 复用危急值引擎 source_domain=LAB 混域 | M07 critical-values 契约扩展 source_domain 枚举（LAB/EXAM/TRANSFUSION）；M12 使用 TRANSFUSION |

## 3. MINOR（65 项，按文件归组，fix 时一并处理）

- **01-system**：R1-06 API 路径前缀统一 `/api/v1`；R1-07 报表模板归属 M19 的显式划分；R1-08 FU 表补 P0/P1 优先级标注；R1-09 补 event_registry 登记义务声明 + 主数据全量初始化/版本对账接口；R1-19 同步（master 侧统一遥测常态值 50 msg/s）
- **README**：R1-18 字典条目改为"M01 经 fy.topic 广播、M20 分发治理"；R4-19 注明"文档头不计节号"；新增判定口径："同步 API 调用方=依赖方；事件订阅不构成依赖方向"（写进第 3 节）
- **00-master-spec**：R1-19 统一遥测常态值为 50 msg/s（5.4 与第 8 章一致）；5.3-3 措辞修订为"关键队列客户端确认+幂等"（采纳 M14 偏差 1）
- **02-patient**：R1-03（并入 M-1）
- **13-billing**：R1-10 参数读取方式声明+补订阅 `system.param.changed`；R1-11 补入院起费锚定口径（与 M04 对齐：床位费起费锚定入院事件或长期医嘱审核，二选一需与 FixD 协同——裁决：起费锚定 `inpatient.visit.admitted`，M13 补订阅）；R1-12 医保超时口径拆分两条声明并记偏差节；R4-09 订阅补 `imaging.exam.completed`
- **03-outpatient**：R2-08 visit_type 枚举补"互联网诊疗"（信息页代码 4）；R2-10 处方引用行作废必须经 M06 作废 API；R2-11/R4-12 诊毕校验纳入 M09 待写文书清单（可参数化为"提醒不拦截"）；R4-10 补订阅 `lab.specimen.collected`、`imaging.exam.registered`（驱动执行中）；R4-11 `outpatient.order.cancelled` 说明补 M07/M08 消费方
- **04-inpatient**：R3-06 护理类分发表述改为"经 transferred/执行计划链进入 M05"（删 audited.nursing 悬空分发）；R3-12 会诊 OVERDUE 改 overdue_flag 动作（状态停留 REQUESTED，对齐 M14/M05），同步修 M05 转述；R3-13 对应（M13 侧补登，M04 无需改）；R3-14 自审"四个必选点"计数修正；补 FU-M04-06"临时医嘱转抄时生成单次计划实例"显式表述；B-6/M-5/M-10/M-4/M-25 相关侧修订
- **05-nursing**：R3-07 删 `inpatient.order.revoked` 死订阅；R3-08 生成规则显式排除 blood/surgery/exam 类医嘱；execution_type"标本采集"限定为非检验标本；R3-11 补订阅 `icu.patient.transferred-out`（重症标识解除）；R3-15 补输注中断（EXECUTING→CANCELLED）回签规则（部分执行回签携实际量、医嘱头经停嘱终态、M13 按实际量计费）；R3-16 source 枚举（并入 M-18）；R5-17 REST 补 `GET /stats/override-rate`、`GET /stats/adverse-events`；R5-18 `POST /tasks` 注记补 M16 为调用方（幂等键=call_no）；R6-12 相应（icu 指名订阅处理见 M 项）；R2-03/R6-03 相关（订阅 pharmacy.prescription.rejected/returned 由 FixC 在 M03 侧，M05 无需）
- **06-pharmacy**：R2-12 状态机标题笔误 narcotic_discrepancy；R2-13 部分退两类时点口径明确（发药完成后部分退 vs 发药中部分明细退场，必要时补"部分发药"中间态）；R2-14 `APPROVED→PENDING_FEE` 迁移驱动声明（补订 `billing.fee.created` 或声明本地乐观迁移+M13 事件校正——裁决：补订阅 `billing.fee.created`）；R6-14 月检延迟队列命名 `delay.base-stock-monthly`；R6-10 `pharmacy.drug.changed` 消费方描述修正（改为"经 M01 通知/API 兜底"或 M03/M04/M13 补订——裁决：修正描述，选药场景实时查 API，不做广播缓存）
- **07-lab**：R4-07 "M20 红线"引用改为"职责①协议门户定位"；R4-08 发布清单订阅方标注补 M11；R4-14 通道容量与设备报文合并估算说明；R4-15 文档头下游被依赖补 M11/M12；R4-17 `lab.specimen.rejected` 载荷强制携带处理类型（退回重采/退检）；R6-10 `lab.specimen.rejected` 消费方描述修正（重采通知经 M01 通知中心）
- **08-imaging**：R4-18 删除 `imaging.study.received` 的 M09 消费表述；R6-04 相关（M03/M04 补订由其 fix 承担）；R6-10 `imaging.study.received` 消费方描述修正（M15/M19 走 API）
- **09-emr**：R4-15 文档头下游被依赖补 M10/M12；R3-10 补 M11 依赖与 `icu.nursing-record.finalized`/`icu.score.completed` 订阅；R4-16 首页费用段快照字段构成说明（引用性存储、计算权威 M13）；R6-12 补 `surgery.anesthesia-record.finalized`、`peis.report.published/corrected` 订阅（如确需，裁决：补订）
- **10-surgery**：R6-09（并入 M-26）
- **11-icu**：R4-08 补订阅 `lab.critical-value.notified`；R3-09 补订阅 `surgery.apply.completed`（过滤去向=ICU，合并落 postop_ref）
- **12-transfusion**：R4-13 source_domain=TRANSFUSION（并入 M-28）；R6-10 `transfusion.inventory.changed` 消费方描述修正（看板刷新改 WS/轮询表述）
- **14-iot**：R5-08 全院大屏主题 `/topic/iot/dashboard/global` 或轮询口径（裁决：增补全院主题）；R5-09 补 REST `POST /ingest/iotda-fallback`；R5-10 被依赖清单补 FU-M16-04；R1-15 source 枚举 IOTDA/HL7 改名；R1-16 字典订阅用途表述澄清（国标字典引用 vs MDC 自管）；R1-13 补 outbox+发布确认声明；R1-14 绑定校验补患者冻结/合并校验（经 M02 解析服务）+ 订阅 patient.merged/frozen（并入 M-25 成对）
- **16-ward**：R5-19 ward_meal_order channel 笔误 OTHER；M-19/M-24 相关侧修订
- **17-peis**：R5-11 红线 3 措辞（"不落费用与结算数据，套餐参考价除外"）；R5-12 补 `POST /group-exams/{no}/settle` 端点与测试；R5-13 `peis.checkin.registered` 发布时点注记（REGISTERED→IN_PROGRESS 迁移时发布）；M-27 措辞修订
- **18-internet**：R5-14 会话超时状态机两处矛盾统一（WAITING 超时→CANCELLED 退费；ACCEPTED 后超时→FINISHED(finish_type=TIMEOUT)）；R5-15 偏差 2 措辞（"逐一比对一致，最终以 event_registry 为准"）；R5-16 偏差 1 补"复诊号别+线上渠道配额表达"裁决请求（不扩枚举）
- **19-ops**：M-21 相关；R6-13 broadcast 事件标注说明
- **00-implementation-order**：按本轮裁决更新异常清单状态（已裁决闭环项标注），CF 冻结点补充新增契约（billing.charge.guaranteed、billing.arrears.approved、pharmacy.medication-order.audit-*、iot.call.triggered/iot.access.passed）

## 4. 已备案待裁决项终审（撰写阶段申报项）

| # | 申报项 | 终审 |
| --- | --- | --- |
| 1 | M14 偏差 1（AMQP 客户端确认） | 采纳；总 Spec 5.3-3 措辞同步修订 |
| 2 | M14 偏差 2（订阅 M04 事件） | 采纳（事件名已在 M04 登记核实） |
| 3 | M15→M13 收入统计 API 扩展 | 采纳（文档头补被依赖 M15）；单机效益的设备归集由 M15 asset_charge_mapping 自持 |
| 4 | M15→M14 asset_ref | 采纳（条件见 M-20） |
| 5 | M15 usage-eligibility 批量校验 | 采纳收窄版：命令下发前同步调用；绑定走 overdue 事件+本地标识 |
| 6 | M16 偏差 1（iot.device.event） | 否决泛化；按 M-19 具体事件登记 |
| 7 | M16 偏差 2（婴儿防盗产科对象=M02/M04） | 采纳（M03 无产科条目已核实） |
| 8 | M17 偏差 2（M13 体检枚举） | 采纳（见 M-27） |
| 9 | M17 偏差 3（幂等豁免） | 改写为 pricing_rule 计价规则配置表达，否决运行时豁免 |
| 10 | M17 偏差 1（体检不签发 visit_id） | 采纳（peis_checkin_no 为锚点，不进 visit 域） |
| 11 | M17 偏差 4（M07/M08 PEIS 适配位） | 采纳（P6 启用，启用时同步修 source_type 枚举与订阅并登记事件） |
| 12 | M18 偏差 1（visit_type 互联网诊疗） | 采纳（M03 枚举补代码 4）；号别问题按 R5-16 处理 |
| 13 | M18 偏差 2（rx_type=INTERNET） | 采纳（激活方式见 M-9） |
| 14 | M18 偏差 3（M09 线上适配位） | 采纳 |
| 15 | M19 偏差 3（M20 target_type） | 无需扩展（"监管平台"枚举覆盖，以 report_type/dataset_code 区分） |
| 16 | M19 偏差 4（M03/M04 工作量统计 API） | 采纳为接口位（P6 前登记口径） |
| 17 | M03 澄清 1-4 | 1/2/4 采纳；澄清 3（计费行归属）按 M-4 主方案落定，删除备选表述 |
| 18 | M06 澄清 1-4 | 全部采纳（处方主数据归 M06、住院用药以医嘱为权威、五专完整口径、审方共用引擎） |
| 19 | M04 澄清 1-4 | 全部采纳（药师审核必经、包床计费属性、routing 子键、会诊域内闭环） |
| 20 | M07 偏差 1-4 | 偏差 1（签收计费+上机占用双时点）采纳；偏差 2（ASTM 通道）按 M-15 落地；偏差 3（危急值引擎归属）采纳；偏差 4 采纳 |
| 21 | M08 偏差 1-6 | 偏差 1/2/3/6 采纳；偏差 4（计费双时点）采纳（M13 补 completed，R4-09）；偏差 5（DICOM 不经 M20）按 M-15 登记 |
| 22 | M09 偏差 1-4 | 全部采纳（WS 445 混合策略、icd_mapping 豁免、借阅语义、传染病卡落点） |
| 23 | M10 偏差 1-3 | 偏差 1（WS 329-2024 分级粒度）采纳；偏差 2（术前核查单独立）采纳；偏差 3（离室门控 P1 先行）采纳 |
| 24 | M11 偏差 1-3 | 全部采纳（出入量分工、复用 M14 引擎、双通道） |
| 25 | M12 偏差 1-4 | 1/2/3 采纳；偏差 4（订阅登记以本模块为准）按 M-17/M-18 落地 |
| 26 | M02 澄清（合并细化为读侧归一） | 采纳（与 B-2 修复一致） |

## 5. 全局核查结论（R6 矩阵）

- 发布事件 189 个 / 订阅声明约 267 条；名称级匹配 88%；WebSocket 15 个端点前缀全局唯一无冲突；38 个延迟队列命名无冲突（唯一缺陷 R6-14 已裁决）；REST 路径无冲突、高频跨模块 API 引用全部存在且语义一致。
- 19 个零订阅发布事件判定为合理广播/模块内闭环，要求在 event_registry 统一标注 broadcast 语义。

## 6. 修复执行与验证

- 修复由 fix subagent 按本报告执行，分 8 组：FixA（README/master/01/20）、FixB（02/13）、FixC（03/06）、FixD（04/05）、FixE（07/08/09）、FixF（10/11/12）、FixG（14/15/16）、FixH（17/18/19/00-implementation-order）。
- 修复后由验证 subagent 复核：①全部 BLOCKER/MAJOR 逐条核对修复落地；②抽验修复引入的新事件名/路径与对方清单一致性；③编码与格式合规（UTF-8 无 BOM / LF / 无占位符）。
- 闭环状态：Round 1 + Round 2 已闭环（2026-09-07）：Round 1 裁决 99 项全部修复并验证（BLOCKER 6/6、MAJOR 28/28、MINOR 抽验 54/55）；Round 2 点状修复 13 处，复核 13/13 通过。Round 2 复核中发现的 00-implementation-order 两处旧口径已随本闭环修正。
