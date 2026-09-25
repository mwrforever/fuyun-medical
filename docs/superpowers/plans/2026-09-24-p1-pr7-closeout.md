# P1·PR-7「P1 收口」（门诊全流程真栈演示留痕 + TASK.md 销项核对与新发现盘点 + 计划完成项标注与 DoD 出口检查 + P1 终验小结）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 把 P1 阶段七个 PR 的最后一步「PR-7 P1 收口」交付完毕——扫描成果回流 dev（PR #50 六项修复 feat 分支→dev 纯 merge 回流 PR，CI 六 job 绿）+ 演示预检 W-28 销项（起栈 healthy + 号源对账 + fy.delay 深度检查 + 必要时 purge，执行后删行）+ 门诊挂号→就诊→收费→发药全流程真栈真机演示（workstation 端四环节，playwright-cli 浏览器逐环节截图与断言留证；医保走模拟通道口径留痕）+ portal 患者预约渠道演示与 workstation 链路衔接断言 + DoD 第 5 条三处审计行抽查（患者查询/收费票据/发药记录 DB 取行留证）与敏感数据脱敏抽查 + CHANGELOG 演示留痕（IOTDA 先例形态：前提/步骤化证据/口径注明）+ TASK.md 销项核对留痕（W-4/W-5/W-6 已销确认、W-7 按待批结论处置、D-8 已销确认、W-30~W-36/D-22~D-24 在案盘点、扫描待裁决 4 项按待批结论登记）+ P1 实施计划 §3 各 PR 完成项内联标注（merge hash 实取）与 §4 DoD 五条出口核验留证 + P1 终验小结（`docs/prompt/2026-09-XX-P1终验报告.md`，P0 骨架九节）+ 全量门禁（后端 verify + 前端六门禁）与 PR 质量门（/code-review）。

**架构：** 纯文档收口 PR——全部交付物为「登记与文档」（TASK.md / CHANGELOG.md / P1 实施计划 / P1 终验报告），零代码改动、零新增依赖、零迁移；演示与门禁为只读验证动作（compose 真栈起停、浏览器操作、psql/redis-cli/rabbitmqctl 查询、mvn verify 与 pnpm 六门禁），不修改任何 `*.java` / `*.ts` / `*.sql`。跨任务数据流：Task 1 产出新基线 dev@回流合并点 → Task 2 自新基线建 `feat/p1-pr7-closeout` 并完成销项对象现场复核（结论留痕 SDD 台账）→ Task 3 起栈并销项 W-28（产出 healthy 栈）→ Task 4/5 演示（产出截图与断言数值台账，供 Task 6 CHANGELOG 引用）→ Task 6 台账登记与留痕 → Task 7 完成项标注与 DoD 证据实取 → Task 8 终验报告 → Task 9 门禁与 PR。四项工作对应 P1 计划 §3 PR-7 小节原文（`docs/plans/2026-09-14-P1实施计划.md:96-98`）的四拆读（简报 §1.1），DoD 五条原文见同文件 :100-106（简报 §1.2）。

**技术栈：** 既有栈零新增——Java 17（Temurin）/ Spring Boot 3.5.16 模块化单体（compose 真栈六服务：nginx 1.30.4 / backend:8080 容器内 / timescale/timescaledb:2.29.2-pg16 / redis:8.10.1 / rabbitmq:4.3.5-management / minio）/ Vue 3.5.42 + TypeScript 三前端（workstation / portal / bigscreen，nginx :80 唯一入口，子路径 `/workstation/` `/portal/` `/bigscreen/`）；工具面 docker compose / gh CLI / playwright-cli（浏览器真机，先例 `.superpowers/gui-test-screenshots/`）/ psql / redis-cli / rabbitmqctl / python（commitlint 行宽自查）。**零代码改动**（出现任何后端/前端/迁移文件改动即越界，见 Global Constraints 2）。

**基线与分支：** 撰写时点 dev@9aa4c8b（PR #49 合并点）、回流源 `origin/feat/p1-pr6-m05-nursing@1d372c7`（PR #50 merge commit，扫描六项修复 PERF×3/ALGO×1/SEC×2 已合入该 feat 分支而未回流 dev——主控 2026-09-24 核实）；**Task 1 执行后新基线 = dev@回流合并点（执行时实取 hash）**，Task 2 自该基线建分支 `feat/p1-pr7-closeout`；计划撰写日期 2026-09-24；执行目录 `D:\code\project\fuyun-medical`（Git Bash），勿在计划交付物之外切分支/提交。**`<执行日>` 已由用户裁决定格 = 2026-09-25**（演示与 Task 3 预检在该日连执行；Task 1/2 纯技术操作于批准当时 2026-09-24 深夜先行）；终验报告文件名定格 `docs/prompt/2026-09-25-P1终验报告.md`。

---

> **本计划范围声明**：本计划只交付 P1 计划 §3 PR-7 小节的四项工作（原文 :96-98：门诊全流程真栈演示留痕（演示记录入 CHANGELOG）；TASK.md W-4/W-5/W-6 销项核对、本阶段新发现登记；计划完成项标注与 DoD 出口检查（§4）；P1 终验小结（docs/prompt/）），外加主控核实的两项执行前提：扫描成果回流 dev（Task 1，回流义务源于 `docs/progress/2026-09-23-性能安全扫描-终验.md` §六「待裁决 4 项建议并入 PR-7 销项核对盘面」与本会话主控指示）与演示预检 W-28 销项（Task 3，源于 TASK.md:51 工单「执行后删除本行」）。W-7 的**实施**不在本计划（未实施实锤已核实，处置列待批项呈报清单第 1 条由用户裁决）；扫描待裁决 4 项的**修复**不在本计划（仅按待批结论登记或不登记）。
>
> **not in scope（显式排除，判据 = P1 计划 §5「明确不在 P1 范围」原文 :108-110）**：M03 的 P1 条目（门诊护士站治疗 FU-M03-09/急诊绿道 FU-M03-10/自助机 FU-M03-11）；M06 完整（药库/药房库存/审方引擎/毒麻/抗菌药管理）；M13 完整（电子发票/财务对账/智能控费）；M20 集成引擎/HL7 接入/FHIR；M04/M07/M08/M09/M10/M11/M12/M14 完整/M15/M16~M19 全部功能；生产部署与真实医保联调（视用户侧环境另定）；E2E 框架。**PR-7 是收口 PR，对以上边界零越界**——演示只走已交付面，不为演示补任何功能。另显式排除：W-7 实施（待批项 1）、扫描待裁决 4 项修复（待批项 2）、真实医保联调补演（P1 计划决策 3 原文「真实医保联调环境用户侧准备后补演」）、大屏端演示（DoD 第 1 条口径为 workstation/portal 端；bigscreen 叫号页为 PR-5 已验证面，不构成 PR-7 义务）。

## Global Constraints（每个任务隐含遵守）

1. **JDK17 命令前缀**：所有 Maven 命令一律 `JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml ...`（系统默认 JDK21，忘加前缀即工具链红线违规；PR-6 计划 Global Constraints 1 先例）。
2. **零代码改动红线**：本计划全部交付物为文档与登记，改动面仅限四个文件——`TASK.md`、`CHANGELOG.md`、`docs/plans/2026-09-14-P1实施计划.md`、`docs/prompt/2026-09-XX-P1终验报告.md`（新建）；Task 1 回流 PR 为纯 merge 无新提交。执行任何任务中出现 `*.java`/`*.ts`/`*.vue`/`*.sql`/`pom.xml`/`ci.yml` 改动诉求即越界——停止并回报主控（W-7、扫描 4 项、演示中发现的功能缺陷均走登记，不走顺手修）。
3. **流程红线（P1 计划 :17 同款）**：一切变更走 feature 分支 → PR → checks 全绿 → merge；本计划两支 PR（Task 1 回流 PR + Task 9 收口 PR）同口径；merge 按仓库既有 merge commit 惯例（`Merge pull request #NN` 形态）。
4. **commitlint**：conventional commits、中文 subject、type 仅 docs/chore（本计划无 feat/fix 面）；body 每行 ≤100 字符，提交前 python 逐行 len 自查（命令见 Task 6 Step 4）。
5. **CI 口径（DoD 第 2 条口径差异，终验必留痕）**：DoD 第 2 条「CI 五 required checks」为 P1 计划撰写时口径；现行 CI 为**六 job**（changes / commitlint / hygiene / backend / verify / frontend / verify / images，`.github/workflows/ci.yml` 实测）——一律按六 job 执行，差异写入终验报告 §3 流程偏差。
6. **PR-7 纯文档 PR 的 CI 证据口径**：changes 路径过滤可能令 backend/frontend job 跳过——DoD 第 2 条 CI 证据 = **「Task 1 回流 PR（含代码改动）CI 六 job 全绿 + PR-7 本地全量门禁绿」组合**，终验报告 §1.2 按此组合记载，不得声称 PR-7 PR 自身跑过六 job。
7. **演示质量门**：演示为真机浏览器验证（playwright-cli 驱动，截图先例 `.superpowers/gui-test-screenshots/pr6-*`），**不是 API 冒烟**；每环节 = 具体页面 URL + 登录账号 + 按钮操作 + 断言 + 截图（命名 `pr7-*`），断言数值（visit_id/apptNo/settleNo/rxNo/审计行）执行时实取并记入 SDD 台账（Task 6 CHANGELOG 引用台账值）。
8. **纯文档任务的测试循环**：每任务 = 「执行 → 验证（grep / git diff / 表格核对命令与 Expected 输出）→ 提交」三步循环；**禁虚构单测**（本计划零测试代码交付；基线健康由 Task 9 全量门禁承载）。
9. **W-7 不展开实施**：主控 2026-09-24 核实——fuyun-iot 迁移止于 V403（无 V404+ 文本承载列迁移，`backend/fuyun-iot/src/main/resources/db/migration/iot/` 目录实测仅 V400~V403 四文件），`TelemetryIngestServiceImpl` 仍丢弃非数值行（`skipped_non_numeric` 口径在案 `TelemetryIngestServiceImpl.java:131`）；W-7 自述「P1 PR-1 开工前 fix PR 闭合」时点已过而未实施。处置列**待批项 1** 由用户裁决：分支 A = 转 TASK.md 改期登记（Task 6 Step 3d-A 改写说明列）；分支 B = PR-7 内补实施（属实现类 PR，**须另行增补计划，本计划自 Task 6 起阻断**）。
10. **扫描待裁决 4 项不展开修复**：BE-AUTHZ-GAP（全站授权缺口）/ BE-S1-01（portal 免登录退号 IDOR，置信 85）/ FE-S1-01（大屏令牌内联，置信 85）/ SEC-02 病区维度子面（`docs/progress/2026-09-23-安全扫描问题清单.md` :5/:15-18/:26/:27 在案）——本计划仅按**待批项 2** 的用户裁决登记入 TASK.md（分支 A）或不登记仅终验报告记载（分支 B），不改任何代码。
11. **医保演示口径**：主流程收费走自费（UI 实测口径——`web/apps/workstation/src/views/billing/PricingSettleView.vue:200` `payerType: 'SELF_PAY'` 固定）；医保模拟通道演示按 Task 4 Step 7 双档执行：7a 首选 API 级 preview（payerType=CITY_INS）实演留痕，7b 兜底「口径注明」降级（IOTDA 留痕先例形态，`CHANGELOG.md:629-636`）——降级时 CHANGELOG 注明「医保模拟拆分由 `InsuranceSimulatorAdapter`（统筹 60%/个账 20%/自付 20% 确定性拆分，`InsuranceSimulatorAdapter.java:25`）承载、行为证据 = PR-3 IT 全量覆盖」。
12. **P1 §5 边界**：Global Constraints not-in-scope 引用块原文为 PR-7 不得越界的边界；演示中缺某能力（如语音外放、真实医保）一律「口径注明」留痕，禁为演示补功能。
13. **演示环境**：`docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d`（deploy/.env 在位不入库）；六服务全 healthy 为演示前置；backend 镜像须以**回流后代码**重建（Task 3 Step 2：build + force-recreate）；backend 不发布宿主端口，DB/Redis/RQ 查询经 `docker compose exec`。
14. **敏感与卫生红线**：演示患者用虚构证件号/手机号（禁真实个人信息）；日志禁敏感明文（Task 5 Step 4 断言）；截图与 SDD 台账存 `.superpowers/`（不入 PR 提交面，PR-6 计划 Global Constraints 34 同款）；AGENTS.md 若有用户未提交改动勿动勿提交。
15. **先实测条款**：本计划引用的行号/计数/端点为 2026-09-24 撰写期实测；执行期发现漂移以实况为准修正并在 PR 描述登记（如 TASK.md 行号、页面按钮文案、`PricingSettleView.vue:200`）。
16. **编码规范**：注释/文档中文、标识符英文；UTF-8 无 BOM、LF 行尾、文件末单换行（pre-commit 与 CI hygiene 同源校验）。
17. **执行目录与分支**：`D:\code\project\fuyun-medical`（Git Bash）；Task 1 在 dev 远程侧操作回流 PR（本地仅 fetch 核验）；Task 2 起新分支 `feat/p1-pr7-closeout`；主控纯编排制（实现/审核派 subagent 串行）。
18. **质量门（不可跳过）**：Task 9 的 /code-review 插件审核为合并前置（PR-2 交接 §3 同款）；≥80 分 findings 必修清零——纯文档 PR 的 findings 以登记勘误方式闭环。

---

## 前置项（主控任务框架 9 任务 + 主控新事实 3 项 → 任务映射）

| # | 前置项 | 处置 | 落点 |
| --- | --- | --- | --- |
| 1 | 扫描成果回流 dev（feat/p1-pr6-m05-nursing@1d372c7 → dev，无新提交纯 merge，PR 描述引用终验报告） | Task 1（CI 六 job 绿后合并） | **Task 1** |
| 2 | PR-7 分支与前置核实（新基线建分支；W-7/D-8/W-4~W-6 销项对象现场复核，结论留痕） | Task 2（SDD 台账 task-2-recon.md） | **Task 2** |
| 3 | 演示预检 W-28（起栈 healthy；号源对账→fy.delay 深度→必要时 purge；执行后删行 + grep 零残留） | Task 3（TASK.md:51 删除） | **Task 3** |
| 4 | 门诊全流程真栈演示 workstation 端（挂号→就诊→收费→发药四环节真机；医保模拟通道口径留痕；截图与断言留证） | Task 4（台账 task-4-demo.md + 截图 pr7-*） | **Task 4** |
| 5 | portal 预约渠道演示（P1 计划决策 5）与 workstation 链路衔接；DoD 第 5 条三处审计行抽查 + 脱敏抽查 | Task 5（DB 取审计行 + UI 掩码截图 + 日志零明文断言） | **Task 5** |
| 6 | CHANGELOG 演示留痕（IOTDA 先例形态）+ TASK.md 收口（销项核对留痕/W-7 按待批结论/W-30~36・D-22~24 盘点/扫描 4 项按待批结论登记） | Task 6（两条 CHANGELOG 条目 + TASK.md 编辑） | **Task 6** |
| 7 | 计划完成项标注（P1 计划 §3 内联完成状态 + merge hash 实取，P0 先例形态）+ DoD 出口检查（§4 五条逐项核验留证） | Task 7（`gh pr list --state merged` 实取 hash；五条证据实取） | **Task 7** |
| 8 | P1 终验小结（`docs/prompt/2026-09-XX-P1终验报告.md`，P0 骨架九节：属性表 + DoD 五条核验 + 资产 + 偏差 + 遗留 + 结论 + 附录） | Task 8（新建文件） | **Task 8** |
| 9 | 全量门禁（后端 mvn verify + 前端六门禁，基线健康出口检查）+ PR（base=dev）+ /code-review 质量门 + merge 收尾 | Task 9 | **Task 9** |
| 10 | **主控新事实①**：W-7 未实施实锤（迁移止 V403 / 丢弃口径 :131 在案） | 不展开实施，列待批项 1 由用户裁决；Task 2 复核留痕、Task 6 Step 3d 分支执行 | **Task 2/6** |
| 11 | **主控新事实②**：扫描成果未回流 dev（PR #50 合并点在 feat 分支） | Task 1 回流 PR | **Task 1** |
| 12 | **主控新事实③**：扫描待裁决 4 项（BE-AUTHZ-GAP / BE-S1-01 / FE-S1-01 / SEC-02 病区子面） | 列待批项 2（登记去向与形态由用户裁决）；Task 6 Step 3f 分支执行 | **Task 6** |

## 文件结构（本计划全量改动面）

| 动作 | 文件 | 职责 |
| --- | --- | --- |
| （无文件改动） | Task 1 回流 PR：`feat/p1-pr6-m05-nursing@1d372c7` → dev | 纯 merge 回流扫描六项修复，PR 描述引用 `docs/progress/2026-09-23-性能安全扫描-终验.md` |
| Create | `.superpowers/sdd/2026-09-24-p1-pr7-closeout/task-2-recon.md` | Task 2 销项对象复核结论表（**不入 PR 提交面**，供 Task 6 消费） |
| Create | `.superpowers/sdd/2026-09-24-p1-pr7-closeout/task-4-demo.md`、`task-5-demo.md` | Task 4/5 演示断言数值台账（**不入 PR 提交面**，供 Task 6 CHANGELOG 引用） |
| Create | `.superpowers/gui-test-screenshots/pr7-*.png` | 演示逐环节截图（**不入 PR 提交面**，质量门证据） |
| Modify | `TASK.md` | Task 3 删 W-28 行（:51）；Task 6 销项核对（无新增行）/ W-7 按待批分支 A 改写说明列 / 扫描 4 项按待批分支 A 新增登记 |
| Modify | `CHANGELOG.md` | Task 6 演示留痕条目（IOTDA 先例形态）+ PR-7 收口条目 |
| Modify | `docs/plans/2026-09-14-P1实施计划.md` | Task 7 §3 七个 PR 条目内联完成标注（merge hash 实取）+ §4 五条出口核验行 |
| Create | `docs/prompt/2026-09-XX-P1终验报告.md`（XX=执行日） | Task 8 P1 终验小结（P0 骨架九节） |

---

### Task 1: 扫描成果回流 dev（PR #50 成绩回流 PR——无新提交纯 merge，CI 六 job 绿后合并）

**Files:** 无文件改动（纯 merge；`docs/progress/2026-09-23-性能安全扫描-终验.md` 仅在 PR 描述引用，不修改）

**Interfaces:**
- Consumes: `origin/feat/p1-pr6-m05-nursing@1d372c7`（PR #50 merge commit，六项修复 PERF×3/ALGO×1/SEC×2 + V900 迁移等交付面已在该分支）；`origin/dev@9aa4c8b`（PR #49 合并点，撰写时点基线）。
- Produces（后续任务依赖的冻结面）: **新基线 `dev@<回流合并点 hash>`**（执行时实取，Task 2 建分支消费）；回流 PR 编号与 CI run 编号（Task 8 终验报告 §1.2/§2 证据引用）。

- [ ] **Step 1: 核实回流源与基线（进入条件，可机检）**

```bash
cd /d/code/project/fuyun-medical
git fetch origin --prune
git rev-parse --short=7 origin/feat/p1-pr6-m05-nursing   # 预期：1d372c7
git rev-parse --short=7 origin/dev                        # 预期：9aa4c8b
git merge-base --is-ancestor origin/dev origin/feat/p1-pr6-m05-nursing && echo ANCESTOR_OK
# 预期：输出 ANCESTOR_OK（dev 是回流源的祖先，纯 merge 无冲突面）
gh pr view 50 --json state,baseRefName,headRefName,mergeCommit
# 预期：state=MERGED、base=feat/p1-pr6-m05-nursing、mergeCommit.oid 前缀 1d372c7（扫描修复合入 feat 分支实证）
```

任一不符（如 origin/feat 分支已前移、dev 已含 1d372c7）：停止并回报主控，以实况重新定回流源。

- [ ] **Step 2: 建回流 PR（head=既有 feat 分支，base=dev；不新建分支不产生新提交）**

```bash
gh pr create --base dev --head feat/p1-pr6-m05-nursing \
  --title "chore: 性能安全扫描成果回流 dev（PR #50 六项修复纯 merge）" \
  --body-file .superpowers/pr7-backflow-body.md
```

PR body 必含四段（写入 `.superpowers/pr7-backflow-body.md`，该文件不入提交面）：
① 来源：PR #50（fix/perf-security-scan-20260923 → feat/p1-pr6-m05-nursing，merge commit `1d372c7`）——性能安全扫描任务六项修复（PERF×3 / ALGO×1 / SEC×2），完整交付面与终验结论见 `docs/progress/2026-09-23-性能安全扫描-终验.md`；
② 形态：纯 merge 无新提交（`git diff 9aa4c8b...1d372c7` 即扫描修复面）；dev@9aa4c8b 为回流源祖先，无冲突；
③ 待裁决 4 项说明：BE-AUTHZ-GAP / BE-S1-01（portal 退号 IDOR）/ FE-S1-01（大屏令牌内联）/ SEC-02 病区维度子面**不在本 PR**（契约变更与通道演进项，登记去向随 PR-7 收口由用户裁决）；
④ CI 预期：本 PR 含后端与前端代码改动，六 job（changes/commitlint/hygiene/backend / verify/frontend / verify/images）必跑全绿。

- [ ] **Step 3: CI 六 job 绿核验 + 改动面核对**

```bash
gh pr checks <回流PR号>          # 预期：六项全 pass（changes/commitlint/hygiene/backend / verify/frontend / verify/images）
gh pr diff <回流PR号> --name-only | head -40
# 预期：全部为扫描修复面文件（后端 java/测试/迁移 V900/前端 ts 等），与本计划四文件改动面零交集
```

- [ ] **Step 4: 合并（merge commit 惯例）+ 实取新基线**

```bash
gh pr merge <回流PR号> --merge    # 仓库惯例 merge commit（git log --merges 实证形态）
git fetch origin
git rev-parse --short=7 origin/dev   # 记录：新基线 hash（写入 SDD 台账 task-1-report.md，Task 2 消费）
```

- [ ] **Step 5: 留痕**

`.superpowers/sdd/2026-09-24-p1-pr7-closeout/task-1-report.md`：回流 PR 号 / merge hash（新基线）/ CI 六 job 结论 / diff 文件计数。本任务零提交（回流 PR 不经本地分支）。

---

### Task 2: PR-7 分支与前置核实（新基线建分支 + 销项对象现场复核，结论留痕供 Task 6 用）

**Files:**
- Create: `.superpowers/sdd/2026-09-24-p1-pr7-closeout/task-2-recon.md`（复核结论表，不入提交面）

**Interfaces:**
- Consumes: Task 1 新基线 `dev@<回流合并点>`。
- Produces: `task-2-recon.md` 复核结论表（Task 6 各步骤的执行依据——每行 = 对象 / 复核命令 / 结论 / Task 6 处置动作）；分支 `feat/p1-pr7-closeout`（后续任务工作分支）。

- [ ] **Step 1: 自新基线建分支**

```bash
git checkout -b feat/p1-pr7-closeout origin/dev
git log --oneline -1 --format="%H %s"   # 预期：回流合并点提交（标题 Merge pull request #<回流PR号>...）
```

- [ ] **Step 2: W-4/W-5/W-6 已销复核（DoD 第 4 条对象）**

```bash
grep -nE "^\| W-4 \||^\| W-5 \||^\| W-6 \|" TASK.md
# 预期：零命中（三条已随 PR-1 销项，简报 §2.5 grep 实证）；命中即偏差——回 Task 6 登记后不得静默处置
```

- [ ] **Step 3: D-8 已销复核（DoD 第 4 条对象）**

```bash
grep -n "D-8" TASK.md
# 预期：零命中（D-8 裁决结论已回填销项，简报 §2.5 grep 零命中实证）
```

- [ ] **Step 4: W-7 在案实锤复核（DoD 第 4 条唯一在案残留对象）**

```bash
grep -n "| W-7 |" TASK.md
# 预期：命中 TASK.md:39（简报 §2.1 原文）
ls backend/fuyun-iot/src/main/resources/db/migration/iot/
# 预期：仅 V400~V403 四文件（无 V404+ 文本承载列迁移——W-7 未实施实锤之一）
grep -n "skipped_non_numeric" backend/fuyun-iot/src/main/java/com/fuyun/iot/service/impl/TelemetryIngestServiceImpl.java
# 预期：命中 :131 附近（非数值行丢弃口径仍在——W-7 未实施实锤之二）
```

结论写入台账：**W-7 未实施**，处置按待批项 1 用户裁决分支（A=Task 6 Step 3d-A 改写登记；B=本计划阻断上报）。

- [ ] **Step 5: 本阶段新发现盘点复核（DoD 第 4 条「新发现待决策项登记」的在案确认）**

```bash
grep -nE "^\| W-3[0-6] \|" TASK.md      # 预期：W-30~W-36 七行命中（:52-:58，简报 §2.2）
grep -nE "^\| D-2[234] \|" TASK.md      # 预期：D-22/D-23/D-24 三行命中（:15-:17，简报 §2.3）
```

在案确认即可（七行 + 三行均为 PR-6 收口期登记，内容不改，Task 6 仅核对留痕、Task 8 计入遗留节）。

- [ ] **Step 6: 扫描待裁决 4 项在案复核**

```bash
grep -nE "BE-AUTHZ-GAP|BE-S1-01|FE-S1-01|SEC-02" docs/progress/2026-09-23-安全扫描问题清单.md | head -8
# 预期：:5（三项 2026-09-24 待裁决留痕）、:15-18（SEC-02 含病区维度子面）、:26（BE-S1-01）、:27（FE-S1-01）
```

- [ ] **Step 7: 留痕成表**

`task-2-recon.md` 落五列表（对象 / 复核命令 / 命中或零命中结论 / 依据锚 / Task 6 处置动作），行集 = W-4、W-5、W-6、D-8、W-7、W-30~36、D-22~24、扫描 4 项。本任务零提交（台账不入库，分支已建）。

---

### Task 3: 演示预检 W-28（起栈 healthy + 镜像重建 + 号源对账→fy.delay 深度→必要时 purge + 销项删行）

**Files:**
- Modify: `TASK.md`（删 W-28 行，撰写期实测 :51）

**Interfaces:**
- Consumes: Task 1 新基线（镜像以回流后代码构建）；`deploy/docker-compose.yml` + `deploy/.env`。
- Produces: 六服务全 healthy 的演示栈（Task 4/5 消费）；TASK.md W-28 已销（Task 6 Step 3a 核对）。

- [ ] **Step 1: 起栈全 healthy**

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
sleep 45
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps
# 预期：六服务（postgres/redis/rabbitmq/minio/backend/nginx）STATUS 列全 (healthy)
```

- [ ] **Step 2: backend 镜像以回流后代码重建（演示栈 = 回流后基线）**

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml -pl fuyun-app -am package -DskipTests
docker build -f backend/Dockerfile -t fuyun/backend:dev backend
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --force-recreate backend
sleep 30
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps backend   # 预期：(healthy)
```

- [ ] **Step 3: W-28 步①——号源对账（Redis 池键余量 vs DB 池行余量抽样比对）**

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T redis \
  redis-cli --scan --pattern 'fy:outpatient:pool:*'
# 记录扫描到的池键（无键=当日池未预热，属正常态，登记后继续）
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T redis \
  redis-cli GET fy:outpatient:pool:<池键中的 poolId>
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres psql -U fuyun -d fuyun \
  -c "SELECT id, sched_date, total_quota, used_count, total_quota - used_count AS remaining FROM outpatient.appt_number_pool WHERE deleted = 0 AND sched_date >= CURRENT_DATE ORDER BY id;"
# 对账判据：每个已预热 Redis 池键的值 = 对应池行 remaining（漂移即登记台账——日对账窗口缓冲语义下的抽样执行）
```

- [ ] **Step 4: W-28 步②——fy.delay 队列深度检查**

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T rabbitmq \
  rabbitmqctl list_queues name messages | grep -i delay
# 记录输出（队列名与 messages 深度；无 delay 队列命中=零积压，登记后跳过步⑤）
```

- [ ] **Step 5: W-28 步③——必要时 purge（仅深度 >0 的 delay 队列）**

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T rabbitmq \
  rabbitmqctl purge_queue <步④输出的延迟队列名>
# 预期：purged 0 条或积压帧数；丢弃的释放动作由兜底路径回收（TASK.md W-28 行原文口径）
```

- [ ] **Step 6: 销项 W-28（执行后删除本行——工单原文口径）**

```bash
grep -n "| W-28 |" TASK.md        # 预期：命中 :51（先实测行号，漂移以实况为准）
# 编辑 TASK.md：删除 W-28 整行
grep -n "W-28" TASK.md            # 预期：零命中（零残留验证）
```

- [ ] **Step 7: 提交（commitlint 自查先行）**

```bash
python - <<'EOF'
for line in open('commitmsg.txt', encoding='utf-8', encoding_errors='replace'):
    line = line.rstrip('\n')
    if len(line) > 100:
        print('OVER100:', len(line), line)
EOF
# 先把提交信息写入 commitmsg.txt 再跑自查；输出仅允许「无 OVER100 行」，随后用其内容提交
git add TASK.md
git commit -m "chore(task): 演示预检 W-28 销项——fy.delay 积压清理三步执行完毕"
```

（提交信息 body 如有，逐行 ≤100 字符同自查；下同，不再重复。）

---

### Task 4: 门诊全流程真栈演示·workstation 端（挂号→分诊报到→就诊→收费→发药→诊毕四环节 + 医保模拟通道口径留痕）

**Files:**
- Create: `.superpowers/sdd/2026-09-24-p1-pr7-closeout/task-4-demo.md`（断言数值台账，不入提交面）
- Create: `.superpowers/gui-test-screenshots/pr7-*.png`（截图，不入提交面）

**Interfaces:**
- Consumes: Task 3 healthy 栈；演示账号 `doctordemo` / `Fuyun@2026`（V704 种子，口令与 V303 admin 同源——`backend/fuyun-system/src/main/resources/db/migration/system/V704__create_practice_grant.sql:428-429` 注释口径）；`admin` / `Fuyun@2026`（V303 种子，API 造数用）。
- Produces: `task-4-demo.md` 台账（visit_id / settleNo / rxNos / 发药回执 / 医保拆分实测值——Task 5 审计抽查与 Task 6 CHANGELOG 引用）；`pr7-*` 截图集（Task 9 PR 描述引用路径）。

演示事实基线（撰写期实测，页面细节以 PR-5 计划为形态权威）：workstation base URL `http://localhost/workstation/`（nginx :80 唯一入口）；挂号收费联动页路由 `/outpatient/registration-charge`、分诊台 `/outpatient/triage-board`、医生站 `/outpatient/doctor-station`、划价结算 `/billing/pricing-settle`、发药工作台 `/pharmacy/dispense-workbench`、患者建档 `/patient/create`、患者检索 `/patients`（`web/apps/workstation/src/router/index.ts` 实测）。页面操作流与按钮面 = PR-5 计划 Task 13 Step 1/2 页面规格（`docs/superpowers/plans/2026-09-20-p1-pr5-m03-outpatient.md:1769-1771`：挂号收费页「选患者→选排班/号别→挂号→挂号费收费」、医生站「候诊列表→接诊→开单（项目码+数量）/开方→诊毕（去向下拉+在途单据确认勾选）」）；发药工作台三步链 `POST /api/v1/pharmacy/dispenses/{no}/pick|verify|issue`（`DispenseController.java:43-73` 实测）。

- [ ] **Step 0: 演示数据前置准备（API 造号源 + UI 造患者）**

0a. 登录取 token（admin，经 nginx 入口）：

```bash
TOKEN=$(curl -s -X POST http://localhost/api/v1/system/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"loginName":"admin","password":"Fuyun@2026"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
echo "token_len=${#TOKEN}"   # 预期：非 0（LoginResponse.accessToken 字段实测）
```

0b. 造排班模板（workstation 无排班管理页，API 承载；值取 OutpatientFullFlowIT 造数口径 `DEPT_CODE="DEP-IT-FLOW"`、doctorId="3"——`OutpatientFullFlowIT.java:85/:449` 实测）：

```bash
curl -s -X POST http://localhost/api/v1/outpatient/schedule-templates \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
  "deptCode": "DEP-IT-FLOW", "doctorId": "3",
  "effFrom": "<执行日>", "weekPattern": "1111111",
  "session": "MORNING", "apptType": "GENERAL",
  "slotStart": "08:00", "slotEnd": "12:00", "slotQuota": 20 }'
# 预期：2xx 返回模板 id（记录入台账；字段清单 = ScheduleTemplateSaveRequest :33-50 实测）
```

0c. 放号生成（T+N 批量展开）：

```bash
curl -s -X POST http://localhost/api/v1/outpatient/schedules/generate \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"endDate": "<执行日+1>", "days": 2}'
# 预期：2xx（生成排班与池行；ScheduleGenerateRequest endDate/days 实测）
curl -s "http://localhost/api/v1/outpatient/number-pools/available?deptCode=DEP-IT-FLOW&date=<执行日>&apptType=GENERAL" \
  -H "Authorization: Bearer $TOKEN"
# 预期：当日可约池行非空（演示号源就位断言）
```

0d. UI 建演示患者（playwright-cli 打开 `http://localhost/workstation/patient/create`，登录 doctordemo / Fuyun@2026）：录入虚构姓名（如「演示患者甲」）、虚构 18 位证件号（如 `110101199001010011`——**虚构值，禁真实个人信息**）、虚构手机号（如 `13900000001`）等必填项提交；断言建档成功回显 patientId（记录入台账，Task 5 portal 预约复用该患者证件号）。截图 `pr7-patient-create.png`。

- [ ] **Step 1: 挂号环节（RegistrationChargeView）**

浏览器打开 `http://localhost/workstation/outpatient/registration-charge`（doctordemo 会话）——操作链（PR-5 计划 :1769 页面规格）：选患者（检索框输入演示患者姓名/证件号选定）→ 选当日号源（DEP-IT-FLOW / GENERAL）→ 挂号（WINDOW 渠道）→ 断言：页面直出 **visit_id**（形态 `O<yyyyMMdd><5 位流水>`，CF-3 签发锚）→ 挂号费收费（手工计费→预结算→结算 CASH，复用 billing 面）→ 断言结算回执（settleNo 呈现）。截图 `pr7-reg-1.png`（visit_id 直出）、`pr7-reg-2.png`（结算回执）。visit_id / settleNo 记入台账。

- [ ] **Step 2: 分诊报到环节（TriageBoardView）**

`http://localhost/workstation/outpatient/triage-board`——操作：定位演示患者行 → 报到（check-in）→ 叫号（call）→ 断言：该患者队列状态转已叫（页面状态标签；bigscreen 非本次义务，不查）。截图 `pr7-triage-1.png`。

- [ ] **Step 3: 就诊环节（DoctorStationView）——接诊/开单/开方（诊毕留待 Step 6）**

`http://localhost/workstation/outpatient/doctor-station`——操作链（PR-5 计划 :1771 页面规格）：候诊列表选定演示患者 → 接诊 → 患者上下文可见 → 开单（检查类申请单：项目码+数量，quantity 显式数字）→ 开方（处方：药品+数量，调 M06 开方 API）→ 断言：申请单行 PENDING_FEE、处方引用行在案（页面状态呈现）。截图 `pr7-doc-1.png`（接诊）、`pr7-doc-2.png`（开单+开方后单据列表）。

- [ ] **Step 4: 收费环节（PricingSettleView）**

`http://localhost/workstation/billing/pricing-settle`——操作链：输入 Step 1 的 visitId → 查询费用（挂号费已结 + 就诊费用行清单）→ 预结算 → 结算（CASH 单行，金额 = preview 回传 totalAmount string 透传）→ 断言：结算成功回执（settleNo #2）、处方转待发药。截图 `pr7-charge-1.png`（费用清单）、`pr7-charge-2.png`（结算回执）。settleNo #2 记入台账（Task 5 收费票据审计行锚）。

- [ ] **Step 5: 发药环节（DispenseWorkbenchView）**

`http://localhost/workstation/pharmacy/dispense-workbench`——操作链：检索 Step 3 处方（PENDING_DISPENSE 态）→ 配药（pick）→ 核对（verify）→ 发药签名（issue）→ 断言：发药完成回执（单号回显，`POST /dispenses/{no}/issue` 链路）。截图 `pr7-dispense-1.png`。发药单号记入台账（Task 5 发药审计行锚）。

- [ ] **Step 6: 诊毕（DoctorStationView，直线段终点）**

回 `http://localhost/workstation/outpatient/doctor-station`——操作：选定患者 → 诊毕（去向 disposition 下拉选出院/取药等 + 在途单据确认勾选——已收费单据呈已结清态）→ 断言：诊毕成功；DB 终态验证：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres psql -U fuyun -d fuyun \
  -c "SELECT visit_id, status, finished_at IS NOT NULL AS finished FROM outpatient.visit WHERE visit_id = '<Step1 visitId>';"
# 预期：finished = t（诊毕时点落库；status 终态以 VisitStatus 词表实况为准记录台账）
```

截图 `pr7-finish-1.png`。

- [ ] **Step 7: 医保模拟通道口径留痕（双档执行）**

7a（首选，API 级实演）：

```bash
# ① 取挂号费收费项目 id（itemCode = Step 1 手工计费实际录入值）
curl -s "http://localhost/api/v1/billing/charge-items/by-code/<挂号费itemCode>" -H "Authorization: Bearer $TOKEN"
# ② 维护医保对照（V601 对照 ACTIVE 后计费快照才携带 nhsaCodeSnapshot——SettlementServiceImpl 贯标校验前置）
curl -s -X POST http://localhost/api/v1/billing/insurance-mappings/upsert \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
  "chargeItemId": <①返回id>, "mapType": "TREATMENT", "nhsaCode": "DEMO-NHSA-001",
  "catalogVersion": "2026.0", "selfPayRatio": 0.0, "insurancePayType": "CLASS_A" }'
# ③ 第二患者（或同患者再挂号）当日挂号 → 手工计费挂号费（经 UI 或 POST /api/v1/billing/fees/manual）→ 医保预结算：
curl -s -X POST http://localhost/api/v1/billing/settlements/preview \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"visitId": "<第二visitId>", "patientId": "<patientId>", "payerType": "CITY_INS"}'
# 断言（InsuranceSimulatorAdapter.java:25 确定性拆分口径）：
#   pooledAmount = total 的 60%、acctPayAmount = 20%、selfPay 项 = 20%（整分 HALF_UP）；
#   五拆分之和 = total（勾稽）；insSettleNo 前缀 "SIM-"；insurance_call_log 落 2102 行（psql 查验）
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres psql -U fuyun -d fuyun \
  -c "SELECT txn_code, status, visit_id FROM billing.insurance_call_log ORDER BY id DESC LIMIT 3;"
```

7b（降级口径，触发条件 = 7a 对照链维护受阻超 30 分钟或任一调用非 2xx 且非操作失误）：按 IOTDA 先例「口径注明」形态处置——CHANGELOG 演示条目（Task 6 Step 1）注明：「医保结算演示走模拟通道口径：UI 收费面 payerType 固定 SELF_PAY（`PricingSettleView.vue:200` 实测），医保模拟拆分由 `InsuranceSimulatorAdapter`（统筹 60%/个账 20%/自付 20% 确定性拆分）承载，行为证据 = PR-3 IT 全量覆盖」；终验报告 §3 登记该降级与原因。**执行 7b 前先在台账记录 7a 受阻的具体报错。**

- [ ] **Step 8: 台账归档**

`task-4-demo.md` 逐环节落：环节 / URL / 关键操作 / 断言数值（visit_id、settleNo×2、rxNo、发药单号、诊毕 DB 行、医保拆分实测或 7b 降级记录）/ 截图文件名。截图存 `.superpowers/gui-test-screenshots/pr7-*`。本任务零提交（台账与截图不入库）。

---

### Task 5: portal 预约渠道演示 + workstation 链路衔接断言 + DoD 第 5 条三处审计行抽查与脱敏抽查

**Files:**
- Create: `.superpowers/sdd/2026-09-24-p1-pr7-closeout/task-5-demo.md`（断言数值台账，不入提交面）

**Interfaces:**
- Consumes: Task 3 healthy 栈；Task 4 演示患者证件号与 settleNo / 发药单号；portal 匿名通道 `GET|POST /api/v1/outpatient/portal/**`（`SystemWebConfig.AUTH_WHITELIST` 白名单，PR-5 裁决 13）。
- Produces: `task-5-demo.md`（portal 出票数值 + 衔接 DB 断言 + 三处审计行 + 脱敏断言——Task 6 CHANGELOG 引用；Task 8 §1.1/§1.5 证据）。

- [ ] **Step 1: portal 预约（P1 计划决策 5「演示端 portal 患者预约渠道」）**

浏览器打开 `http://localhost/portal/appointment`（免登录，路由 meta public）——操作链（PR-5 计划 :1772 页面规格）：证件号输入 Task 4 演示患者证件号（18 位规则显式校验）→ 查询可约号源（选 `<执行日+1>` DEP-IT-FLOW 池）→ 提交预约 → 断言出票卡：**apptNo**（形态 `AP<yyyyMMdd><6 位流水>`）+ 支付时限倒计时文案呈现。截图 `pr7-portal-1.png`（查询）、`pr7-portal-2.png`（出票卡）。apptNo 记入台账。

- [ ] **Step 2: workstation 链路衔接断言（DB 与 Redis 留证）**

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres psql -U fuyun -d fuyun \
  -c "SELECT appt_no, channel, status, pay_deadline IS NOT NULL AS has_deadline, visit_id FROM outpatient.appointment WHERE appt_no = '<Step1 apptNo>';"
# 预期：channel=PORTAL、status=RESERVED、has_deadline=t（15 分钟支付时限，fy.delay 延迟释放链入队实证）
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres psql -U fuyun -d fuyun \
  -c "SELECT sched_date, total_quota, used_count FROM outpatient.appt_number_pool WHERE id = '<Step1 所选池id>';"
# 预期：used_count 较池行基值 +1（跨应用链路衔接：portal 占号 → 池行扣减）
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T redis redis-cli --scan --pattern 'fy:outpatient:pay-hold:*'
# 预期：命中 <apptNo> 占位键（预约占位键在案）
```

- [ ] **Step 3: 三处审计行抽查（DoD 第 5 条原文口径：患者查询/收费票据/发药记录）**

3a. 患者查询审计行——先在 workstation `http://localhost/workstation/patients` 检索演示患者（姓名关键字）产生查询流量，再取行：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres psql -U fuyun -d fuyun \
  -c "SELECT id, operator_id, action_type, resource, biz_no, result FROM system.audit_log WHERE resource LIKE '/api/v1/patient/patients%' ORDER BY id DESC LIMIT 5;"
# 预期：≥1 行（resource 含 patients 检索/详情路径；operator_id=3 即 doctordemo 会话；result=SUCCESS；列集 = V302 实测 :5-13）
```

3b. 收费票据审计行：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres psql -U fuyun -d fuyun \
  -c "SELECT id, operator_id, action_type, resource, biz_no, result FROM system.audit_log WHERE resource IN ('/api/v1/billing/settlements', '/api/v1/billing/settlements/preview') ORDER BY id DESC LIMIT 5;"
# 预期：≥2 行 WRITE（preview + settle，@AuditLog(WRITE) 实测 SettlementController:56-74；biz_no 与 Task 4 settleNo 对得上即票据锚命中）
```

3c. 发药记录审计行：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres psql -U fuyun -d fuyun \
  -c "SELECT id, operator_id, action_type, resource, result FROM system.audit_log WHERE resource LIKE '/api/v1/pharmacy/dispenses/%' ORDER BY id DESC LIMIT 5;"
# 预期：≥3 行 WRITE（pick/verify/issue 三步链，@AuditLog(WRITE) 实测 DispenseController:43-73）
```

- [ ] **Step 4: 敏感数据脱敏抽查（DoD 第 5 条前半句）**

① UI 掩码断言：Step 3a 的患者检索结果页，证件号/手机号列呈 `*` 掩码（截图 `pr7-mask-1.png`；检索页脱敏输出 = PatientController「档案详情脱敏输出」同源口径）；② 日志禁明文断言：

```bash
docker logs deploy-backend-1 2>&1 | grep -c "<演示患者证件号>"
# 预期：0（日志禁敏感明文红线——03 Spec §9 口径）
```

- [ ] **Step 5: 台账归档**

`task-5-demo.md` 落：portal 出票数值 / 衔接三断言输出 / 三处审计行（各取 1 行摘录）/ 脱敏两断言结论 / 截图文件名。本任务零提交。

---

### Task 6: CHANGELOG 演示留痕 + TASK.md 收口（销项核对留痕 + W-7 按待批结论 + 新发现盘点 + 扫描 4 项按待批结论登记）

**Files:**
- Modify: `CHANGELOG.md`（顶部新增两条条目：演示留痕条目 + PR-7 收口条目）
- Modify: `TASK.md`（W-7 按待批分支 A 改写 / 扫描 4 项按待批分支 A 新增；W-28 已于 Task 3 删除）

**Interfaces:**
- Consumes: `task-2-recon.md`（销项复核结论）、`task-4-demo.md` / `task-5-demo.md`（演示数值）；待批项 1 / 待批项 2 的用户裁决结论（Execution Handoff 呈报，批准计划时一并裁决）。
- Produces: CHANGELOG 两条条目（终验报告 §2 资产引用）；TASK.md 收口终态（Task 7 DoD 第 4 条核验与 Task 8 §1.4 证据）。

- [ ] **Step 1: CHANGELOG 演示留痕条目（IOTDA 先例形态：标题=日期·主题：演示项列举；条目=前提/步骤化证据/口径注明）**

在 `CHANGELOG.md` 顶部插入（`<执行日>` 与台账数值执行时回填；形态模板 = `CHANGELOG.md:629-636` IOTDA 联调收口条目）：

```markdown
## <执行日> · P1 收口：门诊全流程真栈演示（挂号→就诊→收费→发药）与 portal 预约渠道演示

- **前提**：PR #<回流PR号> 扫描成果回流 dev@<新基线hash> 后重建 fuyun/backend:dev 镜像并起栈，
  compose 六服务全 healthy；演示预检 W-28 三步执行（号源对账 Redis/DB 余量比对一致、fy.delay
  队列深度 <实测值>、<purge 条数或未触发>），W-28 工单销项。
- **门诊四环节（workstation 端，playwright-cli 真机，截图 .superpowers/gui-test-screenshots/pr7-*）**：
  ① 挂号——DEP-IT-FLOW 当日普通号 WINDOW 渠道，visit_id=<实测>（O+日期+5 位流水，CF-3），
  挂号费手工计费+结算 settleNo=<实测>；② 分诊报到+叫号——队列状态转已叫；③ 就诊——接诊后
  开检查单（PENDING_FEE）与处方（PENDING_DISPENSE）；④ 收费——就诊费用预结算+结算
  settleNo=<实测>（CASH）；发药——pick/verify/issue 三步链发药单号=<实测>；诊毕——去向确认后
  visit finished_at 落库（psql 实证）。
- **portal 预约渠道（决策 5）**：/portal/appointment 免登录证件号预约，出票 apptNo=<实测>
  （AP+日期+6 位流水）+ 支付时限倒计时；衔接断言：appointment 行 RESERVED/PORTAL/15 分钟
  pay_deadline、池行 used_count +1、Redis pay-hold 占位键在案。
- **DoD 第 5 条抽查**：患者查询/收费票据/发药记录三处 system.audit_log 行各 1 行摘录
  （operator/action_type/resource/result 全 SUCCESS）；脱敏——检索页证件号/手机号掩码截图 +
  backend 日志 grep 演示证件号 0 命中。
- **口径注明**：<医保段——按 Task 4 Step 7 实际执行档写入：7a=API 级 preview CITY_INS 实测
  拆分 60/20/20 + SIM- 流水号 + insurance_call_log 2102 行；7b=UI 固定 SELF_PAY（
  PricingSettleView.vue:200），模拟拆分由 InsuranceSimulatorAdapter 承载、PR-3 IT 覆盖>。
  语音外放属现场外设（PR-5 口径沿袭）；bigscreen 叫号页非 DoD 义务面未纳入本次演示。
```

- [ ] **Step 2: 复核销项终态（grep 留痕，逐条 Expected）**

```bash
grep -n "W-28" TASK.md && echo FOUND || echo CLEAN      # 预期：CLEAN（Task 3 已删）
grep -nE "^\| W-4 \||^\| W-5 \||^\| W-6 \|" TASK.md      # 预期：零命中（已销，复核确认）
grep -n "D-8" TASK.md                                     # 预期：零命中（已销，复核确认）
grep -n "| W-7 |" TASK.md                                 # 预期：命中（待 Step 3d 处置）
```

- [ ] **Step 3: TASK.md 收口动作**

3a. W-4/W-5/W-6/D-8 已销确认——零命中结论留痕写入 Step 4 收口条目（不新增行，销项核对即 DoD 第 4 条口径的「核对留痕」）。
3b. 本阶段新发现盘点确认——`grep -nE "^\| W-3[0-6] \|" TASK.md`（预期七行）与 `grep -nE "^\| D-2[234] \|" TASK.md`（预期三行）行号留痕写入收口条目（内容不改：均为 PR-6 收口期已登记的在案条目，PR-7 义务=盘点确认而非重复登记）。
3c. W-28 销项——Task 3 已删，本步零动作（grep CLEAN 留痕入收口条目）。
3d. **W-7 按待批项 1 裁决分支执行**：
  - **分支 A（裁决=转 TASK.md 改期登记）**：编辑 W-7 行说明列，在「**P1 PR-1 开工前 fix PR 闭合（闭合时删除本行）**」之后追加：「**2026-09-24 复核：P1 期间未实施（fuyun-iot 迁移止 V403、skipped_non_numeric 丢弃口径 TelemetryIngestServiceImpl:131 在案）；<执行日> 用户裁决改期至 <裁决指定目标阶段/PR> 承载，闭合时删除本行**」；涉及面列不动。
  - **分支 B（裁决=PR-7 内补实施）**：**阻断本计划后续任务**——W-7 实施属实现类 PR（V404+ 迁移 + TelemetryIngestServiceImpl 改造 + TelemetryFrameParserTest/IotTelemetryPipelineIT 同步），须另行增补计划并经用户批准；本计划 Task 6 起暂停并回报主控。
3e. D-8 裁决结论登记——已随历史 PR 回填销项（零命中即登记完成的终态），收口条目记载该终态。
3f. **扫描待裁决 4 项按待批项 2 裁决分支执行**：
  - **分支 A（裁决=登记入 TASK.md）**：TODO 工单表末尾按实际末号顺延新增四行（撰写期末号 W-36，预期 W-37~W-40；`grep -nE "^\| W-3[6-9] \||^\| W-40 \|" TASK.md` 先实测确认续号），说明列各含：问题一句话 + 置信 + 依据锚（`docs/progress/2026-09-23-安全扫描问题清单.md` 对应行）+ 建议承载（BE-AUTHZ-GAP→随 M01 数据范围拦截器/RBAC 全量 403 专项；BE-S1-01→portal 账号体系/风控随 M18 或契约扩展立项；FE-S1-01→P2 WS 通道演进开大屏匿名只读通道、过渡期部署面轮换令牌；SEC-02 病区子面→随护士-病区归属数据模型设计）+「登记于 PR-7 收口，<执行日> 用户裁决」；涉及面列引用问题清单原文。
  - **分支 B（裁决=不登记）**：TASK.md 零动作；终验报告 §4 遗留节全量记载四项（含依据锚），声明「用户裁决 P1 收口不登记 TASK.md」。

- [ ] **Step 4: CHANGELOG PR-7 收口条目 + 提交**

收口条目（置于演示留痕条目之上，时间倒序）：

```markdown
## <执行日> · P1 PR-7 收口

- 交付面：扫描成果回流 dev（PR #<回流PR号> 纯 merge，CI 六 job 绿，新基线 dev@<hash>）+
  演示预检 W-28 销项 + 门诊全流程真栈演示与 portal 预约演示留痕（见同日演示条目）+
  TASK.md 销项核对留痕（W-4/W-5/W-6/D-8 已销复核零命中、W-28 删除零残留；W-7 <分支A：改期
  登记结论>/W-7 <分支B：阻断另行增补计划——此形态不出现>）+ 本阶段新发现盘点确认
  （W-30~W-36/D-22~D-24 在案）+ 扫描待裁决 4 项<分支A：登记 W-37~W-40>/（分支B：终验报告
  遗留节记载，TASK.md 不动）+ P1 实施计划 §3 七 PR 完成项内联标注（merge hash 实取）与
  §4 DoD 五条出口核验 + P1 终验小结（docs/prompt/<执行日>-P1终验报告.md）。
- CI 口径：DoD 第 2 条「五 required checks」为 P1 计划撰写时口径，按现行六 job 执行；
  PR-7 纯文档 PR 的 CI 证据=回流 PR 六 job 绿 + 本地全量门禁绿组合（终验报告 §1.2）。
```

提交（python len 自查后）：

```bash
python - <<'EOF'
# commitmsg.txt 逐行 ≤100 字符自查（内容先行写入）
for line in open('commitmsg.txt', encoding='utf-8', encoding_errors='replace'):
    line = line.rstrip('\n')
    if len(line) > 100:
        print('OVER100:', len(line), line)
EOF
git add CHANGELOG.md TASK.md
git commit -m "docs(chore): PR-7 收口——CHANGELOG 演示留痕与 TASK.md 销项核对"
```

---

### Task 7: 计划完成项标注 + DoD 出口检查（P1 实施计划 §3 完成标注 + §4 五条逐项核验留证）

**Files:**
- Modify: `docs/plans/2026-09-14-P1实施计划.md`（§3 七个 PR 小节内联标注 + §4 五条出口核验行）

**Interfaces:**
- Consumes: `gh pr list --state merged --base dev` 实取的各 PR merge hash；Task 4/5/6 证据台账；Task 2 复核结论。
- Produces: 标注后的 P1 实施计划（Task 8 终验报告 §2 资产引用「PR-1a~PR-7 已内联标注 hash」——P0 终验报告 :133 先例形态）。

- [ ] **Step 1: 实取各 PR merge hash（PR↔计划文件名映射表）**

```bash
gh pr list --state merged --base dev --limit 40 --json number,title,mergeCommit,mergedAt \
  --jq '.[] | "\(.number)\t\(.mergeCommit.oid[0:7])\t\(.mergedAt)\t\(.title)"' | head -40
```

映射表（计划文件 ↔ PR 定位依据，撰写期在案的七份计划文件）：

| P1 计划 §3 条目 | 实施计划文件（docs/superpowers/plans/） | PR 定位关键词（--search 补查） |
| --- | --- | --- |
| PR-1（拆 1a/1b 两计划两 PR） | `2026-09-14-p1-pr1a-modulith-infra.md`、`2026-09-15-p1-pr1b-m20-governance.md` | PR 标题含 Modulith / M20 治理 |
| PR-2 | `2026-09-16-p2-pr2-m02-empi.md` | M02 EMPI |
| PR-3 | `2026-09-17-p1-pr3-m13-billing.md` | M13 收费 |
| PR-4 | `2026-09-18-p1-pr4-m06-pharmacy.md` | M06 药事 |
| PR-5 | `2026-09-20-p1-pr5-m03-outpatient.md` | M03 门诊 |
| PR-6 | `2026-09-22-p1-pr6-m05-nursing.md` | M05 护理（PR #49） |
| 扫描回流（计划外） | —（本计划 Task 1） | 回流 PR 号（Task 1 台账） |

PR-1~PR-5 的 PR 号执行时以上表关键词 `gh pr list --state merged --base dev --search "<关键词>"` 补查定位（若标题检索仍歧义，按 mergedAt 与计划文件日期的先后对应关系判定，判定过程记入 PR 描述）。

- [ ] **Step 2: §3 七个 PR 条目内联完成标注（P0 先例=hash 内联）**

在 `docs/plans/2026-09-14-P1实施计划.md` 每个 `### PR-N ...` 标题行（:52/:60/:67/:74/:81/:89/:96）之下插入一行引用块（示例以 PR-5 填法给出，七条同构，PR-1 行列 1a/1b 两 hash）：

```markdown
> **完成标注（<执行日> PR-7 收口回填）**：已交付合并 dev@<hash7>（PR #<号>，<mergedAt 日期>）。
```

PR-7 自身条目标注：「> **完成标注（<执行日> 本 PR 收口回填）**：本 PR = `feat/p1-pr7-closeout`（合并点 hash 见终验报告附录 A.5——收口 PR 自身 hash 先验不可知，以终验报告为准）。」

- [ ] **Step 3: §4 DoD 五条出口核验（逐条证据实取 + 内联核验行）**

五条核验动作与证据源（逐条执行后回填结论）：

| DoD 条 | 核验动作（本任务执行） | 证据源 |
| --- | --- | --- |
| 第 1 条 演示 | 引用 Task 4/5 台账结论（四环节 + portal 全过/降级口径） | task-4-demo.md / task-5-demo.md / CHANGELOG 演示条目 |
| 第 2 条 门禁 | Task 1 回流 PR `gh pr checks` 六 job 绿输出 + Task 9 本地门禁结论（引用；Task 9 未跑则标注「见终验报告 §1.2」） | 回流 PR run + 终验报告 §1.2 |
| 第 3 条 CF-3/4/5/6 | 探针：`psql "SELECT count(*) FROM integration.event_registry WHERE deleted = 0;"`（预期 64）+ `rabbitmqctl list_exchanges name`（预期仅 fy.topic/fy.dlx/fy.delay 与 amq 默认，无私建）+ MessagingGovernanceIT 随 Task 9 verify 绿 | 本步探针输出 + Task 9 |
| 第 4 条 TASK.md | 引用 Task 6 Step 2/3 grep 留痕（W-4/5/6/D-8 零命中、W-28 CLEAN、W-7 处置结论、W-30~36/D-22~24 在案、扫描 4 项登记结论） | task-2-recon.md / Task 6 输出 |
| 第 5 条 脱敏审计 | 引用 Task 5 Step 3/4 三处审计行与两断言结论 | task-5-demo.md |

在 §4 各条（:102/:103/:104/:105/:106——以实况行号为准）行尾追加核验行：

```markdown
> **出口核验（<执行日>）**：✓ <一句话结论或口径>——证据：docs/prompt/<执行日>-P1终验报告.md §1.<N>。
```

（第 2 条核验行必须含六 job 口径差异说明；第 1 条含医保 7a/7b 实际档位。）

- [ ] **Step 4: 验证与提交**

```bash
grep -c "完成标注（" docs/plans/2026-09-14-P1实施计划.md    # 预期：7（PR-1~PR-7 各一）
grep -c "出口核验（" docs/plans/2026-09-14-P1实施计划.md    # 预期：5（DoD 五条各一）
git diff --stat    # 预期：仅 docs/plans/2026-09-14-P1实施计划.md 一个文件
```

提交：`docs(chore): P1 实施计划完成项标注与 DoD 五条出口核验`（python len 自查同 Task 6 Step 4）。

---

### Task 8: P1 终验小结（docs/prompt/<执行日>-P1终验报告.md，P0 骨架九节）

**Files:**
- Create: `docs/prompt/2026-09-XX-P1终验报告.md`（XX=执行日；命名沿用 P0 先例 `2026-09-09-P0终验报告.md`——用户已裁决口径）

**Interfaces:**
- Consumes: Task 1~7 全部台账与输出（回流 PR / 演示 / 销项 / 标注 / 门禁探针）。
- Produces: P1 终验报告（Task 9 PR 交付物之一；P1 阶段出口的唯一权威结论载体）。

骨架九节（P0 终验报告形态先例——属性表六字段 + 每核验节「结论行 + 证据（本时点实际执行）」；全部证据取自本时点实际命令输出，非预检转抄）：

- [ ] **Step 1: 属性表 + §1 DoD 五条逐项核验**

属性表：文档编号 `FINAL-ACCEPT-P1-01` / 日期=<执行日> / 性质=P1 终验（对 P1 实施计划 §4 五条 DoD 逐项跑命令留证后作终验结论）/ 执行依据=`docs/plans/2026-09-14-P1实施计划.md` §4 + `docs/superpowers/plans/2026-09-24-p1-pr7-closeout.md` / 基线=dev@9aa4c8b → dev@<回流hash> → dev@<PR-7 合并点>/ 结论口径=✓ 通过 / 口径差异留痕（附登记载体）。
§1 五小节：1.1 演示（四环节+portal+脱敏审计，证据=Task 4/5 台账与截图清单）；1.2 门禁（**CI 口径差异留痕：五→六 job**；组合证据=回流 PR 六 job 绿 run 编号 + 本地 `mvn verify` 与前端六门禁输出摘要）；1.3 CF-3/4/5/6（event_registry 64 行探针 + exchanges无私建 + MessagingGovernanceIT 随 verify 绿 + Spec 对齐注记在案引 03/06/05/04-inpatient 注记块）；1.4 TASK.md（销项核对留痕表：W-4/5/6/D-8 零命中、W-28 CLEAN、W-7 处置结论、新发现盘点行号、扫描 4 项登记结论）；1.5 脱敏与审计（三处审计行摘录表 + 掩码截图引用 + 日志零明文）。

- [ ] **Step 2: §2 过程资产清单 + §3 流程偏差与处置 + §4 遗留事项 + §5 终验结论**

§2 表：七份实施计划文件 + 回流 PR/PR-7 PR（号与 merge hash）+ 两份 UI 设计文档（PR-5/PR-6）+ 扫描终验报告 + SDD 台账目录 + CI run 证据。§3 偏差至少四条：① DoD 第 2 条五→六 job 口径差异；② W-7 未实施与处置结论（分支 A/B 实况）；③ 扫描成果回流 dev 的时序偏差（PR #50 落 feat 分支、经 PR-7 Task 1 回流）；④ 医保演示 7a/7b 实际档位与原因；⑤（如有）PR 定位歧义判定记录（Task 7 Step 1）。§4 遗留表：全部指向 TASK.md 现行条目（D-3~D-7/D-19/D-22~D-24、W-7（分支 A 时）、W-8~W-15/W-19/W-20/W-25/W-27/W-30~W-36、扫描 4 项（按登记去向））——以执行时 `grep` 实况成表。§5 结论：五条逐条 ✓/口径结论 + 「P1 交付物达到出口标准，主循环退出」判词。

- [ ] **Step 3: 附录命令输出证据摘录 + 提交**

附录五节（A.1 起栈与 W-28 三步输出；A.2 后端 verify 尾段；A.3 前端六门禁退出码；A.4 演示四环节关键断言输出；A.5 基线与 PR 状态 `git log --merges` 尾段——**PR-7 合并点 hash 本节为准**）。验证：`grep -c "^## " docs/prompt/<执行日>-P1终验报告.md` 预期 6（§1~§5 + 附录）+ 属性表在首。提交：`docs(chore): P1 终验小结（FINAL-ACCEPT-P1-01）`（len 自查）。

---

### Task 9: 全量门禁 + PR + 质量门（后端 verify + 前端六门禁 + /code-review + merge 收尾）

**Files:** 无新增（门禁执行与 PR 编排；PR body 落 `.superpowers/pr7-body.md` 不入库）

**Interfaces:**
- Consumes: Task 1~8 全部交付（分支 `feat/p1-pr7-closeout` 上四个提交：Task 3/6/7/8 各一）。
- Produces: PR-7 合并（P1 阶段终态）；PR-7 合并点 hash（终验报告附录 A.5 为准的回填锚）。

- [ ] **Step 1: 后端全量 verify（基线健康出口检查——DoD 第 2 条组合证据之二）**

```bash
JAVA_HOME=/d/code/java/jdk/jdk17 mvn -B -ntp -f backend/pom.xml verify 2>&1 | tail -60
```

Expected: BUILD SUCCESS；全反应堆绿；JaCoCo 双阈值 `All coverage checks have been met.`；`MessagingGovernanceIT`（总行 64 断言）与三护理锚点 IT、门诊三 IT 全绿（DoD 第 3 条佐证）。

- [ ] **Step 2: 前端六门禁**

```bash
cd web && pnpm lint && pnpm format:check && pnpm type-check && pnpm test && pnpm build \
  && pnpm audit --audit-level high --registry=https://registry.npmjs.org
```

Expected: 六步退出码全 0（audit「No known vulnerabilities found」——CI frontend job 同命令）。

- [ ] **Step 3: 提交面复核 + commitlint 终检**

```bash
git diff --stat origin/dev    # 预期：仅四个文件（TASK.md / CHANGELOG.md / P1实施计划 / P1终验报告），零代码文件
git log origin/dev..HEAD --oneline   # 预期：四个 docs/chore 提交
python - <<'EOF'
import subprocess
for line in subprocess.run(['git','log','origin/dev..HEAD','--format=%B'],capture_output=True,text=True).stdout.splitlines():
    if len(line) > 100: print('OVER100:', len(line), line)
EOF
# 预期：零 OVER100 输出（历史提交 body 复检）
```

- [ ] **Step 4: PR 建单（base=dev）**

```bash
git push -u origin feat/p1-pr7-closeout
gh pr create --base dev --title "docs(chore): P1 PR-7 收口——演示留痕/销项核对/DoD 出口核验/终验小结" \
  --body-file .superpowers/pr7-body.md
```

PR body 必含：四项工作交付对照（P1 计划 :96-98 四拆读逐项）；扫描回流 PR 号与新基线 hash；演示四环节+portal 结论与截图路径（`.superpowers/gui-test-screenshots/pr7-*`）；三处审计行摘录；W-7 与扫描 4 项的待批裁决结论及批复出处；DoD 第 2 条 CI 组合证据口径声明（五→六 job 差异 + 纯文档 PR 说明）；本地门禁输出摘要；PR 定位歧义判定记录（如有）。

- [ ] **Step 5: 质量门（不可跳过）+ 合并**

`/code-review` 插件审核：≥80 分 findings 必修（纯文档 PR 以登记勘误闭环——改动、复检、直至 findings 清零）；随 CI checks（changes/commitlint/hygiene 必跑；backend/frontend 可能因路径过滤跳过——**不得视为失败**，证据口径见 Global Constraints 6）。全绿后按 merge 惯例合并：

```bash
gh pr checks <PR-7号>
gh pr merge <PR-7号> --merge
```

- [ ] **Step 6: 收尾**

```bash
git fetch origin && git checkout dev && git pull
git rev-parse --short=7 origin/dev    # PR-7 合并点 hash——回填终验报告附录 A.5 的权威值
git log --oneline -1                  # 停留 dev（P0 收尾惯例）
```

PR-7 合并点 hash 回填终验报告附录 A.5：以一处 `docs` 补充提交直接推 dev **不合规**——正确形态 = 在 PR 描述或 SDD 台账 `task-9-report.md` 记录该 hash（终验报告正文引用「合并点见 PR #<PR-7号>」；报告文件内 A.5 占位以「见 PR #<PR-7号> merge commit」表述，执行 Task 8 Step 3 时即按此写，无需合并后改文件）。台账归档 `task-9-report.md`（门禁输出摘要 + PR 号 + 合并点 hash + /code-review 结论）。

---

## 自审记录（writing-plans Self-Review，2026-09-24）

**1. Spec 覆盖对照（PR-7 范围 = P1 计划 :96-98 四项工作 + DoD 五条 :100-106 + 主控三项新事实）**

- 四项工作拆读覆盖：①门诊全流程真栈演示留痕（演示记录入 CHANGELOG）→ Task 4（workstation 四环节）+ Task 5（portal 端，DoD 第 1 条「workstation/portal 端」双端口径）+ Task 6 Step 1（CHANGELOG 留痕条目）✓；②TASK.md W-4/W-5/W-6 销项核对、本阶段新发现登记→ Task 2（复核）+ Task 6 Step 2/3（销项核对留痕 + W-30~36/D-22~24 盘点确认）✓；③计划完成项标注与 DoD 出口检查（§4）→ Task 7 ✓；④P1 终验小结（docs/prompt/）→ Task 8 ✓。
- DoD 五条逐条落点：第 1 条（演示 workstation/portal 端）→ Task 4/5，证据入 Task 8 §1.1 ✓；第 2 条（mvn verify/前端六门禁/CI）→ Task 9 Step 1/2 + Global Constraints 5/6 口径（五→六 job 差异留痕 Task 8 §1.2/§3）✓；第 3 条（CF-3/4/5/6 冻结登记+治理构件+审核项）→ Task 7 Step 3 探针（64 行/无私建交换机/MessagingGovernanceIT）+ Task 8 §1.3 ✓；第 4 条（W-4/5/6/7 销项、D-8 登记、新发现登记）→ Task 2 复核 + Task 6 Step 3（W-7 双分支、D-8 终态、W-30~36/D-22~24 盘点；D-2/D-9 已随计划审批 PR 删除——简报 §1.2 原文括注，无需动作）✓；第 5 条（脱敏+三处审计行）→ Task 5 Step 3/4 ✓。
- 主控新事实三项：W-7 未实施实锤 → Global Constraints 9 + 待批项 1 + Task 2 Step 4 复核 + Task 6 Step 3d 双分支 ✓；扫描成果回流 → Task 1 ✓（PR 描述引用终验报告）；扫描待裁决 4 项 → Global Constraints 10 + 待批项 2 + Task 2 Step 6 + Task 6 Step 3f 双分支 ✓。
- 演示口径相关 P1 计划原文：交付验证物（:26「compose 真栈，workstation 端走通」）→ Task 4 真栈真机 ✓；决策 5 portal 渠道（:36）→ Task 5 Step 1 ✓；决策 3 医保模拟通道（:34）→ Task 4 Step 7 双档 + Global Constraints 11 ✓；§5 边界（:108-110）→ not-in-scope 引用块 + Global Constraints 12 ✓。
- 演示步骤零上下文可照做性：账号（doctordemo/admin 均 Fuyun@2026，V704/V303 种子锚）、URL（`http://localhost/workstation/...` 七路由实测）、API（登录/排班模板/放号/可约号源/医保 preview 五端点实测含响应字段 accessToken）、断言（visit_id/apptNo/settleNo/审计行 SQL 全给出）、素材缺口处以 PR-5 计划精确行号引用（:1769-1771/:1772 页面规格——有锚引用非泛引）✓。

**2. 占位符扫描**

`grep -nE "TBD|待定|适当处理|后续补齐|类似 Task|详见前述|参见 PR-5" <本计划>`——预期零命中（「参见 PR-5 计划」类无锚泛引已杜绝，仅存带行号的取材指引）。文中形参仅三类合法：①`<执行日>`（头部声明全局替换参数）；②`<回流PR号>`/`<hash>`/`<PR-7号>`/`<实测>`（执行时实取回填——writing-plans 对「运行时产出值」的合法形态，PR-6 计划 W-xx 续号同款）；③Task 6 Step 3d/3f 与 Task 4 Step 7 的分支执行体（双档/双分支均给出完整动作与文本，非悬置）。`<挂号费itemCode>`/`<第二visitId>` 等 Task 4 Step 7a 运行时入参均在同 Step 内给出来源命令（charge-items/by-code 查询/UI 实录值）。

**3. 类型一致性抽查清单**

- 分支名 `feat/p1-pr7-closeout` = 头部基线区 = Task 2 Step 1 = Task 9 Step 4 —— 四处一致 ✓；回流源 `origin/feat/p1-pr6-m05-nursing@1d372c7` = Task 1 Step 1 = 文件结构表 Task 1 行 ✓。
- 四文件改动面（TASK.md/CHANGELOG/P1 实施计划/P1 终验报告）= Global Constraints 2 = 文件结构表 = Task 9 Step 3 `git diff --stat` 预期 —— 四处一致 ✓（`.superpowers/` 台账与截图三处均标「不入提交面」：Global Constraints 14、文件结构表、Task 4/5/8/9 各 Step）。
- 账号口径：doctordemo/Fuyun@2026（Task 4 Interfaces + Step 0d）= V704 :428-429 种子注释；admin/Fuyun@2026（Task 4 Step 0a）= V303 种子 + FuyunStackITBase 先例；audit 行 operator_id=3（Task 5 Step 3a 预期）= V704 sys_user.id=3 身份链 ✓。
- 路由七条 = `web/apps/workstation/src/router/index.ts` 实测原文；portal `/appointment` = portal router 实测；`/api/v1/system/auth/login`（accessToken 字段）、`/api/v1/outpatient/schedule-templates|schedules/generate|number-pools/available`、`/api/v1/billing/charge-items/by-code|insurance-mappings/upsert|settlements/preview`、`/api/v1/pharmacy/dispenses/{no}/pick|verify|issue` = 各 Controller 实测 ✓。
- `system.audit_log` 查询列集（id/operator_id/action_type/resource/biz_no/result）= V302 :5-13 实测列 ✓；`fy:outpatient:pool:*`/`fy:outpatient:pay-hold:*` 键名 = PR-5 计划 Global Constraints Redis 键规范原文 ✓。
- 六 job 名（changes/commitlint/hygiene/backend / verify/frontend / verify/images）= ci.yml 实测 = Global Constraints 5 = Task 1 Step 2④/Step 3 = Task 8 §1.2 —— 四处一致 ✓。
- CHANGELOG 两条条目（演示留痕 + 收口）= Task 6 Step 1/4 = 文件结构表 = Task 8 §2 资产 ✓；终验报告九节 = Task 8 Steps = P0 骨架（简报 §4.1）✓；`grep -c` 标注行预期 7/5 = Task 7 Step 2/3 产出 ✓。
- W-28 三步（对账→深度→purge）= TASK.md:51 原文口径 = Task 3 Steps 3-5；「执行后删除本行」= Task 3 Step 6 ✓。
- 医保拆分 60/20/20 与 SIM- 前缀 = `InsuranceSimulatorAdapter.java:25`/:38 注释实测 = Task 4 Step 7a 断言 = Task 6 Step 1 口径注明文本 ✓；SELF_PAY 固定 = `PricingSettleView.vue:200` 实测 = Global Constraints 11 = 7b 文本 ✓。

**4. 任务框架 9 项与主控指示的偏差（理由呈报）**

① 任务顺序零调整（Task 1~9 与主控框架一一对应）；② Task 4 内部将「诊毕」从就诊环节后移至 Step 6（收费、发药完成后）——依据 PR-5 计划 :26 演示直线段定义「挂号→就诊→开单→收费→发药→诊毕」的环节序与诊毕「在途单据显式确认」前置依赖（先结清后诊毕，演示最短路径）；③ Task 7 吸收了 DoD 第 3 条探针执行（主控框架将其归入「出口检查」语义，落 Task 7 Step 3 表格），证据复用 Task 8 §1.3——避免同一探针两处执行；④ PR-7 自身 merge hash 的先验不可知性以「终验报告引用 PR 号」形态处置（Task 9 Step 6 论证），未安排合并后改文件（避免 docs 直推 dev 违反流程红线）。

## Execution Handoff

计划已保存：`docs/superpowers/plans/2026-09-24-p1-pr7-closeout.md`（**9 任务**；前置项 12 条全映射；任务依赖序 **1→2→3→4→5→6→7→8→9** 严格串行——Task 1 产出新基线为 Task 2 建分支前提；Task 3 栈为 Task 4/5 演示前提；Task 4/5 台账为 Task 6 CHANGELOG 素材；Task 7 标注引用 Task 6 终态与 Task 4/5 证据；Task 8 汇编 Task 1~7；Task 9 门禁与 PR。Task 6 Step 3d 分支 B 触发时自该步起阻断，其余任务不受影响的部分亦不得先行——W-7 增补计划须先过用户批准）。

### 一、待批项呈报清单（超出既定裁决的自增项与执行期判定，逐条附依据；批准计划即一并裁决以下条目）

> **用户裁决（2026-09-24 深夜批复，全链路解除阻塞）：1A / 2A / 同日连执行（演示日 2026-09-25）/ 授权降级。** 各条裁决与附加条件如下，执行专员以本节裁决为终态依据。

1. **W-7 处置**（DoD 第 4 条唯一在案残留销项对象）：主控 2026-09-24 核实**未实施**（fuyun-iot 迁移止 V403 无 V404+ 文本承载列迁移；`TelemetryIngestServiceImpl.java:131` 仍丢弃非数值行）——自述闭合时点「P1 PR-1 开工前」已过。两个选项：**A. 转 TASK.md 改期登记**（Task 6 Step 3d-A 改写说明列，指定目标阶段/PR；本计划不受阻）／**B. PR-7 内补实施**（属实现类 PR：V404+ 迁移+实现改造+TelemetryFrameParserTest/IotTelemetryPipelineIT 同步——须**另行增补计划**并批准，本计划自 Task 6 起阻断）。本计划不预设倾向，以用户裁决为准。
   - **裁决 = A（改期登记）**。附加条件：①说明列三要素写齐——P1 期间未实施 + 复核证据（迁移止于 V403、`TelemetryIngestServiceImpl.java:131` 仍为 `skipped_non_numeric`）+ 改期去向落到具体阶段：**P2 承载，若届时 P2 主题不符则开独立 IoT 数据面专项，不得写「待定」**；②终验报告「遗留事项」节同步留痕违约事实与原因，不许静默带过。
2. **扫描待裁决 4 项登记去向与形态**（BE-AUTHZ-GAP 全站授权缺口 / BE-S1-01 portal 匿名退号 IDOR / FE-S1-01 大屏令牌内联 / SEC-02 病区维度子面；`docs/progress/2026-09-23-安全扫描问题清单.md` :5/:15-18/:26/:27 在案，扫描终验报告 §六建议并入 PR-7 盘面）：**A. 登记入 TASK.md**（Task 6 Step 3f-A 新增四行 TODO 工单，建议承载随 M01 RBAC/M18 账号体系/P2 WS 通道演进/病区归属数据模型）／**B. 不登记**（仅终验报告 §4 遗留节记载）。登记形态若选 A，默认 TODO 工单（W 续号）；如需按待决策（D 续号）或混合形态登记，请在批复时注明。
   - **裁决 = A（登记 W 续号 TODO 工单）**。附加条件：①BE-S1-01（退号 IDOR）与 FE-S1-01（令牌内联）加注风险标记「**对外暴露上线前必须消化**」；②SEC-02 病区子面写明前置依赖 = 病区归属数据模型立项。③（用户另指示）`PricingSettleView.vue:200` 硬编码 SELF_PAY 属演示准备中新发现，按同口径在 Task 6 新发现登记环节登记 **W-41**（UI payerType 参数化，承载 P2 UI 面）——即本步新增共 **5 行（预期 W-37~W-41，以实际末号实测顺延为准）**。
3. **演示执行日**：计划未定死执行日——W-28 要求「演示前一天」三步预检，本计划将 Task 3（预检）与 Task 4/5（演示）安排为同会话先后执行（目的等价：防积压消息演示中集中爆发）；若用户希望严格按「前一天」执行（跨日），Task 3 与 Task 4 之间可间隔一日，Task 4 Step 0 前加一道 fy.delay 深度复测。请批复执行日或确认同日连执行的等价性。
   - **裁决 = 同日连执行成立，演示日定格 2026-09-25**（24 日深夜不安排连夜演示）。附加条件：**若预检（Task 3）完成后到实际开演间隔 >4 小时，开演前补一次 fy.delay 深度复测（不重跑全三步）**。
4. **医保演示双档授权**：Task 4 Step 7 首选 7a（API 级 payerType=CITY_INS preview 实演，前置=挂号费医保对照 upsert）；受阻超 30 分钟自动降级 7b「口径注明」（IOTDA 先例形态）。如用户要求「必须实演不降级」或「直接口径注明」，请批复档位。
   - **裁决 = 授权双档 + 30 分钟自动降级 7b**。附加条件：**降级不许静默——7a 的实际尝试记录与受阻原因须一并写入 CHANGELOG 口径注明段落**。

### 二、与简报/任务框架的偏差清单（执行与评审对照）

① **W-28「号源对账」的操作化解释**：W-28 原文「跑一次号源对账（日对账机制/sweep）」——仓内无独立对账端点（grep 实测仅 TTL 锚语义），本计划落为「Redis 池键余量 vs DB 池行 remaining 抽样比对」（Task 3 Step 3 具体命令），语义=对账动作的演示级执行，非新增机制。② **Task 4 诊毕后移**与 **Task 7 吸收 DoD 第 3 条探针**（见自审记录第 4 节）。③ **PR-7 自身 merge hash 先验不可知**：终验报告以「见 PR #号」表述承载（Task 9 Step 6 论证），P0 先例（终验报告 :233-239 附录实取 hash）在 PR-7 场景需合并后才有值——不安排合并后改文件（流程红线优先）。④ **PR-1~PR-5 的 PR 号未预填**：gh 标题检索 + mergedAt 对应（Task 7 Step 1 映射表），歧义判定过程记 PR 描述——简报未载各 PR 号，属执行期实取面。⑤ **门诊四环节页面的按钮级文案未逐字落计划**：以 PR-5 计划 :1769-1771/:1772 页面规格（有锚行号）+ 执行时页面实况为准——PR-5 计划为页面形态权威，本计划不复制其全部按钮文案（避免双源漂移）。

### 三、SDD 执行方式

**Subagent-Driven（推荐）**——`superpowers:subagent-driven-development`：每任务全新 subagent + 任务间审查；台账落 `.superpowers/sdd/2026-09-24-p1-pr7-closeout/`（task-1-report ~ task-9-report）。备选 **Inline Execution**——`superpowers:executing-plans`。执行期质量门（不可跳过）：Task 9 /code-review findings 清零后合并；演示质量门 = 真机浏览器 + 截图 + 断言数值台账（Task 4/5）。**进入条件**：本计划经用户批复（含待批项 1~4 裁决）。
