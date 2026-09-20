# P1·PR-5 M03 门诊主流程（CF-3/CF-4/CF-5 回切真实 + 三前端）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 把 M03 门诊域从「零实现」推进到门诊主链路可用——号源池域（排班模板×排班日历×号源池行汇总、T+N 放号生成、停诊整池作废、加号额度、池行乐观锁+余量谓词条件更新与 Redis 原子预扣双道闸）+ 预约挂号/当日挂号（多渠道统一入口、`visit_id=O+yyyyMMdd+5 位流水` 本模块唯一签发、支付时限 `fy.delay` 延迟释放与爽约信用限约）+ 退号退费联动与改期（统一走 M13 免审档退费、`billing.refund.approved` 回执驱动终态、reschedule_of 改期链）+ 分诊台与候诊叫号（排队表+Redis ZSET 优先级队列+outpatient 自建 STOMP configurer 与 REST 快照双通道）+ 门诊医生站（候诊列表/接诊/诊毕/clinic_order 开单发布 `outpatient.order.created`/调 M06 开方 API 登记处方引用）+ 收费编排（消费 `billing.settlement.completed` 单据放行并扇出 `outpatient.order.charged`、消费 `billing.refund.approved` 完成退号与开单退费逆向并扇出 `outpatient.order.cancelled`）+ 执业授权 `practice/check` 真实化（V704 建 practice_grant+管理端点+最小种子+真实校验，M03 开单/开方强校验与 M06 开方纵深防御双端接线）+ D-16 unmask 三态门禁接入（patient 侧 CareRelationQuery SPI+M03 注册实现）+ PR-4 三占位事件回切（id 23/25/31 载荷冻结 UPDATE、pharmacy 放行链/退费收口/凭证核验/作废路径四点改造）；前端 workstation 三页（挂号收费联动/分诊台/门诊医生站）+ portal 免登录预约页 + bigscreen 候诊叫号页。

**架构：** 后端照抄 backend 宪法 B.1 模块内分层：controller（@Valid + @AuditLog）→ service/impl（事务边界与状态机；本 PR 将 `com.fuyun.outpatient.service.impl` 纳入父 POM JaCoCo PACKAGE LINE=1.00 名单——号源权威库存扣减/visit 主状态机/退号退费联动直接驱动资金联动，属「核心业务状态机」，2026-09-19 主控裁决 2）→ mapper/entity（MP 单表链式 + CAS 条件更新防超卖）；对外契约唯一出口 `outpatient/api` 包（OutpatientErrorCode、11 个 payload record）；消息设施归 `internal/`，复用 common 模板基类 `DomainEventSender`/`IdempotentConsumerSupport`（`@Qualifier` 显式定绑 outpatientEventSender/outpatientConsumerSupport）；对外进程内对接一律经对端 api 端口（billing 侧新增 `OutpatientBillingPort`/`SettlementQueryPort`、pharmacy 侧新增 `PrescriptionOpenPort`——PrescriptionFeePort 同款先例），禁跨模块读表、禁 HTTP 自调用。事件红线全链在位：outpatient 全部发布事件经 V204 先登记（id 23/25/31 冻结 UPDATE+兜底 INSERT、id 32–40 新登记）、事务内 `ApplicationEventPublisher` → AFTER_COMMIT 出 `fy.topic`、无任何生产 stub。WS 面 outpatient 自建 `OutpatientWebSocketConfig`（STOMP 端点 `/ws/outpatient` + CONNECT 帧级鉴权拦截器镜像 iot 形态、依赖 system api TokenVerifier 合法面，禁依赖 fuyun-iot——裁决 12）。前端照抄 web 宪法分层：openapi 重生成 → `api/outpatient.ts` → workstation 三页 → portal 免登录基座从零建 → bigscreen 复用 STOMP 范式。

**技术栈：** Java 17（Temurin）/ Spring Boot 3.5.16 / MyBatis-Plus 3.5.17（父 POM 锁定）/ Spring AMQP 3.2.12 / Flyway 11.7.2 / Spring Modulith 1.4.13 / Springdoc 2.8.17 / spring-websocket 6.2.19（Boot BOM 托管，零新增依赖声明）/ Testcontainers（镜像 tag 与 compose 一致：timescale/timescaledb:2.29.2-pg16、redis:8.10.1、rabbitmq:4.3.5-management）/ Vue 3.5.42 + TypeScript 5.9.3 + Element Plus 2.14.5 + Vitest 4.1.11 + @stomp/stompjs（bigscreen 既有）。**零新增第三方依赖**（fuyun-outpatient pom 依赖面对齐 fuyun-billing/fuyun-pharmacy 实证清单，版本全部已在父 POM/BOM/catalog 锁定）。

**基线与分支：** 基线 `dev@d4b335a`（PR #30/PR-4 合并点）；分支名建议 `feat/p1-pr5-m03-outpatient`；计划撰写日期 2026-09-20；执行目录 `D:\code\project\fuyun-medical`（Git Bash），勿在计划交付物之外切分支/提交。

**本计划范围声明（docs/plans/2026-09-14-P1实施计划.md :81-87 原文）：**

> ### PR-5 M03 门诊主流程（阶段核心，与 PR-4 事件互切真实）
>
> - 范围（03-outpatient Spec P0 条目 FU-M03-01~08）：号源池管理、预约挂号/当日挂号/退号退费联动（**含 portal 患者线上预约渠道，已裁决**）、分诊台管理、候诊叫号（大屏展示复用 bigscreen 应用与既有 WebSocket 链路；语音外放属现场外设，软件侧仅预留叫号信号输出）、门诊医生站（患者列表/接诊诊毕/检查检验申请开单/处方开立（调 M06 开方 API）/处置治疗单——病历书写按已裁决的临时纯文本文书能力过渡，M09 编辑器维持 P4）、门诊收费编排（划价→`outpatient.order.charged` 放行→发药联动）、门诊药房发药衔接。
> - 执业授权强校验真实化（`POST /api/v1/system/practice/check` 由 P0 骨架转真实查询，M01 小改）。
> - 事件接线回切：PR-4 的 stub 放行替换为真实 `outpatient.order.charged`/`billing.settlement.completed` 事件流。
> - 前端 workstation：门诊医生站 + 分诊台 + 挂号收费联动页；portal：患者预约挂号页（线上渠道）；bigscreen：候诊叫号页。
> - 验收：**门诊挂号→就诊→开单→收费→发药全流程 IT 与真栈演示**（阶段交付验证物）；退号→退费→放行回滚 IT。

**not in scope（明确不做，避免误判遗漏）：**

- **FU-M03-09/10/11**（P1 实施计划 :108-110）：门诊护士站/治疗室执行（`outpatient.order.executed` 不登记不实装）、急诊绿通（green_channel_record 不建表、`billing.charge.guaranteed` 不订阅、`outpatient.green-channel.opened/closed` 不登记）、自助机（kiosk_terminal/kiosk_txn_log 不建表）。分诊台仅承载报到/二次分诊/调级，绿通置顶随排除面顺延。
- **演示终点直线段（裁决 0 显式声明）**：验收直线段=挂号→就诊→开单→收费→发药→诊毕——visit 不走 IN_EXECUTION 回诊段（lab/imaging 执行回执事件源 P3 前缺位）、不走 PENDING_MEDICATION 独立驻留段（发药回执仅做「已发药」聚合展示）。visit 枚举的 IN_EXECUTION/PENDING_MEDICATION/NO_SHOW 与 clinic_order 枚举的 IN_EXECUTION/COMPLETED 为**声明态**（状态机合法迁移对登记、P1 无生产触发点，javadoc 注记触发事件源与阶段），非死代码豁免面。
- **回诊/复诊队列回环**：「一次挂号管三天」复诊免费号、回诊优先级因子在优先级公式中声明取值，回环业务流随 P3 执行回执事件源到位后贯通。
- **M09 病历编辑器**（P1 裁决 2）：诊毕前置校验的「待写文书清单」参数化为提醒不拦截（`GET /api/v1/emr/documents/pending-list` M09 P4 前不存在，本 PR 不调用），离院去向国标代码落 visit.disposition。
- **M01 通知中心**：预约成功/停诊/叫号的患者通知触达（短信/公众号）不实装（M01 通知通道 P1 后续交付）；`outpatient.queue.called` 不登记事件（纯 WS 通道承载大屏/医生站，裁决面；语音由叫号客户端本地播报）。
- **M04/M07/M08/M05/M18/M19 消费方**：`outpatient.visit.registered/finished`、`appointment.*`、`schedule.stopped` 按 Spec §7 登记并发布（订阅方缺位不阻断），M07/M08/M05 放行扇出消费随 P3。
- **RBAC 403 强制**：新端点权限点登记与 API 权限强制 403 属 P1 后续（PracticeController javadoc :15 先例：仅 401 认证拦截）——路由 meta 权限语义先登记（patient/billing 三页先例）。
- **E2E**；**门诊诊疗信息页数据集视图 API**（`GET /visits/{visitId}/info-page` 72 项全集随 M19/M09 取数面 P4，本 PR 仅落 visit 就诊过程段权威字段）。

## Global Constraints（每个任务隐含遵守）

- **JDK17 命令前缀**：所有 Maven 命令一律 `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml ...`（系统默认 JDK21，忘加前缀即工具链红线违规）。
- **Maven 形态（D-11 裁决沿袭）**：模块内验证 `-pl fuyun-outpatient -am`（跨模块改动带 `.am` 侧如 `-pl fuyun-pharmacy,fuyun-billing,fuyun-patient,fuyun-system -am`；IT/装配为 `-pl fuyun-app -am`）；**`-am` 必须携带**；带 `-Dtest=`/`-Dit.test=` 过滤的命令必须同时带 `-Dsurefire.failIfNoSpecifiedTests=false`（IT 过滤另带 `-Dfailsafe.failIfNoSpecifiedTests=false`）。
- **迁移号段红线**：outpatient 首批固定百位段 **V200–V299**（裁决 1；首批 V200–V204，outpatient schema 基线零迁移触发乱序守卫「号段初始化豁免」`scripts/check-migration-governance.py:152-154`，批次合入后 outpatient 后续迁移一律走 V500+ 通用段）；practice_grant 走 system 通用段 **V704**、门诊字典种子走 **V705**（裁决 9 原拟 V608 经守卫算术改道——system 非零迁移 schema 新迁移必须 > 基线全局最大 V703，见前置项 P-2 与偏差②）；**禁改已应用迁移**（V1–V5/V100–V105/V300–V303/V400–V403/V500–V503/V600–V606/V607/V700–V703 冻结，**V605 id 23 与 V702 id 25/31 仅允许数据行 payload_desc UPDATE 且 UPDATE 语句落在 outpatient 段 V204**，V605/V702 文件本身一字不动）；迁移描述全小写下划线；种子幂等形态 `INSERT ... WHERE NOT EXISTS`；**存量 dev 卷一次性重置**为 outpatient 首批进入条件（V200–V204 低于基线 V703，Flyway outOfOrder=false 拒绝——脚本 docstring :7-11「存量环境经 CHANGELOG 登记的一次性重置承接」，Task 1 登记、**Task 13 api-docs 导出前执行**——导出要求 backend 在含 V200–V204 的新卷上启动，旧卷必因 Flyway pending 拒绝启动；重置后全新卷供 Task 15 真栈复用）。
- **事件字面量三方一致红线**：每个事件的「event_registry 种子行（V204 payload_desc）↔ OutpatientMessagingConstants 事件字面量 ↔ api payload record 组件名」三方逐字一致，任何一侧变更属 CF-3/CF-4/CF-5 契约变更须**双向评审**并在 PR 描述声明（CF-5 双向评审声明随 PR——id 23/25/31 三行 desc 冻结即裁决 3 授权载体；Task 3 契约测试为可执行锚）。
- **事件 id 排定（全局递增，按迁移执行序，裁决 1/3）**：id 23=outpatient.order.created（V605 已占，V204 冻结载荷 desc）、id 25=outpatient.order.charged（V702 已占，V204 冻结）、id 31=outpatient.order.cancelled（V702 已占，V204 冻结）；新登记 **id 32=outpatient.visit.registered、33=outpatient.visit.finished、34=outpatient.visit.cancelled、35=outpatient.visit.no-show（仅登记无发布点，触发点随当日爽约判定任务，id 27 先例）、36=outpatient.appointment.booked、37=outpatient.appointment.cancelled、38=outpatient.appointment.rescheduled、39=outpatient.appointment.timeout（延迟队列回调内部事件，自产自消）、40=outpatient.schedule.stopped**；总行 31→40；MessagingGovernanceIT 总行断言与 V204 种子**同任务落改**（Task 3，PR-3「种子+断言同任务」Task 17 先例）；`system.practice.changed` 已随 V5 id 6 登记（Task 2 仅发布接线，零新登记）；`queue.called` 不登记（纯 WS 通道）。
- **stub 边界红线（PR-4 裁决延续，本 PR 回切后废止）**：PR-5 起 outpatient 全部发布事件有真实发布点——IT 内如需注入上游帧（billing.*/pharmacy.*）仍一律 `RabbitTemplate` + `EventEnvelopeCodec.create(...)` 手工合成信封（BillingSettlementFlowIT :230-245 形态）；禁止为本 PR 测试新建任何占位发布器。
- **common 模板类多实例红线**：fuyun-common `DomainEventSender`/`IdempotentConsumerSupport` 为跨模块多实例 Bean——fuyun-app 上下文同类型多候选（patient/billing/pharmacy/outpatient 四套），注入点必须 `@Qualifier` 显式定绑（outpatientEventSender/outpatientConsumerSupport），禁赌 Spring 回退链；对应单测按位置构造传 mock 不受影响零改动。
- **事件事务红线（A.4.2-7）**：发布走「事务内 `ApplicationEventPublisher.publishEvent(OutpatientDomainEvent)` → AFTER_COMMIT `@TransactionalEventListener(fallbackExecution=true)` → fy.topic 直发」（OutpatientEventPublisher 范式照抄 PharmacyEventPublisher）；**事务内禁 MQ 发送与外部调用**；不注册 Confirm/Returns 回调（共享单槽位归 SystemEventPublisher，TASK.md W-11）；消费走 `@RabbitListener` + `IdempotentConsumerSupport` 标准三段式；不使用 @Externalized；延迟投递经 `fy.delay`（`declareDelayQueue` 单档位 `appointment-timeout`，到期经 DLX 以 `outpatient.appointment.timeout` 路由键回 fy.topic）。
- **IT 容器类级独占红线**：fuyun-app 新增 IT 一律各自声明三容器（PostgreSQL/Redis/RabbitMQ，tag 与 compose 严格一致 + it/rabbitmq.conf 挂载 + @ServiceConnection），禁收敛入 FuyunStackITBase（共享 broker 令手工捕获队列跨 IT 抢消费串扰，PR-3 第 2 轮审查 P1-1 主控裁决）；登录/播种/POST 助手复用 FuyunStackITBase（admin/Fuyun@2026、seedReviewerUser、postJson）。
- **覆盖率门禁**：`com.fuyun.outpatient.service.impl` 纳入父 POM 规则二 PACKAGE LINE=1.00（Task 1 落改；包不存在时规则零包平凡通过，首个 impl 落码即生效）；**既有 `com.fuyun.pharmacy.service.impl` LINE=1.00 与 `com.fuyun.billing.service.impl` LINE=1.00 不回退**（Task 9/10/11 billing/pharmacy 改动同步补测试）；**跨模块 PortImpl 所在包（billing/system/pharmacy service.impl）LINE=1.00 由各消费任务补转调单测承载**（OutpatientBillingPortImpl→Task 6、PracticeCheckPortImpl→Task 8、PrescriptionOpenPortImpl/PrescriptionCancelPortImpl→Task 9、SettlementQueryPortImpl→Task 10）；BUNDLE LINE ≥ 0.80 全局；config/dto/entity/constants/convert/*ConverterImpl/*Application/生成代码在 excludes。
- **模块依赖方向（B.1/B.2）**：outpatient 只依赖 fuyun-common 与 system/patient/billing/pharmacy/integration 的 **api 包**（禁依赖 fuyun-iot——WS 拦截器镜像自建，裁决 12；禁跨模块读表；billing 两 api 端口与 pharmacy 开方端口为唯一进程内对接面）；`internal/` 禁外引；`ApplicationModules.verify()` 随 fuyun-app 门禁自动把关（outpatient 入图即校验，outpatient→pharmacy/billing 与 pharmacy/billing→outpatient 双向禁（事件承载），入图即证）。
- **资金无涉红线（金额不涉改「资金动作归 M13」口径，裁决 7）**：本模块零资金逻辑——DDL 不设金额列（挂号费/退费金额一律 M13 权威）；挂号费计费走 billing 手工计费通道（`POST /fees/manual`，reason=门诊挂号费）、退号退费统一 `POST /api/v1/billing/refunds` 免审档经 `OutpatientBillingPort` 进程内承载；禁止本模块自行「退款成功」——appointment/visit 终态一律消费 `billing.refund.approved` 后置；前端凡展示计费金额处一律 string 透传 billing 契约（web A.3-6）。
- **API 契约（A.3）**：前缀 `/api/v1/outpatient/`；资源复数小写连字符、动作子路径 POST（`/appointments/{no}/take|cancel|reschedule`、`/schedules/{id}/stop|resume`、`/schedules/generate`、`/triage/check-in|adjust`、`/queue/call`、`/queue/tickets/{id}/pass|recall`、`/visits/{visitId}/admit|finish`、`/visits/{visitId}/orders`、`/orders/{no}/cancel`）；成功直出 DTO；失败 ProblemDetail + `properties.errorCode` = **`OP-xxxx`**（前缀查证：全仓 grep `"OP-` 零命中，2026-09-20 核验，Task 1 复验）；分页 `page`（0 基）/`size` → `{content,page,size,total}`（common `PageResult.of`）；数量 DECIMAL 出入参 string 承载（D-18 同源）；visit_id/appointmentNo 等雪花外业务号 string 承载。
- **错误码接续（OutpatientErrorCode，OP-1001 起连续无重号）**：OP-1001 VISIT_NOT_FOUND（404）/ OP-1002 POOL_NOT_FOUND（404）/ OP-1003 POOL_EXHAUSTED（409 号源不足）/ OP-1004 SCHEDULE_STATE_NOT_ALLOWED（409 停诊/状态违例）/ OP-1005 DUPLICATE_APPOINTMENT（409 同日同科限约）/ OP-1006 APPT_RESTRICTED（409 爽约限约期内）/ OP-1007 PATIENT_BLOCKED（409 冻结/合并中拦截）/ OP-1008 PAY_DEADLINE_PASSED（409 支付时限已过）/ OP-1009 APPOINTMENT_STATE_NOT_ALLOWED（409）/ OP-1010 CANCEL_WINDOW_CLOSED（409 线上退号时限外转窗口）/ OP-1011 VISIT_STATE_NOT_ALLOWED（409）/ OP-1012 TICKET_NOT_FOUND（404）/ OP-1013 TICKET_STATE_NOT_ALLOWED（409）/ OP-1014 ORDER_NOT_FOUND（404）/ OP-1015 ORDER_STATE_NOT_ALLOWED（409）/ OP-1016 FINISH_CHECK_FAILED（409 诊毕在途单据校验未过）/ OP-1017 PRACTICE_CHECK_FAILED（403 开单执业授权未过）/ OP-1018 DISPOSITION_INVALID（400 离院去向代码词表外）/ OP-1019 PARAM_FORMAT_INVALID（400 入参显式格式校验——W-22⑦「禁裸 parse」先例）。
- **Redis 键规范（A.5-1，W-22① 教训直接应用——一律 `fy:` 前缀 + 显式 TTL）**：`fy:outpatient:pool:{poolId}` 号源余量计数（string，TTL=sched_date 次日 02:00 对账窗口缓冲）；`fy:outpatient:visit-seq:{yyyyMMdd}` 当日签发流水（INCR，TTL=48h，裁决 11）；`fy:outpatient:pay-hold:{apptNo}` 预约占位键（TTL=支付时限+5min 缓冲）；`fy:outpatient:queue:{deptCode}` 候诊 ZSET（TTL=当日末+2h）；禁无 TTL 键、禁 JDK 序列化（StringRedisTemplate 承载）。
- **号源扣减双道闸（Spec 3.2 选定）**：第一道 Redis Lua 原子校验扣减（余量谓词 `used<total` + EXPIRE），Redis 异常降级直连 DB 条件更新（功能不中断，warn 留痕）；第二道预约单落库与池行条件更新同事务——`UPDATE outpatient.appt_number_pool SET used_count = used_count + 1, version = version + 1 WHERE id = #{poolId} AND deleted = 0 AND status = 'ACTIVE' AND used_count < total_quota AND version = #{version}`（version 乐观锁 + 余量谓词，影响行数 0 即重读重试至多 2 次后判 OP-1003）；回池对称 `used_count - 1` 带 `used_count > 0` 谓词。
- **MyBatis-Plus 3.5.17 包路径（禁凭记忆写）**：`IService`/`ServiceImpl` 在 `com.baomidou.mybatisplus.spring.service[.impl]`；分页 `com.baomidou.mybatisplus.extension.plugins.pagination.Page`；wrapper 断言只对 `getSqlSegment()`/`getParamNameValuePairs()` 做 contains 子串断言；条件更新（池行扣减/回池/状态 CAS）一律 @Update 注解 SQL + 影响行数判定（FeeRecordMapper.casMarkFeesSettled 实证形态）；注解 SQL 显式补 `deleted = 0`（不继承 @TableLogic）。
- **单测构造范式（patient/billing/pharmacy 实证）**：service impl 纯单测 = MockitoExtension + `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), 实体.class)`（@BeforeAll）+ 构造器注入 collaborator mock + `ReflectionTestUtils.setField(impl, "baseMapper", mockMapper)`（ServiceImpl 继承字段）。
- **审计与安全**：退号/改期/加号/停诊/绿通位（不适用）/信用解除/号源配置变更/开单/作废/接诊/诊毕/报到/二次分诊/叫号端点一律 `@AuditLog(AuditActionType.WRITE)`（`com.fuyun.system.api`）；日志中文含业务锚点（visitId/apptNo/poolId/orderNo/ticketNo），患者敏感字段禁明文（日志与事件载荷脱敏——03 Spec §9）；portal 免登录端点不经 OperatorContextHolder（操作者留痕取哨兵值 `PORTAL`，裁决 13）。
- **visit 状态机红线（03 Spec 红线 5）**：全部 visit 迁移经 OutpatientVisitStateMachine 单点校验（合法迁移对静态表）+ `visit_status_log` 每迁必记（from/to/reason/operator/occurredAt）；`FINISHED/CANCELLED` 后拒绝一切开单/缴费/执行动作（OP-1011）；合法迁移对全集=REGISTERED→WAITING、WAITING→IN_CONSULT、IN_CONSULT→PENDING_FEE、PENDING_FEE→IN_CONSULT、REGISTERED→CANCELLED、WAITING→CANCELLED、IN_CONSULT→FINISHED、PENDING_FEE→FINISHED、REGISTERED→NO_SHOW（声明态）。
- **注释/日志/编码**：注释与日志全中文（业务意图与「为什么」）；标识符英文；UTF-8 无 BOM、LF、文件末单换行（pre-commit/CI hygiene 同源）；提交前 `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml spotless:apply`。
- **提交规范**：conventional commits、中文 subject、body 每行 ≤100 字符（提交前 python 逐行 len 自查；本地无 commit-msg 钩子）；type 仅 build/chore/ci/docs/feat/fix/perf/refactor/revert/style/test（无 config）。
- **前端门禁（web C.4/C.5）**：`cd web && pnpm lint && pnpm format:check && pnpm type-check && pnpm test && pnpm build` 五连全绿；api.d.ts 重生成 diff 为空（新鲜度本地核对，CI 通道登记 PR 描述）；组件 `<script setup lang="ts">` 零例外、禁 any；api.d.ts 生成物唯一来源（A.3-3，禁手写契约类型）；**新页面自带合规形态（W-22⑥/⑦ 教训）**：动作按钮 loading+在途守卫+零出网用例、入参显式格式校验 4xx 提示、禁裸 parse。
- **前端三应用门禁口径**：workstation 增三页（挂号收费联动/分诊台/门诊医生站）；portal 免登录预约页（裁决 13——portal http 客户端/auth 基座从零建，抄 workstation api/http.ts 形态，无登录页、匿名直连 `/api/v1/outpatient/portal/**` 白名单通道）；bigscreen 候诊叫号页复用 useIotStomp 连接范式（buildBrokerUrl 换 `/ws/outpatient`，订阅 `/topic/outpatient/queue/{deptCode}`）；portal/bigscreen 无权限路由语义（公开页 meta `{ public: true }`）。
- **WS 自建依赖约束（裁决 12）**：outpatient 自建 `OutpatientWebSocketConfig`（@EnableWebSocketMessageBroker 与 iot 侧重复导入为 Spring 去重 no-op）+ `OutpatientConnectAuthInterceptor`（镜像 `fuyun-iot/internal/StompConnectAuthInterceptor.java` 语义：CONNECT 帧 Bearer 令牌经 `com.fuyun.system.api.TokenVerifier` 校验、拒绝抛 MessagingException、日志不含令牌——iot 侧类在 internal 包禁外引，镜像复制为唯一合法形态，偏差⑥）；broker 复用 `/topic` 前缀（iot 侧已 enableSimpleBroker("/topic")，同值幂等）；通道=`/topic/outpatient/queue/{deptCode}` 与 `/topic/outpatient/doctor/{doctorId}`（Spec :179）；REST 快照 `GET /queues/{queueId}/tickets` 双通道（Spec :153）；推送端到端 ≤2s（Spec :198）。
- **portal 匿名通道（裁决 13）**：`SystemWebConfig.AUTH_WHITELIST`（:72-77 实测常量）追加 `"/api/v1/outpatient/portal/**"` 单条目——portal 域端点免 401、服务端经介质解析（就诊卡号/证件号 → `patient/api/PatientContextResolver`+标识解析）定 patientId；portal 患者账号体系随 M18/P6 完整化（P1 演示口径注记）；portal 域端点限流/风控随 M18 注记。
- **真栈环境**：`docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d`（deploy/.env 在位不入库）；后端镜像重建 `docker build -f backend/Dockerfile -t fuyun/backend:dev backend`；backend 不发布宿主端口（容器内 curl 探测）；**outpatient 首批合入后 dev 卷须先重置再起栈**（Task 1 CHANGELOG 登记、Task 13 执行——前移至 api-docs 导出之前，Task 15 真栈复用该重置后卷）。
- **执行目录与分支**：`D:\code\project\fuyun-medical`（Git Bash）；自 `dev`（≥d4b335a）建分支 `feat/p1-pr5-m03-outpatient`；一切变更走 PR，实现 PR 质量门 = 全量门禁 + 真栈 + 浏览器真机（三应用 UI 面）→ `/code-review` 插件审核通过方可合并（PR-2 交接 §3，不可跳过）。
- **先实测条款**：标注「先实测」的步骤必须先跑给定命令取实况再落码，禁凭记忆写 API/包路径/行号/计数字面。
- **workspace 卫生**：AGENTS.md 存在用户未提交改动（`git status` 实证）——执行全程勿动、勿纳入本 PR 提交面；`.playwright-cli/`、`.superpowers/` 未跟踪产物不纳入提交面。

## 前置项（pr5-recon 14 条裁决 → 任务映射）

| # | 前置项 | 处置 | 落点 |
| --- | --- | --- | --- |
| P-0 | **W-22 独立 fix PR 先行**（裁决 14，TASK.md :46 口径）：九条合规遗留（Redis 键 fy: 前缀+TTL/枚举化评估/N+1/saveBatch/FQN/前端防抖/裸 parse/注释订正/prescription_item.status 注记）由计划外独立 fix PR 闭合——**fix PR 本身在计划外先行，计划仅登记其合入为进入条件**；TASK.md W-22 行由该 fix PR 回填删除，本 PR Task 1 Step 1 核验其已闭合，新页面/DTO 自带合规形态 | 进入条件核验（不闭合则阻断 Task 1 提交） | **Task 1** |
| P-1 | **outpatient 号段初始化批次 V200–V204**（裁决 1）：CHANGELOG 先记再改（号段登记+存量卷重置声明+首批清单）；outpatient schema 基线零迁移→乱序守卫初始化豁免放行；批次合入后 outpatient 迁移一律 V500+ 通用段 | 登记条目（迁移落文件随各域任务） | **Task 1** |
| P-2 | **practice_grant 走 system 通用段 V704**（裁决 9 的守卫算术改道，偏差②）：V608<基线最大 V703 必被乱序守卫拦截（system 非零迁移 schema 无初始化豁免）；V704∈(500,None) 合法（V607 先例） | V704 迁移全文（Task 2 Step 1） | **Task 2**（登记在 Task 1） |
| P-3 | **id 23/25/31 载荷冻结双形态**（裁决 3 + 偏差①）：V204 应用序先于 V605/V702——纯 UPDATE 在新库 no-op 后被 V605/V702 以占位 desc 首插终态漂移；落「UPDATE（存量卷）+ WHERE NOT EXISTS 兜底 INSERT（新库）」双语句形态保两序同终态；CF-5 双向评审声明随 PR | V204 迁移全文（Task 3 Step 1） | **Task 3**（登记在 Task 1） |
| P-4 | **MessagingGovernanceIT 总行断言 31→40**（PR-3/PR-4「种子+断言同任务」先例）：V204 新增 9 行（id 32–40）；断言块 :300-319 实测段整块替换与种子同任务落改保证每任务提交全绿 | 断言块整体替换 | **Task 3** |
| P-5 | **fuyun-outpatient 工程挂接**：pom 三处（fuyun-outpatient 依赖面/fuyun-app 依赖/父 POM jacoco include `com.fuyun.outpatient.service.impl`）+ `OutpatientConfig` 空壳 + 装配冒烟（ModulithBoundaryTest 入图） | pom 三处落改 + 空壳 | **Task 1**（装配接线续于 Task 3） |
| P-6 | **billing/pharmacy/patient/system 四侧小改点归位**：billing 两 api 端口（OutpatientBillingPort/SettlementQueryPort，Task 10）；pharmacy 四点回切（releaseByRxNos/verify 凭证/confirmRefundTerminal 收口/order.cancelled 实装）+ practice/check 接线（Task 9/11）；patient unmask 三态门禁（CareRelationQuery SPI，Task 2）；system practice_grant 域（Task 2） | 各任务独立交付 | **Task 2/9/10/11** |
| P-7 | **api.d.ts 重生成流程**（PR-4 Task 12 Step 1 同款）：存量卷重置（down -v+up，Task 1 登记的进入条件）→ mvn package → 镜像重建 → compose 重建 backend → 容器内导出 api-docs → `pnpm gen:api` → diff 核对 | workstation/portal 五连门禁前置 | **Task 13** |
| P-8 | **门户匿名通道**（裁决 13）：SystemWebConfig.AUTH_WHITELIST 追加 `/api/v1/outpatient/portal/**` 单条目 + portal 域三端点（可约号源查询/预约/退号） | 白名单 + PortalController | **Task 5** |
| P-9 | **D-16 三态门禁**（裁决 10）：patient/api 新 `CareRelationQuery` SPI（无实现=维持角色豁免单门禁 warn，OngoingVisitQuery 冻结语义同款）+ PrivacyServiceImpl unmask 第二道校验 + M03 实现（在途就诊操作者匹配）+ IT 断言 | SPI + 两端接线 | **Task 2** |

## 文件结构（本计划全量改动面）

| 动作 | 文件 | 职责 |
| --- | --- | --- |
| 修改 | `CHANGELOG.md` | Task 1 先记再改（号段初始化/存量卷重置/V704 改道/事件 id 排定/jacoco 扩名单/W-22 前置）；Task 15 收口条目 |
| 修改 | `TASK.md` | Task 1 核验 W-22 已闭合（fix PR 外部完成）；Task 15 回填删除本 PR 新登记项 |
| 修改 | `backend/pom.xml`（父 POM） | Task 1 JaCoCo 规则二 includes 增 `com.fuyun.outpatient.service.impl` |
| 修改 | `backend/fuyun-outpatient/pom.xml`、`backend/fuyun-app/pom.xml` | Task 1 依赖面扩展与 app 挂接 |
| 修改 | `backend/fuyun-system/src/main/resources/db/migration/system/V704__create_practice_grant.sql` | Task 2 practice_grant 建表+演示医师种子（含 sys_user/sys_employee/sys_user_role 最小行） |
| 修改/创建 | system `api/SystemErrorCode.java`、`service/IPracticeService.java`、`service/impl/PracticeServiceImpl.java`、`internal/PracticeChangedEvent.java`、`internal/SystemEventPublisher.java`、`controller/PracticeGrantController.java`、`dto/PracticeGrantCreateRequest.java` | Task 2 check 真实化+管理端点+practice.changed 发布 |
| 修改 | patient `api/CareRelationQuery.java`（新建）、`service/impl/PrivacyServiceImpl.java` | Task 2 D-16 三态门禁 |
| 创建 | `backend/fuyun-outpatient/src/main/resources/db/migration/outpatient/V200__create_schedule_and_pool.sql` | 排班模板/排班日历/号源池三表（Task 4） |
| 创建 | `V705__seed_outpatient_dict.sql`（system 段） | appt-type/visit-type/disposition 三类字典 19 条（Task 4） |
| 创建 | `V201__create_appointment_and_visit.sql` | appointment/visit/appt_credit_record/visit_status_log 四表（Task 5） |
| 创建 | `V202__create_triage_and_queue.sql` | triage_record/queue_ticket 两表（Task 7） |
| 创建 | `V203__create_clinic_order.sql` | clinic_order/clinic_order_item 两表（Task 8） |
| 创建 | `V204__seed_outpatient_event_registry.sql` | id 23/25/31 冻结双形态 + id 32–40 登记（Task 3） |
| 创建 | `backend/fuyun-outpatient/src/main/java/com/fuyun/outpatient/`（api/11 record+OutpatientErrorCode、constants/OutpatientMessagingConstants、enums/9 枚举、entity/10、mapper/、dto/、vo/、properties/OutpatientProperties、cache/、service/、service/impl/、controller/） | 门诊全域（Task 4–10 分批落码） |
| 创建 | `internal/OutpatientDomainEvent.java`、`OutpatientEventPublisher.java`、`OutpatientMessagingConfig.java`、五监听器 | 消息装配与消费（Task 3/5/6/8/10） |
| 创建 | `config/OutpatientWebConfig.java`、`OutpatientWebSocketConfig.java`、`internal/OutpatientConnectAuthInterceptor.java` | 装配与 WS（Task 3/7） |
| 创建 | `resources/lua/pool_deduct.lua`、`pool_release.lua` | 号源 Redis 原子预扣/回补（Task 4/5） |
| 修改 | billing `api/OutpatientBillingPort.java`、`api/SettlementQueryPort.java`（新建）+ `service/impl/` 两 PortImpl + `config/BillingWebConfig.java` @Import | 进程内对接面（Task 6/10） |
| 修改 | pharmacy `service/IDispenseService.java`、`service/impl/DispenseServiceImpl.java`、`internal/PharmacyChargedOrderListener.java`、`internal/PharmacyRefundApprovedListener.java`、`internal/PharmacyOrderCancelledListener.java`、`controller/DispenseController.java`、`api/PrescriptionOpenPort.java`（新建）、`service/impl/PrescriptionServiceImpl.java` | 放行链/退费收口/凭证核验/作废路径回切 + practice/check 接线（Task 9/11） |
| 修改 | `backend/fuyun-app/src/test/java/com/fuyun/app/MessagingGovernanceIT.java:300-319` | 总行断言 31→40（Task 3） |
| 修改 | system `config/SystemWebConfig.java:72-77` | AUTH_WHITELIST 追加 portal 匿名条目（Task 5） |
| 创建 | `backend/fuyun-app/src/test/java/com/fuyun/app/OutpatientFullFlowIT.java`、`OutpatientRefundRollbackIT.java`、`OutpatientPoolConcurrencyIT.java` | 三验收锚点 IT（Task 12） |
| 创建/修改 | `web/apps/workstation/src/api/outpatient.ts`、`views/outpatient/`（3 页+specs）、`router/index.ts`、`views/layout/components/AppSidebar.vue`、`web/packages/shared/src/api.d.ts` | workstation 三页（Task 13） |
| 创建/修改 | `web/apps/portal/src/api/http.ts`、`api/outpatient.ts`、`views/appointment/AppointmentView.vue`（+spec）、`router/index.ts` | portal 预约页（Task 14） |
| 创建/修改 | `web/apps/bigscreen/src/api/outpatientQueue.ts`、`composables/useQueueStomp.ts`（+spec）、`views/queue/QueueBoardView.vue`（+spec）、`router/index.ts` | bigscreen 叫号页（Task 14） |
| 修改 | `docs/specs/modules/03-outpatient.md`、`docs/specs/modules/06-pharmacy.md` §7、`CHANGELOG.md` | 收口注记与变更登记（Task 15） |

---

### Task 1: 先记再改与工程前置收口（W-22 进入条件 / 号段登记 / V704·V705 改道声明 / pom 三处 / jacoco / 装配冒烟）

**Files:**
- Modify: `CHANGELOG.md`（头部说明块之后插入新条目）
- Modify: `backend/pom.xml:277`（JaCoCo 规则二 `<includes>` 段——先实测行号）
- Modify: `backend/fuyun-outpatient/pom.xml`（依赖面整体替换）
- Modify: `backend/fuyun-app/pom.xml`（fuyun-pharmacy 依赖块后追加 outpatient 依赖块）
- Create: `backend/fuyun-app/src/main/java/com/fuyun/app/config/OutpatientConfig.java`

**Interfaces:**
- Consumes: 既有 `_SEGMENTS` 结构（outpatient 条目已在表：`((200, 299), (500, None))`，Task 1 仅核验不需落改——号段登记先例 V200 段自 2026-09-08 初始登记即在位）；fuyun-pharmacy/pom.xml 依赖清单（实证模板）；父 POM 规则二「包不存在时规则匹配零包平凡通过」语义。
- Produces: outpatient 号段初始化批次合法化（Task 4 起迁移落文件与 CI hygiene 前提）；`com.fuyun.outpatient.service.impl` JaCoCo LINE=1.00 生效位；fuyun-app classpath 携带 fuyun-outpatient（Task 3 消息装配、Task 12 IT 的前提）。

- [ ] **Step 1: 进入条件与现状核验（先实测，禁凭记忆）**

```bash
git log --oneline -1                       # 预期：d4b335a 或其后代（基线确认）
git log --oneline --all --grep="W-22" -5   # 预期：命中 fix PR 提交（P-0 进入条件）——未命中则阻断本任务提交并向主控上报
grep -c "^| W-22" TASK.md                  # 预期：0（fix PR 已回填删除 W-22 行）；非 0 同为阻断
grep -rn "\"OP-" backend web/packages --include="*.java" --include="*.ts" | head   # 预期：空——OP 前缀无冲突（2026-09-20 已核，执行时复验）
grep -n "com.fuyun.outpatient.service.impl" backend/pom.xml   # 预期：空——规则二尚无 outpatient 条目，本任务落改
grep -n "outpatient" backend/fuyun-app/pom.xml                 # 预期：空——app 未挂接
ls backend/fuyun-outpatient/src/main/resources/db/migration/outpatient/       # 预期：仅 .gitkeep（号段初始化豁免前提）
ls backend/fuyun-system/src/main/resources/db/migration/system/ | grep -c "V"  # 预期：5（V300–V303+V607，V704/V705 未占）
python scripts/check-migration-governance.py                   # 预期：exit 0，35 个迁移文件全绿（基线 HEAD=d4b335a）
grep -n "isEqualTo(31)" backend/fuyun-app/src/test/java/com/fuyun/app/MessagingGovernanceIT.java   # 预期：:308/:313 两处（Task 3 落改点）
find backend/fuyun-outpatient/src -name "*.java" | wc -l       # 预期：0（api/config 等目录仅 .gitkeep 骨架）
```

- [ ] **Step 2: CHANGELOG 登记（先记再改）**

在 `CHANGELOG.md` 头部说明块（`> 记录规则…`）之后、当前最新条目之前插入：

```markdown
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
  Testcontainers IT 每次全新库不受影响）。
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
```

- [ ] **Step 3: 父 POM JaCoCo 规则二 includes 增 outpatient**

`backend/pom.xml` 规则二 `<includes>` 段（`<include>com.fuyun.pharmacy.service.impl</include>` 行——先实测 :277 附近）之后追加：

```xml
                  <!-- outpatient 域核心包（P1 PR-5 起随首个 impl 实装生效；号源权威库存扣减/visit
                       主状态机/退号退费联动直接驱动资金联动，CHANGELOG 2026-09-20 条目先记再改） -->
                  <include>com.fuyun.outpatient.service.impl</include>
```

- [ ] **Step 4: fuyun-outpatient pom 依赖面扩展（照 fuyun-pharmacy/pom.xml 实证形态）**

`backend/fuyun-outpatient/pom.xml` 的 `<dependencies>` 整块替换为（保留既有 parent/common 两段语义）：

```xml
  <!-- 业务模块（M03 门诊服务）：P1 PR-5 起落迁移与业务实现，依赖面对齐 fuyun-pharmacy 实证清单
       （去加密项；billing/pharmacy api 端口为进程内对接唯一面，禁依赖 fuyun-iot） -->
  <artifactId>fuyun-outpatient</artifactId>
  <dependencies>
    <dependency>
      <groupId>com.fuyun</groupId>
      <artifactId>fuyun-common</artifactId>
      <version>${project.version}</version>
    </dependency>
    <!-- 审计切面注解契约（@AuditLog/AuditActionType）与 TokenVerifier（WS CONNECT 鉴权）：
         仅消费 fuyun-system api 包（B.2-2） -->
    <dependency>
      <groupId>com.fuyun</groupId>
      <artifactId>fuyun-system</artifactId>
      <version>${project.version}</version>
    </dependency>
    <!-- 患者域：PatientContextResolver/VisitIdValidator/OngoingVisitQuery/CareRelationQuery
         （介质解析与结构校验、在途就诊 SPI、诊疗关系 SPI，B.2-2） -->
    <dependency>
      <groupId>com.fuyun</groupId>
      <artifactId>fuyun-patient</artifactId>
      <version>${project.version}</version>
    </dependency>
    <!-- 收费域：OutpatientBillingPort/SettlementQueryPort（退费申请/费用反查/凭证核验，进程内） -->
    <dependency>
      <groupId>com.fuyun</groupId>
      <artifactId>fuyun-billing</artifactId>
      <version>${project.version}</version>
    </dependency>
    <!-- 药事域：PrescriptionOpenPort（医生站开方动作入口，进程内同步调用） -->
    <dependency>
      <groupId>com.fuyun</groupId>
      <artifactId>fuyun-pharmacy</artifactId>
      <version>${project.version}</version>
    </dependency>
    <!-- 自/入事件消费队列声明（MessagingGovernance/ConsumerQueueSpec）：仅 api 包 -->
    <dependency>
      <groupId>com.fuyun</groupId>
      <artifactId>fuyun-integration</artifactId>
      <version>${project.version}</version>
    </dependency>
    <!-- Modulith 注解（@NamedInterface api 出口声明）：provided 不入运行产物 -->
    <dependency>
      <groupId>org.springframework.modulith</groupId>
      <artifactId>spring-modulith-api</artifactId>
      <scope>provided</scope>
    </dependency>
    <!-- API 文档注解（宪法 A.3-7；Springdoc 2.8.17 禁升 3.x，版本与 fuyun-app 同源锁定） -->
    <dependency>
      <groupId>org.springdoc</groupId>
      <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
      <version>2.8.17</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-amqp</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    <dependency>
      <groupId>com.baomidou</groupId>
      <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    </dependency>
    <dependency>
      <groupId>com.baomidou</groupId>
      <artifactId>mybatis-plus-jsqlparser</artifactId>
    </dependency>
    <!-- 实体 @Getter/@Setter 编译期依赖（A.1-12 允许清单），provided 不打进运行产物 -->
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
```

（注意：spring-web/spring-websocket 经 fuyun-common 与 spring-boot-starter-test 传递——controller/@Configuration 编译零新增声明，与 pharmacy/billing 同口径；本 PR 无 MapStruct。）

- [ ] **Step 5: fuyun-app pom 挂接与 OutpatientConfig 空壳**

`backend/fuyun-app/pom.xml` 在 fuyun-pharmacy 依赖块之后追加：

```xml
    <!-- M03 门诊主流程（P1 PR-5）：模块装配经本模块 OutpatientConfig @Import（PharmacyConfig 同模式，
         装配归 app，B.1）；V200–V204 迁移资源随本依赖进入 fuyun-app 的 Flyway classpath
         （spring.flyway.locations 已显式枚举 outpatient 目录） -->
    <dependency>
      <groupId>com.fuyun</groupId>
      <artifactId>fuyun-outpatient</artifactId>
      <version>${project.version}</version>
    </dependency>
```

创建 `backend/fuyun-app/src/main/java/com/fuyun/app/config/OutpatientConfig.java`：

```java
package com.fuyun.app.config;

import org.springframework.context.annotation.Configuration;

/**
 * M03 门诊模块装配：fuyun-outpatient 配置类引入 Boot 上下文的集中入口（PharmacyConfig 同模式，
 * 不放宽组件扫描）；Web/服务面经 OutpatientWebConfig、消息面经 OutpatientMessagingConfig、
 * WS 面经 OutpatientWebSocketConfig——三配置类随 Task 3/5/7 落码后在 {@code @Import} 逐任务
 * 追加（PR-4 装配分段接线同款；本任务先落空壳使 app 依赖可编译、Modulith 把 outpatient 入图）。
 */
@Configuration
public class OutpatientConfig {}
```

- [ ] **Step 6: 守卫自测与装配冒烟**

```bash
python scripts/check-migration-governance.py
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ModulithBoundaryTest'
```

Expected: 守卫 exit 0（35 个迁移文件）；BUILD SUCCESS——ModulithBoundaryTest 对含 outpatient 在内的全模块图 `ApplicationModules.verify()` 零违规（outpatient 尚无业务类，平凡通过；该冒烟同时验证 pom 三处落改后全仓可编译）。

- [ ] **Step 7: 提交**

```bash
git add CHANGELOG.md backend/pom.xml backend/fuyun-outpatient/pom.xml backend/fuyun-app/pom.xml backend/fuyun-app/src/main/java/com/fuyun/app/config/OutpatientConfig.java
git commit -m "chore(gate): outpatient 号段 V200–V299 初始化登记与工程前置收口（PR-5 前置）

- CHANGELOG 先记再改：号段初始化批次 + 存量卷一次性重置 + V704/V705 通用段改道
  + 事件 id 23/25/31 冻结与 32–40 排定 + JaCoCo 核心包扩名单 + W-22 前置核验
- fuyun-outpatient/app 父 POM 依赖面扩展与 OutpatientConfig 空壳挂接"
```

---

### Task 2: practice_grant 真实化与 D-16 三态门禁（V704 建表种子 / 管理端点 / check 真实查询 / practice.changed 发布 / patient SPI）

**Files:**
- Create: `backend/fuyun-system/src/main/resources/db/migration/system/V704__create_practice_grant.sql`
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/api/SystemErrorCode.java:42-45` 之后（追加 SYS-1021/1022）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/service/IPracticeService.java`（接口扩管理三方法）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/service/impl/PracticeServiceImpl.java:22-58`（恒 false 骨架整类重写为真实查询）
- Create: `backend/fuyun-system/src/main/java/com/fuyun/system/entity/PracticeGrant.java`、`mapper/PracticeGrantMapper.java`
- Create: `backend/fuyun-system/src/main/java/com/fuyun/system/dto/PracticeGrantCreateRequest.java`、`vo/PracticeGrantVO.java`、`controller/PracticeGrantController.java`
- Create: `backend/fuyun-system/src/main/java/com/fuyun/system/internal/PracticeChangedEvent.java`
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/internal/SystemEventPublisher.java`（增 onPracticeChanged 监听方法）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/config/SystemWebConfig.java`（@Import 增 PracticeServiceImpl 依赖的 mapper/entity 与 PracticeGrantController）
- Create: `backend/fuyun-patient/src/main/java/com/fuyun/patient/api/CareRelationQuery.java`
- Modify: `backend/fuyun-patient/src/main/java/com/fuyun/patient/service/impl/PrivacyServiceImpl.java:85-95`（unmask 豁免校验后追加第二道）
- Create: `backend/fuyun-system/src/test/java/com/fuyun/system/service/impl/PracticeServiceImplTest.java`
- Create: `backend/fuyun-patient/src/test/java/com/fuyun/patient/service/impl/PrivacyCareRelationGateTest.java`

**Interfaces:**
- Consumes: `PracticeCheckRequest(Long employeeId, String grantType, OffsetDateTime checkTime)` 与 `PracticeCheckResponse(String employeeId, String grantType, OffsetDateTime checkTime, boolean passed, String reason)`（契约冻结不变——PracticeServiceImpl javadoc :12-14 承诺）；`PracticeChangedPayload(Long employeeId, String grantType, String status)`（api 既有，V5 id 6 登记）；SystemEventPublisher AFTER_COMMIT 范式（DictVersionPublishedEvent :127 发布点先例）。
- Produces（Task 8/9/11 依赖的冻结面）:
  - grant_type 词表：`PRESCRIPTION`（处方权）/ `NARCOTIC`（麻精处方权）/ `ANTIBIO_NONRESTRICT`（抗菌药非限制）/ `ANTIBIO_RESTRICT`（限制）/ `ANTIBIO_SPECIAL`（特殊）——01-system.md:58「处方权/麻精处方权/抗菌药{非限制/限制/特殊}」编码化，全仓消费方（Task 8 开单校验、Task 9 开方校验、Task 11 纵深防御）逐字引用。
  - practice_grant DDL：`id BIGINT PK / employee_id BIGINT NOT NULL / grant_type VARCHAR(32) NOT NULL / legal_basis VARCHAR(255) NULL / valid_from DATE NOT NULL / valid_to DATE NULL（NULL=长期）/ status VARCHAR(16) NOT NULL DEFAULT 'EFFECTIVE'（EFFECTIVE/SUSPENDED/EXPIRED）/ approval_ref VARCHAR(128) NULL / created_at·updated_at·created_by·updated_by·deleted 标准审计五列`；部分唯一索引 `uk_practice_grant_active ON system.practice_grant (employee_id, grant_type) WHERE deleted = 0 AND status = 'EFFECTIVE'`。
  - check 语义：`EFFECTIVE 且 (valid_to IS NULL OR valid_to >= checkDate)` 判 passed=true，reason=`执业授权有效：{grantType}`；无命中=`无有效执业授权记录：{grantType}`；命中但 `valid_to < checkDate`=`授权已过期：{grantType}`（读侧派生 EXPIRED，无定时任务口径同 PrivacyAuthServiceImpl.deriveStatus 先例）。
  - `CareRelationQuery` SPI：`boolean hasCareRelation(long patientId, String operatorId)`——patient 侧只声明契约，M03 Task 8 注册实现；无实现时维持角色豁免单门禁（warn，OngoingVisitQuery 冻结语义同款写入 javadoc）。
- 管理端点（FU-M01-04 口径，Admin 401 内）：`POST /api/v1/system/practice-grants`（授权登记，重复 EFFECTIVE 冲突 409 SYS-1022）、`POST /api/v1/system/practice-grants/{id}/withdraw`（停权 SUSPENDED+发布 practice.changed）、`GET /api/v1/system/practice-grants?employeeId=`（清单，valid_to 过期读侧派生 EXPIRED 展示）。

- [ ] **Step 1: 写测试（先红后绿）**

创建 `PracticeServiceImplTest.java`（MockitoExtension，Global Constraints 单测构造范式）用例全集（用例名即业务意图，断言值冻结）：

1. `checkPassesWhenEffectiveGrantWithinValidity`——库返回 EFFECTIVE+valid_to=今日+1：passed=true、reason=`执业授权有效：PRESCRIPTION`。
2. `checkPassesWhenGrantHasNoExpiry`——valid_to=null：passed=true。
3. `checkFailsWhenNoGrantRow`——空清单：passed=false、reason=`无有效执业授权记录：PRESCRIPTION`。
4. `checkFailsWhenGrantSuspended`——status=SUSPENDED 行被查询条件排除后空清单：passed=false（同用例 3 文案；SUSPENDED 行存在性由清单端点可见，check 不放行）。
5. `checkFailsWhenGrantExpiredAtCheckTime`——EFFECTIVE+valid_to=今日-1：passed=false、reason=`授权已过期：PRESCRIPTION`。
6. `checkDefaultsCheckTimeToNowWhenAbsent`——request.checkTime()=null：mapper 查询入参日期=LocalDate.now()。
7. `grantRejectsDuplicateEffectiveGrant`——重复 (employeeId,grantType) EFFECTIVE：抛 BizException SYS-1022。
8. `grantPublishesPracticeChangedOnGrant`——登记成功后 `verify(events).publishEvent(any())` 且捕获 PracticeChangedEvent（employeeId/grantType/status=EFFECTIVE 三组件逐字断言）。
9. `withdrawMarksSuspendedAndPublishesPracticeChanged`——CAS 成功后 status=SUSPENDED + 发布事件（status=SUSPENDED）。
10. `withdrawRejectsUnknownGrant`——CAS 影响行 0：抛 BizException SYS-1021。

创建 `PrivacyCareRelationGateTest.java` 用例全集：

1. `unmaskPassesOnRoleExemptWithoutCareRelationProbe`——豁免命中：`verifyNoInteractions(careRelationQuery)`（豁免短路，第二道不触发）。
2. `unmaskPassesOnCareRelationWhenNotExempt`——无豁免+SPI 返回 true：200 语义（不抛 PAT-1018），台账照落。
3. `unmaskRejectsWhenNeitherExemptNorCareRelation`——无豁免+SPI 返回 false：BizException PAT-1018（403 文案=`无豁免角色且无在途诊疗关系`）。
4. `unmaskKeepsSingleGateWhenNoCareRelationBeanRegistered`——构造时不传 SPI（null）：无豁免仍走原 PAT-1018，不 NPE（ObjectProvider 风格空安全）。

Run（预期红）:

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-system,fuyun-patient -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='PracticeServiceImplTest,PrivacyCareRelationGateTest'
```

预期失败形态：两类编译错误（IPracticeService 无新方法、CareRelationQuery 不存在）——测试先行锚定契约面。

- [ ] **Step 2: V704 迁移（建表 + 演示医师最小种子）**

创建 `backend/fuyun-system/src/main/resources/db/migration/system/V704__create_practice_grant.sql`：

```sql
-- V704：执业授权 practice_grant 表 + 演示医师最小种子（M01 FU-M01-04，recon 裁决 9）。
-- 落位合法性（CHANGELOG 2026-09-20 条目先记再改，偏差②）：system 登记段=(300,399)+(500,None)，
--   704∈通用段且 704>基线全局最大 V703——乱序守卫放行（recon 原拟 V608<V703 必被拦，禁试）。
-- 列结构照 01-system.md:58（employee_id/grant_type/legal_basis/valid_from/valid_to/status/审批引用）。
-- 种子幂等形态 INSERT ... WHERE NOT EXISTS（V303/V607 先例）；演示口令与 V303 admin 同源
--   （Fuyun@2026 仅存 bcrypt 哈希，禁明文入库——V303 :63 红线注记同款）；种子行取小整数 ID
--   （sys_user id=3 与 sys_employee id=3：避开 IT seedReviewerUser 固定占用的 user id=2）。
-- 身份链对齐红线：运行态操作者为 userId（AuthTokenInterceptor :76 `String.valueOf(session.userId())`
--   注入 OperatorContextHolder），Task 8/9 校验入参 Long.parseLong(OperatorContextHolder.get())
--   直作 employeeId——故 employee_id 必须与 sys_user.id 同值（=3），错位即真栈开单/开方 403。

-- ---------------------------------------------------------------- 1. 执业授权表
CREATE TABLE system.practice_grant (
    id              BIGINT        PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    employee_id     BIGINT        NOT NULL,                            -- 员工 ID（sys_employee.id，应用层保证完整性）
    grant_type      VARCHAR(32)   NOT NULL,                            -- 授权类型词表：PRESCRIPTION/NARCOTIC/ANTIBIO_NONRESTRICT/ANTIBIO_RESTRICT/ANTIBIO_SPECIAL
    legal_basis     VARCHAR(255)  NULL,                                -- 法定依据（执业证书号/批文引用等）
    valid_from      DATE          NOT NULL,                            -- 生效日（含当日）
    valid_to        DATE          NULL,                                -- 失效日（含当日）；NULL=长期有效
    status          VARCHAR(16)   NOT NULL DEFAULT 'EFFECTIVE',        -- 状态机：EFFECTIVE 生效/SUSPENDED 停权/EXPIRED 过期（读侧派生为主）
    approval_ref    VARCHAR(128)  NULL,                                -- 审批引用（医务审批单号）
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted         SMALLINT      NOT NULL DEFAULT 0
);

-- 同一员工同一授权类型仅一条生效行（停权/过期行不占唯一性，可重新登记）
CREATE UNIQUE INDEX uk_practice_grant_active ON system.practice_grant (employee_id, grant_type)
    WHERE deleted = 0 AND status = 'EFFECTIVE';
CREATE INDEX idx_practice_grant_employee ON system.practice_grant (employee_id) WHERE deleted = 0;

CREATE TRIGGER trg_practice_grant_updated_at BEFORE UPDATE ON system.practice_grant
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 2. 演示医师账号（复用 V303 admin 口令哈希）
INSERT INTO system.sys_user (id, login_name, password_hash, user_type, status)
SELECT 3, 'doctordemo', '$2a$10$mySbYjh9bHhK7WDdnoKvtOHx.gU.z9I4fbsKfyomOyvh.9zlt/FjW', 'STAFF', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user WHERE login_name = 'doctordemo');

INSERT INTO system.sys_employee (id, user_id, emp_no, emp_name, title, primary_org_id, status)
SELECT 3, 3, 'DOC001', '演示医师', '主治医师', NULL, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_employee WHERE emp_no = 'DOC001');

INSERT INTO system.sys_user_role (id, user_id, role_id)
SELECT 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user_role WHERE user_id = 3 AND role_id = 1);

-- ---------------------------------------------------------------- 3. 演示医师执业授权三档（employee_id=3 与 sys_user.id 对齐，处方权/麻精/抗菌药非限制）
INSERT INTO system.practice_grant (id, employee_id, grant_type, legal_basis, valid_from, valid_to, status, approval_ref)
SELECT 1, 3, 'PRESCRIPTION', '演示执业证书 PR-2026-001', DATE '2026-01-01', NULL, 'EFFECTIVE', 'SEED-PR5'
WHERE NOT EXISTS (SELECT 1 FROM system.practice_grant WHERE employee_id = 3 AND grant_type = 'PRESCRIPTION' AND deleted = 0);

INSERT INTO system.practice_grant (id, employee_id, grant_type, legal_basis, valid_from, valid_to, status, approval_ref)
SELECT 2, 3, 'NARCOTIC', '麻精药品处方权培训合格证 NP-2026-001', DATE '2026-01-01', DATE '2027-12-31', 'EFFECTIVE', 'SEED-PR5'
WHERE NOT EXISTS (SELECT 1 FROM system.practice_grant WHERE employee_id = 3 AND grant_type = 'NARCOTIC' AND deleted = 0);

INSERT INTO system.practice_grant (id, employee_id, grant_type, legal_basis, valid_from, valid_to, status, approval_ref)
SELECT 3, 3, 'ANTIBIO_NONRESTRICT', '抗菌药物临床应用培训非限制级', DATE '2026-01-01', NULL, 'EFFECTIVE', 'SEED-PR5'
WHERE NOT EXISTS (SELECT 1 FROM system.practice_grant WHERE employee_id = 3 AND grant_type = 'ANTIBIO_NONRESTRICT' AND deleted = 0);
```

- [ ] **Step 3: 错误码与实体/mapper**

`SystemErrorCode` 在 `DICT_TYPE_CODE_EXISTS("SYS-1014")` 之后追加（**注意**：:45 实测该行为当前末枚举、以 `;` 收尾——追加前须将该行尾 `;` 改为 `,`，新追加段末行 `PRACTICE_GRANT_DUPLICATE` 保持 `;` 收尾）：

```java
    /** 执业授权记录不存在（404）：withdraw/query 定位失败 */
    PRACTICE_GRANT_NOT_FOUND("SYS-1021"),

    /** 同一员工同一授权类型已存在生效行（409）：重复登记冲突 */
    PRACTICE_GRANT_DUPLICATE("SYS-1022");
```

`PracticeGrant` 实体（@TableName "system.practice_grant" + @TableLogic deleted + 全列字段，照 billing FeeRecord 实体形态）；`PracticeGrantMapper extends BaseMapper<PracticeGrant>` 增两条注解 SQL（显式补 deleted = 0）：

```java
    /** 停权 CAS（仅 EFFECTIVE 可停）：影响行数 0=并发已停或不存在（调用方重读定性） */
    @Update("UPDATE system.practice_grant SET status = 'SUSPENDED', updated_by = #{operator}, updated_at = now() "
            + "WHERE id = #{id} AND deleted = 0 AND status = 'EFFECTIVE'")
    int casWithdraw(@Param("id") long id, @Param("operator") String operator);

    /** 生效授权查询（check 主查询）：有效期含当日，valid_to NULL=长期 */
    @Select("SELECT * FROM system.practice_grant WHERE employee_id = #{employeeId} AND grant_type = #{grantType} "
            + "AND deleted = 0 AND status = 'EFFECTIVE' AND valid_from <= #{checkDate} "
            + "AND (valid_to IS NULL OR valid_to >= #{checkDate}) LIMIT 1")
    PracticeGrant selectEffective(@Param("employeeId") long employeeId, @Param("grantType") String grantType,
            @Param("checkDate") LocalDate checkDate);
```

- [ ] **Step 4: check 真实化 + 管理端点 + 发布链**

`PracticeServiceImpl` 整类重写（保持类名与 `IPracticeService.check` 签名不变，响应契约冻结）：

- 构造器注入 `PracticeGrantMapper practiceGrantMapper, ApplicationEventPublisher events`（双参全参构造，装配归 SystemWebConfig @Import）。
- `check(request)`：`LocalDate checkDate = (request.checkTime() != null ? request.checkTime() : OffsetDateTime.now()).toLocalDate();` → `PracticeGrant grant = practiceGrantMapper.selectEffective(request.employeeId(), request.grantType(), checkDate);` → 命中返回 `new PracticeCheckResponse(String.valueOf(request.employeeId()), request.grantType(), request.checkTime() == null ? OffsetDateTime.now() : request.checkTime(), true, "执业授权有效：" + request.grantType())`；未命中再查「EFFECTIVE 但 valid_to < checkDate」行（`selectOne` lambda status='EFFECTIVE' AND valid_to < checkDate）区分 reason=`授权已过期：{grantType}` 与 `无有效执业授权记录：{grantType}`，passed=false。删除 SKELETON_REASON 常量与 P1 扩展点注释行（死代码零容忍）。
- 管理三方法（接口同步扩）：`long grant(PracticeGrantCreateRequest request)`（重复 EFFECTIVE 冲突捕获唯一索引 violation 转 409 SYS-1022；登记成功即发布 `PracticeChangedEvent`）、`void withdraw(long id, String reason)`（`casWithdraw` 0 行抛 SYS-1021；成功发布 `PracticeChangedEvent`，reason 落 warn 日志）、`List<PracticeGrantVO> listByEmployee(long employeeId)`（valid_to < 今日的 EFFECTIVE 行展示态派生 EXPIRED，不回写库）。
- `PracticeChangedEvent(long employeeId, String grantType, String status)` internal record；`SystemEventPublisher` 增 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true) public void onPracticeChanged(PracticeChangedEvent event)`——照 `onDictVersionPublished` 同型发出（先实测该方法的发送语句形态：`grep -n "onDictVersionPublished" -A 12 backend/fuyun-system/src/main/java/com/fuyun/system/internal/SystemEventPublisher.java`，eventType 传 `"system.practice.changed"`、payload 构造 `new PracticeChangedPayload(event.employeeId(), event.grantType(), event.status())`；Confirm/Returns 回调单槽位红线不新增注册）。
- `PracticeGrantController`（`@RequestMapping("/api/v1/system/practice")`，三端点 `POST /grants`（@AuditLog WRITE）/`POST /grants/{id}/withdraw`（WRITE，body `{"reason": "..."}` @NotBlank）/`GET /grants`）——@Valid + 直出 VO，禁业务逻辑；`PracticeGrantCreateRequest(@NotNull Long employeeId, @NotBlank String grantType, String legalBasis, @NotNull LocalDate validFrom, LocalDate validTo)`（grantType 词表 @Pattern `PRESCRIPTION|NARCOTIC|ANTIBIO_NONRESTRICT|ANTIBIO_RESTRICT|ANTIBIO_SPECIAL`）。
- `SystemWebConfig` @Import 增 `PracticeServiceImpl.class, PracticeGrantController.class`（mapper 经 MP 扫描既有机制——先实测 `grep -n "MapperScan\|@Mapper" backend/fuyun-system/src/main/java -r` 取实况对齐，pharmacy mapper 形态同款）。

- [ ] **Step 5: patient 侧三态门禁**

创建 `backend/fuyun-patient/src/main/java/com/fuyun/patient/api/CareRelationQuery.java`：

```java
package com.fuyun.patient.api;

/**
 * 「诊疗关系查询」SPI 扩展点（D-16 三态硬门禁，02-patient.md:140 归 PR-5 注记兑现；2026-09-19 recon 裁决 10）。
 *
 * <p><b>冻结语义（与 {@link OngoingVisitQuery} 同款契约形态）</b>：Spring 容器内无任何本接口实现时，
 * unmask 第二道校验跳过并记录 warn——维持角色豁免单门禁现状；任一实现（M03 门诊在途诊疗关系）注册后
 * 自动收紧为「无豁免且无诊疗关系即 403」。修改须经消费方（M03）双向评审。
 */
public interface CareRelationQuery {

    /**
     * 判定操作者与患者是否存在在途诊疗关系（明文查阅第二道门禁依据）。
     *
     * @param patientId  患者主索引；来源：unmask 请求体
     * @param operatorId 操作者标识（登录名/工号）；来源：OperatorContextHolder
     * @return true=存在在途诊疗关系（放行并留痕）；false=无关系（403）
     */
    boolean hasCareRelation(long patientId, String operatorId);
}
```

`PrivacyServiceImpl.unmask`（:85-95 实测段）在角色豁免循环之后、解密之前插入第二道：

```java
        // ②诊疗关系校验（D-16 三态第二道）：SPI 无实现=跳过维持单门禁（warn，冻结语义）；
        //   有实现时无豁免字段须命中在途诊疗关系，否则 403（不落查阅台账，留痕由审计切面 FAIL 行承担）
        if (!exemptAll && careRelationQuery != null
                && !careRelationQuery.hasCareRelation(request.patientId(), operatorId)) {
            log.warn("明文查阅拒绝（无豁免角色且无在途诊疗关系）：patientId={}", request.patientId());
            throw new BizException(
                    PatientErrorCode.PRIVILEGE_NOT_ALLOWED, HttpStatus.FORBIDDEN, "无豁免角色且无在途诊疗关系");
        }
```

（`careRelationQuery` 为新构造器可选依赖——`ObjectProvider<CareRelationQuery>` 注入取 `getIfAvailable()` 存字段，无实现为 null；`exemptAll`/`operatorId` 以 Step 1 实测的既有局部变量名为准对齐，:85-90 豁免循环产出。`PRIVILEGE_NOT_ALLOWED` 为 PAT-1018 对应枚举名——先实测 `grep -n "PAT-1018" backend/fuyun-patient/src/main/java/com/fuyun/patient/api/PatientErrorCode.java` 取枚举名替换，禁凭记忆写。）

- [ ] **Step 6: 跑绿 + 守卫**

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-system,fuyun-patient -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='PracticeServiceImplTest,PrivacyCareRelationGateTest,SystemMasterDataPayloadTest'
python scripts/check-migration-governance.py
```

Expected: BUILD SUCCESS——Step 1 全部 14 用例绿；SystemMasterDataPayloadTest 线格式冻结断言不受影响（PracticeChangedPayload 未改）；守卫 36 个迁移文件全绿（35+V704）。

- [ ] **Step 7: 提交**

```bash
git add backend/fuyun-system backend/fuyun-patient
git commit -m "feat(system,patient): 执业授权 practice/check 真实化与 D-16 三态门禁

- V704 建 practice_grant + 演示医师三档种子（处方权/麻精/抗菌药非限制）
- check 骨架转真实查询（响应契约冻结）；grant/withdraw/query 管理端点
- practice.changed 经 SystemEventPublisher AFTER_COMMIT 发布（V5 id6 零新登记）
- patient CareRelationQuery SPI + unmask 第二道门禁（无实现维持单门禁语义）"
```

---

### Task 3: 门诊事件契约种子与消息装配（V204 + Constants + 发布器 + 契约测试 + 门禁断言 31→40）

**Files:**
- Create: `backend/fuyun-outpatient/src/main/resources/db/migration/outpatient/V204__seed_outpatient_event_registry.sql`
- Create: `backend/fuyun-outpatient/src/main/java/com/fuyun/outpatient/constants/OutpatientMessagingConstants.java`
- Create: `backend/fuyun-outpatient/src/main/java/com/fuyun/outpatient/api/OutpatientErrorCode.java`、`api/package-info.java` + 11 payload record（OrderCreatedPayload/OrderChargedPayload/OrderCancelledPayload/VisitRegisteredPayload/VisitFinishedPayload/VisitCancelledPayload/AppointmentBookedPayload/AppointmentCancelledPayload/AppointmentRescheduledPayload/AppointmentTimeoutPayload/ScheduleStoppedPayload）
- Create: `backend/fuyun-outpatient/src/main/java/com/fuyun/outpatient/internal/OutpatientDomainEvent.java`、`internal/OutpatientEventPublisher.java`、`config/OutpatientMessagingConfig.java`
- Modify: `backend/fuyun-app/src/main/java/com/fuyun/app/config/OutpatientConfig.java`（@Import 接线）
- Modify: `backend/fuyun-app/src/test/java/com/fuyun/app/MessagingGovernanceIT.java:299-319`（总行断言 31→40）
- Create: `backend/fuyun-outpatient/src/test/java/com/fuyun/outpatient/api/OutpatientEventContractTest.java`

**Interfaces:**
- Consumes: common `DomainEventSender`/`EventEnvelopeCodec`/`IdempotentConsumerSupport`；integration api `MessagingGovernance`/`ConsumerQueueSpec`；V605 id 17–24 与 V702 id 25–31 既有登记行。
- Produces（Task 4–12 依赖的冻结面）:
  - `OutpatientMessagingConstants.MODULE = "outpatient"`；发布事件字面量 `EVENT_ORDER_CREATED("outpatient.order.created")/EVENT_ORDER_CHARGED/EVENT_ORDER_CANCELLED/EVENT_VISIT_REGISTERED/EVENT_VISIT_FINISHED/EVENT_VISIT_CANCELLED/EVENT_APPOINTMENT_BOOKED/EVENT_APPOINTMENT_CANCELLED/EVENT_APPOINTMENT_RESCHEDULED/EVENT_APPOINTMENT_TIMEOUT/EVENT_SCHEDULE_STOPPED`；登记字面量 `EVENT_REGISTRY_VISIT_NO_SHOW("outpatient.visit.no-show"——仅登记无发布点，禁引用于任何发布点)`；订阅事件字面量 `EVENT_SUB_BILLING_FEE_CREATED/EVENT_SUB_BILLING_SETTLEMENT_COMPLETED/EVENT_SUB_BILLING_REFUND_APPROVED/EVENT_SUB_PHARMACY_PRESCRIPTION_CANCELLED/EVENT_SUB_PHARMACY_DISPENSE_COMPLETED/EVENT_SUB_PHARMACY_DISPENSE_RETURNED`；`SUBSCRIBED_EVENT_TYPES`（Task 3 起空数组，随 Task 5/6/8/10 消费者落码逐批追加）。
  - `OutpatientDomainEvent(String eventType, Object payload)` + `OutpatientEventPublisher`（AFTER_COMMIT，@Qualifier 定绑 outpatientEventSender）。
  - `OutpatientMessagingConfig` 三 Bean：outpatientEventSender / outpatientConsumerSupport / outpatientConsumerQueues。
  - 11 payload record 组件全集（Task 3 契约测试 + Task 4–10 发布构造 + Task 12 IT 断言逐字同源）:
    - `OrderCreatedPayload(String orderId, Long patientId, String visitId, List<Line> lines)`、`Line(String itemCode, String quantity)`——orderId=order_no 业务号（billing sourceRef 直取），quantity DECIMAL string。
    - `OrderChargedPayload(Long settlementId, String settleNo, Long patientId, String visitId, List<String> orderNos, List<String> rxNos, boolean greenChannelFlag)`——rxNos 为 M06 单据精确放行清单（裁决 4）。
    - `OrderCancelledPayload(String orderNo, Long patientId, String visitId, List<String> rxNos, String reason)`——rxNos 为退费逆向终态确认清单（裁决 6）。
    - `VisitRegisteredPayload(String visitId, Long patientId, String visitType, String deptCode, String doctorId)`（M13 医保就诊登记依据，Spec :160）。
    - `VisitFinishedPayload(String visitId, Long patientId, String disposition, String finishOperator)`。
    - `VisitCancelledPayload(String visitId, Long patientId, String reason)`。
    - `AppointmentBookedPayload(String apptNo, Long patientId, String schedDate, String session, String deptCode, String apptType, String channel)`。
    - `AppointmentCancelledPayload(String apptNo, Long patientId, String reason, boolean feeRefundTriggered)`。
    - `AppointmentRescheduledPayload(String oldApptNo, String newApptNo, Long patientId, String newSchedDate, String newSlotStart)`。
    - `AppointmentTimeoutPayload(String apptNo, Long patientId, Long poolId)`（延迟队列回调内部载荷）。
    - `ScheduleStoppedPayload(Long scheduleId, String schedDate, String deptCode, String doctorId, String stopReason)`。

- [ ] **Step 1: V204 迁移（id 23/25/31 冻结双形态 + id 32–40 登记）**

创建 `backend/fuyun-outpatient/src/main/resources/db/migration/outpatient/V204__seed_outpatient_event_registry.sql`：

```sql
-- V204：outpatient 事件契约种子（CF-3/CF-5 冻结载体；V605/V702 先例）。
-- 应用序要点（偏差①）：本迁移版本号低于 V605/V702——全新库先于 billing/pharmacy 种子执行，
--   故 id 23/25/31 采用「UPDATE（存量卷命中）+ WHERE NOT EXISTS 兜底 INSERT（新库落冻结行）」
--   双语句形态，两序同终态；V605/V702 的 INSERT ... WHERE NOT EXISTS 因行已在而自然跳过。
-- id 32–40 = 新登记（全局递增，裁决 1）；id 35 仅登记无发布点（id 27 先例，禁发布）；
--   id 39 为延迟队列回调内部事件（fy.delay 档位 appointment-timeout 到期经 DLX 以本路由键回 fy.topic）。
-- 幂等形态：INSERT ... WHERE NOT EXISTS（V5/V105/V605/V702 先例）。

-- ---------------------------------------------------------------- 1. 占位行冻结（id 23/25/31，CF-5 双向评审声明随 PR）
UPDATE integration.event_registry
SET payload_desc = '门诊申请单开立（CF-5 冻结，PR-5 实装，V605 占位升级）：orderId/visitId/patientId/lines[]{itemCode,quantity}（字段名与 api OrderCreatedPayload record 组件逐字同源；orderId=order_no 业务号，billing sourceRef 直取，quantity 为 DECIMAL string）；M13(本仓 billing) 生成 PENDING 费用（PR-3 已订阅，按 orderId 取 sourceRef）；禁敏感明文'
WHERE id = 23 AND event_type = 'outpatient.order.created';

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 23, 'outpatient.order.created', 'outpatient',
       '门诊申请单开立（CF-5 冻结，PR-5 实装，V605 占位升级）：orderId/visitId/patientId/lines[]{itemCode,quantity}（字段名与 api OrderCreatedPayload record 组件逐字同源；orderId=order_no 业务号，billing sourceRef 直取，quantity 为 DECIMAL string）；M13(本仓 billing) 生成 PENDING 费用（PR-3 已订阅，按 orderId 取 sourceRef）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.order.created');

UPDATE integration.event_registry
SET payload_desc = '门诊缴费放行扇出（CF-5 冻结，PR-5 实装）：settlementId/settleNo/patientId/visitId/orderNos[]/rxNos[]/greenChannelFlag（与 api OrderChargedPayload record 组件逐字同源；orderNos/rxNos 为本次结算覆盖的申请单号与处方号精确清单）；M06(本仓 pharmacy) 处方转待调配（PR-5 起按 rxNos 单据精确放行，裁决 4）、M07/M08/M05 执行放行随 P3；禁敏感明文'
WHERE id = 25 AND event_type = 'outpatient.order.charged';

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 25, 'outpatient.order.charged', 'outpatient',
       '门诊缴费放行扇出（CF-5 冻结，PR-5 实装）：settlementId/settleNo/patientId/visitId/orderNos[]/rxNos[]/greenChannelFlag（与 api OrderChargedPayload record 组件逐字同源；orderNos/rxNos 为本次结算覆盖的申请单号与处方号精确清单）；M06(本仓 pharmacy) 处方转待调配（PR-5 起按 rxNos 单据精确放行，裁决 4）、M07/M08/M05 执行放行随 P3；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.order.charged');

UPDATE integration.event_registry
SET payload_desc = '门诊退费逆向扇出（CF-5 冻结，PR-5 实装）：orderNo/patientId/visitId/rxNos[]/reason（与 api OrderCancelledPayload record 组件逐字同源；rxNos 为经 billing SettlementQueryPort 按 settlementId 反查的处方号清单）；本模块承担未发药作废与退药单终态确认（06-pharmacy §8 B-3 单向链）：M06 未发药处方作废/已退药单据收敛（PR-5 回切实装）、M07/M08/M05 随 P3；禁敏感明文'
WHERE id = 31 AND event_type = 'outpatient.order.cancelled';

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 31, 'outpatient.order.cancelled', 'outpatient',
       '门诊退费逆向扇出（CF-5 冻结，PR-5 实装）：orderNo/patientId/visitId/rxNos[]/reason（与 api OrderCancelledPayload record 组件逐字同源；rxNos 为经 billing SettlementQueryPort 按 settlementId 反查的处方号清单）；本模块承担未发药作废与退药单终态确认（06-pharmacy §8 B-3 单向链）：M06 未发药处方作废/已退药单据收敛（PR-5 回切实装）、M07/M08/M05 随 P3；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.order.cancelled');

-- ---------------------------------------------------------------- 2. 新登记（id 32–40）
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 32, 'outpatient.visit.registered', 'outpatient',
       '门诊挂号/取号成功（PR-5 实装）：visitId/patientId/visitType/deptCode/doctorId（与 api VisitRegisteredPayload record 组件逐字同源；visitId=O+yyyyMMdd+5 位流水，M03 唯一签发，CF-3）；M13(本仓 billing) 医保就诊登记依据（订阅随 P3）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.visit.registered');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 33, 'outpatient.visit.finished', 'outpatient',
       '门诊诊毕（PR-5 实装）：visitId/patientId/disposition/finishOperator（与 api VisitFinishedPayload record 组件逐字同源；disposition=离院去向国标代码 1~7/9，调研依据 1）；M09 信息页/病案与 M19 工作量统计取数依据（订阅随 P4）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.visit.finished');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 34, 'outpatient.visit.cancelled', 'outpatient',
       '门诊退号回滚（PR-5 实装）：visitId/patientId/reason（与 api VisitCancelledPayload record 组件逐字同源）；M13 就诊登记撤销与 M18 患者端同步依据（订阅随 P3/P2）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.visit.cancelled');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 35, 'outpatient.visit.no-show', 'outpatient',
       '门诊爽约（仅登记，无发布点——发布点随当日爽约判定任务交付，id 27 先例，禁发布）：预期字段 visitId/patientId；号源释放与信用限约在 appointment 链承载（visit.no-show 为 visit 维度声明态）',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.visit.no-show');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 36, 'outpatient.appointment.booked', 'outpatient',
       '预约成功（PR-5 实装）：apptNo/patientId/schedDate/session/deptCode/apptType/channel（与 api AppointmentBookedPayload record 组件逐字同源；channel=窗口/自助机/公众号/小程序/诊间/外联，P1 实装窗口/portal 两渠道）；M18 患者端订单同步与 M19 统计依据（订阅随 P2）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.appointment.booked');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 37, 'outpatient.appointment.cancelled', 'outpatient',
       '退号完成（PR-5 实装）：apptNo/patientId/reason/feeRefundTriggered（与 api AppointmentCancelledPayload record 组件逐字同源；发布时点=号源已回池、退费联动已触发，feeRefundTriggered 区分支付时限内免退费路径）；M18 患者端订单同步依据（订阅随 P2）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.appointment.cancelled');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 38, 'outpatient.appointment.rescheduled', 'outpatient',
       '改期完成（PR-5 实装）：oldApptNo/newApptNo/patientId/newSchedDate/newSlotStart（与 api AppointmentRescheduledPayload record 组件逐字同源；reschedule_of 链，号源先占新后退旧防两头空）；M18 患者端同步依据（订阅随 P2）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.appointment.rescheduled');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 39, 'outpatient.appointment.timeout', 'outpatient',
       '预约支付超时回调（PR-5 实装，自产自消内部事件）：apptNo/patientId/poolId（与 api AppointmentTimeoutPayload record 组件逐字同源；fy.delay 档位 appointment-timeout 到期经 DLX 以本路由键回 fy.topic，outpatient 自消费置 NO_SHOW+回池+信用记录；超时与支付成功并发以预约单状态 CAS 先到先得）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.appointment.timeout');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 40, 'outpatient.schedule.stopped', 'outpatient',
       '停诊广播（PR-5 实装）：scheduleId/schedDate/deptCode/doctorId/stopReason（与 api ScheduleStoppedPayload record 组件逐字同源；已约患者改期/退费联动依据，通知触达随 M01 通知中心）；M18/M19 消费随 P2（Spec §7-M03 流程 4）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.schedule.stopped');
```

- [ ] **Step 2: 错误码、消息常量与发布设施**

创建 `backend/fuyun-outpatient/src/main/java/com/fuyun/outpatient/api/OutpatientErrorCode.java`（Global Constraints 错误码全集 OP-1001~OP-1019 逐条落枚举，实现 common `ErrorCode` 契约，格式照 `SystemErrorCode`：枚举名("OP-10xx") + getCode()）；`api/package-info.java` 带 `@NamedInterface`（照 pharmacy/api/package-info.java 同型）。

创建 11 payload record（组件清单见 Interfaces，逐 record javadoc 注明 event_registry id、冻结契约与消费方；格式照 `OrderChargedPayload` 的 billing SettlementCompletedPayload 先例——`@param` 全组件注释 + 禁敏感明文注记）。

创建 `backend/fuyun-outpatient/src/main/java/com/fuyun/outpatient/constants/OutpatientMessagingConstants.java`：

```java
package com.fuyun.outpatient.constants;

/**
 * 门诊域消息治理常量：事件字面量与 V204 种子行、api payload record 三方一致，
 * 任何一侧变更属 CF-3/CF-5 契约变更（双向评审）。
 */
public final class OutpatientMessagingConstants {

    /** 模块域标识（信封 producer / 队列命名 / 幂等 consumerModule） */
    public static final String MODULE = "outpatient";

    /** 发布事件：申请单开立（id 23，携非药品计费行，CF-5） */
    public static final String EVENT_ORDER_CREATED = "outpatient.order.created";

    /** 发布事件：缴费放行扇出（id 25，单据精确放行清单，CF-5） */
    public static final String EVENT_ORDER_CHARGED = "outpatient.order.charged";

    /** 发布事件：退费逆向扇出（id 31，终态确认，CF-5） */
    public static final String EVENT_ORDER_CANCELLED = "outpatient.order.cancelled";

    /** 发布事件：挂号/取号成功（id 32，CF-3 visit_id 签发锚） */
    public static final String EVENT_VISIT_REGISTERED = "outpatient.visit.registered";

    /** 发布事件：诊毕（id 33） */
    public static final String EVENT_VISIT_FINISHED = "outpatient.visit.finished";

    /** 发布事件：退号回滚（id 34） */
    public static final String EVENT_VISIT_CANCELLED = "outpatient.visit.cancelled";

    /** 登记事件：门诊爽约（id 35——发布点随当日爽约判定任务，禁引用于任何发布点） */
    public static final String EVENT_REGISTRY_VISIT_NO_SHOW = "outpatient.visit.no-show";

    /** 发布事件：预约成功（id 36） */
    public static final String EVENT_APPOINTMENT_BOOKED = "outpatient.appointment.booked";

    /** 发布事件：退号完成（id 37） */
    public static final String EVENT_APPOINTMENT_CANCELLED = "outpatient.appointment.cancelled";

    /** 发布事件：改期完成（id 38） */
    public static final String EVENT_APPOINTMENT_RESCHEDULED = "outpatient.appointment.rescheduled";

    /** 发布事件：预约支付超时回调（id 39，fy.delay 档位回调，自产自消） */
    public static final String EVENT_APPOINTMENT_TIMEOUT = "outpatient.appointment.timeout";

    /** 发布事件：停诊广播（id 40） */
    public static final String EVENT_SCHEDULE_STOPPED = "outpatient.schedule.stopped";

    /** 订阅事件：PENDING 费用生成回执（id 17 既有；申请单 CREATED→PENDING_FEE 与 visit 待缴费推进） */
    public static final String EVENT_SUB_BILLING_FEE_CREATED = "billing.fee.created";

    /** 订阅事件：结算完成（id 19 既有；单据放行与 order.charged 扇出唯一权威） */
    public static final String EVENT_SUB_BILLING_SETTLEMENT_COMPLETED = "billing.settlement.completed";

    /** 订阅事件：退费审批通过（id 20 既有；退号终态与开单退费逆向，以 M13 回执为退费权威） */
    public static final String EVENT_SUB_BILLING_REFUND_APPROVED = "billing.refund.approved";

    /** 订阅事件：处方作废回流（id 26 既有；处方引用行 CANCELLED 联动，Spec :119 R2-10） */
    public static final String EVENT_SUB_PHARMACY_PRESCRIPTION_CANCELLED = "pharmacy.prescription.cancelled";

    /** 订阅事件：门诊发药完成（id 28 既有；处方引用行「已发药」聚合） */
    public static final String EVENT_SUB_PHARMACY_DISPENSE_COMPLETED = "pharmacy.dispense.completed";

    /** 订阅事件：退药受理完成（id 29 既有；引用行聚合与退费联动依据） */
    public static final String EVENT_SUB_PHARMACY_DISPENSE_RETURNED = "pharmacy.dispense.returned";

    /**
     * 订阅事件全集（队列声明与监听器同源）：Task 3 交付时为空数组（仅发布面），
     * 随消费任务逐批追加——Task 5 补 appointment.timeout、Task 6 补 refund.approved、
     * Task 8 补 fee.created、Task 10 补 settlement.completed/prescription.cancelled/
     * dispense.completed/returned；每批追加须与该任务监听器同任务落改（先登记后订阅红线）。
     * prescription.created 不订阅：M03 经 PrescriptionOpenPort 同步登记引用（事件订阅为重复面，
     * 偏差注记），登记一致性由 Task 12 IT 断言 ext_ref 在位承载。
     */
    public static final String[] SUBSCRIBED_EVENT_TYPES = {};

    /** 私有构造器（A.2-6） */
    private OutpatientMessagingConstants() {}
}
```

创建 `internal/OutpatientDomainEvent.java` 与 `internal/OutpatientEventPublisher.java`——照抄 `PharmacyDomainEvent`/`PharmacyEventPublisher`（PR-4 Task 4 Step 2 全文形态，2026-09-20 实测锚 `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/internal/PharmacyEventPublisher.java`），仅三处替换：包名、类名、`@Qualifier("outpatientEventSender")`。javadoc 注明「门诊域 MQ 事件发布器（CF-3/CF-5 发布事件唯一发送执行点）」。

- [ ] **Step 3: 消息装配类与 app 接线**

创建 `config/OutpatientMessagingConfig.java`——照抄 `PharmacyMessagingConfig`（PR-4 Task 4 Step 3 全文形态，实测锚 `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/config/PharmacyMessagingConfig.java`），替换：包名、三 Bean 名（outpatientEventSender/outpatientConsumerSupport/outpatientConsumerQueues）、常量类引用、javadoc「M03 消息装配」。追加延迟档位声明 Bean（仅本模块有延迟面）：

```java
    /**
     * 预约支付超时延迟档位（Spec §8 M20：delay.appointment-timeout；单档位、TTL=支付时限）：
     * 到期经 DLX 以 outpatient.appointment.timeout 路由键回 fy.topic，由本模块 timeout 监听器消费。
     * 声明幂等（RabbitAdmin）；TTL 与 OutpatientProperties.appointmentTimeout() 同源（Task 5 接线）。
     *
     * @param governance  消息治理构件，非空
     * @param properties  门诊域参数，非空
     * @return 声明集合（延迟队列 + 绑定）
     */
    @Bean
    public Declarables appointmentTimeoutDelayQueue(MessagingGovernance governance,
            com.fuyun.outpatient.properties.OutpatientProperties properties) {
        return governance.declareDelayQueue(new com.fuyun.integration.api.DelayQueueSpec(
                "appointment-timeout", properties.appointmentTimeout(),
                OutpatientMessagingConstants.EVENT_APPOINTMENT_TIMEOUT));
    }
```

（`OutpatientProperties` 随本任务落最小骨架：`@ConfigurationProperties("fuyun.outpatient")` record `OutpatientProperties(Duration appointmentTimeout, int onlineCancelBeforeDays, int noShowWindowDays, int noShowThreshold, int restrictDays)`——缺省 15m/1/90/3/90，装配归 OutpatientMessagingConfig @EnableConfigurationProperties；Task 5/6 逐字段消费。）

`OutpatientConfig` 整类替换 @Import：`@Import({OutpatientWebConfig.class, OutpatientMessagingConfig.class})`；`OutpatientWebConfig` 本任务先落空壳（`@Configuration public class OutpatientWebConfig {}`，仅 javadoc 注明「M03 门诊域 Web/服务装配集中点，Task 4 起逐任务追加 @Import 注册面——PharmacyWebConfig 同款」），Task 4 起逐任务追加 @Import 注册面。

- [ ] **Step 4: MessagingGovernanceIT 总行断言 31→40（P-4，与种子同任务）**

`backend/fuyun-app/src/test/java/com/fuyun/app/MessagingGovernanceIT.java` 的 `seedRegistryRowsAreFrozenAndActive` 用例（:299-319 实测段）整块替换为：

```java
    @Test
    @Order(1)
    @DisplayName("冻结登记断言：event_registry 四十条种子行齐全且全部 ACTIVE，system.dict.published 生产方为 system")
    void seedRegistryRowsAreFrozenAndActive() {
        // 总量口径：V5 七条 + V403 iot 一条 + V105 患者域八条（id 9–16）+ V605 billing 域八条
        // （id 17–24）+ V702 pharmacy 域七条（id 25–31）+ V204 outpatient 域九条（id 32–40；
        //   id 23/25/31 系 V605/V702 占位行经 V204 UPDATE 冻结，不增行）
        Integer totalRows =
                jdbcTemplate.queryForObject("SELECT count(*) FROM integration.event_registry", Integer.class);
        assertThat(totalRows).isEqualTo(40);
        Integer activeRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integration.event_registry WHERE status = ?",
                Integer.class,
                MessagingConstants.REGISTRY_STATUS_ACTIVE);
        assertThat(activeRows).isEqualTo(40);
        String producer = jdbcTemplate.queryForObject(
                "SELECT producer_module FROM integration.event_registry WHERE event_type = ?",
                String.class,
                EVENT_TYPE);
        assertThat(producer).isEqualTo("system");
    }
```

- [ ] **Step 5: 契约测试（三方一致可执行锚）**

创建 `backend/fuyun-outpatient/src/test/java/com/fuyun/outpatient/api/OutpatientEventContractTest.java`——照抄 `PharmacyEventContractTest`（PR-4 Task 4 Step 6 全文形态，实测锚 `backend/fuyun-pharmacy/src/test/java/com/fuyun/pharmacy/api/PharmacyEventContractTest.java`），差异点：

- `SEED_SQL` 读 `/db/migration/outpatient/V204__seed_outpatient_event_registry.sql`。
- 用例 1 `registryDescriptionsMatchPublishedPayloadRecords`：断言 id 23/25/31/32/33/34/36/37/38/39/40 十一行——`assertDescContains` 逐事件对 record 组件（含 lines 行组件两事件）；`segmentOf` 对 id 23/25/31 三事件走 UPDATE 形态回溯（锚点 `SET payload_desc =` 至 `WHERE id = 23|25|31`，照 pharmacy id 24 同型；判定条件改「eventType 属于三冻结面集合」）。
- 用例 2 `placeholderRowsAreRegisteredWithFreezeNotes`：`assertThat(SEED_SQL).contains("'outpatient.visit.no-show'").contains("无发布点")` 与 id 39 的 `自产自消` 注记在位。
- 用例 3 `constantsLiteralsMatchRegistryRows`：V204 登记的十一字面量（ORDER_CREATED/CHARGED/CANCELLED/VISIT_REGISTERED/FINISHED/CANCELLED/NO_SHOW/APPOINTMENT_BOOKED/CANCELLED/RESCHEDULED/TIMEOUT/SCHEDULE_STOPPED——12 常量中 NO_SHOW 亦在 V204 在位）须在 SEED_SQL 原文在位；`billing.`/`pharmacy.` 六条订阅字面量断言 `doesNotContain`（登记于 V605/V702，禁 V204 重复登记）。

- [ ] **Step 6: 单测与装配冒烟**

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-outpatient -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='OutpatientEventContractTest'
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-app -am verify -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test='MessagingGovernanceIT,SmokeStackIT'
```

Expected: 两侧 BUILD SUCCESS——契约测试 3 例全绿；MessagingGovernanceIT 40/40 断言通过（V204 在 IT 库随 Flyway 应用，新库序下 V204 先插冻结行、V605/V702 跳过）；SmokeStackIT 全上下文启动通过（outpatient 装配链在位：outpatientEventSender/outpatientConsumerSupport 多候选定绑成立——@Qualifier 缺失即启动抛 NoUniqueBeanDefinitionException 被该 IT 捕获；延迟档位队列声明在 V204 id 39 已登记前提下通过注册校验）。

- [ ] **Step 7: 提交**

```bash
git add backend/fuyun-outpatient backend/fuyun-app
git commit -m "feat(outpatient): CF-3/CF-5 事件契约种子与消息装配——V204 冻结+id32-40 登记

- id 23/25/31 载荷 desc 冻结（UPDATE+兜底 INSERT 双形态保两序同终态）
- OutpatientMessagingConstants/发布器/三 Bean @Qualifier 定绑；延迟档位 appointment-timeout
- 11 payload record + 契约测试三方一致可执行锚；门禁断言 31→40"
```

---

### Task 4: 号源池域（V200 三表 + V705 字典种子 + 排班模板/放号生成/停诊/加号/余量查询 + 池行 CAS 与 Redis 预扣）

**Files:**
- Create: `backend/fuyun-outpatient/src/main/resources/db/migration/outpatient/V200__create_schedule_and_pool.sql`
- Create: `backend/fuyun-system/src/main/resources/db/migration/system/V705__seed_outpatient_dict.sql`
- Create: `enums/ApptType.java`、`enums/SessionType.java`、`enums/ScheduleStatus.java`、`enums/PoolStatus.java`（4 枚举，enums/ 包 D-6 裁决）
- Create: `entity/ScheduleTemplate.java`、`entity/Schedule.java`、`entity/ApptNumberPool.java` + 三 mapper
- Create: `mapper/ApptNumberPoolMapper.java` 内两条 CAS 注解 SQL
- Create: `dto/ScheduleTemplateSaveRequest.java`、`dto/ScheduleGenerateRequest.java`、`vo/ScheduleTemplateVO.java`、`vo/ScheduleVO.java`、`vo/NumberPoolVO.java`
- Create: `service/IScheduleService.java`、`service/impl/ScheduleServiceImpl.java`
- Create: `controller/ScheduleController.java`
- Create: `properties/OutpatientProperties.java`（Task 3 骨架补字段 javadoc，本任务消费 onlineCancelBeforeDays 前仅 appointmentTimeout 在位）
- Create: `resources/lua/pool_deduct.lua`、`resources/lua/pool_release.lua`
- Create: `cache/PoolRedisGate.java`
- Modify: `config/OutpatientWebConfig.java`（@Import 增 impl/controller/mapper 面）
- Create: `src/test/java/com/fuyun/outpatient/service/impl/ScheduleServiceImplTest.java`
- Create: `src/test/java/com/fuyun/outpatient/cache/PoolRedisGateTest.java`

**Interfaces:**
- Consumes: system 字典读接口 `GET /api/v1/system/dicts/{type}`（前端取值源，V607 先例）；common StringRedisTemplate + DefaultRedisScript。
- Produces（Task 5/7/12 依赖的冻结面）:
  - V200 DDL：`schedule_template(id BIGINT PK / dept_code VARCHAR(64) NOT NULL / doctor_id VARCHAR(64) NOT NULL / eff_from DATE NOT NULL / eff_to DATE NULL / week_pattern VARCHAR(7) NOT NULL（7 位 0/1 串，位序周一~周日）/ session VARCHAR(16) NOT NULL（MORNING/AFTERNOON/EVENING）/ appt_type VARCHAR(32) NOT NULL（普通/专家/专病/急诊/复诊词表=字典 outpatient.appt-type item_code）/ slot_start TIME NOT NULL / slot_end TIME NOT NULL / slot_quota INT NOT NULL（该时段号总数）/ room VARCHAR(64) NULL / release_days INT NOT NULL DEFAULT 7（T+N 放号周期）/ release_time TIME NOT NULL DEFAULT '07:00' / status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' / 审计五列）`；`schedule(id / template_id BIGINT NOT NULL / sched_date DATE NOT NULL / session VARCHAR(16) NOT NULL / dept_code VARCHAR(64) NOT NULL / doctor_id VARCHAR(64) NOT NULL / appt_type VARCHAR(32) NOT NULL / total_quota INT NOT NULL / used_quota INT NOT NULL DEFAULT 0 / room VARCHAR(64) NULL / status VARCHAR(16) NOT NULL DEFAULT 'NORMAL'（NORMAL/STOPPED）/ stop_reason VARCHAR(255) NULL / 审计五列；uk_schedule UNIQUE(template_id, sched_date, session) WHERE deleted=0)`；`appt_number_pool(id / schedule_id BIGINT NOT NULL / appt_type VARCHAR(32) NOT NULL / slot_start TIME NOT NULL / slot_end TIME NOT NULL / total_quota INT NOT NULL / channel_quota VARCHAR(255) NOT NULL DEFAULT '{"PORTAL":60,"WINDOW":30,"KIOSK":5,"RESERVED":5}'（线上/窗口/自助/预留 JSON 配额百分比，P1 校验仅 PORTAL/WINDOW 通道计数）/ used_count INT NOT NULL DEFAULT 0 / extra_used INT NOT NULL DEFAULT 0 / version INT NOT NULL DEFAULT 0 / status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'（ACTIVE/STOPPED/EXPIRED）/ 审计五列；uk_pool UNIQUE(schedule_id, appt_type, slot_start) WHERE deleted=0)`。
  - 池行 CAS 双 SQL（ApptNumberPoolMapper，注解 SQL 显式补 deleted = 0）:
    - `casOccupy`: `UPDATE outpatient.appt_number_pool SET used_count = used_count + 1, version = version + 1, updated_by = 'system', updated_at = now() WHERE id = #{poolId} AND deleted = 0 AND status = 'ACTIVE' AND used_count < total_quota AND version = #{version}`。
    - `casRelease`: `UPDATE outpatient.appt_number_pool SET used_count = used_count - 1, version = version + 1, updated_by = 'system', updated_at = now() WHERE id = #{poolId} AND deleted = 0 AND status = 'ACTIVE' AND used_count > 0`。
  - Redis Lua 双脚本（`fy:outpatient:pool:{poolId}` 键、TTL=sched_date 次日 02:00）:
    - `pool_deduct.lua`（KEYS[1]=池键；ARGV[1]=扣减量 1、ARGV[2]=池总量、ARGV[3]=TTL 秒）：`local remain = tonumber(redis.call('GET', KEYS[1]))`→键缺失（放号生成时预热为 total_quota）返回 -2；`remain <= 0` 返回 -1；否则 `DECRBY`+`EXPIRE` 返回 remain-1。
    - `pool_release.lua`：键缺失返回 -1；`INCRBY` 后越界封顶 ARGV[2]（对账防漂移）+`EXPIRE`，返回封顶后余量。
  - REST 面（Spec :150）：`GET/POST/PUT /schedule-templates`、`POST /schedules/generate`（body `{endDate, days}` 按 T+N 规则批量生成）、`GET /schedules?deptCode=&dateFrom=&dateTo=`、`POST /schedules/{id}/stop`（body `{reason}`，整池 STOPPED+发布 schedule.stopped）、`POST /schedules/{id}/resume`、`GET /number-pools/available?deptCode=&date=&apptType=`（可约号源查询，供全渠道与 M18）、`POST /number-pools/{id}/extra-quota`（body `{count}`，加号 total_quota 不变、加号占用走 extra_used，Task 5 挂号时消耗）。
  - `PoolRedisGate`（cache/）：`int deduct(long poolId, long total, java.time.Duration ttl)`（返回扣减后余量；-1 余量不足；-2 键缺失）、`int release(long poolId, long total, java.time.Duration ttl)`、`void prime(long poolId, long total, java.time.Duration ttl)`（放号生成预热 SET total+TTL）——Redis 异常上抛由调用方降级（Task 5）。

- [ ] **Step 1: 写测试（先红后绿）**

`ScheduleServiceImplTest` 用例全集（断言值冻结）：

1. `generateExpandsTemplatesByWeekPatternToSchedulesAndPools`——模板 week_pattern=`1100000`、slot 08:00-08:30 slot_quota=4：对 endDate 区间内周一/周二各生成 1 schedule + 1 pool，pool.total_quota=4、channel_quota JSON 逐字回读；池键 `prime` 被调 2 次（ArgumentCaptor 断言 poolId 与 ttl）。
2. `generateSkipsExistingScheduleDateIdempotently`——uk 命中日期跳过不抛（warn 留痕），返回生成计数 0。
3. `stopMarksScheduleStoppedAndInvalidatesPools`——schedule CAS NORMAL→STOPPED 成功 + 同事务池行批量 `UPDATE ... SET status='STOPPED'` + 发布 ScheduleStoppedEvent（scheduleId/schedDate/deptCode/doctorId/stopReason 五组件断言）；`stopRejectsAlreadyStoppedSchedule`——CAS 0 行抛 OP-1004。
4. `resumeRestoresPoolsOnlyWhenDateInFuture`——sched_date=今日+1 恢复 NORMAL+池 ACTIVE；sched_date=今日-1 拒绝 OP-1004（过期排班不可恢复）。
5. `extraQuotaIncrementsPoolQuotaAndCapsAtLimit`——加号 count=5：`UPDATE ... SET total_quota = total_quota + 5` 生效（注解 SQL `casAddExtraQuota`）；count>50 拒 OP-1019。
6. `availablePoolsFiltersStoppedAndExhausted`——余量查询仅返回 ACTIVE 且 used_count<total_quota 行，按 slot_start 升序。

`PoolRedisGateTest` 用例全集：

1. `deductReturnsRemainingAfterAtomicDecr`——脚本执行返回 3（total=4）。
2. `deductReturnsMinusOneWhenPoolEmpty`——余量 0：返回 -1。
3. `deductReturnsMinusTwoWhenKeyMissing`——键缺失：返回 -2（调用方降级信号）。
4. `releaseCapsAtTotalQuota`——余量 total-1 再回补 2：返回 total（封顶断言）。
5. `deductAlwaysAppliesTtl`——执行后 `EXPIRE` 生效（剩余 TTL>0 断言）。

Run（预期红）：编译错误（IScheduleService/PoolRedisGate 不存在）+ `mvn -pl fuyun-outpatient -am test -Dtest='ScheduleServiceImplTest,PoolRedisGateTest'`。

- [ ] **Step 2: V200 迁移 + V705 字典种子**

V200 按 Interfaces DDL 全文落文件（表注释全中文、枚举词表入列注释、部分唯一索引带 `WHERE deleted = 0`、各表挂 `fuyun_set_updated_at` 触发器——照 V602 表结构注释形态）。

创建 `V705__seed_outpatient_dict.sql`（V607 同构形态，字典类型 id 3–5、dict_version id 3–5、dict_item id 26–44）：

```sql
-- V705：门诊三类字典预置（03 Spec §8「字典（号别/就诊类型/离院去向）引用 M01 字典 code，不自建副本」；
-- recon 未列，属计划补充（待批 2）——前端下拉与 visit_type/disposition 词表校验取值源）。
-- 落位合法性：system 通用段 V705>基线最大 V703（同 V704）；条目编码=国标/行业标准清单：
--   visit_type 对齐信息页就诊类型代码（调研依据 1：1 急诊/2 普通/3 特需/4 互联网诊疗/5 MDT/9 其他）；
--   disposition 对齐急诊患者去向八类代码（1 医嘱离院/2 医嘱转院/3 医嘱转社区/4 非医嘱离院/5 死亡/
--   6 急诊留观/7 急诊转住院/9 其他）；appt_type=号别五类（普通/专家/专病/急诊/复诊）。
-- PUBLISHED 直落、不发 system.dict.published（V607 同口径注记）。
-- 形态同构 V607：INSERT ... WHERE NOT EXISTS 幂等（dict_item 按 dict_version_id+item_code 判重）；
--   占用 dict_type 3–5、dict_version 3–5、dict_item 26–44（V607 已占 type 1–2/version 1–2/item 1–25，
--   小整数续用）；全部 25 条 INSERT 逐条全文落死（code/name/sort 已冻结的机械展开，无压缩面）。

-- ---------------------------------------------------------------- 1. 字典类型（三类）
INSERT INTO system.dict_type (id, type_code, type_name, national_standard, remark)
SELECT 3, 'outpatient.visit-type', '就诊类型', true, 'M03 门（急）诊信息页就诊类型代码（国卫办医政发〔2024〕16 号）'
WHERE NOT EXISTS (SELECT 1 FROM system.dict_type WHERE type_code = 'outpatient.visit-type' AND deleted = 0);

INSERT INTO system.dict_type (id, type_code, type_name, national_standard, remark)
SELECT 4, 'outpatient.disposition', '离院去向', true, 'M03 离院去向代码（急诊患者去向八类，含其他）'
WHERE NOT EXISTS (SELECT 1 FROM system.dict_type WHERE type_code = 'outpatient.disposition' AND deleted = 0);

INSERT INTO system.dict_type (id, type_code, type_name, national_standard, remark)
SELECT 5, 'outpatient.appt-type', '号别', false, '排班号别五类，可扩充'
WHERE NOT EXISTS (SELECT 1 FROM system.dict_type WHERE type_code = 'outpatient.appt-type' AND deleted = 0);

-- ---------------------------------------------------------------- 2. 字典版本（各 v1，直接 PUBLISHED）
INSERT INTO system.dict_version (id, dict_type_id, version, status, effective_at, published_at)
SELECT 3, 3, 1, 'PUBLISHED', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM system.dict_version WHERE dict_type_id = 3 AND version = 1 AND deleted = 0);

INSERT INTO system.dict_version (id, dict_type_id, version, status, effective_at, published_at)
SELECT 4, 4, 1, 'PUBLISHED', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM system.dict_version WHERE dict_type_id = 4 AND version = 1 AND deleted = 0);

INSERT INTO system.dict_version (id, dict_type_id, version, status, effective_at, published_at)
SELECT 5, 5, 1, 'PUBLISHED', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM system.dict_version WHERE dict_type_id = 5 AND version = 1 AND deleted = 0);

-- ---------------------------------------------------------------- 3. 就诊类型条目（六条，dict_version_id=3）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 26, 3, 'EMERGENCY', '急诊', 1
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 3 AND item_code = 'EMERGENCY' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 27, 3, 'GENERAL', '普通门诊', 2
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 3 AND item_code = 'GENERAL' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 28, 3, 'SPECIAL', '特需门诊', 3
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 3 AND item_code = 'SPECIAL' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 29, 3, 'INTERNET', '互联网诊疗', 4
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 3 AND item_code = 'INTERNET' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 30, 3, 'MDT', '多学科诊疗', 5
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 3 AND item_code = 'MDT' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 31, 3, 'OTHER', '其他', 6
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 3 AND item_code = 'OTHER' AND deleted = 0);

-- ---------------------------------------------------------------- 4. 离院去向条目（八条，dict_version_id=4）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 32, 4, 'DISCHARGE_HOME', '医嘱离院', 1
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'DISCHARGE_HOME' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 33, 4, 'TRANSFER_HOSPITAL', '医嘱转院', 2
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'TRANSFER_HOSPITAL' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 34, 4, 'TRANSFER_COMMUNITY', '医嘱转社区', 3
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'TRANSFER_COMMUNITY' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 35, 4, 'NON_MEDICAL_LEAVE', '非医嘱离院', 4
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'NON_MEDICAL_LEAVE' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 36, 4, 'DEATH', '死亡', 5
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'DEATH' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 37, 4, 'OBSERVATION', '急诊留观', 6
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'OBSERVATION' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 38, 4, 'TRANSFER_INPATIENT', '急诊转住院', 7
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'TRANSFER_INPATIENT' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 39, 4, 'OTHER', '其他', 8
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'OTHER' AND deleted = 0);

-- ---------------------------------------------------------------- 5. 号别条目（五条，dict_version_id=5）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 40, 5, 'GENERAL', '普通', 1
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 5 AND item_code = 'GENERAL' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 41, 5, 'EXPERT', '专家', 2
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 5 AND item_code = 'EXPERT' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 42, 5, 'SPECIAL_DISEASE', '专病', 3
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 5 AND item_code = 'SPECIAL_DISEASE' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 43, 5, 'EMERGENCY', '急诊', 4
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 5 AND item_code = 'EMERGENCY' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 44, 5, 'REVISIT', '复诊', 5
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 5 AND item_code = 'REVISIT' AND deleted = 0);
```

- [ ] **Step 3: 实现与发布点回挂**

`ScheduleServiceImpl`（LINE=1.00 生效面）：构造器注入三 mapper + `ApplicationEventPublisher events` + `PoolRedisGate poolRedisGate`；核心逻辑逐法落码——`generate`（模板 week_pattern 位串 × 日期区间展开，幂等跳过 uk 冲突，池键预热 prime）；`stop`（schedule 注解 CAS `casStatus(id,"NORMAL","STOPPED")` + 池行批量置 STOPPED + 事务内 `events.publishEvent(new OutpatientDomainEvent(OutpatientMessagingConstants.EVENT_SCHEDULE_STOPPED, new ScheduleStoppedPayload(...)))`）；`resume`/`extraQuota`/`listTemplates`/`listSchedules`/`availablePools`。**禁出现任何金额字段**。

- [ ] **Step 4: 跑绿 + 提交**

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-system,fuyun-outpatient -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ScheduleServiceImplTest,PoolRedisGateTest'
python scripts/check-migration-governance.py
```

Expected: BUILD SUCCESS（11 用例绿）+ 守卫 38 文件全绿。

```bash
git add backend/fuyun-system/src/main/resources/db/migration/system/V705__seed_outpatient_dict.sql backend/fuyun-outpatient
git commit -m "feat(outpatient): 号源池域——V200 三表+V705 字典种子+排班/放号/停诊/加号

- 池行 CAS（version 乐观锁+余量谓词）与 Redis Lua 预扣/回补双道闸第一道
- schedule.stopped 事务内发布（AFTER_COMMIT 出线）；加号额度 extra_used 口径
- 号源余量对外查询 API（供全渠道与 M18 复用）"
```

---

### Task 5: 预约挂号与当日挂号（V201 四表 + visit_id 签发 + Redis 预扣降级 + 支付时限延迟释放 + portal 匿名通道）

**Files:**
- Create: `backend/fuyun-outpatient/src/main/resources/db/migration/outpatient/V201__create_appointment_and_visit.sql`
- Create: `enums/ApptChannel.java`、`enums/ApptStatus.java`、`enums/VisitStatus.java`、`enums/VisitType.java`、`enums/FeeStatusType.java`（5 枚举）
- Create: `entity/Appointment.java`、`entity/Visit.java`、`entity/ApptCreditRecord.java`、`entity/VisitStatusLog.java` + 四 mapper（AppointmentMapper 含 `casStatus`/`casTake` 注解 SQL；VisitMapper 含 `casStatus` 注解 SQL）
- Create: `service/IVisitIdIssuer.java`、`service/impl/VisitIdIssuerImpl.java`（Redis INCR 当日键）
- Create: `service/IAppointmentService.java`、`service/impl/AppointmentServiceImpl.java`
- Create: `service/impl/OutpatientOngoingVisitQuery.java`（实现 patient/api/OngoingVisitQuery）
- Create: `dto/AppointmentCreateRequest.java`、`dto/PortalAppointmentRequest.java`、`vo/AppointmentVO.java`、`vo/VisitVO.java`
- Create: `controller/AppointmentController.java`、`controller/PortalAppointmentController.java`
- Create: `internal/OutpatientAppointmentTimeoutListener.java`
- Modify: `constants/OutpatientMessagingConstants.java`（SUBSCRIBED_EVENT_TYPES 追加 `EVENT_APPOINTMENT_TIMEOUT`）
- Modify: `config/OutpatientMessagingConfig.java`（@Import 增监听器）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/config/SystemWebConfig.java:72-77`（AUTH_WHITELIST 追加 `"/api/v1/outpatient/portal/**"`）
- Create: `src/test/java/com/fuyun/outpatient/service/impl/VisitIdIssuerImplTest.java`、`AppointmentServiceImplTest.java`

**Interfaces:**
- Consumes: Task 4 池行 CAS/PoolRedisGate；patient `PatientContextResolver.resolve`（冻结拦截 blocked=true→OP-1007、从档归一 resolvedPatientId）；`VisitIdValidator`（结构自检）；common OperatorContextHolder（窗口/诊间渠道操作者）。
- Produces（Task 6/7/8/12 依赖的冻结面）:
  - V201 DDL：`appointment(id / appt_no VARCHAR(32) NOT NULL（AP+yyyyMMdd+6 位流水的业务号）/ patient_id BIGINT NOT NULL / schedule_id BIGINT NOT NULL / pool_id BIGINT NOT NULL / appt_type VARCHAR(32) NOT NULL / sched_date DATE NOT NULL / slot_start TIME NOT NULL / slot_end TIME NOT NULL / channel VARCHAR(16) NOT NULL（WINDOW/KIOSK/PORTAL/MINIAPP/CONSULT/EXTERNAL 词表）/ fee_status VARCHAR(16) NOT NULL DEFAULT 'UNPAID'（UNPAID/PAID/REFUNDED）/ fee_settlement_id BIGINT NULL（挂号费结算单 id 回填，退号退费定位锚）/ pay_deadline TIMESTAMPTZ NULL / reschedule_of VARCHAR(32) NULL（改期链）/ visit_id VARCHAR(14) NULL（取号后回填）/ status VARCHAR(16) NOT NULL DEFAULT 'RESERVED'（RESERVED/TAKEN/CANCELLED/NO_SHOW）/ 审计五列；uk_appt_no UNIQUE(appt_no)；uk_appt_patient UNIQUE(patient_id, sched_date, dept 维度经 schedule join——落 dept_code VARCHAR(64) NOT NULL 冗余列后 UNIQUE(patient_id, sched_date, dept_code) WHERE status IN ('RESERVED','TAKEN'))`；`visit(id BIGINT PK / visit_id VARCHAR(14) NOT NULL / patient_id BIGINT NOT NULL / appt_id BIGINT NULL（当日挂号亦建 appointment）/ dept_code VARCHAR(64) NOT NULL / doctor_id VARCHAR(64) NULL / visit_type VARCHAR(16) NOT NULL（EMERGENCY/GENERAL/SPECIAL/INTERNET/MDT/OTHER）/ is_revisit SMALLINT NOT NULL DEFAULT 0 / triage_level INT NULL（急诊Ⅰ~Ⅳ=1~4）/ insurance_type VARCHAR(32) NULL / green_channel_flag SMALLINT NOT NULL DEFAULT 0（声明列，P1 恒 0）/ registered_at TIMESTAMPTZ NOT NULL / checked_in_at TIMESTAMPTZ NULL / admitted_at TIMESTAMPTZ NULL / finished_at TIMESTAMPTZ NULL / disposition VARCHAR(32) NULL / finish_operator VARCHAR(64) NULL / status VARCHAR(16) NOT NULL DEFAULT 'REGISTERED' / 审计五列；uk_visit_id UNIQUE(visit_id)）`；`appt_credit_record(id / patient_id BIGINT NOT NULL / action VARCHAR(16) NOT NULL（NO_SHOW/TIMEOUT_CANCEL）/ occurred_at TIMESTAMPTZ NOT NULL / window_days INT NOT NULL / restrict_from DATE NULL / restrict_to DATE NULL / release_reason VARCHAR(255) NULL / 审计五列)`；`visit_status_log(id / visit_id VARCHAR(14) NOT NULL / from_status VARCHAR(16) NOT NULL / to_status VARCHAR(16) NOT NULL / reason VARCHAR(255) NULL / operator VARCHAR(64) NOT NULL / occurred_at TIMESTAMPTZ NOT NULL DEFAULT now())`（只增表，红线 5 迁移日志）。
  - appointment CAS 面：`casStatus(id, from, to)`、`casTake(id, visitId)`（`WHERE status='RESERVED' AND (pay_deadline IS NULL OR pay_deadline > now())`——取号超时守卫）。
  - visit CAS 面：`casStatus(visitPk, from, to)`（Task 7/8 消费）。
  - `VisitIdIssuerImpl.issue()`: `String visitId = "O" + yyyyMMdd + String.format("%05d", seq)`——键 `fy:outpatient:visit-seq:{yyyyMMdd}` INCR，返回 1 时 `expire 48h`；签发后 `assert VisitIdValidator.isValid(visitId)` 自检（结构红线，违例即 IllegalStateException）。
  - `AppointmentServiceImpl` 核心签名：
    - `AppointmentVO book(AppointmentCreateRequest req)`（req：`@NotNull Long patientId, @NotNull Long poolId, @NotNull String channel`）——主流程七步锁死：①PatientContextResolver 归一+冻结拦截；②爽约限约拦截（appt_credit_record 窗口内 NO_SHOW 计数 ≥ properties.noShowThreshold() 且 restrict_to ≥ 今日 → OP-1006；窗口=noShowWindowDays 天）；③限购拦截（uk_appt_patient 命中→OP-1005）；④池行重读（ACTIVE+余量谓词，停诊→OP-1004）；⑤Redis 预扣 `poolRedisGate.deduct` 返回 -2/-3 类异常或 -1→降级/失败（-1=OP-1003）；⑥事务内 `appointment INSERT` + `poolMapper.casOccupy`（0 行→重读重试 ≤2 次→OP-1003，并回补 Redis `release`）；⑦RESERVED 且 channel=PORTAL 时写 pay_deadline=now()+appointmentTimeout、投递延迟信封（`rabbitTemplate.convertAndSend("fy.delay", "delay.appointment-timeout", codec envelope of AppointmentTimeoutPayload)`——经 `internal/` 轻封装类 `DelayEnvelopeSender`，禁事务内直发的例外注记：延迟信封入队属「占位登记」动作、消费侧 CAS 定性幂等，javadoc 说明与裁决链）+ 发布 `EVENT_APPOINTMENT_BOOKED`；当日挂号（channel=WINDOW/KIOSK）一步直达 TAKEN：同事务 `visit INSERT`（visit_id 签发）+ `casTake` + 发布 `EVENT_VISIT_REGISTERED`。
    - `VisitVO take(String apptNo)`——预约取号：`casTake` 0 行→状态重读（TAKEN 幂等返回/CANCELLED/NO_SHOW→OP-1009、pay_deadline 过→OP-1008）；同事务签发 visit + 删 `fy:outpatient:pay-hold:{apptNo}` + 发布 visit.registered。
    - `markTimeout(AppointmentTimeoutPayload payload)`（timeout 消费业务）——`casStatus(appt.id,"RESERVED","NO_SHOW")` 1 行才执行：池行 `casRelease`+Redis `release`+删占位键+`appt_credit_record INSERT`（action=NO_SHOW，窗口/阈值取 properties，命中阈值即写 restrict_from=今日、restrict_to=今日+restrictDays）+ warn 日志含 apptNo；0 行=已支付/已取消，info 幂等跳过。
  - portal 两端点（免登录，P-8）：`GET /api/v1/outpatient/portal/schedules?deptCode=&date=`（可约号源聚合）、`POST /api/v1/outpatient/portal/appointments`（body `@NotBlank String credentialType（ID_CARD|VISIT_CARD）, @NotBlank String credentialNo, @NotNull Long poolId`——经标识解析定 patientId，操作者哨兵 `PORTAL`）；portal 退号端点 `POST /api/v1/outpatient/portal/appointments/{no}/cancel` 随 Task 6 与退号四分支统一交付（禁空实现）。
  - `SystemWebConfig.AUTH_WHITELIST` 追加单条目 `"/api/v1/outpatient/portal/**"`（:72-77 实测常量 List.of 追加）。

- [ ] **Step 1: 写测试（先红后绿）**

`VisitIdIssuerImplTest` 用例全集：

1. `issueProducesOTypeFourteenCharId`——INCR 返回 7、日期 2026-09-20：返回 `O2026092000007` 且 `VisitIdValidator.isValid` 为真。
2. `issueSeqOneSetsTtlFortyEightHours`——INCR 返回 1 时 expire(48h) 被调；返回 2 时不调。
3. `issueFailsFastWhenSeqExceedsDailyCap`——INCR 返回 100000（超 5 位）：IllegalStateException（签发自检红线）。

`AppointmentServiceImplTest` 用例全集（核心 12 例，断言值冻结）：

1. `windowRegistrationIssuesVisitAndMarksTaken`——WINDOW 渠道：appointment TAKEN+visit REGISTERED+visit_id 形态 `O+今日+00001`+visit.registered 发布断言（eventType/五组件）。
2. `portalBookingHoldsSlotWithPayDeadline`——PORTAL 渠道：RESERVED+pay_deadline≈now()+15m（±2s）+延迟信封入队断言（捕获 routing key=`delay.appointment-timeout`）+appointment.booked 发布。
3. `bookingRejectsWhenPoolStopped`——池行 status=STOPPED：OP-1004，零写断言（verify(apptMapper, never()).insert）。
4. `bookingRejectsWhenRedisReportsExhausted`——deduct 返回 -1：OP-1003，零写。
5. `bookingDegradesToDbCasWhenRedisDown`——deduct 抛 RedisConnectionFailureException：走 casOccupy 成功路径且 warn 留痕（日志断言可省，行为断言=insert 调用 1 次）。
6. `bookingRollsBackRedisHoldWhenDbCasExhausted`——casOccupy 连续 0 行：OP-1003 + `verify(poolRedisGate).release(...)` 回补断言。
7. `bookingRejectsDuplicateSameDaySameDept`——uk 冲突：OP-1005。
8. `bookingRejectsRestrictedNoShowPatient`——窗口内 3 次 NO_SHOW+restrict_to≥今日：OP-1006。
9. `bookingRejectsFrozenPatient`——resolver blocked=true：OP-1007。
10. `takeIssuesVisitWithinPayDeadline`——casTake 1 行：visit 签发+占位键删除断言。
11. `takeRejectsWhenDeadlinePassed`——casTake 0 行且库态 RESERVED+pay_deadline<now：OP-1008。
12. `timeoutMarksNoShowReleasesPoolAndRecordsCredit`——casStatus 1 行：NO_SHOW+casRelease 调用+credit 行 action=NO_SHOW+restrict 写入断言；`timeoutIdempotentWhenAlreadyTaken`——0 行：零回池零 credit。

Run（预期红）：编译错误（IAppointmentService 等不存在）。

- [ ] **Step 2: V201 迁移 + 实现落码**

V201 按 Interfaces DDL 全文落文件（uk_appt_patient 需 dept_code 冗余列入 appointment——DDL 已含）。`AppointmentServiceImpl` 按七步主流程落码；`OutpatientOngoingVisitQuery` 实现 `com.fuyun.patient.api.OngoingVisitQuery`（`hasOngoingVisit`：visit 表 status IN ('REGISTERED','WAITING','IN_CONSULT','PENDING_FEE') 命中即 true——合并阻断随本实现注册自动收紧）；`CareRelationQuery` 实现随 Task 8（操作者维度查询，需接诊留痕 doctor_id 语义）。timeout 监听器照 `PharmacyRefundApprovedListener` 三段式（@RabbitListener 队列 `q.outpatient.outpatient.appointment.timeout` + consume + payload 三字段非空守卫 → `appointmentService.markTimeout`）。

- [ ] **Step 3: 跑绿 + 守卫 + 提交**

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-outpatient,fuyun-system -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='VisitIdIssuerImplTest,AppointmentServiceImplTest'
python scripts/check-migration-governance.py
```

Expected: 15 用例绿 + 守卫 39 文件绿。

```bash
git add backend/fuyun-outpatient backend/fuyun-system/src/main/java/com/fuyun/system/config/SystemWebConfig.java
git commit -m "feat(outpatient): 预约/当日挂号——visit_id 签发+双道闸扣减+支付时限延迟释放

- V201 四表（appointment/visit/credit/status_log）；O 型 14 位签发 Redis 当日键（TTL 48h）
- 窗口一步 TAKEN、portal 15 分钟占位+fy.delay 超时 NO_SHOW+爽约信用限约
- portal 匿名通道白名单+介质解析；OngoingVisitQuery SPI 注册（合并阻断收紧）"
```

---

### Task 6: 退号退费联动与改期（cancel/reschedule + billing 退费端口 + refund.approved 消费终态 + 信用解除）

**Files:**
- Modify: `backend/fuyun-billing/src/main/java/com/fuyun/billing/api/OutpatientBillingPort.java`（新建）+ `api/VisitFeeView.java`、`api/VisitRefundCommand.java`（record，api 包）+ `service/impl/OutpatientBillingPortImpl.java`（新建）+ `config/BillingWebConfig.java`（@Import 增 PortImpl）
- Modify: `constants/OutpatientMessagingConstants.java`（SUBSCRIBED_EVENT_TYPES 追加 `EVENT_SUB_BILLING_REFUND_APPROVED`）
- Create: `internal/OutpatientRefundApprovedListener.java`
- Modify: `config/OutpatientMessagingConfig.java`（@Import 增监听器）
- Modify: `service/IAppointmentService.java`、`service/impl/AppointmentServiceImpl.java`（扩 cancel/reschedule/confirmRefundedCancel 三方法）
- Modify: `controller/AppointmentController.java`（扩 cancel/reschedule 端点）
- Create: `controller/ApptCreditController.java`（`GET /appt-credits?patientId=`、`POST /appt-credits/{id}/release`）
- Create: `src/test/java/com/fuyun/outpatient/service/impl/AppointmentCancelRescheduleTest.java`
- Create: `backend/fuyun-billing/src/test/java/com/fuyun/billing/service/impl/OutpatientBillingPortImplTest.java`（跨模块 PortImpl 转调单测——billing impl 包 LINE=1.00 行覆盖承载）

**Interfaces:**
- Consumes: Task 5 appointment CAS/credit 面；billing `RefundServiceImpl.apply`（`RefundApplyRequest(Long settlementId, List<RefundLine> lines, String reason)` 免审档 DAY_CORRECTION 自动直退——裁决 7 统一通道）；`RefundApprovedPayload(Long refundId, String refundNo, Long settlementId, Long patientId, Long amount, String refundType, boolean autoApproved)`（V605 id 20）。
- Produces（Task 10/12 依赖的冻结面）:
  - `com.fuyun.billing.api.OutpatientBillingPort`（消费方命名端口，PrescriptionFeePort 先例）:
    - `java.util.List<VisitFeeView> feesByVisit(String visitId)`——fee_record 按 visit_id 全状态查询（id 升序），`VisitFeeView(Long feeId, String status, String sourceRef, String triggerPoint, Long amountFen, Long settlementId)`。
    - `long applyRefund(VisitRefundCommand cmd)`——转调 `IRefundService.apply(new RefundApplyRequest(settlementId, lines→RefundLine(feeId, quantity), reason))` 返回 refundId，`VisitRefundCommand(long settlementId, java.util.List<Line> lines, String reason)`、`Line(long feeId, String quantity)`。
    - `void cancelPendingFee(long feeId, String reason)`——转调引擎 `IPricingEngineService` 既有 PENDING/CONFIRMED 作废语义（先实测 `grep -n "cancelFee\|/fees/{id}/cancel" backend/fuyun-billing/src/main/java/com/fuyun/billing/service/impl/PricingEngineServiceImpl.java backend/fuyun-billing/src/main/java/com/fuyun/billing/controller/FeeController.java` 取方法名对齐，禁新写状态迁移）。
  - 退号语义链（裁决 7，`cancel(String apptNo, String reason)` 四分支锁死）:
    1. **支付时限内未支付**（RESERVED+fee_status=UNPAID+pay_deadline 未过或已过但无 settlement 关联）：`casStatus("RESERVED","CANCELLED")`+池行回池（casRelease+Redis release）+删占位键+feeRefundTriggered=false 发布 `EVENT_APPOINTMENT_CANCELLED`——无结算可退不涉 billing（裁决 7 字面）。
    2. **已支付未取号**（RESERVED+PAID，fee_settlement_id 已由收费联动回填——Task 10）：`applyRefund`（免审档：当日退号=DAY_CORRECTION 自动直退即收 refund.approved；跨日=CROSS_DAY 进一级审批）→ appointment 保持 RESERVED 待回执（退号时限校验：sched_date - onlineCancelBeforeDays 前可线上退，逾窗 OP-1010 转窗口口径=窗口渠道不受限）。
    3. **已取号未报到**（TAKEN+visit REGISTERED）：同分支 2 退费链 + visit `casStatus("REGISTERED","CANCELLED")`（回执后）+ visit_status_log 记录 + 发布 `EVENT_VISIT_CANCELLED`。
    4. **已报到/已接诊**：OP-1010 拒绝（窗口人工「未诊即退」规则线下承载，Spec :137）。
  - `confirmRefundedCancel(RefundApprovedPayload payload)`（refund.approved 消费业务，appointment 分支）：按 `appointment.fee_settlement_id = payload.settlementId()` 定位 RESERVED/TAKEN 单 → casStatus→CANCELLED（TAKEN 态同步 visit REGISTERED→CANCELLED+log+visit.cancelled 发布）+ 池行回池 + appointment.fee_status=REFUNDED + 发布 `EVENT_APPOINTMENT_CANCELLED`（feeRefundTriggered=true）。
  - `reschedule(String apptNo, RescheduleRequest req)`（body `@NotNull Long newPoolId`）：先占新（新池行全套预扣+CAS+新 appointment 行 status=RESERVED、reschedule_of=旧 apptNo）后放旧（旧单 casStatus→CANCELLED+回池+删占位键）——「先占新后退旧防两头空」（Spec :137）；任一步失败整体回滚（事务）+ 发布 `EVENT_APPOINTMENT_RESCHEDULED`。
  - 信用手工解除：`POST /appt-credits/{id}/release`（body reason）——restrict_to=今日-1 提前解除+release_reason 留痕（@AuditLog WRITE）。

- [ ] **Step 1: 写测试（先红后绿）**

`AppointmentCancelRescheduleTest` 用例全集（断言值冻结）：

1. `unpaidCancelReleasesPoolWithoutBillingCall`——分支 1：CANCELLED+casRelease 调用+`verifyNoInteractions(billingPort)`+cancelled 事件 feeRefundTriggered=false。
2. `paidCancelAppliesRefundViaPortAndWaitsReceipt`——分支 2：applyRefund 返回 901、appointment 仍 RESERVED、fee_status 不变。
3. `takenCancelAppliesRefundAndWaitsReceipt`——分支 3（TAKEN+PAID）：applyRefund 调用且 visit 保持 REGISTERED（回执前不触 visit CAS）。
4. `cancelRejectsAfterCheckIn`——visit status=WAITING：OP-1010。
5. `cancelRejectsBeyondOnlineWindow`——sched_date=今日+3、onlineCancelBeforeDays=1：OP-1010（线上渠道）；同参 WINDOW 渠道放行（窗口不受限——分支 2 直走）。
6. `refundReceiptCancelsReservationAndReleasesPool`——payload settlementId 命中：CANCELLED+回池+fee_status=REFUNDED+cancelled 事件 feeRefundTriggered=true。
7. `refundReceiptRollsBackTakenVisitWithLog`——TAKEN 态：visit REGISTERED→CANCELLED+visit_status_log 一行（from=REGISTERED,to=CANCELLED）+visit.cancelled 发布。
8. `refundReceiptIgnoredForUnknownSettlement`——无命中：info 幂等跳过零写。
9. `rescheduleOccupiesNewBeforeReleasingOld`——新池 casOccupy 先于旧池 casRelease（InOrder 断言）；新占失败（casOccupy 0 行）时旧单零写+OP-1003。
10. `reschedulePublishesLinkEvent`——oldApptNo/newApptNo 载荷断言。
11. `creditReleaseShortensRestriction`——restrict_to 提前+release_reason 留痕。

同任务补 billing 侧 PortImpl 转调单测（`OutpatientBillingPortImpl` 位于 `com.fuyun.billing.service.impl`——父 POM 规则二 LINE=1.00 包，端口三方法转调行覆盖由本测试承载，Global Constraints 覆盖率门禁）。`OutpatientBillingPortImplTest` 用例全集：

1. `feesByVisitDelegatesAndMapsAllComponents`——FeeRecordMapper 返回两行：VisitFeeView 六组件逐字映射（feeId/status/sourceRef/triggerPoint/amountFen/settlementId）、id 升序透传。
2. `applyRefundDelegatesToRefundApplyWithLineConversion`——cmd.lines 逐条转 RefundLine(feeId, quantity)（ArgumentCaptor 断言）+refundId 透传返回。
3. `cancelPendingFeeDelegatesToPricingEngine`——feeId/reason 原样转调断言；引擎抛出异常原样上抛（转调不吞）。

Run（预期红）：`OutpatientBillingPort` 不存在编译错误。

- [ ] **Step 2: billing 端口与消费链落码**

`OutpatientBillingPortImpl`（billing service/impl，构造注入 IRefundService/IPricingEngineService/FeeRecordMapper；@Import 进 BillingWebConfig）三方法按 Interfaces 转调；`OutpatientRefundApprovedListener`（三段式，队列 `q.outpatient.billing.refund.approved`，payload 七字段 `settlementId==null→IllegalStateException` 守卫）→ `appointmentService.confirmRefundedCancel(payload)`；SUBSCRIBED_EVENT_TYPES 追加（与监听器同任务落改）。refund 分支关键点：**免审直退 refund.approved 立达**（apply 同事务发布）——IT 断言窗口 10s 轮询（Task 12）。

- [ ] **Step 3: 跑绿 + 提交**

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-outpatient,fuyun-billing -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AppointmentCancelRescheduleTest,OutpatientBillingPortImplTest'
```

Expected: 14 用例绿（11 退号/改期 + 3 端口转调；billing 既有 RefundServiceImplTest 零改动全绿——apply 转调不改内部）。

```bash
git add backend/fuyun-outpatient backend/fuyun-billing
git commit -m "feat(outpatient,billing): 退号退费联动与改期——统一 M13 免审档+回执驱动终态

- OutpatientBillingPort（feesByVisit/applyRefund/cancelPendingFee）进程内承载
- 退号四分支：时限内免退费/已付退费审批/已取号回滚/已报到拒线上退
- refund.approved 消费终态（回执前占位不释放）；改期先占新后退旧+reschedule_of 链
- 信用手工解除端点；appointment.cancelled/rescheduled 事件发布"
```

---

### Task 7: 分诊台与候诊队列（V202 两表 + 报到/二次分诊/调级 + Redis ZSET + 自建 WS configurer + REST 快照）

**Files:**
- Create: `backend/fuyun-outpatient/src/main/resources/db/migration/outpatient/V202__create_triage_and_queue.sql`
- Create: `enums/TriageAction.java`、`enums/TicketStatus.java`、`enums/TicketType.java`（3 枚举）
- Create: `entity/TriageRecord.java`、`entity/QueueTicket.java` + 两 mapper（QueueTicketMapper 含 `casStatus`/`selectWaiting` 注解 SQL——selectWaiting 供重启恢复惰性重建取 WAITING 权威行）
- Create: `cache/QueueZsetStore.java`
- Create: `service/ITriageService.java`、`service/impl/TriageServiceImpl.java`
- Create: `dto/CheckInRequest.java`、`dto/TriageAdjustRequest.java`、`dto/QueueCallRequest.java`、`vo/QueueTicketVO.java`
- Create: `controller/TriageController.java`（check-in/adjust）、`controller/QueueController.java`（call/pass/recall/tickets 快照）
- Create: `config/OutpatientWebSocketConfig.java`、`internal/OutpatientConnectAuthInterceptor.java`
- Modify: `config/OutpatientWebConfig.java`（@Import 增 impl/controller）
- Modify: `backend/fuyun-app/src/main/java/com/fuyun/app/config/OutpatientConfig.java`（@Import 增 OutpatientWebSocketConfig）
- Create: `src/test/java/com/fuyun/outpatient/service/impl/TriageServiceImplTest.java`、`src/test/java/com/fuyun/outpatient/internal/OutpatientConnectAuthInterceptorTest.java`

**Interfaces:**
- Consumes: Task 5 visit CAS（REGISTERED→WAITING）；OperatorContextHolder（护士/医生操作者）。
- Produces（Task 8/13/14 依赖的冻结面）:
  - V202 DDL：`triage_record(id / visit_id VARCHAR(14) NOT NULL / station_id VARCHAR(64) NOT NULL（分诊台/自助终端标识）/ action VARCHAR(16) NOT NULL（CHECK_IN/RE_TRIAGE/LEVEL_ADJUST/QUEUE_TRANSFER）/ triage_level INT NULL / target_queue VARCHAR(64) NOT NULL（=dept_code 诊区队列）/ doctor_id VARCHAR(64) NULL（二次分诊定医生）/ priority_factor VARCHAR(255) NULL（急/老幼残/回诊因子 JSON）/ nurse_id VARCHAR(64) NOT NULL / reason VARCHAR(255) NULL / 审计五列)`；`queue_ticket(id / visit_id VARCHAR(14) NOT NULL / queue_id VARCHAR(64) NOT NULL（=dept_code，P1 诊区队列口径，偏差⑧）/ ticket_no VARCHAR(16) NOT NULL（队列内当日序号，如 A007）/ ticket_type VARCHAR(16) NOT NULL（FIRST/VISIT/RETURN/EXTRA）/ doctor_id VARCHAR(64) NULL / priority_score INT NOT NULL / queue_seq INT NOT NULL（当日序） / queue_time TIMESTAMPTZ NOT NULL / called_count INT NOT NULL DEFAULT 0 / call_time TIMESTAMPTZ NULL / serve_time TIMESTAMPTZ NULL / status VARCHAR(16) NOT NULL DEFAULT 'WAITING'（WAITING/CALLED/SERVING/SERVED/PASSED/CANCELLED）/ 审计五列；uk_ticket_visit UNIQUE(visit_id, queue_seq)；uk_ticket_queue UNIQUE(queue_id, ticket_no) WHERE deleted=0)`。
  - 优先级公式（冻结，偏差⑨）：`priority_score = 100（基础）+ 绿通 900（P1 恒不触发，声明值）+ 急诊分级（triage_level 1/2/3/4 → 800/700/600/500，非急诊 0）+ 回诊/复诊 300（ticket_type=RETURN/REVISIT 来源）+ 老幼残 200（分诊台人工设定 priority_factor 含 "ELDERLY"/"CHILD"/"DISABLED" 任一）`；同分序=queue_seq 升序；ZSET score = `priority_score * 100000000L + queue_seq`（量级 3e11 < 2^53 安全）。
  - `QueueZsetStore`（`fy:outpatient:queue:{deptCode}`，TTL=当日末+2h）：`void enqueue(String deptCode, long ticketPk, int score)`、`Long pollTop(String deptCode, String doctorId)`（ZRANGELIST 首个匹配 doctor 未指派或指派一致者，经 Lua 原子出队并返回 ticketPk；无匹配 null）、`void remove(String deptCode, long ticketPk)`、`java.util.List<java.lang.Long> snapshot(String deptCode, int limit)`、`int rebuildIfMissing(String deptCode, java.util.Map<Long, Integer> ticketScores)`（**重启恢复**：键缺失时按 WAITING 权威行整体 ZADD 重建+TTL、返回重建票数；键在位返回 -1 零写——Spec :210「叫号服务重启后队列从排队表完整恢复」）。
  - 队列语义：`checkIn(CheckInRequest{visitId, stationId})`——visit `casStatus("REGISTERED","WAITING")` + visit.checked_in_at 回填（国标报到时间）+ triage_record(action=CHECK_IN) + queue_ticket 建行（ticket_no=A+%03d 队列当日序）+ ZSET 入队 + 已约取号即报到可配置（appointment 已 TAKEN 直接可 check-in）；`adjust(TriageAdjustRequest{visitId, action, targetQueue?, doctorId?, triageLevel?, priorityFactors?})`——跨队列转接（换 dept=旧 ZSET remove+新队建票重算分）、调级（重算分 ZSET 更新 score，票号不变——过号降级重排不改号 Spec :106）；`call(QueueCallRequest{deptCode, doctorId})`——前置惰性重建（`queueTicketMapper.selectWaiting(deptCode)` 取当日 WAITING 权威行→`rebuildIfMissing(deptCode, 票→score 公式映射)`；幂等：键在位零重建，禁回灌已出队票）→ZSET 原子出队→ticket CAS WAITING→CALLED+called_count+1+call_time；叫号≠接诊：visit 保持 WAITING，接诊由 Task 8 `/visits/{visitId}/admit` 承载（ticket CALLED→SERVING 由 admit 联动）+ WS 推送 `{type:"CALLED", ticketNo, visitId(脱敏为 ticketNo+姓名脱敏), doctorId, room}` 至 `/topic/outpatient/queue/{deptCode}` 与 `/topic/outpatient/doctor/{doctorId}`；`pass(ticketId)`——CALLED→PASSED+ZSET 以降级分（priority_score-100，下限 0）重排（WAITING 语义经 PASSED 再入）；`recall(ticketId)`——PASSED→CALLED 重复叫（called_count+1）；`GET /queues/{queueId}/tickets?status=`——REST 快照（脱敏：患者姓名掩码+无证件号）。
  - `OutpatientWebSocketConfig`（裁决 12）：`@Configuration @EnableWebSocketMessageBroker`（与 IotWebSocketConfig 重复导入为 Spring 去重 no-op，注记）实现 WebSocketMessageBrokerConfigurer——`registerStompEndpoints` 增端点 `/ws/outpatient`（纯 WebSocket 无 SockJS）；`configureClientInboundChannel` 挂 `OutpatientConnectAuthInterceptor`；`configureMessageBroker` 幂等 `enableSimpleBroker("/topic")`（同值，两 configurer 共存依据：DelegatingWebSocketMessageBrokerConfiguration 收集全部 configurer，各 registerStompEndpoints 叠加生效——执行时 SmokeStackIT 启动实证双端点在位）。
  - `OutpatientConnectAuthInterceptor`：镜像 `fuyun-iot/internal/StompConnectAuthInterceptor.java` 全语义（CONNECT 帧 Bearer→`TokenVerifier.verifyAccessToken` 布尔校验；拒绝抛 MessagingException 固定摘要防枚举；日志不含令牌；仅拦 CONNECT）——包名/javadoc/TRACE_ID MDC 键改 `traceId`（common TraceIdFilter 同源），偏差⑥。

- [ ] **Step 1: 写测试（先红后绿）**

`TriageServiceImplTest` 用例全集（断言值冻结）：

1. `checkInMovesVisitToWaitingAndEnqueues`——visit CAS REGISTERED→WAITING+ticket priority_score=100（无因子）+ZSET score=100*1e8+seq。
2. `checkInComputesEmergencyLevelScore`——triage_level=1：priority_score=900（100+800）。
3. `checkInAddsFrailtyAndRevisitFactors`——factors=[ELDERLY]+ticket_type=RETURN：priority_score=600（100+200+300）。
4. `checkInRejectsNonRegisteredVisit`——visit=FINISHED：OP-1011。
5. `adjustRecomputesScoreWithoutChangingTicketNo`——调级 100→900 且 ticket_no 不变（过号降级重排不改号）。
6. `adjustTransfersQueueRebuildsTicket`——换 dept：旧 ZSET remove+新队新票（uk_ticket_queue 新 queue_id）。
7. `callPollsTopMatchingDoctorAndMarksCalled`——doctor 指派匹配首票 CALLED+called_count=1+WS 推送两通道断言（SimpMessagingTemplate mock 捕获 destination 与载荷）。
8. `callReturnsNullWhenQueueEmpty`——null 返回（200 空语义）。
9. `passDemotesAndRequeues`——PASSED 后 ZSET 以 priority_score-100 重入。
10. `recallReCallsPassedTicket`——PASSED→CALLED+called_count=2。
11. `snapshotMasksPatientName`——快照 VO 姓名=`张*`（掩码；证件号不出网）。
12. `callRebuildsQueueFromWaitingTicketsWhenZsetKeyMissing`——重启恢复场景（Spec :210）：hasKey=false+库中两张 WAITING 票：rebuildIfMissing 返回 2（ZADD score=100*1e8+seq 公式）+call 正常出队首票。
13. `callSkipsRebuildWhenZsetKeyPresent`——键在位：rebuildIfMissing 返回 -1 零重建（幂等，正常出队态不回灌）。

`OutpatientConnectAuthInterceptorTest` 用例全集（镜像 iot 侧同名单——先实测 `sed -n '1,106p' backend/fuyun-iot/src/main/java/com/fuyun/iot/internal/StompConnectAuthInterceptor.java` 对齐，iot 侧既有测试为模板）：`connectPassesWithValidBearerToken`/`connectRejectsWithoutAuthorizationHeader`/`connectRejectsWithNonBearerScheme`/`connectRejectsWithInvalidToken`/`nonConnectFramePassesThrough`/`rejectionThrowsMessagingExceptionWithFixedMessage` 六例。

Run（预期红）：编译错误。

- [ ] **Step 2: 迁移与实现落码**

V202 全文落文件；`TriageServiceImpl` 按语义落码（WS 推送经构造注入 `SimpMessagingTemplate`——`@EnableWebSocketMessageBroker` 派生 Bean，Task 3 OutpatientConfig @Import 链在 app 上下文生效；`call`/快照路径前置 `selectWaiting`+`rebuildIfMissing` 惰性重建——实现锚：

```java
    /**
     * 队列惰性重建（叫号服务重启后队列从排队表完整恢复，Spec :210）：ZSET 键缺失（Redis 重启/
     * 淘汰后首访）时按 queue_ticket 的 WAITING 权威行整体重建；键在位零写（幂等——已出队票的
     * CAS WAITING→CALLED 与 ZSET 移除同步，回灌即重复叫号）。
     *
     * @param deptCode     诊区队列标识，非空
     * @param ticketScores WAITING 票 pk→score（priority_score*1e8+queue_seq）映射，调用方由库行计算
     * @return 重建票数；键在位返回 -1（跳过信号）
     */
    public int rebuildIfMissing(String deptCode, Map<Long, Integer> ticketScores) {
        String key = keyOf(deptCode);
        // 键在位=正常态：零写返回，防把已 CALLED/PASSED 出队票回灌队列
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return -1;
        }
        // 键缺失=重启/淘汰后首访：WAITING 权威行整体 ZADD 重建（score 公式与入队同源）
        ticketScores.forEach((ticketPk, score) ->
                redisTemplate.opsForZSet().add(key, String.valueOf(ticketPk), score));
        redisTemplate.expire(key, ttlOfTodayEndPlus2h()); // 重建键与常规入队同 TTL 规范（当日末+2h）
        return ticketScores.size();
    }
```

）；`OutpatientWebSocketConfig`/`OutpatientConnectAuthInterceptor` 落码；OutpatientConfig @Import 增 WS 配置类。

- [ ] **Step 3: 跑绿 + 提交**

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-outpatient -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='TriageServiceImplTest,OutpatientConnectAuthInterceptorTest'
```

Expected: 19 用例绿（13 分诊/队列 + 6 拦截器）。

```bash
git add backend/fuyun-outpatient backend/fuyun-app
git commit -m "feat(outpatient): 分诊台与候诊队列——V202+优先级 ZSET+自建 STOMP 通道

- 报到/二次分诊/调级/跨队列转接全留痕；过号降级重排不改号
- priority_score 冻结公式（绿通/急诊分级/回诊/老幼残因子）+ZSET 当日序并列序
- 自建 OutpatientWebSocketConfig+/ws/outpatient 端点+CONNECT 帧鉴权镜像（禁依赖 fuyun-iot）
- 队列 REST 快照双通道（脱敏出网）；/topic/outpatient/queue|doctor 双 topic 推送"
```

---

### Task 8: 门诊医生站与开单（V203 申请单两表 + 候诊列表/接诊/诊毕 + order.created 发布 + fee.created 消费 + CareRelationQuery 注册）

**Files:**
- Create: `backend/fuyun-outpatient/src/main/resources/db/migration/outpatient/V203__create_clinic_order.sql`
- Create: `enums/OrderType.java`、`enums/OrderStatus.java`（2 枚举）
- Create: `entity/ClinicOrder.java`、`entity/ClinicOrderItem.java` + 两 mapper（ClinicOrderMapper 含 `casStatus` 注解 SQL）
- Create: `service/IClinicOrderService.java`、`service/impl/ClinicOrderServiceImpl.java`
- Create: `service/impl/OutpatientCareRelationQuery.java`（实现 patient/api/CareRelationQuery）
- Modify: `service/impl/TriageServiceImpl.java`（admit 联动 ticket CALLED→SERVING+serve_time——接口方法随 IVisitService 扩展）
- Create: `service/IVisitService.java`、`service/impl/VisitServiceImpl.java`（admit/finish/patientQueue + visit 状态机单点 `VisitStateMachine`）
- Create: `dto/OrderCreateRequest.java`、`dto/OrderItemRequest.java`、`dto/FinishVisitRequest.java`、`vo/ClinicOrderVO.java`、`vo/DoctorQueueItemVO.java`
- Create: `controller/VisitController.java`（admit/finish/patient-queue/orders）、`controller/OrderController.java`（cancel/查询）
- Create: `internal/OutpatientFeeCreatedListener.java`
- Modify: `constants/OutpatientMessagingConstants.java`（SUBSCRIBED_EVENT_TYPES 追加 `EVENT_SUB_BILLING_FEE_CREATED`）
- Modify: `config/OutpatientMessagingConfig.java`（@Import 增监听器）、`config/OutpatientWebConfig.java`（@Import 增 impl/controller 面）
- Create: `src/test/java/com/fuyun/outpatient/service/impl/VisitServiceImplTest.java`、`ClinicOrderServiceImplTest.java`
- Create: `backend/fuyun-system/src/test/java/com/fuyun/system/service/impl/PracticeCheckPortImplTest.java`（跨模块 PortImpl 转调单测——system impl 包 LINE=1.00 行覆盖承载）

**Interfaces:**
- Consumes: `POST /api/v1/system/practice/check` 为 CF-2 REST 契约，但跨模块进程内调用仅经 api 包合法（B.2）——本任务落 `backend/fuyun-system/src/main/java/com/fuyun/system/api/PracticeCheckPort.java`（`PracticeCheckResult check(long employeeId, String grantType)`，`api/PracticeCheckResult.java` record `(boolean passed, String reason)`）+ `service/impl/PracticeCheckPortImpl.java`（转调 IPracticeService.check）+ SystemWebConfig @Import 增 PortImpl；Task 4 池行 CAS；OperatorContextHolder。
- Produces（Task 9/10/12 依赖的冻结面）:
  - V203 DDL：`clinic_order(id / order_no VARCHAR(32) NOT NULL（OP+yyyyMMdd+6 位流水）/ visit_id VARCHAR(14) NOT NULL / patient_id BIGINT NOT NULL / order_type VARCHAR(16) NOT NULL（EXAM 检查/LAB 检验/TREATMENT 治疗/DISPOSAL 处置/MATERIAL 材料/RX_REF 处方引用）/ ext_ref VARCHAR(64) NULL（rx_ref=M06 rxNo；P1 仅 RX_REF 写）/ order_doctor_id VARCHAR(64) NOT NULL / valid_to TIMESTAMPTZ NULL（执行有效期，P1 空）/ status VARCHAR(16) NOT NULL DEFAULT 'CREATED'（CREATED/PENDING_FEE/CHARGED/IN_EXECUTION（声明态）/COMPLETED（声明态）/CANCELLED）/ fee_settlement_id BIGINT NULL / 审计五列；uk_order_no UNIQUE(order_no))`；`clinic_order_item(id / order_id BIGINT NOT NULL / item_code VARCHAR(64) NOT NULL（M13 物价库 code，红线不自建价格）/ quantity VARCHAR(32) NOT NULL（DECIMAL string）/ usage_summary VARCHAR(255) NULL / 审计五列)`。
  - `VisitStateMachine`（constants/ 或 service/ 静态工具）：合法迁移对静态 `Set<String>`（Global Constraints visit 状态机红线全集九对，字面 `REGISTERED->WAITING` 等）；`require(from, to)` 违例抛 OP-1011；每次 CAS 成功后 `visit_status_log` 插行（from/to/reason/operator）。
  - `VisitServiceImpl`：`admit(String visitId)`——ticket CALLED→SERVING（本医生票）+visit `casStatus("WAITING","IN_CONSULT")`+admitted_at 回填（国标接诊时间）；`finish(String visitId, FinishVisitRequest{disposition, explicitConfirm})`——诊毕前置校验：clinic_order 存在 PENDING_FEE/CREATED 在途且 explicitConfirm=false → OP-1016（在途单据须终态或显式确认；M09 文书校验参数化提醒不拦截——not-in-scope 注记）；disposition 词表校验（`outpatient.disposition` 六/八码集=V705 disposition item_code 全集硬编码静态清单）违例 OP-1018；visit `casStatus("IN_CONSULT","FINISHED")`（PENDING_FEE→FINISHED 亦合法——显式确认路径）+finished_at/finish_operator 回填+发布 `EVENT_VISIT_FINISHED`；`patientQueue(String deptCode, String doctorId)`——候诊列表（本队列 WAITING/CALLED 票+患者摘要（姓名脱敏）+过敏标识位（P1 恒 false——M02 订阅缓存 P-later 注记））。
  - `ClinicOrderServiceImpl.create(String visitId, OrderCreateRequest req)`：req=`@NotNull String orderType（EXAM/LAB/TREATMENT/DISPOSAL/MATERIAL 显式必填）, @NotEmpty List<OrderItemRequest> items`；流程五步：①visit 终态守卫（FINISHED/CANCELLED→OP-1011，红线 5）；②开单执业授权强校验：医师开检查/检验/治疗/处置单统一校验 `PRESCRIPTION` 处方权（Spec :140「开单前调 practice/check」；未过抛 OP-1017 403；employeeId 取 `Long.parseLong(OperatorContextHolder.get())`——运行态 userId 直作 employeeId，V704 种子对齐口径见 Task 2 Step 2 身份链注记）；③order_no 签发（OP+日期+Redis 序列键 `fy:outpatient:order-seq:{yyyyMMdd}` INCR TTL 48h——visit-seq 同型）；④CREATED 落库+明细行落库（quantity DECIMAL string 红线）；⑤发布 `EVENT_ORDER_CREATED`（OrderCreatedPayload，orderId=order_no，lines 逐条 itemCode/quantity）。**事务边界**：发布在事务内 publishEvent（AFTER_COMMIT 出线）。
  - `ClinicOrderServiceImpl.cancel(String orderNo, String reason)`：CREATED/PENDING_FEE→CANCELLED（费用作废：`billingPort.feesByVisit` 定位 sourceRef=orderNo 的 PENDING 行逐行 `cancelPendingFee`）；CHARGED 态拒绝引导退费链 OP-1015（refund.approved 逆向随 Task 10）；RX_REF 行作废返回 OP-1015（文案：`处方引用行作废经 M06 作废链发起（POST /api/v1/pharmacy/prescriptions/{no}/cancel），pharmacy.prescription.cancelled 回流驱动本行 CANCELLED`）——Spec :119 R2-10 语义即「作废必须经 M06 作废 API 发起」，M03 端点对 RX_REF 行引导性 409 为合法业务语义，回流驱动在 Task 10 落地。
  - `OutpatientFeeCreatedListener`（队列 `q.outpatient.billing.fee.created`）：payload.billingKey 三段守卫（`trigger=ORDER_CONFIRMED` 才处理）+ `sourceRef` 定位 clinic_order → `casStatus("CREATED","PENDING_FEE")` + visit（IN_CONSULT→PENDING_FEE 状态机推进+log）；重复投递幂等=CAS 0 行重读 PENDING_FEE info 跳过。
  - `OutpatientCareRelationQuery implements CareRelationQuery`：`hasCareRelation(patientId, operatorId)`——visit 表 `patient_id=? AND doctor_id=? AND status IN ('WAITING','IN_CONSULT','PENDING_FEE')` 命中即 true（D-16 第二道；注册后 patient 侧自动收紧，OngoingVisitQuery 冻结语义对称）。

- [ ] **Step 1: 写测试（先红后绿）**

`VisitServiceImplTest` 用例全集：

1. `admitMarksServingAndMovesVisitToInConsult`——ticket CAS CALLED→SERVING+serve_time+visit IN_CONSULT+admitted_at+log 一行。
2. `admitRejectsWhenNoCalledTicket`——OP-1013。
3. `finishRejectsWithPendingOrdersWithoutConfirm`——在途 PENDING_FEE 单+explicitConfirm=false：OP-1016。
4. `finishPassesWithExplicitConfirm`——explicitConfirm=true：FINISHED+finished_at+visit.finished 发布（disposition/finishOperator 断言）。
5. `finishRejectsUnknownDisposition`——`CHARGE_BACK`（词表外）：OP-1018。
6. `finishedVisitRejectsNewOrder`——红线 5：OP-1011（经 ClinicOrderServiceImpl.create 入口断言）。
7. `patientQueueReturnsMaskedWaitingList`——姓名掩码+票号+优先级降序。

`ClinicOrderServiceImplTest` 用例全集：

1. `createPublishesOrderCreatedWithLines`——两行明细：payload orderId/orderNos 形态、lines[0].itemCode/quantity 逐字断言（quantity "2" string）。
2. `createRejectsWhenPracticeCheckFails`——port false：OP-1017 且零写。
3. `createAssignsSequentialOrderNo`——OP+今日+00001/00002 连续两单。
4. `cancelVoidingPendingFeeViaPort`——PENDING_FEE 单：casStatus→CANCELLED+cancelPendingFee(feeId) 调用断言。
5. `cancelRejectsChargedOrder`——OP-1015。
6. `cancelRejectsRxRefRowWithGuidance`——RX_REF 行：OP-1015 文案含「经 M06 作废链」。

同任务补 system 侧 PortImpl 转调单测（`PracticeCheckPortImpl` 位于 `com.fuyun.system.service.impl`——父 POM 规则二 LINE=1.00 包，端口转调行覆盖由本测试承载）。`PracticeCheckPortImplTest` 用例全集：

1. `checkDelegatesPassResultWithReason`——IPracticeService 返回 passed=true：result.passed=true、reason 原样透传（不二次包装）。
2. `checkDelegatesFailResultWithReason`——passed=false：result.passed=false、reason 原样透传（调用方 OP-1017/PH-1016 语义不受影响）。

Run（预期红）：编译错误。

- [ ] **Step 2: 迁移与实现落码**

V203 全文落文件；`PracticeCheckPort`+Impl 落 system 侧（api record `PracticeCheckResult(boolean passed, String reason)`）；`VisitStateMachine`/`VisitServiceImpl`/`ClinicOrderServiceImpl`/监听器/CareRelationQuery 落码；OUTPATIENT 侧 order-seq Redis 键复用 VisitIdIssuerImpl 模式（提取 `DailyRedisSeq` 私有静态或复制三行——禁为两用新建抽象，visit/order 各自三行直写）。

- [ ] **Step 3: 跑绿 + 提交**

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-outpatient,fuyun-system -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='VisitServiceImplTest,ClinicOrderServiceImplTest,PracticeCheckPortImplTest'
```

Expected: 15 用例绿（13 医生站/开单 + 2 端口转调）。

```bash
git add backend/fuyun-outpatient backend/fuyun-system
git commit -m "feat(outpatient,system): 门诊医生站与开单——visit 状态机+order.created+practice 强校验

- V203 申请单两表；visit 状态机单点校验+visit_status_log 全迁必记（红线 5）
- 接诊/诊毕前置校验（在途单据显式确认+离院去向词表）；候诊列表脱敏
- clinic_order 开单发布 order.created（orderId=order_no，lines 计费行）
- fee.created 消费 CREATED→PENDING_FEE 与 visit 待缴费推进；PracticeCheckPort api 面
- CareRelationQuery 注册（D-16 三态第二道收紧）；RX_REF 作废引导 M06 作废链"
```

---

### Task 9: 处方衔接与 practice/check 双端接线（pharmacy api 两端口 + M03 调用登记处方引用 + pharmacy 纵深防御）

**Files:**
- Create: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/api/PrescriptionOpenPort.java`、`api/PrescriptionCancelPort.java`、`api/PrescriptionOpenCommand.java`、`api/PrescriptionOpenResult.java`
- Create: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/service/impl/PrescriptionOpenPortImpl.java`、`PrescriptionCancelPortImpl.java`
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/config/PharmacyWebConfig.java`（@Import 增两 PortImpl）
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/service/impl/PrescriptionServiceImpl.java:44`、`:86`（两处 TODO(PR-5) 落 practice/check 接线——构造器注入 `PracticeCheckPort`（system api））
- Modify: `backend/fuyun-outpatient/src/main/java/com/fuyun/outpatient/service/impl/ClinicOrderServiceImpl.java`（开方动作编排方法 `openPrescription`）
- Modify: `backend/fuyun-outpatient/src/main/java/com/fuyun/outpatient/controller/OrderController.java`（`POST /visits/{visitId}/prescriptions`——开方动作入口）
- Create: `dto/PrescriptionOpenRequest.java`（M03 侧 DTO）
- Modify: 单测：`PrescriptionServiceImplTest`（构造器演进+两用例：授权未过拒开/授权过放行）+ `ClinicOrderServiceImplTest`（开方编排一例）
- Create: `src/test/java/com/fuyun/pharmacy/service/impl/PrescriptionOpenPortImplTest.java`、`PrescriptionCancelPortImplTest.java`（跨模块 PortImpl 转调单测——pharmacy impl 包 LINE=1.00 行覆盖承载）

**Interfaces:**
- Consumes: `IPrescriptionService.create/cancel` 既有签名（PR-4 实码）；`PracticeCheckPort`（Task 8 system api 面）；practice_grant 词表（PRESCRIPTION/NARCOTIC/ANTIBIO_NONRESTRICT/ANTIBIO_RESTRICT/ANTIBIO_SPECIAL）。
- Produces（Task 12 IT 依赖的冻结面）:
  - `PrescriptionOpenPort`：`PrescriptionOpenResult open(PrescriptionOpenCommand cmd)`；`PrescriptionOpenCommand(Long patientId, String visitId, String rxType, String deptCode, java.util.List<String> diagnosisCodes, Boolean skinTestRequired, java.util.List<Item> items)`、`Item(Long drugId, String quantity, String unit, String singleDose, String routeCode, String frequency, Integer days, String usageNote)`（与 pharmacy `dto/RxItemRequest` 组件一一对应——api 面镜像，禁 dto 外引）；`PrescriptionOpenResult(String rxNo, String status, String reviewLevel, boolean skinTestRequired)`（impl 从 PrescriptionVO 映射：rxNo/status/reviewLevel/skinTestRequired 四组件）。
  - `PrescriptionCancelPort`：`void cancel(String rxNo, String reason)`（转调既有 cancel——未缴费作废语义，已缴费拒 PH-1014 引导退费链口径不变）。
  - **纵深防御接线（裁决 9，Spec :226「与 M03 开方入口校验构成纵深防御」）**：`PrescriptionServiceImpl.create` 在 :86 TODO 行位置插入两段校验（落库前、明细聚合后）：①`practiceCheckPort.check(Long.parseLong(operator), "PRESCRIPTION")` 未过抛 PH-1016（403，新增 `PharmacyErrorCode PRACTICE_NOT_ALLOWED("PH-1016")`，文案含工号脱敏；operator=AuthTokenInterceptor :76 注入的运行态 userId，直作 employeeId——V704 种子 employee_id 与 sys_user.id 对齐=3，Task 2 Step 2 身份链注记）；②按处方命中集追加校验——抗菌药最高分级（ANTIBIO_NONRESTRICT/ANTIBIO_RESTRICT/ANTIBIO_SPECIAL）与麻精类（narcoticClass≠NORMAL）各查对应 grant_type，任一未过即 PH-1016；三次 check 以命中集为准（无抗菌/麻精药只查 PRESCRIPTION 一次）。M03 入口（openPrescription）同样前置 `practiceCheckPort.check(operator, "PRESCRIPTION")`（OP-1017）——M03 开单校验与 M06 开方校验纵深两层。
  - `openPrescription(String visitId, PrescriptionOpenRequest req)` 编排（M03 侧）：①visit 终态守卫 OP-1011；②practice check（PRESCRIPTION）OP-1017；③`prescriptionOpenPort.open(cmd)` 同步调用；④clinic_order 建 RX_REF 引用行（order_type=RX_REF、ext_ref=rxNo、status=CREATED——**不复制药品明细**，红线 3；引用行无计费行——药品计费行由 pharmacy.prescription.created 携带 M-4 裁决，billing 零双头）；⑤返回 ClinicOrderVO（含 rxNo）。
- `DispenseServiceImpl`/`IDispenseService` 的 releaseByRxNos/verify/confirmRefundTerminal 改造**不在本任务**（归 Task 11）——本任务仅动 PrescriptionServiceImpl 与 api 端口。

- [ ] **Step 1: 写测试（先红后绿）**

`PrescriptionServiceImplTest` 演进（既有用例构造器同步追加 practiceCheckPort mock）+新增两例：

1. `createRejectsWhenPrescriptionGrantMissing`——check(PRESCRIPTION) false：PH-1016 零写。
2. `createChecksAntibioAndNarcoticGrantsByTopClass`——含限制级抗菌药+麻精药：verify(port) times(3)（PRESCRIPTION/ANTIBIO_RESTRICT/NARCOTIC），全 true 放行；任一 false 即 PH-1016。

`PrescriptionOpenPortImplTest` 用例：

3. `openDelegatesAndMapsResultComponents`——cmd→create 转调+结果四组件映射断言。

`PrescriptionCancelPortImplTest` 用例（跨模块 PortImpl 转调单测——pharmacy impl 包 LINE=1.00 包行覆盖承载）：

4. `cancelDelegatesToExistingCancelAndPropagatesRejection`——rxNo/reason 转调断言；已缴费拒 PH-1014 异常原样上抛（转调不吞，引导退费链语义不变）。

`ClinicOrderServiceImplTest` 新增：

5. `openPrescriptionRegistersRxRefWithoutCopyingDrugLines`——RX_REF 行 ext_ref=rxNo+clinic_order_item 零插入+OP-1017 前置校验先于端口调用（InOrder）。

Run（预期红）：`PrescriptionOpenPort` 不存在编译错误。

- [ ] **Step 2: 端口与接线落码**

按 Interfaces 落码；`PrescriptionServiceImpl` 构造器追加第六参 `PracticeCheckPort practiceCheckPort`（单测 `newService()` 同步）；删除 :44/:86 两处 TODO 注释行（接线完成即死注释清除）。

- [ ] **Step 3: 跑绿 + 提交**

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-pharmacy,fuyun-outpatient -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='PrescriptionServiceImplTest,PrescriptionOpenPortImplTest,PrescriptionCancelPortImplTest,ClinicOrderServiceImplTest'
```

Expected: 全绿（既有 pharmacy 用例零回退，pharmacy impl LINE=1.00 维持——新分支两例与两 PortImpl 转调单测共同承载新落码行覆盖）。

```bash
git add backend/fuyun-pharmacy backend/fuyun-outpatient
git commit -m "feat(pharmacy,outpatient): 开方衔接与 practice/check 双端接线——TODO(PR-5) 闭合

- pharmacy api 两端口（Open/Cancel）进程内承载（禁 HTTP 自调用，PrescriptionFeePort 先例）
- 开方纵深防御：处方权/抗菌药三级/麻精按命中集三次校验（PH-1016）
- M03 openPrescription 编排：RX_REF 引用行登记不复制药品明细（红线 3，M-4 零双头）"
```

---

### Task 10: 收费编排与退费逆向（settlement.completed 消费放行扇出 + refund.approved order 分支回滚 + SettlementQueryPort）

**Files:**
- Create: `backend/fuyun-billing/src/main/java/com/fuyun/billing/api/SettlementQueryPort.java`、`api/SettlementSourceRefs.java`（record）+ `service/impl/SettlementQueryPortImpl.java`（@Import 进 BillingWebConfig）
- Create: `internal/OutpatientSettlementCompletedListener.java` + 三回流监听器 `internal/OutpatientPrescriptionCancelledListener.java`、`internal/OutpatientDispenseCompletedListener.java`、`internal/OutpatientDispenseReturnedListener.java`（pharmacy 每事件一监听器先例同型）
- Modify: `constants/OutpatientMessagingConstants.java`（SUBSCRIBED_EVENT_TYPES 追加 settlement.completed/prescription.cancelled/dispense.completed/returned 四条）
- Modify: `config/OutpatientMessagingConfig.java`（@Import 增四监听器）
- Create: `service/IChargingService.java`、`service/impl/ChargingServiceImpl.java`（收费编排域）
- Modify: `service/impl/AppointmentServiceImpl.java`（挂号费收费回填 `markRegistrationPaid(settleNo, settlementId, patientId)`——settlement.completed 消费侧分发）
- Create: `src/test/java/com/fuyun/outpatient/service/impl/ChargingServiceImplTest.java`
- Create: `backend/fuyun-billing/src/test/java/com/fuyun/billing/service/impl/SettlementQueryPortImplTest.java`（跨模块 PortImpl 转调单测——billing impl 包 LINE=1.00 行覆盖承载）

**Interfaces:**
- **收费同步面裁决（裁决 7/资金无涉红线推论）**：就诊费用的划价/预结算/结算为 M13 同步 REST 契约（CF-4「预结算/结算/退费 API」人机面）——收费工作台前端**直调 `/api/v1/billing/**` 既有端点**（workstation 划价结算页同构，PR-3 已交付前端 billing.ts），M03 后端零收费编排 REST、零资金逻辑；本任务后端编排面=事件消费+状态推进+挂号费收费回填。
- Consumes: `SettlementCompletedPayload(Long settlementId, String settleNo, Long patientId, String visitId, String settleType, ...)`（V605 id 19）；`PrescriptionCancelledPayload/DispenseCompletedPayload/DispenseReturnedPayload`（pharmacy api 既有）；Task 8 clinic_order CAS 面。
- Produces（Task 11/12 依赖的冻结面）:
  - `com.fuyun.billing.api.SettlementQueryPort`（裁决 5/6/8 的「反查」唯一载体）:
    - `SettlementSourceRefs sourceRefsOfSettlement(long settlementId)`——fee_record 按 settlement_id 查询按 trigger_point 分组：`SettlementSourceRefs(long settlementId, java.util.List<String> orderRefs（trigger=ORDER_CONFIRMED 的 source_ref 去重升序）, java.util.List<String> rxRefs（trigger=PRESCRIPTION_EFFECTIVE 去重升序）)`。
    - `boolean settledUnder(String settleNo, String sourceRef)`——settlement(settle_no) JOIN fee_record(source_ref) 存在 SETTLED 费用行即 true（Task 11 凭证核验消费）。
  - `ChargingServiceImpl`（LINE=1.00 面）:
    - `onSettlementCompleted(SettlementCompletedPayload payload)`（OUT/门诊结算类型守卫：settleType="OUT" 才处理——IN 为住院分支忽略）：①visit 存在性校验；②clinic_order 申请单 `casStatus("PENDING_FEE","CHARGED")` 批量（sourceRef=orderNo 经 `SettlementSourceRefs.orderRefs` 精确定位——**单据精确**：以 billing 端口反查 orderRefs 为准，非 visit 全量扫描）+fee_settlement_id 回填；②b 处方引用行 CHARGED：RX_REF 行按 ext_ref ∈ `SettlementSourceRefs.rxRefs` 精确定位 `casStatus("CREATED","CHARGED")` 批量（药品费用行 trigger=PRESCRIPTION_EFFECTIVE 不经 fee.created 推进、RX_REF 行停留 CREATED 态，故自 CREATED 直迁 CHARGED——Spec :142/FU-M03-08「settlement.completed 后处方引用行→CHARGED」）；③rxNos=同端口 rxRefs（与②b 引用行定位同源）；④发布 `EVENT_ORDER_CHARGED`（OrderChargedPayload：settlementId/settleNo/patientId/visitId/orderNos/rxNos/greenChannelFlag=false）；⑤visit 状态推进（PENDING_FEE→IN_CONSULT 状态机+log）；⑥appointment.fee_settlement_id 回填分发（挂号费结算：settleType=OUT 且该 visit 的 appointment UNPAID→fee_status=PAID+fee_settlement_id 回填——挂号费与就诊费同 visit 结算面）。
    - `onRefundApproved(payload)` order 分支（Task 6 appointment 分支之后追加）：`sourceRefsOfSettlement(payload.settlementId())` → orderRefs 逐单 `casStatus("CHARGED","CANCELLED")`（CHARGED/IN_EXECUTION 逆向仅在回执后——Spec :119）+visit_log；rxRefs 作废面由 order.cancelled 扇出承载 pharmacy（本模块不直改 pharmacy 表）；逐单发布 `EVENT_ORDER_CANCELLED`（OrderCancelledPayload：orderNo/patientId/visitId/rxRefs/reason="退费逆向终态确认"）。
    - `onPrescriptionCancelled(PrescriptionCancelledPayload p)`——RX_REF 行（ext_ref=p.rxNo()）按当前态 CAS 至 CANCELLED（CREATED→CANCELLED、PENDING_FEE→CANCELLED 或 CHARGED→CANCELLED——结算完成后作废的引用行已处 CHARGED 态，与②b 终态衔接，Spec :119 R2-10 回流驱动）。
    - `onDispenseCompleted/Returned`——「已发药」聚合：RX_REF 行新列 `dispense_status VARCHAR(16) NULL`（DISPENSED/PART_RETURNED/FULL_RETURNED 回流镜像，该列并入 V203 迁移原文——同 PR 未合入，执行时并回 Task 8 的 V203 文件）；引用行状态机五值不变，已发药为派生展示面（Spec :142「M06 发药回执→医生站/患者端可见已发药」）。

- [ ] **Step 1: 写测试（先红后绿）**

`ChargingServiceImplTest` 用例全集（断言值冻结）：

1. `settlementMarksCoveredOrdersChargedByExactRefs`——orderRefs=[OP...0001]：仅该单 CHARGED（visit 全量另单 CREATED 不动——单据精确断言核心）。
2. `settlementFansOutChargedWithRxList`——payload 八组件断言（rxNos 与端口返回逐字一致）。
3. `settlementIgnoresInpatientType`——settleType="IN"：零写零发布。
4. `settlementAdvancesVisitPendingFeeBackToInConsult`——状态机+log 断言。
5. `settlementMarksRegistrationFeePaid`——appointment UNPAID→PAID+fee_settlement_id 回填。
6. `refundApprovedRollsBackChargedOrdersAndFansOutCancelled`——orderRefs 两单：双 CANCELLED+双 order.cancelled 发布（reason="退费逆向终态确认"）。
7. `refundApprovedSkipsUnknownOrders`——orderRefs 无命中：零发布（幂等）。
8. `prescriptionCancelledMarksRxRefRowCancelled`——RX_REF ext_ref 命中：CANCELLED。
9. `dispenseCompletedMirrorsDispensedOnRxRef`——dispense_status=DISPENSED 回流镜像。
10. `settlementIdempotentOnRedelivery`——同 payload 重投：第二次全跳过（CAS 0 行重读 CHARGED info）。
11. `settlementMarksRxRefRowsChargedByExactRefs`——rxRefs 命中引用行 CREATED→CHARGED（ext_ref 精确定位，未列引用行不动——与用例 1 并列的单据精确断言）。

同任务补 billing 侧 PortImpl 转调单测（`SettlementQueryPortImpl` 位于 `com.fuyun.billing.service.impl`——父 POM 规则二 LINE=1.00 包，端口转调行覆盖由本测试承载）。`SettlementQueryPortImplTest` 用例全集：

1. `sourceRefsGroupsByTriggerPointSortedDeduped`——fee_record 混合 trigger 行：orderRefs=[OP…0001,OP…0002]（ORDER_CONFIRMED 组，去重升序）、rxRefs=[RX…0001]（PRESCRIPTION_EFFECTIVE 组）。
2. `settledUnderTrueWhenSettledFeeRowExists`——settlement(settleNo) JOIN fee_record(sourceRef) 命中 SETTLED 行：true。
3. `settledUnderFalseWhenNoMatchingRow`——无命中：false（Task 11 verify 凭证核验拒绝分支的输入侧）。

Run（预期红）：编译错误。

- [ ] **Step 2: 端口与消费链落码**

`SettlementQueryPortImpl`（billing 侧，构造注入 FeeRecordMapper/SettlementMapper——先实测 SettlementMapper 既有查询形态）；`ChargingServiceImpl` 落码含②b 引用行 CREATED→CHARGED 批量 CAS（与② orderRefs 并列同事务）；四监听器三段式落码（队列名逐字 `q.outpatient.billing.settlement.completed` 等四条）；V203 补列 `dispense_status`（并入 Task 8 迁移原文）；SUBSCRIBED_EVENT_TYPES 四条追加与监听器同任务。

- [ ] **Step 3: 跑绿 + 提交**

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-outpatient,fuyun-billing -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ChargingServiceImplTest,SettlementQueryPortImplTest'
```

Expected: 14 用例绿（11 收费编排 + 3 端口转调）。

```bash
git add backend/fuyun-outpatient backend/fuyun-billing
git commit -m "feat(outpatient,billing): 收费编排与退费逆向——单据精确放行扇出+回执回滚链

- SettlementQueryPort（sourceRefsOfSettlement/settledUnder）——裁决 5/6 反查唯一载体
- settlement.completed 按 orderRefs 单据精确 CHARGED（非 visit 全量扫描）+order.charged 扇出
- refund.approved order 分支回滚+order.cancelled 逐单扇出（终态确认，CF-4 载荷零变更）
- prescription.cancelled 回流 RX_REF 作废；dispense 回执已发药镜像列
- 挂号费收费回填 fee_settlement_id（收费工作台前端直调 billing 面，零资金逻辑）"
```

---

### Task 11: pharmacy 放行链回切与退费收口（releaseByRxNos / verify 凭证核验 / confirmRefundTerminal 单据化 / order.cancelled 作废路径）

**Files:**
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/service/IDispenseService.java:13-17`（releaseByVisit javadoc 与签名）
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/service/impl/DispenseServiceImpl.java:131-152`（releaseByVisit→releaseByRxNos）、`:240-260`（verify 入参扩展）、`:478-503`（confirmRefundTerminal 单据化重写）
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/internal/PharmacyChargedOrderListener.java:52-59`（读 rxNos 调新签名）
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/internal/PharmacyRefundApprovedListener.java:52-63`（经 SettlementQueryPort 反查 rxNos）
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/internal/PharmacyOrderCancelledListener.java:39-49`（占位留痕→实装作废路径）
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/controller/DispenseController.java:53-56`（verify 端点可选 body——行号 2026-09-20 实测：@PostMapping :53/方法签名 :55-56）
- Create: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/dto/VerifyCredentialRequest.java`
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/api/PharmacyErrorCode.java`（追加 PH-1017 CREDENTIAL_MISMATCH）
- Modify: 单测 `DispenseServiceImplTest`（releaseByVisit 用例改名改断言/verify 凭证三用例/confirmRefundTerminal 重写断言/order.cancelled 作废路径用例）+ `PharmacyDispenseGuardIT`/`PharmacyPrescriptionFlowIT` 注入帧形态演进（publishCharged 改携 rxNos）

**Interfaces:**
- Consumes: `OrderChargedPayload.rxNos`（Task 10）；`OrderCancelledPayload.rxNos`（Task 10）；`SettlementQueryPort.settledUnder/sourceRefsOfSettlement`（Task 10）；`RefundApprovedPayload.settlementId`（V605 id 20）。
- Produces（裁决 4/5/8 与 PR-4 注记⑥⑦⑧四点全闭合）:
  - `IDispenseService.releaseByVisit(String visitId)` **删除**，替换 `void releaseByRxNos(java.util.List<String> rxNos)`——逐 rxNo：PENDING_FEE+OUTPATIENT/EMERGENCY 定位处方 → 逐单 CAS 转 PENDING_DISPENSE 并创建发药单（原 visit 维度全量放行单据化，`IDispenseService.java:15` javadoc 预告兑现）；空清单 info 跳过。
  - `PharmacyChargedOrderListener`：payload 读 `rxNos` 数组；`visitId` 字段守卫维持（不合规帧双字段校验）；`rxNos` 空数组=该结算无药品行（纯检查/检验结算）合法 info 跳过。
  - `verify(String dispenseNo, String credential)`（credential 可空，null 安全）：非空时 `settlementQueryPort.settledUnder(credential, d.getRxNo())` false → PH-1017（409，文案=`取药凭证与处方归属不一致`——裁决 8 核验语义：凭证（settlementNo）与处方归属一致性）；空=跳过凭证核验（追溯码核验维持防回流主道——PR-4 注记⑧豁免面就此闭合）。
  - `confirmRefundTerminal(long patientId)` **删除**，替换 `void confirmRefundTerminalByRx(java.util.List<String> rxNos)`——逐 rxNo 定位处方+其发药单：DISPENSED 态处方按发药单终态镜像 PART/FULL_RETURNED（原语义单据化）；`PharmacyRefundApprovedListener` 消费 refund.approved 后 `sourceRefsOfSettlement(payload.settlementId()).rxRefs()` 反查传递（空=无药品退费，info 跳过）；**同患者两处方仅目标处方收敛**（PR-4 注记⑦ ledger 移交项闭合断言，Task 12 IT 验收）。
  - `PharmacyOrderCancelledListener` 实装（PR-4 注记⑥回切）：payload 读 `rxNos[]`——逐 rxNo：PENDING_DISPENSE/DISPENSING 态且无活动发药单（dispense 无 CREATED/PICKING/PICKED 行）→ `casStatus(现态,"CANCELLED")`+发药单同步 CANCELLED+批次锁定释放（`releaseLock` 循环，复用 DISPENSING_CANCEL 退场段形态）；DISPENSED 态→已由 refund.approved 双通道终态（幂等 info）；`reason` 透传日志。
- 既有用例演进清单（禁留旧签名用例）：`releaseByVisit*` 全部改名改断言（`releaseByRxNosReleasesExactlyListedPrescriptions` 等）；`confirmRefundTerminal*`→`confirmRefundTerminalByRx*`（新增「同患者两处方仅目标收敛」用例）；GuardIT/FlowIT `publishCharged(visitId, traceId)`→`publishCharged(visitId, rxNos, traceId)`（payload 加 rxNos 数组）；verify 相关 POST 助手增可选 body 通道。

- [ ] **Step 0: 现状核验（先实测）**

```bash
grep -n "releaseByVisit\|confirmRefundTerminal\|public void verify" backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/service/IDispenseService.java backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/service/impl/DispenseServiceImpl.java
# 预期：IDispenseService:17 / DispenseServiceImpl:131/:240/:478 与 2026-09-20 撰写期核验一致；漂移则以实况为准修正 Files 行号
grep -rn "releaseByVisit\|confirmRefundTerminal(patientId" backend/fuyun-pharmacy/src/test --include="*.java" -l   # 既有用例演进面清单
```

- [ ] **Step 1: 写测试（先红后绿）**

用例全集（断言值冻结）：

1. `releaseByRxNosReleasesExactlyListedPrescriptions`——同 visit 两 PENDING 处方、rxNos 仅含其一：仅目标单 CAS+建发药单（单据精确核心断言）。
2. `releaseByRxNosSkipsEmptyList`——空清单零调用。
3. `verifyWithMatchingCredentialPasses`——settledUnder true：PICKED+verifier 回填。
4. `verifyWithMismatchedCredentialRejected`——false：PH-1017。
5. `verifyWithoutCredentialSkipsCheck`——null：`verifyNoInteractions(settlementQueryPort)`+追溯码主道不变。
6. `confirmRefundTerminalByRxMirrorsOnlyListedRx`——同患者两 DISPENSED：仅列表内处方镜像 FULL_RETURNED。
7. `orderCancelledVoidsUndispensedPrescription`——PENDING_DISPENSE+无活动发药单：CANCELLED+发药单 CANCELLED。
8. `orderCancelledReleasesBatchLocksForDispensing`——DISPENSING 态：释放锁定+明细 CANCELLED。
9. `orderCancelledIdempotentForDispensedRx`——DISPENSED：零写 info（双通道已收敛）。
10. `chargedListenerToleratesEmptyRxNos`——rxNos=[]：info 跳过零放行；`chargedListenerStillRejectsMissingVisitId`（守卫回归）。

Run（预期红）：`releaseByRxNos` 不存在编译错误：

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-pharmacy -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='DispenseServiceImplTest'
```

- [ ] **Step 2: 实现落码**

按 Interfaces 五点逐一落码；两既有 IT 的合成信封注入函数同步演进（payload 增 rxNos 数组、退费断言改单据化口径——两 IT 随本任务保持绿，Task 12 三 IT 新建不复用其容器）。

- [ ] **Step 3: 跑绿 + pharmacy 门禁复验 + 提交**

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-pharmacy -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='DispenseServiceImplTest,PrescriptionServiceImplTest'
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-app -am verify -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test='PharmacyDispenseGuardIT,PharmacyPrescriptionFlowIT'
```

Expected: 全绿——pharmacy impl LINE=1.00 维持（旧方法删除后其用例同步删除，覆盖率不回退）；两既有 IT 演进后绿。

```bash
git add backend/fuyun-pharmacy
git commit -m "refactor(pharmacy): PR-4 四点回切——单据精确放行/退费收口/凭证核验/作废路径

- releaseByVisit→releaseByRxNos（charged 载荷 rxNos 精确放行，裁决 4）
- confirmRefundTerminal→ByRx（refund.approved 经 SettlementQueryPort 反查，裁决 5；
  同患者两处方仅目标收敛——ledger 移交项闭合）
- verify 可选凭证核验（settlementNo 与处方归属一致性，裁决 8；PH-1017）
- order.cancelled 实装未发药作废/批次锁释放（注记⑥回切）"
```

---

### Task 12: 三验收锚点 IT（OutpatientFullFlowIT 全链 + OutpatientRefundRollbackIT 退号回滚 + OutpatientPoolConcurrencyIT 并发抢号）

**Files:**
- Create: `backend/fuyun-app/src/test/java/com/fuyun/app/OutpatientFullFlowIT.java`
- Create: `backend/fuyun-app/src/test/java/com/fuyun/app/OutpatientRefundRollbackIT.java`
- Create: `backend/fuyun-app/src/test/java/com/fuyun/app/OutpatientPoolConcurrencyIT.java`

**Interfaces:**
- 形态基座：三容器类级独占 + FuyunStackITBase（PharmacyPrescriptionFlowIT :60-130 逐字同型）；合成信封注入（BillingSettlementFlowIT :230-245 形态）；三 IT 按 PR-3 裁决②层次（用例名+冻结断言清单+形态引用）撰写，断言目标/造数链/幂等面逐条列明。
- Consumes: Task 1–11 全部交付面；造数：`jdbcTemplate` 直插计费项目与价格（billing.charge_item+charge_item_price，BillingSettlementFlowIT preparePrice 同款）、药品+批次+价格（PharmacyPrescriptionFlowIT :356 prepare 同款）、`outpatient.schedule_template`（SQL 直插）与患者（patient.patient SQL 直插或建档 API）。
- 事件断言通道：先实测取实况——`grep -n "received_event\|CAPTURED\|EventPublication" backend/fuyun-app/src/test/java/com/fuyun/app/BillingSettlementFlowIT.java | head`（ItCaptureConfig 手工捕获队列先例）与 `grep -n "integration.received_event" backend/fuyun-app/src/test/java/com/fuyun/app/*.java | head -3`，二者择一贯穿三 IT。

- [ ] **OutpatientFullFlowIT（验收锚点①：门诊挂号→就诊→开单→收费→发药→诊毕直线段真栈）** 用例序列（@Order）：

1. `prepareMasterData`——项目两枚（LAB-001 单价 3000 分/DRUG 关联 itemCode 单价 2000 分）+药品+批次+排班模板直插；V705 字典在位断言（dict_item 三 type 共 19 条 count）。
2. `windowRegistrationIssuesVisitAndPublishesRegistered`——POST /appointments（WINDOW 渠道）→ 200：appointmentNo/visit_id=`O+今日+00001` 形态；visit.registered 帧在位断言。
3. `checkInAndCallTicket`——check-in→WAITING；queue/call→CALLED+REST 快照 `GET /queues/{dept}/tickets` 首行票号一致；admit→IN_CONSULT+admitted_at 非空。
4. `createOrderGeneratesPendingFeesViaRealPublisher`——POST /visits/{visitId}/orders（LAB-001×2）→ **真实发布** order.created（非注入）：轮询断言 billing `GET /fees?visitId=` 出现 PENDING 行（sourceRef=orderNo、trigger=ORDER_CONFIRMED）；visit 推进 PENDING_FEE。
5. `openPrescriptionRegistersRxRefAndGeneratesDrugFees`——doctordemo 登录（V704 种子口令，登录态 userId=3 与 V704 employee_id 对齐——Task 2 Step 2 身份链口径）POST /visits/{visitId}/prescriptions → rxNo 返回（practice check 经真实种子放行）；RX_REF 行 ext_ref=rxNo 且明细零行；billing 费用出现 sourceRef=rxNo 行（pharmacy.prescription.created 真实链）。
6. `settleVisitFeesAndFanOutCharged`——preview→settle（CASH 勾稽）→ 轮询断言：两申请单 CHARGED、RX_REF 引用行 CHARGED（ext_ref=rxNo——Task 10 ②b 引用行终态断言）、处方 PENDING_DISPENSE（**真实 order.charged 精确放行**——PR-4 注入帧通道废止的回切实证）、visit 回 IN_CONSULT。
7. `dispenseThroughVerifyWithCredential`——pick（追溯码）→ verify 携 settlementNo（归属一致）→ issue → RX_REF dispense_status=DISPENSED 断言。
8. `finishVisitWithDisposition`——finish（disposition=DISCHARGE_HOME）→ FINISHED+finished_at；visit.finished 帧在位；**诊毕后开单拒**（POST orders → 409 OP-1011 断言）。

- [ ] **OutpatientRefundRollbackIT（验收锚点②：退号→退费→放行回滚）** 用例序列：

1. `preparePaidVisitWithTwoDocs`——挂号（挂号费 manual+settle）+开检查单 A+开方 B→settle 全部→两单 CHARGED+处方 PENDING_DISPENSE（FullFlow 步骤 2–6 造数链紧凑版）。
2. `dispensedReturnTriggersRefundAndRollback`——退药受理（追溯码核验）→ 免审退费 apply→refund.approved 立达（DAY_CORRECTION autoApproved=true）→ 断言：`order.cancelled` 帧 rxNos=[rxNo]（精确清单）、pharmacy 处方 FULL_RETURNED（Task 11 双通道）、clinic_order A 保持 CHARGED（**同 visit 另单不受牵连**——患者维度误伤面闭合验收断言，裁决 5）、批次回补勾稽。
3. `unpaidReservationCancelReleasesWithoutBilling`——portal 预约（未支付）→ cancel → 池 used_count 回 0+无 refund 帧+appointment.cancelled feeRefundTriggered=false。
4. `timeoutNoShowReleasesPoolAndRecordsCredit`——`@DynamicPropertySource` 覆盖 `fuyun.outpatient.appointment-timeout=PT2S`（registerSecrets 同型追加）→ 预约后等 3s：NO_SHOW+池回补+credit 行 restrict 写入断言。

- [ ] **OutpatientPoolConcurrencyIT（验收锚点③：放号瞬间并发抢号零超卖）** 用例：

1. `concurrentBookingNeverOversellsPool`——池 total_quota=5：`ExecutorService` 20 线程并发 POST /appointments（同 pool、20 患者直插建档）→ 断言：成功恰 5 单、pool.used_count=5（DB）、Redis 余量键=0、失败方 409（OP-1003/OP-1005）、appointment 表 count=5；全链 wall time <30s（Spec :209 快速失败边界项）。
2. `duplicateBookingSameDaySameDeptRejected`——同患者同日同科第二单：409 OP-1005 且 used_count 不变。

- [ ] **Step 1: 三 IT 落码并逐个单跑**

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-app -am verify -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test='OutpatientPoolConcurrencyIT'
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-app -am verify -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test='OutpatientRefundRollbackIT'
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-app -am verify -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test='OutpatientFullFlowIT'
```

Expected: 三 IT 各自全绿（类级独占容器互不串扰）。

- [ ] **Step 2: 提交**

```bash
git add backend/fuyun-app/src/test
git commit -m "test(outpatient): 三验收锚点 IT——全链直线段/退号回滚/并发抢号零超卖

- FullFlow：真实发布链（order.created/prescription.created/charged 非注入）端到端
- RefundRollback：先退药后退费+order.cancelled 精确清单+另单不受牵连断言
- PoolConcurrency：20 并发抢 5 号零超卖+限购拦截+快速失败"
```

---

### Task 13: 前端三应用（workstation 三页 / portal 免登录预约页 / bigscreen 叫号页 + api.d.ts 重生成）

**Files:**
- Modify: `web/packages/shared/src/api.d.ts`（`pnpm gen:api` 重生成——P-7 流程）
- Create/Modify: `web/apps/workstation/src/api/outpatient.ts`、`views/outpatient/RegistrationChargeView.vue`（挂号收费联动页）+`.spec.ts`、`views/outpatient/TriageBoardView.vue`（分诊台）+`.spec.ts`、`views/outpatient/DoctorStationView.vue`（门诊医生站）+`.spec.ts`、`router/index.ts`（三路由）、`views/layout/components/AppSidebar.vue`（「门诊服务」菜单组三项）
- Create/Modify: `web/apps/portal/src/api/http.ts`、`api/outpatient.ts`、`views/appointment/AppointmentView.vue`+`.spec.ts`、`router/index.ts`
- Create/Modify: `web/apps/bigscreen/src/composables/useQueueStomp.ts`+`.spec.ts`、`views/queue/QueueBoardView.vue`+`.spec.ts`、`router/index.ts`

**Interfaces:**
- `api/outpatient.ts`（workstation）：类型别名全走生成物 `components['schemas']`（pharmacy.ts :18-30 形态）；函数面=排班模板 CRUD/放号生成/停诊恢复/余量查询/预约/取号/退号/改期/报到/调级/叫号/过号/重呼/队列快照/接诊/诊毕/开单/开方/申请单查询——逐函数 `http.<verb>` 薄封装；金额与数量 string 零运算（A.3-6）。
- 挂号收费联动页（RegistrationChargeView）：选患者（复用 patient search 组件）→ 选排班/号别（number-pools/available）→ 挂号（WINDOW 渠道，TAKEN 直出 visit_id）→ 挂号费收费（复用 billing.ts manualCharge/preview/settle——资金面全走既有 billing api，零金额运算）→ visit 费用与状态展示；动作按钮 loading+在途守卫+零出网用例（W-22⑥ 合规形态自带）。
- 分诊台页（TriageBoardView）：队列快照表（脱敏姓名/票号/优先级/状态，REST 轮询 5s）+报到（visitId 输入+显式格式校验）+调级/转队列+叫号/过号/重呼按钮组。
- 医生站页（DoctorStationView）：候诊列表（patientQueue）→ 接诊 → 患者上下文（visit 详情+费用+处方引用行含「已发药」镜像列）→ 开单（项目码+数量表单，quantity 显式数字校验 4xx 提示——W-22⑦ 禁裸 parse）/开方（复用 pharmacy.ts createPrescription）→ 诊毕（去向下拉=V705 disposition 八项前端常量清单+在途单据显式确认勾选+M09 提醒不拦截注记）。
- portal（裁决 13 免登录）：`http.ts` 从零建——抄 workstation 形态**去 auth store 依赖**（无 token 注入、无 401 回调注册；保留 X-Trace-Id 注入与 ProblemDetail 错误出口，401 恒不触发于匿名通道）；`AppointmentView`：就诊卡号/证件号二选一输入（显式格式校验：证件 18 位规则/卡号非空，4xx 提示）→ portal 号源查询 → 提交预约 → 出票展示（apptNo+支付时限倒计时文案）。
- bigscreen：`useQueueStomp` 复用 useIotStomp 连接范式（buildBrokerUrl 改 `/ws/outpatient`、connectHeaders Bearer）；大屏为受控演示面，令牌经构建期 `VITE_BIGSCREEN_TOKEN` 注入（默认空=页面显示「未配置大屏令牌」横幅且零出网——订阅级鉴权/匿名 STOMP 通道随 P2 演进注记）；`QueueBoardView`：输入 deptCode → REST 快照首屏 → 订阅 `/topic/outpatient/queue/{deptCode}` 叫号列表（票号/诊室/状态）≤2s 刷新（Spec :198）。
- 路由/菜单：workstation 三路由 meta `{ permission: 'outpatient:registration:register' / 'outpatient:triage:manage' / 'outpatient:doctor:consult' }` 语义登记（403 接线 P-later 注记，patient 三页先例）；portal 路由 meta `{ public: true }`。

- [ ] **Step 1: api.d.ts 重生成（P-7，先实测）**

```bash
# 存量 dev 卷一次性重置（Task 1 CHANGELOG 登记的进入条件，执行时点前移至本步——api-docs 导出
# 要求 backend 在含 V200–V204 的新卷上启动，旧卷 Flyway outOfOrder=false 必拒 pending 迁移；
# 重置后全新卷按版本升序一次应用 V200–V204/V704/V705，Task 15 真栈复用该卷）
docker compose -f deploy/docker-compose.yml --env-file deploy/.env down -v
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-app -am package -DskipTests
docker build -f backend/Dockerfile -t fuyun/backend:dev backend
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --force-recreate backend
sleep 30
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres psql -U fuyun -d fuyun -c "SELECT count(*) FROM flyway_schema_history WHERE (script LIKE 'V2%' OR script LIKE 'V70%') AND success = true;"   # 预期：7（V200–V204/V704/V705 全部应用成功——重置生效前提）
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T backend sh -c "curl -s http://localhost:8080/v3/api-docs" > web/api-docs.json
cd web && pnpm gen:api && git diff --stat packages/shared/src/api.d.ts
```

Expected: 迁移核验 7 行 success；diff 仅新增 outpatient 域 schema；**载荷 record 是否进生成物先实测**：`grep -c "FeeCreatedPayload" web/packages/shared/src/api.d.ts`——若 billing 载荷未出现（springdoc 只收 controller 引用面），outpatient 载荷同样不会；若出现则与 PR-3 口径一致接受并登记 PR 描述。

- [ ] **Step 2: workstation 三页 + api 层落码**（`<script setup lang="ts">` 零例外、零 any、生成物类型唯一来源；每页 spec 至少含渲染断言/动作在途守卫断言/零出网断言三件）

- [ ] **Step 3: portal + bigscreen 落码**（portal http.ts spec：请求头无 Authorization 断言；useQueueStomp spec：未配置令牌零连接断言+brokerURL=`ws://…/ws/outpatient` 形态断言）

- [ ] **Step 4: 前端五连门禁**

```bash
cd web && pnpm lint && pnpm format:check && pnpm type-check && pnpm test && pnpm build
```

Expected: 五连全绿。

- [ ] **Step 5: 提交**

```bash
git add web
git commit -m "feat(web): 门诊三前端——workstation 三页+portal 免登录预约+bigscreen 叫号

- api.d.ts 重生成（outpatient 域 schema 新鲜度核对登记 PR 描述）
- 挂号收费联动/分诊台/医生站三页（W-22⑥⑦ 合规形态自带）
- portal 免登录基座（无 token 注入）+介质解析预约页；bigscreen /ws/outpatient 订阅"
```

---

### Task 14: 合入前全量门禁（backend verify 全模块 + 前端五连复核 + 覆盖率双阈值核对）

**Files:** 无新增（门禁修正如有则限 Task 1–13 交付面内微调）。

- [ ] **Step 1: 后端全量门禁**

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml verify
```

Expected: BUILD SUCCESS——全模块绿；`com.fuyun.outpatient.service.impl` PACKAGE LINE=1.00 达标（聚合报告核对形态照 pharmacy 先例：`grep -n "com.fuyun.outpatient.service.impl" backend/target/site/jacoco-aggregate/jacoco.xml`——先实测聚合报告落点）；pharmacy/billing LINE=1.00 不回退；Modulith `ApplicationModules.verify()` 随 fuyun-app 门禁通过（outpatient 入图，双向依赖禁面校验）。

- [ ] **Step 2: 前端五连复核**——`cd web && pnpm lint && pnpm format:check && pnpm type-check && pnpm test && pnpm build` 五连绿 + api.d.ts 新鲜度复验（`git diff --exit-code web/packages/shared/src/api.d.ts`，输出空即通过）。

- [ ] **Step 3: 台账记录**——门禁输出摘要记入 `.superpowers/sdd/2026-09-20-p1-pr5-m03-outpatient/` 台账（无修正则本任务零提交）。

---

### Task 15: 收口（Spec 注记 / TASK.md / CHANGELOG / 真栈探针 / 浏览器真机 / PR 与质量门）

**Files:**
- Modify: `docs/specs/modules/03-outpatient.md`（§7 后追加 P1·PR-5 落地注记块）
- Modify: `docs/specs/modules/06-pharmacy.md`（§7 落地注记六点回填）
- Modify: `CHANGELOG.md`（收口条目）
- Modify: `TASK.md`（本 PR 新登记项回填）

- [ ] **Step 1: 03-outpatient Spec 注记（节选要点，全文按此八点展开）**：①事件 id 23/25/31 冻结+id 32–40 登记与实装面（id 35 无发布点注记；queue.called 不登记=纯 WS）；②号源池 V200 三表与双道闸/降级口径；③visit_id 签发 Redis 当日键（裁决 11）；④退号四分支与免审档统一通道（裁决 7）、W-20 规避注记（挂号费>0 演示数据）；⑤WS 自建 configurer 与 `/ws/outpatient` 端点+两 topic+REST 快照（裁决 12；Spec :179 通道表述 `/ws/outpatient/queue/{queueId}` 的显式映射=STOMP 端点 `/ws/outpatient` + 目的地 `/topic/outpatient/queue/{deptCode}`（queueId=dept_code，偏差⑧）——沿 iot 范式的「端点/目的地」两段式，Spec 路径字面非直连 URL，注记须写明该转译关系）；⑥portal 匿名通道 `/api/v1/outpatient/portal/**`（裁决 13，患者账号体系随 M18/P6 注记）；⑦演示终点直线段声明（裁决 0——IN_EXECUTION/PENDING_MEDICATION/NO_SHOW 声明态）；⑧排除面回执（FU-M03-09/10/11 与 P1 计划 :108-110 对齐）。

- [ ] **Step 2: 06-pharmacy §7 注记回填（:205-222 注记块整块改写）**：①order.charged/cancelled 生产发布方回切=PR-5 已交付（本 PR）；②practice/check 双端接线完成（TODO(PR-5) 已清除）；⑥PENDING_DISPENSE/DISPENSING→CANCELLED 作废路径已随 order.cancelled 实装；⑦confirmRefundTerminal 已按「billing api 端口单据化精确定位」收口（SettlementQueryPort，CF-4 载荷零变更——两候选路径落定记录）；⑧取药凭证载体=settlementNo 落地（verify 可选核验，豁免面闭合）。②③④⑤ 五条不动（PR-4 交付面）。

- [ ] **Step 3: TASK.md 回填**——核验无本 PR 新登记残留（W-19/W-20/D-19 维持现状不动）；如执行期新发现按登记规则追加。

- [ ] **Step 4: CHANGELOG 收口条目**——交付面/门禁记录/裁决落实（14 条对照）/偏差与待批处置结论，格式照 2026-09-18 PR-4 收口条目。

- [ ] **Step 5: 真栈探针（存量卷已于 Task 13 Step 1 重置——Task 1 进入条件兑现；此处仅确保栈在位后探测，不再重复 `down -v`）**

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-app -am package -DskipTests
docker build -f backend/Dockerfile -t fuyun/backend:dev backend
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --force-recreate backend
sleep 30
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres psql -U fuyun -d fuyun -c "SELECT version, script, success FROM flyway_schema_history WHERE script LIKE 'V2%' OR script LIKE 'V70%' ORDER BY version;"   # 预期：V200–V204/V704/V705 全 success=true
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres psql -U fuyun -d fuyun -c "SELECT count(*) FROM integration.event_registry;"   # 预期：40
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres psql -U fuyun -d fuyun -c "SELECT payload_desc FROM integration.event_registry WHERE id = 23;"   # 预期：含「CF-5 冻结，PR-5 实装」（新库双形态终态实证）
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T backend sh -c "curl -s http://localhost:8080/v3/api-docs | head -c 200"   # 预期：JSON 在位（后端健康）
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres psql -U fuyun -d fuyun -c "SELECT count(*) FROM system.practice_grant WHERE status='EFFECTIVE';"   # 预期：3
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T backend sh -c "curl -s -X POST http://localhost:8080/api/v1/system/practice/check -H 'Content-Type: application/json' -d '{\"employeeId\":3,\"grantType\":\"PRESCRIPTION\"}'"   # 预期：401（匿名）——经登录令牌重放预期 passed=true（演示医师种子闭环；employeeId=3=doctordemo 的 userId/employee_id 对齐值，Task 2 Step 2 身份链口径）
```

- [ ] **Step 6: 浏览器真机（三应用 UI 面，质量门不可跳过）**——playwright-cli 驱动：workstation 登录 doctordemo→挂号收费联动页走通一单（挂号→收费）→分诊台报到/叫号→医生站接诊/开单/开方/诊毕→bigscreen 叫号页显示队列→portal 免登录预约一单；截图存 `.superpowers/gui-test-screenshots/`（不入提交面）；WS 推送 ≤2s 断言（点击叫号至大屏刷新计时）。

- [ ] **Step 7: PR 与质量门**

1. PR body 按 `.superpowers/` 既有 body 模板要素（验收锚点三 IT 与真机截图清单；前置项收口对照 P-0~P-9；CF-5 双向评审声明——id 23/25/31 载荷冻结；真栈探针记录含 V200–V204/V704/V705 success、event_registry=40；api.d.ts 新鲜度核对；质量门自证：全量门禁输出摘要+三应用浏览器证据路径）；`gh pr create --base dev`。
2. `/code-review` 插件审核 findings 清零后方可合并（PR-2 交接 §3，不可跳过）；main/dev 分支保护 required checks 对齐。

---

## 自审记录（writing-plans Self-Review，2026-09-20）

**1. Spec 覆盖对照（03-outpatient.md，PR-5 范围 = FU-M03-01~08 P0 + practice/check + PR-4 回切 + 三前端）**：

- FU-M03-01（Spec :135）：排班模板/放号规则参数化 → V200 schedule_template+Task 4 generate ✓；排班日历批量生成与手工调整 → Task 4 ✓；停诊整池作废+已约患者联动 → Task 4 stop（批量通知改期或退费=M01 通知中心缺位注记，退费联动链在位）✓；加号额度 → extra-quota+extra_used ✓；号源占用/释放全量留痕 → appointment/credit 行+@AuditLog+池行 CAS（version 递增审计）✓；余量对外查询 API → available ✓。
- FU-M03-02（:136）：多渠道统一预约 API → POST /appointments（channel 词表六值，P1 实装 WINDOW/KIOSK/PORTAL）✓；并发防护 → 双道闸+占位支付时限+延迟释放（Task 4/5）✓；限购与爽约信用 → uk_appt_patient+credit 限约拦截（Task 5 用例 7/8）✓；通知触达 → not-in-scope 注记（M01 通知中心缺位）✓。
- FU-M03-03（:137）：当日挂号一步取号 → Task 5 用例 1 ✓；改期 reschedule_of 链先占新后退旧 → Task 6 用例 9 ✓；退号规则参数化 → onlineCancelBeforeDays+OP-1010+窗口口径（用例 5）✓；退号退费联动 → refund.approved 驱动终态（Task 6 分支 2/3+用例 6/7）✓；挂号有效期三天模式 → not-in-scope 注记（回诊段顺延）。
- FU-M03-04（:138）：报到双入口 → check-in API（station_id 承载自助终端位）✓；二次分诊跨队列转接重排 → Task 7 用例 6 ✓；优先级调整（急诊分级/老幼残）→ 优先级公式+调级（用例 2/3/5）✓；报到时间国标采集 → visit.checked_in_at ✓；绿通置顶 → 排除面注记（公式声明值 900 随排除面不触发）。
- FU-M03-05（:139）：队列模型（排队表+Redis ZSET+WS 推送）→ Task 7 ✓；叫号联动 → call/pass/recall ✓；过号降级重排不改号 → 用例 5/9 ✓；大屏刷新 ≤2s → WS 推送+Task 15 真机计时 ✓；患者端提醒 → queue.called 不登记注记（M01 通知缺位）✓；队列恢复（Spec :210）→ Task 7 ZSET 键缺失惰性重建（rebuildIfMissing+selectWaiting WAITING 权威行回填+用例 12/13 重启恢复与幂等两例——实现级承载，非仅注记）✓。
- FU-M03-06（:140）：候诊列表+过敏标识位 → patientQueue（过敏标识 P1 恒 false 注记）✓；接诊/诊毕+前置校验 → Task 8（M09 文书校验参数化提醒不拦截=not-in-scope）✓；开单 → clinic_order+order.created ✓；处方调 M06 开方 API+执业授权强校验 → Task 9 双端 ✓；结果查看/转住院 → M07/M08/M04 缺位排除注记；接诊时间国标 → admitted_at ✓。
- FU-M03-07（:141）：收费工作台 → workstation 挂号收费联动页复用 billing 划价结算面（后端零资金逻辑，裁决 7）✓；消费 settlement.completed 放行 → Task 10 ✓；退费发起与执行占用前置 → M13 既有链+Task 11 单据化 ✓。
- FU-M03-08（:142）：charged→引用行 CHARGED→扇出放行 → Task 10 ②b（rxRefs 引用行 CREATED→CHARGED 批量 CAS+用例 11+FullFlow 步骤 6 引用行终态断言；绿通挂账场景随排除面，greenChannelFlag 声明列恒 false）✓；发药回执回流 → dispense_status 镜像 ✓；退药退费先退药后退费 → Task 12 RefundRollback ✓；order.cancelled 终态确认 → Task 10/11 ✓。
- practice/check 真实化（01-system.md :58/:69/:80/:94）→ Task 2（V704+管理端点+check 真实+practice.changed 发布）；D-16（02-patient.md :140）→ Task 2 SPI+门禁+Task 8 注册实现 ✓。
- PR-4 移交面（06-pharmacy.md §7 注记 :205-222）：①→Task 3/10；②→Task 9；⑥→Task 11；⑦→Task 11；⑧→Task 11——五点全闭合 ✓。
- §10 测试要点（PR-5 范围内）：并发抢号零超卖/限购拦截/快速失败（PoolIT）✓；支付时限到期与支付成功并发（timeout CAS 先到先得+Timeout 用例）✓；退号时限边界（OP-1010 用例）✓；重复投递幂等（Charging 用例 10+Task 11 双通道）✓；重复退号拦截（回执前 RESERVED 保持+用例 2/3）✓；已发药退费全链（RefundRollback）✓；诊毕在途单据显式确认（Visit 用例 3/4）✓；无权限角色 403（PRACTICE_CHECK_FAILED/403 面，RBAC 全量 403 属 P1-later 注记）✓。

**2. 占位符扫描**：`grep -nE "同上|类似 Task|TBD|待定|适当处理|落码订正|残片|禁落码|\.\.\.|省略" docs/superpowers/plans/2026-09-20-p1-pr5-m03-outpatient.md`——执行时复验；已知的合法形态仅一处：三 IT 采用 PR-3 裁决②紧凑层次（用例名+冻结断言清单+形态引用精确到模板文件行号），与 PR-4 Task 11 同档获主控认可之层次。SQL 迁移 V200/V201/V202/V203/V204/V704/V705 全文逐条落死（R1 修复后 V705=25 条 INSERT 全文展开：3 type+3 version+19 item，无「同型展开规则」压缩面残留）；错误码 19+2+1 枚举值逐字给定；事件 desc 12 行逐字给定；无「适当处理/详见 XX」句式。

**3. 类型一致性抽查清单**：

- `OrderCreatedPayload(orderId, patientId, visitId, lines[]{itemCode,quantity})` = V204 id 23 desc = Task 8 发布构造 = billing BillingChargeEventListener 读取字段（:64 `sourceRefField="orderId"`、:93-96 三要素守卫、:107-108 itemCode/quantity）逐字对齐 ✓；quantity string 与 `new BigDecimal(line.path("quantity").asText("0"))` 兼容 ✓。
- `OrderChargedPayload(settlementId, settleNo, patientId, visitId, orderNos, rxNos, greenChannelFlag)` = V204 id 25 desc = Task 10 发布 = Task 11 监听器读取（rxNos 数组）= Task 12 FullFlow 步骤 6 断言 ✓。
- `OrderCancelledPayload(orderNo, patientId, visitId, rxNos, reason)` = V204 id 31 desc = Task 10 发布 = Task 11 order.cancelled 实装读取 ✓。
- `SettlementSourceRefs(settlementId, orderRefs, rxRefs)` = SettlementQueryPort 返回 = Task 10 消费 = Task 11 反查 = Task 12 RefundRollback 断言——四处同源 ✓；`settledUnder(settleNo, sourceRef)` = Task 11 verify 消费 ✓。
- visit 状态字面量九对迁移 = `VisitStateMachine` 静态集 = VisitMapper.casStatus 调用点（Task 5/7/8/10）= V201 列注释 = 状态机红线 Global Constraints 逐字一致 ✓；appointment 四态/clinic_order 六态/queue_ticket 六态与各 CAS 字面对齐 ✓。
- 队列名 `q.outpatient.<事件三段名>` = ConsumerQueueSpec 拼装（MODULE+eventType）= 四监听器 @RabbitListener 字面 = Task 15 真栈探针（可选扩展队列计数）；延迟链 `delay.appointment-timeout` 队列名 = DelayQueueSpec.business 拼装 = fy.delay 路由键 = Task 5 投递路由 ✓。
- grant_type 五值 = V704 列注释 = PracticeGrantCreateRequest @Pattern = Task 8/9 校验调用字面 = V704 种子三行 ✓；PH-1016/PH-1017/SYS-1021/OP-1001~1019 接续无重号（PH 至 1015、SYS 至 1014 实测）✓。
- `OutpatientProperties.appointmentTimeout` = OutpatientMessagingConfig 延迟档位 TTL = appointment pay_deadline 计算 = Task 12 IT `@DynamicPropertySource` 覆盖键 `fuyun.outpatient.appointment-timeout`（relaxed binding）——四处同源 ✓。
- Redis 键四族（pool/visit-seq/pay-hold/queue）= 各 impl 字面 = Global Constraints 键规范 = W-22① fy: 前缀+TTL 双条款 ✓。
- V704 种子身份链（R1 修复后）：employee_id=3 = sys_user.id=3（doctordemo）= 运行态 operator（AuthTokenInterceptor :76 注入 userId，Task 8/9 校验入参 Long.parseLong 直作 employeeId）= Task 12 FullFlow 步骤 5 登录态 = Task 15 探针 employeeId:3——五处同源 ✓。
- rxRefs（R1 修复后）= SettlementSourceRefs.rxRefs（端口反查）= Task 10 ②b 引用行 CAS 定位 = OrderChargedPayload.rxNos 扇出 = Task 11 放行/作废清单 = Task 12 FullFlow 步骤 6 引用行 CHARGED 断言——五处同源 ✓。
- QueueZsetStore.rebuildIfMissing 重建 score 与入队冻结公式同源（priority_score*1e8+queue_seq）、TTL 同规范（当日末+2h）——恢复队列与常规队列序一致 ✓。
- 事件 id 全局排定 23/25/31 冻结+32–40 新增+总行 40 = V204 种子 = MessagingGovernanceIT 断言 = 06-pharmacy/03-outpatient 注记 = Task 15 探针四处一致 ✓。

**4. recon 14 条裁决落实核对**：范围界定（裁决 0→文档头 not-in-scope+演示终点声明）✓；号段（1→P-1/Task 1，practice_grant 改道 V704 见偏差②）✓；jacoco（2→Task 1）✓；id 23/25/31 冻结（3→P-3/Task 3，双形态见偏差①）✓；放行链回切（4→Task 11，orderId→rxNos 映射取「载荷携带」案）✓；confirmRefundTerminal 收口（5→Task 10 端口+Task 11 替换）✓；refund 映射 M03 自查（6→Task 10）✓；退号退费统一免审档（7→Task 6）✓；凭证载体 settlementNo（8→Task 11 verify）✓；practice/check 真实化（9→Task 2/9）✓；D-16（10→Task 2/8）✓；visit_id Redis 键（11→Task 5）✓；WS 自建（12→Task 7）✓；portal 免登录（13→Task 5 白名单+Task 13）✓；W-22 前置（14→P-0/Task 1）✓。

## Execution Handoff

计划已保存：`docs/superpowers/plans/2026-09-20-p1-pr5-m03-outpatient.md`（15 任务；前置项 P-0~P-9 全映射；任务依赖序 1→2→3→4→5→6→7→8→9→10→11→12→13→14→15，其中 2/4/5/7/8/10/11 涉及跨模块联改面均单任务内闭环、提交全绿）。

### 一、待批项呈报清单（超出 recon 14 条裁决的特别标注项，逐条附依据；批准计划即批准以下条目）

1. **practice_grant 改道 system 通用段 V704**（recon 裁决 9 原拟 V608）：基线全局最大已应用版本=V703（PR-4 首批），system schema 基线非零迁移无初始化豁免——V608<V703 必被 `check_out_of_order` 拦截（脚本 :147-160 实证逻辑）；V704∈(500,None) 通用段合法（V607 先例，先登记先占）。**技术必要性改道，号段语义不变。**
2. **门诊三类字典种子 V705**（recon 未列）：03 Spec §8「字典（号别/就诊类型/离院去向）引用 M01 字典 code，不自建副本」——M01 字典管理面 P1 未交付，取值源缺位；V705 预置 appt-type 五条/visit-type 六条/disposition 八条（国卫办医政发〔2024〕16 号代码表）共 19 条，前端下拉与 disposition 词表校验的唯一起点。
3. **存量 dev 卷一次性重置**（Task 1 CHANGELOG 登记、**Task 13 执行**——重置点自 Task 15 前移：api-docs 导出要求 backend 在含 V200–V204 的新卷上启动，旧卷必因 Flyway pending V200–V204 拒绝启动；Task 15 真栈探针复用该重置后卷）：outpatient 首批 V200–V204 低于基线 V703，Flyway outOfOrder=false 对存量卷拒绝应用——守卫脚本 docstring :7-11 明示「存量环境经 CHANGELOG 登记的一次性重置承接」；Testcontainers IT 全新库不受影响。
4. **事件种子「UPDATE+兜底 INSERT」双形态**（裁决 3 的执行序修正）：V204 应用序先于 V605/V702，纯 UPDATE 在新库 no-op 后会被 V605/V702 以占位 desc 首插、终态漂移——双语句形态保两序同终态（V204 注释块与偏差①留档）。
5. **`outpatient.appointment.timeout`（id 39）登记**（recon 事件登记方向未列）：延迟队列回 `fy.topic` 的路由键须经 `declareConsumerQueue` 注册校验（构件「事件未登记阻断启动」语义），自产自消内部事件须登记；`outpatient.queue.called` 不登记（纯 WS，裁决面内）；`schedule.stopped` 登记并发布（id 40，无消费者不阻断）；`visit.no-show` 仅登记无发布点（id 27 先例）。
6. **`OutpatientConnectAuthInterceptor` 镜像复制**（裁决 12 的依赖面落定）：iot 侧同类在 `com.fuyun.iot.internal`（禁外引）且引用 iot 常量——上收 common 需改动 PR-1b 已交付 iot 链与其测试（出 PR-5 范围）；outpatient 侧镜像 ~105 行为最小爆炸半径方案，偏差⑥留档（后续 PR-3 裁决「模板基类提炼」范式收敛时可统一上收）。
7. **billing 两 api 端口 + pharmacy 开方端口**：`OutpatientBillingPort`（feesByVisit/applyRefund/cancelPendingFee——裁决 7 免审档进程内承载）、`SettlementQueryPort`（sourceRefsOfSettlement/settledUnder——裁决 5/6/8 反查唯一载体，CF-4 载荷零变更）、`PracticeCheckPort`（system，check REST 契约的进程内镜像）、`PrescriptionOpenPort`/`PrescriptionCancelPort`（pharmacy，禁 HTTP 自调用——PR-4 偏差⑤ PrescriptionFeePort 先例）。
8. **诊区队列口径**（Spec queue_id「诊区/医生队列」二选一）：P1 取 queue_id=dept_code 诊区单队列（ticket.doctor_id 辅助定向），医生级队列与跨院区随 P2 注记——叫号/大屏/分诊台三面演示最短路径。
9. **收费工作台前端直调 billing 面**：就诊费用划价/结算为 CF-4 同步 REST 人机面，M03 后端零收费编排 REST（资金无涉红线）；挂号收费联动页复用 PR-3 交付的 billing.ts（manualCharge/preview/settle）。

### 二、与 recon/调研的偏差清单（执行与评审对照）

① **id 23/25/31 冻结 UPDATE 无法纯 UPDATE 形态落 V2xx 段**（裁决 3 前提「V702 UPDATE V605 先例」依赖应用序后者改前者；outpatient 段 V200s<V605/V702 应用序倒置）→ UPDATE+WHERE NOT EXISTS 兜底 INSERT 双形态（待批 4）。② **V608→V704**（裁决 1/9 号段算术，待批 1）。③ **practice.changed 零新登记**：V5 id 6 已登记（撰写期实测）——Task 2 仅发布接线，总行断言 31→40 一次性落位（非两段 31→41）。④ **verify 入参扩展形态**：`@PostMapping` 增可选 body `VerifyCredentialRequest(String credential)`——既有无体调用向后兼容（api 契约加法变更，非 CF 冻结面，裁决 8 授权）。⑤ **releaseByVisit/confirmRefundTerminal 签名废除**（裁决 4/5 的接口形态落定）——`IDispenseService` javadoc :15 预告兑现；旧签名用例全删（死代码零容忍）。⑥ **WS 鉴权拦截器镜像复制**（待批 6）。⑦ **声明态枚举面**：visit IN_EXECUTION/PENDING_MEDICATION/NO_SHOW、clinic_order IN_EXECUTION/COMPLETED 为状态机合法迁移对登记、P1 无生产触发点（裁决 0 演示终点直线段；javadoc 注记触发事件源，非死代码豁免面）。⑧ **queue_id=dept_code**（待批 8）。⑨ **优先级公式冻结**：`100+绿通900+急诊分级800/700/600/500+回诊300+老幼残200`，ZSET score=priority×1e8+queue_seq——Spec :90 优先级因子的量化落定。⑩ **W-20 不修**（裁决 7）：挂号费>0 演示数据规避 0 元结算 400，TASK.md W-19/W-20/D-19 维持现状。

### 三、SDD 执行方式

**Subagent-Driven（推荐）**——`superpowers:subagent-driven-development`：每任务全新 subagent + 任务间 spec/quality 双结论审查；台账落 `.superpowers/sdd/2026-09-20-p1-pr5-m03-outpatient/`（跨会话续接按 PR-2/PR-3/PR-4 先例）。备选 **Inline Execution**——`superpowers:executing-plans`（本会话批量执行+检查点复核）。执行期质量门（不可跳过）：实现类 PR = 全量门禁 + 真栈（Task 15 Step 5 探针——存量卷重置已前移 Task 13 Step 1）+ 浏览器真机（三应用 UI 面，Task 15 Step 6）→ 建 PR → `/code-review` findings 清零 → 合并。**进入条件**：W-22 fix PR 已合入（P-0，Task 1 Step 1 核验）。
