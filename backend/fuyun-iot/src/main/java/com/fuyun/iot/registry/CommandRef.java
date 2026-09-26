package com.fuyun.iot.registry;

/**
 * 命令回执载体（IotDeviceRegistry.sendCommand 出参，record 不可变）：命令下发受理标识与结果
 * 摘要。华为实现取同步命令响应的 commandId/response；模拟实现即时回执 SUCCESS（行为等价
 * 契约之一）。
 *
 * @param commandId 注册中心命令标识，非空；来源：命令下发响应（命令结果回查键）
 * @param result    结果摘要（华为=云端 response 摘要/模拟=SUCCESS），可空（云端无回执体时为 null）
 */
public record CommandRef(String commandId, String result) {}
