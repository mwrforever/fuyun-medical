---
name: 富云医疗（fuyun-medical）workstation 设计系统 ·「暖纸卷宗 Warm Paper Archive」
description: 摊开的病案卷宗上的文书工作台——暖纸十值色域、棕墨承文、双红分工（装饰朱砂/状态印泥）、衬线承门牌、书法承钤印的高密度临床设计系统
colors:
  paper-page: "#f4ecdb"
  paper-card: "#f8f4e9"
  paper-input: "#ffffff"
  paper-deep: "#eadfc8"
  paper-edge: "#d8c9a8"
  ink: "#2a2318"
  ink-soft: "#5a4c38"
  ink-faint: "#8a7a5e"
  cinnabar-decorative: "#a5352c"
  cinnabar-deep: "#82251f"
  cinnabar-light: "#c7544a"
  gold: "#b08d4f"
  seal-red-status: "#b42318"
  status-green: "#1f7a33"
  status-amber: "#b45309"
  triage-orange: "#c2410c"
  triage-blue: "#1d4ed8"
  hover-wash: "#f1e8d7"
  selected-gold-wash: "#ebe2cd"
  shell-rail: "#2a2318"
  shell-text: "rgba(244, 236, 219, 0.92)"
  rule-stone: "#d8c9a8"
  rule-hairline: "rgba(90, 76, 56, 0.18)"
  focus-ring: "rgba(42, 35, 24, 0.35)"
typography:
  page-title:
    fontFamily: "'Noto Serif SC', 'Songti SC', 'STSong', 'SimSun', Georgia, serif"
    fontSize: "22px"
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: "0.06em"
  card-title:
    fontFamily: "'Noto Serif SC', 'Songti SC', 'STSong', 'SimSun', Georgia, serif"
    fontSize: "16px"
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: "normal"
  heading-page:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', sans-serif"
    fontSize: "18px"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "normal"
  key-count:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', sans-serif"
    fontSize: "24px"
    fontWeight: 700
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
  brush:
    fontFamily: "'Ma Shan Zheng', 'KaiTi', 'STKaiti', serif"
    fontSize: "24px"
    fontWeight: 400
    lineHeight: 1.2
    letterSpacing: "0.2em"
rounded:
  sm: "2px"
  md: "4px"
  seal: "6px"
  lg: "8px"
  frame: "10px"
  card: "14px"
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
  page-head-title:
    fontFamily: "serif-stack"
    fontSize: "22px"
    fontWeight: 700
    borderBottom: "2px solid {colors.ink}"
  nav-menu-item-active:
    backgroundColor: "{colors.paper-card}"
    textColor: "{colors.ink}"
    rounded: "2px"
    height: "40px"
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "#ffffff"
    rounded: "4px"
  empty-state:
    border: "1px dashed {colors.rule-stone}"
    titleFontFamily: "serif-stack"
    markFontFamily: "brush-stack"
---

# Design System: 富云医疗 workstation ·「暖纸卷宗 Warm Paper Archive」

## Overview

**Creative North Star:「暖纸卷宗——全院运转在同一部摊开的病案卷宗里」。**

登录页是这部卷宗的门厅：医护在暖纸封面上以朱砂印鉴签认身份（「启封当日病案」）；业务页是同一部卷宗的内页——工作面是微黄的陈年纸底，卡片是一页页亮纸，弹层是浮起的一页纸，规线是墨笔格线，确认是钤印，批注必带「谁·何时」。本系统拒绝「蓝色 admin」的类别默认脸，也拒绝均质卡片罗列；品牌感活在材质的精确里：暖纸十值色域、棕墨承文、金线装订、朱砂印鉴，绝不抢操作。这是为高密度、长时间、时间压力下的临床操作构建的系统：一屏可见行数优先于装饰表达，扫读性与任务完成率优先于视觉惊喜。

信息以「双通道」抵达：状态色永远伴随文字（或形状/字重）第二通道，形状本身即选中语言；纸的层级靠亮度差不靠投影。氛围级动效（六层底纹/鼠标视差/行军线/书法底字）为登录门厅专属；业务页动效走「卷宗文书语法」，丰富而克制。

**世界换血记录**：「纸质病案 Paper Chart」（2026-10-08 锁定，蓝黑墨/纸白三级/印泥朱只承状态）为上一代世界；2026-10-10 用户裁定以登录页 v3「暖纸卷宗+朱砂印鉴」为全站标准执行换血（槽位名不变仅值换血，否定双轨制），换血映射见文末「换血实施记录」。「刷手服青绿 Scrub」（2026-10-08 推翻）仅此处留痕。

**Key Characteristics:**

- 暖纸三级承载工作面：工作面 #f4ecdb 沉一档、卡面 #f8f4e9 亮一档、表单域 #ffffff 最亮——层级靠亮度差，不靠投影
- 棕墨三级承文：浓墨 #2a2318 承正文与主操作（墨即操作）、灰墨 #5a4c38 批注、弱墨 #8a7a5e 装饰与脚注
- **双红分工**：装饰朱=朱砂 #a5352c（品牌印鉴/走线/题字/装饰叙事），状态朱=印泥朱 #b42318（危急/停用/作废/校验错误）——两类朱永不混用于同一语义位
- **字体三分**：衬线 Noto Serif SC 承门牌/卷宗标题与叙事区，黑体系统栈承一切操作与数据面，书法 Ma Shan Zheng 只承钤印与装饰字（禁入正文/表格/表单）
- 状态唯一映射：绿=确认/在档、琥珀=警示/冻结、印泥朱=危急、灰=弱化；色永不单独承义
- 规线三级：2px 墨规=页级、1px 石规（纸缘 #d8c9a8）=面板、1px 发丝=卡内；阴影三级（sm/md/lg 墨基）只给浮起层
- 壳层=棕墨卷宗封皮导轨 #2a2318 + 亮纸药丸选中系（形状即选中通道）
- 子集本地字体（Noto Serif SC 400/600/700 + Ma Shan Zheng 400，零外链）+ 全站 tabular 等宽数字；病历密排 13px/22px
- 动效只动 transform/opacity；motion.css 是全站唯一 keyframes 来源；弹层硬 snap；reduced-motion 全局兜底（时长+延迟双归零）

### 动效语法（世界级）

时长三档：fast 120ms（悬停/按压/微反馈）、base 200ms（显隐过渡/状态翻转）、slow 320ms（进场/FLIP/场景级）。缓动六族：standard `cubic-bezier(0.2,0,0,1)`、enter `cubic-bezier(0.16,1,0.3,1)`、exit `cubic-bezier(0.4,0,1,1)`、emphasis `cubic-bezier(0.19,1,0.22,1)`、**out `cubic-bezier(0.22,1,0.36,1)`（门面浮入）、ink `cubic-bezier(0.65,0.05,0.36,1)`（钤印/下划线）**。级联步长 40ms ≤5 档。

既定语法：**stagger 进场**（`.fuy-stagger` + 子元素内联 `--fuy-stagger-index`）；**路由切换过渡**（MainLayout `Transition name="fuy-page" mode="out-in"`：旧页 120ms 直退、新页 240ms 上浮显影，页内 stagger 自带节奏不叠加）；**弹层硬 snap**（`.fuy-snap-popper`：translateY(-4px)→0 fast 档一次到位，离开直切）；**钤印确认**（`.fuy-stamp-toast` + `fuy-stamp-in` 落章三拍，只承成功确认）；**下划展开**（聚焦朱砂下划自中心 scaleX 展开，0.45s ink）；**呼吸点**（`.fuy-live-dot` 伪元素光环，仅实时/进行语义）；**FLIP 列表重排**（`.fuy-flip`，AppSidebar `playGroupsFlip` 参考实现）。折叠瞬切铁律不变：侧栏 240↔64 宽度零动画。

**氛围动效专属边界**：六层底纹（墨晕呼吸/缓旋双印/祥云缓移/浮墨字/双线流水/光带缓扫）、鼠标视差、行军线走线、书法底字呼吸——**仅登录门厅（LoginView）可用**；业务页零氛围动效，403 页书法水印保持静态。

## Colors

一句话性格：面是摊开的陈年卷宗纸（暖黄三级），字是写透纸背的棕墨（三级浓度），印是两方——朱砂印鉴承门面与叙事，印泥朱承危急与状态，金线只管装订。

### Primary（棕墨承文）

- **浓墨 Ink** (#2a2318)：品牌主色＝`--fuy-color-brand`＝EP primary。承文（正文/标题/关键数字，工作面 13.2:1）也承操作（主按钮实底/焦点环/插入符）——「墨即操作」
- **灰墨 Ink Soft** (#5a4c38)：次要文本与批注（7.1:1）、悬停亮一档、悬停描边
- **弱墨 Ink Faint** (#8a7a5e)：装饰小字/图标/水印/占位符——非正文通道，信息文本一律 ≥12px 且优先灰墨以上
- **墨加深档 Ink Deep** (#1d1811，申请值 A-1)：按压态与书脊外缘缝

### Neutral（暖纸三级与规线）

- **工作面暖纸 Paper Page** (#f4ecdb)：内容区工作面，沉一档的陈年纸底
- **卷宗卡面 Paper Card** (#f8f4e9)：卡片、弹层、顶栏压条、选中药丸底（paper 0.6 于白结算）
- **表单域最亮纸面 Paper Input** (#ffffff)：输入域与空白填充
- **悬停暖底 Hover Wash** (#f1e8d7)：白面上按钮/菜单/链接悬停底（paper-deep 半洗）
- **金洗纸 Selected Wash** (#ebe2cd)：选中态浅底（gold 0.18 洗，金线装订同源，避「朱=危急」误读）
- **石规 Rule Stone** (#d8c9a8)＝**纸缘 Paper Edge**：1px 面板规线；滚动轴/虚缝同源
- **发丝 Rule Hairline** (rgba(90,76,56,0.18))：1px 卡内细缝；体温单网格同值

### 壳层（棕墨卷宗封皮）

- **封皮 Shell Rail** (#2a2318)：左侧导轨底色（浓墨封皮）
- **封皮纸字 Shell Text** (rgba(244,236,219,0.92))：导轨正文（对封皮底 ≈11:1）
- **封皮纸纱悬停** (rgba(244,236,219,0.10))：导轨悬停底
- **封皮外缘缝** (#1d1811，申请值)：缝在封皮上——缝的颜色跟着面走

### 双红（本世界签名规则）

- **装饰朱＝朱砂 Cinnabar** (#a5352c / 深 #82251f / 浅 #c7544a)：品牌「富」印块、竖排朱印、签牌下划线、tabs 签线、tagline 左线、「签」分隔字、聚焦下划展开线、钤印确认浮层、书法底字。**永不承状态语义**（禁作状态文字/状态徽标底/错误通道）
- **状态朱＝印泥朱 Seal Red** (#b42318)：危急/停用/作废/校验错误/驳回（工作面 5.6:1、实底白字 6.6:1）。**永不作装饰**。登录门厅朱砂兼承校验态为既有特例（零回归保护），业务页一律印泥朱

### 状态语义色（唯一映射，新纸面全档核验见契约 ③.2）

- **确认绿** (#1f7a33)：已发药/已结算/在线/空床（工作面 4.6:1）
- **警示琥珀** (#b45309，暖化申请 A-2→#9a4708)：待支付/待配药/离线/冻结
- **分级橙** (#c2410c，暖化申请 A-3→#b03a0c)：分诊Ⅱ级/病重/预占/已流转
- **分诊蓝** (#1d4ed8)：分诊Ⅳ级/体温单体温系——蓝只表示「普通/常规」，不是品牌色
- **弱化灰**＝灰墨 #5a4c38：中性态/停用/转出
- 命名别名族（triage/nursing/bed/device/infusion/chart）全部 semantic 别名指向 palette，零新色值（M04/M05/M14/M16 先例）

### Named Rules

**The 双红分工 Rule.** 装饰朱（朱砂 #a5352c 族）只出现在品牌印鉴、朱砂走线、题字与装饰叙事上；状态朱（印泥朱 #b42318 族）只出现在危急/停用/作废/校验错误上。两类朱永不互换、永不混用于同一语义位；见到红先问「这是印鉴还是印泥」。

**The 状态唯一映射 Rule.** 绿=确认/在档、琥珀=警示/冻结、印泥朱=危急/停用/作废、灰=弱化，全院唯一；色永不单独承义（必配文字/形状第二通道）；禁私造第四种状态语义色。

**The 墨即操作 Rule.** 主操作=浓墨实底，品牌色与操作色是同一滴墨；职业色/彩色系不得充当品牌主色，朱砂不是操作色。

**The 零新色值 Rule.** 新色彩需求只在 semantic 层加语义别名指向既有 palette；全站新值只能出自登录十值及其 alpha 派生；状态族暖化微调必须走申请表审批（契约 §③.3），页面禁自造色值。

**The 单向映射 Rule.** `--fuy-* → --el-*` 赋值只存在于 element-plus.css，经 `:root:root` 双写或 `.fuy-*` 挂类；禁裸改 `.el-*`、禁改 EP 主题 SCSS、禁造不存在的 EP 变量名；fuy 层永不引用 el 值。

**The 数字不跳宽 Rule.** 全站数字等宽（`:root` tabular-nums + `.fuy-num` 兜底）；金额以 string 承载、前端不做计算。

**The 规线不越级 Rule.** 2px 墨规=页级收底，1px 石规=面板分界，1px 发丝=卡内细节；虚缝（dashed）专属「这里本来什么都没有」与装订语义。

**The 阴影三级给浮起层 Rule.** sm=小面悬浮（签牌）、md=标准弹层（popper/drawer）、lg=大浮面（dialog）；版面内静态堆叠禁投影，层级靠纸面亮度与规线。

## Typography

**三分矩阵**（精确边界）：

- **衬线 `--fuy-font-family-serif`**（'Noto Serif SC','Songti SC','STSong','SimSun',Georgia,serif）：门牌页首标题（22px/700/0.06em）、卷宗卡标题（16px/700）、dialog/drawer 标题、诚实空态主句、403 主句。**禁入**表格/表单/按钮/标签
- **黑体 `--fuy-font-family-base`**（'PingFang SC','Microsoft YaHei','Noto Sans SC',sans-serif）：一切操作与数据面默认继承
- **书法 `--fuy-font-family-brush`**（'Ma Shan Zheng','KaiTi','STKaiti',serif）：钤印家族字（品牌印块/竖排朱印/钤印浮层/「签」分隔字/空态书法字/403 水印）。**凡承载业务信息的文本一律禁书法**

字体加载：四件子集 woff2 本地化（`src/assets/fonts/`，合计 ~116KB），`styles/fonts.css` 全局 `@font-face`（font-display: swap，index.html preload），零外链；LoginView scoped 内声明保留不动（同描述符合并零回归）。

**Hierarchy**：门牌标题 22/700 衬线 → 页面标题 18/600 → 卷宗卡题 16/700 衬线 → 正文 14/400 → 病历密排 13/22（表头 600）→ 脚注/批注行/徽标 12/500。关键计数 24/700 + tabular。可读文本下限 12px；10-11.5px 仅限 aria-hidden 装饰层。

### Named Rules

**The 字体三分 Rule.** 衬线只上「门面」，黑体只上「数据」，书法只上「印章」；跨区使用即违宪——数据表里出现衬线、正文里出现书法都属缺陷。

## Layout

三段骨架（MainLayout）：左 **240px 棕墨封皮导轨**（折叠 64px 瞬切零动画；外缘 1px 封皮缝；滚动轴纸色细轴）+ 顶部 **56px 卷宗卡面顶条**（下缘 1px 石规）+ **暖纸工作面**。`height:100dvh` + 滚动收敛内容区。

导轨内部：品牌头（56px sticky，衬线字标「富云」/折叠书法「富」单字）→ 树形菜单（el-sub-menu 分组默认全展开 + el-menu-item 40px 药丸选中：亮纸底+浓墨字 600；43 枚 icons-vue 线性图标，组/项不重名）→ 收起态 64px 图标条（悬浮弹层/tooltip 瞬切全名）。菜单可见性=权限过滤（路由 meta 单一事实源）。

**工作面节奏**：业务页根挂 `.fuy-page`（max-width 1600px，内边距 16px，纵向 gap 12px）。8px 基线十档间距不变。页面纵向序：**门牌页首 → 工具栏/筛选区 → 卷宗卡（表格/表单/看板）→ 页脚批注**。工具条 `.fuy-toolbar` min-height 48px（CLS 锁）。

**门牌页首**（全站页级头部标准）：衬线标题 22/700 + 英文小注（Georgia 11/0.24em，装饰层可省）+ 右挂状态胶囊 + 批注行「谁·何时」（12px 灰墨，空值 — 占位零伪数据）+ 2px 墨规收底。HomeView 门牌同构。

**响应式事实**：workstation 以 1440+ 桌面为唯一目标形态，断点仅防破版（900px）；PDA 页 480px 移动自持基线独立保留。

## Elevation & Depth

平面系统：版面内深度靠纸面亮度差与规线三级；浮起层靠阴影三级。**规线三级**：2px 墨规（门牌收底）、1px 石规 #d8c9a8（顶栏下缘/面板分界）、1px 发丝 rgba(90,76,56,0.18)（卡内）。EP 描边通道为石规浓淡档（rgba(216,201,168,x) 系）+ 金深档（rgba(176,141,79,x)，激活/选中描边——金线装订语义）。

**阴影三级**（墨基 #2a2318 alpha）：sm `0 4px 10px rgba(42,35,24,0.1)`、md `0 10px 24px -6px rgba(42,35,24,0.18)`、lg `0 24px 60px -12px rgba(42,35,24,0.28), 0 4px 12px rgba(42,35,24,0.1)`。弹层是浮起的一页纸：卡面底 + 其下墨纱 rgba(42,35,24,0.5)。

**虚缝装订**：`.fuy-stitch-ink`（弱墨 8px 周期）/ `.fuy-stitch-gold`（金线 10px 周期）2px 竖向虚缝=卷宗装订语义（叙事卡/摘要卡左缘）；空态虚缝=1px dashed 石规弱形态。「签」字分隔（`.fuy-sign-divider`）：1px 渐变线 + 书法「签」盖线，卡内文书分界。

### Named Rules

**The 装订语义 Rule.** 虚缝与金线只表达「装订/空缺」两类文书语义，不作通用装饰线；禁在按钮、徽标、表格线上使用虚缝。

## Shapes

圆角档位：sm 2px（tag/徽标/药丸）→ md 4px（input/button）→ **seal 6px（印鉴块/钤印浮层）** → lg 8px（el-card 存量过渡）→ **frame 10px（卷框/页框）** → **card 14px（卷宗卡/dialog）** → full 999px（胶囊/轴头）。直角倾向不变。

**钤印四式**：描边印=标记（1.5px 浓墨描边墨字，身份/岗位类）；实底印=强状态（印泥朱实底白字徽标家族）；朱笔划销=作废（`.fuy-tag-strike` 1.5px 印泥朱划线+灰墨字）；钤印确认=成功反馈（书法字朱砂实底浮层 `fuy-stamp-in` 落章，禁承失败/危险）。文字缩写体系与纯 CSS 几何（汉堡/三角箭头）沿旧世界不变。

## Components

### 导航 · 棕墨封皮导轨（Signature）

亮纸药丸选中语言：40px 菜单项、透明底纸纱字、选中反白亮纸药丸+浓墨 600；悬停纸纱加深 120ms；导轨焦点环覆写纸白。分组标题 12px/600 书脊弱字。树形进场级联 `.fuy-tree-stagger`（初载一次）+ 组展开叶项显影 `fuy-leaf-reveal-in`。品牌字标衬线「富云」/折叠书法「富」。

### 顶栏 · 卷宗卡面条

折叠开关（纯 CSS 汉堡）、站点名「富云医护工作站」（16px/600 衬线可选，App.spec 文本锚点禁改名）、患者检索文字钮（权限过滤）、用户下拉（`.fuy-snap-popper`）。悬停暖底 120ms。

### 门牌页首（Signature）

见 Layout。DOM：`.fuy-page-head > .fuy-page-head-main (.fuy-page-title + .fuy-page-en) + .fuy-page-status (.fuy-status-pill) + .fuy-page-note`。

### 卷宗卡（`.fuy-card`）

卡面亮纸白 + 发丝描边 + radius 14，卡头衬线 16/700 + 发丝分隔；`--stitch` 变体左缘金虚缝；卡内分区用「签」分隔或发丝。表格卡禁虚缝。

### 工具栏与筛选区

`.fuy-toolbar`（48px CLS 锁）+ `.fuy-filter`（label 疏排 + 40px 控件 + 右挂操作钮）。表单整脸挂 `.fuy-form`：label 13px 灰墨疏排、输入最亮纸白+石规 inset 描边、聚焦墨环+朱砂下划展开、错误印泥朱显影（`fuy-note-in`）。

### 密排数据表（病历密排）

`.fuy-dense` 唯一：13px/22px、表头纸 #f2ecdc 600、行悬停纸、斑马 rgba(234,223,200,0.35)、边线发丝；数字列 `.fuy-num`；操作列 `.fuy-ops-8`。分页右对齐、激活页墨底纸字。

### 弹层

dialog：卡面底 + radius 14 + shadow-lg + 衬线标题 + 头部发丝；drawer：右推 520/640 两档 + 头部「谁·何时」批注槽；popper：卡面底 + shadow-md + 硬 snap。遮罩=墨纱 rgba(42,35,24,0.5)。提交成功盖 `.fuy-stamp-toast`（aria-live polite，≤1200ms 收场）。

### 状态徽标与胶囊

既有徽标家族（分诊/护理/床位/设备/输液）零改动承新值；通用 `.fuy-status-pill`（语义色 8%~12% 洗底+同色描边+同色文字 12/600）+ `.fuy-live-dot` 呼吸点（仅实时/进行语义）。双通道铁律：必带文字。

### 诚实空态（`.fuy-empty`）

石规虚缝框 + 书法「空」48px 弱墨（aria-hidden）+ 主句 14/600 衬线 + 说明 13px 灰墨给下一步。文案语法=`暂无〈业务客体〉`/`尚无〈业务客体〉`，禁「暂无数据」裸词、禁 lorem、禁伪数据；el-empty 默认纸箱插画全站压制（`.fuy-page/.fuy-dialog/.fuy-drawer` 子树 display:none）。错误态=「说问题+给恢复+重试钮」，不得伪装成空态。

### 加载骨架与页脚批注

`.fuy-sk` 静态金洗纸块（零 shimmer），min-height 锁 CLS，骨架→内容 200ms fade。`.fuy-page-foot`：12px 灰墨+上缘发丝，只放数据时点/刷新语义。

## Do's and Don'ts

### Do:

- **Do** 用 token 消费一切颜色（页面零 hex 字面量）；新需求走 semantic 别名或申请表
- **Do** 状态双通道（色+文字/形状）；实底印只给强状态；钤印只承成功
- **Do** 门牌页首（衬线标题+「谁·何时」批注+墨规收底）作为每页开头；表单挂 `.fuy-form`；弹层挂 `.fuy-dialog/.fuy-drawer`；popper 挂 `fuy-snap-popper`
- **Do** 数字加 `.fuy-num`；表格统一 `.fuy-dense`；进场用 `.fuy-stagger`（≤5 档）
- **Do** 空态走 `.fuy-empty` 空态脸；错误态说问题给恢复
- **Do** 动效只 transform/opacity，长列表分页/虚拟化，STOMP 帧批处理（≥500ms 或 rAF 合并）

### Don't:

- **Don't** 裸改 `.el-*`、改 EP SCSS、造不存在的 EP 变量；禁 Tailwind/UnoCSS
- **Don't** 把朱砂用于状态、把印泥朱用于装饰（双红分工）；禁第四种状态语义色
- **Don't** 表格/表单/按钮用衬线，正文/表格用书法（字体三分）；书法禁承载业务信息
- **Don't** 业务页使用氛围级动效（底纹/视差/行军线/书法底字呼吸——登录门厅专属）；禁 width/height/top/left 动画；motion.css 外零 `@keyframes`
- **Don't** 版面内投影堆叠；弹层阴影越级（sm/md/lg 各安其位）
- **Don't** 改 LoginView.vue / LoginView.spec.ts / login.html（零回归冻结）；motion.css 对 `fuy-login-*` 段只增不改
- **Don't** 私造色值/未批先取申请表色值；禁图标库新增依赖（icons-vue 既有 43 枚之外走宪法流程）

## 工程红线（全程序执行者必读）

1. **token 命名空间**：`--fuy-*` 每 app 各持一份，禁上收 packages/shared；portal/bigscreen 批次 5 各建各的。
2. **EP 映射单向通道**：`--fuy-* → --el-*` 仅 element-plus.css；`:root:root` 双写或 `.fuy-*` 挂类；fuy 层永不引用 el 值。通道扩充注记（2026-10-10 主控裁决留痕）：焦点环语汇类属性级定向收口：限 `:focus-visible` 单属性、限 element-plus.css、须留痕裁决（根因=EP 元素自身声明短路 ：root 继承，变量映射通道无效）。
3. **token 三层纪律**：primitive（十值正名/palette/字号/间距）→ semantic（语义别名）→ 原生面收编（选区/插入符/滚动条/焦点环/tabular）；模块增量零新色值。
4. **动效红线**：motion.css 唯一 keyframes 来源；只动 transform/opacity（stroke-dashoffset 例外不扩围）；侧栏折叠瞬切；reduced-motion 时长+延迟双归零兜底（唯一豁免 `.fuy-loading-essential`）；氛围动效限登录门厅。
5. **样式技术栈与加载顺序**：纯 CSS 自定义属性 + scoped CSS；`index.css` 固定 fonts → tokens → element-plus → motion；字体零外链（子集 woff2 本地化）。
6. **登录零回归**：LoginView.vue/LoginView.spec.ts/login.html 冻结；基础册每次提交以 LoginView.spec 全绿为前置门禁。
7. **样式层门禁**：tokens.css 外零新 hex 字面量、motion.css 外零 `@keyframes`、element-plus.css 外零 `--el-*` 赋值；`pnpm lint --max-warnings=0`/`format:check`/`type-check`（双 project）/`test`/`build` 全绿方可合入。

## 换血实施记录（2026-10-10 · 十值映射与旧值对照）

以登录页 v3 十值（LoginView.vue:556-565）为唯一新值来源，槽位名不变仅值换血：

| 槽位 | 旧值（纸质病案） | 新值（暖纸卷宗） | 来源 |
| --- | --- | --- | --- |
| surface-page | #f5f3ec | #f4ecdb | 十值 paper |
| surface-card | #fdfcf8 | #f8f4e9 | paper 0.6 于白 |
| surface-input | #ffffff | #ffffff（保留） | —— |
| text-emphasis / brand / gray-800 | #1e2a44 | #2a2318 | 十值 ink |
| text-secondary / gray-600 / info | #565d6e | #5a4c38 | 十值 ink-soft |
| 占位符/弱墨 | #676d7b | #8a7a5e | 十值 ink-faint |
| 壳层 bg | #1a2540 | #2a2318 | 十值 ink |
| 壳层 text / hover | rgba(253,252,248,.88) / rgba(253,252,248,.08) | rgba(244,236,219,.92) / rgba(244,236,219,.10) | paper alpha |
| 壳层缝 / brand-900 | #131c33 | #1d1811（申请 A-1） | ink 加深 |
| 石规 | #c6c0ae | #d8c9a8 | 十值 paper-edge |
| 发丝 / 图表网格 | #dcd7c9 | rgba(90,76,56,0.18) | ink-soft alpha |
| 悬停暖底 gray-50 | #efece1 | #f1e8d7 | paper-deep 半洗 |
| 选中金洗 brand-100 | #e9e7de | #ebe2cd | gold 0.18 洗 |
| 印泥朱 red-700/600 | #b42318 | #b42318（保留） | 状态朱不变 |
| 印泥朱按压 red-800 | #8f1a12 | #82251f | 十值 cinnabar-dk |
| 琥珀 amber-700 | #b45309 | #9a4708（申请 A-2，未批保旧值） | 警示暖化加深 |
| 分级橙 orange-700 | #c2410c | #b03a0c（申请 A-3，未批保旧值） | 分级橙加深 |
| 分诊蓝 blue-700 | #1d4ed8 | #1d4ed8（保留） | —— |
| 确认绿 green-800 | #1f7a33 | #1f7a33（保留） | —— |
| 焦点环 | rgba(30,42,68,0.35) | rgba(42,35,24,0.35) | ink alpha |
| 朱洗选区 | #f0d9d3 | rgba(165,53,44,0.14) | cinnabar alpha |
| 阴影 md（唯一） | 0 12px 28px rgba(19,28,51,.16) | 0 10px 24px -6px rgba(42,35,24,.18)（并新增 sm/lg 三级） | 墨基三级 |
| 弹层遮罩 | rgba(30,42,68,0.45) | rgba(42,35,24,0.5) | 墨纱暖化 |
| EP primary 阶 | #1e2a44 系冷灰五档 | #2a2318 / #5a4c38 / #8a7a5e / #d8c9a8 / 暖洗两档 / #1d1811 | 十值结算 |
| ::selection | #f0d9d3 | rgba(165,53,44,0.14) | 朱洗暖化 |
| 圆角档 | 2/4/8/12/999 | 沿用 + 新增 seal 6 / frame 10 / card 14 | 登录圆角档补种 |
| 字体 | 黑体系统栈单栈 | 三分（serif/brush 新增双栈 + @font-face 全局上收） | 登录 v3 字体 |

状态族申请表（A-1~A-5）与全档对比度核验见 `.impeccable/contract-warm-paper-world.md` §③，未批前相关槽位维持旧值施工。旧「纸质病案」世界的蓝黑墨值（#1e2a44/#131c33/#1a2540 等）自本记录起降为历史证据，仅此处留痕。
