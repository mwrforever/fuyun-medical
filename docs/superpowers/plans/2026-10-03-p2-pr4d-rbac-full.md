# PR-4D 全量 403 鉴权包（RBAC Full）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 D-28 裁决为全仓 302 个业务端点挂 403 角色鉴权（AuthorizationInterceptor + PermissionRegistry 启动装载），落业务角色与双命名空间权限点种子，填实登录链路 UserVO.permissions，收紧前端 hasRoutePermission 空集语义，并承载 W-90（iot WS 订阅收窄）与 W-91/W-93（同族扫描索引）。

**Architecture:** 拦截器式 403（非注解式）：`AuthorizationInterceptor` 挂 `AuthTokenInterceptor` 之后（同 registry 顺序注册），数据源为启动期从 `sys_permission` 装载的 `PermissionRegistry`（内存 Map，`PathPatternParser` 匹配与 MVC 路由同源）；权限判定=会话角色（RoleContextHolder，已注入）∩ 端点允许角色集。ADMIN 角色运行期一票放行（不种绑定行）；登记面 fail-closed（命中权限点但角色不符=403 SYS-1033），未登记面登录态放行+warn（哨兵三端点豁免挂码），矩阵完整性由 RbacMatrixIT 全量对照断言守护。登录链路 SessionUser/SessionData 双 record 增 permissions 字段（会话自包含，refresh 重组同源），前端删「空集全放行」分支。

**Tech Stack:** Java 17 / Spring Boot 3.5 MVC Interceptor + `PathPatternParser`；Flyway V1116~V1119（system×2 + nursing + outpatient）；Vue 3 Pinia store 收紧。

## Global Constraints（继承总纲 §4 + 本册专项）

- 迁移号段：V1116/V1117（system 目录，RBAC 种子）+ V1118（nursing 目录，nurse_id 部分索引）+ V1119（outpatient 目录，pay_deadline 部分索引）；CHANGELOG 先记再占；A.4.1-3 已应用禁改。
- **核心包 100% 行覆盖硬门禁**：`com.fuyun.system.service.impl` 在父 POM JaCoCo PACKAGE 规则中（LINE ≥ 1.00）——`RoleServiceImpl`/`AuthServiceImpl` 本次新增/改动行必须全部单测覆盖。
- 时区红线（本册不涉业务日界，但迁移内 `now()` 由 DB DEFAULT 承担，禁应用层写时间）；modulith 零反向依赖（`SecurityConstants` 不可跨模块 import——iot 侧需镜像常量）；UTF-8 无 BOM/LF/中文注释。
- commitlint body 每行 ≤100 字符；push 前 `npx commitlint --from origin/dev --to HEAD` 自查。
- 每任务报告必含「装配清单（物理核对）」节。
- D-21 断言纪律：既有测试断言波及（前端 auth spec 空集语义反转、AuthFlowIT）逐条申报，新断言不低于原严格度。
- OpenAPI 契约：UserVO.permissions 字段已在契约（可选 List<String>），填实值零契约变更，**无需 gen:api 再生成**。
- doctordemo（sys_user id=3）保留 ADMIN 绑定不动（13 个 IT 依赖其全链路调用），仅增绑 DOCTOR 角色（权限只增不减，IT 零影响）。
- IT 单测门禁铁律：指定 IT/单测必带 `-Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true -Dfailsafe.failIfNoSpecifiedTests=false`。

## 本册八项设计裁定（主控调研定稿，实现不得偏离）

| # | 裁定 | 依据 |
| --- | --- | --- |
| D1 | **perm_code API 型形态 = `METHOD /api/v1/...`**（动词+空格+路径模板，如 `POST /api/v1/billing/refunds/{id}/approve`），解决同路径 GET/POST 权限差异（r1 缺口③）；V303 旧 6 点经 V1116 UPDATE 为新形态（绑定关系按 id 不受影响） | M01「权限点编码 = API 路径」语义保留，方法前缀为粒度扩展 |
| D2 | **双命名空间并存**：perm_type=API（后端 403 消费，302 点）+ perm_type=MENU（前端路由守卫消费，33 点=现有冒号码，前端 36 条 meta 去重后 33 码）；F 册后续加 ELEMENT 第三命名空间 | r1 缺口②（改 36 处前端码不推荐） |
| D3 | **ADMIN 运行期全放**：拦截器 roles 含 ADMIN 一票放行 + `findPermissionCodesByUserId` 对 ADMIN 特判返回全量 perm_code（含 MENU，前端侧栏可见）——不种 ADMIN 绑定行（迁移瘦身 ~335 行，超管语义 javadoc+Spec 注记声明） | 常见 RBAC 超管语义；F 册管理台对 ADMIN 列特殊渲染 |
| D4 | **登记面 fail-closed / 未登记面放行+warn**：命中权限点且角色不符=403 SYS-1033（新增错误码，SYS-1032 已占哨兵）；未登记路径登录态放行+warn 日志；完整性由 RbacMatrixIT「全量端点登记对照断言」守护（新增端点不登记→CI 红） | 医疗可用性优先（运行期全量 fail-closed 有全站锁死风险）；哨兵三端点必须可达 |
| D5 | **哨兵三端点豁免挂码**：`GET /nursing/board/{wardId}`、`GET /ward/infusion-board/{wardId}`、`GET /iot/alarms` 不进 API 矩阵（哨兵 roles 空集必被 403）；防线=W-39 哨兵限行+W-40 当班绑定守卫（board 族）；RbacMatrixIT 豁免清单显式断言 | PR-4C 既有防线在位；403 拦截器无法承载哨兵只读语义（r1 §5.1 已论证） |
| D6 | **业务角色集 6 个**（DOCTOR/NURSE/PHARMACIST/CASHIER/REGISTRAR/IOT_ADMIN）+ ADMIN；矩阵按附件 A/B 全量定稿（管理面/资金审批面/规则维护面/集成面=ADMIN 专属不种业务绑定，临床域=对应角色，跨域读面=相关角色集） | 模块 Spec 权限语义推导+前端 33 码域归属 |
| D7 | **演示账号策略**：doctordemo 增绑 DOCTOR（ADMIN 不动）；新种 nursedemo/pharmdemo/cashierdemo/registrardemo/iotdemo 五账号（口令同 Fuyun@2026 形态，bcrypt 哈希，dev/test 演示语义注记）；全部绑 `system.sys_employee` 员工行（displayName 需要） | e2e 403 正反例需要真实业务角色账号 |
| D8 | **W-90 承载分两面**：REST 面=vital-board 等 Long wardId 端点入 403 角色矩阵（角色门禁替代绑定集，标识空间错配 W-74 下唯一可行防线）；WS 面=`/ws/iot` 新增 IotSubscribeInterceptor（哨兵限订 iot 三主题族+global 白名单，登录态放行；wardId 段归属核验因 W-74 映射缺失登记工单不擅自造映射） | W-90 工单原文「REST 403 全量矩阵一并评估承载」 |

---

### Task 1: 立项底座（分支 + CHANGELOG 占号 + 计划入库）

**Files:**
- Create: `docs/superpowers/plans/2026-10-03-p2-pr4d-rbac-full.md`（本文件）
- Modify: `CHANGELOG.md`

**Interfaces:**
- Produces: 分支 `feat/p2-pr4d-rbac-full`（基线 dev@65f8398）；CHANGELOG V1116~V1119 占号条目

- [x] **Step 1: 建分支**：`git checkout -b feat/p2-pr4d-rbac-full`（基于 dev@65f8398）
- [x] **Step 2: CHANGELOG 立项**：追加 PR-4D 条目（V1116/V1117 RBAC 种子 + V1118 nurse_id 索引 + V1119 pay_deadline 索引占号，先记再占）
- [x] **Step 3: 计划入库提交**：`git add CHANGELOG.md docs/superpowers/plans/2026-10-03-p2-pr4d-rbac-full.md && git commit -m "docs(pr4d): 立项——全量 403 鉴权包计划与 V1116~V1119 占号"`

---

### Task 2: V1116 全量权限点种子（sys_permission 302 API + 33 MENU + 旧 6 点 UPDATE）

**Files:**
- Create: `backend/fuyun-system/src/main/resources/db/migration/system/V1116__seed_full_permissions.sql`
- Test: 迁移治理脚本 `MIGRATION_BASE_REF=origin/dev python scripts/check-migration-governance.py`（EXIT=0）

**Interfaces:**
- Produces: `sys_permission` 335 行新权限点（302 API + 33 MENU）+ V303 旧 6 点 perm_code UPDATE 为方法前缀形态；后续 Task 3 的 V1117 绑定迁移按 `perm_code` join 解析 id（勿手写 id）

- [x] **Step 1: 撰写迁移**——结构分四段（照 V303 幂等形态 INSERT...SELECT...WHERE NOT EXISTS；id 用 19 位内固定值，API 点 `1116000000000000001+序`、MENU 点 `1116001000000000001+序` 前缀分段防撞）：
  1. **V303 旧 6 点 UPDATE**（perm_code 加方法前缀；`WHERE perm_code = '旧值' AND perm_type='API'` 双条件守卫，id 逐字保留后追加语义照 V1111 先例）：`/api/v1/system/dicts/{type}`→`GET ...`（注意 V303 用的模板变量名是 `{type}`，矩阵统一 `{typeCode}`——UPDATE 按 V303 原文全串匹配）、dict-types→`POST`、dict-types/{typeCode}/versions→`POST`、dict-versions/{versionId}/items→`POST`、dict-versions/{versionId}/publish→`POST`、practice/check→`POST`
  2. **302 个 API 点 INSERT**：perm_code/perm_name/perm_type 三列（名称用中文业务语义，如「退费审批」）；**逐行照附件 A 矩阵**（路径模板变量名必须与 Controller 实际 @RequestMapping 一致——附件 A 已按代码提取，实现者复核 5 处抽验即可）
  3. **33 个 MENU 点 INSERT**：perm_code=冒号码（如 `patient:archive:create`）、perm_type='MENU'（照附件 B）
  4. **幂等判重**：每行 `WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = '<同值>')`
- [x] **Step 2: 迁移治理校验**：`MIGRATION_BASE_REF=origin/dev python scripts/check-migration-governance.py`，Expected EXIT=0
- [x] **Step 3: 行数自证**：SQL 文件末尾注释登记「API 302 + MENU 33 + UPDATE 6」计数；实现者以 `grep -c "INSERT INTO system.sys_permission" V1116文件` 自证=335
- [x] **Step 4: Commit**：`git add backend/fuyun-system/src/main/resources/db/migration/system/V1116__seed_full_permissions.sql && git commit -m "feat(system): V1116 全量权限点种子——302 API+33 MENU 双命名空间+旧6点方法前缀UPDATE"`

---

### Task 3: V1117 业务角色 + 角色绑定 + 演示账号种子

**Files:**
- Create: `backend/fuyun-system/src/main/resources/db/migration/system/V1117__seed_business_roles_bindings.sql`

**Interfaces:**
- Consumes: Task 2 的 `sys_permission` 全量行（按 perm_code join 解析）
- Produces: 6 业务角色行（id 101~106 小整数，避开 V303 的 id 1）；角色→权限绑定（API+MENU 全量，附件 A「授予角色」列 + 附件 B）；doctordemo 增绑 DOCTOR；5 个演示账号（sys_user id 11~15 + sys_employee 行 + user_role 绑定）

- [x] **Step 1: 角色行**——INSERT 6 行（照 V303 形态，data_scope_type 按域语义：DOCTOR/NURSE/PHARMACIST='WARD'、CASHIER/REGISTRAR='HOSP'、IOT_ADMIN='ALL'；status='ACTIVE'；role_code 即矩阵角色码）
- [x] **Step 2: 绑定行**——压缩形态一条 SQL（VALUES 映射表 join 双表解析 id + NOT EXISTS 幂等，id 用 `1117000000000000000 + row_number() OVER ()` 生成）：
```sql
INSERT INTO system.sys_role_permission (id, role_id, permission_id)
SELECT 1117000000000000000 + row_number() OVER (),
       r.id, p.id
FROM (VALUES ('DOCTOR', 'GET /api/v1/system/dicts/{typeCode}'), /* …附件 A 全量… */ ) AS m(role_code, perm_code)
JOIN system.sys_role r ON r.role_code = m.role_code AND r.deleted = 0
JOIN system.sys_permission p ON p.perm_code = m.perm_code AND p.deleted = 0
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id AND rp.deleted = 0);
```
  注意：VALUES 大列表分段（API 绑定段 + MENU 绑定段两段 INSERT，防单语句过长）；**同一权限点多角色的展开为多 VALUES 行**（如 `('DOCTOR','GET ...'),('NURSE','GET ...')`）；绑定行数自证注释（API ≈294 + MENU ≈40，以实现者按附件实数登记为准）
- [x] **Step 3: doctordemo 增绑**——`INSERT INTO system.sys_user_role ... SELECT 1117001000000000001, 3, <DOCTOR role_id> WHERE NOT EXISTS(...)`（id=3 在案，V704 注释锚）
- [x] **Step 4: 演示账号 5 个**——每账号三行（sys_user id 11~15 + sys_employee id 11~15 + sys_user_role），loginName=nursedemo/pharmdemo/cashierdemo/registrardemo/iotdemo，password_hash 复用 bcrypt 串（`$2a$10$mySbYjh9bHhK7WDdnoKvtOHx.gU.z9I4fbsKfyomOyvh.9zlt/FjW`，V303 同款，红线注记照抄 V303:70-72「dev/test 联调意义」），绑对应业务角色
- [x] **Step 5: 迁移治理校验**：同 Task 2 Step 2，Expected EXIT=0
- [x] **Step 6: Commit**：`git add backend/fuyun-system/src/main/resources/db/migration/system/V1117__seed_business_roles_bindings.sql && git commit -m "feat(system): V1117 业务角色六席位+全量角色权限绑定+演示账号族种子"`

---

### Task 4: PermissionRegistry + AuthorizationInterceptor 403 拦截器（TDD）

**Files:**
- Create: `backend/fuyun-system/src/main/java/com/fuyun/system/internal/PermissionRegistry.java`
- Create: `backend/fuyun-system/src/main/java/com/fuyun/system/internal/AuthorizationInterceptor.java`
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/api/SystemErrorCode.java`（新增 SYS-1033 PERMISSION_DENIED）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/config/SystemWebConfig.java`（registry 注册第二拦截器 + @Import PermissionRegistry Bean）
- Test: `backend/fuyun-system/src/test/java/com/fuyun/system/internal/PermissionRegistryTest.java`
- Test: `backend/fuyun-system/src/test/java/com/fuyun/system/internal/AuthorizationInterceptorTest.java`
- Test: `backend/fuyun-system/src/test/java/com/fuyun/system/api/SystemErrorCodeTest.java`（若在案则顺延计数——先 grep 确认，形态照 NursingErrorCodeTest 先例）

**Interfaces:**
- Consumes: `RoleContextHolder.get()`（common，已注入）；`PermissionMapper.selectList`（在案）
- Produces（后续任务依赖的精确签名）:
  - `PermissionRegistry`：`void load()`（启动装载，幂等可重载）、`Optional<PermissionEntry> resolve(String method, String path)`；`record PermissionEntry(String permCode, Set<String> allowedRoles)`
  - `AuthorizationInterceptor implements HandlerInterceptor`：构造器 `(PermissionRegistry registry, ObjectMapper objectMapper)`；403 body `{type,title,status,detail,errorCode:"SYS-1033",traceId}` 与 AuthTokenInterceptor.writeForbidden 同构
  - 常量：`SecurityConstants.ADMIN_ROLE_CODE = "ADMIN"`（新增，供 Task 5 导出侧共用）

- [x] **Step 1: 写失败测试 PermissionRegistryTest**——用例四条：①装载后 resolve 精确路径命中（`("GET","/api/v1/billing/settlements/{no}")` 直命中）②带路径变量请求归一命中（`("POST","/api/v1/billing/refunds/123/approve")` 匹配模板 `POST /api/v1/billing/refunds/{id}/approve`）③方法不匹配返回 empty（同路径 GET vs POST）④未登记路径返回 empty。mock PermissionMapper 返回固定权限点行+角色绑定行（Registry 内部两步查询：`sys_permission` API 行 + `sys_role_permission` join `sys_role` 取 ACTIVE 角色码）——数据源方法签名：`PermissionMapper.selectList(...)` 与 `RolePermissionMapper/RoleMapper` 组合查询，Registry 构造器注入三 mapper
- [x] **Step 2: 跑测确认失败**：`mvn -B -ntp -pl fuyun-system -am verify -DskipITs -Dtest=PermissionRegistryTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`，Expected FAIL（类不存在）
- [x] **Step 3: 实现 PermissionRegistry**——要点：`load()` 查 `sys_permission WHERE perm_type='API' AND deleted=0` + 绑定角色集（两步单表查询禁连表，照 RoleServiceImpl.findRoleCodesByUserId 先例：先 role_permission 按 permission_id 批量取 role_id，再 sys_role 取 ACTIVE 角色码）；解析结构 `Map<String, Map<PathPattern, PermissionEntry>>`（外键=HTTP 方法，内层=模式→条目；`PathPatternParser.defaultInstance.parse(路径模板)`——与 MVC 路由匹配同源，`{var}` 段天然支持）；`resolve(method,path)` 先按方法取候选 Map 再 `PathContainer.parse(path)` 遍历 match（302 点按方法分组后候选集 ≤ 百级，微秒级）；`@PostConstruct` 不用——由 SystemWebConfig 装配后显式调 `load()`（Bean 生命周期 javadoc 注明：启动装载一次，变更重启生效，运行期刷新归 PR-4F）
- [x] **Step 4: 跑测通过**：同 Step 2 命令，Expected PASS（4/4）
- [x] **Step 5: 写失败测试 AuthorizationInterceptorTest**——用例六条：①ADMIN 角色一票放行（未登记路径也放行——ADMIN 优先级最高）②命中权限点且角色交集非空放行（NURSE 订 board 外的 nursing 端点）③命中但角色不符→403 body 断言 errorCode=SYS-1033+status=403+contentType=application/problem+json ④未登记路径放行（RoleContextHolder 有角色）⑤未登记路径放行时 log.warn 留痕（断言日志或仅断言放行+注释申报 warn 面）⑥哨兵（roles 空集）访问未登记路径放行/访问已登记路径 403。测试注入 RoleContextHolder set/clear（OrderExecutionOperateServiceImplTest 先例）+ Mock HttpServletResponse
- [x] **Step 6: 实现 AuthorizationInterceptor**——`preHandle`：`RoleContextHolder.get()` 含 ADMIN→true；`registry.resolve(method,uri)`：empty→warn(`"403矩阵未登记路径放行：uri={}",uri`)+true；命中→`allowedRoles ∩ sessionRoles` 非空→true 否则 writeForbidden（完整复制 AuthTokenInterceptor.writeForbidden 形态，errorCode=SYS-1033，detail=`"无权访问该功能（权限不足）"`）；**无需 afterCompletion 清理**（上下文清理归 AuthTokenInterceptor，本拦截器只读）
- [x] **Step 7: SystemErrorCode 增 SYS-1033 + 装配**——枚举追加 `PERMISSION_DENIED("SYS-1033")`（javadoc：403 鉴权拒绝，AuthorizationInterceptor 消费）；SystemWebConfig：`@Import` 追加 PermissionRegistry.class？——PermissionRegistry 经 `@Bean` 方法装配（构造器注入三 mapper，@Import 不支持接口式注入，用 @Bean 方法）+ `addInterceptors` 在 AuthTokenInterceptor 之后 `registry.addInterceptor(new AuthorizationInterceptor(permissionRegistry, objectMapper)).addPathPatterns(INTERCEPT_PATH_PATTERN).excludePathPatterns(AUTH_WHITELIST)`（同白名单；哨兵三端点不 exclude——哨兵靠豁免挂码+D4 放行语义）
- [x] **Step 8: 全测通过**：`mvn -B -ntp -pl fuyun-system -am verify -DskipITs`，Expected BUILD SUCCESS（含既有 SystemWebConfigTest——如断言拦截器数需顺延，D-21 申报）
- [x] **Step 9: Commit**：`git add` 六文件 && `git commit -m "feat(system): 403 鉴权拦截器——PermissionRegistry 启动装载+SYS-1033 拒绝（W-37 主体）"`

---

### Task 5: 登录链路 permissions 填实（核心包 100% 约束）

**Files:**
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/record/SessionUser.java`（+permissions 字段，末位插入）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/record/SessionData.java`（+permissions 字段，末位插入）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/service/IRoleService.java` + `impl/RoleServiceImpl.java`（+findPermissionCodesByUserId）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/service/impl/AuthServiceImpl.java`（buildSessionUser 查权限集；refresh 重组透传会话 permissions；issueBigscreenToken 构造器补位 List.of()）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/convert/AuthConverter.java`（删恒空集 @Mapping 表达式+javadoc 收紧）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/vo/UserVO.java`（javadoc 更新：空集=无任何权限）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/constants/SecurityConstants.java`（+ADMIN_ROLE_CODE 常量；Task 4 若已加则跳过）
- Test: `backend/fuyun-system/src/test/java/com/fuyun/system/service/impl/RoleServiceImplTest.java`（新用例）
- Test: `backend/fuyun-system/src/test/java/com/fuyun/system/service/impl/AuthServiceImplTest.java`（既有类扩展：login/refresh/哨兵三链路 permissions 断言）
- Test: `backend/fuyun-system/src/test/java/com/fuyun/system/convert/AuthConverterTest.java`（若在案则扩展，先 grep 确认）

**Interfaces:**
- Consumes: Task 2/3 种子数据；`SecurityConstants.ADMIN_ROLE_CODE`
- Produces: `List<String> IRoleService.findPermissionCodesByUserId(Long userId)`——ACTIVE 角色展开的全量 perm_code（MENU+API 全导出，前端按需消费）；ADMIN 角色特判返回全表 perm_code（含 MENU，前端侧栏可见性依赖）

- [x] **Step 1: 写失败测试 RoleServiceImplTest.findPermissionCodesByUserId**——用例四条：①普通用户两步查询返回绑定角色展开的 perm_code（mock UserRoleMapper/RoleMapper/RolePermissionMapper/PermissionMapper 四 mapper）②无绑定返回空清单 ③**ADMIN 特判**：roles 含 ADMIN 时直接查 sys_permission 全表（deleted=0）返回全量码，不触绑定查询 ④停用角色不展开（复用 findRoleCodesByUserId 的 ACTIVE 过滤语义）
- [x] **Step 2: 跑测失败**：同 Task 4 门禁命令形态，Expected FAIL
- [x] **Step 3: 实现 RoleServiceImpl.findPermissionCodesByUserId**——流程：findRoleCodesByUserId 复用取角色码→含 ADMIN→全表投影 perm_code 返回；否则 role_codes→user_role 反查？——绑定链是 user_role→role_id→role_permission→permission_id→perm_code，三步单表（禁连表）：①复用 findRoleCodesByUserId 得角色码（ADMIN 短路）②sys_role 按 code 取 id（ACTIVE）③role_permission 按 role_id IN 批量取 permission_id④permission 按 id IN 投影 perm_code；方法级 `@Transactional(readOnly=true)`；**该文件在核心包 100% 规则内，新增行全测**
- [x] **Step 4: 写失败测试 AuthServiceImplTest**——用例三条：①login 链路 buildSessionUser 后 SessionUser.permissions 非空（mock roleService.findPermissionCodesByUserId 返回固定清单，断言 toUserVO 出参携带）②refresh 重组 SessionUser 从 SessionData.permissions 透传（构造含 permissions 的 SessionData）③哨兵签发 permissions=空清单（List.of() 补位）；AuthConverterTest（若有）：toUserVO 同名映射断言（删表达式后自动映射）
- [x] **Step 5: 实现**——SessionUser/SessionData 末位加 `List<String> permissions`（javadoc：角色展开授权点集，变更语义=踢出重登生效；SessionData record 缺字段=null 旧会话兼容注记）；AuthServiceImpl 三处构造器补参；AuthConverter 删 `@Mapping(target="permissions", expression=...)` 行+javadoc 改「permissions 经会话身份同名映射（P1-authz 已接线）」；UserVO/TODO 注释清理
- [x] **Step 6: 全测通过**：`mvn -B -ntp -pl fuyun-system -am verify -DskipITs`，Expected BUILD SUCCESS（含 JaCoCo 核心包 100% 检查——本步全量跑不 skip jacoco：`mvn -B -ntp -pl fuyun-system -am verify -DskipITs`）
- [x] **Step 7: Commit**：`git add` 九文件 && `git commit -m "feat(system): 登录链路 permissions 填实——SessionUser/SessionData 扩字段+ADMIN 全量导出"`

---

### Task 6: 前端守卫收紧（空集语义反转）

**Files:**
- Modify: `web/apps/workstation/src/stores/auth.ts`（删「空集全放行」分支+注释/TODO 清理）
- Modify: `web/apps/workstation/src/router/index.ts`（守卫注释更新）
- Modify: `web/apps/workstation/src/components/common/AppSidebar.vue`（过滤注释同步，逻辑零改——共用 hasRoutePermission 口径）
- Test: `web/apps/workstation/src/stores/auth.spec.ts`（语义反转运+:heavy_check_mark:既有用例适配）

**Interfaces:**
- Consumes: Task 5 的登录响应 `user.permissions`（契约字段已在 api.d.ts）
- Produces: `hasRoutePermission` 两态语义（未登记放行/登记且不含拒绝；空集=无权限全拒）

- [x] **Step 1: 写失败测试**——auth.spec.ts 新增用例：①permissions 空清单+路由登记 permission→hasRoutePermission=false（语义反转核心断言）②permissions 含目标码→true ③路由未登记 permission→true（空集也放行——public 路由语义）；既有 mock 无 permissions 的用例逐一适配（补 permissions 数组或改断言，D-21 逐条申报）；AppSidebar.spec（若在案）侧栏过滤用例同步
- [x] **Step 2: 跑测失败**：`pnpm --filter workstation test`，Expected 新用例 FAIL
- [x] **Step 3: 实现**——auth.ts：`hasRoutePermission` 删 `if (permissions.value.length === 0) return true;` 分支与 TODO(P1-authz) 注释块；computed permissions 的 javadoc 注释改「空集=无任何权限（后端已填实，D 册接线）」；router/index.ts:275-276 注释同步；AppSidebar 注释同步
- [x] **Step 4: 前端门禁**：`pnpm lint && pnpm format:check && pnpm type-check && pnpm test && pnpm build`（web/ 目录六连的本地五连，audit 归 CI）
- [x] **Step 5: Commit**：`git add` 四文件 && `git commit -m "feat(workstation): 路由权限守卫收紧——空集语义反转为无权限全拒"`

---

### Task 7: W-90 承载——/ws/iot SUBSCRIBE 防线 + iot/ward REST 面注记

**Files:**
- Create: `backend/fuyun-iot/src/main/java/com/fuyun/iot/internal/IotSubscribeInterceptor.java`
- Modify: `backend/fuyun-iot/src/main/java/com/fuyun/iot/config/IotWebSocketConfig.java`（clientInboundChannel 追加第二拦截器）
- Modify: `backend/fuyun-iot/src/main/java/com/fuyun/iot/constants/IotConstants.java`（+哨兵订阅白名单常量；若常量类名不同以实况为准，镜像 NursingSecurityConstants 先例——**禁止跨模块 import system 的 SecurityConstants**）
- Test: `backend/fuyun-iot/src/test/java/com/fuyun/iot/internal/IotSubscribeInterceptorTest.java`

**Interfaces:**
- Consumes: `StompConnectAuthInterceptor` 已验证的会话注入形态（CONNECT 后 Principal/Attribute 携带 loginName——实现前先读 StompConnectAuthInterceptor 确认会话属性的传递通道，镜像 NursingSubscribeWardInterceptor 的取用形态）
- Produces: 哨兵（loginName=bigscreen）SUBSCRIBE 限订白名单四前缀：`/topic/iot/telemetry/`、`/topic/iot/alarm/`、`/topic/iot/device-status/`、精确 `/topic/iot/dashboard/global`；登录态全放行

- [x] **Step 1: 先读两个先例**——`NursingSubscribeWardInterceptor`（PR-4C 防线蓝本：SUBSCRIBE 帧 destination 拦截形态）+ `StompConnectAuthInterceptor`（会话身份在 message header 的传递通道）——brief 中登记两文件的实况锚点
- [x] **Step 2: 写失败测试**——用例五条：①登录态 SUBSCRIBE 任意 /topic 主题放行 ②哨兵订 telemetry/alarm/device-status 前缀主题放行 ③哨兵订 dashboard/global 精确放行 ④哨兵订白名单外主题（如 /topic/nursing/board/W01 或 /topic/iot/other）拒绝（accessor 返回异常/拦截形态照 nursing 先例）⑤泛哨兵（无 wardId）同白名单语义（本防线不校验 wardId 段——W-74 映射缺失，javadoc 注记+工单留痕）
- [x] **Step 3: 实现**——ChannelInterceptor 拦 SUBSCRIBE 帧：非 SUBSCRIBE 原样放行；取会话 loginName（CONNECT 注入通道）；=哨兵登录名（iot 侧镜像常量，勿 import system）→ destination 前缀/精确白名单判定，越面拒（异常消息含 destination 与 traceId，禁打令牌）；登录态放行；javadoc 如实声明**增量语义**：本防线收窄「哨兵可订任意 /topic/**」为 iot 四主题面；wardId 段归属核验需 W-74 双标识映射收口后升级（不擅自造映射表）
- [x] **Step 4: 装配+全测**：IotWebSocketConfig 追加拦截器（interceptors(connectAuthInterceptor, subscribeInterceptor))；`mvn -B -ntp -pl fuyun-iot -am verify -DskipITs`，Expected BUILD SUCCESS
- [x] **Step 5: Commit**：`git add` 四文件 && `git commit -m "feat(iot): /ws/iot 订阅防线——哨兵限订四主题白名单（W-90 WS 面）"`

---

### Task 8: RbacMatrixIT——403 矩阵集成测试（含全量登记对照断言）

**Files:**
- Create: `backend/fuyun-app/src/test/java/com/fuyun/app/RbacMatrixIT.java`

**Interfaces:**
- Consumes: Task 3 演示账号（nursedemo 等）；Task 4 拦截器；既有 IT 基建（Testcontainers 编排基类——实现前先读 AuthFlowIT 的基类挂接形态与 login helper）
- Produces: 四组断言——①全量登记对照 ②ADMIN 全放 ③业务角色正反例 ④哨兵三端点可达

- [x] **Step 1: 读基建**——AuthFlowIT 基类形态（容器编排/login helper/postJson 断言工具），brief 登记锚点
- [x] **Step 2: 写失败测试（四组用例）**：
  1. **全量登记对照断言**（矩阵完整性守护，D4 语义的测试承载）：测试内硬编码 Controller 端点扫描（`RequestMappingHandlerMapping.getHandlerMethods()` 反射取全部 /api/v1 端点 method+pattern）对照 `PermissionRegistry.resolve` 非空——豁免清单 12 端点显式列在测试常量（3 auth+1 logout+3 portal+1 queues+3 哨兵+1 ingest 不在 /api/v1 天然出界）——**新增端点不登记即本断言红**（CI 拦截，D4 的完整性门禁）
  2. **ADMIN 全放**：admin 登录→抽验 5 个跨域端点（refunds approve/dict-types POST/products POST/dead-letters GET/schedule-templates POST）全非 403（2xx/4xx 业务码均可，断言 ≠403）
  3. **业务角色正反例**：nursedemo 登录→nursing 端点（GET tasks）非 403；跨域反例（POST /billing/refunds/{id}/approve 403+errorCode=SYS-1033、POST /iot/products 403）；registrardemo→GET patients/search 非 403、POST /billing/settlements 403
  4. **哨兵可达**：bigscreen-token 签发→GET /nursing/board/W01（携绑定集 wardId）200、GET /iot/alarms?wardId=… 200（哨兵限行内）、GET /billing/settlements 预期 401/403（越面，哨兵限行拒绝——断言非 200 即可）
- [x] **Step 3: 跑测红→绿**：`JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B verify -pl fuyun-app -am -Dit.test=RbacMatrixIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true -Dfailsafe.failIfNoSpecifiedTests=false`（注意 fuyun-app 依赖模块全量编译，首次跑红=断言缺失登记行，补种子后绿）
- [x] **Step 4: 既有 IT 回归抽验**：AuthFlowIT 全量 + 三个登录态代表 IT（NurseBoardWsIT/OutpatientFullFlowIT/BillingSettlementFlowIT）——admin/doctordemo 均 ADMIN 角色零影响；异常则 D-21 申报修复
- [x] **Step 5: Commit**：`git add backend/fuyun-app/src/test/java/com/fuyun/app/RbacMatrixIT.java && git commit -m "test(app): 403 矩阵 IT——全量登记对照+admin 全放+角色正反例+哨兵可达"`

---

### Task 9: W-91/W-93 同族索引迁移 + 评估注记落 TASK.md

**Files:**
- Create: `backend/fuyun-nursing/src/main/resources/db/migration/nursing/V1118__add_nurse_assignment_nurse_id_index.sql`
- Create: `backend/fuyun-outpatient/src/main/resources/db/migration/outpatient/V1119__add_appointment_pay_deadline_index.sql`
- Modify: `TASK.md`（W-91/W-93 部分销项：索引已落，环境门控/幂等谓词/断链恢复评估结论注记）

**Interfaces:**
- Produces: 两个部分索引；TASK.md 两条工单部分销项（保留评估面注记）

- [x] **Step 1: V1118 索引**——`CREATE INDEX IF NOT EXISTS idx_nurse_assignment_nurse_active ON nursing.nurse_assignment (nurse_id) WHERE deleted = 0 AND status = 'ACTIVE';`（注释：WardAccessServiceImpl.activeBoundWardIds 按 nurse_id 查询支撑，W-91①；valid_from/valid_to 窗口谓词不进索引——部分唯一索引先例与 selectivity 权衡注记）
- [x] **Step 2: V1119 索引**——`CREATE INDEX IF NOT EXISTS idx_appointment_pay_deadline_reserved ON outpatient.appointment (pay_deadline) WHERE deleted = 0 AND status = 'RESERVED';`（注释：scanAndReleaseTimedOut 周期扫描支撑，W-93①/C-F1）
- [x] **Step 3: 迁移治理**：同 Task 2 命令，Expected EXIT=0
- [x] **Step 4: TASK.md 注记**——W-91 条目更新：①索引已随 V1118 落地✅；②环境门控评估结论（V1117 演示账号与 V1114 绑定行同口径：生产语义靠「演示账号仅存于演示环境」前置约束天然隔离，Flyway 社区版无 profile 门控，维持现状+部署红线注记）；③幂等谓词窄化维持登记（演示账号不触发，历史数据环境潜在——留单）。W-93 条目更新：①索引已随 V1119 落地✅；②断链恢复维持登记（15m 主通道保正确性在位，逐单 try-catch 隔离与 nursing 同族统一评估——留单）
- [x] **Step 5: 门禁**：nursing+outpatient 两模块 `mvn -B -ntp -pl <模块> -am verify -DskipITs`（迁移不触代码，编译+单测回归）
- [x] **Step 6: Commit**：`git add` 三文件 && `git commit -m "feat(nursing,outpatient): W-91/W-93 同族扫描索引——nurse_id+pay_deadline 部分索引落地"`

---

### Task 10: 销项与 Spec 注记

**Files:**
- Modify: `TASK.md`（W-37 全量销项、W-90 承载销项、W-91/W-93 部分销项补完、W-74 关联注记）
- Modify: `docs/specs/modules/01-system.md`（§RBAC 节注记：perm_code 方法前缀形态+ADMIN 运行期全放语义+双命名空间）
- Modify: `docs/specs/modules/14-iot.md`（WS 订阅防线注记——照 05-nursing §16 先例形态）
- Modify: `docs/plans/2026-09-25-P2实施计划.md` §3（PR-4E 完成标注回填——A/B/C 册先例形态）

**Interfaces:** 无代码接口；文档收口。

- [x] **Step 1: TASK.md 销项**——W-37 条目改 ✅（全量 302 端点 403+六业务角色+双命名空间+前端收紧+RbacMatrixIT 全量对照门禁，随 PR-4D）；W-90 改 ✅（REST 面入矩阵+WS 面四主题白名单防线；W-74 映射收口后可升级 wardId 段核验——关联注记）；W-91/W-93 部分销项补完（见 Task 9）
- [x] **Step 2: 三处 Spec 注记**——01-system §权限模型节追加「PR-4D 形态」段（perm_code=`METHOD 路径` 双命名空间、ADMIN 一票放行、未登记面放行+完整性测试守护、变更重启生效/运行期刷新归 PR-4F）；14-iot 追加 WS 防线注记节；P2 计划 §3 PR-4E 行完成标注
- [x] **Step 3: 编码钩子**：`pre-commit run --files <涉及文件>` 全过
- [x] **Step 4: Commit**：`git add` 四文件 && `git commit -m "docs(pr4d): 工单销项与 Spec 注记——W-37/W-90 全量销项+W-91/W-93 部分销项"`

---

### Task 11: 收口（主控执行，非 SDD 任务）

范围全量终验（后端 `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml verify` 全 21 模块+前端六连）→ e2e 真栈走查（各角色登录面/侧栏过滤/403 页面：nursedemo 登录侧栏仅护理域、直调收费 API 403；admin 全量侧栏）→ /code-review 五路评审 → 修复环 → PR 开出（base dev）→ CI 六 job 盯绿 → merge → 删分支 → 直接开工 PR-4F。

---

## 附件 A：API 权限点 × 角色矩阵（302 端点全量）

> perm_code = `动词 路径模板`；「授予角色」= 种入 V1117 绑定的业务角色（ADMIN 运行期全放不列）；空白授予角色 = ADMIN 专属（不种绑定行）。路径模板变量名与 Controller 一致。

### A.1 system（9 挂码 / 13 端点；豁免 4：login/refresh/bigscreen-token/logout）

| perm_code | 名称 | 授予角色 |
| --- | --- | --- |
| GET /api/v1/system/dicts/{typeCode} | 字典发布版本读取 | DOCTOR,NURSE,PHARMACIST,CASHIER,REGISTRAR,IOT_ADMIN |
| POST /api/v1/system/dict-types | 字典类型创建 | |
| POST /api/v1/system/dict-types/{typeCode}/versions | 字典版本创建 | |
| POST /api/v1/system/dict-versions/{versionId}/items | 字典条目新增 | |
| POST /api/v1/system/dict-versions/{versionId}/publish | 字典版本发布 | |
| POST /api/v1/system/practice/check | 执业授权校验 | DOCTOR |
| POST /api/v1/system/practice/grants | 执业授权授予 | |
| POST /api/v1/system/practice/grants/{id}/withdraw | 执业授权撤回 | |
| GET /api/v1/system/practice/grants | 执业授权查询 | |

### A.2 patient（34 挂码）

| perm_code | 名称 | 授予角色 |
| --- | --- | --- |
| GET /api/v1/patient/card-accounts/{id} | 卡账户读取 | CASHIER,REGISTRAR |
| POST /api/v1/patient/card-accounts/{id}/freeze | 卡账户冻结 | CASHIER,REGISTRAR |
| POST /api/v1/patient/card-accounts/{id}/close | 卡账户销户 | CASHIER,REGISTRAR |
| GET /api/v1/patient/card-accounts/{id}/txns | 卡账户流水查询 | CASHIER,REGISTRAR |
| POST /api/v1/patient/cards/issue | 就诊卡发放 | REGISTRAR,CASHIER |
| POST /api/v1/patient/cards/bind | 就诊卡绑定 | REGISTRAR,CASHIER |
| POST /api/v1/patient/cards/loss/{cardNo} | 就诊卡挂失 | REGISTRAR,CASHIER |
| POST /api/v1/patient/cards/replace | 就诊卡补卡 | REGISTRAR,CASHIER |
| POST /api/v1/patient/cards/unbind/{cardNo} | 就诊卡解绑 | REGISTRAR,CASHIER |
| GET /api/v1/patient/cards/{cardNo} | 就诊卡读取 | REGISTRAR,CASHIER |
| GET /api/v1/patient/possible-duplicates | 疑似重复工作台 | |
| POST /api/v1/patient/possible-duplicates/{id}/exclude | 疑似重复排除 | |
| POST /api/v1/patient/merges | EMPI 合并申请 | |
| POST /api/v1/patient/merges/{id}/approve | EMPI 合并审批 | |
| POST /api/v1/patient/merges/{id}/split | EMPI 拆分 | |
| GET /api/v1/patient/patients/{patientId}/health-summary | 健康档案摘要 | DOCTOR,NURSE |
| POST /api/v1/patient/patients/{patientId}/health-items | 健康档案项新增 | DOCTOR,NURSE |
| POST /api/v1/patient/health-items/{id}/correct | 健康档案项更正 | DOCTOR,NURSE |
| POST /api/v1/patient/patients | 患者建档 | REGISTRAR,DOCTOR,NURSE |
| POST /api/v1/patient/patients/match-check | 建档匹配检查 | REGISTRAR,DOCTOR,NURSE |
| GET /api/v1/patient/patients/{patientId} | 患者主档读取 | REGISTRAR,DOCTOR,NURSE,PHARMACIST |
| PUT /api/v1/patient/patients/{patientId} | 患者主档更新 | REGISTRAR,DOCTOR |
| GET /api/v1/patient/patients/search | 患者检索 | REGISTRAR,DOCTOR,NURSE,PHARMACIST |
| POST /api/v1/patient/patients/{patientId}/freeze | 患者冻结 | REGISTRAR |
| POST /api/v1/patient/patients/{patientId}/unfreeze | 患者解冻 | REGISTRAR |
| POST /api/v1/patient/identifiers/resolve | 患者标识解析 | REGISTRAR,DOCTOR,NURSE,PHARMACIST |
| POST /api/v1/patient/patients/{patientId}/identifiers | 患者标识登记 | REGISTRAR,DOCTOR,NURSE |
| GET /api/v1/patient/patients/{patientId}/identifiers | 患者标识查询 | REGISTRAR,DOCTOR,NURSE,PHARMACIST |
| GET /api/v1/patient/privacy-auths | 隐私授权查询 | REGISTRAR,DOCTOR |
| POST /api/v1/patient/privacy-auths | 隐私授权登记 | REGISTRAR,DOCTOR |
| GET /api/v1/patient/privacy-mask-rules | 脱敏规则查询 | |
| PUT /api/v1/patient/privacy-mask-rules/{ruleCode} | 脱敏规则维护 | |
| POST /api/v1/patient/privacy/unmask | 明文查阅 | DOCTOR |
| GET /api/v1/patient/privacy-access-logs | 明文查阅审计 | |

### A.3 billing（34 挂码）

| perm_code | 名称 | 授予角色 |
| --- | --- | --- |
| POST /api/v1/billing/arrears-approvals | 欠费审批申请 | |
| POST /api/v1/billing/arrears-approvals/{approvalNo}/approve | 欠费审批通过 | |
| POST /api/v1/billing/arrears-approvals/{approvalNo}/reject | 欠费审批驳回 | |
| POST /api/v1/billing/charge-items | 收费项创建 | |
| GET /api/v1/billing/charge-items/by-code/{itemCode} | 收费项编码查询 | DOCTOR,CASHIER |
| POST /api/v1/billing/charge-items/{id}/combo-components | 组合收费项组件维护 | |
| GET /api/v1/billing/daily-lists | 日结单查询 | CASHIER |
| POST /api/v1/billing/deposits | 押金收取 | CASHIER |
| GET /api/v1/billing/deposits | 押金查询 | CASHIER |
| POST /api/v1/billing/pricing/quote | 计费试算 | DOCTOR,CASHIER |
| POST /api/v1/billing/fees/manual | 手工计费 | DOCTOR,CASHIER |
| GET /api/v1/billing/fees | 费用查询 | DOCTOR,CASHIER |
| POST /api/v1/billing/fees/{id}/cancel | 费用作废 | CASHIER |
| POST /api/v1/billing/insurance/register | 医保登记 | |
| POST /api/v1/billing/insurance/fee-uploads | 医保费用上传 | |
| POST /api/v1/billing/insurance/reverse | 医保冲正 | |
| GET /api/v1/billing/insurance/call-logs | 医保调用日志 | |
| POST /api/v1/billing/insurance/call-logs/{id}/compensation | 医保补偿重试 | |
| POST /api/v1/billing/insurance/credential | 医保凭证配置 | |
| POST /api/v1/billing/insurance-mappings/upsert | 医保对照维护 | |
| GET /api/v1/billing/insurance-mappings/{chargeItemId} | 医保对照查询 | |
| POST /api/v1/billing/charge-items/{id}/prices | 调价新草稿 | |
| POST /api/v1/billing/price-adjustments/{id}/publish | 调价发布 | |
| GET /api/v1/billing/charge-items/{id}/prices | 调价历史查询 | |
| POST /api/v1/billing/pricing-rules/upsert | 定价规则维护 | |
| GET /api/v1/billing/pricing-rules | 定价规则查询 | |
| POST /api/v1/billing/refunds | 退费发起 | CASHIER |
| POST /api/v1/billing/refunds/{id}/approve | 退费审批 | |
| POST /api/v1/billing/refunds/{id}/reject | 退费驳回 | |
| POST /api/v1/billing/refunds/{id}/execute | 退费执行 | CASHIER |
| GET /api/v1/billing/refunds | 退费单查询 | CASHIER |
| POST /api/v1/billing/settlements/preview | 结算预览 | CASHIER |
| POST /api/v1/billing/settlements | 结算收费 | CASHIER |
| GET /api/v1/billing/settlements/{no} | 结算单查询 | CASHIER |

### A.4 pharmacy（26 挂码）

| perm_code | 名称 | 授予角色 |
| --- | --- | --- |
| POST /api/v1/pharmacy/dispenses/{no}/pick | 门诊摆药拣药 | PHARMACIST |
| POST /api/v1/pharmacy/dispenses/{no}/verify | 门诊摆药核对 | PHARMACIST |
| POST /api/v1/pharmacy/dispenses/{no}/issue | 门诊摆药发药 | PHARMACIST |
| POST /api/v1/pharmacy/dispense-returns | 门诊退药 | PHARMACIST |
| GET /api/v1/pharmacy/medication-occupancy | 给药占用查询 | PHARMACIST |
| GET /api/v1/pharmacy/dispenses | 摆药单查询 | PHARMACIST |
| POST /api/v1/pharmacy/dispense-plans/generate | 住院摆药计划生成 | PHARMACIST |
| POST /api/v1/pharmacy/dispense-plans/{no}/pick | 住院摆药拣药 | PHARMACIST |
| POST /api/v1/pharmacy/dispense-plans/{no}/verify | 住院摆药核对 | PHARMACIST |
| POST /api/v1/pharmacy/dispense-plans/{no}/issue | 住院摆药发药 | PHARMACIST |
| POST /api/v1/pharmacy/dispense-plans/{no}/deliver | 住院摆药配送 | PHARMACIST |
| POST /api/v1/pharmacy/dispense-plans/{no}/receive | 住院摆药签收 | PHARMACIST |
| GET /api/v1/pharmacy/dispense-plans | 住院摆药计划查询 | PHARMACIST |
| GET /api/v1/pharmacy/dispense-plans/{no}/label | 摆药标签查询 | PHARMACIST |
| GET /api/v1/pharmacy/dispense-plans/{no}/returnable | 可退明细读面 | PHARMACIST |
| POST /api/v1/pharmacy/drugs | 药品创建 | PHARMACIST |
| PUT /api/v1/pharmacy/drugs/{id} | 药品维护 | PHARMACIST |
| GET /api/v1/pharmacy/drugs/{id} | 药品读取 | DOCTOR,PHARMACIST |
| POST /api/v1/pharmacy/drugs/{id}/insurance-mapping | 药品医保对照 | |
| GET /api/v1/pharmacy/drugs/search | 药品检索 | DOCTOR,PHARMACIST |
| POST /api/v1/pharmacy/prescriptions | 处方开立 | DOCTOR |
| POST /api/v1/pharmacy/prescriptions/{no}/cancel | 处方作废 | DOCTOR |
| GET /api/v1/pharmacy/prescriptions | 处方查询 | DOCTOR,PHARMACIST |
| GET /api/v1/pharmacy/review-tasks | 审方任务查询 | PHARMACIST |
| POST /api/v1/pharmacy/review-tasks/{id}/approve | 审方通过 | PHARMACIST |
| POST /api/v1/pharmacy/review-tasks/{id}/reject | 审方驳回 | PHARMACIST |

### A.5 outpatient（27 挂码 / 31 端点；豁免 4：portal×3+queues tickets×1）

| perm_code | 名称 | 授予角色 |
| --- | --- | --- |
| POST /api/v1/outpatient/appointments | 预约挂号 | REGISTRAR |
| POST /api/v1/outpatient/appointments/{no}/take | 取号 | REGISTRAR |
| POST /api/v1/outpatient/appointments/{no}/cancel | 退号 | REGISTRAR |
| POST /api/v1/outpatient/appointments/{no}/reschedule | 改约 | REGISTRAR |
| GET /api/v1/outpatient/appt-credits | 号源信用查询 | REGISTRAR |
| POST /api/v1/outpatient/appt-credits/{id}/release | 号源信用解除 | REGISTRAR |
| POST /api/v1/outpatient/visits/{visitId}/prescriptions | 门诊开方 | DOCTOR |
| POST /api/v1/outpatient/orders/{no}/cancel | 门诊订单作废 | DOCTOR |
| GET /api/v1/outpatient/orders | 门诊订单查询 | DOCTOR |
| POST /api/v1/outpatient/queue/call | 队列叫号 | REGISTRAR |
| POST /api/v1/outpatient/queue/tickets/{id}/pass | 候诊过号 | REGISTRAR |
| POST /api/v1/outpatient/queue/tickets/{id}/recall | 候诊召回 | REGISTRAR |
| GET /api/v1/outpatient/schedule-templates | 排班模板查询 | REGISTRAR |
| POST /api/v1/outpatient/schedule-templates | 排班模板创建 | |
| PUT /api/v1/outpatient/schedule-templates | 排班模板更新 | |
| POST /api/v1/outpatient/schedules/generate | 号源批量生成 | |
| GET /api/v1/outpatient/schedules | 号源查询 | REGISTRAR |
| POST /api/v1/outpatient/schedules/{id}/stop | 号源停用 | |
| POST /api/v1/outpatient/schedules/{id}/resume | 号源恢复 | |
| GET /api/v1/outpatient/number-pools/available | 号池可用查询 | REGISTRAR |
| POST /api/v1/outpatient/number-pools/{id}/extra-quota | 号池加号 | |
| POST /api/v1/outpatient/triage/check-in | 分诊报到 | NURSE |
| POST /api/v1/outpatient/triage/adjust | 分诊调整 | NURSE |
| POST /api/v1/outpatient/visits/{visitId}/admit | 接诊开始 | DOCTOR |
| POST /api/v1/outpatient/visits/{visitId}/finish | 接诊结束 | DOCTOR |
| GET /api/v1/outpatient/doctor/patient-queue | 医生候诊队列 | DOCTOR |
| POST /api/v1/outpatient/visits/{visitId}/orders | 门诊开单 | DOCTOR |

### A.6 inpatient（40 挂码）

| perm_code | 名称 | 授予角色 |
| --- | --- | --- |
| POST /api/v1/inpatient/admissions | 住院证开立 | DOCTOR |
| GET /api/v1/inpatient/admissions | 住院登记查询 | REGISTRAR,DOCTOR,NURSE |
| POST /api/v1/inpatient/admissions/{no}/schedule | 住院证排床 | DOCTOR |
| POST /api/v1/inpatient/admissions/{no}/cancel | 住院证作废 | DOCTOR |
| POST /api/v1/inpatient/admissions/{no}/register | 入院登记 | REGISTRAR |
| POST /api/v1/inpatient/visits/{visitId}/admit-ward | 入科接收 | REGISTRAR,NURSE |
| GET /api/v1/inpatient/visits/arrears | 在院欠费查询 | REGISTRAR,CASHIER |
| GET /api/v1/inpatient/beds/map | 床位图查询 | REGISTRAR,DOCTOR,NURSE |
| POST /api/v1/inpatient/beds/{id}/reserve | 床位预留 | REGISTRAR,NURSE |
| POST /api/v1/inpatient/beds/{id}/assign | 床位分配 | REGISTRAR,NURSE |
| POST /api/v1/inpatient/beds/{id}/release | 床位释放 | REGISTRAR,NURSE |
| POST /api/v1/inpatient/beds/{id}/disinfect-done | 床位消毒完成 | NURSE |
| POST /api/v1/inpatient/beds/{id}/maintain | 床位维修上报 | NURSE |
| POST /api/v1/inpatient/beds/{id}/maintain-done | 床位维修完成 | NURSE |
| POST /api/v1/inpatient/consultations | 会诊申请 | DOCTOR |
| POST /api/v1/inpatient/consultations/{no}/accept | 会诊接受 | DOCTOR |
| POST /api/v1/inpatient/consultations/{no}/opinion | 会诊意见 | DOCTOR |
| POST /api/v1/inpatient/consultations/{no}/cancel | 会诊取消 | DOCTOR |
| GET /api/v1/inpatient/consultations | 会诊查询 | DOCTOR |
| POST /api/v1/inpatient/visits/{visitId}/discharge-request | 出院申请 | DOCTOR |
| POST /api/v1/inpatient/discharge-requests/{no}/cancel | 出院申请取消 | DOCTOR |
| GET /api/v1/inpatient/discharge-requests/{no}/clearance | 出院结算清单 | DOCTOR,CASHIER |
| POST /api/v1/inpatient/discharge-requests/{no}/confirm | 出院确认 | DOCTOR |
| POST /api/v1/inpatient/visits/{visitId}/orders | 住院医嘱开立 | DOCTOR |
| GET /api/v1/inpatient/orders | 住院医嘱查询 | DOCTOR |
| GET /api/v1/inpatient/orders/{no} | 住院医嘱详情 | DOCTOR,NURSE |
| POST /api/v1/inpatient/orders/{no}/stop | 医嘱停止 | DOCTOR |
| POST /api/v1/inpatient/orders/{no}/cancel | 医嘱作废 | DOCTOR |
| POST /api/v1/inpatient/orders/{no}/revoke-audit | 医嘱撤销审核 | DOCTOR |
| POST /api/v1/inpatient/orders/reorganize | 医嘱重整 | DOCTOR |
| POST /api/v1/inpatient/orders/{no}/resubmit | 医嘱重提交 | DOCTOR |
| POST /api/v1/inpatient/orders/{no}/oral-confirm | 口服药确认 | DOCTOR |
| GET /api/v1/inpatient/order-plans | 执行计划查询 | NURSE |
| POST /api/v1/inpatient/order-plans/standby-trigger | 备用医嘱触发 | NURSE |
| POST /api/v1/inpatient/order-plans/{no}/execute-confirm | 执行计划确认 | NURSE |
| GET /api/v1/inpatient/orders/{no}/trace | 医嘱轨迹查询 | DOCTOR,NURSE |
| GET /api/v1/inpatient/transfer-worklist | 转科工作台 | DOCTOR,NURSE |
| POST /api/v1/inpatient/orders/transfer-check | 转科医嘱核对 | DOCTOR |
| POST /api/v1/inpatient/visits/{visitId}/transfer | 转科执行 | REGISTRAR,NURSE |
| POST /api/v1/inpatient/visits/{visitId}/change-bed | 转床执行 | REGISTRAR,NURSE |

### A.7 nursing（52 挂码 / 53 端点；豁免 1：board/{wardId}）

| perm_code | 名称 | 授予角色 |
| --- | --- | --- |
| POST /api/v1/nursing/adverse-events | 不良事件上报 | NURSE |
| GET /api/v1/nursing/adverse-events | 不良事件查询 | NURSE |
| POST /api/v1/nursing/adverse-events/{no}/handle | 不良事件处置 | NURSE |
| POST /api/v1/nursing/adverse-events/{no}/close | 不良事件关闭 | NURSE |
| POST /api/v1/nursing/adverse-events/{no}/return | 不良事件退回 | NURSE |
| GET /api/v1/nursing/stats/adverse-events | 不良事件统计 | NURSE |
| POST /api/v1/nursing/executions/{no}/needle-out | 输液拔针 | NURSE |
| GET /api/v1/nursing/infusions/active | 在输列表查询 | NURSE |
| POST /api/v1/nursing/io-records | 出入量记录 | NURSE |
| GET /api/v1/nursing/io-records | 出入量查询 | NURSE |
| POST /api/v1/nursing/io-summaries | 出入量小结 | NURSE |
| GET /api/v1/nursing/io-summaries | 出入量小结查询 | NURSE |
| GET /api/v1/nursing/assessment-scales | 评估量表查询 | NURSE |
| POST /api/v1/nursing/assessments | 护理评估 | NURSE |
| GET /api/v1/nursing/assessments | 评估记录查询 | NURSE |
| POST /api/v1/nursing/nursing-records | 护理文书书写 | NURSE |
| GET /api/v1/nursing/nursing-records | 护理文书查询 | NURSE |
| GET /api/v1/nursing/nursing-records/{recordNo} | 护理文书详情 | NURSE |
| POST /api/v1/nursing/nursing-records/{recordNo}/submit | 护理文书提交 | NURSE |
| POST /api/v1/nursing/nursing-records/{recordNo}/revise | 护理文书修订 | NURSE |
| POST /api/v1/nursing/tasks | 护理任务创建 | NURSE |
| GET /api/v1/nursing/tasks | 护理任务查询 | NURSE |
| POST /api/v1/nursing/tasks/{taskNo}/complete | 护理任务完成 | NURSE |
| POST /api/v1/nursing/tasks/{taskNo}/claim | 护理任务认领 | NURSE |
| POST /api/v1/nursing/tasks/generate-routine | 常规任务生成 | NURSE |
| POST /api/v1/nursing/tasks/{taskNo}/cancel | 护理任务取消 | NURSE |
| GET /api/v1/nursing/executions | 执行单查询 | NURSE |
| POST /api/v1/nursing/executions/{no}/sign-receive | 执行单签收 | NURSE |
| POST /api/v1/nursing/executions/{no}/check | 执行单核对 | NURSE |
| POST /api/v1/nursing/executions/{no}/start | 执行开始 | NURSE |
| POST /api/v1/nursing/executions/{no}/finish | 执行完成 | NURSE |
| POST /api/v1/nursing/executions/{no}/cancel | 执行取消 | NURSE |
| GET /api/v1/nursing/executions/occupancy | 给药占用查询 | NURSE |
| GET /api/v1/nursing/executions/{no}/trace | 执行轨迹查询 | NURSE |
| POST /api/v1/nursing/pda/override-check | 破码双授权 | NURSE |
| GET /api/v1/nursing/pda/patient-summary | PDA 患者摘要 | NURSE |
| POST /api/v1/nursing/pda/patrol | PDA 巡视打卡 | NURSE |
| POST /api/v1/nursing/handovers/generate | 交班报告生成 | NURSE |
| POST /api/v1/nursing/handovers/{handoverNo}/complete | 交班完成 | NURSE |
| GET /api/v1/nursing/handovers | 交班查询 | NURSE |
| GET /api/v1/nursing/temperature-charts | 体温图查询 | NURSE |
| POST /api/v1/nursing/temperature-charts/{visitId}/special-events | 体温图特殊事件 | NURSE |
| POST /api/v1/nursing/vital-signs | 体征录入 | NURSE |
| GET /api/v1/nursing/vital-signs | 体征查询 | NURSE |
| GET /api/v1/nursing/vital-signs/pending-review | 体征待复审查询 | NURSE |
| POST /api/v1/nursing/vital-signs/{id}/confirm | 体征复审确认 | NURSE |
| POST /api/v1/nursing/vital-signs/{id}/reject | 体征复审驳回 | NURSE |
| GET /api/v1/nursing/ward-patients | 病区患者列表 | NURSE,DOCTOR |
| GET /api/v1/nursing/ward-patients/{visitId} | 病区患者详情 | NURSE,DOCTOR |
| GET /api/v1/nursing/assignments | 责任分配查询 | NURSE |
| POST /api/v1/nursing/assignments | 责任分配维护 | NURSE |
| DELETE /api/v1/nursing/assignments/{id} | 责任分配解除 | NURSE |

### A.8 iot（52 挂码 / 54 端点；豁免 2：alarms GET[哨兵]+ingest[非 /api/v1]）

| perm_code | 名称 | 授予角色 |
| --- | --- | --- |
| POST /api/v1/iot/alarms/{alarmNo}/acknowledge | 告警确认 | IOT_ADMIN |
| POST /api/v1/iot/alarms/{alarmNo}/close | 告警关闭 | IOT_ADMIN |
| GET /api/v1/iot/alarm-rules | 告警规则查询 | IOT_ADMIN |
| POST /api/v1/iot/alarm-rules | 告警规则创建 | IOT_ADMIN |
| PUT /api/v1/iot/alarm-rules/{id} | 告警规则更新 | IOT_ADMIN |
| DELETE /api/v1/iot/alarm-rules/{id} | 告警规则删除 | IOT_ADMIN |
| POST /api/v1/iot/alarm-rules/{id}/simulate | 告警规则模拟 | IOT_ADMIN |
| POST /api/v1/iot/bindings | 设备绑定创建 | IOT_ADMIN |
| POST /api/v1/iot/bindings/{deviceId}/unbind | 设备解绑 | IOT_ADMIN |
| GET /api/v1/iot/bindings | 设备绑定查询 | IOT_ADMIN |
| GET /api/v1/iot/bindings/wards/{wardId} | 病区绑定清单 | IOT_ADMIN |
| GET /api/v1/iot/bindings/devices/{deviceId}/active | 设备当前绑定查询 | IOT_ADMIN |
| POST /api/v1/iot/commands/confirm-challenge | 命令挑战确认 | IOT_ADMIN |
| POST /api/v1/iot/commands | 设备命令下发 | IOT_ADMIN |
| GET /api/v1/iot/commands | 命令查询 | IOT_ADMIN |
| GET /api/v1/iot/commands/{commandNo} | 命令详情 | IOT_ADMIN |
| GET /api/v1/iot/consume-errors | 消费错误查询 | IOT_ADMIN |
| POST /api/v1/iot/consume-errors/{errorId}/replay | 消费错误重放 | IOT_ADMIN |
| POST /api/v1/iot/consume-errors/{errorId}/abandon | 消费错误放弃 | IOT_ADMIN |
| GET /api/v1/iot/dashboard/summary | 看板汇总 | IOT_ADMIN |
| GET /api/v1/iot/dashboard/wards/{wardId} | 病区看板 | IOT_ADMIN |
| POST /api/v1/iot/devices | 设备注册 | IOT_ADMIN |
| GET /api/v1/iot/devices | 设备查询 | IOT_ADMIN |
| GET /api/v1/iot/devices/{deviceId} | 设备详情 | IOT_ADMIN |
| POST /api/v1/iot/devices/{deviceId}/credential-reset | 设备凭证重置 | IOT_ADMIN |
| POST /api/v1/iot/devices/{deviceId}/disable | 设备停用 | IOT_ADMIN |
| GET /api/v1/iot/devices/{deviceId}/shadow | 设备影子查询 | IOT_ADMIN |
| GET /api/v1/iot/gateways | 网关查询 | IOT_ADMIN |
| POST /api/v1/iot/gateways | 网关创建 | IOT_ADMIN |
| PUT /api/v1/iot/gateways/{gatewayId} | 网关更新 | IOT_ADMIN |
| DELETE /api/v1/iot/gateways/{gatewayId} | 网关删除 | IOT_ADMIN |
| GET /api/v1/iot/linkage-rules | 联动规则查询 | IOT_ADMIN |
| POST /api/v1/iot/linkage-rules | 联动规则创建 | IOT_ADMIN |
| PUT /api/v1/iot/linkage-rules/{id} | 联动规则更新 | IOT_ADMIN |
| DELETE /api/v1/iot/linkage-rules/{id} | 联动规则删除 | IOT_ADMIN |
| GET /api/v1/iot/linkage-logs | 联动日志查询 | IOT_ADMIN |
| POST /api/v1/iot/linkage-logs/{linkageNo}/retry | 联动重试 | IOT_ADMIN |
| GET /api/v1/iot/metrics | 指标字典查询 | IOT_ADMIN |
| POST /api/v1/iot/metrics | 指标字典维护 | IOT_ADMIN |
| GET /api/v1/iot/monitor/consumer-lag | 消费积压监控 | IOT_ADMIN |
| POST /api/v1/iot/products | 产品创建 | IOT_ADMIN |
| POST /api/v1/iot/products/{productId}/model-sync | 产品物模型同步 | IOT_ADMIN |
| GET /api/v1/iot/products | 产品查询 | IOT_ADMIN |
| GET /api/v1/iot/products/{productId} | 产品详情 | IOT_ADMIN |
| PUT /api/v1/iot/products/{productId}/commands | 产品命令注册 | IOT_ADMIN |
| GET /api/v1/iot/products/{productId}/commands | 产品命令查询 | IOT_ADMIN |
| PUT /api/v1/iot/products/{productId}/metric-mappings | 产品指标映射 | IOT_ADMIN |
| GET /api/v1/iot/products/{productId}/metric-mappings | 产品指标映射查询 | IOT_ADMIN |
| GET /api/v1/iot/quality/stats | 质量统计 | IOT_ADMIN |
| GET /api/v1/iot/quality/device-usage | 设备利用率 | IOT_ADMIN |
| GET /api/v1/iot/telemetry/series | 遥测序列查询 | IOT_ADMIN |
| GET /api/v1/iot/telemetry/latest | 遥测最新值 | IOT_ADMIN |

### A.9 ward（18 挂码 / 19 端点；豁免 1：infusion-board/{wardId}）

| perm_code | 名称 | 授予角色 |
| --- | --- | --- |
| POST /api/v1/ward/cold-chain/archives | 冷链档案创建 | IOT_ADMIN |
| GET /api/v1/ward/cold-chain/archives | 冷链档案查询 | IOT_ADMIN |
| GET /api/v1/ward/cold-chain/archives/{archiveNo} | 冷链档案详情 | IOT_ADMIN |
| PUT /api/v1/ward/cold-chain/archives/{archiveNo} | 冷链档案维护 | IOT_ADMIN |
| DELETE /api/v1/ward/cold-chain/archives/{archiveNo} | 冷链档案删除 | IOT_ADMIN |
| POST /api/v1/ward/cold-chain/archives/{archiveNo}/records | 冷链记录登记 | IOT_ADMIN |
| GET /api/v1/ward/cold-chain/archives/{archiveNo}/records | 冷链记录查询 | IOT_ADMIN |
| GET /api/v1/ward/infusion-history/{deviceId} | 输注历史查询 | NURSE,IOT_ADMIN |
| GET /api/v1/ward/vital-board/{wardId} | 病区生命体征板 | NURSE,IOT_ADMIN |
| POST /api/v1/ward/ward-calls | 呼叫发起 | NURSE |
| GET /api/v1/ward/ward-calls | 呼叫查询 | NURSE |
| GET /api/v1/ward/ward-calls/{callNo} | 呼叫详情 | NURSE |
| POST /api/v1/ward/ward-calls/{callNo}/answer | 呼叫应答 | NURSE |
| POST /api/v1/ward/ward-calls/{callNo}/progress | 呼叫处理推进 | NURSE |
| POST /api/v1/ward/ward-calls/{callNo}/complete | 呼叫完成 | NURSE |
| POST /api/v1/ward/ward-calls/{callNo}/transfer | 呼叫转接 | NURSE |
| POST /api/v1/ward/ward-calls/{callNo}/route | 呼叫路由 | NURSE |
| POST /api/v1/ward/ward-calls/{callNo}/cancel | 呼叫取消 | NURSE |

### A.10 integration（10 挂码，全部 ADMIN 专属不种绑定）

| perm_code | 名称 |
| --- | --- |
| GET /api/v1/integration/dead-letters | 死信查询 |
| GET /api/v1/integration/dead-letters/{id} | 死信详情 |
| POST /api/v1/integration/dead-letters/{id}/replay | 死信重放 |
| POST /api/v1/integration/dead-letters/{id}/close | 死信关闭 |
| GET /api/v1/integration/event-publications | 事件发布查询 |
| GET /api/v1/integration/event-registry | 事件注册表查询 |
| GET /api/v1/integration/mdm-subscriptions | 主数据订阅查询 |
| POST /api/v1/integration/mdm-subscriptions | 主数据订阅维护 |
| DELETE /api/v1/integration/mdm-subscriptions/{id} | 主数据订阅删除 |
| GET /api/v1/integration/received-events | 入站事件查询 |

## 附件 B：MENU 权限码 × 角色矩阵（33 码）

> perm_type=MENU，perm_code=冒号码（workstation 路由 meta.permission 既有形态，去重后 33 码）；前端 hasRoutePermission 消费。

| perm_code | 名称 | 授予角色 |
| --- | --- | --- |
| patient:archive:create | 患者建档菜单 | REGISTRAR,DOCTOR,NURSE |
| patient:archive:search | 患者检索菜单 | REGISTRAR,DOCTOR,NURSE,PHARMACIST |
| billing:charge:settle | 收费结算菜单 | CASHIER |
| billing:refund:approve | 退费审批菜单 | ADMIN 专属（无业务角色，侧栏不可见） |
| billing:statement:daily-list | 日结单菜单 | CASHIER |
| pharmacy:drug:maintain | 药品维护菜单 | PHARMACIST |
| pharmacy:dispense:issue | 门诊摆药菜单 | PHARMACIST |
| pharmacy:dispense:return | 门诊退药菜单 | PHARMACIST |
| pharmacy:review:audit | 审方菜单 | PHARMACIST |
| pharmacy:dispense:inpatient | 住院摆药菜单 | PHARMACIST |
| outpatient:registration:register | 预约挂号菜单 | REGISTRAR |
| outpatient:triage:manage | 门诊分诊菜单 | NURSE |
| outpatient:doctor:consult | 医生接诊菜单 | DOCTOR |
| nursing:ward:view | 病区看板菜单 | NURSE,DOCTOR |
| nursing:execution:perform | 护理执行菜单 | NURSE |
| nursing:adverse-event:report | 不良事件菜单 | NURSE |
| nursing:pda:use | PDA 入口 | NURSE |
| inpatient:admission:manage | 入院管理菜单 | REGISTRAR,DOCTOR |
| inpatient:bed:view | 床位图菜单 | REGISTRAR,DOCTOR,NURSE |
| inpatient:station:view | 护士站菜单 | REGISTRAR,NURSE |
| inpatient:transfer:check | 转科核对菜单 | DOCTOR,NURSE |
| inpatient:discharge:manage | 出院管理菜单 | DOCTOR |
| iot:product:manage | 产品管理菜单 | IOT_ADMIN |
| iot:device:manage | 设备管理菜单 | IOT_ADMIN |
| iot:binding:manage | 绑定管理菜单 | IOT_ADMIN |
| iot:alarm-rule:manage | 告警规则菜单 | IOT_ADMIN |
| iot:command:issue | 命令下发菜单 | IOT_ADMIN |
| iot:linkage:manage | 联动规则菜单 | IOT_ADMIN |
| iot:quality:view | 质量看板菜单 | IOT_ADMIN |
| ward:infusion:view | 输注板菜单 | NURSE |
| ward:call:handle | 呼叫处理菜单 | NURSE |
| ward:coldchain:manage | 冷链管理菜单 | IOT_ADMIN |

## 附件 C：403 矩阵豁免清单（12 端点，RbacMatrixIT 显式断言）

| 端点 | 豁免原因 | 防线归属 |
| --- | --- | --- |
| POST /api/v1/system/auth/login | 免认证白名单（AUTH_WHITELIST） | 无需登录 |
| POST /api/v1/system/auth/refresh | 免认证白名单 | refresh 令牌强校验 |
| POST /api/v1/system/auth/bigscreen-token | 免认证白名单 | 哨兵签发端点（5min 短令牌） |
| POST /api/v1/system/auth/logout | 登录态自我会话管理 | AuthTokenInterceptor 401 |
| GET /api/v1/outpatient/portal/schedules | portal 患者匿名通道 | 服务端介质解析定 patientId |
| POST /api/v1/outpatient/portal/appointments | portal 患者匿名通道 | 同上 |
| POST /api/v1/outpatient/portal/appointments/{no}/cancel | portal 患者匿名通道 | 同上 |
| GET /api/v1/outpatient/queues/{queueId}/tickets | 候诊榜匿名只读快照 | 出参脱敏 |
| GET /api/v1/nursing/board/{wardId} | 哨兵可达端点（roles 空集必被 403） | W-39 哨兵限行+W-40 当班绑定守卫 |
| GET /api/v1/ward/infusion-board/{wardId} | 哨兵可达端点 | 同上 |
| GET /api/v1/iot/alarms | 哨兵可达端点 | W-39 哨兵限行（wardId query 一致性） |
| POST /ingest/iotda-fallback | 非 /api/v1（IoTDA HTTP 兜底通道） | 不在拦截路径；IoTDA 凭证 |

---

## Self-Review 记录（主控撰写后自查）

1. **Spec 覆盖**：D-28 全量 403（附件 A 302 点）✓；双命名空间（D2）✓；登录链路填实（Task 5）✓；前端收紧（Task 6）✓；W-90 两面（Task 7+矩阵 A.8/A.9）✓；W-91/W-93 索引（Task 9）✓；403 矩阵 IT（Task 8）✓；F 册移交（ELEMENT 命名空间+运行期刷新+管理台——D2/D3 注记声明）✓。
2. **占位符扫描**：无 TBD/TODO 型步骤；Task 3 VALUES 列表以附件 A 为准逐行展开（计划不预写 335 行 SQL 全文，实现者按矩阵生成——矩阵即权威源，非占位符）。
3. **类型一致性**：`PermissionRegistry.resolve(String,String)` → `Optional<PermissionEntry>`；`findPermissionCodesByUserId(Long)` → `List<String>`；SessionUser/SessionData 末位追加 permissions——Task 4/5/8 间签名一致。
