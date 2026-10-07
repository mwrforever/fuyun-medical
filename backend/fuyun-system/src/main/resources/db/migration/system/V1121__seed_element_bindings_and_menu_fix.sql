-- V1121：元素权限绑定种子 + F6 四处 MENU 错位扩绑（P2 PR-4F Task 3 / 计划 2026-10-03-p2-pr4f-ui-perm）。
-- 装配口径：sys_role_permission 增量 47 行——ELEMENT 绑定 43 行（附件 A 40 码中 38 码按「绑定角色」列
--   展开，绑定角色集镜像各码对应 API 端点的 V1117 绑定角色集，一对一度锚；billing:refund:btn:approve
--   与 pharmacy:drug:btn:insurance-mapping 两码 ADMIN 专属零行——ADMIN 运行期全放自动可见，
--   billing:refund:approve MENU 先例）+ MENU 扩绑 4 行（F6 四处「页内写端点角色 ⊄ 路由码绑定集」收口）。
-- ID 分段：ELEMENT 绑定 1121000000000000000+序、MENU 扩绑 1121001000000000000+序（sys_role_permission
--   表内分段防撞，接 V1117 的 1117 段位顺延）；绑定 id 一律经 role_code/perm_code join 双表解析，
--   禁手写数字 id（V1116 头注口径）。
-- 幂等形态：全段 INSERT ... SELECT ... WHERE NOT EXISTS（V303/V1116/V1117 先例）——经 VALUES 映射表
--   join 双表按 role_code/perm_code 解析 id + deleted=0 精确匹配，NOT EXISTS 按 role_id+permission_id
--   对防重，重放零重复零唯一冲突；绑定 id 用「段前缀 + row_number() OVER ()」生成（窗口函数在
--   NOT EXISTS 过滤后编号，首跑全量连续、重放零行插入不消耗 id——V1117:6-9 同款）。
-- 审计口径：created_at/updated_at 由数据库 DEFAULT now() 维护、created_by/updated_by 取默认 'system'
--   （backend 宪法 A.4.2-9）；updated_at 另由 V300 触发器统一维护。

-- ---------------------------------------------------------------- 1. ELEMENT 绑定 43 行（附件 A「绑定角色」列展开；ADMIN 专属码不生成行）
-- perm_code 与 V1120 实际 INSERT 的码逐字一致（join 解析 id，禁手写数字 id）；
-- 前端 v-perm/hasPerm 消费面（Task 8/10~12 按附件 A「落位说明」列挂接），后端经角色绑定关系放行。
INSERT INTO system.sys_role_permission (id, role_id, permission_id)
SELECT 1121000000000000000 + row_number() OVER (),
       r.id, p.id
FROM (VALUES
    -- A.1 患者域（4 行：建档 3 角色 + 冻结/解冻 1 角色）
    ('REGISTRAR', 'patient:archive:btn:create'),
    ('DOCTOR', 'patient:archive:btn:create'),
    ('NURSE', 'patient:archive:btn:create'),
    ('REGISTRAR', 'patient:archive:btn:freeze'),
    -- A.2 收费域（5 行：手工计费 2 角色 + 预结算/申请退费/执行退费各 1；审批码 ADMIN 专属零行）
    ('DOCTOR', 'billing:charge:btn:manual'),
    ('CASHIER', 'billing:charge:btn:manual'),
    ('CASHIER', 'billing:charge:btn:settle'),
    ('CASHIER', 'billing:refund:btn:apply'),
    ('CASHIER', 'billing:refund:btn:execute'),
    -- A.3 门诊域（5 行：每码单角色）
    ('NURSE', 'outpatient:triage:btn:manage'),
    ('REGISTRAR', 'outpatient:queue:btn:call'),
    ('DOCTOR', 'outpatient:doctor:btn:admit'),
    ('DOCTOR', 'outpatient:doctor:btn:order'),
    ('REGISTRAR', 'outpatient:registration:btn:register'),
    -- A.4 住院域（8 行：登记入院/出院管理/医嘱/核对各 1 角色 + 入区登记/转床双角色 2×2）
    ('DOCTOR', 'inpatient:admission:btn:create'),
    ('REGISTRAR', 'inpatient:admission:btn:register'),
    ('NURSE', 'inpatient:admission:btn:register'),
    ('REGISTRAR', 'inpatient:bed:btn:change'),
    ('NURSE', 'inpatient:bed:btn:change'),
    ('DOCTOR', 'inpatient:discharge:btn:manage'),
    ('DOCTOR', 'inpatient:station:btn:order'),
    ('DOCTOR', 'inpatient:transfer:btn:check'),
    -- A.5 药房域（5 行：5 码均 PHARMACIST 单角色；医保对照码 ADMIN 专属零行）
    ('PHARMACIST', 'pharmacy:dispense:btn:issue'),
    ('PHARMACIST', 'pharmacy:dispense:btn:plan'),
    ('PHARMACIST', 'pharmacy:dispense:btn:return'),
    ('PHARMACIST', 'pharmacy:drug:btn:maintain'),
    ('PHARMACIST', 'pharmacy:review:btn:audit'),
    -- A.6 护理域（7 行：全 NURSE 单角色）
    ('NURSE', 'nursing:ward:btn:vital'),
    ('NURSE', 'nursing:ward:btn:record'),
    ('NURSE', 'nursing:ward:btn:task'),
    ('NURSE', 'nursing:ward:btn:handover'),
    ('NURSE', 'nursing:execution:btn:perform'),
    ('NURSE', 'nursing:pda:btn:use'),
    ('NURSE', 'nursing:adverse-event:btn:manage'),
    -- A.7 IoT 域（7 行：全 IOT_ADMIN 单角色）
    ('IOT_ADMIN', 'iot:alarm-rule:btn:manage'),
    ('IOT_ADMIN', 'iot:binding:btn:manage'),
    ('IOT_ADMIN', 'iot:command:btn:issue'),
    ('IOT_ADMIN', 'iot:device:btn:manage'),
    ('IOT_ADMIN', 'iot:linkage:btn:manage'),
    ('IOT_ADMIN', 'iot:product:btn:manage'),
    ('IOT_ADMIN', 'iot:quality:btn:replay'),
    -- A.8 病区域（2 行：呼叫 NURSE + 冷链 IOT_ADMIN）
    ('NURSE', 'ward:call:btn:handle'),
    ('IOT_ADMIN', 'ward:coldchain:btn:manage')
) AS m(role_code, perm_code)
JOIN system.sys_role r ON r.role_code = m.role_code AND r.deleted = 0
JOIN system.sys_permission p ON p.perm_code = m.perm_code AND p.deleted = 0
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id AND rp.deleted = 0);
-- ELEMENT 绑定行数自证：A.1 4 + A.2 5 + A.3 5 + A.4 8 + A.5 5 + A.6 7 + A.7 7 + A.8 2 = 43 行。

-- ---------------------------------------------------------------- 2. MENU 错位扩绑 4 行（F6：四处「页内写端点角色 ⊄ 路由码绑定集」收口）
-- 前端路由守卫消费面（裁定 D2）；绑定角色集与 V1117 MENU 段既有行零重复——四处均为增量角色
--   （V1117 既有：registration:register 仅 REGISTRAR、station:view 仅 REGISTRAR+NURSE、
--   triage:manage 仅 NURSE、admission:manage 仅 REGISTRAR+DOCTOR），NOT EXISTS 双守卫下重放安全。
INSERT INTO system.sys_role_permission (id, role_id, permission_id)
SELECT 1121001000000000000 + row_number() OVER (),
       r.id, p.id
FROM (VALUES
    ('CASHIER', 'outpatient:registration:register'),
    ('DOCTOR', 'inpatient:station:view'),
    ('REGISTRAR', 'outpatient:triage:manage'),
    ('NURSE', 'inpatient:admission:manage')
) AS m(role_code, perm_code)
JOIN system.sys_role r ON r.role_code = m.role_code AND r.deleted = 0
JOIN system.sys_permission p ON p.perm_code = m.perm_code AND p.deleted = 0
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id AND rp.deleted = 0);
-- MENU 扩绑行数自证：F6①~④ 各 1 行 = 4 行。

-- ---------------------------------------------------------------- 3. 计数自证
-- 本文件装配：sys_role_permission ELEMENT 绑定 43 行 + MENU 扩绑 4 行 = 47 行。
--   自证口径：grep -c "^    ('" 本文件 = 47（行首四空格锚定 VALUES 数据行，规避注释自匹配，
--   V1117/V1116 计数自证同款口径）。
-- 净效果：38 个业务元素码全部获得角色绑定（40 码中两码 ADMIN 专属零行）+ F6 四处路由码错位收口
--   （扩绑后页内写端点角色均落在路由码绑定集内，元素码按附件 A 独立收口不受影响）；
--   Task 7 快照断言的期望源之一 = V1117+V1121 VALUES 全集。
