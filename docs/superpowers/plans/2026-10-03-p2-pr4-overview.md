# P2 PR-4 安全收敛包·多计划总纲（拆分架构与流程契约）

> **For agentic workers:** 本目录下 PR-4 拆为六份实现计划（PR-4A/B/C/E/D/F），按本总纲顺序连续执行。
> 每份计划独立走完整 superpowers 流程（见 §3 流程契约）；分册计划才是 SDD 执行的直接依据。

**制定日期：** 2026-10-03。**批准依据：** 用户 2026-10-03 两轮裁决（§1 D-28~D-31 与 D-32~D-35）+「多份实现计划一次执行、连续开工」流程指令。

**基线：** dev@f67343e（PR #63/#64 合入后）。**调研档案：** `.superpowers/pr4-research/`（r1/r2/r3）。

## 1. 用户裁决（2026-10-03 商讨答复，逐字固化不得推翻）

| # | 决策点 | 裁决 |
| --- | --- | --- |
| D-28 | W-37 挂载面 | **全量端点 403**（约 300 端点，非主控原推荐的 45 端点收敛面） |
| D-29 | W-40 空数据语义 | **方案 A + fail-closed**（无 ACTIVE 绑定行一律 403——须同批为演示/运维账号种 nurse_assignment 绑定行） |
| D-30 | W-66 多明细退药 UI | **纳入 PR-4**（PR-4B 承载：后端可退明细读面+弹窗多行化） |
| D-31 | 评审顺手包 | **全选**：D-3 分页慢回包+D-8/D-11 前端小修对（→PR-4B）；W-70③ 时区双跑 CI（→PR-4A）；D-4 大屏 WS 行 TTL（→PR-4C） |
| D-32 | 元素级 UI 访问控制范围 | **仅 workstation**（唯一多角色应用；portal 患者端/bigscreen 匿名大屏不适用） |
| D-33 | 元素控制粒度 | **按钮+功能面板级**（字段/列级不混入 RBAC——归 M02 脱敏体系 PrivacyMask） |
| D-34 | 不可见语义 | **无元素码全隐藏**（DOM 移除；不采禁用置灰混合形态） |
| D-35 | 元素控制+管理台归属 | **新增 PR-4F 册收尾**（六册顺序 A→B→C→E→D→F，F 依赖 D 册权限模型与登录链路底座） |

既有裁决重申：W-72 服务端强制（2026-10-03 上午，方案 A 字段保留服务端覆盖）；W-68/W-39 禁白名单化、组合①令牌附调+②哨兵限行（评审 A-5）；W-41 settle 仅 SELF_PAY 门；W-67 判别子跳过+visitType 净解；W-73 主案 dorny v4.0.3+some-with-excludes；W-27 仅号源 tick（GC21④ 不并入）；A-4 secondary 授权核验留 W-37 角色面后（PR-4D 内收敛或工单）。

**元素级控制安全理念（D-32~D-35 的设计根基，F 册 Global Constraints 必引）**：元素控制是 UX 层而非安全边界——无码隐藏仅移除入口，API 仍可被直调；每个受控元素对应的后端 API 必须挂 API 权限码由 403 强制（D-28 全量底座）。两体系一对一度是 F 册矩阵设计的硬约束。

## 2. 六份计划拆分（按业务模块/功能实现语义）

| 册 | 计划文件 | 范围（业务语义） | 联调面→e2e | 迁移 |
| --- | --- | --- | --- | --- |
| **PR-4A** | `2026-10-03-p2-pr4a-infra.md` | **工程与 CI 基座包**：W-73 CI 路径过滤修复+评审技术债快修批（B-3/W-65 裸 now、E-3 TODO、B-1 注释、E-2 await fail、A-7② 注记、C-3 registry 注记、B-2 NursingProperties @Validated、W-64 死变量、B-5 注释误引批量修正）+E-1 Seeder 补测+C-4/C-5 补课索引+W-70①②钉面+③时区双跑 CI | 无（纯工程）→**免 e2e** | V1112/V1113 |
| **PR-4B** | （A 合入后撰写）`2026-10-03-p2-pr4b-clinical-ops.md` | **临床操作面语义包**（nursing/pharmacy 操作人+同页质量）：W-72 服务端强制四域（含 A-4 primary 收敛）+前端去手输+gen:api 再生成+W-66 多明细退药（读面+弹窗多行化）+D-3 分页慢回包守卫+D-8/D-11 前端小修对 | PDA 执行链/摆药签收退药→**e2e** | 按需（W-66 读面预计零迁移） |
| **PR-4C** | （B 合入后撰写）`2026-10-03-p2-pr4c-board-ward.md` | **大屏通道与病区防线包**：W-39 哨兵限行+令牌携区+W-68 令牌附调+A-2 SUBSCRIBE 病区防线+W-40 方案 A **fail-closed**（含绑定行种子迁移）+D-5 randomUUID 降级三 app+D-4 大屏 WS 行 TTL | 大屏匿名/越区订阅→**e2e** | V111x 绑定行种子 |
| **PR-4E** | （C 合入后撰写）`2026-10-03-p2-pr4e-events-tickets.md` | **事件与工单收敛包**：W-67 死信分流（判别子跳过+fee.created visitType 净解）+W-47 患者读面审计+A-6 上报词表+频控+A-8 PDA 频控+W-41 payerType+W-27 号源 tick（含 T-R3-4 quorum TTL 探针实测）+TASK 过时项销项（W-38/61/62） | 收费页 payerType→**轻 e2e** | V111x visitType |
| **PR-4D** | （E 合入后撰写）`2026-10-03-p2-pr4d-rbac-full.md` | **全量 403 鉴权包**：全量端点×角色矩阵调研设计（约 300 端点按模块 Spec 权限语义推导归属，矩阵入计划附件）+权限点/角色/绑定种子迁移+403 拦截器+登录链路 permissions 填实+前端守卫收紧+403 矩阵 IT | 各角色登录面/侧栏/403 面→**e2e** | V111x 全量种子 |
| **PR-4F** | （D 合入后撰写）`2026-10-03-p2-pr4f-ui-perm.md` | **元素级 UI 访问控制与权限管理台包**（D-32~D-35）：perm_type=ELEMENT 第三命名空间+元素码种子（按钮+面板清单自 workstation 视图盘点，与 API 码一对一度硬约束）+`v-perm` 指令与 `hasPerm()` 双入口（无码全隐藏）+登录响应承载元素集（复用 D 册 UserVO.permissions 链路）+角色权限管理台（system 域管理端点族 CRUD+权限矩阵读写+@AuditLog 留痕+**管理端点族自挂 ADMIN 码入 403 矩阵**）+workstation 管理页（角色×权限矩阵编辑器）+PermissionRegistry 运行期刷新（Redis pub/sub，dict broadcast 先例镜像）+前端变更下次登录生效语义 | 管理台配置→不同角色登录界面差异→**e2e** | V111x ELEMENT 码种子 |

**执行顺序与理由：** A→B→C→E→D→F。A 先修 CI 路径过滤（后续每份 PR 的门禁精确触发，且 W-70③ 双跑为 B/C 的前端改动提供时区安全网）；**D 压轴于功能册**因 D-28 全量矩阵必须基于最终端点清单（B 的 W-66 会新增读面端点，C/E 不加端点——B/C/E 合入后端点面冻结，D 的矩阵一次成型不返工）；**F 收尾**因元素控制依赖 D 册的权限模型（ELEMENT 第三命名空间）、登录链路（permissions 承载）与 403 底座（安全一对一度），且 F 自身新增管理端点族的 403 码在 F 册内自挂补充。

**迁移号段纪律（总纲锁定）：** 全局最大 V1111；各册按实际落盘顺序取下一空号，CHANGELOG 先记再占；A 册占 V1112/V1113。

## 3. 流程契约（用户 2026-10-03 指令逐字固化——每册生命周期）

1. **SDD 执行**：subagent-driven-development——每任务全新 subagent 派发（brief=计划段原文抽取）+双结论审查（实现者 concerns 核实+仓库实况抽查）+修复环；ledger 落 `.superpowers/sdd/<册标识>/progress.md`。
2. **范围全量终验**：本册收口任务=后端全量 `mvn verify`（Testcontainers+JaCoCo 双阈值）+前端六连；终验发现的既有测试欠账就地修（D-21 纪律申报）。
3. **真机 e2e**（涉前后端联调设计时）：compose 真栈起服+浏览器真机走查本册联调面（B=PDA 执行/摆药签收退药；C=大屏匿名三端点/越区订阅拒；D=角色登录面 403/侧栏；E=收费页 payerType）；证据截图/断言留档 SDD 工作区 probe/。
4. **/code-review**：五路评审（A 安全/B 架构/C 数据/D 前端/E 测试）→合并去重→置信度终评（≥80=must-fix 修复环或呈报裁决）→门槛项收口。
5. **合入 dev**：PR 开出（body 含本册裁决与义务清单）→CI 六 job 全绿→merge→删分支。
6. **直接开工下一册**：基于合入后 dev 实况撰写下一册计划（writing-plans 技能）→循环；**中间零请示**（总纲批准即全链授权）。

## 4. 跨册约束（各册 Global Constraints 引用本节，不重复展开）

- 时区红线：业务日界一律 `now(HEALTHCARE_TZ)`；前端出网时刻带偏移 ISO+断言形态正则；前端回显钉北京钟面（W-70①钉面后任意 TZ 绿为验收口径）。
- modulith 零反向依赖；event_registry 唯一来源+payload_desc 只增不删（V1111 先例）；迁移禁改（A.4.1-3）。
- commitlint body 每行 ≤100 字符+push 前 `npx commitlint --from origin/dev --to HEAD` 自查。
- 每任务报告必含「装配清单（物理核对）」节。
- D-21 断言纪律；死代码零容忍；UTF-8/LF/中文注释。
- OpenAPI 契约变更（DTO 字段增删/可空化）须 docker run 一次性容器导出+gen:api 再生成入库（SPRING_MAIN_LAZY_INITIALIZATION 禁用）。
- `docs/progress/`、`.superpowers/` 不入提交面（评审/SDD 档案保留在档不入库——code-review-pr63 先例）。

## 5. 验收（PR-4 整体完成判据）

六册全部合入 dev；TASK.md 销项 W-37/38/39/40/41/47/61/62/64/65/67/68/70/72/73+评审门槛与顺手包全量+元素级 UI 访问控制交付（仅 workstation/按钮+面板级/无码全隐藏/管理台可配置运行期生效）；新登记工单（W-74 映射面/W-75 BillingProperties/W-76 A-4 secondary/W-77 脱敏投影/W-78 四发号器/W-79 GC21④/W-80 医保 settle 口径等）在案；P2 计划 §3 PR-4 完成标注回填（docs-only PR）→PR-5 收口。
