# PR-3（M01 系统与权限基础）任务简报

| 属性 | 内容 |
| --- | --- |
| 编号 | BRIEF-PR3-01 |
| 日期 | 2026-09-09 |
| 性质 | 实现专员执行输入：PR-3 全部批次的逐文件实现规格、TDD 验收指令与红线清单 |
| 唯一 spec | `docs/plans/2026-09-08-P0实施计划.md` §1-PR-3（范围与验收）+ `docs/specs/modules/01-system.md`（业务口径） |
| 上游状态 | PR-2 已合入 dev（a019f47）：M20 治理构件全套就位（信封/codec/Long→String/队列声明/event_registry V1–V2+V5/received_event V3/dead_letter V4+死信监听器/端到端 IT 模式） |
| 执行依据 | `docs/prompt/2026-09-09-loop-P0工程骨架.md` §3-P3（批次 B3.1→B3.4、出口门禁） |

**上游构件速查（实现专员必读的既有代码，禁止重复造轮子）**：

| 构件 | 位置 | 复用方式 |
| --- | --- | --- |
| 事件信封/编解码 | `fuyun-common` `com.fuyun.common.messaging.EventEnvelope` / `EventEnvelopeCodec` | 注入 codec：`create(clock, producer, eventType, traceId, payload)` 发布、`fromJson` 消费解析（含合规校验） |
| Long→String | `com.fuyun.common.config.JacksonLongToStringConfig` | 已在 fuyun-app MessagingConfig @Import 生效，禁止零散处理 |
| 异常基座/全局渲染 | `com.fuyun.common.exception.BizException` / `ErrorCode` / `com.fuyun.common.web.GlobalExceptionHandler` | 业务异常抛 `BizException(SystemErrorCode.XXX, HttpStatus, message)`，ProblemDetail 自动渲染 |
| traceId 过滤器 | `com.fuyun.common.context.TraceIdFilter`（app TraceIdConfig 注册，HIGHEST_PRECEDENCE） | MDC 键 `traceId`；响应头 X-Trace-Id 已回写 |
| 操作人上下文 | `com.fuyun.common.context.OperatorContextHolder` | 认证拦截器 set/clear，审计切面与操作人注入读取 |
| 幂等构件 | `com.fuyun.common.messaging.MessageIdempotencyService`（接口在 common，实现在 integration） | 消费者按接口 javadoc 标准范式调用；本 PR 按 D-7 改造实现 |
| 队列声明构件 | `com.fuyun.integration.api.MessagingGovernance` + `ConsumerQueueSpec` | `@Bean Declarables xxx(governance){return governance.declareConsumerQueue(new ConsumerQueueSpec("system", 事件类型));}`，订阅自动登记 |
| 装配惯例 | `fuyun-app/config/MessagingConfig`、`TraceIdConfig`、`MybatisPlusConfig` | common/integration/system 包不在扫描范围，一律 app 配置类 @Import；mapper 加 @Mapper 即被既有 @MapperScan 扫到 |
| IT 模式 | `fuyun-app/src/test/.../MessagingGovernanceIT`、`SmokeStackIT` | 三容器（timescale/redis/rabbit，tag 与 compose 一致）+ @ServiceConnection + static @Container |

---

## 0. 范围与切片口径（P0 切片边界，禁止越界）

PR-3 交付：登录认证（D-2 轻量 HMAC 令牌 + Redis 会话）、RBAC 五表迁移、字典管理（含 `system.dict.published` 广播）、审计切面（落库 + traceId + 脱敏）、`POST /api/v1/system/practice/check` 骨架端点、workstation 登录页 + 主布局 + Axios 单例。

**明确不在 P0（做了即越界）**：完整用户/角色管理 CRUD UI 与端点；API 权限强制（403 鉴权拦截，P0 只做认证 401）；数据范围注入拦截器；role_org 数据范围集合表；执业授权库表（practice_grant）与真实校验逻辑；审计异步批量写与检索界面；通知/打印/参数/电子签名/工作台；字典定时生效（fy.delay）与映射表；登录 IP 限流；密码策略（90 天更换/历史 5 次）；openapi-typescript 契约生成链路（P0 前端手写后备类型）。

---

## 1. D-2 令牌方案设计规格（B3.1 核心）

### 1.1 令牌格式与内容

- 形态：`Base64Url(payloadJson) + "." + Base64Url(HMAC-SHA256(payloadJsonBytes, secret))`（两段式，非 JWT 结构——不引 jjwt/spring-security 全家桶）。
- payloadJson 紧凑 JSON，字段（短键自定义，非 JWT claims 集）：

```json
{"uid":"123...","eid":"456...","oid":null,"sid":"<UUID>","typ":"access","exp":1730000000000}
```

| 字段 | 含义 | 来源 |
| --- | --- | --- |
| uid | userId（雪花 ID 十进制字符串） | 登录成功后的 sys_user.id |
| eid | employeeId，可为 null（系统/接口账号无员工） | sys_employee.user_id 反查 |
| oid | 主归属机构 id，可为 null（P0 种子不建 org 行） | sys_employee.primary_org_id |
| sid | 会话标识 UUID（= Redis 会话键尾段） | 签发时生成 |
| typ | `access` / `refresh` | 签发时区分；校验端严格匹配 |
| exp | 过期时刻 epoch 毫秒 | 签发时刻 + TTL |

- 签名：JDK `Mac.getInstance("HmacSHA256")`；密钥经环境变量 `FUYUN_SECURITY_TOKEN_HMAC_SECRET`（≥32 字符，`SecurityProperties` 启动期 `@Size(min=32)` 校验，缺失 fail-fast）。
- 校验顺序：格式两段 → 重算签名 `MessageDigest.isEqual` 常量时间比较 → exp 未过 → typ 匹配 → Redis 会话键存在。任一失败即 401。

### 1.2 Redis 会话设计（A.5-1 键规范）

- 键：`fy:system:session:{sid}`（冒号分层；常量 `SecurityConstants.SESSION_KEY_PREFIX = "fy:system:session:"`）。
- 值：JSON 序列化会话对象（StringRedisTemplate，Key/Value 均 String 序列化，禁 JDK 序列化）：`{userId, loginName, displayName, employeeId, orgId, roles:[roleCode...]}`（角色摘要存会话不进令牌体，M01 Spec §5"令牌含角色摘要"语义由 sid→会话承载，D-2 裁决口径）。
- TTL：`fuyun.security.access-token-ttl`（默认 2h）。滑动续期：认证拦截器每次校验成功 `expire` 重置为 access TTL（廉价写，不做阈值判断）；refresh 成功同样续满。会话键必有 TTL（A.5-1 禁无过期键红线）。
- 登出 = 删除会话键（access 与 refresh 同 sid 同时失效）；改密/停用踢出（M01 FU-M01-02）= 同机制删键，P1 用户管理接入时复用。
- access TTL 内 refresh 端点用 `typ=refresh` 令牌换新 access（同 sid 不换发 refresh 值，P0 不做 refresh 轮换）。

### 1.3 三操作 API 与 401 契约（ProblemDetail 同构）

| 端点 | 认证 | 请求 | 成功响应 | 失败 |
| --- | --- | --- | --- | --- |
| `POST /api/v1/system/auth/login` | 白名单 | `LoginRequest{loginName @NotBlank, password @NotBlank}` | 200 `LoginResponse{accessToken, refreshToken, tokenType:"Bearer", expiresIn(秒), user:UserVO}` | 401 SYS-1001（用户不存在与密码错误同文案，防枚举）；401 SYS-1002（锁定，文案含解锁时间）；403 SYS-1006（已停用） |
| `POST /api/v1/system/auth/refresh` | 白名单 | `RefreshRequest{refreshToken @NotBlank}` | 200 同 LoginResponse | 401 SYS-1005（typ 错/签名错/会话不存在） |
| `POST /api/v1/system/auth/logout` | 需令牌 | 无 body | 204 | 拦截器 401 同下 |

- `UserVO{userId, loginName, displayName, orgId, roles}`：userId/orgId 为 Long，经全局 Long→String 以 JSON 字符串输出（前端 string 承载）。
- **受保护请求 401 契约**：缺 Authorization 头 / 非 Bearer / 签名无效 / 过期 / 会话不存在 / typ 不符 → 拦截器直接写出 401 `application/problem+json`（不经 GlobalExceptionHandler——拦截器无异常出口），body 结构与全局渲染同构：`{type, title:"Unauthorized", status:401, detail, errorCode, traceId}`；errorCode 取值 SYS-1003（缺失或无效）/ SYS-1004（已过期），traceId 取当前 MDC。
- 登录成功副作用（同事务内）：失败计数清零、last_login_at 更新；失败路径：fail_count+1，达 5 次置 `locked_until = now + 30min`（M01 Spec §5 用户状态机；锁定自动到期即恢复，P0 不做手动解锁端点）。

### 1.4 与 common 基座的集成点

- TraceIdFilter（HIGHEST_PRECEDENCE Filter）先于一切 HandlerInterceptor：认证拦截器内 MDC `traceId` 必可用，401 body 与日志均可携带。
- `OperatorContextHolder`：拦截器 `preHandle` 校验通过后 `set(String.valueOf(userId))`；`afterCompletion` finally `clear()`（该类 javadoc 明示请求结束必须清理，防线程复用串号）。审计切面与后续 created_by 注入统一读此上下文。
- MQ 发布 traceId：发布器从 `MDC.get("traceId")` 取当前值传入信封（MQ 回调线程无上下文，仅在 HTTP 线程发布时非空）。

### 1.5 认证拦截器落位：fuyun-system（裁决与依据）

**结论：拦截器与令牌/会话构件全部落 fuyun-system，不下沉 fuyun-common。** 依据：

1. backend 宪法 B.1：common 边界 = 公共工具/基础异常/全局渲染/审计支撑，不依赖任何业务模块；令牌格式、会话结构、登录状态机是 M01 Spec §1 明文职责（"认证"），属业务域契约而非纯横切。若下沉 common，common 必须感知 M01 令牌语义 → 业务契约泄漏进公共层，违背 B.1。
2. M01 Spec 文档头：M01"上游依赖无（全系统根模块），下游被依赖全部模块（认证、权限……）"——认证本就是 M01 对外供给的领域能力，其他模块经 OperatorContextHolder/common 已有抽象消费"当前用户"，无需各自感知令牌。
3. 装配形态（沿用既有惯例）：`fuyun-system/config/SystemWebConfig implements WebMvcConfigurer` 注册 `internal/AuthTokenInterceptor`（`addPathPatterns("/api/v1/**")` + `excludePathPatterns("/api/v1/system/auth/login", "/api/v1/system/auth/refresh")` 白名单）；fuyun-app 新增 `config/SystemConfig`，`@Import({SystemWebConfig.class, SystemMessagingConfig.class})`（与 MessagingConfig/TraceIdConfig 同模式，不放宽组件扫描）。actuator/springdoc 路径不在 `/api/v1/**` 下，天然不受拦截，无需额外白名单。

---

## 2. RBAC 五表迁移规格（B3.1）

### 2.1 号段裁决：system = V300–V399

- M01 **不是**公共治理域：PR-2 已裁"公共域低位 V1–V99"归 integration（event_registry/received_event/dead_letter 是全系统治理台账，仅物理落 integration schema，取低位保证先于全部业务迁移执行）。M01 表仅落 `system` schema，无"先于全部业务迁移"的硬需求。
- 采纳 TASK.md W-4 登记建议值"系统建议 V300–V399"。Flyway 多目录按版本全局排序（locations 枚举顺序不影响执行序），`system` 的 V300 天然晚于 integration 的 V1（公共触发器函数），依赖安全。
- PR-3 须在 CHANGELOG 登记号段占用（V300–V303），TASK.md W-4 号段登记载体要求。
- 迁移目录：`backend/fuyun-system/src/main/resources/db/migration/system/`（目录已存在）。**禁止 `CREATE INDEX CONCURRENTLY`**（TASK.md T-R3-1 Flyway 事务外执行兼容性为待调研项，迁移设计一律普通 `CREATE INDEX`）；禁止修改已应用迁移；索引显式命名 `uk_`/`idx_` 前缀。

### 2.2 迁移文件清单

| 文件 | 内容 |
| --- | --- |
| `V300__create_system_rbac_tables.sql` | sys_org、sys_user、sys_employee、sys_role、sys_permission 五核心表 + sys_user_role、sys_role_permission 两关联表（RBAC0 关联是模型可运转的最小胶水，属"五表迁移"交付物内含项） |
| `V301__create_system_dict_tables.sql` | dict_type、dict_version、dict_item |
| `V302__create_system_audit_log.sql` | audit_log（只增表） |
| `V303__seed_system_rbac.sql` | ADMIN 角色 + P0 权限点行 + role_permission/user_role 绑定 + admin 账号与员工行（幂等 `INSERT ... WHERE NOT EXISTS`，同 V5 先例） |

### 2.3 DDL 公共约定

- 全表 `id BIGINT PRIMARY KEY`（雪花，MP ASSIGN_ID）；审计列 `created_at/updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`、`created_by/updated_by VARCHAR(64) NOT NULL DEFAULT 'system'`、`deleted SMALLINT NOT NULL DEFAULT 0`（audit_log 除外）。
- 有 UPDATE 生命周期的表挂 `updated_at` 触发器，**复用 `public.fuyun_set_updated_at()`**（integration V1 公共函数，禁止重复定义）；audit_log 只增：无 updated_at 列、不挂触发器、无 deleted 列。
- 不建外键约束（关联完整性应用层保证，V2/V3 先例）；唯一约束用部分唯一索引 `WHERE deleted = 0`（V2 先例）。
- 所有状态列 `VARCHAR(16)`，值域 = §2.5 枚举 code。
- schema 前缀 `system.`；实体 `@TableName("system.sys_user")`（integration 实体先例）。

### 2.4 逐表字段（自 M01 Spec §4 提取，P0 切片标注）

**sys_org**：org_code VARCHAR(64)（uk）、org_name VARCHAR(128)、org_type VARCHAR(16)、org_attr VARCHAR(16)、parent_id BIGINT NULL、sort INT NOT NULL DEFAULT 0、status VARCHAR(16)；idx parent_id。（M01 Spec 树形闭包表双写为完整形态，P0 仅邻接表 parent_id——组织树查询非 P0 验收项，闭包表 P1 随组织管理补。）

**sys_user**：login_name VARCHAR(64)（uk）、password_hash VARCHAR(100)（bcrypt，`$2a$` 60 字符留余量）、user_type VARCHAR(16)、status VARCHAR(16)、fail_count INT NOT NULL DEFAULT 0、locked_until TIMESTAMPTZ NULL、password_updated_at TIMESTAMPTZ NULL（P1 密码策略启用，先落列避 ALTER，注释标注）、last_login_at TIMESTAMPTZ NULL。

**sys_employee**：user_id BIGINT NOT NULL（uk）、emp_no VARCHAR(64)（uk）、emp_name VARCHAR(64)、title VARCHAR(64) NULL、primary_org_id BIGINT NULL、status VARCHAR(16)。

**sys_role**：role_code VARCHAR(64)（uk）、role_name VARCHAR(128)、data_scope_type VARCHAR(16)、status VARCHAR(16)、remark VARCHAR(255) NULL。

**sys_permission**：perm_code VARCHAR(128)（uk，权限点编码 = API 路径，M01 FU-M01-03）、perm_name VARCHAR(128)、perm_type VARCHAR(16)。

**sys_user_role**：user_id + role_id（uk 联合）。**sys_role_permission**：role_id + permission_id（uk 联合）。

**dict_type**：type_code VARCHAR(64)（uk）、type_name VARCHAR(128)、national_standard BOOLEAN NOT NULL DEFAULT false（国标字典 code 不可改标记，FU-M01-06）、remark VARCHAR(255) NULL。

**dict_version**：dict_type_id BIGINT NOT NULL、version INT NOT NULL、status VARCHAR(16)、effective_at TIMESTAMPTZ NULL、published_at TIMESTAMPTZ NULL；uk(dict_type_id, version)。

**dict_item**：dict_version_id BIGINT NOT NULL、item_code VARCHAR(64)、item_name VARCHAR(128)、parent_code VARCHAR(64) NULL、ext_attrs JSONB NOT NULL DEFAULT '{}'（P0 API 不暴露该字段，插入走列默认值——避免为此引入 JSONB TypeHandler，P1 需要扩展属性读写时随 handler/ 交付）、sort INT NOT NULL DEFAULT 0；uk(dict_version_id, item_code)。

**audit_log**（只增）：operator_id VARCHAR(64)（= OperatorContextHolder 值）、action_type VARCHAR(16)、resource VARCHAR(256)（请求 URI）、biz_no VARCHAR(128) NULL、client_ip VARCHAR(64)、trace_id VARCHAR(64)、result VARCHAR(16)、fail_reason VARCHAR(500) NULL（脱敏截断后）、detail VARCHAR(1000) NULL（脱敏摘要）、occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()、created_at TIMESTAMPTZ NOT NULL DEFAULT now()；索引：(operator_id, occurred_at)、(resource, occurred_at)、(biz_no)。留存 ≥6 个月为等保红线（M01 目标 ≥3 年），归档清理策略 P1+（P0 只增不清理）。

### 2.5 枚举类清单（D-6 已裁：目录 `enums/`）

D-6 默认裁决已生效：枚举包目录 = `enums/`。PR-3 开工第一个动作 = 修宪落地（见 §3.0）。枚举规范（宪法 A.2-7）：`code` 字段 + `@EnumValue`（MP DB 映射）+ `@JsonValue`（JSON 输出 code）+ `static fromCode(String)` 双向映射方法；禁止常量类/整型模拟枚举。

`com.fuyun.system.enums` 下新建（均为 P0 实际使用，非预留）：

| 枚举 | 值域（code=存储值） | 业务含义 |
| --- | --- | --- |
| UserType | STAFF / SYSTEM / API | 员工/系统/接口账号（M01 §4） |
| UserStatus | ACTIVE / LOCKED / DISABLED | 用户状态机（M01 §5） |
| EmployeeStatus | ACTIVE / DISABLED | 在职/停用 |
| OrgType | CAMPUS / DEPT / WARD / TEAM | 院区/科室/病区/班组（Spec 四值，不私增"医院"值；根节点以 parent_id IS NULL 表达） |
| OrgAttr | CLINICAL / MEDTECH / ADMIN | 临床/医技/职能 |
| OrgStatus | ACTIVE / DISABLED | 启用/停用 |
| RoleStatus | ACTIVE / DISABLED | 角色启停 |
| DataScopeType | ALL / HOSP / DEPT / WARD / SELF | 数据范围（M01 §4 role 字段） |
| PermissionType | MENU / API | 菜单/接口权限点（对应 Spec role_menu/role_api 收敛单表） |
| DictVersionStatus | DRAFT / PUBLISHED / DEPRECATED | 字典版本状态机（M01 §5） |
| AuditActionType | LOGIN / WRITE / PRINT / SENSITIVE_QUERY | 审计动作类型（M01 §4） |
| AuditResult | SUCCESS / FAIL | 审计结果 |

另：`com.fuyun.system.api.SystemErrorCode`（错误码枚举，implements common `ErrorCode`，落 api 包是宪法 B.1 表明文）。建议值域（实现可微调，约束 = `<模块助记>-<4位>` 全项目唯一，当前 SYS- 前缀无占用）：

| 错误码 | HTTP | 场景 |
| --- | --- | --- |
| SYS-1001 | 401 | 登录名或密码错误（防枚举同文案） |
| SYS-1002 | 401 | 账号已锁定（文案含解锁时间） |
| SYS-1003 | 401 | 令牌缺失或无效 |
| SYS-1004 | 401 | 令牌已过期 |
| SYS-1005 | 401 | 刷新令牌无效 |
| SYS-1006 | 403 | 账号已停用 |
| SYS-1011 | 404 | 字典类型不存在 |
| SYS-1012 | 404 | 字典版本不存在 |
| SYS-1013 | 409 | 字典版本状态不允许发布（仅 DRAFT 可发布） |
| SYS-1014 | 409 | 字典类型编码已存在 |

### 2.6 V303 种子内容（幂等）

1. 角色 ADMIN（role_code=ADMIN，data_scope_type=ALL，role_name=系统管理员）。
2. 权限点行：P0 全部受保护端点按 `perm_code = API 路径` 逐行登记（`/api/v1/system/dicts/{type}` 读、dict-types/dict-versions 写、publish、practice/check 等，以最终端点清单为准；login/refresh/logout 为免认证端点不登记）。
3. role_permission：ADMIN → 全部权限点。
4. admin 用户（login_name=admin，user_type=STAFF，status=ACTIVE）+ 对应员工行（emp_no=ADMIN）+ user_role 绑定。
5. admin 初始口令：bcrypt 哈希字面量，明文 `Fuyun@2026`（SQL 注释标注"P0 联调初始口令"）。哈希值由实现专员经 BCryptPasswordEncoder 一次性生成后写入 SQL（禁止明文口令入库，只入哈希）。红线注记：该初始口令仅具 dev/test 联调意义，P1 密码策略交付时必须强制改密并评估禁用此种子；P0 无生产部署（计划 §4），风险可控。

---

## 3. B3.1 / B3.2 / B3.3 逐文件规格（后端）

### 3.0 B3.1 前置：D-6 修宪落地（先记再改）

1. `CHANGELOG.md` 顶部追加条目：登记 D-6 默认裁决生效（枚举包目录 enum/ → enums/，宪法 v1.3 → v1.4）与 system 号段占用 V300–V303。
2. `backend/AGENTS.md`：B.1 模块内包职责表 `constants/ enum/` 行与 C.3 目录树 `config/ properties/ constants/ enum/ exception/` 行改为 `enums/`。
3. 全部 20 个模块 `src/main/java/com/fuyun/{domain}/enum/.gitkeep` 目录更名 `enums/.gitkeep`（含 fuyun-system；integration 已实装无 enum 目录则跳过更名）。

### 3.1 B3.1：RBAC 迁移 + 令牌/会话构件

**fuyun-system/pom.xml 新增依赖（版本全 BOM 托管零声明，PR 描述申报理由）**：

| 依赖 | 用途 |
| --- | --- |
| `com.baomidou:mybatis-plus-spring-boot3-starter` + `mybatis-plus-jsqlparser` | 实体注解与 mapper（同 integration pom 先例） |
| `org.springframework.boot:spring-boot-starter-data-redis` | 会话 StringRedisTemplate |
| `org.springframework.boot:spring-boot-starter-amqp` | B3.2 发布器 RabbitTemplate |
| `org.springframework.boot:spring-boot-starter-validation` | DTO/Properties JSR-303 |
| `org.springframework.security:spring-boot-starter-security`? **否**——只加 `org.springframework.security:spring-boot-crypto`? 正确坐标为 `org.springframework.security:spring-security-crypto` | BCryptPasswordEncoder（宪法 A.4.2-10 点名 BOM 托管构件，非 spring-security 全家桶，仅 crypto 单 jar） |
| `org.projectlombok:lombok`（provided） | 编译期 |
| `org.mapstruct:mapstruct` | 转换器 |

**逐文件（包路径 `com.fuyun.system`）**：

| 文件 | 规格 |
| --- | --- |
| `enums/`（12 个枚举类） | §2.5 清单；每枚举含 code 字段、fromCode 双向映射、@EnumValue/@JsonValue |
| `api/SystemErrorCode.java` | §2.5 错误码枚举 |
| `constants/SecurityConstants.java` | SESSION_KEY_PREFIX、CLAIM_* 键名、TOKEN_TYPE_ACCESS/REFRESH、BEARER_PREFIX="Bearer "、AUTH_HEADER="Authorization"、登录失败锁定阈值 5 与锁定时长常量、MDC/traceId 相关键（禁魔法值散落） |
| `properties/SecurityProperties.java` | record + `@Validated @ConfigurationProperties(prefix="fuyun.security")`：`tokenHmacSecret`（`@NotBlank @Size(min=32)`）、`accessTokenTtl`（`@DefaultValue("2h") Duration`，正值校验）、`refreshTokenTtl`（`@DefaultValue("24h")`）。yml：application.yml 增 `fuyun.security.*` 占位（secret = `${FUYUN_SECURITY_TOKEN_HMAC_SECRET:}`，禁止任何明文默认值——dev/test 本地裸跑未设 env 时启动 fail-fast 报缺失，属预期）；dev/test/prod yml 不放默认密钥 |
| `entity/UserEntity.java`、`OrgEntity`、`EmployeeEntity`、`RoleEntity`、`PermissionEntity`、`UserRoleEntity`、`RolePermissionEntity` | @TableName("system.sys_xxx")、@TableId(ASSIGN_ID)、@TableLogic deleted、状态字段用 §2.5 枚举类型（MP @EnumValue 自动映射）、时间 OffsetDateTime；@Getter/@Setter（禁 @Data） |
| `mapper/UserMapper.java` 等 7 个 | 接口 + `@Mapper`（app MybatisPlusConfig 的 @MapperScan 已覆盖 com.fuyun，勿再扫） |
| `service/ITokenService.java` | `TokenPair issue(SessionUser user)`；`SessionData verify(String rawToken, String expectedType)`（校验链 §1.1，成功滑动续期）；`void evict(String sid)` |
| `service/impl/TokenServiceImpl.java` | HMAC 签发/校验（JDK Mac + Base64 URL without padding + MessageDigest.isEqual）、StringRedisTemplate 会话读写（ObjectMapper 注入做 JSON 转换）。无状态单例。**本类属认证核心路径：单测必须全覆盖** |
| `service/IAuthService.java` | `LoginResponse login(LoginRequest)`、`LoginResponse refresh(RefreshRequest)`、`void logout(String token)` |
| `service/impl/AuthServiceImpl.java` | login：IUserService.findByLoginName → 锁定校验（locked_until > now → SYS-1002）→ 停用校验（SYS-1006）→ BCrypt matches（失败走 IUserService.recordLoginFailure：fail_count+1、达 5 置 locked_until，SYS-1001）→ 成功 recordLoginSuccess（清零+last_login_at）→ 组 SessionUser（角色经 UserRole/Role 查询）→ tokenService.issue。@Transactional 边界在本层方法（写路径），查询只读事务 |
| `service/IUserService.java` + `impl/UserServiceImpl.java` | extends IService<UserEntity> + findByLoginName/recordLoginFailure/recordLoginSuccess |
| `service/IRoleService.java` + `impl/RoleServiceImpl.java` | extends IService<RoleEntity> + findRoleCodesByUserId（连表→mapper XML 或两步单表查询；P0 两步单表 lambdaQuery 即可，无需 XML） |
| `convert/AuthConverter.java` | MapStruct：SessionUser/UserEntity+roles → UserVO/LoginResponse 组装 |
| `dto/LoginRequest.java`、`dto/RefreshRequest.java` | record + JSR-303 |
| `vo/UserVO.java`、`vo/LoginResponse.java` | record；Long 字段出参（Jackson 全局转字符串） |
| `internal/AuthTokenInterceptor.java` | HandlerInterceptor：preHandle 解析 Bearer → tokenService.verify(raw, ACCESS) → OperatorContextHolder.set → true；失败写 §1.3 401 ProblemDetail（ObjectMapper 序列化，contentType application/problem+json）返回 false；afterCompletion finally OperatorContextHolder.clear() |
| `config/SystemWebConfig.java` | WebMvcConfigurer 注册拦截器（含白名单 excludePathPatterns）+ @EnableConfigurationProperties(SecurityProperties.class) |
| `controller/AuthController.java` | 三端点；参数 @Valid；禁业务逻辑禁 @Transactional |
| `exception/` | 无需模块异常类（直接抛 BizException(SystemErrorCode.XXX, HttpStatus, msg)） |

**单测（surefire，命名表达业务意图 + 中文 @DisplayName）**：TokenServiceImplTest（签发→校验往返、篡改签名拒绝、过期拒绝、typ 错拒绝、会话被删拒绝、续期生效）；AuthServiceImplTest（成功登录、密码错计数、第 5 次锁定、锁定期间拒绝、停用拒绝、refresh 换发、logout 后 access 失效）；枚举 fromCode 双向映射测试；SecurityProperties 校验测试（短密钥启动失败）。注意：`com.fuyun.system.service.impl` 为 JaCoCo PACKAGE LINE ≥ 1.00 核心包（父 POM 已生效，包内出现第一个类即门禁立即生效）——impl 包内每个类每行都要有测试触达。

### 3.2 B3.2：登录端点 + 401 拦截 + 字典管理与广播

（登录端点与拦截器代码在 B3.1 已落，本批次端到端联通与联调收口；主要新增为字典域与 D-7 改造。）

**D-7 改造（跨模块小改，PR 描述申报）**——`fuyun-integration/service/impl/MessageIdempotencyServiceImpl.tryAcquire`：

- 现状：Redis NX 抢占失败（返回 false）即判重复跳过。D-7 裁决：NX 失败时**回查 received_event 表**（(event_id, consumer_module) 唯一索引查询）——已存在 PROCESSED 行 → 返回 false（确认已处理，跳过）；不存在行 → warn（前置键残留/上次处理中断）并返回 true（放行重新处理）。彻底消除"TTL 窗口内上次处理未完成、重投被 NX 误判丢弃"窗口，保持 at-least-once。
- 同步更新：`fuyun-common` `MessageIdempotencyService` 接口 javadoc（tryAcquire 契约与标准消费范式注释）、实现类 javadoc、`MessageIdempotencyServiceImplTest` 补分支（NX 失败+台账已处理→false；NX 失败+台账无行→true；Redis 故障降级路径回归）。`com.fuyun.integration.service.impl` 保持 LINE 1.00。MessagingGovernanceIT 的测试消费者无需改动（范式不变）。

**字典域逐文件（fuyun-system）**：

| 文件 | 规格 |
| --- | --- |
| `entity/DictTypeEntity/DictVersionEntity/DictItemEntity` + `mapper/` 三个 | 同 §3.1 实体规范 |
| `service/IDictTypeService.java` + impl | extends IService；createType（typeCode 唯一校验→SYS-1014） |
| `service/IDictVersionService.java` + impl | extends IService；createVersion（默认 DRAFT）；`publish(Long versionId)` **@Transactional**：DRAFT 校验（非 DRAFT→SYS-1013）→ 置 PUBLISHED + published_at/effective_at=now → 同 type 旧 PUBLISHED 行置 DEPRECATED（"同一 type 同一时刻仅一个 PUBLISHED"，M01 §5）→ 事务内 `ApplicationEventPublisher.publishEvent(new DictVersionPublishedEvent(typeCode, version))`（Spring 应用事件，运行于事务上下文；record 落 `internal/`——模块内事件非对外契约） |
| `service/IDictItemService.java` + impl | extends IService；addItem（版本必须 DRAFT，PUBLISHED 版本禁改） |
| `service/IDictQueryService.java` + impl | `DictVersionVO readPublished(String typeCode, Integer version)`：version 空取当前 PUBLISHED，否则取指定版本；条目全量返回（契约型读接口，M01 §7 `GET /dicts/{type}?version=` 无分页语义，豁免分页约束——豁免理由写入 javadoc） |
| `internal/SystemEventPublisher.java` | `@TransactionalEventListener(phase = AFTER_COMMIT)` 监听 DictVersionPublishedEvent → `codec.create(Clock.systemUTC(), "system", "system.dict.published", MDC.get("traceId"), new DictPublishedPayload(typeCode, version))` → `rabbitTemplate.convertAndSend("fy.topic", eventType, envelope, new CorrelationData(eventId))`。实现 `RabbitTemplate.ConfirmCallback` + `ReturnsCallback` 并在构造器 `rabbitTemplate.setConfirmCallback/setReturnsCallback` 注册：nack / 不可路由 → error 日志（含 eventId/eventType）告警，P0 不自动重发（outbox 补偿属 P1 治理完整化，B.3-3；MessagingGovernanceConfig javadoc 已预告 PR-3 发布侧范围=确认回调）。A.4.2-7：事务提交后才发送，AFTER_COMMIT 保证 |
| `internal/DictPublishedListener.java` | `@RabbitListener(queues = SystemMessagingConstants.QUEUE_DICT_PUBLISHED)`；raw Message 承接（容器工厂 SimpleMessageConverter 兜底，先例 DeadLetterListener/IT）→ UTF-8 解码 → `codec.fromJson`（不合规抛 IllegalArgumentException → 有界重试耗尽进 fy.dlx 留痕）→ **标准幂等范式**：`tryAcquire(eventId, "system")` false 即 return（AUTO 确认跳过）→ try{ 业务=info 日志（dictType/version，**P0 缓存刷新占位**，P1 接入字典本地缓存失效）+ `recordProcessed(...)` } catch { `release` + rethrow }。落 internal/ 与 DeadLetterListener 同例 |
| `constants/SystemMessagingConstants.java` | EVENT_DICT_PUBLISHED="system.dict.published"、CONSUMER_MODULE="system"、QUEUE_DICT_PUBLISHED="q.system.system.dict.published" |
| `config/SystemMessagingConfig.java` | `@Bean Declarables dictPublishedConsumerQueue(MessagingGovernance governance)`（走构件声明，事件已在 V5 登记，订阅自动登记 system）；`@Import({DictPublishedListener.class, SystemEventPublisher.class})` |
| `controller/DictTypeController.java` | `POST /api/v1/system/dict-types`；`POST /api/v1/system/dict-types/{typeCode}/versions` |
| `controller/DictVersionController.java` | `POST /api/v1/system/dict-versions/{versionId}/items`；`POST /api/v1/system/dict-versions/{versionId}/publish`（无 body） |
| `controller/DictController.java` | `GET /api/v1/system/dicts/{type}?version=`（业务读，响应带缓存头 `Cache-Control: no-cache` 供协商，P0 不建服务端缓存） |
| `dto/` | DictTypeCreateRequest{typeCode @NotBlank @Pattern(小写点分), typeName @NotBlank, nationalStandard, remark}；DictVersionCreateRequest（空 body 或仅备注）；DictItemCreateRequest{itemCode/itemName @NotBlank, parentCode, sort} |
| `vo/` | DictTypeVO、DictItemVO、DictVersionVO{typeCode, version, status, publishedAt, items:List<DictItemVO>} |
| `convert/DictConverter.java` | MapStruct |
| fuyun-app | `config/SystemConfig.java`：`@Import({SystemWebConfig.class, SystemMessagingConfig.class})`（新增，与 MessagingConfig 同模式） |
| deploy | `deploy/.env.example` 增补 `FUYUN_SECURITY_TOKEN_HMAC_SECRET=`（空占位 + 独立行中文注释"必填：令牌 HMAC 签名密钥，≥32 字符，禁止提交真实值"）；核对 `deploy/docker-compose.yml` backend environment 段，若为显式映射清单则同步新增该变量透传 |

**单测**：DictVersionServiceImplTest（发布状态机：DRAFT→PUBLISHED、非 DRAFT 拒绝、旧 PUBLISHED 置 DEPRECATED、事件在事务内发布）；SystemEventPublisherTest（AFTER_COMMIT 触发、信封字段与 traceId 透传、nack/return 回调 error 日志——Mockito 验证 convertAndSend）；DictPublishedListenerTest（范式三分支：重复跳过/成功登记/失败释放重抛；不合规信封上抛）；MessageIdempotencyServiceImplTest（D-7 新分支）；字典 CRUD service 薄测试。

### 3.3 B3.3：审计切面 + practice/check 骨架 + 端到端 IT

**审计切面**：

| 文件 | 规格 |
| --- | --- |
| `api/AuditLog.java` | 注解（@Target(METHOD) @Retention(RUNTIME)）：`AuditActionType actionType()`。落 api 包=对外契约（其他模块将注解自己的 controller，M01 底座能力） |
| `internal/AuditLogAspect.java` | @Aspect @Around("@annotation(com.fuyun.system.api.AuditLog)")：proceed 成功 → 记 SUCCESS；BusinessException/任意异常 → 记 FAIL（fail_reason=异常 message 经脱敏与 500 字符截断）后**原样 rethrow**。字段组装：operator=OperatorContextHolder.get()、traceId=MDC.get("traceId")、resource/client_ip 取 RequestContextHolder 当前请求、occurred_at=now。**落库 try-catch 全吞：失败仅 error 日志告警，绝不阻断业务**（M01 模块红线）。切面落 controller 层注解点——业务事务已在 service 提交，审计写入天然在事务外（解耦），且业务回滚仍能记 FAIL |
| `service/IAuditLogService.java` + impl | `void append(AuditLogEntry)`（record 参数对象）；impl 直接 mapper.insert（单语句自原子，不开方法级事务） |
| `entity/AuditLogEntity` + `mapper/AuditLogMapper` | 同规范；只增无 @TableLogic |
| fuyun-common `utils/SensitiveMasker.java` | **脱敏工具落 common**（公共工具属 B.1 common 职责；纯字符串零依赖）：`maskPhone`（保留前 3 后 4）、`maskIdCard`（保留前 6 后 4）、`maskName`（姓保留名打星）、`truncate(String, int)`。单测覆盖 |
| 注解落点（P0） | AuthController.login（LOGIN）、logout（LOGIN）；DictTypeController/DictVersionController 全部写端点（WRITE）。practice/check 与字典读为查询，P0 不审计（敏感查询留痕 P1） |

**practice/check 骨架**：

| 文件 | 规格 |
| --- | --- |
| `controller/PracticeController.java` | `POST /api/v1/system/practice/check`（受保护端点，M01 §7 路径原样） |
| `dto/PracticeCheckRequest.java` | record：`employeeId @NotNull Long`、`grantType @NotBlank String`、`checkTime OffsetDateTime`（可空，默认 now——该时点是查询语义入参非业务落库时间，不违"禁止前端传业务时间"口径） |
| `vo/PracticeCheckResponse.java` | record：`employeeId String、grantType、checkTime、passed boolean、reason String` |
| `service/IPracticeService.java` + impl | P0 骨架实现：入参回显 + `passed=false`、`reason="执业授权库表随 P1 交付后启用真实校验"`。javadoc 声明 P1 替换内部实现（practice_grant 表 + EFFECTIVE 校验 + 30 天到期通知），响应契约不变 |

**端到端 IT（fuyun-app/src/test，落 §5 规格）**：`AuthFlowIT`、`DictBroadcastIT`。

---

## 4. B3.4 前端规格（workstation）

依赖与脚手架现状：Element Plus 按需（unplugin 两件套）已配、路由懒加载已配、`src/api|stores|types` 均为 .gitkeep 占位、冒烟单测 `App.spec.ts` 存在、**axios 未安装**（catalog 已有 1.20.0）。

| 文件 | 规格 |
| --- | --- |
| `apps/workstation/package.json` | dependencies 增 `"axios": "catalog:"`（catalog 已锁定 1.20.0，不进 app 独立版本） |
| `src/api/http.ts` | Axios 单例：`axios.create({ baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api', timeout: 15000 })` 模块级导出（web A.3-1，禁组件直连 axios）。请求拦截器：`Authorization: Bearer {token}`（useAuthStore 延迟调用——拦截器回调运行时 pinia 已安装，B.3-1 组件外使用口径）+ `X-Trace-Id: crypto.randomUUID()`（每请求新生成，后端 TraceIdFilter 复用并回写响应头）。响应拦截器：非 2xx 统一 `ElMessage.error(detail ?? '请求失败')`（detail 取 ProblemDetail body）；**status===401 → 调用注册的未授权回调（清会话+跳登录）**。导出 `setUnauthorizedHandler(cb)` 供 auth store 注册——经回调解耦，禁 http.ts 反向 import router（防 views→api→router 循环） |
| `src/api/auth.ts` | `login(payload: LoginRequest): Promise<LoginResponse>`、`logout(): Promise<void>`、`refresh(token: string): Promise<LoginResponse>` 类型化函数（A.3-5 按域模块化） |
| `src/types/auth.ts` | **手写后备类型**（web A.3-3 明示后备条款）：`LoginRequest/LoginResponse/UserVO` interface，文件头注释"openapi-typescript 生成物可用后由 packages/shared api.d.ts 承接并删除本文件（T-R4-3 演练后启动生成链路）"。userId/orgId 等 Long 字段一律 `string` 类型（A.3-6） |
| `src/stores/auth.ts` | Pinia Setup Store（B.3-1）：state `token/refreshToken/user`；持久化 sessionStorage（初始化读入、变更写回、登出清除——医疗工作站换机即失效语义）；getter `isLoggedIn`；action `login(credentials)`（调 api→写 state）、`logout()`（api 忽略失败→清 state→跳 /login）、`loadFromStorage()`。构造时注册 setUnauthorizedHandler(clear+跳转)。组件外（守卫/拦截器）使用均为回调内延迟调用，合规 B.3-1 |
| `src/router/index.ts`（改造） | 路由表：`/login` → `views/login/LoginView.vue`（`meta: { public: true }`）；`/` → `views/layout/MainLayout.vue`（懒加载）嵌套 children：`''` → home（HomeView 迁入）。`beforeEach`：默认拒绝——`!to.meta.public && !auth.isLoggedIn` → `return { path: '/login', query: { redirect: to.fullPath } }`；`/login` 且已登录 → `return '/'`（防死循环）。`meta` 承载权限语义（web B.3-2） |
| `src/views/login/LoginView.vue` | el-form（loginName/password，rules：必填+长度 4-64），提交按钮 loading，错误走拦截器统一 message（组件内不重复弹错），成功跳 `route.query.redirect ?? '/'`。回车提交 |
| `src/views/layout/MainLayout.vue` | el-container 三段：AppSidebar / AppHeader / el-main（RouterView）。多词组件名合规 |
| `src/views/layout/components/AppSidebar.vue` | el-menu 静态菜单（首页 + 占位分组文案；权限驱动菜单 P1，P0 不接角色接口） |
| `src/views/layout/components/AppHeader.vue` | 面包屑/系统名 + el-dropdown 用户区（displayName 取 store，command=logout → store.logout()）。登出必须清 sessionStorage 并回登录页 |
| 单测（vitest + @vue/test-utils，jsdom） | `stores/auth.spec.ts`（登录写 state+sessionStorage、登出清空、isLoggedIn 计算）；`router/router.spec.ts`（未登录访问 / 重定向 /login；public 路由直通；已登录访问 /login 重定向 /）；`views/login/LoginView.spec.ts`（空提交被校验拦截、有效提交调用 store.login）；`api/http.spec.ts`（请求头注入 Authorization 与 X-Trace-Id；401 触发未授权回调——以自定义 adapter/vi.mock 承载，禁打真实网络）。ElMessage 在测试中 mock |
| `src/App.spec.ts`（存量修改） | 现断言 `router.currentRoute.value.path === '/'` 将因守卫重定向 /login 失效——按"因本次改动失效的旧测试直接修改"原则改造：注入测试会话（store.setToken）后再断言首页可达，或拆两条（未登录重定向 + 登录后可达） |
| `.env.example` / `vite-env.d.ts` | 无新增 VITE_ 变量（复用 VITE_API_BASE_URL），无需三处同步 |

红线：token 与患者敏感数据禁入日志（web A.6）；路由组件全懒加载；组件禁直连 axios；store 禁 import views；表单双向绑定 defineModel/el-form model 按 Element Plus 惯例；禁 any。

---

## 5. 端到端 IT 规格（B3.3，loop P3 出口门禁载体）

容器基座与 SmokeStackIT/MessagingGovernanceIT 完全同款（static @Container + @ServiceConnection + `it/rabbitmq.conf` 挂载 + `@ActiveProfiles("test")`）；HMAC 密钥经 `@DynamicPropertySource` 注入测试用 64 字符随机串（测试资产假密钥，非真实凭证）；HTTP 走 `webEnvironment = RANDOM_PORT` + `TestRestTemplate`（真实穿过 Filter→Interceptor→Controller 全链，MockMvc 不含 Filter 链不作首选）。

### 5.1 AuthFlowIT（登录链路 + 审计落库断言）

1. 错误密码登录 → 401，`errorCode=SYS-1001`，ProblemDetail 含 traceId。
2. admin 正确登录（V303 种子）→ 200，accessToken/refreshToken/user.userId 为 JSON 字符串。
3. 无令牌 `POST /api/v1/system/practice/check` → 401 `SYS-1003`，body.traceId 与响应头 X-Trace-Id 一致。
4. 携带令牌同端点 → 200 骨架响应（passed=false）。
5. refresh → 新 accessToken 可用；logout → 原 accessToken 再访问 → 401（会话已删）。
6. 连续 5 次错密码 → 第 6 次 401 `SYS-1002`（锁定）。
7. 审计断言（JdbcTemplate 查 `system.audit_log`）：登录后存在 `action_type=LOGIN` 行，`operator_id=admin`、`trace_id`=请求注入的 X-Trace-Id、`result=SUCCESS`；错误密码登录后存在 FAIL 行且 fail_reason 不含明文密码。

### 5.2 DictBroadcastIT（字典发布 → @RabbitListener 消费端到端）

1. V5/迁移断言：event_registry 含 `system.dict.published` 行且 ACTIVE（回归 MessagingGovernanceIT Order1 口径）。
2. 注入 IDictTypeService/IDictVersionService/IDictItemService 建 type→draft version→item→`publish(versionId)`（代理调用事务提交触发 AFTER_COMMIT 发布）。
3. 轮询断言：`integration.received_event` 出现 `consumer_module='system'` 且 status=PROCESSED 行（真实 DictPublishedListener 消费成功，标准范式 recordProcessed 落库）。
4. event_registry 该事件 `subscriber_modules` 含 `system`（队列声明构件订阅自动登记副作用）。
5. 幂等重投：以已消费 eventId 手工经 RabbitTemplate 重发同信封帧 → received_event 该 (event_id, consumer_module) 行数仍为 1（D-7 回查路径：NX 失败+台账已处理→跳过）。
6. 业务读口径：publish 后 `GET /api/v1/system/dicts/{type}`（带 token）返回 PUBLISHED 版本条目；旧版本自动 DEPRECATED。

验收对照（计划 §1-PR-3 验收行）：步骤 3/4 = "登录→携带令牌访问受保护端点→无令牌 401"；DictBroadcastIT 全程 = "字典发布→@RabbitListener 消费端到端"；AuthFlowIT 步骤 7 = "审计日志落库断言"。

---

## 6. TDD 与验收指令

**TDD 铁律**（loop §7）：每任务先写失败测试（RED）再实现（GREEN）再重构；测试与实现同一次提交；禁止先写生产代码。

| 批次 | 完成标准 |
| --- | --- |
| B3.1 | 迁移可重放（SmokeStackIT 类上下文启动即验证 Flyway）+ 令牌/会话/登录 service 单测绿；`cd backend && mvn -B -ntp verify` 全绿（含 Spotless、JaCoCo：BUNDLE ≥0.80、`com.fuyun.system.service.impl` 与 `com.fuyun.integration.service.impl` PACKAGE LINE=1.00） |
| B3.2 | 同上门禁全绿 + D-7 改造单测与既有 MessageIdempotencyServiceImplTest 回归绿 |
| B3.3 | 同上 + AuthFlowIT/DictBroadcastIT failsafe 绿（Testcontainers 需 Docker） |
| B3.4 | `cd web && pnpm install --frozen-lockfile`（首次新增 axios 会更新 lockfile，本地正常 install 后提交）→ `pnpm lint`（--max-warnings=0）`pnpm format:check` `pnpm type-check` `pnpm test` `pnpm build` 五门禁全绿（`pnpm audit` 由 CI frontend job 承载） |
| PR-3 整体 | 计划 §1-PR-3 验收行全过（§5 两 IT + 审计断言）→ feature 分支 → `gh pr create` → 五 checks（backend/verify、frontend/verify、images、commitlint、hygiene）全绿 → /code-review 无 findings → 合入 dev |

调试单 IT 命令（宪法 C.4）：`mvn -B verify -Dit.test=AuthFlowIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`。

---

## 7. 提交切分建议（conventional commits，中文 subject，逻辑顺序）

1. `docs(changelog): 登记 D-6 枚举目录裁决生效与 system 号段占用（先记再改）`
2. `docs(backend): 宪法 B.1/C.3 枚举包目录更名为 enums（D-6）`（同提交内完成 20 模块目录更名，原子修宪）
3. `feat(system): RBAC 五表/字典三表/审计表迁移与 RBAC 种子（V300–V303）`
4. `feat(system): HMAC 令牌签发校验与 Redis 会话构件（D-2）`
5. `feat(system): 登录刷新登出端点与 401 认证拦截器`
6. `fix(integration): 幂等前置键抢占失败回查台账（D-7 消除丢消息窗口）`
7. `feat(system): 字典管理与 system.dict.published 发布确认回调`
8. `feat(system): dict.published 消费者与订阅队列治理声明`
9. `feat(system): 审计切面落库与敏感字段脱敏工具`
10. `feat(system): practice/check 执业授权校验骨架端点`
11. `test(backend): 登录链路与字典广播端到端 IT（含审计落库断言）`
12. `feat(workstation): 登录页主布局与 Axios 单例路由守卫`
13. `chore(changelog): 登记 PR-3 M01 系统与权限基础变更条目`（收尾，含 deploy/.env.example 增键与 compose 透传如发生）

---

## 8. 红线清单（实现与审核双用，违者不得合入）

1. **错误码**：`<模块助记>-<4位>`（SYS-xxxx）枚举落 fuyun-system `api/` 包，实现 common `ErrorCode`，全项目唯一；失败一律 ProblemDetail（errorCode + traceId 双属性），禁"全 200+错误码"。
2. **配置**：禁 @Value 散落——HMAC 密钥/TTL 全部走 `SecurityProperties`（@ConfigurationProperties + @Validated 构造器绑定，fuyun.* 前缀）；密钥仅经 `FUYUN_SECURITY_TOKEN_HMAC_SECRET` 环境变量注入，yml/代码/文档/测试断言出现明文真实密钥即红线（测试用假密钥须注释声明为测试资产）。
3. **Redis**：会话键 `fy:system:session:{sid}` 冒号分层；除白名单外禁无过期键（会话必有 TTL）；String 序列化禁 JDK 序列化。
4. **审计**：留存 ≥6 个月（等保红线，M01 目标 ≥3 年）；写入与业务事务解耦、失败不阻断业务但必须 error 告警；audit_log 只增（应用层零 UPDATE/DELETE）；审计查询留痕 P1。
5. **脱敏**：密码/令牌/执业证书号禁入日志与审计 detail/ fail_reason；脱敏统一走 common SensitiveMasker，禁业务代码散落正则；日志禁打印 token 与完整敏感字段（全局 §二）。
6. **注入与事务**：构造器注入强制（禁 @Autowired 字段）；@Transactional 只在 service impl 方法级，controller 禁加；事务内禁 MQ 发送（发布走 AFTER_COMMIT）。
7. **MP**：三大插件已在 app MybatisPlusConfig 注册勿重复；mapper 接口加 @Mapper 即被既有 @MapperScan 扫描；单表 lambdaQuery 链式 + select() 精确投影禁 SELECT *；实体 @Getter/@Setter 禁 @Data；ASSIGN_ID 禁手动赋值。
8. **MQ**：交换机全集三件套禁私建；消费队列声明一律走 MessagingGovernance 构件（先登记后订阅自动完成）；消费 @RabbitListener + AUTO 确认 + 标准幂等范式（D-7 语义）；发布走信封 codec + 确认回调（nack/不可路由 error 告警）；事件对象载荷落发布方 api 包（DictPublishedPayload 已在，禁重复定义）。
9. **迁移**：禁修改已应用迁移；禁 CREATE INDEX CONCURRENTLY（T-R3-1 规避）；号段 system=V300–V399 先登记后使用；触发器复用 V1 公共函数。
10. **枚举/常量**：枚举落 `enums/`（D-6 修宪后正文），code↔enum 双向映射；常量 constants/ 私有构造器；禁魔法值散落。
11. **令牌安全**：登录失败文案防用户枚举；签名比较常量时间；刷新/登出须校验 typ；会话删除即全端失效。
12. **测试**：`com.fuyun.system.service.impl` 与 `com.fuyun.integration.service.impl` JaCoCo LINE 1.00 硬门禁（出现类即生效）；因本 PR 失效的存量测试（App.spec.ts）必须同步改造，禁留失效测试；测试命名表达业务意图；断言针对业务结果。
13. **前端**：token 禁打日志；Axios 单例唯一出网口；路由全懒加载 + meta 权限语义；store Setup Store 且组件外延迟调用；Long 一律 string 承载禁 number 计算。
14. **流程**：先记再改（CHANGELOG）；表外依赖申报（fuyun-system pom 新增五依赖、deploy/.env.example 增键、D-7 跨模块小改，全部写入 PR 描述）；实现专员严禁并行、TDD 先行、批次过审后推进。

---

## 9. 决策记录

| 决策 | 状态与内容 | 推翻改动面 |
| --- | --- | --- |
| D-2 令牌方案（loop 决策编号 = 计划 §2"令牌方案"行；注意与 TASK.md D-2"Spring Modulith"撞号，两者无关） | **已按默认裁决生效（2026-09-09）**：轻量 HMAC-SHA256 签名令牌 + Redis 会话；JWT 全家桶评审随 P1；不引 spring-security | TokenServiceImpl/SecurityProperties/AuthController 契约 + 前端 auth store 与登录页令牌获取方式；表结构与端点路径不受影响 |
| D-6 枚举包目录 | **已按默认裁决生效**：`enums/`；PR-3 修宪提交（CHANGELOG → 正文 → 目录更名三同步） | 目录名 + 包名 + 宪法正文，一次替换可回收 |
| D-7 幂等 NX 误判补救 | **已按默认裁决生效**：NX 失败回查 received_event 表，确认已处理才跳过。注：PR-2 合入（a019f47）先于裁决，改造未落码——**本 PR-3 内完成**（MessageIdempotencyServiceImpl 单类 + 接口 javadoc + 单测） | 该单类 + 单测（若改判缩短 TTL 或维持现状） |

**本简报新增的推导性建议（非阻塞，实现专员按下执行，审核专员核对合理性；用户可推翻后重下简报）**：

1. system 号段采纳 W-4 建议值 V300–V399（§2.1）。
2. `practice_cert_no` 加密列 P0 不建：唯一消费方 practice_grant 表属 P1；先建列将催生无写入方的 AES-GCM 加密死代码（A.4.2-10 与死代码零容忍冲突）。P1 随执业授权一并交付（列 + Spring Security Crypto AES-GCM + 盲索引）。A.4.2-10 在 P0 因此**未触发**（五表无手机号/身份证/住址列）。
3. employee 多机构归属（Spec"org 归属（可多）"）P0 以 `primary_org_id` 单主归属表达，junction 表 P1。
4. V303 admin 种子初始口令 `Fuyun@2026`（bcrypt 字面量入库），P1 密码策略交付时强制改密（§2.6）。
5. 审计切面 P0 同步写（controller 层、事务外、try-catch 告警），异步批量 P1（A.1-10 异步链路传递成本）。
6. 字典发布 P0 立即生效（effective_at=now），fy.delay 定时生效 P1。
7. 登录 IP 限流 P0 不做，仅失败计数锁定（M01 §9 限流 P1 经 Redis 计数实现）。
8. 字典读接口全量返回豁免分页（契约型读，理由入 javadoc）。
9. 拦截器落 fuyun-system 非 common（§1.5，B.1 边界推导）。

---

## 附：待裁决 / 关注项汇总（回报主控）

1. **D-7 跨模块小改**：改造点在 fuyun-integration（非 fuyun-system），PR-3 PR 描述必须申报；不影响 integration 对外契约（接口签名不变，仅语义增强）。
2. **D-2 编号撞号**：loop 文档 D-2（令牌）与 TASK.md D-2（Modulith）编号复用，简报已显式区分，主控登记时注意。
3. **deploy 变更面**：`.env.example` 增 1 键 + compose environment 可能增 1 行透传，属 PR-3 表外文件变更，需 PR 描述申报。
4. **Spec 切片简化项**（§9 建议 2/3/6/7）均为 P0 计划切片内的合理延后，非 Spec 冲突；M01 Spec 对应条目（执业授权、组织闭包表、定时生效、限流、密码策略）在 P1+ 落地时以 Spec 原文为验收口径。
5. **无 spec 硬冲突**：M01 Spec §7 API/MQ 清单、M20 治理约定、两宪法条款在简报中均可直接对齐；唯一口径澄清为"令牌含角色摘要"由 sid→Redis 会话承载（D-2 裁决的推导结论）。
