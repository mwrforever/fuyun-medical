package com.fuyun.nursing.constants;

import java.time.ZoneId;

/**
 * 护理域时间口径冻结常量（BUG-03）：医疗日界类计算（出入量/护理记录当日窗、班次统计周期、
 * 体温单月页归属、体征查询窗）统一按北京时区执行，禁止依赖 ZoneId.systemDefault()——容器基底
 * （eclipse-temurin:17-jre）默认 UTC，systemDefault 会使北京时间 00:00-08:00 的记录错归
 * 前一日/前月页（本地开发机 Asia/Shanghai 全绿掩盖缺陷）。部署链已联动注入 TZ=Asia/Shanghai
 * 兜底（Dockerfile/compose/.env.example 三处同源），本常量使业务语义显式化、与运行环境解耦。
 * 线程安全：不可变常量类，无并发风险。
 */
public final class NursingTimeConstants {

    /** 医疗业务时区（北京时区 Asia/Shanghai）：护理日界/班次窗/体温单月页归属的统一口径，改动属业务契约变更 */
    public static final ZoneId HEALTHCARE_TZ = ZoneId.of("Asia/Shanghai");

    private NursingTimeConstants() {}
}
