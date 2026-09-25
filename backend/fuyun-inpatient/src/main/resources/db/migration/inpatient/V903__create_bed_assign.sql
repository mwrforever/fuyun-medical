-- V903：M04 床位管理域两表（04-inpatient Spec §4/§3.5/§5；P2 PR-1 Task 4；
--   台账 docs/migrations/flyway-version-registry.md 已先记再改）。
--   ① bed        床位主数据——五态状态机（FREE/RESERVED/OCCUPIED/DISINFECTING/MAINTENANCE）由
--                条件更新（CAS）+ 影响行数判定驱动，防重复占床为硬防线（仅 FREE/RESERVED 可占床）；
--                包床（PRIVATE）为 bed_attr 计费属性标记，不占用状态位；visit_id 为当前占用冗余列
--                （权威在 bed_assign 未闭合行；RESERVED 预占不绑定 visit——登记确认才签发 visit_id）
--   ② bed_assign 床位占用流水（只增表）——床位历史占用回溯依据；ended_at 由转移/出院动作闭合，
--                未闭合行每床至多一条（部分唯一索引兜底并发开账）
-- 通用约定：雪花 BIGINT 主键（MP ASSIGN_ID）、审计五列、TIMESTAMPTZ 服务器时间、逻辑删、状态 VARCHAR 常量。

CREATE TABLE inpatient.bed (
    id           BIGINT       PRIMARY KEY,
    bed_no       VARCHAR(32)  NOT NULL,                          -- 床号（病区内唯一，uk_bed_ward_no 复合承载）
    ward_id      VARCHAR(64)  NOT NULL,                          -- 归属病区编码（M01 组织机构 code；转床轻量路径限定同病区）
    bed_attr     VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',         -- 床位属性：NORMAL 普通 / PRIVATE 包床（计费属性非状态） / EXTRA 加床
    allow_gender VARCHAR(8)   NULL,                              -- 性别限制：MALE 男 / FEMALE 女 / NULL 不限
    visit_id     VARCHAR(14)  NULL,                              -- 当前占用住院就诊号（I 型 14 位；冗余列权威在 bed_assign 未闭合行；预占不绑定）
    status       VARCHAR(20)  NOT NULL DEFAULT 'FREE',           -- FREE 空床 / RESERVED 预占（预约入院/转科预占/全院签床） / OCCUPIED 占床 / DISINFECTING 消毒中（转出/出院后） / MAINTENANCE 维修中（五态词表，状态机见 Spec §5）
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by   VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted      SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE inpatient.bed IS '床位主数据：病区床位图数据源；五态状态机 CAS 驱动（防重复占床硬防线）；包床为 bed_attr 计费属性非状态';
CREATE UNIQUE INDEX uk_bed_ward_no ON inpatient.bed (ward_id, bed_no) WHERE deleted = 0;
CREATE INDEX idx_bed_ward_status ON inpatient.bed (ward_id, status);
CREATE TRIGGER trg_bed_updated_at BEFORE UPDATE ON inpatient.bed
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE inpatient.bed_assign (
    id          BIGINT       PRIMARY KEY,
    bed_id      BIGINT       NOT NULL,                           -- 占用床位 id（bed 1:N bed_assign，未闭合行每床至多一条）
    visit_id    VARCHAR(14)  NOT NULL,                           -- 住院就诊号（I 型 14 位；占用主体，历史占用回溯键）
    assign_type VARCHAR(16)  NOT NULL,                           -- 占用类型：ADMISSION 入院分配 / BED_CHANGE 转床 / WARD_TRANSFER 转科转入
    started_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),             -- 占用开始时点（库端时间）
    ended_at    TIMESTAMPTZ  NULL,                               -- 占用结束时点（转移/出院动作闭合；NULL=未闭合在用行）
    operator    VARCHAR(64)  NOT NULL,                           -- 操作者（开账护士/系统联动操作者）
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by  VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted     SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE inpatient.bed_assign IS '床位占用流水（只增表）：床位历史占用回溯依据；ended_at 由转移/出院动作闭合；每床未闭合行唯一';
CREATE UNIQUE INDEX uk_bed_assign_open ON inpatient.bed_assign (bed_id) WHERE ended_at IS NULL AND deleted = 0;
CREATE INDEX idx_bed_assign_visit ON inpatient.bed_assign (visit_id);
CREATE TRIGGER trg_bed_assign_updated_at BEFORE UPDATE ON inpatient.bed_assign
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
