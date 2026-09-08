# PR-2 M20 最小治理构件任务简报（CF-1 冻结载体）

| 简报属性 | 内容 |
| --- | --- |
| 简报编号 | BRIEF-PR2-01 |
| 日期 | 2026-09-09 |
| 执行者 | 实现专员（本简报为唯一需求来源，spec 口径以 `docs/plans/2026-09-08-P0实施计划.md` §1-PR-2 为准） |
| 上游依据 | P0 实施计划 PLAN-P0-01 §1-PR-2 / §3-DoD 第 2、4 条；`docs/specs/modules/20-integration.md`（M20 Spec v1.1）；`docs/specs/modules/00-implementation-order.md` §5-CF-1/CF-2/CF-7；`docs/specs/modules/01-system.md` §7；`docs/specs/modules/14-iot.md` FU-M14-05；`backend/AGENTS.md`（下称 backend 宪法）；`CHANGELOG.md` v1.2 裁决；`TASK.md` T-R3-4 / D-6 |
| 上游状态 | PR-1 已合入 dev（ed5e34e）：父 POM / fuyun-common（异常基座+ProblemDetail+traceId）/ fuyun-app（装配+三 profile yml）/ 20 空模块就位，`mvn verify` 与 CI 五 checks 全绿 |
| 交付批次 | B2.1 事件信封 + Long→String 序列化 + 交换机/队列声明构件 + event_registry → B2.2 received_event 幂等构件 + dead_letter 落库告警 → B2.3 首批事件名与 CF-7 登记 + 端到端 IT（核心包 1.00 首次真实生效） |
| 完成定义 | 见本简报 §6（各批次验收命令与 PR-2 收尾标准） |

---

## 0. 执行前提（开工前必做）

1. 通读根 `AGENTS.md` → `backend/AGENTS.md`（宪法，写代码前必读）；M20 治理口径以 M20 Spec 为准，**其中"手动确认"表述已被 CHANGELOG v1.2 裁决覆盖**：消费一律 `@RabbitListener` + 容器 AUTO 确认（backend 宪法 A.5-5），本简报一律按 AUTO 口径写。
2. **先记再改**：开工前在 `CHANGELOG.md` 登记本次变更条目；本次不修宪法正文，实现口径偏差逐项在 PR 描述申报（见 §8）。
3. **分支与流程红线**：自 `dev` 拉 feature 分支（如 `feat/pr2-m20-governance`）→ `gh pr create`（目标分支 dev）→ 五项检查（`backend / verify`、`frontend / verify`、`images`、`commitlint`、`hygiene`）全绿 → 合入 dev；提交信息 conventional commits。
4. 本地环境（同 PR-1）：`JAVA_HOME=D:\code\java\jdk\jdk17`、Maven 3.9.x、Docker Desktop 运行中（集成测试必需）。
5. 所有新建文件：UTF-8 无 BOM、LF 行尾、文件末尾一个换行；注释/日志/文档全中文，标识符英文；测试与实现同提交，TDD 先 RED 后 GREEN。
6. 新增生产代码的目录中原有 `.gitkeep` 随首个真实文件一并删除（防孤儿占位残留）。

---

## 1. 构件归属决策（落位总表）

### 1.1 决策：不新建 fuyun-messaging 模块；接口与契约沉 fuyun-common，实现与表落 fuyun-integration

依据（三条，按效力排序）：

1. **M20 Spec v1.1 已裁决（M-3，`docs/specs/modules/20-integration.md` 文档头/§1-红线 2/§12 修订记录）**："幂等构件接口下沉 fuyun-common（接口在 common，实现由本模块运行时装配，表仍在 integration schema，各模块零编译期依赖）"。治理构件的所有权归 M20（`fuyun-integration`），这是 Spec 定稿而非开放问题。
2. **模块清单已冻结**：P0 计划 §1-PR-1 与 PR-1 交付的父 POM `modules` 固定为 22 个（common + 20 业务域 + app）；新建 Maven 模块属计划/宪法修订，loop 红线禁止偏离计划。
3. **fuyun-common 边界允许**：backend 宪法 B.1 定义 common = "公共工具/基础异常/全局异常渲染/审计支撑；不依赖任何业务模块"——信封 record、幂等接口、遥测消息模型均为**零业务依赖的契约载体**（与既有 `ErrorCode` 接口同性质），沉入 common 后各业务模块仅依赖 common 即可发布/消费事件，满足 M20 "各模块零编译期依赖" 的治理目标。反之若把信封/声明构件放 fuyun-integration api/，20 个业务模块将全部编译期依赖 M20，与幂等构件"零编译期依赖"的裁决精神冲突。

### 1.2 落位总表（宪法目录规则 → 本 PR 实际落位）

| 构件 | 落位（包/目录） | 批次 | 依据 |
| --- | --- | --- | --- |
| 事件信封 record | `fuyun-common`: `com.fuyun.common.messaging.EventEnvelope` | B2.1 | 全系统契约载体，common 不依赖业务模块（B.1）；M-3 裁决模式 |
| 信封编解码器 | `fuyun-common`: `com.fuyun.common.messaging.EventEnvelopeCodec` | B2.1 | 同上；仅依赖 Jackson（starter-web 已有） |
| Long→String 全局序列化 | `fuyun-common`: `com.fuyun.common.config.JacksonLongToStringConfig`（Bean 装配归 fuyun-app @Import） | B2.1 | backend A.3-8 "集中注册于 config/ 包"；装配归 app 先例（TraceIdConfig @Import GlobalExceptionHandler，CHANGELOG PR #4 Finding 1） |
| 幂等构件接口 | `fuyun-common`: `com.fuyun.common.messaging.MessageIdempotencyService` + `ReceivedEventRecord` | B2.2 | M-3 裁决明文"接口下沉 fuyun-common" |
| CF-7 标准遥测消息模型 | `fuyun-common`: `com.fuyun.common.messaging.StandardTelemetryMessage` | B2.3 | M20 Spec §3.4：遥测移交 SPI 沉 common（M14 注册实现、M20 调用），消息模型是 SPI 签名载体，须与 SPI 同层，避免 PR-4 跨模块移类 |
| 队列/交换机声明构件 | `fuyun-integration`: `api/MessagingGovernance`（接口）+ `api/ConsumerQueueSpec`、`api/DelayQueueSpec`（契约 record）+ `service/impl/QueueGovernorImpl`（实现） | B2.1 | api/ = 对外契约唯一出口（B.1）；实现落 service/impl（A.4.3-20 impl 后缀） |
| event_registry 登记服务 | `fuyun-integration`: `service/IEventRegistryService` + `service/impl/EventRegistryServiceImpl`（extends ServiceImpl） | B2.1 | A.4.3-20 CRUD 型 IService/ServiceImpl |
| 幂等实现 | `fuyun-integration`: `service/impl/MessageIdempotencyServiceImpl` | B2.2 | 实现由 M20 运行时装配（M-3）；落 service/impl（即核心包 1.00 覆盖对象） |
| 三治理实体 + mapper | `fuyun-integration`: `entity/EventRegistry`、`entity/ReceivedEvent`、`entity/DeadLetter` + `mapper/` 同名 Mapper | B2.1/B2.2 | A.4.3-20：`@TableName` 实体放 entity/、mapper 接口放 mapper/，不出数据层 |
| 死信监听器 | `fuyun-integration`: `internal/DeadLetterListener` | B2.2 | 容器驱动的模块内入口、禁止外部引用（B.1 internal/ 语义）；非对外契约故不入 api/ |
| 治理常量 | `fuyun-integration`: `constants/MessagingConstants`（public final static + 私有构造器） | B2.1 | A.2-6；**状态值一律字符串常量（README §3"状态字段用 VARCHAR 常量"），本 PR 不落任何 enum 类，规避 D-6（见 §8-1）** |
| 治理配置属性 | `fuyun-integration`: `properties/MessagingProperties`（`fuyun.messaging.*` 前缀，@Validated + record 构造器绑定） | B2.1 | A.2-2/A.2-4 |
| 消息治理装配 | `fuyun-integration`: `config/MessagingGovernanceConfig`（Declarables 三交换机+死信队列、Jackson2JsonMessageConverter、@EnableConfigurationProperties、@Import 四实现类） | B2.1/B2.2 | B.1 config/ 集中；@Import 显式装配（@SpringBootApplication 只扫 com.fuyun.app.*，PR #4 既有裁决不放宽扫描） |
| app 装配与插件 | `fuyun-app`: `config/MessagingConfig`（@Import 两配置类）、`config/MybatisPlusConfig`（@MapperScan + 三大插件）；`application.yml`/`application-test.yml` 追加键 | B2.1 | B.1 装配归 app；A.4.3-19 三大插件集中注册（PR-2 为首个 MP 使用模块） |
| Flyway 迁移 | `fuyun-integration/src/main/resources/db/migration/integration/`：V1～V5 | B2.1（V1-V2）/B2.2（V3-V4）/B2.3（V5） | A.4.1-1/4；号段分配见 §2.5 |
| 端到端 IT | `fuyun-app/src/test/java/com/fuyun/app/MessagingGovernanceIT` | B2.3 | 聚合测试归 app（PR-1 先例 SmokeStackIT） |
| CF-2 五事件载荷 | `fuyun-system`: `api/` 下五个 record | B2.3 | 事件对象定义在发布方 api 包（B.3-1）；占位载荷 schema |
| CF-2 载荷测试 | `fuyun-system/src/test/java/com/fuyun/system/api/SystemMasterDataPayloadTest` | B2.3 | 撑起 fuyun-system BUNDLE 0.80 + 线格式冻结断言 |

**依赖变化**（全部表内/BOM 托管，无表外新增）：`fuyun-integration/pom.xml` 增 `spring-boot-starter-amqp`、`spring-boot-starter-data-redis`、`mybatis-plus-spring-boot3-starter`、`mybatis-plus-jsqlparser`（均 compile）+ `spring-boot-starter-test`（test）；`fuyun-common`/`fuyun-app` pom **零新增**。

---

## 2. B2.1 逐文件规格（信封 + 序列化 + 声明构件 + event_registry）

### 2.1 `com.fuyun.common.messaging.EventEnvelope`（record，CF-1 冻结载体）

- 七字段（M20 Spec §3.2 信封全集，覆盖计划 §1-PR-2 最小五字段）：`String eventId`（UUID，工厂生成）、`Instant occurredAt`（Clock 注入生成）、`String producer`（生产模块域标识，如 `system`）、`String eventType`（`<模块>.<实体>.<动作>`）、`String payloadVersion`（默认 `"1"`）、`String traceId`（可空，全链路追踪号）、`JsonNode payload`（业务载荷，Jackson 泛型载体）。
- record 纯数据载体（A.1-2 透明浅不可变）；类注释写明：**本类即 CF-1 事件信封冻结形态，全系统事件必须经信封发布，新增信封字段属宪法/Spec 修订**。
- 线格式（写入注释）：UTF-8 JSON；`occurredAt` 序列化为 ISO-8601 UTC 字符串；`payload` 为嵌套 JSON 对象；`contentType=application/json`；`__TypeId__` 头不作消费依据（消费方按 String 承接后经 codec 解析，防类型映射耦合）。
- JSON 反序列化由 Jackson record 支持（Boot 3.5 内建），无需 @JsonCreator 定制。

### 2.2 `com.fuyun.common.messaging.EventEnvelopeCodec`（Bean，构造注入 ObjectMapper）

- `EventEnvelope create(Clock clock, String producer, String eventType, String traceId, Object payload)`：生成 UUID eventId + `Instant.now(clock)`（**时钟注入可测性**，单测用 `Clock.fixed` 断言确定性）；payload 经注入的 ObjectMapper 转 `JsonNode`；payloadVersion 固定 `"1"`（私有常量）。`traceId` 取 MDC 当前值由调用方传入（HTTP 线程内发布时传 `MDC.get("traceId")`，MQ 线程无值传 null——javadoc 说明）。
- `String toJson(EventEnvelope envelope)`：序列化为线格式 JSON。
- `EventEnvelope fromJson(String json)`：反序列化并做**信封合规校验**（M20 §3.2 消费侧流程①）：eventId 可解析为 UUID、producer/eventType/payloadVersion 非空、occurredAt 非空、payload 非空——不合规抛 `IllegalArgumentException`（消息内容进异常消息，不含敏感值），供消费方拒绝并转死信留痕。
- 类注释声明线程安全：无状态单例，ObjectMapper 为 Boot 全局定制实例（Long→String 已生效）。

### 2.3 `com.fuyun.common.config.JacksonLongToStringConfig`（Long→String 全局配置）

- `@Configuration` 类，单个 `@Bean Jackson2ObjectMapperBuilderCustomizer`：`serializerByType(Long.class, ToStringSerializer.instance)` + `serializerByType(Long.TYPE, ToStringSerializer.instance)`（backend A.3-8：雪花 ID、金额分值超出 2^53 统一字符串输出；该配置集中注册，禁止各接口零散处理）。
- 由 `com.fuyun.app.config.MessagingConfig` `@Import` 装配（Boot 自动配置的 ObjectMapper 与 Spring MVC 共用同一实例，REST 与消息 JSON 同时生效）。
- **联动约束**：`Jackson2JsonMessageConverter` Bean（§2.7）必须以注入的同一 ObjectMapper 构建，保证消息侧与 REST 侧序列化行为一致。

### 2.4 `com.fuyun.integration.api` 契约三件

- `ConsumerQueueSpec`（record）：`String consumerModule`（消费者模块域标识）、`String eventType`（订阅的事件类型 = routing key）。
- `DelayQueueSpec`（record）：`String business`（业务名，队列名 `delay.<business>`）、`Duration ttl`（该档位延迟时长，必须 >0）、`String targetRoutingKey`（到期转发回 fy.topic 的目标路由键）。
- `MessagingGovernance`（接口，治理构件统一 API）：
  - `Declarables declareConsumerQueue(ConsumerQueueSpec spec)`：声明队列 `q.<consumerModule>.<eventType>`（durable、显式 `x-queue-type=quorum`、`x-dead-letter-exchange=fy.dlx`，不设 x-dead-letter-routing-key 以保留原始路由键）+ Binding 到 `fy.topic`（key=eventType）；同时调 `IEventRegistryService.registerSubscriber(eventType, consumerModule)` 做**订阅自动登记**。返回 Declarables 由调用方以 `@Bean` 暴露、RabbitAdmin 幂等声明（各模块标准用法：`@Bean Declarables xxx(MessagingGovernance g){ return g.declareConsumerQueue(spec); }`）。**事件未登记或已废止 → 抛 `IllegalStateException`（中文消息）阻断启动**——落实 M20 "新增/变更事件类型须先在 event_registry 登记" 治理约定。
  - `Declarables declareDelayQueue(DelayQueueSpec spec)`：声明队列 `delay.<business>`（durable、quorum、`x-message-ttl=ttl.toMillis()`、`x-dead-letter-exchange=fy.topic`、`x-dead-letter-routing-key=targetRoutingKey`）+ Binding 到 `fy.delay`（key=`delay.<business>`）。一条队列一个延迟档位（A.5-7）；**P0 不声明任何业务延迟队列**（T-R3-4 口径见 §8-4）。
  - 命名审查（FU-M20-06 命名治理）：eventType 必须匹配 `^[a-z][a-z0-9-]*(\.[a-z0-9-]+){2,}$`（小写点分 ≥3 段），consumerModule/business 必须非空小写——违规抛 `IllegalArgumentException`。

### 2.5 event_registry 表（Flyway V1、V2）与号段登记

迁移目录 `backend/fuyun-integration/src/main/resources/db/migration/integration/`，迁移描述全小写下划线（A.4.1-2）。

**号段决策（登记供后续模块避让）**：integration 治理域占用 **V1–V99（公共域低位）**。理由：① 宪法 A.4.1-2 明文"公共域低位"，event_registry/received_event/dead_letter 是全系统统一幂等落点与事件契约台账，是迁移体系中最接近"公共域"的表组，仅物理落位在 integration schema（M20 红线 2）；② PR-2 是全系统首个业务迁移，取最低号段使迁移历史版本序与实际交付时序一致，规避 `outOfOrder=false`（A.4.1-3）下低位段后补触发的乱序拦截。避让登记：

| 号段 | 归属 | 状态 |
| --- | --- | --- |
| V1–V99 | integration 治理域（本 PR 占用 V1–V5） | 已占用 |
| V100–V199 | 患者域 M02 | 宪法例举既定 |
| V200–V299 | 医嘱域 M04 | 宪法例举既定 |
| V300–V399 | 系统域 M01 | 建议（PR-3 拟用） |
| V400–V499 | 物联域 M14 | 建议（PR-4 拟用：超表+策略迁移） |
| V500 起 | 其余模块按实装先后递增分配，先登记先占（载体 = 各 PR 简报 + CHANGELOG） | 待分配 |

**V1__create_common_audit_trigger_function.sql**：`CREATE OR REPLACE FUNCTION public.fuyun_set_updated_at() RETURNS trigger`——`NEW.updated_at = now(); RETURN NEW;`（plpgsql）。注释说明：updated_at 由数据库统一维护（A.4.2-9）；取最低号段保证先于全部业务迁移执行，后续模块触发器复用本公共函数（登记为全项目约定）。

**V2__create_event_registry.sql**：

```sql
CREATE TABLE integration.event_registry (
    id                 BIGINT        PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    event_type         VARCHAR(128)  NOT NULL,                            -- 事件类型 <模块>.<实体>.<动作>
    producer_module    VARCHAR(32)   NOT NULL,                            -- 生产模块域标识
    payload_desc       VARCHAR(1000) NOT NULL,                            -- 载荷结构说明（冻结契约摘要）
    subscriber_modules VARCHAR(500)  NOT NULL DEFAULT '',                 -- 订阅模块清单（逗号分隔；broadcast=零订阅广播，R6-13）
    status             VARCHAR(16)   NOT NULL,                            -- ACTIVE 生效 / DEPRECATED 废止
    registered_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),              -- 业务登记时间
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by         VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by         VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted            SMALLINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_event_registry_event_type ON integration.event_registry (event_type) WHERE deleted = 0;
CREATE TRIGGER trg_event_registry_updated_at BEFORE UPDATE ON integration.event_registry
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
```

**updated_at 触发器口径**：触发器只挂 event_registry（有订阅登记 UPDATE 生命周期）；received_event/dead_letter 为只增台账（时间列由应用/DEFAULT now() 维护，无通用 updated_at 语义），不挂触发器——登记为简报定案口径（§8-5）。

### 2.6 `IEventRegistryService` + `EventRegistryServiceImpl`

- 接口：`void register(EventRegistrationSpec spec)`（幂等登记：已存在同 event_type 则 warn 跳过，不覆盖——契约冻结后禁止静默改写）；`void registerSubscriber(String eventType, String consumerModule)`（事件缺失/DEPRECATED → IllegalStateException；已存在则追加订阅模块，重复追加幂等跳过）；`boolean isRegistered(String eventType)`。
- `EventRegistrationSpec`（record，A.7 参数对象化）：`eventType / producerModule / payloadDesc / subscriberModules / broadcast`（boolean，broadcast=true 时 subscriberModules 记为 `broadcast`）。
- 实现按 A.4.3：`extends ServiceImpl<EventRegistryMapper, EventRegistry>`，单表 `this.lambdaQuery()` 链式 + `select()` 精确投影；写操作 `@Transactional`（方法级最小边界）；登记/订阅写操作记 info 日志（含 event_type 与模块标识）。

### 2.7 `com.fuyun.integration.config.MessagingGovernanceConfig`

- `@Configuration` + `@EnableConfigurationProperties(MessagingProperties.class)` + `@Import({QueueGovernorImpl.class, EventRegistryServiceImpl.class})`（B2.2 追加幂等实现与死信监听两个 import）。
- `@Bean Declarables messagingExchangesAndDeadLetterQueue()`：`fy.topic`（TopicExchange，durable）、`fy.dlx`（TopicExchange，durable）、`fy.delay`（TopicExchange，durable）——交换机全集固定三件套（M20 §7 治理约定，**全系统只此三个，任何模块禁止私建**）；死信统一队列 `q.integration.dead-letter`（durable、quorum）+ Binding 到 `fy.dlx`（key=`#`，全量死信汇入本队列）。
- `@Bean Jackson2JsonMessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper)`：以 Boot 全局定制 ObjectMapper 构建（Long→String 一致生效）；Boot 自动装配将其挂到 RabbitTemplate（生产发送与消费转换共用）。
- 发布确认回调说明（写入类注释）：application.yml 已定 `publisher-confirm-type: correlated` 姿态（PR-1 落地）；确认回调（nack/不可路由 error 日志与补偿）随首个真实发布构件落地（PR-3 发布侧 / P1 outbox），**本 PR 无生产发送代码，非遗漏**（B.3-3 可靠投递属 P1 治理完整化）。

### 2.8 `com.fuyun.integration.properties.MessagingProperties`

- record 构造器绑定 + `@Validated`，前缀 `fuyun.messaging`：字段 `@DefaultValue("24h") Duration idempotencyRedisTtl`（幂等 Redis 前置键 TTL；DB 唯一索引为最终兜底，TTL 只需覆盖常态重复投递窗口）。

### 2.9 `com.fuyun.integration.constants.MessagingConstants`

- `public final static` + 私有构造器（A.2-6）：`EXCHANGE_TOPIC="fy.topic"`、`EXCHANGE_DLX="fy.dlx"`、`EXCHANGE_DELAY="fy.delay"`、`QUEUE_DEAD_LETTER="q.integration.dead-letter"`、`QUEUE_PREFIX="q."`、`DELAY_QUEUE_PREFIX="delay."`、死信参数键 `X_DEAD_LETTER_EXCHANGE`/`X_MESSAGE_TTL`/`X_DEAD_LETTER_ROUTING_KEY`/`X_QUEUE_TYPE`、状态常量 `REGISTRY_STATUS_ACTIVE="ACTIVE"`/`REGISTRY_STATUS_DEPRECATED="DEPRECATED"`、`RECEIVED_STATUS_PROCESSED="PROCESSED"`、`DEAD_LETTER_STATUS_PENDING="PENDING"`、`SUBSCRIBER_BROADCAST="broadcast"`、`ENVELOPE_DEFAULT_VERSION="1"`。**本 PR 不建任何枚举类（D-6 规避，§8-1）。**

### 2.10 fuyun-app 装配（B2.1 内完成）

- `com.fuyun.app.config.MessagingConfig`：`@Configuration` + `@Import({JacksonLongToStringConfig.class, MessagingGovernanceConfig.class})`，零业务逻辑（B.1）。
- `com.fuyun.app.config.MybatisPlusConfig`：`@Configuration` + `@MapperScan(basePackages = "com.fuyun", annotationClass = Mapper.class)`（一次覆盖全部未来模块，mapper 接口须标 `@Mapper`）+ `@Bean MybatisPlusInterceptor`（A.4.3-19 三大插件集中注册：`PaginationInnerInterceptor`（maxLimit=2000）+ `BlockAttackInnerInterceptor` + `OptimisticLockerInnerInterceptor`）。
- `application.yml` 追加（追加式，不动既有键）：

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        retry:                    # 有界重试默认 3 次退避（backend 宪法 A.5-5）；耗尽后经队列死信参数进 fy.dlx
          enabled: true
          max-attempts: 3
          initial-interval: 1000ms
          multiplier: 2.0
fuyun:
  messaging:
    idempotency-redis-ttl: 24h    # 幂等 Redis 前置键 TTL（DB 唯一索引为最终兜底）
```

- `application-test.yml` 追加重试快速参数覆盖（`initial-interval: 100ms`、`multiplier: 1.0`），使 IT 死信链路秒级收敛。

### 2.11 B2.1 实体与 mapper（event_registry 部分）

- `com.fuyun.integration.entity.EventRegistry`：`@TableName("integration.event_registry")` + `@TableId(type = IdType.ASSIGN_ID)`；字段与 DDL 一一对应（camelCase ↔ snake_case）；`deleted` 字段标 `@TableLogic`；**禁 @Data（A.1-12），用 @Getter/@Setter**。
- `com.fuyun.integration.mapper.EventRegistryMapper`：`@Mapper public interface EventRegistryMapper extends BaseMapper<EventRegistry> {}`（无 XML——单表链式覆盖，A.4.3-15）。

### 2.12 B2.1 单元测试清单（与实现同提交，先 RED 后 GREEN）

| 测试类（落位） | 用例（业务语义命名 + 中文 @DisplayName） |
| --- | --- |
| `EventEnvelopeTest`（common test） | 固定 Clock 工厂创建：eventId 可解析 UUID、occurredAt 等于固定时刻、producer/type/version 正确；record equals 语义两实例字段相同即相等 |
| `EventEnvelopeCodecTest`（common test） | create→toJson→fromJson 往返字段一致；payload 复杂嵌套（含 List/中文）往返无损；缺 producer / 非法 eventId / 缺 payload 各场景 fromJson 抛 IllegalArgumentException；payload 中 Long 字段序列化为 JSON 字符串（定制生效） |
| `JacksonLongToStringConfigTest`（common test） | 定制器应用于 ObjectMapper 后：`Map.of("id",123L,"amount",999L)` 序列化产出 `"123"`/`"999"` 字符串；long 原生同样转字符串；`Instant` 仍为 ISO-8601 字符串（回归防护，防误伤时间字段） |
| `QueueGovernorImplTest`（integration test，Mockito mock AmqpAdmin 所需对象与 IEventRegistryService） | declareConsumerQueue：队列名 `q.it.system.dict.published`、durable、参数含 quorum + x-dead-letter-exchange=fy.dlx 且不设死信路由键、Binding 到 fy.topic 且 key=eventType；订阅登记被调用；事件未登记抛 IllegalStateException；eventType 非法命名（大写/两段）抛 IllegalArgumentException。declareDelayQueue：队列名 `delay.<biz>`、参数含 x-message-ttl/fy.topic 死信转发/目标路由键；ttl≤0 拒绝 |
| `EventRegistryServiceImplTest`（integration test，mock mapper） | register 新事件插入成功；register 重复 event_type warn 且不插入；registerSubscriber 事件缺失抛 IllegalStateException；DEPRECATED 事件拒绝订阅；已存在订阅追加且重复追加幂等；broadcast 标注落 subscriber_modules |

---

## 3. B2.2 逐文件规格（幂等构件 + 死信落库告警）

### 3.1 Flyway V3、V4

**V3__create_received_event.sql**：

```sql
CREATE TABLE integration.received_event (
    id              BIGINT        PRIMARY KEY,                     -- 雪花 ID（MP ASSIGN_ID）
    event_id        UUID          NOT NULL,                        -- 信封 eventId（全局唯一）
    event_type      VARCHAR(128)  NOT NULL,
    producer        VARCHAR(32)   NOT NULL,
    occurred_at     TIMESTAMPTZ   NOT NULL,
    consumer_module VARCHAR(32)   NOT NULL,                        -- 幂等键第二要素：消费者模块
    status          VARCHAR(16)   NOT NULL,                        -- PROCESSED 已消费（P0 唯一写入值）；FAILED 预留 P1 消费失败登记
    fail_reason     VARCHAR(1000),                                 -- P1 启用（Spec 字段全集随表落盘，避免后续 ALTER）
    retry_count     INT           NOT NULL DEFAULT 0,              -- P1 启用
    received_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
-- 幂等最终兜底：同事件同消费者仅一条（M20 Spec §4；backend 宪法 A.5-6）；幂等判定走唯一索引 P99 < 5ms（M20 §9）
CREATE UNIQUE INDEX uk_received_event_event_consumer ON integration.received_event (event_id, consumer_module);
CREATE INDEX idx_received_event_event_type ON integration.received_event (event_type, received_at);
```

表注释注明：保留 180 天后归档清理（M20 §9），归档策略 P1+ 完整化，本 PR 不建清理任务。

**V4__create_dead_letter.sql**：

```sql
CREATE TABLE integration.dead_letter (
    id             BIGINT        PRIMARY KEY,
    source_queue   VARCHAR(128)  NOT NULL,        -- x-death[].queue 来源队列
    routing_key    VARCHAR(128),                  -- 原始路由键（=事件类型）
    event_type     VARCHAR(128),                  -- 信封 eventType；信封不合规时为空
    event_id       VARCHAR(64),                   -- 信封 eventId；信封不合规时为空
    payload_body   TEXT          NOT NULL,        -- 原始消息体全文（重放依赖原文；M20 Spec"payload 引用与摘要"落地形态见 §8-6）
    payload_digest VARCHAR(64),                   -- SHA-256 摘要（列表页快速比对）
    fail_reason    VARCHAR(1000) NOT NULL,        -- 死信原因（消费重试耗尽/信封不合规标注）
    first_dead_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    status         VARCHAR(16)   NOT NULL DEFAULT 'PENDING',  -- PENDING/REPLAYED/CLOSED 状态机（M20 §5）
    replay_count   INT           NOT NULL DEFAULT 0,          -- P1 死信管理界面启用
    handler        VARCHAR(64),
    handle_note    VARCHAR(500),
    handled_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_dead_letter_status ON integration.dead_letter (status, first_dead_at);
CREATE INDEX idx_dead_letter_event_id ON integration.dead_letter (event_id);
```

P0 仅写 PENDING 行（重放/关闭随 P1 死信界面交付）；状态机列随表落盘不为死代码（Spec 字段全集，同 §8-5 口径）。

### 3.2 幂等构件（接口沉 common，实现落 integration）

**`com.fuyun.common.messaging.MessageIdempotencyService`**（接口，零基础设施依赖签名）：

- `boolean tryAcquire(String eventId, String consumerModule)`：Redis `SET NX PX` 前置去重（键 `fy:integration:idempotency:<consumerModule>:<eventId>`，A.5-1 键命名规范；TTL 取 MessagingProperties）。返回 false = 重复投递，消费方直接确认跳过；**Redis 异常降级放行**（warn 日志 + 返回 true，由唯一索引兜底——Redis 故障不得放大为消费不可用）。
- `void recordProcessed(ReceivedEventRecord record)`：插入 received_event（status=PROCESSED、processed_at=now()）；**唯一索引冲突（`DuplicateKeyException`）= 并发重复投递已被他实例处理，捕获后 warn 且不抛**（消费方正常返回即 AUTO 确认跳过）；其他 DB 异常原样上抛（真故障必须暴露，交容器重试）。
- `void release(String eventId, String consumerModule)`：删除 Redis 前置键——**业务失败路径由消费方在 catch 中调用**（成功不得释放），保证重投可重新抢占。
- 接口 javadoc 给出**标准消费范式**（消费方按此编写，PR-3 出现第二个真实消费者时再提炼模板基类，本 PR 不做抽象）：

```java
if (!idempotency.tryAcquire(eventId, module)) { return; }   // 重复投递：跳过即 AUTO 确认
try {
    doBusiness();                                            // 业务执行
    idempotency.recordProcessed(record);                     // 成功登记（唯一索引兜底并发）
} catch (RuntimeException e) {
    idempotency.release(eventId, module);                    // 失败释放前置键，允许重试/重投
    throw e;                                                 // 上抛交容器有界重试，耗尽进 fy.dlx
}
```

**`com.fuyun.common.messaging.ReceivedEventRecord`**（record）：`eventId / eventType / producer / occurredAt / consumerModule`（>3 参数参数对象化，A.7-1）。

**`com.fuyun.integration.service.impl.MessageIdempotencyServiceImpl`**：构造注入 `StringRedisTemplate`、`ReceivedEventMapper`、`MessagingProperties`；实现上述三方法。两层去重齐全（A.5-6：Redis 加速 + 唯一索引最终保证）。该类落 `service/impl/`，即 **JaCoCo PACKAGE 1.00 覆盖对象**。

### 3.3 死信监听器 `com.fuyun.integration.internal.DeadLetterListener`

- `@RabbitListener(queues = MessagingConstants.QUEUE_DEAD_LETTER)`（注解驱动 + 容器 AUTO 确认，禁手编监听容器 Bean，A.5-5）；方法签名以 `org.springframework.amqp.core.Message` 承接（**不做 JSON 类型转换**——死信可能是毒丸报文，转换器二次失败会丢消息）。
- 处理流程：① 从 `x-death` 头取 source_queue / routing-keys / reason；② 消息体 UTF-8 解码为原文 → SHA-256 摘要；③ 尝试经 `EventEnvelopeCodec.fromJson` 解析提取 event_id/event_type，失败则两列置空且 fail_reason 标注"信封不合规"（M20 红线 1：不合规信封拒收留痕）；④ 组装 PENDING 行插入 dead_letter（info 日志含 source_queue/event_id/摘要，**不打印完整 payload 防敏感信息入日志**）；⑤ 插入失败（DB 故障）→ catch 后 **error 日志告警且不抛**（AUTO 确认放弃该帧，M20 §10"留痕写入失败必须告警"口径），避免毒丸无限循环。
- 同一死信重复投递会重复落行：dead_letter 无唯一约束（同一 eventId 可因不同消费者多次死信），P1 死信界面完整化时收敛——注释说明。

### 3.4 实体与 mapper（B2.2）

- `entity/ReceivedEvent`（`@TableName("integration.received_event")`）、`entity/DeadLetter`（`@TableName("integration.dead_letter")`）：同 §2.11 规范；received_event/dead_letter 不设 @TableLogic（无逻辑删列）。
- `mapper/ReceivedEventMapper`、`mapper/DeadLetterMapper`：`extends BaseMapper<...>`，无 XML。

### 3.5 父 POM 修改（B2.2 同提交）

- jacoco 规则二 `<includes>` 追加 `com.fuyun.integration.service.impl`（billing/system 两个占位保留）——**核心包 PACKAGE 1.00 首个真实生效包**（DoD 第 2 条）。
- jacoco `<excludes>` 追加 `com/fuyun/**/constants/**`（常量类仅私有构造器 + 字面量，无私有逻辑，与 properties/config 同语义；宪法 C.5-2 排除清单的扩展项，PR 描述申报，§8-7）。

### 3.6 B2.2 单元测试清单（幂等单测全覆盖——核心包 1.00 硬门槛）

`MessageIdempotencyServiceImplTest`（mock StringRedisTemplate + ReceivedEventMapper）：

1. 首次消费抢占成功：`setIfAbsent` true → tryAcquire 返回 true，键名/TTL 断言正确；
2. 重复投递抢占拒绝：`setIfAbsent` false → 返回 false；
3. Redis 降级放行：`setIfAbsent` 抛 `DataAccessException` → 返回 true 不上抛（唯一索引兜底语义）；
4. 成功登记落库：recordProcessed 调 mapper.insert 且实体 status=PROCESSED、processed_at 非空；
5. 唯一索引冲突兜底：insert 抛 `DuplicateKeyException` → 不抛出（并发重复被吞为已处理）；
6. DB 真故障上抛：insert 抛其他 `DataAccessException` → 原样上抛（交容器重试，禁止吞错）；
7. 失败释放前置键：release 调用 delete 且键名一致。

`DeadLetterListenerTest`（mock mapper + codec）：

1. 合规信封死信落库：x-death 头解析 source_queue/routing_key、event_id/event_type 提取、payload_body=原文、digest 为 64 位十六进制、status=PENDING；
2. 信封不合规死信留痕：body 非 JSON → event_id/event_type 空、fail_reason 含"信封不合规"、插入仍执行（拒收留痕）；
3. 落库失败告警不抛：insert 抛异常 → 方法正常返回（AUTO 确认放弃，M20 §10 口径）；
4. 重试耗尽 reason 透传：x-death reason=rejected 写入 fail_reason。

---

## 4. B2.3 逐文件规格（首批事件名 + CF-7 登记 + 端到端 IT）

### 4.1 CF-2 五个主数据事件名与占位载荷（fuyun-system api/）

五个事件名**全部为 Spec 明示**（M01 Spec §7 MQ 事件发布清单 = CF-2 契约行，无推导项）：`system.dict.published`、`system.org.changed`、`system.user.changed`、`system.param.changed`、`system.practice.changed`。

载荷 record（事件对象定义在发布方 api 包，B.3-1；均标注"占位 schema：正式字段随 PR-3 M01 实装冻结，登记行 payload_desc 同步"）：

| record（`com.fuyun.system.api`） | 占位字段 | Spec 依据 |
| --- | --- | --- |
| `DictPublishedPayload` | `dictType`（type_code）、`version` | M01 §5 dict_version DRAFT→PUBLISHED 发布广播 |
| `OrgChangedPayload` | `orgId`、`changeType` | 同构推导（变更类事件） |
| `UserChangedPayload` | `userId`、`changeType` | 同构推导 |
| `ParamChangedPayload` | `module`、`paramKey` | **module 字段为 Spec 明示**（M01 FU-M01-07"参数变更发 system.param.changed（带 module）"） |
| `PracticeChangedPayload` | `employeeId`、`grantType`、`status` | M01 §5 practice_grant 变更广播（授权类型为 Spec 字段） |

### 4.2 CF-7 标准遥测消息模型 `com.fuyun.common.messaging.StandardTelemetryMessage`

- record 七字段（14-iot FU-M14-05"设备号/指标编码/值/单位/发生时间/质量/来源"）：`String deviceId`、`String metricCode`（MDC 编码）、`String value`（原始值字符串承载，数值解析定型随 PR-4）、`String unit`、`Instant occurredAt`、`String quality`（GOOD/SUSPECT/BAD，javadoc 注明取值）、`String source`（IOTDA 主链路 / HL7 模式 D 辅链路）。
- 落 common 依据见 §1.2（遥测移交 SPI 签名载体）；**P0 只登记不消费**：本 PR 不建 SPI 接口、不建任何 iot 消费代码（SPI 与消费链路归 PR-4）。

### 4.3 种子迁移 V5__seed_event_registry_cf2_cf7.sql（DoD 第 4 条"迁移+测试断言"落点）

固定 id 1–7 插入七行（`INSERT ... SELECT WHERE NOT EXISTS` 或先删后插均可，迁移只跑一次）：

| id | event_type | producer | payload_desc（摘要） | subscriber |
| --- | --- | --- | --- | --- |
| 1 | `integration.convention.event-envelope` | integration | CF-1 事件信封约定冻结：eventId/occurredAt/producer/eventType/payloadVersion/traceId/payload 七字段必填规则 + fy.topic/fy.dlx/fy.delay 三交换机 + q.<消费者>.<事件> 队列命名 | broadcast |
| 2–6 | 五个 `system.*` 事件 | system | 各自占位载荷字段摘要（§4.1） | ''（空，待订阅登记） |
| 7 | `iot.telemetry.message` | iot | CF-7 标准遥测消息模型冻结：deviceId/metricCode/value/unit/occurredAt/quality/source 七字段四路同构 | iot |

status 全部 ACTIVE。CF-1/CF-7 登记行形态为简报推导定案（Spec 未定义非 fy.topic 契约的登记形态，§8-2）。

### 4.4 端到端集成测试 `com.fuyun.app.MessagingGovernanceIT`

- 容器三件套与 SmokeStackIT 完全同款（tag 严格一致：`timescale/timescaledb:2.29.2-pg16`、`redis:8.10.1`、`rabbitmq:4.3.5-management` + `it/rabbitmq.conf` 挂载；static @Container 类级共享 + `@ServiceConnection`；`@SpringBootTest` + `@ActiveProfiles("test")`；构造器 `@Autowired` 显式标注）。本类独立声明容器，不改 SmokeStackIT。
- 测试内部 `@TestConfiguration`：
  - `@Bean Declarables itConsumerQueue(MessagingGovernance governance)`——`governance.declareConsumerQueue(new ConsumerQueueSpec("it", "system.dict.published"))`（走构件声明，禁止测试私建；队列由 RabbitAdmin 幂等声明后监听容器启动）。
  - `@Bean` 测试消费者组件（`@RabbitListener(queues = "q.it.system.dict.published")`，参数 String 承接 + codec 解析），**按 §3.2 标准消费范式**实现：payload 含 `"poison":true` 抛 `IllegalStateException`（模拟业务失败→重试→死信）；否则 `AtomicInteger` 计数 + `CountDownLatch` 放行 + recordProcessed。
- 断言链（五步，全部中文 @DisplayName）：
  1. **冻结登记断言（DoD-4 落点）**：JdbcTemplate 查 event_registry——七种子行齐全且 status=ACTIVE；`system.dict.published` 的 producer=system；
  2. **发布→消费**：codec.create（Clock.systemUTC，producer=system，eventType=system.dict.published，traceId=测试锚点）→ `rabbitTemplate.convertAndSend("fy.topic", "system.dict.published", envelope)` → latch 等待（15s 上限）→ 业务计数=1、收到的 eventId/traceId 与发布一致；
  3. **重复投递被幂等拦截**：同 eventId 再发一帧 → 等待后业务计数仍=1、received_event 表该 eventId+it 仅一行；
  4. **异常消息入 fy.dlx 落库**：发布 poison 帧 → 等待死信落库（15s 上限，test profile 快速重试 3 次≈亚秒级）→ JdbcTemplate 断言 dead_letter 存在 PENDING 行：event_id 匹配、source_queue=`q.it.system.dict.published`、payload_body 含 poison 标记、fail_reason 非空；
  5. **构件副作用断言**：event_registry 中 `system.dict.published` 的 subscriber_modules 含 `it`（订阅自动登记生效）；`AmqpAdmin.getQueueProperties("q.integration.dead-letter")` 非空（三交换机+死信统一队列随上下文声明成功）。
- **不测延迟时延**：不含任何 fy.delay TTL 到期断言（T-R3-4 为压测待调研项，PR-2 只落构件，§8-4）；亦不新增 awaitility 等测试依赖（Latch+超时已覆盖）。

---

## 5. TDD 与验收指令（每批次完成标准）

| 批次 | 完成标准（命令全绿即过；均在 `backend/` 目录执行） |
| --- | --- |
| B2.1 | `mvn -B -ntp test`（全模块单测绿：新增 5 测试类 ≈ 18 用例）+ `mvn -B spotless:check`（格式门禁；报差异先 `mvn -B spotless:apply` 再复核 diff） |
| B2.2 | 同上（累计幂等 7 场景 + 死信 4 场景全绿）；父 POM jacoco 规则已增补（`com.fuyun.integration.service.impl` 1.00 + constants/ 排除） |
| B2.3 | `mvn -B -ntp verify`（spotless → 单测 → failsafe `MessagingGovernanceIT` 五步断言 → JaCoCo 双阈值全绿，**需 Docker Desktop 运行中**）。此为 `mvn verify` 首次真实跑 IT 的 PR；核心包 1.00 首次真实生效包 = `com.fuyun.integration.service.impl`（MessageIdempotencyServiceImpl/EventRegistryServiceImpl/QueueGovernorImpl 三类全覆盖） |
| PR-2 收尾 | `pre-commit run --all-files` 绿（与 CI hygiene 同源）→ PR 五 checks 全绿 → `/code-review` 无 findings → 合入 dev → 台账登记（批次状态 + 测试摘要） |

TDD 纪律：每个生产类先写失败测试再实现；迁移文件以 B2.3 IT 的迁移断言验证（Flyway 无法单测）；测试与实现同一次提交；禁止为凑覆盖率新增无业务语义断言。

---

## 6. 提交切分建议（conventional commits，中文 subject）

| 序 | 提交信息 | 内容 |
| --- | --- | --- |
| 1 | `feat(common): 事件信封对象与 Long→String 全局序列化配置` | EventEnvelope/EventEnvelopeCodec/JacksonLongToStringConfig + 三测试类 + app MessagingConfig @Import |
| 2 | `feat(integration): 交换机队列声明构件与 event_registry 登记服务` | api 契约三件/QueueGovernorImpl/IEventRegistryService/entity+mapper/constants/properties/MessagingGovernanceConfig + app MybatisPlusConfig + yml 追加键 + V1/V2 迁移 + 对应单测 |
| 3 | `feat(integration): received_event 幂等构件与 dead_letter 落库告警` | MessageIdempotencyService 接口+实现、ReceivedEvent/DeadLetter entity+mapper、DeadLetterListener、V3/V4 迁移、父 POM jacoco 增补、全部单测 |
| 4 | `feat(system): CF-2 主数据事件占位载荷与 CF-7 遥测消息模型` | fuyun-system api 五 record + SystemMasterDataPayloadTest + StandardTelemetryMessage + V5 种子迁移 |
| 5 | `test(backend): 消息治理端到端集成测试打通发布幂等死信链路` | MessagingGovernanceIT + application-test.yml 重试快速参数 |

若会话能力允许，4+5 可合并；1~3 顺序不可调换（依赖链：信封 → 声明构件 → 幂等/死信）。

---

## 7. 红线清单（实现专员绝对禁止项，摘自宪法与 Spec，违者不得合入）

1. **禁私建交换机**：全系统仅 `fy.topic`/`fy.dlx`/`fy.delay` 三个交换机，声明集中于 MessagingGovernanceConfig；任何模块（含测试代码）不得声明其他交换机（A.5-4、M20 红线 4）。
2. **队列全 quorum**：构件声明显式 `x-queue-type=quorum`；RabbitAdmin 幂等声明，测试/生产一致（A.5-4、总 Spec D1）。
3. **消费幂等两层齐全**：Redis SET NX 前置（降级放行）+ received_event 唯一索引兜底（冲突视为已处理），缺一不可（A.5-6）；AUTO 确认不豁免幂等（A.5-5）。
4. **AUTO 确认**：一律 `@RabbitListener` 注解驱动 + 容器 AUTO 确认 + `defaultRequeueRejected=false` + 有界重试（3 次退避）后进 fy.dlx；禁手编监听容器 Bean、禁裸无限重投（A.5-5，CHANGELOG v1.2 裁决覆盖 M20 Spec"手动确认"表述）。
5. **Long→String 集中配置**：Jackson 定制仅此一处（common config/），REST 与消息共用同一 ObjectMapper；禁止任何接口/构件零散注册序列化定制（A.3-8）。
6. **事件对象定义在发布方 api 包**：CF-2 载荷 record 落 `com.fuyun.system.api`；通用信封与遥测模型按 §1.2 沉 common（M-3 裁决载体）；禁止把载荷对象放 dto/vo 或 integration（B.3-1）。
7. **@TransactionalEventListener 时机**：同事务一致性走同步调用或应用事件（提交前后时机经 @TransactionalEventListener 控制），跨进程最终一致才走 RabbitMQ——本 PR 无进程内事件桥接，若实现中引入 `ApplicationEvent` 必须按 B.3-1 评审时机。
8. **常量/枚举目录**：常量进 `constants/`（public final static + 私有构造器）；**本 PR 零枚举类**（D-6 未裁决，状态值一律 VARCHAR 字符串常量，README §3 背书）；禁止魔法值散落（A.2-6/7）。
9. **审计字段 DDL 规范**：created_at/updated_at 由数据库维护（DEFAULT now() + 触发器），公共触发器函数随 V1 建立并登记为全项目约定；操作人由应用层注入（治理表默认 'system'）；禁止应用层写 updated_at（A.4.2-9）。
10. **迁移红线**：只经 Flyway 版化迁移（禁 schema.sql/自动 DDL）；禁改已应用迁移；V 号唯一且号段按 §2.5 登记；描述全小写下划线（A.4.1）。
11. **模块边界**：fuyun-common 不依赖业务模块；业务模块只依赖 common 与他人 api 包；mapper/entity 不出数据层；internal/ 禁止外部引用；ArchUnit 边界规则随实装逐步编写（本 PR 不强制新增）。
12. **MP 用法**：单表 lambdaQuery 链式 + select 精确投影；实体 @Getter/@Setter（禁 @Data/@AllArgsConstructor）；ASSIGN_ID；三大插件集中注册；禁再引 mybatis/mybatis-spring（A.4.3）。
13. **测试与死代码**：禁止空断言/无业务语义 mock 校验；禁止空实现桩与无调用预留方法（received_event 的 FAILED/fail_reason 列属 Spec 字段全集，非死代码——§8-5）；禁裸 System.out；日志全中文且不含 payload 全文/敏感值。
14. **编码与流程**：UTF-8 无 BOM、LF；先记 CHANGELOG 再改；feature 分支 + PR 五 checks；表外依赖/排除项扩展一律 PR 描述申报。

---

## 8. 待裁决与定案口径（实现按默认执行，逐项在 PR 描述申报）

| # | 事项 | 定案/默认方案 | 性质 |
| --- | --- | --- | --- |
| 1 | **D-6（TASK.md 待决策）：`enum/` 目录为 Java 保留字无法编译** | PR-2 全程不落枚举类：状态值/档位/事件类型一律 constants/ 字符串常量（README §3"状态字段用 VARCHAR 常量"背书，不违 A.2-7"禁常量类模拟枚举"——本 PR 无需 code↔映射语义的类型化枚举）。**PR-2 不被 D-6 阻塞**；D-6 仍为 PR-3（M01 状态机）前的必裁决项 | 阻塞规避 |
| 2 | CF-1/CF-7 在 event_registry 的登记行形态（Spec 只定义了 `<模块>.<实体>.<动作>` 事件行） | 按 §4.3 种子迁移七行执行：CF-1 = `integration.convention.event-envelope` 约定行；CF-7 = `iot.telemetry.message` 消息模型行。若终验（P6 DoD 第 4 条核对）口径不同，仅需调整种子迁移数据（一行 SQL），不动结构 | 简报推导，需终验核对 |
| 3 | Flyway 号段分配（宪法只例举患者 V100/医嘱 V200） | integration 治理域 = V1–V99（§2.5 全表 + 理由）；后续模块按实装先后递增占段。号段归属的 CI 自动校验（A.4.1-2）PR-1 未落地，本 PR 不补建（防范围蔓延），建议登记 TASK.md 待办 | 定案 + 待办建议 |
| 4 | **T-R3-4（TASK.md 待调研）：fy.delay TTL+DLX 时延压测** | PR-2 只交付延迟队列声明构件（declareDelayQueue API + 参数完备性单测），**不声明任何业务延迟队列、不做时延实测**；实测随首个真实延迟业务（P1+）进行并回填 TASK.md | 口径声明 |
| 5 | received_event 的 FAILED/fail_reason/retry_count 列 P0 无写入方 | Spec 字段全集随表落盘（避免后续 ALTER），P0 仅写 PROCESSED；构件不预留无调用的 markFailed 方法（死代码零容忍）。失败可观测由 dead_letter 承担 | 定案口径 |
| 6 | M20 Spec dead_letter"payload 引用与摘要"未定义列形态 | 落地为 `payload_body TEXT`（原文全文，重放依赖）+ `payload_digest VARCHAR(64)`（SHA-256）两列 | 定案口径 |
| 7 | jacoco excludes 追加 `com/fuyun/**/constants/**` | 宪法 C.5-2 排除清单（config/dto/entity/Application/生成物）的扩展，常量类无可测逻辑；PR 描述申报，宪法回补随修宪流程 | 申报项 |
| 8 | M20 Spec §3.2/§7/§9"手动确认"表述 | 已被 CHANGELOG v1.2 裁决（AUTO）覆盖，TASK.md W-5 已登记同步义务；本简报一律 AUTO 口径，Spec 文字修订归宪法同步流程，不在本 PR 处理 | 已裁决冲突 |
| 9 | 发布侧可靠投递（outbox + 发布确认回调） | A.5-4 confirm 姿态已在 yml（PR-1）；确认回调/outbox 随首个真实发布方（PR-3）与 P1 治理完整化交付，本 PR 无生产发送代码 | 范围澄清 |
