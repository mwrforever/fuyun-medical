# TASK.md（登记台）

> 登记规则（根 AGENTS.md §7）：`{待调研项}`（检索不可得，注明原因与回填时点）、`{待决策项}`（需用户 / 总 Spec 裁决）、TODO 工单。条目回填后即删除；来源编号对应 `docs/agmds-research/` 调研报告（R1=CI链方案、R2=Java与SpringBoot栈、R3=数据与集成基础设施、R4=前端栈、R5=构建测试与CI落地细则）。

## 待决策项（阻塞对应工作，须先裁决）

| 编号 | 事项 | 背景 / 调研建议 | 影响范围 |
| --- | --- | --- | --- |
| D-3 | iot-simulator 实现语言与代码位置 | **已按默认裁决执行（2026-09-10，用户未响应 ask_question，取计划默认项，可推翻）**：Java + Maven 子模块 `backend/iot-simulator`，复用父 POM 工具链与 Dockerfile 策略，images job 第三构建步骤同构追加；若用户改判 Node 顶层目录，改动面=simulator 模块位置+独立 Dockerfile+CI 构建步骤+compose 镜像名 | CI images job、deploy compose、PR-4 B4.4 |
| D-4 | OWASP 周审失败处置流程 | R5 §5-4：fail 后开 issue 还是仅通知，属流程决策 | security.yml |
| D-5 | 方案 C 二期演进（CodeQL / Trivy / dependency-review / Renovate/Dependabot / SonarQube） | R1 §3.3/§4：用户已定方案 B 上线基线、C 为二期；SonarQube 若引入需 CI 双 JDK（17 构建 + 21 扫描）；Renovate vs Dependabot 二选一（倾向 Renovate 的 monorepo 分组能力） | security.yml 扩展、仓库设置 |
| D-6 | 宪法 `enum/` 包目录命名与 Java 保留字冲突 | **已按默认裁决执行（2026-09-09，用户未响应 ask_question，取推荐项，可推翻）**：枚举包目录改 `enums/`——B.1/C.3 正文随 PR-3 修宪提交更新，已建的 `enum/.gitkeep` 目录同步改名；若用户改判 enumeration/ 或其他，改动面=目录名+包名+宪法正文，一次替换可回收 | backend 全部 20 模块目录结构、PR-3 枚举类落位 |
| D-7 | 幂等前置去重 NX 误判丢消息窗口的补救策略 | **已按默认裁决执行（2026-09-09，用户未响应 ask_question，取推荐项，可推翻）**：消费范式改为「Redis NX 失败时回查 received_event 表（唯一索引查询），确认已处理才跳过」——彻底消除丢消息窗口、保持 at-least-once；代价为每条重复消息一次 DB 点查。若用户改判缩短 TTL 或维持现状，改动面=MessageIdempotencyServiceImpl 单类+单测 | M20 幂等构件消费范式、PR-3 起全部 @RabbitListener 消费者 |
| D-19 | 押金预警阈值缺省分叉：`BillingProperties` 代码缺省 10000 分 vs `application.yml` 500000 分（运行期 yml 生效） | PR-3 Task 16/17 执行期已裁定保持现状（Task 16 审查官：brief 模板 500000 与计划 Global Constraints 互斥时以代码缺省 10000 为准合规；Task 17 yml 键 `${FUYUN_BILLING_DEPOSIT_WARNING_THRESHOLD_FEN:500000}` 显式注入使代码缺省生产休眠）——两处缺省口径不一致移交用户裁决：①统一对齐代码缺省（yml 删显式默认值）②统一对齐 yml（改 `@DefaultValue` 为 500000）③维持现状并注记 yml 为唯一权威、代码缺省仅编译期兜底 | backend/fuyun-billing properties/BillingProperties、backend/fuyun-app application.yml `fuyun.billing.deposit-warning-threshold-fen`、预交金开户欠费预警行为（13-billing §6 FU-M13-04） |

## 待调研项（检索不可得 / 需实测，回填后删除）

| 编号 | 事项 | 来源 | 回填时点 |
| --- | --- | --- | --- |
| T-R2-1 | 国内 HIS 行业 ORM 主流度权威统计 | R2 T-1 | 已无决策影响（ORM 已定稿 MyBatis-Plus），仅存档 |
| T-R2-3 | 统一 envelope 响应模式权威出处（本宪法已裁决走 ProblemDetail 路线，仅存档） | R2 T-3 | 已闭环 |
| T-R2-5 | MyBatis-Plus SQL 日志配置细节（MP 配置项承载，替代裸 MyBatis log-impl） | R2 T-5 | P0 实施期 |
| T-R3-1 | `CREATE INDEX CONCURRENTLY` 在 Flyway 11.7.2 的事务外执行兼容性 | R3 T1 | P0 实测 |
| T-R3-4 | `fy.delay` quorum 队列 TTL+DLX 到期转发时延压测 | R3 T4 | P0 实测 |
| T-R3-5 | IoTDA 单消息 ≤0.5KB 限制对遥测报文分片的影响 | R3 T5 | 与 14-iot Spec 联动 |
| T-R3-6 | HAPI MLLP `stop()` 与 `stopAndShutdown()` 排空语义差异 | R3 T6 | 联调期实测 |
| T-R4-1 | Axios 新官方站（axios.rest）实例/默认配置页稳定 URL | R4 §8 | A.3 引用归位 |
| T-R5-1 | Testcontainers 官方无 GHA 专页（以 runner-images 预装 Docker 为依据），首跑 verify 实测 | R5 §5-1 | CI 首跑 |
| T-R5-2 | palantir-java-format 在 spotless 3.4.0 的内置默认版本号 | R5 §5-2 | 本地首跑 spotless:check 确认 |
| T-R5-3 | pre-commit-hooks 官方钩子具体 tag（当前 v6.0.0 已核实，后续 autoupdate 锁定） | R1 §5 | 实施期 `pre-commit autoupdate` |

## TODO 工单

| 编号 | 事项 | 说明 |
| --- | --- | --- |
| W-7 | 非数值遥测入库存储（D-9 裁决落地） | 2026-09-14 用户裁决：非数值且需要的数据像数值型一样提取转换入库——iot_telemetry 新增文本承载列（iot 号段新迁移，禁改已应用迁移），TelemetryIngestServiceImpl 不再丢弃非数值行（skip_non_numeric 丢弃口径退役），非数值标量以原文承载、对象/数组以紧凑 JSON 文本承载；quality 维持 isNumeric→BAD 标注（语义=非数值定型标注，不阻断）；value NUMERIC 列仅数值定型行填写。**P1 PR-1 开工前 fix PR 闭合（闭合时删除本行）** | fuyun-iot V404+ 迁移、TelemetryIngestServiceImpl、TelemetryFrameParserTest/IotTelemetryPipelineIT |
| W-8 | FU-M20-04 剩余 P0 条目（全量初始化 / 每日版本对账 / 落后自动全量重发 / `POST /mdm/redispatch`） | **跨模块前置阻塞**：依赖 M01 版本化回源与重发接口（当前 M01 仅 `GET /api/v1/system/dicts/{type}?version=` 覆盖字典，org/user/param/practice 无版本化读接口，PR-1b 侦察报告 §Gap 实证）。PR-1b 已交付订阅登记 + 广播链路分发流水 + 矩阵查询；对账状态 `mdm_subscription.recon_status` 现仅 PENDING（待对账），其余取值与自动重发随 M01 接口就绪后引入 | fuyun-integration V504+ 迁移（如需）、MdmSubscriptionServiceImpl、M01 接口 |
| W-9 | DeadLetterListener 同一 eventId 重复落行收敛 | **PR-1b 终审建议登记**：V4 迁移定案口径允许同一死信重复投递重复落行（dead_letter 无唯一约束，同一 eventId 可因不同消费者多次死信），DeadLetterListener javadoc 原有「P1 死信管理界面完整化时收敛」承诺在 PR-1b（即 P1 完整化）交付后仍未兑现；收敛动作（如补唯一约束或落行去重）归 FU-M20-06 死信告警完整化或后续工单 | fuyun-integration DeadLetterListener、dead_letter 表约束（如需）、相关单测 |
| W-10 | iot_binding.visit_id BIGINT 与 CF-3 visit_id 14 位字符串类型冲突 | CF-3 冻结的 visit_id = `O\|I + 8 位日期 + 5 位流水` 定长字符串（00-implementation-order §5 CF-3 / 02-patient §3.4），V400 已应用不可改（宪法禁改已应用迁移）；现库演示夹具行（id=900001，patient_id=1/visit_id=1）为纯外键占位语义、无引用目标（PR-2 调研 §1.4 实证）。M14 P2 实装时经 iot 号段 V404+ 迁移改造（改列类型 + 夹具行处置），届时同步 14-iot Spec | fuyun-iot V404+ 迁移、IotBinding 实体 |
| W-11 | @Externalized 桥接范围设计评审（宪法 B.3-2 预留）+ RabbitTemplate 回调单槽位覆盖 | 宪法 B.3-2：「@Externalized 桥接范围经设计评审定稿后再修订本条」。PR-2 患者八事件按 SystemEventPublisher 范式 AFTER_COMMIT 直发 fy.topic，不依赖 @Externalized；待 Modulith 注册表与 RabbitMQ 双通道职责边界评审定稿后修宪。并项评审（审查 I4）：共享 RabbitTemplate 的 Confirm/Returns 回调为单槽位——system/patient 两发布器构造期各自 setConfirmCallback/setReturnsCallback，后注册者覆盖前者（bean 初始化顺序漂移），nack/退回告警承载方不固定；评审 multicast/组合注册或各模块独立 template | backend/AGENTS.md B.3-2、SystemEventPublisher/PatientEventPublisher |
| W-12 | patient 号段 V100–V199 仅承载初始化批次，后续迁移一律 V500+ | 号段初始化豁免仅作用于 schema 基线零迁移的首个批次（PR-2 V100–V105）——批次合入后追加 V106+ 被乱序守卫全局规则拦截（审查 I6，E1 场景四实测）；V106–V199 号段余量保留不使用，patient 后续迁移先在 CHANGELOG 登记 V500+ 再落文件 | scripts/check-migration-governance.py、fuyun-patient 后续迁移（V500+） |
| W-14 | docs/language/2026-09-07-技术栈选型.md §6 残留 minio 9000 端口引用勘误 | 实为容器内端口语境（P-1 已明示网络内引用不变），措辞易混淆、非阻塞——下次触碰 docs/language 时顺带澄清（终审 T2-①b，2026-09-18） | docs/language/2026-09-07-技术栈选型.md §6 |
| W-15 | billing.insurance_call_log.request_digest VARCHAR(512) 单侧未钳制 | record() 已钳 responseDigest（列宽 512）、request_digest 同款待处理——PR-3 写方恒短无触发路径，P5 真实医保通道接入前须双侧同钳或收窄 javadoc 契约（终审 T15-①b，2026-09-18） | fuyun-billing InsuranceCallLogServiceImpl.record、V604 insurance_call_log |
| W-19 | billing 退费分级「部分退 → 一级审批」维度未落地 | 同日部分退且金额≤免审阈值仍走免审（沿用 PR-3 现状）；Spec :136 列有该维度；判定口径待产品澄清（部分退金额 vs 原单金额）后补 | fuyun-billing RefundServiceImpl.resolveApprovalLevel、V606、相关单测 |
| W-20 | billing PaymentLine.amount @Positive 使 0 元结算收窄为 400 | PriceDraftRequest.price 允许 0 价项目→0 额费用行→preview total=0→前端落 amount=0 被 400 拒；0 价项目/0 元结算业务口径待澄清（preview 前置拒或前端提示或放开 0 行） | fuyun-billing PaymentLine、SettlementServiceImpl、web PricingSettleView |
| W-21 | system dict 给药途径/用药频次两类字典种子缺失 | 2026-09-18 核查实证缺失且 system 段低号新迁移 V304/V504 均被乱序守卫拦截（基线全局最大 V606，禁试）；用户 2026-09-19 追加裁决改判 **PR-4 顺手预置**——system 段通用号 V607（607>606，号段 (500,None) 合法）随本 PR Task 2 Step 1b 落地（给药途径 15 条+用药频次 10 条=25 条，PUBLISHED 直落，type_code=medication.route/medication.frequency），本行于 Task 13 Step 2b 回填删除（销项凭据=V607 迁移合入+真栈探针 25 行断言绿）；给药途径主校验维持 drug.route_codes（PH-1015 不变），字典供前端下拉与 M01 管理面维护 | Task 2 V607 迁移（本 PR）、M06 P3 审方引擎字典消费 |
