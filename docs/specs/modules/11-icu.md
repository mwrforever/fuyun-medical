# M11 重症监护（ICU）· 功能实现 Spec

| 属性 | 内容 |
| --- | --- |
| 模块编号 | M11 |
| Maven 模块 | `fuyun-icu`（schema：`icu`） |
| 版本 / 状态 | v1.1 / 统一审查修订（修订记录见 §12） |
| 上游依赖 | M01（认证/RBAC+数据范围/字典/参数/审计/通知中心/CA 电子签名）、M02（patient_id/EMPI 归一）、M04（visit_id、转科编排事件——ICU 为护理单元，转入转出经转科）、M05（点测体征仲裁权威、出入量明细账与体温单小结落点、ICU 病区通用通道关闭联动）、M07（检验结果供评分取数、危急值事件，P1）、M10（PACU→ICU 转入衔接事件）、M14（遥测查询/设备绑定/告警引擎复用）、M20（事件总线治理、幂等构件、延迟队列） |
| 下游被依赖 | M05（出入量班次/24h 小结回写来源、ICU 病区通道开关的对端）、M09（重症记录单与评分随病历归档引用、患者全景取数）、M10（ICU 转入确认回执，其 Spec 已声明预留订阅）、M19（ICU 质量指标取数）、web-workstation（ICU 医护工作站/床旁端）、web-bigscreen（ICU 中央监控屏） |
| 对应总 Spec | FU-M11-01 ~ FU-M11-04（均 P2） |

---

## 1. 模块定位与边界

**职责**：本模块是重症医学科（ICU）的专科临床信息系统，ICU 在 M01 组织树中是病区（护理单元），患者转入转出经 M04 转科编排，本模块不介入转科事务、只消费其结果事件。四项职责：① 重症护理记录自动采集——ICU 病区内按分钟级栅格自动汇聚监护仪/呼吸机/输液泵/CRRT 遥测与护理事件，生成电子重症护理记录单（特护单），对应卫办医政发〔2010〕125 号"病危（病重）患者护理记录"的 ICU 专科形态（调研依据 1、4）；② 重症评分——APACHE II/SOFA/GCS 量表配置化引擎，按评分计划自动取数（遥测/检验/出入量/执行记录）+ 人工确认门控，异常评分升级提醒；③ 出入量管理——自动入量（输液/血制品/CRRT 置换液）与出量（尿量/引流等）的明细归集、小时级/班次/24h 汇总平衡计算、小结回写体温单；④ 重症统一报警——告警引擎（规则评估/闭环/升级/风暴抑制）全量复用 M14，本模块提供 ICU 专科报警规则模板、中央监控看板（床位×设备×报警分级矩阵）与报警响应工作台。核心资产：重症患者监护配置与评分计划、随病历长期保存的重症护理记录单、评分结果、出入量汇总。

**非职责**：转科/床位/护理单元主数据与编排（归 M04，本模块只按事件识别转入转出）；医嘱开立与状态权威（归 M04；CRRT/呼吸机治疗以医嘱为前提，本模块只做治疗登记与数据采集）；通用护理文书（体温单/交接班/护理不良事件/常规护理任务）与体征点测仲裁（归 M05，ICU 病区同样适用）；设备接入、遥测落库、告警规则评估与闭环（归 M14，本模块只查询遥测、订阅告警事件、经其接口操作闭环）；检验全流程与危急值闭环（归 M07，本模块只取结果与订阅事件）；病历文书编辑器/质控/病案首页（归 M09，本模块提供文书与快照引用）；计价收费（归 M13，监护费/呼吸机使用费随 M04 医嘱链条，本模块不涉资金）；输液泵/注射泵等设备的绑定与传感（归 M14/M15）。

**模块红线**：
1. 数据归属三权威不越界：遥测原始数据唯一权威在 M14（本模块只引用与快照固化，不落遥测明细）；点测体征唯一权威在 M05 vital_sign_record（本模块引用其仲裁结果，不自建仲裁）；重症专科文书唯一权威在本模块。任何一处重复落账即为双写违规。
2. 重症护理记录单与评分结果是病历资料：随病历长期保存（≥30 年口径）；提交/确认后锁定，修订走留痕版本链且原值可见；签名经 M01 CA；业务时间一律服务器时间；快照固化后不随 M14 遥测保留策略（明细 90 天）失真。
3. 评分自动计算结果未经医护确认不得入病历（CALCULATED 为中间态）；必填参数缺失时不得以残缺参数出具评分；参数取数全部带来源与取数时间留痕，可逐项追溯。
4. 本模块不建告警引擎、不评估告警规则、不承接告警闭环状态机——一律复用 M14（FU-M14-08）；ICU 侧仅维护专科规则模板数据（经 M14 规则接口下发）与重症视角展示/响应工作台（调 M14 闭环接口）。
5. 禁止跨模块读表：遥测与绑定调 M14、点测体征与出入量明细调 M05、检验结果调 M07、转科与就诊调 M04、执业授权与签名调 M01；对外仅暴露 API 与事件。

## 2. 调研依据

1. **重症临床信息管理系统建设实践（中国医疗器械杂志）**：电子化 ICU 护理记录单（ICU 特护单、ICU 护理计划单等）、自动采集监护仪/呼吸机等设备数据以提高效率并减少差错、检验结果集成是 ICU 临床信息系统的标配能力。（来源：https://zgylqxzz.xml-journal.net/cn/article/pdf/preview/10.3969/j.issn.1671-7104.2020.03.007.pdf ）
2. **解放军总医院 ICU 医疗设备物联网（25 个月生产实证，总 Spec 2.3 已引）**：护理集成系统自动将生理数据导入护理记录，每天减少护士 0.8 小时/床工作量；4 病区日均 15,531 MB、波形占 86.64%、体征 7.92%、报警 5.44%；报警消息走 ORU^R40（PCD-04）经 AMQP 上传。（来源：https://pmc.ncbi.nlm.nih.gov/articles/PMC11955343/ ）
3. **厂商重症监护信息系统生产形态**：普博 ICIS 以患者为中心自动采集存储床旁设备数据、实时监测智能报警、自动生成记录单（https://www.prunusmedical.com/index.php?m=content&c=index&a=show&catid=78&id=34 ）；迈瑞瑞智危重症决策支持系统宣称设备数据自动采集使护理文书效率提升 70%（https://www.mindray.com/cn/innovation/ruizhi-critical-care-decision-support-system ）；学术综述实证"按监护需要设置采集频率、自动抓取记录至电子护理单、自动生成评估表单"可降低重复登记并减少抄录错误（https://html.rhhz.net/dejydxxb/html/2025/1/20250116.htm ）；真实医院 ICU 系统招标均要求"监护数据自动传输"并满足电子病历分级评价（https://sdfybj.com/upfile/2022/11/20221114102248_270.pdf ）。
4. **重症护理记录单规范**：卫办医政发〔2010〕125 号将病危（病重）患者护理记录列为表格式护理文书、随病历保存（https://yygl.bjmu.edu.cn/yygl/hlgl/7eac5dd4c8874090a76e1339d87fcb21.htm ）；重症护理记录单适用于病情危重/大手术后/需严密观察患者，记录项目含意识、体温、心率、呼吸、血压、血氧、吸氧、出入量等（https://www.cn-healthcare.com/articlewm/20220411/content-1336887.html 、http://nurs.dxy.cn/article/558599 ）；《病历书写基本规范》要求病危患者随时记录、每天至少 1 次、时间具体到分钟（https://www.nhc.gov.cn/wjw/gfxwj/200205/8348500efb5b490c8db6519e818e96e3.shtml ）。
5. **APACHE II 评分构成（权威口径）**：总分 0~71 = 急性生理评分（APS，12 项之和）+ 年龄分值 + 慢性健康分值；12 项为核心温度、平均动脉压、心率、呼吸频率、氧合（FiO₂≥0.5 用 A-aDO₂、<0.5 用 PaO₂）、动脉血 pH、血清钠、血清钾、血清肌酐（急性肾衰分值加倍）、血细胞比容、白细胞、GCS（记 15−实际值）；无动脉血气时第 13 项静脉碳酸氢盐可替代 pH；取值规则为**最近 24 小时内最差值**；年龄 <44=0、45~54=2、55~64=3、65~74=5、≥75=6；慢性健康按"免疫低下或严重器官功能不全史（肝/心血管/肾/肺）+手术状态"加分（择期术后 +2、非手术或急诊术后 +5）。（来源：https://www.msdmanuals.cn/professional/critical-care-medicine/approach-to-the-critically-ill-patient/critical-care-scoring-systems ）
6. **SOFA/GCS 与评分自动化实践**：SOFA 覆盖呼吸（PaO₂/FiO₂）、凝血（血小板）、肝脏（胆红素）、循环（MAP/升压药）、神经（GCS）、肾脏（肌酐/尿量）6 个系统、各 0~4 分，取过去 24 小时内各系统最差值求和；Sepsis-3 以"感染+SOFA 较基线急性升高 ≥2"判定（https://www.medsci.cn/article/show_article.do?id=5c539045e53c 、https://zhuanlan.zhihu.com/p/720087721 ）；四川大学华西医院中心 ICU 已推行 **SOFA 评分实时自动评价**（评分自动化的三甲实证）（https://www.wchscu.cn/public/department/nephrology/dynamics/56728.html ）；GCS 按睁眼（1~4）/语言（1~5）/运动（1~6）三分量求和、总分 3~15，插管患者语言分量需临床标记（https://www.doctor-network.com/Public/LittleTools/125.html ）；SOFA 存在版本演进（2025 年 SOFA-2 更新阈值与器官支持手段，https://news.sciencenet.cn/htmlpaper/2025/11/2025111184154326141736.shtm ）——构成量表必须配置化、可版本化的直接依据。
7. **ICU 收治与转出标准**：真实医院 ICU 收治标准（试行）以 **APACHE II ≥12 分或早期预警评分 EWS ≥4 分**作为高危住院患者应接受 ICU 监护治疗的量化收治线，并按病情分类明确转出指征（病情稳定 48h 内转出等）（http://www.tjmuch.com/system/2010/11/13/010062561.shtml ）；《中国重症医学科建设和发展指南（2025 版）》要求 ICU 实施重症患者全周期救治管理（https://cmtopdr.com/post/detail/30997502-8e26-430d-9ffc-03463faadf7f ）。
8. **ICU 出入量与 CRRT 容量管理**：出入量平衡 = 24 小时入量 − 出量（正/负平衡），出量含尿/便/呕吐/引流等（https://zhuanlan.zhihu.com/p/1933763398761423089 ）；《连续性肾替代治疗容量评估与管理专家共识》规定 CRRT 处方含容量平衡目标、24h 出入量、净超滤量等要素，容量管理分级策略（一级即计算监测 8~24h 液体平衡）（http://www.cjn.org.cn/CN/10.3760/cma.j.cn441217-20230911-00911 ）；北京协和医院 CRRT 实践要求"准确测量出入量、持续记录计算平衡值、提供实时记录及总结"（https://www.pumch.cn/Uploads/UploadFiles/ksyl/2013/8/2013813225427.pdf ）；ECMO/CRRT 期间需精确记录每小时出入量、尿量与超滤量（https://wap.oajrc.org/ArticleDetail.aspx?cid=10781&type=PDF ）；输液泵多数无数据通信接口（论文实证，总 Spec D7）——出量以手工计量为主、CRRT/输液泵在网设备可遥测的混合形态。
9. **报警统一管理与报警疲劳**：卫健委《呼吸机安全管理》规定呼吸机须具备分钟通气量/气道压/氧浓度/通气频率/PEEP 等报警功能检查项（https://www.nhc.gov.cn/fzs/c100048/201911/9f1d3cd8faf5462e8074915ecb63b1dc/files/1734000625640_54382.pdf ）；ICU 单患者每天报警可达数百至上千次、其中 85%~99% 为无需临床干预的误报（https://chinamedglobal.com/blog/icu-patient-monitor-multi-parameter-global-registration-guide 、https://www.hamilton-medical.com/zh/Products/Digital-Solutions/Distributed-Alarm-System.html ）；报警疲劳优化方向为多参数融合、智能阈值提醒、不应期延迟等集中治理（https://zgylqxzz.xml-journal.net/article/doi/10.3969/j.issn.1671-7104.2020.06.003 ）——与 M14 告警引擎五项风暴抑制设计一致，构成"复用而非重建"的依据。
10. **行业标准**：T/CHIA 47-2024《智慧化重症监护病房建设规范》规定重症监护数据采集与共享、重症监护临床信息系统业务功能设计要求（https://www.chim.org.cn/zzs/zzdt/202404/d28a113b30814ac190dd42c527f626bb/files/6be9436a95fc4357bc9e05ad5d45ea6b.pdf ）。

## 3. 方案推导（关键设计点选型）

### 3.1 重症护理记录模型与 M05 落点边界：全走 M05 通用通道 vs M11 全自建体征域 vs 三权威分离（选定）

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| ICU 病区全走 M05 通用通道 | ICU 患者体征/记录单全部按普通病区模式处理 | M05 通用通道以"点测+参数化频次"为口径（特级 q1h 起），无法承载 ICU 分钟级连续采集与呼吸机/CRRT 专科参数；0.8h/床/天的自动化收益（调研依据 2）落空；M05 Spec 已显式声明"ICU 重症专科护理记录归 M11"，通用通道越界承接与其边界冲突 |
| M11 全自建体征域（自建体征表+自建仲裁） | ICU 体征在 M11 独立建账 | 与 M05 vital_sign_record 形成两本体征账与两套仲裁规则，病历权威栏双源；点测体温单（M05 权威）与重症记录单（M11）数据不一致风险；违背 M05 红线"体温单/护理记录落点唯一"精神 |
| **三权威分离（选定）** | 遥测原始数据唯一权威在 M14（本模块经 `GET /telemetry/query|latest` 引用，不落明细）；点测体征唯一权威在 M05（ICU 病区护士手工/PDA 点测仍走 M05 录入通道，本模块订阅 `nursing.vital-sign.recorded` 引用其仲裁结果）；重症专科文书唯一权威在本模块（icu_nursing_record = 监测点流 + 护理事件流双流合一，与 M10 麻醉记录单同构） | 一数一源、引用不复制；与 M05 Spec"ICU 病区经 nursing_ward_config 关闭 IoT 自动落卡与手工出入量，防双写"的声明严丝合缝——关闭的是 M05 的 IoT 自动转正与出入量录入，**保留**手工点测通道（点测权威不可转移）；遥测明细 90 天 vs 病历 ≥30 年的矛盾按 M10 既定策略以快照固化解决（日结/班结/转出时点批量转 SNAPSHOT） |

**双流模型落点**：icu_nursing_record（记录单主档，按日分页）+ icu_monitor_point（监测点流：指标编码引用 M14 MDC 术语、测点时间、值、来源[IOT 遥测引用/点测引用 M05/MANUAL 手工/LAB 血气引用/SNAPSHOT 快照固化]）+ icu_care_event（护理事件流：病情观察/护理措施/给药执行引用/吸痰/翻身/管路/约束/镇静评估等）。**渲染**：按病区配置栅格（默认 1h，特级可 30min，参数化）实时拉取 M14 遥测渲染；**固化**：每日 24 时、班次交接、转出 ICU 三个时点将窗口内遥测引用点批量转 SNAPSHOT（此后只读）；**合并规则**同 M10——同栅格内点测值优先入权威位、遥测值留参考互链（对齐 M05"点测优先"既定精神）；遥测断流（消费 `iot.telemetry.anomaly`）产生缺测标注与补录提醒。

**结论**：三权威分离 + 双流合一 + 三时点快照固化 + 点测优先合并规则。ICU 病区配置开关（icu_ward_config）与 M05 nursing_ward_config 的 ICU 关闭项一一对应，统一审查对照。

### 3.2 评分引擎：全硬编码 vs 全自动直出 vs 量表配置化+自动预填+确认门控（选定）

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 全硬编码 | APACHE II/SOFA/GCS 各写死一套计算逻辑 | 三套量表参数口径差异大（APACHE II 12 项生理+年龄+慢性健康，调研依据 5；SOFA 6 系统 24h 最差值，调研依据 6），硬编码即三份独立实现；SOFA 已出现版本演进（SOFA-2，调研依据 6）、各院阈值存在本地化调整，每次调整须发版；无法支持专科自定义量表扩展 |
| 全自动直出（算出即入病历） | 参数齐备自动计算并直接落病历权威 | ICU 数据质量参差（遥测伪差、检验漏项），自动取数错误直接固化为病历错误；慢性健康/GCS 分量等临床判断项无法可靠自动获取；违背"病历资料医护确认"底线 |
| **量表配置化+自动预填+确认门控（选定）** | score_definition（量表定义：版本/参数项/来源通道绑定/分段计分规则/总分构成/判级阈值/计划频次）+ score_param_source（参数↔取数通道映射：M14 遥测指标 code、M07 检验项目 code、出入量项目、执行记录、手工）+ score_result（结果：参数快照+各维度分+总分+判级+计算方式+确认人）；**自动取数边界**见下；按评分计划（转入 24h 内首评 APACHE II、每日 SOFA、GCS 按护理级别频次）生成待评任务，参数自动预填→服务端计算→医护确认入病历 | 配置化支持量表版本与专科扩展（调研依据 6 的 SOFA-2 演进）；自动预填兑现华西"SOFA 实时自动评价"生产实证的效率（调研依据 6）；确认门控保证病历正确性；参数快照带来源与取数时间，逐项可追溯可审计 |

**自动取数边界（硬约定）**：
- **可自动取**：核心温度/MAP/心率/呼吸频率/SpO₂/FiO₂（M14 遥测，评分窗口最差值且仅取 quality=GOOD，SUSPECT/BAD 进待补录清单）；动脉血气 pH/PaO₂ 与 A-aDO₂（M07 检验结果，A-aDO₂ 与 FiO₂ 同窗匹配）；血清钠/钾/肌酐/血细胞比容/白细胞/血小板/胆红素（M07）；年龄（M02 出生日期推算）；尿量（出入量明细账）；升压药剂量（M05 执行单自动预填，无法获取转手工）。
- **必须手工**：GCS 三分量（睁眼/语言/运动为床旁神经学评估，任何设备不可测；插管患者语言分量由临床标记）；慢性健康评分（免疫低下与器官功能不全史为临床判断）；手术状态默认按 M10 术后转入标记自动预填、允许修改。
- **计算方式枚举**：AUTO_AUTO（全参数自动）/ AUTO_MANUAL（部分手工补录后计算）/ MANUAL（整体手工）；缺必填参数不得出具 CALCULATED。

**异常评分升级**：判级阈值（如 APACHE II ≥20 危重、SOFA 较基线升高 ≥2 提示脓毒症风险，阈值在量表定义中配置化）命中时发布 `icu.score.completed`（携判级），经 M01 通知中心向主治医生/护士长升级提醒（动作式，不改状态）；评分与转入收治核对（APACHE II ≥12 收治线，调研依据 7）仅做提示参考，不构成系统拦截（收治决策权在医生）。

**结论**：量表配置化 + 分通道自动取数（GOOD 质量闸门）+ 三种计算方式 + 确认门控 + 判级升级动作。

### 3.3 出入量模型：全复用 M05 vs M11 全自建双账本 vs 明细一本账+汇总专科归 M11（选定）

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 全复用 M05（明细+小结都在 M05） | ICU 出入量照普通病区模式在 M05 录入与小结 | M05 io_summary 仅班次/24h 两级，无 ICU 必需的小时级平衡与 CRRT 净超滤等专科维度（调研依据 8）；M05 Spec 已声明 ICU 病区关闭手工出入量、自动汇总归 M11，此方案与其边界冲突 |
| M11 全自建明细账+汇总 | ICU 出入量明细在 M11 独立建账 | 与 M05 io_record 形成两本明细账：同一患者转科后普通病区与 ICU 两段出入量分散两处，体温单/病历渲染需跨模块拼接；输液执行自动带入量行（M05 既有 INFUSION_AUTO 设计）与 M11 重复生成即双写 |
| **明细一本账在 M05 + 汇总计算在 M11（选定）** | 出入量**明细账唯一权威在 M05 io_record**（本模块自动行/代录行经 M05 API 写入，source 标 ICU_AUTO/ICU_MANUAL，与普通病区行同构不双账）；本模块建 icu_fluid_balance 承载 **ICU 专科汇总**：小时级平衡（ICU 常规）、班次小结、24h 总结，含 CRRT 净超滤、目标容量平衡差等专科列；**班次小结与 24h 总结的病历落点唯一在 M05 io_summary**（本模块计算后调 M05 API 回写，保证体温单红双线栏单一渲染源），icu_fluid_balance 仅承载小时级与专科维度、不做病历小结的第二落点 | 明细一本账杜绝双写与转科断账；CRRT 处方要素（容量平衡目标/净超滤/24h 出入量，调研依据 8）有专科载体；体温单与病历小结不受 ICU/普通病区分段影响；自动通道：输液执行完成（M05 INFUSION_AUTO 已生成入量行）、血制品执行、CRRT 置换液/超滤（CRRT 在网设备经 M14 遥测取流量，无接口设备按治疗登记+手工计量，对齐总 Spec D7 论文实证）；出量以手工为主（尿量/引流，M11 工作台代录→M05 明细） |

**CRRT 治疗登记**：icu_crrt_session（治疗起止、设备引用[经 M14 绑定查询]、置换液/透析液速率、净超滤目标与实际、抗凝方式）作为专科明细来源，治疗期间的置换液入量行与净超滤出量行自动/半自动写入 M05 明细账；治疗未闭合（未登记结束）时出入量周期不得闭合（门控）。

**结论**：明细账唯一在 M05、ICU 汇总（小时/专科维度）在本模块、病历小结（班次/24h）回写 M05 io_summary 单一落点。

### 3.4 重症统一报警形态：ICU 自建告警引擎 vs 全托管 M14 无专科形态 vs 复用引擎+重症专科层（选定）

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| ICU 自建告警引擎 | 本模块自建阈值评估/闭环/升级 | 与 M14 FU-M14-08 直接重复建设；两套告警实例与闭环状态必然漂移；报警风暴抑制（同源聚合/抖动防护/抑制/风暴预警，M14 方案 3.4）无必要重造 |
| 全托管 M14、无 ICU 专科形态 | 报警完全由 M14 通用界面承载 | FU-M11-04 无落点；ICU 特有需求（床位×设备×报警矩阵中央监控、按重症分级聚合、报警响应工作台与响应时长统计）在通用界面无载体；ICU 专科规则模板（呼吸机报警参数对齐卫健委《呼吸机安全管理》，调研依据 9）无处沉淀 |
| **复用引擎+重症专科层（选定）** | 告警评估/分级（INFO/WARNING/CRITICAL）/闭环状态机/超时升级/风暴抑制全量在 M14；本模块三件事：① icu_alarm_template——ICU 专科报警规则模板（按设备类型预置阈值/持续时间/分级/静默窗，经 M14 规则接口下发为 iot_alarm_rule，ICU 病区批量套用）；② 中央监控看板 `/topic/icu/board/{wardId}`——床位×设备×报警分级矩阵（数据调 M14 告警查询/快照 API 与订阅 `iot.alarm.*`，刷新 ≤2s）；③ 报警响应工作台——未确认报警按床位认领/确认/处理/关闭（直接调 M14 闭环接口 `POST /alarms/{id}/ack|processing|close`，本模块只记录重症侧响应视角统计，不建第二套闭环） | 零重复建设（红线 4）；专科规则模板以数据形态沉淀（调研依据 9 的呼吸机报警规范落点）；看板与工作台复用 M14 权威数据，统计口径单一；与 M14 Spec"M11 复用本模块告警引擎与查询接口"的被依赖声明互相对齐 |

**结论**：复用 M14 引擎 + ICU 规则模板（数据）+ 中央监控看板 + 响应工作台（调其接口）。

## 4. 领域模型

表设计统一遵循 README 第 3 节约定：雪花 BIGINT 主键、统一审计字段（created_by/created_at/updated_by/updated_at/deleted）、TIMESTAMPTZ 服务器时间、逻辑删、状态字段 VARCHAR 常量+迁移日志、跨模块引用只存号/code 引用。量表判级用语、出入量项目等为模块专业字典（挂接 M01 字典 code，对齐 M14/M04/M10 先例）。

| 实体 | 关键字段 | 说明 |
| --- | --- | --- |
| icu_ward_config 病区 ICU 配置 | ward_id（唯一）、is_icu_ward（是否 ICU 病区）、record_grid_minutes（记录单渲染栅格，默认 60）、monitor_metrics（默认监测指标集，M14 MDC code 引用）、score_plan_template（默认评分计划模板：量表×频次）、fluid_hourly_enabled（小时级平衡开关）、alarm_template_ref（报警模板引用）、snapshot_policy（快照策略：日结/班结/转出） | 病区级 ICU 专科策略唯一配置点；转入识别依据；与 M05 nursing_ward_config 的 ICU 关闭项成对配置 |
| icu_patient_config 重症患者监护配置 | config_no、patient_id、visit_id（M04 签发）、ward_id、bed_id、transferred_in_at（转入 ICU 时间）、transferred_out_at、postop_ref（M10 术后转入标记引用）、score_plan 引用集、current_record_ref、admit_apache_hint（收治评分提示值）、status（见状态机） | 重症监护期唯一载体；同一在院 visit 至多一条"监护中"记录（部分唯一约束） |
| score_plan 评分计划 | plan_no、config 引用、scale_code（APACHE_II/SOFA/GCS/CUSTOM）、计划频次（首评时点/周期时点序列）、enable_flag | 转入确认时按模板实例化；可启停 |
| score_definition 量表定义 | scale_code、version、status（草稿/发布/废止）、param_items（参数项清单：参数编码/名称/单位/必填性/生理极限）、scoring_rules（分段计分规则：区间→分值，含肌酐急性肾衰加倍等特殊规则标记）、total_compose（总分构成：维度分求和/加权）、grade_rules（判级阈值）、calc_window（取数窗口，默认 24h 最差值）、发布审批留痕 | 量表配置化引擎的定义侧；发布版本化，历史评分绑定其时点版本（对齐 M01 字典版本化精神） |
| score_param_source 参数取数映射 | scale_code+version、param_code、source_type（IOT 遥测/LAB 检验/IO 出入量/EXEC 执行记录/PATIENT 患者主数据/MANUAL 手工）、source_ref（M14 metric_code/M07 检验项目 code/出入量项目 code 等）、worst_direction（最差值方向：最高/最低/最偏离） | 参数↔通道绑定；自动取数的执行依据 |
| score_result 评分结果 | result_no、config/plan 引用、scale_code+version（时点版本锁定）、scored_at（评分时点）、param_snapshot（参数快照：各参数值+来源+取数时间+质量标记）、dimension_scores（各维度分）、total_score、grade（判级）、calc_mode（AUTO_AUTO/AUTO_MANUAL/MANUAL）、calc_by、confirm_by/confirm_at、recalc_version（重算版本，留痕）、status（见状态机） | 评分结果权威；确认后随病历归档 |
| icu_nursing_record 重症护理记录单 | record_no、config 引用、patient_id/visit_id、page_date（按日分页）、shift（班次）、监测段/事件段/自由段引用、snapshot_at/snapshot_scope（最近快照时点与范围）、签名引用（M01 CA）、status（见状态机）、修订链引用 | 对应 125 号文病危（病重）护理记录的 ICU 形态；提交锁定+修订留痕原值可见；随病历 ≥30 年 |
| icu_monitor_point 监测数据点 | record_id、metric_code（M14 MDC 引用）、measured_at、value、unit、source（IOT_REF/M05_VITAL_REF/MANUAL/LAB_REF/SNAPSHOT）、iot_ref（设备+原始时间窗引用，IOT/SNAPSHOT 填写）、vital_ref（M05 体征记录引用，点测源填写）、quality 标记、录入人 | 监测点流；栅格渲染与快照固化对象；不落遥测明细（仅引用与固化值） |
| icu_care_event 护理事件 | record_id、event_time、event_type（病情观察/护理措施/给药执行/吸痰/翻身/管路护理/约束/镇静评估/特殊事件）、execution_ref（M05 执行单引用，给药类）、assessment_ref（M05 评估单引用，镇静/疼痛类）、内容摘要、操作人、补记标记 | 护理事件流；与监测点流按时间轴双流合一渲染 |
| icu_fluid_balance 出入量汇总 | balance_id、visit_id、cycle_type（HOURLY/SHIFT/24H）、cycle_from/cycle_to、total_intake/total_output/balance_value、crrt_net_uf（CRRT 净超滤）、target_balance（目标容量平衡差）、missing_items（缺项清单：未闭合手工项）、status（OPEN/CLOSED）、close_by/close_at | ICU 专科汇总；小时级与专科维度权威；班次/24h 数值计算后回写 M05 io_summary（病历落点唯一在其） |
| icu_crrt_session CRRT 治疗登记 | session_no、visit_id、config 引用、started_at/ended_at、device_ref（M14 设备标识，经绑定查询）、replacement_rate/dialysate_rate（置换液/透析液速率）、net_uf_target/net_uf_actual、anticoag（抗凝方式）、status（RUNNING/CLOSED） | 出入量专科明细来源；未闭合时出入量周期闭合被门控 |
| icu_alarm_template ICU 报警规则模板 | template_id、template_name、device_type（监护仪/呼吸机/输液泵/CRRT）、规则行集（指标/阈值/持续时间/分级/静默窗，语义对齐 M14 iot_alarm_rule 字段）、basis_ref（依据：卫健委呼吸机安全管理等）、status（启用/停用） | 经 M14 规则接口下发为 ICU 病区 iot_alarm_rule；本模块不评估规则（红线 4） |
| icu_status_log 状态迁移日志 | owner_type/owner_no、from_status/to_status、reason、operator、occurred_at | 只增；README 状态迁移留痕要求 |

关系要点：icu_ward_config 1:1 病区（M01）；inpatient_visit（M04）1:0..1 icu_patient_config（监护中唯一）1:N score_plan/score_result/icu_nursing_record/icu_fluid_balance/icu_crrt_session；icu_nursing_record 1:N icu_monitor_point/icu_care_event；score_definition 1:N score_result（按版本）；score_definition 1:N score_param_source；icu_alarm_template → M14 iot_alarm_rule（经接口，非外键）。

## 5. 状态机与业务流程

- **icu_patient_config**：`PENDING_CONFIRM（转入识别：消费 transferred 事件且目标病区为 ICU 病区，自动建档待确认）→ MONITORING（重症监护中：护士确认评分计划/记录单开页后生效）→ CLOSED（转出 ICU 归档，终态）`；侧支：`PENDING_CONFIRM → CANCELLED（误识别/取消转入，终态）`。消费 `inpatient.visit.discharged` 兜底强制 CLOSED（未正常转出场景，附原因）。
- **score_result**：`PLANNED（按计划生成待取数）→ CALCULATED（必填参数齐备、自动/手工算出待确认）→ CONFIRMED（医护确认入病历，终态）`；侧支：`PLANNED/CALCULATED → VOIDED（作废：计划停用/重复/患者转出，附原因）`；CALCULATED 阶段新参数到达可重算（recalc_version 递增、留痕，不产生新单号）；确认后不可改，更正=重开新结果单留痕。
- **icu_nursing_record**：`IN_PROGRESS（书写中：监测点/事件实时写入）→ SNAPSHOT（阶段快照固化：日结/班结/转出，固化窗口点转 SNAPSHOT）→ ARCHIVED（随病历归档，终态）`；快照后窗口内新数据写入下一页/下一周期；SNAPSHOT/ARCHIVED 后确需更正走留痕版本链且原值可见（对齐 M10 麻醉记录单同款治理）。
- **icu_fluid_balance**：`OPEN（统计周期进行中）→ CLOSED（周期闭合：班结/24h 结，缺项进 missing_items 清单并提示补录，闭合后数值只读）`；周期闭合门控：当期 CRRT 治疗必须已登记结束（未闭合阻断并提示）。
- **icu_crrt_session**：`RUNNING（治疗中）→ CLOSED（治疗结束登记，终态）`。

主流程时序：
1. **转入 ICU**：M04 转科编排完成发布 `inpatient.visit.transferred`（目标病区=ICU）→ 本模块建 icu_patient_config（PENDING_CONFIRM）→ 护士确认（按 icu_ward_config 模板实例化评分计划、重症记录单开页、CRRT/呼吸机经 M14 绑定确认）→ MONITORING，发布 `icu.patient.admitted`（M10 订阅做 PACU→ICU 闭环回签；M05 大屏重症标识）。术后直入：M10 `surgery.pacu.discharged`（去向=ICU）先于转科事件到达时暂存为衔接提示，转科事件到达后合并落 postop_ref；来源=手术间的转入同步回执 M10。
2. **重症护理记录连续采集**：按栅格周期拉取 M14 遥测（监护仪/呼吸机/CRRT，质量过滤）渲染监测点流；M05 点测体征事件（`nursing.vital-sign.recorded`）落点测行（同栅格点测优先）；护理事件床旁录入；`iot.telemetry.anomaly` 触发缺测标注与补录提醒；每日 24 时/班结/转出三时点快照固化；发布 `icu.nursing-record.finalized`（M09 归档引用）。
3. **评分周期**：评分计划时点生成 score_result（PLANNED）→ 分通道自动取数（遥测 GOOD/检验/出入量/执行记录，取窗口最差值）+ 手工项补录（GCS 分量/慢性健康）→ 服务端计算 CALCULATED → 医护确认 CONFIRMED → 发布 `icu.score.completed`（携判级；判级命中升级阈值经 M01 通知升级提醒）。
4. **出入量周期**：自动入量行（输液执行/血制品/CRRT 遥测流量）与手工出量行（尿量/引流代录）经 M05 API 落明细账 → 每小时 HOURLY 平衡滚动计算 → 班结/24h 结时周期闭合（CRRT 未闭合阻断）→ 计算值回写 M05 io_summary（体温单红双线栏）→ 发布 `icu.fluid-balance.closed`。
5. **报警协同**：`iot.alarm.triggered/escalated/closed` 到达 → 中央监控看板矩阵刷新（本模块不处理闭环）→ 责任护士在工作台调 M14 接口确认/处理/关闭 → 响应视角统计供 M19。
6. **转出 ICU**：消费 `inpatient.visit.transferred`（原病区=ICU）→ 转出编排：未完成评分 PLANNED 置 VOIDED → 当期出入量周期强制闭合（缺项清单留痕）→ 当期记录单快照固化 → config 置 CLOSED → 发布 `icu.patient.transferred-out`（M05/M19）。

## 6. 功能实现设计（逐 FU）

| FU | 实现设计要点 |
| --- | --- |
| FU-M11-01 重症护理记录自动采集（P2） | 三权威分离+双流合一（方案 3.1 全套）：按 icu_ward_config 栅格周期调 M14 `GET /telemetry/query|latest` 渲染监测点流（监护仪体征/呼吸机参数——潮气量/气道压/PEEP/FiO₂/呼吸频率/ETCO₂/输液泵余量/CRRT 流量，指标编码引用 M14 MDC 术语）；呼吸机"设定类参数"（通气模式/目标潮气量等治疗设定）手工录入核对、监测值自动；点测体征引用 M05 仲裁结果（订阅事件）；护理事件流床旁录入（给药执行引用 M05 执行单号，防二次录入）；同栅格点测优先合并；三时点快照固化（明细 90 天 vs 病历 ≥30 年矛盾的唯一解，同 M10 先例）；缺测补录与"监测无效标注"（设备原因允许标注不记录）；记录单按日分页、打印（M01 模板）随病历归档；发布 `icu.nursing-record.finalized` |
| FU-M11-02 重症评分（P2） | 量表配置化引擎（方案 3.2 全套）：score_definition/score_param_source/score_plan/score_result 四实体；预置 APACHE II（12 项生理[含 FiO₂ 分段氧合与肌酐急性肾衰加倍]/年龄 5 档/慢性健康手术状态分档/24h 最差值/第 13 项碳酸氢盐替代规则）、SOFA（6 系统各 0~4、24h 最差值）、GCS（三分量手工、总分自动）三套量表定义（参数与规则按调研依据 5、6 权威口径）；评分计划自动生成待评；分通道自动取数（GOOD 质量闸门/检验最差方向/升压药执行引用）+ 手工项边界；三种计算方式；确认门控（未确认不入病历）；判级升级动作（M01 通知中心）；量表版本演进管理（发布/废止、历史结果锁版本）；收治评分提示（APACHE II ≥12 参考线，仅提示不拦截） |
| FU-M11-03 出入量管理（P2） | 明细一本账+汇总专科归 M11（方案 3.3 全套）：自动入量（输液执行完成[M05 INFUSION_AUTO 行]/血制品/CRRT 置换液遥测流量）与手工出量（尿量 q1h/引流/呕吐/大便，M11 工作台代录）统一经 M05 API 落 io_record 明细账（source 标 ICU_AUTO/ICU_MANUAL）；icu_fluid_balance 小时级平衡滚动计算+班次/24h 周期闭合（CRRT 未登记结束阻断闭合，缺项清单提示补录）；班次小结与 24h 总结数值计算后调 M05 API 回写 io_summary（体温单红双线栏单一渲染源，调研依据 8）；CRRT 治疗登记（起止/设备经 M14 绑定/速率/净超滤目标与实际/抗凝）；CRRT 无通信接口设备按治疗登记+手工计量（总 Spec D7 论文实证） |
| FU-M11-04 重症统一报警（P2） | 复用 M14 告警引擎（方案 3.4 全套）：icu_alarm_template 专科规则模板（监护仪/呼吸机/输液泵/CRRT 按设备类型预置阈值/持续时间/恢复带/分级/静默窗；呼吸机报警项对齐卫健委《呼吸机安全管理》五类报警功能，调研依据 9），经 M14 规则接口下发为病区规则并批量套用；中央监控看板（床位×设备×报警分级矩阵/未确认报警列表/响应计时，数据调 M14 告警查询与快照 API、订阅 `iot.alarm.*`，刷新 ≤2s）；报警响应工作台（认领/确认/处理/关闭直调 M14 闭环接口 `POST /alarms/{id}/ack|processing|close`，本模块不建第二套闭环状态）；报警响应时长等重症视角统计供 M19（口径引用 M14 告警统计）；报警疲劳治理依赖 M14 风暴抑制既定设计（误报 85%~99% 的行业实证，调研依据 9），ICU 侧不重复建设 |

## 7. 对外接口

**REST（`/api/v1/icu/` 前缀，响应统一 `{code, message, data, traceId}`，节选）**：
- 配置：`GET /patient-configs?wardId=&status=`、`POST /patient-configs`（转入确认）、`GET /patient-configs/current?visitId=`、`POST /patient-configs/{no}/close`（转出归档）、`GET/PUT /ward-configs`（病区 ICU 配置）
- 重症记录单：`GET /records?visitId=&date=`、`GET /records/{no}/render`（双流合一渲染数据）、`POST /records/{no}/events`（护理事件）、`POST /records/{no}/monitor-points`（手工补录/监测无效标注）、`POST /records/{no}/snapshot`（阶段快照）、`GET /records/{no}/print`
- 评分：`GET /score-plans?configNo=`、`GET/POST /score-results`、`GET /score-results?visitId=&scaleCode=`、`POST /score-results/{no}/confirm`（确认入病历）、`POST /score-results/{no}/recalculate`（重算）、`GET/POST/PUT /scale-definitions`（量表定义管理）
- 出入量：`GET /fluid-balances?visitId=&date=`、`POST /fluid-balances/{visitId}/close-shift|close-24h`（周期闭合）、`POST /fluid-output-entries`（出量代录，转调 M05 明细 API）、`POST /crrt-sessions`、`POST /crrt-sessions/{no}/stop`
- 报警：`GET /alarm-board/{wardId}`（中央监控快照，聚合 M14 数据）、`GET/POST/PUT /alarm-templates`、`POST /alarm-templates/{id}/apply`（下发 M14）、`GET /alarm-response-stats?wardId=&date=`（供 M19）
- 取数与统计：`GET /patient-icu-view?patientId=`（供 M09 患者全景，只读）、`GET /stats/icu-quality`（ICU 质量指标，供 M19，只读）

**内部服务接口（进程内调用对方 API）**：M14 `GET /telemetry/query|/telemetry/latest`、`GET /bindings/current`、`GET /alarms`、`POST /alarms/{id}/ack|processing|close`、`GET /dashboard/summary`、告警规则接口（模板下发）；M05 `POST /io-records`（出入量明细代录）、`POST /io-summaries`（小结回写）、体征记录查询（点测引用）；M07 检验结果查询（评分取数，报告号引用）；M02 患者上下文解析（年龄推算/合并归一）；M01 `POST /practice/check`（评分确认资质）、CA 电子签名、通知中心（判级升级/缺项提醒）；M04 转科与就诊查询（转入识别校验）。

**MQ 事件（发布，经 `fy.topic`，信封遵循 M20 治理约定 eventId/occurredAt/producer，先登记 event_registry）**：
- `icu.patient.admitted`（转入 ICU 确认生效，载荷含 config_no/visit_id/病区床位/转入时间/术后转入标记；M10 订阅做 PACU→ICU 闭环回签、M05 大屏重症标识）
- `icu.patient.transferred-out`（转出 ICU 归档完成，载荷含监护时长/末次评分摘要；M05/M19）
- `icu.score.completed`（评分确认完成，载荷含 result_no/量表与版本/总分/判级/升级标记；M09 归档引用、M01 升级通知依据）
- `icu.fluid-balance.closed`（出入量周期闭合：班次/24h，载荷含总入/总出/平衡值/CRRT 净超滤；M09/M19）
- `icu.nursing-record.finalized`（记录单快照固化/归档，载荷含记录号/固化范围；M09 引用归档）

**MQ 事件（订阅，全部经 M20 幂等构件消费，队列命名 `q.icu.<事件名>`）**：
- `inpatient.visit.transferred`（转入/转出 ICU 识别：目标病区∈icu_ward_config 建档、原病区∈ICU 转出编排；M04 发布）
- `inpatient.visit.discharged`（兜底强制关闭监护配置）
- `nursing.vital-sign.recorded`（M05 点测体征→重症记录单点测行）
- `iot.alarm.triggered` / `iot.alarm.escalated` / `iot.alarm.closed`（中央监控看板刷新与响应统计；闭环动作仍在 M14）
- `iot.telemetry.anomaly`（设备断流→记录单缺测标注与补录提醒）
- `iot.binding.changed`（设备绑定缓存刷新：呼吸机/CRRT/输液泵归属）
- `lab.result.audited`（M07 检验结果审核完成→评分参数待补录刷新；M07 §7 已登记该事件，原 `lab.result.reported` 悬空已废弃）
- `lab.critical-value.notified`（M07 危急值通知→重症看板危急值提示）
- `surgery.pacu.discharged`（去向=ICU 的术后衔接提示，M10 发布）
- `surgery.apply.completed`（载荷去向=ICU 的手术间直入 ICU 场景，与 `surgery.pacu.discharged` 合并落 postop_ref，M10 发布）
- `patient.merged`（历史重症记录读侧经 EMPI 归一，M02 发布）/ `patient.split`（成对订阅：拆分逆映射同步刷新）
- `system.dict.published` / `system.org.changed` / `system.user.changed` / `system.param.changed`（M01 主数据广播缓存刷新）

**WebSocket**：自建端点 `/ws/icu`（STOMP，握手鉴权）：`/topic/icu/board/{wardId}`（中央监控：报警矩阵/评分异常提示/出入量周期与缺项提醒）；遥测曲线与设备状态**复用 M14 端点 `/ws/iot` 主题**（不自建重复通道，对齐 M05 方案 3.5 与 M10 既定分工）。

## 8. 集成点

- **M01**：认证与 RBAC（ICU 数据范围 WARD 隔离；评分确认/记录单提交/量表发布/转出归档独立权限点）；字典引用（判级用语/出入量项目等模块专业字典挂接 code，不自建国标副本）；CA 电子签名（记录单快照签名/评分确认）；通知中心（判级升级/出入量缺项/评分超时提醒）；审计切面（全写操作留痕）；主数据广播订阅。
- **M02**：一切重症数据以 `patient_id`+`visit_id` 关联；年龄推算与身份解析；合并后历史记录读侧经 EMPI 归一。
- **M04（转入转出编排，上游）**：ICU 是护理单元，转入转出经其四阶段转科编排（转科医嘱→转入确认→预占床位→转移执行），本模块只消费 `inpatient.visit.transferred/discharged` 事件做重症监护配置生命周期，不介入床位与转科事务；CRRT/呼吸机治疗以 M04 医嘱为业务前提（医嘱语义权威在其，本模块不建医嘱表）。
- **M05（文书边界，双向对齐其 Spec 第 8 节）**：① ICU 病区经其 nursing_ward_config 关闭 IoT 自动落卡与手工出入量（防双写），本模块 icu_ward_config 与之成对配置；② 点测体征权威在其（ICU 病区手工/PDA 点测仍走 M05 通道），本模块引用其仲裁结果；③ 出入量明细账唯一在其 io_record（本模块自动行/代录行经其 API 写入）、班次小结与 24h 总结文书落点唯一在其 io_summary（本模块计算后回写）；④ 通用护理文书（体温单/交接班/不良事件/常规任务）仍在其，ICU 病区照常适用；⑤ 重症护理记录单/评分/出入量专科汇总归本模块（其 Spec 非职责条款一致）。
- **M14（数据与告警复用，被依赖对齐）**：遥测查询（记录单渲染/评分取数/CRRT 流量）、设备绑定查询、告警事件订阅、告警闭环接口调用、WebSocket 遥测主题复用；本模块不落遥测明细、不评估告警规则、不建告警闭环（其 Spec"M11 复用本模块告警引擎与查询接口"被依赖声明的对端落地）。
- **M07（P1，评分取数）**：血气/生化检验结果经其报告查询 API 取值（评分参数 LAB 源）；订阅 `lab.result.audited`（审核完成）驱动评分参数待补录刷新，订阅 `lab.critical-value.notified` 联动重症看板危急值提示；本模块不做检验流程。
- **M10（PACU→ICU 衔接，双向对齐）**：订阅 `surgery.pacu.discharged`（去向=ICU）做术后直入衔接（postop 标记供 APACHE II 手术状态取数与 M09 首页）；`icu.patient.admitted` 供其订阅完成去向闭环回签（其 Spec 已预留"ICU 转入确认回执"订阅声明，事件名以本模块第 7 节登记为准）。
- **M09**：重症护理记录单/评分结果/出入量小结随病历归档（快照引用与归档状态经事件与查询供其装配）；患者全景取数 API（只读）；文书留痕与签名规范对齐其病历规范。
- **M19**：ICU 质量指标取数（收治评分分布/异常评分升级响应时长/报警响应时长/出入量缺项率/记录单按时完成率）API（只读）。
- **M20**：事件信封/交换机/队列治理、幂等构件、延迟队列（`delay.score-confirm-remind` 评分确认超时、`delay.fluid-cycle-close` 出入量周期闭合提醒、`delay.icu-record-snapshot` 日结快照时点）、出站留痕；本模块全部事件先登记 event_registry。

## 9. 非功能与安全

- 性能：记录单渲染（24h×栅格折算数千监测点+事件流）P95 < 1s（同 M10 记录单渲染口径）；中央监控看板刷新 ≤2s（总 Spec 8）；评分自动计算在参数齐备后 1 分钟内完成 CALCULATED；出入量小时平衡滚动计算不阻塞明细写入；转出编排端到端 < 3s。
- 可用性：ICU 记录 7×24（总 Spec 核心可用性）；M14 不可用时记录单降级为手工录入通道（独立可用，恢复后补采，补录标记）；M05 不可用时出入量代录本地暂存补投（outbox 补偿）；M07 不可用时评分检验参数走手工补录（不阻塞确认门控之外的计划生成）。
- 一致性：评分确认与快照固化同事务（固化不完整则确认失败）；出入量周期闭合与 io_summary 回写同事务（回写失败则闭合回滚）；事件发布走 outbox（M20 治理约定），订阅方 eventId 幂等；监护配置"监护中唯一"部分唯一约束防重复建档。
- 审计：评分确认/重算、记录单提交与修订、快照固化、转出归档、量表定义发布、报警模板下发全量审计（M01 切面）；状态迁移日志独立留痕；评分参数快照逐项带来源与取数时间（临床审计可追溯）。
- 权限：病区数据范围隔离（跨病区查询/订阅 403 且留审计）；评分确认与记录单提交分权（护士录入/医师确认评分，按院内制度配置）；量表定义发布限系统管理员角色；报警闭环操作权限沿用 M14 接口鉴权。
- 合规映射：卫办医政发〔2010〕125 号（病危病重护理记录类别与内容要素）；《病历书写基本规范》病危随时记录/每天至少 1 次/时间到分钟（调研依据 4）；T/CHIA 47-2024 智慧化 ICU 数据采集与临床信息系统功能要求（调研依据 10）；《中国重症医学科建设和发展指南（2025 版）》全周期管理（调研依据 7）；卫健委《呼吸机安全管理》报警功能项（调研依据 9）；电子病历分级评价（重症数据自动采集入病历支撑 4~5 级数据整合）；等保三级（审计/TLS/权限最小化/敏感字段脱敏）。
- 数据留存：重症护理记录单/评分结果/出入量小结随病历保存 ≥30 年；快照固化点长期保留（不参与任何降采样清理）；监测点流中的 IOT_REF 引用随 M14 保留策略失效后仅存固化值；小时级汇总在线 ≥3 年后归档；报警响应统计 ≥3 年。

## 10. 测试要点

- 正常：转入全链路（transferred 事件→建档→确认→评分计划实例化→记录单开页→`icu.patient.admitted` 供 M10 回签）；术后直入（pacu.discharged 先到暂存、转科事件后到合并 postop_ref）；记录单双流渲染（遥测自动+点测引用+护理事件按时间轴合一、同栅格点测优先）；APACHE II 首评（12 项自动取数+GCS/慢性健康手工→CALCULATED→CONFIRMED→判级升级提醒）；SOFA 每日评分 24h 最差值口径正确；出入量 24h 周期（自动入量行+手工出量行→平衡值→回写 io_summary→体温单红双线）；报警模板下发 M14 后规则生效、看板矩阵随 `iot.alarm.*` 刷新。
- 边界：同栅格点测与遥测并存（点测入权威位、遥测留参考互链）；遥测 SUSPECT 值不自动取、进待补录清单；缺必填参数（如 GCS 分量未录）时评分停留 PLANNED 且确认接口被拦截提示补录；FiO₂ 恰为 0.5 时氧合指标切换（A-aDO₂/PaO₂）口径；肌酐急性肾衰加倍规则命中；评分确认后新参数到达走重算版本而非改原值；CRRT 未登记结束时出入量周期闭合被阻断；转出瞬间未完成评分置 VOIDED、当期周期强制闭合且缺项清单留痕；同一 visit 重复转入事件仅建一条监护配置（唯一约束兜底）；量表新版本发布后历史结果仍锁定原版本；每日 24 时快照与跨日窗口数据归属正确。
- 异常：M14 断流（`iot.telemetry.anomaly`）产生缺测标注与补录提醒、不阻塞记录单书写；M14 不可用降级手工通道、恢复后补采带补录标记；`inpatient.visit.transferred` 重复投递仅编排一次（幂等）；io_summary 回写失败周期闭合回滚且重试；出量代录 M05 不可用本地暂存补投不重复；`nursing.vital-sign.recorded` 重复投递点测行不重复（体征记录号幂等）；M07 不可用时检验参数手工补录路径可用；患者合并后历史记录 EMPI 归一可查。
- 安全：跨病区查询记录单/订阅看板主题 403 且留审计；无确认权限护士确认评分被拒；无发布权限维护量表定义被拒；报警闭环操作直接调 M14 鉴权（本模块无旁路）；日志与事件载荷患者敏感字段脱敏抽验；快照固化后原值不可改、修订走留痕链。

## 11. 自审记录

- [x] 无 TBD/TODO/占位符，文档头 + 12 节内容完整
- [x] 覆盖 FU-M11-01~04 全部条目，无遗漏、无私增（CRRT 治疗登记为 FU-M11-03 出入量专科明细来源的落地细化；报警模板/看板/工作台为 FU-M11-04"复用 M14 告警引擎"的对端形态，均非新功能点；全部条目沿用总 Spec P2 优先级）
- [x] 内部一致：领域模型 ↔ 状态机 ↔ API ↔ 测试一一对应（icu_patient_config/score_result/icu_nursing_record/icu_fluid_balance/icu_crrt_session 五个状态机均有对应接口、流程与测试项；icu_ward_config/score_plan/score_definition/score_param_source/icu_monitor_point/icu_care_event/icu_alarm_template/icu_status_log 均有操作或查询路径与测试场景）
- [x] 符合跨模块约定：schema=icu；主键 BIGINT 雪花；无资金字段（计价权威在 M13）；事件命名 `<模块>.<实体>.<动作>`、信封 eventId/occurredAt/producer、消费走 integration.received_event 幂等、队列 `q.icu.<事件>`；REST 路径 `/api/v1/icu/`；字典只存 M01 code 引用（量表/判级/出入量项目为模块专业字典，对齐 M14/M04/M10 先例）；状态字段 VARCHAR 常量+迁移日志；patient_id+visit_id 关联落实；无跨模块读表
- [x] 依赖方向正确：依赖 M01/M02/M04/M05/M07/M10/M14/M20 对外接口与事件；M09/M19 取数为事件发布或只读 API 的被动声明；无反向依赖；被依赖清单与 M05（通道开关对端/小结回写来源）、M10（ICU 转入回执订阅，其 Spec 已预留声明）、M14（复用告警引擎，其 Spec 已声明）、M09/M19（取数方）Spec 声明互相对齐
- [x] 方案推导 4 个关键点均有备选对比与依据，含任务要求的三个必选点（3.1 重症护理记录模型与 M05/M14 落点边界与防双写、3.2 评分引擎配置化 vs 硬编码与自动取数边界、3.3 出入量模型与 M05 io_record 关系并给出结论），另含 3.4 重症统一报警形态，每个结论附调研来源
- [x] 无代码级实现（无类名/方法体/SQL DDL 全文；表设计为"表-关键字段-约束"粒度；APACHE II/SOFA/GCS/MDC/CRRT/PACU 为行业标准术语非代码）
- [x] 歧义消除：三权威分离（遥测 M14/点测仲裁 M05/重症文书 M11）、ICU 病区关闭项的精确语义（关 IoT 自动落卡与手工出入量、保留手工点测）、出入量明细与小结落点（明细与班次/24h 小结在 M05、小时级与专科维度在 M11）、评分确认门控与重算语义、快照固化三时点与只读边界、CRRT 未闭合对周期闭合的门控、报警闭环归属（M14）均已显式定义
- [x] 中文注释与术语一致（重症护理记录单/特护单/重症评分/出入量/平衡/转入转出/中央监控/告警分级，与总 Spec 及 M05/M14/M10 用词一致）

## 12. 与总 Spec 的偏差

无结构性偏差。三处细化澄清（请统一审查裁决确认）：
1. **FU-M11-03 出入量"自动汇总"的分工细化**：总 Spec 表述为"输液/引流量自动汇总与平衡计算"，本 Spec 落为"明细账唯一权威在 M05 io_record（ICU 行经其 API 写入）、汇总与平衡计算（小时级/专科维度）归 M11、班次小结与 24h 总结数值由 M11 计算后回写 M05 io_summary"——与 M05 Spec"ICU 自动出入量汇总归 M11"声明互为镜像，属分工细化非范围变化。
2. **FU-M11-04 "重症统一报警"的形态**：告警引擎（规则评估/闭环/升级/风暴抑制）全量复用 M14（总 Spec 条目本身已注明"复用 M14 告警引擎"），本模块落地为 ICU 专科规则模板数据、中央监控看板与响应工作台（调 M14 接口），不新建引擎与闭环状态。
3. **FU-M11-01 自动采集范围**：总 Spec 表述"体征/呼吸机参数自动入护理记录单"，本 Spec 细化为"遥测通道自动（监护仪/呼吸机/输液泵/CRRT，质量过滤）+ 点测值引用 M05 仲裁结果"双通道合一——点测权威不转移（M05 红线），自动通道不双写（M05 的 ICU 病区 IoT 自动落卡关闭项与本模块承接互为对端）。

**v1.1 统一审查修订记录（依据 `docs/specs/modules/90-cross-review.md` 统一裁决执行）**：
- **B-4**：§7 订阅 `lab.result.reported` 悬空（M07 无此事件，评分取数链断裂）改为 `lab.result.audited`（M07 §7 已登记，审核完成触发评分参数待补录刷新），§8 M07 条目同步。
- **R4-08**：§7 补订阅 `lab.critical-value.notified`（M07 危急值通知→重症看板危急值提示），§8 M07 条目声明落位。
- **R3-09**：§7 补订阅 `surgery.apply.completed`（过滤载荷去向=ICU，与 `surgery.pacu.discharged` 合并落 postop_ref，补齐"手术间直入 ICU"场景）。
- **M-25**：§7 订阅 `patient.merged` 处成对补订 `patient.split`（拆分逆映射同步刷新，成对语义权威口径在 M02 §7）。
- **偏差 1~3**：经统一审查裁决全部采纳确认（出入量明细/汇总分工、复用 M14 告警引擎的专科形态、自动采集双通道），详见 90 号文档第 4 节终审 #24。
