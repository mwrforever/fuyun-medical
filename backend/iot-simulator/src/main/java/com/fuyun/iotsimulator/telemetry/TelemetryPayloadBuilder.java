package com.fuyun.iotsimulator.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Random;

/**
 * 物模型 properties/report 上行载荷构造器（BRIEF-PR4-01 §5）：产出 IoTDA 物模型标准形态
 * {@code {"services":[{"service_id":"Monitor","properties":{...}}]}} 的监护体征演示帧。
 *
 * <p><b>确定性伪随机序列</b>：体征值取自固定 seed 的 {@link Random} 序列——同 seed 必然回放
 * 出逐字符相同的帧序列，联调比对后端消费链路时可用同一 seed 复现全量上行内容。生产构造以
 * deviceId 派生 seed（{@link String#hashCode()} 有跨 JVM 规范保证），同一设备每次启动回放
 * 同一序列，便于 IoTDA 侧报文与库内落库的逐帧对账。
 *
 * <p>service_id 与属性键为 P0 演示字面量（P0 未建 iot_metric_dict，原生编码直传）；真实产品
 * 物模型冻结后仅调整常量与属性写入段。实例非线程安全：仅由单一调度线程调用（宪法 B.3-4
 * 单线程命名调度器纪律），无需加锁。
 */
public class TelemetryPayloadBuilder {

    /** 物模型服务标识（P0 演示字面量，对齐 14-iot 监护设备模型口径） */
    public static final String SERVICE_ID = "Monitor";

    /** 体征属性键：心率（bpm） */
    public static final String PROP_HEART_RATE = "heartRate";

    /** 体征属性键：血氧饱和度（%） */
    public static final String PROP_SPO2 = "spo2";

    /** 心率演示值域下界（bpm，静息正常区间） */
    private static final int HEART_RATE_MIN = 60;

    /** 心率演示值域跨度（60~100 bpm） */
    private static final int HEART_RATE_SPAN = 41;

    /** 血氧演示值域下界（%） */
    private static final int SPO2_MIN = 95;

    /** 血氧演示值域跨度（95~100%） */
    private static final int SPO2_SPAN = 6;

    /** 物模型 JSON 键名（IoTDA 标准形态，禁散落魔法值） */
    private static final String KEY_SERVICES = "services";

    /** 物模型 JSON 键名：服务标识 */
    private static final String KEY_SERVICE_ID = "service_id";

    /** 物模型 JSON 键名：属性集 */
    private static final String KEY_PROPERTIES = "properties";

    /** 确定性伪随机源：seed 固定即序列固定（同 seed 回放口径） */
    private final Random random;

    /** JSON 组装器：ObjectNode 保持键插入序，产出即物模型标准形态 */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 以设备标识派生 seed 构造（生产入口）：同一设备跨启动回放同一序列。
     *
     * @param deviceId 平台设备标识，非空；其 hashCode（跨 JVM 规范稳定）作伪随机 seed
     */
    public TelemetryPayloadBuilder(String deviceId) {
        this(deviceId.hashCode());
    }

    /**
     * 以显式 seed 构造（联调/测试入口）：同 seed 序列逐帧可回放。
     *
     * @param seed 伪随机种子；同 seed 必产生逐字符相同帧序列
     */
    public TelemetryPayloadBuilder(long seed) {
        this.random = new Random(seed);
    }

    /**
     * 产出一帧物模型上行 JSON。
     *
     * @return 形如 {"services":[{"service_id":"Monitor","properties":{"heartRate":72,"spo2":98}}]}
     *         的 JSON 串，非空；心率 60~100 bpm、血氧 95~100%
     */
    public String next() {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode services = root.putArray(KEY_SERVICES);
        ObjectNode service = services.addObject();
        service.put(KEY_SERVICE_ID, SERVICE_ID);
        ObjectNode properties = service.putObject(KEY_PROPERTIES);
        properties.put(PROP_HEART_RATE, HEART_RATE_MIN + random.nextInt(HEART_RATE_SPAN));
        properties.put(PROP_SPO2, SPO2_MIN + random.nextInt(SPO2_SPAN));
        return root.toString();
    }
}
