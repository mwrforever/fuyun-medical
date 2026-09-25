-- V1001：费用归属切分表（M13 住院计费联动，P2 PR-1 Task 13；billing 增量通用段四位数首例，
--   台账 docs/migrations/flyway-version-registry.md 已先记再改——固定段 ≤V699 小于基线全局
--   最大 V900 被乱序守卫拦截，取 V1000+ 并避开 inpatient 段，理由同 pharmacy V1000）。
--
-- 业务定位：消费 inpatient 六事件的住院费用归属时间线落点——入科起费锚点（ADMIT_START，附当日
--   床位费计价入口）、转科归属切分点（TRANSFER，visit_id/from_ward/to_ward/split_at）、出院停费
--   标记（DISCHARGE_STOP，日切任务据此跳过出院就诊）。床日费按时间线切分归 P3，本 PR 落切分点
--   记录（P2 PR-1 Task 13 brief 冻结面）。
--
-- 列面说明（brief DDL 清单外的两列补充，留痕）：patient_id 为日切床位费计价命令入参所需
--   （FeeGenerateCommand.patientId 必填，计费唯一键首要素）；split_type 为三类行共表鉴别列
--   （锚点/切分/停费标记同表承载，日切「在院判定」依赖按类型反查）。
--
-- DDL 公共约定（V600 先例）：审计列 DEFAULT now() + V1 公共触发器；deleted 逻辑删；不建外键
--   （应用层保证）；索引带 WHERE deleted = 0。

CREATE TABLE billing.fee_ownership_split (
    id           BIGINT      PRIMARY KEY,                  -- 雪花（实体 ASSIGN_ID）
    visit_id     VARCHAR(14) NOT NULL,                     -- CF-3 住院就诊号（I 型 14 位）
    patient_id   BIGINT      NOT NULL,                     -- 患者主索引（日切床位费计价命令入参）
    from_ward_id VARCHAR(64) NULL,                         -- 转出病区编码（入科锚点 NULL）
    to_ward_id   VARCHAR(64) NULL,                         -- 转入病区编码（出院停费标记 NULL）
    split_type   VARCHAR(16) NOT NULL,                     -- 切分类型：ADMIT_START 入科起费锚点/TRANSFER 转科归属切分/DISCHARGE_STOP 出院停费标记
    split_at     TIMESTAMPTZ NOT NULL,                     -- 切分时点（事件载荷时点透传，UTC）
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   VARCHAR(64) NOT NULL DEFAULT 'system',
    updated_by   VARCHAR(64) NOT NULL DEFAULT 'system',
    deleted      SMALLINT    NOT NULL DEFAULT 0
);

-- 高频读路径（事件消费幂等守卫按 visit_id+split_type 反查；日切按锚点类型全院扫描）
CREATE INDEX idx_fee_ownership_visit ON billing.fee_ownership_split (visit_id) WHERE deleted = 0;
CREATE INDEX idx_fee_ownership_type ON billing.fee_ownership_split (split_type) WHERE deleted = 0;

-- 幂等硬防线（修复环 R1 补）：ADMIT_START 入科锚点/DISCHARGE_STOP 出院停费标记类行同就诊至多一行
--   ——服务层 check-then-insert 守卫存在读-写间隙，并发重投由本部分唯一索引兜底（冲突方
--   DuplicateKey 幂等吞过，应用层照既有先例容错）；TRANSFER 转科切分行一就诊多次转科多行合法，
--   不纳入唯一约束（事件重投幂等由 eventId 构件幂等承载，同就诊同类型多行属业务正常态）。
CREATE UNIQUE INDEX uk_fee_split_visit_type ON billing.fee_ownership_split (visit_id, split_type)
    WHERE deleted = 0 AND split_type IN ('ADMIT_START', 'DISCHARGE_STOP');

-- updated_at 触发器（V1 公共函数复用，A.4.2-9 应用层禁写）
CREATE TRIGGER trg_fee_ownership_split_updated_at BEFORE UPDATE ON billing.fee_ownership_split
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
