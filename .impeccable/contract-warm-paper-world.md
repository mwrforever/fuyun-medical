# 全站暖纸世界契约（contract-warm-paper-world · 2026-10-10）

> 谋段产出（web 宪法 A.8「谋·谋局定策」，`@impeccable` + `@ui-ux-pro-max` 已实载）。
> 效力：「暖纸卷宗」全站化程序（36 视图重构 + 基础册换血 + 壳层过渡 + 403 重排 + api 补缺）全部施工节点的唯一视觉方向依据；与 DESIGN.md 互为表里，冲突以本契约为准（本程序范围内）。
> 主控已裁决基座（不得推翻，本文只细化落地）：换血路线否定双轨制；双红分工（装饰朱/状态朱）；字体三分；登录零回归；既有工程红线全部继续有效；氛围级动效限登录门厅专属。
> 事实基准：`docs/progress/2026-10-10-全站暖纸化重设计-调研底册.md`；构图权威：`.impeccable/mocks/paper-chart/login.html`（v3.3）与 `LoginView.vue`（1703 行，只读禁改）。

---

## ① 世界裁定与叙事

**North Star：「暖纸卷宗 Warm Paper Archive——全院运转在同一部摊开的病案卷宗里」。**

登录页是这部卷宗的门厅：医护在暖纸封面上以朱砂印鉴签认身份（「启封当日病案」），推开内页进入工作世界。工作世界不是另一个房间，而是同一部卷宗的内页——工作面是微黄的陈年纸底，卡片是一页页亮纸，弹层是浮起的一页纸，规线是纸上的墨笔格线，筛选与表格是文书表格，确认是钤印，批注必带「谁·何时」。所有 36 个业务视图从「蓝色 admin 的卡片罗列脸」整体重排为「卷宗文书脸」，但信息架构、数据契约、权限口径、表单逻辑零变动——换的是纸与墨，不是业务。品牌感活在材质的精确里：暖纸十值色域、棕墨承文、金线装订、朱砂印鉴；绝不抢操作，一屏可见行数与扫读性优先于装饰表达（Operate 场景纪律：状态色永不单独承义，动效传达意义而非炫技，渲染性能前置取舍）。

**世界命名**：暖纸卷宗（Warm Paper Archive）。登录门厅=封面与内页；业务页=卷宗内页的文书工作面；状态=印泥；品牌与叙事=朱砂印鉴。

**模式裁定**：workstation 全站 Operate 模式——扫读性、一致性、真实使用场景（高密度、长时间、时间压力） outrank 表达；品牌活在精确细节（规线/疏排/印鉴/密排），不活在大面积氛围上。**氛围级动效（六层底纹/鼠标视差/行军线/书法底字/墨晕呼吸/祥云/流水/光带）为登录门厅专属，业务页一律禁止**（ mandate 第 8 条；性能前置取舍：大面积 blur 与全屏常驻动画在定向时即排除于业务页）。

---

## ② token 全局映射表（换血路线 · token 名不变仅值换血）

### ②.1 换血总则

1. 路线=「换血」：`tokens.css` 既有 `--fuy-*` 槽位的**值**替换为登录十值及其 alpha 派生，**槽位名一律不变**；36 个存量视图凭同名 token 自动承继（tokens.css:12 先例）。否定双轨制：不新增并行的第二套 surface/text/brand 族。
2. **零新色值约束**：本表全部新值只能出自登录十值（`#f4ecdb / #eadfc8 / #d8c9a8 / #2a2318 / #5a4c38 / #8a7a5e / #a5352c / #82251f / #c7544a / #b08d4f`，来源 LoginView.vue:556-565）及其 alpha 派生；alpha 派生一律写 rgba() 形式或给出结算公式与结算值（实现以公式为准）。既有状态族如需暖化微调，**逐色进 §④ 申请表待主控审批，不得自行引入**。
3. 既有语义别名族（triage l1-l4 / 床位五态 / 设备五态 / 输液三档 / nursing 族 / 体温单 `--fuy-chart-*`）全部为 semantic 别名指向 palette，**零改动自动承继**新值；个别上游 palette 调整走申请表。

### ②.2 surface 三级

| 槽位 | 旧值 | 新值 | 依据 |
| --- | --- | --- | --- |
| `--fuy-surface-page` | `#f5f3ec` | `#f4ecdb` | 十值 `--paper` 直取（工作面=陈年纸底） |
| `--fuy-surface-card` | `#fdfcf8` | `#f8f4e9` | 结算值：`rgba(244,236,219,0.6)` 于 `#ffffff`（卷宗内页纸面，LoginView.vue:1120 同构 .6 档）；亮度差层级法不变：卡面亮于工作面 |
| `--fuy-surface-input` | `#ffffff` | `#ffffff`（保留） | 表单域最亮纸面；登录页字段底为 paper 0.75 alpha（LoginView.vue:1380），业务页输入保持最亮纸白，与卡面拉开一档 |

### ②.3 shell 四件（书脊=卷宗封皮深棕墨）

| 槽位 | 旧值 | 新值 | 依据 |
| --- | --- | --- | --- |
| `--fuy-shell-bg` | `#1a2540` | `#2a2318` | 十值 `--ink` 直取（浓墨封皮）；书脊语义从「蓝黑病历夹」改叙「棕墨卷宗封皮」 |
| `--fuy-shell-hover` | `rgba(253,252,248,0.08)` | `rgba(244,236,219,0.10)` | paper 十值 alpha（纸纱悬停） |
| `--fuy-shell-hairline` | `#131c33` | `#1d1811` | **进申请表 A-1**（墨加深档=ink 向黑加深约 14%，十值内无更深的墨；备选=ink-soft 受光缝） |
| `--fuy-shell-text` | `rgba(253,252,248,0.88)` | `rgba(244,236,219,0.92)` | paper 十值 alpha；对 `#2a2318` 对比度 ≈11:1 |

### ②.4 text 三级与 brand

| 槽位 | 旧值 | 新值 | 依据 |
| --- | --- | --- | --- |
| `--fuy-palette-brand-700` / `--fuy-palette-gray-800` / `--fuy-color-text-emphasis` / `--fuy-color-brand` | `#1e2a44` | `#2a2318` | 十值 `--ink`；「墨即操作」不变，浓墨换棕墨；对卡面 14.1:1 / 工作面 13.2:1 |
| `--fuy-palette-brand-900` | `#131c33` | `#1d1811` | **进申请表 A-1**（墨加深档：按压态/书脊缝共用一值） |
| `--fuy-palette-gray-600` / `--fuy-color-text-secondary` / `--fuy-color-info-text` | `#565d6e` | `#5a4c38` | 十值 `--ink-soft`（灰墨）；7.1:1（工作面）/ 7.6:1（卡面） |
| `--fuy-color-focus-ring` | `rgba(30,42,68,0.35)` | `rgba(42,35,24,0.35)` | ink alpha（墨焦点环换血） |
| `--fuy-palette-brand-200` | `#2b3a5e` | `#5a4c38` | 墨实底钮悬停亮一档=灰墨（HomeView home-retry:hover 消费处方向不变） |
| `--fuy-palette-gray-50` / `--fuy-palette-brand-50` | `#efece1` | `#f1e8d7` | 结算值：`rgba(234,223,200,0.5)` 于卡面（悬停暖底换血） |
| `--fuy-palette-brand-100` | `#e9e7de` | `#ebe2cd` | 结算值：`rgba(176,141,79,0.18)` 于卡面（选中态金洗纸——金线装订同源，避「朱=危急」误读与旧墨洗同理） |

### ②.5 规线色（石规/发丝/缝）

| 槽位 | 旧值 | 新值 | 依据 |
| --- | --- | --- | --- |
| `--fuy-color-rule-stone` | `#c6c0ae` | `#d8c9a8` | 十值 `--paper-edge` 直取（石规=纸缘色；滚动条/虚缝/面板规线同源单点） |
| `--fuy-border-hairline` | `1px solid #dcd7c9` | `1px solid rgba(90,76,56,0.18)` | ink-soft alpha（卡内发丝=墨笔细线，与登录 `--login-line` 同族 .18 档） |
| `--fuy-chart-grid-color` | `#dcd7c9` | `rgba(90,76,56,0.18)` | 与发丝同值（SVG stroke 场景别名，体温单网格） |
| `--fuy-border-panel` | `1px solid var(--fuy-color-rule-stone)` | 不变（值随石规自动承继） | 面板级分界 |

### ②.6 阴影（唯一阴影→墨基三级）

| 槽位 | 旧值 | 新值 | 依据 |
| --- | --- | --- | --- |
| `--fuy-shadow-md` | `0 12px 28px rgba(19,28,51,0.16)` | `0 10px 24px -6px rgba(42,35,24,0.18)` | 十值墨 alpha（LoginView.vue:570 原文摘录）；弹层消费面名值不变仅换血 |
| `--fuy-shadow-lg`（新增） | 无 | `0 24px 60px -12px rgba(42,35,24,0.28), 0 4px 12px rgba(42,35,24,0.1)` | LoginView.vue:569 原文；消费面：dialog / 登录 stage 同级的浮起大面 |
| `--fuy-shadow-sm`（新增） | 无 | `0 4px 10px rgba(42,35,24,0.1)` | LoginView.vue:571 原文；消费面：签牌/小面悬浮态 |
| `--el-box-shadow` / `-light` / `-lighter`（element-plus.css） | 蓝墨三档 | 同源换血为上三值（lighter 用 sm 值） | 弹层阴影单源 |

**阴影纪律修订**（随三级落位）：「唯一阴影给弹层」修订为「**阴影三级给浮起层**」——lg=大浮面（dialog/stage 级）、md=标准弹层（popper/drawer）、sm=小面悬浮（签牌/入口卡 hover）。版面内（卡/卡中卡）仍禁投影，层级靠纸面亮度与规线；三级阴影**禁止消费于版面内静态堆叠**。

### ②.7 浏览器原生面换血

| 槽位 | 旧值 | 新值 |
| --- | --- | --- |
| `::selection` background | `#f0d9d3` | `rgba(165,53,44,0.14)`（朱洗暖化；字色仍浓墨） |
| `scrollbar-color` thumb | `var(--fuy-color-rule-stone)` | 不变（随石规自动承继 #d8c9a8） |
| `caret-color` | `var(--fuy-color-brand)` | 不变（自动承继 #2a2318，恰与登录 `--login-ink` 同值） |
| `:focus-visible` outline | `var(--fuy-color-brand)` | 不变（自动承继棕墨） |

### ②.8 新增槽位清单

| 槽位/工具类 | 值 | 用途 |
| --- | --- | --- |
| `--fuy-font-family-serif` | `'Noto Serif SC', 'Songti SC', 'STSong', 'SimSun', Georgia, serif` | 衬线栈（LoginView.vue:575 原文） |
| `--fuy-font-family-brush` | `'Ma Shan Zheng', 'KaiTi', 'STKaiti', serif` | 书法栈（LoginView.vue:576 原文） |
| `--fuy-font-size-title` | `22px` | 门牌页首衬线标题专用档（登录 sheet-title 1237-1241 同档） |
| `--fuy-radius-card` | `14px` | 卷宗卡/dialog（登录圆角档 14） |
| `--fuy-radius-frame` | `10px` | 卷框/页框级（登录 page-frame rx 10） |
| `--fuy-radius-seal` | `6px` | 印鉴块/钤印浮层（登录 brand-mark/stamp-toast 档 6） |
| `--fuy-ease-out` | `cubic-bezier(0.22, 1, 0.36, 1)` | 门面浮入缓动（LoginView.vue:572 原文，落 motion.css） |
| `--fuy-ease-ink` | `cubic-bezier(0.65, 0.05, 0.36, 1)` | 钤印/下划线缓动（LoginView.vue:573 原文，落 motion.css） |
| `.fuy-stitch-ink` | `repeating-linear-gradient(to bottom, var(--fuy-ink-faint) 0 8px, transparent 8px 16px)` 2px 宽 | 弱墨虚缝装订线（封面装订，LoginView.vue:925-938 同构） |
| `.fuy-stitch-gold` | `repeating-linear-gradient(to bottom, var(--fuy-gold) 0 10px, transparent 10px 20px)` 2px 宽 | 金线虚缝装订线（内页装订，LoginView.vue:1124-1133 同构） |
| `.fuy-sign-divider` | 1px 渐变线 + ::after 书法「签」盖线 | 「签」字分隔（LoginView.vue:1288-1313 同构，双云贴片可选省略） |

**新增 token 引用约定**：`--fuy-ink-faint`/`--fuy-gold` 等十值直引别名在换血段一并落 tokens.css（`--fuy-paper: #f4ecdb`、`--fuy-paper-deep: #eadfc8`、`--fuy-paper-edge: #d8c9a8`、`--fuy-ink: #2a2318`、`--fuy-ink-soft: #5a4c38`、`--fuy-ink-faint: #8a7a5e`、`--fuy-cinnabar: #a5352c`、`--fuy-cinnabar-dk: #82251f`、`--fuy-cinnabar-lt: #c7544a`、`--fuy-gold: #b08d4f`），作为 primitive 层十值正名（与 `--login-*` 局部色域同名异域，互不干扰）。

---

## ③ 双红分工条款 + 状态色唯一映射表

### ③.1 双红分工条款（修宪级 Named Rule，DESIGN.md 同步）

1. **装饰朱＝朱砂**（`#a5352c / #82251f / #c7544a` 三档）：承品牌印鉴（「富」印块、竖排朱印）、朱砂走线（签牌下划、tabs 签线、tagline 左线、行军线）、书法底字与「签」分隔字、聚焦下划展开线等**装饰叙事**。永不承状态语义：不得作状态文字色、状态徽标底、错误/告警/驳回通道。
2. **状态朱＝印泥朱**（`#b42318` 族，含按压档申请值 `#82251f` 承接旧 `#8f1a12` 语义）：只承危急/停用/作废/校验错误/驳回等**状态语义**（状态文字、实底徽标、朱笔划销线、`--el-color-danger` 通道）。永不作装饰。
3. **过渡条款（登录特例）**：登录页 v3 朱砂兼承校验错误态（LoginView.vue:581 页内 `--el-color-danger` 下发）为门厅既有事实，**零回归保护不动**；全站业务页校验/危险通道一律印泥朱。
4. **聚焦反馈归类**：聚焦=操作域=墨（`--el-input-focus-border-color` 走 primary 墨）；朱砂下划展开线是聚焦的**装饰第二通道**（非状态语义），两类朱永不混用于同一语义位。

### ③.2 状态色唯一映射表（新纸面对比度核验）

新纸面基准：工作面 `#f4ecdb`（L≈0.844）/ 卡面 `#f8f4e9`（L≈0.905）。双通道铁律不变：色永不单独承义，必配文字（或形状/字重）第二通道。

| 语义 | token 通道 | 取值 | 工作面对比 | 卡面对比 | 白字实底 | 裁定 |
| --- | --- | --- | --- | --- | --- | --- |
| 危急/停用/作废/校验错误 | `--fuy-color-danger-text` / triage-l1 / nursing-critical / infusion-red / bed-maintenance / device-abnormal | `#b42318`（保留） | 5.6:1 | 6.4:1 | 6.6:1 | 保留原值，全档达标 |
| 确认/在档 | `--fuy-color-success-text` / bed-free / device-online | `#1f7a33`（保留） | 4.6:1 | 5.3:1 | 5.4:1 | 保留；工作面 4.6 为边缘达标，备选申请加深见 A-4 |
| 警示/冻结/待办 | `--fuy-color-warning-text` / triage-l3 / nursing-l2 / infusion-yellow | `#b45309`（申请调整） | 4.3:1（不达 AA） | 4.8:1 | 5.0:1 | **申请表 A-2**：`#9a4708` |
| 分级橙（Ⅱ级-急/病重/预占/已流转） | triage-l2 / nursing-serious / bed-reserved / state-passed / infusion-orange | `#c2410c`（申请调整） | 4.4:1（不达 AA） | 4.7:1 | 5.2:1 | **申请表 A-3**：`#b03a0c` |
| 普通/常规分级·体温系 | triage-l4 / nursing-surgery / `--fuy-chart-temp-color` | `#1d4ed8`（保留） | 5.7:1 | 6.1:1 | 6.7:1 | 保留原值 |
| 中性/弱化 | `--fuy-color-info-text` / bed-disinfecting / device-disabled / nursing-exit | `#5a4c38`（随灰墨换血） | 7.1:1 | 7.6:1 | — | 随换血自动承继 |

> 实底白字场景全部达标（最低 5.0:1）；不达标仅发生在「状态文字直接坐在新工作面上」的通道（warning/orange），故申请只收窄到 A-2/A-3 两行。

### ③.3 状态色申请表（汇总待主控审批，未批前维持旧值施工）

| 编号 | 槽位 | 现值 | 申请值 | 用途 | 论证 | 替代方案（若驳回申请值） |
| --- | --- | --- | --- | --- | --- | --- |
| A-1 | `--fuy-palette-brand-900` / `--fuy-shell-hairline` | `#131c33` | `#1d1811` | 按压态/书脊外缘缝 | 十值内无更深墨；ink `#2a2318` 向黑加深 14% 的派生档，缝与按压共用一值防多档散布 | 缝改用 ink-soft（受光缝语义，缝变亮线）；按压态改 ink-soft |
| A-2 | `--fuy-palette-amber-700`（→ warning-text/triage-l3/nursing-l2/infusion-yellow 全族自动） | `#b45309` | `#9a4708` | 警示文字承新暖纸面 | 旧值对新工作面仅 4.27:1 不达 AA；加深后 5.4:1（卡面 5.8、实底白字 6.4）；琥珀色相不变仅降亮度 | 保留旧值+警示文字强制 600 字重与图标第二通道（对比缺口 ~0.2，不推荐）；或警示文字通道改印泥朱（语义冲突，禁） |
| A-3 | `--fuy-palette-orange-700`（→ triage-l2/nursing-serious/bed-reserved/state-passed/infusion-orange 全族自动） | `#c2410c` | `#b03a0c` | 分级橙文字/描边承新暖纸面 | 旧值对新工作面 4.41:1 边缘不达；加深后 5.2:1（卡面 5.5、实底白字 6.1）；实底白字场景同步增益 | 仅「文字通道」改 `#b03a0c`、实底族保旧值（拆两档，维护成本高） |
| A-4（备选） | `--fuy-palette-green-800` | `#1f7a33` | 不调整（默认） | 确认绿 | 工作面 4.59:1 达标但余量薄；如主控求稳可批 `#1c6e2e`（工作面 ~5.1:1） | 保持不动即可达标，仅在主控明示下执行 |
| A-5 | EP 功能色 light-9/light-8 洗底族（element-plus.css `:root:root`） | EP 默认冷调浅底 | danger `rgba(180,35,24,0.08)/0.14`、warning `rgba(154,71,8,0.10)/0.16`、success `rgba(31,122,51,0.10)/0.16`、info `rgba(90,76,56,0.08)/0.14`；danger dark-2=`#82251f` | el-tag light 底/message/notification 底/按钮 hover 底的暖化 | 既有「功能色保留 EP 默认」在新暖纸上冷调洗底冲突加剧；全部为既有状态色 alpha 派生，零新色相 | 不映射则 el-tag light 底仍为冷粉/冷灰（现状债延续，登记不收编） |

> 审批前施工纪律：A-1~A-5 未批期间，相关槽位一律**维持旧值**或暂用 §③.2「保留原值」列，禁止施工 agent 自行取申请值。

> **扩批注记（2026-10-10 主控裁决，验段+代码审查驱动）**：A-5 已扩批为 danger/warning/success/info **四族各七档**（base/light-3/5/7/8/9/dark-2；light-3/5/7=基色 alpha 同构口径、dark-2=color-mix 黑混公式+@supports 基色兜底）**+error 语义别名族七档**（danger 同构直引，封死 EP 冷粉回落）。全部取值为已批状态色 alpha/color-mix 派生，零新色相；裁决留痕见 CHANGELOG 同日条目与进度台账 §6。

---

## ④ 字体使用矩阵 + @font-face 全局上收方案

### ④.1 三分矩阵（精确使用面）

| 栈 | token | 允许使用面 | 禁止使用面 |
| --- | --- | --- | --- |
| 衬线 serif | `--fuy-font-family-serif` | 门牌页首标题（`.fuy-page-title`，22px/700，字距 0.06em）；卷宗卡标题（`.fuy-card-title`，16px/700）；dialog/drawer 标题（16px/700）；诚实空态主句（14px/600）；403 页主句与巨字水印区；弹层确认主句 | 表格任何单元格/表头；表单 label/输入值/按钮；el-tag/徽标/胶囊；批注行/页脚/时间戳；工具栏 |
| 黑体 base | `--fuy-font-family-base`（默认继承） | 一切操作与数据面：表格、表单、按钮、tag、徽标、工具栏、批注行「谁·何时」、页脚、事件流、下拉/弹层正文 | —— |
| 书法 brush | `--fuy-font-family-brush` | 钤印家族字：品牌「富」印块、竖排朱印、钤印确认浮层（「提交成功」等）、「签」分隔字、空态书法字、403 底字水印、装饰叙事字（aria-hidden 装饰层优先） | 正文、表格、表单、按钮、tag、批注行——**凡承载业务信息的文本一律禁书法** |

字号阶梯：沿用七档（12/13/14/16/18/20/24）不变；新增 `--fuy-font-size-title: 22px`（门牌衬线标题）。书法/装饰字（水印巨字、钤印浮层 24px、空态书法字 48px 级）为场景一次性值不进阶梯。**可读文本下限 12px**（ui-ux-pro-max 可访问性纪律）；10-11.5px 档仅允许出现在 aria-hidden 装饰层（竖排批注/水印，登录门厅语法），业务页信息文本一律 ≥12px。字距疏排语法随衬线面下沉：标题 0.06em、英文小注 0.24em（Georgia）、标签 0.14em 仅登录门厅保留，业务表单 label 疏排 0.02em。

### ④.2 @font-face 全局上收方案

1. **载体**：新增 `web/apps/workstation/src/styles/fonts.css`；`index.css` 载入顺序改为 `fonts → tokens → element-plus → motion`（工程红线第 6 条表述同步扩充「fonts 居首」，CHANGELOG 先记再改）。
2. **声明内容**：四条 `@font-face` 从 LoginView.vue:524-551 **原文摘录**（noto-serif-sc-400/600/700.subset.woff2 + ma-shan-zheng-400.subset.woff2，`font-display: swap`，路径改由 fonts.css 相对 `../assets/fonts/` 解析），文件全部 <1MB 入库（截图红线同源，现四件已在 `src/assets/fonts/`）。
3. **font-display：swap**（现口径不变）；FOUT 控制：子集体积小（24KB×3+44KB），swap 回退链 `Songti SC/STSong/SimSun` 与 `KaiTi/STKaiti` 系统族兜底；`index.html` 增 4 条 `<link rel="preload" as="font" type="font/woff2" crossorigin>`（同源、零外链红线不破）。
4. **与 LoginView scoped 既有声明的关系**：同名同描述符的 `@font-face` 浏览器按族+字重合并，后者（scoped 后加载）不产生二次下载与视觉差；**LoginView.vue 内声明一律不删不改**（登录零回归）。全局上收后 LoginView 实际命中的仍是同一批 woff2，渲染结果逐像素等价。

---

## ⑤ element-plus.css 收编扩编目录（逐组件）

> 通道约定：`:root:root` 双写只承载**变量映射**；一切结构/伪元素级定制走 `.fuy-*` 挂类前缀组合选择器（`.fuy-dense`/`.fuy-tree-popper` 先例，非裸改 `.el-*`）。下表「出处」= LoginView.vue 可复用写法行号。

| # | 组件面 | 目标脸 | 实现通道（命名给出） | 出处 |
| --- | --- | --- | --- | --- |
| 1 | form label | 13px 灰墨 500 + 0.02em 疏排 + 8px 底距 | `.fuy-form .el-form-item__label`（整表单挂 `class="fuy-form"`，免逐控件挂类） | 1349-1360 |
| 2 | 输入族围合 | 最亮纸白底 + 石规 inset 描边 + 聚焦墨环 + 朱砂下划自中心展开（0.45s ease-ink） | `:root:root` 映射 `--el-input-bg-color: #ffffff`、`--el-input-border-color: var(--fuy-color-rule-stone)`、`--el-input-focus-border-color: var(--fuy-color-brand)`；`.fuy-form .el-input__wrapper::after` 承下划线 | 1377-1418 |
| 3 | placeholder / 前后缀图标 | placeholder=弱墨 13px；图标弱墨、聚焦转墨 | `:root:root` `--el-text-color-placeholder: #8a7a5e`；`.fuy-form .el-input__prefix/.el-input__suffix` + `.is-focus` 联动 | 1427-1440 |
| 4 | error 显影 | 印泥朱 11.5px→12px + 圈叹图标 + 沉落显影（新键帧 `fuy-note-in`）；围合 inset 印泥朱描边 | `.fuy-form .el-form-item__error` / `.el-form-item.is-error .el-input__wrapper`；`:root:root` `--el-color-danger: var(--fuy-color-danger-text)` | 1392-1395、1459-1483（shake 键帧不外扩，业务页只用显影） |
| 5 | select / date-picker 弹层面 | 弹层=亮纸卡+唯一阴影+硬 snap；选中项=金洗底浓墨字；hover 暖底 | 全局映射自动承继（bg-overlay/fill/light 阶）+ popper 统一挂 `popper-class="fuy-snap-popper"`；选中底经 primary-light-9 换血（A-5 同批 `:root:root`） | 1461-1466（压制先例）、131-143（挂类先例） |
| 6 | table 族 | 表头=表头纸 #f2ecdc（fill-light 换血）600 + 0.04em 字距；行悬停=行悬停纸；斑马行=`rgba(234,223,200,0.35)`；边线=发丝；密排沿 `.fuy-dense` | `:root:root` fill/border 族换血即达；`.fuy-dense .el-table--striped .el-table__body tr` 承斑马；`.fuy-dense` 既有规则零改动 | 71-85（既有） |
| 7 | pagination | 右对齐 + 页码 tabular + 激活页=墨底纸字（primary 实底自动）+ 悬停暖底 | `.fuy-page .el-pagination { justify-content: flex-end; padding-top: var(--fuy-space-2) }` | ——（映射自动面） |
| 8 | tabs | 卷宗签语法：激活签=顶部 2px 朱砂签线 + 浓墨 600；非激活灰墨；禁朱以外色 | `.fuy-tabs`（挂 el-tabs）：`.fuy-tabs .el-tabs__active-bar { background: var(--fuy-cinnabar) }` + item 态 | 1046-1081（签牌 hover/下划同构） |
| 9 | dialog | 卡面底 + radius-card 14 + shadow-lg + 衬线标题 16px/700 + 头底发丝 | `:root:root` `--el-dialog-border-radius: var(--fuy-radius-card)`；`.fuy-dialog`（挂 el-dialog）标题与头部分隔 | 903（阴影档）、1237-1241（标题档） |
| 10 | drawer | 右推 520/640px 两档 + 卡面底 + 衬线标题 + 头部「谁·何时」批注槽 + 发丝分隔 | `.fuy-drawer`（挂 el-drawer） | —— |
| 11 | message / notification | 批注条脸：卡面底+发丝描边+sm 阴影+浓墨字；状态色只上图标与左缘 2px 色条 | A-5 light 阶映射自动承继 + `.fuy-message`（message `customClass` 通道；notification 同） | 1586-1604（金/朱左线提示条语法同构） |
| 12 | tag | 沿 `.fuy-tag-aa`/`.fuy-tag-strike` 家族；radius-sm；实底/描边/light 三形态纪律（实底=强状态、light=弱态） | 既有类零改动 + A-5 洗底换血 | 100-115（既有） |
| 13 | checkbox / radio / switch | 选中=墨（primary 自动）；边=石规；switch 开=墨；禁选红 | `:root:root` 映射自动；零额外规则 | —— |
| 14 | dropdown / popper | 沿 `.fuy-snap-popper` 硬 snap + 亮纸卡 + shadow-md；菜单项 34px 密排（`.fuy-tree-popper` 语法推广） | 既有两类零改动，新 popper 统一挂 `fuy-snap-popper` | 241-291（既有） |
| 15 | descriptions | label 列=表头纸底 + 灰墨 500 + 0.02em；值=浓墨；边=发丝 | `:root:root` `--el-descriptions-item-bordered-label-background: var(--el-fill-color-light)` | —— |
| 16 | el-empty | 见 ⑥（全局空态脸） | `.fuy-empty` + `.fuy-page/.fuy-dialog/.fuy-drawer .el-empty__image { display:none }` | —— |
| 17 | skeleton | 见 ⑧ 加载骨架（纸感静态块，非 EP skeleton 组件时） | `.fuy-sk` 族（自绘静态块，HomeView 1039-1092 收编） | —— |
| 18 | 页级组件族 | 门牌页首/卷宗卡/页脚批注全局类（DOM 见 ⑧） | `.fuy-page-head` / `.fuy-card` / `.fuy-page-foot` | 1224-1256、1586-1604 |

---

## ⑥ 诚实空态收编方案（el-empty 61 处 → 全局空态脸）

1. **禁纸箱插画**：默认 `image` 槽在 `.fuy-page`/`.fuy-dialog`/`.fuy-drawer` 子树内一律压制（`display:none`，挂类前缀组合选择器，非裸改 `.el-*`）；存量 30 视图 61 处随页批次逐页改挂 `.fuy-empty`（TASK.md W-103 随本程序清账）。
2. **空态脸 DOM 骨架**（element-plus.css `.fuy-empty` 段 + 页内替换 `<el-empty>` 为全局脸或给 el-empty 挂类二选一，推荐后者少改动）：

```html
<div class="fuy-empty" role="status">
  <span class="fuy-empty-mark" aria-hidden="true">空</span>
  <p class="fuy-empty-title">暂无在院患者</p>
  <p class="fuy-empty-hint">当前病区暂无在院记录，可切换病区或稍后刷新。</p>
</div>
```

3. **规格**：容器=石规虚缝弱形态（1px dashed `--fuy-color-rule-stone` + radius-lg + 40px/16px 内边距居中，HomeView 975-983 先例）；书法字「空」48px 弱墨 @0.5（装饰层 aria-hidden，承钤印意象）；主句 14px/600 衬线浓墨；说明 13px 灰墨。
4. **「暂无」文案语法**：主句=`暂无〈业务客体〉`或`尚无〈业务客体〉`；说明句=给下一步（可切换/可刷新/可新建），禁「暂无数据」「No Data」裸词、禁 lorem、禁伪数据；错误态不得伪装成空态（错误走「说问题+给恢复」脸，HomeView 986-1036 先例）。

---

## ⑦ motion 扩编目录

1. **既有资产零改动**：19 枚既有 keyframes、`.fuy-stagger/.fuy-tree-stagger/.fuy-flip/.fuy-tag-flip/.fuy-content-fade/.fuy-snap-popper`、两段 reduced-motion 兜底、`fuy-login-*` 15 枚（只增不改，消费方注记扩围=无）全部冻结；`TODO(fuy-pulse)` 预留位维持。
2. **新增缓动 token**：`--fuy-ease-out` / `--fuy-ease-ink`（②.8 表，落 motion.css `:root`，与既有四族并存；既有三档时长 120/200/320 不变）。
3. **新增业务共享 keyframes**（全落 motion.css，名称不带 login 语义；属性纪律=仅 transform/opacity）：

| 名称 | 时长/缓动 | 用途 | 属性 |
| --- | --- | --- | --- |
| `fuy-stamp-in` | 0.5s `--fuy-ease-ink` | 钤印确认反馈（scale 1.6 rotate(-14deg) → 0.96 rotate(2deg) → 1 rotate(-8deg)，落章三拍，LoginView.vue:255-268 语义同构换名） | transform+opacity |
| `fuy-note-in` | 200ms `--fuy-ease-enter` | 表单错误/批注显影（translateY(-4px)→0） | transform+opacity |
| `fuy-halo-breathe` | 2s ease-in-out 无限 | 实时指示/在岗呼吸点光环（伪元素 scale 1→1.9 + 明灭，LoginView.vue:388-398 语义同构换名） | transform+opacity |

4. **路由切换过渡规范**：MainLayout 的裸 `<RouterView />` 补：

```html
<RouterView v-slot="{ Component }">
  <Transition name="fuy-page" mode="out-in">
    <component :is="Component" />
  </Transition>
</RouterView>
```

- 过渡类（motion.css 新段）：`.fuy-page-leave-active`（120ms `--fuy-ease-exit`，opacity 单属性直退）；`.fuy-page-enter-active`（240ms `--fuy-ease-enter`，opacity+translateY(10px)→0）；enter-from=`opacity:0; transform:translateY(10px)`；leave-to=`opacity:0`。out-in 语义=旧页先退、新页后进，与页内 `.fuy-stagger` 衔接（stagger 自带 delay 不叠加路由延迟；页面根节点不挂 `.fuy-stagger`，stagger 挂在页内区块，防双重进场节奏）。
- 氛围边界重申：路由过渡只此一处场景级动效，禁再加全屏底纹/视差。

5. **EP 内建过渡压制推广面**：既有压制三处（`.fuy-tree-popper` 压 fade-in-linear/zoom-in-left、`.fuy-snap-popper` 压 zoom-in-top、`.fuy-menu` 压 collapse-transition）维持；新增压制仅一处口径——一切显式 `popper-class`（select/date-picker/dropdown 新增消费）统一挂 `.fuy-snap-popper`；dialog/drawer 保留 EP 内建 transform 过渡（合规且是「翻纸/推入」语义，不压制）。
6. **reduced-motion 兜底**：既有两段全局兜底（时长 0.01ms + delay 归零，唯一豁免 `.fuy-loading-essential`）自动覆盖全部新增 keyframes 与过渡类，**零新增兜底块**；`fuy-halo-breathe` 无限循环在 reduce 下被压停（可接受：呼吸点本体恒亮，光环消失不承载状态语义）。

---

## ⑧ 页级标准组件语法（DOM 骨架与 class 命名）

### ⑧.1 门牌页首（`.fuy-page-head`）

```html
<header class="fuy-page-head">
  <div class="fuy-page-head-main">
    <h1 class="fuy-page-title">门诊医生站</h1>
    <span class="fuy-page-en" aria-hidden="true">OUTPATIENT DESK</span>
  </div>
  <div class="fuy-page-status">
    <span class="fuy-status-pill fuy-status-pill--amber">
      <i class="fuy-live-dot" aria-hidden="true"></i>接诊中
    </span>
  </div>
  <p class="fuy-page-note">签认人 张三 · 2026-10-10 周五</p>
</header>
```

规格：标题=衬线 22px/700/0.06em 浓墨（`--fuy-font-size-title`）；英文小注=Georgia 11px/0.24em 弱墨（装饰层 aria-hidden，可省略）；批注行=「谁·何时」12px 灰墨，空值以 — 占位零伪数据（LoginView.vue:396-399、HomeView 370-372 同构）；收底=2px 墨规 `border-bottom: 2px solid var(--fuy-color-text-emphasis)`；状态胶囊右挂 baseline 对齐。

### ⑧.2 卷宗卡（`.fuy-card`）

```html
<section class="fuy-card">
  <header class="fuy-card-head">
    <h2 class="fuy-card-title">今日待配药</h2>
    <div class="fuy-card-extra"><!-- 计数/工具钮 --></div>
  </header>
  <div class="fuy-card-body"><!-- 表格/表单/列表 --></div>
</section>
```

规格：纸面=卡面亮纸白 + 发丝描边 + `radius-card 14`（版面内零投影）；卡头=衬线 16px/700 + 底部发丝；可选 `.fuy-card--stitch`（左缘金虚缝装订 `.fuy-stitch-gold`，叙事卡/摘要卡专用，表格卡禁用）；卡内分区用 `.fuy-sign-divider` 或发丝，禁石规下沉卡内（规线不越级不变）。

### ⑧.3 工具栏与筛选区

沿用 `.fuy-toolbar`（min-height 48px CLS 锁不变）；筛选区新增 `.fuy-filter`：`label 12px 灰墨 0.02em 疏排 + 控件 40px 高`，flex wrap gap 12px；操作钮右挂（margin-left auto）；检索钮=墨实底、重置=透明底暖底悬停。

### ⑧.4 密排数据表

`.fuy-dense` 唯一（禁 size="small" 双轨不变）+ ⑤#6 扩展（斑马/表头字距）；数字列一律 `.fuy-num`；操作列 `class-name="fuy-ops-8"`；状态列走 ⑧.6 徽标家族。

### ⑧.5 抽屉与弹层（钤印确认反馈）

```html
<el-dialog class="fuy-dialog" ...>
  <!-- 头部：衬线标题 + 右上关闭 -->
  <!-- 提交成功时内容区盖钤印浮层 -->
  <div class="fuy-stamp-toast is-show" aria-live="polite">已提交</div>
</el-dialog>
```

规格：`.fuy-stamp-toast`（element-plus.css 全局段，LoginView.vue:1607-1630 同构换语境）——书法 24px 纸白字、朱砂实底、`radius-seal 6`、shadow-lg、`fuy-stamp-in` 落章；业务语境文案=动词结果（已提交/已发药/已审核），≤1200ms 后随路由或弹层关闭收场；**危险/失败不得用钤印**（失败=message 批注条，钤印只承成功确认）。弹层遮罩=墨纱换血值；drawer 头部含「谁·何时」批注槽。

### ⑧.6 状态徽标

家族沿用（分诊/护理/床位/设备/输液各族既有类零改动）；新增通用胶囊 `.fuy-status-pill`（LoginView.vue:1257-1268 同构换血语境）：`12px 600 + 3px 10px 内距 + radius-full + 状态色 8%~12% 洗底 + 同色描边 + 同色文字`，色值只取 §③.2 语义通道（amber/success/danger/info 四色变体），配 `.fuy-live-dot` 呼吸点（仅实时/进行语义挂 `fuy-halo-breathe`）。双通道铁律：胶囊必带文字。

### ⑧.7 加载骨架（纸感）

`.fuy-sk` 族（HomeView 1039-1092 收编全局）：静态墨洗纸块（`--fuy-palette-brand-100` 金洗纸底），零 shimmer 常驻动画；占位高度 min-height 锁 CLS；骨架→内容 `.fuy-content-fade` 200ms。

### ⑧.8 页脚批注（`.fuy-page-foot`）

`12px 灰墨 + 上缘发丝`：内容=数据时点/刷新语义（HomeView 1095-1100 先例）；禁放操作钮。

---

## ⑨ 渲染性能宪章

1. **动效属性纪律**：只动 transform/opacity（全站铁律不变）；stroke-dashoffset 例外**不扩围**（仅登录行军线既有一处）；box-shadow/filter 永不参与逐帧动画（阴影过渡仅限签牌/按钮 ≤200px² 小面，卡片悬浮优先规线加重+纸面亮度表达）。
2. **blur/阴影边界**：业务页禁 backdrop-filter 与常驻 blur 滤镜；blur 仅允许登录门厅静态底纹（先例 LoginView.vue:662）；阴影消费面=⑧.5 弹层与 sm 小面悬浮。
3. **will-change 限额**：同视口 ≤6 层（登录 4 层先例）；业务页默认 0 声明，仅 STOMP 高频更新面（输液看板床卡/事件流容器）允许预声明 `will-change: transform` 且随卸载移除。
4. **长列表策略**：分页基线=usePagedList（≥50 行/页常规表）；单屏渲染节点阈值——表格 >200 行或卡墙 >120 卡（床位图/执行工作台/输液看板）必须虚拟化或改分组分页，禁一次全量渲染；图片（患者照/设备图）懒加载 + 按需取尺寸。
5. **STOMP 实时面更新策略**：推送帧合并到 rAF 或 ≥500ms 批处理再落响应式状态，禁逐帧 setState；行级更新走 `.fuy-flip`/`.fuy-tag-flip`（transform 反演），禁整表重渲染；页面隐藏（visibilityState）暂停轮询与订阅消费（B.3 既有纪律）。
6. **字体加载策略**：4 子集本地 woff2（合计 ~116KB）+ swap + preload；书法栈仅装饰层消费（aria-hidden），不阻塞任何信息文本首绘。
7. **EP 过渡压制**：显式 popper 一律 `.fuy-snap-popper`；表格/表单/标签零内建动画依赖；CLS 锁：`.fuy-toolbar` 48px、骨架 min-height、图表容器定高（HomeView 先例）。

---

## ⑩ 登录零回归审计（grep 实证）

**实证命令**：`grep -n -- "--fuy-\|--el-\|fuy-" web/apps/workstation/src/views/login/LoginView.vue`。结论：LoginView 对全局 token 的消费面=**3 处**，其余视觉全部 `--login-*` 局部色域自持（556-576 行）。

| # | 消费点 | 位置 | 换血后判定 |
| --- | --- | --- | --- |
| 1 | `var(--fuy-motion-base)` + `var(--fuy-ease-enter)` | 1474（错误显影动画） | **零漂移**：两 token 值冻结不动（本次只新增 ease-out/ease-ink） |
| 2 | `.fuy-loading-essential` 豁免类 | 452（提交转轮） | **零漂移**：豁免行为不变 |
| 3 | 15 枚 `fuy-login-*` keyframes 消费 | 670/678/686/700/709/723/730/737/748/804/812/825/858/908/955/991/1145/1285/1394/1582/1641 | **零漂移**：motion.css 对该段只增不改（铁律） |

页内下发的 EP 变量（581-582 `--el-color-danger`/`--el-font-family`）为**设置非消费**，页内 scoped :deep 已全覆盖 EP 视觉面（wrapper 1377-1418、label 1349-1374、error 1459-1483、autofill 1455-1458、按钮 focus 1551-1554），全局 `:root:root` 换血对其零作用面。

**残余微漂移清单**（全局原生面换血波及，逐项给止血方案）：

| 面 | 漂移 | 判定 | 止血方案（禁改 LoginView.vue 本身） |
| --- | --- | --- | --- |
| 输入插入符 caret | `--fuy-color-brand` #1e2a44→#2a2318，恰与 `--login-ink` 同值 | 零漂移 | 无需 |
| 文字选区 ::selection | #f0d9d3→朱洗暖化 rgba(165,53,44,0.14) | 微漂移，方向更贴 v3 暖纸 | 默认接受；如主控要求绝对零漂移，在 index.css 全局层补 `.login-page ::selection { background: #f0d9d3 }` |
| 滚动轴 scrollbar-color | 石规随换血 #c6c0ae→#d8c9a8；仅 ≤900px 窄窗容器滚轴可见 | 微漂移 | 默认接受；同上通道可钉原值 |
| 全局焦点环 outline | brand 换棕墨；登录页唯一焦点件=提交按钮已自覆写纸白环（1551-1554） | 零可见漂移 | 无需 |
| scoped @font-face 与全局重复声明 | 同描述符合并 | 零漂移 | 无需（LoginView 声明禁删） |

**门禁锚点**：LoginView.spec.ts 全量既有用例不改一字、全绿为登录零回归的机器证据；任何基础册改动后先跑该 spec。

---

## ⑪ 基础册施工蓝图

### ⑪.1 tokens.css

- 改动段 1（文件头注释）：世界叙改为「暖纸卷宗 Warm Paper Archive」（2026-10-10 用户裁定全站化），命名空间/单向映射表述不变。
- 改动段 2（primitive 换血）：②.2~②.5 全部槽位换值 + 新增十值正名块（`--fuy-paper` 族 10 枚）。
- 改动段 3（semantic）：`--fuy-color-focus-ring` 换值；状态族按 §③.2 裁定值落位（A-2/A-3 未批前保旧值）。
- 改动段 4（新增）：字体双栈 + `--fuy-font-size-title` + radius 三档（card/frame/seal）。
- 改动段 5（阴影）：`--fuy-shadow-md` 换值 + 新增 sm/lg；注释「唯一阴影」改「阴影三级」。
- 改动段 6（原生面）：`::selection` 换值。
- M04/M05/M14/M16 增量段：零改动（semantic 别名自动承继）。

### ⑪.2 fonts.css（新增文件）

四条 `@font-face`（④.2）；文件头中文注释（用途/零外链红线/与 LoginView scoped 声明的关系）。`index.css` 增 `@import './fonts.css';` 于首位。

### ⑪.3 element-plus.css

- 改动段 1（`:root:root` 换血块）：primary 阶五档（`#2a2318 / #5a4c38 / #8a7a5e / #d8c9a8 / rgba(216,201,168,0.6) / rgba(234,223,200,0.5) / #1d1811`）、中性面四族（bg/text/border/fill 按 §② 换血：bg-color=#f8f4e9、page=#f4ecdb、border 族=石规浓淡档 rgba(216,201,168,x) 系+gold 深档、fill 族=暖底族）、遮罩 `rgba(42,35,24,0.5)`、阴影三档、`--el-input-*` 三枚、`--el-dialog-border-radius`、`--el-descriptions-item-bordered-label-background`、A-5 功能色洗底族（未批不落）。
- 新增挂类段：`.fuy-form` / `.fuy-tabs` / `.fuy-dialog` / `.fuy-drawer` / `.fuy-message` / `.fuy-empty` / `.fuy-status-pill`(+四色变体) / `.fuy-live-dot` / `.fuy-stamp-toast` / `.fuy-stitch-ink` / `.fuy-stitch-gold` / `.fuy-sign-divider` / `.fuy-page-head` 族 / `.fuy-card` 族 / `.fuy-page-foot` / `.fuy-sk` 族 / `.fuy-filter` / `.fuy-page` 子树空态插画压制规则 / `.fuy-dense` 斑马扩展。
- 既有段零改动：`.fuy-dense` 核心、`.fuy-tag-aa`、`.fuy-tag-strike`、`.fuy-triage-badge`、`.fuy-menu` 系、`.fuy-tree-popper`、`.fuy-snap-popper`（motion.css）、`.fuy-toolbar`、`.fuy-section-title`、`.fuy-total-strip`、`.fuy-page`、nursing 族。壳层微调一处：`.fuy-menu-brand` 增 `font-family: var(--fuy-font-family-serif)`（展开「富云」衬线字标；折叠「富」单字增 brush——见 ⑪.6）。

### ⑪.4 motion.css

- `:root` 增两缓动 token；新增 `fuy-stamp-in` / `fuy-note-in` / `fuy-halo-breathe` 三键帧 + `.fuy-page-*` 过渡类段（⑦.4）；文件头注释补「氛围动效限登录门厅」边界。fuy-login-* 段冻结。

### ⑪.5 index.html

`<head>` 增 4 条字体 preload（④.2）；零其他改动。

### ⑪.6 壳层改动清单

- **MainLayout.vue**：`<RouterView />` 换 ⑦.4 过渡包裹（唯一必改）；aside/header/content 样式零改动（token 换血自动承继）。
- **AppSidebar.vue**：零必改。微调（可选，随基础册一并）：品牌字标衬线化经 element-plus.css `.fuy-menu-brand` 承载（组件文件不动）。
- **AppHeader.vue**：零必改。微调（可选）：站点名 `.app-header-title` 增 `font-family: var(--fuy-font-family-serif)`（文本锚点「富云医护工作站」禁改名不变，仅字体）。
- **menu.ts / 路由 / store**：零改动。

### ⑪.7 ForbiddenView 重排蓝图（403）

现 27 行 el-result 脸整体替换为门牌构图（零出网、零 STOMP）：

```html
<section class="fuy-page forbidden-page">
  <div class="forbidden-mark" aria-hidden="true">禁</div><!-- 书法底字水印 280px 级 朱砂 @0.06 呼吸可选(静态默认) -->
  <header class="fuy-page-head">
    <h1 class="fuy-page-title">403 · 无访问权限</h1>
    <p class="fuy-page-note">签认人 — · 时刻 —</p><!-- 空值 — 占位；或接 authStore 真实登录名 -->
  </header>
  <div class="fuy-empty forbidden-body" role="alert">
    <p class="fuy-empty-title">当前会话未获授权访问该功能</p>
    <p class="fuy-empty-hint">请联系管理员开通权限；被拒路由不提供自动重试。</p>
  </div>
  <el-button type="primary" @click="goHome">返回首页</el-button>
</section>
```

规格：书法「禁」水印=装饰层（aria-hidden，静态或 `fuy-login-seal-breathe` 不复用——业务页禁氛围动效，保持静态）；主句衬线；按钮墨实底；页面进场随路由 `.fuy-page` 过渡自然显影，页内不另加动画。既有 ForbiddenView.spec 按新构图重写（见 ⑪.8-3）。

### ⑪.8 api 层新增蓝图（system orgs + dicts 消费）

- **后端**（另线，调研 §3.2 A1/A2）：`GET /api/v1/system/orgs?type=WARD|DEPT`（OrgEntity 已在，V1123 起种子迁移，权限矩阵登记 + RbacMatrixIT 守护，OpenAPI 重生成 api.d.ts）。
- **前端 `api/system.ts` 增**（类型一律取生成物，禁手写 paths）：

```ts
/** 契约类型别名（生成物唯一来源）：组织节点视图（病区/科室共用） */
export type OrgVO = components['schemas']['OrgVO'];
/** 契约类型别名：字典项视图 */
export type DictItemVO = components['schemas']['DictItemVO'];

/** 查询组织清单（type=WARD 病区 / DEPT 科室）：替换 12 视图 WARD_OPTIONS 与 3 门诊视图 DEPT-INT 假常量 */
export async function listOrgs(query: { type: 'WARD' | 'DEPT' }): Promise<OrgVO[]>;

/** 查询字典项清单（typeCode 路径参数）：替换 inpatient ORDER_FREQUENCY_OPTIONS / outpatient DISPOSITION_OPTIONS 前端常量 */
export async function listDictItems(typeCode: string): Promise<DictItemVO[]>;
```

- **消费面**：病区选择族（WardBoard/ExecutionWorkbench/Admission/BedMap/InpatientDoctorStation/TransferWorklist/DispenseWorkbench/InpatientDispense/CallWorkbench/InfusionBoard/ColdChain 等）与门诊三视图换真数据下拉（useAsyncTask 三态）；ward.ts `WARD_OPTIONS='1001'` 与他处 `'W01'` 不一致随收编清零（假常量零残留，死代码纪律）。

### ⑪.9 TDD 锚点清单（先写失败测试再施工）

| # | 测试文件 | 断言要点 |
| --- | --- | --- |
| 1 | `api/system.spec.ts`（增） | ① `listOrgs({type:'WARD'})` 请求 `GET /v1/system/orgs?type=WARD` 且返回 data 直通；② `type:'DEPT'` 同理；③ http reject 时 promise reject（错误冒泡不吞）；④ `listDictItems('ORDER_FREQUENCY')` 请求路径 `/v1/system/dicts/ORDER_FREQUENCY`（typeCode 编码在位） |
| 2 | `views/layout/MainLayout.spec.ts`（增） | ① RouterView 包裹 Transition 且 `name="fuy-page"`、`mode="out-in"`（挂载后经真实 router push 同步帧断言内容子元素出现 `fuy-page-enter-active/from` 类）；② 既有折叠态/事件上行用例全绿不破 |
| 3 | `views/error/ForbiddenView.spec.ts`（重写） | ① 渲染「403」与「无访问权限」文本锚点；② 批注行存在且空值以 — 占位；③ 点击「返回首页」push `'/'`；④ 断言零 el-result 结构（旧断言随本次改动删除，重写属本次功能交付内合法断言现代化） |
| 4 | `views/login/LoginView.spec.ts`（冻结门禁） | 零改动全绿——任何基础册提交的前置门禁；登录零回归的机器证据 |
| 5 | 样式层回归（轻量） | 若既有惯例存在样式文件读取断言则同步；否则以 `pnpm lint/format:check/type-check/test/build` 全绿 + grep 门禁（禁裸 `.el-*` 新增、motion.css 外零 `@keyframes`、tokens.css 外零新 hex 字面量——归 scripts 编码校验扩展项呈批）兜底 |
| 6 | 各页批次 spec | 见 ⑫（每页 spec 先行重写后施工） |

> **降级注记（2026-10-10 主控裁决）**：锚点 2 的挂类同步帧断言若真实时钟下 flaky（jsdom 真实定时器时序），允许降级为 Transition `name`/`mode` 结构 props 断言，须在 spec 注释留痕降级理由——MainLayout.spec 已按此口径交付，严格度不低于契约主句（两个业务声明点仍被锚定）。

---

## ⑫ 批次施工纪律（单页单 agent 建段守则）

1. **单页单 agent**（web A.8 执行模式铁律）：每实现 subagent 只负责单页（page + 子组件 + 对应 spec）；共享层（tokens/element-plus/motion/fonts/index）只归基础册，页 agent **禁改共享层与 LoginView、禁越页**。
2. **忠实蓝图施工**：方向=本契约 + DESIGN.md（暖纸世界版）；施工前实读本契约 ⑧ 页级语法与 ⑤ 通道表；建段全程保持两技能可查；不偏移、不擅自降级、不做契约外发挥。
3. **spec 先行**：每页施工前先重写该页 spec（断言新构图与业务行为：门牌页首锚点、`.fuy-page/.fuy-card/.fuy-form` 挂类、空态脸、假常量清零后的真数据三态），红→绿；测试与实现同一次提交。
4. **每页自验清单**（提交前逐项勾）：
   - ① 零新 hex 字面量（取色只走 `--fuy-*` 语义别名；装饰朱/状态朱按双红分工取值）；
   - ② `.fuy-page` 根挂载 + 门牌页首（衬线标题/「谁·何时」批注/墨规收底）齐备；
   - ③ 表单挂 `.fuy-form`，弹层挂 `.fuy-dialog/.fuy-drawer`，显式 popper 挂 `fuy-snap-popper`；
   - ④ el-empty 全部空态脸化（零默认纸箱插画、文案合「暂无」语法）；
   - ⑤ 零新增 `@keyframes`（只消费 motion.css）；零氛围级动效；动效只 transform/opacity；
   - ⑥ 数字列全 `.fuy-num`；状态全双通道；徽标取语义别名 token；
   - ⑦ 长列表合规（⑨.4 阈值）；STOMP 面批处理；骨架 min-height 锁 CLS；
   - ⑧ 假常量（WARD_OPTIONS/DEPT-INT/字典常量）清零改真端点；
   - ⑨ `pnpm lint --max-warnings=0` / `type-check`（双 project）/ `test` / `build` 全绿；既有 LoginView.spec 全绿；
   - ⑩ 截图自验（>1MB 禁入库，JPEG 化）。
5. **验证**：每页完成后派独立验证 subagent（A.8 验段律：三级定级 + 四维核验，证据=真实渲染同屏目测）；批次全过后另派终验 subagent 全局收口。

（契约完）
