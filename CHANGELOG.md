# CHANGELOG（工程变更记录）

> 记录规则（根 AGENTS.md §7）：**先记再改**——任何宪法 / 规范 / 机制文件的修订，先在本文件登记（日期、范围、理由、裁决），再改正文。追加式保留全部历史。

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
