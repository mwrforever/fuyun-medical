# M15 设备与资产管理 · 功能实现 Spec

| 属性 | 内容 |
| --- | --- |
| 模块编号 | M15 |
| Maven 模块 | `fuyun-asset`（schema：`asset`） |
| 版本 / 状态 | v1.1 / 统一审查修订 |
| 上游依赖 | M01（认证/RBAC+数据范围/审计/通知中心/打印模板/组织与员工主数据/参数）、M02（patient_id——不良事件涉及患者伤害时引用）、M13（收入统计只读取数 API，日终拉取）、M14（设备利用率日统计 `GET /quality/device-usage`、iot_device 接入档案互引、`iot.device.status-changed` 可用性参考）、M07（检验仪器工作量/使用统计 API）、M08（检查设备工作量/利用率统计 API）、M10（手术设备使用事实输出）、M16（冷链与定位标签的资产号引用校验、`ward.cold-chain.alert-archived` 订阅、RTLS 资产位置查询）、M20（事件总线治理、幂等构件、延迟队列） |
| 下游被依赖 | M14（iot_device 的资产引用维护与资产↔设备对照查询、报废联动停用、强检逾期提示）、M16（cold_chain_archive/rtls_tag 的资产号有效性查询、报废解除引用）、M10（手术室/麻醉设备资产标识引用与档案查询）、M07/M08（仪器/检查设备业务档案的资产引用校验）、M19（装备管理指标取数：强检完成率、PM 完成率、完好率、维修费用、效益分析汇总、闲置清单）、M01（设备科工作台卡片注册） |
| 对应总 Spec | FU-M15-01 ~ FU-M15-07 |

---

## 1. 模块定位与边界

**职责**：本模块是全院医学装备（仪器设备类固定资产口径）全生命周期的唯一权威管理域，七项核心职责：① 资产台账——一物一码（二维码）贯穿"采购申请→论证→采购→验收→入库建档→领用/转移/借用→使用→闲置调剂→报废处置"全生命周期（含扫码盘点）；② 计量检定管理——强检/非强检分类、周期检定计划、到期提醒与逾期升级、检定证书与合格标识管理、逾期未检使用拦截；③ 维修管理——报修工单、派工、维修执行、外修管理、验收关闭、停机时长与维修费用统计；④ 保养管理——预防性维护（PM）计划、巡检模板、执行记录与完成率统计；⑤ 效益分析——IoT 利用率数据（M14）× 收入数据（M13）× 成本数据（折旧/维修/保养）装配为单机/科室效益报表；⑥ 不良事件监测——医疗器械不良事件收集、上报时限控制、群体事件处置流程与上报凭证归档；⑦ 设备档案文档——申购/技术/使用维修资料电子化归档（一物一码扫码调阅）。

**非职责**：设备接入、物模型、遥测、告警判定与设备-患者绑定（全部归 M14，本模块只消费其统计结果与状态事件，不解析任何设备原始数据）；检验仪器通讯档案与检查设备业务档案（归 M07/M08，本模块为其资产权威源，其档案仅存资产引用）；冷链合规台账、RTLS 标签档案与呼叫域（归 M16，本模块为其提供资产号与档案查询）；收费项目物价定义、国家编码对照与金额计算权威（归 M13，本模块只做收入归集引用，不落金额权威字段——效益分析表中的收入列均为 M13 取数快照）；药品/耗材业务字典与出入库（归 M06，本模块台账为仪器设备类固定资产口径，低值易耗品与耗材不入台账）；财务总账凭证与折旧引擎（归外部 HRP/财务系统，本模块仅记录资产原值与折旧参数供效益分析与财务对账）；供应商主数据全院共享域（仅建设备采购维度的轻量登记，供应商证照完整性由采购制度线下把关）。

**模块红线**：
1. 资产台账是设备类固定资产的唯一权威主数据：一物一码（asset_code 全院唯一），M07/M08/M10/M14/M16 一律只存资产引用（asset_id），禁止自建设备资产主数据副本；设备接入属性（物模型/绑定/遥测）权威在 M14，资产业务属性（采购/验收/折旧/计量/维保/报废）权威在 M15，两实体互引但互不复制对方业务字段（方案 3.1）。
2. 禁止跨模块读表：收入经 M13 取数 API、利用率经 M14 API、工作量经 M07/M08 API、使用事实经 M10 API；不消费 billing.* 事件做实时记账（效益分析为 T+1 日终批量装配，方案 3.4）。
3. 强检法规硬约束：列入强检目录的设备检定逾期即视为不满足使用条件，本模块内置拦截（使用登记强拦截 + 可用性校验接口对外提供），不合格检定结论同样禁用（调研依据 2、3）。
4. 报废处置必须双审批（报废鉴定 + 国资处置审批）且全程留痕，处置完成置终态后档案只读、只可调阅；已报废资产不得发起任何领用/报修/保养动作。
5. 不良事件"可疑即报"时限（死亡 7 日、严重伤害/可能导致严重伤害或死亡 20 日、群体事件 12 小时）由系统硬性监控提醒，禁止静默超期（调研依据 6）。

## 2. 调研依据

1. 《医疗卫生机构医学装备管理办法》（卫生部印发）核心条款：① 机构领导、医学装备管理部门、使用部门**三级管理制度**，二级以上设专门装备管理部门并成立医学装备管理委员会（重大事项论证咨询）；② 单价 1 万元及以上或一次批量 5 万元及以上纳入**年度装备计划**管理，单价 50 万元及以上须**可行性论证**（配置必要性/社会经济效益/预期使用/人员资质）；③ **验收制度**：到货安装调试后由装备管理部门组织使用部门、供货方按合同验收，验收报告各方签字、在索赔期限内完成；④ **分类分户电子账目信息化管理**，档案集中统一管理、保管期限至报废，单价 5 万元及以上建管理档案（申购资料/技术资料/使用维修资料）；⑤ 不得使用无合格证明、过期、失效、淘汰的装备；生命支持类、急救类、植入类、辐射类、灭菌类和大型医用设备使用情况**重点监控**，须计量准确、性能合格方可使用，并制定生命支持/急救类应急预案；⑥ **维修制度**（优化报修流程）与**预防性维护**（按期保养、减少故障）；⑦ 使用人员培训考核合格上岗；⑧ **使用评价制度**：大型设备使用/功能开发/社会效益/费用分析评价，长期闲置、低效运转、超标准配置装备院内**调剂使用**；⑨ 处置方式为调拨、捐赠、报废，报废情形含"严重损坏无法修复或维修费用过高""失效或技术落后不能满足需求"等，公立机构处置须履行国资审批手续。（来源：https://www.nhc.gov.cn/guihuaxxs/c100133/201103/29a80bf8e52d4b109162f937ca44f068.shtml ）
2. 强制检定制度：《中华人民共和国强制检定的工作计量器具检定管理办法》规定强检器具由法定或授权计量技术机构执行检定，合格发**检定证书/检定合格证/合格印**，不合格发检定结果通知书；《实施强制管理的计量器具目录》（市场监管总局公告修订）将用于医疗卫生对人体心电、脉搏、血氧饱和度等测量的计量器具列入强检（监管方式 P+V，强检形式为周期检定，周期按检定规程）；《计量检定印、证管理办法》明确检定证书/结果通知书/合格证/合格印五类印证。（来源：https://xzfg.moj.gov.cn/front/law/detail?LawID=121 、https://www.nim.ac.cn/node/773 、http://psp.e-cqs.cn/InspMeter/RemindDetectionApply/RemindDetection.jsp 、https://www.smq.com.cn/Web/DownLoadLCT.aspx?ID=5 、https://policy.mofcom.gov.cn/claw/clawContent.shtml?id=12615 ）
3. 医疗设备计量检定实践：未申请检定或检定不合格的计量器具**不得使用**，任何单位不得拒绝计量监督检查；医院一批设备属依法管理的计量器具目录范围，需建立周期检定台账与质量检测体系。（来源：http://cs.china-cmd.org/zgylsb/CN/article/downloadArticleFile.do?attachType=PDF&id=1724 ）
4. 预防性维护（PM）：PM 指在设备故障前按计划（时间/使用量周期）执行检查、清洁、润滑、调整、部件更换与性能/精度校准以消除隐患（SAP/IBM 通用定义）；WHO《医疗设备维护管理概论》要求临床工程部门将各设备的检测频率与工时纳入年度 PM 计划；真实医院制度实践为维修人员每季度全院巡检、每月对急诊/手术室/ICU/产房/血透等重点科室巡检；《医疗机构医疗设备、医用耗材管理质量控制考核评价准则》（总 Spec 来源 31）将"开展预防性维护与质量控制、定期巡检并有记录、专人管理"列为考核项；研究表明 PM 相比故障后维修能显著降低急救生命支持类设备故障率与设备相关不良事件。（来源：https://www.sap.cn/resources/what-is-preventive-maintenance 、https://www.ibm.com/cn-zh/think/topics/what-is-preventive-maintenance 、https://iris.who.int/bitstreams/5a101d73-a438-4775-9222-25dc5dcdab84/download 、https://www.zyxyfy.com/ksdh/gljg/yyhzznbm/sbglb/gzzd/content_14269 、https://www.zjha.org/downLoadFileById?annexId=1000028 、https://www.ylzbzz.org.cn/index.php?m=content&c=index&a=show&catid=43&id=416 ）
5. 维修工单闭环生产实践：主流医院报修系统均按"报修→派工→维修执行→验收→评价归档"闭环建模，把散落在电话/微信/纸质单的报修转化为可追溯数字流程；维修方式分**自修**与**外修**（厂家/第三方，保修合同控制费用）；统计维度含设备履历、故障率、停机时间、维修费用（含外修费用）对比；痛点为报修遗漏、派工忙闲不均、进度不透明。（来源：https://www.hollycrm.com/innews/10189.html 、https://m.sohu.com/a/1020278335_120825411 、https://www.itgcb.com/detail?id=1390393690232459264 ）
6. 不良事件监测：《医疗器械不良事件监测和再评价管理办法》（市场监管总局令第 1 号，NMPA 解读）确立**可疑即报**原则；使用单位发现或获知**导致死亡**的可疑事件 **7 日内**、**导致严重伤害/可能导致严重伤害或死亡**的 **20 日内**通过国家医疗器械不良事件监测信息系统报告（二级以上医疗机构须注册为系统用户，系统报送视同告知持有人）；**群体事件** 12 小时内电话/传真报省级药监与卫生行政部门、24 小时内按个例报告并迅速自查；国家系统 maers.adrs.org.cn 于 2019-01-01 上线运行。（来源：https://www.nmpa.gov.cn/directory/web/nmpa/yaowen/ypjgyw/ylqxyw/20180831142701571.html 、https://maers.adrs.org.cn/ ）
7. 效益分析口径（行业公式与实践）：**开机率 =（开机天数 ÷ 应工作天数）× 100%**；**使用率（机时利用率）=（实际工作时数 ÷ 额定工作时数）× 100%**；**投资收益率 = 年净收入 ÷ 投资总额**；**投资回收期 = 投资总额 ÷ 年净收入**；量本利（盈亏平衡人次）法用于购置论证；成熟方案按**全院/科室/单机**三维度输出效益，《大型医用设备绩效评估指标体系构建》给出社会效益/经济效益/发展可持续三维度指标体系并强调"通过数据接口打通医院信息系统"取数；前沿实践用物联网采集设备真实运行数据自动统计开机率/使用率，替代人工登记。（来源：https://zhuanlan.zhihu.com/p/72304376 、https://html.rhhz.net/ZGWSZY/html/2022-6-813.htm 、http://cs.china-cmd.org/zgylsb/CN/article/downloadArticleFile.do?attachType=PDF&id=3560 、https://zgylqxzz.xml-journal.net/article/doi/10.12455/j.issn.1671-7104.230210 ）
8. UDI 医疗器械唯一标识：UDI 由 **UDI-DI**（产品静态标识）与 **UDI-PI**（生产标识：序列号/生产批号/生产日期/失效日期）组成，配套数据载体（一维码/二维码/RFID）与数据库三部分，是医疗器械"身份证"；使用单位应用实践表明 UDI-DI+PI 联合可实现批次/单件级追溯与"一物一码"精细管理（发码机构为 GS1 中物编码等指定机构）。（来源：https://udid.nmpa.gov.cn/attachments/attachment/download.html?path=A2E0C4E371D356DC134BE4F49B8062115BDE71683E61CA7B38AFC6519F30AB1DEDE2715EDD765BE9B05BDE18310EB7E19A090B26E28A75B9DA99300E9EA25D3E6356E9A5DE57D21C669B8237250DBEC6 、https://zgylqxzz.xml-journal.net/cn/article/pdf/preview/10.3969/j.issn.1671-7104.2021.01.016.pdf 、https://www.cmdi.org.cn/zx_4/xyzl/202101/t20210124_277262.html ）
9. 一物一码资产标签实践：每个资产对应唯一二维码，扫码即看资产档案（基础信息/购置信息/维保到期/附件资料）并可直接发起**报修、巡检、保养、盘点**；扫码盘点对错盘/漏盘/重盘告警；RFID 具备批量远场识别优势但成本高于二维码，医院资产盘点以二维码为主、RFID 为高价值设备增强选项。（来源：https://cli.im/solutions/asset-management/asset-management-qrcode 、https://zhuanlan.zhihu.com/p/619912038 、https://www.xiaomai-rfid.com/list_17/162.html ）

## 3. 方案推导（关键设计点选型）

### 3.1 资产台账与 IoT 设备档案的关系：单一实体 vs IoT 为主从 vs 双实体互引

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 单一实体（asset 兼做接入档案） | 在资产表上扩展一机一密凭证、物模型、绑定等接入字段，M15 一张表管全部 | 违反模块边界与 M14 已定稿的设备元数据中心职责（其 Spec 红线 1：绑定判定唯一权威在 M14）；大量资产（手术床、器械柜、无通信接口的老设备——总 Spec 5.2 实证多数输液泵）永不接入 IoT，接入字段长期为空，档案语义污染；M14 遥测管道高频读写设备行，与管理台账低频重事务互相干扰 |
| IoT 为主、资产从属 | iot_device 为主数据，资产作为其附属信息 | 主从方向颠倒：资产的生命周期（采购/验收/折旧/计量/报废）是财务与装备管理口径，先于接入存在、也存在于永不接入的设备上；以"能否联网"作为资产存在的前提，等于把未联网资产排除出台账，违背《办法》"分类分户电子账目"全覆盖要求（调研依据 1） |
| **双实体互引（选定）** | M15 `asset` 为资产权威主数据（存 `iot_device_ref`，可空——未联网资产为空）；M14 `iot_device` 为接入档案（经订阅本模块事件维护 `asset_ref` 展示级冗余引用）；两侧互引**只存对方主键、互不复制业务字段**；同步机制=事件驱动（asset 建档/变更/报废发布事件，M14 幂等消费维护引用与停用）+ 每日对账兜底（引用存在性与一致性核对，漂移即告警并单侧修复提示）；资产↔设备对照的查询权威在本模块（`GET /assets/by-device`），任何模块需要"设备↔资产"映射一律调本接口，不读对方表 | 职责纯净：接入归 M14、资产归 M15，与两模块已定稿 Spec 的非职责声明互为镜像；覆盖未联网资产（iot_device_ref 为空合法）；事件同步解耦、幂等可重放，对账兜底防两处主数据漂移；M14 侧 asset_ref 引用列与 asset.* 事件订阅已在 M14 v1.1 落地（统一审查裁决采纳，见第 12 节修订记录） |

**结论**：双实体互引。资产建/撤同步链路：建档（或关联既有 iot_device）→ 发布 `asset.asset.created` / `asset.asset.changed`（载荷含 asset_id、iot_device_ref、状态、使用科室）→ M14 订阅维护引用并在绑定/命令下发场景提示资产合规标识；报废 → 发布 `asset.asset.scrapped` → M14 置 iot_device `DISABLED`、M16 解除冷链/标签引用。

### 3.2 计量/保养周期调度模型：设备行日期字段+扫描 vs 外部调度引擎 vs 规则-计划两级+延迟消息链

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 设备行存"下次检定日期/下次保养日期"+定时任务扫描 | 每台设备两个字段，扫描到期即提醒 | 实现最简，但历次检定/保养记录无处挂靠（历史不可溯，违反档案要求）；提醒提前量、多级升级、窗口期等语义无法表达；计量与保养两套重复逻辑；日期字段被手工改动无审批留痕 |
| 外部调度引擎硬编码 | Quartz/XXL-Job 任务表集中编排 | 调度与业务规则耦合在代码中，周期/提前量/责任岗位不可由设备科配置；与本系统既有 `fy.delay`（TTL+DLX）延迟消息基建重复建设 |
| **规则-计划两级模型 + fy.delay 延迟链（选定）** | 第一级**周期规则**（metrology_rule / pm_rule：按设备类别或单机配置周期、提醒提前量 30/15/7 天[参数化]、责任岗位、巡检模板引用）；第二级**到期计划**（metrology_plan / pm_plan：由规则按当前记录的完成时点自动推进生成下一期，含应检/应保日期、计划窗口、责任人）；提醒与逾期经 `fy.delay` 延迟队列驱动：计划生成即投递"提前提醒"延迟消息 → 到期置 DUE 并再次提醒 → 窗口关闭未执行置 OVERDUE，触发**逾期升级动作**（重复通知并逐级升级至装备管理部门负责人，动作不新增状态，对齐 M14 告警升级模式）→ 发布 `asset.metrology.overdue` / `asset.pm.overdue`；执行后写记录（metrology_record / pm_record）并按规则推进下一期计划 | 历史完整（规则/计划/记录三层分离，历次记录挂计划）；参数全部可配置（周期、提前量、责任岗位）；复用全系统统一的 fy.delay 基建，无新增中间件；强检合规审计链完整（规则→计划→提醒→记录→证书） |

**逾期未检的使用拦截策略**（强检硬约束落地）：① 本模块强拦截——扫码使用登记（usage_log，见 FU-M15-05 双口径说明）时校验资产可用性（强检逾期/维修中/待报废/已停用即拒绝并提示原因）；② 对外提供批量可用性校验接口 `GET /usage-eligibility?assetIds=`，供 M07（上机）、M08（登记）在业务动作前调用；M14 已按统一审查收窄口径落地——仅命令下发前同步调用，绑定场景走 `asset.metrology.overdue` 事件+本地缓存合规标识（M07/M08 最低保障同为订阅 `asset.metrology.overdue` 在各自界面展示"强检逾期"合规标识）；③ 强检逾期同时联动 asset 置"待检停用"标记，检定合格后自动解除。

**结论**：规则-计划两级 + fy.delay 延迟链 + 三层拦截（本模块强拦截/对外校验接口/合规标识广播）。

### 3.3 维修工单与停机损失统计：登记簿 vs 全状态工单+备件联动 vs 五态工单+停机口径双记

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 报修登记簿 | 只登记报修与处理结果，无状态流转 | 无法统计响应时长/停机时长/故障率，外修费用与工单脱节，不满足《办法》"维修制度+优化报修流程"与报废鉴定"维修费用过高"取证要求（调研依据 1） |
| 全状态工单+备件库存联动 | 工单驱动备件扣减/退补、供应商结算 | 备件库存属物资管理域（本期 M15 不做备件账，避免与 M06 物资边界纠缠）；配件仅登记名称/数量/费用不联动库存，满足履历与费用统计；全状态机徒增复杂度 |
| **五态工单 + 停机口径双记（选定）** | 状态链：REPORTED（报修）→ ACCEPTED（受理/派工）→ REPAIRING（维修中，含外修子形态 repair_mode=EXTERNAL：送修/返回登记）→ PENDING_ACCEPTANCE（待验收）→ CLOSED（已关闭）；验收不合格退回 REPAIRING（分段累计）；受理前可 CANCELLED（误报/重复报修，必填原因）；**停机时长口径**：起=受理时核定的"故障停用时间"（报修单可填发现故障时间、受理人核定修正），止=验收通过时刻，跨验收退回分段累加；**停机性质双记**：故障停机（本工单）与计划停机（保养/检定计划窗口）分开统计，供效益分析区分"可靠性问题"与"计划占用"；维修费用（人工工时费[可选]+配件费+外修费）挂单归集 | 五态覆盖真实闭环且状态数最少（调研依据 5 生产实践同构）；停机口径显式定义消除统计歧义；双性质停机与效益分析联动解释利用率（低利用+高故障停机=可靠性问题；低利用+无停机=配置过剩→调剂依据，《办法》第四十一条） |

**与效益分析的数据关系**：repair_order 关闭后（`asset.repair.closed`）其停机窗口与费用进入 device_benefit_stat 装配（方案 3.4），效益报表中"故障停机时长/维修费用"列即来源于此；急救/生命支持类设备报修受理时自动提示同类别备用机清单（含 M16 RTLS 位置，P2）与应急借用入口（《办法》应急预案要求，调研依据 1）。

**结论**：五态工单 + 外修子形态 + 停机起止核定 + 停机性质双记 + 费用挂单归集。

### 3.4 效益分析数据装配链：事件实时归集 vs 要求 M13 加设备维度 vs 映射归集+日终批量装配

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 订阅 billing.* 事件实时归集 | 消费费用生成/退费/红冲事件实时累加收入 | 费用存在退费审批、红冲重结等逆向链路（M13 已定稿），实时归集必须双向对冲消费且保证重放幂等，复杂度高；效益分析是管理报表，实时性无必要；事件流量与报表价值不成比例 |
| 要求 M13 费用行增加设备维度 | fee_record 增设备引用，按设备直接聚合 | M13 fee_record 无设备引用（已定稿：来源单据/执行单维度），设备归集不是计费要素，改上游计费主数据语义代价大；且科室共用项目（如病区监护仪）无法逐笔挂单机 |
| **映射归集 + 日终批量装配（选定）** | 本模块维护**设备↔收费项目关联**（asset_charge_mapping：单机专属收费项目[如某台 CT 的检查费]一对一映射；科室共用项目按执行科室归集到科室层），日终批量调用 M13 只读收入统计 API（按收费项目×执行科室×日期聚合）取数，本地装配 device_benefit_stat 宽表：收入（M13 快照）+ 利用率（M14 `GET /quality/device-usage` 日统计 + 本模块 usage_log 人工登记口径）+ 工作量（M07/M08 统计 API、M10 使用事实）+ 成本（折旧[原值×折旧参数]+维修费[repair_order]+保养计量费用）→ 单机/科室/全院三级报表；T+1 可重算幂等（重算覆盖快照，保留版本） | 不动上游主数据语义；收入列一律为 M13 取数快照并记录取数版本，与 M13 对账可追；映射归集是医院单机核算的成熟实践（调研依据 7：三维度效益分析均以收费项目→设备归集为前提）；装配失败可降级重跑，不阻塞任何业务 |

**指标口径结论**（报表元数据固化，可解释可审计）：开机率=开机天数/应工作天数（应工作天数按设备日历与计划停机核定）；使用率=实际使用时长/额定机时（IoT 口径取 M14 有数据时长，人工口径取 usage_log 累计时长，报表显式标注数据源）；投资收益率=年净收入/投资总额；投资回收期=投资总额/年净收入；单机效益=归集收入−归集成本。低利用率清单联动调剂建议（《办法》第四十一条）。

**结论**：asset_charge_mapping 映射归集 + 日终批量装配 + T+1 可重算 + 指标口径元数据化。**M13 取数契约**：不订阅 billing.* 事件；调用 M13 对外只读收入统计 API（同 M19 取数接口位口径），提请统一审查将 M13 该 API 的被依赖方扩展至 M15。

## 4. 领域模型

表设计统一遵循 README 第 3 节约定：雪花 BIGINT 主键、统一审计字段（created_by/created_at/updated_by/updated_at/deleted）、TIMESTAMPTZ 服务器时间、逻辑删；金额 BIGINT 分值制（单位分）。

| 实体 | 关键字段 | 说明 |
| --- | --- | --- |
| asset_supplier 供应商登记 | supplier_code、supplier_name、credit_code(统一社会信用代码)、联系人/电话、保修合同引用、状态 | 设备采购维度轻量登记；不做全院供应商主数据域 |
| purchase_request 采购申请 | request_no、apply_dept、asset_class(设备类别)、name/规格建议、quantity、budget_amount(BIGINT，分)、plan_flag(计划内/计划外)、demonstration_ref(可研/论证文件引用，50 万及以上必填)、committee_opinion(委员会意见引用)、procurement_mode(集中采购/公开招标/其他[报批依据])、contract_no、supplier_ref、deal_amount(BIGINT，分)、status(见状态机) | 覆盖申请→论证→审批→采购结果登记（《办法》第三章计划与采购；1 万/5 万计划阈值与 50 万论证阈值走系统参数可配置） |
| acceptance_record 验收单 | acceptance_no、request_ref、arrived_at、installed_at、acceptance_date、conclusion(合格/不合格[索赔])、signers(装备管理部门/使用部门/供货方三方签字引用)、claim_deadline(索赔期限)、report_ref(验收报告附件)、status | 三方验收+索赔期提醒（《办法》第二十四、二十五条） |
| asset 资产台账 | asset_code(一物一码，全院唯一)、asset_name、asset_class(设备分类，模块专业字典：对齐全国卫生系统医疗器械仪器设备分类与代码)、brand/model、serial_no、origin(国产/进口)、registration_no(医疗器械注册证号)、config_permit_no(大型医用设备配置许可证号，可空)、udi_di(UDI-DI，可空)、risk_class(风险等级：生命支持/急救/植入/辐射/灭菌/大型/普通——对应《办法》第三十四条监控分类)、meter_attr(计量属性：强检/非强检强校准/免检)、supplier_ref、origin_type(采购/捐赠/调拨)、purchase_date、original_value(BIGINT，分)、depreciation_method/dep_years/monthly_dep(BIGINT，分)（折旧参数，供效益分析与财务对账）、using_dept、location、keeper(责任人)、iot_device_ref(M14 设备标识，可空)、metrology_flag(待检停用标记)、label_printed(标签打印状态)、status(见状态机) | 资产权威主数据；一物一码发号+二维码标签；资产侧对 M14 的唯一引用列 |
| asset_transfer 领用转移借用单 | transfer_no、asset_id、transfer_type(领用/退库/院内转移/调拨/借用/归还)、from_dept/to_dept、from_keeper/to_keeper、reason、borrow_due_at(借用应还时间)、confirm_out/confirm_in(双方确认记录)、status(见状态机) | 领用/转移/借用/归还统一单据模型，双确认闭环；调拨对应《办法》处置方式之一 |
| asset_inventory 盘点单 + asset_inventory_line 明细 | inventory_no、scope(全院/科室/类别)、planned_count/scanned_count/surplus_count/loss_count、line: asset_id、scan_result(正常/盘盈/盘亏/位置不符)、scan_by/scan_at、status(进行中/已关闭) | 扫码盘点；重复扫码幂等；盘盈补建档、盘亏走差异处理审批 |
| asset_lifecycle_log 全生命周期流水 | asset_id、action_type(建档/验收/领用/转移/借用/归还/维修/保养/检定/停用/启用/调剂/盘点差异/报废申请/报废处置等)、action_at、operator、biz_ref(关联单据号)、digest(摘要) | 只增表；设备履历完整审计链，报废鉴定取证依据 |
| metrology_rule 计量周期规则 | rule_code、scope_type(设备类别/单机)、scope_ref、mandatory_flag(强检)、period_months(检定周期，按检定规程)、remind_days(提前提醒集[30/15/7])、responsible_post(责任岗位)、enabled | 强检目录以模块字典维护（目录代码/强检方式/参考周期），规则引用之 |
| metrology_plan 检定计划 | plan_no、rule_ref、asset_id、due_date(应检日期)、window_end(窗口止)、responsible、status(PENDING/DUE/OVERDUE/COMPLETED，见状态机)、escalation_count | 周期推进生成；逾期升级计数对齐 M14 动作式升级模式 |
| metrology_record 检定记录 | record_no、plan_ref、asset_id、metrology_org(检定机构)、result(合格/不合格)、certificate_no(证书/通知书编号)、certificate_ref(证书附件引用)、valid_from/valid_to(有效期)、executor、recorded_at | 检定证书/结果通知书登记（调研依据 2/3）；不合格自动置资产待检停用并生成整改计划 |
| pm_rule 保养规则 + inspection_template 巡检模板 | pm_rule: rule_code、scope_type/scope_ref、period_type(时间周期/使用量周期)、period_value、template_ref、responsible_post、enabled；template: template_code、template_name、asset_class、items(巡检项集：项目/方法/合格标准[JSONB]) | PM 规则与巡检模板分离，模板按设备类别复用；生命支持/急救类默认高频周期（参数可配，调研依据 4） |
| pm_plan 保养计划 / pm_record 保养记录 | plan 同 metrology_plan 结构；record: record_no、plan_ref、asset_id、executed_at、executor、item_results(逐项结果：合格/异常/不适用+数值)、abnormal_digest(异常摘要)、repair_ref(异常转报修单引用)、duration_min | 执行逐项打卡；异常发现一键转报修（repair_order.source=PM 巡检） |
| repair_order 维修工单 | order_no、asset_id、source(扫码报修/工作台报修/PM 巡检转单/IoT 状态提示人工确认)、reporter、fault_desc、fault_class(故障分类，模块字典)、urgency(普通/紧急/急救类高优先)、acceptor/repairer、repair_mode(自修/外修)、external_info(送修日期/返回日期/维修商/保修标识)、downtime_from(故障停用时间，受理核定)、downtime_to(验收通过时刻)、downtime_segments(分段累计，验收退回产生多段)、parts_used(配件记录：名称/数量/费用)、labor_cost/external_cost/parts_cost/total_cost(BIGINT，分)、cause/measure(故障原因/处理措施，结构化)、acceptance(验收人/结论/时间)、status(见状态机) | 方案 3.3 全套；费用挂单归集；保修期内设备报修自动提示保修合同状态 |
| usage_log 设备使用登记 | log_id、asset_id、using_dept、log_type(开机/使用/归还)、logged_at、operator、eligibility_result(校验结果：通过/拦截原因) | 非 IoT 设备人工口径数据源 + 强检拦截点（方案 3.2） |
| device_usage_stat 利用率日统计（缓存） | asset_id、stat_date、source(IOT/IOT+MANUAL/MANUAL)、powered_days_flag(当日开机标记)、usage_minutes(使用时长)、expected_minutes(额定机时)、upstream_ref(M14 统计批次引用) | M14 `GET /quality/device-usage` 日终拉取缓存 + usage_log 聚合合并；M14 不可用时人工口径降级标记 |
| asset_charge_mapping 设备收费项目关联 | asset_id、charge_item_ref(M13 收费项目标识)、attribution_type(单机专属/科室分摊)、share_rule(分摊规则：按机台均摊/按工作量权重)、effective_from/to、maintained_by | 收入归集映射权威（方案 3.4）；设备科与物价员协同维护，M13 不感知 |
| device_benefit_stat 效益分析日表 | stat_date、dim_type(单机/科室/全院)、dim_ref、revenue(BIGINT 分值制，M13 快照)、workload(工作量)、powered_rate(开机率)、usage_rate(使用率)、fault_downtime_min(故障停机)、plan_downtime_min(计划停机)、dep_amount(折旧)、repair_cost、pm_metrology_cost、net_amount、source_versions(各数据源取数版本/批次引用)、rebuild_at | 装配宽表，T+1 日终批产、可重算（覆盖并保留版本）；月/年为日表聚合视图 |
| adverse_event_md 医疗器械不良事件 | event_no、asset_id(器械引用，可关联未建档器械的 UDI/批号)、udi_pi(生产标识记录)、happen_at、aware_at(发现/获知时间)、event_desc、harm_level(死亡/严重伤害/可能严重伤害或死亡/其他)、patient_ref(patient_id，可空——造成患者伤害时)、device_action(停用/封存/移交持有人/继续使用)、group_flag(是否群体事件)、deadline_at(法定上报截止时刻，按 harm_level 计算)、report_status(见状态机)、report_receipt_ref(国家系统上报凭证引用)、investigation(调查/自查记录)、follow_ups | 可疑即报；《办法》时限硬监控（方案见 FU-M15-06）；患者字段对齐 M02 脱敏规则 |
| asset_document 档案文档 | asset_id、doc_class(申购资料/技术资料/使用维修资料/验收报告/采购合同/注册证/计量证书/合格标识照片/处置批复)、file_ref(对象存储引用)、version、uploaded_by/at | 《办法》第三十二条三类资料+扩展类目；5 万元以上资产档案完整性强校验；保管至报废后转归档 |

关系要点：purchase_request 1:1 acceptance_record → 1:N asset；asset 1:N asset_transfer / asset_lifecycle_log / asset_document / metrology_plan（1:N 记录）/ pm_plan（1:N 记录）/ repair_order / usage_log；asset N:1 asset_supplier；metrology_rule/pm_rule 1:N 各自 plan；asset_charge_mapping、device_usage_stat、device_benefit_stat 挂 asset（或科室维度）；adverse_event_md N:1 asset（可空关联未建档器械）。

## 5. 状态机与业务流程

- **asset 资产**：`IN_SERVICE(在用) ⇄ SUSPENDED(停用：闲置封存/待检停用[metrology_flag 联动]/待修停用) ⇄ IN_REPAIR(维修中，工单受理且需停机时进入，验收合格回 IN_SERVICE)`；`IN_SERVICE / SUSPENDED / IN_REPAIR → PENDING_SCRAP(待报废，鉴定申请受理) → SCRAPPED(已报废，处置审批完成，终态，档案只读)`；`PENDING_SCRAP → 驳回回原状态`。强检逾期置 metrology_flag 待检停用（SUSPENDED 子形态），检定合格自动解除；每次迁移发布 `asset.asset.changed`（报废为 `asset.asset.scrapped`）并留迁移日志与 lifecycle_log。
- **repair_order 维修工单**：`REPORTED(报修) → ACCEPTED(受理/派工，核定 downtime_from) → REPAIRING(维修中；repair_mode=EXTERNAL 时为外修形态，送修/返回登记) → PENDING_ACCEPTANCE(待验收) → CLOSED(已关闭，终态：核定 downtime_to、费用与结论归档)`；`PENDING_ACCEPTANCE → 验收不合格退回 REPAIRING`（停机分段累计）；`REPORTED → CANCELLED(取消，必填原因)`。关键节点发布 `asset.repair.created` / `asset.repair.closed`。
- **metrology_plan / pm_plan**：`PENDING(未到窗口) → DUE(窗口内，提醒中) → OVERDUE(逾期，升级动作持续+发布 asset.metrology.overdue / asset.pm.overdue；强检逾期联动资产待检停用) → COMPLETED(已执行，写记录并推进下一期 PENDING)`；OVERDUE 完成检定同样转 COMPLETED 并解除停用。
- **purchase_request 采购申请**：`DRAFT → IN_APPROVAL(科室提交，逐级审批+50 万以上委员会论证节点) → APPROVED → ACCEPTED(验收通过，转建档) / REJECTED(驳回) / CANCELLED(撤销)`；审批通过后采购结果登记不改状态（合同/供应商/成交金额为字段更新，全程审计）。
- **asset_transfer 领用转移借用单**：`DRAFT → PENDING_OUT(待转出方确认) → PENDING_IN(待转入方确认) → COMPLETED(完成，资产科室/责任人/位置联动变更)`；`PENDING_OUT / PENDING_IN → REJECTED / CANCELLED`；借用超期未还经 fy.delay 提醒与升级。
- **adverse_event_md 不良事件**：`REGISTERED(登记，发现/获知即建) → INVESTIGATING(调查/自查中，登记动作后进入) → READY_TO_REPORT(材料就绪，临期 48 小时由 fy.delay 时限监控自动置入并提醒) → REPORTED(已上报，凭证归档，终态)`；`REPORTED 后监管或持有人反馈 → 补充记录（不改状态，追加 follow_ups）`；临近 deadline_at 未上报自动升级提醒，超期未报持续告警（禁止静默超期）。

主流程时序：

1. **全生命周期主线**：科室采购申请（计划内校验）→ 审批/论证（50 万以上委员会节点）→ 采购结果登记 → 到货验收（三方签字、索赔期提醒）→ 入库建档（asset_code 发号 + 二维码标签打印[M01 模板] + UDI-DI/注册证登记 + 5 万以上档案完整性校验）→ 发布 `asset.asset.created`（M14 建立引用、M16 可关联）→ 科室领用（双确认）→ 使用阶段：检定/保养按期执行、故障报修闭环、扫码使用登记 → 闲置识别（低利用率）→ 调剂/转移 → 报废鉴定 → 双审批 → 处置执行（调拨/捐赠/报废）→ `asset.asset.scrapped` → M14 停用设备、M16 解除引用、档案转归档。
2. **强检闭环**：规则按目录判定强检 → 计划生成 → fy.delay 提前提醒（30/15/7 天）→ 送检/约现场检定 → 登记结果与证书 → 合格标识打印（有效期标签）→ 合格则推进下一期；逾期则升级+停用+广播（方案 3.2 三层拦截）。
3. **维修闭环**：扫码/工作台报修（急救类置高优先并提示备用机）→ 受理派工（核定停用时间）→ 自修或外修 → 提交验收 → 使用科室验收 → 关闭（停机窗口/费用/结论归档）→ `asset.repair.closed` 供效益装配与 M19 统计。
4. **效益分析装配（T+1）**：日终批量任务依次拉取 M14 利用率日统计、M13 收入统计（按收费项目×执行科室×日）、M07/M08 工作量与利用率、M10 手术设备使用事实 → 经 asset_charge_mapping 收入归集 + repair/pm/metrology 费用与停机归集 + 折旧参数 → 装配 device_benefit_stat（记录各源版本）→ 单机/科室/全院报表可查可重算。
5. **不良事件上报**：发现/获知登记（可疑即报）→ 按 harm_level 计算法定截止时刻 → fy.delay 时限监控（临期提醒/超期升级）→ 器械停用/封存处置 → 报送国家监测信息系统（内容生成+凭证回传归档）→ REPORTED 终态，群体事件走 12 小时快速通道提示。

## 6. 功能实现设计（逐 FU）

| FU | 实现设计要点 |
| --- | --- |
| FU-M15-01 资产台账（P1） | 三级管理制度映射（《办法》第二章）：装备管理部门为本模块主要操作方，使用部门设科室资产管理员（数据范围=本科室），管理委员会对应论证审批流节点；采购申请→论证（50 万以上强制可研附件，1 万/5 万计划阈值参数化）→采购结果登记→验收单（三方签字、索赔期到期提醒经 fy.delay）→建档：asset_code 雪花发号+规则化展示号、二维码标签打印（M01 打印模板，标签含资产号供 PDA 扫码）、UDI-DI/注册证号/配置许可证号登记（UDI 为型号级补充标识，非必填）；领用/转移/调拨/借用/归还统一 transfer 单双确认，借用超期 fy.delay 提醒；扫码盘点（盘点单+明细行，重复扫码幂等、错盘漏盘告警，盘盈补建档/盘亏差异审批）；闲置与低效识别（连续 N 月低利用率清单，对接效益分析输出）→院内调剂建议（《办法》第四十一条）；报废：鉴定申请（取维修履历与维修费用比佐证"无法修复或维修费用过高"）→鉴定结论→国资处置审批→处置执行（调拨/捐赠/报废）→终态归档；全动作落 asset_lifecycle_log 只增流水 |
| FU-M15-02 计量检定（P1） | 强检目录字典（目录代码/强检方式/参考周期，模块自管）+ 计量属性判定（建档时按类别+测量功能标注强检/非强检强校准/免检）；周期规则（按类别或单机覆盖）→ 计划生成 → fy.delay 分级提醒（30/15/7 天参数化）→ 检定执行登记（机构/结果/证书号/有效期，附件上传）→ 检定合格打印合格标识（有效期标签走 M01 打印模板）→ 下一期计划自动推进；不合格结论：置待检停用+整改计划+复检登记；逾期三层拦截（方案 3.2）：使用登记强拦截、`GET /usage-eligibility` 对外校验、`asset.metrology.overdue` 广播合规标识；年度强检计划汇总与强检完成率输出（供 M19 质控指标，对齐 zjha 考核准则） |
| FU-M15-03 维修管理（P1） | 报修双入口（一物一码扫码报修自动带出档案与位置；设备科工作台代报）；急救/生命支持类置高优先级并提示同类别备用机清单与应急借用入口（《办法》应急预案要求）；受理派工（工程师分派、核定 downtime_from）；维修执行（故障分类/原因/措施结构化、配件更换登记[名称/数量/费用，不联动库存]）；外修子形态（送修/返回登记、维修商、保修合同标识——保修期内报修自动提示保修状态避免漏保）；验收（使用科室确认，不合格退回分段累计停机）；关闭归档（`asset.repair.closed` 含停机窗口/费用/结论）；统计：单机维修费用与故障率 TopN、响应时长/停机时长分布（供 M19 与报废鉴定取证） |
| FU-M15-04 保养管理（P1） | 巡检模板（设备类别×巡检项：项目/方法/合格标准）；PM 规则（时间/使用量双周期类型，生命支持/急救类默认高频、参数化，对齐 WHO PM 计划方法与医院季度/月度巡检制度实践，调研依据 4）；PM 计划（规则推进生成，fy.delay 提醒与逾期升级，`asset.pm.overdue`）；执行：PDA/扫码逐项打卡（合格/异常/不适用+实测值），异常发现一键转报修（source=PM 巡检，双单互引）；日常巡检（重点科室月度/全院季度模板化）；PM 完成率/及时率统计（质控考核指标输出，对齐 zjha 考核准则"预防性维护+定期巡检有记录"） |
| FU-M15-05 效益分析（P1） | 双口径利用率：IoT 设备日终调 M14 `GET /quality/device-usage`（FU-M14-11 输出：有数据时长/在院时长日统计）入 device_usage_stat 并记 upstream_ref；非 IoT 设备以 usage_log 扫码使用登记（开机/使用/归还）人工口径补充，报表显式标注数据源（IOT/MANUAL）；工作量取数：M07 `/stats/instrument-utilization|workload`、M08 `/stats/modality-utilization|workload`、M10 手术设备使用事实（手术号×设备×时段）；收入取数与归集：方案 3.4 结论（不订阅 billing 事件，日终调 M13 只读收入统计 API，经 asset_charge_mapping 单机专属/科室分摊归集）；成本归集：折旧（原值×参数）+维修费（repair_order）+保养计量费；指标：开机率/使用率/投资收益率/投资回收期/单机净收益（口径见方案 3.4，报表元数据固化）；三级报表（单机/科室/全院）+ 月度年度趋势 + 低利用率清单联动调剂建议；T+1 批产、可重算幂等（重算覆盖并保留版本）；M14/M13 任一数据源失败降级出表并标记缺口，不阻塞报表整体 |
| FU-M15-06 不良事件监测（P2） | 可疑即报原则落地：发现/获知即登记（无需确认因果），事件关联资产或未建档器械的 UDI-DI/UDI-PI/批号（UDI 支撑批次级追溯，调研依据 8）；harm_level 驱动法定时限自动计算（死亡 7 日、严重伤害/可能导致严重伤害或死亡 20 日），fy.delay 时限监控（临期 48 小时提醒责任人、超期持续升级——红线 5）；群体事件标记后走 12 小时快速通道提示（电话/传真报省药监与卫生行政部门的线下动作在系统内生成待办与记录位）；器械处置登记（停用/封存/移交持有人，封存记录留证）；上报方式：生成符合国家监测信息系统要求的报告内容供用户在 maers.adrs.org.cn 填报，凭证截图/回执归档（国家系统无开放上报 API，预留经 M20 对接的扩展位）；患者伤害关联 patient_id（可空，脱敏对齐 M02）；调查/自查记录与 follow_ups 追加；年度报告统计输出（供 M19/医务） |
| FU-M15-07 设备档案文档（P1） | 档案分类按《办法》第三十二条三类（申购资料/技术资料/使用维修资料）+扩展类目（验收报告/合同/注册证/计量证书/合格标识照片/处置批复）；单价 5 万及以上建档完整性强校验（三类必齐方可通过年检校验任务，缺失清单提醒）；文件存对象存储（引用+版本），在线 90 天→归档分层沿用总 Spec 4.4 策略；保管期限至报废（《办法》第三十一条），报废后档案转归档库按国资档案要求延长留存；扫码档案调阅（一物一码入口聚合基础信息/购置信息/维保状态/文档附件，对齐一物一码调研实践）；档案查阅与导出留痕（M01 审计切面） |

## 7. 对外接口

**REST（`/api/v1/asset/` 前缀，响应统一 `{code, message, data, traceId}`）**：
- 采购与验收：`GET/POST/PUT /purchase-requests`、`POST /purchase-requests/{no}/submit|approve|reject|register-procurement`、`POST /acceptances`、`GET /acceptances/{no}`
- 资产台账：`GET/POST/PUT /assets`、`POST /assets/{id}/suspend|resume|scrap-apply|scrap-approve|scrap-execute`（状态动作端点）、`GET /assets/{id}/profile`（扫码档案聚合视图）、`GET /assets/by-code?assetCode=`（PDA 扫码）、`GET /assets/by-device?deviceId=`（资产↔设备对照查询，供 M14/M16/M10）
- 领用转移借用：`GET/POST /transfers`、`POST /transfers/{no}/confirm-out|confirm-in|reject|cancel`
- 盘点：`POST /inventories`、`POST /inventories/{no}/scan`（扫码登记，幂等）、`POST /inventories/{no}/close`
- 计量：`GET/POST/PUT /metrology-rules`、`GET /metrology-plans?status=&deptId=`、`POST /metrology-plans/{id}/records`（检定登记）、`POST /metrology-plans/{id}/remind`（催办）
- 保养：`GET/POST/PUT /pm-rules`、`GET/POST/PUT /inspection-templates`、`GET /pm-plans?status=`、`POST /pm-plans/{id}/records`（逐项执行登记）
- 维修：`GET /repair-orders?status=&deptId=`、`POST /repair-orders`（报修）、`POST /repair-orders/{no}/accept|dispatch|start-repair|external-send|external-return|submit-acceptance|close|cancel`、`GET /repair-orders/{no}/downtime`（停机明细）
- 使用登记：`POST /usage-logs`（扫码使用登记，内置可用性校验，拦截时返回原因）
- 可用性校验：`GET /usage-eligibility?assetIds=`（批量返回可用状态与拦截原因，供 M07/M08；M14 仅命令下发前同步调用——统一审查收窄口径）
- 效益分析：`GET /benefit/devices?dateFrom=&dateTo=`、`GET /benefit/departments`、`GET /benefit/summary`、`POST /benefit/rebuild`（指定区间重算）
- 不良事件：`GET/POST /adverse-events`、`POST /adverse-events/{no}/report`（上报登记+凭证归档）、`POST /adverse-events/{no}/follow-ups`、`GET /adverse-events/{no}/deadline`
- 档案文档：`GET/POST /documents?assetId=`、`GET /documents/{id}/file`（对象存储签名引用）
- 统计输出（供 M19/医务）：`GET /stats/pm-completion`、`GET /stats/metrology-compliance`、`GET /stats/repair-costs`、`GET /stats/inactivity`（闲置清单）、`GET /stats/adverse-events`

**内部服务接口（进程内）**：资产档案与有效性查询（供 M07/M08/M10/M16 引用校验）；资产↔IoT 设备对照查询（供 M14）。

**MQ 事件（`fy.topic` 发布，信封 eventId/occurredAt/producer 遵循 README/M20 约定，先登记 event_registry）**：
- 发布：`asset.asset.created`（建档，载荷含 asset_id/asset_code/iot_device_ref/使用科室——M14 引用建立依据）、`asset.asset.changed`（关键信息/状态/位置/责任人变更——M16/M10/M07/M08 缓存刷新）、`asset.asset.scrapped`（报废终态——M14 停用设备、M16 解除引用依据）、`asset.repair.created`（报修工单创建——M16 冷链设备关联、M14 可用性参考）、`asset.repair.closed`（维修闭环，载荷含停机起止/费用/故障分类——效益装配与 M19）、`asset.metrology.overdue`（强检逾期——可用性拦截依据，M14/M07/M08 订阅展示合规标识）、`asset.pm.overdue`（保养逾期——质控提醒）、`asset.adverse-event.reported`（不良事件上报完成——M19 统计与医务通知）
- 订阅（队列命名 `q.asset.<事件名>`，一律 @RabbitListener + 容器 AUTO 确认 + `integration.received_event` 幂等）：`system.dict.published` / `system.org.changed` / `system.user.changed` / `system.param.changed`（M01 主数据：字典/组织/人员/参数缓存刷新）；`iot.device.status-changed`（M14：设备长期离线/异常置"疑似故障"提示于设备科工作台，人工确认后转报修——不自动建单，防误报）；`ward.cold-chain.alert-archived`（M16：冷链告警处置归档作为设备可靠性记录参考与 PM 规则调优输入，事件名以 M16 Spec 登记为准）；`patient.merged`（不良事件患者引用经 EMPI 归一刷新；成对订阅 `patient.split`——拆分逆映射恢复，M02 成对语义）。**明确不订阅 `billing.*`**：效益收入取数走 M13 只读 API 日终拉取（方案 3.4 结论）。

**延迟队列（`fy.delay`，队列名 `delay.<业务>`）**：`delay.metrology-remind`（检定分级提醒与逾期升级）、`delay.pm-remind`（保养提醒）、`delay.repair-reminder`（待验收超时催办）、`delay.transfer-overdue`（借用超期）、`delay.adverse-event-deadline`（上报时限监控）、`delay.claim-deadline`（验收索赔期提醒）。

**WebSocket**：无自建主题（对齐 M13 做法）。报修提醒/检定临期/不良事件时限提醒统一经 M01 通知中心投递（站内信通道 `/ws/notify/{userId}`）；设备科工作台数据经 REST 快照刷新。

## 8. 集成点

- **依赖上游**：M01（令牌鉴权、RBAC+数据范围[科室维度]、审计切面、通知中心、打印模板[资产标签/合格标识/检定标签]、组织与员工主数据、系统参数[计划阈值/论证阈值/提醒提前量/免审规则]）；M02（不良事件患者伤害引用 patient_id 与合并事件）；M13（收入统计只读 API 日终取数）；M14（利用率日统计 `GET /quality/device-usage`、`iot.device.status-changed` 订阅）；M07/M08（工作量与利用率统计 API）；M10（手术设备使用事实查询）；M16（RTLS 资产位置查询[P2]、`ward.cold-chain.alert-archived` 订阅）；M20（event_registry 登记、received_event 幂等构件、fy.delay 延迟队列、死信治理覆盖本模块事件）。
- **被下游依赖**：M14（资产引用维护与对照查询、报废联动、强检逾期标识）；M16（cold_chain_archive.asset_ref / rtls_tag.bound_ref 的资产有效性查询与报废解除引用）；M10（or_room/surgery_record 的资产标识引用与档案查询）；M07/M08（instrument / exam_modality 资产引用校验）；M19（装备管理指标与效益汇总取数 API）；M01（设备科工作台卡片注册）。
- **与 M14 的关系（双实体互引契约，重点）**：① 权威分工——asset 为资产权威主数据、iot_device 为接入档案；互引只存对方主键（asset.iot_device_ref 可空；iot_device.asset_ref 为展示级冗余引用，权威在本模块——M14 v1.1 已增补该引用列）；② 同步机制——事件驱动（`asset.asset.created/changed/scrapped`，M14 v1.1 已登记订阅并幂等消费）+ 每日对账兜底（本模块核对引用存在性与一致性，漂移告警并提示单侧修复），杜绝两处主数据漂移；③ 报废联动——`asset.asset.scrapped` → M14 置 iot_device `DISABLED`（其状态机允许任意状态迁移）、M16 解除引用；④ 可用性联动——`asset.metrology.overdue` → M14 在绑定/命令下发界面展示合规标识；`GET /usage-eligibility` 已按统一审查收窄口径落地：M14 仅命令下发前同步调用，绑定场景走 overdue 事件+本地缓存合规标识；⑤ 利用率取数——M15 日终调 M14 `GET /quality/device-usage`，M15 不读 iot schema 任何表，设备接入属性（物模型/绑定/遥测）展示一律经 M14 接口。
- **与 M13 的关系（效益收入取数契约，重点）**：不消费 billing.* 事件；日终调用 M13 对外只读收入统计 API（按收费项目×执行科室×日期聚合，同 M19 取数接口位口径，提请统一审查将被依赖方扩展至 M15）；设备归集由本模块 asset_charge_mapping 完成（单机专属一对一映射/科室共用按执行科室分摊），M13 不感知设备维度；收入列一律为取数快照并记录版本，与 M13 每日对账勾稽，差异进异常清单。
- **与 M07/M08 的关系**：两模块业务档案（instrument / exam_modality）已声明资产引用指向本模块；本模块提供资产有效性查询（引用资产报废/停用时其界面可提示）；本模块日终拉取其工作量/利用率统计 API 作为效益工作量口径；不反向写两模块数据。
- **与 M10 的关系**：手术设备（麻醉机/监护仪/腔镜/手术床等）资产台账/计量/维保在本模块（M10 Spec 边界声明一致）；M10 or_room 设备配置与 surgery_record 使用引用集存本模块资产标识；本模块经 M10 对外接口获取手术设备使用事实（手术号×设备×起止时段），与 M14 利用率口径交叉核对（手术室设备遥测在线率与手术排台的相关性校验）。
- **与 M16 的关系**：冷链台账与定位标签引用本模块资产号（其 Spec 已声明"其 Spec 定稿后对齐"），本模块提供资产档案/有效性查询与报废事件；急救类设备应急调配可调 M16 RTLS 资产位置（P2，FU-M16-08）；订阅其冷链告警归档事件作可靠性参考。
- **与 M19 的关系**：输出装备管理指标 API（强检完成率、PM 完成率、完好率、维修费用、闲置清单、效益分析三级汇总、不良事件统计），M19 管理驾驶舱"设备视图"取数（总 Spec FU-M19-02 三视图）。

## 9. 非功能与安全

- 性能：扫码档案聚合视图 P95 < 500ms（本地缓存+单表索引）；效益分析日终批处理（千级资产×四类数据源装配）在夜间窗口 30 分钟内完成，支持断点重跑；盘点扫码并发（多科室同时盘点）按明细行唯一约束幂等；事件消费 P99 < 1s。
- 可用性：台账与校验接口 7×24；效益批处理失败可重跑且幂等（rebuild 覆盖同版本）；M14/M13 数据源不可用时降级出表（缺口显式标记），不阻塞台账主业务。
- 容量与留存：asset_lifecycle_log、repair_order、metrology_record、pm_record、adverse_event_md 长期保留（装备管理与医疗审计要求）；asset_document 文件报废后按国资档案要求延长留存（年限参数化）；device_usage_stat/device_benefit_stat 保留 ≥ 3 年供趋势分析。
- 审计：资产状态变更、报废双审批、转移确认、检定结果登记、不良事件上报、映射与规则调整全量审计（M01 审计切面）；档案查阅/导出单独留痕；效益重算记录操作者与区间。
- 权限：装备管理部门（台账/验收/计量/维修派工/效益分析分角色）、科室资产管理员（本科室数据范围：领用确认/报修/验收/盘点）、审批角色（采购论证/报废鉴定/国资处置分离）、不良事件上报（临床科室+装备管理部门双角色）；报废处置与处置执行为独立权限点。
- 合规映射：《医疗卫生机构医学装备管理办法》（三级管理/年度计划/论证/验收/电子账目/档案至报废/维保/使用评价/闲置调剂/处置审批，调研依据 1）；《计量法》强检制度（目录/周期/证书/合格标识/不合格禁用，调研依据 2/3）；《医疗器械不良事件监测和再评价管理办法》（可疑即报/时限/群体事件/系统上报，调研依据 6）；医疗器械注册证与大型医用设备配置许可登记（《办法》第三十三条）；等保三级（审计/权限/TLS）；个保法（不良事件患者字段脱敏、查阅留痕）。

## 10. 测试要点

- 正常：采购申请→审批→验收→建档→领用→检定保养按期→报修闭环→效益出表全链路；强检到期提醒（30/15/7 天）→检定登记→证书归档→合格标识打印→下一期计划推进；报废鉴定→双审批→处置→M14 停用/M16 解除引用联动；不良事件登记→时限提醒→上报凭证归档→统计输出；效益分析装配链四类数据源版本记录完整、重算结果幂等一致。
- 边界：计划/论证/档案阈值边界（1 万/5 万/50 万参数化精确命中）；检定窗口末日 23:59 与次日 00:00 状态迁移；借用应还时刻跨日提醒；验收退回后停机分段累计正确；退费红冲次日重算后效益收入与 M13 对账一致；已报废资产发起领用/报修/保养/使用登记全部被拒；iot_device_ref 关联与解除后对照查询一致；重复盘点扫码仅计一次。
- 异常：M14 利用率取数失败→报表降级标记缺口且次日自动补拉重算；M13 取数失败重试与告警、差异清单生成；检定逾期升级动作持续至完成；不良事件临期未报升级、超期持续告警；PM 执行异常项转报修双单互引；验收不合格退回再验收；报废审批驳回正确回退原状态。
- 安全：跨科室资产查询/操作 403 且留审计；报废处置越权（无独立权限点）被拒；不良事件患者字段对外展示脱敏；UDI/证书附件下载权限校验；档案导出留痕可检索。

## 11. 自审记录

- [x] 无 TBD/TODO/占位符，文档头 + 12 节内容完整
- [x] 覆盖 FU-M15-01~07 全部条目，无遗漏、无私增（FU-M15-06 按总 Spec P2 定位细化；盘点/调剂/借用为 FU-M15-01"全生命周期"的法规依据细化，见《办法》第三十一条/第四十一条/处置条款；优先级沿用总 Spec）
- [x] 内部一致：领域模型 ↔ 状态机 ↔ API ↔ 测试一一对应（asset/repair_order/metrology_plan/pm_plan/purchase_request/asset_transfer/adverse_event_md 七个状态机均有对应动作端点、流程与测试项；asset_charge_mapping/device_benefit_stat 有维护/查询/重算接口与测试场景）
- [x] 符合跨模块约定：schema=asset；事件命名 `<模块>.<实体>.<动作>` 且信封含 eventId/occurredAt/producer；消费幂等统一落 integration.received_event；交换机只用 fy.topic/fy.dlx/fy.delay 三件套，延迟队列按 `delay.<业务>` 命名；患者关联引用 patient_id（不良事件可空引用）；无跨模块读表（收入/利用率/工作量/使用事实均经 API）
- [x] 依赖方向正确：依赖 M01/M02/M07/M08/M10/M13/M14/M16/M20 对外接口；被依赖清单与 M14（"M15（设备利用率与效益分析）"被依赖声明）、M16（"冷链/定位设备的资产号引用"）、M10（"M15（手术设备使用事实输出）"）、M07/M08（资产引用与统计 API 供 M15）的 Spec 声明互相对齐；无反向依赖
- [x] 方案推导 4 个关键点均有备选对比与依据，含任务要求的三个必选点（资产与 IoT 设备双实体关系、计量/保养周期调度、维修工单与停机统计），每个结论附调研来源
- [x] 无代码级实现（无类名/方法体/SQL DDL 全文；表设计为"表-关键字段-约束"粒度；JSONB/BIGINT 分值制为 README 规定的字段规格说明）
- [x] 歧义消除：双实体互引的权威方向与同步机制、互引列可空语义、停机起止核定与分段累计口径、故障停机与计划停机双记、收入归集映射（单机专属/科室分摊）、双口径利用率标注、强检逾期三层拦截、时限计算规则（按伤害程度）均已显式定义
- [x] 术语与总 Spec 一致（医学装备/一物一码/计量检定/强检/预防性维护/巡检/报修工单/停机损失/效益分析/开机率/不良事件/报废处置）

## 12. 与总 Spec 的偏差

无结构性偏差。四处细化澄清（非偏差，提请统一审查确认）：
1. **FU-M15-01 细化出扫码盘点、借用、闲置调剂**：总 Spec 条目原文"采购-验收-入库-领用-转移-报废全生命周期"，盘点/借用/调剂为《医疗卫生机构医学装备管理办法》（调研依据 1：档案管理、使用评价与闲置调剂、处置条款）支撑下的全生命周期内涵细化，未新增 FU 条目。
2. **FU-M15-05 双口径利用率**：总 Spec 注明效益分析"依赖 M14 IoT 采集"，本 Spec 明确 IoT 口径为主、非 IoT 设备以人工扫码使用登记口径补充且报表显式标注数据源——回应总 Spec 5.2"无任何接口的老设备只能资产化扫码管理（M15）"的既有结论。
3. **新增集成点（改上游已定稿 Spec 的增补请求，提请统一审查裁决）**：① M14 领域模型 iot_device 增补 asset_ref 展示级冗余引用列（权威在 M15，经事件维护）；② M14 绑定/命令下发、M07 上机、M08 登记可选调用本模块 `GET /usage-eligibility` 批量可用性校验（最低保障为订阅 `asset.metrology.overdue` 展示合规标识）；③ M13 对外只读收入统计 API 的被依赖方由"M03/M04/M19"扩展至 M15（同口径只读，无新增载荷）。
4. **不良事件上报方式**：本模块生成报告内容并归档上报凭证，实际填报在国家医疗器械不良事件监测信息系统（maers.adrs.org.cn）完成——该系统无开放上报 API，经 M20 对接列为预留扩展位，不构成 FU-M15-06 功能缺失。

### v1.1 统一审查修订记录

按统一交叉审查报告 Round 1 裁决执行，本版落地项：
- **M-20（确认侧）**：双实体互引在 M14 v1.1 落地——iot_device 已增补 asset_ref 展示级冗余列（权威在本模块 asset.iot_device_ref），M14 已订阅 `asset.asset.created/changed/scrapped`（引用维护与报废置 DISABLED）与 `asset.metrology.overdue`（合规标识）；`GET /usage-eligibility` 收窄口径落地（M14 仅命令下发前同步调用，绑定走 overdue 事件+本地标识）。第 12 节偏差 3 之 ①② 闭环，§3.1/§3.2/§7/§8 相关"提请裁决"措辞已更新为落地表述。
- **M-25**：§7 `patient.merged` 订阅处成对补订 `patient.split`（M02 成对语义，拆分逆映射恢复）。
- **R1-19**：核对本模块无 33 msg/s 常态值引用（遥测吞吐为 M14 域口径，本模块引用的 M14 利用率日统计不受影响），无需修订。
- **偏差 3 之 ③（M13 收入统计 API 被依赖方扩展至 M15）跟踪说明**：经核对 M13 v1.1 文档头被依赖清单未登记本模块，取数接口位口径（同 M19 只读收入统计 API、日终拉取）维持申报状态，待 M13 侧登记后闭环。
