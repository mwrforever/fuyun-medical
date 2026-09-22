-- V802：M05 护理文书骨架三表（05-nursing Spec §4 / §113 护理记录状态机 / 红线 2 护理文书）
--   ① nursing_record           护理记录单（一般/病重病危表格式；提交锁定 + 修订留痕原值可见；签名引用 P2 接 CA）
--   ② temperature_chart_page   体温单月页（住院月页；手术后天数（M10）与归档随 P2/P4 注记）
--   ③ temperature_chart_entry  体温单条目（VITAL 体征引用 / SPECIAL_EVENT 特殊事件 / DAILY_VALUE 日行值）
-- 唯一约束形态说明：类型键 type_key 由**服务写入**（VITAL 条目=体温部位 temp_site、SPECIAL_EVENT=事件类型、
--   DAILY_VALUE=日行值类型），使「(page_id, entry_time, entry_type, 类型键) 唯一」（Spec :110）可建部分唯一索引，
--   且同刻不同体温部位的体征条目互不冲突（Spec :108 允许同刻异部位）。

CREATE TABLE nursing.nursing_record (
    id              BIGINT       PRIMARY KEY,
    record_no       VARCHAR(32)  NOT NULL,                                 -- 护理记录号（NR+yyyyMMdd+5 位流水，本模块签发）
    visit_id        VARCHAR(14)  NOT NULL,                                 -- 住院就诊号（I 型）
    patient_id      BIGINT       NOT NULL,                                 -- 患者 ID
    ward_id         VARCHAR(64)  NOT NULL,                                 -- 病区编码
    record_class    VARCHAR(16)  NOT NULL,                                 -- GENERAL 一般 / CRITICAL 病重病危
    record_time     TIMESTAMPTZ  NOT NULL,                                 -- 记录时间（服务器时间，Spec 红线 2）
    observation     TEXT         NULL,                                     -- 病情观察（结构化段）
    measures        TEXT         NULL,                                     -- 护理措施（结构化段）
    evaluation      TEXT         NULL,                                     -- 效果评价（结构化段）
    free_text       TEXT         NULL,                                     -- 自由文本补充
    ref_vital_id    BIGINT       NULL,                                     -- 关联体征记录引用（Task 5 回填）
    ref_event_ref   VARCHAR(64)  NULL,                                     -- 关联特殊事件引用（type_key）
    auto_generated  BOOLEAN      NOT NULL DEFAULT false,                   -- 是否由体征归集自动生成（观察行）
    abnormal_flag   BOOLEAN      NOT NULL DEFAULT false,                   -- 观察行异常标记——异常归集新建行置 true，正常归集合并谓词限定 false，兼防 selectOne 多行
    signature_ref   VARCHAR(64)  NULL,                                     -- 电子签名引用（M01 CA，P2 接；P1 留痕=operator+signed_at）
    signed_operator VARCHAR(64)  NULL,                                     -- 签名操作者（P1 有效留痕）
    signed_at       TIMESTAMPTZ  NULL,                                     -- 签名时间
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',                 -- DRAFT 草稿 / SUBMITTED 已提交锁定 / REVISED 修订件
    revised_from    VARCHAR(32)  NULL,                                     -- 修订链：被修订的原记录号（原值可见靠原行保留）
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted         SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.nursing_record IS '护理记录单（提交锁定、修订留痕原值可见；随病历长期保存）';
CREATE UNIQUE INDEX uk_nursing_record_no ON nursing.nursing_record (record_no) WHERE deleted = 0;
CREATE INDEX idx_nursing_record_visit_time ON nursing.nursing_record (visit_id, record_time);
CREATE TRIGGER trg_nursing_record_updated_at BEFORE UPDATE ON nursing.nursing_record
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE nursing.temperature_chart_page (
    id         BIGINT       PRIMARY KEY,
    visit_id   VARCHAR(14)  NOT NULL,                                      -- 住院就诊号（I 型）
    chart_month VARCHAR(7)  NOT NULL,                                      -- 住院月页（yyyy-MM）
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',                     -- ACTIVE 进行中 / ARCHIVED 已归档
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted    SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.temperature_chart_page IS '体温单月页（住院天数/术后天数等要素的页面维度）';
CREATE UNIQUE INDEX uk_chart_page_visit_month ON nursing.temperature_chart_page (visit_id, chart_month) WHERE deleted = 0;
CREATE TRIGGER trg_chart_page_updated_at BEFORE UPDATE ON nursing.temperature_chart_page
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE nursing.temperature_chart_entry (
    id                 BIGINT       PRIMARY KEY,
    page_id            BIGINT       NOT NULL,                              -- 月页 ID
    entry_time         TIMESTAMPTZ  NOT NULL,                              -- 条目时点
    entry_type         VARCHAR(16)  NOT NULL,                              -- VITAL 体征引用 / SPECIAL_EVENT 特殊事件 / DAILY_VALUE 日行值
    vital_ref          BIGINT       NULL,                                  -- 体征记录引用（entry_type=VITAL）
    special_event_type VARCHAR(32)  NULL,                                  -- 特殊事件类型（entry_type=SPECIAL_EVENT）
    daily_value_type   VARCHAR(32)  NULL,                                  -- 日行值类型（entry_type=DAILY_VALUE：STOOL_COUNT/BODY_WEIGHT/HEIGHT/IO_SUMMARY_SHIFT/IO_SUMMARY_24H/SKIN_TEST）
    type_key           VARCHAR(32)  NOT NULL DEFAULT '',                   -- 类型键（唯一约束第四维；服务写入：VITAL=体温部位、SPECIAL_EVENT=事件类型、DAILY_VALUE=日行值类型）
    value_text         VARCHAR(128) NULL,                                  -- 日行值文本（如「入 2500 / 出 2100」）
    recorder_id        VARCHAR(64)  NULL,                                  -- 记录人
    recorder_name      VARCHAR(64)  NULL,                                  -- 记录人姓名
    remark             VARCHAR(255) NULL,                                  -- 备注（物理降温前体温等）
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by         VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted            SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.temperature_chart_entry IS '体温单条目（体征符号由前端按体温部位渲染：腋温×/口温●/肛温〇；物理降温红圈红虚线、脉搏短绌短红线）';
CREATE UNIQUE INDEX uk_chart_entry_key ON nursing.temperature_chart_entry (page_id, entry_time, entry_type, type_key) WHERE deleted = 0;
CREATE INDEX idx_chart_entry_page_time ON nursing.temperature_chart_entry (page_id, entry_time);
CREATE TRIGGER trg_chart_entry_updated_at BEFORE UPDATE ON nursing.temperature_chart_entry
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
