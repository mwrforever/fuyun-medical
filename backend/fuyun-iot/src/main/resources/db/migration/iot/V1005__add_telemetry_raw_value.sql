-- V1005：遥测非数值承载列 raw_value（TASK.md W-7，D-9 裁决 2026-09-14）。
-- 承载改期声明：原计划 P1 PR-1 闭合（TASK.md W-7 复核行），2026-09-25 用户裁决改期 P2 PR-2
-- Task 3 落盘——W-7 红线逐字承责：非数值标量以原文承载、对象/数组以紧凑 JSON 文本承载（应用层
-- Jackson writeValueAsString 标准输出无空格）；value NUMERIC 列仅数值定型行填写（非数值行 NULL，
-- 哨兵值会污染生理指标统计，D-9 裁决理由，禁回填）；quality 维持 isNumeric→BAD 标注（语义=
-- 非数值定型标注，不阻断入库）。
-- 号段说明：原计划号 V405 已废——2026-09-26 迁移号勘误后 iot 增量走通用段（台账
-- docs/migrations/flyway-version-registry.md V1005 行先记再改）；宪法 A.4.1-3 禁改已应用迁移
-- （V402），故本列经新迁移追加而非回填改写。

-- 非数值遥测文本承载列：非数值标量为原文、对象/数组为紧凑 JSON；数值定型行为 NULL
ALTER TABLE iot.iot_telemetry ADD COLUMN raw_value TEXT;

-- value 列放开非空（W-7 红线"value 仅数值定型行填写"的物理前提；非数值行 value=NULL、
-- raw_value 承载原文；演示库为空表瞬时完成，存量非空值不受影响）
ALTER TABLE iot.iot_telemetry ALTER COLUMN value DROP NOT NULL;

COMMENT ON COLUMN iot.iot_telemetry.raw_value IS '非数值遥测文本承载（W-7）：非数值标量为原文、对象/数组为紧凑 JSON；数值定型行为 NULL';
COMMENT ON COLUMN iot.iot_telemetry.value IS '采集值（仅数值定型行填写，非数值行为 NULL，原文见 raw_value）；质量以 quality 列 BAD 标注';
