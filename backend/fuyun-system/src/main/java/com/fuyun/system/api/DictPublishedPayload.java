package com.fuyun.system.api;

/**
 * 字典发布广播事件载荷（system.dict.published，CF-2 首批主数据事件，M01 Spec §7 明示）。
 *
 * <p>触发场景：dict_version DRAFT → PUBLISHED 发布动作广播，订阅方据此刷新字典缓存并按版本对账
 * （M01 Spec §5：同一 type 同一时刻仅一个 PUBLISHED）。事件对象定义在发布方 api 包（backend 宪法 B.3-1），
 * 作为信封 payload 进线格式（字段名全集经 SystemMasterDataPayloadTest 线格式冻结断言）。
 *
 * <p>占位 schema：正式字段随 PR-3 M01 实装冻结，event_registry 登记行 payload_desc 同步。
 *
 * <p>record 纯数据载体（backend 宪法 A.1-2 透明浅不可变）。
 *
 * @param dictType 字典类型编码（type_code，如 gender/icd10），非空；来源：发布方字典版本记录
 * @param version  发布的字典版本号（同 type 内递增），非空；来源：发布方字典版本记录
 */
public record DictPublishedPayload(String dictType, Integer version) {}
