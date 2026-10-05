-- V1120：元素级 UI 权限点种子（P2 PR-4F Task 2 / 计划 2026-10-03-p2-pr4f-ui-perm 附件 A/B 权威矩阵）——
--   sys_permission 增量 45 点（40 ELEMENT + 1 MENU + 4 API）+ integration.event_registry
--   权限矩阵变更广播事件登记 1 行（F3 刷新通道）。
-- 登记计数：ELEMENT 权限点 40（附件 A #1~#40 逐行）+ MENU 权限点 1（附件 B system:permission:manage）
--   + API 权限点 4（附件 B PR-4F 管理端点族）+ event_registry 1（id 84）。
-- ID 取值：ELEMENT 点 1120000000000000001+序、MENU 点 1120001000000000001、API 点 1120002000000000001+序
--   （19 位内固定值，112 前缀分段防撞，接 V1116 的 1116 段位顺延）；Task 3 的 V1121 绑定一律按
--   perm_code join 解析 id，禁手写。
-- perm_code 形态（F1 裁定）：ELEMENT 型 = 四段冒号码（域:功能:btn:动作，如 billing:refund:btn:approve），
--   与 MENU 三段码、含空格 API 码三态天然可辨，perm_code 唯一索引空间不冲突；管理端点 API 码与
--   Task 4/5 Controller 最终路径逐字一致（{roleCode} 模板变量名与 @PathVariable 同名）。
-- 绑定口径：MENU 点与 4 个 API 点为 ADMIN 专属码不种绑定行（F5——ADMIN 运行期全放自动可见，
--   billing:refund:approve MENU 先例）；40 元素码业务角色绑定归 Task 3 的 V1121 按附件 A「绑定角色」列展开。
-- 幂等形态：INSERT ... SELECT ... WHERE NOT EXISTS ... AND deleted = 0（V303/V1116 先例，与
--   uk_sys_permission_perm_code 部分唯一索引同口径，重放零重复零唯一冲突）；event_registry 行按
--   event_type 判存（V605/V1109 先例）。
-- 审计口径：created_at/updated_at 由数据库 DEFAULT now() 维护、created_by/updated_by 取默认 'system'
--   （backend 宪法 A.4.2-9）；updated_at 另由 V300 触发器统一维护。
-- 事件登记口径：id 84 全局递增（接 V1109 nursing id 83）；system 模块跨 schema 种
--   integration.event_registry 照 billing V605 / nursing V1109 先例；本行先于 Task 6 队列声明落库
--   ——「先登记后订阅」治理校验依赖此行存在。

-- ---------------------------------------------------------------- 1. ELEMENT 权限点 40 点（附件 A 逐行，perm_type=ELEMENT）
-- workstation 前端 v-perm/hasPerm 消费面（Task 8/10~12 按附件 A「落位说明」列挂接）；序号 = 附件 A #1~#40。

-- ---- A.1 患者域（2 点）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000001, 'patient:archive:btn:create', '患者建档-预检与建档按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'patient:archive:btn:create' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000002, 'patient:archive:btn:freeze', '患者主档-冻结/解冻按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'patient:archive:btn:freeze' AND deleted = 0);

-- ---- A.2 收费域（5 点）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000003, 'billing:charge:btn:manual', '划价结算-手工计费按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'billing:charge:btn:manual' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000004, 'billing:charge:btn:settle', '划价结算-预结算与结算收费按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'billing:charge:btn:settle' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000005, 'billing:refund:btn:apply', '退费审批-申请退费按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'billing:refund:btn:apply' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000006, 'billing:refund:btn:execute', '退费审批-执行退费按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'billing:refund:btn:execute' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000007, 'billing:refund:btn:approve', '退费审批-审批与拒绝按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'billing:refund:btn:approve' AND deleted = 0);

-- ---- A.3 门诊域（5 点；含头注申报补齐的 #12 挂号按钮码）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000008, 'outpatient:triage:btn:manage', '分诊台-报到与分诊调整按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'outpatient:triage:btn:manage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000009, 'outpatient:queue:btn:call', '分诊台-叫号过号与重呼按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'outpatient:queue:btn:call' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000010, 'outpatient:doctor:btn:admit', '门诊医生站-接诊与结束就诊按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'outpatient:doctor:btn:admit' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000011, 'outpatient:doctor:btn:order', '门诊医生站-开立治疗单与处方按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'outpatient:doctor:btn:order' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000012, 'outpatient:registration:btn:register', '挂号收费-挂号按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'outpatient:registration:btn:register' AND deleted = 0);

-- ---- A.4 住院域（6 点）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000013, 'inpatient:admission:btn:create', '住院登记-登记入院与床位安排按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'inpatient:admission:btn:create' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000014, 'inpatient:admission:btn:register', '住院登记-办理入院登记按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'inpatient:admission:btn:register' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000015, 'inpatient:bed:btn:change', '床位图-转床按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'inpatient:bed:btn:change' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000016, 'inpatient:discharge:btn:manage', '出院管理-申请取消与确认出院按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'inpatient:discharge:btn:manage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000017, 'inpatient:station:btn:order', '住院医生站-保存医嘱按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'inpatient:station:btn:order' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000018, 'inpatient:transfer:btn:check', '转科工作台-提交核对按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'inpatient:transfer:btn:check' AND deleted = 0);

-- ---- A.5 药房域（6 点；#23 医保对照为 ADMIN 专属码）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000019, 'pharmacy:dispense:btn:issue', '门诊摆药-拣药核对发药按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'pharmacy:dispense:btn:issue' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000020, 'pharmacy:dispense:btn:plan', '住院摆药-计划生成与摆药签收按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'pharmacy:dispense:btn:plan' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000021, 'pharmacy:dispense:btn:return', '药房退药-退药受理与退药按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'pharmacy:dispense:btn:return' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000022, 'pharmacy:drug:btn:maintain', '药品字典-建档与变更按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'pharmacy:drug:btn:maintain' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000023, 'pharmacy:drug:btn:insurance-mapping', '药品字典-医保对照按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'pharmacy:drug:btn:insurance-mapping' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000024, 'pharmacy:review:btn:audit', '审方任务-通过与驳回按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'pharmacy:review:btn:audit' AND deleted = 0);

-- ---- A.6 护理域（7 点，全 NURSE）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000025, 'nursing:ward:btn:vital', '病区看板-体温录入与复审按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'nursing:ward:btn:vital' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000026, 'nursing:ward:btn:record', '病区看板-IO记录评估与特殊事件按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'nursing:ward:btn:record' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000027, 'nursing:ward:btn:task', '病区看板-任务生成与流转按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'nursing:ward:btn:task' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000028, 'nursing:ward:btn:handover', '病区看板-交班生成与完成按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'nursing:ward:btn:handover' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000029, 'nursing:execution:btn:perform', '执行工作台-签收核对开始完成取消按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'nursing:execution:btn:perform' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000030, 'nursing:pda:btn:use', '移动护理PDA-覆核执行拔针体征巡视按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'nursing:pda:btn:use' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000031, 'nursing:adverse-event:btn:manage', '不良事件-上报处置退回关闭按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'nursing:adverse-event:btn:manage' AND deleted = 0);

-- ---- A.7 IoT 域（7 点，全 IOT_ADMIN）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000032, 'iot:alarm-rule:btn:manage', '告警规则-编辑删除模拟与告警处置按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'iot:alarm-rule:btn:manage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000033, 'iot:binding:btn:manage', '设备绑定-绑定与解绑按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'iot:binding:btn:manage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000034, 'iot:command:btn:issue', '命令中心-下发与挑战确认按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'iot:command:btn:issue' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000035, 'iot:device:btn:manage', '设备管理-注册凭证重置与停用按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'iot:device:btn:manage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000036, 'iot:linkage:btn:manage', '联动规则-新建编辑删除与重试按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'iot:linkage:btn:manage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000037, 'iot:product:btn:manage', '产品管理-新建同步映射与命令模板按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'iot:product:btn:manage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000038, 'iot:quality:btn:replay', '质量看板-重放与放弃按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'iot:quality:btn:replay' AND deleted = 0);

-- ---- A.8 病区域（2 点）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000039, 'ward:call:btn:handle', '呼叫工作台-应答处置完成转接取消按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'ward:call:btn:handle' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120000000000000040, 'ward:coldchain:btn:manage', '冷链监测-档案新建与记录登记按钮', 'ELEMENT'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'ward:coldchain:btn:manage' AND deleted = 0);

-- ---------------------------------------------------------------- 2. MENU 权限点 1 点（PR-4F 管理台路由码，ADMIN 专属不种绑定）
-- Task 9 管理页路由 meta.permission 消费（路由 meta 双侧同步登记）；行形态须精确匹配
-- menuPermissionMatrix.spec 的 MENU_ROW_PATTERN 正则（SELECT 数字, 码, 名, MENU）。
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120001000000000001, 'system:permission:manage', '权限管理台菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'system:permission:manage' AND deleted = 0);

-- ---------------------------------------------------------------- 3. API 权限点 4 点（PR-4F 管理端点族，ADMIN 专属不种绑定行）
-- 后端 403 拦截器消费面；perm_code 与 Task 4/5 Controller 最终路径逐字一致（{roleCode} 与 @PathVariable 同名）。
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120002000000000001, 'GET /api/v1/system/roles', '角色清单与绑定码集读取', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/system/roles' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120002000000000002, 'GET /api/v1/system/permissions', '权限点分组清单读取', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/system/permissions' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120002000000000003, 'PUT /api/v1/system/roles/{roleCode}/permissions', '角色权限矩阵全量覆写', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'PUT /api/v1/system/roles/{roleCode}/permissions' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1120002000000000004, 'PUT /api/v1/system/roles/{roleCode}/status', '角色启用停用', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'PUT /api/v1/system/roles/{roleCode}/status' AND deleted = 0);

-- ---------------------------------------------------------------- 4. 事件登记 1 行（F3 刷新通道：fy.topic 广播 + 各实例 Registry 重载）
-- 镜像 V5 id 6 system.practice.changed 行形态；Task 6 队列声明「先登记后订阅」校验依赖本行先落库。
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 84,
       'system.permission.changed',
       'system',
       'PR-4F 权限矩阵变更广播：roleCode=变更角色码；消费动作=PermissionRegistry.load() 幂等重载 + 受影响角色会话键清理',
       '',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'system.permission.changed');

-- ---------------------------------------------------------------- 5. 计数自证
-- 本文件登记：sys_permission INSERT 45 段（ELEMENT 40 + MENU 1 + API 4）+ event_registry INSERT 1 行。
--   自证口径：grep -c "INSERT INTO system\.sys_permission" = 45、
--   grep -c "INSERT INTO integration\.event_registry" = 1、ELEMENT 型行（单引号界定）计数 = 40
--   （注释内点号转义/描述化避免自匹配，V1116 计数自证同款处理）。
-- 净效果：sys_permission 增 45 权限点（40 ELEMENT 新面 + 1 MENU 新面 + 4 管理端点 API 新面）
--   + integration.event_registry 登记 id 84（system.permission.changed）。
