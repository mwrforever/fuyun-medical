# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

三端三类用户（依据 docs/specs/00-master-spec.md §1.1、§4.2 与 docs/plans/2026-09-20 §1.1）：

- **医护工作站 workstation**：医生 / 护士 / 药师 / 收费员 / 设备科等专业操作者，岗位工作台按 RBAC 角色配置（总 Spec FU-M01-11）；高密度、长时间、时间压力下的临床操作场景，错误代价高。
- **患者门户 portal**：患者本人，免登录自助预约；低焦虑、大字号、少步骤。
- **数据大屏 bigscreen**：诊区候诊叫号屏、护士站看板、IoT 看板；3–10 米外可读，无人值守常亮运行，经 query 参数书签化直部署。

终端形态还包括 PDA、床旁屏 / 门口屏、自助机、患者手机（H5/小程序预留，后端已支撑）。

## Product Purpose

富云（fuyun）大型医院管理系统（HIS）：面向二级~三级医院（1000 床位设计基线）的全院区业务闭环，覆盖「智慧服务—智慧医疗—智慧管理」三位一体，9 大业务域 20 个模块（门诊、住院、护理、药房、检验、影像、电子病历、手术、ICU、输血、收费医保、IoT 设备接入、资产、智慧病房、体检、互联网医院、运维院感、集成平台等）。成功 = 医护在高密度工作流里快而准地完成临床操作，患者低门槛自助办事，管理者实时看见全院运行状态。

## Positioning

华为云 IoTDA 医疗设备物联网全链路接入（设备 → IoTDA → AMQP → 库 → WebSocket 推送到端）+ 模块化单体全业务闭环——通用 HIS 厂商与通用 IoT 平台都无法完整复制这一组合。

## Operating Context

- 部署形态：nginx :80 唯一入口（三前端静态资源 + `/api` 反代 + `/ws` WebSocket 升级）；backend :8080 无状态不出宿主端口；PostgreSQL 16 + TimescaleDB / Redis / RabbitMQ / MinIO（dev）。
- 实时链路：STOMP over WebSocket（`/ws/iot` 主题清单见 docs/specs/modules/14-iot.md），每 app 单 Client 实例、库内建重连；无推送主题的低频快照才轮询。
- 合规背景：等保 2.0 三级（身份鉴别 / 访问控制 / 审计日志留存 ≥6 个月 / TLS）；互联网诊疗监管合规（M18）；个保法脱敏展示（FU-M02-06）。
- 使用环境：院内 PC 工作站为主（workstation 密度优先），大屏常亮运行，患者用手机浏览器。

## Capabilities and Constraints

已实现功能面（web/ 三应用，约 50 个 Vue 组件）：

- workstation：38 个页面级视图 / 13 业务域——患者域（建档 / 检索 / 详情）、门诊（挂号收费 / 分诊台 / 医生站）、收费（划价结算 / 退费审批 / 一日清单）、药房（药品字典 / 发药工作台 / 退药 / 住院审方 / 摆药）、护理（护士站 / 执行工作台 / 不良事件 / PDA 扫码）、住院（入院 / 床位图 / 医生站 / 转抄 / 出院）、IoT 管理 7 页、病区视图 3 页（输液看板 / 呼叫工作台 / 冷链台账）、权限管理。
- portal：首页 + 免登录预约页。
- bigscreen：首页遥测总览、候诊叫号屏、IoT 看板、护士站看板。

技术约束（宪法级，一切视觉重设计不得违背）：

- Element Plus 2.14.5 仅 workstation（unplugin 按需引入）；echarts 6.1.0 仅 bigscreen；portal 无 UI 库纯自绘。更换组件库须先修 web/AGENTS.md C.2 与总 Spec §1.2（宪法修订流程）。
- 样式 = 纯 CSS 自定义属性 + scoped CSS；禁 Tailwind / UnoCSS（设计文档 §7.4 红线）。
- Element Plus 定制只走 CSS 变量映射层（workstation `styles/element-plus.css` 单向 `--fuy-* → --el-*`），禁改主题 SCSS 编译、禁全局裸改 `.el-*` 选择器。
- 动效：各 app `styles/motion.css` 为唯一 keyframes 来源；只动 transform / opacity，禁 width 动画；`prefers-reduced-motion` 全局兜底。
- token 命名空间 `--fuy-*`，每 app 各持一份（workstation 含 `--el-*` 映射层），禁上收 packages/shared。
- 类型契约 = openapi-typescript 生成物 `api.d.ts` 唯一来源；雪花 ID / 金额分值以 string 承载，前端不做金额计算。
- 图标库 = @element-plus/icons-vue 2.3.2（仅 workstation，2026-10-08 批次 2 裁决引入，侧栏树形菜单组/项图标专用；EP 同源图标族精确锁版）。
- web 宪法 C.7「谋建琢三段律」：任何 UI 变更强制加载 @ui-ux-pro-max（谋 / 建）与 @taste-skill（琢）技能依律执行。

已裁决事项（2026-10-07 用户拍板，后续会话不得反复）：

1. 前端现代化重设计覆盖**三端统一设计系统**，一次立项、按批次分端落地。
2. 视觉世界**全新替换**：旧 P1-PR5 设计规范降级为证据与反参照；「富云」品牌名与上述宪法技术红线保留。
3. workstation 侧栏导航**按岗位工作台彻底重组**（医生 / 护士 / 药师 / 收费员 / 设备科视角，总 Spec FU-M01-11），触及导航模型与权限过滤展示层。**（此项已被 2026-10-08 批次 2 裁决取代：废除岗位筛选导航——RBAC 已承载权限过滤，侧栏改为父子树形菜单 + 组/项图标 + 64px 收起图标条 + 悬浮 tooltip，menu.ts 岗位维度零死代码清除；权限口径不变=路由 meta 单一事实源 + hasRoutePermission 过滤。）**

批次 2 补充裁决（2026-10-08 用户拍板，后续会话不得反复）：

1. **设计流程换约**：旧 P1-PR5 UI 设计 spec 整体废弃，后续一切 UI 实现以 impeccable 流程产出物（DESIGN.md + surface brief 方向契约 + `.impeccable/mocks/paper-chart/` 样稿）为唯一设计依据。
2. **样稿 = 构图权威兼最低设计要求**：实现须在样稿之上更细致打磨（更美观精致、高级视觉、流畅动效交互、高渲染性能），禁以「还原样稿」为完成标准。
3. **合入免审批**（四册流水线执行期）：各册门禁全绿后直接合入 dev，免审批不免门禁（终审 finish review 与 /code-review 仍强制）。

明确未决：新品牌 favicon 替换、朱色白底精确校准——另行呈批（原「图标方案」未决项已由批次 2 裁决落地）。

## Brand Commitments

- 中文名「富云」，拼音 fuyun；三端标题「富云医护工作站 / 富云患者门户 / 富云数据大屏」（workstation 标题被 App.spec 冒烟测试锁定，禁改名）。
- 现主色为蓝黑墨 #1e2a44（「墨即操作」，2026-10-08 批次 1 换血定稿；旧临床蓝 #0369a1 已随「刷手服青绿→纸质病案」世界推翻一并废弃）。
- 无正式 logo 素材；favicon 为占位图（蓝底 #2563eb 圆角十字，与现主色不一致）。

## Evidence on Hand

- 总 Spec：docs/specs/00-master-spec.md；模块 Spec：docs/specs/modules/01..20-*.md。
- **现行设计唯一依据**：DESIGN.md（根目录，批次 1 documenter 按建成品重写）+ surface brief 方向契约 + `.impeccable/mocks/paper-chart/` HTML 样稿（构图权威）。
- 已废弃证据：docs/plans/2026-09-20-p1-pr5-m03-outpatient-ui-design.md 与 docs/plans/2026-09-23-p1-pr6-m05-nursing-ui-design.md（旧 P1-PR5 视觉规范，2026-10-08 废弃，文件头已插废弃声明，仅供证据不作权威）。
- 已落代码：三 app 各自 `src/styles/{tokens,motion,index}.css`；workstation 另有 element-plus.css 映射层；布局壳在 `web/apps/workstation/src/views/layout/`（MainLayout.vue + components/AppSidebar.vue、AppHeader.vue；菜单数据唯一来源为 views/layout/menu.ts 常量 MENU_ITEMS（2026-10-08 批次 1 自 AppSidebar 迁出，承载侧栏/折叠缩写/首页入口/岗位过滤四个消费面），权限过滤走路由 meta）。
- 不得伪造：无真实 logo / 品牌素材、无用户调研数据、无成品截图资产（除非另行提供）。

## Product Principles

1. **临床效率优先于表达**：密度、扫读性、零打扰动效是 workstation 的第一性；品牌感活在精确细节里，不抢操作。
2. **三端一体、各安其位**：统一设计语言之下，workstation 求准、portal 求稳、bigscreen 求远（3–10 米可读）。
3. **实时可信**：状态必须如实即时反映（STOMP 推送 + 状态语义唯一映射），医疗场景不容陈旧数据与含糊状态。
4. **合规与可及内建**：等保三级、脱敏展示、对比度 / 焦点环 / reduced-motion 是底线而非增强项。
5. **宪法约束内创新**：一切视觉创新在 Element Plus 主题化 + CSS 变量 + 既有分层架构内完成，不靠换库、不靠堆依赖。

## Accessibility & Inclusion

- 正文对比度 ≥4.5:1；焦点环永远可见；`prefers-reduced-motion` 全局兜底。
- 触控目标：portal ≥44px；workstation 高密度场景例外 ≥24px（docs/plans/2026-09-20 §1.2-4 定稿）。
