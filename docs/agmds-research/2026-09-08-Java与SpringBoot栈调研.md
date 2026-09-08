# Java 17 与 Spring Boot 3.5 栈规范调研报告

| 文档属性 | 内容 |
| --- | --- |
| 归属文档 | `backend/AGENTS.md` 槽位 A.1（编码约束）、A.2（配置管理）、A.3（API 设计）、A.7（跨层数据对象与传参）、Part B（架构分层）、C.2（数据访问层/ORM——本报告为其选型空白补调研，不替用户决策） |
| 调研时点 | 2026-09-08 |
| 性质 | 规范候选条目调研报告（只调研、不实现；ORM 一节给出对比专表与取舍建议，结论标注"待总 Spec/用户裁决"） |
| 上游冻结约束 | `docs/language/2026-09-07-技术栈选型.md` v1.1：Java 17（Temurin）/ Spring Boot 3.5.16 / Spring Framework 6.2.19（BOM 托管）/ Jackson 2.21.4（BOM）/ Springdoc OpenAPI 2.8.17（禁止升 3.x）/ Flyway 11.7.2（BOM）/ JUnit Jupiter 5.12.2（BOM）/ Mockito 5.17.0（BOM）/ PostgreSQL 16.15；版本禁止变更；总 Spec D4 模块化单体、不引入 Spring Cloud；Lombok 未在选型报告中出现，是否采用由本报告调研后给出候选 |
| 版本核实原则 | 全部版本号以 2026-09-08 检索的 Maven Central `maven-metadata.xml`、GitHub Releases/API、官方支持矩阵为准，禁止凭记忆填写；检索不到的登记于第 9 节 {待调研项} |

---

## 1. 调研范围与方法

1. 覆盖槽位：A.1 编码约束、A.2 配置管理、A.3 API 设计、A.7 跨层数据对象与传参、Part B 架构分层、ORM 选型候选（对应 backend/AGENTS.md C.2 槽位的前置调研）。
2. 事实来源分级：官方文档（docs.spring.io / docs.oracle.com / maven.apache.org）> 官方制品库与发布页（repo1.maven.org / GitHub Releases/API / jooq.org 支持矩阵）> 权威行业指南（refactoring.com / Zalando 指南）> 社区专家（Thorben Janssen / sip-of-java）。社区来源在表中显式标注。
3. 每个候选条目均给出：条目、依据（一句话）、来源 URL、适用版本、取舍建议。取舍建议是调研立场，不是决策；规范落稿时由 `backend/AGENTS.md` 定稿人逐条裁决。

---

## 2. 关键版本核实总表（2026-09-08）

| 组件 | 版本（2026-09-08 核实） | 来源 URL | 与本项目关系 |
| --- | --- | --- | --- |
| Spring Boot | 3.5.16（上游冻结） | https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom | 技术基座，BOM 唯一版本权威 |
| Spring Framework | 6.2.19（BOM `spring-framework.version`，当日直查 pom 复核） | 同上 | Web/事务/校验/事件习语均以此为准 |
| Hibernate ORM | 6.6.53.Final（BOM `hibernate.version`） | 同上 | 仅当 ORM 选 JPA 时生效 |
| Spring Data JPA | 3.5.13（BOM 导入 `spring-data-bom` 2025.0.13，当日直查该 BOM pom） | https://repo1.maven.org/maven2/org/springframework/data/spring-data-bom/2025.0.13/spring-data-bom-2025.0.13.pom | 仅当 ORM 选 JPA 时生效 |
| Jakarta Validation / Hibernate Validator | jakarta.validation-api 3.0.2 / Hibernate Validator 8.0.3.Final（均为 BOM 托管属性 `jakarta-validation.version` / `hibernate-validator.version`） | https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom | A.2 配置校验、A.3 参数校验的实现基座 |
| Lombok | 上游最新 1.18.48（2026-09-01 发布，新增 JDK27 支持）；Boot BOM 托管 1.18.46 | https://repo1.maven.org/maven2/org/projectlombok/lombok/maven-metadata.xml ；https://projectlombok.org/changelog | 未采用于选型报告；本报告给出候选（见 3.4） |
| MapStruct | 稳定版 1.6.3（2024-11-09）；1.7.0.Beta2（2026-06-27）为预发布 | https://github.com/mapstruct/mapstruct/releases ；https://repo1.maven.org/maven2/org/mapstruct/mapstruct/maven-metadata.xml | A.7 DTO 映射候选 |
| ArchUnit | 1.5.0（2026-08-04，支持至 Java 27） | https://api.github.com/repos/TNG/ArchUnit/releases ；https://www.archunit.org/userguide/html/000_Index.html | Part B 模块边界守护候选 |
| Spring Modulith | 1.4.13（Boot 3.5 世代，BOM 元数据核实）；2.1.1 为最新 GA 但属 Boot 4 世代；2.2.0-M1 为里程碑 | https://repo1.maven.org/maven2/org/springframework/modulith/spring-modulith-bom/maven-metadata.xml ；https://docs.spring.io/spring-modulith/reference/appendix.html | Part B 增量框架候选（是否引入待裁决） |
| mybatis-spring-boot-starter | 3.0.5（3.x 线最新，官方兼容 Spring Boot 3.2-3.5 / Java 17+ / MyBatis 3.5.x / MyBatis-Spring 3.0）；4.x 属 Boot 4 世代（最新 4.1.0） | https://github.com/mybatis/spring-boot-starter ；https://repo1.maven.org/maven2/org/mybatis/spring/boot/mybatis-spring-boot-starter/maven-metadata.xml | ORM 候选之一 |
| jOOQ | 上游最新 3.21.8；**Boot 3.5.16 BOM 托管 3.19.35**（`jooq.version`，当日直查 pom 核实） | https://repo1.maven.org/maven2/org/jooq/jooq/maven-metadata.xml ；https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom | ORM 候选之一；对 PG 16 的支持矩阵见 8.2 专项 |
| Spring AMQP | 3.2.12（BOM，上游报告已核实） | 本地文档 `docs/language/2026-09-07-技术栈选型.md` | Part B 事件边界（跨进程） |

---

## 3. 槽位 A.1：编码约束候选条目（Java 17 + Spring Boot 3.5 习语）

### 3.1 Java 17 语言特性使用约束

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| record 的适用场景：透明浅不可变数据载体——DTO、值对象、参数对象、多值返回、流式处理的局部中间结果（局部 record） | Oracle 官方定位 record 为"以更少样板建模纯数据聚合"的数据载体，字段隐式 final，自动派生 equals/hashCode/toString | https://docs.oracle.com/en/java/javase/17/language/records.html | Java 17 | **建议采纳**：能 record 则 record；record 兼具"免 Lombok 样板 + 不可变 + 值相等语义" |
| record 的禁用场景：需要继承多态的领域实体（record 隐式 final 不可继承）、可变状态载体、JPA 实体（record 不能作实体，但可作 JPQL/原生查询的 DTO 投影） | record "implicitly final"、禁实例字段；JPA 实体要求可变 POJO，社区明确"Records Cannot be Entities"但可作为查询投影 | https://docs.oracle.com/en/java/javase/17/language/records.html ；https://wkorando.github.io/sip-of-java/015.html | Java 17 | **建议采纳**（第 2 条为 ORM 选 JPA 时的硬约束） |
| sealed 用于封闭业务层级：有限状态、受限子类型集合（如异常分类、审批流节点），与 switch 表达式配合获得编译器穷尽检查 | Oracle 官方：sealed 以 `permits` 子句限制哪些类可继承/实现（JEP 409，Java 17 定稿） | https://docs.oracle.com/en/java/javase/17/language/sealed-classes-and-interfaces.html | Java 17 | **建议采纳**：错误分类/状态机优先 `sealed 接口 + record 实现`；禁止为"封装"而 sealed 无封闭语义的开放层级 |
| switch 表达式用于"值映射"型分支：错误码映射、状态转换、枚举分派；穷尽性由编译器强制（覆盖全部枚举常量或显式 default） | Oracle 官方：switch 表达式求值为单值、`case L ->` 消除 fall-through、`yield` 返回值、分支必须穷尽 | https://docs.oracle.com/en/java/javase/17/language/switch-expressions.html | Java 14+（17 内可用） | **建议采纳**；禁用于替代 if 表达长副作用流程 |
| text block 用于静态多行文本：SQL 模板样例、单测断言 JSON、报文示例；禁止拼接业务变量与敏感值 | Oracle 官方章节（JEP 378，Java 15 定稿）定位为文本块语言特性 | https://docs.oracle.com/en/java/javase/17/language/text-blocks.html | Java 15+ | **建议采纳（受限使用）**：仅静态文本；业务 SQL 仍以 ORM 机制承载 |

### 3.2 Optional 与 equals/hashCode

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| Optional 仅用作方法返回类型，表达"明确无结果"；Optional 类型变量本身永不为 null | JDK 17 javadoc API Note 原文："Optional is primarily intended for use as a method return type where there is a clear need to represent 'no result'... A variable whose type is Optional should never itself be null" | https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Optional.html | Java 17 | **建议采纳**：禁止 Optional 作字段、方法入参、集合元素、构造器参数 |
| 配置类中禁止使用 Optional 字段 | Spring Boot 官方明确"The use of Optional with @ConfigurationProperties is not recommended" | https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html | Boot 3.5 | **建议采纳** |
| 值对象/DTO 的 equals/hashCode 直接采用 record 语义（类型相同且组件值相等） | record 官方语义："two record classes are equal if they are of the same type and contain equal component values" | https://docs.oracle.com/en/java/javase/17/language/records.html | Java 17 | **建议采纳**：值对象不手写 equals/hashCode |
| ORM 实体的 equals/hashCode 专项约束（懒加载代理、自引用集合的语义冲突） | 属 ORM 相关专项，权威来源未在本次调研中逐条核验，登记于第 9 节 | {待调研项} | 待 ORM 定稿 | **暂缓落条目**：ORM 定稿后补充（选 MyBatis 则无此问题） |

### 3.3 异常层次设计（业务异常基类 + 错误码枚举）

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| 异常体系三层：`BaseException`（业务异常抽象基类，携带错误码枚举）→ 各模块业务异常（继承基类）→ 全局 `@RestControllerAdvice` 统一渲染 | Spring 官方：继承 `ResponseEntityExceptionHandler` 并声明为 `@ControllerAdvice`，可同时接管全部 Spring MVC 内置异常 | https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html | Spring Framework 6.x | **建议采纳**（官方机制组合，社区通行模式） |
| 业务异常实现 `ErrorResponse` 接口（或继承 `ErrorResponseException`），让异常对象自带"状态码 + 响应体映射" | Spring 官方："this allows exceptions to encapsulate and expose the details of how they map to an HTTP response. All Spring MVC exceptions implement this" | 同上 | Spring Framework 6.0+ | **建议采纳**：错误码枚举放异常对象，渲染集中一处 |
| 错误响应体采用 RFC 7807/9457 ProblemDetail，错误码放 `properties` 扩展字段（Jackson 自动平铺为顶层 JSON 字段） | Spring 官方支持 Problem Details 规范；`properties` Map 由 `ProblemDetailJacksonMixin` 平铺渲染 | 同上；规范原文 https://www.rfc-editor.org/rfc/rfc9457 | Spring Framework 6.0+ / RFC 9457 | **建议采纳**（与 A.3 候选 A 联动，见 5.1） |
| 国际化消息：ErrorResponse 的 type/title/detail 消息码经 `MessageSource` 解析 | Spring 官方："ResponseEntityExceptionHandler resolves these through a MessageSource" | 同上 | Spring Framework 6.x | 可选：本项目界面语言单一，暂按中文直出评估，落规范时裁决 |
| Boot 侧启用开关：`spring.mvc.problemdetails.enabled=true`（启用 Boot 自动 ProblemDetail 处理；自建 advice 顺序需在其前） | Spring 官方 Boot 自动配置说明 | 同上 | Boot 3.x | 随 ProblemDetail 路线一并采纳 |
| checked 业务异常一律不引入：业务异常统一继承 `RuntimeException` 体系，规避 @Transactional 默认不回滚 checked 的陷阱 | Spring 官方回滚默认规则（见 3.5 条目 4） | https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html | Spring Framework 6.2 | **建议采纳**（异常设计与事务语义互锁） |

### 3.4 Lombok 采用与否（选型报告未定项）

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| 采用结论：**建议有限采用**。Lombok 由 Boot BOM 官方托管（3.5.16 托管 1.18.46，版本配对经官方 BOM 验证），跟随 BOM 不自行覆盖；上游最新 1.18.48（2026-09-01，新增 JDK27 支持，与 Java 17 完全兼容） | Boot BOM `lombok.version=1.18.46`（当日直查 pom）；上游 changelog 2026-09-01 发布 1.18.48 | https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom ；https://projectlombok.org/changelog ；https://repo1.maven.org/maven2/org/projectlombok/lombok/maven-metadata.xml | 1.18.46（BOM）/ 1.18.48（上游最新） | **建议采纳（BOM 版本 1.18.46）**；升级走依赖升级提案流程，CI 全量编译把关 |
| 允许清单：`@Getter`/`@Setter`/`@RequiredArgsConstructor`（构造器注入减样板）/`@Slf4j`/`@Builder`（非 record 场景） | Lombok 定位为编译期样板消除；构造器注入规范见 3.5 | https://projectlombok.org/changelog | 1.18.46 | **建议采纳**；配合记录类策略"能 record 则 record"，两者不冲突 |
| 禁用清单：`@Data`（聚合了 @EqualsAndHashCode/@ToString 风险）用于 ORM 实体与含敏感字段对象；`@AllArgsConstructor`（开放全参构造破坏不变量）；已废弃的 `lombok.experimental.Wither`/`lombok.Delegate`（1.18.48 已移除，禁止残留） | 1.18.48 更新日志明确移除废弃注解；实体 equals/hashCode 风险与 ORM 相关，登记第 9 节 | https://projectlombok.org/changelog | 1.18.46+ | **建议采纳为约束**；ORM 专项来源待 ORM 定稿补 |
| record 与 Lombok 的分工：数据载体优先 record（零依赖、天然不可变）；Lombok 补足 record 覆盖不了的可变配置 bean、继承体系 | Oracle 对 record 的"透明数据载体"官方定位 | https://docs.oracle.com/en/java/javase/17/language/records.html | Java 17 | **建议采纳** |
| 与 MapStruct 共存：Lombok ≥ 1.18.16 时必须额外引入 `lombok-mapstruct-binding` 并注意注解处理器顺序（MapStruct 等 Lombok 改写完 AST 再生成） | MapStruct FAQ 原文："If you are using Lombok 1.18.16 or newer you also need to add lombok-mapstruct-binding" | https://mapstruct.org/faq/ | MapStruct ≥1.2 + Lombok ≥1.18.16 | **采纳（条件项）**：仅当两者同时引入时，binding 必须进 `annotationProcessorPaths` |

### 3.5 Spring 习语（注入 / 事务 / Bean 作用域）

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| 构造器注入强制，禁字段注入（@Autowired 字段） | Spring 官方："The Spring team generally advocates constructor injection, as it lets you implement application components as immutable objects and ensures that required dependencies are not null" | https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html | Spring Framework 6.2 | **建议采纳（强制级）**：字段注入无法保证 final 不可变与完全初始化，与官方立场相悖 |
| 必填依赖走构造器、可选依赖走 setter（setter 注入仅限有合理默认值的可选项） | Spring 官方："a good rule of thumb to use constructors for mandatory dependencies and setter methods or configuration methods for optional dependencies" | 同上 | Spring Framework 6.2 | **建议采纳** |
| 构造器参数过多视为坏味道（拆分类职责，或按 A.7 引入参数对象） | Spring 官方："a large number of constructor arguments is a bad code smell, implying that the class likely has too many responsibilities" | 同上 | Spring Framework 6.2 | **建议采纳**（CI 可加 Checkstyle/ArchUnit 辅助） |
| @Transactional 回滚语义：默认只回滚 RuntimeException/Error，checked 不回滚；业务方法按需显式 `rollbackFor` | Spring 官方："Any RuntimeException or Error triggers rollback, and any checked Exception does not."；`rollbackFor` 为"must cause rollback"的异常类型数组 | https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html | Spring Framework 6.2 | **建议采纳**：默认规则不熟即事故源；配合 3.3"业务异常全 RuntimeException"可少写 rollbackFor |
| 自调用失效：同类内方法互调不经过代理，被调方法的 @Transactional 不生效 | Spring 官方："self-invocation ... does not lead to an actual transaction at runtime even if the invoked method is marked with @Transactional"（代理模式下仅外部经代理调用被拦截） | 同上 | Spring Framework 6.2 | **建议采纳（强制级）**：事务方法必须经代理进入；确需自调用的走拆类或自注入代理并评审 |
| 事务边界归属 service 层；controller 禁加 @Transactional；事务粒度=业务用例（医嘱-计费-库存联动在同一事务内） | 事务与业务用例对齐是声明式事务设计惯例；配套 ArchUnit 分层规则机器化（见 7.4） | 同上；https://www.archunit.org/userguide/html/000_Index.html | Spring Framework 6.2 | **建议采纳**（工程约定 + CI 规则双保险） |
| 6.2 新能力备选：`@EnableTransactionManagement(rollbackOn=ALL_EXCEPTIONS)` 可全局改为全部异常回滚 | Spring 官方："As of 6.2, you can globally change the default rollback behavior" | 同上 | Spring Framework 6.2+ | 备选不启用：默认策略 + RuntimeException 体系已覆盖，避免全局语义漂移 |
| Bean 作用域：默认全部 singleton，服务类保持无状态（多实例部署前提，compose `--scale backend=2`）；prototype/request 等作用域引入需评审（scoped proxy 复杂度） | Spring 官方 Bean Scopes："singleton (Default) Scopes a single bean definition to a single object instance for each Spring IoC container" | https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html | Spring Framework 6.2 | **建议采纳** |
| 异步事件/@Async 的三个限制必须知悉：异常不上抛调用方、不能返回值级联发布事件、ThreadLocal 与日志上下文默认不传播（审计 traceId 链路需显式处理） | Spring 官方事件文档原文列出三条限制 | https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html | Spring Framework 6.2 | **建议采纳**：引入异步前先解决日志上下文传递，防审计断链（等保三级背景） |

### 3.6 编码硬约束（UTF-8 / LF / 禁裸输出）

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| UTF-8 无 BOM、LF 行尾 | 全局代码生成规范强制项；工程落地上由 `.gitattributes` + pre-commit 钩子（end-of-file-fixer、mixed-line-ending）机器保障 | https://git-scm.com/docs/gitattributes ；本仓库 `docs/agmds-research/2026-09-08-CI链方案调研.md`（方案 B 已规划） | git 全版本 | **采纳**（CI 链报告已覆盖，此处登记为编码约束的工程落点） |
| 禁裸 `System.out`/`printStackTrace`，统一 SLF4J 门面（Boot 默认 Logback 实现） | Spring Boot 官方："Spring Boot uses Commons Logging for all internal logging... By default, if you use the starters, Logback is used for logging"，且内置 SLF4J/JUL/Log4j 路由兼容 | https://docs.spring.io/spring-boot/3.5/reference/features/logging.html | Boot 3.5 | **建议采纳**；API 日志格式/内容（中文、业务标识、脱敏）遵循全局规范第二、三节，不在本报告重复 |
| 代码格式化与静态检查工具链（Spotless/Checkstyle/Error Prone 的版本与 Java 17 兼容性） | 已由 CI 链调研报告专项核实（spotless-maven-plugin 3.4.0 + palantir-java-format；Checkstyle 须锁 12.x 因 13.x+ 需 Java 21） | 本地文档 `docs/agmds-research/2026-09-08-CI链方案调研.md` | 见该报告 | **引用即可**，不重复调研 |

---

## 4. 槽位 A.2：配置管理候选条目（Spring Boot）

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| 配置文件分层：`application.yml` 只放全环境公共项与安全默认值；`application-{profile}.yml` 承载 dev/test/prod 差异；profile 专属文件始终覆盖非专属文件，多 profile 时后激活者胜（last-wins） | Spring Boot 官方："Profile-specific properties ... always overriding the non-specific ones. If several profiles are specified, a last-wins strategy applies" | https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html | Boot 3.5 | **建议采纳**：固定 dev/test/prod 三 profile；禁止 prod 专属文件携带明文密钥入库 |
| @ConfigurationProperties 类型安全绑定：配置一律建 `*Properties` 类；构造器绑定（支持 record/不可变风格，需 `-parameters` 编译参数）；启用用 `@ConfigurationPropertiesScan` | Spring Boot 官方："bound to structured objects through @ConfigurationProperties"；构造器绑定"the presence of a single parameterized constructor implies that constructor binding should be used" | 同上 | Boot 3.5 | **建议采纳**；官方不推荐配置类中用 Optional（见 3.2） |
| 禁止 @Value 散落使用 | Spring Boot 官方："Using the @Value ... annotation to inject configuration properties can sometimes be cumbersome, especially if you are working with multiple properties or your data is hierarchical in nature"，类型安全 bean 可"govern and validate"配置 | 同上 | Boot 3.5 | **建议采纳**：@Value 仅允许出现在启动装配类等个别场景（落规范时可全禁，从严） |
| spring-boot-configuration-processor 生成配置元数据：Maven 侧配置 compiler plugin（3.12.0+）的 `annotationProcessorPaths` 引入；IDE 获得属性补全与文档；内部类自动识别为嵌套属性；手工补充走 `additional-spring-configuration-metadata.json` | Spring Boot 官方："The jar includes a Java annotation processor which is invoked as your project is compiled"；"configure the compiler plugin (3.12.0 or later) to add spring-boot-configuration-processor to the annotation processor paths" | https://docs.spring.io/spring-boot/specification/configuration-metadata/annotation-processor.html | Boot 3.5 | **建议采纳**：处理器只进编译期 processor path，不进运行时 classpath |
| 配置校验：配置类加 `@Validated` + JSR-303 约束（@NotNull/@NotBlank 等），启动期即失败，防"跑起来才发现配置缺失" | Spring Boot 官方外部化配置校验章节（类型安全 bean "govern and validate" 配置）；实现为 BOM 托管的 Hibernate Validator 8.0.3.Final | https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html ；https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html | Boot 3.5 / Hibernate Validator 8.0.3.Final | **建议采纳**：必填项约束 + 启动失败，契合医疗系统"配置错误尽早暴露"诉求 |
| 敏感配置注入：一切凭据经环境变量占位 `${VAR}` 注入，明文密钥禁止入库（compose/代码/yml 全覆盖）；prod 用华为云 DEW/KMS 下发 | 上游技术栈选型报告 §6.5 已定红线："一切凭据经环境变量注入，禁止硬编码在 compose/代码/配置库中" | 本地文档 `docs/language/2026-09-07-技术栈选型.md` | — | **采纳（上游红线，非候选）**；Boot 侧仅要求 yml 中只写 `${VAR}` 占位 |
| 配置命名空间：业务配置统一 `fuyun.*` 前缀（与 compose 中 `FUYUN_*` 环境变量组一一对应， relaxed binding 自动映射） | 上游 compose 设计已用 `FUYUN_DATASOURCE_*`/`FUYUN_REDIS_*` 等变量组 | 本地文档 `docs/language/2026-09-07-技术栈选型.md` §6.6 | Boot 3.5 | **建议采纳**（工程约定；落实 spring-boot-configuration-processor 后有元数据支撑） |

---

## 5. 槽位 A.3：API 设计候选条目（REST + OpenAPI）

### 5.1 响应模式：统一包装 vs RFC 7807 ProblemDetail

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| 候选 A（错误响应）：RFC 7807/9457 ProblemDetail——Spring Framework 6 原生 `ProblemDetail`/`ErrorResponse`/`ErrorResponseException`/`ResponseEntityExceptionHandler` 四抽象 | Spring 官方："The Spring Framework supports the 'Problem Details for HTTP APIs' specification"（Spring 文档现引 RFC 9457，其更新替代 RFC 7807） | https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html ；https://www.rfc-editor.org/rfc/rfc9457 | Spring Framework 6.0+ | **候选 A（倾向推荐）**：标准化、机器可读、与框架异常体系原生打通；错误码经 `properties` 扩展携带 |
| 候选 B：统一 envelope（全响应包络 `{code,message,data}`，成功失败同构） | 国内社区广泛惯例，前端处理单一；但无任何权威规范背书，且与 HTTP 状态码语义重复、OpenAPI 描述冗余 | {待调研项：无权威来源，业界惯例} | — | **候选 B**：可保留但只包成功响应；与候选 A 组合时错误体仍走 ProblemDetail |
| 折中建议：成功响应=轻量 envelope 或裸数据（规范定稿时二选一）；**错误响应一律 ProblemDetail**（HTTP 状态语义化 + `properties.errorCode` 业务错误码 + `properties.traceId` 排查锚点） | `properties` Map 由 Jackson 自动平铺为顶层 JSON 字段，扩展无需自定义序列化器 | https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html | Spring Framework 6.x | **折中建议（推荐）**：兼顾前端约定简单与错误处理标准化 |
| 启用开关：`spring.mvc.problemdetails.enabled=true` 让 Boot 自动以 ProblemDetail 渲染内置 MVC 异常；自建 `@RestControllerAdvice` 顺序须在 Boot 默认（order 0）之前接管业务异常 | Spring 官方 Boot 自动配置说明 | 同上 | Boot 3.x | 随上述路线采纳 |

### 5.2 统一错误码设计

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| 错误码枚举：`<模块助记>-<4 位数字>`（如 `ORDR-1001`）集中定义于各模块 api 包的枚举；错误码经业务异常 → ProblemDetail.properties 输出；`type` 字段可放错误码 URI 便于文档跳转 | Spring 官方 ErrorResponse 允许异常"encapsulate and expose the details of how they map to an HTTP response"；properties 扩展官方支持 | https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html | Spring Framework 6.x | **建议采纳**（枚举机制官方，编码格式为项目约定，需全院唯一） |
| 错误码与 HTTP 状态码双层模型：HTTP 状态表达传输层语义（400/401/403/404/409/500），业务错误码表达业务失败原因，二者不混用 | RFC 9457 以 `status` 为 HTTP 状态、扩展字段承载补充信息的设计意图 | https://www.rfc-editor.org/rfc/rfc9457 | RFC 9457 | **建议采纳**（避免"全 200+错误码"反模式） |
| 校验失败统一渲染：`MethodArgumentNotValidException` 等经 `ResponseEntityExceptionHandler` 基类渲染为 ProblemDetail，字段错误进 `properties`/detail；禁止各 controller 各自兜底 | Spring 官方：继承该基类即接管全部 Spring MVC 异常与 `ErrorResponseException` | https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html | Spring Framework 6.x | **建议采纳** |

### 5.3 分页参数规范（ORM 未定，给中立建议）

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| 分页请求参数采纳 Spring Data 风格：`page`（0 起）、`size`、`sort=字段,方向`；**page 0 基必须在规范中显式写明**（前端易按 1 基误用） | Spring Data 官方：查询方法原生识别 `Pageable/Sort/Limit`；`PageRequest` javadoc 定义 "zero-based page index"；REST 层参数名沿用同一语义 | https://docs.spring.io/spring-data/commons/reference/repositories/query-methods-details.html | Spring Data 3.5.x（BOM） | **中立建议：采纳 page/size/sort**——与 Spring 全家桶（含 Springdoc、Spring Data REST 惯例）对齐最自然；offset/limit 仅是命名差异，经统一参数转换层即可适配任一 ORM，不影响 API 契约先行定稿 |
| 分页响应契约：`{content, page, size, total}`；count 开销过大时可降级 `{content, hasNext}`（Slice 语义），接口层不感知 ORM | Spring Data 官方："A Page knows about the total number of elements ... by the infrastructure triggering a count query... you can instead return a Slice"，官方已把 count 成本与降级路径写明 | 同上 | Spring Data 3.5.x | **建议采纳**：契约含 total 为默认，报表类大清单接口允许 hasNext 降级（落规范时逐接口标注） |

### 5.4 版本化与 URL 设计

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| URL 路径版本前缀 `/api/v1`：所有后端接口挂该前缀；大版本演进时并行 `/api/v2`，禁止无版本接口裸奔 | 行业通行实践（nginx 反代路由、Springdoc 分组、前端 baseURL 管理均最简单）；注意存在相反立场：Zalando 指南要求媒体类型版本化并禁止路径版本化（其 #845 议题正在讨论放宽该强制） | https://opensource.zalando.com/restful-api-guidelines/ ；https://github.com/zalando/restful-api-guidelines/issues/845 | — | **建议采纳 /api/v1**（与上游 compose 的 `/api` 反代设计天然衔接）；Zalando 相反立场记录在案，供规范定稿时知情裁决 |
| URL 风格：资源名复数、小写连字符、层级表从属（`/api/v1/patients/{id}/visits`）；动作型端点收敛为子资源 POST（如 `/api/v1/prescriptions/{id}/dispense`） | 业界 REST 风格共识（Zalando 指南同类规则可佐证资源命名方向） | https://opensource.zalando.com/restful-api-guidelines/ | — | **建议采纳**（工程约定级，落 backend/AGENTS.md 时逐条编号） |

### 5.5 Springdoc OpenAPI 注解与契约

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| 版本冻结：springdoc-openapi-starter-webmvc-ui 2.8.17，禁止升 3.x（3.x 仅兼容 Boot 4） | 上游选型报告已核实（springdoc 官方 FAQ：2.8.x 行兼容 Boot 3.5） | 本地文档 `docs/language/2026-09-07-技术栈选型.md`；https://springdoc.org/v2/ | 2.8.17 | **采纳（上游冻结项）** |
| 注解约定：Controller 用 `@Tag`（按业务模块分组）/`@Operation`（接口摘要与用途）；DTO 用 `@Schema`（字段业务含义）；OpenAPI 文档由代码注解推导生成，禁止绕开 DTO 手绘 schema 防止契约漂移 | springdoc 官方文档定位：从注解生成 OpenAPI 描述（annotation-based 文档生成是该版本主线能力） | https://springdoc.org/v2/ | 2.8.17 | **建议采纳**；契约=DTO 注解推导，评审 DTO 即评审契约 |
| Swagger UI 仅 dev/test 暴露，prod 关闭（`springdoc.api-docs.enabled=false` + 路由屏蔽） | 等保三级对信息暴露面的要求（上游 compose 已有"管理台仅 dev/test"同型先例） | 本地文档 `docs/language/2026-09-07-技术栈选型.md`（RabbitMQ 管理台先例） | 2.8.17 | **建议采纳**（安全工程约定） |

### 5.6 参数校验（Jakarta Validation）

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| 请求校验：请求 DTO 字段挂约束注解（@NotNull/@NotBlank/@Size/@Positive 等，jakarta.validation-api 3.0.2），Controller 参数加 `@Valid` 触发；MVC 内建支持无需 AOP | Spring 官方："Spring provides full support for the Bean Validation API"；"Spring MVC and WebFlux have built-in support for the same underlying method validation but without the need for AOP" | https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html | Spring Framework 6.2 / HV 8.0.3.Final（BOM） | **建议采纳（强制级）**：对外 API 入参必须声明式校验，禁止 service 层散落手写 if 判空 |
| Service 层方法校验（跨模块接口入参）：类上 `@Validated` + 参数约束注解 | Spring 官方："To be eligible for Spring-driven method validation, target classes need to be annotated with Spring's @Validated annotation" | 同上 | Spring Framework 6.2 | **建议采纳**：模块 api 接口的入参约束写在接口上，实现方免重复 |
| 校验失败统一走 5.2 的全局异常渲染（ProblemDetail + 字段错误明细），响应含 traceId | 与 mvc-ann-rest-exceptions 的 ResponseEntityExceptionHandler 机制衔接 | https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html | Spring Framework 6.x | **建议采纳** |

---

## 6. 槽位 A.7：跨层数据对象与传参候选条目

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| 参数对象化：公开方法（含模块 api 接口）形参 > 3 个必须参数对象化（record）；强相关参数对（起止日期等）即使 2 个也建议成对象 | Fowler 重构目录 Introduce Parameter Object：一组反复成对出现的参数应封装为对象（数据泥团解法） | https://refactoring.com/catalog/introduceParameterObject.html | Java 任意 | **建议采纳**；record 是参数对象的 Java 17 标准实现 |
| 禁止裸 `Map`/`List<String,Object>` 作为业务数据载体（跨方法/跨模块传参、请求响应体均禁） | 类型不安全、契约不可见、OpenAPI 无法生成、序列化歧义；无单一权威规范，属工程强约定 | {待调研项：工程约定，无权威出处} | — | **建议采纳（强制级）**：仅允许内部算法局部变量级使用 |
| 请求/响应 DTO 分离建模：`XxxCreateRequest`/`XxxUpdateRequest`/`XxxQuery`/`XxxResponse` 各自独立，禁止一 DTO 通吃增删改查 | JPA 专家 Thorben Janssen：写操作适配实体投影、读操作适配 DTO 投影，读写职责天然不同构 | https://thorben-janssen.com/entities-dtos-use-projection/ （社区专家来源） | — | **建议采纳** |
| DTO ≠ Entity ≠ 查询对象，职责隔离：Entity 服务持久化与事务写、DTO 服务 API 契约、查询对象（record）服务读投影 | 同上："Entity projections are great for all write operations ... DTO projections are the most efficient ones for read operations" | 同上 | — | **建议采纳** |
| 禁止 Entity 直接出 API 层（字段过曝、敏感字段泄漏、懒加载序列化风险）；禁止 Entity 进跨模块 api 包 | 同上 DTO/实体职责论述 + 等保三级敏感字段（身份证/病历）最小暴露原则 | 同上；本地文档 `docs/specs/00-master-spec.md`（等保背景） | — | **建议采纳（强制级）** |
| record 作查询投影：JPA 场景下 JPQL 构造器表达式/原生查询可直接映射 record（record 不能作 JPA 实体但可作投影） | sip-of-java："Records could be an excellent option for use a database projection. Records Cannot be Entities" | https://wkorando.github.io/sip-of-java/015.html | Java 17 | **建议采纳**（选 JPA 时生效） |
| DTO 映射工具候选 A：MapStruct 1.6.3（当前稳定版 2024-11-09；1.7.0 仍为 Beta 不采用）——编译期生成纯 Java 映射代码（无反射），支持 record 映射 | MapStruct GitHub Releases 版本史 + maven-metadata（latest=1.7.0.Beta2，稳定线 1.6.3） | https://github.com/mapstruct/mapstruct/releases ；https://repo1.maven.org/maven2/org/mapstruct/mapstruct/maven-metadata.xml ；https://mapstruct.org/faq/ | MapStruct 1.6.3 | **候选 A（倾向推荐）**：20 模块体量 Entity↔DTO 映射量大，编译期生成可审计；须与 lombok-mapstruct-binding 同置 processor path（见 3.4） |
| DTO 映射工具候选 B：手工映射（record 构造器/静态工厂方法） | 零依赖零魔法；金额、状态等关键字段手写更显式、编译器可查 | 工程常识（无权威出处） | — | **候选 B**：资金/医嘱状态等关键字段映射推荐手写 + 单测锁定 |
| 折中建议：引入 MapStruct 1.6.3 承担批量常规映射；金额（NUMERIC(18,2)→BigDecimal）、医嘱状态等关键业务字段的映射必须手写或单测全覆盖（全局规范核心功能 100% 覆盖率对齐） | 全局规范第四节核心功能界定（资金/状态机路径） | 本地文档（全局规范）+ MapStruct Releases | MapStruct 1.6.3 | **折中建议（推荐）** |

---

## 7. 槽位 Part B：架构分层候选条目（模块化单体）

### 7.1 Maven 多模块工程结构

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| 工程骨架：`backend/` 下 parent POM 聚合全部子模块；parent 不继承 `spring-boot-starter-parent`，改在 `dependencyManagement` 以 `import` scope 导入 `spring-boot-dependencies:3.5.16`（保留 parent 灵活性、版本仍零漂移） | Spring Boot 官方："The list is available as a standard Bills of Materials (spring-boot-dependencies) that can be used with both Maven and Gradle"；Maven 插件文档给出 import scope 配置样板 | https://docs.spring.io/spring-boot/3.5/reference/using/build-systems.html | Boot 3.5.16 / Maven 3.9.x | **建议采纳**（20 业务模块体量下自有 parent 是通行做法；compiler 插件 Java 17、annotationProcessorPaths 须自行配置） |
| BOM 优先：凡 BOM 托管依赖（Flyway/Jackson/JUnit/Mockito/Testcontainers/AMQP/Validation/jOOQ 等）禁止自行声明版本；BOM 未托管的（MyBatis starter、MapStruct、Lombok 若采用则随 BOM、ArchUnit）由 parent `dependencyManagement` 锁定 | 上游选型报告原则 5："凡被 Spring Boot BOM 托管的依赖，跟随 BOM 版本，禁止自行覆盖造成版本漂移" | 本地文档 `docs/language/2026-09-07-技术栈选型.md` | — | **采纳（上游原则）** |
| 模块划分按业务域（对齐总 Spec M01-M20 的 20 个业务模块），不按技术分层划分 | 总 Spec D4 模块化单体以业务边界为模块边界；技术分层模块会造成任何业务改动横跨全部模块 | 本地文档 `docs/specs/00-master-spec.md`（D4） | — | **建议采纳**：每个业务模块内部再分层（见 7.4），"外按业务域、内按分层" |
| 模块形态：`backend/<module>/`（单 jar 多模块）或 `backend/<module>-api` + `backend/<module>-impl` 成对；api 侧只放接口、DTO、事件契约、错误码枚举，实现全部在 impl/内部包 | Spring Modulith 的 provided/required interface 概念（模块对外仅暴露 API 包，internal 子包禁止外部引用）可平移为纯 Maven 约束 | https://docs.spring.io/spring-modulith/reference/fundamentals.html | — | **建议采纳**：20 模块全拆 api/impl 双模块会使模块数翻倍，建议初期单模块 + api 包约定，ArchUnit 强制；热点模块（医嘱/计费）可后续独立 api 模块 |
| 依赖方向：业务模块只依赖 common/基础模块与**其他模块的 api 包**；禁止依赖他人 impl；禁止循环依赖 | Maven 依赖机制 + ArchUnit `slices()...notDependOnEachOther()` 可机器化 | https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html ；https://www.archunit.org/userguide/html/000_Index.html | Maven 3.9.x / ArchUnit 1.5.0 | **建议采纳**（CI 规则兜底，见 7.2/7.4） |

### 7.2 模块边界守护：ArchUnit vs Spring Modulith（两条路线候选）

| 维度 | 路线一：ArchUnit（纯测试守护） | 路线二：Spring Modulith（框架级守护） |
| --- | --- | --- |
| 当前版本 | 1.5.0（2026-08-04；`archunit-junit5` 配套） | 1.4.13（Boot 3.5 世代）；2.x 属 Boot 4 世代 |
| 对 Boot 3.5 / JUnit 5.12 支持 | 与 Boot 解耦，任意 JUnit 项目可用；JUnit 5 经 TestEngine 透明集成（`@AnalyzeClasses`+`@ArchTest`，JUnit 5 的 @Disabled 需换 @ArchIgnore） | 官方兼容矩阵：Modulith 1.4 编译基线 Spring Boot 3.5、支持 3.1-3.5、对接 Spring Data 2023.2/2025.0（1.4 GA 博客同期升级 Boot 3.5/Framework 6.2/ArchUnit 1.4） |
| 边界表达力 | 手写规则：`layeredArchitecture()`（层访问方向）、`slices().matching("..<module>..").should().beFreeOfCycles()/notDependOnEachOther()`、`noClasses()...dependOnClassesThat()` | 声明式：包结构即模块、`@ApplicationModule(allowedDependencies)`、`@NamedInterface` 精确到命名接口；`ApplicationModules.of(App.class).verify()` 一行校验（底层即 ArchUnit） |
| 额外能力 | `FreezingArchRule` 冻结存量违规（遗留代码渐进治理） | PlantUML/C4 模块文档自动生成、按模块切片的集成测试、事件发布注册表（事务内落日志+失败重试）、事件外部化到 MQ |
| 引入成本 | 低：test 依赖 + 规则类，CI 失败即阻断 | 中：新概念学习 + BOM/starter 依赖 + 编码方式约束；Boot 4 迁移时需整体换 2.x 线 |
| 取舍建议 | **第一步必选**（成本最低、立刻可机器化总 Spec D4 的模块边界） | **增量候选**：是否引入属增量框架决策，待总 Spec/用户裁决；若引入必须锁 1.4.x（2.x 需 Boot 4，上游冻结不允许） |

来源：https://www.archunit.org/userguide/html/000_Index.html ；https://api.github.com/repos/TNG/ArchUnit/releases ；https://docs.spring.io/spring-modulith/reference/appendix.html ；https://docs.spring.io/spring-modulith/reference/fundamentals.html ；https://spring.io/blog/2025/05/28/spring-modulith-1-4-1-3-6-and-1-2-13-released ；https://spring.io/blog/2025/11/21/spring-modulith-2-0-ga-1-4-5-and-1-3-11-released ；https://repo1.maven.org/maven2/org/springframework/modulith/spring-modulith-bom/maven-metadata.xml

### 7.3 事件驱动边界：Spring 应用事件（进程内）vs RabbitMQ 领域事件（跨进程）

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| 选型界限总则：**同事务一致性诉求 → 直接方法调用或同步应用事件；最终一致/跨 bounded context/需重试与死信 → RabbitMQ**。进程内应用事件默认同步执行且运行在发布者事务上下文中 | Spring 官方："by default, event listeners receive events synchronously ... it operates inside the transaction context of the publisher if a transaction context is available" | https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html | Spring Framework 6.2 | **建议采纳为判定约定**：医嘱开立→计费/库存强联动（总 Spec 强事务背景）走同事务直调；通知/统计/推送类走事件或 MQ |
| 进程内模块解耦用 Spring 应用事件（任意 POJO 事件 + @EventListener；@TransactionalEventListener 控制提交前后时机） | Spring 官方事件机制即观察者模式，4.2 起支持任意对象作事件 | 同上 | Spring Framework 6.2 | **建议采纳**：事件对象定义在发布方 api 包（契约化） |
| 需"发布成功才投递 + 失败可重试"的进程内异步：Modulith `@ApplicationModuleListener`（等价 @Async + REQUIRES_NEW + @TransactionalEventListener 组合）+ Event Publication Registry（事件日志随原事务落库，失败留痕可重投） | Spring Modulith 官方："writes entries for each of them into an event publication log as part of the original business transaction... so that retry mechanisms can be deployed" | https://docs.spring.io/spring-modulith/reference/events.html | Spring Modulith 1.4.x（仅当引入 Modulith） | **增量候选**：不引入 Modulith 时，同级别可靠性需自行实现（事务后事件表 + 定时重投），成本自评估 |
| 跨进程领域事件走 RabbitMQ（总 Spec D1 唯一自建 MQ，quorum 队列）；Modulith 场景可用 `@Externalized` + `spring-modulith-events-amqp` 把进程内事件桥接到 AMQP | Spring Modulith 官方："Spring Modulith allows publishing selected events to a variety of message brokers"（AMQP artifact 经 Spring AMQP 对接任意兼容 broker） | https://docs.spring.io/spring-modulith/reference/events.html ；本地文档 `docs/language/2026-09-07-技术栈选型.md`（D1：RabbitMQ 4.3.5 + Spring AMQP 3.2.12） | Spring AMQP 3.2.12（BOM）/ Modulith 1.4.x | **建议采纳（跨进程一律 RabbitMQ）**；@Externalized 桥接仅在引入 Modulith 后作为实现手段评估 |

### 7.4 分层规范（controller / service / repository）

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| 包结构固定：模块内 `controller`/`service`/`repository`（ORM 定稿后可加 `mapper`/`persistence`）+ `api`（对外契约）+ `internal`（禁止外部引用） | Modulith 模块结构惯例：基础包为 API、子包为 internal，"Code within those must not be referred to from other modules" | https://docs.spring.io/spring-modulith/reference/fundamentals.html | — | **建议采纳** |
| controller 职责：参数校验（@Valid）+ DTO 转换 + 编排 service；禁业务逻辑、禁直接注入 repository | ArchUnit 官方分层规则示例即此拓扑：Controller 层不得被任何层访问、Persistence 仅被 Service 访问 | https://www.archunit.org/userguide/html/000_Index.html | ArchUnit 1.5.0 | **建议采纳**（规则机器化：`whereLayer("Controller").mayNotBeAccessedByAnyLayer()`） |
| service 职责：业务逻辑 + 事务边界（@Transactional）+ 经接口调用其他模块 api + 发应用事件；金额计算在此层服务端完成（总 Spec D5） | 事务边界归属与业务用例对齐（3.5 条目 6）；D5 金额服务端计算上游约束 | https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html ；本地文档总 Spec | — | **建议采纳** |
| repository 职责：纯持久化访问，禁业务判断、禁事务策略（事务归 service） | 同上 ArchUnit 分层拓扑（Persistence 仅被 Service 访问） | https://www.archunit.org/userguide/html/000_Index.html | ArchUnit 1.5.0 | **建议采纳** |
| 跨模块调用仅经接口：模块间只允许引用对方 api 包类型；ArchUnit `slices().matching("...fyun.(*)..").should().notDependOnEachOther()` 与包依赖规则双向兜底 | ArchUnit slices 官方示例：`matching("..myapp.(*)..")` 以捕获段为切片互斥检查 | https://www.archunit.org/userguide/html/000_Index.html | ArchUnit 1.5.0 | **建议采纳（CI 硬门禁）**，对齐总 Spec D4 |

---

## 8. ORM 选型对比（选型空白补调研；结论待总 Spec/用户裁决）

### 8.1 三候选对比专表

| 维度 | Spring Data JPA（Hibernate 6.6.53.Final） | MyBatis（mybatis-spring-boot-starter 3.0.5） | jOOQ（BOM 托管 3.19.35） |
| --- | --- | --- | --- |
| 版本与 Boot 3.5 协调 | BOM 原生：spring-data-bom 2025.0.13 → spring-data-jpa 3.5.13 + hibernate 6.6.53.Final，零版本漂移 | 非 BOM 托管，由 starter 自带版本；官方 README 兼容矩阵明确 3.0.x ↔ Spring Boot 3.2-3.5 / Java 17+ / MyBatis 3.5.x（4.x 属 Boot 4 世代，禁用） | **Boot BOM 托管 3.19.35**（`jooq.version`，当日直查 pom）；按 BOM 优先原则应锁 3.19.35，禁止自行升 3.21.8（见 8.2） |
| 强事务复杂业务表达力 | 强：实体关系图、脏检查、乐观锁、事务内一致性最省心；弱：复杂动态查询（Specification 臃肿）、批量写弱 | 中-强：事务交由 Spring @Transactional 管理；SQL 全手写、动态 SQL（`<if>/<foreach>`）灵活；无持久化上下文，写路径需自己保证一致性 | 强：类型安全 DSL 动态组合查询、嵌套集合（MULTISET）、事务 API 与 Spring 集成；无持久化上下文，写路径同 MyBatis 属显式 SQL |
| HIS 报表/复杂条件查询适配 | 弱-中：报表类最终大量回落 native SQL + DTO 投影，声明式价值被架空 | **强**：复杂多表统计、动态条件、分页报表即 MyBatis 主场；XML SQL 可独立评审/复用 | **强**：DSL 逼近原生 SQL 且可编译期检查；但复杂 SQL 的 DSL 写法学习曲线高 |
| 与 Flyway 协作（ddl-auto 必须 validate/none） | 需要纪律：Boot 官方默认非嵌入库即 `none`，且"If you are using a higher-level database migration tool, like Flyway or Liquibase, you should use them alone"；实体映射与迁移脚本漂移靠 `validate` 兜底 | 无 schema 参与，天然零冲突 | 代码生成读库 schema（jooq-codegen），建议流水线中于 Flyway 迁移后生成；无任何 schema 修改权，天然零冲突 |
| 类型安全 | 中：JPQL 为字符串（可元模型缓解），运行期才暴露错误 | 弱：XML/注解 SQL 无编译期检查，改列名运行期才炸 | **最强**：改列名/类型即编译失败 |
| 团队维护成本（国内医疗行业背景） | JPA 概念深（持久化上下文/N+1/懒加载），国内团队普遍经验少，排查成本高 | 国内 Java 团队普及度最高、学习成本最低（HIS 行业主流为 MyBatis 系——此为任务书背景陈述，未获权威统计，见第 9 节） | 概念与构建流程（代码生成）较新，团队需专门学习；国内使用面窄于 MyBatis |
| 许可与上游风险 | Apache 2.0，零风险；Hibernate 6.6 系随 BOM | Apache 2.0，零风险 | 双许可：jOOQ 3.2 起"Apache Software License 2.0 (for use with Open Source databases) and commercial"；PG 为开源库故 OSE 合法可用；风险见 8.2 |

### 8.2 jOOQ 与 PostgreSQL 16 的兼容性专项（决策关键事实）

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| 官方支持矩阵：PostgreSQL 行的 OSS 版最低版本——3.21（latest）→ **PG 18**；3.20 → **PG 17**；3.19 → **PG 15**；商业版也从未列出 PG 16 | jOOQ 官方支持矩阵原文（当日抓取）："jOOQ 3.22 (dev) / 3.21 (latest) [commercial] 9.3...15, 17, 18 [OSS minimum] 18；3.20 [OSS minimum] 17；3.19 [OSS minimum] 15" | https://www.jooq.org/download/support-matrix | jOOQ 3.19-3.21 | **关键事实**：上游最新 jOOQ OSS（3.21.8）官方不支持 PG 16，禁止自行升级到 3.20/3.21 |
| 社区同名疑问（官方以里程碑 3.21.0 关闭，支持矩阵至 2026-09 仍无 PG 16） | GitHub Issue #18328："the 3.19 minimum version is 15 and 3.20 is 17. So it looks like there is no jOOQ version that supports PostgreSQL 16" | https://github.com/jOOQ/jOOQ/issues/18328 | jOOQ 3.19-3.21 | 记录在案：PG 16 不在 jOOQ 官方声明支持列表 |
| 可行路线：**跟随 Boot 3.5.16 BOM 的 jOOQ 3.19.35**（OSE 最低 PG 15 → 覆盖项目 PG 16.15），符合上游"BOM 托管优先"原则 | Boot BOM `jooq.version=3.19.35`（当日直查 pom，同时托管 jooq-codegen/jooq-meta/jooq-codegen-maven） | https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom ；Boot 自带 jOOQ 自动配置见 https://docs.spring.io/spring-boot/3.5/reference/data/sql.html | jOOQ 3.19.35 / PG 16.15 | 若选 jOOQ：**必须锁 3.19.35**；遗留风险为 3.19 落后上游两个小版本（当前 3.21），旧小版本的 OSS 补丁策略需实施期确认——登记第 9 节 |

### 8.3 无论选谁都必须遵守的通用约束

| 条目 | 依据（一句话） | 来源 URL | 适用版本 | 取舍建议 |
| --- | --- | --- | --- | --- |
| Schema 唯一来源=Flyway：禁 `schema.sql`/`data.sql` 与 Flyway 混用；选 JPA 时 `spring.jpa.hibernate.ddl-auto` 生产固定 `validate`（或 `none`），**禁 `update`/`create`/`create-drop`**（Boot 对非嵌入库默认即 none） | Boot 官方："you should use them alone to create and initialize the schema. Using the basic schema.sql and data.sql scripts alongside Flyway or Liquibase is not recommended and support will be removed"；非嵌入库默认 none | https://docs.spring.io/spring-boot/how-to/data-initialization.html | Boot 3.5 / Flyway 11.7.2（BOM） | **建议采纳（强制级，与上游迁移策略零冲突）** |
| SQL 日志规范：dev/test 开 ORM SQL 日志（JPA：`org.hibernate.SQL` DEBUG；MyBatis：mapper 包 DEBUG；jOOQ：`ExecutingListeners`/pretty printing）便于对标 Flyway 迁移；**生产禁全量 SQL 打印**（性能 + 参数含 PHI 的泄露风险），SQL 参数日志必须脱敏 | Boot 文档提及 debug 模式输出 Hibernate SQL（`org.hibernate.SQL` logger）；生产脱敏为等保三级与全局日志规范要求 | https://docs.spring.io/spring-boot/how-to/data-initialization.html ；https://docs.spring.io/spring-boot/3.5/reference/features/logging.html | Boot 3.5 | **建议采纳**；各 ORM 具体日志开关落规范时按选定 ORM 补细节（MyBatis log-impl 细节见第 9 节待办） |
| 金额映射：NUMERIC(18,2) ↔ `BigDecimal` 全链路（Entity/DTO/计算），禁 `double`/`float`/`Float`/`Double`；比较用 `compareTo` | 总 Spec D5 金额服务端计算 + NUMERIC(18,2) 上游约束；浮点二进制误差不可用于资金 | 本地文档 `docs/specs/00-master-spec.md`（D5）+ `docs/language/2026-09-07-技术栈选型.md` | — | **建议采纳（强制级，ORM 无关）** |
| 审计字段（创建人/创建时间/更新人/更新时间）与审计日志留存 ≥6 个月为全局横切约束，ORM 层以拦截器/审计框架实现，具体形态随 ORM 定稿细化 | 上游等保三级背景 + 总 Spec 审计要求 | 本地文档 `docs/specs/00-master-spec.md` | — | **登记为 ORM 定稿后的细化项** |

### 8.4 取舍建议（调研立场，待总 Spec/用户裁决）

| 顺位 | 候选 | 建议要点 |
| --- | --- | --- |
| 1（主选建议） | MyBatis（mybatis-spring-boot-starter 3.0.5，锁 3.0.x 线禁升 4.x） | HIS 大量报表/复杂条件查询 SQL 主导、国内团队维护成本最低、与 Flyway 零冲突、starter 官方明确兼容 Boot 3.2-3.5；代价是类型安全弱（以 mapper 单测 + Flyway 对标 + CI 规则补偿） |
| 2（可行备选） | Spring Data JPA（Hibernate 6.6.53.Final + spring-data-jpa 3.5.13，全 BOM） | 版本协调最省心、事务写路径成熟；但 HIS 报表场景大量回落 native SQL，且团队学习/排查成本最高；选它则必须执行 8.3 的 ddl-auto 纪律 |
| 3（受限备选） | jOOQ（BOM 托管 3.19.35，禁升 3.20/3.21） | 类型安全最强、适合复杂查询；但被 PG 16 支持矩阵锁死在 3.19 旧线（落后上游两版 + 旧线补丁策略待确认），且团队学习成本高——仅当团队明确偏好类型安全 DSL 时考虑 |
| 不建议 | 混合双栈（JPA 管 CRUD + MyBatis 管查询） | 双套映射与事务语义维护成本高、审计口径不统一；如总 Spec 修订想保留该选项，应显式裁决并限定到个别模块 |

**决策标注：以上顺位为调研建议，ORM 最终选型待总 Spec/用户裁决；裁决前任何 ORM 依赖不得进入 backend/ 父 POM。**

---

## 9. {待调研项}清单

| 编号 | 事项 | 原因与建议 |
| --- | --- | --- |
| T-1 | 国内 HIS 行业 ORM 市占/主流度的权威统计（"国内 HIS 主流 MyBatis 系"） | 检索仅获教程类内容与社区共识，无权威统计来源；该说法作为任务书背景陈述保留，本报告不将其计为调研证据 |
| T-2 | ORM 实体 equals/hashCode 与 Lombok `@Data` 冲突的权威来源条目 | 已定位到社区专家站（thorben-janssen.com）但未逐篇核验具体 URL；ORM 定稿后按所选 ORM 补充 A.1 条目 |
| T-3 | 统一 envelope 响应模式（候选 B）的权威出处 | 业界惯例无单一权威规范；若规范定稿采用 envelope，作为项目约定落稿并注明无规范依据 |
| T-4 | Springdoc 2.8.17 ↔ Boot 3.5 官方 FAQ 原文的当日语句复核 | 结论沿用 2026-09-07 技术栈选型报告已核实版本（2.8.x 兼容 Boot 3.5、3.x 仅 Boot 4），本次未重复核实原文页面 |
| T-5 | MyBatis SQL 日志（`log-impl`）官方文档细节 | 依赖 ORM 裁决结果；选 MyBatis 后补 8.3 对应条目的具体配置项与来源 |
| T-6 | jOOQ 3.19 旧小版本的 OSS 补丁维护策略（上游是否仅为最新小版本发 OSS 补丁） | 若 ORM 裁决选 jOOQ，需在实施前核实该策略以评估 3.19.35 的长期安全补丁风险 |

---

## 10. 自审记录

- [x] **槽位覆盖**：A.1（3.1-3.6 六小节）、A.2、A.3（5.1-5.6 五小节）、A.7、Part B（7.1-7.4 四小节）、ORM（8.1-8.4）逐节候选条目表覆盖；无空槽。
- [x] **版本当日核实**：Lombok 1.18.48/1.18.46、MapStruct 1.6.3（1.7.0.Beta2 预发布）、ArchUnit 1.5.0、Spring Modulith 1.4.13/2.1.1、mybatis-spring-boot-starter 3.0.5（4.1.0 属 Boot 4）、jOOQ 3.21.8（BOM 托管 3.19.35）、Hibernate 6.6.53.Final、Spring Data JPA 3.5.13、Hibernate Validator 8.0.3.Final、jakarta.validation-api 3.0.2——全部经 Maven Central metadata / GitHub Releases-API / 官方 pom 直查（2026-09-08），无凭记忆填写的版本。
- [x] **URL 真实性**：全部引用 URL 经本次 WebFetch/web-reader 实际抓取验证可访问（含 jooq.org 支持矩阵、Boot BOM pom、Modulith appendix、ArchUnit 用户指南、Oracle Java 17 四个语言特性页、JDK17 Optional javadoc、refactoring.com 条目页）；仅两类例外——本地文档交叉引用（技术栈选型报告、总 Spec、CI 链报告）与上游报告已核实的 springdoc FAQ（登记 T-4）。
- [x] **决策边界**：Lombok 给出"有限采用"候选而非假定采用；Spring Modulith 标注"增量框架决策待裁决"；ORM 三候选对比 + 明确顺位建议 + 全文多处"待总 Spec/用户裁决"标注；未替用户做任何定稿决策。
- [x] **编码规范**：本文件 UTF-8 无 BOM、LF 行尾、全中文说明、文件末尾单换行（写入后经字节级检查）。
