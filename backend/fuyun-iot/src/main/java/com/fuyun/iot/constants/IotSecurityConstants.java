package com.fuyun.iot.constants;

/**
 * IoT 域安全常量镜像（PR-4D Task 7，W-90 WS 面——镜像 nursing 侧 NursingSecurityConstants 先例）：
 * 承载大屏哨兵识别锚点（登录名）的 iot 侧副本。
 *
 * <p><b>同源约定（主控预授权偏差通道）</b>：权威定义在
 * {@code com.fuyun.system.constants.SecurityConstants#BIGSCREEN_LOGIN_NAME}，但 system 模块仅暴露
 * api NamedInterface（modulith 边界，api 之外包模块私有），跨模块 import constants 包将击穿
 * ModulithBoundaryTest 门禁；为单一常量扩大模块暴露面违反「勿为常量扩暴露面」裁定，故在 iot
 * 侧自建镜像（nursing 侧 NursingSecurityConstants 同款精神）。<b>两侧取值必须逐字同步</b>——
 * system 侧修订时必须同步本镜像与 nursing 侧镜像（哨兵识别契约变更，须多边 PR 同步）；若后续
 * system 将该常量升入 api 面，本镜像应随即退役改直引（死代码零容忍）。
 *
 * <p>消费方：{@code IotSubscribeInterceptor}（/ws/iot SUBSCRIBE 哨兵限订判定锚点——令牌主体
 * loginName 比对）。哨兵订阅白名单四主题常量不在本类——推送出口既有
 * {@link IotMessagingConstants#TOPIC_TELEMETRY_PREFIX} 等词表即白名单本体（与推送侧逐字同源防
 * 漂移，直接复用不另立副本）。
 * 线程安全：不可变常量类，无并发风险。
 */
public final class IotSecurityConstants {

    /**
     * 大屏哨兵登录名（=={@code "bigscreen"}）——与 system 侧
     * {@code com.fuyun.system.constants.SecurityConstants#BIGSCREEN_LOGIN_NAME} 同源镜像：
     * /ws/iot SUBSCRIBE 限订防线的哨兵判定锚点（令牌主体 loginName 比对）；两侧取值必须逐字
     * 同步，修订须多边 PR 同步（同源约定见类 javadoc）。
     */
    public static final String BIGSCREEN_LOGIN_NAME = "bigscreen";

    /** 纯常量类，禁止实例化（backend 宪法 A.2-6） */
    private IotSecurityConstants() {}
}
