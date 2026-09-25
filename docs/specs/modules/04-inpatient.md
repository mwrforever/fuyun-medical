# M04 住院管理 · 功能实现 Spec

| 属性 | 内容 |
| --- | --- |
| 模块编号 | M04 |
| Maven 模块 | `fuyun-inpatient`（schema：`inpatient`） |
| 版本 / 状态 | v1.1 / 统一审查修订（修订记录见 §12） |
| 上游依赖 | M01（认证/权限/执业授权校验/字典/参数/审计/通知/打印，注册 M01"组织停用前置校验"SPI[床位占用查询实现，接口由 M01 定义]）、M02（patient_id 与 EMPI、健康档案过敏项、visit_id 结构规范）、M09（住院病历编辑能力嵌入、文书时限提醒）、M13（住院计价/停费/押金/一日清单/出院结算、费用与结算事件）、M06（用药医嘱药师审核、摆药/出院带药）、M20（事件总线治理、幂等构件、延迟队列） |
| 下游被依赖 | M05（医嘱转抄事件与执行计划、执行回签回流、病区患者列表）、M06（用药/出院带药医嘱分发）、M07/M08/M10/M12（检验/检查/手术/输血医嘱分发）、M13（visit_id 签发与计价停费事件源）、M14（住院 visit_id、入科/转科/出院事件驱动设备绑定解绑）、M09（住院病历文书 visit 上下文与入科/转科/出院事件）、M02（在途就诊查询 SPI 实现）、M18（入院预办/预约入院线上渠道复用）、M19（住院工作量与床位统计取数）、M20（事件契约登记方） |
| 对应总 Spec | FU-M04-01 ~ FU-M04-09 |

---

## 1. 模块定位与边界

**职责**：本模块是住院业务主线的中枢与全院临床医嘱闭环的发起方，九项职责：① 入院登记——住院证（入院通知单）登记、待入院队列（候床排序）、预约入院/预住院、入院手续办理与入科确认；② 床位管理——床位图、床位状态机（空床/预占/占床/消毒/维修）、占床/预占/包床、转科转床联动；③ 住院医生站——在院患者列表、医嘱开立编排入口、转科/出院医嘱动作；④ 医嘱开立——长期/临时医嘱、成组医嘱、嘱托（长期备用）、九类医嘱类型全集、医嘱重整；⑤ 医嘱审核与控制——开立校验、药师审核（调 M06）、停嘱/作废/撤销；⑥ 医嘱转抄与执行闭环——护士转抄核对、长期医嘱执行计划分解、执行回签聚合与全程追溯；⑦ 出院管理——出院申请、在途医嘱清理、费用预审联动、出院带药、出院结算联动放行、随访计划；⑧ 住院计费入口——医嘱事件驱动计费联动（计价权威在 M13）、手工计费入口、一日清单与押金的入口展示与欠费提醒；⑨ 会诊管理——院内会诊申请/响应/意见/超时升级闭环。本模块是**住院 `visit_id` 的唯一签发主体**（遵循 M02 结构规范，类型码 `I`），医嘱域权威状态（医嘱头/执行计划状态机）是本模块核心资产，医嘱事件是 M05/M06/M07/M08/M10/M12/M13/M14 的上游驱动源。

**非职责**：护理执行作业（执行单批量执行、PDA 扫码给药/输液/标本采集归 M05，本模块只做转抄核对与执行回签聚合）；处方调剂与药师审方规则（归 M06，本模块只发起审核请求与接收回执）；检验/检查/手术/输血的受理执行（归 M07/M08/M10/M12，本模块只按类型分发医嘱事件与聚合状态回执）；资金收退付、计价规则与医保结算（归 M13，本模块只提供住院侧业务入口与消费其事件）；病历文书内容（归 M09，医生站嵌入其编辑能力）；患者身份与档案（归 M02）；设备绑定与遥测（归 M14，本模块只发住院事件并引用其绑定查询）。

**模块红线**：
1. `visit_id` 只能由本模块按 M02 结构规范签发（类型码 `I` + 8 位日期 + 5 位当日流水，定长 14 位），在入院登记确认时点签发，签发后不可变、不可复用，必须与签发时点 `patient_id` 同时落库；其余任何模块不得生成住院就诊标识。
2. 医嘱状态是全院医嘱语义的唯一权威：医嘱状态迁移必须经状态机服务校验并留迁移日志（README 约定）；M05/M06/M07/M08/M10/M12/M13 对医嘱进度的感知一律以本模块事件与查询接口为准，禁止任何模块自行推导或改写医嘱状态。
3. 停嘱/作废/转科/出院的计费影响只通过事件通知 M13，本模块不直接写任何费用数据；费用、押金、结算的业务权威与金额计算全部在 M13（总 Spec 决策 D5），本模块工作站传入的金额字段不采信。
4. 离院（出院完成）前置校验强制：全部长期医嘱已至终态、在途执行计划清零、M13 费用预审通过（未结清走 M13 挂账审批，凭 `billing.arrears.approved` 转 READY 放行）；未满足条件禁止置 `DISCHARGED`。
5. 禁止跨模块读表；医嘱分发只经事件与接口，本模块不感知下游模块内部实现。

## 2. 调研依据

1. **医嘱生命周期官方定义**（医院管理研究所专利 CN101256603A）：医嘱生命周期=开立、处理（复核/查对/修改/重整）、执行、结果返回、误差监测、成本核算、停止的全过程电子记录；医嘱按医学属性/时间/内容分类；长期医嘱单与临时医嘱单分列；医嘱不必然收费（膳食、出院医嘱等不收费）；抢救口头医嘱须护士复诵、结束后据实补记。（来源：https://patents.google.com/patent/CN101256603A/zh ）
2. **医嘱分类与规范**：长期医嘱有效时间 24 小时以上、每日重复执行、医师注明停止时间后失效；长期备用医嘱（prn/嘱托）每执行一次需在临时医嘱中留记录；医嘱重整后将正在执行的长期医嘱按序重排。（来源：https://www.cnblogs.com/ChinaEHR/archive/2011/10/24/2222657.html 、https://www.cnblogs.com/ChinaEHR/archive/2011/10/24/2222680.html ）
3. **长期医嘱分解生产实践**：系统每天凌晨对长期医嘱自动生成次日待执行执行单，护士只需执行；分解异常（字典/项目缺失）置失败可见可处理；频次字典结构为"编码+名称+输入串+次数+执行时点序列"（如 bid=2 次/天:08,16）。（来源：https://his.wio2o.com/new/inpatient.php 、https://blog.csdn.net/weixin_33704591/article/details/91543093 ）
4. **医嘱拆分执行**：专利 CN109817289A 将医嘱数据自动解析并按诊疗项目和执行班次拆分形成医嘱执行表——医嘱头/明细与执行计划实例分层是行业通行建模。（来源：https://patents.google.com/patent/CN109817289A/zh ）
5. **转抄双人核对制度**：护士转抄医嘱按"三查七对"要求，每周双人核对全科医嘱，转抄与查对者均须签名；转抄标准化流程要求两名护士共同核对患者信息、剂量、频次、途径，高危药物重点复核。（来源：https://www.jdyy.cn/index.php?m=home&c=View&a=index&aid=612 、https://health.baidu.com/m/detail/ar_6384319980983802348 ）
6. **电子病历分级评价**：4 级要求全院信息共享与医疗业务闭环，6 级要求全流程医疗数据闭环管理——医嘱闭环（开立-审核-执行-停止全程可追溯）是评级硬性支撑。（来源：https://blog.csdn.net/oldfellow8312/article/details/134624921 、https://chima.org.cn/Sites_OldFiles/webedit/UploadFile/2018/1010/20181010064159543.pdf ）
7. **床位状态模型**：HIS 住院模块床位状态含空床、占用、预占、维修（消毒为出院/转出后的过渡态），调床/转科自动变更状态、避免重复占床。（来源：https://developer.aliyun.com/article/1757322 ）
8. **床位调度实践**：北京天坛医院"全院一张床"按本位区→顺位区→机动床优先级签床（机动床约占 30%）；深圳二医院"预住院"模式以虚拟床位先安排门诊检查再正式住院；出院前 48 小时自动保留虚拟床位的预留算法。（来源：https://www.btch.edu.cn/xxdt/mtbd/b7d9512b963740578ab27aea6913891e.htm 、https://wjw.sz.gov.cn/xxgk/gzdt/content/post_11415483.html 、https://www.eshutong.com/archives/46073.html ）
9. **入院流程**：医生开入院证/入院通知单 → 候床队列（床位预约）→ 预约通知与自助预办（信息填写、证件上传）→ 入院登记（押金/医保手续）→ 病区护士入科确认（分配床位）。（来源：https://www.bjcyh.com.cn/Html/News/Articles/65999.html 、https://www.gdghospital.org.cn/zhuyuan/info_itemid_43196.html 、https://www.btch.edu.cn/xxdt/ywgk/ywgkgzzd/70549.htm ）
10. **转科制度**：转科须经转入科会诊同意，转科前由经治医师开转科医嘱并写转科记录，护士通知住院处并按约定时间转科，转出科派人护送；行业规范要求 HIS 提供转科、批准出院、医嘱退药、催缴通知与费用清单功能。（来源：https://www.xinhuamed.com.cn/med/detail-179.html 、https://www.6thhosp.com/Content/News/2878 、https://zjt.hubei.gov.cn/IGI/upload/file/2023/02/10/2023021010473396716w.pdf ）
11. **出院流程**：主管医师提前告知出院时间、出院带药、去向；出院医嘱含出院注意事项、出院带药、随访计划；"预出院"实践——查房评估后开"明日出院"医嘱并预先整理出院文书；病案首页离院方式代码（1 医嘱离院等）。（来源：https://www.yantai.gov.cn/art/2022/9/8/art_79262_2997970.html 、https://20785173.s21i.faiusr.com/61/ABUIABA9GAAggM2w-AUo5NrX-AI.pdf 、https://wjw.fujian.gov.cn/xxgk/gzdt/mtbd/202502/t20250214_6714512.htm 、https://yygl.bjmu.edu.cn/yygl/ylws/5afa17ba52414366bee5f631ef3f2c7b.htm ）
12. **押金与欠费**：预交金充值退费、押金收据、流水台账；每日自动冲抵费用并实时欠费预警，弹窗提醒护士催收；押金下限可控（担保功能防误停）；病区欠费病人清单与催缴单是护士站标配。（来源：https://developer.aliyun.com/article/1757322 、https://www.sohu.com/a/1045875978_122151074 、https://www.jxhis.cn/?products_16/44.html ）
13. **会诊闭环**：会诊医嘱开立→提交会诊申请单→会诊安排→会诊通知→会诊签到→会诊意见提交→会诊结束；响应时限行业实践为急会诊 30 分钟、普通会诊 24 小时；官方规范要求会诊级别管理与主动干预。（来源：https://zfcg.henan.gov.cn/webfile/xuchang/rootfiles/2021/10/29/d62d1a11965a495c9a56faff7750e931.pdf 、https://zhuanlan.zhihu.com/p/461266796 、https://www.nhc.gov.cn/ewebeditor/uploadfile/2018/04/20180413162542120.pdf ）
14. **医嘱交互国标**：国家卫健委《医院信息平台交互标准第 8 部分：医嘱信息交互服务》规定医嘱信息消息模型与校验流程，本模块医嘱载荷契约对齐其语义要素。（来源：https://www.nhc.gov.cn/fzs/c100048/202411/463d427bef9c4c12a71eaee349d9f605/files/1733125981627_90057.pdf ）
15. **开源参考**：OpenHIS 开源医院系统住院域（入院登记、床位管理、住院医嘱、出院结算）与云 HIS 住院子系统功能清单，用于表结构与流程参照。（来源：https://gitee.com/tntlinking-opensource/openhis ）

## 3. 方案推导（关键设计点选型）

### 3.1 医嘱主模型：单表继承 vs 主子表（医嘱头+医嘱项）vs 单表+JSONB 明细

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 单表继承（类型字段+全部列可空） | 一条医嘱一行，用药/检验/检查等字段集全堆在一张宽表 | 九类医嘱字段差异极大（剂量途径滴速皮试 vs 标本容器 vs 检查部位），宽表稀疏列+可空约束使校验规则分散失效；成组医嘱（主药+溶媒多条计价单元）在单表内需行间自关联，行语义混乱；M13 计费行抽取无统一明细结构，每类医嘱都要写专门取数逻辑 |
| 单表+JSONB 明细 | 明细装 JSONB 字段 | 校验与约束最弱（剂量/途径无法用结构化约束兜底），计费行、执行计划、闭环统计都要解析 JSON，查询与对账成本高，违背"强事务一致性"基线 |
| **主子表：医嘱头+医嘱项（选定）** | medical_order（医嘱头）承载生命周期状态、类型、长期/临时、频次、开立信息；medical_order_item（医嘱项）承载可执行可计费明细（项目 code+数量+用法摘要+类型化扩展属性）；成组医嘱=组号+组内序号+组内延续标志在项上表达 | 与行业"医嘱头/明细/执行表分层"建模一致（调研依据 4、14）；计费行、执行计划、分发事件都有统一明细结构可引用；约束可结构化表达（药品项必填剂量途径、检验项必填标本类型）；头/项职责边界清晰，状态机只作用在头上，项随头状态联动 |

**结论**：主子表。成组医嘱三要素落点：`group_no`（组标识，默认等于本医嘱号，支持多组串联输注的扩展——后续组引用前组组号并延续通道）；`item_seq`（组内序号，决定排列与执行顺序）；`continue_flag`（组内延续标志：置真的项延续上一项的用法/途径/滴速，不重复开立——即"成组用法"的行业惯例表达）。嘱托（长期备用 prn）= 医嘱头上 `standby_flag`，仅长期医嘱可用。

### 3.2 长期医嘱执行计划生成：开立时全量预生成 vs 实时逐次生成 vs 日切批量分解+当日增量补偿

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 开立/审核时全量预生成 | 按频次一次性展开到停嘱日的全部执行计划 | 长期医嘱通常无预定停止时间（"医师注明停止时间后失效"是动态行为，调研依据 2），全量展开无终点；即使有终点，改频次/停嘱需批量回滚海量计划行，事务面不可控 |
| 实时逐次生成 | 到点即生成单次计划 | 护士执行单打印、批量核对无提前量；分解失败（字典缺失等）无缓冲窗口，到点才发现即漏执行 |
| **日切批量分解+当日增量补偿（选定）** | 每日夜切任务按长期医嘱频次解析（时点序列字典）生成**次日**全部执行计划实例（行业凌晨分解实践，调研依据 3）；当日新开长期医嘱在审核通过时即时补生成当日剩余时点计划；停嘱/作废即时作废未来计划；嘱托（prn）不预生成，护士按需触发单次计划并留执行台账；分解失败置异常清单可重跑 | 窗口固定一天，计划行规模可控（1000 床位×均 5 条长嘱×均 3 时点≈1.5 万行/日），停嘱回滚事务面小；执行单有整晚提前量供核对打印；分解异常夜内有整晚缓冲；与 M13 日切计价（长嘱持续性费用分解）共用同一频次解析口径，费用与执行计划天然对齐 |

**结论**：日切批量分解+当日增量补偿。频次解析的权威数据为 order_frequency 模块专业字典（编码/次数/时点序列/星期模式/prn 标志，调研依据 3 的结构），对齐 M01 院内字典 code 引用；执行计划实例（医嘱项×时点×执行日）持独立状态机，是 M05 生成执行单的唯一输入。

### 3.3 医嘱状态机与计费截断联动：执行域自管状态 vs 医嘱域集中状态机+事件驱动（选定）

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 执行域自管状态 | M05/M06/M07 等各自维护医嘱执行进度，医嘱状态由各方拼装展示 | 医嘱状态语义碎片化，全院闭环追溯无单一事实来源；M13 计价无法找到权威"审核/执行/停止"时点；评级要求的全程可追溯无从落地 |
| **医嘱域集中状态机+事件驱动（选定）** | 医嘱头状态机集中在本模块（全集见第 5 节），每一步迁移有唯一驱动方：开立（医生站）→审核（系统校验+药师 M06）→转抄（护士）→执行中（M05/M07/M08 执行回签回流聚合）→完成/停止；状态迁移即发布对应事件，M13 以事件时点驱动计价/停费/截断 | 每个迁移责任主体明确、留痕可审计（调研依据 1、6 的闭环与评级要求）；M13 契约（审核事件→计价、执行回签→费用确认、停嘱→停费）与本状态机一一映射；计费截断点=停嘱时点（`STOPPED` 迁移时间），未生成的执行计划同时作废，杜绝"人停了费还在涨" |

**结论**：集中状态机+事件驱动。合法迁移与驱动方全集：

| 迁移 | 驱动方 | 计费联动（M13 事件契约） |
| --- | --- | --- |
| CREATED → AUDITED | 系统自动校验（全员必经）+药师人工审核（用药类，经 M06）| 发布 `inpatient.order.audited` → M13 离散类即时计价 |
| CREATED → AUDIT_REJECTED | 药师审核驳回（M06 回执）| 发布 `inpatient.order.audit-rejected`，无计费 |
| AUDIT_REJECTED → CREATED | 医生修改后重新提交（留痕）| — |
| AUDITED → CREATED | 医生撤回/撤销审核（转抄前，权限控制）| 发布 `inpatient.order.revoked` → M13 作废对应未确认费用，重新审核通过后再计价 |
| AUDITED → TRANSFERRED | 护士转抄核对（双人，高危药强制）| — |
| AUDITED/TRANSFERRED → CANCELLED | 医生作废（未产生执行；TRANSFERRED 作废联动 M05 撤销未执行执行单）| 发布 `inpatient.order.cancelled` → M13 作废未确认费用 |
| TRANSFERRED → EXECUTING | 首个执行回签回流（长期医嘱）| — |
| TRANSFERRED → COMPLETED | 临时医嘱单次执行回签回流 | 发布 `inpatient.order.executed` → M13 离散类费用确认 |
| EXECUTING → COMPLETED | 全部执行计划实例终态 | — |
| AUDITED/TRANSFERRED/EXECUTING → STOPPED | 医生停嘱 / 转科自动停嘱 / 出院清理自动停嘱 | 发布 `inpatient.order.stopped` → M13 停费（截断未生成费用）+ 本模块作废 PENDING 执行计划 |

`COMPLETED/STOPPED/CANCELLED` 为终态；医嘱重整只重排视图并留痕，不改变任何医嘱状态。计费口径注：住院药费以 `inpatient.order.audited` 即时计价为唯一计价触发（权威在 M13），后续发药/摆药事件（M06）仅为执行占用与确认标记，不触发二次计费（与 M13 v1.1 口径一致）。

### 3.4 医嘱分类分发机制：同步分发器 vs 全类型广播自过滤 vs 统一事件+类型子路由键

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 同步分发器 | M04 内嵌分发组件，审核通过后按类型逐个同步调 M06/M07/M08/M10/M12 接口 | M04 与五个模块运行时强耦合，任一下游故障级联阻断医嘱闭环；开单高峰把下游压力直接传导为医生站卡顿，与总 Spec 4.3"闭环=异步事件链"结论冲突 |
| 全类型广播自过滤 | 单事件类型广播全部医嘱，各模块订阅后自行过滤类型 | 实现最简，但 M07 会收到全部用药/手术医嘱消息并丢弃，无效消费随医嘱量线性放大；队列积压互相拖累 |
| **统一事件+类型子路由键（选定）** | 事件类型统一为 `inpatient.order.audited`（及 stopped/cancelled/executed），routing key 携带类型子键（`inpatient.order.audited.drug|lab|exam|surgery|blood|diet|consult|discharge-med`；护理（nursing）类不经 audited 子键分发，经 `inpatient.order.transferred`/`inpatient.order-plan.generated` 事件链进入 M05，见下方分发目标映射）；目标模块按自身类型的子键绑定队列，M13 等全量消费方以通配子键绑定；载荷契约统一（医嘱头+项+患者/就诊上下文+计费行摘要） | Topic 交换机原生能力，路由层过滤零成本；新增医嘱类型只增子键不改总线；M04 不感知订阅方清单（订阅关系由各方在 event_registry 登记）；载荷统一使 M13 计价与各执行域解析同一契约 |

**结论**：统一事件+类型子路由键。分发目标映射：用药（drug）→M06 摆药/PIVAS、检验（lab）→M07 标本条码、检查（exam）→M08 预约、手术（surgery）→M10 手术申请、输血（blood）→M12 输血申请、护理（nursing）→M05（不经 audited 子键分发，经 `inpatient.order.transferred` 转抄/`inpatient.order-plan.generated` 执行计划事件链进入 M05 生成执行单与护理任务）、会诊（consult）→本模块会诊流程（不外发，转内部流程）、膳食（diet）→M13 计价联动（外部营养膳食系统对接经 M20 预留接口位）、出院带药（discharge-med）→M06（出院流程触发，见 FU-M04-07）。

### 3.5 床位与护理单元模型及转科联动时序：床位独立主数据+占用流水+四阶段转科编排（选定）

床位实体归属本模块（M01 只维护"病区"组织节点，床位为住院业务对象；M14 仅引用）。转科不是 visit 状态变更，而是"护理单元变更"编排，四阶段：**转科申请（医生开转科医嘱，目标病区）→ 转入科确认（会诊同意或确认权限）→ 预占目标床位 → 转移执行（编排事务）→ 入科确认**。转移执行编排时序（一个事务+一组事件）：

1. 转出病区全部长期医嘱自动置 `STOPPED`（停嘱时间=转科时间），发布 `inpatient.order.stopped` → M13 按转科时间线截断转出侧持续性费用（床位费/护理费切分归属，调研依据 10 转科医嘱制度）；
2. 在途处置三分规则：未执行完的**临时医嘱执行计划**保留并随患者归属新病区（M05 执行单重定向）；**待执行长期计划**随停嘱作废，由转入科按需重开；**在途费用**不改写，由 M13 日切按时间线切分归属；
3. 床位占用流水闭合（转出床置 `DISINFECTING` 消毒流转），visit 当前病区/床位原子更新；
4. 发布 `inpatient.visit.transferred` → M14 强制解绑设备（解绑中语义对齐 M14 Spec 绑定状态机）、M05 刷新病区患者列表、M13 记录费用归属切分点；
5. 入科确认（转入病区护士）→ 床位移交患者（`OCCUPIED`）→ M14 触发待绑定提醒。

**结论**：bed 独立主数据（含包床属性）+ bed_assign 只增占用流水（历史可溯，消毒/维修等状态切换全程留痕）+ 四阶段转科编排。同病区转床走同一编排的轻量路径（无医嘱停嘱步骤，仅床位切换与事件）。"全院一张床"跨病区签床（本位区→顺位区→机动床优先级，调研依据 8）作为入院队列分配与转科预占的调度规则参数化，不新增实体。

## 4. 领域模型

表设计统一遵循 README 第 3 节约定：雪花 BIGINT 主键、统一审计字段（created_by/created_at/updated_by/updated_at/deleted）、TIMESTAMPTZ 服务器时间、逻辑删、金额 BIGINT（分值制）、状态字段 VARCHAR 常量+迁移日志。

| 实体 | 关键字段 | 说明 |
| --- | --- | --- |
| admission 入院申请（住院证） | admission_no、patient_id、source_type（门诊转诊/急诊/体检/其他）、source_visit_id（门诊 visit_id 引用，转诊关联）、target_dept_id/target_ward_id、admission_type（普通/急诊/预住院）、expect_date、入院诊断摘要、开证医生、status（见状态机） | 待入院队列主体；住院证登记即建单；预住院类型对应深圳虚拟床位模式（调研依据 8） |
| inpatient_visit 住院就诊 | visit_id（14 位：I+yyyyMMdd+5 位流水，本模块签发）、patient_id、admission_id、current_dept_id/current_ward_id/current_bed_id、attending_doctor_id、nursing_level（护理级别）、insurance_type、入院诊断、registered_at（签发）/admitted_at（入科）/discharge_requested_at/discharged_at、离院方式（病案首页代码）、status（见状态机） | 就业主实体；转科/转床仅变更 current_ward/current_bed 并留事件，不改状态 |
| bed 床位 | bed_no、ward_id、bed_attr（普通/包床/加床）、allow_gender、当前占用 visit_id（冗余，权威在 bed_assign）、status（见状态机） | 病区床位图数据源；包床为计费属性标记非状态 |
| bed_assign 床位占用流水 | bed_id、visit_id、assign_type（入院分配/转床/转科转入）、started_at/ended_at、operator | 只增表；床位历史占用回溯依据；ended_at 由转移/出院动作闭合 |
| medical_order 医嘱头 | order_no、visit_id、patient_id、order_type（drug/lab/exam/surgery/blood/nursing/diet/consult/discharge-med）、order_class（long/stat）、standby_flag（嘱托 prn）、group_no、freq_code、begin_at/end_at、开立医生/时间、审核引用、重整引用、状态（见状态机）、stop_reason | 生命周期与分发主体；重整引用指向重整时点记录 |
| medical_order_item 医嘱项 | order_id、item_seq（组内序号）、continue_flag（组内延续）、item_type（药品/诊疗项目/材料/标本容器）、item_code、名称快照、剂量/单位、给药途径、滴速、数量、执行科室、皮试标志、类型化扩展属性、计费回执标记（已计价/已停费，对账用） | 可执行可计费明细；皮试阳性结果经 M02 API 回写过敏史（对齐 M03 同款契约） |
| order_frequency 频次字典（模块专业字典） | freq_code、freq_name、times_per_day、time_points（时点序列）、week_pattern、prn_flag | 结构化解析依据（调研依据 3）；code 引用 M01 院内字典并挂接结构化时点，引用关系在本 schema 维护（对齐 M14 专业字典先例） |
| order_execute_plan 执行计划实例 | plan_no、order_id、order_item_id、visit_id、ward_id、plan_time（计划执行时间）、shift（班次）、执行回签引用（执行人/执行时间/途径核对结果）、状态（PENDING/EXECUTED/CANCELLED） | M05 生成执行单的唯一输入；回签由 M05 调本模块回签接口驱动；停嘱/作废联动置 CANCELLED |
| order_transfer_log 转抄记录 | order_id、转抄护士、转抄时间、核对结论、第二核对人（双人核对，高危药强制） | 转抄环节留痕（调研依据 5） |
| order_audit 审核记录 | order_id、audit_stage（系统校验/药师审核）、审核方引用（M06 审核单）、结论、理由、occurred_at | 每医嘱多条；药师审核单据本体在 M06，此处存引用与结论 |
| order_status_log 医嘱状态迁移日志 | order_id、from_status/to_status、reason、operator、occurred_at | 只增；README 状态迁移留痕要求；重整、撤回、停嘱原因在此留痕 |
| consultation 会诊 | consult_no、visit_id、patient_id、order_ref（会诊医嘱引用）、申请科室/医生、目标科室/会诊人、urgency（急会诊/普通会诊）、请求时间、响应时限（急 30min/普 24h，参数可配）、响应时间、会诊时间、会诊意见、overdue_flag（超时未响应动作标记，响应后清除）、状态（见状态机） | 急会诊时效对齐行业实践（调研依据 13） |
| discharge_request 出院申请 | request_no、visit_id、申请人（医生）、申请时间、预出院时间、离院方式、在途清理结果（长期医嘱停嘱/临时医嘱追踪清单）、费用预审引用（M13）、带药医嘱引用、status（见状态机） | "预出院/明日出院"实践载体（调研依据 11）；预审不通过原因留痕 |
| follow_up_plan 随访计划 | visit_id、patient_id、plan_date、方式（电话/公众号/复诊）、内容摘要、status（待执行/已完成/已取消） | 出院医嘱三要素之一（注意事项/带药/随访，调研依据 11）；触达经 M01 通知中心 |

关系要点：admission 1:0..1 inpatient_visit；patient（M02）1:N admission/inpatient_visit；bed 1:N bed_assign（至多一条未闭合）；inpatient_visit 1:N medical_order 1:N medical_order_item / order_execute_plan / order_transfer_log / order_audit / order_status_log；inpatient_visit 1:0..1 discharge_request 1:N follow_up_plan；inpatient_visit 1:N consultation。

## 5. 状态机与业务流程

- **admission**：`WAITING（待入院，候床队列）→ SCHEDULED（已预约/预住院检查中）→ COMPLETED（已办理入院登记，签发 visit_id，终态）`；`WAITING/SCHEDULED → CANCELLED（作废，终态）`；`SCHEDULED → WAITING（逾期未入院回队列重排）`。队列排序规则参数化（急诊优先/预约时段/候床时长）。
- **inpatient_visit**：`REGISTERED（已登记待入科）→ ADMITTED（在院，入科确认）→ DISCHARGE_REQUESTED（出院申请中）→ DISCHARGED（已出院，终态）`；`REGISTERED → CANCELLED（登记作废，终态）`；`DISCHARGE_REQUESTED → ADMITTED（取消出院申请回在院）`。转科/转床为 ADMITTED 内属性变更（发 `inpatient.visit.transferred`），独立于状态机。
- **bed**：`FREE（空床）⇄ RESERVED（预占：预约入院/转科预占/全院签床）→ OCCUPIED（占床，入科/分配确认）`；`FREE → OCCUPIED（直接分配）`；`OCCUPIED → DISINFECTING（消毒，转出/出院后）→ FREE（消毒完成确认）`；`FREE ⇄ MAINTENANCE（维修）`。包床为 bed_attr 属性标记，不占用状态位。
- **medical_order**（医嘱状态全集，第 3.3 节迁移表为准）：`CREATED（已开立，待审核）→ AUDITED（审核通过，待转抄）→ TRANSFERRED（已转抄）→ EXECUTING（执行中）→ COMPLETED（已完成，终态）`；侧支：`CREATED ⇄ AUDIT_REJECTED（驳回↔修改重提）`；`AUDITED → CREATED（撤回重审）`；`AUDITED/TRANSFERRED → CANCELLED（作废，终态）`；`AUDITED/TRANSFERRED/EXECUTING → STOPPED（停嘱，终态）`。每次迁移留 order_status_log 并发布对应事件。
- **order_execute_plan**：`PENDING（待执行）→ EXECUTED（已执行回签）`；`PENDING → CANCELLED（停嘱/作废/出院清理联动）`。PENDING 超期未回签触发提醒动作（延迟消息），不新增状态。
- **consultation**：`REQUESTED（已申请，待响应）→ ACCEPTED（已响应）→ COMPLETED（已完成，终态）`；`REQUESTED/ACCEPTED → CANCELLED（取消，终态）`。超时不设独立状态：`fy.delay` 延迟队列到期未响应置 overdue_flag（升级动作——重复通知目标科室+升级上报医务），状态停留 REQUESTED，仍可被响应回 ACCEPTED（响应时清除 overdue_flag）；对齐 M14"升级为动作非状态"同款设计。
- **discharge_request**：`REQUESTED（已申请，在途清理与费用预审中）→ READY（预审通过，待结算离院）→ COMPLETED（离院完成，终态）`；`REQUESTED → BLOCKED（预审未通过/欠费挂账审批中，附原因；处理后回 REQUESTED，或凭 M13 `billing.arrears.approved`（挂账审批通过）事件转 READY）`；`REQUESTED → CANCELLED（取消出院，visit 回 ADMITTED）`。

主流程时序：

1. **入院主链路**：门诊/急诊医生开住院证（M03 转诊引用门诊 visit_id）→ 入院登记台建 admission（候床队列）→ 床位预约（bed 置 RESERVED，队列排序）→ 通知患者 → 入院登记确认（校验患者可用性经 M02 解析、医保类型登记，**同事务签发 I 型 visit_id**、发布 `inpatient.visit.registered` 供 M13 医保入院办理登记）→ 病区护士入科确认（床位 RESERVED→OCCUPIED、护理级别登记）→ 发布 `inpatient.visit.admitted` → M14 触发设备待绑定提醒、M05 入组病区患者列表。
2. **医嘱闭环主链路**：医生站开医嘱（校验执业授权/过敏史/剂量重复）→ `CREATED` → 审核环节（用药类调 M06 药师审核，其余系统校验即过）→ `AUDITED`，发布 `inpatient.order.audited.<type>` → M13 计价 + 类型域受理（M06 摆药单/M07 标本条码/M08 预约/M10 排程/M12 配血）→ 护士站转抄核对（双人）→ `TRANSFERRED`，发布 `inpatient.order.transferred` → M05 生成执行单（护理类医嘱经转抄/执行计划事件链进入 M05，不经 audited 子键分发）；长期医嘱由日切分解出次日 `order_execute_plan`（发布 `inpatient.order-plan.generated`）→ PDA 执行回签调本模块回签接口 → 计划 `EXECUTED`、医嘱头推进 → 发布 `inpatient.order.executed` → M13 费用确认、医生站可见执行状态；停嘱（医生/转科/出院自动）→ `STOPPED`，发布 `inpatient.order.stopped` → M13 停费 + M05 撤销未执行执行单。
3. **转科编排**：见第 3.5 节五步时序。
4. **出院主链路**：医生评估后开出院医嘱并提交出院申请（"明日出院"预出院模式）→ `REQUESTED` → 在途清理（长期医嘱批量停嘱、临时医嘱追踪、未执行计划作废）→ 调 M13 费用预审 → 预审通过 `READY` / 欠费未清 `BLOCKED`（走 M13 挂账审批，凭 `billing.arrears.approved` 转 READY）→ 患者缴费结算（M13 出院结算，多退少补）→ 消费 `billing.settlement.completed` + 人工确认离院 → visit 置 `DISCHARGED`、床位转 `DISINFECTING`、发布 `inpatient.visit.discharged` → M14 强制解绑设备、M19 统计入池；出院带药医嘱在出院确认时放行 M06 摆药；随访计划生成并经 M01 通知触达。

## 6. 功能实现设计（逐 FU）

| FU | 实现设计要点 |
| --- | --- |
| FU-M04-01 入院登记（P0） | 住院证登记（来源含 M03 门诊转诊——记录住院证开具时间并引用门诊 visit_id 建立转诊关联、急诊直接登记）；待入院队列（按急诊优先/候床时长参数排序，支持全院一张床跨病区签床：本位区→顺位区→机动床优先级规则，调研依据 8）；预约入院（通知+自助/线上预办信息预填，渠道经 M18 复用）；预住院模式（无床时虚拟登记先完成入院前检查，床位到位转正式，调研依据 8）；入院登记确认同事务签发 I 型 visit_id（红线 1）、登记医保类型；入科确认（床位分配、护理级别、发布 admitted）；登记作废（未入科）回收预占床位 |
| FU-M04-02 床位管理（P0） | 病区床位图可视化（状态色标：空床/预占/占床/消毒/维修+包床标记，调研依据 7）；占床/预占/包床/换床操作全留痕；床位状态机第 5 节全集，避免重复占床由状态条件更新兜底；转科转床按第 3.5 节编排（在途医嘱/在途计划/在途费用三分规则，费用归属切分点交 M13）；消毒完成确认回 FREE；维修与恢复；床位使用率/周转统计供 M19 |
| FU-M04-03 住院医生站（P0） | 在院患者列表（本医生管床维度+病区维度，含护理级别/欠费标识/过敏标识缓存）；病程记录嵌入 M09 编辑能力（本模块传 visit 上下文，内容归 M09）；诊断维护引用 M01 ICD 字典；医嘱开立入口（FU-M04-04）；转科/出院医嘱动作；检查检验结果查看调 M07/M08；开立前强校验 M01 执业授权（处方权/抗菌药分级，调 `POST /api/v1/system/practice/check`） |
| FU-M04-04 医嘱开立（P0） | 主子表模型（方案 3.1）：长期/临时（order_class）、嘱托（standby_flag，仅长期可用）、九类 order_type 全集；成组医嘱（组号/组内序号/组内延续标志）与成组用法；开立校验四层：执业授权→过敏史与禁忌（M02 健康档案缓存）→剂量/途径/重复用药规则（用药类联动 M06 合理用药规则）→项目/频次字典有效性；频次引用 order_frequency 结构化时点；医嘱模板（个人/科室常用套）加速录入；医嘱重整（转科/病危/手术节点重排视图+留痕，不改数据）；开立即提交并记 `CREATED`，医嘱内容不可直接改，修改=作废重开留痕（对齐"医嘱不得涂改"，调研依据 1） |
| FU-M04-05 医嘱审核与控制（P0） | 系统自动审核全员必经（校验结果入 order_audit）；用药医嘱药师人工审核经 M06（发布审核请求，消费 M06 回执驱动状态迁移，事件名以 M06 Spec 登记为准），审方不通过即 `AUDIT_REJECTED` 附理由；停嘱（医生主动/转科/出院自动，停嘱时间=服务器时间，联动计划作废与 M13 停费）；作废（仅未产生执行，TRANSFERRED 作废联动 M05 撤执行单与 M13 作废费用）；撤销（转抄前撤回重编，需相应权限并留痕）；抢救口头医嘱补录通道（标记口头补记+事后限时确认，调研依据 1） |
| FU-M04-06 医嘱转抄与执行闭环（P0） | 转抄工作台（待转抄列表按病区/班次）；转抄核对双人签名（高危药/输血强制第二核对人，调研依据 5）；转抄后发布 `inpatient.order.transferred` 供 M05 生成执行单；临时医嘱转抄时同步生成单次计划实例（与 M05 执行单生成规则对齐）；长期医嘱执行计划日切分解+当日增量补偿（方案 3.2），发布 `inpatient.order-plan.generated`；执行回签接口供 M05 调用（计划置 EXECUTED、医嘱头推进、发布 `inpatient.order.executed`）；执行超时提醒（计划时间+阈值仍 PENDING 经 fy.delay 触发护士站/长班提醒）；闭环追溯视图（单条医嘱开立→审核→转抄→执行→停止全环节人/时/果一屏可溯，评级 4 级支撑，调研依据 6） |
| FU-M04-07 出院管理（P0） | 出院申请（预出院模式支持"明日出院"，调研依据 11）；在途清理编排（长期医嘱批量停嘱→临时医嘱逐条追踪→未执行计划作废，清理结果入 discharge_request）；费用预审调 M13（预审通过 READY/欠费 BLOCKED 挂账审批，对齐 M13 契约③）；出院带药（discharge-med 类医嘱在出院确认时点放行 M06 摆药，取药完成后方可离院确认）；出院结算联动（患者经 M13 结算，本模块消费 `billing.settlement.completed` 后允许离院确认）；离院确认（visit→DISCHARGED、床位转消毒、发布 discharged，离院方式按病案首页代码记录）；随访计划生成与到期触达（经 M01 通知中心）；取消出院申请回在院 |
| FU-M04-08 住院计费（P0） | 计价权威在 M13，本模块四类入口与联动：① 医嘱联动自动计费——审核/执行回签/停嘱/转科/出院申请事件按第 3.3 节契约发 M13（计费唯一性、重复拦截由 M13 计价唯一键兜底）；② 手工计费入口（病区补录耗材/评估类费用，转调 M13 手工计费 API，操作者与理由强制）；③ 一日清单（调 M13 `GET /daily-lists` 于工作站/床旁屏展示，患者端经 M16/M01 通道取数）；④ 押金管理（缴存/退回入口转调 M13；消费 `billing.deposit.changed` 驱动欠费提醒：护士站弹窗（WebSocket）+责任护士/主管医生站内信+病区欠费清单与催缴单，调研依据 12；默认仅提醒不自动停药，押金下限阈值参数化，担保患者白名单免提醒） |
| FU-M04-09 会诊管理（P1） | 会诊申请（独立发起或经会诊医嘱 type=consult 转内部流程）；紧急程度分级（急会诊 30min/普通会诊 24h 响应时限，参数可配，调研依据 13）；响应（目标科室接单 `ACCEPTED`）；会诊意见提交 `COMPLETED`；超时升级（fy.delay 延迟队列到期未响应置 overdue_flag 动作——重复通知目标科室+上报医务值班，状态停留 REQUESTED 仍可被响应，对齐"主动干预"要求）；会诊记录归档供 M09 病历引用；会诊级别管理（科内/院内/多学科 MDT 预留字段） |

## 7. 对外接口

**REST（`/api/v1/inpatient/` 前缀，节选）**：
- 入院：`POST /admissions`（住院证登记）、`GET /admissions?status=`（候床队列）、`POST /admissions/{no}/schedule`（预约入院/预住院）、`POST /admissions/{no}/cancel`、`POST /admissions/{no}/register`（入院登记，签发 visit_id）、`POST /visits/{visitId}/admit-ward`（入科确认）
- 床位：`GET /beds/map?wardId=`（床位图）、`POST /beds/{id}/reserve|assign|release|disinfect-done|maintain|maintain-done`、`POST /visits/{visitId}/transfer`（转科）、`POST /visits/{visitId}/change-bed`（转床）
- 医嘱：`POST /visits/{visitId}/orders`（开立）、`POST /orders/{no}/cancel|stop|revoke-audit`、`POST /orders/reorganize`（重整）、`GET /orders?visitId=&class=`、`GET /orders/{no}/trace`（闭环追溯）
- 转抄与执行：`GET /transfer-worklist?wardId=`、`POST /orders/transfer-check`（批量转抄核对）、`POST /order-plans/{no}/execute-confirm`（M05 执行回签）、`GET /order-plans?date=&wardId=`、`POST /order-plans/standby-trigger`（嘱托按需触发单次计划）
- 出院：`POST /visits/{visitId}/discharge-request`、`POST /discharge-requests/{no}/cancel`、`POST /discharge-requests/{no}/confirm`（离院确认）、`GET /discharge-requests/{no}/clearance`（在途清理与预审结果查询）
- 会诊：`POST /consultations`、`POST /consultations/{no}/accept|opinion|cancel`、`GET /consultations?status=&deptId=`
- 计费入口：`POST /manual-fees`（手工计费，转调 M13）、`GET /daily-list?visitId=&date=`（转调 M13）、`POST /deposits`（转调 M13）、`GET /deposit-alarms?wardId=`（欠费清单）

**CF-6 占位行注记（P1 PR-6，2026-09-22 批复条件 2）**：P2 实装 `POST /order-plans/{no}/execute-confirm` 时，须以彼时更高版本迁移 UPDATE `integration.event_registry` id 55 的 payload_desc 补齐字段级契约（P1 PR-6 已约定名占行，见 05-nursing Spec「P1 切片落地注记」第 12 条）。

**内部服务接口（进程内）**：在途就诊查询实现（注册为 M02 SPI 扩展点：在院 visit 存在时阻断患者合并）；医嘱状态与执行进度查询（供 M05/M13/M19 取数，只读）；执行回签聚合（供 M05）。

**MQ 事件（发布，经 `fy.topic`，信封遵循 M20 治理约定，登记 event_registry；医嘱类事件 routing key 携带类型子键，见方案 3.4）**：
- `inpatient.visit.registered`（入院登记，visit_id 已签发，M13 医保入院办理依据）
- `inpatient.visit.admitted`（入科确认，M14 设备待绑定提醒、M05 病区列表依据）
- `inpatient.visit.transferred`（转科/转床完成，载荷含 transfer_type/前后病区床位/时间线，M13 费用归属切分、M14 强制解绑依据）
- `inpatient.visit.discharge-requested`（出院申请，M13 费用预审与停止计费依据）
- `inpatient.visit.discharged`（已出院，M14 强制解绑、M19 统计依据）
- `inpatient.order.created`（医嘱开立）
- `inpatient.order.audited`（审核通过，M13 计价与 M06/M07/M08/M10/M12 分发依据）
- `inpatient.order.audit-rejected`（审核驳回）
- `inpatient.order.revoked`（审核撤回，转抄前撤回重编，M13 作废未确认费用依据）
- `inpatient.order.transferred`（转抄完成，M05 生成执行单依据）
- `inpatient.order-plan.generated`（长期医嘱执行计划分解完成，M05 执行单批量生成依据）
- `inpatient.order.executed`（执行回签聚合，M13 费用确认与医生站展示依据）
- `inpatient.order.stopped`（停嘱，M13 停费与 M05 撤单依据）
- `inpatient.order.cancelled`（作废，M13 费用作废/退费逆向与各受理方撤单依据）
- `inpatient.consultation.requested` / `inpatient.consultation.accepted` / `inpatient.consultation.completed` / `inpatient.consultation.overdue`（超时升级动作广播，状态停留 REQUESTED）/ `inpatient.consultation.cancelled`（会诊闭环）
- `inpatient.bed.changed`（床位状态变更广播，病区屏/大屏刷新依据）

**MQ 事件（订阅，全部经 M20 幂等构件消费）**：
- `billing.deposit.changed`（M13：欠费提醒驱动与出院放行校验数据源）
- `billing.settlement.completed`（M13：出院结算完成放行离院确认）
- `pharmacy.medication-order.audit-completed`（M06 药师审核通过回执，驱动医嘱 CREATED→AUDITED 迁移）/ `pharmacy.medication-order.audit-rejected`（审核驳回回执，必附药师意见，驱动医生站修改后重提路径；载荷 target=m04_order_no，与 M06 Spec 登记对齐）
- M05 护理执行回执（执行单完成回执，与 PDA 直调回签接口双路对账；事件名以 M05 Spec 登记为准）
- M07/M08/M10/M12 受理与完成回执（检验/检查/手术/输血医嘱状态回执聚合；事件名以各 Spec 登记为准）
- `system.dict.published` / `system.org.changed` / `system.user.changed` / `system.param.changed` / `system.practice.changed`（M01 主数据广播：频次/途径字典、病区组织、执业授权缓存刷新）
- `iot.alarm.*`：**本模块不消费**（设备告警业务闭环归 M05/M16，M04 仅在转科/出院时经事件触发 M14 解绑，不直接消费告警）

**WebSocket**：`/ws/inpatient/ward/{wardId}`（护士站实时主题：欠费预警、新医嘱/停嘱提醒、床位图变更、出院申请通知；刷新 ≤2s）。

## 8. 集成点

- **M01**：认证与 RBAC（开立/审核/转抄/停嘱/出院/手工计费/床位管理独立权限点）；执业授权强校验（开医嘱前）；频次/途径/费别等院内字典与 ICD 诊断字典引用（不自建副本）；通知中心（欠费催缴、会诊超时升级、随访触达、床位通知）；打印模板（腕带/执行单/催缴单/出院带药单）；审计切面；注册 M01"组织停用前置校验"SPI（床位占用查询实现，接口由 M01 定义——存在在院床位占用时阻断组织停用）。
- **M02**：一切业务以 `patient_id` 关联，登记前经解析服务核验（FROZEN/MERGED 拒绝）；visit_id 按 M02 结构规范签发（类型码 `I`，本模块唯一签发主体）；过敏史读取用于开立校验，皮试阳性经其 API 回写；**注册 M02"在途就诊查询"SPI 实现**（在院状态阻断患者合并）。
- **M13（住院计费契约，双向对齐其 Spec 第 8 节）**：① 本模块医嘱审核（audited）、审核撤回（revoked）、执行回签（executed）、停嘱（stopped）、作废（cancelled）、转科（transferred）、出院申请（discharge-requested）事件 → M13 计价/费用确认/停费/作废/预审/归属切分（事件契约）；② M13 提供费用查询、押金缴存、一日清单、出院结算 API 供本模块工作站调用（同步 API 契约）；③ `billing.deposit.changed` → 本模块欠费提醒与出院放行校验；`billing.arrears.approved`（M13 挂账审批通过事件）→ discharge_request `BLOCKED→READY`（BLOCKED 挂账审批路径的唯一放行驱动）；④ `billing.settlement.completed` → 离院确认放行。
- **M05（转抄后执行归 M05）**：本模块完成医嘱审核与转抄核对（医嘱域），发布 `inpatient.order.transferred` 与 `inpatient.order-plan.generated`；M05 据此生成执行单、批量执行、PDA 扫码执行（执行域），执行完成调本模块执行回签接口（或回执事件回流，双路对账）；转科时执行单重定向由 M05 依据 transferred 事件处理；责任护士分配归 M05（FU-M05-01），本模块入科确认不越界。
- **M06**：用药医嘱药师审核（请求-回执）；drug 类医嘱分发→摆药/PIVAS；discharge-med 类→出院带药摆药；审方规则、毒麻管理均归 M06。
- **M07/M08/M10/M12**：lab/exam/surgery/blood 类医嘱经 `inpatient.order.audited` 类型子键分发（M07 生成标本条码、M08 检查预约、M10 手术排程、M12 输血申请）；其受理/完成回执回流聚合至闭环追溯；执行占用校验（退费场景）由 M13 前置调用其查询 API，本模块不重复建设。
- **M09**：住院病历文书（入院记录/病程/转科记录/出院小结）在 M09 书写，本模块提供 visit 上下文与转科/出院事件触发文书时限提醒素材；会诊意见归档供病历引用。
- **M14**：本模块是 `inpatient.visit.admitted/transferred/discharged` 的发布方（M14 Spec 已声明订阅，驱动设备待绑定提醒/强制解绑/数据归属切换）；床位有效性校验经本模块接口；设备当前绑定查询调 M14（转科编排只发事件不直查绑定）。
- **M03**：急诊转住院边界——M03 记录住院证开具时间与去向代码，本模块 admission 引用其门诊 visit_id 建立转诊关联，两 visit 各自独立。
- **M18**：预约入院/预住院的线上预办渠道（信息预填、床位通知）复用本模块入院服务 API，业务落本模块，渠道标记为线上。
- **M19**：床位使用率、平均住院日、医嘱工作量等统计取数 API（只读）。
- **M20**：事件信封/交换机/队列治理、幂等构件、延迟队列（`delay.consultation-timeout` 会诊超时、`delay.order-execute-remind` 执行超时提醒）、出站留痕；本模块全部事件先登记 event_registry。

## 9. 非功能与安全

- 性能：医嘱开立 P95 < 500ms；转科编排端到端（申请确认→事件发布）< 3s；长期医嘱日切分解（全院约 1.5 万计划行/日）在夜间低峰 30 分钟内完成、分批提交可断点续跑；闭环追溯查询 P95 < 1s；护士站 WebSocket 推送 ≤ 2s；审核事件到 M13 费用生成端到端 ≤ 3s。
- 可用性：医嘱开立/转抄/停嘱 7×24（总 Spec 核心可用性）；M13 不可用时开单与转抄可继续（计价事件积压重放），押金/结算入口显式提示降级；日切分解任务失败可重跑且幂等（计划唯一约束：医嘱项×计划时点）。
- 一致性：医嘱状态迁移与事件发布走 outbox（M20 治理约定），订阅方幂等；visit 签发与 admission 状态同事务；bed 状态条件更新防重复占床；床位占用流水与 bed 冗余字段每日对账；执行计划与 M05 执行单经回签接口+回执事件双路对账，差异进异常清单。
- 审计：开立、审核、驳回、撤回、停嘱、作废、转抄、转科、出院确认、手工计费入口全量审计（M01 审计切面）；医嘱状态迁移日志独立留痕（order_status_log 只增）。
- 权限：医生（开立/停嘱/出院申请）、护士（转抄/回签/床位）、药师（审核经 M06）、医务（会诊升级）分权；跨病区数据按 M01 数据范围隔离；嘱托触发、抢救补录确认独立权限点。
- 合规映射：电子病历分级评价 4 级医嘱闭环（全程可追溯，调研依据 6）；医嘱内容"不得涂改、取消留痕"（调研依据 1，修改=作废重开）；双人核对制度（调研依据 5）；病案首页离院方式代码（调研依据 11）；等保三级（审计/TLS/敏感字段脱敏——患者姓名/诊断在日志与事件载荷脱敏）；医嘱交互国标语义要素对齐（调研依据 14）。
- 数据留存：医嘱与状态迁移日志随病历长期保留（≥15 年，对齐病历档案要求）；执行计划与转抄记录在线 ≥2 年后归档；床位占用流水长期保留（占用回溯依据）。

## 10. 测试要点

- 正常：入院全链路（住院证→候床→预约→登记签发 visit_id→入科→事件核对）；用药医嘱闭环（开立→药师审核→转抄双人→摆药→PDA 回签→费用确认→停嘱停费）；长期医嘱日切分解数量与频次时点一致、审核通过当日增量补偿正确；转科编排五步时序各事件与床位状态核对；出院全链路（申请→在途清理→预审→结算→放行→床位消毒→设备解绑）；会诊 30 分钟急会诊响应闭环。
- 边界：停嘱恰在日切分解前后两个窗口的计划作废与费用截断正确；转科瞬间未执行临时计划随患者转移而长期计划作废；医嘱审核通过与撤回并发（先到先得，撤回后转抄被拒）；出院预审不通过 BLOCKED→挂账审批→人工放行路径；嘱托（prn）多次触发生成多次台账且不重复计价（M13 唯一键兜底）；床位消毒中禁止分配；取消出院申请回在院后长期医嘱不复活（需重新开立）；SCHEDULED 逾期自动回队列。
- 异常：`inpatient.order.audited` 重复投递仅计价一次（幂等）；M06 审核回执丢失→对账发现并人工补偿；日切分解失败（字典缺失）进异常清单可重跑且不阻塞其他医嘱；转科编排中途失败回滚（床位/医嘱/visit 原子恢复）；执行回签与回执事件双路到达仅计一次；M13 不可用时开单继续、事件积压恢复后补投。
- 安全：无处方权医生开立被拒（M01 执业授权校验）；跨病区护士转抄他区医嘱 403 且留审计；高危药单核对缺第二人被拦截；医嘱修改请求（非作废重开路径）被拒；日志与事件载荷患者敏感字段脱敏抽验；作废他人医嘱越权 403。

## 11. 自审记录

- [x] 无 TBD/TODO/占位符，13 项内容完整（文档头 + 12 节）
- [x] 覆盖 FU-M04-01~09 全部条目，无遗漏、无私增（预住院/全院一张床为 FU-M04-01/02 的调度规则细化，医嘱重整/抢救补录为 FU-M04-04/05 的合规细化，均非新功能点；FU-M04-09 按 P1 定位）
- [x] 内部一致：领域模型 ↔ 状态机 ↔ API ↔ 测试一一对应（admission/inpatient_visit/bed/medical_order/order_execute_plan/consultation/discharge_request 七个状态机均有对应接口、流程与测试项；order_frequency/bed_assign/order_transfer_log/order_audit/order_status_log/follow_up_plan 均有查询或操作路径与测试场景）
- [x] 符合跨模块约定：schema=inpatient；visit_id 按 M02 结构规范签发（类型码 I、14 位、入院登记时点、唯一签发主体）；金额不落地（权威在 M13）；事件命名 `<模块>.<实体>.<动作>`、信封合规、消费走 integration.received_event 幂等；REST 路径 `/api/v1/inpatient/`；字典只存 M01 code 引用（order_frequency 为模块专业字典，对齐 M14 先例并挂接 M01 code）；状态字段 VARCHAR 常量+迁移日志；无跨模块读表
- [x] 依赖方向正确：依赖 M01/M02/M13/M20 及 M06/M07/M08/M09/M10/M12/M14/M05 的对外接口与事件；对 M02 仅 SPI 扩展点注册（依赖倒置，M02 Spec 已预留）；注册 M01"组织停用前置校验"SPI（床位占用查询实现，接口由 M01 定义，B-1 裁决合法反向形态）；无反向依赖；被依赖清单明确
- [x] 方案推导 5 个关键点均有备选对比与依据，含任务要求的四个必选点（3.1 医嘱主模型与成组建模、3.2 执行计划生成、3.3 状态机与计费截断联动、3.4 分类分发机制），另含 3.5 床位与转科联动，每个结论附调研来源
- [x] 无代码级实现（无类名/方法体/SQL DDL 全文；表设计为"表-关键字段-约束"粒度；outbox/条件更新/雪花 ID 为技术机制描述非代码）
- [x] 歧义消除：医嘱修改语义（作废重开非原地改）、停嘱计费截断时点（STOPPED 迁移时间）、转科在途三分规则、嘱托执行台账语义、会诊超时为动作式升级（overdue_flag 置位、状态停留 REQUESTED，仍可被响应）、出院放行双条件（settlement.completed+人工离院确认）、执行回签双路对账（接口+事件）均已显式定义
- [x] 术语与总 Spec 一致（医嘱/长期医嘱/临时医嘱/嘱托/成组医嘱/转抄/停嘱/作废/转科/转床/一日清单/押金/出院带药/会诊）

## 12. 与总 Spec 的偏差

无结构性偏差。四处细化澄清（请统一审查裁决确认）：
1. **医嘱审核环节范围**：总 Spec FU-M04-05 表述"药师审核（调 M06）"，本 Spec 细化为"系统自动校验全员必经+用药类医嘱强制药师人工审核（M06）+其余类型可配置人工审核"，非用药类直接经系统审核通过，依据是审方规则仅覆盖用药域（FU-M06-07 定位）。
2. **包床语义**：总 Spec FU-M04-02 将包床与占床/预占并列，本 Spec 将包床建为床位**计费属性**（bed_attr）而非状态机状态——包床可与占床/预占并存表达，避免状态组合爆炸；床位状态机为空床/预占/占床/消毒/维修五态（消毒为任务要求的过渡态，维修为调研实践补充）。
3. **医嘱分发子路由键**：医嘱类事件 routing key 在事件类型后携带 order_type 子键（如 `inpatient.order.audited.drug`），事件类型登记名仍为 `inpatient.order.audited`，与 README 事件命名约定兼容，全量消费方（M13）以通配绑定。
4. **会诊医嘱的归属**：会诊医嘱（type=consult）审核通过后不外发分发，转入本模块会诊流程（FU-M04-09），consultation 实体与会诊事件在本模块闭环；多学科 MDT 仅预留级别字段，不在本期（总 Spec 无 MDT 独立条目）。

**v1.1 统一审查修订记录（依据 `docs/specs/modules/90-cross-review.md` 统一裁决执行）**：
- **R3-06**：护理（nursing）类医嘱分发口径修正——删除"audited.nursing 子键→M05 任务"悬空表述（M05 不订阅该子键），改为"护理类经 `inpatient.order.transferred`（转抄）/`inpatient.order-plan.generated`（执行计划）事件链进入 M05"（§3.4 方案表与分发目标映射、§5 主流程、§7 `inpatient.order.audited` 发布注记同步）。
- **R3-12**：会诊 OVERDUE 改为动作式——consultation 状态机删除"REQUESTED → OVERDUE"迁移，超时置 overdue_flag（动作），状态停留 REQUESTED、仍可被响应回 ACCEPTED（语义不变）；§5/FU-M04-09/§11/§7 事件注记同步，"对齐 M14 升级为动作非状态"表述与状态机的矛盾消除。
- **R3-14**：§11 自审"四个必选点后列五项"计数修正（3.1~3.4 为任务必选点，3.5 为自增关键点）。
- **FU-M04-06**：补"临时医嘱转抄时生成单次计划实例"显式表述（与 M05 执行单生成规则对齐）。
- **B-6**：§7 订阅 `pharmacy.audit.completed` 改为 `pharmacy.medication-order.audit-completed` / `pharmacy.medication-order.audit-rejected`（与 M06 已登记事件名逐字对齐；rejected 必附药师意见，驱动 CREATED 侧修改重提路径）。
- **M-10**：discharge_request `BLOCKED→READY` 明确由 `billing.arrears.approved`（M13 挂账审批通过事件）驱动；红线 4、§5 状态机与主流程、§8-③ 同步该事件名（M13 v1.1 已新增该实体与事件）。
- **M-4**：§3.3 补注住院药费口径——`inpatient.order.audited` 即时计价为唯一计价触发，发药/摆药事件仅为执行占用标记（与 M13 v1.1 口径一致）。
- **M-25 说明**：本模块未订阅 `patient.merged`（患者合并阻断经 M02"在途就诊查询"SPI 实现，无缓存视图需刷新），不在 90 号文档 M-25 成对登记清单内，无 `patient.split` 补订义务。
- **B-1（Round 2 补登）**：注册 M01"组织停用前置校验"SPI（床位占用查询实现，接口由 M01 定义）——文档头上游依赖 M01 括注、§8 集成点 M01 条目、§11 自审"依赖方向正确"条目三处补镜像声明。

## 13. P2 PR-1 落地注记（2026-09-26，feat/p2-pr1-m04-inpatient）

> 本节为 P2 PR-1（M04 住院/医嘱闭环 + M06 审方薄切片 + M13 住院计费联动）交付面相对本 Spec 的界定、降级与裁决声明，执行依据 `docs/superpowers/plans/2026-09-25-p2-pr1-m04-inpatient.md`（Global Constraints 32 收口硬门槛）；P2 PR-1 口径以本节为准，Spec 正文不回改。

1. **五大降级清单（Spec 声明而本 PR 缺位者，登记降级 + 注记 P3/P4，禁静默删改 Spec 语义）**：① `/ws/inpatient/ward/{wardId}` WS 主题不落（消费方护士站实时提醒归 P2 PR-3 M05 完整化，本 PR 不建 WS 端点）；② 通知中心（M01 缺位）——欠费提醒/会诊超时升级通知/随访触达降级为工作站列表可见（欠费标识列 `GET /visits/arrears` 病区欠费清单/会诊 overdue 查询/随访计划表）；③ 打印模板（腕带/执行单/催缴单，M01 打印缺位）；④ 会诊超时与执行计划超时的 `fy.delay` 档位不扩展——降级为**读时惰性逾期判定**（查询时 `now > deadline` 置视图 overdue 标记并广播 `inpatient.consultation.overdue` 动作事件一次，DB flag 防重发；fy.delay 档位扩展随 W-27 tick 方案 P2 PR-4 一并设计）；⑤ SCHEDULED 逾期自动回队列定时任务不落（本 PR 手工 cancel 重排）+「全院一张床」跨病区签床调度规则不参数化（本 PR 仅本位区队列排序），均注记 P3。
2. **降级清单第六项（高危药双人核对，Task 7）**：Spec「高危强制项第二核对人」降级为 **BLOOD（输血类）单面**——转抄核对仅对输血类医嘱强制 `secondCheckerId`（IP-1016），药品级高危分级标记 V904 无 item 级高危列且 M01 药品高危分级契约面缺位（GC11 禁跨模块读表），三处代码 P3 注记在位，随 M01 契约面交付后补齐。
3. **降级清单第七项（出院带药，Task 9）**：Spec「取药完成方可确认离院」降级为**放行即确认**——离院确认时 DISCHARGE_MED∩CREATED 医嘱经状态机放行至 AUDITED（`audited.discharge-med` 子键事件驱动 M06 摆药），取药完成回执归 P2 PR-3 M06 住院摆药衔接。
4. **会诊时限枚举常量未参数化（Task 11，与降级④一并登记）**：急会诊 30 分钟/普通会诊 24 小时时限以枚举常量承载（`ConsultationServiceImpl`），未做配置面参数化，P3 配置化。
5. **医嘱模板与套顺延（FU-M04-04 加速录入项，P3）**：本 PR 交付手写开立全量校验链，模板/套编辑与引用不落。
6. **FU-M04-03 临床周边面 P4 顺延（显式排除）**：病程记录嵌入 M09 编辑能力/诊断维护引用 M01 ICD 字典/检查检验结果查看调 M07/M08——三依赖模块均未交付；本 PR 住院医生站=在院列表+医嘱开立+闭环追溯三区（前端六页已交付）。
7. **住院计费入口前端直调形态偏差（Spec §7 偏差登记）**：Spec §7「inpatient 前缀计费转调端点」不落地——住院计费入口（押金缴存/一日清单）经前端直调 M13 既有 REST（门诊同款先例）；后端仅保留欠费标识事件驱动面（`billing.deposit.changed` 消费 → `inpatient_visit.arrears_flag`）与 `GET /visits/arrears` 病区欠费清单。
8. **抢救口头医嘱限时催办 P3**：本 PR 落 `oral_flag` 标记 + `POST /orders/{no}/oral-confirm` 补录确认端点，「事后限时确认」催办流转 P3（fy.delay 档位归降级④）。
9. **CF-6/W-33 定稿落点**：执行回签字段级契约以 **V901 UPDATE `integration.event_registry` id 55 payload_desc** 定稿（W-33 闭合，迁移头留痕替代占位语义；响应四字段 planNo/m04OrderNo/orderStatus/planStatus 契约由 `InpatientOrderFlowIT` 响应面断言）；**Task 8 审查修复环 R1 契约增补**：LONG 医嘱 EXECUTING→COMPLETED 加 end_at 已到守卫（end_at 为空保持 EXECUTING 由停嘱终结；日切候选同步过滤次日>end_at），V901 id 55 desc 含该限定句——临床安全默认项，消费端不存在故以迁移头注释声明替代双向评审。
10. **事件 id 65–73 排定**：65 `inpatient.visit.registered`、66 `inpatient.order.created`（routing 携类型子键，登记名不带子键）、67 `inpatient.order.audit-rejected`、68–72 会诊五态、73 `billing.arrears.approved`（producer=billing，落 billing V1002）；V800 既有 id 41–52/53/54 payload_desc 冻结不动。
11. **迁移号段形态**：inpatient 固定百位段 V900–V999 首批 V901–V908；**pharmacy V1000 与 billing V1001–V1003 开创 V500+ 通用段四位数先例**（固定段内号必被乱序守卫拦截，取 V1000+ 并避开 inpatient 段）。
12. **日切分解异常清单简化决策（偏差登记）**：Spec §10「分解失败进异常清单可重跑」简化为 **warn 日志留痕（order_no+缺失原因）+ 该医嘱不生成**，不建异常清单表；重跑由查前置+`uk_plan_order_item_time` 唯一约束双幂等兜底，夜内缓冲人工对账。
13. **住院计价承载面裁决（Task 13）**：Spec §7/M-4「`inpatient.order.audited` 即时计价」的承载面落定为 **`inpatient.order.created`**——V800 id 41 audited 冻结载荷六字段不携 items（计价数据面不可得），id 66 created 载荷携 items[]，门诊侧先例同款（开单事件驱动计价）；audited 帧到店 info 留痕直返，PENDING 行经 executed 确认、经 stopped/作废/撤回/审方驳回四终态截断。本 PR 验收锚点 IT（`BillingInpatientLinkageIT`）按 created 承载面断言终态。
14. **M13 通配消费子键归一修正（Task 16 IT 实测发现的生产缺陷修复）**：`BillingInpatientEventListener` 派发前对 created/audited 两事件投递面 eventType 剥离 order_type 子键归一回登记名——未归一时子键帧全部落入 default 分支静默直返，住院离散计价失效；随五条验收锚点 IT（`InpatientOrderFlowIT`）实测暴露并修复（含单测两例）。
15. **随访与准备窗口行为变更（Task 10 回接参数化，审查确认非回归红线情形）**：随访缺省时距 7→**14 日**（`fuyun.inpatient.follow-up-interval-days`，可显式传参覆盖）、临时单次计划默认准备窗口 15→**60 分钟**（`fuyun.inpatient.default-execute-window-minutes`）；断言适配严格度不降。
16. **前端 OrderCreatePayload 本地类型偏差（Task 15，GC30 登记）**：住院开单请求 DTO 与门诊 `OrderCreateRequest` Springdoc schema 同名覆盖，住院契约在生成物失真——前端以本地 `OrderCreatePayload`（必需字段对齐出网）承载；根治（后端 DTO 改名或 springdoc 命名策略）已登记 TASK.md D-25 待决策，修复后删本地别名回归生成物。
17. **api.d.ts 键序重排噪声（Task 14，PR 描述登记义务）**：openapi-typescript 重生成引入 745 旧 schema 零丢失 + 202 新增面的同时伴随既有键序重排噪声，本地 `git diff` 人工核对承载，CI 生成物新鲜度校验缺口见 TASK.md W-30。
18. **W-34 触发就位（退役执行仍留 PR-3）**：`inpatient.visit.registered/admitted` + `inpatient.bed.changed` 事件链上线并经 `InpatientAdmissionFlowIT` 真栈验收通过，P1 过渡通道（`POST /api/v1/nursing/ward-patients` + `nursing_ward_patient`）退役触发条件达成；退役五项执行（端点/DTO、状态值与视图表、审计留痕、VO 字段面回归、一览 IT 重跑）归 P2 PR-3 M05 完整化。
