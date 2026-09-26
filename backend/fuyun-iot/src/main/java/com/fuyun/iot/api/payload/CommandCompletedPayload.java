package com.fuyun.iot.api.payload;

import java.time.Instant;

/**
 * 命令结果回推事件载荷（iot.command.completed，V1004 id 79 冻结契约）：设备命令下发终态回推时
 * 发布，M05/M16 据此回写命令执行结果与告知上下文。
 *
 * @param commandNo   命令业务号，非空；来源：命令下发域签发
 * @param deviceId    IoTDA 设备标识，非空；来源：命令目标设备
 * @param commandName 命令名，非空；来源：产品物模型命令定义
 * @param status      执行结果状态，非空（成功/失败/超时等终态，值域随命令域冻结）；来源：设备回执
 * @param operator    操作人，非空；来源：命令下发操作上下文
 * @param completedAt 终态时点（UTC），非空；来源：设备回执或超时判定时刻
 * @param errorMsg    失败原因，可空（成功终态为 null）；来源：设备回执错误信息
 */
public record CommandCompletedPayload(
        String commandNo,
        String deviceId,
        String commandName,
        String status,
        String operator,
        Instant completedAt,
        String errorMsg) {}
