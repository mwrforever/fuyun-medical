-- V1009：命令下发日志单表（FU-M14-09，P2 PR-2 Task 8）——iot_command_log。
-- 号段说明：iot 增量走通用段（V1008 先例，台账 docs/migrations/flyway-version-registry.md
--   V1009 行先记再改；宪法 A.4.1-3 禁改已应用迁移）。
-- DDL 公共约定（V400/V1008 先例）：审计列由数据库维护（DEFAULT now() + V1 公共触发器函数，backend
--   宪法 A.4.2-9）；deleted 逻辑删标记；不建外键约束（关联完整性应用层保证）。
-- 主键口径：雪花代理 id（MP ASSIGN_ID，宪法 A.4.3-16）——命令行以 command_no 业务号对外
--   （IotSeqGate.nextCommandNo，CMD{yyyyMMdd}{%05d}；凭证签发时预占，下发时随凭证消费落行）。
-- 业务意图（14-iot.md FU-M14-09 / 方案 3.5）：白名单+二次确认+五步下发的全要素留痕——操作者/
--   设备/命令/参数/安全等级/凭证引用/通道/状态/时点/错误/追踪号一行承载；治疗级豁免使用的审计面
--   由 safety_level=TREATMENT 行本身与 M01 审计切面双留痕（模块红线 3：白名单与豁免必须留痕可审计）。
-- 状态机（14-iot §词汇）：ISSUED 已下发 → DELIVERED 已送达（设备确认收到）→ SUCCESS 执行成功 /
--   FAILED 执行失败；ISSUED 停留超时 → TIMEOUT（终态）；终态不可变更（CAS 以旧状态限定兜底）。

CREATE TABLE iot.iot_command_log (
    id             BIGINT       PRIMARY KEY,                     -- 雪花 ID（MP ASSIGN_ID）
    command_no     VARCHAR(32)  NOT NULL,                        -- 命令业务号（CMD{yyyyMMdd}{%05d}，IotSeqGate 签发，全局唯一）
    device_id      VARCHAR(64)  NOT NULL,                        -- 目标设备号（关联 iot_device，应用层保证存在）
    command_name   VARCHAR(64)  NOT NULL,                        -- 命令名称（物模型 commands[].name）
    command_params JSONB        NULL,                            -- 命令参数快照（下发请求原文，无参命令为空）
    safety_level   VARCHAR(16)  NOT NULL,                        -- 命令安全等级：SAFETY 安全级/TREATMENT 治疗级（白名单标注快照）
    operator       VARCHAR(64)  NOT NULL,                        -- 操作人（操作者上下文，无登录上下文回退 system）
    confirm_ref    VARCHAR(64)  NULL,                            -- 确认引用：二次确认凭证标识 challengeId（凭证一次性消费后的回溯锚）
    deliver_mode   VARCHAR(8)   NOT NULL,                        -- 下发通道：SYNC 在线同步（等待回执）/ASYNC 离线异步（待结果帧回推）
    status         VARCHAR(16)  NOT NULL DEFAULT 'ISSUED',       -- 命令状态：ISSUED 已下发/DELIVERED 已送达/SUCCESS 成功/FAILED 失败/TIMEOUT 超时
    issued_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),          -- 下发时刻（落行时点）
    result_at      TIMESTAMPTZ  NULL,                            -- 结果时刻（回执/结果帧/超时判定的业务时点）
    error_msg      VARCHAR(500) NULL,                            -- 失败原因（SUCCESS 终态为空；取回执/结果帧失败摘要，超时取判定文案）
    trace_id       VARCHAR(64)  NULL,                            -- 全链路追踪号（下发请求 MDC 捕获，跨链路排查锚）
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted        SMALLINT     NOT NULL DEFAULT 0
);

-- 命令号唯一（对外主标识，查询端点按号定位）
CREATE UNIQUE INDEX uk_iot_command_no ON iot.iot_command_log (command_no);
-- 命令列表查询路径（GET /commands 按设备+状态过滤分页，issued_at 降序）
CREATE INDEX idx_iot_command_device_status ON iot.iot_command_log (device_id, status);
-- 异步结果帧回推定位路径：设备维度未终态异步行（FIFO 最早下发行即结果归属行，见
-- IotCommandLogMapper.selectOutstandingAsync；仓库无真实结果帧样例，按 IoTDA 文档形态实现并注记）
CREATE INDEX idx_iot_command_async_outstanding ON iot.iot_command_log (device_id, issued_at)
    WHERE deliver_mode = 'ASYNC' AND status IN ('ISSUED', 'DELIVERED') AND deleted = 0;

CREATE TRIGGER trg_iot_command_log_updated_at BEFORE UPDATE ON iot.iot_command_log
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 列注释（枚举词表入列注释）
COMMENT ON TABLE iot.iot_command_log IS 'IoT 命令下发日志表（FU-M14-09）：白名单+二次确认+五步下发的全要素留痕与状态机载体';
COMMENT ON COLUMN iot.iot_command_log.id IS '雪花 ID（MP ASSIGN_ID）';
COMMENT ON COLUMN iot.iot_command_log.command_no IS '命令业务号（CMD{yyyyMMdd}{%05d}，IotSeqGate 签发，凭证签发时预占）';
COMMENT ON COLUMN iot.iot_command_log.device_id IS '目标设备号（关联 iot_device）';
COMMENT ON COLUMN iot.iot_command_log.command_name IS '命令名称（物模型 commands[].name）';
COMMENT ON COLUMN iot.iot_command_log.command_params IS '命令参数快照（JSONB，下发请求原文，无参命令为空）';
COMMENT ON COLUMN iot.iot_command_log.safety_level IS '命令安全等级：SAFETY 安全级（默认放行）/TREATMENT 治疗级（默认禁放行，豁免开关显式开启方放行）';
COMMENT ON COLUMN iot.iot_command_log.operator IS '操作人（操作者上下文，无登录上下文回退 system）';
COMMENT ON COLUMN iot.iot_command_log.confirm_ref IS '确认引用：二次确认凭证标识 challengeId（GETDEL 一次性消费，回溯锚）';
COMMENT ON COLUMN iot.iot_command_log.deliver_mode IS '下发通道：SYNC 在线同步（等待回执更新终态）/ASYNC 离线异步（置 ISSUED 待结果帧回推）';
COMMENT ON COLUMN iot.iot_command_log.status IS '命令状态：ISSUED 已下发/DELIVERED 已送达/SUCCESS 执行成功/FAILED 执行失败/TIMEOUT 超时（终态不可变更）';
COMMENT ON COLUMN iot.iot_command_log.issued_at IS '下发时刻（落行时点）';
COMMENT ON COLUMN iot.iot_command_log.result_at IS '结果时刻（回执/结果帧/超时判定时点）';
COMMENT ON COLUMN iot.iot_command_log.error_msg IS '失败原因（SUCCESS 终态为空；回执/结果帧失败摘要或超时判定文案）';
COMMENT ON COLUMN iot.iot_command_log.trace_id IS '全链路追踪号（下发请求 MDC 捕获）';
