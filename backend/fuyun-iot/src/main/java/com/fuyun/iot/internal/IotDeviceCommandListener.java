package com.fuyun.iot.internal;

import com.fuyun.iot.internal.TelemetryFrameParser.ParsedFrame.CommandResultFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * IoTDA 命令结果帧消费入口（P2 PR-2 Task 8，AMQP 命令状态队列帧的消费域承接点）：接收
 * IotAmqpTelemetryConsumer 按 TelemetryFrameParser 第五形态（resource=device.command.status）
 * 分派的解析产物，移交命令下发编排器执行终态迁移与 iot.command.completed 事件发布。
 *
 * <p>职责边界：本类只承载命令域的消费受理（受理留痕 + 编排委托），帧确认语义归消费者
 * （命令帧即时确认，处理失败按业务失败触发会话重建令帧回归重投域，CAS 幂等兜底）。
 * 归 internal/ 包：容器驱动入口不外引（宪法 B.1），装配归 IotConfig @Import。
 */
@Slf4j
public class IotDeviceCommandListener {

    private final CommandDispatcher dispatcher;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param dispatcher 命令下发编排器，非空；结果回推终态迁移与事件发布执行点
     */
    @Autowired
    public IotDeviceCommandListener(CommandDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 命令结果帧受理（消费者分派回调）：受理留痕后移交编排器。
     *
     * @param frame 命令状态帧解析产物，非空
     */
    public void onCommandResultFrame(CommandResultFrame frame) {
        log.info(
                "命令结果帧受理：deviceId={}，commandId={}，registryStatus={}，occurredAt={}",
                frame.deviceId(),
                frame.commandId(),
                frame.registryStatus(),
                frame.occurredAt());
        dispatcher.completeFromResultFrame(frame);
    }
}
