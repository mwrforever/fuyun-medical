# CHANGELOG（工程变更记录）

> 记录规则（根 AGENTS.md §7）：**先记再改**——任何宪法 / 规范 / 机制文件的修订，先在本文件登记（日期、范围、理由、裁决），再改正文。追加式保留全部历史。

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
