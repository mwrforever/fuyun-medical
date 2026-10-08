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

## 2. 登录页构图契约（LoginView 纸墨门面）

- **语义**：病案夹的封面页——登录=翻开当日病案前的身份确认。Operate 底色，克制、可信、零炫技；**克制动效零入场动画**（无 stagger/无渐入编排，仅保留交互反馈级过渡）。
- **构图**：纸面三级铺陈（工作面 #f5f3ec 底 + 卡面 #fdfcf8 表单区）；品牌字标「富云」墨字 + 副题「富云医院信息系统 · 请登录」（「请登录」文案锚点保留——现有 LoginView 文案语义零改动）；表单区 **2px 墨规收底**（页级规线语法移植到门面）；字段=登录名/口令（现行表单模型零改动）。
- **阴影红线**：旧登录卡 box-shadow 属收编对象——**唯一阴影只给弹层**；登录表单区以纸面层级+墨规分割替代阴影立体感。
- **图标**：口令域可挂 `Lock`、登录名域可挂 `User`（包内已有映射，语义不与侧栏冲突——侧栏患者管理组亦用 User，属不同 surface 不构成混淆；若实现端判定混淆，字段图标可整体省略，非必选项）。
- **交互反馈**：聚焦环 2px 墨 outline（全站语法）；错误态红字+字段级提示（现行校验逻辑零改动）；登录按钮=「墨即操作」浓墨实底。
- **表单逻辑零改动清单**（红线）：表单模型/校验规则/提交逻辑/store 交互（auth store 登录调用、加载态、错误信息呈现通道）全部保留，只动视觉与模板结构。

## 3. 壳层树形契约（AppSidebar/MainLayout/menu.ts）

- **menu.ts 数据模型**：`SidebarMenuItem` 删 posts/abbr 两字段与岗位族类型/常量/函数（PostKey/PostSelection/POST_OPTIONS/selectMenuItemsForPost/POST_SELECTION_KEY 零死代码）；增 `icon: string` 字段（项图标名，值域=§1.2 表）；分组结构沿 `groupMenuItems` 聚合（`MenuGroup` 增 `icon: string`，值域=§1.1 表）。
- **树形形态**：分组=可折叠父节点（el-sub-menu，组图标+组名+展开箭头）；页项=叶节点（项图标+全称，40px 高药丸选中语法延续——亮纸白药丸墨字 600 字重）；**默认全展开**（上班扫读第一优先，折叠是用户主动行为）。
- **收起态**（侧栏整体收起 64px）：图标条形态——组图标列（组内页项收纳于组图标悬浮 popover/tooltip 层）；页项图标严格居中；**悬浮 tooltip 全名**（el-tooltip，瞬切零动画）；当前选中项药丸压缩为图标块高亮。
- **瞬切零动画**（铁律）：侧栏展开↔收起、菜单组展开↔折叠均无过渡动画（EP 折叠动画关闭）；tooltip 无淡入。
- **MainLayout**：删岗位 provide 与岗位状态持有；侧栏宽度态（展开 240px/收起 64px）持有权留 MainLayout（折叠开关事件上行），不新增 store。
- **AppHeader 不动**（顶栏四件已合规；折叠开关/站点名/患者检索/用户下拉原样）。
- **权限口径不变**：消费方 hasRoutePermission 过滤（BUG-14 空集语义）；空权限会话=侧栏仅恒显项+诚实空态。

## 4. HomeView 删岗后口径（册 1 范围内）

- 门牌页首：问候语 + 批注行「登录名 · YYYY-MM-DD 周Z」（**岗位描边印移除**——岗位维度废除，批注行不再含「当前岗位」段；空值以 — 占位语法延续）；2px 墨规收底不变。
- 常用入口链接条：单字纸块缩写+名称的标签架语法不变；数据源=menu.ts 真实路由入口，过滤口径=仅权限单道（原「权限∩岗位双道」随岗位废除收敛为权限单道）。
- 指标带/趋势/事件流区域：册 1 不动（现裁剪态/诚实空态保留），册 2 接真实 API 归位。
- 问候语时段逻辑、日期格式化逻辑零改动。

## 5. 样稿扩册契约（N2 派遣输入）

- D1（login.html，新建）：§2 构图契约的静态样稿化——纸面三级/品牌字标/2px 墨规收底表单区/克制动效零入场；新增样式**内嵌页内**，禁改共享 paper-chart.css；零新色值（只用既有 token 字面量）。
- D2（home.html 侧栏改版）：侧栏节按 §3 树形契约改版（树形/图标/收起 64px/悬浮全名四形态齐）；paper-chart.css 扩树形侧栏样式段；图标用 `@element-plus/icons-vue` 包内真实 SVG path 内联（节点后详）；零新色值。
- 两样稿共同底线：静态可开零缺资源；样稿=构图权威兼**最低设计要求**（后续实现须在其上打磨增益）。
