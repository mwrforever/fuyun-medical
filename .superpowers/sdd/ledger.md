# SDD ledger — plan: docs/plans/2026-09-08-P0实施计划.md

> 本台账为 P0 交付 loop（执行依据：`docs/prompt/2026-09-09-loop-P0工程骨架.md`）的状态续传载体：每过一个门禁即更新，中断后从台账 + git log 恢复；完成声明必附证据。

## 阶段状态总表

| 阶段 | 内容 | 状态 | 证据 / 说明 |
| --- | --- | --- | --- |
| P0 | dev 分支与门禁准备 | complete | dev 自 origin/main(a10369e) 建立；gh api 证实 dev 保护 = main 五 checks（strict=true、enforce_admins=true、禁 force push/删除），见下「门禁证据」 |
| P1 | PR-1 工程骨架（B1.1~B1.4） | complete | PR #4 合入 dev（ed5e34e）；五 checks 绿（backend verify/frontend verify/images 双镜像/commitlint/hygiene）；/code-review 2 findings 均核实修复；CI 缺陷修复 2 项（buildx 安装、images 去 matrix 化保住必需检查名） |
| P2 | PR-2 M20 治理构件（B2.1~B2.3） | complete | PR #5 合入 dev（a019f47）；五 checks 绿；批次审核四轮独立审查全过；PR 时点 /code-review 未单独派遣（流程偏差已登记交接 §2.3，P6 终验对 diff ed5e34e..a019f47 补跑） |
| P3 | PR-3 M01 系统与权限（B3.1~B3.4） | complete | PR #6 合入 dev（a93179a）；五 checks 绿 + /code-review findings 修复（efd674b/4e577d5）；JaCoCo 双核心包 LINE=1.00（203 单测+21 IT） |
| P4 | PR-4 M14 IoT 通路（B4.1~B4.4） | complete | **PR #7 合入 dev（9107f92）**：五 checks 绿（backend verify 4m9s 含 380 测试、images 三镜像真跑绿含 iot-simulator 第三镜像、commitlint/hygiene 过，frontend skip=web 零变更）；/code-review 2 findings 修复+复审 PASS（Critical：CLIENT_ACK 累计确认丢数→失败即重建会话+真实 broker 重投 IT RED/GREEN 实证；Important：simulator MQTT 凭证缺失→补 username/password）；CI 一轮 flaky 攒批测试加固（失败收尾闩锁同步，10/10 绿）；commitlint 3 提交 body 超长 rebase reword 修正（树零变化）；终态 342 单测+38 IT、三核心包 LINE=1.00；延后条款 L-1~L-4 登记 TASK.md |
| P5 | PR-5 收口（B5.1~B5.2） | complete | **PR #8 合入 dev（86e1420，2026-09-11）**：CI run 34646796652 六 job 全 SUCCESS（backend verify/frontend verify 含 audit/images/commitlint/hygiene）；B5.1 前端六门禁 53 例全绿 + code-review 4 findings 修复复审 PASS；B5.2 W-3 销项 + T-R3 回填核对 + DoD 预检报告落盘；CI 守卫缺陷同 PR 修复（2548043） |
| P6 | 终验报告 | complete | 终验报告 docs/prompt/2026-09-09-P0终验报告.md 经 docs PR 合入 dev；DoD 七条：1/2/4/5/6 ✓ 附命令输出证据（compose 七服务 healthy、后端 343+39 全绿三核心包 1.00、前端六门禁 53 例、gh api 双分支保护逐字段一致、CF-1/2/7 断言在位），第 1 条 sim 演示延后豁免（L-1~L-4），第 3 条 5/5（PR-2 补跑通过，3 Minor→W-6），第 7 条台账完整+停留 dev；PR-2 补跑缺口、PR-5 CI 守卫缺陷修复、commitlint reword 四项流程偏差处置已记录报告 §3；合并后本地停留 dev，P0 主循环退出 |

## 冲突扫描记录（P0，2026-09-09）

逐项核对 loop 文档 / PLAN-P0-01 / 根 AGENTS.md / backend·web 宪法 / 技术栈定稿：

1. **nginx 静态资源 bind mount 路径**：定稿 §6.6 片段写 `../../web/workstation/dist`（且片段缺 portal、路径缺 `apps/` 段）；web 宪法 C.3 与 TASK.md W-3 对齐项②已裁决「产物路径以 `web/apps/<app>/dist` 为准」。→ 按 W-3 既有裁决执行：compose 内写 `../web/apps/<app>/dist`（相对 deploy/），portal 一并挂载，无需用户再裁决。
2. **前端门禁步数**：loop 文档称「前端五门禁」，web 宪法 C.5-1 为六步（lint / format:check / type-check / test / build / audit）。→ 以宪法与 CI 为准六步全跑，loop 五门禁为概称，不构成冲突。
3. **版本口径**：Boot 3.5.16、MP 3.5.17（含 jsqlparser 3.5.17）、jacoco 0.8.15、spotless 3.4.0、ArchUnit 1.5.0、ShedLock 6.10.0、qpid-jms 2.11.0、TimescaleDB 镜像 2.29.2-pg16、redis 8.10.1、rabbitmq 4.3.5-management、minio RELEASE.2025-09-07T16-13-09Z、nginx 1.30.4、pnpm 12.3.4、Node 24、Vitest 4.1.11——loop 文档 / 计划 / 双宪法 / 定稿四方一致，无表外依赖。
4. **CI 五 checks 名称**：`backend / verify`、`frontend / verify`、`images`、`commitlint`、`hygiene` 与 main 分支保护（gh api 实测）一致。
5. **决策点**：D-2（P3 前）、D-3（P4 前）与 TASK.md 待决策表一致；T-R3-2 为实测项非用户决策。

结论：无遗留分歧，无需阻塞问询。

## 门禁证据（P0）

- dev 分支：`git push -u origin dev` 成功（origin/dev 已建）。
- dev 保护 gh api GET 输出：`contexts=["backend / verify","frontend / verify","images","commitlint","hygiene"], strict=true, enforce_admins=true, pr_reviews=0, allow_force_pushes=false, allow_deletions=false`——与 main 逐字段一致。

## 批次明细（P1~P5 推进时逐批回填）

| PR | 批次 | 状态 | 备注（专员 / 测试 / 审核结论） |
| --- | --- | --- | --- |
| PR-1 | B1.1 后端骨架：父 POM/插件管理、common/app、20 域占位 | complete | 462 文件 3 提交；mvn test 10/10 绿+spotless 绿；审核 SPEC/QUALITY 双 PASS（3 Minor 非阻断） |
| PR-1 | B1.2 冒烟 IT + Dockerfile COPY 重写 | complete | SmokeStackIT 3 断言 + verify 全绿 + 镜像构建成功；审核双 PASS（classifier=exec 与构造器 @Autowired 经实证必需） |
| PR-1 | B1.3 deploy/ 全量 + .env.example + ci.yml 移除排除项 | complete | 五服务两次起停全 healthy+nginx -t 过；审核双 PASS（4 项申报偏差全部成立） |
| PR-1 | B1.4 web monorepo + 三 app + shared/ui + 冒烟单测 | complete | 六门禁全绿+3 单测；审核双 PASS（1 Important 已修复 eabd153）；docker build 待 images job |
| PR-1 | PR-1 收尾：compose 七服务全 healthy + 起停二次 + 全分支终审 | complete | 六服务两轮起停全 healthy（nginx 探针缺陷已修）；终审 FAIL→修复 C-1（reword 4 提交树一致）/I-1（vite base）/M-1/M-2 后达 PASS 条件；D-6（enum 保留字）已登记 TASK.md |
| PR-2 | B2.1 事件信封+Long→String 序列化；队列声明构件+event_registry 登记 | complete | EventEnvelope 七字段 record+codec+Jackson 定制器；QueueGovernor 统一声明；V1-V2 迁移；审核双 PASS |
| PR-2 | B2.2 received_event 幂等构件+dead_letter 落库告警 | complete | V3 幂等（两层语义）+V4 死信+死信监听器；幂等单测全覆盖；审核双 PASS |
| PR-2 | B2.3 首批事件名+CF-7 登记；发布→消费→重复拦截→死信 IT | complete | 五事件名（M01 Spec §7 逐字）+占位载荷+CF-7；MessagingGovernanceIT 5 步；integration 核心包 LINE=1.00；宪法回补 v1.3 |
| PR-2 | PR-2 收尾：终审+PR 合入 | complete | PR #5 合入 dev（a019f47）；全分支终审过；PR 时点 /code-review 缺口→P6 补跑（交接 §2.3） |
| PR-3 | B3.1 RBAC 迁移（V300/V303）+令牌签发校验+Redis 会话（D-2） | complete | D-6 修宪 enums/（宪法 v1.4）；12 枚举；HMAC 令牌+会话构件单测全覆盖 |
| PR-3 | B3.2 登录端点+401 拦截；字典管理+dict.published 广播+D-7 幂等改造 | complete | 三端点+AuthTokenInterceptor；AFTER_COMMIT 广播+消费者；D-7 回查语义落码 |
| PR-3 | B3.3 审计切面+practice/check 骨架+AuthFlowIT 7 步+DictBroadcastIT 6 步 | complete | @AuditLog 双路径+SensitiveMasker 数字边界；两 IT failsafe 绿；system 核心包 LINE=1.00 |
| PR-3 | B3.4 workstation 登录页+主布局+Axios 单例+路由守卫 | complete | 前端六门禁绿（19 测试）；expiresIn 契约 string 承载（B3.4 审核修复） |
| PR-3 | PR-3 收尾：终审+PR+/code-review+合入 | complete | PR #6 合入 dev（a93179a）；PR 审查修复字典条件更新与脱敏边界（efd674b/4e577d5） |
| PR-4 | B4.1 iot 迁移 V400–V403 + 遥测超表 + T-R3-2 实测锁定 + IotMigrationIT | complete | 7 提交（f1246ac..d0f0476）；T-R3-2=add_columnstore_policy（2.29.2 为 PROCEDURE 须 CALL，compression 自 2.18 弃用）；mvn verify 全绿 203 单测+26 IT；审核 SPEC/QUALITY 双 PASS（2 Minor：V401 头补齐已修、IT 基座裁剪判合规）；偏差申报：fuyun-app pom 前移 fuyun-iot 依赖、MessagingGovernanceIT 冻结断言 7→8 改造 |
| PR-4 | B4.2 领域基座+Qpid 消费链路（枚举/实体/解析/三服务/消费者/攒批/配置装配） | complete | 11 提交（5d61296..1fc9fcd）；审核双 FAIL→修复循环 1 轮（phase 对调落实 A.5-15 停机契约、整批非数值空防御消除毒帧重投、措辞 2 处）→ scoped 复审 PASS；277 单测+29 IT 全绿；核心包 com.fuyun.iot.service.impl LINE=1.00（116 行）；偏差申报：枚举 8 个（简报计数笔误）、非数值 value 跳过+告警、IT 队列 /queues/ 前缀（RabbitMQ 4.3 实测）、管理 API 预声明 quorum 队列、iotAmqpClock Bean、攒批时间窗批首起算（口径申报随 PR 描述）；遗留登记：properties record toString 脱敏（TASK.md）、fuyun-integration 依赖 B4.3 补 |
| PR-4 | B4.3 TokenVerifier 跨模块小改+扇出自事件消费+STOMP+HTTP 兜底+双指标 | complete | 9 提交（b10231b..c272e70）+修复 4 提交（2603eee..4d6230a）；审核 SPEC FAIL→修复循环 1 轮（摘要推送移出事务 afterCommit 化、设备状态事件以档案 ward_id 补全恢复推送链路、IT 步骤 5/6 恢复全链断言）→ scoped 复审 PASS；311 单测+33 IT 全绿（IotTelemetryPipelineIT 7 步）；核心包 LINE=1.00（173 行）；偏差申报：发布确认回调共享单槽（Spring AMQP 单回调限制，iot 复用 SystemEventPublisher 告警通道）、sink 接线 ObjectProvider 破装配环、IotMigrationIT 零订阅断言改 contains iot、STOMP 握手鉴权经 WebSocketHttpHeaders+Configurator 透传真实全链、IT 订阅确认以时序保证替代 RECEIPT、摘要推送接线点=ingest 落库后分组（afterCommit 化后合规）；Minor 备案：nack 单测移位 SystemEventPublisherTest、步骤 7 同步断言 |
| PR-4 | B4.4 iot-simulator 子模块+images 第三构建步骤+断链重连 IT（T-R3-3 实测） | complete | 6 提交（77cd627..0961fe4）；审核 SPEC/QUALITY 双 PASS（4 Minor：onException 直调单测随终审补、CHANGELOG 归源修正与宪法 A.5-9 正文同步登记 TASK.md D-8、simulator 测试 shutdownHook 幂等备案）；334 单测+37 IT 全绿（IotAmqpReconnectIT 4 例真实 stop_app/start_app 断链恢复）；simulator BUNDLE 0.852；双镜像本地构建成功；实测修正：一机一密=官方 HmacSHA256(UTC yyyyMMddHH, secret)（偏离简报预判，官方示例固化单测）、failover 参数补官方前缀+maxReconnectAttempts 3 次+supervisor 移交（D-8 登记）；TASK.md L-1~L-4 延后登记 |
| PR-5 | B5.1 bigscreen 最小遥测页+STOMP 封装+workstation 首页骨架 | complete | 4 提交（8efbca1..628272a）；前端六门禁全绿 53 例（31 新增）；@stomp/stompjs 7.3.0 app 级锁定（表外申报：版本在定稿权威表内，不进 catalog 先例）；B.3-3 逐条款合规；批次审核双 PASS（3 Minor 备案：B.3-3 连接级回调主题日志口径随 P1 修宪、audit 官方 registry 口径、token trim 页面入口已兜底）；code-review 4 findings（2C+2I）修复+复审 PASS：①鉴权点迁移（浏览器 WS 无法携带自定义 HTTP 头→后端 HTTP 握手层拦截器迁 STOMP CONNECT 帧级，IT 迁 StompHeaders，安全等价）②断线重连订阅静默丢失（stompjs 7.3.0 无自动重订阅实测=T-R4-2 回填）③已连接换病区状态机卡死④traceId 非安全上下文降级；跨栈终态：后端 343 单测+39 IT、前端 53 例；文档措辞备案（spring-websocket 版本号笔误 6.2.19→实为 6.2.18、SimpleBroker 归因不准）随 P1 |
| PR-5 | B5.2 W-3 销项+T-R3 回填核对+计划勾选+DoD 预检 | complete | 4 提交（5f71865..e35f40e）；W-3 三项逐项实测复核达成后销项（Dockerfile 26 条显式 COPY/三处同路径/ci.yml 无骨架期排除），禁误销清单全在位；计划内联标注 PR-1~4（hash 逐一核实）；DoD 预检报告落盘（docs/plans/2026-09-11-P0-DoD预检.md 七条三态）；审核随 B5.1 整批双 PASS |
| PR #12 | IOTDA 联调 L-3：报文映射第三形态+毒丸留痕脱敏 | complete | 5 提交（e0a3c38..eb623f5）；TDD：单测 +20（解析器 21 例含真实取证报文/消费者挂尾确认与脱敏接线断言/Masker 6 例）+IotTelemetryPipelineIT 步骤⑨；审核 QUALITY PASS（4 Minor：Q1 脱敏接线直接断言、Q2 Masker 边界用例已修，Q3 回填随本批、Q4 口径备案）+SPEC S1 路径 b 收口（quality 口径细化声明入 CHANGELOG 待用户追认，登记 TASK.md D-9）；本地 verify 全绿+CI 五 checks 全绿，合入 dev@0584e1e；真栈回填证据（2026-09-14 CHANGELOG 条目）：新毒丸归零、L-1 全链路演示（MQTT→AMQP→库→STOMP 摘要推送）、L-2 断链 10m26s 后新时间戳凭证重建一次追平 252 条零丢失、L-4 积压水位应用侧代理口径 10m35s |
