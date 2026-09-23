# CHANGELOG（工程变更记录）

> 记录规则（根 AGENTS.md §7）：**先记再改**——任何宪法 / 规范 / 机制文件的修订，先在本文件登记（日期、范围、理由、裁决），再改正文。追加式保留全部历史。

## 2026-09-24 · P1 PR-6 M05 修复环 R1（后端四项 Important）

- 五视角审查 R1 后端四项修复：①`com.fuyun.nursing.api` 包补 `package-info.java`
  `@NamedInterface("api")` 声明（逐字对齐 outpatient/patient/system 形态，宪法 B.1 对外契约出口；
  spring-modulith-api 依赖自此有消费点，P2 消费方引用不再被 Modulith 边界拦截）；
  ②巡视打卡日志改 `identifierTail` 尾四位摘要口径（对齐 PdaServiceImpl，扫码标识明文禁入日志）；
  ③patrol `source_ref` 按标识形态分流——I 型腕带就诊编码（`VisitIdValidator` 冻结结构，
  visitId 形态非敏感）原值留痕，证件号/就诊卡号形态落尾四位掩码值（V805 列注释口径 +
  等保「敏感字段脱敏落库」红线）；④评估复评降级（非高危）同事务移除对应床旁风险标识
  （`IWardMetaService#removeRiskFlag` 新增；映射与高危追加同源 `NursingScaleConstants#riskFlagOf`，
  幂等零写兜底）——床旁风险标识权威 = 最新评估判级，防 FALL/PRESSURE 降级后永久残留误导临床。
- 测试：NursingTaskServiceImplTest 补 patrol 留痕三形态分流、WardMetaServiceImplTest 补移除
  （保序回写/清空落空串/幂等零写/NS-1001）、NursingAssessmentServiceImplTest 补升→降全链路
  （高危追加 PRESSURE → 复评 MEDIUM 清标识，防范任务零新增）；冻结用例断言零触碰。
  Spec 同步：05-nursing.md §13 追加第 16 条注记。

## 2026-09-24 · P1 PR-6 M05 护理基础收口

- 交付面：V800–V807 八迁移（CF-6 事件登记 24 行 id 41–64；病区元数据/护理文书/体征/出入量/护理任务/
  评估/交接班七域）+ nursing 模块服务面（`com.fuyun.nursing.service.impl` LINE=1.00，实测
  COVERED=1343/MISSED=0）+ 三条锚点 IT（体征归集链 8 / 文书与交接班链 9 / 患者上下文拦截链 6）+
  workstation 护士站页与 PDA 页 + UI 设计文档（865e535）+ 前端审查修复环 R1（65faa32：S7 重叠红圈
  改坐标同格判定、短绌起止红竖线、PDA 卡号路径判空零出网、交接班摘要「特级/病重」标签归位）。
- 纯新增迁移段 V800–V899（nursing）且 V800>V706 → 免存量卷重置；存量 spec 断言零回退（仅新增
  `WardBoardView.spec.ts` +592 / `PdaView.spec.ts` +208）。
- 合入前全量门禁四步全绿：后端 `-pl fuyun-app -am verify` BUILD SUCCESS（09:51，三锚点 IT、
  `MessagingGovernanceIT` 64 行断言、`ModulithBoundaryTest` 全绿）；BUNDLE LINE 0.9606 ≥ 0.80；
  前端五连 32 文件 150 用例全绿；迁移守卫 51 文件基线 dev 通过。
- Spec 落地注记：`05-nursing.md` 新增 §13「P1 切片落地注记」（FU 界定 / 过渡通道退役三处留痕 /
  五项降级 / CF-6 落点 / 归集量化 / WARD 降级 / Task 12 八项 REST 面适配 / PDA 最小脱敏口径 /
  体温单符号契约类名）；`04-inpatient.md` §7 补 CF-6 id 55 占位行升级义务注记（双侧留痕）。
- TASK.md：W-26、W-29 销项；新登记 W-31（ArchUnit 分层规则缺口）～W-35（「P1」双义词消歧）、
  W-36（任务列表日期过滤与业务号日期段时区口径错位，真机实测发现）、D-22～D-24（计划级张力与
  裁定三项：体征唯一约束 vs 服务器时间、归集并发双行竞态、连续取值域 vs 离散档位）。
- 真栈探针四项通过：迁移计数 8（V800–V807 连续无缺号）/ 事件登记 64 行 / `/v3/api-docs` 200 且含
  `/api/v1/nursing/` 契约面（含 PDA 两端点）/ compose 六服务全 healthy。
- 浏览器真机两页六流程：4 通过 + 2 部分通过（异常体温呈现经核对为体温单 S1 腋温部位符号体系、
  符合设计权威；任务列表时区错位登记 W-36 非阻断），截图存 `.superpowers/gui-test-screenshots/pr6-*`。

## 2026-09-22 · P1 PR-6 M05 护理基础：nursing 号段登记与门禁适配（先记再改）

- **nursing 专属固定百位段 V800–V899 新登记**：M05 为 schema 基线零迁移的新模块，首批迁移占百位段
  V800–V807（患者元数据/护理文书/体征/出入量/护理任务/评估/交接班/事件登记八批）；批次合入后
  nursing 后续迁移一律走 V500+ 通用段（W-12 口径，patient/outpatient 先例）。
  **段位语义（2026-09-22 用户批复条件 1）：V800–V899 为 nursing 专属固定段位、非通用段，仅供 nursing
  模块迁移占用，其他模块不得使用**——防止后人把 V8xx 误读为通用段。
- **免存量卷重置**：首批 V800–V807 高于基线全局最大已应用版本 V706，Flyway outOfOrder=false
  对存量 dev 卷不构成 pending 阻断——本 PR 无需 down -v（与 PR-5 V200<V703 的处置不同）。
- **事件 id 排定**：CF-6 契约冻结载体与 M05 发布面共 24 行，id 41–64（当前最大 40）；status
  一律 ACTIVE；CF-6 冻结行 desc 标注「(CF-6 冻结载体)」，占位行标注「(P1 占位登记，P2 实装)」。
- **JaCoCo 规则二扩名单**：`com.fuyun.nursing.service.impl` 纳入 PACKAGE LINE=1.00（护理文书为
  病历要件、体征落卡状态机与评估判级属核心面）。
- **迁移守卫登记**：`scripts/check-migration-governance.py` `_SEGMENTS` 增 nursing 百位段；
  `docs/migrations/flyway-version-registry.md` 同步登记 V800–V807。

## 2026-09-22 · W-29 后端 PR 开工：门诊契约缝三条补齐 + D-4 schema 坍缩治理

- **范围**：①D-2 `QueueTicketVO` 补 `triageLevel`（数据源 visit.triage_level 权威快照，snapshot 路径零新增
  查询）；②D-3 `ClinicOrderVO` 补 `dispenseStatus`（发药回流镜像纯投影漏带）；③D-9 `TriageAdjustRequest` 补
  `reason`（落既有 triage_record.reason 列，零迁移；LEVEL_ADJUST 服务层必携校验）+ 修复 adjust 留痕错位传参
  （第 8 实参误传 targetQueue → 改传 reason）；④D-4 `@Schema(name=...)` 治 springdoc 同名嵌套 record 注册坍缩
  （`ClinicOrderVO.Item`→`ClinicOrderItem`、`PrescriptionOpenRequest.Item`→`PrescriptionItem`，backend 首例，
  不动类名、不动 Line 侧 6 条载荷）。
- **依据**：调研报告 `.superpowers/w29-recon.md`（需求锚 TASK.md W-29；偏差登记
  docs/specs/modules/03-outpatient.md:226-231，PR 内回填修复状态并订正 :229 归因措辞）。
- **门禁承诺**：后端 `mvn verify`（Spotless + 单测 + JaCoCo 双阈值）与前端五连全绿后方可交付；契约与生成物
  （web/api-docs.json + api.d.ts）同 PR 原子，`DoctorStationView.spec.ts` mock 由 drugId 坍缩形态改回 itemCode
  形态属 D-4 修复本义、非断言放宽。

## 2026-09-22 · D-21 断言现代化：发药签名 confirm 补中文按钮与单号回显 + 根宪法新增「回归红线出口」

- **宪法修订（先记再改）**：根 `AGENTS.md` §7 跨切约定新增「**回归红线出口（测试断言现代化流程）**」条目——既有
  断言冻结的恰为「待改进的实现细节」（而非业务行为）时，禁在功能 PR 内顺手改断言，须走专项断言现代化 PR，四条
  边界：范围（逐次批准、单点单次、不构成泛化先例）/ 严格度（新断言不得低于原断言，须对新契约全量精确匹配）/
  原子性（断言修订+实现变更+回归锚同 PR）/ 留痕（CHANGELOG 本条目）；并载出口边界声明——**出口=既有合规路径，
  硬红线（安全 / 版本 / 编码红线、A.4.1-3 迁移禁改等）不在出口范围**，无合规出口者登记 `TASK.md` 待决策。
  **2026-09-22 用户批复 D-21 选项①并批准
  破例**——判据：本案为实现侧唯一物理路径必触碰断言（ElMessageBox 函数式挂载不继承 ConfigProvider locale，中文
  按钮只能经显式传参），属红线空隙走显式破例；且该断言锁的是文案细节非业务行为，两参→三参为严格度迁移非回归弱化。
- **实现（DispenseWorkbenchView 发药签名 confirm）**：文案单号前置——`发药单 {dispenseNo} 签名后药品出库且不可逆，
  确认发药？`（先单据后动作的防错阅读顺序，§4.4 禁裸确认）；补 `confirmButtonText: '确认发药'` /
  `cancelButtonText: '取消'`（R-3 移交清单三处缺口消尽——PricingSettle/RefundApproval 批次 3 两处 + 本处；质量门
  复核另发现清单外 6 处同类残余，已按台账纪律登记 W-26，不在本 PR 破例范围）。
- **断言现代化（同 PR 原子交付）**：`DispenseWorkbenchView.spec.ts` 冻结断言由两参精确匹配升为**三参全量精确匹配**
  （单号前置新文案 + 中文按钮 options）；回归锚=该断言本体与既有出网序/在途守卫断言（未弱化）。
- **销项**：TASK.md D-21 行删除（闭合即删行）。
- **门禁记录**：前端五连绿（lint 零输出 / format:check 通过 / type-check 三应用 Done / test 30 文件 131 用例全过 /
  build 三应用成功）；**红绿验证**——实现回退至两参版本时新断言必红（`AssertionError: expected … to be called with
  arguments: ['发药单 D1 签名后药品出库且不可逆，确认发药？', …(2)]`），严格度实证后还原。

## 2026-09-22 · W-23/W-24 收尾：V706 prescription_item.status 列注释订正 + 两行销项

- **W-23 订正（V706，pharmacy 域）**：V701 :66 内联注释「returned_quantity/status 由退药链回写」中 status
  半句失实——主代码对 pharmacy.prescription_item.status **零写入点**（W-22⑨ 核实：全量检索 updateById/
  setStatus/注解 SQL/mapper 写路径后，唯一写入为 PrescriptionItemMapper.accumulateReturnedQuantity 的
  returned_quantity 原子累加；发药中明细退场写 pharmacy.dispense_item.item_status，V703:95）；宪法 A.4.1-3
  禁改已应用迁移，故以新迁移 COMMENT ON COLUMN 就地更新权威口径（V606 同款先例），迁移头部自解释列明
  核实结论与承载理由（防后人误信旧注释）。代码侧残留一并收口：mapper javadoc 已随 W-22 fix PR 订正，
  **实体 PrescriptionItem javadoc 两处（类/字段）本次订正**（原句与 V701:66 逐字同源，质量门 F-1 实证）。
- **号段登记**：docs/migrations/flyway-version-registry.md 同 PR 登记 V706（V500+ 通用段续号——全局最大
  V705 的下一号，满足乱序守卫；用户批复硬要求「号段立即登记台账」）。
- **W-24 销项**：DispenseServiceImpl.getByRxNo 取消态排除修复已随 PR-5 Task 11 交付，「行删除待合并后执行」
  触发条件成立——TASK.md W-23/W-24 两行删除（闭合即删行）。
- **门禁记录**：迁移治理守卫 `MIGRATION_BASE_REF=dev` 通过（43 个迁移文件）；后端 `mvn verify` 全 24 模块
  BUILD SUCCESS（Testcontainers 全新库日志实证「Successfully applied 45 migrations … now at version v706」，
  45=43 源文件 + 2 处 PR-1a 期改名遗留 target 构建产物 V6__create_event_publication / V7__create_shedlock，
  守卫脚本 docstring 已注记该产物不在扫描面内）；真栈探针（重建容器对 dev 卷）——flyway_schema_history
  V706 success=t、`col_description` 列注释全文在位。
- **裁决留痕（2026-09-22 用户批复，本 PR 落实 W-23/W-24 两项）**：W-23「批准，立即执行」；W-24「与 W-23
  同 PR 删行」。

## 2026-09-22 · P1 PR-5 M03 门诊主流程收口：outpatient 全链+门诊三前端交付（CF-5 冻结载体实装）

- **交付面**：后端 outpatient 全链——号源池域（V200 三表+V705 三类字典种子：排班模板/放号/停诊/加号）、
  预约挂号域（V201 预约/就诊四表：visit_id 当日键签发+双道闸扣减+支付时限延迟释放+退号四分支+改期链）、
  分诊队列域（V202 两表：报到/二次分诊/调级/跨队列转接+叫号 CAS+过号重排+WS 推送）、医生站域（V203 两表：
  开单/作废/RX_REF 引用登记+接诊/诊毕状态机+开方端口转调）、收费退费联动（手工计费挂号费/
  settlement.completed 放行扇出/refund.approved 终态回滚/fee.created 补账）、V204 事件契约种子
  （id 23/25/31 冻结+id 32–40 登记，event_registry 总行 31→40）、practice/check 真实化（V704
  practice_grant+outpatient/pharmacy 双端接线）、portal 匿名通道（SystemWebConfig 白名单+
  PortalAppointmentController）、WS 自建面（/ws/outpatient 端点+两 topic+帧级鉴权拦截器）、三验收锚点 IT
  （FullFlow/RefundRollback/PoolConcurrency）与 `docs/migrations/flyway-version-registry.md` 建档；
  前端三应用——workstation 门诊三页（挂号收费联动/分诊台/医生站）+存量面全站打磨（Task 16 taste-skill）、
  portal 免登录基座（无 token 注入）+预约出票页、bigscreen /ws/outpatient 叫号页暗色化；**Task 17 存量
  前端基建六批次子 PR（#37–#42 全合并，末端 dev@58e16a5）**——批次 0 设计系统地基/批次 1 布局壳数据化
  菜单/批次 2 患者三页/批次 3 收费三页/批次 4 药房三页/批次 5 bigscreen+portal，经 c0cfb26 合并回主线
  （AppSidebar 冲突按预登记消解：门诊菜单组并入数据驱动 MENU_ITEMS）。
- **门禁记录**：Task 14 全量——后端 `mvn verify` 全 24 模块绿（全反应堆 1369 例零失败；
  outpatient/pharmacy/billing service.impl 三包 JaCoCo 实测 LINE=1.00 不回退；Modulith
  `ApplicationModules.verify()` 过）；前端五连绿（30 文件 129 用例）+ api.d.ts 新鲜度
  `git diff --exit-code` 输出空；门禁期修复 1 提交（1d7746c OutpatientRefundRollbackIT 时段型缺陷——
  effectiveFrom 改取 UTC 当日零点）；Task 17 六批次各自五连绿+22 既有 spec 断言 diff=0+六 checks 绿；
  Task 16 打磨五连绿+spec diff=0 复核。
- **裁决落实（recon 14 条对照）**：0 范围界定/演示终点直线段声明（文档头 not-in-scope+03 Spec 注记⑦）；
  1 号段 V200–V299（practice_grant 改道 V704=偏差②）；2 JaCoCo 核心包 LINE=1.00（outpatient impl 入
  名单）；3 id 23/25/31 冻结（V204 UPDATE+兜底 INSERT 双形态=偏差①）；4 放行链回切（rxNos 载荷携带案，
  Task 11）；5 confirmRefundTerminal 收口（SettlementQueryPort 反查，CF-4 载荷零变更）；6 refund 映射
  M03 自查（refundableLines visit 锚，Task 10 契约缝定案）；7 退号退费统一免审档（OutpatientBillingPort
  DAY_CORRECTION）；8 取药凭证载体 settlementNo（verify 可选 body，Task 11）；9 practice/check 真实化
  （Task 2/9）；10 D-16 三态门禁（Task 2/8）；11 visit_id Redis 当日键（Task 5）；12 WS 自建（镜像
  拦截器=偏差⑥，收敛工单 W-25）；13 portal 免登录白名单（Task 5/13）；14 W-22 前置 fix PR 先行
  （P-0 已合入）。
- **偏差与待批处置结论**：批次 4 主控裁决——发药工作台「发药签名」confirm 两参调用被其 spec :198-201
  `toHaveBeenCalledWith` 锁死文案与元数，R-3 confirmButtonText 第三实参与 §4.4 单号回显任一落地必破
  冻结断言，按「改实现不改断言」红线本调用不动，**新登 TASK.md D-21 待决策项**（选项=专项 PR 修订该
  断言并补两交付 / 维持现状）；R-3 三处已完成两处（批次 3 PricingSettle/RefundApproval 中文按钮文案）；
  D-20 fy.delay quorum TTL 惰性过期待决策项随 PR 描述声明缺口与影响边界；W-19/W-20/D-19 维持现状
  （W-20 已转产品待办）；W-23/W-24 两行不回填（W-24 代码修复已随本 PR 交付、行删除待合并后执行）。

## 2026-09-20 · P1 PR-5 M03 门诊主流程：outpatient 号段初始化登记与门禁适配（先记再改）

- **号段初始化批次**：outpatient 域启用固定百位段 **V200–V299**（recon 裁决 1；段内 V200–V299 全空），
  首批 V200–V204（V200 号源池三表、V201 预约/就诊四表、V202 分诊/队列两表、V203 申请单两表、
  V204 门诊事件契约种子）。outpatient schema 基线零迁移，`scripts/check-migration-governance.py`
  乱序守卫「号段初始化豁免」（:152-154）放行首批；批次合入后 outpatient 后续迁移一律走 V500+
  通用段（TASK.md W-12 全局规则恢复约束）。
- **存量 dev 卷一次性重置**（进入条件，Task 13 api-docs 导出前执行——导出要求 backend 在含 V200–V204
  的新卷上启动，旧卷 Flyway outOfOrder=false 必拒 pending 迁移；Task 15 真栈探针复用该重置后卷）：
  首批 V200–V204 低于基线全局最大已应用版本 V703，Flyway outOfOrder=false 对存量卷拒绝应用
  （守卫脚本 docstring :7-11 与 PR-1a 真栈实证）；处置=`docker compose -f deploy/docker-compose.yml
  --env-file deploy/.env down -v && up -d` 全新卷按版本升序一次应用（本条目即登记载体；
  Testcontainers IT 每次全新库不受影响）。**团队广播警示（待批 3 执行条件）**：重置=存量 dev 库
  一次性清空重建（down -v 清卷），执行前须在团队渠道广播警示——「存量 dev 库将一次性清空重建，
  未入库数据先行导出」；广播记录随执行台账归档。
- **practice_grant 改道 system 通用段 V704**（recon 裁决 9 原拟 V608 经守卫算术改道，偏差②）：
  system schema 基线非零迁移（V300–V303/V607），新迁移必须 > V703——V608 必被乱序守卫拦截；
  V704∈(500,None) 合法（V607 先例）。**V705**=门诊三类字典种子（appt-type/visit-type/disposition，
  03 Spec §8「引用 M01 字典 code 不自建副本」）。
- **CF-5/CF-3 事件 id 排定（全局递增按迁移执行序）**：id 23/25/31 载荷 desc 经 outpatient V204
  冻结（「UPDATE 存量行 + WHERE NOT EXISTS 兜底 INSERT」双语句形态——V204 应用序先于 V605/V702，
  纯 UPDATE 在新库 no-op 后会被 V605/V702 以占位 desc 首插，双形态保两序同终态，偏差①；CF-5
  双向评审声明随 PR）；新增 id 32 outpatient.visit.registered / 33 visit.finished / 34
  visit.cancelled / 35 visit.no-show（仅登记无发布点，id 27 先例）/ 36 appointment.booked /
  37 appointment.cancelled / 38 appointment.rescheduled / 39 appointment.timeout（延迟队列回调
  内部事件，自产自消）/ 40 schedule.stopped；`outpatient.queue.called` 不登记（纯 WS 通道）；
  `system.practice.changed` 已随 V5 id 6 登记（发布接线随 Task 2，零新登记）；
  MessagingGovernanceIT 总行断言 31→40 与 V204 同任务落改（PR-3「种子+断言同任务」Task 17 先例）。
- **JaCoCo 核心包扩名单**：父 POM 规则二增 `com.fuyun.outpatient.service.impl`（号源权威库存扣减/
  visit 主状态机/退号退费联动直接驱动资金联动=「核心业务状态机」LINE=1.00，recon 裁决 2，
  2026-09-19 主控裁决；包不存在时零包平凡通过，首个 impl 落码即生效）。
- **W-22 前置**：PR-4 九条合规遗留 fix PR 已先行合入（裁决 14，TASK.md W-22 行由其回填删除），
  本 PR 新增页面/DTO 自带合规形态（loading+在途守卫+零出网用例；入参显式格式校验 4xx）。

## 2026-09-20 · P1 PR-5 批复落档：11 项待批/10 项偏差全部批准认可，6 项执行条件融入任务步骤

- **批复记录**：用户逐项批复——待批 1–11 全部批准、偏差①–⑩全部认可；其中待批 3/4/5/6 与
  偏差⑤/⑨ 附执行条件，两点补充约束（偏差⑦ 声明态边界、偏差⑩ W-20 产品待办登记）与
  Task 16/17 回归护栏同批给出；计划自此具备 SDD 执行条件（进入条件 P-0=W-22 fix PR 先行）。
- **执行条件融入**（`docs/superpowers/plans/2026-09-20-p1-pr5-m03-outpatient.md`）：
  待批 3→Task 1 团队广播警示语 + Task 13 重置三点闭环与 Flyway 版本占用登记表建档；
  待批 4→Task 3 幂等键先实测 + 新旧库双时序迁移自检；待批 5→Task 5 超时幂等守卫 +
  casRelease version 断言 + 两新用例；待批 6→Task 7 镜像文件头三要素 + 只复制不顺手重构
  + 技术债工单登记；偏差⑤→Task 11 删除前置全局检索 + PR 描述删除清单；偏差⑨→Task 7
  优先级公式用户裁决版（类别分取最高单项/老幼残跨类叠加/封顶 999/同分按建行时间序）+ 三新用例。
- **补充约束与护栏**：偏差⑦ 声明态边界注记落 Task 5/8（仅注册迁移对+javadoc，不写无触发逻辑）；
  W-20 转产品待办（Task 1 Step 2b，附 0 元挂号/0 元组合结算两问）+ 结算报错可读红线（Task 8/10）；
  Task 17 改**六批次子 PR 形态**（批次 0 前置 Task 13，批次 1–5 串行，独立分支
  `feat/p1-pr5-ui-batch-0..5`+独立 PR 可独立 revert，合入硬门槛=五连门禁绿+22 spec 断言
  diff=0）、Task 16 同门槛注记；Handoff 批复记录段/进入条件/自审第 7 段落档。

## 2026-09-20 · P1 PR-5 计划二次增补：存量前端基建全面优化（用户增补裁决）

- **增补依据**：用户 2026-09-20 第二次增补裁决——前端优化范围**不限于新五页**，对已有
  前端基建全面优化（ui-ux-pro-max 设计方法论 + taste-skill 打磨同律适用，宪法 C.7）。
- **设计方案扩展**：`docs/plans/2026-09-20-p1-pr5-m03-outpatient-ui-design.md` 追加 §9
  「存量前端全面优化方案」（+443 行，总 1198 行）——存量 14 SFC 现状审计（styles 目录
  实为空壳、样式 100% SFC 内联；EP 无 zh-cn locale；ElMessageBox 按需样式缺失三页；
  侧栏详情路由高亮缺失等）、`--fuy-*` 设计系统迁移策略、布局壳升级（菜单数据化/折叠
  瞬切零动画）、9 业务页逐页改造规格、交互动效统一化、性能治理、六批次实施分期
  （0 全局地基→1 布局壳→2 患者→3 收费→4 药房→5 bigscreen/portal）、落地自查清单。
- **计划增补**（16→**17 任务**，+48/-10）：新增 **Task 17「存量前端基建全面优化」**
  （ui-ux-pro-max 强制加载；按 §9.8 六批次执行；**回归红线=22 个既有 spec 用例断言
  零改动**——审计实证全锁业务行为；W-22⑥⑦ 同文件交叠按 §9.8.3 协调）；Task 16
  打磨范围扩为**全站**（新 5 页+存量面）；执行序 13→17→14→16→15；Handoff 待批项
  增**第 11 条**。

## 2026-09-20 · 宪法修订：web 宪法 C.7「UI 设计思想·谋建琢三段律」与根宪法 §9 问题处理（用户直接修订正文，本条补录）

- **web/AGENTS.md 新增 C.7**：凡涉 UI 工作必循「谋→建→琢」三段闭环（顺序不可逆）——
  谋局定策（@ui-ux-pro-max：布局/样式/交互/动画四维通盘推敲、方案成形方可动工）→
  依图营造（忠实实现既定方案，粗成品严禁交付）→ 琢玉成器（@taste-skill 逐层打磨，
  四维验收=高级视觉/高级交互/流畅动画/高性能渲染缺一不可）；**协作纪律**=凡派遣
  subagent 必须在指令中明确要求加载 @ui-ux-pro-max 与 @taste-skill 方可开工。
  落款依据=用户 2026-09-20 UI 增补指令的宪法化固化（PR-5 计划 Task 16 与
  docs/plans/2026-09-20-p1-pr5-m03-outpatient-ui-design.md 为首个适用实例）。
- **AGENTS.md 新增 §9 问题处理**：zcode 派发 subagent 遇 `Idle-time tasks do not
  support background agents` 报错时，去掉后台标记改前台子智能体重发（PR-3 会话
  2026-09-18 用户写入，随本条一并入库）。
- **格式重排**：两宪法既有表格 prettier 风格对齐（根 §2 运行形态/§5 索引、web B.1
  目录边界/单 app 职责/C.2 技术栈）——内容零变化。

## 2026-09-20 · P1 PR-5 计划增补：UI 设计系统与 taste-skill 深度打磨任务（用户增补指令）

- **增补依据**：用户 2026-09-20 增补指令——用 ui-ux-pro-max 插件对 PR-5 前端整体布局/
  样式/交互/动画产出设计方案并应用；落地后用 taste-skill 插件对组件/样式/交互/动画
  全面深度打磨；两插件均为执行 subagent 开工强制加载项。
- **设计方案入库**：`docs/plans/2026-09-20-p1-pr5-m03-outpatient-ui-design.md`（755 行，
  PR-5 计划伴随规范）——设计 token 系统（`--fuy-*` 命名空间、临床蓝 #0369A1 经
  `:root:root` 双写覆盖 `--el-*` 梯度、白字对比 5.93:1）、五页布局骨架（workstation
  挂号收费联动/分诊台/医生站三栏体系+portal 480px 移动优先+bigscreen 暗色三段
  grid 与 rem 缩放零媒体查询）、组件定制样式、交互三态、动画编排（仅 transform/
  opacity 合成层、FLIP 用 Vue TransitionGroup 零依赖、prefers-reduced-motion 全局
  兜底）、性能红线与落地自查清单。
- **计划增补**（+67/-6，15→16 任务）：Global Constraints 增「UI 设计系统红线」（设计
  文档为前端视觉唯一权威）；前置项增 P-10；Task 13 增 token 落位与设计对照步骤；
  新增 Task 16「UI 深度打磨」（taste-skill 强制加载：design-taste-frontend/
  high-end-visual-design/minimalist-ui/redesign-existing-projects；执行序 13→14→16→15，
  打磨完成后进收口）；Handoff 待批项增第 10 条、SDD 派发强制加载设计技能声明。

## 2026-09-20 · P1 PR-5 计划定稿：M03 门诊主流程实施计划（docs-only）

- **计划本体**：`docs/superpowers/plans/2026-09-20-p1-pr5-m03-outpatient.md`（1865 行/15 任务/
  前置项 P-0~P-9；撰写链=两路调研（Spec 语义+dev 现状）→主控 14 条裁决固化
  `.superpowers/pr5-recon.md`→初稿 1708 行→R1 FAIL（P1×5：PortImpl 单测缺口/演示医师
  身份链断裂/存量卷重置时点/处方引用行 CHARGED 缺失/叫号队列重启恢复）→修复收敛
  （P1×5+P2×6）→R2 窄域复审「收敛可交付」）。
- **范围**：FU-M03-01~08 全 P0+practice/check 真实化（practice_grant V704+管理端点+种子）
  +D-16 unmask 三态门禁接入+PR-4 三占位事件回切（id 23/25/31 载荷冻结经 V204
  UPDATE+兜底 INSERT 双形态）+新事件 id 32–40 登记（总行 31→40）+前端三应用
  （workstation 三页/portal 免登录预约页/bigscreen 候诊叫号页）。
- **关键裁决**（recon 14 条，详 `.superpowers/pr5-recon.md`）：号段 outpatient V200–V204
  （初始化豁免+存量 dev 卷一次性重置，重置点前移 Task 13 api-docs 导出前）；practice_grant
  改道 system 通用段 V704（V608<V703 被乱序守卫拦截）；门诊字典种子 V705（19 条）；
  confirmRefundTerminal 误伤面走 billing api 端口单据化收口（CF-4 载荷零变更）；
  退号退费统一 M13 免审档；取药凭证载体=settlementNo；W-22 fix PR 为 SDD 进入条件（P-0）。
- **待批 9 条+偏差①–⑩**：见计划 Execution Handoff；批复后落档（PR-4 批复记录同款）。

## 2026-09-18 · P1 PR-4 M06 药事基础收口：药品字典+门诊发药闭环交付（CF-5 冻结载体）

- **交付面**：给药途径/用药频次字典预置（V607 两类 PUBLISHED 各 v1 共 25 条，前置项 P-3 改判载体）、
  药品字典（V700+对照/检索/changed 广播/未对照标记）、处方域（V701+开方/作废/
  billing PrescriptionFeePort 同事务联动）、发药闭环（V703+charged 放行/三段调剂/退药受理/
  refund.approved 终态收敛）、CF-5 事件 id 24–31 登记（V702）、billing 占用回写接线（零迁移，
  订阅经治理构件副作用回填）、W-16/17/18 退费守卫收口、前端药房工作站三页。
- **门禁记录**：后端 `mvn verify` 全模块绿（pharmacy impl LINE=1.00 生效）；前端五连绿；
  双验收锚点 IT（PharmacyPrescriptionFlowIT/PharmacyDispenseGuardIT）真栈绿；真栈探针
  （V700 系迁移 success/event_registry=31/q.pharmacy.* 七队列/api.d.ts 新鲜度）全绿。
- **裁决落实**：号段 V700–V799、事件 id 全局递增排定、stub 边界（无生产发布器，IT 注入）、
  W-16/17/18 本 PR 承接收口（TASK.md 回填删除）；偏差与评估结论见计划
  `docs/superpowers/plans/2026-09-18-p1-pr4-m06-pharmacy.md` Execution Handoff 偏差清单。

## 2026-09-18 · P1 PR-4 M06 药事基础：pharmacy 号段登记与门禁适配（先记再改）

- **号段登记**：pharmacy 域占用固定百位段 **V700–V799**（宪法 A.4.1-2「每模块固定百位段」；
  既分配对 integration V1–99 / patient V100–V199 / outpatient V200–V299 / system V300–V399 /
  iot V400–V499 / billing V600–V699，V700 段未占用）；首批 V700–V703（V700 药品字典、
  V701 处方两表、V702 CF-5 事件契约种子 id 24 载荷冻结 UPDATE + id 25–31 登记、V703 发药/批次
  四表）。V700 > 基线全局已应用最大版本 V606，存量 dev 卷与新库同按序应用，乱序守卫双保险；
  `scripts/check-migration-governance.py` `_SEGMENTS` 同步增
  `"pharmacy": ((700, 799), (500, None))`。**V605（billing）文件禁改**——id 24 载荷冻结经 V702
  对 integration.event_registry 数据行 UPDATE 承载（数据契约演进非 DDL 变更）。
- **CF-5 事件 id 排定**（全局递增按迁移执行序）：id 25 outpatient.order.charged（占位，producer=
  outpatient，生产发布方随 PR-5）、id 26 pharmacy.prescription.cancelled、id 27
  pharmacy.prescription.rejected（P3 审方引擎接入前无发布点）、id 28 pharmacy.dispense.completed、
  id 29 pharmacy.dispense.returned、id 30 pharmacy.drug.changed、id 31 outpatient.order.cancelled
  （占位，终态确认 PR-5 回切）；MessagingGovernanceIT 总行断言 24→31 与 V702 同任务落改（PR-3
  「种子+断言同任务」Task 17 先例）。
- **JaCoCo 核心包扩名单**：父 POM 规则二增 `com.fuyun.pharmacy.service.impl`（发药/退药状态机
  直接驱动计费占用回写与退费收敛=资金链路延伸，命中全局规范「核心业务状态机」LINE=1.00，
  2026-09-18 主控裁决；包不存在时零包平凡通过，首个 impl 落码即生效）。
- **M01 给药途径/用药频次字典预置改判**（PR-4 前置项 P-3；用户 2026-09-19 追加裁决「PR-4 顺手预置」，
  推翻 2026-09-18「V304 被乱序守卫阻断→随 M01 交付」结论）：改用 system 段通用号 V607（607>基线全局
  最大版本 V606，号段 (500,None) 合法、执行序先于 pharmacy V700–V703）预置两类 PUBLISHED 字典各 v1
  （medication.route 15 条/medication.frequency 10 条），迁移全文随 Task 2 Step 1b 落文件；校验分工=
  给药途径主校验维持 drug.route_codes 院内途径集（PH-1015 不变），字典供前端下拉与 M01 管理面维护，
  频次维持非空校验（条目级消费随 P3 审方引擎，W-8 同款前置注记）；`system.dict.published` 订阅
  在 PR-4 承载版本水位缓存刷新（部署期种子不发该事件，M01 管理面后续变更经广播刷新——语义顺承）。
- **billing 占用回写零迁移结论**（前置项 P-6）：billing 侧零行变更不落迁移、不占版本号（V607 号位
  由 system 段字典种子 V607 使用；pharmacy
  dispense 两事件行由 V702 登记 id 28/29；billing 订阅经治理构件 declareConsumerQueue 副作用
  registerSubscriber 运行期自动回填 subscriber_modules，PR-3 BillingSettlementFlowIT 实证先例）。

## 2026-09-18 · P1 PR-3 评审修复轮：退费二级审批实装（D，含 V606）+ 调价定时生效（E）登记（用户裁决=本 PR 内完整实现）

- **D 退费二级审批实装**（Spec FU-M13-03 P0，`docs/specs/modules/13-billing.md:136` 分级口径）：分级判定
  集中 `RefundServiceImpl.resolveApprovalLevel`——L0 免审（当日更正+无执行占用+金额≤autoExemptFen，现逻辑
  保留）、L1 一级（跨日/部分退/超免审但≤singleApprovalFen 且自费）、L2 二级（金额严格大于
  singleApprovalFen，恰等于归一级；或 payerType≠SELF_PAY 医保已结算，等价 refundType=SETTLED_REFUND，
  原判定解耦复用）；「票据已开具」维度依赖 FU-M13-06（明确不在本 PR），分级处留显式
  `TODO(FU-M13-06): 票据已开具 → 二级` 占位，测试声明该维度缺省不触发。
- **状态机与审批链**：`RefundStatus` 增 `PENDING_SECOND_APPROVAL`（一级已批、待二级）；`approve` 两段式
  复用现端点——PENDING_APPROVAL 批：L2 → PENDING_SECOND_APPROVAL + 落 first_approver/first_approved_at
  且不发事件，L1 → APPROVED + 发事件（现行为）；PENDING_SECOND_APPROVAL 批 → APPROVED + 发
  `billing.refund.approved`（载荷不变，CF-4 冻结）。连批守卫 BILL-1020 语义扩展：二级批人≠一级批人
  （同一账号不得连批两级），申请人自审守卫不变。reject 两个待审态均可驳（置 REJECTED+理由留痕）。
- **V606 迁移**（billing 固定段 V600–V699，V603 禁改）：`refund_request` 增审批链引用列
  `first_approver VARCHAR(32)` / `first_approved_at TIMESTAMPTZ`，`status` 列宽 VARCHAR(16)→VARCHAR(32)
  （新值 23 字符超原宽；PG 加长变宽为元数据级变更，存量数据保全）。本条目先记，迁移正文随后落盘。
- **零契约改动**：RefundVO 不加字段（前端靠 status 值区分待一级/待二级）、REST 无新端点、事件零改、
  api.d.ts 零重生成（生成态 status 为 string 宽容）；收费组长/财务/医保办角色硬校验随 PR-5 RBAC
  接线，本 PR 以「级别 × 双人链」近似并在代码注释显式声明。
- **E 调价定时生效**（工作包 2，commit ee3bede，采纳其报告建议条目文本）：snapshot 取价改按生效区间
  判定（status <> DRAFT AND effective_from <= now AND (effective_to IS NULL OR effective_to > now)，
  effective_from DESC 取首行；now＝装配期注入 Clock.systemUTC()，不注册全局 Clock Bean 以免与条件装配
  iotAmqpClock 类型注入歧义），到点由取价侧自然切换，不引入调度器/延迟队列；publish 补区间倒挂守卫
  BILL-1004（新起点早于当前未闭行起点拒发布，兑现并删除计划 TODO(P1-后段)）；零契约/迁移/事件改动。
- **B 支付行校验收口**已随上方「工作包 1」条目登记（commit 02035ea 支付行正数硬校验+退费读回卡引用
  守卫、2219cd7 前端执行按钮防抖），本条不重复展开，仅登记衔接关系。
- **测试与门禁**：后端单测新增 8 例（免审边界=autoExemptFen、超免审未超上限自费一级即 APPROVED、
  =singleApprovalFen 归一级、>singleApprovalFen 升二级且一级批零事件+审批链落库、医保 payerType
  直判二级、二级批→APPROVED+事件、连批守卫 403 BILL-1020、二级态驳回），申请人自审守卫回归复用既有
  用例；IT 扩二级场景（三账号：申请→一级批→连批拒→二级批→执行全链）；billing `mvn verify`（JaCoCo
  核心包 100%）与 `BillingSettlementFlowIT` 真栈 7/7、前端五连全绿。

## 2026-09-18 · P1 PR-3：/code-review 复核缺口修复工作包 1（用户裁决=本 PR 内完整修复）

- **B1/B3 请求侧硬校验**：`PaymentLine.amount` 与 `RefundLine.refundQuantity` 增 `@Positive`（0/负值
  400 拒）。修复前负数卡行可使 Σamount 勾稽假平、但 `cardPayFen` 合计不 >0 跳过扣卡，payment_details
  仍无条件落库 → 退费 execute 读回后全额入卡（凭空入卡）。
- **B2 读回侧守卫**：`RefundServiceImpl.execute` 读 payment_details 的 CARD_BALANCE 行时，channelRef
  缺失/JSON null/空文本/非数字 → BILL-1012（400）显式拒（新增私有 `parseCardAccountId`，与写入侧
  `SettlementServiceImpl.parseCardAccountId` 对称形态）。修复前 NullNode.asText() 返字面量 `"null"`
  致裸 `Long.parseLong` 抛 NumberFormatException 经兜底渲染成 500，出 BILL-* 契约外形态。
- **B4 前端防抖**：RefundApprovalView 执行/驳回按钮补 `:loading` + `:disabled` 组合与 handler 入口
  在途守卫（驳回含弹窗未决窗口）。修复前请求在途按钮仍可点，双击发双 POST（并发双退触发面）。
- **测试**：后端新增 4 用例（支付行 0/负值 400、refundQuantity 0/负值 400、execute 四类非法卡引用
  BILL-1012 且零资金动作）；前端新增 2 用例（execute 在途二次点击零出网、驳回弹窗未决抑制二次弹窗）。
- **范围界定**：execute 并发幂等（TASK.md W-16）与混付分摊/二级审批/定时生效不在本包，另行派发。

## 2026-09-18 · P1 PR-3：结算/退费并发缺口收口（终审 parked 三项，用户裁决=结算锚点幂等重构·本 PR 内修复）

- **修法落地**（条件更新/CAS 族，最小侵入根除读-校验-写 TOCTOU）：①settle 状态迁移改
  `SettlementMapper.casMarkSettled` 条件更新抢锚（DRAFT/PRESETTLED 谓词，输家重读分流幂等直返/
  BILL-1015），费用迁移改 `FeeRecordMapper.casMarkFeesSettled` 条件更新（PENDING 谓词+行数全量断言，
  不足抛 BILL-1016 同事务整体回滚），动卡入账严格后置于锚抢占成功；②apply 目标费用行集
  `FeeRecordMapper.lockByIds`（SELECT FOR UPDATE + id 升序锁序）串行化并发申请，锁内重读聚合做
  超可退守卫；③execute CARD_BALANCE 行按 channelRef 聚合、每卡单次全额贷记（与写入侧求和扣款
  口径对称）。不动迁移文件（零加列）、不动 REST 契约与 api.d.ts。
- **preview 双单口径**：不加同 visit 在途单守卫（最小侵入），双 DRAFT 单并发由 settle 费用行条件
  更新兜底（第二单 BILL-1016 拒，属可接受语义）。
- **测试**：单测新增 CAS 三分支/行锁顺序/同卡聚合/mapper 注解 SQL 守卫 8 用例（billing 173 绿）；
  新增 `BillingConcurrencyGuardIT` 三场景×3 轮真栈并发 IT（同单双 settle 恰一赢一幂等+PAY 台账
  恰一行/并发双 apply 恰一 201 一 409 BILL-1021/同卡拆分两行 execute REFUND 台账恰一行）。

## 2026-09-17 · P1 PR-3 M13 收费物价与医保基线收口（CF-4 冻结载体交付 + 前置项全清，先记再改）

- CF-4 六事件登记（V605 id 17–22）并发布/消费可用；CF-5 占位订阅两行（id 23–24）登记，
  上游 PR-4/5 冻结载荷后零改动接通；billing.charge.guaranteed/arrears.approved 随上游顺延注记。
- 前置项收口：minio 宿主端口 9003/9004；D-13 一卡通状态操作条件更新收口（stale balance 根除）；
  D-15 非法 ISO→400 PAT-1023；D-18 springdoc int64→string 生成契约根治（与 Jackson 运行时同源）；
  两轮终审 deferred minors 模板基类提炼（DomainEventSender/IdempotentConsumerSupport/
  FuyunStackITBase）与 matchedRules JSON 口径/updateRule @Pattern/快照断言收口；W-13 勘误回填。
- billing V600–V605（12 表 + 种子）；fuyun-app 装配入图，种子总量断言 16→24；双验收锚点 IT
  （结算闭环/快照不漂移）与收费员工作站三页真机通过；全量门禁双栈绿。

## 2026-09-17 · P1 PR-3：openapi int64 生成契约根治为 string（D-18 裁决方向②，机制修订·先记再改）

- **问题**：openapi-typescript 7.13.0 按 `format:int64` 生成 number，与后端 Jackson 全局
  Long→String 运行时输出及 web 宪法 A.3-6「Long 一律 string 承载」红线漂移（D-18）。
- **裁决落地**：fuyun-app 新增 `OpenApiSchemaConfig` 注册 springdoc ModelConverter，
  int64 数值 schema 一律覆写为 type=string（无 format）——契约与运行时单口径，前端
  `String()` 兜底不再是正确性依赖；PageResult.total 手写声明（string）与生成物自此同源。
- **影响**：api.d.ts 全量重生成（int64 字段 number→string），前端五连复验；真实医保/HRP 等
  外部对接不消费本生成物，无外部契约影响。

## 2026-09-17 · P1 PR-3 M13 收费物价与医保基线：billing 号段登记与门禁适配（先记再改）

- **号段登记**：billing 域占用固定百位段 **V600–V699**（宪法 A.4.1-2「每模块固定百位段」；
  既分配对 integration V1–99 / patient V100–199 / outpatient V200–299 / system V300–399 / iot V400–499，
  V600 段未占用）；首批 V600–V605（V600 项目/价格/组合、V601 医保对照/计价规则、V602 fee_record、
  V603 结算/退费三表、V604 押金两表+insurance_call_log、V605 CF-4 六事件 + CF-5 占位两事件种子 id 17–24）。
  选段依据另含「真库已应用最大版本 V503，V600 段对存量 dev 卷与新库同为顺序应用，
  免 out-of-order 承接路径」；`scripts/check-migration-governance.py` `_SEGMENTS` 同步增
  `"billing": ((600, 699), (500, None))`。patient 后续迁移一律 V500+（W-12）红线不受影响。
- **JaCoCo 核验**：父 POM 规则二核心包名单已预置 `com.fuyun.billing.service.impl`（P0 预置注释
  「billing 随模块实装生效」），PR-3 零门禁修订，首个 impl 落码即 100% 行覆盖生效。
- **事件三段名核验**：QueueGovernorImpl EVENT_TYPE_PATTERN（≥3 段）逐一过验，CF-4 六事件字面量
  `billing.fee.created` 等本身即 `<模块>.<实体>.<动作>` 三段合规，无 patient 式二段塌缩，落码零校正。
- **minio 宿主端口裁决落地预告**：deploy compose 宿主映射改 9003(API)/9004(console 预留)，
  避开本机 mindsoar-minio 占用 9000/9001（2026-09-17 用户决策，执行见 PR-3 计划 Task 2，
  根 AGENTS.md §2 端口表同步）。

## 2026-09-17 · P1 PR-3：用户批准计划并裁决 D-14/D-16/D-17 按默认建议（裁决登记·先记再改）

- **计划批准**：PR-3 M13 收费物价与医保基线实施计划（`docs/superpowers/plans/2026-09-17-p1-pr3-m13-billing.md`，
  20 任务，经 PR #24 合入 dev@69fe0fc）获用户批准，SDD 执行自 Task 1 起（前置项 Task 1–6 先行）。
- **三项用户裁决（均确认采纳计划默认建议，计划 Task 20 Step 2「禁代裁」前提就此闭合）**：
  D-14 就诊卡 LOST 找回=保留人工路径（窗口解绑/补卡办理，零代码，M02 Spec §7 补办理说明）；
  D-16 unmask=保留角色豁免单门禁，三态硬门禁归属 M03（PR-5 注册诊疗关系后接入，Spec §7 注记）；
  D-17 同证件异名建档=Spec 改写「证件冲突直接拒建（PAT-1002）」并移除建档期 SUSPECT 强冲突承诺。
- **落点**：三项 Spec 注记与 TASK.md 行删除统一在计划 Task 20 Step 2 执行（实施前 TASK.md 三行已标
  「已裁决」状态保留）；计划文档前置项表 P-3/P-5/P-6 与 Execution Handoff 同步登记裁决口径。

## 2026-09-17 · P1 PR-2 Task 18 全量门禁暴露的装配完整性缺口修复（先记再改）

- **问题一（上下文启动前置缺键）**：Task 14 将 `PatientConfig` @Import 接入 fuyun-app 后，
  `PatientCryptoProperties`（prefix=`fuyun.patient.crypto`，@NotBlank fail-fast）成为 fuyun-app 全部
  app 层 Spring 上下文启动前置；test profile 无该组键 → 绑定失败 → context refresh 取消 → 同 JVM 内
  后续 IT 级联失败（仅自带 @DynamicPropertySource 的 EmpiGovernanceIT 独活）。
- **修复一**：`fuyun-app/src/main/resources/application-test.yml` 追加 `fuyun.patient.crypto.data-key` /
  `mac-key` 兜底合成值（随机 64 位 hex，仅供测试上下文启动，与 EmpiGovernanceIT 常量互异，
  **非生产密钥、不触碰「禁提交真实凭据」红线**；@DynamicPropertySource 优先级更高可继续覆写）。
- **问题二（冻结总量口径漂移）**：V105 患者域八事件种子（id 9–16）随 Task 14 装配进入 fuyun-app IT 库，
  `event_registry` 总量 8→16，`MessagingGovernanceIT.seedRegistryRowsAreFrozenAndActive` 旧总量断言失效。
- **修复二**：该断言总量口径更新为 16（V5 七条 + V403 一条 + V105 八条，注释同步）；属本次改动导致的
  旧测试失效，按全局规范 §四 同步更新而非删除或跳过。

## 2026-09-17 · P1 PR-2 Task 17 配套：openapi 契约生成物 Prettier 排除清单（机制修订）

- **问题**：Task 16（2671324）gen:api 首跑后，生成物 `web/packages/shared/src/api.d.ts`（openapi-typescript
  输出 4 空格缩进）与本地生成中间产物 `web/api-docs.json`（已 gitignore）被 `pnpm format:check` 判格式偏差，
  web 五连门禁 format 环节自 Task 16 合入后不可过（属门禁配套缺口，非 Task 17 引入）。
- **裁决**：`web/.prettierignore` 增补两条排除，而非以 Prettier 重排生成物入库——生成物格式以生成端输出为准，
  一旦 Prettier 版式入库，下次 `pnpm gen:api` 重新生成必然产生 diff，反而击穿 web C.5-3
  「生成物新鲜度校验（重新生成 diff 为空才可合入）」门禁。

## 2026-09-16 · P1 PR-2：openapi 类型契约生成链路首跑（T-R4-3/T-R4-4 兑现）

- **Springdoc 首次引入**：fuyun-app 增 springdoc-openapi-starter-webmvc-ui 2.8.17（显式锁版，禁升 3.x）。
- **T-R4-3 实证结论**：绿：Springdoc 2.8.17 产出与 openapi-typescript 7.13.0 端到端兼容，生成物
  packages/shared/src/api.d.ts 入库，workstation type-check 全绿。
- **T-R4-4 实证结论**：Element Plus 2.14.5 + dayjs 1.11.23 显式依赖已满足最小集，回填删除。
- **遗留登记（PR 描述同步）**：CI 新鲜度自动校验（重新生成 diff 为空）需 backend job 产出 api-docs
  artifact → frontend job 消费的跨 job 通道，随 CI 演进接线；本 PR 以收口任务本地重生成核对兜底。

## 2026-09-17 · P1 PR-2 Task 15 验收 IT 暴露两处真栈缺陷修复（先记再改）

- **缺陷一（装配遗漏）**：Task 15（EmpiGovernanceIT 端到端验收）真栈首跑实证 `POST /api/v1/patient/patients`
  404（No static resource）——`PatientWebConfig` 的 `@Import` 清单漏登记 `PatientController.class`
  （其余六个 patient 控制器均已装配），建档/详情/更新/检索/冻结/解冻七端点全部未进 MVC 映射；
  单测（MockMvc standalone 直连 controller 构造器）与 Modulith verify 均无法暴露此缺陷，真栈 IT
  验收门禁首跑即抓住。修复：`@Import` 补 `PatientController.class`（与其余六个控制器同模式）。
- **缺陷二（迁移约束与状态机矛盾）**：IT 二跑实证 `POST /merges` 500——`merge_record.pre_snapshot`
  被声明为 NOT NULL，但快照在 approve 执行合并时才产生（`executeMerge` 写入），发起合并（PROCESSING）
  的合法 INSERT 必然违反约束。修复：V101 修订 `pre_snapshot` 为可空并补列注释（拆分守卫仅放行
  COMPLETED，快照必在，可空性与状态机一致）。修订合法窗口 = 本 PR 未合入、无任何已应用基线
  （存量 dev 卷最大 v503 不含 patient 段，Task 14 同口径）。
- **缺陷三（拆分遗留悬空合并指针）**：IT 三跑实证 `POST /merges/{id}/split` 后从档详情仍带
  `merged_into_patient_id`——`split()` 以 `updateById` 落恢复状态，MyBatis-Plus 默认忽略 null 字段，
  指针置空从未生效（单测内存表断言掩盖）。修复：改 `LambdaUpdateWrapper` 显式 SET
  status=NORMAL + merged_into_patient_id=NULL；同步改写 `splitRestoresMergedArchiveAndPublishes`
  断言（捕获 wrapper 校验 SET 列与 NULL 值）。
- **验收**：EmpiGovernanceIT 七用例全绿为本次三处修复的验收依据。

## 2026-09-17 · P1 PR-2 Task 14 门禁驱动的两项契约修复（先记再改）

- **背景**：Task 14（装配与边界）真栈冒烟暴露两处上游契约缺陷，均由门禁 fail-fast 定位：
  Modulith 边界校验拒绝 patient 引用 system 未导出类型；fuyun-app 真栈启动时 M20 消息治理构件
  拒绝 patient 六个 2 段式事件名（`QueueGovernorImpl` ≥3 段审查，代码 + `QueueGovernorImplTest`
  两段拒绝用例 + FU-M20-06 三重锁定）。
- **修复一（审计契约枚举归位）**：`AuditActionType` 从 `com.fuyun.system.enums` 迁入
  `com.fuyun.system.api`——它是 api 包 `@AuditLog` 注解的成员类型，跨模块标注即引用，按宪法 B.1
  「api/ 对外契约唯一出口」随注解同住 api 显式导出；不放宽 Modulith 边界、不开 enums 包第二出口。
- **修复二（患者事件名对齐三段命名治理）**：六个 2 段式事件名改为 `patient.patient.<动作>`
  （created/updated/merged/split/frozen/unfrozen），依据 = M02 Spec §11 自审自己声明的
  「事件命名 `<模块>.<实体>.<动作>`」约定（spec §7 六个字面量与 §11 约定自相矛盾，本次以 §11
  为准）；M-25 成对语义不变；`patient.identifier.changed` / `patient.health-summary.updated`
  两个 3 段名不变。同步面：`PatientMessagingConstants` 六常量、V105 种子六行 event_type（迁移
  本 PR 未合入、无任何已应用基线，内容修订合法且为唯一窗口——一旦合入即冻结）、api payload 六
  record 与服务接口 javadoc、`docs/specs/modules/02-patient.md` §7。**Task 15（EmpiGovernanceIT）
  与后续订阅方一律以新名为准**。
- **修复三（发布确认回调归属纠偏）**：`PatientEventPublisher` 移除 `RabbitTemplate.Confirm/Returns
  Callback` 实现与构造期注册，复用 SystemEventPublisher 统一持有的共享回调告警通道——Spring AMQP
  共享模板单回调槽位为硬断言（设第二实例即启动失败，真栈冒烟实证；Task 13 审查 I4「后注册者覆盖
  前者」的记载有误，IotEventPublisher B4.3 偏差申报的「单一槽位统一持有」才是既定范式）。同步删除
  失效测试两例、新增「不注册回调」契约断言（IotEventPublisherTest 同款）；回调整合归 P1
  RabbitTemplateCustomizer（TASK.md W-11 评审项）收口，届时各发布器零改动。
- **裁决说明**：曾评估放宽 M20 pattern 至 ≥2 段——否决：须删除治理构件专门的两段拒绝测试用例、
  修订 FU-M20-06 与三处 javadoc，削弱已定稿治理规则且 M01/M14 事件全部合规，属反向迁就。
- **探针密钥勘误**：task-14 简报给的数据密钥为 48 位 hex，构件 fail-fast 校验要求 64 位 hex
  （32 字节 AES-256，`PatientCryptoProperties`），冒烟以 64 位探针值执行（仅影响冒烟 env，无代码影响）。

## 2026-09-16 · P1 PR-2 M02 患者 EMPI：patient 号段登记与门禁修订（先记再改）

- **号段登记（V500 起先登记先占惯例的号段制对齐条目）**：patient 域本次占用 **V100–V105**
  （V100 patient/patient_identifier、V101 possible_duplicate/merge_record、V102 health_summary/health_item、
  V103 隐私三表+脱敏规则种子、V104 card_account/card_txn、V105 患者八事件 event_registry 种子），
  均在 patient 登记号段（V100–V199）内；scripts/check-migration-governance.py `_SEGMENTS` 既有登记无需改动。
- **乱序守卫豁免修订（宪法 C.5 门禁工具修订）**：`check_out_of_order` 增「号段初始化豁免」——
  schema 在基线中零迁移时其首个批次放行（全新库升序应用为 Flyway 唯一事实；追加场景全局规则不变）。
- **JaCoCo 名单修订（宪法 C.5-2 门禁配置修订）**：父 POM 规则二核心包名单增
  `com.fuyun.patient.service.impl`（EMPI 归一/合并/冻结属核心业务状态机转换路径，
  对齐 P1 DoD「新增 M02 核心包覆盖率按 JaCoCo 双阈值」；代价 = 该包全部 impl 单测 100% 行覆盖）。
- **存量环境承接说明（审查 C5 双路径，待计划审批确认）**：全新库（CI/Testcontainers/compose 新卷）按版本
  升序一次应用 V100–V105；存量 dev 卷（最大已应用 V503）启动时 Flyway validate 将报
  「detected resolved migration not applied to database」并 fail-fast。承接路径 A（默认，非破坏）=
  application.yml `out-of-order` 键 env 化为 `${FUYUN_FLYWAY_OUT_OF_ORDER:false}`（默认 false 红线不变），
  以一次性临时容器注入 true 应用本批次后即毁（步骤/验证/还原防呆见 Task 16 Step 3），不触碰任何数据、
  兼容拍板 7「iot 夹具不动」；路径 B（备选，破坏性）= `docker compose down -v` 重建 + iot 演示夹具行
  （id=900001）留档原值重注入（重置即丢该行，与拍板 7 有张力、须经拍板）。两路径均随本条目登记。
- **号段批次后果（审查 I6）**：号段初始化豁免仅承载 schema 基线零迁移的首个批次——本批 V100–V105 合入后
  patient 后续迁移（V106+）将被乱序守卫全局规则拦截，patient 后续迁移一律走 V500+ 通用段
  （TASK.md W-12 同步登记）。
- **CF-3 冻结载体落点**：V105 八事件种子（id 9–16）+ Task 3 的 VisitIdValidator/OngoingVisitQuery
  契约 + Task 15 的 EmpiGovernanceIT（isRegistered 与 fy.topic 可消费断言）。

## 2026-09-15 · PR-1b 收尾：TASK.md W-4/W-5/W-6 工程债回填删除

- **背景**：PR-1b（M20 事件总线治理完整化）实现期内三项 TODO 工单已随各 Task 清偿，按登记台「条目回填后删除」
  规则收口；本条目为先记再改登记，TASK.md 三行删除随本条目同批落盘。
- **W-4（Flyway 迁移号段归属与版本唯一的 CI 自动校验）清偿**：`scripts/check-migration-governance.py`（号段归属 +
  版本唯一 + 相对基线乱序三重守卫，乱序守卫正对 PR-1a 实证的「号段内合法仍判 out-of-order」缺口），接线
  pre-commit local hook（`.pre-commit-config.yaml`）与 CI hygiene job（`.github/workflows/ci.yml`，fetch-depth 0 +
  MIGRATION_BASE_REF 基线注入）；既有 15 个迁移全绿，随本 PR Task 9/10 新增 V502/V503 后 17 个全绿（2026-09-15
  号段登记条目预告的「TASK.md W-4 回填依据」就此兑现）。
- **W-5（配置 properties record 的 toString 脱敏覆写兜底）清偿**：IotProperties.Amqp（11 字段全清单覆写，
  accessSecret/tokenHmacSecret 两凭据打码）与 IotProperties.Fallback、SecurityProperties toString 脱敏覆写 + 单测
  （明文泄露断言改脱敏断言），等保三级纵深防御补齐。
- **W-6（PR-2 /code-review 三项 Minor 处置）清偿**：①死信留痕列宽钳长——DeadLetterListener 五列 TextTruncate
  钳长 + 单测（防畸形帧超 VARCHAR 列宽致「不合规信封拒收留痕」红线落库失败）；②订阅登记并发守卫——
  EventRegistryServiceImpl 单语句 CAS 自旋 3 次 fail-fast + broadcast 标记行拒订守卫 + 用例（消除多实例并发丢更新）；
  ③消费范式 release 异常遮蔽——MessageIdempotencyServiceImpl.settleFailure addSuppressed 双保留 + 三处消费方同步 +
  MessagingGovernanceIT 回归（原始业务异常不再被 Redis release 异常顶掉）。
- **登记收口**：TASK.md W-4/W-5/W-6 三行随本条目回填删除；W-7（非数值遥测入库，用户指示暂缓实现）与 W-8
  （FU-M20-04 剩余条目，本 PR Task 9 新增登记）两行保留不动。
- **影响范围**：仅 TASK.md 与本文件两文件，零代码变更。

## 2026-09-15 · PR-1b 终审收口：M20 Spec §7 同步注记与 TASK.md W-9 工单登记（先记再改）

- **背景**：PR-1b 全分支终审核断——已定义端点的实现不构成改契约（Spec 无需大改），但存在三处
  「实现已交付 / Spec §7 未登记」面与一条遗留承诺未兑现，须补注记与工单登记后方可收口合入。
- **M20 Spec §7 三处注记（docs/specs/modules/20-integration.md，改动最小化、不重写既有内容）**：
  ①新增 `GET /event-publications` 行（Modulith 事件发布注册表只读投影，status 为 COMPLETED/INCOMPLETE
  派生态——PR-1b 已交付而原清单缺登记）；②死信管理行补重推上限口径（每死信 3 次，超限错误码
  INT-1003 / HTTP 409，计数载体 = dead_letter.replay_count——控制器拍板值首次入 Spec）；③
  `POST/DELETE /event-registry` 与 `POST /mdm/redispatch` 加「分阶段交付」注记并指向 TASK.md W-8
  工单（前者写动词由队列声明治理构件自动化登记承接、无 P0 消费方故裁剪；后者受 M01 版本化回源 /
  重发接口跨模块前置阻塞）。
- **TASK.md 新增 W-9 工单（终审建议 #4）**：DeadLetterListener 同一 eventId 重复落行收敛——V4 迁移
  定案口径允许同一死信重复投递重复落行，DeadLetterListener javadoc 原承诺「P1 死信管理界面完整化时
  收敛」在 PR-1b（即 P1 完整化）交付后仍未兑现；收敛动作归 FU-M20-06 死信告警完整化或后续工单。
  DeadLetterListener 该句 javadoc 同步改为 W-9 现实口径（仅注释一处、零行为变更），编译验证通过。
- **影响范围**：docs/specs/modules/20-integration.md、TASK.md、本文件三文档，外加 DeadLetterListener
  一处 javadoc 注释，零行为变更。

## 2026-09-15 · D-8 裁决落地：宪法 A.5-9 failover 参数正文同步（先记再改）

- **背景**：PR-4 B4.4 实测（`IotAmqpReconnectIT` 两次 RED 留证）证实 qpid-jms 2.11 的 failover 选项必须带
  `failover.` 前缀（裸名形态不被 failover 层识别，语义等同未配置）；官方选项表无 timeout 类移交参数，
  唯一移交机制为有限 `failover.maxReconnectAttempts`（由 -1 改 3 后交 supervisor 以新时间戳凭证无限重建）。
  代码与 CHANGELOG 2026-09-11 条目已登记，宪法正文未同步（TASK.md D-8 待决策行）。
- **裁决**：按 TASK.md D-8 默认建议执行——**修订正文**（用户 2026-09-15 裁决，PR-1b 随本次交付）。
- **宪法修订范围**：backend/AGENTS.md A.5-9 正文——三参数补 `failover.` 前缀语法说明与取值；补
  `failover.maxReconnectAttempts=3` 的有限重试移交 supervisor 语义（「无限重连」语义上移到凭证刷新层，
  正对 IoTDA 拒绝超 5 分钟旧时间戳的服务端语义）；条款措辞与 IotAmqpConfig 装配实现逐字对齐。
- **归源更正（追记）**：CHANGELOG 2026-09-11 条目中「此为宪法 A.5-9『failover.maxReconnectAttempts=-1（无限次）』
  文字的实测修正」表述**归源错误**——A.5-9 正文从未写入 maxReconnectAttempts（见修订前正文），
  `-1` 实为**简报 §1.3 预判值**；该条目就地更正为「简报 §1.3 预判值的实测修正」（历史事实保留，仅纠正归源）。
- **登记收口**：TASK.md D-8 待决策行随本条目回填删除。
- **代码影响面**：零行为变更（仅 IotProperties javadoc 引用措辞同步；IotAmqpConfig 装配与 IotAmqpConfigTest
  URI 断言不动）。

## 2026-09-15 · P1 PR-1b M20 事件总线治理完整化：号段登记与实施落盘（先记再改）

- **号段登记（V500 起「先登记先占」，登记载体 = 本文件）**：本次占用 **V502**（`integration.mdm_subscription` 主数据分发订阅台账）、**V503**（`integration.mdm_dispatch_log` 主数据分发流水）；两者均在 integration 号段（V1-V99 与 V500+ 通用段）内，且版本号大于真库历史最大值 V501（Flyway `outOfOrder=false` 硬约束，PR-1a 真栈实证）。
- **落盘依据**：M20 Spec §4 两张治理表（mdm_subscription / mdm_dispatch_log）+ FU-M20-04 主数据分发（订阅登记、广播链路分发流水、矩阵查询）。
- **跨模块前置（登记）**：FU-M20-04 的全量初始化、每日版本对账、落后自动全量重发与 `POST /mdm/redispatch` 端点依赖 M01 版本化回源/重发接口（当前仅字典有版本化读接口），未随本次交付，登记 TASK.md 待办与 PR 描述。
- **CI 联动**：新增 `scripts/check-migration-governance.py`（号段归属 + 版本唯一 + 乱序守卫）随 PR-1b 落盘，本批两条迁移为其守护对象（TASK.md W-4 回填依据）。

## 2026-09-15 · PR-1a 收尾 D-10/D-11 用户裁决落地并修订宪法（先记再改）

- **背景**：PR-1a（Spring Modulith 事件基础设施，PR #16 合入 dev@1d998d5）执行期实证三项收尾事项登记 TASK.md D-10~D-12，用户 2026-09-15 裁决全部修正；本条目记 D-10/D-11 修宪（D-12 flaky 修复为代码变更，随修复 PR 合入，不涉宪法）。
- **D-10 裁决（跨模块监听注解包路径修宪明确）**：`org.springframework.modulith.ApplicationModuleListener`（spring-modulith-api 包）在 1.4.13 标记 @Deprecated(since="1.1", forRemoval=true)，官方 javadoc 指定替代为 `org.springframework.modulith.events.ApplicationModuleListener`（同名注解迁移至 spring-modulith-events-api，组合语义一致：@Async + @Transactional(REQUIRES_NEW) + @TransactionalEventListener；PR-1a 代码已用该路径）。**宪法修订**：B.2-6 跨模块监听条款注明注解取 `org.springframework.modulith.events` 包路径（api 包同名注解禁新代码引用）。
- **D-11 裁决（C.4 补单模块构建 -am 风险提示）**：多模块反应堆中 `mvn -pl fuyun-{domain}` 不带 `-am` 会从本地仓库解析依赖模块的已安装 jar（而非反应堆内最新构建），PR-1a 实证两类假故障：集成测试报 Flyway「迁移缺失」（旧 integration jar 无新迁移）、边界测试假违规（旧模块 class）。**宪法修订**：C.4「指定模块门禁」命令注释补不带 `-am` 的风险提示。
- **登记收口**：TASK.md D-10/D-11 两行随本条目回填删除（D-12 行随修复 PR 回填删除）。
- **修订范围（随本 PR）**：backend/AGENTS.md B.2-6（补注解包路径约定）、C.4（补 -am 提示）。

## 2026-09-14 · P1 实施计划审批通过，D-2/D-9 用户裁决落地并修订宪法 Modulith 条款（先记再改）

- **计划审批**：PLAN-P1-01（`docs/plans/2026-09-14-P1实施计划.md`）经用户裁决五项决策后批准；范围=总 Spec §9-P1 六模块 P0 优先级条目切片，PR 序列七支，交付验证物=门诊挂号→就诊→收费→发药全流程真栈演示。
- **D-2 裁决（引入 Spring Modulith）**：版本锁 1.4.x（当前 1.4.13，父 POM 锁 spring-modulith-bom，BOM 外依赖）；starter-jdbc 事件持久化（非 JPA）+ test 边界校验；`republish-outstanding-events-on-restart=false`（多实例不安全）；EventOpsJob 编程式重试（卡住>5 分钟重投）与清理（7 天前完成记录）挂 ShedLock（A.5-14）；`ApplicationModules.verify()` 进 fuyun-app 测试套纳入 verify 门禁（与 ArchUnit 1.5.0 并存分工：Modulith 管模块级边界、ArchUnit 管自定义分层规则）+ CI 生成 PlantUML/C4 模块依赖图；跨模块监听一律 `@ApplicationModuleListener`（独立事务异步）且发布方必须在事务代理内。**偏差申报（对用户参考配置）**：`events.jdbc.schema-initialization.enabled=false`——事件日志表建表走 Flyway（integration 号段 V6+，官方 event_publication 结构），理由=宪法 A.4.1「Schema 唯一来源=Flyway、禁自动 DDL」红线不豁免 + `--scale backend=2` 多实例并发自动建表竞态；用户改判框架自动建表须同步修宪豁免。
- **宪法修订范围（随本 PR）**：backend/AGENTS.md B.2-6（Modulith 引入定稿：版本锁定/边界校验 CI 强制/与 ArchUnit 分工/@ApplicationModuleListener 约定/日志表 Flyway 建表）、B.3-2（in-JVM 可靠投递走 Modulith 注册表，@Externalized 桥接范围经设计评审后再修订）、B.3-3（可靠事件投递形态由自建"事务后事件表+定时重投"改为 Modulith 事件发布注册表 + 定时重试/清理）、C.2 技术栈表增 Spring Modulith 行。
- **D-9 裁决（非数值遥测入库）**：非数值且需要的数据像数值型一样提取转换入库存储——quality 维持既有 isNumeric 标注（BAD=非数值定型标注，语义不变）；入库不再丢弃：iot_telemetry 新增文本承载列（iot 号段新迁移，禁改已应用迁移），非数值标量以原文承载、对象/数组以紧凑 JSON 文本承载，skip_non_numeric 丢弃口径退役。实现登记 **TASK.md W-7**（P1 PR-1 开工前 fix PR 闭合），D-9 行就此回填删除。
- **其余裁决**：M03 病历书写=临时纯文本文书能力过渡（M09 编辑器维持 P4）；医保基线接口=接口位+模拟适应器（真实联调环境用户侧后补）；portal 患者预约渠道纳入 P1（PR-5）。
- **登记动作**：TASK.md 待决策项 D-2/D-9 行回填删除、TODO 工单新增 W-7。

## 2026-09-14 · IOTDA 联调收口：L-1 全链路演示、L-2 十分钟断链、L-4 积压水位实测与 TASK.md 延后登记回填（先记再改）

- **前提**：PR #12（L-3 报文映射，合入点 dev@0584e1e）交付后重建 `fuyun/backend:dev` 镜像并 `--profile sim` 起栈，七服务全 healthy。本条目为 TASK.md「延后事项 L-1~L-4」的回填记录（回填后删除），全部证据产生于真实华为云 IoTDA 环境（dev 联调栈）。
- **L-1 全链路演示（IoTDA→AMQP→TimescaleDB→WebSocket）**：①上行——iot-simulator MQTT/TLS（ssl://…iotda-device…:8883，deviceId=6aa570ac155456566827c784_fuyun-demo-001）5 秒周期 properties 上报；②消费与映射——backend AMQP 消费每 5 秒一批 2 条（batchSize=2/inserted=2，无 SASL 错误、无毒丸），`iot_consume_error_log` 起新毒丸为 0（历史 8740 行为映射落地前存量留痕），`iot.iot_telemetry` heartRate/spo2 行 quality=GOOD、source=IOTDA；③绑定归属——联调夹具 `iot_binding` id=900001（demo 设备→病区 1，dev 环境测试数据，P1 管理端点交付前的演示夹具）生效后落库行 patient_id/visit_id 富化为 1/1；④推送——admin 令牌经 nginx `/ws` 升级、STOMP CONNECT 帧鉴权（Authorization: Bearer）通过，订阅 `/topic/iot/telemetry/1` 收到摘要帧（count=2、items=heartRate/spo2、occurredAtUpperBound 与批次对齐，16 秒采样窗收 3 帧）。**口径注明**：设备状态帧链路（iot_device.status 更新与 /topic/iot/device-status/{wardId} 推送）真实栈不可演示——simulator 仅发 properties 上报无状态帧，该管道行为由 IotTelemetryPipelineIT 集成测试覆盖。
- **L-2 真实端点 10 分钟断链演示**：方法 = `docker stop deploy-backend-1` 制造真实 AMQP 断链 10 分 26 秒（17:23:29Z→17:33:55Z），期间 simulator 持续上行（IoTDA 服务端积压）；`docker start` 后消费线程 4.5 秒以**新时间戳凭证**重建连接（日志「AMQP 连接已建立（新时间戳凭证）」，正对 IoTDA 凭证内嵌时间戳超 5 分钟拒绝建链的服务端语义——本地 broker 无法复现该语义，真实端点演示由此补全 T-R3-3 口径）；首批攒批一次追平积压 received=252/inserted=252（126 帧×2 属性，与断链窗口帧数分毫不差，零丢失），随后恢复 5 秒/2 条稳态。断链退避节奏（12s→24s→30s 封顶）已在 PR #11 联调期于真实 IoTDA 拒链场景实证。
- **L-4 真实积压水位实测（应用侧代理口径）**：IoTDA 控制台指标不可达（无控制台访问），以应用侧实测代理——重连后积压帧最旧 occurred_at=17:23:32.508Z（断链后首个上报），距追平落库时刻 17:34:07Z 约 **10 分 35 秒**，即本次断链窗口的 IoTDA 侧最旧未消费消息年龄实测值；252 行时间边界连续（最旧 17:23:32.508Z/最新 17:34:05.121Z）无缺口无重复。持续水位观测由既有 iot.amqp 指标词表承载（iot.amqp.connected 实测 1.0、断链时长、攒批队列填充率、重建计数）。
- **L-3**：已随 PR #12 落地（2026-09-13 条目），本次以「新毒丸归零 + 遥测落库」实证；其 quality 口径细化声明待用户追认（登记 TASK.md D-9）。
- **登记收口**：TASK.md 延后事项 L-1~L-4 四行按「回填后删除」规则删除（该节清空）；新增 D-9 待决策项；台账补 PR #12 批次行。

## 2026-09-13 · L-3 冻结：真实 IoTDA 规则引擎报文映射（选项 B 代码映射）与毒丸留痕脱敏落地（先记再改）

- **背景与批准结论（TASK.md L-3，用户 2026-09-13 批准选项 B）**：P0 线格式为 CF-7 JSON，但真实华为云 IoTDA AMQP 推送报文与 CF-7 不匹配，毒丸隔离机制已留痕 569 帧（iot_consume_error_log，stage=PARSE、确认抛弃）。真实报文结构已取证（raw_payload 原文）：顶层 `resource="device.property"` + `notify_data.header`（device_id/node_id/product_id）+ `notify_data.body.services[]`（service_id/properties/event_time）。选项 B 裁决：解析器新增「IoTDA AMQP 推送报文」第三形态，真实报文展开为 N 条 CF-7 标准遥测消息，下游攒批/落库/推送管道零改动。
- **映射规格（冻结，禁再猜测性兼容）**：判别条件 = 顶层 `resource` 字段存在且精确等于 `"device.property"`（优先于既有遥测/状态判别；非该形态回退既有判别，CF-7 帧行为零变化）。字段映射：deviceId ← `notify_data.header.device_id`（必填非空白，缺失即毒丸）；occurredAt ← 顶层 `event_time_ms`（ISO-8601，复用既有 OffsetDateTime→Instant 双回退解析为 UTC Instant，缺失或不可解析即毒丸）；`notify_data.body.services` 必须为数组且 ≥1 元素、每个 service 的 `properties` 必须为非空对象，否则毒丸。值承载（StandardTelemetryMessage 契约）：每属性键一条消息，metricCode=属性名（如 heartRate/spo2，P1 建字典再规范化）；value 字符串承载——JSON 标量（文本/数值/布尔/null 字面量）`asText()` 转文本，对象/数组 `toString()` 紧凑 JSON 文本；unit 恒 null（IoTDA 属性上报不含单位）。quality 口径细化声明（2026-09-13 审核 S1 订正，待用户追认）：①冻结决策文本「映射时需 String.valueOf 并保持 quality=GOOD」的上下文为数值型真实报文（heartRate=78/spo2=100），实现对该场景产 GOOD，符合冻结文本；②冻结文本未覆盖非数值标量/布尔/null/对象/数组属性值，实现按 P0 既有 CF-7 口径补齐——value 非数值 → quality 强制 BAD 并保留原文，标注不阻断（与 TelemetryFrameParser 既有 javadoc「value 非数值时 quality 强制 BAD 并保留原文」口径同构）；③端到端等价论证：TelemetryIngestServiceImpl 仅对 value 可数值定型的行入库（约 110-116 行），非数值属性无论 GOOD/BAD 均不入库，真实报文两口径产物完全一致，偏差仅在解析器契约层；④待用户追认：若用户改判恒 GOOD，改动面 = 解析器单行 + 断言翻转，随下一次修宪/修订登记回收。source=IOTDA。
- **消费者确认回调挂尾设计与安全性论证**：批量帧展开的 N 条消息逐条入攒批器，客户端确认回调只挂尾条、其余挂空动作。安全性：JMS CLIENT_ACKNOWLEDGE 为会话级累计确认且每队列独立会话——同一队列单消费线程按序投递、攒批器单 flush 线程按序刷批，任何会累计确认到本 JMS 消息的确认回调必然在其尾条所属批次落库之后才可能执行（批次按序、批内先落库后确认），不存在「确认先于落库」窗口；落库失败路径零真实确认执行 → 会话销毁令整条消息回归重投域 → iot_telemetry 唯一约束 ON CONFLICT DO NOTHING 幂等去重，at-least-once 语义保持。防御分支：展开产物为空（解析器契约不可能）按毒丸留痕抛弃——否则该 JMS 消息无确认动作致 broker 无限重推。
- **毒丸留痕脱敏落地（TASK.md L-3 行自带义务，终审 Minor 2026-09-11）**：现有 569 毒丸帧原文含生命体征数值（健康数据），禁止原文入库。新增 `ConsumePayloadMasker.sanitize`：IoTDA 推送形态（可解析为 JSON 对象且含 `notify_data` 对象字段，无论毒因）→ 白名单字段提取——保留 resource/event/event_time_ms、header 三标识与 services[].service_id 结构及 properties 键名，属性值一律替换 `"*"`（紧凑 JSON，白名单外字段如 services[].event_time 丢弃）；其余文本（非 JSON/其他形态）→ SensitiveMasker 正则兜底（先证后机组合约定）；null/空串原样。接线后 iot_consume_error_log.raw_payload 恒为脱敏文本；口径变化：raw_digest 随之为脱敏后文本的摘要（用途=排查锚点与重复帧对账，脱敏后同构报文摘要合并无害）。
- **改动面**：`IotMessagingConstants` 新增 IoTDA 推送报文字段常量；`TelemetryFrameParser` sealed `ParsedFrame` 新增 `TelemetryBatchFrame` 第三变体与 `parseIotdaDeviceProperty` 解析（既有遥测/状态判别与解析零逻辑改动）；`IotAmqpTelemetryConsumer` 分派链新增批量帧分支与 `dispatchTelemetryBatch`（挂尾确认），毒丸留痕接脱敏；新增 `ConsumePayloadMasker`（internal/ 静态工具，对齐 SensitiveMasker 模式）；测试增量：解析器/消费者/脱敏器单测与 `IotTelemetryPipelineIT` 步骤⑨（真实取证报文经 fake broker → iot_telemetry 展开 2 行）。

## 2026-09-12 · 缺陷修复：AMQP 凭证改华为云官方三段 username 与原值 password 格式（先记再改）

- **缺陷实证（本地 compose 联调）**：启用 AMQP 对接真实华为云 IoTDA 后认证恒被拒，backend 日志 `Client failed to authenticate using SASL: PLAIN`（supervisor 退避重建 12s→24s→30s 封顶运转正常、simulator MQTT 链路正常）——排除重建机制问题后，经官方文档核对定位为凭证组装格式错误。
- **官方核对结论（来源：《AMQP客户端接入说明》 support.huaweicloud.com/usermanual-iothub/iot_01_00100_2.html）**：username = `accessKey=${accessKey}|timestamp=${timestamp}|instanceId=${instanceId}` 三段竖线拼接（instanceId 可选，同一 Region 多个标准版实例才需设置，单实例留空段即可）；password = accessCode **原值、无任何拼接**；timestamp 为 13 位毫秒且服务端校验偏差超 5 分钟即拒绝（每次建链刷新机制保留）；连接串子参数 `amqp.vhost=default&amqp.idleTimeout=8000&amqp.saslMechanisms=PLAIN`（vhost 仅支持 default）。此前 PR-4 调研期「password = accessSecret + 13 位毫秒时间戳拼接」为**错误预判**，相关注释表述本次一并清除。
- **修复面**：① `IotAmqpTelemetryConsumer.ensureConnected` 凭证组装改官方格式（username 三段、password 原值，javadoc 引用官方 URL）；② `IotAmqpConfig` 连接 URI 于 amqps（IoTDA 生产端点）子 URI 追加官方三子参数——vhost 仅支持 default 属 IoTDA 接入面参数，本地 amqp:// RabbitMQ（vhost 为 `/`）不追加，failover.* 三参数零改动，URI 装配契约以新增单测固化；③ 消费者单测凭证断言改新格式（username 三段解析 + 时间戳随 clock 进动递增 + password 原值）；④ fuyun-app 三个 AMQP IT 的 broker 建号（用户名/标签/权限三命令）与生产者凭证同步（固定时钟下 username 三段字面可预置，固定时钟机制保留）。

## 2026-09-12 · 缺陷修复：禁用 JMS 健康指标，消除无凭证探测致 backend 容器 unhealthy（先记再改）

- **缺陷链（本地 compose 联调实证）**：启用 `FUYUN_IOT_AMQP_ENABLED=true` 对接华为云 IoTDA AMQP 后，Spring Boot actuator 的 JmsHealthIndicator 自动探测 classpath 上的 Qpid JMS ConnectionFactory 并发起**不带凭证**的连接——IoTDA 强制 SASL PLAIN 鉴权，探测恒失败（JMSSecuritySaslException）→ actuator/health 聚合 DOWN → compose healthcheck（探 actuator/health 要求 UP）判 backend 容器 unhealthy → iot-simulator（depends_on service_healthy）无法启动。
- **修复裁决**：`application.yml` 增 `management.health.jms.enabled: false` 禁用 jms 健康指标。理由：实际业务消费者带凭证 `createContext(accessKey, password, CLIENT_ACK)` 工作正常，探测语义对本架构无意义且有害；AMQP 链路真实状态已由自研 Micrometer 指标（iot.amqp.connected / disconnect.duration.seconds / reconnect.total 等）承载。
- **P1 完整化方向**：自研 HealthIndicator 反映 iot.amqp.connected 真实链路状态，纳入 readiness 聚合后再评估替代本禁用项。

## 2026-09-11 · PR-5 CI 门禁缺陷修复：移除骨架期 pom/webpkg 构建守卫，恢复后端/前端门禁触发（先记再改）

- **缺陷实证（PR #8，CI run 34645973742）**：changes job 判定输出 `Filter backend = true`、`Filter pom = false`，backend job 双条件 `needs.changes.outputs.backend == 'true' && needs.changes.outputs.pom == 'true'` 为 false → `backend / verify` skipping——本 PR 明确含后端改动（fuyun-iot 鉴权迁移 + fuyun-app IT，9 个 java 文件）却未跑后端门禁，属于严格门禁模型（方案 B）下的门禁绕过缺陷。
- **根因与守卫退役原因**：`pom: 'backend/**/pom.xml'` 与 `webpkg: 'web/**/package.json'` 两个过滤器是 PR-1 骨架期的「构建文件存在性守卫」——骨架期 pom.xml/package.json 尚未落盘时防止构建 job 空跑；骨架早已落盘后该守卫存在前提消失，语义退化为「diff 必须触碰 pom.xml/package.json 才跑门禁」，改 java/vue 不碰构建文件的后端/前端改动全部绕过 verify 门禁，故按死配置退役删除。
- **PR-2~PR-4 未暴露原因**：各 PR 均恰好新增模块/依赖（必碰 pom.xml 或 package.json），守卫条件恒为 true，双条件退化等价于单条件，缺陷未显形。
- **修复面**：backend/frontend job 的 if 删除 pom/webpkg 条件（仅保留路径变更单条件）；changes job 的 pom/webpkg 过滤器定义与 outputs 映射一并删除（全仓 grep 核实 `outputs.pom`/`outputs.webpkg` 无其他消费方；setup-java 的 `cache-dependency-path: backend/**/pom.xml` 与 pnpm 的 `package_json_file: web/package.json` 属工具自身参数，与该 output 无关，不受影响）；五 checks 名（backend / verify、frontend / verify、images、commitlint、hygiene）与其余 filter/job 结构零改动。

## 2026-09-11 · PR-5 独立审查修复：/ws/iot 鉴权点迁移（HTTP 握手层→STOMP CONNECT 帧）、断线重订阅读断言与 bigscreen 状态机/traceId 修复（先记再改）

- **Finding 1（Critical，跨栈）**：fuyun-iot 鉴权点由 HTTP 握手层迁移至 STOMP CONNECT 帧级——浏览器原生 WebSocket API 无法携带自定义 HTTP 头，stompjs connectHeaders 只进入建连后的 CONNECT 帧，原 `StompHandshakeAuthInterceptor` 读 HTTP 升级头对浏览器客户端必然 401（端到端永远无法建连）。迁移后 /ws/iot 升级端点允许匿名建立 WebSocket 传输层，但任何 STOMP 会话必须先通过 CONNECT 帧令牌校验方可 CONNECTED——SimpleBroker 仅在 CONNECTED 后接受 SUBSCRIBE，未授权会话无法订阅/收发任何数据（订阅前无数据暴露），鉴权时点仍先于一切数据通道，安全等价。拒绝语义（spring-websocket 6.2.19 `StompSubProtocolHandler` 字节码实证）：帧级 ChannelInterceptor 抛 MessagingException → 服务端回 ERROR 帧（message=不含令牌与原因的固定摘要，防枚举）→ 随即以 CloseStatus.PROTOCOL_ERROR 关闭连接。`StompHandshakeAuthInterceptor` 及其单测删除，鉴权逻辑全部迁至 `StompConnectAuthInterceptor`（clientInboundChannel 挂载）；`IotTelemetryPipelineIT` 改为 CONNECT 头承载令牌（与生产浏览器客户端同通道）并补无/错令牌拒绝负路径用例；docs/specs 14-iot §WebSocket 两处「握手鉴权」表述同步（接口契约同步条款）。web 端零改动理由：前端 connectHeaders 注入方式本就承载于 CONNECT 帧，迁移后与帧级拦截器天然对齐，仅修正注释中「握手层」表述。
- **Finding 2（Critical，T-R4-2 实测结论回填）**：stompjs 7.3.0 断线自动重连后无自动重订阅（onWebSocketClose 时 _stompHandler 整体作废，库内不重建订阅）——bigscreen useIotStomp 在 onWebSocketClose/onStompError 将在册订阅句柄置 null（旧句柄已随连接作废），onConnect 无条件重订阅（与首连复用同一 doSubscribe 落地方法），消除「徽标已连接、零帧流入」假连接。**T-R4-2 结论**：stompjs 7.3.0 无自动重订阅，客户端须在 onConnect 重订阅，已在 PR-5 落码；TASK.md 该行按登记台规则回填删除。
- **Finding 3（Important）**：stompjs activate() 对已激活 Client 为 no-op，connect() 无条件置 connecting 使已连接换病区再点连接卡死 connecting 态（断开按钮 v-if connected 消失）——已连接（client.connected=true）时改为保持 connected 态、不置 connecting、不重复 activate，订阅切换由紧随其后的 subscribeTelemetrySummary 已连接分支承接（与断线重连重订阅复用同一内部方法，防两处订阅逻辑漂移）。
- **Finding 4（Important）**：crypto.randomUUID 带 [SecureContext] 限定，仓库拓扑 nginx :80 无 TLS、内网 HTTP 访问下为 undefined（TypeError）——useIotStomp traceId 生成加守卫降级（时间戳+随机数组合串，仅作日志锚点非密码学用途）。

## 2026-09-11 · PR-5 B5.2：P0 收口事务——W-3 销项、T-R3 回填核对、计划完成项标注与 DoD 预检落盘（先记再改）

- **W-3 销项（逐项核实后删除，禁盲删）**：三项对齐逐一实测复核达成——① `backend/Dockerfile` 26 条显式 COPY 逐模块（含 fuyun-iot/iot-simulator POM 行），glob 拍平已消除（台账 B1.2 行 complete）；② web 产物路径三处同路径（compose 三应用 dist bind mount + web/Dockerfile 三条 `COPY --from=build .../apps/<app>/dist` + nginx 三 location alias，均为 W-3 裁决口径 `web/apps/<app>/dist`）；③ `ci.yml` 无骨架期排除项（changes 过滤器仅永久 `*.md` 排除，images job 三镜像构建步骤在位，台账 B1.3 行 complete）——W-3 整行删除；W-4/W-5/D-8/L-1~L-4/T-R4-2 等行一律不动。
- **T-R3-2/T-R3-3 回填核对（无文件改动，声明核对结论）**：T-R3-2 原行已于 PR-4 B4.1 实测回填删除（结论 = `add_columnstore_policy` 胜出，见 2026-09-10 B4.1 条目收口补记），TASK.md 待调研表现无该行；T-R3-3 原行已于 B4.4 回填删除，本地两级实测结论（supervisor 单测 + IotAmqpReconnectIT）并入 TASK.md L-2 行，核对在位且表述完整。
- **计划完成项标注（最小内联标注法，禁改正文语义）**：`docs/plans/2026-09-08-P0实施计划.md` §1 五个 PR 标题行尾对 PR-1~PR-4 追加「——已完成（PR #N，dev@<hash>）」四处标注，合入点以台账记录为准（#4/ed5e34e、#5/a019f47、#6/a93179a、#7/9107f92）；PR-5 行不自标（合入时点未知，随 P6 终验补记）；§3 DoD 五条不动——勾选属 P6 终验，提前打勾即伪造证据。
- **DoD 预检报告落盘**：新增 `docs/plans/2026-09-11-P0-DoD预检.md`——对交付 loop §5 七条 DoD 逐条预检（已满足 / 待 P6 终验附证据 / 延后条款豁免三态，附验证命令与证据来源）；属 PR-5 时点预检而非终验勾选，终验逐项附证据归 P6。

## 2026-09-11 · PR-5 B5.1：bigscreen 最小遥测页与 STOMP 单例封装、workstation 首页骨架（先记再改）

- **依赖申报（表外申报①，随本批次首个功能提交生效）**：bigscreen app 级 package.json 新增 `@stomp/stompjs` **7.3.0**（版本来源=技术栈定稿 §4.1 与 web 宪法 C.2 唯一权威值，非新值）；**申报位置=app 级而非 catalog**——依据 pnpm-workspace.yaml 第 2 行既有注释先例（「业务独立依赖不进 catalog：……echarts/@stomp 待 PR-5 再引」），与 axios 跨 app 共享进 catalog 的口径不同；lockfile 随同一提交更新。
- **bigscreen 最小遥测页**：`/ws/iot` STOMP 单例封装（web 宪法 B.3-3 逐条款：Client 首次 connect 惰性单例、重连心跳全交库内建固定间隔 10s 禁自研循环、订阅句柄组件卸载统一退订、token 经 beforeConnect 每次连接尝试实时读 sessionStorage 键 `fy:bigscreen:iot-token`、onStompError/onWebSocketClose 统一日志含主题与 traceId 禁打令牌）；首页原位改造三区——连接设置（wardId 路由 query 可书签化 + 令牌 password 输入）、链路状态（徽标/订阅主题/帧计数）、遥测摘要（最近一帧覆盖渲染，count/occurredAtUpperBound 原样展示/items 明细表）；手写后备类型 types/iot.ts（openapi-typescript 生成链路不覆盖 STOMP 载荷，字段与后端 ITelemetryPushService record 逐字对齐并声明漂移风险）与 unknown 收窄解析 utils/iotMessage.ts；不引 echarts、不订设备状态主题（P5 负面清单，简报 §0）。
- **workstation 首页骨架**：HomeView 原位改造两区——会话问候（displayName/loginName 取既有 auth store，空值兜底「未登录用户」防御文案）+ 业务开通占位卡（文案与 AppSidebar 占位口径一致）；零新增依赖、零 api/store/路由改动、零出网调用（P0 无首页数据接口，禁止推测性调用）。
- **宪法 B.3-3 措辞差异关注项（不阻塞，简报附 1）**：条款括号「reconnectDelay 指数退避」与 @stomp/stompjs 7.3.0 内建实况（固定间隔毫秒值，无内建指数退避）存在措辞出入，本 PR 按库内建固定间隔 10000ms 落地、绝不自研退避循环（合规核心=重连完全交库内建）；措辞修订随 P1 workstation 接入 STOMP 时走修宪流程（先记 CHANGELOG 再改正文），本 PR 不动宪法。

## 2026-09-11 · PR #7 独立审查修复：AMQP 确认语义修正（累计确认丢数窗口）与 simulator MQTT 鉴权凭证补齐（先记再改）

- **Finding 1（Critical，确认语义设计前提被证伪）**：JMS `CLIENT_ACKNOWLEDGE` 为会话级累计确认（JMS 规范 §4.4.11）——对同会话任一消息 `acknowledge()` 会一并确认此前全部未确认交付。原设计「落库失败零回调→帧留待 IoTDA 重推」只在会话/连接重建时成立：真实时序下失败批 [A,B] 未确认，后续成功批 [C,D] 的批末确认会把 A、B 一并累计确认，broker 不再重投，数据无痕丢失；状态帧 `apply` 失败帧同根缺陷（被后续成功状态帧确认吞掉）。
- **修复裁决（审查方向①，失败即重建会话）**：落库失败（攒批 flushBatch）与状态帧业务失败（dispatchSafely 业务异常域）统一触发既有 supervisor 全局重建路径——关闭全部在册上下文，会话销毁令其全部未确认交付回归 broker 重投域，重投帧由 iot_telemetry 唯一约束 ON CONFLICT DO NOTHING 幂等去重；worker 线程的业务失败在触发重建后仍上抛走既有退避（防 DB 持续故障下无退避热循环）。线程安全：复用 onException 同款机制（volatile 引用置换 + CopyOnWriteArrayList 遍历 + 幂等关闭），攒批 flush 线程与消费线程并发触发无新锁。在途帧处置：失败瞬间清空攒批挂起队列（在途帧均为已交付未确认态，且清空先于上下文关闭，其会话销毁后必然回归重投域——丢弃语义自洽）；极小窗口内旧会话帧再入队时其确认失败将再次触发重建直至收敛（幂等无害）。javadoc 旧「落库失败零确认待重推」表述一并改写为真实语义。
- **Finding 2（Important，iot-simulator）**：`IotdaMqttClient.connect` 构造 MqttConnectOptions 从未设置 username/password，真实 IoTDA 一机一密鉴权（CONNECT 报文 username=deviceId、password=HMAC 摘要）必然拒绝——connect 补 `setUserName`/`setPassword`，修正「凭证随 clientId 构造生效」错误注释，单测补 options 携带凭证断言。

## 2026-09-11 · PR-4 终审修复：sim 全链路 backend 侧 AMQP 启用接线闭环（先记再改）

- **问题（终审 Finding 1，Important）**：`fuyun.iot.amqp.enabled/queues` 在 application.yml 硬编码 `false`/`[]` 无 env 占位，deploy 编排未透传启用开关与队列清单——用户按 .env.example 填齐 IOTDA_* 六变量后 `docker compose --profile sim up`，AMQP 消费链仍静默 disabled，TASK.md L-1 延后演示路径不通；且手动 enabled=true 而未配 queues 时 fail-fast 全栈不可用无前置提示。
- **修复范围**：① application.yml 两键改 env 占位（`FUYUN_IOT_AMQP_ENABLED:false` / `FUYUN_IOT_AMQP_QUEUES:` 空占位）+ 中文注释说明逗号分隔格式与默认值语义；② IotProperties.validateAmqpEnabled 增空白队列名 fail-fast 校验（空串绑定实测 + 单测固化）；③ .env.example 增两变量占位与注释；④ docker-compose.yml backend environment 增两行透传；⑤ TASK.md L-1 行补启用前提说明。deploy 透传带 `:-` 默认值属有据偏差：实测 enabled 绑定不接受空串（boolean 绑定失败阻断启动），`:-false` 防 .env 缺键/留空，environment 段优先级高于 env_file 可覆盖整组注入的空值。
- **queues 空串绑定实测结论（ApplicationContextRunner 实测，2026-09-11）**：空串 env 经 relaxed binding 绑定为**空列表**（size=0，非 null、无空串元素），enabled=true 时由既有启用组 @NotEmpty fail-fast（中文报错），单测固化该绑定语义；但含空段的 env（如 `q1,,q2`、尾逗号、空白项）绑定为**含空串元素**的列表，@NotEmpty 只拦整体缺失放行无效元素——validateAmqpEnabled 增空白队列名显式拒绝（fail-fast 中文报错），单测固化。

## 2026-09-11 · PR-4 B4.4：T-R3-3 本地实测重要发现——Qpid failover 透明恢复屏蔽 supervisor，AMQP URI 补正官方选项语法并改有限重试移交（先记再改）

- **实测发现（IotAmqpReconnectIT 首跑 RED 留证，2026-09-11）**：`rabbitmqctl stop_app` 优雅断链下，Qpid failover 传输层做纯透明恢复——ExceptionListener 不触发、阻塞中的 receive() 持续等待重连、消费者 supervisor 全程未介入（`iot.amqp.connected` 恒 1、`iot.amqp.reconnect.total` 恒 0，断链时长指标恒 0）。推演生产语义：IoTDA 真实断链超 5 分钟后，failover 仍以连接建立时捕获的旧时间戳凭证无限重试（`failover.maxReconnectAttempts=-1`），被服务端拒绝后永续循环且消费链路无感知——supervisor 的「新时间戳凭证重建」被完全屏蔽，宪法 A.5-9 的 supervisor 语义落空。
- **对策（AMQP 连接 URI 修正为官方 failover 选项语法 + maxReconnectAttempts 改有限值移交 supervisor）**：① 选项前缀修正——qpid-jms 官方文档「Client configuration」明确 failover 选项语法为 `failover.` 前缀形态（failover.initialReconnectDelay / failover.reconnectDelay / failover.maxReconnectDelay），B4.2 起的裸名形态不会被 failover 层识别为选项（语义等同未配置，三值 3s/3s/30s 从未真实生效），本次按官方语法补正前缀、取值零变化。② 移交机制——qpid-jms 2.11 官方选项表核对（来源 qpid.apache.org/releases/qpid-jms-2.11.0/docs）**无 timeout 类移交参数**（`failover.timeout` 属 ActiveMQ failover 词表，Qpid 下装配即报「Failed to create JMS Provider instance for: failover」，第二次 RED 实测留证）；官方选项表内唯一移交机制为有限 `failover.maxReconnectAttempts`——由宪法/简报锁定的 -1 改为 3：provider 连续重试 3 次放弃后连接失败（ExceptionListener 触发 / receive 失败上抛），控制权移交 supervisor 以新时间戳凭证无限重建，「无限重连」语义上移到凭证刷新层（每次重建刷新 13 位时间戳，正对 IoTDA 5 分钟拒绝语义）；瞬时抖动（≤3 次重试约 9s 内）仍走透明恢复。**偏差申报**：此为**简报 §1.3 预判值**（-1，非宪法条文）的实测修正（归源更正见 2026-09-15 D-8 条目）——无限重连语义在 supervisor 层完整保留，总重连次数不设上限，仅传输层透明重试限 3 次；真实 IoTDA 端点的等价行为验证随 TASK.md L-2 联调演示回填。

## 2026-09-10 · PR-4 B4.4：iot-simulator 子模块、表外依赖核实与一机一密算法官方核对（先记再改）

- **iot-simulator Maven 子模块申报（D-3 默认裁决，纯 Java 零 Spring）**：父 POM `<modules>` 增 `iot-simulator`（fuyun-app 之后）；新增 `backend/iot-simulator/Dockerfile`（多阶段独立镜像，与 backend/Dockerfile 七条规范对齐）；`backend/Dockerfile` 两处小改——pom COPY 清单追加 `COPY iot-simulator/pom.xml iot-simulator/` 一行 + 删除尾部 `# TODO(iot-simulator)` 注释行；CI images job 追加第三构建步骤「构建镜像（iot-simulator）」（同构显式步骤、禁 matrix、job 名 `images` 不变、cache scope=iot-simulator、step 级 if 与 backend 步骤同条件）并删除第 149 行 TODO 注释。
- **表外依赖核实与申报（简报 §10 要求落码前以 Maven Central 元数据核实）**：① `org.eclipse.paho:org.eclipse.paho.client.mqttv3` **1.2.5**——Central maven-metadata 实测 `<release>`/`<latest>` 均为 1.2.5（Eclipse Paho 官方最新稳定行，技术栈定稿未收录 MQTT 客户端），版本经父 POM dependencyManagement 集中声明（属性 `paho-mqtt.version`，遵循「BOM 外依赖集中声明、子模块禁自带版本号」宪法口径，qpid 2.11.0 先例）；② `maven-jar-plugin` **3.4.2**——Central versions 清单核实存在（3.4.2 命中 1 行）；③ `maven-dependency-plugin` **3.8.1**——Central versions 清单核实存在（3.8.1 命中 1 行，目录探针 HTTP 200），simulator 模块内显式锁定。
- **一机一密连接三元组官方核对结论（简报 §5 要求实现期核对，来源：华为云 IoTDA 官方文档《密钥鉴权_MQTT(S)协议接入》support.huaweicloud.com/devg-iothub/iot_02_0203.html）**：clientId = `{deviceId}_0_0_{时间戳}`（第 2 段固定 0=设备 ID 标识、第 3 段 0=HMACSHA256 不校验时间戳准确度但仍须携带时间戳，官方生成工具默认形态）、username = deviceId、password = **HmacSHA256(key=UTC 时间戳, message=deviceSecret) 小写十六进制**——时间戳格式为 **UTC `yyyyMMddHH`（10 位，小时粒度）而非简报预判的 13 位毫秒**，HMAC 方向为时间戳作密钥、secret 作内容（官方示例实测复算一致：secret=12345678、timestamp=2025041401 → `c75150e6cb841417396819e4d2ee4358a416344a03a083e3a8567074ddec820a`，与文档原例逐字符相同）。**与简报 §5 预判口径（13 位毫秒时间戳）偏离，以官方文档为准落码**，核对结论写入 DeviceCredentialEncoder javadoc；若真实联调发现服务端行为出入，改动面仅该类 + 单测。
- **T-R3-3 本地两级实测与延后登记预告**：supervisor 单测（B4.2 已交付）+ 本地 broker 断链恢复 IT（IotAmqpReconnectIT，本批次交付）构成代码级实测闭环；「真实 IoTDA 端点 10 分钟断链演示」与 `--profile sim` 全链路演示、真实 IoTDA 规则引擎报文映射冻结（P0 线格式=CF-7）、真实积压水位指标（IoTDA 侧最旧未消费消息年龄）四条一并延后登记 TASK.md（本批次收口提交执行）。

## 2026-09-10 · PR-4 B4.3 审核修复

- **F-1 摘要推送违反宪法 A.4.2-7**：TelemetryIngestServiceImpl 在 @Transactional ingest 事务方法内直推 STOMP 摘要（"进程内直推非 MQ"自我解释不成立，宪法原文"事务内禁止远程调用、消息发送与人工等待"不限 MQ）——修复为 TransactionSynchronizationManager 注册 afterCommit 回调执行既有 pushSummariesByWard（分组数据事务内组装、推送 I/O 移出事务）；isSynchronizationActive=false（单测直调无事务）时保持直推行为不变，相关 javadoc 同步修正；单测补 TransactionTemplate 时序断言（事务内不推、提交后推送）。
- **F-2 状态主题生产死路径（wardId 恒 null）**：P0 状态帧契约不含 wardId、解析产物恒 null，消费者原样发布致 /topic/iot/device-status/{wardId} 生产无数据源——IDeviceStatusService.apply 返回值 boolean→Long（模块内接口，返回设备档案 ward_id；null=设备不存在/条件未命中/档案未编病区，不发布事件），select 投影增补 ward_id；IotAmqpTelemetryConsumer.handleStatusFrame 以返回 wardId 构造含 wardId 的事件再发布；IotTelemetryPipelineIT 步骤 5 恢复 AMQP→STOMP 全链断言（不再以手工信封替代链路）、步骤 6 改从 integration.received_event 台账读取真实已消费 eventId 重建重投。

## 2026-09-10 · PR-4 B4.3 任务 B：STOMP/WebSocket 依赖申报与 deploy 兜底密钥变更（先记再改）

- **fuyun-iot pom 依赖申报（BOM/父 POM 托管零版本声明，PR 描述申报）**：① `org.springframework.boot:spring-boot-starter-websocket`——`/ws/iot` STOMP 端点（IotWebSocketConfig：@EnableWebSocketMessageBroker + 内存 SimpleBroker(/topic) + 无 SockJS，P0 客户端仅 PR-5 bigscreen 原生 WebSocket）；② `io.micrometer:micrometer-core`——IotAmqpMetrics 三 gauge（iot.amqp.connected / disconnect.duration.seconds / batch.queue.fill.ratio）与双 counter（reconnect.total / batch.flush.failure.total）注册的 MeterRegistry 编译依赖；③ `com.fuyun:fuyun-system`——仅消费 api 包 TokenVerifier（任务 A 已交付契约）作 STOMP 握手鉴权，宪法 B.2-2 合规。
- **deploy 变更面申报（简报附 1 待裁决项，主控已裁决 nginx /ingest 路由纳入本 PR）**：`.env.example` 增 `FUYUN_IOT_FALLBACK_TOKEN=` 空占位（独立行中文注释"必填：IoT 兜底通道共享密钥，禁止提交真实值"）；`docker-compose.yml` backend environment 增同名透传一行；`deploy/nginx/fuyun.conf` 增 `location /ingest/` 反代——兜底端点 `POST /ingest/iotda-fallback` 不在 `/api/v1` 前缀下，既有 `/api/`、`/ws/` 两条路由无法覆盖，唯一公网入口原则下的路由缺口补齐（对齐既有 /api location 写法）。
- **fuyun-app application.yml 配置占位**：增 `fuyun.iot.fallback.token: ${FUYUN_IOT_FALLBACK_TOKEN:}` 映射（B4.2 已落 IotProperties.Fallback 嵌套 record，本次补 yml 环境变量映射行）；空默认 = 未配置，兜底鉴权比对侧 fail-closed 一律拒绝（IOT-1001）。

## 2026-09-10 · PR-4 B4.3：TokenVerifier 跨模块小改与 iot 扇出依赖申报（先记再改）

- **跨模块小改申报（D-7 先例，随本批次首个功能提交生效）**：fuyun-system api 新增 `TokenVerifier` 接口（`boolean verifyAccessToken(String rawToken)`——校验 access 令牌全链（签名/过期/typ/会话存在），通过 true、任何失败 false 不抛异常且不区分原因防枚举，适配 WebSocket 握手与 MQ 线程等无 ProblemDetail 出口场景）；`TokenServiceImpl` implements 增补（内部委托既有 verify(ACCESS) 校验链，捕获 BizException 返回 false）；`SystemWebConfig` 增补一行 @Bean 以接口类型暴露同一实例。消费方：iot /ws/iot STOMP 握手鉴权（本批次仅交付契约与单测，握手拦截器随任务 B）；fuyun-iot 后续仅依赖 system api 包（宪法 B.2-2 合规）。接口属对外契约新增，PR 描述申报。
- **fuyun-iot pom 依赖申报（BOM/父 POM 托管零版本声明，B4.2 审核 Minor 4 遗留项补齐）**：① `com.fuyun:fuyun-integration`——IotMessagingConfig 经 api 包 MessagingGovernance/ConsumerQueueSpec 声明自事件消费队列 q.iot.iot.device.status-changed（V403 已登记，先登记后订阅；system pom 先例）；② `org.springframework.boot:spring-boot-starter-amqp`——IotEventPublisher RabbitTemplate 发布与 IotFanoutListener @RabbitListener 消费（AUTO 确认 + MessageIdempotencyService 标准幂等范式，与 AMQP 主链路客户端确认两套机制并存）。
- **实现偏差申报（简报 §4 IotEventPublisher"Confirm/Returns 回调 SystemEventPublisher 同模式"）**：Spring AMQP 对共享 RabbitTemplate 强制断言仅支持单一 Confirm/Returns 回调（注册第二个不同实例即启动失败，IT 实证）——双发布器并存下"各自注册"不可成立，故回调保持由 PR-3 交付的 SystemEventPublisher 构造期统一注册（与装配顺序无关：iot 发布器不注册），iot 发布的 nack/不可路由告警复用同一回调（error 日志含 eventId/路由三要素、P0 不自动重发，语义等价）；回调归属整合（如 RabbitTemplateCustomizer 收口治理装配）归 P1 治理完整化，届时 iot 侧零改动。

## 2026-09-10 · PR-4 B4.2：JaCoCo 核心包增补 iot service.impl（先记再改）

- **POM 门禁变更登记**：父 POM JaCoCo PACKAGE 级 LINE=1.00 规则 include 清单增补 `com.fuyun.iot.service.impl`，与首个 iot service.impl 实装类同一提交生效（BRIEF-PR4-01 §7 处置结论）——iot 消费落库链属"对外服务接口（第三方对接）"核心功能（全局 §四核心界定），沿用 backend 宪法 C.5-2"核心包 rule 随模块实装逐步声明"既有模式（integration/billing/system 三包先例）；SmartLifecycle 消费器/监听器/解析器落 internal/ 包按 BUNDLE 0.80 承载，1.00 规则不误伤难测基础设施类。本项属 PR-4 表外申报清单预告项（B4.1 条目已预告），PR 描述重申申报。

## 2026-09-10 · PR-4 B4.1：iot 号段占用登记（先记再改）

- **号段登记（TASK.md W-4 载体）**：iot 域（M14）占用 **V400–V499**，本批（PR-4 B4.1）使用 V400–V403——V400 设备档案与绑定表、V401 消费错误日志表、V402 遥测超表与压缩/保留策略（T-R3-2 实测锁定）、V403 设备状态事件种子登记。核对结论：现存迁移仅 integration V1–V5 与 system V300–V303，V400–V499 无冲突。
- **PR-4 表外申报预告**（简报 §10 清单，随各批次落地逐项申报）：qpid-jms-client 2.11.0（父 POM 已锁）、Boot BOM 托管 starter 集合（validation / websocket / amqp / micrometer / mybatis-plus / mapstruct / lombok 等）、Eclipse Paho MQTT 客户端 1.2.5 与 maven-jar-plugin 3.4.2 / maven-dependency-plugin 3.8.1（iot-simulator）、父 POM modules 增 iot-simulator + JaCoCo 核心包增补 `com.fuyun.iot.service.impl`、fuyun-app pom 增 fuyun-iot 依赖、fuyun-system api 增 TokenVerifier 接口、deploy 增 FUYUN_IOT_FALLBACK_TOKEN 占位与 nginx `/ingest/` 路由、CI images job 追加 iot-simulator 第三构建步骤。
- **T-R3-2 实测结论（收口补记）**：压缩策略函数胜者 = `add_columnstore_policy`（2026-09-10，`timescale/timescaledb:2.29.2-pg16` 探针容器实测）——探针 SQL「`SELECT proname FROM pg_proc WHERE proname IN ('add_columnstore_policy','add_compression_policy') ORDER BY 1;`」输出两函数均存在；`pg_proc.prokind` 实测 `add_columnstore_policy = p`（过程，须 `CALL` 调用）、`add_compression_policy = f`（函数，自 2.18.0 起弃用），两函数并存以非弃用者为胜 → V402 压缩策略以 `CALL add_columnstore_policy('iot.iot_telemetry', INTERVAL '7 days')` 落盘；保留策略 `add_retention_policy` 实测 `prokind = f`（SELECT 函数，非 T-R3-2 比对项）照常调用。样例超表实证：CALL 后 `timescaledb_information.jobs` 落 `policy_compression` 作业、`SELECT add_retention_policy` 落 `policy_retention` 作业（均 scheduled=true）。TASK.md T-R3-2 行按登记台规则回填后删除。

## 2026-09-10 · PR #6 审查修复（Minor×2：脱敏正则数字边界 + 字典发布条件更新防双广播）

- F-1（fuyun-common/utils/SensitiveMasker.java）：PHONE/ID_CARD_15/ID_CARD_18 三正则补前后视数字边界（`(?<!\d)...(?!\d)`）——原实现对长数字串（12 位工单号/19 位雪花 ID 等）内部会误命中截断，与 javadoc「非目标长度不处理」承诺矛盾；SensitiveMaskerTest 补 12+/19 位数字串不脱敏断言（先 RED：19 位串现行实现被误脱敏，修复后 GREEN）。
- F-2（fuyun-system/service/impl/DictVersionServiceImpl.publish）：DRAFT 读-检-写改为条件更新原子抢占发布权（`UPDATE ... WHERE id=? AND status='DRAFT'`，MP 单表链式 A.4.3-13）——并发双 publish 原先双双通过前置校验并触发两次 AFTER_COMMIT 广播；现仅影响行数=1 者继续旧版本 DEPRECATED 与事务内事件，=0 抛 SYS-1013（竞态落败方不发事件）；DictVersionServiceImplTest 改条件更新语义并补「已发布版本重复 publish 不发事件」「竞态落败不发事件」两断言（先 RED 后 GREEN）。

## 2026-09-10 · PR-3 B3.4：workstation 登录页 + 主布局 + Axios 单例（前端接入认证链路）

- Axios 单例（src/api/http.ts，web A.3-1 唯一出网口）：baseURL = VITE_API_BASE_URL ?? '/api'、timeout 15s 模块级导出；请求拦截器注入 `Authorization: Bearer {token}`（useAuthStore 延迟到回调运行时调用，web B.3-1 组件外口径）+ 每请求唯一 `X-Trace-Id`（crypto.randomUUID，后端 TraceIdFilter 复用为 MDC 锚点并回写响应头）；响应拦截器统一错误出口——非 2xx 提取 ProblemDetail.detail 经 ElMessage 统一提示（缺省回退「请求失败」）、401 触发注册的未授权回调；`setUnauthorizedHandler` 回调解耦（http.ts 禁反向 import router，防循环依赖），拦截器内不落业务逻辑（A.3-2）。
- 认证 api 与后备类型：src/api/auth.ts 三类型化函数（login/refresh/logout，路径 /api/v1/system/auth/* 与后端 B3.2 契约对齐）+ src/types/auth.ts 手写后备类型 LoginRequest/LoginResponse/UserVO（web A.3-3 后备条款，文件头标注 openapi-typescript 生成物就位后由 packages/shared api.d.ts 承接并删除本文件；userId/orgId 按 Long→String 规则一律 string 承载，orgId 可 null）。
- 认证会话 store（src/stores/auth.ts，Pinia Setup Store web B.3-1）：token/refreshToken/user 三态 + sessionStorage 持久化（键 fy:workstation:auth，医疗工作站「换机即失效」语义：登录写回、登出清除、构造时恢复）+ getter isLoggedIn；action login（调 api→写 state→持久化）/logout（api 失败忽略→清 state→await 跳转 /login）/loadFromStorage；快照读入经 unknown 收窄类型守卫（损坏 JSON 丢弃并清残留键）；构造时注册 401 未授权回调（清会话 + 回登录页，登录页内重复导航跳过）。
- 路由与守卫（src/router/index.ts 改造）：/login（meta.public 免认证）+ / 主布局（MainLayout 懒加载）嵌套 '' home（HomeView 迁入，路由组件全懒加载）；beforeEach 只做认证判定（web B.3-2 分层）——默认拒绝：非公开路由未登录重定向 /login 并携带 redirect 回跳地址；已登录访问 /login 回首页防死循环；RouteMeta 声明合并增补 public 字段承载权限语义（权限点校验 P1 接入）。
- 视图层：LoginView（el-form 声明式校验必填 + 长度 4-64、提交 loading 防重复、错误提示走拦截器统一出口组件内不重复弹错、redirect 仅接受站内根相对路径防 open redirect、回车与按钮双提交入口、useTemplateRef 3.5 基线）；MainLayout（el-container 侧栏 + 顶栏 + 内容区三段骨架）；AppSidebar（el-menu 静态菜单：首页 + 占位分组文案，权限驱动菜单 P1 不接角色接口）；AppHeader（系统名 + el-dropdown 用户区显示 displayName、command=logout 走 store.logout 保证清 sessionStorage 并回登录页）。
- 单测（TDD 先行 RED 留证 → 实现 GREEN，vitest + @vue/test-utils + jsdom，ElMessage/网络全部 mock 不打真实请求）5 文件 17 例：http.spec 5（Bearer 注入与每请求唯一 X-Trace-Id、响应头透出、无令牌不注入、非 2xx 提示 detail 不触发登出、401 触发回调、缺 detail 回退文案）；stores/auth.spec 5（登录写 state+sessionStorage、登出清空并回登录页含 api 失败路径、isLoggedIn 翻转、会话恢复、损坏数据防御）；router.spec 3（public 直通、默认拒绝含回跳地址、已登录防死循环）；LoginView.spec 2（空提交被校验拦截、有效提交透传凭据并跳首页）；App.spec 冒烟因守卫失效同步改造为两条（未登录重定向 + 注入会话后首页可达）——因本次改动失效旧测试直接改造（简报 §4 / 全局 §四）。
- 测试基建表外申报两处（简报 §4 未列，实现必需）：① workstation vitest.config.ts 增 unplugin-vue-components + unplugin-auto-import 两件套（与 vite.config 同款）使单测真实装配 Element Plus 组件（表单校验/交互行为断言的前提），并经 server.deps.inline=['element-plus'] 内联其按需样式模块（Node 原生加载 .css 报错，交 Vite 管线按 css:false 桩化）；② tsconfig.app.json 增 skipLibCheck=true（Vue 官方脚手架标准配置）：element-plus 传递依赖 @vueuse/core 的 d.ts 引用标准 lib 外的 WebBluetooth 类型、其 dropdown 声明与 vue-tsc 3.3.10 存在库内兼容问题，跳过第三方声明文件自检，本项目源码类型检查不放宽。
- 杂项：workstation package.json dependencies 增 axios: catalog:（catalog 已锁 1.20.0，lockfile 同步更新并通过 --frozen-lockfile 校验）；删除 api/stores/types 三目录 .gitkeep（首个真实文件落地即删占位，PR-2 先例）。
- 门禁验证：cd web 后六门禁逐个真实执行全绿——pnpm lint（--max-warnings=0，0 错误）、pnpm format:check（All matched files use Prettier code style!）、pnpm type-check（workstation/portal/bigscreen/ui/shared 五项目 vue-tsc Done）、pnpm test（7 文件 19 例全过）、pnpm build（三应用 dist 产物构建成功）、pnpm audit --audit-level high（No known vulnerabilities found；本地 npmmirror 无 audit 端点，经官方 registry 执行，与 CI 同命令语义）。

## 2026-09-10 · PR-3 B3.3：审计切面 + practice/check 骨架 + 登录链路与字典广播端到端 IT

- 审计切面（BRIEF-PR3-01 §3.3）：api/AuditLog 注解（actionType，落 api 包为对外契约底座）+ internal/AuditLogAspect（@Around @annotation 环绕）——proceed 成功记 SUCCESS、任意异常记 FAIL 后原样 rethrow；fail_reason 经 SensitiveMasker 脱敏并 500 字符截断（V302 列宽防线），detail 请求参数摘要对口令/令牌入参显式打码（record 整对象 toString 会带出敏感明文，禁用）并 1000 字符截断；落库 try-catch 全吞仅 error 告警绝不阻断业务（M01 红线）；P0 同步写（controller 层、业务事务外、try-catch 告警），异步批量 P1（简报 §9-5）。注解落点：AuthController login/logout（LOGIN）、DictTypeController/DictVersionController 四写端点（WRITE）；practice/check 与字典读为查询 P0 不审计。
- 实现口径定案两处（简报未明确处，PR 描述同步申报）：① 免认证登录端点无 OperatorContextHolder 上下文，审计操作人回退取入参 LoginRequest 登录名作审计主体（AuthFlowIT 步骤 7 operator_id=admin 的语义来源），无上下文且无登录名入参兜底 system（与 created_by 系统操作口径一致）；② traceId 取 MDC（TraceIdFilter 前置必可用）、resource/client_ip 取 RequestContextHolder 当前请求（非 HTTP 线程兜底 unknown）。
- 审计配套：IAuditLogService/AuditLogServiceImpl（只增表唯一写入口，mapper.insert 单语句自原子不开方法级事务）+ AuditLogEntity/AuditLogMapper（无 @TableLogic、零 UPDATE/DELETE 路径）+ record/AuditLogEntry 参数 record（A.7-1）。
- fuyun-common utils/SensitiveMasker 通用脱敏工具（公共工具属 B.1 common 职责，禁业务散落正则）：maskPhone（11 位前 3 后 4，可嵌文本）、maskIdCard（18 位含 X 校验位/15 位老号前 6 后 4）、maskName（姓留名打星，单字/空白原样）、truncate（列宽截断统一收口）；组合使用约定"先证后机"（证号含长数字段，先掩证号再掩手机号防误插星）；9 例单测覆盖。
- practice/check 骨架：POST /api/v1/system/practice/check（M01 §7 路径原样，受 401 认证拦截，V303 权限点已登记）+ PracticeCheckRequest（JSR-303）+ PracticeCheckResponse（employeeId 字符串化出参）+ IPracticeService/PracticeServiceImpl（P0 骨架 passed=false 固定语义，javadoc 声明 P1 practice_grant 表 + EFFECTIVE 校验 + 30 天到期通知替换内部实现，响应契约不变）。
- 端到端 IT（fuyun-app，容器三件套与既有 IT 同款 + 测试资产假密钥）：AuthFlowIT 七步（错误口令防枚举 401 SYS-1001、admin 种子登录双令牌与 user.userId JSON 字符串、无令牌 401 SYS-1003 且 body.traceId 与响应头 X-Trace-Id 一致、携带令牌 practice/check 200 骨架响应、refresh 换发可用 + 登出后旧令牌 401、连续 5 次错密码第 6 次 SYS-1002 含解锁时间、JdbcTemplate 审计断言——LOGIN SUCCESS 行 operator_id=admin/trace_id=注入锚点/result=SUCCESS，FAIL 行 fail_reason/detail 不含口令明文）；DictBroadcastIT 六步 + B3.2 审核 M-4 负路径补断言（V5 登记行 ACTIVE、服务代理 publish 事务提交 AFTER_COMMIT 广播经真实 DictPublishedListener 消费落 PROCESSED 台账、event_registry 订阅自动登记含 system、已发布版本重发布 409 SYS-1013 事务回滚广播不出（以锚点事件证明台账终局恰 2 行）、同 eventId 手工重投 D-7 回查跳过行数仍为 1、业务读口径——无 version 返回当前 PUBLISHED、指定 version=1 返回 DEPRECATED）。
- 表外依赖申报：fuyun-system pom 增 spring-boot-starter-aop（BOM 托管）——@Aspect/@Around 编译期依赖 aspectjweaver；运行期自动代理由 fuyun-app 既有同款 starter（PR #4 起）装配，版本零声明。
- TDD 与验证：AuthFlowIT/DictBroadcastIT 先行 RED 留证（practice/check 404、审计表无行）→ 实现 GREEN；新增单测 5 类 21 例（SensitiveMaskerTest 9、AuditLogAspectTest 8、AuditLogServiceImplTest 1、PracticeServiceImplTest 2、PracticeControllerTest 1）先行 RED（编译失败留证）；全量 `mvn -B -ntp verify` 绿：单测 198（common 36 + integration 32 + system 129 + app 1）+ IT 21（SmokeStack 3 + MessagingGovernance 5 + AuthFlow 7 + DictBroadcast 6）全过；JaCoCo 双核心包 com.fuyun.system.service.impl / com.fuyun.integration.service.impl PACKAGE LINE 1.00 保持、各模块 BUNDLE ≥0.80（common 0.93/integration 0.98/system 0.999）；spotless:check 绿。

## 2026-09-09 · PR-3 B3.2：登录端点 + 401 拦截接线 + 字典管理与 dict.published 广播 + D-7 幂等回查改造

- D-7 改造（跨模块申报项，接口签名不变仅语义增强）：MessageIdempotencyServiceImpl.tryAcquire 在 Redis NX 抢占失败时回查 received_event 台账（(event_id, consumer_module) 唯一索引查询）——已有 PROCESSED 行才返回 false 跳过，无行则 warn 放行重新处理（前置键残留/上次处理中断场景），消除 TTL 窗口内重投被 NX 误判丢弃的消息丢失面；fuyun-common MessageIdempotencyService 接口 javadoc 同步契约；对应单测补 NX 失败回查命中跳过/回查未命中放行两分支，Redis 故障降级路径回归保留。
- 认证端点收口：IAuthService/AuthServiceImpl（login 防枚举 SYS-1001 同文案、locked_until 锁定校验 SYS-1002、停用 SYS-1006、bcrypt 校验 spring-security-crypto、recordLoginFailure/recordLoginSuccess 状态机；refresh/登出收敛令牌服务）+ IUserService/UserServiceImpl（findByLoginName/失败计数达 5 置锁定/成功清零续期）+ IRoleService/RoleServiceImpl（两步单表查角色编码，停用角色不入会话）+ AuthController 三端点（login/refresh 白名单免认证、logout 需令牌 204）+ AuthConverter（MapStruct）。ITokenService 按刷新/登出语义增补两方法（refreshAccessToken：同 sid 换发新 access 不轮换 refresh 值、失败统一 SYS-1005；logout(rawToken)：typ=access 校验后删会话键）——B3.1 三操作接口的语义内聚扩展，SessionData 线格式不变。
- 401 拦截接线：SystemWebConfig（fuyun-system config/，@EnableConfigurationProperties(SecurityProperties) + ITokenService/BCryptPasswordEncoder/AuthTokenInterceptor Bean + addInterceptors 注册 /api/v1/**，白名单仅 login/refresh——actuator/springdoc 不在 /api/v1/** 下天然不受拦截）+ fuyun-app SystemConfig @Import 接线（不放宽组件扫描）。
- 字典域：dict_type/dict_version/dict_item 三实体与 mapper + 四 service（类型创建 SYS-1014 唯一校验、版本创建默认 DRAFT 递增版本号、publish @Transactional 状态机 DRAFT→PUBLISHED 且旧 PUBLISHED 置 DEPRECATED + 事务内 Spring 应用事件 DictVersionPublishedEvent、条目新增仅 DRAFT 可维护、readPublished 契约型读豁免分页）+ 三个 controller（dict-types/dict-versions/dicts，权限点与 V303 种子 perm_code 对齐）+ DictConverter。发布事件经 SystemEventPublisher @TransactionalEventListener(AFTER_COMMIT) 事务提交后发 MQ（A.4.2-7 事务内禁发送），信封 codec + CorrelationData，注册 Confirm/Returns 回调（nack/不可路由 error 告警，P0 不自动重发）；DictPublishedListener @RabbitListener 消费走标准幂等范式（含 D-7 回查），队列经 MessagingGovernance 声明（q.system.system.dict.published，V5 种子已登记事件、订阅经声明构件自动补登记 system）。
- fuyun-system pom 表外依赖增补：spring-security-crypto（BCrypt，BOM 托管）、mapstruct（父 POM dependencyManagement 托管）、spring-boot-starter-amqp（RabbitTemplate）、fuyun-integration（仅消费 api 包 MessagingGovernance/ConsumerQueueSpec，B.2-2 允许）。

## 2026-09-09 · PR-3 B3.1：RBAC 迁移（V300–V303）+ D-2 HMAC 令牌/Redis 会话构件 + 认证拦截器骨架

- 按 BRIEF-PR3-01 §1/§2（B3.1 批次）落地：fuyun-system 新增 RBAC 迁移四件——V300 五核心表（sys_org/sys_user/sys_employee/sys_role/sys_permission）+ 两关联表（sys_user_role/sys_role_permission，RBAC0 最小胶水）、V301 字典三表（dict_type/dict_version/dict_item）、V302 审计只增表（无 updated_at 列/无触发器/无 deleted）、V303 幂等种子（ADMIN 角色 + 六个 P0 权限点（perm_code=API 路径，login/refresh/logout 免认证端点不登记）+ role_permission 全量绑定 + admin 账号（user_type=STAFF）与员工行（emp_no=ADMIN）及 user_role 绑定；口令仅 bcrypt 哈希字面量入库，初始口令红线注记随 P1 密码策略处置）。DDL 公共约定：雪花 ID 主键、审计列数据库维护、触发器复用 integration V1 公共函数 public.fuyun_set_updated_at()（跨 schema 复用）、部分唯一索引 WHERE deleted=0（11 个 uk_ 索引）、无外键；关联表 P0 只 INSERT/DELETE 故不挂触发器（注释明示）。
- 12 个枚举落 enums/（D-6 修宪后正文）：UserType/UserStatus/EmployeeStatus/OrgType/OrgAttr/OrgStatus/RoleStatus/DataScopeType/PermissionType/DictVersionStatus/AuditActionType/AuditResult，规范形态 = code 字段 + `@EnumValue`（MP DB 列映射；3.5.17 该注解仅支持 FIELD 目标，落字段非 getter，实现期实证）+ `@JsonValue`（JSON 输出 code）+ fromCode 双向映射（未知 code 抛 IllegalArgumentException）；另 api/ 包新增 SystemErrorCode 错误码枚举（SYS-1001~SYS-1006/SYS-1011~SYS-1014，implements common ErrorCode）。
- D-2 令牌构件：ITokenService/TokenServiceImpl（两段式 Base64Url(payloadJson).Base64Url(HMAC-SHA256) 线格式，JDK Mac + MessageDigest.isEqual 常量时间比较；校验链 = 两段格式→签名→exp→typ→会话存在→滑动续期；会话键 fy:system:session:{sid} 必带 TTL，值 = SessionData JSON（StringRedisTemplate String 序列化）；issue/verify/evict 三操作，角色摘要存会话不进令牌体）；SecurityProperties（record + @Validated：tokenHmacSecret @NotBlank @Size(min=32)、双 TTL @DurationMin，secret 仅承 FUYUN_SECURITY_TOKEN_HMAC_SECRET 环境变量，application.yml 空占位缺失即启动 fail-fast）；SecurityConstants（claims 短键/令牌类型/Bearer/锁定阈值 5 与 30 分钟/会话键前缀）；AuthTokenInterceptor 骨架落 internal/（401 ProblemDetail 手工渲染与全局同构 {type,title,status,detail,errorCode,traceId}，OperatorContextHolder set/afterCompletion clear；MVC 注册白名单归 B3.2）；线格式载体四 record（TokenClaims/SessionUser/SessionData/TokenPair）落 record/。
- 实体与 mapper：七实体（@TableName("system.sys_xxx")、ASSIGN_ID、@TableLogic、状态列用枚举类型）+ 七 mapper（@Mapper 注解被既有 @MapperScan 扫描）；fuyun-system pom 新增四依赖（mybatis-plus-spring-boot3-starter/mybatis-plus-jsqlparser/data-redis/validation，版本全 BOM/父 POM 托管零声明；amqp/spring-security-crypto/mapstruct 随 B3.2 按需增补）。
- 表外变更申报（BRIEF-PR3-01 CONCERNS-3）：deploy/.env.example 增 FUYUN_SECURITY_TOKEN_HMAC_SECRET 键（空占位 + 独立行中文注释）+ deploy/docker-compose.yml backend environment 增同名透传行（docker compose config 校验通过）。
- TDD 与验证：令牌/拦截器/属性三测试类先行 RED（编译失败留证：constants/properties/record/service.impl 包不存在）→ 实现 GREEN；全量单测 109 例绿（fuyun-common 27 + fuyun-system 50 + fuyun-integration 31 + fuyun-app 1，本批新增 46 例：SystemEnumsTest 24 参数化 + SystemErrorCodeTest 2 + TokenServiceImplTest 10 + AuthTokenInterceptorTest 5 + SecurityPropertiesTest 5）；`mvn -B -ntp spotless:check` 绿；迁移重放验证：全新 timescale/timescaledb:2.29.2-pg16 容器 + 应用 classpath Flyway 执行 V1→V303 共 9 迁移全 success，11 表落位 system schema，种子（角色 1/权限 6/绑定 7/用户 1/员工 1）与触发器、uk_ 索引逐一核对通过。

## 2026-09-09 · 宪法 v1.3 → v1.4 修订（D-6）：枚举包目录 `enum/` 更名 `enums/` + system 域迁移号段占用登记

- 修订来源：TASK.md D-6 待决策项（P0 PR-1 审核发现）——`enum` 是 Java 保留字，`package com.fuyun.{domain}.enum` 无法编译，首个枚举类落地（PR-3）前必须裁决。用户未响应裁决询问，按推荐项默认裁决执行（2026-09-09）：枚举包目录定名 `enums/`；若后续改判（如 `enumeration/`），改动面 = 目录名 + 包名 + 宪法正文，一次替换可回收。
- 修订范围（backend/AGENTS.md 三处正文 + 20 个模块目录更名，原子修宪）：① A.2-7「定义于 enum/ 包」改为「定义于 enums/ 包」；② B.1 模块内包职责表 `constants/ enum/` 行改为 `constants/ enums/`；③ C.3 目录树 `config/ properties/ constants/ enum/ exception/` 行同步改为 `enums/`。全部 20 个业务模块 `src/main/java/com/fuyun/{domain}/enum/.gitkeep` 占位目录以 git mv 语义更名 `enums/.gitkeep`（含 fuyun-integration——该模块 enum/ 仍为占位，实际类型处理器落 handler/，不受影响）。
- 号段占用登记（TASK.md W-4 载体要求，BRIEF-PR3-01 §2.1）：system 域（M01）占用 **V300–V399**，本批（PR-3 B3.1）使用 V300–V303——V300 RBAC 五核心表 + 两关联表、V301 字典三表、V302 审计日志表（只增）、V303 RBAC 种子（ADMIN 角色 + P0 权限点 + admin 账号）。依据：M01 非公共治理域，其表仅落 system schema，无「先于全部业务迁移」的硬需求；Flyway 多目录按版本全局排序，system 的 V300 天然晚于 integration 的 V1 公共触发器函数，依赖安全（号段归属 CI 自动校验仍按 W-4 待办补建）。
- 影响范围：backend/AGENTS.md 三处、20 模块 `enum/` 目录名；宪法版本 v1.3 → v1.4。

## 2026-09-09 · 宪法 v1.2 → v1.3 回补：handler/ 包目录与 jacoco constants 排除

- 修订来源：PR-2（M20 治理构件）执行期 B2.2 / B2.3 批次的审核申报与核验结论——两项偏差经批次审核确认合法并定案，本次将正文回补到位，消除「CHANGELOG 已记、正文滞后」状态（B2.2 / B2.3 条目内为表外申报记录，本节为正文定稿登记）。
- 修订一（B.1 模块内包职责表新增 `handler/` 行，C.3 目录树同步）：fuyun-integration 落地 `com.fuyun.integration.handler.UuidTypeHandler`（BaseTypeHandler\<UUID\>，pgjdbc 原生 setObject/getObject 绑定），经 `mybatis-plus.type-handlers-package` 全局注册。职责边界：「MyBatis TypeHandler（类型处理器）：java 类型↔JDBC 列值转换（如 UUID、JSON 列）；经 mybatis-plus.type-handlers-package 全局注册，禁止散落注解指定」。理由：MyBatis 无内置 UUID TypeHandler，B2.2「UnknownTypeHandler→setObject 原生写入」推定被 B2.3 端到端 IT 复验证伪后的修复产物（见下两条申报记录）；归位数据层配套，与 mapper/entity 同层不出数据层，后续模块 uuid 列零成本复用，目录形态需入宪法避免各模块私设散落。
- 修订二（C.5-2 覆盖率排除清单追加 `constants/**`）：排除清单由「排除 config/dto/entity/Application/生成代码」扩为「排除 config/dto/entity/constants/Application/生成代码」。理由：常量类仅私有构造器 + `public final static` 字面量（A.2-6），无可执行业务分支，与 config/properties 同语义不可测；父 POM jacoco excludes 已随 B2.2 落地 `com/fuyun/**/constants/**`，本次正文对齐。
- 影响范围：仅 backend/AGENTS.md 三处（B.1 表、C.3 树、C.5-2）；不触及 D-6/D-7 等待决策项；宪法版本 v1.2 → v1.3。

## 2026-09-09 · PR-2 B2.3：首批事件名 + CF-7 遥测模型登记 + 消息治理端到端 IT

- 按 BRIEF-PR2-01 §4（B2.3 批次）落地：fuyun-system api/ 新增 CF-2 五个主数据事件占位载荷 record（system.dict.published / system.org.changed / system.user.changed / system.param.changed / system.practice.changed，事件名全部为 M01 Spec §7 明示，事件对象落发布方 api 包为 B.3-1 红线，均标注"占位 schema：正式字段随 PR-3 M01 实装冻结"）；fuyun-common 新增 `com.fuyun.common.messaging.StandardTelemetryMessage`（CF-7 标准遥测消息模型七字段 record：deviceId/metricCode/value/unit/occurredAt/quality/source，对齐 M14 FU-M14-05"四路同构"，P0 只登记不消费——不建 SPI 接口与任何 iot 消费代码，SPI 与消费链路归 PR-4）；Flyway V5 种子迁移七行（id 1=CF-1 信封约定行 integration.convention.event-envelope、id 2-6=五个 system.* 事件、id 7=CF-7 行 iot.telemetry.message，status 全 ACTIVE，CF-1/CF-7 登记行形态为简报 §4.3/§8-2 推导定案待终验核对）。
- 端到端 IT：fuyun-app 新增 `MessagingGovernanceIT`（Testcontainers 三容器与 SmokeStackIT 完全同款 tag 与 rabbitmq.conf 挂载，独立声明不改 SmokeStackIT），五步断言打通「冻结登记 → 发布→消费（信封线格式 + payload 线格式）→ 重复投递被幂等拦截（Redis NX 前置 + received_event 唯一行）→ poison 帧经有界重试耗尽进 fy.dlx 死信落库（PENDING，source_queue/routing_key/event_id/payload_body/fail_reason）→ 构件副作用（订阅自动登记 it + 死信统一队列声明）」；同链路复验 B2.2 申报①的 UUID 真库写入（received_event 真实 insert）；不含 fy.delay TTL 时延断言（T-R3-4 口径），不新增 awaitility 等测试依赖。
- 表外最小补充四处（PR 描述申报）：① fuyun-system pom 增 `spring-boot-starter-test`（test scope，BOM 托管）——SystemMasterDataPayloadTest 线格式冻结断言的必要测试依赖；② 新增 `StandardTelemetryMessageTest`（fuyun-common test）——CF-7 模型为冻结契约载体，补七字段语义与 JSON 往返断言（与 EventEnvelopeTest 同型，简报 §4 未列、按全局"测试与实现同提交"补齐）；③ fuyun-app pom 增 `fuyun-system` 模块依赖（compile）——IT 以发布方 api 真实载荷发布 system.dict.published 打通契约链路，装配模块聚合业务模块属 B.1 既有职责，与 B2.1 增 fuyun-integration 依赖同型，PR-3 M01 实装后由装配刚需承接；④ 删除 fuyun-system api/.gitkeep（简报 §0-6：首个真实文件落地即删占位）。
- IT 首跑修复一处 B2.1/B2.2 遗留启动缺陷（verify 首次真实跑 IT 即暴露，PR 描述申报）：`MessagingProperties.idempotencyRedisTtl` 的 `@Positive` 对 `Duration` 无内建校验器——上下文启动期抛 HV000030（UnexpectedTypeException）致 SmokeStackIT/MessagingGovernanceIT 全量红灯；此前批次仅跑单测（记录直接构造、容器只做 Flyway 重放），未真实装配 Properties Bean 故未暴露。修复：替换为 Hibernate Validator 专属 `@DurationMin(nanos = 1)`（语义等价"必须为正"，A.2-2 启动期校验红线不松动）；时间边界取舍：纳秒下界表达严格大于零，毫秒级下界会误拒合法亚毫秒配置。
- IT 首跑修复一处 B2.1 消费承接设计缺陷（PR 描述申报）：Spring AMQP 3.2 监听链路对任何方法签名都先经转换器提取 payload（无 raw 旁路，字节码实证），默认 `TYPE_HEADERS` 优先级下 `__TypeId__=EventEnvelope` 不在受信包白名单（仅 java.util/java.lang）致全部消费帧转换失败；且 Jackson 2.19 `readValue(json, String.class)` 对 JSON 对象节点抛 MismatchedInputException——"String 参数直接承接"在 Jackson 转换器下不成立（本机实证）。修复（简报 §4.4"String 承接"落地形态修正，CF-1 语义不变）：MessagingGovernanceConfig 新增项目标准消费容器工厂（覆盖 Boot 默认同名 `rabbitListenerContainerFactory`，经 Boot configurer 装配 AUTO 确认/有界重试/defaultRequeueRejected=false 姿态不变，仅 payload 承接换 `SimpleMessageConverter`——任意报文兜底 byte[] 永不抛转换异常，防死信二次转换失败回环）；消费方一律 raw `Message` 参数（容器经 providedArgs 注入原始帧）+ UTF-8 解码为 String + codec.fromJson——"原文进 codec、__TypeId__ 不作消费依据"按 CF-1 冻结约定落地；DeadLetterListener 注解零改动即随新工厂获得毒丸安全链路。
- IT 首跑修复一处 B2.2 幂等台账写库缺陷（PR 描述申报）：B2.2 申报①"received_event.event_id（PG uuid 列）经 UnknownTypeHandler→setObject 原生写入"推定被端到端 IT 复验证伪——MyBatis 在 insert 参数映射构建期即抛 "Type handler was null on parameter mapping for property 'eventId'（javaType java.util.UUID）"，根本未达 JDBC 层（同链路 dead_letter 全 VARCHAR 列落库正常，形成测试 2/3 红、测试 4 绿的定位信号）。修复：新增 `com.fuyun.integration.handler.UuidTypeHandler`（BaseTypeHandler<UUID>，pgjdbc 原生 setObject/getObject 绑定）并经 application.yml `mybatis-plus.type-handlers-package` 全局注册（insert 参数与 wrapper 查询条件两侧生效，后续模块 uuid 列零成本复用）；配套 UuidTypeHandlerTest 三用例（原生绑定/三通道读取/SQL NULL 透传）。目录申报：handler/ 为宪法 B.1 包职责清单的扩展（数据层配套，与 mapper/entity 同层不出数据层）。
- TDD：IT + 两单测先行编写跑 RED（载荷 record / 遥测模型缺失致编译失败留证）→ 补齐五 record、StandardTelemetryMessage 与 V5 迁移后 GREEN；验证 = `mvn -B -ntp verify` 全绿（surefire 全量单测 + failsafe SmokeStackIT/MessagingGovernanceIT + JaCoCo 双阈值：`com.fuyun.integration.service.impl` 核心包 PACKAGE LINE 1.00 首次真实生效 + 各模块 BUNDLE ≥ 0.80）+ `mvn -B -ntp spotless:check` 绿。

## 2026-09-09 · PR-2 B2.2：received_event 幂等构件 + dead_letter 死信落库告警

- 按 BRIEF-PR2-01 §3（B2.2 批次）落地：fuyun-common 新增 `com.fuyun.common.messaging.MessageIdempotencyService` 契约接口（tryAcquire/recordProcessed/release 三方法 + 标准消费范式 javadoc，接口沉 common 为 M-3 裁决载体）与 `ReceivedEventRecord` 参数 record；fuyun-integration 落地 `MessageIdempotencyServiceImpl`（Redis SET NX PX 前置去重，键 `fy:integration:idempotency:<module>:<eventId>`、TTL 取 fuyun.messaging.idempotency-redis-ttl、Redis 故障降级放行由唯一索引兜底 + `DuplicateKeyException` 吞为已处理、其他 DB 异常原样上抛 + release 失败释放前置键）与 `internal/DeadLetterListener`（@RabbitListener 监听 q.integration.dead-letter + 容器 AUTO，raw Message 承接毒丸不做 JSON 转换，x-death 轨迹解析 + SHA-256 摘要 + 信封合规校验（不合规两列置空并标注留痕），落库失败 error 告警不抛防毒丸无限循环）；`ReceivedEvent`/`DeadLetter` 实体与 mapper + Flyway V3/V4 迁移（received_event 表：event_id+consumer_module 唯一索引；dead_letter 表：payload 落「原文全文+SHA-256 摘要」两列，简报 §8-6 定案口径）。
- 装配与门禁配套：MessagingGovernanceConfig @Import 追加幂等实现与死信监听两类（简报 §2.7 预告的 B2.2 追加）；MessagingConstants 增 `IDEMPOTENCY_KEY_PREFIX`/`HEADER_X_DEATH`（A.2-6 Redis 键前缀与消息头词表）；父 POM jacoco 规则二 includes 追加 `com.fuyun.integration.service.impl`（核心包 PACKAGE LINE 1.00 首个真实生效包，DoD 第 2 条）+ excludes 追加 `com/fuyun/**/constants/**`（宪法 C.5-2 排除清单扩展项，PR 描述申报，§8-7）。
- 实现口径定案两处（简报未明确处，PR 描述同步申报）：① received_event.event_id 列为 PG UUID 类型，实体字段取 `java.util.UUID`——MyBatis 无内置 UUID TypeHandler，经 UnknownTypeHandler→ObjectTypeHandler→`ps.setObject` 走 pgjdbc 原生 UUID 支持（insert 路径成立，B2.3 端到端 IT 复验真实插入）；② 死信监听器落库失败 catch 范围取 `RuntimeException` 兜底（比简报"DB 故障"更宽），覆盖一切运行时异常防毒丸回环，error 日志即为 M20 §10 告警通道。
- TDD：新增 2 测试类 11 用例（幂等 7 场景 + 死信 4 场景）先 RED（测试先行编译失败留证）后 GREEN，测试与实现同提交；验证 = `mvn -B -ntp test` 全绿 + `mvn -B -ntp spotless:check` 绿 + V1→V4 迁移对全新 timescale 容器库 Flyway 重放留证（同 B2.1 方式）。

## 2026-09-09 · PR-2 B2.1：事件信封 + Long→String 序列化 + 队列声明构件 + event_registry（CF-1 冻结载体落盘）

- 按 BRIEF-PR2-01 §2（B2.1 批次）落地：fuyun-common 新增 `com.fuyun.common.messaging` 包（EventEnvelope 七字段信封 record = CF-1 冻结形态 + EventEnvelopeCodec 时钟注入工厂 / 线格式编解码与消费侧合规校验）与 `com.fuyun.common.config.JacksonLongToStringConfig`（Long/long → String 全局定制唯一注册点，backend 宪法 A.3-8）；fuyun-integration 落地消息治理构件（api/ 三件契约 MessagingGovernance/ConsumerQueueSpec/DelayQueueSpec + QueueGovernorImpl + IEventRegistryService 登记服务 + EventRegistry 实体与 mapper + MessagingConstants/MessagingProperties/MessagingGovernanceConfig）与 Flyway V1/V2 迁移（公共审计触发器函数 fuyun_set_updated_at + integration.event_registry 表）；fuyun-app 装配（MessagingConfig @Import 两配置类、MybatisPlusConfig @MapperScan + 三大插件）与 application.yml / application-test.yml 追加键（消费端有界重试 + fuyun.messaging.idempotency-redis-ttl，test 快速重试覆盖）。
- Flyway 号段登记（BRIEF-PR2-01 §2.5）：integration 治理域占用 V1–V99（本批用 V1–V2）；V100–V199 患者域、V200–V299 医嘱域为宪法例举既定；V300–V399 系统域、V400–V499 物联域为建议分段（PR-3/PR-4 拟用）；V500 起按实装先后递增分配、先登记先占（载体 = 各 PR 简报 + 本文件）。号段归属 CI 自动校验（宪法 A.4.1-2）本批不补建，登记 TASK.md 待办。
- 实现口径定案三处（简报未明确处，PR 描述同步申报）：① EventEnvelopeCodec Bean 的装配落点简报未指明（§2.2 仅称"Bean"），随 MessagingGovernanceConfig @Import 一并装配——发布/消费共用，且 B2.2 死信监听依赖其 Bean 化；② fuyun-app pom 新增 fuyun-integration 模块依赖——§2.10 要求 MessagingConfig @Import(MessagingGovernanceConfig) 需编译期可见，与 §1.2 "fuyun-app pom 零新增"表述冲突，以装配要求为准（模块内部依赖，版本随 ${project.version}，非表外三方依赖）；③ MessagingConstants 补 QUEUE_TYPE_QUORUM / BINDING_KEY_ALL 两常量承载 quorum 队列类型值与死信全量绑定键，防魔法值散落（A.2-6）。
- TDD：新增 5 测试类（EventEnvelopeTest / EventEnvelopeCodecTest / JacksonLongToStringConfigTest / QueueGovernorImplTest / EventRegistryServiceImplTest）先 RED 后 GREEN，测试与实现同提交；验证 = `mvn -B -ntp test` 全绿 + `mvn -B -ntp spotless:check` 绿 + V1/V2 迁移 Flyway 重放留证（B2.1 不跑 failsafe，SmokeStackIT 随 B2.3 verify 回归）。

## 2026-09-09 · PR #4 审查修复（Finding 1/2）：全局异常渲染装配缺失 + prod springdoc 兜底

- Finding 1（Critical，独立审查发现，本次核实成立）：`GlobalExceptionHandler` 从未注册为 Bean——`@SpringBootApplication` 默认仅扫 `com.fuyun.app.*`，`com.fuyun.common.web` 包在扫描范围外，common 无 AutoConfiguration.imports / spring.factories，TraceIdConfig 也未引入它，@RestControllerAdvice 装配缺失导致 ProblemDetail + errorCode/traceId 统一契约运行时未生效（装配级死代码）。修复：fuyun-app 的 `TraceIdConfig` 增加 `@Import(GlobalExceptionHandler.class)`（装配归 app，宪法 B.1；不放宽 scanBasePackages），并新增 @WebMvcTest 切片注册测试——探针 controller 抛业务异常，断言响应为 RFC 9457 ProblemDetail 且状态码/errorCode/traceId/响应头回写齐全；TDD 先 RED（无 @Import）后 GREEN。
- Finding 2（Minor，采纳）：`application-prod.yml` 预置 `springdoc.api-docs.enabled=false` 与 `springdoc.swagger-ui.enabled=false`（宪法 A.3-7 Swagger UI 仅 dev/test）。P0 未引入 springdoc，两键当前为无害冗余安全开关；首个 REST 端点引入 springdoc（锁 2.8.17）时自动兜底，防 prod 误开 API 文档。与「无消费方不写键」的口径区别：此为安全默认值声明而非业务配置，注释已说明动机。
- 本次不修订宪法正文；验证：新增测试 RED→GREEN 过程留证 + `mvn verify` 全绿 + `spotless:check` 绿。

## 2026-09-09 · PR-1 B1.4：web pnpm monorepo 三应用脚手架落盘 + web/Dockerfile 运行层取产物修正

- 按 BRIEF-PR1-01 §5 落地 web monorepo：根配置五件（`package.json` 七脚本 + packageManager 锁定 pnpm@12.3.4、`pnpm-workspace.yaml`（apps/*/packages/* + catalog 共享工具链版本表，与 web 宪法 C.2 一致）、`eslint.config.mjs`（withVueTs 组合：flat/essential → recommendedTypeChecked → eslint-config-prettier 置尾，--max-warnings=0 门禁）、`.prettierrc`（printWidth 100 / singleQuote / trailingComma all / endOfLine lf）、`vitest.config.ts`（projects 聚合三应用））；三应用脚手架（workstation / portal / bigscreen：package.json、index.html、vite.config（/api 无 rewrite + /ws ws:true、resolve.alias `@` 与 tsconfig paths 同步）、solution tsconfig 三件（strict 手写不引 @vue/tsconfig）、.env.example、vite-env.d.ts（ImportMetaEnv）、B.1 目录基线 .gitkeep 占位、懒加载首页路由 + 极简 HomeView + 各一个真实断言冒烟单测）；`packages/shared`（纯 TS 分页契约 `PageResult<T>`，禁依赖 vue/element-plus）与 `packages/ui`（`export {}` 中文占位说明 + vue peerDep，本批不引 element-plus）；`pnpm-lock.yaml` 入库。workstation 依赖差异：element-plus 2.14.5 + unplugin-vue-components 32.1.0 + unplugin-auto-import 21.1.0 + dayjs 显式声明（web B.3-6）。
- web/Dockerfile 修正（W-3 对齐项②）：运行层三行 COPY 补 `--from=build`——产物生成于构建层容器内且 `.dockerignore` 已排除 `**/dist/`，原写法必然构建失败；其余（node:24 + corepack pnpm@12.3.4、nginx:1.30.4、注释）不动。
- 表外版本按 BRIEF-PR1-01 §9 默认方案锁定并在 PR 描述申报（合入后回补宪法 C.2 版本表与定稿表）：`@vitejs/plugin-vue 6.0.8`（官方 peer 声明支持 vite ^8.0.0，registry 已核实）、`dayjs 1.11.23`（element-plus 2.14.5 依赖范围 ^1.11.20 内，安装后 `pnpm why dayjs` 复核，T-R4-4 结论供回填）、`@types/node 24.13.3`（tsconfig.node.json `types:["node"]` 简报显式要求，需类型包落盘；24.x 线对齐 Node 24 运行时）。
- 实现口径裁决一处：`PageResult<T>.total` 按 web 宪法 A.3-6 / backend A.3-8（Jackson 全局 Long→String）以 `string` 承载，简报 §5.4 行内 `total: number` 为笔误，以宪法为准（本条即冲突报告）。
- 卫生配套补 `.prettierignore`（dist/、auto-imports.d.ts、components.d.ts、coverage/、pnpm-lock.yaml、`*.md` 不进格式门禁）：unplugin 生成物与构建产物不入库且内容非人工维护，进入 prettier --check 会让本地构建后格式门禁永红；pnpm-lock.yaml 内容经哈希校验格式化无意义；`*.md`（web/AGENTS.md 宪法）为手工维护文档，排除以避免每次全量 format 的重排 churn（实测 prettier 会重排宪法表格缩进）；根 `.gitignore` 追加 `auto-imports.d.ts`、`components.d.ts` 两行（简报 §5.6），与 vite.config dts 输出路径一致避免 git status 永脏。
- 简报字段外最小补充三处（均随本批落地并在 PR 描述申报）：根 package.json 补 `"type": "module"`（消除 Vite 原生配置加载器对根 vitest.config.ts 的「ESM 语法按 CommonJS 加载」警告，与三应用清单一致）；三应用 package.json 补 `@types/node`（表外申报项，tsconfig.node.json `types:["node"]` 消费方在各 app，根不声明）；根 `web/tsconfig.json`（include 仅根 vitest.config.ts——根级配置文件不归属任何 app 的 tsconfig 项目，无归属项目时 ESLint 类型感知规则报「file not found by the project service」，补根项目后 lint 全绿）。
- 门禁配套修正两处（本批验证暴露，证据驱动）：① `.pre-commit-config.yaml` 的 `check-yaml` 钩子增加 `exclude: web/pnpm-lock.yaml`——pnpm lockfile 为多文档 YAML（`---` 分隔），check-yaml 单文档断言必然误报，不排除则 lockfile 无法入库（简报 §5.6 硬性要求入库）；② 三应用 `tsconfig.node.json` 补 `target: ES2022` + `lib: ["ES2023"]` + `skipLibCheck: true`——简报手写 spec 未列 target（默认 ES5）导致 node_modules 声明文件私有字段报 TS18028，且 unplugin/vitest 的 d.ts 引用可选框架类型需 skipLibCheck 抑制（与 create-vue 生态标准配置一致）；app 项目 tsconfig 验证无需该补丁。
- 审核修复（修复循环第 1 轮）：`PageResult<T>.page` 注释由「从 1 起」修正为 0 基契约语义——backend A.3-6 明文「请求 page（0 基，必须显式告知前端）」，响应回显请求值；仅改注释不动类型结构（`page: number` 不变）。
- 终审修复（缺陷 I-1，修复循环第 1 轮）：三应用 vite.config.ts 补 `base`（workstation/portal/bigscreen 分别为 `/workstation/`、`/portal/`、`/bigscreen/`，与 nginx fuyun.conf 子路径 alias 一一对应），三应用 router 改 `createWebHistory(import.meta.env.BASE_URL)`。根因：简报 §5.3 vite.config 规格未列 base（规格缺口）——默认 base `/` 使构建产物资源引用为绝对路径 `/assets/*`，在 nginx 子路径挂载下 JS/CSS 全部 404（三前端白屏）；router 硬编码 `createWebHistory()` 同样无法感知子路径。方案：base 与路由以 `import.meta.env.BASE_URL` 同源联动（web A.2-5 产物路径与部署挂载耦合的延伸约束）；dev 模式应用即服务于该子路径属预期。

## 2026-09-09 · PR-1 B1.3：deploy/ 全量编排落盘 + ci.yml 骨架期排除项移除

- 按 BRIEF-PR1-01 §4 落地 deploy/ 五件套：`docker-compose.yml`（postgres/redis/rabbitmq/minio/backend/nginx 七服务 + iot-simulator `profiles:["sim"]`；镜像 tag 全锁定禁 latest；healthcheck 参数照定稿报告 §6.3（统一 interval 10s/timeout 5s/retries 5，rabbitmq start_period 90s、backend 120s、nginx retries 3）；depends_on 全部 `service_healthy` 禁裸 service_started；backend 不发布宿主端口并追加 `stop_grace_period: 40s` 对齐 yml `timeout-per-shutdown-phase: 30s`（backend A.5-15））；`.env.example`（23 键全清单：PG 4 + Redis 2 + RabbitMQ 4 + MinIO 3 + IoTDA 6 + 应用 4，值只留占位与中文注释）；`postgres/initdb/01-init.sql`（仅 `CREATE EXTENSION IF NOT EXISTS timescaledb`，业务库由 POSTGRES_DB 环境变量承担，业务 DDL 全归 Flyway）；`rabbitmq/rabbitmq.conf`（`default_queue_type = quorum`，总 Spec D1）；`nginx/fuyun.conf`（三前端静态路由 + `/api` 反代保留前缀 + `/ws` WebSocket 升级 + `/healthz` 静态 200）。
- ci.yml changes job 移除四处骨架期排除行（`!backend/Dockerfile`、`!backend/.dockerignore`、`!web/Dockerfile`、`!web/.dockerignore`）及对应两条注释，Dockerfile/.dockerignore 变更恢复镜像构建触发；pom/webpkg 存在性守卫保留（paths-filter 误判兜底，见 2026-09-08 CI 首跑修正条目）；job name 一律未动（分支保护 required checks 精确对齐）。
- 实现口径修正三处（简报/定稿示意片段笔误或环境差异，随本批落地）：① compose 内部挂载点相对路径以 compose 文件所在 deploy/ 目录为基准，简报表中 `../postgres/initdb`、`../rabbitmq/`、`../nginx/` 修正为 `./postgres/initdb`、`./rabbitmq/`、`./nginx/`（`../` 写法会解析到仓库根导致挂载落空；`../web/apps/<app>/dist` 不变，W-3 裁决路径）；② .env.example 注释一律独立成行、不用行内 `#`——compose `env_file:` 注入容器环境沿用 docker env-file 格式，无行内注释语义，行内 `#` 会混入变量值；③ postgres healthcheck 的 `$POSTGRES_USER/$POSTGRES_DB` 写作 `$$` 转义——compose 会先对 yml 全文做变量插值，未转义时探针取 .env 插值而非容器内环境，`$$` 使容器内 shell 从 `environment:` 块展开，语义一致但不再依赖插值时序。
- rabbitmq:4.3.5-management 镜像实证（本机 `rabbitmqctl list_users` 验证）：`RABBITMQ_DEFAULT_USER/PASS` 环境变量在该 tag 上仍由服务端生效（入口脚本不再转换但种子用户正常创建），简报的凭据注入方案可用，无需改用配置文件承载口令（口令入库即红线）。
- 审核修复（修复循环第 1 轮，PR-1 收尾全栈验证发现）：nginx healthcheck 探针 `/dev/tcp/127.0.0.1:80` 为无效 bash 网络重定向语法——`/dev/tcp` 须为 `/host/port` 斜杠形态，冒号形态被 shell 当字面路径（实测报 `No such file or directory`），探针必然失败使 nginx 恒 unhealthy，违反「七服务全 healthy」验收。根因：定稿报告 §6.6 片段（FJ-02）本身即此写法，属规格带病照抄。方案：保持 bash `/dev/tcp` 形态仅修正为 `/dev/tcp/127.0.0.1/80`（精准修改，不换探针方案；已在 nginx:1.30.4 容器内实测修正后探针对 fuyun.conf 的 /healthz 返回 200，并经 compose 全栈验证 nginx 转 healthy）。备查记录：nginx:1.30.4 镜像实测含 /usr/bin/curl（8.14.1），定稿 FJ-02「官方镜像无 curl/wget」前提已过时，后续升级镜像时可评估改用 curl 探针简化写法，本批不换。
- 终审修复（全分支终审 M-1/M-2，两处 Minor）：① M-1 ci.yml frontend job audit 步骤 `pnpm audit` 改为 `pnpm audit --audit-level high`——pnpm 内建命令优先于 web/package.json 同名 script，裸命令不带 script 中的阈值参数，实际按任意级别阻断，把 web 宪法 C.5-5「high 及以上阻断」语义放大（功能偏保守但非宪法约定语义），改为与 C.5-5 字面一致消除解析歧义；② M-2 compose backend `FUYUN_DATASOURCE_URL` 追加 `?reWriteBatchedInserts=true`——env 变量存在会覆盖 application-dev.yml 内含该参数的默认值，致批量写优化静默失效（backend A.4.2-6 要求全环境 JDBC URL 携带），.env.example 库名注释处同步补「生产/自定义 URL 需自带该参数」提示。
- CI 修复（PR #4 images job 首跑失败，backend/web 两 matrix 同报 `Cache export is not supported for the docker driver`）：images job「构建镜像」步骤前增加 `docker/setup-buildx-action@v3`——job 未安装 buildx 时 docker/build-push-action 落在 Docker 内建 docker driver 上，该 driver 不支持 `cache-from/cache-to: type=gha` 的缓存导出；骨架期 Dockerfile 变更被排除、构建步骤从未真实执行，PR #4 首跑暴露此潜伏缺陷。该 action 缺省创建 docker-container driver 的 builder，支持 gha 缓存导出；matrix、步骤条件与缓存 scope 一律未动。验证边界：本地仅 actionlint 语法校验可用，buildx 创建与 gha 缓存链路无法本地模拟，由 CI 复跑验证。
- CI 修复（PR #4 合并被分支保护阻断，images 审查修复循环第 2 轮）：images job 去 matrix 化——GitHub 对 matrix job 展开的 check run 名带维度后缀（PR #4 真跑实测展开为 `images (backend, backend, backend/Dockerfile)` 与 `images (web, web, web/Dockerfile)`），而 main/dev 分支保护 required check 为聚合名 `images`（方案 B 五 checks 模型）；骨架期构建步骤从未真跑、跳过态 check 名恰为聚合名故从未暴露，真跑时聚合名消失致 required check 永不满足、mergeStateStatus=BLOCKED。方案：去掉 `strategy.matrix`，改同 job 内两个显式构建步骤（backend/web），每步固定携带原 matrix 条目等价参数（context/file/tags/cache-from/cache-to scope 全部字面量化，不再引用 matrix 上下文），步骤级 if 沿用原条目触发逻辑（对应侧变更 && 对应 verify success）；job 级 if/name/permissions/needs 未动；两步顺序执行天然等价原 fail-fast 语义（一步失败 job 即败、后续步骤默认跳过）。PR-4 的 iot-simulator 第三镜像原「第三条 matrix」表述等价转换为「追加第三个同构构建步骤」（TODO 注释同步改写，禁改回 matrix，否则 required check 名再度失配）。本地验证仅 actionlint 可用，check run 展开行为无法本地模拟，由 push 后 CI 与分支保护 mergeable 状态验证。

## 2026-09-09 · PR-1 B1.2：冒烟集成测试 + backend/Dockerfile COPY 策略重写

- 按 BRIEF-PR1-01 §3 落地 B1.2：① fuyun-app 新增 `SmokeStackIT`（fuyun-app 下唯一 `*IT`）——Testcontainers 拉起与 compose 同 tag 的三容器（timescale/timescaledb:2.29.2-pg16、redis:8.10.1、rabbitmq:4.3.5-management，backend 宪法 C.5-4），@ServiceConnection 注入连接，test profile 启动 fuyun-app 完整上下文，依次验证 Flyway 首跑建表（flyway_schema_history 落 public schema）、Redis 读写一回合（TTL 生效）、RabbitMQ 队列声明与一帧收发（CF-1 最小验证）；RabbitMQ 容器经 test 资源挂载 `default_queue_type=quorum` 与 B1.3 compose 的 rabbitmq.conf 同语义。② backend/Dockerfile 构建层 POM 拷贝由 glob 拍平（`COPY pom.xml fuyun-*/pom.xml ./`）改为逐模块 COPY 保持目录结构，其余七条镜像规范不动（backend 宪法 C.5-6）。③ .dockerignore 已含 target/ 排除，无需改动。本次不修订宪法正文，不新增生产代码。
- IT 落地过程中修复一处 B1.1 遗留构建缺陷（fuyun-app/pom.xml 最小配置变更，非生产代码）：spring-boot-maven-plugin repackage 增配 `<classifier>exec</classifier>`——默认在位替换使 failsafe 集成测试 classpath 拿到 BOOT-INF 布局 fat jar，应用类对普通类加载器不可见，导致 @SpringBootTest 装配失败（本机三次复现定位：包扫描找不到 @SpringBootConfiguration → 注解合并返回 null → 构造器注入失效）。fat jar 产出移至 `*-exec.jar`，Dockerfile 同步取 `*-exec.jar`；另在 IT 构造器显式标注 @Autowired（Spring 6.2 测试构造器默认 annotated 模式需显式声明，属构造器注入形态，符合 backend 宪法 A.1-7）。

## 2026-09-09 · PR-1 B1.1：后端工程骨架落盘（父 POM / fuyun-common / fuyun-app / 20 业务域空模块）

- 按 PLAN-P0-01 §1-PR-1 与 BRIEF-PR1-01 §2 落地 backend 骨架：父 POM（spring-boot-dependencies:3.5.16 BOM import、插件管理、22 模块聚合）、fuyun-common（错误码契约 / 业务异常基座 / ProblemDetail 全局渲染 / traceId 过滤器 / 操作人上下文 + 四测试类）、fuyun-app（装配入口 / 全环境与 dev-test-prod yml / TraceProperties @Validated 示范）、20 个业务域空模块（pom + 目录占位，无空实现类）。本次不修订宪法正文。
- 表外版本按 BRIEF-PR1-01 §9 默认方案锁定并将在 PR 描述申报（PR 合入后回补宪法 C.2 版本表）：maven-compiler-plugin 3.14.1、maven-surefire-plugin 3.5.6、maven-failsafe-plugin 3.5.6（取 spring-boot-dependencies:3.5.16 pluginManagement 原值，已核对 Maven Central）、lombok-mapstruct-binding 0.2.0（MapStruct 官方标准搭配值）。
- 行为代码（fuyun-common）按 TDD 先写失败测试再实现，测试与实现同提交；纯结构文件（pom / 目录占位 / yml）以构建命令验证。

## 2026-09-09 · P0 交付启动：dev 分支与门禁就绪 + SDD 台账建立

- 新增 `dev` 集成分支（自 main@a10369e），经 gh api 配置分支保护与 main 逐字段一致（五 required checks + strict + enforce_admins + 禁 force push / 删除）；此后 P0 五 PR 依序合入 dev（用户 2026-09-09 裁决），dev→main 合并另行裁决。
- 新增 `.superpowers/sdd/ledger.md`：P0 交付 loop（`docs/prompt/2026-09-09-loop-P0工程骨架.md`）的状态续传台账——阶段门禁状态、冲突扫描记录、批次明细，随各门禁推进更新。
- 冲突扫描结论：仅定稿 §6.6 nginx bind mount 片段路径与 web 宪法 C.3 存在表述分歧，按 TASK.md W-3 既有裁决（产物路径 = `web/apps/<app>/dist`）执行，无需新裁决；版本口径 / CI checks 名称 / 决策点（D-2/D-3）四方核对一致。

## 2026-09-09 · P0 交付执行设计落盘 + 定位层地图补 docs/prompt

- 新增 `docs/prompt/2026-09-09-loop-P0工程骨架.md`：以 PLAN-P0-01 为唯一 spec 的交付 loop 执行设计（P0→P6 七阶段门禁、17 个审批批次、TDD+SDD subagent 派遣制、批内修复/PR 审核/终验三级循环、PR 依序合入 dev 流程），供执行会话作为唯一执行依据；定位层仓库地图 §3 同步登记 `docs/prompt/`。
- 用户裁决两项（已录入文档 §8）：端到端测试口径 = 链路级端到端（浏览器 E2E 框架维持计划 §4 排除）；PR 粒度 = 计划五 PR 依序合入 dev。执行文档字数超限分歧经裁决按中文字数口径交付（2217 中文字，总字符含 ASCII 标识符 5853）。

## 2026-09-08 · CI 首跑修正（PR #1 实测暴露，两处）

1. **commitlint subject-case 中文误报**：中文 subject 以大写字母/数字开头（如"P0 实施计划…"）被 config-conventional 的 subject-case 规则误判为 pascal-case/upper-case，PR 检查失败。裁决：关闭 subject-case 规则（对 CJK 文本无实际意义，属该规则设计语境为英文的误伤），其余 conventional 规则全量保留。
2. **路径过滤意外触发构建 job**：PR #1 为纯文档变更，dorny/paths-filter 仍将 backend/frontend 判定为有变更（判定输出与实际文件清单不符），且 `setup-java` 的 `cache-dependency-path` 在 pom.xml 缺失时硬报错、`pnpm/action-setup` 在 package.json 缺失时报"No pnpm version specified"，导致骨架期必红。加固：changes job 增设 `pom`（backend/**/pom.xml）与 `webpkg`（web/**/package.json）存在性过滤器，backend/frontend job 触发条件追加"对应构建文件存在"——骨架期兜底不跑，P0 工程骨架落地后自动恢复触发（语义：有变更且可构建才运行门禁）。

## 2026-09-08 · P0 实施计划落盘 + 定位层地图补 docs/plans

- 新增 `docs/plans/2026-09-08-P0实施计划.md`（PLAN-P0-01）：P0 阶段的 PR 序列（工程骨架 / M20 治理构件 / M01 基础 / M14 骨架 / 收口）、各 PR 交付物与验收标准、决策点与 DoD，供新会话作为执行输入；定位层仓库地图与 §8 同步登记 `docs/plans/`（阶段实施计划）。
- W-6（NVD_API_KEY）经用户裁决撤销：不配置，security.yml 周审以匿名限流运行（TASK.md 同步删除）。
- 首次走 PR 流程合入（分支保护生效后的流程验证）。

## 2026-09-08 · 宪法 v1.1 → v1.2 修订：金额分值制 + 序列化/目录/MQ 消费模式裁决

### 用户裁决记录（2026-09-08）

1. **金额存储改 BIGINT 分值制**（覆盖 v1.0 沿用总 Spec 的 NUMERIC(18,2)）：全部金额列以 `BIGINT` 存"分"，应用层全程 `long`（禁浮点）；分↔元换算集中统一工具（convert 层 MoneyUtil），禁止散落 \*100 / ÷100；对外序列化以字符串承载。总 Spec §4.4 权威行本次同步修订；12 个模块 Spec 中 NUMERIC(18,2) 表述的批量同步登记 TASK.md（W-4）。
2. **JSON 序列化精度防线**：Jackson 全局注册 Long → String（ToStringSerializer）——雪花 ID 与金额分值超出 JS Number.MAX_SAFE_INTEGER（2^53）的部分前端以字符串接收，杜绝精度丢失；前端对应条款（金额/长整型一律字符串承载）同步进 web 宪法。
3. **注解优先**：能用注解 / 框架声明式能力解决的，禁止手写样板代码。
4. **RabbitMQ 消费确认改 AUTO 模式**（覆盖 M20 Spec §3.2/§7/§9 的"手动确认"选定）：@RabbitListener 注解驱动消费 + 容器 AUTO 确认（方法成功返回即确认、异常按重试策略 nack）+ 有界重试 + fy.dlx 死信 + 幂等不变——AUTO 语义等价"业务成功才确认"，并消除 MANUAL 模式漏写 ack 的实际事故源；M20 Spec 表述同步登记 TASK.md（W-5）。
5. **目录规范补充**（backend）：dto/（入参 DTO）· vo/（前端返回）· record/（特殊处理实体对象）· enum/（枚举一律 enum 类型，禁常量类模拟）· cache/（按业务领域的复杂缓存设计，{Domain}CacheService）· constants/（全部常量，常量类 public final static + 构造器私有）。
6. **Lua 脚本规范**：resources/lua/ 目录集中存放；经 Redisson RScript **预注册**（应用启动 SCRIPT LOAD 全部脚本缓存 SHA 单例，运行时 EVALSHA 调用，NOSCRIPT 异常回退重载）；所有原子性脚本使用的 key 必须携带 `{业务功能}` hash tag（Redis Cluster 语义，保证同一业务的 key 命中同一实例）。

### 同步执行记录（2026-09-08，W-4 / W-5 落地，用户裁决即批准）

- **模块 Spec 金额表述批量同步**：13 个文件 47 处（README 权威行 + 12 个模块 Spec 的表设计约定行/字段表/自审清单），统一为"BIGINT 分值制 / BIGINT，分"；14-iot 遥测 `value(NUMERIC)`、17-peis `result_form(NUMERIC)`、19-ops 指标值 `NUMERIC(18,4)` 等非金额 NUMERIC 不属本裁决范围，保留。
- **RabbitMQ 确认模式表述同步**：M20 §3.2/§3.4 共 6 处、14-iot 事件订阅 1 处、15-asset 事件订阅 1 处——"手动确认"改为"@RabbitListener 注解驱动 + 容器 AUTO 确认（语义等价'业务成功才确认'）"；IoTDA AMQP（Qpid JMS）客户端确认不在裁决范围，保持不变。
- **仓库分支保护**（原 W-2）：经 gh api 直接配置（required status checks 五项与 job 名精确对齐 + require PR + 禁止绕过），配置完成后删除对应工单。

### 与既有文档的差异说明

- v1.1 中"金额 NUMERIC(18,2) + BigDecimal"条款作废，A.4.2-8 重写；A.3 新增序列化条款；A.5-4/5 消费确认表述更新；B.1/C.3 目录表扩充。
- R2/R3 调研报告中 NUMERIC(18,2) 与"MANUAL ack"相关条目作为历史调研档案不改，以本 CHANGELOG 裁决为准。

## 2026-09-08 · 宪法 v1.0 → v1.1 修订：ORM 定稿 + 分层细化 + 命令呈现优化

### 用户裁决与修订要求（2026-09-08）

1. **ORM 定稿（TASK.md D-1 销项）**：MyBatis + MyBatis-Plus 协同——单表链式编程用 MP，复杂 SQL 走 mapper + XML。版本经官方核实：`mybatis-plus-spring-boot3-starter:3.5.17`（Boot 3 必须用 spring-boot3 后缀 starter，MP 3.5.16 起官方基线对齐 Boot 3.5 线）+ `mybatis-plus-jsqlparser:3.5.17`（3.5.9 起分页插件必需）；官方安装页明确禁止再引入 mybatis / mybatis-spring / mybatis-spring-boot-starter（MP starter 已内含 mybatis 3.5.19 + mybatis-spring 3.0.5）；Boot BOM 不托管，父 POM 锁版本。
2. **编码新条款**：禁止全限定类名声明（import 后用短类名）；禁用 @Deprecated API。
3. **C.4 常用命令呈现**：表格 → 代码块 + 精简注释（backend 与 web 两侧）。
4. **分层细化**（参考 commerce-customer 宪法）：B.1/B.2 吸收 mapper/entity 数据层归位、Service 接口+实现模式（IService/ServiceImpl）、循环依赖拆层切断禁 @Lazy 掩盖、跨 service 仅经接口复用查询；repository 表述统一为 mapper。

### 用户补充要求（同日追加）

5. **Redisson 正式纳入技术栈**：Redisson 4.7.0（redisson-spring-boot-starter，配套 redisson-spring-data-35，父 POM 锁版本）为正式选型组件，用于 watchdog 自动续期 / 可重入 / 读写锁等完整分布式锁语义；毫秒-秒级短持锁仍可选 BOM 托管的 RedisLockRegistry；防超卖最终由数据库唯一约束兜底。
6. **前端目录规范与层级依赖同颗粒度细化**：web 宪法 B.1 目录职责表与 B.2 层级依赖图按后端同等标准重写（views→components→composables→api/stores 单向依赖、types 全层可引用、跨 app 复用必须下沉 packages、禁反向与循环）。

### 与调研建议的差异说明

- R2 §8 调研主选为裸 MyBatis（mybatis-spring-boot-starter 3.0.5）；用户裁决升级为 MyBatis-Plus 协同方案（MP 为 MyBatis 增强层，包含并替代裸 starter），调研报告作为历史档案不改。
- T-R2-2（ORM 实体 equals/hashCode 与 Lombok 冲突）销项：MyBatis-Plus 实体无 JPA 持久化上下文代理语义，且宪法 A.1 已禁止 @Data 用于实体，风险已被覆盖。
- T-R2-5（log-impl 日志细节）保留，回填时点更新为 P0 实施期（MP SQL 日志经 mybatis-plus 配置项承载）。

## 2026-09-08 · 宪法体系 v1.0 初版生成 + CI 机制（方案 B）批准落地

### 变更范围

- 新增根定位层 `AGENTS.md`、子项目宪法 `backend/AGENTS.md` 与 `web/AGENTS.md`、根索引 `CLAUDE.md`、登记台 `TASK.md`。
- 调研依据（`docs/agmds-research/`，5 份，均经来源真实性评审）：CI 链方案、Java 与 SpringBoot 栈、数据与集成基础设施、前端栈、构建测试与 CI 落地细则。

### 用户裁决记录（2026-09-08）

| 裁决 | 内容 |
| --- | --- |
| CI 方案 | 选定方案 B「严格门禁」（5 job 全机器阻断）；方案 C 安全增强（CodeQL/Trivy/dependency-review 等）列为二期演进 |
| 落地范围 | CI 机制全套文件随本次交付（workflow / pre-commit / commitlint 等，路径过滤保证无代码阶段不空跑） |
| 命名模式 | agent 模式：AGENTS.md 承载宪法实体，CLAUDE.md 仅作根索引 |

### 合成裁决记录（宪法撰写时依调研报告"定稿时二选一"项作出，可经修宪流程推翻）

| 裁决 | 选择 | 理由 |
| --- | --- | --- |
| API 响应模式 | 成功 2xx 裸数据 + 失败一律 ProblemDetail（RFC 9457，properties.errorCode/traceId） | REST 状态语义化、openapi-typescript 契约链路类型最干净；envelope 候选因无权威规范且与 HTTP 语义冗余弃用 |
| JaCoCo 门禁 | 每模块 check 模式（BUNDLE LINE ≥0.80 + 核心包 PACKAGE 1.00）；聚合报告仅只读 | 官方 issue #902：check 不支持聚合报告；逐模块失败点可定位 |
| Spotless 格式化器 | palantir-java-format | Java 17 红线：google-java-format ≥1.22 需 JDK 21 |
| 前端覆盖率 | report-only 起步，不设硬阈值 | 金额计算全服务端（D5）；硬阈值易催生空断言测试，违反全局规范 |
| 前端单测框架 | Vitest 4.1.11（非 latest 5.0.0） | 5.0.0 GA 不足一季度，违反技术栈选型原则 2 |
| 并发锁 | Spring Integration RedisLockRegistry（BOM）+ DB 唯一约束兜底 | BOM 托管优先；号源/床位锁为短持锁场景 |
| 定时任务 | @Scheduled + ShedLock 6.10.0 | 多实例互斥最小方案；Quartz 本期不引入 |

### 评审修正记录

- 5 份调研报告经评审：3 份直接通过；2 份退回定点修正（JaCoCo "Boot BOM 托管"断言不实——经 spring-boot-dependencies/parent-3.5.16.pom 直查均无 jacoco 条目，已更正为"父 POM pluginManagement 显式锁定 0.8.15"；另修正 Spotless 版本表述、paths-filter latest 表述、withVueTs API 对齐、排除项笔误）。16 项来源 URL 抽查全部真实。
- 宪法体系经审核专员终审：修复 1 项 MAJOR（backend A.4 引用 TASK 编号断链）与 4 项 MINOR（B.5 槽位省略声明、HTML 实体写法、web B.3-4 四主题清单改引用式、TASK 补登 T-R2-5）后通过。

### CI 机制落地记录（同日，用户已批准）

- 落盘 12 件：`.github/workflows/ci.yml`（6 job 主链，images 按"谁变更构建谁"的 matrix 条目级条件）与 `security.yml`（OWASP 周审）、`.pre-commit-config.yaml`、`commitlint.config.mjs`、`scripts/check-encoding.py`（UTF-8 无 BOM/乱码/CRLF 三查）、`.gitattributes`/`.gitignore` 补强、`backend/Dockerfile` 与 `web/Dockerfile` 及各自 `.dockerignore`（P0 骨架后方可实际构建）。
- 验证：pre-commit 4.6.2 `run --all-files` 9 钩子全部通过（含 actionlint 校验 workflow）；check-encoding.py 对三类违规注入样本测试通过；既有文件零卫生违规。
- 有意偏差（相对 R5 蓝本）：frontend job 的 pnpm/action-setup 补 `package_json_file: web/package.json`（monorepo 在子目录，action 默认读根 package.json 会失败）；images 门禁由"两构建 job 全 success"细化为"谁变更构建谁"（job 级至少一侧成功 + 构建步骤按 matrix 条目校验对应变更与 verify 结果），修复纯前端 PR 失去 web 镜像构建验证的缺口。
