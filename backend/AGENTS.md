# backend 宪法（Java 17 + Spring Boot 3.5 模块化单体）

> 本子项目最高规范：**只存工程原则与约束**。功能实现与业务数据契约见代码与 `../docs/specs/`，待办见 `../TASK.md`，变更记录见 `../CHANGELOG.md`（先记再改），本文档均不重复。
> 任何与本文档冲突的代码或设计不得合入 main 分支。
> 仓库定位层见根 [../AGENTS.md](../AGENTS.md)：跨切总则（编码红线 / 版本红线 / 安全红线 / CI 链总则）不在此重复，说明性内容不复制，也不引用兄弟子宪法替代成文（与前端相同的约束在本文件完整成文）。
> 注释 / 日志 / 测试覆盖规范见全局 `~/.zcode/AGENTS.md`（§一 注释规范、§二 日志规范、§四 测试与死代码），强制生效。
> 配套文件职责（specs / TASK.md / CHANGELOG.md 边界）由根定位层 §7 声明，本文件只留指向、不复述。

**三段结构**：Part A Java/Spring Boot 通用 / Part B 架构分层 / Part C backend 实际。

---

## Part A — Java 17 + Spring Boot 3.5 通用规范

### A.1 编码约束

1. 语言基线 Java 17（LTS，Temurin）；Spring Boot 3.5.16 为 3.x 最终开源版，冻结该版本行，升级只能走总 Spec 修订裁决。
2. record 用于透明浅不可变数据载体（DTO、值对象、参数对象、查询投影）；禁止用于需要继承多态的领域实体与可变状态载体。
3. sealed + switch 表达式用于有限状态 / 受限子类型（错误分类、审批流节点、状态机分派），获得编译器穷尽检查；禁止为"封装"而对开放层级使用 sealed；switch 表达式禁用于替代含长副作用流程的 if。
4. text block 仅用于静态多行文本（测试断言 JSON、报文样例），禁止拼接业务变量与敏感值。
5. Optional 仅作方法返回类型表达"明确无结果"；Optional 变量永不为 null；禁止作字段、入参、集合元素、构造器参数；配置类中禁用 Optional 字段。
6. 值对象 / DTO 的 equals/hashCode 直接采用 record 语义，禁止手写；业务异常一律继承 RuntimeException 体系（禁止 checked 业务异常），与事务默认回滚语义互锁。
7. 构造器注入强制，禁止字段注入（@Autowired 字段）；必填依赖走构造器、可选依赖走 setter；构造器参数过多视为拆分职责信号（配合 A.7 参数对象化）。
8. @Transactional：默认只回滚 RuntimeException/Error，按需显式 rollbackFor；事务边界归 service 层，controller 禁加 @Transactional；禁止事务方法自调用失效（同类内互调不经过代理，需拆类或经代理对象）。
9. Bean 默认 singleton，服务类必须无状态（多实例部署前提）；prototype/request 作用域引入需评审。
10. @Async 与异步事件的三条限制必须处理：异常不上抛调用方、返回值不级联、ThreadLocal / 日志上下文（traceId）默认不传播——引入异步前先解决链路传递，防审计断链。
11. 禁止裸 System.out / printStackTrace，统一 SLF4J（Boot 默认 Logback）。
12. Lombok 有限采用（BOM 托管 1.18.46）：仅允许 @Getter/@Setter/@RequiredArgsConstructor/@Slf4j/@Builder；禁止 @Data 用于实体与含敏感字段对象、禁止 @AllArgsConstructor；数据载体优先 record。
13. 禁止全限定类名声明：一律 import 后使用短类名，仅同名类冲突时允许全限定名消歧。
14. 禁止使用 @Deprecated 标记的 API（JDK、Spring、MyBatis-Plus 等全部依赖）；依赖升级出现的弃用告警随版本演进清理，禁止新代码引入。
15. **注解优先**：能用注解 / 框架声明式能力解决的（校验、事务、缓存、MQ 监听、调度、权限等），禁止手写样板代码与命令式重复实现。
16. 代码格式由 Spotless 强制（见 C.5/C.6），手写风格不一致不构成违规豁免理由。

### A.2 配置管理

1. 配置分层：`application.yml` 只放全环境公共项与安全默认值；`application-{profile}.yml` 承载 dev/test/prod 差异；固定三个 profile。
2. 配置一律建 `*Properties` 类经 `@ConfigurationProperties` 构造器绑定（支持 record），禁止 @Value 散落；配置类加 `@Validated` + JSR-303 约束，启动期即校验失败。
3. `spring-boot-configuration-processor` 进 compiler 插件 annotationProcessorPaths（编译期），生成配置元数据；手工补充走 additional 元数据文件。
4. 业务配置统一 `fuyun.*` 前缀（与 `FUYUN_*` 环境变量组 relaxed binding 对应）。
5. 敏感配置（数据库口令 / Redis 口令 / RabbitMQ 凭证 / MinIO 密钥 / IoTDA 凭证 / NVD API key 等）一律环境变量占位 `${VAR}` 注入；yml / 代码 / compose / 文档中出现明文密钥即为红线违规。
6. **常量集中 constants/ 包**：项目全部常量配置走常量类，定义使用 `public final static` 字段 + 私有构造器防实例化；禁止魔法值散落业务代码；Lua 脚本 SHA、Redis 键前缀、错误码前缀等运行期不变量同属常量。
7. **枚举统一 enum 类型**：定义于 enum/ 包，禁止用常量类 / 整型魔法值模拟枚举；携带业务 code 的状态枚举必须实现 code↔enum 双向映射方法（供 JSON 序列化与 MyBatis-Plus TypeHandler 使用）。

### A.3 API 设计（REST + OpenAPI）

1. 所有 REST 接口挂统一前缀 `/api/v1`，禁止无版本接口；URL 资源名复数、小写连字符、层级表从属（`/api/v1/patients/{id}/visits`）；动作型端点收敛为子资源 POST。
2. 成功响应：HTTP 2xx 直接返回业务数据（DTO），不做 envelope 包装；失败响应：一律 RFC 9457 ProblemDetail（`spring.mvc.problemdetails.enabled=true`），业务错误码经 `properties.errorCode`、排查锚点经 `properties.traceId` 携带。
3. 双层错误模型：HTTP 状态码表达传输语义（400/401/403/404/409/500），`errorCode` 表达业务失败原因，禁止"全 200 + 错误码"。
4. 错误码枚举 `<模块助记>-<4 位数字>`（如 `ORDR-1001`），定义于各模块 api 包，全项目唯一；业务异常携带错误码，经全局 `@RestControllerAdvice`（继承 ResponseEntityExceptionHandler）统一渲染，禁止各 controller 各自兜底。
5. 请求校验强制声明式：请求 DTO 字段挂 Jakarta Validation 约束注解 + controller 参数 `@Valid`；跨模块接口入参约束写在接口上（类级 @Validated）；禁止 service 层散落手写 if 判空替代契约校验。
6. 分页契约：请求 `page`（0 基，必须显式告知前端）/`size`/`sort=字段,方向`；响应 `{content, page, size, total}`，大清单报表接口允许降级 `{content, hasNext}`。
7. Springdoc 2.8.17（禁升 3.x）：契约=DTO 注解（@Tag/@Operation/@Schema）推导生成，禁止绕开 DTO 手绘 schema；Swagger UI 仅 dev/test 暴露，prod 关闭。
8. **JSON 序列化精度防线**：Jackson 全局注册 Long/long → String（ToStringSerializer）——雪花 ID、金额分值等超出 JS `Number.MAX_SAFE_INTEGER`（2^53）的长整型统一以字符串输出，前端以字符串接收；该配置集中注册于 config/ 包的 Jackson 定制 Bean，禁止各接口零散处理。

### A.4 数据库操作（数据访问定稿：MyBatis-Plus）

**A.4.1 迁移与 Schema**

1. **Schema 唯一来源 = Flyway**：全部 DDL（含索引、TimescaleDB 策略）只经版本化迁移；MyBatis-Plus 无 schema 参与天然零冲突，禁止引入 Flyway 之外的任何 schema 管理通道（禁 schema.sql/data.sql、禁自动 DDL）。
2. 迁移命名 `V{version}__{desc}.sql`（描述全小写下划线）；跨环境数据修复用 `R__`；版本号采用模块分段号段（公共域低位、每模块固定百位段，如患者域 V100-V199、医嘱域 V200-V299），CI 校验号段归属与版本唯一。
3. 禁止修改已应用迁移（checksum 校验必失败）；错误修正只能追加新版本迁移；全环境 `outOfOrder=false`、`baseline-on-migrate=false`（从零建库）。
4. 迁移文件随模块源码（`src/main/resources/db/migration/{module}`），主应用 `spring.flyway.locations` 显式枚举全部模块目录（禁通配符）；`default-schema` 指向公共 schema（承载 flyway_schema_history），`schemas` 全量声明。
5. TimescaleDB：`CREATE EXTENSION` 只在 deploy/postgres/initdb 承担，Flyway 不重复；超表迁移按"CREATE TABLE → create_hypertable（按天分区）→ 压缩开启与策略 → 保留策略"顺序，分区列必须入主键/唯一约束；压缩 7 天后启用、明细保留 90 天 / 聚合 1 年为定稿默认值，策略函数 P0 实测后锁定（见 TASK.md T-R3-2）。

**A.4.2 连接、事务与数据规范**

6. 连接池 HikariCP：固定大小池（minimumIdle=maximumPoolSize），单实例 10-20 起步压测校准，多实例总连接数低于 PG max_connections 余量；`leakDetectionThreshold` dev/test 60s、prod 300s，禁止 0；JDBC URL 全环境 `reWriteBatchedInserts=true`。
7. 事务边界：`@Transactional` 放 service 实现层方法级最小边界，禁止 controller 开事务、禁止业务代码手动 commit/rollback；默认只回滚 RuntimeException/Error（按需显式 rollbackFor）；事务内禁止远程调用、消息发送与人工等待，对外调用在事务提交后执行（需原子性时走 outbox 模式评审）；查询服务方法标注只读事务（不作为写保护）；遥测批量落库按 500-5000 条/批独立事务。
8. **金额分值制**：DDL 一律 `BIGINT` 存储"分"，应用层全程 `long`（禁 float/double 参与金额存储与运算，整数运算天然无舍入）；分↔元换算集中在 convert/ 层 MoneyUtil 统一承担（含展示格式化与入参校验），禁止业务代码散落 `*100` / `÷100`；金额计算全部服务端完成。（2026-09-08 裁决由 NUMERIC(18,2) 方案改为分值制，见 CHANGELOG v1.2）
9. 审计字段（created_at/updated_at/created_by/updated_by）：时间戳由数据库维护（DEFAULT now() + updated_at 触发器），操作人由应用层统一注入；审计日志留存 ≥6 个月（等保三级）。
10. 敏感字段（身份证 / 手机号 / 住址等）应用层列加密（Spring Security Crypto AES-GCM，BOM 托管）后落库，密钥经环境变量/KMS 注入；需等值检索的字段配 HMAC 盲索引辅助列；生产由加密云盘兜底静态加密。
11. 只用官方 `timescale/timescaledb` 镜像（Apache-2+TSL 发行版，压缩/保留策略可用），禁换纯 Apache 构建。

**A.4.3 MyBatis-Plus 使用规范**

12. 依赖定稿：`mybatis-plus-spring-boot3-starter:3.5.17`（Boot 3 必须用 spring-boot3 后缀 starter）+ `mybatis-plus-jsqlparser:3.5.17`（3.5.9 起分页等插件必需，单独引入）；父 POM 显式锁版本（Boot BOM 不托管）；官方禁止再引入 mybatis / mybatis-spring / mybatis-spring-boot-starter（MP starter 已内含 mybatis 3.5.19 + mybatis-spring 3.0.5，重复引入因版本差异出错）。
13. 单表链式编程：本 service 主表操作一律 ServiceImpl 内置 `this.lambdaQuery()` / `this.lambdaUpdate()`（IService 能力），不手动构建 wrapper；查询目标非本 service 主表（副表/跨模块）才用 `Wrappers` 静态工厂；wrapper 禁止跨层/跨线程传递。
14. 按需取列：查询必须 `select()` 精确投影，禁止 SELECT *、禁止全字段取回后丢弃；循环内单查改 in 批量，拒绝 N+1。
15. 复杂 SQL（连表 / 分组统计 / 聚合 / 复杂条件）必须走 mapper 接口 + `resources/mapper/` XML 映射文件（类名.xml），禁止业务层拼 SQL、禁止 JdbcTemplate 字符串拼接；XML 只用常用标签（select/insert/update/delete/where/if/foreach/set/choose）。
16. ID 生成：实体 `@TableId(ASSIGN_ID)` 自动雪花 ID，禁止手动 IdWorker；批量插入用 `saveBatch`（JDBC 批处理、自动填充 ID，**须在事务内调用**）。
17. 查询必带分页（`PaginationInnerInterceptor` maxLimit=2000）；分页/取前 N 必须带 ORDER BY 约束唯一顺序；大 OFFSET 深翻页改 Keyset（游标）方案。
18. 逻辑删除统一 `@TableLogic` + 全局配置（logic-delete-value=1 / logic-not-delete-value=0）；并发敏感实体用 `@Version` 乐观锁 + `OptimisticLockerInnerInterceptor`。
19. 注册 `BlockAttackInnerInterceptor` 拦截无 WHERE 条件的全表 UPDATE/DELETE（命中抛异常拒绝执行）；分页 / 防全表 / 乐观锁三大插件集中注册于 `MybatisPlusInterceptor` Bean（config/ 包），禁止散落配置。
20. 实体与 mapper 归位：`@TableName` 实体放 entity/ 包、mapper 接口放 mapper/ 包，均不出 service 边界；Service 层 CRUD 型接口 `extends IService<Entity>`、实现 `extends ServiceImpl<Mapper, Entity>`（接口 I 前缀 + 实现 Impl 后缀，落 service/impl/）；聚合/报表型接口不继承 IService，实现注入所需 mapper。
21. 跨 service 复用查询：经依赖注入调用对方 service 公开方法（含对方实例的链式能力），禁止直接操作他人 mapper、禁止复制查询逻辑。

### A.5 基础设施生命周期

1. **Redis**：Lettuce（Boot 默认）；Key 用 String 序列化、Value 用 JSON 序列化（禁 JDK 序列化）；键命名 `fy:{module}:{biz}:{id}` 冒号分层；除白名单外禁无过期键（长期字典也必须有 TTL 兜底）。
2. **并发锁**：防超卖最终由数据库唯一约束兜底（锁只做效率层）；毫秒-秒级短持锁可用 BOM 托管的 Spring Integration `RedisLockRegistry`；需要 watchdog 自动续期 / 可重入 / 读写锁等完整分布式锁语义时引入 Redisson 4.7.0（`redisson-spring-boot-starter`，须配套 `redisson-spring-data-35`，父 POM 锁版本，非 BOM 托管）；禁止自研 SET NX + Lua 锁。
3. **缓存一致性**：默认 Cache-Aside（先更新数据库再删除缓存）+ TTL 兜底 + 删除失败重试；禁"延迟双删"。
4. **RabbitMQ 生产端**：`publisher-confirm-type: correlated` + `publisher-returns` + mandatory，全部业务发送走确认回调（nack/不可路由记 error 日志并重发或告警），confirm 为异步回调禁同步阻塞；业务队列全部 quorum 类型，声明集中在 M20 治理构件（私建交换机禁止）。
5. **RabbitMQ 消费端**：一律 `@RabbitListener` 注解驱动（禁手编监听容器 Bean）+ 容器 AUTO 确认模式——监听方法成功返回即自动确认、抛异常按重试策略拒绝，语义等价"业务成功才确认"且消除 MANUAL 模式漏写 ack 的事故源；消费失败有界重试（默认 3 次退避）后进 `fy.dlx`，死信统一落库 + 告警；`defaultRequeueRejected=false` 兜底，禁止裸无限重投。AUTO 不改变 at-least-once 投递语义，幂等条款（第 6 条）不豁免。
6. **消费幂等**：幂等键在业务表建唯一索引（最终保证）+ Redis SET NX 前置去重（加速），两者都必须有。
7. **延迟消息**：`fy.delay` 队列级 TTL + DLX 死信转发（一条队列一个延迟档位），不引入 delayed-message 插件。
8. **监听器**：并发与 prefetch 按队列分级（遥测高吞吐 4-8 / 250；关键业务 2-4 / 10-50），压测校准。
9. **IoTDA AMQP（Qpid JMS 2.11.0）**：与 Spring AMQP 完全连接隔离（自建 ConnectionFactory + 专用容器工厂 + 独立 `iot.amqp.*` 配置前缀）；URI 显式写全 failover 参数（initialReconnectDelay=3000 / reconnectDelay=3000 / maxReconnectDelay=30000）；消费者包装为 SmartLifecycle，连接数预算"实例数 × 每实例连接数 ≤ 32"（单凭证上限）；凭证经 env 注入；部署机 NTP 同步为前置检查。
10. **IoTDA 消费硬约束**：服务端仅缓存 24h/1GB 积压——消费侧必须高可用 + 快速落库 + 批量化，监控"积压水位 + 断链时长"双指标；关键告警保留 IoTDA 联动规则 HTTP 兜底双通道。
11. **对象存储**：统一 AWS SDK for Java v2（2.54.13，父 POM 锁版本），`forcePathStyle + endpointOverride` 配置化切换 MinIO/OBS；禁用 minio-java SDK（上游已归档停维）；≥100MB 走 TransferManager 分片，读取流式返回禁全量入内存；dev 桶由应用启动检查创建，prod OBS 桶运维预建、应用只校验可访问性 fail-fast。
12. **HL7 MLLP（HAPI 2.6.0）**：HapiContext 生命周期随 Spring（SmartLifecycle）；解析链"原始字节 → 显式字符集（与对接方联调定）→ PipeParser → 必填段校验"；解析失败原样落库待人工处理，禁止静默丢弃；MLLP 网络线程与业务线程隔离。
13. **DICOM（dcm4che 5.35.1）**：作为库嵌入 imaging 模块，Device/Connection 对象随 Spring 生命周期启停，DICOM 端口与线程池独立于 web 线程池。
14. **定时任务**：多实例下 @Scheduled 必须配 ShedLock（6.10.0，JDBC provider 复用业务 PG，锁表放公共 schema，lockAtMostFor=任务最长执行上界）；定时任务一律设计为幂等可重跑；禁止裸 @Scheduled 多实例部署；Quartz 本期不引入。
15. **优雅停机**：`server.shutdown=graceful`（超时 20-30s，与部署 stop_grace_period 对齐）；停机顺序：readiness 置 DOWN → 停 MQ/MLLP/DICOM/JMS 拉取 → 排空在途 → 关连接池；actuator 开启 liveness/readiness 探针，readiness 纳入 DB/Redis/RabbitMQ 连通性。
16. **缓存实现分层**：简单场景（非热 key、TTL 可配置化、失效可表达为单键/全量）走 Spring Cache 注解（`@Cacheable` / `@CacheEvict`）+ `RedisCacheManager`，TTL 经配置注册，禁止硬编码散落注解方法；复杂场景（热 key 保护 / 键前缀批量失效 / 多步原子逻辑）按业务领域做缓存设计——领域缓存类放 cache/ 目录、命名 `{Domain}CacheService`（如 `UserCacheService`），内部经 Redisson / RedisTemplate 实现，业务层只注入该类；禁止把复杂缓存逻辑散落 service。
17. **Lua 原子脚本**：脚本文件统一放 `resources/lua/` 目录；经 Redisson RScript **预注册**——应用启动时 `SCRIPT LOAD` 全部脚本、SHA 缓存为单例常量（constants/），运行时一律 `EVALSHA` 调用（NOSCRIPT 异常回退重载后再执行）；**所有原子性脚本操作的 key 必须携带 `{业务功能}` hash tag**（如 `{user-lock}:123`），保证同一业务的 key 在 Redis Cluster 语义下命中同一实例；禁止应用层"读-改-写"竞态代码替代 Lua 原子操作。

### A.6 注释 / 日志 / 测试

见全局 `~/.zcode/AGENTS.md` §一（注释规范）、§二（日志规范）、§四（测试与死代码：核心功能单测 100%、非核心 ≥80%、核心链路集成测试、死代码零容忍）。本项目强制生效。补充：模块划分与核心包清单以 `docs/specs/` 为准，覆盖率阈值落地见 C.5；mapper 层切片测试可用官方 `@MybatisPlusTest`（`mybatis-plus-spring-boot3-starter-test`，嵌入式数据库不依赖容器），跨层集成测试仍 `@SpringBootTest` + Testcontainers。

### A.7 跨层数据对象与传参约束（必含）

1. **参数对象化**：公开方法（含跨模块 api 接口）形参 > 3 必须参数对象整体传参（record 为标准实现）；强相关参数对（起止日期等）即使 2 个也建议成对象；仅业务确需灵活传参可例外。
2. **返回业务对象**：业务返回必须是业务对象（DTO/领域对象），禁止裸 Map/`List<Object>` 作业务数据载体（仅算法局部变量可用）；请求与响应分别建模（`XxxCreateRequest`/`XxxUpdateRequest`/`XxxQuery`/`XxxResponse`），禁同一对象双向复用。
3. **职责隔离**：DTO ≠ Entity ≠ 查询对象——Entity 只服务持久化与事务写、DTO 服务 API 契约、查询对象（record）服务读投影；字段全同也按职责各自建类；禁止 Entity 直接出 API 层与进跨模块 api 包（字段过曝 + 敏感字段泄漏 + 懒加载序列化风险）。
4. **DTO 映射**：引入 MapStruct 1.6.3 承担批量常规映射（须与 lombok-mapstruct-binding 同置 processor path）；金额、医嘱状态等关键业务字段映射必须手写或单测全覆盖。
5. **例外边界**：仅业务功能确需动态/灵活结构可偏离，且须能陈述业务理由；无理由的违反视为缺陷，不得合入。

---

## Part B — 架构分层（模块化单体）

### B.1 目录职责边界（Maven 多模块）

| 目录 / 模块 | 边界 |
| --- | --- |
| `backend/pom.xml`（parent） | 聚合全部子模块；`dependencyManagement` 以 import scope 导入 spring-boot-dependencies:3.5.16，不继承 spring-boot-starter-parent |
| `fuyun-common`（公共模块） | 公共工具 / 基础异常 / 全局异常渲染 / 审计支撑；不依赖任何业务模块 |
| `fuyun-{domain}`（20 业务模块，对齐总 Spec M01-M20） | 按业务域划分（不按技术分层）；模块内包职责见下表 |
| `fuyun-app`（装配模块） | 唯一可执行入口；聚合配置与全部模块，不放业务逻辑 |

模块内包职责（每个 `fuyun-{domain}` 一致）：

| 包 | 职责边界 |
| --- | --- |
| `api/` | 对外契约唯一出口：跨模块接口、契约 DTO、事件对象、错误码枚举；`internal/` 子包禁止外部引用 |
| `controller/` | 入参校验（@Valid）+ 调用 service + 编排响应；禁业务逻辑、禁直接注入 mapper |
| `dto/` | 请求入参对象（`XxxCreateRequest` / `XxxUpdateRequest` / `XxxQuery`），每接口独立定义，禁跨层复用（A.7） |
| `vo/` | 响应出参对象（前端返回），由 entity/中间结果经 MapStruct 转换产出；禁 Entity 直接出 API 层 |
| `record/` | 特殊处理的实体对象：record 类型的多值返回、临时聚合、查询投影等杂项对象（不隶属上述各层时归此） |
| `service/`（`impl/`） | 业务逻辑 + 事务边界；接口 I 前缀 + 实现 Impl 后缀；CRUD 型 `extends IService/ServiceImpl`（A.4.3-20） |
| `mapper/` `entity/` | 数据层：mapper 接口、`@TableName` 实体；复杂 SQL XML 落 `resources/mapper/`（A.4.3-15）；均不出数据层 |
| `handler/` | MyBatis TypeHandler（类型处理器）：java 类型↔JDBC 列值转换（如 UUID、JSON 列）；经 mybatis-plus.type-handlers-package 全局注册，禁止散落注解指定 |
| `convert/` | MapStruct 转换器（XxxConverter）+ MoneyUtil 金额分↔元集中换算（A.4.2-8）；金额 / 状态关键字段映射手写 + 单测 |
| `cache/` | 领域缓存服务 `{Domain}CacheService`（如 UserCacheService）：复杂缓存设计（热 key / 批量失效 / Lua 原子操作）；简单场景走 Spring Cache 注解（A.5-16） |
| `gateway/` | 外部系统适配（IoTDA / HL7 / DICOM / 医保 / 短信，B.4） |
| `config/` `properties/` | @Configuration 与插件 Bean / @ConfigurationProperties 属性类，全部集中、禁止散落 |
| `constants/` `enum/` | 常量类（public final static + 私有构造器，A.2-6）与枚举（enum 类型 + code 双向映射，A.2-7） |
| `exception/` | 模块业务异常与错误码（基座与全局渲染在 common） |

### B.2 层级依赖（强制）

```
fuyun-app ──装配──▶ fuyun-{domain}.impl ──实现──▶ fuyun-{domain}.api ◀──仅经接口/事件── fuyun-{other-domain}.impl
                                     │
                                     ▼
                              fuyun-common
模块内：controller ──▶ service(impl) ──▶ mapper / entity        （单向，禁止反向与跨层）
```

1. 模块内分层单向：controller → service → mapper/entity；禁止跨层调用（controller 禁调 mapper、mapper 不含业务逻辑、实体不出数据层）。
2. 业务模块只依赖 fuyun-common 与**其他模块的 api 包**；禁止依赖他人 impl；调用方一律注入接口类型（禁注入实现类）。
3. 出现循环依赖时**拆层切断**（交叉查询下沉为独立 service 或 common，双方只依赖下沉层），禁止用 @Lazy / ObjectProvider 延迟注入掩盖。
4. 事务边界 = 业务用例（医嘱-计费-库存联动在同一事务内，总 Spec 强事务约束）。
5. 模块边界由 ArchUnit 1.5.0 测试守护（CI 硬门禁）：slices 互斥（模块间不互相依赖）、分层访问方向、controller 不可被访问；违规即构建失败。
6. Spring Modulith（1.4.13）为增量框架候选，是否引入属总 Spec 修订决策（见 TASK.md）；未引入前禁止任何 Modulith 依赖进父 POM。

### B.3 运行时原则

1. **事件边界**：同事务一致性诉求 → 同步方法调用或 Spring 应用事件（同步、运行在发布者事务上下文）；最终一致 / 跨 bounded context / 需重试与死信 → RabbitMQ 领域事件。事件对象定义在发布方 api 包（契约化）；@TransactionalEventListener 控制提交前后时机。
2. 跨进程领域事件一律走 RabbitMQ（quorum 队列），Modulith @Externalized 桥接仅在引入 Modulith 后评估。
3. 可靠事件投递（发布成功才投递 + 失败可重投）：未引入 Modulith 时需自行实现"事务后事件表 + 定时重投"，成本须在模块设计评审时评估。
4. 定时任务遵循 A.5-14（ShedLock + 幂等）；后台线程池必须显式管理（有界、命名、随上下文关闭）。
5. 多实例前提：全部服务无状态，会话入 Redis，`--scale backend=2` 必须可用（compose 验证项）。

### B.4 外部能力网关（IoTDA / HL7 / DICOM / 医保 / 短信）

1. 外部系统对接一律收敛到所属模块的 gateway/adapter 包，凭证经环境变量注入，禁止凭证出现在代码 / 配置库 / 日志。
2. 每个外部网关必须显式配置：连接超时 / 读超时 / 有界重试（退避）/ 熔断或降级策略（第三方医保、短信等 HTTP 网关用 Spring 风格 RestClient + 重试器，禁裸循环重试）。
3. 出入站报文必须留痕（脱敏后）：外部接口调用与 IoTDA/HL7 报文记录 info 日志（含业务标识与 traceId），满足等保审计。
4. 外部网关的启动 / 停止全部纳入 SmartLifecycle 与 A.5-15 停机顺序；网关不可用不得阻塞应用启动（fail-fast 仅限启动必需依赖：DB/Redis/RabbitMQ）。
5. 医保编码对照、CA 电子签名等业务级集成行为遵循对应模块 Spec（docs/specs/modules/），本文只约束工程形态。

（B.5 横切关注点不单独成节：安全 / 审计红线见根定位层 §7，落库与留痕细则见 A.4 / B.4-3，编号不重排。）

---

## Part C — backend 实际

### C.1 子项目定位

Spring Boot 模块化单体（总 Spec D4），承载全部 20 个业务域的后端：REST API（经 nginx `/api` 反代）、WebSocket 推送（`/ws`）、IoTDA 遥测消费入 TimescaleDB、RabbitMQ 领域事件总线、HL7/DICOM 医技集成。

### C.2 技术栈选型（版本唯一权威：docs/language/2026-09-07-技术栈选型.md v1.1）

| 职责 | 技术 | 版本 | 约束 |
| --- | --- | --- | --- |
| 运行语言 | Java（Temurin） | 17 LTS | 冻结；下个评估点 Java 21 |
| 应用框架 | Spring Boot | 3.5.16（BOM） | 3.x 最终开源版，冻结（10.3 节专项对策） |
| Web/事务/校验 | Spring Framework 6.2.19 / Hibernate Validator 8.0.3.Final | BOM 托管 | 禁自行覆盖 |
| 数据访问 | MyBatis-Plus（mybatis-plus-spring-boot3-starter + mybatis-plus-jsqlparser） | 3.5.17（父 POM 锁定） | 禁再引入 mybatis-spring-boot-starter（官方互斥）；用法见 A.4.3 |
| 连接池 / 驱动 | HikariCP 6.3.3 / pgjdbc 42.7.11 | BOM 托管 | — |
| 迁移 | Flyway | 11.7.2（BOM） | 社区版功能边界 |
| 时序 | TimescaleDB（PG 16 扩展） | 2.29.2-pg16（镜像） | 官方混合发行版 |
| 缓存 / 锁 | Spring Data Redis（Lettuce）/ Spring Integration RedisLockRegistry / Redisson | BOM 2025.0.13 / 6.5.10 / 4.7.0（父 POM 锁定） | Redisson 引入时配套 redisson-spring-data-35；使用边界见 A.5-2 |
| 消息 | Spring AMQP（RabbitMQ 4.3.5）/ Qpid JMS（IoTDA） | 3.2.12（BOM）/ 2.11.0 | 两套连接工厂隔离 |
| 对象存储 | AWS SDK for Java v2 | 2.54.13（父 POM 锁定） | 禁 minio-java |
| 集成库 | HAPI HL7v2 2.6.0 / dcm4che 5.35.1 | 定稿冻结 | M20/M08 模块承载 |
| 定时任务 | ShedLock | 6.10.0（父 POM 锁定） | 配 @Scheduled |
| 加密 | Spring Security Crypto | 6.5.11（BOM） | 字段级 AES-GCM |
| API 文档 | Springdoc OpenAPI | 2.8.17 | 禁升 3.x |
| DTO 映射 | MapStruct | 1.6.3 | 配 lombok-mapstruct-binding |
| 边界守护 | ArchUnit | 1.5.0 | test 依赖 |
| 测试 | JUnit Jupiter 5.12.2 / Mockito 5.17.0 / Testcontainers 1.21.4 | BOM 托管 | 容器 tag 与 compose 严格一致 |
| Lombok | 1.18.46 | BOM 托管 | A.1-12 允许清单 |

### C.3 目录结构

```
backend/
├── pom.xml                    # parent：BOM import、pluginManagement（spotless/jacoco/surefire/failsafe/compiler）、MP 等非 BOM 版本锁定
├── Dockerfile                 # 多阶段构建（规范见 C.5）
├── fuyun-common/              # 公共基础（异常基座/全局渲染/审计/工具），不依赖业务模块
├── fuyun-{domain}/            # 20 个业务模块（对齐总 Spec M01-M20），每个模块内：
│   └── src/main/
│       ├── java/com/fuyun/{domain}/
│       │   ├── api/           # 对外契约（接口/契约 DTO/事件/错误码）——唯一出口
│       │   ├── controller/    # 校验 + 编排
│       │   ├── dto/           # 请求入参对象
│       │   ├── vo/            # 响应出参对象（前端返回）
│       │   ├── record/        # 特殊处理实体对象（record 投影/多值返回/临时聚合）
│       │   ├── service/       # 业务接口（I 前缀）+ impl/（事务边界）
│       │   ├── mapper/        # MyBatis-Plus mapper 接口
│       │   ├── entity/        # @TableName 实体（不出数据层）
│       │   ├── handler/       # MyBatis TypeHandler（类型处理器，type-handlers-package 全局注册）
│       │   ├── convert/       # MapStruct 转换器 + MoneyUtil 金额换算
│       │   ├── cache/         # 领域缓存 {Domain}CacheService
│       │   ├── gateway/       # 外部系统适配（B.4）
│       │   ├── config/ properties/ constants/ enum/ exception/
│       │   └── internal/      # 禁止外部引用
│       └── resources/
│           ├── db/migration/{domain}/   # Flyway 迁移（号段制）
│           ├── mapper/        # 复杂 SQL XML（类名.xml，A.4.3-15）
│           └── lua/           # Redis 原子脚本（Redisson 预注册 EVALSHA，key 带 {业务功能} hash tag）
└── fuyun-app/                 # 装配模块（唯一可执行入口）+ 聚合测试
```

### C.4 常用命令（在 `backend/` 目录执行）

```bash
# 全量门禁（CI 与本地交付前必跑）：Spotless check → surefire 单测 → failsafe 集成测试（Testcontainers，需 Docker）→ JaCoCo check
mvn -B -ntp verify

# 快速单测反馈（本地开发循环；不触发集成测试与覆盖率门禁，禁止作为合并依据）
mvn -B test

# 指定模块门禁（含依赖模块；模块名 = fuyun-<域>）
mvn -B -pl fuyun-{domain} -am verify

# 调试单个集成测试类（-Dtest=NoSuchTest 使 surefire 空跑不报错）
mvn -B verify -Dit.test=OrderFlowIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false

# 格式修复（本地写入后 / 提交前）；spotless:check 只读校验已绑 verify，CI 零额外配置
mvn -B spotless:apply

# 依赖漏洞审计（需 NVD_API_KEY；CI security.yml 周审承载，本地按需）
mvn -B -ntp org.owasp:dependency-check-maven:13.0.0:check

# 版本漂移巡检（人工评估，不自动升级，对齐版本红线）
mvn -B -ntp versions:display-dependency-updates versions:display-plugin-updates
```

测试命名约定：单测 `*Test.java`（surefire）、集成测试 `*IT.java`（failsafe）；Testcontainers 编排基类用非拾取命名；不引入"跳过集成测试"的 profile（全局规范禁止人工绕过）。

### C.5 CI 生产落地方案（方案 B 严格门禁 · backend 侧）

1. **触发与门禁**：`.github/workflows/ci.yml` 中 backend job（`name: backend / verify`）在 `backend/**` 或 CI 配置变更时触发；步骤：checkout → setup-java@v6（Temurin 17，`cache: maven`）→ `mvn -B -ntp verify`；timeout 40 分钟；失败即阻断合入，main 分支保护 required checks 强制。
2. **覆盖率硬门槛**（Jacoco 门禁模式 a——每模块 check）：jacoco-maven-plugin **不在 Boot BOM 托管范围**，父 POM pluginManagement 显式锁定 0.8.15；prepare-agent + report（verify）+ check（verify）三 execution；规则 = BUNDLE LINE ≥ 0.80（非核心）+ 核心包 PACKAGE 级 rule（资金/交易/支付/状态机/认证权限/第三方回调，LINE ≥ 1.00，逐包声明）；排除 config/dto/entity/constants/Application/生成代码；聚合报告仅作只读总览（report-aggregate），不作为门禁口径（官方 issue #902）。
3. **格式硬门禁**：spotless-maven-plugin 3.4.0（锁定值）+ palantir-java-format（Java 17 红线筛选：google-java-format ≥1.22 需 JDK 21 被排除；Checkstyle 如引入须锁 12.x）+ importOrder + removeUnusedImports + endWithNewline；`spotless:check` 默认绑 verify，CI 零额外配置；配置全部在父 POM pluginManagement，子模块继承。
4. **集成测试**：Testcontainers 1.21.4，容器 tag 与 deploy compose 严格一致（timescale/timescaledb:2.29.2-pg16、redis:8.10.1、rabbitmq:4.3.5-management）；static @Container 类级共享；不启用 reuse（实验特性，破坏隔离）；GHA ubuntu-latest 预装 Docker 零配置；中间件镜像不缓存。
5. **依赖审计**：OWASP dependency-check-maven 13.0.0 在 `security.yml` 每周 schedule + 手动触发（不进 PR 关键路径：NVD 限流）；GitHub Secret `NVD_API_KEY` env 注入；`failBuildOnCVSS=9` 起步；失败按 TASK 登记流程处置。
6. **镜像**：images job（needs backend+frontend，`name: images`）push main 时以 sha + 分支-latest tag 推 GHCR；构建缓存 type=gha scope 按镜像隔离；Dockerfile 规范：多阶段（maven:3.9-eclipse-temurin-17 构建 → eclipse-temurin:17-jre 运行）、运行层必须装 curl（健康检查依赖）、非 root 运行、pom 拷贝先于源码（层缓存）、`.dockerignore` 必备、镜像内零密钥。

### C.6 永久环境约束

1. 本地开发前置：JDK 17（Temurin）、Maven 3.9.x、Docker Desktop（集成测试必需）；首次克隆后执行 `pre-commit install`（根目录）与 `pre-commit install-hooks`。
2. Java 17 工具红线（选型已规避，升级 JDK 前不得推翻）：google-java-format ≥1.22、Checkstyle 13.x+、Error Prone 当前版、SonarQube 扫描器（2026-07 起）均需 JDK 21——本项目一律不用或锁旧线。
3. 本地钩子分工：文件卫生钩子 pre-commit 即时执行；`backend-spotless-check`（local hook）为 pre-push 阶段重型校验；CI 为最终兜底（同命令）。
4. 环境变量注入经 `deploy/.env`（模板 `.env.example` 入库）；禁止在 IDE 运行配置中硬编码凭证后截屏外发。
