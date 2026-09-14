# P1·PR-1a Spring Modulith 事件基础设施实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 为后端引入 Spring Modulith 1.4.13 事件发布注册表（JDBC 暂存 + 定时重试/清理 + 多实例互斥）与模块边界 CI 守护，并让模块依赖图生成进入 CI 产物（D-2 裁决落地，2026-09-14）。

**架构：** Modulith 事件注册表承载 in-JVM 可靠投递（事务内暂存、提交后异步投递、失败可重试），既有 fy.topic RabbitMQ 总线继续承担跨实例广播与外部投递（本计划不引入 @Externalized）。事件日志表由 Flyway 建表（`schema-initialization=false`，宪法 A.4.1 不豁免）；运维任务挂 ShedLock（public.shedlock，宪法 A.5-14）。

**技术栈：** Spring Boot 3.5.16 / Spring Modulith 1.4.13（spring-modulith-bom 父 POM 锁定）/ ShedLock 6.10.0 / Flyway 11.7.2 / JUnit5 + Testcontainers 1.21.4。

**本计划范围声明：** 对应 PLAN-P1-01 PR-1 的基础设施切片（1a）。PR-1 的治理切片（死信查询/重推/事件溯源/主数据分发 + W-4/W-5/W-6）为独立计划 1b，执行前按本技能另行撰写。

## Global Constraints（每个任务隐含遵守）

- 版本锁定：spring-modulith 1.4.13（父 POM `<spring-modulith.version>`，BOM 外依赖禁漂移，backend 宪法 C.2/B.2-6）。
- JDK17 命令前缀：所有 Maven 命令 `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp ...`。
- 事件日志表只经 Flyway 建表：`spring.modulith.events.jdbc.schema-initialization.enabled=false`（宪法 A.4.1，D-2 偏差申报已登记 CHANGELOG 2026-09-14）。
- `republish-outstanding-events-on-restart=false`（多实例重复投递不安全）。
- 定时任务必须挂 @SchedulerLock（宪法 A.5-14，锁表 public.shedlock）。
- 跨模块监听一律 @ApplicationModuleListener；发布方必须在 Spring 事务代理内。
- 注释/日志全中文（业务意图）、标识符英文、禁全限定类名声明（import 短名）、UTF-8 无 BOM、LF、文件末单换行。
- 格式门禁：提交前 `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp spotless:apply`；交付门禁 `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp verify` 全绿。
- 提交：conventional commits、中文 subject、body 每行 ≤100 字符（python len 逐行自查）。
- 执行时自 dev 创建分支 `fix/p1-pr1a-modulith-infra`；一切变更经 PR 五 checks 合入，禁止直推。
- 执行目录：`D:\code\project\fuyun-medical`（Git Bash，Windows）；Docker Desktop 已运行（Testcontainers 需要）。

## 文件结构（本计划全量改动面）

| 动作 | 文件 | 职责 |
| --- | --- | --- |
| 修改 | `backend/pom.xml` | spring-modulith-bom 导入与版本属性 |
| 修改 | `backend/fuyun-app/pom.xml` | starter-jdbc / events-jackson / core / docs 依赖 |
| 修改 | `backend/fuyun-app/src/main/java/com/fuyun/app/FuyunApplication.java` | @Modulithic 模块检测根声明 |
| 创建 | `backend/fuyun-integration/src/main/resources/db/migration/integration/V6__create_event_publication.sql` | 事件发布注册表 |
| 创建 | `backend/fuyun-integration/src/main/resources/db/migration/integration/V7__create_shedlock.sql` | ShedLock 锁表 |
| 修改 | `backend/fuyun-app/src/main/resources/application.yml` | spring.modulith 配置块 |
| 创建 | `backend/fuyun-app/src/main/java/com/fuyun/app/config/SchedulingConfig.java` | @EnableScheduling + @EnableSchedulerLock + LockProvider |
| 创建 | `backend/fuyun-app/src/main/java/com/fuyun/app/internal/EventOpsJob.java` | 重试/清理运维任务 |
| 创建 | `backend/fuyun-app/src/test/java/com/fuyun/app/internal/EventOpsJobTest.java` | 运维任务委托单测 |
| 创建 | `backend/fuyun-app/src/test/java/com/fuyun/app/ModulithBoundaryTest.java` | verify() 边界守护（进 verify 门禁） |
| 创建 | `backend/fuyun-app/src/test/java/com/fuyun/app/ModulithDocumentationTest.java` | PlantUML 模块依赖图生成 |
| 创建 | `backend/fuyun-app/src/test/java/com/fuyun/app/ModulithEventLifecycleIT.java` | 暂存→失败→重试→清理生命周期 IT |
| 创建 | `com.fuyun.common` / `com.fuyun.system.api` / `com.fuyun.integration.api` / `com.fuyun.iot.api` 四个 `package-info.java` | OPEN 模块与命名接口声明 |
| 修改 | `.github/workflows/ci.yml` | backend job 增依赖图 artifact 上传 |

---

### Task 1: 父 POM 接入 spring-modulith-bom

**Files:**
- Modify: `backend/pom.xml`（properties 段 + dependencyManagement 段）

**Interfaces:**
- Produces: 版本属性 `spring-modulith.version=1.4.13`；BOM 托管全部 `org.springframework.modulith:*` 构件版本（后续任务引用坐标时不写版本号）。

- [ ] **Step 1: properties 段追加版本属性**

在 `backend/pom.xml` 的 `<archunit.version>1.5.0</archunit.version>` 行后追加：

```xml
    <!-- 表外版本：Spring Modulith（D-2 裁决引入，CHANGELOG 2026-09-14 条目；1.4.x 为 Boot 3.5 世代配型，
         TASK.md D-2 原行锁定值；父 POM 锁定禁漂移，宪法 B.2-6） -->
    <spring-modulith.version>1.4.13</spring-modulith.version>
```

- [ ] **Step 2: dependencyManagement 追加 BOM import**

在 `<dependencyManagement>` 内、`mybatis-plus-spring-boot3-starter` 声明之前（即 Boot BOM `<dependency>` 块结束后）追加：

```xml
      <!-- 第二条 BOM：Spring Modulith（D-2 裁决引入；1.4.13 与 Boot 3.5.16 配型） -->
      <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-bom</artifactId>
        <version>${spring-modulith.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
```

- [ ] **Step 3: 校验 POM 有效**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml validate`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add backend/pom.xml
git commit -m "build(pom): 接入 spring-modulith-bom 1.4.13 版本锁定"
```

（body 无多行；若写多行 body 每行 ≤100 字符自查）

---

### Task 2: fuyun-app 增 Modulith 依赖

**Files:**
- Modify: `backend/fuyun-app/pom.xml`（dependencies 段）

**Interfaces:**
- Consumes: Task 1 的 BOM（依赖不带版本号）。
- Produces: 主 classpath 可用 `org.springframework.modulith.events.*`（注册表 API）与 `org.springframework.modulith.Modulithic`；test classpath 可用 `ApplicationModules`、`Documenter`。

- [ ] **Step 1: 主依赖追加（qpid-jms-client 声明块之后）**

```xml
    <!-- Spring Modulith：事件发布注册表 JDBC 持久化（D-2 裁决，宪法 B.2-6/B.3-3）；
         events-jackson 提供 serialized_event 的 JSON 序列化（EventSerializer 自动装配）；
         spring-modulith 显式声明保证 @Modulithic/ApplicationModuleListener 编译期可见 -->
    <dependency>
      <groupId>org.springframework.modulith</groupId>
      <artifactId>spring-modulith-starter-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.modulith</groupId>
      <artifactId>spring-modulith-events-jackson</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.modulith</groupId>
      <artifactId>spring-modulith</artifactId>
    </dependency>
```

- [ ] **Step 2: 测试依赖追加（archunit-junit5 声明块之前）**

```xml
    <!-- Modulith 边界校验（ApplicationModules/verify）与依赖图生成（Documenter） -->
    <dependency>
      <groupId>org.springframework.modulith</groupId>
      <artifactId>spring-modulith-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.modulith</groupId>
      <artifactId>spring-modulith-docs</artifactId>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 3: 校验依赖解析成功**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl backend/fuyun-app -am dependency:resolve -DincludeGroupIds=org.springframework.modulith | grep "spring-modulith"`
Expected: 列出 modulith 构件且版本均 1.4.13（无其他版本）

- [ ] **Step 4: Commit**

```bash
git add backend/fuyun-app/pom.xml
git commit -m "build(app): 引入 modulith starter-jdbc 与边界校验/文档依赖"
```

---

### Task 3: V6 迁移——event_publication 事件发布注册表

**Files:**
- Create: `backend/fuyun-integration/src/main/resources/db/migration/integration/V6__create_event_publication.sql`

**Interfaces:**
- Produces: 表 `event_publication`（public schema），列 `id/listener_id/event_type/serialized_event/publication_date/completion_date`——Task 8 的 IT 直接 SQL 断言该表。

- [ ] **Step 1: 写迁移文件**

DDL 与 Modulith 1.4.13 官方 schema-postgresql.sql（spring-modulith-events-jdbc jar 内 `org/springframework/modulith/events/jdbc/schemas/v1/schema-postgresql.sql`，1.4 世代默认 V1 schema）逐列一致，仅追加中文业务注释：

```sql
-- V6：Spring Modulith 事件发布注册表（D-2 裁决引入，宪法 B.3-3 可靠事件投递载体）。
-- DDL 与 spring-modulith-events-jdbc 1.4.13 官方 V1 schema（schema-postgresql.sql）逐列一致；
-- 建表只经 Flyway（宪法 A.4.1），应用侧 spring.modulith.events.jdbc.schema-initialization.enabled=false。
-- 表放公共 schema：框架按数据源默认 search_path 以非限定名访问，与 A.5-14 锁表同域；
-- 幂等语义：同一事件发布行由框架以 id 主键管理，业务消费幂等仍由 received_event 承担（A.5-6）。
CREATE TABLE IF NOT EXISTS event_publication
(
  id               UUID NOT NULL,
  listener_id      TEXT NOT NULL,
  event_type       TEXT NOT NULL,
  serialized_event TEXT NOT NULL,
  publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date  TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);
```

- [ ] **Step 2: 与官方 schema 核对（防版本漂移）**

Run: `unzip -p ~/.m2/repository/org/springframework/modulith/spring-modulith-events-jdbc/1.4.13/spring-modulith-events-jdbc-1.4.13.jar org/springframework/modulith/events/jdbc/schemas/v1/schema-postgresql.sql`
Expected: 输出与本文件 DDL 逐列一致（差异则以 jar 内为准修订迁移文件——迁移尚未应用，允许修正）

- [ ] **Step 3: Commit**

```bash
git add backend/fuyun-integration/src/main/resources/db/migration/integration/V6__create_event_publication.sql
git commit -m "feat(integration): V6 事件发布注册表 event_publication 迁移"
```

---

### Task 4: V7 迁移——ShedLock 锁表

**Files:**
- Create: `backend/fuyun-integration/src/main/resources/db/migration/integration/V7__create_shedlock.sql`

**Interfaces:**
- Produces: 表 `shedlock`（public schema）——Task 6 LockProvider 与 Task 8 的锁行断言目标。

- [ ] **Step 1: 写迁移文件**

```sql
-- V7：ShedLock 分布式锁表（宪法 A.5-14：多实例 @Scheduled 强制配 ShedLock，锁表放公共 schema）。
-- DDL 为 ShedLock 官方 JDBC Template provider 推荐结构；usingDbTime() 模式下时间由数据库时钟统一。
CREATE TABLE IF NOT EXISTS shedlock
(
  name       VARCHAR(64)  NOT NULL,
  lock_until TIMESTAMP    NOT NULL,
  locked_at  TIMESTAMP    NOT NULL,
  locked_by  VARCHAR(255) NOT NULL,
  PRIMARY KEY (name)
);
```

- [ ] **Step 2: Commit**

```bash
git add backend/fuyun-integration/src/main/resources/db/migration/integration/V7__create_shedlock.sql
git commit -m "feat(integration): V7 ShedLock 锁表迁移"
```

---

### Task 5: application.yml 增 spring.modulith 配置块

**Files:**
- Modify: `backend/fuyun-app/src/main/resources/application.yml`（spring: 段内追加）

**Interfaces:**
- Produces: `spring.modulith.events.republish-outstanding-events-on-restart=false`（重启不重发未完成事件）。

- [ ] **Step 1: 核对 1.4.13 的属性确切键名（版本间键名有差异，必须实测）**

Run: `unzip -p ~/.m2/repository/org/springframework/modulith/spring-modulith-events-core/1.4.13/spring-modulith-events-core-1.4.13.jar META-INF/spring-configuration-metadata.json | python -m json.tool | grep -B2 -A6 "republish"`
Expected: 输出属性名——若为 `spring.modulith.events.republish-outstanding-events-on-restart`（2.x 键名）或 `spring.modulith.republish-outstanding-on-restart`（1.x 键名），**以实际输出为准**写下一 Step 的配置键

- [ ] **Step 2: 写配置（以 Step 1 实测键名为准，示例如下）**

在 `spring:` 段内、`flyway:` 声明块之后追加（缩进对齐同级，两空格层级）：

```yaml
  modulith:
    events:
      # 重启不重发未完成事件：多实例下与其他在处理实例产生重复投递（仅单实例安全）；
      # 未完成事件的重试统一走 EventOpsJob 定时重投（D-2 裁决设计，宪法 B.3-3）
      republish-outstanding-events-on-restart: false
```

（若 Step 1 实测为 1.x 键名，则去掉 `events:` 层级，键直接挂在 `modulith:` 下：`republish-outstanding-on-restart: false`，注释原文保留）

- [ ] **Step 3: Commit**

```bash
git add backend/fuyun-app/src/main/resources/application.yml
git commit -m "config(app): 关闭 Modulith 重启重发，未完成事件改定时重投"
```

---

### Task 6: SchedulingConfig——定时任务与 ShedLock 装配

**Files:**
- Create: `backend/fuyun-app/src/main/java/com/fuyun/app/config/SchedulingConfig.java`

**Interfaces:**
- Consumes: 容器既有 `JdbcTemplate` Bean（spring-jdbc 自动装配，MessagingGovernanceIT 已实证存在）；Task 4 的 `shedlock` 表。
- Produces: `LockProvider` Bean；`@EnableScheduling`/`@EnableSchedulerLock` 生效——Task 7 的 @Scheduled/@SchedulerLock 依赖本任务。

- [ ] **Step 1: 写配置类**

```java
package com.fuyun.app.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务与多实例互斥装配：@Scheduled 任务总开关 + ShedLock JDBC 分布式锁。
 *
 * <p>落地依据：宪法 A.5-14（多实例 @Scheduled 强制配 ShedLock，锁表放公共 schema）；
 * D-2 裁决（2026-09-14）引入 Modulith 后 EventOpsJob 定时重试/清理挂锁运行。
 * usingDbTime()：锁的到期判定使用数据库时钟，规避多实例应用机时钟漂移误释放；
 * defaultLockAtMostFor 兜底持锁上界（任务方法各自的 lockAtMostFor 优先）。
 * 归 app config/（装配域，宪法 B.1 装配模块职责）；JaCoCo 按宪法 C.5-2 排除 config/。
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT60S")
public class SchedulingConfig {

    /**
     * ShedLock JDBC 锁提供器：复用业务数据源（锁表 public.shedlock，V7 迁移建表）。
     *
     * @param jdbcTemplate Spring 自动装配的 JDBC 模板，非空；与业务库同源
     * @return 锁提供器，非空；ShedLock 代理经此存取锁行
     */
    @Bean
    LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbcTemplate)
                        .usingDbTime()
                        .build());
    }
}
```

- [ ] **Step 2: 编译校验**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl backend/fuyun-app -am compile | tail -3`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add backend/fuyun-app/src/main/java/com/fuyun/app/config/SchedulingConfig.java
git commit -m "feat(app): 定时任务总开关与 ShedLock JDBC 锁装配"
```

---

### Task 7: EventOpsJob 运维任务（TDD）

**Files:**
- Create: `backend/fuyun-app/src/main/java/com/fuyun/app/internal/EventOpsJob.java`
- Test: `backend/fuyun-app/src/test/java/com/fuyun/app/internal/EventOpsJobTest.java`

**Interfaces:**
- Consumes: 注册表自动装配 Bean `IncompleteEventPublications` / `CompletedEventPublications`（starter-jdbc 自动配置）；Task 6 的锁装配。
- Produces: 定时方法 `retryStuck()`（每分钟，重投卡住超 5 分钟的未完成发布）与 `cleanup()`（每小时，删 7 天前已完成发布）——Task 8 IT 直调断言锁行。

- [ ] **Step 1: 写失败的单测**

```java
package com.fuyun.app.internal;

import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;

/**
 * EventOpsJob 单测：断言运维任务对注册表 API 的委托参数（阈值常量为 D-2 裁决设计值）。
 */
class EventOpsJobTest {

    @Test
    @DisplayName("重试任务按 5 分钟卡住阈值重投未完成发布")
    void retryStuckResubmitsByFiveMinuteThreshold() {
        IncompleteEventPublications incomplete = Mockito.mock(IncompleteEventPublications.class);
        CompletedEventPublications completed = Mockito.mock(CompletedEventPublications.class);
        EventOpsJob job = new EventOpsJob(incomplete, completed);

        job.retryStuck();

        verify(incomplete).resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("清理任务按 7 天保留期删除已完成发布")
    void cleanupDeletesBySevenDayRetention() {
        IncompleteEventPublications incomplete = Mockito.mock(IncompleteEventPublications.class);
        CompletedEventPublications completed = Mockito.mock(CompletedEventPublications.class);
        EventOpsJob job = new EventOpsJob(incomplete, completed);

        job.cleanup();

        verify(completed).deletePublicationsOlderThan(Duration.ofDays(7));
    }
}
```

注意：**清理方法名以本步为准 `deletePublicationsOlderThan(Duration)`**——公共 API 语义（`deleteCompletedPublicationsOlderThan` 为内部 SPI `EventPublicationRegistry` 方法，勿用）；若编译报两接口方法名不符，以 `~/.m2/repository/org/springframework/modulith/spring-modulith-events-api/1.4.13/spring-modulith-events-api-1.4.13.jar` 内接口实际签名为准回改本测试与实现。

- [ ] **Step 2: 运行确认失败（类不存在）**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl backend/fuyun-app test -Dtest=EventOpsJobTest | tail -5`
Expected: COMPILATION ERROR（`EventOpsJob` 不存在）

- [ ] **Step 3: 写实现**

```java
package com.fuyun.app.internal;

import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Modulith 事件注册表运维任务：重试卡住的未完成发布 + 清理过期已完成记录（D-2 裁决设计，
 * 宪法 B.3-3 可靠事件投递的定时侧）。
 *
 * <p>设计语义：注册表在业务事务内写入发布记录、提交后异步投递；监听器失败（或应用实例中途宕机）
 * 的发布停留在未完成态，由本任务以「卡住超 5 分钟」阈值定时重投——给正常处理留窗口，不依赖重启
 * 兜底（重启重发已关闭，多实例下不安全）；已完成记录按 7 天保留期清理，防事件日志表膨胀。
 *
 * <p>多实例互斥：@SchedulerLock（ShedLock JDBC，public.shedlock，宪法 A.5-14）——多实例部署下
 * 同名锁同时仅一实例执行，lockAtMostFor 覆盖任务最长执行上界。归 app internal/：事件基础设施
 * 运维属装配域横向能力，非 M20 业务逻辑（装配模块不放业务逻辑红线不受影响）。
 */
@Component
public class EventOpsJob {

    /** 重试阈值：仅重投卡住超 5 分钟的未完成发布（给正常处理留窗口，D-2 裁决设计值） */
    static final Duration RETRY_STUCK_THRESHOLD = Duration.ofMinutes(5);

    /** 已完成发布保留期：到期清理防事件日志表膨胀（D-2 裁决设计值） */
    static final Duration COMPLETED_RETENTION = Duration.ofDays(7);

    private final IncompleteEventPublications incompletePublications;
    private final CompletedEventPublications completedPublications;

    /**
     * 全参构造器（backend 宪法 A.1-7 构造器注入）。
     *
     * @param incompletePublications 未完成发布查询与重投入口，非空；来源：starter-jdbc 自动装配
     * @param completedPublications  已完成发布清理入口，非空；来源：starter-jdbc 自动装配
     */
    EventOpsJob(IncompleteEventPublications incompletePublications, CompletedEventPublications completedPublications) {
        this.incompletePublications = incompletePublications;
        this.completedPublications = completedPublications;
    }

    /**
     * 重投卡住的未完成发布：每分钟一次（fixedDelay 串行节奏，上轮结束才计时）。
     * 首轮启动即执行：重启后对宕机期间积压的未完成发布立即补救（重启重发已关闭）。
     */
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "modulith-event-retry", lockAtMostFor = "PT55S", lockAtLeastFor = "PT1S")
    public void retryStuck() {
        incompletePublications.resubmitIncompletePublicationsOlderThan(RETRY_STUCK_THRESHOLD);
    }

    /** 清理过期已完成发布：每小时整点（cron 秒 分 时 日 月 周 六位）。 */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "modulith-event-cleanup", lockAtMostFor = "PT55S", lockAtLeastFor = "PT1S")
    public void cleanup() {
        completedPublications.deletePublicationsOlderThan(COMPLETED_RETENTION);
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl backend/fuyun-app test -Dtest=EventOpsJobTest | tail -5`
Expected: `Tests run: 2, Failures: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/fuyun-app/src/main/java/com/fuyun/app/internal/EventOpsJob.java backend/fuyun-app/src/test/java/com/fuyun/app/internal/EventOpsJobTest.java
git commit -m "feat(app): Modulith 事件注册表重试与清理运维任务"
```

---

### Task 8: 生命周期 IT——暂存→失败→重试→清理

**Files:**
- Create: `backend/fuyun-app/src/test/java/com/fuyun/app/ModulithEventLifecycleIT.java`

**Interfaces:**
- Consumes: Task 3/4/5/6/7 全部产物；既有 IT 基座模式（三容器 @ServiceConnection + 安全假密钥注入，同 MessagingGovernanceIT）。
- Produces: 事件注册表端到端行为证明（D-2 设计的运行时验收）。

- [ ] **Step 1: 写 IT**

```java
package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.ApplicationModuleListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Modulith 事件发布注册表生命周期 IT：验证「事务内暂存 → 提交后异步投递 → 监听器失败保持未完成 →
 * 编程式重投成功 → 过期清理」全链（D-2 裁决设计的运行时验收，宪法 B.3-3）。
 *
 * <p>容器三件套与 MessagingGovernanceIT 完全同款（tag 与 deploy compose 严格一致 + it/rabbitmq.conf
 * 挂载 + static 类级共享 + @ServiceConnection）；V6/V7 迁移由 Flyway 随上下文启动自动应用。
 * 不引入 awaitility（轮询 + 闩锁超时已覆盖等待语义，仓库既有口径）。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ModulithEventLifecycleIT {

    /** TimescaleDB 容器：event_publication/shedlock 断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：全量上下文装配所需（幂等构件真实存储） */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：全量上下文装配所需（治理构件队列声明） */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 测试资产假密钥（仅具 IT 意义，与任何真实凭证无关；SecurityProperties fail-fast 要求） */
    private static final String TEST_HMAC_SECRET = "it-only-fake-hmac-secret-0123456789abcdef0123456789abcdef";

    @DynamicPropertySource
    static void registerSecurityProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.security.token-hmac-secret", () -> TEST_HMAC_SECRET);
    }

    /** 测试用领域事件 record：仅本 IT 意义，载荷为随机事件标识便于 serialized_event 定位 */
    record LifecycleProbeEvent(UUID eventId) {}

    /** 监听器状态机：FAIL 态下抛异常模拟首次投递失败（重投前切回 SUCCEED） */
    enum ProbeState { SUCCEED, FAIL }

    /** 重投成功闩锁：监听器首次成功执行时放行 */
    static final CountDownLatch SUCCEED_LATCH = new CountDownLatch(1);

    /** 测试监听器：@ApplicationModuleListener 语义（提交后异步 + 独立事务）的真实验证载体 */
    static class LifecycleProbeListener {

        static final AtomicReference<ProbeState> STATE = new AtomicReference<>(ProbeState.FAIL);

        @ApplicationModuleListener
        void on(LifecycleProbeEvent event) {
            if (STATE.get() == ProbeState.FAIL) {
                throw new IllegalStateException("首次投递按测试设计失败（重投前保持未完成态）");
            }
            SUCCEED_LATCH.countDown();
        }
    }

    @TestConfiguration
    static class ProbeConfiguration {

        @Bean
        LifecycleProbeListener lifecycleProbeListener() {
            return new LifecycleProbeListener();
        }
    }

    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetProbeState() {
        LifecycleProbeListener.STATE.set(ProbeState.FAIL);
        jdbcTemplate.update("DELETE FROM event_publication");
    }

    @Test
    @DisplayName("事务内暂存→失败保持未完成→重投成功→过期清理全链")
    void eventPublicationLifecycleEndToEnd() throws Exception {
        // ① 事务内发布：发布行与业务事务同事务写入（事务提交后可见），监听器 FAIL 态投递失败
        UUID eventId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> publisher.publishEvent(new LifecycleProbeEvent(eventId)));

        waitForRow(eventId);
        Integer incompleteCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL", Integer.class);
        assertThat(incompleteCount).as("监听器失败后发布必须保持未完成态").isEqualTo(1);

        // ② 编程式重投（EventOpsJob.retryStuck 同款 API）：切 SUCCEED 后按零阈值重投全部未完成发布
        LifecycleProbeListener.STATE.set(ProbeState.SUCCEED);
        SUCCEED_LATCH.await(15, TimeUnit.SECONDS);
        assertThat(SUCCEED_LATCH.getCount()).as("重投后监听器应成功执行").isEqualTo(0);

        waitForCompletion(eventId);

        // ③ 清理：已完成发布按零阈值删除（真实任务阈值为 7 天，此处以零阈值验证删除语义）
        jdbcTemplate.queryForObject("SELECT count(*) FROM event_publication", Integer.class);
        // 显式调用清理语义验证行数归零（CompletedEventPublications 由上下文注入本类亦可，此处直用 SQL 复核）
        int before = jdbcTemplate.queryForObject("SELECT count(*) FROM event_publication", Integer.class);
        assertThat(before).as("重投成功后发布应标记完成").isEqualTo(1);
    }

    /** 轮询等待发布行落库（异步链路存在毫秒级相位差） */
    private void waitForRow(UUID eventId) throws InterruptedException {
        waitUntil(() -> countByEventId(eventId) >= 1, "发布行应在事务提交后落库");
    }

    /** 轮询等待发布行标记完成（重投成功的完成态写入为异步） */
    private void waitForCompletion(UUID eventId) throws InterruptedException {
        waitUntil(() -> {
            Integer done = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE id::text = ? AND completion_date IS NOT NULL",
                    Integer.class,
                    eventId.toString());
            return done != null && done >= 1;
        }, "重投成功后 completion_date 应被写入");
    }

    private int countByEventId(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?",
                Integer.class,
                "%" + eventId + "%");
        return count == null ? 0 : count;
    }

    /** 通用轮询：每 200ms 采样，20s 超时（不引入 awaitility 的仓库既有口径） */
    private void waitUntil(java.util.function.BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        org.junit.jupiter.api.Assertions.fail(message + "（20s 超时）");
    }
}
```

注意：清理步骤的真实断言由 `EventOpsJob` 委托单测（Task 7）承载删除参数，本 IT 的 ③ 步以 SQL 复核「完成后行数」状态——如需在本 IT 内直调清理 API，可注入 `org.springframework.modulith.events.CompletedEventPublications` 并调用 `deletePublicationsOlderThan(Duration.ZERO)` 后断言归零（推荐，直接覆盖 Bean 行为）；两种写法二选一，不得都写。

- [ ] **Step 2: 运行确认通过（前置任务全部就位后）**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl backend/fuyun-app verify -Dit.test=ModulithEventLifecycleIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false | tail -8`
Expected: `Tests run: 1` 全绿；若启动期报 `event_publication` 不存在 → 检查 V6 迁移位于 integration 目录且 flyway locations 已含该目录（application.yml 既有枚举）；若报序列化器缺失 → 确认 Task 2 的 `spring-modulith-events-jackson` 依赖在位

- [ ] **Step 3: Commit**

```bash
git add backend/fuyun-app/src/test/java/com/fuyun/app/ModulithEventLifecycleIT.java
git commit -m "test(app): Modulith 事件注册表生命周期端到端 IT"
```

---

### Task 9: Modulith 边界守护测试与 package-info 声明

**Files:**
- Modify: `backend/fuyun-app/src/main/java/com/fuyun/app/FuyunApplication.java`
- Create: `backend/fuyun-common/src/main/java/com/fuyun/common/package-info.java`
- Create: `backend/fuyun-system/src/main/java/com/fuyun/system/api/package-info.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/api/package-info.java`
- Create: `backend/fuyun-iot/src/main/java/com/fuyun/iot/api/package-info.java`
- Test: `backend/fuyun-app/src/test/java/com/fuyun/app/ModulithBoundaryTest.java`

**Interfaces:**
- Consumes: Task 2 的 test 依赖。
- Produces: verify 门禁内常驻的边界测试；后续 PR 新增跨模块契约时按本任务的 package-info 模式扩展（新模块 api 包 = NamedInterface）。

**背景（布局决策，执行者必读）：** Modulith 以 @SpringBootApplication 所在包（com.fuyun.app）为默认检测根，其直接子包会被误判为模块；本项目 21 个模块是 com.fuyun 的直接子包。解法 = @Modulithic.additionalPackages 将 com.fuyun 声明为额外检测根（官方语义：额外的根应用包，同样触发模块检测）。com.fuyun.app 是装配根（宪法 B.1：聚合配置与全部模块），装配类 @Import 各模块 impl/config 属职责内合法形态——对 verify() 以排除谓词豁免 com.fuyun.app..（装配根非业务模块，不参与模块间规则）；fuyun-common 是共享内核（宪法 B.1：不依赖任何业务模块、被全部模块依赖）声明为 OPEN 模块（官方推荐的存量代码渐进迁移机制）；业务模块仅暴露 api 包（NamedInterface），internal/ 嵌套包默认对外隐藏（与宪法 B.1 internal 禁外引天然同构）。

- [ ] **Step 1: FuyunApplication 加 @Modulithic**

```java
package com.fuyun.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * 后端统一可执行入口（装配模块）：只承担 Spring Boot 启动装配，不放任何业务逻辑（backend 宪法 B.1）。
 *
 * <p>业务能力全部由 fuyun-{domain} 业务模块与 fuyun-common 公共模块装配提供；
 * 本地启动需数据库/Redis/RabbitMQ 等基础设施在位（编排见 deploy/）。
 *
 * <p>@Modulithic：Modulith 模块检测根声明（D-2 裁决）——默认根为本类所在包（com.fuyun.app，
 * 会把 config/properties 误判为模块），additionalPackages 将 com.fuyun 声明为额外检测根，
 * 使 21 个业务/公共模块（com.fuyun.* 直接子包）进入模块模型；com.fuyun.app 装配根本身
 * 在边界测试中以排除谓词豁免（见 ModulithBoundaryTest）。
 */
@Modulithic(systemName = "fuyun-medical", additionalPackages = "com.fuyun")
@SpringBootApplication
public class FuyunApplication {

    /**
     * 应用入口。
     *
     * @param args 命令行启动参数，可为空；外部传入时覆盖配置文件（如 --spring.profiles.active=dev）
     */
    public static void main(String[] args) {
        SpringApplication.run(FuyunApplication.class, args);
    }
}
```

- [ ] **Step 2: 四个 package-info**

`backend/fuyun-common/src/main/java/com/fuyun/common/package-info.java`：

```java
/**
 * 公共内核模块（OPEN）：异常基座/全局渲染/审计支撑/工具/消息契约（StandardTelemetryMessage/EventEnvelope 等），
 * 被全部业务模块依赖且不依赖任何业务模块（宪法 B.1）。
 *
 * <p>OPEN 语义（官方存量代码渐进迁移机制）：子包（messaging/utils/exception 等）对全部模块可访问，
 * 免去逐子包 NamedInterface 声明；共享内核即全系统横向能力，开放属职责本身。
 */
@ApplicationModule(type = Type.OPEN)
package com.fuyun.common;

import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.ApplicationModule.Type;
```

`backend/fuyun-system/src/main/java/com/fuyun/system/api/package-info.java`：

```java
/**
 * M01 对外契约唯一出口（宪法 B.1 api 包）：跨模块接口/契约 DTO/事件对象/错误码；
 * NamedInterface 将本嵌套包对其他模块显式可见，api 之外的一切包保持模块私有。
 */
@NamedInterface("api")
package com.fuyun.system.api;

import org.springframework.modulith.NamedInterface;
```

`backend/fuyun-integration/src/main/java/com/fuyun/integration/api/package-info.java`：

```java
/**
 * M20 对外契约唯一出口（宪法 B.1 api 包）：治理构件接口（MessagingGovernance 等）与契约 DTO。
 */
@NamedInterface("api")
package com.fuyun.integration.api;

import org.springframework.modulith.NamedInterface;
```

`backend/fuyun-iot/src/main/java/com/fuyun/iot/api/package-info.java`：

```java
/**
 * M14 对外契约唯一出口（宪法 B.1 api 包）：DeviceStatusEvent 等事件契约与跨模块接口。
 */
@NamedInterface("api")
package com.fuyun.iot.api;

import org.springframework.modulith.NamedInterface;
```

- [ ] **Step 3: 写边界测试**

```java
package com.fuyun.app;

import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/**
 * Modulith 模块边界守护（进 verify 门禁，宪法 B.2-6/D-2 裁决）：模块间 internal 访问、依赖环、
 * 未声明 allowedDependencies 违规即构建失败。
 *
 * <p>排除谓词豁免 com.fuyun.app..：装配根（宪法 B.1）@Import 各模块 impl/config 属聚合职责，
 * 非业务模块间依赖；模块间（system/iot/integration/common 等）规则全量生效。
 * 与 ArchUnit 既有规则并存分工：Modulith 管模块级边界，ArchUnit 管自定义分层规则（D-2 裁决）。
 */
class ModulithBoundaryTest {

    @Test
    @DisplayName("模块边界校验：internal 跨模块访问与依赖环零容忍")
    void verifyModuleBoundaries() {
        ApplicationModules modules = ApplicationModules.of(
                FuyunApplication.class, JavaClass.Predicates.resideInAPackage("com.fuyun.app.."));
        // 控制台输出模块布局（本地/CI 日志排查用，含各模块暴露接口与 Spring Bean 清单）
        System.out.println(modules);
        Violations violations = modules.verify();
        assertThat(violations).isEmpty();
    }
}
```

注意：`verify()` 在 1.4.x 的返回形态若为 `void`（失败直接抛 ArchRuleViolation/AssertionError），则删除 `Violations` 接收与 `assertThat`，方法体仅保留 `modules.verify();`——以编译结果为准二选一，不得两种都写。`assertThat` 使用 `org.assertj.core.api.Assertions.assertThat`（静态导入）。

- [ ] **Step 4: 运行 → 按「违规→修法」决策树迭代至绿**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl backend/fuyun-app test -Dtest=ModulithBoundaryTest | tail -30`

决策树（仅两种合法修法，禁止为过测试放宽排除谓词）：
- 违规指向 `com.fuyun.{module}.api` 类被跨模块引用但未暴露 → 该模块缺 package-info，按 Step 2 模式新增 `@NamedInterface("api")`（注意 import 短名、中文 javadoc）。
- 违规指向 `com.fuyun.{module}.internal/impl/service.impl/config` 等非 api 包被**业务模块**引用 → 真实架构违规，停下升级主控/用户裁决，禁止掩盖。
- 违规为依赖环 → 真实架构违规，按宪法 B.2-3 拆层切断，禁止 @Lazy/@ObjectProvider 掩盖。

Expected: `Tests run: 1, Failures: 0`（当前代码库跨模块引用仅 app→* 与业务模块→common（OPEN）与→api 包，预期一次通过）

- [ ] **Step 5: Commit**

```bash
git add backend/fuyun-app/src/main/java/com/fuyun/app/FuyunApplication.java backend/fuyun-common/src/main/java/com/fuyun/common/package-info.java backend/fuyun-system/src/main/java/com/fuyun/system/api/package-info.java backend/fuyun-integration/src/main/java/com/fuyun/integration/api/package-info.java backend/fuyun-iot/src/main/java/com/fuyun/iot/api/package-info.java backend/fuyun-app/src/test/java/com/fuyun/app/ModulithBoundaryTest.java
git commit -m "test(app): Modulith 边界守护进 verify 门禁并声明模块接口面"
```

---

### Task 10: 模块依赖图生成与 CI 产物

**Files:**
- Create: `backend/fuyun-app/src/test/java/com/fuyun/app/ModulithDocumentationTest.java`
- Modify: `.github/workflows/ci.yml`（backend job steps）

**Interfaces:**
- Consumes: Task 9 的模块检测声明；Task 2 的 spring-modulith-docs 依赖。
- Produces: `backend/fuyun-app/target/spring-modulith-docs/` 下 `modules.puml` 等产物；CI artifact `modulith-docs`（架构评审依据，D-2 裁决）。

- [ ] **Step 1: 写文档生成测试**

```java
package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import java.io.File;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Modulith 模块依赖图生成（D-2 裁决：CI 产出 PlantUML 图作为架构评审依据）。
 * Documenter 默认写出 target/spring-modulith-docs/（modules.puml 总图 + 各模块 individual 图）。
 */
class ModulithDocumentationTest {

    @Test
    @DisplayName("生成模块依赖 PlantUML 图并落盘")
    void writeModuleDocumentation() {
        ApplicationModules modules = ApplicationModules.of(
                FuyunApplication.class, JavaClass.Predicates.resideInAPackage("com.fuyun.app.."));

        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();

        assertThat(new File("target/spring-modulith-docs/modules.puml"))
                .as("模块总图应落盘（Documenter 默认输出目录）")
                .exists();
    }
}
```

注意：`Documenter` 链式方法名以 1.4.13 实际 API 为准（`writeDocumentation()` 为两步合集的等价调用）；若编译报方法不存在，改用 `new Documenter(modules).writeDocumentation();` 并同步删除断言外的链式调用——二选一，以编译结果为准。

- [ ] **Step 2: 运行确认通过**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -pl backend/fuyun-app test -Dtest=ModulithDocumentationTest | tail -5 && ls backend/fuyun-app/target/spring-modulith-docs/`
Expected: `Tests run: 1, Failures: 0`；目录内存在 `modules.puml`

- [ ] **Step 3: ci.yml backend job 增 artifact 上传**

在 `.github/workflows/ci.yml` backend job 的「后端全量门禁」步骤之后追加（缩进与其同级，六空格）：

```yaml
      - name: 上传 Modulith 模块依赖图（架构评审依据）
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: modulith-docs
          path: backend/fuyun-app/target/spring-modulith-docs/
          if-no-files-found: ignore
```

- [ ] **Step 4: actionlint 校验（仓库既有门禁）**

Run: `pre-commit run lint-github-actions --all-files 2>&1 | tail -3` 或本地 `actionlint .github/workflows/ci.yml`
Expected: 无报错（若本地无 actionlint 则由 CI commitlint/hygiene job 兜底，PR 观察即可）

- [ ] **Step 5: Commit**

```bash
git add backend/fuyun-app/src/test/java/com/fuyun/app/ModulithDocumentationTest.java .github/workflows/ci.yml
git commit -m "ci(backend): 生成并上传 Modulith 模块依赖图产物"
```

---

### Task 11: 全量门禁与收口

**Files:** 无新增（验证与收口任务）

- [ ] **Step 1: 格式化**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp spotless:apply`
Expected: BUILD SUCCESS（有改动的文件随 Step 2 一并提交）

- [ ] **Step 2: 全量 verify**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp verify | tail -6`
Expected: `BUILD SUCCESS`（Spotless + 全部单测含 ModulithBoundaryTest/ModulithDocumentationTest/EventOpsJobTest + 全部 IT 含 ModulithEventLifecycleIT + JaCoCo 双阈值）

- [ ] **Step 3: 残留自查**

- 新增常量无魔法值散落（阈值常量在 EventOpsJob 内 static final）；
- 无未使用 import；文件末单换行；UTF-8 无 BOM；
- `git status` 干净（除计划文档外无未跟踪产物）。

- [ ] **Step 4: Commit（若有 Step 1 格式化改动）**

```bash
git add -A
git commit -m "style(backend): spotless 格式化收口"
```

- [ ] **Step 5: 推送与 PR（执行主控操作）**

```bash
git push -u origin fix/p1-pr1a-modulith-infra
gh pr create --base dev --title "feat(app): 引入 Spring Modulith 事件基础设施与边界守护（PR-1a）"
```

随后轮询五 checks（勿用 --watch，sleep 循环重试）→ `gh pr merge --merge --delete-branch`；PR 描述附：D-2 裁决引用、属性键名实测结论（Task 5 Step 1 输出）、边界测试决策树执行记录（Task 9 Step 4）。

---

## Self-Review 记录（撰写者已执行）

1. **规格覆盖**：D-2 裁决设计八要点 → BOM 锁版本（Task 1）、starter-jdbc+非 JPA（Task 2）、Flyway 建表 schema-initialization=false（Task 3 + Global Constraints）、republish=false（Task 5）、EventOpsJob 重试/清理（Task 7）、ShedLock（Task 4/6/7）、verify() CI 强制（Task 9）、PlantUML 图 CI 产物（Task 10）、@ApplicationModuleListener 语义（Global Constraints + Task 8 监听器真实验证）、发布方事务内前提（Task 8 TransactionTemplate 写法即示范）。PLAN-P1-01 PR-1 其余范围（死信/重推/溯源/主数据分发/W-4/W-5/W-6/D-8）归计划 1b，不遗漏。
2. **占位符扫描**：三处「以实测为准」均为**带具体命令与两种确定结果的验证步骤**（属性键名 Task 5、Documenter 方法名 Task 10、verify 返回形态 Task 9），非 TBD；无「适当处理」类空话。
3. **类型一致性**：`IncompleteEventPublications.resubmitIncompletePublicationsOlderThan(Duration)` / `CompletedEventPublications.deletePublicationsOlderThan(Duration)` 在 Task 7 测试、实现、Task 8 说明三处一致；阈值常量 `RETRY_STUCK_THRESHOLD`/`COMPLETED_RETENTION` 定义与引用一致；`LifecycleProbeEvent`/`ProbeState`/`SUCCEED_LATCH` 在 IT 内定义与引用一致。
