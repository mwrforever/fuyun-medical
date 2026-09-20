-- V704：执业授权 practice_grant 表 + 演示医师最小种子（M01 FU-M01-04，recon 裁决 9）。
-- 落位合法性（CHANGELOG 2026-09-20 条目先记再改，偏差②）：system 登记段=(300,399)+(500,None)，
--   704∈通用段且 704>基线全局最大 V703——乱序守卫放行（recon 原拟 V608<V703 必被拦，禁试）。
-- 列结构照 01-system.md:58（employee_id/grant_type/legal_basis/valid_from/valid_to/status/审批引用）。
-- 种子幂等形态 INSERT ... WHERE NOT EXISTS（V303/V607 先例）；演示口令与 V303 admin 同源
--   （Fuyun@2026 仅存 bcrypt 哈希，禁明文入库——V303 :63 红线注记同款）；种子行取小整数 ID
--   （sys_user id=3 与 sys_employee id=3：避开 IT seedReviewerUser 固定占用的 user id=2）。
-- 身份链对齐红线：运行态操作者为 userId（AuthTokenInterceptor :76 `String.valueOf(session.userId())`
--   注入 OperatorContextHolder），Task 8/9 校验入参 Long.parseLong(OperatorContextHolder.get())
--   直作 employeeId——故 employee_id 必须与 sys_user.id 同值（=3），错位即真栈开单/开方 403。

-- ---------------------------------------------------------------- 1. 执业授权表
CREATE TABLE system.practice_grant (
    id              BIGINT        PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    employee_id     BIGINT        NOT NULL,                            -- 员工 ID（sys_employee.id，应用层保证完整性）
    grant_type      VARCHAR(32)   NOT NULL,                            -- 授权类型词表：PRESCRIPTION/NARCOTIC/ANTIBIO_NONRESTRICT/ANTIBIO_RESTRICT/ANTIBIO_SPECIAL
    legal_basis     VARCHAR(255)  NULL,                                -- 法定依据（执业证书号/批文引用等）
    valid_from      DATE          NOT NULL,                            -- 生效日（含当日）
    valid_to        DATE          NULL,                                -- 失效日（含当日）；NULL=长期有效
    status          VARCHAR(16)   NOT NULL DEFAULT 'EFFECTIVE',        -- 状态机：EFFECTIVE 生效/SUSPENDED 停权/EXPIRED 过期（读侧派生为主）
    approval_ref    VARCHAR(128)  NULL,                                -- 审批引用（医务审批单号）
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted         SMALLINT      NOT NULL DEFAULT 0
);

-- 同一员工同一授权类型仅一条生效行（停权/过期行不占唯一性，可重新登记）
CREATE UNIQUE INDEX uk_practice_grant_active ON system.practice_grant (employee_id, grant_type)
    WHERE deleted = 0 AND status = 'EFFECTIVE';
CREATE INDEX idx_practice_grant_employee ON system.practice_grant (employee_id) WHERE deleted = 0;

CREATE TRIGGER trg_practice_grant_updated_at BEFORE UPDATE ON system.practice_grant
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 2. 演示医师账号（复用 V303 admin 口令哈希）
INSERT INTO system.sys_user (id, login_name, password_hash, user_type, status)
SELECT 3, 'doctordemo', '$2a$10$mySbYjh9bHhK7WDdnoKvtOHx.gU.z9I4fbsKfyomOyvh.9zlt/FjW', 'STAFF', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user WHERE login_name = 'doctordemo');

INSERT INTO system.sys_employee (id, user_id, emp_no, emp_name, title, primary_org_id, status)
SELECT 3, 3, 'DOC001', '演示医师', '主治医师', NULL, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_employee WHERE emp_no = 'DOC001');

INSERT INTO system.sys_user_role (id, user_id, role_id)
SELECT 3, 3, 1
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user_role WHERE user_id = 3 AND role_id = 1);

-- ---------------------------------------------------------------- 3. 演示医师执业授权三档（employee_id=3 与 sys_user.id 对齐，处方权/麻精/抗菌药非限制）
INSERT INTO system.practice_grant (id, employee_id, grant_type, legal_basis, valid_from, valid_to, status, approval_ref)
SELECT 1, 3, 'PRESCRIPTION', '演示执业证书 PR-2026-001', DATE '2026-01-01', NULL, 'EFFECTIVE', 'SEED-PR5'
WHERE NOT EXISTS (SELECT 1 FROM system.practice_grant WHERE employee_id = 3 AND grant_type = 'PRESCRIPTION' AND deleted = 0);

INSERT INTO system.practice_grant (id, employee_id, grant_type, legal_basis, valid_from, valid_to, status, approval_ref)
SELECT 2, 3, 'NARCOTIC', '麻精药品处方权培训合格证 NP-2026-001', DATE '2026-01-01', DATE '2027-12-31', 'EFFECTIVE', 'SEED-PR5'
WHERE NOT EXISTS (SELECT 1 FROM system.practice_grant WHERE employee_id = 3 AND grant_type = 'NARCOTIC' AND deleted = 0);

INSERT INTO system.practice_grant (id, employee_id, grant_type, legal_basis, valid_from, valid_to, status, approval_ref)
SELECT 3, 3, 'ANTIBIO_NONRESTRICT', '抗菌药物临床应用培训非限制级', DATE '2026-01-01', NULL, 'EFFECTIVE', 'SEED-PR5'
WHERE NOT EXISTS (SELECT 1 FROM system.practice_grant WHERE employee_id = 3 AND grant_type = 'ANTIBIO_NONRESTRICT' AND deleted = 0);
