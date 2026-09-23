-- V807：M05 交接班表（05-nursing Spec §4 shift_handover / 调研依据 7 SBAR 结构化交接班）
--   内容由系统按本班业务数据自动汇总生成草稿，人工补充后双班签名；
--   待续事项的在途输注/未闭环告警引用随 M14/M16 接入（P2），P1 为空数组（Spec 注记登记）。

CREATE TABLE nursing.shift_handover (
    id                   BIGINT       PRIMARY KEY,
    handover_no          VARCHAR(32)  NOT NULL,                            -- 交接班单号（HO+yyyyMMdd+5 位流水）
    ward_id              VARCHAR(64)  NOT NULL,                            -- 病区编码
    shift_code           VARCHAR(32)  NOT NULL,                            -- 班次 code（取病区班次定义）
    handover_date        DATE         NOT NULL,                            -- 交接班日期（列表按日检索）
    outgoing_nurse_id    VARCHAR(64)  NOT NULL,                            -- 交班护士
    incoming_nurse_id    VARCHAR(64)  NULL,                                -- 接班护士（完成签署时写入）
    patient_summary      JSONB        NOT NULL,                            -- 患者摘要快照 {total,specialCount,criticalCount,newAdmissionCount,surgeryCount,todayDischargeCount,transferOutCount}
    sbar_situation       TEXT         NULL,                                -- S 现状
    sbar_background      TEXT         NULL,                                -- B 背景
    sbar_assessment      TEXT         NULL,                                -- A 评估
    sbar_recommendation  TEXT         NULL,                                -- R 建议
    pending_items        JSONB        NOT NULL DEFAULT '[]',               -- 待续事项：在途任务清单 [{taskNo,taskType,planTime,overdueFlag,visitId}]
    pending_infusions    JSONB        NOT NULL DEFAULT '[]',               -- 待续事项：在途输注（P2 接入）
    unclosed_alarms      JSONB        NOT NULL DEFAULT '[]',               -- 待续事项：未闭环告警（P2 接入）
    outgoing_signed_at   TIMESTAMPTZ  NULL,                                -- 交班签名时间
    incoming_signed_at   TIMESTAMPTZ  NULL,                                -- 接班签名时间
    status               VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',            -- DRAFT 草稿 / SIGNING 签署中 / COMPLETED 已完成
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by           VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted              SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.shift_handover IS 'SBAR 结构化交接班（自动汇总 + 双班签名；未完成不阻塞业务）';
CREATE UNIQUE INDEX uk_shift_handover_no ON nursing.shift_handover (handover_no) WHERE deleted = 0;
CREATE INDEX idx_shift_handover_ward_date ON nursing.shift_handover (ward_id, handover_date);
CREATE TRIGGER trg_shift_handover_updated_at BEFORE UPDATE ON nursing.shift_handover
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
