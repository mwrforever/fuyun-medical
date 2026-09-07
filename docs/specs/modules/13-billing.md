# M13 收费物价与医保结算 · 功能实现 Spec

| 属性 | 内容 |
| --- | --- |
| 模块编号 | M13 |
| Maven 模块 | `fuyun-billing`（schema：`billing`） |
| 版本 / 状态 | v1.1 / 统一审查修订 |
| 上游依赖 | M01（权限/字典/参数/审计/通知/打印模板）、M02（patient_id 与 EMPI）、M03（门诊 visit_id 签发与收费业务入口、开单事件、门诊就诊登记/治疗执行/绿通事件）、M04（住院 visit_id 签发、入院/医嘱/执行/出院事件）、M06（药品编码对照、处方生效计费事件、发药/退药事件）、M07/M08（检验上机/检查登记与完成事件、执行状态查询）、M09（病案首页诊疗信息与首页提交事件，结算清单数据源，P1 后）、M10（手术计费事件）、M12（输血计费事件）、M17（体检登记事件，P6 预留）、M18（线上处方退回事件）、M20（出站通道、调用留痕监控、幂等构件） |
| 下游被依赖 | M03/M04（收退费、划价、押金、一日清单 API）、M07/M08/M10（计费结果查询、退费前置校验回调）、M18（线上缴费/线上医保结算复用）、M19（收入统计取数 API）、M15（设备效益收入取数——按收费项目×执行科室×日期只读聚合统计 API，与 M19 同口径）、M20（医保通道治理的业务对象来源） |
| 对应总 Spec | FU-M13-01 ~ FU-M13-08 |

---

## 1. 模块定位与边界

**职责**：本模块是全院收入主线的数据与规则中枢，八项职责：① 收费项目与物价管理——物价项目库、版本化价格、国家医保 22 项编码对照、组合项目；② 计价引擎——医嘱/执行联动自动计价、手工计价、重复计费拦截；③ 统一收退费——门诊/住院收退费、支付方式收口、退费分级审批；④ 押金管理——住院预交金、欠费提醒、一日清单；⑤ 医保结算接口——国家基线版接口（门诊/住院结算、医保电子凭证、异地就医、结算清单上传）；⑥ 发票管理——医疗收费电子票据与数电票开具、红冲；⑦ 财务对账——收费员日结/月结、医保日对账、与 HRP 财务系统对账；⑧ 医保智能控费基础——目录内费用监测、高频违规提醒（智能审核规则库为扩展）。

**非职责**：药品/耗材业务字典及其国家编码维护（归 M06，本模块仅维护收费项目到医保编码的对照关系）；检验/检查/手术项目业务定义（归 M07/M08/M10）；病案首页与结算清单的诊疗内容（诊断/手术编码与质控归 M09，本模块提供费用域数据并编排上传）；收费业务操作界面（门诊收费窗口与诊间收费入口归 M03、住院费用展示归 M04，本模块提供能力 API）；通道级接口协议治理与监控（归 M20）；财务总账凭证生成（归外部 HRP 系统，本模块只输出对账数据）；就诊卡账户余额本身（归 M02，本模块结算时调用其账户服务收付）。

**模块红线**：
1. 金额计算全部服务端完成（总 Spec 决策 D5）：单价、数量校验、折扣、医保统筹/个账/自付/自费拆分、退费金额一律服务端计算，前端传入的任何金额字段不采信。
2. 已结算数据不可变：结算单、票据、医保回执一经结算只读，更正只能经退费/红冲流程，历史费用记录禁止改写。
3. 计费必须可追溯：每笔费用必须携带计费来源（医嘱联动/执行联动/手工）与来源单据引用；手工计费强制记录操作者与理由。
4. 禁止跨模块读表；医保出站调用统一经 M20 通道发起并留痕；收费/退费/日结等资金操作全量审计。
5. 医保交易凭证（预结算/正式结算回执、撤销回执）原文留存、不可篡改，作为基金结算争议的唯一凭据。

## 2. 调研依据

1. **计费触发模式**：HIS 存在"前计费（医嘱审核/分解时计费）与后计费（执行时计费）"两种模式，药品前计费在审核（临时医嘱）或分解（长期医嘱）时点计费，未发药可直接退费。（来源：https://www.cnblogs.com/hangwq/p/3520461.html ）医嘱自动生成执行单并生成计价信息传送住院收费、自动按医嘱划价是标准设计。（来源：https://www.cnblogs.com/onmyway/archive/2011/08/19/2145189.html ）
2. **联动计费方式族**：真实医院招标要求完整计费体系支持扫码执行计费、医嘱派生计费、持续时长计费（床位/护理类）、评估单录入计费四类联动，且计费规则可设置。（来源：成武县人民医院信息化技术外包招标 http://www.chengwu.gov.cn/ ）
3. **重复计费拦截为官方规范要求**：《中医医院信息化建设基本规范（试行）》要求护士站计费时提示目前已收费用、避免重复收费；住院收费系统必须具备住院预交金管理、欠费管理功能。（来源：http://www.jincao.com/fa/10/law10.49.htm ）
4. **国家基线版接口**：定点医药机构接口规范（基线版）以交易码组织门诊挂号/预结算/结算、住院入院办理/费用上传/预结算/结算、结算撤销、医保目录与政策参数下载、文件上传下载等 118+ 项交易，明确"住院/门诊号不可重复"。（来源：重庆市医保局接口规范 https://ylbzj.cq.gov.cn/bmfw_535/xzzq/202301/P020230109645235887118.pdf 、https://ylbzj.cq.gov.cn/bmfw_535/xzzq/202408/P020240805414917177370.pdf ；咸宁市基线版 http://ybj.xianning.gov.cn/xxgk/zc/wjzl/202605/P020260528535824147855.pdf ）
5. **异地就医**：跨省直接结算执行"就医地目录、参保地政策"，基金支付部分先预付后清算；住院、普通门诊、门诊慢特病费用统一纳入直接结算。（来源：https://www.nhsa.gov.cn/art/2020/9/30/art_37_3679.html 、https://www.nhsa.gov.cn/art/2022/7/26/art_104_8629.html ）
6. **医保电子凭证**：医保码用户超 10 亿，挂号/缴费/诊间/自助机扫码均可结算；结算前需完成安全认证（如密码核验认证服务接入）。（来源：https://www.nhsa.gov.cn/art/2023/11/24/art_14_11548.html ；重庆市医保电子凭证密码核验认证服务接入规范 https://ylbzj.cq.gov.cn/bmfw_535/xzzq/202211/P020221120624363574175.pdf ）
7. **结算超时冲正**：医保结算超时时由发起方自动触发冲正、撤销已完成结算并释放冻结资金，冲正后该笔不可再发起支付——结算交易必须以幂等流水号管理并支持撤销确认。（来源：微信医保支付开发指引 https://pay.weixin.qq.com/doc/v3/merchant/4016824681 ）
8. **结算清单**：医保结算清单是 DRG/DIP 付费的结算依据，主要诊断/手术编码准确率纳入医保绩效考核（多地要求 100%）；每月医保费用申报前必须完成结算清单上传。（来源：泉州市 CHS-DRG 绩效考核方案 https://qzybj.quanzhou.gov.cn/zwgk/zfxxgk/fdzdgknr/zcwj/202210/P020221028364261380471.pdf ；湖北省即时结算经办办法 http://ybj.hubei.gov.cn/zfxxgk/zc/gfwj/202512/t20251208_5828755.shtml ；辽宁省医保结算清单及编码填报管理规范 https://ybj.ln.gov.cn/ybj/zwgk/zcwjyjd/2025110609133982058/index.shtml ）
9. **编码贯标**：医保信息业务编码已扩展至 22 项并由国家平台动态维护；贯标验收要求定点医疗机构 HIS 收费系统全部收费项目对照国家码且辅助校验通过。（来源：https://code.nhsa.gov.cn/ 、https://www.nhsa.gov.cn/art/2024/11/29/art_14_14901.html 、重庆市永川区贯标通知 https://www.cqyc.gov.cn/bm/qybj_87656/zwgk_87667/zfxxgkml0_168853/zcwj0_168857/qtwj0/202106/t20210618_9406916.html ）
10. **飞检与智能审核**：重复收费、超标准收费、分解项目收费约占飞检违规问题的 36%，串换药品/耗材/诊疗项目为另一高发类别，《医疗保障基金使用监督管理条例》第十五条为认定依据；国家智能监管已上线 243 类 9 万余条审核规则、实现事中全自动审核。（来源：https://www.nhsa.gov.cn/art/2024/4/28/art_105_12530.html 、https://www.nhsa.gov.cn/art/2026/6/18/art_14_21031.html ）
11. **电子票据**：医疗收费电子票据由财政部门监制监管（区别于应税发票），系统"内连 HIS、外接财政查验平台"，覆盖申领/开具/核验/冲红/归档全环节；票据状态含已开具/已打印/已红冲/已换开纸质；红字冲销按红字确认单流程，开票有误仅允许全额红冲、销货退回可部分红冲。（来源：https://html.rhhz.net/ZGWSZY/html/2022-4-478.htm 、https://www.chima.org.cn/Html/News/Articles/8715.html 、https://www.zryhyy.com.cn/zryh/c100021/202501/35b2831565ff42149cf096f38a86cc51.shtml 、https://www.kdfpy.com/news/385.html 、https://fgk.chinatax.gov.cn/zcfgk/c100012/c5236067/content.html ）
12. **预交金政策**：2025 年 3 月起公立医院全面取消门诊预交金，住院预交金额度降至同病种个人自付平均水平，按疾病诊断/治疗方式/结算类型/医保类型核定，配套"信用就医"。（来源：https://www.gz.gov.cn/zwfw/zxfw/ylfw/content/post_10094549.html 、https://www.yicai.com/news/102448823.html 、https://china.cnr.cn/gdgg/20250301/t20250301_527085239.shtml ）
13. **日结与 HRP 对账**：互联网支付背景下收费员原则上每天结账（可跨天不跨月）；上海新华医院将 HIS 门诊收入、住院收入、预交金数据每天经集成接口同步 HRP，核对后自动生成财务凭证。（来源：http://wdczkjsgzs.com/yiyuantuoguan_view.asp?id=28 、http://www.aidemed.com/news/373-cn.html 、https://www.woshipm.com/pd/6132161.html ）
14. **即时结算改革**：医保基金对医疗机构即时结算已启动，拨付时限由约 60 天压缩至 1 天，倒逼院内结算与对账数据实时准确。（来源：https://www.nhsa.gov.cn/art/2025/1/13/art_14_15457.html ）

## 3. 方案推导（关键设计点选型）

### 3.1 计价引擎触发模式：医嘱开立实时计价 vs 定时批处理 vs 事件驱动为主 + 周期日切补充（混合）

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 医嘱开立/审核即实时计价 | 医嘱审核通过立即生成全部费用 | 长期医嘱（按频次多次执行）在开立时点无法确定全部费用，仍需二次分解；医嘱高峰直接转化为计费写库洪峰；停嘱/作废需高频冲费，费用与执行态易脱节 |
| 定时批处理 | 夜间/每小时批量扫描医嘱与执行记录统一生成费用 | 写库平稳，但费用实时性差：患者日间看不到当日费用、欠费预警滞后、门诊收费无法依赖；批处理故障影响全院计费 |
| **事件驱动 + 周期日切补充（选定）** | 按"费用是否依赖执行确认"分流：依赖执行的离散类费用（检验上机、检查登记、手术、扫码消耗耗材）订阅执行域事件即时计费（后计费）；药品费用单独锚定权威计费时点——门诊药费订阅 M06 `pharmacy.prescription.created`（处方生效）生成 PENDING、住院药费按 M04 `inpatient.order.audited`（医嘱审核通过）即时计价，发药/摆药事件仅更新费用执行占用标记、不触发生成费用行；持续性费用（床位费/护理费/诊查费等按时长按日）以 `inpatient.visit.admitted`（入院）为起费锚点、由每日日切任务按长期医嘱频次分解自动生成；门诊非药品开单事件生成待收费费用、收费时正式确认；另设手工计费通道 | 与"费用与医嘱（执行）一致性"目标最强：每笔费用锚定执行闭环事件，杜绝"收了费没执行/执行了没收费"；门诊高峰（总 Spec 2000 人次/h）的计费发生在开单与收费两个天然同步点，住院洪峰被日切任务挪到低峰，性能可控；前计费/后计费并存是行业既有实践（调研依据 1、2），本方案按费用类型显式归类而非二选一 |

**结论**：事件驱动为主 + 周期日切补充。配套两条硬规则：① 计费唯一键（来源单据 + 计费点 + 项目 + 计费日）唯一约束兜底防重——门诊/住院就诊类费用来源单据为医嘱/申请单/执行单引用，体检类费用（P6 预留）来源单据取 peis_checkin_no，计费前回显该就诊已收费用（调研依据 3）；② 执行类事件必须幂等消费（复用 M20 幂等构件），执行域与费用域以来源单据引用做每日一致性对账，差异进异常清单人工处理。

### 3.2 医保接口对接架构：同步阻塞调用 vs 全异步任务 vs 分级同步 + 异步对账补偿

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 全同步阻塞 | 收费/结算全链路同步等待医保中心应答 | 基线版交易虽为"请求-应答"式，但医保中心高峰/维护期响应不稳定，患者在场场景下窗口等待不可控；费用上传、清单上传、目录下载若同步执行会拖垮收费主链路 |
| 全异步任务 | 一切医保交易进任务队列排队执行 | 预结算结果（统筹/个账/自付拆分）是收费员向患者收款的依据，患者在场时必须秒级可得，全异步不满足窗口业务形态 |
| **分级同步 + 异步对账补偿（选定）** | 患者在场的短交互（电子凭证认证、预结算、正式结算、结算撤销/冲正）同步调用、短超时、快速失败重试，以"机构 + 终端 + 交易流水号"为幂等键，超时悬挂先用查询交易确认终态、不可确认才转补偿任务，绝不盲目重发正式结算（调研依据 7 的冲正语义）；无人在场的批量交互（住院费用明细上传、结算清单上传、目录/政策下载、日对账、失败补偿）任务化异步执行、指数退避重试、失败进死信人工处理 | 与真实窗口业务形态匹配；医保中心不稳定的兜底完整：悬挂确认 → 冲正/补偿 → 日对账差异清单三级防线；应急能力：医保通道不可用时启用"自费先行"应急模式（参数开关、全量留痕、显式标记待补结算），恢复后按待办补办医保结算或引导患者零星报销 |

**结论**：分级同步 + 异步对账补偿。全部医保调用经 M20 出站通道与 interface_call_log 留痕监控（FU-M20-07）；本模块另建业务级 insurance_call_log 记录"交易 ↔ 结算单 ↔ 补偿状态"关联，两级日志分工：M20 管通道治理（成功率/耗时/重推），M13 管业务补偿（悬挂确认、冲正、补结算待办）。

### 3.3 退费的风控与审批流：全部免审 vs 全部审批 vs 分级免审 + 审批 + 红冲规则

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 全部免审 | 收费员直接退 | 退费是医保飞检与院内资金舞弊高发环节（调研依据 10），无管控不可接受 |
| 全部审批 | 一切退费走审批 | 收费员当日错收更正属高频日常操作，全审批导致窗口排队与临床体验崩坏 |
| **分级免审 + 审批 + 红冲规则（选定）** | 按"是否发生业务占用、是否跨日、金额、是否已结算、是否已开票"五维分级（详见 6-FU-M13-03） | 日常更正秒级放行、风险场景留痕审批；与逆向业务（退药/撤执行）硬绑定，杜绝"只退钱不退业务"；已结算账单一律走红冲不改历史，与财务及医保撤销流程对齐 |

**结论**：分级免审 + 审批。已结算账单处理规则：自费结算退费 = 票据红冲 + 负向退费记录 + 原渠道退款；医保已结算退费 = 先调医保结算撤销交易（基金拆分回退）→ 院内退费审批 → 票据红冲 → 需要时重新结算；红冲为"红票对冲原票"，原票与原结算单永远保留可查。审批分级阈值全部走系统参数（M01）可配置，不写死代码。

### 3.4 价格一致性机制：直接改价 vs 逐笔冻结全量上下文 vs 版本化价格 + 计费时点快照

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 直接改价 | 项目表存当前价，调价即更新 | 历史费用按新价重现，审计无法追溯"当时为什么收这个价"，物价检查不可过 |
| 逐笔冻结全量上下文 | 每笔费用冻结价格 + 医保对照 + 先自付比例 + 限价全量冗余 | 一致性最强，但字段冗余量大且与版本表重复 |
| **版本化价格 + 计费时点快照（选定）** | 项目价格与医保对照均版本化（生效区间管理，调价草稿 → 定时生效 → 广播事件）；费用生成时冻结单价快照（单价、价格版本、医保对照编码、目录版本、先自付比例、限价），结算、清单、对账、审计一律按快照重现 | 调价不溯既往（物价合规硬要求）；医保目录动态更新（调研依据 9）下结算按目录版本可重现；快照字段控制在结算必需集，避免无限冗余 |

**结论**：版本化价格 + 计费时点快照。快照为费用表的冗余字段集（非独立表），版本表为唯一权威源；两者不一致以费用快照为准（历史正确性优先），新费用一律取当前生效版本。

## 4. 领域模型

表设计统一遵循 README 第 3 节约定：雪花 BIGINT 主键、统一审计字段（created_by/created_at/updated_by/updated_at/deleted）、TIMESTAMPTZ 服务器时间、逻辑删。

| 实体 | 关键字段 | 说明 |
| --- | --- | --- |
| charge_item 收费项目 | item_code、item_name、item_class(药品/西药/中药/诊疗/耗材/床位/护理/其他)、unit(计价单位)、exec_dept_id(执行科室)、price_flag(可单独收费/仅组合内)、combo_flag(组合项目)、清单费用大类、状态 | 物价项目库权威源；药品/耗材收费项的业务属性引用 M06 主数据，仅存 item_code 级关联，不复制药品字典 |
| charge_item_price 价格版本 | charge_item_id、price(NUMERIC(18,2))、version、effective_from/effective_to、price_source(物价批文/协议价)、批文号 | 同一项目多版本，区间不重叠约束；调价草稿→定时生效，发事件广播 |
| charge_item_component 组合构成 | combo_item_id、component_item_id、默认数量 | 组合项目划价时展开为成员明细，逐成员计价与对照医保 |
| insurance_mapping 医保对照 | charge_item_id、map_type(诊疗项目/药品/耗材)、nhsa_code(国家 22 项编码)、目录版本、先自付比例、限价、医保支付属性(甲/乙/丙/自费)、对照状态、校验回执 | 贯标核心表；未对照或对照失效项目不允许参与医保结算（仅可自费并显式提示） |
| pricing_rule 计价规则 | rule_code、trigger_type(order_confirmed 医嘱审核/prescription_effective 处方生效/executed 执行回签/registered 登记/scanned 扫码/duration 时长分解/manual 手工)、charge_item 集合、频次分解规则、计费唯一键构成、启停 | 计价引擎的配置面；规则变更全量审计；体检来源"仅回写执行占用、不生成费用行"以规则配置表达——trigger_type=executed 的规则排除 charge_source=体检类目（否决运行时豁免分支） |
| fee_record 费用明细 | fee_no、patient_id、visit_id、visit_type(门诊/住院/体检——体检为 P6 预留取值)、charge_item_id、item_name_snapshot、unit_price_snapshot(单价快照 NUMERIC(18,2))、quantity、amount、清单费用大类、charge_source(医嘱联动/执行联动/日切分解/手工/体检——体检为 P6 预留取值)、source_ref(来源单据引用：医嘱/执行单/申请单/操作者/peis_checkin_no)、insurance_mapping_snapshot(对照编码/目录版本/先自付比例/限价快照)、settlement_id、执行占用标记(已发药/已执行/已上机)、status、charged_at | 全模块核心表；金额=服务端按快照计算；计费唯一键唯一约束防重；原明细只读，退费以关联负向记录表达 |
| refund_fee_link 退费费用关联 | refund_id、fee_id、refund_quantity、refund_amount(NUMERIC(18,2)) | 退费申请与费用明细多对多，支持部分退 |
| refund_request 退费申请 | refund_no、settlement_id、patient_id、visit_id、reason、refund_type(当日更正/跨日退费/已结算退费)、amount、审批链引用、payment_refund_ref(原路退回流水)、status | 审批分级按规则引擎取系统参数阈值 |
| arrears_approval 出院挂账审批 | approval_no、patient_id、visit_id(住院)、arrears_amount(挂账金额 NUMERIC(18,2))、reason、applicant(病区/主管医师)、approver、approved_at、status(DRAFT/PENDING_APPROVAL/APPROVED/REJECTED) | 出院未结清挂账的审批载体（M04 工作站发起申请）；审批通过发布 `billing.arrears.approved`，驱动 M04 出院放行校验 BLOCKED→READY |
| deposit_account 预交金账户 | patient_id、visit_id(住院)、balance(NUMERIC(18,2))、warning_threshold、status | 一人一住院账户；门诊预交金按国家政策取消，不设门诊账户 |
| deposit_txn 预交金流水 | account_id、txn_type(缴入/退回/结算抵扣)、amount、payment_method、渠道流水号、操作者、occurred_at | 只增流水，余额由流水聚合维护 |
| settlement 结算单 | settle_no、patient_id、visit_id、settle_type(门诊/住院/出院结算/体检——体检为 P6 预留取值)、payer_type(自费/市医保/省医保/异地医保/商业保险预留)、fee_period(费用起止)、total_amount、医保拆分(统筹支付/个账支付/自付/自费/先自付，均 NUMERIC(18,2))、payment_details(支付方式×金额×渠道流水)、医保侧标识(中心结算流水号/回执原文引用)、目录版本、清单引用、status | 金额拆分全部服务端按医保预结算回执落库，本地不自行计算基金拆分 |
| invoice 票据 | settlement_id、ticket_type(财政电子票据/数电票)、ticket_no、开票渠道引用、pdf/ofs 文件引用、red_flip_ref(红冲票据引用)、status | 财政电子票据为主（非营利机构医疗收入）、数电票用于应税场景；与结算单一一对应 |
| daily_statement 日结 | statement_no、operator_id(收费员/病区)、period_start/end、金额汇总(按支付渠道/费别/医保类型)、缴款状态、HRP 传输状态、status | 收费员日结可跨天不跨月（调研依据 13）；月结为 period_type=MONTH 的同构记录 |
| insurance_call_log 医保业务调用日志 | txn_code(基线版交易码)、settlement_id/visit_id 关联、request_digest/response_digest(脱敏)、中心流水号、result_code、duration_ms、悬挂标记、补偿任务状态、trace_id | 业务级留痕：悬挂确认、冲正、补结算待办的驱动数据；通道级留痕在 M20 interface_call_log，不重复建设 |
| control_rule 控费规则 | rule_type(重复收费/超标准收费/分解项目收费/串换预警)、threshold、scope(项目/科室/全院)、action(仅提醒)、enabled | FU-M13-08；本期只提醒不拦截，规则命中记录留档供医保办核查 |

关系要点：项目 1:N 价格版本 / 对照记录；组合项目 1:N 构成成员；费用 N:1 结算单；退费申请 N:M 费用（经 refund_fee_link）；出院挂账审批挂住院 visit；结算单 1:1 票据；预交金账户 1:N 流水；日结聚合区间内结算单与流水。

## 5. 状态机与业务流程

- **fee_record 费用明细**：`PENDING(已生成待确认) → CONFIRMED(已确认入账) → SETTLED(已结算) → PART_REFUND(部分退) / FULL_REFUND(全退)`；`PENDING / CONFIRMED → CANCELLED(作废，仅未结算可作废，必填原因与操作者)`；绿通/挂账分支：`CONFIRMED → GUARANTEED(绿通/挂账放行，发布 billing.charge.guaranteed)`，`GUARANTEED → SETTLED(挂账追缴入账) / BAD_DEBT(坏账核销，走财务核销流程，留痕)`。状态迁移经状态机服务校验并留迁移日志；`SETTLED` 后原记录只读，退费生成负向关联记录。
- **settlement 结算单**：`DRAFT(创建) → PRESETTLED(医保预结算回执已锁价，自费结算可跳过) → SETTLED(已结算，终态基点) → REFUNDED(全额退结) / RED_REVERSED(红冲重结)`；`DRAFT / PRESETTLED → CANCELLED(放弃/预结算失效)`。
- **deposit_account**：`NORMAL ⇄ ARREARS(余额低于阈值自动切换，恢复缴存自动解除)` → `SETTLED(出院结清) → CLOSED`。
- **deposit_txn**：`ACTIVE → REFUNDED(退回) / OFFSET(结算抵扣)`；`ACTIVE` 流水禁止修改，退回生成对冲流水。
- **invoice 票据**：`ISSUED(已开具) → RED_FLIPPED(已红冲)`；`ISSUED → PAPER_ISSUED(已换开纸质，电子票锁定不可再红冲，换开留痕)`。
- **refund_request**：`DRAFT → PENDING_APPROVAL(待审批) → APPROVED(审批通过) → EXECUTED(资金原路退回完成)`；`PENDING_APPROVAL → REJECTED(驳回，必填原因)`；`EXECUTED` 失败可重试并留痕。
- **arrears_approval 出院挂账审批**：`DRAFT(病区/主管医师发起申请) → PENDING_APPROVAL(审批中) → APPROVED(已批准，发布 billing.arrears.approved) / REJECTED(已驳回，必填原因)`；REJECTED 为终态，可重新发起新申请。
- **insurance_call_log**：`INIT → SENT → SUCCESS / FAILED / TIMEOUT(悬挂)`；`TIMEOUT → COMPENSATED(查询/冲正确认终态) / WAIVED(人工核销，必填结论)`。

主流程时序：

1. **门诊收费（医保患者）**：医生站开单（M03 发布开单事件，非药品计费行；药品计费行由 M06 `pharmacy.prescription.created` 处方生效事件生成）→ 计价引擎按规则生成 PENDING 费用（价格快照冻结）→ 收费员发起结算：本模块调医保电子凭证认证 → 费用上传 → 预结算（同步短超时，取回基金拆分）→ 患者支付（多渠道）→ 正式结算（幂等流水号）→ 结算单 `SETTLED` + 费用批量 `SETTLED` → 开具医疗收费电子票据 → 发布 `billing.settlement.completed` → M03 放行发药、患者端收到票据。
2. **急诊绿通挂账放行**：绿通开启（订阅 `outpatient.green-channel.opened`，置挂账态）→ 绿通患者费用确认后不即时结算，转 `GUARANTEED` 并发布 `billing.charge.guaranteed`（挂账放行回执）→ M03 订阅后置订单 `CHARGED` 放行发药 → 绿通关闭（订阅 `outpatient.green-channel.closed`）后挂账费用转正常追缴结算（`GUARANTEED → SETTLED`）或坏账核销（`BAD_DEBT`，财务流程留痕）。
3. **住院计费与出院结算**：订阅 `inpatient.visit.admitted` 登记床位费/护理费等持续性费用起费锚点 → 医嘱审核事件（`inpatient.order.audited`，住院药费即时计价）与执行回签事件生成离散类费用；每日日切任务按长期医嘱分解生成持续性费用并按转科时间线切分归属 → 费用 `CONFIRMED` 入账（欠费预警基于账户余额与已确认费用计算，低于阈值发 `billing.deposit.changed` 与 M01 通知）→ 患者或床旁屏按日查询一日清单 → M04 出院申请 → 停止计费 → 医保患者费用明细上传 → 预结算 → 出院结算（预交金流水抵扣、多退少补）→ `SETTLED` → 票据 → 订阅 `emr.homepage.submitted`（M09 病案首页提交）触发结算清单上传任务 → 清单数据就绪待上传。
4. **医保结算冲正补偿**：正式结算超时 → insurance_call_log 置 `TIMEOUT` → 悬挂查询交易确认：已成功则回填回执、未成功则按属地域规范发起撤销/冲正 → 冲正回执归档 → `COMPENSATED`；日对账任务将本院结算流水与医保中心流水逐笔核对，差异生成清单并驱动补偿任务。
5. **已结算退费（医保患者）**：退费申请（关联原结算单与费用）→ 系统校验执行占用（已发药/已执行强制先逆向业务）→ 按分级审批通过 → 调医保结算撤销交易（基金拆分回退回执落库）→ 票据红冲 → 原路退回患者 → 相关费用转 `PART_REFUND/FULL_REFUND`、结算单转 `REFUNDED` 或红冲重结 → 发布 `billing.refund.approved` 与票据事件。

## 6. 功能实现设计（逐 FU）

| FU | 实现设计要点 |
| --- | --- |
| FU-M13-01 收费项目管理（P0） | 物价项目库维护（类别/计价单位/执行科室/清单费用大类）；价格版本化与定时生效调价（方案 3.4），调价发布广播 `billing.charge-item-price.published` 供工作站刷新；国家医保 22 项编码对照：诊疗项目对照在本模块维护，药品/耗材收费项对照引用 M06 维护的国家码（单一权威源，防双头维护），对照表含先自付比例与限价，随医保目录版本更新；贯标硬校验：无有效国家码对照的项目禁止参与医保结算、仅可自费并界面显式提示（调研依据 9）；组合项目：组合定义 + 划价展开为成员明细，逐成员对照医保，保证清单费用大类正确 |
| FU-M13-02 计价引擎（P0） | 规则化触发：医嘱审核联动（住院离散类与住院药费，锚定 `inpatient.order.audited`）、处方生效联动（门诊药费，锚定 M06 `pharmacy.prescription.created`，生成 PENDING 费用）、执行回签联动（上机/登记/扫码，事件订阅；发药/摆药事件仅更新执行占用标记、不生成费用行）、日切时长分解（床位/护理/诊查类，起费锚定 `inpatient.visit.admitted`，按频次规则与转科归属切分）、手工计费（强制操作者与理由）；门诊非药品开单事件生成 PENDING 费用、收费时确认；计费唯一键（来源单据+计费点+项目+计费日）唯一约束 + 计费前已收费用回显双防线（调研依据 3），体检类费用来源单据取 peis_checkin_no（P6 预留）；体检来源的执行域事件仅回写执行占用、不生成费用行——以 pricing_rule 规则配置表达（trigger_type=executed 的规则排除 charge_source=体检类目），不设运行时豁免分支；提供预计价 API（不落库，供开单界面与收费处划价展示）与正式计价服务；执行域事件幂等消费；每日费用↔医嘱/执行一致性对账，差异进异常清单 |
| FU-M13-03 收退费（P0） | 统一收退费服务：支付方式收口（现金/银行卡/扫码聚合支付/就诊卡余额调 M02 账户/急诊绿通挂账）；退费分级（方案 3.3）：当日更正性退费且未发生执行占用且金额≤阈值 → 收费员免审直退；跨日、超免审阈值、部分退 → 收费组长一级审批；大额（参数阈值）、医保已结算、票据已开具 → 财务/医保办二级审批；执行占用硬前置：已发药先退药（M06）、已执行/已上机先撤销执行（M05/M07/M08，状态经其查询 API 校验）；已结算账单按红冲规则处理（方案 3.3），历史记录只读 |
| FU-M13-04 押金管理（P0） | 住院预交金：多渠道缴存（窗口/自助机/扫码/线上，线上经 M18 复用）、账户余额实时聚合；预交金额度参考同病种个人自付平均水平配置并支持超标准提醒（2025 年国家政策，调研依据 12；门诊预交金取消不设门诊账户）；欠费预警：余额−已确认费用 < 阈值 → 押金账户转 `ARREARS`，经 M01 通知护士站/主管医师/患者，发布 `billing.deposit.changed`；一日清单：按日费用明细+汇总 API，供 M04 工作站、床旁屏、患者端取数展示；出院结算多退少补，抵扣流水化 |
| FU-M13-05 医保结算接口（P0 起步、P5 完整） | 基线版接口对接（交易族按属地域规范配置）：就诊登记（门诊/入院办理，机构内号唯一约束呼应"住院/门诊号不可重复"，调研依据 4）、费用明细上传（住院每日批量）、预结算、正式结算、结算撤销/冲正、日对账、目录与政策参数下载；架构按方案 3.2 分级同步+异步补偿；医保电子凭证：扫码/线上凭证认证后结算，认证失败明确提示改用实体卡；异地就医：备案信息校验、基金拆分以医保中心预结算回执为准落库（就医地目录、参保地政策）；结算清单上传：费用段由本模块按清单规范汇总生成、诊疗段取自 M09 病案首页，清单上传任务由订阅 `emr.homepage.submitted`（M09 病案首页提交）触发，上传前质控校验（诊断/手术编码完整、费用大类汇总与明细合计一致、清单与结算单金额勾稽），上传任务异步+回执留痕（清单准确率纳入医保考核，调研依据 8）；医保通道不可用时自费先行应急模式（参数开关+待补结算清单，恢复后补办） |
| FU-M13-06 发票管理（P1） | 财政医疗收费电子票据为主：对接属地财政票据平台（申领/开具/查验/冲红/归档），内连结算外接财政平台（调研依据 11）；数电票用于应税场景，红冲按红字确认单规则（开票有误仅全额红冲，调研依据 11）；结算完成即触发出票任务（异步），出票失败可重开；红冲=红票对冲，原票保留；换开纸质后电子票锁定；票据文件（PDF/OFD）经对象存储归档，患者端经链接取票 |
| FU-M13-07 财务对账（P1） | 收费员日结：区间=上次日结完点至本次，可跨天不跨月（调研依据 13），按支付渠道/费别/医保类型汇总、生成缴款单；日结完成发布事件并触发与 HRP 同步（门诊收入/住院收入/预交金/票据汇总，每日一次经 M20 通道传输，对齐新华医院实践）；月结汇总；医保日对账：本院结算流水↔医保中心流水逐笔核对，差异清单驱动补偿；渠道对账：聚合支付/就诊卡账户流水与结算单勾稽；对账差异统一进异常工作台（定位、处理、销号全留痕） |
| FU-M13-08 医保智能控费基础（P2） | 内置高频违规提醒规则（对齐飞检 36% 高发类别，调研依据 10）：重复收费（同就诊同项目超合理频次）、超标准收费（快照单价越医保限价）、分解项目收费（组合与成员重复收取）、串换预警（收费项目与执行/对照项目语义偏离）；仅提醒不拦截（避免影响诊疗流程），命中记录推送医保办工作台核查；费用目录内占比、自费占比监测报表；预留属地智能审核知识库/规则库接入扩展位（规则来源可插拔） |

## 7. 对外接口

**REST（`/api/v1/billing/` 前缀，节选）**：
- 项目物价：`GET/POST/PUT /charge-items`、`POST /charge-items/{id}/prices`（调价草稿）、`POST /price-adjustments/{id}/publish`（生效发布）、`GET/POST/PUT /insurance-mappings`（对照维护）
- 划价与费用：`POST /pricing/quote`（预计价，不落库）、`POST /fees/manual`（手工计费）、`GET /fees?visitId=`（费用查询）、`POST /fees/{id}/cancel`（未结算作废）、`GET /daily-lists?visitId=&date=`（一日清单）
- 结算与退费：`POST /settlements/preview`（预结算）、`POST /settlements`（正式结算）、`GET /settlements/{no}`、`POST /refunds`（退费申请）、`POST /refunds/{id}/approve|reject`（审批）、`POST /refunds/{id}/execute`（执行退回）
- 挂账审批：`POST /arrears-approvals`（出院挂账申请，M04 工作站调用）、`GET /arrears-approvals?visitId=`、`POST /arrears-approvals/{id}/approve|reject`（审批）
- 押金：`POST /deposits`（缴存）、`GET /deposits?visitId=`、`POST /deposits/{txnId}/refund`
- 医保：`POST /insurance/register`（就诊/入院登记上传）、`POST /insurance/fee-upload`、`POST /insurance/settle`、`POST /insurance/reverse`（撤销/冲正）、`POST /insurance/checklists/upload`（结算清单）、`GET /insurance/call-logs`、`POST /insurance/compensations/{id}/retry`
- 票据：`POST /invoices`（开具）、`POST /invoices/{id}/red-flip`（红冲）、`GET /invoices/{id}/file`
- 对账：`POST /daily-statements/close`、`GET /daily-statements`、`POST /monthly-statements/close`、`GET /recon/differences`、`POST /recon/hrp-sync`（触发补传）
- 控费：`GET/PUT /control-rules`（规则与阈值配置）、`GET /control-hits`（命中记录查询与核查销号）
- 对 M03/M04/M19 的取数 API：费用汇总、收入统计、清单数据（只读）

**MQ 事件（遵循 README 命名与信封约定，发布方登记 event_registry）**：
- 发布：`billing.fee.created`（费用生成）、`billing.fee.confirmed`（费用确认入账）、`billing.charge.guaranteed`（绿通/挂账放行回执，M03 订阅后置订单 CHARGED）、`billing.arrears.approved`（出院挂账审批通过，驱动 M04 出院放行 BLOCKED→READY）、`billing.settlement.completed`（结算完成，载荷含结算类型与医保拆分摘要）、`billing.refund.approved`（退费审批通过）、`billing.deposit.changed`（押金账户余额/预警变更）、`billing.invoice.issued`（票据开具/红冲完成）、`billing.daily-statement.closed`（日结完成）、`billing.charge-item-price.published`（调价生效广播）
- 订阅（按上游模块分组，事件名以上游模块 Spec 在 event_registry 登记为准）：
  - M03：开单事件（非药品计费行）、`outpatient.visit.registered`（门诊就诊登记，医保就诊登记联动）、`outpatient.order.executed`（门诊治疗执行，门诊治疗补费）、`outpatient.green-channel.opened/closed`（绿通开/关，挂账状态维护）
  - M04：`inpatient.visit.admitted`（入院事件，床位费/护理费等持续性费用起费锚点）、`inpatient.visit.registered`（入院办理，医保入院登记联动）、`inpatient.order.audited`（医嘱审核，住院药费即时计价与住院离散类费用锚点）、执行回签/转科/出院申请事件（计费与停费）
  - M06：`pharmacy.prescription.created`（处方生效，门诊药费计费行生成 PENDING）、发药/摆药事件（仅更新费用执行占用标记，不生成费用行）、退药事件（退费逆向业务联动）
  - M07：检验上机/标本签收事件（计费与执行占用）
  - M08：检查登记事件（计费）+ 检查完成事件 `imaging.exam.completed`（执行占用回写）
  - M10：手术事件（手术计费）
  - M12：`transfusion.specimen.received`（相容性检测费）、`transfusion.blood.issued`（血费/储血费）、`transfusion.inventory.changed`（动作=RETURN_BACK 时回库冲退）
  - M09：`emr.homepage.submitted`（病案首页提交，触发结算清单上传任务）
  - M18：`internet.prescription.returned`（线上处方退回，线上退费联动）
  - M17：`peis.checkin.registered/cancelled`（体检登记/取消，P6 启用预留）
  - M02：`patient.merged` / `patient.split`（成对订阅，刷新费用域归一映射/统计宽表，历史费用行不改写）
  - M01：`system.dict.published`（费别与收费类别字典刷新）、`system.param.changed`（系统参数本地缓存热刷新）

**WebSocket**：无自建主题。欠费预警、结算完成等业务提醒统一经 M01 通知中心投递（站内信通道 `/ws/notify/{userId}`），患者端消息经 M01 通知通道下发。

## 8. 集成点

- **M02**：一切费用/结算/押金记录引用 `patient_id`；退费原路退回可能涉及就诊卡余额账户（调用 M02 账户服务）；订阅 `patient.merged` 刷新费用域归一映射/统计宽表，历史费用行不改写，读侧经 M02 EMPI 解析服务收敛到主档（与 M02 红线"历史行不改写、读侧归一"契约一致）。
- **M03（门诊收费调用契约）**：M03 提供收费业务入口（窗口/诊间/自助机），资金与结算逻辑在本模块。契约：① M03 开单事件（非药品计费行）+ M06 处方生效事件 `pharmacy.prescription.created`（药品计费行）→ 本模块生成 PENDING 费用（事件契约）；② M03 收费界面调用本模块 预结算/结算/退费 API（同步 API 契约）；③ 本模块发布 `billing.settlement.completed` / `billing.refund.approved` / `billing.charge.guaranteed`（绿通/挂账放行回执，M03 订阅后置订单 CHARGED）→ M03 据此放行/拦截发药与退号退费联动；④ M03 发布 `outpatient.visit.registered`（医保就诊登记）、`outpatient.order.executed`（门诊治疗补费）、`outpatient.green-channel.opened/closed`（挂账态维护），本模块订阅。
- **M04（住院计费调用契约）**：① M04 入院事件（`inpatient.visit.admitted` 持续性费用起费锚点、`inpatient.visit.registered` 医保入院登记联动）、医嘱审核、执行回签、转科、出院申请事件 → 本模块计价与停费（事件契约）；② 本模块提供费用查询、押金缴存、一日清单、出院结算、挂账审批 API 供 M04 工作站调用；③ `billing.deposit.changed` → M04 触发欠费提醒与出院放行校验；出院未结清经 M04 发起挂账审批，审批通过后发布 `billing.arrears.approved`，M04 出院放行校验 BLOCKED→READY 由该事件驱动。
- **M06/M07/M08/M10**：执行域事件驱动计费（发药/摆药事件仅更新费用执行占用标记、不生成费用行）；退费前置校验调用其执行状态查询 API；药费退费与退药单据双向引用。
- **M12**：订阅输血计费事件（`transfusion.specimen.received` 相容性检测费、`transfusion.blood.issued` 血费/储血费、`transfusion.inventory.changed` 动作=RETURN_BACK 回库冲退）；退费前置校验调 `GET /api/v1/transfusion/irreversible-check`（不可逆输血项目退费拦截）。
- **M09**：结算清单诊疗段（诊断/手术编码、病案首页信息）取自 M09；订阅 `emr.homepage.submitted`（病案首页提交）触发清单上传任务；清单上传前质控不通过退回 M09 整改（P1 后联通，此前清单上传功能具备、依赖诊疗段接口位）。
- **M17（P6 预留）**：订阅 `peis.checkin.registered/cancelled` 维护体检登记关联；体检类费用走 settle_type/charge_source/visit_type 的"体检"预留枚举；体检来源的执行域事件（`lab.specimen.received`/`imaging.exam.registered`）仅回写执行占用、不生成费用行，以 pricing_rule 规则配置表达。
- **M01**：权限（收费/退费/审批/调价/日结权限点）、费别与收费类别字典、系统参数（免审阈值/欠费阈值/预交金额度/应急开关）——启动时加载本地缓存并订阅 `system.param.changed` 热刷新（不逐请求调用 M01）、通知中心、票据打印模板、审计。
- **M20**：医保与 HRP、财政票据平台、聚合支付的出站调用统一经 M20 通道发起，落 interface_call_log 并受监控告警（FU-M20-07）；本模块业务级 insurance_call_log 与其分工见方案 3.2；幂等消费复用 M20 构件。
- **外部系统**：属地医保信息平台（基线版接口）、财政电子票据平台、电子税务局数电票服务、HRP 财务系统、聚合支付渠道——协议适配经 M20 通道配置承载，本模块只定义业务载荷。

## 9. 非功能与安全

- 性能（本地处理）：门诊收费主链路（预结算→支付→正式结算）本地处理 P95 < 500ms（对齐总 Spec 门诊高峰 2000 人次/h 指标）；预计价 P95 < 200ms；住院日切批量任务在夜间低峰完成、分批提交可断点续跑；费用查询高频读走缓存+索引（visit_id + 状态）。
- 外部依赖 SLA：医保中心应答为外部依赖 SLA，不纳入本地性能口径；同步调用超时上限 30s，超时按"悬挂确认 → 冲正/补偿"流程处理（方案 3.2），不因中心高峰判定本地性能不达标。
- 可用性：收费/结算 7×24（总 Spec 核心可用性要求）；医保通道故障不阻断院内收费（自费先行应急模式）；日切任务失败可重跑且幂等。
- 一致性：费用与结算同事务落库；事件发布走 outbox（与 M20 治理约定一致）；金额勾稽三层校验（明细合计=结算总额、结算=支付明细合计、清单大类汇总=明细汇总）。
- 审计：收费、退费（含免审直退）、调价、日结、冲正、应急开关启闭全量审计（经 M01 审计切面）；免审直退单独标识供事后抽查。
- 权限：收费员/收费组长/财务/医保办/物价员分权；退费审批、调价发布、日结销号、冲正确认为独立权限点。
- 合规映射：医保基金监管条例第十五条（重复/超标准/分解/串换收费的规则化提醒，FU-M13-08）；物价管理（价格版本化+快照，调价不溯既往）；医保 22 项贯标（对照完整性硬校验）；等保三级（敏感字段——证件号/医保号/银行卡号脱敏展示与存储加密，审计留存）；个保法（费用数据对外提供经 M01 脱敏规则）。
- 数据留存：医保回执原文与票据文件留存 ≥ 10 年（基金结算争议与财务档案要求）；insurance_call_log 在线 180 天后归档；费用明细长期保留。

## 10. 测试要点

- 正常：门诊医保收费全链路（认证→预结算→支付→正式结算→票据→事件）；住院日切分解数量与频次规则一致、转科日费用按归属切分；出院结算多退少补金额正确；清单费用大类汇总与明细勾稽一致。
- 边界：调价生效瞬间的前后两笔费用各取新旧价格且历史不变；免审阈值边界（恰好等于阈值）；日结跨天不跨月边界；预交金余额恰好等于待付金额的出院结算；预结算成功但正式结算超时的悬挂确认；绿通关闭后挂账费用追缴入账（`GUARANTEED → SETTLED`）与坏账核销（`BAD_DEBT`）两条路径；出院挂账审批驳回后 M04 出院放行保持 BLOCKED、批准事件到达后转 READY。
- 异常：重复执行事件只产生一笔费用（幂等）；医保中心超时→冲正→补偿任务闭环；日对账差异生成并销号；票据红冲后原票不可再开；已发药项目直接退费被拦截；应急模式开启后的费用标记与补结算待办。
- 安全：前端伪造金额字段被服务端拒绝；越权退费/调价 403 且留审计；免审直退在审计中可检索抽查；医保回执原文不可篡改。

## 11. 自审记录

- [x] 无 TBD/TODO/占位符，13 项内容完整（文档头 + 12 节）
- [x] 覆盖 FU-M13-01~08 全部条目，无遗漏、无私增；优先级沿用总 Spec（05 的"P0 起步、P5 完整"对齐总 Spec 路线图分期，非优先级变更）
- [x] 内部一致：领域模型 ↔ 状态机 ↔ API ↔ 测试一一对应（fee_record/settlement/deposit_account/invoice/refund_request/arrears_approval/insurance_call_log 七个状态机均有对应 API、流程与测试项；control_rule、daily_statement 有查询/操作接口与测试场景）
- [x] 符合跨模块约定：schema=billing；金额 NUMERIC(18,2) 且服务端计算（D5）；事件命名 `<模块>.<实体>.<动作>` 且信封合规；患者关联 patient_id/visit_id；字典经 M01 引用不建副本；无跨模块读表（执行状态经 API 查询）
- [x] 依赖方向正确：依赖 M01/M02/M03/M04/M06/M07/M08/M09/M10/M20 的对外接口与事件；无反向依赖；被依赖清单明确
- [x] 方案推导 4 个关键点均有备选对比与依据，含任务要求的三个必选点（计价触发模式、医保接口架构、退费审批流），每个结论附调研来源
- [x] 无代码级实现（无类名/方法体/SQL DDL；表设计为"表-关键字段-约束"粒度；NUMERIC(18,2) 为 README 规定的字段规格说明）
- [x] 歧义消除：计费来源四分类（另登记"体检"P6 预留取值）、免审/审批分级规则、已结算红冲路径、M20 通道日志与本模块业务日志分工、门诊不设预交金账户等均已显式定义
- [x] 术语与总 Spec 一致（划价/计费/预交金/一日清单/结算清单/红冲/基线版接口）

## 12. 与总 Spec 的偏差

无。两处细化澄清（非偏差）：① FU-M13-04 押金管理聚焦住院预交金——依据 2025 年 3 月起国家全面取消门诊预交金政策（调研依据 12），门诊不再设预交金账户，总 Spec 条目原文"预交金"未限定门诊；② FU-M13-06 发票管理明确为"财政医疗收费电子票据为主 + 数电票应税场景"双轨——非营利医疗机构医疗收入使用财政监制票据是法定口径（调研依据 11），总 Spec"电子发票"条目按此口径细化。

### v1.1 统一审查修订记录（Round 1，依据 90-cross-review.md 裁决）

| ID | 修订点 | 裁决依据 |
| --- | --- | --- |
| B-2 / R1-02 | §8 M02 集成点改写为"订阅 `patient.merged` 刷新费用域归一映射/统计宽表，历史费用行不改写，读侧经 M02 EMPI 解析服务收敛到主档" | 90 号文档 B-2：原"迁移历史费用归属"表述违反 M02"历史行不改写、读侧归一"契约（BLOCKER） |
| M-4 / R2-04 / R3-04 | 药品计费权威时点统一：门诊药费订阅 M06 `pharmacy.prescription.created`（处方生效）生成 PENDING、住院药费按 M04 `inpatient.order.audited`（医嘱审核）即时计价；"药品发药"移出后计费类，发药/摆药事件仅更新费用执行占用标记；同步修订 §1 上游依赖、§3.1 计价触发（trigger_type 增 prescription_effective）、§5 主流程、§6 FU-M13-02、§7 订阅清单、§8 契约① | 90 号文档 M-4：处方链三方计费时点冲突的统一裁决 |
| M-5 | 新增发布 `billing.charge.guaranteed`（绿通/挂账放行回执）+ 订阅 `outpatient.green-channel.opened/closed`（挂账状态维护）；§5 fee_record 状态机补挂账分支（`CONFIRMED → GUARANTEED → SETTLED / BAD_DEBT`）与急诊绿通挂账放行流程；§8 M03 契约③补 charge.guaranteed（M03 订阅后置 CHARGED）；§10 补挂账追缴/坏账核销边界用例 | 90 号文档 M-5：急诊绿通发药放行链路缺事件 |
| M-10 | 新增挂账审批实体 arrears_approval（`DRAFT → PENDING_APPROVAL → APPROVED / REJECTED`）+ 审批 API（`POST /arrears-approvals`、`approve|reject`）+ 发布 `billing.arrears.approved`；§8 M04 契约③明确出院放行 BLOCKED→READY 由该事件驱动；§10 补审批驳回/批准边界用例 | 90 号文档 M-10：出院挂账审批在 M13 无实体/状态机/事件，M04 BLOCKED→READY 悬空 |
| M-13 | §7 订阅清单补 `emr.homepage.submitted`（病案首页提交，触发结算清单上传任务）；§6 FU-M13-05 与 §8 M09 集成点同步 | 90 号文档 M-13：原事件单边悬空，清单上传无触发 |
| M-16 | §7 订阅清单补 `outpatient.visit.registered`（医保就诊登记）、`inpatient.visit.registered`（入院办理）、`outpatient.order.executed`（门诊治疗补费）、`internet.prescription.returned`（线上退费联动） | 90 号文档 M-16：订阅清单缺 5 类上游指名事件（绿通事件并入 M-5 处理） |
| M-17 | §7 订阅清单补 M12 三事件：`transfusion.specimen.received`（相容性检测费）、`transfusion.blood.issued`（血费/储血费）、`transfusion.inventory.changed`（动作=RETURN_BACK 回库冲退）；§8 补"M12 退费前置校验调 `GET /api/v1/transfusion/irreversible-check`" | 90 号文档 M-17：缺 M12 输血计费事件订阅与退费前置校验调用方 |
| M-25 | §7 M02 订阅组以 `patient.merged` / `patient.split` 成对登记 | 90 号文档 M-25：patient.split 成对登记裁决（成对语义权威口径在 M02 §7/§12） |
| M-27 | settle_type / charge_source / fee_record.visit_type 登记"体检"预留枚举（P6 启用）；§7 补订阅 `peis.checkin.registered/cancelled`；体检来源执行域事件"仅回写执行占用、不生成费用行"以 pricing_rule 规则配置表达（trigger_type=executed 的规则排除 charge_source=体检类目），否决运行时豁免分支；体检计费唯一键来源单据=peis_checkin_no | 90 号文档 M-27：体检计费契约单向申报；备案终审 #8/#9（豁免改计价规则配置） |
| 终审 #3（A1-3/A2-3 收口） | 文档头下游被依赖补 M15（设备效益收入取数——日终调用按收费项目×执行科室×日期只读聚合统计 API，与 M19 同口径；单机效益的设备归集由 M15 asset_charge_mapping 自持，M13 不感知设备维度） | 90 号文档备案终审 #3：M15→M13 收入统计 API 扩展采纳（文档头补被依赖 M15） |
| R1-10 | 系统参数读取方式显式声明：启动加载本地缓存 + 订阅 `system.param.changed` 热刷新；§7 订阅补该事件 | 90 号文档 R1-10 |
| R1-11 | 床位费/护理费等持续性费用起费锚点裁决为订阅 `inpatient.visit.admitted`；§5 主流程与 §7 订阅同步 | 90 号文档 R1-11：与 M04 协同裁决（入院事件起费锚定） |
| R1-12 | §9 性能口径拆分两条：本地处理 P95 < 500ms 与"医保中心应答为外部依赖 SLA（同步调用超时上限 30s）"分列 | 90 号文档 R1-12：口径澄清（外部 SLA 不得计入本地性能考核） |
| R4-09 | §7 M08 订阅补全为"检查登记事件（计费）+ 检查完成事件 `imaging.exam.completed`（执行占用回写）" | 90 号文档 R4-09：M08 偏差 4 检查计费双时点采纳 |
| （事件计数） | 发布事件由 8 个增至 10 个（新增 `billing.charge.guaranteed`、`billing.arrears.approved`），与 §7 发布清单一致；两事件已列入 90 号文档 CF 契约冻结补充清单 | 90 号文档 M-5/M-10、第 3 节 MINOR 组 00-implementation-order 条目 |
