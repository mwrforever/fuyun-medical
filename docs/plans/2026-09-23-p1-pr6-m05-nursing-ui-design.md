# PR-6 M05 护理模块 · 前端 UI 设计规范

> **文档定位**：PR-6 计划（M05 护理模块）Task 12 前端任务的**唯一视觉与交互权威**，覆盖护士站页（`/nursing/ward`）与 PDA 页（`/pda`）两页及体温单符号渲染规范。SDD 实现者按本文落码，spec 断言值以本文冻结契约（§5.3 符号类名、§3 区块类名）为准。
> **增量规范**：PR-5 设计文档（`docs/plans/2026-09-20-p1-pr5-m03-outpatient-ui-design.md`）§2–§7 为通用地基（token 四文件、布局栅格、交互三态、动效参数、性能红线），**冲突以本文件为准**。本文不重复定义任何 PR-5 既有 token / 工具类 / keyframes，只做增量声明。
> **约束来源**：全局 `~/.zcode/AGENTS.md` + `web/AGENTS.md` + M05 Spec（`docs/specs/modules/05-nursing.md`，体温单符号以 Spec §2 调研依据 2 与调研依据 6 为**内容唯一权威**）。
> **设计方法论**：ui-ux-pro-max 族四技能（ui-ux-pro-max / design / design-system / ui-styling）加载后产出。技能检索对「医院护士站高密度工作台」无数据库命中（query: hospital nursing station dense clinical dashboard → 0 结果，重试 dense data table dashboard → 仅泛化表格建议），按技能契约声明：本领域规范以内置优先级规则表（无障碍 → 触控交互 → 性能 → 布局）+ PR-5 已验证地基为 fallback 基线；移动触控面吸收检索命中三条硬规则（触控目标 48dp / 相邻目标间距 ≥8px / `overscroll-behavior: contain` 防误触下拉刷新）。

---

## 1. 设计定位与原则

### 1.1 两页一句话定位

| 页面 | 定位 | 视觉气质 |
| --- | --- | --- |
| 护士站页（workstation `/nursing/ward`） | 病区护理指挥台——一屏完成「看床位墙 → 选患者 → 录体征 / 看体温单 / 做评估 / 管任务」闭环 | 亮色、克制、信息密度优先、零装饰（继承 PR-5 workstation 定位） |
| PDA 页（workstation `/pda`，顶层路由） | 床旁移动操作面——护士单手持机，四步完成「扫腕带 → 核对患者 → 录体征 → 巡视打卡」 | 亮色、大触控目标、单列单任务流、脱敏展示 |

### 1.2 模块级原则（继承 PR-5 §1.2 四条 + 护理特化三条，冲突按序裁决）

继承（不赘述，原文见 PR-5 §1.2）：HIS 专业感、信息密度优先、动效克制有目的、无障碍底线。

护理特化：

5. **医疗安全优先于效率**：体温单符号规范（§5）是病历法律文书的一部分，符号形态、颜色、坐标分度**不得为视觉美观让步**；宁可页面朴素，不可符号失真。符号渲染实现零创造裁量。
6. **患者安全标识永不满屏红**：卡墙角标（危/过敏/风险）数量多时以「上限 4 个 + 溢出 +N」收敛（§3.4），红色只保留给最高优先级标识，防止红色泛滥导致的警示疲劳。
7. **PDA 单手可完成全部操作**：所有触控目标 ≥48px、主按钮全宽置底、扫码输入框自动聚焦（扫码枪即键盘形态），护士一手扶床一手操作。

---

## 2. 复用与增量 token

### 2.1 复用面清单（零重定义，直接消费）

token 四文件实况落点为 `web/apps/workstation/src/styles/`（tokens.css / element-plus.css / motion.css / index.css；派发简报所述 `web/packages/shared/src/styles/` 路径与实况不符，以实况为准——shared 为纯 TS 包不承载样式，PR-5 §2.1 既有裁决）。

| 复用面 | 消费方式 |
| --- | --- |
| 品牌色阶与语义色（`--fuy-palette-*`、`--fuy-color-*`） | 新页面全部颜色经 token 引用，组件零裸 hex |
| 字号阶梯（`--fuy-font-size-xs/sm/md/lg/xl/2xl/3xl`） | 高密度表格 13px、正文 14px、区块题 16px、页面题 18px |
| 间距系统（`--fuy-space-1..16`） | 卡内 `space-4`、卡间 `space-3`、表单行距 `space-3` |
| 圆角 / 阴影 / 描边（§2.5 PR-5） | 卡片无阴影 1px 描边，弹层 `shadow-md` |
| 动效 token 与全部既有过渡类（motion.css） | §6 编排表零新 keyframes |
| 工具类 `.fuy-dense` / `.fuy-num` / `.fuy-page` / `.fuy-toolbar` / `.fuy-section-title` / `.fuy-tag-aa` / `.fuy-tag-strike` / `.fuy-ops-8` | 表格容器挂 `fuy-dense`、数字列 `.fuy-num`、页面根 `.fuy-page` |
| EP 主色映射（`:root:root`） | 零改动（element-plus.css 不动） |

### 2.2 增量 token（唯一清单，落 `tokens.css` 尾部新段）

三层纪律：全部为 semantic 层语义别名或组件/场景几何值，**零新色值**（色值一律指向既有 palette）；实现期 tokens.css 的 diff 必须且只能包含本清单。

```css
:root {
  /* ===== M05 护理模块增量（设计文档 §2.2 唯一清单）===== */

  /* 护理级别徽标色（实底白字场景；色值与分诊四级同源 palette，语义独立命名防混用） */
  --fuy-color-nursing-special: var(--fuy-palette-red-600);    /* 特级护理 4.8:1 */
  --fuy-color-nursing-l1: var(--fuy-palette-orange-700);      /* 一级护理 5.2:1 */
  --fuy-color-nursing-l2: var(--fuy-palette-amber-700);       /* 二级护理 5.0:1 */
  --fuy-color-nursing-l3: var(--fuy-palette-green-800);       /* 三级护理 6.3:1 */

  /* 病情标记角标（卡墙 mini 徽标底色） */
  --fuy-color-nursing-critical: var(--fuy-palette-red-600);   /* 病危「危」/ 过敏「敏」 */
  --fuy-color-nursing-serious: var(--fuy-palette-orange-700); /* 病重「重」 */
  --fuy-color-nursing-surgery: var(--fuy-palette-blue-700);   /* 手术「术」 */
  --fuy-color-nursing-new: var(--fuy-palette-brand-700);      /* 新入「新」 */
  --fuy-color-nursing-exit: var(--fuy-palette-gray-600);      /* 转出/今日出院（弱化不抢焦） */

  /* 体温单专用（§5 渲染唯一取色来源；蓝=体温系、红=脉搏/降温/事件系，临床惯例色） */
  --fuy-chart-temp-color: var(--fuy-palette-blue-700);   /* 体温符号与连线（蓝笔惯例）6.7:1 */
  --fuy-chart-pulse-color: var(--fuy-palette-red-600);   /* 脉搏/心率/物理降温/短绌/事件竖线（红笔惯例）4.8:1 */
  --fuy-chart-grid-color: #e5e7eb;                        /* 网格细线（与 --fuy-border-hairline 同值，SVG stroke 场景别名） */
  --fuy-chart-text-color: var(--fuy-palette-gray-600);   /* 刻度与底栏文字 */

  /* 体温单几何（§5.2 坐标系冻结值；SVG viewBox 计算唯一来源） */
  --fuy-chart-grid-x: 16px;      /* X 轴每小格宽（= 2 小时） */
  --fuy-chart-grid-y: 8px;       /* Y 轴每小格高（= 0.2℃ = 脉搏 4 次/分） */
  --fuy-chart-symbol-size: 10px; /* 符号外接尺寸（×/●/〇 直径） */
  --fuy-chart-line-width: 1.5px; /* 曲线连线与符号描边线宽 */

  /* PDA 页移动基线（§4 冻结输入约束） */
  --fuy-pda-touch: 48px;         /* 触控目标最小尺寸（工效学最小值，全页控件高度下限） */
  --fuy-pda-font-base: 16px;     /* PDA 字号基线（正文） */
  --fuy-pda-page-width: 480px;   /* PDA 自持布局最大宽度（居中） */
}
```

### 2.3 增量工具类（落 `element-plus.css` 尾部新段，唯一清单）

```css
/* 护理级别徽标（复用 .fuy-triage-badge 同形态，色值独立取护理四级 token） */
.fuy-nursing-level-badge { display: inline-block; min-width: 36px; padding: 0 8px; height: 22px;
  line-height: 22px; border-radius: var(--fuy-radius-sm); color: #fff;
  font-size: var(--fuy-font-size-xs); font-weight: 600; text-align: center; }
.fuy-nursing-level-badge--special { background: var(--fuy-color-nursing-special); }
.fuy-nursing-level-badge--l1 { background: var(--fuy-color-nursing-l1); }
.fuy-nursing-level-badge--l2 { background: var(--fuy-color-nursing-l2); }
.fuy-nursing-level-badge--l3 { background: var(--fuy-color-nursing-l3); }

/* 卡墙病情/风险 mini 角标（高 18px 单字/双字徽标，密度收敛） */
.fuy-nursing-flag { display: inline-block; min-width: 18px; padding: 0 4px; height: 18px;
  line-height: 18px; border-radius: var(--fuy-radius-sm); color: #fff;
  font-size: var(--fuy-font-size-xs); font-weight: 600; text-align: center; }
.fuy-nursing-flag--danger { background: var(--fuy-color-nursing-critical); }   /* 危/敏 */
.fuy-nursing-flag--warning { background: var(--fuy-color-nursing-serious); }   /* 重 */
.fuy-nursing-flag--info { background: var(--fuy-color-nursing-surgery); }      /* 术 */
.fuy-nursing-flag--brand { background: var(--fuy-color-nursing-new); }         /* 新 */
.fuy-nursing-flag--muted { background: var(--fuy-color-nursing-exit); }        /* 转/出 */
/* 风险标识（跌倒/压疮/管路）：红描边空心形态，与过敏实底区分（防红色实底泛滥） */
.fuy-nursing-flag--outline { background: #fff; color: var(--fuy-color-danger-text);
  border: 1px solid var(--fuy-color-danger-text); }
```

页面级 scoped 类（`fuy-temp-*` 等体温单符号类、`fuy-assess-*`、`fuy-task-*` 等）不入全局文件，在视图 scoped 内定义（§5.4 / §3 各区块给出精确值）；其中**契约类名**（spec 断言依据）在 §5.3 与 §3.10/§3.9 冻结。

---

## 3. 护士站页布局骨架（八区块）

### 3.1 页面外框与栅格

- 路由：MainLayout children，`path: 'nursing/ward'`，`meta: { permission: 'nursing:ward:view' }`。
- 外框：根节点 `.fuy-page`（PR-5 §3.1：max-width 1600px、padding space-4、flex column、gap space-3）。
- 栅格：延续 `el-row`/`el-col`（`:gutter="16"`），断点 `md ≥992` 折叠单列、`lg ≥1200` 完整三列、`xl ≥1536` 卡墙升 10 列。
- 页面题：页头行「护士工作站」18px/600 + 病区名（当前选中病区回显）。

### 3.2 八区块布局树（整体结构）

```
.fuy-page
├── [页头/操作条 48px] ① 病区选择（el-select 180px）+ 病情计数（在区 N / 危 N / 重 N，
│   .fuy-num 16px/700）+ 待复核徽标（红底白字圆形 18px，挂体征区块锚点）
│   +「入区登记」primary 按钮（96px，弹窗）+ 刷新按钮（图标按钮 32×32）
├── el-card ② 床位序患者卡墙（全宽）
│   └── grid：lg 8 列 / xl 10 列，gap 8px；床位卡 min-width 148px、高 84px
├── el-row :gutter=16（操作层三列）
│   ├── el-col :md=24 :lg=6 —— ③ 患者详情面板（el-card，min-height 200px）
│   │   └── ④ 责任护士分配（el-card，挂 ③ 下方）
│   ├── el-col :md=24 :lg=12 —— ⑤ 体征录入 + 待复核列表（el-card 双段，min-height 240px）
│   │   └── ⑦ 护理评估（el-card，挂 ⑤ 下方）
│   └── el-col :md=24 :lg=6 —— ⑧ 任务列表 + 交接班双签（el-card 双段，min-height 240px）
└── el-card ⑥ 体温单渲染区（全宽，min-height 420px：月页切换条 + SVG 曲线区
    + 日行值底栏 + 特殊事件录入行；渲染规范见 §5）
```

信息层级裁决：**看人（②）→ 做操作（③④⑤⑦⑧）→ 查趋势（⑥）**。卡墙全宽置顶（床位墙的「墙」语义）；体温单全宽置底（宽幅需求：7 天 × 192px/天 ≈ 1344px 恰好一周可视，中列放不下）；md 折叠为单列纵排，DOM 顺序即 Tab 顺序 = ①②③④⑤⑦⑧⑥。

### 3.3 区块①：病区选择 + 入区登记弹窗

- **病区选择**：`el-select`（180px，size 默认）选项来自后端病区列表；切换即重载 ②⑤⑧（`v-loading` 各自区块，不整页遮罩）。默认记住上次病区（sessionStorage 键 `nursing.wardId`，仅会话内）。
- **病情计数**：在区总数（24px `.fuy-num`/700）+「危 N」「重 N」（13px，色 `--fuy-color-nursing-critical` / `--fuy-color-nursing-serious`）。
- **入区登记弹窗**（`el-dialog` 宽 520px 固定、`destroy-on-close`、标题 16px/600）：

| 字段 | 控件 | 必填 | 校验（失焦 + 提交双触发） |
| --- | --- | --- | --- |
| 患者 ID | el-input 220px | 是 | 非空纯数字；错误文案「患者 ID 应为数字编号，请核对住院登记」 |
| visit 号 | el-input 220px | 是 | `/^I\d{13}$/`（I 前缀 + 8 位日期 + 5 位流水，共 14 字符）；错误文案「visit 号应以 I 开头共 14 位（I+日期+流水），请核对入区单」 |
| 姓名 | el-input 120px | 是 | 非空 |
| 床位 | el-select 160px（空余床位） | 是 | 非空 |
| 护理级别 | el-select 160px（特级/一级/二级/三级） | 是 | 非空 |
| 病情标记 | el-select multiple（危/重/新入/手术/分娩） | 否 | — |

- 提交按钮 `:loading` + 在途 ref 守卫（`if (registering.value) return` 先于一切 await）；成功 `ElMessage.success('入区登记完成')` + 弹窗关闭 + 卡墙插入新卡（fuy-flip enter）；失败拦截器统一弹错（spec 用例：visit 号填 `I2026` → 提示 + `wardPatients.register` 调用 0 次）。

### 3.4 区块②：床位序患者卡墙

- 数据 `GET /ward-patients?wardId=`，**按床位号升序渲染**（spec 冻结语序断言；床位号字符串序即临床序）。
- 床位卡（`.fuy-bed-card`，84px 高，min-width 148px，1px 描边 `--fuy-border-hairline`，radius-lg）：

```
┌────────────────────┐
│ 03-01  张三 62岁 [Ⅱ] │  床位号(.fuy-num 16px/700) + 姓名(14px/600) + 年龄 + 护理级别徽标
│ [危][术][敏][跌]+1  │  角标行（.fuy-nursing-flag，18px；上限 4 个，超出 +N）
│ 责任 李护士 · 4 在途  │  责任护士 + 在途任务数（12px 次要色）
└────────────────────┘
```

- 选中态：描边转品牌 2px + 底 `--fuy-palette-brand-100` + 左缘 3px 品牌色条（120ms 描边/底色 transition，PR-5 号源卡选中同形态）；选中驱动 ③⑤⑥⑦ 患者上下文。
- 角标溢出规则：显示优先级 = 过敏 > 危 > 重 > 跌倒/压疮/管路 > 新入/手术/分娩/转出/今日出院；上限 4 个 + 「+N」溢出角标（灰），全集在 ③ 详情面板可见（§1.2-6 红色收敛原则）。
- 空床位渲染为虚线框卡（床位号 + 「空床」12px 灰），可点击直接打开入区登记弹窗（床位预填）。
- 出区操作：卡右上「⋯」下拉（出区/转科），出区为高风险档（danger 弹窗 + 必填原因 + 回显摘要「即将为 03-01 张三办理出区」）。
- 动效：`<TransitionGroup name="fuy-flip">` 承接入区插入/出区移除；卡片列表容器挂 stagger 进场。

### 3.5 区块③：患者详情面板

- 数据：`GET /ward-patients` 选中项 + 前端另调 `GET /vital-signs?patientId=&from=&to=`（近 24h）组装「最新体征」行（简报冻结口径）。
- 结构（el-descriptions `:column="1"` border + 标识区）：
  - 患者头行：姓名（16px/600）+ 性别/年龄 + 护理级别徽标 + 病情 tag。
  - **过敏区**（`GET` 过敏嵌查，M02 缓存）：红色实底「敏」+ 过敏源列表（13px，`--fuy-color-danger-text`）；无过敏显示灰字「无已知过敏」。
  - 风险标识行：跌倒/压疮/管路（描边空心角标，来自评估单高危结果）。
  - 责任护士（当前班次）。
  - 在途任务：任务数徽标（warning 底白字圆形 18px）+ 最近 3 条任务摘要（12px）。
  - 最新体征行：体温（含部位符号字 ×/●/〇）/ 脉搏 / 呼吸 / 血压 / 血氧（`.fuy-num` 14px）+ 测量时点（12px）。
- 未选患者空态：`<el-empty :image-size="72" description="从床位卡墙选择患者查看详情">`。

### 3.6 区块④：责任护士分配

- 结构：班次 `el-select`（100px：早班 D/中班 E/夜班 N，词表前端常量）+ 分配列表 + 增删。
- 分配行（行高 36px）：护士姓名（14px）↔ 床位段/患者数（13px `.fuy-num`）+「移除」link 按钮；底部「新增分配」按钮展开内联行（护士 select + 床位多选 + 确认/取消）。
- 提交 `POST /assignments`（`:loading` + 守卫）；**不做拖拽调整**（Spec FU-M05-01 的拖拽批量分配登记为 P-later 演进，本 PR 只交付增删形态——简单优先）。
- 空态：「本班次暂无分配，请新增」。

### 3.7 区块⑤：体征录入 + 待复核列表

**录入表单**（患者上下文 = 选中床位卡；未选中时整段禁用 + 灰字「先选择患者」）：

- 布局：两行 × 四列 grid（字段组 min-width 170px，行距 `--fuy-space-3`）：

| 行 | 字段 | 控件与校验（显式，`@change` + 提交双触发） |
| --- | --- | --- |
| 1 | 体温值 + 部位 | el-input 80px（`inputmode="decimal"`）+ el-select 88px（腋下/口腔/直肠）；值 35.0–42.0 一位小数 `/^\d{2}(\.\d)?$/` 且范围判定；错误「体温应为 35.0–42.0 的数值（如 36.5），请重新输入」 |
| 1 | 脉搏 | el-input-number 80px `:precision="0"`；20–250 整数；错误「脉搏应为 20–250 的整数」 |
| 1 | 呼吸 | 同上，5–60；错误「呼吸应为 5–60 的整数」 |
| 1 | 血压（收缩/舒张） | 两枚 72px 整数框；收缩 60–250、舒张 30–180；错误「收缩压应为 60–250 的整数」等 |
| 2 | 血氧 | 80px 整数 50–100（%） |
| 2 | 体重 | 80px `inputmode="decimal"`，20–300 一位小数（kg） |
| 2 | 身高 | 80px 整数 30–250（cm） |
| 2 | 疼痛评分 | 80px 整数 0–10（NRS） |

- 提交按钮（primary 96px，`:loading` + `recording` 在途守卫，双击零出网）；成功 `ElMessage.success('体征已录入')` + 表单清空 + ⑥ 体温单刷新。
- 后端 4xx 超生理极限（`NS-1005`）由拦截器弹 detail 原文（「体温超出生理极限」）——前端只透传不转译（与 PR-5 OP-100x 映射差异：NS 域后端 detail 已是中文业务口径）。

**待复核列表**（`GET /vital-signs/pending-review?wardId=`）：

- el-table 高密度（`fuy-dense`），列：时点(140px)/体温(90px)/脉搏(80px)/呼吸(80px)/血压(110px)/来源(80px：IoT/一体机)/质量(90px)/操作(140px：确认/驳回)。
- 确认 `POST /vital-signs/{id}/confirm`、驳回 `.../reject`（中档：ElMessageBox.confirm 带回显「确认将 09-23 08:00 体温 37.8 入体温单？」）；确认成功该行移除（fuy-flip leave，spec 断言语义）。
- 空态：`el-empty description="暂无待复核体征"`。

### 3.8 区块⑥：体温单渲染区 + 特殊事件录入

渲染规范全文见 §5（本文最高优先级章）。区块结构：

```
el-card ⑥
├── [切换条 40px] 患者（选中回显）+ 月页切换（上月/下月 chevron 按钮 + 当前月「2026-09」.fuy-num）
│   + 图例（×腋温 ●口温 〇肛温 ·红点脉率，12px，静态常驻——符号语义自助解读）
├── SVG 曲线区（横向滚动容器：默认视窗约 4 天、整月横滚；高 280px + 刻度行）
├── 日行值底栏（表格行：日期/住院天数/手术后天数/大便次数/入量/出量/体重/身高）
└── [特殊事件录入行 40px] 事件类型 el-select 160px（入院/手术/分娩/转科/出院/死亡/物理降温/
    脉搏短绌起/脉搏短绌止/呼吸心跳停止）+ 时点 el-time-select 120px +「记录」按钮
```

- 未选患者：整卡空态 `el-empty description="从床位卡墙选择患者查看体温单"`。
- 月页切换与患者切换：`content-fade` 200ms 显隐（曲线不做位移动画，防趋势误读）。

### 3.9 区块⑦：护理评估

- 三段流（卡内纵向）：
  1. **量表选择**：el-select 200px（`GET /assessment-scales`：Braden 压疮/Morse 跌倒/NRS 疼痛/Barthel 自理/MEWS 早期预警）。
  2. **条目打分表**：每条目一行（条目名 14px + `el-radio-group` 选项组，选项 label「分值 - 描述」13px；量表条目与选项定义全部来自 GET 响应，前端零内置量表）。
  3. **结果条**（提交后出现，`content-fade` 200ms）：总分（24px `.fuy-num`/700）+ 判级 tag（HIGH=danger「高风险」/MEDIUM=warning「中风险」/LOW=success「低风险」）。
- **高危红标契约**（spec 断言冻结）：riskLevel=HIGH 时结果条容器挂类 `.fuy-assess-result--high`（红描边 1px `--fuy-color-danger-text` + 底 `--fuy-palette-red-700` 8% 透明度 `rgba(185,28,28,0.08)`——唯一允许的底色透明字面量）+ 「高风险」文案。
- 提交 `POST /assessments`（`:loading` + 守卫；未选患者/未选量表/存在未答条目时前置拦截零出网）。
- 历史评估：卡底部「最近评估」行（量表名 + 总分 + 判级 + 时点，13px，`GET /assessments?visitId=&scaleType=`）。

### 3.10 区块⑧：任务列表 + 交接班双签

**任务列表**（`GET /tasks?wardId=&status=&date=`，默认当日全状态）：

- el-table 高密度（`fuy-dense`），列：任务号(120px)/类型(90px：给药/输液/翻身/巡视…)/患者(140px：床位+姓名)/计划时间(140px)/责任护士(90px)/状态(90px tag)/操作(120px：完成/取消)。
- 状态 tag 映射（沿用 PR-5 §4.3 映射法）：PENDING=info「待执行」、IN_PROGRESS=primary「执行中」、COMPLETED=success+aa「已完成」、CANCELLED=info+strike「已取消」。
- **逾期契约**（spec 断言冻结）：`overdueFlag=true` 时该行挂类 `.fuy-task-overdue`（行内左侧 3px 红条 + 计划时间文字转 `--fuy-color-danger-text`）+ 「逾期」danger tag；逾期为动作标记不改状态（M05 Spec §5 状态机：仍可完成）。
- 完成 `POST /tasks/{no}/complete`（中档确认 + 回显「完成任务 T20260923001（03-01 张三 翻身）？」）；取消 `.../cancel`（中档确认 + 必填原因 prompt）。
- 分页：50 行/页。
- 空态：`el-empty description="当前病区暂无护理任务"`。

**交接班双签**（挂任务列表下方）：

- 「生成交接班」按钮（primary，`POST /handovers/generate`，`:loading` + 守卫）→ 生成后展示：患者摘要行（总数/病危/病重/新入/手术/转出/今日出院，13px `.fuy-num`）+ SBAR 四段（现状/背景/评估/建议，每段小节题 `.fuy-section-title` 12px + 内容 13px，自动汇总只读）+ 待续事项计数。
- **双签按钮契约**（spec 断言冻结）：状态 DRAFT → 「完成交接」按钮可点（primary，中档 ElMessageBox.confirm 带回显「交班 李护士 → 接班 王护士，确认完成交接？」，`POST /handovers/{no}/complete`）；状态 COMPLETED → 按钮置灰 `disabled` + 文案「已完成交接」。
- 未生成时空态：「点击生成本班交接班材料（SBAR 自动汇总）」。

### 3.11 护理域状态标签语义映射汇总（唯一映射表）

| 业务态 | 呈现 | 类/色 |
| --- | --- | --- |
| 护理级别 特级/Ⅰ/Ⅱ/Ⅲ | `.fuy-nursing-level-badge--special/l1/l2/l3` 实底白字（特级护理/一级/二级/三级） | §2.3 |
| 病情 危/重/新/术/娩 | `.fuy-nursing-flag--danger/warning/brand/info/brand`（娩归 brand 系） | §2.3 |
| 过敏 | 「敏」`.fuy-nursing-flag--danger` 实底 | §2.3 |
| 风险 跌倒/压疮/管路 | 「跌」「压」「管」`.fuy-nursing-flag--outline` 红描边 | §2.3 |
| 转出/今日出院 | 「转」「出」`.fuy-nursing-flag--muted` | §2.3 |
| 体征复核 PENDING_REVIEW | warning tag「待复核」 | PR-5 §4.3 映射法 |
| 评估风险 HIGH/MEDIUM/LOW | danger「高风险」/warning「中风险」/success「低风险」+ 容器 `.fuy-assess-result--high`（仅 HIGH） | §3.9 |
| 任务逾期 | 行 `.fuy-task-overdue` +「逾期」danger tag | §3.10 |
| 交接班 DRAFT/COMPLETED | 「完成交接」可点 / disabled「已完成交接」 | §3.10 |

---

## 4. PDA 页移动布局

### 4.1 路由与外框

- 路由：**顶层**，`path: '/pda'`、`name: 'pda'`、`meta: { permission: 'nursing:pda:use' }`（语义登记，403 接线 P-later 注记——patient 三页先例），在 MainLayout **之外**（照 `/login` 路由形态：懒加载、非 public 默认受保护）。
- 外框（scoped 类 `.pda-page`）：

```css
.pda-page {
  max-width: var(--fuy-pda-page-width);  /* 480px 自持移动布局 */
  margin: 0 auto;
  min-height: 100dvh;
  padding: var(--fuy-space-4);
  background: var(--fuy-palette-gray-50);
  font-size: var(--fuy-pda-font-base);   /* 16px 字号基线 */
  overscroll-behavior: contain;          /* 防误触下拉刷新（技能检索命中规则） */
}
```

- **不用 EP 组件**（PdaView 内零 Element Plus 组件）：EP 默认 32px 控件高不满足 48px 触控，覆盖 `--el-*` 面大得不偿失；PDA 页全部原生控件 + scoped CSS（照 PR-5 §4.4 portal 原生基线）。`ElMessage` 允许使用（顶部 toast，样式手动引入，PR-5 既有口径）。

### 4.2 页面流四段（纵向单列，逐段解锁）

```
.pda-page
├── [页头 48px] 「富云移动护理」16px/600 + 当前护士名（13px）
├── 第 1 段卡「患者识别」：输入框（自动聚焦 autofocus；扫码枪即键盘输入，
│   @keyup.enter 直接触发校验）+「查询」按钮（全宽 48px 高）
│   校验规则：/^I\d{13}$/（I 型 14 位腕带住院号）或 /^\d{8,}$/（患者卡号）二选一；
│   非法 → 贴字段红字「腕带号应为 I 开头 14 位，或 8 位以上数字卡号」+ 零出网（spec 冻结）
├── 第 2 段卡「患者卡」（校验成功后展开，未完成第 1 段时 60% 透明度 +「先完成患者识别」副文案）：
│   姓名（脱敏姓*名 / **，18px/600）+ 性别/年龄 + 在区床位（.fuy-num）+ 护理级别徽标
│   + 过敏标识（红「敏」+ 过敏源）；**超敏字段零渲染**——无证件号/手机号/住址字段（spec 冻结：
│   DOM 不含「手机」「证件」文案；数据源为脱敏摘要 GET /pda/patient-summary）
├── 第 3 段卡「体征录入」：体温+部位 / 脉搏 / 呼吸 / 血压（收缩/舒张）/ 血氧 —— 五字段子集
│   （体重/身高/疼痛归护士站页，PDA 只保留巡床高频面）；每字段 48px 高输入框、
│   数字字段 inputmode="numeric"；校验规则与 §3.7 同口径；提交按钮全宽 48px（:loading 在途守卫）
└── 第 4 段卡「巡视打卡」：「巡视打卡」全宽按钮（48px 高，primary 底白字）
    成功后按钮转已完成态（绿底白字「已巡视 ✓」+ taskNo 回显 13px .fuy-num）
    + ElMessage.success；在途守卫拦截重复点击（spec 冻结：patrol 调用一次）
```

段间解锁动画：`content-fade` 200ms（PR-5 §6.7 既有类）；reduced-motion 直达。

### 4.3 触控与输入基线（冻结）

| 项 | 值 | 依据 |
| --- | --- | --- |
| 触控目标（全部可点元素） | ≥48×48px（`--fuy-pda-touch`） | 简报冻结输入约束（Android 48dp 工效学最小值，技能检索 Result 1） |
| 相邻触控间距 | ≥8px（`--fuy-space-2`） | 技能检索 Result 3 |
| 字号基线 | 16px（正文/输入框）；13px 仅限只读辅助信息 | 简报冻结 |
| 输入框 | 高 48px、圆角 12px（`--fuy-radius-xl`）、描边 `--fuy-border-hairline`、聚焦描边 `--fuy-color-brand` + `box-shadow: 0 0 0 3px var(--fuy-color-focus-ring)` | PR-5 §4.4 portal 原生基线同款 |
| 主按钮 | 高 48px 全宽、品牌底白字、禁用态 60% 透明度 | 同上 |
| 错误文案 | 14px `--fuy-color-danger-text` 置字段正下方 + `aria-describedby` 关联 | 同上 |
| 数字键盘 | 数字字段 `inputmode="numeric"`（体温 `decimal`） | 技能检索 Result 2 |
| Tab 顺序 | = 视觉顺序（四段卡自然流） | PR-5 §5.3 portal 条款 |

### 4.4 校验与容错文案口径

- 双触发（失焦 + 提交）显式校验，错误贴字段（§4.3 表）；三处冻结文案：
  - 「腕带号应为 I 开头 14 位，或 8 位以上数字卡号，请重新扫描」
  - 「体温应为 35.0–42.0 的数值（如 36.5），请重新测量输入」（与 §3.7 同文案族）
  - 「未识别到该患者，请核对腕带或改用患者卡号」（查询 404 口径）
- 在途守卫：查询/体征提交/巡视打卡三个动作各自独立 `ref` 守卫 + 按钮 `disabled`，双击零出网。
- 患者识别成功后输入框清空并保持聚焦（连续扫下一个患者的床旁节奏）。

---

## 5. 体温单渲染规范（最高优先级——医疗安全）

> **本章地位**：体温单是病历法律文书的组成部分，符号规范失真即医疗安全问题。符号内容以 M05 Spec §2 调研依据 2（体温单绘制规则）与调研依据 6（出入量红双线）为**唯一内容权威**，**符号不得自创**；本章每条规则标注来源。X 轴时间分度、脉搏轴刻度范围等 Spec 未冻结项为本文设计裁量，逐条标注「设计裁量」并给出理由。

### 5.1 渲染技术裁决：SVG

- **SVG 而非 Canvas**：① 符号类名可被 CSS 选择器与测试框架断言（spec 机器判据 `fuy-temp-x` 等需要 DOM 类名）；② 内联 SVG 消费 CSS 变量（`stroke="var(--fuy-chart-temp-color)"` 全局换色一处生效）；③ 矢量打印友好（病历打印语义）；④ 可加 `<title>` 提供符号无障碍读法。
- 坐标计算为纯函数（`buildTempChart(entries, month)` → 符号/线段/网格描述数组），视图层仅映射渲染——spec 可对纯函数与 DOM 双层断言。
- 零图表库（不引 ECharts/SVG 库，PR-5 §7.5 零新增依赖红线）。

### 5.2 坐标系与网格

| 轴 | 分度 | 冻结值 | 来源 |
| --- | --- | --- | --- |
| Y 轴·体温 | **35.0–42.0℃，每小格 0.2℃**（35 小格），每 1℃ 粗横线，左侧刻度 35–42 | 格高 `--fuy-chart-grid-y` 8px，曲线区高 280px | **Spec 调研依据 2 原文：「体温单每小格 0.2℃」** |
| Y 轴·脉搏 | 20–160 次/分，每小格 4 次/分（35 小格与体温完全共格），右侧刻度 20/40/…/160 | 同一格高双语义（左体温右脉搏双刻度列，各 32px 宽） | 脉搏与体温共网格为纸质体温单惯例（调研依据 2「体温与脉搏重叠」规则即以共格为前提）；20–160 覆盖临床值域 + 35 格共格对齐为**设计裁量** |
| X 轴·时间 | 每小格 2 小时（每天 12 小格），每天日隔粗竖线；测量时点吸附最近小格中心（点不落格线，临床画法惯例） | 格宽 `--fuy-chart-grid-x` 16px，每天 192px，7 天视窗 1344px | 时间分度为**设计裁量**：2 小时/小格使 q1h（特级）/q2h/q4h（常规）频次均整格落点；每 4 小时辅竖线（半粗）对应护理班次节律 |
| 底部行 | 日期（dd）/ 住院天数 / 手术后天数三行文字 | 行高 20px，12px `.fuy-num` | Spec §4 temperature_chart_page（住院天数/术后天数页要素） |

- 网格线：普通线 `stroke: var(--fuy-chart-grid-color); stroke-width: 0.5`；1℃ 线与日隔线 `stroke-width: 1`；类名 `.fuy-chart-grid` / `.fuy-chart-grid--major`。
- 空床位/无数据日：网格照画（整页连续网格），符号缺席。

### 5.3 符号规范总表（**类名契约——spec 机器判据来源，冻结**）

| # | 符号 | 形态 | 类名（冻结） | 颜色 | 来源 |
| --- | --- | --- | --- | --- | --- |
| S1 | 腋温 | 叉 ×（两段 45° 线交叉） | `fuy-temp-x` | `--fuy-chart-temp-color` 蓝 | 调研依据 2：「腋温蓝叉」 |
| S2 | 口温 | 实心圆点 ● | `fuy-temp-dot` | 蓝 | 调研依据 2：「口温蓝点」 |
| S3 | 肛温 | 空心圆圈 〇 | `fuy-temp-circle` | 蓝（描边、不填充） | 调研依据 2：「肛温蓝圈」 |
| S4 | 脉率点 | 实心圆点 | `fuy-pulse-dot` | `--fuy-chart-pulse-color` 红 | 调研依据 2：「脉率红点红线相连」 |
| S5 | 脉率连线 | 相邻脉率点红色直线相连 | `fuy-pulse-line` | 红 | 同上 |
| S6 | 心率点 | 空心圆圈 | `fuy-pulse-heart-ring` | 红（描边） | 调研依据 2：「心率红圈」 |
| S7 | 体温脉搏重叠 | 先画体温符号，再于其外画红圈 | `fuy-temp-overlap-ring`（r 加大到 6px，与体温符号同心，红描边不填充） | 红 | 调研依据 2：「体温与脉搏重叠时先画体温符号再于其外画红圈」 |
| S8 | 物理降温复测体温 | 红圈（不以蓝符号画） | `fuy-temp-cooling-ring` | 红 | 调研依据 2：「物理降温 30 分钟后所测体温以红圈表示」 |
| S9 | 降温连线 | 降温前体温与复测红圈之间红**虚线**相连 | `fuy-temp-cooling-line`（stroke-dasharray: 4 3） | 红 | 调研依据 2：「以红虚线与降温前体温相连」 |
| S10 | 脉搏短绌填充 | 脉率点与心率点两曲线之间以红直线填充（短绌时段内每个时点画一条竖线段连接两值） | `fuy-temp-deficit-line`（**简报冻结类名**） | 红 | 调研依据 2：「脉搏短绌时在脉率与心率两曲线之间以红直线填充」 |
| S11 | 特殊事件竖线 | 入院/手术/分娩/转科/出院/死亡：贯穿曲线区的红竖线 | `fuy-event-line` + 修饰符 `--admission/--surgery/--delivery/--transfer/--discharge/--death` | 红 | Spec §4 special_event_type 枚举 + 基础护理惯例（事件时刻以竖线标注于体温单） |
| S12 | 呼吸心跳停止 | 红**双**竖线 | `fuy-event-line--arrest`（两条 1px 竖线，间距 2px） | 红 | Spec §4 special_event_type 含「呼吸心跳停止」；双线形态为**设计裁量**（与普通事件竖线区分终末事件） |
| S13 | 体温连线 | 相邻同部位体温点蓝色直线相连 | `fuy-temp-line` | 蓝 | 调研依据 2 体温曲线连线惯例（「脉率红点红线相连」对偶：体温蓝点蓝线相连） |
| S14 | 出入量班次小结 | 底栏出入量单元格上下红双线（border 双线） | `fuy-io-summary-rule--shift` | 红 | 调研依据 6：「各班小结与 24 小时总结需用红双线标识」 |
| S15 | 出入量 24h 总结 | 同上（加粗强调） | `fuy-io-summary-rule--24h`（border-top-width 3px double） | 红 | 同上 |

类名命名纪律：体温域 `fuy-temp-*`、脉搏域 `fuy-pulse-*`、事件域 `fuy-event-*`、出入量域 `fuy-io-*`；**实现零改名零增删**（spec 用例 7 直接断言 `fuy-temp-x` / `fuy-temp-dot` / `fuy-temp-deficit-line` 的 DOM 存在性）。

### 5.4 符号几何与样式定义（SVG，冻结精确值）

```css
/* 体温单符号（视图 scoped；符号尺寸 10px 于 16×8px 小格内留余白——格子含符号不溢出） */
.fuy-temp-x { stroke: var(--fuy-chart-temp-color); stroke-width: var(--fuy-chart-line-width); }
/* × 由两条对角线段组成：(±3.5, ±3.5) 即 7px 臂长交叉，几何在 SVG path 生成，类只控色与线宽 */
.fuy-temp-dot { fill: var(--fuy-chart-temp-color); }                       /* r 4px 实心 */
.fuy-temp-circle { fill: none; stroke: var(--fuy-chart-temp-color);
  stroke-width: var(--fuy-chart-line-width); }                             /* r 4px 空心 */
.fuy-temp-line { stroke: var(--fuy-chart-temp-color);
  stroke-width: var(--fuy-chart-line-width); fill: none; }
.fuy-pulse-dot { fill: var(--fuy-chart-pulse-color); }                     /* r 4px 实心 */
.fuy-pulse-heart-ring { fill: none; stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width); }                             /* r 4px 空心 */
.fuy-pulse-line { stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width); fill: none; }
.fuy-temp-overlap-ring { fill: none; stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width); }                             /* r 6px 同心红圈 */
.fuy-temp-cooling-ring { fill: none; stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width); }                             /* r 4px 红圈 */
.fuy-temp-cooling-line { stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width); stroke-dasharray: 4 3; fill: none; }
.fuy-temp-deficit-line { stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width); }                             /* 竖线段：脉率点 y → 心率点 y */
.fuy-event-line { stroke: var(--fuy-chart-pulse-color); stroke-width: 1; } /* 贯穿曲线区竖线 */
.fuy-event-line--arrest { stroke: var(--fuy-chart-pulse-color); stroke-width: 1; }
/* double 双线在 <3px 时退化为单线，班次 3px / 24h 总结 4px 保证双线形态可见 */
.fuy-io-summary-rule--shift { border-top: 3px double var(--fuy-chart-pulse-color); }
.fuy-io-summary-rule--24h { border-top: 4px double var(--fuy-chart-pulse-color); }
```

- 禁止在 SVG 元素上内联色值字面量（`stroke="#..."` 零出现），全部经类消费 token（§2.2 唯一例外为允许值：`--fuy-chart-grid-color` 已是字面量 token）。
- 每个数据点符号附 `<title>`（如「09-23 08:00 腋温 36.5℃」），hover 原生提示 + 屏幕阅读器可读（无障碍底线，零 JS tooltip 依赖）。

### 5.5 连线规则

1. **体温连线**（`fuy-temp-line`）：同一测量部位序列内相邻点直线相连；部位切换处（如腋温→口温）**不连线**（两符号体系各自连续，防止跨部位曲线误读——设计裁量，符合纸质惯例按体系分别画线）。
2. **脉率连线**（`fuy-pulse-line`）：相邻脉率点相连；心率圈不连线（仅当脉搏短绌时段与脉率点做 S10 填充）。
3. **物理降温虚线**（`fuy-temp-cooling-line`）：仅降温前体温点 ↔ 30 分钟后复测红圈之间画一次；复测后恢复常规蓝符号体系连线。
4. **断点**：数据缺口 ≥2 小格（4 小时）时不跨缺口连线（断线表达数据缺失，防趋势臆造——设计裁量）。

### 5.6 特殊事件竖线规则

- 事件时点吸附最近小格中心，竖线从曲线区顶（42℃ 线上方 0px）贯穿至底栏上缘。
- 线旁标注事件名（12px 红，竖排或 45° 斜排——**裁决：横排置于线右侧 4px**，竖排中文在窄格内不可读）。
- 脉搏短绌起/止是**时段事件**（Spec §4 special_event_type「脉搏短绌起止」）：起止时点各画一条红竖线，时段内每时点画 S10 填充线。
- 同格多事件：事件竖线右移半格错开（最多 2 条，更多时合并标注「多事件」——异常场景登记不展开）。

### 5.7 日行值底栏（DAILY_VALUE）

- 表格行（曲线区正下方，与 X 轴日列严格对齐）：日期 / 住院天数 / 手术后天数 / 大便次数 / 入量 / 出量 / 体重 / 身高（Spec §4 daily_value_type 枚举全集；皮试结果归 P-later 演进登记——门诊场景为主）。
- 每格一天一值（12px `.fuy-num` 居中）；出入量格的班次小结/24h 总结值挂 S14/S15 红双线（调研依据 6）。
- 体重/身高来自体征录入当日末次值（后端聚合，前端直渲）。

### 5.8 月页切换与数据映射

- `GET /temperature-charts?visitId=&month=`（month 格式 `yyyy-MM`）→ 条目列表（VITAL / SPECIAL_EVENT / DAILY_VALUE 三类，Spec §4 temperature_chart_entry）。
- 前端映射：VITAL 条目含体温值+部位 → S1–S3 + S13；脉搏/心率 → S4–S6；物理降温/短绌/事件条目 → S8–S12；日行值 → 底栏格。**符号选择由数据驱动，前端不存任何符号配置表**（部位 → 符号类名的映射常量 `TEMP_SITE_SYMBOL = { AXILLARY: 'fuy-temp-x', ORAL: 'fuy-temp-dot', RECTAL: 'fuy-temp-circle' }` 为唯一映射点，导出供 spec 断言）。
- 月页切换：上月/下月按钮（32×32 图标按钮）+ 当前月回显；越界（早于入院月）禁用。切换重渲染（`content-fade` 200ms）。

### 5.9 打印语义注记

体温单区块打印（Ctrl+P / 后续打印模板）时：横向滚动容器打印展开为整月全宽（`@media print` 内 `overflow: visible` + SVG 原始宽度）——登记为实现注记，非本 PR 交付面。

---

## 6. 交互三态与动效

### 6.1 操作反馈三态（全量复用 PR-5 §5.1）

| 态 | 呈现 | 说明 |
| --- | --- | --- |
| pending | 按钮 `:loading` + 动作 ref 守卫（`if (xxx.value) return` 先于一切 await） | 全部写操作（入区登记/体征录入/复核 confirm/reject/评估提交/任务完成取消/交接班生成完成/PDA 四动作）各自独立守卫 |
| success | `ElMessage.success('中文业务结果')` + 局部重载 | 文案含业务锚点（床位/患者/任务号） |
| fail | 拦截器统一弹错（含 NS-1005 等 detail 透传） | 组件 catch 内仅注释驻留，不重复弹错 |

### 6.2 确认分档（护理域映射 PR-5 §5.2）

| 风险档 | 护理域动作 |
| --- | --- |
| 低（直达 + loading） | 病区切换、刷新、体温单翻月、量表选择 |
| 中（ElMessageBox.confirm + 回显） | 体征复核确认/驳回、任务完成/取消（取消附原因 prompt）、交接班完成、出区前置确认 |
| 高（danger 弹窗 + 必填理由 + 回显摘要） | 出区/转区（终态变更）、入区登记（新在区记录——表单弹窗本身承载，确认按钮 primary，理由非必填：登记非终态可出区纠正，降为表单档） |

### 6.3 动效编排表（全部复用 PR-5 §6 既有类，**零新 keyframes、禁动画库**）

| 触发 | 场景 | 参数 | 来源 |
| --- | --- | --- | --- |
| mounted | 八区块卡组进场 | stagger：320ms `enter`、步长 40ms、index 0–5 封顶（卡墙/三列/体温单依 DOM 序） | PR-5 §6.1 |
| 入区/出区 | 床位卡插入/移除 | fuy-flip：enter 200ms / leave 120ms | PR-5 §6.2 |
| 待复核确认 | 该行移除 | fuy-flip-leave 120ms | PR-5 §6.2 |
| 任务/状态流转 | 状态列 tag | fuy-tag-flip 120ms out-in、scale 0.8→1 | PR-5 §6.5 |
| 床位卡选中 | 描边 + 底色 120ms transition | PR-5 号源卡选中形态（paint 级单元素反馈） | PR-5 §8.1 |
| 区块显隐 | 患者未选/已选切换、评估结果条出现、体温单翻月 | fuy-content-fade 200ms opacity | PR-5 §6.7 |
| PDA 段解锁 | 第 2/3/4 段卡展开 | fuy-content-fade 200ms | PR-5 §6.7 |
| 体温单曲线 | **无任何动画**（静态渲染；打印与法律文书语义） | — | 本文裁决 |
| reduced-motion | 全部动画直达终态 | motion.css 全局兜底零改动 | PR-5 §6.9 |

### 6.4 键盘与快捷键

- **护士站页：零页面级快捷键**（裁决：八区块焦点域复杂，全局快捷键误触风险大于收益；床位卡/表格行 Tab + Enter 原生可达）。
- **PDA 页：Enter 即提交**（输入框 `@keyup.enter` 触发患者识别——扫码枪回车形态，唯一快捷键）。
- 焦点环全站既有（`:focus-visible` 2px 品牌描边），零改动。

---

## 7. 性能红线

| 指标 | 预算 | 达成手段 |
| --- | --- | --- |
| 交互响应 | <100ms（点击→视觉反馈） | loading ref 同步置位、按钮禁用即时 |
| 床位卡墙 | ≤60 床直渲染（grid + TransitionGroup） | 单病区床位天然 ≤60；超限属异常病区规模，登记 TASK.md 不做虚拟化（PR-5 §7.2 同裁决口径） |
| 待复核列表 | ≤50 行直渲染；>50 后端分页 | 病区级队列短生命期 |
| 任务列表 | 50 行/页分页 | 服务端分页 |
| 体温单 SVG | 单月页节点 <2500（31 天 × 至多 12 点/天符号 + 网格 66 线 + 刻度） | 月页切换整块重渲染（`v-if` 月键 + content-fade）；坐标计算纯函数 `computed` 缓存 |
| 轮询/WS | **零轮询零 WebSocket**（本 PR 纯 REST，刷新显式触发） | WS 推送（`/ws/nursing`）为 bigscreen 与 P-later 演进面，护士站页不接 |
| CLS | <0.1 | 卡墙容器 min-height 240px、详情面板 200px、体征/任务卡 240px、体温单卡 420px、底栏行高锁定 |
| 动画帧率 | 60fps | 仅 transform/opacity（§6.3 全部复用类） |
| 定时器 | 零 `setInterval`（无倒计时无轮询） | — |
| 依赖 | **零新增依赖**（无动画库/图表库/手势库） | package.json diff 仅 devDependencies 零变化 |

---

## 8. 落地自查清单（机检式，逐条勾选）

实现者交付前逐条自查；「机检」列给出可执行命令口径（在 `web/` 下执行；`WS` = `web/apps/workstation/src`）。

| # | 检查项 | 机检口径 |
| --- | --- | --- |
| 1 | 符号类名契约落地：S1–S3/S10 五个冻结类名在体温单渲染代码中出现且零改名 | `grep -c "fuy-temp-x\|fuy-temp-dot\|fuy-temp-circle\|fuy-temp-deficit-line" WS/views/nursing/WardBoardView.vue` ≥ 4 |
| 2 | 契约类名全集（含本文件自定冻结项）：overlap/cooling/pulse/event/io 系列零偏差 | 对照 §5.3 表逐一眼检 + `grep -o "fuy-[a-z-]*" WardBoardView.vue \| sort -u` 与 §5.3/§3 冻结集比对 |
| 3 | 部位→符号唯一映射常量 | `TEMP_SITE_SYMBOL` 常量导出且仅此一处映射 |
| 4 | 体温单 Y 轴冻结：35.0–42.0℃ / 0.2℃ 每格 | 渲染常量 `TEMP_MIN=35 / TEMP_MAX=42 / TEMP_STEP=0.2` 存在且被坐标计算消费；spec 断言 |
| 5 | SVG 零内联色值 | `grep -c 'stroke="#\|fill="#' WardBoardView.vue` = 0 |
| 6 | tokens.css 增量零超范围 | `git diff WS/styles/tokens.css` 仅含 §2.2 清单 20 枚 token（护理级别 4 + 病情 5 + 体温单色 4 + 几何 4 + PDA 3） |
| 7 | element-plus.css 增量零超范围 | diff 仅含 §2.3 清单（nursing-level-badge / nursing-flag 两族） |
| 8 | motion.css **零 diff**（零新 keyframes） | `git diff WS/styles/motion.css` 为空 |
| 9 | 禁动画库/图表库/新增依赖 | `git diff package.json pnpm-lock.yaml` 仅零新增（或空） |
| 10 | PDA 触控基线：全部可点元素 ≥48px | PdaView scoped 内按钮/输入框 height ≥ `--fuy-pda-touch`；`grep -c "48px\|fuy-pda-touch" PdaView.vue` ≥ 4；人工抽检 devtools |
| 11 | PDA 自持布局 480px + 居中 + 16px 基线 | `.pda-page` 含 `max-width: var(--fuy-pda-page-width)` 与 `--fuy-pda-font-base` 消费 |
| 12 | PDA 超敏字段零渲染 | spec 断言：DOM 不含「手机」「证件」文案 |
| 13 | 路由契约：`/nursing/ward` 挂 MainLayout children、`/pda` 顶层 | `router/index.ts` 结构眼检 + spec |
| 14 | 高危评估容器类 `.fuy-assess-result--high`（仅 HIGH） | spec 断言 + grep |
| 15 | 任务逾期行类 `.fuy-task-overdue` + 「逾期」文案 | spec 断言 + grep |
| 16 | 交接班双签：DRAFT 可点 / COMPLETED disabled | spec 断言 |
| 17 | 表格容器全挂 `fuy-dense`、数字列全 `.fuy-num`、金额 string 零运算（护理页无金额字段——N/A 注记） | grep + 眼检 |
| 18 | 三态齐备：每写操作 loading + 守卫 + 拦截器弹错，双击零二次出网 | spec 在途守卫用例（护士站 4 条 + PDA 1 条） |
| 19 | 确认弹窗带回显摘要，禁裸「确认吗？」 | §6.2 表逐动作眼检 |
| 20 | reduced-motion：全部动效直达终态、体温单静态不受影响 | 开启系统 reduce 抽检 |
| 21 | 五连门禁全绿 + 存量 spec 零 diff | `pnpm lint && pnpm format:check && pnpm type-check && pnpm test && pnpm build` |
