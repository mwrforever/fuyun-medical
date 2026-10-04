# P2 PR-4C 大屏通道与病区防线包 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 W-39 哨兵 REST 限行（allowlist 三 GET 端点+wardId 一致性）+ W-68 bigscreen 令牌附调 + 令牌携 wardId（SessionData 扩展）+ A-2 WS SUBSCRIBE 病区防线 + W-40 方案 A fail-closed 病区隔离（含 V1114 绑定行种子）+ D-5 三 app randomUUID 降级 + D-4 大屏 WS 增量行 TTL，令大屏匿名令牌从「全 API 同权」收窄到「单病区只读三端点+单病区订阅」，护士 REST/WS 读面按当班病区归属校验。

**Architecture:** 组合①+②（评审 A-5 定案，勿白名单化）——①bigscreen 前端 REST 附哨兵令牌解决三端点 401；②AuthTokenInterceptor 增哨兵判定把哨兵令牌 REST 面收窄到三端点 allowlist 且校验 wardId 一致性。W-40 按用户裁决 D-29 采方案 A fail-closed：复用 `nursing.nurse_assignment` 派生「护士→当班病区集」（无 ADMIN 角色豁免，绑定行种子与守卫同批交付防锁死）；WS 侧 NursingWebSocketConfig 增第二 ChannelInterceptor 拦 SUBSCRIBE 帧（登录态查绑定集、哨兵比对令牌 wardId）。D-4/D-5 为前端独立小修。

**Tech Stack:** Spring Boot 3.5 + Spring WebSocket（ChannelInterceptor）+ MyBatis-Plus + Flyway、JUnit5+Mockito+Testcontainers IT、Vue 3 + TS + Vitest + @vue/test-utils、openapi-typescript 7.13.0。

**制定日期：** 2026-10-04。**基线：** dev@31fc9cb（PR #67 / PR-4B 合入后）。**分支：** `feat/p2-pr4c-board-ward`（Task 1 自建）。**总纲：** `docs/superpowers/plans/2026-10-03-p2-pr4-overview.md`（裁决 D-28~D-35 与流程契约在彼）。

**行号声明：** 本计划全部行号来自撰写时实况现场重核（dev@31fc9cb）；执行时以 dev 实况重核——行号漂移时以「方法名/锚点代码」定位为准。

**背景与移交判断（写明存档）：**
- 调研档案：`.superpowers/pr4-research/r1-security.md` §3（大屏通道修法对比+A-2 防线设计）、`r2-tickets.md` §3（W-40 方案 A 归属载体实测）、`r3-review-leftovers.md` §四（D-5 降级先例）§六（A-5 与 W-68 工单原文冲突）；评审档案 `.superpowers/code-review-pr63/findings-D.md`（D-4 WS 行 TTL/D-5 randomUUID）。
- r1 §3.4 曾建议「无绑定行放行+warn」灰度语义——**已被用户裁决 D-29 推翻**：一律 fail-closed（无 ACTIVE 绑定行 403），绑定行种子同批交付。计划按 D-29 撰写，勿回头。
- **双标识空间在案**：护理编码（`W01`/`W-IT-9006`）与 iot/大屏数字串（`1001`）并存（W-74 工单在案，映射面归 M16 联调冻结）。本计划所有 wardId 校验均为**字符串等值比对**，不关心空间——哨兵令牌 wardId 与请求/订阅 wardId 天然同空间自洽；iot/ward 域 Long 型 wardId 端点**不挂** W-40 守卫（空间错配，留 W-74 后收敛），计划 Task 6 显式声明边界。
- workstation 不订阅 `/topic/nursing/board`（grep 实证零命中）——WS 防线影响面=bigscreen 哨兵+NurseBoardWsIT 登录态链路。

## Global Constraints

- **GC1 裁决链**：D-29（W-40 方案 A+fail-closed+绑定行种子同批）；评审 A-5（禁白名单化三端点，组合①+②为定案）；D-31（D-4/D-5 顺手包归本册）。哨兵判定锚点=`loginName=="bigscreen"`（SessionData 随 verify 可得，TokenClaims 线格式**不动**——全令牌契约变更不在本册）。
- **GC2 fail-closed 语义**：无 ACTIVE 绑定行一律 403（含 ADMIN 角色账号——不做角色豁免）；唯一豁免=哨兵操作者 `"0"`（HTTP 层已由限行+wardId 一致性校验覆盖，service 侧豁免为防御纵深而非二次授权）。种子迁移与守卫代码同 PR 交付（单 PR 原子合入即满足 D-29「同批」）。
- **GC3 时区红线**：业务日界一律 `LocalDate.now(TimeConstants.HEALTHCARE_TZ)`（WardAccessService 当日窗口判定）禁裸 LocalDate.now()；本册无前端时点出网新面。
- **GC4 OpenAPI 契约变更纪律**：bigscreen-token 增 query 参数后必须 gen:api 再生成入库（Task 8）；导出用 docker run 一次性容器全量 boot（**SPRING_MAIN_LAZY_INITIALIZATION 严禁设置**——D-18 lazy 失效教训）；生成物 diff 逐块核对仅预期面。
- **GC5 commitlint**：body 每行 ≤100 字符；push 前主控 `npx commitlint --from origin/dev --to HEAD` 自查。
- **GC6 门禁命令形态**：后端单任务 `cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl <模块> -am verify -DskipITs`；指定 IT 加 `-Dit.test=<IT 名> -Dtest=NoSuchTest -Djacoco.skip=true -Dfailsafe.failIfNoSpecifiedTests=false`；前端 `cd web && pnpm --filter bigscreen test -- <spec>`；收口全量 verify+前端六连。
- **GC7 迁移纪律**：本册占 **V1114**（全局最大 V1113，CHANGELOG 先记再占）；A.4.1-3 已应用禁改；种子幂等照 V303 `INSERT...SELECT...WHERE NOT EXISTS` 先例。
- **GC8 文件卫生与分层**：UTF-8 无 BOM、LF、注释/日志全中文；modulith 零反向依赖（nursing 只依赖 system **api** 包——TokenPrincipal/TokenVerifier 新面落 api 包，record 包 SessionData **不外泄**）；死代码零容忍；`.superpowers/`、`docs/progress/` 不入提交面。
- **GC9 装配清单**：每任务报告必含「装配清单（物理核对）」节。
- **GC10 核心链路覆盖**：本册属鉴权路径（核心功能）——哨兵限行/病区守卫/WS 防线改造分支单测全覆盖，模块 IT 全绿（含哨兵三端点 200/越区 403/写面 403/登录态无绑定 403）。
- **GC11 D-21 断言纪律**：fail-closed 上线后既有 IT 红=预期行为变更，同步造绑定行适配（收紧而非放宽），逐任务申报改动断言清单。
- **GC12 安全红线**：本册零敏感字段新增；哨兵 403/越区拒绝日志记 wardId+uri+errorCode，禁打令牌内容。

---

### Task 1: 立项底座

**Files:**
- Modify: `CHANGELOG.md`（PR-4C 立项条目+V1114 占号预告）
- Add: `docs/superpowers/plans/2026-10-03-p2-pr4c-board-ward.md`（本计划入库）

**Interfaces:**
- Produces: 分支 `feat/p2-pr4c-board-ward`（基于 dev@31fc9cb）；后续任务在此分支追加单笔提交。

- [ ] **Step 1: 建分支**

```bash
cd /d/code/project/fuyun-medical
git checkout dev && git pull origin dev
git checkout -b feat/p2-pr4c-board-ward
```

- [ ] **Step 2: CHANGELOG 立项（先记再改）**

在 `CHANGELOG.md` 未发布段新增：

```markdown
### PR-4C 大屏通道与病区防线包（立项 2026-10-04）
- 占用迁移号 V1114（nurse_assignment 演示/运维账号绑定行种子——先记再占，全局最大 V1113）
- 范围：W-39 哨兵 REST 限行（三端点 allowlist+wardId 一致性）+令牌携 wardId+W-68 bigscreen 令牌附调
  +A-2 WS SUBSCRIBE 病区防线+W-40 方案 A fail-closed（D-29，绑定行种子同批）+D-5 randomUUID 降级+D-4 WS 行 TTL
- 裁决依据：总纲 D-29/D-31 与评审 A-5（组合①+②，禁白名单化）；r1 §3.4 灰度语义已被 D-29 推翻
```

- [ ] **Step 3: 提交**

```bash
git add CHANGELOG.md docs/superpowers/plans/2026-10-03-p2-pr4c-board-ward.md
git commit -m "docs: PR-4C 立项——大屏通道与病区防线包计划落盘"
```

---

### Task 2: system 会话链携 wardId + TokenPrincipal api 面

**Files:**
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/record/SessionData.java`
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/record/SessionUser.java`
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/controller/AuthController.java:98-101`
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/service/IAuthService.java:49-62`
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/service/impl/AuthServiceImpl.java:222-233`
- Add: `backend/fuyun-system/src/main/java/com/fuyun/system/api/TokenPrincipal.java`
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/api/TokenVerifier.java`
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/service/impl/TokenServiceImpl.java`（实现 verifyAccessPrincipal）
- Test: `backend/fuyun-system/src/test/java/com/fuyun/system/service/impl/AuthServiceImplTest.java`、`TokenServiceImplTest.java`
- Test: `backend/fuyun-app/src/test/java/com/fuyun/app/AuthFlowIT.java`（增 bigscreen-token 签发用例）

**Interfaces:**
- Produces: `record SessionData(Long userId, String loginName, String displayName, Long employeeId, Long orgId, List<String> roles, String wardId)`（wardId 可 null——登录态恒 null，哨兵签发时透传）；`SessionUser` 同构增参。
- Produces: `record TokenPrincipal(Long userId, String loginName, String wardId)`（api 包，跨模块最小暴露面——nursing WS 防线消费）。
- Produces: `TokenVerifier.verifyAccessPrincipal(String rawToken)` 返回 `TokenPrincipal`（失败返回 null，防枚举口径与 verifyAccessToken 一致）；旧方法 `verifyAccessToken` 保留（iot/outpatient 消费方不动）。
- Produces: `IAuthService.issueBigscreenToken(String wardId)`（wardId 可 null=泛哨兵令牌，仅够 WS CONNECT 的 queue 屏使用；REST 三端点与 nursing 订阅均要求非 null 才能过校验）。
- Consumes: 既有 `TokenServiceImpl.verifyInternal` 六步校验链（复用不重写）。

- [ ] **Step 1: 写失败测试（AuthServiceImplTest 增两用例）**

```java
@Test
void issueBigscreenTokenCarriesWardIdIntoSentinelSession() {
    // 携病区签发：哨兵会话 wardId 透传（W-39 通道锚点——REST 限行与 WS 订阅防线的比对源）
    authService.issueBigscreenToken("1001");
    ArgumentCaptor<SessionUser> captor = ArgumentCaptor.forClass(SessionUser.class);
    verify(tokenService).issueAccess(captor.capture(), any());
    assertThat(captor.getValue().wardId()).isEqualTo("1001");
}

@Test
void issueBigscreenTokenWithoutWardIdYieldsNullWardSession() {
    // 泛哨兵（候诊屏 useQueueStomp 无病区概念）：wardId 归一 null 而非空串（空串会过字符串等值误匹配）
    authService.issueBigscreenToken(null);
    ArgumentCaptor<SessionUser> captor = ArgumentCaptor.forClass(SessionUser.class);
    verify(tokenService).issueAccess(captor.capture(), any());
    assertThat(captor.getValue().wardId()).isNull();
}
```

（既有用例 `issueBigscreenTokenIssuesShortLivedAccessForSentinelSession` 的 `issueBigscreenToken()` 调用点同步改新签名，断言不动——D-21 申报：签名扩展非行为变化。）

- [ ] **Step 2: 跑红**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-system -am verify -DskipITs
```
预期：编译失败（wardId 字段/参数不存在）。

- [ ] **Step 3: 实现**

`SessionData`/`SessionUser` 各追加尾参 `String wardId`（javadoc 补 `@param wardId 大屏哨兵令牌绑定的病区编码，可 null（登录态恒 null；哨兵经 bigscreen-token?wardId= 透传，REST 限行与 WS 订阅防线比对源）`；record 无 compact constructor 则不加——null 归一在签发侧完成）。全仓 `new SessionUser(`/`new SessionData(` 调用点逐一补 `null` 尾参（grep 定位：AuthServiceImpl 登录链、TokenServiceImplTest、AuthServiceImplTest、NurseBoardWsIT 等）。

`AuthController.bigscreenToken` 改（类上若无 `@Validated` 则补——参数级 JSR-303 生效前提）：

```java
@PostMapping("/bigscreen-token")
@Operation(summary = "签发大屏匿名短期令牌（可携病区编码收窄授权面）", operationId = "issueBigscreenToken")
public BigscreenTokenVO bigscreenToken(
        @RequestParam(value = "wardId", required = false)
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "病区编码仅允许字母数字下划线连字符，长度 1-64")
        String wardId) {
    return authService.issueBigscreenToken(wardId == null || wardId.isBlank() ? null : wardId.trim());
}
```

`AuthServiceImpl.issueBigscreenToken` 改（哨兵 SessionUser 构造末参传 wardId 原样）。

`api/TokenPrincipal.java` 新建：

```java
package com.fuyun.system.api;

/**
 * 令牌校验通过后的最小主体摘要（PR-4C W-39/A-2 跨模块消费面）。
 *
 * <p>与 system.record.SessionData 职责分离：本对象只暴露 WS 防线与哨兵限行所需三字段
 * （宪法 B.1 跨模块契约唯一出口=api 包，record 包会话全量状态不外泄）。
 *
 * @param userId   用户 ID，非空；哨兵为 0L（BIGSCREEN_SENTINEL_USER_ID 约定，不与雪花正数冲突）
 * @param loginName 登录名，非空；哨兵判定锚点（=="bigscreen"）
 * @param wardId   哨兵令牌绑定病区编码，可 null（登录态恒 null；泛哨兵=候诊屏 WS 用）
 */
public record TokenPrincipal(Long userId, String loginName, String wardId) {}
```

`TokenVerifier` 增方法（javadoc 说明：与 verifyAccessToken 同源校验链，成功返回主体摘要、失败返回 null 不区分原因——消费方禁止透出细分差异；消费方=WS SUBSCRIBE 防线需 wardId/哨兵锚点而布尔面不足）：

```java
TokenPrincipal verifyAccessPrincipal(String rawToken);
```

`TokenServiceImpl` 实现（复用 verify 链）：

```java
@Override
public TokenPrincipal verifyAccessPrincipal(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
        return null;
    }
    try {
        SessionData session = verify(rawToken, SecurityConstants.TOKEN_TYPE_ACCESS);
        return new TokenPrincipal(session.userId(), session.loginName(), session.wardId());
    } catch (BizException ex) {
        // 校验链任一环节失败归 null（防枚举口径与 verifyAccessToken 一致）
        return null;
    }
}
```

`TokenServiceImplTest` 增用例：携 wardId 哨兵令牌 verifyAccessPrincipal 返回主体三元组；坏签名返回 null；登录令牌 wardId 为 null。

- [ ] **Step 4: AuthFlowIT 增第 9 步（bigscreen-token 签发面）**

```java
@Order(9)
@Test
void bigscreenTokenCarriesWardIdAndIssuesAnonymousAccess() {
    // 携 wardId 签发：匿名 POST 成功、响应 accessToken 非空（wardId 经会话承载，本步锚定签发面；
    // 限行行为面归 Task 3 的第 10 步）
    ResponseEntity<String> resp = postJson("/api/v1/system/auth/bigscreen-token?wardId=1001", null, null);
    assertThat(resp.getStatusCode().value()).isEqualTo(200);
    assertThat(JsonPath.<String>read(resp.getBody(), "$.accessToken")).isNotBlank();
}
```
（postJson 形态对齐 AuthFlowIT 既有匿名 POST 先例——:319-332 候诊榜用例的工具方法名以实况为准。）

- [ ] **Step 5: 跑绿+提交**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-system -am verify -DskipITs
cd .. && cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-app -am verify -Dit.test=AuthFlowIT -Dtest=NoSuchTest -Djacoco.skip=true -Dfailsafe.failIfNoSpecifiedTests=false
git add -A && git commit -m "feat(system): 大屏哨兵令牌携 wardId——SessionData 扩展与 TokenPrincipal api 面（W-39 通道锚点）"
```

---

### Task 3: 哨兵 REST 限行（AuthTokenInterceptor）

**Files:**
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/constants/SecurityConstants.java`（公共化哨兵常量）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/api/SystemErrorCode.java`（增 SYS-1032）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/config/SystemWebConfig.java`（SENTINEL_ALLOWLIST 常量）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/service/impl/AuthServiceImpl.java:52`（private 常量改引公共常量）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/internal/AuthTokenInterceptor.java`
- Test: `backend/fuyun-system/src/test/java/com/fuyun/system/internal/AuthTokenInterceptorTest.java`
- Test: `backend/fuyun-app/src/test/java/com/fuyun/app/AuthFlowIT.java`（增第 10 步限行行为面）

**Interfaces:**
- Consumes: Task 2 的 `SessionData.wardId()`。
- Produces: `SecurityConstants.BIGSCREEN_LOGIN_NAME = "bigscreen"`、`SecurityConstants.BIGSCREEN_SENTINEL_OPERATOR_ID = "0"`；`SystemWebConfig.SENTINEL_ALLOWLIST`（`List<String>`，三端点前缀/精确）；`SystemErrorCode.SENTINEL_ACCESS_DENIED("SYS-1032")`。
- Produces: 哨兵请求规则——`/api/v1/nursing/board/{w}` 与 `/api/v1/ward/infusion-board/{w}` 要求路径尾段 `w.equals(session.wardId())`；`/api/v1/iot/alarms` 要求 query 参数 wardId 等值；其余任意路径 403 SYS-1032。泛哨兵（wardId=null）三端点一律 403。

- [ ] **Step 1: 写失败测试（AuthTokenInterceptorTest 增五用例）**

```java
@Test
void sentinelAllowedWhenBoardPathMatchesSessionWard() throws Exception {
    // 哨兵+allowlist 内+wardId 一致：放行（W-68 三端点 200 的机制面）
    when(tokenService.verify(RAW, SecurityConstants.TOKEN_TYPE_ACCESS))
            .thenReturn(session(BIGSCREEN_LOGIN, "1001"));
    assertThat(interceptor.preHandle(request("GET", "/api/v1/nursing/board/1001"), response, handler)).isTrue();
}

@Test
void sentinelRejectedWhenBoardPathWardMismatches() throws Exception {
    // 越区：路径尾段 != 令牌病区 → 403 SYS-1032（A-2 HTTP 侧面）
    when(tokenService.verify(RAW, SecurityConstants.TOKEN_TYPE_ACCESS))
            .thenReturn(session(BIGSCREEN_LOGIN, "1001"));
    assertThat(interceptor.preHandle(request("GET", "/api/v1/nursing/board/W02"), response, handler)).isFalse();
    verifyForbidden(SYS_1032);
}

@Test
void sentinelRejectedWhenAccessingNonAllowlistedEndpoint() throws Exception {
    // 哨兵调写面（任意非 allowlist 路径）→ 403（W-39/A-1 收敛主体面）
    when(tokenService.verify(RAW, SecurityConstants.TOKEN_TYPE_ACCESS))
            .thenReturn(session(BIGSCREEN_LOGIN, "1001"));
    assertThat(interceptor.preHandle(request("POST", "/api/v1/nursing/assignments"), response, handler)).isFalse();
    verifyForbidden(SYS_1032);
}

@Test
void sentinelWithoutWardRejectedEvenOnAllowlistedEndpoint() throws Exception {
    // 泛哨兵（候诊屏令牌）三端点一律拒——泛哨兵仅够 queue WS CONNECT
    when(tokenService.verify(RAW, SecurityConstants.TOKEN_TYPE_ACCESS))
            .thenReturn(session(BIGSCREEN_LOGIN, null));
    assertThat(interceptor.preHandle(request("GET", "/api/v1/nursing/board/1001"), response, handler)).isFalse();
}

@Test
void loginSessionUnaffectedBySentinelGuard() throws Exception {
    // 登录态零影响：admin 任意路径照旧放行（回归锚）
    when(tokenService.verify(RAW, SecurityConstants.TOKEN_TYPE_ACCESS))
            .thenReturn(session("admin", null));
    assertThat(interceptor.preHandle(request("POST", "/api/v1/nursing/assignments"), response, handler)).isTrue();
}
```
（`session(loginName, wardId)`/`request(method, uri)`/`verifyForbidden` 为测试内私有辅助，对齐既有用例的 mock/ProblemDetail 断言形态；iot alarms 用例补一条 query 参数等值/不等值，实现自定。）

- [ ] **Step 2: 跑红**（同 Task 2 门禁命令）

- [ ] **Step 3: 实现**

`SecurityConstants` 增：

```java
/** 大屏哨兵登录名（哨兵会话判定锚点——AuthTokenInterceptor 限行与 WS 防线共用） */
public static final String BIGSCREEN_LOGIN_NAME = "bigscreen";
/** 哨兵操作者注入值（userId=0 十进制字符串化——WardAccessService 豁免判定锚点） */
public static final String BIGSCREEN_SENTINEL_OPERATOR_ID = "0";
```

（`AuthServiceImpl` 的 private `BIGSCREEN_LOGIN_NAME` 改引公共常量并删除私有声明——死代码零容忍；`BIGSCREEN_SENTINEL_USER_ID=0L` 保留 private 仅签发用。）

`SystemErrorCode` 增 `SENTINEL_ACCESS_DENIED("SYS-1032")`（沿用 103x 段，javadoc：大屏匿名令牌越权访问只读看板白名单外端点或病区不匹配）。

`SystemWebConfig` 增常量（紧邻 AUTH_WHITELIST）：

```java
/** 哨兵令牌 REST 只读 allowlist（W-39 限行面）：前两条按前缀匹配+尾段 wardId 一致性，第三条精确+query 一致性 */
static final List<String> SENTINEL_ALLOWLIST = List.of(
        "/api/v1/nursing/board/",
        "/api/v1/ward/infusion-board/",
        "/api/v1/iot/alarms");
```

`AuthTokenInterceptor.preHandle` 在 verify 成功、两上下文注入之后、`return true` 之前插入：

```java
// 哨兵限行（W-39）：大屏匿名令牌仅放行只读看板三端点且要求 wardId 一致——
// 令牌本与登录 access 同构同权（IAuthService 演进注记的过渡态），此处把暴露面收窄到单病区只读
if (SecurityConstants.BIGSCREEN_LOGIN_NAME.equals(session.loginName())
        && !sentinelAllowed(request, session)) {
    writeForbidden(request, response, SystemErrorCode.SENTINEL_ACCESS_DENIED, "大屏匿名令牌仅允许访问绑定病区的只读看板端点");
    return false;
}
```

私有方法（同文件追加，照 writeUnauthorized 形态新增 writeForbidden——403+同构 ProblemDetail）：

```java
private boolean sentinelAllowed(HttpServletRequest request, SessionData session) {
    String uri = request.getRequestURI();
    String ward = session.wardId();
    if (ward == null || ward.isBlank()) {
        // 泛哨兵（未携病区签发）：无一致可校，一律拒（fail-closed）
        return false;
    }
    for (String prefix : SystemWebConfig.SENTINEL_ALLOWLIST) {
        if (uri.equals(prefix)) {
            // 精确端点（iot/alarms）：wardId 走 query 参数等值比对
            return ward.equals(request.getParameter("wardId"));
        }
        if (uri.startsWith(prefix)) {
            // 前缀端点（board/infusion-board）：尾段即路径病区编码，字符串等值比对（双标识空间自洽，W-74 不在此收敛）
            return ward.equals(uri.substring(prefix.length()));
        }
    }
    return false;
}
```

- [ ] **Step 4: AuthFlowIT 增第 10 步（限行行为面）**

```java
@Order(10)
@Test
void sentinelTokenRestrictedToBoardAllowlist() {
    String token = JsonPath.<String>read(
            postJson("/api/v1/system/auth/bigscreen-token?wardId=1001", null, null).getBody(), "$.accessToken");
    // 哨兵调写面 → 403 SYS-1032（W-39 收敛主断言）
    ResponseEntity<String> denied = postJson("/api/v1/nursing/assignments", SOME_BODY, token);
    assertThat(denied.getStatusCode().value()).isEqualTo(403);
    assertThat(denied.getBody()).contains("SYS-1032");
    // 哨兵调非 allowlist 读面 → 403（A-1 暴露面收窄）
    assertThat(getJson("/api/v1/system/users", token).getStatusCode().value()).isEqualTo(403);
}
```
（SOME_BODY/getJson(token) 以 AuthFlowIT 既有工具实况对齐；board 三端点 200 断言归 NurseBoardWsIT——Task 6。）

- [ ] **Step 5: 跑绿+提交**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-system -am verify -DskipITs
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-app -am verify -Dit.test=AuthFlowIT -Dtest=NoSuchTest -Djacoco.skip=true -Dfailsafe.failIfNoSpecifiedTests=false
git add -A && git commit -m "feat(system): 哨兵 REST 限行——allowlist 三端点+wardId 一致性+SYS-1032（W-39/A-1 收敛）"
```

---

### Task 4: nursing 病区归属校验服务（WardAccessService）

**Files:**
- Add: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/service/IWardAccessService.java`
- Add: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/service/impl/WardAccessServiceImpl.java`
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/api/NursingErrorCode.java`（增 NS-1028）
- Test: `backend/fuyun-nursing/src/test/java/com/fuyun/nursing/service/impl/WardAccessServiceImplTest.java`

**Interfaces:**
- Consumes: `NurseAssignmentMapper`（既有 BaseMapper）、`OperatorContextHolder`、`SecurityConstants.BIGSCREEN_SENTINEL_OPERATOR_ID`（system api 依赖经 fuyun-nursing 既有 pom——TokenVerifier 同链先例）。
- Produces:

```java
public interface IWardAccessService {
    /** 查操作者当班 ACTIVE 病区集（valid_from<=北京当日<=valid_to，nurse_id=操作者十进制串） */
    List<String> activeBoundWardIds(String operatorId);
    /** REST 守卫入口：取 OperatorContextHolder 身份校验 wardId∈绑定集；哨兵豁免；空/越界 403 NS-1028 */
    void assertWardAllowed(String wardId);
    /** 显式传参版（WS broker 线程无 ThreadLocal——SUBSCRIBE 防线消费） */
    void assertWardAllowedFor(String operatorId, String wardId);
}
```

- [ ] **Step 1: 写失败测试（覆盖：集内过/集外 403/无绑定 403/哨兵豁免/上下文空 403/当日窗口边界）**

```java
@Test
void assertWardAllowedPassesWhenWardInActiveBindings() {
    OperatorContextHolder.set("1");
    when(mapper.selectList(any())).thenReturn(List.of(assignment("W01"), assignment("W02")));
    assertThatCode(() -> service.assertWardAllowed("W01")).doesNotThrowAnyException();
}

@Test
void assertWardAllowedRejectsWardOutsideBindings() {
    OperatorContextHolder.set("1");
    when(mapper.selectList(any())).thenReturn(List.of(assignment("W01")));
    assertThatThrownBy(() -> service.assertWardAllowed("W03"))
            .isInstanceOf(BizException.class)
            .satisfies(ex -> assertThat(((BizException) ex).getErrorCode().getCode()).isEqualTo("NS-1028"));
}

@Test
void assertWardAllowedFailsClosedWhenNoActiveBinding() {
    // D-29 fail-closed：无 ACTIVE 绑定行一律 403（ADMIN 亦无豁免）
    OperatorContextHolder.set("1");
    when(mapper.selectList(any())).thenReturn(List.of());
    assertThatThrownBy(() -> service.assertWardAllowed("W01")).isInstanceOf(BizException.class);
}

@Test
void assertWardAllowedExemptsSentinelOperator() {
    // 哨兵已在 HTTP 层限行+一致性校验，此处豁免=防御纵深（BoardController 双重身份端点）
    OperatorContextHolder.set(SecurityConstants.BIGSCREEN_SENTINEL_OPERATOR_ID);
    when(mapper.selectList(any())).thenReturn(List.of());
    assertThatCode(() -> service.assertWardAllowed("1001")).doesNotThrowAnyException();
    verifyNoInteractions(mapper);
}
```
（另补：OperatorContextHolder 空 → 403；valid_to 已过期行不进集（lambdaQuery 条件锚）；`assertWardAllowedFor` 与 ThreadLocal 版语义等价一条。）

- [ ] **Step 2: 跑红** → **Step 3: 实现**

`NursingErrorCode` 增 `WARD_ACCESS_DENIED("NS-1028")`（沿用 102x 段；javadoc：请求病区不在操作者当班绑定集或无绑定行——W-40 fail-closed）。

`WardAccessServiceImpl`（聚合型 service 不继承 IService，注入 mapper——宪法 A.4.3-20）：

```java
@Service
public class WardAccessServiceImpl implements IWardAccessService {

    private final NurseAssignmentMapper assignmentMapper;

    public WardAccessServiceImpl(NurseAssignmentMapper assignmentMapper) {
        this.assignmentMapper = assignmentMapper;
    }

    @Override
    public List<String> activeBoundWardIds(String operatorId) {
        // 当日窗口按北京钟面医疗日（时区红线 GC4——禁 DB 会话时区 CURRENT_DATE）
        LocalDate today = LocalDate.now(TimeConstants.HEALTHCARE_TZ);
        List<NurseAssignment> rows = assignmentMapper.selectList(Wrappers.lambdaQuery(NurseAssignment.class)
                .select(NurseAssignment::getWardId)
                .eq(NurseAssignment::getNurseId, operatorId)
                .eq(NurseAssignment::getStatus, "ACTIVE")
                .le(NurseAssignment::getValidFrom, today)
                .and(w -> w.isNull(NurseAssignment::getValidTo).or().ge(NurseAssignment::getValidTo, today)));
        return rows.stream().map(NurseAssignment::getWardId).distinct().toList();
    }

    @Override
    public void assertWardAllowed(String wardId) {
        String operator = OperatorContextHolder.get();
        assertWardAllowedFor(operator, wardId);
    }

    @Override
    public void assertWardAllowedFor(String operatorId, String wardId) {
        if (SecurityConstants.BIGSCREEN_SENTINEL_OPERATOR_ID.equals(operatorId)) {
            return; // 哨兵豁免：HTTP 层已限行+wardId 一致性校验（防御纵深，非二次授权）
        }
        if (operatorId == null || operatorId.isBlank()) {
            throw denied("操作者身份缺失，病区访问被拒（fail-closed）");
        }
        List<String> bound = activeBoundWardIds(operatorId);
        if (bound.isEmpty() || !bound.contains(wardId)) {
            // D-29 fail-closed：无绑定行一律 403；有绑定但越区同拒
            log.warn("病区访问被拒：operator={}，wardId={}，boundWards={}", operatorId, wardId, bound);
            throw denied("病区不在当班绑定范围或无有效绑定（fail-closed）");
        }
    }

    private BizException denied(String detail) {
        return new BizException(NursingErrorCode.WARD_ACCESS_DENIED, HttpStatus.FORBIDDEN, detail);
    }
}
```
（TimeConstants 包路径与 NurseAssignment getter 名以实况对齐；日志含 operator/wardId 禁打令牌——GC12。）

- [ ] **Step 4: 跑绿+提交**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-nursing -am verify -DskipITs
git add -A && git commit -m "feat(nursing): 病区归属校验服务——当班绑定集+fail-closed 403 NS-1028（W-40 方案 A）"
```

---

### Task 5: V1114 绑定行种子迁移

**Files:**
- Add: `backend/fuyun-nursing/src/main/resources/db/migration/nursing/V1114__seed_nurse_assignment_binding.sql`

**Interfaces:**
- Produces: dev/CI/IT 环境共用的 admin/doctordemo→W01 绑定行（Flyway 全环境执行——Testcontainers 一次性库同跑，IT 的 W01 锚用例零适配自动通过）。

- [ ] **Step 1: 撰写迁移（幂等照 V303 先例）**

```sql
-- V1114：nurse_assignment 演示/运维账号病区绑定行种子（PR-4C W-40 方案 A / D-29 裁决）。
-- 背景：W-40 fail-closed 上线后无 ACTIVE 绑定行的账号一律 403，须同批为演示/运维账号种绑定行
--   （否则锁死全部账号）。admin=sys_user.id 1、doctordemo=id 3（V303/V704 种子在案）。
-- 语义声明：本两行 assignment_type='PRIMARY' 但 patient_id/bed_no 为 NULL——非护理责任分配，
--   仅承载 W-40 访问授权（应用层 assign 端点的类型一致性校验不适用于种子通道；uk 为部分索引
--   仅覆盖 patient_id/bed_no IS NOT NULL 行，NULL 行不进索引无冲突）。
-- 幂等形态：INSERT...SELECT...WHERE NOT EXISTS（V303 先例）；ID 取固定值（雪花 19 位量级永不冲突）。
INSERT INTO nursing.nurse_assignment
    (id, ward_id, nurse_id, assignment_type, shift_code, bed_no, patient_id,
     valid_from, valid_to, status, created_by, updated_by, deleted)
SELECT 9114000000000000001, 'W01', '1', 'PRIMARY', 'DAY', NULL, NULL,
       DATE '2026-01-01', NULL, 'ACTIVE', 'V1114', 'V1114', 0
WHERE NOT EXISTS (SELECT 1 FROM nursing.nurse_assignment WHERE nurse_id = '1' AND deleted = 0)
UNION ALL
SELECT 9114000000000000002, 'W01', '3', 'PRIMARY', 'DAY', NULL, NULL,
       DATE '2026-01-01', NULL, 'ACTIVE', 'V1114', 'V1114', 0
WHERE NOT EXISTS (SELECT 1 FROM nursing.nurse_assignment WHERE nurse_id = '3' AND deleted = 0);
```
（created_at/updated_at 由 DEFAULT now() 维护——A.4.2-9；表内触发器补 updated_at。）

- [ ] **Step 2: 迁移治理校验**

```bash
MIGRATION_BASE_REF=origin/main python scripts/check-migration-governance.py
```
预期：通过（V1114 通用段、命名规范、幂等）。

- [ ] **Step 3: IT 快速验证（任一 nursing IT 起库跑迁移）+提交**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-app -am verify -Dit.test=NursingPatientContextIT -Dtest=NoSuchTest -Djacoco.skip=true -Dfailsafe.failIfNoSpecifiedTests=false
git add backend/fuyun-nursing/src/main/resources/db/migration/nursing/V1114__seed_nurse_assignment_binding.sql
git commit -m "feat(nursing): V1114 演示运维账号病区绑定行种子——fail-closed 同批配套（D-29）"
```

---

### Task 6: W-40 端点挂守卫 + 可选 wardId 集合过滤 + IT 适配

**Files:**
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/controller/WardController.java:49-52`（listByWard）
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/controller/NursingTaskController.java:71-84`
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/controller/ShiftHandoverController.java:75-82`
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/controller/OrderExecutionController.java:64-74`
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/controller/InfusionController.java:70-73`
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/controller/VitalSignController.java:77-78`（pendingReview）
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/controller/BoardController.java:38-41`
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/controller/AdverseEventController.java:78-88/:156-163`（list/stats）
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/service/IAdverseEventService.java` + `impl/AdverseEventServiceImpl.java`（wardScope 参数）
- Test: 各 controller 单测（守卫委托断言）+ `AdverseEventServiceImplTest`（集合过滤）
- Test(IT 适配): `NurseBoardWsIT.java`（W-IT-9006 绑定行）、`InfusionClosedLoopIT.java`（W-IT-9008，若其调守卫端点——执行时 grep 核实）、其余以非 W01 锚调守卫端点的 IT

**Interfaces:**
- Consumes: Task 4 `IWardAccessService.assertWardAllowed/activeBoundWardIds`；Task 5 V1114 种子。
- Produces: 8 controller 9 端点首行守卫；`IAdverseEventService.list(query, List<String> wardScope)` / `stats(query, wardScope)`（wardScope=null=不过滤[内部/哨兵语义]，非空=wardId ∈ 集合过滤；query.wardId 非空时守卫已校验、service 按 wardId 单值过滤并要求 ∈ wardScope）。
- **范围边界（显式声明）**：ward/iot/inpatient/pharmacy 域的 wardId 端点（`/api/v1/ward/infusion-board`、`/api/v1/ward/vital-board`、`/api/v1/iot/*`、`/api/v1/inpatient/beds:bedMap` 等）**不挂**守卫——Long 型 iot 病区 id 与护理编码异空间（W-74 在案），强行校验必全拒；哨兵面由 Task 3 限行覆盖，登录态留 PR-4D 角色矩阵与 W-74 映射面收敛。

- [ ] **Step 1: 写失败测试（controller 委托断言形态，BoardController 示例）**

```java
@Test
void boardDelegatesWardGuardBeforeSnapshot() {
    // W-40 守卫委托锚：controller 首行必调 assertWardAllowed（越区在守卫层已 403，此处锚定委托顺序）
    controller.board("W01");
    InOrder order = inOrder(wardAccessService, boardService);
    order.verify(wardAccessService).assertWardAllowed("W01");
    order.verify(boardService).board("W01");
}
```
（其余 7 controller 同形态各一条；AdverseEventServiceImplTest 增三用例：query 带 wardId 时按单值过滤且要求 ∈ scope、无 wardId 时按 scope in 过滤、scope 空清单返回空列表[fail-closed 下不会发生，防御]。）

- [ ] **Step 2: 跑红** → **Step 3: 实现**

每 controller 注入 `IWardAccessService`，目标方法首行加 `wardAccessService.assertWardAllowed(wardId);`（javadoc 补一行「W-40：请求病区须 ∈ 操作者当班绑定集（fail-closed，NS-1028）」）。

AdverseEvent list/stats：

```java
@GetMapping("/api/v1/nursing/adverse-events")
public PageResult<AdverseEventVO> list(AdverseEventQueryRequest query) {
    // W-40：携 wardId 时校验归属；不携时按当班绑定集过滤（单病区绑定=默认本病区视角，页面行为不变）
    List<String> scope = wardAccessService.activeBoundWardIds(operator());
    if (query.wardId() != null && !query.wardId().isBlank()) {
        wardAccessService.assertWardAllowed(query.wardId());
        return adverseEventService.list(query, null);
    }
    return adverseEventService.list(query, scope);
}
```
（operator() 取 OperatorContextHolder；哨兵不达此端点[非 allowlist]；service impl 查询条件 `wardId != null ? eq(wardId) : in(scope 非空)`，scope 空清单直接返回空 PageResult——fail-closed 防御。stats 同款。）

- [ ] **Step 4: IT 适配（fail-closed 引发的预期红——D-21 申报清单）**

判定法：`grep -l "wardId=W\|wardId=\"W" backend/fuyun-app/src/test/java/com/fuyun/app/*IT.java` 后逐文件核实是否调守卫端点且锚非 W01。已核实两处，补绑定行（照 Task 5 种子行形态，IT 内 JdbcTemplate 直插——`WardPatientRetirementIT`/`NursingVitalSignFlowIT` 等 W01 锚用例经 V1114 种子自动通过零适配）：

```java
// NurseBoardWsIT @BeforeAll 补（admin 登录态订阅/快照用例的病区锚）
jdbcTemplate.update("""
    INSERT INTO nursing.nurse_assignment
      (id, ward_id, nurse_id, assignment_type, shift_code, bed_no, patient_id,
       valid_from, valid_to, status, created_by, updated_by, deleted)
    VALUES (?, 'W-IT-9006', '1', 'PRIMARY', 'DAY', NULL, NULL,
       DATE '2026-01-01', NULL, 'ACTIVE', 'IT', 'IT', 0)
    """, 9114000000000000101L);
```
（InfusionClosedLoopIT 若调 `/nursing/infusions/active` 同款插 W-IT-9008 行；JdbcTemplate 注入形态对齐各 IT 既有造数先例。）

同时 NurseBoardWsIT 增哨兵 REST 场景（@Order(4)，board 三端点 200+越区 403 的 IT 锚——AuthFlowIT 第 10 步已覆盖写面 403）：

```java
@Order(4)
@Test
void sentinelTokenReadsBoardWithinBoundWardOnly() throws Exception {
    ResponseEntity<String> tokenResp = postJson(
            "/api/v1/system/auth/bigscreen-token?wardId=W-IT-9006", null, null);
    String sentinelToken = JsonPath.<String>read(tokenResp.getBody(), "$.accessToken");
    // 区内：三端点之一 200（W-68 闭合主断言——哨兵令牌附调后匿名大屏恢复）
    assertThat(getJson("/api/v1/nursing/board/W-IT-9006", sentinelToken).getStatusCode().value()).isEqualTo(200);
    // 越区：路径尾段 != 令牌病区 → 403（A-2 HTTP 面）
    assertThat(getJson("/api/v1/nursing/board/W01", sentinelToken).getStatusCode().value()).isEqualTo(403);
}
```

- [ ] **Step 5: 跑绿（含全 nursing IT）+提交**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-nursing -am verify -DskipITs
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-app -am verify -Dit.test='NurseBoardWsIT,NursingPatientContextIT,NursingVitalSignFlowIT,WardPatientRetirementIT,InfusionClosedLoopIT,NursingOrderExecutionFlowIT,NursingDocumentFlowIT,ExecutionRedirectReconcileIT,LinkageNursingTaskIT' -Dtest=NoSuchTest -Djacoco.skip=true -Dfailsafe.failIfNoSpecifiedTests=false
git add -A && git commit -m "feat(nursing): W-40 病区守卫挂九读端点+不良事件绑定集过滤——fail-closed（D-29）"
```

---

### Task 7: A-2 WS SUBSCRIBE 病区防线

**Files:**
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/internal/NursingConnectAuthInterceptor.java`（verify 换 TokenPrincipal+sessionAttributes 承载）
- Add: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/internal/NursingSubscribeWardInterceptor.java`
- Modify: `backend/fuyun-nursing/src/main/java/com/fuyun/nursing/config/NursingWebSocketConfig.java:64-67`（挂第二拦截器）
- Test: `NursingConnectAuthInterceptorTest`（改造适配）、新增 `NursingSubscribeWardInterceptorTest`、`NursingWebSocketConfigTest`
- Test(IT): `NurseBoardWsIT.java`（增 @Order(5) 匿名令牌 WS 场景；既有登录态用例经 Task 6 绑定行已过）

**Interfaces:**
- Consumes: Task 2 `TokenVerifier.verifyAccessPrincipal`；Task 4 `assertWardAllowedFor`。
- Produces: CONNECT 帧会话属性键 `NursingConnectAuthInterceptor.ATTR_TOKEN_PRINCIPAL = "FY_WS_TOKEN_PRINCIPAL"`（`TokenPrincipal` 实例，连接级生命周期）；`NursingSubscribeWardInterceptor` 规则——SUBSCRIBE 帧且 destination 以 `/topic/nursing/board/` 开头时：哨兵要求尾段==principal.wardId()（null 拒）；登录态要求尾段 ∈ 当班绑定集（fail-closed）；其余 destination 放行；无 principal（异常态未 CONNECT）拒。
- 范围边界：仅 nursing WebSocketConfig 挂防线（iot/outpatient WS 的主题族无病区隔离语义，A-2 原文面即 board 族）。

- [ ] **Step 1: 写失败测试（NursingSubscribeWardInterceptorTest 五用例）**

```java
@Test
void subscribeWithinSentinelBoundWardPasses() {
    // 哨兵区内订阅：destination 尾段 == 令牌病区（大屏单病区通道主路径）
    when(accessor.getCommand()).thenReturn(StompCommand.SUBSCRIBE);
    when(accessor.getDestination()).thenReturn("/topic/nursing/board/1001");
    attrs.put(ATTR_TOKEN_PRINCIPAL, new TokenPrincipal(0L, "bigscreen", "1001"));
    assertThatCode(() -> interceptor.preSend(message, channel)).doesNotThrowAnyException();
}

@Test
void subscribeOutsideSentinelWardRejected() {
    // 哨兵越区：全院 board 越层订阅被拒（A-2 主断言）
    when(accessor.getCommand()).thenReturn(StompCommand.SUBSCRIBE);
    when(accessor.getDestination()).thenReturn("/topic/nursing/board/9999");
    attrs.put(ATTR_TOKEN_PRINCIPAL, new TokenPrincipal(0L, "bigscreen", "1001"));
    assertThatThrownBy(() -> interceptor.preSend(message, channel)).isInstanceOf(MessagingException.class);
}

@Test
void sentinelWithoutWardRejectedOnAnyBoardSubscribe() {
    // 泛哨兵（wardId=null）nursing board 订阅一律拒（候诊屏令牌不得订护理板）
    attrs.put(ATTR_TOKEN_PRINCIPAL, new TokenPrincipal(0L, "bigscreen", null));
    assertThatThrownBy(() -> interceptor.preSend(message, channel)).isInstanceOf(MessagingException.class);
}

@Test
void loginSubscribeWithinBindingsPassesAndOutsideRejected() {
    // 登录态：绑定集内过、集外拒（fail-closed 查无绑定同拒——mock 空清单断言拒）
    attrs.put(ATTR_TOKEN_PRINCIPAL, new TokenPrincipal(1L, "admin", null));
    when(wardAccessService.activeBoundWardIds("1")).thenReturn(List.of("W-IT-9006"));
    when(accessor.getDestination()).thenReturn("/topic/nursing/board/W-IT-9006");
    assertThatCode(() -> interceptor.preSend(message, channel)).doesNotThrowAnyException();
    when(accessor.getDestination()).thenReturn("/topic/nursing/board/W02");
    assertThatThrownBy(() -> interceptor.preSend(message, channel)).isInstanceOf(MessagingException.class);
}

@Test
void nonBoardDestinationAndNonSubscribeFramesPassThrough() {
    // 非 board 主题（未来扩展位）与非 SUBSCRIBE 帧（SEND/DISCONNECT）原样放行
    when(accessor.getCommand()).thenReturn(StompCommand.SEND);
    assertThatCode(() -> interceptor.preSend(message, channel)).doesNotThrowAnyException();
}
```
（mock 形态对齐 NursingConnectAuthInterceptorTest 既有 StompHeaderAccessor/MessagingException 手法。）

- [ ] **Step 2: 跑红** → **Step 3: 实现**

`NursingConnectAuthInterceptor` 改造（保留类名与 CONNECT 拒绝语义）：

```java
public static final String ATTR_TOKEN_PRINCIPAL = "FY_WS_TOKEN_PRINCIPAL";
// preSend CONNECT 分支：verifyAccessToken(布尔) → verifyAccessPrincipal(主体)
TokenPrincipal principal = tokenVerifier.verifyAccessPrincipal(token);
if (principal == null) {
    throw new MessagingException("CONNECT 帧鉴权未通过，连接已被服务端拒绝");
}
// 主体存连接级会话属性——SUBSCRIBE 防线经此取 wardId/哨兵锚（零二次 Redis 读）
accessor.getSessionAttributes().put(ATTR_TOKEN_PRINCIPAL, principal);
```

`NursingSubscribeWardInterceptor`（新类，javadoc 声明防线语义与 fail-closed 口径）：

```java
@Component
public class NursingSubscribeWardInterceptor implements ChannelInterceptor {

    static final String BOARD_TOPIC_PREFIX = "/topic/nursing/board/"; // 与 NurseBoardPushListener 前缀逐字对齐

    private final IWardAccessService wardAccessService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessorHelper.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return message; // 非 SUBSCRIBE 帧放行（CONNECT 鉴权已由第一拦截器承担）
        }
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(BOARD_TOPIC_PREFIX)) {
            return message; // 非 board 主题不在防线面（A-2 原文范围）
        }
        Object raw = accessor.getSessionAttributes() == null
                ? null : accessor.getSessionAttributes().get(NursingConnectAuthInterceptor.ATTR_TOKEN_PRINCIPAL);
        if (!(raw instanceof TokenPrincipal principal)) {
            throw new MessagingException("订阅缺少已鉴权会话主体，订阅已被服务端拒绝");
        }
        String wardId = destination.substring(BOARD_TOPIC_PREFIX.length());
        if (SecurityConstants.BIGSCREEN_LOGIN_NAME.equals(principal.loginName())) {
            // 哨兵：单病区通道——尾段必须等于令牌绑定病区（泛哨兵 null 一律拒）
            if (principal.wardId() == null || !principal.wardId().equals(wardId)) {
                log.warn("哨兵越区订阅被拒：destination={}，绑定病区={}", destination, principal.wardId());
                throw new MessagingException("大屏匿名令牌仅可订阅绑定病区的看板主题");
            }
            return message;
        }
        // 登录态：尾段 ∈ 当班绑定集（fail-closed，D-29；403 语义经 MessagingException 转ERROR 帧关闭）
        wardAccessService.assertWardAllowedFor(String.valueOf(principal.userId()), wardId);
        return message;
    }
}
```
（MessageHeaderAccessor 取 accessor 的工具形态对齐 NursingConnectAuthInterceptor 既有写法实况；SecurityConstants 引用经 nursing→system api 依赖。）

`NursingWebSocketConfig.configureClientInboundChannel` 改：

```java
@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    // 双拦截器序：CONNECT 鉴权在前（注入会话主体），SUBSCRIBE 病区防线在后（消费主体）——A-2
    registration.interceptors(connectAuthInterceptor, subscribeWardInterceptor);
}
```
（两拦截器由构造器注入 Bean 形态——照 config 既有注入风格调整，NursingWebSocketConfigTest 同步断言两拦截器挂载。）

- [ ] **Step 4: NurseBoardWsIT 增 @Order(5) 匿名令牌 WS 全链**

```java
@Order(5)
@Test
void sentinelWsSubscribeRestrictedToBoundWard() throws Exception {
    // 哨兵令牌 CONNECT+区内订阅可达（帧可收）+越区订阅被服务端拒（ERROR/无订阅）——A-2 e2e 前置 IT 锚
    String token = JsonPath.<String>read(postJson(
            "/api/v1/system/auth/bigscreen-token?wardId=W-IT-9006", null, null).getBody(), "$.accessToken");
    // 实现形态对齐既有 WS 客户端造数手法（boardTopicPushesBedPatientFrame 的 StompClient 工具）：
    // 1) 越区订阅 /topic/nursing/board/W01 → 断言收 ERROR 帧或 session 异常关闭
    // 2) 区内订阅 /topic/nursing/board/W-IT-9006 → CONNECT 成功且订阅无异常
}
```
（具体断言手法照既有用例实况——`boardTopicPushesBedPatientFrame` :330-376 的 StompSession 工具复用。）

- [ ] **Step 5: 跑绿+提交**

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-nursing -am verify -DskipITs
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-app -am verify -Dit.test=NurseBoardWsIT -Dtest=NoSuchTest -Djacoco.skip=true -Dfailsafe.failIfNoSpecifiedTests=false
git add -A && git commit -m "feat(nursing): WS SUBSCRIBE 病区防线——哨兵单病区+登录态绑定集（A-2，75 分门槛项）"
```

---

### Task 8: 前端令牌附调 + D-5 randomUUID 降级 + gen:api 再生成

**Files:**
- Modify: `web/apps/bigscreen/src/api/bigscreenToken.ts`（令牌缓存下沉 api 层）
- Modify: `web/apps/bigscreen/src/api/http.ts`（Authorization 注入+generateTraceId）
- Modify: `web/apps/bigscreen/src/composables/useNursingStomp.ts`（ensure 改调 api 层+wardId 透传）
- Modify: `web/apps/bigscreen/src/composables/useQueueStomp.ts`（同上，无 wardId）
- Modify: `web/apps/workstation/src/api/http.ts:60`、`web/apps/portal/src/api/http.ts:73`（generateTraceId 降级）
- Modify: `web/packages/shared/src/api.d.ts`（gen:api 再生成——bigscreen-token 增 query 参数）
- Test: `web/apps/bigscreen/src/api/bigscreenToken.spec.ts`、`http.spec.ts`、`composables/useNursingStomp.spec.ts`（既有 14 it 扩展）、workstation/portal http 各一条降级 spec

**Interfaces:**
- Consumes: Task 2 后端契约（POST /v1/system/auth/bigscreen-token?wardId=）。
- Produces: `bigscreenToken.ts` 三导出——`fetchBigscreenToken(wardId?: string): Promise<BigscreenToken>`、`ensureBigscreenToken(wardId?: string): Promise<boolean>`（缓存命中=未过期且 wardId 一致；wardId 变化强制重签）、`getCachedBigscreenToken(): string`（同步读缓存，供 http.ts 拦截器）；useNursingStomp/useQueueStomp 删除各自模块级令牌缓存（收敛单源）。

- [ ] **Step 1: 写失败测试（bigscreenToken.spec 三用例+http.spec 两用例）**

```ts
it('ensureBigscreenToken 携 wardId 出网且缓存按 wardId 区分重签', async () => {
  vi.mocked(http.post).mockResolvedValue({ data: { accessToken: 't1', tokenType: 'Bearer', expiresIn: '300' } });
  await ensureBigscreenToken('1001');
  expect(http.post).toHaveBeenCalledWith('/v1/system/auth/bigscreen-token', undefined, { params: { wardId: '1001' } });
  // 缓存命中：同 wardId 二次调用零出网
  await ensureBigscreenToken('1001');
  expect(http.post).toHaveBeenCalledTimes(1);
  // 换病区：强制重签（换病区重订阅链路依赖）
  vi.mocked(http.post).mockResolvedValue({ data: { accessToken: 't2', tokenType: 'Bearer', expiresIn: '300' } });
  await ensureBigscreenToken('1002');
  expect(http.post).toHaveBeenCalledTimes(2);
});

it('请求拦截器为持有令牌的请求注入 Authorization，无令牌保持匿名', () => {
  // 大屏白名单面（候诊榜）无令牌照常匿名出网；board 三端点携令牌（W-68 主路径）
  vi.spyOn(bigt, 'getCachedBigscreenToken').mockReturnValue('');
  const anon = requestInterceptor({ headers: {} as AxiosHeaders });
  expect(anon.headers.Authorization).toBeUndefined();
  vi.spyOn(bigt, 'getCachedBigscreenToken').mockReturnValue('t1');
  const authed = requestInterceptor({ headers: {} as AxiosHeaders });
  expect(authed.headers.Authorization).toBe('Bearer t1');
});

it('generateTraceId 降级：非安全上下文（randomUUID 缺失）不抛且产出非空串', () => {
  const stub = { Date: { now: () => 1728000000000 }, Math } as unknown as Crypto; // 视实现形态 mock
  // 断言形态：mock crypto.randomUUID 为 undefined 后调用拦截器不抛 TypeError 且 X-Trace-Id 非空
});
```
（D-5 spec 三 app 同款一条：mock `crypto.randomUUID` undefined → 拦截器不抛+traceId 非空。）

- [ ] **Step 2: 跑红**

```bash
cd web && pnpm --filter bigscreen test -- src/api/bigscreenToken.spec.ts src/api/http.spec.ts
```

- [ ] **Step 3: 实现**

`bigscreenToken.ts` 重构（缓存三态：token/wardId/expiresAt 模块级；`ensureBigscreenToken` 命中条件 `cachedToken !== '' && now < expiresAt - 30_000 && (wardId === undefined || wardId === cachedWardId)`；失败清缓存返 false 不抛——语义承接 useNursingStomp 既有 ensureNursingToken javadoc）。

`http.ts` 请求拦截器改：

```ts
import { getCachedBigscreenToken } from './bigscreenToken';
// ...
client.interceptors.request.use((config) => {
  // 大屏令牌附调（W-68）：board 三端点等受保护面携哨兵令牌；白名单面（候诊榜）无令牌匿名照常
  const token = getCachedBigscreenToken();
  if (token !== '') {
    config.headers.Authorization = `Bearer ${token}`;
  }
  config.headers['X-Trace-Id'] = generateTraceId();
  return config;
});
```

`generateTraceId`（三 app http.ts 各内嵌同款，勿抽 shared——运行时导出超出顺带体量，r3 §四结论）：

```ts
/** traceId 生成：非安全上下文（HTTP 部署无 TLS）randomUUID 为 undefined 时降级时间戳+随机串（STOMP 侧同款先例） */
function generateTraceId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}
```

`useNursingStomp.ts`：删除模块级 `nursingToken/tokenExpiresAt/tokenFetchInFlight/TOKEN_REFRESH_SKEW_MS` 与 `ensureNursingToken` 本体，改薄壳调 `ensureBigscreenToken(wardId)`（`connect(wardId)`/`subscribeBoard(wardId)` 处透传当前 wardId——换病区重订阅链路衔接 ensure 的 wardId 变化重签）；`useQueueStomp.ts` 同款收敛调 `ensureBigscreenToken()`。两文件 javadoc 注明「令牌缓存收敛至 api/bigscreenToken.ts 单源（W-68 附调改造）」。

- [ ] **Step 4: gen:api 再生成（契约纪律 GC4）**

后端 exec.jar 构建旧镜像基底覆盖（容器内 mvn 网络不可用——两分钟路线先例）：

```bash
cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl fuyun-app -am package -DskipTests
cd ..
set -a; source deploy/.env; set +a
docker build -t fuyun/backend:pr4c-export -f- backend <<'EOF'
FROM fuyun/backend:dev
COPY fuyun-app/target/fuyun-app-*-exec.jar /app/app.jar
EOF
FY_NET="$(docker network ls --format '{{.Name}}' | grep 'fy-net')"
docker run -d --name pr4c-api-export --network "$FY_NET" --env-file deploy/.env \
  -e SPRING_PROFILES_ACTIVE=dev -e TZ=Asia/Shanghai \
  -e "FUYUN_DATASOURCE_URL=jdbc:postgresql://postgres:5432/${POSTGRES_DB}?reWriteBatchedInserts=true" \
  -e "FUYUN_DATASOURCE_USERNAME=${POSTGRES_USER}" -e "FUYUN_DATASOURCE_PASSWORD=${POSTGRES_PASSWORD}" \
  -e FUYUN_REDIS_HOST=redis -e "FUYUN_REDIS_PASSWORD=${REDIS_PASSWORD}" \
  -e FUYUN_RABBITMQ_HOST=rabbitmq -e "FUYUN_RABBITMQ_USERNAME=${RABBITMQ_USER}" -e "FUYUN_RABBITMQ_PASSWORD=${RABBITMQ_PASSWORD}" \
  -e FUYUN_MINIO_ENDPOINT=http://minio:9000 -e "FUYUN_MINIO_ACCESS_KEY=${MINIO_ROOT_USER}" -e "FUYUN_MINIO_SECRET_KEY=${MINIO_ROOT_PASSWORD}" \
  -e "FUYUN_SECURITY_TOKEN_HMAC_SECRET=${FUYUN_SECURITY_TOKEN_HMAC_SECRET}" \
  -p 127.0.0.1:18080:8080 fuyun/backend:pr4c-export
until curl -fsS http://127.0.0.1:18080/actuator/health | grep -q '"status":"UP"'; do sleep 10; done
curl -fsS http://127.0.0.1:18080/v3/api-docs -o web/api-docs.json
cd web && pnpm gen:api
docker rm -f pr4c-api-export
```
生成物 diff 逐块核对：仅 `/v1/system/auth/bigscreen-token` 增 `wardId` query 参数一处（int64=0 无倒退）。

- [ ] **Step 5: 跑绿+提交**

```bash
cd web && pnpm --filter bigscreen test && pnpm --filter workstation test && pnpm --filter portal test && pnpm lint && pnpm type-check
git add -A && git commit -m "feat(web): 大屏令牌附调三端点+令牌缓存收敛 api 层+三 app traceId 降级（W-68/D-5）"
```

---

### Task 9: D-4 大屏 WS 增量行 TTL 清理

**Files:**
- Modify: `web/apps/bigscreen/src/views/nurse/NurseBoardView.vue`（:203-211/:232-242/:292-306 三处+常量）
- Test: `web/apps/bigscreen/src/views/nurse/NurseBoardView.spec.ts`（既有扩展）

**Interfaces:**
- Produces: 三个平行时间戳 Map（不改动行结构）——`wsRowFirstSeen: Map<string, number>`（key=taskNo）、`callRowFirstSeen: Map<string, number>`（key=告警行唯一键，照 alertRows 现有键形态）、`escalationFirstSeen: Map<string, number>`（key=executionNo）；常量 `WS_ROW_GRACE_MS = 10 * 60 * 1000`（逾期 wsOnly 行宽限窗）、`CALL_ROW_TTL_MS = 5 * 60 * 1000`（呼叫行 TTL）、`ESCALATION_TTL_MS = 30 * 60 * 1000`（升级行 TTL）。
- 语义：mergeOverdueSnapshot/mergeAlertSnapshot/prependEscalationRows 入口统一执行 `purgeExpired(now)`——过期行从数组与 Map 双清；新 WS 帧行 set 时间戳；快照行（snapshot 覆盖）不进 Map（快照自带生命周期）。

- [ ] **Step 1: 写失败测试（三用例）**

```ts
it('wsOnly 逾期行超宽限窗后被退役清除（10 分钟）', () => {
  vi.useFakeTimers();
  // 造 WS 帧 overdue 行（taskNo=T1）→ 快照不含 T1（wsOnly 保留）→ 快进 10 分钟+触发 merge → T1 清除
  vi.advanceTimersByTime(WS_ROW_GRACE_MS + 1);
  // ...断言 overdueRows 不含 T1 且 Map 已清条目
  vi.useRealTimers();
});

it('CALL 告警行超 5 分钟 TTL 后从告警列清除', () => { /* 同款时间快进 */ });

it('escalationRows 超 30 分钟 TTL 退役，容量截断语义保持', () => { /* 同款 */ });
```
（断言不绑定实现细节：以渲染/导出的行为面为准——组件 expose 或 DOM 断言照既有 spec 手法。）

- [ ] **Step 2: 跑红** → **Step 3: 实现**

```ts
/** D-4：WS 派生行 TTL——快照无「解除」帧，长时值守大屏的前插行按首见时间戳退役（防永久驻留误导） */
const wsRowFirstSeen = new Map<string, number>();
const callRowFirstSeen = new Map<string, number>();
const escalationFirstSeen = new Map<string, number>();

function purgeExpiredRows(now: number): void {
  // 三族统一入口：数组过滤过期行 + Map 双清（防泄漏）
  if (overdueRows.value.some((row) => expired(wsRowFirstSeen.get(row.taskNo), now, WS_ROW_GRACE_MS))) {
    overdueRows.value = overdueRows.value.filter((row) => !expired(wsRowFirstSeen.get(row.taskNo), now, WS_ROW_GRACE_MS));
  }
  // alertRows CALL 行 / escalationRows 同款（键形态照实况）...
}

function expired(firstSeen: number | undefined, now: number, ttl: number): boolean {
  return firstSeen !== undefined && now - firstSeen > ttl;
}
```
（mergeOverdueSnapshot 内：新 WS 帧行 `wsRowFirstSeen.set(taskNo, Date.now())`；快照覆盖行 `wsRowFirstSeen.delete(taskNo)`；wsOnly 过滤条件追加 `!expired(...)`。mergeAlertSnapshot 的 callRows 与 prependEscalationRows 同款接入。`Date.now()` 直用——纯展示层时钟，无出网时点，不涉时区红线。）

- [ ] **Step 4: 跑绿+提交**

```bash
cd web && pnpm --filter bigscreen test -- src/views/nurse/NurseBoardView.spec.ts && pnpm lint && pnpm type-check
git add -A && git commit -m "feat(bigscreen): WS 增量行 TTL 退役——逾期宽限窗/呼叫 5 分钟/升级 30 分钟（D-4）"
```

---

### Task 10: Spec 注记与工单销项

**Files:**
- Modify: `docs/specs/modules/01-system.md`（bigscreen-token wardId 参数+哨兵限行注记）
- Modify: `docs/specs/modules/05-nursing.md`（W-40 守卫九端点+WS 防线+nurse_assignment 授权语义注记）
- Modify: `TASK.md`（W-39/W-40/W-68/D-4/D-5 销项；W-68 行改写防白名单回头路；范围边界注记 iot/ward 域端点留 W-74/PR-4D）

**Interfaces:**
- Consumes: Task 2~9 落地事实。

- [ ] **Step 1: 01-system.md 注记**（bigscreen-token 端点描述增 wardId 可选参数与哨兵限行 SYS-1032 语义、TokenPrincipal api 面注记）
- [ ] **Step 2: 05-nursing.md 注记**（九读端点 403 NS-1028 守卫表+nurse_assignment「当班绑定集=访问授权锚」双语义声明[责任分配+访问授权]+V1114 种子行语义+WS SUBSCRIBE 防线章节）
- [ ] **Step 3: TASK.md 销项**——W-39/W-40/W-68 三行改「✅ PR-4C 收口」形态（照 W-72 先例：✅ 前缀+交付摘要）；**W-68 行必须改写修法指引**（r3 §六：删除「三端点入白名单」选项，改记「修法采 A-5 反向意见+组合①+②（令牌附调+哨兵限行）；白名单化已被评审 A-5 否决」——防后续执行者回头路）；D-4/D-5 如有独立登记行一并销项；新增注记行「iot/ward 域 Long 型 wardId 端点登录态守卫留 PR-4D/W-74 收敛（PR-4C 范围边界）」。
- [ ] **Step 4: 提交**

```bash
git add docs/specs/modules/01-system.md docs/specs/modules/05-nursing.md TASK.md
git commit -m "docs: PR-4C Spec 注记与工单销项——W-39/W-40/W-68/D-4/D-5 收口留痕"
```

---

### Task 11: 收口（主控执行，非 SDD 派发）

按总纲 §3 流程契约：①范围全量终验（`cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml verify` 全模块+前端六连，verify 主控后台直跑轮询日志禁派子代理）②真机 e2e 三面（compose 真栈起服+浏览器：匿名大屏三端点 200/越区订阅拒/哨兵调写面 403，证据留 SDD 工作区 probe/）③/code-review 五路并行评审（A 安全/B 架构/C 数据/D 前端/E 测试）→置信度终评→must-fix 修复环 ④PR 开出（base dev、body 含 D-29/D-31/A-5 裁决与义务清单）→CI 六 job 盯绿→merge→删分支 ⑤CHANGELOG 收口条目 ⑥直接开工 PR-4E（零请示）。

e2e 断言口径（总纲 §2 C 行）：
- 匿名大屏三端点 200：`POST /api/v1/system/auth/bigscreen-token?wardId=1001` → `GET /api/v1/nursing/board/1001`、`GET /api/v1/ward/infusion-board/1001?`（path 尾段）、`GET /api/v1/iot/alarms?wardId=1001` 全 200（浏览器 Network 面取证）；
- 越区订阅拒：大屏页 wardId=1001 令牌下手工构造订阅 `/topic/nursing/board/9999` → 服务端拒（console ERROR 帧或连接关闭截图）；
- 哨兵调写面 403：同令牌 `POST` 任意写端点（如 `/api/v1/nursing/assignments`）→ 403 SYS-1032 ProblemDetail 截图。

---

## Self-Review 记录（撰写时已核）

1. **Spec 覆盖**：总纲 §2 PR-4C 行七项（W-39 限行→T3、wardId 携带→T2、W-68 附调→T8、A-2 防线→T7、W-40 fail-closed+种子→T4/T5/T6、D-5→T8、D-4→T9）+e2e 面→T11，无缺口。
2. **占位符扫描**：T6 IT 适配「执行时 grep 核实」与 T7/T9「照既有 spec 手法」为对既有仓库先例的锚定引用（非 TBD——具体 SQL/断言代码已给出形态与锚点），可接受。
3. **类型一致性**：`TokenPrincipal(userId, loginName, wardId)` 三字段在 T2 定义/T7 消费一致；`assertWardAllowedFor(operatorId, wardId)` 在 T4 定义/T7 消费一致；`ensureBigscreenToken(wardId?)`/`getCachedBigscreenToken()` 在 T8 内部自洽。
