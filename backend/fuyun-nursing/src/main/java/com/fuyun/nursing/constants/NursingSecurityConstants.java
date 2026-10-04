package com.fuyun.nursing.constants;

/**
 * 护理域安全常量镜像（PR-4C Task 4，W-40 方案 A）：承载大屏哨兵操作者标识的 nursing 侧副本。
 *
 * <p><b>同源约定（主控预授权偏差通道，报告申报）</b>：权威定义在
 * {@code com.fuyun.system.constants.SecurityConstants#BIGSCREEN_SENTINEL_OPERATOR_ID}，
 * 但 system 模块仅暴露 api NamedInterface（modulith 边界，api 之外包模块私有），跨模块
 * import constants 包将击穿 ModulithBoundaryTest 门禁；为单一常量扩大模块暴露面违反
 * 「勿为常量扩暴露面」裁定，故在 nursing 侧自建镜像。<b>两侧取值必须逐字同步</b>——
 * system 侧修订时必须同步本镜像（值为大屏哨兵 userId=0 十进制字符串化，改动即哨兵
 * 会话识别契约变更，须双边 PR 同步）；若后续 system 将该常量升入 api 面，本镜像应随即
 * 退役改直引（死代码零容忍）。
 *
 * <p>消费方：{@code WardAccessServiceImpl}（哨兵豁免判定）、Task 6 端点守卫、Task 7
 * WS SUBSCRIBE 防线（哨兵识别锚点）。
 * 线程安全：不可变常量类，无并发风险。
 */
public final class NursingSecurityConstants {

    /** 大屏哨兵操作者注入值（userId=0 十进制字符串化）——与 system 侧 SecurityConstants 同源镜像 */
    public static final String BIGSCREEN_SENTINEL_OPERATOR_ID = "0";

    /** 纯常量类，禁止实例化（backend 宪法 A.2-6） */
    private NursingSecurityConstants() {}
}
