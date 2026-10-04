package com.fuyun.nursing.service;

import java.util.List;

/**
 * 病区归属校验服务（PR-4C Task 4，W-40 方案 A）：大屏/护士站数据面的病区级访问防线基座——
 * 以「操作者当班 ACTIVE 绑定集」为唯一授权依据，集外一律 fail-closed 拒绝（D-29：无绑定行
 * 一律 403，ADMIN 亦无豁免）。消费方：Task 6 大屏/病区端点守卫（REST 路径，经
 * {@link #assertWardAllowed(String)} 取 ThreadLocal 身份）与 Task 7 WS SUBSCRIBE 防线
 * （broker 线程无 ThreadLocal，经 {@link #assertWardAllowedFor(String, String)} 显式传参）。
 * 聚合型服务不继承 IService（backend 宪法 A.4.3-20）。
 * 线程安全：实现为无状态 singleton，可并发调用。
 */
public interface IWardAccessService {

    /**
     * 查操作者当班 ACTIVE 病区绑定集：nurse_assignment 表中 nurse_id=操作者、status=ACTIVE 且
     * 当日有效窗口命中（valid_from<=北京钟面医疗日<=valid_to，valid_to 为 NULL 视为长期有效）的
     * 行，取 ward_id 去重返回。当日窗口一律按北京时区计算（时区红线 GC4/GC3），禁止依赖容器
     * 或 DB 会话时区。
     *
     * @param operatorId 操作者标识（护士 userId 十进制字符串），非空；来源：REST 路径取
     *                   OperatorContextHolder，WS 路径取令牌/会话解析的显式传参
     * @return 当班绑定病区编码清单（去重，无有效绑定返回空清单，非 null）
     */
    List<String> activeBoundWardIds(String operatorId);

    /**
     * REST 守卫入口：取 OperatorContextHolder 当前操作者身份，校验目标病区在当班绑定集内；
     * 大屏哨兵操作者豁免直通（HTTP 层已限行+wardId 一致性校验，此处为防御纵深，非二次授权）。
     * 供 controller 端点挂守卫消费。
     *
     * @param wardId 目标病区编码，非空；来源：路径/查询参数
     * @throws com.fuyun.common.exception.BizException NS-1028（403）：操作者上下文缺失（无/空白
     *             ThreadLocal 身份）、无 ACTIVE 绑定行（fail-closed，ADMIN 无豁免）或目标病区
     *             不在绑定集内（越区访问）
     */
    void assertWardAllowed(String wardId);

    /**
     * 显式传参校验版（语义与 {@link #assertWardAllowed(String)} 完全一致）：供 WS broker 线程
     * 等无 ThreadLocal 上下文的消费方直接传操作者身份（Task 7 SUBSCRIBE 防线）。
     * 哨兵豁免与 fail-closed 语义同 ThreadLocal 版。
     *
     * @param operatorId 操作者标识（userId 十进制字符串），可空——空/空白即身份缺失按
     *                   fail-closed 拒绝；来源：WS 握手/令牌解析的显式传参
     * @param wardId     目标病区编码，非空；来源：SUBSCRIBE 目的地解析
     * @throws com.fuyun.common.exception.BizException NS-1028（403）：触发条件同
     *             {@link #assertWardAllowed(String)}（身份缺失/无绑定行/越区）
     */
    void assertWardAllowedFor(String operatorId, String wardId);
}
