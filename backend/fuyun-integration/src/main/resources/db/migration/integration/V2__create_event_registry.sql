-- V2：事件契约台账表（M20 Spec §3，全系统事件先登记后订阅的治理落点）
-- updated_at 由 V1 公共触发器函数维护；订阅登记（registerSubscriber）产生 UPDATE 生命周期故挂触发器；
-- received_event/dead_letter 为只增台账（B2.2），时间列由 DEFAULT now() 维护，无通用 updated_at 语义不挂触发器。
CREATE TABLE integration.event_registry (
    id                 BIGINT        PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    event_type         VARCHAR(128)  NOT NULL,                            -- 事件类型 <模块>.<实体>.<动作>
    producer_module    VARCHAR(32)   NOT NULL,                            -- 生产模块域标识
    payload_desc       VARCHAR(1000) NOT NULL,                            -- 载荷结构说明（冻结契约摘要）
    subscriber_modules VARCHAR(500)  NOT NULL DEFAULT '',                 -- 订阅模块清单（逗号分隔；broadcast=零订阅广播，R6-13）
    status             VARCHAR(16)   NOT NULL,                            -- ACTIVE 生效 / DEPRECATED 废止
    registered_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),              -- 业务登记时间
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by         VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by         VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted            SMALLINT      NOT NULL DEFAULT 0
);

-- 契约业务唯一：同事件类型仅一行（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_event_registry_event_type ON integration.event_registry (event_type) WHERE deleted = 0;

-- updated_at 触发器：复用 V1 公共函数，应用层禁止写入该列（backend 宪法 A.4.2-9）
CREATE TRIGGER trg_event_registry_updated_at BEFORE UPDATE ON integration.event_registry
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
