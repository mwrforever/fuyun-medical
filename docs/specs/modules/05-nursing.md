# M05 护理管理（含移动护理）· 功能实现 Spec

| 属性 | 内容 |
| --- | --- |
| 模块编号 | M05 |
| Maven 模块 | `fuyun-nursing`（schema：`nursing`） |
| 版本 / 状态 | v1.1 / 统一审查修订（修订记录见 §12） |
| 上游依赖 | M01（认证/RBAC+数据范围/字典/参数/审计/通知中心/打印模板/CA 电子签名）、M02（patient_id/EMPI 归一、健康档案过敏项）、M04（visit_id、医嘱转抄与执行计划事件、执行回签接口、病区/床位/护理级别）、M06（住院摆药签收事件、药品查询 API、病区退药受理）、M14（遥测查询/设备绑定/告警事件/联动规则/输液告急）、M20（事件总线治理、幂等构件、延迟队列） |
| 下游被依赖 | M04（执行回签 API 被调方、执行回执事件订阅方）、M06（病区退药发起方）、M07（标本采集扫码执行承接，P1）、M09（护理文书与体征取数——患者全景）、M11（ICU 通用护理文书边界协同）、M13（执行占用查询——退费前置校验）、M14（输液任务事件订阅方）、M19（护理工作量/不良事件统计取数）、web-workstation / web-bigscreen / PDA（WebSocket 推送） |
| 对应总 Spec | FU-M05-01 ~ FU-M05-09 |

---

## 1. 模块定位与边界

**职责**：本模块是住院病区护理业务与护理文书病历数据的权威域，九项职责：① 护士工作站——病区患者一览（护理级别/病情状态/风险与过敏标识）、责任护士分配（护士↔患者/床位×班次）；② 生命体征双通道——手工（工作站）/PDA（含一体机直采）/物联网连续遥测三源录入与冲突仲裁，自动入体温单与护理记录；③ 护理文书——体温单（含物理降温/脉搏短绌等特殊事件符号绘制规则）、护理记录单、出入量、护理评估单（Braden 压疮/Morse 跌倒/NRS 疼痛等量表）；④ 医嘱执行域——执行单生成、床旁核对、执行推进、向 M04 回签（医嘱语义权威在 M04）；⑤ 移动护理 PDA——三向扫码核对（患者腕带↔药品/输液袋瓶签↔医嘱执行单）、给药/输液/标本采集执行、巡视打卡、体征采集上传、患者查询；⑥ 输液闭环——输液任务（药房签收驱动）→扫码核对→开始输注→M14 余量监测联动→拔针确认；⑦ 护理任务管理——定时任务（翻身/给药/巡视）生成提醒与逾期升级；⑧ 护士站大屏——病区运行态势聚合（呼叫/输液告警/任务逾期/危急值/床位动态）；⑨ 交接班与不良事件——SBAR 结构化电子交接班、护理不良事件上报/分类统计/整改追踪。

**非职责**：医嘱开立/审核/转抄核对/医嘱状态权威（归 M04，本模块只消费其事件并回签执行结果）；药品审方与摆药调剂（归 M06，本模块只做病区签收、床旁核对与病区退药发起）；门诊治疗室皮试/注射/换药执行记录（归 M03，两模块仅前端复用 PDA 扫码组件）；手术室术中清点与 PACU 复苏记录（归 M10）；ICU 重症专科护理记录、重症评分与 ICU 自动出入量汇总（归 M11，通用护理文书仍在本模块）；输血床旁执行闭环（归 M12）；病历文书通用编辑器与质控引擎（归 M09，护理文书本体与留痕在本模块）；设备绑定/遥测判定/告警规则（归 M14，本模块只消费告警与查询遥测）；呼叫对讲硬件接入与接听流程（归 M16，本模块只在大屏聚合展示）；资金收退付与计价（归 M13）。

**模块红线**：
1. 医嘱语义唯一权威在 M04：本模块不建医嘱表、不复制医嘱明细作为权威源、不改写医嘱状态；执行域一律引用 M04 医嘱号与执行计划实例号，医嘱进度感知只经 M04 事件与查询接口。
2. 护理文书是病历资料的组成部分：书写客观、真实、准确、及时、规范（卫办医政发〔2010〕125 号）；提交后锁定、修订必须留痕且原值可见，签名经 M01 电子签名；文书业务时间一律服务器时间。
3. 给药/输液执行确认强制三向核对：未经"腕带↔执行单（瓶签）↔医嘱项"服务端校验通过不得完成执行回签；破码（越过扫码）放行仅限抢救等紧急场景，必须双人授权+原因留痕+事后审查。
4. 体征入体温单权威栏必须经质量过滤与双通道仲裁：未经仲裁的 IoT 值不得直接覆盖点测值；冲突时双值均保留可溯，体温单/护理记录落点唯一在本模块。
5. 禁止跨模块读表：执业授权调 M01、过敏史调 M02、医嘱与计划调 M04、药品摘要调 M06、遥测与绑定调 M14；对外仅暴露 API 与事件。

## 2. 调研依据

1. **表格式护理文书官方规范**（卫办医政发〔2010〕125 号）：护士填写/书写的护理文书为体温单、医嘱单（长期/临时）、手术清点记录、病重（病危）患者护理记录；体温单内容含脉搏、体温、呼吸、血压、出入量、大便次数、体重、身高、住院天数、手术后天数等要素；**长期医嘱的给药单/输液单/治疗单由执行护士签名、不归入病历**；临时医嘱由执行护士填写执行时间并签名；护理记录书写须客观、真实、准确、及时、规范。（来源：https://yygl.bjmu.edu.cn/yygl/hlgl/7eac5dd4c8874090a76e1339d87fcb21.htm ）
2. **体温单绘制规则**：口温蓝点、腋温蓝叉、肛温蓝圈；脉率红点红线相连、心率红圈；体温与脉搏重叠时先画体温符号再于其外画红圈；**脉搏短绌时在脉率与心率两曲线之间以红直线填充**；**物理降温 30 分钟后所测体温以红圈表示、以红虚线与降温前体温相连**；体温单每小格 0.2℃。（来源：https://www.cn-healthcare.com/articlewm/20230723/wap-content-1582376.html 、https://zhuanlan.zhihu.com/p/430720753 、https://blog.csdn.net/dinglinqu2724/article/details/101732891 ）
3. **移动护理 PDA 闭环**：医嘱执行五环节"收药—摆药—核对—执行—拔针"扫码管理（301 医院招标技术需求，总 Spec 来源 15）；PDA 扫描患者腕带与药品条码自动核对，达成 5R 目标（正确的病人/药品/剂量/途径/时间）；非本人药品 PDA 拒绝执行并提醒；真实医院 PDA 含护理录入、巡视、医嘱执行、标本采集、患者查询等 21 个移动端功能模块；移动护理已纳入电子病历评级标准。（来源：https://www.chainway.cn/mobile/industry/Subcon/48 、https://4g.lsznk.com/html/yydt/2672.html 、https://www.hyrmyy.com/view.aspx?id=12565 、http://www.cn-witmed.com/list/36/21683.html 、http://cs.china-cmd.org/zgylsb/CN/article/downloadArticleFile.do?attachType=PDF&id=4686 ）
4. **生命体征多源采集**：生命体征一体机以二维码匹配患者身份、实时上传并共享至护理记录单（总 Spec 2.4 智慧病房实证）；行业方案体征数据源支持"设备自动采集、护士手工录入、HIS 同步"三通道并保证数据完整；物联网护理集成自动导入生理数据省 0.8h/床/天（解放军总医院 ICU 论文）。（来源：https://www.lonbon.com/yycp_ztjjfa27/ 、https://www.medvision.com.cn/article/23/29.html 、https://pmc.ncbi.nlm.nih.gov/articles/PMC11955343/ ）
5. **输液闭环与监控联动**：PDA 扫描患者腕带、药袋、设备编码完成三查七对；输液余量 15/10/5ml 逐级报警、滴速异常与空瓶报警联动护士站弹窗/语音/呼叫。（来源：https://ztlab.njupt.edu.cn/2024/0414/c17179a259859/page.htm 、https://mp.ofweek.com/iot/a556714273697 、https://www.lonbon.com/yycp_ztjjfa10/ ）
6. **出入量记录规范**：每班小结出入量，大夜班每 24 小时总结一次并记录在体温单相应栏内，各班小结与 24 小时总结需用红双线标识；入量含静脉输液/口服/鼻饲/输血，出量含尿/便/呕吐/引流等。（来源：https://zhuanlan.zhihu.com/p/213813911 、https://cmtopdr.com/post/detail/51bcc512-4aae-478b-b5e0-2dd73039b041 ）
7. **SBAR 交接班**：S 现状-B 背景-A 评估-R 建议的标准化结构化沟通模式已在医院全面推行，可提高交接准确性与实效性；系统化实践将本班照护内容自动汇总生成交班材料。（来源：https://wjw.beijing.gov.cn/xwzx_20031/jcdt/202604/t20260407_4575594.html 、https://www.bjcyh.com.cn/Html/News/Articles/23680.html 、https://patents.google.com/patent/CN111261249A/zh ）
8. **护理不良事件分级与上报**：国家卫健委《医疗质量安全不良事件分级分类标准》分 IV 类事件（隐患事件等）与 A~E 严重程度；中国医院协会将医疗不良事件分警告事件、不良事件、未造成后果事件、隐患事件四级，I/II 级属强制上报、III/IV 级鼓励上报；倡导非惩罚性主动报告文化，实时网报+每月汇总分析。（来源：https://www.nhc.gov.cn/bgt/c100264/202311/1b4c8ebd22a54b2180d63e8edb3f73fb/files/1733795787562_85421.pdf 、https://patents.google.com/patent/CN104732327A/zh 、https://view.inews.qq.com/k/20250813A07D7X00?scene=wap&no-redirect=1 ）
9. **护理评估量表**：Braden 压疮（≤9 极高危、18 分轻度风险分界）、Morse 跌倒（≥45 高风险、25~44 中风险）、NRS 疼痛（0~10 分）、Barthel 自理能力等为医院常用护理量表体系，评分结果用于风险分层与防范措施指引。（来源：https://www.cn-healthcare.com/articlewm/20210714/content-1243080.html 、https://mengte.online/medical_scales/14389 、https://cals.medlive.cn/calc/show/2?id=calc-1331 、https://zhuanlan.zhihu.com/p/4831286621 ）
10. **电子病历评级与护理闭环**：4 级要求全院信息共享与医疗业务闭环，6 级要求检查/检验/治疗/手术/输血/护理全流程数据跟踪与闭环管理（华西六级实证）；五级指标含护理计划模板与护理记录关联；移动护理纳入评级标准。（来源：https://www.cd120.com/comprehensive/87871.html 、https://zhuanlan.zhihu.com/p/501481417 、http://www.cn-witmed.com/list/36/21683.html ）

## 3. 方案推导（关键设计点选型）

### 3.1 医嘱执行模型：M04 执行计划实例与本模块执行记录的关系（执行单生成/批量 vs 逐条/回签链路）

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| A 纯批量执行（按批次确认） | 按给药批次整体确认，不逐条扫码 | 床旁效率高，但身份/药品核对靠人眼，违背三查七对与 5R（调研依据 3），评级闭环"执行可追溯"无据点；高危药无双人核对抓手 |
| B 逐条全扫码（每条独立扫腕带） | 每条执行项都先扫腕带再扫药品 | 核对最严，但同一患者同一时点多条给药（口服 3 种+输液 1 袋）需重复扫腕带 4 次，床旁效率不可接受 |
| **C 患者维度唤起+逐条药品扫码混合（选定）** | 扫腕带一次唤起该患者该时点全部待执行执行单；**每条药品/输液袋必须逐条扫码核对**（瓶签码↔执行单↔医嘱项服务端校验），高危药加第二核对人会签；非药品治疗/巡视类按腕带+执行单双向核对 | 兼顾 5R 强制性与床旁效率；与生产实践一致（输液执行先扫腕带再扫瓶签，两条匹配方可用药，调研依据 3）；执行单以 M04 计划实例为一一锚点，闭环追溯逐条可查 |

**执行单生成规则**：执行单（order_execution）是本模块执行域的原子单元，与 M04 执行计划实例（order_execute_plan）经 plan_no 幂等绑定（唯一约束，一实例一执行单；M04 已约定执行计划实例是执行单生成的唯一输入）。三路生成：① 消费 `inpatient.order.transferred`——临时医嘱（M04 转抄时建单次计划实例）生成单次执行单；② 消费 `inpatient.order-plan.generated`——长期医嘱按次日计划实例批量生成执行单（万级行/日，事件驱动批量落库）；③ 嘱托（prn）经 M04 触发单次计划后沿②同路径生成。显式排除：blood/surgery/exam 类医嘱不生成本模块执行单（输血床旁执行归 M12、手术执行归 M10、检查执行归 M08，各域经 M04 事件链自行受理）。药品类执行单生成后为 CREATED，须消费 `pharmacy.dispense.completed`（住院摆药/PIVAS 签收）完成病区签收（SIGNED）方进入可核对状态；非药品类（治疗/标本采集/护理操作）生成即可核对。

**执行回签链路（EXECUTING→COMPLETED 与计划回签）**：执行单 COMPLETED 时**主路径**进程内同步调 M04 `POST /api/v1/inpatient/order-plans/{no}/execute-confirm`（M04 计划置 EXECUTED，医嘱头聚合推进：长期医嘱首个回签 TRANSFERRED→EXECUTING、全部计划终态 EXECUTING→COMPLETED、临时医嘱单次回签 TRANSFERRED→COMPLETED，并发布 `inpatient.order.executed` 供 M13 费用确认）；**辅路径**本模块发布 `nursing.order-execution.completed` 回执事件，M04 订阅做双路对账（差异进异常清单）。M04 不可用时回签本地暂存队列按序补偿重试，床旁执行确认不受阻塞（先落本模块终态），事件对账兜底，双路到达以 M04 计划唯一约束幂等仅计一次。

**结论**：方案 C + 三路生成 + API 主路径/事件辅路径双路回签。

### 3.2 体征数据双通道仲裁：IoT 直写权威 vs 手工权威+IoT 仅参考 vs 时间窗冲突仲裁+复核转正

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| IoT 直写权威 | 设备值自动覆盖入体温单权威栏 | 连续监测值存在体动伪差/探头脱落（论文实证数据质量参差，调研依据 4/总 Spec 5.3），直接覆盖会污染病历权威数据；且体温单口径以护士点测为准（口/腋/肛部位符号不同） |
| 手工权威+IoT 仅参考 | IoT 值只做屏显不入卡 | 丧失 IoT 核心价值（省 0.8h/床/天实证，调研依据 4）；体温单仍需手工全量录入，双通道名存实亡 |
| **时间窗冲突仲裁+复核转正（选定）** | ① 三源归一：手工（工作站）/PDA（含一体机直采，二维码匹配患者）/IoT（M14 遥测周期拉取）统一落 vital_sign_record，标 source 与复核状态；② 质量闸门：仅 M14 quality=GOOD 且在生理极限内的值可自动入卡，SUSPECT 进待复核，BAD 不入卡；③ 冲突仲裁：点测值（手工/PDA）与 IoT 值落在同窗（参数化，默认 ±10 分钟）视为同一时点冲突对，**点测优先入体温单权威栏**，IoT 值保留为参考值可溯；④ 复核转正：无冲突且 quality=GOOD 的 IoT 值按病区配置可自动转正入卡，其余待护士在体温单/PDA 上确认后转正，转正前不占权威栏 | 兼顾自动化收益与病历正确性；冲突双值可溯满足医疗审计；三源枚举是总 Spec"手工+IoT 双通道"的落点细化（PDA 属移动端手工形态） |

**落点规则**：转正后的体征按测量时点写入体温单数据（符号由渲染端按体温部位决定：腋温×/口温●/肛温〇）；物理降温、脉搏短绌、呼吸心跳停止等特殊事件作为体温单特殊事件条目单独记录（符号规则见调研依据 2）；符合条件时自动生成/合并护理记录观察行。病区级开关控制 IoT 自动落卡（ICU 病区由 M11 专用通道承接时关闭，防双写）。

**结论**：三源归一 + 质量闸门 + 时间窗仲裁（点测优先）+ 复核转正。

### 3.3 PDA 三向核对链路：单向核对 vs 双向核对 vs 三向核对+服务端集中校验（含拦截与破码放行）

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 单向核对（仅扫药品标签） | 只验证药品身份 | 无法防"给错人"——给药差错最高频场景；不满足 5R 中的正确病人 |
| 双向核对（腕带+药品） | 患者与药品各自验证后由护士人工关联 | 两码匹配关系在客户端判断，存在绕过与误配空间；医嘱项维度（剂量/途径/时间窗）无人校验 |
| **三向核对+服务端集中校验（选定）** | 扫描顺序固定"先患者腕带、后药品/输液袋瓶签"；服务端集中校验：腕带码解析出 patient_id+visit_id → 校验与本模块绑定床位一致 → 唤起待执行执行单 → 瓶签码解析出执行单号 → 校验执行单.visit=腕带.visit ∧ 药品码/批号/剂量/途径与医嘱项快照一致 ∧ 当前时间在执行时间窗内 ∧ 医嘱未停/未撤（5R 全量校验，调研依据 3、5）；任一不匹配立即拦截并提示（PDA 拒绝执行，调研依据 3），核对失败记录 execution_check_log | 三向=腕带↔瓶签（执行单）↔医嘱项，把核对从"终端 UI 约定"提升为服务端强制；校验依据（医嘱快照/时间窗/停嘱状态）集中可审计；与输液场景"腕带+药袋+设备编码三查七对"实践一致（调研依据 5） |

**拦截与放行规则**：① 常规拦截——患者不匹配/药品不匹配/超时间窗/已停嘱/已撤单五类失败一律拦截，PDA 显示失败原因，不产生任何执行确认；超窗执行需护士长权限并附理由留痕。② 破码放行（急诊抢救例外）——腕带缺失/损坏、患者昏迷无法出示腕带等场景，经"双人授权（执行护士+第二授权人，权限点独立）+原因强制+破码标记"放行，执行单落 override 标记，**事后 24 小时内自动生成审查清单**推送护理部复核（破码率作为护理质量指标供 M19）；毒麻药品禁用破码放行。③ 降级补录——PDA/网络故障时工作站手工补录执行结果，强制标记补录+事后审核，与破码分开统计。

**结论**：三向核对服务端集中校验 + 五类拦截 + 破码双人授权留痕事后审查。

### 3.4 输液闭环任务基座：告警即任务 vs 输液执行单为基座+告警升级挂任务

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 告警不在 M05 建任务 | M14 告警只通知护士（大屏/PDA），M05 无任务载体 | 余量告急与拔针确认无业务单据衔接，输液闭环"拔针确认"环节无落点；告警处理责任不落地（谁接、处理结果无台账） |
| 每次告警生成新任务 | 每条 iot.alarm.triggered 建一条任务 | 输液传感器 15/10/5ml 逐级报警（调研依据 5）会为一个输液袋产生三条任务，任务风暴且与执行单脱钩 |
| **输液执行单为任务基座+告警升级挂任务（选定）** | 药房签收事件生成输液执行单即输液任务载体；开始输注时发布 `nursing.infusion.started`（M14 订阅建立告警↔任务关联）并经 M14 绑定查询挂接输液传感器；余量告急等 `iot.alarm.*` 到达时按患者/床位匹配在途输注执行单，对既有执行单做**升级动作**（优先级提升+责任护士强提醒+逾期阈值收紧），不新建任务；拔针确认（`nursing.infusion.completed`）后通知 M14 停止监测、闭环复位 | 任务与执行单一一对应无风暴；告急→升级动作复用 M14/M04"超时升级为动作"的既定设计；拔针确认闭合五环节最后一环，闭环可追溯 |

**结论**：输液任务=输液执行单，告警升级挂单不新建，拔针闭合监测。

### 3.5 护士站大屏聚合架构：全自建数据通道 vs 全复用 M14 主题 vs IoT 主题复用+护理域自建主题

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 全自建 | M05 自建 WebSocket 重建告警/遥测推送 | 与 M14 主题功能重复，两处推流不一致风险；重复消费遥测浪费（遥测摘要已 2 秒节流）；M14 已按病区主题化并处理断连补齐 |
| 全复用 | 大屏只订 M14 主题 | 呼叫（M16）、任务逾期、危急值（M07）、床位动态（M04）非 IoT 域，M14 主题不承载，覆盖不了 FU-M05-08 全部内容 |
| **IoT 主题复用+护理域自建主题（选定）** | web-bigscreen 前端双端点订阅：**直订 M14 端点** `/ws/iot` 的 `/topic/iot/alarm/{wardId}`（输液/设备告警触发/升级/关闭）、`/topic/iot/telemetry/{wardId}`（输液余量/体征摘要）、`/topic/iot/device-status/{wardId}`（设备状态）——IoT 域主题一律复用不重建；**订阅 M05 自建端点** `/ws/nursing` 的 `/topic/nursing/board/{wardId}`（呼叫转发/任务逾期/危急值提醒/床位患者动态）——M05 后端订阅 MQ 事件（M16 呼叫、M07 危急值、M04 床位与患者变更、本模块任务逾期）聚合后经自建主题推送；REST 快照 `GET /board/{wardId}` 兜底（聚合 M14 快照接口+本模块数据） | 职责按域切分：IoT 数据通道零重复建设，护理业务事件有单一归属；两主题刷新均满足 ≤2s（总 Spec 8）；断连补齐沿用 M14 既有机制，自建主题按同规范实现 |

**结论**：IoT 主题复用 M14、护理域自建 `/ws/nursing`、前端双端点订阅、REST 快照兜底。

## 4. 领域模型

表设计统一遵循 README 第 3 节约定：雪花 BIGINT 主键、统一审计字段、TIMESTAMPTZ 服务器时间、逻辑删、状态字段 VARCHAR 常量+迁移日志、跨模块引用只存 code/号引用。

| 实体 | 关键字段 | 说明 |
| --- | --- | --- |
| nursing_ward_config 病区护理配置 | ward_id（唯一）、体征默认测量频次（按护理级别：特级 q1h/病重 q4h 等参数组）、iot_autocast_enabled（IoT 自动落卡开关）、iot_sync_interval（拉取周期）、conflict_window（双通道冲突时间窗）、execute_time_window（执行时间窗阈值）、overdue_escalate_threshold/escalate_chain（任务逾期升级阈值与链路）、override_roles（破码授权角色集）、routine_task_templates（翻身/巡视常规模板引用）、shift_definitions（班次定义）、退药开关 | 病区级护理策略唯一配置点；ICU 等专科病区经此关闭通用通道（与 M11/M16 边界联动） |
| nurse_assignment 责任护士分配 | ward_id、nurse_id、assignment_type（责任组/管床）、bed 集合或患者集合、shift_code、valid_from/valid_to | FU-M05-01 责任分配载体；大屏"护士管床"与任务自动派发依据；同一床位同一班次唯一 |
| vital_sign_record 体征记录 | patient_id、visit_id、measured_at（测量时点）、体温（值+部位：口/腋/肛）、脉搏、呼吸、血压（收缩/舒张）、血氧、体重、身高、疼痛评分引用、source（MANUAL/PDA/IOT）、review_status（PENDING_REVIEW/CONFIRMED/REJECTED，自动转正直落 CONFIRMED）、conflict_ref（冲突对参照记录）、iot_quality（GOOD/SUSPECT/BAD，IoT 源填写）、复核人/复核时间 | 三源归一权威记录；(visit_id, measured_at, 体温部位) 唯一约束防双写重复；冲突双值经 conflict_ref 互链可溯 |
| temperature_chart_page 体温单页 | visit_id、chart_month（住院月页）、页状态（进行中/已归档）、手术后天数序列（依据 M10 手术事件标注） | 体温单页面维度（调研依据 1 内容要素：住院天数/术后天数/页码） |
| temperature_chart_entry 体温单条目 | page_id、entry_time、entry_type（VITAL 体征引用/SPECIAL_EVENT 特殊事件/DAILY_VALUE 日行值）、vital_ref（体征引用）、special_event_type（入院/手术/分娩/转科/出院/死亡/物理降温/脉搏短绌起止/呼吸心跳停止等）、daily_value_type（大便次数/出入量小结/体重/身高/皮试结果等）、符号提示（腋温×/口温●/肛温〇/红圈/红虚线由渲染端按规则绘制）、记录人 | 体温单数据点权威；特殊事件符号规则对齐调研依据 2；(page_id, entry_time, entry_type, 类型键) 唯一 |
| io_record 出入量明细 | visit_id、occur_at、io_type（INTAKE 入量/OUTPUT 出量）、item_code（静脉输液/口服/鼻饲/输血；尿/便/呕吐/引流/穿刺液）、数量/单位、source（MANUAL/PDA/INFUSION_AUTO 输液执行自动带入/TRANSFUSION_AUTO 输血自动带入/ICU_AUTO ICU 自动汇总/ICU_MANUAL ICU 手工录入）、source_ref（执行单号）、班次、记录人 | 出入量明细账；输液执行（开始/拔针）自动生成入量行，输注开始/结束经 M12 事件生成输血入量行；输血/ICU 自动入量行不受 ICU 病区手工出入量关闭开关影响（账仍落本模块 io_record） |
| io_summary 出入量小结 | visit_id、summary_type（SHIFT 班次小结/24H 24 小时总结）、统计周期、总入量/总出量/平衡值、记录人、体温单条目引用 | 每班小结+大夜 24h 总结，入体温单相应栏红双线标识（调研依据 6） |
| nursing_record 护理记录单 | record_no、visit_id、patient_id、record_class（GENERAL 一般/CRITICAL 病重病危）、记录时间、结构化内容（病情观察/护理措施/效果评价+自由文本）、关联引用（体征/执行单/特殊事件）、护士签名（电子签名引用）、status（草稿/已提交锁定/已修订）、修订链引用 | 表格式护理记录（调研依据 1）；提交锁定、修订留痕原值可见；记录频次按护理级别参数提醒 |
| nursing_assessment 护理评估单 | assess_no、patient_id/visit_id/ward_id、scale_type（BRADEN/MORSE/NRS/BARTHEL/MEWS/CUSTOM）、条目应答快照、总分、risk_level（高风险/中风险/低风险）、评估时点、评估人、next_assess_plan（按等级周期）、triggered_task_ref（防范任务引用）、adverse_event_ref（事件后回评引用） | 量表引擎承载（调研依据 9）；高风险自动生成防范任务与床旁风险标识 |
| nursing_task 护理任务 | task_no、patient_id/visit_id/bed_id/ward_id、task_type（给药/输液护理/翻身/巡视/标本采集/出入量监测/IOT 联动/评估提醒）、source（ORDER_PLAN/INFUSION_ALARM/IOT_LINKAGE/ROUTINE/MANUAL）、source_ref（执行单号/告警号/规则号）、plan_time、assigned_nurse、priority、overdue_flag、escalation_count、status（见状态机） | 任务统一载体；定时任务、IoT 联动任务、告警升级均落此表；M14 联动规则"创建护理任务"动作的落点 |
| order_execution 医嘱执行单 | execution_no（E+日期+流水，本模块签发）、m04_plan_no（M04 计划实例号，唯一约束）、m04_order_no、execution_type（口服/输液/注射/治疗/标本采集[限非检验标本，检验标本采集经 M07 条码/清单承载]/膳食/护理操作）、医嘱项摘要快照（药品/项目/剂量/途径/滴速，只读引用 M04）、patient_id/visit_id/bed_id/ward_id、plan_time、执行时间窗、五环节时点集（签收/核对/开始/完成/拔针时间）、核对快照（腕带码摘要/瓶签码/批号/校验结论）、执行人/第二核对人、override_flag（破码标记：原因/双授权人）、状态（见状态机） | 执行域原子单元；一计划实例一执行单（幂等绑定）；长期医嘱给药单/输液单执行签名不归入病历（调研依据 1） |
| execution_check_log 核对日志 | execution_no、check_stage、fail_type（患者不匹配/药品不匹配/剂量不匹配/超时间窗/已停嘱/已撤单）、scan_digest（扫码内容脱敏摘要）、pda_id、occurred_at、处理结果（拦截/超窗放行/破码放行） | 拦截与放行全留痕；破码事后审查与 M19 破码率指标数据源 |
| infusion_monitor_link 输液监测关联 | execution_no（唯一）、bag_label_code（输液袋瓶签码）、iot_device_id（输液传感器，经 M14 绑定查询获取）、started_at/ended_at、latest_alarm_ref、status（监测中/已结束/已解除） | 本模块执行单与 M14 传感器的关联台账；拔针后驱动 M14 停止监测 |
| adverse_event 护理不良事件 | event_no、patient_id（可空）/visit_id（可空）、发生时间/地点（病区/床位）、event_category（跌倒坠床/压力性损伤/管路滑脱/给药错误/烫伤/误吸窒息/输液外渗/其他）、severity_class（I 警告/II 不良/III 未造成后果/IV 隐患）、severity_grade（A~E）、当事人/上报人/上报时间、时限合规标记（严重事件即时口头上报+24h 内系统补报）、经过与处置、原因分析（RCA）、整改措施、审签引用、status（见状态机） | 分级口径对齐调研依据 8；非惩罚主动报告；分类统计供 M19 |
| shift_handover 交接班 | handover_no、ward_id、shift_code、交班护士/接班护士、患者摘要快照（总数/病危/病重/新入/手术/转出/今日出院）、SBAR 四段（现状/背景/评估/建议，自动汇总+人工补充）、待续事项清单（在途任务/在途输注/未闭环告警引用）、交班签名时间/接班签名时间、status（见状态机） | SBAR 结构化交接班（调研依据 7）；内容由系统按本班业务数据自动汇总 |

关系要点：nursing_ward_config 1:1 病区；inpatient_visit（M04）1:N vital_sign_record/nursing_record/io_record/nursing_assessment/order_execution/adverse_event；vital_sign_record 1:N temperature_chart_entry（引用）；order_execution 1:1 m04 计划实例（引用）/1:1 infusion_monitor_link/N execution_check_log；nursing_task N:1 order_execution（source_ref）；nursing_assessment 1:N nursing_task（防范任务）；shift_handover 引用任务/输注/告警快照。

## 5. 状态机与业务流程

- **order_execution（执行单五环节状态链）**：`CREATED（执行单已生成，药品类待病区签收）→ SIGNED（病区签收完成，消费 pharmacy.dispense.completed；非药品类生成即可核对，可跳过 SIGNED）→ CHECKED（三向核对通过）→ EXECUTING（开始执行/输注中，输液类挂接监测）→ COMPLETED（执行完成：给药确认/拔针确认，终态）`；侧支：`CREATED/SIGNED/CHECKED → CANCELLED（停嘱/作废/撤单/出院清理联动，终态）`；`EXECUTING → CANCELLED` 仅限输注中断等特殊情形（需护士长权限并留痕，已入量照记）。输注中断回签规则：本模块向 M04 按部分执行回签（携实际输注量，M13 按实际量计费），医嘱头经停嘱（M04 发布 `inpatient.order.stopped`）达终态，执行单置 CANCELLED。核对失败不产生状态迁移（拦截并记 execution_check_log）。每环节时点、操作人全留痕。
- **nursing_task**：`PENDING（待执行）→ IN_PROGRESS（执行中）→ COMPLETED（已完成，终态）`；`PENDING/IN_PROGRESS → CANCELLED（源单据作废/撤单联动，终态）`；PENDING 超过计划时间阈值触发**逾期动作**（overdue_flag 置位+按升级链"责任护士→护士长"重复通知+escalation_count 递增，状态不变，仍可被完成）——动作式逾期，对齐 M04 会诊/M14 告警超时升级同款设计。
- **adverse_event**：`REPORTED（已上报）→ HANDLING（处理中：定性分级、原因分析、整改措施录入）→ CLOSED（护理部审签关闭，终态）`；`HANDLING → REPORTED（退回补充，附要求）`；I/II 级（警告/不良事件）强制上报且处理时限参数化，超时经延迟消息提醒升级（不改状态）。
- **shift_handover**：`DRAFT（系统按本班数据自动汇总生成草稿）→ SIGNING（交班内容确认与双班签名中）→ COMPLETED（接班确认完成，终态）`；交接班未完成不阻塞业务（任务/执行照常运行）。
- **vital_sign_record 复核流**：`PENDING_REVIEW（待复核：IoT 冲突对或 SUSPECT 质量）→ CONFIRMED（护士确认，入体温单权威栏）/ REJECTED（驳回，不入权威栏）`；无冲突且 GOOD 的值按病区参数自动 CONFIRMED，或护士手工/PDA 点测值直接 CONFIRMED。

主流程时序：

1. **临时给药执行闭环**：M04 转抄（`inpatient.order.transferred`）→ 本模块生成执行单（CREATED）→ M06 摆药签收（`pharmacy.dispense.completed`）→ 病区签收（SIGNED）→ PDA 扫腕带唤起待执行列表→逐条扫药品瓶签（三向核对）→ CHECKED（高危药第二核对人会签）→ 床旁给药确认 → COMPLETED → 进程内调 M04 执行回签（计划 EXECUTED、医嘱头 COMPLETED、发布 `inpatient.order.executed`→M13 费用确认）+ 发布 `nursing.order-execution.completed` 对账回执。
2. **长期医嘱每日循环**：M04 日切分解（`inpatient.order-plan.generated`）→ 批量生成次日执行单 → 打印执行单/瓶签（M01 模板，给药单执行签名不归入病历）→ 各时点按流程 1 执行 → 首次回签后 M04 医嘱头转 EXECUTING、全部时点完成转 COMPLETED；停嘱（`inpatient.order.stopped`）→ 未执行执行单批量 CANCELLED。
3. **输液闭环（FU-M05-06）**：M06 摆药/PIVAS 签收 → 输液执行单生成+病区签收 → PDA 三向核对（腕带↔瓶签↔医嘱项，5R）→ 开始输注（EXECUTING，发布 `nursing.infusion.started`，M14 订阅建立告警↔任务关联，经 M14 绑定查询挂接输液传感器）→ 输注中 M14 余量监测：余量告急 `iot.alarm.triggered` → 本模块匹配在途输注执行单做**升级动作**（优先级提升+责任护士强提醒+逾期收紧，不新建任务）→ 拔针确认（发布 `nursing.infusion.completed`，通知 M14 停止监测、自动生成输液入量记录行）→ 执行单 COMPLETED → 回签 M04。
4. **体征双通道落卡**：IoT 通道按病区周期拉取 M14 最新值（质量闸门）→ 无冲突 GOOD 自动转正或进待复核；手工/PDA 点测直接 CONFIRMED；点测与 IoT 同窗冲突时点测入权威栏、IoT 值留参考（conflict_ref 互链）→ 转正值写体温单条目（符号渲染）+按规则生成护理记录观察行 → 发布 `nursing.vital-sign.recorded`。
5. **交接班**：交班护士发起 → 系统自动汇总本班患者摘要/SBAR 初稿/待续事项（在途任务、在途输注、未闭环告警）→ 人工补充确认 → 双班签名 COMPLETED → 发布 `nursing.shift.completed`。
6. **不良事件**：事件发生 → 上报（REPORTED，I/II 级即时口头+24h 内系统补报合规标记）→ 护士长/护理部处理（HANDLING：定性分级、RCA、整改）→ 审签关闭（CLOSED）→ 发布 `nursing.adverse-event.reported` 供分类统计。

## 6. 功能实现设计（逐 FU）

| FU | 实现设计要点 |
| --- | --- |
| FU-M05-01 护士工作站（P0） | 病区患者一览：床位序展示（护理级别/病情状态：危/重/新入/手术/分娩/转出/今日出院；风险标识：跌倒/压疮/管路来自评估单高危结果；过敏标识来自 M02 健康档案订阅缓存）；数据源为 M04 病区患者与床位事件（订阅 admitted/transferred/discharged + bed.changed 维护本地视图）；责任护士分配（nurse_assignment：护士↔患者/床位×班次，支持拖拽调整与批量分配，交接班快照引用）；患者详情卡聚合在途任务/输注/未闭环告警；欠费提醒等住院计费类展示复用 M04 工作站数据（本模块不重复建设） |
| FU-M05-02 生命体征录入（P0） | 三源录入与仲裁（方案 3.2）：工作站手工表单、PDA 录入/一体机直采（扫码匹配患者后测量值自动上传入卡）、IoT 周期拉取 M14 遥测（`GET /api/v1/iot/telemetry/latest`，仅 quality=GOOD 入卡、SUSPECT 待复核、BAD 不入）；MEWS 等早期预警评分按配置自动计算，超标生成提醒任务；落卡唯一约束防双写；转正后自动入体温单与护理记录；体征趋势图（调 M14 时序查询）；发布 `nursing.vital-sign.recorded` |
| FU-M05-03 护理文书（P0） | 体温单：月页模型+条目数据（体征符号/特殊事件/日行值按调研依据 1、2 规则渲染：腋温×/口温●/肛温〇、物理降温红圈红虚线、脉搏短绌短红线、入院/手术/转科/出院/死亡竖线标注）；出入量：明细+班次小结+24h 总结（红双线入体温单相应栏，输液执行自动带入量）；护理记录单：一般/病重病危表格式，结构化段+自由文本，按护理级别频次提醒（特级 q1h/病重每班，参数化），提交锁定+修订留痕+电子签名（M01 CA）；评估单：Braden/Morse/NRS/Barthel/MEWS 及自定义量表引擎，评分自动判级，高危自动生成防范任务+床旁风险标识+按等级周期复评提醒；文书归档随病历（体温单/护理记录单入病历，执行单不归入病历） |
| FU-M05-04 医嘱执行（P0） | 执行单三路生成与双路回签（方案 3.1 全套）；执行单/瓶签条码打印（M01 模板，码内容=执行单号供 PDA 扫码）；执行工作台（按病区/班次/患者分组：待签收/待核对/待执行/执行中）；嘱托（prn）经 M04 standby-trigger API 触发单次计划后生成执行单；转科重定向：消费 `inpatient.visit.transferred` 后未执行执行单随患者归属新病区（计划时间不变，长期停嘱作废由 M04 事件联动）；出院清理：消费 discharge-requested 清退在途任务提示、discharged 终清；闭环追溯：单条执行单全环节人/时/码一屏可溯（与 M04 医嘱闭环追溯视图关联） |
| FU-M05-05 移动护理 PDA（P0） | 三向扫码核对（方案 3.3 全套：腕带↔瓶签↔医嘱项，5R 服务端集中校验，五类拦截，破码双人授权+事后审查）；给药/输液执行（流程见 FU-M05-04/06）；标本采集执行：经 M07 接口获取待采集标本与试管条码清单，床旁扫腕带+试管条码双向核对，采集记录回传 M07（标本状态权威与送检流转在 M07，P1）；巡视打卡（扫腕带/床头卡记录巡视时间与执行人）；体征采集上传（PDA 录入/一体机直采）；患者查询（基本信息/医嘱执行/体征趋势/费用欠费，脱敏输出）；PDA 弱网本地缓存补传（补传标记） |
| FU-M05-06 输液闭环（P0） | 任务基座+告警升级（方案 3.4 全套）：药房签收生成输液执行单 → 三向核对（腕带/输液袋瓶签，核对输液袋与患者匹配方可输注，调研依据 5）→ 开始输注（挂接 M14 输液传感器经绑定查询，发布 infusion.started）→ 输注监控视图（全病区输液一览：余量/滴速/剩余时间，数据调 M14 遥测）→ 余量告急 iot.alarm 联动升级动作（不新建任务，逐级 15/10/5ml 告警均挂同一执行单累计升级）→ 拔针确认（needle-out：核对腕带后确认，通知 M14 停止监测、生成入量行、执行单 COMPLETED 回签）；异常处理：滴速异常/阻塞告警同挂执行单提醒；破码场景（袋签损坏）按 3.3 放行规则 |
| FU-M05-07 护理任务管理（P1） | 任务来源四路：医嘱执行计划（给药/输液/标本等执行单联动生成）、输液告急联动（升级挂单）、IoT 联动规则（M14 linkage 调本模块创建任务 API：离床确认/设备断流确认等）、护理常规（翻身 q2h/巡视等模板按频次批量生成）；任务工作台（按班次/责任组/状态分组，认领/完成/取消全留痕）；定时提醒（PDA+工作站，经 M01 通知通道）；逾期动作式升级（延迟队列驱动，阈值与升级链病区参数化）；任务完成回写关联单据（执行单/评估单） |
| FU-M05-08 护士站大屏（P0） | 聚合架构（方案 3.5）：web-bigscreen 双端点订阅——复用 M14 `/ws/iot` 三主题（告警/遥测摘要/设备状态）+自建 `/ws/nursing` 的 `/topic/nursing/board/{wardId}`（呼叫转发[M16 事件订阅]、任务逾期、危急值提醒[M07 事件订阅]、床位患者动态[M04 事件订阅]）；展示视图：床位总览墙（护理级别/风险/责任护士）、未确认告警列表、输液动态（余量倒计时）、任务逾期看板、危急值待处理、出入院动态；REST 快照 `GET /board/{wardId}` 兜底（聚合 M14 快照+本模块数据）；刷新 ≤2s；断连重连按 REST 增量补齐（沿用 M14 规范） |
| FU-M05-09 护理不良事件上报（P1） | 上报：结构化表单（类别/经过/处置/当事人，支持匿名鼓励上报通道）；分级：I 警告/II 不良/III 未造成后果/IV 隐患四类+A~E 严重程度（调研依据 8），I/II 级强制上报（严重事件即时口头上报+24h 内系统补报，时限合规标记）；非惩罚文化：报表不含个人惩罚字段，仅流程改进导向；处理：护士长初处理→护理部定性→RCA 原因分析→整改措施与责任人→审签关闭，超时提醒升级；关联：事件后回评（评估单引用）、涉事执行单/告警引用追溯；分类统计与趋势（按类别/病区/时段/等级）供 M19 护理质量指标；发布 `nursing.adverse-event.reported` |

## 7. 对外接口

**REST（`/api/v1/nursing/` 前缀，节选，响应统一 `{code, message, data, traceId}`）**：
- 工作站：`GET /ward-patients?wardId=`、`GET /assignments?wardId=&shift=`、`POST /assignments`
- 体征：`POST /vital-signs`（手工/PDA）、`GET /vital-signs?patientId=&from=&to=`、`GET /vital-signs/pending-review?wardId=`、`POST /vital-signs/{id}/confirm|reject`、`POST /vital-signs/iot-sync`（手动触发同步）
- 体温单：`GET /temperature-charts?visitId=&month=`、`POST /temperature-charts/{visitId}/special-events`、`POST /io-records`、`GET /io-records?visitId=&date=`、`POST /io-summaries`
- 护理记录：`GET/POST /records`、`POST /records/{no}/submit`、`POST /records/{no}/revise`
- 评估：`GET /assessment-scales`、`POST /assessments`、`GET /assessments?visitId=&scaleType=`
- 任务：`GET /tasks?wardId=&status=&date=`、`POST /tasks`（手工/系统联动创建，调用方：M14 联动动作、M16 紧急呼叫自动转任务[幂等键=call_no]）、`POST /tasks/{no}/claim|complete|cancel`
- 执行：`GET /executions?wardId=&date=&shift=`、`POST /executions/{no}/sign-receive|check|start|finish|needle-out`、`GET /executions/occupancy?patientId=&m04OrderNo=`（执行占用查询，供 M13 退费前置校验）
- 输液监控：`GET /infusions/active?wardId=`（全病区输注中一览：余量/滴速/剩余时间，数据调 M14 遥测查询）
- PDA：`GET /pda/patient-summary?patientId=`、`GET /pda/pending-executions?visitId=`（腕带扫码唤起）、`POST /pda/patrol`（巡视打卡）、`POST /pda/override-check`（破码放行申请，双授权）
- 不良事件：`GET/POST /adverse-events`、`POST /adverse-events/{no}/handle|close`
- 交接班：`POST /handovers/generate`、`POST /handovers/{no}/complete`、`GET /handovers?wardId=`
- 大屏与取数：`GET /board/{wardId}`（快照兜底）、`GET /patient-nursing-view?patientId=`（供 M09 患者全景，只读）、`GET /stats/nursing-workload?wardId=&date=`（供 M19，只读）、`GET /stats/override-rate?wardId=&date=`（破码率统计，供 M19 护理质量指标，只读）、`GET /stats/adverse-events?category=&wardId=&date=`（不良事件分类统计，供 M19，只读）

**内部服务接口（进程内）**：M04 执行回签调用（`POST /api/v1/inpatient/order-plans/{no}/execute-confirm`）；M06 药品摘要查询（核对快照用）与病区退药受理（`POST /api/v1/pharmacy/dispense-returns`，本模块发起）；M14 绑定查询、遥测最新值/时序查询、大屏快照聚合取数；M02 过敏项快速校验嵌查。

**MQ 事件（发布，经 `fy.topic`，信封遵循 M20 治理约定 eventId/occurredAt/producer，先登记 event_registry）**：
- `nursing.order-execution.completed`（执行回执：执行单号/plan_no/医嘱号/患者/环节时点/执行人/破码标记摘要；**M04 订阅做双路对账**）
- `nursing.task.created`（任务生成；M14 订阅用于输液告警与任务单关联）
- `nursing.task.completed` / `nursing.task.overdue`（任务完成/逾期升级动作广播）
- `nursing.infusion.started`（开始输注；M14 订阅建立告警↔任务↔传感器关联）
- `nursing.infusion.completed`（拔针/输注结束；M14 停止监测、M16 呼叫复位参照）
- `nursing.vital-sign.recorded`（体征记录转正入卡；M09/M11/M19 可能取数）
- `nursing.assessment.completed`（评估完成，含风险等级；防范任务联动）
- `nursing.adverse-event.reported`（不良事件上报；M19/M09 统计与全景）
- `nursing.shift.completed`（交接班完成；M19 工作量统计）

**MQ 事件（订阅，全部经 M20 幂等构件消费，队列命名 `q.nursing.<事件名>`）**：
- `inpatient.order.transferred`（转抄→临时执行单生成）、`inpatient.order-plan.generated`（长期计划→执行单批量生成）
- `inpatient.order.stopped` / `inpatient.order.cancelled`（未执行执行单撤销、在途核对拦截；`inpatient.order.revoked` 系转抄前撤回、彼时尚无执行单，不构成本模块订阅）
- `inpatient.visit.admitted`（入组病区患者列表）/ `inpatient.visit.transferred`（病区患者变更+执行单重定向）/ `inpatient.visit.discharge-requested`（在途任务清退提示）/ `inpatient.visit.discharged`（终清）/ `inpatient.bed.changed`（床位动态大屏）
- `pharmacy.dispense.completed`（住院摆药/PIVAS 签收→收药环节与核对数据）、`pharmacy.drug.changed`（药品摘要缓存刷新）
- `iot.alarm.triggered` / `iot.alarm.escalated` / `iot.alarm.closed`（输液告急→执行单升级动作；告警闭环→大屏复位）、`iot.telemetry.anomaly`（设备断流→确认任务）、`iot.binding.changed`（绑定缓存刷新）
- `lab.critical-value.notified`（危急值→大屏提醒与护理任务，事件名以 M07 Spec 登记为准）
- M16 呼叫事件（大屏呼叫转发聚合，事件名以 M16 Spec 登记为准）
- M10 手术事件（体温单"手术"行与术后天数标注依据，事件名以 M10 Spec 登记为准）
- `transfusion.infusion.started` / `transfusion.infusion.completed`（M12 输注开始/结束→自动生成输血入量行并闭合，io_record source=TRANSFUSION_AUTO，与 M12 Spec 登记对齐）
- `icu.patient.transferred-out`（M11 转出 ICU 归档完成→解除患者一览"危/重"重症标识，与 M11 Spec 登记对齐）
- `outpatient.order.executed`（全院执行视图统计用途，M03 已声明）
- `patient.health-summary.updated`（过敏标识刷新）、`patient.merged`（历史文书读侧经 EMPI 归一）/ `patient.split`（拆分逆映射刷新，与 patient.merged 成对订阅）
- `system.dict.published` / `system.org.changed` / `system.user.changed` / `system.param.changed`（M01 主数据广播缓存刷新）

**WebSocket**：自建端点 `/ws/nursing`（STOMP，握手鉴权）：`/topic/nursing/board/{wardId}`（大屏与护士站聚合：任务逾期/呼叫转发/危急值提醒/床位患者动态）；IoT 域主题复用 M14 端点（见方案 3.5），不自建重复主题；PDA 任务与告警提醒经 M01 通知通道投递。

## 8. 集成点

- **M01**：认证与 RBAC（病区数据范围 WARD 隔离；执行/核对/破码授权/文书提交/不良事件管理分权）；执业授权校验（执行护士资质）；字典引用（给药途径/频次/体征项目 code，不自建副本）；通知中心（任务提醒/逾期升级/危急值提醒/破码审查推送）；打印模板（腕带/执行单/瓶签/交接班单）；CA 电子签名（护理记录签名）；审计切面（全写操作留痕）。
- **M02**：一切护理数据以 `patient_id`+`visit_id` 关联；过敏史嵌查+`patient.health-summary.updated` 订阅双通道；患者合并后历史文书读侧经 EMPI 归一；一体机/腕带扫码身份解析经其标识解析服务。
- **M04（执行域衔接，双向对齐其 Spec 第 7/8 节）**：① 订阅 transferred/order-plan.generated/stopped/cancelled/visit.* 事件驱动执行单全生命周期（revoked 系转抄前撤回、无执行单，不订阅）；② 执行完成主路径进程内调其 `POST /order-plans/{no}/execute-confirm`（计划 PENDING→EXECUTED、医嘱头 EXECUTING→COMPLETED），辅路径 `nursing.order-execution.completed` 事件对账（双路到达幂等仅计一次）；③ 嘱托经其 standby-trigger API 触发；④ 责任护士分配归本模块（其入科确认不越界），护理级别以其 visit 属性为准；⑤ 执行单重定向按其转科三分规则（未执行临时计划随患者转移）。
- **M06（摆药-给药衔接）**：订阅 `pharmacy.dispense.completed` 完成病区签收（收药环节）与给药核对数据准备；PDA 核对经其药品查询 API 获取药品主数据/批号效期/高警示标识（缓存）；病区退药由本模块发起、调其 `POST /dispense-returns` 受理回补（停嘱/出院联动）；高警示（A 级）药品触发强制第二核对。
- **M07（P1，标本衔接）**：本模块 PDA 承载床旁采血扫码执行（腕带+试管条码双向核对、采集记录），标本状态权威与送检流转在其（FU-M07-03）；待采集清单与试管条码经其接口获取，采集完成回传；危急值事件订阅（名称以其登记为准）。
- **M09**：护理文书（体温单/护理记录/评估）、体征、执行记录经取数 API 供患者全景（只读）；护理文书锁定/留痕/签名规范对齐其病历规范；护理记录时限提醒参数在本模块。
- **M10（边界）**：入手术室至出 PACU 期间的术中清点、麻醉与复苏记录归 M10；本模块承接患者回病区后的术后护理；体温单"手术"行与术后天数标注订阅 M10 手术事件；术前准备类任务（备皮/导尿等）归本模块。
- **M11（边界）**：ICU 患者重症专科护理记录（分钟级自动采集）、重症评分、ICU 自动出入量汇总归 M11；通用护理文书（体温单/交接班/不良事件/常规任务）仍在本模块；ICU 病区经 nursing_ward_config 关闭 IoT 自动落卡与手工出入量，防双写（输血/ICU 自动入量行除外——账仍落本模块 io_record，不受该关闭开关影响）；体征原始数据两模块同源 M14 查询。
- **M13**：执行占用查询 API 供其退费硬前置校验（已执行须先撤销执行，对齐其 FU-M13-03 契约）；本模块不做任何计价与收费。
- **M14（IoT 三场景）**：① 体征双通道——遥测最新值/时序查询+质量标记消费（FU-M14-06）；② 输液联动——订阅 iot.alarm.*，告急→执行单升级动作，infusion.started/completed 供其建立/解除告警↔任务↔传感器关联（其 Spec 已声明订阅，名称以本模块第 7 节登记为准）；绑定查询获取输液传感器；③ 大屏——复用其 WebSocket 三主题与快照接口；其联动规则"创建护理任务"动作调本模块 `POST /tasks`。
- **M16（边界）**：呼叫对讲业务归 M16，本模块订阅其呼叫事件仅做大屏转发聚合，不做接听流程；紧急呼叫自动转任务由 M16 调本模块 `POST /api/v1/nursing/tasks`（幂等键=call_no）；床旁屏体征/任务展示归 M16，经本模块 API 取数。
- **M19**：护理工作量（执行单/任务/巡视）、破码率、不良事件分类统计、逾期率等指标取数 API（只读）。
- **M20**：事件信封/交换机/队列治理、幂等构件、延迟队列（`delay.task-overdue` 任务逾期、`delay.assessment-remind` 复评提醒、`delay.override-review` 破码事后审查）、出站留痕；本模块全部事件先登记 event_registry。

## 9. 非功能与安全

- 性能：PDA 三向核对接口 P95 < 500ms（医嘱快照/药品摘要/绑定关系三级缓存兜底）；病区患者一览与执行工作台 P95 < 500ms；体征 IoT 落卡延迟 ≤ 同步周期+1 分钟（默认周期 5 分钟，参数化）；长期医嘱执行单批量生成（万级行/日）在计划事件到达后 5 分钟内完成；大屏刷新 ≤2s（复用主题与自建主题同线）；任务逾期提醒端到端 ≤30s。
- 可用性：给药/输液执行 7×24（总 Spec 核心可用性）；PDA 弱网本地缓存补传（补传标记+幂等防重复确认）；M04 不可用时执行确认先落本模块终态、回签暂存队列按序补偿（事件对账兜底）；M14 不可用时体征自动通道降级为手工并提示（手工通道独立可用）；M06 不可用时核对摘要走本地缓存。
- 一致性：执行确认与 M04 回签主路径同事务（进程内），失败补偿重试；执行单与计划实例 plan_no 唯一约束幂等；体征落卡（visit+时点+类型）唯一约束防 IoT/手工双写；事件发布走 outbox（M20 治理约定），订阅方 eventId 幂等。
- 审计：破码放行（含双授权人与原因）、执行确认、核对失败、文书提交与修订、评估、不良事件处置、交接班签名全量审计（M01 切面）；破码审查清单 24 小时自动推送护理部留处理结论。
- 权限：病区数据范围隔离（跨病区 PDA 查询 403）；执行/核对/破码/文书提交/不良事件管理分权；高危药第二核对人与执行人不得同一账号（同人拦截）；毒麻给药禁用破码放行。
- 合规映射：卫办医政发〔2010〕125 号表格式护理文书（类别与内容要素）；《病历书写基本规范》护理记录客观及时要求；三查七对与高危药双人核对制度；5R 用药安全目标（调研依据 3）；电子病历分级评价 4~6 级护理闭环与移动护理评级支撑（调研依据 10）；等保三级（审计/TLS/PDA 终端准入）；日志与事件载荷患者敏感字段脱敏。
- 数据留存：体温单/护理记录单/评估单随病历长期保存（住院病历 ≥30 年口径）；执行单与核对日志在线 ≥2 年后归档；任务/联动/交接班记录 ≥3 年；不良事件记录 ≥3 年、重大事件长期保留。

## 10. 测试要点

- 正常：临时给药全链路（转抄事件→执行单→摆药签收→腕带唤起→逐条扫码→高危双签→完成→M04 计划 EXECUTED/医嘱头 COMPLETED→`inpatient.order.executed` 供 M13 费用确认）；长期医嘱次日执行单数量与计划时点一致、首次回签转 EXECUTING、末次回签转 COMPLETED；输液闭环全链（签收→三向核对→开始输注→15/10/5ml 告急逐级升级挂单→拔针→M14 停监测→入量行生成）；体征三源落卡与体温单渲染（腋温×、物理降温红圈红虚线、脉搏短绌短红线、24h 出入量红双线）；Braden 高危→防范任务+床旁标识+按期复评提醒；SBAR 交接班自动汇总（在途任务/输注/告警齐全）+双签；不良事件 I 级上报→RCA→整改→关闭→分类统计。
- 边界：双通道同窗冲突（点测入权威栏、IoT 值留参考互链可溯）；IoT GOOD 无冲突按参数自动转正、SUSPECT 进待复核、BAD 不入卡；执行时间窗边界（窗内放行、超窗拦截需护士长权限+理由）；停嘱瞬间在途核对被拦截（已 CHECKED 执行单置 CANCELLED、EXECUTING 提示人工终止）；输注中断（EXECUTING→CANCELLED）回签：计划按部分执行回签携实际输注量、医嘱头经停嘱终态、M13 按实际量计费、已入量照记；转科后执行单重定向新病区且计划时间不变；出院终清未执行任务全部 CANCELLED；同一 plan_no 重复回签/重复事件仅计一次；破码缺任一授权人被拒；毒麻类破码请求一律拒绝；患者合并后历史文书经 EMPI 归一可查。
- 异常：M04 回签超时→暂存重试+`nursing.order-execution.completed` 对账补投、M04 侧幂等仅计一次；`pharmacy.dispense.completed` 重复投递仅签收一次；`iot.alarm.triggered` 重复投递升级动作不重复累计告警任务（告警号幂等）；M14 不可用降级手工体征通道且恢复后自动续采；PDA 断网补传不产生重复执行确认；M06 不可用时核对走缓存量效标识；交接班 DRAFT 未完成不影响任务执行。
- 安全：跨病区 PDA 查询患者 403 且留审计；无破码权限放行被拒且审计；执行人自任第二核对人被拦截；文书提交后原值不可改、修订走留痕链；日志/事件载荷患者敏感字段脱敏抽验；无资质护士（执业授权失效）执行确认被拒。

## 11. 自审记录

- [x] 无 TBD/TODO/占位符，13 项内容完整（文档头 + 12 节）
- [x] 覆盖 FU-M05-01~09 全部条目，无遗漏、无私增（标本采集执行为 FU-M05-05 既有表述的落地细化；MEWS/翻身/巡视为 FU-M05-02/07"评估/定时任务"的量表与模板细化，均非新功能点；FU-M05-07/09 按 P1 定位）
- [x] 内部一致：领域模型 ↔ 状态机 ↔ API ↔ 测试一一对应（order_execution/nursing_task/adverse_event/shift_handover/vital_sign_record 复核流五个状态机均有对应接口、流程与测试项；temperature_chart_page/entry、io_record/summary、nursing_record、nursing_assessment、execution_check_log、infusion_monitor_link 均有操作或查询路径与测试场景）
- [x] 符合跨模块约定：schema=nursing；主键 BIGINT 雪花；无资金字段（计价权威在 M13）；事件命名 `<模块>.<实体>.<动作>`、信封 eventId/occurredAt/producer、消费走 integration.received_event 幂等、队列 `q.nursing.<事件>`；REST 路径 `/api/v1/nursing/`；字典只存 M01 code 引用（量表为模块专业配置非国标字典）；状态字段 VARCHAR 常量+迁移日志；patient_id+visit_id 关联落实；无跨模块读表
- [x] 依赖方向正确：依赖 M01/M02/M04/M06/M14/M20 对外接口与事件；M07/M10/M16/M09/M11/M13/M19 为 API 拉取、事件订阅或被动的被依赖声明；无反向依赖；被依赖清单与 M04（执行回执订阅）/M06（退药发起）/M13（占用查询）/M14（输液任务事件订阅）Spec 声明互相对齐
- [x] 方案推导 5 个关键点均有备选对比与依据，含任务要求的三个必选点（3.1 医嘱执行模型含执行单生成规则/批量 vs 逐条/回签链路、3.2 体征双通道仲裁、3.3 PDA 三向核对与拦截破码），另含 3.4 输液闭环任务基座、3.5 大屏主题复用 vs 自建，每个结论附调研来源
- [x] 无代码级实现（无类名/方法体/SQL DDL 全文；表设计为"表-关键字段-约束"粒度；5R/MEWS/PIVAS/SBAR 为行业术语非代码）
- [x] 歧义消除：执行单与计划实例一一绑定（plan_no 幂等）、回签双路以 API 为主事件对账为辅、核对失败不留状态仅记日志、破码放行边界（毒麻禁用+双人授权+24h 事后审查）、逾期为动作非状态、体温单落点唯一（转正值入权威栏）、输液告警升级不新建任务、大屏 IoT 主题复用与护理主题自建的分界均已显式定义
- [x] 术语与总 Spec 一致（移动护理/PDA/腕带/体温单/护理记录单/出入量/护理评估单/医嘱执行/执行单/执行回签/输液闭环/拔针/护理任务/不良事件/交接班/责任护士）

## 12. 与总 Spec 的偏差

无结构性偏差。四处细化澄清（请统一审查裁决确认）：
1. **体征录入通道枚举细化**：总 Spec FU-M05-02 表述"手工录入 + IoT 自动采集双通道"，本 Spec 落为"手工（工作站）/PDA（含一体机直采）/IoT 连续遥测"三源——PDA 源本质仍属手工录入形态（移动端），与总 Spec 双通道语义一致，仅枚举细化；三源统一仲裁规则见方案 3.2。
2. **标本采集执行的模块分工**：总 Spec FU-M05-05（PDA 含标本采集执行）与 FU-M07-03（标本采集与流转含 PDA 采血扫码核对）存在表述重叠，本 Spec 口径：床旁扫码核对与采集执行动作由本模块移动护理统一承载（复用三向核对组件），标本状态权威、送检流转与不合格退回重采归 M07，采集结果经接口回传（P1，事件名以 M07 Spec 登记为准）。
3. **FU-M05-07"逾期升级"落为动作**：逾期不设独立状态（overdue_flag+升级通知+计数），与 M04 会诊超时/M14 告警超时的"升级为动作"既定设计一致。
4. **大屏数据通道分工**：FU-M05-08 细化为"IoT 域主题直订复用 M14 WebSocket 三主题、护理业务域自建 /ws/nursing 聚合主题"（方案 3.5），前端双端点订阅；呼叫展示仅聚合转发（业务归 M16）。

**v1.1 统一审查修订记录（依据 `docs/specs/modules/90-cross-review.md` 统一裁决执行）**：
- **R3-07**：删除 §7 对 `inpatient.order.revoked` 的死订阅（转抄前无执行单，执行单撤销已由 `inpatient.order.cancelled` 覆盖）；§8 M04 集成点①同步。
- **R3-08**：§3.1 执行单生成规则显式排除 blood/surgery/exam 类医嘱不生成本模块执行单（归 M12/M10/M08）；execution_type"标本采集"限定为非检验标本（检验标本采集经 M07 条码/清单承载）。
- **M-18/R3-16**：§7 补订阅 `transfusion.infusion.started` / `transfusion.infusion.completed`（生成输血入量行，与 M12 Spec 登记对齐）；§4 io_record.source 枚举增补 `TRANSFUSION_AUTO`、`ICU_AUTO`、`ICU_MANUAL`；明确输血/ICU 自动入量行不受 ICU 病区手工出入量关闭开关影响（账仍落本模块 io_record，§8 M11 边界同步）。
- **R3-11**：§7 补订阅 `icu.patient.transferred-out`（解除患者一览"危/重"重症标识，与 M11 Spec 登记对齐）。
- **R3-15**：§5 补输注中断（EXECUTING→CANCELLED）回签规则——计划按部分执行回签（携实际输注量）、医嘱头经停嘱（stopped）达终态、M13 按实际量计费；§10 补对应边界用例。
- **R5-17**：§7 REST 补 `GET /stats/override-rate`（破码率统计）、`GET /stats/adverse-events`（不良事件分类统计），供 M19 取数。
- **R5-18**：§7 `POST /tasks` 调用方注记补 M16（紧急呼叫自动转任务，幂等键=call_no）；§8 M16 条目同步确认。
- **M-25**：§7 订阅 `patient.merged` 处成对补订 `patient.split`。
- **R3-12 核对**：§12-3"逾期升级为动作"转述与 M04 v1.1 新口径（会诊超时置 overdue_flag、状态停留 REQUESTED）一致，无需修改。
