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

## 附：与既有形态的兼容声明

本规范不改动任何既有页面（patient/billing/pharmacy 六页与其 scoped 样式零触碰）；token 文件为纯新增，`main.ts` 各增一行 import 属 Task 13 交付面。三页落地时若与既有组件范式冲突（如患者检索组件行高），**以既有组件为准、新页适配**，禁止为视觉统一反向改造既有页（精准修改原则）。
