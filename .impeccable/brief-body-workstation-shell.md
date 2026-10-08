---
version: 2
status: 灭失重建件（2026-10-08 深夜会话依在位权威重建；原件于 21:40 前后灭失，重建依据=v1 surface brief（web/apps/workstation/.impeccable/surfaces/rkstation-src-views-layout-mainlayout-vue-ead1411b.md）+ 批次 1 交接文档 §2/§3 记载的 v2 换约要点 + 执行文档约定）
---

# Surface Brief v2 · workstation 布局壳（树形侧栏）+ 登录门面 + 首页真数据

## 范围与模式

- 范围：`web/apps/workstation` 布局壳（`views/layout/MainLayout.vue` + `components/AppSidebar.vue`；AppHeader 本批次不动）+ 登录页（`views/login/LoginView.vue` 视觉重设计，表单逻辑零改动）+ 首页（`views/home/HomeView.vue` 接真实后端聚合数据）；配套 `src/styles/` 沿用批次 1 三 CSS 分层（tokens/element-plus/motion），motion.css 补 `TODO(fuy-pulse)` 告警脉冲预留位落位。
- 模式：Operate（工具型工作台；扫描性、一致性、任务完成率高于表达）。登录页=Persuade 与 Operate 之间取 Operate 底色（医疗系统门面，克制、可信、零炫技）。

## 受众与任务

- 受众：医生 / 护士 / 药师 / 收费员 / 设备科等专业操作者（RBAC 角色配置），高密度、长时间、时间压力下的临床操作。
- 任务：侧栏三秒定位目标业务（树形分组+图标双通道扫读）；上班第一屏即知「现在该干什么」（首页真数据：指标带/趋势/候诊/事件流）；登录页三秒完成身份确认（单表单零干扰）。

## 约束（宪法级，逐条硬约束）

- Element Plus 2.14.5 主题化只走 `styles/element-plus.css` 的 `--fuy-* → --el-*` 单向映射层与 `.fuy-*` 挂类；禁改主题 SCSS 编译、禁全局裸改 `.el-*` 选择器、禁 Tailwind/UnoCSS。
- **图标库**：@element-plus/icons-vue 2.3.2（仅 workstation，精确锁版，2026-10-08 用户裁决引入）；图标消费面限定=侧栏树形菜单组/项图标+折叠态图标条；EP 同源图标族，禁止引入其他图标体系（含 emoji/自绘 SVG 图标族）。
- `styles/motion.css` 是全站唯一 keyframes 来源；只动 transform/opacity，禁 width/height 动画；侧栏折叠保持瞬切零动画（铁律延续）；`prefers-reduced-motion` 全局兜底。
- 菜单权限过滤口径不变：权限语义只在路由 meta（单一事实源），菜单不重复登记权限编码；空集会话语义保持（BUG-14 / PR-4D）。
- **岗位维度整体废除**（2026-10-08 用户裁决）：menu.ts 的 posts/abbr/POST_OPTIONS/selectMenuItemsForPost/POST_SELECTION_KEY 及随动类型 PostKey/PostSelection 零死代码清除；MainLayout 删岗位 provide；HomeView 删岗位消费；侧栏不再按岗位过滤（RBAC 权限过滤已足够）。
- 标题「富云医护工作站」被 App.spec 冒烟测试锁定，禁改名；品牌头「富云/富」字标形态保留。
- LoginView 表单逻辑零改动：表单模型/校验规则/提交逻辑/store 交互全保留，「请登录」文案锚点保留；只动视觉与模板结构。
- **零伪数据铁律**：首页一切数字/图表/事件必有真实 API 或 STOMP 主题源；危急值段因检验 M07 未开发按缺位降级先例明示空数组+降级文案。
- 四类模型禁复用（A.7-3）；改动失效的旧测试同 PR 清理，新功能同步交付测试；门禁 = 五连（lint/format:check/type-check/test/build）全绿。

## 选定方向与记忆点

- 方向：「纸质病案 Paper Chart」（2026-10-08 用户二次钉定：批次 1 被推翻的是实现而非设计稿——`.impeccable/mocks/paper-chart/` 样稿=构图权威兼最低设计要求）。
- 记忆点：墨脊书脊导轨上的亮纸白药丸选中态（延续）；树形菜单的组图标+页项图标构成「病案标签架」扫读通道（新增）；页级 2px 墨规下的门牌页首与批注行（延续）；登录页纸墨门面——纸面三级+品牌字标+2px 墨规收底（新增）。

## 未决事项

- portal / bigscreen 套用同一世界 → 批次 5（各 app 自建 `--fuy-*` token）。
- 临床五站产品化重构（门诊医生站/分诊台/护士站/发药工作台/病区床位图「工作台不是查询页」七语法重构）→ 批次 3 顺延另行呈批。
- favicon 替换（现为蓝 #2563eb 占位十字）、朱色白底精确校准终稿 → 另行呈批。
- /ws/ops 自建 STOMP 端点 → 后续批次；本批次事件流复用既有三 STOMP 端点主题。

## Direction contract

THESIS: 病案纸上的文书工作台——全院操作发生在纸质病案的物质世界里：页面即文书，纸白承载工作面、蓝黑墨承文、印泥朱只承状态；拒绝「蓝色 admin」类别默认脸与首页均质卡片罗列，品牌感活在规线三级、密排表格与朱批印章的精确里，绝不抢操作。

OWN-WORLD: 纸白三级 #f5f3ec 工作面 / #fdfcf8 卡面 / #ffffff 输入域；蓝黑墨三级承文 #1e2a44 浓墨 / #565d6e 灰墨 / #676d7b 弱墨；印泥朱 #b42318（深 #8f1a12、洗 #f7e6e2）状态专用永不装饰；状态唯一映射绿 #1f7a33=确认/在档、琥珀 #b45309=警示/冻结；规线三级——2px 墨规收页首（页级）、1px 石规 #c6c0ae（面板）、1px 发丝 #dcd7c9（卡内）；壳层蓝黑墨书脊 #1a2540（深 #131c33）；唯一阴影给弹层（0 12px 28px）；系统黑体栈 + tabular 等宽数字；病历密排 13px/22px；朱批印章语法——描边印=标记、实底斜印=危急、朱笔划销=作废、批注带「谁·何时」。

STORY: 医护打开工作站如翻开当日病案：墨脊导轨上是亮纸白药丸点亮的树形菜单（分组如病案夹章、页项如标签、图标双通道扫读），收起时化为 64px 图标条、悬浮见全名；首页门牌页首下，真实指标带/趋势/候诊/事件流如报表摊开——数字皆可指源、危急以实底斜印抵达、无数据处诚实空态明示；登录即纸墨门面：一张纸、一枚字标、一道墨规，三秒完成身份确认。

FIRST VIEWPORT（壳层）: 左 240px 墨脊＝品牌亮纸白字标「富云」（折叠 64px 态「富」）+ 树形菜单（9 分组=可折叠父节点 el-sub-menu 带组图标、34 页项带项图标、默认全展开、药丸选中态延续）；顶 56px 亮纸白条＝折叠开关 + 「富云医护工作站」锚点 + 全局患者检索入口 + 用户下拉（弹层硬 snap）；侧栏折叠=瞬切零动画 64px 图标条，页项悬浮 el-tooltip 全名（同样瞬切）；首页＝门牌页首（问候语 + 批注行「登录名 · 日期」，2px 墨规收底，岗位描边印随岗位维度废除移除）+ 真数据报表区（指标带六格 / 14 日趋势 / 候诊表 / 事件流，全部真实 API/STOMP 源）+「常用入口」紧凑链接条 + 诚实空态；登录页＝纸墨门面（纸面三级铺陈 + 品牌字标 + 「请登录」锚点 + 2px 墨规收底 + 唯一阴影只给弹层——旧登录卡 box-shadow 属收编对象）。动效语法：弹层硬 snap + stagger 进场 + 全局 reduced-motion 兜底；侧栏折叠瞬切零动画。

FORM: 纸质病案 Paper Chart——用户锁定（批次 1 方向掷骰 seed 21571ba6 IMPECCABLE'S PICK；2026-10-08 方向二次钉定：seed efd8b4da 重掷作废，样稿=构图权威）。HTML 样稿为构图与材质权威兼**最低设计要求**：实现须在样稿之上更细致打磨——更美观精致、高级视觉、流畅动效交互、高渲染性能；禁以「还原样稿」为完成标准。

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.
