# 生产部署检查单（production checklist）

> 定位：生产环境起栈后**必须逐条执行**的强制动作清单（PR-4D 五路评审 A-1 must-fix 落盘的
> 可执行载体；长期门控方案见 `TASK.md` W-95 工单）。dev/test 演示环境不适用本清单。
> 红线背景：等保三级——已知口令账号与配置漂移均属审计不合规项。

## 1. 种子/演示账号处置（起栈后立即执行）

Flyway 迁移 `V303`（admin/doctordemo）与 `V1117`（五账号族）共 7 个 ACTIVE 账号使用仓库
公开初始口令（bcrypt 哈希同源，文档与迁移注释均明示该口令），对生产环境构成**已知凭据暴露面**
（Flyway 社区版无环境门控，种子无条件入库）。处置口径：

| login_name | 来源 | 角色 | 生产处置 |
| --- | --- | --- | --- |
| `admin` | V303 | 超级管理员（ADMIN 一票放行） | **强制改密**（生产必用账号；初始口令视为已泄露） |
| `doctordemo` | V303 | DOCTOR | 停用（演示账号无生产用途） |
| `nursedemo` | V1117 | NURSE | 停用 |
| `pharmdemo` | V1117 | PHARMACIST | 停用 |
| `cashierdemo` | V1117 | CASHIER | 停用 |
| `registrardemo` | V1117 | REGISTRAR | 停用 |
| `iotdemo` | V1117 | IOT_ADMIN（数据范围 ALL） | 停用（ALL 数据范围，暴露面最大） |

可执行 SQL（psql 直连业务库；幂等可重复执行，`updated_at` 由触发器维护）：

```sql
-- 演示账号族停用（六账号无生产用途）
UPDATE system.sys_user SET status = 'DISABLED'
WHERE login_name IN ('doctordemo','nursedemo','pharmdemo','cashierdemo','registrardemo','iotdemo')
  AND status = 'ACTIVE';

-- admin 改密不走 SQL（bcrypt 哈希须经应用侧生成）：起栈后立即登录改密，
-- 改密前禁止对公网暴露 nginx 入口
```

验证口径：处置后以初始口令逐账号登录应全部失败（401）；`SELECT login_name, status FROM
system.sys_user WHERE login_name IN (...)` 应仅剩 `admin` 为 ACTIVE。

## 2. 禁设 `server.servlet.context-path`（403 鉴权矩阵匹配前提）

`AuthorizationInterceptor` 以 `request.getRequestURI()`（含 contextPath）匹配权限矩阵模板，
MVC 路由匹配则会剥离 contextPath——一旦配置 context-path，矩阵全量不命中，全站退化为
「401 + 未登记面放行」（fail-open 且无报警）。当前部署（nginx `/api` 直转 backend）无此配置，
**运维侧禁止引入**；如确需路径前缀，须经 PR 评审同步改造 `AuthorizationInterceptor` 的
contextPath 剥离逻辑后方可。

## 3. 角色与权限绑定的变更纪律（管理台上线前过渡口径）

PR-4F 权限管理台落地前，角色/权限绑定仅可经 SQL 直改；**每次 SQL 变更后必须清理对应用户的
会话键**（`fy:system:session:*`，按 userId 定位删除），否则旧会话的角色与权限快照漂移至 TTL
过期（含 ADMIN 撤权后仍全放行面）。管理台上线后由「变更即删会话键」实现承载（W-96 工单）。
