# P2 PR-4E 事件与工单收敛包 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 W-67 死信分流（dispense.completed 判别子跳过+fee.created 载荷扩 visitType 净解）+W-47 患者读面 SENSITIVE_QUERY 审计+A-6 上报 wardId 词表+NursingRateGuard 频控骨架（A-6 上报限频+A-8 PDA 枚举冷却两键空间）+W-41 收费页 payerType UI 参数化（双硬编码联动）+W-27 号源超时 tick 三件套（克隆 nursing 先例，含 T-R3-4 quorum TTL 探针实测）+TASK 六工单销项。

**Architecture:** W-67 分两族修——dispense.completed 族=两消费方入口按住院行判别子（m04OrderNo 在位/rxNo 空）info 跳过（住院计费归 M13 路径，占位回写/申请单推进是门诊语义）；fee.created 族=根因是 billingKey sourceRef 语义双载不可区分，修法走载荷扩 visitType 组件（FeeCreatedPayload 尾部追加+V1115 event_registry payload_desc 只增不删+发布方按 chargeSource 推导填值+消费方 INPATIENT 跳过）。频控按 PortalCredentialRateGuard 先例镜像通用骨架（Redis INCR+TTL 原语，Redis 异常降级放行），两业务键空间挂两挂点。W-27 克隆 nursing delay.task-overdue 三件套（tick 档位当调度器绕开 quorum TTL 惰性，双通道幂等 CAS）。

**Tech Stack:** Spring Boot 3.5+RabbitMQ quorum/delay+MyBatis-Plus+Flyway、JUnit5+Mockito+Testcontainers IT、Vue 3+TS+Vitest+Element Plus。

**制定日期：** 2026-10-05。**基线：** dev@62537d7（PR #68 / PR-4C 合入后）。**分支：** `feat/p2-pr4e-events-tickets`（Task 1 自建）。**总纲：** `docs/superpowers/plans/2026-10-03-p2-pr4-overview.md`。

**行号声明：** 行号来自撰写时调研实况（2026-10-05 dev@62537d7）；执行时以实况重核——行号漂移以「方法名/锚点代码」定位为准。

**调研依据（写明存档）：** `.superpowers/code-review-pr63/findings-A.md`（A-6/A-8 原文）；TASK.md W-27/W-41/W-47/W-67/T-R3-4 行；现状调研报告（2026-10-05 主控派发，七节全量锚点——本计划全部行号与形态出处）；fee.created visitType 走 V1115 通用段=交接已锁定决策（V1114 已被 PR-4C 占用）。

## Global Constraints

- **GC1 裁决链**：W-67 判别子跳过+visitType 净解（2026-10-03 用户裁决重申）；W-41 settle 仅 SELF_PAY 放行门（同批——CITY_INS 医保 preview 拆分实测 PR-7 已过，UI 面本次打开）；W-27 仅号源 tick（GC21④ 会诊档位不并入）；W-38/W-61/W-62 按「已随 PR #59 闭环」销项（代码 5e4e9e7 在 HEAD，仅补 ✅ 注记——W-61 实际实现为 Registry 内常量直配非 IotdaAdminProperties 配置化，销项措辞按实形态写）。
- **GC2 迁移纪律**：本册占 **V1115**（integration.event_registry id 17 fee.created payload_desc 追加 visitType 句——V1111 先例形态：UPDATE+双条件守卫 id+event_type+只增不删）；A.4.1-3 已应用禁改；CHANGELOG 先记再占。
- **GC3 event_registry 唯一来源**：payload_desc 只增不删（旧文本逐字保留后追加）；FeeCreatedPayload record 组件尾部追加（COMPONENT_NAMES 同步追加，禁中插）。
- **GC4 门禁命令**：后端单任务 `cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl <模块> -am verify -DskipITs`；指定 IT 加 `-Dit.test=<IT 名> -Dtest=NoSuchTest -Djacoco.skip=true -Dfailsafe.failIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false`；前端 `cd web && pnpm --filter <app> test -- <spec>`；收口全量 verify+前端六连（lint/format:check/type-check/test/build/audit——**format:check 必跑**，PR-4C CI 教训）。
- **GC5 commitlint**：body 每行 ≤100 字符；push 前+合并前 `npx commitlint --from origin/dev --to HEAD` 自查；提交显式列文件（禁 add -A）。
- **GC6 时区红线**：业务日界一律 `now(HEALTHCARE_TZ)` 禁裸 now()；本册频控窗口/TTL 均为相对时长毫秒值不涉日界。
- **GC7 Redis 纪律**：键命名 `fy:nursing:{biz}:{id}`；频控键必须带 TTL（禁无过期键）；键成分经 SHA-256 摘要（证件号/identifier 禁明文入键——A.8 先例同款）；Redis 异常一律降级放行 warn 留痕不阻断主链路（PortalCredentialRateGuard 先例）。
- **GC8 死代码零容忍**：被替换的旧逻辑删净；`@Scheduled` 多实例配 ShedLock（tick sender 挂 ShedLock——nursing 先例核实其形态照抄）。
- **GC9 装配清单**：每任务报告必含「装配清单（物理核对）」节。
- **GC10 D-21 断言纪律**：既有断言因行为变更失效可同步收紧（不低于原严格度）逐条申报。
- **GC11 拒绝语义**：频控拒绝 429+新错误码（NS-1029 上报频控/NS-1030 PDA 冷却——冻结序以 NursingErrorCode 实况顺延，实现者核实当前冻结序后占号并在 ledger 登记）；禁打敏感键明文。
- **GC12 审计**：@AuditLog(SENSITIVE_QUERY) 落库失败不阻断（切面既有语义）；W-47 挂注解不改端点行为。

---

### Task 1: 立项底座

**Files:**
- Modify: `CHANGELOG.md`（PR-4E 立项条目+V1115 占号预告）
- Add: `docs/superpowers/plans/2026-10-03-p2-pr4e-events-tickets.md`（本计划入库）

**Interfaces:**
- Produces: 分支 `feat/p2-pr4e-events-tickets`（基于 dev@62537d7）；后续任务在此分支追加单笔提交。

- [ ] **Step 1: 建分支+立项提交**

```bash
cd /d/code/project/fuyun-medical
git checkout dev && git pull origin dev
git checkout -b feat/p2-pr4e-events-tickets
```
CHANGELOG 未发布段新增（先记再改）：

```markdown
## 2026-10-05 · P2 PR-4E 立项（事件与工单收敛包）

- 占用迁移号 V1115（integration.event_registry id 17 billing.fee.created payload_desc 追加
  visitType 组件语义句——先记再占，全局最大 V1114）。
- 范围：W-67 死信分流（dispense.completed 两消费方判别子跳过+fee.created 载荷扩 visitType
  净解）+W-47 患者读面 SENSITIVE_QUERY 审计（11 GET 端点）+A-6 上报 wardId 词表（nursing_
  ward_config requireConfig）+NursingRateGuard 频控骨架（A-6 上报限频+A-8 PDA 枚举冷却）+
  W-41 收费页 payerType UI 参数化（preview+settle 双硬编码联动）+W-27 号源超时 tick 三件套
  （克隆 nursing delay.task-overdue 先例）+T-R3-4 quorum TTL 探针实测+TASK 六工单销项
  （W-38/W-61/W-62 随 PR #59 闭环补注记+W-27/W-41/W-47/W-67 本册 ✅+T-R3-4 回填销项）。
- 裁决依据：总纲既有裁决重申（W-67 判别子+visitType 净解/W-41 仅 SELF_PAY 门/W-27 仅号源
  tick/GC21④ 不并入）；fee.created 载荷扩 visitType 走 V1115 通用段。
```

```bash
git add CHANGELOG.md docs/superpowers/plans/2026-10-03-p2-pr4e-events-tickets.md
git commit -m "docs: PR-4E 立项——事件与工单收敛包计划落盘（V1115 占号）"
```

---

### Task 2: W-67a dispense.completed 两消费方住院行判别子跳过

**Files:**
- Modify: `backend/fuyun-billing/src/main/java/com/fuyun/billing/internal/BillingPharmacyOccupyListener.java:65-96`（handleCompleted 入口分流）
- Modify: `backend/fuyun-outpatient/src/main/java/com/fuyun/outpatient/internal/OutpatientDispenseCompletedListener.java:59-63`（handleDispenseCompleted 入口分流）
- Test: `backend/fuyun-billing/src/test/java/com/fuyun/billing/internal/BillingPharmacyOccupyListenerTest.java`
- Test: `backend/fuyun-outpatient/src/test/java/com/fuyun/outpatient/internal/OutpatientDispenseCompletedListenerTest.java`
- Test(IT): `backend/fuyun-app/src/test/java/com/fuyun/app/DispenseSignoffLinkageIT.java`（住院行双消费方零死信断言——既有双形态夹具 :136-165 复用）

**Interfaces:**
- Consumes: `DispenseCompletedPayload`（十组件 record；住院行形态=rxNo/prescriptionId 双 null+m04OrderNo 非空——V1111 扩展语义）。
- Produces: 两消费方入口「住院行 info 跳过」语义——判定锚 `m04OrderNo() != null`（住院行专属组件，门诊行恒 null）；跳过=方法直返（ack），日志记 dispenseNo+m04OrderNo+跳过原因，禁 warn 级（合法业务行非异常）。

- [ ] **Step 1: 写失败测试**

BillingPharmacyOccupyListenerTest 增两用例（包级直驱 handle* 既有形态）：

```java
@Test
@DisplayName("住院行 dispense.completed（rxNo/prescriptionId 双 null+m04OrderNo 在位）：占位回写跳过不抛（住院计费归 M13 路径）")
void skipsInpatientDispenseCompletedWithoutOccupancyWrite() {
    // 住院行载荷：m04OrderNo 在位即住院判别子（V1111 扩展形态），门诊占位回写语义不适用
    handleCompleted(new DispenseCompletedPayload("DP-1", null, null, 9L, "I2026100500001",
            "INPATIENT", List.of(), "M04-001", "W01", "DPN-1"));
    // 跳过=不触达占用回写链（verify 零交互——mock 形态照既有用例的依赖桩）
    verifyNoInteractions(occupancyService);
}

@Test
@DisplayName("门诊行（rxNo 在位）照常走占位回写——分流不误伤既有路径")
void outpatientRowStillRoutesToOccupancyWrite() { /* 既有主路径用例已覆盖，补显式对照 */ }
```
（OutpatientDispenseCompletedListenerTest 同款两用例：住院行跳过不触达 chargingService.onDispenseCompleted；门诊行对照。）

- [ ] **Step 2: 跑红**（`mvn -pl fuyun-billing -am test -Dtest=BillingPharmacyOccupyListenerTest` 等）→ **Step 3: 实现**

BillingPharmacyOccupyListener.handleCompleted 入口首行（requireRxNo 之前）：

```java
// W-67 住院行判别子跳过：住院摆药签收（m04OrderNo 在位）的处方占用回写是门诊语义，
// 住院计费归 M13 InpatientChargeService 路径——不抛错走死信，info 留痕直接 ack
if (payload.m04OrderNo() != null) {
    log.info("dispense.completed 住院行跳过处方占用回写：dispenseNo={}，m04OrderNo={}",
            payload.dispenseNo(), payload.m04OrderNo());
    return;
}
```
OutpatientDispenseCompletedListener.handleDispenseCompleted 同款（判定同锚，跳过原因=门诊收费链语义；info 日志记 dispenseNo+m04OrderNo）。

- [ ] **Step 4: DispenseSignoffLinkageIT 增住院行零死信断言**

既有 `publishDispenseCompleted` 住院形态注入（:136-165 附近住院行四字段扩列形态）后断言：

```java
// W-67：住院行注入后 billing/outpatient 两消费方不再死信（既有实测=每次住院摆药签收 8 行
// 死信台账；分流后 dead_letter 表零新增行）
awaitUntil("住院行双消费方零死信", AWAIT_TIMEOUT.toMillis(), () ->
        jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM integration.dead_letter WHERE received_at > ?", Long.class, baselineTs) == 0);
```
（baselineTs=注入前时间戳锚；dead_letter 表列名以实况为准——awaitDeadLetter :210-225 诊断辅助同源表。）

- [ ] **Step 5: 跑绿+提交**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-billing -am verify -DskipITs && \
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-outpatient -am verify -DskipITs
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-app -am verify -Dit.test=DispenseSignoffLinkageIT \
  -Dtest=NoSuchTest -Djacoco.skip=true -Dfailsafe.failIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false
git add <显式列四文件>
git commit -m "fix(billing,outpatient): dispense.completed 住院行判别子跳过——占位回写/收费链双消费方零死信（W-67a）"
```

---

### Task 3: W-67b fee.created 载荷扩 visitType 净解

**Files:**
- Modify: `backend/fuyun-billing/src/main/java/com/fuyun/billing/api/FeeCreatedPayload.java`（第十组件 visitType 尾部追加）
- Add: `backend/fuyun-billing/src/main/resources/db/migration/billing/V1115__update_fee_created_payload_desc.sql`
- Modify: `backend/fuyun-billing/src/main/java/com/fuyun/billing/service/impl/PricingEngineServiceImpl.java:160-230`（chargeOne 发布处填 visitType）
- Modify: `backend/fuyun-outpatient/src/main/java/com/fuyun/outpatient/internal/OutpatientFeeCreatedListener.java:73-91`（visitType=INPATIENT 跳过 markPendingFee）
- Test: 上述三实现类配套 Test（PayloadTest/发布侧断言/消费方跳过用例）
- Test(IT): `backend/fuyun-app/src/test/java/com/fuyun/app/`（住院 fee.created 零死信 IT——挂点视 InpatientChargeIT 类实况，DispenseSignoffLinkageIT 同款断言形态）

**Interfaces:**
- Consumes: `ChargeSource.ORDER_LINKED`（住院医嘱链专属 source——visitType 推导锚）。
- Produces: `FeeCreatedPayload` 十一组件（追加 `String visitType`，取值 "INPATIENT"/"OUTPATIENT"，null 不可达——发布方恒填）；`COMPONENT_NAMES` 尾部追加 `"visitType"`；outpatient 消费方契约——visitType=="INPATIENT" 行 info 跳过 markPendingFee（住院缴费回执不推进门诊申请单）。

- [ ] **Step 1: 写失败测试**

```java
// FeeCreatedPayload 单测：组件数冻结 11+COMPONENT_NAMES 尾部追加序（禁中插——既有消费方按序解包防线）
@Test
void componentNamesAppendVisitTypeAtTail() {
    assertThat(FeeCreatedPayload.COMPONENT_NAMES).containsExactly(
            "feeId", "feeNo", "patientId", "visitId", "chargeItemId", "itemName", "amount",
            "chargeSource", "billingKey", "visitType");
}

// OutpatientFeeCreatedListenerTest：
@Test
@DisplayName("住院行 fee.created（visitType=INPATIENT）：跳过申请单推进不抛（W-67b 净解）")
void skipsInpatientFeeCreatedWithoutClinicOrderAdvance() {
    handleFeeCreated(inpatientFeeFrame("F-1", "M04-001"));  // visitType=INPATIENT 载荷帧
    verifyNoInteractions(clinicOrderService);
}
// 对照：visitType=OUTPATIENT 行照常 markPendingFee（既有用例形态）
```

- [ ] **Step 2: 跑红** → **Step 3: 实现**

V1115 迁移（V1111 先例形态——UPDATE+双条件守卫+只增不删）：

```sql
-- V1115：billing.fee.created 载荷追加 visitType 组件（W-67b 净解）——payload_desc 只增不删
UPDATE integration.event_registry
SET payload_desc = payload_desc || '；visitType=就诊类型（INPATIENT=住院行/OUTPATIENT=门诊行，发布方按 chargeSource 推导恒填——消费方按其分流住院行，W-67b）'
WHERE id = 17 AND event_type = 'billing.fee.created' AND payload_desc NOT LIKE '%visitType=%';
```

PricingEngineServiceImpl.chargeOne 发布处（第九参 billingKey 后追加）：

```java
// W-67b：visitType 净解组件——ORDER_LINKED 为住院医嘱链专属 source（InpatientChargeServiceImpl
// 唯一产出），据此推导恒填；消费方（outpatient 申请单推进）按其跳过住院行，终结 sourceRef 语义双载死信
String visitType = chargeSource == ChargeSource.ORDER_LINKED ? "INPATIENT" : "OUTPATIENT";
```
（chargeSource 在 chargeOne 上下文的取值面以实况核实——按行内既有变量名接入。）

OutpatientFeeCreatedListener.handleFeeCreated 在 billingKey 五段守卫后：

```java
// W-67b：住院行跳过——住院计费的缴费回执不推进门诊申请单（markPendingFee 查无 clinic_order
// 的死信根因即 sourceRef 语义双载：门诊申请单号 vs 住院医嘱号；visitType 组件终结双载）
if ("INPATIENT".equals(payload.path("visitType").asText(""))) {
    log.info("fee.created 住院行跳过门诊申请单推进：feeNo={}，m04OrderNo={}", feeNo, sourceRef);
    return;
}
```
（帧解析形态以该 listener 既有 JsonNode 手法为准；`asText("")` 兜底空串≠INPATIENT 走原路径——旧版本事件帧兼容。）

- [ ] **Step 4: 住院 fee.created 零死信 IT**（InpatientCharge 流 IT 或新 IT：住院医嘱确认→fee.created→断言 dead_letter 零新增+outpatient 零死信；DispenseSignoffLinkageIT :210 awaitDeadLetter 同源表形态）

- [ ] **Step 5: 跑绿+提交**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-billing -am verify -DskipITs && \
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-outpatient -am verify -DskipITs
MIGRATION_BASE_REF=origin/dev python scripts/check-migration-governance.py
# IT 双 flag 形态照 Task 2 Step 5
git add <显式列文件>
git commit -m "feat(billing): fee.created 载荷扩 visitType 组件——消费方住院行分流净解（W-67b，V1115）"
```

---

### Task 4: W-47 患者读面 SENSITIVE_QUERY 审计

**Files:**
- Modify: fuyun-patient 七 controller 11 GET 端点挂 `@AuditLog(AuditActionType.SENSITIVE_QUERY)`：
  `PatientController.java:92/:118`、`PatientIdentifierController.java:94`、`HealthController.java:47`、`CardController.java:118`、`CardAccountController.java:53/:98`、`DuplicateMergeController.java:59`、`PrivacyController.java:75/:102/:150`
- Test: patient 模块 controller 侧切面行为不在本任务重测（AuditLogAspect 既有测试覆盖切面语义）；补一条 ArchUnit 式冻结或 controller 单测断言注解在位（照 PdaController GET 先例的测试形态——若无先例测试则省略，报告说明）

**Interfaces:**
- Consumes: `com.fuyun.system.api.AuditLog` + `AuditActionType.SENSITIVE_QUERY`（已在位，javadoc「P1 随查询审计交付」）。
- Produces: patient 11 GET 端点全部挂审计（切面落 system.audit_log，落库失败不阻断——切面既有语义）。

- [ ] **Step 1: 逐端点挂注解**（import 补齐，方法级注解与既有 @Operation 共存——fuyun-patient 有 springdoc 可用）

```java
/** 既有 GET 方法，仅追加注解行（方法体零改动）： */
@AuditLog(AuditActionType.SENSITIVE_QUERY)
@Operation(summary = "...")
@GetMapping("/{patientId}")
public PatientVO detail(...) { ... }
```
（PrivacyController 既有 POST /privacy/unmask 挂法为域内先例；全 11 端点同款。）

- [ ] **Step 2: 注解在位冻结断言**（可选轻量：patient 模块测试新增一条反射扫描——`PatientController.class.getMethod("detail", ...).isAnnotationPresent(AuditLog.class)`；无既有先例测试则以 grep 自查+报告申报形态替代，勿为凑覆盖造空断言测试）

- [ ] **Step 3: 跑绿+提交**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-patient -am verify -DskipITs
git add <显式列七 controller>
git commit -m "feat(patient): 患者读面 11 GET 端点挂 SENSITIVE_QUERY 审计（W-47）"
```

---

### Task 5: A-6 上报 wardId 词表校验

**Files:**
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/service/impl/AdverseEventServiceImpl.java:127-154`（report 入口词表守卫段追加 wardId 校验）
- Test: `backend/fuyun-nursing/src/test/java/com/fuyun/nursing/service/impl/AdverseEventServiceImplTest.java`（+两用例）

**Interfaces:**
- Consumes: `WardMetaService.wardConfig(wardId)` → `requireConfig` 缺行抛 `NS-1016 (409)`（nursing_ward_config 词表载体——V801 种子 W01；调研确认 system 侧 sys_org 空壳勿用）。
- Produces: report 入口 wardId 词表守卫——`wardConfig 查无行 → NS-1016 409 "未知病区或缺少护理配置"`（复用既有语义与错误码，禁新码）。

- [ ] **Step 1: 写失败测试**

```java
@Test
@DisplayName("上报 wardId 非词表病区：NS-1016 409 拒绝（A-6 词表校验——防伪造 wardId 定向推送他病区提醒）")
void rejectsReportWhenWardIdNotInVocabulary() {
    // mock wardConfig 查无行（requireConfig 既有抛出形态）——wardId="FAKE-WARD"
    assertThatThrownBy(() -> service.report(reportReq("FAKE-WARD")))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(NursingErrorCode.WARD_CONFIG_MISSING);  // NS-1016 枚举名以实况为准
    verify(adverseEventMapper, never()).insert(any());  // 不落库
}

@Test
@DisplayName("词表内 wardId（W01）照常上报——校验不误伤主路径")
void acceptsReportWhenWardIdInVocabulary() { /* mock 配置行在位，走既有主路径断言 */ }
```
（WardMetaService 经构造器注入 AdverseEventServiceImpl——若未注入则补依赖[构造器参数+NursingWebConfig 装配核实]；mock 形态照该 Test 既有 Mockito 桩。）

- [ ] **Step 2: 跑红** → **Step 3: 实现**

report 入口词表守卫段（category/severity 三级守卫后、occurredAt 守卫前后皆可——紧邻词表族语义归拢）：

```java
// A-6 wardId 词表校验：上报病区必须是护理配置词表内病区（nursing_ward_config 行存在性，
// requireConfig 语义复用）——防伪造 wardId 污染统计并定向驱动他病区大屏 ADVERSE_EVENT_REMIND
wardMetaService.wardConfig(req.wardId());
```
（wardConfig 内部 requireConfig 缺行即抛 NS-1016——无需在本类重复判定。）

- [ ] **Step 4: 跑绿+提交**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-nursing -am verify -DskipITs
git add <显式列两文件>
git commit -m "feat(nursing): 不良事件上报 wardId 词表校验——nursing_ward_config 守卫（A-6）"
```

---

### Task 6: NursingRateGuard 频控骨架（A-6 上报限频+A-8 PDA 枚举冷却）

**Files:**
- Add: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/cache/NursingRateGuard.java`
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/config/NursingWebConfig.java`（@Import 装配——PortalCredentialRateGuard→OutpatientWebConfig 先例）
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/service/impl/AdverseEventServiceImpl.java`（report 入口限频）
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/service/impl/PdaServiceImpl.java:112-159`（patientSummary 冷却检查+解析失败计数）
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/api/NursingErrorCode.java`（NS-1029/1030 顺延占号——冻结序实况核实）
- Test: `NursingRateGuardTest.java`（新建）、AdverseEventServiceImplTest/PdaServiceImplTest 增频控用例

**Interfaces:**
- Consumes: RedisTemplate（StringRedisTemplate；PortalCredentialRateGuard 先例镜像）；SHA-256 摘要工具（先例同款——明文 identifier 禁入键，GC7）。
- Produces: `NursingRateGuard` 两方法族——`checkWithinWindow(String space, String key, int limit, long windowMs): boolean`（INCR+首次 EXPIRE 窗口计数，超阈 false；A-6 用：space="report-freq"，key=operatorId，limit=10/窗口 60s 起步可调常量）；`recordProbeFailure(String space, String key, int threshold, long coolMs)` + `checkNotCooling(String space, String key)`（A-8 用：space="pda-probe"，key=SHA-256(identifier)，threshold=5/冷却 30m——PortalCredentialRateGuard 同锚）；Redis 异常一律降级放行（warn 留痕）；错误码 NS-1029（上报频控 429）/NS-1030（PDA 冷却 429）。

- [ ] **Step 1: 写失败测试**（NursingRateGuardTest 镜像 PortalCredentialRateGuardTest 185 行形态——Redis mock 三态：窗口内放行/超阈拒/异常降级放行；两方法族各覆盖）

- [ ] **Step 2: 跑红** → **Step 3: 实现**

NursingRateGuard 骨架（先例逐段镜像，键前缀 `fy:nursing:`）：

```java
/**
 * 护理域频控守卫（A-6/A-8，PortalCredentialRateGuard 先例镜像）：Redis INCR+TTL 原语双方法族——
 * 窗口计数限频（上报滥用面）与失败计数冷却（枚举探测面）；键成分经 SHA-256 摘要（identifier/
 * 证件号禁明文入键）；Redis 异常一律降级放行 warn 留痕（频控是效率层防线，Redis 故障不阻断医护主链路）。
 * 无状态单例；装配归 NursingWebConfig @Import。
 */
public final class NursingRateGuard {
    static final String KEY_PREFIX = "fy:nursing:";
    /** A-6 上报限频窗口参数（每 operatorId 60s 内 10 次——滥用面基础闸门，M18 治理前先行） */
    static final int REPORT_LIMIT = 10;
    static final long REPORT_WINDOW_MS = 60_000;
    /** A-8 PDA 枚举冷却参数（同 identifier 解析失败 5 次冷却 30 分钟——与 system 登录锁定同锚） */
    static final int PDA_THRESHOLD = 5;
    static final long PDA_COOL_MS = 30 * 60_000L;
    // checkWithinWindow / recordProbeFailure / checkNotCooling 三方法——先例形态逐段镜像
}
```
挂点一（AdverseEventServiceImpl.report 最前，词表守卫之前——频控先行省无效校验）：

```java
// A-6 上报频控：每操作者窗口限频（匿名哨兵已被 PR-4C 限行拒于写面，此处按登录态 operatorId 计数）
if (!rateGuard.checkWithinWindow("report-freq", operatorId(), NursingRateGuard.REPORT_LIMIT, NursingRateGuard.REPORT_WINDOW_MS)) {
    throw new BizException(NursingErrorCode.REPORT_RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS);
}
```
挂点二（PdaServiceImpl.patientSummary：入口 checkNotCooling；resolveByIdentifier 抛 NS-1003 处 recordProbeFailure；成功 clearFailureCount）：

```java
// A-8 PDA 枚举冷却：同 identifier 反复试错枚举在区患者（证件号高价值枚举键）——失败计数达阈冷却
rateGuard.checkNotCooling("pda-probe", sha256(identifier));  // 冷却中抛 NS-1030 429
try { patientId = resolveByIdentifier(normalized); }
catch (BizException e) { rateGuard.recordProbeFailure("pda-probe", sha256(identifier)); throw e; }
rateGuard.clearFailureCount("pda-probe", sha256(identifier));
```
（sha256 形态照 PortalCredentialRateGuard 摘要先例；operatorId() 取 OperatorContextHolder 既有链。）

- [ ] **Step 4: 两挂点配套用例**（AdverseEventServiceImplTest：频控拒 NS-1029 不触词表校验；PdaServiceImplTest：冷却中拒 NS-1030、失败计数/成功清零——mock rateGuard 形态）

- [ ] **Step 5: 跑绿+提交**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-nursing -am verify -DskipITs
git add <显式列六文件>
git commit -m "feat(nursing): NursingRateGuard 频控骨架——上报限频+PDA 枚举冷却（A-6/A-8，NS-1029/1030）"
```

---

### Task 7: W-41 收费页 payerType UI 参数化（双硬编码联动）

**Files:**
- Modify: `web/apps/workstation/src/views/billing/PricingSettleView.vue:204/:253`（payerType 选择控件+preview 联动+settle payments 组装+确认文案）
- Test: `web/apps/workstation/src/views/billing/PricingSettleView.spec.ts`（扩展）

**Interfaces:**
- Consumes: 后端入参面已完备（SettlementPreviewRequest.payerType 枚举直绑非法 code 400 BILL-1034；SettleRequest 无 payerType——类型取自 preview DRAFT 行；payments 必填）。
- Produces: 工具栏新增支付方式选择（`payerType` ref——SELF_PAY/CITY_INS 两档起步可选五值，词表内联常量+中文标签「自费/市医保/省医保/异地医保/商业保险」）；preview 出网携所选 payerType；settle payments 组装按 payerType 联动（SELF_PAY→method='CASH' amount=totalAmount 单行；医保档→按 preview 回填自付额组装——**payments 医保形态以 SettleRequest/医保 IT 既有断言实况为准**，实现前先 grep InpatientChargeIT/结算 IT 中 CITY_INS settle 的 payments 构造先例，勿凭空造形态）；确认文案随选择联动（「（现金）」→所选支付方式名）。

- [ ] **Step 1: 写失败测试**（spec 扩展三用例：默认 SELF_PAY 预览出网；切 CITY_INS 后 preview 携 CITY_INS+settle payments 按医保形态组装；文案联动——mock 形态照既有 spec 的 api 桩）

- [ ] **Step 2: 跑红** → **Step 3: 实现**（控件 ElSelect 挂工具栏；payerType ref 默认 'SELF_PAY'；:204 改 `payerType: payerType.value`；:253 payments 组装抽 `buildPaymentLines(payerType, draft)` 纯函数；文案插值联动）

- [ ] **Step 4: 跑绿+提交**

```bash
cd web && pnpm --filter workstation test -- src/views/billing/PricingSettleView.spec.ts && pnpm lint && pnpm type-check && pnpm format:check
git add web/apps/workstation/src/views/billing/PricingSettleView.vue web/apps/workstation/src/views/billing/PricingSettleView.spec.ts
git commit -m "feat(workstation): 收费页 payerType UI 参数化——预结算/结算双硬编码联动（W-41）"
```

---

### Task 8: W-27 号源超时 tick 三件套 + T-R3-4 探针实测

**Files:**
- Add: `backend/fuyun-outpatient/src/main/java/com/fuyun/outpatient/internal/AppointmentTimeoutTickSender.java`
- Add: `backend/fuyun-outpatient/src/main/java/com/fuyun/outpatient/internal/AppointmentTimeoutTickListener.java`
- Add: `backend/fuyun-outpatient/src/main/java/com/fuyun/outpatient/internal/AppointmentTimeoutTickSeeder.java`
- Modify: `backend/fuyun-outpatient/src/main/java/com/fuyun/outpatient/config/OutpatientMessagingConfig.java:99-106`（tick 档位声明+Tick 三件装配）
- Modify: `backend/fuyun-outpatient/src/main/java/com/fuyun/outpatient/service/impl/AppointmentServiceImpl.java`（tick 扫描入口——复用 markTimeout 幂等链）
- Test: 三件套单测+`backend/fuyun-app/src/test/java/com/fuyun/app/MessagingGovernanceIT.java`（T-R3-4 探针断言——或新建 `DelayTtlProbeIT`）

**Interfaces:**
- Consumes: nursing `delay.task-overdue` 三件套完整先例（TaskOverdueTickSender/Listener/Seeder+NursingMessagingConstants:52-67 专用 routing key 豁免登记形态）；`declareDelayQueue(DelayQueueSpec)`。
- Produces: outpatient tick 档位 `delay.appointment-timeout.tick`（TTL=60s，DLX 键 `outpatient.appointment-timeout.tick` 专用，消费队列 `q.outpatient.appointment-timeout.tick` 自声明）；TickSender @Scheduled 周期 60s+ShedLock（nursing 先例核实其 ShedLock 挂法照抄）+hasPendingTick 惰性判积压；TickListener 直收空 ping（不经幂等三段式）→扫描超时面：**扫 Redis pay-hold 键与 RESERVED 预约单超时判定（Redis SCAN `fy:outpatient:pay-hold:*`+按预约单 RESERVED 且创建超时面扫描——形态以 AppointmentServiceImpl.markTimeout 幂等链可复用面为准）直接调 markTimeout 语义**（双通道：原 15m 档消息路径保留，tick 扫描路径幂等 CAS 兜底——双通道安全由 RESERVED→NO_SHOW CAS 0 行幂等跳过保证）；T-R3-4 探针=IT 内投短 TTL 消息实测 quorum 队列 TTL 到期经 DLX 转发时延（断言 <5s 容忍窗——test profile 重试参数亚秒形态参照）。

- [ ] **Step 1: 写失败测试**（TickSender 周期播种幂等/TickListener 空帧直收+扫描触发/T-R3-4 探针——形态逐段照 nursing 三件套单测先例）

- [ ] **Step 2: 跑红** → **Step 3: 实现**（三件套逐文件克隆先例改 outpatient 域常量；OutpatientMessagingConfig 增 tick 档位 declareDelayQueue+@Import 装配；扫描入口复用 markTimeout）

- [ ] **Step 4: T-R3-4 探针 IT**

```java
// T-R3-4 回填探针：quorum 队列 TTL 到期→DLX 转发时延实测（ MessagingGovernanceIT :64-65
// 「不含 fy.delay TTL 时延断言」注释的本册解冻）
@Test
@Order(N)
void delayQueueTtlExpiryForwardsWithinTolerance() throws Exception {
    // 投短 TTL 档消息（nursing delay.task-overdue 60s 档或测试专用更短档）→ 记投递时刻 →
    // 消费面收帧（CountDownLatch）→ 断言 forwardingDelay ∈ [TTL, TTL + 5s 容忍窗]
}
```

- [ ] **Step 5: 跑绿+提交**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-outpatient -am verify -DskipITs
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-app -am verify -Dit.test=MessagingGovernanceIT \
  -Dtest=NoSuchTest -Djacoco.skip=true -Dfailsafe.failIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false
git add <显式列文件>
git commit -m "feat(outpatient): 号源超时 tick 三件套——quorum TTL 惰性双通道兜底+探针实测（W-27/T-R3-4）"
```

---

### Task 9: TASK 销项与 Spec 注记

**Files:**
- Modify: `TASK.md`（六工单 ✅+T-R3-4 回填销项+范围注记）
- Modify: `docs/specs/modules/`（涉改模块 Spec 注记：billing fee.created visitType[06-billing 或对应册]、outpatient tick[02-outpatient]、nursing 频控/词表[05-nursing]——按各 Spec 实际章节归属，注记形态照 PR-4C 05-nursing §15 先例）

**Steps:**
- [ ] **Step 1: TASK.md 销项**——W-67（✅ PR-4E 收口：判别子跳过+visitType 净解双族）、W-47（✅ 11 GET 挂 SENSITIVE_QUERY）、W-41（✅ UI 参数化）、W-27（✅ tick 三件套+T-R3-4 实测回填）、W-38/W-61/W-62（✅ 随 PR #59 闭环补注记——W-61 措辞按实形态「Registry 内常量直配[withConnectionTimeout/withReadTimeout/withRetry]，非 IotdaAdminProperties 配置化」）、T-R3-4 行删除（回填=探针 IT 落地+实测值记入 W-27 ✅ 摘要）；A-6/A-8（✅ 随词表+频控收口——若 A-6/A-8 有独立登记行则 ✅，无则在 W-67 族行或新注记行带记）
- [ ] **Step 2: Spec 注记**（三模块各一小节或行内注记——语义面：fee.created visitType 组件契约/tick 双通道语义/NursingRateGuard 频控参数与词表校验）
- [ ] **Step 3: 提交**

```bash
git add TASK.md <Spec 文件>
git commit -m "docs: PR-4E 工单销项与 Spec 注记——W-27/W-38/W-41/W-47/W-61/W-62/W-67/T-R3-4 收口留痕"
```

---

### Task 10: 收口（主控执行，非 SDD 派发）

按总纲 §3 流程契约：①范围全量终验（后端全模块 verify+前端六连——format:check 必含）②轻 e2e=收费页 payerType 选择与 preview 联动（compose 真栈+浏览器走查，证据留 SDD 工作区 probe/）③/code-review 五路评审（A 安全/B 架构/C 数据/D 前端/E 测试——前台逐路派发[idle-time 限制]，brief=计划+diff 包）→置信度终评→must-fix 修复环 ④PR 开出（base dev、body 含裁决与义务清单）→CI 六 job 盯绿→merge→删分支 ⑤CHANGELOG 收口条目 ⑥直接开工 PR-4D（writing-plans 撰写 `2026-10-03-p2-pr4d-rbac-full.md`——端点×角色矩阵附件，零请示）。

---

## Self-Review 记录（撰写时已核）

1. **Spec 覆盖**：总纲 PR-4E 行八项全落位（W-67→T2/T3、W-47→T4、A-6→T5/T6、A-8→T6、W-41→T7、W-27+T-R3-4→T8、销项→T9、收口→T10），无缺口。
2. **占位符扫描**：T4 Step 2 注解在位断言与 T7 payments 医保形态标注为「以实况先例为准」的锚定引用（先例在仓内确实存在——PdaController 测试/结算 IT grep 面），非 TBD；其余步骤均含实态代码块。
3. **类型一致性**：`m04OrderNo() != null` 判别子在 T2 两消费方同锚；`visitType` 组件名在 T3 发布方/迁移/消费方三处一致；NursingRateGuard 三方法签名在 T6 骨架与两挂点一致；tick 档位名/队列名/routing key 在 T8 四文件一致。
4. **先例克隆面**：T6 频控（PortalCredentialRateGuard 214 行）/T8 tick 三件套（nursing delay.task-overdue）先例均在仓内完整存在，计划按锚点引用不复制全文。
