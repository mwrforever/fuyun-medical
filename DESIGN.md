---
name: 富云医疗（fuyun-medical）三端统一设计系统 · 批次 1「纸质病案 Paper Chart」
description: 病案纸上的文书工作台——纸白三级工作面、蓝黑墨承文、印泥朱只承状态的高密度临床设计系统（批次 1 落地于 workstation）
colors:
  ink-emphasis: "#1e2a44"
  ink-deep: "#131c33"
  ink-secondary: "#565d6e"
  ink-faint: "#676d7b"
  ink-washed: "#a6abb8"
  paper-page: "#f5f3ec"
  paper-card: "#fdfcf8"
  paper-input: "#ffffff"
  paper-hover: "#efece1"
  paper-wash: "#e9e7de"
  header-paper: "#f2efe5"
  row-hover-paper: "#f6f3ea"
  shell-rail: "#1a2540"
  shell-text: "rgba(253, 252, 248, 0.88)"
  shell-dim: "rgba(253, 252, 248, 0.62)"
  seal-red: "#b42318"
  seal-red-deep: "#8f1a12"
  wash-red: "#f0d9d3"
  status-green: "#1f7a33"
  status-amber: "#b45309"
  triage-orange: "#c2410c"
  triage-blue: "#1d4ed8"
  rule-stone: "#c6c0ae"
  rule-hairline: "#dcd7c9"
  focus-ring: "rgba(30, 42, 68, 0.35)"
typography:
  key-count:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', sans-serif"
    fontSize: "24px"
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: "normal"
  home-title:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', sans-serif"
    fontSize: "20px"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "normal"
  heading-page:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', sans-serif"
    fontSize: "18px"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "normal"
  heading-card:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', sans-serif"
    fontSize: "16px"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "normal"
  body:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
  body-dense:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: "22px"
    letterSpacing: "normal"
  caption:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', sans-serif"
    fontSize: "12px"
    fontWeight: 500
    lineHeight: 1.3
    letterSpacing: "normal"
rounded:
  sm: "2px"
  md: "4px"
  lg: "8px"
  xl: "12px"
  full: "999px"
spacing:
  "1": "4px"
  "2": "8px"
  "3": "12px"
  "4": "16px"
  "5": "20px"
  "6": "24px"
  "8": "32px"
  "10": "40px"
  "12": "48px"
  "16": "64px"
components:
  nav-menu-item:
    backgroundColor: "transparent"
    textColor: "{colors.shell-text}"
    rounded: "{rounded.sm}"
    height: "40px"
  nav-menu-item-active:
    backgroundColor: "{colors.paper-card}"
    textColor: "{colors.ink-emphasis}"
    rounded: "{rounded.sm}"
    height: "40px"
  post-chip:
    backgroundColor: "transparent"
    textColor: "{colors.shell-text}"
    rounded: "{rounded.sm}"
    height: "28px"
  post-chip-active:
    backgroundColor: "{colors.paper-card}"
    textColor: "{colors.ink-emphasis}"
    rounded: "{rounded.sm}"
    height: "28px"
  brand-mark:
    backgroundColor: "{colors.paper-card}"
    textColor: "{colors.ink-deep}"
    rounded: "{rounded.md}"
    height: "24px"
  button-primary:
    backgroundColor: "{colors.ink-emphasis}"
    textColor: "#ffffff"
    rounded: "{rounded.md}"
    padding: "8px 16px"
  button-primary-hover:
    backgroundColor: "#626a7c"
  button-primary-active:
    backgroundColor: "#182236"
  home-quick-link:
    backgroundColor: "transparent"
    textColor: "{colors.ink-emphasis}"
    rounded: "{rounded.md}"
    padding: "5px 10px"
  post-stamp:
    backgroundColor: "transparent"
    textColor: "{colors.ink-emphasis}"
    rounded: "{rounded.sm}"
    height: "22px"
  triage-badge-l1:
    backgroundColor: "{colors.seal-red}"
    textColor: "#ffffff"
    rounded: "{rounded.sm}"
    height: "22px"
---

# Design System: 富云医疗（fuyun-medical）三端统一设计系统 · 批次 1「纸质病案 Paper Chart」

## Overview

**Creative North Star: 「纸质病案 Paper Chart——病案纸上的文书工作台」**

全院操作发生在纸质病案的物质世界里：页面即文书，纸白承载工作面、蓝黑墨承文、印泥朱只承状态。本系统拒绝「蓝色 admin」的类别默认脸，也拒绝首页均质卡片罗列——品牌感活在规线三级、密排表格与朱批印章的精确里，绝不抢操作。这是一个为高密度、长时间、时间压力下的临床操作构建的系统：一屏可见行数优先于装饰表达，扫读性与任务完成率优先于视觉惊喜。

信息以「双通道」抵达：状态色永远伴随文字（或形状/字重）第二通道，形状本身即选中语言（墨脊书脊上的亮纸白药丸），关闭动效后一切信息仍然完整。纸的层级靠亮度差不靠投影：工作面沉纸白、卡面亮纸白、表单域最亮纸面；深度由规线三级表达，唯一阴影只给弹层。医护打开工作站如翻开当日病案——门牌页首、岗位描边印、「谁·何时」批注行、常用入口如病案标签架三秒直达业务。

世界锁定记录：「纸质病案 Paper Chart」2026-10-08 由用户锁定（方向掷骰 seed 21571ba6）；同日「刷手服青绿 Scrub」世界（seed 261b0951）被用户整体推翻，其色板与规则不再具任何权威地位，仅此处留痕、值不入本文档。

**批次边界（如实记录）**：批次 1 已换血面 = 布局壳（`views/layout/` 的 MainLayout / AppSidebar / AppHeader / menu.ts）+ 首页（`views/home/HomeView.vue`）+ styles 层（`tokens.css` / `element-plus.css` / `motion.css`，`index.css` 为固定顺序入口）；其余 37 个存量视图凭「token 名不变、仅值换血」的兼容策略自动承继新世界，未逐页重排。off-world 残留（如 el-empty 默认「纸箱」插画）已登记 TASK.md W-103，随批次 2 临床五站收编——本文档不得被读作全站换血完成。token 体系唯一权威来源为 `web/apps/workstation/src/styles/tokens.css`，EP 映射层为 `styles/element-plus.css`，动效层为 `styles/motion.css`；token 值冲突时以 tokens.css 为准。本文与其机器可读侧车 `.impeccable/design.json` 同源生成。

**Key Characteristics:**

- 纸白三级承载工作面：工作面 #f5f3ec 沉一档、卡面 #fdfcf8 亮一档、表单域 #ffffff 最亮——纸的层级靠亮度差，不靠投影
- 蓝黑墨三级承文：浓墨 #1e2a44 承正文与主操作（墨即操作）、灰墨 #565d6e 批注、弱墨脚注
- 印泥朱 #b42318 只承状态（危急/停用/作废），永不装饰
- 状态唯一映射：绿=确认/在档、琥珀=警示/冻结、朱=危急、灰=弱化；色永不单独承义（必配文字/形状第二通道）
- 规线三级：2px 墨规=页级、1px 石规=面板、1px 发丝=卡内；唯一阴影给弹层
- 朱批印章语法：描边印=标记、实底印=危急/强状态、朱笔划销=作废、批注带「谁·何时」
- 壳层=蓝黑墨书脊导轨 #1a2540 + 亮纸白药丸选中系（形状即选中通道）
- 中文黑体系统栈零网络字体 + 全站 tabular 等宽数字；病历密排 13px/22px
- 浏览器原生面收编：朱洗选区、即墨插入符、穿纸细轴、墨焦点环
- 动效只动 transform / opacity；motion.css 是全站唯一 keyframes 来源；弹层硬 snap；reduced-motion 全局兜底

### 批次 2+ 承接项（记录于 2026-10-08，均为未建成状态）

- **portal / bigscreen 套用同一世界**：批次 5 各 app 自建 `--fuy-*` token（禁共享一份）；portal 的 ≥44px 触控、bigscreen 的 3–10 米可读字号阶梯待各自落地定稿
- **朱批印章组件 / STOMP 告警脉冲徽标**：签名交互随批次 2 接真实数据源落地（motion.css 留 `TODO(fuy-pulse)` 预留位，载体=顶栏告警铃未读数；无真实数据源不落地、不留死代码）
- **off-world 残留收编**：el-empty 默认「纸箱」插画等（TASK.md W-103），走 element-plus.css 既有 `.fuy-*` 挂类通道，随批次 2 临床五站一并收编
- **岗位→菜单映射业务确认**：menu.ts 五岗位归属按总 Spec FU-M01-11 起草，待业务侧确认后微调
- **图标方案 / favicon / 朱色白底精确校准终稿**：均属未决呈批事项（现无图标库，文字缩写体系；favicon 仍为蓝 #2563eb 占位十字），本文档不预设结论

### 动效语法（世界级）

时长三档：fast 120ms（悬停/按压/微反馈）、base 200ms（显隐过渡/状态翻转）、slow 320ms（进场/FLIP/场景级）。缓动家族：standard `cubic-bezier(0.2, 0, 0, 1)`（位移默认）、enter `cubic-bezier(0.16, 1, 0.3, 1)`（进场减速）、exit `cubic-bezier(0.4, 0, 1, 1)`（离场加速）、emphasis `cubic-bezier(0.19, 1, 0.22, 1)`（成功确认，无过冲）。级联步长 40ms，最多 5 档，第 6 项起并发播放。

四种既定语法：**stagger 进场**（`.fuy-stagger` + 子元素内联 `--fuy-stagger-index`，fuy-rise 上浮 8px）；**岗位换装 FLIP**（AppSidebar.vue `playGroupsFlip` 为全站列表重排的参考实现——pre-flush 捕获旧位 → nextTick 反演 → 强制回流 → 逐组级联归位）；**弹层硬 snap**（`.fuy-snap-popper`：弹层是一页翻起的纸，硬进不回弹——进场单轴 translateY(-4px)→0 + opacity，fast 档一次到位，离开直切）；**reduced-motion 全局兜底**（reduce 下全部动画/过渡直达终态，唯一豁免类 `.fuy-loading-essential` 为加载指示保留 1.5s 循环——停转会被误读为卡死）。

**折叠瞬切铁律**：侧栏展开 240px / 折叠 64px 宽度切换零动画（`:collapse-transition="false"`）。这是「禁 width/height 动画」红线在壳层上的直接推论。

## Colors

一句话性格：面是摊开的病案纸（三级亮度），字是蓝黑钢笔墨（三级浓度），状态是印泥盖下的朱——纸墨为本，朱色极少而准。

### Primary（蓝黑墨承文）

- **浓墨 Ink Emphasis** (#1e2a44)：品牌主色＝`--fuy-color-brand`＝EP `--el-color-primary`。既承文（正文/标题/关键数字，对白底 ~14:1）也承操作（主按钮实底、键盘焦点环、输入插入符）——「墨即操作」
- **书脊深墨 Ink Deep** (#131c33)：`brand-900`，按压态/品牌字标字色/墨脊上的缝
- **灰墨 Ink Secondary** (#565d6e)：次要文本与批注（6.3:1），`--fuy-color-text-secondary`＝`--fuy-color-info-text`；EP 悬停描边同值
- **弱墨 Ink Faint** (#676d7b)：EP 占位符文字
- **褪墨 Ink Washed** (#a6abb8)：EP 禁用文本

### Neutral（纸白三级与规线）

- **工作面纸白 Paper Page** (#f5f3ec)：内容区工作面（`--fuy-surface-page`），沉一档的旧纸底
- **卡面亮纸白 Paper Card** (#fdfcf8)：卡片、弹层、顶栏压条、选中药丸底（`--fuy-surface-card`）
- **表单域最亮纸面 Paper Input** (#ffffff)：输入域与空白填充（`--fuy-surface-input`）
- **纸面悬停暖底 Paper Hover** (#efece1)：`gray-50`＝`brand-50` 同值，白面上按钮/菜单/链接的悬停底
- **墨洗纸 Paper Wash** (#e9e7de)：`brand-100`，选中态浅底（墨洗防「朱=危急」语义误读）
- **表头纸 Header Paper** (#f2efe5)：EP `--el-fill-color-light`，高密度表格表头底
- **行悬停纸 Row Hover Paper** (#f6f3ea)：EP `--el-fill-color-lighter`，表格行悬停底
- **石规 Rule Stone** (#c6c0ae)：1px 面板级规线描边（`--fuy-border-panel`）；穿纸细轴滚动条同源
- **发丝 Rule Hairline** (#dcd7c9)：1px 卡内细缝（`--fuy-border-hairline`）；体温单网格同值

### 壳层（蓝黑墨书脊＝病历夹封皮）

- **书脊 Shell Rail** (#1a2540)：左侧导轨底色（`--fuy-shell-bg`）
- **书脊纸纱 Shell Text** (rgba(253, 252, 248, 0.88))：导轨正文（对墨脊底 ≥4.5:1）
- **书脊弱字 Shell Dim** (rgba(253, 252, 248, 0.62))：分组标题/时间戳
- **书脊悬停纸纱** (rgba(253, 252, 248, 0.08))：导轨悬停底（`--fuy-shell-hover`）
- **书脊深墨缝** (#131c33)：导轨外缘缝——缝的颜色跟着面走，墨脊上的缝是「缝在封皮上」的深墨，不用灰线

### 状态语义色（唯一映射）

双通道铁律下的状态色（全部满足白底 ≥4.5:1）：

- **印泥朱 Seal Red** (#b42318)：危急/停用/作废（6.5:1）；实底白字徽标底同值（分诊Ⅰ级/病危/过敏 4.8:1）；深一档 **朱深** (#8f1a12) 供按压/悬停；**朱洗** (#f0d9d3) 专属文字选区浅底（选区色不承危急语义）
- **确认绿 Status Green** (#1f7a33)：已发药/已结算/在线/空床——确认/在档（5.4:1）
- **警示琥珀 Status Amber** (#b45309)：待支付/待配药/离线/冻结——警示（5.0:1）
- **徽标橙 Triage Orange** (#c2410c)：分诊Ⅱ级-急/病重/预占/预检临床惯例（5.2:1）
- **分诊蓝 Triage Blue** (#1d4ed8)：分诊Ⅳ级-普通诊/体温单体温系临床惯例（6.7:1）——蓝在此世界只表示「普通/常规」分级，不是品牌色
- **弱化灰**＝灰墨 #565d6e：中性态/停用/转出——弱化不抢焦

命名语义别名体系（分诊四级 `--fuy-color-triage-l1..l4`、护理级别 `--fuy-color-nursing-*`、床位五态 `--fuy-color-bed-*`、设备五态 `--fuy-color-device-*`、输液三档 `--fuy-color-infusion-*`、体温单 `--fuy-chart-*`）全部指向上述 palette 值，**零新色值**——模块增量只允许在 semantic 层加语义别名，禁止发明新颜色（M04/M05/M14/M16 先例）。

**实现侧事实**：Element Plus 功能色变量（success/warning/danger/info）保留 EP 默认值未映射（与存量页面硬编码语义一致）；状态文本通道走 `--fuy-color-*-text` 唯一映射，el-tag light 形态文字色经 `.fuy-tag-aa` 挂类修正达 AA。EP 中性面已整体映射落纸墨世界：组件基底=卡面亮纸白、页面基底=工作面纸白、主文本=浓墨、描边=石规浓淡档（#c6c0ae → #d5d0c1 → #ddd8ca → #e7e3d6，深档 #a49e8b / #8a8471 供激活选中）、填充=纸面暖底族、弹层遮罩=墨纱 rgba(30, 42, 68, 0.45)——EP 组件自然长在纸上，不再出现冷灰「灰塑料」杂色。

### Named Rules

**The 印泥朱只承状态 Rule.** 印泥朱 #b42318 只出现在危急/停用/作废三类状态上——状态文字、实底徽标、朱笔划销线、朱洗选区。禁止作装饰：标题、品牌图形、悬停底、大面积铺色一律不得用朱。朱的稀缺性就是危急的可辨识性。

**The 状态唯一映射 Rule.** 状态语义全院唯一：绿=确认/在档、琥珀=警示/冻结、朱=危急/停用/作废、灰=弱化。任何状态色永不单独承义——色标/色带必须伴随文字（或形状/字重）第二通道；任何页面不得私造第四种状态语义色。

**The 墨即操作 Rule.** 主操作=浓墨实底（EP primary 映射后 #1e2a44），品牌色与操作色是同一滴墨——纸上写过的字和签下的确认同色。任何职业色/彩色系不得充当品牌主色：品牌感活在纸墨材质里，不在某个彩色按钮上。

**The 零新色值 Rule.** 新模块的色彩需求只允许在 semantic 层加语义别名指向既有 palette（M04/M05/M14/M16 先例）；临床惯例色（体温蓝/脉搏红/分诊橙）必须收编为 token 后取用，页面禁止自造色值。

**The 单向映射 Rule.** `--fuy-* → --el-*` 的赋值只允许出现在 `styles/element-plus.css` 一个文件里，经 `:root:root` 双写（特异性 0,2,0）或 `.fuy-*` 挂类承载；全仓库禁止裸改 `.el-*` 组件选择器、禁止改 EP 主题 SCSS 编译。方向是单向的：EP 变量可以吃 fuy 值，fuy 层永不引用 el 值。

## Typography

**Display/Body Font:** 中文黑体系统栈——'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', sans-serif（`--fuy-font-family-base`，零网络字体依赖；EP 组件字体经 `--el-font-family` 映射到同一栈）

**Label/Mono Font:** 无独立等宽字体——数字等宽由 `font-variant-numeric: tabular-nums` 承担（在 `:root` 全站继承生效，另有 `.fuy-num` 工具类兜底）

**Character:** 无展示型字体、无品牌字体——这是刻意的选择：文书工作台的字体人格活在字重三档（400 正文 / 600 标题表头 / 700 关键数字）与病历密排的行距精度里，而不是字形里。

### Hierarchy

- **关键计数 key-count** (700, 24px, 1.3)：候诊数/今日挂号数等大数字，必配 tabular-nums
- **门牌标题 home-title** (600, 20px, 1.3)：首页门牌页首问候行（与样稿门牌标题同档）
- **页面标题 heading-page** (600, 18px, 1.3)：页面标题
- **卡片标题 heading-card** (600, 16px, 1.3)：卡片标题、顶栏站点名
- **正文 body** (400, 14px, 1.5)：页面正文基线（与 EP base 一致）
- **病历密排 body-dense** (400, 13px/22px 行高)：`.fuy-dense` 表格正文（表头 600）——一屏可见行数优先的表格基线
- **脚注 caption** (500, 12px)：表格脚注/时间戳/批注行/徽标文字；分组标题与小节题为 600 + letter-spacing 1px

### Named Rules

**The 数字不跳宽 Rule.** 全站数字一律等宽（tabular-nums 在 `:root` 全局生效 + `.fuy-num` 兜底）：列表数字列、计数、金额在轮询刷新时宽度恒定，不因数位变化引起行内抖动。金额场景金额以 string 承载、前端不做计算（类型契约红线，防浮点误差）。

## Layout

三段骨架（`MainLayout.vue`）：左侧 **240px 蓝黑墨书脊导轨**（折叠 64px，宽度瞬切零动画；外缘 1px 书脊深墨缝，滚动轴为纸色细轴）+ 顶部 **56px 亮纸白顶条**（下缘 1px 石规——顶栏是搁在工作面上的一页纸压条，与导轨品牌头 56px 同高对齐，横向接缝齐平）+ **纸白工作面**。整体 `height: 100dvh` + `overflow: hidden`，滚动收敛到内容区，三段骨架自身不滚。

导轨内部结构（自上而下）：品牌头（56px sticky 吸附，亮纸白字标「富云」/折叠「富」单字共用一套形态，24px 高、700、字距 4px/折叠 0）→ 岗位切换器（六枚 28px chip 三列网格，sticky 吸附于品牌头之下，`aria-pressed` 表达选中；折叠态降级为 32×28 单枚恒亮药丸，点击沿选项序循环切换）→ 按岗位过滤的业务域分组菜单（`.fuy-menu`，菜单项 40px 高；分组标题 12px/600/1px 字距书脊弱字；折叠态隐藏分组标题、单字缩写严格居中）。

**岗位工作台模型**：`menu.ts` 一份常量承载四个消费面（侧栏分组渲染、折叠单字缩写、首页入口链接条、岗位过滤纯函数）。菜单可见性 = 权限过滤（路由 meta 单一事实源，BUG-14/PR-4D 空集语义）∩ 岗位过滤（纯函数 `selectMenuItemsForPost`）两道独立口径；六岗位为 全部/医生/护士/药师/收费员/设备科。菜单渲染顺序 = 常量首现序，过滤后清空的分组整组剔除。

工作面节奏：业务页根节点挂 `.fuy-page`（max-width 1600px 居中，防 2K 屏无限拉伸；内边距 16px；纵向 gap 12px）。间距系统为 8px 基线（4px 细分）十档阶梯（4/8/12/16/20/24/32/40/48/64px）：卡内 padding 16px、卡间 12px、表单行距 12px；检索/操作工具条 `.fuy-toolbar` min-height 48px（加载/空态切换零塌陷的 CLS 锁）。

首页 = **报表式编辑构图**（非均质卡片罗列）：门牌页首（问候语 20px/600 + 当前岗位描边印 + 批注行「当前岗位 · 登录名 · 日期」，2px 墨规收底）→ 小节题「常用入口」（灰墨疏排小字）→ 紧凑链接条（flex 换行 gap 6px/10px，单字纸块缩写 20×20 + 名称 13px，stagger 进场，入口数据来自 menu.ts 真实路由、权限∩岗位双道过滤）→ 诚实空态（确无入口时）。指标带/趋势图/事件流属报表构图的裁剪区：无真实 API 支撑一律不落，接真实 API 后归位（批次 2+）。

**响应式事实**：workstation 无响应式断点——院内 PC 工作站是唯一目标形态（PRODUCT.md 运行环境），窄屏下导轨仍占 240px、内容区横向压缩不重排。响应式课题归批次 5 的 portal（患者手机场景）承担，workstation 不预设移动端规则。

## Elevation & Depth

这是一个**平面系统**：深度由规线三级表达，不由投影堆叠表达。**规线三级**（等级森严，不越级使用）：**2px 墨规**＝页级（门牌页首 2px 浓墨收底——页级文书的压章线）；**1px 石规** #c6c0ae＝面板级（顶栏压条下缘、业务面板分界、空态卡虚缝同值）；**1px 发丝** #dcd7c9＝卡内细缝。石规在 EP 描边通道有由浅到深的浓淡档（#e7e3d6 → #ddd8ca → #d5d0c1 → #c6c0ae，深档 #a49e8b / #8a8471 供激活选中描边，悬停描边=灰墨 #565d6e）。缝永远跟着面走：纸面上缝是石规/发丝，墨脊上缝是书脊深墨 #131c33——灰缝属于桌面世界。

全系统唯一的投影 token 是 `--fuy-shadow-md: 0 12px 28px rgba(19, 28, 51, 0.16)`（蓝墨基色）：只用于真正脱离版面的浮起层——EP 弹层（popper/dialog）。EP 侧三档阴影同源（lighter 浅档 0 4px 12px rgba(19, 28, 51, 0.1)）。弹层是浮起的一页纸：卡面亮纸白基底，其下覆一层墨纱 rgba(30, 42, 68, 0.45)——纸上页内页。任何堆叠面板（卡中卡、并排卡）禁止加投影。

### Named Rules

**The 规线不越级 Rule.** 页级分割用 2px 墨规，面板分界用 1px 石规，卡内细节用 1px 发丝；2px 线不下沉到卡内，发丝线不充当页级收底。深度只有两级：版面内（规线分割）与版面外浮起（唯一阴影 token）。

**The 唯一阴影给弹层 Rule.** 投影只属于脱离版面的浮起层（popper/dialog）；卡与卡的层级差靠纸面亮度（#fdfcf8 vs #f5f3ec）与一道规线表达，禁止投影堆叠。

## Shapes

圆角五档，全部收敛在直角倾向：sm 2px（tag、分诊/护理徽标、药丸选中态、印章、岗位 chip）→ md 4px（input/button/品牌字标/入口链接，与 EP 默认一致，勿改）→ lg 8px（卡片/空态卡）→ xl 12px（dialog）→ full 999px（滚动轴圆头等）。

**朱批印章语法**（世界签名，四式）：**描边印＝标记**——1.5px 浓墨描边 + 墨字（首页岗位印 22px；朱印只承危急，岗位是身份故用墨印）；**实底印＝危急/强状态**——印泥朱实底白字（分诊Ⅰ级/病危/过敏，22px/18px 徽标家族）；**朱笔划销＝作废**——`.fuy-tag-strike` line-through 1.5px 朱色划线 + 灰墨字，弱化不删信息（单元格内保持全对比度可读）；**批注带「谁·何时」**——文书页边注语法，任何批注行必带操作者与时间（首页批注行「当前岗位 · 登录名 · YYYY-MM-DD 周X」）。

虚缝＝「这里本来什么都没有」：1px dashed #c6c0ae（石规同值虚线）专属诚实空态，不作他饰。文字缩写体系替代图标（零图标库依赖）：单字淡墨纸块（首页入口 20×20，底 rgba(30, 42, 68, 0.08) 淡墨一成）+ 菜单缩写单字 + 品牌字标共用一套语法。纯 CSS 几何：汉堡开关三条 2px×16px 横线（间距 5px）、下拉指示箭头为 border 三角（4px 边 + 5px 高，currentColor 55% 透明度）——零图标字符、零图标库。

## Components

### 导航 · 墨脊书脊导轨（Signature）

亮纸白药丸选中语言是本系统的签名形态：菜单项 40px 高、左右 8px 收边、2px 圆角，透明底书脊纸纱字；**选中态反白为亮纸白药丸**（#fdfcf8 底 + #1e2a44 墨字 + 600 字重）——像别在墨脊书脊上的胸牌，形状即选中通道。悬停纸纱加深（rgba(253,252,248,0.08)，120ms）。分组标题 12px/600/1px 字距书脊弱字。键盘焦点环在导轨上覆写为白色 2px outline（即墨环在墨脊底上不可见，焦点永远可见）。折叠态：EP 折叠动画关闭瞬切，单字缩写严格居中（`justify-content` + `text-align` 双保险），分组标题隐藏。

### 岗位切换器

六枚 28px chip 三列网格（`aria-pressed` 表达选中）：默认透明底纸纱字 12px/500，选中亮纸白药丸墨字 600（与菜单选中同语法）；折叠态降级为 32×28 单枚恒亮药丸，`aria-label` 承载当前岗位全名，点击沿 POST_OPTIONS 序循环切换。岗位选择状态唯一持有者是 MainLayout（事件上行翻转 + provide 只读下行），刷新复位不持久化。

### 顶栏 · 亮纸白条

透明底按钮家族：折叠开关（32×32 纯 CSS 汉堡，悬停纸面暖底 #efece1）、站点名「富云医护工作站」（16px/600 浓墨，App.spec 冒烟测试文本锚点禁改名）、全局患者检索（纯文字 13px 灰墨，权限过滤可见性，悬停暖底转浓墨）、用户区（40px 点击域 + 纯 CSS 三角箭头）。下拉弹层挂 `.fuy-snap-popper`（硬 snap）。

### 门牌页首（Signature）

首页（及后续页级头部）的文书页眉语法：问候语 20px/600 浓墨 + 岗位描边印（1.5px 浓墨描边、22px 高、2px 圆角、12px/600 墨字——`aria-hidden` 装饰性重复字符）+ 批注行 12px 灰墨（「当前岗位：X · 登录名 Y · YYYY-MM-DD 周Z」，空值以 — 占位不渲染裸空值）；baseline 对齐，2px 墨规收底。

### 常用入口链接条

零描边零卡片的标签架语法：直接坐在纸上的链接（flex 换行 gap 6px/10px，内边距 5px/10px，4px 圆角），单字纸块缩写（20×20，淡墨一成底，11px/600）+ 名称 13px 浓墨；悬停以纸面暖底 #efece1 点亮（120ms）。stagger 进场（`--fuy-stagger-index` ≤5）。数据来自 menu.ts 真实路由入口（同一份数据驱动侧栏与首页），零伪数据。

### 诚实空态

石规虚缝弱形态（1px dashed #c6c0ae，8px 圆角，40px/16px 内边距居中）：标题 14px/600 浓墨 + 说明 13px 灰墨，文案为真实权限/数据语义（「当前会话未被授予任何××业务功能的访问权限」），禁止占位 lorem、禁止伪数据。

### 状态标签与徽标家族

- **el-tag AA 修正**（`.fuy-tag-aa`）：light 形态文字色经 EP 变量修正为 `--fuy-color-*-text` 达 4.5:1
- **朱笔划销**（`.fuy-tag-strike`）：作废语义 line-through 1.5px 朱色 + 灰墨字——三通道之一（划销+印章+图例），弱化后信息仍完整可读
- **分诊四级徽标**（`.fuy-triage-badge--l1..l4`）：22px 高实底白字 12px/600，2px 圆角，色值只取 `--fuy-color-triage-l1..l4`
- **护理级别/病情角标**（`.fuy-nursing-level-badge` / `.fuy-nursing-flag`）：同形态家族（22px / 18px 高）；风险标识用红描边空心形态与实底区分（防红色实底泛滥）

### 按钮

主操作走 EP primary（映射后即浓墨）：默认 #1e2a44 / 悬停 light-3 #626a7c / 激活 dark-2 #182236，白字、4px 圆角——「墨即操作」。次要/工具操作 = 透明底 + 纸面暖底悬停（顶栏按钮家族语法，灰墨承文悬停转墨）。表格操作列间距收窄用 `.fuy-ops-8` 挂类（8px，经 class-name 通道，特异性 (0,3,0) 压 EP 默认 12px）。

### 表格（病历密排）

`.fuy-dense` 挂类：13px 字号 / 22px 行高 / 单元格纵向 padding 5px / 表头纸 #f2efe5 600 字重 / 行悬停纸 #f6f3ea；**禁与 EP `size="small"` 双轨混用**（fuy-dense 唯一）。数字列一律 `.fuy-num`。

### 弹层

popper/dialog：卡面亮纸白 #fdfcf8 + 12px 圆角 + 唯一阴影 `--fuy-shadow-md` + 硬 snap 进场（`.fuy-snap-popper`：translateY(-4px)→0 + opacity 一次到位，无回弹，离开直切）。骨架屏→内容切换 200ms opacity 单属性（`.fuy-content-fade`），占位高度用 min-height 锁定防 CLS。

## Do's and Don'ts

### Do:

- **Do** 用 `.fuy-*` 挂类或 CSS 变量通道定制 Element Plus（`:root:root` 双写 / `.fuy-menu` 同族挂类，特异性恒压 EP 按需注入，不依赖加载顺序）
- **Do** 状态一律双通道：色标/色带 + 文字（或形状、字重）；取状态色只取语义别名 token，禁止页面自造色值
- **Do** 所有数字加 `.fuy-num`（或依赖 `:root` 全局 tabular-nums），数字列宽度恒定不跳宽
- **Do** 高密度表格统一 `.fuy-dense`（13px/22px）；列表重排动效沿 AppSidebar `playGroupsFlip` 的 FLIP 参考实现
- **Do** 进场用 `.fuy-stagger`（子元素内联 `--fuy-stagger-index`，≤5 封顶）；弹层挂 `.fuy-snap-popper` 硬 snap
- **Do** 面板分割沿规线三级（2px 墨规页级 / 1px 石规面板 / 1px 发丝卡内）；业务页根节点挂 `.fuy-page`；新模块色彩需求只允许 semantic 层语义别名指向既有 palette（M04/M05/M14/M16 先例）
- **Do** 页级头部用 2px 墨规收底 +「谁·何时」批注行；身份标记用描边印（墨印），朱印只给危急
- **Do** 键盘焦点环永远可见（全站 `:focus-visible` 2px 墨 outline；导轨上覆写白色；PDA 页用 `--fuy-color-focus-ring` 的 box-shadow 同源变体）
- **Do** 空态诚实：真实权限/数据语义文案，石规虚缝弱形态，禁止占位假数据

### Don't:

- **Don't** 裸改 `.el-*` 组件选择器、禁改 EP 主题 SCSS 编译——定制只走 `element-plus.css` 的 `--fuy-* → --el-*` 单向映射或 `.fuy-*` 挂类；禁造不存在的 EP 变量名（须经 EP base.css 验证在位）
- **Don't** 引入 Tailwind / UnoCSS——样式 = 纯 CSS 自定义属性 + scoped CSS（设计文档 §7.4 红线）
- **Don't** 动画 width/height/top/left——只动 transform 与 opacity；侧栏折叠保持瞬切零动画
- **Don't** 在 motion.css 之外新增 `@keyframes`——它是全站唯一 keyframes 来源，页面级动画零新增
- **Don't** 把 token 上收 packages/shared——命名空间 `--fuy-*` 每 app 各持一份（workstation 含 `--el-*` 映射层；portal/bigscreen 批次 5 各建各的）
- **Don't** 用印泥朱作装饰（标题/品牌图形/悬停底/大面积铺色）；禁止发明第四种状态语义色（绿/琥珀/朱/灰之外的分级场景用既有 palette 语义别名，且必配文字通道）
- **Don't** 用持续闪烁表达状态——状态变化以「色标+文字+一次脉冲」抵达，脉冲载体批次 2 才接入，本批次不做假徽标
- **Don't** 首页回退成均质卡片罗列或虚构指标带/趋势图/事件流——报表式构图的裁剪区无真实 API 不落（零伪数据）
- **Don't** 为 workstation 预设移动端断点、引入图标库或变更 favicon——均属未决呈批事项，走宪法流程

## 工程红线（批次 2-5 执行者必读）

以下纪律为宪法级硬约束，新世界批次（2-5）一切视觉工作不得违背；违者产物不得合入 main。

1. **token 命名空间**：`--fuy-*` 每 app 各持一份（workstation 含 `--el-*` 映射层），禁上收 packages/shared；portal / bigscreen 于批次 5 各建各的 token 文件，套用世界语法而非共享变量。
2. **EP 映射单向通道**：`--fuy-* → --el-*` 赋值只存在于 `styles/element-plus.css`；`:root:root` 双写（特异性 0,2,0，压 EP 按需注入顺序不确定性）或 `.fuy-*` 挂类（`.fuy-dense` / `.fuy-menu` / `.fuy-tag-aa` / `.fuy-snap-popper` / `.fuy-ops-8` 先例，特异性恒压 EP）两种承载方式；禁裸改 `.el-*` 选择器、禁改主题 SCSS 编译、禁造不存在的 EP 变量名。fuy 层永不引用 el 值（单向）。
3. **token 三层纪律**：primitive（palette 色阶/字号/间距）→ semantic（语义别名/组件几何）→ 浏览器原生面收编（选区/插入符/滚动条/焦点环/tabular 数字）；模块增量只允许在 semantic 层加语义别名，零新色值（M04/M05/M14/M16 先例）。
4. **动效红线**：`styles/motion.css` 是全站唯一 keyframes 来源，页面零新增；只动 transform 与 opacity，禁 width/height/top/left 动画；侧栏折叠瞬切零动画；`prefers-reduced-motion` 全局兜底（唯一豁免 `.fuy-loading-essential`）；`TODO(fuy-pulse)` 预留位批次 2 落位。
5. **样式技术栈**：纯 CSS 自定义属性 + scoped CSS；禁 Tailwind / UnoCSS；零新增运行时依赖（含图标库——沿用文字缩写体系，引入图标方案属新依赖决策，走宪法流程）。
6. **样式加载顺序**：`index.css` 固定 tokens → element-plus → motion（main.ts 仅引此一行）。
7. **批次边界自觉**：批次 1 换血面 = 壳层 + 首页 + styles 三 CSS；其余 37 视图为 token 值域兼容自动承继，非逐页重排。批次 2 起逐站收编时走 `.fuy-*` 挂类通道（el-empty 默认插画等 off-world 残留见 TASK.md W-103）；收编完成前不得将本文档引用为「全站完成」。

## 未收编残留（不 canonize 声明）

以下为建成系统中已发现但未修复的 off-world 缺陷，仅作登记、不构成设计系统规则：

- el-empty 默认「纸箱」插画（患者检索页等存量视图）：类别默认脸，与「纸质病案」世界相抵——已登记 TASK.md W-103，随批次 2 收编为诚实空态语法；批次 2-5 的新页面不得效仿（新页面一律走诚实空态）。
- favicon 仍为蓝底 #2563eb 占位十字（与新世界无关的旧占位物）；替换属未决呈批事项。
- LoginView 登录卡 box-shadow（views/login/LoginView.vue，换血前存量）：与「唯一阴影给弹层」规则相抵的非弹层阴影反例；登录页不在批次 1 触碰范围，随批次 2 收编（新增消费一律仅限弹层）。
