# PR-1 工程骨架任务简报（TASK.md W-3）

| 简报属性 | 内容 |
| --- | --- |
| 简报编号 | BRIEF-PR1-01 |
| 日期 | 2026-09-09 |
| 执行者 | 实现专员（本简报为唯一需求来源，spec 口径以 `docs/plans/2026-09-08-P0实施计划.md` §1-PR-1 为准） |
| 上游依据 | P0 实施计划 PLAN-P0-01 §0/§1；根 `AGENTS.md` §7；`backend/AGENTS.md`（下称 backend 宪法）；`web/AGENTS.md`（下称 web 宪法）；`docs/language/2026-09-07-技术栈选型.md`（下称定稿报告）v1.1；`TASK.md` W-3 |
| 交付批次 | B1.1 后端骨架 → B1.2 冒烟 IT + backend/Dockerfile 重写 → B1.3 deploy/ 全量编排 + ci.yml 修正 → B1.4 web monorepo |
| 完成定义 | 见本简报 §6（各批次验收命令与 PR-1 收尾标准） |

---

## 0. 执行前提（开工前必做）

1. 通读根 `AGENTS.md` → `backend/AGENTS.md` → `web/AGENTS.md`（写代码前必读宪法，根定位层 §5 强制路由）。
2. **先记再改**：开工前在 `CHANGELOG.md` 登记本次变更条目（根定位层 §7 配套文件职责）；本次改动不修宪法正文，表外版本走 §9 待裁决流程（PR 描述申报）。
3. **分支与流程红线**（P0 计划 §0-3）：main 分支保护已生效，一切变更走 feature 分支 → `gh pr create` → 五项检查（`backend / verify`、`frontend / verify`、`images`、`commitlint`、`hygiene`）全绿 → `gh pr merge`；提交信息 conventional commits。
4. 本地环境（已确认）：`JAVA_HOME=D:\code\java\jdk\jdk17`（Temurin 17）、Maven 3.9.16、Docker Desktop 29.7.2（集成测试与 compose 必需，保持运行）、Node 24.19、pnpm 12.3.4（corepack 已启用）。
5. 所有新建文件：UTF-8 无 BOM、LF 行尾、文件末尾一个换行（根定位层 §7 编码红线；`scripts/check-encoding.py` 与 CI hygiene job 会拦）；注释/日志/文档全中文，标识符英文。

---

## 1. 20 个业务模块清单（Maven 模块名与 specs 严格对齐）

模块英文名、包根、schema 名逐个取自 `docs/specs/modules/01~20-*.md` 文档头「Maven 模块」字段（README §3 约定：schema 名 = Maven 模块名）。**禁止任何改名与缩写**。

| M 编号 | 模块中文名（spec 标题） | Maven 模块（artifactId） | 包根（Java package） | Flyway 迁移目录（resources 下） |
| --- | --- | --- | --- | --- |
| M01 | 系统与权限管理 | `fuyun-system` | `com.fuyun.system` | `db/migration/system/` |
| M02 | 患者主索引与档案（EMPI） | `fuyun-patient` | `com.fuyun.patient` | `db/migration/patient/` |
| M03 | 门诊服务 | `fuyun-outpatient` | `com.fuyun.outpatient` | `db/migration/outpatient/` |
| M04 | 住院管理 | `fuyun-inpatient` | `com.fuyun.inpatient` | `db/migration/inpatient/` |
| M05 | 护理管理（含移动护理） | `fuyun-nursing` | `com.fuyun.nursing` | `db/migration/nursing/` |
| M06 | 药事管理 | `fuyun-pharmacy` | `com.fuyun.pharmacy` | `db/migration/pharmacy/` |
| M07 | 检验管理（LIS） | `fuyun-lab` | `com.fuyun.lab` | `db/migration/lab/` |
| M08 | 检查与影像（RIS/PACS） | `fuyun-imaging` | `com.fuyun.imaging` | `db/migration/imaging/` |
| M09 | 电子病历与病案（EMR） | `fuyun-emr` | `com.fuyun.emr` | `db/migration/emr/` |
| M10 | 手术麻醉 | `fuyun-surgery` | `com.fuyun.surgery` | `db/migration/surgery/` |
| M11 | 重症监护（ICU） | `fuyun-icu` | `com.fuyun.icu` | `db/migration/icu/` |
| M12 | 输血管理 | `fuyun-transfusion` | `com.fuyun.transfusion` | `db/migration/transfusion/` |
| M13 | 收费物价与医保结算 | `fuyun-billing` | `com.fuyun.billing` | `db/migration/billing/` |
| M14 | 医疗设备物联网平台 | `fuyun-iot` | `com.fuyun.iot` | `db/migration/iot/` |
| M15 | 设备与资产管理 | `fuyun-asset` | `com.fuyun.asset` | `db/migration/asset/` |
| M16 | 智慧病房应用 | `fuyun-ward` | `com.fuyun.ward` | `db/migration/ward/` |
| M17 | 体检管理（PEIS） | `fuyun-peis` | `com.fuyun.peis` | `db/migration/peis/` |
| M18 | 互联网医院 | `fuyun-internet` | `com.fuyun.internet` | `db/migration/internet/` |
| M19 | 运营与决策支持 | `fuyun-ops` | `com.fuyun.ops` | `db/migration/ops/` |
| M20 | 集成平台与数据服务 | `fuyun-integration` | `com.fuyun.integration` | `db/migration/integration/` |

子模块合计 22 个 = `fuyun-common` + `fuyun-app` + 上表 20 个业务模块。

---

## 2. B1.1 后端骨架逐文件规格

### 2.1 `backend/pom.xml`（父 POM）

坐标：`com.fuyun:fuyun-backend:0.1.0-SNAPSHOT`，`packaging=pom`。**不继承 spring-boot-starter-parent**，`dependencyManagement` 以 import scope 导入 `spring-boot-dependencies:3.5.16`（backend B.1 / P0 计划 §1-PR-1）。

#### （1）properties 清单（每个值与来源必须一致）

| property 名 | 值 | 来源与用途 |
| --- | --- | --- |
| `project.build.sourceEncoding` / `project.reporting.outputEncoding` | `UTF-8` | 编码红线 |
| `maven.compiler.release` | `17` | Java 17 基线（backend C.2） |
| `maven.compiler.parameters` | `true` | 对齐 spring-boot-starter-parent 默认行为（不继承 parent 须自行补，`@RequestParam`/`@ConfigurationProperties` 构造器绑定依赖参数名） |
| `spring-boot.version` | `3.5.16` | BOM import 版本；同时供 `spring-boot-configuration-processor`、`spring-boot-maven-plugin` 版本引用（import scope 不继承 BOM properties，必须显式声明） |
| `lombok.version` | `1.18.46` | backend C.2（BOM 托管值，但 import 不带属性，须显式） |
| `mapstruct.version` | `1.6.3` | backend C.2 |
| `lombok-mapstruct-binding.version` | `0.2.0` | 表外（MapStruct 官方文档标准搭配值），见 §9 待裁决 |
| `mybatis-plus.version` | `3.5.17` | backend C.2（starter 与 jsqlparser 同版本） |
| `qpid-jms.version` | `2.11.0` | backend C.2（M14 骨架用） |
| `shedlock.version` | `6.10.0` | backend C.2 |
| `archunit.version` | `1.5.0` | backend C.2（test） |
| `jacoco.version` | `0.8.15` | backend C.5-2（**不在 Boot BOM 托管范围，必须显式**） |
| `spotless.version` | `3.4.0` | backend C.5-3（锁定值） |
| `maven-compiler-plugin.version` / `maven-surefire-plugin.version` / `maven-failsafe-plugin.version` / `spring-boot-maven-plugin` 用 `${spring-boot.version}` | 见 §9 待裁决 | 宪法未锁 Maven 核心插件版本；落地时取 `spring-boot-starter-parent:3.5.16` 的 pluginManagement 原值锁定（Maven Central 直读该 POM），保持 Boot 工具链一致并申报 |

#### （2）dependencyManagement（顺序敏感）

1. 第一条：`org.springframework.boot:spring-boot-dependencies:3.5.16`，type=pom，scope=import。
2. 其后集中声明 BOM 外版本（版本全部引用上述 properties）：
   - `com.baomidou:mybatis-plus-spring-boot3-starter`（backend A.4.3-12：必须 spring-boot3 后缀）
   - `com.baomidou:mybatis-plus-jsqlparser`
   - `org.apache.qpid:qpid-jms-client`
   - `net.javacrumbs.shedlock:shedlock-spring`、`net.javacrumbs.shedlock:shedlock-provider-jdbc-template`
   - `com.tngtech.archunit:archunit-junit5`
   - `org.mapstruct:mapstruct`
   - **禁止**引入表内未列依赖；禁止再引入 `mybatis`/`mybatis-spring`/`mybatis-spring-boot-starter`（backend A.4.3-12 互斥红线）；禁止引入 Spring Modulith（backend B.2-6，D-2 未裁决）。

#### （3）pluginManagement 完整配置要求

**maven-compiler-plugin**：
- `<release>17</release>`、`<parameters>true</parameters>`。
- `annotationProcessorPaths` 按固定顺序（顺序影响注解处理可见性，不得调换）：
  1. `org.projectlombok:lombok`（`${lombok.version}`）
  2. `org.mapstruct:mapstruct-processor`（`${mapstruct.version}`）
  3. `org.projectlombok:lombok-mapstruct-binding`（`${lombok-mapstruct-binding.version}`，使 Lombok 生成的 getter/setter 对 MapStruct 可见）
  4. `org.springframework.boot:spring-boot-configuration-processor`（`${spring-boot.version}`，生成配置元数据，backend A.2-3）

**spotless-maven-plugin 3.4.0**（backend C.5-3，Java 全文件格式门禁）：
- `<java>` 配置：`<palantirJavaFormat/>`（**不显式 pin 版本号**——内置默认版本属 TASK.md T-R5-2 待调研项，本地首跑 `spotless:check` 确认后再议，禁自行引入 google-java-format，backend C.6-2 JDK17 红线）、`<importOrder/>`、`<removeUnusedImports/>`、`<endWithNewline/>`。
- executions：`check` goal 绑 verify（execution id `spotless-check`），CI 零额外配置。

**jacoco-maven-plugin 0.8.15**（backend C.5-2，三 execution + 规则）：
- `prepare-agent`（默认 phase）——不覆盖 `argLine`，surefire/failsafe 均不得自定义 argLine（否则吞掉 agent 注入）。
- `report` 绑 verify、`check` 绑 verify。**插件在 `<build><plugins>` 中的声明顺序必须使 jacoco 排在 failsafe 之后**（同一 verify 阶段按声明顺序执行，保证报告与门禁含 IT 覆盖数据；spotless 的 check 执行放在最前实现格式快速失败）。
- `check` 规则：
  - 规则一（全部模块生效）：element=`BUNDLE`，limit：counter=`LINE`，value=`COVEREDRATIO`，minimum=`0.80`。
  - 规则二（核心包占位，backend C.5-2「核心包 PACKAGE 级 rule 随模块实装逐步声明，P0 至少 billing/system 占位」）：element=`PACKAGE`，`<includes>` 写 `com.fuyun.billing.service.impl` 与 `com.fuyun.system.service.impl`，limit：counter=`LINE`，value=`COVEREDRATIO`，minimum=`1.00`。**此时两包无任何类，规则匹配零包 → 平凡通过，jacoco 不会因无代码失败；一旦这两个包出现第一个类，1.00 门禁立即生效**——这正是占位语义，禁止改用「先注释、实装时再放开」的做法。无测试类导致的 execution data 缺失时 jacoco 以 warning 跳过，同样不阻塞空模块。
- 插件级 `<excludes>`（依据 backend C.5-2「排除 config/dto/entity/Application/生成代码」）：`com/fuyun/**/config/**`、`com/fuyun/**/properties/**`、`com/fuyun/**/dto/**`、`com/fuyun/**/entity/**`、`**/FuyunApplication.class`、`**/*ConverterImpl.class`（MapStruct 生成物，规则现在写全，避免实装时遗漏）。

**maven-surefire-plugin**：`<includes><include>**/*Test.java</include></includes>`（单测命名约定，backend C.4）。禁止自定义 argLine。

**maven-failsafe-plugin**：`<includes><include>**/*IT.java</include></includes>`（集成测试命名约定）。executions：`integration-test` + `verify` 两 goal。**在父 POM `<build><plugins>` 显式声明以继承 executions**（failsafe 非默认生命周期绑定）。

**插件启用方式总结**：版本与配置全部进 `pluginManagement`；`failsafe`、`jacoco`、`spotless` 三个非默认绑定插件必须在父 POM `<build><plugins>` 显式声明（子模块零配置继承）；`compiler`/`surefire` 为默认生命周期绑定，pluginManagement 即可生效。启用顺序：spotless → failsafe → jacoco。

#### （4）modules 清单（22 个）

`fuyun-common`、`fuyun-system`、`fuyun-patient`、`fuyun-outpatient`、`fuyun-inpatient`、`fuyun-nursing`、`fuyun-pharmacy`、`fuyun-lab`、`fuyun-imaging`、`fuyun-emr`、`fuyun-surgery`、`fuyun-icu`、`fuyun-transfusion`、`fuyun-billing`、`fuyun-iot`、`fuyun-asset`、`fuyun-ward`、`fuyun-peis`、`fuyun-internet`、`fuyun-ops`、`fuyun-integration`、`fuyun-app`（reactor 实际构建顺序由 Maven 依赖分析决定，列表按 common → 业务域 → app 排列即可）。

### 2.2 `backend/fuyun-common`（公共模块）

pom：`parent` 指向 `com.fuyun:fuyun-backend:0.1.0-SNAPSHOT`，artifactId `fuyun-common`，packaging jar。依赖集保持最小：`org.springframework.boot:spring-boot-starter-web`（提供 ProblemDetail/OncePerRequestFilter/@RestControllerAdvice 基类）；`org.springframework.boot:spring-boot-starter-test`（test）。**不依赖任何业务模块**（backend B.1）。

代码清单（四个生产类 + 四个测试类，禁止再多）：

| 类（包路径） | 职责边界 |
| --- | --- |
| `com.fuyun.common.exception.ErrorCode` | 错误码接口：`String getCode()`。约定格式 `<模块助记>-<4 位数字>`（backend A.3-4）；实现类是各业务模块 api/ 包下的枚举，本模块只定契约 |
| `com.fuyun.common.exception.BizException` | 业务异常基座，继承 `RuntimeException`（backend A.1-6，禁 checked 业务异常）。字段：`ErrorCode errorCode` + `HttpStatus httpStatus` + message。构造器：`(ErrorCode, HttpStatus, String message)` 全参、`(ErrorCode, HttpStatus)`（message 默认取 `errorCode.getCode()`）。携带错误码与 HTTP 状态供全局渲染 |
| `com.fuyun.common.web.GlobalExceptionHandler` | `@RestControllerAdvice`，**继承 `ResponseEntityExceptionHandler`**（backend A.3-4）。`@ExceptionHandler(BizException.class)`：按异常携带的 HttpStatus 渲染 RFC 9457 `ProblemDetail`，`properties.errorCode` 放错误码、`properties.traceId` 放 MDC 当前 traceId；`@ExceptionHandler(Exception.class)` 兜底 500（detail 用通用文案，不泄漏内部信息；error 级日志记录堆栈，日志中文、不含敏感值）。`spring.mvc.problemdetails.enabled=true` 使标准 MVC 异常同样走 ProblemDetail，由继承的父类自动处理 |
| `com.fuyun.common.context.TraceIdFilter` | `OncePerRequestFilter`。请求进入：读请求头 `X-Trace-Id`（前端拦截器注入，web 宪法 A.3-2），缺失则生成 UUID；写入 MDC（key 由构造器参数传入，默认 `traceId`）；响应头回写 `X-Trace-Id`（由构造器参数开关控制）；`finally` 中清理 MDC（防线程池串号）。**无 @Component**，由 fuyun-app 配置类经 `FilterRegistrationBean` 注册（`Ordered.HIGHEST_PRECEDENCE`），保证全链路最先生效 |
| `com.fuyun.common.context.OperatorContextHolder` | 审计支撑最小类：`ThreadLocal<String>` 持有当前操作人标识，`set/get/clear` 三方法 + 私有构造器。注释写明业务定位：供 M01 审计切面（PR-3）注入操作人（backend A.4.2-9「操作人由应用层统一注入」）；**@Async 线程不传播，异步逻辑须显式传递**（backend A.1-10） |

单元测试（与实现同提交；本模块是 **JaCoCo BUNDLE LINE ≥ 0.80 首个真实生效模块**，测试必须撑起阈值，全局规范：核心 API 服务接口属核心功能须 100%）：

- `BizExceptionTest`：全参与便捷构造器分别携带正确 errorCode/httpStatus/message；便捷构造 message 等于错误码。
- `GlobalExceptionHandlerTest`：直接调用 handler 方法断言——BizException 渲染出的 ProblemDetail 状态码与异常一致、`properties.errorCode`/`properties.traceId`（预先 MDC.put 造锚点）正确；兜底 Exception 返回 500 且 detail 为通用文案。
- `TraceIdFilterTest`（`MockHttpServletRequest/Response`）：无请求头时生成 traceId 且响应头回写；有 `X-Trace-Id` 时透传原值；请求结束后 MDC 中 traceId 被清理；开关关闭时不回写响应头。
- `OperatorContextHolderTest`：set 后 get 返回同值；clear 后 get 为 null（验证无残留，防线程复用泄漏）。

### 2.3 `backend/fuyun-app`（装配模块）

pom：parent 指父 POM，artifactId `fuyun-app`，packaging jar。**P0 依赖集逐项**（P0 计划 §1-PR-1；版本来源见 §2.1）：

| 依赖 | scope | 版本来源 |
| --- | --- | --- |
| `spring-boot-starter-web` | compile | BOM |
| `spring-boot-starter-validation` | compile | BOM |
| `spring-boot-starter-aop` | compile | BOM |
| `spring-boot-starter-actuator` | compile | BOM（health probes 显式开启，见 yml） |
| `spring-boot-starter-data-redis` | compile | BOM（Lettuce 默认，backend A.5-1） |
| `spring-boot-starter-amqp` | compile | BOM |
| `com.baomidou:mybatis-plus-spring-boot3-starter` | compile | `${mybatis-plus.version}`（backend A.4.3-12） |
| `com.baomidou:mybatis-plus-jsqlparser` | compile | `${mybatis-plus.version}` |
| `org.flywaydb:flyway-core` | compile | BOM 11.7.2 |
| `org.flywaydb:flyway-database-postgresql` | compile | BOM（Flyway 10 起 PG 方言拆分模块，缺失则迁移直接报错，必须显式声明） |
| `org.postgresql:postgresql` | runtime | BOM 42.7.11 |
| `org.apache.qpid:qpid-jms-client` | compile | `${qpid-jms.version}`（M14 骨架用，P0 计划明列） |
| `net.javacrumbs.shedlock:shedlock-spring` | compile | `${shedlock.version}` |
| `net.javacrumbs.shedlock:shedlock-provider-jdbc-template` | compile | `${shedlock.version}` |
| `com.fuyun:fuyun-common` | compile | `${project.version}` |
| `spring-boot-starter-test` | test | BOM |
| `org.springframework.boot:spring-boot-testcontainers` | test | BOM |
| `org.testcontainers:junit-jupiter`、`org.testcontainers:postgresql`、`org.testcontainers:rabbitmq` | test | BOM 1.21.4 |
| `com.tngtech.archunit:archunit-junit5` | test | `${archunit.version}`（backend B.2-5 指定 fuyun-app 为聚合边界测试落点，规则类 PR-2 起编写） |

**明确禁止引入**（P0 计划 §1-PR-1）：hapi、dcm4che（P3/P4）、AWS SDK（P1+）、redisson（出现 watchdog/可重入诉求时再加）、spring-security 全家桶（P0 认证轻量方案在 M01 PR 内定）、springdoc（P0 依赖集未列，首个 REST 端点落地时再声明，届时锁 2.8.17，backend C.2）。

**build 插件**：`org.springframework.boot:spring-boot-maven-plugin`（版本 `${spring-boot.version}`，进父 POM pluginManagement），fuyun-app 内声明 execution 绑定 `repackage` goal（不继承 starter-parent 须显式，Dockerfile 的 `mvn package` 才产出可执行 fat jar）。

#### （1）`FuyunApplication`

`com.fuyun.app.FuyunApplication`，`@SpringBootApplication`，标准 main。不放任何业务逻辑（backend B.1）。

#### （2）`src/main/resources/application.yml`（全环境公共项，关键键值）

```yaml
spring:
  application:
    name: fuyun-backend
  mvc:
    problemdetails:
      enabled: true            # backend A.3-2：失败响应一律 RFC 9457 ProblemDetail
  lifecycle:
    timeout-per-shutdown-phase: 30s   # backend A.5-15：优雅停机 20-30s，与 compose stop_grace_period 对齐
  datasource:
    hikari:
      minimum-idle: 10         # backend A.4.2-6：固定大小池，起步值 10-20，压测校准
      maximum-pool-size: 10
  flyway:
    out-of-order: false        # backend A.4.1-3 显式声明
    baseline-on-migrate: false
    default-schema: public     # 公共 schema 承载 flyway_schema_history（backend A.4.1-4；取 PG 默认 public）
    schemas:                   # 全量声明（Flyway 自动创建缺失 schema）
      - public
      - system
      - patient
      - outpatient
      - inpatient
      - nursing
      - pharmacy
      - lab
      - imaging
      - emr
      - surgery
      - icu
      - transfusion
      - billing
      - iot
      - asset
      - ward
      - peis
      - internet
      - ops
      - integration
    locations:                 # backend A.4.1-4：显式枚举 20 模块目录，禁通配符
      - classpath:db/migration/system
      - classpath:db/migration/patient
      - classpath:db/migration/outpatient
      - classpath:db/migration/inpatient
      - classpath:db/migration/nursing
      - classpath:db/migration/pharmacy
      - classpath:db/migration/lab
      - classpath:db/migration/imaging
      - classpath:db/migration/emr
      - classpath:db/migration/surgery
      - classpath:db/migration/icu
      - classpath:db/migration/transfusion
      - classpath:db/migration/billing
      - classpath:db/migration/iot
      - classpath:db/migration/asset
      - classpath:db/migration/ward
      - classpath:db/migration/peis
      - classpath:db/migration/internet
      - classpath:db/migration/ops
      - classpath:db/migration/integration
  rabbitmq:                    # backend A.5-4/A.5-5 定稿姿态
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true
    listener:
      simple:
        acknowledge-mode: auto
        default-requeue-rejected: false
server:
  shutdown: graceful           # backend A.5-15
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      probes:
        enabled: true          # liveness/readiness 探针显式开启（backend A.5-15）
      show-details: never      # prod 安全默认，dev profile 覆盖
logging:
  pattern:
    level: "%5p [%X{traceId:-}]"   # traceId 经 MDC 关联日志的最小落法：仅覆写 level 段，其余沿用 Boot 默认 pattern
fuyun:
  trace:
    response-header-enabled: true  # 响应头回写 X-Trace-Id，前端排障锚点
    mdc-key: traceId
```

注意：PR-1 无任何迁移文件，Flyway 首跑仅创建 `flyway_schema_history`（B1.2 冒烟断言目标即此）。MinIO/IoTDA 的 `FUYUN_MINIO_*`/`FUYUN_IOTDA_*` 变量 PR-1 无消费方（AWS SDK/qpid Bean 未建），yml **不写**对应键，避免死配置；compose 侧照定稿报告 §6.6 原样保留传参。

#### （3）`application-dev.yml` / `application-test.yml` / `application-prod.yml` 骨架

三文件均以 `${FUYUN_*}` 环境变量占位（backend A.2-5 敏感项红线；relaxed binding 对齐 `FUYUN_*` 命名组，backend A.2-4）。差异项：

| 键 | dev | test | prod |
| --- | --- | --- | --- |
| `spring.datasource.url` | `${FUYUN_DATASOURCE_URL:jdbc:postgresql://localhost:5432/fuyun_medical?reWriteBatchedInserts=true}`（默认值内含 `reWriteBatchedInserts=true`，backend A.4.2-6） | 同左（Testcontainers 经 @ServiceConnection 覆盖，默认值仅兜底） | `${FUYUN_DATASOURCE_URL}`（无默认，缺失即启动失败 fail-fast） |
| `spring.datasource.username` / `password` | `${FUYUN_DATASOURCE_USERNAME:fuyun}` / `${FUYUN_DATASOURCE_PASSWORD:}` | 同 dev | `${FUYUN_DATASOURCE_USERNAME}` / `${FUYUN_DATASOURCE_PASSWORD}` |
| `spring.datasource.hikari.leak-detection-threshold` | `60000` | `60000` | `300000`（backend A.4.2-6：dev/test 60s、prod 300s，禁 0） |
| `spring.data.redis.host` / `port` / `password` | `${FUYUN_REDIS_HOST:localhost}` / `${FUYUN_REDIS_PORT:6379}` / `${FUYUN_REDIS_PASSWORD:}` | 同 dev | `${FUYUN_REDIS_HOST}` / `${FUYUN_REDIS_PORT}` / `${FUYUN_REDIS_PASSWORD}` |
| `spring.rabbitmq.host` / `port` / `username` / `password` | `${FUYUN_RABBITMQ_HOST:localhost}` / `${FUYUN_RABBITMQ_PORT:5672}` / `${FUYUN_RABBITMQ_USERNAME:fuyun}` / `${FUYUN_RABBITMQ_PASSWORD:}` | 同 dev | 同 prod 数据源口径，全部无默认 |
| `management.endpoint.health.show-details` | `always` | `always` | 不写（继承公共 never） |

#### （4）@ConfigurationProperties 示范类（唯一一个，服务真实功能不设死配置）

- `com.fuyun.app.properties.TraceProperties`：`@Validated` + record 构造器绑定，`@ConfigurationProperties(prefix = "fuyun.trace")`，字段 `boolean responseHeaderEnabled` + `@DefaultValue("traceId") @NotBlank String mdcKey`（演示 JSR-303 启动期校验与默认值，backend A.2-2）。
- `com.fuyun.app.config.TraceIdConfig`：`@Configuration` + `@EnableConfigurationProperties(TraceProperties.class)`；`FilterRegistrationBean<TraceFilter>` Bean——`new TraceFilter(properties.mdcKey(), properties.responseHeaderEnabled())`，`setOrder(Ordered.HIGHEST_PRECEDENCE)`、`addUrlPatterns("/*")`。演示构造器注入（backend A.1-7）与配置集中 config/（backend B.1）。

### 2.4 20 个业务域空模块（模板统一）

每个 `fuyun-{domain}` 的 `pom.xml` 用同一模板（以 fuyun-system 为例，其余仅换 artifactId）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.fuyun</groupId>
    <artifactId>fuyun-backend</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <!-- 业务模块（M01 系统与权限）：仅依赖公共模块，业务实现按 P0~P6 计划逐域实装 -->
  <artifactId>fuyun-system</artifactId>
  <dependencies>
    <dependency>
      <groupId>com.fuyun</groupId>
      <artifactId>fuyun-common</artifactId>
      <version>${project.version}</version>
    </dependency>
  </dependencies>
</project>
```

每个模块的目录占位（**只建目录 + `.gitkeep`，禁止任何空实现类**，P0 计划 §1-PR-1「禁止空实现类堆积」）：

- `src/main/java/com/fuyun/{domain}/` 下 17 个包目录，逐个放 `.gitkeep`：`api/`、`controller/`、`dto/`、`vo/`、`record/`、`service/`、`service/impl/`、`mapper/`、`entity/`、`convert/`、`cache/`、`gateway/`、`config/`、`properties/`、`constants/`、`enum/`、`exception/`、`internal/`（backend 宪法 C.3 / B.1 包职责表）。
- `src/main/resources/` 下三个资源目录，逐个放 `.gitkeep`：`db/migration/{domain}/`（如 `db/migration/system/`，与 §1 清单一致）、`mapper/`（复杂 SQL XML）、`lua/`（Redis 原子脚本）。
- PR-1 **不落任何 Flyway 迁移文件**；号段制（backend A.4.1-2：公共域低位、每模块固定百位段）随首个真实迁移在对应 PR 落地并由 CI 校验。

---

## 3. B1.2 冒烟集成测试 + backend/Dockerfile 重写

### 3.1 冒烟 IT（fuyun-app 下唯一 `*IT`）

文件：`fuyun-app/src/test/java/com/fuyun/app/SmokeStackIT.java`（命名约定 *IT → failsafe 拾取，backend C.4）。

- **容器三件套，tag 与 deploy compose 严格一致**（backend C.5-4）：`timescale/timescaledb:2.29.2-pg16`、`redis:8.10.1`、`rabbitmq:4.3.5-management`。
- 声明方式：`@Testcontainers` + 三个 `static final` 容器字段、`@Container`（static 类级共享，backend C.5-4；禁用 reuse）。
- **连接注入：选 Boot 3.5 原生 `@ServiceConnection`**（理由：零样板、由 Spring Boot 自动生成连接细节并覆盖 test profile 数据源/Rabbit/Redis 配置）。每个字段标 `@ServiceConnection`；Redis 用 `GenericContainer("redis:8.10.1")`（Boot 按镜像名识别 redis 连接，测试容器不设密码，无需口令细节）。备选方案 `@DynamicPropertySource` 仅在 @ServiceConnection 对该容器类型不生效时降级，不并列混用。类上：`@SpringBootTest` + `@ActiveProfiles("test")`。
- 三条断言（对应 P0 计划「Flyway 迁移可重放 → Redis 读写 → RabbitMQ 队列声明收发一帧」，CF-1 最小验证）：
  1. **Flyway**：上下文成功启动本身即证明 migrate 无异常；再经 `JdbcTemplate` 查 `flyway_schema_history` 表存在且 `installed_rank` 记录可查（断言查询不抛异常且返回列非空），证明公共 schema 落点正确。
  2. **Redis**：`StringRedisTemplate` 写 `fy:app:it-smoke:<uuid>`（键名遵循 `fy:{module}:{biz}:{id}`，backend A.5-1）并设 TTL 60s（对齐「除白名单外禁无过期键」），读回断言值相等、`getExpire > 0`，最后删除。
  3. **RabbitMQ**：`RabbitAdmin` 声明队列 `q.it.smoke`（服务端 `default_queue_type=quorum` 使其实际为 quorum 类型）；`rabbitTemplate.convertAndSend` 发一帧字符串，`receive(5s)` 取回断言内容一致（队列清理交给容器销毁）。
- 日志中文；测试注释说明业务意图（基础设施装配最小验证）。

### 3.2 `backend/Dockerfile` COPY 策略重写（W-3 对齐项①）

现状缺陷：`COPY pom.xml fuyun-*/pom.xml ./` 的 glob 把全部子模块 POM **拍平**拷进 `/workspace` 根，Maven 解析父聚合结构失败。重写为逐模块 COPY 保目录结构（构建上下文 = `backend/`，共 23 个 POM）：

```dockerfile
# 先拷父 POM 与全部子模块 POM，保持各自目录结构（消除 glob 拍平），单独成层命中依赖缓存
COPY pom.xml ./
COPY fuyun-common/pom.xml fuyun-common/
COPY fuyun-app/pom.xml fuyun-app/
COPY fuyun-system/pom.xml fuyun-system/
COPY fuyun-patient/pom.xml fuyun-patient/
COPY fuyun-outpatient/pom.xml fuyun-outpatient/
COPY fuyun-inpatient/pom.xml fuyun-inpatient/
COPY fuyun-nursing/pom.xml fuyun-nursing/
COPY fuyun-pharmacy/pom.xml fuyun-pharmacy/
COPY fuyun-lab/pom.xml fuyun-lab/
COPY fuyun-imaging/pom.xml fuyun-imaging/
COPY fuyun-emr/pom.xml fuyun-emr/
COPY fuyun-surgery/pom.xml fuyun-surgery/
COPY fuyun-icu/pom.xml fuyun-icu/
COPY fuyun-transfusion/pom.xml fuyun-transfusion/
COPY fuyun-billing/pom.xml fuyun-billing/
COPY fuyun-iot/pom.xml fuyun-iot/
COPY fuyun-asset/pom.xml fuyun-asset/
COPY fuyun-ward/pom.xml fuyun-ward/
COPY fuyun-peis/pom.xml fuyun-peis/
COPY fuyun-internet/pom.xml fuyun-internet/
COPY fuyun-ops/pom.xml fuyun-ops/
COPY fuyun-integration/pom.xml fuyun-integration/
```

- 依赖预热层 `RUN mvn -B -ntp dependency:go-offline`、源码层 `COPY . .`、打包 `RUN mvn -B -ntp package -DskipTests` 维持原设计。
- **七条规范原样保留，一处不改**：多阶段（maven:3.9-eclipse-temurin-17 → eclipse-temurin:17-jre）/ 运行层装 curl / 非 root uid 1001 / pom 先于源码 / 打包跳过测试 / 镜像内零密钥 / jar 取自 fuyun-app（backend C.5-6）。
- 预留说明（写入注释，一行）：PR-4 若按 D-3 默认裁决增补 `backend/iot-simulator` Maven 子模块，此处同步追加其 COPY 行。

### 3.3 `web/Dockerfile` 核对结论

核对发现一处真实缺陷，随本 PR 修正（属 W-3 对齐项②「产物路径以 `web/apps/<app>/dist` 为准」的落地内容）：运行层 `COPY apps/workstation/dist ...` **缺 `--from=build`**——产物在构建层容器内 `pnpm build` 生成于 `/workspace/apps/*/dist`，且 `.dockerignore` 已排除 `**/dist/`（上下文内根本无 dist），现状必然构建失败。修正：三行 COPY 改为 `COPY --from=build /workspace/apps/<app>/dist /usr/share/nginx/html/<app>`（workstation/bigscreen/portal），其余（node:24 + corepack pnpm@12.3.4、nginx:1.30.4、注释）不动。

---

## 4. B1.3 deploy/ 全量编排 + ci.yml 修正

### 4.1 `deploy/docker-compose.yml`（七服务 + sim profile，全部照定稿报告 §6.6，禁 latest）

服务规格总表（健康检查参数统一 `interval: 10s / timeout: 5s / retries: 5`；例外已标注）：

| service | image:tag | 宿主端口 | 卷 | healthcheck | depends_on | 要点 |
| --- | --- | --- | --- | --- | --- | --- |
| `postgres` | `timescale/timescaledb:2.29.2-pg16` | `${POSTGRES_PORT:-5432}:5432` | `pg_data:/var/lib/postgresql/data` + `../postgres/initdb:/docker-entrypoint-initdb.d:ro` | `CMD-SHELL` `pg_isready -U $POSTGRES_USER -d $POSTGRES_DB`，`start_period: 60s` | 无（拓扑根） | `POSTGRES_DB/USER/PASSWORD` 全部 `${}` 注入 |
| `redis` | `redis:8.10.1` | `${REDIS_PORT:-6379}:6379` | `redis_data:/data` | `CMD-SHELL` `redis-cli ping \| grep PONG`，配 `REDISCLI_AUTH` 环境变量（避免 -a 进程列表泄露） | 无 | `command: ["redis-server", "--requirepass", "${REDIS_PASSWORD}"]` |
| `rabbitmq` | `rabbitmq:4.3.5-management` | `${RABBITMQ_PORT:-5672}:5672`、`${RABBITMQ_MGMT_PORT:-15672}:15672` | `rabbitmq_data:/var/lib/rabbitmq` + `../rabbitmq/rabbitmq.conf:/etc/rabbitmq/rabbitmq.conf:ro` | `CMD` `rabbitmq-diagnostics -q ping`，`start_period: 90s`（首启慢） | 无 | `RABBITMQ_DEFAULT_USER/PASS` 注入；管理台仅 dev/test 暴露 |
| `minio` | `minio/minio:RELEASE.2025-09-07T16-13-09Z` | `${MINIO_PORT:-9000}:9000` | `minio_data:/data` | `CMD` `mc ready local` | 无 | `command: ["server", "/data", "--address", ":9000"]`（不传 --console-address） |
| `backend` | `fuyun/backend:${BACKEND_TAG:-dev}` | **不发布宿主端口**（`--scale` 前提） | 无 | `CMD-SHELL` `curl -fsS http://127.0.0.1:8080/actuator/health \| grep -q '"status":"UP"'`，`start_period: 120s`（含 Flyway 窗口） | 四基础设施全部 `condition: service_healthy`（禁裸 service_started） | `env_file: .env` + `environment` 注入 `SPRING_PROFILES_ACTIVE` 与 `FUYUN_DATASOURCE_*/FUYUN_REDIS_*/FUYUN_RABBITMQ_*/FUYUN_MINIO_*/FUYUN_IOTDA_*`（照报告 §6.6 原样）；**追加 `stop_grace_period: 40s`**（与 yml `timeout-per-shutdown-phase: 30s` 对齐，backend A.5-15） |
| `nginx` | `nginx:1.30.4` | `${NGINX_PORT:-80}:80` | 只读 bind：`../web/apps/workstation/dist:/usr/share/nginx/html/workstation:ro`、`../web/apps/portal/dist:.../portal:ro`、`../web/apps/bigscreen/dist:.../bigscreen:ro`、`../nginx/fuyun.conf:/etc/nginx/conf.d/default.conf:ro` | `CMD-SHELL` bash 内建 `/dev/tcp` 探测 `GET /healthz` 返回 200（官方镜像无 curl），`retries: 3` | `backend: service_healthy` | bind 相对路径以 deploy/ 为基准；**报告 §6.6 中 `../../web/<app>/dist` 为陈旧写法，按 W-3 对齐项②一律改为 `../web/apps/<app>/dist`** |
| `iot-simulator` | `fuyun/iot-simulator:${SIMULATOR_TAG:-dev}` | 无 | 无 | 无（PR-4 前镜像不存在，服务定义先落） | `backend: service_healthy` | `profiles: ["sim"]`；`env_file: .env` + `IOTDA_MQTT_HOST/IOTDA_DEVICE_ID/IOTDA_DEVICE_SECRET` |

顶层：网络 `fy-net`（driver: bridge）；四 named volumes `pg_data`/`redis_data`/`rabbitmq_data`/`minio_data`；不写已废弃的 `version:` 字段。本地验收时 `BACKEND_TAG=dev` 与 `docker build -t fuyun/backend:dev` 的 tag 对齐。

### 4.2 `deploy/.env.example` 全键清单（定稿报告 §6.7 + IOTDA 六变量）

| 分组 | 键 | 占位说明 |
| --- | --- | --- |
| PostgreSQL/TimescaleDB | `POSTGRES_DB=fuyun_medical`、`POSTGRES_USER=fuyun`、`POSTGRES_PASSWORD=`（必填）、`POSTGRES_PORT=5432` | |
| Redis | `REDIS_PASSWORD=`（必填）、`REDIS_PORT=6379` | |
| RabbitMQ | `RABBITMQ_USER=fuyun`、`RABBITMQ_PASSWORD=`（必填）、`RABBITMQ_PORT=5672`、`RABBITMQ_MGMT_PORT=15672` | |
| MinIO | `MINIO_ROOT_USER=fuyun`、`MINIO_ROOT_PASSWORD=`（必填，至少 8 位）、`MINIO_PORT=9000` | |
| IoTDA（六变量） | `IOTDA_AMQP_ENDPOINT=`（amqps://… 形态）、`IOTDA_AMQP_ACCESS_KEY=`、`IOTDA_AMQP_ACCESS_SECRET=`（必填）、`IOTDA_MQTT_HOST=`、`IOTDA_DEVICE_ID=`、`IOTDA_DEVICE_SECRET=`（必填） | PR-4 端到端演示前由用户填真实值 |
| 应用 | `SPRING_PROFILES_ACTIVE=dev`、`BACKEND_TAG=dev`、`SIMULATOR_TAG=dev`、`NGINX_PORT=80` | |

文件头中文注释注明「复制为 .env 后填写；.env 不入库（.gitignore 已含）」。

### 4.3 `deploy/postgres/initdb/01-init.sql`

```sql
-- 初始化：业务库由 POSTGRES_DB 环境变量创建，官方镜像以该库为 initdb 脚本的执行上下文，
-- 本脚本只负责 TimescaleDB 扩展（backend A.4.1-5：CREATE EXTENSION 只在 initdb 承担，Flyway 不重复）
CREATE EXTENSION IF NOT EXISTS timescaledb;
```

其余表结构/超表/压缩保留策略全部归 Flyway（PR-3/PR-4），compose 不承载业务 DDL。

### 4.4 `deploy/rabbitmq/rabbitmq.conf`

```ini
# 默认队列类型 quorum（总 Spec D1；业务队列全部 quorum，backend A.5-4）
default_queue_type = quorum
# 管理台监听（仅 dev/test compose 暴露 15672，prod 不使用本编排）
management.tcp.port = 15672
```

交换机/队列不在此预声明（M20 治理构件应用侧声明，禁私建交换机）。

### 4.5 `deploy/nginx/fuyun.conf`（三前端静态路由 + /api 反代 + /ws Upgrade + /healthz）

```nginx
# fuyun-medical dev 入口：三前端静态资源 + /api 反向代理 + /ws WebSocket 升级
server {
    listen 80;
    server_name _;

    # 容器健康检查探针（compose healthcheck 依赖，静态 200）
    location = /healthz {
        access_log off;
        default_type text/plain;
        return 200 "ok\n";
    }

    # 默认入口跳转医护工作站（dev 便利；生产入口经云上负载均衡）
    location = / { return 302 /workstation/; }

    # 三前端 SPA 静态路由（bind mount 自 web/apps/<app>/dist，产物路径与 pnpm build 默认 dist 一致，禁改名）
    location /workstation/ { alias /usr/share/nginx/html/workstation/; try_files $uri $uri/ /workstation/index.html; }
    location /portal/      { alias /usr/share/nginx/html/portal/;      try_files $uri $uri/ /portal/index.html; }
    location /bigscreen/   { alias /usr/share/nginx/html/bigscreen/;   try_files $uri $uri/ /bigscreen/index.html; }

    # REST 反向代理：保留 /api 前缀原样转发（接口挂 /api/v1，backend A.3-1，禁 rewrite）
    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # WebSocket 升级（/ws/** 统一前缀，根定位层 §7 跨子项目协作契约）
    location /ws/ {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```

### 4.6 `.github/workflows/ci.yml` 修改点（W-3 对齐项③）

- **只删四处「骨架期排除」行及其两条注释行**：changes job 中 `- '!backend/Dockerfile'`、`- '!backend/.dockerignore'`、`- '!web/Dockerfile'`、`- '!web/.dockerignore'` 与对应的 `# 骨架期排除：…` 注释（第 42~44、48~50 行区域）。删除后 `backend/**`、`web/**` 过滤器恢复对 Dockerfile/.dockerignore 的触发。
- **保留 `pom` 与 `webpkg` 存在性守卫及 backend/frontend job 的双重 if 条件，理由**：(a) paths-filter 误判兜底——守卫确保即使过滤器误报也不会在无构建清单的分支/ fork 上空跑门禁（原注释「防缓存与构建空跑」语义不变）；(b) webpkg 守卫保护 `pnpm/action-setup` 的 `package_json_file: web/package.json` 不因清单缺失而失败。P0 落地后守卫恒真，不影响触发。**不得顺手改动 ci.yml 其他任何内容**（job name 与分支保护 required checks 精确对齐，改即断链）。

---

## 5. B1.4 web monorepo 逐文件规格

### 5.1 `web/package.json`（根）

```json
{
  "name": "fuyun-web",
  "private": true,
  "packageManager": "pnpm@12.3.4",
  "engines": { "node": ">=24 <25" },
  "scripts": {
    "lint": "eslint . --max-warnings=0",
    "format": "prettier --write .",
    "format:check": "prettier --check .",
    "type-check": "pnpm -r --if-present type-check",
    "test": "vitest run",
    "build": "pnpm -r --if-present build",
    "audit": "pnpm audit --audit-level high"
  }
}
```

- 版本全部经 catalog 引用（`"eslint": "catalog:"` 形式），根 devDependencies 安装：`eslint`、`eslint-plugin-vue`、`typescript-eslint`、`@vue/eslint-config-typescript`、`eslint-config-prettier`、`prettier`、`typescript`、`vitest`、`@vitest/coverage-v8`、`jsdom`、`@vue/test-utils`、`vue-tsc`。
- `pnpm audit` 脚本内固定 `--audit-level high`（web C.5-5），CI 现有 `pnpm audit` 步骤无需改动。

### 5.2 `web/pnpm-workspace.yaml`

```yaml
packages:
  - "apps/*"
  - "packages/*"
catalog:
  vue: 3.5.42
  typescript: 5.9.3
  vite: 8.2.2
  vue-tsc: 3.3.10
  pinia: 4.0.3
  vue-router: 5.3.1
  axios: 1.20.0
  eslint: 10.10.0
  eslint-plugin-vue: 10.10.0
  typescript-eslint: 8.69.0
  "@vue/eslint-config-typescript": 14.9.0
  eslint-config-prettier: 10.1.8
  prettier: 3.9.6
  vitest: 4.1.11
  "@vitest/coverage-v8": 4.1.11
  "@vue/test-utils": 2.5.0
  jsdom: 30.0.1
```

catalog 口径（web C.4 约定）：共享工具链进 catalog；**业务独立依赖不进 catalog**——`element-plus 2.14.5`（workstation 与 packages/ui 两处引用，可进 catalog）、`dayjs`（element-plus peer，版本随其 peer 要求，见 §9）、`unplugin-vue-components 32.1.0`/`unplugin-auto-import 21.1.0`（仅 workstation）不进 catalog。`@vitejs/plugin-vue` 为表外必需配套（§9 待裁决）。echarts/`@stomp/stompjs` **本批不装**（PR-5 首个真实页面再引，业务独立依赖）。

### 5.3 三 app 通用文件清单（`web/apps/{workstation,portal,bigscreen}/`）

| 文件 | 规格 |
| --- | --- |
| `package.json` | `name: "@fuyun/<app>"`、`private: true`、`type: "module"`、`"vue": "catalog:"` 等经 catalog；scripts：`dev`（vite）、`build`（`vue-tsc --noEmit -p tsconfig.node.json && vite build`）、`type-check`（`vue-tsc --noEmit -p tsconfig.app.json && vue-tsc --noEmit -p tsconfig.node.json`）、`test`（`vitest run`）；对 `@fuyun/shared` 用 `workspace:*`（web B.2-5） |
| `index.html` | 中文 `<title>`（如「富云医护工作站」，各 app 对应），`<div id="app">` + `<script type="module" src="/src/main.ts">` |
| `vite.config.ts` | `defineConfig({ plugins: [vue()] })`；`server.proxy`：`"/api": { target: "http://localhost:8080" }`（**禁 rewrite 前缀**，web A.2-4）、`"/ws": { target: "ws://localhost:8080", ws: true }`；build.outDir 保持默认 dist（web A.2-5，与 compose bind 路径耦合禁改名）。workstation 额外注册按需插件：`unplugin-vue-components`（`dirs` 默认、`dts: "src/components.d.ts"`）+ `unplugin-auto-import`（`dts: "src/auto-imports.d.ts"`）+ `ElementPlusResolver`（web B.3-6） |
| `tsconfig.json` | solution 文件：`{ "files": [], "references": [{ "path": "./tsconfig.app.json" }, { "path": "./tsconfig.node.json" }] }`（web C.3） |
| `tsconfig.app.json` | 手写（不引 @vue/tsconfig 包，避免表外依赖）：`strict: true`（web A.1-4）、`target: "ES2022"`、`lib: ["ES2022", "DOM", "DOM.Iterable"]`、`module: "ESNext"`、`moduleResolution: "bundler"`、`noEmit: true`、`types: ["vite/client"]`、`jsx: "preserve"`、`baseUrl` + `paths: { "@/*": ["./src/*"] }`（与 vite 别名同步，web A.2-5）、`include: ["src/**/*.ts", "src/**/*.d.ts", "src/**/*.vue"]` |
| `tsconfig.node.json` | `strict: true`、`module`/`moduleResolution` 同上、`types: ["node"]`、`noEmit: true`、`include: ["vite.config.ts", "vitest.config.ts"]` |
| `src/vite-env.d.ts` | `/// <reference types="vite/client" />` + `interface ImportMetaEnv { readonly VITE_API_BASE_URL?: string }`（PR-3 的 Axios 单例消费；三处同步规则——env 文件、ImportMetaEnv、.env.example——写入中文注释，web A.2-3） |
| `.env.example` | `VITE_API_BASE_URL=/api`（值占位 + 中文注释） |
| `src/App.vue` | `<script setup lang="ts">` 基线（web A.1-1 零例外），模板含根容器 + `<RouterView />`，多词组件名 `TheAppLayout`？否——保持 `App.vue` 约定名即可，模板以站点中文名文案为冒烟锚点 |
| `src/router/index.ts` | createRouter + createWebHistory；一条首页路由 `{ path: "/", name: "home", component: () => import("@/views/home/HomeView.vue") }`——**路由组件全懒加载**（web B.3-2），`meta` 预留（注释说明「路由 = 权限点清单」形态） |
| `src/views/home/HomeView.vue` | 最小页面：展示站点中文名与一句说明文案（供冒烟断言） |
| `src/main.ts` | `createApp(App).use(createPinia()).use(router).mount("#app")` |
| `src/` 其余目录 | `api/`、`stores/`、`composables/`、`components/common/`、`types/`、`utils/`、`assets/`、`styles/`、`directives/` 各放 `.gitkeep`（web B.1 目录基线；禁堆空模块） |
| `vitest.config.ts` | `defineConfig({ test: { environment: "jsdom", include: ["src/**/*.spec.ts"] }, plugins: [vue()] })`（web C.3/C.5-4） |
| `src/App.spec.ts` | 每 app 唯一冒烟单测，断言点：`mount(App, { global: { plugins: [createPinia(), router] } })` → ① `router.isReady()` 与 `flushPromises` 后页面渲染出站点中文名文案（workstation=「医护工作站」、portal=「患者门户」、bigscreen=「数据大屏」）；② `router.currentRoute.value.path === "/"`。断言业务结果（页面可达 + 路由就绪），禁止空断言 |

依赖差异：workstation 额外 `element-plus 2.14.5` + `unplugin-vue-components 32.1.0` + `unplugin-auto-import 21.1.0` + `dayjs`（显式声明，pnpm 严格依赖下 element-plus 官方明示的坑，web B.3-6）；`ElementPlusResolver` 从 `unplugin-vue-components/resolvers` 导入，无需额外依赖；portal/bigscreen 仅 catalog 基础集（web C.1/B.2-8：element-plus 仅 workstation）。

### 5.4 packages 占位（禁止堆空类）

- `web/packages/shared`：`package.json`（`@fuyun/shared`、`type: module`、`exports` 指向 `src/index.ts`、**禁依赖 vue/element-plus**，web B.1）；`src/index.ts` 仅导出分页契约类型 `PageResult<T> { content: T[]; page: number; size: number; total: number }`（来源 backend A.3-6，真实契约非占位空壳；后端 Long→String 序列化字段的 `string` 承载规则写入注释，web A.3-6）。openapi-typescript 生成物与生成脚本待 PR-3（后端首个 OpenAPI 端点可用，T-R4-3），本批不装依赖。
- `web/packages/ui`：`package.json`（`@fuyun/ui`、peerDeps `vue`；element-plus 待首个跨 app 组件落地时再声明）；`src/index.ts` 为带中文占位说明注释的 `export {}`（说明「首个跨应用复用组件落地时替换」，不写任何空组件类）。

### 5.5 根配置三件

- `web/eslint.config.mjs`：`defineConfigWithVueTs(pluginVue.configs['flat/essential'], vueTsConfigs.recommendedTypeChecked, eslintConfigPrettier, { ignores: ["**/dist/**", "**/node_modules/**", "**/auto-imports.d.ts", "**/components.d.ts", "**/coverage/**"] })`——prettier 配置**必须置尾**、类型感知规则从 recommendedTypeChecked 起步（web C.5-2）；格式规则一律不进 eslint（禁 eslint-plugin-prettier）。类型感知的 project 解析依赖 @vue/eslint-config-typescript 14.x 内建 projectService；若首跑报 project 未找到，在该文件补 `languageOptions.parserOptions.projectService: true`，不得降级到非类型感知配置。
- `web/.prettierrc`：`{ "printWidth": 100, "singleQuote": true, "trailingComma": "all", "endOfLine": "lf" }`（web C.3 原文）。
- `web/vitest.config.ts`：根聚合 `defineConfig({ test: { projects: ["apps/workstation", "apps/portal", "apps/bigscreen"] } })`（新增 app 必须同步此清单，web C.4 约定）；覆盖率 report-only 不设阈值（web C.5-4）。

### 5.6 卫生配套

- `pnpm-lock.yaml` 入库（CI `--frozen-lockfile` 依据）；根 `.gitignore` 追加两行 `auto-imports.d.ts`、`components.d.ts`（构建期生成物不入库；首个真实使用按需 API 的 PR 再评估改为入库，届时同步注释）。
- 生成物文件名路径（vite.config `dts` 指向 `src/`）与 gitignore 保持一致，避免 `git status` 永脏。

---

## 6. TDD 与验收指令（每批次完成标准）

TDD 要求：fuyun-common 的四个测试类与实现同批先写断言后补实现（全局规范 §四：测试与实现同一次提交）；冒烟 IT 与 fuyun-app 装配同批交付。所有命令在仓库根对应子目录执行。

| 批次 | 完成标准（命令全绿即过） |
| --- | --- |
| B1.1 | `cd backend && mvn -B -ntp test`（surefire 单测全绿，含 fuyun-common 四测试类；JAVA_HOME 指向 JDK17）+ `cd backend && mvn -B spotless:check`（格式门禁绿；若报格式差异先 `mvn -B spotless:apply` 再复核 diff） |
| B1.2 | `cd backend && mvn -B -ntp verify`（spotless + 单测 + failsafe 冒烟 IT + JaCoCo 双规则全绿；**需 Docker Desktop 运行中**）+ `cd backend && docker build -t fuyun/backend:dev .`（镜像构建成功，证明 COPY 策略修复）+ `cd web && docker build -t fuyun/web:dev .`（web Dockerfile `--from=build` 修复后可独立构建，需先有 pnpm-lock 与 workspace） |
| B1.3 | `cd deploy && docker compose --env-file .env config -q`（语法与插值校验）+ 填好 `deploy/.env`（本地值）后 `docker compose -f docker-compose.yml --env-file .env up -d postgres redis rabbitmq minio` 四容器 `docker compose ps` 全 `healthy`；本地再以 B1.2 构建的 `fuyun/backend:dev` 起 backend + nginx，`curl http://localhost:8080`（直连容器）与 `curl http://localhost/healthz`、`curl http://localhost/api/actuator/health` 均通 |
| B1.4 | `cd web && pnpm install`（生成 lockfile 入库）→ `pnpm lint` → `pnpm format:check` → `pnpm type-check` → `pnpm test`（三应用冒烟单测绿）→ `pnpm build`（三应用 dist 产出，路径 `web/apps/<app>/dist`）→ `pnpm audit`（high 及以上阻断） |
| PR-1 收尾 | `docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d` 七服务全 healthy（iot-simulator 除外，属 sim profile）+ **起停二次验证**：`docker compose ... down`（不带 -v）后再次 `up -d`，确认 volume 持久化生效（flyway_schema_history 不重建、RabbitMQ 用户保留）；PR 五检查（`backend / verify`、`frontend / verify`、`images`、`commitlint`、`hygiene`）全绿后合并 |

---

## 7. 提交切分建议（conventional commits，中文 subject）

| 序 | 提交信息 | 内容 |
| --- | --- | --- |
| 1 | `build(backend): 落地父 POM 与公共基座（版本锁定/JaCoCo 双规则/Spotless 门禁）` | `backend/pom.xml` + `fuyun-common` 全部类与单测 |
| 2 | `build(backend): 装配 fuyun-app 与 20 个业务域空模块骨架` | `fuyun-app`（Application/yml 骨架/TraceProperties）+ 20 个空模块 |
| 3 | `test(backend): 增加基础设施冒烟集成测试（Testcontainers 三容器）` | `SmokeStackIT` + fuyun-app test 依赖 |
| 4 | `fix(backend): 重写镜像 COPY 策略消除 glob 拍平` | `backend/Dockerfile`（W-3 对齐①） |
| 5 | `build(deploy): 落地全栈编排与中间件初始化配置` | `deploy/` 五件套（compose/.env.example/initdb/rabbitmq.conf/nginx） |
| 6 | `chore(ci): 移除骨架期镜像触发排除项` | `.github/workflows/ci.yml` 四处删除（W-3 对齐③） |
| 7 | `build(web): 搭建 pnpm monorepo 三应用工作区与冒烟单测` | B1.4 全部（含 `.gitignore` 追加） |
| 8 | `fix(web): 前端镜像运行层改为从构建层取产物` | `web/Dockerfile` 补 `--from=build`（W-3 对齐②落地） |

提交前本地跑 `pre-commit run --all-files`（与 CI hygiene 同源）与 `cd web && pnpm format`（写入修复）。若会话能力允许，4+8 可并入各自骨架提交。

---

## 8. 红线清单（实现专员绝对禁止项，摘自宪法，违者不得合入）

1. **版本红线**：只允许本简报 §2.1/§5.2 列出的版本；BOM 托管依赖禁止自行覆盖版本；禁止引入表外依赖（新依赖先在 PR 描述申报理由，P0 计划 §0-2）；禁止 `latest`/漂移 tag；禁止再引入 mybatis/mybatis-spring（backend A.4.3-12）；禁止 Modulith 依赖进父 POM（backend B.2-6）。
2. **编码红线**：全部文件 UTF-8 无 BOM、LF 行尾；注释/日志/文档全中文、标识符英文；禁止提交密钥与 `.env` 真实值；禁止 >1MB 文件（根定位层 §7）。
3. **Java 编码**：禁止 `@Autowired` 字段注入（构造器注入强制，backend A.1-7）；禁止 `@Data`/`@AllArgsConstructor`，Lombok 白名单仅 `@Getter/@Setter/@RequiredArgsConstructor/@Slf4j/@Builder`（backend A.1-12）；禁止裸 `System.out`/`printStackTrace`（backend A.1-11）；禁止全限定类名声明（backend A.1-13）；禁止 @Deprecated API（backend A.1-14）；业务异常一律 RuntimeException 体系（backend A.1-6）。
4. **常量与枚举**：常量进 `constants/`（`public final static` + 私有构造器），枚举进 `enum/` 且携带 code 的必须实现 code↔enum 双向映射（backend A.2-6/7）；错误码枚举 `<模块助记>-<4 位数字>`、定义于各模块 api 包、全项目唯一（backend A.3-4）。
5. **配置红线**：业务配置统一 `fuyun.*` 前缀；敏感配置一律 `${ENV}` 占位，yml/代码/compose 出现明文密钥即红线违规（backend A.2-5）；禁止 @Value 散落（backend A.2-2）。
6. **模块与目录**：20 模块名/package/schema 与 §1 清单一字不差；模块内包目录严格按 backend C.3/B.1 包职责表归位（§2.4 所列 17+impl），禁止散落；fuyun-common 不依赖业务模块；空模块禁止空实现类堆积（P0 计划 §1-PR-1）。
7. **前端编码**：SFC 统一 `<script setup lang="ts">`（web A.1-1）；tsconfig 全应用 strict、禁 any（web A.1-4）；路由组件全懒加载（web B.3-2）；共享包引用一律 `workspace:*`（web B.2-5）；共享依赖版本进 catalog（web C.4）；`VITE_` 前缀一律视为公开信息，禁放任何密钥（web A.2-2）；element-plus 仅 workstation（web B.2-8）；产物目录保持默认 dist 禁改名（web A.2-5）。
8. **测试与死代码**：禁止凑覆盖率的空断言；测试命名表达业务意图；未引用 import、注释掉的代码块、孤儿文件零容忍；失效旧测试直接删除（全局规范 §四）。
9. **流程红线**：main 直推被拒——feature 分支 + PR + 五检查全绿；先记 `CHANGELOG.md` 再改；`ci.yml` job name 与分支保护精确对齐禁止改名（P0 计划 §0-3、ci.yml 头注）。

---

## 9. 待裁决（spec 真实缺口，非 W-3 已记录项；建议按默认方案落地并在 PR 描述申报、定稿表回补）

以下四项均属「宪法/定稿表未锁定版本号，但 PR-1 落盘必需」的缺口，不是规格冲突；简报已给出默认方案，实现专员按默认方案执行，同时**必须**在 PR 描述中逐项申报，由宪法修订（先记 CHANGELOG）回补 `docs/language/2026-09-07-技术栈选型.md` 与对应子宪法 C.2 版本表：

| # | 缺口 | 默认方案（简报已按此写规格） |
| --- | --- | --- |
| 1 | Maven 核心插件版本表外未锁：`maven-compiler-plugin`、`maven-surefire-plugin`、`maven-failsafe-plugin`（不继承 starter-parent 后必须显式锁版本） | 取 `spring-boot-starter-parent:3.5.16` 的 pluginManagement 对应版本原值锁定（Maven Central 直读该 POM），保持与 Boot 3.5.16 工具链一致；`spring-boot-maven-plugin` 用 `${spring-boot.version}` 无缺口 |
| 2 | `lombok-mapstruct-binding` 版本表外 | `0.2.0`（MapStruct 官方文档标准搭配值，多年稳定） |
| 3 | `@vitejs/plugin-vue` 版本表外（Vite 8 构建与 Vitest 编译 SFC 必需配套） | 取其官方声明兼容 Vite 8.2.2 的最新稳定行，pnpm registry 核实后锁定进 catalog |
| 4 | `dayjs` 版本表外（workstation 显式依赖，element-plus 官方明示的 peer 坑；TASK.md T-R4-4 已登记调研） | 以 element-plus 2.14.5 实际 peer/依赖的 dayjs 版本为准（安装后 `pnpm why dayjs` 核实）显式声明，T-R4-4 结论回填 |

另有两处**简报内已定案的实现口径**（非冲突，供评审知晓）：① backend A.4.1-4 的「公共 schema」宪法未命名，取 PG 默认 `public` 承载 `flyway_schema_history`；② 定稿报告 §6.2「建业务库」由 compose `POSTGRES_DB` 环境变量承担（官方镜像以该库为 initdb 脚本执行上下文），`01-init.sql` 只做 `CREATE EXTENSION`，避免双重建库。`docs/specs/modules/README.md` §3「响应统一 {code,message,data,traceId}」与 backend A.3-2 ProblemDetail 无 envelope 的表述差异**已有台账裁决**（TASK.md T-R2-3 已闭环，走 ProblemDetail 路线），shared 类型按 ProblemDetail 设计，不另立裁决。
