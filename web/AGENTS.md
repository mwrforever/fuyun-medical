# web 宪法（Vue 3 + TypeScript + Vite 三应用 monorepo）

> 本子项目最高规范：**只存工程原则与约束**。功能实现与业务数据契约见代码与 `../docs/specs/`，待办见 `../TASK.md`，变更记录见 `../CHANGELOG.md`（先记再改），本文档均不重复。
> 任何与本文档冲突的代码或设计不得合入 main 分支。
> 仓库定位层见根 [../AGENTS.md](../AGENTS.md)：跨切总则（编码红线 / 版本红线 / 安全红线 / CI 链总则）不在此重复，说明性内容不复制，也不引用兄弟子宪法替代成文（与后端相同的约束在本文件完整成文）。
> 注释 / 日志 / 测试与死代码规范见全局 `~/.zcode/AGENTS.md`（§一 注释规范、§二 日志规范、§四 测试与死代码），强制生效。
> 配套文件职责（specs / TASK.md / CHANGELOG.md 边界）由根定位层 §7 声明，本文件只留指向、不复述。

**三段结构**：Part A Vue/TS 通用 / Part B 前端架构分层 / Part C web 实际。
（A.4 数据库、A.5 基础设施生命周期、B.4 外部能力网关对本子项目不适用，整段省略，编号不重排。）

---

## Part A — Vue 3 + TypeScript 通用规范

### A.1 编码约束

1. SFC 统一 `<script setup lang="ts">` + Composition API，零例外；Options API 不允许（无历史代码）。
2. Vue 官方风格指南 Priority A 全部强制（多词组件名、prop 必须定义、v-for 必带 key、禁 v-if 与 v-for 同元素、样式 scoped 隔离等），经 eslint-plugin-vue `flat/essential` 机器把关；Priority B 关键项（文件名 PascalCase、The 前缀单例组件、紧耦合子组件父前缀）强烈推荐。
3. props 声明强制泛型类型化 `defineProps<T>()`；默认值统一用 3.5 响应式解构默认值（`const { msg = 'hello' } = defineProps<Props>()`），禁止再引入 withDefaults；emit 强制命名元组写法 `defineEmits<{ change: [id: number] }>()`，事件载荷禁止 any。
4. tsconfig 全应用 `"strict": true`；禁 any（确需未知类型用 unknown 收窄），ESLint `@typescript-eslint/no-explicit-any` 把关；模板事件处理器显式标注参数类型。
5. 响应式：ref 为默认主 API（官方立场），reactive 仅限"相关状态分组"且禁泛型参数；props 单向数据流——子组件禁止直接写 prop（含嵌套属性隐性变更），变更走 emit。
6. computed 保持纯函数（getter 内禁副作用），副作用走 watch/watchEffect。
7. 模板引用统一 `useTemplateRef<T>()`（3.5 写法基线），组件引用类型用 `InstanceType<typeof Comp>`。
8. 表单双向绑定统一 `defineModel<T>()`，禁止手写 modelValue prop + update:modelValue emit 样板。
9. 组件/文件命名：组件 PascalCase 多词；目录跟随文件名（组件目录 PascalCase）。

### A.2 配置管理（Vite）

1. 运行时配置只经 `import.meta.env` 访问（业务代码禁读 process.env）；env 值全为字符串，类型转换集中封装。
2. **敏感信息红线**：`VITE_` 前缀变量一律视为公开信息（官方：构建后暴露进客户端源码）；任何密钥 / secret / 内部凭证禁止声明为 `VITE_` 变量——后端密钥只存在于后端；`envPrefix` 保持默认不改（可审计性）。
3. env 文件分层 `.env` < `.env.local` < `.env.[mode]` < `.env.[mode].local`，`*.local` 不入库；每个 app 维护 `.env.example` 模板入库（键齐全、值占位），新增 `VITE_` 变量必须同步 example 与 `ImportMetaEnv` 类型增补（三处同步，vue-tsc 把关）。
4. `server.proxy` 仅 dev 生效：`/api` 指向本地后端（禁 rewrite 前缀）；`/ws` WebSocket 联调需 `ws: true`（`rewriteWsOrigin` 仅限本地 dev）；生产反代由 nginx 承担。
5. 路径别名 `@` → 各自 `src/`（与 tsconfig paths 同步）；monorepo 共享包用 `@fuyun/*` 包名而非别名；构建产物目录保持默认 `dist`（与部署 bind mount 路径耦合，禁改名）。
6. `NODE_ENV` 与 `--mode` 是两个独立概念，禁止混用；mode 采用 development/test/production 三档。

### A.3 前端 API 层与类型契约

1. 每 app 经 `axios.create({ baseURL, timeout })` 建立唯一实例并模块级单例导出，禁用全局 axios 对象、禁止组件内直接 axios 调用。
2. 拦截器职责收敛（每实例 1+1）：请求拦截器注入 Authorization token 与 traceId 请求头；响应拦截器统一错误出口（非 2xx → Element Plus message 统一提示 + 401 触发刷新/重登），拦截器内不落地业务逻辑。
3. **类型契约唯一来源 = openapi-typescript 生成物**（7.13.0）：从后端 Springdoc `/v3/api-docs` 生成 `api.d.ts` 提交入库（契约漂移在 vue-tsc 阶段暴露）；业务类型取 `components["schemas"]["XxxVO"]`；手写 interface 仅作生成链路故障的后备；CI 校验生成物新鲜度（重新生成后 diff 为空才可合入）。
4. 不引入 openapi-fetch 作为第二 HTTP 客户端（Axios 拦截器承载 token 刷新/traceId 是保留它的主因）。
5. API 按业务域模块化（`src/api/patient.ts`），每模块导出类型化请求函数；统一响应结构的前端映射类型集中定义（字段名与后端 A.3 定稿对齐），禁止各 api 模块自定义响应包装。
6. **长整型与金额承载**：后端经 Jackson 全局以字符串输出的 Long 字段（雪花 ID、金额分值等，backend A.3-8）在前端类型中一律 `string` 承载，禁按 number 处理；金额展示格式化（分→元）集中在 utils/ 的统一工具函数，禁止组件内散落 `÷100` 换算；金额仅展示与传回（原样字符串），前端不做任何金额计算（总 Spec D5）。

（A.4、A.5 不适用，省略。）

### A.6 注释 / 日志 / 测试

见全局 `~/.zcode/AGENTS.md` §一（注释规范）、§二（日志规范）、§四（测试与死代码）。本项目强制生效。前端补充：禁止散落 console 调试输出（STOMP/轮询等运行日志经统一 logger 或按环境裁剪），日志不得打印 token 与患者敏感数据；测试栈约定（Vitest 4.1.11 + @vue/test-utils 2.5.0 + jsdom）见 C.5。

### A.7 跨层数据对象与传参约束（必含）

1. **参数对象化**：函数 / composable / api 模块形参 > 3 必须以 interface/type 参数对象整体传参，禁逐参罗列。
2. **返回业务对象**：api 模块返回类型化业务对象（生成契约类型组合），禁裸 object/any；请求与响应分别建模（`XxxCreateRequest` 与 `XxxVO` 禁互相赋值复用），生成类型天然分离、手写后备同此规则。
3. **职责隔离**：四类模型禁复用——API 响应类型 ≠ 表单模型 ≠ Pinia store state ≠ 组件 props 类型（生命周期与校验语义不同）；转换边界显式化为纯函数（`toFormModel()` / `toRequest()`，放 api 或 composables 层）。
4. **传参层级判定**（由近及远）：直接父子 props/emit → 跨深层级 provide/inject（`InjectionKey<T>` 导出 Symbol 作 key、provide 响应式值 + readonly 防篡改）→ 跨页面全局状态才建 Pinia store；store 解构必须 `storeToRefs()`（actions 可直接解构）。
5. **例外边界**：仅业务功能确需动态/灵活结构可偏离，且须能陈述业务理由；无理由的违反视为缺陷，不得合入。

---

## Part B — 前端架构分层

### B.1 目录职责边界

仓库层：

| 目录 | 边界 |
| --- | --- |
| `web/pnpm-workspace.yaml` | workspace 根声明（必须在根目录），glob 圈定 apps/packages + catalog 共享版本 |
| `web/apps/{workstation,portal,bigscreen}` | 三个可独立构建应用（与部署挂载路径一一对应） |
| `web/packages/shared` | 纯 TS 类型与工具（含 openapi 契约生成物），**禁依赖 vue/element-plus** |
| `web/packages/ui` | 跨 app 组件封装（依赖 element-plus 等 UI 库），被多 app 复用的业务组件 |

单 app 内 `src/` 目录职责（三 app 一致）：

| 目录 | 职责边界 |
| --- | --- |
| `views/` | 路由级页面，按业务域分子目录；只做组装与布局，业务逻辑下沉 composables |
| `components/` | 可复用组件：通用件 `common/` 与业务件 `{domain}/` 分目录；页面私有组件就近放 views 对应目录 |
| `api/` | API 模块（按业务域），唯一出网出口（A.3）；禁在组件/composable 直接 axios |
| `stores/` | Pinia store（Setup Store），跨页面共享状态；文件名与 store id 一致 |
| `router/` | 路由（`modules/` 按业务域拆分）+ 导航守卫（认证/权限，B.3-2） |
| `composables/` | 复用逻辑（`use` 前缀）：数据获取、轮询、STOMP 订阅等；无渲染逻辑复用一律在此 |
| `types/` | env 类型增补（ImportMetaEnv）+ openapi 契约生成物引用；纯类型无运行时 |
| `utils/` | 纯函数工具（格式化、校验等）；禁持有业务状态、禁依赖 vue 组件 |
| `assets/` `styles/` `directives/` | 静态资源 / 全局样式与主题变量 / 自定义指令 |

### B.2 层级依赖（强制）

```
packages/shared ◀── packages/ui ◀── apps/{workstation,portal,bigscreen}
app 内：views ──▶ components ──▶ composables ──▶ api / stores        （单向，禁止反向）
        utils / types / assets：纯类型与纯函数，全层可引用，不反向产生依赖
```

1. 依赖方向单向：views → components → composables → api/stores；types 与 utils（纯类型/纯函数）全层可引用；禁止反向引用、禁止循环依赖（pnpm 循环警告视为构建失败级）。
2. 组件禁直接出网与持全局状态：业务组件禁止直接调用 axios（经 api 层）、禁止绕过 store 直接修改全局状态；组件间通信走 props/emit（A.7-4 传参层级判定）。
3. composables 可组合（composable 调 composable），但禁止反向依赖 views/components；store 禁 import views/components（store 被调用，不主动依赖视图层）。
4. 跨目录/跨 app 复用判定：同 app 多处复用 → 下沉该 app composables/utils；跨 app 复用 → 下沉 packages/shared 或 packages/ui；禁止跨 app 直接 import 对方 src/。
5. 共享包引用一律 `workspace:*` 协议（禁普通 semver 引本地包）；apps → ui → shared 方向单向，禁反向。
6. 业务逻辑禁入组件：有状态复用逻辑抽 composables（`use` 开头、副作用在 onUnmounted 清理、入参 toValue 归一化、仅 setup 同步调用）；数据获取逻辑（loading/error/data）一律进 composables + api 层。
7. composable 与 store 边界：每组件实例独立状态 → composable；跨组件/页面共享 → Pinia。
8. 组件库依赖按 app 圈定：element-plus 仅 workstation 安装；echarts 仅 bigscreen 安装（包体与构建隔离）。

### B.3 运行时原则

1. **Pinia**：store 统一 Setup Store 写法且必须返回全部 state；仅"多视图共享状态/跨视图修改同一状态"才建 store（认证会话、全局字典、看板布局为典型）；store 内禁存路由对象等外部注入物；组件外使用（守卫/拦截器）必须延迟调用或显式传 pinia 实例。
2. **路由**：路由组件全部懒加载（`() => import(...)`），禁静态导入与 defineAsyncComponent 作路由组件；路由文件按业务域模块化；导航守卫分层——beforeEach 只做认证/权限（return 重定向、防死循环）、beforeResolve 做数据预取、afterEach 做埋点/标题；每条路由 meta 承载权限语义（医疗系统"路由 = 权限点清单"审计形态，不启用文件路由）。
3. **STOMP 实时推送**：每 app 一个 Client 实例（封装为 service/`useStomp` composable），禁止组件各自建连；重连与心跳完全交给库内建机制（reconnectDelay 指数退避 + 心跳 10s 与后端协商），禁止自研重连循环；订阅返回句柄必须在组件卸载时 unsubscribe（统一封装）；token 经 beforeConnect 动态填 connectHeaders；onStompError 与 onWebSocketClose 必须挂统一处理（日志含主题与 traceId，禁打 token）。
4. **实时数据界限**：凡 `/ws/iot` 既有主题（主题清单见 `../docs/specs/modules/14-iot.md`）一律走 STOMP 推送；无推送主题的低频快照才用 HTTP 轮询（visibilityState 隐藏时暂停）；新增实时需求先问"能否并入既有主题"。
5. **ECharts（bigscreen）**：按需引入强制（echarts/core + 按类型注册 + 手动二选一渲染器），集中单一模块注册；option 用 ComposeOption 组合严格类型；图表实例生命周期（init/setOption/resize/dispose）封装进统一组件或 useEChart，页面隐藏/卸载必须 dispose，防实例泄漏。
6. **Element Plus 按需引入**（unplugin-vue-components + unplugin-auto-import + ElementPlusResolver）；pnpm 严格依赖下显式声明 dayjs 依赖（官方明示的坑）；主题定制先 CSS 变量（类作用域），不满足再上 SCSS 编译期方案。

（B.4 外部能力网关不适用已省略；B.5 横切关注点不单独成节：安全红线（VITE_ 公开性等）见 A.2 与根定位层 §7，编号不重排。）

（B.4 不适用，省略。）

---

## Part C — web 实际

### C.1 子项目定位

pnpm monorepo 三前端应用：workstation（医护工作站，Element Plus 管理界面，核心业务操作端）、portal（患者门户，互联网医院服务端）、bigscreen（数据大屏，ECharts 可视化 + STOMP 实时推送）。

### C.2 技术栈选型（版本唯一权威：docs/language/2026-09-07-技术栈选型.md v1.1）

| 职责 | 技术 | 版本 | 约束 |
| --- | --- | --- | --- |
| 运行时 | Node.js | 24 LTS | CI 与本机一致，禁混用其他大版本 |
| 包管理 | pnpm | 12.3.4 | packageManager 字段锁死；workspace 单根 lockfile |
| 框架 / 语言 | Vue 3.5.42 / TypeScript 5.9.3 | 定稿 | 禁升 TS 7（生态未收敛） |
| 构建 | Vite | 8.2.2 | 产物目录 dist 不变 |
| 组件库 | Element Plus | 2.14.5 | 仅 workstation；按需引入 |
| 状态 / 路由 | Pinia 4.0.3 / Vue Router 5.3.1 | 定稿 | Setup Store / 全懒加载 |
| HTTP | Axios | 1.20.0 | 单实例 + 拦截器 |
| 可视化 | ECharts | 6.1.0 | 仅 bigscreen；按需注册 |
| 实时推送 | @stomp/stompjs | 7.3.0 | 单例 + 库内建重连 |
| 类型契约 | openapi-typescript | 7.13.0 | 生成物入库 |
| 按需插件 | unplugin-vue-components 32.1.0 / unplugin-auto-import 21.1.0 | 锁定 | engines Node ≥20.19 |
| 单测 | Vitest 4.1.11 / @vue/test-utils 2.5.0 / jsdom 30.0.1 | 锁定 | 禁升 Vitest 5（GA 不足一季度，演进路径） |
| lint / 格式 | ESLint 10.10.0 / eslint-plugin-vue 10.10.0 / typescript-eslint 8.69.0 / @vue/eslint-config-typescript 14.9.0 / Prettier 3.9.6 / eslint-config-prettier 10.1.8 | 锁定 | flat config；格式归 Prettier |
| 类型检查 | vue-tsc | 3.3.10 | --noEmit |

### C.3 目录结构

```
web/
├── package.json               # 根：scripts 全量入口 + packageManager 锁定
├── pnpm-workspace.yaml        # workspace 声明 + catalog（共享依赖版本常量）
├── eslint.config.mjs          # 单根 flat config 覆盖三应用（withVueTs 组合）
├── .prettierrc                # printWidth 100 / singleQuote / trailingComma all / endOfLine lf
├── vitest.config.ts           # projects 聚合三应用
├── commitlint -> ../commitlint.config.mjs 相关配置经 husky 挂接（见 C.6）
├── apps/
│   ├── workstation/           # 医护工作站（element-plus）
│   │   ├── src/{views,components,api,stores,router,composables,types}/
│   │   ├── vitest.config.ts   # environment jsdom
│   │   └── tsconfig.json      # solution 文件（references → app/node）
│   ├── portal/                # 患者门户
│   └── bigscreen/             # 数据大屏（echarts + stomp）
└── packages/
    ├── shared/                # 纯 TS 类型与工具（含 api.d.ts 契约生成物）
    └── ui/                    # 跨应用组件封装
```

### C.4 常用命令（在 `web/` 目录执行）

```bash
# 依赖安装（CI 同命令；禁裸 install 造成 lockfile 漂移）
pnpm install --frozen-lockfile

# ESLint 全量校验（--max-warnings=0 硬门禁，单根 flat config 覆盖三应用）
pnpm lint

# Prettier 写入修复（本地） / 只读校验（CI 门禁）
pnpm format
pnpm format:check

# 各应用 vue-tsc --noEmit 类型检查（pnpm -r 拓扑递归，报错定位到应用）
pnpm type-check

# Vitest 全量单测（根配置 projects 聚合三应用）
pnpm test

# 按拓扑序构建三应用产物（dist，与部署挂载路径对应）
pnpm build

# 依赖漏洞审计（high 及以上退出非零即门禁阻断；豁免用 GHSA ID 留痕）
pnpm audit
```

约定：共享依赖版本进 `pnpm-workspace.yaml` catalog 统一管理；业务独立依赖（如 echarts）不进 catalog；新增 app 必须同步 vitest projects、eslint 覆盖与 CI 缓存配置。

### C.5 CI 生产落地方案（方案 B 严格门禁 · web 侧）

1. **触发与门禁**：ci.yml 中 frontend job（`name: frontend / verify`）在 `web/**` 或 CI 配置变更时触发；步骤：checkout → **先** pnpm/action-setup@v6.1.0（读 packageManager 字段）**再** setup-node@v7.0.0（Node 24，`cache: pnpm`，顺序不可颠倒）→ `pnpm install --frozen-lockfile` → `pnpm lint` → `pnpm format:check` → `pnpm type-check` → `pnpm test` → `pnpm build` → `pnpm audit`；timeout 20 分钟。
2. **风格硬门禁**：ESLint `--max-warnings=0`（flat config 组合：`withVueTs(pluginVue.configs['flat/essential'], vueTsConfigs.recommendedTypeChecked, eslintConfigPrettier)`——prettier 配置必须置尾；类型感知规则从 recommendedTypeChecked 起步）+ Prettier `--check`（`endOfLine: "lf"` 与 .gitattributes 双保险）；禁用 eslint-plugin-prettier（格式统一交 Prettier）。
3. **类型契约新鲜度**：`openapi-typescript` 重新生成后 diff 非空即失败（防止契约类型与后端漂移合入）。
4. **测试**：Vitest 4.1.11（jsdom 30.0.1 环境）+ @vue/test-utils 2.5.0；覆盖率 report-only 起步（@vitest/coverage-v8 4.1.11，金额计算全在后端，前端硬阈值易催生空断言测试——全局规范禁止；测试文化成型后经宪法修订收紧 thresholds）。
5. **依赖审计**：`pnpm audit --audit-level high` 进 frontend job 主链（高危阻断；豁免用 GHSA ID 留痕登记）。
6. **镜像**：images job 中 web 镜像多阶段构建——构建层 node:24（corepack 启用 pnpm 12.3.4）容器内独立 `pnpm build`（不依赖 frontend job 产物传递，保证镜像可独立复现），运行层 nginx:1.30.4 承载三应用 dist。

### C.6 永久环境约束

1. 本地开发前置：Node 24 LTS、pnpm 12.3.4（`corepack enable && corepack prepare pnpm@12.3.4 --activate`）；首次克隆后在**仓库根**执行 `pre-commit install`（文件卫生钩子），在 `web/` 执行 husky 安装（commit-msg 挂 commitlint——本地 git hook 双工具并存：pre-commit 管文件卫生与格式、husky 管提交信息）。
2. 提交信息遵循 conventional commits（`feat: ...` / `fix(scope): ...`），CI commitlint job 校验（push 用 `--last`、PR 用 base..head 区间）；本地由 husky + commitlint 同规则前置。
3. pnpm 严格依赖：幽灵依赖（未声明即引用）在 pnpm 下默认不可用，属预期行为——缺依赖必须显式声明到对应 package.json；禁用 shamefully-hoist 全局提升。
4. 浏览器兼容基线与 PDA 适配为产品决策，遵循对应模块 Spec；本宪法不约束视觉与交互设计（归 specs/ 与设计稿）。
