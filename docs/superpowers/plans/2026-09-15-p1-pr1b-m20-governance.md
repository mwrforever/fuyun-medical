# P1·PR-1b M20 事件总线治理完整化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 把 M20 集成平台的事件总线治理从「P0 只写不查」推进到「可查、可处置、可追溯」——死信管理查询 API + 失败重推（留痕与上限）+ 关闭动作、投递/消费/重试全链留痕查询 API、FU-M20-04 主数据分发订阅登记与分发流水，并清偿 TASK.md W-4/W-5/W-6 三项工程债 + D-8 宪法 A.5-9 正文修订。

**架构：** 后端分层照抄 backend 宪法 B.1：controller（校验 + 编排）→ service（事务与状态机）→ mapper（MP BaseMapper）；DTO/VO 落 integration 的 `dto/`、`vo/` 包，实体 → 出参经 MapStruct 转换器 `convert/IntegrationConverter`；对外契约（错误码枚举）落 `integration/api`（B.1 api 包 = 跨模块唯一出口）；模块装配沿用既有裁决「装配归 app」——模块内新增 `IntegrationWebConfig` / `IntegrationMdmConfig`，app 侧新增 `IntegrationConfig` 统一 `@Import`。死信重推的 MQ 投递一律在事务外（A.4.2-7 事务内禁消息发送），状态机变更走单语句 CAS UPDATE（多实例安全，零新增锁）。主数据分发治理落两张新表（`mdm_subscription` / `mdm_dispatch_log`，integration 号段 V502/V503），广播链路的「本模块记分发流水」由 M20 订阅 M01 五个主数据事件后落流水实现。

**技术栈：** Spring Boot 3.5.16 / MyBatis-Plus 3.5.17（分页插件 maxLimit=2000 已在位）/ Spring AMQP 3.2.12 / MapStruct 1.6.3（父 POM 已配置 annotationProcessorPaths）/ Flyway 11.7.2 / JUnit5 + Mockito 5.17.0 + Testcontainers 1.21.4 / Spring Modulith 1.4.13（PR-1a 已合入）。

**本计划范围声明（PR-1 治理切片 1b，对应 PLAN-P1-01 PR-1 第 2 条范围）：**

- **死信处理完整化**：仅实现 M20 §7 已定义端点 `GET /dead-letters`、`GET /dead-letters/{id}`（诊断看载荷）、`POST /dead-letters/{id}/replay`、`POST /dead-letters/{id}/close`。**重推上限为控制器拍板口径（Spec 未定义）**：每死信最多重推 3 次，载体 = V4 既有 `replay_count` 列（已应用迁移零改动，不新增迁移）。
- **事件溯源日志**：`GET /received-events`（Spec §7 原文词汇，消费台账查询）+ `GET /event-publications`（PR-1a 引入的 Modulith 投递注册表只读查询面）+ `GET /event-registry`（Spec §7 原文词汇，契约台账查询）。**不覆盖 `iot.iot_consume_error_log`**——他模块 schema 禁读（M20 红线 2 / 宪法 B.2-2），其查询面属 M14。
- **FU-M20-04（P0 子集）**：实现「订阅登记（module × topic × sync_mode）」「广播链路本模块记分发流水」「主题 × 订阅方 × 版本 × 对账状态 矩阵查询」。**不实现**：全量初始化（新订阅方经 M01 回源）、每日版本对账、落后自动全量重发、`POST /mdm/redispatch` 端点——四者均依赖 M01 版本化回源/重发接口（当前 M01 仅有 `GET /api/v1/system/dicts/{type}?version=`，org/user/param/practice 无版本化读接口，侦察报告 §Gap 已实证），本计划以「跨模块前置」显式登记（Task 8 末步写 TASK.md 与 PR 描述），禁止落空动作端点充数。
- **`integration.dead-letter.created` 事件不在本计划**：M20 Spec §5 流程 2 要求死信消费者落表后发布该事件通知运维，但 V5/V403 种子的 8 行 event_registry 内无该事件登记行、全仓无发布者（侦察报告 §2 实证）；发布该事件须先补登记行 + 发布器 + M01 通知链路，PR-1 验收不含，本计划 Task 4 只做死信留痕列宽钳长（该告警链随 FU-M20-06 死信告警完整化登记 PR 描述）。
- **not in scope（明确不做，避免误判遗漏）**：`POST/DELETE /event-registry`（登记已由治理构件自动化，废止动作无 P0 消费方与状态机约束支撑）、死信操作接 M01 审计切面 `@AuditLog`（需新增 integration→system 跨模块依赖）、`subscriber_modules` 列宽钳长（W-6② 只裁并发与 broadcast 两项，见计划末「遗留问题」）、M01 侧主数据版本化接口本身。

## Global Constraints（每个任务隐含遵守）

- **JDK17 命令前缀**：所有 Maven 命令一律 `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp ...`。
- **Maven 形态（D-11 裁决）**：从仓库根执行 `-f backend/pom.xml -pl fuyun-<域> -am`；**`-am` 必须携带**（不带时依赖模块从本地仓库解析已安装 jar，会出现迁移缺失/边界假违规的假故障）。**所有带 `-Dtest=` 过滤的命令必须同时带 `-Dsurefire.failIfNoSpecifiedTests=false`**：`-am` 会把上游模块（fuyun-common 等）拉进反应堆，其 surefire 拿到同一 `-Dtest` 过滤器后零匹配即中止构建。
- **MyBatis-Plus 3.5.17 包路径陷阱（禁凭记忆写）**：`IService`/`ServiceImpl` 在 `com.baomidou.mybatisplus.spring.service[.impl]`（与既有 `fuyun-integration` 的 `IEventRegistryService`/`EventRegistryServiceImpl` 首行 import 一致；写成 `extension.service` 必编译失败）；分页类在 `com.baomidou.mybatisplus.extension.plugins.pagination.Page`、拦截器在 `com.baomidou.mybatisplus.extension.plugins.inner.*`。
- **Flyway 迁移红线（A.4.1-2/A.4.1-3 + PR-1a 实证）**：禁改已应用迁移（V1–V5、V300–V303、V400–V403、V500/V501 全部冻结）；新迁移只能追加，**版本号必须 > 501**（真库历史已应用 500 段，`outOfOrder=false` 下低版本号新文件会被 Flyway 拒绝——2026-09-14 真栈探针实证）；V500 起「先登记先占」，新号段须在 `CHANGELOG.md` 登记后再落文件（本计划 V502/V503 于 Task 8/9 各登记一次）。
- **覆盖率门禁**：`com.fuyun.integration.service.impl` 在父 POM 核心包 PACKAGE LINE=1.00 名单内（`backend/pom.xml`）——本计划新增的 `DeadLetterServiceImpl` / `ReceivedEventQueryServiceImpl` / `EventPublicationQueryServiceImpl` / `MdmSubscriptionServiceImpl` 必须 100% 行覆盖；BUNDLE LINE ≥0.80 全局生效；`config/properties/dto/entity/constants/*ConverterImpl` 在 excludes 内不计门禁。
- **事务红线（A.1-8 / A.4.2-7）**：controller 禁 `@Transactional`；事务内禁 MQ 发送与远程调用；死信重推的投递与状态写必须分步（先投递后 CAS 写）。
- **API 契约（A.3-1~A.3-6）**：前缀 `/api/v1/integration/`；资源复数小写连字符；成功直出业务数据（无 envelope）；失败一律 ProblemDetail（`properties.errorCode` = `INT-xxxx`）；分页请求 `page`（**0 基**）/`size`，响应 `{content, page, size, total}`；URL 用词以 M20 Spec §7 原文优先（`dead-letters` / `replay` / `close` / `received-events` / `event-registry`）。
- **全链溯源留痕（M20 §3.2/§4）**：死信的 handler/handle_note/handled_at/replay_count 与 received_event 的 status/fail_reason/retry_count 全部实写，禁留空列。
- **注释 / 日志 / 编码**：注释与日志全中文（描述业务意图与「为什么」）；标识符英文；import 短名（禁全限定类名声明，A.1-13）；UTF-8 无 BOM、LF 行尾、文件末单换行；日志禁打印 payload 原文与凭证。
- **格式门禁**：提交前 `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml spotless:apply`；交付门禁 `... verify` 全绿。
- **提交规范**：conventional commits、中文 subject、body 每行 ≤100 字符（`python len` 逐行自查）；可用 type 仅 build/chore/ci/docs/feat/fix/perf/refactor/revert/style/test（**无 `config`**）。
- **执行目录与分支**：`D:\code\project\fuyun-medical`（Git Bash，Windows）；Docker Desktop 运行中（Testcontainers 必需）；执行时自 `dev` 创建分支 `feat/p1-pr1b-m20-governance`，一切变更经 PR 五 checks 合入，禁直推。
- **经验证法条款**：本计划标注「先实测」的步骤必须按 Step 给出的命令取实况结果再落码，禁凭记忆写 API；标「二选一」处只允许按实测结果保留一种写法，禁两种并存。

## 文件结构（本计划全量改动面）

| 动作 | 文件 | 职责 |
| --- | --- | --- |
| 创建 | `scripts/check-migration-governance.py` | W-4：号段归属 + 版本唯一 + 乱序守卫三项校验（只读） |
| 修改 | `.pre-commit-config.yaml` | 注册 local hook `check-migration-governance`（pass_filenames: false） |
| 修改 | `.github/workflows/ci.yml` | hygiene job：fetch-depth 0 + `MIGRATION_BASE_REF` 注入（乱序守卫基线） |
| 创建 | `backend/fuyun-common/src/main/java/com/fuyun/common/web/PageResult.java` | 全模块统一分页出参契约（A.3-6，不依赖 MP） |
| 创建 | `backend/fuyun-common/src/main/java/com/fuyun/common/utils/TextTruncate.java` | 列宽截断公共工具（W-6① / 失败留痕 / 载荷预览共用） |
| 修改 | `backend/fuyun-common/src/main/java/com/fuyun/common/messaging/MessageIdempotencyService.java` | 幂等契约：新增 `settleFailure`（失败收尾），移除 `release`（原语下沉实现内部） |
| 创建 | `backend/fuyun-integration/src/main/java/com/fuyun/integration/api/IntegrationErrorCode.java` | M20 错误码枚举（INT-1001~INT-1012） |
| 修改 | `backend/fuyun-integration/pom.xml` | 增 MapStruct 依赖（版本父 POM 托管） |
| 修改 | `backend/fuyun-integration/src/main/java/com/fuyun/integration/constants/MessagingConstants.java` | 死信状态/上限/列宽/载荷预览/状态派生/模块标识常量 |
| 创建 | `backend/fuyun-integration/src/main/java/com/fuyun/integration/constants/MdmConstants.java` | 主数据主题、同步方式、分发模式、对账状态、五个主数据事件类型 |
| 创建 | `.../integration/convert/IntegrationConverter.java` | 实体 → 出参 MapStruct 转换器（A.7-4） |
| 创建 | `.../integration/dto/DeadLetterQuery.java` | 死信列表查询条件记录 |
| 创建 | `.../integration/dto/DeadLetterCloseRequest.java` | 死信关闭请求（原因必填） |
| 创建 | `.../integration/vo/DeadLetterVO.java` | 死信列表行出参（含载荷预览，不含全文） |
| 创建 | `.../integration/vo/DeadLetterDetailVO.java` | 死信详情出参（含载荷全文，诊断用） |
| 创建 | `.../integration/service/IDeadLetterService.java` | 死信管理服务契约（查询/详情/重放/关闭） |
| 创建 | `.../integration/service/impl/DeadLetterServiceImpl.java` | 死信管理与状态机实现（CAS 写 + 事务外投递） |
| 创建 | `.../integration/controller/DeadLetterController.java` | `GET /dead-letters`、`GET /{id}`、`POST /{id}/replay|close` |
| 修改 | `.../integration/internal/DeadLetterListener.java` | W-6①：留痕五列列宽钳长 |
| 修改 | `.../integration/service/impl/EventRegistryServiceImpl.java` | W-6②：订阅登记 CAS 并发守卫 + broadcast 拒订守卫 + 台账查询 |
| 修改 | `.../integration/service/IEventRegistryService.java` | 增台账查询方法 |
| 创建 | `.../integration/dto/EventRegistryQuery.java` | 契约台账查询条件 |
| 创建 | `.../integration/vo/EventRegistryVO.java` | 契约台账出参（含订阅清单） |
| 创建 | `.../integration/controller/EventRegistryController.java` | `GET /event-registry` |
| 创建 | `.../integration/dto/ReceivedEventQuery.java` | 消费台账查询条件（类型/时间/状态/消费者） |
| 创建 | `.../integration/vo/ReceivedEventVO.java` | 消费台账出参（含 FAILED 原因与重试计数） |
| 创建 | `.../integration/service/IReceivedEventQueryService.java` | 消费台账查询契约 |
| 创建 | `.../integration/service/impl/ReceivedEventQueryServiceImpl.java` | 消费台账分页查询实现 |
| 创建 | `.../integration/controller/ReceivedEventController.java` | `GET /received-events` |
| 创建 | `.../integration/entity/EventPublication.java` | Modulith 投递注册表只读投影（不含 serialized_event） |
| 创建 | `.../integration/mapper/EventPublicationMapper.java` | 投递注册表只读 mapper |
| 创建 | `.../integration/dto/EventPublicationQuery.java` | 投递台账查询条件（类型/完成态/时间窗） |
| 创建 | `.../integration/vo/EventPublicationVO.java` | 投递台账出参（COMPLETED/INCOMPLETE 派生态） |
| 创建 | `.../integration/service/IEventPublicationQueryService.java` | 投递台账查询契约 |
| 创建 | `.../integration/service/impl/EventPublicationQueryServiceImpl.java` | 投递台账分页查询实现 |
| 创建 | `.../integration/controller/EventPublicationController.java` | `GET /event-publications` |
| 修改 | `.../integration/service/impl/MessageIdempotencyServiceImpl.java` | FAILED 登记 + FAILED→PROCESSED 升级 + D-7 status 过滤 + settleFailure |
| 创建 | `.../integration/entity/MdmSubscription.java` | 主数据订阅台账实体（逻辑删） |
| 创建 | `.../integration/mapper/MdmSubscriptionMapper.java` | 订阅台账 mapper |
| 创建 | `.../integration/dto/MdmSubscriptionCreateRequest.java` | 订阅登记请求 |
| 创建 | `.../integration/dto/MdmSubscriptionQuery.java` | 订阅矩阵查询条件 |
| 创建 | `.../integration/vo/MdmSubscriptionVO.java` | 订阅矩阵行出参（主题×订阅方×版本×对账状态） |
| 创建 | `.../integration/service/IMdmSubscriptionService.java` | 订阅登记/注销/矩阵查询/订阅方清单契约 |
| 创建 | `.../integration/service/impl/MdmSubscriptionServiceImpl.java` | 订阅台账实现（登记幂等 + 逻辑删 + 主题校验） |
| 创建 | `.../integration/controller/MdmSubscriptionController.java` | `GET/POST /mdm-subscriptions`、`DELETE /{id}` |
| 创建 | `.../integration/entity/MdmDispatchLog.java` | 分发流水只增实体 |
| 创建 | `.../integration/mapper/MdmDispatchLogMapper.java` | 分发流水 mapper |
| 创建 | `.../integration/internal/MdmDispatchListener.java` | 五个主数据事件的消费侧分发流水登记 |
| 创建 | `.../integration/config/IntegrationWebConfig.java` | 模块 Web/查询/动作面装配（controller + service + converter Bean） |
| 创建 | `.../integration/config/IntegrationMdmConfig.java` | 主数据订阅服务装配 + 五个消费队列声明 + 消费者 @Import |
| 创建 | `backend/fuyun-app/src/main/java/com/fuyun/app/config/IntegrationConfig.java` | 装配归 app：@Import 上述两个模块配置 |
| 创建 | `backend/fuyun-integration/src/main/resources/db/migration/integration/V502__create_mdm_subscription.sql` | 主数据订阅台账（含 uk + 触发器） |
| 创建 | `backend/fuyun-integration/src/main/resources/db/migration/integration/V503__create_mdm_dispatch_log.sql` | 主数据分发流水（只增台账） |
| 修改 | `backend/fuyun-system/src/main/java/com/fuyun/system/internal/DictPublishedListener.java` | 消费范式同步（settleFailure + record 前置构造） |
| 修改 | `backend/fuyun-iot/src/main/java/com/fuyun/iot/internal/IotFanoutListener.java` | 消费范式同步（同型） |
| 修改 | `backend/fuyun-iot/src/main/java/com/fuyun/iot/properties/IotProperties.java` | W-5：Amqp/Fallback toString 脱敏 + A.5-9 措辞同步（D-8） |
| 修改 | `backend/fuyun-system/src/main/java/com/fuyun/system/properties/SecurityProperties.java` | W-5：toString 脱敏覆写 |
| 修改 | `backend/AGENTS.md` | D-8：A.5-9 正文修订（failover. 前缀 + 有限重试移交语义） |
| 修改 | `CHANGELOG.md` | 号段登记（V502/V503）+ D-8 条目与 2026-09-11 条目归源更正 |
| 修改 | `TASK.md` | D-8 行回填删除；FU-M20-04 跨模块前置新增 TODO 行 |
| 修改 | `backend/fuyun-app/src/test/java/com/fuyun/app/MessagingGovernanceIT.java` | 消费范式同步（settleFailure） |
| 创建 | `backend/fuyun-common/src/test/java/com/fuyun/common/utils/TextTruncateTest.java` | 截断工具单测 |
| 创建 | `backend/fuyun-common/src/test/java/com/fuyun/common/web/PageResultTest.java` | 分页契约单测 |
| 创建 | `backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/DeadLetterServiceImplTest.java` | 死信服务单测（查询/重放/上限/关闭/并发） |
| 创建 | `backend/fuyun-integration/src/test/java/com/fuyun/integration/convert/IntegrationConverterTest.java` | 转换器单测（预览截断） |
| 创建 | `backend/fuyun-integration/src/test/java/com/fuyun/integration/controller/DeadLetterControllerTest.java` | 死信端点薄层单测 |
| 创建 | `backend/fuyun-integration/src/test/java/com/fuyun/integration/controller/IntegrationQueryControllersTest.java` | 三个查询端点薄层单测（复用 `DictControllersTest` 合并类形态） |
| 创建 | `backend/fuyun-integration/src/test/java/com/fuyun/integration/controller/MdmSubscriptionControllerTest.java` | 订阅端点薄层单测 |
| 创建 | `backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/ReceivedEventQueryServiceImplTest.java` | 消费台账查询单测 |
| 创建 | `backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/EventPublicationQueryServiceImplTest.java` | 投递台账查询单测 |
| 创建 | `backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/MdmSubscriptionServiceImplTest.java` | 订阅登记/注销/矩阵单测 |
| 创建 | `backend/fuyun-integration/src/test/java/com/fuyun/integration/internal/MdmDispatchListenerTest.java` | 分发流水消费者单测 |
| 修改 | `backend/fuyun-integration/src/test/java/com/fuyun/integration/internal/DeadLetterListenerTest.java` | 增列宽钳长用例 |
| 修改 | `backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/EventRegistryServiceImplTest.java` | 增并发守卫/broadcast 拒订/台账查询用例 |
| 修改 | `backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/MessageIdempotencyServiceImplTest.java` | release 用例替换为 settleFailure/FAILED/升级用例 |
| 修改 | `backend/fuyun-iot/src/test/java/com/fuyun/iot/properties/IotPropertiesTest.java` | 增 toString 脱敏用例 |
| 修改 | `backend/fuyun-system/src/test/java/com/fuyun/system/properties/SecurityPropertiesTest.java` | 增 toString 脱敏用例 |
| 修改 | `backend/fuyun-iot/src/test/java/com/fuyun/iot/internal/IotFanoutListenerTest.java` | 范式同步（settleFailure 断言） |
| 修改 | `backend/fuyun-system/src/test/java/com/fuyun/system/internal/DictPublishedListenerTest.java` | 范式同步（settleFailure 断言） |
| 创建 | `backend/fuyun-app/src/test/java/com/fuyun/app/DeadLetterGovernanceIT.java` | 死信→重放→消费成功闭环 + FAILED 升级 + 关闭 + 分发流水 IT |

---

### Task 1: W-4 迁移治理校验脚本（号段归属 + 版本唯一 + 乱序守卫）

**Files:**
- Create: `scripts/check-migration-governance.py`
- Modify: `.pre-commit-config.yaml`（local hooks 段追加一条）
- Modify: `.github/workflows/ci.yml`（hygiene job）

**Interfaces:**
- Produces: 命令 `python scripts/check-migration-governance.py [--root <dir>] [--base-ref <git-ref>]`，退出码 0=全绿、1=有违规；脚本被 pre-commit hook `check-migration-governance` 与 CI hygiene job 同源调用——Task 8/9 新增 V502/V503 后由本脚本自动守护号段与乱序。

**背景（执行者必读）：** 三项校验的语义来自 backend 宪法 A.4.1-2（CI 校验号段归属与版本唯一）与 TASK.md W-4 的 PR-1a 实证补充（号段归属合法 ≠ 执行顺序合法）。号段登记载体是 `CHANGELOG.md`（2026-09-08 条目：「integration 治理域占用 V1–V99 … V300–V399 系统域、V400–V499 物联域为建议分段；V500 起按实装先后递增分配、先登记先占」），故脚本内「模块 → 号段」数据结构须与该登记行逐条对齐；未登记号段的模块（asset/billing/…/ward 等 15 个）只允许 V500+ 通用段。乱序守卫的「新增」判定必须是相对基线版本（本地默认 `HEAD`、CI 经环境变量 `MIGRATION_BASE_REF` 注入 PR base commit）的新增文件，因为全量清单本身跨段并存（V1–V5 与 V500/V501 合法共存），静态全量扫描无法表达「执行顺序合法」。

- [ ] **Step 1: 写脚本（含三项校验与三种退出路径）**

```python
#!/usr/bin/env python3
"""Flyway 迁移治理校验：号段归属 + 版本唯一 + 乱序守卫（backend 宪法 A.4.1-2/A.4.1-3，TASK.md W-4）。

三项校验：
  ① 号段归属——每个迁移文件所在 schema 目录须落在其登记号段内（含 V500+「先登记先占」通用段）；
  ② 版本唯一——全部 locations（各模块 db/migration/<schema>/）内版本号不得重复；
  ③ 乱序守卫——相对基线版本新增的迁移文件，版本号必须大于基线中全部 locations 已有最大版本号
     （真库已应用 500 段后，低版本号新文件会被 Flyway 以 outOfOrder=false 拒绝，2026-09-14 真栈实证）。

只读校验、不修改文件：任一违规即退出码 1，逐条打印中文报错（文件路径 + 违规类型）。
"""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

# 迁移文件路径形态：backend/<模块目录>/src/main/resources/db/migration/<schema>/V<版本>__<描述>.sql
_MIGRATION_FILE_RE = re.compile(r"^V(?P<version>\d+)__[a-z0-9_]+\.sql$")
_MIGRATION_DIR_GLOB = "backend/*/src/main/resources/db/migration/*"

# 号段登记表（载体 = CHANGELOG.md 2026-09-08 条目「Flyway 号段登记」，新增登记须同步本表）
# 值为号段区间元组列表，None 表示无上界；各模块一律允许 V500+ 通用段（先登记先占）
_SEGMENTS = {
    "integration": ((1, 99), (500, None)),
    "patient": ((100, 199), (500, None)),
    "outpatient": ((200, 299), (500, None)),
    "system": ((300, 399), (500, None)),
    "iot": ((400, 499), (500, None)),
}

# 未登记号段模块的唯一合法区间（V500+ 通用段）
_UNREGISTERED_SEGMENT = ((500, None),)


def _git(root: Path, *args: str) -> str | None:
    """执行只读 git 命令；仓库不可用或命令失败返回 None（由调用方决定告警语义）。"""
    try:
        result = subprocess.run(
            ["git", "-C", str(root), *args], capture_output=True, text=True, check=True
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    return result.stdout


def _iter_migration_files(root: Path) -> list[tuple[str, int, str]]:
    """扫描全部迁移文件，返回（schema, 版本号, 相对仓库根路径）三元组列表，按路径排序。

    只扫 backend/*/src/main/resources/db/migration/ 下的 src 路径：构建产物
    （target/classes/db/migration/…，PR-1a 改名前的 V6/V7 旧产物仍在）天然不在扫描面内。
    """
    found: list[tuple[str, int, str]] = []
    for directory in sorted(root.glob(_MIGRATION_DIR_GLOB)):
        if not directory.is_dir():
            continue
        for file in sorted(directory.glob("*.sql")):
            match = _MIGRATION_FILE_RE.match(file.name)
            if match is None:
                found.append((directory.name, -1, file.relative_to(root).as_posix()))
                continue
            found.append((directory.name, int(match.group("version")), file.relative_to(root).as_posix()))
    return found


def _in_segments(version: int, segments: tuple) -> bool:
    """版本号是否落在任一登记号段内（区间闭区间，None 表示无上界）。"""
    for lower, upper in segments:
        if version >= lower and (upper is None or version <= upper):
            return True
    return False


def check_segments(files: list[tuple[str, int, str]]) -> list[str]:
    """校验一：号段归属——schema 目录须与其登记号段匹配。"""
    problems: list[str] = []
    for schema, version, path in files:
        if version < 0:
            problems.append(f"[号段归属] {path}：文件名不符合 V<版本>__<全小写下划线描述>.sql 规范")
            continue
        segments = _SEGMENTS.get(schema, _UNREGISTERED_SEGMENT)
        if not _in_segments(version, segments):
            hint = "、".join(
                f"V{lower}-{'∞' if upper is None else 'V' + str(upper)}" for lower, upper in segments
            )
            problems.append(f"[号段归属] {path}：V{version} 不在 schema={schema} 的登记号段（{hint}）内")
    return problems


def check_duplicates(files: list[tuple[str, int, str]]) -> list[str]:
    """校验二：版本唯一——全部 locations 内版本号不得重复（Flyway 迁移历史全局唯一）。"""
    seen: dict[int, str] = {}
    problems: list[str] = []
    for _schema, version, path in files:
        if version < 0:
            continue
        if version in seen:
            problems.append(f"[版本重复] {path}：V{version} 已被 {seen[version]} 占用")
            continue
        seen[version] = path
    return problems


def _base_migration_versions(root: Path, base_ref: str) -> list[tuple[str, int, str]] | None:
    """读取基线版本中全部迁移文件三元组；基线不可解析（非 git 仓库/引用不存在）返回 None。"""
    listing = _git(root, "ls-tree", "-r", "--name-only", base_ref)
    if listing is None:
        return None
    prefix = "backend/"
    suffix = "/src/main/resources/db/migration/"
    base_files: list[tuple[str, int, str]] = []
    for line in listing.splitlines():
        if not line.startswith(prefix) or suffix not in line or not line.endswith(".sql"):
            continue
        schema_part = line.split(suffix, 1)[1]
        if "/" not in schema_part:
            continue
        schema, name = schema_part.split("/", 1)
        match = _MIGRATION_FILE_RE.match(name)
        if match is not None:
            base_files.append((schema, int(match.group("version")), line))
    return base_files


def check_out_of_order(
    root: Path, files: list[tuple[str, int, str]], base_ref: str
) -> list[str]:
    """校验三：乱序守卫——新增迁移版本号必须大于基线中已有最大版本号。"""
    base_files = _base_migration_versions(root, base_ref)
    if base_files is None:
        return [
            f"[乱序守卫] 基线版本 {base_ref} 无法解析：请先 git fetch（CI 需 fetch-depth: 0 与 "
            f"MIGRATION_BASE_REF 注入，见 .github/workflows/ci.yml hygiene job）"
        ]
    base_paths = {path for _schema, _version, path in base_files}
    max_base_version = max((version for _schema, version, _path in base_files), default=0)
    problems: list[str] = []
    for _schema, version, path in files:
        if path in base_paths or version < 0:
            continue
        if version <= max_base_version:
            problems.append(
                f"[乱序守卫] {path}：新增迁移 V{version} 不大于基线已有最大版本 V{max_base_version}"
                f"（Flyway outOfOrder=false 会拒绝执行，PR-1a 真栈实证）"
            )
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "校验 Flyway 迁移治理：号段归属 + 版本唯一 + 乱序守卫"
            "（backend 宪法 A.4.1-2/A.4.1-3；pre-commit 钩子 check-migration-governance 的实现）"
        )
    )
    parser.add_argument(
        "--root",
        default=None,
        help="仓库根目录（默认由本脚本位置推导：scripts/ 的上一级）；自测时可指向夹具目录",
    )
    parser.add_argument(
        "--base-ref",
        default=None,
        help="乱序守卫基线版本（默认取环境变量 MIGRATION_BASE_REF，未设时用 HEAD）",
    )
    args = parser.parse_args()

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parents[1]
    base_ref = args.base_ref or os.environ.get("MIGRATION_BASE_REF") or "HEAD"
    files = _iter_migration_files(root)
    problems = check_segments(files) + check_duplicates(files) + check_out_of_order(root, files, base_ref)

    if problems:
        for problem in problems:
            print(f"[迁移治理违规] {problem}")
        print(f"共发现 {len(problems)} 处迁移治理违规，请修复后重试（号段登记载体：CHANGELOG.md）")
        return 1
    print(f"迁移治理校验通过：{len(files)} 个迁移文件，基线 {base_ref}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: 对既有 15 个迁移跑全绿（验收口径 P1 计划 §PR-1）**

Run: `python scripts/check-migration-governance.py`
Expected: 输出 `迁移治理校验通过：15 个迁移文件，基线 HEAD`，退出码 0（`echo $?` 为 0）

- [ ] **Step 3: 构造夹具自测四类违规（越号/重号/越段/未登记模块）**

```bash
tmp=$(mktemp -d)
mkdir -p "$tmp/backend/fuyun-integration/src/main/resources/db/migration/integration"
mkdir -p "$tmp/backend/fuyun-system/src/main/resources/db/migration/system"
mkdir -p "$tmp/backend/fuyun-billing/src/main/resources/db/migration/billing"
printf -- '-- 夹具\n' > "$tmp/backend/fuyun-integration/src/main/resources/db/migration/integration/V1__create_x.sql"
printf -- '-- 夹具\n' > "$tmp/backend/fuyun-integration/src/main/resources/db/migration/integration/V500__create_y.sql"
printf -- '-- 夹具\n' > "$tmp/backend/fuyun-system/src/main/resources/db/migration/system/V300__create_z.sql"
git -C "$tmp" init -q && git -C "$tmp" add -A && git -C "$tmp" -c user.email=t@t -c user.name=t commit -qm base
# 绿：新增 V502（> 基线最大 500 且落在 integration 允许段）
printf -- '-- 夹具\n' > "$tmp/backend/fuyun-integration/src/main/resources/db/migration/integration/V502__create_new.sql"
python scripts/check-migration-governance.py --root "$tmp"; echo "绿用例退出码=$?"
# 红①乱序：integration 号段内合法但小于基线最大（PR-1a 实证场景）
printf -- '-- 夹具\n' > "$tmp/backend/fuyun-integration/src/main/resources/db/migration/integration/V6__create_late.sql"
python scripts/check-migration-governance.py --root "$tmp"; echo "乱序用例退出码=$?"
rm "$tmp/backend/fuyun-integration/src/main/resources/db/migration/integration/V6__create_late.sql"
# 红②重号：system 段内 V500 与 integration V500 重号
printf -- '-- 夹具\n' > "$tmp/backend/fuyun-system/src/main/resources/db/migration/system/V500__dup.sql"
python scripts/check-migration-governance.py --root "$tmp"; echo "重号用例退出码=$?"
rm "$tmp/backend/fuyun-system/src/main/resources/db/migration/system/V500__dup.sql"
# 红③越段 + 未登记模块：system V450 越段、billing V1 未登记号段
printf -- '-- 夹具\n' > "$tmp/backend/fuyun-system/src/main/resources/db/migration/system/V450__out_of_band.sql"
printf -- '-- 夹具\n' > "$tmp/backend/fuyun-billing/src/main/resources/db/migration/billing/V1__unregistered.sql"
python scripts/check-migration-governance.py --root "$tmp"; echo "越段用例退出码=$?"
rm -rf "$tmp"
```
Expected: 绿用例 `退出码=0`；乱序用例 `退出码=1` 且报错含 `[乱序守卫]`；重号用例 `退出码=1` 且报错含 `[版本重复]`；越段用例 `退出码=1` 且同时含 `[号段归属] ... system/V450` 与 `[号段归属] ... billing/V1`

- [ ] **Step 4: 注册 pre-commit 钩子（与 CI hygiene 同源）**

在 `.pre-commit-config.yaml` 的 `repos: - repo: local` → `hooks:` 段末（`backend-spotless-check` 之后）追加：

```yaml
      - id: check-migration-governance
        name: 校验 Flyway 迁移号段/唯一/乱序（宪法 A.4.1-2/A.4.1-3，脚本 scripts/check-migration-governance.py）
        entry: python scripts/check-migration-governance.py
        language: python
        files: ^backend/.*/src/main/resources/db/migration/.*\.sql$
        pass_filenames: false   # 全局校验语义（号段与唯一性跨目录判定），不按文件传参
```

- [ ] **Step 5: CI hygiene job 补基线可见性（乱序守卫需要 base commit）**

`.github/workflows/ci.yml` 的 `hygiene` job 两处修改：

```yaml
      - uses: actions/checkout@v7
        with:
          fetch-depth: 0      # 迁移治理乱序守卫需要基线 commit 可见（默认 depth=1 不可解析）
```

```yaml
      - name: 全量文件卫生校验
        env:
          # 乱序守卫基线：PR 事件取 base commit，push 事件取被推前一个 tip；
          # 其余事件为空 → 脚本回退 HEAD（等价「相对最近提交的新增」语义）
          MIGRATION_BASE_REF: ${{ github.event.pull_request.base.sha || github.event.before }}
        run: pre-commit run --all-files
```

- [ ] **Step 6: workflow 语法与钩子冒烟**

Run: `pre-commit run check-migration-governance --all-files 2>&1 | tail -3`
Expected: `校验 Flyway 迁移号段/唯一/乱序 ... Passed`（若本地未装 actionlint，workflow 语法由 CI hygiene job 兜底，PR 观察即可）

- [ ] **Step 7: Commit**

```bash
git add scripts/check-migration-governance.py .pre-commit-config.yaml .github/workflows/ci.yml
git commit -m "ci(scripts): 迁移号段/唯一/乱序三项治理校验脚本与门禁接线"
```

---

### Task 2: 死信查询与详情（常量 / 错误码 / 分页契约 / 转换器 / 服务 / 端点）

**Files:**
- Modify: `backend/fuyun-integration/pom.xml`（dependencies 段）
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/constants/MessagingConstants.java`
- Create: `backend/fuyun-common/src/main/java/com/fuyun/common/web/PageResult.java`
- Create: `backend/fuyun-common/src/main/java/com/fuyun/common/utils/TextTruncate.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/api/IntegrationErrorCode.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/convert/IntegrationConverter.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/dto/DeadLetterQuery.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/vo/DeadLetterVO.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/vo/DeadLetterDetailVO.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/service/IDeadLetterService.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/service/impl/DeadLetterServiceImpl.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/controller/DeadLetterController.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/config/IntegrationWebConfig.java`
- Create: `backend/fuyun-app/src/main/java/com/fuyun/app/config/IntegrationConfig.java`
- Test: `backend/fuyun-common/src/test/java/com/fuyun/common/utils/TextTruncateTest.java`
- Test: `backend/fuyun-common/src/test/java/com/fuyun/common/web/PageResultTest.java`
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/convert/IntegrationConverterTest.java`
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/DeadLetterServiceImplTest.java`
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/controller/DeadLetterControllerTest.java`

**Interfaces:**
- Consumes: `DeadLetter` 实体（`.../integration/entity/DeadLetter.java`，字段 id/sourceQueue/routingKey/eventType/eventId/payloadBody/payloadDigest/failReason/firstDeadAt/status/replayCount/handler/handleNote/handledAt/createdAt）与 `DeadLetterMapper`（`BaseMapper<DeadLetter>`，已有）。
- Produces:
  - `MessagingConstants.DEAD_LETTER_STATUS_REPLAYED/CLOSED`、`RECEIVED_STATUS_FAILED`、`FAIL_REASON_MAX_LENGTH=1000`、`DEAD_LETTER_SOURCE_QUEUE_MAX_LENGTH=128`、`DEAD_LETTER_ROUTING_KEY_MAX_LENGTH=128`、`DEAD_LETTER_EVENT_ID_MAX_LENGTH=64`、`DEAD_LETTER_EVENT_TYPE_MAX_LENGTH=128`、`DEAD_LETTER_PAYLOAD_PREVIEW_LENGTH=200`、`DEAD_LETTER_REPLAY_MAX_COUNT=3`、`HANDLER_MAX_LENGTH=64`、`MODULE="integration"`
  - `PageResult.of(List<T> content, long page, long size, long total)`（`com.fuyun.common.web.PageResult`，record 字段 `content/page/size/total`）
  - `TextTruncate.truncate(String text, int maxLength)`（`com.fuyun.common.utils.TextTruncate`）
  - `IntegrationErrorCode.DEAD_LETTER_NOT_FOUND("INT-1001")` / `DEAD_LETTER_STATUS_NOT_ACTIONABLE("INT-1002")` / `DEAD_LETTER_REPLAY_LIMIT_EXCEEDED("INT-1003")` / `DEAD_LETTER_NOT_REPLAYABLE("INT-1004")` / `DEAD_LETTER_REPLAY_DELIVERY_FAILED("INT-1005")` / `MDM_SUBSCRIPTION_NOT_FOUND("INT-1011")` / `MDM_TOPIC_UNKNOWN("INT-1012")`
  - `IntegrationConverter.INSTANCE` + 方法 `toDeadLetterVOs(List<DeadLetter>)`、`toDeadLetterVO(DeadLetter)`、`toDeadLetterDetailVO(DeadLetter)`
  - `IDeadLetterService.query(DeadLetterQuery) : PageResult<DeadLetterVO>`、`IDeadLetterService.detail(Long) : DeadLetterDetailVO`（Task 3 在本接口续加 `replay`/`close`）

- [ ] **Step 1: 分页契约与截断工具的失败单测**

`backend/fuyun-common/src/test/java/com/fuyun/common/web/PageResultTest.java`：

```java
package com.fuyun.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分页契约单测（backend 宪法 A.3-6）：四字段取值与空清单语义。
 */
class PageResultTest {

    @Test
    @DisplayName("分页出参契约：content/page/size/total 四字段按 0 基页码原样承载")
    void carriesContractFieldsAsIs() {
        PageResult<String> result = PageResult.of(List.of("a", "b"), 0L, 20L, 2L);

        assertThat(result.content()).containsExactly("a", "b");
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20L);
        assertThat(result.total()).isEqualTo(2L);
    }
}
```

`backend/fuyun-common/src/test/java/com/fuyun/common/utils/TextTruncateTest.java`：

```java
package com.fuyun.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 列宽截断工具单测：短文本与 null 原样返回、超长按最大字符数截断（列宽防线的公共实现）。
 */
class TextTruncateTest {

    @Test
    @DisplayName("短文本与 null 原样返回（可空列语义保留）")
    void keepsShortTextAndNullAsIs() {
        assertThat(TextTruncate.truncate("abc", 10)).isEqualTo("abc");
        assertThat(TextTruncate.truncate(null, 10)).isNull();
    }

    @Test
    @DisplayName("超长文本按最大字符数截断（保留头部，死信原因的信封不合规标注在前部）")
    void truncatesOverlongTextKeepingHead() {
        String overlong = "信封不合规：" + "x".repeat(2000);

        String truncated = TextTruncate.truncate(overlong, 1000);

        assertThat(truncated).hasSize(1000);
        assertThat(truncated).startsWith("信封不合规：");
    }
}
```

- [ ] **Step 2: 运行确认失败（两个类不存在）**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-common -am test -Dtest='PageResultTest,TextTruncateTest' -Dsurefire.failIfNoSpecifiedTests=false | tail -5`
Expected: COMPILATION ERROR（`PageResult` / `TextTruncate` 不存在）

- [ ] **Step 3: 写两个公共类**

`backend/fuyun-common/src/main/java/com/fuyun/common/web/PageResult.java`：

```java
package com.fuyun.common.web;

import java.util.List;

/**
 * 分页响应契约（backend 宪法 A.3-6）：请求 page 为 <b>0 基</b>，响应四字段 {content, page, size, total}。
 *
 * <p>本类为全模块统一分页出参形态的公共载体（共享内核 common），刻意不依赖 MyBatis-Plus：
 * 服务层负责把 IPage 转为本对象，common 保持零数据访问依赖（宪法 B.1 公共模块边界）。
 * 无状态不可变载体（record，A.1-2），可跨线程安全共享。
 *
 * @param content 当前页数据清单，非空；可为空清单（无匹配行场景）
 * @param page    当前页码（0 基，与请求同口径），非空
 * @param size    单页条数（请求期望值），非空
 * @param total   符合条件总条数（跨页累计），非空；用于前端计算总页数
 * @param <T>     数据元素类型
 */
public record PageResult<T>(List<T> content, long page, long size, long total) {

    /**
     * 构造分页出参。
     *
     * @param content 当前页数据清单，非空；可为空清单
     * @param page    当前页码（0 基），非空
     * @param size    单页条数，非空
     * @param total   总条数，非空
     * @param <T>     数据元素类型
     * @return 分页出参，非空
     */
    public static <T> PageResult<T> of(List<T> content, long page, long size, long total) {
        return new PageResult<>(content, page, size, total);
    }
}
```

`backend/fuyun-common/src/main/java/com/fuyun/common/utils/TextTruncate.java`：

```java
package com.fuyun.common.utils;

/**
 * 文本列宽截断工具：把超长文本按数据库列宽截断，null 原样返回。
 *
 * <p>存在原因（TASK.md W-6① 实证）：畸形帧的异常消息、x-death 来源队列等可超出 VARCHAR 列宽，
 * 直接落库触发整行写入失败——留痕静默丢失违背 M20「不合规信封拒收留痕」红线。截断保留头部
 * （不合规标注在前部，截断后仍可识别违规类型）。
 *
 * <p>无状态静态工具（纯函数），线程安全；common 共享内核承载，各模块复用（禁止各自复制实现）。
 */
public final class TextTruncate {

    /** 纯静态工具类，禁止实例化（backend 宪法 A.2-6）。 */
    private TextTruncate() {}

    /**
     * 按最大字符数截断文本。
     *
     * @param text      原文，可空；null 表示可空列语义，原样返回
     * @param maxLength 最大保留字符数（对应 DB 列宽），必须为正
     * @return 长度不超过 maxLength 的文本；入参为 null 返回 null
     */
    public static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-common -am test -Dtest='PageResultTest,TextTruncateTest' -Dsurefire.failIfNoSpecifiedTests=false | tail -5`
Expected: `Tests run: 3, Failures: 0`

- [ ] **Step 5: 常量与错误码扩展**

在 `MessagingConstants.java` 的 `DEAD_LETTER_STATUS_PENDING` 行之后追加（同文件末尾 `ENVELOPE_DEFAULT_VERSION` 附近按语义就近放置亦可，禁止改动既有常量）：

```java
    /** dead_letter 状态：已重放（重放投递成功，M20 §5 状态机 PENDING → REPLAYED） */
    public static final String DEAD_LETTER_STATUS_REPLAYED = "REPLAYED";

    /** dead_letter 状态：已关闭（终态，必须填写关闭原因；不得再重放） */
    public static final String DEAD_LETTER_STATUS_CLOSED = "CLOSED";

    /** received_event 状态：消费失败（D-7 回查必须过滤 status=PROCESSED，FAILED 行不得误判为已处理） */
    public static final String RECEIVED_STATUS_FAILED = "FAILED";

    /** 死信重推上限（次）：达到后重推接口拒绝（Spec 未定义，控制器 2026-09-15 拍板口径） */
    public static final int DEAD_LETTER_REPLAY_MAX_COUNT = 3;

    /** fail_reason 列宽防线：dead_letter.fail_reason 与 received_event.fail_reason 同为 VARCHAR(1000) */
    public static final int FAIL_REASON_MAX_LENGTH = 1000;

    /** source_queue 列宽防线：dead_letter.source_queue VARCHAR(128)（x-death 队列名理论可超） */
    public static final int DEAD_LETTER_SOURCE_QUEUE_MAX_LENGTH = 128;

    /** routing_key 列宽防线：dead_letter.routing_key VARCHAR(128) */
    public static final int DEAD_LETTER_ROUTING_KEY_MAX_LENGTH = 128;

    /** event_id 列宽防线：dead_letter.event_id VARCHAR(64)（信封 eventId 为 UUID 字符串，理论超长面） */
    public static final int DEAD_LETTER_EVENT_ID_MAX_LENGTH = 64;

    /** event_type 列宽防线：dead_letter.event_type VARCHAR(128) */
    public static final int DEAD_LETTER_EVENT_TYPE_MAX_LENGTH = 128;

    /** 载荷预览长度（字符）：列表页仅出头部预览，全文只经详情端点（诊断看载荷） */
    public static final int DEAD_LETTER_PAYLOAD_PREVIEW_LENGTH = 200;

    /** handler 列宽防线：dead_letter.handler VARCHAR(64)（操作人标识超长收口，防整行写入失败） */
    public static final int HANDLER_MAX_LENGTH = 64;

    /** 本模块消费者/生产者域标识：队列命名与 received_event.consumer_module 的第二要素 */
    public static final String MODULE = "integration";
```

新建 `backend/fuyun-integration/src/main/java/com/fuyun/integration/api/IntegrationErrorCode.java`：

```java
package com.fuyun.integration.api;

import com.fuyun.common.exception.ErrorCode;

/**
 * M20 集成平台模块错误码枚举（INT-xxxx，backend 宪法 A.3-4）。
 *
 * <p>落 api 包为宪法 B.1 明文（错误码枚举属对外契约）；实现 common {@link ErrorCode} 契约，
 * 全项目编码唯一（当前 INT- 前缀无其他占用）。业务异常抛
 * {@code BizException(IntegrationErrorCode.XXX, HttpStatus, message)}，由全局渲染器输出
 * RFC 9457 ProblemDetail（properties.errorCode/traceId），禁止「全 200 + 错误码」。
 *
 * <p>段位约定：1001-1005 死信管理；1011-1012 主数据分发治理。后续码段随 FU 实装扩充。
 */
public enum IntegrationErrorCode implements ErrorCode {

    /** 死信不存在（404；id 未命中台账） */
    DEAD_LETTER_NOT_FOUND("INT-1001"),

    /** 死信当前状态不允许该操作（409；重放仅 PENDING，关闭仅 PENDING，CLOSED 为终态） */
    DEAD_LETTER_STATUS_NOT_ACTIONABLE("INT-1002"),

    /** 死信重推次数已达上限（409；上限 DEAD_LETTER_REPLAY_MAX_COUNT，控制器拍板值） */
    DEAD_LETTER_REPLAY_LIMIT_EXCEEDED("INT-1003"),

    /** 死信不可重放（409；信封不合规无路由键/来源队列缺失或已下线，重放必然不可路由） */
    DEAD_LETTER_NOT_REPLAYABLE("INT-1004"),

    /** 死信重放投递失败（500；broker 不可达等，已回到待处理并累加重放次数） */
    DEAD_LETTER_REPLAY_DELIVERY_FAILED("INT-1005"),

    /** 主数据订阅记录不存在（404；注销未命中台账） */
    MDM_SUBSCRIPTION_NOT_FOUND("INT-1011"),

    /** 未知主数据主题（400；合法主题见 MdmConstants.MDM_TOPICS） */
    MDM_TOPIC_UNKNOWN("INT-1012");

    /** 错误码字符串，格式 {@code <模块助记>-<4位数字>} */
    private final String code;

    IntegrationErrorCode(String code) {
        this.code = code;
    }

    /**
     * 取业务错误码。
     *
     * @return 错误码字符串（如 INT-1001），非空；经全局渲染输出至 ProblemDetail.properties.errorCode
     */
    @Override
    public String getCode() {
        return code;
    }
}
```

- [ ] **Step 6: 接入 MapStruct（pom）**

`backend/fuyun-integration/pom.xml` 的 `lombok` 依赖块之前追加：

```xml
    <!-- DTO/VO 映射（A.7-4）：IntegrationConverter，版本父 POM 托管，处理器路径父 compiler 插件已配置 -->
    <dependency>
      <groupId>org.mapstruct</groupId>
      <artifactId>mapstruct</artifactId>
    </dependency>
    <!-- Web 层显式声明（本模块新增 @RestController 后按模块显式声明风格落盘，二选一取显式）：
         此前经 fuyun-common 传递可得（fuyun-common pom 声明 starter-web），显式声明使本模块 Web 依赖自洽、
         不依赖传递路径稳定性；版本 Boot BOM 托管，禁写版本号 -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
```

- [ ] **Step 7: 转换器失败单测**

`backend/fuyun-integration/src/test/java/com/fuyun/integration/convert/IntegrationConverterTest.java`：

```java
package com.fuyun.integration.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.entity.DeadLetter;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 治理域转换器单测：死信实体 → 列表行/详情出参映射，列表行载荷只出预览（全文仅详情端点）。
 */
class IntegrationConverterTest {

    @Test
    @DisplayName("列表行出参：载荷字段只出头部预览，摘要与身份字段原样承载")
    void listRowCarriesPayloadPreviewOnly() {
        DeadLetter entity = sample("y".repeat(500));

        DeadLetterVO vo = IntegrationConverter.INSTANCE.toDeadLetterVO(entity);

        assertThat(vo.payloadPreview()).hasSize(MessagingConstants.DEAD_LETTER_PAYLOAD_PREVIEW_LENGTH);
        assertThat(vo.id()).isEqualTo(7L);
        assertThat(vo.eventId()).isEqualTo("b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60");
        assertThat(vo.replayCount()).isZero();
    }

    @Test
    @DisplayName("详情出参：载荷全文承载，短载荷预览不截断")
    void detailCarriesFullPayload() {
        DeadLetter entity = sample("{\"eventId\":\"x\"}");

        DeadLetterDetailVO detail = IntegrationConverter.INSTANCE.toDeadLetterDetailVO(entity);

        assertThat(detail.payloadBody()).isEqualTo("{\"eventId\":\"x\"}");
        assertThat(IntegrationConverter.INSTANCE.toDeadLetterVO(entity).payloadPreview())
                .isEqualTo("{\"eventId\":\"x\"}");
    }

    /**
     * 构造死信样本行。
     *
     * @param payloadBody 载荷原文，非空
     * @return 死信实体
     */
    private DeadLetter sample(String payloadBody) {
        DeadLetter entity = new DeadLetter();
        entity.setId(7L);
        entity.setSourceQueue("q.it.system.dict.published");
        entity.setRoutingKey("system.dict.published");
        entity.setEventType("system.dict.published");
        entity.setEventId("b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60");
        entity.setPayloadBody(payloadBody);
        entity.setPayloadDigest("d".repeat(64));
        entity.setFailReason("消费死信：reason=rejected");
        entity.setFirstDeadAt(OffsetDateTime.parse("2026-09-15T01:02:03Z"));
        entity.setStatus(MessagingConstants.DEAD_LETTER_STATUS_PENDING);
        entity.setReplayCount(0);
        return entity;
    }
}
```

- [ ] **Step 8: 写转换器实现**

`backend/fuyun-integration/src/main/java/com/fuyun/integration/convert/IntegrationConverter.java`：

```java
package com.fuyun.integration.convert;

import com.fuyun.common.utils.TextTruncate;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.entity.DeadLetter;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * M20 治理域 MapStruct 转换器（backend 宪法 A.7-4）：台账实体 → 出参对象映射。
 *
 * <p>componentModel 取默认（非 spring）：Bean 注册点为 IntegrationWebConfig 经
 * {@link Mappers#getMapper} 装配（宪法 B.1 装配归 app 侧配置）。
 *
 * <p>最小暴露原则：死信列表行只出载荷头部预览（{@link MessagingConstants#DEAD_LETTER_PAYLOAD_PREVIEW_LENGTH}
 * 字符）+ SHA-256 摘要，载荷全文只经详情端点（运维诊断看载荷场景，M20 §3.2）。
 */
@Mapper
public interface IntegrationConverter {

    /** 默认组件模型的生成实现获取入口（单测与装配同源） */
    IntegrationConverter INSTANCE = Mappers.getMapper(IntegrationConverter.class);

    /**
     * 死信实体 → 列表行出参。
     *
     * @param entity 死信实体，非空
     * @return 列表行出参（payloadBody 不出现，仅预览），非空
     */
    @Mapping(target = "payloadPreview", source = "payloadBody")
    DeadLetterVO toDeadLetterVO(DeadLetter entity);

    /**
     * 死信实体清单 → 列表行出参清单。
     *
     * @param entities 死信实体清单，非空（可为空清单）
     * @return 列表行出参清单，非 null
     */
    List<DeadLetterVO> toDeadLetterVOs(List<DeadLetter> entities);

    /**
     * 死信实体 → 详情出参（含载荷全文、处理人/备注/时间留痕）。
     *
     * @param entity 死信实体，非空
     * @return 详情出参，非空
     */
    DeadLetterDetailVO toDeadLetterDetailVO(DeadLetter entity);

    /**
     * 载荷预览：截取原文头部固定长度（列表页防大字段刷屏与最小暴露）。
     *
     * @param payloadBody 载荷原文，可空
     * @return 长度不超过预览上限的文本；入参为 null 返回 null
     */
    default String mapPayloadPreview(String payloadBody) {
        return TextTruncate.truncate(payloadBody, MessagingConstants.DEAD_LETTER_PAYLOAD_PREVIEW_LENGTH);
    }
}
```

同包新建 `vo/DeadLetterVO.java`：

```java
package com.fuyun.integration.vo;

import java.time.OffsetDateTime;

/**
 * 死信列表行出参（GET /api/v1/integration/dead-letters）。
 *
 * <p>record 透明浅不可变载体（A.1-2）。载荷只出头部预览与摘要，全文经详情端点
 * （最小暴露：列表可能一次返回多帧原文，避免大字段与敏感内容批量外泄）。
 *
 * @param id             死信 ID（雪花 ID），非空；JSON 输出为字符串
 * @param sourceQueue    来源队列（x-death[].queue），非空
 * @param routingKey     原始路由键（=事件类型），可空（轨迹缺失帧）
 * @param eventType      事件类型，可空（信封不合规帧为空）
 * @param eventId        信封 eventId，可空（信封不合规帧为空）；死信溯源锚点
 * @param payloadDigest  载荷 SHA-256 摘要（64 位十六进制），可空
 * @param payloadPreview 载荷头部预览（不超过 DEAD_LETTER_PAYLOAD_PREVIEW_LENGTH 字符），可空
 * @param failReason     死信原因，非空
 * @param firstDeadAt    首次死信时间，非空
 * @param status         处理状态（PENDING/REPLAYED/CLOSED），非空
 * @param replayCount    重放计数（含失败重放），非空
 * @param handler        处理人标识，可空（尚未处理）
 * @param handleNote     处理备注（关闭原因），可空
 * @param handledAt      处理时间，可空（尚未处理）
 */
public record DeadLetterVO(
        Long id,
        String sourceQueue,
        String routingKey,
        String eventType,
        String eventId,
        String payloadDigest,
        String payloadPreview,
        String failReason,
        OffsetDateTime firstDeadAt,
        String status,
        Integer replayCount,
        String handler,
        String handleNote,
        OffsetDateTime handledAt) {}
```

同包新建 `vo/DeadLetterDetailVO.java`：

```java
package com.fuyun.integration.vo;

import java.time.OffsetDateTime;

/**
 * 死信详情出参（GET /api/v1/integration/dead-letters/{id}）：列表行全部字段 + 载荷全文。
 *
 * <p>载荷全文供运维诊断（M20 §3.2「诊断：看载荷、失败原因、源队列」）；本端点用于重放前人工
 * 核对原文，禁止在列表端点返回全文。
 *
 * @param id             死信 ID（雪花 ID），非空；JSON 输出为字符串
 * @param sourceQueue    来源队列，非空
 * @param routingKey     原始路由键，可空
 * @param eventType      事件类型，可空
 * @param eventId        信封 eventId，可空
 * @param payloadBody    载荷原文全文（重放依赖同一原文），非空
 * @param payloadDigest  载荷 SHA-256 摘要，可空
 * @param failReason     死信原因，非空
 * @param firstDeadAt    首次死信时间，非空
 * @param status         处理状态，非空
 * @param replayCount    重放计数，非空
 * @param handler        处理人标识，可空
 * @param handleNote     处理备注，可空
 * @param handledAt      处理时间，可空
 */
public record DeadLetterDetailVO(
        Long id,
        String sourceQueue,
        String routingKey,
        String eventType,
        String eventId,
        String payloadBody,
        String payloadDigest,
        String failReason,
        OffsetDateTime firstDeadAt,
        String status,
        Integer replayCount,
        String handler,
        String handleNote,
        OffsetDateTime handledAt) {}
```

- [ ] **Step 9: 运行确认转换器用例通过**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest=IntegrationConverterTest -Dsurefire.failIfNoSpecifiedTests=false | tail -8`
Expected: `Tests run: 2, Failures: 0`；若生成实现未调用 `mapPayloadPreview`（预览与原文等长断言失败），把列表映射改成表达式形态二选一：`@Mapping(target = "payloadPreview", expression = "java(IntegrationConverter.INSTANCE.mapPayloadPreview(entity.getPayloadBody()))")`，两种写法只留一种

- [ ] **Step 10: 查询条件记录与死信服务失败单测**

`dto/DeadLetterQuery.java`：

```java
package com.fuyun.integration.dto;

/**
 * 死信列表查询条件（GET /api/v1/integration/dead-letters，A.7-1 参数对象化）。
 *
 * @param status      处理状态过滤（PENDING/REPLAYED/CLOSED），可空 = 不过滤
 * @param eventType   事件类型过滤，可空 = 不过滤
 * @param eventId     信封 eventId 过滤（死信溯源锚点），可空 = 不过滤
 * @param sourceQueue 来源队列过滤，可空 = 不过滤
 * @param page        页码（0 基，宪法 A.3-6），非空；来源：请求参数（缺省 0）
 * @param size        单页条数（1-200），非空；来源：请求参数（缺省 20）
 */
public record DeadLetterQuery(String status, String eventType, String eventId, String sourceQueue, int page, int size) {}
```

`service/IDeadLetterService.java`：

```java
package com.fuyun.integration.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.DeadLetterQuery;
import com.fuyun.integration.entity.DeadLetter;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;

/**
 * 死信管理服务：dead_letter 台账的查询与处置入口（M20 §5 死信处理流程）。
 *
 * <p>状态机（Spec §5 原文）：PENDING → REPLAYED（重放投递成功）；重放失败回到 PENDING 并累加
 * 重放次数；PENDING → CLOSED（关闭，必填原因）；CLOSED 为终态不得再重放。重放/关闭全留痕
 * （handler/handle_note/handled_at/replay_count）。
 */
public interface IDeadLetterService extends IService<DeadLetter> {

    /**
     * 分页查询死信台账（按状态/类型/事件/来源队列过滤，按首次死信时间倒序）。
     *
     * @param query 查询条件，非空；page 0 基、size 1-200
     * @return 分页出参（0 基页码），非空；无匹配时 content 为空清单
     */
    PageResult<DeadLetterVO> query(DeadLetterQuery query);

    /**
     * 读取死信详情（含载荷全文，运维诊断用）。
     *
     * @param id 死信 ID，非空
     * @return 详情出参，非空
     * @throws com.fuyun.common.exception.BizException 死信不存在（INT-1001，404）时触发；
     *                                                  建议处理策略：前端提示记录不存在并刷新列表
     */
    DeadLetterDetailVO detail(Long id);
}
```

`service/impl/DeadLetterServiceImpl.java`（查询/详情部分）：

```java
package com.fuyun.integration.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.api.IntegrationErrorCode;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.DeadLetterQuery;
import com.fuyun.integration.entity.DeadLetter;
import com.fuyun.integration.mapper.DeadLetterMapper;
import com.fuyun.integration.service.IDeadLetterService;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 死信管理服务实现：查询与处置的单一写入口（M20 §5 状态机的执行点）。
 *
 * <p>事务边界（A.4.2-7）：查询方法只读事务；处置动作（重放/关闭）的事务边界与 MQ 投递分离——
 * 投递在事务外先执行，随后以单语句 CAS UPDATE 落状态（含状态与上限守卫），多实例并发下
 * 后到者影响 0 行，无丢更新与双处置。
 *
 * <p>归 service/impl 包 = JaCoCo 核心包 PACKAGE LINE 1.00 覆盖对象（backend/pom.xml 核心包名单）。
 */
@Slf4j
public class DeadLetterServiceImpl extends ServiceImpl<DeadLetterMapper, DeadLetter> implements IDeadLetterService {

    private final IntegrationConverter converter;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import）。
     *
     * @param converter 治理域转换器，非空；来源：IntegrationWebConfig @Bean
     */
    public DeadLetterServiceImpl(IntegrationConverter converter) {
        this.converter = converter;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<DeadLetterVO> query(DeadLetterQuery query) {
        LambdaQueryWrapper<DeadLetter> wrapper = Wrappers.lambdaQuery(DeadLetter.class)
                .eq(query.status() != null, DeadLetter::getStatus, query.status())
                .eq(query.eventType() != null, DeadLetter::getEventType, query.eventType())
                .eq(query.eventId() != null, DeadLetter::getEventId, query.eventId())
                .eq(query.sourceQueue() != null, DeadLetter::getSourceQueue, query.sourceQueue())
                // 排序唯一性约束（A.4.3-17）：时间相同时以主键兜底，防深翻页漏行
                .orderByDesc(DeadLetter::getFirstDeadAt)
                .orderByDesc(DeadLetter::getId);
        // 契约 0 基（A.3-6）↔ MP 分页器 1 基：服务层唯一转换点，进出各一次
        Page<DeadLetter> page = this.page(new Page<>(query.page() + 1L, query.size()), wrapper);
        return PageResult.of(
                converter.toDeadLetterVOs(page.getRecords()),
                page.getCurrent() - 1,
                page.getSize(),
                page.getTotal());
    }

    @Override
    @Transactional(readOnly = true)
    public DeadLetterDetailVO detail(Long id) {
        DeadLetter row = this.getById(id);
        if (row == null) {
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_NOT_FOUND, HttpStatus.NOT_FOUND, "死信不存在：id=" + id);
        }
        return converter.toDeadLetterDetailVO(row);
    }
}
```

`backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/DeadLetterServiceImplTest.java`：

```java
package com.fuyun.integration.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.api.IntegrationErrorCode;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.DeadLetterQuery;
import com.fuyun.integration.entity.DeadLetter;
import com.fuyun.integration.mapper.DeadLetterMapper;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 死信管理服务单测：分页契约转换、条件过滤、详情不存在语义（mapper 以 Mockito 模拟，
 * MP 表信息缓存手工装载——容器外单测的既有范式，见 EventRegistryServiceImplTest）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeadLetterServiceImplTest {

    @Mock
    private DeadLetterMapper deadLetterMapper;

    private DeadLetterServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件解析列名依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DeadLetter.class);
    }

    @BeforeEach
    void setUp() {
        service = new DeadLetterServiceImpl(IntegrationConverter.INSTANCE);
        // ServiceImpl 的 baseMapper 为 protected 字段，单测经反射注入 mock（既有范式）
        ReflectionTestUtils.setField(service, "baseMapper", deadLetterMapper);
        ReflectionTestUtils.setField(service, "entityClass", DeadLetter.class);
    }

    @Test
    @DisplayName("分页查询：0 基请求转为 MP 1 基后的出参页码仍为 0 基，出参含预览与摘要")
    void queryKeepsZeroBasedPageContract() {
        DeadLetter row = pendingRow(9L, 0);
        when(deadLetterMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenAnswer(invocation -> {
            IPage<DeadLetter> page = invocation.getArgument(0);
            page.setRecords(List.of(row));
            page.setTotal(1L);
            return page;
        });

        PageResult<DeadLetterVO> result =
                service.query(new DeadLetterQuery("PENDING", "system.dict.published", null, null, 0, 20));

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20L);
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).payloadPreview()).isEqualTo("{\"eventId\":\"x\"}");
        // MP 分页器以 1 基接收（契约 0 基 → 内部 1 基转换）
        verify(deadLetterMapper).selectPage(any(IPage.class), any(Wrapper.class));
    }

    @Test
    @DisplayName("详情查询：命中返回全文载荷；未命中抛 404 业务异常（INT-1001）")
    void detailReturnsFullPayloadOrThrowsNotFound() {
        DeadLetter row = pendingRow(9L, 0);
        when(deadLetterMapper.selectById(9L)).thenReturn(row);
        DeadLetterDetailVO detail = service.detail(9L);
        assertThat(detail.payloadBody()).isEqualTo("{\"eventId\":\"x\"}");
        assertThat(detail.status()).isEqualTo("PENDING");

        when(deadLetterMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.detail(404L))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_NOT_FOUND);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    /**
     * 构造 PENDING 死信样本行。
     *
     * @param id          死信 ID
     * @param replayCount 重放计数
     * @return 死信实体
     */
    private DeadLetter pendingRow(Long id, int replayCount) {
        DeadLetter row = new DeadLetter();
        row.setId(id);
        row.setSourceQueue("q.it.system.dict.published");
        row.setRoutingKey("system.dict.published");
        row.setEventType("system.dict.published");
        row.setEventId("b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60");
        row.setPayloadBody("{\"eventId\":\"x\"}");
        row.setPayloadDigest("d".repeat(64));
        row.setFailReason("消费死信：reason=rejected");
        row.setFirstDeadAt(OffsetDateTime.parse("2026-09-15T01:02:03Z"));
        row.setStatus("PENDING");
        row.setReplayCount(replayCount);
        return row;
    }
}
```

- [ ] **Step 11: 运行确认失败→实现→通过（红灯核对：Step 10 的测试类此刻应编译失败）**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest=DeadLetterServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false | tail -5`
Expected: 首次为 COMPILATION ERROR（`IDeadLetterService`/`DeadLetterServiceImpl` 不存在）→ 落 Step 9/Step 10 的实现类后复跑为 `Tests run: 2, Failures: 0`

- [ ] **Step 12: 控制器与薄层单测**

`controller/DeadLetterController.java`：

```java
package com.fuyun.integration.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.DeadLetterQuery;
import com.fuyun.integration.service.IDeadLetterService;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 死信管理读端点（GET /api/v1/integration/dead-letters[/{id}]，M20 §7）。
 *
 * <p>受既有 /api/v1/** 认证拦截（SystemWebConfig，未认证 401）；职责边界：仅参数透传与响应编排
 * （宪法 B.1），禁业务逻辑、禁 @Transactional。分页契约 page 0 基 / size 1-200（A.3-6），越界由
 * 方法级校验渲染 400 ProblemDetail（Spring 6.2 内建 HandlerMethodValidationException 处理）。
 */
@RestController
@RequestMapping("/api/v1/integration/dead-letters")
@Validated
public class DeadLetterController {

    private final IDeadLetterService deadLetterService;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import，backend 宪法 B.1）。
     *
     * @param deadLetterService 死信管理服务，非空；注入接口类型（B.2-2）
     */
    public DeadLetterController(IDeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    /**
     * 分页查询死信（按状态/事件类型/事件 ID/来源队列过滤，首次死信时间倒序）。
     *
     * @param status      处理状态过滤，可空 = 不过滤
     * @param eventType   事件类型过滤，可空 = 不过滤
     * @param eventId     信封 eventId 过滤，可空 = 不过滤
     * @param sourceQueue 来源队列过滤，可空 = 不过滤
     * @param page        页码（0 基），非空，缺省 0
     * @param size        单页条数（1-200），非空，缺省 20
     * @return 分页出参（0 基页码）
     */
    @GetMapping
    public PageResult<DeadLetterVO> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "eventId", required = false) String eventId,
            @RequestParam(value = "sourceQueue", required = false) String sourceQueue,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return deadLetterService.query(new DeadLetterQuery(status, eventType, eventId, sourceQueue, page, size));
    }

    /**
     * 读取死信详情（含载荷全文，重放前人工核对）。
     *
     * @param id 死信 ID（路径参数），非空
     * @return 详情出参；不存在时由全局渲染器输出 404 ProblemDetail（INT-1001）
     */
    @GetMapping("/{id}")
    public DeadLetterDetailVO detail(@PathVariable("id") Long id) {
        return deadLetterService.detail(id);
    }
}
```

`controller/DeadLetterControllerTest.java`：

```java
package com.fuyun.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.DeadLetterQuery;
import com.fuyun.integration.service.IDeadLetterService;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 死信端点单元测试（controller 编排薄层）：查询参数装配与响应直返，业务逻辑归 service 层测试。
 */
@ExtendWith(MockitoExtension.class)
class DeadLetterControllerTest {

    @Mock
    private IDeadLetterService deadLetterService;

    @Captor
    private ArgumentCaptor<DeadLetterQuery> queryCaptor;

    private DeadLetterController controller;

    @BeforeEach
    void setUp() {
        controller = new DeadLetterController(deadLetterService);
    }

    @Test
    @DisplayName("列表端点：六个请求参数按序装配为查询对象，服务出参直返（无 envelope 包装）")
    void listDelegatesQueryParametersAsIs() {
        PageResult<DeadLetterVO> expected = PageResult.of(List.of(), 0L, 20L, 0L);
        when(deadLetterService.query(any())).thenReturn(expected);

        PageResult<DeadLetterVO> actual =
                controller.list("PENDING", "system.dict.published", "e-1", "q.it.system.dict.published", 2, 50);

        assertThat(actual).isSameAs(expected);
        verify(deadLetterService).query(queryCaptor.capture());
        DeadLetterQuery query = queryCaptor.getValue();
        assertThat(query.status()).isEqualTo("PENDING");
        assertThat(query.eventType()).isEqualTo("system.dict.published");
        assertThat(query.eventId()).isEqualTo("e-1");
        assertThat(query.sourceQueue()).isEqualTo("q.it.system.dict.published");
        assertThat(query.page()).isEqualTo(2);
        assertThat(query.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("详情端点：路径 id 原样透传服务层")
    void detailDelegatesPathId() {
        DeadLetterDetailVO expected = new DeadLetterDetailVO(
                9L, "q.it.system.dict.published", "system.dict.published", "system.dict.published",
                "b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60", "{}", "d", "原因", null, "PENDING", 0, null, null, null);
        when(deadLetterService.detail(9L)).thenReturn(expected);

        assertThat(controller.detail(9L)).isSameAs(expected);
    }
}
```

对应的 import 段（短名，A.1-13 禁全限定类名声明）：

```java
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.DeadLetterQuery;
import com.fuyun.integration.service.IDeadLetterService;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;
import java.util.List;
```

（`@Captor private ArgumentCaptor<DeadLetterQuery> queryCaptor;` 与 `@Mock private IDeadLetterService deadLetterService;` 字段声明同前文所列。）

- [ ] **Step 13: 装配（模块配置 + app 配置）**

`backend/fuyun-integration/src/main/java/com/fuyun/integration/config/IntegrationWebConfig.java`：

```java
package com.fuyun.integration.config;

import com.fuyun.integration.controller.DeadLetterController;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.service.impl.DeadLetterServiceImpl;
import org.mapstruct.factory.Mappers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M20 治理 Web/查询/处置面装配：控制器与查询服务 Bean 的集中注册点（backend 宪法 B.1 装配归 app，
 * 本类由 fuyun-app IntegrationConfig @Import 生效；com.fuyun.integration 不在组件扫描范围）。
 *
 * <p>与 {@link MessagingGovernanceConfig} 分工：本类承载对外 REST 面与查询服务；消息治理构件
 * （交换机/队列声明、幂等、死信监听）仍归消息治理配置类，两者不重叠。
 */
@Configuration
@Import({DeadLetterServiceImpl.class, DeadLetterController.class})
public class IntegrationWebConfig {

    /**
     * 治理域 MapStruct 转换器 Bean：接口不可经 @Import 注册，经 Mappers.getMapper 装配生成实现
     * （与单测取用同源，SystemWebConfig 的 authConverter/dictConverter 同款）。
     *
     * @return 治理域转换器
     */
    @Bean
    public IntegrationConverter integrationConverter() {
        return Mappers.getMapper(IntegrationConverter.class);
    }
}
```

`backend/fuyun-app/src/main/java/com/fuyun/app/config/IntegrationConfig.java`（**本任务只 import 已有配置类**；Task 8 交付 `IntegrationMdmConfig` 时在 @Import 清单内追加，保持每步可编译）：

```java
package com.fuyun.app.config;

import com.fuyun.integration.config.IntegrationWebConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M20 集成平台治理装配：将 fuyun-integration 的治理配置类引入 Boot 上下文的集中入口
 * （backend 宪法 B.1 装配归 app，与 SystemConfig/IotConfig 同模式，不放宽组件扫描）。
 *
 * <p>治理 Web/查询/处置面经 {@link IntegrationWebConfig} 生效（主数据分发订阅与流水治理配置类
 * 随 FU-M20-04 交付时在本 @Import 清单内追加）。
 */
@Configuration
@Import(IntegrationWebConfig.class)
public class IntegrationConfig {}
```

- [ ] **Step 14: 编译与两个模块单测**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest='IntegrationConverterTest,DeadLetterServiceImplTest,DeadLetterControllerTest' -Dsurefire.failIfNoSpecifiedTests=false | tail -8`
Expected: `Tests run: 6, Failures: 0`（转换器 2 + 死信服务 2 + 死信端点 2）

- [ ] **Step 15: Commit**

```bash
git add backend/fuyun-common/src/main/java/com/fuyun/common/web/PageResult.java \
        backend/fuyun-common/src/main/java/com/fuyun/common/utils/TextTruncate.java \
        backend/fuyun-common/src/test/java/com/fuyun/common/web/PageResultTest.java \
        backend/fuyun-common/src/test/java/com/fuyun/common/utils/TextTruncateTest.java \
        backend/fuyun-integration/pom.xml \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/constants/MessagingConstants.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/api/IntegrationErrorCode.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/convert/IntegrationConverter.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/dto/DeadLetterQuery.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/vo/DeadLetterVO.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/vo/DeadLetterDetailVO.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/service/IDeadLetterService.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/service/impl/DeadLetterServiceImpl.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/controller/DeadLetterController.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/config/IntegrationWebConfig.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/convert/IntegrationConverterTest.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/DeadLetterServiceImplTest.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/controller/DeadLetterControllerTest.java \
        backend/fuyun-app/src/main/java/com/fuyun/app/config/IntegrationConfig.java
git commit -m "feat(integration): 死信管理查询与详情端点及分页契约落地"
```

### Task 3: 死信重放（含上限与留痕）与关闭动作

**Files:**
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/dto/DeadLetterCloseRequest.java`
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/service/IDeadLetterService.java`
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/service/impl/DeadLetterServiceImpl.java`
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/controller/DeadLetterController.java`
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/DeadLetterServiceImplTest.java`（追加用例）
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/controller/DeadLetterControllerTest.java`（追加用例）

**Interfaces:**
- Consumes: Task 2 的 `DeadLetterServiceImpl(IntegrationConverter)`、`IntegrationErrorCode`、`MessagingConstants.DEAD_LETTER_REPLAY_MAX_COUNT/DEAD_LETTER_STATUS_*/HANDLER_MAX_LENGTH`、`TextTruncate.truncate`、`OperatorContextHolder`（`com.fuyun.common.context`，认证拦截器已注入）。
- Produces:
  - `IDeadLetterService.replay(Long id) : DeadLetterDetailVO`（重放投递成功 → REPLAYED + replay_count++ + handler/handled_at；投递失败 → PENDING + replay_count++ 后抛 INT-1005）
  - `IDeadLetterService.close(Long id, DeadLetterCloseRequest request) : DeadLetterDetailVO`
  - `DeadLetterCloseRequest(@NotBlank @Size(max = 500) String handleNote)`
  - `DeadLetterServiceImpl` 构造器变更：`(IntegrationConverter converter, RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin)`
  - HTTP：`POST /api/v1/integration/dead-letters/{id}/replay`、`POST /api/v1/integration/dead-letters/{id}/close`

**语义裁决（写死，执行者勿改口径）：** ①「重放成功」的判定 = 原帧已发出（broker 未抛异常）；消费侧是否真正业务成功由消费幂等台账（`received_event`）与端到端 IT 证明（Task 13）——M20 无跨模块消费回调通道，Spec 状态机 `REPLAYED(重放且消费成功)` 在 M20 侧的可观测口径即「重放帧已投递」。② 上限 3 次为控制器 2026-09-15 拍板口径（Spec 无值），载体 = V4 既有 `replay_count` 列，**不新增迁移**。③ 重放前校验来源队列在位（`AmqpAdmin.getQueueProperties(source_queue)`），防「投递成功但无队列接收」的静默丢失——`mandatory=true` 的退回回调是异步的，无法在同步响应内感知。

- [ ] **Step 1: 关闭请求 DTO**

`dto/DeadLetterCloseRequest.java`：

```java
package com.fuyun.integration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 死信关闭请求入参（POST /api/v1/integration/dead-letters/{id}/close，M20 §7）。
 *
 * <p>关闭必填原因（Spec §5 状态机 PENDING → CLOSED 硬要求「必须填写原因，如脏数据放弃」）；
 * record 透明浅不可变载体（A.1-2）+ 声明式校验（A.3-5）。
 *
 * @param handleNote 关闭原因，非空且不超过 500 字符（dead_letter.handle_note 列宽）；来源：运维人工填写
 */
public record DeadLetterCloseRequest(@NotBlank @Size(max = 500) String handleNote) {}
```

- [ ] **Step 2: 服务契约追加两个动作方法**

在 `IDeadLetterService.java` 内追加：

```java
    /**
     * 重放死信：原帧原文重投 fy.topic（保留原 eventId，靠消费侧幂等防重复），成功置 REPLAYED 并累加
     * 重放次数；投递失败回到 PENDING 并累加次数后抛业务异常（INT-1005）。
     *
     * <p>拒绝条件（不触达投递）：id 不存在（INT-1001）；非 PENDING 状态（INT-1002）；
     * 重放次数已达 {@link com.fuyun.integration.constants.MessagingConstants#DEAD_LETTER_REPLAY_MAX_COUNT}
     * （INT-1003）；无可用路由键或来源队列不在位（INT-1004）。
     *
     * @param id 死信 ID，非空
     * @return 重放后的死信详情，非空；status=REPLAYED、replayCount 已递增、handler/handledAt 已留痕
     * @throws com.fuyun.common.exception.BizException 上述四种拒绝场景与投递失败场景；
     *                                                  建议处理策略：按 errorCode 分支提示运维（状态冲突刷新重试、超限转人工关闭）
     */
    DeadLetterDetailVO replay(Long id);

    /**
     * 关闭死信：置终态 CLOSED 并留痕处理人/备注/时间（PENDING → CLOSED，Spec §5）。
     *
     * @param id      死信 ID，非空
     * @param request 关闭请求，非空；handleNote 必填（关闭原因）
     * @return 关闭后的死信详情，非空；status=CLOSED
     * @throws com.fuyun.common.exception.BizException id 不存在（INT-1001）或非 PENDING 状态（INT-1002，
     *                                                  含并发处置抢先）时触发
     */
    DeadLetterDetailVO close(Long id, DeadLetterCloseRequest request);
```

- [ ] **Step 3: 实现类追加动作方法（CAS + 事务外投递）**

`DeadLetterServiceImpl.java` 追加 import 与成员，并追加下列方法（构造器改为三参）：

```java
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.utils.TextTruncate;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.dto.DeadLetterCloseRequest;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
```

```java
    /** 无操作人上下文（非 HTTP 线程）时的处理人兜底值：与 created_by 系统操作口径一致 */
    private static final String DEFAULT_HANDLER = "system";

    private final RabbitTemplate rabbitTemplate;

    private final AmqpAdmin amqpAdmin;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import）。
     *
     * @param converter      治理域转换器，非空；来源：IntegrationWebConfig @Bean
     * @param rabbitTemplate MQ 发送模板，非空；来源：Boot 自动装配（correlated confirm + mandatory）
     * @param amqpAdmin      AMQP 管理台，非空；来源：Boot 自动装配（来源队列在位校验）
     */
    public DeadLetterServiceImpl(
            IntegrationConverter converter, RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin) {
        this.converter = converter;
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
    }

    @Override
    public DeadLetterDetailVO replay(Long id) {
        DeadLetter row = requirePending(id, "重放");
        // 重推上限（Spec 无值，控制器拍板）：达到上限拒绝，交运维关闭处置
        if (row.getReplayCount() != null && row.getReplayCount() >= MessagingConstants.DEAD_LETTER_REPLAY_MAX_COUNT) {
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_REPLAY_LIMIT_EXCEEDED,
                    HttpStatus.CONFLICT,
                    "死信重推次数已达上限 " + MessagingConstants.DEAD_LETTER_REPLAY_MAX_COUNT + "：id=" + id);
        }
        String routingKey = resolveRoutingKey(row);
        // 不可路由防线：来源队列不在位时拒绝（mandatory 退回回调为异步，同步响应内无法感知不可路由）
        if (amqpAdmin.getQueueProperties(row.getSourceQueue()) == null) {
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_NOT_REPLAYABLE,
                    HttpStatus.CONFLICT,
                    "来源队列不在位，禁止重放（防不可路由静默丢失）：source_queue=" + row.getSourceQueue());
        }
        // 事务外投递（A.4.2-7 事务内禁消息发送）：投递先行，状态写回随后（单语句 CAS，无需方法级事务）
        if (!publishOriginalFrame(row, routingKey)) {
            // 投递失败：回到待处理并累加重放次数（Spec §5「重放失败回到待处理并累加重放次数」）
            markReplayAttempt(id, MessagingConstants.DEAD_LETTER_STATUS_PENDING);
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_REPLAY_DELIVERY_FAILED,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "死信重放投递失败，已回到待处理并累加重放次数：id=" + id);
        }
        if (!markReplayAttempt(id, MessagingConstants.DEAD_LETTER_STATUS_REPLAYED)) {
            // CAS 影响 0 行：并发处置已抢先（本次投递已发出，消费侧幂等防重复业务）
            log.warn("死信重放状态写回未命中（并发处置抢先，本次投递已发出）：id={}", id);
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_STATUS_NOT_ACTIONABLE,
                    HttpStatus.CONFLICT,
                    "死信状态已变化（并发处置），请刷新后重试：id=" + id);
        }
        log.info(
                "死信重放完成：id={}，event_id={}，routing_key={}，replay_count={}",
                id,
                row.getEventId(),
                routingKey,
                row.getReplayCount() == null ? 1 : row.getReplayCount() + 1);
        return detail(id);
    }

    @Override
    public DeadLetterDetailVO close(Long id, DeadLetterCloseRequest request) {
        requirePending(id, "关闭");
        String handler = resolveHandler();
        // 单语句 CAS（状态守卫）：并发处置后到者影响 0 行，天然幂等于先行者结果
        boolean closed = this.lambdaUpdate()
                .eq(DeadLetter::getId, id)
                .eq(DeadLetter::getStatus, MessagingConstants.DEAD_LETTER_STATUS_PENDING)
                .set(DeadLetter::getStatus, MessagingConstants.DEAD_LETTER_STATUS_CLOSED)
                .set(DeadLetter::getHandleNote, request.handleNote())
                .set(DeadLetter::getHandler, handler)
                .set(DeadLetter::getHandledAt, OffsetDateTime.now())
                .update();
        if (!closed) {
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_STATUS_NOT_ACTIONABLE,
                    HttpStatus.CONFLICT,
                    "死信状态已变化（并发处置），请刷新后重试：id=" + id);
        }
        // 关闭原因属运维备注（可能含院内业务描述）：日志只记长度不记原文（敏感信息禁入日志）
        log.info("死信关闭完成：id={}，handler={}，原因长度={}", id, handler, request.handleNote().length());
        return detail(id);
    }

    /**
     * 载入死信并校验状态可处置：仅 PENDING（Spec §5 状态机——REPLAYED 已处置、CLOSED 为终态）。
     *
     * @param id     死信 ID，非空
     * @param action 动作中文名（进异常文案）
     * @return 死信行，非空
     * @throws BizException id 不存在（INT-1001）或状态非 PENDING（INT-1002）时触发
     */
    private DeadLetter requirePending(Long id, String action) {
        DeadLetter row = this.getById(id);
        if (row == null) {
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_NOT_FOUND, HttpStatus.NOT_FOUND, "死信不存在：id=" + id);
        }
        if (!MessagingConstants.DEAD_LETTER_STATUS_PENDING.equals(row.getStatus())) {
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_STATUS_NOT_ACTIONABLE,
                    HttpStatus.CONFLICT,
                    "死信当前状态不允许" + action + "（仅 PENDING 可处置）：id=" + id + "，status=" + row.getStatus());
        }
        return row;
    }

    /**
     * 解析重放路由键：优先原始路由键（死信转发保留原路由键），轨迹缺失时回退事件类型；
     * 两者皆空（信封不合规帧）拒绝重放。
     *
     * @param row 死信行，非空
     * @return 重放路由键，非空
     * @throws BizException 无可用路由键（INT-1004）时触发
     */
    private String resolveRoutingKey(DeadLetter row) {
        String routingKey = row.getRoutingKey() == null || row.getRoutingKey().isBlank()
                ? row.getEventType()
                : row.getRoutingKey();
        if (routingKey == null || routingKey.isBlank()) {
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_NOT_REPLAYABLE,
                    HttpStatus.CONFLICT,
                    "死信无可用路由键（信封不合规帧），禁止重放：id=" + row.getId());
        }
        return routingKey;
    }

    /**
     * 重放投递：以 payload_body 原文重建消息投 fy.topic，不走消息转换器（原文即 CF-1 信封线格式，
     * 经 Jackson 转换器会被二次序列化为 JSON 字符串而破坏线格式）。
     *
     * @param row        死信行，非空；取 payloadBody 原文
     * @param routingKey 重放路由键，非空
     * @return true=已发出；false=投递异常（已记 error 日志，由调用方落 PENDING 语义）
     */
    private boolean publishOriginalFrame(DeadLetter row, String routingKey) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(row.getPayloadBody().getBytes(StandardCharsets.UTF_8), properties);
        try {
            rabbitTemplate.send(MessagingConstants.EXCHANGE_TOPIC, routingKey, message);
            return true;
        } catch (RuntimeException e) {
            log.error("死信重放投递失败：id={}，routing_key={}，原因={}", row.getId(), routingKey, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 单语句 CAS 写回重放尝试（多实例安全，禁应用层读-改-写）：状态与上限双守卫，影响 0 行即并发抢先。
     *
     * <p>留痕口径：成功置 REPLAYED 时写 handler/handled_at（处置留痕）；失败回 PENDING 时只累加
     * replay_count（Spec §5 只要求这两项效果，不污染处理人语义）。
     *
     * @param id     死信 ID，非空
     * @param status 目标状态（REPLAYED 成功 / PENDING 投递失败）
     * @return true=本次 CAS 生效；false=并发处置已抢先或已达上限
     */
    private boolean markReplayAttempt(Long id, String status) {
        boolean replayed = MessagingConstants.DEAD_LETTER_STATUS_REPLAYED.equals(status);
        return this.lambdaUpdate()
                .eq(DeadLetter::getId, id)
                .eq(DeadLetter::getStatus, MessagingConstants.DEAD_LETTER_STATUS_PENDING)
                .lt(DeadLetter::getReplayCount, MessagingConstants.DEAD_LETTER_REPLAY_MAX_COUNT)
                .set(DeadLetter::getStatus, status)
                .setSql("replay_count = replay_count + 1")
                .set(replayed, DeadLetter::getHandler, resolveHandler())
                .set(replayed, DeadLetter::getHandledAt, OffsetDateTime.now())
                .update();
    }

    /**
     * 解析处理人标识：取认证拦截器注入的操作人上下文，非 HTTP 线程回退 system；
     * 按 handler 列宽截断（W-6① 同款列宽防线，防超长标识致整行写入失败）。
     *
     * @return 处理人标识，非空，长度不超过 HANDLER_MAX_LENGTH
     */
    private static String resolveHandler() {
        String operator = OperatorContextHolder.get();
        String handler = operator == null || operator.isBlank() ? DEFAULT_HANDLER : operator;
        return TextTruncate.truncate(handler, MessagingConstants.HANDLER_MAX_LENGTH);
    }
```

- [ ] **Step 4: 服务单测追加六个用例（先失败后通过）**

在 `DeadLetterServiceImplTest` 中追加 `@Mock RabbitTemplate rabbitTemplate`、`@Mock AmqpAdmin amqpAdmin`（`org.springframework.amqp.core.AmqpAdmin` / `org.springframework.amqp.rabbit.core.RabbitTemplate`），`setUp` 构造改为 `new DeadLetterServiceImpl(IntegrationConverter.INSTANCE, rabbitTemplate, amqpAdmin)`，并追加：

```java
    @Test
    @DisplayName("重放成功：原帧原文原路由键投 fy.topic，状态置 REPLAYED 且计数与处理人留痕")
    void replayPublishesOriginalFrameAndMarksReplayed() {
        DeadLetter row = pendingRow(9L, 0);
        when(deadLetterMapper.selectById(9L)).thenReturn(row);
        when(amqpAdmin.getQueueProperties("q.it.system.dict.published")).thenReturn(new Properties());
        when(deadLetterMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        DeadLetter replayed = pendingRow(9L, 1);
        replayed.setStatus(MessagingConstants.DEAD_LETTER_STATUS_REPLAYED);
        when(deadLetterMapper.selectById(9L)).thenReturn(row, replayed);

        DeadLetterDetailVO detail = service.replay(9L);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(MessagingConstants.EXCHANGE_TOPIC), eq("system.dict.published"), messageCaptor.capture());
        // 原文即信封线格式：不经常规转换器（否则会被二次序列化为 JSON 字符串）
        assertThat(new String(messageCaptor.getValue().getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"eventId\":\"x\"}");
        assertThat(detail.status()).isEqualTo(MessagingConstants.DEAD_LETTER_STATUS_REPLAYED);
        assertThat(detail.replayCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("重放上限：replay_count 达 3 时拒绝（INT-1003），不触达投递")
    void replayRejectsWhenLimitReached() {
        when(deadLetterMapper.selectById(9L)).thenReturn(pendingRow(9L, 3));

        assertThatThrownBy(() -> service.replay(9L))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_REPLAY_LIMIT_EXCEEDED);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("来源队列不在位：拒绝重放（INT-1004），不触达投递")
    void replayRejectsWhenSourceQueueMissing() {
        when(deadLetterMapper.selectById(9L)).thenReturn(pendingRow(9L, 0));
        when(amqpAdmin.getQueueProperties("q.it.system.dict.published")).thenReturn(null);

        assertThatThrownBy(() -> service.replay(9L))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_NOT_REPLAYABLE);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("投递失败：状态写回待处理并累加计数后抛 INT-1005（Spec「重放失败回到待处理」）")
    void replayFallsBackToPendingWhenDeliveryFails() {
        when(deadLetterMapper.selectById(9L)).thenReturn(pendingRow(9L, 0));
        when(amqpAdmin.getQueueProperties("q.it.system.dict.published")).thenReturn(new Properties());
        when(rabbitTemplate.send(anyString(), anyString(), any(Message.class)))
                .thenThrow(new AmqpConnectException(new RuntimeException("broker 不可达")));
        when(deadLetterMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertThatThrownBy(() -> service.replay(9L))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_REPLAY_DELIVERY_FAILED);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                });
        // 失败路径仍写状态（PENDING 回置 + 计数累加）
        verify(deadLetterMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("关闭：必填原因写入备注列，状态置 CLOSED 且处理人/时间留痕")
    void closeWritesReasonAndTerminalStatus() {
        when(deadLetterMapper.selectById(9L)).thenReturn(pendingRow(9L, 0));
        when(deadLetterMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        DeadLetter closed = pendingRow(9L, 0);
        closed.setStatus(MessagingConstants.DEAD_LETTER_STATUS_CLOSED);
        closed.setHandleNote("脏数据放弃");
        when(deadLetterMapper.selectById(9L)).thenReturn(pendingRow(9L, 0), closed);

        DeadLetterDetailVO detail = service.close(9L, new DeadLetterCloseRequest("脏数据放弃"));

        assertThat(detail.status()).isEqualTo(MessagingConstants.DEAD_LETTER_STATUS_CLOSED);
        assertThat(detail.handleNote()).isEqualTo("脏数据放弃");
        verify(deadLetterMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("终态守卫：已关闭死信拒绝重放（INT-1002，CLOSED 为终态不得再重放）")
    void replayRejectsClosedDeadLetter() {
        DeadLetter closed = pendingRow(9L, 1);
        closed.setStatus(MessagingConstants.DEAD_LETTER_STATUS_CLOSED);
        when(deadLetterMapper.selectById(9L)).thenReturn(closed);

        assertThatThrownBy(() -> service.replay(9L))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_STATUS_NOT_ACTIONABLE);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("重放/关闭不存在 id：抛 404 业务异常（INT-1001，requirePending 缺行分支）")
    void replayAndCloseRejectMissingId() {
        when(deadLetterMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.replay(404L))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_NOT_FOUND);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        assertThatThrownBy(() -> service.close(404L, new DeadLetterCloseRequest("脏数据放弃")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_NOT_FOUND);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("信封不合规帧无可用路由键：拒绝重放（INT-1004，resolveRoutingKey 空值分支）")
    void replayRejectsWhenNoRoutingKeyAvailable() {
        DeadLetter noRoutingKey = pendingRow(9L, 0);
        noRoutingKey.setRoutingKey(null);
        noRoutingKey.setEventType(null);
        when(deadLetterMapper.selectById(9L)).thenReturn(noRoutingKey);

        assertThatThrownBy(() -> service.replay(9L))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_NOT_REPLAYABLE);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("重放 CAS 影响 0 行：并发处置抢先时抛状态冲突（INT-1002），本次投递已发出")
    void replayReportsConflictWhenCasMisses() {
        when(deadLetterMapper.selectById(9L)).thenReturn(pendingRow(9L, 0));
        when(amqpAdmin.getQueueProperties("q.it.system.dict.published")).thenReturn(new Properties());
        // 投递成功但状态写回未命中：同帧已被并发处置者抢先
        when(deadLetterMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.replay(9L))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_STATUS_NOT_ACTIONABLE);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(rabbitTemplate).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("关闭 CAS 影响 0 行：并发处置抢先时抛状态冲突（INT-1002）")
    void closeReportsConflictWhenCasMisses() {
        when(deadLetterMapper.selectById(9L)).thenReturn(pendingRow(9L, 0));
        when(deadLetterMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.close(9L, new DeadLetterCloseRequest("脏数据放弃")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_STATUS_NOT_ACTIONABLE);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }
```

（import 追加：`org.springframework.amqp.AmqpConnectException`、`org.springframework.amqp.core.AmqpAdmin`、`org.springframework.amqp.core.Message`、`org.springframework.amqp.rabbit.core.RabbitTemplate`、`java.nio.charset.StandardCharsets`、`java.util.Properties`、`static org.mockito.ArgumentMatchers.anyString`、`static org.mockito.ArgumentMatchers.eq`、`static org.mockito.ArgumentMatchers.isNull`、`static org.mockito.Mockito.never`、`com.fuyun.integration.dto.DeadLetterCloseRequest`、`com.fuyun.integration.constants.MessagingConstants`、`org.mockito.ArgumentCaptor`。注意 `service.close(...)` 与 `detail(...)` 的两次 `selectById` 打桩用 `thenReturn(第一次, 第二次)` 顺序返回，`replay` 用例同理。）

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest=DeadLetterServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false | tail -8`
Expected: 首跑编译失败（`replay`/`close` 未实现）→ 落 Step 3 后复跑 `Tests run: 12, Failures: 0`（Task 2 的 2 用例 + 本步 6 用例 + 覆盖率补强 4 用例）

- [ ] **Step 5: 端点追加两个动作端点与薄层用例**

`DeadLetterController` 追加（import 追加 `DeadLetterCloseRequest`、`jakarta.validation.Valid`、`org.springframework.web.bind.annotation.PostMapping`、`org.springframework.web.bind.annotation.RequestBody`）：

```java
    /**
     * 重放死信（POST /dead-letters/{id}/replay，M20 §7）：原 eventId 重新入队，消费侧幂等防重复业务。
     *
     * @param id 死信 ID（路径参数），非空
     * @return 重放后详情（status=REPLAYED）；拒绝场景由全局渲染器输出 4xx/5xx ProblemDetail
     */
    @PostMapping("/{id}/replay")
    public DeadLetterDetailVO replay(@PathVariable("id") Long id) {
        return deadLetterService.replay(id);
    }

    /**
     * 关闭死信（POST /dead-letters/{id}/close，M20 §7）：必填原因，置终态 CLOSED。
     *
     * @param id      死信 ID（路径参数），非空
     * @param request 关闭请求，非空；handleNote 必填（JSR-303 校验失败由全局渲染器输出 400）
     * @return 关闭后详情（status=CLOSED）
     */
    @PostMapping("/{id}/close")
    public DeadLetterDetailVO close(@PathVariable("id") Long id, @Valid @RequestBody DeadLetterCloseRequest request) {
        return deadLetterService.close(id, request);
    }
```

`DeadLetterControllerTest` 追加：

```java
    @Test
    @DisplayName("重放端点：路径 id 原样透传服务层，出参直返")
    void replayDelegatesPathId() {
        DeadLetterDetailVO expected = replayDetail();
        when(deadLetterService.replay(9L)).thenReturn(expected);

        assertThat(controller.replay(9L)).isSameAs(expected);
        verify(deadLetterService).replay(9L);
    }

    @Test
    @DisplayName("关闭端点：路径 id 与请求对象原样透传服务层")
    void closeDelegatesRequestAsIs() {
        DeadLetterCloseRequest request = new DeadLetterCloseRequest("脏数据放弃");
        DeadLetterDetailVO expected = replayDetail();
        when(deadLetterService.close(9L, request)).thenReturn(expected);

        assertThat(controller.close(9L, request)).isSameAs(expected);
        verify(deadLetterService).close(9L, request);
    }

    /** 构造重放/关闭后的详情出参样本。 */
    private DeadLetterDetailVO replayDetail() {
        return new DeadLetterDetailVO(
                9L, "q.it.system.dict.published", "system.dict.published", "system.dict.published",
                "b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60", "{}", "d", "原因", null, "REPLAYED", 1, "1", null, null);
    }
```

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest='DeadLetterControllerTest,DeadLetterServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false | tail -6`
Expected: `Tests run: 16, Failures: 0`（死信端点 4 + 死信服务 12）

- [ ] **Step 6: Commit**

```bash
git add backend/fuyun-integration/src/main/java/com/fuyun/integration/dto/DeadLetterCloseRequest.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/service/IDeadLetterService.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/service/impl/DeadLetterServiceImpl.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/controller/DeadLetterController.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/DeadLetterServiceImplTest.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/controller/DeadLetterControllerTest.java
git commit -m "feat(integration): 死信重放与关闭动作及重推上限处置"
```

---

### Task 4: W-6① 死信留痕列宽钳长

**Files:**
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/internal/DeadLetterListener.java`
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/internal/DeadLetterListenerTest.java`（追加用例）

**Interfaces:**
- Consumes: Task 2 的 `MessagingConstants` 列宽常量（`FAIL_REASON_MAX_LENGTH` / `DEAD_LETTER_SOURCE_QUEUE_MAX_LENGTH` / `DEAD_LETTER_ROUTING_KEY_MAX_LENGTH` / `DEAD_LETTER_EVENT_ID_MAX_LENGTH` / `DEAD_LETTER_EVENT_TYPE_MAX_LENGTH`）与 `TextTruncate.truncate`。
- Produces: `DeadLetterListener.onDeadLetter(Message)` 落库前对五列做列宽截断（对外签名不变）。

**背景（W-6① 原文）：** 「DeadLetterListener 死信留痕 failReason/source_queue/routing_key 未按列宽钳长——畸形帧异常消息超 VARCHAR(1000) 致留痕落库失败违背『不合规信封拒收留痕』红线（jackson 实证复现）」。失效模式同样覆盖 `event_id`(64)/`event_type`(128) 两列（截断收敛一并收口，避免同类缺陷复发）。

- [ ] **Step 1: 追加失败单测（超长帧落库成功且各列不超列宽）**

在 `DeadLetterListenerTest` 追加：

```java
    @Test
    @DisplayName("超长帧钳长：x-death 队列名/路由键与超长异常消息截断至列宽，留痕仍落库成功（W-6①）")
    void truncatesOverlongFieldsToColumnWidth() {
        String overlongQueue = "q." + "x".repeat(300);
        String overlongRoutingKey = "system." + "y".repeat(300);
        String overlongReason = "事件信封不合规：" + "z".repeat(2000);
        Map<String, Object> xDeath = Map.of(
                "queue", overlongQueue, "reason", "rejected", "routing-keys", List.of(overlongRoutingKey));
        when(eventEnvelopeCodec.fromJson(BODY)).thenThrow(new IllegalArgumentException(overlongReason));

        listener.onDeadLetter(deadLetterMessage(BODY, xDeath));

        verify(deadLetterMapper).insert(deadLetterCaptor.capture());
        DeadLetter saved = deadLetterCaptor.getValue();
        assertThat(saved.getSourceQueue()).hasSize(MessagingConstants.DEAD_LETTER_SOURCE_QUEUE_MAX_LENGTH);
        assertThat(saved.getRoutingKey()).hasSize(MessagingConstants.DEAD_LETTER_ROUTING_KEY_MAX_LENGTH);
        assertThat(saved.getFailReason()).hasSize(MessagingConstants.FAIL_REASON_MAX_LENGTH);
        // 截断保留头部：不合规标注在前部，仍可识别违规类型（M20 红线 1 留痕语义不被截断削弱）
        assertThat(saved.getFailReason()).startsWith("事件信封不合规");
    }
```

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest=DeadLetterListenerTest -Dsurefire.failIfNoSpecifiedTests=false | tail -8`
Expected: FAIL（`expected size: 128 but was: 302` 类断言失败）

- [ ] **Step 2: 实现钳长**

`DeadLetterListener.java` 的组装段（`deadLetter.setSourceQueue(...)` 至 `setFailReason(...)`）改为：

```java
        DeadLetter deadLetter = new DeadLetter();
        // 列宽防线（W-6①）：畸形帧的来源队列/路由键与超长异常消息一律截断后落库，
        // 否则整行写入失败 → 留痕静默丢失，违背「不合规信封拒收留痕」红线
        deadLetter.setSourceQueue(
                TextTruncate.truncate(death.sourceQueue(), MessagingConstants.DEAD_LETTER_SOURCE_QUEUE_MAX_LENGTH));
        deadLetter.setRoutingKey(
                TextTruncate.truncate(death.routingKey(), MessagingConstants.DEAD_LETTER_ROUTING_KEY_MAX_LENGTH));
        deadLetter.setEventId(TextTruncate.truncate(eventId, MessagingConstants.DEAD_LETTER_EVENT_ID_MAX_LENGTH));
        deadLetter.setEventType(
                TextTruncate.truncate(eventType, MessagingConstants.DEAD_LETTER_EVENT_TYPE_MAX_LENGTH));
        deadLetter.setPayloadBody(body);
        deadLetter.setPayloadDigest(digest);
        deadLetter.setFailReason(TextTruncate.truncate(failReason, MessagingConstants.FAIL_REASON_MAX_LENGTH));
```

并在文件 import 段追加：

```java
import com.fuyun.common.utils.TextTruncate;
```

类 javadoc 的流程段追加一句（保持文档与实现一致）：

```
 * <p>W-6① 列宽防线：source_queue/routing_key/event_id/event_type/fail_reason 五列落库前按 V4 列宽
 * 截断（TextTruncate，常量集中 constants/）——畸形帧超长字段不得使留痕落库失败。
```

- [ ] **Step 3: 运行确认全部通过**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest=DeadLetterListenerTest -Dsurefire.failIfNoSpecifiedTests=false | tail -6`
Expected: `Tests run: 5, Failures: 0`

- [ ] **Step 4: Commit**

```bash
git add backend/fuyun-integration/src/main/java/com/fuyun/integration/internal/DeadLetterListener.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/internal/DeadLetterListenerTest.java
git commit -m "fix(integration): 死信留痕五列按列宽截断防留痕落库失败（W-6①）"
```

---

### Task 5: 幂等构件失败链（FAILED 登记 + 状态口径 + release 异常遮蔽，W-6③）

**Files:**
- Modify: `backend/fuyun-common/src/main/java/com/fuyun/common/messaging/MessageIdempotencyService.java`
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/service/impl/MessageIdempotencyServiceImpl.java`
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/internal/DictPublishedListener.java`
- Modify: `backend/fuyun-iot/src/main/java/com/fuyun/iot/internal/IotFanoutListener.java`
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/MessageIdempotencyServiceImplTest.java`
- Test: `backend/fuyun-system/src/test/java/com/fuyun/system/internal/DictPublishedListenerTest.java`
- Test: `backend/fuyun-iot/src/test/java/com/fuyun/iot/internal/IotFanoutListenerTest.java`
- Test: `backend/fuyun-app/src/test/java/com/fuyun/app/MessagingGovernanceIT.java`

**Interfaces:**
- Produces（common 契约收敛为三方法）：
  - `boolean tryAcquire(String eventId, String consumerModule)`（回查增加 `status=PROCESSED` 过滤）
  - `void recordProcessed(ReceivedEventRecord record)`（唯一索引冲突时：FAILED 行升级为 PROCESSED；已是 PROCESSED 行幂等跳过）
  - `void settleFailure(ReceivedEventRecord record, RuntimeException businessFailure)`（失败收尾：释放前置键 + FAILED 登记，两者异常一律 `addSuppressed` 挂回 `businessFailure`，本方法不抛出）
  - **移除** `void release(String eventId, String consumerModule)`（原语下沉实现内部，标准范式不再暴露）
- Consumes: `MessagingConstants.RECEIVED_STATUS_FAILED` / `FAIL_REASON_MAX_LENGTH`（Task 2）、`TextTruncate`、`TextTruncate` 同源截断。

**语义裁决（Spec §3.2 步骤②④⑤ 落地口径）：**
- **② 登记**：`(event_id, consumer_module)` 唯一索引行承载状态机——同一事件同一消费者一行，状态在行内迁移（Spec「成功则幂等表置已处理」的「置」字即行内状态迁移）。
- **④ 成功置已处理**：`recordProcessed` 插入冲突时按 `status=FAILED` 条件升级为 PROCESSED（清 `fail_reason`、写 `processed_at`）；若既有行已是 PROCESSED（并发重复投递）→ 保持 `processed_at` 首次成功时刻不变，幂等跳过。
- **⑤ 失败记录原因**：`settleFailure` 首登记写 FAILED 行（`retry_count=1`）；容器有界重试再次失败时命中唯一索引，改为原子累加 `retry_count = retry_count + 1` 并刷新 `fail_reason`（单语句，多实例安全）。`retry_count` 口径 = **应用侧消费失败登记次数**（容器侧重试次数不落库，PR-1b 前恒为 0）。
- **D-7 前置（类注释已明示的强制项）**：`tryAcquire` 回查 NX 失败分支必须带 `status=PROCESSED` 过滤，否则 FAILED 行会被误判为已处理而丢重投。
- **W-6③**：`release` 的 Redis 异常保持「必须暴露」语义（不吞），改为以 `suppressed` 挂回原始业务异常（双保留），原始业务异常始终是主异常。

- [ ] **Step 1: 契约文件改写（先写接口 javadoc 范式文本）**

`MessageIdempotencyService.java` 全文替换为：

```java
package com.fuyun.common.messaging;

/**
 * 消息消费幂等服务（M20 消费幂等治理构件契约）：接口沉 common、实现由 fuyun-integration
 * 运行时装配（M20 Spec M-3 裁决），各业务模块仅依赖本接口即获得消费幂等能力，零编译期
 * 依赖 fuyun-integration。
 *
 * <p>两层去重缺一不可（backend 宪法 A.5-6）：Redis SET NX PX 前置去重（加速层，故障降级
 * 放行）+ received_event 表 (event_id, consumer_module) 唯一索引最终兜底（正确性保证层）。
 *
 * <p>D-7 裁决（消除 TTL 窗口误判丢消息）：NX 抢占失败不必然是重复投递——上次处理可能中断于
 * 业务执行前，前置键残留而台账无行。因此 NX 失败时必须回查 received_event 台账：已有
 * <b>status=PROCESSED</b> 行才判定重复（返回 false 跳过）；无 PROCESSED 行（含仅 FAILED 行）
 * 则视为前置键残留/上次失败，放行重新处理（返回 true），保持 at-least-once。
 *
 * <p>失败留痕（Spec §3.2 步骤⑤）：消费失败经 {@link #settleFailure} 登记 FAILED 行（首次）
 * 或原子累加 retry_count（重试再失败）；后续重试成功时 {@link #recordProcessed} 把同一行
 * 升级为 PROCESSED（Spec 步骤④「成功则幂等表置已处理」）。
 *
 * <p>标准消费范式（消费方一律按此编写；PR-3 出现第二个真实消费者时再提炼模板基类，本契约
 * 不做抽象）：
 * <pre>{@code
 * ReceivedEventRecord record = new ReceivedEventRecord(
 *         envelope.eventId(), envelope.eventType(), envelope.producer(), envelope.occurredAt(), module);
 * if (!idempotency.tryAcquire(record.eventId(), module)) { return; }   // 重复投递：跳过即 AUTO 确认
 * try {
 *     doBusiness();                                                   // 业务执行
 *     idempotency.recordProcessed(record);                            // 成功登记（唯一索引兜底并发）
 * } catch (RuntimeException e) {
 *     idempotency.settleFailure(record, e);                           // 释放前置键 + FAILED 留痕（不遮蔽 e）
 *     throw e;                                                        // 上抛交容器有界重试，耗尽进 fy.dlx
 * }
 * }</pre>
 */
public interface MessageIdempotencyService {

    /**
     * 前置抢占幂等键：Redis {@code SET NX PX} 原子占位（键
     * {@code fy:integration:idempotency:<consumerModule>:<eventId>}，TTL 取
     * fuyun.messaging.idempotency-redis-ttl 配置）。
     *
     * <p>Redis 故障时降级放行（warn 日志 + 返回 true，不抛出）——Redis 故障不得放大为消费
     * 不可用，此时由唯一索引兜底最终幂等。
     *
     * <p>D-7 回查语义（NX 失败分支）：回查 received_event 台账且仅认 status=PROCESSED 行——
     * 有 PROCESSED 行 → 返回 false（确认已处理）；否则（无行 / 仅 FAILED 行）→ warn 后返回
     * true 放行重新处理。
     *
     * @param eventId        事件信封 eventId（UUID 字符串），非空；来源：消费消息解析出的信封
     * @param consumerModule 消费者模块域标识（如 it），非空；幂等键第二要素（同事件可被多模块消费）
     * @return true=可执行业务；false=确认重复投递，消费方直接返回跳过（即 AUTO 确认）
     */
    boolean tryAcquire(String eventId, String consumerModule);

    /**
     * 成功登记：插入 received_event 台账（status=PROCESSED、processed_at=now()）。
     *
     * <p>唯一索引冲突（{@code DuplicateKeyException}）分两种：①既有行 status=FAILED（前次失败后
     * 重试成功）→ 行内升级为 PROCESSED（清 fail_reason、写 processed_at）；②既有行已 PROCESSED
     * （并发重复投递）→ warn 幂等跳过，不刷新 processed_at（保留首次成功时刻）。
     * 其他 DB 异常原样上抛（真故障必须暴露，交容器有界重试，耗尽进 fy.dlx）。
     *
     * @param record 信封五要素登记记录，非空；来源：消费消息解析出的信封字段
     */
    void recordProcessed(ReceivedEventRecord record);

    /**
     * 失败收尾（标准消费范式 catch 分支的唯一调用点）：释放前置键 + 登记消费失败留痕（FAILED）。
     *
     * <p>双保留语义（W-6③）：释放前置键的 Redis 异常与失败留痕的 DB 异常都不再上抛，也不吞没，
     * 一律 {@code businessFailure.addSuppressed(...)} 挂回——原始业务异常保持主异常地位，交容器
     * 有界重试耗尽进 fy.dlx；异常链完整保留便于排障（原设计「释放失败必须暴露」的诉求由
     * suppressed + warn 日志共同满足）。
     *
     * <p>本方法自身不抛出（除 {@code record == null} 之类的编程错误由 JVM 抛出）。
     *
     * @param record          信封五要素记录，非空；FAILED 行的 event_id/consumer_module 取自本对象
     * @param businessFailure 原始业务异常，非空；作为主异常保留被挂 suppressed 与上抛
     */
    void settleFailure(ReceivedEventRecord record, RuntimeException businessFailure);
}
```

- [ ] **Step 2: 实现类改写**

`MessageIdempotencyServiceImpl.java` 关键改动（保留既有字段与构造器；`tryAcquire` 回查加过滤；`recordProcessed` 增升级分支；删除 `release`；新增 `settleFailure` 与私有 `registerFailed`/`releaseKey`/`truncateFailReason`）：

```java
    @Override
    public void recordProcessed(ReceivedEventRecord record) {
        ReceivedEvent entity = new ReceivedEvent();
        fillEnvelope(entity, record);
        entity.setStatus(MessagingConstants.RECEIVED_STATUS_PROCESSED);
        entity.setProcessedAt(OffsetDateTime.now());
        try {
            receivedEventMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 唯一索引冲突：前次失败行 → 行内升级为已处理（Spec §3.2 步骤④「成功则置已处理」）；
            // 已是 PROCESSED 行 → 影响 0 行，幂等跳过（不刷新 processed_at，保留首次成功时刻）
            int upgraded = receivedEventMapper.update(null, Wrappers.lambdaUpdate(ReceivedEvent.class)
                    .eq(ReceivedEvent::getEventId, entity.getEventId())
                    .eq(ReceivedEvent::getConsumerModule, entity.getConsumerModule())
                    .eq(ReceivedEvent::getStatus, MessagingConstants.RECEIVED_STATUS_FAILED)
                    .set(ReceivedEvent::getStatus, MessagingConstants.RECEIVED_STATUS_PROCESSED)
                    .set(ReceivedEvent::getProcessedAt, entity.getProcessedAt())
                    .setSql("fail_reason = NULL"));
            if (upgraded > 0) {
                log.info("前次失败重试成功，消费台账行内升级为已处理：consumer_module={}，event_id={}",
                        record.consumerModule(), record.eventId());
            } else {
                log.warn("重复投递命中 received_event 唯一索引，视为已处理跳过：consumer_module={}，event_id={}",
                        record.consumerModule(), record.eventId());
            }
            return;
        }
        log.info("消费幂等登记完成：consumer_module={}，event_id={}，event_type={}",
                record.consumerModule(), record.eventId(), record.eventType());
    }

    @Override
    public void settleFailure(ReceivedEventRecord record, RuntimeException businessFailure) {
        // ① 释放前置键：Redis 异常不吞（原「释放失败必须暴露」语义不变），以 suppressed 挂回业务异常
        try {
            releaseKey(record.eventId(), record.consumerModule());
        } catch (RuntimeException releaseFailure) {
            businessFailure.addSuppressed(releaseFailure);
            log.warn(
                    "幂等前置键释放失败（已挂 suppressed，不遮蔽业务异常；D-7 回查仍可放行重投）：consumer_module={}，event_id={}，原因={}",
                    record.consumerModule(),
                    record.eventId(),
                    releaseFailure.getMessage());
        }
        // ② FAILED 消费失败登记（Spec §3.2 步骤⑤）：登记失败同样只挂 suppressed，不禁用重试链路
        try {
            registerFailed(record, businessFailure.getMessage());
        } catch (RuntimeException recordFailure) {
            businessFailure.addSuppressed(recordFailure);
            log.error(
                    "消费失败留痕写入失败（已挂 suppressed，不遮蔽业务异常）：consumer_module={}，event_id={}",
                    record.consumerModule(),
                    record.eventId(),
                    recordFailure);
        }
    }

    /**
     * 失败登记：首次失败插入 FAILED 行（retry_count=1）；重试再失败命中唯一索引时原子累加
     * retry_count 并刷新失败原因（单语句原子，多实例安全）。
     *
     * @param record     信封五要素记录，非空
     * @param failReason 失败原因，可空；按列宽截断后落库
     */
    private void registerFailed(ReceivedEventRecord record, String failReason) {
        String truncatedReason = TextTruncate.truncate(failReason, MessagingConstants.FAIL_REASON_MAX_LENGTH);
        ReceivedEvent entity = new ReceivedEvent();
        fillEnvelope(entity, record);
        entity.setStatus(MessagingConstants.RECEIVED_STATUS_FAILED);
        entity.setFailReason(truncatedReason);
        entity.setRetryCount(1);
        try {
            receivedEventMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 同一帧重试再次失败：行内原子累加计数（读-改-写会丢更新，禁用）
            receivedEventMapper.update(null, Wrappers.lambdaUpdate(ReceivedEvent.class)
                    .eq(ReceivedEvent::getEventId, entity.getEventId())
                    .eq(ReceivedEvent::getConsumerModule, entity.getConsumerModule())
                    .set(ReceivedEvent::getStatus, MessagingConstants.RECEIVED_STATUS_FAILED)
                    .set(ReceivedEvent::getFailReason, truncatedReason)
                    .setSql("retry_count = retry_count + 1"));
            log.info(
                    "消费失败留痕累加：consumer_module={}，event_id={}，原因={}",
                    record.consumerModule(),
                    record.eventId(),
                    truncatedReason);
            return;
        }
        log.info("消费失败留痕登记完成：consumer_module={}，event_id={}，原因={}",
                record.consumerModule(), record.eventId(), truncatedReason);
    }

    /**
     * 释放前置键原语：Redis 异常原样上抛（暴露语义保留），由 settleFailure 挂 suppressed 收口。
     *
     * @param eventId        事件信封 eventId，非空
     * @param consumerModule 消费者模块域标识，非空
     */
    private void releaseKey(String eventId, String consumerModule) {
        stringRedisTemplate.delete(buildKey(eventId, consumerModule));
    }

    /**
     * 信封五要素 → 实体公共填充（成功登记与失败登记共用，防两处字段映射漂移）。
     *
     * @param entity 目标实体，非空
     * @param record 信封五要素记录，非空
     */
    private static void fillEnvelope(ReceivedEvent entity, ReceivedEventRecord record) {
        entity.setEventId(UUID.fromString(record.eventId()));
        entity.setEventType(record.eventType());
        entity.setProducer(record.producer());
        entity.setOccurredAt(OffsetDateTime.ofInstant(record.occurredAt(), ZoneOffset.UTC));
        entity.setConsumerModule(record.consumerModule());
    }
```

`tryAcquire` 的回查段改为（其余不变）：

```java
        // D-7 回查（NX 失败分支）：只认已处理行——P1 起 received_event 会出现 FAILED 行，
        // 必须带 status=PROCESSED 过滤，否则失败行会被误判为已处理而跳过重投（丢消息）
        boolean processed = receivedEventMapper.exists(Wrappers.lambdaQuery(ReceivedEvent.class)
                .eq(ReceivedEvent::getEventId, UUID.fromString(eventId))
                .eq(ReceivedEvent::getConsumerModule, consumerModule)
                .eq(ReceivedEvent::getStatus, MessagingConstants.RECEIVED_STATUS_PROCESSED));
```

类 javadoc 的「P1 前置约束」段改写为已落地说明（点明 status 过滤已生效、FAILED 行为同表状态机），import 追加 `com.fuyun.common.utils.TextTruncate`，删除 `release` 方法。

- [ ] **Step 3: 实现类单测改写（先失败后通过）**

`MessageIdempotencyServiceImplTest` 删除 `releaseDeletesPrefixedKeyForRedelivery`，改为下列用例（`@Captor ArgumentCaptor<Wrapper<ReceivedEvent>> updateWrapperCaptor` 需新增）：

```java
    @Test
    @DisplayName("D-7 回查带已处理状态过滤：NX 失败且台账仅有 FAILED 行时放行重新处理（不丢消息）")
    void tryAcquireFiltersProcessedStatusOnLedgerLookup() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(receivedEventMapper.exists(any())).thenReturn(false);

        assertThat(service.tryAcquire(EVENT_ID, MODULE)).isTrue();

        ArgumentCaptor<Wrapper<ReceivedEvent>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(receivedEventMapper).exists(wrapperCaptor.capture());
        // 回查条件必须含 status=PROCESSED 等值参数（FAILED 行不得被认作已处理）
        assertThat(((LambdaQueryWrapper<ReceivedEvent>) wrapperCaptor.getValue())
                        .getParamNameValuePairs()
                        .values())
                .contains(MessagingConstants.RECEIVED_STATUS_PROCESSED);
    }

    @Test
    @DisplayName("失败收尾：释放前置键 + 登记 FAILED 行（含原因与重试计数 1），本方法不抛出")
    void settleFailureReleasesKeyAndRegistersFailedRow() {
        IllegalStateException businessFailure = new IllegalStateException("业务失败：字典版本缺失");

        service.settleFailure(sampleRecord(), businessFailure);

        verify(stringRedisTemplate).delete("fy:integration:idempotency:it:" + EVENT_ID);
        verify(receivedEventMapper).insert(insertEntityCaptor.capture());
        ReceivedEvent saved = insertEntityCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(MessagingConstants.RECEIVED_STATUS_FAILED);
        assertThat(saved.getFailReason()).isEqualTo("业务失败：字典版本缺失");
        assertThat(saved.getRetryCount()).isEqualTo(1);
        assertThat(saved.getProcessedAt()).isNull();
        assertThat(businessFailure.getSuppressed()).isEmpty();
    }

    @Test
    @DisplayName("W-6③ 双保留：release 的 Redis 异常挂 suppressed 不遮蔽业务异常，FAILED 留痕照常写入")
    void settleFailureKeepsBusinessExceptionAsPrimaryWhenReleaseFails() {
        DataAccessResourceFailureException releaseFailure = new DataAccessResourceFailureException("redis 连接不可用");
        when(stringRedisTemplate.delete(anyString())).thenThrow(releaseFailure);
        IllegalStateException businessFailure = new IllegalStateException("业务失败");

        service.settleFailure(sampleRecord(), businessFailure);

        assertThat(businessFailure.getSuppressed()).containsExactly(releaseFailure);
        verify(receivedEventMapper).insert(any(ReceivedEvent.class));
    }

    @Test
    @DisplayName("失败留痕失败同样不遮蔽：DB 异常挂 suppressed，业务异常保持主异常")
    void settleFailureKeepsBusinessExceptionWhenFailureLedgerWriteFails() {
        DataAccessResourceFailureException ledgerFailure = new DataAccessResourceFailureException("数据库连接不可用");
        when(receivedEventMapper.insert(any(ReceivedEvent.class))).thenThrow(ledgerFailure);
        IllegalStateException businessFailure = new IllegalStateException("业务失败");

        service.settleFailure(sampleRecord(), businessFailure);

        assertThat(businessFailure.getSuppressed()).containsExactly(ledgerFailure);
    }

    @Test
    @DisplayName("重试再失败：FAILED 行冲突时原子累加 retry_count 并刷新原因（禁读-改-写）")
    void settleFailureAccumulatesRetryCountOnRepeatedFailure() {
        when(receivedEventMapper.insert(any(ReceivedEvent.class)))
                .thenThrow(new DuplicateKeyException("uk_received_event_event_consumer"));
        ArgumentCaptor<Wrapper<ReceivedEvent>> captor = ArgumentCaptor.forClass(Wrapper.class);

        service.settleFailure(sampleRecord(), new IllegalStateException("再次失败"));

        verify(receivedEventMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<ReceivedEvent> wrapper = (LambdaUpdateWrapper<ReceivedEvent>) captor.getValue();
        assertThat(wrapper.getSqlSet()).contains("retry_count = retry_count + 1");
    }

    @Test
    @DisplayName("失败后重试成功：唯一索引冲突时按 status=FAILED 条件升级为 PROCESSED 并清失败原因")
    void recordProcessedUpgradesFailedRow() {
        when(receivedEventMapper.insert(any(ReceivedEvent.class)))
                .thenThrow(new DuplicateKeyException("uk_received_event_event_consumer"));
        when(receivedEventMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        ArgumentCaptor<Wrapper<ReceivedEvent>> captor = ArgumentCaptor.forClass(Wrapper.class);

        service.recordProcessed(sampleRecord());

        verify(receivedEventMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<ReceivedEvent> wrapper = (LambdaUpdateWrapper<ReceivedEvent>) captor.getValue();
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(MessagingConstants.RECEIVED_STATUS_FAILED, MessagingConstants.RECEIVED_STATUS_PROCESSED);
        assertThat(wrapper.getSqlSet()).contains("fail_reason = NULL");
    }

    @Test
    @DisplayName("失败原因超列宽：留痕按 1000 字符截断后落库（W-6① 同款列宽防线）")
    void settleFailureTruncatesOverlongReason() {
        service.settleFailure(sampleRecord(), new IllegalStateException("原".repeat(3000)));

        verify(receivedEventMapper).insert(insertEntityCaptor.capture());
        assertThat(insertEntityCaptor.getValue().getFailReason())
                .hasSize(MessagingConstants.FAIL_REASON_MAX_LENGTH);
    }
```

（import 追加：`com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper`、`com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper`、`com.baomidou.mybatisplus.core.conditions.Wrapper`、`static org.mockito.ArgumentMatchers.isNull`、`com.fuyun.integration.constants.MessagingConstants`、`org.mockito.ArgumentCaptor`。既有 `recordProcessedSwallowsDuplicateKeyConflict` 用例保留，但需补 `when(receivedEventMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);`（已是 PROCESSED 行的幂等跳过分支）。）

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest=MessageIdempotencyServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false | tail -8`
Expected: `Tests run: 14, Failures: 0`

- [ ] **Step 4: 三处消费范式同步（system / iot / IT 测试消费者）**

`DictPublishedListener.onDictPublished` 与 `IotFanoutListener.onDeviceStatusChanged`、`MessagingGovernanceIT.ItDictPublishedConsumer.onDictPublished` 的 try/catch 段统一改为：

```java
        // 标准范式①：重复投递（NX 失败且回查确认已处理）直接返回跳过，即 AUTO 确认
        if (!idempotencyService.tryAcquire(envelope.eventId(), SystemMessagingConstants.MODULE)) {
            log.info(
                    "重复投递跳过：consumerModule={}，event_id={}，eventType={}",
                    SystemMessagingConstants.MODULE,
                    envelope.eventId(),
                    envelope.eventType());
            return;
        }
        // 信封五要素在业务前构造一次：成功登记与失败留痕共用（两处字段映射不漂移）
        ReceivedEventRecord record = new ReceivedEventRecord(
                envelope.eventId(),
                envelope.eventType(),
                envelope.producer(),
                envelope.occurredAt(),
                SystemMessagingConstants.MODULE);
        try {
            doBusiness(envelope);
            // 标准范式②：成功登记 received_event（唯一索引兜底并发，前次失败行升级为已处理）
            idempotencyService.recordProcessed(record);
        } catch (RuntimeException e) {
            // 标准范式③：释放前置键 + FAILED 留痕（W-6③ 双保留，异常链不遮蔽 e），上抛走有界重试进 fy.dlx
            idempotencyService.settleFailure(record, e);
            throw e;
        }
```

（三处仅模块常量不同：system 用 `SystemMessagingConstants.MODULE`、iot 用 `IotMessagingConstants.MODULE`、IT 测试类用其 `CONSUMER_MODULE` 常量；对应测试类的 `verify(idempotencyService).release(...)` 断言改为 `verify(idempotencyService).settleFailure(any(ReceivedEventRecord.class), eq(exception))` 形态——`DictPublishedListenerTest` 3 处、`IotFanoutListenerTest` 4 处、`MessagingGovernanceIT` 1 处（IT 内消费者实现本身），逐一改写并同步类 javadoc 的范式段文字。）

- [ ] **Step 5: 三模块单测与编译**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration,fuyun-system,fuyun-iot -am test | tail -12`
Expected: `BUILD SUCCESS`，各模块 `Failures: 0`（含改写后的 `DictPublishedListenerTest` / `IotFanoutListenerTest`）

- [ ] **Step 6: Commit**

```bash
git add backend/fuyun-common/src/main/java/com/fuyun/common/messaging/MessageIdempotencyService.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/service/impl/MessageIdempotencyServiceImpl.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/MessageIdempotencyServiceImplTest.java \
        backend/fuyun-system/src/main/java/com/fuyun/system/internal/DictPublishedListener.java \
        backend/fuyun-system/src/test/java/com/fuyun/system/internal/DictPublishedListenerTest.java \
        backend/fuyun-iot/src/main/java/com/fuyun/iot/internal/IotFanoutListener.java \
        backend/fuyun-iot/src/test/java/com/fuyun/iot/internal/IotFanoutListenerTest.java \
        backend/fuyun-app/src/test/java/com/fuyun/app/MessagingGovernanceIT.java
git commit -m "feat(common): 幂等构件失败链留痕与 release 异常双保留（W-6③/D-7 口径）"
```

### Task 6: 消费台账查询端点（received-events，事件溯源·消费侧）

**Files:**
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/convert/IntegrationConverter.java`（追加映射方法）
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/dto/ReceivedEventQuery.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/vo/ReceivedEventVO.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/service/IReceivedEventQueryService.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/service/impl/ReceivedEventQueryServiceImpl.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/controller/ReceivedEventController.java`
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/config/IntegrationWebConfig.java`
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/ReceivedEventQueryServiceImplTest.java`
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/controller/IntegrationQueryControllersTest.java`

**Interfaces:**
- Consumes: `ReceivedEvent` 实体与 `ReceivedEventMapper`（已有，BaseMapper）；Task 2 的 `PageResult`、`IntegrationConverter`。
- Produces:
  - `IReceivedEventQueryService.query(ReceivedEventQuery) : PageResult<ReceivedEventVO>`（**聚合/报表型接口不继承 IService**，A.4.3-20：实现注入 mapper）
  - `ReceivedEventQuery(String eventType, UUID eventId, String consumerModule, String status, OffsetDateTime receivedFrom, OffsetDateTime receivedTo, int page, int size)`
  - `IntegrationConverter.toReceivedEventVO(ReceivedEvent)` / `toReceivedEventVOs(List<ReceivedEvent>)`
  - HTTP：`GET /api/v1/integration/received-events`（FU-M20-06 事件查询台：按事件类型/时间/状态检索消费记录）

**覆盖维度说明（FU-M20-06「按事件类型/时间/状态检索消费记录」）：** 时间维走 V3 既有索引 `idx_received_event_event_type(event_type, received_at)`；状态维含 `FAILED`（Task 5 起实写）；`eventId` 维支撑「按事件全链溯源消费侧记录」。`ReceivedEventQuery.eventId` 用 `UUID` 类型（PG `uuid` 列 + 全局 `UuidTypeHandler`），HTTP 侧非法 UUID 由 Spring 类型转换失败渲染 400。

- [ ] **Step 1: 查询条件、出参与查询服务契约**

`dto/ReceivedEventQuery.java`：

```java
package com.fuyun.integration.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 消费台账查询条件（GET /api/v1/integration/received-events，A.7-1 参数对象化）。
 *
 * @param eventType      事件类型过滤（如 system.dict.published），可空 = 不过滤
 * @param eventId        信封 eventId 过滤（UUID，全链溯源锚点），可空 = 不过滤
 * @param consumerModule 消费者模块域标识过滤，可空 = 不过滤
 * @param status         消费状态过滤（PROCESSED/FAILED），可空 = 不过滤
 * @param receivedFrom   接收时间下界（含），可空 = 不限
 * @param receivedTo     接收时间上界（含），可空 = 不限
 * @param page           页码（0 基，宪法 A.3-6），缺省 0
 * @param size           单页条数（1-200），缺省 20
 */
public record ReceivedEventQuery(
        String eventType,
        UUID eventId,
        String consumerModule,
        String status,
        OffsetDateTime receivedFrom,
        OffsetDateTime receivedTo,
        int page,
        int size) {}
```

`vo/ReceivedEventVO.java`：

```java
package com.fuyun.integration.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 消费台账行出参（GET /api/v1/integration/received-events，M20 Spec §4 received_event 字段面）。
 *
 * @param id             台账行 ID（雪花 ID），非空；JSON 输出为字符串
 * @param eventId        信封 eventId（UUID），非空
 * @param eventType      事件类型，非空
 * @param producer       生产模块域标识，非空
 * @param occurredAt     事件发生时刻（信封字段），非空
 * @param consumerModule 消费者模块域标识，非空
 * @param status         消费状态：PROCESSED 已消费 / FAILED 消费失败（重试中），非空
 * @param failReason     消费失败原因，可空（PROCESSED 行清空）
 * @param retryCount     消费失败登记次数（0=未失败过；容器侧重试不落库，口径见 PR-1b 计划），非空
 * @param receivedAt     接收时间（数据库维护），非空
 * @param processedAt    处理完成时间，可空（FAILED 行未完成）
 */
public record ReceivedEventVO(
        Long id,
        UUID eventId,
        String eventType,
        String producer,
        OffsetDateTime occurredAt,
        String consumerModule,
        String status,
        String failReason,
        Integer retryCount,
        OffsetDateTime receivedAt,
        OffsetDateTime processedAt) {}
```

`service/IReceivedEventQueryService.java`：

```java
package com.fuyun.integration.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.ReceivedEventQuery;
import com.fuyun.integration.vo.ReceivedEventVO;

/**
 * 消费台账查询服务（M20 FU-M20-06 事件查询台）：received_event 的只读查询面。
 *
 * <p>聚合/报表型接口不继承 IService（backend 宪法 A.4.3-20），实现注入 mapper 承担分页查询；
 * 写路径归 {@code MessageIdempotencyServiceImpl}（两层幂等构件），本接口零写语义。
 */
public interface IReceivedEventQueryService {

    /**
     * 分页查询消费台账（按事件类型/事件 ID/消费者/状态/接收时间窗过滤，接收时间倒序）。
     *
     * @param query 查询条件，非空；page 0 基、size 1-200
     * @return 分页出参（0 基页码），非空
     */
    PageResult<ReceivedEventVO> query(ReceivedEventQuery query);
}
```

- [ ] **Step 2: 查询实现失败单测**

`backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/ReceivedEventQueryServiceImplTest.java`：

```java
package com.fuyun.integration.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.ReceivedEventQuery;
import com.fuyun.integration.entity.ReceivedEvent;
import com.fuyun.integration.mapper.ReceivedEventMapper;
import com.fuyun.integration.vo.ReceivedEventVO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 消费台账查询服务单测：分页契约转换（0 基 ↔ MP 1 基）、过滤条件装配与出参映射。
 */
@ExtendWith(MockitoExtension.class)
class ReceivedEventQueryServiceImplTest {

    /** 测试事件号：与断言中的 eventId 一致 */
    private static final UUID EVENT_ID = UUID.fromString("b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60");

    @Mock
    private ReceivedEventMapper receivedEventMapper;

    private ReceivedEventQueryServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReceivedEvent.class);
    }

    @BeforeEach
    void setUp() {
        service = new ReceivedEventQueryServiceImpl(receivedEventMapper, IntegrationConverter.INSTANCE);
    }

    @Test
    @DisplayName("分页查询：0 基请求与 0 基出参一致，过滤条件进入 wrapper，FAILED 行原样出参")
    void queryKeepsZeroBasedContractAndCarriesFailureRow() {
        ReceivedEvent row = new ReceivedEvent();
        row.setId(11L);
        row.setEventId(EVENT_ID);
        row.setEventType("system.dict.published");
        row.setProducer("system");
        row.setOccurredAt(OffsetDateTime.parse("2026-09-15T01:00:00Z"));
        row.setConsumerModule("it");
        row.setStatus("FAILED");
        row.setFailReason("业务失败：字典版本缺失");
        row.setRetryCount(3);
        row.setReceivedAt(OffsetDateTime.parse("2026-09-15T01:00:05Z"));
        when(receivedEventMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenAnswer(invocation -> {
            IPage<ReceivedEvent> page = invocation.getArgument(0);
            page.setRecords(List.of(row));
            page.setTotal(1L);
            return page;
        });

        PageResult<ReceivedEventVO> result = service.query(new ReceivedEventQuery(
                "system.dict.published",
                EVENT_ID,
                "it",
                "FAILED",
                OffsetDateTime.parse("2026-09-14T00:00:00Z"),
                OffsetDateTime.parse("2026-09-16T00:00:00Z"),
                0,
                20));

        assertThat(result.page()).isZero();
        assertThat(result.total()).isEqualTo(1L);
        ReceivedEventVO vo = result.content().get(0);
        assertThat(vo.status()).isEqualTo("FAILED");
        assertThat(vo.failReason()).isEqualTo("业务失败：字典版本缺失");
        assertThat(vo.retryCount()).isEqualTo(3);
        assertThat(vo.processedAt()).isNull();
        ArgumentCaptor<IPage<ReceivedEvent>> pageCaptor = ArgumentCaptor.forClass(IPage.class);
        verify(receivedEventMapper).selectPage(pageCaptor.capture(), any(Wrapper.class));
        // 契约 0 基 → MP 分页器 1 基（服务层唯一转换点）
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(20L);
    }
}
```

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest=ReceivedEventQueryServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false | tail -5`
Expected: COMPILATION ERROR（`ReceivedEventQueryServiceImpl` 不存在）

- [ ] **Step 3: 查询实现**

`service/impl/ReceivedEventQueryServiceImpl.java`：

```java
package com.fuyun.integration.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.ReceivedEventQuery;
import com.fuyun.integration.entity.ReceivedEvent;
import com.fuyun.integration.mapper.ReceivedEventMapper;
import com.fuyun.integration.service.IReceivedEventQueryService;
import com.fuyun.integration.vo.ReceivedEventVO;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消费台账查询实现：received_event 的只读分页查询（M20 FU-M20-06 事件查询台）。
 *
 * <p>禁写：本类不提供任何写方法——写路径唯一入口是两层幂等构件（MessageIdempotencyServiceImpl），
 * 避免同一台账出现两条写入语义（读-改-写与幂等登记冲突）。
 *
 * <p>归 service/impl 包 = JaCoCo 核心包 PACKAGE LINE 1.00 覆盖对象。
 */
public class ReceivedEventQueryServiceImpl implements IReceivedEventQueryService {

    private final ReceivedEventMapper receivedEventMapper;

    private final IntegrationConverter converter;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import）。
     *
     * @param receivedEventMapper 消费台账 mapper，非空；来源：@MapperScan 扫描注册
     * @param converter           治理域转换器，非空；来源：IntegrationWebConfig @Bean
     */
    public ReceivedEventQueryServiceImpl(ReceivedEventMapper receivedEventMapper, IntegrationConverter converter) {
        this.receivedEventMapper = receivedEventMapper;
        this.converter = converter;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ReceivedEventVO> query(ReceivedEventQuery query) {
        LambdaQueryWrapper<ReceivedEvent> wrapper = Wrappers.lambdaQuery(ReceivedEvent.class)
                .eq(query.eventType() != null, ReceivedEvent::getEventType, query.eventType())
                .eq(query.eventId() != null, ReceivedEvent::getEventId, query.eventId())
                .eq(query.consumerModule() != null, ReceivedEvent::getConsumerModule, query.consumerModule())
                .eq(query.status() != null, ReceivedEvent::getStatus, query.status())
                .ge(query.receivedFrom() != null, ReceivedEvent::getReceivedAt, query.receivedFrom())
                .le(query.receivedTo() != null, ReceivedEvent::getReceivedAt, query.receivedTo())
                // 排序唯一性约束（A.4.3-17）：接收时间相同时以主键兜底
                .orderByDesc(ReceivedEvent::getReceivedAt)
                .orderByDesc(ReceivedEvent::getId);
        Page<ReceivedEvent> page =
                receivedEventMapper.selectPage(new Page<>(query.page() + 1L, query.size()), wrapper);
        return PageResult.of(
                converter.toReceivedEventVOs(page.getRecords()),
                page.getCurrent() - 1,
                page.getSize(),
                page.getTotal());
    }
}
```

同时在 `IntegrationConverter.java` 追加：

```java
    /**
     * 消费台账实体 → 行出参。
     *
     * @param entity 消费台账实体（received_event），非空
     * @return 行出参，非空
     */
    ReceivedEventVO toReceivedEventVO(ReceivedEvent entity);

    /**
     * 消费台账实体清单 → 行出参清单。
     *
     * @param entities 实体清单，非空（可为空清单）
     * @return 行出参清单，非 null
     */
    List<ReceivedEventVO> toReceivedEventVOs(List<ReceivedEvent> entities);
```

（import 追加 `com.fuyun.integration.entity.ReceivedEvent`、`com.fuyun.integration.vo.ReceivedEventVO`。）

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest=ReceivedEventQueryServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false | tail -5`
Expected: `Tests run: 1, Failures: 0`

- [ ] **Step 4: 端点**

`controller/ReceivedEventController.java`：

```java
package com.fuyun.integration.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.ReceivedEventQuery;
import com.fuyun.integration.service.IReceivedEventQueryService;
import com.fuyun.integration.vo.ReceivedEventVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消费台账读端点（GET /api/v1/integration/received-events，M20 §7 + FU-M20-06 事件查询台）。
 *
 * <p>受既有 /api/v1/** 认证拦截；职责边界：仅参数透传与响应编排（宪法 B.1）。时间参数取 ISO-8601
 * （{@code 2026-09-15T01:02:03Z}），闭区间语义（ge/le）；非法 UUID 或时间格式由 Spring 参数绑定
 * 失败渲染 400 ProblemDetail。
 */
@RestController
@RequestMapping("/api/v1/integration/received-events")
@Validated
public class ReceivedEventController {

    private final IReceivedEventQueryService receivedEventQueryService;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import）。
     *
     * @param receivedEventQueryService 消费台账查询服务，非空；注入接口类型（B.2-2）
     */
    public ReceivedEventController(IReceivedEventQueryService receivedEventQueryService) {
        this.receivedEventQueryService = receivedEventQueryService;
    }

    /**
     * 分页查询消费记录（按事件类型/事件 ID/消费者/状态/接收时间窗检索）。
     *
     * @param eventType      事件类型过滤，可空 = 不过滤
     * @param eventId        信封 eventId 过滤（UUID），可空 = 不过滤
     * @param consumerModule 消费者模块标识过滤，可空 = 不过滤
     * @param status         消费状态过滤（PROCESSED/FAILED），可空 = 不过滤
     * @param receivedFrom   接收时间下界（含，ISO-8601），可空 = 不限
     * @param receivedTo     接收时间上界（含，ISO-8601），可空 = 不限
     * @param page           页码（0 基），缺省 0
     * @param size           单页条数（1-200），缺省 20
     * @return 分页出参（0 基页码）
     */
    @GetMapping
    public PageResult<ReceivedEventVO> list(
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "eventId", required = false) UUID eventId,
            @RequestParam(value = "consumerModule", required = false) String consumerModule,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "receivedFrom", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime receivedFrom,
            @RequestParam(value = "receivedTo", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime receivedTo,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return receivedEventQueryService.query(
                new ReceivedEventQuery(eventType, eventId, consumerModule, status, receivedFrom, receivedTo, page, size));
    }
}
```

`IntegrationWebConfig` 的 @Import 清单追加 `ReceivedEventQueryServiceImpl.class, ReceivedEventController.class`。

- [ ] **Step 5: 端点薄层单测**

`controller/IntegrationQueryControllersTest.java`（**本类在 Task 7/8 继续追加另两个查询端点的用例**）：

```java
package com.fuyun.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.ReceivedEventQuery;
import com.fuyun.integration.service.IReceivedEventQueryService;
import com.fuyun.integration.vo.ReceivedEventVO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 治理查询端点薄层单测（合并类形态对齐既有 DictControllersTest）：请求参数装配与响应直返，
 * 业务逻辑归各查询服务单测。
 */
@ExtendWith(MockitoExtension.class)
class IntegrationQueryControllersTest {

    @Mock
    private IReceivedEventQueryService receivedEventQueryService;

    @Captor
    private ArgumentCaptor<ReceivedEventQuery> queryCaptor;

    private ReceivedEventController receivedEventController;

    @BeforeEach
    void setUp() {
        receivedEventController = new ReceivedEventController(receivedEventQueryService);
    }

    @Test
    @DisplayName("消费台账端点：八个请求参数按序装配为查询对象，服务出参直返")
    void receivedEventListDelegatesQueryParameters() {
        UUID eventId = UUID.fromString("b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60");
        PageResult<ReceivedEventVO> expected = PageResult.of(List.of(), 0L, 20L, 0L);
        when(receivedEventQueryService.query(any())).thenReturn(expected);

        PageResult<ReceivedEventVO> actual = receivedEventController.list(
                "system.dict.published",
                eventId,
                "it",
                "FAILED",
                OffsetDateTime.parse("2026-09-14T00:00:00Z"),
                OffsetDateTime.parse("2026-09-16T00:00:00Z"),
                1,
                50);

        assertThat(actual).isSameAs(expected);
        verify(receivedEventQueryService).query(queryCaptor.capture());
        ReceivedEventQuery query = queryCaptor.getValue();
        assertThat(query.eventType()).isEqualTo("system.dict.published");
        assertThat(query.eventId()).isEqualTo(eventId);
        assertThat(query.consumerModule()).isEqualTo("it");
        assertThat(query.status()).isEqualTo("FAILED");
        assertThat(query.receivedFrom()).isEqualTo(OffsetDateTime.parse("2026-09-14T00:00:00Z"));
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(50);
    }
}
```

- [ ] **Step 6: 运行与 Commit**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest='ReceivedEventQueryServiceImplTest,IntegrationQueryControllersTest' -Dsurefire.failIfNoSpecifiedTests=false | tail -6`
Expected: `Tests run: 2, Failures: 0`

```bash
git add backend/fuyun-integration/src/main/java/com/fuyun/integration/convert/IntegrationConverter.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/dto/ReceivedEventQuery.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/vo/ReceivedEventVO.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/service/IReceivedEventQueryService.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/service/impl/ReceivedEventQueryServiceImpl.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/controller/ReceivedEventController.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/config/IntegrationWebConfig.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/ReceivedEventQueryServiceImplTest.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/controller/IntegrationQueryControllersTest.java
git commit -m "feat(integration): 消费台账查询端点支撑事件溯源消费侧检索"
```

---

### Task 7: 投递台账查询端点（event-publications，事件溯源·投递侧）

**Files:**
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/constants/MessagingConstants.java`（完成态派生常量）
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/convert/IntegrationConverter.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/entity/EventPublication.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/mapper/EventPublicationMapper.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/dto/EventPublicationQuery.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/vo/EventPublicationVO.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/service/IEventPublicationQueryService.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/service/impl/EventPublicationQueryServiceImpl.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/controller/EventPublicationController.java`
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/config/IntegrationWebConfig.java`
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/EventPublicationQueryServiceImplTest.java`
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/controller/IntegrationQueryControllersTest.java`（追加）

**Interfaces:**
- Consumes: PR-1a 的 `event_publication`（V500，公共 schema）；Spring Modulith 1.4.13 官方表结构（id UUID / listener_id TEXT / event_type TEXT / serialized_event TEXT / publication_date / completion_date）。
- Produces:
  - 常量 `MessagingConstants.PUBLICATION_STATUS_COMPLETED = "COMPLETED"`、`PUBLICATION_STATUS_INCOMPLETE = "INCOMPLETE"`
  - `IEventPublicationQueryService.query(EventPublicationQuery) : PageResult<EventPublicationVO>`
  - `EventPublicationQuery(String eventType, String status, OffsetDateTime publishedFrom, OffsetDateTime publishedTo, int page, int size)`
  - `EventPublicationVO(UUID id, String listenerId, String eventType, OffsetDateTime publicationDate, OffsetDateTime completionDate, String status)`
  - HTTP：`GET /api/v1/integration/event-publications`

**契约边界（写死）：** ① 只读投影——`event_publication` 由 Modulith 框架读写（`EventOpsJob` 重试/清理），M20 侧**禁写**；② **不映射 `serialized_event`**（框架整事件序列化载荷，含业务数据，不对外出参，也避免大字段与全表 LIKE 反查）；③ 版本耦合面 = V500 DDL（自家迁移）+ 框架 1.4.13（父 POM 锁定），列集变更随 Modulith 升级的迁移审查同步。

- [ ] **Step 1: 常量与实体（只读投影）**

`MessagingConstants` 追加：

```java
    /** event_publication 完成态派生值：completion_date 非空 = 投递完成（框架已标记） */
    public static final String PUBLICATION_STATUS_COMPLETED = "COMPLETED";

    /** event_publication 完成态派生值：completion_date 为空 = 未完成（监听器失败/实例宕机，待 EventOpsJob 重投） */
    public static final String PUBLICATION_STATUS_INCOMPLETE = "INCOMPLETE";
```

`entity/EventPublication.java`：

```java
package com.fuyun.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Modulith 事件发布注册表只读投影（public.event_publication，V500 迁移；D-2 裁决载体）。
 *
 * <p>只读边界：表的写入与清理由 Spring Modulith 框架（JDBC 事件注册表）与 EventOpsJob 承担，
 * 本实体仅供治理查询面投影，禁止经本实体写库（无 setter 使用场景亦不得新增写方法）。
 *
 * <p>刻意不映射 serialized_event 列：该列为框架自用的整事件序列化载荷（含业务数据），
 * 查询面不需要且不应外泄；MP 生成的 SELECT 仅含本类声明列（按需取列，A.4.3-14）。
 *
 * <p>id 用 IdType.INPUT：框架以 UUID 主键写入，本投影不生成 ID。
 */
@Getter
@Setter
@TableName("event_publication")
public class EventPublication {

    /** 发布记录主键（框架生成的 UUID） */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 监听器标识（框架以「类名.方法名」形态记录事件监听目标） */
    private String listenerId;

    /** 事件类型全限定名（Java 类名，非 event_registry 的 <模块>.<实体>.<动作> 命名） */
    private String eventType;

    /** 发布时刻（业务事务内暂存时间） */
    private OffsetDateTime publicationDate;

    /** 完成时刻；为空 = 未完成（监听失败或实例宕机，待 EventOpsJob 按 5 分钟阈值重投） */
    private OffsetDateTime completionDate;
}
```

`mapper/EventPublicationMapper.java`：

```java
package com.fuyun.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.integration.entity.EventPublication;
import org.apache.ibatis.annotations.Mapper;

/**
 * 投递注册表只读 mapper：event_publication 单表查询经 BaseMapper 内置能力（无 XML，宪法 A.4.3-15）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 * 写入归框架（Modulith 注册表 + EventOpsJob），本 mapper 禁承担任何写调用（调用方审查项）。
 */
@Mapper
public interface EventPublicationMapper extends BaseMapper<EventPublication> {}
```

- [ ] **Step 2: 查询条件、出参、服务契约**

`dto/EventPublicationQuery.java`：

```java
package com.fuyun.integration.dto;

import java.time.OffsetDateTime;

/**
 * 投递台账查询条件（GET /api/v1/integration/event-publications，A.7-1 参数对象化）。
 *
 * @param eventType     事件类型全限定名过滤（框架记录形态），可空 = 不过滤
 * @param status        完成态过滤（COMPLETED/INCOMPLETE），可空 = 不过滤
 * @param publishedFrom 发布时间下界（含），可空 = 不限
 * @param publishedTo   发布时间上界（含），可空 = 不限
 * @param page          页码（0 基，宪法 A.3-6），缺省 0
 * @param size          单页条数（1-200），缺省 20
 */
public record EventPublicationQuery(
        String eventType, String status, OffsetDateTime publishedFrom, OffsetDateTime publishedTo, int page, int size) {}
```

`vo/EventPublicationVO.java`：

```java
package com.fuyun.integration.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 投递台账行出参（GET /api/v1/integration/event-publications）：PR-1a 可靠投递链路的可观测面。
 *
 * @param id              发布记录 ID（框架 UUID），非空
 * @param listenerId      监听器标识（框架记录），非空
 * @param eventType       事件类型全限定名（框架记录；与 event_registry 的 <模块>.<实体>.<动作> 命名不同源）
 * @param publicationDate 发布时刻（业务事务内暂存时间），非空
 * @param completionDate  完成时刻，可空；status=INCOMPLETE 时为空
 * @param status          完成态派生值（COMPLETED/INCOMPLETE），非空
 */
public record EventPublicationVO(
        UUID id,
        String listenerId,
        String eventType,
        OffsetDateTime publicationDate,
        OffsetDateTime completionDate,
        String status) {}
```

`service/IEventPublicationQueryService.java`：

```java
package com.fuyun.integration.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.EventPublicationQuery;
import com.fuyun.integration.vo.EventPublicationVO;

/**
 * 投递台账查询服务（事件溯源·投递侧）：Modulith 事件发布注册表的只读查询面。
 *
 * <p>用途：运维排查「事件已暂存但未完成（监听器失败/实例宕机）」与投递历史；未完成记录的重投与
 * 已完成记录清理归 EventOpsJob（fuyun-app internal，挂 ShedLock），本接口零写语义。
 */
public interface IEventPublicationQueryService {

    /**
     * 分页查询投递记录（按事件类型/完成态/发布时间窗过滤，发布时刻倒序）。
     *
     * @param query 查询条件，非空；page 0 基、size 1-200；status 仅接受 COMPLETED/INCOMPLETE
     * @return 分页出参（0 基页码），非空
     */
    PageResult<EventPublicationVO> query(EventPublicationQuery query);
}
```

- [ ] **Step 3: 查询实现失败单测**

`backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/EventPublicationQueryServiceImplTest.java`：

```java
package com.fuyun.integration.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.EventPublicationQuery;
import com.fuyun.integration.entity.EventPublication;
import com.fuyun.integration.mapper.EventPublicationMapper;
import com.fuyun.integration.vo.EventPublicationVO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 投递台账查询服务单测：完成态派生（completion_date 空 ↔ INCOMPLETE）、分页契约与出参映射。
 */
@ExtendWith(MockitoExtension.class)
class EventPublicationQueryServiceImplTest {

    @Mock
    private EventPublicationMapper eventPublicationMapper;

    private EventPublicationQueryServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), EventPublication.class);
    }

    @BeforeEach
    void setUp() {
        service = new EventPublicationQueryServiceImpl(eventPublicationMapper, IntegrationConverter.INSTANCE);
    }

    @Test
    @DisplayName("未完成投递：completion_date 空的行派生 status=INCOMPLETE，序列化载荷列不出参")
    void queryDerivesIncompleteStatusWithoutPayloadColumn() {
        EventPublication incomplete = new EventPublication();
        incomplete.setId(UUID.fromString("2f3d0d6a-1c2b-4f5e-8a91-0b1c2d3e4f50"));
        incomplete.setListenerId("com.fuyun.app.internal.ProbeListener.on");
        incomplete.setEventType("com.fuyun.app.LifecycleProbeEvent");
        incomplete.setPublicationDate(OffsetDateTime.parse("2026-09-15T02:00:00Z"));
        when(eventPublicationMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenAnswer(invocation -> {
            IPage<EventPublication> page = invocation.getArgument(0);
            page.setRecords(List.of(incomplete));
            page.setTotal(1L);
            return page;
        });

        PageResult<EventPublicationVO> result = service.query(
                new EventPublicationQuery(null, MessagingConstants.PUBLICATION_STATUS_INCOMPLETE, null, null, 0, 20));

        assertThat(result.page()).isZero();
        EventPublicationVO vo = result.content().get(0);
        assertThat(vo.status()).isEqualTo(MessagingConstants.PUBLICATION_STATUS_INCOMPLETE);
        assertThat(vo.completionDate()).isNull();
        assertThat(vo.listenerId()).isEqualTo("com.fuyun.app.internal.ProbeListener.on");
    }
}
```

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest=EventPublicationQueryServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false | tail -5`
Expected: COMPILATION ERROR（`EventPublicationQueryServiceImpl` 不存在）

- [ ] **Step 4: 查询实现与转换器方法**

`service/impl/EventPublicationQueryServiceImpl.java`：

```java
package com.fuyun.integration.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.EventPublicationQuery;
import com.fuyun.integration.entity.EventPublication;
import com.fuyun.integration.mapper.EventPublicationMapper;
import com.fuyun.integration.service.IEventPublicationQueryService;
import com.fuyun.integration.vo.EventPublicationVO;
import org.springframework.transaction.annotation.Transactional;

/**
 * 投递台账查询实现：event_publication 的只读分页查询（PR-1a 可靠投递链路的可观测面）。
 *
 * <p>只读边界：本类不提供任何写方法；表写入与清理归框架与 EventOpsJob。
 * 归 service/impl 包 = JaCoCo 核心包 PACKAGE LINE 1.00 覆盖对象。
 */
public class EventPublicationQueryServiceImpl implements IEventPublicationQueryService {

    private final EventPublicationMapper eventPublicationMapper;

    private final IntegrationConverter converter;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import）。
     *
     * @param eventPublicationMapper 投递注册表只读 mapper，非空；来源：@MapperScan 扫描注册
     * @param converter              治理域转换器，非空；来源：IntegrationWebConfig @Bean
     */
    public EventPublicationQueryServiceImpl(
            EventPublicationMapper eventPublicationMapper, IntegrationConverter converter) {
        this.eventPublicationMapper = eventPublicationMapper;
        this.converter = converter;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<EventPublicationVO> query(EventPublicationQuery query) {
        boolean incomplete = MessagingConstants.PUBLICATION_STATUS_INCOMPLETE.equals(query.status());
        boolean completed = MessagingConstants.PUBLICATION_STATUS_COMPLETED.equals(query.status());
        LambdaQueryWrapper<EventPublication> wrapper = Wrappers.lambdaQuery(EventPublication.class)
                .eq(query.eventType() != null, EventPublication::getEventType, query.eventType())
                // 完成态无独立列：以 completion_date 空/非空表达（与 VO 派生口径同源）
                .isNull(incomplete, EventPublication::getCompletionDate)
                .isNotNull(completed, EventPublication::getCompletionDate)
                .ge(query.publishedFrom() != null, EventPublication::getPublicationDate, query.publishedFrom())
                .le(query.publishedTo() != null, EventPublication::getPublicationDate, query.publishedTo())
                .orderByDesc(EventPublication::getPublicationDate)
                .orderByDesc(EventPublication::getId);
        Page<EventPublication> page =
                eventPublicationMapper.selectPage(new Page<>(query.page() + 1L, query.size()), wrapper);
        return PageResult.of(
                converter.toEventPublicationVOs(page.getRecords()),
                page.getCurrent() - 1,
                page.getSize(),
                page.getTotal());
    }
}
```

`IntegrationConverter.java` 追加：

```java
    /**
     * 投递记录实体 → 行出参（完成态由 completion_date 空/非空派生）。
     *
     * @param entity 投递记录实体，非空
     * @return 行出参（status=COMPLETED/INCOMPLETE），非空
     */
    @Mapping(target = "status", source = "completionDate")
    EventPublicationVO toEventPublicationVO(EventPublication entity);

    /**
     * 投递记录实体清单 → 行出参清单。
     *
     * @param entities 实体清单，非空（可为空清单）
     * @return 行出参清单，非 null
     */
    List<EventPublicationVO> toEventPublicationVOs(List<EventPublication> entities);

    /**
     * 完成态派生：completion_date 非空 = COMPLETED，为空 = INCOMPLETE（框架完成标记的唯一判据）。
     *
     * @param completionDate 完成时刻，可空
     * @return COMPLETED 或 INCOMPLETE，非空
     */
    default String mapPublicationStatus(java.time.OffsetDateTime completionDate) {
        return completionDate == null
                ? MessagingConstants.PUBLICATION_STATUS_INCOMPLETE
                : MessagingConstants.PUBLICATION_STATUS_COMPLETED;
    }
```

（`mapPublicationStatus` 的形参写全限定名仅为示例：落码时 import `java.time.OffsetDateTime` 用短名，A.1-13。）

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest=EventPublicationQueryServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false | tail -5`
Expected: `Tests run: 1, Failures: 0`

- [ ] **Step 5: 端点与薄层用例追加**

`controller/EventPublicationController.java`：

```java
package com.fuyun.integration.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.EventPublicationQuery;
import com.fuyun.integration.service.IEventPublicationQueryService;
import com.fuyun.integration.vo.EventPublicationVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投递台账读端点（GET /api/v1/integration/event-publications）：Modulith 事件注册表只读投影。
 *
 * <p>受既有 /api/v1/** 认证拦截；职责边界：仅参数透传与响应编排（宪法 B.1）。status 仅接受
 * COMPLETED/INCOMPLETE（@Pattern 声明式校验，非法值渲染 400）。
 */
@RestController
@RequestMapping("/api/v1/integration/event-publications")
@Validated
public class EventPublicationController {

    private final IEventPublicationQueryService eventPublicationQueryService;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import）。
     *
     * @param eventPublicationQueryService 投递台账查询服务，非空；注入接口类型（B.2-2）
     */
    public EventPublicationController(IEventPublicationQueryService eventPublicationQueryService) {
        this.eventPublicationQueryService = eventPublicationQueryService;
    }

    /**
     * 分页查询投递记录（排查未完成投递：status=INCOMPLETE）。
     *
     * @param eventType     事件类型全限定名过滤，可空 = 不过滤
     * @param status        完成态过滤（COMPLETED/INCOMPLETE），可空 = 不过滤
     * @param publishedFrom 发布时间下界（含，ISO-8601），可空 = 不限
     * @param publishedTo   发布时间上界（含，ISO-8601），可空 = 不限
     * @param page          页码（0 基），缺省 0
     * @param size          单页条数（1-200），缺省 20
     * @return 分页出参（0 基页码）
     */
    @GetMapping
    public PageResult<EventPublicationVO> list(
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "status", required = false)
                    @Pattern(regexp = "COMPLETED|INCOMPLETE", message = "status 仅支持 COMPLETED 或 INCOMPLETE")
                    String status,
            @RequestParam(value = "publishedFrom", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime publishedFrom,
            @RequestParam(value = "publishedTo", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime publishedTo,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return eventPublicationQueryService.query(
                new EventPublicationQuery(eventType, status, publishedFrom, publishedTo, page, size));
    }
}
```

`IntegrationWebConfig` @Import 追加 `EventPublicationQueryServiceImpl.class, EventPublicationController.class`；`IntegrationQueryControllersTest` 追加 `@Mock IEventPublicationQueryService` + 用例：

```java
    @Test
    @DisplayName("投递台账端点：六个请求参数装配为查询对象，含 INCOMPLETE 完成态过滤")
    void eventPublicationListDelegatesQueryParameters() {
        PageResult<EventPublicationVO> expected = PageResult.of(List.of(), 0L, 20L, 0L);
        when(eventPublicationQueryService.query(any())).thenReturn(expected);

        PageResult<EventPublicationVO> actual =
                eventPublicationController.list("com.fuyun.app.LifecycleProbeEvent", "INCOMPLETE", null, null, 0, 20);

        assertThat(actual).isSameAs(expected);
        verify(eventPublicationQueryService).query(publicationQueryCaptor.capture());
        assertThat(publicationQueryCaptor.getValue().status()).isEqualTo("INCOMPLETE");
        assertThat(publicationQueryCaptor.getValue().eventType()).isEqualTo("com.fuyun.app.LifecycleProbeEvent");
    }
```

（同批次补 `@Mock IEventPublicationQueryService eventPublicationQueryService;`、`@Captor ArgumentCaptor<EventPublicationQuery> publicationQueryCaptor;` 字段与 `setUp` 构造。）

- [ ] **Step 6: 运行与 Commit**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest='EventPublicationQueryServiceImplTest,IntegrationQueryControllersTest' -Dsurefire.failIfNoSpecifiedTests=false | tail -6`
Expected: `Tests run: 3, Failures: 0`

```bash
git add backend/fuyun-integration/src/main/java/com/fuyun/integration/constants/MessagingConstants.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/convert/IntegrationConverter.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/entity/EventPublication.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/mapper/EventPublicationMapper.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/dto/EventPublicationQuery.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/vo/EventPublicationVO.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/service/IEventPublicationQueryService.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/service/impl/EventPublicationQueryServiceImpl.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/controller/EventPublicationController.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/config/IntegrationWebConfig.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/EventPublicationQueryServiceImplTest.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/controller/IntegrationQueryControllersTest.java
git commit -m "feat(integration): 投递台账查询端点暴露 Modulith 注册表只读投影"
```

---

### Task 8: W-6② 订阅登记并发守卫与 broadcast 拒订 + 契约台账查询端点

**Files:**
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/service/IEventRegistryService.java`
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/service/impl/EventRegistryServiceImpl.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/dto/EventRegistryQuery.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/vo/EventRegistryVO.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/controller/EventRegistryController.java`
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/convert/IntegrationConverter.java`
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/config/IntegrationWebConfig.java`
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/EventRegistryServiceImplTest.java`
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/controller/IntegrationQueryControllersTest.java`（追加）

**Interfaces:**
- Consumes: `EventRegistry` 实体（@TableLogic deleted）、`EventRegistryMapper`、Task 2 的 `IntegrationConverter`/`PageResult`、`MessagingConstants.SUBSCRIBER_BROADCAST`。
- Produces:
  - `EventRegistryServiceImpl` 构造器变更：`(IntegrationConverter converter)`
  - `registerSubscriber(String eventType, String consumerModule)` 语义增强：CAS 条件更新 + 自旋重试 3 次 + broadcast 拒订 + 超限 fail-fast
  - `IEventRegistryService.query(EventRegistryQuery) : PageResult<EventRegistryVO>`
  - `EventRegistryQuery(String eventType, String producerModule, String status, int page, int size)`、`EventRegistryVO(Long id, String eventType, String producerModule, String payloadDesc, String subscriberModules, String status, OffsetDateTime registeredAt)`
  - HTTP：`GET /api/v1/integration/event-registry`

**并发语义（W-6② 裁决）：** 采用**条件更新 CAS**（`WHERE id = ? AND subscriber_modules = <读取到的旧值>`）替代读-改-写盲写：影响 0 行 = 他实例已改清单 → 重读重算重试（上界 3 次）；耗尽仍失败则抛 `IllegalStateException` **fail-fast**（订阅登记在装配期执行，静默丢订阅比启动失败危险得多——与唯一索引兜底 `register` 的「吞冲突」语义不同，此处不得吞）。零迁移（复用既有列，不加 version 列）。broadcast 行拒订：`subscriber_modules='broadcast'` 语义为「零订阅广播，不承载订阅清单」（R6-13），追加会破坏标记语义，故拒绝并给出治理改法提示。

- [ ] **Step 1: 失败单测（broadcast 拒订 + CAS 并发重试 + 查询）**

在 `EventRegistryServiceImplTest` 中：**先修正一条既有用例**（I1）——CAS 循环使 `update` 成为必经调用，
未打桩时 mock 默认返回 0 → 重试 3 次后抛异常使该用例失败：
- `registerSubscriberAppendsNewModuleToSubscriberList` 补打桩 `when(eventRegistryMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);`（`BaseMapper.update` 返回 **int 影响行数**，不是 boolean），既有 `verify(...).update(isNull(), captor.capture())` 与清单断言保留；其余既有用例（未登记拒 / DEPRECATED 拒 / 重复跳过）在 update 之前即返回，无需打桩；
- `setUp` 中 `new EventRegistryServiceImpl()` 改为 `new EventRegistryServiceImpl(IntegrationConverter.INSTANCE)`；

再追加四个新用例（既有 9 个 + 新增 4 个 = 13 个）：

```java
    @Test
    @DisplayName("broadcast 拒订守卫：订阅清单为 broadcast 标记行时抛异常（零订阅广播不承载订阅清单）")
    void registerSubscriberRejectsBroadcastMarkedRow() {
        EventRegistry broadcastRow = activeRow("broadcast");
        when(eventRegistryMapper.selectOne(any())).thenReturn(broadcastRow);

        assertThatThrownBy(() -> service.registerSubscriber("integration.convention.event-envelope", "it"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broadcast");
        verify(eventRegistryMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("并发守卫：CAS 影响 0 行时重读重算重试，第二次命中后写入含两模块的清单")
    void registerSubscriberRetriesOnCasMiss() {
        EventRegistry firstRead = activeRow("it");
        EventRegistry secondRead = activeRow("it,lab");
        when(eventRegistryMapper.selectOne(any())).thenReturn(firstRead, secondRead);
        // 首次 CAS 未命中（他实例已并发改写），第二次命中
        when(eventRegistryMapper.update(isNull(), any(Wrapper.class))).thenReturn(0, 1);

        service.registerSubscriber("system.dict.published", "pharmacy");

        ArgumentCaptor<Wrapper<EventRegistry>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(eventRegistryMapper, times(2)).update(isNull(), captor.capture());
        assertThat(((LambdaUpdateWrapper<EventRegistry>) captor.getAllValues().get(1))
                        .getParamNameValuePairs()
                        .values())
                .contains("it,lab,pharmacy");
    }

    @Test
    @DisplayName("并发守卫上界：CAS 连续 3 次未命中即 fail-fast（禁静默丢订阅）")
    void registerSubscriberFailsFastAfterCasAttemptsExhausted() {
        when(eventRegistryMapper.selectOne(any())).thenReturn(activeRow("it"));
        when(eventRegistryMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.registerSubscriber("system.dict.published", "pharmacy"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("并发");
        verify(eventRegistryMapper, times(3)).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("契约台账查询：0 基分页契约与订阅读数出参")
    void queryReturnsPagedRegistryRows() {
        EventRegistry row = activeRow("it,lab");
        when(eventRegistryMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenAnswer(invocation -> {
            IPage<EventRegistry> page = invocation.getArgument(0);
            page.setRecords(List.of(row));
            page.setTotal(1L);
            return page;
        });

        PageResult<EventRegistryVO> result = service.query(new EventRegistryQuery(null, "system", "ACTIVE", 0, 20));

        assertThat(result.page()).isZero();
        assertThat(result.content().get(0).subscriberModules()).isEqualTo("it,lab");
        assertThat(result.content().get(0).eventType()).isEqualTo("system.dict.published");
    }
```

（import 追加：`com.baomidou.mybatisplus.core.metadata.IPage`、`static org.mockito.Mockito.times`、`com.fuyun.common.web.PageResult`、`com.fuyun.integration.convert.IntegrationConverter`、`com.fuyun.integration.dto.EventRegistryQuery`、`com.fuyun.integration.vo.EventRegistryVO`、`java.util.List`（若未在）。）

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest=EventRegistryServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false | tail -8`
Expected: FAIL（broadcast 用例无异常、CAS 用例 `Wanted 2 times but was 1`、查询用例编译失败）

- [ ] **Step 2: 实现并发守卫与拒订 + 查询**

`IEventRegistryService` 追加：

```java
    /**
     * 分页查询事件契约台账（管理面只读：主题 × 订阅方 × 状态）。
     *
     * @param query 查询条件，非空；page 0 基、size 1-200
     * @return 分页出参（0 基页码），非空
     */
    PageResult<EventRegistryVO> query(EventRegistryQuery query);
```

`EventRegistryServiceImpl` 的 `registerSubscriber` 全文替换为（并新增字段/常量/构造器）：

```java
    /** 订阅清单 CAS 最大尝试次数：并发追加竞争窗口内的自旋上界（超限 fail-fast） */
    private static final int SUBSCRIBER_CAS_MAX_ATTEMPTS = 3;

    private final IntegrationConverter converter;

    /**
     * 全参构造器（装配归 MessagingGovernanceConfig @Import）。
     *
     * @param converter 治理域转换器，非空；来源：IntegrationWebConfig @Bean
     */
    public EventRegistryServiceImpl(IntegrationConverter converter) {
        this.converter = converter;
    }

    @Override
    @Transactional
    public void registerSubscriber(String eventType, String consumerModule) {
        for (int attempt = 1; attempt <= SUBSCRIBER_CAS_MAX_ATTEMPTS; attempt++) {
            EventRegistry registry = this.lambdaQuery()
                    .eq(EventRegistry::getEventType, eventType)
                    .select(EventRegistry::getId, EventRegistry::getStatus, EventRegistry::getSubscriberModules)
                    .one();
            // 事件先登记后订阅：未登记事件拒绝订阅，阻断消费队列声明（M20 治理约定）
            if (registry == null) {
                throw new IllegalStateException("事件类型 " + eventType + " 未在 event_registry 登记，禁止订阅（事件先登记后订阅）");
            }
            if (MessagingConstants.REGISTRY_STATUS_DEPRECATED.equals(registry.getStatus())) {
                throw new IllegalStateException("事件类型 " + eventType + " 已废止（DEPRECATED），禁止订阅");
            }
            String currentModules = registry.getSubscriberModules() == null ? "" : registry.getSubscriberModules();
            // broadcast 拒订守卫（W-6②）：零订阅广播标记行不承载订阅清单，追加会破坏 R6-13 语义
            if (MessagingConstants.SUBSCRIBER_BROADCAST.equals(currentModules.trim())) {
                throw new IllegalStateException("事件类型 " + eventType
                        + " 为零订阅广播标记行（subscriber_modules=broadcast），不承载订阅清单——"
                        + "如需订阅制治理，请由发布方先修正契约行后再订阅");
            }
            List<String> modules = new ArrayList<>();
            if (!currentModules.isBlank()) {
                modules.addAll(Arrays.asList(currentModules.split(SUBSCRIBER_SEPARATOR)));
            }
            // 重复订阅幂等：清单中已存在该模块时跳过，避免声明重放产生冗余写
            if (modules.contains(consumerModule)) {
                log.info("订阅模块 {} 已在事件 {} 的订阅清单中，幂等跳过", consumerModule, eventType);
                return;
            }
            modules.add(consumerModule);
            String merged = String.join(SUBSCRIBER_SEPARATOR, modules);
            // 并发守卫（W-6②）：条件更新以「读取到的旧清单」为 CAS 条件——多实例并发追加时
            // 后到者影响 0 行（丢更新防线），自旋重读重算；上界耗尽即 fail-fast 禁静默丢订阅
            boolean updated = this.lambdaUpdate()
                    .eq(EventRegistry::getId, registry.getId())
                    .eq(EventRegistry::getSubscriberModules, currentModules)
                    .set(EventRegistry::getSubscriberModules, merged)
                    .update();
            if (updated) {
                log.info("订阅登记完成：event_type={}，新增订阅模块={}，subscriber_modules={}", eventType, consumerModule, merged);
                return;
            }
            log.warn(
                    "订阅清单并发变更，重读重算重试：event_type={}，consumer_module={}，attempt={}",
                    eventType,
                    consumerModule,
                    attempt);
        }
        throw new IllegalStateException("事件类型 " + eventType + " 订阅登记并发竞争超过 " + SUBSCRIBER_CAS_MAX_ATTEMPTS
                + " 次重试，拒绝静默丢订阅（请重启装配进程重试）");
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<EventRegistryVO> query(EventRegistryQuery query) {
        LambdaQueryWrapper<EventRegistry> wrapper = Wrappers.lambdaQuery(EventRegistry.class)
                .eq(query.eventType() != null, EventRegistry::getEventType, query.eventType())
                .eq(query.producerModule() != null, EventRegistry::getProducerModule, query.producerModule())
                .eq(query.status() != null, EventRegistry::getStatus, query.status())
                // 排序唯一性约束（A.4.3-17）：类型名 + 主键
                .orderByAsc(EventRegistry::getEventType)
                .orderByAsc(EventRegistry::getId);
        Page<EventRegistry> page = this.page(new Page<>(query.page() + 1L, query.size()), wrapper);
        return PageResult.of(
                converter.toEventRegistryVOs(page.getRecords()),
                page.getCurrent() - 1,
                page.getSize(),
                page.getTotal());
    }
```

（import 追加：`com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper`、`com.baomidou.mybatisplus.core.toolkit.Wrappers`、`com.baomidou.mybatisplus.extension.plugins.pagination.Page`、`com.fuyun.common.web.PageResult`、`com.fuyun.integration.convert.IntegrationConverter`、`com.fuyun.integration.dto.EventRegistryQuery`、`com.fuyun.integration.vo.EventRegistryVO`。逻辑删由 @TableLogic 自动附加 `deleted=0`，查询无需显式条件。）

- [ ] **Step 3: 查询条件、出参、端点与转换器**

`dto/EventRegistryQuery.java`：

```java
package com.fuyun.integration.dto;

/**
 * 事件契约台账查询条件（GET /api/v1/integration/event-registry，A.7-1 参数对象化）。
 *
 * @param eventType      事件类型过滤，可空 = 不过滤
 * @param producerModule 生产模块域标识过滤，可空 = 不过滤
 * @param status         契约状态过滤（ACTIVE/DEPRECATED），可空 = 不过滤
 * @param page           页码（0 基，宪法 A.3-6），缺省 0
 * @param size           单页条数（1-200），缺省 20
 */
public record EventRegistryQuery(String eventType, String producerModule, String status, int page, int size) {}
```

`vo/EventRegistryVO.java`：

```java
package com.fuyun.integration.vo;

import java.time.OffsetDateTime;

/**
 * 事件契约台账行出参（GET /api/v1/integration/event-registry）：主题 × 订阅方 × 状态矩阵读面。
 *
 * @param id                契约行 ID（雪花 ID），非空；JSON 输出为字符串
 * @param eventType         事件类型 <模块>.<实体>.<动作>，非空
 * @param producerModule    生产模块域标识，非空
 * @param payloadDesc       载荷结构说明（冻结契约摘要），非空
 * @param subscriberModules 订阅模块清单（逗号分隔；broadcast=零订阅广播标记），非空
 * @param status            契约状态：ACTIVE 生效 / DEPRECATED 废止，非空
 * @param registeredAt      业务登记时间，非空
 */
public record EventRegistryVO(
        Long id,
        String eventType,
        String producerModule,
        String payloadDesc,
        String subscriberModules,
        String status,
        OffsetDateTime registeredAt) {}
```

`controller/EventRegistryController.java`：

```java
package com.fuyun.integration.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.EventRegistryQuery;
import com.fuyun.integration.service.IEventRegistryService;
import com.fuyun.integration.vo.EventRegistryVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件契约台账读端点（GET /api/v1/integration/event-registry，M20 §7）。
 *
 * <p>只读面：契约登记由治理构件（发布方装配）自动完成，本端点供管理台查询「事件 × 生产方 ×
 * 订阅方 × 状态」矩阵；POST/DELETE /event-registry 不在本 PR 范围（登记已自动化、废止动作
 * 无 P0 消费方，见计划范围声明）。受既有 /api/v1/** 认证拦截。
 */
@RestController
@RequestMapping("/api/v1/integration/event-registry")
@Validated
public class EventRegistryController {

    private final IEventRegistryService eventRegistryService;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import）。
     *
     * @param eventRegistryService 事件契约台账服务，非空；注入接口类型（B.2-2）
     */
    public EventRegistryController(IEventRegistryService eventRegistryService) {
        this.eventRegistryService = eventRegistryService;
    }

    /**
     * 分页查询契约台账（按事件类型/生产方/状态过滤，类型名升序）。
     *
     * @param eventType      事件类型过滤，可空 = 不过滤
     * @param producerModule 生产模块过滤，可空 = 不过滤
     * @param status         契约状态过滤（ACTIVE/DEPRECATED），可空 = 不过滤
     * @param page           页码（0 基），缺省 0
     * @param size           单页条数（1-200），缺省 20
     * @return 分页出参（0 基页码）
     */
    @GetMapping
    public PageResult<EventRegistryVO> list(
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "producerModule", required = false) String producerModule,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return eventRegistryService.query(new EventRegistryQuery(eventType, producerModule, status, page, size));
    }
}
```

`IntegrationConverter` 追加：

```java
    /**
     * 契约台账实体 → 行出参。
     *
     * @param entity 契约台账实体，非空
     * @return 行出参，非空
     */
    EventRegistryVO toEventRegistryVO(EventRegistry entity);

    /**
     * 契约台账实体清单 → 行出参清单。
     *
     * @param entities 实体清单，非空（可为空清单）
     * @return 行出参清单，非 null
     */
    List<EventRegistryVO> toEventRegistryVOs(List<EventRegistry> entities);
```

`IntegrationWebConfig` @Import 追加 `EventRegistryController.class`（服务 `EventRegistryServiceImpl` 已由 MessagingGovernanceConfig @Import，**不得重复注册**，否则 Bean 冲突）；`IntegrationQueryControllersTest` 追加 `@Mock IEventRegistryService` + 用例（断言 5 个参数装配与出参直返）。

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test | tail -10`
Expected: `BUILD SUCCESS`，`EventRegistryServiceImplTest` 共 13 用例全绿（9 既有 + 4 新增），`IntegrationQueryControllersTest` 3 用例全绿

- [ ] **Step 4: Commit**

```bash
git add backend/fuyun-integration/src/main/java/com/fuyun/integration/service/IEventRegistryService.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/service/impl/EventRegistryServiceImpl.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/dto/EventRegistryQuery.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/vo/EventRegistryVO.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/controller/EventRegistryController.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/convert/IntegrationConverter.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/config/IntegrationWebConfig.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/EventRegistryServiceImplTest.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/controller/IntegrationQueryControllersTest.java
git commit -m "fix(integration): 订阅登记并发 CAS 守卫与 broadcast 拒订并补契约台账查询（W-6②）"
```

### Task 9: FU-M20-04 主数据订阅登记（V502 迁移 + 服务 + 端点 + 跨模块前置登记）

**Files:**
- Modify: `CHANGELOG.md`（新增 PR-1b 条目：V502/V503 号段登记，先登记后落文件）
- Create: `backend/fuyun-integration/src/main/resources/db/migration/integration/V502__create_mdm_subscription.sql`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/constants/MdmConstants.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/entity/MdmSubscription.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/mapper/MdmSubscriptionMapper.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/dto/MdmSubscriptionCreateRequest.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/dto/MdmSubscriptionQuery.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/vo/MdmSubscriptionVO.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/service/IMdmSubscriptionService.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/service/impl/MdmSubscriptionServiceImpl.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/controller/MdmSubscriptionController.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/config/IntegrationMdmConfig.java`
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/convert/IntegrationConverter.java`
- Modify: `backend/fuyun-app/src/main/java/com/fuyun/app/config/IntegrationConfig.java`
- Modify: `TASK.md`（FU-M20-04 跨模块前置登记为 TODO 行）
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/MdmSubscriptionServiceImplTest.java`
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/controller/MdmSubscriptionControllerTest.java`

**Interfaces:**
- Consumes: `IntegrationErrorCode.MDM_TOPIC_UNKNOWN("INT-1012")` / `MDM_SUBSCRIPTION_NOT_FOUND("INT-1011")`（Task 2）、`IntegrationConverter`、`PageResult`、V1 公共触发器函数 `public.fuyun_set_updated_at()`（V1 迁移已建）。
- Produces:
  - 表 `integration.mdm_subscription`（topic / subscriber_module / sync_mode / last_version / last_sync_at / last_recon_at / recon_status + 审计列 + 逻辑删；`uk_mdm_subscription_topic_subscriber` 部分唯一索引）
  - `MdmConstants.TOPIC_DICT/ORG/USER/PARAM/PRACTICE`、`MdmConstants.TOPICS`（Set）、`SYNC_MODE_EVENT_SUBSCRIBE="EVENT_SUBSCRIBE"`、`SYNC_MODE_API_PULL="API_PULL"`、`RECON_STATUS_PENDING="PENDING"`（Task 10 追加事件类型与分发模式常量）
  - `IMdmSubscriptionService.query/register/unregister/listSubscriberModules`（`listSubscriberModules(String topic) : List<String>` 供 Task 10 分发消费者使用）
  - `MdmSubscriptionVO(Long id, String topic, String subscriberModule, String syncMode, Long lastVersion, OffsetDateTime lastSyncAt, OffsetDateTime lastReconAt, String reconStatus)`
  - HTTP：`GET /api/v1/integration/mdm-subscriptions`（矩阵）、`POST /api/v1/integration/mdm-subscriptions`、`DELETE /api/v1/integration/mdm-subscriptions/{id}`（204）

**范围与口径（写死）：**
- **矩阵语义**：Spec §6「管理界面展示『主题 × 订阅方 × 版本 × 对账状态』矩阵」= `GET /mdm-subscriptions` 列表本身（每行即矩阵一格），不另设聚合端点。
- **对账状态初值**：登记写 `PENDING`（待对账）。`CONSISTENT` / `LAGGING` 等取值随对账任务（依赖 M01 版本化回源）引入——本 PR **不预置未使用常量**（死代码零容忍），在 `MdmConstants` javadoc 中记录演进点。
- **全量初始化 / 每日对账 / 自动全量重发 / `POST /mdm/redispatch` 不在本 PR**：依赖 M01 版本化回源接口（org/user/param/practice 无版本化读接口，侦察报告 §Gap 实证）。**本任务末步以 TASK.md TODO 行 + PR 描述双层登记该跨模块前置**（禁止落空动作端点）。
- **迁移纪律**：V502 版本号 > 501（PR-1a 实证的乱序守则）；先 CHANGELOG 登记号段再落文件。

- [ ] **Step 1: CHANGELOG 登记号段（先登记先占）**

在 `CHANGELOG.md` 头部说明块之后、`## 2026-09-15 · PR-1a 收尾 …` 条目之前插入：

```markdown
## 2026-09-15 · P1 PR-1b M20 事件总线治理完整化：号段登记与实施落盘（先记再改）

- **号段登记（V500 起「先登记先占」，登记载体 = 本文件）**：本次占用 **V502**（`integration.mdm_subscription` 主数据分发订阅台账）、**V503**（`integration.mdm_dispatch_log` 主数据分发流水）；两者均在 integration 号段（V1-V99 与 V500+ 通用段）内，且版本号大于真库历史最大值 V501（Flyway `outOfOrder=false` 硬约束，PR-1a 真栈实证）。
- **落盘依据**：M20 Spec §4 两张治理表（mdm_subscription / mdm_dispatch_log）+ FU-M20-04 主数据分发（订阅登记、广播链路分发流水、矩阵查询）。
- **跨模块前置（登记）**：FU-M20-04 的全量初始化、每日版本对账、落后自动全量重发与 `POST /mdm/redispatch` 端点依赖 M01 版本化回源/重发接口（当前仅字典有版本化读接口），未随本次交付，登记 TASK.md 待办与 PR 描述。
- **CI 联动**：新增 `scripts/check-migration-governance.py`（号段归属 + 版本唯一 + 乱序守卫）随 PR-1b 落盘，本批两条迁移为其守护对象（TASK.md W-4 回填依据）。
```

- [ ] **Step 2: V502 迁移**

`backend/fuyun-integration/src/main/resources/db/migration/integration/V502__create_mdm_subscription.sql`：

```sql
-- V502：主数据分发订阅台账（M20 Spec §4 mdm_subscription，FU-M20-04 分发关系与对账依据）
-- 号段登记：V500 起「先登记先占」（CHANGELOG 2026-09-08 条目）；V502 于 CHANGELOG 2026-09-15 号段登记条目后落盘，
-- 版本号大于真库历史最大 V501（Flyway outOfOrder=false 拒绝低位新迁移，PR-1a 真栈实证）。
-- 字段全集 = Spec §4 原文字段（topic/subscriber_module/sync_mode/last_version/last_sync_at/last_recon_at/recon_status）
-- 随表落盘防后续 ALTER（V3/V4 同口径）；审计列与逻辑删为项目跨切约定（A.4.2-9 + V2 同型）。
-- updated_at 由 V1 公共触发器函数维护：订阅登记后的对账写路径（last_* / recon_status）产生 UPDATE 生命周期。
CREATE TABLE integration.mdm_subscription (
    id                BIGINT        PRIMARY KEY,
    topic             VARCHAR(32)   NOT NULL,                     -- 主数据主题：dict/org/user/param/practice（M20 §4）
    subscriber_module VARCHAR(32)   NOT NULL,                     -- 订阅方模块域标识（如 system/patient）
    sync_mode         VARCHAR(16)   NOT NULL,                     -- 同步方式：EVENT_SUBSCRIBE 事件订阅 / API_PULL 接口拉取
    last_version      BIGINT,                                     -- 订阅方已同步到的版本号（dict 事件载荷 version 口径）
    last_sync_at      TIMESTAMPTZ,                                -- 最近一次同步时刻（对账写路径，P0 留空）
    last_recon_at     TIMESTAMPTZ,                                -- 最近一次对账时刻（对账任务写路径，P0 留空）
    recon_status      VARCHAR(16)   NOT NULL DEFAULT 'PENDING',   -- 对账状态：PENDING 待对账（登记初值）
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted           SMALLINT      NOT NULL DEFAULT 0
);

-- 订阅关系业务唯一：同主题同订阅方仅一行（逻辑删行不占用唯一性，V2 同口径）
CREATE UNIQUE INDEX uk_mdm_subscription_topic_subscriber
    ON integration.mdm_subscription (topic, subscriber_module) WHERE deleted = 0;

-- updated_at 触发器：复用 V1 公共函数，应用层禁止写入该列（backend 宪法 A.4.2-9）
CREATE TRIGGER trg_mdm_subscription_updated_at BEFORE UPDATE ON integration.mdm_subscription
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
```

- [ ] **Step 3: 迁移治理脚本自检（新迁移即受守护）**

Run: `python scripts/check-migration-governance.py`
Expected: `迁移治理校验通过：16 个迁移文件，基线 HEAD`（V502 已入号段与唯一性校验面）

- [ ] **Step 4: 常量、实体、mapper**

`constants/MdmConstants.java`：

```java
package com.fuyun.integration.constants;

import java.util.Set;

/**
 * 主数据分发治理常量（M20 Spec §4 mdm_subscription/mdm_dispatch_log 词表与 FU-M20-04 口径）。
 *
 * <p>主题取值与 Spec 原文字段说明一一对应（`topic(dict/org/user/param/practice)`）；同步方式取
 * 事件订阅/接口拉取两值（`sync_mode(事件订阅/接口拉取)`）。写路径的取值校验以此为准（禁字符串散落）。
 *
 * <p>演进点（不在本 PR）：对账状态在 PENDING 之外还需 CONSISTENT/LAGGING 等取值，随「每日版本对账 +
 * 落后自动全量重发」任务（依赖 M01 版本化回源接口）一并引入——本 PR 不预置未使用常量（死代码零容忍）。
 */
public final class MdmConstants {

    /** 主题：字典（M01 dict） */
    public static final String TOPIC_DICT = "dict";

    /** 主题：组织机构（M01 org） */
    public static final String TOPIC_ORG = "org";

    /** 主题：人员（M01 user） */
    public static final String TOPIC_USER = "user";

    /** 主题：系统参数（M01 param） */
    public static final String TOPIC_PARAM = "param";

    /** 主题：执业授权（M01 practice） */
    public static final String TOPIC_PRACTICE = "practice";

    /** 合法主题全集：登记入参校验与查询过滤的唯一依据 */
    public static final Set<String> TOPICS = Set.of(TOPIC_DICT, TOPIC_ORG, TOPIC_USER, TOPIC_PARAM, TOPIC_PRACTICE);

    /** 同步方式：事件订阅（fy.topic 广播链路，本模块记分发流水） */
    public static final String SYNC_MODE_EVENT_SUBSCRIBE = "EVENT_SUBSCRIBE";

    /** 同步方式：接口拉取（订阅方经 M01 回源接口按版本拉取） */
    public static final String SYNC_MODE_API_PULL = "API_PULL";

    /** 对账状态：待对账（登记初值；对账任务引入后追加其余取值） */
    public static final String RECON_STATUS_PENDING = "PENDING";

    /**
     * 私有构造器：常量类禁止实例化（backend 宪法 A.2-6）。
     */
    private MdmConstants() {}
}
```

`entity/MdmSubscription.java`：

```java
package com.fuyun.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 主数据分发订阅台账实体（integration.mdm_subscription）：M20 分发关系与对账依据（M20 §4）。
 *
 * <p>写入语义：订阅方登记（register）落行，recon_status 初值 PENDING；注销置逻辑删（deleted=1）。
 * last_version/last_sync_at/last_recon_at/recon_status 的写路径属对账任务（依赖 M01 版本化回源
 * 接口，未在本 PR 交付）——本实体保留字段以承载 Spec 字段全集（V502 随表落盘），零应用层写路径。
 *
 * <p>updated_at 由数据库触发器维护（V1 公共函数）；deleted 为逻辑删标记（唯一索引仅在 deleted=0 上生效）。
 */
@Getter
@Setter
@TableName("integration.mdm_subscription")
public class MdmSubscription {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 主数据主题：dict/org/user/param/practice（MdmConstants.TOPICS 校验） */
    private String topic;

    /** 订阅方模块域标识（如 system/patient） */
    private String subscriberModule;

    /** 同步方式：EVENT_SUBSCRIBE 事件订阅 / API_PULL 接口拉取 */
    private String syncMode;

    /** 订阅方已同步到的版本号（可空；对账任务写路径） */
    private Long lastVersion;

    /** 最近一次同步时刻（可空；对账任务写路径） */
    private OffsetDateTime lastSyncAt;

    /** 最近一次对账时刻（可空；对账任务写路径） */
    private OffsetDateTime lastReconAt;

    /** 对账状态：PENDING 待对账（登记初值） */
    private String reconStatus;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护，应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：治理台账默认 'system'（数据库默认值） */
    private String createdBy;

    /** 更新人：治理台账默认 'system'（数据库默认值） */
    private String updatedBy;

    /** 逻辑删除标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
```

`mapper/MdmSubscriptionMapper.java`：

```java
package com.fuyun.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.integration.entity.MdmSubscription;
import org.apache.ibatis.annotations.Mapper;

/**
 * 主数据订阅台账 mapper：单表 CRUD 经 BaseMapper 内置能力（无 XML，宪法 A.4.3-15）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface MdmSubscriptionMapper extends BaseMapper<MdmSubscription> {}
```

- [ ] **Step 5: DTO/VO/服务契约与失败单测**

`dto/MdmSubscriptionCreateRequest.java`：

```java
package com.fuyun.integration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 主数据订阅登记请求（POST /api/v1/integration/mdm-subscriptions）。
 *
 * @param topic            主数据主题，非空；取值 dict/org/user/param/practice（服务层按 MdmConstants.TOPICS 复校）
 * @param subscriberModule 订阅方模块域标识，非空且小写（模块名规则与队列命名一致）
 * @param syncMode         同步方式，非空；EVENT_SUBSCRIBE 事件订阅 / API_PULL 接口拉取
 */
public record MdmSubscriptionCreateRequest(
        @NotBlank @Pattern(regexp = "dict|org|user|param|practice", message = "主题仅支持 dict/org/user/param/practice")
        String topic,

        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]*$", message = "订阅方模块标识须为小写字母开头")
        String subscriberModule,

        @NotBlank @Pattern(regexp = "EVENT_SUBSCRIBE|API_PULL", message = "同步方式仅支持 EVENT_SUBSCRIBE 或 API_PULL")
        String syncMode) {}
```

`dto/MdmSubscriptionQuery.java`：

```java
package com.fuyun.integration.dto;

/**
 * 主数据订阅矩阵查询条件（GET /api/v1/integration/mdm-subscriptions，A.7-1 参数对象化）。
 *
 * @param topic            主题过滤，可空 = 不过滤
 * @param subscriberModule 订阅方过滤，可空 = 不过滤
 * @param page             页码（0 基），缺省 0
 * @param size             单页条数（1-200），缺省 20
 */
public record MdmSubscriptionQuery(String topic, String subscriberModule, int page, int size) {}
```

`vo/MdmSubscriptionVO.java`：

```java
package com.fuyun.integration.vo;

import java.time.OffsetDateTime;

/**
 * 主数据订阅矩阵行出参（GET /api/v1/integration/mdm-subscriptions）：主题 × 订阅方 × 版本 × 对账状态。
 *
 * @param id               订阅记录 ID（雪花 ID），非空；JSON 输出为字符串
 * @param topic            主数据主题，非空
 * @param subscriberModule 订阅方模块域标识，非空
 * @param syncMode         同步方式，非空
 * @param lastVersion      订阅方已同步版本号，可空（尚未对账/从未同步）
 * @param lastSyncAt       最近同步时刻，可空
 * @param lastReconAt      最近对账时刻，可空
 * @param reconStatus      对账状态（PENDING 待对账），非空
 */
public record MdmSubscriptionVO(
        Long id,
        String topic,
        String subscriberModule,
        String syncMode,
        Long lastVersion,
        OffsetDateTime lastSyncAt,
        OffsetDateTime lastReconAt,
        String reconStatus) {}
```

`service/IMdmSubscriptionService.java`：

```java
package com.fuyun.integration.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.MdmSubscriptionCreateRequest;
import com.fuyun.integration.dto.MdmSubscriptionQuery;
import com.fuyun.integration.entity.MdmSubscription;
import com.fuyun.integration.vo.MdmSubscriptionVO;
import java.util.List;

/**
 * 主数据分发订阅服务：订阅登记 / 注销 / 矩阵查询 / 订阅方清单（M20 FU-M20-04）。
 *
 * <p>边界（M20 红线 3）：本服务只做分发治理（谁订了什么、订到什么版本），不存任何主数据业务值，
 * 也不修改主数据本身（权威源唯一 = M01）。
 */
public interface IMdmSubscriptionService extends IService<MdmSubscription> {

    /**
     * 分页查询订阅矩阵（主题 × 订阅方 × 版本 × 对账状态）。
     *
     * @param query 查询条件，非空；page 0 基、size 1-200
     * @return 分页出参（0 基页码），非空
     */
    PageResult<MdmSubscriptionVO> query(MdmSubscriptionQuery query);

    /**
     * 登记订阅关系（(topic, subscriber_module) 幂等：已存在时返回既有行不覆盖）。
     *
     * @param request 登记请求，非空；topic 须在 MdmConstants.TOPICS 内
     * @return 登记后的订阅出参，非空
     * @throws com.fuyun.common.exception.BizException 未知主题（INT-1012，400）时触发
     */
    MdmSubscriptionVO register(MdmSubscriptionCreateRequest request);

    /**
     * 注销订阅关系（逻辑删 deleted=1）。
     *
     * @param id 订阅记录 ID，非空
     * @throws com.fuyun.common.exception.BizException 记录不存在（INT-1011，404）时触发
     */
    void unregister(Long id);

    /**
     * 取指定主题的订阅方模块清单（分发流水登记的 target_modules 数据源）。
     *
     * @param topic 主数据主题，非空；取值见 MdmConstants.TOPICS
     * @return 订阅方模块标识清单，非 null；无订阅方时为空清单（广播事件仍记流水）
     */
    List<String> listSubscriberModules(String topic);
}
```

`service/impl/MdmSubscriptionServiceImpl.java`：

```java
package com.fuyun.integration.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.api.IntegrationErrorCode;
import com.fuyun.integration.constants.MdmConstants;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.MdmSubscriptionCreateRequest;
import com.fuyun.integration.dto.MdmSubscriptionQuery;
import com.fuyun.integration.entity.MdmSubscription;
import com.fuyun.integration.mapper.MdmSubscriptionMapper;
import com.fuyun.integration.service.IMdmSubscriptionService;
import com.fuyun.integration.vo.MdmSubscriptionVO;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主数据订阅服务实现：mdm_subscription 的唯一业务写入口（FU-M20-04 分发关系台账）。
 *
 * <p>写语义：登记幂等（同主题同订阅方已存在时 warn 跳过，不覆盖既有对账进度）；并发首登记由
 * uk_mdm_subscription_topic_subscriber 兜底（冲突后回读既有行返回，与 EventRegistryServiceImpl
 * 同款幂等姿态）；注销走 @TableLogic 逻辑删。对账类列（last_* / recon_status）本 PR 只写登记初值。
 *
 * <p>归 service/impl 包 = JaCoCo 核心包 PACKAGE LINE 1.00 覆盖对象。
 */
@Slf4j
public class MdmSubscriptionServiceImpl extends ServiceImpl<MdmSubscriptionMapper, MdmSubscription>
        implements IMdmSubscriptionService {

    private final IntegrationConverter converter;

    /**
     * 全参构造器（装配归 IntegrationMdmConfig @Import）。
     *
     * @param converter 治理域转换器，非空；来源：IntegrationWebConfig @Bean
     */
    public MdmSubscriptionServiceImpl(IntegrationConverter converter) {
        this.converter = converter;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<MdmSubscriptionVO> query(MdmSubscriptionQuery query) {
        LambdaQueryWrapper<MdmSubscription> wrapper = Wrappers.lambdaQuery(MdmSubscription.class)
                .eq(query.topic() != null, MdmSubscription::getTopic, query.topic())
                .eq(
                        query.subscriberModule() != null,
                        MdmSubscription::getSubscriberModule,
                        query.subscriberModule())
                // 排序唯一性约束（A.4.3-17）：主题 + 主键（矩阵阅读顺序）
                .orderByAsc(MdmSubscription::getTopic)
                .orderByAsc(MdmSubscription::getId);
        Page<MdmSubscription> page = this.page(new Page<>(query.page() + 1L, query.size()), wrapper);
        return PageResult.of(
                converter.toMdmSubscriptionVOs(page.getRecords()),
                page.getCurrent() - 1,
                page.getSize(),
                page.getTotal());
    }

    @Override
    @Transactional
    public MdmSubscriptionVO register(MdmSubscriptionCreateRequest request) {
        if (!MdmConstants.TOPICS.contains(request.topic())) {
            throw new BizException(
                    IntegrationErrorCode.MDM_TOPIC_UNKNOWN,
                    HttpStatus.BAD_REQUEST,
                    "未知主数据主题：" + request.topic() + "（合法取值 " + MdmConstants.TOPICS + "）");
        }
        MdmSubscription existing = this.lambdaQuery()
                .eq(MdmSubscription::getTopic, request.topic())
                .eq(MdmSubscription::getSubscriberModule, request.subscriberModule())
                .one();
        // 幂等登记：已登记的订阅关系不覆盖（对账进度属既有事实，静默改写会让对账结论失真）
        if (existing != null) {
            log.warn(
                    "主数据订阅关系已存在，幂等跳过不覆盖：topic={}，subscriber_module={}",
                    request.topic(),
                    request.subscriberModule());
            return converter.toMdmSubscriptionVO(existing);
        }
        MdmSubscription entity = new MdmSubscription();
        entity.setTopic(request.topic());
        entity.setSubscriberModule(request.subscriberModule());
        entity.setSyncMode(request.syncMode());
        // 对账状态登记初值 PENDING（待对账）；对账任务的其余取值随 M01 版本化回源接口引入
        entity.setReconStatus(MdmConstants.RECON_STATUS_PENDING);
        try {
            this.save(entity);
        } catch (DuplicateKeyException e) {
            // 并发首登记竞态：唯一索引为最终保证，冲突后回读既有行返回（幂等语义与前置查询一致）
            log.warn(
                    "主数据订阅并发登记命中唯一索引，幂等跳过不覆盖：topic={}，subscriber_module={}",
                    request.topic(),
                    request.subscriberModule());
            MdmSubscription row = this.lambdaQuery()
                    .eq(MdmSubscription::getTopic, request.topic())
                    .eq(MdmSubscription::getSubscriberModule, request.subscriberModule())
                    .one();
            return converter.toMdmSubscriptionVO(row);
        }
        log.info(
                "主数据订阅登记完成：topic={}，subscriber_module={}，sync_mode={}",
                request.topic(),
                request.subscriberModule(),
                request.syncMode());
        return converter.toMdmSubscriptionVO(entity);
    }

    @Override
    @Transactional
    public void unregister(Long id) {
        if (!this.removeById(id)) {
            throw new BizException(
                    IntegrationErrorCode.MDM_SUBSCRIPTION_NOT_FOUND, HttpStatus.NOT_FOUND, "主数据订阅不存在：id=" + id);
        }
        log.info("主数据订阅注销完成（逻辑删）：id={}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listSubscriberModules(String topic) {
        return this.list(Wrappers.lambdaQuery(MdmSubscription.class)
                        .eq(MdmSubscription::getTopic, topic)
                        .select(MdmSubscription::getSubscriberModule)
                        .orderByAsc(MdmSubscription::getId))
                .stream()
                .map(MdmSubscription::getSubscriberModule)
                .toList();
    }
}
```

`MdmSubscriptionServiceImplTest`（先失败后通过）：

```java
package com.fuyun.integration.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.api.IntegrationErrorCode;
import com.fuyun.integration.constants.MdmConstants;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.MdmSubscriptionCreateRequest;
import com.fuyun.integration.dto.MdmSubscriptionQuery;
import com.fuyun.integration.entity.MdmSubscription;
import com.fuyun.integration.mapper.MdmSubscriptionMapper;
import com.fuyun.integration.vo.MdmSubscriptionVO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 主数据订阅服务单测：登记幂等（不覆盖既有对账进度）、未知主题拒绝、并发冲突回读、
 * 注销不存在语义、初值 PENDING 与分发目标清单读取。
 */
@ExtendWith(MockitoExtension.class)
class MdmSubscriptionServiceImplTest {

    @Mock
    private MdmSubscriptionMapper mdmSubscriptionMapper;

    private MdmSubscriptionServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MdmSubscription.class);
    }

    @BeforeEach
    void setUp() {
        service = new MdmSubscriptionServiceImpl(IntegrationConverter.INSTANCE);
        ReflectionTestUtils.setField(service, "baseMapper", mdmSubscriptionMapper);
        ReflectionTestUtils.setField(service, "entityClass", MdmSubscription.class);
    }

    @Test
    @DisplayName("登记新订阅：落行含 PENDING 对账初值与同步方式，出参回填登记值")
    void registerInsertsRowWithPendingReconStatus() {
        when(mdmSubscriptionMapper.selectOne(any())).thenReturn(null);
        when(mdmSubscriptionMapper.insert(any(MdmSubscription.class))).thenReturn(1);

        MdmSubscriptionVO vo = service.register(new MdmSubscriptionCreateRequest(
                MdmConstants.TOPIC_DICT, "patient", MdmConstants.SYNC_MODE_EVENT_SUBSCRIBE));

        ArgumentCaptor<MdmSubscription> captor = ArgumentCaptor.forClass(MdmSubscription.class);
        verify(mdmSubscriptionMapper).insert(captor.capture());
        assertThat(captor.getValue().getReconStatus()).isEqualTo(MdmConstants.RECON_STATUS_PENDING);
        assertThat(captor.getValue().getTopic()).isEqualTo(MdmConstants.TOPIC_DICT);
        assertThat(vo.subscriberModule()).isEqualTo("patient");
        assertThat(vo.reconStatus()).isEqualTo(MdmConstants.RECON_STATUS_PENDING);
    }

    @Test
    @DisplayName("重复登记：幂等跳过且不插入（不覆盖既有对账进度）")
    void registerSkipsExistingSubscription() {
        MdmSubscription existing = new MdmSubscription();
        existing.setId(1L);
        existing.setTopic(MdmConstants.TOPIC_DICT);
        existing.setSubscriberModule("patient");
        existing.setSyncMode(MdmConstants.SYNC_MODE_EVENT_SUBSCRIBE);
        existing.setReconStatus(MdmConstants.RECON_STATUS_PENDING);
        when(mdmSubscriptionMapper.selectOne(any())).thenReturn(existing);

        MdmSubscriptionVO vo = service.register(new MdmSubscriptionCreateRequest(
                MdmConstants.TOPIC_DICT, "patient", MdmConstants.SYNC_MODE_EVENT_SUBSCRIBE));

        assertThat(vo.id()).isEqualTo(1L);
        verify(mdmSubscriptionMapper, never()).insert(any(MdmSubscription.class));
    }

    @Test
    @DisplayName("未知主题：抛 400 业务异常（INT-1012），不触达落库")
    void registerRejectsUnknownTopic() {
        assertThatThrownBy(() -> service.register(
                        new MdmSubscriptionCreateRequest("unknown-topic", "patient", MdmConstants.SYNC_MODE_API_PULL)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.MDM_TOPIC_UNKNOWN);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(mdmSubscriptionMapper, never()).insert(any(MdmSubscription.class));
    }

    @Test
    @DisplayName("并发首登记：唯一索引冲突后回读既有行返回（幂等语义）")
    void registerFallsBackToExistingRowOnConcurrentInsert() {
        MdmSubscription existing = new MdmSubscription();
        existing.setId(2L);
        existing.setTopic(MdmConstants.TOPIC_ORG);
        existing.setSubscriberModule("nursing");
        existing.setSyncMode(MdmConstants.SYNC_MODE_EVENT_SUBSCRIBE);
        existing.setReconStatus(MdmConstants.RECON_STATUS_PENDING);
        when(mdmSubscriptionMapper.selectOne(any())).thenReturn(null, existing);
        when(mdmSubscriptionMapper.insert(any(MdmSubscription.class)))
                .thenThrow(new DuplicateKeyException("uk_mdm_subscription_topic_subscriber"));

        MdmSubscriptionVO vo = service.register(new MdmSubscriptionCreateRequest(
                MdmConstants.TOPIC_ORG, "nursing", MdmConstants.SYNC_MODE_EVENT_SUBSCRIBE));

        assertThat(vo.id()).isEqualTo(2L);
    }

    @Test
    @DisplayName("注销不存在：抛 404 业务异常（INT-1011）")
    void unregisterRejectsMissingRow() {
        when(mdmSubscriptionMapper.deleteById(9L)).thenReturn(0);

        assertThatThrownBy(() -> service.unregister(9L))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.MDM_SUBSCRIPTION_NOT_FOUND);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("订阅方清单读取：按主题返回模块标识清单（分发流水 target_modules 数据源）")
    void listSubscriberModulesReturnsTargets() {
        MdmSubscription first = new MdmSubscription();
        first.setSubscriberModule("lab");
        MdmSubscription second = new MdmSubscription();
        second.setSubscriberModule("pharmacy");
        when(mdmSubscriptionMapper.selectList(any())).thenReturn(List.of(first, second));

        assertThat(service.listSubscriberModules(MdmConstants.TOPIC_DICT)).containsExactly("lab", "pharmacy");
    }

    @Test
    @DisplayName("矩阵查询：0 基分页契约（请求与出参同口径）且矩阵行字段完整映射")
    void queryKeepsZeroBasedContractAndMapsMatrixRow() {
        MdmSubscription row = new MdmSubscription();
        row.setId(5L);
        row.setTopic(MdmConstants.TOPIC_DICT);
        row.setSubscriberModule("patient");
        row.setSyncMode(MdmConstants.SYNC_MODE_EVENT_SUBSCRIBE);
        row.setReconStatus(MdmConstants.RECON_STATUS_PENDING);
        when(mdmSubscriptionMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenAnswer(invocation -> {
            IPage<MdmSubscription> page = invocation.getArgument(0);
            page.setRecords(List.of(row));
            page.setTotal(1L);
            return page;
        });

        PageResult<MdmSubscriptionVO> result =
                service.query(new MdmSubscriptionQuery(MdmConstants.TOPIC_DICT, "patient", 0, 20));

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20L);
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.content()).hasSize(1);
        MdmSubscriptionVO vo = result.content().get(0);
        assertThat(vo.topic()).isEqualTo(MdmConstants.TOPIC_DICT);
        assertThat(vo.subscriberModule()).isEqualTo("patient");
        assertThat(vo.reconStatus()).isEqualTo(MdmConstants.RECON_STATUS_PENDING);
        ArgumentCaptor<IPage<MdmSubscription>> pageCaptor = ArgumentCaptor.forClass(IPage.class);
        verify(mdmSubscriptionMapper).selectPage(pageCaptor.capture(), any(Wrapper.class));
        // MP 分页器以 1 基接收（契约 0 基 → 内部 1 基转换，服务层唯一转换点）
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1L);
    }
}
```

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest=MdmSubscriptionServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false | tail -6`
Expected: 首跑 COMPILATION ERROR → 落 Step 5 实现后 `Tests run: 7, Failures: 0`

- [ ] **Step 6: 端点、转换器方法与装配**

`controller/MdmSubscriptionController.java`：

```java
package com.fuyun.integration.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.MdmSubscriptionCreateRequest;
import com.fuyun.integration.dto.MdmSubscriptionQuery;
import com.fuyun.integration.service.IMdmSubscriptionService;
import com.fuyun.integration.vo.MdmSubscriptionVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主数据订阅治理端点（/api/v1/integration/mdm-subscriptions，M20 §7 + FU-M20-04）。
 *
 * <p>矩阵语义：GET 列表即「主题 × 订阅方 × 版本 × 对账状态」矩阵（Spec §6 管理界面口径）。
 * 受既有 /api/v1/** 认证拦截；职责边界：仅校验 + 编排（宪法 B.1），禁业务逻辑与事务。
 */
@RestController
@RequestMapping("/api/v1/integration/mdm-subscriptions")
@Validated
public class MdmSubscriptionController {

    private final IMdmSubscriptionService mdmSubscriptionService;

    /**
     * 全参构造器（装配归 IntegrationMdmConfig @Import）。
     *
     * @param mdmSubscriptionService 主数据订阅服务，非空；注入接口类型（B.2-2）
     */
    public MdmSubscriptionController(IMdmSubscriptionService mdmSubscriptionService) {
        this.mdmSubscriptionService = mdmSubscriptionService;
    }

    /**
     * 分页查询订阅矩阵。
     *
     * @param topic            主题过滤，可空 = 不过滤
     * @param subscriberModule 订阅方过滤，可空 = 不过滤
     * @param page             页码（0 基），缺省 0
     * @param size             单页条数（1-200），缺省 20
     * @return 分页出参（0 基页码）
     */
    @GetMapping
    public PageResult<MdmSubscriptionVO> list(
            @RequestParam(value = "topic", required = false) String topic,
            @RequestParam(value = "subscriberModule", required = false) String subscriberModule,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return mdmSubscriptionService.query(new MdmSubscriptionQuery(topic, subscriberModule, page, size));
    }

    /**
     * 登记订阅关系（(topic, subscriber_module) 幂等）。
     *
     * @param request 登记请求，非空；JSR-303 校验失败渲染 400
     * @return 登记后的订阅出参
     */
    @PostMapping
    public MdmSubscriptionVO register(@Valid @RequestBody MdmSubscriptionCreateRequest request) {
        return mdmSubscriptionService.register(request);
    }

    /**
     * 注销订阅关系（逻辑删）。
     *
     * @param id 订阅记录 ID（路径参数），非空
     * @return 204 无响应体；不存在时由全局渲染器输出 404 ProblemDetail（INT-1011）
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> unregister(@PathVariable("id") Long id) {
        mdmSubscriptionService.unregister(id);
        return ResponseEntity.noContent().build();
    }
}
```

`IntegrationConverter` 追加：

```java
    /**
     * 主数据订阅实体 → 矩阵行出参。
     *
     * @param entity 订阅实体，非空
     * @return 矩阵行出参，非空
     */
    MdmSubscriptionVO toMdmSubscriptionVO(MdmSubscription entity);

    /**
     * 主数据订阅实体清单 → 矩阵行出参清单。
     *
     * @param entities 实体清单，非空（可为空清单）
     * @return 矩阵行出参清单，非 null
     */
    List<MdmSubscriptionVO> toMdmSubscriptionVOs(List<MdmSubscription> entities);
```

`config/IntegrationMdmConfig.java`（本任务只装配服务与控制器；Task 10 追加消费者与队列声明）：

```java
package com.fuyun.integration.config;

import com.fuyun.integration.controller.MdmSubscriptionController;
import com.fuyun.integration.service.impl.MdmSubscriptionServiceImpl;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M20 主数据分发治理装配：订阅台账服务与端点的集中注册点（backend 宪法 B.1 装配归 app）。
 *
 * <p>本类由 fuyun-app IntegrationConfig @Import 生效；主数据事件消费侧（分发流水登记）与消费队列
 * 声明随 FU-M20-04 分发链路一并装配（同一配置类内聚，见类后续扩展）。
 */
@Configuration
@Import({MdmSubscriptionServiceImpl.class, MdmSubscriptionController.class})
public class IntegrationMdmConfig {}
```

`backend/fuyun-app/.../IntegrationConfig.java` 的 @Import 改为 `{IntegrationWebConfig.class, IntegrationMdmConfig.class}`，import 段补 `com.fuyun.integration.config.IntegrationMdmConfig`。

`controller/MdmSubscriptionControllerTest.java`：

```java
package com.fuyun.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.constants.MdmConstants;
import com.fuyun.integration.dto.MdmSubscriptionCreateRequest;
import com.fuyun.integration.dto.MdmSubscriptionQuery;
import com.fuyun.integration.service.IMdmSubscriptionService;
import com.fuyun.integration.vo.MdmSubscriptionVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * 主数据订阅端点薄层单测：参数装配、请求透传与 204 注销编排（业务逻辑归服务单测）。
 */
@ExtendWith(MockitoExtension.class)
class MdmSubscriptionControllerTest {

    @Mock
    private IMdmSubscriptionService mdmSubscriptionService;

    @Captor
    private ArgumentCaptor<MdmSubscriptionQuery> queryCaptor;

    private MdmSubscriptionController controller;

    @BeforeEach
    void setUp() {
        controller = new MdmSubscriptionController(mdmSubscriptionService);
    }

    @Test
    @DisplayName("矩阵查询端点：主题/订阅方/分页参数装配为查询对象，服务出参直返")
    void listDelegatesQueryParameters() {
        PageResult<MdmSubscriptionVO> expected = PageResult.of(List.of(), 0L, 20L, 0L);
        when(mdmSubscriptionService.query(any())).thenReturn(expected);

        assertThat(controller.list(MdmConstants.TOPIC_DICT, "patient", 0, 20)).isSameAs(expected);
        verify(mdmSubscriptionService).query(queryCaptor.capture());
        assertThat(queryCaptor.getValue().topic()).isEqualTo(MdmConstants.TOPIC_DICT);
        assertThat(queryCaptor.getValue().subscriberModule()).isEqualTo("patient");
    }

    @Test
    @DisplayName("登记端点：请求对象原样透传服务层")
    void registerDelegatesRequest() {
        MdmSubscriptionCreateRequest request = new MdmSubscriptionCreateRequest(
                MdmConstants.TOPIC_DICT, "patient", MdmConstants.SYNC_MODE_API_PULL);
        MdmSubscriptionVO expected = new MdmSubscriptionVO(
                7L, MdmConstants.TOPIC_DICT, "patient", MdmConstants.SYNC_MODE_API_PULL, null, null, null,
                MdmConstants.RECON_STATUS_PENDING);
        when(mdmSubscriptionService.register(request)).thenReturn(expected);

        assertThat(controller.register(request)).isSameAs(expected);
    }

    @Test
    @DisplayName("注销端点：路径 id 透传服务层并编排 204 无响应体")
    void unregisterReturnsNoContent() {
        assertThat(controller.unregister(9L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(mdmSubscriptionService).unregister(9L);
    }
}
```

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test | tail -8`
Expected: `BUILD SUCCESS`（`MdmSubscriptionServiceImplTest` 7 用例 + `MdmSubscriptionControllerTest` 3 用例全绿）

- [ ] **Step 7: 跨模块前置登记（TASK.md 与 PR 描述）**

在 `TASK.md` 的「TODO 工单」表末追加一行（编号取执行时表内最大号顺延——当前最大为 W-7，故为 W-8；若执行时已变化则顺延）：

```markdown
| W-8 | FU-M20-04 剩余 P0 条目（全量初始化 / 每日版本对账 / 落后自动全量重发 / `POST /mdm/redispatch`） | **跨模块前置阻塞**：依赖 M01 版本化回源与重发接口（当前 M01 仅 `GET /api/v1/system/dicts/{type}?version=` 覆盖字典，org/user/param/practice 无版本化读接口，PR-1b 侦察报告 §Gap 实证）。PR-1b 已交付订阅登记 + 广播链路分发流水 + 矩阵查询；对账状态 `mdm_subscription.recon_status` 现仅 PENDING（待对账），其余取值与自动重发随 M01 接口就绪后引入 | fuyun-integration V504+ 迁移（如需）、MdmSubscriptionServiceImpl、M01 接口 |
```

同时在 PR 描述中加「跨模块前置」小节，引用本行。

- [ ] **Step 8: Commit**

```bash
git add CHANGELOG.md TASK.md \
        backend/fuyun-integration/src/main/resources/db/migration/integration/V502__create_mdm_subscription.sql \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/constants/MdmConstants.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/entity/MdmSubscription.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/mapper/MdmSubscriptionMapper.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/dto/MdmSubscriptionCreateRequest.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/dto/MdmSubscriptionQuery.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/vo/MdmSubscriptionVO.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/service/IMdmSubscriptionService.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/service/impl/MdmSubscriptionServiceImpl.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/controller/MdmSubscriptionController.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/config/IntegrationMdmConfig.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/convert/IntegrationConverter.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/service/impl/MdmSubscriptionServiceImplTest.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/controller/MdmSubscriptionControllerTest.java \
        backend/fuyun-app/src/main/java/com/fuyun/app/config/IntegrationConfig.java
git commit -m "feat(integration): 主数据分发订阅登记与矩阵查询（FU-M20-04 P0 子集）"
```

---

### Task 10: FU-M20-04 主数据分发流水（V503 迁移 + 消费侧流水登记）

**Files:**
- Modify: `CHANGELOG.md`（V503 已在 Task 9 条目登记，本任务不重复登记；若 Task 9 未登记则补登）
- Create: `backend/fuyun-integration/src/main/resources/db/migration/integration/V503__create_mdm_dispatch_log.sql`
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/constants/MessagingConstants.java`（target_modules 列宽常量）
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/constants/MdmConstants.java`（事件类型 + 映射 + 分发模式）
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/entity/MdmDispatchLog.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/mapper/MdmDispatchLogMapper.java`
- Create: `backend/fuyun-integration/src/main/java/com/fuyun/integration/internal/MdmDispatchListener.java`
- Modify: `backend/fuyun-integration/src/main/java/com/fuyun/integration/config/IntegrationMdmConfig.java`
- Test: `backend/fuyun-integration/src/test/java/com/fuyun/integration/internal/MdmDispatchListenerTest.java`

**Interfaces:**
- Consumes: Task 9 的 `MdmConstants.*` / `IMdmSubscriptionService.listSubscriberModules(String)`；Task 5 的三方法幂等契约；既有 `EventEnvelopeCodec` / `MessageIdempotencyService` / `MessagingGovernance.declareConsumerQueue(ConsumerQueueSpec)`（PR-2 交付）。
- Produces:
  - 表 `integration.mdm_dispatch_log`（topic / version / dispatch_mode / dispatched_at / target_modules + created_at；只增台账）
  - `MdmConstants.EVENT_DICT_PUBLISHED/EVENT_ORG_CHANGED/EVENT_USER_CHANGED/EVENT_PARAM_CHANGED/EVENT_PRACTICE_CHANGED`、`TOPIC_BY_EVENT_TYPE`（Map）、`DISPATCH_MODE_BROADCAST="BROADCAST"`
  - 五个消费队列 `q.integration.<事件类型>`（经治理构件声明，subscription 自动登记 `integration`）
  - `MdmDispatchListener.onMasterDataChanged(Message)`（raw Message 承接，标准幂等范式）

**语义（写死）：** ① 分发流水 = M20 订阅 M01 五个主数据广播事件后落一行（「广播链路 = M01 事件 → fy.topic → 各模块缓存刷新，本模块记分发流水」，Spec §6 FU-M20-04）；② `target_modules` = 落流水时刻 `mdm_subscription` 中该主题的订阅方清单（逗号分隔，空清单记空串——广播事件无订阅方仍记流水）；③ `version` 只读载荷 `version` 字段（Spec §4 明文列 + §3.3「同步到哪个版本」的治理要素，**不解读其他业务字段**——M20 红线 1 边界）；占位 schema 事件（org/user/param/practice）载荷无该字段 → 登记 null 并在类 javadoc 注明待 M01 实装；④ `dispatched_at` 取信封 `occurredAt`（事件发生即分发，与 `received_event.occurred_at` 同口径）。

- [ ] **Step 1: V503 迁移与常量**

`V503__create_mdm_dispatch_log.sql`：

```sql
-- V503：主数据分发流水（M20 Spec §4 mdm_dispatch_log，FU-M20-04 补偿重发与分发审计依据）
-- 号段登记：V500 起「先登记先占」；V503 于 CHANGELOG 2026-09-15 号段登记条目后落盘（版本 > 真库最大 V501）。
-- 只增台账：无通用 updated_at 语义（V3/V4 定案口径），created_at 由数据库 DEFAULT now() 维护；
-- dispatched_at 为业务时刻（信封 occurredAt，应用层写入），与 received_event.occurred_at 同口径。
-- target_modules 宽度 1000：模块数 ≤ 20，模块标识 ≤32 字符，余量充分（截断仅作列宽防线）。
CREATE TABLE integration.mdm_dispatch_log (
    id             BIGINT        PRIMARY KEY,
    topic          VARCHAR(32)   NOT NULL,               -- 主数据主题：dict/org/user/param/practice
    version        BIGINT,                               -- 分发版本号（dict 事件载荷 version；占位 schema 主题为 null）
    dispatch_mode  VARCHAR(16)   NOT NULL,               -- 分发模式：BROADCAST 广播（全量重发待 M01 回源接口就绪）
    dispatched_at  TIMESTAMPTZ   NOT NULL,               -- 分发时刻（信封 occurredAt）
    target_modules VARCHAR(1000) NOT NULL DEFAULT '',    -- 分发目标模块清单（逗号分隔；空串=当时无订阅方）
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- 分发审计主检索：按主题 + 时间窗回溯分发历史（对账与补偿重发的依据）
CREATE INDEX idx_mdm_dispatch_log_topic ON integration.mdm_dispatch_log (topic, dispatched_at);
```

`MessagingConstants` 追加：

```java
    /** target_modules 列宽防线：mdm_dispatch_log.target_modules VARCHAR(1000) */
    public static final int MDM_TARGET_MODULES_MAX_LENGTH = 1000;
```

`MdmConstants` 追加：

```java
    /** 主数据事件类型（M01 发布清单，M20 §7 订阅清单逐条对应；V5 种子已登记，订阅方经声明构件自动登记） */
    public static final String EVENT_DICT_PUBLISHED = "system.dict.published";

    /** 主数据事件类型：机构变更 */
    public static final String EVENT_ORG_CHANGED = "system.org.changed";

    /** 主数据事件类型：用户变更 */
    public static final String EVENT_USER_CHANGED = "system.user.changed";

    /** 主数据事件类型：参数变更 */
    public static final String EVENT_PARAM_CHANGED = "system.param.changed";

    /** 主数据事件类型：执业授权变更 */
    public static final String EVENT_PRACTICE_CHANGED = "system.practice.changed";

    /** 事件类型 → 主数据主题映射：分发流水登记的 topic 推导源（未登记事件不经本链路消费） */
    public static final Map<String, String> TOPIC_BY_EVENT_TYPE = Map.of(
            EVENT_DICT_PUBLISHED, TOPIC_DICT,
            EVENT_ORG_CHANGED, TOPIC_ORG,
            EVENT_USER_CHANGED, TOPIC_USER,
            EVENT_PARAM_CHANGED, TOPIC_PARAM,
            EVENT_PRACTICE_CHANGED, TOPIC_PRACTICE);

    /** 分发模式：广播（M01 变更事件经 fy.topic 广播；FULL_REDISPATCH 待 M01 回源接口就绪后引入） */
    public static final String DISPATCH_MODE_BROADCAST = "BROADCAST";
```

（import 追加 `java.util.Map`。）

- [ ] **Step 2: 实体与 mapper**

`entity/MdmDispatchLog.java`：

```java
package com.fuyun.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 主数据分发流水实体（integration.mdm_dispatch_log）：广播/全量重发的审计依据（M20 §4）。
 *
 * <p>只增台账：不设 @TableLogic（无逻辑删列）、无 updated_at 语义（不挂触发器）；dispatched_at 为
 * 业务时刻（信封 occurredAt，应用层写入），created_at 由数据库 DEFAULT now() 维护。
 */
@Getter
@Setter
@TableName("integration.mdm_dispatch_log")
public class MdmDispatchLog {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 主数据主题：dict/org/user/param/practice */
    private String topic;

    /** 分发版本号；可空（占位 schema 主题的载荷无 version 字段） */
    private Long version;

    /** 分发模式：BROADCAST 广播（MdmConstants.DISPATCH_MODE_BROADCAST） */
    private String dispatchMode;

    /** 分发时刻（信封 occurredAt，应用层写入） */
    private OffsetDateTime dispatchedAt;

    /** 分发目标模块清单（逗号分隔；空串=当时无订阅方） */
    private String targetModules;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;
}
```

`mapper/MdmDispatchLogMapper.java`：

```java
package com.fuyun.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.integration.entity.MdmDispatchLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分发流水 mapper：mdm_dispatch_log 单表插入经 BaseMapper 内置能力（无 XML，宪法 A.4.3-15）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface MdmDispatchLogMapper extends BaseMapper<MdmDispatchLog> {}
```

- [ ] **Step 3: 消费者失败单测**

`internal/MdmDispatchListenerTest.java`：

```java
package com.fuyun.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.integration.constants.MdmConstants;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.entity.MdmDispatchLog;
import com.fuyun.integration.mapper.MdmDispatchLogMapper;
import com.fuyun.integration.service.IMdmSubscriptionService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * 主数据分发流水消费者单测：订阅方清单进 target_modules、version 只取治理字段、
 * 非主数据事件拒绝、失败路径走 settleFailure 后重抛（标准消费范式）。
 */
@ExtendWith(MockitoExtension.class)
class MdmDispatchListenerTest {

    /** 测试事件号：信封 eventId */
    private static final String EVENT_ID = "b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60";

    @Mock
    private MessageIdempotencyService idempotencyService;

    @Mock
    private IMdmSubscriptionService subscriptionService;

    @Mock
    private MdmDispatchLogMapper dispatchLogMapper;

    private MdmDispatchListener listener;

    @BeforeEach
    void setUp() {
        EventEnvelopeCodec codec = new EventEnvelopeCodec(new ObjectMapper());
        listener = new MdmDispatchListener(idempotencyService, codec, subscriptionService, dispatchLogMapper);
    }

    @Test
    @DisplayName("分发流水登记：topic 由事件类型推导，version 取载荷治理字段，target_modules 为订阅方清单")
    void recordsDispatchLogWithSubscriberTargets() {
        when(idempotencyService.tryAcquire(EVENT_ID, MessagingConstants.MODULE)).thenReturn(true);
        when(subscriptionService.listSubscriberModules(MdmConstants.TOPIC_DICT)).thenReturn(List.of("it", "lab"));

        listener.onMasterDataChanged(message(envelope(MdmConstants.EVENT_DICT_PUBLISHED, 3)));

        ArgumentCaptor<MdmDispatchLog> captor = ArgumentCaptor.forClass(MdmDispatchLog.class);
        verify(dispatchLogMapper).insert(captor.capture());
        MdmDispatchLog row = captor.getValue();
        assertThat(row.getTopic()).isEqualTo(MdmConstants.TOPIC_DICT);
        assertThat(row.getVersion()).isEqualTo(3L);
        assertThat(row.getDispatchMode()).isEqualTo(MdmConstants.DISPATCH_MODE_BROADCAST);
        assertThat(row.getTargetModules()).isEqualTo("it,lab");
        assertThat(row.getDispatchedAt()).isEqualTo(Instant.parse("2026-09-15T01:02:03Z").atOffset(java.time.ZoneOffset.UTC));
        verify(idempotencyService).recordProcessed(any(ReceivedEventRecord.class));
    }

    @Test
    @DisplayName("占位 schema 事件：载荷无 version 字段时登记 null（不解读其他业务字段）")
    void recordsNullVersionWhenPayloadHasNoVersionField() {
        when(idempotencyService.tryAcquire(EVENT_ID, MessagingConstants.MODULE)).thenReturn(true);
        when(subscriptionService.listSubscriberModules(MdmConstants.TOPIC_ORG)).thenReturn(List.of());

        listener.onMasterDataChanged(message(envelope(MdmConstants.EVENT_ORG_CHANGED, null)));

        ArgumentCaptor<MdmDispatchLog> captor = ArgumentCaptor.forClass(MdmDispatchLog.class);
        verify(dispatchLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isNull();
        assertThat(captor.getValue().getTargetModules()).isEmpty();
    }

    @Test
    @DisplayName("重复投递：tryAcquire 返回 false 时直接跳过，不写流水不登记")
    void skipsDuplicateDelivery() {
        when(idempotencyService.tryAcquire(EVENT_ID, MessagingConstants.MODULE)).thenReturn(false);

        listener.onMasterDataChanged(message(envelope(MdmConstants.EVENT_DICT_PUBLISHED, 1)));

        verify(dispatchLogMapper, never()).insert(any(MdmDispatchLog.class));
        verify(idempotencyService, never()).recordProcessed(any(ReceivedEventRecord.class));
    }

    @Test
    @DisplayName("非主数据事件：拒绝消费（装配与契约漂移显性失败），失败路径经 settleFailure 后重抛")
    void rejectsNonMasterDataEventAndSettlesFailure() {
        when(idempotencyService.tryAcquire(EVENT_ID, MessagingConstants.MODULE)).thenReturn(true);

        assertThatThrownBy(() -> listener.onMasterDataChanged(message(envelope("iot.telemetry.message", 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("主数据主题");
        verify(idempotencyService).settleFailure(any(ReceivedEventRecord.class), any(RuntimeException.class));
        verify(dispatchLogMapper, never()).insert(any(MdmDispatchLog.class));
    }

    @Test
    @DisplayName("落库失败：settleFailure 收尾后重抛交容器重试（不吞错）")
    void rethrowsWhenDispatchLogInsertFails() {
        when(idempotencyService.tryAcquire(EVENT_ID, MessagingConstants.MODULE)).thenReturn(true);
        when(subscriptionService.listSubscriberModules(MdmConstants.TOPIC_DICT)).thenReturn(List.of("it"));
        when(dispatchLogMapper.insert(any(MdmDispatchLog.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("数据库连接不可用"));

        assertThatCode(() -> listener.onMasterDataChanged(message(envelope(MdmConstants.EVENT_DICT_PUBLISHED, 1))))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        verify(idempotencyService).settleFailure(any(ReceivedEventRecord.class), any(RuntimeException.class));
    }

    /**
     * 构造合规信封：eventType 指定、version 可空（null 时不写入载荷 version 字段）。
     *
     * @param eventType 事件类型
     * @param version   载荷 version 字段值，可空
     * @return 事件信封
     */
    private EventEnvelope envelope(String eventType, Integer version) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        if (version != null) {
            payload.put("version", version);
        }
        return new EventEnvelope(
                EVENT_ID,
                Instant.parse("2026-09-15T01:02:03Z"),
                "system",
                eventType,
                "1",
                null,
                payload);
    }

    /**
     * 信封对象 → raw MQ 帧（消费侧以 HTTP 无关的原文承接）。
     *
     * @param envelope 事件信封
     * @return raw Message
     */
    private Message message(EventEnvelope envelope) {
        String body = new EventEnvelopeCodec(new ObjectMapper()).toJson(envelope);
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
```

（`anyString` 未使用则删除该静态导入；`assertThatCode(...).isInstanceOf(...)` 写法以 AssertJ 实际 API 为准——等价写法 `assertThatThrownBy(...)`，二选一保留一种。）

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test -Dtest=MdmDispatchListenerTest -Dsurefire.failIfNoSpecifiedTests=false | tail -6`
Expected: COMPILATION ERROR（`MdmDispatchListener` 不存在）

- [ ] **Step 4: 消费者实现**

`internal/MdmDispatchListener.java`：

```java
package com.fuyun.integration.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.common.utils.TextTruncate;
import com.fuyun.integration.constants.MdmConstants;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.entity.MdmDispatchLog;
import com.fuyun.integration.mapper.MdmDispatchLogMapper;
import com.fuyun.integration.service.IMdmSubscriptionService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * 主数据分发流水消费者：M01 五个主数据广播事件的消费侧登记点（M20 §6 FU-M20-04「广播链路 =
 * M01 事件 → fy.topic → 各模块缓存刷新，本模块记分发流水」）。
 *
 * <p>消费姿态：容器 AUTO 确认 + raw {@link Message} 承接原文 + 标准幂等范式（{@link
 * MessageIdempotencyService}，后端宪法 A.5-5/A.5-6）；队列名与 IntegrationMdmConfig 经治理构件
 * 声明的队列同源（同一组常量拼接，禁手写队列字面量）。
 *
 * <p>载荷边界（M20 红线 1）：只读取载荷的 {@code version} 治理字段（Spec §4 mdm_dispatch_log
 * 明文列 + §3.3「同步到哪个版本」），不解读其他业务字段；占位 schema 事件（org/user/param/practice）
 * 载荷尚无 version 字段，登记 null 待 M01 实装（PR-3）。
 *
 * <p>归 internal/ 包：容器驱动的模块内入口，禁止外部引用（backend 宪法 B.1）；Bean 注册点为
 * IntegrationMdmConfig @Import。
 */
@Slf4j
public class MdmDispatchListener {

    private final MessageIdempotencyService idempotencyService;

    private final EventEnvelopeCodec codec;

    private final IMdmSubscriptionService subscriptionService;

    private final MdmDispatchLogMapper dispatchLogMapper;

    /**
     * 全参构造器（装配归 IntegrationMdmConfig @Import）。
     *
     * @param idempotencyService  消费幂等构件，非空；来源：M20 治理构件装配
     * @param codec               信封编解码器，非空；来源：MessagingGovernanceConfig 装配
     * @param subscriptionService 主数据订阅服务，非空；分发目标清单数据源（同模块 service）
     * @param dispatchLogMapper   分发流水 mapper，非空；容器驱动入口按死信监听同款直用 mapper
     */
    public MdmDispatchListener(
            MessageIdempotencyService idempotencyService,
            EventEnvelopeCodec codec,
            IMdmSubscriptionService subscriptionService,
            MdmDispatchLogMapper dispatchLogMapper) {
        this.idempotencyService = idempotencyService;
        this.codec = codec;
        this.subscriptionService = subscriptionService;
        this.dispatchLogMapper = dispatchLogMapper;
    }

    /**
     * 主数据广播事件统一消费入口（五个事件共用一套队列声明与处理逻辑）。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至 q.integration.* 队列的信封线格式
     */
    @RabbitListener(
            queues = {
                MessagingConstants.QUEUE_PREFIX + MessagingConstants.MODULE + "." + MdmConstants.EVENT_DICT_PUBLISHED,
                MessagingConstants.QUEUE_PREFIX + MessagingConstants.MODULE + "." + MdmConstants.EVENT_ORG_CHANGED,
                MessagingConstants.QUEUE_PREFIX + MessagingConstants.MODULE + "." + MdmConstants.EVENT_USER_CHANGED,
                MessagingConstants.QUEUE_PREFIX + MessagingConstants.MODULE + "." + MdmConstants.EVENT_PARAM_CHANGED,
                MessagingConstants.QUEUE_PREFIX + MessagingConstants.MODULE + "." + MdmConstants.EVENT_PRACTICE_CHANGED
            })
    public void onMasterDataChanged(Message message) {
        // 原文进 codec：__TypeId__ 头不作消费依据（CF-1 冻结约定）
        EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
        // 标准范式①：重复投递（NX 失败且回查确认已处理）直接返回跳过，即 AUTO 确认
        if (!idempotencyService.tryAcquire(envelope.eventId(), MessagingConstants.MODULE)) {
            return;
        }
        ReceivedEventRecord record = new ReceivedEventRecord(
                envelope.eventId(),
                envelope.eventType(),
                envelope.producer(),
                envelope.occurredAt(),
                MessagingConstants.MODULE);
        try {
            doBusiness(envelope);
            // 标准范式②：成功登记 received_event（唯一索引兜底并发，前次失败行升级为已处理）
            idempotencyService.recordProcessed(record);
        } catch (RuntimeException e) {
            // 标准范式③：释放前置键 + FAILED 留痕（不遮蔽 e），上抛交容器有界重试耗尽进 fy.dlx
            idempotencyService.settleFailure(record, e);
            throw e;
        }
    }

    /**
     * 登记一行分发流水：topic 由事件类型推导、version 取载荷治理字段、target_modules 取当前订阅方清单。
     *
     * @param envelope 已解析的合规信封，非空
     * @throws IllegalStateException 事件类型不在主数据主题映射内（队列绑定与常量漂移）时触发——
     *                               按消费失败处置（上抛走有界重试），禁止静默丢弃
     */
    private void doBusiness(EventEnvelope envelope) {
        String topic = MdmConstants.TOPIC_BY_EVENT_TYPE.get(envelope.eventType());
        if (topic == null) {
            throw new IllegalStateException("事件类型未登记为主数据主题，禁止经本监听器消费：" + envelope.eventType());
        }
        JsonNode versionNode = envelope.payload().path("version");
        Long version = versionNode.isNumber() ? versionNode.asLong() : null;
        List<String> targets = subscriptionService.listSubscriberModules(topic);
        String targetModules =
                TextTruncate.truncate(String.join(",", targets), MessagingConstants.MDM_TARGET_MODULES_MAX_LENGTH);
        MdmDispatchLog logRow = new MdmDispatchLog();
        logRow.setTopic(topic);
        logRow.setVersion(version);
        logRow.setDispatchMode(MdmConstants.DISPATCH_MODE_BROADCAST);
        // dispatched_at 取信封 occurredAt（事件发生即分发；与 received_event.occurred_at 同口径）
        logRow.setDispatchedAt(OffsetDateTime.ofInstant(envelope.occurredAt(), ZoneOffset.UTC));
        logRow.setTargetModules(targetModules);
        dispatchLogMapper.insert(logRow);
        log.info(
                "主数据分发流水登记完成：topic={}，version={}，target_modules={}，event_id={}，traceId={}",
                topic,
                version,
                targetModules,
                envelope.eventId(),
                envelope.traceId());
    }
}
```

- [ ] **Step 5: 队列声明装配**

`IntegrationMdmConfig` 改为：

```java
package com.fuyun.integration.config;

import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MdmConstants;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.controller.MdmSubscriptionController;
import com.fuyun.integration.internal.MdmDispatchListener;
import com.fuyun.integration.service.impl.MdmSubscriptionServiceImpl;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M20 主数据分发治理装配：订阅台账服务/端点 + 分发流水消费者与五个消费队列声明。
 *
 * <p>队列经治理构件声明（先登记后订阅 + quorum + 死信 fy.dlx，M20 §7 治理约定）：声明副作用同时把
 * `integration` 写入五个主数据事件的 subscriber_modules（事件已在 V5 种子登记）；队列名与消费者
 * {@code @RabbitListener} 同源推导（同一组常量），禁手写队列字面量。
 */
@Configuration
@Import({MdmSubscriptionServiceImpl.class, MdmSubscriptionController.class, MdmDispatchListener.class})
public class IntegrationMdmConfig {

    /**
     * 声明五个主数据消费队列（q.integration.<事件类型>）并绑定 fy.topic。
     *
     * @param governance 消息治理构件，非空；来源：MessagingGovernanceConfig 装配
     * @return 声明集合（五队列 + 五绑定）交 RabbitAdmin 幂等声明
     */
    @Bean
    public Declarables mdmMasterDataQueues(MessagingGovernance governance) {
        List<Declarable> declarables = Stream.of(
                        MdmConstants.EVENT_DICT_PUBLISHED,
                        MdmConstants.EVENT_ORG_CHANGED,
                        MdmConstants.EVENT_USER_CHANGED,
                        MdmConstants.EVENT_PARAM_CHANGED,
                        MdmConstants.EVENT_PRACTICE_CHANGED)
                .flatMap(eventType -> governance
                        .declareConsumerQueue(new ConsumerQueueSpec(MessagingConstants.MODULE, eventType))
                        .getDeclarables()
                        .stream())
                .toList();
        return new Declarables(declarables);
    }
}
```

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-integration -am test | tail -8`
Expected: `BUILD SUCCESS`（`MdmDispatchListenerTest` 5 用例全绿）

- [ ] **Step 6: 迁移自检与 Commit**

Run: `python scripts/check-migration-governance.py`
Expected: `迁移治理校验通过：17 个迁移文件，基线 HEAD`

```bash
git add backend/fuyun-integration/src/main/resources/db/migration/integration/V503__create_mdm_dispatch_log.sql \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/constants/MdmConstants.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/constants/MessagingConstants.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/entity/MdmDispatchLog.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/mapper/MdmDispatchLogMapper.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/internal/MdmDispatchListener.java \
        backend/fuyun-integration/src/main/java/com/fuyun/integration/config/IntegrationMdmConfig.java \
        backend/fuyun-integration/src/test/java/com/fuyun/integration/internal/MdmDispatchListenerTest.java
git commit -m "feat(integration): 主数据分发流水登记与五个消费队列声明"
```

---

### Task 11: W-5 properties record toString 脱敏覆写

**Files:**
- Modify: `backend/fuyun-iot/src/main/java/com/fuyun/iot/properties/IotProperties.java`（`Amqp` 与 `Fallback` 两个 record 各加覆写）
- Modify: `backend/fuyun-system/src/main/java/com/fuyun/system/properties/SecurityProperties.java`（加覆写）
- Test: `backend/fuyun-iot/src/test/java/com/fuyun/iot/properties/IotPropertiesTest.java`（追加）
- Test: `backend/fuyun-system/src/test/java/com/fuyun/system/properties/SecurityPropertiesTest.java`（追加）

**Interfaces:**
- Produces: `IotProperties.Amqp.toString()` / `IotProperties.Fallback.toString()` / `SecurityProperties.toString()`——敏感字段固定打码 `***`，非敏感字段照常输出；外部签名与绑定行为零变化。

**范围裁决（W-5 原文「backend 各模块 properties/ 包」）：** 仅覆写**含敏感字段**的两个 properties 类（侦察报告 §6 清单：`IotProperties.Amqp.accessKey/accessSecret`、`IotProperties.Fallback.token`、`SecurityProperties.tokenHmacSecret`）。`TraceProperties`（responseHeaderEnabled/mdcKey）与 `MessagingProperties`（idempotencyRedisTtl）无敏感字段，**不覆写**——record 默认 toString 即完整诊断信息，多一次覆写属无因改动。`iot-simulator` 的 `SimulatorConfig`/`MqttCredential` 与 system `dto/LoginRequest`、`RefreshRequest` 不在 properties 包（W-5 文字范围外），列入计划末「遗留问题」交后续专项。覆写方法在 JaCoCo excludes（`properties/**`）内，不计覆盖率门禁，但单测仍必需（等保纵深防御的契约证明）。

- [ ] **Step 1: 追加失败单测**

`IotPropertiesTest` 追加：

```java
    @Test
    @DisplayName("toString 脱敏：accessKey/accessSecret 打码不外泄，非敏感字段照常输出（W-5）")
    void amqpToStringMasksCredentials() {
        String text = ENABLED_AMQP.toString();

        assertThat(text).doesNotContain("test-access-key").doesNotContain("test-access-secret");
        assertThat(text).contains("accessKey=***", "accessSecret=***", "amqp://127.0.0.1:5672");
    }

    @Test
    @DisplayName("Fallback toString 脱敏：兜底通道共享密钥打码（W-5）")
    void fallbackToStringMasksToken() {
        String text = new IotProperties.Fallback("test-fallback-token").toString();

        assertThat(text).doesNotContain("test-fallback-token").contains("token=***");
    }
```

`SecurityPropertiesTest` 追加：

```java
    @Test
    @DisplayName("toString 脱敏：HMAC 密钥打码不外泄，TTL 字段照常输出（W-5）")
    void toStringMasksHmacSecret() {
        SecurityProperties properties =
                new SecurityProperties(TEST_SECRET_32, Duration.ofHours(2), Duration.ofHours(24));

        String text = properties.toString();

        assertThat(text).doesNotContain(TEST_SECRET_32);
        assertThat(text).contains("tokenHmacSecret=***", "accessTokenTtl=PT2H", "refreshTokenTtl=PT24H");
    }
```

（`SecurityPropertiesTest` 需补 `import java.time.Duration;`。）

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-iot,fuyun-system -am test -Dtest='IotPropertiesTest,SecurityPropertiesTest' -Dsurefire.failIfNoSpecifiedTests=false | tail -8`
Expected: FAIL（默认 toString 含明文凭证断言失败）

- [ ] **Step 2: 实现三个覆写**

`IotProperties.Amqp` 的 `validateAmqpEnabled()` 之后追加（同级成员）：

```java
        /**
         * 脱敏 toString（W-5，等保三级纵深防御）：accessKey/accessSecret 固定打码，其余字段照常输出。
         *
         * <p>覆写原因：record 默认 toString 会直出全部字段值（含凭证），而「凭证禁入日志」是红线——
         * 不能依赖每个调用方自觉避开整对象打印（如日志模板误用 {} 直出对象）。endpoint 按配置约定
         * 不含用户信息（凭证经 SASL 三段 username 传入，见 IotAmqpTelemetryConsumer#ensureConnected），
         * 故照常输出供排障定位。
         *
         * @return 脱敏文本，非空；凭证字段恒为 ***
         */
        @Override
        public String toString() {
            return "Amqp[enabled=" + enabled
                    + ", endpoint=" + endpoint
                    + ", accessKey=***"
                    + ", accessSecret=***"
                    + ", queues=" + queues
                    + ", queuePrefetch=" + queuePrefetch
                    + ", batchSize=" + batchSize
                    + ", batchFlushInterval=" + batchFlushInterval
                    + ", batchQueueCapacity=" + batchQueueCapacity
                    + ", reconnectInitialDelay=" + reconnectInitialDelay
                    + ", reconnectMaxDelay=" + reconnectMaxDelay
                    + "]";
        }
```

`IotProperties.Fallback` 改为：

```java
    /**
     * HTTP 兜底通道配置（fuyun.iot.fallback.*，B4.3 兜底端点独立鉴权使用）。
     *
     * @param token 兜底通道共享密钥，与请求头 X-Iot-Fallback-Token 常量时间比对；未配置绑定为
     *              null（yml 空占位解析为空串，两态同义=未配置，比对侧 fail-closed 一律拒绝）；
     *              来源：FUYUN_IOT_FALLBACK_TOKEN 环境变量映射；禁入日志
     */
    public record Fallback(String token) {

        /**
         * 脱敏 toString（W-5）：共享密钥固定打码——record 默认形态直出密钥值，属日志红线风险。
         *
         * @return 脱敏文本，非空；token 恒为 ***
         */
        @Override
        public String toString() {
            return "Fallback[token=***]";
        }
    }
```

`SecurityProperties` 追加（record 体内）：

```java
    /**
     * 脱敏 toString（W-5，等保三级纵深防御）：HMAC 签名密钥固定打码，TTL 字段照常输出。
     *
     * <p>覆写原因：record 默认 toString 直出密钥值；密钥「禁明文入 yml/代码/文档/测试断言」属红线，
     * 整对象日志打印必须不可能泄漏。
     *
     * @return 脱敏文本，非空；tokenHmacSecret 恒为 ***
     */
    @Override
    public String toString() {
        return "SecurityProperties[tokenHmacSecret=***"
                + ", accessTokenTtl=" + accessTokenTtl
                + ", refreshTokenTtl=" + refreshTokenTtl
                + "]";
    }
```

- [ ] **Step 3: 运行确认通过**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-iot,fuyun-system -am test -Dtest='IotPropertiesTest,SecurityPropertiesTest' -Dsurefire.failIfNoSpecifiedTests=false | tail -6`
Expected: `Tests run: …`（两模块各自全绿，新增 3 用例通过）

- [ ] **Step 4: Commit**

```bash
git add backend/fuyun-iot/src/main/java/com/fuyun/iot/properties/IotProperties.java \
        backend/fuyun-system/src/main/java/com/fuyun/system/properties/SecurityProperties.java \
        backend/fuyun-iot/src/test/java/com/fuyun/iot/properties/IotPropertiesTest.java \
        backend/fuyun-system/src/test/java/com/fuyun/system/properties/SecurityPropertiesTest.java
git commit -m "fix(properties): 敏感配置 record 补 toString 脱敏覆写（W-5）"
```

---

### Task 12: D-8 宪法 A.5-9 正文修订（先记 CHANGELOG 再改正文）

**Files:**
- Modify: `CHANGELOG.md`（新条目：裁决落地记录 + 2026-09-11 条目归源更正）
- Modify: `backend/AGENTS.md`（A.5-9 正文）
- Modify: `backend/fuyun-iot/src/main/java/com/fuyun/iot/properties/IotProperties.java`（`Amqp` javadoc 措辞与宪法同步）
- Modify: `TASK.md`（D-8 行回填删除）

**Interfaces:**
- Produces: 宪法 A.5-9 正文与代码/实测一致的 failover 参数条款（`failover.` 前缀语法 + `failover.maxReconnectAttempts=3` 有限重试移交语义）；`IotProperties` javadoc 引用措辞同步。**代码零行为变更**（`IotAmqpConfig` 装配与 `IotAmqpConfigTest` 断言均不动）。

**裁决依据（TASK.md D-8 行，默认建议 = 修订正文）：** PR-4 B4.4 实测（`IotAmqpReconnectIT` 两次 RED 留证）+ CHANGELOG 2026-09-11 条目已登记「裸名不被 failover 层识别」「官方选项表无 timeout 类移交参数，唯一移交机制为有限 maxReconnectAttempts」。A.5-9 正文当前为三参数**裸名**形态且无移交语义，与代码/实测不一致。

- [ ] **Step 1: 先记 CHANGELOG（修宪前登记）**

在 `CHANGELOG.md` 头部说明块之后、上一条 2026-09-15 条目之前插入：

```markdown
## 2026-09-15 · D-8 裁决落地：宪法 A.5-9 failover 参数正文同步（先记再改）

- **背景**：PR-4 B4.4 实测（`IotAmqpReconnectIT` 两次 RED 留证）证实 qpid-jms 2.11 的 failover 选项必须带
  `failover.` 前缀（裸名形态不被 failover 层识别，语义等同未配置）；官方选项表无 timeout 类移交参数，
  唯一移交机制为有限 `failover.maxReconnectAttempts`（由 -1 改 3 后交 supervisor 以新时间戳凭证无限重建）。
  代码与 CHANGELOG 2026-09-11 条目已登记，宪法正文未同步（TASK.md D-8 待决策行）。
- **裁决**：按 TASK.md D-8 默认建议执行——**修订正文**（用户 2026-09-15 裁决，PR-1b 随本次交付）。
- **宪法修订范围**：backend/AGENTS.md A.5-9 正文——三参数补 `failover.` 前缀语法说明与取值；补
  `failover.maxReconnectAttempts=3` 的有限重试移交 supervisor 语义（「无限重连」语义上移到凭证刷新层，
  正对 IoTDA 拒绝超 5 分钟旧时间戳的服务端语义）；条款措辞与 IotAmqpConfig 装配实现逐字对齐。
- **归源更正（追记）**：CHANGELOG 2026-09-11 条目中「此为宪法 A.5-9『failover.maxReconnectAttempts=-1（无限次）』
  文字的实测修正」表述**归源错误**——A.5-9 正文从未写入 maxReconnectAttempts（见修订前正文），
  `-1` 实为**简报 §1.3 预判值**；该条目就地更正为「简报 §1.3 预判值的实测修正」（历史事实保留，仅纠正归源）。
- **登记收口**：TASK.md D-8 待决策行随本条目回填删除。
- **代码影响面**：零行为变更（仅 IotProperties javadoc 引用措辞同步；IotAmqpConfig 装配与 IotAmqpConfigTest
  URI 断言不动）。
```

- [ ] **Step 2: 修订 A.5-9 正文**

`backend/AGENTS.md` A.5 第 9 条原文（逐字）：

```markdown
9. **IoTDA AMQP（Qpid JMS 2.11.0）**：与 Spring AMQP 完全连接隔离（自建 ConnectionFactory + 专用容器工厂 + 独立 `iot.amqp.*` 配置前缀）；URI 显式写全 failover 参数（initialReconnectDelay=3000 / reconnectDelay=3000 / maxReconnectDelay=30000）；消费者包装为 SmartLifecycle，连接数预算"实例数 × 每实例连接数 ≤ 32"（单凭证上限）；凭证经 env 注入；部署机 NTP 同步为前置检查。
```

替换为：

```markdown
9. **IoTDA AMQP（Qpid JMS 2.11.0）**：与 Spring AMQP 完全连接隔离（自建 ConnectionFactory + 专用容器工厂 + 独立 `iot.amqp.*` 配置前缀）；URI 的 failover 参数一律用官方 `failover.` 前缀语法（`failover.initialReconnectDelay=3000` / `failover.reconnectDelay=3000` / `failover.maxReconnectDelay=30000`，裸名形态不被 failover 层识别、语义等同未配置，B4.4 实测留证）+ 有限重试移交 `failover.maxReconnectAttempts=3`（传输层透明重试上限 3 次后连接失败、控制权移交消费者 supervisor 以新时间戳凭证无限重建——「无限重连」语义上移到凭证刷新层，正对 IoTDA 拒绝超 5 分钟旧时间戳的服务端语义；官方选项表无 timeout 类移交参数）；消费者包装为 SmartLifecycle，连接数预算"实例数 × 每实例连接数 ≤ 32"（单凭证上限）；凭证经 env 注入；部署机 NTP 同步为前置检查。
```

- [ ] **Step 3: CHANGELOG 2026-09-11 条目归源就地更正**

将该条目内的片段（逐字）：

```markdown
**偏差申报**：此为宪法 A.5-9「failover.maxReconnectAttempts=-1（无限次）」文字的实测修正——无限重连语义在 supervisor 层完整保留
```

替换为：

```markdown
**偏差申报**：此为**简报 §1.3 预判值**（-1，非宪法条文）的实测修正（归源更正见 2026-09-15 D-8 条目）——无限重连语义在 supervisor 层完整保留
```

- [ ] **Step 4: IotProperties javadoc 措辞同步**

`IotProperties.Amqp` 的两处 `@param` 描述（逐字）：

```java
     * @param reconnectInitialDelay 断链重连初始退避，默认 3s（宪法 A.5-9 failover 参数原文值）
     * @param reconnectMaxDelay    断链重连最大退避，默认 30s（指数退避上限，宪法 A.5-9 原文值）
```

替换为：

```java
     * @param reconnectInitialDelay 断链重连初始退避，默认 3s（宪法 A.5-9 `failover.initialReconnectDelay`
     *                              / `failover.reconnectDelay` 取值）
     * @param reconnectMaxDelay    断链重连最大退避，默认 30s（指数退避上限，宪法 A.5-9
     *                              `failover.maxReconnectDelay` 取值）
```

- [ ] **Step 5: 全仓 A.5-9 引用一致性核验**

Run: `grep -rn "A\.5-9" backend/ docs/ CHANGELOG.md TASK.md 2>/dev/null | grep -v target`
Expected（**关键命中清单**，逐条判定，**默认无需动作**——禁把正确引用当残留误改历史文本；另有历史/过程文档多处命中——`docs/plans/*`、`docs/prompt/*`、`docs/progress/*`、`backend/fuyun-app/src/main/resources/application.yml:133/137`、`backend/fuyun-iot/pom.xml:42` 及本计划文件自身——一律无需动作、禁改）：
- `backend/AGENTS.md` A.5-9：**本次修订对象**（Step 2）；
- `CHANGELOG.md:93`（2026-09-11 条目实测发现段）：历史事实陈述，无需动作；
- `CHANGELOG.md:94`（同条目对策段）：仅「宪法 A.5-9…文字」归源短语按 Step 3 就地更正，其余文本保留；
- `TASK.md:14`（D-8 行）：Step 6 回填删除，无需其他动作；
- `backend/fuyun-iot/src/main/java/com/fuyun/iot/config/IotAmqpConfig.java:16/24/39/90`：16 条款编号引用、24 行已含「官方 failover. 前缀语法 + maxReconnectAttempts 有限值移交 supervisor」表述、39 行连接数预算、90 行「与宪法 A.5-9 原文值一致」为取值一致的事实陈述（修订后仍成立）——四行均**无需动作**；
- `backend/fuyun-iot/src/main/java/com/fuyun/iot/internal/IotAmqpTelemetryConsumer.java:36/52/80/102/279/485`、`TelemetryFrameParser.java:21`、`IotAmqpTelemetryConsumerTest.java:408`：条款编号/连接数预算/退避参数引用，与本次修订无关，**无需动作**；
- `backend/fuyun-iot/src/main/java/com/fuyun/iot/properties/IotProperties.java:41`：条款编号引用无需动作；`:62/:63` 按 Step 4 同步措辞。
核验结论（含「无残留归源表述」判定）写入 PR 描述。

- [ ] **Step 6: TASK.md D-8 行删除并复跑相关测试**

删除 `TASK.md` 待决策项表中 D-8 整行（登记规则：回填后删除）。

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-iot -am test -Dtest='IotPropertiesTest,IotAmqpConfigTest' -Dsurefire.failIfNoSpecifiedTests=false | tail -5`
Expected: `BUILD SUCCESS`（文档类修订零行为影响，测试仅作回归证明）

- [ ] **Step 7: Commit**

```bash
git add CHANGELOG.md backend/AGENTS.md TASK.md \
        backend/fuyun-iot/src/main/java/com/fuyun/iot/properties/IotProperties.java
git commit -m "docs(constitution): D-8 落地——A.5-9 failover 前缀语法与有限重试移交语义"
```

### Task 13: 死信治理端到端 IT（死信 → 重推 → 消费成功闭环 + FAILED 升级 + 关闭 + 分发流水）

**Files:**
- Create: `backend/fuyun-app/src/test/java/com/fuyun/app/DeadLetterGovernanceIT.java`

**Interfaces:**
- Consumes: Task 3 的 `IDeadLetterService.replay/close/detail`、Task 5 的幂等失败链、Task 8 的订阅自动登记、Task 9/10 的订阅服务与分发流水；IT 基座范式照抄 `MessagingGovernanceIT`（三容器 + 假密钥 + 手写轮询，不引入 awaitility）。
- Produces: PR-1 验收明文「死信→重推→消费成功闭环 IT」的落地证明（含 Spec §10 异常场景「死信重放失败回 PENDING 并累加计数」的状态口径在单测侧已覆盖、本 IT 覆盖成功路径）。

**测试语义（写死）：** ① 消费者以静态开关 `FAIL_MODE` 模拟业务失败；拨回成功后再重放，验证「失败登记 FAILED → 重放 → 消费成功 → 台账行内升级 PROCESSED」全链；② 重推上限以 SQL 造态（`replay_count=3`）验证拒绝；③ 关闭留痕与终态禁重放；④ 主数据广播事件落分发流水并把订阅方清单写入 `target_modules`。IT 只调服务层（HTTP 层由 controller 薄层单测覆盖），不打 HTTP——与本仓库既有 IT 口径一致。

- [ ] **Step 1: 写 IT**

```java
package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import com.fuyun.common.exception.BizException;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.IntegrationErrorCode;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MdmConstants;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.dto.DeadLetterCloseRequest;
import com.fuyun.integration.dto.MdmSubscriptionCreateRequest;
import com.fuyun.integration.dto.MdmSubscriptionQuery;
import com.fuyun.integration.service.IDeadLetterService;
import com.fuyun.integration.service.IMdmSubscriptionService;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 死信治理端到端 IT（PR-1 验收明文「死信→重推→消费成功闭环」）：真实三中间件打通
 * 「消费失败 → FAILED 留痕 → 有界重试耗尽 → fy.dlx → dead_letter(PENDING) → 重放 → 消费成功
 * → 台账行内升级 PROCESSED → dead_letter(REPLAYED)」全链，并覆盖重推上限、关闭留痕与主数据
 * 分发流水（FU-M20-04「本模块记分发流水」）。
 *
 * <p>容器三件套与 MessagingGovernanceIT 完全同款（tag 与 deploy compose 严格一致 +
 * it/rabbitmq.conf 挂载 + static 类级共享 + @ServiceConnection）；测试 profile 的重试为
 * 100ms/1.0（3 次亚秒级耗尽），死信链路可在 15s 等待窗内收敛。不引入 awaitility。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeadLetterGovernanceIT {

    /** TimescaleDB 容器：dead_letter / received_event / mdm_* 断言目标库 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：幂等前置键真实存储 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：消费重试耗尽 → fy.dlx → 死信落库 → 重放回 fy.topic 的真实链路 */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 测试资产假密钥（仅具 IT 意义，与任何真实凭证无关） */
    private static final String TEST_HMAC_SECRET = "it-only-fake-hmac-secret-0123456789abcdef0123456789abcdef";

    @DynamicPropertySource
    static void registerSecurityProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.security.token-hmac-secret", () -> TEST_HMAC_SECRET);
    }

    /** 测试消费者域标识：幂等键第二要素与队列命名第一段 */
    private static final String CONSUMER_MODULE = "it";

    /** 闭环链路事件类型：CF-2 首批种子事件（V5 登记行） */
    private static final String EVENT_TYPE = "system.dict.published";

    /** 监听队列名：构件命名规则 q.<consumerModule>.<eventType> */
    private static final String QUEUE_NAME = MessagingConstants.QUEUE_PREFIX + CONSUMER_MODULE + "." + EVENT_TYPE;

    /** 链路等待上限：覆盖消费重试（100ms×3）+ 死信转发 + 死信消费落库 + 重放回投 */
    private static final Duration LINK_TIMEOUT = Duration.ofSeconds(20);

    /** 轮询间隔：200ms 步进（仓库既有口径） */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /**
     * 测试消费者业务开关：true=业务失败（模拟毒丸），false=业务成功（重放后恢复正常）。
     *
     * <p>用例内时序（@Order 串联、跨用例共享静态状态，改动任一用例前必须复核本时序）：
     * ①初值 true——@Order(1) 制造首帧死信；②@Order(2) 起始置 false——重放后业务成功；
     * ③@Order(3) 起始置 **true** 制造第二帧死信（关闭场景），结尾复位 false；④@Order(4) 保持 false。
     */
    static final AtomicBoolean FAIL_MODE = new AtomicBoolean(true);

    /** 业务成功计数：重放后恰好 +1（幂等拦截证明——同 eventId 不重复进入业务） */
    static final AtomicInteger BUSINESS_COUNT = new AtomicInteger();

    /** 测试消费者：标准幂等范式（tryAcquire → 业务 → recordProcessed；失败 settleFailure + 重抛） */
    static class DeadLetterProbeConsumer {

        private final MessageIdempotencyService idempotencyService;

        private final EventEnvelopeCodec codec;

        DeadLetterProbeConsumer(
                MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec) {
            this.idempotencyService = idempotencyService;
            this.codec = codec;
        }

        @RabbitListener(queues = QUEUE_NAME)
        void onDictPublished(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            if (!idempotencyService.tryAcquire(envelope.eventId(), CONSUMER_MODULE)) {
                return;
            }
            ReceivedEventRecord record = new ReceivedEventRecord(
                    envelope.eventId(),
                    envelope.eventType(),
                    envelope.producer(),
                    envelope.occurredAt(),
                    CONSUMER_MODULE);
            try {
                if (FAIL_MODE.get()) {
                    throw new IllegalStateException("模拟业务失败：毒丸帧触发有界重试与死信链路");
                }
                BUSINESS_COUNT.incrementAndGet();
                idempotencyService.recordProcessed(record);
            } catch (RuntimeException e) {
                idempotencyService.settleFailure(record, e);
                throw e;
            }
        }
    }

    /** 测试装配：消费队列经治理构件声明（禁测试私建队列，M20 红线）；消费者 Bean 注册 */
    @TestConfiguration
    static class DeadLetterProbeConfig {

        @Bean
        Declarables itDeadLetterProbeQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(new ConsumerQueueSpec(CONSUMER_MODULE, EVENT_TYPE));
        }

        @Bean
        DeadLetterProbeConsumer deadLetterProbeConsumer(
                MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec) {
            return new DeadLetterProbeConsumer(idempotencyService, codec);
        }
    }

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired EventEnvelopeCodec codec;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired IDeadLetterService deadLetterService;
    @Autowired IMdmSubscriptionService mdmSubscriptionService;

    /** 场景一产出的死信 eventId：场景二重放复用 */
    private static String firstDeadLetterEventId;

    @Test
    @Order(1)
    @DisplayName("消费失败留痕：poison 帧重试耗尽落死信 PENDING，received_event 留 FAILED 行并累加失败次数")
    void poisonFrameLandsAsPendingDeadLetterAndFailedConsumption() {
        String eventId = publishPoisonFrame();

        Map<String, Object> deadLetter = awaitDeadLetterRow(eventId);
        assertThat(deadLetter.get("status")).isEqualTo(MessagingConstants.DEAD_LETTER_STATUS_PENDING);
        assertThat(deadLetter.get("source_queue")).isEqualTo(QUEUE_NAME);
        assertThat(deadLetter.get("replay_count")).isEqualTo(0);

        // Spec §3.2 步骤⑤：失败记录原因；D-7 口径：FAILED 行不得被回查误判为已处理
        awaitUntil(() -> !jdbcTemplate
                        .queryForList(
                                "SELECT retry_count FROM integration.received_event"
                                        + " WHERE event_id = CAST(? AS uuid) AND consumer_module = ? AND status = 'FAILED'",
                                eventId,
                                CONSUMER_MODULE)
                        .isEmpty(),
                "消费失败未落 FAILED 台账行");
        Integer retryCount = jdbcTemplate.queryForObject(
                "SELECT retry_count FROM integration.received_event WHERE event_id = CAST(? AS uuid) AND consumer_module = ?",
                Integer.class,
                eventId,
                CONSUMER_MODULE);
        assertThat(retryCount).as("容器有界重试 3 次应累计失败登记次数").isGreaterThanOrEqualTo(1);

        firstDeadLetterEventId = eventId;
    }

    @Test
    @Order(2)
    @DisplayName("重放闭环：重放后消费成功且业务仅执行一次，FAILED 行升级 PROCESSED，死信置 REPLAYED 并计数")
    void replayClosesTheLoopAndUpgradesFailedRow() {
        FAIL_MODE.set(false);
        Long deadLetterId = deadLetterIdByEventId(firstDeadLetterEventId);
        int businessBefore = BUSINESS_COUNT.get();

        DeadLetterDetailVO replayed = deadLetterService.replay(deadLetterId);

        assertThat(replayed.status()).isEqualTo(MessagingConstants.DEAD_LETTER_STATUS_REPLAYED);
        assertThat(replayed.replayCount()).isEqualTo(1);
        assertThat(replayed.handler()).isNotNull();
        awaitUntil(
                () -> BUSINESS_COUNT.get() == businessBefore + 1,
                "重放帧未被消费者业务处理（或重复进入业务）");
        awaitUntil(
                () -> {
                    String status = jdbcTemplate.queryForObject(
                            "SELECT status FROM integration.received_event"
                                    + " WHERE event_id = CAST(? AS uuid) AND consumer_module = ?",
                            String.class,
                            firstDeadLetterEventId,
                            CONSUMER_MODULE);
                    return MessagingConstants.RECEIVED_STATUS_PROCESSED.equals(status);
                },
                "重放成功后消费台账行未升级为已处理（FAILED → PROCESSED）");
    }

    @Test
    @Order(3)
    @DisplayName("上限与终态守卫：replay_count 达 3 拒绝重放（INT-1003）；关闭留痕后终态禁重放（INT-1002）")
    void replayLimitAndClosedStateAreEnforced() {
        // 上限守卫：构造已达上限的 PENDING 死信（控制器的重推上限口径 = 3 次）
        Long replayedId = deadLetterIdByEventId(firstDeadLetterEventId);
        jdbcTemplate.update(
                "UPDATE integration.dead_letter SET status = 'PENDING', replay_count = ? WHERE id = ?",
                MessagingConstants.DEAD_LETTER_REPLAY_MAX_COUNT,
                replayedId);
        assertThatThrownBy(() -> deadLetterService.replay(replayedId))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_REPLAY_LIMIT_EXCEEDED);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        // 关闭留痕：先把业务开关拨回失败态（@Order(2) 结尾已置 false；不复位则第二帧业务成功、
        // 死信永不落库 → 下方 await 必超时），再另起一帧 poison 并经关闭处置留痕
        FAIL_MODE.set(true);
        String secondEventId = publishPoisonFrame();
        awaitDeadLetterRow(secondEventId);
        Long secondId = deadLetterIdByEventId(secondEventId);
        DeadLetterDetailVO closed = deadLetterService.close(secondId, new DeadLetterCloseRequest("脏数据放弃：测试场景关闭"));
        assertThat(closed.status()).isEqualTo(MessagingConstants.DEAD_LETTER_STATUS_CLOSED);
        assertThat(closed.handleNote()).isEqualTo("脏数据放弃：测试场景关闭");
        assertThat(closed.handler()).isNotNull();
        assertThat(closed.handledAt()).isNotNull();

        // 终态守卫：CLOSED 不得再重放（Spec §5）
        assertThatThrownBy(() -> deadLetterService.replay(secondId))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_STATUS_NOT_ACTIONABLE);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        FAIL_MODE.set(false);
    }

    @Test
    @Order(4)
    @DisplayName("主数据分发流水：订阅登记后广播事件落 mdm_dispatch_log，target_modules 含订阅方")
    void masterDataBroadcastRecordsDispatchLog() {
        mdmSubscriptionService.register(
                new MdmSubscriptionCreateRequest(MdmConstants.TOPIC_DICT, CONSUMER_MODULE, MdmConstants.SYNC_MODE_EVENT_SUBSCRIBE));
        EventEnvelope envelope = codec.create(
                Clock.systemUTC(), "system", EVENT_TYPE, "it-dead-letter-governance", Map.of("dictType", "gender", "version", 7));

        rabbitTemplate.convertAndSend(MessagingConstants.EXCHANGE_TOPIC, EVENT_TYPE, envelope);

        awaitUntil(
                () -> !jdbcTemplate
                        .queryForList(
                                "SELECT id FROM integration.mdm_dispatch_log WHERE topic = ? AND version = ?",
                                MdmConstants.TOPIC_DICT,
                                7)
                        .isEmpty(),
                "主数据广播事件未落分发流水");
        Map<String, Object> row = jdbcTemplate.queryForList(
                        "SELECT dispatch_mode, target_modules FROM integration.mdm_dispatch_log WHERE topic = ? AND version = ?",
                        MdmConstants.TOPIC_DICT,
                        7)
                .get(0);
        assertThat(row.get("dispatch_mode")).isEqualTo(MdmConstants.DISPATCH_MODE_BROADCAST);
        assertThat((String) row.get("target_modules")).contains(CONSUMER_MODULE);

        // 矩阵查询（FU-M20-04 管理面）：登记行经服务读出且对账状态为待对账初值
        assertThat(mdmSubscriptionService
                        .query(new MdmSubscriptionQuery(MdmConstants.TOPIC_DICT, CONSUMER_MODULE, 0, 20))
                        .content())
                .hasSize(1)
                .first()
                .extracting(vo -> vo.reconStatus())
                .isEqualTo(MdmConstants.RECON_STATUS_PENDING);
    }

    /**
     * 发布一帧毒丸信封（payload 含 poison 标记，业务开关 FAIL_MODE 决定是否失败）。
     *
     * @return 该帧信封 eventId
     */
    private String publishPoisonFrame() {
        EventEnvelope envelope = codec.create(
                Clock.systemUTC(), "system", EVENT_TYPE, "it-dead-letter-governance", Map.of("poison", true));
        rabbitTemplate.convertAndSend(MessagingConstants.EXCHANGE_TOPIC, EVENT_TYPE, envelope);
        return envelope.eventId();
    }

    /**
     * 轮询等待死信落库并返回该行视图。
     *
     * @param eventId 信封 eventId，非空
     * @return 死信行字段视图（id/source_queue/status/replay_count）
     */
    private Map<String, Object> awaitDeadLetterRow(String eventId) {
        awaitUntil(
                () -> !jdbcTemplate
                        .queryForList("SELECT id FROM integration.dead_letter WHERE event_id = ?", eventId)
                        .isEmpty(),
                "死信未在等待窗内落库：event_id=" + eventId);
        return jdbcTemplate
                .queryForList(
                        "SELECT id, source_queue, status, replay_count FROM integration.dead_letter WHERE event_id = ?",
                        eventId)
                .get(0);
    }

    /**
     * 按 eventId 取死信主键。
     *
     * @param eventId 信封 eventId，非空
     * @return 死信主键（雪花 ID）
     */
    private Long deadLetterIdByEventId(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM integration.dead_letter WHERE event_id = ?", Long.class, eventId);
    }

    /**
     * 通用轮询等待（200ms 步进，LINK_TIMEOUT 超时）：不引入 awaitility 的仓库既有口径。
     *
     * @param condition 等待条件，非空
     * @param message   超时失败信息（业务语义），非空
     */
    private void awaitUntil(BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + LINK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail(message + "（" + LINK_TIMEOUT + " 超时）");
    }
}
```

（本节代码块已按 A.1-13 消除全部全限定名：`MessageIdempotencyService`/`ReceivedEventRecord`/`StandardCharsets`/`BooleanSupplier`/`Assertions.fail` 均以 import 短名承载；`List` 未使用故不入 import。）

- [ ] **Step 2: 运行确认通过**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-app -am verify -Dit.test=DeadLetterGovernanceIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false | tail -12`
Expected: `Tests run: 4, Failures: 0`（前置任务全部就位 + Docker 运行中）；若报 `mdm_subscription`/`mdm_dispatch_log` 不存在 → 检查 V502/V503 位于 integration 迁移目录且 flyway locations 已含该目录；若死信未落库 → 检查 `integration.mdm_subscription` 队列声明是否与消费者队列名一致（同一组常量拼接）

- [ ] **Step 3: Commit**

```bash
git add backend/fuyun-app/src/test/java/com/fuyun/app/DeadLetterGovernanceIT.java
git commit -m "test(app): 死信重放闭环与主数据分发流水端到端 IT"
```

---

### Task 14: 全量门禁与收口

**Files:** 无新增（验证与收口任务）

- [ ] **Step 1: 格式化**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml spotless:apply`
Expected: `BUILD SUCCESS`（有改动的文件随 Step 3 一并提交）

- [ ] **Step 2: 全量门禁**

Run: `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml verify | tail -8`
Expected: `BUILD SUCCESS`（Spotless + 全部单测 + 全部 IT 含 `DeadLetterGovernanceIT`/`MessagingGovernanceIT`/`ModulithBoundaryTest` + JaCoCo 双阈值；`com.fuyun.integration.service.impl` 四个新 service 全覆盖）

- [ ] **Step 3: 文件卫生与迁移治理自检**

Run: `pre-commit run --all-files 2>&1 | tail -5 && python scripts/check-migration-governance.py`
Expected: 全部钩子 `Passed`；迁移治理输出 `迁移治理校验通过：17 个迁移文件，基线 HEAD`

- [ ] **Step 4: 残留自查**

- 无魔法值散落（列宽/上限/状态值/主题词一律常量来源）；
- 无未使用 import、无注释掉的代码块、无空实现桩；
- 全部新文件 UTF-8 无 BOM、LF、文件末单换行；
- 日志无 payload 原文与凭证（`grep -rn "payloadBody" backend/fuyun-integration/src/main | grep -i log` 应零命中）；
- `git status` 干净（除计划/报告类未跟踪文档外无产物残留）。

- [ ] **Step 5: Commit（若 Step 1 有格式化改动）**

```bash
git add -A
git commit -m "style(backend): spotless 格式化收口"
```

- [ ] **Step 6: 推送与 PR（执行主控操作）**

```bash
git push -u origin feat/p1-pr1b-m20-governance
gh pr create --base dev --title "feat(integration): M20 事件总线治理完整化（PR-1b）"
```

PR 描述必含：① 范围声明（死信管理四端点 / 事件溯源三查询端点 / FU-M20-04 P0 子集 / W-4·W-5·W-6 三项工程债 / D-8 修宪）；② 控制器拍板口径（重推上限 3 次、载体 = 既有 `replay_count` 列，零迁移）；③ A.5-9 引用一致性核验结论（Task 12 Step 5 输出）；④ 跨模块前置登记（FU-M20-04 剩余条目 = TASK.md 新 TODO 行）；⑤ 未覆盖项与理由（`iot.iot_consume_error_log` 查询面归 M14；`POST/DELETE /event-registry`；死信操作 `@AuditLog` 接线）。随后轮询五 checks（勿用 `--watch`，sleep 循环重试）→ `gh pr merge --merge --delete-branch`。

---

## Self-Review 记录（撰写者已执行）

**1. 规格覆盖（五块范围 → 任务映射）**

| 范围来源 | 落点 | 任务 |
| --- | --- | --- |
| 死信管理查询 API（`GET /dead-letters`）+ 诊断载荷 | `DeadLetterController` 列表/详情 + `DeadLetterServiceImpl.query/detail` | Task 2 |
| 失败重推（含留痕与上限）+ 关闭动作 | `replay`/`close`（CAS + 事务外投递 + replay_count 上限 + handler/handle_note/handled_at 留痕） | Task 3 |
| Spec §10 异常「重放失败回 PENDING 并累加计数」 | `markReplayAttempt(PENDING)` + INT-1005 | Task 3（单测）+ Task 13（成功路径 IT） |
| 事件溯源·消费侧（`GET /received-events`，类型/时间/状态检索） | `ReceivedEventQueryServiceImpl` + 端点 | Task 6 |
| 事件溯源·投递侧（投递/重试留痕查询，4 表中 `event_publication`） | `EventPublicationQueryServiceImpl`（只读投影，不映射 serialized_event） | Task 7 |
| 事件溯源·FAILED 登记与 D-7 status 口径 | `settleFailure` + `tryAcquire` status 过滤 + `recordProcessed` 升级 | Task 5 |
| 事件溯源·`event_registry` 查询面（recon 四 API 组之一） | `IEventRegistryService.query` + `EventRegistryController` | Task 8 |
| FU-M20-04 订阅登记（mdm_subscription） | V502 + `MdmSubscriptionServiceImpl` + `MdmSubscriptionController` | Task 9 |
| FU-M20-04 广播链路本模块记分发流水（mdm_dispatch_log） | V503 + `MdmDispatchListener` + 五队列声明 | Task 10 |
| FU-M20-04 矩阵（主题×订阅方×版本×对账状态） | `GET /mdm-subscriptions` 列表 + `recon_status` PENDING 初值 | Task 9（+ Task 13 断言） |
| FU-M20-04 剩余 P0（全量初始化/对账/自动重发/redispatch） | 显式不在范围（M01 前置）+ TASK.md 登记 | Task 9 Step 7（+ 计划范围声明） |
| W-6① 死信留痕钳长（failReason/source_queue/routing_key + event_id/event_type 收口） | `DeadLetterListener` + 列宽常量 + `TextTruncate` | Task 4（+ Task 2 常量/工具） |
| W-6② 订阅登记并发守卫 + broadcast 拒订 | CAS 条件更新 + 自旋 3 次 fail-fast + broadcast 拒订 | Task 8 |
| W-6③ release 异常遮蔽 | `settleFailure`（addSuppressed 双保留）+ 三处消费范式同步 | Task 5 |
| W-5 properties toString 脱敏 | `IotProperties.Amqp/Fallback` + `SecurityProperties` 覆写 + 单测 | Task 11 |
| W-4 迁移治理校验脚本（号段归属/版本唯一/乱序守卫） | `scripts/check-migration-governance.py` + pre-commit + ci.yml | Task 1（+ Task 9/10 自检） |
| D-8 修宪（A.5-9 前缀语法 + 有限重试移交 + CHANGELOG 归源更正） | CHANGELOG 先记 + A.5-9 正文 + javadoc 同步 + TASK.md 回填 | Task 12 |
| P1 验收「重复订阅登记竞态单测」 | `registerSubscriberRetriesOnCasMiss` / `registerSubscriberFailsFastAfterCasAttemptsExhausted` | Task 8 |
| P1 验收「号段校验脚本对既有迁移全绿」 | Step 2（15 文件绿）+ Task 9/10 自检（16/17 文件绿） | Task 1 / 9 / 10 |
| P1 验收「死信→重推→消费成功闭环 IT」 | `DeadLetterGovernanceIT`（四场景） | Task 13 |
| 全局规范「对外 API 100% 单测」 | 5 个控制器薄层单测（含合并类）+ 6 个 service 单测 | Task 2/3/6/7/8/9 |

无规格缺口；未覆盖项均已在「本计划范围声明」显式列出并给出理由。

**2. 占位符扫描**：全文无 TBD/TODO/「适当处理」类空话。三处「二选一/以实测为准」均为**带具体命令与两种确定结果**的验证步骤：Task 2 Step 9（MapStruct 是否选用预览方法，给了表达式写法）、Task 7 Step 4（`mapPublicationStatus` 形参 import 短名）、Task 10 Step 3（`assertThatCode(...).isInstanceOf` 与 `assertThatThrownBy` 二选一）。Task 9 的 TASK.md 编号「W-8（若执行时已变化则顺延）」附当前最大号事实，非开放占位。

**3. 类型一致性核验**：`IDeadLetterService` 四方法（`query`/`detail`/`replay`/`close`）在 Task 2/3/13 三处签名一致；`DeadLetterServiceImpl` 构造器三参形态在 Task 3 定义后，Task 3 单测与 Task 13 IT（Spring 装配）一致；`settleFailure(ReceivedEventRecord, RuntimeException)` 在 Task 5 接口/实现/三处调用/Task 10 新消费者五处一致；`PageResult.of(List,long,long,long)` 在 Task 2 定义后于 Task 3/6/7/8/9 与 IT 一致；`MessagingConstants` 新增常量名在 Task 2 定义、Task 3/4/5/10/13 引用逐字一致（`DEAD_LETTER_STATUS_REPLAYED`/`DEAD_LETTER_STATUS_CLOSED`/`RECEIVED_STATUS_FAILED`/`FAIL_REASON_MAX_LENGTH`/`DEAD_LETTER_REPLAY_MAX_COUNT`/`DEAD_LETTER_PAYLOAD_PREVIEW_LENGTH`/`HANDLER_MAX_LENGTH`/`MODULE`/`PUBLICATION_STATUS_*`/`MDM_TARGET_MODULES_MAX_LENGTH`）；`MdmConstants` 常量在 Task 9 定义、Task 10/13 引用一致（`TOPICS`/`TOPIC_*`/`SYNC_MODE_*`/`RECON_STATUS_PENDING`/`EVENT_*`/`TOPIC_BY_EVENT_TYPE`/`DISPATCH_MODE_BROADCAST`）；表列名（`replay_count`/`handler`/`handle_note`/`handled_at`/`retry_count`/`fail_reason`/`target_modules`/`recon_status`）与 V4/V3/V502/V503 DDL 及实体字段逐一对应；错误码 `INT-1001~1005/1011/1012` 在 Task 2 定义后于 Task 3/8/9/13 引用一致。
