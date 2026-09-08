# CHANGELOG（工程变更记录）

> 记录规则（根 AGENTS.md §7）：**先记再改**——任何宪法 / 规范 / 机制文件的修订，先在本文件登记（日期、范围、理由、裁决），再改正文。追加式保留全部历史。

## 2026-09-09 · P0 交付执行设计落盘 + 定位层地图补 docs/prompt

- 新增 `docs/prompt/2026-09-09-loop-P0工程骨架.md`：以 PLAN-P0-01 为唯一 spec 的交付 loop 执行设计（P0→P6 七阶段门禁、17 个审批批次、TDD+SDD subagent 派遣制、批内修复/PR 审核/终验三级循环、PR 依序合入 dev 流程），供执行会话作为唯一执行依据；定位层仓库地图 §3 同步登记 `docs/prompt/`。
- 用户裁决两项（已录入文档 §8）：端到端测试口径 = 链路级端到端（浏览器 E2E 框架维持计划 §4 排除）；PR 粒度 = 计划五 PR 依序合入 dev。执行文档字数超限分歧经裁决按中文字数口径交付（2217 中文字，总字符含 ASCII 标识符 5853）。

## 2026-09-08 · CI 首跑修正（PR #1 实测暴露，两处）

1. **commitlint subject-case 中文误报**：中文 subject 以大写字母/数字开头（如"P0 实施计划…"）被 config-conventional 的 subject-case 规则误判为 pascal-case/upper-case，PR 检查失败。裁决：关闭 subject-case 规则（对 CJK 文本无实际意义，属该规则设计语境为英文的误伤），其余 conventional 规则全量保留。
2. **路径过滤意外触发构建 job**：PR #1 为纯文档变更，dorny/paths-filter 仍将 backend/frontend 判定为有变更（判定输出与实际文件清单不符），且 `setup-java` 的 `cache-dependency-path` 在 pom.xml 缺失时硬报错、`pnpm/action-setup` 在 package.json 缺失时报"No pnpm version specified"，导致骨架期必红。加固：changes job 增设 `pom`（backend/**/pom.xml）与 `webpkg`（web/**/package.json）存在性过滤器，backend/frontend job 触发条件追加"对应构建文件存在"——骨架期兜底不跑，P0 工程骨架落地后自动恢复触发（语义：有变更且可构建才运行门禁）。

## 2026-09-08 · P0 实施计划落盘 + 定位层地图补 docs/plans

- 新增 `docs/plans/2026-09-08-P0实施计划.md`（PLAN-P0-01）：P0 阶段的 PR 序列（工程骨架 / M20 治理构件 / M01 基础 / M14 骨架 / 收口）、各 PR 交付物与验收标准、决策点与 DoD，供新会话作为执行输入；定位层仓库地图与 §8 同步登记 `docs/plans/`（阶段实施计划）。
- W-6（NVD_API_KEY）经用户裁决撤销：不配置，security.yml 周审以匿名限流运行（TASK.md 同步删除）。
- 首次走 PR 流程合入（分支保护生效后的流程验证）。

## 2026-09-08 · 宪法 v1.1 → v1.2 修订：金额分值制 + 序列化/目录/MQ 消费模式裁决

### 用户裁决记录（2026-09-08）

1. **金额存储改 BIGINT 分值制**（覆盖 v1.0 沿用总 Spec 的 NUMERIC(18,2)）：全部金额列以 `BIGINT` 存"分"，应用层全程 `long`（禁浮点）；分↔元换算集中统一工具（convert 层 MoneyUtil），禁止散落 \*100 / ÷100；对外序列化以字符串承载。总 Spec §4.4 权威行本次同步修订；12 个模块 Spec 中 NUMERIC(18,2) 表述的批量同步登记 TASK.md（W-4）。
2. **JSON 序列化精度防线**：Jackson 全局注册 Long → String（ToStringSerializer）——雪花 ID 与金额分值超出 JS Number.MAX_SAFE_INTEGER（2^53）的部分前端以字符串接收，杜绝精度丢失；前端对应条款（金额/长整型一律字符串承载）同步进 web 宪法。
3. **注解优先**：能用注解 / 框架声明式能力解决的，禁止手写样板代码。
4. **RabbitMQ 消费确认改 AUTO 模式**（覆盖 M20 Spec §3.2/§7/§9 的"手动确认"选定）：@RabbitListener 注解驱动消费 + 容器 AUTO 确认（方法成功返回即确认、异常按重试策略 nack）+ 有界重试 + fy.dlx 死信 + 幂等不变——AUTO 语义等价"业务成功才确认"，并消除 MANUAL 模式漏写 ack 的实际事故源；M20 Spec 表述同步登记 TASK.md（W-5）。
5. **目录规范补充**（backend）：dto/（入参 DTO）· vo/（前端返回）· record/（特殊处理实体对象）· enum/（枚举一律 enum 类型，禁常量类模拟）· cache/（按业务领域的复杂缓存设计，{Domain}CacheService）· constants/（全部常量，常量类 public final static + 构造器私有）。
6. **Lua 脚本规范**：resources/lua/ 目录集中存放；经 Redisson RScript **预注册**（应用启动 SCRIPT LOAD 全部脚本缓存 SHA 单例，运行时 EVALSHA 调用，NOSCRIPT 异常回退重载）；所有原子性脚本使用的 key 必须携带 `{业务功能}` hash tag（Redis Cluster 语义，保证同一业务的 key 命中同一实例）。

### 同步执行记录（2026-09-08，W-4 / W-5 落地，用户裁决即批准）

- **模块 Spec 金额表述批量同步**：13 个文件 47 处（README 权威行 + 12 个模块 Spec 的表设计约定行/字段表/自审清单），统一为"BIGINT 分值制 / BIGINT，分"；14-iot 遥测 `value(NUMERIC)`、17-peis `result_form(NUMERIC)`、19-ops 指标值 `NUMERIC(18,4)` 等非金额 NUMERIC 不属本裁决范围，保留。
- **RabbitMQ 确认模式表述同步**：M20 §3.2/§3.4 共 6 处、14-iot 事件订阅 1 处、15-asset 事件订阅 1 处——"手动确认"改为"@RabbitListener 注解驱动 + 容器 AUTO 确认（语义等价'业务成功才确认'）"；IoTDA AMQP（Qpid JMS）客户端确认不在裁决范围，保持不变。
- **仓库分支保护**（原 W-2）：经 gh api 直接配置（required status checks 五项与 job 名精确对齐 + require PR + 禁止绕过），配置完成后删除对应工单。

### 与既有文档的差异说明

- v1.1 中"金额 NUMERIC(18,2) + BigDecimal"条款作废，A.4.2-8 重写；A.3 新增序列化条款；A.5-4/5 消费确认表述更新；B.1/C.3 目录表扩充。
- R2/R3 调研报告中 NUMERIC(18,2) 与"MANUAL ack"相关条目作为历史调研档案不改，以本 CHANGELOG 裁决为准。

## 2026-09-08 · 宪法 v1.0 → v1.1 修订：ORM 定稿 + 分层细化 + 命令呈现优化

### 用户裁决与修订要求（2026-09-08）

1. **ORM 定稿（TASK.md D-1 销项）**：MyBatis + MyBatis-Plus 协同——单表链式编程用 MP，复杂 SQL 走 mapper + XML。版本经官方核实：`mybatis-plus-spring-boot3-starter:3.5.17`（Boot 3 必须用 spring-boot3 后缀 starter，MP 3.5.16 起官方基线对齐 Boot 3.5 线）+ `mybatis-plus-jsqlparser:3.5.17`（3.5.9 起分页插件必需）；官方安装页明确禁止再引入 mybatis / mybatis-spring / mybatis-spring-boot-starter（MP starter 已内含 mybatis 3.5.19 + mybatis-spring 3.0.5）；Boot BOM 不托管，父 POM 锁版本。
2. **编码新条款**：禁止全限定类名声明（import 后用短类名）；禁用 @Deprecated API。
3. **C.4 常用命令呈现**：表格 → 代码块 + 精简注释（backend 与 web 两侧）。
4. **分层细化**（参考 commerce-customer 宪法）：B.1/B.2 吸收 mapper/entity 数据层归位、Service 接口+实现模式（IService/ServiceImpl）、循环依赖拆层切断禁 @Lazy 掩盖、跨 service 仅经接口复用查询；repository 表述统一为 mapper。

### 用户补充要求（同日追加）

5. **Redisson 正式纳入技术栈**：Redisson 4.7.0（redisson-spring-boot-starter，配套 redisson-spring-data-35，父 POM 锁版本）为正式选型组件，用于 watchdog 自动续期 / 可重入 / 读写锁等完整分布式锁语义；毫秒-秒级短持锁仍可选 BOM 托管的 RedisLockRegistry；防超卖最终由数据库唯一约束兜底。
6. **前端目录规范与层级依赖同颗粒度细化**：web 宪法 B.1 目录职责表与 B.2 层级依赖图按后端同等标准重写（views→components→composables→api/stores 单向依赖、types 全层可引用、跨 app 复用必须下沉 packages、禁反向与循环）。

### 与调研建议的差异说明

- R2 §8 调研主选为裸 MyBatis（mybatis-spring-boot-starter 3.0.5）；用户裁决升级为 MyBatis-Plus 协同方案（MP 为 MyBatis 增强层，包含并替代裸 starter），调研报告作为历史档案不改。
- T-R2-2（ORM 实体 equals/hashCode 与 Lombok 冲突）销项：MyBatis-Plus 实体无 JPA 持久化上下文代理语义，且宪法 A.1 已禁止 @Data 用于实体，风险已被覆盖。
- T-R2-5（log-impl 日志细节）保留，回填时点更新为 P0 实施期（MP SQL 日志经 mybatis-plus 配置项承载）。

## 2026-09-08 · 宪法体系 v1.0 初版生成 + CI 机制（方案 B）批准落地

### 变更范围

- 新增根定位层 `AGENTS.md`、子项目宪法 `backend/AGENTS.md` 与 `web/AGENTS.md`、根索引 `CLAUDE.md`、登记台 `TASK.md`。
- 调研依据（`docs/agmds-research/`，5 份，均经来源真实性评审）：CI 链方案、Java 与 SpringBoot 栈、数据与集成基础设施、前端栈、构建测试与 CI 落地细则。

### 用户裁决记录（2026-09-08）

| 裁决 | 内容 |
| --- | --- |
| CI 方案 | 选定方案 B「严格门禁」（5 job 全机器阻断）；方案 C 安全增强（CodeQL/Trivy/dependency-review 等）列为二期演进 |
| 落地范围 | CI 机制全套文件随本次交付（workflow / pre-commit / commitlint 等，路径过滤保证无代码阶段不空跑） |
| 命名模式 | agent 模式：AGENTS.md 承载宪法实体，CLAUDE.md 仅作根索引 |

### 合成裁决记录（宪法撰写时依调研报告"定稿时二选一"项作出，可经修宪流程推翻）

| 裁决 | 选择 | 理由 |
| --- | --- | --- |
| API 响应模式 | 成功 2xx 裸数据 + 失败一律 ProblemDetail（RFC 9457，properties.errorCode/traceId） | REST 状态语义化、openapi-typescript 契约链路类型最干净；envelope 候选因无权威规范且与 HTTP 语义冗余弃用 |
| JaCoCo 门禁 | 每模块 check 模式（BUNDLE LINE ≥0.80 + 核心包 PACKAGE 1.00）；聚合报告仅只读 | 官方 issue #902：check 不支持聚合报告；逐模块失败点可定位 |
| Spotless 格式化器 | palantir-java-format | Java 17 红线：google-java-format ≥1.22 需 JDK 21 |
| 前端覆盖率 | report-only 起步，不设硬阈值 | 金额计算全服务端（D5）；硬阈值易催生空断言测试，违反全局规范 |
| 前端单测框架 | Vitest 4.1.11（非 latest 5.0.0） | 5.0.0 GA 不足一季度，违反技术栈选型原则 2 |
| 并发锁 | Spring Integration RedisLockRegistry（BOM）+ DB 唯一约束兜底 | BOM 托管优先；号源/床位锁为短持锁场景 |
| 定时任务 | @Scheduled + ShedLock 6.10.0 | 多实例互斥最小方案；Quartz 本期不引入 |

### 评审修正记录

- 5 份调研报告经评审：3 份直接通过；2 份退回定点修正（JaCoCo "Boot BOM 托管"断言不实——经 spring-boot-dependencies/parent-3.5.16.pom 直查均无 jacoco 条目，已更正为"父 POM pluginManagement 显式锁定 0.8.15"；另修正 Spotless 版本表述、paths-filter latest 表述、withVueTs API 对齐、排除项笔误）。16 项来源 URL 抽查全部真实。
- 宪法体系经审核专员终审：修复 1 项 MAJOR（backend A.4 引用 TASK 编号断链）与 4 项 MINOR（B.5 槽位省略声明、HTML 实体写法、web B.3-4 四主题清单改引用式、TASK 补登 T-R2-5）后通过。

### CI 机制落地记录（同日，用户已批准）

- 落盘 12 件：`.github/workflows/ci.yml`（6 job 主链，images 按"谁变更构建谁"的 matrix 条目级条件）与 `security.yml`（OWASP 周审）、`.pre-commit-config.yaml`、`commitlint.config.mjs`、`scripts/check-encoding.py`（UTF-8 无 BOM/乱码/CRLF 三查）、`.gitattributes`/`.gitignore` 补强、`backend/Dockerfile` 与 `web/Dockerfile` 及各自 `.dockerignore`（P0 骨架后方可实际构建）。
- 验证：pre-commit 4.6.2 `run --all-files` 9 钩子全部通过（含 actionlint 校验 workflow）；check-encoding.py 对三类违规注入样本测试通过；既有文件零卫生违规。
- 有意偏差（相对 R5 蓝本）：frontend job 的 pnpm/action-setup 补 `package_json_file: web/package.json`（monorepo 在子目录，action 默认读根 package.json 会失败）；images 门禁由"两构建 job 全 success"细化为"谁变更构建谁"（job 级至少一侧成功 + 构建步骤按 matrix 条目校验对应变更与 verify 结果），修复纯前端 PR 失去 web 镜像构建验证的缺口。
