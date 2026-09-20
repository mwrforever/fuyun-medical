package com.fuyun.outpatient.service;

import com.fuyun.outpatient.dto.CheckInRequest;
import com.fuyun.outpatient.dto.QueueCallRequest;
import com.fuyun.outpatient.dto.TriageAdjustRequest;
import com.fuyun.outpatient.vo.QueueTicketVO;
import java.util.List;

/**
 * 分诊台与候诊队列服务（M03 FU-M03-04 分诊台管理/候诊叫号，Task 7 写路径唯一入口）：
 * 报到入队（visit REGISTERED→WAITING 迁移+建票+ZSET 入队）、二次分诊/调级/跨队列转接（全留痕）、
 * 叫号（惰性重建+原子出队+CAS+双 topic WS 推送）、过号降级重排（票号不变 Spec :106）与重呼、
 * 队列 REST 快照（脱敏出网双通道之一）。优先级分冻结公式（类别分取最高单项+老幼残跨类叠加+
 * 封顶 999，偏差⑨）；Redis ZSET 仅为加速视图，queue_ticket WAITING 行为权威（Spec :210 重启恢复）。
 *
 * <p>错误码契约：OP-1001 VISIT_NOT_FOUND/OP-1011 VISIT_STATE_NOT_ALLOWED/OP-1012 TICKET_NOT_FOUND/
 * OP-1013 TICKET_STATE_NOT_ALLOWED/OP-1019 PARAM_FORMAT_INVALID（ProblemDetail properties.errorCode）。
 * 事务边界：写方法 @Transactional（报到/调级/转接与 visit 迁移、triage_record 留痕同事务原子）。
 */
public interface ITriageService {

    /**
     * 分诊报到（CHECK_IN）：visit CAS REGISTERED→WAITING（红线 5 每迁必记 visit_status_log）+
     * checked_in_at 回填（国标报到时间）+ queue_ticket 建票（A+当日序票号、冻结公式优先级分）+
     * ZSET 入队 + triage_record 留痕。预约已 TAKEN 直接可报到（TAKEN 即 visit 已签发 REGISTERED，
     * 天然满足前置态，零额外配置面）。重复报到/终态 OP-1011 拒绝。
     *
     * @param request 报到请求（visitId/stationId/老幼残因子），非空
     * @return 候诊票据出参（含脱敏展示名），非空
     * @throws com.fuyun.common.exception.BizException OP-1001（404 就诊不存在）/OP-1011（409 状态
     *                                                  不允许报到）/OP-1019（400 因子词表外）时触发
     */
    QueueTicketVO checkIn(CheckInRequest request);

    /**
     * 二次分诊/调级/跨队列转接（RE_TRIAGE/LEVEL_ADJUST/QUEUE_TRANSFER，CHECK_IN 走专用端点）：
     * 全部动作 triage_record 留痕。调级重算分值但票号不变（过号降级重排不改号 Spec :106）；转队列
     * 旧队 ZSET remove+旧票 CANCELLED+新队建票重算分。因子重算为全量口径（未携带按无老幼残因子）。
     *
     * @param request 分诊调整请求，非空
     * @return 调整后候诊票据出参（转队列为新票），非空
     * @throws com.fuyun.common.exception.BizException OP-1001（404）/OP-1012（404 在队票不存在）/
     *                                                  OP-1019（400 动作词表外/分级越界/因子词表外/
     *                                                  转队列缺目标）时触发
     * @throws IllegalStateException 转队列放旧票 CAS 并发落败（数据异常，交事务回滚留痕）
     */
    QueueTicketVO adjust(TriageAdjustRequest request);

    /**
     * 叫号：前置惰性重建（当日待重叫权威行 WAITING+PASSED→ZSET，键在位零写幂等；PASSED 过号
     * 再入票重启后不跌出队列——fix round 1 Important-2 裁决①）→ZSET 原子出队（首个未指派或
     * 指派一致票）→按票行当前态 CAS→CALLED（WAITING 首叫/PASSED 队内重叫共用，called_count+1
     * +call_time）→双 topic WS 推送。叫号≠接诊：visit 保持 WAITING（接诊由 /visits/{visitId}/admit
     * 承载）。空队/无可叫票返回 null（200 空语义）。
     *
     * @param request 叫号请求（deptCode/doctorId），非空
     * @return 叫中票据出参；队列空返回 null
     * @throws com.fuyun.common.exception.BizException OP-1012（404 出队票行缺失）/OP-1013（409 并发
     *                                                  CAS 落败）时触发
     */
    QueueTicketVO call(QueueCallRequest request);

    /**
     * 过号（CALLED→PASSED）：ZSET 以降级分（priority_score-100，下限 0）重排保持 WAITING 语义
     * 再入（票号与优先级分列不变——过号降级重排不改号 Spec :106）。
     *
     * @param ticketId 票据主键，非空
     * @return 过号票据出参（PASSED），非空
     * @throws com.fuyun.common.exception.BizException OP-1012（404 票不存在）/OP-1013（409 非 CALLED
     *                                                  或并发 CAS 落败）时触发
     */
    QueueTicketVO pass(long ticketId);

    /**
     * 重呼（PASSED→CALLED 重复叫）：called_count 累加并重复双 topic 推送（大屏/医生站再次播报）。
     *
     * @param ticketId 票据主键，非空
     * @return 重呼票据出参（CALLED），非空
     * @throws com.fuyun.common.exception.BizException OP-1012（404 票不存在）/OP-1013（409 非 PASSED
     *                                                  或并发 CAS 落败）时触发
     */
    QueueTicketVO recall(long ticketId);

    /**
     * 队列 REST 快照（实时通道之外的拉取通道，双通道之一 Spec :153）：按优先级分降序、同分按
     * queue_time 建行时间升序（库端权威，禁应用服务器时钟——偏差⑨）。脱敏出网：patientName 为
     * patient 侧掩码收口展示名，无证件号字段。
     *
     * @param queueId 队列标识（=dept_code），非空
     * @param status  状态过滤词表值（TicketStatus code），可空（空=全状态）
     * @return 票据出参列表（优先级降序）；空队列返回空列表
     * @throws com.fuyun.common.exception.BizException OP-1019（400 状态词表外）时触发
     */
    List<QueueTicketVO> snapshot(String queueId, String status);
}
