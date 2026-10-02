package com.fuyun.common.constants;

import java.time.ZoneId;

/**
 * 全仓公共时间口径常量（时区纪律专项）：医疗业务时区统一按北京钟面执行，禁止依赖
 * ZoneId.systemDefault()——容器基底（eclipse-temurin:17-jre）默认 UTC，systemDefault
 * 会使北京时间 00:00-08:00 的业务日期错归前一日（本地开发机 Asia/Shanghai 全绿掩盖缺陷）。
 * 部署链已注入 TZ=Asia/Shanghai 兜底（Dockerfile/compose/.env.example 三处同源），
 * 本常量使业务语义显式化、与运行环境解耦。
 * 线程安全：不可变常量类，无并发风险。
 */
public final class TimeConstants {

    /** 医疗业务时区（北京时区 Asia/Shanghai）：全仓业务日期/医疗日界/号段与 TTL 锚的统一口径，改动属业务契约变更 */
    public static final ZoneId HEALTHCARE_TZ = ZoneId.of("Asia/Shanghai");

    private TimeConstants() {}
}
