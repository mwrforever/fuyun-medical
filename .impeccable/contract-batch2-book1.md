# 批次 2 · 册 1 设计契约（壳层改约与登录门面）

> 主控亲产（执行文档 N1 节点，不派遣）。输入：DESIGN.md + 契约 v2（`.impeccable/brief-body-workstation-shell.md`）+ v1 surface brief + menu.ts 实盘（34 项+9 分组）+ `@element-plus/icons-vue@2.3.2` 包导出实盘（293 枚）。
> 效力：册 1 全部设计与实现节点的唯一契约；与 DESIGN.md 冲突以本契约为准（册 1 范围内）；图标映射已经 node 包导出机器核实（43/43 在位零缺失，2026-10-08 深夜会话）。

## 1. 图标语义映射（34 项 + 9 分组，逐项列死）

选型原则：①语义优先——图标名即业务动作/客体，禁纯装饰；②线性形态（非 Filled 实底）——实底留给选中态/危急语义，与「朱只承状态」同理；③组图标与项图标不重名（扫读双通道不混淆）；④医疗语义优先取具象物（病历/号票/药盒/呼叫铃），抽象符号仅作兜底。

### 1.1 分组图标（9 组，el-sub-menu 父节点）

| 分组 | 图标 | 业务理由 |
| --- | --- | --- |
| 患者管理 | `User` | 分组主体=患者本人，最直白的人员域标识 |
| 门诊服务 | `FirstAidKit` | 门诊急救箱——医疗服务第一入口，具象且零歧义 |
| 收费管理 | `Wallet` | 钱包——收费域的资金语义载体 |
| 药房管理 | `Box` | 药盒/摆药筐——药房核心客体 |
| 护理管理 | `Notebook` | 护理记录本——护理域本质是执行文书与记录 |
| 住院管理 | `OfficeBuilding` | 住院大楼——在院域的空间语义 |
| IoT 管理 | `Cpu` | 芯片——物联网设备的数字内核 |
| 病区视图 | `Monitor` | 病区监视屏——看板域的呈现语义 |
| 系统 | `Setting` | 齿轮——系统管理通用语义（权限管理唯一子项） |

### 1.2 页项图标（34 项）

| 路由 | 页名 | 图标 | 业务理由 |
| --- | --- | --- | --- |
| `/` | 首页 | `HomeFilled` | 门户首页唯一允许的实底图标（起点强调，与药丸选中态同语法） |
| `/patient/create` | 患者建档 | `DocumentAdd` | 新增文书——建档即新建病案文书 |
| `/patients` | 患者检索 | `Search` | 检索放大镜，通用零歧义 |
| `/outpatient/registration-charge` | 挂号收费 | `Tickets` | 号票——挂号凭证的具象物 |
| `/outpatient/triage-board` | 分诊台 | `Compass` | 罗盘——分诊的病情导向与分流语义 |
| `/outpatient/doctor-station` | 门诊医生站 | `EditPen` | 处方笔——医生站核心动作是开立处方/医嘱 |
| `/billing/pricing-settle` | 划价结算 | `PriceTag` | 标价签——划价动作的具象物 |
| `/billing/refunds` | 退费审批 | `Discount` | 折让——资金减免返还语义（退费） |
| `/billing/daily-list` | 一日清单 | `List` | 清单列表——日结清单的直白形态 |
| `/pharmacy/drug-dict` | 药品字典 | `Reading` | 翻阅——字典典藏查阅语义 |
| `/pharmacy/dispense-workbench` | 发药工作台 | `Sell` | 售出发药——药品出库交付动作 |
| `/pharmacy/dispense-return` | 退药受理 | `TakeawayBox` | 取回药盒——已发药品被取回的退药画面 |
| `/nursing/ward` | 护士站 | `Bell` | 呼叫铃——护士站作为病区响应中枢 |
| `/nursing/execution` | 护理执行工作台 | `Finished` | 完成勾——医嘱执行的核对完成语义 |
| `/nursing/adverse-events` | 不良事件上报 | `Warning` | 线性警示三角——不良事件的上报警示（非实底，警示色留给状态） |
| `/pda` | PDA 扫码 | `Iphone` | 手持终端——PDA 移动设备形态 |
| `/inpatient/admission` | 入院登记台 | `Memo` | 登记簿便签——入院登记的文书画面 |
| `/inpatient/beds` | 病区床位图 | `Grid` | 网格——床位图的矩阵布局语义 |
| `/inpatient/station` | 住院医生站 | `Operation` | 控制杆——住院医嘱的持续处置操作语义 |
| `/inpatient/transfer` | 转抄工作台 | `CopyDocument` | 誊抄文书——转抄动作的直译 |
| `/inpatient/discharge` | 出院管理 | `SuitcaseLine` | 行李箱（线性）——离院归家的画面语义 |
| `/pharmacy/review` | 住院审方台 | `DocumentChecked` | 审核勾验文书——处方审核完成态 |
| `/pharmacy/inpatient-dispense` | 住院摆药台 | `ShoppingTrolley` | 摆药推车——按单配药的病区摆药画面 |
| `/iot/products` | 产品与物模型 | `Files` | 规格文档族——产品档案+物模型定义 |
| `/iot/devices` | 设备管理 | `Odometer` | 仪表盘——在网设备的运行盘点语义 |
| `/iot/bindings` | 设备绑定 | `Connection` | 连接——设备与床位/实体的绑定关系 |
| `/iot/alarm-rules` | 告警规则 | `AlarmClock` | 闹钟——阈值触发的告警语义 |
| `/iot/commands` | 命令中心 | `Promotion` | 纸飞机——下行命令的发送语义 |
| `/iot/linkage-rules` | 联动规则 | `Link` | 链环——多设备联动编排 |
| `/iot/quality` | 质量看板 | `TrendCharts` | 趋势图——质量指标的统计呈现 |
| `/ward/infusion-board` | 输液看板 | `Pouring` | 倾注——输液滴注的液体语义 |
| `/ward/call-workbench` | 呼叫工作台 | `Service` | 服务耳机——呼叫应答的受理语义 |
| `/ward/cold-chain` | 冷链台账 | `Refrigerator` | 冷藏设备——冷链温控的直白具象 |
| `/system/permissions` | 权限管理 | `Key` | 钥匙——权限开锁语义（`Lock` 留作登录页密码域，避免混淆） |

核实记录：43 名已经 `node -e` 对照包导出全量核实（`web/node_modules/.pnpm/@element-plus+icons-vue@2.3.2.../dist/index.cjs`，293 枚池）零缺失；无近义替换项。

## 2. 登录页构图契约 v3（用户亲产样稿权威 · 2026-10-09 第二次用户裁决改写）

> **修订注记（v3，2026-10-09）**：用户亲手重写 login.html 样稿（v3.3，804 行）为「暖纸卷宗+朱砂印鉴」门面形态，并指令重新读取样稿对现有登录页重构优化。v2 契约中与样稿冲突的条款（墨脊左栏叙事面/全站零 box-shadow/全站零新色值口径/「请登录」锚点）以本 v3 为准，v2 全文废止（历史留痕见 CHANGELOG 2026-10-09 两条件目）。样稿=构图权威兼**最低设计要求**（实现须在其上更细致打磨：更美观精致、高级视觉、流畅动效交互、高渲染性能）。
- **语义**：登录即「启封当日病案」——全站唯一全视口页面，纸质卷宗世界的门厅：医护在案卷封面上签认身份，翻开内页进入工作世界。
- **构图（1440+ 桌面单形态，以 v3.3 样稿为唯一依据）**：
  - **外部留白**：暖纸底纹（radial+线性渐变+SVG 方胜纹重复+噪点 multiply）+ 六层动效底纹（墨晕呼吸×3 / 缓旋双印 / 祥云缓移×3 / 浮墨字×3 / 底部双线流水 / 光带缓扫）+ 鼠标视差（四层差速 rAF 插值，pointer:fine 且非 reduced-motion 才启用）+ 页面级朱砂走线（stroke-dasharray 行军线）+ 四角回字纹。
  - **stage 双栏卡片**（max-width 1160，圆角 14，样稿三级阴影，stage-in 进场）：左=封面（品牌「富」朱印块 / hero「以墨为凭 · 以纸为证」书法字 / tagline 朱左线引用体 / 五业务域标签 hover 浮起+朱下划 / 山水 SVG 远景 / cover-foot 元数据+竖排朱印「启封有据」）；右=病案内页（菱格底纹 / 四角朱花角 / 侧栏云纹+竖排批注「闭环流转 · 永续留痕 · 签认归档」/ sheet-head「启 · 当日病案」+状态胶囊呼吸点（「签认人 —」「开卷时刻 —」以 — 占位，零伪数据）/ divider「签」字分隔+双云 / 表单区）。
  - **表单区**：登录名/口令两字段（field-label 必填星+hint / field-wrap 纸亮底聚焦朱环+朱砂下划线展开 / 口令可见切换 / 错误态 shake+朱字提示）；提交按钮朱砂实底（纸纹噪点 overlay+hover 光带扫过+按压沉底+loading 白转轮）；底部审计注记（金左线提示条「登录行为纳入审计日志 · 留存不少于六个月」，静态真实合规语义）。
  - **成功反馈**：「启封成功」朱砂印章浮层（居中钤印式盖章动画，≤1000ms 编排后跳转；reduced-motion 下浮层直达终态、跳转编排缩短）。
- **色板（登录页局部色域）**：只用样稿 :root 十值（--paper #f4ecdb / --paper-deep #eadfc8 / --paper-edge #d8c9a8 / --ink #2a2318 / --ink-soft #5a4c38 / --ink-faint #8a7a5e / --cinnabar #a5352c / --cinnabar-dk #82251f / --cinnabar-lt #c7544a / --gold #b08d4f），零新增；全站 token 池零变动（收编归册 4 N14）。
- **字体**：标题/正文衬线宋（Noto Serif SC 栈），印章字/hero 书法（Ma Shan Zheng 栈）；实现**零外链**（禁 Google Fonts CDN）——优先字符子集 woff2 本地化（登录页用字集，单文件 <1MB 入 assets/fonts），网络不可得则系统栈回退（"Songti SC"/"STSong"/"SimSun" 宋族 + "KaiTi"/"STKaiti" 楷族）并如实登记视觉偏差。
- **动效（样稿语法全保留+世界动效语法）**：进场编排（stage-in → 品牌 → hero/tagline/tabs/foot fade-up 级联 → 右栏 head/divider/form/audit 级联）；常驻氛围（墨晕呼吸/双印旋转/祥云/浮墨字/流水/光带/走线行军/状态点脉冲）；微交互（标签 hover 浮起+朱下划/字段聚焦环+下划线展开/按钮 hover 浮起+光带/按压沉底/错误 shake/输入即清错）；硬约束：只动 transform/opacity、blur 滤镜仅静态不参与动画、新增 keyframes 一律落 `styles/motion.css`（fuy-login- 前缀，全站唯一 keyframes 来源，样稿内联演示）、prefers-reduced-motion 全静止兜底、禁 width/height 动画。
- **红线（v3 修订口径）**：色板/阴影/朱砂装饰三条以本契约放行范围为准（见上）；禁 lorem、禁伪数据；表单逻辑零改动（表单模型/校验规则/提交逻辑/store 交互零变动，只动视觉与模板结构）；EP 校验错误通道保留（错误文案仍经既有校验通道产生，视觉样式按样稿错误态收编呈现，通道不动）。
- **图标**：字段域图标取 icons-vue（`User` 登录名域 / `Lock` 口令域）或样稿内联 SVG stroke 图标（语义优先）；装饰性 SVG（云/山水/回纹）承世界语义放行。
- **验证口径**：taste 四维（高级视觉/高级交互/流畅动画/高性能渲染）逐项验收+真机三态（默认/聚焦/校验错误）+loading+成功印章浮层+登录成功链全通；实现须在样稿之上更细致打磨。

## 3. 壳层树形契约（AppSidebar/MainLayout/menu.ts）

- **menu.ts 数据模型**：`SidebarMenuItem` 删 posts/abbr 两字段与岗位族类型/常量/函数（PostKey/PostSelection/POST_OPTIONS/selectMenuItemsForPost/POST_SELECTION_KEY 零死代码）；增 `icon: string` 字段（项图标名，值域=§1.2 表）；分组结构沿 `groupMenuItems` 聚合（`MenuGroup` 增 `icon: string`，值域=§1.1 表）。
- **树形形态**：分组=可折叠父节点（el-sub-menu，组图标+组名+展开箭头）；页项=叶节点（项图标+全称，40px 高药丸选中语法延续——亮纸白药丸墨字 600 字重）；**默认全展开**（上班扫读第一优先，折叠是用户主动行为）。
- **收起态**（侧栏整体收起 64px）：图标条形态——组图标列（组内页项收纳于组图标悬浮 popover/tooltip 层）；页项图标严格居中；**悬浮 tooltip 全名**（el-tooltip，瞬切零动画）；当前选中项药丸压缩为图标块高亮。
- **瞬切零动画（铁律 · 2026-10-09 裁决收敛口径）**：瞬切面收敛为两处——侧栏 240↔64 宽度切换、弹层硬 snap（tooltip/ popover 无淡入，EP 折叠动画关闭）；分组展开/折叠内的叶项显影、展开箭头旋转等 transform/opacity 节奏动效属 2026-10-09 动效纠偏裁决放行面（原文留痕见 CHANGELOG 2026-10-09 条目），禁 height/width 动画红线不变。
- **MainLayout**：删岗位 provide 与岗位状态持有；侧栏宽度态（展开 240px/收起 64px）持有权留 MainLayout（折叠开关事件上行），不新增 store。
- **AppHeader 不动**（顶栏四件已合规；折叠开关/站点名/患者检索/用户下拉原样）。
- **权限口径不变**：消费方 hasRoutePermission 过滤（BUG-14 空集语义）；空权限会话=侧栏仅恒显项+诚实空态。

## 4. HomeView 删岗后口径（册 1 范围内）

- 门牌页首：问候语 + 批注行「登录名 · YYYY-MM-DD 周Z」（**岗位描边印移除**——岗位维度废除，批注行不再含「当前岗位」段；空值以 — 占位语法延续）；2px 墨规收底不变。
- 常用入口链接条：单字纸块缩写+名称的标签架语法不变；数据源=menu.ts 真实路由入口，过滤口径=仅权限单道（原「权限∩岗位双道」随岗位废除收敛为权限单道）。
- 指标带/趋势/事件流区域：册 1 不动（现裁剪态/诚实空态保留），册 2 接真实 API 归位。
- 问候语时段逻辑、日期格式化逻辑零改动。

## 5. 样稿扩册契约（N2 派遣输入）

- D1（login.html，v2 完全重构）：§2 v2 构图契约的静态样稿化——全视口沉浸式两栏（左世界叙事面/右表单工作面）、丰富元素填充、进场 stagger 编排/微交互/悬停反馈（动效遵 DESIGN.md 语法，样稿内联演示）；新增样式**内嵌页内**，禁改共享 paper-chart.css；零新色值（只用既有 token 字面量）；旧 login.html 构图降为反参照，禁止小修慢补。（**v3 注记（2026-10-09）**：D1 产物已被用户亲手重写替换（v3.3，暖纸卷宗+朱砂印鉴形态），现行样稿以用户版本为构图权威，§2 契约已同步 v3。）
- D2（home.html 侧栏改版）：侧栏节按 §3 树形契约改版（树形/图标/收起 64px/悬浮全名四形态齐）；paper-chart.css 扩树形侧栏样式段；图标用 `@element-plus/icons-vue` 包内真实 SVG path 内联（节点后详）；零新色值。
- 两样稿共同底线：静态可开零缺资源；样稿=构图权威兼**最低设计要求**（后续实现须在其上打磨增益）。
