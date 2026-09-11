# PR-5（P0 收口）任务简报

| 属性 | 内容 |
| --- | --- |
| 编号 | BRIEF-PR5-01 |
| 日期 | 2026-09-11 |
| 性质 | 实现专员执行输入：PR-5 全部批次（B5.1~B5.2）的逐文件实现规格、TDD 验收指令与红线清单 |
| 唯一 spec | `docs/plans/2026-09-08-P0实施计划.md` §1-PR-5（第 67-71 行，范围与验收）+ §3 DoD（第 81-87 行） |
| 上游状态 | PR-4 已合入 dev@9107f92（PR #7）：`/ws/iot` STOMP 端点与双主题推送就位，后端 342 单测+38 IT 全绿、三核心包（system/integration/iot 的 service.impl）JaCoCo LINE=1.00 |
| 执行依据 | `docs/prompt/2026-09-09-loop-P0工程骨架.md` §3-P5（第 42 行出口门禁、第 102-103 行批次表 B5.1/B5.2）、§5 DoD 七条（第 117-126 行）、§8（E2E 排除裁决） |
| 分支姿态 | 当前分支 `feat/pr5-p0-closeout` 已由主控建立（HEAD=ef10f60，P4 台账终态已提交）；B5.1 起的全部提交直接落本分支，收尾经 PR 合入 dev |

**上游构件速查（实现专员必读的既有代码与约定，禁止重复造轮子）**：

| 构件 | 位置 | 复用方式 |
| --- | --- | --- |
| STOMP 端点契约 | `backend/fuyun-iot/.../config/IotWebSocketConfig.java` 第 47/53 行 | 端点 `/ws/iot`（纯 WebSocket 无 SockJS）；SimpleBroker 前缀 `/topic`；**客户端禁止自加 `/app` 应用前缀**（P0 无客户端出站消息） |
| 握手鉴权契约 | `backend/fuyun-iot/.../internal/StompHandshakeAuthInterceptor.java` 第 60-75 行 | 握手头必须带 `Authorization: Bearer {M01 访问令牌}`，校验失败 401 拒绝握手；令牌经登录端点 `POST /api/v1/system/auth/login` 获取（LoginResponse.accessToken） |
| 遥测摘要载荷契约 | `backend/fuyun-iot/.../service/ITelemetryPushService.java` 第 66/74 行 | `TelemetrySummary{count:int, occurredAtUpperBound:Instant, items:List<Item>}`、`Item{deviceId, metricCode}`；items 上限 100（第 33 行 SUMMARY_MAX_ITEMS，超限截断以 count 为准） |
| 主题路径契约 | `backend/fuyun-iot/.../constants/IotMessagingConstants.java` 第 55/58 行 | 遥测摘要 `/topic/iot/telemetry/{wardId}`（本 PR 唯一消费主题）；设备状态 `/topic/iot/device-status/{wardId}`（P0 不消费，负面清单） |
| 推送触发语义 | `backend/fuyun-iot/.../service/impl/TelemetryPushServiceImpl.java` 第 42-44 行 | 每批落库一帧；wardId 为空（无绑定快照）或空批次不推送——页面长时间无帧属预期，非缺陷 |
| Instant 序列化格式 | `IotFanoutListenerTest.java` 第 232 行（禁 WRITE_DATES_AS_TIMESTAMPS）+ `IotTelemetryPipelineIT.java` 第 384-386 行（断言 `count`/`items[].deviceId` 字段名） | `occurredAtUpperBound` 以 ISO-8601 字符串输出（Spring Boot 默认），前端 `string` 承载 |
| workstation 既有会话 | `web/apps/workstation/src/stores/auth.ts`（`user.displayName`/`isLoggedIn`）、`src/views/layout/`（MainLayout/AppSidebar/AppHeader） | 首页骨架只消费 auth store 展示 displayName，零新增 store/api/路由改动 |
| bigscreen 脚手架现状 | `web/apps/bigscreen/`：vite base `/bigscreen/`（vite.config.ts 第 9 行）、`/ws` 代理 `ws:true`（第 22-23 行）、路由 `/` → HomeView 懒加载（router/index.ts）、冒烟单测 App.spec.ts、vitest include `src/**/*.spec.ts` | 遥测页改造 HomeView 原位完成，路由与冒烟断言零改动（App.spec.ts 断言站点名「数据大屏」仍成立） |
| catalog 先例 | `web/pnpm-workspace.yaml` 第 2 行 | 既有注释「业务独立依赖不进 catalog：……echarts/@stomp 待 PR-5 再引」——@stomp/stompjs 按 app 级 package.json 锁定，**不进 catalog**（axios 进 catalog 是跨 app 共享先例，两者口径不同） |
| 前端门禁先例 | `web/package.json` scripts + web 宪法 C.5-1 | 六门禁：`pnpm lint` / `format:check` / `type-check` / `test` / `build` / `audit --audit-level high` |

---

## 0. 范围与切片口径（P5 切片边界，禁止越界）

PR-5 交付（计划 §1-PR-5 原文三条）：① bigscreen 最小遥测页（订阅 `/ws/iot` 遥测摘要主题展示，验证 WebSocket 链路）+ workstation 首页骨架；② TASK.md W-3 销项、计划文档勾选完成项、T-R3-2/T-R3-3 回填核对；③ P0 出口检查（DoD 预检，见 §3.4）。

**明确不在 P5（做了即越界）**：

1. **portal 应用零变更**（三 app 中 portal 无任何 P5 交付物）。
2. **echarts 不引入**：数据大屏完整版式（图表/多面板/布局网格）属 P1+；本 PR 遥测展示为纯文本/基础样式，不装 echarts 依赖（web 宪法 B.2-8「echarts 仅 bigscreen 安装」的引入时机由业务页面驱动，P5 无图表诉求即不装）。
3. **设备状态主题不消费**：`/topic/iot/device-status/{wardId}` 订阅随 P1 大屏完整版式交付（计划原文仅点名遥测摘要主题）；`types/iot.ts` 禁预置未用的 DeviceStatus 类型（死代码零容忍）。
4. **bigscreen 登录页不做**：P0 无大屏账号体系计划；token 注入姿态见 §1.3 最小方案。
5. **告警页、`/topic/iot/dashboard/global` 全院视图主题、2 秒窗口节流、订阅级数据范围校验**：均属 14-iot FU-M14-07 P1 完整化（该功能条目整行为 P1，docs/specs/modules/14-iot.md 第 146 行）；P0 后端侧亦未交付告警与全院主题推送。
6. **浏览器 E2E 维持排除**（loop §8 已锁定「链路级口径」裁决）：真实链路演示归 TASK.md L-1（IOTDA 六变量就绪后），PR-5 页面行为验证以单测承载。
7. **STOMP 封装不下沉 packages/shared 或 packages/ui**：本 PR 仅 bigscreen 一个消费方，跨 app 复用判定未触发（web 宪法 B.2-4）；workstation 接入 STOMP 属 P1，届时再按判定条款评估下沉。
8. **dev→main 合并、终验报告撰写**：归 P6（loop §3），PR-5 只做 DoD 预检不做终验勾选。

---

## 1. 设计规格

### 1.1 STOMP 封装方案（对照 web 宪法 B.3-3 逐条款，web/AGENTS.md 第 110 行）

| B.3-3 条款 | 本 PR 落法 |
| --- | --- |
| 每 app 一个 Client 实例，禁止组件各自建连 | `src/composables/useIotStomp.ts` 模块级单例：Client 惰性创建（首次 `connect()` 调用时 new 并缓存，非模块加载期、非 setup 期——保证 App.spec 冒烟挂载零网络副作用）；全 app 唯一获取入口 |
| 重连与心跳完全交库内建机制，禁止自研重连循环 | `reconnectDelay: 10000`（库内建固定间隔重试，禁自写 setInterval/setTimeout 退避）；`heartbeatIncoming: 10000 / heartbeatOutgoing: 10000`（与后端 STOMP 心跳协商，10s 对齐宪法数值） |
| 订阅返回句柄必须在组件卸载时 unsubscribe（统一封装） | `useIotTelemetry.ts` 在 `onUnmounted` 中对 `client.subscribe(...)` 返回的句柄统一 `unsubscribe()`；显式 `disconnect()` 同样先退订再 `deactivate()` |
| token 经 beforeConnect 动态填 connectHeaders | `beforeConnect` 回调内每次连接尝试（含断线重连）从 sessionStorage 实时读取令牌填 `connectHeaders['Authorization'] = 'Bearer ...'`——重连自动携带最新令牌，禁在构造参数里固化一次性 token |
| onStompError 与 onWebSocketClose 必须挂统一处理（日志含主题与 traceId，禁打 token） | 两回调统一走 `src/utils/logger.ts`：每次连接生成 `crypto.randomUUID()` 作链路 traceId 入日志；日志含当前订阅主题路径与 brokerURL；**令牌与 sessionStorage 键值禁入任何日志**（web A.6） |

连接状态机（页面消费）：`disconnected → connecting → connected`，`onConnect` 置 connected、`onWebSocketClose` 置 disconnected、`onStompError` 置 disconnected 并 error 留痕；deactivate 后不再自动重连（库语义：deactivate 取消重连计划，正好承载「用户主动断开」）。

brokerURL 推导：同源拼接 `((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws/iot')`——dev 走 vite `/ws` 代理（vite.config.ts 第 22-23 行已配 `ws: true`），生产走 nginx `/ws/` 升级路由（deploy/nginx/fuyun.conf 已就绪），**零新增 VITE_ 变量**（.env.example 与 ImportMetaEnv 不动，免三处同步）。

### 1.2 bigscreen 最小遥测页信息架构（单页三区，禁止越界做完整大屏）

| 区块 | 内容 | 数据来源 |
| --- | --- | --- |
| 连接设置区 | wardId 输入（路由 query `?wardId=` 承载，可书签化；纯数字校验，空/非法不订阅并提示）+ 访问令牌注入（password 型输入框 + 「连接」按钮；令牌存 sessionStorage 键 `fy:bigscreen:iot-token`，仅标签页周期存活）+ 「断开」按钮（connected 态可见） | 用户输入；令牌来源=登录接口获取后粘贴（大屏无登录页，见 §1.3） |
| 链路状态区 | 连接状态徽标（已连接/连接中/已断开）+ 当前订阅主题路径（`/topic/iot/telemetry/{wardId}`）+ 已接收帧计数 | `useIotTelemetry` 状态 ref |
| 遥测摘要区 | 最近一帧摘要：`count`（本批条数）、`occurredAtUpperBound`（ISO-8601 字符串原样展示，禁 new Date 换算后丢失原始精度——展示格式化工具随 P1 统一交付）、items 明细表（deviceId / metricCode 两列）；无帧时显示「暂无遥测数据」占位 | `TelemetrySummary`（仅保留最近一帧，新帧覆盖旧帧，防内存无界增长；帧历史留痕属 P1 版式） |

推送语义再确认（防误判缺陷）：后端每批落库推一帧、无绑定快照不推送（TelemetryPushServiceImpl.java 第 42-44 行）——页面长时间无帧是 P0 链路的正常形态，单测断言按「收到帧才更新」建模，不做轮询兜底（web 宪法 B.3-4：凡 `/ws/iot` 既有主题一律走 STOMP 推送）。

### 1.3 无后端 / 无令牌时的本地开发姿态

1. **单测姿态**：全部 STOMP 行为经 `vi.mock('@stomp/stompjs')` 承载（mock Client 类捕获构造参数与回调挂接），禁测试真实建连；载荷解析、状态流转、订阅生命周期断言全部在 mock 层完成。
2. **本地开发姿态（无后端）**：`pnpm dev` 打开页面即为 disconnected 态 + 库内建重连周期性尝试，状态区如实展示，无 mock 数据注入按钮（死代码零容忍——不做演示数据桩）。
3. **令牌注入最小方案（本简报推导建议，可推翻）**：P0 大屏无账号体系，token 经页面输入框人工注入并存 sessionStorage（键 `fy:bigscreen:iot-token`）。约束推导：禁 `VITE_` 环境变量承载（凭证禁声明为 VITE_，web A.2-2 红线）；禁 URL query 传递令牌（浏览器历史/日志泄漏面）；禁 localStorage（跨标签页持久会话，违背医疗终端「换机即失效」语义先例——workstation auth store 同口径用 sessionStorage）。

### 1.4 类型定义来源（手写后备类型的正当性声明）

`types/iot.ts` 手写 `TelemetrySummary`/`TelemetrySummaryItem`：openapi-typescript 生成物只覆盖 REST（`/v3/api-docs`），STOMP 载荷无生成链路来源，手写为唯一路径（web A.3-3 手写条款的 STOMP 延伸）。硬约束：字段名与后端 record 逐字对齐（`count`/`occurredAtUpperBound`/`items`/`deviceId`/`metricCode`，ITelemetryPushService.java 第 66/74 行）；`occurredAtUpperBound: string`（Instant 经 Spring Boot 默认 ISO-8601 字符串输出）；文件头注释声明来源类与漂移风险（后端契约变更时 vue-tsc 无法自动暴露，依赖后端测试与前端类型同步维护）。本载荷无 Long 字段；`wardId` 属主题路径参数，以字符串承载（web A.3-6 精神：后端 Long 一律 string，不做数值运算）。

### 1.5 workstation 首页骨架定义（计划原文仅「首页骨架」四字，本简报最小推导）

首页骨架 = MainLayout 内容区内、面向已登录用户的最小工作台语义视图（原 HomeView 仅一行占位文案）：

1. **会话问候区**：`{displayName}，欢迎回来`（displayName 取 `authStore.user?.displayName`，空值兜底「未登录用户」防御文案——路由守卫已默认拒绝未登录，兜底仅防直接挂载场景）；`loginName` 次行小字展示。
2. **业务开通占位区**：一张占位提示卡（文案与 AppSidebar 占位口径一致：「业务功能随各模块逐步开通，当前可经左侧菜单访问已开通功能」），**禁预列 20 模块卡片/禁伪数据/禁图表**。
3. 零新增依赖、零 api/store/路由改动（首页不发起任何出网调用——P0 无首页数据接口，引入调用即属推测性设计）。

### 1.6 T-R4-2 处置（stompjs 7.3.0 断线自动重订阅语义官方文档位置）

**维持登记，不改 TASK.md 该行（第 29 行），与 PR-5 无关。** 依据：P0 封装不依赖「断线自动重订阅」的官方保证——遥测摘要帧本就是滚动最新态（无增量补齐诉求），断线期间帧即丢弃、重连成功后下一帧自然恢复展示，订阅句柄重连后的自动恢复属库行为观察项而非契约依赖；「重连后按 REST 增量拉取快照补齐断连窗口」属 FU-M14-07 P1 范畴（14-iot.md 第 146 行）。T-R4-2 的回填时点（「STOMP 封装定稿前」）据此顺延至 P1 workstation/bigscreen 完整版式接入前，B5.2 禁误删该行。

---

## 2. B5.1 逐文件规格（前端；批次依赖：PR-4 已合入 dev）

### 2.1 bigscreen（最小遥测页）

包路径均在 `web/apps/bigscreen/` 下（略写）：

| 文件 | 动作 | 规格 |
| --- | --- | --- |
| `package.json` | 改 | dependencies 增 `"@stomp/stompjs": "7.3.0"`（**app 级锁定，非 catalog**——依据 pnpm-workspace.yaml 第 2 行既有注释先例「echarts/@stomp 待 PR-5 再引」归业务独立依赖；版本来源=技术栈定稿 §4.1 与 web 宪法 C.2 第 139 行，全仓唯一权威值）。落地动作：`cd web && pnpm install`（非 frozen）更新 lockfile 后一并提交；禁只改 package.json 不提交 lockfile |
| `src/types/iot.ts` | 新 | `TelemetrySummaryItem{deviceId: string; metricCode: string}`、`TelemetrySummary{count: number; occurredAtUpperBound: string; items: TelemetrySummaryItem[]}` 两个 interface + 全中文 JSDoc（字段业务含义/来源标注，§1.4 口径）；禁预置 DeviceStatus 等未用类型 |
| `src/utils/logger.ts` | 新 | 统一日志工具（web A.6「运行日志经统一 logger 或按环境裁剪」的最小落法）：`debug` 仅 `import.meta.env.DEV` 输出，`info/warn/error` 全量 `console` 输出；全中文消息前缀 `[bigscreen]`；模块注释声明「禁打 token 与敏感数据」红线 |
| `src/utils/iotMessage.ts` | 新 | `parseTelemetrySummary(raw: unknown): TelemetrySummary | null`：unknown 逐字段收窄守卫（count 正数、occurredAtUpperBound 非空 string、items 为数组且元素字段合法；items 允许空数组——后端空批次不推送，防御口径仍收窄放行）；任一字段不合法返回 null 并由调用方 warn 留痕。纯函数零依赖（web B.1 utils 边界），禁 any（web A.1-4） |
| `src/composables/useIotStomp.ts` | 新 | app 级 STOMP 单例封装（§1.1 表格全条款）：导出 `connect(options: StompConnectOptions): void`（含 token、wardId、onStateChange 回调——参数 >3 走参数对象，web A.7-1）、`disconnect(): Promise<void>`、`subscribeTelemetrySummary(wardId: string, onFrame: (s: TelemetrySummary) => void): StompSubscription`（内部 `client.subscribe('/topic/iot/telemetry/' + wardId, ...)`，回调内 try/catch JSON.parse + parseTelemetrySummary，毒帧 warn 留痕不中断）、连接状态 ref。Client 惰性单例；wardId 订阅前以 `^\d+$` 校验。头注释逐条对照 B.3-3 条款声明合规点 |
| `src/composables/useIotTelemetry.ts` | 新 | 页面级组合（页面私有状态走 composable 不建 store，web B.2-7）：`latestSummary: Ref<TelemetrySummary | null>`（新帧覆盖）、`frameCount: Ref<number>`、`connectionState`、`connect(token, wardId)`（校验 wardId → useIotStomp 连接 → 订阅摘要主题）、`disconnect()`；`onUnmounted` 统一 unsubscribe + 断开（B.3-3 卸载清理条款；副作用清理在 onUnmounted，web B.2-6）。禁 import views/components（web B.2-3） |
| `src/views/home/HomeView.vue` | 改 | 原位改造为最小遥测页（§1.2 三区组装；保留 `<h1>富云数据大屏</h1>` 站点名——App.spec.ts 冒烟断言锚点不动）：脚本区仅组装（读 route.query.wardId 回填输入、调 useIotTelemetry），业务逻辑全部在 composables（web B.1 views 只做组装）；wardId 变更经 `router.replace` 同步 query；样式 scoped（web A.1-2） |
| `src/views/home/components/TelemetrySummaryPanel.vue` | 新 | 纯展示组件：`defineProps<TelemetrySummaryPanelProps>()` 泛型声明（web A.1-3，props 含 summary: TelemetrySummary \| null、frameCount: number），多词 PascalCase 命名；无 emits、无出网、无状态（props 单向数据流，web A.1-5） |

### 2.2 workstation（首页骨架）

| 文件 | 动作 | 规格 |
| --- | --- | --- |
| `src/views/home/HomeView.vue` | 改 | §1.5 两区骨架：问候区（displayName/loginName 取 `useAuthStore()`）+ 业务开通占位卡；零新增依赖与出网调用；样式 scoped；注释声明「业务模块随 P1+ 逐步开通」 |

### 2.3 B5.1 单测清单（vitest + @vue/test-utils + jsdom；先写失败测试再实现）

| 测试文件 | 覆盖断言（正常 / 边界 / 异常三类场景齐备） |
| --- | --- |
| bigscreen `src/utils/iotMessage.spec.ts` | 合法载荷解析成功字段逐一断言；items 空数组放行；缺 count / occurredAtUpperBound 非字符串 / items 含残缺元素 → null；嵌套脏数据（items 非数组）→ null |
| bigscreen `src/composables/useIotStomp.spec.ts` | vi.mock Client：①单例——两次 connect 复用同一实例；②beforeConnect 每次尝试从 sessionStorage 读令牌拼 `Bearer` 头（改值后重连带新值）；③构造参数断言 reconnectDelay=10000、heartbeatIncoming/Outgoing=10000、onStompError/onWebSocketClose 已挂接且不抛 token；④subscribeTelemetrySummary 订阅路径精确等于 `/topic/iot/telemetry/{wardId}`；⑤毒帧（非法 JSON/残缺载荷）warn 不抛；⑥无 token 调 connect 拒绝建连（提示注入令牌）；⑦非数字 wardId 拒绝订阅 |
| bigscreen `src/composables/useIotTelemetry.spec.ts` | 收帧后 latestSummary 覆盖更新与 frameCount 递增；disconnect 后再 connect 状态机往返（disconnected→connecting→connected）；组件卸载触发 unsubscribe（挂载消费组件后 unmount，断言句柄 unsubscribe 被调） |
| bigscreen `src/views/home/HomeView.spec.ts` | 未连接态渲染连接设置与「暂无遥测数据」；wardId 缺失/非法时连接按钮拦截；注入假帧后面板渲染 count/items（经 mock composable 承载，禁真实建连） |
| workstation `src/views/home/HomeView.spec.ts` | 已登录态（直接注入 auth store state）渲染 displayName 与 loginName；空 store 兜底文案渲染 |

存量测试处置：bigscreen `App.spec.ts` 与 workstation 全部存量单测零改动通过（遥测页连接动作全部显式触发、Client 惰性创建、App 冒烟挂载无网络副作用；站点名断言锚点保留）——若实现中发现存量断言失效，按「因本次改动失效的旧测试直接修改」原则同步改造并申报。

---

## 3. B5.2 逐文件规格（P0 收口事务；依赖 B5.1）

### 3.1 TASK.md W-3 销项（逐项核实后删除条目，禁止盲删）

W-3 原文（TASK.md 第 51 行）销项口径 = 「落地时对齐三项」。逐项核对证据（实现专员复核下表现状后执行删除；任一项与表不符即停手上报，禁删）：

| # | 对齐项 | 核对证据（2026-09-11 实测） | 结论 |
| --- | --- | --- | --- |
| ① | Dockerfile COPY glob 拍平修正 | `backend/Dockerfile` 26 条显式 COPY 逐模块拷贝（含 `COPY fuyun-iot/pom.xml fuyun-iot/`）；ledger B1.2 行「Dockerfile COPY 重写 complete」 | 已达成 |
| ② | web 产物路径以宪法 `web/apps/<app>/dist` 为准 | `deploy/docker-compose.yml` 第 146-148 行三应用 dist bind mount + `web/Dockerfile` 三条 `COPY --from=build .../apps/<app>/dist` + `deploy/nginx/fuyun.conf` 三 location alias 同路径 | 已达成 |
| ③ | 移除 ci.yml 骨架期排除项 | `.github/workflows/ci.yml` 无任何骨架期排除（dorny/paths-filter 正常触发，backend/web Dockerfile 均在 images job 构建清单）；ledger B1.3 行 complete | 已达成 |

操作：三项均核实达成 → 删除 TODO 工单表中 W-3 整行（登记台规则「条目回填后即删除」）。

**禁误销清单（与 W-3 同表/相近，删除动作仅限 W-3 一行）**：W-4（迁移号段 CI 校验，随 CI 完整化补建）、W-5（properties toString 脱敏，P1）、D-8（宪法 A.5-9 正文同步，待用户裁决随 P1）、L-1~L-4（IOTDA 联调延后组）、T-R4-2（§1.6 处置）、T-R4-3/T-R4-4/T-R3-1 等其余待调研行。

### 3.2 T-R3-2 / T-R3-3 回填核对（已回填，声明无动作）

- **T-R3-2**：已于 PR-4 B4.1 实测回填并按登记台规则删除原行——结论 = `add_columnstore_policy` 胜出（2.29.2 为 PROCEDURE 须 CALL，`add_compression_policy` 自 2.18 弃用），见 CHANGELOG.md「T-R3-2 实测结论（收口补记）」条目（第 54 行）。B5.2 动作 = 核对 TASK.md 待调研表确无 T-R3-2 行 + PR 描述声明核对结论，无文件改动。
- **T-R3-3**：原行已于 B4.4 回填删除——本地两级实测（supervisor 单测 + IotAmqpReconnectIT 断链恢复 IT）全绿，「真实 IoTDA 端点 10 分钟断链演示」并入 L-2 延后登记（TASK.md 第 42-43 行）。B5.2 动作 = 核对 L-2 行在位且表述完整，无文件改动。

### 3.3 计划文档勾选完成项（机制定义：计划现状 0 个 checkbox，本简报定义最小标注法）

`docs/plans/2026-09-08-P0实施计划.md` 修改（标注形式，禁改正文语义）：

1. §1 五个 PR 标题行尾追加完成标注（利用已知合入点）：PR-1~PR-4 四行分别追加「——已完成（PR #4，dev@ed5e34e）」「——已完成（PR #5，dev@a019f47）」「——已完成（PR #6，dev@a93179a）」「——已完成（PR #7，dev@9107f92）」（合入点以 ledger 各收尾行记录为准，执行时复核）。
2. PR-5 行**不自标**（本 PR 合入时点未知，禁止写预估值）：随 P6 终验一并补记。
3. §3 DoD 五条不动：勾选动作属 P6 终验（loop §3-P6「按 DoD 逐项核对附证据」），PR-5 只做预检（§3.4），提前打勾即伪造证据。

### 3.4 DoD 预检清单（输出 = PR 描述中的预检表；逐条对齐 loop §5 七条）

| # | DoD 条目（loop 第 119-125 行） | 验证命令 / 证据来源 | PR-5 时点预检结论 |
| --- | --- | --- | --- |
| 1 | compose 七服务全 healthy；`--profile sim` 端到端演示或 IOTDA_* 缺失按计划延后登记 | `docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d` 后 `docker compose ps`；延后证据=TASK.md L-1~L-4 行 | 延后条款生效中（L-1 已登记启用前提）；P6 复跑 healthy |
| 2 | 后端门禁绿含 JaCoCo 双阈值（核心包 1.00）；前端五门禁绿 | `cd backend && JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp verify`（PR #7 终态 342 单测+38 IT 绿）；`cd web && pnpm lint && pnpm format:check && pnpm type-check && pnpm test && pnpm build`（B5.1 后复跑，audit 第 6 步同跑） | B5.1 出口条件即本条前端侧证据；后端零改动免复跑（PR 描述声明） |
| 3 | 五个 PR 均经 /code-review 审核通过后合入 dev | ledger 各「PR 收尾」行 + 本次 PR-5 的 /code-review 记录 | PR-5 本体随本 PR 走；PR #5 时点缺口由 P6 补跑（交接文档 §2.3），非 PR-5 范围 |
| 4 | main 与 dev 保护均含五 required checks | `gh api repos/{owner}/{repo}/branches/main|dev/protection` | P0 门禁准备时已证实逐字段一致；P6 复证落终验报告 |
| 5 | CF-1/CF-2/CF-7 冻结登记 event_registry | V5 种子 + V403 + MessagingGovernanceIT 冻结断言（PR-2/PR-4 已交付） | 已完成，无动作 |
| 6 | TASK.md W-3 销项、T-R3-2/3 回填 | B5.2 本批执行（§3.1-§3.2） | B5.2 完成即闭环 |
| 7 | 台账完整；终验报告每项附证据并合入 dev；本地最终停留 dev | 台账 B5 行由主控随批次回填；终验报告属 P6 | 预检确认台账机制在位；本条整体归 P6 |

---

## 4. TDD 与验收指令

**TDD 铁律**（loop §7）：每任务先写失败测试（RED）→ 实现（GREEN）→ 重构；测试与实现同一次提交；禁止先写生产代码。B5.1 顺序建议：类型与解析纯函数（types+utils）→ STOMP 封装（composable，全 mock）→ 页面组装 → workstation 首页。

**批次完成标准**：

| 批次 | 完成标准 |
| --- | --- |
| B5.1 | `cd web && pnpm install --frozen-lockfile`（lockfile 已随新依赖更新入库）→ 六门禁全绿：`pnpm lint`（--max-warnings=0）、`pnpm format:check`、`pnpm type-check`、`pnpm test`（§2.3 全部新测试 + 存量回归）、`pnpm build`（三应用按拓扑序构建，bigscreen base=/bigscreen/）、`pnpm audit --audit-level high` |
| B5.2 | §3.1-§3.3 文件改动落盘 + §3.4 预检表写入 PR 描述；`pnpm format:check` 等文档无关门禁不涉；TASK.md 修改后核对仅 W-3 一行被删（git diff 复核） |
| PR-5 整体 | 计划 §1-PR-5 三条交付物全落 → feature 分支（当前 feat/pr5-p0-closeout）→ `gh pr create` → 五 checks 全绿 → /code-review 无 findings → 合入 dev（P5 出口门禁：计划 §1-PR-5 全过 + DoD 预检 + 合入 dev，loop 第 42 行） |

链路级验收口径声明：WebSocket 真链路演示（sim 全链路）归 TASK.md L-1（IOTDA 六变量就绪后），PR-5 以「单测全覆盖 + 六门禁绿」为页面级完成口径；浏览器 E2E 维持排除（loop §8 裁决）。

---

## 5. 提交切分建议（conventional commits，中文 subject；body 每行 ≤100 字符）

1. `docs(changelog): 登记 PR-5 P0 收口变更与 bigscreen STOMP 依赖申报（先记再改）`
2. `feat(bigscreen): STOMP 客户端单例封装与遥测摘要类型解析`（package.json + lockfile + types/iot.ts + utils/ + useIotStomp + 对应 spec）
3. `feat(bigscreen): 最小遥测页（连接状态与遥测摘要面板）`（HomeView 改造 + TelemetrySummaryPanel + 对应 spec）
4. `feat(workstation): 首页骨架（会话问候与业务开通占位）`
5. `docs(task): W-3 销项与 T-R3-2/3 回填核对结论登记`（TASK.md 仅删 W-3 行）
6. `docs(plan): 计划 §1 勾选 PR-1 至 PR-4 完成项`（+ CHANGELOG 若有补记）

---

## 6. 红线清单（实现与审核双用，违者不得合入）

1. **B.3-3 逐条款**（web/AGENTS.md 第 110 行）：每 app 单例 Client；重连心跳全交库内建（禁自研重连循环/禁自写退避定时器）；订阅句柄组件卸载必 unsubscribe；token 仅经 beforeConnect 动态注入；onStompError/onWebSocketClose 统一处理（日志含主题与 traceId）。
2. **token 安全**：令牌禁入日志（web A.6）与 console；禁 `VITE_` 变量承载（web A.2-2）；禁 URL query 传递；sessionStorage 键 `fy:bigscreen:iot-token` 值禁出现在测试快照与注释示例中。
3. **禁 any**：载荷解析一律 unknown 收窄（web A.1-4）；emit/props 载荷禁 any。
4. **组件禁直连 axios**：本 PR bigscreen 无 HTTP 调用（STOMP 不经 axios）；workstation 首页禁新增出网调用。
5. **Long 字段 string**：遥测摘要无 Long 字段；wardId 以字符串承载拼主题路径，禁数值化。
6. **路由组件懒加载**：现有 `/` 路由已懒加载，禁改静态导入（web B.3-2）；路由 meta 语义不动。
7. **多词组件名与 SFC 约定**：TelemetrySummaryPanel 多词 PascalCase；`<script setup lang="ts">` 零例外；props 泛型声明、样式 scoped。
8. **catalog 版本纪律**：@stomp/stompjs 7.3.0 app 级锁定（依据 pnpm-workspace.yaml 第 2 行先例），禁漂移、禁顺手引入任何其他依赖（echarts/dayjs 等一律不装）；lockfile 变更必须同提交。
9. **E2E 排除**：禁引入任何浏览器 E2E 框架/脚本（loop §8 链路级裁决）。
10. **W-3 销项必须逐项核实**：三项对齐逐一以 §3.1 证据表复核后才可删行；W-4/W-5/D-8/L-1~L-4/T-R4-2 等行禁误删；T-R3-2/T-R3-3 已回填不得重复操作。
11. **计划文档只做标注**：§3.3 标注法之外禁改计划正文任何语义；DoD 禁提前勾选。
12. **死代码零容忍**：禁预置未用类型（DeviceStatus）、禁演示数据桩、禁注释掉的代码；因本次改动失效的存量测试同步改造。
13. **注释与日志全中文**（全局 §一/§二）：类/函数中文 JSDoc 含参数业务含义与来源；日志经统一 logger，禁散落 console。
14. **portal 零变更**：git diff 中不得出现 `web/apps/portal/` 路径。

---

## 7. 决策记录与表外申报

| 决策 | 内容与依据 | 推翻改动面 |
| --- | --- | --- |
| @stomp/stompjs 依赖申报 | 版本 7.3.0（技术栈定稿 §4.1 与 web 宪法 C.2 唯一权威值，非表外新值）；**声明位置 = bigscreen app 级 package.json 而非 catalog**——依据 pnpm-workspace.yaml 第 2 行既有注释先例（「业务独立依赖不进 catalog：……echarts/@stomp 待 PR-5 再引」）；PR 描述申报新增依赖及版本来源 | 若主控改判进 catalog（理由候选：workstation P1 也将用 STOMP）：改动面 = pnpm-workspace.yaml catalog 段增一行 + package.json 引用改 `catalog:`，一次替换可回收 |
| T-R4-2 处置 | 维持登记不改行：P0 不依赖断线自动重订阅语义（§1.6）；回填时点顺延至 P1 完整接入前 | 无（登记台现状即目标态） |
| bigscreen 令牌注入姿态 | 页面输入框 + sessionStorage（§1.3 推导建议） | 若用户裁决大屏专用凭证/服务端下发：改动面 = 连接设置区取值来源 + sessionStorage 键，封装层 beforeConnect 读取点单点替换 |
| 计划勾选机制 | §3.3 内联标注法（计划原文「勾选完成项」无既定格式，现状 0 checkbox） | 若改判状态表格列：改动面 = 计划 §1 表头与五行标注，内容不变 |
| workstation 首页骨架形态 | §1.5 问候区 + 占位卡（计划原文仅「首页骨架」） | 形态增减不影响其他文件；store/api/路由契约不变 |

**表外申报汇总（写入 PR 描述）**：① 新增依赖 @stomp/stompjs 7.3.0（版本在权威定稿表内，申报的是「新增依赖本身 + app 级声明位置」）；② lockfile 变更（随①）；③ TASK.md 删 W-3 一行、计划文档四处内联标注、CHANGELOG 两条新增（先记再改条目与收尾条目）——均为计划 §1-PR-5 点名的收口动作，无计划外文件变更。

---

## 附：待裁决 / 关注项汇总（回报主控）

1. **宪法 B.3-3 措辞差异（关注，不阻塞）**：条款括号内「reconnectDelay 指数退避」与 @stomp/stompjs 7.3.0 内建机制实况（`reconnectDelay` 为固定间隔毫秒值，无内建指数退避）存在措辞出入。P0 处置 = 用库内建固定间隔 10000ms、绝不自研退避循环（合规核心 = 「重连完全交库内建、禁自研循环」）；宪法措辞修订走修宪流程（先记 CHANGELOG 再改正文），建议随 P1 workstation 接入 STOMP 时一并处理，本 PR 不动宪法。
2. **bigscreen 令牌注入姿态**为本简报推导建议（计划/spec 均未规定大屏取凭据方式，后端握手 401 硬约束决定必须有令牌）——主控可按 §7 表推翻，改动面已注明。
3. **计划文档勾选机制**为本简报定义（原文无格式）——主控改判不阻塞 B5.2。
4. **workstation 首页骨架具体形态**为最小推导（计划原文四字），如用户对首页有明确产品预期，随 P1 权限菜单/工作台设计再演进，P5 不扩。
5. **无 spec 硬冲突**：bigscreen 遥测页字段/主题/端点与 backend PR-4 交付源码逐字对齐（ITelemetryPushService/IotMessagingConstants/IotWebSocketConfig）；14-iot §7 的四主题清单中 P0 仅消费遥测摘要主题，与计划「至少遥测摘要主题」口径一致。
