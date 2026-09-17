# M02 患者主索引与档案（EMPI）· 功能实现 Spec

| 属性 | 内容 |
| --- | --- |
| 模块编号 | M02 |
| Maven 模块 | `fuyun-patient`（schema：`patient`） |
| 版本 / 状态 | v1.1 / 统一审查修订 |
| 上游依赖 | M01（认证/权限/审计/字典/通知）、M20（事件总线治理、幂等构件、事件契约登记） |
| 下游被依赖 | M03/M04/M05（就诊与护理身份关联）、M06（过敏提示）、M07/M08/M10/M11/M12（医技/专科患者关联）、M09（患者全景数据源之一）、M13（结算患者信息与一卡通支付）、M14/M16（遥测与病房患者关联）、M17/M18/M19（体检/互联网医院/运营）、M20（FHIR Patient 资源映射源） |
| 对应总 Spec | FU-M02-01 ~ FU-M02-07 |

---

## 1. 模块定位与边界

**职责**：本模块是全院患者的唯一身份权威源。四项核心职责：① 患者建档——以身份证（读卡器）、医保电子凭证、电子健康卡、就诊卡、线上实人验证等多介质采集身份并实名建档，覆盖无证件的临时/急诊建档场景；② EMPI 主索引——对证件号/手机号/姓名等多标识进行匹配归一，为每个自然人签发并维护全院唯一 `patient_id`，提供高频"标识→主索引"解析服务；③ 重复治理——建档时疑似重复提示、批量重复扫描、疑似重复人工审核、档案合并与拆分；④ 患者主数据服务——基础健康档案（过敏史/慢病史/手术史/免疫接种等）、就诊卡与一卡通账户、隐私授权与脱敏展示、患者标签与人群圈选。

**非职责**：`visit_id` 的签发与就诊业务状态维护（归 M03 门诊/M04 住院，本模块只定义其结构规范）；诊疗明细数据的聚合展示（检验/检查/用药/病历全景归 M09 患者全景，本模块只维护基础健康档案项）；一卡通资金的收退付动作（归 M13，本模块只维护卡账户余额台账并接收 M13 记账通知）；电子健康卡/医保电子凭证的国家平台注册签发（外部职责，本模块对接）；国家字典的维护（归 M01，本模块只引用）。

**模块红线**：
1. `patient_id` 全院唯一且只能由本模块签发；任何模块不得自行生成患者标识或绕过解析服务以介质号直接关联临床数据。
2. 禁止物理改写历史临床数据的患者归属来实现合并（采用主索引映射，见方案 3.3）；合并、拆分、冻结等主索引级变更必须经状态机服务并全程留痕。
3. 患者证件号等敏感字段存储加密；任何明文展示必须过脱敏规则或"明文查阅"授权留痕，禁止在日志、事件载荷中输出完整敏感明文。
4. 本模块不反向依赖业务模块的编译期实现；"是否有在途就诊"等合并前置检查通过本模块定义的扩展点（SPI）由业务模块注册实现（依赖倒置，见第 8 节）。

## 2. 调研依据

1. EMPI 的核心算法是记录匹配，方法分**确定性匹配**（关键字段精确命中，如证件号）与**概率匹配**（多字段加权评分，常以姓名拼音做首轮索引）两类。（来源：https://chima.org.cn/Html/News/Articles/4560.html ；https://zhuanlan.zhihu.com/p/324443670 ）
2. 国际实践与文献：确定性匹配以业务规则为主、概率匹配更常见于 EMPI 产品；存在"确定性精确 + 概率近似"混合（hybrid）算法的同行评议论文；开源实体解析引擎 OpenEMPI 同时提供确定性、概率性与扩展匹配；AWS HealthLake 以匹配规则输出带置信度分数的统一档案。（来源：https://pmc.ncbi.nlm.nih.gov/articles/PMC6841798/ ；https://journals.sagepub.com/doi/10.1177/1460458215607080 ；https://www.openempi.org/ ；https://docs.aws.amazon.com/zh_cn/healthlake/latest/devguide/architectural-patterns-empi.html ）
3. 北京协和医院主索引实践：以**有效证件号为关联主线**；无证件患者须经特殊权限授权核实；多 ID 合并分"检验（以证件为主线查多 ID）→控制（医生站限制未合并患者就诊；**存在未完成就诊记录时禁止合并**）→合并（双方有效信息相互复制避免丢失）"三环节；PACS/LIS 等系统操作前读卡核对身份。（来源：https://www.cnblogs.com/yisheng163/p/4929273.html ）
4. 国内 EMPI 实施经验：主索引建设是持续运行的"**增量匹配**"而非一次性全量去重；以检测策略+可信阈值算法筛选生成主索引；EMPI 标配"增加、删除、修改、**合并、拆分**、查询"管理功能。（来源：https://blog.csdn.net/qq_42884482/article/details/128507157 ；http://www.wisetop.cn/News/149.html ；https://www.researchgate.net/publication/364945416 ）
5. 电子健康卡：国家推进"一卡（码）通"，要求公立医疗机构**自建就诊卡等患者主索引统一对接**；患者持电子健康卡二维码即可完成挂号身份识读；院内建档前须完成身份验证（对接注册系统实名核验）。（来源：https://csmi.cma.org.cn/art/2019/1/3/art_723_25007.html ；https://www.nhc.gov.cn/mohwsbwstjxxzx/s2908/201911/734c26d5b30f4c27b4b5e437f049c858/files/1740021154678_46976.pdf ；https://open.tengmed.com/openAccess/ability/detail?sceneId=22&catalogId=20&serviceId=164 ）
6. 医保电子凭证：由**全国统一医保信息平台统一签发**，定点医疗机构须完成 HIS 接口改造实现挂号/就医/结算全流程支持医保码。（来源：https://www.nhsa.gov.cn/art/2021/8/30/art_110_7051.html ；https://www.nhsa.gov.cn/art/2023/11/24/art_14_11548.html ）
7. 就诊卡业务：就诊卡支持挂失（凭身份证等本人有效证明）、补卡并将**旧卡信息转移**至新卡；医院侧登记范围含患者注册、**临时患者注册**、主索引合并及就诊卡的发卡/退卡/注销/挂失。（来源：https://www.gdhis.net/html/web/xinwenzixun/jishubaike/1864951929742827522.html ；https://zfcg.henan.gov.cn/webfile/xuchang/rootfiles/2021/10/29/d62d1a11965a495c9a56faff7750e931.pdf ）
8. 隐私合规：《个人信息保护法》第 28 条将医疗健康信息列为**敏感个人信息**，处理须有特定目的、充分必要性并取得**单独同意**；脱敏重点字段含姓名、出生日期、电话、地址、证件号等；《医疗机构病历管理规定》要求非诊疗关系内的机构和个人不得擅自查阅病历，泄露患者隐私承担侵权责任。（来源：https://www.kangdalawyers.com/newsdetail_2178.html ；https://pmc.ncbi.nlm.nih.gov/articles/PMC10400823/ ；https://epaper.gmw.cn/wzb/html/2025-05/10/nw.D110000wzb_20250510_2-08.htm ）
9. 健康档案内容标准：区域卫生信息平台建设指南将血型、过敏史、预防接种史、既往疾病史、家族遗传病史列为**基本健康信息**；国家基本公共卫生服务规范（第三版）规定居民健康档案含个人基本信息（含既往史、家族史）、健康体检与重点人群管理记录；国家已发布《居民电子健康档案首页基本内容（试行）》。（来源：https://www.cisco.com/web/CN/partners/industry/pdf/healthcare_solutions_02.pdf ；http://www.nbphsp.org.cn/jbgw/jkda/ ；http://www.news.cn/20240621/c9a550c9986243f3812ac1132da23293/c.html ）
10. 实名制与身份识别：实名制建档支持窗口读身份证、自助机建档、线上渠道实人绑卡（人脸核身）三种典型路径；卫生行业标准《患者身份识别管理标准》（WS/T 840—2025）要求以姓名、性别、出生日期、证件号等核实患者身份（国家卫健委 2025 年发布行业标准，未引用网络来源）。

## 3. 方案推导（关键设计点选型）

### 3.1 EMPI 匹配策略：纯确定性 vs 纯概率评分 vs 确定性+概率性分层组合

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 纯确定性匹配 | 仅以证件号等强标识精确匹配归一 | 实现简单、无误配；但漏配高——无证建档、证件录入错位、儿童/新生儿无证、一证多档历史数据全部漏掉，重复档案持续累积（调研依据 1、3 的多 ID 乱象正是前期规则不全所致） |
| 纯概率评分 | 姓名拼音+性别+出生日期+手机号等加权评分，超阈值自动归一 | 召回高；但医疗场景**误配=病历张冠李戴，不可逆**，同名同生日、亲属代挂号等真实场景下全自动合并风险不可接受（调研依据 8 的个保法与病历正确性要求） |
| **分层组合（选定）** | 第一层：强标识（大陆居民身份证号等法定证件号）精确命中即自动归一，但**人口属性矛盾时降级为疑似重复转人工**；第二层：无强标识命中时，以弱标识组合（姓名拼音+性别+出生日期、手机号等）概率评分，评分进入"疑似重复待审"由人工确认，**任何合并不自动越过人工审核** | 与协和"证件号为主线+人工控制合并"一致（调研依据 3）；与混合算法学术结论一致（调研依据 2）；自动归一只发生在强标识唯一命中且属性吻合时，安全边界清晰；检测策略与阈值参数化，支持按院内数据质量调优 |

**结论**：分层组合。强标识唯一命中且姓名/性别/出生日期一致 → 自动归一；强标识命中但属性矛盾、或弱标识评分达阈值 → 生成疑似重复待人工审核；评分低于阈值 → 直接新建档案。疑似重复同时来自两个渠道：建档时实时检测、周期性批量扫描（增量比对，非一次性全量，调研依据 4）。

### 3.2 patient_id 生成与全局唯一机制：介质号兼主键 vs 数据库自增 vs 独立发号器+标识注册表

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 介质号（就诊卡号/证件号）兼作主索引 ID | 单一标识即身份 | 与多介质现实冲突（同一患者可同时持证件、医保码、健康卡、就诊卡）；换卡/补卡即换 ID，历史数据被迫改写；无卡无证患者（急诊）无法建 ID |
| 数据库自增序列 | PG 序列发号 | 与 README"业务表 BIGINT 雪花 ID（分布式预留）"的全局主键约定冲突；分库/迁移/独立进程化后失效；ID 连续可推测业务量 |
| **独立发号器 + 标识注册表（选定）** | `patient_id` 由本模块发号服务按雪花算法生成（BIGINT，满足全局约定）；一切介质与证件作为**标识**登记在 patient_identifier 注册表并挂接到主索引，解析服务维护"标识值 → patient_id"映射并做两级缓存 | 患者身份与介质解耦，换卡/补卡/挂标识不动主索引；多介质天然归一；发号无单点（雪花位分配）；对外交互只认 patient_id，介质号仅作检索条件 |

**结论**：独立发号器 + 标识注册表。发号器雪花位按模块预分配；标识表以"标识类型 + 标识值摘要"唯一约束兜底并发重复登记；冻结/合并状态在解析服务返回中同步携带，供业务模块拦截。

### 3.3 患者合并的数据处理：物理迁移历史数据 vs 主记录指针映射

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 物理迁移 | 合并时把从档的全部历史就诊/医嘱/费用记录 UPDATE 为主档 patient_id | 读侧简单；但跨全模块百万行级 UPDATE，事务重、锁冲突、与门诊高峰互斥；**拆分几乎不可恢复**（原归属信息丢失）；违反"医疗记录不可篡改"精神（调研依据 8）；总 Spec FU-M02-03 要求合并与拆分并存，物理迁移与可拆分天然矛盾 |
| **指针映射（选定）** | 从档置 `MERGED` 状态并登记 `merged_into_patient_id` 指向主档；历史业务行**一律不动**；归一由本模块解析服务在读侧完成（任何 patient_id 解析均收敛到主档），并以 `patient.patient.merged` 事件广播，各业务模块幂等消费刷新本地映射/宽表；主档补齐从档的主数据字段（联系方式等按字段级择优，双方原值留快照）与从档全部标识 | 合并操作轻量原子（仅主索引层变更），不与业务表争锁；**拆分=逆映射，可恢复**（调研依据 4：合并、拆分是 EMPI 并存标配）；协和实践"存在未完成就诊禁止合并+双方有效信息相互复制"直接落在本方案上（调研依据 3）；代价是读侧必须强制经归一服务——以"全院唯一解析入口 + 事件广播 + 红线约束"保证无旁路 |

**结论**：主记录指针映射 + 事件广播。合并记录保存从档字段与标识的完整快照（merge_record），支撑审计与拆分回滚；各模块对 `patient.patient.merged` 的消费一律幂等（M20 received_event 去重），事件重放不产生二次合并。

### 3.4 visit_id 结构规范：纯雪花号 vs 结构化业务号

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 纯雪花号 | visit 主键直接用雪花 BIGINT | 与 patient_id 同构、发号简单；但就诊号在腕带/单据/口头核对场景需要**可读、可校验日期**，纯数字长号易抄错且无法目视分辨门诊/住院 |
| **结构化业务号（选定）** | visit_id 为 14 位定长字符串 `{类型码}{yyyyMMdd}{5 位当日流水}`，由签发模块当日发号器生成 | 可读可校验（对齐患者身份识别标准对核对要素的要求，调研依据 10）；类型码区分门诊/住院；唯一性由签发模块唯一约束保证；patient_id（雪花）与 visit_id（结构号）职责分离：前者标识"这个人"，后者标识"这次就诊" |

**结论**：结构化业务号。正式约定（供 M03/M04 遵守，统一审查对照）：① 签发主体唯一——门诊/急诊就诊由 M03 签发（类型码 `O`）、住院由 M04 签发（类型码 `I`），其余模块不得签发；② 结构 `O|I + 8 位签发日期 + 5 位当日流水（左补零）`，定长 14 位；③ 签发后**不可变、不可复用、不因患者合并/拆分而改写**；④ 必须与签发时点的 `patient_id` 同时落库；⑤ 历史就诊永远指向签发时的 patient_id，患者合并后读取经 EMPI 归一解析到主档；⑥ 结构校验规则由本模块发布供各模块自查。

### 3.5 电子健康卡/医保电子凭证对接形态：逐渠道直连 vs 统一适配器

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 各建档渠道分别直连外部平台 | 窗口/自助机/公众号各自实现健康卡与医保码核验调用 | 同一外部接口在多渠道重复实现，密钥与对账分散，变更需多处改造 |
| **统一适配器（选定）** | 本模块内建"介质核验适配器"层，电子健康卡（注册系统/服务商开放平台）、医保电子凭证（经 M13 医保接口通道）各封装为一个可配置适配器，对外提供统一的"介质核验与身份提取"服务位；外部服务不可用时按介质降级（转人工证件核实并标记未实名） | 建档渠道全部经同一入口，密钥、留痕、对账集中；外部供应商或接入方式变化只改适配器；与 M20 通道治理理念一致（对端对接经适配器注册，调研依据 5、6） |

## 4. 领域模型

| 实体 | 关键字段 | 说明 |
| --- | --- | --- |
| patient 患者主索引 | patient_id（雪花 BIGINT，本模块签发）、name、name_pinyin、sex、birth_date、id_card_no（加密存）、mobile、nation/ethnicity、address、occupation、marital_status、blood_type、status(NORMAL/FROZEN/MERGED)、merged_into_patient_id、real_name_flag(实名/未实名)、register_channel、archive_source | 全院唯一；出生日期与性别引用 M01 国标字典 code；合并从档保留 merged_into 指向 |
| patient_identifier 患者标识 | patient_id、identifier_type(身份证/护照/军官证/其他法定证件/医保电子凭证/电子健康卡/就诊卡/院内病历号)、identifier_value(加密存)、value_hash(HMAC 检索列)、card_no(卡面号，卡类介质)、status(ACTIVE/LOST/REPLACED/DISABLED)、is_primary、bound_at/unbound_at | (identifier_type, value_hash) 唯一约束防一标识挂多档；卡类介质含发卡渠道与操作留痕 |
| possible_duplicate 疑似重复 | patient_id_a、patient_id_b、match_score、matched_rules(命中字段与相似度快照)、source(建档实时/批量扫描)、status(PENDING/MERGED/EXCLUDED)、reviewed_by/reviewed_at/review_note | (patient_id_a, patient_id_b) 唯一；同一对患者重复命中只生成一条待审 |
| merge_record 合并记录 | survivor_patient_id(主档)、merged_patient_id(从档)、merge_reason、pre_snapshot(从档字段与标识合并前快照)、status(PROCESSING/COMPLETED/FAILED/REVERSED)、operator、approved_by、completed_at、reversed_at/reverse_reason | (merged_patient_id) 部分唯一约束（仅 status 为 PROCESSING/COMPLETED 时生效——活跃合并期内一档只能被合并一次；置 REVERSED（拆分）或 FAILED 后，同一从档允许再次发起合并，不因历史合并记录永久阻断）；拆分以 REVERSED 状态留痕，不删记录 |
| health_summary 健康档案 | patient_id(唯一)、blood_type/RH、past_history(既往史)、family_history(家族史)、summary_updated_at | 1:1 聚合表；明细项挂子表 |
| health_item 健康档案明细 | patient_id、item_type(ALLERGY 过敏/CHRONIC 慢病/SURGERY 手术/VACCINATION 免疫接种)、item_code(过敏物/ICD 诊断/疫苗 code，引用 M01 字典)、item_name、severity、onset_date、status(有效/已纠错)、source(医生站录入/手工补录)、note | 纠错不改原记录（置已纠错并新增），全程留痕 |
| privacy_auth 患者隐私授权 | patient_id、auth_type(建档知情同意/敏感信息使用单独同意/监护人代管授权)、auth_basis(纸质的凭证引用/电子签名引用，签发经 M01 CA)、scope、signed_at、valid_to、status(EFFECTIVE/EXPIRED/REVOKED) | 个保法"单独同意"留痕载体；建档必须存在有效知情同意授权 |
| privacy_mask_rule 脱敏规则 | rule_code、target_field(姓名/证件号/手机号/地址/出生日期等)、mask_pattern(保留策略)、exempt_roles(豁免角色集合)、enabled | 规则集中配置，各端展示统一生效；豁免角色经 M01 RBAC 角色 |
| privacy_access_log 敏感查阅留痕 | operator_id、patient_id、access_type(明文查阅/档案导出/全景调阅)、purpose、fields、occurred_at、trace_id | 只增表；与 M01 审计互补（M01 记"谁动了系统"，本表记"看了谁的什么"） |
| patient_tag 标签定义 | tag_code、tag_name、tag_type(系统自动/人工)、circle_rule(圈选条件)、status(ACTIVE/DISABLED)、valid_days | 圈选条件限定本模块维度（人口属性/健康档案/既有标签）；诊疗行为维度为 P2 预留适配位（经数据服务接口，见第 8 节） |
| patient_tag_rel 患者标签关联 | patient_id、tag_code、assigned_source(圈选/人工)、assigned_at、expire_at | (patient_id, tag_code) 唯一；过期自动失效 |
| card_account 一卡通账户（可选启用） | patient_id(唯一)、balance(BIGINT，分)、status(ACTIVE/FROZEN/CLOSED)、opened_at/closed_at | 默认关闭（系统参数启用）；资金收退付动作在 M13，本表仅为余额台账 |
| card_txn 一卡通流水 | account_id、txn_type(RECHARGE 充值/PAY 消费/REFUND 退款/REVERSE 冲正)、amount(BIGINT，分)、balance_after、biz_ref(M13 收费单据引用)、occurred_at | 只增表；与 M13 对账依据；记账与资金动作分离（M13 发起、本模块记账） |

关系要点：patient 1:N patient_identifier / privacy_auth / patient_tag_rel / card_txn，1:1 health_summary / card_account；patient N:N patient（经 possible_duplicate、merge_record 表达）；健康档案明细挂 patient。

## 5. 状态机与业务流程

- **patient**：`NORMAL ⇄ FROZEN`（冻结：身份存疑/风控要求，冻结期间解析服务返回拦截标记，挂号/入院等新就诊由业务模块拒绝）；`NORMAL → MERGED`（作为被合并方，登记 merged_into 指向）；`MERGED → NORMAL`（拆分恢复，仅能由 merge_record 置 REVERSED 触发）。MERGED 档案不可发起任何新就诊。
- **possible_duplicate**：`PENDING → MERGED(按合并处理) / EXCLUDED(排除，必填理由)`；PENDING 超时未处理经延迟消息提醒登记组长，不自动处置。
- **merge_record**：`PROCESSING → COMPLETED(广播 patient.patient.merged 成功后) / FAILED(前置检查失败或广播异常，可重试回 PROCESSING)`；`COMPLETED → REVERSED(拆分)`。REVERSED 为终态。
- **patient_identifier**：`ACTIVE ⇄ LOST(挂失，解析立即失效)`；`LOST → REPLACED(补卡转移，旧标识终态)`；`ACTIVE → DISABLED(解绑/注销)`。
- **card_account**：`ACTIVE ⇄ FROZEN(挂失联动)`；`ACTIVE/FROZEN → CLOSED(销户，余额必须为零，未结清拒绝)`。
- **privacy_auth**：`EFFECTIVE → EXPIRED(到期自动) / REVOKED(撤回)`。

主流程时序：
1. **建档与归一**：渠道（窗口读卡器/自助机/线上实人绑卡/住院登记）提交建档 → 介质核验适配器完成实名验证（读卡/健康卡注册系统/医保码）→ 匹配预检：强标识查标识注册表——唯一命中且姓名/性别/出生日期一致 → **归一返回既有 patient_id 并补挂新标识**，不新建；强标识命中但属性矛盾，或弱标识评分达阈值 → 提示疑似重复，操作员人工核对后决定"挂既有档"或"确认非同一人新建"；无命中且低于阈值 → 发号新建（无证件走授权建档，标记未实名，发 `patient.patient.created`）。
2. **重复审核与合并**：疑似重复待审列表 → 审核界面双档对照 → 经扩展点检查双方是否存在在途就诊（有则阻断，提示先完结——对齐协和"未完成就诊禁止合并"实践）→ 审批通过执行合并：主数据字段择优补齐、从档标识全部重挂、从档置 MERGED、快照入 merge_record、失效解析缓存 → 发 `patient.patient.merged`，各业务模块幂等消费刷新映射。
3. **就诊身份解析**：患者出示任一介质（卡/码/证件）→ 业务模块调解析服务 → 返回 patient_id + 档案状态（FROZEN/MERGED 拒绝并提示原因）→ 业务模块以 patient_id 签发 visit（visit_id 结构见方案 3.4）开展诊疗。

## 6. 功能实现设计（逐 FU）

| FU | 实现设计要点 |
| --- | --- |
| FU-M02-01 患者建档（P0） | 统一建档 API 供窗口（身份证读卡器即刷即录）、自助机、公众号/小程序（实人绑卡组件人脸核验）、住院登记（M04 调用）多渠道调用；介质核验统一走适配器层（方案 3.5）：身份证读卡直读、电子健康卡对接注册系统核验、医保电子凭证经 M13 医保通道提取身份；无证件患者走"授权建档"（权限控制+未实名标记+后续补实名转正式）；急诊无名氏/新生儿建临时档案（新生儿可关联母亲档案），取得身份信息后转正式并保留关联留痕；建档必填项与实名制要求对齐（姓名/性别/出生日期/证件/联系方式）；建档即触发实时重复检测（FU-M02-03） |
| FU-M02-02 患者主索引 EMPI（P0） | 匹配引擎按方案 3.1 分层执行；解析服务为全院唯一入口（标识值→patient_id+状态），两级缓存（进程内+Redis，事件失效）；发号器按雪花位预分配独立发号；"标识→档案"归一结果同时供 M20 对外服务（FHIR Patient 门面映射源、区域上报患者主数据源）；匹配规则（字段权重、阈值、强标识清单）系统参数化可调 |
| FU-M02-03 重复识别与合并拆分（P0） | 双渠道发现：建档实时检测 + 周期性批量增量扫描（新档/变更档与存量比对，延迟任务调度）；疑似重复工作台（并排双档对照、命中字段高亮、评分与规则解释）；合并前置检查（在途就诊经 SPI 扩展点查询，任一方存在则阻断）；合并执行按方案 3.3 指针映射，双人角色（经办+审批）可配置；拆分：从 REVERSED 链路恢复从档 NORMAL、标识按快照回挂、广播 `patient.patient.split`，全过程留痕；合并/拆分仅限指定权限角色 |
| FU-M02-04 就诊卡管理（P0） | 就诊卡全生命周期：发卡（卡库存管理：入库/领用/退回）、绑定档案、挂失（校验本人有效证件，解析立即失效）、补卡（新卡发号、旧卡信息与账户余额按快照转移至新标识，旧卡 REPLACED）、解绑/注销；一卡通余额为可选项（系统参数默认关闭，启用时金额以 BIGINT 存分）：充值/消费/退款动作由 M13 收费通道执行，本模块仅记账户台账与流水并支持与 M13 按日对账；卡介质自助机发卡与窗口发卡共用同一 API |
| FU-M02-05 患者健康档案视图（P0） | 基础健康档案项：过敏史、慢病史、手术史、免疫接种史、既往史、家族史、血型（对齐区域平台"基本健康信息"与基本公卫规范口径，调研依据 9）；数据来源：临床医生站经本模块 API 维护（就诊中强制提示完善过敏史）、历史数据导入工具；纠错留痕（旧值置已纠错不删除）；变更广播 `patient.health-summary.updated`（M06 处方审核、M05 护理执行、M03/M04 开单场景订阅做过敏与禁忌提示）；诊疗明细（检验/检查/用药记录）不在本模块聚合，由 M09 患者全景经各模块数据组装 |
| FU-M02-06 患者授权与隐私管理（P0） | 授权侧：建档强制采集知情同意（纸质凭证登记或电子签署引用 M01 CA），敏感信息二次利用（如科研/外送）须单独同意留痕（privacy_auth）；展示侧：脱敏规则集中配置（姓名保留姓氏、证件号/手机号中间打码、地址保留省市），按角色豁免（挂号收费等业务必需场景可见必要字段）；明文查阅走独立 API：校验功能权限+诊疗关系，写 privacy_access_log；日志与事件载荷禁带完整敏感明文；导出必审批并留痕 |
| FU-M02-07 患者标签管理（P2） | 标签定义（系统自动/人工两类）与人工打标、批量导入；人群圈选：基于本模块维度（人口属性/健康档案/标签组合）条件圈选，输出患者集合；诊疗行为维度（就诊频次/诊断/费用）为 P2 预留适配位，经数据服务接口实现（依赖 M19 数据服务，接口位不提前实现）；圈选结果供 M01 通知中心批量触达（随访/义诊通知）与 M19 分析导出；系统标签随事件自动维护（如慢病标签经健康档案变更触发） |

## 7. 对外接口

**REST（`/api/v1/patient/` 前缀）**：
- 建档与查询：`POST /patients`（建档，含匹配预检结果）、`GET /patients/{patientId}`、`PUT /patients/{patientId}`、`POST /patients/match-check`（建档前预检）、`GET /patients/search`（按标识/姓名/拼音检索，脱敏输出）、`POST /patients/{patientId}/freeze|unfreeze`（P1 PR-2 拍板 2 新增端点，本节原清单外：冻结/解冻成对最小 API，状态机见 §5 patient，事件成对见 §11 M-25）
- 标识与解析：`POST /identifiers/resolve`（标识→patient_id+状态，全院高频入口）、`POST /patients/{patientId}/identifiers`（补挂标识）、`GET /patients/{patientId}/identifiers`
- 重复与合并：`GET /possible-duplicates`（出参 `matchedRules` 为规则名数组——库值 JSON 数组文本读侧归一，终审 Minor 口径统一注记 2026-09-17）、`POST /possible-duplicates/{id}/exclude`、`POST /merges`（发起合并）、`POST /merges/{id}/approve`、`POST /merges/{id}/split`（拆分）
- 就诊卡：`POST /cards/issue|bind|replace`、`POST /cards/loss/{cardNo}`、`POST /cards/unbind/{cardNo}`（挂失/解绑以卡号路径承载、无请求体——A.3-1 收敛，P1 PR-2 实现注记）、`GET /cards/{cardNo}`；`POST /card-accounts/{id}/freeze|close`、`GET /card-accounts/{id}/txns`（充值/消费记账由 M13 调内部接口）
- 健康档案：`GET /patients/{patientId}/health-summary`、`POST /patients/{patientId}/health-items`、`POST /health-items/{id}/correct`（健康项新增/纠错的 `onsetDate` 日期入参非法 → 400 `PAT-1023`，D-15 收口注记 2026-09-17）
- 隐私：`GET/POST /privacy-auths`（`signedAtIso`/`validToIso` 时刻入参非法 → 400 `PAT-1023`，D-15 收口注记 2026-09-17）、`GET/PUT /privacy-mask-rules`（`maskPattern` 词表外值 400 拒改，终审 Minor 注记）、`POST /privacy/unmask`（明文查阅，留痕）、`GET /privacy-access-logs`
- 标签：`GET/POST/PUT /tags`、`POST /tags/{tagCode}/assign`（圈选打标）、`GET /patients/{patientId}/tags`、`POST /tag-circles/preview`（人群圈选试算）

**内部服务接口（进程内，供各模块调用）**：患者上下文解析（patient_id → 归一主档视图）、健康档案过敏项快速校验（供 M06 审方/开单嵌查）、一卡通记账登记（M13 资金动作后调用）、visit_id 结构校验规则下发；本模块经 SPI 扩展点调用业务模块注册的"在途就诊查询"实现（见第 8 节）。

**MQ 事件（发布，经 `fy.topic`，信封遵循 M20 治理约定）**：
- `patient.patient.created`（新档建立，含未实名标记）
- `patient.patient.updated`（主数据变更）
- `patient.patient.merged`（合并完成：主档/从档 ID 与映射，订阅方幂等刷新；**成对语义**——凡订阅本事件的模块必须成对登记订阅 `patient.patient.split`，成对关系由本模块在 event_registry 登记时统一标注）
- `patient.patient.split`（拆分恢复：从档恢复正常；`patient.patient.merged` 的逆操作事件，与之一一成对）
- `patient.patient.frozen` / `patient.patient.unfrozen`（冻结状态变更；**成对语义**——凡订阅 `patient.patient.frozen` 的模块必须成对登记订阅 `patient.patient.unfrozen`，成对关系由本模块在 event_registry 登记时统一标注）
- `patient.identifier.changed`（绑卡/挂失/补卡/解绑，解析缓存失效依据）
- `patient.health-summary.updated`（健康档案变更，含过敏项摘要）
- `patient.tag.changed`（打标/失效）

**MQ 事件（订阅）**：`system.dict.published`、`system.org.changed`、`system.user.changed`、`system.param.changed`（M01 主数据广播：字典/组织/用户/参数缓存刷新，消费经 M20 幂等构件）。

**WebSocket**：无本模块专属主题（合并待审提醒等经 M01 通知中心站内信通道投递）。

## 8. 集成点

- **依赖上游 M01**：登录认证与 RBAC（脱敏豁免角色、合并/明文查阅权限点）、国标字典引用（性别/民族/证件类型/ICD/疫苗 code，不自建副本）、审计切面（建档/合并/拆分/冻结/明文查阅全量留痕）、通知中心（疑似重复待审/冻结通知）、CA 电子签名（知情同意电子签署）。
- **依赖上游 M20**：事件总线治理（信封规范、fy.topic、幂等构件 received_event、死信处理）；事件先登记 event_registry 后发布；出站外部调用（健康卡注册系统/医保通道）经 M20 出站留痕与监控。
- **SPI 扩展点（依赖倒置）**：本模块定义"在途就诊查询"扩展点契约，M03/M04 注册实现（门诊在途就诊/在院状态）；合并流程经扩展点查询，不产生对业务模块的编译期依赖——此为本模块红线 4 的唯一合法跨模块调用形态。
- **被下游依赖**：M03/M04/M05（身份解析、冻结拦截、过敏提示）、M06（过敏与禁忌校验）、M07/M08/M10/M11/M12（patient_id 关联解析）、M09（健康档案与主数据源之一）、M13（结算患者信息、一卡通记账）、M14/M16（设备遥测与病房场景患者关联）、M17/M18（体检/互联网医院建档与身份归一）、M19（标签人群数据输出）、M20（FHIR Patient 资源映射源、区域上报患者主数据）。
- **外部对接（经适配器）**：电子健康卡注册系统/服务商开放平台（注册核验）、身份证读卡器（窗口设备驱动）、医保电子凭证身份提取（经 M13 医保接口通道，不在本模块直连医保平台）。

## 9. 非功能与安全

- 性能：门诊高峰 2000 人次/小时叠加自助机/PDA 高频解析，标识解析接口 P95 < 100ms（两级缓存，缓存命中率 > 99%，事件驱动失效）；建档（含匹配预检）P95 < 500ms；解析服务无状态可多实例。
- 容量：1000 床位医院累计档案按 200 万患者、500 万标识规划（普通索引即可，无需分区）；批量扫描任务错峰执行不影响在线业务。
- 安全：证件号/健康卡卡号等敏感字段存储加密并附 HMAC 检索列（密钥环境变量管理）；传输 TLS 1.2+；日志与事件载荷禁带完整敏感明文；明文查阅与导出双重留痕（M01 审计 + privacy_access_log）；对齐等保三级与个保法"敏感个人信息单独同意+最小必要"要求（调研依据 8）。
- 一致性与可靠性：合并/拆分/冻结经状态机服务并留迁移日志；`patient.patient.merged` 等事件先 outbox 后投递（M20 治理约定），订阅方以 eventId 幂等；解析缓存失效与事件广播双保险（缓存兜底短 TTL）；发号器雪花位冲突零容忍（位图配置化）。
- 合规映射：实名制就医（WS/T 840—2025 身份核对要素）；电子健康卡一卡（码）通主索引统一对接（调研依据 5）；病历查阅限制（调研依据 8）；健康档案项对齐国家基本公卫规范与区域平台口径（调研依据 9）。

## 10. 测试要点

- 正常：身份证读卡建档 → 发号建新档并发 `patient.patient.created`；已建档患者再持就诊卡就诊 → 解析归一返回既有 patient_id；疑似重复审核合并 → 从档在途检查通过、合并后从档历史就诊经解析服务在主档全景可见；就诊卡挂失后解析立即失效、补卡后余额与标识完整转移；健康档案过敏项变更 → M06 审方侧收到更新提示。
- 边界：同名同性别同出生日期但证件不同 → 进疑似重复待审（不自动合并）；同一证件号首次建双档（属性矛盾）→ 待审而非自动归一；合并时从档存在在途就诊 → 阻断并提示；被合并从档发起建档请求 → 解析返回主档且补挂标识；一卡通并发充值与消费 → 余额按串行化台账不超扣；拆分后从档标识与字段按快照完整恢复；拆分后同一从档再次发起合并 → 合并可正常执行（部分唯一约束仅对 PROCESSING/COMPLETED 生效，REVERSED 历史记录不阻断再次合并，拆分-再合并可循环）；冻结患者挂号请求被业务模块拒绝且提示原因。
- 异常：电子健康卡注册系统不可用 → 适配器降级为人工证件核实建档（标记未实名）并补传队列；`patient.patient.merged` 订阅方消费失败 → 死信重放后业务仍只生效一次（幂等）；合并广播中断 → merge_record 置 FAILED 可重试且不产生半合并状态；批量扫描任务失败 → 可重跑且同一对患者不重复生成待审。
- 安全：无豁免角色查询患者列表仅见脱敏字段；无权限调明文查阅接口 403 且留审计；有权限明文查阅写入 privacy_access_log；事件与日志中证件号/手机号脱敏抽验；越权合并（无合并角色）403。

## 11. 自审记录

- [x] 无 TBD/TODO/占位符，13 项内容完整（文档头 + 12 节）
- [x] 覆盖 FU-M02-01~07 全部条目，无遗漏、无私增（FU-M02-07 按 P2 定位细化，诊疗维度圈选仅留适配位不提前实现；临时/急诊建档为 FU-M02-01 多介质场景的细化，非新功能点）
- [x] 内部一致：领域模型 ↔ 状态机 ↔ API ↔ 测试一一对应（patient/possible_duplicate/merge_record/patient_identifier/card_account/privacy_auth 六个状态机均有对应接口、流程与测试项；health_item 纠错、card_txn 对账均有测试场景）
- [x] 符合跨模块约定：schema=patient；主键 BIGINT 雪花；金额以 BIGINT 存分且服务端台账；事件命名 `<模块>.<实体>.<动作>`、信封 eventId/occurredAt/producer、消费走 integration.received_event 幂等；REST 路径 `/api/v1/patient/`；字典只存 M01 code 引用；状态字段 VARCHAR 常量+迁移日志；patient_id+visit_id 患者关联约定已落实并给出 visit_id 结构规范
- [x] 依赖方向正确：仅依赖 M01/M20 对外接口；对业务模块仅在"在途就诊查询"处采用 SPI 扩展点（依赖倒置，红线 4 已显式声明）；无跨模块读表
- [x] 方案推导 5 个关键点均有备选对比与依据，含任务要求的三个必选点（3.1 匹配策略、3.3 合并数据处理、3.2 patient_id 生成），每个结论附调研来源
- [x] 无代码级实现（无类名/方法体/SQL DDL；表设计为"表-关键字段-约束"粒度；读卡器/健康卡开放平台为设备与平台名，非代码实现）
- [x] 歧义消除：合并语义（指针映射而非物理改写）、visit_id 签发主体与结构、一卡通资金动作（M13）与台账（本模块）边界、临时档案转正式流程均已显式定义
- [x] 术语与总 Spec 一致（建档/主索引/归一/合并/拆分/脱敏/人群圈选/电子健康卡/医保电子凭证）

## 12. 与总 Spec 的偏差

无偏差。一处细化澄清（请统一审查裁决确认）：总 Spec FU-M02-03 说明中的"档案合并（历史数据迁移）"细化理解为——**患者主数据层面归一**（字段择优合并、标识重挂、主索引指针映射、读侧经 EMPI 解析收敛到主档，患者历史数据在主档下一处可见），业务明细历史记录不物理改写 patient_id；依据为拆分可逆性要求（FU-M02-03 同时要求拆分）与医疗记录不可篡改原则，见方案 3.3。

### v1.1 统一审查修订记录（Round 1，依据 90-cross-review.md 裁决）

| ID | 修订点 | 裁决依据 |
| --- | --- | --- |
| M-1 / R1-03 | §4 merge_record 的 (merged_patient_id) 唯一约束改为**部分唯一约束**（仅 status 为 PROCESSING/COMPLETED 时生效）；§10 边界补"拆分后再次合并"测试用例 | 90 号文档 M-1：原全量唯一约束使从档拆分后无法再次合并，永久阻断"合并→拆分→再合并"循环；裁决二选一中采用部分唯一方案（无需引入合并序号） |
| M-25 | §7 `patient.patient.merged` / `patient.patient.frozen` 发布登记补**成对语义**注记：订阅方模块必须成对登记 `patient.patient.split` / `patient.patient.unfrozen`，成对关系由本模块在 event_registry 登记时统一标注 | 90 号文档 M-25：`patient.patient.split` 与 `patient.patient.unfrozen` 曾零订阅（拆分逆映射与冻结解除无人消费），裁决"成对登记" |
| （备案确认） | §12 首段"合并细化为读侧归一"澄清已经统一审查终审采纳（90 号文档第 4 节备案终审 #26，与 B-2 修复一致），本模块维持该口径 | 90 号文档第 4 节 #26 |
