# CHANGELOG（工程变更记录）

> 记录规则（根 AGENTS.md §7）：**先记再改**——任何宪法 / 规范 / 机制文件的修订，先在本文件登记（日期、范围、理由、裁决），再改正文。追加式保留全部历史。

## 2026-10-05 · P2 PR-4E 收口修复环 R1（五路评审 must-fix 三主项+顺手三项）

- 交付：A-1 settle 非自费单 fail-closed（结算单 payerType≠SELF_PAY 抛 BILL-1015 409，payments
  非自费组装形态归 W-80，拒绝≠造形态）+C-F2/A-2 频控窗口每次续期自愈（checkWithinWindow
  滑窗化，杜绝 INCR 后 EXPIRE 失败遗留永久键锁死操作者）+D-1/E-1 收费页 preview 在途切档
  竞态守卫（payerType 发起锚定、回包不一致整包丢弃）+D-2 拦截文案随所选档中文标签参数化
  （商业保险档不再统称医保）+B-1 判别子对照用例门诊行补 m04OrderNo:null 显式线格式（billing/
  outpatient 两侧各一行）+E-3 恰阈值 10L 边界用例；评审档案 `.superpowers/code-review-pr4e/
  findings-*.md`，修复环报告 `.superpowers/code-review-pr4e/fix-round-R1.md`。
- **D-21 断言现代化申报（逐次批准，单点单次）**：NursingRateGuardTest.windowCountRejectsOverLimit
  断言「非首计 never expire（固定窗口锚）」→「每次调用 expire 续期（滑窗自愈锚）」——原断言
  冻结的恰是 C-F2 点名的脆弱实现细节（无 TTL 永久键→操作者第 11 次起永久 429 无自愈）；新断言
  较原多锚一次 expire 且叠加恰阈值 10L 放行回归锚，严格度不低于原；断言修订+实现变更+回归锚
  同 PR 交付零混入（批准出处=本修复环工单 R1-3 指令）。
- 门禁：backend billing/outpatient/nursing 三模块 `mvn verify -DskipITs` 全绿（Spotless+单测+
  JaCoCo 双阈值）；web `pnpm lint/type-check/test/format:check` 四连全绿（workstation 361 用例）。

## 2026-10-05 · P2 PR-4E 立项（事件与工单收敛包）

- 占用迁移号 V1115（integration.event_registry id 17 billing.fee.created payload_desc 追加
  visitType 组件语义句——先记再占，全局最大 V1114）。
- 范围：W-67 死信分流（dispense.completed 两消费方判别子跳过+fee.created 载荷扩 visitType
  净解）+W-47 患者读面 SENSITIVE_QUERY 审计（11 GET 端点）+A-6 上报 wardId 词表（nursing_
  ward_config requireConfig）+NursingRateGuard 频控骨架（A-6 上报限频+A-8 PDA 枚举冷却）+
  W-41 收费页 payerType UI 参数化（preview+settle 双硬编码联动）+W-27 号源超时 tick 三件套
  （克隆 nursing delay.task-overdue 先例）+T-R3-4 quorum TTL 探针实测+TASK 六工单销项
  （W-38/W-61/W-62 随 PR #59 闭环补注记+W-27/W-41/W-47/W-67 本册 ✅+T-R3-4 回填销项）。
- 裁决依据：总纲既有裁决重申（W-67 判别子+visitType 净解/W-41 仅 SELF_PAY 门/W-27 仅号源
  tick/GC21④ 不并入）；fee.created 载荷扩 visitType 走 V1115 通用段。

## 2026-10-05 · P2 PR-4C 收口（大屏通道与病区防线包）

- 交付：11 任务全落地（立项→wardId 携带→哨兵 REST 限行→WardAccessService→V1114 种子→九端点守卫→
  WS SUBSCRIBE 防线→前端令牌附调+D-5+gen:api→D-4 WS 行 TTL→Spec 注记销项→收口），分支
  feat/p2-pr4c-board-ward 共 12 笔（3542697→59b20e6 含修复环）。
- 终验：后端 21 模块全量 verify BUILD SUCCESS+前端六连全绿（lint/type-check/test/build；audit 归 CI
  权威——本地 registry TLS 拦截在案）；真机 e2e 三面取证（匿名三端点 200/越区订阅 ERROR 帧/写面 403
  SYS-1032，证据留 SDD 工作区 probe/）；五路评审（A 安全 85/B 架构 86/C 数据 88/D 前端 85/E 测试 88）
  全 APPROVE with findings，must-fix 四项修复环 R1 收口（59b20e6）。
- 修复环 R1（Important 四项去重后）：①A-1 WS SEND 帧跨病区注入封堵（防线扩 SEND 帧，拒绝摘要
  「仅可订阅」→「仅可访问」同步 IT 锚）②C-F1 V1114 种子行从患者详情卡责任分配清单排除
  （WardMetaServiceImpl.detail 消费侧双判过滤，同 NurseBoardServiceImpl 先例）③D-1/E-F1 前端令牌缓存
  到期重签时间维度覆盖回补（bigscreenToken.spec fake timers 用例，承接随 W-68 收敛删除的原
  useNursingStomp/useQueueStomp 时间维度用例）④A-2 iot WS 订阅面缺口登记 TASK.md W-90（WS 面注记）。
- D-21 断言纪律申报（本册全部）：AuthFlowIT 第 9/10 步新增；哨兵 allowlist 冻结镜像断言+403 分支
  上下文清理效果断言（338b7f7→9397b74）；**WardPatientRetirementIT 适配**（W-IT-9005/9006 双锚
  +补绑定行 9114000000000000102/0103——fail-closed 上线后既有 IT 红为预期行为变更，同步造绑定行
  收紧，Task 6 审查点名补记）；NursingConnectAuthInterceptorTest 三态口径变更（「不触达校验器」→
  「null 入校验契约归一返 null」，换链 verifyAccessPrincipal 必要适配）；NurseBoardWsIT 哨兵 REST
  @Order(4)+WS @Order(5) 新增；useNursingStomp/useQueueStomp spec 缓存用例改写（缓存本体迁移
  bigscreenToken.spec 语义等价承接+R1 补时间维度）；NursingSubscribeWardInterceptorTest SEND 用例
  收紧（「SEND 直通」→「SEND 到 board 同受限行」，评审 A-1）；WardMetaServiceImplTest 详情卡
  分配断言扩种子行排除。
- 工单登记：W-90（iot/ward Long 型端点+iot WS 订阅面留 PR-4D 收敛——收口评审 A-2 补 WS 面）；W-91
  （nurse_assignment 生产化收尾：nurse_id 索引+V1114 种子环境门控+幂等谓词窄化——评审 C-F2/A-5/B-2
  合并登记）。W-39/W-40/W-68 ✅ 销项+D-4/D-5 随册收口（TASK.md）。
- Minor 留档不阻断（五路）：B-1 allowlist startsWith 语义宽于声明（无现实暴露）/A-3 allowlist 未约束
  HTTP 方法（路由 405 兜底）/A-4 哨兵常量镜像双源失配窗口/C-F3 空操作者双轨不对称（不可达）/
  D-2 in-flight 不区分 wardId（单病区不可达）/D-3 已连接态换病区重签（当前编排不可达）/D-4 泛哨兵
  跨页令牌耦合注释缺/E-F2 infusion-board 尾段一致性无专测/E-F3 泛哨兵拒未断言 verifyForbidden——
  均登记于评审档案 `.superpowers/code-review-pr4c/findings-*.md`，随 W-90/W-91 或 PR-4D/E 批量收敛。

## 2026-10-04 · P2 PR-4C 立项（大屏通道与病区防线包）

- 占用迁移号 V1114（nurse_assignment 演示/运维账号绑定行种子——先记再占，全局最大 V1113）。
- 范围：W-39 哨兵 REST 限行（三端点 allowlist+wardId 一致性+SYS-1032）+令牌携 wardId
  （SessionData/SessionUser 扩展+TokenPrincipal api 面）+W-68 bigscreen 令牌附调+A-2 WS
  SUBSCRIBE 病区防线（哨兵单病区+登录态绑定集）+W-40 方案 A fail-closed（九读端点守卫
  NS-1028+绑定行种子同批）+D-5 三 app randomUUID 降级+D-4 大屏 WS 增量行 TTL。
- 裁决依据：总纲 D-29/D-31 与评审 A-5（组合①+②，禁白名单化）；r1 §3.4 灰度语义已被 D-29 推翻
  （无 ACTIVE 绑定行一律 403）；范围边界=iot/ward 域 Long 型 wardId 端点不挂守卫（双标识空间
  W-74 在案，留 PR-4D/W-74 收敛）。

## 2026-10-04 · PR-4B 五路评审修复环：两门槛项收口 + W-88/W-89 登记

- **C-F1（85）**：退药数量 scale≤3 前后端双钉——后端 DispensePlanServiceImpl.parseReturnQuantity
  stripTrailingZeros 后 scale>3 抛 PH-1016（对齐 dispense_item DECIMAL(12,3)，防 PG 静默舍入致
  PART/FULL 终态与 returned_qty、事件载荷勾稽漂移，单测三面：>3 拒/恰 3 过/整数过）；前端
  InpatientDispenseView isValidReturnQty 正则收紧 `^\d+(\.\d{1,3})?$`（文件头与 D-8 注释同步）。
- **E-1（85）**：contextOperatorId「令牌身份非空非数字」守卫分支补覆盖——AdverseEventServiceImplTest
  与 NursingTaskServiceImplTest 各补 NS-1019 零写库用例；NursingTaskServiceImplTest 认领守卫
  DisplayName 原虚报「缺失/非数字」实未跑非数字面，补齐分支后如实（方法名同步扩语义）。
- **D-21 申报（前端 D-8 用例族新增非法形态断言=收紧非放宽）**：badQty 族增 '0.1234'（4 位小数），
  warning 计数断言 (4)→(5)——新增非法形态、严格度提升；正则收紧实现+断言修订+回归锚同笔交付。
- **工单登记**：W-88（破码副授权人工号无存在性校验与服务层 null 不设防，评审 A-2[70]/A-3 合并）、
  W-89（D-3 族 loading/error 语义一致性收口，评审 B-2[60]+D-F1/D-F2 合并）。

## 2026-10-04 · P2 PR-4B 收口（临床操作面语义包）

- **W-72**：四域操作人服务端强制（方案 A 字段保留+令牌覆盖+匿名通道保留）+A-4 破码 primary 收敛
  （两人不同改服务端比较）+InpatientDispenseView 去手输+PdaView 双授权主授权人展示回显。
- **W-66（D-30）**：可退明细读面端点+退药弹窗多行化（含 D-8 数量正则/范围双验收口、住院追溯码必拒输入面删除注记）。
- **D-31 顺手包**：D-3 分页慢回包守卫（usePagedList/useExecutions 序号守卫三消费面收口）+D-11 patientId 死字段清理。
- **契约**：gen:api 再生成（八身份字段可空化+returnable 读面）；迁移零新增。
- **D-21 申报汇总**：UT 执行人断言令牌化（EXECUTOR→NURSE 差异锁定用例新增）；IT 回签 executor_id="66"→登录管理员身份；
  claim assigneeId 空守卫用例随消费面删除（同笔新增令牌锁定用例）；收口终验另修 InpatientDailyDecomposeIT
  捕获帧按医嘱号分拣（终验暴露既有欠账：候选查询无 ORDER BY 致分解帧消息序不确定，去序依赖、断言严格度不降）。
- **终验与 e2e**：后端全量 verify BUILD SUCCESS（两轮，第二轮 44:30 含 IT 修复）；前端六连绿（pnpm audit 本地受
  TLS 拦截阻断、归 PR CI frontend job 权威承载）；真机 e2e 五面全 PASS（PDA 执行链/摆药签收/多明细退药/
  不良事件双路匿名/D-3 弱网竞态，证据 `.superpowers/sdd/p2-pr4b/probe/`）；e2e 新发现 W-86/W-87 工单登记。

## 2026-10-04 · P2 PR-4B 立项（临床操作面语义包）启动

- **范围**：W-72 服务端强制四域（执行单 start/finish/needleOut+破码 primary 收敛/摆药 receive/任务认领/
  不良事件 report·handle·close·return——方案 A 字段保留服务端覆盖，匿名上报通道保留）+InpatientDispenseView
  去手输+gen:api 再生成+W-66 多明细退药（读面+弹窗多行化，D-30）+D-3 分页慢回包守卫+D-8/D-11 前端小修对（D-31）。
- **移交判断**：M-2 已随 PR-4A 修复环 ce11b2a 收口；M-4/W-81 扩面走独立工单——均不并入本册。
- **迁移**：预计零迁移（如需即回计划裁决，号段 V1114+）。

## 2026-10-03 · Flyway 号段登记：V1112/V1113 补课索引（C-4/C-5，先记再改）

- **号段登记（先记再改，PR-4A Task 6 落盘）**：通用段 V1112/V1113 两件——撰写期实测全局最大
  已应用 V1111，V1112 > V1111 乱序守卫通过。归属：V1112 pharmacy.dispense 计划号部分索引
  idx_dispense_dispense_plan_no（C-4：住院链 receive/acceptInpatientReturn 按 dispense_plan_no
  等值点查全表扫描根治，门诊行 NULL 不进索引）；V1113 nursing.order_execution visit_id 部分
  索引 idx_execution_visit（C-5：casCancelByVisit/casRedirectWard 两支批量 CAS 首要谓词
  visit_id 等值点查，status IN 残余行个位数不进索引）。依据：PR-4 评审遗留调研 r3 §二设计候选
  （`.superpowers/pr4-research/r3-review-leftovers.md`）；登记载体
  docs/migrations/flyway-version-registry.md 同 PR 同步更新。

## 2026-10-03 · P2 PR-4 立项（多计划连续执行）· PR-4A 工程与 CI 基座包启动

- **总纲**：docs/superpowers/plans/2026-10-03-p2-pr4-overview.md——五册拆分（A 基座/B 临床操作/C 大屏病区/E 事件工单/D 全量 403）
  连续执行，每册 SDD→范围全量终验→（如涉联调）真机 e2e→/code-review→合入 dev→开工下一册（用户 2026-10-03 流程指令）。
- **裁决固化**：D-28 全量端点 403/D-29 W-40 fail-closed+绑定行种子/D-30 W-66 入 PR-4B/D-31 顺手包全选
  （W-70③→本册，D-3+D-8/D-11→B 册，D-4→C 册）。
- **PR-4A 范围**：W-73 CI 过滤修复/技术债快修批/E-1 补测/V1112·V1113 索引/W-70①②③；迁移号段占 V1112/V1113。

## 2026-10-03 · P2 PR-4A 工程与 CI 基座包收口

- **范围销项清单（工单销项 + /code-review 评审 finding 编号一并列明）**：W-73 CI changes job
  路径过滤修复（dorny/paths-filter v4.0.3 + some-with-excludes）；W-64/W-65 裸 now 钉面；
  B-2 NursingProperties @Validated；B-3；E-2；E-3；W-70①②③（①出网用例钉北京钟面 ②enableAutoUnmount
  经 bigscreen test-setup 落地 ③CI frontend job Vitest 时区双跑）；B-1（W-74 配套登记在案）；
  A-7②；C-3 注记；B-5 批量修正 19 处；E-1 六用例；C-4/C-5 V1112·V1113 补课索引；W-74 登记
  （NurseBoardView.vue 双标识空间如实申报）。
- **终验结论（主控预跑）**：后端全量 verify 24 模块 BUILD SUCCESS（25:41）；前端六连全绿——
  audit 按 CI 口径带 W-71 在案豁免参数通过（braces GHSA-vfj7-8cjw-p6xm 点名豁免）。
- **W-73 探针终验义务移交主控**：本册合入 dev 后以 docs-only PR 复验（changes 输出
  backend/frontend=false 且双 verify skip、required check 语义维持）。
- **新登记 W-81**：DashboardView formatClock 渲染侧本地墙钟回显——渲染侧北京钟面钉面缺口，
  轻量收口（详见 TASK.md W-81 行）。

## 2026-10-03 · CI 门禁补丁：backend job 超时线 40→55 分钟（runner 波动撞线两连杀）

- **范围**：`.github/workflows/ci.yml` backend job `timeout-minutes` 40 → 55。
- **理由**：PR #63 修复环三笔（dc43bfb/809e71f/d2a6a92）推送后 CI run 37105473638 两次
  尝试均在 **40 分 16/18 秒被超时线击杀**（非测试失败：commitlint/changes/hygiene/frontend
  四 job 全绿，Maven 步骤无任何报错输出）；同代码基线 c91db7b 当日早上 backend 仅
  23m28s（run 37095628097），修复环后端增量仅 pharmacy 一 Mapper 方法+单测（本地模块
  verify BUILD SUCCESS），不足以解释 +17m——判定为 GitHub 托管 runner 环境波动
  （Testcontainers 镜像拉取/磁盘 IO 时段性变慢）。
- **裁决**：40m 线本就偏紧——PR #62 backend 实测 35m4s，余量仅 13%；上调至 55m
  （对最差观测值 40m 留 37% 余量）。若 55m 仍撞线则排除环境波动假设，转入本地
  复现七验收 IT 排查挂死（修复环 dc43bfb 改动 DispensePlanMapper 幂等插入形态，
  全链 IT 本地未跑过——`-DskipITs` 门禁盲区，届时按 IT 复现流程处置）。

## 2026-10-03 · PR #63 合并前修复环：/code-review 门槛项三点收口 + W-72 登记

- **范围**：C-1 摆药 generate 事务毒化修复（dc43bfb，DispensePlanMapper 新增
  `insertIgnoreOrderTimeConflict` ON CONFLICT DO NOTHING 与 V1110 部分唯一索引谓词
  逐字咬合，净除毒化 catch）；D-1 不良事件上报 occurredAt 出网线改 `toISOString()`
  带偏移形态（809e71f，修复后端 OffsetDateTime 反序列化必败致 UI 上报链路不可用）；
  D-2 大屏 metricText 补 nullish 双判（809e71f，「余量 null ml」渲染瑕疵）；TASK.md
  登记 W-72（临床留痕操作人身份客户端供给——门槛项 A-3 工单化归 PR-4 待产品裁决）。
- **理由**：PR #63 /code-review 五路评审（A 安全/B 架构/C 数据/D 前端/E 测试，40 候选
  →合并去重 36 条）终评门槛项 3 条——C-1（85，并发兜底在其设计场景整体失败）/D-1
  （80，功能链路不可用）随修复环合并前收口（scoped 复审 APPROVED：谓词逐字咬合、
  D-21 八条断言迁移无放宽、零越界 7 文件）；A-3（85，修复策略三选一涉产品语义）超主控
  裁量，工单化随 PR-4 与 W-37/W-39 操作人可信面系统收敛。
- **裁决留痕**：A-1（匿名令牌过 401 门）经主控取证归并 W-39 已知工单族（机制在案
  逐字重合），真实增量=本 PR 使暴露面扩大至临床写面，作 PR-4 优先级佐证；C-2（同日
  重入漏判）经取证下调——compose+Dockerfile 双源钉 TZ=Asia/Shanghai，真栈读回 +08:00
  表示 contains 命中，非确定性失败；评审证据链留档 `.superpowers/code-review-pr63/`
  （findings-A~E/merged/scores-final/brief-fixround/fixround-report/review-fixround.diff）。

## 2026-10-03 · CI 门禁补丁：前端 audit 点名豁免 braces 无补丁 advisory（GHSA-vfj7-8cjw-p6xm）

- **范围**：`.github/workflows/ci.yml` frontend job audit 步骤增补 `--ignore=GHSA-vfj7-8cjw-p6xm`
  点名豁免参数（含注释留痕）；TASK.md 登记 W-71 追踪工单。
- **理由**：PR #63 CI 第三轮（2026-10-03）frontend verify 挂于依赖漏洞审计——上游 advisory
  滚动新判 braces<=3.0.3 全量 high（栈耗尽 DoS）且 **Patched=None 无补丁版本可升**（版本红线
  的升级路径不存在）；引入链为 devDependency 工具链
  （@vue/eslint-config-typescript>fast-glob>micromatch>braces）不进生产构建产物——属上游
  事件非本仓引入，不豁免则 dev 后续一切 PR 被阻断。
- **裁决**：走 pnpm 官方点名豁免机制（窄面单 advisory，非 --ignore-unfixable 宽面）+注释
  留痕+W-71 工单追踪；上游发布补丁后随依赖升级提案（Renovate/Dependabot）落地时同步移除
  豁免参数。共存 1 moderate 低于 high 阈值不阻断，随升级顺带收敛。
- **同轮 CI 事实留痕**：第三轮 backend verify PASS（21m18s）+commitlint/changes/hygiene 过；
  第二轮 frontend Vitest 五用例挂为 NurseBoardView 回显时区敏感（CI=UTC），时区修复环
  1a2576e 回显钉北京钟面修复（Task 17 报告⑤节），W-70 工单化（DischargeManageView 预存
  敏感+spec 泄漏放大器+前端时区双跑纪律建议）。

## 2026-10-03 · P2 PR-3 收口：真栈五环节闭环演示 + 三 Spec 落地注记 + 工单销项五项（W-34/W-60/D-23/D-24/D-25）

- **范围**：PR-3 收口面（SDD 计划 Task 19）——真栈探针五项取证、05-nursing §14 /
  06-pharmacy §13 / 04-inpatient §14 落地注记、TASK.md 五项销项、P2 计划 §3 PR-3 完成标注、
  `InpatientVisitEventListener` TODO(P2-PR3)→TODO(P3) 改标（非行为变更）。
- **真栈探针结论（2026-10-03，compose 全栈 healthy）**：① 迁移计数 V110*=10（V1100–V1109）、
  V111*=2（V1110/V1111），总迁移 89 件全 success；② `integration.event_registry` 总行 83
  （id 83 = nursing.adverse-event.reported 在册）；③ 容器内 `/v3/api-docs` 200（244KB）且含
  `/api/v1/nursing/executions` 与 `/api/v1/pharmacy/dispense-plans` 路径族；④ compose 六服务
  全 healthy；⑤ 五环节闭环演示链全链 2xx+库态断言全过——入院四步→LONG bid 静脉医嘱开立→
  药师审方（异人）→转抄核对（compensateToday 当日时点计划+护理计划执行单）→摆药五步
  （PIVAS/deliver 半步/出库流水）→签收（执行单批量 SIGNED+INFUSION 升格+监测建链）→袋签
  核对→开始输注（iot 消费留痕）→告警信封直投（escalation_count=1+任务零新增+大屏
  INFUSION_ESCALATION 帧推送日志）→拔针（COMPLETED/挂接 ENDED/自动入量 250ml/泵解绑/
  M04 回签 EXECUTED/对账 CONFIRMED）；大屏截图经 playwright-cli 真机留痕。
- **降级清单汇总（详见三 Spec 落地注记与 PR 描述）**：W-34 退役五项履行+conditionTags 降级；
  大屏 M14 聚合降级为前端组合+危急值空段；打印/通知降级顺延 P3；iot_sync_interval/
  conflict_window 列落消费后置；毒麻专册与摆药机 P3 预留；退药开关校验归发起端（W-66）；
  出院申请 board 推送与在途任务 remark 追加归 P3；STAT 单次计划转抄链不发
  order-plan.generated（护理计划执行单仅日切/补偿面承载——真栈实测边界，P3 复核）。
- **工单销项（五行，履行完毕）**：W-34（退役五项，Task 7/13/14 履行+WardPatientRetirementIT）、
  W-60（OfflineDetector 收敛，Task 12 履行）、D-23（V1108 部分唯一索引+冲突回查独立事务，
  Task 2/7 履行）、D-24（维持连续区间零代码，裁决留痕本文件 2026-10-01 立项条目 ⑤）、
  D-25（InpatientOrderCreateRequest/InpatientOrderItemRequest 改名，Task 13 履行）；
  W-42/W-43/W-45 留单（本 PR 未拾取，PR 描述留痕声明）；W-66/W-67 在案（PR-3 新登记）。
- **真栈新发现（呈报留痕，处置归主控）**：① 护士站大屏 REST 首屏三端点
  （/nursing/board、/ward/infusion-board、/iot/alarms）不在 AUTH_WHITELIST 而 bigscreen
  http.ts 禁注入 Authorization——真栈 401 降级轮询（WS 链路经 bigscreen-token 正常）；
  ② billing.fee.created 住院行遭 outpatient 消费方死信（与 W-67 dispense.completed 同族，
  载荷守卫未适配住院行）；③ 陈旧构建产物 V6/V7 迁移残留在 fuyun-integration target/classes
  （源已改号 V500/V501），对既有卷起栈 Flyway 校验失败（fresh 库因 IF NOT EXISTS 吸收）——
  `mvn clean` 全量重建即除，CI clean 构建无此患。

## 2026-10-01 · P2 PR-3 立项：M05 护理完整 + M06 住院摆药衔接（V1106–V1111 号段 + id 83 + 错误码排定 + 依赖增量与 D-23/D-24/D-25 裁决落档）

- **范围**：P2 阶段 PR-3 切片（FU-M05-04/06/07/08/09 + FU-M06-05 + W-34 退役五项 + W-60 收敛 +
  iot 联动/输液消费骨架回接），功能口径以 05/06 模块 Spec v1.1 为准；计划文档
  `docs/superpowers/plans/2026-10-01-p2-pr3-m05-m06.md` 随本条目入库（GC-36）。
- ① **迁移号段登记（先记再改，随 Task 2/3 落盘）**：通用段 V1106–V1111 六件——撰写期实测全局
  最大已应用 V1105，V1106 > V1105 乱序守卫通过（nursing 固定段 V800–V899 已被守卫封死禁用）。
  归属：nursing 四件（V1106 执行域三表 order_execution/execution_check_log/infusion_monitor_link；
  V1107 不良事件表 adverse_event + nursing_ward_config P2 六列；V1108 W-34 表改造
  nursing_ward_patient DROP status/source + D-23 部分唯一索引；V1109 event_registry id 83 种子）；
  pharmacy 两件（V1110 dispense_plan 表 + dispense 住院扩列；V1111 id 28 载荷契约 UPDATE，
  只增不删，双向评审声明进 PR 描述）。
- ② **事件 id 83 排定**：`nursing.adverse-event.reported`（producer=nursing，落 V1109；撰写期
  实测 event_registry 最大 id=82）；`MessagingGovernanceIT` 总行断言 82→83（Task 2 落）；id
  61/62/63/64 由 P1 占位转实装，登记行不改（载荷契约按 V800 冻结文本出网）。
- ③ **错误码排定（撰写期实测 NS 段最大 NS-1019、PH 段最大 PH-1022）**：NursingErrorCode 续号
  NS-1020~1027（EXECUTION_NOT_FOUND 404 / EXECUTION_STATE_NOT_ALLOWED 409 /
  EXECUTION_CHECK_FAILED 409 / OVERRIDE_CHECK_INVALID 409 / INFUSION_NOT_ACTIVE 409 /
  ADVERSE_EVENT_NOT_FOUND 404 / ADVERSE_EVENT_STATE_NOT_ALLOWED 409 / EXECUTION_TIME_WINDOW 409）；
  pharmacy 续号 PH-1023~1026（DISPENSE_PLAN_NOT_FOUND 404 / DISPENSE_PLAN_STATE_NOT_ALLOWED 409 /
  DISPENSE_PLAN_ORDER_INVALID 400 / WARD_RECEIVE_INVALID 409）。
- ④ **模块依赖两处增量及无环论证**：nursing pom 增 fuyun-inpatient（仅 api 面：新增
  `OrderExecutionConfirmPort`，执行单 COMPLETED 后进程内直调回签，不走 HTTP 自调）；iot pom 增
  fuyun-nursing（仅 api 面：新增 `NursingTaskLinkagePort`，联动 NURSING_TASK 动作进程内直调）。
  无环论证：inpatient 不依赖 nursing/iot，nursing 不依赖 iot——两处增量均不成环；禁
  nursing→fuyun-iot 任何形态依赖（与 iot→nursing 成环），nursing 对 iot 数据一律经事件
  （iot.alarm.* 载荷自带 patientId/visitId/deviceId）与前端组合（复用
  `GET /api/v1/ward/infusion-board/{wardId}` 与 /ws/iot 主题）；`ApplicationModules.verify()`
  随 fuyun-app 门禁自动把关。
- ⑤ **三项裁决落定（P2 计划 §3 PR-3 在案工单条目授权「结论在 PR-3 SDD 计划拆分时落定」，随
  计划批准即生效；以下照计划范围声明节原文逐字落）**：
  - **D-23 根治**：走部分唯一索引 + 冲突回查合并（D-22 同款两层兜底范式）——`nursing_record` 上 `(visit_id, record_date)` 部分唯一索引限定「auto_generated=true AND abnormal_flag=false AND deleted=0」正常合并行形态，`appendObservation` 合并分支捕获 `DuplicateKeyException` 后回查重试一次；DDL 落 V1108（通用段）。理由：用户 2026-09-29 总裁决「宪法为唯一标准，偏离一律收拢」，先查后插竞态属一致性路径隐患，根治成本一段索引+一个 catch 分支。
  - **D-24 维持现状**：量表条目维持 P1 连续闭区间取值域（Task 8 冻结用例 4/6 有效不动），不回归经典离散档位。理由：无业务方提出离散档位需求，冻结用例已按连续口径验收，维持零代码；Task 19 删 TASK.md D-24 行并在 CHANGELOG 留痕。
  - **D-25 选①改名**：住院 DTO `com.fuyun.inpatient.dto.OrderCreateRequest` 改名 `InpatientOrderCreateRequest`（出网 schema 名随之收敛），不动 springdoc 全局命名策略。理由：单点改名影响面=inpatient 模块两处方法签名+OpenAPI 生成物+前端一处类型别名，方案②全局 NamingStrategy 影响全仓 schema 名（存量前端类型全部漂移）；Task 13 落地并同步删前端本地 `OrderCreatePayload` 回归生成物。

## 2026-10-01 · 时区纪律专项立项（P2 第一步修复前置项：34 处裸 now() 收敛北京钟面 + 红线）

- **根因**：终验报告 3.5a 呈报——全仓裸 `LocalDate/LocalDateTime/LocalTime.now()` 34 处，CI（UTC JVM）在北京 00:00–08:00 取错医疗日/业务窗；BUG-03 残余三处（PR #60=6c58768）已实证缺陷模型（班次小结查空、观察行该合并不合并），属医疗业务真实缺陷。
- **任务面**：A 类 21 处医疗日界/业务窗口语义改 `now(HEALTHCARE_TZ)`（billing 2/inpatient 2/nursing 8/outpatient 8/system 1，TDD 先红后绿+各域 UTC 锚定用例）；B 类 13 处号段/TTL 锚技术日切同款显式化（序号门 4/号段 5/TTL 锚 4，生产 TZ=Asia/Shanghai 下逐字等价零行为变）；`TimeConstants.HEALTHCARE_TZ` 下沉 fuyun-common、`NursingTimeConstants` 改委托。
- **红线（随专项立，持续生效）**：业务日期/医疗日界一律 `now(HEALTHCARE_TZ)`，号段/TTL 锚同款显式化；禁新增裸 `now()`；Windows 本地时区验证一律 `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`（JVM 不读 TZ 环境变量），生效凭证=日志 `Picked up`。
- **验证**：双时区全量 verify 双 BUILD SUCCESS + A 类各域窗口边界锚定用例 + PR CI 六 job 全绿。
- **终局回填（2026-10-02）**：站点终局 44（A 类 31=billing 2/inpatient 7/nursing 11/outpatient 8/system 3，含 broaden 增补 10 处；B 类 13=序号门 4/号段 5/TTL 锚 4）；必然同步终局 106=A/B 期 71+双时区终验分歧窗暴露三批修复环 35（InpatientDailyDecomposeIT 15、fuyun-app 八 IT 15+池定位配套 1、OrderPlanServiceImplTest 4——日切分歧窗北京 00:00–08:00 内期望/造数面与生产北京钟面号段/业务日错位显形，第一轮 15:40Z 贴窗缘未显形、第二轮 16:1xZ 入窗八例红）；确定性分歧时区锚 9；即时时刻/instant 窗口/纯回显三类出界不动（anchor-pattern 裁定）。红线重申：禁新增裸 now()，业务日期/医疗日界一律 now(HEALTHCARE_TZ)。验证终局：默认时区全量 verify BUILD SUCCESS（16:49 min）+UTC 全量 verify BUILD SUCCESS（16:13 min，Picked up 凭证，Finished 17:12:02Z=北京 10-02 01:12 分歧窗内）+Etc/GMT-14 整仓 test BUILD SUCCESS（01:57 min 零失败，+14h 反方向实证）+三批修复后 UTC/缺省/GMT-14 定向全绿。

## 2026-09-30 · 批次 G 前端压轴组总收口（EX-42~49：重复族下沉/通用范式沉淀/巨型组件拆分/健壮性与数据丢失防护，波 1 三路+波 2 核证收口+余项统筹）

- **根因**：FE-Q1 重复族（surfaceBizError ×16/formatTime ×15/STOMP ×3）+FE-Q1-06 与 FE-A4-05 样板复制（41 处 loading 骨架/16 处三段式分页）+FE-A4-03/04 巨型组件（WardBoardView 2760 行/AppointmentView 916 行）+FE-A1 运行错误五点+FE-A2 数据丢失四点+BE-Q1-02 访问器副本。
- **波 1 三提交**：EX-44 inpatient 访问器下沉（3a9f6c2，实扫 17 处=登记 11 处超集全量下沉）；EX-47 WardBoardView 拆 11 作业面 composable+wardBoardShared（4e2a96f，script 1171→约 190 行+三处行为修复先红后绿+新增 35 用例）；EX-48 portal 预约页三 composable（fd48ba7，src/composables/ 落位+.gitkeep 清理）。
- **波 2 两提交**（中断专员半成品 14 文件主控核证可用后收口，744+/50- 与交接登记吻合）：EX-45 余四点（b801b4b，医生站竞态守卫+判空/结算判空+附带竞态守卫/物模型稳定行键，6 用例先红后绿；FE-A1-04 已随 EX-47）；EX-46 四点（7f71dab，删除确认+草稿守卫/建档路由守卫（登记措辞与页面形态不符按整页表单选型）/告警版本比对（强于授权降级）/字典覆盖防护（授权降级留痕），11 用例）。
- **EX-43 重复族下沉**（ea00488）：两族收拢 app 级 utils/bizError.ts+timeFormat.ts（函数体逐字保持），wardBoardShared 删双定义回归 nursing 专属；tempChart 变体实质不同不收拢；STOMP 三文件令牌策略/订阅登记/帧管线全分叉且 B.1 禁 shared 依赖 vue——留痕不下沉。
- **EX-42/49 统筹四提交**（试点+全量三组）：沉淀 useAsyncTask（loading/error/run，onError 可注入默认静默、竞态守卫随任务体保留）+usePagedList（页码四态+1↔0 基转换+契约兜底）附 14 单测+试点 3 文件（fc1640c，285=271+14）；全量三组并行 33 处/23 文件迁移（ec6979d/c46c234/8490cd2，iot/ward 14+inpatient/outpatient 11+billing/patient/pharmacy 9，285 持平 spec 零改动）；PatientDetailView 原无 catch 经 onError 重抛保持上抛语义（主控裁决留痕）。
- **顺带清账**：CI format:check 11 文件 Prettier 偏差（EX-47 拆分提交遗留，实证 HEAD 即失败）独立 style 提交清账（705f0c7），CI frontend job Prettier 只读校验恢复绿。
- **验证**：workstation 285（254→271→285）/portal 33/bigscreen 68 门禁全绿+lint/format:check/type-check 全过；既有测试断言全程零改动（行为保持红线）；新交互语义（EX-46 确认框/离开守卫）与三处行为修复（EX-47）入 N5 行为变化清单。

## 2026-09-30 · 批次 F 性能收拢组总收口（EX-37~41：批量写/键集批查/精确投影/IoTDA 防御/前端隐藏暂停，六路并行+前端补派）

- **根因**：BE-B3-04~12（循环逐行写）+BE-C4-19/20（循环内逐行读写）+BE-C4-25/27/28/29/30（全列取回仅用少量列）+BE-B4-01（IoTDA 客户端无超时/重试/熔断）+FE-B1-01（看板隐藏不暂停）。
- **批量写六模块**（A.4.3-16，0 行防线 CAS 全部逐行保留甄别）：pharmacy Dispense 五段批插/补丁批更（0274d5d，衔接 EX-24 五处明细补丁化）；inpatient 医嘱明细/计划批插（821dc79，DuplicateKeyException→IP-1023 语义经 Spring 翻译链等价保持）；outpatient 放号两批写（b9d9962，装配块裁决维持）；billing 退费判态批更+组合成员批插（938bed7）；iot 产品命令/映射批插（8b55ad9）。
- **键集批查/批量清理**：outpatient QueueZsetStore 失效票 selectBatchIds 前置+ZREM 单命令批清理（af4238f，可叫票 Lua 守卫保留）；patient 拆分回挂 listByIds 前置+updateBatchById（2ef256d，灶位甄别=split 回挂面呈报采纳，EX-21 CAS 零触碰）。
- **精确投影五处**（A.4.3-14，OPT-12/13 先例）：inpatient 转科分野 1 列（821dc79）/billing 结算清单 2 列（d5547ca）/iot 失配与频率 2 处 1 列（8b55ad9）/patient 近窗档 4 列（20021db）。
- **EX-40 IoTDA 防御配置（B.4-2）**：HttpConfig 超时 10s/30s（SDK 缺省 60/120s 与人机链路失配）+invoker 连接级重试 2 次（仅请求未送达，非幂等写安全）+熔断自实现（5 次开断/30s 半开/4xx 不计入）；错误通道 IOT-1022 零变化（8fa6328）。
- **EX-41 前端隐藏暂停（web 宪法 B.3-4）**：InfusionBoardView visibilitychange 对齐 TriageBoard 先例——隐藏期三路刷新停发、恢复立刷；告急提示条仍落（94dc077）。
- **验证**：六模块门禁全绿 pharmacy 178（+5）/inpatient 198（+3）/outpatient 341（+2）/billing 297（+4）/iot 576（+7）/patient 253（+3）+workstation 219（+2）+spotless/lint 过+主控六模块联跑终验 BUILD SUCCESS；对外行为零变化（放号 409 message 单日→窗口期、EX-40/41 新防御语义入 N5 清单）。

## 2026-09-30 · 批次 E 资源组总收口（EX-30~36：可观测降级/线程池生命周期/测试资源回收，两路并行）

- **根因**：BE-A1-02/03（catch 静默降级无可观测留痕）+BE-A5-01~05（无界池/无 shutdown/测试执行器驻留）——资源组七项。
- **可观测降级两处**：EX-30 LinkageRuleVO JSONB 畸形原文降级 warn 留痕（ruleId/field+异常摘要，可空契约零变化，6e2ba70）；EX-31 Dashboard 最新值解析降级 warn（deviceId/metricCode；否决 isNumeric 前置守卫——与 BigDecimal 负数/小数/科学计数口径不一致将误杀合法值，680e870）。
- **线程池生命周期两处**：EX-32 CommandDispatcher 无界 newCachedThreadPool 改有界 ThreadPoolExecutor（4/16/32/keepAlive 60s，低频管理面口径注释论证；CallerRuns 保命令不丢）+DisposableBean destroy 三段收口（1c1842e）；EX-33 模拟器 CommandSubscriber 补 close（iot-simulator 纯 Java 零 Spring 模块对齐 shutdownHook 显式形态，停机链取消任务→停调度→关订阅→断连接；Paho 回调零外抛不变量守卫，a5a9d5b）。
- **测试资源回收三处**（fuyun-app failsafe 统一承载）：EX-36 IotTelemetryPipelineIT STOMP 调度器 @AfterEach 登记回收（3ee8ddc）；EX-34/35 Billing/Pharmacy GuardIT shutdownNow 移 finally（OutpatientPoolConcurrencyIT 0eb66a1 同型+复现锚点用例：动作异常后 worker 线程 join 有界退出，44db981/f5b5a65）。
- **验证**：iot 569（+6）/iot-simulator 55（+2）/billing 293/pharmacy 173 全绿+定向 IT 真栈绿（IotTelemetryPipelineIT 9/9、GuardIT 12 用例）+spotless 过+主控 14 模块联跑终验 BUILD SUCCESS；对外行为零变化（内部线程池过载语义留痕注释）。

## 2026-09-30 · 批次 D 一致性组总收口（EX-21~29：CAS 收口/守卫折语句/软删谓词/补丁回写/原子累加/Lua 原子/portal 缓解，五路并行）

- **根因**：BE-A2-02~05（读后判/读改写并发面）+BE-B5-02/05/06（条件更新/原子累加/Lua 原子）+BE-A3-02（portal 冒名，用户裁决③转正）——一致性组九项。
- **CAS 收口四处**：EX-21 合并审批 casApproveProcessing（PROCESSING/FAILED 谓词+审批人同语句落库，0 行重读定性 PAT-1007/1008，613422b）；EX-25 就诊卡四写 cas 族（bind 无主谓词/loss/unbind ACTIVE 谓词/replace LOST 谓词，PAT-1011/1012，c7edbca）；EX-22 网关删除 standby 守卫折入置删语句（NOT EXISTS 反查同 UPDATE，IOT-1025 拒删/行消失幂等留痕，1ead926）；EX-24 pharmacy 四写点整行回写改指定列补丁（update 吞 mapInsurance 对照列/mapInsurance 吞档案面/pick/verify 吞留痕快照，BUG-07 先例；PrescriptionServiceImpl.create 自建行持锁保留举证，Dispense 五处循环明细写归 EX-37 衔接补丁化，a26c1d7）。
- **原子化四处**：EX-23 CardAccount UPDATE...RETURNING 补 deleted=0（DepositAccountMapper 对齐，软删死账户不可复活记账，985d31d）；EX-28 登录失败计数 setSql 原子累加+锁定判定迁 SQL CASE（阈值/窗口逐字等价，recordLoginSuccess 本为原子未动，f1f7d47）；EX-27 告警越限回合标记 Redis 三步改 Lua 原子（breach_marker_transition.lua 外置脚本 PoolRedisGate 先例；标记承载值 ISO→epoch 毫秒为原子判定前提；残余窗口=READ COMMITTED 跨语句交错与模块全部 CAS 先例同级，2561653）；EX-26 风险标识追加改 DB 侧单语句原子拼接（CASE 空串直落+首尾补逗 position 去重谓词与 Java contains 逐字等价，并发同标识 0 行幂等；removeRiskFlag 单写者降级语义保持邻界登记，本提交补 hash 见 git log）。
- **EX-29 portal 冒名两层临时缓解（M18 后由归属校验取代，裁决③）**：PORTAL 渠道单患者活跃在约上限 3（409 新码 OP-1022 仅免登录面）+证件号解析失败频控 5 次/10 分钟冷却 30 分钟（429 新码 OP-1023，Redis INCR+EXPIRE，键 SHA-256 摘要明文禁入，异常降级放行，ab2f89e）。
- **BUG-01 portal 免登录退号介质归属校验（本 PR 补记，提交 7a82b20）**：portal 退号（BUG-01）凭证必填（缺省 400 OP-1019/PARAM_FORMAT_INVALID，工作站两参通道共用 DTO 可空行为保持）+ 归属不符 OP-1021 403（介质解析患者与单据归属同事务比对，阻断匿名遍历单号退他人号源）。
- **EX-29 缓解②退号侧收口（N7 补完）**：portal cancel 链路补证件号频控三件套（冷却期前置 429 OP-1023 不触达解析/PAT-1001 计连续失败/解析成功清零），与 book 同链路同口径，堵经退号端点裸解析绕过。
- **D-21 断言现代化裁量（主控逐次批准两处）**：pharmacy 3 行（快照同值断言→isNull 严格契约，「未触碰」由列不进 SET 承载）+system 2 处（计数字面值参数→SQL 原子契约片段，额外锚定阈值常量与 CASE 形态）——原断言冻结实现细节，新断言严格度不低于原，四边界合规。
- **EX-26 removeRiskFlag 对称原子化（N7 审查 B-1 补完）**：整串置值回写（updateRiskFlags）改 DB 侧 array_to_string/array_remove/string_to_array 单语句摘除 casRemoveRiskFlag，「当前值含该标识」position 谓词与追加侧同形态取反向——行级锁串行化下并发追加/移除双向不互吞（原「追加侧原子拼接保底不丢标记」失实论断修正：整串置值会抹掉并发追加标记）；0 行重读三态定性（行不在区 NS-1001/已被并发移除幂等命中/让位并发重追加不误删新判级），updateRiskFlags 无调用方随删。
- **EX-24/EX-25 就诊卡 loss/unbind 镜像写时钟归一（N7 审查 B-2 补完）**：casMarkLost/casDisable 同语句 DB now() 落 unbound_at 后的 updateById 镜像写改仅携状态列补丁实体（EX-24 指定列补丁纪律），裁剪不携 unbound_at——应用时钟与快照残留旧值（复绑卡再挂失/解绑）不再覆写 DB 时刻，时刻契约与实现归一。
- **验证**：五模块门禁全绿 patient 250（+8）/iot 563（+3）/pharmacy 173（+4）/system 164（+1）/outpatient 339（+16）+spotless 过+主控五模块联跑终验 BUILD SUCCESS；行为变化（并发输家显式 409/软删不命中/新 4xx 契约 OP-1022/1023/EX-22 守卫时点与 EX-27 编码变化）入 N5 行为变化清单。

## 2026-09-30 · EX-19 裸 IAE 模式级收口全量闭环（BE-C3-05，11 模块两波，三态口径确立）

- **根因**：BE-C3-05 全仓 209 处裸 IllegalArgumentException/IllegalStateException 绕过
  双层错误模型（宪法 A.3-3/4）——枚举 fromCode 词表外值直接 500、内部断言与用户可达
  路径混用同一通道。
- **三态收口口径（本项确立）**：A 枚举 fromCode 用户可达→转 BizException 新码最小化
  （400+errorCode）；B 用户可达 DTO 前置校验 400；C 内部断言（编程错误/环境异常/数据
  不一致/MQ 契约）保留+「EX-19 C 类收口留痕」行级注释；断言 IAE→BizException 测试
  迁移按 D-21 四边界放行且提交 body 留痕。
- **第一波六模块**：iot 24 枚举→IOT-1026（1f25efb）/billing 24→BILL-1034（83d3342）/
  pharmacy 15→PH-1022（3f46f42）/outpatient 8 复用 OP-1019 零新增码（725f18e）/
  system 13→SYS-1031（0d47ec4）/ward 6→WD-1007/1008+IOT 手工入口守卫迁移（Jackson
  探针实证 record 构造器异常 errorCode 无法出网）（9b0dda4）；B 类普遍 0（既有守卫
  已覆盖）、fromCode 多无生产调用点=契约级 500→400 预留。
- **第二波五模块**：nursing 9+inpatient 8 全 C 类留痕（两模块 fromCode 本为「null+
  调用方判空」形态无转码面，22a8949）；patient A 类 1 点 PAT-1025+D-21 断言迁移两测
  （cccea1f）；integration 9+common 11 全 C 类留痕（common 共享底座无业务错误码
  体系全保留，36031d4）。
- **验证**：11 模块局部门禁全绿（iot 560/billing 293/pharmacy 169/outpatient 323/
  system 163/ward 85/patient 242/inpatient 195/nursing 211/integration 83）+各模块
  spotless 过；对外行为变化（各新码点 500→400、ward 守卫次序提前）入 N5 行为变化
  清单汇总。

## 2026-09-29 · 批次 C 宪法 C3/C1 轨总收口（EX-14~18+20：分层下沉/异常可观测/审计全量/for 头形态，五路并行+专项）

- **根因**：BE-C3-03/04/06/07/10/12（分层/审计/异常口径——「自述边界/契约留痕不构成
  背书」按总裁决收拢）+BE-C4-02~12（总裁决④用户显式重开 for 头形态收拢）。
- **分层下沉**：EX-14 patient PrivacyController 两链路（SEC-01 门禁折入
  PrivacyMaskServiceImpl.updateRule 既有用例方法防无门禁写路径回归、分页收敛下沉
  PrivacyServiceImpl 参数对象法，8fb82a0）；EX-15 iot 兜底入库鉴权+消息组装下沉新建
  IFallbackIngestService（app IotConfig @Import 装配行 3 行最小跨模块触碰，aca3a69）。
- **异常可观测**：EX-16 评估告警 catch 事实修正（fa87a33 起已有 error 非静默）——
  增强既有唯一日志补设备标识/旁路语义/异常类名（采样常量防刷屏，不降级 error）；
  EX-17 CommandLogVO 空 catch 补 @Slf4j+参数化 warn（契约不变 null 出网）（同提交
  fea8ec8）。
- **审计全量口径**：EX-18 四写端点（book/take/register/feeUpload）补
  @AuditLog(WRITE)（待裁决 #8 转正；portal 豁免维持；依赖可达无 BUG-24 反应环，
  ef1f366）。
- **EX-20 for 头形态收拢（11 处=BE-C4-02~12 全量，含实扫补全 2 处）**：冒号右侧
  查询/IO 内嵌提取循环前变量——纯形态重构（for 头仅求值一次，零行为零性能变化）；
  修宪候选地位不变仍呈报 N8；第 12 处 WardMetaServiceImpl:574（本地私有方法间接
  解析）形态间接主控裁定不扩，N8 复核。
- **验证**：patient 242（+2 门禁用例等强度迁移）/iot 532（+8 fallback 交付）/
  billing 292/outpatient 295/nursing 211 全绿+四模块 spotless 过；fuyun-app
  test-compile 13 模块 SUCCESS。附：EX-03 重命名 fuyun-app 两 IT 跨模块引用遗漏
  由本轮并行验证暴露并即修（9255c37）。

## 2026-09-29 · 批次 B 持久层组总收口（EX-08~13，BE-C2 半配对/手构 wrapper/无上界查询，按模块六提交）

- **根因**：BE-C2 待裁决 #5/#6/#7 转正（宪法 A.4.3-20 半配对十七对全部判 CRUD 收拢、
  iot 三服务窄职责不采信、Wrappers 直构自述不采信）+BE-C2-13/14/16；A.4.3-13 链式纪律
  与 A.4.3-14 分页纪律。
- **收拢口径（本批确立）**：以全仓既有先例（ISettlementService 持状态机仍以主表实体
  收拢）为准——「跨表编排/状态 CAS 为自有方法、IService 面仅对主表 CRUD」不构成聚合根
  排除条件；接口收拢处 javadoc 一律补护栏段「IService 通用写面不承载状态语义，禁经
  通用写面绕行」。十七对全闭环：nursing 8（e1bdd59）/pharmacy 4（含主控裁定收拢的
  Dispense/Prescription 两对）/iot 3+EX-09 三服务补全配对/inpatient 2（床位 CAS 高并发
  域，cas* 权威入口零触碰）。
- **链式化（EX-10/11/12）**：inpatient 三处+integration 三实现四处+pharmacy 八处成交
  （字节码级核验 ChainQuery 终态与 mapper 直调等价、CAS 谓词逐字等价）；跨表/副表查询
  合法保留手构；pharmacy buildSearchWrapper 一处因测试断言锚定 LambdaQueryWrapper
  强转属「断言必改」情形按 D-21 出口留专项（方法 javadoc 留痕，N8 呈报）。
- **EX-13 防御收拢**：计价规则/告警规则两配置清单加 LIMIT 200 硬顶+截断 warn（
  QualityServiceImpl:82 先例同款）；正常配置量行为不变，超限截断为新防御语义；配套
  截断用例两例护航 JaCoCo 核心包 LINE=1.00（mock 恰 200 行驱动 warn 分支，先例同构）。
- **验证**：nursing 211/iot 524（+1 截断用例）/pharmacy 169（+2 配对纪律用例）/
  inpatient 195（+2 配对纪律用例）/integration 83/billing 292（+1）全绿；各模块
  spotless 通过；测试改动全部为 mock 面机械调整（entityClass 直设/类型 matcher 锁定）
  与新增用例，既有断言零修改。

## 2026-09-29 · 机械组 EX-04 全量收口：全仓全限定类名声明改 import+简名（BE-C3-11，纯机械零行为变化，按模块四提交）

- **根因（BE-C3-11 评分 75，2026-09-28 全仓高风险问题清单低置信节）**：IDispenseService:
  :21 等 14 处全限定类名声明——backend 宪法 A.1 节第 13 条「禁止全限定类名声明」点名、
  Spotless 不覆盖，消歧理由不成立。宪法收拢总裁决下按条款全量实扫收口（登记 14 处→
  实扫 **44 处/28 文件**：首批 17+追加深包与 com.fuyun 20+实扫补 3+package-info 收尾
  4——含预扫正则盲区 java.util.function/stream 深包与 com.baomidou Wrappers）。
- **修复（src/main 范围，test 不扩；同名冲突消歧例外零适用）**：①iot 10 文件（ZoneOffset
  /RoundingMode/Map.of/Consumer/Collectors.toSet/DeviceStatus/IotAlarmEntity 等枚举实体
  /Wrappers.lambdaQuery/api package-info NamedInterface）；②patient 7 文件（LinkedHashMap
  L1 缓存声明/Map.Entry 覆写签名/Arrays.stream/Collectors.joining/PatientConverter/
  CardAccountLedger/AllergyChecker/PatientMatchCheckVO extends 尾项）；③pharmacy 4 文件
  （List 签名 5 处含 PickLine/OccupancyVO 双限定/DispenseReturnRequest/DispenseVO/
  DrugBatch/SettlementQueryPort/api package-info）；④跨模块收尾 5 文件（billing/nursing/
  outpatient 三 api package-info NamedInterface 同款统一——全仓 8 处 package-info 形态
  归一；common RoleContextHolder/system AuthTokenInterceptor 注释旧类名 PrivacyMaskService
  →IPrivacyMaskService 同步，EX-03 配套）。Spotless 连带的链式调用换行重排为必要配套。
- **验证**：iot 523/patient 240/pharmacy 167 用例全绿（=基线）+三模块 spotless 通过；
  billing/nursing/outpatient 编译+spotless 通过；common/system spotless 通过；三模块
  src/main FQN 复扫零残留。

## 2026-09-29 · 机械组 EX-06：workstation 零消费 vitalBoard API 移除（FE-Q1-14，死代码零容忍）

- **根因（FE-Q1-14，2026-09-28 全仓性能与代码质量优化清单）**：workstation api/ward.ts
  :188-195 vitalBoard 资源组 API 零消费——全局 §四死代码零容忍。
- **修复（纯删除 10 行）**：引用核验零消费（web/ 全工作区 grep `vitalBoard|VitalBoardVO`
  仅命中 ward.ts 自身与生成物 api.d.ts 契约声明——生成物禁改、契约存在不构成消费）；
  删除 vitalBoard 资源组函数+VitalBoardVO 类型别名（仅 ward.ts 内部消费）+头部注释
  「+ 体征看板（病区快照）」条目同步。nursing 域 vitalSigns 为另一资源不涉。
- **验证**：web 根 `pnpm lint`（--max-warnings=0）通过+`pnpm --filter @fuyun/workstation
  type-check` 通过+`test` 42 文件 217 用例全过（=基线）+改动文件 prettier --check 通过；
  diff 纯删除无引用改写。

## 2026-09-29 · 机械组 EX-05：fuyun-app 模块边界测试裸 System.out 改 SLF4J（BE-C3-13，输出通道等效替换）

- **根因（BE-C3-13 评分 75，2026-09-28 全仓高风险问题清单低置信节）**：
  ModulithBoundaryTest:26 测试内裸 System.out——backend 宪法 A.1 节第 11 条「禁止裸
  System.out，统一 SLF4J」无测试豁免条款。
- **修复**：实扫全文件仅 1 处（:26）；补测试类 logger（标准 SLF4J 声明，项目测试类无
  自身 logger 先例）；`System.out.println(modules)` → `log.info("{}", modules)` 等效
  输出（模块布局内容不丢失）；关联注释「控制台输出」→「日志输出」同步措辞。断言零
  改动（补偿防线与 modules.verify() 原样保留），测试行为零变化。
- **验证**：`mvn -B -ntp -pl fuyun-app -am -Dtest=ModulithBoundaryTest test` 全绿
  （1 用例；补 -Dsurefire.failIfNoSpecifiedTests=false 规避上游模块无匹配测试的参数
  语义差异）+ `mvn -B -ntp -pl fuyun-app spotless:check` 通过（59 文件 clean）；diff
  +7/-2（2 import+2 行 logger+1 处替换，-2 为原 System.out 语句与注释行）。

## 2026-09-29 · 机械组 EX-02（C 路 b）：fuyun-iot 遥测推送生命周期测试隐式断言显式化（BE-A4-15，只补不改）

- **根因（BE-A4-15 评分 50，2026-09-28 全仓高风险问题清单低置信节）**：
  TelemetryPushServiceImplLifecycleTest:107-119 隐式断言（不抛异常即通过）与
  @DisplayName「兜底排空失败吞错：发送异常捕获留痕不上抛（不打断调度周期）」声称不符。
- **修复（11 行纯新增，既有断言零改动——D-21 红线）**：flushDueWindowsSwallowsSendFailures
  补两组显式断言锚定 DisplayName 声称：①verify convertAndSend 命中 doThrow 桩且执行流
  越过吞错点=「捕获不上抛」；②二次 flushDueWindowsQuietly 后 times(1) 恒定=「不打断
  调度周期、失败帧不补推」。「留痕」维度（log.error）未断言——测试类无日志捕获设施，
  引入属过度设计，锚定两个可观测语义。
- **验证**：`mvn -B -ntp -pl fuyun-iot -am test` 全绿（523 用例=基线，用例数不变）+
  `spotless:check` 通过；git diff 11 insertions/0 deletions/0 modifications。

## 2026-09-29 · 机械组 EX-02（C 路 a）：fuyun-system 审计写库补 info 日志（BE-A4-12，日志新增零逻辑变化）

- **根因（BE-A4-12 评分 55，2026-09-28 全仓高风险问题清单低置信节）**：AuditLogServiceImpl
  :37-51 审计写库无 info——原「审计表即日志载体」豁免申报按宪法收拢总裁决不采信（全局
  §二「数据库写操作必须 info」显式条款可锚定）。
- **修复**：append 落库成功后补一行中文 info（actionType/operatorId/resource/bizNo/
  result/traceId 六摘要字段）；**敏感红线**：detail 原文与 failReason 不入日志（detail
  可能残留身份证/手机号脱敏残留，行级注释标明）；类补 Lombok @Slf4j。**必要注释修正
  （1 行）**：类 javadoc 原「本类不落日志」条款与新日志直接矛盾，保留即成失实注释（违
  全局 §一），改写为准确口径（成功落库本类 info 摘要；失败告警由审计切面 error 统一
  承担）。
- **验证**：`mvn -B -ntp -pl fuyun-system -am test` 全绿（161 用例=基线）+
  `spotless:check` 通过；代码既有行零改动，新增 @Slf4j/import/日志块+1 行 javadoc 修正。

## 2026-09-29 · 机械组 EX-03（B 路 a）：fuyun-patient 五服务接口 I 前缀重命名（BE-C2-10，纯命名层零行为变化）

- **根因（BE-C2-10 评分 75，2026-09-28 全仓高风险问题清单低置信节）**：patient 五个
  服务接口无 I 前缀（backend 宪法 A.4.3-20 点名，纯命名层机械重命名无外部契约）。
- **修复**：PatientMatchingService/PatientRegistrationService/PrivacyMaskService/
  PrivacyService/VisitCardService → 各加 I 前缀（文件重命名+声明/import/字段/构造参数/
  javadoc/@link 词边界机械替换，XxxServiceImpl 零误伤）；引用面 main 11 文件（3 controller+
  7 impl+1 vo）+test 9 文件，旧名全模块 grep 零残留；spotless 连带的 import 字母序重排
  为重命名必要配套。test 九文件断言零改动。**顺带（EX-04 双 ID 标注）**：重命名面文件
  PossibleDuplicateServiceImpl 内 2 处全限定类名（:198 java.util.stream.Collectors、
  :220 java.util.Arrays）一并改 import+简名，避免同文件二次触碰。
- **跨模块注释残留说明**：fuyun-common RoleContextHolder:10 与 fuyun-system
  AuthTokenInterceptor:77 注释中提及旧名「PrivacyMaskService」字样——纯注释非代码引用，
  由 EX-04 追加收口任务同步（见后续条目）。
- **验证**：`mvn -B -ntp -pl fuyun-patient -am test` 全绿（240 用例=基线，reactor 全
  SUCCESS）+ `mvn -B -ntp -pl fuyun-patient spotless:check` 通过；diff 核验全部改动行
  含接口名替换/import 重排，无逻辑行变化。

## 2026-09-29 · 机械组 EX-02（B 路 b）：fuyun-patient 预留依赖 TODO 补版本计划格式（BE-A4-14，零功能影响）

- **根因（BE-A4-14 评分 65，2026-09-28 全仓高风险问题清单低置信节）**：
  PatientIdentifierServiceImpl:38 预留依赖 TODO 无「计划于 X 版本引入」版本计划——全局
  规范 §四唯一例外格式要求 `TODO(<feature>): <扩展说明，计划于 X 版本引入>`。
- **修复（仅注释追加）**：`// TODO(card-ops): 患者主索引服务依赖，供 Task 10 卡操作
  （挂失/补卡/解绑）解析收敛视图复用` 追加「，计划于 P2 引入」。P2 选择依据：①原暗示
  阶段 P1 已于 2026-09-25 终验收尾且卡操作实际实现未消费此预留依赖（依赖悬空不可再写
  P1）；②P2 住院线 M04 入院登记/M05 PDA 是卡介质解析收敛的最近合理消费窗口。
- **验证**：`mvn -B -ntp -pl fuyun-patient -am test` 全绿（240 用例=基线，reactor 全
  SUCCESS）+ `spotless:check` 通过；diff 仅 1 行注释追加。

## 2026-09-29 · 机械组 EX-03/EX-02（A 路）：fuyun-inpatient 十服务接口 I 前缀重命名+占用流水开账写库补日志（BE-C2-10/BE-A4-13，纯命名层+日志新增）

- **根因（BE-C2-10 评分 75 / BE-A4-13 评分 70，2026-09-28 全仓高风险问题清单低置信节）**：
  inpatient 十个服务接口无 I 前缀（backend 宪法 A.4.3-20 点名，纯命名层机械重命名无外部
  契约）；BedServiceImpl:378 锚点方法体含数据库写操作而方法内零日志（占用流水开账
  insert 无审计锚点）。
- **修复**：①十接口重命名——AdmissionService/BedService/ConsultationService/
  DischargeService/MedicalOrderService/OrderAuditService/OrderPlanService/
  OrderStateMachineService/OrderTransferService/TransferService → 各加 I 前缀（文件重命名+
  声明/引用/javadoc/@link 全量机械替换，词边界无误伤）；引用面 main 45 文件+test 10 文件，
  旧名全模块 grep 零残留（迁移 SQL 注释 V902:5/V904:4/39/V905:6 四处提及旧类名按
  A.4.1-3 迁移禁改红线冻结——Flyway checksum 保护）；test 十文件纯重命名引用替换、断言
  零改动。②EX-02 日志——BedServiceImpl 实扫全文件 9 个写语句，唯一「方法体含写且方法内
  零日志」为 openAssign 的 assignMapper.insert（占用流水开账，原 :390；报告锚点 :378 即
  该方法声明）；补一行 info（床位号/就诊 ID/占用类型/FREE→OCCUPIED 状态迁移/操作人）。
  **口径差异说明**：报告「等 3 处」实扫仅 1 处成立——其余 8 个写语句所在方法均已有业务
  完成日志，疑将 assign/occupyForAdmission 等无直接写语句的委托入口误计，给其补日志将与
  同路径主方法日志逐笔重复，按精准修改不扩。
- **验证**：`mvn -B -ntp -pl fuyun-inpatient -am test` 全绿（193 用例=基线，reactor 全
  SUCCESS）+ `mvn -B -ntp -pl fuyun-inpatient spotless:check` 通过（159 文件 clean）；
  44/45 改动文件与「HEAD+机械替换」零差异，唯 BedServiceImpl 额外差异恰为 1 条日志。

## 2026-09-29 · 注释补齐环 EX-01（billing 组，第 6/6 b 路）：fuyun-billing 计价方法级与退费资金口径行注释补齐（BE-A4-06 计价侧+BE-A4-08，零行为变化）

- **根因（BE-A4-06/08 评分 75，2026-09-28 全仓高风险问题清单低置信节）**：计价语义
  公开方法无方法级 Javadoc（BE-A4-06 组量约 10 跨门诊/计价，本组收口计价侧）；退费
  RefundServiceImpl:511 等 4 处写语句无行级注释（退费资金口径行无锚点）——报告行号已因
  OPT-05 退费三级级联/OPT-12 累计已退投影/OPT-13 押金投影三次改造失准，按「退费资金
  口径写语句」语义实扫定位。
- **补齐（纯注释，零代码行变化）**：方法级 1 方法+行级 4 处 / 2 文件——
  PrescriptionFeePortImpl.cancelPendingBySourceRef（按来源单据号作废处方触发在途
  PENDING 费用行组：三段定位谓词+逐行复用引擎 cancel 的 PENDING→CANCELLED+REQUIRED
  传播加入 M06 调用方事务，全模块实扫唯一方法级缺口——计价链路 PricingEngine/
  ChargePrice/ChargeItem/PricingRule 服务+接口+Controller+监听器已经 OPT-04 等改造补齐）
  + RefundServiceImpl 行级 4（apply 逐行算额累加禁前端传额红线 1、lineAmounts 供负向
  台账逐行落 refund_amount；approve 终批状态落库 PENDING_APPROVAL/
  PENDING_SECOND_APPROVAL→APPROVED 资金放行语义、CAS 已原子落终批人全行回写保事件载荷
  一致；execute 卡台账跨模块 M02 资金写每卡单次全额贷记与写入侧同卡多行求和出账口径
  对称、回填流水 id 作资金溯源锚；execute 退费单终态 APPROVED→EXECUTED 事务首步 CAS
  抢锚全行回写补 payment_refund_ref 流水引用）。**多退**：save+saveBatch 块注释、
  casEscalateFirstApproval、casFinal/casReject/casMarkExecuted 三支 CAS、费用行判态块、
  结算单 REFUNDED 落库行等已有行级锚点未动；Mapper 接口经人工核验均有完整 Javadoc。
- **验证**：`mvn -B -ntp -pl fuyun-billing -am test` 全绿（fuyun-billing 291 用例，
  reactor 六模块全 SUCCESS）+ `mvn -B -ntp -pl fuyun-billing spotless:check` 通过
  （206 文件 clean）；`git diff` 复核 27 行全为注释新增、零删除、零代码行/签名/import
  变化。**EX-01 六组全收口（iot/ward/pharmacy/system/integration/outpatient+billing）**。

## 2026-09-29 · 注释补齐环 EX-01（outpatient 组，第 6/6 a 路）：fuyun-outpatient 门诊排班方法级与预约/分诊关键行注释补齐（BE-A4-06 门诊侧+BE-A4-09/10，零行为变化）

- **根因（BE-A4-06/09/10 评分 75，2026-09-28 全仓高风险问题清单低置信节）**：门诊
  排班公开方法无方法级 Javadoc（ScheduleServiceImpl:103 锚点，组量约 10 跨门诊/计价，
  本组收口门诊侧）；预约挂号 AppointmentServiceImpl:417 等 4 处缓存读写与 4 处数据库写
  缺行级注释（行号基于 OPT 批查化改造前快照，按语义实扫定位）；实扫另得分诊链路
  TriageServiceImpl 6 处库写+ScheduleServiceImpl 模板 insert 1 处缺行级，属 BE-A4-10
  「数据库写行级缺失」同类（N1 统计 45 处写操作行缺注范围），经主控裁量按宪法收拢
  总裁决一并补齐。
- **补齐（纯注释，零代码行变化）**：方法级 8 方法+行级 16 处 / 3 文件——
  ScheduleServiceImpl 8（saveTemplate 登记 ACTIVE/号段倒挂 OP-1019、generate T+N 放号
  week_pattern 展开两段幂等、stop/resume CAS 状态迁移+整池联动+AFTER_COMMIT 事件面、
  availablePools 余量谓词、extraQuota 加号 CAS+池键 INCRBY 快路径）+ AppointmentServiceImpl
  行级 9（BE-A4-09 缓存 5：当日流水键 INCR 原子计数/首签 48h TTL 禁无过期键/预约失败
  Lua 原子回补越界封顶/回池余量同步/支付占位键删除；BE-A4-10 库写 4：取号 visit 落库
  初始 REGISTERED/当日挂号 RESERVED→TAKEN CAS+visit 同事务落库/池行条件回池 version
  防双回补）+ TriageServiceImpl 行级 6（报到回写仅同值列/建票初始 WAITING/分级快照仅
  triage_level 列不落状态机列 BUG-07 纪律/RE_TRIAGE 票面指派/调级分值不改号 Spec/跨队列
  转接新队建票唯一键区分）+ ScheduleServiceImpl 模板 insert 行级 1。**多退**：7 个 MQ
  监听器 onXxx 人工核实在 @RabbitListener 上方已有 Javadoc（扫描跨行注解误报）；
  take/releaseCredit 邻近写语句已有语义覆盖行级注释未动；fuyun-billing 计价侧归同组
  b 路另行提交。
- **验证**：`mvn -B -ntp -pl fuyun-outpatient -am test` 全绿（fuyun-outpatient 295
  用例，reactor 八模块全 SUCCESS）+ `mvn -B -ntp -pl fuyun-outpatient spotless:check`
  通过（154 文件 clean）；`git diff` 复核 84 行全为注释新增、零删除、零代码行/签名/
  import 变化。

## 2026-09-29 · 注释补齐环 EX-01（integration 组，第 5/6）：fuyun-integration 消费幂等与事件基础设施公开方法 Javadoc 补齐（BE-A4-02 本体补位，零行为变化）

- **根因（BE-A4-02 评分 75，2026-09-28 全仓高风险问题清单低置信节）**：全仓消费幂等
  基础设施 true/false 语义缺方法级说明——本体 MessageIdempotencyServiceImpl 位于
  fuyun-integration（EX-01 原登记模块列表漏列，本组补位；iot 侧灶位已随第 1/6 组收口），
  锚点 :71 等，组量约 19。
- **补齐（纯注释，零代码行变化）**：19 方法 / 7 文件（均 service/impl）——
  MessageIdempotencyServiceImpl 3（tryAcquire 消费幂等前置判定：**true/false 语义
  单独成段写实底**——true=放行执行业务（NX 抢占首次/前置键残留台账无 PROCESSED 的
  上次中断/仅 FAILED 行的有界重投/Redis 故障降级四来源）、false=确认重复投递（台账已有
  PROCESSED 行）调用方 return 即 AUTO 确认；幂等键构成含 consumerModule 要素隔离同事件
  多模块消费、同帧并发业务可能重复执行由 recordProcessed 唯一索引兜底的 at-least-once
  边界；recordProcessed PROCESSED 事实登记与 DuplicateKeyException 分流；settleFailure
  释放前置键+FAILED 留痕、异常 addSuppressed 挂回不改变控制流）+ QueueGovernorImpl 2
  （消费者/延迟队列声明，先登记后订阅阻断启动）+ EventRegistryServiceImpl 4（幂等登记
  不覆盖冻结契约、清单 CAS 自旋 3 次上界、isRegistered true 含 DEPRECATED 已废止须另判
  状态）+ DeadLetterServiceImpl 4（replay PENDING→REPLAYED 状态机 CAS 抢先/超限/不可
  路由/失败回退 INT-1002~1005、close 终态敏感备注只记长度）+ 查询面 3（死信列表/消费
  台账/发布台账只读分页，零写语义）+ MdmSubscriptionServiceImpl 4（(topic,subscriber)
  幂等、逻辑删保对账、空清单≠null）。**多退**：5 控制器/5 服务接口/2 监听器入口/
  MessagingGovernance/config/Converter/TypeHandler 实扫均已合规；MessageIdempotencyService
  接口本体在 fuyun-common 且 Javadoc 已完整覆盖 true/false 语义（D-7 段落），越界未动；
  iot 模块零触碰。
- **验证**：`mvn -B -ntp -pl fuyun-integration -am test` 全绿（fuyun-integration 83
  用例，reactor 三模块全 SUCCESS）+ `mvn -B -ntp -pl fuyun-integration spotless:check`
  通过（72 文件 clean）；`git diff` 复核 241 行全为 Javadoc 新增、零删除、零代码行/
  签名/import 变化。

## 2026-09-29 · 注释补齐环 EX-01（system 组，第 4/6）：fuyun-system 认证域与字典域公开方法 Javadoc 补齐（BE-A4-05，零行为变化）

- **根因（BE-A4-05 评分 75，2026-09-28 全仓高风险问题清单低置信节）**：认证域
  （AuthServiceImpl:92 锚点）方法体编号行注释较完整，公开方法缺方法级 Javadoc；实扫
  全模块缺方法级 Javadoc 公开方法恰 13 个（=报告组量）：认证域 7+字典域 6。
- **补齐（纯注释，零代码行变化）**：13 方法 / 7 文件——AuthServiceImpl 3（login：
  账号加载→锁定/停用校验→bcrypt 比对→状态机复位→会话组装→双令牌签发，SYS-1001/1002/
  1006 与防枚举口径；refresh：同 sid 换发 refresh 值不轮换 SYS-1005；logout：按 sid
  删会话键双令牌同时失效 SYS-1003/1004）+ UserServiceImpl 3（findByLoginName 认证列
  精确投影、逻辑删/不存在同归 null；recordLoginFailure 计数累加达阈置锁定含并发丢计数
  边界；recordLoginSuccess 计数/锁定/最近登录三复位）+ RoleServiceImpl 1
  （findRoleCodesByUserId 两步单表查询仅 ACTIVE）+ 字典域 6（DictItemServiceImpl.
  addItem 仅 DRAFT 版本可维护、uk 兜底并发；DictQueryServiceImpl.readPublished 无
  服务端缓存+no-cache 协商每次回源写实；DictTypeServiceImpl.createType 前置校验已删行
  不占用+uk 并发兜底、getByTypeCode 两列投影禁当完整实体用；DictVersionServiceImpl.
  createVersion 版本号同类型自增 uk 兜底、publish DRAFT→PUBLISHED 条件更新防并发双
  发布+AFTER_COMMIT 广播下游缓存联动语义）。**多退**：控制器/服务接口/
  TokenServiceImpl/AuthTokenInterceptor/Practice 系列等实扫均已合规未动。
- **验证**：`mvn -B -ntp -pl fuyun-system -am test` 全绿（fuyun-system 161 用例，
  reactor 四模块全 SUCCESS）+ `mvn -B -ntp -pl fuyun-system spotless:check` 通过；
  `git diff` 复核 157 行全为 Javadoc 新增、零删除、零代码行/签名/import 变化。

## 2026-09-29 · 注释补齐环 EX-01（pharmacy 组，第 3/6）：fuyun-pharmacy 药事核心链路公开方法 Javadoc 补齐（BE-A4-04，零行为变化）

- **根因（BE-A4-04 评分 75，2026-09-28 全仓高风险问题清单低置信节）**：药事核心链路
  （DispenseServiceImpl:207 等，组量 17——报告行号基于 OPT-07/08/09 批查化改造前快照，
  本次按语义实扫定位）公开方法行级注释完整而方法级 Javadoc 缺——参数可空性/来源与
  PH-xxxx 异常码口径无 impl 侧契约锚点。
- **补齐（纯注释，零代码行变化）**：实扫 fuyun-pharmacy src/main/java 公开方法，按
  BE-A4-04 语义范围补齐 20 方法 / 4 文件——DispenseServiceImpl 10（缴费放行/费用回执/
  调剂三段 pick·verify·issue/退药受理两时点/退费终态收敛/未发药作废/占用查询/工作台
  回显：状态机前后态、PH-1008~1021 异常口径与建议处理、参数可空性与来源）+
  PrescriptionServiceImpl 3（create/cancel/list：执业授权纵深两段 PH-1017、TOCTOU 费用
  联动裁决 7、四条件分页口径）+ DrugServiceImpl 5（建档/变更/详情/医保对照/检索：uk
  双防线、changed 广播 changeType 语义、默认启用面）+ BatchSelectServiceImpl 1（FEFO
  选批单批足量约束、无批次=null 由调用方 PH-1010 定性）。**多退**：接口层（IDispenseService
  等 5 接口）、controller 层 4、MedicationReviewServiceImpl（{@inheritDoc}+自有 Javadoc）、
  PrescriptionCancelPortImpl/PrescriptionOpenPortImpl 实扫方法级 Javadoc 均已合规（含
  参数/返回值/PH 码粒度）；internal 8 监听器入口方法（onXxx(Message)）有简短方法级
  Javadoc 且业务语义在包级 handleXxx 完整承载，超出 BE-A4-04 点名语义不动，条目外
  文件零改动。
- **验证**：`mvn -B -ntp -pl fuyun-pharmacy -am test` 全绿（fuyun-pharmacy 167 用例，
  reactor 全 SUCCESS）+ `mvn -B -ntp -pl fuyun-pharmacy spotless:check` 通过；
  `git diff` 复核 280 行全为 Javadoc 新增、零删除、零代码行/签名/import 变化。

## 2026-09-29 · 注释补齐环 EX-01（ward 组，第 2/6）：fuyun-ward 病区呼叫/冷链/输液板状态机迁移公开方法 Javadoc 补齐（BE-A4-03，零行为变化）

- **根因（BE-A4-03 评分 75，2026-09-28 全仓高风险问题清单低置信节）**：病房呼叫/冷链/
  输注板状态机迁移方法（WardCallServiceImpl:95 等，组量 18）缺粒度契约——公开方法无
  方法级 Javadoc，状态迁移前后态与异常码（WD-xxxx/HTTP 口径）无契约锚点。
- **补齐（纯注释，零代码行变化）**：实扫 fuyun-ward src/main/java 公开方法，按 BE-A4-03
  语义范围补齐 18 方法 / 4 文件——WardCallServiceImpl 8（create/answer/progress/complete/
  transfer/route/cancel/get：六态迁移表前后态、CAS 零行 WD-1002 并发口径、route 事务回滚
  语义）+ ColdChainServiceImpl 7（档案 CRUD/记录登记：WD-1004/WD-1005 触发条件、
  ALARM_HANDLE 同事务事件发布、overdue 惰性判定基线）+ InfusionBoardServiceImpl 2（看板
  聚合/历史追溯：三档映射展示口径、曲线降级边界）+ VitalSignBoardServiceImpl 1（SCAN
  禁 KEYS 红线、deviceId 维度过滤缺位申报）。**多退**：全模块其余公开面（4 控制器/4 服务
  接口/2 mapper/WardSeqGate/4 消费监听器/WardMessagingConfig 等）实扫方法级 Javadoc 均已
  合规（含参数/返回值/异常粒度），无需补齐，条目外文件零改动。
- **验证**：`mvn -B -ntp -pl fuyun-ward -am test` 全绿（fuyun-ward 71 用例，reactor 全
  SUCCESS）+ `mvn -B -ntp -pl fuyun-ward spotless:check` 通过；`git diff -U0` 复核 195 行
  全为 Javadoc 新增、零删除、零代码行/签名/import 变化。

## 2026-09-29 · 注释补齐环 EX-01（iot 组，第 1/6）：fuyun-iot 华为对接边界与 trivial 覆写公开方法 Javadoc 补齐（BE-A4-01/02/07，零行为变化）

- **根因（BE-A4-01 评分 75 / BE-A4-07 评分 60，2026-09-28 全仓高风险问题清单低置信节）**：
  fuyun-iot 华为对接边界（IotDeviceRegistry 双实现）与命名锚点（AlarmRuleServiceImpl:83）公开
  方法行级注释在而方法级 Javadoc 缺；SmartLifecycle trivial 覆写（IotAmqpTelemetryConsumer:372
  isAutoStartup 等）无覆写意图说明。BE-A4-02（消费幂等基础设施）iot 侧实扫为空——幂等消费
  监听器（IotAlarmEventListener/IotFanoutListener）方法级 Javadoc 已合规，其基础设施本体
  （MessageIdempotencyServiceImpl）在 fuyun-integration，归后续 EX 组。
- **补齐（纯注释，零代码行变化）**：实扫 fuyun-iot src/main/java 公开方法，按三组语义范围
  补齐 26 方法 / 6 文件——华为对接边界 HuaweiIotdaRegistry 8 + SimulatedRegistry 8（SDK
  映射、secret 一次性透出红线、幂等语义）+ 命名锚点 AlarmRuleServiceImpl 5（抖动防护②、
  回放状态机、LIMIT 硬顶）+ AMQP 消费循环 QueueWorker.run 1 + trivial 覆写 4
  （isAutoStartup×3 / isRunning×1，一两句覆写意图）。**多退**：全模块实扫 88 处缺方法级
  Javadoc，超出三组语义范围者（其余 service impl / config / handler 等约 62 处）与两处
  匿名类 afterCommit 覆写（外围方法已文档化意图）本次不动，留待后续组。
- **验证**：`mvn -B -ntp -pl fuyun-iot -am test` 全绿（fuyun-iot 523 用例，reactor 全
  SUCCESS）+ `mvn -B -ntp -pl fuyun-iot spotless:check` 通过；`git diff -U0` 复核 185 行
  全为注释新增、零删除、零代码行与 import 变化。

## 2026-09-29 · 性能清单修复环 OPT-14：inpatient 日计划批任务在院就诊候选查询补 .select 精确投影（性能，行为保持）

- **根因（OPT-14 / BE-C4-26 ↔ BE-C2-18 归并组，2026-09-28 全仓性能与代码质量优化清单，
  评分 80，A.4.3-14 投影子款点名）**：`OrderPlanServiceImpl.decomposeCandidates`（长期医嘱
  日切批任务候选查询）全列取回全部 ADMITTED 在院就诊行（三级医院夜间千级宽行，含入院
  诊断/医保/床位/护理级别等 25 列）仅 `stream().map(InpatientVisit::getId)` 组装候选医嘱
  IN 集；宽行全量入内存徒增占用，逐夜必发。
- **修复（行为保持，方案：补 .select 投影）**：`.select(InpatientVisit::getId)`（恰 1 列）；
  谓词（status=ADMITTED）、候选口径（TRANSFERRED/EXECUTING × LONG × end_at 过滤）、生成
  与写侧零变化；javadoc 与行级注释补投影口径句（行集不变仅列收敛，IN 集与全列取回完全
  等价）。**方案裁量（.select 而非聚合/子查询下推）**：消费面为 id 键集（非聚合值），IN
  集组装须在应用侧进行，`.select` 已完整承载——无可下推的聚合算式，键集语义等价，故以
  最小改动收敛列面，不新增 XML SQL 面。
- **测试（先红后绿）**：新增 3 个行为锚定——「在院就诊候选查询投影契约（恰 1 列 id+谓词
  status=ADMITTED 零变化锚定）」改前红（getSqlSelect 为 null，NPE 断言失败）改后绿；
  「批任务输出等价（两在院就诊 id 全量喂入候选 IN 集+候选谓词 visit_id/status 双值/
  order_class=LONG 零变化锚定，各医嘱计划归属各自就诊 orderId@visitId 配对——丢任一就诊
  行即失败）」与「零在院就诊边界（空集直过零事务零事件，不发起下游医嘱候选查询——投影
  不改空集语义）」两用例改前改后均绿（行为锚定）。既有用例零改动（桩面 selectList(any())
  对投影不敏感，业务断言零变化，无 D-21 桩更新）。
- **验证**：`mvn -B -ntp -pl fuyun-inpatient -am test` 全绿（193 用例，OrderPlanServiceImplTest
  24）+ `spotless:check` 通过。

## 2026-09-29 · 性能清单修复环 OPT-13：billing 押金欠费判定已确认费用聚合补 .select 精确投影（性能，行为保持）

- **根因（OPT-13 / BE-C4-24，2026-09-28 全仓性能与代码质量优化清单，评分 80，
  A.4.3-14 投影 + A.4.3-15 聚合子款点名）**：`DepositServiceImpl.deposit`（押金缴存路径）
  的已确认未结算费用聚合查询全列取回该就诊全部 CONFIRMED 费用行（长疗程数百行宽行，
  含费用项/数量/单价/来源单等列）仅 `mapToLong(FeeRecord::getAmount).sum()` 求和；
  宽行全量入内存徒增占用，缴存路径每次执行命中。
- **修复（行为保持，方案：补 .select 投影）**：`.select(FeeRecord::getAmount)`（恰 1 列）；
  谓词（visit_id+status=CONFIRMED）、求和算式、欠费判定逻辑零变化；javadoc 与行级注释补
  投影口径句（行集不变仅列收敛，空集空流求和天然 0，判定结果等价）。
  **方案裁量（.select 而非 SUM 下推）**：求和语义用 .select 承载已完全等价——行集由谓词
  决定、投影仅收敛列面，空集 `.stream().sum()` 天然 0；SUM 下推须为空集 NULL 加 coalesce
  兜 0 且新增 XML 聚合 SQL 面，缴存路径单次调用非循环热路径无聚合下推收益，宽行内存浪费
  是唯一问题，.select 以最小改动消除，故不新增 XML 聚合。
- **测试（先红后绿）**：新增 3 个行为锚定——「聚合投影契约（恰 1 列 amount+谓词 visit_id/
  status=CONFIRMED 零变化锚定）」改前红（getSqlSelect 为 null，NPE 断言失败）改后绿；
  「多行求和欠费等价（两行 12000+11000=23000，回读 30000−23000<阈值转 ARREARS，漏加
  任一行即 19000/18000 ≥ 阈值不切换、断言即失败）」与「零行边界判定等价（CONFIRMED 空 →
  聚合 0，回读恰等于阈值不算欠费，ARREARS 回升 NORMAL）」两用例改前改后均绿（行为锚定）。
  既有用例零改动（桩面 selectList(any()) 对投影不敏感，业务断言零变化，无 D-21 桩更新）。
- **验证**：`mvn -B -ntp -pl fuyun-billing -am test` 全绿（291 用例，DepositServiceImplTest
  13）+ `spotless:check` 通过。

## 2026-09-29 · 性能清单修复环 OPT-12：billing 结算维度累计已退两步查询补 .select 精确投影（性能，行为保持）

- **根因（OPT-12 / BE-C4-23 ↔ BE-B1-10 归并组，2026-09-28 全仓性能与代码质量优化清单，
  评分 80，A.4.3-14 投影子款点名）**：`RefundServiceImpl.totalRefundedFen`（原路退回
  execute 的结算终态判定）两步查询均全列取回仅用 1 列——第一步取回本结算单
  APPROVED/EXECUTED 态退费单全行（金额/渠道等宽行字段）仅 map id，第二步取回集内全部
  refund_fee_link 宽行仅取 refund_amount 求和；退费单/关联表宽行全量入内存徒增占用。
- **修复（行为保持，方案：两步补 .select 投影）**：第一步 `.select(RefundRequest::getId)`
  （恰 1 列）、第二步 `.select(RefundFeeLink::getRefundAmount)`（恰 1 列）；谓词、顺序、
  空 id 集短路（isEmpty → 0 分）、求和算式零变化；javadoc 补投影口径句（本方法仅消费
  该两列，宽行全列取回徒增内存占用）。「维持 id 集两步查询」为 javadoc 有意决策不动
  （不合并 JOIN）。**方案裁量（.select 而非 SUM 下推）**：两步语义用 .select 承载已完全
  等价——空 id 集短路是 Java 侧控制流，SUM 下推须折叠进 SQL（空集 SUM 为 NULL 须
  coalesce 兜 0）且为非循环热路径新增 XML 聚合 SQL 面；本方法单次调用非循环热路径
  （javadoc 自述），宽行内存浪费是唯一问题，.select 以最小改动消除，故不新增 XML 聚合。
- **测试（先红后绿）**：新增 3 个行为锚定——「两步查询投影契约（第一步恰 1 列 id/
  第二步恰 1 列 refund_amount+谓词零变化锚定）」改前红（getSqlSelect 为 null，NPE
  断言失败）改后绿；「空集短路（第二步 link 查询零发出、聚合贡献 0 分留 SETTLED）」与
  「多行求和等价（集内两 link 5000+3000=8000 ≥ 总额转 REFUNDED，漏加单行即失败）」
  两用例改前改后均绿（行为锚定）。既有用例零改动（桩面 selectList(any()) 对投影不
  敏感，业务断言零变化，无 D-21 桩更新）。
- **验证**：`mvn -B -ntp -pl fuyun-billing -am test` 全绿（288 用例，RefundServiceImplTest
  55）+ `spotless:check` 通过。

## 2026-09-29 · 性能清单修复环 OPT-11：patient 脱敏豁免判定批量面收敛查询 + 精确投影（性能，行为保持）

- **根因（OPT-11 / BE-C4-21 + BE-C4-22 ↔ BE-B1-09 + BE-C2-15 归并组，四报并一，
  2026-09-28 全仓性能与代码质量优化清单，评分 80，A.4.3-14 + A.4.3-17 点名）**：
  `PrivacyServiceImpl.unmask`（明文查阅 sensitive 操作路径）循环逐字段调
  `PrivacyMaskService.isExempt`，而 `PrivacyMaskServiceImpl.isExempt` 每次调用执行一次
  无 WHERE 全表查询 `ruleMapper.selectList(null)`——unmask 请求数字段数 = 全表查询次数
  （循环内单查放大），且 isExempt 仅消费 3 列（target_field/enabled/exempt_roles）却全列
  取回（投影缺失）。规则表种子 5 行当前实害小，但 sensitive 路径每请求必放大。
- **修复（行为保持，方案 a：接口增批量判定面）**：`PrivacyMaskService` 增
  `exemptFields(roles, targetFields)` 批量豁免判定——内部规则单次装载（请求内内存复用）
  + 逐字段判定纯内存，判定面查询数与请求字段数解耦（N 字段 N 查 → 恒 1 查）；装载查询
  补 `.select` 精确投影仅取判定消费 3 列（A.4.3-14）；既有单字段 `isExempt` 保留（判定体
  逐字下沉私有 `isExemptAgainst` 共享，其余调用方零扰动）并同样走投影装载。`unmask` ①段
  豁免判定改用批量结果，exemptAll 聚合、firstUnexemptField 取值、诊疗关系第二道与异常
  语义零变化（判定纯函数，批量=同一规则快照上逐字段单查的逐词等价）。选择方案 a 而非
  unmask 端装载：A.4.3-21 要求豁免判定逻辑单点收口在脱敏引擎（禁复制判定逻辑），方案 b
  必然把词匹配+角色交集逻辑复制进 PrivacyServiceImpl 或暴露实体级装载面，违反该约束。
  **设计豁免消化**：类注释原「规则每次请求加载、禁提前缓存」口径更新为「每请求从库装载、
  同请求内复用、禁跨请求缓存」——请求内单次装载不跨请求、管理面变更下轮请求即生效，
  不违反原豁免意图；全量 @Cacheable（跨请求缓存）仍禁、须另行评审。applyAll/listRules
  全列取回按清单结论保留（applyAll 完整字段消费、listRules 对外契约）。行为锚定测试
  「四字段混合面批量判定恰一次查询+逐字段与单查口径等价（豁免/角色未命中/空豁免集/未登记
  四形态）」与「投影契约（wrapper .select 仅 3 列，非消费列不入投影）」「unmask 恰一次批量
  调用+逐字段单查零触达」先红后绿交付（红基线=委托版逐字段实现上恰一次断言失败，
  4 字段 4 查被拦截，1 用例红 10 绿）。
- **D-21 桩更新留痕**：既有用例桩面随实现机械迁移——PrivacyMaskServiceImplTest 的
  `selectList(null)` 放宽为 `selectList(any())`（豁免判定改经投影 wrapper 查询）、
  PrivacyServiceImplTest/PrivacyCareRelationGateTest 的 `isExempt(anyList(), anyString())`
  换 `exemptFields(anyList(), anyCollection())`（thenAnswer 全字段统一豁免/空集 = 原
  恒 true/false 语义的批量等价）；业务断言零改动；范围单点单次、与实现同 PR 原子交付。
- **验证**：`mvn -B -ntp -pl fuyun-patient -am test` 全绿（240 用例，+3 新锚定）+
  `spotless:check` 通过。

## 2026-09-29 · 性能清单修复环 OPT-10：outpatient 退费回执逐单号查询改清单键集一次 IN 批查（性能，行为保持）

- **根因（OPT-10 / BE-C4-18 ↔ BE-B1-05 归并组，2026-09-28 全仓性能与代码质量优化清单，
  评分 80，A.4.3-14 点名）**：`ChargingServiceImpl.onRefundApproved`（refund.approved 退费
  回执 MQ 消费业务体）@Transactional 内 `for (String orderNo : refs.orderRefs())` 逐单号
  无条件 `clinicOrderMapper.selectOne` 定位本域申请单——热路径每单必查（与同文件 :136/:153
  CAS 未命中条件分支重读性质不同，后者清单已认定豁免），N 单即 N 次单查，放大消费事务
  持锁时长；orderRefs 键集前置已知。
- **修复（行为保持）**：orderRefs 全集循环前一次 IN 批查 → `LinkedHashMap<orderNo,
  ClinicOrder>` 按号映射（uk_order_no 保证每单号至多一行=原逐单 selectOne 语义；遇序保序
  与原逐行处理序一致）；循环内取行换 Map.get，缺号映射缺位即原无命中幂等跳过分支（info
  文案逐字保持）；空清单短路零查询（与原空循环零查询语义对齐）。命中后状态流转写侧、
  逐单扇出、日志与幂等语义全部原形态零触碰；N 单 N 查 → 恒 1 查。行为锚定测试「多单号
  清单（两命中+一无命中缺号）批查恰一次+键集契约=清单全集+逐单号 selectOne 零触达+混合面
  输出等价（缺号幂等跳过不阻断同批，两命中单仍按清单序 CAS 与扇出）」先红后绿交付；既有
  4 个退费用例桩面随实现由 selectOne 机械换至 selectList（业务断言零改动）。
- **验证**：`mvn -B -ntp -pl fuyun-outpatient -am test` 全绿（295 用例，+1 新锚定）+
  `spotless:check` 通过。

## 2026-09-29 · 性能清单修复环 OPT-09：pharmacy 退费终态确认双重 N+1 改两级键集 IN 批查（性能，行为保持）

- **根因（OPT-09 / BE-C4-16 ↔ BE-B1-02 归并组，2026-09-28 全仓性能与代码质量优化清单，
  评分 80）**：`DispenseServiceImpl.confirmRefundTerminalByRx`（refund.approved 退费终态
  确认 MQ 消费业务体）@Transactional 内 `for (String rxNo : rxNos)` 双重逐号查询——逐
  rxNo `prescriptionMapper.selectOne` 查处方 + 命中 DISPENSED 再逐 rxNo `baseMapper.selectOne`
  查活动发药单，退费多处方时 2N 次查询放大事务持锁时长（A.4.3-14 点名）。
- **修复（行为保持）**：两级键集前置 IN 批查 + 原循环序单趟判定——①处方按清单 rxNos 一次
  批查按号映射（uk_rx_no 保证每号至多一行，与原逐号 selectOne 同语义；脏差异缺号映射缺位
  即原 null 分支）；②活动发药单按「第一级命中且 DISPENSED 的 rxNo 集」（原逐号查询的精确
  谓词面——非 DISPENSED 分支与缺号不进键集）一次批查按号映射（排除 CANCELLED，
  uk_dispense_rx_active 保证每号至多一行活动单=原逐号 selectOne 语义；缺号映射缺位即原无
  活动单分支；空键集短路零查询）。循环内两处取行换 Map.get；状态判定分支、casStatus 终态
  镜像、日志与幂等语义原形态零触碰；空清单兜底短路与原空循环零查询对齐。2N 查 → 恒 2 查。
  行为锚定测试「五处方号混合面（两 DISPENSED 待镜像 FULL/PART+一 DISPENSED 无活动单+一
  非 DISPENSED+一脏差异缺号）两级批查各恰一次+键集契约（一级=清单全集/二级=DISPENSED
  命中集+排 CANCELLED）+逐号 selectOne 零触达+混合状态输出等价（镜像遇序、守卫行零迁移）」
  先红后绿交付；既有 6 个终态确认用例桩面随实现由 selectOne 机械换至 selectList（业务断言
  零改动，无读形态断言需现代化）。
- **验证**：`mvn -B -ntp -pl fuyun-pharmacy -am test` 全绿（167 用例，+1 新锚定）+
  `spotless:check` 通过。

## 2026-09-29 · 性能清单修复环 OPT-08：pharmacy 缴费放行逐 rxNo 查询改清单键集一次 IN 批查（性能，行为保持）

- **根因（OPT-08 / BE-C4-15 ↔ BE-B1-02 归并组，2026-09-28 全仓性能与代码质量优化清单，
  评分 80）**：`DispenseServiceImpl.releaseByRxNos`（settlement.completed 缴费放行 MQ 消费
  业务体）@Transactional 内 `for (String rxNo : rxNos)` 逐号 `prescriptionMapper.selectOne`
  查处方——N 号即 N 次单查，MQ 消费路径放大事务持锁时长（A.4.3-14 点名）；清单脏差异场景下
  （部分号缺行）仍全额付出往返。
- **修复（行为保持）**：rxNos 全集循环前一次 IN 批查 → `LinkedHashMap<rxNo, Prescription>`
  按号映射（uk_rx_no 保证每号至多一行，与原逐号 selectOne 同语义；遇序保序与原逐行处理序
  一致）；循环内取行换 Map.get，缺号映射缺位即原 null 分支（warn 文案逐字保持，留痕不阻断
  同批放行）。通道过滤、CAS 放行、CAS 0 行重读定性、createDispense 建单入队、日志与幂等
  语义全部原形态零触碰；N 号 N 查 → 恒 1 查。行为锚定测试「多 rxNo 清单（两待放行+一脏差异
  缺号）批查恰一次+键集契约=清单全集+逐号 selectOne 零触达+缺号 warn 不阻断同批（两待放行
  号仍按清单序 CAS 与建单入队）+放行输出（CREATED/rxNo 序/saveBatch 两次）等价」先红后绿
  交付；既有 5 个放行用例桩面随实现由 selectOne 机械换至 selectList（断言零改动），其中
  「单据精确放行」用例的「selectOne 恰一次」读形态断言随批查契约现代化为「selectList 恰
  一次」（D-21 裁量：单点单次、严格度不降、原子同 PR、提交 body 留痕）。
- **验证**：`mvn -B -ntp -pl fuyun-pharmacy -am test` 全绿（166 用例，+1 新锚定）+
  `spotless:check` 通过。

## 2026-09-29 · 性能清单修复环 OPT-07：pharmacy 退费逆向作废三级级联 N+1 改三级键集前置批查（性能，行为保持）

- **根因（OPT-07 / BE-C4-17 ↔ BE-B1-02 归并组，2026-09-28 全仓性能与代码质量优化清单，
  评分 85）**：`DispenseServiceImpl.voidUndispensedByRx`（order.cancelled 退费逆向消费业务体）
  @Transactional 内三级逐行查询链——①逐 rxNo `prescriptionMapper.selectOne` 查处方、②逐处方
  `baseMapper.selectList` 查活动发药单、③逐发药单 `dispenseItemMapper.selectList` 查 NORMAL
  明细，N 处方即 N+N×M+N×M×K 查询，refund.approved/order.cancelled 消费同步窗口查询数随
  处方×单×行放大、拉长事务持锁（A.4.3-14 点名）。
- **修复（行为保持）**：三级键集前置 IN 批查 + 原循环序逐行判定单趟——①清单 rxNos 一次批查
  处方按 rxNo 映射（uk_rx_no 保证每号至多一行，与原逐号 selectOne 同语义，脏差异缺号映射
  缺位即原 null 分支）；②活动发药单按「全部命中处方 rxNo 集」批查按 rxNo 分组（排除
  CANCELLED、组内 id 升序，与原逐处方单查谓词/序一致；键集含守卫将跳过行——守卫在批查后
  逐行判定，跳过行不消费自身分组，只扩大读面不改写面）；③NORMAL 明细按「全部涉及发药单
  id 集」批查按单分组（组内 id 升序同原逐单单查）。分组容器 LinkedHashMap 遇序保序；各级
  空键集短路零查询。逐行判定趟内状态分支守卫、日志、ISSUED 脏数据显式暴露、写侧逐行 CAS
  （处方 CAS 先行→释放批次锁→明细退场→发药单 CAS）与作废留痕日志全部原语义原形态；
  N+N×M+N×M×K → 恒 3 查。**保留未批查化**：处方 CAS 0 行后的 `selectById` 重读定性
  （条件分支单查，批查快照过期/清单重复号的收敛锚，ChargingServiceImpl:136/:153 同性质
  豁免先例）。行为锚定测试「多处方多单多明细三级批查各恰一次+键集契约（清单全集/命中
  处方集/涉及发药单集）+处方逐号 selectOne 旧路径零触达+逐行作废输出（CAS 序/明细退场/
  锁释放/发药单作废/守卫跳过行零写）等价+混合批次 ISSUED 脏守卫 PH-1009 保持」先红后绿
  交付；既有 11 个作废用例桩面随实现机械换至批查面，其中 2 个用例的「活动单零读取」读
  形态断言随批查契约现代化为「键集批查恰一次+零写面断言保持」（D-21 裁量：单点单次、
  严格度不降、原子同 PR、提交 body 留痕）。
- **验证**：`mvn -B -ntp -pl fuyun-pharmacy -am test` 全绿（165 用例，+2 新锚定）+
  `spotless:check` 通过。

## 2026-09-29 · 性能清单修复环 OPT-05：pharmacy 处方开立逐药品 N+1 改 drugIds 去重批查（性能，行为保持）

- **根因（OPT-05 / BE-C4-14 ↔ BE-B1-03 归并组，2026-09-28 全仓性能与代码质量优化清单，
  评分 85）**：`PrescriptionServiceImpl.create` 明细装配循环内逐行
  `drugMapper.selectById(itemReq.drugId())` 点查后 `validateLine`——多药品处方（常见 3-10 行）
  即 3-10 次单查，开方为医生工作站高频操作、全院并发时往返线性放大（A.4.3-14 直接命中）；
  同方法写侧已 `Db.saveBatch` 而读侧逐行，形态不一致。
- **修复（行为保持）**：循环前收集全部 `itemReq.drugId()` 去重 → `drugMapper.selectByIds`
  一次批查（MP BaseMapper 自带；selectByIds 为 3.5.17 非过时形态——selectBatchIds 已标
  deprecated，与 pharmacy 模块 MedicationReviewServiceImpl 既有批查口径一致）→ 按 id 建
  Map，循环内改 Map 取行；批查缺行（Map 无键）取 null 行进 `validateLine`，与旧逐行点查
  缺行同一分支——PH-1003（409）+ 文案「药品不存在或已停用：drugId=请求行 id」逐字等价，
  行序不变、首个无效行报错语义保持，`validateLine` 本体零触碰；键集空集零查询（空明细属
  契约外形态，HTTP 面 @NotEmpty 已拒，与旧空循环零药品查询语义对齐）。N 行明细 N 查 → 恒
  1 查，同药多行（不同频次）去重批查天然共享装载。行为锚定测试「多药品处方批查恰一次 +
  逐行 selectById 零触达 + 重复药品多行明细/事件计费行逐字段等价 + 缺行错误码/HTTP 态/
  文案三重逐字等价」先红后绿交付；既有 8 个开方用例桩面随实现机械换至批查面（断言零
  改动，D-21 裁量：单点单次、严格度不降、原子同 PR、提交 body 留痕）。
- **验证**：`mvn -B -ntp -pl fuyun-pharmacy -am test` 全绿（163 用例）+ `spotless:check` 通过。

## 2026-09-29 · 性能清单修复环 OPT-04：billing 划价链预计价逐行 2N~3N 单查链改键集批查 + 逐行补偿校验（性能，行为保持）

- **根因（OPT-04 / BE-C4-13，2026-09-28 全仓性能与代码质量优化清单，评分 85）**：
  `PricingEngineServiceImpl.quote` 逐行单查链——行内 `requireActiveByCode` 逐行项目查询、
  组合项 `listComponents` 逐项构成、成员 `memberItem` 逐成员（getById + 按码生效守卫再两查）、
  `quoteLine` 逐行 snapshot（价格版本 + 医保对照再两查），N 行单据预计价 2N~3N 查询链
  （A.4.3-14 直接点名），划价/开单高频界面直连。
- **修复（行为保持）**：新增批量取数面——`IChargeItemService.listByCodes`（itemCode 去重 IN
  批查，含停用行供补偿校验区分缺行/停用）与 `listComponentsByComboItemIds`（构成 IN 批查按
  组合分组、组内 id 升序定序）、`IInsuranceMappingService.effectiveMappings`（ACTIVE 对照 IN
  批查）、`IChargePriceService.snapshots`（价格版本区间判定 IN 批查 + 逐项目 effective_from
  DESC 首行收敛，与单查同口径）；quote 改「三跳键集预取（项目 → 构成/成员 → 快照，四类取数
  各恰一次）+ 逐行补偿校验」：缺行 BILL-1001/404、停用 BILL-1003/409、组合未维护构成与无
  生效价格 BILL-1008/409 均与逐行单查同码同文案同 HTTP 态且行序不变（首个无效行报错）；
  金额算式、组合展开（数量=行数量×构成默认量）、合计与快照装配零变化，取价时刻收敛为单次
  求值（同一单据同一瞬时定价，区间判定语义与单查一致）；N 行单据 2N~3N 查询链 → 恒 3 查
  （无组合）/5 查（含组合），与行数解耦。单查面（requireActiveByCode/listComponents/
  effectiveMapping/snapshot）与 generateFromSource 实收链及其测试零触碰。行为锚定测试
  「多行单据四类批查各恰一次 + 逐行单查链零触达 + 金额逐字段等价 + 补偿校验错误语义等价」
  先红后绿交付；既有 3 个 quote 用例桩面随实现机械换至批查面（断言零改动，D-21 裁量：
  单点单次、严格度不降、原子同 PR、提交 body 留痕）。
- **验证**：`mvn -B -ntp -pl fuyun-billing -am test` 全绿 + `spotless:check` 通过。

## 2026-09-29 · 性能清单修复环 OPT-03：nursing 交接班生成在途任务 N+1 改 visitIds 单次批查 + 惰性逾期批量 CAS（性能，行为保持）

- **根因（OPT-03 / BE-C4-01，2026-09-28 全仓性能与代码质量优化清单，评分 85）**：
  `ShiftHandoverServiceImpl.generate` 步骤③外层遍历在区患者、内层逐患者调
  `inFlightByVisit` 单查在途任务（每次 selectList 单查 + markOverdueLazily 逐行惰性逾期
  CAS），病区满员 50 人即 50 查 + 潜在 50 写（A.4.3-14 直接点名），交接班生成路径。
- **修复（行为保持）**：`INursingTaskService` 新增 `inFlightByVisits(Collection)` 批量面
  ——visitIds 键集前置已知（在区患者视图先行汇总），一次 IN 批查 + 内存按 visitId 分组，
  交接班生成改走批量（50 查 → 1 查，输出序不变：患者床位序 + 组内计划时间升序）；守卫
  判定（在途 + 未标记 + 越阈值）命中的越阈值未标记行收敛为单条 `casMarkOverdueBatch`
  批量 CAS（病区级最坏 50 写 → 1 写），per-row overdue_flag=false 谓词保持仅首次递增、
  escalation_count 生命周期至多一次递增（overdue_flag 单向置位无复位路径）故守卫行回写
  与库态恒一致——批量 CAS 与逐行 CAS 语义逐行等价；`inFlightByVisit` 单查面保留（Task 3
  详情卡仍消费），list/inFlightByVisit 既有逐行路径与其既有测试零触碰；行为锚定测试
  「多患者交接班生成恰一次批查」先红后绿交付。
- **验证**：`mvn -B -ntp -pl fuyun-nursing -am test` 全绿（211/0）+ `spotless:check` 通过。

## 2026-09-29 · 性能清单修复环 OPT-06：V1105 outpatient.clinic_order 处方引用行 CAS 谓词补 ext_ref 部分索引（性能，行为保持）

- **根因（OPT-06，2026-09-28 全仓性能与代码质量优化清单，评分 85）**：`ClinicOrderMapper`
  casRxRefCharged / casCancelRxRef / casMirrorDispensed / casMirrorReturned 四支 CAS 均以
  ext_ref + order_type='RX_REF' + deleted 谓词定位行（settlement.completed /
  prescription.cancelled / dispense.completed / dispense.returned 四类事件消费），V203 既有
  索引均不含 ext_ref，引用行定位只能对 clinic_order 全表顺序扫描，随门诊开单量增长线性劣化。
- **修复（行为保持）**：新增增量迁移 V1105 建 `idx_clinic_order_ext_ref (ext_ref)
  WHERE order_type = 'RX_REF' AND deleted = 0`——四支 CAS 由顺序扫描 → 索引点查；部分谓词
  与语句常量条件严格同构，非处方引用五类单据行（ext_ref 恒 NULL）不入索引，索引体量随处方量
  而非开单总量增长；SQL 谓词与 Java 代码零改动，既有迁移 V203 未触碰（A.4.1-3 禁改红线）；
  普通 CREATE INDEX（Flyway 事务内 CONCURRENTLY 不可用，V808/V900 同款取舍）。
  注册表与 CHANGELOG 同 PR 先记再改。

## 2026-09-29 · 性能清单修复环 OPT-02：V1104 billing.refund_fee_link 退费聚合驱动侧补 fee_id 前导部分索引（性能，行为保持）

- **根因（OPT-02，2026-09-28 全仓性能与代码质量优化清单，评分 85）**：RefundRequestMapper.xml
  两支可退余额聚合下推 SQL（PERF-01）以 `l.fee_id IN (...)` 驱动 JOIN refund_request，V603
  uk_refund_fee (refund_id, fee_id) 前导列为 refund_id，fee_id 非前导不可用，聚合对
  refund_fee_link 只能顺序扫描——退费 apply 超可退守卫为资金热路径，EXECUTED 终态 link 行
  随运营年限单调增长。
- **修复（行为保持）**：新增增量迁移 V1104 建 `idx_refund_fee_link_fee (fee_id)
  WHERE deleted = 0`——驱动侧由顺序扫描 → 索引点查集；单列即足（JOIN 键与聚合列仍需回表，
  扩列无 index-only 收益）；XML 内两支 SQL 语句零改动；同步修正 RefundRequestMapper.xml
  头注释「索引聚合（fee_id 侧驱动）」与 V603 schema 的矛盾表述（改锚 V1104 索引实况，
  RefundAggregateSqlGuardTest 逐子句守卫不受影响）；既有迁移 V603 未触碰（A.4.1-3 禁改
  红线）；普通 CREATE INDEX（Flyway 事务内 CONCURRENTLY 不可用，V808/V900 同款取舍）。
  注册表与 CHANGELOG 同 PR 先记再改。

## 2026-09-29 · 性能清单修复环 OPT-01：V1103 billing.fee_record 发药链 CAS 谓词补 source_ref 前导部分索引（性能，行为保持）

- **根因（OPT-01，2026-09-28 全仓性能与代码质量优化清单，评分 90）**：`FeeRecordMapper`
  casMarkDispensed / casReleaseDispense 两支 CAS UPDATE 以 source_ref + trigger_point +
  exec_occupy_status + deleted 谓词定位行（M06 dispense.completed / dispense.returned 事件
  消费主路径，每张处方发药与全额退药各触发一次），V602 既有索引均不含 source_ref 前导列，
  事件通道每次消费对 fee_record 全表顺序扫描，随费用明细量增长线性劣化。
- **修复（行为保持）**：新增增量迁移 V1103 建 `idx_fee_source_ref_trigger (source_ref,
  trigger_point) WHERE deleted = 0`——两支发药链 CAS 由顺序扫描 → 索引点查，O(全表) →
  O(log n + 单据行数)；casConfirmByOrder / casCancelPendingByOrder 的 (visit_id, source_ref)
  复合经评估不建（V602 idx_fee_visit_status 已对 visit_id + status 双等值前缀服务，第三条索引
  纯冗余，本索引 source_ref 前导列另提供兜底路径）；SQL 谓词与 Java 代码零改动，既有迁移
  V602 未触碰（A.4.1-3 禁改红线）；普通 CREATE INDEX（Flyway 事务内 CONCURRENTLY 不可用，
  V808/V900 同款取舍）。注册表与 CHANGELOG 同 PR 先记再改。

## 2026-09-29 · 风险清单修复环分流：BUG-24 被 Maven 依赖环阻塞登记 D-28（裁决留痕）

- **背景**：2026-09-28 全仓高风险问题清单 BUG-24（BE-C3-09，低危）要求 integration 模块四治理写端点
  （DeadLetterController replay/close、MdmSubscriptionController register/unregister）补 @AuditLog
  （Spec 20-integration.md:170「通道配置与死信处理操作全量审计」）。
- **阻塞事实**：注解契约 AuditLog/AuditActionType 落 com.fuyun.system.api，而 fuyun-system 已编译依赖
  fuyun-integration（消费消息治理 api，pom 注释明示「无反向依赖」）；integration 反向依赖 system 即
  Maven 反应堆成环（实测 cyclic reference 构建拒绝，任意 scope 同样成环）。切面 pointcut 为直接
  @annotation 绑定，元注解/自建副本均不可拦截，无小改合规出口。
- **裁决**：执行派发边界内默认项「挂账暂缓」登记 TASK.md D-28 待决策（用户未响应 ask_question，
  取推荐项，可推翻）；结构性解法二选一待裁：①审计注解契约下沉 fuyun-common（67 文件/11 模块
  import 更新 + 宪法留痕 + 全仓 CI）②反转 system→integration 边（消息治理契约搬家，更大）。
  挂账期间四端点 service impl 已有 log.info 应用日志留痕，缺统一审计台账（system.audit_log）。
- **同环交付**：BUG-21（fuyun-patient VisitCardServiceImpl bind/replace 补 info 留痕）不受阻塞，
  独立提交完成（288d2fb）。

## 2026-09-28 · PR #57 合并前修复环收口：四笔修复 + scoped 复审 + 五条分流登记（先记再改）

- ① **四笔修复（用户 2026-09-28 决策派发合并前修复环，范围锁定 B1/B2/P1/C2/C3 五项不扩大）**：
  3c5f95c B1——AlarmEngine evaluate 批事务内 fireNewAlarm 捕获 DuplicateKeyException 后 PG 25P02
  中止态连带回滚整批告警，每条告警「落行 + 事务内 triggered 事件 + afterCommit 推送/补推登记」
  收进独立 REQUIRES_NEW perAlarmTx（对照 VitalSign 范式），冲突只丢单条、推送时机语义不回退，
  补并发冲突用例；5d9c73d B2——风暴解除补推排空自 THRESHOLD 循环上提至 evaluate() 评估起点，
  DEVICE_ALARM/OFFLINE 遗留队列不再静默等 1h TTL 过期，补两规则源排空用例；d2f7e5c P1——
  fuyun-ward api 包补 package-info @NamedInterface（照 fuyun-iot 同款），连带 ward pom 补
  spring-modulith-api（provided，BOM 托管零声明，注解编译类路径所需）；18dc26c C2/C3——
  OfflineDetector 断流候选与 QualityServiceImpl 在线设备扫描两处 LIMIT 截断补
  orderByAsc(last_online_at)（A.4.3-17），处置子集确定化。
- ② **scoped 复审**：四笔落地后范围限定复审（仅核四笔改动面与回归），结论 READY_FOR_CI。
- ③ **五条分流登记（TASK.md，同日修复环决策，不扩大修复环范围）**：C4 OfflineDetector 逐规则
  循环单查（A.4.3-14，「批级口径」注释未申报）→W-60 随 PR-3（收敛单条动态 SQL 或补正式偏差
  申报，二选一）；C1 HuaweiIotdaRegistry 外部网关无超时/读超时/有界重试/熔断显式配置（B.4-2）
  →W-61 随 PR-4（IoTDA 联调硬前置）；C6 CommandDispatcher deliveryExecutor 无界
  newCachedThreadPool（B.3-4）→W-62 随 PR-4；C7 registry/huawei 包位 vs B.4-1 gateway/adapter
  归位→待决策 D-26（迁包或修宪豁免，二选一）；H1 消费错误重放与 raw_payload 脱敏/4000 截断
  契约冲突→待决策 D-27（产品裁决重放适用范围，短期止血「打码/截断形态重放前显式拒绝」随裁决
  一并定）。评审来源：PR #57 合并前 /code-review 修复环分诊（评分与核实细节见评审记录留档）。

## 2026-09-27 · simulator 命令回执断言竞态窗口加宽（CI 稳定化）

- CI runner 唯一失败用例 CommandSubscriberTest#respondsSuccessReceiptForSupportedCommand（run 36346561581，
  本地历轮全绿）：回执断言 2s 轮询窗口在 CI 高负载下偶发不足（异步回执未达断言窗口）。修复提常量
  AWAIT_MILLIS=5000（复用同模块既有字面量，fuyun-iot 同名惯例）加宽窗口，类内同型六处 timeout 统一替换，
  断言内容与生产码零变更——终审分诊工单 W-52 CI 稳定性张（T10②/T14①/T15⑦）之 **T14① 顺带核销**，余项仍挂。

## 2026-09-27 · 终审收尾双提交：comment fixup（必修四条+搭车十二条）与终审分诊工单登记 W-49~W-59（Ready to merge 后零生产逻辑变更）

- ① **件一 comment fixup（commit 6341cef，15 文件纯注释/文案修正，零逻辑零断言变更）**：必修四条——
  IotSeqGate「计划 GC15」实为 GC13（禁 Lua 条款，类注释与 next 行注两处）；IotMessagingConfig 定绑锚
  GC7→GC6、不注册回调 GC8→GC7（Progress Task 2 审查 deferred-① 编号错位收口，javadoc 三处）；
  MessagingGovernanceIT DisplayName「八十一条」→「八十二条（id 74–82）」与 totalRows=82 断言一致；
  CommandDispatcher challengeId 明文 warn 补终审豁免注记（GETDEL 一次性消费+TTL 120s+仅失效路径
  打印，不做掩码改码）。搭车十二条：IBindingService 类注释遥测富化通道改批量面（listActiveByDevices）
  且 listActiveByDevices 参数措辞对齐 null 短路实现；ProductVO createdAt 注明 POST /products 上架响应
  未经回查为 null；IotDeviceRegistry 类注释「五面」补删除/注销面；DeviceStatusServiceImplTest 存在性
  查询 helper「三列投影」→「四列投影」（投影含 last_online_at）；application.yml 波形日配额预留参数补
  TODO(wave-quota) 格式；TelemetryPushServiceImpl「两轮轮询」→「四轮」（2000/500）+ buildAlarmPayload
  补 occurredAt 漂移口径注记（last_triggered_at 经计数 UPDATE 原地刷新，补推/强化重推晚于 MQ 首发值）；
  IotConfig Task 7 告警域件数七→八（含 AlarmProperties，与 @Import 行注八件对齐）；冷链请求 DTO 与实体
  「其余类型忽略置空/为空」→「不校验不置空、按请求原样落库」（registerRecord 实况）；WardMessagingConfig
  订阅事件三→四（V1004 id 74/78/81 与 V800 id 63）；ProductManageView 映射弹窗「编码退化为手工录入」→
  「指标编码下拉无选项（映射行无法补全）」（空 select 实况）。
- ② **件二工单登记（本提交）**：终审分诊表「立工单」清单按域归并 11 张（W-49~W-59，每张注明来源条目号
  与一句修法指引）——W-49 SeqGate 原子性（T3⑥/T12④）/ W-50 Wrapper 条件断言（T5②，建议下一 PR 首项）/
  W-51 输入校验面（T4③/T4④/T13①）/ W-52 CI 稳定性（T10②/T14①/T15⑦）/ W-53 性能面（T7①/T10①/T11①）/
  W-54 临床与运维可见性（T12⑥ 输液停报 NONE 掩盖红档 + T15⑤ 分页截断提示 + T17① 趋势图冻结）/
  W-55 契约防重（T12⑧）/ W-56 命令与设备域轻量项（T8② 注记已搭车存档掩码选项、T7④/T5③/T5④/T8③/T4②）/
  W-57 消费链与推送域轻量项（T10③/T11③/T11⑤/T6③/T7⑤/T14③/T12②）/ W-58 前端轻量项（T15②/T15③）/
  W-59 消息域 GC 编号引用错位残余（WardSeqGate「计划 GC15」与 ward/iot 消息域 GC7/GC8 旧引用——必修②
  同类扩展，非终审清单项；PR-1 产物各自计划语境引用不动）。
- ③ **验证与跳过清单**：`python scripts/check-encoding.py` 通过；`mvn -pl fuyun-iot,fuyun-app -am
  test-compile` BUILD SUCCESS（编译零破坏）。跳过条目：T15⑥「注册缺项用例计数笔误」——现场核对四页
  spec 头部/用例计数（6+6+5+6=23 与 Task 15 报告一致）、注册缺项用例题面四必填字段（设备ID/产品ID/名称/
  类型）与注册表单实况、SimulatedRegistryTest 七用例/HuaweiIotdaRegistryTest 三用例均名实相符，未定位到
  笔误，按「禁猜」原则跳过（明细见 .superpowers/sdd/2026-09-25-p2-pr2-m14-m16/task-18-final-fixup-report.md）。

## 2026-09-27 · Task 18 真栈探针修复环 round 2：D1 AMQP 启用态 Clock 二义启动失败修复（D2/D3 配置面零代码闭合）

- ① **D1（阻断级）根因与修法**：`fuyun.iot.amqp.enabled=true` 时 `IotAmqpConfig.iotAmqpClock` 与
  `IotWebSocketConfig.iotPushClock` 双 `Clock` Bean 并存，Spring Modulith 事件注册表工厂方法
  （`EventPublicationAutoConfiguration#eventPublicationRegistry`）经 `ObjectProvider<Clock>` 按
  类型无标识解析（库内注入点挂不上 @Qualifier）二义失败，经 EventOpsJob 依赖链阻断启动——
  全量门禁 IT 恰有 @Primary 测试时钟（IotTelemetryPipelineIT/IotAmqpReconnectIT）遮蔽，CI 绿而
  真栈红。修复取 BillingWebConfig 取价时钟同款先例（非纯 @Qualifier 形态的裁决依据：库内注入点
  不可限定，且任一生产 Clock 加 @Primary 会与既有两 IT 的 @Primary 测试时钟双 primary 冲突）：
  删除 `iotPushClock` 全局 Bean，推送节流时钟改 `IotWebSocketConfig` 装配点显式构造
  `Clock.systemUTC()`；`TelemetrySummaryAggregator` 构造器摘除 @Qualifier；enabled 两态按类型
  候选均 ≤1（true=iotAmqpClock 单候选，false=零候选走注册表内置 UTC 默认），AMQP 凭证时钟与
  推送节流时钟语义各自不变（推送时钟生产恒系统 UTC，单测构造器注入固定时钟不变）。
- ② **D1 回归锚**：新增 `IotAmqpClockAmbiguityTest`（fuyun-app，ApplicationContextRunner 走真实
  Modulith 自动配置，无容器 CI 可跑）两面——缺陷机理面：双无主 Clock 候选下上下文启动失败且
  报告点名 `iotAmqpClock/iotPushClock/eventPublicationRegistry`（与真栈错误同形，防再引入第二
  全局 Clock Bean）；修复面：真实 `IotAmqpConfig`（enabled=true + 连接四要素）与
  `IotWebSocketConfig` 同上下文装配成功、Clock 候选恰一、注册表解析成功、聚合器 Bean 在位。
- ③ **D2/D3 定性与零代码闭合**：D2 实为映射管理面（PUT metric-mappings）字典存在性校验
  （IOT-1004）拒绝 `INFUSION_SHORTAGE`——ingest 两面（HTTP 兜底/AMQP）词表外直通本就同构无差异；
  词表缺口走既有运行时登记端点 `POST /api/v1/iot/metrics`（字典自管设计内面，ward 侧
  WardMessagingConstants「词表缺位申报」预告的对齐路径）闭合，不改 V1007 种子、不新增迁移号、
  ward 侧 metricCode 精确相等语义不动；D3 经核 `deploy/.env.example:86` 占位键与
  `docker-compose.yml:111` 接线在 HEAD 已齐备，缺口仅在本地真实 `.env`（红线禁动，演示经进程
  环境变量注入）。

## 2026-09-27 · P2 PR-2 收口：M14/M16 七条验收锚点 IT 全绿与文档收口（Task 18 批次 D）

- ① **事件与号段收口**：**事件 id 74–82 落地**（74–81 iot 八事件落 V1004、82 ward.cold-chain.alert-archived 落 V1102，三方一致契约锚 IotMessagingContractTest/WardMessagingContractTest）；**迁移号勘误申报**（乱序守卫裁定出处=本文件 2026-09-26「P2 PR-2 前置」条目⑤ + 台账 `docs/migrations/flyway-version-registry.md` 同日行）：iot 原排 V404–V413 与 nursing 原排 V809 低于基线全局最大已应用 V1003 被乱序守卫拒止，改通用段续号 **V1004–V1013/V1014**（billing V1001–V1003 先例），ward **V1100–V1102 维持**（全新 schema 享号段初始化豁免且 V1100>V1003 双保险）。
- ② **D-21 断言现代化留痕（主控义务①）**：Task 6 对 `IotTelemetryPipelineIT` 步骤⑨ quality 断言 **GOOD→SUSPECT 单点等严修订**——取证报文为固定历史时点（真实取证原文不改），时间合理性新契约下精确值即 SUSPECT，行数断言（恰 2 行）续守「标注不丢弃」语义；四边界齐：**逐次批准**（用户 2026-09-22 批复 D-21 破例制度化，根 AGENTS.md §7 回归红线出口）/ **严格度不降**（`isEqualTo` 全量精确匹配，未放宽为部分匹配）/ **原子交付**（断言修订+实现变更+回归锚同 PR）/ **留痕**（task-6-report 申报 + 本条）。
- ③ **IOT-1023~1025 词表扩容申报（Task 11 主控裁定接受，借用先例延续）**：网关 CRUD 域计划无冻结码位（计划缺口），沿 Task 4 确立的「IOT-1023 起接续顺延」先例借增 GATEWAY_NOT_FOUND(404)/GATEWAY_ALREADY_EXISTS(409)/GATEWAY_STANDBY_INVALID(409)；`IotErrorCodeTest` 冻结全集 22→25 逐位连续 + HTTP 映射全量精确匹配（严格度不降）。
- ④ **交付面落地**：Registry 双实现（`fuyun.iot.admin.enabled` 条件装配——false 缺省 SimulatedRegistry（CI/单测/IT 恒此形态），true 走 HuaweiIotdaRegistry 出网华为 SDK 3.1.218（表外申报先例），调用失败统一 RegistryException→IOT-1022(503)）；**iot-simulator 生产级升级**（Task 14 @ d11a2d8：输液场景剧本/临床值域多指标/设备状态帧/命令下行订阅）；W-7 数据面（V1005 raw_value）与 W-10 类型改造（V1006 CF-3 VARCHAR(14)+夹具重插）随验收锚 IT 落地；**七条验收锚点 IT 全绿入库**（679dc3f：IotNonNumericIngestIT/IotBindingMigrationIT/IotTelemetryQueryIT/IotAlarmClosedLoopIT/IotCommandFlowIT/IotLinkageFlowIT/WardCallColdChainIT，全量门禁通过）。
- ⑤ **casClose 实况收口（与 14-iot.md §13 第 10 条注记同源）**：告警关闭 CAS 实况允许 ACTIVE/ACKNOWLEDGED 两态直关（`IotAlarmMapper`:74-76），与 Spec §5 线性生命周期描述的矛盾以收口注记收口——主控裁定后端实况为准，前端已三方收敛（api 注释/视图暴露/测试用例，Task 15）。
- ⑥ **批次 A/B/C 生产代码修复申报面**：七条 IT 验证报告（A：IotNonNumericIngestIT+IotBindingMigrationIT / B：IotTelemetryQueryIT+IotAlarmClosedLoopIT / C：IotCommandFlowIT+IotLinkageFlowIT / ward：WardCallColdChainIT）**生产代码改动均为零**。测试侧修复申报：A 批次 IotNonNumericIngestIT 两处（兜底 token 动态属性注入缺失致 401、非法 JSON 输入改 `{bad}` 精确命中树规整失败兜底分支）、B1 批次 IotTelemetryQueryIT +16 行（动态属性/CALL 形态回刷/注释校正）、门禁期 IotTelemetryPipelineIT 步骤③ pollBatchSummaryFrame 时序适配（ff5c9c4：2s 窗口节流下滞后摘要帧过滤后取本批帧，断言契约与生产码零改动）；B2/C1/C2 一次通过零改动；ward 批次首跑即绿，contracts 三处事实修正（LT 越限示例、操作者取登录 id、complete 空白 400 出处）仅测试侧锚定，spotless 顺带归一四个姊妹 IT 文件格式（零逻辑变更，已在收口提交面）。终局申报：全量门禁三轮证据终局 BUILD SUCCESS（首跑 TelemetryPipeline 回归经 ff5c9c4 已修、run2 Pharmacy 超时定性环境抖动单跑复现绿、run3 全零终局 EXIT=0）+ 前端 audit 官方源复核零漏洞；Task 4 物模型 JSON 双形态不对称经可达性核链判定不修（华为同步专属解析面在 CI/单测/IT/演示链全路径不可达——admin.enabled=false 缺省装配 SimulatedRegistry 原样存储不解析、批次 E 演示链管理面申报待凭证注入，生产触发需凭证注入+超契约包裹形态入库双条件且失败为显式 IOT-1022 可对账，依据申报见 task-18-gate-report「遗留申报」节）。
- ⑦ **文档收口（GC29/GC30）**：`docs/specs/modules/14-iot.md` 追加「§13 P2 PR-2 落地注记」10 条（W-7/W-10 数据面形态、事件 id 74–81、Registry 双实现切换口径、GC17①③ 降级（GC17⑥ 测量队列条审查门归位 16-ward 侧，14-iot 侧留一句话指路）、OTA 与波形查询端点顺延、iot_metric_dict 自管面（词表外直通行质量按管道重算，「词表外 SUSPECT」仅报文侧缺省语义）、casClose 实况、WS 尾帧 ≤2.5s 量级注记）；`docs/specs/modules/16-ward.md` 追加「§13 P2 PR-2 落地注记」7 条（ward 号段 V1100–V1102 零迁移豁免段/事件 id 82、GC17②④⑤⑥ 降级、nursing.infusion.completed 消费骨架 PR-3 接线声明、route 任务转换 PENDING）；TASK.md 销项——**W-7 删除**（V1005+IotNonNumericIngestIT 闭合）、**W-10 删除**（V1006+IotBindingMigrationIT 闭合）、**D-22 删除**（V1014+Task 13 幂等键链闭合）、**W-48 保留**（Task 1 排查结论已在位：三排查点无可确定性收敛缺陷、@ServiceConnection/latch/future.get(60s) 均非固定 sleep，失败形态 Docker 闪断，不改码留单继续观察）、**T-R3-5 回填闭环**（官方页 1MB 实测 + Task 14 帧体 147B/74B/40B 双证据，无需分片）；其余工单行不动（W-27/W-34/W-37~W-41/W-47/D-25 归 PR-3/PR-4）。

## 2026-09-27 · P2 PR-2 Task 13：D-22 PDA 弱网补传幂等键收敛（nursing V1014 + 重放语义 + PdaView 幂等键）

- ① **V1014 迁移落盘**（nursing 段，`V1014__add_vital_sign_client_msg_id.sql`）：vital_sign_record 增
  可空 client_msg_id VARCHAR(64) 列 + 稀疏部分唯一索引 uk_vital_sign_client_msg（WHERE
  client_msg_id IS NOT NULL AND deleted = 0）——DB 层最终兜底，与 NS-1016 应用层语义构成两层幂等
  （A.5-6 同构）；号段依 2026-09-26 条目⑤勘误走通用段（registry 台账 V1014 行 Task 1.5 已登记，
  本次核验一致零微调）；V803 禁改红线不变（新文件 ALTER，零触碰既有迁移）。
- ② **后端重放语义（GC16 方案 B 冻结口径）**：VitalSignRecordRequest 增可空 clientMsgId（向后兼
  容，空白归一 NULL 不占稀疏键位）；record() insert 捕获 DuplicateKeyException 后按 client_msg_id
  回查——命中重放返回原 VO（HTTP 200 非 409，观察行/体温单条目/事件零重复），未命中（极端并
  发下行已逻辑删）维持 NS-1016，无键请求既有路径全不变；measuredAt 服务器时间红线不动（GC25）。
  **实现注记**：回查经构造器注入 PlatformTransactionManager 构建只读 REQUIRES_NEW 事务模板承载
  （replayLookupTx）——PostgreSQL 唯一冲突即中止当前事务（25P02），同事务内 SELECT 必失败，须
  挂起死事务以独立新事务回查（IT 真栈实证：同键重试 200 返回原行 id，无 25P02/无 409）。
- ③ **前端 PdaView 幂等键**：vitalClientMsgId 生成后保持，成功落卡或换患者识别才轮换（弱网在途
  失败重试复用同一键，服务端按键重放收敛补传）；键置于 await 之前捕获；payload 增 clientMsgId。
  **实现注记**：newIdempotencyKey() 带非安全上下文回退（crypto.randomUUID 仅 HTTPS/localhost 可用，
  院内 PDA 经 nginx :80 HTTP 访问该 API 缺位会 setup 即崩）——回退自拼 v4 形态，仍为 ≤64 位标准串。
- ④ **测试**：VitalSignServiceImplTest 扩四组用例（同键重放返回原 VO/无键 409 保持+零回查锚/异键
  新行/空键兼容）+ 回查未命中兜底例，TDD 先红后绿；NursingVitalSignFlowIT 扩 step11 重试重放真栈
  用例（200 同 id 同刻，行数/条目/观察行零新增）；前端 PdaView.spec 扩键随载荷+成功轮换与失败重
  试复用同键两例。api.d.ts 经 pnpm gen:api 全量重生成（临时导出 IT 等价 curl /v3/api-docs 管道，
  用后即删）：+78 schema/+54 path 全为 PR-2 Tasks 2–12 iot/ward 契约首次入库，零 schema/路径删除
  （−行均为 operation id 改号噪声），nursing 面净增 clientMsgId 一行。

## 2026-09-26 · P2 PR-2 Task 12 装配面增量：ward pom 依赖增补 + iot api.payload NamedInterface 暴露

- ① **fuyun-ward pom 增补 fuyun-iot 依赖**（仅消费其 api NamedInterface 面）：Task 1 pom 注释
  「iot↔ward 零模块依赖」先于 Task 10 端口演进——`IotTelemetryQueryPort` javadoc 明示「ward 依赖
  iot api 包 = Modulith verify 把关的 api 面」（冷链温度曲线/输液看板消费面），Task 12 实装按端口
  契约增补；事件链零模块依赖语义不变（消费走 RabbitMQ 队列，Modulith 边界仅放行 api 包引用）。
- ② **iot/api/payload 子包 NamedInterface 暴露**（新增 package-info.java）：Modulith 1.4 子包默认
  不继承父包 NamedInterface——ward 消费 `AlarmTriggeredPayload`/`TelemetryAnomalyPayload`
  （V1004 id 74/78 冻结契约）触发「depends on non-exposed type」红灯；载荷 record 本就是跨模块
  消费契约（V1004 种子三方一致红线），`@NamedInterface("api")` 显式声明即设计意图落纸。

## 2026-09-26 · P2 PR-2 前置：ward 号段登记（V1100 段）+ 事件 id 74–82 排定 + 表外依赖申报 + JaCoCo 扩名单

- ① ward 固定百位段 **V1100–V1199** 登记（`scripts/check-migration-governance.py` `_SEGMENTS` 增行
  + `docs/migrations/flyway-version-registry.md` 台账同步）：首批 V1100–V1102（呼叫域两表/冷链域两表/
  事件种子 id 82，随 PR-2 Task 12 落盘）；基线全局最大 V1003，V1100 > V1003 乱序守卫天然通过
  （ward 全新 schema 另享号段初始化豁免，双保险）。
- ② **事件 id 74–82 排定**（全局递增，先例 V901 id 65–72/V1002 id 73，撰写期实测 event_registry
  最大 id=73）：74–81 iot 八事件（alarm.triggered / alarm.escalated / alarm.closed /
  binding.changed / telemetry.anomaly / command.completed / linkage.executed / call.triggered）
  落 iot V1004；82 ward.cold-chain.alert-archived（producer=ward）落 ward V1102；
  MessagingGovernanceIT 总行断言两批落改——Task 2 改 73→81（V1004 +8），Task 12 改 81→82（V1102 +1）。
- ③ **表外依赖申报**：后端 `com.huaweicloud.sdk:huaweicloud-sdk-iotda` + `huaweicloud-sdk-core`
  （华为云 IoTDA 管理 SDK，Registry 双实现之 HuaweiIotdaRegistry；Boot BOM 外依赖，版本执行期实取
  maven central 锁定 **3.1.218**（2026-09-26 复核 metadata，lastUpdated 2026-09-24），仅 fuyun-iot
  pom 显式声明，父 POM dependencyManagement 不动；PR-2 结束前补 docs/language 定稿表）；
  前端 bigscreen `echarts`（IoT 运营大屏图表首引，pnpm 锁定，随 PR-2 前端任务引入）。
- ④ **JaCoCo 规则二纳入 `com.fuyun.ward.service.impl`**（父 POM PACKAGE LINE=1.00）：呼叫状态机/
  冷链合规台账属「核心业务状态机」路径；撰写期实测 `com.fuyun.iot.service.impl` 已在名单（父 POM
  :271）无需增行，仅新增 ward 行；包不存在时规则零包平凡通过，首个 impl 落码即生效。
- ⑤ **迁移号勘误（先记再改）**：全局乱序守卫（基线最大已应用 V1003）拒止 iot V404–V413 与 nursing
  V809——改通用段续号 V1004–V1013/V1014（billing V1001–V1003 先例），ward V1100–V1102 维持；后续 PR
  各模块固定段已低于全局最大者，增量一律走通用段续号。

## 2026-09-26 · 宪法修订：web C.7 谋建琢三段律按体系模板内置条款补强

- 范围：仅 `web/AGENTS.md` §C.7 一节（标题层级 `##`→`###` 归位，与 C.1–C.6 一致；正文按 constitution-generator `template.md` A.8 原文直写），其余章节零改动。
- 理由：该技能反模式明确禁止前端三段律弱化「凡涉 UI 任何变更无论大小一律强制全流程、逐段强制加载技能、禁空思考」前置；现行 C.7 恰为弱化版——总则仅一行、缺强制加载与禁空思考前置，谋 / 琢两段红线漏「未加载技能」条件，协作纪律未覆盖当前 agent 且缺「未加载不得开工」句。
- 修订内容：总则扩为模板原文（无论改动大小一律强制全流程、逐段先实际加载技能、顺序不可逆、禁空思考、禁以改动小绕段、违律产出不得交付）；三段 text 块各补「核心工具（强制加载）」与「强制前置（未加载即思考 = 空思考，无效）」行；谋段红线补「未加载技能」，建段补「无图施工即违律」前置，琢段红线补「未加载技能」；协作纪律扩为「当前 agent 与被派遣 subagent 一律开工前实际加载对应技能（谋 / 建 → `@ui-ux-pro-max`，琢 → `@taste-skill`），派遣指令必须写入加载要求，未加载不得开工，凭经验空思考视为未执行本律」。

## 2026-09-26 · P2 PR-1 收口：M04 住院域五条验收锚点 IT + 文档收口（Task 16）

- ① **五条验收锚点 IT 落码跑绿**（`fuyun-app` 真栈 Testcontainers，三容器类级独占 GC9）：`InpatientAdmissionFlowIT`（入院链+W-34 三事件真投递+EMPI 在院合并拦截 PAT-1006，6 用例）/ `InpatientOrderFlowIT`（CF-6 医嘱闭环全链：created.drug→pharmacy 审→回执→AUDITED→转抄→execute-confirm 四字段契约→停嘱；lab 自动过审+audited.lab 子键路由+M13 通配消费，7 用例）/ `InpatientTransferDischargeIT`（转科五步+出院全链[挂账→真实结算→双条件确认 IP-1017 拒绝]+带药放行+随访，8 用例）/ `InpatientDailyDecomposeIT`（bid/qd 分解+幂等+补偿+停嘱联动+generated 投递，5 用例）/ `BillingInpatientLinkageIT`（六事件计价终态+重复投递幂等+precheck 聚合，8 用例）。
- ② **生产缺陷修复（IT 实测暴露）**：`BillingInpatientEventListener.handleOrderEvent` 派发前对 created/audited 投递面 eventType 剥离 order_type 子键归一回登记名——未归一时子键帧全部落入 default 分支静默直返、住院离散计价失效；修复随单测两例（子键帧归一派发/非子键族不受影响）。R3-06「登记名不带子键、routing 携子键」的消费侧适配自此闭合。
- ③ **文档收口**：`docs/specs/modules/04-inpatient.md` 追加「§13 P2 PR-1 落地注记」18 条（GC21 五项降级+高危药 BLOOD 单面+出院带药放行即确认+会诊时限未参数化+模板/WS/计费入口直调/口头医嘱催办顺延+CF-6 id 55 定稿含 Task 8 R1 end_at 守卫增补+事件 id 65–73+V1000+ 通用段+日切异常清单简化+计价承载面 created 裁决+子键归一修正+随访 14 日/准备窗口 60 分钟行为变更+前端 OrderCreatePayload 偏差+api.d.ts 键序噪声+W-34 触发就位）；TASK.md W-33 销项删除（V901 UPDATE 已履行+IT 断言）、W-34 更新（触发已就位+退役五项归 PR-3）、W-42~W-47 六行照 P2 计划 §2 裁决 2 原文登记、D-25 新登记（住院 OrderCreateRequest Springdoc schema 同名覆盖待决策）。
- ④ **行为变更注记（Task 10 回接）**：随访缺省时距 7→14 日、临时单次计划默认准备窗口 15→60 分钟（`fuyun.inpatient.*` 参数化，审查确认非回归红线情形，断言适配严格度不降）。
- ⑤ 门禁证据：五 IT 单跑逐条绿 + 后端全量 `mvn verify`（含 IT/JaCoCo 双阈值）+ 前端六连全绿（lint/format/type-check/test 177/build/audit 官方源）+ 真栈探针四项与浏览器六页走查（详见 task-16-report）。

## 2026-09-25 · P2 PR-1 前置：inpatient 号段登记（V900 段）+ V1000+ 通用段四位数先例 + 事件 id 65–73 排定 + JaCoCo 扩名单

- ① inpatient 固定百位段 **V900–V999** 登记（`scripts/check-migration-governance.py` `_SEGMENTS` 增行
  + `docs/migrations/flyway-version-registry.md` 台账同步）：首批 V901–V908（事件登记升级种子/入院两表/
  床位两表/医嘱三表/审核两表/转抄计划两表/出院随访两表/会诊一表，随 PR-1 Task 2–11 逐任务落盘）；
  **V900 已被 patient 通用段借用**（V900__add_patient_name_trgm_gin_index，已应用不可改）——版本唯一
  校验兜底 inpatient 禁用 V900，inpatient 首批 V901 > 基线全局最大 V900，乱序守卫天然通过（零豁免）。
- ② **V500+ 通用段四位数号先例开创**：pharmacy V1000（order_medication/review_task 审方薄切片）与
  billing V1001–V1003（fee_ownership_split / arrears_approval+event_registry id 73 种子 / 住院计价项目
  种子）——pharmacy/billing 固定段内号 ≤V799/V699 小于基线全局最大 V900 必被乱序守卫拦截，故取
  V1000+，同时避开 inpatient 固定段 V900–V999 防未来撞车。
- ③ **事件 id 65–73 排定**（全局递增，先例 V800 id 41–64）：65–72 inpatient 八事件
  （visit.registered / order.created / order.audit-rejected / consultation 五态）落 inpatient V901；
  73 billing.arrears.approved（producer=billing）落 billing V1002；**W-33 义务声明**——V901 迁移内
  UPDATE integration.event_registry id 55 payload_desc 补齐执行回签字段级契约（UPDATE 已有行非改
  DDL，V702 UPDATE V605 先例，合法例外）。
- ④ **JaCoCo 规则二纳入 `com.fuyun.inpatient.service.impl`**（父 POM PACKAGE LINE=1.00）：visit_id
  签发与医嘱状态机属「核心业务状态机」、计费停费联动属「资金关联路径」（2026-09-25 主控裁决）；包
  不存在时规则零包平凡通过，首个 impl 落码即生效，既有八包 LINE=1.00 不回退。

## 2026-09-25 · P1 PR-7 收口

- 交付面：扫描成果回流 dev（PR #51 纯 merge，CI 六 job 绿，新基线 dev@97fc7af）+ 演示预检 W-28 销项
  + 门诊全流程真栈演示与 portal 预约演示留痕（见同日演示条目）+ TASK.md 销项核对留痕（W-4/W-5/W-6/D-8
  已销复核零命中、W-28 删除零残留——行级 `\| W-28 \|` 零命中，裸 grep 命中 TASK.md:50 系 W-27 行内
  历史交叉引用、既有惯例保留不改写；W-7 按用户裁决改期登记：P2 承载，届时 P2 主题不符则开独立 IoT
  数据面专项）+ 本阶段新发现盘点确认（W-30~W-36 七行 TASK.md:51-57、D-22~D-24 三行 TASK.md:15-17
  在案，内容不改）+ 扫描待裁决 4 项按裁决登记 W-37~W-41 五行（含用户另指示 W-41：
  PricingSettleView.vue:200 SELF_PAY 硬编码→UI payerType 参数化，承载 P2 UI 面）+ P1 实施计划 §3
  七 PR 完成项内联标注（merge hash 实取）与 §4 DoD 五条出口核验 + P1 终验小结
  （docs/prompt/2026-09-25-P1终验报告.md）。
- CI 口径：DoD 第 2 条「五 required checks」为 P1 计划撰写时口径，按现行六 job 执行；PR-7 纯文档 PR 的
  CI 证据=回流 PR 六 job 绿 + 本地全量门禁绿组合（终验报告 §1.2）。

## 2026-09-25 · P1 收口：门诊全流程真栈演示（挂号→就诊→收费→发药）与 portal 预约渠道演示

- **前提**：PR #51 扫描成果回流 dev@97fc7af 后重建 fuyun/backend:dev 镜像并起栈，compose 六服务全
  healthy；演示预检 W-28 三步执行（号源对账 Redis/DB 余量比对一致——0 池键+0 池行零漂移=当日未预热
  正常态、fy.delay（delay.appointment-timeout）队列深度 0、purge 未触发），W-28 工单销项。
- **门诊四环节（workstation 端，playwright-cli 真机，截图 .superpowers/gui-test-screenshots/pr7-*）**：
  ① 挂号——DEP-IT-FLOW 当日普通号 WINDOW 渠道，visit_id=O2026092400001（O+日期+5 位流水，CF-3），
  挂号费手工计费+结算 settleNo=S543157389167001；② 分诊报到+叫号——队列状态转已叫；③ 就诊——接诊后
  开检查单（PENDING_FEE）与处方（PENDING_DISPENSE）；④ 收费——就诊费用预结算+结算
  settleNo=S544348037045074（CASH）；发药——pick/verify/issue 三步链发药单号=D20260924013108；
  诊毕——去向确认后 visit finished_at 落库（psql 实证）。
- **portal 预约渠道（决策 5）**：/portal/appointment 免登录证件号预约，出票 apptNo=AP20260924000005
  （AP+日期+6 位流水）+ 支付时限倒计时；衔接断言：appointment 行 RESERVED/PORTAL/15 分钟 pay_deadline、
  池行 used_count +1、Redis pay-hold 占位键在案。
- **DoD 第 5 条抽查**：患者查询/收费票据/发药记录三处 system.audit_log 行各 1 行摘录
  （operator/action_type/resource/result 全 SUCCESS）；脱敏——检索页证件号/手机号掩码截图 +
  backend 日志 grep 演示证件号 0 命中。
- **口径注明**：医保段=7a 实演成功口径（未触发降级）——UI 收费面 payerType 固定 SELF_PAY
  （PricingSettleView.vue:200），本次经 API preview CITY_INS 实演拆分 60/20/20（total 1000 分=统筹
  600+个账 200+自付 200）+ SIM- 回执（SIM-S546062211559206）+ insurance_call_log 2102 SUCCESS；
  医保模拟拆分由 InsuranceSimulatorAdapter 承载、PR-3 IT 覆盖。语音外放属现场外设（PR-5 口径沿袭）；
  bigscreen 叫号页非 DoD 义务面未纳入本次演示。演示偏差（登记不修复）：分诊叫号 API 降级（分诊台诊区
  下拉硬编码三诊区）、主链诊毕 API 降级+第二 visit（O2026092400003）UI 补演（医生站无在诊恢复）、
  portal 诊区常量不含 DEP-IT-FLOW 落 DEPT-INT 池（衔接断言全过）、患者查询 GET 面未挂 @AuditLog
  不产审计行（患者域以 WRITE 建档行在案）。

## 2026-09-24 · N4 修复环 PERF-01：退费链 apply/execute 可退余额聚合 SQL 下推 + 批量预载 + link 批插（性能）

- **根因（PERF-01，性能与算法优化清单定稿，置信 88，吸收 P1-01/P1-04/P3-05/A2-01/A2-02）**：
  `RefundServiceImpl.apply` 逐费用行调 `refundedFen`——每次把全部 PENDING_* 在途退费单整行拉入
  内存仅取 id；`decidedRefundedFen` 每次把全部 APPROVED/EXECUTED 退费单（EXECUTED 终态单调无界
  增长）整行实体拉入内存仅取 id，且 status 无前导索引（V603 仅 idx_refund_settlement）顺序扫描；
  `execute` 每 link 串 selectById + decidedRefundedFen（循环内不变集合重复全量拉取）；
  `apply` 逐行 insert fee_link。N 行明细退费单 → 数倍 N 条查询 + N 次全表实体拉取，资金热路径
  随运营年限量级劣化。
- **修复（行为保持）**：①`RefundRequestMapper` 新增两支聚合下推 SQL（`resources/mapper/
  RefundRequestMapper.xml`，A.4.3-15 连表聚合走 mapper+XML）：refund_fee_link JOIN
  refund_request 按 status 过滤 SUM(refund_amount) GROUP BY fee_id——已决支（APPROVED/
  EXECUTED）与在途支（PENDING_*，含 excludeRefundId 非空时 `r.id != #{excludeRefundId}`
  在途排斥自身语义），计算结果与旧 refundedFen/decidedRefundedFen「全量捞单取 id + 集内
  link 逐费用求和」两步内存路径完全等价（W-17 已决/在途分立口径原样保留）；②apply 同批
  feeId 已决+在途各一次聚合、lockByIds 行锁内装载（TOCTOU 收口语义不变），逐行 insert
  link 改 `Db.saveBatch` 批插（A.4.3-16，先例 DispenseServiceImpl）；③execute 费用行集一次
  selectBatchIds + 已决聚合一次循环外复用，循环内零查询；零 link 零查询与旧空循环语义对齐。
  旧 refundedFen/decidedRefundedFen/sumLinks 由批量形态 refundedFenByFeeIds/
  decidedRefundedFenByFeeIds 替代并删除；totalRefundedFen（单次调用非循环热路径）维持原形态。
  对外契约、状态机语义、DB schema 零变更。
- **测试与验证（行为保持对照）**：既有单测全部随门禁保持绿（超可退守卫/免审直退/部分退全额退/
  分级阈值边界/驳回解锁/卡侧守卫；打桩点从两次全量 selectList 切换至两支聚合，断言面从
  wrapper 谓词切换至聚合调用参数 + XML 文本守卫）；新增金额边界对照用例——多费用行已决+在途
  混合逐行守卫（聚合同批各恰一次）、多费用行第二行超可退整单拒零批插、跨单部分退累计判态
  FULL/PART 各行独立，对照基准=旧逐行两步求和手工等值；在途排斥自身（W-17 单测直驱）切换为
  excludeRefundId 参数下推断言；新增 RefundAggregateSqlGuardTest 逐子句钉死 XML 聚合 SQL
  （状态谓词两支互斥不混入、在途排斥 `<if>` 按需生效、两表 deleted=0、JOIN 主键勾稽、
  GROUP BY + ORDER BY fee_id）。门禁：worktree 根 `mvn -B test -pl fuyun-billing -am`
  全绿（billing 215/0，含 RefundServiceImplTest 46/0）。
- **复杂度改善**：时间——查询次数 O(明细数×全表行数) 顺序扫描 → apply/execute 各常数条索引
  聚合（fee_id IN 过滤 + GROUP BY，走 fee_id 侧索引）+ O(1) 次批插/批读；空间——
  O(全院已决/在途退费单整行实体) 内存驻留 → O(聚合结果行)。

## 2026-09-24 · N4 修复环 ALGO-01：PDA 患者摘要体征取值改单行点查（算法）

- **根因（ALGO-01，性能与算法优化清单定稿，吸收 P1-08/A2-03）**：`PdaServiceImpl.patientSummary`
  取最近一次体征走 `listByPatient(patientId, null, null)`——按患者主索引跨全部住院史无窗口无
  LIMIT 全量拉入内存 + 全量 VO 转换，仅为取最后一条作摘要；长期住院/慢性病患者数千行，PDA
  每次扫码摘要均付出 O(患者终身体征行数) 传输与转换代价（叠加 PERF-02 落地前的缺索引为全表扫）。
- **修复（行为保持对照）**：`IVitalSignService` 新增 `latestByPatient(patientId)` 点查——
  ORDER BY measured_at DESC, id DESC LIMIT 1（MyBatis-Plus `last("LIMIT 1")`，
  IoRecordServiceImpl 同款先例）：与旧「升序清单取末位」同为最近测量时点，id DESC 为同刻 tie
  的确定性 tie-break（旧升序无次键、同刻多行取值依赖 DB 返回顺序，本实现收敛取最新落卡行）；
  patientSummary 改用之，listByPatient 既有调用方零触碰。时间/空间复杂度：O(患者终身体征行数)
  全量拉取+转换 → O(1) 单行回表（配合 PERF-02 idx_vital_sign_patient_time 逆序扫描首行即止）。
- **测试与验证（ALGO 对照协议：先对照断言再优化）**：VitalSignServiceImplTest 先行落对照用例
  并验证 RED（点查未实现红）再改实现转 GREEN——`latestByPatientMatchesAscendingTailSelection-
  WithDeterministicTieBreak`（两行异刻 + 同刻 tie 两行：点查与旧升序末位同测量时点、tie 确定性
  取 id 最大行、SQL 片段钉死 ORDER BY measured_at DESC / id DESC / LIMIT 1 下推 DB）与
  `latestByPatientReturnsNullWhenPatientHasNoRows`（无记录返回 null 边界）；PdaServiceImplTest
  八处 mock 随调用面切换。NursingVitalSignFlowIT 补 step10 真栈对照（单语句三行夹具含同刻
  tie：latestByPatient 与旧 listByPatient 升序末位同测量时点、tie 命中 id 最大行）。门禁：
  worktree 根 `mvn -B test -pl fuyun-nursing -am` 全绿（198/0）+ NursingVitalSignFlowIT 真栈
  单类重放 10/0 全绿。

## 2026-09-24 · N4 修复环 PERF-03：V900 patient.patient 姓名检索 trigram GIN 索引（性能）

- **根因（PERF-03，性能与算法优化清单定稿，置信 82）**：`PatientServiceImpl.search` 对姓名
  关键词生成 `name LIKE '%kw%'`（MP like 前后通配），V100 idx_patient_name 普通 B-tree 无法
  服务前导通配谓词，挂号/建档台最高频检索入口对百万级主档全表扫描 + 全表 COUNT（分页
  total），随档案量增长线性劣化。
- **修复（行为保持）**：新增增量迁移 V900 幂等启用 pg_trgm 扩展并建
  `idx_patient_name_trgm gin (name gin_trgm_ops)`——'%kw%' 谓词由顺序扫描 → trigram 索引
  扫描，时间复杂度 O(全表) → O(索引候选集)；查询语句、结果集与排序零变化，查询代码与既有
  迁移 V100 均未触碰（A.4.1-3 红线）。扩展归属边界说明：A.4.1-5 的 initdb 独占口径约束
  TimescaleDB 扩展（须容器级预载共享库），pg_trgm 为 trusted 扩展经幂等 CREATE EXTENSION
  IF NOT EXISTS 随迁移启用——迁移是唯一能对全环境（compose / IT 容器 / 生产托管库）一致
  保证扩展在位的载体。普通 CREATE INDEX（Flyway 事务内 CONCURRENTLY 不可用，P1 数据量
  锁表窗口可接受，V808 同款取舍）。
- **测试与验证**：search 行为保持由既有 PatientServiceImplTest `searchDispatchesKeywordForms`
  （姓名形态 name LIKE '%张%' 分派与分页语义）与 `blankKeywordShortCircuitsToEmptyPageWithoutDb`
  （空串/空白/null 边界）承载，零改动随门禁回归；EmpiGovernanceIT 补 step8 迁移断言
  （V900 success 落库、pg_trgm 扩展在位且 CREATE EXTENSION IF NOT EXISTS 幂等重放、GIN
  索引 gin_trgm_ops 落位、真栈单字关键词检索返回集与 patientId 降序一致）。门禁：worktree
  根 `mvn -B test -pl fuyun-patient -am` 全绿 + EmpiGovernanceIT 真栈单类重放全绿。
- **号段登记**：docs/migrations/flyway-version-registry.md 同 PR 登记 V900（patient 后续
  迁移走 V500+ 通用段；V800–V899 为 nursing 专属段不得占用，故取全局最大 V808 之后的首个
  合法号 V900；全局最大随登记更新为 V900）。

## 2026-09-24 · N4 修复环 PERF-02：V808 vital_sign_record 患者维度前导索引（性能）

- **根因（PERF-02，性能与算法优化清单定稿，置信 80）**：`VitalSignServiceImpl.listByPatient`
  按 patient_id 过滤（工作站 GET /vital-signs?patientId= 与 PDA 患者摘要每次扫码均调用），
  V803 仅有 (visit_id, measured_at) 与 (ward_id, review_status) 前导索引，患者维度查询只能
  顺序扫描；体征表为全院持续增长的高速写入表（P2 IoT 接入后写入量放大），随运营年限量级劣化。
- **修复（行为保持）**：新增增量迁移 V808 建 `idx_vital_sign_patient_time (patient_id,
  measured_at)` 复合索引——查询计划由顺序扫描 → 索引范围扫描，时间复杂度 O(全表) →
  O(log n + 患者行数)；查询代码零改动即受益，既有迁移 V803 未触碰（A.4.1-3 红线）。
  普通 CREATE INDEX（Flyway 迁移在事务内执行，CONCURRENTLY 不可用于事务块；P1 阶段
  数据量有限锁表窗口可接受，P2 后大表补索引另行评估并发建索引方案）。
- **测试与验证**：listByPatient 行为保持由既有 VitalSignServiceImplTest
  `listByPatientSupportsWindowAndOrdersByMeasuredAt`（patientId 过滤 + 窗口含头不含尾 +
  升序钉死）承载，零改动随门禁回归；NursingVitalSignFlowIT 补 step9 迁移断言
  （pg_indexes 断言索引落位且列序 (patient_id, measured_at)，IotMigrationIT 断言③同款形态）。
  门禁：worktree 根 `mvn -B test -pl fuyun-nursing -am` 全绿。
- **号段登记**：docs/migrations/flyway-version-registry.md 同 PR 登记 V808（nursing 专属段
  V800–V899 续号——全局最大已应用版本 V807 的下一号，满足乱序守卫；全局最大随登记更新为 V808）。

## 2026-09-24 · P1 PR-6 M05 修复环 R2（审计切面标识白名单掩码，安全 Important）

- R2 审查发现：fuyun-system 共享切面 `AuditLogAspect#buildDetail` 对非 Bearer 的 String 参数与
  方法入参 record 直出（仅口令/令牌类打码），PR-6 新增的 PDA 两端点（`GET
  /api/v1/nursing/pda/patient-summary?identifier=` SENSITIVE_QUERY 与 `POST
  /api/v1/nursing/pda/patrol` WRITE）会把证件号/卡号明文写进 `audit_log.detail`（留存 ≥6 个月）
  ——违反「日志禁打印敏感信息」等保红线。
- 处置取增量白名单路径（策略 b；实测无参数级脱敏扩展点，`@AuditLog` 仅 `actionType()`）：
  仅「参数名为 identifier 的 String 入参」与「含 identifier 组件的 record 入参」尾四位掩码
  （`****` + 后四位，与 nursing `identifierTail` 同形态）；全平台影响面实测仅 PdaController 两
  落点（patient 模块 identifierType/identifierValue 等近名参数不命中），白名单外端点 detail
  行为逐字节不变，不改全平台审计语义。掩码逻辑收口切面私有方法
  `maskIdentifierArgIfNeeded`/`maskIdentifierTail`，参数名经编译期 `-parameters` 提供。
- 测试：AuditLogAspectTest 补四锚（identifier String 尾四位掩码精确匹配 / ≤4 位短标识全星回退 /
  白名单外 keyword 参数逐字节原样 / PdaPatrolRequest 形态 record identifier 组件掩码其余组件原样）；
  存量断言零触碰。验证：`mvn -pl fuyun-system -am test` 154/0 全绿，`mvn -pl fuyun-app -am verify`
  BUILD SUCCESS。

## 2026-09-24 · P1 PR-6 M05 修复环 R1（后端四项 Important）

- 五视角审查 R1 后端四项修复：①`com.fuyun.nursing.api` 包补 `package-info.java`
  `@NamedInterface("api")` 声明（逐字对齐 outpatient/patient/system 形态，宪法 B.1 对外契约出口；
  spring-modulith-api 依赖自此有消费点，P2 消费方引用不再被 Modulith 边界拦截）；
  ②巡视打卡日志改 `identifierTail` 尾四位摘要口径（对齐 PdaServiceImpl，扫码标识明文禁入日志）；
  ③patrol `source_ref` 按标识形态分流——I 型腕带就诊编码（`VisitIdValidator` 冻结结构，
  visitId 形态非敏感）原值留痕，证件号/就诊卡号形态落尾四位掩码值（V805 列注释口径 +
  等保「敏感字段脱敏落库」红线）；④评估复评降级（非高危）同事务移除对应床旁风险标识
  （`IWardMetaService#removeRiskFlag` 新增；映射与高危追加同源 `NursingScaleConstants#riskFlagOf`，
  幂等零写兜底）——床旁风险标识权威 = 最新评估判级，防 FALL/PRESSURE 降级后永久残留误导临床。
- 测试：NursingTaskServiceImplTest 补 patrol 留痕三形态分流、WardMetaServiceImplTest 补移除
  （保序回写/清空落空串/幂等零写/NS-1001）、NursingAssessmentServiceImplTest 补升→降全链路
  （高危追加 PRESSURE → 复评 MEDIUM 清标识，防范任务零新增）；冻结用例断言零触碰。
  Spec 同步：05-nursing.md §13 追加第 16 条注记。

## 2026-09-24 · P1 PR-6 M05 护理基础收口

- 交付面：V800–V807 八迁移（CF-6 事件登记 24 行 id 41–64；病区元数据/护理文书/体征/出入量/护理任务/
  评估/交接班七域）+ nursing 模块服务面（`com.fuyun.nursing.service.impl` LINE=1.00，实测
  COVERED=1343/MISSED=0）+ 三条锚点 IT（体征归集链 8 / 文书与交接班链 9 / 患者上下文拦截链 6）+
  workstation 护士站页与 PDA 页 + UI 设计文档（865e535）+ 前端审查修复环 R1（65faa32：S7 重叠红圈
  改坐标同格判定、短绌起止红竖线、PDA 卡号路径判空零出网、交接班摘要「特级/病重」标签归位）。
- 纯新增迁移段 V800–V899（nursing）且 V800>V706 → 免存量卷重置；存量 spec 断言零回退（仅新增
  `WardBoardView.spec.ts` +592 / `PdaView.spec.ts` +208）。
- 合入前全量门禁四步全绿：后端 `-pl fuyun-app -am verify` BUILD SUCCESS（09:51，三锚点 IT、
  `MessagingGovernanceIT` 64 行断言、`ModulithBoundaryTest` 全绿）；BUNDLE LINE 0.9606 ≥ 0.80；
  前端五连 32 文件 150 用例全绿；迁移守卫 51 文件基线 dev 通过。
- Spec 落地注记：`05-nursing.md` 新增 §13「P1 切片落地注记」（FU 界定 / 过渡通道退役三处留痕 /
  五项降级 / CF-6 落点 / 归集量化 / WARD 降级 / Task 12 八项 REST 面适配 / PDA 最小脱敏口径 /
  体温单符号契约类名）；`04-inpatient.md` §7 补 CF-6 id 55 占位行升级义务注记（双侧留痕）。
- TASK.md：W-26、W-29 销项；新登记 W-31（ArchUnit 分层规则缺口）～W-35（「P1」双义词消歧）、
  W-36（任务列表日期过滤与业务号日期段时区口径错位，真机实测发现）、D-22～D-24（计划级张力与
  裁定三项：体征唯一约束 vs 服务器时间、归集并发双行竞态、连续取值域 vs 离散档位）。
- 真栈探针四项通过：迁移计数 8（V800–V807 连续无缺号）/ 事件登记 64 行 / `/v3/api-docs` 200 且含
  `/api/v1/nursing/` 契约面（含 PDA 两端点）/ compose 六服务全 healthy。
- 浏览器真机两页六流程：4 通过 + 2 部分通过（异常体温呈现经核对为体温单 S1 腋温部位符号体系、
  符合设计权威；任务列表时区错位登记 W-36 非阻断），截图存 `.superpowers/gui-test-screenshots/pr6-*`。

## 2026-09-22 · P1 PR-6 M05 护理基础：nursing 号段登记与门禁适配（先记再改）

- **nursing 专属固定百位段 V800–V899 新登记**：M05 为 schema 基线零迁移的新模块，首批迁移占百位段
  V800–V807（患者元数据/护理文书/体征/出入量/护理任务/评估/交接班/事件登记八批）；批次合入后
  nursing 后续迁移一律走 V500+ 通用段（W-12 口径，patient/outpatient 先例）。
  **段位语义（2026-09-22 用户批复条件 1）：V800–V899 为 nursing 专属固定段位、非通用段，仅供 nursing
  模块迁移占用，其他模块不得使用**——防止后人把 V8xx 误读为通用段。
- **免存量卷重置**：首批 V800–V807 高于基线全局最大已应用版本 V706，Flyway outOfOrder=false
  对存量 dev 卷不构成 pending 阻断——本 PR 无需 down -v（与 PR-5 V200<V703 的处置不同）。
- **事件 id 排定**：CF-6 契约冻结载体与 M05 发布面共 24 行，id 41–64（当前最大 40）；status
  一律 ACTIVE；CF-6 冻结行 desc 标注「(CF-6 冻结载体)」，占位行标注「(P1 占位登记，P2 实装)」。
- **JaCoCo 规则二扩名单**：`com.fuyun.nursing.service.impl` 纳入 PACKAGE LINE=1.00（护理文书为
  病历要件、体征落卡状态机与评估判级属核心面）。
- **迁移守卫登记**：`scripts/check-migration-governance.py` `_SEGMENTS` 增 nursing 百位段；
  `docs/migrations/flyway-version-registry.md` 同步登记 V800–V807。

## 2026-09-22 · W-29 后端 PR 开工：门诊契约缝三条补齐 + D-4 schema 坍缩治理

- **范围**：①D-2 `QueueTicketVO` 补 `triageLevel`（数据源 visit.triage_level 权威快照，snapshot 路径零新增
  查询）；②D-3 `ClinicOrderVO` 补 `dispenseStatus`（发药回流镜像纯投影漏带）；③D-9 `TriageAdjustRequest` 补
  `reason`（落既有 triage_record.reason 列，零迁移；LEVEL_ADJUST 服务层必携校验）+ 修复 adjust 留痕错位传参
  （第 8 实参误传 targetQueue → 改传 reason）；④D-4 `@Schema(name=...)` 治 springdoc 同名嵌套 record 注册坍缩
  （`ClinicOrderVO.Item`→`ClinicOrderItem`、`PrescriptionOpenRequest.Item`→`PrescriptionItem`，backend 首例，
  不动类名、不动 Line 侧 6 条载荷）。
- **依据**：调研报告 `.superpowers/w29-recon.md`（需求锚 TASK.md W-29；偏差登记
  docs/specs/modules/03-outpatient.md:226-231，PR 内回填修复状态并订正 :229 归因措辞）。
- **门禁承诺**：后端 `mvn verify`（Spotless + 单测 + JaCoCo 双阈值）与前端五连全绿后方可交付；契约与生成物
  （web/api-docs.json + api.d.ts）同 PR 原子，`DoctorStationView.spec.ts` mock 由 drugId 坍缩形态改回 itemCode
  形态属 D-4 修复本义、非断言放宽。

## 2026-09-22 · D-21 断言现代化：发药签名 confirm 补中文按钮与单号回显 + 根宪法新增「回归红线出口」

- **宪法修订（先记再改）**：根 `AGENTS.md` §7 跨切约定新增「**回归红线出口（测试断言现代化流程）**」条目——既有
  断言冻结的恰为「待改进的实现细节」（而非业务行为）时，禁在功能 PR 内顺手改断言，须走专项断言现代化 PR，四条
  边界：范围（逐次批准、单点单次、不构成泛化先例）/ 严格度（新断言不得低于原断言，须对新契约全量精确匹配）/
  原子性（断言修订+实现变更+回归锚同 PR）/ 留痕（CHANGELOG 本条目）；并载出口边界声明——**出口=既有合规路径，
  硬红线（安全 / 版本 / 编码红线、A.4.1-3 迁移禁改等）不在出口范围**，无合规出口者登记 `TASK.md` 待决策。
  **2026-09-22 用户批复 D-21 选项①并批准
  破例**——判据：本案为实现侧唯一物理路径必触碰断言（ElMessageBox 函数式挂载不继承 ConfigProvider locale，中文
  按钮只能经显式传参），属红线空隙走显式破例；且该断言锁的是文案细节非业务行为，两参→三参为严格度迁移非回归弱化。
- **实现（DispenseWorkbenchView 发药签名 confirm）**：文案单号前置——`发药单 {dispenseNo} 签名后药品出库且不可逆，
  确认发药？`（先单据后动作的防错阅读顺序，§4.4 禁裸确认）；补 `confirmButtonText: '确认发药'` /
  `cancelButtonText: '取消'`（R-3 移交清单三处缺口消尽——PricingSettle/RefundApproval 批次 3 两处 + 本处；质量门
  复核另发现清单外 6 处同类残余，已按台账纪律登记 W-26，不在本 PR 破例范围）。
- **断言现代化（同 PR 原子交付）**：`DispenseWorkbenchView.spec.ts` 冻结断言由两参精确匹配升为**三参全量精确匹配**
  （单号前置新文案 + 中文按钮 options）；回归锚=该断言本体与既有出网序/在途守卫断言（未弱化）。
- **销项**：TASK.md D-21 行删除（闭合即删行）。
- **门禁记录**：前端五连绿（lint 零输出 / format:check 通过 / type-check 三应用 Done / test 30 文件 131 用例全过 /
  build 三应用成功）；**红绿验证**——实现回退至两参版本时新断言必红（`AssertionError: expected … to be called with
  arguments: ['发药单 D1 签名后药品出库且不可逆，确认发药？', …(2)]`），严格度实证后还原。

## 2026-09-22 · W-23/W-24 收尾：V706 prescription_item.status 列注释订正 + 两行销项

- **W-23 订正（V706，pharmacy 域）**：V701 :66 内联注释「returned_quantity/status 由退药链回写」中 status
  半句失实——主代码对 pharmacy.prescription_item.status **零写入点**（W-22⑨ 核实：全量检索 updateById/
  setStatus/注解 SQL/mapper 写路径后，唯一写入为 PrescriptionItemMapper.accumulateReturnedQuantity 的
  returned_quantity 原子累加；发药中明细退场写 pharmacy.dispense_item.item_status，V703:95）；宪法 A.4.1-3
  禁改已应用迁移，故以新迁移 COMMENT ON COLUMN 就地更新权威口径（V606 同款先例），迁移头部自解释列明
  核实结论与承载理由（防后人误信旧注释）。代码侧残留一并收口：mapper javadoc 已随 W-22 fix PR 订正，
  **实体 PrescriptionItem javadoc 两处（类/字段）本次订正**（原句与 V701:66 逐字同源，质量门 F-1 实证）。
- **号段登记**：docs/migrations/flyway-version-registry.md 同 PR 登记 V706（V500+ 通用段续号——全局最大
  V705 的下一号，满足乱序守卫；用户批复硬要求「号段立即登记台账」）。
- **W-24 销项**：DispenseServiceImpl.getByRxNo 取消态排除修复已随 PR-5 Task 11 交付，「行删除待合并后执行」
  触发条件成立——TASK.md W-23/W-24 两行删除（闭合即删行）。
- **门禁记录**：迁移治理守卫 `MIGRATION_BASE_REF=dev` 通过（43 个迁移文件）；后端 `mvn verify` 全 24 模块
  BUILD SUCCESS（Testcontainers 全新库日志实证「Successfully applied 45 migrations … now at version v706」，
  45=43 源文件 + 2 处 PR-1a 期改名遗留 target 构建产物 V6__create_event_publication / V7__create_shedlock，
  守卫脚本 docstring 已注记该产物不在扫描面内）；真栈探针（重建容器对 dev 卷）——flyway_schema_history
  V706 success=t、`col_description` 列注释全文在位。
- **裁决留痕（2026-09-22 用户批复，本 PR 落实 W-23/W-24 两项）**：W-23「批准，立即执行」；W-24「与 W-23
  同 PR 删行」。

## 2026-09-22 · P1 PR-5 M03 门诊主流程收口：outpatient 全链+门诊三前端交付（CF-5 冻结载体实装）

- **交付面**：后端 outpatient 全链——号源池域（V200 三表+V705 三类字典种子：排班模板/放号/停诊/加号）、
  预约挂号域（V201 预约/就诊四表：visit_id 当日键签发+双道闸扣减+支付时限延迟释放+退号四分支+改期链）、
  分诊队列域（V202 两表：报到/二次分诊/调级/跨队列转接+叫号 CAS+过号重排+WS 推送）、医生站域（V203 两表：
  开单/作废/RX_REF 引用登记+接诊/诊毕状态机+开方端口转调）、收费退费联动（手工计费挂号费/
  settlement.completed 放行扇出/refund.approved 终态回滚/fee.created 补账）、V204 事件契约种子
  （id 23/25/31 冻结+id 32–40 登记，event_registry 总行 31→40）、practice/check 真实化（V704
  practice_grant+outpatient/pharmacy 双端接线）、portal 匿名通道（SystemWebConfig 白名单+
  PortalAppointmentController）、WS 自建面（/ws/outpatient 端点+两 topic+帧级鉴权拦截器）、三验收锚点 IT
  （FullFlow/RefundRollback/PoolConcurrency）与 `docs/migrations/flyway-version-registry.md` 建档；
  前端三应用——workstation 门诊三页（挂号收费联动/分诊台/医生站）+存量面全站打磨（Task 16 taste-skill）、
  portal 免登录基座（无 token 注入）+预约出票页、bigscreen /ws/outpatient 叫号页暗色化；**Task 17 存量
  前端基建六批次子 PR（#37–#42 全合并，末端 dev@58e16a5）**——批次 0 设计系统地基/批次 1 布局壳数据化
  菜单/批次 2 患者三页/批次 3 收费三页/批次 4 药房三页/批次 5 bigscreen+portal，经 c0cfb26 合并回主线
  （AppSidebar 冲突按预登记消解：门诊菜单组并入数据驱动 MENU_ITEMS）。
- **门禁记录**：Task 14 全量——后端 `mvn verify` 全 24 模块绿（全反应堆 1369 例零失败；
  outpatient/pharmacy/billing service.impl 三包 JaCoCo 实测 LINE=1.00 不回退；Modulith
  `ApplicationModules.verify()` 过）；前端五连绿（30 文件 129 用例）+ api.d.ts 新鲜度
  `git diff --exit-code` 输出空；门禁期修复 1 提交（1d7746c OutpatientRefundRollbackIT 时段型缺陷——
  effectiveFrom 改取 UTC 当日零点）；Task 17 六批次各自五连绿+22 既有 spec 断言 diff=0+六 checks 绿；
  Task 16 打磨五连绿+spec diff=0 复核。
- **裁决落实（recon 14 条对照）**：0 范围界定/演示终点直线段声明（文档头 not-in-scope+03 Spec 注记⑦）；
  1 号段 V200–V299（practice_grant 改道 V704=偏差②）；2 JaCoCo 核心包 LINE=1.00（outpatient impl 入
  名单）；3 id 23/25/31 冻结（V204 UPDATE+兜底 INSERT 双形态=偏差①）；4 放行链回切（rxNos 载荷携带案，
  Task 11）；5 confirmRefundTerminal 收口（SettlementQueryPort 反查，CF-4 载荷零变更）；6 refund 映射
  M03 自查（refundableLines visit 锚，Task 10 契约缝定案）；7 退号退费统一免审档（OutpatientBillingPort
  DAY_CORRECTION）；8 取药凭证载体 settlementNo（verify 可选 body，Task 11）；9 practice/check 真实化
  （Task 2/9）；10 D-16 三态门禁（Task 2/8）；11 visit_id Redis 当日键（Task 5）；12 WS 自建（镜像
  拦截器=偏差⑥，收敛工单 W-25）；13 portal 免登录白名单（Task 5/13）；14 W-22 前置 fix PR 先行
  （P-0 已合入）。
- **偏差与待批处置结论**：批次 4 主控裁决——发药工作台「发药签名」confirm 两参调用被其 spec :198-201
  `toHaveBeenCalledWith` 锁死文案与元数，R-3 confirmButtonText 第三实参与 §4.4 单号回显任一落地必破
  冻结断言，按「改实现不改断言」红线本调用不动，**新登 TASK.md D-21 待决策项**（选项=专项 PR 修订该
  断言并补两交付 / 维持现状）；R-3 三处已完成两处（批次 3 PricingSettle/RefundApproval 中文按钮文案）；
  D-20 fy.delay quorum TTL 惰性过期待决策项随 PR 描述声明缺口与影响边界；W-19/W-20/D-19 维持现状
  （W-20 已转产品待办）；W-23/W-24 两行不回填（W-24 代码修复已随本 PR 交付、行删除待合并后执行）。

## 2026-09-20 · P1 PR-5 M03 门诊主流程：outpatient 号段初始化登记与门禁适配（先记再改）

- **号段初始化批次**：outpatient 域启用固定百位段 **V200–V299**（recon 裁决 1；段内 V200–V299 全空），
  首批 V200–V204（V200 号源池三表、V201 预约/就诊四表、V202 分诊/队列两表、V203 申请单两表、
  V204 门诊事件契约种子）。outpatient schema 基线零迁移，`scripts/check-migration-governance.py`
  乱序守卫「号段初始化豁免」（:152-154）放行首批；批次合入后 outpatient 后续迁移一律走 V500+
  通用段（TASK.md W-12 全局规则恢复约束）。
- **存量 dev 卷一次性重置**（进入条件，Task 13 api-docs 导出前执行——导出要求 backend 在含 V200–V204
  的新卷上启动，旧卷 Flyway outOfOrder=false 必拒 pending 迁移；Task 15 真栈探针复用该重置后卷）：
  首批 V200–V204 低于基线全局最大已应用版本 V703，Flyway outOfOrder=false 对存量卷拒绝应用
  （守卫脚本 docstring :7-11 与 PR-1a 真栈实证）；处置=`docker compose -f deploy/docker-compose.yml
  --env-file deploy/.env down -v && up -d` 全新卷按版本升序一次应用（本条目即登记载体；
  Testcontainers IT 每次全新库不受影响）。**团队广播警示（待批 3 执行条件）**：重置=存量 dev 库
  一次性清空重建（down -v 清卷），执行前须在团队渠道广播警示——「存量 dev 库将一次性清空重建，
  未入库数据先行导出」；广播记录随执行台账归档。
- **practice_grant 改道 system 通用段 V704**（recon 裁决 9 原拟 V608 经守卫算术改道，偏差②）：
  system schema 基线非零迁移（V300–V303/V607），新迁移必须 > V703——V608 必被乱序守卫拦截；
  V704∈(500,None) 合法（V607 先例）。**V705**=门诊三类字典种子（appt-type/visit-type/disposition，
  03 Spec §8「引用 M01 字典 code 不自建副本」）。
- **CF-5/CF-3 事件 id 排定（全局递增按迁移执行序）**：id 23/25/31 载荷 desc 经 outpatient V204
  冻结（「UPDATE 存量行 + WHERE NOT EXISTS 兜底 INSERT」双语句形态——V204 应用序先于 V605/V702，
  纯 UPDATE 在新库 no-op 后会被 V605/V702 以占位 desc 首插，双形态保两序同终态，偏差①；CF-5
  双向评审声明随 PR）；新增 id 32 outpatient.visit.registered / 33 visit.finished / 34
  visit.cancelled / 35 visit.no-show（仅登记无发布点，id 27 先例）/ 36 appointment.booked /
  37 appointment.cancelled / 38 appointment.rescheduled / 39 appointment.timeout（延迟队列回调
  内部事件，自产自消）/ 40 schedule.stopped；`outpatient.queue.called` 不登记（纯 WS 通道）；
  `system.practice.changed` 已随 V5 id 6 登记（发布接线随 Task 2，零新登记）；
  MessagingGovernanceIT 总行断言 31→40 与 V204 同任务落改（PR-3「种子+断言同任务」Task 17 先例）。
- **JaCoCo 核心包扩名单**：父 POM 规则二增 `com.fuyun.outpatient.service.impl`（号源权威库存扣减/
  visit 主状态机/退号退费联动直接驱动资金联动=「核心业务状态机」LINE=1.00，recon 裁决 2，
  2026-09-19 主控裁决；包不存在时零包平凡通过，首个 impl 落码即生效）。
- **W-22 前置**：PR-4 九条合规遗留 fix PR 已先行合入（裁决 14，TASK.md W-22 行由其回填删除），
  本 PR 新增页面/DTO 自带合规形态（loading+在途守卫+零出网用例；入参显式格式校验 4xx）。

## 2026-09-20 · P1 PR-5 批复落档：11 项待批/10 项偏差全部批准认可，6 项执行条件融入任务步骤

- **批复记录**：用户逐项批复——待批 1–11 全部批准、偏差①–⑩全部认可；其中待批 3/4/5/6 与
  偏差⑤/⑨ 附执行条件，两点补充约束（偏差⑦ 声明态边界、偏差⑩ W-20 产品待办登记）与
  Task 16/17 回归护栏同批给出；计划自此具备 SDD 执行条件（进入条件 P-0=W-22 fix PR 先行）。
- **执行条件融入**（`docs/superpowers/plans/2026-09-20-p1-pr5-m03-outpatient.md`）：
  待批 3→Task 1 团队广播警示语 + Task 13 重置三点闭环与 Flyway 版本占用登记表建档；
  待批 4→Task 3 幂等键先实测 + 新旧库双时序迁移自检；待批 5→Task 5 超时幂等守卫 +
  casRelease version 断言 + 两新用例；待批 6→Task 7 镜像文件头三要素 + 只复制不顺手重构
  + 技术债工单登记；偏差⑤→Task 11 删除前置全局检索 + PR 描述删除清单；偏差⑨→Task 7
  优先级公式用户裁决版（类别分取最高单项/老幼残跨类叠加/封顶 999/同分按建行时间序）+ 三新用例。
- **补充约束与护栏**：偏差⑦ 声明态边界注记落 Task 5/8（仅注册迁移对+javadoc，不写无触发逻辑）；
  W-20 转产品待办（Task 1 Step 2b，附 0 元挂号/0 元组合结算两问）+ 结算报错可读红线（Task 8/10）；
  Task 17 改**六批次子 PR 形态**（批次 0 前置 Task 13，批次 1–5 串行，独立分支
  `feat/p1-pr5-ui-batch-0..5`+独立 PR 可独立 revert，合入硬门槛=五连门禁绿+22 spec 断言
  diff=0）、Task 16 同门槛注记；Handoff 批复记录段/进入条件/自审第 7 段落档。

## 2026-09-20 · P1 PR-5 计划二次增补：存量前端基建全面优化（用户增补裁决）

- **增补依据**：用户 2026-09-20 第二次增补裁决——前端优化范围**不限于新五页**，对已有
  前端基建全面优化（ui-ux-pro-max 设计方法论 + taste-skill 打磨同律适用，宪法 C.7）。
- **设计方案扩展**：`docs/plans/2026-09-20-p1-pr5-m03-outpatient-ui-design.md` 追加 §9
  「存量前端全面优化方案」（+443 行，总 1198 行）——存量 14 SFC 现状审计（styles 目录
  实为空壳、样式 100% SFC 内联；EP 无 zh-cn locale；ElMessageBox 按需样式缺失三页；
  侧栏详情路由高亮缺失等）、`--fuy-*` 设计系统迁移策略、布局壳升级（菜单数据化/折叠
  瞬切零动画）、9 业务页逐页改造规格、交互动效统一化、性能治理、六批次实施分期
  （0 全局地基→1 布局壳→2 患者→3 收费→4 药房→5 bigscreen/portal）、落地自查清单。
- **计划增补**（16→**17 任务**，+48/-10）：新增 **Task 17「存量前端基建全面优化」**
  （ui-ux-pro-max 强制加载；按 §9.8 六批次执行；**回归红线=22 个既有 spec 用例断言
  零改动**——审计实证全锁业务行为；W-22⑥⑦ 同文件交叠按 §9.8.3 协调）；Task 16
  打磨范围扩为**全站**（新 5 页+存量面）；执行序 13→17→14→16→15；Handoff 待批项
  增**第 11 条**。

## 2026-09-20 · 宪法修订：web 宪法 C.7「UI 设计思想·谋建琢三段律」与根宪法 §9 问题处理（用户直接修订正文，本条补录）

- **web/AGENTS.md 新增 C.7**：凡涉 UI 工作必循「谋→建→琢」三段闭环（顺序不可逆）——
  谋局定策（@ui-ux-pro-max：布局/样式/交互/动画四维通盘推敲、方案成形方可动工）→
  依图营造（忠实实现既定方案，粗成品严禁交付）→ 琢玉成器（@taste-skill 逐层打磨，
  四维验收=高级视觉/高级交互/流畅动画/高性能渲染缺一不可）；**协作纪律**=凡派遣
  subagent 必须在指令中明确要求加载 @ui-ux-pro-max 与 @taste-skill 方可开工。
  落款依据=用户 2026-09-20 UI 增补指令的宪法化固化（PR-5 计划 Task 16 与
  docs/plans/2026-09-20-p1-pr5-m03-outpatient-ui-design.md 为首个适用实例）。
- **AGENTS.md 新增 §9 问题处理**：zcode 派发 subagent 遇 `Idle-time tasks do not
  support background agents` 报错时，去掉后台标记改前台子智能体重发（PR-3 会话
  2026-09-18 用户写入，随本条一并入库）。
- **格式重排**：两宪法既有表格 prettier 风格对齐（根 §2 运行形态/§5 索引、web B.1
  目录边界/单 app 职责/C.2 技术栈）——内容零变化。

## 2026-09-20 · P1 PR-5 计划增补：UI 设计系统与 taste-skill 深度打磨任务（用户增补指令）

- **增补依据**：用户 2026-09-20 增补指令——用 ui-ux-pro-max 插件对 PR-5 前端整体布局/
  样式/交互/动画产出设计方案并应用；落地后用 taste-skill 插件对组件/样式/交互/动画
  全面深度打磨；两插件均为执行 subagent 开工强制加载项。
- **设计方案入库**：`docs/plans/2026-09-20-p1-pr5-m03-outpatient-ui-design.md`（755 行，
  PR-5 计划伴随规范）——设计 token 系统（`--fuy-*` 命名空间、临床蓝 #0369A1 经
  `:root:root` 双写覆盖 `--el-*` 梯度、白字对比 5.93:1）、五页布局骨架（workstation
  挂号收费联动/分诊台/医生站三栏体系+portal 480px 移动优先+bigscreen 暗色三段
  grid 与 rem 缩放零媒体查询）、组件定制样式、交互三态、动画编排（仅 transform/
  opacity 合成层、FLIP 用 Vue TransitionGroup 零依赖、prefers-reduced-motion 全局
  兜底）、性能红线与落地自查清单。
- **计划增补**（+67/-6，15→16 任务）：Global Constraints 增「UI 设计系统红线」（设计
  文档为前端视觉唯一权威）；前置项增 P-10；Task 13 增 token 落位与设计对照步骤；
  新增 Task 16「UI 深度打磨」（taste-skill 强制加载：design-taste-frontend/
  high-end-visual-design/minimalist-ui/redesign-existing-projects；执行序 13→14→16→15，
  打磨完成后进收口）；Handoff 待批项增第 10 条、SDD 派发强制加载设计技能声明。

## 2026-09-20 · P1 PR-5 计划定稿：M03 门诊主流程实施计划（docs-only）

- **计划本体**：`docs/superpowers/plans/2026-09-20-p1-pr5-m03-outpatient.md`（1865 行/15 任务/
  前置项 P-0~P-9；撰写链=两路调研（Spec 语义+dev 现状）→主控 14 条裁决固化
  `.superpowers/pr5-recon.md`→初稿 1708 行→R1 FAIL（P1×5：PortImpl 单测缺口/演示医师
  身份链断裂/存量卷重置时点/处方引用行 CHARGED 缺失/叫号队列重启恢复）→修复收敛
  （P1×5+P2×6）→R2 窄域复审「收敛可交付」）。
- **范围**：FU-M03-01~08 全 P0+practice/check 真实化（practice_grant V704+管理端点+种子）
  +D-16 unmask 三态门禁接入+PR-4 三占位事件回切（id 23/25/31 载荷冻结经 V204
  UPDATE+兜底 INSERT 双形态）+新事件 id 32–40 登记（总行 31→40）+前端三应用
  （workstation 三页/portal 免登录预约页/bigscreen 候诊叫号页）。
- **关键裁决**（recon 14 条，详 `.superpowers/pr5-recon.md`）：号段 outpatient V200–V204
  （初始化豁免+存量 dev 卷一次性重置，重置点前移 Task 13 api-docs 导出前）；practice_grant
  改道 system 通用段 V704（V608<V703 被乱序守卫拦截）；门诊字典种子 V705（19 条）；
  confirmRefundTerminal 误伤面走 billing api 端口单据化收口（CF-4 载荷零变更）；
  退号退费统一 M13 免审档；取药凭证载体=settlementNo；W-22 fix PR 为 SDD 进入条件（P-0）。
- **待批 9 条+偏差①–⑩**：见计划 Execution Handoff；批复后落档（PR-4 批复记录同款）。

## 2026-09-18 · P1 PR-4 M06 药事基础收口：药品字典+门诊发药闭环交付（CF-5 冻结载体）

- **交付面**：给药途径/用药频次字典预置（V607 两类 PUBLISHED 各 v1 共 25 条，前置项 P-3 改判载体）、
  药品字典（V700+对照/检索/changed 广播/未对照标记）、处方域（V701+开方/作废/
  billing PrescriptionFeePort 同事务联动）、发药闭环（V703+charged 放行/三段调剂/退药受理/
  refund.approved 终态收敛）、CF-5 事件 id 24–31 登记（V702）、billing 占用回写接线（零迁移，
  订阅经治理构件副作用回填）、W-16/17/18 退费守卫收口、前端药房工作站三页。
- **门禁记录**：后端 `mvn verify` 全模块绿（pharmacy impl LINE=1.00 生效）；前端五连绿；
  双验收锚点 IT（PharmacyPrescriptionFlowIT/PharmacyDispenseGuardIT）真栈绿；真栈探针
  （V700 系迁移 success/event_registry=31/q.pharmacy.* 七队列/api.d.ts 新鲜度）全绿。
- **裁决落实**：号段 V700–V799、事件 id 全局递增排定、stub 边界（无生产发布器，IT 注入）、
  W-16/17/18 本 PR 承接收口（TASK.md 回填删除）；偏差与评估结论见计划
  `docs/superpowers/plans/2026-09-18-p1-pr4-m06-pharmacy.md` Execution Handoff 偏差清单。

## 2026-09-18 · P1 PR-4 M06 药事基础：pharmacy 号段登记与门禁适配（先记再改）

- **号段登记**：pharmacy 域占用固定百位段 **V700–V799**（宪法 A.4.1-2「每模块固定百位段」；
  既分配对 integration V1–99 / patient V100–V199 / outpatient V200–V299 / system V300–V399 /
  iot V400–V499 / billing V600–V699，V700 段未占用）；首批 V700–V703（V700 药品字典、
  V701 处方两表、V702 CF-5 事件契约种子 id 24 载荷冻结 UPDATE + id 25–31 登记、V703 发药/批次
  四表）。V700 > 基线全局已应用最大版本 V606，存量 dev 卷与新库同按序应用，乱序守卫双保险；
  `scripts/check-migration-governance.py` `_SEGMENTS` 同步增
  `"pharmacy": ((700, 799), (500, None))`。**V605（billing）文件禁改**——id 24 载荷冻结经 V702
  对 integration.event_registry 数据行 UPDATE 承载（数据契约演进非 DDL 变更）。
- **CF-5 事件 id 排定**（全局递增按迁移执行序）：id 25 outpatient.order.charged（占位，producer=
  outpatient，生产发布方随 PR-5）、id 26 pharmacy.prescription.cancelled、id 27
  pharmacy.prescription.rejected（P3 审方引擎接入前无发布点）、id 28 pharmacy.dispense.completed、
  id 29 pharmacy.dispense.returned、id 30 pharmacy.drug.changed、id 31 outpatient.order.cancelled
  （占位，终态确认 PR-5 回切）；MessagingGovernanceIT 总行断言 24→31 与 V702 同任务落改（PR-3
  「种子+断言同任务」Task 17 先例）。
- **JaCoCo 核心包扩名单**：父 POM 规则二增 `com.fuyun.pharmacy.service.impl`（发药/退药状态机
  直接驱动计费占用回写与退费收敛=资金链路延伸，命中全局规范「核心业务状态机」LINE=1.00，
  2026-09-18 主控裁决；包不存在时零包平凡通过，首个 impl 落码即生效）。
- **M01 给药途径/用药频次字典预置改判**（PR-4 前置项 P-3；用户 2026-09-19 追加裁决「PR-4 顺手预置」，
  推翻 2026-09-18「V304 被乱序守卫阻断→随 M01 交付」结论）：改用 system 段通用号 V607（607>基线全局
  最大版本 V606，号段 (500,None) 合法、执行序先于 pharmacy V700–V703）预置两类 PUBLISHED 字典各 v1
  （medication.route 15 条/medication.frequency 10 条），迁移全文随 Task 2 Step 1b 落文件；校验分工=
  给药途径主校验维持 drug.route_codes 院内途径集（PH-1015 不变），字典供前端下拉与 M01 管理面维护，
  频次维持非空校验（条目级消费随 P3 审方引擎，W-8 同款前置注记）；`system.dict.published` 订阅
  在 PR-4 承载版本水位缓存刷新（部署期种子不发该事件，M01 管理面后续变更经广播刷新——语义顺承）。
- **billing 占用回写零迁移结论**（前置项 P-6）：billing 侧零行变更不落迁移、不占版本号（V607 号位
  由 system 段字典种子 V607 使用；pharmacy
  dispense 两事件行由 V702 登记 id 28/29；billing 订阅经治理构件 declareConsumerQueue 副作用
  registerSubscriber 运行期自动回填 subscriber_modules，PR-3 BillingSettlementFlowIT 实证先例）。

## 2026-09-18 · P1 PR-3 评审修复轮：退费二级审批实装（D，含 V606）+ 调价定时生效（E）登记（用户裁决=本 PR 内完整实现）

- **D 退费二级审批实装**（Spec FU-M13-03 P0，`docs/specs/modules/13-billing.md:136` 分级口径）：分级判定
  集中 `RefundServiceImpl.resolveApprovalLevel`——L0 免审（当日更正+无执行占用+金额≤autoExemptFen，现逻辑
  保留）、L1 一级（跨日/部分退/超免审但≤singleApprovalFen 且自费）、L2 二级（金额严格大于
  singleApprovalFen，恰等于归一级；或 payerType≠SELF_PAY 医保已结算，等价 refundType=SETTLED_REFUND，
  原判定解耦复用）；「票据已开具」维度依赖 FU-M13-06（明确不在本 PR），分级处留显式
  `TODO(FU-M13-06): 票据已开具 → 二级` 占位，测试声明该维度缺省不触发。
- **状态机与审批链**：`RefundStatus` 增 `PENDING_SECOND_APPROVAL`（一级已批、待二级）；`approve` 两段式
  复用现端点——PENDING_APPROVAL 批：L2 → PENDING_SECOND_APPROVAL + 落 first_approver/first_approved_at
  且不发事件，L1 → APPROVED + 发事件（现行为）；PENDING_SECOND_APPROVAL 批 → APPROVED + 发
  `billing.refund.approved`（载荷不变，CF-4 冻结）。连批守卫 BILL-1020 语义扩展：二级批人≠一级批人
  （同一账号不得连批两级），申请人自审守卫不变。reject 两个待审态均可驳（置 REJECTED+理由留痕）。
- **V606 迁移**（billing 固定段 V600–V699，V603 禁改）：`refund_request` 增审批链引用列
  `first_approver VARCHAR(32)` / `first_approved_at TIMESTAMPTZ`，`status` 列宽 VARCHAR(16)→VARCHAR(32)
  （新值 23 字符超原宽；PG 加长变宽为元数据级变更，存量数据保全）。本条目先记，迁移正文随后落盘。
- **零契约改动**：RefundVO 不加字段（前端靠 status 值区分待一级/待二级）、REST 无新端点、事件零改、
  api.d.ts 零重生成（生成态 status 为 string 宽容）；收费组长/财务/医保办角色硬校验随 PR-5 RBAC
  接线，本 PR 以「级别 × 双人链」近似并在代码注释显式声明。
- **E 调价定时生效**（工作包 2，commit ee3bede，采纳其报告建议条目文本）：snapshot 取价改按生效区间
  判定（status <> DRAFT AND effective_from <= now AND (effective_to IS NULL OR effective_to > now)，
  effective_from DESC 取首行；now＝装配期注入 Clock.systemUTC()，不注册全局 Clock Bean 以免与条件装配
  iotAmqpClock 类型注入歧义），到点由取价侧自然切换，不引入调度器/延迟队列；publish 补区间倒挂守卫
  BILL-1004（新起点早于当前未闭行起点拒发布，兑现并删除计划 TODO(P1-后段)）；零契约/迁移/事件改动。
- **B 支付行校验收口**已随上方「工作包 1」条目登记（commit 02035ea 支付行正数硬校验+退费读回卡引用
  守卫、2219cd7 前端执行按钮防抖），本条不重复展开，仅登记衔接关系。
- **测试与门禁**：后端单测新增 8 例（免审边界=autoExemptFen、超免审未超上限自费一级即 APPROVED、
  =singleApprovalFen 归一级、>singleApprovalFen 升二级且一级批零事件+审批链落库、医保 payerType
  直判二级、二级批→APPROVED+事件、连批守卫 403 BILL-1020、二级态驳回），申请人自审守卫回归复用既有
  用例；IT 扩二级场景（三账号：申请→一级批→连批拒→二级批→执行全链）；billing `mvn verify`（JaCoCo
  核心包 100%）与 `BillingSettlementFlowIT` 真栈 7/7、前端五连全绿。

## 2026-09-18 · P1 PR-3：/code-review 复核缺口修复工作包 1（用户裁决=本 PR 内完整修复）

- **B1/B3 请求侧硬校验**：`PaymentLine.amount` 与 `RefundLine.refundQuantity` 增 `@Positive`（0/负值
  400 拒）。修复前负数卡行可使 Σamount 勾稽假平、但 `cardPayFen` 合计不 >0 跳过扣卡，payment_details
  仍无条件落库 → 退费 execute 读回后全额入卡（凭空入卡）。
- **B2 读回侧守卫**：`RefundServiceImpl.execute` 读 payment_details 的 CARD_BALANCE 行时，channelRef
  缺失/JSON null/空文本/非数字 → BILL-1012（400）显式拒（新增私有 `parseCardAccountId`，与写入侧
  `SettlementServiceImpl.parseCardAccountId` 对称形态）。修复前 NullNode.asText() 返字面量 `"null"`
  致裸 `Long.parseLong` 抛 NumberFormatException 经兜底渲染成 500，出 BILL-* 契约外形态。
- **B4 前端防抖**：RefundApprovalView 执行/驳回按钮补 `:loading` + `:disabled` 组合与 handler 入口
  在途守卫（驳回含弹窗未决窗口）。修复前请求在途按钮仍可点，双击发双 POST（并发双退触发面）。
- **测试**：后端新增 4 用例（支付行 0/负值 400、refundQuantity 0/负值 400、execute 四类非法卡引用
  BILL-1012 且零资金动作）；前端新增 2 用例（execute 在途二次点击零出网、驳回弹窗未决抑制二次弹窗）。
- **范围界定**：execute 并发幂等（TASK.md W-16）与混付分摊/二级审批/定时生效不在本包，另行派发。

## 2026-09-18 · P1 PR-3：结算/退费并发缺口收口（终审 parked 三项，用户裁决=结算锚点幂等重构·本 PR 内修复）

- **修法落地**（条件更新/CAS 族，最小侵入根除读-校验-写 TOCTOU）：①settle 状态迁移改
  `SettlementMapper.casMarkSettled` 条件更新抢锚（DRAFT/PRESETTLED 谓词，输家重读分流幂等直返/
  BILL-1015），费用迁移改 `FeeRecordMapper.casMarkFeesSettled` 条件更新（PENDING 谓词+行数全量断言，
  不足抛 BILL-1016 同事务整体回滚），动卡入账严格后置于锚抢占成功；②apply 目标费用行集
  `FeeRecordMapper.lockByIds`（SELECT FOR UPDATE + id 升序锁序）串行化并发申请，锁内重读聚合做
  超可退守卫；③execute CARD_BALANCE 行按 channelRef 聚合、每卡单次全额贷记（与写入侧求和扣款
  口径对称）。不动迁移文件（零加列）、不动 REST 契约与 api.d.ts。
- **preview 双单口径**：不加同 visit 在途单守卫（最小侵入），双 DRAFT 单并发由 settle 费用行条件
  更新兜底（第二单 BILL-1016 拒，属可接受语义）。
- **测试**：单测新增 CAS 三分支/行锁顺序/同卡聚合/mapper 注解 SQL 守卫 8 用例（billing 173 绿）；
  新增 `BillingConcurrencyGuardIT` 三场景×3 轮真栈并发 IT（同单双 settle 恰一赢一幂等+PAY 台账
  恰一行/并发双 apply 恰一 201 一 409 BILL-1021/同卡拆分两行 execute REFUND 台账恰一行）。

## 2026-09-17 · P1 PR-3 M13 收费物价与医保基线收口（CF-4 冻结载体交付 + 前置项全清，先记再改）

- CF-4 六事件登记（V605 id 17–22）并发布/消费可用；CF-5 占位订阅两行（id 23–24）登记，
  上游 PR-4/5 冻结载荷后零改动接通；billing.charge.guaranteed/arrears.approved 随上游顺延注记。
- 前置项收口：minio 宿主端口 9003/9004；D-13 一卡通状态操作条件更新收口（stale balance 根除）；
  D-15 非法 ISO→400 PAT-1023；D-18 springdoc int64→string 生成契约根治（与 Jackson 运行时同源）；
  两轮终审 deferred minors 模板基类提炼（DomainEventSender/IdempotentConsumerSupport/
  FuyunStackITBase）与 matchedRules JSON 口径/updateRule @Pattern/快照断言收口；W-13 勘误回填。
- billing V600–V605（12 表 + 种子）；fuyun-app 装配入图，种子总量断言 16→24；双验收锚点 IT
  （结算闭环/快照不漂移）与收费员工作站三页真机通过；全量门禁双栈绿。

## 2026-09-17 · P1 PR-3：openapi int64 生成契约根治为 string（D-18 裁决方向②，机制修订·先记再改）

- **问题**：openapi-typescript 7.13.0 按 `format:int64` 生成 number，与后端 Jackson 全局
  Long→String 运行时输出及 web 宪法 A.3-6「Long 一律 string 承载」红线漂移（D-18）。
- **裁决落地**：fuyun-app 新增 `OpenApiSchemaConfig` 注册 springdoc ModelConverter，
  int64 数值 schema 一律覆写为 type=string（无 format）——契约与运行时单口径，前端
  `String()` 兜底不再是正确性依赖；PageResult.total 手写声明（string）与生成物自此同源。
- **影响**：api.d.ts 全量重生成（int64 字段 number→string），前端五连复验；真实医保/HRP 等
  外部对接不消费本生成物，无外部契约影响。

## 2026-09-17 · P1 PR-3 M13 收费物价与医保基线：billing 号段登记与门禁适配（先记再改）

- **号段登记**：billing 域占用固定百位段 **V600–V699**（宪法 A.4.1-2「每模块固定百位段」；
  既分配对 integration V1–99 / patient V100–199 / outpatient V200–299 / system V300–399 / iot V400–499，
  V600 段未占用）；首批 V600–V605（V600 项目/价格/组合、V601 医保对照/计价规则、V602 fee_record、
  V603 结算/退费三表、V604 押金两表+insurance_call_log、V605 CF-4 六事件 + CF-5 占位两事件种子 id 17–24）。
  选段依据另含「真库已应用最大版本 V503，V600 段对存量 dev 卷与新库同为顺序应用，
  免 out-of-order 承接路径」；`scripts/check-migration-governance.py` `_SEGMENTS` 同步增
  `"billing": ((600, 699), (500, None))`。patient 后续迁移一律 V500+（W-12）红线不受影响。
- **JaCoCo 核验**：父 POM 规则二核心包名单已预置 `com.fuyun.billing.service.impl`（P0 预置注释
  「billing 随模块实装生效」），PR-3 零门禁修订，首个 impl 落码即 100% 行覆盖生效。
- **事件三段名核验**：QueueGovernorImpl EVENT_TYPE_PATTERN（≥3 段）逐一过验，CF-4 六事件字面量
  `billing.fee.created` 等本身即 `<模块>.<实体>.<动作>` 三段合规，无 patient 式二段塌缩，落码零校正。
- **minio 宿主端口裁决落地预告**：deploy compose 宿主映射改 9003(API)/9004(console 预留)，
  避开本机 mindsoar-minio 占用 9000/9001（2026-09-17 用户决策，执行见 PR-3 计划 Task 2，
  根 AGENTS.md §2 端口表同步）。

## 2026-09-17 · P1 PR-3：用户批准计划并裁决 D-14/D-16/D-17 按默认建议（裁决登记·先记再改）

- **计划批准**：PR-3 M13 收费物价与医保基线实施计划（`docs/superpowers/plans/2026-09-17-p1-pr3-m13-billing.md`，
  20 任务，经 PR #24 合入 dev@69fe0fc）获用户批准，SDD 执行自 Task 1 起（前置项 Task 1–6 先行）。
- **三项用户裁决（均确认采纳计划默认建议，计划 Task 20 Step 2「禁代裁」前提就此闭合）**：
  D-14 就诊卡 LOST 找回=保留人工路径（窗口解绑/补卡办理，零代码，M02 Spec §7 补办理说明）；
  D-16 unmask=保留角色豁免单门禁，三态硬门禁归属 M03（PR-5 注册诊疗关系后接入，Spec §7 注记）；
  D-17 同证件异名建档=Spec 改写「证件冲突直接拒建（PAT-1002）」并移除建档期 SUSPECT 强冲突承诺。
- **落点**：三项 Spec 注记与 TASK.md 行删除统一在计划 Task 20 Step 2 执行（实施前 TASK.md 三行已标
  「已裁决」状态保留）；计划文档前置项表 P-3/P-5/P-6 与 Execution Handoff 同步登记裁决口径。

## 2026-09-17 · P1 PR-2 Task 18 全量门禁暴露的装配完整性缺口修复（先记再改）

- **问题一（上下文启动前置缺键）**：Task 14 将 `PatientConfig` @Import 接入 fuyun-app 后，
  `PatientCryptoProperties`（prefix=`fuyun.patient.crypto`，@NotBlank fail-fast）成为 fuyun-app 全部
  app 层 Spring 上下文启动前置；test profile 无该组键 → 绑定失败 → context refresh 取消 → 同 JVM 内
  后续 IT 级联失败（仅自带 @DynamicPropertySource 的 EmpiGovernanceIT 独活）。
- **修复一**：`fuyun-app/src/main/resources/application-test.yml` 追加 `fuyun.patient.crypto.data-key` /
  `mac-key` 兜底合成值（随机 64 位 hex，仅供测试上下文启动，与 EmpiGovernanceIT 常量互异，
  **非生产密钥、不触碰「禁提交真实凭据」红线**；@DynamicPropertySource 优先级更高可继续覆写）。
- **问题二（冻结总量口径漂移）**：V105 患者域八事件种子（id 9–16）随 Task 14 装配进入 fuyun-app IT 库，
  `event_registry` 总量 8→16，`MessagingGovernanceIT.seedRegistryRowsAreFrozenAndActive` 旧总量断言失效。
- **修复二**：该断言总量口径更新为 16（V5 七条 + V403 一条 + V105 八条，注释同步）；属本次改动导致的
  旧测试失效，按全局规范 §四 同步更新而非删除或跳过。

## 2026-09-17 · P1 PR-2 Task 17 配套：openapi 契约生成物 Prettier 排除清单（机制修订）

- **问题**：Task 16（2671324）gen:api 首跑后，生成物 `web/packages/shared/src/api.d.ts`（openapi-typescript
  输出 4 空格缩进）与本地生成中间产物 `web/api-docs.json`（已 gitignore）被 `pnpm format:check` 判格式偏差，
  web 五连门禁 format 环节自 Task 16 合入后不可过（属门禁配套缺口，非 Task 17 引入）。
- **裁决**：`web/.prettierignore` 增补两条排除，而非以 Prettier 重排生成物入库——生成物格式以生成端输出为准，
  一旦 Prettier 版式入库，下次 `pnpm gen:api` 重新生成必然产生 diff，反而击穿 web C.5-3
  「生成物新鲜度校验（重新生成 diff 为空才可合入）」门禁。

## 2026-09-16 · P1 PR-2：openapi 类型契约生成链路首跑（T-R4-3/T-R4-4 兑现）

- **Springdoc 首次引入**：fuyun-app 增 springdoc-openapi-starter-webmvc-ui 2.8.17（显式锁版，禁升 3.x）。
- **T-R4-3 实证结论**：绿：Springdoc 2.8.17 产出与 openapi-typescript 7.13.0 端到端兼容，生成物
  packages/shared/src/api.d.ts 入库，workstation type-check 全绿。
- **T-R4-4 实证结论**：Element Plus 2.14.5 + dayjs 1.11.23 显式依赖已满足最小集，回填删除。
- **遗留登记（PR 描述同步）**：CI 新鲜度自动校验（重新生成 diff 为空）需 backend job 产出 api-docs
  artifact → frontend job 消费的跨 job 通道，随 CI 演进接线；本 PR 以收口任务本地重生成核对兜底。

## 2026-09-17 · P1 PR-2 Task 15 验收 IT 暴露两处真栈缺陷修复（先记再改）

- **缺陷一（装配遗漏）**：Task 15（EmpiGovernanceIT 端到端验收）真栈首跑实证 `POST /api/v1/patient/patients`
  404（No static resource）——`PatientWebConfig` 的 `@Import` 清单漏登记 `PatientController.class`
  （其余六个 patient 控制器均已装配），建档/详情/更新/检索/冻结/解冻七端点全部未进 MVC 映射；
  单测（MockMvc standalone 直连 controller 构造器）与 Modulith verify 均无法暴露此缺陷，真栈 IT
  验收门禁首跑即抓住。修复：`@Import` 补 `PatientController.class`（与其余六个控制器同模式）。
- **缺陷二（迁移约束与状态机矛盾）**：IT 二跑实证 `POST /merges` 500——`merge_record.pre_snapshot`
  被声明为 NOT NULL，但快照在 approve 执行合并时才产生（`executeMerge` 写入），发起合并（PROCESSING）
  的合法 INSERT 必然违反约束。修复：V101 修订 `pre_snapshot` 为可空并补列注释（拆分守卫仅放行
  COMPLETED，快照必在，可空性与状态机一致）。修订合法窗口 = 本 PR 未合入、无任何已应用基线
  （存量 dev 卷最大 v503 不含 patient 段，Task 14 同口径）。
- **缺陷三（拆分遗留悬空合并指针）**：IT 三跑实证 `POST /merges/{id}/split` 后从档详情仍带
  `merged_into_patient_id`——`split()` 以 `updateById` 落恢复状态，MyBatis-Plus 默认忽略 null 字段，
  指针置空从未生效（单测内存表断言掩盖）。修复：改 `LambdaUpdateWrapper` 显式 SET
  status=NORMAL + merged_into_patient_id=NULL；同步改写 `splitRestoresMergedArchiveAndPublishes`
  断言（捕获 wrapper 校验 SET 列与 NULL 值）。
- **验收**：EmpiGovernanceIT 七用例全绿为本次三处修复的验收依据。

## 2026-09-17 · P1 PR-2 Task 14 门禁驱动的两项契约修复（先记再改）

- **背景**：Task 14（装配与边界）真栈冒烟暴露两处上游契约缺陷，均由门禁 fail-fast 定位：
  Modulith 边界校验拒绝 patient 引用 system 未导出类型；fuyun-app 真栈启动时 M20 消息治理构件
  拒绝 patient 六个 2 段式事件名（`QueueGovernorImpl` ≥3 段审查，代码 + `QueueGovernorImplTest`
  两段拒绝用例 + FU-M20-06 三重锁定）。
- **修复一（审计契约枚举归位）**：`AuditActionType` 从 `com.fuyun.system.enums` 迁入
  `com.fuyun.system.api`——它是 api 包 `@AuditLog` 注解的成员类型，跨模块标注即引用，按宪法 B.1
  「api/ 对外契约唯一出口」随注解同住 api 显式导出；不放宽 Modulith 边界、不开 enums 包第二出口。
- **修复二（患者事件名对齐三段命名治理）**：六个 2 段式事件名改为 `patient.patient.<动作>`
  （created/updated/merged/split/frozen/unfrozen），依据 = M02 Spec §11 自审自己声明的
  「事件命名 `<模块>.<实体>.<动作>`」约定（spec §7 六个字面量与 §11 约定自相矛盾，本次以 §11
  为准）；M-25 成对语义不变；`patient.identifier.changed` / `patient.health-summary.updated`
  两个 3 段名不变。同步面：`PatientMessagingConstants` 六常量、V105 种子六行 event_type（迁移
  本 PR 未合入、无任何已应用基线，内容修订合法且为唯一窗口——一旦合入即冻结）、api payload 六
  record 与服务接口 javadoc、`docs/specs/modules/02-patient.md` §7。**Task 15（EmpiGovernanceIT）
  与后续订阅方一律以新名为准**。
- **修复三（发布确认回调归属纠偏）**：`PatientEventPublisher` 移除 `RabbitTemplate.Confirm/Returns
  Callback` 实现与构造期注册，复用 SystemEventPublisher 统一持有的共享回调告警通道——Spring AMQP
  共享模板单回调槽位为硬断言（设第二实例即启动失败，真栈冒烟实证；Task 13 审查 I4「后注册者覆盖
  前者」的记载有误，IotEventPublisher B4.3 偏差申报的「单一槽位统一持有」才是既定范式）。同步删除
  失效测试两例、新增「不注册回调」契约断言（IotEventPublisherTest 同款）；回调整合归 P1
  RabbitTemplateCustomizer（TASK.md W-11 评审项）收口，届时各发布器零改动。
- **裁决说明**：曾评估放宽 M20 pattern 至 ≥2 段——否决：须删除治理构件专门的两段拒绝测试用例、
  修订 FU-M20-06 与三处 javadoc，削弱已定稿治理规则且 M01/M14 事件全部合规，属反向迁就。
- **探针密钥勘误**：task-14 简报给的数据密钥为 48 位 hex，构件 fail-fast 校验要求 64 位 hex
  （32 字节 AES-256，`PatientCryptoProperties`），冒烟以 64 位探针值执行（仅影响冒烟 env，无代码影响）。

## 2026-09-16 · P1 PR-2 M02 患者 EMPI：patient 号段登记与门禁修订（先记再改）

- **号段登记（V500 起先登记先占惯例的号段制对齐条目）**：patient 域本次占用 **V100–V105**
  （V100 patient/patient_identifier、V101 possible_duplicate/merge_record、V102 health_summary/health_item、
  V103 隐私三表+脱敏规则种子、V104 card_account/card_txn、V105 患者八事件 event_registry 种子），
  均在 patient 登记号段（V100–V199）内；scripts/check-migration-governance.py `_SEGMENTS` 既有登记无需改动。
- **乱序守卫豁免修订（宪法 C.5 门禁工具修订）**：`check_out_of_order` 增「号段初始化豁免」——
  schema 在基线中零迁移时其首个批次放行（全新库升序应用为 Flyway 唯一事实；追加场景全局规则不变）。
- **JaCoCo 名单修订（宪法 C.5-2 门禁配置修订）**：父 POM 规则二核心包名单增
  `com.fuyun.patient.service.impl`（EMPI 归一/合并/冻结属核心业务状态机转换路径，
  对齐 P1 DoD「新增 M02 核心包覆盖率按 JaCoCo 双阈值」；代价 = 该包全部 impl 单测 100% 行覆盖）。
- **存量环境承接说明（审查 C5 双路径，待计划审批确认）**：全新库（CI/Testcontainers/compose 新卷）按版本
  升序一次应用 V100–V105；存量 dev 卷（最大已应用 V503）启动时 Flyway validate 将报
  「detected resolved migration not applied to database」并 fail-fast。承接路径 A（默认，非破坏）=
  application.yml `out-of-order` 键 env 化为 `${FUYUN_FLYWAY_OUT_OF_ORDER:false}`（默认 false 红线不变），
  以一次性临时容器注入 true 应用本批次后即毁（步骤/验证/还原防呆见 Task 16 Step 3），不触碰任何数据、
  兼容拍板 7「iot 夹具不动」；路径 B（备选，破坏性）= `docker compose down -v` 重建 + iot 演示夹具行
  （id=900001）留档原值重注入（重置即丢该行，与拍板 7 有张力、须经拍板）。两路径均随本条目登记。
- **号段批次后果（审查 I6）**：号段初始化豁免仅承载 schema 基线零迁移的首个批次——本批 V100–V105 合入后
  patient 后续迁移（V106+）将被乱序守卫全局规则拦截，patient 后续迁移一律走 V500+ 通用段
  （TASK.md W-12 同步登记）。
- **CF-3 冻结载体落点**：V105 八事件种子（id 9–16）+ Task 3 的 VisitIdValidator/OngoingVisitQuery
  契约 + Task 15 的 EmpiGovernanceIT（isRegistered 与 fy.topic 可消费断言）。

## 2026-09-15 · PR-1b 收尾：TASK.md W-4/W-5/W-6 工程债回填删除

- **背景**：PR-1b（M20 事件总线治理完整化）实现期内三项 TODO 工单已随各 Task 清偿，按登记台「条目回填后删除」
  规则收口；本条目为先记再改登记，TASK.md 三行删除随本条目同批落盘。
- **W-4（Flyway 迁移号段归属与版本唯一的 CI 自动校验）清偿**：`scripts/check-migration-governance.py`（号段归属 +
  版本唯一 + 相对基线乱序三重守卫，乱序守卫正对 PR-1a 实证的「号段内合法仍判 out-of-order」缺口），接线
  pre-commit local hook（`.pre-commit-config.yaml`）与 CI hygiene job（`.github/workflows/ci.yml`，fetch-depth 0 +
  MIGRATION_BASE_REF 基线注入）；既有 15 个迁移全绿，随本 PR Task 9/10 新增 V502/V503 后 17 个全绿（2026-09-15
  号段登记条目预告的「TASK.md W-4 回填依据」就此兑现）。
- **W-5（配置 properties record 的 toString 脱敏覆写兜底）清偿**：IotProperties.Amqp（11 字段全清单覆写，
  accessSecret/tokenHmacSecret 两凭据打码）与 IotProperties.Fallback、SecurityProperties toString 脱敏覆写 + 单测
  （明文泄露断言改脱敏断言），等保三级纵深防御补齐。
- **W-6（PR-2 /code-review 三项 Minor 处置）清偿**：①死信留痕列宽钳长——DeadLetterListener 五列 TextTruncate
  钳长 + 单测（防畸形帧超 VARCHAR 列宽致「不合规信封拒收留痕」红线落库失败）；②订阅登记并发守卫——
  EventRegistryServiceImpl 单语句 CAS 自旋 3 次 fail-fast + broadcast 标记行拒订守卫 + 用例（消除多实例并发丢更新）；
  ③消费范式 release 异常遮蔽——MessageIdempotencyServiceImpl.settleFailure addSuppressed 双保留 + 三处消费方同步 +
  MessagingGovernanceIT 回归（原始业务异常不再被 Redis release 异常顶掉）。
- **登记收口**：TASK.md W-4/W-5/W-6 三行随本条目回填删除；W-7（非数值遥测入库，用户指示暂缓实现）与 W-8
  （FU-M20-04 剩余条目，本 PR Task 9 新增登记）两行保留不动。
- **影响范围**：仅 TASK.md 与本文件两文件，零代码变更。

## 2026-09-15 · PR-1b 终审收口：M20 Spec §7 同步注记与 TASK.md W-9 工单登记（先记再改）

- **背景**：PR-1b 全分支终审核断——已定义端点的实现不构成改契约（Spec 无需大改），但存在三处
  「实现已交付 / Spec §7 未登记」面与一条遗留承诺未兑现，须补注记与工单登记后方可收口合入。
- **M20 Spec §7 三处注记（docs/specs/modules/20-integration.md，改动最小化、不重写既有内容）**：
  ①新增 `GET /event-publications` 行（Modulith 事件发布注册表只读投影，status 为 COMPLETED/INCOMPLETE
  派生态——PR-1b 已交付而原清单缺登记）；②死信管理行补重推上限口径（每死信 3 次，超限错误码
  INT-1003 / HTTP 409，计数载体 = dead_letter.replay_count——控制器拍板值首次入 Spec）；③
  `POST/DELETE /event-registry` 与 `POST /mdm/redispatch` 加「分阶段交付」注记并指向 TASK.md W-8
  工单（前者写动词由队列声明治理构件自动化登记承接、无 P0 消费方故裁剪；后者受 M01 版本化回源 /
  重发接口跨模块前置阻塞）。
- **TASK.md 新增 W-9 工单（终审建议 #4）**：DeadLetterListener 同一 eventId 重复落行收敛——V4 迁移
  定案口径允许同一死信重复投递重复落行，DeadLetterListener javadoc 原承诺「P1 死信管理界面完整化时
  收敛」在 PR-1b（即 P1 完整化）交付后仍未兑现；收敛动作归 FU-M20-06 死信告警完整化或后续工单。
  DeadLetterListener 该句 javadoc 同步改为 W-9 现实口径（仅注释一处、零行为变更），编译验证通过。
- **影响范围**：docs/specs/modules/20-integration.md、TASK.md、本文件三文档，外加 DeadLetterListener
  一处 javadoc 注释，零行为变更。

## 2026-09-15 · D-8 裁决落地：宪法 A.5-9 failover 参数正文同步（先记再改）

- **背景**：PR-4 B4.4 实测（`IotAmqpReconnectIT` 两次 RED 留证）证实 qpid-jms 2.11 的 failover 选项必须带
  `failover.` 前缀（裸名形态不被 failover 层识别，语义等同未配置）；官方选项表无 timeout 类移交参数，
  唯一移交机制为有限 `failover.maxReconnectAttempts`（由 -1 改 3 后交 supervisor 以新时间戳凭证无限重建）。
  代码与 CHANGELOG 2026-09-11 条目已登记，宪法正文未同步（TASK.md D-8 待决策行）。
- **裁决**：按 TASK.md D-8 默认建议执行——**修订正文**（用户 2026-09-15 裁决，PR-1b 随本次交付）。
- **宪法修订范围**：backend/AGENTS.md A.5-9 正文——三参数补 `failover.` 前缀语法说明与取值；补
  `failover.maxReconnectAttempts=3` 的有限重试移交 supervisor 语义（「无限重连」语义上移到凭证刷新层，
  正对 IoTDA 拒绝超 5 分钟旧时间戳的服务端语义）；条款措辞与 IotAmqpConfig 装配实现逐字对齐。
- **归源更正（追记）**：CHANGELOG 2026-09-11 条目中「此为宪法 A.5-9『failover.maxReconnectAttempts=-1（无限次）』
  文字的实测修正」表述**归源错误**——A.5-9 正文从未写入 maxReconnectAttempts（见修订前正文），
  `-1` 实为**简报 §1.3 预判值**；该条目就地更正为「简报 §1.3 预判值的实测修正」（历史事实保留，仅纠正归源）。
- **登记收口**：TASK.md D-8 待决策行随本条目回填删除。
- **代码影响面**：零行为变更（仅 IotProperties javadoc 引用措辞同步；IotAmqpConfig 装配与 IotAmqpConfigTest
  URI 断言不动）。

## 2026-09-15 · P1 PR-1b M20 事件总线治理完整化：号段登记与实施落盘（先记再改）

- **号段登记（V500 起「先登记先占」，登记载体 = 本文件）**：本次占用 **V502**（`integration.mdm_subscription` 主数据分发订阅台账）、**V503**（`integration.mdm_dispatch_log` 主数据分发流水）；两者均在 integration 号段（V1-V99 与 V500+ 通用段）内，且版本号大于真库历史最大值 V501（Flyway `outOfOrder=false` 硬约束，PR-1a 真栈实证）。
- **落盘依据**：M20 Spec §4 两张治理表（mdm_subscription / mdm_dispatch_log）+ FU-M20-04 主数据分发（订阅登记、广播链路分发流水、矩阵查询）。
- **跨模块前置（登记）**：FU-M20-04 的全量初始化、每日版本对账、落后自动全量重发与 `POST /mdm/redispatch` 端点依赖 M01 版本化回源/重发接口（当前仅字典有版本化读接口），未随本次交付，登记 TASK.md 待办与 PR 描述。
- **CI 联动**：新增 `scripts/check-migration-governance.py`（号段归属 + 版本唯一 + 乱序守卫）随 PR-1b 落盘，本批两条迁移为其守护对象（TASK.md W-4 回填依据）。

## 2026-09-15 · PR-1a 收尾 D-10/D-11 用户裁决落地并修订宪法（先记再改）

- **背景**：PR-1a（Spring Modulith 事件基础设施，PR #16 合入 dev@1d998d5）执行期实证三项收尾事项登记 TASK.md D-10~D-12，用户 2026-09-15 裁决全部修正；本条目记 D-10/D-11 修宪（D-12 flaky 修复为代码变更，随修复 PR 合入，不涉宪法）。
- **D-10 裁决（跨模块监听注解包路径修宪明确）**：`org.springframework.modulith.ApplicationModuleListener`（spring-modulith-api 包）在 1.4.13 标记 @Deprecated(since="1.1", forRemoval=true)，官方 javadoc 指定替代为 `org.springframework.modulith.events.ApplicationModuleListener`（同名注解迁移至 spring-modulith-events-api，组合语义一致：@Async + @Transactional(REQUIRES_NEW) + @TransactionalEventListener；PR-1a 代码已用该路径）。**宪法修订**：B.2-6 跨模块监听条款注明注解取 `org.springframework.modulith.events` 包路径（api 包同名注解禁新代码引用）。
- **D-11 裁决（C.4 补单模块构建 -am 风险提示）**：多模块反应堆中 `mvn -pl fuyun-{domain}` 不带 `-am` 会从本地仓库解析依赖模块的已安装 jar（而非反应堆内最新构建），PR-1a 实证两类假故障：集成测试报 Flyway「迁移缺失」（旧 integration jar 无新迁移）、边界测试假违规（旧模块 class）。**宪法修订**：C.4「指定模块门禁」命令注释补不带 `-am` 的风险提示。
- **登记收口**：TASK.md D-10/D-11 两行随本条目回填删除（D-12 行随修复 PR 回填删除）。
- **修订范围（随本 PR）**：backend/AGENTS.md B.2-6（补注解包路径约定）、C.4（补 -am 提示）。

## 2026-09-14 · P1 实施计划审批通过，D-2/D-9 用户裁决落地并修订宪法 Modulith 条款（先记再改）

- **计划审批**：PLAN-P1-01（`docs/plans/2026-09-14-P1实施计划.md`）经用户裁决五项决策后批准；范围=总 Spec §9-P1 六模块 P0 优先级条目切片，PR 序列七支，交付验证物=门诊挂号→就诊→收费→发药全流程真栈演示。
- **D-2 裁决（引入 Spring Modulith）**：版本锁 1.4.x（当前 1.4.13，父 POM 锁 spring-modulith-bom，BOM 外依赖）；starter-jdbc 事件持久化（非 JPA）+ test 边界校验；`republish-outstanding-events-on-restart=false`（多实例不安全）；EventOpsJob 编程式重试（卡住>5 分钟重投）与清理（7 天前完成记录）挂 ShedLock（A.5-14）；`ApplicationModules.verify()` 进 fuyun-app 测试套纳入 verify 门禁（与 ArchUnit 1.5.0 并存分工：Modulith 管模块级边界、ArchUnit 管自定义分层规则）+ CI 生成 PlantUML/C4 模块依赖图；跨模块监听一律 `@ApplicationModuleListener`（独立事务异步）且发布方必须在事务代理内。**偏差申报（对用户参考配置）**：`events.jdbc.schema-initialization.enabled=false`——事件日志表建表走 Flyway（integration 号段 V6+，官方 event_publication 结构），理由=宪法 A.4.1「Schema 唯一来源=Flyway、禁自动 DDL」红线不豁免 + `--scale backend=2` 多实例并发自动建表竞态；用户改判框架自动建表须同步修宪豁免。
- **宪法修订范围（随本 PR）**：backend/AGENTS.md B.2-6（Modulith 引入定稿：版本锁定/边界校验 CI 强制/与 ArchUnit 分工/@ApplicationModuleListener 约定/日志表 Flyway 建表）、B.3-2（in-JVM 可靠投递走 Modulith 注册表，@Externalized 桥接范围经设计评审后再修订）、B.3-3（可靠事件投递形态由自建"事务后事件表+定时重投"改为 Modulith 事件发布注册表 + 定时重试/清理）、C.2 技术栈表增 Spring Modulith 行。
- **D-9 裁决（非数值遥测入库）**：非数值且需要的数据像数值型一样提取转换入库存储——quality 维持既有 isNumeric 标注（BAD=非数值定型标注，语义不变）；入库不再丢弃：iot_telemetry 新增文本承载列（iot 号段新迁移，禁改已应用迁移），非数值标量以原文承载、对象/数组以紧凑 JSON 文本承载，skip_non_numeric 丢弃口径退役。实现登记 **TASK.md W-7**（P1 PR-1 开工前 fix PR 闭合），D-9 行就此回填删除。
- **其余裁决**：M03 病历书写=临时纯文本文书能力过渡（M09 编辑器维持 P4）；医保基线接口=接口位+模拟适应器（真实联调环境用户侧后补）；portal 患者预约渠道纳入 P1（PR-5）。
- **登记动作**：TASK.md 待决策项 D-2/D-9 行回填删除、TODO 工单新增 W-7。

## 2026-09-14 · IOTDA 联调收口：L-1 全链路演示、L-2 十分钟断链、L-4 积压水位实测与 TASK.md 延后登记回填（先记再改）

- **前提**：PR #12（L-3 报文映射，合入点 dev@0584e1e）交付后重建 `fuyun/backend:dev` 镜像并 `--profile sim` 起栈，七服务全 healthy。本条目为 TASK.md「延后事项 L-1~L-4」的回填记录（回填后删除），全部证据产生于真实华为云 IoTDA 环境（dev 联调栈）。
- **L-1 全链路演示（IoTDA→AMQP→TimescaleDB→WebSocket）**：①上行——iot-simulator MQTT/TLS（ssl://…iotda-device…:8883，deviceId=6aa570ac155456566827c784_fuyun-demo-001）5 秒周期 properties 上报；②消费与映射——backend AMQP 消费每 5 秒一批 2 条（batchSize=2/inserted=2，无 SASL 错误、无毒丸），`iot_consume_error_log` 起新毒丸为 0（历史 8740 行为映射落地前存量留痕），`iot.iot_telemetry` heartRate/spo2 行 quality=GOOD、source=IOTDA；③绑定归属——联调夹具 `iot_binding` id=900001（demo 设备→病区 1，dev 环境测试数据，P1 管理端点交付前的演示夹具）生效后落库行 patient_id/visit_id 富化为 1/1；④推送——admin 令牌经 nginx `/ws` 升级、STOMP CONNECT 帧鉴权（Authorization: Bearer）通过，订阅 `/topic/iot/telemetry/1` 收到摘要帧（count=2、items=heartRate/spo2、occurredAtUpperBound 与批次对齐，16 秒采样窗收 3 帧）。**口径注明**：设备状态帧链路（iot_device.status 更新与 /topic/iot/device-status/{wardId} 推送）真实栈不可演示——simulator 仅发 properties 上报无状态帧，该管道行为由 IotTelemetryPipelineIT 集成测试覆盖。
- **L-2 真实端点 10 分钟断链演示**：方法 = `docker stop deploy-backend-1` 制造真实 AMQP 断链 10 分 26 秒（17:23:29Z→17:33:55Z），期间 simulator 持续上行（IoTDA 服务端积压）；`docker start` 后消费线程 4.5 秒以**新时间戳凭证**重建连接（日志「AMQP 连接已建立（新时间戳凭证）」，正对 IoTDA 凭证内嵌时间戳超 5 分钟拒绝建链的服务端语义——本地 broker 无法复现该语义，真实端点演示由此补全 T-R3-3 口径）；首批攒批一次追平积压 received=252/inserted=252（126 帧×2 属性，与断链窗口帧数分毫不差，零丢失），随后恢复 5 秒/2 条稳态。断链退避节奏（12s→24s→30s 封顶）已在 PR #11 联调期于真实 IoTDA 拒链场景实证。
- **L-4 真实积压水位实测（应用侧代理口径）**：IoTDA 控制台指标不可达（无控制台访问），以应用侧实测代理——重连后积压帧最旧 occurred_at=17:23:32.508Z（断链后首个上报），距追平落库时刻 17:34:07Z 约 **10 分 35 秒**，即本次断链窗口的 IoTDA 侧最旧未消费消息年龄实测值；252 行时间边界连续（最旧 17:23:32.508Z/最新 17:34:05.121Z）无缺口无重复。持续水位观测由既有 iot.amqp 指标词表承载（iot.amqp.connected 实测 1.0、断链时长、攒批队列填充率、重建计数）。
- **L-3**：已随 PR #12 落地（2026-09-13 条目），本次以「新毒丸归零 + 遥测落库」实证；其 quality 口径细化声明待用户追认（登记 TASK.md D-9）。
- **登记收口**：TASK.md 延后事项 L-1~L-4 四行按「回填后删除」规则删除（该节清空）；新增 D-9 待决策项；台账补 PR #12 批次行。

## 2026-09-13 · L-3 冻结：真实 IoTDA 规则引擎报文映射（选项 B 代码映射）与毒丸留痕脱敏落地（先记再改）

- **背景与批准结论（TASK.md L-3，用户 2026-09-13 批准选项 B）**：P0 线格式为 CF-7 JSON，但真实华为云 IoTDA AMQP 推送报文与 CF-7 不匹配，毒丸隔离机制已留痕 569 帧（iot_consume_error_log，stage=PARSE、确认抛弃）。真实报文结构已取证（raw_payload 原文）：顶层 `resource="device.property"` + `notify_data.header`（device_id/node_id/product_id）+ `notify_data.body.services[]`（service_id/properties/event_time）。选项 B 裁决：解析器新增「IoTDA AMQP 推送报文」第三形态，真实报文展开为 N 条 CF-7 标准遥测消息，下游攒批/落库/推送管道零改动。
- **映射规格（冻结，禁再猜测性兼容）**：判别条件 = 顶层 `resource` 字段存在且精确等于 `"device.property"`（优先于既有遥测/状态判别；非该形态回退既有判别，CF-7 帧行为零变化）。字段映射：deviceId ← `notify_data.header.device_id`（必填非空白，缺失即毒丸）；occurredAt ← 顶层 `event_time_ms`（ISO-8601，复用既有 OffsetDateTime→Instant 双回退解析为 UTC Instant，缺失或不可解析即毒丸）；`notify_data.body.services` 必须为数组且 ≥1 元素、每个 service 的 `properties` 必须为非空对象，否则毒丸。值承载（StandardTelemetryMessage 契约）：每属性键一条消息，metricCode=属性名（如 heartRate/spo2，P1 建字典再规范化）；value 字符串承载——JSON 标量（文本/数值/布尔/null 字面量）`asText()` 转文本，对象/数组 `toString()` 紧凑 JSON 文本；unit 恒 null（IoTDA 属性上报不含单位）。quality 口径细化声明（2026-09-13 审核 S1 订正，待用户追认）：①冻结决策文本「映射时需 String.valueOf 并保持 quality=GOOD」的上下文为数值型真实报文（heartRate=78/spo2=100），实现对该场景产 GOOD，符合冻结文本；②冻结文本未覆盖非数值标量/布尔/null/对象/数组属性值，实现按 P0 既有 CF-7 口径补齐——value 非数值 → quality 强制 BAD 并保留原文，标注不阻断（与 TelemetryFrameParser 既有 javadoc「value 非数值时 quality 强制 BAD 并保留原文」口径同构）；③端到端等价论证：TelemetryIngestServiceImpl 仅对 value 可数值定型的行入库（约 110-116 行），非数值属性无论 GOOD/BAD 均不入库，真实报文两口径产物完全一致，偏差仅在解析器契约层；④待用户追认：若用户改判恒 GOOD，改动面 = 解析器单行 + 断言翻转，随下一次修宪/修订登记回收。source=IOTDA。
- **消费者确认回调挂尾设计与安全性论证**：批量帧展开的 N 条消息逐条入攒批器，客户端确认回调只挂尾条、其余挂空动作。安全性：JMS CLIENT_ACKNOWLEDGE 为会话级累计确认且每队列独立会话——同一队列单消费线程按序投递、攒批器单 flush 线程按序刷批，任何会累计确认到本 JMS 消息的确认回调必然在其尾条所属批次落库之后才可能执行（批次按序、批内先落库后确认），不存在「确认先于落库」窗口；落库失败路径零真实确认执行 → 会话销毁令整条消息回归重投域 → iot_telemetry 唯一约束 ON CONFLICT DO NOTHING 幂等去重，at-least-once 语义保持。防御分支：展开产物为空（解析器契约不可能）按毒丸留痕抛弃——否则该 JMS 消息无确认动作致 broker 无限重推。
- **毒丸留痕脱敏落地（TASK.md L-3 行自带义务，终审 Minor 2026-09-11）**：现有 569 毒丸帧原文含生命体征数值（健康数据），禁止原文入库。新增 `ConsumePayloadMasker.sanitize`：IoTDA 推送形态（可解析为 JSON 对象且含 `notify_data` 对象字段，无论毒因）→ 白名单字段提取——保留 resource/event/event_time_ms、header 三标识与 services[].service_id 结构及 properties 键名，属性值一律替换 `"*"`（紧凑 JSON，白名单外字段如 services[].event_time 丢弃）；其余文本（非 JSON/其他形态）→ SensitiveMasker 正则兜底（先证后机组合约定）；null/空串原样。接线后 iot_consume_error_log.raw_payload 恒为脱敏文本；口径变化：raw_digest 随之为脱敏后文本的摘要（用途=排查锚点与重复帧对账，脱敏后同构报文摘要合并无害）。
- **改动面**：`IotMessagingConstants` 新增 IoTDA 推送报文字段常量；`TelemetryFrameParser` sealed `ParsedFrame` 新增 `TelemetryBatchFrame` 第三变体与 `parseIotdaDeviceProperty` 解析（既有遥测/状态判别与解析零逻辑改动）；`IotAmqpTelemetryConsumer` 分派链新增批量帧分支与 `dispatchTelemetryBatch`（挂尾确认），毒丸留痕接脱敏；新增 `ConsumePayloadMasker`（internal/ 静态工具，对齐 SensitiveMasker 模式）；测试增量：解析器/消费者/脱敏器单测与 `IotTelemetryPipelineIT` 步骤⑨（真实取证报文经 fake broker → iot_telemetry 展开 2 行）。

## 2026-09-12 · 缺陷修复：AMQP 凭证改华为云官方三段 username 与原值 password 格式（先记再改）

- **缺陷实证（本地 compose 联调）**：启用 AMQP 对接真实华为云 IoTDA 后认证恒被拒，backend 日志 `Client failed to authenticate using SASL: PLAIN`（supervisor 退避重建 12s→24s→30s 封顶运转正常、simulator MQTT 链路正常）——排除重建机制问题后，经官方文档核对定位为凭证组装格式错误。
- **官方核对结论（来源：《AMQP客户端接入说明》 support.huaweicloud.com/usermanual-iothub/iot_01_00100_2.html）**：username = `accessKey=${accessKey}|timestamp=${timestamp}|instanceId=${instanceId}` 三段竖线拼接（instanceId 可选，同一 Region 多个标准版实例才需设置，单实例留空段即可）；password = accessCode **原值、无任何拼接**；timestamp 为 13 位毫秒且服务端校验偏差超 5 分钟即拒绝（每次建链刷新机制保留）；连接串子参数 `amqp.vhost=default&amqp.idleTimeout=8000&amqp.saslMechanisms=PLAIN`（vhost 仅支持 default）。此前 PR-4 调研期「password = accessSecret + 13 位毫秒时间戳拼接」为**错误预判**，相关注释表述本次一并清除。
- **修复面**：① `IotAmqpTelemetryConsumer.ensureConnected` 凭证组装改官方格式（username 三段、password 原值，javadoc 引用官方 URL）；② `IotAmqpConfig` 连接 URI 于 amqps（IoTDA 生产端点）子 URI 追加官方三子参数——vhost 仅支持 default 属 IoTDA 接入面参数，本地 amqp:// RabbitMQ（vhost 为 `/`）不追加，failover.* 三参数零改动，URI 装配契约以新增单测固化；③ 消费者单测凭证断言改新格式（username 三段解析 + 时间戳随 clock 进动递增 + password 原值）；④ fuyun-app 三个 AMQP IT 的 broker 建号（用户名/标签/权限三命令）与生产者凭证同步（固定时钟下 username 三段字面可预置，固定时钟机制保留）。

## 2026-09-12 · 缺陷修复：禁用 JMS 健康指标，消除无凭证探测致 backend 容器 unhealthy（先记再改）

- **缺陷链（本地 compose 联调实证）**：启用 `FUYUN_IOT_AMQP_ENABLED=true` 对接华为云 IoTDA AMQP 后，Spring Boot actuator 的 JmsHealthIndicator 自动探测 classpath 上的 Qpid JMS ConnectionFactory 并发起**不带凭证**的连接——IoTDA 强制 SASL PLAIN 鉴权，探测恒失败（JMSSecuritySaslException）→ actuator/health 聚合 DOWN → compose healthcheck（探 actuator/health 要求 UP）判 backend 容器 unhealthy → iot-simulator（depends_on service_healthy）无法启动。
- **修复裁决**：`application.yml` 增 `management.health.jms.enabled: false` 禁用 jms 健康指标。理由：实际业务消费者带凭证 `createContext(accessKey, password, CLIENT_ACK)` 工作正常，探测语义对本架构无意义且有害；AMQP 链路真实状态已由自研 Micrometer 指标（iot.amqp.connected / disconnect.duration.seconds / reconnect.total 等）承载。
- **P1 完整化方向**：自研 HealthIndicator 反映 iot.amqp.connected 真实链路状态，纳入 readiness 聚合后再评估替代本禁用项。

## 2026-09-11 · PR-5 CI 门禁缺陷修复：移除骨架期 pom/webpkg 构建守卫，恢复后端/前端门禁触发（先记再改）

- **缺陷实证（PR #8，CI run 34645973742）**：changes job 判定输出 `Filter backend = true`、`Filter pom = false`，backend job 双条件 `needs.changes.outputs.backend == 'true' && needs.changes.outputs.pom == 'true'` 为 false → `backend / verify` skipping——本 PR 明确含后端改动（fuyun-iot 鉴权迁移 + fuyun-app IT，9 个 java 文件）却未跑后端门禁，属于严格门禁模型（方案 B）下的门禁绕过缺陷。
- **根因与守卫退役原因**：`pom: 'backend/**/pom.xml'` 与 `webpkg: 'web/**/package.json'` 两个过滤器是 PR-1 骨架期的「构建文件存在性守卫」——骨架期 pom.xml/package.json 尚未落盘时防止构建 job 空跑；骨架早已落盘后该守卫存在前提消失，语义退化为「diff 必须触碰 pom.xml/package.json 才跑门禁」，改 java/vue 不碰构建文件的后端/前端改动全部绕过 verify 门禁，故按死配置退役删除。
- **PR-2~PR-4 未暴露原因**：各 PR 均恰好新增模块/依赖（必碰 pom.xml 或 package.json），守卫条件恒为 true，双条件退化等价于单条件，缺陷未显形。
- **修复面**：backend/frontend job 的 if 删除 pom/webpkg 条件（仅保留路径变更单条件）；changes job 的 pom/webpkg 过滤器定义与 outputs 映射一并删除（全仓 grep 核实 `outputs.pom`/`outputs.webpkg` 无其他消费方；setup-java 的 `cache-dependency-path: backend/**/pom.xml` 与 pnpm 的 `package_json_file: web/package.json` 属工具自身参数，与该 output 无关，不受影响）；五 checks 名（backend / verify、frontend / verify、images、commitlint、hygiene）与其余 filter/job 结构零改动。

## 2026-09-11 · PR-5 独立审查修复：/ws/iot 鉴权点迁移（HTTP 握手层→STOMP CONNECT 帧）、断线重订阅读断言与 bigscreen 状态机/traceId 修复（先记再改）

- **Finding 1（Critical，跨栈）**：fuyun-iot 鉴权点由 HTTP 握手层迁移至 STOMP CONNECT 帧级——浏览器原生 WebSocket API 无法携带自定义 HTTP 头，stompjs connectHeaders 只进入建连后的 CONNECT 帧，原 `StompHandshakeAuthInterceptor` 读 HTTP 升级头对浏览器客户端必然 401（端到端永远无法建连）。迁移后 /ws/iot 升级端点允许匿名建立 WebSocket 传输层，但任何 STOMP 会话必须先通过 CONNECT 帧令牌校验方可 CONNECTED——SimpleBroker 仅在 CONNECTED 后接受 SUBSCRIBE，未授权会话无法订阅/收发任何数据（订阅前无数据暴露），鉴权时点仍先于一切数据通道，安全等价。拒绝语义（spring-websocket 6.2.19 `StompSubProtocolHandler` 字节码实证）：帧级 ChannelInterceptor 抛 MessagingException → 服务端回 ERROR 帧（message=不含令牌与原因的固定摘要，防枚举）→ 随即以 CloseStatus.PROTOCOL_ERROR 关闭连接。`StompHandshakeAuthInterceptor` 及其单测删除，鉴权逻辑全部迁至 `StompConnectAuthInterceptor`（clientInboundChannel 挂载）；`IotTelemetryPipelineIT` 改为 CONNECT 头承载令牌（与生产浏览器客户端同通道）并补无/错令牌拒绝负路径用例；docs/specs 14-iot §WebSocket 两处「握手鉴权」表述同步（接口契约同步条款）。web 端零改动理由：前端 connectHeaders 注入方式本就承载于 CONNECT 帧，迁移后与帧级拦截器天然对齐，仅修正注释中「握手层」表述。
- **Finding 2（Critical，T-R4-2 实测结论回填）**：stompjs 7.3.0 断线自动重连后无自动重订阅（onWebSocketClose 时 _stompHandler 整体作废，库内不重建订阅）——bigscreen useIotStomp 在 onWebSocketClose/onStompError 将在册订阅句柄置 null（旧句柄已随连接作废），onConnect 无条件重订阅（与首连复用同一 doSubscribe 落地方法），消除「徽标已连接、零帧流入」假连接。**T-R4-2 结论**：stompjs 7.3.0 无自动重订阅，客户端须在 onConnect 重订阅，已在 PR-5 落码；TASK.md 该行按登记台规则回填删除。
- **Finding 3（Important）**：stompjs activate() 对已激活 Client 为 no-op，connect() 无条件置 connecting 使已连接换病区再点连接卡死 connecting 态（断开按钮 v-if connected 消失）——已连接（client.connected=true）时改为保持 connected 态、不置 connecting、不重复 activate，订阅切换由紧随其后的 subscribeTelemetrySummary 已连接分支承接（与断线重连重订阅复用同一内部方法，防两处订阅逻辑漂移）。
- **Finding 4（Important）**：crypto.randomUUID 带 [SecureContext] 限定，仓库拓扑 nginx :80 无 TLS、内网 HTTP 访问下为 undefined（TypeError）——useIotStomp traceId 生成加守卫降级（时间戳+随机数组合串，仅作日志锚点非密码学用途）。

## 2026-09-11 · PR-5 B5.2：P0 收口事务——W-3 销项、T-R3 回填核对、计划完成项标注与 DoD 预检落盘（先记再改）

- **W-3 销项（逐项核实后删除，禁盲删）**：三项对齐逐一实测复核达成——① `backend/Dockerfile` 26 条显式 COPY 逐模块（含 fuyun-iot/iot-simulator POM 行），glob 拍平已消除（台账 B1.2 行 complete）；② web 产物路径三处同路径（compose 三应用 dist bind mount + web/Dockerfile 三条 `COPY --from=build .../apps/<app>/dist` + nginx 三 location alias，均为 W-3 裁决口径 `web/apps/<app>/dist`）；③ `ci.yml` 无骨架期排除项（changes 过滤器仅永久 `*.md` 排除，images job 三镜像构建步骤在位，台账 B1.3 行 complete）——W-3 整行删除；W-4/W-5/D-8/L-1~L-4/T-R4-2 等行一律不动。
- **T-R3-2/T-R3-3 回填核对（无文件改动，声明核对结论）**：T-R3-2 原行已于 PR-4 B4.1 实测回填删除（结论 = `add_columnstore_policy` 胜出，见 2026-09-10 B4.1 条目收口补记），TASK.md 待调研表现无该行；T-R3-3 原行已于 B4.4 回填删除，本地两级实测结论（supervisor 单测 + IotAmqpReconnectIT）并入 TASK.md L-2 行，核对在位且表述完整。
- **计划完成项标注（最小内联标注法，禁改正文语义）**：`docs/plans/2026-09-08-P0实施计划.md` §1 五个 PR 标题行尾对 PR-1~PR-4 追加「——已完成（PR #N，dev@<hash>）」四处标注，合入点以台账记录为准（#4/ed5e34e、#5/a019f47、#6/a93179a、#7/9107f92）；PR-5 行不自标（合入时点未知，随 P6 终验补记）；§3 DoD 五条不动——勾选属 P6 终验，提前打勾即伪造证据。
- **DoD 预检报告落盘**：新增 `docs/plans/2026-09-11-P0-DoD预检.md`——对交付 loop §5 七条 DoD 逐条预检（已满足 / 待 P6 终验附证据 / 延后条款豁免三态，附验证命令与证据来源）；属 PR-5 时点预检而非终验勾选，终验逐项附证据归 P6。

## 2026-09-11 · PR-5 B5.1：bigscreen 最小遥测页与 STOMP 单例封装、workstation 首页骨架（先记再改）

- **依赖申报（表外申报①，随本批次首个功能提交生效）**：bigscreen app 级 package.json 新增 `@stomp/stompjs` **7.3.0**（版本来源=技术栈定稿 §4.1 与 web 宪法 C.2 唯一权威值，非新值）；**申报位置=app 级而非 catalog**——依据 pnpm-workspace.yaml 第 2 行既有注释先例（「业务独立依赖不进 catalog：……echarts/@stomp 待 PR-5 再引」），与 axios 跨 app 共享进 catalog 的口径不同；lockfile 随同一提交更新。
- **bigscreen 最小遥测页**：`/ws/iot` STOMP 单例封装（web 宪法 B.3-3 逐条款：Client 首次 connect 惰性单例、重连心跳全交库内建固定间隔 10s 禁自研循环、订阅句柄组件卸载统一退订、token 经 beforeConnect 每次连接尝试实时读 sessionStorage 键 `fy:bigscreen:iot-token`、onStompError/onWebSocketClose 统一日志含主题与 traceId 禁打令牌）；首页原位改造三区——连接设置（wardId 路由 query 可书签化 + 令牌 password 输入）、链路状态（徽标/订阅主题/帧计数）、遥测摘要（最近一帧覆盖渲染，count/occurredAtUpperBound 原样展示/items 明细表）；手写后备类型 types/iot.ts（openapi-typescript 生成链路不覆盖 STOMP 载荷，字段与后端 ITelemetryPushService record 逐字对齐并声明漂移风险）与 unknown 收窄解析 utils/iotMessage.ts；不引 echarts、不订设备状态主题（P5 负面清单，简报 §0）。
- **workstation 首页骨架**：HomeView 原位改造两区——会话问候（displayName/loginName 取既有 auth store，空值兜底「未登录用户」防御文案）+ 业务开通占位卡（文案与 AppSidebar 占位口径一致）；零新增依赖、零 api/store/路由改动、零出网调用（P0 无首页数据接口，禁止推测性调用）。
- **宪法 B.3-3 措辞差异关注项（不阻塞，简报附 1）**：条款括号「reconnectDelay 指数退避」与 @stomp/stompjs 7.3.0 内建实况（固定间隔毫秒值，无内建指数退避）存在措辞出入，本 PR 按库内建固定间隔 10000ms 落地、绝不自研退避循环（合规核心=重连完全交库内建）；措辞修订随 P1 workstation 接入 STOMP 时走修宪流程（先记 CHANGELOG 再改正文），本 PR 不动宪法。

## 2026-09-11 · PR #7 独立审查修复：AMQP 确认语义修正（累计确认丢数窗口）与 simulator MQTT 鉴权凭证补齐（先记再改）

- **Finding 1（Critical，确认语义设计前提被证伪）**：JMS `CLIENT_ACKNOWLEDGE` 为会话级累计确认（JMS 规范 §4.4.11）——对同会话任一消息 `acknowledge()` 会一并确认此前全部未确认交付。原设计「落库失败零回调→帧留待 IoTDA 重推」只在会话/连接重建时成立：真实时序下失败批 [A,B] 未确认，后续成功批 [C,D] 的批末确认会把 A、B 一并累计确认，broker 不再重投，数据无痕丢失；状态帧 `apply` 失败帧同根缺陷（被后续成功状态帧确认吞掉）。
- **修复裁决（审查方向①，失败即重建会话）**：落库失败（攒批 flushBatch）与状态帧业务失败（dispatchSafely 业务异常域）统一触发既有 supervisor 全局重建路径——关闭全部在册上下文，会话销毁令其全部未确认交付回归 broker 重投域，重投帧由 iot_telemetry 唯一约束 ON CONFLICT DO NOTHING 幂等去重；worker 线程的业务失败在触发重建后仍上抛走既有退避（防 DB 持续故障下无退避热循环）。线程安全：复用 onException 同款机制（volatile 引用置换 + CopyOnWriteArrayList 遍历 + 幂等关闭），攒批 flush 线程与消费线程并发触发无新锁。在途帧处置：失败瞬间清空攒批挂起队列（在途帧均为已交付未确认态，且清空先于上下文关闭，其会话销毁后必然回归重投域——丢弃语义自洽）；极小窗口内旧会话帧再入队时其确认失败将再次触发重建直至收敛（幂等无害）。javadoc 旧「落库失败零确认待重推」表述一并改写为真实语义。
- **Finding 2（Important，iot-simulator）**：`IotdaMqttClient.connect` 构造 MqttConnectOptions 从未设置 username/password，真实 IoTDA 一机一密鉴权（CONNECT 报文 username=deviceId、password=HMAC 摘要）必然拒绝——connect 补 `setUserName`/`setPassword`，修正「凭证随 clientId 构造生效」错误注释，单测补 options 携带凭证断言。

## 2026-09-11 · PR-4 终审修复：sim 全链路 backend 侧 AMQP 启用接线闭环（先记再改）

- **问题（终审 Finding 1，Important）**：`fuyun.iot.amqp.enabled/queues` 在 application.yml 硬编码 `false`/`[]` 无 env 占位，deploy 编排未透传启用开关与队列清单——用户按 .env.example 填齐 IOTDA_* 六变量后 `docker compose --profile sim up`，AMQP 消费链仍静默 disabled，TASK.md L-1 延后演示路径不通；且手动 enabled=true 而未配 queues 时 fail-fast 全栈不可用无前置提示。
- **修复范围**：① application.yml 两键改 env 占位（`FUYUN_IOT_AMQP_ENABLED:false` / `FUYUN_IOT_AMQP_QUEUES:` 空占位）+ 中文注释说明逗号分隔格式与默认值语义；② IotProperties.validateAmqpEnabled 增空白队列名 fail-fast 校验（空串绑定实测 + 单测固化）；③ .env.example 增两变量占位与注释；④ docker-compose.yml backend environment 增两行透传；⑤ TASK.md L-1 行补启用前提说明。deploy 透传带 `:-` 默认值属有据偏差：实测 enabled 绑定不接受空串（boolean 绑定失败阻断启动），`:-false` 防 .env 缺键/留空，environment 段优先级高于 env_file 可覆盖整组注入的空值。
- **queues 空串绑定实测结论（ApplicationContextRunner 实测，2026-09-11）**：空串 env 经 relaxed binding 绑定为**空列表**（size=0，非 null、无空串元素），enabled=true 时由既有启用组 @NotEmpty fail-fast（中文报错），单测固化该绑定语义；但含空段的 env（如 `q1,,q2`、尾逗号、空白项）绑定为**含空串元素**的列表，@NotEmpty 只拦整体缺失放行无效元素——validateAmqpEnabled 增空白队列名显式拒绝（fail-fast 中文报错），单测固化。

## 2026-09-11 · PR-4 B4.4：T-R3-3 本地实测重要发现——Qpid failover 透明恢复屏蔽 supervisor，AMQP URI 补正官方选项语法并改有限重试移交（先记再改）

- **实测发现（IotAmqpReconnectIT 首跑 RED 留证，2026-09-11）**：`rabbitmqctl stop_app` 优雅断链下，Qpid failover 传输层做纯透明恢复——ExceptionListener 不触发、阻塞中的 receive() 持续等待重连、消费者 supervisor 全程未介入（`iot.amqp.connected` 恒 1、`iot.amqp.reconnect.total` 恒 0，断链时长指标恒 0）。推演生产语义：IoTDA 真实断链超 5 分钟后，failover 仍以连接建立时捕获的旧时间戳凭证无限重试（`failover.maxReconnectAttempts=-1`），被服务端拒绝后永续循环且消费链路无感知——supervisor 的「新时间戳凭证重建」被完全屏蔽，宪法 A.5-9 的 supervisor 语义落空。
- **对策（AMQP 连接 URI 修正为官方 failover 选项语法 + maxReconnectAttempts 改有限值移交 supervisor）**：① 选项前缀修正——qpid-jms 官方文档「Client configuration」明确 failover 选项语法为 `failover.` 前缀形态（failover.initialReconnectDelay / failover.reconnectDelay / failover.maxReconnectDelay），B4.2 起的裸名形态不会被 failover 层识别为选项（语义等同未配置，三值 3s/3s/30s 从未真实生效），本次按官方语法补正前缀、取值零变化。② 移交机制——qpid-jms 2.11 官方选项表核对（来源 qpid.apache.org/releases/qpid-jms-2.11.0/docs）**无 timeout 类移交参数**（`failover.timeout` 属 ActiveMQ failover 词表，Qpid 下装配即报「Failed to create JMS Provider instance for: failover」，第二次 RED 实测留证）；官方选项表内唯一移交机制为有限 `failover.maxReconnectAttempts`——由宪法/简报锁定的 -1 改为 3：provider 连续重试 3 次放弃后连接失败（ExceptionListener 触发 / receive 失败上抛），控制权移交 supervisor 以新时间戳凭证无限重建，「无限重连」语义上移到凭证刷新层（每次重建刷新 13 位时间戳，正对 IoTDA 5 分钟拒绝语义）；瞬时抖动（≤3 次重试约 9s 内）仍走透明恢复。**偏差申报**：此为**简报 §1.3 预判值**（-1，非宪法条文）的实测修正（归源更正见 2026-09-15 D-8 条目）——无限重连语义在 supervisor 层完整保留，总重连次数不设上限，仅传输层透明重试限 3 次；真实 IoTDA 端点的等价行为验证随 TASK.md L-2 联调演示回填。

## 2026-09-10 · PR-4 B4.4：iot-simulator 子模块、表外依赖核实与一机一密算法官方核对（先记再改）

- **iot-simulator Maven 子模块申报（D-3 默认裁决，纯 Java 零 Spring）**：父 POM `<modules>` 增 `iot-simulator`（fuyun-app 之后）；新增 `backend/iot-simulator/Dockerfile`（多阶段独立镜像，与 backend/Dockerfile 七条规范对齐）；`backend/Dockerfile` 两处小改——pom COPY 清单追加 `COPY iot-simulator/pom.xml iot-simulator/` 一行 + 删除尾部 `# TODO(iot-simulator)` 注释行；CI images job 追加第三构建步骤「构建镜像（iot-simulator）」（同构显式步骤、禁 matrix、job 名 `images` 不变、cache scope=iot-simulator、step 级 if 与 backend 步骤同条件）并删除第 149 行 TODO 注释。
- **表外依赖核实与申报（简报 §10 要求落码前以 Maven Central 元数据核实）**：① `org.eclipse.paho:org.eclipse.paho.client.mqttv3` **1.2.5**——Central maven-metadata 实测 `<release>`/`<latest>` 均为 1.2.5（Eclipse Paho 官方最新稳定行，技术栈定稿未收录 MQTT 客户端），版本经父 POM dependencyManagement 集中声明（属性 `paho-mqtt.version`，遵循「BOM 外依赖集中声明、子模块禁自带版本号」宪法口径，qpid 2.11.0 先例）；② `maven-jar-plugin` **3.4.2**——Central versions 清单核实存在（3.4.2 命中 1 行）；③ `maven-dependency-plugin` **3.8.1**——Central versions 清单核实存在（3.8.1 命中 1 行，目录探针 HTTP 200），simulator 模块内显式锁定。
- **一机一密连接三元组官方核对结论（简报 §5 要求实现期核对，来源：华为云 IoTDA 官方文档《密钥鉴权_MQTT(S)协议接入》support.huaweicloud.com/devg-iothub/iot_02_0203.html）**：clientId = `{deviceId}_0_0_{时间戳}`（第 2 段固定 0=设备 ID 标识、第 3 段 0=HMACSHA256 不校验时间戳准确度但仍须携带时间戳，官方生成工具默认形态）、username = deviceId、password = **HmacSHA256(key=UTC 时间戳, message=deviceSecret) 小写十六进制**——时间戳格式为 **UTC `yyyyMMddHH`（10 位，小时粒度）而非简报预判的 13 位毫秒**，HMAC 方向为时间戳作密钥、secret 作内容（官方示例实测复算一致：secret=12345678、timestamp=2025041401 → `c75150e6cb841417396819e4d2ee4358a416344a03a083e3a8567074ddec820a`，与文档原例逐字符相同）。**与简报 §5 预判口径（13 位毫秒时间戳）偏离，以官方文档为准落码**，核对结论写入 DeviceCredentialEncoder javadoc；若真实联调发现服务端行为出入，改动面仅该类 + 单测。
- **T-R3-3 本地两级实测与延后登记预告**：supervisor 单测（B4.2 已交付）+ 本地 broker 断链恢复 IT（IotAmqpReconnectIT，本批次交付）构成代码级实测闭环；「真实 IoTDA 端点 10 分钟断链演示」与 `--profile sim` 全链路演示、真实 IoTDA 规则引擎报文映射冻结（P0 线格式=CF-7）、真实积压水位指标（IoTDA 侧最旧未消费消息年龄）四条一并延后登记 TASK.md（本批次收口提交执行）。

## 2026-09-10 · PR-4 B4.3 审核修复

- **F-1 摘要推送违反宪法 A.4.2-7**：TelemetryIngestServiceImpl 在 @Transactional ingest 事务方法内直推 STOMP 摘要（"进程内直推非 MQ"自我解释不成立，宪法原文"事务内禁止远程调用、消息发送与人工等待"不限 MQ）——修复为 TransactionSynchronizationManager 注册 afterCommit 回调执行既有 pushSummariesByWard（分组数据事务内组装、推送 I/O 移出事务）；isSynchronizationActive=false（单测直调无事务）时保持直推行为不变，相关 javadoc 同步修正；单测补 TransactionTemplate 时序断言（事务内不推、提交后推送）。
- **F-2 状态主题生产死路径（wardId 恒 null）**：P0 状态帧契约不含 wardId、解析产物恒 null，消费者原样发布致 /topic/iot/device-status/{wardId} 生产无数据源——IDeviceStatusService.apply 返回值 boolean→Long（模块内接口，返回设备档案 ward_id；null=设备不存在/条件未命中/档案未编病区，不发布事件），select 投影增补 ward_id；IotAmqpTelemetryConsumer.handleStatusFrame 以返回 wardId 构造含 wardId 的事件再发布；IotTelemetryPipelineIT 步骤 5 恢复 AMQP→STOMP 全链断言（不再以手工信封替代链路）、步骤 6 改从 integration.received_event 台账读取真实已消费 eventId 重建重投。

## 2026-09-10 · PR-4 B4.3 任务 B：STOMP/WebSocket 依赖申报与 deploy 兜底密钥变更（先记再改）

- **fuyun-iot pom 依赖申报（BOM/父 POM 托管零版本声明，PR 描述申报）**：① `org.springframework.boot:spring-boot-starter-websocket`——`/ws/iot` STOMP 端点（IotWebSocketConfig：@EnableWebSocketMessageBroker + 内存 SimpleBroker(/topic) + 无 SockJS，P0 客户端仅 PR-5 bigscreen 原生 WebSocket）；② `io.micrometer:micrometer-core`——IotAmqpMetrics 三 gauge（iot.amqp.connected / disconnect.duration.seconds / batch.queue.fill.ratio）与双 counter（reconnect.total / batch.flush.failure.total）注册的 MeterRegistry 编译依赖；③ `com.fuyun:fuyun-system`——仅消费 api 包 TokenVerifier（任务 A 已交付契约）作 STOMP 握手鉴权，宪法 B.2-2 合规。
- **deploy 变更面申报（简报附 1 待裁决项，主控已裁决 nginx /ingest 路由纳入本 PR）**：`.env.example` 增 `FUYUN_IOT_FALLBACK_TOKEN=` 空占位（独立行中文注释"必填：IoT 兜底通道共享密钥，禁止提交真实值"）；`docker-compose.yml` backend environment 增同名透传一行；`deploy/nginx/fuyun.conf` 增 `location /ingest/` 反代——兜底端点 `POST /ingest/iotda-fallback` 不在 `/api/v1` 前缀下，既有 `/api/`、`/ws/` 两条路由无法覆盖，唯一公网入口原则下的路由缺口补齐（对齐既有 /api location 写法）。
- **fuyun-app application.yml 配置占位**：增 `fuyun.iot.fallback.token: ${FUYUN_IOT_FALLBACK_TOKEN:}` 映射（B4.2 已落 IotProperties.Fallback 嵌套 record，本次补 yml 环境变量映射行）；空默认 = 未配置，兜底鉴权比对侧 fail-closed 一律拒绝（IOT-1001）。

## 2026-09-10 · PR-4 B4.3：TokenVerifier 跨模块小改与 iot 扇出依赖申报（先记再改）

- **跨模块小改申报（D-7 先例，随本批次首个功能提交生效）**：fuyun-system api 新增 `TokenVerifier` 接口（`boolean verifyAccessToken(String rawToken)`——校验 access 令牌全链（签名/过期/typ/会话存在），通过 true、任何失败 false 不抛异常且不区分原因防枚举，适配 WebSocket 握手与 MQ 线程等无 ProblemDetail 出口场景）；`TokenServiceImpl` implements 增补（内部委托既有 verify(ACCESS) 校验链，捕获 BizException 返回 false）；`SystemWebConfig` 增补一行 @Bean 以接口类型暴露同一实例。消费方：iot /ws/iot STOMP 握手鉴权（本批次仅交付契约与单测，握手拦截器随任务 B）；fuyun-iot 后续仅依赖 system api 包（宪法 B.2-2 合规）。接口属对外契约新增，PR 描述申报。
- **fuyun-iot pom 依赖申报（BOM/父 POM 托管零版本声明，B4.2 审核 Minor 4 遗留项补齐）**：① `com.fuyun:fuyun-integration`——IotMessagingConfig 经 api 包 MessagingGovernance/ConsumerQueueSpec 声明自事件消费队列 q.iot.iot.device.status-changed（V403 已登记，先登记后订阅；system pom 先例）；② `org.springframework.boot:spring-boot-starter-amqp`——IotEventPublisher RabbitTemplate 发布与 IotFanoutListener @RabbitListener 消费（AUTO 确认 + MessageIdempotencyService 标准幂等范式，与 AMQP 主链路客户端确认两套机制并存）。
- **实现偏差申报（简报 §4 IotEventPublisher"Confirm/Returns 回调 SystemEventPublisher 同模式"）**：Spring AMQP 对共享 RabbitTemplate 强制断言仅支持单一 Confirm/Returns 回调（注册第二个不同实例即启动失败，IT 实证）——双发布器并存下"各自注册"不可成立，故回调保持由 PR-3 交付的 SystemEventPublisher 构造期统一注册（与装配顺序无关：iot 发布器不注册），iot 发布的 nack/不可路由告警复用同一回调（error 日志含 eventId/路由三要素、P0 不自动重发，语义等价）；回调归属整合（如 RabbitTemplateCustomizer 收口治理装配）归 P1 治理完整化，届时 iot 侧零改动。

## 2026-09-10 · PR-4 B4.2：JaCoCo 核心包增补 iot service.impl（先记再改）

- **POM 门禁变更登记**：父 POM JaCoCo PACKAGE 级 LINE=1.00 规则 include 清单增补 `com.fuyun.iot.service.impl`，与首个 iot service.impl 实装类同一提交生效（BRIEF-PR4-01 §7 处置结论）——iot 消费落库链属"对外服务接口（第三方对接）"核心功能（全局 §四核心界定），沿用 backend 宪法 C.5-2"核心包 rule 随模块实装逐步声明"既有模式（integration/billing/system 三包先例）；SmartLifecycle 消费器/监听器/解析器落 internal/ 包按 BUNDLE 0.80 承载，1.00 规则不误伤难测基础设施类。本项属 PR-4 表外申报清单预告项（B4.1 条目已预告），PR 描述重申申报。

## 2026-09-10 · PR-4 B4.1：iot 号段占用登记（先记再改）

- **号段登记（TASK.md W-4 载体）**：iot 域（M14）占用 **V400–V499**，本批（PR-4 B4.1）使用 V400–V403——V400 设备档案与绑定表、V401 消费错误日志表、V402 遥测超表与压缩/保留策略（T-R3-2 实测锁定）、V403 设备状态事件种子登记。核对结论：现存迁移仅 integration V1–V5 与 system V300–V303，V400–V499 无冲突。
- **PR-4 表外申报预告**（简报 §10 清单，随各批次落地逐项申报）：qpid-jms-client 2.11.0（父 POM 已锁）、Boot BOM 托管 starter 集合（validation / websocket / amqp / micrometer / mybatis-plus / mapstruct / lombok 等）、Eclipse Paho MQTT 客户端 1.2.5 与 maven-jar-plugin 3.4.2 / maven-dependency-plugin 3.8.1（iot-simulator）、父 POM modules 增 iot-simulator + JaCoCo 核心包增补 `com.fuyun.iot.service.impl`、fuyun-app pom 增 fuyun-iot 依赖、fuyun-system api 增 TokenVerifier 接口、deploy 增 FUYUN_IOT_FALLBACK_TOKEN 占位与 nginx `/ingest/` 路由、CI images job 追加 iot-simulator 第三构建步骤。
- **T-R3-2 实测结论（收口补记）**：压缩策略函数胜者 = `add_columnstore_policy`（2026-09-10，`timescale/timescaledb:2.29.2-pg16` 探针容器实测）——探针 SQL「`SELECT proname FROM pg_proc WHERE proname IN ('add_columnstore_policy','add_compression_policy') ORDER BY 1;`」输出两函数均存在；`pg_proc.prokind` 实测 `add_columnstore_policy = p`（过程，须 `CALL` 调用）、`add_compression_policy = f`（函数，自 2.18.0 起弃用），两函数并存以非弃用者为胜 → V402 压缩策略以 `CALL add_columnstore_policy('iot.iot_telemetry', INTERVAL '7 days')` 落盘；保留策略 `add_retention_policy` 实测 `prokind = f`（SELECT 函数，非 T-R3-2 比对项）照常调用。样例超表实证：CALL 后 `timescaledb_information.jobs` 落 `policy_compression` 作业、`SELECT add_retention_policy` 落 `policy_retention` 作业（均 scheduled=true）。TASK.md T-R3-2 行按登记台规则回填后删除。

## 2026-09-10 · PR #6 审查修复（Minor×2：脱敏正则数字边界 + 字典发布条件更新防双广播）

- F-1（fuyun-common/utils/SensitiveMasker.java）：PHONE/ID_CARD_15/ID_CARD_18 三正则补前后视数字边界（`(?<!\d)...(?!\d)`）——原实现对长数字串（12 位工单号/19 位雪花 ID 等）内部会误命中截断，与 javadoc「非目标长度不处理」承诺矛盾；SensitiveMaskerTest 补 12+/19 位数字串不脱敏断言（先 RED：19 位串现行实现被误脱敏，修复后 GREEN）。
- F-2（fuyun-system/service/impl/DictVersionServiceImpl.publish）：DRAFT 读-检-写改为条件更新原子抢占发布权（`UPDATE ... WHERE id=? AND status='DRAFT'`，MP 单表链式 A.4.3-13）——并发双 publish 原先双双通过前置校验并触发两次 AFTER_COMMIT 广播；现仅影响行数=1 者继续旧版本 DEPRECATED 与事务内事件，=0 抛 SYS-1013（竞态落败方不发事件）；DictVersionServiceImplTest 改条件更新语义并补「已发布版本重复 publish 不发事件」「竞态落败不发事件」两断言（先 RED 后 GREEN）。

## 2026-09-10 · PR-3 B3.4：workstation 登录页 + 主布局 + Axios 单例（前端接入认证链路）

- Axios 单例（src/api/http.ts，web A.3-1 唯一出网口）：baseURL = VITE_API_BASE_URL ?? '/api'、timeout 15s 模块级导出；请求拦截器注入 `Authorization: Bearer {token}`（useAuthStore 延迟到回调运行时调用，web B.3-1 组件外口径）+ 每请求唯一 `X-Trace-Id`（crypto.randomUUID，后端 TraceIdFilter 复用为 MDC 锚点并回写响应头）；响应拦截器统一错误出口——非 2xx 提取 ProblemDetail.detail 经 ElMessage 统一提示（缺省回退「请求失败」）、401 触发注册的未授权回调；`setUnauthorizedHandler` 回调解耦（http.ts 禁反向 import router，防循环依赖），拦截器内不落业务逻辑（A.3-2）。
- 认证 api 与后备类型：src/api/auth.ts 三类型化函数（login/refresh/logout，路径 /api/v1/system/auth/* 与后端 B3.2 契约对齐）+ src/types/auth.ts 手写后备类型 LoginRequest/LoginResponse/UserVO（web A.3-3 后备条款，文件头标注 openapi-typescript 生成物就位后由 packages/shared api.d.ts 承接并删除本文件；userId/orgId 按 Long→String 规则一律 string 承载，orgId 可 null）。
- 认证会话 store（src/stores/auth.ts，Pinia Setup Store web B.3-1）：token/refreshToken/user 三态 + sessionStorage 持久化（键 fy:workstation:auth，医疗工作站「换机即失效」语义：登录写回、登出清除、构造时恢复）+ getter isLoggedIn；action login（调 api→写 state→持久化）/logout（api 失败忽略→清 state→await 跳转 /login）/loadFromStorage；快照读入经 unknown 收窄类型守卫（损坏 JSON 丢弃并清残留键）；构造时注册 401 未授权回调（清会话 + 回登录页，登录页内重复导航跳过）。
- 路由与守卫（src/router/index.ts 改造）：/login（meta.public 免认证）+ / 主布局（MainLayout 懒加载）嵌套 '' home（HomeView 迁入，路由组件全懒加载）；beforeEach 只做认证判定（web B.3-2 分层）——默认拒绝：非公开路由未登录重定向 /login 并携带 redirect 回跳地址；已登录访问 /login 回首页防死循环；RouteMeta 声明合并增补 public 字段承载权限语义（权限点校验 P1 接入）。
- 视图层：LoginView（el-form 声明式校验必填 + 长度 4-64、提交 loading 防重复、错误提示走拦截器统一出口组件内不重复弹错、redirect 仅接受站内根相对路径防 open redirect、回车与按钮双提交入口、useTemplateRef 3.5 基线）；MainLayout（el-container 侧栏 + 顶栏 + 内容区三段骨架）；AppSidebar（el-menu 静态菜单：首页 + 占位分组文案，权限驱动菜单 P1 不接角色接口）；AppHeader（系统名 + el-dropdown 用户区显示 displayName、command=logout 走 store.logout 保证清 sessionStorage 并回登录页）。
- 单测（TDD 先行 RED 留证 → 实现 GREEN，vitest + @vue/test-utils + jsdom，ElMessage/网络全部 mock 不打真实请求）5 文件 17 例：http.spec 5（Bearer 注入与每请求唯一 X-Trace-Id、响应头透出、无令牌不注入、非 2xx 提示 detail 不触发登出、401 触发回调、缺 detail 回退文案）；stores/auth.spec 5（登录写 state+sessionStorage、登出清空并回登录页含 api 失败路径、isLoggedIn 翻转、会话恢复、损坏数据防御）；router.spec 3（public 直通、默认拒绝含回跳地址、已登录防死循环）；LoginView.spec 2（空提交被校验拦截、有效提交透传凭据并跳首页）；App.spec 冒烟因守卫失效同步改造为两条（未登录重定向 + 注入会话后首页可达）——因本次改动失效旧测试直接改造（简报 §4 / 全局 §四）。
- 测试基建表外申报两处（简报 §4 未列，实现必需）：① workstation vitest.config.ts 增 unplugin-vue-components + unplugin-auto-import 两件套（与 vite.config 同款）使单测真实装配 Element Plus 组件（表单校验/交互行为断言的前提），并经 server.deps.inline=['element-plus'] 内联其按需样式模块（Node 原生加载 .css 报错，交 Vite 管线按 css:false 桩化）；② tsconfig.app.json 增 skipLibCheck=true（Vue 官方脚手架标准配置）：element-plus 传递依赖 @vueuse/core 的 d.ts 引用标准 lib 外的 WebBluetooth 类型、其 dropdown 声明与 vue-tsc 3.3.10 存在库内兼容问题，跳过第三方声明文件自检，本项目源码类型检查不放宽。
- 杂项：workstation package.json dependencies 增 axios: catalog:（catalog 已锁 1.20.0，lockfile 同步更新并通过 --frozen-lockfile 校验）；删除 api/stores/types 三目录 .gitkeep（首个真实文件落地即删占位，PR-2 先例）。
- 门禁验证：cd web 后六门禁逐个真实执行全绿——pnpm lint（--max-warnings=0，0 错误）、pnpm format:check（All matched files use Prettier code style!）、pnpm type-check（workstation/portal/bigscreen/ui/shared 五项目 vue-tsc Done）、pnpm test（7 文件 19 例全过）、pnpm build（三应用 dist 产物构建成功）、pnpm audit --audit-level high（No known vulnerabilities found；本地 npmmirror 无 audit 端点，经官方 registry 执行，与 CI 同命令语义）。

## 2026-09-10 · PR-3 B3.3：审计切面 + practice/check 骨架 + 登录链路与字典广播端到端 IT

- 审计切面（BRIEF-PR3-01 §3.3）：api/AuditLog 注解（actionType，落 api 包为对外契约底座）+ internal/AuditLogAspect（@Around @annotation 环绕）——proceed 成功记 SUCCESS、任意异常记 FAIL 后原样 rethrow；fail_reason 经 SensitiveMasker 脱敏并 500 字符截断（V302 列宽防线），detail 请求参数摘要对口令/令牌入参显式打码（record 整对象 toString 会带出敏感明文，禁用）并 1000 字符截断；落库 try-catch 全吞仅 error 告警绝不阻断业务（M01 红线）；P0 同步写（controller 层、业务事务外、try-catch 告警），异步批量 P1（简报 §9-5）。注解落点：AuthController login/logout（LOGIN）、DictTypeController/DictVersionController 四写端点（WRITE）；practice/check 与字典读为查询 P0 不审计。
- 实现口径定案两处（简报未明确处，PR 描述同步申报）：① 免认证登录端点无 OperatorContextHolder 上下文，审计操作人回退取入参 LoginRequest 登录名作审计主体（AuthFlowIT 步骤 7 operator_id=admin 的语义来源），无上下文且无登录名入参兜底 system（与 created_by 系统操作口径一致）；② traceId 取 MDC（TraceIdFilter 前置必可用）、resource/client_ip 取 RequestContextHolder 当前请求（非 HTTP 线程兜底 unknown）。
- 审计配套：IAuditLogService/AuditLogServiceImpl（只增表唯一写入口，mapper.insert 单语句自原子不开方法级事务）+ AuditLogEntity/AuditLogMapper（无 @TableLogic、零 UPDATE/DELETE 路径）+ record/AuditLogEntry 参数 record（A.7-1）。
- fuyun-common utils/SensitiveMasker 通用脱敏工具（公共工具属 B.1 common 职责，禁业务散落正则）：maskPhone（11 位前 3 后 4，可嵌文本）、maskIdCard（18 位含 X 校验位/15 位老号前 6 后 4）、maskName（姓留名打星，单字/空白原样）、truncate（列宽截断统一收口）；组合使用约定"先证后机"（证号含长数字段，先掩证号再掩手机号防误插星）；9 例单测覆盖。
- practice/check 骨架：POST /api/v1/system/practice/check（M01 §7 路径原样，受 401 认证拦截，V303 权限点已登记）+ PracticeCheckRequest（JSR-303）+ PracticeCheckResponse（employeeId 字符串化出参）+ IPracticeService/PracticeServiceImpl（P0 骨架 passed=false 固定语义，javadoc 声明 P1 practice_grant 表 + EFFECTIVE 校验 + 30 天到期通知替换内部实现，响应契约不变）。
- 端到端 IT（fuyun-app，容器三件套与既有 IT 同款 + 测试资产假密钥）：AuthFlowIT 七步（错误口令防枚举 401 SYS-1001、admin 种子登录双令牌与 user.userId JSON 字符串、无令牌 401 SYS-1003 且 body.traceId 与响应头 X-Trace-Id 一致、携带令牌 practice/check 200 骨架响应、refresh 换发可用 + 登出后旧令牌 401、连续 5 次错密码第 6 次 SYS-1002 含解锁时间、JdbcTemplate 审计断言——LOGIN SUCCESS 行 operator_id=admin/trace_id=注入锚点/result=SUCCESS，FAIL 行 fail_reason/detail 不含口令明文）；DictBroadcastIT 六步 + B3.2 审核 M-4 负路径补断言（V5 登记行 ACTIVE、服务代理 publish 事务提交 AFTER_COMMIT 广播经真实 DictPublishedListener 消费落 PROCESSED 台账、event_registry 订阅自动登记含 system、已发布版本重发布 409 SYS-1013 事务回滚广播不出（以锚点事件证明台账终局恰 2 行）、同 eventId 手工重投 D-7 回查跳过行数仍为 1、业务读口径——无 version 返回当前 PUBLISHED、指定 version=1 返回 DEPRECATED）。
- 表外依赖申报：fuyun-system pom 增 spring-boot-starter-aop（BOM 托管）——@Aspect/@Around 编译期依赖 aspectjweaver；运行期自动代理由 fuyun-app 既有同款 starter（PR #4 起）装配，版本零声明。
- TDD 与验证：AuthFlowIT/DictBroadcastIT 先行 RED 留证（practice/check 404、审计表无行）→ 实现 GREEN；新增单测 5 类 21 例（SensitiveMaskerTest 9、AuditLogAspectTest 8、AuditLogServiceImplTest 1、PracticeServiceImplTest 2、PracticeControllerTest 1）先行 RED（编译失败留证）；全量 `mvn -B -ntp verify` 绿：单测 198（common 36 + integration 32 + system 129 + app 1）+ IT 21（SmokeStack 3 + MessagingGovernance 5 + AuthFlow 7 + DictBroadcast 6）全过；JaCoCo 双核心包 com.fuyun.system.service.impl / com.fuyun.integration.service.impl PACKAGE LINE 1.00 保持、各模块 BUNDLE ≥0.80（common 0.93/integration 0.98/system 0.999）；spotless:check 绿。

## 2026-09-09 · PR-3 B3.2：登录端点 + 401 拦截接线 + 字典管理与 dict.published 广播 + D-7 幂等回查改造

- D-7 改造（跨模块申报项，接口签名不变仅语义增强）：MessageIdempotencyServiceImpl.tryAcquire 在 Redis NX 抢占失败时回查 received_event 台账（(event_id, consumer_module) 唯一索引查询）——已有 PROCESSED 行才返回 false 跳过，无行则 warn 放行重新处理（前置键残留/上次处理中断场景），消除 TTL 窗口内重投被 NX 误判丢弃的消息丢失面；fuyun-common MessageIdempotencyService 接口 javadoc 同步契约；对应单测补 NX 失败回查命中跳过/回查未命中放行两分支，Redis 故障降级路径回归保留。
- 认证端点收口：IAuthService/AuthServiceImpl（login 防枚举 SYS-1001 同文案、locked_until 锁定校验 SYS-1002、停用 SYS-1006、bcrypt 校验 spring-security-crypto、recordLoginFailure/recordLoginSuccess 状态机；refresh/登出收敛令牌服务）+ IUserService/UserServiceImpl（findByLoginName/失败计数达 5 置锁定/成功清零续期）+ IRoleService/RoleServiceImpl（两步单表查角色编码，停用角色不入会话）+ AuthController 三端点（login/refresh 白名单免认证、logout 需令牌 204）+ AuthConverter（MapStruct）。ITokenService 按刷新/登出语义增补两方法（refreshAccessToken：同 sid 换发新 access 不轮换 refresh 值、失败统一 SYS-1005；logout(rawToken)：typ=access 校验后删会话键）——B3.1 三操作接口的语义内聚扩展，SessionData 线格式不变。
- 401 拦截接线：SystemWebConfig（fuyun-system config/，@EnableConfigurationProperties(SecurityProperties) + ITokenService/BCryptPasswordEncoder/AuthTokenInterceptor Bean + addInterceptors 注册 /api/v1/**，白名单仅 login/refresh——actuator/springdoc 不在 /api/v1/** 下天然不受拦截）+ fuyun-app SystemConfig @Import 接线（不放宽组件扫描）。
- 字典域：dict_type/dict_version/dict_item 三实体与 mapper + 四 service（类型创建 SYS-1014 唯一校验、版本创建默认 DRAFT 递增版本号、publish @Transactional 状态机 DRAFT→PUBLISHED 且旧 PUBLISHED 置 DEPRECATED + 事务内 Spring 应用事件 DictVersionPublishedEvent、条目新增仅 DRAFT 可维护、readPublished 契约型读豁免分页）+ 三个 controller（dict-types/dict-versions/dicts，权限点与 V303 种子 perm_code 对齐）+ DictConverter。发布事件经 SystemEventPublisher @TransactionalEventListener(AFTER_COMMIT) 事务提交后发 MQ（A.4.2-7 事务内禁发送），信封 codec + CorrelationData，注册 Confirm/Returns 回调（nack/不可路由 error 告警，P0 不自动重发）；DictPublishedListener @RabbitListener 消费走标准幂等范式（含 D-7 回查），队列经 MessagingGovernance 声明（q.system.system.dict.published，V5 种子已登记事件、订阅经声明构件自动补登记 system）。
- fuyun-system pom 表外依赖增补：spring-security-crypto（BCrypt，BOM 托管）、mapstruct（父 POM dependencyManagement 托管）、spring-boot-starter-amqp（RabbitTemplate）、fuyun-integration（仅消费 api 包 MessagingGovernance/ConsumerQueueSpec，B.2-2 允许）。

## 2026-09-09 · PR-3 B3.1：RBAC 迁移（V300–V303）+ D-2 HMAC 令牌/Redis 会话构件 + 认证拦截器骨架

- 按 BRIEF-PR3-01 §1/§2（B3.1 批次）落地：fuyun-system 新增 RBAC 迁移四件——V300 五核心表（sys_org/sys_user/sys_employee/sys_role/sys_permission）+ 两关联表（sys_user_role/sys_role_permission，RBAC0 最小胶水）、V301 字典三表（dict_type/dict_version/dict_item）、V302 审计只增表（无 updated_at 列/无触发器/无 deleted）、V303 幂等种子（ADMIN 角色 + 六个 P0 权限点（perm_code=API 路径，login/refresh/logout 免认证端点不登记）+ role_permission 全量绑定 + admin 账号（user_type=STAFF）与员工行（emp_no=ADMIN）及 user_role 绑定；口令仅 bcrypt 哈希字面量入库，初始口令红线注记随 P1 密码策略处置）。DDL 公共约定：雪花 ID 主键、审计列数据库维护、触发器复用 integration V1 公共函数 public.fuyun_set_updated_at()（跨 schema 复用）、部分唯一索引 WHERE deleted=0（11 个 uk_ 索引）、无外键；关联表 P0 只 INSERT/DELETE 故不挂触发器（注释明示）。
- 12 个枚举落 enums/（D-6 修宪后正文）：UserType/UserStatus/EmployeeStatus/OrgType/OrgAttr/OrgStatus/RoleStatus/DataScopeType/PermissionType/DictVersionStatus/AuditActionType/AuditResult，规范形态 = code 字段 + `@EnumValue`（MP DB 列映射；3.5.17 该注解仅支持 FIELD 目标，落字段非 getter，实现期实证）+ `@JsonValue`（JSON 输出 code）+ fromCode 双向映射（未知 code 抛 IllegalArgumentException）；另 api/ 包新增 SystemErrorCode 错误码枚举（SYS-1001~SYS-1006/SYS-1011~SYS-1014，implements common ErrorCode）。
- D-2 令牌构件：ITokenService/TokenServiceImpl（两段式 Base64Url(payloadJson).Base64Url(HMAC-SHA256) 线格式，JDK Mac + MessageDigest.isEqual 常量时间比较；校验链 = 两段格式→签名→exp→typ→会话存在→滑动续期；会话键 fy:system:session:{sid} 必带 TTL，值 = SessionData JSON（StringRedisTemplate String 序列化）；issue/verify/evict 三操作，角色摘要存会话不进令牌体）；SecurityProperties（record + @Validated：tokenHmacSecret @NotBlank @Size(min=32)、双 TTL @DurationMin，secret 仅承 FUYUN_SECURITY_TOKEN_HMAC_SECRET 环境变量，application.yml 空占位缺失即启动 fail-fast）；SecurityConstants（claims 短键/令牌类型/Bearer/锁定阈值 5 与 30 分钟/会话键前缀）；AuthTokenInterceptor 骨架落 internal/（401 ProblemDetail 手工渲染与全局同构 {type,title,status,detail,errorCode,traceId}，OperatorContextHolder set/afterCompletion clear；MVC 注册白名单归 B3.2）；线格式载体四 record（TokenClaims/SessionUser/SessionData/TokenPair）落 record/。
- 实体与 mapper：七实体（@TableName("system.sys_xxx")、ASSIGN_ID、@TableLogic、状态列用枚举类型）+ 七 mapper（@Mapper 注解被既有 @MapperScan 扫描）；fuyun-system pom 新增四依赖（mybatis-plus-spring-boot3-starter/mybatis-plus-jsqlparser/data-redis/validation，版本全 BOM/父 POM 托管零声明；amqp/spring-security-crypto/mapstruct 随 B3.2 按需增补）。
- 表外变更申报（BRIEF-PR3-01 CONCERNS-3）：deploy/.env.example 增 FUYUN_SECURITY_TOKEN_HMAC_SECRET 键（空占位 + 独立行中文注释）+ deploy/docker-compose.yml backend environment 增同名透传行（docker compose config 校验通过）。
- TDD 与验证：令牌/拦截器/属性三测试类先行 RED（编译失败留证：constants/properties/record/service.impl 包不存在）→ 实现 GREEN；全量单测 109 例绿（fuyun-common 27 + fuyun-system 50 + fuyun-integration 31 + fuyun-app 1，本批新增 46 例：SystemEnumsTest 24 参数化 + SystemErrorCodeTest 2 + TokenServiceImplTest 10 + AuthTokenInterceptorTest 5 + SecurityPropertiesTest 5）；`mvn -B -ntp spotless:check` 绿；迁移重放验证：全新 timescale/timescaledb:2.29.2-pg16 容器 + 应用 classpath Flyway 执行 V1→V303 共 9 迁移全 success，11 表落位 system schema，种子（角色 1/权限 6/绑定 7/用户 1/员工 1）与触发器、uk_ 索引逐一核对通过。

## 2026-09-09 · 宪法 v1.3 → v1.4 修订（D-6）：枚举包目录 `enum/` 更名 `enums/` + system 域迁移号段占用登记

- 修订来源：TASK.md D-6 待决策项（P0 PR-1 审核发现）——`enum` 是 Java 保留字，`package com.fuyun.{domain}.enum` 无法编译，首个枚举类落地（PR-3）前必须裁决。用户未响应裁决询问，按推荐项默认裁决执行（2026-09-09）：枚举包目录定名 `enums/`；若后续改判（如 `enumeration/`），改动面 = 目录名 + 包名 + 宪法正文，一次替换可回收。
- 修订范围（backend/AGENTS.md 三处正文 + 20 个模块目录更名，原子修宪）：① A.2-7「定义于 enum/ 包」改为「定义于 enums/ 包」；② B.1 模块内包职责表 `constants/ enum/` 行改为 `constants/ enums/`；③ C.3 目录树 `config/ properties/ constants/ enum/ exception/` 行同步改为 `enums/`。全部 20 个业务模块 `src/main/java/com/fuyun/{domain}/enum/.gitkeep` 占位目录以 git mv 语义更名 `enums/.gitkeep`（含 fuyun-integration——该模块 enum/ 仍为占位，实际类型处理器落 handler/，不受影响）。
- 号段占用登记（TASK.md W-4 载体要求，BRIEF-PR3-01 §2.1）：system 域（M01）占用 **V300–V399**，本批（PR-3 B3.1）使用 V300–V303——V300 RBAC 五核心表 + 两关联表、V301 字典三表、V302 审计日志表（只增）、V303 RBAC 种子（ADMIN 角色 + P0 权限点 + admin 账号）。依据：M01 非公共治理域，其表仅落 system schema，无「先于全部业务迁移」的硬需求；Flyway 多目录按版本全局排序，system 的 V300 天然晚于 integration 的 V1 公共触发器函数，依赖安全（号段归属 CI 自动校验仍按 W-4 待办补建）。
- 影响范围：backend/AGENTS.md 三处、20 模块 `enum/` 目录名；宪法版本 v1.3 → v1.4。

## 2026-09-09 · 宪法 v1.2 → v1.3 回补：handler/ 包目录与 jacoco constants 排除

- 修订来源：PR-2（M20 治理构件）执行期 B2.2 / B2.3 批次的审核申报与核验结论——两项偏差经批次审核确认合法并定案，本次将正文回补到位，消除「CHANGELOG 已记、正文滞后」状态（B2.2 / B2.3 条目内为表外申报记录，本节为正文定稿登记）。
- 修订一（B.1 模块内包职责表新增 `handler/` 行，C.3 目录树同步）：fuyun-integration 落地 `com.fuyun.integration.handler.UuidTypeHandler`（BaseTypeHandler\<UUID\>，pgjdbc 原生 setObject/getObject 绑定），经 `mybatis-plus.type-handlers-package` 全局注册。职责边界：「MyBatis TypeHandler（类型处理器）：java 类型↔JDBC 列值转换（如 UUID、JSON 列）；经 mybatis-plus.type-handlers-package 全局注册，禁止散落注解指定」。理由：MyBatis 无内置 UUID TypeHandler，B2.2「UnknownTypeHandler→setObject 原生写入」推定被 B2.3 端到端 IT 复验证伪后的修复产物（见下两条申报记录）；归位数据层配套，与 mapper/entity 同层不出数据层，后续模块 uuid 列零成本复用，目录形态需入宪法避免各模块私设散落。
- 修订二（C.5-2 覆盖率排除清单追加 `constants/**`）：排除清单由「排除 config/dto/entity/Application/生成代码」扩为「排除 config/dto/entity/constants/Application/生成代码」。理由：常量类仅私有构造器 + `public final static` 字面量（A.2-6），无可执行业务分支，与 config/properties 同语义不可测；父 POM jacoco excludes 已随 B2.2 落地 `com/fuyun/**/constants/**`，本次正文对齐。
- 影响范围：仅 backend/AGENTS.md 三处（B.1 表、C.3 树、C.5-2）；不触及 D-6/D-7 等待决策项；宪法版本 v1.2 → v1.3。

## 2026-09-09 · PR-2 B2.3：首批事件名 + CF-7 遥测模型登记 + 消息治理端到端 IT

- 按 BRIEF-PR2-01 §4（B2.3 批次）落地：fuyun-system api/ 新增 CF-2 五个主数据事件占位载荷 record（system.dict.published / system.org.changed / system.user.changed / system.param.changed / system.practice.changed，事件名全部为 M01 Spec §7 明示，事件对象落发布方 api 包为 B.3-1 红线，均标注"占位 schema：正式字段随 PR-3 M01 实装冻结"）；fuyun-common 新增 `com.fuyun.common.messaging.StandardTelemetryMessage`（CF-7 标准遥测消息模型七字段 record：deviceId/metricCode/value/unit/occurredAt/quality/source，对齐 M14 FU-M14-05"四路同构"，P0 只登记不消费——不建 SPI 接口与任何 iot 消费代码，SPI 与消费链路归 PR-4）；Flyway V5 种子迁移七行（id 1=CF-1 信封约定行 integration.convention.event-envelope、id 2-6=五个 system.* 事件、id 7=CF-7 行 iot.telemetry.message，status 全 ACTIVE，CF-1/CF-7 登记行形态为简报 §4.3/§8-2 推导定案待终验核对）。
- 端到端 IT：fuyun-app 新增 `MessagingGovernanceIT`（Testcontainers 三容器与 SmokeStackIT 完全同款 tag 与 rabbitmq.conf 挂载，独立声明不改 SmokeStackIT），五步断言打通「冻结登记 → 发布→消费（信封线格式 + payload 线格式）→ 重复投递被幂等拦截（Redis NX 前置 + received_event 唯一行）→ poison 帧经有界重试耗尽进 fy.dlx 死信落库（PENDING，source_queue/routing_key/event_id/payload_body/fail_reason）→ 构件副作用（订阅自动登记 it + 死信统一队列声明）」；同链路复验 B2.2 申报①的 UUID 真库写入（received_event 真实 insert）；不含 fy.delay TTL 时延断言（T-R3-4 口径），不新增 awaitility 等测试依赖。
- 表外最小补充四处（PR 描述申报）：① fuyun-system pom 增 `spring-boot-starter-test`（test scope，BOM 托管）——SystemMasterDataPayloadTest 线格式冻结断言的必要测试依赖；② 新增 `StandardTelemetryMessageTest`（fuyun-common test）——CF-7 模型为冻结契约载体，补七字段语义与 JSON 往返断言（与 EventEnvelopeTest 同型，简报 §4 未列、按全局"测试与实现同提交"补齐）；③ fuyun-app pom 增 `fuyun-system` 模块依赖（compile）——IT 以发布方 api 真实载荷发布 system.dict.published 打通契约链路，装配模块聚合业务模块属 B.1 既有职责，与 B2.1 增 fuyun-integration 依赖同型，PR-3 M01 实装后由装配刚需承接；④ 删除 fuyun-system api/.gitkeep（简报 §0-6：首个真实文件落地即删占位）。
- IT 首跑修复一处 B2.1/B2.2 遗留启动缺陷（verify 首次真实跑 IT 即暴露，PR 描述申报）：`MessagingProperties.idempotencyRedisTtl` 的 `@Positive` 对 `Duration` 无内建校验器——上下文启动期抛 HV000030（UnexpectedTypeException）致 SmokeStackIT/MessagingGovernanceIT 全量红灯；此前批次仅跑单测（记录直接构造、容器只做 Flyway 重放），未真实装配 Properties Bean 故未暴露。修复：替换为 Hibernate Validator 专属 `@DurationMin(nanos = 1)`（语义等价"必须为正"，A.2-2 启动期校验红线不松动）；时间边界取舍：纳秒下界表达严格大于零，毫秒级下界会误拒合法亚毫秒配置。
- IT 首跑修复一处 B2.1 消费承接设计缺陷（PR 描述申报）：Spring AMQP 3.2 监听链路对任何方法签名都先经转换器提取 payload（无 raw 旁路，字节码实证），默认 `TYPE_HEADERS` 优先级下 `__TypeId__=EventEnvelope` 不在受信包白名单（仅 java.util/java.lang）致全部消费帧转换失败；且 Jackson 2.19 `readValue(json, String.class)` 对 JSON 对象节点抛 MismatchedInputException——"String 参数直接承接"在 Jackson 转换器下不成立（本机实证）。修复（简报 §4.4"String 承接"落地形态修正，CF-1 语义不变）：MessagingGovernanceConfig 新增项目标准消费容器工厂（覆盖 Boot 默认同名 `rabbitListenerContainerFactory`，经 Boot configurer 装配 AUTO 确认/有界重试/defaultRequeueRejected=false 姿态不变，仅 payload 承接换 `SimpleMessageConverter`——任意报文兜底 byte[] 永不抛转换异常，防死信二次转换失败回环）；消费方一律 raw `Message` 参数（容器经 providedArgs 注入原始帧）+ UTF-8 解码为 String + codec.fromJson——"原文进 codec、__TypeId__ 不作消费依据"按 CF-1 冻结约定落地；DeadLetterListener 注解零改动即随新工厂获得毒丸安全链路。
- IT 首跑修复一处 B2.2 幂等台账写库缺陷（PR 描述申报）：B2.2 申报①"received_event.event_id（PG uuid 列）经 UnknownTypeHandler→setObject 原生写入"推定被端到端 IT 复验证伪——MyBatis 在 insert 参数映射构建期即抛 "Type handler was null on parameter mapping for property 'eventId'（javaType java.util.UUID）"，根本未达 JDBC 层（同链路 dead_letter 全 VARCHAR 列落库正常，形成测试 2/3 红、测试 4 绿的定位信号）。修复：新增 `com.fuyun.integration.handler.UuidTypeHandler`（BaseTypeHandler<UUID>，pgjdbc 原生 setObject/getObject 绑定）并经 application.yml `mybatis-plus.type-handlers-package` 全局注册（insert 参数与 wrapper 查询条件两侧生效，后续模块 uuid 列零成本复用）；配套 UuidTypeHandlerTest 三用例（原生绑定/三通道读取/SQL NULL 透传）。目录申报：handler/ 为宪法 B.1 包职责清单的扩展（数据层配套，与 mapper/entity 同层不出数据层）。
- TDD：IT + 两单测先行编写跑 RED（载荷 record / 遥测模型缺失致编译失败留证）→ 补齐五 record、StandardTelemetryMessage 与 V5 迁移后 GREEN；验证 = `mvn -B -ntp verify` 全绿（surefire 全量单测 + failsafe SmokeStackIT/MessagingGovernanceIT + JaCoCo 双阈值：`com.fuyun.integration.service.impl` 核心包 PACKAGE LINE 1.00 首次真实生效 + 各模块 BUNDLE ≥ 0.80）+ `mvn -B -ntp spotless:check` 绿。

## 2026-09-09 · PR-2 B2.2：received_event 幂等构件 + dead_letter 死信落库告警

- 按 BRIEF-PR2-01 §3（B2.2 批次）落地：fuyun-common 新增 `com.fuyun.common.messaging.MessageIdempotencyService` 契约接口（tryAcquire/recordProcessed/release 三方法 + 标准消费范式 javadoc，接口沉 common 为 M-3 裁决载体）与 `ReceivedEventRecord` 参数 record；fuyun-integration 落地 `MessageIdempotencyServiceImpl`（Redis SET NX PX 前置去重，键 `fy:integration:idempotency:<module>:<eventId>`、TTL 取 fuyun.messaging.idempotency-redis-ttl、Redis 故障降级放行由唯一索引兜底 + `DuplicateKeyException` 吞为已处理、其他 DB 异常原样上抛 + release 失败释放前置键）与 `internal/DeadLetterListener`（@RabbitListener 监听 q.integration.dead-letter + 容器 AUTO，raw Message 承接毒丸不做 JSON 转换，x-death 轨迹解析 + SHA-256 摘要 + 信封合规校验（不合规两列置空并标注留痕），落库失败 error 告警不抛防毒丸无限循环）；`ReceivedEvent`/`DeadLetter` 实体与 mapper + Flyway V3/V4 迁移（received_event 表：event_id+consumer_module 唯一索引；dead_letter 表：payload 落「原文全文+SHA-256 摘要」两列，简报 §8-6 定案口径）。
- 装配与门禁配套：MessagingGovernanceConfig @Import 追加幂等实现与死信监听两类（简报 §2.7 预告的 B2.2 追加）；MessagingConstants 增 `IDEMPOTENCY_KEY_PREFIX`/`HEADER_X_DEATH`（A.2-6 Redis 键前缀与消息头词表）；父 POM jacoco 规则二 includes 追加 `com.fuyun.integration.service.impl`（核心包 PACKAGE LINE 1.00 首个真实生效包，DoD 第 2 条）+ excludes 追加 `com/fuyun/**/constants/**`（宪法 C.5-2 排除清单扩展项，PR 描述申报，§8-7）。
- 实现口径定案两处（简报未明确处，PR 描述同步申报）：① received_event.event_id 列为 PG UUID 类型，实体字段取 `java.util.UUID`——MyBatis 无内置 UUID TypeHandler，经 UnknownTypeHandler→ObjectTypeHandler→`ps.setObject` 走 pgjdbc 原生 UUID 支持（insert 路径成立，B2.3 端到端 IT 复验真实插入）；② 死信监听器落库失败 catch 范围取 `RuntimeException` 兜底（比简报"DB 故障"更宽），覆盖一切运行时异常防毒丸回环，error 日志即为 M20 §10 告警通道。
- TDD：新增 2 测试类 11 用例（幂等 7 场景 + 死信 4 场景）先 RED（测试先行编译失败留证）后 GREEN，测试与实现同提交；验证 = `mvn -B -ntp test` 全绿 + `mvn -B -ntp spotless:check` 绿 + V1→V4 迁移对全新 timescale 容器库 Flyway 重放留证（同 B2.1 方式）。

## 2026-09-09 · PR-2 B2.1：事件信封 + Long→String 序列化 + 队列声明构件 + event_registry（CF-1 冻结载体落盘）

- 按 BRIEF-PR2-01 §2（B2.1 批次）落地：fuyun-common 新增 `com.fuyun.common.messaging` 包（EventEnvelope 七字段信封 record = CF-1 冻结形态 + EventEnvelopeCodec 时钟注入工厂 / 线格式编解码与消费侧合规校验）与 `com.fuyun.common.config.JacksonLongToStringConfig`（Long/long → String 全局定制唯一注册点，backend 宪法 A.3-8）；fuyun-integration 落地消息治理构件（api/ 三件契约 MessagingGovernance/ConsumerQueueSpec/DelayQueueSpec + QueueGovernorImpl + IEventRegistryService 登记服务 + EventRegistry 实体与 mapper + MessagingConstants/MessagingProperties/MessagingGovernanceConfig）与 Flyway V1/V2 迁移（公共审计触发器函数 fuyun_set_updated_at + integration.event_registry 表）；fuyun-app 装配（MessagingConfig @Import 两配置类、MybatisPlusConfig @MapperScan + 三大插件）与 application.yml / application-test.yml 追加键（消费端有界重试 + fuyun.messaging.idempotency-redis-ttl，test 快速重试覆盖）。
- Flyway 号段登记（BRIEF-PR2-01 §2.5）：integration 治理域占用 V1–V99（本批用 V1–V2）；V100–V199 患者域、V200–V299 医嘱域为宪法例举既定；V300–V399 系统域、V400–V499 物联域为建议分段（PR-3/PR-4 拟用）；V500 起按实装先后递增分配、先登记先占（载体 = 各 PR 简报 + 本文件）。号段归属 CI 自动校验（宪法 A.4.1-2）本批不补建，登记 TASK.md 待办。
- 实现口径定案三处（简报未明确处，PR 描述同步申报）：① EventEnvelopeCodec Bean 的装配落点简报未指明（§2.2 仅称"Bean"），随 MessagingGovernanceConfig @Import 一并装配——发布/消费共用，且 B2.2 死信监听依赖其 Bean 化；② fuyun-app pom 新增 fuyun-integration 模块依赖——§2.10 要求 MessagingConfig @Import(MessagingGovernanceConfig) 需编译期可见，与 §1.2 "fuyun-app pom 零新增"表述冲突，以装配要求为准（模块内部依赖，版本随 ${project.version}，非表外三方依赖）；③ MessagingConstants 补 QUEUE_TYPE_QUORUM / BINDING_KEY_ALL 两常量承载 quorum 队列类型值与死信全量绑定键，防魔法值散落（A.2-6）。
- TDD：新增 5 测试类（EventEnvelopeTest / EventEnvelopeCodecTest / JacksonLongToStringConfigTest / QueueGovernorImplTest / EventRegistryServiceImplTest）先 RED 后 GREEN，测试与实现同提交；验证 = `mvn -B -ntp test` 全绿 + `mvn -B -ntp spotless:check` 绿 + V1/V2 迁移 Flyway 重放留证（B2.1 不跑 failsafe，SmokeStackIT 随 B2.3 verify 回归）。

## 2026-09-09 · PR #4 审查修复（Finding 1/2）：全局异常渲染装配缺失 + prod springdoc 兜底

- Finding 1（Critical，独立审查发现，本次核实成立）：`GlobalExceptionHandler` 从未注册为 Bean——`@SpringBootApplication` 默认仅扫 `com.fuyun.app.*`，`com.fuyun.common.web` 包在扫描范围外，common 无 AutoConfiguration.imports / spring.factories，TraceIdConfig 也未引入它，@RestControllerAdvice 装配缺失导致 ProblemDetail + errorCode/traceId 统一契约运行时未生效（装配级死代码）。修复：fuyun-app 的 `TraceIdConfig` 增加 `@Import(GlobalExceptionHandler.class)`（装配归 app，宪法 B.1；不放宽 scanBasePackages），并新增 @WebMvcTest 切片注册测试——探针 controller 抛业务异常，断言响应为 RFC 9457 ProblemDetail 且状态码/errorCode/traceId/响应头回写齐全；TDD 先 RED（无 @Import）后 GREEN。
- Finding 2（Minor，采纳）：`application-prod.yml` 预置 `springdoc.api-docs.enabled=false` 与 `springdoc.swagger-ui.enabled=false`（宪法 A.3-7 Swagger UI 仅 dev/test）。P0 未引入 springdoc，两键当前为无害冗余安全开关；首个 REST 端点引入 springdoc（锁 2.8.17）时自动兜底，防 prod 误开 API 文档。与「无消费方不写键」的口径区别：此为安全默认值声明而非业务配置，注释已说明动机。
- 本次不修订宪法正文；验证：新增测试 RED→GREEN 过程留证 + `mvn verify` 全绿 + `spotless:check` 绿。

## 2026-09-09 · PR-1 B1.4：web pnpm monorepo 三应用脚手架落盘 + web/Dockerfile 运行层取产物修正

- 按 BRIEF-PR1-01 §5 落地 web monorepo：根配置五件（`package.json` 七脚本 + packageManager 锁定 pnpm@12.3.4、`pnpm-workspace.yaml`（apps/*/packages/* + catalog 共享工具链版本表，与 web 宪法 C.2 一致）、`eslint.config.mjs`（withVueTs 组合：flat/essential → recommendedTypeChecked → eslint-config-prettier 置尾，--max-warnings=0 门禁）、`.prettierrc`（printWidth 100 / singleQuote / trailingComma all / endOfLine lf）、`vitest.config.ts`（projects 聚合三应用））；三应用脚手架（workstation / portal / bigscreen：package.json、index.html、vite.config（/api 无 rewrite + /ws ws:true、resolve.alias `@` 与 tsconfig paths 同步）、solution tsconfig 三件（strict 手写不引 @vue/tsconfig）、.env.example、vite-env.d.ts（ImportMetaEnv）、B.1 目录基线 .gitkeep 占位、懒加载首页路由 + 极简 HomeView + 各一个真实断言冒烟单测）；`packages/shared`（纯 TS 分页契约 `PageResult<T>`，禁依赖 vue/element-plus）与 `packages/ui`（`export {}` 中文占位说明 + vue peerDep，本批不引 element-plus）；`pnpm-lock.yaml` 入库。workstation 依赖差异：element-plus 2.14.5 + unplugin-vue-components 32.1.0 + unplugin-auto-import 21.1.0 + dayjs 显式声明（web B.3-6）。
- web/Dockerfile 修正（W-3 对齐项②）：运行层三行 COPY 补 `--from=build`——产物生成于构建层容器内且 `.dockerignore` 已排除 `**/dist/`，原写法必然构建失败；其余（node:24 + corepack pnpm@12.3.4、nginx:1.30.4、注释）不动。
- 表外版本按 BRIEF-PR1-01 §9 默认方案锁定并在 PR 描述申报（合入后回补宪法 C.2 版本表与定稿表）：`@vitejs/plugin-vue 6.0.8`（官方 peer 声明支持 vite ^8.0.0，registry 已核实）、`dayjs 1.11.23`（element-plus 2.14.5 依赖范围 ^1.11.20 内，安装后 `pnpm why dayjs` 复核，T-R4-4 结论供回填）、`@types/node 24.13.3`（tsconfig.node.json `types:["node"]` 简报显式要求，需类型包落盘；24.x 线对齐 Node 24 运行时）。
- 实现口径裁决一处：`PageResult<T>.total` 按 web 宪法 A.3-6 / backend A.3-8（Jackson 全局 Long→String）以 `string` 承载，简报 §5.4 行内 `total: number` 为笔误，以宪法为准（本条即冲突报告）。
- 卫生配套补 `.prettierignore`（dist/、auto-imports.d.ts、components.d.ts、coverage/、pnpm-lock.yaml、`*.md` 不进格式门禁）：unplugin 生成物与构建产物不入库且内容非人工维护，进入 prettier --check 会让本地构建后格式门禁永红；pnpm-lock.yaml 内容经哈希校验格式化无意义；`*.md`（web/AGENTS.md 宪法）为手工维护文档，排除以避免每次全量 format 的重排 churn（实测 prettier 会重排宪法表格缩进）；根 `.gitignore` 追加 `auto-imports.d.ts`、`components.d.ts` 两行（简报 §5.6），与 vite.config dts 输出路径一致避免 git status 永脏。
- 简报字段外最小补充三处（均随本批落地并在 PR 描述申报）：根 package.json 补 `"type": "module"`（消除 Vite 原生配置加载器对根 vitest.config.ts 的「ESM 语法按 CommonJS 加载」警告，与三应用清单一致）；三应用 package.json 补 `@types/node`（表外申报项，tsconfig.node.json `types:["node"]` 消费方在各 app，根不声明）；根 `web/tsconfig.json`（include 仅根 vitest.config.ts——根级配置文件不归属任何 app 的 tsconfig 项目，无归属项目时 ESLint 类型感知规则报「file not found by the project service」，补根项目后 lint 全绿）。
- 门禁配套修正两处（本批验证暴露，证据驱动）：① `.pre-commit-config.yaml` 的 `check-yaml` 钩子增加 `exclude: web/pnpm-lock.yaml`——pnpm lockfile 为多文档 YAML（`---` 分隔），check-yaml 单文档断言必然误报，不排除则 lockfile 无法入库（简报 §5.6 硬性要求入库）；② 三应用 `tsconfig.node.json` 补 `target: ES2022` + `lib: ["ES2023"]` + `skipLibCheck: true`——简报手写 spec 未列 target（默认 ES5）导致 node_modules 声明文件私有字段报 TS18028，且 unplugin/vitest 的 d.ts 引用可选框架类型需 skipLibCheck 抑制（与 create-vue 生态标准配置一致）；app 项目 tsconfig 验证无需该补丁。
- 审核修复（修复循环第 1 轮）：`PageResult<T>.page` 注释由「从 1 起」修正为 0 基契约语义——backend A.3-6 明文「请求 page（0 基，必须显式告知前端）」，响应回显请求值；仅改注释不动类型结构（`page: number` 不变）。
- 终审修复（缺陷 I-1，修复循环第 1 轮）：三应用 vite.config.ts 补 `base`（workstation/portal/bigscreen 分别为 `/workstation/`、`/portal/`、`/bigscreen/`，与 nginx fuyun.conf 子路径 alias 一一对应），三应用 router 改 `createWebHistory(import.meta.env.BASE_URL)`。根因：简报 §5.3 vite.config 规格未列 base（规格缺口）——默认 base `/` 使构建产物资源引用为绝对路径 `/assets/*`，在 nginx 子路径挂载下 JS/CSS 全部 404（三前端白屏）；router 硬编码 `createWebHistory()` 同样无法感知子路径。方案：base 与路由以 `import.meta.env.BASE_URL` 同源联动（web A.2-5 产物路径与部署挂载耦合的延伸约束）；dev 模式应用即服务于该子路径属预期。

## 2026-09-09 · PR-1 B1.3：deploy/ 全量编排落盘 + ci.yml 骨架期排除项移除

- 按 BRIEF-PR1-01 §4 落地 deploy/ 五件套：`docker-compose.yml`（postgres/redis/rabbitmq/minio/backend/nginx 七服务 + iot-simulator `profiles:["sim"]`；镜像 tag 全锁定禁 latest；healthcheck 参数照定稿报告 §6.3（统一 interval 10s/timeout 5s/retries 5，rabbitmq start_period 90s、backend 120s、nginx retries 3）；depends_on 全部 `service_healthy` 禁裸 service_started；backend 不发布宿主端口并追加 `stop_grace_period: 40s` 对齐 yml `timeout-per-shutdown-phase: 30s`（backend A.5-15））；`.env.example`（23 键全清单：PG 4 + Redis 2 + RabbitMQ 4 + MinIO 3 + IoTDA 6 + 应用 4，值只留占位与中文注释）；`postgres/initdb/01-init.sql`（仅 `CREATE EXTENSION IF NOT EXISTS timescaledb`，业务库由 POSTGRES_DB 环境变量承担，业务 DDL 全归 Flyway）；`rabbitmq/rabbitmq.conf`（`default_queue_type = quorum`，总 Spec D1）；`nginx/fuyun.conf`（三前端静态路由 + `/api` 反代保留前缀 + `/ws` WebSocket 升级 + `/healthz` 静态 200）。
- ci.yml changes job 移除四处骨架期排除行（`!backend/Dockerfile`、`!backend/.dockerignore`、`!web/Dockerfile`、`!web/.dockerignore`）及对应两条注释，Dockerfile/.dockerignore 变更恢复镜像构建触发；pom/webpkg 存在性守卫保留（paths-filter 误判兜底，见 2026-09-08 CI 首跑修正条目）；job name 一律未动（分支保护 required checks 精确对齐）。
- 实现口径修正三处（简报/定稿示意片段笔误或环境差异，随本批落地）：① compose 内部挂载点相对路径以 compose 文件所在 deploy/ 目录为基准，简报表中 `../postgres/initdb`、`../rabbitmq/`、`../nginx/` 修正为 `./postgres/initdb`、`./rabbitmq/`、`./nginx/`（`../` 写法会解析到仓库根导致挂载落空；`../web/apps/<app>/dist` 不变，W-3 裁决路径）；② .env.example 注释一律独立成行、不用行内 `#`——compose `env_file:` 注入容器环境沿用 docker env-file 格式，无行内注释语义，行内 `#` 会混入变量值；③ postgres healthcheck 的 `$POSTGRES_USER/$POSTGRES_DB` 写作 `$$` 转义——compose 会先对 yml 全文做变量插值，未转义时探针取 .env 插值而非容器内环境，`$$` 使容器内 shell 从 `environment:` 块展开，语义一致但不再依赖插值时序。
- rabbitmq:4.3.5-management 镜像实证（本机 `rabbitmqctl list_users` 验证）：`RABBITMQ_DEFAULT_USER/PASS` 环境变量在该 tag 上仍由服务端生效（入口脚本不再转换但种子用户正常创建），简报的凭据注入方案可用，无需改用配置文件承载口令（口令入库即红线）。
- 审核修复（修复循环第 1 轮，PR-1 收尾全栈验证发现）：nginx healthcheck 探针 `/dev/tcp/127.0.0.1:80` 为无效 bash 网络重定向语法——`/dev/tcp` 须为 `/host/port` 斜杠形态，冒号形态被 shell 当字面路径（实测报 `No such file or directory`），探针必然失败使 nginx 恒 unhealthy，违反「七服务全 healthy」验收。根因：定稿报告 §6.6 片段（FJ-02）本身即此写法，属规格带病照抄。方案：保持 bash `/dev/tcp` 形态仅修正为 `/dev/tcp/127.0.0.1/80`（精准修改，不换探针方案；已在 nginx:1.30.4 容器内实测修正后探针对 fuyun.conf 的 /healthz 返回 200，并经 compose 全栈验证 nginx 转 healthy）。备查记录：nginx:1.30.4 镜像实测含 /usr/bin/curl（8.14.1），定稿 FJ-02「官方镜像无 curl/wget」前提已过时，后续升级镜像时可评估改用 curl 探针简化写法，本批不换。
- 终审修复（全分支终审 M-1/M-2，两处 Minor）：① M-1 ci.yml frontend job audit 步骤 `pnpm audit` 改为 `pnpm audit --audit-level high`——pnpm 内建命令优先于 web/package.json 同名 script，裸命令不带 script 中的阈值参数，实际按任意级别阻断，把 web 宪法 C.5-5「high 及以上阻断」语义放大（功能偏保守但非宪法约定语义），改为与 C.5-5 字面一致消除解析歧义；② M-2 compose backend `FUYUN_DATASOURCE_URL` 追加 `?reWriteBatchedInserts=true`——env 变量存在会覆盖 application-dev.yml 内含该参数的默认值，致批量写优化静默失效（backend A.4.2-6 要求全环境 JDBC URL 携带），.env.example 库名注释处同步补「生产/自定义 URL 需自带该参数」提示。
- CI 修复（PR #4 images job 首跑失败，backend/web 两 matrix 同报 `Cache export is not supported for the docker driver`）：images job「构建镜像」步骤前增加 `docker/setup-buildx-action@v3`——job 未安装 buildx 时 docker/build-push-action 落在 Docker 内建 docker driver 上，该 driver 不支持 `cache-from/cache-to: type=gha` 的缓存导出；骨架期 Dockerfile 变更被排除、构建步骤从未真实执行，PR #4 首跑暴露此潜伏缺陷。该 action 缺省创建 docker-container driver 的 builder，支持 gha 缓存导出；matrix、步骤条件与缓存 scope 一律未动。验证边界：本地仅 actionlint 语法校验可用，buildx 创建与 gha 缓存链路无法本地模拟，由 CI 复跑验证。
- CI 修复（PR #4 合并被分支保护阻断，images 审查修复循环第 2 轮）：images job 去 matrix 化——GitHub 对 matrix job 展开的 check run 名带维度后缀（PR #4 真跑实测展开为 `images (backend, backend, backend/Dockerfile)` 与 `images (web, web, web/Dockerfile)`），而 main/dev 分支保护 required check 为聚合名 `images`（方案 B 五 checks 模型）；骨架期构建步骤从未真跑、跳过态 check 名恰为聚合名故从未暴露，真跑时聚合名消失致 required check 永不满足、mergeStateStatus=BLOCKED。方案：去掉 `strategy.matrix`，改同 job 内两个显式构建步骤（backend/web），每步固定携带原 matrix 条目等价参数（context/file/tags/cache-from/cache-to scope 全部字面量化，不再引用 matrix 上下文），步骤级 if 沿用原条目触发逻辑（对应侧变更 && 对应 verify success）；job 级 if/name/permissions/needs 未动；两步顺序执行天然等价原 fail-fast 语义（一步失败 job 即败、后续步骤默认跳过）。PR-4 的 iot-simulator 第三镜像原「第三条 matrix」表述等价转换为「追加第三个同构构建步骤」（TODO 注释同步改写，禁改回 matrix，否则 required check 名再度失配）。本地验证仅 actionlint 可用，check run 展开行为无法本地模拟，由 push 后 CI 与分支保护 mergeable 状态验证。

## 2026-09-09 · PR-1 B1.2：冒烟集成测试 + backend/Dockerfile COPY 策略重写

- 按 BRIEF-PR1-01 §3 落地 B1.2：① fuyun-app 新增 `SmokeStackIT`（fuyun-app 下唯一 `*IT`）——Testcontainers 拉起与 compose 同 tag 的三容器（timescale/timescaledb:2.29.2-pg16、redis:8.10.1、rabbitmq:4.3.5-management，backend 宪法 C.5-4），@ServiceConnection 注入连接，test profile 启动 fuyun-app 完整上下文，依次验证 Flyway 首跑建表（flyway_schema_history 落 public schema）、Redis 读写一回合（TTL 生效）、RabbitMQ 队列声明与一帧收发（CF-1 最小验证）；RabbitMQ 容器经 test 资源挂载 `default_queue_type=quorum` 与 B1.3 compose 的 rabbitmq.conf 同语义。② backend/Dockerfile 构建层 POM 拷贝由 glob 拍平（`COPY pom.xml fuyun-*/pom.xml ./`）改为逐模块 COPY 保持目录结构，其余七条镜像规范不动（backend 宪法 C.5-6）。③ .dockerignore 已含 target/ 排除，无需改动。本次不修订宪法正文，不新增生产代码。
- IT 落地过程中修复一处 B1.1 遗留构建缺陷（fuyun-app/pom.xml 最小配置变更，非生产代码）：spring-boot-maven-plugin repackage 增配 `<classifier>exec</classifier>`——默认在位替换使 failsafe 集成测试 classpath 拿到 BOOT-INF 布局 fat jar，应用类对普通类加载器不可见，导致 @SpringBootTest 装配失败（本机三次复现定位：包扫描找不到 @SpringBootConfiguration → 注解合并返回 null → 构造器注入失效）。fat jar 产出移至 `*-exec.jar`，Dockerfile 同步取 `*-exec.jar`；另在 IT 构造器显式标注 @Autowired（Spring 6.2 测试构造器默认 annotated 模式需显式声明，属构造器注入形态，符合 backend 宪法 A.1-7）。

## 2026-09-09 · PR-1 B1.1：后端工程骨架落盘（父 POM / fuyun-common / fuyun-app / 20 业务域空模块）

- 按 PLAN-P0-01 §1-PR-1 与 BRIEF-PR1-01 §2 落地 backend 骨架：父 POM（spring-boot-dependencies:3.5.16 BOM import、插件管理、22 模块聚合）、fuyun-common（错误码契约 / 业务异常基座 / ProblemDetail 全局渲染 / traceId 过滤器 / 操作人上下文 + 四测试类）、fuyun-app（装配入口 / 全环境与 dev-test-prod yml / TraceProperties @Validated 示范）、20 个业务域空模块（pom + 目录占位，无空实现类）。本次不修订宪法正文。
- 表外版本按 BRIEF-PR1-01 §9 默认方案锁定并将在 PR 描述申报（PR 合入后回补宪法 C.2 版本表）：maven-compiler-plugin 3.14.1、maven-surefire-plugin 3.5.6、maven-failsafe-plugin 3.5.6（取 spring-boot-dependencies:3.5.16 pluginManagement 原值，已核对 Maven Central）、lombok-mapstruct-binding 0.2.0（MapStruct 官方标准搭配值）。
- 行为代码（fuyun-common）按 TDD 先写失败测试再实现，测试与实现同提交；纯结构文件（pom / 目录占位 / yml）以构建命令验证。

## 2026-09-09 · P0 交付启动：dev 分支与门禁就绪 + SDD 台账建立

- 新增 `dev` 集成分支（自 main@a10369e），经 gh api 配置分支保护与 main 逐字段一致（五 required checks + strict + enforce_admins + 禁 force push / 删除）；此后 P0 五 PR 依序合入 dev（用户 2026-09-09 裁决），dev→main 合并另行裁决。
- 新增 `.superpowers/sdd/ledger.md`：P0 交付 loop（`docs/prompt/2026-09-09-loop-P0工程骨架.md`）的状态续传台账——阶段门禁状态、冲突扫描记录、批次明细，随各门禁推进更新。
- 冲突扫描结论：仅定稿 §6.6 nginx bind mount 片段路径与 web 宪法 C.3 存在表述分歧，按 TASK.md W-3 既有裁决（产物路径 = `web/apps/<app>/dist`）执行，无需新裁决；版本口径 / CI checks 名称 / 决策点（D-2/D-3）四方核对一致。

## 2026-09-09 · P0 交付执行设计落盘 + 定位层地图补 docs/prompt

- 新增 `docs/prompt/2026-09-09-loop-P0工程骨架.md`：以 PLAN-P0-01 为唯一 spec 的交付 loop 执行设计（P0→P6 七阶段门禁、17 个审批批次、TDD+SDD subagent 派遣制、批内修复/PR 审核/终验三级循环、PR 依序合入 dev 流程），供执行会话作为唯一执行依据；定位层仓库地图 §3 同步登记 `docs/prompt/`。
- 用户裁决两项（已录入文档 §8）：端到端测试口径 = 链路级端到端（浏览器 E2E 框架维持计划 §4 排除）；PR 粒度 = 计划五 PR 依序合入 dev。执行文档字数超限分歧经裁决按中文字数口径交付（2217 中文字，总字符含 ASCII 标识符 5853）。

## 2026-09-08 · CI 首跑修正（PR #1 实测暴露，两处）

1. **commitlint subject-case 中文误报**：中文 subject 以大写字母/数字开头（如"P0 实施计划…"）被 config-conventional 的 subject-case 规则误判为 pascal-case/upper-case，PR 检查失败。裁决：关闭 subject-case 规则（对 CJK 文本无实际意义，属该规则设计语境为英文的误伤），其余 conventional 规则全量保留。
2. **路径过滤意外触发构建 job**：PR #1 为纯文档变更，dorny/paths-filter 仍将 backend/frontend 判定为有变更（判定输出与实际文件清单不符），且 `setup-java` 的 `cache-dependency-path` 在 pom.xml 缺失时硬报错、`pnpm/action-setup` 在 package.json 缺失时报"No pnpm version specified"，导致骨架期必红。加固：changes job 增设 `pom`（backend/**/pom.xml）与 `webpkg`（web/**/package.json）存在性过滤器，backend/frontend job 触发条件追加"对应构建文件存在"——骨架期兜底不跑，P0 工程骨架落地后自动恢复触发（语义：有变更且可构建才运行门禁）。

## 2026-09-08 · P0 实施计划落盘 + 定位层地图补 docs/plans

- 新增 `docs/plans/2026-09-08-P0实施计划.md`（PLAN-P0-01）：P0 阶段的 PR 序列（工程骨架 / M20 治理构件 / M01 基础 / M14 骨架 / 收口）、各 PR 交付物与验收标准、决策点与 DoD，供新会话作为执行输入；定位层仓库地图与 §8 同步登记 `docs/plans/`（阶段实施计划）。
- W-6（NVD_API_KEY）经用户裁决撤销：不配置，security.yml 周审以匿名限流运行（TASK.md 同步删除）。
- 首次走 PR 流程合入（分支保护生效后的流程验证）。

## 2026-09-08 · 宪法 v1.1 → v1.2 修订：金额分值制 + 序列化/目录/MQ 消费模式裁决

### 用户裁决记录（2026-09-08）

1. **金额存储改 BIGINT 分值制**（覆盖 v1.0 沿用总 Spec 的 NUMERIC(18,2)）：全部金额列以 `BIGINT` 存"分"，应用层全程 `long`（禁浮点）；分↔元换算集中统一工具（convert 层 MoneyUtil），禁止散落 \*100 / ÷100；对外序列化以字符串承载。总 Spec §4.4 权威行本次同步修订；12 个模块 Spec 中 NUMERIC(18,2) 表述的批量同步登记 TASK.md（W-4）。
2. **JSON 序列化精度防线**：Jackson 全局注册 Long → String（ToStringSerializer）——雪花 ID 与金额分值超出 JS Number.MAX_SAFE_INTEGER（2^53）的部分前端以字符串接收，杜绝精度丢失；前端对应条款（金额/长整型一律字符串承载）同步进 web 宪法。
3. **注解优先**：能用注解 / 框架声明式能力解决的，禁止手写样板代码。
4. **RabbitMQ 消费确认改 AUTO 模式**（覆盖 M20 Spec §3.2/§7/§9 的"手动确认"选定）：@RabbitListener 注解驱动消费 + 容器 AUTO 确认（方法成功返回即确认、异常按重试策略 nack）+ 有界重试 + fy.dlx 死信 + 幂等不变——AUTO 语义等价"业务成功才确认"，并消除 MANUAL 模式漏写 ack 的实际事故源；M20 Spec 表述同步登记 TASK.md（W-5）。
5. **目录规范补充**（backend）：dto/（入参 DTO）· vo/（前端返回）· record/（特殊处理实体对象）· enum/（枚举一律 enum 类型，禁常量类模拟）· cache/（按业务领域的复杂缓存设计，{Domain}CacheService）· constants/（全部常量，常量类 public final static + 构造器私有）。
6. **Lua 脚本规范**：resources/lua/ 目录集中存放；经 Redisson RScript **预注册**（应用启动 SCRIPT LOAD 全部脚本缓存 SHA 单例，运行时 EVALSHA 调用，NOSCRIPT 异常回退重载）；所有原子性脚本使用的 key 必须携带 `{业务功能}` hash tag（Redis Cluster 语义，保证同一业务的 key 命中同一实例）。

### 同步执行记录（2026-09-08，W-4 / W-5 落地，用户裁决即批准）

- **模块 Spec 金额表述批量同步**：13 个文件 47 处（README 权威行 + 12 个模块 Spec 的表设计约定行/字段表/自审清单），统一为"BIGINT 分值制 / BIGINT，分"；14-iot 遥测 `value(NUMERIC)`、17-peis `result_form(NUMERIC)`、19-ops 指标值 `NUMERIC(18,4)` 等非金额 NUMERIC 不属本裁决范围，保留。
- **RabbitMQ 确认模式表述同步**：M20 §3.2/§3.4 共 6 处、14-iot 事件订阅 1 处、15-asset 事件订阅 1 处——"手动确认"改为"@RabbitListener 注解驱动 + 容器 AUTO 确认（语义等价'业务成功才确认'）"；IoTDA AMQP（Qpid JMS）客户端确认不在裁决范围，保持不变。
- **仓库分支保护**（原 W-2）：经 gh api 直接配置（required status checks 五项与 job 名精确对齐 + require PR + 禁止绕过），配置完成后删除对应工单。

### 与既有文档的差异说明

- v1.1 中"金额 NUMERIC(18,2) + BigDecimal"条款作废，A.4.2-8 重写；A.3 新增序列化条款；A.5-4/5 消费确认表述更新；B.1/C.3 目录表扩充。
- R2/R3 调研报告中 NUMERIC(18,2) 与"MANUAL ack"相关条目作为历史调研档案不改，以本 CHANGELOG 裁决为准。

## 2026-09-08 · 宪法 v1.0 → v1.1 修订：ORM 定稿 + 分层细化 + 命令呈现优化

### 用户裁决与修订要求（2026-09-08）

1. **ORM 定稿（TASK.md D-1 销项）**：MyBatis + MyBatis-Plus 协同——单表链式编程用 MP，复杂 SQL 走 mapper + XML。版本经官方核实：`mybatis-plus-spring-boot3-starter:3.5.17`（Boot 3 必须用 spring-boot3 后缀 starter，MP 3.5.16 起官方基线对齐 Boot 3.5 线）+ `mybatis-plus-jsqlparser:3.5.17`（3.5.9 起分页插件必需）；官方安装页明确禁止再引入 mybatis / mybatis-spring / mybatis-spring-boot-starter（MP starter 已内含 mybatis 3.5.19 + mybatis-spring 3.0.5）；Boot BOM 不托管，父 POM 锁版本。
2. **编码新条款**：禁止全限定类名声明（import 后用短类名）；禁用 @Deprecated API。
3. **C.4 常用命令呈现**：表格 → 代码块 + 精简注释（backend 与 web 两侧）。
4. **分层细化**（参考 commerce-customer 宪法）：B.1/B.2 吸收 mapper/entity 数据层归位、Service 接口+实现模式（IService/ServiceImpl）、循环依赖拆层切断禁 @Lazy 掩盖、跨 service 仅经接口复用查询；repository 表述统一为 mapper。

### 用户补充要求（同日追加）

5. **Redisson 正式纳入技术栈**：Redisson 4.7.0（redisson-spring-boot-starter，配套 redisson-spring-data-35，父 POM 锁版本）为正式选型组件，用于 watchdog 自动续期 / 可重入 / 读写锁等完整分布式锁语义；毫秒-秒级短持锁仍可选 BOM 托管的 RedisLockRegistry；防超卖最终由数据库唯一约束兜底。
6. **前端目录规范与层级依赖同颗粒度细化**：web 宪法 B.1 目录职责表与 B.2 层级依赖图按后端同等标准重写（views→components→composables→api/stores 单向依赖、types 全层可引用、跨 app 复用必须下沉 packages、禁反向与循环）。

### 与调研建议的差异说明

- R2 §8 调研主选为裸 MyBatis（mybatis-spring-boot-starter 3.0.5）；用户裁决升级为 MyBatis-Plus 协同方案（MP 为 MyBatis 增强层，包含并替代裸 starter），调研报告作为历史档案不改。
- T-R2-2（ORM 实体 equals/hashCode 与 Lombok 冲突）销项：MyBatis-Plus 实体无 JPA 持久化上下文代理语义，且宪法 A.1 已禁止 @Data 用于实体，风险已被覆盖。
- T-R2-5（log-impl 日志细节）保留，回填时点更新为 P0 实施期（MP SQL 日志经 mybatis-plus 配置项承载）。

## 2026-09-08 · 宪法体系 v1.0 初版生成 + CI 机制（方案 B）批准落地

### 变更范围

- 新增根定位层 `AGENTS.md`、子项目宪法 `backend/AGENTS.md` 与 `web/AGENTS.md`、根索引 `CLAUDE.md`、登记台 `TASK.md`。
- 调研依据（`docs/agmds-research/`，5 份，均经来源真实性评审）：CI 链方案、Java 与 SpringBoot 栈、数据与集成基础设施、前端栈、构建测试与 CI 落地细则。

### 用户裁决记录（2026-09-08）

| 裁决 | 内容 |
| --- | --- |
| CI 方案 | 选定方案 B「严格门禁」（5 job 全机器阻断）；方案 C 安全增强（CodeQL/Trivy/dependency-review 等）列为二期演进 |
| 落地范围 | CI 机制全套文件随本次交付（workflow / pre-commit / commitlint 等，路径过滤保证无代码阶段不空跑） |
| 命名模式 | agent 模式：AGENTS.md 承载宪法实体，CLAUDE.md 仅作根索引 |

### 合成裁决记录（宪法撰写时依调研报告"定稿时二选一"项作出，可经修宪流程推翻）

| 裁决 | 选择 | 理由 |
| --- | --- | --- |
| API 响应模式 | 成功 2xx 裸数据 + 失败一律 ProblemDetail（RFC 9457，properties.errorCode/traceId） | REST 状态语义化、openapi-typescript 契约链路类型最干净；envelope 候选因无权威规范且与 HTTP 语义冗余弃用 |
| JaCoCo 门禁 | 每模块 check 模式（BUNDLE LINE ≥0.80 + 核心包 PACKAGE 1.00）；聚合报告仅只读 | 官方 issue #902：check 不支持聚合报告；逐模块失败点可定位 |
| Spotless 格式化器 | palantir-java-format | Java 17 红线：google-java-format ≥1.22 需 JDK 21 |
| 前端覆盖率 | report-only 起步，不设硬阈值 | 金额计算全服务端（D5）；硬阈值易催生空断言测试，违反全局规范 |
| 前端单测框架 | Vitest 4.1.11（非 latest 5.0.0） | 5.0.0 GA 不足一季度，违反技术栈选型原则 2 |
| 并发锁 | Spring Integration RedisLockRegistry（BOM）+ DB 唯一约束兜底 | BOM 托管优先；号源/床位锁为短持锁场景 |
| 定时任务 | @Scheduled + ShedLock 6.10.0 | 多实例互斥最小方案；Quartz 本期不引入 |

### 评审修正记录

- 5 份调研报告经评审：3 份直接通过；2 份退回定点修正（JaCoCo "Boot BOM 托管"断言不实——经 spring-boot-dependencies/parent-3.5.16.pom 直查均无 jacoco 条目，已更正为"父 POM pluginManagement 显式锁定 0.8.15"；另修正 Spotless 版本表述、paths-filter latest 表述、withVueTs API 对齐、排除项笔误）。16 项来源 URL 抽查全部真实。
- 宪法体系经审核专员终审：修复 1 项 MAJOR（backend A.4 引用 TASK 编号断链）与 4 项 MINOR（B.5 槽位省略声明、HTML 实体写法、web B.3-4 四主题清单改引用式、TASK 补登 T-R2-5）后通过。

### CI 机制落地记录（同日，用户已批准）

- 落盘 12 件：`.github/workflows/ci.yml`（6 job 主链，images 按"谁变更构建谁"的 matrix 条目级条件）与 `security.yml`（OWASP 周审）、`.pre-commit-config.yaml`、`commitlint.config.mjs`、`scripts/check-encoding.py`（UTF-8 无 BOM/乱码/CRLF 三查）、`.gitattributes`/`.gitignore` 补强、`backend/Dockerfile` 与 `web/Dockerfile` 及各自 `.dockerignore`（P0 骨架后方可实际构建）。
- 验证：pre-commit 4.6.2 `run --all-files` 9 钩子全部通过（含 actionlint 校验 workflow）；check-encoding.py 对三类违规注入样本测试通过；既有文件零卫生违规。
- 有意偏差（相对 R5 蓝本）：frontend job 的 pnpm/action-setup 补 `package_json_file: web/package.json`（monorepo 在子目录，action 默认读根 package.json 会失败）；images 门禁由"两构建 job 全 success"细化为"谁变更构建谁"（job 级至少一侧成功 + 构建步骤按 matrix 条目校验对应变更与 verify 结果），修复纯前端 PR 失去 web 镜像构建验证的缺口。
