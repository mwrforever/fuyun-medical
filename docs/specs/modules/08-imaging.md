# M08 检查与影像（RIS/PACS）· 功能实现 Spec

| 属性 | 内容 |
| --- | --- |
| 模块编号 | M08 |
| Maven 模块 | `fuyun-imaging`（schema：`imaging`） |
| 版本 / 状态 | v1.1 / 统一审查修订完成 |
| 上游依赖 | M01（认证/权限/字典/参数/审计/通知/打印模板/CA 电子签名）、M02（patient_id 与 EMPI、visit_id 关联、脱敏规则）、M03（门诊检查申请收费放行事件）、M04（住院检查医嘱审核分发事件）、M07（危急值闭环引擎共用，本模块单向 API 调用）、M13（检查计费触发、退费前置校验调用本模块）、M20（事件总线治理、幂等构件、延迟队列） |
| 下游被依赖 | M03（报告发布回执、自助打印放行）、M04（受理/完成回执、医嘱闭环追溯取数）、M09（检查报告与影像入病历、患者全景数据源）、M13（执行状态查询、计费/退费联动）、M15（设备工作量与利用率取数）、M19（检查量/TAT/危急值指标统计取数）、M17（体检检查受理复用，P2 预留）、M18（患者端报告查询复用，P2 预留）、M20（事件契约登记方） |
| 对应总 Spec | FU-M08-01 ~ FU-M08-09 |

---

## 1. 模块定位与边界

**职责**：本模块是检查业务主线（RIS/PACS）的中枢，八项职责：① 检查申请受理与预约——受理住院检查医嘱（M04 分发）与门诊检查申请（M03 收费放行），按设备×时段预约排程、改期与候检队列；② 检查登记与 Worklist——报到登记确认、签发检查号（AccessionNumber）、DICOM Modality Worklist 向设备实时下发已登记检查列表；③ 检查执行管理——MPPS/人工双通道的开始/结束确认、中断与重检记录、造影剂与准备事项留痕；④ 影像接收与归档——DICOM C-STORE 接收、影像匹配入库、三级分层存储与生命周期管理、存储确认（Storage Commitment）；⑤ 影像浏览——诊断工作站阅片（窗宽窗位/测量/放大/历史对比）与临床科室轻量浏览；⑥ 结构化报告——报告模板、书写、初审/终审审核、发布与更正留痕；⑦ 检查危急值触发与展示——危急征象经 M07 危急值引擎闭环（本模块只做触发登记与状态展示）；⑧ 报告发布与患者服务——图文报告发布、患者端查询、胶片打印、云胶片预留、高级后处理集成位（FU-M08-09）。核心资产：检查单状态机（检查全流程的唯一权威）、影像索引库（Study/Series/Instance 三级索引，像素数据在对象存储）、报告版本链、检查危急征象字典。

**非职责**：检查医嘱的开立与审核（归 M03/M04，本模块消费其分发事件并回执）；资金收退付与计价（归 M13，本模块只发计费触发事件、提供执行状态查询 API，不落任何金额字段）；危急值闭环引擎（归 M07——通知/接收/处理/升级/统计均由其状态机驱动，本模块仅触发与展示）；病历文书与患者全景聚合（归 M09，本模块为报告与影像数据源）；设备资产台账/计量检定/维修保养（归 M15，本模块仅维护设备业务档案并存资产引用）；三维重建/MPR 等高级后处理算法（外部组件职责，见 FU-M08-09 与决策 D6）；检验业务（归 M07）；设备遥测采集（归 M14，影像设备不接入 IoTDA 遥测链路）。

**模块红线**：
1. 检查单是检查域唯一锚点：预约、登记、执行、影像、报告、费用联动全部锚定检查单；影像匹配以检查单登记时签发的 AccessionNumber（及映射的 StudyInstanceUID）为准，C-STORE 影像匹配失败的进待处理清单人工处置并告警，禁止静默丢弃或猜测挂接患者。
2. 像素数据不入业务库：业务库（imaging schema）只存索引与元数据、缩略图，像素数据一律落对象存储（MinIO/华为云 OBS）并分层管理；禁止在 PostgreSQL 存储像素大字段。
3. 已发布报告不可变：发布后的修改只能走报告更正流程（新版本发布、原版本置被取代态、全程留痕并广播事件），禁止物理改写已发布报告；报告版本链永不删除。
4. 危急值闭环权威在 M07：本模块只做"危急征象触发登记（经 M07 实例 API，source_domain=EXAM）与本科室展示"，通知/接收/处理/升级一律由 M07 引擎驱动，本模块不得自建危急值状态机。
5. 本模块不落任何金额与费用数据：计费只经事件触发（检查登记事件）与执行状态查询 API 交由 M13 完成；"检查已开始不可退"的退费拦截权威在 M13（其退费前置校验调用本模块执行状态 API），本模块不自行放行退费。
6. 禁止跨模块读表：医嘱上下文经事件载荷与查询契约，患者上下文经 M02 解析服务，费用上下文经 M13 API，DICOM 网络服务为本模块内建能力（M20 通道协议枚举不含 DICOM，见第 8 节）。

## 2. 调研依据

1. **RIS/PACS 全流程**：登记→检查→影像接收（DICOM）→阅片→结构化报告→归档全流程，遵循 DICOM/HL7/IHE 标准——本 Spec 的流程基线（总 Spec 2.6 已引）。（来源：https://developer.aliyun.com/article/1757538 ；https://www.wedcm.com/artical83.htm ）
2. **RIS 业务流程真实形态**：电子申请单缴费后经接口自动传入检查系统，登记员确认登记；预约检查与立即检查两种模式并存；报到支持自助机/人工（凭证件/医保卡/住院条码）；候检叫号支持暂缓（如超声憋尿未达标）、过号顺延、二次叫号；报告书写流程为"技师上传影像→低年资医生初写→高年资医生审核"（放射双签），而超声/内镜为医生边检查边采图、口述选图即时完成报告，通常无独立审核环节；报告模板分公共与个人（按权限可编辑）；痕迹记录含操作医生/动作/时间；急诊即开即做优先。（来源：https://www.woshipm.com/pd/5483123.html ）
3. **DICOM 核心服务**：C-STORE（影像从设备 SCU 传输至归档 SCP 存储）、C-FIND（查询，含 Modality Worklist）、C-MOVE（影像取回）、C-ECHO（连接验证），SCU/SCP 角色分工与协议栈（ACSE→DIMSE）。（来源：https://blog.csdn.net/wangnaisheng/article/details/145517734 ；https://zhuanlan.zhihu.com/p/386657486 ；https://dicom.nema.org/medical/dicom/current/output/chtml/part07/sect_9.3.html ）
4. **MPPS 检查状态上报**：设备开始采集时经 N-CREATE 创建检查执行步骤实例（状态 IN PROGRESS），结束时经 N-SET 更新为 COMPLETED 或 DISCONTINUED（异常中止需重新安排）；MWL（检查前下发计划）→MPPS（检查中上报实际执行）→C-STORE（检查后传输影像）按时间顺序衔接；未启用 MPPS 的系统只能靠影像到达被动推断或人工确认检查完成。（来源：https://www.cnblogs.com/jak-black/archive/2012/12/18/2822896.html ）
5. **Storage Commitment 存储确认**：设备（SCU）以 N-ACTION 请求归档方（SCP）对已收影像作出存储承诺，归档方校验安全存储后以 N-EVENT-REPORT 回告成功清单与失败清单；设备据此方可安全清理本地，杜绝"设备以为传成功而归档侧丢片"。（来源：https://orthanc.uclouvain.be/book/users/storage-commitment.html ；https://www.medicalconnections.co.uk/kb/Storage-Commitment ）
6. **dcm4che / DCM4CHEE 架构**：dcm4che 是 Java 生态成熟开源 DICOM 工具库（SCP/SCU 工具集、影像读写）；dcm4chee-arc-light（Archive 5）为完整 DICOM 归档应用——WildFly 部署、LDAP 集中配置、影像本体存外部存储、数据库存元数据索引，支持 C-STORE/Worklist/MPPS/查询检索/存储确认、DICOMweb（QIDO-RS/WADO-RS/STOW-RS）、IHE 与 FHIR。（来源：https://web.dcm4che.org/ ；https://github.com/dcm4che/dcm4chee-arc-light ；https://pmc.ncbi.nlm.nih.gov/articles/PMC2039778/ ）
7. **影像数据量与小文件特征**：MR 单幅平均约 60KB、CT 单幅约 300KB，均为小文件；一次典型 CT 检查产生 2000 余幅、总量约 1GB（压缩后约 500MB）；十年间 PACS 影像数据量增长 10 倍——存储规划必须分层且容量参数化。（来源：https://www.shxiaoyun.com.cn/solution/show.php?id=172 ；https://zhuanlan.zhihu.com/p/477054893 ）
8. **分级存储行业实践**：在线/近线/离线三级存储是 PACS 经典架构，按影像使用频率制定迁移策略；分布式存储方案普遍以在线高性能池（全闪）、近线混闪池、离线大容量池分层承载，兼顾阅片性能与容量成本。（来源：https://baike.baidu.com/item/PACS%E7%B3%BB%E7%BB%9F/5929422 ；https://www.xsky.com/solution/healthcare/pacs ；https://www.smartx.com/blog/2024/07/smartx-dsaip-solution/ ）
9. **结构化报告实践**：采用"标准编码+结构项+关键图像"整合策略，按疾病/部位设计报告模板（文本报告与图文报告）；诊断报告要素含受检者信息、检查技术、影像表现、影像诊断等（浙江省放射影像诊断报告格式规范）；模板分公有/私有并呈树形分级；修改留痕（时间/账号/内容）与多级审核为招标硬性要求。（来源：https://zgylqxzz.xml-journal.net/cn/article/id/250b095e-8d85-42fe-91ff-5e41114ddfdd ；http://www.zjradiology.org/portal/details/1678909884705280000 ；https://www.ccgp-neimenggu.gov.cn/gpx-bid-file/150001/gpx-tender/2024/10/18/402881d29270baf501929efd19de72ee.pdf ）
10. **检查危急值**：影像医师发现危急征象时在报告系统选择危急值并填写说明，报告审核后随报告发送 HIS；全院危急值平台整合 LIS/PACS/ECG 等医技系统统一闭环管理，配套通报率/及时率/处置合格率质控指标。（来源：https://mp.ofweek.com/medical/a056714281397 ；http://www.fortuneltd.com/html/criticalValue.html ；https://www.cnblogs.com/Javame/p/18253620 ）
11. **云胶片/数字影像服务**：报告审核完毕后患者影像与报告加密上传云端，患者经扫码/链接调阅；江苏省医保局自 2024 年 7 月起将影像检查项目与实体胶片解绑收费、数字影像服务单独计价——云胶片业务落地需物价与医保政策配套。（来源：https://xyy.tsinghua.edu.cn/info/1042/6800.htm ；https://www.qiluhospital.com/show-26-38447-1.html ；https://www.nhsa.gov.cn/art/2024/8/6/art_14_13485.html ）
12. **检查预约排程实践**：预约中心统一号源排班与医技二次预约排班并存（CT/造影按设备排班）；多渠道智能化预约（自主预约/剩余号源优先/工作量优先/机器预约）；MRI 等设备调度需权衡设备转换成本与患者等候（学术优化模型）。（来源：https://www.jdfy.cn/uploads/file/20240508/1715135163401000.pdf ；https://his.wio2o.com/new/yjyy.php ；https://xtglxb.sjtu.edu.cn/CN/10.3969/j.issn.1005-2542.2024.01.005 ）
13. **超声/内镜等非放射影像差异**：PACS 对超声、DSA、内窥镜、病理提供专门报告书写工具与视频/图像采集（边检边采、脚踏控制、图文混排）；内镜中心图文报告系统含视频采集、图像处理、报告编辑、病历管理、临床调阅。（来源：http://portal.smu.edu.cn/wlzx/info/1018/1045.htm ；http://www.101doctor.net/h-col-328.html ）
14. **临床影像调阅**：向临床终端提供统一浏览阅片服务（图像处理与报告查看）为三甲医院招标硬性需求，影像与报告需融入临床诊疗视图。（来源：https://zbcg.301hospital.com.cn/Admin-Portal-fileDownload-accessory_id-64013.html ）

## 3. 方案推导（关键设计点选型）

### 3.1 影像归档与 DICOM 服务路线：全自研协议栈 vs DCM4CHEE 整体作为模块核心 vs 主干自建（dcm4che 为协议底座）+外部后处理集成位

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 全自研 DICOM 协议栈 | C-STORE SCP、Worklist、MPPS、存储确认、Q/R 全部从零实现 | DICOM 一致性验证成本极高（各厂商设备需逐家按一致性声明联调，传输语法/字符集/私有标签边界条件多）；与"简单优先"冲突；成熟开源已验证十余年（调研依据 6），重复造轮子无收益 |
| DCM4CHEE 整体作为模块核心 | 部署 dcm4chee-arc-light 承担全部归档与工作流，业务状态以其为准 | 功能最全，但 WildFly+LDAP+Keycloak 独立技术栈与运维面，与本系统模块化单体、统一权限/审计体系割裂；检查预约/登记/报告/计费联动等 RIS 业务仍需自建，检查状态权威将分裂在两套系统；总 Spec D6 明确"若集成 DCM4CHEE 作为独立子系统经 M20 集成"，即不允许其成为模块内核 |
| **主干自建 + 外部集成位（选定）** | RIS 主干（申请/预约/登记/执行/报告/危急值触发/事件）与影像索引库自建；DICOM 网络服务以内嵌 DICOM 接入组件实现（以 dcm4che 工具库为协议解析底座，类比 M20 以 HAPI 为 HL7 底座、M07 仪器 ASTM 接入的既定先例）；像素数据落对象存储三级分层；FU-M08-09 高级后处理不自研，冻结外部组件接入契约（DCM4CHEE 完整归档或商用后处理工作站作为独立子系统经 M20 集成） | 数据主权在本模块——imaging 索引库是检查/影像/报告状态的唯一权威；设备兼容性风险由成熟开源协议底座兜底；高级后处理按需引入不阻塞主干交付；与决策 D6 逐字对齐 |

**结论**：主干自建 + 外部集成位。集成位契约（本期仅冻结、不实现，见 FU-M08-09）：① 影像取阅出口——本模块提供影像对象读取 API（短时效签名 URL/代理流），预留 DICOMweb 兼容出口（QIDO-RS/WADO-RS）供外部组件与云胶片；② 后处理任务契约——检查/序列级任务下发（任务号+影像引用+算法类型）与结果回执回调；③ 结果索引登记——外部组件产出的后处理结果（重建序列/关键图）经登记接口挂回 imaging_study 索引并标记来源；④ 接入安全——外部组件作为独立子系统经 M20 通道注册（接口账号+白名单+调用留痕），不直连业务库。若引入 DCM4CHEE 完整归档承担存储/转发角色，索引与业务状态仍以本模块为准并每日对账。

### 3.2 Modality Worklist 数据来源与检查完成确认机制

**Worklist 数据来源**（备选对比）：

| 方案 | 评估 |
| --- | --- |
| 开单即进 Worklist | 未预约/未缴费/未登记的检查出现在设备端，技师可绕过登记直接检查，检查单状态与设备执行脱节；退单/改期需高频同步设备端 |
| 设备端全量患者查询 | 不符合 MWL 标准语义（Scheduled Procedure Step 按设备/时段取任务清单），设备端操作负担重 |
| **登记后进入 Worklist（选定）** | 检查报到登记（签发 AccessionNumber）后进入"待检查"应答集，MWL C-FIND 按设备 AE Title/模态实时过滤应答；停嘱/作废/退检即时从应答集移除——设备端看到的永远是"已确认可执行"的检查 |

**检查完成确认机制**（备选对比）：

| 方案 | 评估 |
| --- | --- |
| 仅人工确认 | 技师界面点击开始/结束——对所有设备普适，但检查起止时间靠手工、与设备脱节，报告关联依赖人工选检查 |
| 仅 MPPS | 自动化程度最高，但存量设备与超声/内镜支持率低，且 MPPS COMPLETED 不代表影像已到齐（调研依据 4） |
| **MPPS 优先 + 人工兜底 + 影像完整性判定（选定）** | 支持 MPPS 的设备：N-CREATE 驱动"检查中"、N-SET COMPLETED 驱动"待影像归集"、DISCONTINUED 转"中断待重新安排"；不支持 MPPS 的设备以技师人工开始/结束确认；"影像已接收"最终以 C-STORE 影像聚合完整性判定（幅数达到 Worklist 下发约定或技师确认收图完整）；本模块作为存储确认 SCP 对设备的承诺请求回告 N-EVENT-REPORT（设备方可安全清理本地）；MPPS/人工/影像到达三路信息每日对账，差异进异常清单 |

**结论**：登记后下发 + MPPS 优先人工兜底 + 影像完整性判定。DICOM 通讯节点（AE Title/IP/端口/支持服务）在 dicom_node 配置单元集中管理，设备接入变更全程审计。

### 3.3 影像存储与业务库分离架构

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 业务库存像素大字段 | 像素数据存 PostgreSQL 大对象 | 备份与 WAL 膨胀、海量小文件高吞吐调阅拖垮 OLTP 主库；与总 Spec 4.4"影像文件经对象存储"既定分层直接冲突 |
| 应用服务器本地文件系统 | 影像落本地磁盘自管 | 无生命周期策略、无多副本、扩容与多实例共享困难，单点风险高 |
| **对象存储分层 + 业务库只存索引与缩略图（选定）** | 像素数据落 MinIO/华为云 OBS（纠删码多副本）；imaging 库仅存 study/系列/实例三级索引、元数据与缩略图；生命周期分层（在线 90 天→近线 2 年→归档 ≥15 年，全部参数化，对齐总 Spec 4.4）；调阅性能靠索引缓存+缩略图预生成+在线池高性能介质保障 | 写入路径"对象存储持久化成功后才置索引可见"保证不丢片；索引库轻量可随业务库统一备份；分层迁移对临床透明（归档数据按需回迁） |

**容量估算**（1000 床位基线，全部参数化可配）：日均检查假设 CT 120 例（压缩后单均约 0.25GB）+ MR 40 例（约 0.2GB）+ DR 250 例（约 30MB）+ 超声/内镜 300 例（约 50MB，含静帧与短视频），日增约 60GB、年增约 22TB；在线 90 天约 5.5TB、近线 2 年约 44TB，归档随年限线性增长；实例索引按年分区。无损压缩入库可配置，调阅解压对临床透明。生命周期任务每日低峰执行层级迁移（对象存储生命周期策略与索引 storage_tier 同步更新）；归档池只读，临床调阅触发自动回迁（异步并提示等待）。

### 3.4 检查预约号源模型：复用 M03 门诊号源 vs 设备×时段独立号源池

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 复用 M03 号源池 | 检查预约套用门诊"医生×时段×号别"模型 | 语义不合：检查预约是"设备×时段×检查类型"的**排他资源**模型——检查时长差异大（DR 分钟级、MR 半小时级）、设备能力约束（项目×设备能力矩阵）、患者准备要求（空腹/憋尿/造影剂），均无法用门诊号别表达；设备维修停机粒度也与医生停诊不同 |
| **设备×时段独立号源池（选定）** | exam_slot（设备×日期×时段）持容量/已用/急诊保留/乐观锁版本；时段容量按检查类型时长权重折算；急诊保留时段、设备维护整池冻结 | 检查预约量级远低于门诊挂号（日千级 vs 高峰 2000 人次/小时），DB 条件更新（已用<容量）+同一患者同设备同时段唯一预约约束即可防超约，**不引入 Redis 预扣**（对齐"简单优先"，M03 高并发方案在此属过度设计）；停机维护=整时段池冻结+已约患者批量改期通知（经 M01 通知中心） |

**结论**：设备×时段独立号源池。预约渠道：检查台/病区护士站/医生站/自助机；线上渠道（公众号/小程序）为 P2 经 M18 复用预留；改期=退旧约新（先占新后退旧防两头空）；急诊检查占用保留时段或即时检查（跳过预约直接登记）。

### 3.5 报告审核模式：全院统一双签 vs 按检查组差异化配置

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 全院统一双签（书写→初审→终审） | 一切检查报告强制三步 | 放射/CT/MR 报告必须双签（低年资书写+上级审核）符合诊断报告审核制度；但超声/内镜为检查医师边检边采图即时书写，检查与诊断同一人完成，强制双签与真实流程不符且拉长发布时效（调研依据 2、13） |
| 全部单签 | 书写即签发 | 放射报告单签不符合诊断报告审核制度与影像质控要求（调研依据 9；PACS/RIS 质控实践） |
| **按检查组差异化配置（选定）** | 报告分组（放射/CT/MR/超声/内镜等）×审核模式参数：双签模式（DRAFTING→初审→终审发布）与单签模式（DRAFTING→终审签发即发布）并存；默认放射组双签、超声/内镜组单签，可按院情调整；双签组支持初审打回重写、终审打回重检 | 与真实生产流程一致（调研依据 2）；审核模式是配置而非硬编码，医院等级与科室管理差异可适配；打回链路保证质控抓手 |

### 3.6 检查危急值触发时点：报告发布后触发 vs 阅片发现即触发

| 方案 | 说明 | 评估 |
| --- | --- | --- |
| 报告发布后触发 | 双签+发布完成后随报告触发危急值 | 时效差：危急值处置时限（M07 引擎参数，接收默认 15 分钟）从临床接收起算，但等双签+发布才触发通报，空耗黄金处置时间；与"PACS 发现危急即反馈临床"的真实实践不符（调研依据 10） |
| **阅片发现即触发（选定）** | 报告医师阅片发现危急征象即刻登记触发记录并调 M07 引擎 API 登记危急值实例（source_domain=EXAM，携带征象字典快照与影像引用）；M07 的复检确认环节对检查域语义=上级医师影像复核（复核影像而非复测样本）；报告发布时若实例仍开放则报告携带危急值标记 | 时效最优；误报由 M07 引擎的确认环节兜底（影像复核），与检验域复检机制同构；触发与展示归本模块、闭环归 M07，职责边界与总 Spec FU-M08-06 一致 |

## 4. 领域模型

表设计统一遵循 README 第 3 节约定：雪花 BIGINT 主键、统一审计字段（created_by/created_at/updated_by/updated_at/deleted）、TIMESTAMPTZ 服务器时间、逻辑删、状态字段 VARCHAR 常量+迁移日志。本模块不落任何金额字段。

| 实体 | 关键字段 | 说明 |
| --- | --- | --- |
| exam_item 检查项目 | item_code、item_name、modality_type（CR/DR/CT/MR/US/ENDO/DSA/NM/造影等）、body_part（部位）、默认时长（分钟）、准备要求（空腹/憋尿/禁食/造影剂知情）、报告分组、计费项目 code（M13 引用）、急诊允许标志、status | 检查类型权威字典；模块专业字典（对齐 M14/M04/M07 先例） |
| exam_modality 检查设备 | modality_code、modality_name、modality_type、dept_id、房间位置、能力矩阵（可执行检查项目集）、DICOM 节点引用、每日开放时段模板、状态（正常/维护/停用）、资产引用（M15） | 设备业务档案；FU-M08-01 |
| dicom_node DICOM 通讯节点 | node_code、ae_title、host、port、role（设备/外部系统）、支持服务（C-STORE/MWL/MPPS/存储确认/Q-R）、字符集与传输语法白名单、来源白名单、status | DICOM 网络配置单元；变更全程审计 |
| exam_slot 检查号源池 | modality_id、slot_date、slot_start/slot_end、capacity、used_count、reserved_count（急诊保留）、version（乐观锁）、status | 设备×时段权威库存行；（设备+日期+时段）唯一约束 |
| exam_apply 检查申请单 | apply_no、source_type（INPATIENT/OUTPATIENT）、source_ref（M04 医嘱号/M03 门诊申请单号）、patient_id、visit_id、临床诊断/症状摘要、申请科室/医生、priority（常规/急诊/绿通）、状态（见第 5 节） | 受理主体；受理即生成检查单 |
| exam 检查单（核心） | exam_no、apply_id、patient_id、visit_id、检查项目明细（子表：项目/部位/侧别）、modality_id、priority、退回重检原因、作废/医学终止原因、状态（见第 5 节） | 一次检查一单，检查域状态权威；重检在原单上回环 |
| exam_appointment 检查预约 | appt_no、exam_id、modality_id、slot_id、预约渠道（检查台/病区/医生站/自助/线上预留）、准备事项告知记录、改期链引用、状态（见第 5 节） | 预约排程单据；FU-M08-01 |
| exam_registration 检查登记 | exam_id（唯一）、reg_no、accession_number（检查号，规则化段结构全院唯一）、登记人/登记时间、身份核对结果、准备确认（空腹/知情同意）、状态 | 报到登记记录；登记时点签发 accession 并进入 Worklist 应答集 |
| exam_perform 检查执行记录 | exam_id、技师、开始时间与方式（MPPS/人工）、结束时间与方式、MPPS 实例引用、中断原因、造影剂（品种/剂量/不良反应标记）、体位与曝光条件摘要、候检叫号记录 | 执行过程留痕；MPPS 与人工双通道；重检产生多条 |
| imaging_study 影像检查索引 | study_instance_uid（全院唯一）、exam_id、accession_number、modality、检查日期、幅数、总大小、缩略图引用、storage_tier（ONLINE/NEARLINE/ARCHIVE）、状态（见第 5 节） | study 级索引；一检查单 1:N study（重检换设备场景） |
| imaging_series 影像系列索引 | study_id、series_instance_uid、series_no、序列描述、幅数、存储路径前缀 | 系列级索引 |
| image_index 影像实例索引 | series_id、sop_instance_uid（全院唯一）、instance_no、对象存储键、大小、缩略图键、传输语法、存储层级 | 实例级索引；按年分区 |
| unmatched_image 匹配待处理 | 报文摘要、发送节点、accession/study_uid、患者信息快照、原因（无匹配检查/重复）、状态（PENDING/BOUND/DISCARDED）、挂接处理人与复核人（双人确认） | C-STORE 匹配失败人工处置；禁止静默丢弃（红线 1） |
| exam_report 检查报告（版本） | report_no、exam_id、version_no、correction_of（被更正版本引用）、报告模板引用、书写医师/时间、初审医师/时间、终审医师/时间（CA 引用可选）、影像所见/影像诊断/建议（结构化段+关键图像引用）、危急值标记、报告文件引用（对象存储 PDF）、患者可见标记、状态（见第 5 节） | 版本链支撑更正留痕（红线 3） |
| report_template 报告模板 | template_code、报告分组、modality_type、适用项目/部位、模板层级（公共/科室/个人）、结构项树（检查技术/影像表现/影像诊断/建议各段结构项）、关键图像占位、版本、status | FU-M08-05 |
| critical_finding_dict 检查危急征象字典 | finding_code、征象名称（主动脉夹层/张力性气胸/脑出血/消化道穿孔等）、适用检查组、生效版本、审定记录（医务审批）、status | 检查域征象字典归本模块（对齐 M07 Spec 集成点第 8 节：检查域界限值由 M08 维护并经实例快照带入） |
| critical_finding 危急征象触发记录 | exam_id、report_id（可空=书写中触发）、finding_code、征象描述、影像引用（study/series/instance）、M07 危急值实例号引用、触发人/时间、当前展示状态（同步自 M07 事件） | 只做触发与展示（红线 4）；闭环状态权威在 M07 |
| exam_status_log 检查状态迁移日志 | exam_id、from_status/to_status、reason、operator、occurred_at | 只增表；README 状态迁移留痕要求 |
| report_print 打印记录 | report_id、打印类型（报告/胶片标签）、打印人、终端、打印时间 | 打印留痕与补打控制 |
| postprocess_task 高级后处理任务（P2 预留） | task_no、study/series 引用、算法类型（MPR/三维重建等）、外部组件引用、下发/回执时间、结果索引引用、status | FU-M08-09 集成位实体，本期仅建模不启用 |

关系要点：exam_apply 1:1 exam；exam 1:0..1 exam_appointment、1:0..1 exam_registration、1:0..N exam_perform、1:N imaging_study、1:N exam_report（版本链）、1:0..N critical_finding；exam_modality 1:N exam_slot；imaging_study 1:N imaging_series 1:N image_index；dicom_node 1:N exam_modality / unmatched_image；patient（M02）经 patient_id 关联一切实体。

## 5. 状态机与业务流程

- **exam_apply 检查申请单**：`CREATED（已受理，生成检查单）→ CANCELLED（作废：停嘱/退费逆向，终态）`。受理双通道：消费 `inpatient.order.audited`（exam 子键）或 `outpatient.order.charged`。
- **exam 检查单（权威状态机）**：`WAIT_SCHEDULED（待预约）→ SCHEDULED（已预约）→ REGISTERED（已登记，签发 accession_number、进入 Worklist）→ PERFORMING（检查中）→ IMAGES_RECEIVED（影像已接收）→ REPORTING（报告书写中）→ FIRST_AUDITED（已初审）→ PUBLISHED（已发布，终态）`。侧支：`REPORTING/FIRST_AUDITED → REGISTERED（退回重检：影像不合格/需补序列，原因留痕，重检后重新采集影像与报告）`；`FIRST_AUDITED → REPORTING（初审打回重写）`；`WAIT_SCHEDULED/SCHEDULED → CANCELLED（作废：停嘱/退单/患者放弃）`；`REGISTERED → CANCELLED（退检：登记后未检查，独立权限+必填原因，M13 退费联动）`；`PERFORMING → REGISTERED（检查中断 DISCONTINUED，重新安排，中断原因留痕）`；`PERFORMING → MEDICAL_ABORT（医学终止，终态：独立权限+必填原因；费用照收，走协商/挂账处理，不退费）`——MEDICAL_ABORT（医学终止）与 CANCELLED（未开始作废，可退费）严格区分。急诊检查可由 `WAIT_SCHEDULED` 直接转 `REGISTERED`（跳过预约，即时登记）。每次迁移留 exam_status_log 并发布对应事件。
- **exam_appointment 检查预约**：`RESERVED（已预约）→ CANCELLED（取消/改期退旧）`；`RESERVED → NO_SHOW（爽约：超时未报到，累计记录，参数化限约）`。改期=新建 RESERVED 并回填改期链。
- **imaging_study 影像索引**：`RECEIVING（接收中）→ COMPLETE（接收完整：完整性判定通过）`；`RECEIVING → INCOMPLETE（超时未完整，异常清单；补传完整后回 COMPLETE）`。storage_tier（ONLINE/NEARLINE/ARCHIVE）为属性字段，由生命周期任务驱动，不占状态位。
- **exam_report 报告版本**：`DRAFTING（书写中）→ FIRST_AUDITED（初审通过）→ PUBLISHED（终审发布，终态）`；单签模式（超声/内镜等，按报告分组配置）`DRAFTING → PUBLISHED（终审签发即发布）`；`DRAFTING/FIRST_AUDITED → RETURNED（打回：重写或触发退回重检）`；`PUBLISHED → SUPERSEDED（被更正版本取代，原版本只读留存）`；`PUBLISHED → RETRACTED（撤销作废：重大错误，独立权限+医务确认，终态）`。版本链经 version_no/correction_of 表达，永不删除。

主流程时序：

1. **住院检查全链路**：M04 发布 `inpatient.order.audited`（exam 子键）→ 本模块受理建 exam_apply 并生成 exam（WAIT_SCHEDULED），发布 `imaging.apply.accepted`（受理回执，M04 闭环追溯聚合）→ 预约排程（占用 exam_slot，告知准备事项，经 M01 通知中心提醒）SCHEDULED，发布 `imaging.exam.scheduled` → 患者报到登记（身份核对经 M02 解析、准备确认）REGISTERED，签发 accession_number 并进入 Worklist 应答集，发布 `imaging.exam.registered`（**M13 计费触发**）→ 候检队列叫号 → 检查执行（MPPS N-CREATE 或技师开始确认 → PERFORMING）→ 执行完成（MPPS N-SET COMPLETED 或技师结束确认）→ 设备 C-STORE 影像到达，按 accession 匹配、对象存储持久化、索引入库（IMAGES_RECEIVED，发布 `imaging.exam.completed` 完成回执与 `imaging.study.received` 归档事件；存储确认 SCP 回告设备承诺）→ 阅片书写报告（REPORTING；发现危急征象即触发 M07 危急值，见流程 5）→ 初审（FIRST_AUDITED）→ 终审发布（PUBLISHED，发布 `imaging.report.published`：M03/M04 完成回执、M09 病历引用、M01 通知患者、自助打印放行）。
2. **门诊检查链路**：M03 开单→患者缴费→M03 扇出 `outpatient.order.charged`→本模块受理（同链路 1 后续步骤）；急诊绿通挂账放行同样产生 charged 事件，无例外通道；急诊检查跳过预约直接登记，候检队列置顶。
3. **退回重检链路**：初审/终审发现影像不合格（伪影/体位/序列缺失）→ 报告 RETURNED、exam 回退 REGISTERED（原因留痕，发布 `imaging.exam.returned`）→ 重新排程或直接重检 → 新影像追加至同检查单（新 study 或原 study 新系列）→ 重新书写与审核 → 发布。
4. **作废与退检链路**：消费 `inpatient.order.stopped/cancelled/revoked`（exam 子键）与 `outpatient.order.cancelled` → 未登记 exam 作废（CANCELLED，发布 `imaging.exam.cancelled`）；已登记未检查退检需独立权限确认（CANCELLED+原因留痕）；`billing.refund.approved` 回流确认退费闭环；检查中及之后（PERFORMING 起）M13 退费前置校验调本模块执行状态 API 硬拦截（红线 5）。
5. **检查危急值链路**：报告医师阅片发现危急征象 → 登记触发记录 → 调 M07 `POST /api/v1/lab/critical-values`（source_domain=EXAM，携带征象字典快照、影像引用、患者/检查上下文）→ M07 引擎闭环（影像复核确认→通知→接收→处理→升级→关闭）→ 本模块订阅 `lab.critical-value.notified/accepted/closed` 同步展示状态（检查科工作台可见），报告发布时携带危急值标记。
6. **报告更正链路**：已发布报告发现错误→有权限者发起更正（必填原因）→新版本重走审核→新版本 PUBLISHED、原版本 SUPERSEDED→发布 `imaging.report.corrected`（M03/M04/M09 刷新展示，临床端显式提示"已更正"）。

## 6. 功能实现设计（逐 FU）

| FU | 实现设计要点 |
| --- | --- |
| FU-M08-01 检查申请与预约（P1） | 受理双通道：消费 `inpatient.order.audited`（exam 子键，住院）与 `outpatient.order.charged`（门诊/急诊，收费放行后受理）建申请单与检查单，发布 `imaging.apply.accepted`；检查项目字典（模态类型×部位×时长×准备要求）与设备档案（能力矩阵、开放时段）；检查号源池按设备×时段独立建模（方案 3.4），容量按检查类型时长权重折算，急诊保留时段、设备维护整池冻结；预约渠道：检查台/病区护士站/医生站/自助机，线上经 M18 复用为 P2 预留；改期（退旧约新）、取消（号源回池）、爽约记录（参数化限约）；准备事项告知（空腹/憋尿/造影剂知情）经 M01 通知中心多通道触达；急诊检查跳过预约直接登记、候检置顶（调研依据 2、12）；候检队列为设备级待检列表按优先级排序（急诊/绿通/预约时段），技师工作台叫号，候检大屏/语音为 P2 预留（对齐 M03 叫号模式，本期简化） |
| FU-M08-02 检查登记与 Worklist（P1） | 报到登记：核对患者身份（M02 解析服务）与准备事项完成情况→签发 accession_number（规则化段结构全院唯一）→exam 转 REGISTERED、发布 `imaging.exam.registered`（M13 计费触发）；Modality Worklist：内嵌 DICOM 接入组件提供 MWL C-FIND SCP，登记后的检查实时进入应答集（按设备 AE Title/模态过滤，应答含患者基本信息（PatientID 映射 patient_id）、accession、检查项目与部位、预约时段），未登记不进 Worklist，停嘱/作废/退检即时移除（方案 3.2）；DICOM 通讯节点配置管理（dicom_node：AE/地址/端口/支持服务/白名单）；设备连通性测试（C-ECHO）内建 |
| FU-M08-03 影像接收与归档（P1） | C-STORE SCP 多设备并发接收（内嵌 DICOM 接入组件，dcm4che 为协议底座，方案 3.1）：接收→校验（传输语法/字符集白名单）→按 accession+StudyInstanceUID 匹配检查单→对象存储持久化成功→三级索引（study/series/instance）入库并置可见（先写后索引）；重复影像以 sop_instance_uid 唯一约束幂等去重；匹配失败进 unmatched_image 待处理清单并告警，人工挂接强制双人确认+全量审计（红线 1）；接收完整性判定（幅数达到 Worklist 约定或技师确认），超时不完整转异常清单（`fy.delay` 驱动提醒）支持设备补传；存储确认（Storage Commitment）：校验对象持久化与索引落库后回告承诺清单，失败对象列入未承诺清单由设备保留重发；分层存储与生命周期按方案 3.3（在线 90 天→近线 2 年→归档 ≥15 年，参数化；归档只读、调阅自动回迁）；影像与对象存储每日对账（孤儿对象/缺影清单） |
| FU-M08-04 影像浏览（P1） | 诊断工作站阅片：窗宽窗位预设与调节、测量（长度/角度/CT 值）、放大/翻转/旋转、序列同步对比、同患者历史检查对比、关键图标记；调阅链路：索引库查询→对象存储读取（在线池直读，归档触发自动回迁），缩略图墙加速定位；临床科室轻量浏览：Web 零插件只读视图（基础窗宽窗位预设/缩放/翻页），对齐"临床统一浏览阅片服务"招标硬性要求（调研依据 14）；权限：诊断工作站（书写/审核角色，按报告分组与科室数据范围）、临床只读（诊疗关系内患者）、影像调阅本身为敏感操作全量留痕（对齐 M01 审计与 M02 敏感查阅思路）；检查报告与影像一体调阅（阅片界面右侧呈现历史报告） |
| FU-M08-05 结构化报告（P1） | 报告模板：报告分组×模态类型×项目/部位，模板层级公共/科室/个人三级树形（调研依据 9），结构项树覆盖检查技术/影像表现/影像诊断/建议四段（对齐省级报告格式规范要素）+关键图像占位；书写：结构项勾选填录+自由文本混排、关键图像从阅片界面挂接、同患者历史报告对比引用、阳/阴性征象标注；审核按方案 3.5 差异化：双签组（书写→初审→终审，初审可打回重写、终审可打回重检）、单签组（终审签发即发布），审核模式按报告分组参数化；审核后锁定，更正走版本链（FU-M08-07 链路）；报告双签可选配 CA 电子签名（M01）；报告 PDF 经对象存储归档 |
| FU-M08-06 检查危急值闭环（P1） | 触发（方案 3.6）：报告医师阅片发现危急征象→登记 critical_finding→调 M07 `POST /api/v1/lab/critical-values` 登记实例（source_domain=EXAM，携带 critical_finding_dict 征象快照、影像引用、患者/检查上下文），M07 复检确认环节对检查域=上级医师影像复核；检查危急征象字典由本模块维护（版本化、医务年度审定）；展示：检查科工作台订阅 `lab.critical-value.notified/accepted/closed` 同步实例状态，危急值未关闭的报告在审核发布界面醒目提示；闭环权威在 M07（红线 4），本模块不自建通知/接收/处理/升级；发布后的报告危急值标记随 `imaging.report.published` 载荷携带供临床端提醒 |
| FU-M08-07 报告发布（P1） | 发布前置=终审通过（或单签组签发）+影像完整性确认（COMPLETE）；发布动作：报告置 PUBLISHED、生成 PDF 归档对象存储、发布 `imaging.report.published`（M03 自助打印放行、M04 完成回执、M09 病历引用、M01 通知患者）；报告更正：权限+必填原因→新版本重走审核→原版本 SUPERSEDED→`imaging.report.corrected`；报告撤销（重大错误）需独立权限+医务双确认、原版本 RETRACTED 留痕；患者端查询：仅已发布报告、按 M02 脱敏规则输出，经 M03 自助机/公众号与 M18 渠道取数；胶片打印：报告与胶片标签打印走 M01 打印模板并留打印记录（report_print）；云胶片为 P2 预留接口位（调研依据 11）：预留"审核后影像+报告加密打包、二维码/链接调阅"的服务位与 DICOMweb 出口，业务落地需物价与医保政策配套（江苏 2024-07 数字影像解绑收费实践），本期不实现收费 |
| FU-M08-08 检查计费联动（P1） | 计费衔接双时点（对齐 M13 计价规则 trigger_type=registered 与 M07"签收核收计费"先例）：`imaging.exam.registered`（检查登记）→M13 生成检查费并确认入账（住院侧）或对已预缴费用执行确认对账（门诊侧，载荷含 visit_type 区分）；`imaging.exam.completed`（检查完成/影像接收完整）→M13 执行占用标记与费用执行确认对账（不重复生成费用行，计费唯一键由 M13 兜底）；退费控制矩阵：未登记→随停嘱/退单作废后正常退费；已登记未检查（REGISTERED）→独立权限确认退检（CANCELLED+原因+留痕）后 M13 退费；检查中及之后（PERFORMING 起）→不可退，M13 退费前置校验调本模块 `GET /execution-status` 硬拦截（检查中医学终止转 MEDICAL_ABORT 态时该 API 返回"已执行"——费用照收不可退，费用争议走协商/挂账处理，由 M13 承载）；`billing.refund.approved` 回流作废/退检完成确认；本模块零金额字段（红线 5） |
| FU-M08-09 高级后处理（P2，集成位） | 按 D6 不自研三维重建/MPR 等后处理算法；本期仅冻结集成位契约并建模 postprocess_task（方案 3.1）：① 影像取阅出口（对象存储签名 URL/代理流，预留 DICOMweb QIDO-RS/WADO-RS 兼容出口）；② 后处理任务下发与回执回调契约（检查/序列级任务号+影像引用+算法类型）；③ 后处理结果索引登记契约（外部组件产出挂回 imaging_study 索引并标记来源）；④ 外部组件作为独立子系统经 M20 通道注册（接口账号+白名单+调用留痕），若引入 DCM4CHEE 完整归档仅承担存储/转发角色，索引与业务状态权威仍在本模块并每日对账。本期不实现具体对接与算法 |

## 7. 对外接口

**REST（`/api/v1/imaging/` 前缀，节选）**：
- 字典与设备：`GET/POST/PUT /exam-items`、`GET/POST/PUT /modalities`、`POST /modalities/{id}/maintain|resume`（维护/停用）、`GET/POST/PUT /dicom-nodes`、`POST /dicom-nodes/{id}/echo-test`（连通性测试）
- 号源与预约：`GET /slots?modalityId=&date=`（号源查询）、`POST /appointments`（预约/改期入口）、`POST /appointments/{no}/cancel`、`POST /appointments/{no}/reschedule`
- 登记与执行：`GET /registrations/worklist?modalityId=`（待检队列/报到列表）、`POST /exams/{no}/register`（报到登记，签发 accession）、`POST /exams/{no}/perform-start|perform-end`（人工开始/结束确认，MPPS 设备可省略）、`POST /exams/{no}/discontinue`（检查中断登记）、`POST /exams/{no}/cancel`（作废/退检，权限控制）
- 影像：`GET /studies?patientId=&accessionNo=&visitId=`、`GET /studies/{studyUid}/series`、`GET /studies/{studyUid}/instances?seriesNo=`、`GET /images/object?imageId=`（影像对象读取，签名时效）、`GET /images/thumbnail?imageId=`、`GET /dicom/unmatched`、`POST /dicom/unmatched/{id}/bind`（匹配失败人工挂接，双人确认）
- 报告：`GET /reports/{no}`、`GET /reports?patientId=&visitId=&status=`（临床/全景/自助打印取数）、`POST /reports`（书写提交）、`POST /reports/{id}/first-audit|final-audit|return`（初审/终审/打回）、`POST /reports/{no}/correct`（更正）、`POST /reports/{id}/retract`（撤销）、`GET /reports/{no}/file`（PDF）、`POST /reports/{id}/print`（报告/胶片标签打印，留打印记录）、`GET/POST/PUT /report-templates`
- 危急值：`GET /critical-findings?status=&groupId=`（触发记录与展示状态）、`GET/POST/PUT /critical-finding-dicts`（征象字典，医务审定）
- 对 M13：`GET /execution-status?sourceRef=`（退费前置校验：返回检查单状态与影像接收状态）
- 对 M15/M19：`GET /stats/workload`、`GET /stats/modality-utilization`、`GET /stats/tat`、`GET /stats/critical-value-indicators`（只读统计）
- 患者端取数：`GET /patient/reports`（经 M03/M18 渠道调用，仅已发布报告，脱敏输出）

**MQ 事件（发布，经 `fy.topic`，信封遵循 M20 治理约定，全部登记 event_registry）**：
- `imaging.apply.accepted`（检查受理：申请单与检查单已生成，M04 受理回执聚合依据）
- `imaging.exam.scheduled`（已预约，候检/检查进度展示与患者检查前提醒依据；订阅方按需在 event_registry 登记）
- `imaging.exam.registered`（已登记/已报到，**M13 计费触发**：费用生成+确认，载荷含 visit_type 区分门诊确认对账/住院生成入账）
- `imaging.exam.completed`（检查完成：影像接收完整+完整性判定通过，**M13 执行占用标记**与费用执行确认对账依据、M04 执行完成回执）
- `imaging.exam.cancelled`（作废/退检，M13 费用作废/退费联动与 M03/M04 状态聚合依据）
- `imaging.exam.returned`（退回重检，临床端提示与检查进度展示依据；订阅方按需在 event_registry 登记）
- `imaging.study.received`（影像归档完成：StudyInstanceUID/幅数/大小；广播/模块内归档对账依据——M09 患者全景由 `imaging.report.published` 覆盖、M15/M19 数据量统计经统计 API 拉取，不依赖本事件；重检追加影像时按 study+series 幂等再发布）
- `imaging.report.published`（报告发布，M03 自助打印放行、M04 完成回执、M09 病历引用、M01 患者通知依据，载荷含危急值标记）
- `imaging.report.corrected`（报告更正发布，订阅方刷新与临床"已更正"提示依据）

**MQ 事件（订阅，全部经 M20 幂等构件消费）**：
- `inpatient.order.audited`（routing key `inpatient.order.audited.exam`，M04 住院检查医嘱分发→受理）
- `inpatient.order.stopped` / `inpatient.order.cancelled` / `inpatient.order.revoked`（exam 子键，作废未登记检查单；已登记转人工退检提示）
- `outpatient.order.charged`（M03 门诊/急诊检查收费放行→受理）
- `outpatient.order.cancelled`（门诊退费逆向→作废联动）
- `billing.refund.approved`（M13 退费审批通过→作废/退检完成确认）
- `lab.critical-value.notified` / `lab.critical-value.accepted` / `lab.critical-value.closed`（M07 危急值闭环事件，EXAM 域触发记录展示状态同步；闭环本身由 M07 驱动）
- `patient.merged` / `patient.split` / `patient.frozen` / `patient.unfrozen`（与 patient.frozen 成对订阅——冻结解除、解除拦截）/ `patient.identifier.changed`（M02：患者归一缓存刷新、冻结拦截、标识变更；merged 与 split 成对订阅——split 驱动拆分逆映射刷新）
- `system.dict.published` / `system.org.changed` / `system.user.changed` / `system.param.changed`（M01 主数据广播：部位/检查类别等字典、组织、人员、参数缓存刷新）

**WebSocket**：`/ws/imaging/group/{groupId}`（检查科工作台实时主题：新影像到达、待检队列变更、危急值待复核提醒、影像匹配失败告警、报告打回/退检提醒，刷新 ≤2s）。临床端报告到达提醒由 M03/M04 订阅本模块事件实现，本模块不自建临床主题（对齐 M07 分工）。

**延迟队列（`fy.delay`）**：`delay.exam-notify`（检查前准备提醒）、`delay.image-incomplete`（影像接收不完整超时提醒）、`delay.report-tat`（报告时限超时提醒：影像已接收至报告发布时限，急诊/常规分档参数化，超时提醒组长/科主任，对齐影像质控实践）。

## 8. 集成点

- **M01**：认证与 RBAC（预约/登记/执行确认/退检/影像挂接/报告书写/初审/终审/更正/撤销/模板维护/征象字典维护/设备管理独立权限点）；字典引用（部位/检查类别引用 M01 字典 code，检查项目/设备/征象为模块专业字典，对齐 M14/M04/M07 先例）；通知中心（预约与检查前提醒、报告发布通知、危急值经 M07 引擎触达）；打印模板（申请单/报告/胶片标签）；CA 电子签名（报告双签可选配）；审计切面；主数据广播订阅刷新。
- **M02**：一切业务以 `patient_id` 关联，登记前经解析服务核验（FROZEN/MERGED 拒绝）；患者合并事件驱动影像与报告归属映射刷新；报告与影像推送按 M02 脱敏规则输出；患者端仅可见本人已发布报告。
- **M03（门诊放行与报告回执契约）**：消费 `outpatient.order.charged` 受理（检查仅在收费放行后受理，急诊绿通挂账放行同样产生 charged，无例外通道）；`imaging.report.published` 回流驱动其报告到达提醒与自助打印放行（事件名与 M03 Spec 订阅声明一致）；退费占用校验数据由本模块执行状态查询 API 提供（经 M13 前置调用）。
- **M04（医嘱分发契约）**：消费 `inpatient.order.audited`（exam 子键）受理；`imaging.apply.accepted`（受理回执）、`imaging.exam.completed`（执行完成回执）、`imaging.report.published`（报告完成回执）回流其医嘱闭环追溯；`inpatient.order.stopped/cancelled/revoked`（exam 子键）双向联动作废与停嘱；医嘱状态感知一律以 M04 事件与查询接口为准。
- **M07（危急值引擎共用边界，单向依赖）**：按总 Spec FU-M08-06 与 M07 Spec 集成点第 8 节，危急值闭环引擎（实例状态机/通知/接收/处理/升级/统计）由 M07 提供；本模块经 `POST /api/v1/lab/critical-values` 登记检查域实例（source_domain=EXAM），检查域危急征象字典由本模块维护并经实例快照带入；订阅其闭环事件仅做本科室展示；本模块不依赖 M07 任何其他接口，M07 不依赖本模块。
- **M13（计费契约，双向对齐其 Spec 第 7/8 节）**：M13 订阅声明为"M08 检查登记事件"，对应本模块 `imaging.exam.registered`（检查登记→费用生成/确认）与 `imaging.exam.completed`（检查完成→执行占用标记与执行确认对账，不重复生成费用行），语义与载荷在 FU-M08-08 显式定义（双时点细化同 M07 先例，统一审查时对照 event_registry）；退费硬拦截：M13 退费前置校验调 `GET /execution-status`，检查中及之后不可退；`billing.refund.approved` 回流作废/退检确认；本模块零金额字段，计价权威在 M13。
- **M09**：检查报告与影像为其患者全景/病历引用数据源（`GET /reports`、报告 PDF、影像调阅入口）；`imaging.report.published/corrected` 供其订阅刷新病历视图；报告作为病历文书的引用在 M09 组织，本模块不写病历。
- **M15**：设备资产台账/计量/维修归 M15，本模块设备业务档案仅存资产引用；本模块提供检查工作量/设备利用率统计 API 供其效益分析（FU-M15-05）；设备维护停机在本模块人工登记，暂不订阅 M15 维修工单事件（预留）。
- **M17（P2 预留）**：体检检查复用本模块项目字典、预约与报告链路（总 Spec FU-M17-02），预留申请来源类型适配位，本期不实现。
- **M18（P2 预留）**：患者端报告查询/云胶片经 M18 渠道复用本模块取数 API，业务落本模块，渠道标记为线上。
- **M19**：检查量、TAT 达标率、危急值指标、设备利用率统计取数 API（只读）。
- **M20（事件治理与延迟队列）**：事件信封、outbox、幂等消费、死信、延迟队列（delay.exam-notify/delay.image-incomplete/delay.report-tat）全部复用 M20 治理约定；本模块全部事件先登记 event_registry。**DICOM 通讯不经 M20**：M20 集成引擎通道协议枚举为 HL7/WebService/REST/FILE，不含 DICOM；DICOM 网络服务（C-STORE SCP/MWL SCP/MPPS/存储确认）为本模块内建 DICOM 接入组件（协议底座 dcm4che），DICOM 通讯日志在本模块留痕，通讯异常监控告警对齐 M20 治理理念；若后续外部后处理组件/DCM4CHEE 接入，其 REST/DICOMweb 调用经 M20 通道注册治理。
- **M14**：无直接依赖——影像设备不接入 IoTDA 遥测链路（总 Spec 5.1 仅心电图机文件导出归 M08 检查报告域）；设备利用率如需 IoT 开机率口径由 M15 从 M14 取数，与本模块业务工作量口径并存。

## 9. 非功能与安全

- 性能：C-STORE 接收按全院影像设备并发外推设计（单实例并发关联 ≥100、影像落盘与索引批量处理，接收服务无状态可多实例、多 AE/端口分流）；MWL 应答 P95 < 500ms；诊断阅片在线池首图显示 P95 < 2s、归档回迁 P95 < 30s（异步提示等待）；临床轻量浏览首屏 P95 < 3s；报告查询 P95 < 1s；报告发布事件端到端 ≤ 3s；本模块 MQ 事件量占用 M20 集成引擎共用通道总容量 200 msg/s 的合并估算份额（不叠加单列），DICOM 影像流不经 M20 通道（见第 8 节）。
- 容量：影像年增量约 22TB（压缩后估算，参数化，见方案 3.3）；在线 90 天/近线 2 年/归档 ≥15 年；实例索引按年分区；缩略图与索引缓存命中率 > 95%。
- 可用性：影像接收 7×24（设备侧普遍支持本地缓存与失败重发，接收中断恢复后补传不丢片）；对象存储纠删码多副本；接收服务多实例；M13 不可用时计费事件积压重放（outbox），检查业务不中断；M07 不可用时危急值触发记录转本地待补登队列，API 恢复后补登（时限敏感，告警人工兜底电话通报）。
- 一致性：影像写入"对象存储持久化成功后才置索引可见"；sop_instance_uid/study_instance_uid 唯一约束幂等去重；study 索引与对象存储每日对账（孤儿对象/缺影清单）；MPPS/人工/影像到达三路状态每日对账，差异进异常清单；检查单状态迁移与事件发布走 outbox。
- 审计：影像调阅（谁在何时查看谁的影像，敏感操作独立留痕）、报告书写/初审/终审/发布/更正/撤销、匹配失败人工挂接（双人）、退检、设备与节点配置变更全量审计（M01 审计切面）。
- 权限：技师（登记/执行确认）、书写（按报告分组与科室数据范围）、初审/终审（上级医师资质角色，终审与初审不得同人）、临床只读浏览（诊疗关系内）、患者端（仅本人已发布报告）、匹配挂接与退检（独立权限+双人/审批）分权；跨科室数据按 M01 数据范围隔离。
- 合规映射：DICOM 3.0（总 Spec 第 7 章硬性标准：影像接收 C-STORE、Worklist C-FIND）；电子病历分级评价检查闭环（申请→执行→报告全程可追溯）；诊断报告审核制度与影像质控（双签/打回/质控指标，调研依据 2、9、32）；危急值报告制度（闭环归 M07，本模块触发留痕）；等保三级（审计/TLS/患者敏感字段脱敏——报告与事件载荷禁带完整证件号等明文）；影像资料随病历留存 ≥15 年（可按属地规定参数化）。
- 时间：登记/执行/接收/审核/发布等业务时间一律取服务器时间；DICOM 头内设备时间仅作参考值（对齐全局"禁止前端/外部传入业务时间"约定）。

## 10. 测试要点

- 正常：住院全链路（audited(exam)→受理→预约→登记签发 accession→MWL 查询应答正确→MPPS 开始/结束→C-STORE 接收匹配入库→完整性判定→报告书写→初审→终审发布→各事件逐一核对）；门诊链路（charged→受理→…→发布→自助打印放行）；双签与单签两种审核模式各自闭环；危急值链路（阅片发现→M07 实例登记→影像复核→通知闭环→本模块展示同步→报告携带危急值标记）；退回重检全链路（打回→重检→追加影像→重新报告→发布）；存储确认承诺应答与设备清理本地；报告更正版本链与"已更正"提示。
- 边界：初审提交与终审提交并发（先到先得互斥）；影像接收中断（部分系列）→INCOMPLETE 异常清单→设备补传→完整性判定转 COMPLETE；重检追加系列挂同检查单、study/系列索引正确；已登记未检查退检（CANCELLED，可退费）与检查中医学终止（PERFORMING → MEDICAL_ABORT，执行状态返回"已执行"、费用照收不可退）两条路径互斥；急诊检查跳过预约直接登记且候检置顶；号源并发预约不超约（条件更新+唯一约束）；预约超时未报到转 NO_SHOW、累计触发参数化限约；设备维护期 MWL 不应答该设备且已约患者收到改期通知；单签组报告不出现初审环节；报告时限超时触发 delay 提醒；打印记录含补打计数、已发布报告方可打印。
- 异常：C-STORE 影像匹配失败进待处理清单并告警（不静默丢弃），人工挂接双人确认后归档正确；重复 SOPInstanceUID 仅入库一次（幂等）；登记后停嘱→该条目即时从 Worklist 移除；M07 不可用时危急值触发记录进待补登队列、恢复后补登成功且不重复登记；`imaging.exam.registered` 重复投递仅计费一次（幂等）；M13 不可用时计费事件积压、恢复后补投不重不漏；对象存储不可用时接收暂停并告警、设备侧重发不丢片；归档影像调阅自动回迁成功；接收服务重启后未完成会话按设备重发幂等处理。
- 安全：无权限角色调阅影像 403 且留审计；临床账号仅只读浏览、无测量/导出权限（按角色）；报告更正/撤销越权 403；患者端越权查询他人报告被拒；影像对象读取签名 URL 时效过期失效；日志与事件载荷患者敏感字段脱敏抽验；DICOM 节点白名单外来源拒连并告警。

## 11. 自审记录

- [x] 无 TBD/TODO/占位符，13 项内容完整（文档头 + 12 节）
- [x] 覆盖 FU-M08-01~09 全部条目，无遗漏、无私增（FU-M08-09 按 P2 集成位定位细化，仅冻结契约不实现；候检大屏/云胶片/线上预约为对应 FU 的 P2 预留细化，非新功能点）
- [x] 内部一致：领域模型 ↔ 状态机 ↔ API ↔ 测试一一对应（exam/exam_appointment/exam_report/imaging_study 四个状态机均有对应接口、流程与测试项；exam_apply/exam_registration/exam_perform/unmatched_image 均有操作路径与测试场景；report_print 有打印接口与留痕测试；postprocess_task 为 P2 预留建模，本期无操作路径与测试，与 FU-M08-09 集成位定位一致）
- [x] 符合跨模块约定：schema=imaging；主键 BIGINT 雪花；本模块零金额字段（计费权威在 M13）；事件命名 `<模块>.<实体>.<动作>`、信封 eventId/occurredAt/producer、消费走 integration.received_event 幂等；REST 路径 `/api/v1/imaging/`；字典只存 M01 code 引用（模块专业字典对齐 M14/M04/M07 先例）；状态字段 VARCHAR 常量+迁移日志；患者关联 patient_id+visit_id；无跨模块读表
- [x] 依赖方向正确：依赖 M01/M02/M03/M04/M07/M13/M20 对外接口与事件，其中 M07 为单向 API 调用（危急值引擎共用，总 Spec FU-M08-06 与 M07 Spec 集成点一致）；无反向依赖、无跨模块读表；被依赖清单明确
- [x] 方案推导 6 个关键点均有备选对比与依据，含任务要求的三个必选点（3.1 影像归档方案与自研边界、3.2 Worklist 来源与完成确认机制、3.3 存储与业务库分离架构），每个结论附调研来源
- [x] 无代码级实现（无类名/方法体/SQL DDL 全文；表设计为"表-关键字段-约束"粒度；DICOM 服务以文字描述，dcm4che 为库名非代码）
- [x] 歧义消除：Worklist 应答集边界（登记后才应答、停嘱即时移除）、检查完成三路确认对账（MPPS/人工/影像完整性）、影像先写后索引语义、审核模式差异化配置（双签/单签按报告分组）、危急值触发与闭环职责边界（触发展示归本模块、闭环归 M07）、计费双时点语义（registered 计费/completed 执行占用）、已开始检查不可退的拦截主体（M13 前置调用本模块 API）、DICOM 不经 M20 的原因与替代治理方式，均已显式定义
- [x] 术语与总 Spec 一致（检查申请/预约/登记/报到/检查执行/影像接收/归档/阅片/结构化报告/初审/终审/发布/更正/危急值/云胶片/高级后处理）

## 12. 与总 Spec 的偏差

无结构性偏差。六处细化澄清（请统一审查裁决确认）：
1. **FU-M08-02 登记语义细化**："登记确认"细化为"报到登记签发 accession_number 并进入 MWL 应答集"，Worklist 下发范围=已登记检查（未预约/未登记不出现在设备端），依据是设备端只能执行已确认检查、防止绕过登记（方案 3.2）。
2. **FU-M08-05 双签模式细化**："初审/终审双签"细化为按报告分组差异化配置——放射/CT/MR 双签，超声/内镜单签（终审签发即发布），依据生产实践"超声/内镜边检边写通常无独立审核环节"（调研依据 2、13），双签为默认可配置项而非全院一刀切。
3. **FU-M08-06 危急值共用形态**：与 M07 Spec 集成点第 8 节对齐——闭环引擎归 M07，本模块经 `POST /api/v1/lab/critical-values` 登记 EXAM 域实例（source_domain=EXAM）并订阅其事件做展示，检查域危急征象字典由本模块维护；依赖方向 M08→M07 单向 API 调用。
4. **FU-M08-08 计费时点细化**："登记计费、完成确认"细化为双时点——`imaging.exam.registered`（检查登记）为费用生成+确认时点、`imaging.exam.completed`（检查完成）为执行占用标记与执行确认对账时点；M13 Spec 订阅声明"M08 检查登记事件"，事件名对齐、细化内容在统一审查时对照 event_registry（同 M07 Spec 偏差 1 的处理先例）。
5. **DICOM 通讯不经 M20 通道**：M20 集成引擎协议枚举（HL7/WebService/REST/FILE）不含 DICOM，DICOM 网络服务（C-STORE/MWL/MPPS/存储确认）为本模块内建 DICOM 接入组件（dcm4che 协议底座），依据总 Spec 决策 D6"PACS 主干自建"；外部后处理组件接入时其 REST/DICOMweb 调用仍经 M20 治理。**闭环标注（v1.1）**：已在 M20 v1.1 登记 DICOM 例外口径（DICOM 网络服务由 M08 内建不经通道、DICOMweb 外呼经 REST 通道治理，M-15 裁决），本偏差闭环。
6. **云胶片定位**：FU-M08-07"胶片打印/云胶片（预留）"细化为——胶片打印本期实现（打印模板+留痕），云胶片为 P2 预留接口位（服务位+DICOMweb 出口冻结，不实现收费业务），依据是数字影像收费需物价与医保政策配套（江苏 2024-07 解绑收费实践，调研依据 11）。

**v1.1 统一审查修订记录**（Round 1 裁决落地，条目号对应《90-cross-review》）：
- M-12（R4-03）：exam 状态机 `PERFORMING → CANCELLED（检查中断且终止）` 改为 `PERFORMING → MEDICAL_ABORT（医学终止：费用照收，走协商/挂账处理）`，与"未开始作废 CANCELLED（可退费）"区分；§6 FU-M08-08 退费矩阵同步（MEDICAL_ABORT 态执行状态 API 返回"已执行"、不可退）；删除"M13 联动（退费）"歧义表述；§10 补医学终止边界用例；§4 exam 实体原因字段同步覆盖医学终止。
- R4-18/R6-10：`imaging.study.received` 消费方描述修正——删除"M09 调阅入口刷新"表述（M09 全景由 `imaging.report.published` 覆盖），M15/M19 数据量统计走统计 API 拉取。
- R4-14：§9 容量口径与 M20 合并估算对齐（MQ 事件量占用共用通道总容量 200 msg/s 份额，不叠加单列）。
- R4-15：文档头下游被依赖已核对——M17（体检检查受理复用，P2 预留）预留位已注明，无需增补。
- M-25：订阅补 `patient.split`（与 patient.merged 成对登记）。
- M-25（收尾补登）：订阅 `patient.frozen` 处成对补订 `patient.unfrozen`（冻结解除、解除拦截）。
- 偏差 5（DICOM 不经 M20）：已在 M20 v1.1 登记 DICOM 例外口径（M-15 裁决），本偏差闭环。
