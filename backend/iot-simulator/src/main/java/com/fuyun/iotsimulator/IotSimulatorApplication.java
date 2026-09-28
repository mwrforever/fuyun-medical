package com.fuyun.iotsimulator;

import com.fuyun.iotsimulator.config.SimulatorConfig;
import com.fuyun.iotsimulator.mqtt.CommandSubscriber;
import com.fuyun.iotsimulator.mqtt.DeviceStatusReporter;
import com.fuyun.iotsimulator.mqtt.IotdaMqttClient;
import com.fuyun.iotsimulator.scenario.Scenario;
import com.fuyun.iotsimulator.scenario.ScenarioFactory;
import com.fuyun.iotsimulator.scenario.VitalsScenario;
import com.fuyun.iotsimulator.telemetry.DeviceCredentialEncoder;
import com.fuyun.iotsimulator.telemetry.TelemetryPayloadBuilder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模拟设备主程序（BRIEF-PR4-01 §5，D-3 裁决纯 Java 零 Spring；P2 PR-2 Task 14 生产级升级）：
 * 读取 IOTDA_* 环境变量装配一机一密凭证与剧本引擎（vitals 既有体征循环 / infusion 输液状态机），
 * 经 MQTT 单线程周期上行至 IoTDA（或本地联调 broker）——剧本档位变化另行上行 status 属性帧，
 * 命令下行订阅挂接剧本回调并回执（result_code 0/1），打通 IoTDA→AMQP→库→WebSocket→命令回推
 * 全链路演示（compose profile sim）。
 *
 * <p><b>线程纪律（宪法 B.3-4 同等适用）</b>：单线程命名调度器（iot-simulator-reporter），
 * 固定延迟调度；命令处理在 CommandSubscriber 内部 daemon 单线程执行器；JVM shutdownHook
 * 优雅停机——先取消周期任务、停调度器，再断开 MQTT 连接（daemon 线程不阻塞进程退出）。
 * <b>deviceSecret 禁入任何日志</b>（日志业务标识仅 deviceId）。
 */
public final class IotSimulatorApplication {

    private static final Logger log = LoggerFactory.getLogger(IotSimulatorApplication.class);

    /** 周期上行线程名（单线程命名调度器，宪法 B.3-4） */
    private static final String REPORT_THREAD_NAME = "iot-simulator-reporter";

    /** 停机钩子线程名 */
    private static final String SHUTDOWN_THREAD_NAME = "iot-simulator-shutdown";

    private IotSimulatorApplication() {}

    /**
     * 模拟设备入口：env 装配配置 → 建链 → 命令下行订阅 → 周期上行（非 daemon 调度线程承载
     * JVM 存活）→ 注册优雅停机钩子后装配返回；外部终止信号（docker stop SIGTERM）触发钩子
     * 完成断连退出。
     *
     * <p>启动失败（必填缺失/建链被拒/订阅失败）以异常终止进程并输出中文原因，容器编排按
     * 失败重启处置。
     *
     * @param args 未使用（配置全部经环境变量注入）
     */
    public static void main(String[] args) {
        SimulatorConfig config = SimulatorConfig.fromEnv(System.getenv());
        launch(config);
    }

    /**
     * 装配并启动模拟设备（生产入口）：凭证编码 → 建链 → 剧本装配（ScenarioFactory 按档产出）
     * → 命令下行订阅（回执供 Task 8 结果回推链路真栈演示）→ 周期上行 → 停机钩子。
     *
     * @param config 模拟设备配置，非空；来源：SimulatorConfig.fromEnv（scenario/scenarioSpeed 承载剧本档）
     */
    static void launch(SimulatorConfig config) {
        DeviceCredentialEncoder.MqttCredential credential;
        IotdaMqttClient client;
        try {
            credential = DeviceCredentialEncoder.encode(
                    config.deviceId(), config.deviceSecret(), java.time.Clock.systemUTC());
            client = new IotdaMqttClient(config.mqttHost(), credential);
            client.connect();
        } catch (Exception e) {
            log.error("模拟设备建链失败，进程终止：deviceId={}，host={}，原因={}", config.deviceId(), config.mqttHost(), e.getMessage());
            throw new IllegalStateException("模拟设备建链失败（host=" + config.mqttHost() + "）", e);
        }
        Scenario scenario = ScenarioFactory.create(config);
        DeviceStatusReporter statusReporter = new DeviceStatusReporter(client);
        try {
            // 命令回调挂接剧本（暂停/恢复滴注等）：回执 result_code 语义与 Task 8 消费端终态词表对齐
            new CommandSubscriber(client, config.deviceId(), scenario::handleCommand).subscribe();
        } catch (Exception e) {
            client.close();
            log.error("命令下行订阅失败，进程终止：deviceId={}，原因={}", config.deviceId(), e.getMessage());
            throw new IllegalStateException("命令下行订阅失败（deviceId=" + config.deviceId() + "）", e);
        }
        launchReporting(config, client, scenario, statusReporter);
        log.info(
                "模拟设备已启动：deviceId={}，host={}，上行周期={}s，剧本={}（倍速 {}），上行主题={}（deviceSecret 不打印）",
                config.deviceId(),
                config.mqttHost(),
                config.reportIntervalSeconds(),
                scenario.name(),
                config.scenarioSpeed(),
                IotdaMqttClient.TOPIC_PROPERTIES_REPORT_TEMPLATE.formatted(config.deviceId()));
    }

    /**
     * 装配周期上行循环（P0 兼容入口，既有单测锚定）：按配置档装配剧本（未感知剧本能力的
     * 调用方维持 vitals 既有行为）与状态帧上报器后走全量装配。
     *
     * @param config 模拟设备配置，非空；reportIntervalSeconds 为上行档（秒）
     * @param client MQTT 客户端，非空；生产为真实实例，单测为 Mockito 桩
     * @return 运行中的调度器，非空；调用方（测试）可 shutdownNow 收尾
     */
    static ScheduledExecutorService launchReporting(SimulatorConfig config, IotdaMqttClient client) {
        return launchReporting(config, client, ScenarioFactory.create(config), new DeviceStatusReporter(client));
    }

    /**
     * 装配周期上行循环（全量装配）：单线程命名调度器按配置档固定延迟触发上行周期体
     * （遥测帧 + 剧本档位状态帧），并注册 shutdownHook 优雅停机（取消任务 → 停调度器 →
     * 断连接）。调度线程为非 daemon——main 装配完成即返回，由该线程承载 JVM 存活（docker
     * stop 的 SIGTERM 触发停机钩子后调度器终止、进程随之退出）。
     *
     * @param config         模拟设备配置，非空；reportIntervalSeconds 为上行档（秒）
     * @param client         MQTT 客户端，非空；生产为真实实例，单测为 Mockito 桩
     * @param scenario       剧本实例，非空；来源：ScenarioFactory.create
     * @param statusReporter 状态帧上报器，非空；剧本档位公告的 MQTT 承载
     * @return 运行中的调度器，非空；调用方（测试）可 shutdownNow 收尾
     */
    static ScheduledExecutorService launchReporting(
            SimulatorConfig config, IotdaMqttClient client, Scenario scenario, DeviceStatusReporter statusReporter) {
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, REPORT_THREAD_NAME));
        ScheduledFuture<?> reportTask = scheduler.scheduleWithFixedDelay(
                () -> runReportCycle(client, scenario, statusReporter),
                config.reportIntervalSeconds(),
                config.reportIntervalSeconds(),
                TimeUnit.SECONDS);
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> stopReporting(reportTask, scheduler, client, config.deviceId()), SHUTDOWN_THREAD_NAME));
        return scheduler;
    }

    /**
     * 优雅停机（shutdownHook 与单测共用的收口动作）：先停触发源（取消周期任务、停调度器），
     * 再断连接（残留帧由 qos=1 重试语义兜底）。
     *
     * @param reportTask 周期上行任务，非空
     * @param scheduler 上行调度器，非空
     * @param client MQTT 客户端，非空
     * @param deviceId 设备标识（停机日志业务标识），非空
     */
    static void stopReporting(
            ScheduledFuture<?> reportTask,
            ScheduledExecutorService scheduler,
            IotdaMqttClient client,
            String deviceId) {
        reportTask.cancel(false);
        scheduler.shutdownNow();
        client.close();
        log.info("模拟设备已优雅停机：deviceId={}", deviceId);
    }

    /**
     * 单次上行周期体（剧本形态）：产出一帧遥测 JSON 并发布，随后将本周期剧本档位公告逐条
     * 转 status 属性帧上行；单帧失败（断链中等）吞并记录——固定延迟调度下任务抛错即被取消，
     * 吞并是上行循环持续存活的防线（断链恢复后自动续传）。
     *
     * @param client MQTT 客户端，非空
     * @param scenario 剧本实例，非空（遥测帧与档位公告来源）
     * @param statusReporter 状态帧上报器，非空
     */
    static void runReportCycle(IotdaMqttClient client, Scenario scenario, DeviceStatusReporter statusReporter) {
        try {
            client.publish(scenario.nextFrame());
            for (String phaseCode : scenario.drainPhaseTransitions()) {
                // 剧本阶段变化 → status 属性帧（供 Task 7 透传规则与设备状态链演示）
                statusReporter.reportPhase(phaseCode);
            }
        } catch (Exception e) {
            log.error("上行单帧失败（下周期自动重试，Paho 断链自动重连兜底）：{}", e.getMessage());
        }
    }

    /**
     * 单次上行周期体（vitals 形态，P0 兼容入口——既有单测锚定）：既有确定性体征序列经剧本
     * 引擎同路径发布（行为零变化）。
     *
     * @param client MQTT 客户端，非空
     * @param payloadBuilder 体征载荷构造器，非空（同设备确定性序列）
     */
    static void runReportCycle(IotdaMqttClient client, TelemetryPayloadBuilder payloadBuilder) {
        runReportCycle(client, new VitalsScenario(payloadBuilder), new DeviceStatusReporter(client));
    }
}
