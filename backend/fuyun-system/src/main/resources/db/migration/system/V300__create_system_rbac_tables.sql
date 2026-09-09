-- V300：RBAC 五核心表 + 两关联表（M01 Spec §4 P0 切片，BRIEF-PR3-01 §2）
-- 号段登记（BRIEF-PR3-01 §2.1 / CHANGELOG 2026-09-09 v1.4 条目）：system 域占用 V300–V399，本批用 V300–V303。
-- DDL 公共约定（BRIEF-PR3-01 §2.3）：
--   雪花 ID 主键（MP ASSIGN_ID，应用层禁止手动赋值）；审计列由数据库维护（DEFAULT now() + V1 公共触发器函数，
--   backend 宪法 A.4.2-9）；deleted 逻辑删标记；不建外键约束（关联完整性应用层保证，V2/V3 先例）；
--   唯一约束用部分唯一索引 WHERE deleted = 0（V2 先例）；状态列 VARCHAR(16)，值域 = com.fuyun.system.enums 枚举 code。
-- 触发器说明：五核心表有 UPDATE 生命周期（状态变更/登录计数/资料维护）挂触发器；两关联表（sys_user_role/
--   sys_role_permission）P0 只有 INSERT/DELETE（授权与回收），无 UPDATE 生命周期，不挂触发器（同 V3 received_event 只增口径）。

-- ---------------------------------------------------------------- 机构表
CREATE TABLE system.sys_org (
    id             BIGINT        PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    org_code       VARCHAR(64)   NOT NULL,                            -- 机构编码，业务唯一
    org_name       VARCHAR(128)  NOT NULL,                            -- 机构名称
    org_type       VARCHAR(16)   NOT NULL,                            -- 机构类型：CAMPUS 院区/DEPT 科室/WARD 病区/TEAM 班组
    org_attr       VARCHAR(16)   NOT NULL,                            -- 机构属性：CLINICAL 临床/MEDTECH 医技/ADMIN 职能
    parent_id      BIGINT        NULL,                                -- 父机构 ID（邻接表；NULL=根节点，树形闭包表随 P1 组织管理补）
    sort           INT           NOT NULL DEFAULT 0,                  -- 同级排序号，小者在前
    status         VARCHAR(16)   NOT NULL,                            -- 状态：ACTIVE 启用/DISABLED 停用
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted        SMALLINT      NOT NULL DEFAULT 0
);

-- 机构编码业务唯一（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_sys_org_org_code ON system.sys_org (org_code) WHERE deleted = 0;
-- 组织树按父节点下钻查询路径
CREATE INDEX idx_sys_org_parent_id ON system.sys_org (parent_id);

-- updated_at 触发器：复用 integration V1 公共函数（跨 schema 复用，全项目禁重复定义）
CREATE TRIGGER trg_sys_org_updated_at BEFORE UPDATE ON system.sys_org
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 用户表（登录账号）
CREATE TABLE system.sys_user (
    id                  BIGINT        PRIMARY KEY,                     -- 雪花 ID（MP ASSIGN_ID）
    login_name          VARCHAR(64)   NOT NULL,                        -- 登录名，业务唯一
    password_hash       VARCHAR(100)  NOT NULL,                        -- bcrypt 哈希（$2a$ 60 字符留余量），禁明文
    user_type           VARCHAR(16)   NOT NULL,                        -- 账号类型：STAFF 员工/SYSTEM 系统/API 接口
    status              VARCHAR(16)   NOT NULL,                        -- 状态机：ACTIVE 正常/LOCKED 锁定/DISABLED 停用
    fail_count          INT           NOT NULL DEFAULT 0,              -- 连续登录失败计数（成功登录清零，达 5 次置锁定）
    locked_until        TIMESTAMPTZ   NULL,                            -- 锁定截止时刻（NULL=未锁定；到期自动恢复，P0 无手动解锁）
    password_updated_at TIMESTAMPTZ   NULL,                            -- 口令更新时刻（P1 密码策略启用，先落列避 ALTER）
    last_login_at       TIMESTAMPTZ   NULL,                            -- 最近成功登录时刻
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by          VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted             SMALLINT      NOT NULL DEFAULT 0
);

-- 登录名业务唯一（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_sys_user_login_name ON system.sys_user (login_name) WHERE deleted = 0;

CREATE TRIGGER trg_sys_user_updated_at BEFORE UPDATE ON system.sys_user
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 员工表（人员档案）
CREATE TABLE system.sys_employee (
    id              BIGINT        PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    user_id         BIGINT        NOT NULL,                            -- 关联登录账号 ID，一对一（应用层保证完整性）
    emp_no          VARCHAR(64)   NOT NULL,                            -- 工号，业务唯一
    emp_name        VARCHAR(64)   NOT NULL,                            -- 员工姓名
    title           VARCHAR(64)   NULL,                                -- 职务职称
    primary_org_id  BIGINT        NULL,                                -- 主归属机构 ID（P0 单主归属，多机构 junction 随 P1 补）
    status          VARCHAR(16)   NOT NULL,                            -- 状态：ACTIVE 在职/DISABLED 停用
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted         SMALLINT      NOT NULL DEFAULT 0
);

-- 账号与员工一对一、工号业务唯一（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_sys_employee_user_id ON system.sys_employee (user_id) WHERE deleted = 0;
CREATE UNIQUE INDEX uk_sys_employee_emp_no ON system.sys_employee (emp_no) WHERE deleted = 0;

CREATE TRIGGER trg_sys_employee_updated_at BEFORE UPDATE ON system.sys_employee
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 角色表
CREATE TABLE system.sys_role (
    id              BIGINT        PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    role_code       VARCHAR(64)   NOT NULL,                            -- 角色编码，业务唯一
    role_name       VARCHAR(128)  NOT NULL,                            -- 角色名称
    data_scope_type VARCHAR(16)   NOT NULL,                            -- 数据范围：ALL/HOSP/DEPT/WARD/SELF（P0 不做数据范围注入拦截）
    status          VARCHAR(16)   NOT NULL,                            -- 状态：ACTIVE 启用/DISABLED 停用
    remark          VARCHAR(255)  NULL,                                -- 备注
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted         SMALLINT      NOT NULL DEFAULT 0
);

-- 角色编码业务唯一（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_sys_role_role_code ON system.sys_role (role_code) WHERE deleted = 0;

CREATE TRIGGER trg_sys_role_updated_at BEFORE UPDATE ON system.sys_role
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 权限点表
CREATE TABLE system.sys_permission (
    id              BIGINT        PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    perm_code       VARCHAR(128)  NOT NULL,                            -- 权限点编码 = API 路径（M01 FU-M01-03），业务唯一
    perm_name       VARCHAR(128)  NOT NULL,                            -- 权限点名称
    perm_type       VARCHAR(16)   NOT NULL,                            -- 类型：MENU 菜单/API 接口（Spec role_menu/role_api 收敛单表）
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted         SMALLINT      NOT NULL DEFAULT 0
);

-- 权限点编码业务唯一（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_sys_permission_perm_code ON system.sys_permission (perm_code) WHERE deleted = 0;

CREATE TRIGGER trg_sys_permission_updated_at BEFORE UPDATE ON system.sys_permission
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 用户-角色关联表（RBAC0 最小胶水）
CREATE TABLE system.sys_user_role (
    id             BIGINT        PRIMARY KEY,                          -- 雪花 ID（MP ASSIGN_ID）
    user_id        BIGINT        NOT NULL,                             -- 用户 ID
    role_id        BIGINT        NOT NULL,                             -- 角色 ID
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted        SMALLINT      NOT NULL DEFAULT 0
);

-- 同一用户同一角色仅一行授权（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_sys_user_role_user_role ON system.sys_user_role (user_id, role_id) WHERE deleted = 0;

-- ---------------------------------------------------------------- 角色-权限关联表（RBAC0 最小胶水）
CREATE TABLE system.sys_role_permission (
    id             BIGINT        PRIMARY KEY,                          -- 雪花 ID（MP ASSIGN_ID）
    role_id        BIGINT        NOT NULL,                             -- 角色 ID
    permission_id  BIGINT        NOT NULL,                             -- 权限点 ID
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted        SMALLINT      NOT NULL DEFAULT 0
);

-- 同一角色同一权限点仅一行绑定（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_sys_role_permission_role_perm ON system.sys_role_permission (role_id, permission_id) WHERE deleted = 0;
