package com.fuyun.system.api;

/**
 * 参数变更事件载荷（system.param.changed，CF-2 首批主数据事件，M01 Spec §7 明示）。
 *
 * <p>触发场景：系统参数变更发 system.param.changed（带 module，FU-M01-07 Spec 明示），
 * 相关模块按 module + paramKey 定位并热刷新参数缓存。事件对象定义在发布方 api 包
 * （backend 宪法 B.3-1），字段名全集经 SystemMasterDataPayloadTest 线格式冻结断言。
 *
 * <p>占位 schema：正式字段随 PR-3 M01 实装冻结，event_registry 登记行 payload_desc 同步。
 *
 * <p>record 纯数据载体（backend 宪法 A.1-2 透明浅不可变）。
 *
 * @param module   参数所属模块域标识（如 billing/pharmacy），非空；来源：参数登记记录
 * @param paramKey 参数键（模块域内唯一），非空；来源：参数登记记录
 */
public record ParamChangedPayload(String module, String paramKey) {}
