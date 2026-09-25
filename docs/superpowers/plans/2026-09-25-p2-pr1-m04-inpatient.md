# P2·PR-1 M04 住院/医嘱闭环实施计划（入院/床位/医嘱全链/出院/会诊 + CF-6 实装 + M06 审方薄切片 + M13 住院计费联动）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 把 M04 住院域从「P0 纯骨架（全 .gitkeep）」推进到住院业务主线可用——inpatient 工程挂接与 **V900–V999 新登记固定百位段**（首批 V901–V908；V900 已被 patient 通用段借用，版本唯一性兜底禁重复，registry 台账注记）+ 入院登记域（住院证/候床队列/预约入院/**同事务签发 I 型 visit_id**/入科确认 + OngoingVisitQuery SPI 注册）+ 床位管理域（床位图/五态状态机/占床流水/**转科四阶段编排**[停嘱截断+在途三分+床位流转+事件链]）+ 医嘱开立域（主子表模型/九类 order_type/成组医嘱/嘱托 prn/频次专业字典/开立校验四层[执业授权→过敏史→剂量途径→字典有效性]）+ 医嘱审核与控制（系统自动校验全员必经 + 用药类 M06 药师审方回执驱动迁移 + 停嘱/作废/撤销/重整）+ 转抄与执行闭环（双人转抄核对/长期医嘱**日切分解+当日增量补偿**/**CF-6 执行回签 API 实装**[W-33 闭合]/闭环追溯）+ 出院管理（在途清理/费用预审/结算放行双条件/挂账审批放行/出院带药/随访计划）+ 住院计费入口（欠费标识事件驱动 + 押金/一日清单前端直调 M13）+ 会诊管理（急会诊 30min 时限/超时升级为动作）+ **M06 住院用药审方薄切片**（pharmacy 侧 order_medication+review_task+审方工作台 API+回执事件）+ **M13 住院计费联动**（billing 侧六事件消费计价/停费/归属切分 + arrears_approval 挂账审批实体 + billing.arrears.approved 发布）+ 五条验收锚点 IT + workstation「住院管理」分组五页与药房审方台。

**架构：** 后端照抄 backend 宪法 B.1 模块内分层：controller（`@Valid` + `@AuditLog`）→ service/impl（事务边界与状态机；本 PR 将 `com.fuyun.inpatient.service.impl` 纳入父 POM JaCoCo PACKAGE LINE=1.00 名单——visit_id 签发与医嘱状态机属「核心业务状态机」、计费停费联动属「资金关联路径」，2026-09-25 主控裁决）→ mapper/entity（MP 单表链式 + CAS 条件更新防并发；床位占用/医嘱状态迁移/计划回签一律 DB 唯一约束 + 影响行数判定）。对外契约唯一出口 `inpatient/api` 包（`InpatientErrorCode` + 载荷 record）；消息设施归 `internal/`，复用 common 模板基类 `DomainEventSender`/`IdempotentConsumerSupport`（`@Qualifier` 显式定绑 `inpatientEventSender`/`inpatientConsumerSupport`）；**跨模块只走 api 端口与事件**——`patient/api`（`PatientContextResolver`/`VisitIdValidator`/`AllergyChecker`/`OngoingVisitQuery` 注册实现）、`system/api`（`AuditLog`/`PracticeCheckPort`）、`integration/api`（`MessagingGovernance`）、**新增 `billing/api` 只读端口**（出院预审取数，billing 侧实现），禁跨模块读表、禁 HTTP 自调用。**M06 薄切片与 M13 联动的消费端代码分别落 fuyun-pharmacy / fuyun-billing 模块内**（inpatient 不依赖两模块；pharmacy 消费 `inpatient.order.created` drug 子键生成审方任务并回执；billing 消费 inpatient 六事件驱动计价）。事件红线全链在位：inpatient 全部发布事件经 V901 登记或 V800 已登记（id 41–52 既有 + 本 PR 新增 id 65–72；billing.arrears.approved=id 73 落 billing 侧 V1002）、事务内 `ApplicationEventPublisher` → AFTER_COMMIT 出 `fy.topic`；消费走 `@RabbitListener` + `IdempotentConsumerSupport.consume` 标准三段式。前端照抄 web 宪法分层：`gen:api` 重生成 → `api/inpatient.ts` → 「住院管理」菜单分组五页（MainLayout children）+ 药房管理分组审方台一页；UI 沿用 PR-5/PR-6 设计地基（token 四文件 + `.fuy-dense`），不新出独立 UI 设计文档（偏差登记，见 GC34）。

**技术栈：** Java 17（Temurin）/ Spring Boot 3.5.16 / MyBatis-Plus 3.5.17（父 POM 锁定）/ Spring AMQP 3.2.12 / Flyway 11.7.2 / Spring Modulith 1.4.13 / Springdoc 2.8.17 / Testcontainers（镜像 tag 与 compose 一致：timescale/timescaledb:2.29.2-pg16、redis:8.10.1、rabbitmq:4.3.5-management）/ Vue 3.5.42 + TypeScript 5.9.3 + Element Plus 2.14.5 + Vitest 4.1.11。**零新增第三方依赖**（fuyun-inpatient pom 依赖面对齐 fuyun-nursing 实证清单并裁剪——不引 websocket/pharmacy/billing/nursing/iot，版本全部已在父 POM/BOM/catalog 锁定）。

**基线与分支：** 基线 `dev@0b202ad`（PR #53 合并点）；分支名 `feat/p2-pr1-m04-inpatient`；计划撰写日期 2026-09-25；执行目录 `D:\code\project\fuyun-medical`（Git Bash），勿在计划交付物之外切分支/提交。

---

> **本计划范围声明**：本计划只交付 P2 阶段 PR-1 切片（P2 实施计划 §3 PR-1 四要素 = M04 FU-M04-01~09 全部 + M06 审方薄切片 + CF-6 实装 + M13 住院侧事件契约冻结与联调 + W-33/W-34 触发 + W-42~W-47 登记），不新增功能需求；功能口径以 `docs/specs/modules/04-inpatient.md`（v1.1）为准，本计划对 Spec 的降级与顺延一律在 Task 16 以 Spec 落地注记登记。
>
> **not in scope（显式排除清单——不以「未列即默认」推断）**：M07/M08/M10/M12 子键消费端（P3/P4；本 PR 仅验证子键路由与 M13 通配消费）；**FU-M04-03 临床周边面**（病程记录嵌入 M09 编辑能力/诊断维护引用 M01 ICD 字典/检查检验结果查看调 M07/M08——三依赖模块均未交付，P4 顺延；本 PR 医生站=在院列表+医嘱开立+闭环追溯三区，Task 16 注记）；医嘱模板与套（FU-M04-04 加速录入项，P3 顺延）；M06 审方规则引擎/毒麻/抗菌药完整化（P3；薄切片=全部人工审方）；M06 住院摆药（PR-3 FU-M06-05）；nursing 侧 inpatient 事件消费与 `POST /ward-patients` 过渡通道退役（PR-3 W-34 五项）；`/ws/inpatient/ward/{wardId}` 护士站 WS 主题（消费方护士站实时提醒归 M05 完整化 PR-3，本 PR 不建 WS 端点）；M01 通知中心（不存在，欠费/会诊/随访通知降级为工作站列表可见）；打印模板（腕带/执行单/催缴单，M01 打印缺位）；抢救口头医嘱补录的「事后限时确认」完整流转（本 PR 仅落 oral_flag 标记 + 补录确认端点，限时催办 P3）；`fy.delay` 新档位扩展（会诊超时/执行超时提醒均降级为**读时惰性逾期判定**，档位扩展随 W-27 tick 方案 PR-4 一并设计）；SCHEDULED 逾期自动回队列定时任务（本 PR 手工 cancel 重排，注记 P3）；「全院一张床」跨病区签床调度规则参数化（本 PR 仅本位区队列排序，注记 P3）；预住院虚拟床位完整流转（本 PR 落 admission_type=预住院枚举与 SCHEDULED 状态，虚拟床位先检查编排 P3）；D-22/D-23/D-24（PR-3）；W-37~W-41/W-47 安全收敛（PR-4）。

## Global Constraints（每个任务隐含遵守）

1. **JDK17 命令前缀**：所有 Maven 命令一律 `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml ...`（系统默认 JDK21，忘加前缀即工具链红线违规）。
2. **Maven 形态（D-11 裁决沿袭）**：模块内验证 `-pl fuyun-inpatient -am`；billing/pharmacy 侧任务用 `-pl fuyun-billing -am` / `-pl fuyun-pharmacy -am`；跨模块改动带 `.am`；带 `-Dtest=`/`-Dit.test=` 过滤的命令必须同时带 `-Dsurefire.failIfNoSpecifiedTests=false`（IT 过滤另带 `-Dfailsafe.failIfNoSpecifiedTests=false`）。
3. **迁移号段红线（本 PR 新登记，2026-09-25 撰写期实测 `scripts/check-migration-governance.py` 全文 + 各模块目录）**：inpatient 固定百位段 **V900–V999**（`_SEGMENTS` 增 `"inpatient": ((900, 999), (500, None))` + CHANGELOG 先记再改 + `docs/migrations/flyway-version-registry.md` 台账登记）；首批 **V901–V908 共八个**（清单见「文件结构」）。**V900 已被 patient 通用段借用**（V900__trigram 索引，已应用不可改）——版本唯一校验兜底 inpatient 禁用 V900，registry 台账与 CHANGELOG 双处注记；inpatient 首批 V901 > 基线全局最大 V900，乱序守卫天然通过（零基线豁免兜底）。**pharmacy/billing 的本 PR 增量迁移走 V500+ 通用段、取 V1000+**（四位数通用段先例开创，CHANGELOG 注记；理由：pharmacy 段 (700,799)/billing 段 (600,699) 内任何号 ≤V799/V699 < 基线全局最大 V900，乱序守卫必拦；取 V1000+ 同时避开 inpatient 固定段区间防未来撞车）——pharmacy V1000（order_medication+review_task）、billing V1001（fee_ownership_split）/V1002（arrears_approval + event_registry id 73 种子）/V1003（charge_item 床位费等住院计价项目种子，**先实测** `grep -n "床位\|BED" backend/fuyun-billing/src/main/resources/db/migration/billing/*.sql`，已有种子则本迁移免）。**禁改已应用迁移**（V1–V5/V100–V105/V200–V204/V300–V303/V400–V403/V500–V503/V600–V606/V700–V706/V800–V808/V900 冻结）；**W-33 的 id 55 UPDATE 例外合法**（UPDATE 已有行非改 DDL，V702 UPDATE V605 先例）；迁移描述全小写下划线；种子幂等形态 `INSERT ... WHERE NOT EXISTS`；表注释全中文、枚举词表入列注释、部分唯一索引带 `WHERE deleted = 0`、各表挂 `public.fuyun_set_updated_at` 触发器（照 V602/V200 形态）。
4. **事件字面量三方一致红线**：每个事件的「event_registry 种子行（V901/V1002 payload_desc）↔ `InpatientMessagingConstants` 事件字面量 ↔ `inpatient/api` 载荷 record 组件名」三方逐字一致，任何一侧变更属 CF-6 契约变更须**双向评审**并在 PR 描述声明；Task 2 契约测试为可执行锚。
5. **事件 id 排定（全局递增，先例 V800 id 41–64）**：本 PR 新增 id **65–72**（inpatient 8 行，落 V901）+ id **73**（billing.arrears.approved，producer=billing，落 billing 侧 V1002）：65 `inpatient.visit.registered`、66 `inpatient.order.created`、67 `inpatient.order.audit-rejected`、68–72 `inpatient.consultation.requested/accepted/completed/overdue/cancelled`、73 `billing.arrears.approved`；status 一律 `ACTIVE`；`MessagingGovernanceIT` 总行断言 **64→73** 与 V901/V1002 种子**同任务落改**（PR-3/PR-5「种子+断言同任务」先例）。V800 既有 id 41–52（inpatient 事件族）与 id 53/54（pharmacy 回执）payload_desc **保持不动**（CF-6 冻结语义，字段级契约已含）。
6. **W-33 闭合义务（V901 UPDATE id 55，字段级契约全文）**：V901 迁移内 `UPDATE integration.event_registry SET payload_desc = ..., updated_at = now() WHERE id = 55`，新 desc 全文必须包含：`执行回签 API 契约（CF-6）：POST /api/v1/inpatient/order-plans/{no}/execute-confirm；请求字段：executorId(long,必填,执行护士员工ID)/executedAt(缺省服务器时间)/routeCheckResult(可选,给药途径核对结论)；响应字段：planNo/m04OrderNo/orderStatus(迁移后医嘱头状态)/planStatus(迁移后计划状态)；三态迁移语义=长期医嘱首个回签 plan PENDING→EXECUTED 且医嘱头 TRANSFERRED→EXECUTING / 临时医嘱单次回签医嘱头 TRANSFERRED→COMPLETED / 全部计划实例终态 EXECUTING→COMPLETED，并发布 inpatient.order.executed；幂等=重复回签已 EXECUTED 计划返回当前状态、不迁移不发事件（计划行状态 CAS 兜底）；调用方 M05（主路径进程内同步调用，辅路径 nursing.order-execution.completed 事件对账，双路到达仅计一次）`；迁移头注释声明「W-33 闭合：字段级契约定稿，替代 V800 pending 占位语义」。闭合后 TASK.md W-33 行删除（Task 16）。
7. **common 模板类多实例红线**：fuyun-common `DomainEventSender`/`IdempotentConsumerSupport` 为跨模块多实例 Bean——fuyun-app 上下文同类型多候选（patient/billing/pharmacy/outpatient/nursing/inpatient 六套），注入点必须 `@Qualifier` 显式定绑（`inpatientEventSender`/`inpatientConsumerSupport`），禁赌 Spring 回退链。
8. **事件事务红线（A.4.2-7）**：发布走「事务内 `ApplicationEventPublisher.publishEvent(InpatientDomainEvent)` → AFTER_COMMIT `@TransactionalEventListener(fallbackExecution=true)` → fy.topic 直发」（照 `OutpatientEventPublisher`/`NursingEventPublisher` 形态）；**事务内禁 MQ 发送与外部调用**；**不注册 Confirm/Returns 回调**（共享单槽位归 SystemEventPublisher，TASK.md W-11）；消费走 `@RabbitListener` + `IdempotentConsumerSupport.consume` 标准三段式；routing key 携带 order_type 子键的事件（`inpatient.order.created`/`inpatient.order.audited`）：交换机 `fy.topic`、routing key = `事件名 + "." + order_type`（如 `inpatient.order.audited.drug`），**登记名不带子键**（04-inpatient :258 R3-06 口径）；M13 全量消费方以 `inpatient.order.#` 通配绑定；pharmacy 以 `inpatient.order.created.drug` 精确绑定。
9. **IT 容器类级独占红线**：fuyun-app 新增 IT 一律各自声明三容器（PostgreSQL/Redis/RabbitMQ，tag 与 compose 严格一致 + `it/rabbitmq.conf` 挂载 + `@ServiceConnection`），禁收敛入 `FuyunStackITBase`（共享 broker 令手工捕获队列跨 IT 抢消费串扰）；登录/播种/POST 助手复用 `FuyunStackITBase`（admin/Fuyun@2026、seedReviewerUser、postJson）。
10. **覆盖率门禁**：`com.fuyun.inpatient.service.impl` 纳入父 POM 规则二 PACKAGE LINE=1.00（Task 1 落改；包不存在时规则零包平凡通过，首个 impl 落码即生效）；**既有八包 LINE=1.00 不回退**（billing/pharmacy 已在名单，本 PR 两模块新增 impl 自动覆盖）；BUNDLE LINE ≥ 0.80 全局；excludes（config/properties/dto/entity/constants/Application/ConverterImpl/生成代码）不变。
11. **模块依赖方向（B.1/B.2）**：fuyun-inpatient 只依赖 fuyun-common 与 system/patient/integration 的 **api 包** + **billing/api 只读端口**（Task 9 落，GC12）；禁依赖 fuyun-nursing/pharmacy/billing(impl)/outpatient/iot；禁跨模块读表；`internal/` 禁外引；fuyun-pharmacy/fuyun-billing 各自模块内的本 PR 增量不新增模块依赖（pharmacy 消费 inpatient 事件仅需 spring-amqp 既有依赖 + integration api；billing 同理）；`ApplicationModules.verify()` 随 fuyun-app 门禁自动把关（inpatient 入图即校验；ModulithBoundaryTest 为 verify() 形态非枚举式，无需改）。
12. **模块外契约登记（route 与端口）**：跨模块一律 `patient/api`（`PatientContextResolver#resolve`、`VisitIdValidator.isValid`（纯静态）、`AllergyChecker#listActiveAllergies`、`OngoingVisitQuery#hasOngoingVisit`——inpatient 注册第二个实现，语义=inpatient_visit 在院[REGISTERED/ADMITTED/DISCHARGE_REQUESTED]存在即 true，与 nursing 实现并存「任一命中即阻断」）与 `system/api`（`AuditLog`、`PracticeCheckPort#check(long employeeId, String grantType)`）、`integration/api`（`MessagingGovernance`）；**新增 `billing/api` 只读端口 `BillingAccountQueryPort`**（`DischargePrecheckView precheck(String visitId)`：未结清费用合计/押金余额/是否结清，billing 侧实现；**先实测** billing/api 包现有端口形态与命名惯例再落）；**禁 HTTP 自调用**（进程内端口或事件承载）。
13. **API 契约（A.3）**：前缀 `/api/v1/inpatient/`；资源复数小写连字符、动作子路径 POST；成功直出 DTO；失败 ProblemDetail + `properties.errorCode` = **`IP-xxxx`**；分页 `page`（0 基）/`size` → `{content,page,size,total}`（common `PageResult.of`）；业务号（admission_no/order_no/plan_no/consult_no/request_no）与 visit_id 一律 string 承载；Long/long 由全局 ToStringSerializer 输出字符串（A.3-8）；金额字段零落地（权威在 M13，工作站不传金额）。
14. **错误码接续**：`InpatientErrorCode` **IP-1001 起连续无重号（23 项，撰写期实测全仓 `"IP-"` 零命中）**：IP-1001 ADMISSION_NOT_FOUND(404) / IP-1002 ADMISSION_STATE_NOT_ALLOWED(409) / IP-1003 PATIENT_BLOCKED(409 冻结或合并中拦截) / IP-1004 BED_NOT_FOUND(404) / IP-1005 BED_STATE_NOT_ALLOWED(409) / IP-1006 BED_OCCUPIED(409) / IP-1007 VISIT_NOT_FOUND(404) / IP-1008 VISIT_STATE_NOT_ALLOWED(409) / IP-1009 ORDER_NOT_FOUND(404) / IP-1010 ORDER_STATE_NOT_ALLOWED(409) / IP-1011 ORDER_ITEM_INVALID(400 剂量/途径/频次校验不过) / IP-1012 PRACTICE_FORBIDDEN(403 执业授权未过) / IP-1013 ALLERGY_CONFLICT(409 过敏史强阳性) / IP-1014 PLAN_NOT_FOUND(404) / IP-1015 PLAN_STATE_NOT_ALLOWED(409) / IP-1016 TRANSFER_CHECK_INVALID(400 双人核对缺第二人/高危强制项) / IP-1017 DISCHARGE_NOT_ALLOWED(409 放行条件未满足) / IP-1018 DISCHARGE_REQUEST_NOT_FOUND(404) / IP-1019 CONSULTATION_NOT_FOUND(404) / IP-1020 CONSULTATION_STATE_NOT_ALLOWED(409) / IP-1021 FREQUENCY_NOT_FOUND(404) / IP-1022 PARAM_FORMAT_INVALID(400) / IP-1023 CONFLICT(409 唯一键冲突/未知病区/归属不符——与 IP-1022「入参格式」严格分开)。pharmacy 侧新增 PH-1019 REVIEW_TASK_NOT_FOUND(404) / PH-1020 REVIEW_TASK_STATE_NOT_ALLOWED(409) / PH-1021 MEDICATION_ORDER_NOT_FOUND(404)（**先实测** PH 末号=1018）。billing 侧新增 BILL-1032 ARREARS_APPROVAL_NOT_FOUND(404) / BILL-1033 ARREARS_APPROVAL_STATE_NOT_ALLOWED(409)（**先实测** BILL 末号=1031）。异常载体复用 `com.fuyun.common.exception.BizException`；**不新建异常类**。
15. **Redis 键规范（A.5-1）**：`fy:inpatient:seq:{type}:{yyyyMMdd}` 业务号当日流水（INCR，TTL 48h）；type ∈ `AD`（住院证）/`MO`（医嘱号）/`PL`（执行计划）/`CS`（会诊）/`DC`（出院申请）；**visit_id 流水独立键** `fy:inpatient:seq:VISIT:{yyyyMMdd}`（签发 `I + yyyyMMdd + %05d`，VisitIdValidator 结构校验自证）；禁无 TTL 键、禁 JDK 序列化（StringRedisTemplate 承载）；**本 PR 不写 Lua 脚本**（床位占用/状态迁移/回签幂等一律 DB 唯一约束 + CAS 条件更新）。
16. **visit_id 签发红线（04 Spec 红线 1）**：`visit_id` 只能由本模块按 M02 结构规范签发（类型码 `I` + 8 位日期 + 5 位当日流水，定长 14 位），在**入院登记确认事务内**签发并与 admission 状态变更 + patient_id 同事务落库，签发后不可变、不可复用；签发后立即 `VisitIdValidator.isValid` 自检（失败即事务回滚）。
17. **医嘱状态机红线（04 Spec 红线 2）**：医嘱状态迁移唯一经 `OrderStateMachineService` 校验（合法迁移表驱动，照 §3.3 全集）并**每次迁移写 order_status_log（只增）+ 发布对应事件**（同事务）；用药类医嘱 `CREATED` 停留期间语义=「待药师审」（按 order_audit.stage 区分，不新增状态）；禁任何模块外写医嘱状态。
18. **金额与计费红线（04 Spec 红线 3）**：M04 零资金字段落地；停嘱/作废/转科/出院的计费影响只通过事件通知 M13（billing 侧消费实现）；本模块 DTO 不采信前端传入金额。
19. **离院前置校验红线（04 Spec 红线 4）**：`DISCHARGED` 置位强制前置——全部长期医嘱已至终态 + 在途执行计划清零 + M13 费用预审通过（未结清走挂账审批，凭 `billing.arrears.approved` 转 READY）；未满足抛 IP-1017。
20. **开立校验四层顺序冻结（FU-M04-04）**：执业授权（`PracticeCheckPort.check`，未过 IP-1012）→ 过敏史与禁忌（`AllergyChecker`，强阳性 IP-1013）→ 剂量/途径/重复用药（用药类联动字典校验，不过 IP-1011）→ 项目/频次字典有效性（order_frequency 本地字典，无 IP-1021）；**grantType 取值先实测** pharmacy 开方链实参（`grep -n "PracticeCheckPort\|practiceCheck" backend/fuyun-pharmacy/src/main/java -r`）后对齐（处方权同款）。
21. **五大降级清单（Spec 声明而本 PR 缺位者，一律「登记降级 + 注记 P3」，禁静默删改 Spec 语义）**：① `/ws/inpatient/ward/{wardId}` WS 主题（消费方归 PR-3 M05 完整化）；② 通知中心（M01 缺位——欠费提醒/会诊超时升级通知/随访触达降级为工作站列表可见[欠费标识列/overdue 查询/随访计划表]）；③ 打印模板（腕带/执行单/催缴单，M01 打印缺位）；④ 会诊超时与执行计划超时的 fy.delay 档位（降级读时惰性逾期判定——查询时 `now > deadline` 置视图 overdue 标记并广播 `inpatient.consultation.overdue` 动作事件一次[DB flag 防重发]；fy.delay 档位扩展随 W-27 tick 方案 PR-4 一并设计）；⑤ SCHEDULED 逾期自动回队列与全院一张床调度参数（本 PR 手工 cancel 重排 + 本位区排序，注记 P3）。
22. **审计与脱敏**：入院登记/入科/床位操作/转科/医嘱开立/停嘱作废撤销/转抄/回签/出院确认/挂账审批/审方通过驳回一律 `@AuditLog(AuditActionType.WRITE)`；患者敏感字段（姓名/诊断/身份证）日志与事件载荷脱敏——事件载荷**不含患者姓名与诊断文本**（仅 patient_id/visit_id/业务号，04 Spec §9 脽敏要求）；操作者一律 `com.fuyun.common.context.OperatorContextHolder`。
23. **MyBatis-Plus 3.5.17 包路径（禁凭记忆写）**：`IService`/`ServiceImpl` 在 `com.baomidou.mybatisplus.spring.service[.impl]`；分页 `com.baomidou.mybatisplus.extension.plugins.pagination.Page`；条件更新（床位状态/医嘱状态/计划回签/转抄锁定）一律 `@Update` 注解 SQL + 影响行数判定（`FeeRecordMapper.casMarkFeesSettled` 实证形态）；注解 SQL 显式补 `deleted = 0`。
24. **单测构造范式（patient/billing/pharmacy/outpatient/nursing 实证）**：service impl 纯单测 = `@ExtendWith(MockitoExtension.class)` + `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), 实体.class)`（`@BeforeAll`）+ 构造器注入 collaborator mock + `ReflectionTestUtils.setField(impl, "baseMapper", mockMapper)`。
25. **注释/日志/编码**：注释与日志全中文（业务意图与「为什么」）；标识符英文；UTF-8 无 BOM、LF、文件末单换行；提交前 `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml spotless:apply`。
26. **提交规范**：conventional commits、中文 subject、body 每行 ≤100 字符（提交前 python 逐行 len 自查）；type 仅 build/chore/ci/docs/feat/fix/perf/refactor/revert/style/test。
27. **前端门禁（web C.4/C.5）**：`cd web && pnpm lint && pnpm format:check && pnpm type-check && pnpm test && pnpm build` 五连全绿；`api.d.ts` 重生成 diff 核对（本地 `git diff --exit-code` 核对，PR 描述登记）；组件 `<script setup lang="ts">` 零例外、禁 any；`api.d.ts` 生成物唯一来源（禁手写契约类型）；新页面自带合规形态：动作按钮 loading + 在途守卫 + 零出网用例、入参显式格式校验 4xx 提示、禁裸 parse。
28. **真栈环境与探针**：`docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d`；后端镜像重建 `docker build -f backend/Dockerfile -t fuyun/backend:dev backend`；backend 不发布宿主端口（容器内 curl 探测）；**无需卷重置**（inpatient 零基线豁免 + V901>V900；pharmacy V1000/billing V1001+ >V900 满足乱序守卫；**但真栈现库已应用至 V900，V901+ 全部新面，无重置需求**）；Task 16 真栈探针四项 = ① 迁移计数（`V90*` 计数探针预期 8 + pharmacy/billing `V100*` 计数探针预期 ≤3）② 事件登记行数断言（`integration.event_registry` 总行 64→73）③ 服务健康与契约面（容器内 `curl /v3/api-docs` 返回 200 且含 `/api/v1/inpatient/` 路径）④ compose 全服务 healthy。
29. **执行目录与分支**：`D:\code\project\fuyun-medical`（Git Bash）；自 `dev`（≥0b202ad）建分支 `feat/p2-pr1-m04-inpatient`；一切变更走 PR，实现 PR 质量门 = 全量门禁 + 真栈 + 浏览器真机（住院五页+审方台）→ `/code-review` 插件审核通过方可合并。
30. **先实测条款**：标注「先实测」的步骤必须先跑给定命令取实况再落码，禁凭记忆写 API/包路径/行号/计数字面；本计划撰写期已完成三批实测（号段/事件 registry/前后端工程面，事实已内嵌各条）；执行期发现漂移以实况为准修正并在 PR 描述登记。
31. **workspace 卫生**：执行全程勿动未跟踪的 `.superpowers/`、`.playwright-cli/` 等产物入 PR 提交面（除非计划明示）；AGENTS.md 若有用户未提交改动，勿动、勿纳入本 PR。
32. **Spec 落地注记义务（收口硬门槛）**：Task 16 必须在 `docs/specs/modules/04-inpatient.md` 追加 P2 PR-1 落地注记（五项降级清单/医嘱模板顺延/WS 顺延/计费入口前端直调形态[偏差登记：Spec §7 的 inpatient 前缀计费转调端点不落地，住院计费入口经前端直调 M13 既有 REST——门诊同款先例]/CF-6 id 55 字段级契约定稿落点），未落注记不得进收口。
33. **TASK.md 收口义务**：Task 16 回填——W-33 销项删除（V901 UPDATE 已履行）；W-34 更新触发状态（事件链已上线+验收 IT 通过，退役五项执行仍留 PR-3）；**W-42~W-47 六项新登记**（自 W-41 顺延，内容照 P2 计划 §2 裁决 2 原文：W-42 挂号费联动缺口、W-43 分诊诊区下拉硬编码、W-44 跨日票号冲突 500、W-45 医生站无在诊恢复、W-46 portal 诊区常量三科、W-47 患者查询 GET 无审计行）；P2 实施计划文件头「经用户批准后入库」标注移除（本 PR 首个提交将计划入库）。
34. **UI 设计权威与技能强制加载**：本 PR **不新出独立 UI 设计文档**（偏差登记：控制 PR 规模；PR-5 设计文档 §2–§7 通用地基 + PR-6 体温单/PDA 规范已覆盖 token/交互三态/移动布局）。Task 14/15 派发时 dispatch prompt 必须明确要求实现者先加载 `ui-ux-pro-max:ui-ux-pro-max`、`design`、`design-system`、`ui-styling` 四件；打磨面加载 `taste-skill:design-taste-frontend`、`high-end-visual-design`、`minimalist-ui`；未加载不得开工。床位图五态色标（空床/预占/占床/消毒/维修）采用 `--fuy-color-*` 语义层既有状态色系，禁自创色值。
35. **PR-6 Global Constraints 的继承边界**：本清单为 PR-6（41 条）的 PR-1 改写版——PR-6 中 nursing 专属条款不适用并已逐条替换：`nursing_ward_patient` 过渡通道面（PR-6 GC16/37/38/39）→ 本 PR 零涉及（nursing 侧不动）；量表引擎（GC18）→ 无；PDA 页宿主（GC20）→ 无；体温单符号（GC40）→ 无。跨模块新增面：pharmacy/billing 模块内增量任务（Task 12/13）遵守各自模块宪法同款条款（依赖方向/JaCoCo 已覆盖/错误码续号）。
36. **计划入库动作**：本 PR 首个提交（Task 1 Step 0）将 `docs/plans/2026-09-25-P2实施计划.md` 与本计划一并 `git add` 入库（P2 计划文件头「经用户批准后入库」标注同步移除，用户 2026-09-25 批准门已过）。

---

## 前置项（P2 计划 §3 PR-1 四要素 → 任务映射）

| # | 前置项 | 处置 | 落点 |
| --- | --- | --- | --- |
| 1 | inpatient 固定百位段 V900–V999 新登记（V900 被借注记）+ pharmacy/billing 增量 V1000+ 通用段先例 | CHANGELOG 先记再改 + `_SEGMENTS` 增行 + registry 台账 | **Task 1** |
| 2 | 事件 id 65–73 排定 + V901 UPDATE id 55（W-33 字段级契约全文） | V901 种子 + MessagingGovernanceIT 64→73 | **Task 2** |
| 3 | CF-6 实装：医嘱状态机事件族 + 类型子键分发 + 执行回签 API | Task 5/6/7/8 状态机与事件 + execute-confirm | **Task 5–8** |
| 4 | FU-M04-01 入院登记（候床队列/预约/同事务签发 visit_id/入科） | V902 + AdmissionController + OngoingVisitQuery SPI | **Task 3** |
| 5 | FU-M04-02 床位管理（床位图/五态/转科转床四阶段） | V903 + BedController + 转科编排 | **Task 4** |
| 6 | FU-M04-04 医嘱开立（主子表/九类/成组/嘱托/频次字典） | V904 + OrderController + 校验四层 | **Task 5** |
| 7 | FU-M04-05 医嘱审核与控制（系统审核+药师审方回执+停嘱作废撤销重整） | V905 + 审核服务 + 状态机收口 | **Task 6** |
| 8 | FU-M04-06 转抄与执行闭环（双人转抄/日切分解/回签/追溯） | V906 + 转抄/回签/日切/trace | **Task 7/8** |
| 9 | FU-M04-07 出院管理（在途清理/预审/结算放行/带药/随访） | V907 + DischargeController + billing 消费 | **Task 9** |
| 10 | FU-M04-08 住院计费入口（欠费提醒/押金/一日清单） | deposit.changed 消费 + 欠费标识 + 前端直调 | **Task 10** |
| 11 | FU-M04-09 会诊管理（急会诊 30min/超时升级动作） | V908 + ConsultationController + 五事件 | **Task 11** |
| 12 | M06 审方薄切片（review_task+人工通过驳回+回执回流） | pharmacy V1000 + 监听器 + 审方 API | **Task 12** |
| 13 | M13 住院侧契约冻结与联调（六事件消费+计价/停费/归属切分+arrears） | billing V1001–V1003 + 监听器 + 挂账审批 | **Task 13** |
| 14 | billing/api 只读端口（出院预审取数） | `BillingAccountQueryPort` + billing 实现 | **Task 9/13** |
| 15 | W-34 触发（inpatient 事件链上线+验收 IT） | Task 3/4 发布面 + Task 16 IT 断言 | **Task 16** |
| 16 | W-42~W-47 六项登记（TASK.md） | Task 16 收口面 | **Task 16** |
| 17 | 前端住院管理分组五页+药房审方台 | api/inpatient.ts + 六页 + spec.ts | **Task 14/15** |
| 18 | P2 阶段实施计划与本计划入库 | Task 1 Step 0 | **Task 1** |

## 文件结构（本计划全量改动面）

| 动作 | 文件 | 职责 |
| --- | --- | --- |
| 修改 | `CHANGELOG.md` | Task 1 先记再改（inpatient 号段 V900–V999 + V1000+ 通用段先例 + 事件 id 65–73 + jacoco 扩名单 + V900 借用注记）；Task 16 收口条目 |
| 修改 | `TASK.md` | Task 16 W-33 销项 + W-34 触发状态更新 + W-42~W-47 新登记 |
| 修改 | `scripts/check-migration-governance.py` | Task 1 `_SEGMENTS` 增 `"inpatient": ((900, 999), (500, None))` |
| 修改 | `docs/migrations/flyway-version-registry.md` | Task 1 登记 V901–V908 归属用途 + V1000–V1003 通用段增量 |
| 修改 | `docs/plans/2026-09-25-P2实施计划.md` | Task 1 Step 0 入库（文件头批准标注移除） |
| 修改 | `backend/fuyun-inpatient/pom.xml` | Task 1 依赖面（common/system/patient/integration/billing-api/modulith-api/springdoc/validation/amqp/data-redis/MP/lombok/test） |
| 修改 | `backend/fuyun-app/pom.xml` | Task 1 增 `fuyun-inpatient` 依赖 |
| 修改 | `backend/pom.xml` | Task 1 JaCoCo 规则二 includes 增 `com.fuyun.inpatient.service.impl` |
| 创建 | `backend/fuyun-app/src/main/java/com/fuyun/app/config/InpatientConfig.java` | Task 1 装配（@Import Web/Messaging 两配置，空壳占位后启用） |
| 创建 | `backend/fuyun-inpatient/src/main/resources/db/migration/inpatient/V901__upgrade_and_seed_inpatient_event_registry.sql` | Task 2 UPDATE id 55（W-33）+ 新登记 id 65–72 |
| 创建 | `V902__create_admission_visit.sql` | Task 3 admission / inpatient_visit（含 arrears_flag） |
| 创建 | `V903__create_bed_assign.sql` | Task 4 bed / bed_assign |
| 创建 | `V904__create_medical_order.sql` | Task 5 medical_order / medical_order_item / order_frequency（含种子） |
| 创建 | `V905__create_order_audit_log.sql` | Task 6 order_audit / order_status_log |
| 创建 | `V906__create_transfer_plan.sql` | Task 7 order_transfer_log / order_execute_plan |
| 创建 | `V907__create_discharge_followup.sql` | Task 9 discharge_request / follow_up_plan |
| 创建 | `V908__create_consultation.sql` | Task 11 consultation |
| 创建 | `backend/fuyun-inpatient/src/main/java/com/fuyun/inpatient/` 全包 | `api/`（InpatientErrorCode + 载荷 record + BillingAccountQueryPort 消费面）、`constants/`（InpatientMessagingConstants）、`enums/`（约 12 枚举）、`entity/`、`mapper/`、`dto/`、`vo/`、`service/` + `service/impl/`、`controller/`、`cache/`（InpatientSeqGate）、`internal/`（InpatientDomainEvent / InpatientEventPublisher / InpatientMessagingConfig / 三监听器）、`config/`（InpatientWebConfig）、`properties/`（InpatientProperties） | Task 2–11 分批落码 |
| 创建 | `backend/fuyun-pharmacy/src/main/resources/db/migration/pharmacy/V1000__create_medication_review.sql` | Task 12 order_medication / review_task |
| 修改 | `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/`（api/PharmacyErrorCode 增 3 项、internal/ 监听器、controller/、service/、entity/、mapper/、dto/、vo/） | Task 12 审方薄切片 |
| 创建 | `backend/fuyun-billing/src/main/resources/db/migration/billing/V1001__create_fee_ownership_split.sql` | Task 13 fee_ownership_split |
| 创建 | `backend/fuyun-billing/src/main/resources/db/migration/billing/V1002__create_arrears_approval_seed_event.sql` | Task 13 arrears_approval + event_registry id 73 |
| 创建（条件） | `backend/fuyun-billing/src/main/resources/db/migration/billing/V1003__seed_inpatient_charge_item.sql` | Task 13 床位费等住院计价项目种子（先实测，已有则免） |
| 修改 | `backend/fuyun-billing/src/main/java/com/fuyun/billing/`（api 端口 + internal/ 监听器 + controller/ + service/ + entity/ + mapper/） | Task 13 住院计费联动 |
| 创建 | `backend/fuyun-inpatient/src/test/java/com/fuyun/inpatient/**/*Test.java`（约 12 类） | Task 2–11 单测面 |
| 修改 | `backend/fuyun-app/src/test/java/com/fuyun/app/MessagingGovernanceIT.java` | Task 2 总行断言 64→73 |
| 创建 | `backend/fuyun-app/src/test/java/com/fuyun/app/InpatientAdmissionFlowIT.java`、`InpatientOrderFlowIT.java`、`InpatientTransferDischargeIT.java`、`InpatientDailyDecomposeIT.java`、`BillingInpatientLinkageIT.java` | Task 16 五条验收锚点 IT |
| 创建 | `web/apps/workstation/src/api/inpatient.ts` | Task 14 前端 API 层 |
| 创建 | `web/apps/workstation/src/views/inpatient/AdmissionView.vue` + `.spec.ts` | Task 14 入院登记台（路由 `/inpatient/admission`） |
| 创建 | `web/apps/workstation/src/views/inpatient/BedMapView.vue` + `.spec.ts` | Task 14 病区床位图（`/inpatient/beds`） |
| 创建 | `web/apps/workstation/src/views/inpatient/DoctorStationView.vue` + `.spec.ts` | Task 15 住院医生站（`/inpatient/station`） |
| 创建 | `web/apps/workstation/src/views/inpatient/TransferWorklistView.vue` + `.spec.ts` | Task 15 转抄工作台（`/inpatient/transfer`） |
| 创建 | `web/apps/workstation/src/views/inpatient/DischargeManageView.vue` + `.spec.ts` | Task 15 出院管理（`/inpatient/discharge`） |
| 创建 | `web/apps/workstation/src/views/pharmacy/ReviewTaskView.vue` + `.spec.ts` | Task 15 药房审方台（`/pharmacy/review`，药房管理分组） |
| 修改 | `web/apps/workstation/src/router/index.ts`、`views/layout/components/AppSidebar.vue` | Task 14 六路由 + 「住院管理」分组 + 药房管理分组增项 |
| 修改 | `web/packages/shared/src/api.d.ts` | Task 14 `pnpm gen:api` 重生成 |
| 修改 | `docs/specs/modules/04-inpatient.md` | Task 16 P2 PR-1 落地注记 |

---

### Task 1: 工程前置与号段登记（P2 计划入库 + CHANGELOG 先记再改 + `_SEGMENTS` + 台账 + pom 三处 + InpatientConfig + jacoco + 错误码骨架 + SeqGate）

**Files:**
- Modify: `docs/plans/2026-09-25-P2实施计划.md`（文件头批准标注移除）
- Modify: `CHANGELOG.md`（新增 2026-09-25 条目）
- Modify: `scripts/check-migration-governance.py:29-42`（`_SEGMENTS` 增行）
- Modify: `docs/migrations/flyway-version-registry.md`（V901–V908 + V1000–V1003 登记）
- Modify: `backend/fuyun-inpatient/pom.xml`
- Modify: `backend/fuyun-app/pom.xml`（依赖清单增 `fuyun-inpatient`）
- Modify: `backend/pom.xml:263-281`（JaCoCo 规则二 includes 增 `com.fuyun.inpatient.service.impl`）
- Create: `backend/fuyun-app/src/main/java/com/fuyun/app/config/InpatientConfig.java`
- Create: `backend/fuyun-inpatient/src/main/java/com/fuyun/inpatient/api/InpatientErrorCode.java`
- Create: `backend/fuyun-inpatient/src/main/java/com/fuyun/inpatient/cache/InpatientSeqGate.java`
- Create: `backend/fuyun-inpatient/src/test/java/com/fuyun/inpatient/cache/InpatientSeqGateTest.java`

**Interfaces:**
- Consumes: `com.fuyun.common.context.OperatorContextHolder`、`PageResult`。
- Produces（后续任务依赖的冻结面）:
  - `com.fuyun.inpatient.api.InpatientErrorCode`——常量类/枚举（照 `PharmacyErrorCode` 实测形态复刻），取值照 Global Constraints 14（24 项 IP-1001…IP-1023 连续）。
  - `com.fuyun.inpatient.cache.InpatientSeqGate`——`public String nextNo(String type)`：type ∈ `AD`/`MO`/`PL`/`CS`/`DC` → `{type}{yyyyMMdd}{%05d}`（例 `MO2026092500001`）；`public String nextVisitId()`：键 `fy:inpatient:seq:VISIT:{yyyyMMdd}` → `I{yyyyMMdd}{%05d}`；每次 INCR 后 EXPIRE 48h；StringRedisTemplate 承载；type 非法抛 `IllegalArgumentException`。幂等与并发语义：Redis INCR 原子性保证当日流水不重号（visit_id 唯一性另由 DB 唯一约束兜底）。
  - `com.fuyun.app.config.InpatientConfig`——Task 1 落空壳 `@Configuration` + javadoc 占位说明（`@Import({InpatientWebConfig.class, InpatientMessagingConfig.class})` 待 Task 2/3 配置类落地后启用，避免中间态不可编译）。
  - 构建面事实：`mvn -pl fuyun-app -am verify` 后 fuyun-inpatient 随反应堆构建且 `ApplicationModules.verify()` 通过。

- [ ] **Step 0: P2 计划入库与基线确认**

```bash
git checkout -b feat/p2-pr1-m04-inpatient dev    # 自 dev（≥0b202ad）建分支
git log --oneline -1 --format="%H %s"            # 记录基线提交号
```

`git add docs/plans/2026-09-25-P2实施计划.md docs/superpowers/plans/2026-09-25-p2-pr1-m04-inpatient.md`——本提交随 Step 6 一并提交（P2 计划文件头「经用户批准后入库」行删除、审批状态改「已批准（2026-09-25）」）。

- [ ] **Step 1: CHANGELOG 先记再改**

在 `CHANGELOG.md` 顶部新增 2026-09-25 条目，内容四点：①inpatient 固定百位段 V900–V999 登记（首批 V901–V908；V900 已被 patient 通用段借用、版本唯一校验兜底禁重复）；②V500+ 通用段四位数号先例开创（pharmacy V1000 / billing V1001–V1003——pharmacy/billing 固定段内号 ≤V799/V699 小于基线全局最大 V900 被乱序守卫拦截，故取 V1000+ 并避开 inpatient 段）；③事件 id 65–73 排定（65–72 inpatient 落 V901、73 billing.arrears.approved 落 billing V1002；含 W-33 id 55 UPDATE 义务声明）；④JaCoCo 规则二纳入 `com.fuyun.inpatient.service.impl`。

- [ ] **Step 2: `_SEGMENTS` 增行与迁移治理自检**

```python
# scripts/check-migration-governance.py _SEGMENTS 增行（照 nursing 先例形态）
"inpatient": ((900, 999), (500, None)),
```

`docs/migrations/flyway-version-registry.md` 登记 V901–V908（inpatient）与 V1000/V1001/V1002/V1003（通用段增量，归属 pharmacy/billing）行目。运行 `python scripts/check-migration-governance.py`（此时尚无新迁移文件，预期通过）。

- [ ] **Step 3: pom 三处与装配类**

`backend/fuyun-inpatient/pom.xml` 依赖面对齐 fuyun-nursing 实证清单并裁剪：fuyun-common、fuyun-system、fuyun-patient、fuyun-integration、**fuyun-billing（仅 api 面消费——billing 模块整体依赖会引入 impl，需实测 nursing 是否依赖了完整 fuyun-patient；若宪法允许「模块依赖=api 包可见性由 Modulith 把关」则照 outpatient→billing 既有依赖先例；先实测 `grep -n "fuyun-billing" backend/fuyun-outpatient/pom.xml backend/fuyun-nursing/pom.xml`，outpatient 若依赖 billing 则照抄，若零先例则 Task 9 改为 billing api 端口反转注册[端口接口落 fuyun-common 或 integration]并在偏差清单登记）**、springdoc/validation/amqp/data-redis/MP/lombok/test；`backend/fuyun-app/pom.xml` 增 fuyun-inpatient；`backend/pom.xml` JaCoCo includes 增行；创建 `InpatientConfig` 空壳。

- [ ] **Step 4: 错误码骨架与 SeqGate 落码（TDD）**

先写 `InpatientSeqGateTest`（五类业务号 + visit_id 六用例：格式/流水递增/TTL 设置/type 非法拒绝），跑失败；再落 `InpatientErrorCode`（23 项全文）与 `InpatientSeqGate` 实现（照 `NursingSeqGate` 形态复刻，Redis 键见 GC15）；跑通过。

- [ ] **Step 5: 装配冒烟**

`JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-app -am verify -DskipITs`——预期编译通过、Modulith verify 通过（inpatient 入图）。

- [ ] **Step 6: Commit**

```bash
git add CHANGELOG.md scripts/check-migration-governance.py docs/migrations/flyway-version-registry.md \
  docs/plans/2026-09-25-P2实施计划.md docs/superpowers/plans/2026-09-25-p2-pr1-m04-inpatient.md \
  backend/fuyun-inpatient/pom.xml backend/fuyun-app/pom.xml backend/pom.xml \
  backend/fuyun-app/src/main/java/com/fuyun/app/config/InpatientConfig.java \
  backend/fuyun-inpatient/src/main/java backend/fuyun-inpatient/src/test
git commit -m "feat: M04 工程前置——inpatient 号段登记与装配挂接（V900 段+事件 id 65-73 排定）"
```

### Task 2: 事件契约升级与消息装配（V901 UPDATE id 55[W-33] + 新登记 id 65–72 + MessagingGovernanceIT 64→73 + 发布器/消费者装配）

**Files:**
- Create: `backend/fuyun-inpatient/src/main/resources/db/migration/inpatient/V901__upgrade_and_seed_inpatient_event_registry.sql`
- Create: `backend/fuyun-inpatient/src/main/java/com/fuyun/inpatient/constants/InpatientMessagingConstants.java`
- Create: `backend/fuyun-inpatient/src/main/java/com/fuyun/inpatient/api/payload/`（8 个载荷 record）
- Create: `backend/fuyun-inpatient/src/main/java/com/fuyun/inpatient/internal/InpatientDomainEvent.java`、`InpatientEventPublisher.java`、`InpatientMessagingConfig.java`、`InpatientEventConstantsBinding.java`（可选，订阅常量归并 MessagingConstants）
- Modify: `backend/fuyun-app/src/test/java/com/fuyun/app/MessagingGovernanceIT.java:309,314`（64→73）
- Test: `backend/fuyun-inpatient/src/test/java/com/fuyun/inpatient/internal/InpatientEventPublisherTest.java`、`backend/fuyun-inpatient/src/test/java/com/fuyun/inpatient/constants/InpatientMessagingContractTest.java`

**Interfaces:**
- Consumes: `DomainEventSender`（common）、`MessagingGovernance`（integration api）。
- Produces:
  - `InpatientMessagingConstants`——事件名常量 20 个（V800 既有 12 + V901 新增 8）+ SUBSCRIBED_EVENT_TYPES（`pharmacy.medication-order.audit-completed`、`pharmacy.medication-order.audit-rejected`、`billing.deposit.changed`、`billing.settlement.completed`、`billing.arrears.approved` 五订阅）+ routing key 子键拼接方法 `String withTypeKey(String eventType, String orderType)`。
  - 载荷 record（组件名与 V901/V800 payload_desc 逐字一致）：`VisitRegisteredPayload(visitId, patientId, admissionNo, registeredAt, insuranceType)`、`OrderCreatedPayload(m04OrderNo, visitId, patientId, orderType, orderClass, standbyFlag, groupNo, freqCode, items)`（`items` 为 `List<OrderCreatedItem>`：itemSeq/itemCode/itemName/dosage/unit/route/quantity/itemType）、`OrderAuditRejectedPayload(m04OrderNo, visitId, patientId, rejectReason, rejectedAt)`、`ConsultationPayload` 五态共用（consultNo/visitId/patientId/fromDeptId/toDeptId/urgency/requestedAt/responseDeadline/acceptedAt/completedAt/overdueAt/cancelledAt/reason——按事件取用子集）。
  - `InpatientEventPublisher`——`@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)` + `@Qualifier("inpatientEventSender")`，照 `NursingEventPublisher` 形态复刻。
  - `InpatientMessagingConfig`——声明 `inpatientEventSender`（DomainEventSender Bean）/`inpatientConsumerSupport`（IdempotentConsumerSupport Bean）+ 五订阅队列声明与绑定（`fy.topic`；pharmacy 回执队列绑定 `pharmacy.medication-order.audit-completed/rejected` 精确键；billing 三事件精确键）。

- [ ] **Step 1: V901 迁移全文（UPDATE id 55 + INSERT id 65–72）**

SQL 要点（全文按 V800 先例形态）：头注释三行（W-33 闭合声明/新增 8 行说明/三方一致红线引用）；`UPDATE integration.event_registry SET payload_desc = '<GC6 全文>', updated_at = now() WHERE id = 55;`；8 行 `INSERT INTO integration.event_registry (id, event_type, producer_module, status, payload_desc) VALUES (65, 'inpatient.visit.registered', 'inpatient', 'ACTIVE', '入院登记：visitId/patientId/admissionNo/registeredAt/insuranceType；M13 医保入院办理登记依据'), (66, 'inpatient.order.created', ..., '医嘱开立：m04OrderNo/visitId/patientId/orderType/orderClass/standbyFlag/groupNo/freqCode/items[]（itemSeq/itemCode/itemName/dosage/unit/route/quantity/itemType）；routing key 携带类型子键（drug 子键→M06 审方任务生成）；M06 消费 drug 子键（P2 薄切片）'), (67, 'inpatient.order.audit-rejected', ..., '医嘱审核驳回：m04OrderNo/visitId/patientId/rejectReason/rejectedAt；M06 回执驱动，医生站修改重提路径'), (68~72, 'inpatient.consultation.requested/accepted/completed/overdue/cancelled', ..., '会诊申请/响应/完成/超时升级动作[状态停留 REQUESTED 仍可响应]/取消：consultNo/visitId/patientId/fromDeptId/toDeptId/urgency/requestedAt/responseDeadline/...；overdue 为动作广播非状态迁移') ... WHERE NOT EXISTS`（幂等形态照 V607）。

- [ ] **Step 2: MessagingGovernanceIT 断言同任务落改**

`:309` 与 `:314` 的 `isEqualTo(64)` → `isEqualTo(73)`（注释同步「V901 +8 / V1002 +1」）。

- [ ] **Step 3: 契约测试先行（TDD）**

`InpatientMessagingContractTest`：断言 `InpatientMessagingConstants` 20 个事件名与 V901/V800 登记名逐字一致（读迁移文件文本解析或常量硬断言）、五订阅事件不在发布面（死订阅禁令反向）、`withTypeKey("inpatient.order.audited","drug")` = `inpatient.order.audited.drug`。跑失败。

- [ ] **Step 4: 常量/载荷/发布器/装配落码**

照 Interfaces 冻结面落码；`InpatientEventPublisherTest`（mock sender 断言 eventType/payload/traceId 透传）。跑通过。

- [ ] **Step 5: 迁移治理与模块验证**

`python scripts/check-migration-governance.py`（V901 通过）+ `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-inpatient -am verify -DskipITs`。

- [ ] **Step 6: Commit**

```bash
git add backend/fuyun-inpatient/src backend/fuyun-app/src/test/java/com/fuyun/app/MessagingGovernanceIT.java
git commit -m "feat: CF-6 契约升级——id55 字段级契约定稿(W-33)与 inpatient 事件族补登 id65-72"
```

### Task 3: 入院登记域（FU-M04-01：admission/inpatient_visit 两表 + 六端点 + 同事务签发 visit_id + OngoingVisitQuery SPI + visit 事件族发布）

**Files:**
- Create: `backend/fuyun-inpatient/src/main/resources/db/migration/inpatient/V902__create_admission_visit.sql`
- Create: `entity/Admission.java`、`entity/InpatientVisit.java`、`mapper/AdmissionMapper.java`、`mapper/InpatientVisitMapper.java`
- Create: `enums/AdmissionStatus.java`（WAITING/SCHEDULED/COMPLETED/CANCELLED）、`enums/VisitStatus.java`（REGISTERED/ADMITTED/DISCHARGE_REQUESTED/DISCHARGED/CANCELLED）、`enums/AdmissionType.java`、`enums/SourceType.java`
- Create: `dto/AdmissionCreateRequest.java`、`dto/AdmissionScheduleRequest.java`、`dto/VisitRegisterRequest.java`、`dto/WardAdmitRequest.java`
- Create: `vo/AdmissionVO.java`、`vo/InpatientVisitVO.java`
- Create: `service/AdmissionService.java` + `service/impl/AdmissionServiceImpl.java`
- Create: `controller/AdmissionController.java`
- Create: `service/impl/InpatientOngoingVisitQuery.java`（patient SPI 实现）
- Modify: `backend/fuyun-inpatient/src/main/java/com/fuyun/inpatient/config/InpatientWebConfig.java`（若 Task 2 未建则本任务建）+ `InpatientConfig` 启用 @Import
- Test: `service/impl/AdmissionServiceImplTest.java`

**Interfaces:**
- Consumes: `InpatientSeqGate.nextVisitId()/nextNo("AD")`、`PatientContextResolver.resolve`（FROZEN/MERGED 拦截 → IP-1003）、`InpatientEventPublisher`、`VisitIdValidator.isValid`。
- Produces:
  - REST 六端点（`/api/v1/inpatient` 前缀）：`POST /admissions`（住院证登记，status=WAITING 入队）、`GET /admissions?status=&page=&size=`（候床队列，排序=急诊优先>预约时段>候床时长）、`POST /admissions/{no}/schedule`（预约入院/预住院：SCHEDULED，目标床位 RESERVED 联动调 BedService.reserveForAdmission）、`POST /admissions/{no}/cancel`（WAITING/SCHEDULED→CANCELLED，SCHEDULED 联动释放预占床位）、`POST /admissions/{no}/register`（入院登记确认：**同事务** admission→COMPLETED + 签发 I 型 visit_id 落 inpatient_visit[REGISTERED] + 发布 `inpatient.visit.registered`）、`POST /visits/{visitId}/admit-ward`（入科确认：visit→ADMITTED + 床位 RESERVED→OCCUPIED + bed_assign 开流水 + 发布 `inpatient.visit.admitted`）。
  - `InpatientOngoingVisitQuery implements OngoingVisitQuery`——`hasOngoingVisit(long patientId)`：`selectCount(inpatient_visit where patient_id=? and status in (REGISTERED, ADMITTED, DISCHARGE_REQUESTED) and deleted=0) > 0`；装配归 InpatientWebConfig @Import（与 nursing 实现并存，任一命中即阻断）。
  - `InpatientVisit` 实体字段冻结（V902 列面）：id/admission_id/visit_id(唯一)/patient_id/current_dept_id/current_ward_id/current_bed_id/attending_doctor_id/nursing_level/insurance_type/admission_diagnosis(脱敏承载)/registered_at/admitted_at/discharge_requested_at/discharged_at/discharge_way/arrears_flag(默认 false，Task 10 消费 deposit.changed 刷新)/status + 审计字段。

- [ ] **Step 1: V902 DDL（两表 + 触发器 + 唯一约束）**

要点：admission 表（admission_no 唯一、status 列注释含四态词表、source_visit_id 可空引用门诊 visit）；inpatient_visit 表（visit_id `VARCHAR(14)` 唯一索引、patient_id+status 组合索引、`uk_visit_id` 兜底签发幂等）；两表挂 `fuyun_set_updated_at` 触发器；列注释全中文。

- [ ] **Step 2: 单测先行（TDD）**

`AdmissionServiceImplTest` 用例：①住院证登记入队（WAITING + 排序权重）②登记确认同事务签发 visit_id（mock SeqGate 返回 `I2026092500001`，断言 visit_id 落库+admission COMPLETED+`VisitRegisteredPayload` 发布——TransactionalEventPublisher mock 捕获）③FROZEN 患者登记被拒（IP-1003）④visit_id 结构自检失败回滚（SeqGate 返回畸形值 `I99` → 事务异常）⑤SCHEDULED 取消联动床位释放（mock BedService）⑥入科确认床位流转+`VisitAdmittedPayload` 发布。跑失败。

- [ ] **Step 3: 实现落码（entity/mapper/dto/vo/service/controller/SPI）**

状态条件更新用 `@Update` CAS（`UPDATE admission SET status='COMPLETED' WHERE admission_no=? AND status IN ('WAITING','SCHEDULED') AND deleted=0`，影响行数 0 抛 IP-1002）；登记确认 `@Transactional` 全链（红线下自检 `VisitIdValidator.isValid` 失败抛 IllegalStateException 回滚）；controller 六端点 `@Valid` + `@AuditLog(WRITE)` + 分页 `PageResult.of`。

- [ ] **Step 4: 跑测试通过 + 模块验证**

`JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-inpatient -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false`。

- [ ] **Step 5: Commit**

```bash
git add backend/fuyun-inpatient/src
git commit -m "feat: FU-M04-01 入院登记域——同事务签发 I 型 visit_id 与入科确认事件链"
```

### Task 4: 床位管理域（FU-M04-02：bed/bed_assign + 床位图 + 五态状态机 + 转科四阶段编排 + 转床 + bed.changed/visit.transferred 事件）

**Files:**
- Create: `V903__create_bed_assign.sql`
- Create: `entity/Bed.java`、`entity/BedAssign.java`、`mapper/BedMapper.java`、`mapper/BedAssignMapper.java`
- Create: `enums/BedStatus.java`（FREE/RESERVED/OCCUPIED/DISINFECTING/MAINTENANCE）、`enums/BedAttr.java`（NORMAL/PRIVATE/EXTRA）、`enums/AssignType.java`、`enums/TransferType.java`
- Create: `dto/BedReserveRequest.java`、`dto/BedAssignRequest.java`、`dto/TransferRequest.java`（转科四阶段执行体）、`dto/ChangeBedRequest.java`
- Create: `vo/BedMapVO.java`、`vo/TransferResultVO.java`
- Create: `service/BedService.java` + `impl/BedServiceImpl.java`、`service/TransferService.java` + `impl/TransferServiceImpl.java`
- Create: `controller/BedController.java`（床位图与床位操作）
- Modify: `controller/` 增 `VisitTransferController.java`（transfer/change-bed 两端点）
- Test: `service/impl/BedServiceImplTest.java`、`service/impl/TransferServiceImplTest.java`

**Interfaces:**
- Consumes: `MedicalOrderService.stopAllForTransfer`（Task 5 冻结面，转科自动停嘱——接口先行声明本任务 mock）、`InpatientEventPublisher`。
- Produces:
  - REST：`GET /beds/map?wardId=`（床位图：bed_no/attr/status/占用 visit 摘要/性别限制）、`POST /beds/{id}/reserve|assign|release|disinfect-done|maintain|maintain-done`（六操作全 CAS + `inpatient.bed.changed` 广播[载荷 wardId/bedId/bedNo/bedStatus/patientId——V800 id 52 desc 逐字]）、`POST /visits/{visitId}/transfer`（转科四阶段编排事务）、`POST /visits/{visitId}/change-bed`（同病区转床轻量路径）。
  - `TransferServiceImpl.transfer` 四阶段时序（04 Spec §3.5 五步，单事务 + 事件组）：①转出病区全部长期医嘱自动 STOPPED（stop_reason=转科，停嘱时间=服务器时间）②在途三分（临时计划保留随患者/长期计划作废/费用不改写——本模块只处理前两项，费用归 M13）③床位流水闭合（转出床→DISINFECTING、目标床 RESERVED→OCCUPIED、visit current_ward/current_bed 原子更新）④发布 `inpatient.visit.transferred`（载荷 visitId/patientId/fromWardId/fromBedId/toWardId/toBedId/transferredAt——V800 id 49 desc 逐字）。
  - `BedService.reserveForAdmission`/`releaseForAdmission`（Task 3 联动面）。
  - CAS 防重复占床：`UPDATE bed SET status='OCCUPIED', visit_id=? WHERE id=? AND status IN ('FREE','RESERVED') AND deleted=0`，影响行数 0 抛 IP-1006/IP-1005。

- [ ] **Step 1: V903 DDL**（bed 表：bed_no+ward_id 唯一、status 五态词表入注释、allow_gender；bed_assign 只增表：ended_at 可空、未闭合行唯一部分索引 `WHERE ended_at IS NULL AND deleted = 0`）

- [ ] **Step 2: 单测先行（TDD）**

`BedServiceImplTest`：①占床 CAS 防重（FREE→OCCUPIED 成功 / 已 OCCUPIED 再占抛 IP-1006）②消毒中禁分配（DISINFECTING→assign 抛 IP-1005）③床位图聚合（占用摘要+包床标记）④bed.changed 广播。`TransferServiceImplTest`：①四阶段全链（mock 医嘱停嘱/断言床位三段流转/断言 transferred 载荷六字段）②目标床位被占编排失败回滚（mock 抛异常断言转出床状态复原）③同病区转床轻量路径（无停嘱调用）。

- [ ] **Step 3: 实现落码**（转科编排 `@Transactional`；在途三分规则：临时[order_class=stat]PENDING 计划保留、长期 PENDING 计划批量 CANCELLED[SQL 批更新]）

- [ ] **Step 4: 跑测试通过 + 模块验证**

- [ ] **Step 5: Commit**

```bash
git add backend/fuyun-inpatient/src
git commit -m "feat: FU-M04-02 床位管理域——五态状态机与转科四阶段编排"
```

### Task 5: 医嘱开立域（FU-M04-04：medical_order 主子表 + order_frequency 专业字典 + 九类 order_type + 成组/嘱托 + 开立校验四层 + 状态机骨架 + order.created 事件）

**Files:**
- Create: `V904__create_medical_order.sql`
- Create: `entity/MedicalOrder.java`、`entity/MedicalOrderItem.java`、`entity/OrderFrequency.java`、`mapper/` 三 Mapper
- Create: `enums/OrderType.java`（DRUG/LAB/EXAM/SURGERY/BLOOD/NURSING/DIET/CONSULT/DISCHARGE_MED 九类，含 routing 子键小写映射）、`enums/OrderClass.java`（LONG/STAT）、`enums/OrderStatus.java`（CREATED/AUDITED/AUDIT_REJECTED/TRANSFERRED/EXECUTING/COMPLETED/CANCELLED/STOPPED 八态 + 合法迁移表）
- Create: `dto/OrderCreateRequest.java`（头+项列表）、`dto/OrderItemRequest.java`
- Create: `vo/MedicalOrderVO.java`、`vo/OrderDetailVO.java`
- Create: `service/MedicalOrderService.java` + `impl/MedicalOrderServiceImpl.java`（开立+查询+停嘱面 `stopAllForTransfer`）、`service/OrderStateMachineService.java` + `impl/OrderStateMachineServiceImpl.java`（迁移表驱动）
- Create: `controller/OrderController.java`
- Test: `service/impl/MedicalOrderServiceImplTest.java`、`service/impl/OrderStateMachineServiceImplTest.java`

**Interfaces:**
- Consumes: `PracticeCheckPort.check`（**先实测** grantType 实参——`grep -rn "practiceCheckPort\|PracticeCheckPort" backend/fuyun-pharmacy/src/main/java | head`，对齐处方权取值）、`AllergyChecker.listActiveAllergies`、`InpatientSeqGate.nextNo("MO")`。
- Produces:
  - REST：`POST /visits/{visitId}/orders`（开立：校验四层→CREATED 落库→发布 `inpatient.order.created`[routing key 带子键]）、`GET /orders?visitId=&class=&page=&size=`、`GET /orders/{no}`（详情含项）。
  - `OrderStateMachineService`——`void transition(MedicalOrder order, OrderStatus to, String reason, Long operator)`：合法迁移表（04 Spec §3.3 全集硬编码）校验非法抛 IP-1010 + `order_status_log` 只增写入 + 状态 CAS 更新（**发布事件由调用方在同事务附带**——状态机服务不发事件，事件面归各业务服务，避免双写）。
  - `MedicalOrderService.stopAllForTransfer(Long visitId, String reason)`（Task 4 消费）与 `stop(String orderNo, String reason)`（Task 6 端点消费，两者共用实现：STOPPED 迁移 + 未来计划批量 CANCELLED + 发布 `inpatient.order.stopped`）。
  - `MedicalOrder` 字段冻结：order_no 唯一/visit_id/patient_id/order_type/order_class/standby_flag(仅 LONG 可 true)/group_no/freq_code/begin_at/end_at/开立医生与时间/stop_reason/status + 审计；`MedicalOrderItem`：order_id/item_seq/continue_flag/item_type/item_code/name_snapshot/dosage/dosage_unit/route/drip_rate/quantity/exec_dept_id/skin_test_flag/oral_flag(抢救补录标记)/计费回执标记两列 + 审计。
  - `order_frequency` 专业字典种子（**先实测** V607 `medication.frequency` 条目清单 `grep -A2 "medication.frequency" backend/fuyun-system/src/main/resources/db/migration/system/V607__seed_medication_route_frequency_dict.sql`）：qd(1 次/天,[08:00])/bid(2,[08:00,16:00])/tid(3,[08:00,12:00,16:00])/qid(4)/qn(1,[20:00])/prn(prn_flag=true)/st(临时即刻)——列：freq_code/freq_name/times_per_day/time_points(VARCHAR 逗号分隔)/week_pattern/prn_flag + dict_code 挂接 M01 `medication.frequency` code。

- [ ] **Step 1: V904 DDL**（三表 + 频次种子 INSERT WHERE NOT EXISTS + `uk_order_no`/`idx_visit_status`；time_points 列注释声明「HH:mm 逗号分隔序列」）

- [ ] **Step 2: 单测先行（TDD）**

`MedicalOrderServiceImplTest` 用例：①开立成功（长期用药医嘱：校验四层全过[全 mock]→CREATED→`OrderCreatedPayload` 含 items 明细→routing 子键 drug）②无处方权被拒（PracticeCheckPort passed=false → IP-1012）③过敏强阳性拦截（IP-1013）④剂量缺失拒绝（IP-1011）⑤频次不存在（IP-1021）⑥嘱托仅长期可用（stat+standby → IP-1022）⑦成组医嘱三要素（group_no/item_seq/continue_flag 落库）⑧转科批量停嘱（stopAllForTransfer：长期 STOPPED + 计划 CANCELLED + stopped 事件）。`OrderStateMachineServiceImplTest`：合法迁移八条全过 + 非法迁移两条拒（COMPLETED→EXECUTING、CREATED→TRANSFERRED）。

- [ ] **Step 3: 实现落码**（开立校验四层顺序照 GC20 冻结；`OrderCreateRequest` 校验注解 `@NotBlank`/`@Positive` + 服务层业务校验）

- [ ] **Step 4: 跑测试通过 + 模块验证**

- [ ] **Step 5: Commit**

```bash
git add backend/fuyun-inpatient/src
git commit -m "feat: FU-M04-04 医嘱开立域——主子表模型与状态机骨架及四层校验"
```

### Task 6: 医嘱审核与控制（FU-M04-05：order_audit/order_status_log + 系统自动审核 + 用药类药师审方回执驱动 + 停嘱/作废/撤销/重整 + audit-rejected 事件）

**Files:**
- Create: `V905__create_order_audit_log.sql`
- Create: `entity/OrderAudit.java`、`entity/OrderStatusLog.java`、`mapper/` 两 Mapper
- Create: `enums/AuditStage.java`（SYSTEM/PHARMACIST）
- Create: `dto/OrderStopRequest.java`、`dto/OrderCancelRequest.java`、`dto/OrderReorganizeRequest.java`
- Create: `internal/PharmacyAuditReplyListener.java`（消费 id 53/54 回执）
- Create: `service/OrderAuditService.java` + `impl/OrderAuditServiceImpl.java`
- Modify: `controller/OrderController.java`（增 stop/cancel/revoke-audit/reorganize 四端点）、`internal/InpatientMessagingConfig.java`（增两绑定）
- Test: `service/impl/OrderAuditServiceImplTest.java`、`internal/PharmacyAuditReplyListenerTest.java`

**Interfaces:**
- Consumes: `IdempotentConsumerSupport.consume`、`OrderStateMachineService.transition`、`InpatientEventPublisher`。
- Produces:
  - `OrderAuditServiceImpl.audit(String orderNo)`——开立后系统自动校验（全员必经）：**非用药类**（order_type != DRUG 且 != DISCHARGE_MED）校验通过即 `CREATED→AUDITED` + order_audit(SYSTEM) + 发布 `inpatient.order.audited`（子键路由）；**用药类**系统预检通过后停留 CREATED（语义=待药师审，order_audit 落 SYSTEM 行）等 M06 回执。
  - `PharmacyAuditReplyListener`——消费 `pharmacy.medication-order.audit-completed`（载荷 target=m04_order_no）→ `CREATED→AUDITED` + order_audit(PHARMACIST,通过) + 发布 `inpatient.order.audited.drug`；消费 `audit-rejected`（必附药师意见）→ `CREATED→AUDIT_REJECTED` + order_audit(PHARMACIST,驳回,理由) + 发布 `inpatient.order.audit-rejected`；幂等三段式（重复回执仅首次生效——状态 CAS 兜底）。
  - 医嘱重提：`AUDIT_REJECTED→CREATED`（医生修改后重新提交，order_status_log 留痕）——归 `OrderController` 的 `POST /orders/{no}/resubmit`（载荷=修改后医嘱体，实现=作废重开路径的轻量变体：直接更新项内容+状态回 CREATED+留痕；**修改=作废重开红线不破**——resubmit 仅限 AUDIT_REJECTED 态，CREATED 态的 AUDITED/AUDIT_REJECTED 前内容不改）。
  - REST 增四端点：`POST /orders/{no}/stop`（医生停嘱：AUDITED/TRANSFERRED/EXECUTING→STOPPED + stopped 事件）、`POST /orders/{no}/cancel`（作废：仅未产生执行——TRANSFERRED 作废联动发布 cancelled，执行单撤销归 M05 PR-3）、`POST /orders/{no}/revoke-audit`（撤回：AUDITED→CREATED + revoked 事件，权限控制 TODO 注记 P3）、`POST /orders/reorganize`（重整：只重排视图序+留痕，不改状态）。
  - 抢救口头医嘱：`OrderCreateRequest.oralFlag=true` 标记 + `POST /orders/{no}/oral-confirm`（补录确认，oral_confirmed_at 落库；限时催办 P3 注记）。

- [ ] **Step 1: V905 DDL**（order_audit：order_id/stage/审核方引用[review_task_no 可空]/结论/理由/occurred_at；order_status_log：order_id/from_status/to_status/reason/operator/occurred_at 只增）

- [ ] **Step 2: 单测先行（TDD）**

用例：①非用药类自动过审（lab 医嘱→AUDITED→`audited.lab` 子键）②用药类停留待审（DRUG→CREATED 停留+SYSTEM 审计行）③回执通过迁移（completed→AUDITED+audited.drug）④回执驳回迁移（rejected→AUDIT_REJECTED+audit-rejected 事件+理由留痕）⑤重复回执幂等（二次 completed 无副作用）⑥停嘱三态合法（AUDITED/TRANSFERRED/EXECUTING→STOPPED 各一）⑦作废拦截已执行（EXECUTING 作废抛 IP-1010）⑧撤回仅转抄前（TRANSFERRED 撤回抛 IP-1010）⑨重整不改状态（重排后状态不变+log 留痕）。

- [ ] **Step 3: 实现落码**（监听器照 `BillingPharmacyOccupyListener` 形态复刻——`@RabbitListener(queues = ...)` + consume 三段式）

- [ ] **Step 4: 跑测试通过 + 模块验证**

- [ ] **Step 5: Commit**

```bash
git add backend/fuyun-inpatient/src
git commit -m "feat: FU-M04-05 医嘱审核与控制——回执驱动迁移与停嘱作废撤销收口"
```

### Task 7: 转抄与执行计划域（FU-M04-06 上：order_transfer_log/order_execute_plan + 转抄工作台双人核对 + 临时单次计划 + 嘱托触发 + order.transferred 事件）

**Files:**
- Create: `V906__create_transfer_plan.sql`
- Create: `entity/OrderTransferLog.java`、`entity/OrderExecutePlan.java`、`mapper/` 两 Mapper
- Create: `enums/PlanStatus.java`（PENDING/EXECUTED/CANCELLED）、`enums/CheckConclusion.java`
- Create: `dto/TransferCheckRequest.java`（批量：orderNos[] + 转抄护士 + 核对结论 + 第二核对人[高危/输血强制]）、`dto/StandbyTriggerRequest.java`
- Create: `vo/TransferWorklistVO.java`、`vo/OrderPlanVO.java`
- Create: `service/OrderTransferService.java` + `impl/OrderTransferServiceImpl.java`
- Create: `controller/TransferController.java` + `OrderPlanController.java`
- Test: `service/impl/OrderTransferServiceImplTest.java`

**Interfaces:**
- Consumes: `OrderStateMachineService`、`InpatientEventPublisher`、`MedicalOrderService`（查询面）。
- Produces:
  - REST：`GET /transfer-worklist?wardId=&shift=`（待转抄列表：AUDITED 医嘱按病区/班次）、`POST /orders/transfer-check`（批量转抄核对：逐条 AUDITED→TRANSFERRED + order_transfer_log（转抄护士/时间/结论/第二核对人）+ 发布 `inpatient.order.transferred`[载荷 m04OrderNo/visitId/patientId/transferType/firstTransferredAt——V800 id 42 desc 逐字] + **临时医嘱同步生成单次执行计划**[plan_time=now+默认窗口，plan_no=PL 流水]）、`GET /order-plans?date=&wardId=&page=`、`POST /order-plans/standby-trigger`（嘱托按需触发单次计划，多次触发多次台账不重复计价[M13 唯一键兜底]）。
  - 双人核对强制：`TransferCheckRequest` 服务层校验——order 含高危药（item 高危标记或输血类 BLOOD）而 secondCheckerId 空 → IP-1016。
  - `OrderExecutePlan` 字段冻结：plan_no 唯一/order_id/order_item_id/visit_id/ward_id/plan_time/shift/执行回签引用（executor_id/executed_at/route_check_result）/status + 审计；唯一约束 `uk_plan_order_item_time`（order_item_id + plan_time，日切分解幂等兜底）。

- [ ] **Step 1: V906 DDL**（两表 + 唯一约束 + 班次词表注释[白班/小夜/大夜]）

- [ ] **Step 2: 单测先行（TDD）**

用例：①批量转抄（两条医嘱→TRANSFERRED+两事件+临时单次计划生成）②高危药缺第二核对人拦截（IP-1016）③嘱托触发（两次触发两计划，plan_no 各异）④转抄锁定（已 TRANSFERRED 重复转抄幂等跳过）⑤worklist 聚合（按病区过滤）。

- [ ] **Step 3: 实现落码**

- [ ] **Step 4: 跑测试通过 + 模块验证**

- [ ] **Step 5: Commit**

```bash
git add backend/fuyun-inpatient/src
git commit -m "feat: FU-M04-06 转抄与执行计划域——双人核对与临时单次计划生成"
```

### Task 8: 日切分解与执行回签（FU-M04-06 下：长期医嘱日切+当日增量补偿 + order-plan.generated + **CF-6 execute-confirm 实装** + order.executed + 闭环追溯）

**Files:**
- Create: `internal/OrderPlanDecomposeJob.java`（日切定时任务）
- Create: `dto/ExecuteConfirmRequest.java`（executorId 必填/executedAt 可空/routeCheckResult 可空——GC6 契约逐字）
- Create: `vo/ExecuteConfirmVO.java`（planNo/m04OrderNo/orderStatus/planStatus）、`vo/OrderTraceVO.java`
- Create: `service/OrderPlanService.java` + `impl/OrderPlanServiceImpl.java`（回签+日切+补偿+追溯）
- Modify: `controller/OrderPlanController.java`（增 execute-confirm 与 trace）
- Test: `service/impl/OrderPlanServiceImplTest.java`

**Interfaces:**
- Consumes: `OrderFrequency` 解析（time_points 拆分）、`OrderStateMachineService`、`InpatientEventPublisher`、`InpatientSeqGate.nextNo("PL")`。
- Produces:
  - `OrderPlanServiceImpl.decomposeNextDay(LocalDate date)`——日切批量分解：全院在院（ADMITTED）+TRANSFERRED/EXECUTING 长期医嘱 × 频次时点序列 → 次日执行计划行（批量 INSERT，`uk_plan_order_item_time` 幂等）；分解失败（字典缺失等）**不建异常清单表**——以服务日志 warn（含 order_no 与缺失原因，供次日夜内缓冲人工处理）+ 该医嘱计划行不生成表达，重跑由唯一约束幂等兜底（简化决策，偏差登记 Task 16）；发布 `inpatient.order-plan.generated`（载荷 m04OrderNo/visitId/patientId/planDate/planNos[]/planTimes[]——V800 id 43 desc 逐字；按医嘱逐条发布）。
  - `OrderPlanServiceImpl.compensateToday(OrderNo)`——当日增量补偿：新开长期医嘱审核+转抄后即时补生成当日剩余时点计划（now 之后时点）。
  - **`POST /order-plans/{no}/execute-confirm`（CF-6 实装核心，W-33 契约逐字履行）**：计划 PENDING→EXECUTED（CAS：`UPDATE order_execute_plan SET status='EXECUTED', executor_id=?, executed_at=now() WHERE plan_no=? AND status='PENDING'`，0 行=已 EXECUTED 幂等返回当前状态）；医嘱头推进三态（长期首个回签 TRANSFERRED→EXECUTING / 临时单次 TRANSFERRED→COMPLETED / 长期全部计划终态 EXECUTING→COMPLETED）；发布 `inpatient.order.executed`（载荷 m04OrderNo/visitId/patientId/planNo/executedAt——V800 id 47 desc 逐字）；响应 `ExecuteConfirmVO`。
  - `GET /orders/{no}/trace`——闭环追溯视图：开立→审核[含药师]→转抄→各计划执行→停止全环节人/时/果一屏（聚合 order_status_log + order_audit + order_transfer_log + plan 行）。
  - `OrderPlanDecomposeJob`——`@Scheduled(cron = "0 0 2 * * ?")`（02:00 夜间低峰，SchedulingConfig 已启用的先例形态——**先实测** `backend/fuyun-app/.../SchedulingConfig.java` @EnableScheduling 与既有 job 注册形态）；分批提交（每 500 医嘱一事务）可断点续跑。

- [ ] **Step 1: 单测先行（TDD）**

用例：①日切分解数量与频次一致（bid 长期医嘱→次日 2 行计划、时点 08:00/16:00）②重复日切幂等（唯一约束兜底零新行）③当日补偿（16:30 转抄 bid 医嘱→仅 08:00 次日+当日无剩余[16:00 已过]→当日 0 行次日 2 行）④长期首个回签（TRANSFERRED→EXECUTING+executed 事件）⑤临时单次回签（TRANSFERRED→COMPLETED）⑥全部终态推进（EXECUTING→COMPLETED）⑦重复回签幂等（二次 execute-confirm 返回当前状态零事件）⑧停嘱作废未来计划（STOPPED→PENDING 计划全 CANCELLED）⑨trace 聚合（五环节齐全）。

- [ ] **Step 2: 实现落码**（定时任务独立类 + `@Transactional` 分批；execute-confirm 严格照 GC6 契约字段）

- [ ] **Step 3: 跑测试通过 + 模块验证 + 集成冒烟**

`JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-app -am verify -Dit.test=InpatientOrderFlowIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`（IT 归 Task 16，此处若未建先跳过本步）。

- [ ] **Step 4: Commit**

```bash
git add backend/fuyun-inpatient/src
git commit -m "feat: CF-6 实装——执行回签三态推进与长期医嘱日切分解"
```

### Task 9: 出院管理域（FU-M04-07：discharge_request/follow_up_plan + 在途清理 + 费用预审 + 挂账审批放行 + 结算放行双条件 + 出院带药 + 随访 + billing 三事件消费）

**Files:**
- Create: `V907__create_discharge_followup.sql`
- Create: `entity/DischargeRequest.java`、`entity/FollowUpPlan.java`、`mapper/` 两 Mapper
- Create: `enums/DischargeRequestStatus.java`（REQUESTED/READY/BLOCKED/COMPLETED/CANCELLED）、`enums/FollowUpStatus.java`（PENDING/DONE/CANCELLED）、`enums/DischargeWay.java`（病案首页代码：1 医嘱离院/2 转院/3 转社区卫生机构…按 Spec §4 词表）
- Create: `dto/DischargeRequestCreate.java`、`dto/DischargeConfirmRequest.java`
- Create: `vo/DischargeRequestVO.java`、`vo/ClearanceVO.java`（在途清理与预审结果）
- Create: `service/DischargeService.java` + `impl/DischargeServiceImpl.java`
- Create: `internal/BillingEventListener.java`（消费 settlement.completed / arrears.approved / deposit.changed——deposit.changed 归 Task 10 本类承载）
- Create: `controller/DischargeController.java`
- Test: `service/impl/DischargeServiceImplTest.java`、`internal/BillingEventListenerTest.java`

**Interfaces:**
- Consumes: `BillingAccountQueryPort.precheck(String visitId)`（Task 13 落 billing 实现；本任务先落接口与 inpatient 侧消费——**接口位置以 Task 1 Step 3 实测结论为准**：默认 `com.fuyun.billing.api.BillingAccountQueryPort`）、`MedicalOrderService.stopAllForDischarge`（出院批量停嘱，复用 Task 5 stop 面）、`OrderStateMachineService`、`InpatientEventPublisher`、`InpatientSeqGate.nextNo("DC")`。
- Produces:
  - REST：`POST /visits/{visitId}/discharge-request`（出院申请：visit→DISCHARGE_REQUESTED + 在途清理编排[长期医嘱批量停嘱/临时逐条追踪清单入清理结果/未执行计划作废] + 预审[precheck：结清→READY / 未结清→BLOCKED 附欠费额] + 发布 `inpatient.visit.discharge-requested`）、`POST /discharge-requests/{no}/cancel`（取消：visit 回 ADMITTED + REQUESTED→CANCELLED；**取消后长期医嘱不复活**——需重新开立，Spec 边界）、`GET /discharge-requests/{no}/clearance`（清理与预审结果查询）、`POST /discharge-requests/{no}/confirm`（离院确认：**双条件**=status=READY 且 settlement_completed_flag=true → visit DISCHARGED + 床位 OCCUPIED→DISINFECTING + 随访计划生成[plan_date=出院后 N 日参数] + 出院带药放行[DISCHARGE_MED 类医嘱在此刻发布 `inpatient.order.audited.discharge-med` 子键事件] + 发布 `inpatient.visit.discharged`；不满足抛 IP-1017）。
  - `BillingEventListener`——消费 `billing.settlement.completed`（载荷 visitId：结算类型=出院结算分支 → discharge_request.settlement_completed_at 落标记）；消费 `billing.arrears.approved`（载荷 visitId/approvalNo → discharge_request BLOCKED→READY[记录 approval_no]）；幂等三段式。
  - `DischargeRequest` 字段冻结：request_no 唯一/visit_id/申请人/申请时间/预出院时间/离院方式/清理结果 JSON（长期停嘱数/临时追踪清单/计划作废数）/precheck 结果（状态+欠费额）/settlement_completed_at/approval_no/status + 审计。
  - `follow_up_plan`：visit_id/patient_id/plan_date/方式（电话/公众号/复诊）/内容摘要/status。

- [ ] **Step 1: V907 DDL**（两表 + discharge_request 的 `uk_visit_active` 部分唯一索引 `WHERE status IN ('REQUESTED','READY','BLOCKED') AND deleted = 0`——一 visit 至多一条在途申请）

- [ ] **Step 2: 单测先行（TDD）**

用例：①出院申请全编排（清理三动作+预审 BLOCKED[欠费]+discharge-requested 事件）②预审通过 READY ③挂账审批放行（arrears.approved 消费→BLOCKED→READY）④结算完成标记（settlement.completed 消费）⑤离院确认双条件（READY+已结算→DISCHARGED+床位消毒+带药放行[audited.discharge-med 子键]+discharged 事件+随访生成）⑥单条件拒绝（READY 未结算→IP-1017；BLOCKED→IP-1017）⑦取消回在院（visit→ADMITTED，医嘱不复活断言——状态仍 STOPPED）。

- [ ] **Step 3: 实现落码**（离院前置校验红线 GC19 全链：长期医嘱终态断言 + 在途计划清零断言 + 预审态断言三重校验后置 DISCHARGED）

- [ ] **Step 4: 跑测试通过 + 模块验证**

- [ ] **Step 5: Commit**

```bash
git add backend/fuyun-inpatient/src
git commit -m "feat: FU-M04-07 出院管理域——放行双条件与挂账审批事件驱动"
```

### Task 10: 住院计费入口（FU-M04-08：billing.deposit.changed 消费欠费标识 + 欠费清单查询 + 押金下限参数）

**Files:**
- Create: `properties/InpatientProperties.java`（押金下限阈值 fen/随访间隔日/默认执行窗口等）
- Modify: `backend/fuyun-app/src/main/resources/application.yml`（`fuyun.inpatient` 段：deposit-floor-fen 等，env 注入形态照 `fuyun.billing`）
- Modify: `internal/BillingEventListener.java`（deposit.changed 消费补全）
- Create: `vo/ArrearsAlarmVO.java`；Modify: `controller/`（增 `GET /visits/arrears?wardId=` 欠费清单——读 inpatient_visit.arrears_flag 本地标识聚合）
- Test: `internal/BillingEventListenerTest.java`（增用例）

**Interfaces:**
- Consumes: `InpatientProperties`。
- Produces:
  - `billing.deposit.changed` 消费（载荷 visitId/balance 变动后余额）：余额 < 押金下限阈值 → `inpatient_visit.arrears_flag=true`（CAS 更新）+ warn 日志（护士站欠费标识数据源）；回升 ≥ 阈值 → false。担保白名单免提醒（P3 注记，本 PR 阈值全局一份）。
  - `GET /visits/arrears?wardId=`——病区欠费清单（arrears_flag=true 的在院 visit 聚合：visit_id/患者摘要[脱敏]/床位/欠费标识时点）。
  - **一日清单/押金缴存/手工计费不建 inpatient 转调端点**（偏差登记 GC32：前端直调 M13 既有 REST——`GET /api/v1/billing/daily-lists`、`POST /api/v1/billing/deposits`、`POST /api/v1/billing/fees/manual`，门诊同款先例零新后端面）。
  - `InpatientProperties`——`depositFloorFen(long, 默认 0)`、`followUpIntervalDays(int, 默认 14)`、`defaultExecuteWindowMinutes(int, 默认 60)`。

- [ ] **Step 1: 单测先行（TDD）**——用例：①余额跌破阈值置欠费标识 ②回升复位 ③欠费清单聚合（按病区过滤）④deposit.changed 重复消费幂等。

- [ ] **Step 2: 实现落码**（Properties @ConfigurationProperties + yml 注入）

- [ ] **Step 3: 跑测试通过 + 模块验证 + Commit**

```bash
git add backend/fuyun-inpatient/src backend/fuyun-app/src/main/resources/application.yml
git commit -m "feat: FU-M04-08 住院计费入口——欠费标识事件驱动与清单查询"
```

### Task 11: 会诊管理域（FU-M04-09：consultation + 急会诊时限 + 读时惰性超时升级 + 五事件）

**Files:**
- Create: `V908__create_consultation.sql`
- Create: `entity/Consultation.java`、`mapper/ConsultationMapper.java`
- Create: `enums/ConsultationStatus.java`（REQUESTED/ACCEPTED/COMPLETED/CANCELLED）、`enums/ConsultationUrgency.java`（URGENT/NORMAL——时限 30min/24h）、`enums/ConsultationLevel.java`（DEPT/HOSPITAL/MDT 预留）
- Create: `dto/ConsultationCreateRequest.java`、`dto/ConsultationOpinionRequest.java`
- Create: `vo/ConsultationVO.java`
- Create: `service/ConsultationService.java` + `impl/ConsultationServiceImpl.java`
- Create: `controller/ConsultationController.java`
- Test: `service/impl/ConsultationServiceImplTest.java`

**Interfaces:**
- Consumes: `InpatientEventPublisher`、`InpatientSeqGate.nextNo("CS")`。
- Produces:
  - REST：`POST /consultations`（申请：URGENT→response_deadline=now+30min / NORMAL→+24h，发布 `inpatient.consultation.requested`）、`POST /consultations/{no}/accept`（目标科室接单：REQUESTED→ACCEPTED[**overdue 后仍可接单**——清除 overdue_flag] + accepted 事件）、`POST /consultations/{no}/opinion`（意见提交：ACCEPTED→COMPLETED + completed 事件；会诊意见归档供 M09 引用）、`POST /consultations/{no}/cancel`（REQUESTED/ACCEPTED→CANCELLED + cancelled 事件）、`GET /consultations?status=&deptId=`（列表，**读时惰性逾期**：REQUESTED 且 now>deadline 且 overdue_flag=false → 置 overdue_flag + 发布 `inpatient.consultation.overdue`（动作广播，状态停留 REQUESTED）+ 升级动作=日志 warn[重复通知目标科室+上报医务降级为 overdue 标记可见，通知中心 P3]）。
  - consult 类医嘱内部分发：`MedicalOrderServiceImpl` 开立 CONSULT 类时不外发 audited 子键（审核通过后转内部流程——consult 医嘱 audited 事件**仍发布**（子键 consult，M13 计价需要）但业务流转归会诊流程：audit 服务在 CONSULT 类 AUDITED 后自动创建 consultation 草稿[关联 order_no]）——落 `OrderAuditServiceImpl` 钩子。
  - `Consultation` 字段冻结：consult_no 唯一/visit_id/patient_id/order_ref(可空)/申请科室医生/目标科室/urgency/requested_at/response_deadline/response_time/consult_time/opinion/overdue_flag/status + 审计。

- [ ] **Step 1: V908 DDL**（含 response_deadline 索引[逾期扫描面]）

- [ ] **Step 2: 单测先行（TDD）**——用例：①急会诊时限（URGENT deadline=+30min）②普通 24h ③读时逾期升级（now 超 deadline→overdue_flag+overdue 事件一次，二次查询不重发）④overdue 后仍可响应（accept 清 flag→ACCEPTED）⑤意见完成闭环 ⑥consult 医嘱审核自动建会诊单（钩子断言）。

- [ ] **Step 3: 实现落码 + 跑测试通过 + 模块验证 + Commit**

```bash
git add backend/fuyun-inpatient/src
git commit -m "feat: FU-M04-09 会诊管理域——急会诊时限与动作式超时升级"
```

### Task 12: M06 住院审方薄切片（pharmacy 侧：V1000 order_medication/review_task + 消费 order.created.drug + 审方 API + 回执事件发布）

**Files:**
- Create: `backend/fuyun-pharmacy/src/main/resources/db/migration/pharmacy/V1000__create_medication_review.sql`
- Create: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/entity/OrderMedication.java`、`entity/ReviewTask.java`、`mapper/` 两 Mapper
- Create: `enums/ReviewTaskStatus.java`（PENDING/APPROVED/REJECTED）
- Create: `dto/ReviewDecisionRequest.java`（taskId 或 medicationOrderNo + 结论 + 药师意见[驳回必填]）
- Create: `vo/ReviewTaskVO.java`、`vo/MedicationOrderVO.java`
- Create: `internal/MedicationOrderReviewListener.java`（消费 `inpatient.order.created.drug`）
- Create: `service/MedicationReviewService.java` + `impl/MedicationReviewServiceImpl.java`
- Create: `controller/ReviewTaskController.java`
- Modify: `api/PharmacyErrorCode.java`（增 PH-1019/1020/1021）、`internal/PharmacyMessagingConfig.java`（增 drug 子键队列绑定与回执发布面——**先实测**该配置类现名与形态）
- Test: `service/impl/MedicationReviewServiceImplTest.java`、`internal/MedicationOrderReviewListenerTest.java`

**Interfaces:**
- Consumes: `IdempotentConsumerSupport.consume`（pharmacyConsumerSupport 既有 @Qualifier）、pharmacy 既有 `PharmacyEventPublisher`（**先实测**其发布器 Bean 名与形态，回执事件经它发布）。
- Produces:
  - `MedicationOrderReviewListener`——消费 `inpatient.order.created`（routing key `inpatient.order.created.drug` 精确绑定）：落 `order_medication`（引用 m04_order_no + 医嘱项药品明细快照）+ `review_task`（PENDING，关联 order_medication）；幂等（重复消费仅一任务——`uk_review_medication` 唯一约束兜底）。
  - REST：`GET /api/v1/pharmacy/review-tasks?status=&page=`（审方工作台列表：医嘱号/患者摘要/药品明细/申请科室）、`POST /review-tasks/{id}/approve`（APPROVED + 发布 `pharmacy.medication-order.audit-completed`[载荷 target=m04_order_no + reviewTaskNo + 药师意见]——V800 id 53 desc 对齐）、`POST /review-tasks/{id}/reject`（REJECTED[意见必填] + 发布 `pharmacy.medication-order.audit-rejected`[target=m04_order_no + rejectReason 必附]——id 54 desc 对齐）。
  - `order_medication` 字段：m04_order_no 唯一/visit_id/patient_id/freq_code/items 快照 JSON（药品/剂量/途径/数量）/创建时间；`review_task`：order_medication_id 唯一/申请科室/申请医生/created_at/decided_at/pharmacist_id/opinion/status。
  - **不落**：审方规则引擎（P3——全部任务人工审）、自动预检分级（P3）、双签（P3）。

- [ ] **Step 1: V1000 DDL**（pharmacy 目录，通用段——**迁移治理自检必须通过**：`python scripts/check-migration-governance.py`）

- [ ] **Step 2: 单测先行（TDD）**——用例：①order.created.drug 消费落两表 ②非 drug 类不落（监听器仅绑 drug 子键，断言绑定键）③通过回执（audit-completed 载荷 target 断言）④驳回必附意见（缺意见 PH-1020）⑤重复消费幂等 ⑥状态机（PENDING 才可决，APPROVED 再决拒 IP→PH-1020）。

- [ ] **Step 3: 实现落码 + 模块验证**

`JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-pharmacy -am verify -DskipITs`。

- [ ] **Step 4: Commit**

```bash
git add backend/fuyun-pharmacy/src
git commit -m "feat: M06 住院审方薄切片——drug 子键消费与回执事件回流 M04"
```

### Task 13: M13 住院计费联动（billing 侧：V1001–V1003 + BillingAccountQueryPort + 六事件消费 + arrears_approval 挂账审批 + arrears.approved 发布 + 床位费日切简版）

**Files:**
- Create: `backend/fuyun-billing/src/main/resources/db/migration/billing/V1001__create_fee_ownership_split.sql`
- Create: `V1002__create_arrears_approval_seed_event.sql`
- Create（条件）: `V1003__seed_inpatient_charge_item.sql`（**先实测**：`grep -rn "床位\|BED\|bed" backend/fuyun-billing/src/main/resources/db/migration/billing/*.sql`——charge_item 种子若无住院床位费/护理费项目则补种[项目编码/名称/单价 fen/收费类别]，有则免本迁移）
- Create: `backend/fuyun-billing/src/main/java/com/fuyun/billing/api/BillingAccountQueryPort.java` + `vo`（或 record）`DischargePrecheckView` + `service/impl/BillingAccountQueryPortImpl.java`（若 Task 1 Step 3 实测结论为反转注册则调整位置并登记偏差）
- Create: `entity/FeeOwnershipSplit.java`、`entity/ArrearsApproval.java`、`mapper/` 两 Mapper
- Create: `enums/ArrearsApprovalStatus.java`（DRAFT/PENDING_APPROVAL/APPROVED/REJECTED）
- Create: `internal/BillingInpatientEventListener.java`（消费 inpatient 六事件）
- Create: `internal/InpatientDailyChargeJob.java`（住院持续性费用日切简版）
- Create: `controller/ArrearsApprovalController.java` + `dto`/`vo`
- Modify: `api/BillingErrorCode.java`（增 BILL-1032/1033）、`internal/BillingMessagingConfig.java`（**先实测**现名）增 inpatient 事件通配绑定 `inpatient.visit.#` + `inpatient.order.#` + arrears.approved 发布登记（id 73 已随 V1002 种子）
- Test: `internal/BillingInpatientEventListenerTest.java`、`service/impl/ArrearsApprovalServiceImplTest.java`

**Interfaces:**
- Consumes: `IdempotentConsumerSupport.consume`（billingConsumerSupport）、billing 既有计价引擎（`PricingEngineServiceImpl`——**先实测**其对外 service 接口签名后复用生成费用行）、`BillingEventPublisher`。
- Produces:
  - `BillingInpatientEventListener` 六事件消费（幂等三段式；费用行生成经既有计价通道[price draft→PENDING 行]，VisitType.IN 分支已在）：
    - `inpatient.visit.admitted` → 持续性费用起费锚点（落 visit 起费标记 fee_ownership_split 起点行 or 内存面——**落库**：fee_ownership_split 记录 ward 归属起点）+ 当日床位费 PENDING 行（charge_item=BED 种子价）；
    - `inpatient.order.audited`（通配）→ 住院离散计价：按医嘱项生成 PENDING 费用行（计费行摘要=事件载荷 items；计价唯一键兜底防重复[04 Spec §6 计费唯一性]）；
    - `inpatient.order.executed` → 费用确认（对应 PENDING→CONFIRMED——`fee.confirmed` 常量已有无调用点，此处消费侧直改状态不发事件）；
    - `inpatient.order.stopped` → 停费截断（该医嘱未确认 PENDING 行作废标记）；
    - `inpatient.visit.transferred` → 归属切分（fee_ownership_split 落行：visit_id/from_ward/to_ward/split_at——日切按时间线切分归 P3，本 PR 落切分点记录）；
    - `inpatient.visit.discharge-requested` → 停止持续性计费标记（出院后日切不再生成床位费）。
  - `InpatientDailyChargeJob`——`@Scheduled(cron = "0 30 2 * * ?")`（02:30，在 inpatient 分解[02:00]之后）：全院在院 visit 生成当日床位费 PENDING 行（幂等：visit×日期唯一）；出院标记后跳过。
  - `ArrearsApprovalController`——`POST /api/v1/billing/arrears-approvals`（DRAFT→PENDING_APPROVAL，关联 visitId）、`POST /arrears-approvals/{no}/approve`（APPROVED + 发布 `billing.arrears.approved`[载荷 visitId/approvalNo/approvedAt/approvedBalance]——id 73 desc 对齐）、`POST /arrears-approvals/{no}/reject`（REJECTED）；`@AuditLog(WRITE)` + 资金审批权限注记（W-37 全量 403 归 PR-4，本 PR 端点在位）。
  - `BillingAccountQueryPort.precheck(visitId)`——聚合：未结清费用合计[PENDING+CONFIRMED 未结算]/押金余额/是否结清布尔。
  - V1002 内 `INSERT ... id 73 billing.arrears.approved ... WHERE NOT EXISTS`（desc 照前置项 13 行文）。

- [ ] **Step 1: V1001–V1003 DDL**（fee_ownership_split：visit_id/from_ward_id/to_ward_id/split_at + `idx_visit`；arrears_approval：approval_no 唯一/visit_id/申请理由/审批人/决定时间/status——Spec M-10 四态词表注释；迁移治理自检）

- [ ] **Step 2: 单测先行（TDD）**——用例：①admitted 起费+当日床位费行 ②audited 离散计价（两项两行）③重复 audited 幂等（计价唯一键）④executed 确认 ⑤stopped 截断作废 ⑥transferred 切分落行 ⑦discharge-requested 停持续计费（日切 job 跳过出院 visit）⑧挂账审批通过（approve→arrears.approved 事件载荷四字段）⑨驳回保持 ⑩precheck 聚合三值。

- [ ] **Step 3: 实现落码 + 模块验证**

`JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-billing -am verify -DskipITs`。

- [ ] **Step 4: Commit**

```bash
git add backend/fuyun-billing/src
git commit -m "feat: M13 住院计费联动——六事件消费计价停费与挂账审批放行事件"
```

### Task 14: 前端 API 层与入院登记台/床位图（api/inpatient.ts + gen:api 重生成 + 路由菜单「住院管理」分组 + AdmissionView + BedMapView）

**Files:**
- Create: `web/apps/workstation/src/api/inpatient.ts`
- Modify: `web/packages/shared/src/api.d.ts`（`cd web && pnpm gen:api` 重生成——后端先起或用 api-docs.json 刷新流程照 PR-5/PR-6 先例：**先实测** web/api-docs.json 更新方式[后端容器内 curl /v3/api-docs 落盘]）
- Modify: `web/apps/workstation/src/router/index.ts`（增 6 条 children 路由：`/inpatient/admission|beds|station|transfer|discharge` + `/pharmacy/review`，meta.permission 六枚：`inpatient:admission:manage`/`inpatient:bed:view`/`inpatient:station:view`/`inpatient:transfer:check`/`inpatient:discharge:manage`/`pharmacy:review:audit`）
- Modify: `web/apps/workstation/src/views/layout/components/AppSidebar.vue`（MENU_ITEMS 增「住院管理」分组五项 + 药房管理分组增审方台）
- Create: `web/apps/workstation/src/views/inpatient/AdmissionView.vue` + `.spec.ts`
- Create: `web/apps/workstation/src/views/inpatient/BedMapView.vue` + `.spec.ts`

**Interfaces:**
- Consumes: shared `components['schemas']` 类型（AdmissionVO/InpatientVisitVO/BedMapVO/PageResult）、`http.ts` 出网层。
- Produces:
  - `api/inpatient.ts`——资源分组对象导出：`admissions`（create/list/schedule/cancel/register）、`visits`（admitWard/arrears）、`beds`（map/reserve/assign/release/disinfectDone/maintain/maintainDone）、`transfer`（execute/changeBed）；类型别名逐条 `export type AdmissionVO = components['schemas']['AdmissionVO']` 等（照 nursing.ts 范式）。
  - `AdmissionView.vue`——两栏：左=候床队列（状态筛选 tab[WAITING/SCHEDULED]/排序标识/预约与登记操作），右=登记确认表单（患者搜索/医保类型/诊断摘要[脱敏提示]/入科床位选择）；动作按钮 loading + 在途守卫 + 4xx ElMessage 提示（ProblemDetail.detail）。
  - `BedMapView.vue`——病区选择 + 床位卡片墙（五态色标：空床绿/预占橙/占床蓝/消毒灰/维修红——`--fuy-color-*` 既有语义层映射，禁自创色值）+ 床位操作下拉（预占/分配/消毒完成/维修/恢复）+ 转科转床弹窗（四阶段提示文案）。
  - 两页 `.spec.ts`——照 WardBoardView.spec.ts 范式：`vi.mock('@/api/inpatient')` 整模块替身 + seedAuthSession + 中文场景 it + 断言业务结果（禁绑定实现细节）。

- [ ] **Step 0: 技能加载（硬阻塞，GC34）**——dispatch prompt 要求实现者先加载 `ui-ux-pro-max:ui-ux-pro-max`/`design`/`design-system`/`ui-styling` 四件，未加载不得开工。

- [ ] **Step 1: api.d.ts 重生成 + api/inpatient.ts 落码**——`pnpm gen:api` 后 `git diff --exit-code packages/shared/src/api.d.ts` 核对新增面（仅新增 schemas/paths，无意外漂移）。

- [ ] **Step 2: 路由与菜单落码**——六路由懒加载 + MENU_ITEMS 数据驱动登记（与 router 清单一一对应注释同步）。

- [ ] **Step 3: AdmissionView TDD**——spec 先行（用例：①队列加载渲染 ②预约动作出网 ③登记确认成功提示+队列刷新 ④FROZEN 患者错误提示[ProblemDetail detail 透传]），跑失败→实现→通过。

- [ ] **Step 4: BedMapView TDD**——用例：①床位图五态渲染 ②占用床位显示患者摘要 ③消毒完成操作 ④转科弹窗提交，同上循环。

- [ ] **Step 5: 前端门禁五连**

`cd web && pnpm lint && pnpm format:check && pnpm type-check && pnpm test && pnpm build`。

- [ ] **Step 6: Commit**

```bash
git add web/packages/shared/src/api.d.ts web/apps/workstation/src
git commit -m "feat: 住院管理前端——入院登记台与病区床位图（住院分组路由）"
```

### Task 15: 前端住院医生站/转抄工作台/出院管理/药房审方台（DoctorStationView[住院] + TransferWorklistView + DischargeManageView + ReviewTaskView）

**Files:**
- Create: `web/apps/workstation/src/views/inpatient/DoctorStationView.vue` + `.spec.ts`（在院列表/医嘱开立/闭环追溯三区）
- Create: `web/apps/workstation/src/views/inpatient/TransferWorklistView.vue` + `.spec.ts`
- Create: `web/apps/workstation/src/views/inpatient/DischargeManageView.vue` + `.spec.ts`
- Create: `web/apps/workstation/src/views/pharmacy/ReviewTaskView.vue` + `.spec.ts`

**Interfaces:**
- Consumes: `api/inpatient.ts` 全资源组（Task 14 冻结面）+ `api/pharmacy.ts` 增 `reviewTasks` 资源组（list/approve/reject——本任务落 api/pharmacy.ts 增量）。
- Produces:
  - `DoctorStationView.vue`（住院版）——三区：左=在院患者列表（本医生管床+病区维度切换/护理级别/欠费标识/过敏标识）；中=医嘱开立（类型九选一[下拉词表]/长期临时切换/成组行编辑[group_no 提示]/嘱托开关[仅长期]/频次选择[order_frequency 字典接口]/保存=开立→状态 CREATED→用药类提示「待药师审」）；右=闭环追溯面板（单医嘱开立→审核→转抄→执行→停止时间线）。开立前校验失败（IP-1012/1013/1011）逐类中文提示。
  - `TransferWorklistView.vue`——待转抄列表（病区过滤/高危药红色标识）+ 批量核对操作（勾选→转抄人/第二核对人[高危强制红*]/提交批量）；结果反馈逐条成功/失败。
  - `DischargeManageView.vue`——出院申请列表（REQUESTED/READY/BLOCKED/COMPLETED tab）+ 申请发起弹窗（预出院时间/离院方式词表）+ 清理与预审结果面板（ClearanceVO 渲染：清理三清单+欠费额）+ 挂账审批引导（BLOCKED 提示走 billing 审批页[P1 既有收费面]）+ 离院确认（双条件不满禁用按钮+原因提示）。
  - `ReviewTaskView.vue`——待审任务列表（医嘱号/患者摘要/药品明细表/申请科室）+ 通过/驳回操作（驳回弹窗意见必填）；操作后列表刷新。
  - 四页 spec.ts 各 4–6 用例（照 Task 14 范式；住院医生站用例：①在院列表加载 ②开立用药医嘱出网+待审提示 ③开立检验医嘱（非用药）出网 ④追溯面板渲染 ⑤校验失败提示；转抄台：①列表加载高危标识 ②缺第二核对人 4xx 提示 ③批量提交；出院页：①四态 tab ②申请出网 ③BLOCKED 面板欠费额渲染 ④双条件禁用确认；审方台：①列表 ②驳回缺意见提示 ③通过出网）。

- [ ] **Step 0: 技能加载（硬阻塞，GC34）**——同 Task 14。

- [ ] **Step 1: api/pharmacy.ts 增 reviewTasks 资源组**（三方法 + 类型别名）。

- [ ] **Step 2–5: 四页逐页 TDD 循环**（每页：spec 先行跑失败→实现→通过→下一页；住院医生站最重[参照 outpatient/DoctorStationView 三栏骨架]，转抄/出院/审方依次）。

- [ ] **Step 6: 前端门禁五连 + Commit**

```bash
git add web/apps/workstation/src
git commit -m "feat: 住院管理前端——医生站转抄工作台出院管理与药房审方台"
```

### Task 16: 验收锚点 IT 与收口（五条 IT + W-34 触发核验 + Spec 落地注记 + TASK.md 三项收口 + CHANGELOG + 真栈探针 + 质量门）

**Files:**
- Create: `backend/fuyun-app/src/test/java/com/fuyun/app/InpatientAdmissionFlowIT.java`
- Create: `backend/fuyun-app/src/test/java/com/fuyun/app/InpatientOrderFlowIT.java`
- Create: `backend/fuyun-app/src/test/java/com/fuyun/app/InpatientTransferDischargeIT.java`
- Create: `backend/fuyun-app/src/test/java/com/fuyun/app/InpatientDailyDecomposeIT.java`
- Create: `backend/fuyun-app/src/test/java/com/fuyun/app/BillingInpatientLinkageIT.java`
- Modify: `docs/specs/modules/04-inpatient.md`（P2 PR-1 落地注记）
- Modify: `TASK.md`（W-33 删除/W-34 更新/W-42~W-47 登记）
- Modify: `CHANGELOG.md`（收口条目）

**Interfaces:**
- Consumes: `FuyunStackITBase`（admin 登录/postJson/seedReviewerUser）、三容器类级独占声明（GC9）、RabbitMQ 手工捕获队列形态（照 `MessagingGovernanceIT` 既有捕获断言形态——**先实测**其队列捕获助手）。
- Produces（五条验收锚点 IT，全部真栈 Testcontainers）:
  - `InpatientAdmissionFlowIT`——①住院证登记→候床→预约（床位 RESERVED）→登记确认（visit_id I 型 14 位断言 + `VisitIdValidator.isValid`）→入科（床位 OCCUPIED + bed_assign 未闭合行）②**W-34 触发核验**：`inpatient.visit.registered`/`admitted`/`bed.changed` 三事件经 fy.topic 真实投递（手工捕获队列收信断言信封 event_type 与载荷字段）③EMPI 合并拦截（OngoingVisitQuery 双实现注册后在院阻断——调 M02 合并 API 断言被拒）。
  - `InpatientOrderFlowIT`——**医嘱闭环全链（CF-6 验收核心）**：开立用药医嘱（CREATED→order.created.drug 投递断言）→pharmacy 消费落 review_task（轮询等待）→药师通过（POST review-tasks/{id}/approve）→回执消费→AUDITED（`inpatient.order.audited.drug` 投递断言）→转抄双人（TRANSFERRED + 临时单次计划）→执行回签（execute-confirm：计划 EXECUTED + 医嘱 COMPLETED + `inpatient.order.executed` 投递断言 + **响应四字段与 V901 id 55 契约一致**）→停嘱路径（第二条医嘱 stopped→PENDING 计划全 CANCELLED）；另：非用药医嘱（lab）自动过审 + `audited.lab` 子键路由断言 + M13 通配消费（billing 捕获队列收 `inpatient.order.#`）。
  - `InpatientTransferDischargeIT`——①转科五步（目标床位预占→编排执行：转出医嘱 STOPPED[stopped 事件]/临时计划保留[断言行仍 PENDING 且 ward 更新]/长期计划作废/床位 DISINFECTING→OCCUPIED 流转/`inpatient.visit.transferred` 载荷六字段断言）②出院全链（申请→清理[长期医嘱全终态断言/计划清零断言]→预审 BLOCKED[欠费额>0]→押金不足→挂账审批（billing approve）→`billing.arrears.approved` 投递→READY→**真实结算**[IT 内直调 billing 结算 API 产生 `billing.settlement.completed` 真事件]→消费→离院确认（DISCHARGED + 床位 DISINFECTING + `inpatient.visit.discharged` 投递 + 带药放行[audited.discharge-med] + 随访行生成）③双条件拒绝（未结算确认→403/409 ProblemDetail errorCode=IP-1017）。
  - `InpatientDailyDecomposeIT`——①日切分解（预置两条长期医嘱 bid/qd→调 decomposeNextDay→行数与时点断言[2+1 行，08:00/16:00/08:00]）②重复执行幂等（零新行）③当日增量补偿（当日新转抄→剩余时点补齐）④停嘱联动（STOPPED→次日计划 CANCELLED）⑤`inpatient.order-plan.generated` 投递断言（planNos 数组与落库一致）。
  - `BillingInpatientLinkageIT`——①admitted→床位费 PENDING 行 + 切分起点行 ②audited→离散费用行（两项两行金额=种子价×数量）③executed→CONFIRMED ④stopped→PENDING 作废 ⑤transferred→切分落行（from/to ward）⑥重复投递幂等（同 eventId 重发[关闭幂等或直投]仅一费用行——**用 received_event 幂等语义验证**）⑦precheck 聚合（欠费额=ΣPENDING+CONFIRMED-押金）。

- [ ] **Step 1: 五条 IT 逐条落码跑绿**（每条独立容器类；先跑单条 `-Dit.test=InpatientOrderFlowIT` 再全量）。

- [ ] **Step 2: 全量门禁**

`JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml verify`（全模块含 IT）+ `cd web && pnpm lint && pnpm format:check && pnpm type-check && pnpm test && pnpm build && pnpm audit --audit-level high`。

- [ ] **Step 3: Spec 落地注记（GC32 全项）**

`docs/specs/modules/04-inpatient.md` 追加「P2 PR-1 落地注记」节：五项降级清单[GC21]/医嘱模板顺延/WS 主题顺延/计费入口前端直调形态偏差/SCHEDULED 逾期手工重排/全院一张床参数化 P3/口头医嘱限时催办 P3/CF-6 id 55 定稿落点[V901]/事件 id 65–73 排定/pharmacy V1000 与 billing V1001+ 通用段形态。

- [ ] **Step 4: TASK.md 三项收口（GC33）**

W-33 行删除（V901 已履行+IT 断言）；W-34 行更新（追加「2026-09-XX 触发已就位[PR-1 事件链上线+InpatientAdmissionFlowIT 通过]，退役五项执行归 PR-3」）；W-42~W-47 六行新登记（内容照 P2 计划 §2 裁决 2 原文逐字）。

- [ ] **Step 5: 真栈探针四项（GC28）**

起栈→镜像重建→四探针（迁移计数/事件行数 73/契约面 curl/compose healthy）→浏览器真机六页走查（入院→床位→医生站开嘱→审方→转抄→出院全链操作留痕截图不入库[.playwright-cli/ 已 ignore]）。

- [ ] **Step 6: CHANGELOG 收口条目 + 质量门复审 + Commit**

```bash
git add backend/fuyun-app/src/test docs/specs/modules/04-inpatient.md TASK.md CHANGELOG.md
git commit -m "test: M04 住院域五条验收锚点 IT 与 P2 PR-1 收口注记"
```

推送分支→开 PR（描述含：基线号/事件 id 排定/V900 段登记/偏差清单[计费入口直调、日切异常清单简化、UI 设计文档不新出]/W-42~W-47 登记出处）→ CI checks 全绿 → `/code-review` 审核通过 → 合并停留 dev。

---

## 自审记录（writing-plans Self-Review）

- [x] **Spec 覆盖对照**：FU-M04-01 → Task 3；FU-M04-02 → Task 4；FU-M04-03（住院医生站）→ Task 5 开立入口 + Task 15 前端医生站（临床周边面[M09 嵌入/ICD/M07/M08 结果查看]P4 顺延，not-in-scope 显式排除 + Task 16 注记）；FU-M04-04 → Task 5（模板顺延 not-in-scope）；FU-M04-05 → Task 6；FU-M04-06 → Task 7/8；FU-M04-07 → Task 9；FU-M04-08 → Task 10 + Task 13（billing 侧）；FU-M04-09 → Task 11；M06 薄切片 → Task 12；M13 契约 → Task 9[消费]/Task 13[发布+计价]；W-33 → Task 2 Step 1；W-34 触发 → Task 16 IT①②；W-42~W-47 → Task 16 Step 4；CF-6 实装 → Task 5–8 + Task 16 IT②。
- [x] **占位符扫描**：全文无 TBD/待定；「先实测」标注为执行期实测条款（非占位——命令已给）；「条件迁移 V1003」「InpatientConfig 空壳后启用」为显式条件交付非推迟。
- [x] **编号一致性**：事件 id 41–73 全链一致（V800 既有 41–64 不动、V901 新增 65–72、V1002 新增 73、MessagingGovernanceIT 73）；迁移号 V901–V908/V1000–V1003 与文件结构表逐一对应；错误码 IP-1001–1023 无跳号（IP-1019 后直接 IP-1020…末号 IP-1023）；PH-1019–1021/BILL-1032–1033 续号声明与实测锚在位；任务号 Task 1–16 与前置项映射表落点一致。
- [x] **类型一致性**：`InpatientSeqGate.nextNo/nextVisitId`（Task 1 定义 ↔ Task 3/5/7/9/11 消费签名一致）；`OrderStateMachineService.transition`（Task 5 定义 ↔ Task 6/7/8/9 消费）；`MedicalOrderService.stopAllForTransfer`（Task 5 定义 ↔ Task 4/9 消费）；`BillingAccountQueryPort.precheck`（Task 9 消费 ↔ Task 13 实现）；`withTypeKey`（Task 2 定义 ↔ Task 5/6 消费）；载荷 record 组件名与 V800/V901 desc 字段逐字（Task 2 冻结 ↔ Task 3–11 发布 ↔ Task 16 IT 断言）。
