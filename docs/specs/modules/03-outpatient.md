# M03 门诊服务 · 功能实现 Spec

| 属性 | 内容 |
| --- | --- |
| 模块编号 | M03 |
| Maven 模块 | `fuyun-outpatient`（schema：`outpatient`） |
| 版本 / 状态 | v1.1 / 统一审查修订（修订记录见 §12） |
| 上游依赖 | M01（认证/权限/字典/执业授权校验/通知/打印/审计）、M02（patient_id 与介质解析、健康档案过敏项、冻结拦截）、M13（划价/收费/退费/票据/医保结算、费用与结算事件）、M06（处方开立 API、审方与发药回执事件）、M07/M08（检验检查执行状态与报告回执）、M09（门诊病历文书、抢救记录）、M20（事件总线治理、幂等构件、延迟队列、出站留痕） |
| 下游被依赖 | M13（挂号/开单事件源）、M04（急诊转住院的 visit 引用与住院证）、M06（发药放行事件）、M07/M08（收费放行事件）、M05（门诊治疗执行回执的事件订阅方）、M18（互联网医院线上预约挂号/在线缴费复用本模块号源与预约服务）、M19（门诊工作量与信息页数据取数）、M20（事件契约登记方） |
| 对应总 Spec | FU-M03-01 ~ FU-M03-11 |

---

## 1. 模块定位与边界

**职责**：本模块是门诊（含急诊）业务主线的中枢，八项职责：① 号源池管理——排班模板、分时段号源池、放号规则、停诊与加号；② 预约挂号——窗口/自助机/公众号小程序/诊间多渠道统一预约与取号，爽约识别与限约信用管理；③ 挂号退号——当日挂号、改期、退号与退费联动；④ 分诊台管理——报到确认、二次分诊、优先级调整；⑤ 候诊叫号——队列管理、大屏与语音叫号、患者提醒；⑥ 门诊医生站——患者列表、接诊/诊毕、开单（检查/检验/治疗/处置）与处方开立动作的编排入口；⑦ 门诊收费工作台——窗口/诊间/自助统一的收费入口（资金与结算逻辑调 M13）；⑧ 专项场景——门诊药房发药联动放行、门诊护士站/治疗室执行记录、急诊绿色通道、自助机业务。本模块是**门诊 `visit_id` 的唯一签发主体**（遵循 M02 visit_id 结构规范，类型码 `O`），visit 与其挂号/分诊/叫号/开单/执行/取药/诊毕全旅程状态是本模块的核心资产。

**非职责**：资金收退付与医保结算逻辑（归 M13，本模块只提供收费业务入口并消费其事件）；处方内容与药师审方、发药调剂作业（归 M06，本模块只做"开立动作"编排、放行指令与状态聚合，不建处方表）；门诊病历文书内容与质控（归 M09，医生站嵌入其编辑能力，本模块只承载"书写动作发生在哪次就诊"的关联）；检验/检查的执行与报告（归 M07/M08）；住院病区护理执行闭环（归 M05，门诊治疗执行记录归本模块，见第 8 节边界结论）；患者身份与档案（归 M02）；字典与执业授权（归 M01）。

**模块红线**：
1. `visit_id` 只能由本模块按 M02 结构规范签发（类型码 `O` + 8 位日期 + 5 位当日流水，定长 14 位），签发后不可变、不可复用，必须与签发时点 `patient_id` 同时落库；其余任何模块不得生成门诊就诊标识。
2. 号源是全院唯一权威库存：任何渠道（窗口/自助/线上/诊间/外联）占用与释放号源必须经本模块号源服务，禁止绕过号源池直接"挂号"；停诊、退号必须同步回收号源并通知受影响患者。
3. 处方引用不复制：本模块只登记处方引用关系（M06 处方号 + 状态回执），禁止复制药品明细作为计费或展示的唯一来源；计费行归属见方案 3.4。
4. 金额、支付、退费动作一律调 M13 服务端完成（总 Spec 决策 D5），本模块界面传入的金额字段不采信；退号退费必须消费 `billing.refund.approved` 回执后才置终态，禁止本模块自行"退款成功"。
5. 就诊状态迁移必须经状态机服务校验并留迁移日志（README 约定）；已诊毕（FINISHED）或已退号（CANCELLED）的 visit 禁止再产生任何开单、缴费与执行动作。

## 2. 调研依据

1. 国家卫健委《门（急）诊诊疗信息页质量管理规定（试行）》（国卫办医政发〔2024〕16 号，2024-11-01 施行）：信息页含患者基本信息/就诊过程信息/诊疗信息/费用信息 4 大类 72 项；明确挂号时间（形成号条时间）、报到时间（报到系统记录）、就诊时间（医师系统确认接诊）三个时间节点；就诊类型代码（1 急诊/2 普通门诊/3 特需门诊/4 互联网诊疗/5 MDT/9 其他）；急诊患者分级四级（Ⅰ急危/Ⅱ急重/Ⅲ急症/Ⅳ亚急症非急症）；急诊患者去向八类代码（1 医嘱离院/2 医嘱转院/3 医嘱转社区/4 非医嘱离院/5 死亡/6 急诊留观/7 急诊转住院/9 其他）；信息页保存不少于 15 年。（来源：https://www.nhc.gov.cn/yzygj/c100068/202409/a58db40867bf4ea5a34f24a6b0486a9d.shtml ）
2. 《医疗机构统一号源门诊预约信息技术基本要求》（行业标准文本）：统一号源池应对各级医疗机构的放号、退号以及面向居民的预约征信等规则集中备案管理，并提供重点监管人群预约黑名单的管理与查询。（来源：https://dbba.sacinfo.org.cn/portal/download/e0c2fe21001c67d3288f0fb9a226b2fd1bf9584582c7f64302875bda6cafd0ed ）
3. 统一号源池产品实践：号源池与医院 HIS 实时对接（号源状态、停诊通知、取消停诊服务、预约取消服务闭环）；爽约规则参数化为"累计次数 + 统计时间范围 + 锁定期限 + 超期自动解锁 + 管理员手工解锁"；按渠道/医院/科室统计预约量。（来源：https://www.woshipm.com/pd/2471944.html ）
4. 爽约管理的真实生产规则：北京 114 平台自然年内无故爽约累计 3 次即进入爽约名单、3 个月内取消预约资格（https://www.114gh.com/ghzn/6389.html ）；广州健康通爽约 3 次自动列入黑名单限制预约（http://wjw.gz.gov.cn/xxgk/wgk/fwgk/content/post_9253017.html ）；杭州市肿瘤医院爽约制度——就诊当日不能取消预约，须到院退号，否则视为爽约（https://wushan.hz-hospital.com/content/default/27/1568 ）；北京市预约挂号服务公约——同一患者实名在同一就诊日、同一医院、同一科室只能预约 1 次（http://health.people.com.cn/n/2015/0203/c14739-26496318.html ）。
5. 分诊叫号生产方案：分诊叫号系统由分诊管理平台、自助签到机、候诊叫号屏构成并与 HIS 对接（https://baike.baidu.com/item/%E5%88%86%E8%AF%8A%E5%8F%AB%E5%8F%B7%E7%B3%BB%E7%BB%9F/7082010 ）；医技排队支持住院/门诊/预约/急诊患者同队列排队、呼叫优先级设定、签到/挂起/滞后操作（http://www.paiduixitong.com/medicaltechnologyqueuingsystem ）；二次分诊叫号流程与真人语音库播报（许昌市中心医院信息化方案 https://zfcg.henan.gov.cn/webfile/xuchang/rootfiles/2021/10/29/d62d1a11965a495c9a56faff7750e931.pdf ）。
6. 门诊医生站标准接诊流：以门诊病历为入口——调阅既往病历→诊疗→开检查→查看检验检查结果→诊断下达与处方开立→接诊下一位（http://www.mandalat.com.cn/product/11017/68272.html ）；东直门医院门诊医生工作站实现接诊全流程数字化实践（https://www.chima.org.cn/Html/News/Articles/9558.html ）。
7. 退号退费真实规则：北京海淀医院门急诊退费——开单医生工作站发起退费，项目未确认（未发药、未做检查化验）凭退费凭据直接窗口退费（https://hdhospital.com/Html/News/Articles/13968.html ）；老河口市第一医院三态规则——未交费未发药由开单医师作废、已交费未发药开单医师发起退费窗口办理、已交费已发药先退药再退费（https://www.lhkyyy.cn/show/54.html ）；无锡市第八医院"未诊即退"公告——预约后未取号、取号后未就诊退诊察费，已至现场就诊不予退费（https://www.wxxsph.cn/new/YuanNaGongGao/8355.html ）；"一次挂号管三天"政策——挂号有效期延长至 72 小时，期内复诊免挂号费（https://www.stdaily.com/web/gdxw/2024-12/06/content_269453.html ）。
8. 急诊绿色通道：绿通患者"先检查、用药，后补交费用"制度（https://www.lanlinghospital.com/portal/article/index/id/2355/cid/159.html ）；国家医保接口环境下门诊先诊疗后结算系统改造覆盖欠费转自费、自费转医保业务场景（http://cs.china-cmd.org/zgylsb/CN/article/downloadArticleFile.do?attachType=PDF&id=16720 ）；中国急诊信息化建设规范专家共识要求结构化急诊电子病历（含抢救区）（http://www.cem.org.cn/public/html/202409/20240903/index.htm ）；卒中绿通关键环节时间节点自动采集（http://www.cn-healthcare.com/api/third/article/515153 ）；"先诊疗后付费"信用就医模式——诊疗结束自动完成医保商保处理、自费部分从信用额度扣除（https://www.yxxxx.ac.cn/yxxxx/article/html/20251112 ）。
9. 自助机生产实践：北京协和医院一体化自助机集成建卡、挂号、报到、缴费、打印等 15 项功能（https://www.pumch.cn/detail/7617.html ）；清华长庚自助机支持身份证/医保卡/医保电子凭证自助建档、单据补打印、综合查询（https://www.btch.edu.cn/xxdt/xwdt/81037.htm ）；山西医科大学第一医院自助机增加签到、多种报告彩色打印、医保支付，凭申请单条码操作（https://www.sydyy.com/info/1199/4485.htm ）。
10. 门诊治疗执行：HIS 护士工作站门诊医嘱执行模块负责输液、皮试、肌注等治疗执行，执行后自动计费并打印瓶贴标签（https://developer.aliyun.com/article/1727567 ）；皮试管理流程——处方提交后护士站皮试模块锁定患者药品信息执行皮试并记录结果（https://zhuanlan.zhihu.com/p/3084989171 ）；真实采购需求含皮试结果登记划价、治疗组推送、治疗室额外费用录入与结算（铜陵市公共资源交易中心 https://ggzyjyzx.tl.gov.cn/ ）。
11. 发药闭环与药品追溯码：门诊药房全流程追溯闭环——退药环节经追溯码核验退药品种来源与时间和处方一致，确保退药确属本院发放（《中国药房》 https://journal.china-pharmacy.com/ ）；国家医保局要求药品追溯码"应扫尽扫"，发药与退药环节扫码采集（https://www.nhsa.gov.cn/art/2025/9/9/art_14_17831.html ）；智慧门诊药房经缓存二级库、自动补药、窗口自动核对机三重确认提高发药准确率（https://www.yydbzz.com/article/2022/1004-0781/1004-0781-41-9-1393.shtml ）。
12. 号源并发防超卖技术：医疗预约系统高并发下的号源超卖问题（防同一用户重复预约与同一时段超卖）以 Redis 分布式锁求解（https://blog.csdn.net/Y_yc0206/article/details/162458172 ）；Redis Lua 原子扣减与分布式锁两方案对比实践（https://zhuanlan.zhihu.com/p/1974422498784331316 ）；SETNX+Lua 实践（QPS 120→8200）与分段库存策略（https://developer.aliyun.com/article/1331677 ）；"Redis+Lua 扣库存→MQ 异步下单→DB 最终落库"异步架构（https://github.com/lifei6671/interview-go/blob/master/architecture/0003.md ）；行业经验——Redis 原子扣减即可防超卖，分布式锁是减少无效请求打到下游的流量漏斗而非防超卖必要条件（https://github.com/CoderLeixiaoshuai/java-eight-part ）；扣减流水表对账兜底（https://www.cnblogs.com/yaopengfei/p/19026255 ）。

## 3. 方案推导（关键设计点选型）

### 3.1 号源池生成模型：排班模板×日历实时展开 vs 纯动态计算 vs 模板预生成池行汇总

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 模板×日历实时展开为号源明细行 | 每个时段每个号别展开为逐号明细行，挂号占用一行 | 粒度过细：门诊高峰 2000 人次/h，日均号源行数万级，逐号行在对账、渠道配额、停诊批量作废时事务面大；逐号占用行锁在高并发抢号下竞争剧烈 |
| 纯动态计算（不落号源行） | 挂号时按排班+已约计数实时算余量 | 停诊、加号、渠道配额（线上/窗口/预留）、爽约回补等规则无处落库；高峰聚合计算昂贵；号源审计不可追溯 |
| **模板预生成池行汇总（选定）** | 排班模板按放号规则展开生成"排班日历（schedule）×号源池行（appt_number_pool：号别×时段）"，池行持有总量/渠道配额/已用量/乐观锁版本；号源占用、释放、停诊、加号全部落在池行粒度 | 池行数量可控（科室×医生×时段×号别），对账与停诊批处理事务面小；渠道配额与预留号规则显式建模（对齐统一号源池标准的放号/退号规则集中管理，调研依据 2）；放号=T+N 日滚动生成日历+池行的参数化任务；停诊=整池作废+已约患者批量通知改期（调研依据 3 的停诊/取消停诊服务） |

**结论**：模板预生成池行汇总。放号规则参数化：放号周期 T+N 天滚动、每日放号时点、渠道配额比例（线上/窗口/自助/预留）、预留号（专家保留号/复诊预留/急诊保留号）与释放时点；退号自动回池；"一次挂号管三天"复诊免费号以号别=复诊的专用池表达。

### 3.2 并发抢号与超卖防护：DB 行锁串行 vs 乐观版本重试 vs Redis 原子预扣 + DB 条件更新兜底

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| DB 行锁串行 | 对池行做条件更新（仅当已用号数小于总量时递增已用量），数据库直接保证不超卖 | 热门专家号池行成为热点行，行锁排队消耗连接池，高峰（放号瞬间集中抢号）拖垮挂号主链路（调研依据 12 的超卖/热点问题） |
| 乐观版本号重试 | 池行带 version，冲突重试 | 放号瞬间冲突率极高，重试风暴放大 DB 负载，成功率不可控 |
| **Redis 原子预扣 + DB 条件更新兜底（选定）** | 第一道闸：Redis Lua 脚本原子执行"校验余量>0 且患者限购未命中→扣减→写预约占位键（TTL=支付时限）"；第二道闸：预约单落库与池行条件更新（used<total）同事务，池行唯一约束防同一患者同一排班重复预约；Redis 不可用降级为直连 DB 条件更新（功能不中断）；定时对账 Redis 余量↔池行已用量，差异告警回补；线上预约未按时支付经 `fy.delay` 延迟队列自动释放号源 | 防超卖由原子扣减保证而非分布式锁（行业实证：锁只是流量漏斗，调研依据 12）；占位+支付时限与真实挂号业务形态一致；降级路径保证可用性；对账流水兜底最终一致 |

**结论**：Redis 原子预扣 + DB 条件更新兜底 + 占位支付时限 + 定时对账。限购规则：同一患者同一就诊日同一科室限约 1 次（预约服务公约，调研依据 4）；爽约限约期内全渠道禁止预约（窗口现场挂号不受限）。

### 3.3 就诊状态机设计：单一线性状态机 vs 无状态实时聚合 vs 主状态机+子单据状态机事件驱动

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 单一线性状态机 | 挂号→候诊→就诊中→…→诊毕严格线性 | 无法表达真实门诊的回环与并行：患者检查后回诊室复诊、多次开单多次缴费、执行与取药交叉进行 |
| 无状态实时聚合 | 不存就诊状态，每次按费用/单据/票号实时推导显示 | 高峰期每次聚合计算昂贵；无迁移留痕（等保审计不可过）；多端（大屏/医生站/患者端）显示口径漂移 |
| **主状态机 + 子单据状态机，事件驱动聚合推进（选定）** | visit 主状态机表达就诊旅程阶段（见第 5 节）；clinic_order（申请单）、queue_ticket（票号）、费用（M13）各自持状态机；visit 状态由本模块动作与订阅事件（billing.*/pharmacy.*/lab.*/imaging.*）幂等驱动迁移，全部迁移留日志 | 每个状态迁移有明确责任主体（分诊台/医生/收费事件/执行回执/系统超时），审计可追溯；回环（执行中→候诊复诊）合法；子对象状态与 visit 状态解耦演进，各模块 Spec 可独立细化 |

**结论**：主状态机 + 子单据状态机。迁移责任边界：分诊台驱动"待分诊→候诊"；医生站驱动"候诊→就诊中"与"诊毕"；M13 费用/结算事件驱动"待缴费"进出与放行；M06/M07/M08 执行回执驱动"执行中/待取药"；系统延迟任务驱动爽约与号源释放。诊毕前置校验：在途申请单必须为终态或经医生显式确认（作废/离院自担），并调 M09 待写文书清单校验（`GET /api/v1/emr/documents/pending-list`，可参数化为提醒不拦截），离院方式按国标去向代码记录（调研依据 1）。

### 3.4 与 M13 的计费联动时序：全同步调用 vs 全事件异步 vs 事件开单 + 同步收付 + 事件放行（混合）

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 全同步 | 开单时逐单调 M13 划价，收费时同步完成 | 开单高峰与计费洪峰耦合，M13 故障直接阻断问诊（级联失败）；但支付本身必须在场同步 |
| 全事件异步 | 开单、收费、放行全走事件 | 患者在收费窗口/自助机的支付必须秒级得到结算结果，全异步不满足窗口业务形态（与 M13 方案 3.2 结论一致） |
| **事件开单 + 同步收付 + 事件放行（选定）** | 开单：本模块事务内建申请单并发布 `outpatient.order.created`（携带计费行：项目 code+数量+用途摘要）→ M13 生成 PENDING 费用并冻结价格快照；收费：本模块收费工作台（窗口/诊间/自助同构界面）同步调 M13 预结算/结算 API；放行：消费 `billing.settlement.completed` → 本模块校验后置单据 CHARGED 并扇出 `outpatient.order.charged` → M06 获得发药放行、M07/M08/M05 获得执行放行；退费（先退药后退费）：已发药场景患者到 M06 药房退药受理（追溯码核验+批次回补，发布 `pharmacy.dispense.returned`）→ M13 解除执行占用并走退费审批（未发药场景经执行占用查询 API 前置校验后直接进审批）→ `billing.refund.approved` → 本模块扇出 `outpatient.order.cancelled`（退费原因）仅作终态确认（M06 未发药处方作废/已退药单据收敛、M07/M08 申请单作废、M05 关联撤销），本模块完成退号退费联动 | 与 M13 Spec 已声明契约逐条对齐（开单事件→PENDING 费用、收退费同步 API、settlement.completed/refund.approved 回流）；开单侧削峰可重放，收付侧同步在场景；放行扇出让执行域模块只依赖 M03 事件而不直接依赖 M13（保持 M03 作为就诊/开单主体的编排地位） |

**结论**：混合时序。计费行归属硬约定：非药品申请单（检查/检验/治疗/处置/材料）计费行由本模块 `outpatient.order.created` 携带；处方药品计费行由 M06 处方开立事件携带（本模块调用 M06 开方 API 后仅登记处方引用，不复制药品明细）——两类事件共同构成 M13 的 PENDING 费用来源，杜绝同一行费用双头生成。急诊绿通例外：绿通就诊的开单走"挂账放行"（M13 急诊绿通挂账支付方式），本模块订阅 `billing.charge.guaranteed`（M13 挂账放行回执）后置单据 CHARGED 并扇出含绿通标记的 `outpatient.order.charged`，免等待 `billing.settlement.completed`，绿通关闭时统一结算。

### 3.5 候诊队列与叫号：硬件排队机绑定 vs 纯内存队列 vs 排队表 + 优先级队列 + WebSocket 推送

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 绑定第三方排队机厂商方案 | 软硬件一体采购 | 与本系统审计/权限体系割裂，数据不可控，扩展（多院区/线上提醒）受限 |
| 纯内存队列 | 队列存应用内存 | 应用重启丢队列，多实例不一致，无法支撑分诊台跨实例操作 |
| **排队表 + Redis 优先级队列 + WebSocket 推送（选定）** | queue_ticket 落库（可审计、可恢复），Redis ZSET 维护"队列×优先级因子"的实时序（投票/过号/调级即时生效），叫号动作原子出队；候诊大屏/语音客户端经 WebSocket 订阅队列变更（刷新 ≤2s），语音由叫号客户端以真人语音库播报；患者端提醒经 M01 通知中心（短信/公众号） | 对齐生产方案构成（分诊管理平台+自助签到机+叫号屏+HIS 对接，调研依据 5）；优先级因子支持急（绿通/急诊分级Ⅰ~Ⅳ）、老幼残标记、回诊/复诊、预约时段顺序、过号降级；落库保证审计与故障恢复，Redis 保证实时性 |

**结论**：排队表 + Redis 优先级队列 + WebSocket 推送。二次分诊支持跨队列转接（换医生/换诊区）并重算优先级；过号票降级重排，超时不至按爽约（参数：过号 N 次后需重新报到）。

## 4. 领域模型

表设计统一遵循 README 第 3 节约定：雪花 BIGINT 主键、统一审计字段、TIMESTAMPTZ 服务器时间、逻辑删、金额 BIGINT（分值制）、状态字段 VARCHAR 常量。

| 实体 | 关键字段 | 说明 |
| --- | --- | --- |
| schedule_template 排班模板 | dept_id、doctor_id、eff_from/eff_to、week_pattern（每周几）、session（上午/下午/晚间）、appt_type（普通/专家/专病/急诊/复诊）、slot_rule（时段起止×号总数模板）、channel_quota_rule（渠道配额比例）、release_rule（放号周期 T+N、放号时点、预留号与释放时点）、room、status | FU-M03-01 配置面；号别与总 Spec"普通/专家/专病"对齐并扩展急诊/复诊专用号别 |
| schedule 排班日历 | template_id、sched_date、session、doctor_id、dept_id、total_quota、status（正常/停诊）、stop_reason | 由模板按放号规则批量生成，支持手工调整；停诊整日历生效 |
| appt_number_pool 号源池 | schedule_id、appt_type、slot_start/slot_end、total_quota、channel_quota（线上/窗口/自助/预留 JSON）、used_count、extra_used、version（乐观锁）、status | 号源权威库存行（号别×时段粒度）；加号消耗加号额度（extra_quota 挂 schedule）；池行唯一约束（schedule_id+appt_type+slot） |
| appointment 预约挂号单 | appt_no、patient_id、schedule_id、pool_id、appt_type、slot、channel（窗口/自助机/公众号/小程序/诊间/外联）、fee_status（未付/已付/已退）、pay_deadline（支付时限）、reschedule_of（改期链）、visit_id（取号后回填）、status | 多渠道唯一入口单据；窗口当日挂号直接建为已取号态 |
| visit 门诊就诊 | visit_id（14 位：O+yyyyMMdd+5 位流水，本模块签发）、patient_id、appt_id、dept_id、doctor_id、visit_type（急诊/普通/特需/互联网诊疗/MDT/其他，对齐信息页就诊类型代码：1 急诊/2 普通门诊/3 特需门诊/4 互联网诊疗/5 MDT/9 其他）、is_revisit、triage_level（急诊Ⅰ~Ⅳ）、insurance_type、green_channel_flag、挂号时间/报到时间/接诊时间/诊毕时间、disposition（离院去向国标代码 1~7/9）、finish_operator、status | 就业主实体与信息页"就诊过程信息"权威记录源（调研依据 1）；visit_id 与 patient_id 同事务落库 |
| triage_record 分诊记录 | visit_id、station_id、action（报到/二次分诊/调级/转诊区）、triage_level、target_queue、priority_factor（急/老幼残/回诊等因子）、nurse_id、reason | 一次就诊可多条（报到+多次调整）；老幼残标记来源于患者标签（M02）与分诊台人工设定 |
| queue_ticket 排队票号 | visit_id、queue_id（诊区/医生队列）、ticket_no、ticket_type（初诊/复诊/回诊/加号）、priority_score、queue_time、called_count、call_time、serve_time、status | 同一队列票号当日唯一；过号降级重排不改号 |
| clinic_order 门诊申请单 | order_no、visit_id、patient_id、order_type（检查/检验/治疗/处置/材料/处方引用）、ext_ref（M06 处方号/M08 检查预约号等外部单据引用）、order_doctor_id、valid_to（执行有效期）、status | 统一开单模型；明细行子表 clinic_order_item：收费项目 code（引用 M13 物价库）、数量、用法摘要、组套引用；处方引用行不复制药品明细（红线 3） |
| green_channel_record 绿通记录 | visit_id、channel_type（胸痛/卒中/创伤/危重孕产妇/危重儿童等）、start_time、end_time、credit_flag（挂账）、settle_status（待结算/已结算/转欠费）、关键时间节点（到院/分诊/用药/抢救起止，JSON） | FU-M03-10；抢救病历文书归 M09，本模块记录时间节点与费用放行策略 |
| appt_credit_record 预约信用记录 | patient_id、action（爽约/超时退号）、occurred_at、window（统计窗口）、restrict_from/restrict_to（限约起止）、release_reason | 爽约规则参数（窗口内次数阈值/锁定期）走系统参数；限约期内全渠道预约拦截 |
| kiosk_terminal 自助终端 | terminal_no、location、services（启用业务集）、status、last_heartbeat | 自助机注册与心跳；终端账号经 M01 接口账号体系 |
| kiosk_txn_log 自助业务流水 | terminal_no、action（建档/挂号/缴费/签到/打印）、biz_ref（业务单据引用）、result、occurred_at | 只增表；自助机业务对账与审计依据 |

关系要点：schedule_template 1:N schedule 1:N appt_number_pool；appointment N:1 appt_number_pool，1:0..1 visit；visit 1:N triage_record / queue_ticket / clinic_order / green_channel_record；patient（M02）1:N appointment / visit / appt_credit_record。

## 5. 状态机与业务流程

- **appointment**：`RESERVED（已预约，号源占位）→ TAKEN（已取号/已挂号，签发 visit）`；`RESERVED → CANCELLED（线上退号，释放号源+退挂号费）`；`RESERVED → NO_SHOW（爽约，超时未取号）`；`TAKEN → CANCELLED（当日退号，经退费联动）`；`RESERVED → CANCELLED + 新建 RESERVED（改期，reschedule_of 链）`。窗口/自助当日挂号一步直达 `TAKEN`。
- **visit**：`REGISTERED（已挂号/待分诊）→ WAITING（候诊，报到入队）→ IN_CONSULT（就诊中，医生接诊）⇄ PENDING_FEE（待缴费，存在待缴费用）⇄ IN_EXECUTION（检查检验/治疗执行中）⇄ PENDING_MEDICATION（待取药，存在已收费待发药处方）→ 回 IN_CONSULT（回诊复诊）`；终态：`FINISHED（诊毕，记录离院去向）`、`CANCELLED（已退号回滚）`、`NO_SHOW（爽约）`。回环合法（同一 visit 跨"一次挂号管三天"有效期，有效期参数化）；诊毕前置校验在途单据与待写文书（见 3.3）；`FINISHED/CANCELLED` 后禁止一切新开单与执行（红线 5）。
- **clinic_order**：`CREATED（开立）→ PENDING_FEE（M13 已生成待缴费用）→ CHARGED（已收费，放行）→ IN_EXECUTION（执行中，由 `lab.specimen.collected`/`imaging.exam.registered` 等执行回执驱动）→ COMPLETED（已完成）`；`CREATED/PENDING_FEE → CANCELLED（作废，必填原因，联动 M13 费用作废）`；`CHARGED/IN_EXECUTION → CANCELLED（退费逆向，仅在 billing.refund.approved 后，扇出 outpatient.order.cancelled 撤执行与终态确认）`。处方引用行（order_type=处方引用）的作废必须经 M06 作废 API（`POST /prescriptions/{no}/cancel`）发起，由 `pharmacy.prescription.cancelled` 回流驱动引用行 CANCELLED 与 M13 费用作废；`outpatient.order.cancelled` 对处方行仅作终态确认。
- **queue_ticket**：`WAITING → CALLED（已叫号）→ SERVING（就诊中）→ SERVED（完成）`；`CALLED → PASSED（过号）→ WAITING（降级重排）`；`WAITING/CALLED → CANCELLED（退号/转队列联动）`。
- **green_channel_record**：`OPEN（启用，费用挂账放行）→ SETTLING（关闭，统一结算中）→ SETTLED（已结算）/ ARREARS（转欠费台账）`。
- **appt_credit_record**：命中爽约规则 → 生成限约区间（restrict_from~restrict_to）→ 到期自动解除 / 管理员手工解除（留痕）。

主流程时序：

1. **当日挂号→就诊→收费→发药（门诊主链路）**：窗口/自助机选号 → 号源服务 Redis 预扣+落库（appointment=TAKEN，同一事务签发 visit_id=O+日期+流水，发布 `outpatient.visit.registered` 供 M13 医保就诊登记）→ 分诊台/自助签到（triage_record+queue_ticket，visit=WAITING）→ 医生站叫号接诊（visit=IN_CONSULT）→ 开检验/检查/治疗单（`outpatient.order.created`→M13 PENDING 费用）或调 M06 开方（M06 处方事件→M13 PENDING 费用；visit=PENDING_FEE）→ 患者收费工作台/自助机/线上同步调 M13 结算 → `billing.settlement.completed` → 本模块单据 CHARGED、扇出 `outpatient.order.charged`（M06 发药放行/M07 受理/M08 登记/M05 治疗执行）→ 执行与发药回执回流 → 医生站回诊查看报告（M07/M08 报告事件提醒）→ 诊毕（校验在途单据+选择离院去向）→ `outpatient.visit.finished`。
2. **预约-爽约-退号**：公众号预约（线上支付挂号费或 15 分钟支付时限占位）→ 就诊日取号（自助机/窗口，appointment=TAKEN 回填 visit_id）→ 超时未取号经延迟任务置 NO_SHOW、累加爽约信用、按参数触发限约 → 线上退号（时限内）：号源回池+`billing.refund.approved` 回执后费用原路退+appointment=CANCELLED；时限外须到院窗口办理；当日已报到/已接诊不可线上退（窗口人工按"未诊即退"规则处理）。
3. **急诊绿通**：急诊分诊（分级Ⅰ~Ⅳ）→ 启用绿通（green_channel_record=OPEN，visit 标记）→ 开单/处方事件携带绿通标记 → M13 挂账放行并发布 `billing.charge.guaranteed` → 本模块置单据 CHARGED、扇出含绿通标记的 `outpatient.order.charged` → 抢救/检查/用药执行，本模块采集关键时间节点 → 绿通关闭（SETTLING）→ 家属补缴经 M13 统一结算（SETTLED）或转欠费台账（ARREARS，急诊欠费清单供 M19 与财务追缴）→ 抢救记录文书在 M09 书写、本模块仅挂接时间节点。
4. **停诊**：医生/科室停诊 → schedule 置停诊、当日池行作废 → 已约患者经 M01 通知中心批量通知（短信/公众号）→ 患者改期（改期链）或退号退费（自动原路退）→ 全程审计。

## 6. 功能实现设计（逐 FU）

| FU | 实现设计要点 |
| --- | --- |
| FU-M03-01 号源池管理（P0） | 排班模板（科室/医生/时段/号别/时段模板/诊疗室）+ 放号规则（T+N 滚动放号、放号时点、渠道配额、预留号释放）参数化配置（方案 3.1）；排班日历批量生成与手工调整；停诊整池作废+已约患者批量通知改期或退费；加号额度按医生按日设定；号源占用/释放全量留痕；号源余量对外查询 API 供各渠道与 M18 复用 |
| FU-M03-02 预约挂号（P0） | 多渠道统一预约 API（窗口/自助机/公众号/小程序/诊间/外联平台经 M20）；并发防护按方案 3.2（Redis 预扣+DB 兜底+占位支付时限+延迟释放）；限购（同日同院同科限约 1 次）与爽约信用（参数化：统计窗口/次数阈值/锁定期，北京 114 与广州健康通同款口径，调研依据 4）全渠道拦截；预约成功/取消/改期/停诊消息经 M01 通知中心多通道触达 |
| FU-M03-03 挂号退号（P0） | 当日挂号（窗口/自助一步取号签发 visit）；改期=退旧号新（reschedule_of 链，号源先占新后退旧防两头空）；退号规则参数化：线上退号时限（默认就诊前一日）、时限外窗口办理、已报到/已接诊不可线上退、"未诊即退"（未发生医疗服务退诊察费，调研依据 7）；退号退费联动：本模块发起退号→调 M13 退费→消费 `billing.refund.approved` 回执→appointment=CANCELLED+号源回池；挂号有效期支持"一次挂号管三天"模式参数 |
| FU-M03-04 分诊台管理（P0） | 报到确认（分诊台/自助签到机双入口，预约患者取号即报到可配置）；二次分诊（换医生/换诊区跨队列转接重排）；优先级调整（急诊分级Ⅰ~Ⅳ对齐信息页标准、老幼残标记、绿通置顶）；分诊记录全留痕；报到时间按国标采集写入 visit（调研依据 1） |
| FU-M03-05 候诊叫号（P0） | 队列模型按方案 3.5（排队表+Redis 优先级队列+WS 推送）；医生站呼叫/叫号屏/语音播报联动；过号降级重排与再报到；大屏刷新 ≤2s；患者端候诊提醒（前面等待人数/预计时间）经 M01 通知；号票与叫号全程可审计恢复 |
| FU-M03-06 门诊医生站（P0） | 候诊患者列表（本队列票号+患者摘要+过敏标识来自 M02 健康档案订阅缓存）；接诊/暂停接诊/诊毕（诊毕前置校验：在途单据终态确认 + 调 M09 待写文书清单 `GET /api/v1/emr/documents/pending-list` 校验，可参数化为提醒不拦截 + 离院去向国标代码）；病历书写嵌入 M09 编辑能力（本模块只传 visit 上下文，病历内容归 M09）；开单：检查/检验/治疗/处置/材料统一 clinic_order→`outpatient.order.created`；处方：调 M06 开方 API（开方前强校验 M01 执业授权 `practice/check`，处方权/抗菌药分级），仅登记处方引用；检查检验结果查看入口调 M07/M08；接诊时间按国标采集（调研依据 1）；转住院：开具住院证（去向代码 7，记录住院证开具时间，M04 入院登记引用 visit_id） |
| FU-M03-07 门诊收费（P0） | 收费工作台（窗口/诊间/自助三形态同构）：划价预览、收费、退费入口、票据打印/电子发票取票，全部调 M13 同步 API（本模块零资金逻辑）；医保患者结算由 M13 完成医保链路；消费 `billing.settlement.completed` 放行执行与发药（方案 3.4）；退费发起在收费工作台、执行占用校验由 M13 前置完成 |
| FU-M03-08 门诊药房发药（P0） | 本模块职责=缴费处方放行与闭环状态聚合：`billing.settlement.completed` → 处方引用行 CHARGED → 扇出 `outpatient.order.charged` 放行 M06 配药（绿通挂账场景由 `billing.charge.guaranteed` 驱动同一放行扇出，事件携带绿通标记）；M06 发药回执事件回流 → visit 待取药解除、医生站/患者端可见"已发药"；退药（先退药后退费）：患者到 M06 药房退药受理（追溯码核验退药来源+批次回补，发布 `pharmacy.dispense.returned`，调研依据 11）→ M13 解除执行占用并走退费审批 → `billing.refund.approved` → 本模块扇出 `outpatient.order.cancelled` 仅作终态确认（未发药处方作废/已退药单据收敛）；配药/扫码核对/发药签名等调剂作业实体与界面归 M06（其 Spec 为权威），本模块不建调剂表 |
| FU-M03-09 门诊护士站/治疗室（P1） | 治疗执行记录：治疗/注射/换药/皮试申请单（已收费）推送治疗室工作台 → 护士核对执行 → 执行回签（clinic_order=COMPLETED，发布 `outpatient.order.executed` 供 M13 联动补费/医生站展示）；皮试：皮试登记→结果判定（阴性放行注射/阳性拦截并经 M02 API 回写过敏史健康档案）；治疗室额外耗材补费经 M13 手工计费通道；瓶贴/治疗单打印走 M01 模板；执行边界结论见第 8 节 |
| FU-M03-10 急诊绿色通道（P1） | 绿通启用/关闭（按病种通道类型）、急诊分级（Ⅰ~Ⅳ）与绿通队列置顶；费用挂账放行策略（方案 3.4 例外路径，M13 绿通挂账支付方式）；关键时间节点自动采集（到院/分诊/用药/抢救起止，对齐卒中绿通实践，调研依据 8）；关闭后统一结算或转欠费台账；抢救病历归 M09（结构化），本模块提供时间节点数据 |
| FU-M03-11 自助机业务（P1） | 自助建档（调 M02 建档 API+介质核验适配器）；自助挂号/取号（同号源服务）；自助签到（报到入队）；自助缴费（调 M13 收银台，医保电子凭证扫码结算由 M13 支持）；报告打印（调 M07/M08 报告查询，已发布报告方可打印）；票据/单据补打印；终端注册、心跳监控与业务流水对账（kiosk_txn_log）；终端账号最小权限+操作留痕 |

## 7. 对外接口

**REST（`/api/v1/outpatient/` 前缀，节选）**：
- 号源与排班：`GET/POST/PUT /schedule-templates`、`POST /schedules/generate`、`GET /schedules`、`POST /schedules/{id}/stop|resume`、`GET /number-pools/available`（可约号源查询，供全渠道与 M18）、`POST /number-pools/{id}/extra-quota`（加号额度）
- 预约挂号：`POST /appointments`（多渠道统一预约/当日挂号）、`POST /appointments/{no}/take`（预约取号）、`POST /appointments/{no}/cancel`（退号）、`POST /appointments/{no}/reschedule`（改期）、`GET /appointments?patientId=&date=`
- 就诊：`GET /visits?patientId=`、`GET /visits/{visitId}`、`POST /visits/{visitId}/admit`（接诊）、`POST /visits/{visitId}/finish`（诊毕，含离院去向）、`GET /visits/{visitId}/info-page`（门诊诊疗信息页数据集视图，M19/M09 取数）
- 分诊与队列：`POST /triage/check-in`（报到）、`POST /triage/adjust`（二次分诊/调级/转队列）、`POST /queue/call`、`POST /queue/tickets/{id}/pass|recall`、`GET /queues/{queueId}/tickets`（队列快照）
- 医生站开单：`POST /visits/{visitId}/orders`（开单）、`POST /orders/{no}/cancel`（作废；处方引用行作废经本接口转调 M06 `POST /prescriptions/{no}/cancel`，由 `pharmacy.prescription.cancelled` 回流驱动引用行 CANCELLED 与 M13 费用作废）、`GET /orders?visitId=`、`GET /doctor/patient-queue`（候诊列表）
- 绿通：`POST /green-channels`（启用）、`POST /green-channels/{id}/close`、`GET /green-channels?status=`
- 自助机：`POST /kiosk/{terminalNo}/register|take|check-in|pay|print`（各业务统一入口，内部转调对应服务）、`GET/POST /kiosk-terminals`
- 信用：`GET /appt-credits?patientId=`、`POST /appt-credits/{id}/release`（手工解除）

**MQ 事件（发布，经 `fy.topic`，信封遵循 M20 治理约定，登记 event_registry）**：
- `outpatient.visit.registered`（挂号/取号成功，载荷含 visit_id/patient_id/就诊类型/科室医生，M13 医保就诊登记依据）
- `outpatient.visit.finished`（诊毕，含离院去向代码与时间节点）
- `outpatient.visit.cancelled`（退号回滚）/ `outpatient.visit.no-show`（爽约）
- `outpatient.appointment.booked`（预约成功）/ `outpatient.appointment.cancelled`（退号完成：号源已回池、退费联动已触发，M18 患者端订单同步与 M19 统计依据）/ `outpatient.appointment.rescheduled`（改期）
- `outpatient.schedule.stopped`（停诊，已约患者改期/退费联动依据）
- `outpatient.order.created`（申请单开立，携带非药品计费行）/ `outpatient.order.cancelled`（作废或退费逆向终态确认：未发药处方作废/已退药单据收敛，M06/M07/M08/M05 退费逆向作废联动依据）/ `outpatient.order.charged`（已收费放行，含绿通挂账放行场景并携带绿通标记，M06/M07/M08/M05 执行放行依据）/ `outpatient.order.executed`（门诊治疗执行完成回执）
- `outpatient.queue.called`（叫号，患者端提醒依据）
- `outpatient.green-channel.opened` / `outpatient.green-channel.closed`（绿通启停，M13 挂账/结算依据）

**MQ 事件（订阅，全部经 M20 幂等构件消费）**：
- `billing.settlement.completed` / `billing.charge.guaranteed` / `billing.refund.approved` / `billing.fee.created`（M13：结算放行扇出、绿通挂账放行回执、退号退费联动、待缴费状态校正）
- `patient.created` / `patient.merged` / `patient.split`（成对订阅：合并读侧归一、拆分逆映射同步刷新）/ `patient.frozen` / `patient.unfrozen`（与 patient.frozen 成对订阅，冻结解除后解除拦截）/ `patient.identifier.changed` / `patient.health-summary.updated`（M02：患者缓存与爽约主体归一、冻结拦截、过敏提示）
- `pharmacy.prescription.created` / `pharmacy.prescription.cancelled`（M06：处方生效登记与作废回流；事件名以 M06 Spec 登记为准）
- `pharmacy.prescription.approved` / `pharmacy.prescription.rejected`（M06：人工审方处置回执——rejected 驱动医生站驳回提醒与改方入口，approved 为提醒类按需订阅）
- `pharmacy.dispense.completed` / `pharmacy.dispense.returned`（M06：发药回执与退药受理回流，驱动 visit 待取药状态聚合与解除）
- `lab.specimen.collected` / `imaging.exam.registered`（M07/M08：标本采集/检查登记执行回执，驱动 clinic_order 与 visit 执行中迁移）
- `lab.report.published` / `imaging.report.published`（M07/M08：报告到达提醒与自助打印放行；事件名以 M07/M08 Spec 登记为准）
- `system.dict.published` / `system.org.changed` / `system.user.changed` / `system.param.changed` / `system.practice.changed`（M01：字典/组织/用户/参数/执业授权缓存刷新）

**WebSocket**：`/ws/outpatient/queue/{queueId}`（候诊队列变更与叫号推送：大屏、语音客户端、医生站列表）；`/ws/outpatient/doctor/{doctorId}`（医生站患者到达/报告到达提醒）。语音播报由叫号客户端本地播报，不走 MQ。

> **P1 PR-5 落地注记（2026-09-22，M03 门诊主流程实装交付面）**：本模块已随 P1·PR-5（计划
> `docs/superpowers/plans/2026-09-20-p1-pr5-m03-outpatient.md`）部分实装，以下八点为落地口径；
> 未注记条目（绿通/自助机/治疗执行、M07/M08/M05/M18/M19 消费面等）维持声明面，随 P2/P3 交付。
> ① **事件契约实装面**：id 23（order.created）/25（order.charged）/31（order.cancelled）载荷经 V204
> 以「UPDATE（存量卷命中）+ WHERE NOT EXISTS 兜底 INSERT（新库落冻结行）」双语句形态冻结（CF-5 双向
> 评审声明随 PR，两应用序同终态——偏差①）；新增登记 id 32–40：visit.registered / visit.finished /
> visit.cancelled、**visit.no-show（id 35）仅登记无发布点**（id 27 先例，禁发布——爽约号源释放与信用
> 限约在 appointment 链承载，visit 维度为声明态）、appointment.booked / appointment.cancelled /
> appointment.rescheduled、appointment.timeout（延迟队列回调内部事件，自产自消）、schedule.stopped；
> **`outpatient.queue.called` 不登记 event_registry**（§7 :166 表述的事件登记豁免——叫号走纯 WS 推送，
> 无 MQ 发布点）；payload record 全集 11 个（`outpatient/api` 包），字段与 event_registry payload_desc
> 逐字同源。② **号源池 V200 三表**（schedule_template/schedule/appt_number_pool）与**双道闸降级口径**
> （Task 4/5 对齐裁定，较 §3.2 声明面收紧）：第一道闸 Redis Lua 余量不足（-1）判 OP-1003 直接拒绝
> **不降级**（余量谓词旁路即超卖面）；**仅 Redis 异常（连接/超时）降级 DB 条件更新**（第二道闸
> used<total 谓词，0 行重读重试 ≤2 次后判 OP-1003 并回补 Redis 持有）；加号授权增量 total_quota、
> extra_used 为占用计数列、extraQuota 同步 INCRBY 池键；池键 `fy:outpatient:pool:{poolId}`
> TTL=排班日次日 02:00 对账窗口锚。③ **visit_id 签发**（裁决 11）：O+yyyyMMdd+5 位流水的当日流水经
> Redis 键 `fy:outpatient:visit-seq:{yyyyMMdd}` INCR 承载（TTL=48h；CAS 落败跳号业务无副作用），
> 签发与 appointment casTake 同事务回填 visit_id。④ **退号四分支**（裁决 7 资金无涉红线）：入口守卫
> （存在性/终态/线上退号时限）后按态分流——支付时限内未支付免退费直取消+回池 / RESERVED 已付走退费链
> 原态占位待回执 / TAKEN 走退费链（先核 visit 态）/ 已报到已接诊拒线上退（OP-1010）；退费一律经
> `OutpatientBillingPort` → billing `POST /api/v1/billing/refunds` **免审档（DAY_CORRECTION 自动直退）
> 统一通道**，appointment/visit 终态一律消费 `billing.refund.approved` 回执后置（本模块零金额逻辑）；
> **W-20 规避注记**：P1 挂号费>0 演示数据（0 元结算 400 收窄面已转产品待办 TASK.md W-20，现状规避
> 路径维持）。⑤ **WS 面**（裁决 12 自建 configurer，禁依赖 fuyun-iot）：`OutpatientWebSocketConfig`
> 注册 STOMP 端点 `/ws/outpatient`（纯 WebSocket 无 SockJS）+ CONNECT 帧级鉴权拦截器（镜像 iot 形态，
> 收敛工单 TASK.md W-25）；两目的地 `/topic/outpatient/queue/{deptCode}`（大屏/语音）与
> `/topic/outpatient/doctor/{doctorId}`（医生站提醒）；REST 快照 `GET /queues/{queueId}/tickets` 与
> WS 双通道。**§7 :179 通道表述的显式映射（转译关系）**：`/ws/outpatient/queue/{queueId}` 与
> `/ws/outpatient/doctor/{doctorId}` 系沿 iot 范式的「端点/目的地」两段式声明——实装=统一 STOMP 端点
> `/ws/outpatient` + 订阅目的地 `/topic/outpatient/queue/{deptCode}`（**queueId=dept_code 诊区队列**，
> 偏差⑧）/ `/topic/outpatient/doctor/{doctorId}`；Spec 路径字面**非直连 URL**，客户端按端点连接、
> 按目的地订阅。⑥ **portal 匿名通道**（裁决 13）：`/api/v1/outpatient/portal/**` 单条目入
> SystemWebConfig 免认证白名单（PortalAppointmentController 三端点：`GET /portal/schedules` 号源查询、
> `POST /portal/appointments` 预约、`POST /portal/appointments/{no}/cancel` 退号），免 401；服务端经
> 介质解析（标识号→patientId，patient api 端口）定患者，操作者留痕取哨兵值 `PORTAL`（不经
> OperatorContextHolder）；**portal 患者账号体系随 M18/P6 完整化**（P1 演示口径），限流/风控同随
> M18 注记。⑦ **演示终点直线段声明**（裁决 0）：P1 验收直线段=挂号→就诊→开单→收费→发药→诊毕；
> visit 枚举 IN_EXECUTION/PENDING_MEDICATION/NO_SHOW 与 clinic_order 枚举 IN_EXECUTION/COMPLETED 为
> **声明态**——仅注册状态机合法迁移对（javadoc 注记触发事件源与阶段），P1 无生产触发点（lab/imaging
> 执行回执事件源 P3 前缺位、发药回执仅做「已发药」聚合展示），非死代码豁免面。⑧ **排除面回执**：
> FU-M03-09（门诊护士站/治疗室）/FU-M03-10（急诊绿通）/FU-M03-11（自助机）P1 排除，与 P1 计划
> :108-110 对齐——`outpatient.order.executed` 不登记不实装、green_channel_record 不建表、
> `billing.charge.guaranteed` 不订阅、`outpatient.green-channel.opened/closed` 不登记、
> kiosk_terminal/kiosk_txn_log 不建表；分诊台仅承载报到/二次分诊/调级（绿通置顶随排除面顺延）。
> **契约缝三条**（P1 契约增补回补，禁虚构契约未呈现——前端对应呈现面缺失已在交付面注记归因）：
> **D-2** QueueTicketVO 缺 triageLevel（分诊台级别徽标列无从取数）——**已修复（W-29，2026-09-22，
> 出参补列，数据源 visit.triage_level 权威快照）**；**D-3** ClinicOrderVO 缺 dispense_status
> （医生站「已发药」镜像列无从取数）——**已修复（W-29，2026-09-22，VO 补投影）**；**D-9**
> TriageAdjustRequest 缺 reason（调级理由必填未呈现）——**已修复（W-29，2026-09-22，DTO 补
> reason 落既有 triage_record.reason 列，LEVEL_ADJUST 服务层必携校验并修复留痕错位传参）**。
> **D-4 schema 同名坍缩归因**——**已修复（W-29，2026-09-22）**：springdoc 注册层 outpatient
> `ClinicOrderVO.Item`（itemCode/quantity/usageSummary）与 outpatient dto `PrescriptionOpenRequest.Item`
> （drugId/quantity/…；两坍缩对**均在 outpatient 模块内**——原表述「pharmacy PrescriptionOpenRequest.Item」
> 系归因勘误，pharmacy 侧同构类为 api `PrescriptionOpenCommand.Item`，模块间出站命令不在
> springdoc 契约面）内嵌 record 共名 `Item`，openapi 生成物中两者坍缩为单一 `Item` schema
> （ClinicOrderVO.items 反向引用 drugId 形态；openapi-typescript 系忠实镜像，运行时消费无行为
> 损害）；根治经 `@Schema(name=...)` 分立注册名（`ClinicOrderItem`/`PrescriptionItem`，backend
> 首例）实现，不动类名，生成物已同 PR 重生成分立。

## 8. 集成点

- **M01**：认证与 RBAC（号源配置/退号/加号/绿通启用/退费入口/信用解除独立权限点）；执业授权强校验（开方与开单前调 `POST /api/v1/system/practice/check`）；字典（号别/就诊类型/离院去向引用 M01 字典 code，不自建副本）；通知中心（预约/停诊/叫号提醒多通道）；打印模板（号条/票号/瓶贴/治疗单）；审计切面；订阅其主数据广播事件刷新缓存。
- **M02**：一切业务以 `patient_id` 关联；挂号前经解析服务核验（FROZEN/MERGED 拒绝）；visit_id 按 M02 结构规范签发（类型码 `O`，本模块唯一签发主体，M04 签发 `I`）；皮试阳性经 M02 API 写入过敏史健康档案；老幼残标记读取患者标签；实现 M02"在途就诊查询"SPI 扩展点（门诊未结就诊存在时阻断患者合并）。
- **M13（核心调用契约，双向对齐其 Spec）**：① 本模块开单事件与 M06 处方事件 → M13 生成 PENDING 费用；② 本模块收费工作台调 M13 划价/预结算/结算/退费 API（同步）；③ M13 发布 `billing.settlement.completed`（结算放行）/ `billing.charge.guaranteed`（绿通挂账放行回执）/ `billing.refund.approved`（退费审批通过）→ 本模块放行发药与执行、完成退号退费联动；④ 退费执行占用前置校验由 M13 调执行域查询 API 完成（已发药场景须先经 M06 退药受理，"先退药后退费"），本模块不重复校验；⑤ 绿通挂账支付方式与欠费台账在 M13。
- **M06（处方与发药边界）**：处方内容、审方、调剂作业（配药/扫码核对/发药签名/退药回收）归 M06；本模块医生站为处方"开立动作"入口（调 M06 同步 API），仅登记处方引用（ext_ref），药品计费行由 M06 处方事件携带；发药放行指令经本模块扇出事件（`outpatient.order.charged`）下发；退药由 M06 药房窗口受理并回流 `pharmacy.dispense.returned`，`outpatient.order.cancelled` 仅作退费终态确认（不承担退药指令）。
- **M07/M08**：检验/检查申请单开立后经 `outpatient.order.charged` 放行受理；执行状态与报告回执事件回流驱动 visit 状态与自助打印放行；退费逆向经 `outpatient.order.cancelled` 作废申请单；退费占用校验数据由其查询 API 提供（经 M13 前置调用）。
- **M05（皮试注射执行归属结论）**：门诊治疗室执行（皮试/注射/换药）记录**归 M03**（FU-M03-09 在本模块功能清单内）；M05 聚焦住院病区护理（病区患者/护理文书/移动护理/输液闭环），服务对象为在院患者。两模块仅在前端复用 PDA 扫码交互组件（前端资产复用，非模块依赖）；M05 可订阅 `outpatient.order.executed` 用于全院执行视图统计，无反向调用。
- **M09**：门诊病历文书、抢救记录在 M09 书写（医生站嵌入其编辑器并传 visit 上下文）；诊毕前置校验调 M09 待写文书清单 API（`GET /api/v1/emr/documents/pending-list`，可参数化为提醒不拦截）；门诊诊疗信息页的诊疗段取自 M09，本模块提供就诊过程段权威数据与信息页数据集视图 API（信息页不纳入病历，保存 ≥15 年，调研依据 1）。
- **M04**：急诊转住院（去向代码 7）时本模块记录住院证开具时间，M04 入院登记引用门诊 visit_id（在其住院域新建 `I` 类型 visit_id，两 visit 以转诊关系关联）。
- **M18**：互联网医院线上预约挂号/取号/线上缴费复用本模块号源服务与预约 API（患者端经 M18 入口，业务落本模块，渠道标记为线上）。
- **M19**：门诊工作量、候诊时长、爽约率、信息页数据集等统计取数 API（只读）。
- **M20**：事件信封/交换机/队列治理、幂等构件、延迟队列（支付超时释放号源 `delay.appointment-timeout`、爽约判定）、外联平台出站留痕；本模块全部事件先登记 event_registry。
- **M14**：无直接依赖（门诊治疗室设备数据不构成本模块链路；如未来门诊输液监测接入，经 M16/M14 事件旁路，不在本期）。

## 9. 非功能与安全

- 性能：挂号/取号接口 P95 < 500ms（对齐总 Spec 门诊高峰 2000 人次/h）；号源预扣 P95 < 50ms（Redis 原子操作）；叫号端到端（医生点击→大屏/语音）≤ 2s；医生站候诊列表刷新 ≤ 1s；开单事件产生 PENDING 费用的端到端延迟 ≤ 3s（M13 事件链路）。
- 可用性：挂号/收费/发药放行 7×24（总 Spec 核心可用性）；Redis 不可用时号源扣减降级直连 DB 条件更新（性能下降功能不中断）；M13 不可用时开单可继续（PENDING 费用事件积压重放），收费受影响显式提示。
- 一致性：号源 Redis 余量与池行每日对账+差异告警回补；visit 状态与子单据状态以事件回执为权威校正（本模块预置状态仅作乐观展示）；事件发布走 outbox（M20 治理约定），订阅幂等。
- 审计：退号、改期、加号、停诊、绿通启停、信用手工解除、号源配置变更、自助终端启停全量审计（M01 审计切面）；visit 状态迁移日志独立留痕。
- 权限：号源管理员/分诊护士/医生/收费员/治疗室护士/自助终端账号分权；跨科室数据按 M01 数据范围隔离；医生站开方强校验执业授权（含抗菌药分级）。
- 合规映射：门（急）诊诊疗信息页 72 项中的就诊过程段为本模块权威采集项（挂号/报到/接诊时间、就诊类型、急诊分级、去向代码，调研依据 1）；实名制就诊（介质核验经 M02）；敏感信息（患者姓名/证件）展示脱敏经 M02 脱敏规则，日志与事件载荷禁带敏感明文；等保三级审计与传输加密；医保"门诊号不可重复"经 visit_id 唯一约束与 M13 就诊登记对齐。
- 数据留存：门诊业务数据在线 ≥2 年后归档；门诊诊疗信息页数据集留存 ≥15 年（国标要求）；号源占用/退号流水随 appointment 归档保留。

## 10. 测试要点

- 正常：当日窗口挂号→分诊→接诊→开单→收费→放行→执行/发药→回诊→诊毕全链路（含每一步 visit 状态与事件回执核对）；公众号预约（支付时限内）→次日取号就诊；改期后旧号释放新号占用且号源余量正确；绿通挂账→关闭补缴结算全链路；已发药退费全链路（先退药后退费：M06 退药受理 `pharmacy.dispense.returned` → `billing.refund.approved` → `outpatient.order.cancelled` 终态收敛）；自助机五类业务（建档/挂号/缴费/签到/打印）各自端到端。
- 边界：热门专家号放号瞬间并发抢号——超卖为零、限购拦截、失败请求快速返回；支付时限到期与支付成功并发（先到先得，号源不双占）；退号时限边界（时限前最后一秒线上退 vs 时限后转窗口）；医生加号达到当日加号上限后拒绝再加，加号患者入队位置与优先级正确；"一次挂号管三天"第三日回诊；诊毕时存在未缴费单据的显式确认路径；爽约第 3 次（阈值边界）触发限约与到期自动解除；过号降级重排后再次叫号顺序正确；停诊时已有预约患者全部收到通知且改期/退费不产生孤儿号源。
- 异常：`billing.settlement.completed` 重复投递仅放行一次（幂等）；退费审批通过前患者重复发起退号被拦截；Redis 预扣成功但落库失败→占位超时自动回补号源；M06 发药回执丢失→对账任务发现并人工触发补偿；执行中单据退费申请被 M13 前置校验拦截；皮试阳性后同处方注射被拦截且过敏史回写成功；叫号服务重启后队列从排队表完整恢复。
- 安全：无权限角色调用退号/加号/绿通启用 403 且留审计；A 科室医生查看 B 科室候诊列表被数据范围拦截；自助终端账号越权执行管理动作被拒；日志与事件载荷中患者敏感字段脱敏抽验。

## 11. 自审记录

- [x] 无 TBD/TODO/占位符，13 项内容完整（文档头 + 12 节）
- [x] 覆盖 FU-M03-01~11 全部条目，无遗漏、无私增（FU-M03-09/10/11 按 P1 定位细化；停诊/改期/加号/信用管理为 FU-M03-01~03 的规则细化，非新功能点）
- [x] 内部一致：领域模型 ↔ 状态机 ↔ API ↔ 测试一一对应（appointment/visit/clinic_order/queue_ticket/green_channel_record/appt_credit_record 六个状态机均有对应接口、流程与测试项；schedule/number_pool/kiosk 有管理接口与测试场景）
- [x] 符合跨模块约定：schema=outpatient；visit_id 按 M02 结构规范签发（类型码 O、14 位、唯一签发主体）；金额 BIGINT（分值制） 且资金动作全部调 M13；事件命名 `<模块>.<实体>.<动作>`、信封合规、消费走 integration.received_event 幂等；REST 路径 `/api/v1/outpatient/`；字典只存 M01 code 引用；状态字段 VARCHAR 常量+迁移日志；无跨模块读表（处方/病历/检验检查/结算均经 API 与事件）
- [x] 依赖方向正确：依赖 M01/M02/M13/M20 及 M06/M07/M08/M09 的对外接口与事件；无反向依赖（M05 对本模块事件的订阅为被动声明）；被依赖清单明确
- [x] 方案推导 5 个关键点均有备选对比与依据，含任务要求的三个必选点（3.1+3.2 号源池模型与并发超卖防护、3.3 就诊状态机、3.4 计费联动时序），每个结论附调研来源
- [x] 无代码级实现（无类名/方法体/SQL DDL；表设计为"表-关键字段-约束"粒度；Redis Lua/雪花 ID 为技术名词而非代码）
- [x] 歧义消除：处方计费行归属（M06 事件携带）、发药作业归属（M06）与放行联动归属（本模块）、门诊治疗执行归属（本模块）、visit 签发时点（取号/挂号确认）、退号退费终态以 M13 回执为准、绿通挂账例外路径等均已显式定义
- [x] 术语与总 Spec 一致（号源池/号别/预约挂号/退号/分诊/候诊叫号/接诊/诊毕/开单/处方/发药/绿色通道/自助机）

## 12. 与总 Spec 的偏差

无偏差。四处细化澄清（已经统一审查裁决确认：1/2/4 采纳，3 按主方案落定）：
1. **FU-M03-08 与 FU-M06-04 的分工**：总 Spec 在 M03 与 M06 均列"门诊发药"，按"M03=缴费处方放行指令与闭环状态聚合、M06=配药/扫码核对/发药签名/退药回收调剂作业"切分，两处条目均完整落地，见第 1、6、8 节。
2. **FU-M03-09 治疗执行归属**：门诊护士站/治疗室执行记录归 M03（条目本身在 M03 清单内），M05 不承担门诊治疗执行，见第 8 节边界结论。
3. **处方计费事件来源**：M13 Spec 订阅"M03 开单/处方事件"细化为——非药品计费行经 `outpatient.order.created`（M03）、药品计费行经 M06 处方开立事件携带，避免处方明细双头维护（统一审查按此主方案落定，备选表述已删除）。
4. **门（急）诊诊疗信息页**：总 Spec 未显式列出信息页条目，本模块按国卫办医政发〔2024〕16 号将就诊过程信息（挂号/报到/接诊时间、就诊类型、急诊分级、去向代码）作为 visit 权威采集项，并提供信息页数据集视图 API 供 M09/M19 取数，属合规性细化、不新增 FU 条目。

**v1.1 统一审查修订记录（依据 `docs/specs/modules/90-cross-review.md` 统一裁决执行）**：
- **B-3/R2-01**：退药退费时序统一改写为"先退药后退费"——患者到 M06 药房退药受理（追溯码核验+批次回补，发布 `pharmacy.dispense.returned`）→ M13 解除执行占用并走退费审批 → `billing.refund.approved` → 本模块扇出 `outpatient.order.cancelled` 仅作终态确认（未发药处方作废/已退药单据收敛）；删除原"`billing.refund.approved` → 扇出退药指令 → M06 退药"表述（§3.4/§5/§6 FU-M03-08/§7/§8 同步）。
- **M-5**：绿通挂账放行落为事件契约——本模块订阅 `billing.charge.guaranteed`（M13 挂账放行回执）后置单据 CHARGED 并扇出含绿通标记的 `outpatient.order.charged`（§3.4/§5/§7/§8 同步，替代原"M13 挂账放行不等待结算"的模糊表述）。
- **M-6**：§7 订阅清单补 `pharmacy.prescription.rejected`（医生站驳回提醒+改方入口，`pharmacy.prescription.approved` 声明为提醒类按需）与 `pharmacy.dispense.returned`（visit 状态聚合）；修正 pharmacy 事件组中文注记错位。
- **R2-08**：§4 visit_type 枚举补"互联网诊疗"（信息页代码 4）。
- **R2-10**：§5/§7 明确处方引用行作废必须经 M06 作废 API（`POST /prescriptions/{no}/cancel`），由 `pharmacy.prescription.cancelled` 回流驱动引用行 CANCELLED 与 M13 费用作废；`outpatient.order.cancelled` 对处方行仅作终态确认。
- **R2-11/R4-12**：诊毕前置校验纳入调 M09 待写文书清单校验（`GET /api/v1/emr/documents/pending-list`，可参数化为提醒不拦截）（§3.3/§5/§6/§8）。
- **R4-10**：§7 补订阅 `lab.specimen.collected`、`imaging.exam.registered`（驱动 clinic_order 执行中迁移）。
- **R4-11**：§3.4/§7 `outpatient.order.cancelled` 消费方说明补 M07/M08（退费逆向作废联动）。
- **M-25**：§7 订阅 `patient.frozen` 处成对补订 `patient.unfrozen`。
- **澄清 3**：处方计费事件归属按 M-4 主方案落定，备选表述删除。
- **M-25（收尾补订，Round 2）**：§7 订阅 `patient.merged` 处成对补订 `patient.split`（合并读侧归一、拆分逆映射同步刷新；成对语义权威口径在 M02 §7）。
