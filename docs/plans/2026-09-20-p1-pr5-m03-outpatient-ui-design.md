# PR-5 M03 门诊主流程 · 三应用前端 UI 设计规范

> **文档定位**：PR-5 计划（`docs/superpowers/plans/2026-09-20-p1-pr5-m03-outpatient.md`）的伴随设计规范，Task 13/14 前端任务的**唯一视觉权威**。SDD 实现者按本文落码，taste-skill 打磨者按本文对照。
> **约束来源**：全局 `~/.zcode/AGENTS.md`（注释/日志/测试/死代码）+ `web/AGENTS.md`（组件红线/script setup/禁 any/api.d.ts 唯一来源/五连门禁）+ PR-5 计划 Global Constraints 前端三段（:58-61）。
> **设计方法论**：ui-ux-pro-max 技能（优先级规则表：无障碍 → 触控交互 → 性能 → 风格 → 布局 → 字色 → 动效 → 表单）+ design-system 三层 token 架构（primitive → semantic → component）。
> 技能检索对医疗高密度场景返回了 OLED 暗色推荐，**裁决不采纳于 workstation/portal**（白天临床环境亮色更符合长时阅读与打印截图语义），仅吸收其「高对比文本 + 可见焦点 + reduced-motion」三条硬性要求用于 bigscreen 暗色主题。

---

## 1. 设计定位与原则

### 1.1 三应用一句话定位

| 应用 | 定位 | 视觉气质 |
| --- | --- | --- |
| workstation | 医护高密度操作工作台——一屏之内完成「看队列 → 选患者 → 做动作」闭环 | 亮色、克制、信息密度优先、零装饰 |
| portal | 患者免登录自助预约——低焦虑、大字号、出错也能自己纠正 | 亮色暖中性、大留白、每屏一个主任务 |
| bigscreen | 诊区候诊叫号大屏——3-10 米外一眼看到「现在叫到谁」 | 暗色高对比、超大字号、单一视觉焦点 |

### 1.2 全局原则（四条，冲突时按序裁决）

1. **HIS 专业感**：界面是医疗业务工具，不做营销式视觉。装饰性元素（插画/渐变/玻璃拟态）零使用；层级只靠字号/字重/留白/中性灰阶表达；品牌色仅出现在主操作、选中态、焦点环三处。
2. **信息密度优先**：workstation 表格默认高密度模式（13px/22px 行高），一屏可见行数优先于呼吸感；portal 相反，密度让位于易用（正文 16px、控件高 48px）。
3. **动效克制有目的**：每个动画必须回答「它传达了什么业务事实」——进场动画传达区域次序、FLIP 传达队列变动、叫号脉冲传达「刚叫的号」、出票微弹传达「成功放心」。无业务含义的循环动画仅允许用于加载指示（连接状态呼吸点属状态指示，允许）。
4. **无障碍底线**：正文文本对比度 ≥4.5:1（大字 ≥3:1）；键盘焦点环永远可见（禁 `outline: none`）；全部动画受 `prefers-reduced-motion` 兜底；触控目标 ≥44×44px（portal）/ ≥24×24px 鼠标目标（workstation 密度例外下限）。

---

## 2. 设计 token 系统（CSS 自定义属性，命名空间 `--fuy-*`）

### 2.1 三层架构与文件落点

采用 design-system 技能的三层结构：primitive（原始色阶）→ semantic（语义用途）→ component/scene（组件与场景）。命名空间 `--fuy-*` 与 Element Plus `--el-*` 完全隔离，只在 **workstation 的 EP 映射文件**中允许 `--fuy-* → --el-*` 的单向赋值。

**文件落点（每 app 自带 tokens，因三应用场景值不同，禁止上收 shared——shared 为纯 TS 包）：**

```
web/apps/workstation/src/styles/
├── tokens.css          # primitive + semantic（亮色）
├── element-plus.css    # :root:root 覆盖 --el-* 映射 + 高密度工具类
├── motion.css          # 动效 token + keyframes + TransitionGroup 类 + reduced-motion 兜底
└── index.css           # @import 上述三文件（main.ts 仅引此一行，置于 createApp 之前）
web/apps/portal/src/styles/
├── tokens.css          # primitive + semantic（亮色暖中性）
├── motion.css          # 同构
└── index.css
web/apps/bigscreen/src/styles/
├── tokens.css          # 暗色 primitive + semantic + :root vw 根字号
├── motion.css
└── index.css
```

既有页面零改动（token 只被显式使用 `--fuy-*` 或工具类的新页面消费），属渐进采用；三 app 的 `main.ts` 各增一行 `import './styles/index.css'`。

### 2.2 色彩系统

#### 2.2.1 primitive：品牌色阶（sky 系，亮色应用共用）

```css
:root {
  /* 品牌色阶（primitive）：临床蓝，与 Element Plus 默认 #409EFF 区分，医疗稳重感 */
  --fuy-palette-brand-50: #f0f9ff;   /* 悬停底 */
  --fuy-palette-brand-100: #e0f2fe;  /* 选中底 */
  --fuy-palette-brand-200: #bae6fd;
  --fuy-palette-brand-400: #38bdf8;  /* bigscreen 暗底品牌亮青共用值 */
  --fuy-palette-brand-600: #0284c7;  /* 链接/图标强调（白底文字用 ≥#0369a1） */
  --fuy-palette-brand-700: #0369a1;  /* 品牌主色 */
  --fuy-palette-brand-800: #075985;
  --fuy-palette-brand-900: #0c4a6e;  /* 深文本/按压 */
  --fuy-palette-gray-50: #f9fafb;
  --fuy-palette-gray-600: #4b5563;
  --fuy-palette-gray-800: #1f2937;
  --fuy-palette-red-700: #b91c1c;
  --fuy-palette-amber-700: #b45309;
  --fuy-palette-green-800: #166534;
  --fuy-palette-red-600: #dc2626;
  --fuy-palette-orange-700: #c2410c;
  --fuy-palette-blue-700: #1d4ed8;
  --fuy-palette-slate-ink: #0f172a;  /* 阴影基色 */
}
```

#### 2.2.2 semantic：语义层（workstation / portal 亮色）

```css
:root {
  /* 语义色（亮色）：文本全部满足白底 ≥4.5:1 */
  --fuy-color-brand: var(--fuy-palette-brand-700);      /* 主操作/选中 */
  --fuy-color-brand-strong: var(--fuy-palette-brand-900); /* 按压 */
  --fuy-color-text-emphasis: var(--fuy-palette-gray-800); /* 关键数字/姓名 */
  --fuy-color-text-secondary: var(--fuy-palette-gray-600); /* 次要说明 */
  --fuy-color-success-text: #1f7a33;                    /* 5.4:1，已发药/已结算 */
  --fuy-color-warning-text: var(--fuy-palette-amber-700); /* 5.0:1，待支付/待配药 */
  --fuy-color-danger-text: var(--fuy-palette-red-700);  /* 6.5:1，已退号/异常 */
  --fuy-color-info-text: var(--fuy-palette-gray-600);   /* 7.6:1，中性态 */
  --fuy-color-focus-ring: rgba(3, 105, 161, 0.35);      /* 键盘焦点环 */

  /* 诊区分级语义色（预检分诊四级，实底白字徽标场景，均 ≥4.5:1） */
  --fuy-color-triage-l1: var(--fuy-palette-red-600);     /* Ⅰ级-危 4.8:1 */
  --fuy-color-triage-l2: var(--fuy-palette-orange-700);  /* Ⅱ级-急 5.2:1 */
  --fuy-color-triage-l3: var(--fuy-palette-amber-700);   /* Ⅲ级-重 5.0:1 */
  --fuy-color-triage-l4: var(--fuy-palette-blue-700);    /* Ⅳ级-普 6.7:1 */

  /* 票据状态语义（映射见 §4.3；此处为文本/描边用色，全部复用上列 AA 达标值） */
  --fuy-color-state-waiting: var(--fuy-color-brand);
  --fuy-color-state-called: var(--fuy-color-warning-text);
  --fuy-color-state-serving: var(--fuy-color-success-text);
  --fuy-color-state-served: var(--fuy-color-info-text);
  --fuy-color-state-passed: var(--fuy-palette-orange-700);
  /* 仅限删除线弱化态（tag strike），不作正文文字色——弱化语义不承载唯一信息 */
  --fuy-color-state-cancelled: #9ca3af;
}
```

#### 2.2.3 bigscreen 暗色语义层

```css
:root {
  /* 暗色场景（bigscreen 专用）：深海军蓝底，冷色相与医疗蓝一致 */
  --fuy-screen-bg-base: #0a1a28;      /* 页面底 */
  --fuy-screen-bg-panel: #0f2536;     /* 面板底 */
  --fuy-screen-bg-elevated: #16324a;  /* 当前叫号卡底 */
  --fuy-screen-text-primary: #f0f6fa; /* 16.0:1 on bg-panel */
  --fuy-screen-text-secondary: #a8c3d8; /* 8.6:1 on bg-panel */
  --fuy-screen-brand: var(--fuy-palette-brand-400);  /* 边框/装饰线 */
  --fuy-screen-call: #fbbf24;         /* 当前叫号主色 9.4:1 on bg-elevated */
  --fuy-screen-ok: #34d399;           /* 连接正常 */
  --fuy-screen-warn: #fbbf24;         /* 连接异常复用叫号色系外的琥珀 */
  --fuy-screen-triage-l1: #f87171;
  --fuy-screen-triage-l2: #fb923c;
  --fuy-screen-triage-l3: #fbbf24;
  --fuy-screen-triage-l4: #60a5fa;
}
```

#### 2.2.4 Element Plus 主色映射（仅 workstation，`:root:root` 双写提高特异性）

按需引入的 EP base 样式在运行期注入 `:root { --el-color-primary: ... }`，与覆盖文件同特异性时依赖注入顺序（不可靠）。以 `:root:root`（特异性 0,2,0）覆盖，**无论加载顺序恒定生效**，不修改框架内部、不用 SCSS 编译期方案（web B.3-6「先 CSS 变量」条款）。功能色（success/warning/danger/info）保留 EP 默认值不动——与既有页面硬编码的 `#67c23a/#e6a23c/#f56c6c/#909399` 天然一致。

```css
/* element-plus.css：仅覆盖品牌主色梯度与必要的对比度修正 */
:root:root {
  --el-color-primary: #0369a1;
  /* light-N = 主色混入 N×10% 白（EP 官方混色公式同源计算） */
  --el-color-primary-light-3: #4f96bd;  /* hover */
  --el-color-primary-light-5: #81b4d0;
  --el-color-primary-light-7: #b3d2e3;
  --el-color-primary-light-8: #cde1ec;  /* 边框 */
  --el-color-primary-light-9: #e6f0f6;  /* 浅底 */
  --el-color-primary-dark-2: #025481;   /* active */
}
```

### 2.3 字号阶梯

**workstation（高密度，px 精确值）**：

| token | 值 | 用途 |
| --- | --- | --- |
| `--fuy-font-size-xs` | 12px | 表格脚注/时间戳 |
| `--fuy-font-size-sm` | 13px | 高密度表格正文/表头 |
| `--fuy-font-size-md` | 14px | 页面正文基线（与 EP base 一致） |
| `--fuy-font-size-lg` | 16px | 卡片标题 |
| `--fuy-font-size-xl` | 18px | 页面标题 |
| `--fuy-font-size-2xl` | 20px | 区域强调数字 |
| `--fuy-font-size-3xl` | 24px | 关键计数（候诊数/今日挂号数） |

行高：正文 1.5、标题 1.3；字重：正文 400、标题/表头 600、关键数字 700。**所有数字列与计数一律加 `.fuy-num`（`font-variant-numeric: tabular-nums`）**，防跳动。

**portal（患者端 +2px 基线）**：`--fuy-font-size-sm` 14px / `md` **16px**（正文）/ `lg` 18px / `xl` 20px（区块题）/ `2xl` 28px（页面题）/ `ticket` **24px**（出票号，700 字重）。

**bigscreen（3-10 米远距）**：以 1920×1080 为设计基准，根字号随视口线性缩放——`bigscreen/src/styles/tokens.css` 声明 `:root { font-size: clamp(12px, 0.8333vw, 34px); }`（1920 宽 = 16px，3840 宽 4K = 32px，物理字高随像素密度自动一致）。页面内字号**全部用 rem**：

| 用途 | 1080p px | rem |
| --- | --- | --- |
| 当前叫号票号 | 160px | 10rem |
| 当前叫号姓名 | 72px | 4.5rem |
| 诊区名/时钟 | 48px | 3rem |
| 页面题/区域题 | 40px | 2.5rem |
| 队列行票号 | 40px | 2.5rem |
| 队列行正文（姓名/诊室） | 28px | 1.75rem |
| 表头/状态角标 | 24px | 1.5rem |
| 连接状态小字 | 20px | 1.25rem |

### 2.4 间距系统（4px 基数）

`--fuy-space-1: 4px / 2: 8px / 3: 12px / 4: 16px / 5: 20px / 6: 24px / 8: 32px / 10: 40px / 12: 48px / 16: 64px`。

- workstation：卡内 padding `--fuy-space-4`（16px）、卡间 `--fuy-space-3`（12px）、表单行距 `--fuy-space-3`。
- portal：卡内 `--fuy-space-6`（24px）、区块间 `--fuy-space-8`（32px）——密度翻倍感。
- bigscreen：面板 padding `1.5rem`、面板间距 `1.5rem`。

### 2.5 圆角 / 阴影 / 描边

```css
:root {
  --fuy-radius-sm: 2px;    /* tag、分诊级别徽标 */
  --fuy-radius-md: 4px;    /* input/button（与 EP 默认一致，勿改） */
  --fuy-radius-lg: 8px;    /* el-card 覆盖 */
  --fuy-radius-xl: 12px;   /* el-dialog、portal 卡片 */
  --fuy-radius-full: 999px;

  --fuy-shadow-sm: 0 1px 2px rgba(15, 23, 42, 0.06);
  --fuy-shadow-md: 0 4px 12px rgba(15, 23, 42, 0.08);
  --fuy-shadow-lg: 0 8px 24px rgba(15, 23, 42, 0.12);
  --fuy-screen-glow: 0 0 24px rgba(56, 189, 248, 0.25); /* 仅当前叫号卡 */

  --fuy-border-hairline: 1px solid #e5e7eb;              /* portal/bigscreen 亮暗各自覆盖 */
  --fuy-screen-border-hairline: 1px solid #1e3a52;
}
```

阴影使用规则：workstation 卡片默认无阴影（1px 描边，密度优先），仅 hover（可点卡片）与弹层用 `sm`/`md`；portal 卡片常驻 `sm`；bigscreen 仅当前叫号卡 `glow`。

### 2.6 动效 token

```css
:root {
  /* 时长阶梯：fast=悬停/按压/微反馈；base=显隐过渡/状态翻转；slow=进场/FLIP/场景级 */
  --fuy-motion-fast: 120ms;
  --fuy-motion-base: 200ms;
  --fuy-motion-slow: 320ms;
  --fuy-motion-observe: 960ms;   /* bigscreen 叫号脉冲 = slow × 3 */

  --fuy-ease-standard: cubic-bezier(0.2, 0, 0, 1);      /* 默认：位移/尺寸 */
  --fuy-ease-enter: cubic-bezier(0.16, 1, 0.3, 1);      /* 进场减速（快出缓停） */
  --fuy-ease-exit: cubic-bezier(0.4, 0, 1, 1);          /* 离场加速 */
  --fuy-ease-emphasis: cubic-bezier(0.34, 1.56, 0.64, 1); /* 唯一过冲曲线：仅成功确认时刻 */

  --fuy-motion-stagger: 40ms;   /* 进场级联步长，最多 6 档，第 7 项起并发播放 */
}
```

选用规则（对齐技能库「easing 应匹配元素目的」条款）：**进场用 `enter`（减速）、离场用 `exit`（加速）、常规位移与 FLIP 用 `standard`、加载指示旋转用 `linear`**；`emphasis` 全站仅允许 portal 出票卡与 workstation 挂号成功两处使用。数字补间（JS）用 easeOutCubic：`1 - Math.pow(1 - t, 3)`。

---

## 3. 布局系统

### 3.1 workstation 页面骨架（三页统一外框）

沿用既有 `MainLayout`（侧栏 220px + 顶栏 56px + 内容区）不改动；三页共用外框类 `.fuy-page`：

```css
.fuy-page {
  max-width: 1600px;             /* 超 2K 屏防无限拉伸；1440 主流工作屏全宽利用 */
  margin: 0 auto;
  padding: var(--fuy-space-4);
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}
```

区域骨架延续既有 `el-row`/`el-col`（`:gutter="16"`，与 DispenseWorkbenchView 形态同源），**不引入 CSS Grid 命名区域**（避免与 EP 栅格响应断点体系双轨并行）。三页内容区断点用 EP 标准：`md ≥992` 可用（分栏折叠）、`lg ≥1200` 完整布局、`xl ≥1536` 加宽。

### 3.2 三页区域树（比例与关键尺寸）

**挂号收费联动页 RegistrationChargeView**：

```
.fuy-page
├── [页头 48px] 页面题(18px/600) + 当日挂号计数(24px/.fuy-num) + 刷新按钮
├── el-row :gutter=16 —— 上区「挂号操作」(min-height 480px)
│   ├── el-col :md=24 :lg=16 —— 左：挂号流
│   │   ├── el-card「1 选择患者」(患者检索组件，行高 40px)
│   │   ├── el-card「2 选择排班/号别」(排班日期 tab + 号源池卡阵列 3 列 grid,
│   │   │      每卡 min-height 88px：医生/时段/余号.fuy-num/状态色条 3px)
│   │   └── el-card「3 确认挂号」(摘要 descriptions + 提交按钮 96px 宽)
│   └── el-col :md=24 :lg=8 —— 右：收费联动
│       └── el-card「挂号费收费」(billing 预览/结算，金额 20px/.fuy-num，
│              待缴状态条 + 支付按钮；缴费完成态绿色对勾区)
└── el-card「当日挂号记录」(el-table 高密度模式，≤20 行/页分页)
```

**分诊台页 TriageBoardView**：

```
.fuy-page
├── [操作条 56px] 报到 visitId 输入(240px, autofocus, Enter 提交)
│   + 报到按钮(primary) + 诊区队列切换(el-select 160px) + 轮询状态点
├── el-row :gutter=16
│   ├── el-col :md=24 :lg=17 —— 左：队列快照表
│   │   └── el-card > el-table(高密度) 列序：票号(80px)/姓名(脱敏,100px)
│   │       /分诊级别徽标(64px)/优先级.fuy-num(80px)/状态 tag(96px)
│   │       /等待时长.fuy-num(96px)/操作(200px: 叫号/过号/重呼)
│   └── el-col :md=24 :lg=7 —— 右：操作详情
│       ├── el-card「票务详情」(选中行回显 descriptions)
│       └── el-card「分诊处置」(调级/转队列/二次分诊表单 + 二次确认)
```

**门诊医生站页 DoctorStationView**：

```
.fuy-page
├── [页头 48px] 「门诊医生站」+ 接诊中 visit 徽标 + 出诊医生名
├── el-row :gutter=16
│   ├── el-col :md=24 :lg=6 —— 左：候诊列表（我的队列）
│   │   └── el-card > 虚拟就绪列表(行高 56px：票号/姓名/级别徽标/等待时长，
│   │          current-row 左缘 3px 品牌色条；顶部「接诊」主按钮)
│   ├── el-col :md=24 :lg=12 —— 中：接诊工作区
│   │   ├── el-card「患者上下文」(visit descriptions 4 列)
│   │   └── el-card「在诊单据」(el-tabs：检查检验/处方引用/处置)
│   │          处方行含「已发药」镜像列(success 文本色)
│   └── el-col :md=24 :lg=6 —— 右：开立与结诊
│       ├── el-card「开检查检验单」(项目码+数量，quantity 显式数字校验)
│       ├── el-card「开处方」(复用 pharmacy createPrescription 表单)
│       └── el-card「诊毕」(去向下拉=disposition 八项 + 在途单据确认勾选
│              + M09 提醒不拦截注记文案 12px + 诊毕按钮 danger-outline)
```

### 3.3 portal 移动优先布局

```
 AppointmentView（单页三步纵向流，无路由跳转）
.fuy-portal-page (min-height 100dvh, bg #f5f7fa)
├── [页头 64px] 站点名「富云患者门户」+ 帮助电话(14px)
├── main (max-width 480px 居中；≥768px 展开为 720px 双列)
│   ├── 步骤指示条(3 圆点+连接线，当前点品牌色)
│   ├── 第 1 步卡「身份确认」：就诊卡号/证件号 二选一切换 tab
│   ├── 第 2 步卡「选择号源」：日期横滑条(7 日 chip) + 号源卡列表
│   └── 第 3 步卡「确认出票」：摘要 + 提交 → 出票卡(确认页替换态)
└── [页脚] 备案/免责 12px
```

### 3.4 bigscreen 1080p/4K 适配

rem 根字号方案（§2.3）天然适配任意分辨率；布局用 CSS Grid（bigscreen 无 EP，无栅格双轨问题）：

```
.queue-board (100dvh grid, rows: 96px 1fr 1.2fr, padding 1.5rem, gap 1.5rem)
├── header：左=诊区名(3rem/700) 右=时钟(3rem/.fuy-num) + 连接状态呼吸点
├── current-call：当前叫号卡(bg-elevated + glow)
│   ├── 「请 X 号到 Y 诊室」引导语 1.75rem
│   ├── 票号 10rem/700/.fuy-num(call 色)
│   └── 姓名(脱敏) 4.5rem/600
└── waiting-list：候诊榜（前 8 条，两列 grid 4×2）
    └── 行卡：序位徽标(1.5rem) + 票号 2.5rem/700 + 状态角标 1.25rem
```

1080p 与 4K 零媒体查询（vw 缩放覆盖）；横竖比异常（如竖屏）加 `@media (orientation: portrait)` 显示「请横屏部署」遮罩即可，不做适配。

---

## 4. 组件样式规范

### 4.1 样式组织约定（三页统一）

- 视图级样式一律 `<style scoped>` + `.fuy-` 前缀 BEM 式命名（延续 `.refund-approval-*` 惯例）；**跨页复用的定制面入 `styles/element-plus.css`（非 scoped）**，用工具类挂载（如表格容器加 `class="fuy-dense"`），禁止全局裸改 `.el-table`。
- ElMessage/ElMessageBox 在模板外使用时手动引样式：`import 'element-plus/es/components/message/style/css'`（既有口径延续）。
- workstation 禁在页面内新增 EP 组件的深度选择器超过一层（`.fuy-dense .el-table__cell` 即上限），更深的定制一律用 EP 提供的 CSS 变量。

### 4.2 el-table 高密度模式（`.fuy-dense` 工具类）

```css
/* element-plus.css —— 挂载方式：<el-table> 外层卡片或自身加 class="fuy-dense" */
.fuy-dense .el-table {
  --el-table-row-hover-bg-color: var(--fuy-palette-brand-50);
  font-size: var(--fuy-font-size-sm);            /* 13px */
}
.fuy-dense .el-table .el-table__cell {
  padding: 5px 0;                                /* 默认 8px，压行高 */
}
.fuy-dense .el-table .cell {
  padding: 0 8px;
  line-height: 22px;                             /* 13×22 密集但可读 */
}
.fuy-dense .el-table th.el-table__cell {
  background: var(--el-fill-color-light);
  font-weight: 600;
  color: var(--el-text-color-regular);
}
.fuy-num { font-variant-numeric: tabular-nums; }  /* 数字等宽，全站通用 */
```

### 4.3 状态标签语义映射（唯一映射表，实现禁止各自发挥）

**票据状态（queue_ticket.status，workstation `el-tag` size=small）：**

| 状态 | el-tag type | 附类 | 文本 |
| --- | --- | --- | --- |
| WAITING | primary | — | 候诊中 |
| CALLED | warning | `.fuy-tag-aa` | 已叫号 |
| SERVING | success | `.fuy-tag-aa` | 就诊中 |
| SERVED | info | — | 已就诊 |
| PASSED | warning | `.fuy-tag-aa fuy-tag-strike` | 已过号 |
| CANCELLED | info | `.fuy-tag-strike` | 已取消 |

**分诊级别徽标（实底白字 span，非 el-tag）：**

```css
.fuy-triage-badge {
  display: inline-block;
  min-width: 28px;
  padding: 0 8px;
  height: 22px;
  line-height: 22px;
  border-radius: var(--fuy-radius-sm);
  color: #fff;
  font-size: var(--fuy-font-size-xs);
  font-weight: 600;
  text-align: center;
}
.fuy-triage-badge--l1 { background: var(--fuy-color-triage-l1); }
.fuy-triage-badge--l2 { background: var(--fuy-color-triage-l2); }
.fuy-triage-badge--l3 { background: var(--fuy-color-triage-l3); }
.fuy-triage-badge--l4 { background: var(--fuy-color-triage-l4); }
```

**AA 修正与辅助类：**

```css
/* el-tag effect=light 的文字色默认不达 4.5:1；经 --el-tag-text-color 变量修正——
   变量定义特异性(0,2,0)高于 EP 的 .el-tag--warning(0,1,0)，不依赖按需样式注入顺序 */
.fuy-tag-aa.el-tag--warning { --el-tag-text-color: var(--fuy-color-warning-text); }
.fuy-tag-aa.el-tag--success { --el-tag-text-color: var(--fuy-color-success-text); }
.fuy-tag-aa.el-tag--danger  { --el-tag-text-color: var(--fuy-color-danger-text); }
.fuy-tag-strike { text-decoration: line-through; opacity: 0.75; }
```

**visit 状态**沿用映射法：REGISTERED=info「已挂号」、WAITING=primary「候诊中」、IN_CONSULT=success「接诊中」、PENDING_FEE=warning「待缴费」、FINISHED=info「已完成」、CANCELLED/DISCLAIMED 灰删除线。

### 4.4 el-form 诊疗表单 / el-dialog / 空态 / 骨架屏

- **诊疗表单**：统一 `label-position="right"` `label-width="96px"`（分诊处置）/`"110px"`（开单），size 默认（32px 控件高，高密度不用 small 保证点击域）；quantity 用 `el-input-number :min="1" :step="1"` 且 `@change` 内显式 `Number.isInteger` 校验，非法置回并 4xx 口径提示（W-22⑦ 禁裸 parse）。
- **操作确认 el-dialog**：宽度 420px 固定；标题 16px/600；正文 14px；`destroy-on-close`；危险动作（诊毕、停诊类）确认按钮 `type="danger" plain`；确认弹窗内含**回显摘要**（如「即将为 A007 张* 调至 Ⅲ 级」），禁止裸「确认吗？」。
- **空态**：`<el-empty :image-size="72" description="...">`，description 写业务口径（如「今日候诊队列为空」而非「暂无数据」）。
- **骨架屏**：首屏数据加载用 `<el-skeleton :rows="4" animated />` 承载表格区（表格渲染前）；表格刷新（已有数据）一律 `v-loading`，不用骨架防闪。
- **portal 原生控件基线**（portal 无 EP）：input 高 48px/圆角 12px/字号 16px/描边 `--fuy-border-hairline`，聚焦描边 `var(--fuy-color-brand)` + `box-shadow: 0 0 0 3px var(--fuy-color-focus-ring)`；按钮高 48px/主按钮品牌底白字；错误文案 14px `var(--fuy-color-danger-text)` 置于字段正下方并以 `aria-describedby` 关联。
- **bigscreen 原生暗色基线**：面板 `--fuy-screen-bg-panel` + `--fuy-screen-border-hairline` 圆角 8px；文本两档灰禁低于 secondary。

---

## 5. 交互规范

### 5.1 操作反馈三态统一模式

| 态 | 呈现 | 实现 | 时长 |
| --- | --- | --- | --- |
| pending | 按钮 `:loading` + 同步在途守卫 ref 置位（`if (loading) return` 先于一切 await，双击零出网——延续 `rejecting`/`executing` 形态） | 组件 ref | 请求实际耗时 |
| success | `ElMessage.success('中文业务结果')` + 列表重刷/局部态更新 | 全局 | 3000ms 自动关闭（EP 默认） |
| fail | 响应拦截器统一弹错（组件不重复 catch 弹错，catch 内仅注释驻留策略） | api/http.ts | 3000ms |

禁止：alert/confirm 原生弹窗、双重弹错、静默吞错无注释。

### 5.2 行内编辑与确认模式（按风险分档）

| 动作风险 | 模式 | 例 |
| --- | --- | --- |
| 低（可逆/纯展示刷新） | 单击直达，按钮在途 loading | 叫号、刷新队列、查询快照 |
| 中（改状态可再纠正） | 行内触发 → `ElMessageBox.confirm` 带回显摘要 | 调级、过号、重呼、报到纠错 |
| 高（终态/跨资金） | 弹窗表单 + 必填理由 + danger 确认按钮 | 诊毕、退号退费联动、转队列跨诊区 |

### 5.3 键盘焦点与快捷操作

- **焦点环**（全站强制，`styles/motion.css` 通用段）：

```css
:focus-visible {
  outline: 2px solid var(--fuy-color-brand);
  outline-offset: 2px;
}
/* 仅 outline（现代浏览器自动跟随元素圆角）；禁止在此设 border-radius——会改元素本身形状 */
```

- **快捷键（workstation 两页，共 4 个，不贪多）**：

| 页面 | 键 | 动作 | 实现 |
| --- | --- | --- | --- |
| 分诊台 | Enter（报到框内） | 提交报到 | `@keyup.enter` |
| 分诊台 | `Alt+R` | 叫出队首 | `onMounted` 注册 `keydown`，`onUnmounted` 移除 |
| 医生站 | ↑ / ↓ | 候诊列表行移动 | 列表容器 `tabindex="0"` + keydown |
| 医生站 | Enter（列表聚焦时） | 接诊选中患者 | 同上 |

快捷键以 `kbd` 样式呈现于按钮 tooltip：`<kbd>` 底 `var(--el-fill-color)` 圆角 2px 12px 字号。输入框聚焦时快捷键让位（监听器判 `event.target` 是否 input）。

- **portal**：不做键盘快捷层，但保证整页 Tab 顺序 = 视觉顺序，出票卡出现后焦点移动到出票卡（`tabindex="-1"` + `.focus()`），错误时焦点移到第一个错误字段。

### 5.4 portal 表单分步与容错文案口径

- 三步纵流不锁死前进：未完成第 1 步时第 2/3 步卡呈 60% 透明度 + 「先完成上一步」副文案（可预览不可操作），完成后自动展开下一步（`scrollIntoView({ behavior: 'smooth', block: 'start' })`，reduced-motion 时 `auto`）。
- **显式格式校验（提交前 + 失焦时双触发）**：证件号 `/^\d{17}[\dXx]$/`（18 位含尾 X，统一转大写）；就诊卡号非空且 `>=8` 字符纯数字。错误文案模板（贴近字段 + 指明纠正方法）：
  - 「身份证号应为 18 位数字（末位可为 X），请核对后重新输入」
  - 「就诊卡号应为 8 位以上数字，请查看就诊卡正面」
  - 「该号源刚被约满，请选择其他时段」（OP-1003 转译）；「该证件存在未完成缴费的挂号，请先完成缴费」（OP-1006/1007 转译）——后端 4xx `errorCode` → 文案映射表放 `api/outpatient.ts` 导出常量，禁组件内散写。
- 出票后展示：apptNo（24px/700/.fuy-num）+ 支付时限倒计时（`mm:ss`，`.fuy-num`，剩 5 分钟内转 `--fuy-color-warning-text`），倒计时用 1 个 `setInterval` 且出票卡卸载时 `clearInterval`。

---

## 6. 动画规范（逐场景精确参数）

> 通用铁律：**动画属性仅 `transform` 与 `opacity`**（叠加层用子元素/伪元素 opacity 承载底色脉冲）；所有 keyframes 与过渡受 §6.9 reduced-motion 兜底；`will-change` 仅允许出现在 bigscreen 当前叫号卡与 FLIP 列表容器两处，动画结束即由浏览器回收。

### 6.1 页面/卡片进场（stagger）

```css
/* motion.css */
.fuy-stagger > * {
  animation: fuy-rise var(--fuy-motion-slow) var(--fuy-ease-enter) both;
  animation-delay: calc(var(--fuy-stagger-index, 0) * var(--fuy-motion-stagger));
}
@keyframes fuy-rise {
  from { opacity: 0; transform: translateY(8px); }
  to   { opacity: 1; transform: translateY(0); }
}
```

- 属性 `opacity+transform`；时长 320ms；缓动 `enter`；触发：组件 mounted（CSS 动画自动播放，无需 JS）；stagger 步长 40ms，子元素模板内联 `:style="{ '--fuy-stagger-index': i }"`（i 为渲染序，≤5 封顶，第 6 项起恒为 5 并发）。三页主区域（el-col 卡组）与 portal 三步卡使用。

### 6.2 列表项增删（FLIP 选型：Vue 内建 TransitionGroup）

**选型裁决**：不手写 FLIP、不引库——`<TransitionGroup>` 的 move class 即 FLIP（transform 反演播放），零依赖且与 Vue 渲染周期天然同步；手写方案需测量两次布局（强制 reflow），与 60fps 预算冲突。

```css
/* motion.css —— 用法：<TransitionGroup name="fuy-flip" tag="tbody"> 或列表容器 */
.fuy-flip-move {
  transition: transform var(--fuy-motion-slow) var(--fuy-ease-standard);
}
.fuy-flip-enter-active {
  transition: opacity var(--fuy-motion-base) var(--fuy-ease-enter),
    transform var(--fuy-motion-base) var(--fuy-ease-enter);
}
.fuy-flip-enter-from { opacity: 0; transform: translateY(-8px); }
.fuy-flip-leave-active {
  position: absolute;              /* 脱流使 move 平滑（TransitionGroup 要求） */
  transition: opacity var(--fuy-motion-fast) var(--fuy-ease-exit),
    transform var(--fuy-motion-fast) var(--fuy-ease-exit);
}
.fuy-flip-leave-to { opacity: 0; transform: translateY(8px); }
```

- 进入 200ms / 移位 320ms / 离场 120ms（离场快于进场——技能库「exit-faster-than-enter」条款）；仅 transform/opacity。适用：分诊台快照表行、医生站候诊列表、bigscreen 候诊榜。**REST 5s 轮询刷新禁止整表重挂**：key 用 `ticketId` 稳定值，数据 merge 更新（不变行走 move 之外的零动画路径），只有真实增删才触发动画。

### 6.3 叫号跳前高亮（bigscreen 核心动画，逐帧）

触发条件：WS 帧 `{type:"CALLED", ticketNo, ...}` 到达且 `ticketNo !== 前值`；实现为当前叫号卡内容 `:key="ticketNo"` 重挂，触发三段编排：

| 阶段 | 0→320ms 入场 | 320→1280ms 身份脉冲 | 0→320ms 票号强调 |
| --- | --- | --- | --- |
| 元素 | 卡片本体 | `::after` 叠加层（底色 `--fuy-screen-call`，动画其 opacity） | 票号文本 |
| 属性 | `transform+opacity` | 仅 `opacity` | 仅 `transform` |
| 关键帧 | `from: translateY(-16px) scale(0.96) opacity 0` → `to: none/1` | `0%{opacity:.9} 33%{opacity:.1} 66%{opacity:.6} 100%{opacity:0}`（两次明暗波） | `scale 1→1.08→1` |
| 时长/缓动 | 320ms `enter` | 960ms（observe）线性分段 | 320ms `standard` |

```css
.fuy-call-card { will-change: transform, opacity; position: relative; overflow: hidden; }
.fuy-call-card::after {
  content: '';
  position: absolute;
  inset: 0;
  background: var(--fuy-screen-call);
  opacity: 0;
  animation: fuy-call-flash var(--fuy-motion-observe) linear both;
  pointer-events: none;
}
@keyframes fuy-call-flash {
  0% { opacity: 0.9; }
  33% { opacity: 0.1; }
  66% { opacity: 0.6; }
  100% { opacity: 0; }
}
.fuy-call-no { animation: fuy-call-pop var(--fuy-motion-slow) var(--fuy-ease-standard) both; }
@keyframes fuy-call-pop {
  0% { transform: scale(1); }
  60% { transform: scale(1.08); }
  100% { transform: scale(1); }
}
```

降级：reduced-motion 下三段全部禁用（§6.9 兜底覆盖），票号直接切换为终态（`::after` 恒 opacity 0、无位移）。语音播报为现场外设，软件侧仅保证视觉时序即「帧到达 → 立即入场」，不做人为延迟。

### 6.4 数字滚动（候诊数）

- 方案：`requestAnimationFrame` JS 补间 600ms，easeOutCubic；目标值写入前 `Math.round`；**仅数字内容变更时启动**，补间中再次变更则从当前值续算。
- 约束：容器加 `.fuy-num`（tabular-nums）防宽度抖动（textContent 变更不触发 layout 的前提是占宽稳定）；补间 rAF 句柄存 ref，`onUnmounted` 取消。
- 降级：reduced-motion 或 `document.hidden` 时直接赋终值。
- 适用：workstation 页头当日计数、bigscreen 候诊总数（1 处）；表格内数字**不做滚动**（密度与性能）。

### 6.5 状态标签流转

```css
.fuy-tag-flip-enter-active { transition: opacity var(--fuy-motion-fast) var(--fuy-ease-enter),
  transform var(--fuy-motion-fast) var(--fuy-ease-enter); }
.fuy-tag-flip-leave-active { transition: opacity var(--fuy-motion-fast) var(--fuy-ease-exit); position: absolute; }
.fuy-tag-flip-enter-from { opacity: 0; transform: scale(0.8); }
.fuy-tag-flip-leave-to { opacity: 0; }
```

`<Transition name="fuy-tag-flip" mode="out-in">` 包裹 el-tag，`:key="row.status"`；120ms 进 / 120ms 出，scale 0.8→1。仅用于轮询推送导致状态列变化的单元格。

### 6.6 弹窗与抽屉过渡

**不覆盖 EP 内建过渡**（el-dialog/el-drawer/el-message-box 自带 300ms 淡入缩放，属合格基线，重定义徒增维护面）。仅两处补充：

- dialog 关闭后焦点归还触发按钮：`@closed` 事件内 `triggerButtonRef.value?.focus()`（无障碍焦点管理，非动效）。
- 医生站开单子表单展开收起：`grid-template-rows 0fr→1fr` 或 `max-height` 均触发 layout——**改用内容容器 `v-show` + Transition `opacity+transform: scaleY`，`transform-origin: top`，200ms standard**。

### 6.7 骨架屏 → 内容切换

```css
.fuy-content-fade-enter-active { transition: opacity var(--fuy-motion-base) var(--fuy-ease-enter); }
.fuy-content-fade-enter-from { opacity: 0; }
```

`<Transition name="fuy-content-fade">` + `v-if="!loading"`，200ms opacity 单属性；骨架与内容不做交叉溶解（占位高度差用 `min-height` 锁定防 CLS，表格区 min-height 240px）。

### 6.8 portal 出票卡（成功确认时刻）

`<Transition name="fuy-ticket">` 包裹出票卡（v-if 替换表单区）：`from { opacity: 0; transform: scale(0.92) }` → 终态，320ms `--fuy-ease-emphasis`（全站唯一过冲曲线之二，另一处为 workstation 挂号成功 toast 卡）。配套：出票卡顶部 3px 品牌色条从 0→100% 宽度（`transform: scaleX(0)→1`，`transform-origin: left`，480ms standard 延迟 160ms 启动，制造「打印出票」隐喻）。

### 6.9 reduced-motion 全局兜底（三 app motion.css 尾部同款）

```css
@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
    scroll-behavior: auto !important;
  }
  /* 唯一豁免类：加载指示（状态语义，停转=卡死误判）。豁免元素降速不清除 */
  .fuy-loading-essential,
  .fuy-loading-essential::before,
  .fuy-loading-essential::after {
    animation-duration: 1.5s !important;
    animation-iteration-count: infinite !important;
  }
}
```

v-loading 挂载的遮罩 spinner 由 EP 内部类渲染，无法逐个加豁免类——**裁决**：reduce 下 v-loading spinner 静止但遮罩与「加载中」文本仍在，语义不丢失，接受该形态；`.fuy-loading-essential` 仅用于 bigscreen 连接状态呼吸点与 portal 提交按钮内置 spinner。

---

## 7. 性能红线与实施指引

### 7.1 渲染预算

| 指标 | 预算 | 达成手段 |
| --- | --- | --- |
| 交互响应（点击→视觉反馈） | <100ms | loading ref 同步置位（非 await 后）；按钮禁用态即时 |
| 动画帧率 | 60fps（帧预算 16.7ms） | 仅 transform/opacity；禁 width/height/top/left 动画；叠加层脉冲用伪元素 opacity |
| 轮询刷新（分诊台 5s） | 无整表闪烁 | §6.2 稳定 key + merge 更新；隐藏页 `visibilityState` 暂停轮询 |
| WS 推送端到端 | ≤2s（Spec :198） | 大屏订阅直驱；不做额外节流 |
| 首屏 CLS | <0.1 | 表格/卡片 min-height 锁定；`.fuy-num` 防数字宽度跳动 |

### 7.2 虚拟滚动阈值（候诊列表可增长）

| 场景 | 阈值 | 方案 |
| --- | --- | --- |
| 分诊台队列快照 | ≤200 行 el-table 高密度；>200 或预估超（大诊区高峰） | 服务端 `status=WAITING` 过滤 + 分页（page size 50）；本 PR 不引 el-table-v2（避免为未达阈值场景付复杂度），阈值超限属 P2 演进登记 |
| 医生站候诊列表 | 单医生队列天然 <50 | 直渲染 + TransitionGroup |
| bigscreen 候诊榜 | 固定前 8 条 | WS 快照数组 `slice(0, 8)`，不入 VirtualList |

### 7.3 Element Plus 按需引入现状核实（结论）

`workstation/vite.config.ts` 已配置 `unplugin-vue-components + unplugin-auto-import + ElementPlusResolver`（组件与样式按需联动）；`ElMessage/ElMessageBox` 模板外使用处手动 `import 'element-plus/es/components/message/style/css'`。**三页零新增全量引入，新增组件（el-skeleton/el-empty/el-tabs 等）由 resolver 自动按需，无需改 vite 配置。** portal/bigscreen 不装 EP（B.2-8 依赖圈定），其 UI 全部原生 + scoped CSS + tokens。

### 7.4 主题定制落点

- 主色与梯度：`web/apps/workstation/src/styles/element-plus.css`（`:root:root` 双写，§2.2.4）。
- `main.ts` 引入顺序：`import './styles/index.css'` 置于 `createApp` 之前；index.css 内部顺序 tokens → element-plus → motion。
- 禁止：修改 EP 主题 SCSS 编译（B.3-6 先 CSS 变量条款）；引入 tailwind/unocss（零新增依赖红线）。

### 7.5 bigscreen 长时值守无泄漏设计

- WS：复用 `useIotStomp` 范式（单例 Client、库内建重连 10s、订阅句柄 onUnmounted 退订、onStompError/onWebSocketClose 置断开态）——`useQueueStomp` 镜像改 `/ws/outpatient` 与 `/topic/outpatient/queue/{deptCode}`。
- 定时器：页面内**禁止** `setInterval` 常驻（时钟用 1 个 1s interval，句柄 onUnmounted 清理；除此之外无第二定时器）；轮询逻辑零使用。
- 动画：常驻动画仅连接状态呼吸点（CSS `animation: opacity 1→.4 alternate infinite 1.2s linear`，合成层零 JS）；叫号脉冲 `both` 播完即止不驻留合成层。
- ECharts：本页零图表不引入；如后续演进加图，遵循 B.3-5 init/dispose 生命周期。

### 7.6 实施者 self-check 清单（SDD 实现与 taste-skill 打磨共同对照）

1. 所有颜色引用 `var(--fuy-*)` 或 EP 变量，组件内零裸 hex（既有 bigscreen 硬编码 hex 不在本次改动面，不动）。
2. workstation 新页表格容器已挂 `fuy-dense`；数字列全部 `.fuy-num`。
3. 状态标签/级别徽标取值来自 §4.3 映射表，无自造色。
4. 每个动画属性仅 transform/opacity；叠加层脉冲在伪元素 opacity 上。
5. 每页验证 `prefers-reduced-motion: reduce` 下：进场直接终态、叫号卡无闪烁、出票无过冲。
6. 键盘可完整走通主流程（报到→调级→叫号；候诊→接诊→诊毕；portal 三步提交），焦点环可见。
7. 对比度抽检：状态文本色、级别徽标、bigscreen 两档灰与叫号色（本文已给出计算值）。
8. 动作按钮三态齐全（loading/禁用守卫/错误驻留），双击不产生第二次出网。
9. `<script setup lang="ts">` 零例外、零 any、props 泛型化、v-for 稳定 key；api.d.ts 生成物为类型唯一来源。
10. 五连门禁通过：`pnpm lint && pnpm format:check && pnpm type-check && pnpm test && pnpm build`；新页面 spec 至少含渲染断言/动作在途守卫断言/零出网断言（W-22⑥⑦ 合规形态自带）；金额 string 透传零运算（A.3-6）。

---

## 8. 三应用页面级设计说明

### 8.1 workstation · 挂号收费联动页（RegistrationChargeView）

- **布局**：§3.2 结构树。视觉重心在左列三步卡（1 选择患者 → 2 选排班/号别 → 3 确认），步骤卡编号用 22px 圆形序标（品牌底白字），完成态转 success 底。
- **核心交互流**：选患者（检索组件行点击回填）→ 排班日期切换（号源卡阵列刷新，el-skeleton 过渡）→ 点号源卡（选中：卡描边转品牌 2px + 底 `--fuy-palette-brand-100`）→ 提交挂号（loading → 成功 ElMessage + 右列收费面板自动带出 visit 挂号费待缴行）→ 右列预览/结算（复用 billing api）→ 缴费完成右列转绿色完成态（对勾图标 + 金额）+ 下部记录表插入新行（fuy-flip enter）。
- **动效编排表**：

| 触发 | 场景 | 参数（属性/时长/缓动） |
| --- | --- | --- |
| mounted | 三卡与右列 stagger | §6.1，index 0-3 |
| 号源刷新 | 卡阵列骨架→内容 | §6.7，200ms opacity |
| 点选号源 | 选中态 | 描边+底色 120ms `fast` transition（paint 级单元素状态反馈，不属动画系统、不位移不缩放；**限定：此类色值过渡仅允许 hover/选中单元素反馈，禁止用于列表批量元素或循环场景**） |
| 挂号成功 | 右列待缴行入场 | fuy-flip-enter，200ms |
| 缴费完成 | 完成态对勾 | `scale 0.6→1` 240ms `emphasis`（全站允许的两处之一） |

- **关键样式**：号源卡 `min-height 88px` 内含余号数字（`--fuy-color-text-emphasis` 20px）与状态色条（余 0=danger 3px 左条 + 卡体 60% 透明禁点；余 ≤5=warning 条 + 「紧张」角标 12px）。

### 8.2 workstation · 分诊台页（TriageBoardView）

- **布局**：§3.2 结构树；操作条常驻页顶（轮询状态点：绿=正常刷新、灰=暂停（页面隐藏）、红=上次刷新失败，点旁文字 12px「每 5 秒自动刷新」）。
- **核心交互流**：输入 visitId（autofocus，Enter 或按钮）→ 显式格式校验（visit_id 形态 `O+yyyyMMdd+5 位流水` 前端预检格式、空值拦截）→ 报到成功 → 队列表顶插入新行（fuy-flip）→ 点行选中 → 右列详情/调级（弹窗带回显摘要 + 理由必填）→ 调级成功该行 FLIP 移动到新优先级位置（`fuy-flip-move` 传达「重排了」）→ 叫号（单击直达）→ 行状态 tag 流转（§6.5）；过号后行 PASSED + 移回队列（降级分重排，FLIP 同语义）。
- **动效编排表**：

| 触发 | 场景 | 参数 |
| --- | --- | --- |
| 轮询 merge | 行增删/移位 | §6.2（仅真实增删触发） |
| 状态变更 | 状态列 tag | §6.5，120ms out-in |
| 调级确认 | 行 FLIP 重排 | move 320ms standard |
| 报到成功 | 新行入场 | enter 200ms translateY(-8px) |

- **关键样式**：等待时长列 ≥30 分钟文字转 `--fuy-color-warning-text`（超时预警，无动画）；操作列按钮 size=small 间距 8px。

### 8.3 workstation · 门诊医生站页（DoctorStationView）

- **布局**：§3.2 结构树三栏；中列患者上下文卡头部含大号 visit 标识与级别徽标，是页面锚点。
- **核心交互流**：左列候诊列表 ↑↓ 或点击选中 → 「接诊」（confirm 带患者摘要）→ 中列加载患者上下文（visit 详情 + 在诊单据 tabs）→ 右列开检查检验单（项目码 + 数量，数字校验 4xx 口径）/开处方（复用 pharmacy 表单）→ 单据行落位中列 tabs（行入场 fuy-flip enter）→ 诊毕面板：去向下拉（disposition 八项前端常量）+ 在途单据确认勾选（未勾选禁用按钮）+ M09 提醒不拦截注记（12px 灰）→ 诊毕（danger 确认弹窗）→ 左列该行 SERVED 灰化下沉，右列三卡清空复位。
- **动效编排表**：同 §6.1 进场、§6.2 行增删、§6.5 状态流转；开单表单展开为 `v-show + scaleY` 200ms（§6.6）；诊毕成功后中列内容 fade-out 120ms（`fuy-content-fade` leave）。
- **关键样式**：处方引用行「已发药」镜像列文字色 `--fuy-color-success-text`；「在途单据」未结数徽标（warning 底白字圆形 18px）挂诊毕按钮左侧。

### 8.4 portal · 免登录预约页（AppointmentView）

- **布局**：§3.3；三步卡即页面骨架，步骤指示条 3 圆点（当前品牌实心、已完成 success、未到中性描边）。
- **核心交互流**：身份二选一（tab 切换清空另一介质输入）→ 显式校验（失焦 + 提交双触发，错误贴字段 + aria-describedby）→ 查询可约号源（日期横滑 chip + 号源卡列表，加载骨架）→ 选卡 → 「确认预约」提交 → 出票卡替换态（§6.8）+ 焦点移入 → 倒计时启动（≤5min 琥珀色）→ 「再约一个」复位到第 1 步（保留介质输入）。
- **容错**：OP-1003/1006/1007 文案映射表（§5.4）；提交中全表单禁用防重复出号；失败驻留所选号源卡。
- **动效编排表**：§6.1 进场；步骤切换 scrollIntoView（reduced-motion 时 auto）；§6.8 出票；倒计时数字 `.fuy-num` 无动画直更。

### 8.5 bigscreen · 候诊叫号页（QueueBoardView）

- **布局**：§3.4 grid 三段；未配置令牌时（`VITE_BIGSCREEN_TOKEN` 空）整页横幅「未配置大屏令牌，已禁用数据链路」+ 零出网（无 REST 无订阅），横幅样式 `--fuy-screen-bg-panel` + 琥珀描边。
- **核心交互流**：REST 快照首屏（deptCode 经路由 query 可书签化）→ 订阅 `/topic/outpatient/queue/{deptCode}` → CALLED 帧：§6.3 三段编排 + 候诊榜对应行淡出（该票离队）→ SERVING/状态帧：列表 merge + tag 流转（§6.5，暗色版 tag 用文字色+描边，非 EP tag）→ 断线：头部呼吸点转灰 + 中部「连接中断，自动重连中」横幅（1.25rem），恢复后自动重订阅续播。
- **动效编排表**：

| 触发 | 场景 | 参数 |
| --- | --- | --- |
| 常驻 | 连接呼吸点 | opacity 1→.4 alternate infinite 1.2s linear（状态指示豁免面） |
| CALLED 帧 | §6.3 三段 | 320/960/320ms，仅 transform+opacity |
| 列表 merge | 行增删 | §6.2 fuy-flip |
| 时钟 | 每秒文本直更 | 无动画，`.fuy-num` 防抖 |

- **关键样式**：当前叫号卡 `bg-elevated` + `glow` 阴影 + 1px `--fuy-screen-brand` 描边；候诊榜行卡斑马纹（偶数行 `#0d2132`）替代分割线提升 3 米外行辨识。

---

## 9. 存量前端全面优化方案（第二部分）

> **裁决依据**：用户 2026-09-20 裁决——PR-5 前端优化范围不限于新五页，须对**已有前端基建全面优化**。本节为第二部分：存量页面与样式基建的审计结论与改造规格。
> **与既有章节的关系**：§1-§8 的 token / 布局 / 组件 / 交互 / 动画 / 性能规范是存量改造的**唯一规范源**，本节只写「存量特有」内容，不重复定义任何 token。§3.1「沿用既有 MainLayout 不改动」与附录「本规范不改动任何既有页面」两条，自本节起**收窄为「新五页交付时不因视觉统一反向阻塞存量改造」的过程性约束**，存量改造按本节批次执行时不再受其禁止（新裁决覆盖旧条款；§1-§8 与附录原文一字不动）。本方案不含 W-22 九条合规遗留的修复（属 fix PR 范围），改造时不得与之冲突（§9.8 边界注记）。
> **方法论**：同第一部分（ui-ux-pro-max 优先级规则 + design-system 三层 token）；宪法 C.7 谋建琢三段律适用于每一批次（谋=本节，建=批次实现，琢=taste-skill 打磨）。

### 9.1 现状审计

#### 9.1.0 样式基建审计结论（审计对象核存在性）

| 审计对象 | 实际状态 | 结论 |
| --- | --- | --- |
| `src/styles/` | 仅 0 字节 `.gitkeep` 占位 | **styles/shared 与 styles/ui 均不存在**——零全局样式、零主题覆盖、零工具类；§2.1 规划的四文件（tokens / element-plus / motion / index）属纯新增，尚未落盘 |
| `src/assets/`、`src/components/common/`、`src/composables/`、`src/directives/` | 全部空目录 | 共享组件/组合层为空，样式 100% 内联于 14 个 SFC 的 `<style scoped>` 块 |
| Element Plus 定制现状 | 零定制 | 无 `ElConfigProvider`、无 zh-cn locale、无 `--el-*` 覆盖、无主题 SCSS；`unplugin` resolver 按需引入（§7.3 结论不变）；无 `@element-plus/icons-vue` 依赖 |
| packages/ui | 占位 `export {}` | 无跨应用组件，本方案不触碰 |
| packages/shared | 仅 `PageResult` 契约 + `api.d.ts` | 纯 TS 包边界（web B.1）不破，本方案不触碰 |
| 色值纪律 | workstation 14 个 SFC **零裸 hex**（全走 `var(--el-*)`）；bigscreen 存量 2 文件共 **12 处硬编码 hex**（4 个唯一值，均为 EP 默认色硬拷贝） | workstation 色值基础健康；bigscreen 迁移点见 §9.5 |

#### 9.1.1 审计发现总表（编号 F-x，改造规格回链）

| 编号 | 发现 | 影响 | 回链 |
| --- | --- | --- | --- |
| F-1 | **ElMessageBox 按需样式缺口**：患者建档（alert）、划价结算（confirm）、退费审批（prompt）、发药工作台（confirm）四页在 script 中使用 ElMessageBox 但未手动引 `element-plus/es/components/message-box/style/css`（仅患者详情页正确引入）；深链直达这些路由时确认/输入弹窗无样式，SPA 内跳转因其他页面已加载样式而被掩盖 | 高频确认弹窗在直接进入路由时裸渲染 | §9.4 逐页 + §9.2 批次 0 |
| F-2 | **EP locale 缺口**：无 zh-cn locale 注入，`el-pagination` 渲染英文「Total N」、`el-date-picker` 面板英文月份——违反全中文红线的产品观感 | 患者检索分页、一日清单日期面板 | §9.2 批次 0 |
| F-3 | **侧栏高亮缺口**：`AppSidebar` 用 `default-active="route.path"`，`/patients/:patientId` 详情路由不匹配任何菜单 index → 患者管理组整组无高亮，位置感断裂 | 详情页丢失导航上下文 | §9.3 |
| F-4 | **键盘可达性断点**：患者检索、发药工作台两处「行点击」操作（跳详情/选处方）无键盘路径（`tr` 不可聚焦），键盘用户无法完成主流程 | 违 §1.2-4 无障碍底线 | §9.6 |
| F-5 | **密度失衡**：`size="small"` 表格 9 处 vs 默认密度 3 处（患者检索表、发药工作台队列/明细表）并存；页面容器 max-width 四档并存（720/880/1080/无限制） | 同屏切换页面行高跳变、宽度无预期 | §9.4 统一裁决 |
| F-6 | **反馈模式不一**：成功反馈三形态并存（ElMessage / el-alert 常驻 / `<p>` 文本）；空态两形态并存（el-empty 零使用，`<p>` 文本 2 处、白板 4 处） | 交互一致性缺失 | §9.6 |
| F-7 | **动画零覆盖**：存量页无进场、无内容显隐过渡、无行增删反馈——静态正确但无 §1.2-3 要求的「传达业务事实」动效 | 与新三页并存的质感断层 | §9.6 |
| F-8 | **枚举值直出**：患者详情页建档渠道/档案来源渲染英文枚举原文（`WINDOW`/`STANDARD`）；发药工作台患者列直显雪花 ID | 操作员可读性 | §9.4-3、§9.4-8 |
| F-9 | **性能面干净**：全量 `watch` 零使用、零 `setInterval`、路由组件全懒加载、无图表、无深 watcher——存量无重大性能违规 | 治理以预防性规范为主 | §9.7 |

#### 9.1.2 逐页审计表（布局结构 / 样式组织 / 交互模式 / 痛点）

体量为 SFC 总行数；「样式组织」一栏均指 `<style scoped>`（下表简写 scoped），选择器前缀惯例健康（`.patient-search-*` 等 BEM 式）。

**① MainLayout（42 行）+ AppSidebar（39 行）+ AppHeader（52 行）——布局壳**

| 维度 | 现状 |
| --- | --- |
| 布局结构 | el-container 三段：aside 220px + header 56px + main（bg `--el-fill-color-lighter`）；侧栏无头部区、无折叠能力；Header 仅「站点名 + 用户下拉」两端布局 |
| 样式组织 | 三组件各自 scoped，共 5 条规则，色值全走 EP 变量 |
| 交互模式 | 菜单 `router` 模式导航；用户下拉仅「退出登录」；无折叠、无面包屑、无当前页上下文 |
| 痛点 | F-3 高亮缺口；菜单项高 56px（EP 默认）偏松（密度优先原则下过高）；选中态仅 EP 默认底色无品牌指示；无折叠交互（1280 小屏挤占内容区） |

**② HomeView · workstation 首页（65 行）**

| 维度 | 现状 |
| --- | --- |
| 布局结构 | 纵向文本流：h1 站点名 + 问候区 + 虚线占位卡（与 AppHeader 站点名重复出现） |
| 样式组织 | scoped 7 条规则，px 字号硬写（18/13px） |
| 交互模式 | 纯展示零交互（符合「禁伪数据」红线） |
| 痛点 | h1 与 Header 站点名重复；字号未 token 化；占位卡视觉弱 |

**③ LoginView · 登录页（132 行）**

| 维度 | 现状 |
| --- | --- |
| 布局结构 | 全屏居中 el-card 360px：标题 22px + 副标题 + label-position=top 表单 + 全宽提交 |
| 样式组织 | scoped 5 条规则；背景默认白（无层次） |
| 交互模式 | 三态齐备（validate 拦截 / submitting loading+守卫 / 拦截器弹错+驻留），回跳防 open redirect——**存量交互质量标杆** |
| 痛点 | 无品牌视觉锚点（零品牌色）；卡片无圆角/阴影层级；副标题 13px 未 token 化 |

**④ PatientSearchView · 患者检索（163 行）**

| 维度 | 现状 |
| --- | --- |
| 布局结构 | 单 el-card（max-width 1080）：卡头页面题 + 检索条（input 360 + 查询钮）+ 表格 + 空态 p + 分页右对齐 |
| 样式组织 | scoped 5 条；`*-bar` 检索条形态（6 页重复出现的同构块之一） |
| 交互模式 | 空词前置拦截不出网 ✓、查询 loading ✓、翻页 1 基↔0 基边界转换 ✓、行点击跳详情 |
| 痛点 | 表格默认密度（F-5）；空态用 p 文本（F-6）；行点击无键盘通道（F-4）；分页英文 Total（F-2）；无进场/翻页过渡（F-7） |

**⑤ PatientCreateView · 患者建档（233 行）**

| 维度 | 现状 |
| --- | --- |
| 布局结构 | 单 el-card（max-width 720）：预检 alert（条件）+ 11 行 label-width=140px 平铺长表单 + 底部「匹配预检/建档」双钮 |
| 样式组织 | scoped 4 条；**无 message-box 样式手动引入（F-1）** |
| 交互模式 | 双动作各有 loading+守卫 ✓；预检结论 alert 三态文案 ✓；读卡器占位禁用不伪造 ✓ |
| 痛点 | 11 字段无分节（基础身份/证件介质/建档属性混排，扫描成本高）；F-1 缺口；建档成功跳详情无成功反馈（静默跳转） |

**⑥ PatientDetailView · 患者详情（130 行）**

| 维度 | 现状 |
| --- | --- |
| 布局结构 | 单 el-card（max-width 880）：卡头「患者档案 + 冻结/解冻钮」+ descriptions :column=2 border 12 字段 + 空态 p；卡整体 v-loading |
| 样式组织 | scoped 4 条；message-box 样式**已正确引入**（四页中唯一） |
| 交互模式 | 冻结 prompt 收集原因留痕 ✓、解冻直发 ✓、成对动作按状态显隐 ✓ |
| 痛点 | 冻结/解冻按钮**无 loading 无在途守卫**（双击双 POST 面——不属 W-22⑥ 范围，患者域，本方案 §9.6 补齐）；F-8 枚举直出；v-loading 罩整卡导致卡头动作按钮闪烁；无骨架首屏 |

**⑦ PricingSettleView · 划价结算（394 行，存量最大页）**

| 维度 | 现状 |
| --- | --- |
| 布局结构 | 双 el-card 纵叠（max-width 1080）：检索条（患者号/就诊号+查询费用+手工计费）→ 划价行编辑表（el-input 嵌 el-input-number）→ 划价结果表 / 待收费用表 + 预结算/确认结算钮 + 成功 alert；手工计费 el-dialog 420px |
| 样式组织 | scoped 8 条（存量最多）；`*-bar` 同构块；**无 message-box 样式手动引入（F-1）** |
| 交互模式 | 三态完整（四组 loading ref + 前置拦截 + 拦截器弹错 + 草稿驻留）✓；结算 confirm 带总额回显 ✓——金额 string 零运算红线合规 |
| 痛点 | F-1；金额/数量列无 `.fuy-num` 不等宽（结算场景数字抖动敏感）；小节题 h4 散写；成功 alert 常驻无清理时机说明；划价行数无上限（行编辑组件树随行数线性膨胀） |

**⑧ RefundApprovalView · 退费审批（378 行）**

| 维度 | 现状 |
| --- | --- |
| 布局结构 | 双 el-card 纵叠（max-width 1080）：退费申请（结算号检索 → descriptions :column=4 摘要 → 可退明细勾选表 + 退数量列 + 理由输入）/ 审批队列（状态筛选 + 队列表三动作钮） |
| 样式组织 | scoped 9 条（存量最多）；`*-bar` 同构块 ×2；**无 message-box 样式手动引入（F-1）** |
| 交互模式 | 在途守卫形态最全（rejecting/executing 入口同步置位防双窗双 POST）✓；按态启停三函数 ✓；PENDING_SECOND_APPROVAL 两段式复用 ✓——**存量交互质量另一标杆** |
| 痛点 | F-1；状态 tag 映射粗糙（EXECUTED success/其余 info 二值，待审/驳回语义不可辨）；状态词表与筛选项同源词重复散写组件内；金额列无 `.fuy-num` |

**⑨ DailyListView · 一日清单（148 行）**

| 维度 | 现状 |
| --- | --- |
| 布局结构 | 单 el-card（max-width 1080）：检索条（就诊号 + 日期 + 查询）→ 条件渲染（明细表 + 大类汇总表 420px + 三分区合计条） |
| 样式组织 | scoped 7 条；`*-bar` 同构块；ElMessage 样式已引入 ✓ |
| 交互模式 | 双前置拦截 ✓、BigInt 勾稽佐证展示级合规 ✓、勾稽绿标/红标 |
| 痛点 | **未查询态整页白板**（无引导，F-6 最重一例）；日期面板英文（F-2）；合计条无视觉强调（三个 14px span 并排，患者费用公开场景不够醒目）；金额无 `.fuy-num` |

**⑩ DrugDictView · 药品字典（338 行）**

| 维度 | 现状 |
| --- | --- |
| 布局结构 | 单 el-card（max-width 1080）：检索条（关键词 + 基药/医保对照双 select + 检索 + 建档钮）→ 表格（8 列 + fixed right 操作列）；建档/变更 el-dialog 520px（11 字段）+ 医保对照 el-dialog 420px（3 字段） |
| 样式组织 | scoped 4 条；`*-bar` 同构块；ElMessage 样式已引入 ✓ |
| 交互模式 | 建档/变更弹窗复用（editId 空串判别）✓、必填前置校验（submit 内 if 散写形态）、对照后标记翻转 ✓ |
| 痛点 | 校验散写在提交函数内（错误提示 ElMessage 顶部弹，非字段就近——违 §4.4 表单规范精神）；11 字段弹窗无分节；空结果无空态；tag 无 aa 修正 |

**⑪ DispenseWorkbenchView · 发药工作台（202 行）**

| 维度 | 现状 |
| --- | --- |
| 布局结构 | el-row :gutter=16 左右分栏（:span=10 队列 / :span=14 发药单条件渲染），**写死 span 无响应断点**；队列表 highlight-current-row + 行点击选单 → descriptions（调配/核对）+ 明细表（追溯码逐行录入）+ 配药/核对/发药签名三钮 |
| 样式组织 | scoped 3 条（存量最少）；**无 message-box 样式手动引入（F-1）** |
| 交互模式 | 双段队列合并拉起在途单 ✓、同人双签辅助禁用 ✓、配药无码不结前置 ✓、发药 confirm ✓ |
| 痛点 | F-1；发药确认文案无单号回显（违 §4.4「禁止裸确认」）；单据状态（CREATED/PICKING/PICKED）不可见（仅按钮启停间接表达）；队列空态白板；患者列直显雪花 ID（F-8）；默认密度（F-5） |

**⑫ DispenseReturnView · 退药受理（198 行）**

| 维度 | 现状 |
| --- | --- |
| 布局结构 | 单 el-card（max-width 1080）：检索条（处方号）→ 条件渲染（descriptions :title 形态 + 退药行编辑表 + 模式 radio 组 + 提交钮） |
| 样式组织 | scoped 7 条；`*-bar` 同构块；ElMessage 样式已引入 ✓ |
| 交互模式 | 处方号前置拦截 ✓、实物退逐码必填前置（无码不结）✓、模式切换 placeholder 联动 ✓；提交钮**无 loading 无守卫** |
| 痛点 | 提交钮防抖缺口——**属 W-22⑥ 范围（pharmacy 三页），归 fix PR，本方案不含**（边界注记：改造时保留其修复形态，不重复实现不预先实现）；el-descriptions `:title` 属性用法非常规（标题在列表上方弱呈现）；检索无单时 warning 弹错+白板并存 |

**⑬ portal HomeView（14 行）**：纯占位（h1「富云患者门户」+ 一句话），h1 为路由冒烟断言锚点。见 §9.5 处理口径。
**⑭ bigscreen HomeView（176 行）+ TelemetrySummaryPanel（75 行）**：亮色最小遥测页，原生 input/button/table，12 处硬编码 hex，无暗色底、无品牌感。见 §9.5。

### 9.2 设计系统迁移策略

#### 9.2.1 与「token 纯新增渐进采用」裁决的衔接

既有裁决（§2.1/附录）：token 文件纯新增，仅被显式消费 `--fuy-*` 或工具类的新页面使用。本节将其推进为两阶段：

1. **阶段一（第一部分已裁决，Task 13 交付面）**：三 app `styles/` 四文件落盘 + `main.ts` 各一行 import。存量页零改动，token 已在全局可用。
2. **阶段二（本节新增，存量分批消费）**：存量页按 §9.8 批次把 scoped 私有样式**逐块替换**为 token 引用与共享工具类。每次替换以「该页五连门禁 + spec 零回退」为闸门，禁止全量一把梭（一次 PR 只动一个域的三页或布局壳一批）。

#### 9.2.2 styles/ 目录文件级方案（收编/重构）

**裁决：不新建 `styles/shared` 与 `styles/ui` 目录**（审计证实其不存在，且「shared/ui」命名与 `packages/shared`、`packages/ui` 语义冲突，违反唯一声明原则）。维持 §2.1 的四文件平铺结构，本节给出每文件的「保留 / 新增」精确清单：

| 文件 | 处置 | 内容 |
| --- | --- | --- |
| `tokens.css` | **保留不动** | §2.2-§2.5 已定 primitive + semantic（§9 批次零修改） |
| `element-plus.css` | **保留 + 新增存量收编工具类** | 保留：§2.2.4 `:root:root` 主色映射、§4.2 `.fuy-dense`、§4.3 `.fuy-tag-aa`/`.fuy-tag-strike`/`.fuy-triage-badge`、`.fuy-num`。新增（本节定义，仅此一次）：`.fuy-page`（§3.1 已定）、`.fuy-toolbar`、`.fuy-section-title`、`.fuy-total-strip`、`.fuy-menu-*` 五个存量收编类（规格见下） |
| `motion.css` | **保留不动** | §2.6 + §6 全部 keyframes/transition 类 + reduced-motion 兜底 |
| `index.css` | **保留不动** | @import 链（tokens → element-plus → motion） |
| `.gitkeep` | **删除** | 四文件落盘同批清理（本次改动产生的死文件，全局规范 §四零容忍） |

**存量收编工具类精确规格（element-plus.css 新增段）：**

```css
/* 检索/操作工具条：收编 6 页同构的 *-bar scoped 块（患者检索/划价/退费/一日清单/药品字典/退药受理） */
.fuy-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;               /* 窄屏防溢出（存量无换行能力，收编时一并补齐） */
  gap: var(--fuy-space-3);
  margin-bottom: var(--fuy-space-3);
}

/* 卡内小节题：收编划价/退费/一日清单三页散写的 h4 规则 */
.fuy-section-title {
  margin: var(--fuy-space-4) 0 var(--fuy-space-2);
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
  color: var(--el-text-color-primary);
}

/* 三分区合计强调条：一日清单勾稽佐证条专用（患者费用公开场景的醒目口径） */
.fuy-total-strip {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-6);
  margin-top: var(--fuy-space-3);
  padding: var(--fuy-space-3) var(--fuy-space-4);
  background: var(--el-fill-color-light);
  border-radius: var(--fuy-radius-md);
}
```

**SFC scoped 块收编映射表**（「过渡期并存」：每页改造批内完成替换并删除对应 scoped 规则，禁止跨批残留半收编态）：

| 存量 scoped 规则 | 去向 | 涉及页 |
| --- | --- | --- |
| `.patient-search-bar` / `.pricing-settle-bar` / `.refund-approval-bar` / `.daily-list-bar` / `.drug-dict-bar` / `.dispense-return-bar` | `.fuy-toolbar`（容器挂类）+ 页内保留 input 专属宽度的 1 条 scoped 规则 | 6 页 |
| `.pricing-settle-section` / `.daily-list-section` | `.fuy-section-title` | 2 页 |
| `.daily-list-total` | `.fuy-total-strip` | 1 页 |
| 各页根 `max-width` 720/880/1080 | `.fuy-page` 骨架；**宽度档位四档并两档**：表单/详情卡级 `max-width: 880px` 统一一档（建档 720 并入），列表卡全宽（1080 上限撤销，密度优先全宽利用 §1.2） | 全部业务页 |
| `font-size: 13px/14px/18px` 等 px 硬写 | `var(--fuy-font-size-sm/md/xl)` | 壳 + 业务页 |

#### 9.2.3 Element Plus 主题与 locale 落点

- 主色梯度：`element-plus.css` `:root:root`（§2.2.4，批次 0 落盘即覆盖存量页——主色变化对存量页是**预期内的全局统一**，EP 默认蓝 `#409eff` → 临床蓝 `#0369a1`，功能四色保留 EP 默认不动，与存量硬编码语义一致）。
- **zh-cn locale（F-2 修复）**：`App.vue` 根节点包 `<el-config-provider :locale="zhCn">`，`import zhCn from 'element-plus/es/config-provider/locale/zh-cn'`（按需路径）。单实例挂 App 根，分页「共 N 条」与日期面板即时中文化；不引全量 locale 入口文件。
  > 订正注记（2026-09-21，批次 0 质量门 R-5）：上行路径为笔误——EP 2.14.5 包内不存在 `es/config-provider/locale/zh-cn`，实际按需路径为 `element-plus/es/locale/lang/zh-cn`（同为按需路径 import、非全量 locale 入口文件，不违背本条禁令）；批次 0（PR #37）已按实际路径实现，后人照本文实现时以实际路径为准。
- 禁改：EP 主题 SCSS 编译、`.el-*` 全局裸覆盖（§4.1 禁令对存量改造同样生效——一切定制走 `--el-*` 变量或挂 `fuy-` 类）。

#### 9.2.4 落地顺序（顺序不可逆，前批是后批的地基）

```
批次 0 全局地基（styles 四文件 + locale + F-1 缺口修复）
  → 批次 1 布局壳（MainLayout/Sidebar/Header/Home/Login + fuy-page 全页铺开）
  → 批次 2 患者域三页 → 批次 3 收费域三页 → 批次 4 药房域三页
  → 批次 5 bigscreen/portal 存量
```

每批内统一动作序列：挂 `fuy-page` 骨架 → 工具类收编 → 表格 `fuy-dense` + `.fuy-num` → 空态/加载态补齐 → 动效补齐（§9.6 清单）→ 该页 scoped 死规则清理 → 五连门禁。

### 9.3 布局壳升级

#### 9.3.1 精确布局树（升级后）

```
.main-layout（el-container horizontal，height: 100dvh，overflow: hidden）
├── el-aside .main-aside（:width="collapsed ? '64px' : '220px'"；width 瞬切零动画——铁律）
│   └── AppSidebar :collapsed
│       ├── .fuy-menu-head（高 48px，flex 居中）
│       │   ├── 展开态：品牌字标「富云」14px/700 白字，brand-700 底，radius-md，24px 高
│       │   └── 折叠态：同字标 24×24 缩略（仅「富」单字）
│       └── el-menu（router 模式；:collapse="collapsed" :collapse-transition="false"）
│           ├── 菜单项：高 40px（scoped 覆盖 --el-menu-item-height），字号 md
│           ├── 分组标题：xs/600，letter-spacing 0.5px，次要色（scoped 覆盖）
│           ├── 选中态：::before 左缘 3px brand 色条 + 底 brand-50 + 文字 brand-700
│           └── hover 态：底 gray-50（120ms 背景色过渡，单元素状态反馈）
└── el-container（vertical）
    ├── el-header .main-header（height 56px，border-bottom hairline）
    │   └── AppHeader :collapsed @toggle="collapsed = !collapsed"
    │       ├── 左：折叠按钮（32×32 点击域 ≥24px 下限；纯 CSS 汉堡——三条 2px×16px 横线
    │       │   span 叠放，aria-label 动态「展开/收起侧边栏」，零图标依赖）
    │       │   + 站点名「富云医护工作站」16px/600（文本不动——App.spec 冒烟锚点）
    │       └── 右：用户区（40px 高点击域，hover 底 brand-50 120ms，radius-md）
    │           └── el-dropdown「显示名 ▾ → 退出登录」（逻辑零改动）
    └── el-main .main-content（overflow-y auto，bg --el-fill-color-lighter）
        └── RouterView（各页根节点统一挂 .fuy-page）
```

#### 9.3.2 导航信息架构与状态反馈

- **菜单数据化**：AppSidebar 菜单从模板硬编码改为组件内常量数组（`{ index, label, abbr, group }`），模板 `v-for` 渲染——一份常量同时解决三件事：分组渲染、折叠态单字缩写（`abbr`，如「患者建档」→「档」）、高亮计算。
- **高亮修复（F-3）**：`default-active` 改计算属性 `activeIndex`——`route.path` 与菜单 index 精确相等取之；否则取**前缀最长匹配**（`/patients/P123` → `/patients`）。无匹配回落 `''`（不高亮，不误标）。
- **折叠状态**：MainLayout 持 `ref(false)`，props 下行 / 事件上行（父子直连，不引 provide/store）；**内存态不持久化**（刷新复位，P0 不开 storage 面）。
- **折叠动画裁决**：aside 宽度变化属 layout 属性，**瞬切零动画**（§6 铁律禁 width 动画）；菜单项文字仅 opacity 120ms 过渡（transform/opacity 豁免面内）；EP `:collapse-transition="false"` 关闭内建宽度动画。
- **不做**：面包屑（router meta 无 title 字段，加注属 router 面改动且 Header 站点名 + 侧栏高亮已承载位置感——推测性设计不 做）；图标包（零新增依赖红线，文字导航在 8 项规模下清晰足够；icons 归 P2 演进登记）。

#### 9.3.3 HomeView / LoginView 升级规格

| 页 | 规格 |
| --- | --- |
| HomeView | 根挂 `.fuy-page`；h1 站点名**删除**（与 AppHeader 重复，App.spec 锚点「医护工作站」由 AppHeader 满足），问候语升为页面题（`--fuy-font-size-xl`/600）；问候区与占位卡入 el-card（占位卡保持虚线弱形态 + `--fuy-space-4` 内边距）；字号全 token 化；**禁预列模块卡片与伪数据红线不变**（注释既有红线） |
| LoginView | 背景 `--fuy-palette-gray-50`；卡片 `radius-xl` + `shadow-md` + 顶部 3px 品牌色条（静态，入场零动画——§6.8 出票隐喻不外溢）；标题 22px→`--fuy-font-size-xl`、副标题 `sm`；表单逻辑/文案/autocomplete 零改动（存量交互标杆不动） |

### 9.4 存量业务页逐页改造规格

通用落点（9 页共享，不逐页重复）：根节点 `.fuy-page`（§3.1）；页面题 18px/600（`--fuy-font-size-xl`）+ 页头行高 48px；表格容器挂 `fuy-dense`（§4.2）；金额/数量/计数列 `.fuy-num` + 右对齐；空态 el-empty（§4.4，description 业务口径）；首屏数据加载骨架（§4.4 骨架屏条款）；按钮三态（§5.1）；进场 stagger（§6.1，卡组 index 顺序）；内容显隐 `fuy-content-fade`（§6.7）；状态 tag 一律对照 §4.3 映射法 + `.fuy-tag-aa` 修正。以下仅列**该页特有**：

**① 患者检索 PatientSearchView**
- 表格默认密度 → `fuy-dense`；行点击保留，**新增「详情」link 按钮列（width 60，键盘可达）**与行点击双通道（F-4；spec 仅锁脱敏文本与 0 基转换，安全）。
- 空态 `<p>` → `<el-empty :image-size="72" description="未检索到匹配患者">`；分页保右对齐 + locale 中文化自动生效。
- 检索条 → `.fuy-toolbar`；表格容器 `min-height: 240px`（§7.1 CLS）。
- 一致性保证：状态 tag 走 `patientStatusTagType` 既有 utils（spec 锁 danger 语义）+ aa 修正，与新三页 §4.3 同源。

**② 患者建档 PatientCreateView**
- 11 字段**三分节**（`fuy-section-title`）：身份基础（姓名/性别/出生日期）→ 证件介质（介质/证件号/手机号/住址 + 读卡占位）→ 建档属性（渠道/来源/知情同意）；label-width 140px 保留。
- **补 `import 'element-plus/es/components/message-box/style/css'`**（F-1）。
- 建档成功跳详情前补 `ElMessage.success('建档完成')`（消除静默跳转；成功时刻不加过冲——emphasis 两处允许面不扩容）。
- 预检 alert 保持三态文案与类型（warning/warning/success），仅色值随全局主色统一。
- 一致性保证：表单规范（§4.4 label-position/宽度）与挂号页「选择患者」卡同构。

**③ 患者详情 PatientDetailView**
- 卡头改页头行：患者名（emphasis 18px）+ 状态 tag + 冻结/解冻钮右对齐；descriptions `:column="2"` → `:column="3"`（1440 主流屏密度）。
- **冻结/解冻补 loading + 在途守卫**（`freezing` ref，`if (freezing.value) return` 先于一切 await——§5.1 形态；患者域不属 W-22⑥，纳入本方案；spec『点击解冻触发成对动作』单次点击断言不受影响）。
- F-8：`registerChannel`/`archiveSource` 枚举 → `utils/patientDisplay.ts` 增补两组中文词表纯函数（同文件既有映射形态；spec 无锁这两个字段的渲染文本）。
- v-loading 从整卡移至 descriptions 区（卡头动作不再闪烁）；首屏 `el-skeleton :rows="4"`（数据未达时）。
- 一致性保证：患者上下文卡形态与医生站 §3.2「患者上下文」卡同源。

**④ 划价结算 PricingSettleView**
- 双卡纵叠保留（结算流线性语义）；检索条 → `.fuy-toolbar`；三处小节题 h4 → `.fuy-section-title`。
- 划价行编辑表**软上限 20 行**（增行时超限 `ElMessage.warning('划价行数已达上限 20 行')`，防行编辑组件树膨胀——§9.7-3）。
- 手工计费弹窗 label-width 90px → 96px（§4.4 统一口径）；弹窗 420px 固定已合规。
- 结算成功 alert 保留常驻（业务锚点），补语义：新查询结算时随摘要重置——现行为已隐含，仅注记不改逻辑。
- **补 message-box 样式引入（F-1）**；金额/数量列 `.fuy-num` + 右对齐。
- 一致性保证：右列「收费联动」新页 §8.1 的结算形态与本页预结算/确认结算两步语义同构（同为 §5.2 高风险档）。

**⑤ 退费审批 RefundApprovalView**
- 状态 tag 从二值（success/info）→ §4.3 映射法全表：PENDING_APPROVAL=warning+aa、PENDING_SECOND_APPROVAL=warning+aa、APPROVED=primary、EXECUTED=success+aa、REJECTED=danger+aa、DRAFT=info（文案词表不动——spec 锁按钮禁用态不锁 tag type）。
- 状态词表与筛选项仍同源于组件内 `refundStatusText`（单页使用，简单优先不外移 utils）。
- 审批队列操作列：按钮 size=small 间距 8px（§8.2 同款）；在途守卫形态（rejecting/executing）**零改动**（存量标杆）。
- **补 message-box 样式引入（F-1）**；摘要 descriptions :column=4 保留 + 金额 `.fuy-num`。
- 一致性保证：高风险档确认模式（§5.2 表第三档）与新三页一致。

**⑥ 一日清单 DailyListView**
- **未查询态补引导空态**：`<el-empty description="输入就诊号与清单日期查询费用明细">`（F-6 最重一例）。
- 三分区合计条 → `.fuy-total-strip`：Σ明细/Σ大类 次要 14px，合计 emphasis 20px `.fuy-num`；勾稽 tag 走 aa 修正（success/danger 语义保留）。
- 大类汇总表 `max-width: 420px` 保留；明细表容器 `min-height: 240px`；结果显隐包 `fuy-content-fade`。
- 日期面板中文随批次 0 locale 生效；金额列 `.fuy-num`。
- 一致性保证：费用表列序/宽度与划价页「待收费用」表同构（同为收费域展示表）。

**⑦ 药品字典 DrugDictView**
- 建档/变更弹窗 11 字段**两分节**（基础档案：药码~单位 + switch；管控属性：抗菌/危险/麻精/皮试）；**必填校验从提交函数 if 散写改 el-form `:rules` 声明式**（错误就近字段显示，§4.4 表单规范；提交函数保留 trim 后最终防线上移——spec 锁「以行 id 调 mapInsurance」不锁校验形态）。
- 医保状态 tag 补 `.fuy-tag-aa`（success/warning 文字色修正）；弹窗 520/420 宽保留；label-width 100px → 96px。
- 检索空结果补 `el-empty description="未检索到匹配药品"`；操作列 fixed=right 保留。
- 一致性保证：弹窗规范（§4.4 宽度/回显摘要）与新页开单弹窗同构。

**⑧ 发药工作台 DispenseWorkbenchView**
- `:span="10/14"` 写死 → `:md="24" :lg="10/14"`（响应折叠，§3.1 断点口径）。
- **单据状态可视化**：发药单 descriptions 增状态 tag（§4.3 映射法：CREATED=primary 待配药、PICKING=warning 配药中、PICKED=success 待发药签名、ISSUED=info 已发药）——三按钮启停语义显性化。
- 发药 confirm 文案带单号回显：`发药单 ${dispenseNo} 签名后药品出库且不可逆，确认发药？`（§4.4 禁裸确认；spec 锁出网序列不锁文案）。
- isPicker 禁用按钮补 `title="调配人不可自行核对/发药"`（禁用原因可见）。
- **新增「选择」link 按钮列（width 64，键盘可达）**与行点击双通道（F-4 收口；spec 锁「配药→核对→发药」出网序列不锁行点击来源，安全）。
- 队列空态 `el-empty description="暂无待发/调剂中处方"`；队列表 `fuy-dense`；F-8 患者列直显雪花 ID **保持原样**（PrescriptionVO 无姓名字段，不虚构契约——后端补字段后随 P2 演进，此处仅注记）。
- **W-22⑥ 边界**：配药/核对/发药按钮的在途 loading/守卫归 fix PR 交付；本页改造若晚于 fix PR 则保留其修复形态，禁止移除或重复实现。
- **补 message-box 样式引入（F-1）**。
- 一致性保证：左右分栏 + gutter 16 形态即 §3.2 新页布局树的同源（新页明言「与 DispenseWorkbenchView 形态同源」），本页升级后双向一致。

**⑨ 退药受理 DispenseReturnView**
- `el-descriptions :title` 非常规用法 → `fuy-section-title` 显式标题「发药单信息」+ descriptions 去 title。
- 模式 `el-radio-group` **组件类型保持**（spec 断言依赖组件定位，换控件即断言失效——§9.8-3）；退药数量 `el-input` 保持 string 契约 + `inputmode="numeric"`（**W-22⑦ 边界**：不引入 number 转换、不新增裸 parse，数量校验形态归 fix PR）。
- 检索无单：保留 warning 弹错 + 补 `el-empty description="该处方无发药单"`；结果显隐包 `fuy-content-fade`。
- 提交钮防抖（W-22⑥ 范围）**不在本方案实现**；本页视觉层改造与其同 PR 时按 fix PR 交付形态合入。
- 一致性保证：行编辑表 + 逐码录入形态与发药工作台同构（追溯码录入体验两页统一）。

### 9.5 bigscreen / portal 存量升级

#### 9.5.1 bigscreen HomeView + TelemetrySummaryPanel 并入暗色设计语言

前置：`bigscreen/src/styles/tokens.css`（§2.1/§2.2.3 暗色 primitive + semantic）与 `motion.css`、`index.css` 落盘（Task 13 交付面），`main.ts` 增一行 import。存量两文件的升级为**纯消费**：

- **底色**：`.home-view` 增 `background: var(--fuy-screen-bg-base)`、`color: var(--fuy-screen-text-primary)`、`min-height: 100dvh`（存量是亮色默认底——升级后为暗色遥测值守页，与新 QueueBoardView §8.5 同一视觉语言）。
- **12 处硬编码 hex 映射**（4 个唯一值 → token）：`#909399` → `var(--fuy-screen-text-secondary)`；`#dcdfe6` → `var(--fuy-screen-border-hairline)`；`#e6a23c` → `var(--fuy-screen-warn)`；`#67c23a` → `var(--fuy-screen-ok)`。映射后 scoped 内零裸 hex（§7.6-1 自查项对存量闭环）。
- **原生控件暗色基线**（§4.4 bigscreen 原生暗色基线引用）：input/button 底 `--fuy-screen-bg-panel`、描边 hairline、radius-md、文本 primary/secondary 两档；focus 描边 `--fuy-screen-brand` + `box-shadow: 0 0 0 3px rgba(56, 189, 248, 0.25)`（键盘焦点环可见，暗色版 §5.3）。
- **连接状态徽标 → 呼吸点 + 文字**：三态徽标改「8px 圆点 + 1.25rem 文字」，圆点走 §6 常驻呼吸动画（opacity 1→.4 alternate 1.2s linear，`.fuy-loading-essential` 豁免面——bigscreen 值守语义与 §8.5 连接状态点同款）；三态色 ok/warn/secondary。
- **遥测表暗色化**：面板容器 `--fuy-screen-bg-panel` + hairline 描边 + radius 8px；表头 secondary 小字；偶数行斑马纹 `#0d2132`（§8.5 同款值，此处允许字面量——该值已随 §8.5 定稿为 bigscreen 斑马纹唯一常量）；帧计数 `.fuy-num`。
- **禁改**：h1「富云数据大屏」（App.spec 冒烟锚点，组件注释已声明禁改名）；连接/订阅逻辑与 composable 零触碰；本页仍为「最小遥测页」语义，不升格为 QueueBoardView（大屏正页是 PR-5 新页，两页并存口径：遥测页=运维调试面，队列页=现场展示面）。

#### 9.5.2 portal 占位页处理口径

- **最小维持**：portal 无存量业务页，HomeView 仅为路由冒烟锚点。本 PR 对 portal 仅交付 Task 13 既定面（styles 三文件落盘 + main.ts import），HomeView 自身**零改动**（占位语义 + `padding: 16px` 保留，spec 锚点「富云患者门户」不动）。
- P1 AppointmentView（§8.4）落地时该占位页由路由承接改造为门户壳（归 P1 计划，不在本方案范围——避免为一个待替换页付出打磨成本）。

### 9.6 交互动效统一化（逐页缺失项与补齐规格）

通用规格全部引用既有定义：三态=§5.1、确认模式=§5.2、进场=§6.1、内容显隐=§6.7、reduced-motion 兜底=§6.9（motion.css 全局生效后存量页动画自动受兜底，无需逐页处理）。逐页缺失清单（「—」=已达标不动）：

| 页 | 交互三态 | 加载态 | 空态 | 过渡动效 |
| --- | --- | --- | --- | --- |
| 患者检索 | —（查询已有 loading） | 表格 v-loading ✓ | p → el-empty | 卡组 §6.1 stagger；表格容器 min-height |
| 患者建档 | —（双动作齐备） | — | —（表单页无空态） | §6.1；预检 alert 显隐 §6.7 |
| 患者详情 | **补**：冻结/解冻 loading + `freezing` 守卫 | 整卡 v-loading → 局部 + 首屏骨架 | p → el-empty | 骨架→内容 §6.7 |
| 划价结算 | —（四组齐备，存量标杆） | — | —（条件区自带语义） | §6.1 双卡；划价结果/成功 alert §6.7 |
| 退费审批 | —（守卫形态标杆） | — | —（队列恒有数据语义） | §6.1 双卡；摘要显隐 §6.7 |
| 一日清单 | — | 按钮 loading ✓ | **补**：未查询引导空态 | 结果区 §6.7；合计条入场 §6.1 |
| 药品字典 | — | — | **补**：检索空结果 el-empty | §6.1 |
| 发药工作台 | —（防抖归 W-22⑥ fix PR） | — | **补**：队列空 el-empty | §6.1 双卡；发药单显隐 §6.7 |
| 退药受理 | —（防抖归 W-22⑥ fix PR） | — | **补**：无单 el-empty | 单块显隐 §6.7 |
| 登录页 | —（标杆） | — | — | **零动画**（登录页克制口径，§9.3.3） |

补充裁决：存量页**不加**键盘快捷层（§5.3 快捷键为新页场景定制）；**不加** TransitionGroup 行动画（存量页非轮询刷新场景，REST 手动查询的重挂闪烁可接受——轮询 FLIP 语义属分诊台新页）；页面级路由切换过渡不加（RouterView 包装改变挂载结构风险 > 收益）。§9.4-①⑧新增的「详情/选择」link 按钮列即键盘通道（F-4 收口）。

### 9.7 性能治理

审计结论（F-9）：存量零 `watch`、零 `setInterval`、路由全懒加载、无图表——无重大违规。治理规格（预防性 + 两个具体项）：

| # | 治理项 | 规格 | 依据 |
| --- | --- | --- | --- |
| 1 | CLS 锁定 | 表格区容器 `min-height: 240px`、检索条区 `min-height: 48px`、详情 descriptions 区 `min-height: 200px`——加载/空态切换零塌陷 | §7.1 CLS<0.1 |
| 2 | 数字稳定 | 全部金额/数量/计数列 `.fuy-num`（tabular-nums），页头计数类数字**不做滚动补间**（§6.4 数字滚动仅新页页头一处，存量不扩容） | §2.3/§6.4 |
| 3 | 行编辑组件树 | 划价行编辑软上限 20 行（§9.4-④），超限前置提示不出网不增行 | §7.1 帧预算 |
| 4 | 动画铁律适用 | 折叠宽度瞬切（§9.3.2）；一切新增动效仅 transform/opacity；存量补齐动效全部复用 motion.css 既有类，**零新 keyframes** | §6 铁律 |
| 5 | 懒加载与按需 | 路由懒加载现状保持；批次 0 新增组件（el-config-provider）经 resolver 按需；**禁止**借改造引入任何全量 import | §7.3/§7.4 |
| 6 | 全局单实例 | ElConfigProvider 仅 App 根一处；工具类为纯 CSS 零运行时；token 为静态自定义属性零计算 | §7.1 |
| 7 | 列表阈值 | 存量表数据源均为手动查询 + 服务端分页（≤20 行/页），未达 §7.2 虚拟化阈值——不引 el-table-v2，阈值超限属 P2 登记 | §7.2 |

### 9.8 实施分期与回归保障

#### 9.8.1 改造批次（每批一个 PR 域，批内五连门禁）

| 批次 | 范围 | 文件面 | 风险与注记 |
| --- | --- | --- | --- |
| 0 全局地基 | styles 四文件落盘（若 Task 13 已交付则并入其验收）+ `.gitkeep` 删除 + App.vue ConfigProvider zh-cn（F-2）+ 四页 message-box 样式补引（F-1） | styles/ 4 新增 1 删除、App.vue、4 个 SFC 各 1 行 import | 零断言风险（无业务行为变化）；主色全局切换为本批唯一全局视觉变化 |
| 1 布局壳 | MainLayout/AppSidebar/AppHeader/HomeView/LoginView + 存量 9 页根节点挂 `.fuy-page`（仅骨架类，不动页内样式） | 壳 5 文件 + 9 页根节点 1 行 | App.spec 冒烟锚点经 AppHeader 不受影响；HomeView h1 删除需复核 App.spec 断言路径（锚点由 AppHeader 满足） |
| 2 患者域 | 检索/建档/详情三页全量改造（§9.4-①②③） | 3 SFC + patientDisplay.ts 增词表 | spec：详情页 danger tag 锁定、解冻成对动作断言兼容 loading 守卫 |
| 3 收费域 | 划价/退费/一日清单三页全量改造（§9.4-④⑤⑥） | 3 SFC | spec 密集区（8 用例）：findComponent 定位的 ElSelect/ElDatePicker 组件类型保持；在途守卫形态零改动 |
| 4 药房域 | 字典/工作台/退药三页全量改造（§9.4-⑦⑧⑨） | 3 SFC | **与 W-22 fix PR 协调**：fix 先行则本批在其上叠加；同 PR 则 fix 修复面独立 commit；radio 组件类型保持 |
| 5 尾部 | bigscreen 存量两文件暗色化（§9.5.1）；portal 零改动确认 | bigscreen 2 SFC | App.spec 冒烟锚点「富云数据大屏」禁改名 |

#### 9.8.2 回归红线（specs 断言零回退）

审计背书：存量 22 个视图 spec 用例全部锁**业务行为**（出网调用与参数、前置拦截零出网、在途守卫、状态映射语义、文案锚点），**零样式断言**——视觉改造与断言天然解耦。规则四条：

1. **改实现不改断言为默认**：任何视觉/结构改动不得以「顺手更新断言」收尾；断言文件在改造批次中理想状态是零 diff。
2. **文本锚点禁改名**：「医护工作站」「富云患者门户」「富云数据大屏」「未登录用户」及各 spec 回显的业务文案（脱敏证件号、冻结原因提示等）——改动即回退。
3. **组件类型禁替换**：spec 以 `findComponent(ElSelect/ElDatePicker/ElRadio/...)` 定位的控件不得换成原生或他类组件（退药模式 radio、退费状态筛选 select、清单日期 picker 等）；确因交互升级必须换型 → **停止，登记 TASK.md 待决策**，不得自行改断言。
4. **业务语义映射禁漂移**：`patientStatusTagType`（FROZEN=danger）、分页 0 基转换、金额 string 透传等断言锁定的行为契约，改造只叠加视觉层不修改语义。

断言失效处置序列：先查「是否本批改动破坏了断言锁定的行为」（实现回退修正）；再查「断言是否依赖了本方案明令保持的组件类型/文本」（按第 3 条停止升级）；**不存在**「断言锁的是样式所以改断言」的路径（审计已证断言零样式锁定）。

#### 9.8.3 W-22 边界注记（不冲突声明）

- W-22⑥（pharmacy 三页动作按钮在途防抖）、W-22⑦（quantity/returnQuantity 裸 parse）归 fix PR：批次 4 开工前确认其已合入或同 PR 分 commit；本方案改造面**不实现、不移除、不重构**这两项修复形态，仅叠加视觉/结构层（§9.4-⑧⑨ 已逐处标注）。
- 其余 W-22 条目（①-⑤⑧⑨）均在后端，与前端改造零交集。
- 患者详情冻结/解冻守卫、退药检索空态等不在 W-22 清单的缺口，由本方案 §9.4/§9.6 交付（已在逐页规格标注归属）。

#### 9.8.4 每批门禁

五连门禁（web 宪法 C.4/C.5，CI 与本地同源）：`pnpm lint && pnpm format:check && pnpm type-check && pnpm test && pnpm build`（`pnpm audit` 随 frontend job 主链）。批次含视觉验收：`prefers-reduced-motion: reduce` 抽检本批新增动效全部降级直达终态（§7.6-5）。

### 9.9 落地自查清单（存量优化版 self-check）

供各批次实现者与 taste-skill 打磨者对照，每批全部通过方可交付：

1. 本批页面根节点挂 `.fuy-page`；页内 720/880/1080 私有 max-width 已按两档裁决收敛（表单/详情卡 880，列表全宽）。
2. 本批表格容器挂 `fuy-dense`；金额/数量/计数列 `.fuy-num` 且右对齐；无残留 `size="small"` 与 `fuy-dense` 双轨混用（fuy-dense 唯一）。
3. 页内颜色零裸 hex（workstation）；一切 `--el-*` 覆盖经 `.fuy-*` 挂类或 `:root:root`，无全局裸改 `.el-*`。
4. 本批 scoped 块中已被工具类收编的规则（`*-bar`/小节题/合计条）已删除，无半收编残留；无未使用的样式规则与死代码。
5. ElMessageBox 使用页均已手动引 message-box 样式（F-1 在批次 0 全量闭合，逐页复核）。
6. 空态 description 为业务口径（非「暂无数据」）；加载态满足「首屏骨架 / 刷新 v-loading」二分（§4.4）。
7. 每个按钮三态齐全（loading/守卫/错误驻留），双击零二次出网——W-22 范围按钮除外（fix PR 交付，本批不实现不破坏）。
8. 新增动效全部复用 motion.css 既有类；`prefers-reduced-motion` 下降级直达终态；零新 keyframes、零 width/height 动画。
9. spec 文件零 diff（理想态）或 diff 仅为 §9.8.2 明令事项的 TASK.md 决策产物；文本锚点与 findComponent 组件类型全部原样。
10. 五连门禁全绿 + 本批页面键盘可走通主流程（检索→详情；录入→提交；队列→选单）+ 焦点环可见。

---

## 附：与既有形态的兼容声明

本规范不改动任何既有页面（patient/billing/pharmacy 六页与其 scoped 样式零触碰）；token 文件为纯新增，`main.ts` 各增一行 import 属 Task 13 交付面。三页落地时若与既有组件范式冲突（如患者检索组件行高），**以既有组件为准、新页适配**，禁止为视觉统一反向改造既有页（精准修改原则）。
