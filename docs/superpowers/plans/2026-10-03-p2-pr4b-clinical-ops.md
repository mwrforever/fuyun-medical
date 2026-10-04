# PR-4B 临床操作面语义包 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 W-72 临床留痕操作人身份服务端强制四域（执行单/摆药/任务认领/不良事件，含 A-4 破码 primary 收敛）+ InpatientDispenseView 去手输 + gen:api 契约再生成 + W-66 多明细退药（可退明细读面+弹窗多行化）+ D-3 分页慢回包守卫 + D-8/D-11 前端小修对，令 nursing/pharmacy 临床操作面的法定留痕人不可冒名。

**Architecture:** 方案 A（用户 2026-10-03 裁决，不得推翻）——请求体身份字段保留并放宽为可空，服务端一律以 `contextOperatorId()` 令牌身份覆盖落值（同文件 signReceive/check 与摆药 pick/verify 已有先例）；不良事件匿名上报通道语义保留（显式 isAnonymous=true 才匿名）。后端三任务按域推进（nursing 执行单 → nursing 任务/不良事件 → pharmacy 摆药），前端去手输随后同批；W-66 后端读面 → gen:api 再生成 → 前端多行弹窗；D-3/D-11 独立前端小修收尾。

**Tech Stack:** Spring Boot 3.5 + MyBatis-Plus CAS、Bean Validation、JUnit5+Mockito+Testcontainers IT、Vue 3 + TS + Vitest + @vue/test-utils、openapi-typescript 7.13.0。

**制定日期：** 2026-10-04。**基线：** PR-4A 合入后 dev（A 册迁移占 V1112/V1113，全局最大号 V1113）。**分支：** `feat/p2-pr4b-clinical-ops`（Task 1 自建）。**总纲：** `docs/superpowers/plans/2026-10-03-p2-pr4-overview.md`（裁决 D-28~D-35 与流程契约在彼）。

**行号声明：** 本计划全部行号来自撰写时实况现场重核（分支 `feat/p2-pr4a-infra@ce11b2a`，即含 A 册修复环后的仓库状态），**执行时以 A 册合入后的 dev 实况重核**——行号漂移时以「方法名/锚点代码」定位为准。

**背景与移交判断（写明存档）：**
- 调研档案 `.superpowers/pr4-research/r1-security.md` §2（W-72 四域触点）与 §2.5（A-4 短期修法）；评审档案 `.superpowers/code-review-pr63/findings-D.md`（D-3/D-8/D-11 三发现——注意：D-31 裁决所指的 D-3/D-8/D-11 属 **PR #63 评审 D 路**，非 PR-4A 评审 merged.md 的 D 编号体系）。
- **M-2 移交判断**：useAdverseEvents occurredAt 出网钉面已在 A 册修复环 ce11b2a 收口（+08:00 钉面+spec 断言精确 ISO），B 册不重做。
- **M-4/W-81 移交判断**：timeFormat.ts/AlarmRuleView 回显本地钟面扩面已登记 W-81 工单且修法明确「独立小 PR 勿顺手改」——不并入 B 册。
- **用户裁决语义（W-72，2026-10-03 上午，TASK.md :86 在案）**：服务端强制；方案 A 字段保留服务端覆盖；同批收敛 A-4 破码「两人不同」校验改服务端比较。W-66（D-30）：后端可退明细读面+弹窗多行化。D-31：顺手包全选（D-3+D-8/D-11→本册）。

## Global Constraints

- GC1 迁移：本册预计**零迁移**（W-66 读面无 DDL，W-72 无新列——close/return 留痕本就经 updated_by 审计列承载）；执行中若出现迁移需求即停手上呈主控回计划裁决（总纲号段纪律：V1114+ 通用段，CHANGELOG 先记再占）；A.4.1-3 已应用迁移禁改。
- GC2 W-72 方案 A 语义边界：请求体身份字段（executorId/receivedBy/assigneeId/handlerId/closedBy/returnerId/primaryAuthorizerId/reporterId）一律保留并放宽 @NotNull 为可空（兼容期客户端仍可传，服务端不消费）；服务端落值一律令牌身份；**匿名上报通道保留**（isAnonymous=true → reporter_id 落 NULL，不强制身份——非匿名一律令牌实名）；DTO javadoc 与 `docs/specs/modules/05-nursing.md`/`06-pharmacy.md` 注记言明「字段仅为兼容保留，服务端一律以令牌身份落值」。
- GC3 D-21 断言纪律：W-72 影响的既有断言（UT 令 EXECUTOR=9 落值、IT 回签 executor_id="66"）同步改为令牌身份断言，**且每域必须新增「请求体差异值被忽略、落库=令牌」的锁定用例（收紧而非放宽）**；逐任务报告申报改动断言清单。
- GC4 时区红线：业务日界一律 `OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ)` 禁裸 now()（W-72 改造触碰的方法体内既有钉面保持，新增代码同口径）；前端新增出网点（本册无时点出网面）如有须带偏移 ISO（W-70① 钉面先例）。
- GC5 OpenAPI 契约变更纪律：@NotNull 放宽+新增读面端点后必须 gen:api 再生成入库（Task 7）；导出用 docker run 一次性容器全量 boot（**SPRING_MAIN_LAZY_INITIALIZATION 严禁设置**——D-18 int64→string 覆写器 lazy 失效 181 schema 倒退教训）；`web/api-docs.json` 本地生成源不入库（.gitignore 已覆盖）；生成物 diff 逐块核对仅预期面。
- GC6 commitlint：body 每行 ≤100 字符；push 前主控 `npx commitlint --from origin/dev --to HEAD` 自查（本地 pre-commit 无 commitlint 是已知盲区）。
- GC7 门禁命令形态：后端单任务 `cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl <模块> -am verify -DskipITs`；指定 IT 加 `-Dit.test=<IT 名> -Djacoco.skip=true -Dfailsafe.failIfNoSpecifiedTests=false`；前端 `cd web && pnpm --filter workstation test -- <spec>` 等；收口全量 verify+前端六连。
- GC8 文件卫生与分层：UTF-8 无 BOM、LF、注释/日志全中文；modulith 零反向依赖（pharmacy 不读 nursing 表、nursing 不 import pharmacy——W-72/读面改动均模块内自洽）；死代码零容忍（D-11 patientId 死字段、claim 的 assigneeId 空守卫随消费面删除而删）；`.superpowers/`、`docs/progress/` 不入提交面。
- GC9 装配清单：每任务报告必含「装配清单（物理核对）」节（无装配面注明「本任务零装配面」）。
- GC10 核心链路覆盖：W-72 属鉴权/留痕路径（核心功能）——四域服务层单测 100% 覆盖改造分支（令牌覆盖/差异值忽略/匿名通道/两人不同服务端化），模块 IT 全绿；W-66 读面 UT 覆盖正常/边界（非 DELIVERED 拒/明细映射）/异常（缺调剂行）。

---

### Task 1: 立项底座

**Files:**
- Modify: `CHANGELOG.md`（PR-4B 立项条目）
- Add: `docs/superpowers/plans/2026-10-03-p2-pr4b-clinical-ops.md`（本计划入库）

**Interfaces:**
- Produces: 分支 `feat/p2-pr4b-clinical-ops`（基于 A 册合入后 dev）；后续任务在此分支追加单笔提交。

- [ ] **Step 1: 建分支**（前置：PR-4A 已合入 dev、CI 六 job 全绿、旧分支已删）

```bash
git checkout dev && git pull --ff-only
git checkout -b feat/p2-pr4b-clinical-ops
```

- [ ] **Step 2: CHANGELOG 立项条目**（头部插入）

```markdown
## 2026-10-04 · P2 PR-4B 立项（临床操作面语义包）启动

- **范围**：W-72 服务端强制四域（执行单 start/finish/needleOut+破码 primary 收敛/摆药 receive/任务认领/
  不良事件 report·handle·close·return——方案 A 字段保留服务端覆盖，匿名上报通道保留）+InpatientDispenseView
  去手输+gen:api 再生成+W-66 多明细退药（读面+弹窗多行化，D-30）+D-3 分页慢回包守卫+D-8/D-11 前端小修对（D-31）。
- **移交判断**：M-2 已随 PR-4A 修复环 ce11b2a 收口；M-4/W-81 扩面走独立工单——均不并入本册。
- **迁移**：预计零迁移（如需即回计划裁决，号段 V1114+）。
```

- [ ] **Step 3: 提交**

```bash
git add CHANGELOG.md docs/superpowers/plans/2026-10-03-p2-pr4b-clinical-ops.md
git commit -m "docs: PR-4B 立项——临床操作面语义包计划落盘"
```

---

### Task 2: W-72 执行单域服务端强制（start/finish/needleOut + A-4 破码 primary 收敛）

**Files:**
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/service/impl/OrderExecutionOperateServiceImpl.java:301-339`（start）、`:345-367`（finish）、`:375-441`（needleOut）、`:536-563`（overrideCheck）
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/dto/StartRequest.java:18`、`FinishRequest.java:16`、`NeedleOutRequest.java:22`、`OverrideCheckRequest.java:25-29`（@NotNull 放宽+javadoc）
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/service/IOrderExecutionOperateService.java`（接口 javadoc 身份语义同步）
- Test: `backend/fuyun-nursing/src/test/java/com/fuyun/nursing/service/impl/OrderExecutionOperateServiceImplTest.java`
- Test(IT 断言同步): `backend/fuyun-app/src/test/java/com/fuyun/app/NursingOrderExecutionFlowIT.java:589-646`、`InpatientOrderFlowIT.java:560-563`

**Interfaces:**
- Consumes: `contextOperatorId():875-882`（既有私有方法——数字校验+NS-1019，本任务推广消费）；`publishCompletedReceipt(row, Long executorId):626` / `registerConfirmAfterCommit(row, Long executorId, …):656`（签名不变，传值改令牌）。
- Produces: `OrderExecutionCompletedPayload.executorId`（id 63/64 事件契约）与 `ExecuteConfirmRequest.executorId`（M04 回签）语义=「服务端令牌身份」，**线格式与字段名零变化**；`OverrideCheckRequest.primaryAuthorizerId` 转兼容保留（服务端落令牌身份）。

- [ ] **Step 1: 写失败测试**（`OrderExecutionOperateServiceImplTest`——setup 已 `OperatorContextHolder.set(String.valueOf(NURSE))` :163，NURSE=1001 :96、EXECUTOR=9 :99，请求体差异值语义天然可表达）

新增四条 W-72 锁定用例（夹具循文件内既有 start/finish/needleOut/overrideCheck 用例构造——:270/:303/:412/:795 附近）：

```java
@Test
@DisplayName("W-72 start：请求体 executorId 被忽略，一律以令牌身份落库与出参")
void startUsesTokenExecutorIgnoringRequestBody() {
    // 行夹具与 stub 循既有 start 成功用例（:270 附近）同款；请求体传 EXECUTOR(9)、令牌为 NURSE(1001)
    OrderExecutionVO vo = service.start(EXEC, new StartRequest(EXECUTOR, null, null));
    assertThat(vo.executorId()).as("执行人=令牌身份（W-72）").isEqualTo(NURSE);
    verify(executionMapper).casStart(eq(EXEC), any(), eq(NURSE), any());
}

@Test
@DisplayName("W-72 finish：回执载荷与回签请求的 executorId=令牌身份（请求体值忽略）")
void finishUsesTokenExecutorInReceiptAndConfirm() {
    service.finish(EXEC, new FinishRequest(EXECUTOR, null)); // 行夹具 stub 同既有 :303 用例
    assertThat(payloadCaptor.getValue().executorId()).isEqualTo(NURSE);   // id 64 回执（循 :314 断言锚）
    assertThat(confirmCaptor.getValue().executorId()).isEqualTo(NURSE);   // M04 回签（循 :318 断言锚）
}

@Test
@DisplayName("W-72 needleOut：核对流水 operator_id 与自动入量/回签执行人=令牌身份")
void needleOutUsesTokenExecutorInCheckLogAndIntake() {
    service.needleOut(EXEC, new NeedleOutRequest(EXECUTOR, 250, VISIT)); // 行夹具 stub 同既有 :412 用例
    verify(checkLogMapper).insert(argThat(row -> row.getOperatorId() == NURSE));
    verify(ioRecordService).appendInfusionIntake(any(), eq(EXEC), eq(250), any(), eq(NURSE));
}

@Test
@DisplayName("A-4 破码：两人不同改服务端比较（令牌=secondary 拒 NS-1023），流水 operator_id=令牌")
void overrideCheckComparesTokenAgainstSecondaryServerSide() {
    // 令牌 NURSE(1001) 与 secondary 相同 → 409 OVERRIDE_CHECK_INVALID（请求体 primary 字段不参与比较）
    assertThatThrownBy(() -> service.overrideCheck(new OverrideCheckRequest(EXEC, 5L, NURSE, "同一人")))
            .isInstanceOf(BizException.class);
    // 不同 → 通过：流水 operator_id=令牌（不再取请求体 primary）
    RoleContextHolder.set(List.of("HEAD_NURSE")); // 循 :812 既有角色注入先例
    service.overrideCheck(new OverrideCheckRequest(EXEC, 5L, 6L, "同意"));
    verify(checkLogMapper).insert(argThat(row -> row.getOperatorId() == NURSE));
}
```

同步收紧既有断言（GC3——D-21 申报清单）：`:314/:318`（finish 回执/回签）、`:442`（needleOut 回签）、`:591` 附近（start 后 vo.executorId）、`:799-814` overrideCheck 用例族（请求体 primary 5L 改为与令牌差异/相同的语义重排）——断言值 `EXECUTOR` → `NURSE`。

- [ ] **Step 2: 跑红**——`mvn -B -ntp -pl fuyun-nursing test -Dtest=OrderExecutionOperateServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`，Expected: 新四用例 FAIL（现落请求体值）。

- [ ] **Step 3: 实现**（同文件最小改）：
  - `start:320/:325`：`long executorId = contextOperatorId();` → `casStart(executionNo, now, executorId, operator())`、`row.setExecutorId(executorId)`；`:332-334` 日志用该值（日志参数「executorId=令牌身份（W-72）」语境自然成立）。
  - `finish:362/:364/:365`：`publishCompletedReceipt(row, contextOperatorId())`、`registerConfirmAfterCommit(row, contextOperatorId(), req.routeCheckResult())`（long 自动装箱）；日志同步。
  - `needleOut:393` 后取 `long executorId = contextOperatorId();`，替换 `:396`（流水）、`:425`（自动入量）、`:433/:434`（双路回签）、`:436-438`（日志）。
  - `overrideCheck:540`：`Long primary = contextOperatorId();` + `Objects.equals(primary, req.secondaryAuthorizerId())` 拒绝（两人不同=令牌 vs secondary 服务端比较，A-4 收敛）；`:552-553` 流水 operator_id 落 `primary`；`:554-560` 日志 primary 输出令牌值并注记「请求体 primaryAuthorizerId 兼容保留忽略（W-72）」。
  - `cancel:449-478` 输注中断分支取 `row.getExecutorId()`（库值回签）——**零改动**（重核确认）。
  - 四 DTO：`@NotNull` 删（字段保留），javadoc `@param` 改「兼容保留——服务端一律以令牌身份落值（W-72，2026-10-03 裁决）」；`OverrideCheckRequest.primaryAuthorizerId` 同款。
  - 类注释与 `publishCompletedReceipt/registerConfirmAfterCommit` javadoc 的 `@param executorId` 描述同步（「=令牌身份」）。

- [ ] **Step 4: 跑绿+模块门禁**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-nursing -am verify -DskipITs
```

- [ ] **Step 5: IT 断言令牌化（GC3）**——两 IT 以 admin 令牌登录（`loginToken(ADMIN_LOGIN_NAME)`），W-72 后回签 executor_id=登录管理员身份：
  - `NursingOrderExecutionFlowIT`：加常量 `private static final long ADMIN_USER_ID = 1L; // V303 种子 admin 账号 id——W-72 后回签执行人=登录令牌身份`；`:636` `isEqualTo("66")` → `isEqualTo(String.valueOf(ADMIN_USER_ID))`；`:646` `isEqualTo(66L)` → `isEqualTo(ADMIN_USER_ID)`；`:589/:600` 请求体仍传 66（兼容保留，不改）。
  - `InpatientOrderFlowIT:563` `isEqualTo("66")` → `isEqualTo(String.valueOf(ADMIN_USER_ID))`（常量同款）。
  - `InfusionClosedLoopIT:699/:770`、`ExecutionRedirectReconcileIT:460/:470/:508`、`BillingInpatientLinkageIT` 仅传值无值断言（撰写时已核）——跑绿确认即可。

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-app -am verify -Dit.test=NursingOrderExecutionFlowIT,InpatientOrderFlowIT,InfusionClosedLoopIT,ExecutionRedirectReconcileIT,BillingInpatientLinkageIT -Djacoco.skip=true -Dfailsafe.failIfNoSpecifiedTests=false
```

- [ ] **Step 6: 提交**

```bash
git add backend/fuyun-nursing backend/fuyun-app
git commit -m "feat: 执行单操作人服务端强制——start/finish/needleOut 令牌身份与破码 primary 收敛（W-72/A-4）"
```

---

### Task 3: W-72 任务认领与不良事件域服务端强制

**Files:**
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/service/impl/NursingTaskServiceImpl.java:213-237`（claim）+ 文件尾新增 `contextOperatorId` 私有助手（`operator():535` 旁）
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/service/impl/AdverseEventServiceImpl.java:122-192`（report）、`:237-261`（handle）、`:266-287`（close）、`:292-303`（returnEvent）+ 同款私有助手（`operator():464-468` 旁）
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/dto/TaskClaimRequest.java:12`、`AdverseEventHandleRequest.java:15`、`AdverseEventCloseRequest.java:19`、`AdverseEventReturnRequest.java:19`、`AdverseEventReportRequest.java:26-28`（放宽+javadoc）
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/controller/NursingTaskController.java:105-113`、`AdverseEventController.java:58-135`（javadoc 身份语义）
- Test: `NursingTaskServiceImplTest.java`、`AdverseEventServiceImplTest.java`

**Interfaces:**
- Produces: 认领 `assigned_nurse`（VARCHAR）落「令牌身份十进制字符串」；不良事件非匿名 `reporter_id`=令牌、`handler_id`=令牌、close/return 的 updated_by 承载文本=令牌十进制串；匿名通道=显式 `isAnonymous=true`（reporter_id 落 NULL、不取令牌）。

- [ ] **Step 1: 写失败测试**

`NursingTaskServiceImplTest`（setup :130 `OperatorContextHolder.set("nurse-01")` 非数字——先改 setup 为 `"1001"` 并注记「W-72：认领留痕经 contextOperatorId 数字校验，setup 令牌改数字串」）：

```java
@Test
@DisplayName("W-72 认领：assignee 一律令牌身份（请求体 assigneeId 差异值忽略）")
void claimUsesTokenAssigneeIgnoringRequestBody() {
    when(taskMapper.casClaim(eq(TASK_NO), any(), any())).thenReturn(1);
    when(taskMapper.selectOne(any())).thenAnswer(inv -> claimedRowWithAssigned("1001")); // 回读行夹具循 :584-591 既有构造
    service.claim(TASK_NO, new TaskClaimRequest(9001L)); // 请求体 9001，令牌 1001
    verify(taskMapper).casClaim(eq(TASK_NO), eq("1001"), any()); // assigned_nurse=令牌十进制串
}
```

既有 `claimRejectsBlankAssigneeAndNonPendingState:609-621` 的「assigneeId 空拒 NS-1019」用例**删除**（守卫随消费面删除——D-21 申报），保留 CAS 0 行拒用例。

`AdverseEventServiceImplTest`（setup :113 已 set(REPORTER)；REPORTER/HANDLER 常量在案）：

```java
@Test
@DisplayName("W-72 上报：显式匿名走通道（reporter NULL、不取令牌）；非匿名一律令牌实名")
void reportAttributionFollowsAnonymousFlagAndToken() {
    // 非匿名：请求体 reporterId 携带差异值 999 → 落令牌 REPORTER（差异值忽略）
    AdverseEventVO vo = service.report(reportRequest(999L, false)); // 工厂循 :481-502 既有构造
    assertThat(vo.isAnonymous()).isFalse();
    // 匿名：显式 isAnonymous=true → reporter_id 落 NULL（通道语义保留，不调用令牌取值）
    AdverseEventVO anon = service.report(reportRequest(999L, true));
    assertThat(anon.isAnonymous()).isTrue();
}

@Test
@DisplayName("W-72 处置/关闭/退回：留痕人一律令牌身份（请求体差异值忽略）")
void handleCloseReturnUseTokenIdentity() {
    service.handle(NO, new AdverseEventHandleRequest(888L, "已处置"));
    // vo.handlerId() 断言由 HANDLER 改 REPORTER（:211 既有锚）；
    // close/return 经 casClose/casReturn 的 updated_by 文本断言 String.valueOf(REPORTER)（:272-301 既有锚）
}
```

- [ ] **Step 2: 跑红**——`mvn -B -ntp -pl fuyun-nursing test -Dtest=NursingTaskServiceImplTest,AdverseEventServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`。

- [ ] **Step 3: 实现**：
  - 两文件各加私有助手（照 `OrderExecutionOperateServiceImpl:875-882` 逐字同款，javadoc 注明「W-72 推广——留痕主体令牌解析」）。
  - `claim:217-223`：删 assigneeId 空守卫；`String assignee = String.valueOf(contextOperatorId());` → `casClaim(taskNo, assignee, operator)`；`:230-235` 日志 assigneeId 输出令牌值。
  - `report:144-145/:164`：`boolean anonymous = Boolean.TRUE.equals(req.isAnonymous());`（**取消「reporterId==null 即匿名」归一**——身份已转令牌，该归一口径作废；javadoc 注记行为语义「默认实名、显式匿名」与前端默认 isAnonymous=false 一致）；`row.setReporterId(anonymous ? null : contextOperatorId());`（匿名分支不取令牌——通道不强制身份）。
  - `handle:245/:249`：`long handlerId = contextOperatorId();` 替换 `req.handlerId()` 两处+日志。
  - `close:270`：`casClose(no, req.rcaNote(), req.correctiveAction(), String.valueOf(contextOperatorId()))`+日志；`returnEvent:296`：同款。
  - DTO 四枚 @NotNull 删+javadoc 兼容保留注记；`AdverseEventReportRequest.reporterId` javadoc :26-28 改「兼容保留忽略——归属由 isAnonymous+令牌承载（W-72）」。
  - 两 Controller 与 `INursingTaskService`/`IAdverseEventService` 接口 javadoc 身份语义同步。

- [ ] **Step 4: 跑绿+门禁+提交**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-nursing -am verify -DskipITs
git add backend/fuyun-nursing
git commit -m "feat: 护理任务认领与不良事件留痕人服务端强制——令牌身份覆盖请求体（W-72）"
```

---

### Task 4: W-72 摆药域服务端强制（receive 签收人）

**Files:**
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/service/impl/DispensePlanServiceImpl.java:474-539`（receive 签名与落值）
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/service/IDispensePlanService.java`（接口签名+javadoc）
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/controller/DispenseController.java:196-208`（receivePlan 调用面）
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/dto/DispensePlanReceiveRequest.java`（@NotNull 放宽+javadoc）
- Test: `backend/fuyun-pharmacy/src/test/java/com/fuyun/pharmacy/service/impl/DispensePlanServiceImplTest.java`（receive 用例族 :613-:634/:938-:985）

**Interfaces:**
- Consumes: `contextOperatorId():888` 附近（同文件 pick:291/verify:329 既有先例）。
- Produces: `receive(String planNo)`（签名去 receivedBy 参）；`markDelivered(planId, receivedBy=令牌, deliveredAt)` 落值令牌化；`dispense_plan.received_by` 语义=「病区签收人=当前登录人（令牌强制）」。

- [ ] **Step 1: 写失败测试**——`DispensePlanServiceImplTest` receive 主用例（:622-634）断言改造 + 新增锁定用例：

```java
@Test
@DisplayName("W-72 receive：签收人一律令牌身份（markDelivered receivedBy=令牌）")
void receiveUsesTokenReceiver() {
    // 行/调剂行/明细 stub 循既有 :622 用例同款（setup 已 set("1001") :143）
    impl.receive("DP2026100200001");
    verify(planMapper).markDelivered(eq(planIdCaptor.getValue()), eq(1001L), any());
    // completed 事件载荷不含签收人字段——零断言面变化；日志断言按 LoggingEvent receivedBy=1001（如有既有日志断言锚则同步）
}
```

既有 `:613/:951-:976` 守卫用例族调用面 `impl.receive("DP…", 2001L)` → `impl.receive("DP…")`（断言语义不变，D-21 申报=调用签名适配）。

- [ ] **Step 2: 跑红**——`mvn -B -ntp -pl fuyun-pharmacy test -Dtest=DispensePlanServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`（编译红=签名未改，即为红）。

- [ ] **Step 3: 实现**：
  - `receive` 签名 `receive(String planNo, long receivedBy)` → `receive(String planNo)`；方法首行 `long receivedBy = contextOperatorId();`（后续 :485 markDelivered/:532-538 日志零改动——局部变量承接最小 diff）；javadoc「W-72：签收人=令牌身份，请求体 receivedBy 兼容保留忽略」。
  - `DispenseController.receivePlan:207-208`：`dispensePlanService.receive(no)`（@RequestBody 保留解析——字段兼容期可传可不传）。
  - `DispensePlanReceiveRequest`：@NotNull 删+javadoc 兼容保留注记。
  - `IDispensePlanService` 接口同步。

- [ ] **Step 4: 跑绿+模块门禁+IT 回归**（InfusionClosedLoopIT :629 / NursingOrderExecutionFlowIT :533 receive 仍传 receivedBy=5——兼容保留，跑绿确认）

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-pharmacy -am verify -DskipITs
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-app -am verify -Dit.test=InfusionClosedLoopIT -Djacoco.skip=true -Dfailsafe.failIfNoSpecifiedTests=false
git add backend/fuyun-pharmacy
git commit -m "feat: 摆药签收人服务端强制——receive 令牌身份覆盖请求体 receivedBy（W-72）"
```

---

### Task 5: 前端操作面收敛——InpatientDispenseView 去手输 + PdaView 双授权 primary 展示回显

**Files:**
- Modify: `web/apps/workstation/src/views/pharmacy/InpatientDispenseView.vue:270-309`（签收弹窗逻辑）+ 模板 `:517-542`（签收弹窗）
- Modify: `web/apps/workstation/src/views/nursing/PdaView.vue:218/:226`（overrideForm）、`:276-313`（onSubmitOverride）+ 模板双授权弹窗输入区 `:770-800`
- Modify: `web/apps/workstation/src/api/pharmacy.ts:37/:227-230`（注释如实化）
- Test: `InpatientDispenseView.spec.ts`（:407 签收弹窗用例重写）、`PdaView.spec.ts`（:605-636 双授权用例更新）

**Interfaces:**
- Consumes: Task 2/3/4 已合入本分支的服务端强制语义；`stores/auth.ts` 的 `user.userId/userName`（UserVO：userId/displayName string 可空）。
- Produces: 签收弹窗零手输（出网仍携 `receivedBy=会话 userId`——方案 A 兼容保留、服务端忽略，诚实实现口径与 PdaView 一致）；双授权 primary 输入转展示回显（出网仍携 primaryAuthorizerId=会话 userId）。

- [ ] **Step 1: 写失败测试**（先红）
  - `InpatientDispenseView.spec.ts` :407 用例重写为「签收弹窗无工号输入面：展示当前登录人（会话身份），确认即出网 receivedBy=会话 userId 并重拉；会话缺身份零出网拦截」。
  - `PdaView.spec.ts` :605 用例改「双授权：主授权人=当前登录人展示回显（无输入框），副授权人工号必填+与登录人不同（前端比对改会话工号），合法载荷提交一次（primaryAuthorizerId=会话 userId）」。
  - 两处现有 mock auth 会话按文件内既有 auth stub 形态补 userId/displayName（PdaView.spec 已有会话 stub——循 :498 executorId 'u1' 形态）。

- [ ] **Step 2: 跑红**——`cd web && pnpm --filter workstation test -- InpatientDispenseView PdaView`，Expected: 新用例 FAIL（现仍有手输输入框）。

- [ ] **Step 3: 实现**：
  - InpatientDispenseView：`import { useAuthStore }`（循 PdaView 引入形态）；删 `receiveBy` ref（:275-276）与 `openReceive` 复位（:281）；`onReceive` 删手输校验（:293-297），改会话身份判空早退（循 PdaView requireExecutorId :344-350 同款提示），出网 `dispensePlans.receive(no, { receivedBy: auth.user?.userId ?? '' })`；模板删工号 `<input>`（:520-529）改展示行「签收人：{displayName ?? userId}（当前登录人，服务端留痕）」；hint 文案保留 PH-1026 提示。
  - PdaView：`overrideForm` 去 `primaryAuthorizerId` 字段（:218/:226）；`onSubmitOverride` 校验改「secondary 必填+reason 必填+`secondary === auth.user?.userId` 时提示『破码放行双授权两人不得相同（主授权人=当前登录人）』」；出网 `primaryAuthorizerId: auth.user?.userId ?? ''`（兼容保留）+`secondaryAuthorizerId`；模板 `:770-780` 主授权人 `<input>` 改只读展示（当前登录人 displayName/userId），副授权人/理由输入保留。
  - `api/pharmacy.ts:37` 注释改「病区签收入参（receivedBy 兼容保留——服务端一律以令牌身份落值，W-72；原『与药房操作者分权留痕』语义随服务端强制收敛）」；`:227-229` receive 注释同款同步。

- [ ] **Step 4: 跑绿+门禁+提交**

```bash
cd web && pnpm --filter workstation test -- InpatientDispenseView PdaView && pnpm --filter workstation type-check && pnpm lint
git add web/apps/workstation/src
git commit -m "fix: 前端操作面收敛——签收去手输与破码主授权人展示回显（W-72 前端面）"
```

TASK.md W-72 行尾追注「✅ 服务端强制四域+前端去手输已随 PR-4B Task 2~5 落地」（本笔只注记不销项——Spec 注记与终验归 Task 11/12；`git add TASK.md`）。

---

### Task 6: W-66 后端可退明细读面

**Files:**
- Create: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/vo/DispensePlanReturnableVO.java`
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/service/IDispensePlanService.java`（接口方法）
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/service/impl/DispensePlanServiceImpl.java`（新增 returnable 读方法——置于 `page:541`/`label:555` 读面区）
- Modify: `backend/fuyun-pharmacy/src/main/java/com/fuyun/pharmacy/controller/DispenseController.java`（新 GET 端点）
- Test: `DispensePlanServiceImplTest.java`（新增读面用例组）

**Interfaces:**
- Produces: `GET /api/v1/pharmacy/dispense-plans/{no}/returnable` → `DispensePlanReturnableVO`：

```java
/**
 * 住院可退明细读面（W-66/D-30：退药弹窗多行化的后端数据源）。仅 DELIVERED 调剂行可读
 * （与 acceptInpatientReturn 写面同守卫语义）；明细=NORMAL 行全列（住院退药一次性受理，
 * 缺行守卫要求逐行交代——returnedQty 首退恒 0，防御性携带 returnableQty 供前端禁输）。
 */
public record DispensePlanReturnableVO(
        String planNo, String dispenseNo, String dispenseStatus,
        String patientId, String visitId, String wardId,
        List<ReturnableItem> items) {
    /** 可退明细行：itemSeq=医嘱明细序号锚（prescription_item_id 双语义承载），数量 DECIMAL string */
    public record ReturnableItem(
            String itemSeq, String itemCode, String batchNo,
            String issuedQty, String returnedQty, String returnableQty) {}
}
```

- [ ] **Step 1: 写失败测试**（`DispensePlanServiceImplTest` 新增三用例）

```java
@Test
@DisplayName("W-66 returnable：DELIVERED 行直出 NORMAL 明细与可退净量（itemSeq/数量 string 承载）")
void returnableListsNormalItemsWithReturnableQuantity() {
    // plan/dispense(DELIVERED)/items(NORMAL 两行) stub 循既有 receive 用例夹具形态（:622 用例族）
    DispensePlanReturnableVO vo = impl.returnable("DP2026100200001");
    assertThat(vo.items()).hasSize(2);
    assertThat(vo.items().get(0).returnableQty()).isEqualTo("3"); // issued 3 - returned 0
}

@Test
@DisplayName("W-66 returnable：非 DELIVERED（未签收/已退）409 RETURN_STATE_NOT_ALLOWED（读写面同守卫）")
void returnableRejectsNonDeliveredDispense() { /* 断言 BizException PH RETURN_STATE_NOT_ALLOWED，dispense.status=CHECKED 夹具 */ }

@Test
@DisplayName("W-66 returnable：计划已出库但调剂行缺行（数据不一致）404 显式暴露")
void returnableSurfacesMissingDispenseRow() { /* 断言 DISPENSE_NOT_FOUND，循 acceptInpatientReturn :607-612 同口径 */ }
```

- [ ] **Step 2: 跑红**——`mvn -B -ntp -pl fuyun-pharmacy test -Dtest=DispensePlanServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`（编译红=方法未建）。

- [ ] **Step 3: 实现**

```java
@Override
@Transactional(readOnly = true)
public DispensePlanReturnableVO returnable(String planNo) {
    DispensePlan plan = requireByNo(planNo);
    Dispense dispense = dispenseMapper.selectOne(
            Wrappers.<Dispense>lambdaQuery().eq(Dispense::getDispensePlanNo, planNo));
    if (dispense == null) {
        // 数据库读操作缺行守卫：与 acceptInpatientReturn 同口径显式暴露（未出库不可退）
        throw new BizException(PharmacyErrorCode.DISPENSE_NOT_FOUND, HttpStatus.NOT_FOUND,
                "摆药计划调剂行不存在（未出库不可退药）：" + planNo);
    }
    // 读面与写面同守卫：仅病区签收后可退（W-66 弹窗数据源与受理面一致，防已退行误读）
    if (!"DELIVERED".equals(dispense.getStatus())) {
        throw new BizException(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED, HttpStatus.CONFLICT,
                "住院退药状态不允许（仅病区签收后可退）：" + dispense.getDispenseNo() + "，status=" + dispense.getStatus());
    }
    // 数据库读操作：NORMAL 明细行全列（orderByAsc(id) 与 acceptInpatientReturn :620-623 同序——提交缺行校验按行对齐）
    List<DispenseItem> items = dispenseItemMapper.selectList(Wrappers.<DispenseItem>lambdaQuery()
            .eq(DispenseItem::getDispenseId, dispense.getId())
            .eq(DispenseItem::getItemStatus, "NORMAL")
            .orderByAsc(DispenseItem::getId));
    List<DispensePlanReturnableVO.ReturnableItem> lines = items.stream()
            .map(item -> new DispensePlanReturnableVO.ReturnableItem(
                    String.valueOf(item.getPrescriptionItemId()),
                    item.getItemCode(),
                    item.getBatchNo(),
                    item.getIssuedQty().toPlainString(),
                    item.getReturnedQty().toPlainString(),
                    item.getIssuedQty().subtract(item.getReturnedQty()).toPlainString()))
            .toList();
    return new DispensePlanReturnableVO(plan.getPlanNo(), dispense.getDispenseNo(), dispense.getStatus(),
            dispense.getPatientId(), dispense.getVisitId(), plan.getWardId(), lines);
}
```

Controller（GET 不挂 @AuditLog——查询面统一收口口径，循 pagePlans）：

```java
/**
 * 住院可退明细读面（W-66：退药弹窗多行化数据源——DELIVERED 计划的 NORMAL 明细与可退净量）。
 *
 * @param no 摆药计划号（路径参数）
 * @return 可退明细读面，非空
 */
@Operation(summary = "住院可退明细读面")
@GetMapping("/api/v1/pharmacy/dispense-plans/{no}/returnable")
public DispensePlanReturnableVO returnable(@PathVariable("no") String no) {
    return dispensePlanService.returnable(no);
}
```

- [ ] **Step 4: 跑绿+门禁+提交**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-pharmacy -am verify -DskipITs
git add backend/fuyun-pharmacy
git commit -m "feat: 住院可退明细读面——dispense-plans/{no}/returnable（W-66/D-30）"
```

---

### Task 7: gen:api 契约再生成

**Files:**
- Modify: `web/packages/shared/src/api.d.ts`（生成物——唯一落盘变更）
- 本地中间产物：`web/api-docs.json`（.gitignore 不入库）

**Interfaces:**
- Consumes: Task 2/3/4 的 @NotNull 放宽 + Task 6 新端点（全部已在本分支）。
- Produces: 生成物新增 `DispensePlanReturnableVO` schema 与 `/api/v1/pharmacy/dispense-plans/{no}/returnable` path；八身份字段转可选（required 数组收缩）；供 Task 8 前端类型直连。

- [ ] **Step 1: 构建后端镜像**（宿主打包+运行层同构临时 Dockerfile——原生 docker build 容器内依赖下载失败，N6 实证口径）

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp package -DskipTests && cd ..
cat > /tmp/Dockerfile.pr4b <<'EOF'
# PR-4B api-docs 导出用一次性镜像：与 backend/Dockerfile 运行层同构（jre17+curl 非 root）
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY backend/fuyun-app/target/*.jar app.jar
USER 1000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
EOF
docker build -t fuyun/backend:pr4b-export -f /tmp/Dockerfile.pr4b .
```

- [ ] **Step 2: 起中间件+一次性容器全量 boot**（**严禁设置 SPRING_MAIN_LAZY_INITIALIZATION**——D-18 覆写器失效教训；本册零新迁移，boot 期 Flyway 仅应用已合入 dev 的版本，无打断旧镜像风险）

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d postgres redis rabbitmq minio
set -a; source deploy/.env; set +a
FY_NET="$(docker network ls --format '{{.Name}}' | grep 'fy-net')"   # compose 项目前缀网络（如 fuyun-medical_fy-net）
docker run -d --name pr4b-api-export --network "$FY_NET" --env-file deploy/.env \
  -e SPRING_PROFILES_ACTIVE=dev -e TZ=Asia/Shanghai \
  -e "FUYUN_DATASOURCE_URL=jdbc:postgresql://postgres:5432/${POSTGRES_DB}?reWriteBatchedInserts=true" \
  -e "FUYUN_DATASOURCE_USERNAME=${POSTGRES_USER}" -e "FUYUN_DATASOURCE_PASSWORD=${POSTGRES_PASSWORD}" \
  -e FUYUN_REDIS_HOST=redis -e "FUYUN_REDIS_PASSWORD=${REDIS_PASSWORD}" \
  -e FUYUN_RABBITMQ_HOST=rabbitmq -e "FUYUN_RABBITMQ_USERNAME=${RABBITMQ_USER}" -e "FUYUN_RABBITMQ_PASSWORD=${RABBITMQ_PASSWORD}" \
  -e FUYUN_MINIO_ENDPOINT=http://minio:9000 -e "FUYUN_MINIO_ACCESS_KEY=${MINIO_ROOT_USER}" -e "FUYUN_MINIO_SECRET_KEY=${MINIO_ROOT_PASSWORD}" \
  -e "FUYUN_SECURITY_TOKEN_HMAC_SECRET=${FUYUN_SECURITY_TOKEN_HMAC_SECRET}" \
  -p 127.0.0.1:18080:8080 fuyun/backend:pr4b-export
# 等待全量 boot 完成（start_period 量级，分钟级等待属正常）
until curl -fsS http://127.0.0.1:18080/actuator/health | grep -q '"status":"UP"'; do sleep 10; done
```

- [ ] **Step 3: 导出与再生成**

```bash
curl -fsS http://127.0.0.1:18080/v3/api-docs > web/api-docs.json
cd web && pnpm gen:api
```

- [ ] **Step 4: 生成物核对**——`git diff web/packages/shared/src/api.d.ts` 逐块核对，预期面恰为：①新增 returnable path+`DispensePlanReturnableVO`（含 ReturnableItem）；②`StartRequest/FinishRequest/NeedleOutRequest/OverrideCheckRequest/TaskClaimRequest/AdverseEventHandleRequest/AdverseEventCloseRequest/AdverseEventReturnRequest/DispensePlanReceiveRequest` 的身份字段移出 required；③零其它漂移。抽查 `grep -c '"format": "int64"' web/api-docs.json` Expected: 0（D-18 根治延续——非 0 即 lazy/覆写器异常，停手排查）。

- [ ] **Step 5: 清理+提交**

```bash
docker rm -f pr4b-api-export && docker rmi fuyun/backend:pr4b-export
cd web && pnpm type-check && pnpm test
git add web/packages/shared/src/api.d.ts
git commit -m "chore: gen:api 再生成——身份字段可空化与可退明细读面契约（W-72/W-66）"
```

---

### Task 8: W-66 前端退药弹窗多行化（含 D-8 数量校验收口）

**Files:**
- Modify: `web/apps/workstation/src/api/pharmacy.ts`（新类型别名+returnable 方法）
- Modify: `web/apps/workstation/src/views/pharmacy/InpatientDispenseView.vue:344-405`（退药弹窗逻辑重写）+ 模板 `:592-639`（多行表重写）
- Test: `InpatientDispenseView.spec.ts`（退药用例族重写+新增）

**Interfaces:**
- Consumes: Task 7 生成物 `components['schemas']['DispensePlanReturnableVO']`；`createDispenseReturn` 住院形态（`dispensePlanNo+returnLines[]`——契约已冻结）。
- Produces: 退药弹窗=可退明细多行表（打开即拉 returnable，每行数量录入+正则与可退净量双验[D-8]），提交逐行 returnLines。

- [ ] **Step 1: api 层扩展**（`api/pharmacy.ts` dispensePlans 资源组内，循 label 方法形态）

```ts
/** 住院可退明细读面出参（W-66：退药弹窗多行化数据源；数量 DECIMAL string 承载） */
export type DispensePlanReturnableVO = components['schemas']['DispensePlanReturnableVO'];
  /** 可退明细读面（DELIVERED 计划的 NORMAL 明细与可退净量——弹窗打开拉取，非 DELIVERED 409 归失败弹错） */
  returnable: async (no: string): Promise<DispensePlanReturnableVO> => {
    const resp = await http.get<DispensePlanReturnableVO>(`/v1/pharmacy/dispense-plans/${no}/returnable`);
    return resp.data;
  },
```

（`http.get` 返回形态以文件内既有 GET 先例 :75/:126 为准——若 `http` 封装直出 data 则去掉 `.data`。）

- [ ] **Step 2: 写失败测试**（`InpatientDispenseView.spec.ts` 退药用例族重写——含既有单行用例改造）

```ts
it('退药弹窗多行化：打开拉取可退明细，逐行录入数量后整单提交 returnLines（缺行零出网拦截）', async () => {
  // mock dispensePlans.returnable → 两行明细（itemSeq '1'/'2'，returnableQty '3'/'2'）
  // 断言：两行数量输入面；仅填一行时提交被拦（后端缺行守卫 400——前端「逐行交代」前置拦截零出网）；
  // 全填合法值 → createDispenseReturn 以 returnLines: [{itemSeq:'1',...},{itemSeq:'2',...}] 恰一次
});
it('D-8 数量校验收口：负数/科学计数/Infinity/超可退净量形态零出网拒绝', async () => {
  // 依次录入 '-2' / '1e3' / 'Infinity' / '9.9'（returnableQty '3'）→ 断言零出网+警示文案
});
it('退药弹窗竞态：换行打开旧读面回包丢弃（EX-45 同族）', async () => {
  // A 行 returnable 慢回包，期间换开 B 行 → A 回包不得落值 B 弹窗
});
```

- [ ] **Step 3: 跑红**——`pnpm --filter workstation test -- InpatientDispenseView`。

- [ ] **Step 4: 实现**（弹窗重写要点）：
  - 状态：`returnTarget`（行锚）+ `returnData: DispensePlanReturnableVO | null` + `returnQtys: Record<string, string>`（itemSeq→录入）+ `returnLoading`；`openReturn` 改异步拉取（换行先清旧+回包比对 planNo——EX-45 纪律，循贴签弹窗 `openLabel:324-342` 同款）。
  - 删 `returnItemSeq/returnQuantity/returnTraceCodes` 单行三 ref 与对应输入；**追溯码输入面删除**（住院摆药采集恒空集、非空码后端必拒 PH-1012——原输入面是必 409 陷阱，如实化注记留档；防回流核验语义由后端 UT 承载），提交恒传 `traceCodes: []`。
  - 校验（D-8 收口，循 PdaView `isValidNeedleVolume` 正则+范围双验先例）：每行 `/^\d+(\.\d+)?$/` 且 `0 < Number(qty) <= Number(returnableQty)`；`returnableQty<=0` 行（防御面，正常不可达）只读禁输；任一行不合法或空 → 警示零出网。
  - 提交：`createDispenseReturn({ dispensePlanNo, returnLines: returnData.items.map(i => ({ itemSeq: i.itemSeq, returnQuantity: returnQtys[i.itemSeq] ?? '', traceCodes: [] })) })`——**全 NORMAL 行逐行交代**（后端缺行守卫对齐）。
  - 模板：`el-table` 列=itemSeq/编码/批号/已发/已退/可退/数量输入（`inputmode="decimal"`，aria-label 含行锚）。
  - 页头注释块（:2-14）与退药段注释同步多行化语义。

- [ ] **Step 5: 跑绿+门禁+提交**

```bash
cd web && pnpm --filter workstation test -- InpatientDispenseView && pnpm --filter workstation type-check && pnpm lint
git add web/apps/workstation/src
git commit -m "feat: 住院退药弹窗多行化——可退明细读面接入与逐行数量双验（W-66/D-8）"
```

---

### Task 9: D-3 分页慢回包守卫（usePagedList 家族三消费面一次收口）

**Files:**
- Modify: `web/apps/workstation/src/composables/usePagedList.ts:101-114`（守卫落点）+ 头注释 `:13-14`（并发语义如实化）
- Modify: `web/apps/workstation/src/views/nursing/composables/useExecutions.ts:137-156`（loadBoard 守卫）
- Test: `web/apps/workstation/src/composables/usePagedList.spec.ts`（新增两用例）、`useExecutions.spec.ts`（新增一用例）

**Interfaces:** 无跨任务接口（usePagedList 对外 API 面零变化——纯内部守卫）。

- [ ] **Step 1: 写失败测试**

```ts
// usePagedList.spec.ts
it('D-3 慢回包守卫：筛选切换后旧回包后到被丢弃（rows 不被旧筛选覆盖）', async () => {
  // 两段式 fetcher mock：params() 读 filter ref；发起 A（filter='x'）→ 改 filter='y' 再发起 B
  // 手动按序 resolve A（旧慢回包）→ 断言 rows 仍空；resolve B → rows=B 内容
});
it('D-3 慢回包守卫：翻页快速往返旧页回包丢弃', async () => {
  // goToPage(2) 后立即 goToPage(1)；先 resolve 第 2 页回包 → rows 不落第 2 页内容；再 resolve第 1 页 → 正常落值
});
// useExecutions.spec.ts
it('D-3 loadBoard 慢回包守卫：病区/班次切换后旧回包丢弃且 loading 不误复位', async () => {
  // 两次 loadBoard 交叉 resolve——旧回包不落 boardRows；新请求在途时 loading 保持 true（seq 守卫 finally 分支）
});
```

- [ ] **Step 2: 跑红**——`pnpm --filter workstation test -- usePagedList useExecutions`。

- [ ] **Step 3: 实现**（递增请求序号形态——比参数快照比对更强，同参重发也防乱序）：

usePagedList（task 工厂内）：

```ts
const task = useAsyncTask(
    async () => {
      // D-3 慢回包守卫：递增请求序号，回包落地前比对在位序号——筛选/翻页快速连续触发时
      // 旧慢回包后到即丢弃（防旧行集覆盖新结果，三消费面一次收口）
      const seq = ++requestSeq;
      const result = await options.fetcher({
        ...options.params(),
        // 边界转换：组件 currentPage 1 基 → 契约 page 0 基（api 层保持纯透传）
        page: currentPage.value - 1,
        size: pageSize,
      });
      if (seq !== requestSeq) {
        return; // 过期回包丢弃：发起后已有更新的请求在途/落地
      }
      rows.value = result.content ?? [];
      total.value = toTotalNumber(result.total);
      options.onSuccess?.(result);
    },
    { onError: options.onError },
  );
```

（闭包上方声明 `let requestSeq = 0;`；头注释 :13-14 「不设在途互斥，慢回包竞态由各视图按需另行锚定」→「D-3 收口：统一请求序号守卫承载慢回包竞态，视图层免另行锚定」。）

useExecutions.loadBoard：

```ts
  /** 看板加载请求序号（D-3 慢回包守卫：旧筛选回包后到即丢弃） */
  let boardSeq = 0;

  /** 看板加载：执行单清单 + 遥测组合源并行刷新（遥测失败不阻塞清单） */
  async function loadBoard(): Promise<void> {
    const seq = ++boardSeq;
    boardLoading.value = true;
    try {
      const page = await executions.list({
        wardId: options.wardId.value,
        date: filterDate.value === '' ? undefined : filterDate.value,
        shift: filterShift.value === '' ? undefined : filterShift.value,
        page: 0,
        size: 200,
      });
      if (seq !== boardSeq) {
        return; // 过期回包丢弃：病区/班次/日期已切换，旧回包不得覆盖新看板
      }
      boardRows.value = page.content ?? [];
      await loadInfusionSources();
    } catch {
      // 失败弹错归响应拦截器；驻留旧看板
    } finally {
      // 仅最新请求复位 loading：旧请求晚归不得提前撤掉新请求的加载态
      if (seq === boardSeq) {
        boardLoading.value = false;
      }
    }
  }
```

（AdverseEventView/InpatientDispenseView 经 usePagedList 自动收口——`@change="search"`/`onWardChange → loadBoard` 两消费面零视图改动。）

- [ ] **Step 4: 跑绿+全量前端单测+提交**

```bash
cd web && pnpm --filter workstation test && pnpm --filter workstation type-check && pnpm lint
git add web/apps/workstation/src/composables/usePagedList.ts web/apps/workstation/src/views/nursing/composables/useExecutions.ts web/apps/workstation/src/composables/usePagedList.spec.ts web/apps/workstation/src/views/nursing/composables/useExecutions.spec.ts
git commit -m "fix: 分页/看板列表慢回包守卫——请求序号丢弃过期回包三消费面收口（D-3）"
```

---

### Task 10: D-11 patientId 死字段清理

**Files:**
- Modify: `web/apps/workstation/src/views/nursing/composables/useAdverseEvents.ts:24`（AdverseEventFormModel.patientId 删）、`:38`（EMPTY_FORM）、`:131`（onReport 透传删）
- Test: `web/apps/workstation/src/views/nursing/composables/useAdverseEvents.spec.ts`（若有 patientId 断言同步删；补一条「上报载荷不含 patientId 键」的负向断言）

**Interfaces:** 无（后端 `AdverseEventReportRequest.patientId` 契约字段保留可空——前端从不携带，生成物零变化）。

- [ ] **Step 1: 写失败测试**——`useAdverseEvents.spec.ts` 新增：

```ts
it('D-11 patientId 死字段清理：表单模型与上报载荷均不含 patientId（无录入面字段不透传）', async () => {
  // 断言 reportForm 初始态无 patientId 键；onReport 出网载荷（adverseEvents.report mock 捕获）不含 patientId 键
});
```

- [ ] **Step 2: 跑红** → **Step 3: 实现删字段**（三处一次删净，注释块 :2-5 提及字段面核对无需改——逐字重核后仅删字段相关行）→ **Step 4: 跑绿+门禁+提交**

```bash
cd web && pnpm --filter workstation test -- useAdverseEvents && pnpm --filter workstation type-check
git add web/apps/workstation/src/views/nursing/composables
git commit -m "refactor: 不良事件表单 patientId 死字段清理（D-11）"
```

---

### Task 11: Spec 注记与 TASK.md 销项

**Files:**
- Modify: `docs/specs/modules/05-nursing.md`（API 契约节执行单/任务/不良事件族条目+W-72 注记；§安全破码条目 A-4 注记）
- Modify: `docs/specs/modules/06-pharmacy.md:171` 附近（API 节 receive 条目注记+returnable 读面登记；退药语义 W-66 注记）
- Modify: `TASK.md`（W-72 :86 / W-66 :80 两行销项）

**Interfaces:** 无。

- [ ] **Step 1: 05-nursing.md**——API 契约节（executions `{no}/start|finish|needle-out`、pda override-check、tasks `{taskNo}/claim`、adverse-events 上报/处置/关闭/退回条目）各追加一行：「操作人身份字段兼容保留，服务端一律以登录令牌身份落值（W-72，2026-10-03 裁决；破码双授权 primary=在场授权人令牌身份，secondary 客户端承载+审计留痕——第二授权人角色核验归 W-37 后续）」；§安全验收 :232「执行人自任第二核同人被拦截」条目注记服务端化语义。
- [ ] **Step 2: 06-pharmacy.md**——:171 调剂行 receive 条目追加同款 W-72 注记；dispense-plans 端点族新增 `GET /dispense-plans/{no}/returnable`（可退明细读面——W-66 多明细退药数据源）；§跨模块 M05 衔接退药句注记「多明细住院计划退药 UI 已可达（PR-4B）」。
- [ ] **Step 3: TASK.md 销项**——W-72 行尾「✅ PR-4B 收口（四域服务端强制+前端去手输+A-4 primary 收敛，Task 2~5/11）」；W-66 行尾「✅ PR-4B 收口（可退明细读面+弹窗多行化，Task 6/8）」。D-3/D-8/D-11 非工单表项——收口留痕归 Task 12 CHANGELOG。
- [ ] **Step 4: 提交**

```bash
git add docs/specs/modules/05-nursing.md docs/specs/modules/06-pharmacy.md TASK.md
git commit -m "docs: W-72/W-66 Spec 注记与工单销项——服务端强制语义与读面登记"
```

---

### Task 12: 收口——范围全量终验 + 真机 e2e + PR 开出

**Files:**
- Modify: `TASK.md`（如 Step 1-2 发现欠账就地修并申报）、`CHANGELOG.md`（PR-4B 收口条目）

- [ ] **Step 1: 后端全量 verify**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp verify
```

Expected: BUILD SUCCESS（全模块含 Testcontainers IT+JaCoCo 双阈值；终验暴露的既有欠账就地修——D-21 纪律申报）。

- [ ] **Step 2: 前端六连**

```bash
cd web && pnpm lint && pnpm format:check && pnpm type-check && pnpm test && pnpm build && pnpm audit --audit-level high --registry=https://registry.npmjs.org
```

（audit 挂则读 CI 日志 Patched versions 判可升/不可升——不可升走点名豁免+工单，禁静默绕过。）

- [ ] **Step 3: 真机 e2e（本册联调面——总纲 §3 契约）**——compose 真栈起服+浏览器走查，证据截图/断言留档 `.superpowers/sdd/p2-pr4b/probe/`（不入提交面）：
  1. **PDA 执行链**：工作站登录护士 → 检索患者 → 三向核对 → 破码放行（双授权弹窗主授权人=登录人展示回显、副授权人不同工号）→ 开始执行 → 完成/拔针；DB 断言 `nursing.order_execution.executor_id` 与 `execution_check_log.operator_id`=登录人 id（非请求体值）。
  2. **摆药签收**：住院摆药计划五步至已核对待交接 → 配送交接 → 病区签收（弹窗**无工号输入面**、展示登录人）→ DB 断言 `dispense_plan.received_by`=登录人 id。
  3. **多明细退药**：多明细医嘱走完链路至 DELIVERED → 退药弹窗出多行明细（逐行数量）→ 提交成功；DB 断言 `dispense_item.returned_qty` 与调剂行 FULL/PART_RETURNED；单明细回归路径同验。
  4. **不良事件**：非匿名上报（DB `reporter_id`=登录人）+ 匿名开关上报（`reporter_id` NULL）双路；处置/关闭/退回 updated_by=登录人。
  5. **D-3 抽验**：弱网模拟（DevTools throttling）快速切换筛选，列表不串旧筛选结果。

- [ ] **Step 4: CHANGELOG 收口+TASK 复核+提交**

```markdown
## 2026-10-04 · P2 PR-4B 收口（临床操作面语义包）

- **W-72**：四域操作人服务端强制（方案 A 字段保留+令牌覆盖+匿名通道保留）+A-4 破码 primary 收敛
  （两人不同改服务端比较）+InpatientDispenseView 去手输+PdaView 双授权主授权人展示回显。
- **W-66（D-30）**：可退明细读面端点+退药弹窗多行化（含 D-8 数量正则/范围双验收口、住院追溯码必拒输入面删除注记）。
- **D-31 顺手包**：D-3 分页慢回包守卫（usePagedList/useExecutions 序号守卫三消费面收口）+D-11 patientId 死字段清理。
- **契约**：gen:api 再生成（八身份字段可空化+returnable 读面）；迁移零新增。
- **D-21 申报汇总**：UT 执行人断言令牌化（EXECUTOR→NURSE 差异锁定用例新增）；IT 回签 executor_id="66"→登录管理员身份；
  claim assigneeId 空守卫用例随消费面删除（同笔新增令牌锁定用例）。
```

- [ ] **Step 5: PR 开出**（base dev，body 含本册裁决义务清单[D-30/D-31/W-72 方案 A]+D-21 申报+e2e 证据指针）→ CI 六 job 全绿（backend/frontend 门禁按 GC9 观察照常触发）→ **/code-review 五路评审**（A 安全/B 架构/C 数据/D 前端/E 测试）→ 合并去重+置信度终评（≥80 must-fix 修复环或呈报裁决）→门槛项收口→merge→删分支→**直接开工 PR-4C**（按总纲 §3 以合入后 dev 实况撰写 `2026-10-03-p2-pr4c-board-ward.md`——writing-plans 技能，中间零请示）。

---

## Self-Review 记录

1. **覆盖对照**：PR-4B 范围六项=W-72 四域✓（T2 执行单含 A-4/T3 任务+不良事件/T4 摆药）+前端去手输✓（T5）+gen:api✓（T7）+W-66✓（T6 读面+T8 多行化，D-8 并入 T8）+D-3✓（T9）+D-8/D-11✓（T8/T10）；M-2/M-4 移交判断✓（头部背景节）；Spec 注记/TASK 销项✓（T11）；流程契约终验/e2e/review/合入/下一册✓（T12）。无缺口、无增删。
2. **占位符扫描**：全部代码步骤含实码或逐字锚定（「循既有 :xxx 用例同款」均给出断言语义与锚行）；零 TBD/TODO（唯一 TODO 形态为零——本册无预留接口）。
3. **类型一致性**：`receive(String planNo)` 在 T4 接口/实现/Controller 三处一致；`DispensePlanReturnableVO.ReturnableItem` 六字段名在 T6 VO/实现映射与 T8 前端消费（`i.itemSeq/i.returnableQty`）一致；`contextOperatorId()` 三文件私有助手签名（`private static long`）与消费点装箱形态一致；`requestSeq/boardSeq` 守卫变量在 T9 两文件各自闭包内声明。
