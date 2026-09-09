-- V1：公共审计触发器函数（backend 宪法 A.4.2-9：updated_at 由数据库统一维护，禁止应用层写入）
-- 号段登记（BRIEF-PR2-01 §2.5）：integration 治理域占用 V1-V99（公共域低位）——event_registry/
-- received_event/dead_letter 是全系统统一事件契约台账与幂等落点，仅物理落位 integration schema；
-- 取最低号段保证本迁移先于全部业务迁移执行，规避 outOfOrder=false 下低位段后补触发的乱序拦截。
-- 全项目约定：后续业务表的 updated_at 触发器一律复用本公共函数，禁止各模块重复定义。
CREATE OR REPLACE FUNCTION public.fuyun_set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;
