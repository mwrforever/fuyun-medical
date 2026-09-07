# M17 体检管理（PEIS）· 功能实现 Spec

| 属性 | 内容 |
| --- | --- |
| 模块编号 | M17 |
| Maven 模块 | `fuyun-peis`（schema：`peis`） |
| 版本 / 状态 | v1.1 / 统一审查修订（修订记录见 §12） |
| 上游依赖 | M01（认证/RBAC/审计/字典/通知/CA 电子签名/医师执业资质）、M02（患者主索引与身份解析、脱敏、健康档案回填）、M07（检验执行复用：受理/条码/上机/报告/危急值引擎）、M08（检查执行复用：预约/登记/影像/报告）、M13（套餐计价展开与体检结算/退费）、M14（一般检查设备遥测取数）、M20（事件总线治理、幂等构件、延迟队列） |
| 下游被依赖 | M09（患者全景的体检报告数据源）、M19（体检工作量/异常检出/随访质控取数）、M18（患者端体检报告查询渠道，P2 预留）、M02（体检结论回填健康档案） |
| 对应总 Spec | FU-M17-01 ~ FU-M17-04（全部 P2） |

---

## 1. 模块定位与边界

**职责**：本模块是健康体检业务主线（PEIS）的中枢，六项职责：① 体检套餐与项目组合——套餐定义（个人/团检/入职/职业健康）、性别年龄适配规则、套餐明细与可选加项维护；② 登记与预约——个人/团体登记（经 M02 建档解析身份）、预约改期、费清确认、加项/退项、导检单；③ 导检与分科结果——导检队列（对齐智能导检实践：按项目优先级与人流分配顺序）、一般科室结果录入（手工+设备自动回传双通道）、检验/检查结果聚合映射（复用 M07/M08 执行链的报告回流）；④ 总检与报告——总检工作台（阳性体征汇总、历次对比）、结构化总检结论、建议知识库、主检双签、报告版本化渲染发布与修正重发；⑤ 团检与单位报告——团检批次、名单导入、批次进度、单位汇总报告（仅统计口径）；⑥ 重要异常结果告知与随访——按专家共识 A/B 类分级、告知登记、复查闭环、随访率质控。核心资产：体检登记单状态机（体检全流程唯一权威）、分科结果与总检结论结构化数据、体检报告版本链、重要异常随访闭环记录。

**非职责**：`visit_id` 签发与门诊/住院诊疗业务（归 M03/M04，体检就诊不签发 visit，见方案 3.1）；检验执行本体（标本流转/上机/结果审核/危急值闭环引擎归 M07，本模块只消费其已发布报告）；检查执行本体（号源预约排程/检查登记/影像/报告审核归 M08，本模块只提交申请与消费其已发布报告）；资金收退付与结算（归 M13，本模块只做套餐定价展开与收费工作台入口，零金额字段）；病历文书与患者全景聚合（归 M09，本模块为体检报告数据源）；国家/院内字典维护（归 M01，本模块只引用 code）；体检设备的物联网接入与遥测采集（归 M14/M16，本模块经 M14 查询服务取数）；互联网线上渠道本体（归 M18，患者端报告查询经其渠道复用本模块 API）；职业健康体检的监管上报格式（预留接口位，本期不做）。

**模块红线**：
1. 体检者身份一律经 M02 解析服务建档/归一取得 `patient_id`，禁止以证件号、介质号直接挂接体检数据；体检业务不签发 visit_id（M03/M04 是唯一签发主体），体检单据以本模块 `checkin_no` 为主键锚点。
2. 检验/检查执行权威在 M07/M08：本模块不自建标本状态机、检查单状态机、检验结果本体库与影像存储；检验/检查项目结果一律以医技侧已发布报告为权威来源聚合映射，医技侧报告更正事件驱动体检侧同步刷新。
3. 本模块不落任何费用与结算数据（套餐定价参考价除外，计价权威在 M13）：套餐/加项的定价展开结果仅作为 M13 计费依据传递（计费唯一键=checkin_no），金额、折扣、结算、退费权威全部在 M13。
4. 已发布报告不可变：修正只能走"新版本重发"（原版本置被取代态、全程留痕并广播 `peis.report.corrected`），报告版本链永不删除；总检结论提交审核后修改必须经打回流程留痕。
5. 团检单位汇总报告只含脱敏统计结果，个人明细报告仅向本人（或本人授权人）开放；未经本人单独授权，禁止向单位输出任何个人明细（个保法敏感个人信息要求）。
6. 重要异常结果的告知与随访强制闭环留痕：随访任务禁止无结论静默关闭；禁止跨模块读表（医技上下文经事件与查询契约，患者上下文经 M02，费用上下文经 M13）。

## 2. 调研依据

1. **PEIS 全流程骨架**：健康体检管理系统贯穿"预约、登记、收费、分科检查到总检"全流程，每项检查结果由各科室录入，总检医生审核各分科结果后书写医生建议与体检综述，生成一份完整体检报告。（来源：http://qyiliao.com/product/detail/6719 ；http://chisc.net/doc/view/6291.html ；https://www.xyhis.com/chanpin/tijianguanli-cn.html ）
2. **总检工作台与随访一体化**：北京市体检中心主检报告子系统包含"汇总主检结论、随访记录、工作量统计、结论审核"功能，各科室检查结果自动汇总到主检处；成熟 PEIS 支持主检医生浏览各分科阳性体征、异常结果与科室小结，并可查看受检者历次体检结果对比、自动提取各子系统数据与诊断结论。（来源：https://www.bjtjzx.cn/tsfw/ts2.asp ；https://zhuanlan.zhihu.com/p/686529352 ）
3. **报告签署资质（法规）**：卫生部《健康体检管理暂行规定》第十六条要求医疗机构指定医师审核签署健康体检报告，签署医师应具有内科或外科**副主任医师以上**专业技术职务任职资格；四川省健康体检质控中心要求体检结论处具有**主检医生和审核医生双签名**、无分级审核或无双签名扣分。（来源：http://www.ithc.cn/sn/article/64188.html ；http://www.sctjzk.com/uploadfile/2022/0602/20220602095309711.pdf ）
4. **报告时限（地方标准）**：广州市地方标准 DB4401 要求在受检者所有体检项目（病理学和基因检测除外）全部检查完成后 **5 个工作日内**出具体检报告。（来源：https://scjgj.gz.gov.cn/attachment/7/7748/7748768/10073414.pdf ）
5. **重要异常结果闭环（国家质控与共识）**：国家卫健委《健康体检与管理专业医疗质量控制指标（2023 年版）》将"高危异常结果及时通知"列为质控指标（反映机构发现和处置高危异常结果的应急能力）；《健康体检重要异常结果管理专家共识（试行版）》将重要异常结果分级管理（A 类=需紧急处置/立即就医，B 类=需动态观察复查），要求建立重要异常结果通知随访制度，并建议以信息化手段实现闭环管理；湖南省质控中心将"重要异常结果随访率=完成随访人数/同期检出重要异常总人数"列为质控指标。（来源：https://www.nhc.gov.cn/yzygj/c100068/202311/610e3cc82270478f84eeb3c5ba788e23/files/1733999783133_27512.pdf ；https://zhuanlan.zhihu.com/p/587320447 ；https://guide.medlive.cn/guideline/38808 ；https://hnjkqcc.xy3yy.com/uploadfile/2024/1115/20241115093437144.pdf ）
6. **与 HIS/LIS/PACS 的集成方式（生产实践）**：体检系统在后台运行 LIS 接口模块监控检验结果回传数据，一旦发现体检中心的结果数据即自动提取进入体检报告；典型集成方案为"体检系统将体检人员基本信息、申请项目信息、费用信息传递到 HIS/LIS/PACS，检验/检查完成后结果双向回传自动汇总"，避免大量手工录入。（来源：https://zhuanlan.zhihu.com/p/24175438 ；http://www.scapesoft.cn/down/YKPEIS.pdf ；http://www.forhis.com/Products_detail/12.html ）
7. **团检业务形态**：医院采购需求将"院内套餐、项目、号源、团检名单"作为字典库统一管理并供第三方预约平台同步订单；团检员工可自选加项或调整项目，超额部分个人现场补缴（套餐协议价由单位承担）。（来源：https://dr0759.cn/zxy/portal/article.action?articleId=2c9f7a8189b08c02018dd52dbbe42de4&columnId=402881096699e32e016699e707f3003c ；https://www.sysu5.cn/medical-service/health-check ）
8. **导检与全流程数字化**：智能导检系统根据体检项目优先级和人流量智能分配体检顺序，减少排队等待；全流程健康体检管理系统实践表明标准化数据管理与过程监督可显著降低套餐开单耗时与缴费耗时。（来源：https://www.bjcyh.com.cn:444/Html/Departments/Main/Detail_328.html ；https://cs.china-cmd.org/zgylsb/CN/10.3969/j.issn.1674-1633.2024.09.010 ）

## 3. 方案推导（关键设计点选型）

### 3.1 体检者身份与就诊模型：独立体检档案 vs 复用 EMPI+签发体检 visit vs 复用 EMPI+体检独立单据号

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 独立体检档案 | 体检中心自建体检者库，不进 EMPI | 传统独立体检机构做法；但在医院内造成"同一人两套档案"，历次体检无法与门诊/住院诊疗数据贯通，违背总 Spec"一切临床数据引用 patient_id"约定与 M09 患者全景目标 |
| 复用 EMPI + 签发体检 visit | 体检登记视为一次就诊，由 M02 扩展类型码（如 `P`）签发 visit_id | visit 语义是"临床诊疗闭环"（医嘱/计费/病历首页），体检无此闭环；M02 Spec 方案 3.4 明确 visit 签发主体唯一（M03 的 `O`/M04 的 `I`），新增类型码需改动已定稿上游契约，且体检费用/申请单挂 visit_id 会污染门诊统计口径（门诊量/门诊收入指标） |
| **复用 EMPI + 体检独立单据号（选定）** | 体检登记必须经 M02 解析/建档取得 `patient_id`；体检业务对象以本模块自有业务号 `checkin_no`（结构 `T + yyyyMMdd + 5 位当日流水`，14 位定长，同构于 visit_id 结构风格但类型码 `T` 不进入 visit 域）为锚点，关联 `patient_id`；体检历史报告查询按 patient_id 聚合 | 身份全院唯一、历次体检与诊疗数据可全景贯通（对齐调研依据 2"历次结果对比"）；不触碰 M02 已定稿的 visit 签发契约（红线 1）；体检工作量/收入天然与门诊口径分离；代价是 M09 患者全景需同时识别 visit_id 与 checkin_no 两类锚点（M09 本就以多模块数据组装，成本可控） |

**结论**：复用 M02 EMPI 建 `patient_id`；体检登记**不签发** O/I 类型 visit_id，以 `peis_checkin_no` 为自身主键并关联 patient_id。依据：体检无临床诊疗闭环、不影响门诊/住院 visit 语义、避免污染门诊统计口径。此结论写入第 12 节偏差清单供统一审查裁决。

### 3.2 体检检验/检查执行落点：体检内嵌简化流程 vs 复用 M07/M08 通道 vs 双轨并行

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 体检内嵌简化流程 | PEIS 自建一套精简检验/检查流程（自带条码、结果直接录入、不出医技级报告） | 部分独立体检机构做法；院内场景下与 M07/M08 重复建设仪器双向通讯、危急值引擎、报告审核，违反总 Spec D4 模块边界与"仪器接入复用"决策（总 Spec FU-M17-02 已注明"复用仪器接入"）；简化流程危急值能力缺失，体检高危异常（如检验危急值）无法进入全院闭环 |
| **复用 M07/M08 通道（选定）** | 体检登记确认后，本模块按登记项目明细中的检验项目发布事件驱动 **M07 生成 lab_apply**（进入同一条码-采集-上机-审核-报告链），检查项目驱动 **M08 生成检查申请**（进入预约-登记-影像-报告链）；本模块订阅 `lab.report.published` / `imaging.report.published`，将已发布报告按体检项目映射聚合为"分科结果就绪"，并展示报告入口；医技侧危急值仍由 M07 引擎闭环（体检场景通知对象=体检科总检医师）；M07/M08 Spec 第 8 节均已预留"M17 适配位"（申请来源类型） | 一套仪器接入、一条危急值闭环、一份医技级权威报告（调研依据 6 生产实践即此形态）；体检侧零重复建设；体检报告聚合医技报告的已发布版本，与 M07/M08"已发布报告不可变+更正事件"红线天然对齐；代价是体检者检验/检查体验依赖医技侧排程效率，通过预约协同缓解 |
| 双轨并行 | 院内患者走 M07/M08、体检者走内嵌简化流程 | 同院两套医技流程并存，检验结果不可比（质控体系不同）、总检医师需在两个系统间切换、危急值双标准，运维与合规成本最高 |

**调用契约（写入 M07/M08 适配位启用设计）**：① 受理——本模块发布 `peis.checkin.registered`（载荷含 checkin_no、patient_id、项目清单[每项携带 peis_item 与映射的 lab/exam 项目 code、加项标记]），M07/M08 分别订阅其 `lab`/`exam` 子键，按项目生成本模块检验申请/检查单（source_type=PEIS、source_ref=checkin_no）；② 回流——M07/M08 发布的 `*.report.published` / `*.report.corrected` 事件由本模块订阅，按 source_ref 反查登记项目明细，标记对应 peis 项目"结果就绪"并挂接报告引用；③ 退检联动——`lab.specimen.rejected`（重采提示）、`imaging.exam.cancelled`（退检）驱动本模块登记项目明细转异常态并提示体检科处置；④ 分科完成判定——全部登记项目均"就绪"或"弃检豁免"后自动流转（见第 5 节）。备选"体检内嵌简化流程"作为否决项记录如上。

### 3.3 报告生成模型：全文自由文本 vs 完全模板无结构 vs 结构化结论+版本化渲染

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 全文自由文本 | 总检医师手写一段综述全文直接打印 | 灵活但数据不可统计（异常检出率/慢病分布/单位报告全部做不了）、历次对比只能靠人工翻阅、随访无法挂接结构化异常条目；与"主检浏览各分科阳性结果+历次对比"的成熟形态不符（调研依据 2） |
| 完全模板无结构 | 固定模板填充，不允许总检自由表述 | 体检结论个体差异大，纯模板无法覆盖复杂多病共存场景，实际不可用 |
| **结构化结论 + 版本化渲染（选定）** | 三层模型：① 分科结果结构化（项目结果行：值/单位/参考范围快照/异常标记 + 科室小结）；② 总检结论结构化（peis_summary + 结论条目：结论术语 code/ICD-10 引用/关联项目/分级建议文本，重要异常 A/B 标记落在条目上）；③ 报告版本化渲染（结论+分科数据+医技报告引用按模板渲染 PDF 落对象存储，版本链管理；修正=新版本重发、原版本置 SUPERSEDED、广播 corrected 事件）。建议知识库（结论术语→建议文本→复查建议周期）供总检时选用，总检医师可改写后保存个人模板 | 结构化条目直接支撑：重要异常分级随访（条目级 severity）、单位统计报告（按结论术语聚合）、历次对比（同术语跨期对比）、建议知识库沉淀（调研依据 1、2 的总检综述+保健处方形态）；版本链与 M07/M08 报告更正模式（红线 2/4）全院一致；渲染异步化保证发布动作轻量 |

**结论**：结构化结论 + 版本化渲染。报告时限以延迟队列监控（默认 5 个工作日，参数化，来源 DB4401 口径，见偏差 5）；发布前置校验主检/审核双签齐备且签署医师资质（副主任医师以上）经 M01 执业资质校验（调研依据 3）。

### 3.4 体检项目与三目录映射：套餐直挂收费项目 vs 套餐直挂医技项目 vs 独立体检项目字典三向映射

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 套餐直挂收费项目（M13 charge_item） | 套餐明细直接引用收费项目 | 定价简单；但收费项目无法表达"检验项目→标本/条码"与"检查项目→设备/预约"的执行语义，执行分发时需二次猜测拆分，映射关系隐式不可审计 |
| 套餐直挂医技项目（M07/M08 字典） | 套餐明细直接引用检验/检查项目 | 执行直达；但一般检查（内科/外科/眼科等临床体格检查）在 M07/M08 无对应项目，且收费对照缺失，仍需补第三层映射 |
| **独立体检项目字典三向映射（选定）** | peis_item 为体检业务项目权威字典（含一般检查/问卷类），每个项目显式维护三向映射：`charge_item_ref`（M13 收费项目，计价依据）、`lab_panel_ref`（M07 检验组合，仅检验类）、`exam_item_ref`（M08 检查项目，仅检查类）；套餐（peis_package）由 peis_item 组合而成 | 一处定义、三域一致：计价、执行、结果聚合都由显式映射驱动（对齐调研依据 6"基本信息/申请项目/费用信息传出、结果回传"的双向集成形态）；一般检查类项目仅挂 charge_item_ref，结果由本模块录入/设备回传；映射完整性在建项时强校验，杜绝登记后才发现无法计价/无法执行的脏数据 |

**结论**：独立体检项目字典 + 三向映射。映射规则：LAB 类必须同时具备 lab_panel_ref 与 charge_item_ref；EXAM 类必须同时具备 exam_item_ref 与 charge_item_ref；GENERAL/QUESTION 类仅须 charge_item_ref。

## 4. 领域模型

表设计统一遵循 README 第 3 节约定：雪花 BIGINT 主键、统一审计字段（created_by/created_at/updated_by/updated_at/deleted）、TIMESTAMPTZ 服务器时间、逻辑删、金额 NUMERIC(18,2)（本模块唯一金额字段为套餐定价参考价，权威在 M13，见红线 3）。

| 实体 | 关键字段 | 说明 |
| --- | --- | --- |
| peis_item 体检项目字典 | item_code、item_name、dept_id(执行科室，引用 M01 组织)、item_type(LAB 检验/EXAM 检查/GENERAL 一般检查/QUESTION 问卷)、charge_item_ref(M13 收费项目 code)、lab_panel_ref(M07 检验组合 code)、exam_item_ref(M08 检查项目 code)、result_form(NUMERIC 数值/TEXT 文本/CHOICE 定性选择)、default_unit、default_ref_range(默认参考范围，按性别年龄可覆盖)、sex_applicability、status | 三向映射约束见方案 3.4；登记时快照到登记明细，字典后续变更不影响已登记单 |
| peis_package 体检套餐 | package_code、package_name、package_type(PERSONAL 个人/GROUP 团检/ENTRY 入职/OCCUPATIONAL 职业健康)、sex_applicability、age_min/age_max、ref_price(NUMERIC(18,2)，参考价，实际计价以 M13 收费项目价格展开为准)、version、status(ACTIVE/DISABLED) | 套餐改版升 version，已登记单引用旧版本快照不受影响；性别年龄适配为登记校验规则（如妇科项目仅限女性） |
| peis_package_item 套餐明细 | package_id+version、item_id、is_default(默认含/可选加项候选)、sex_override(项目级性别覆盖)、sort_no | (package_id, version, item_id) 唯一；可选项目在登记时勾选 |
| peis_group_exam 团检批次 | group_no(`G+yyyyMMdd+5位流水`)、org_name、org_license(统一社会信用代码)、contact_name/mobile、discount_agreement(协议折扣描述文本，折扣计算在 M13)、expected_count、start_date/end_date、status(PLANNED/OPEN/CLOSED/SETTLED) | 一个单位一个批次；批次下按分组（职级/性别）分配不同套餐 |
| peis_group_roster 团检名单 | group_no、roster_name、roster_id_no(加密存)、roster_mobile、patient_id(导入时经 M02 解析/建档回填)、group_division(批次内分组)、assigned_package_id、roster_status(PENDING/REGISTERED/CHECKED_IN/REPORTED/EXPIRED) | (group_no, roster_id_no_hash) 唯一；批量导入异步处理并回执；未匹配到既有档案时批量触发 M02 建档 |
| peis_checkin 体检登记单 | checkin_no(`T+yyyyMMdd+5位流水`)、patient_id、checkin_type(PERSONAL/GROUP)、group_no(可空)、package_snapshot(套餐与项目清单快照)、appointment_at、fee_cleared_flag(费清标记，由 billing.settlement.completed 驱动)、confirmed_at、chief_physician_id(总检医师)、status、summary_due_at(报告时限到期点=分科完成时间+参数化工作日)、debt_note(弃检/退项说明) | 体检全流程唯一锚点；(patient_id, checkin_no) 天然唯一；同一患者同日重复登记由业务校验拦截 |
| peis_checkin_item 登记项目明细 | checkin_no、item_id、item_name_snapshot、source(PACKAGE 套餐内/ADDED 加项/REMOVED 退项)、exec_channel(LAB/EXAM/LOCAL)、lab_apply_ref(M07 申请/条码引用)、imaging_no_ref(M08 检查单引用)、report_ref(医技已发布报告引用)、result_ready_flag、status(PENDING 待检/EXECUTING 执行中/READY 结果就绪/CONFIRMED 已确认/REMOVED 已退项/WAIVED 弃检豁免/ABNORMAL 执行异常) | (checkin_no, item_id) 唯一；加项=新增 ADDED 行并触发补费，退项=置 REMOVED 并触发退费（已就绪项目禁止退项）；医技报告更正事件回流时刷新 result_ready_flag 与 report_ref |
| peis_department_result 分科结果（科室小结层） | checkin_no+dept_id 唯一、summary_text(科室小结)、result_physician_id(小结医师)、signed_at(签名时间，M01 CA)、status(PENDING/INPUTTING/SUMMARIZED) | 一单一科室一条小结；全部小结 SUMMARIZED 且项目明细就绪是分科完成的前置条件之一 |
| peis_result_item 分科结果（项目结果行） | result_id(挂 peis_department_result)、checkin_item 引用、result_value(数值/文本/定性)、unit、ref_range_snapshot(参考范围快照)、abnormal_flag(NORMAL/ABNORMAL_H 偏高/ABNORMAL_L 偏低/ABNORMAL)、input_channel(MANUAL 手工/DEVICE 设备回传/EXTRACT 医技报告聚合)、operator_id、input_at、modify_trace(修改留痕链) | 数值型结果自动按参考范围快照判定异常标记；设备回传经 M14 遥测查询取数后需人工核对确认（双通道校验，对齐 M16 体征采集先例）；医技聚合结果本体在 M07/M08，此处仅存映射引用与展示要素 |
| peis_summary 总检结论 | checkin_no 唯一、positive_digest(阳性结果汇总)、health_prescription(保健处方/健康指导)、chief_physician_id、audit_physician_id(审核医师)、chief_signed_at、audit_signed_at(双签时间)、advice_kb_refs(建议知识库引用)、status(DRAFT 草稿/PENDING_AUDIT 待审核/AUDITED 已审核/RETURNED 打回) | 审核通过才可渲染报告；打回必填理由并回 DRAFT，留迁移日志 |
| peis_summary_finding 总检结论条目 | summary_id、finding_code(结论术语 code，模块专业字典挂 M01 编码体系)、icd_code(引用 M01 ICD-10 字典)、related_item_ids(关联体检项目)、advice_text(建议文本)、severity(NORMAL/ABNORMAL 一般异常/IMPORTANT_A 重要异常 A 类/IMPORTANT_B 重要异常 B 类)、followup_required | 重要异常分级对齐专家共识（调研依据 5）；A/B 类条目驱动随访任务生成；条目是单位统计报告与历次对比的聚合维度 |
| peis_report 体检报告版本 | report_no(`R+yyyyMMdd+5位流水`)、checkin_no、report_type(PERSONAL 个人/GROUP_SUMMARY 单位汇总)、version、render_file_ref(PDF 对象存储引用)、report_content_digest(结构化内容快照引用)、published_by/published_at、status(GENERATED 已生成/PUBLISHED 已发布/SUPERSEDED 被取代/RETRACTED 已撤回) | 同一 checkin_no 下版本递增；修正重发=新版本 PUBLISHED + 旧版本置 SUPERSEDED + 广播 `peis.report.corrected`；GROUP_SUMMARY 类型挂 group_no 维度、仅含统计口径 |
| peis_advice_kb 建议知识库 | kb_code、finding_code(关联结论术语)、advice_template(建议文本模板)、review_cycle(建议复查周期)、applicable_sex/age_range、enabled、source(系统内置/医师个人沉淀) | 总检时按结论术语推荐建议文本，医师可改写；个人改写模板可存为私有条目 |
| peis_followup 随访任务 | followup_no(`F+yyyyMMdd+5位流水`)、patient_id、checkin_no、finding_id(关联结论条目)、followup_level(A/B)、notify_channel(电话/短信/站内信，经 M01 通知中心)、notified_at、notify_evidence(告知留痕：外呼记录/回执引用)、plan_date(计划随访日期)、followup_record(随访记录)、recheck_ref(复查结果引用：新 checkin_no 或医技单据)、closed_type(FOLLOWED_UP 已随访闭环/LOST_CONTACT 失访)、owner_id(责任随访人)、status(PENDING/IN_PROGRESS/CLOSED) | A 类告知时限默认 24 小时（参数化）；失访需两次以上不同渠道尝试留痕方可登记；闭环广播 `peis.followup.closed` |

关系要点：peis_package 1:N peis_package_item（N:1 peis_item）；peis_group_exam 1:N peis_group_roster / peis_checkin；peis_checkin 1:N peis_checkin_item / peis_report，1:1 peis_summary，1:N peis_department_result（1 科室 1 条）；peis_department_result 1:N peis_result_item；peis_summary 1:N peis_summary_finding；peis_summary_finding 1:N peis_followup（A/B 类条目）；peis_followup N:1 peis_checkin（复查可关联新 checkin_no）。

## 5. 状态机与业务流程

- **peis_checkin 体检登记单**：`DRAFT(登记单已创建，套餐未选定/未提交) → REGISTERED(已提交/已预约，待费清；触发条件=套餐选定+项目清单快照落库) → IN_PROGRESS(检查中，费清确认后放行并触发项目分发) → DEPT_COMPLETED(分科完成) → SUMMARIZING(总检中) → READY_TO_PUBLISH(报告待发，双签通过渲染完成) → PUBLISHED(已发布，终态基点)`。分支：① **加项**——`IN_PROGRESS / DEPT_COMPLETED` 状态下追加登记项目明细（ADDED）→ 触发 M13 补费 → 费清后新项目进入执行分发，状态若在 DEPT_COMPLETED 则回退 `IN_PROGRESS`；② **退项**——仅 `PENDING`（未执行）项目可退（置 REMOVED → 触发 M13 退费），已就绪项目禁止退项；③ **弃检**——个别项目因故不做置 WAIVED（必填原因，参与分科完成判定时豁免）；④ **退登/弃检整单**——`DRAFT / REGISTERED / IN_PROGRESS → CANCELLED`（未执行项目全额退费、已执行项目按 M13 规则处置）；⑤ `PUBLISHED` 后报告问题走报告修正重发（版本链处理，登记单状态不变）。全部迁移经状态机服务校验并留迁移日志。
- **peis_checkin_item 登记项目明细**：`PENDING → EXECUTING(已分发至 M07/M08 或分科开检) → READY(结果就绪：LOCAL 录入确认/医技报告已发布聚合) → CONFIRMED(总检确认引用)`；分支 `PENDING → REMOVED(退项)/WAIVED(弃检)`；`EXECUTING → ABNORMAL(执行异常：标本不合格/退检，待体检科处置) → EXECUTING(重新分发)/WAIVED(转弃检)`。
- **peis_department_result**：`PENDING → INPUTTING(开检录入中) → SUMMARIZED(小结签名完成)`；签名后修改必须重签留痕。
- **peis_summary**：`DRAFT → PENDING_AUDIT(提交审核) → AUDITED(审核通过，触发渲染) / RETURNED(打回→DRAFT，必填理由)`；AUDITED 后不可修改，问题经报告修正流程整体重走（新 summary 版本随新报告版本重签）。
- **peis_report**：`GENERATED(渲染完成) → PUBLISHED(发布) → SUPERSEDED(被新版本取代) / RETRACTED(撤回，仅未告知受检者前可撤)`。
- **peis_followup**：`PENDING(总检审核生成) → IN_PROGRESS(已完成告知，等待复查/就医反馈) → CLOSED(闭环：FOLLOWED_UP 含复查结果引用 / LOST_CONTACT 失访)`；PENDING 超过告知时限（A 类 24h）经延迟队列提醒并升级提示体检科负责人；IN_PROGRESS 超过计划随访日期经 `delay.peis-followup-due` 提醒；两次不同渠道尝试无应答方可登记 LOST_CONTACT。
- **peis_group_exam**：`PLANNED(批次创建) → OPEN(名单导入可登记) → CLOSED(登记截止/批次完成) → SETTLED(单位汇总报告发布且对公结算完成，经 M13 确认)`。

主流程时序：

1. **登记与费清（个人体检）**：受检者到检/线上预约 → 经 M02 解析服务识别身份（无档建档、FROZEN/MERGED 拦截）→ 选择套餐+可选加项（性别年龄适配校验）→ 套餐展开定价传递 M13（计价依据：peis_item 三向映射的收费项目×数量，计费唯一键=checkin_no）→ 体检收费工作台调 M13 结算 API 完成缴费 → 订阅 `billing.settlement.completed` 置费清标记 → 登记确认（状态机迁移 IN_PROGRESS）→ 发布 `peis.checkin.registered` → 打印导检单；团检批次受检者同流程但费用按批次协议走对公/名单核销，加项超额部分个人补缴（调研依据 7）。
2. **执行与结果回流**：`peis.checkin.registered` 被 M07（lab 子键）/M08（exam 子键）消费生成检验申请/检查单 → 检验走条码-采集-上机-审核-发布链、检查走预约-登记-影像-报告链（全在医技模块）→ 本模块订阅 `lab.report.published` / `imaging.report.published` 按 source_ref 聚合标记 READY；一般检查科室（GENERAL）本地录入或经 M14 遥测查询服务回传设备测量值（核对确认后入库）→ 分科小结签名（SUMMARIZED）→ 全部项目 READY/CONFIRMED/WAIVED 且小结齐备 → 自动流转 DEPT_COMPLETED。
3. **总检与报告发布**：DEPT_COMPLETED 自动进入总检工作列表 → 总检医师审阅（阳性结果汇总、历次对比按 patient_id 取历史报告）→ 结构化结论条目+建议知识库推荐+保健处方 → 提交审核 → 审核医师（副主任医师以上资质经 M01 校验）双签 → 渲染 PDF（GENERATED）→ 发布（PUBLISHED，`peis.report.published`）→ M01 通知中心告知受检者；severity=IMPORTANT_A/B 的结论条目自动生成随访任务（PENDING）。报告时限（分科完成+5 个工作日）由 `delay.peis-report-overdue` 监控超时提醒。
4. **团检批次**：创建批次→ 名单批量导入（异步经 M02 解析/建档回执）→ 批量预约 → 各受检者走个人流程 → 批次全部报告 PUBLISHED（或明确弃检）→ 生成单位汇总报告（GROUP_SUMMARY：按结论术语/异常分级统计，不含个人明细）→ 批次对公结算（M13）→ SETTLED。
5. **随访闭环**：A 类——24h 内电话/短信告知并留痕（IN_PROGRESS）→ 引导就医/复查 → 复查结果回填（关联新 checkin_no 或医技单据引用）→ CLOSED(FOLLOWED_UP)；B 类——按建议复查周期计划随访 → 到期提醒 → 同闭环路径；无应答两次以上 → LOST_CONTACT 闭环留痕 → 发布 `peis.followup.closed`（M19 随访率取数依据）。

## 6. 功能实现设计（逐 FU）

| FU | 实现设计要点 |
| --- | --- |
| FU-M17-01 体检套餐与登记（P2） | 套餐管理：套餐/项目组合/性别年龄适配/版本化（方案 3.4 三向映射约束建项强校验）；登记：个人（现场/预约）与团检（名单导入）双入口，统一经 M02 解析建档（FROZEN/MERGED 拦截）、同日重复登记校验；登记单创建→套餐展开快照→M13 定价结算→费清→确认放行（状态机）；加项（补费后生效，DEPT_COMPLETED 自动回退 IN_PROGRESS）/退项（未执行才可退，联动退费）/弃检（留痕豁免）；导检单打印与导检队列（按项目优先级与人流建议顺序，调研依据 8）；预约改期经导检队列调整，不占 M08 号源（仅检查类项目由 M08 自行排程） |
| FU-M17-02 分科结果录入（P2） | 三通道：① 手工录入（GENERAL 科室：结果行录入、数值型自动判异常标记、修改留痕重签）；② 设备自动回传（总 Spec"复用仪器接入"：一般检查设备——身高体重/血压/肺功能等——按 M16 体征一体机接入模式经 M14 遥测链路上报，本模块经 M14 查询服务按 patient_id+时间窗取数，人工核对确认后回填，双通道校验防错挂）；③ 医技报告聚合（LAB/EXAM 项目按方案 3.2 契约消费 M07/M08 已发布报告，展示报告入口，本体不落库）；分科小结签名（M01 CA）；分科完成自动判定（项目明细 READY/CONFIRMED/WAIVED + 小结齐备）；执行异常（标本不合格/退检）事件驱动提示体检科处置 |
| FU-M17-03 总检与报告（P2） | 总检工作台：待总检列表、阳性结果汇总视图、历次体检对比（按 patient_id 聚合历史 checkin 与报告）；结构化结论条目（结论术语/ICD-10 引用/关联项目/A-B-一般分级）+ 建议知识库推荐（可改写沉淀个人模板）+ 保健处方；提交-审核-打回留痕；发布前置校验：双签齐备+审核医师副主任医师以上资质（M01 执业信息校验，调研依据 3）+ 分科完成；渲染 PDF 版本链落对象存储；发布广播 `peis.report.published` 并经 M01 通知中心告知受检者；修正重发：新版本审核签发、旧版本 SUPERSEDED、广播 `peis.report.corrected`、已打印纸质报告提示作废重打；报告时限 5 个工作日参数化监控（调研依据 4） |
| FU-M17-04 团检与随访（P2） | 团检：批次管理（单位信息/协议折扣描述/批次状态）、名单批量导入（异步+回执+失败明细）、分组套餐分配、批次进度看板（已登记/已到检/已报告统计）、全员报告完成后生成单位汇总报告（GROUP_SUMMARY 仅统计口径：异常检出率/结论术语分布/健康建议，红线 5 个保法约束，调研依据 7）、批次对公结算联动 M13；随访：A/B 类重要异常分级（调研依据 5 共识）、总检审核自动生成随访任务、告知登记（渠道/时间/证据留痕，A 类 24h 时限）、随访记录与复查结果回填（关联新 checkin/医技单据）、失访登记（两次不同渠道尝试留痕前置）、闭环广播 `peis.followup.closed`；随访率/告知及时率统计供 M19 质控取数；重要异常/慢病结论经 M02 健康档案 API 建议性回填（FU-M02-05 health_item，操作确认后写入） |

## 7. 对外接口

**REST（`/api/v1/peis/` 前缀）**：
- 套餐与项目：`GET/POST/PUT /peis-items`（体检项目字典与三向映射维护）、`GET/POST/PUT /packages`、`POST /packages/{id}/items`（套餐明细）、`GET /packages/{id}/price-preview`（展开计价预览，调 M13 预计价）
- 团检：`POST /group-exams`、`GET /group-exams/{no}`、`POST /group-exams/{no}/roster-import`（名单批量导入，异步回执）、`GET /group-exams/{no}/progress`（批次进度）、`POST /group-exams/{no}/summary-report`（生成单位汇总报告）、`POST /group-exams/{no}/settle`（批次对公结算：登记 M13 对公结算引用后批次迁移 SETTLED，R5-12）、`POST /group-exams/{no}/close`
- 登记与执行：`POST /checkins`（个人/团检登记）、`POST /checkins/{no}/appointment`（预约/改期）、`POST /checkins/{no}/confirm`（登记确认，前置校验费清）、`POST /checkins/{no}/add-items`（加项）、`POST /checkins/{no}/remove-items`（退项）、`POST /checkins/{no}/waive-items`（弃检）、`POST /checkins/{no}/cancel`（退登）、`GET /guide-queues?deptId=`（导检队列）
- 分科结果：`POST /department-results/{checkinNo}/{deptId}/items`（结果行录入/设备回传核对确认）、`POST /department-results/{checkinNo}/{deptId}/summary`（小结+签名）、`GET /department-results/{checkinNo}`（分科数据视图）
- 总检与报告：`GET /summary-worklist?deptId=&date=`、`POST /summaries`（总检结论提交）、`POST /summaries/{checkinNo}/submit-audit`、`POST /summaries/{checkinNo}/audit|return`（审核/打回）、`POST /reports/{checkinNo}/publish`、`POST /reports/{no}/correct`（修正重发）、`POST /reports/{id}/retract`、`GET /reports/{no}/file`（PDF）、`GET /reports?patientId=`（历次对比取数）、`GET/POST/PUT /advice-kb`（建议知识库）
- 随访：`GET /followups?status=&level=`、`POST /followups/{no}/notify`（告知登记）、`POST /followups/{no}/records`（随访记录）、`POST /followups/{no}/close`（闭环，含复查引用/失访结论）
- 对 M13：`GET /pricing-basis?checkinNo=`（计价依据：登记项目×收费项目映射×数量+加退项增量，供套餐展开计费与退费核对）
- 对 M19：`GET /stats/workload`、`/stats/abnormal-rate`、`/stats/followup-rate`、`/stats/notify-timeliness`、`/stats/package-usage`（只读统计）
- 患者端取数：`GET /patient/reports`（经 M18/公众号渠道调用，仅本人已发布报告，脱敏输出）
- 对 M09：`GET /reports?patientId=&status=PUBLISHED`（患者全景体检报告数据源）

**MQ 事件（发布，经 `fy.topic`，信封遵循 M20 治理约定，全部登记 event_registry）**：
- `peis.checkin.registered`（登记确认：checkin_no/patient_id/项目清单[含 lab_panel_ref、exam_item_ref 映射与加项标记]，**M07/M08 受理分发的驱动事件**，发布时点=REGISTERED→IN_PROGRESS 迁移（登记确认）时发布——非 REGISTERED 待费清状态时点，R5-13，载荷含业务域标记 biz_domain=PEIS 供 M13 对账区分）
- `peis.checkin.cancelled`（退登/整单弃检：未执行项目清单，M13 退费联动依据）
- `peis.report.published`（报告发布：report_no/checkin_no/report_type[PERSONAL/GROUP_SUMMARY]/重要异常标记，M09 引用、M01 患者通知、M18 渠道放行依据）
- `peis.report.corrected`（报告修正重发：新版本 report_no/被取代版本号，订阅方刷新与"已更正"提示依据，与 lab/imaging.report.corrected 对称）
- `peis.followup.closed`（随访闭环：followup_no/followup_level/closed_type/闭环结论，M19 随访率与告知及时率统计依据）

**MQ 事件（订阅，全部经 M20 幂等构件消费）**：
- `lab.report.published` / `lab.report.corrected`（M07 检验报告发布/更正 → 按 source_ref 聚合标记项目就绪、更正同步刷新）
- `lab.specimen.rejected`（标本不合格退回 → 对应登记项目明细转 ABNORMAL 提示处置）
- `imaging.report.published` / `imaging.report.corrected`（M08 检查报告发布/更正 → 同上聚合与刷新）
- `imaging.exam.cancelled`（检查退检 → 登记项目明细转 ABNORMAL 提示处置）
- `billing.settlement.completed`（体检结算完成 → 置费清标记，放行登记确认；载荷 settle_type=PEIS 匹配）
- `billing.refund.approved`（退费审批通过 → 退项/退登的退费完成确认回写）
- `patient.merged` / `patient.split`、`patient.frozen` / `patient.unfrozen`（两组均按 M02 成对语义成对登记，90 号文档 M-25 裁决）/ `patient.identifier.changed`（M02：体检单据与报告归属映射刷新与拆分恢复、冻结拦截与解除）
- `system.dict.published` / `system.org.changed` / `system.param.changed`（M01 主数据广播：ICD/结论术语字典、组织科室、参数缓存刷新）

**WebSocket**：`/ws/peis/dept/{deptId}`（分科工作站实时主题：待检队列变更、医技报告到达提醒、执行异常提示，刷新 ≤ 2s）；`/ws/peis/summary`（总检工作台：新进入总检列表、打回提醒）。受检者端通知统一经 M01 通知中心投递，本模块不自建患者主题。

**延迟队列（`fy.delay`）**：`delay.peis-report-overdue`（报告时限超时提醒：分科完成至报告发布超 5 个工作日，参数化）、`delay.peis-followup-due`（A 类告知超时升级与 B 类计划随访到期提醒）、`delay.peis-group-reminder`（团检批次到检提醒）。

## 8. 集成点

- **M01**：认证与 RBAC（套餐维护/登记/加退项/分科录入/总检/审核/发布/修正/随访/团检名单导入独立权限点）；字典引用（ICD-10、性别引用 M01 字典 code，结论术语/异常分级为模块专业字典、对齐 M07/M08/M14 先例挂接 M01 编码体系）；**医师执业资质校验**（审核签署医师副主任医师以上要求，调 M01 执业与处方权信息，FU-M01-04）；CA 电子签名（分科小结与总检双签）；通知中心（报告发布通知、随访告知、到检提醒、超时升级站内信/短信）；打印模板（导检单/指引单/报告）；审计切面；主数据广播订阅刷新。
- **M02**：登记前经解析服务建档/归一（FROZEN/MERGED 拒绝）；团检名单批量建档走其建档 API；`patient.merged` 驱动体检单据/报告归属映射刷新；重要异常与慢病结论经其健康档案 API（health_item）建议性回填；报告输出按 M02 脱敏规则；患者端仅可见本人已发布报告。
- **M07（检验执行复用契约，受理分发模式对齐 M04/M03 医嘱分发先例）**：本模块发布 `peis.checkin.registered`（lab 子键），M07 订阅消费并按 lab_panel_ref 生成检验申请与条码（source_type=PEIS、source_ref=checkin_no，M07 Spec 第 8 节预留适配位的启用设计）——事件经总线解耦，不产生编译期反向依赖；本模块订阅 `lab.report.published/corrected`、`lab.specimen.rejected` 聚合与异常提示；体检检验危急值由 M07 危急值引擎闭环（通知对象登记为体检科总检医师），本模块不自建危急值状态机；除上述事件与既定查询契约外本模块不调用 M07 其他接口。
- **M08（检查执行复用契约，同 M07 模式）**：`peis.checkin.registered`（exam 子键）由 M08 订阅生成检查申请，进入其预约-登记-影像-报告链（source_type=PEIS）；本模块订阅 `imaging.report.published/corrected`、`imaging.exam.cancelled`；除上述事件与既定查询契约外本模块不调用 M08 其他接口，两医技模块相互独立。
- **M13（计费与结算契约，需其 Spec 扩展项见偏差 2/3/4）**：本模块经 `GET /pricing-basis` 提供计价依据，体检收费工作台调用其 预计价/结算/退费 API（同步 API 契约，模式对齐"M03 收费入口在 M03、资金逻辑在 M13"的分工）；费用生成时点=登记确认（套餐展开，计费唯一键=checkin_no，charge_source=体检套餐展开）；M13 执行域事件（lab.specimen.received / imaging.exam.registered）对体检来源费用**仅回写执行占用标记、不重复生成费用行**（以 M13 pricing_rule 计价规则配置表达，否决运行时豁免，见偏差 3）；订阅 `billing.settlement.completed`（费清放行）与 `billing.refund.approved`（退项/退登确认）；本模块零金额字段，计价权威在 M13。
- **M09**：体检报告为其患者全景数据源（`GET /reports`，载荷锚点=patient_id+checkin_no）；`peis.report.published/corrected` 供其订阅刷新；病历文书本体归 M09。
- **M14（设备结果回传取数）**：一般检查设备（身高体重/血压/肺功能等）按 M16 体征一体机模式接入 IoTDA 遥测链路（接入与绑定归 M14/M16，本项目不做新接入）；本模块经 M14 遥测查询服务（FU-M14-06）按 patient_id+时间窗取数，人工核对确认后回填分科结果（双通道校验，禁止静默自动入库）。
- **M18（P2 预留）**：患者端体检报告查询/下载经 M18 渠道复用本模块取数 API，业务落本模块，渠道标记为线上。
- **M19**：体检人次、套餐使用、异常检出率（按结论术语/分级）、重要异常随访率、告知及时率统计取数 API（只读，口径对齐调研依据 5 质控指标）。
- **M20（事件治理与延迟队列）**：事件信封、outbox、幂等消费（received_event 去重）、死信处理、延迟队列（delay.peis-report-overdue/delay.peis-followup-due/delay.peis-group-reminder）全部复用 M20 治理约定；本模块全部事件先登记 event_registry。
- **外部对接**：无本期直连外部系统；职业健康体检监管上报格式预留接口位（经 M20 通道，`# TODO(occupational-report): 职业健康体检监管数据上报，计划于 M18/M19 增值阶段评估引入`）。

## 9. 非功能与安全

- 性能：体检高峰按日均 500 人次、团检单批次 300 人规划；名单批量导入异步化（数百人名单分钟级完成并回执）；登记确认 P95 < 500ms（含 M13 同步结算调用于外的本地动作）；报告渲染异步队列（发布动作轻量，渲染完成即 GENERATED）；总检工作台加载（含历次对比）P95 < 2s；导检队列刷新 ≤ 2s（WebSocket）。
- 时限 SLA：报告 5 个工作日、A 类重要异常告知 24 小时均为系统参数默认值（来源为地方标准与质控共识，见偏差 5），超时经延迟队列提醒体检科负责人并留统计。
- 可用性：P2 模块随平台可用性要求（非 7×24 核心链路）；M07/M08/M13 任一上游不可用时——登记确认被费清校验拦截并明确提示、医技执行侧按其模块自身可用性保障，本模块结果聚合以事件补投递兜底（消费可重放，见测试要点）。
- 安全：报告 PDF 落对象存储并按受检者授权访问（患者端仅本人、团检单位仅 GROUP_SUMMARY 统计版）；团检名单证件号加密存储（对齐 M02 敏感字段策略，HMAC 检索列）；个人明细向单位开放必须本人单独授权留痕（个保法，红线 5）；随访告知外呼记录不含具体诊断明文（仅提示"重要异常需复查"并引导本人获取报告）；全量审计（发布/修正/撤回/随访闭环/单位报告生成）经 M01 审计切面。
- 合规映射：《健康体检管理暂行规定》报告签署资质（第十六条）与病历书写规范要求（调研依据 3）；重要异常结果 A/B 分级管理专家共识与国家 2023 版质控指标（调研依据 5）；个保法敏感个人信息（单独同意+最小必要）；数据留存：体检报告与随访记录长期保留（健康管理连续性），对象存储生命周期对齐总 Spec 分层策略。

## 10. 测试要点

- 正常：个人全链路（登记→M13 结算→费清→确认→M07/M08 分发→报告回流聚合→分科小结→总检双签→发布→通知）；检验报告发布事件到达后对应项目自动转 READY 且展示报告入口；报告修正重发后新版本 PUBLISHED、旧版本 SUPERSEDED、订阅方收到 corrected；A 类重要异常总检审核后自动生成随访任务、告知登记、复查回填、闭环广播；团检批次名单导入（含既有档案归一与新建档混合）→ 批次进度 → 单位汇总报告仅含统计口径。
- 边界：性别不符套餐/项目（男性勾选妇科项目）登记拦截；退项仅未执行项目可退（已 READY 项目退项被拒）；加项发生在 DEPT_COMPLETED 后（状态自动回退 IN_PROGRESS、新项目补费后重新流转）；个别项目弃检 WAIVED 后分科完成判定豁免通过；全部项目就绪但任一科室小结未签名 → 不进入总检；报告时限边界（第 5 个工作日 23:59 前发布不告警）；随访失访登记前置（仅一次尝试不可失访）；同一 checkin_no 的 `peis.checkin.registered` 事件重放 → M07/M08 幂等不重复生成申请；批次对公结算（R5-12）：M13 对公结算引用未登记时 `POST /group-exams/{no}/settle` 迁移 SETTLED 被拦截，结算登记完成后批次置 SETTLED、名单与报告不再变更。
- 异常：`lab.report.published` 事件丢失 → M20 重放后聚合补齐（幂等）；M13 结算未完成时确认登记被拦截并提示费清；M07 标本不合格事件 → 项目转 ABNORMAL 且体检科可见处置待办；审核医师资质校验不通过（非副主任医师以上）→ 发布前置拦截；报告渲染失败 → 保持 READY_TO_PUBLISH 可重试且不产生半发布状态；`patient.merged` 后历史体检单据在主档下全景可见且从档查无。
- 安全：无授权角色调患者端报告接口 403 且留审计；团检单位账号尝试拉取个人明细报告被拒绝（仅 GROUP_SUMMARY 可见）；随访外呼记录与事件载荷抽验不含诊断明文；发布/修正/随访闭环全量审计抽验。

## 11. 自审记录

- [x] 无 TBD/TODO/占位符，13 项内容完整（文档头 + 12 节；第 8 节职业健康上报为规范格式标注的未来扩展预留，非占位符）
- [x] 覆盖 FU-M17-01~04 全部条目，无遗漏、无私增（设备结果自动回传/建议知识库/单位报告/重要异常随访均为总 Spec 对应 FU 说明的细化，非新功能点）
- [x] 内部一致：领域模型 ↔ 状态机 ↔ API ↔ 测试一一对应（peis_checkin/checkin_item/department_result/summary/report/followup/group_exam 状态机均有对应接口、流程与测试项；三向映射约束、费清放行、加退项分支均有测试场景）
- [x] 符合跨模块约定：schema=peis；主键 BIGINT 雪花；唯一金额字段（套餐定价参考价）NUMERIC(18,2)，符合红线 3"不落任何费用与结算数据（套餐定价参考价除外，计价权威在 M13）"口径（R5-11）；事件命名 `<模块>.<实体>.<动作>`、信封 eventId/occurredAt/producer、消费走 integration.received_event 幂等；REST 路径 `/api/v1/peis/`；字典只存 M01 code 引用；状态字段 VARCHAR 常量+迁移日志；患者关联一律 patient_id（visit_id 约定的边界处理见方案 3.1 与偏差 1）
- [x] 依赖方向正确：仅依赖 M01/M02/M07/M08/M13/M14/M20 的对外接口与事件契约（M07/M08 为 M17→医技单向依赖，医技不依赖本模块）；无反向依赖、无跨模块读表
- [x] 方案推导 4 个关键点均有备选对比与依据（3.1 身份模型、3.2 执行落点、3.3 报告生成、3.4 三目录映射），每个结论附调研来源
- [x] 无代码级实现（无类名/方法体/SQL DDL；表设计为"表-关键字段-约束"粒度）
- [x] 歧义消除：体检不签发 visit 的身份锚点、检验/检查执行权威与聚合边界、加项/退项/弃检三种变更的费与状态联动、报告修正与总检打回两条修正路径、单位报告统计口径边界均已显式定义
- [x] 中文术语与总 Spec 一致（套餐/登记/分科/总检/主检/随访/团检/重要异常结果；申请单/报告/条码用词与 M07/M08 一致）

## 12. 与总 Spec 的偏差

1. **体检就诊不签发 visit_id（结论提请裁决）**：总 Spec 第 3 节患者关联约定为"patient_id + visit_id（由 M03/M04 签发）"；本 Spec 结论为体检登记独立于门诊/住院 visit 语义——不签发 O/I 类型码，体检单据以 `checkin_no`（`T+yyyyMMdd+5位流水`）为主键锚点、关联 patient_id。依据：体检无临床诊疗闭环、不污染门诊统计口径、不改动 M02 已定稿的 visit 签发主体契约（详见方案 3.1）。请统一审查裁决；若裁决要求扩展 visit 类型码，仅需调整 peis_checkin 锚点字段语义，其余设计不变。**【已经统一审查裁决采纳（终审 #10）】体检不签发 visit_id，`peis_checkin_no` 为锚点，本偏差闭环。**
2. **M13 Spec 需扩展体检类目**（对上游 Spec 的扩展需求）：结算类型（settle_type）增加"体检"（含个人/团检对公）、计费来源（charge_source）增加"体检套餐展开"、费用记录就诊类型（visit_type）增加"体检"枚举（visit_id 为空、以 checkin_no 关联）、计费唯一键支持以 source_ref=checkin_no 幂等。M13 红线 3（计费必须携带来源与来源单据引用）本扩展完全兼容，属类目枚举扩展而非规则变更。**已在 M13 v1.1 登记体检预留枚举与 peis.* 订阅（`peis.checkin.registered/cancelled`），本项闭环（90 号文档 M-27）。**
3. **M13 执行域事件对体检来源"仅回写执行占用、不生成费用行"的计价规则配置表达**：M13 现契约中 `lab.specimen.received` / `imaging.exam.registered` 触发"费用生成+确认"；体检场景费用已在登记确认时生成，该两类事件对体检来源的费用仅回写执行占用标记、不重复生成费用行。该行为以计价规则配置表达（M13 v1.1 已以 pricing_rule 配置落地——trigger_type=executed 的规则排除 charge_source=体检类目，否决运行时豁免分支，90 号文档 M-27）。
4. **M07/M08 预留适配位启用**：两 Spec 第 8 节"M17（P2 预留）适配位"本期启用——申请来源类型增加 PEIS，受理入口增加订阅 `peis.checkin.registered`（各自 lab/exam 子键）；属预留位的按期兑现，非契约变更。
5. **参数化默认值说明**：报告时限 5 个工作日（来源：广州市地方标准 DB4401）、A 类重要异常 24 小时告知（来源：质控共识/国家 2023 质控指标的及时性要求）均为非全国统一强制数值，本 Spec 以系统参数默认值落地并允许属地化调整，不构成对总 Spec 的功能偏差。

### v1.1 统一审查修订记录（Round 1，依据 90-cross-review.md 裁决）

| ID | 修订点 | 裁决依据 |
| --- | --- | --- |
| R5-11 | 红线 3 改为"本模块不落任何费用与结算数据（套餐定价参考价除外，计价权威在 M13）"；§11 自审口径同步对齐（§4 领域模型表头说明原文即符合新口径，未改动） | 90 号文档 R5-11：原"不落任何金额与费用数据"措辞与本模块实际存在套餐定价参考价字段的事实不符 |
| R5-12 | §7 REST 团检组补 `POST /group-exams/{no}/settle`（登记 M13 对公结算引用后批次迁移 SETTLED）；§10 边界补对公结算前置校验与终态保护用例 | 90 号文档 R5-12：peis_group_exam 状态机 SETTLED 迁移缺对应端点与测试 |
| R5-13 | §7 `peis.checkin.registered` 补发布时点注记：REGISTERED→IN_PROGRESS 迁移（登记确认）时发布，消除与 REGISTERED 待费清状态的语义错位 | 90 号文档 R5-13：事件名"registered"与 REGISTERED（待费清）状态存在语义错位 |
| M-27 | §12 偏差 3 措辞由"幂等豁免分支"改为"计价规则配置表达（M13 v1.1 已以 pricing_rule 配置落地，否决运行时豁免）"，§8 M13 集成点同步；偏差 2 标注"已在 M13 v1.1 登记体检预留枚举与 peis.* 订阅，闭环" | 90 号文档 M-27：体检计价契约单向申报的统一裁决；备案终审 #8/#9（豁免改计价规则配置） |
| M-25 | §7 订阅补成对登记：`patient.merged`↔`patient.split`、`patient.frozen`↔`patient.unfrozen` | 90 号文档 M-25：patient.split/patient.unfrozen 曾零订阅，裁决"成对登记"（成对语义权威口径在 M02 §7） |
| 终审 #10 | 偏差 1 补采纳闭环标注（Round 2 补登）：体检不签发 visit_id、以 `peis_checkin_no` 为锚点 | 90 号文档第 4 节终审 #10：体检以 peis_checkin_no 为锚点、不进 visit 域 |
