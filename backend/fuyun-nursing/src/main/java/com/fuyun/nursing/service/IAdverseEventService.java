package com.fuyun.nursing.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.nursing.dto.AdverseEventCloseRequest;
import com.fuyun.nursing.dto.AdverseEventHandleRequest;
import com.fuyun.nursing.dto.AdverseEventReportRequest;
import com.fuyun.nursing.dto.AdverseEventReturnRequest;
import com.fuyun.nursing.vo.AdverseEventStatsVO;
import com.fuyun.nursing.vo.AdverseEventVO;
import java.time.LocalDate;

/**
 * 护理不良事件域服务（P2 PR-3 Task 10，M05 FU-M05-09）：上报（匿名通道+I/II 级 24 小时
 * 时限合规+id 83 事件发布）、处置/关闭/退回状态机三路（REPORTED→HANDLING→CLOSED 主链
 * +HANDLING→REPORTED 侧支）、分页查询（非惩罚红线：出参零惩罚字段）、分类统计聚合
 * （M19 消费缺位注记）与 tick 超时提醒扫描段（TaskOverdueTickListener 挂接——只读
 * 不改状态）。
 *
 * <p>非惩罚文化红线：匿名上报通道（reporter_id NULL）+查询/统计出参不含个人惩罚字段
 * （仅流程改进面）；超时只留痕不拒绝。
 *
 * <p>线程安全：无状态 singleton；写操作 @Transactional 收口（实现侧）。
 */
public interface IAdverseEventService {

    /**
     * 上报不良事件：词表校验（八类/四级/五等）→ 发号（AE+yyyyMMdd+5 位）→ I/II 级
     * report_deadline=occurredAt+24h 落库并上报即判定 deadline_met=（now≤deadline）→
     * 落库后事务内发布 nursing.adverse-event.reported（id 83 五字段——匿名行载荷不含
     * reporter，载荷契约本无该字段天然合规）。匿名归一：isAnonymous=true 或 reporterId
     * 未携带 → reporter_id 落 NULL + is_anonymous=true 配对。
     *
     * @param req 上报入参（category/severityClass/severityGrade/wardId/occurredAt/
     *            eventSummary 必填），非空
     * @return 上报后出参（非惩罚面——无上报人字段），非空
     * @throws com.fuyun.common.exception.BizException NS-1019（400 词表外值）/ NS-1016
     *                 （409 occurredAt 晚于当前时间+容差——禁未来时刻倒灌）
     */
    AdverseEventVO report(AdverseEventReportRequest req);

    /**
     * 分页查询（处置工作清单）：类别/病区/状态/发生日（occurred_at 当日窗口）可选过滤，
     * 发生时点降序（最新事件优先）。date 缺省不限时段（在途处置队列跨日存续——与工作台
     * 「缺省当日」口径不同，处置清单须可见往日未结事件）。
     *
     * @param category 事件类别过滤（AdverseEventCategory code，可空），可空；来源：查询参数
     * @param wardId   病区过滤（可空），可空；来源：查询参数
     * @param status   处置状态过滤（AdverseEventStatus code，可空），可空；来源：查询参数
     * @param date     发生日期过滤（北京钟面当日窗口，可空=不限时段），可空；来源：查询参数
     * @param page     页码（0 基），非负
     * @param size     单页条数（1-200），正数
     * @return 不良事件分页出参（非惩罚面），非空
     * @throws com.fuyun.common.exception.BizException NS-1019（400 category/status 词表外值）
     */
    PageResult<AdverseEventVO> list(String category, String wardId, String status, LocalDate date, int page, int size);

    /**
     * 受理处置（REPORTED→HANDLING）：落处置责任人与处置记录（可空保留上报时记录）；
     * I/II 级 REPORTED 态已超 24h 处置时 deadline_met=false 留痕（不阻断——非惩罚原则）。
     *
     * @param no  不良事件号（路径参数 {no}），非空
     * @param req 处置入参（handlerId 必填/handlingNote 可空），非空
     * @return 处置后出参，非空
     * @throws com.fuyun.common.exception.BizException NS-1025（404 事件不存在）/ NS-1026
     *                 （409 非 REPORTED 态——已处置/已关闭）
     */
    AdverseEventVO handle(String no, AdverseEventHandleRequest req);

    /**
     * 关闭（HANDLING→CLOSED）：RCA 根因分析与整改措施随关闭落库（可空保留行原值）；
     * closedBy 经 updated_by 审计列承载（V1107 无独立 closed_by 列）。
     *
     * @param no  不良事件号，非空
     * @param req 关闭入参（closedBy 必填/rcaNote/correctiveAction 可空），非空
     * @return 关闭后出参，非空
     * @throws com.fuyun.common.exception.BizException NS-1025（404）/ NS-1026（409 非
     *                 HANDLING 态——未处置不可关闭/已关闭）
     */
    AdverseEventVO close(String no, AdverseEventCloseRequest req);

    /**
     * 处置退回（HANDLING→REPORTED，独立 return 端点承载）：退回原因覆写处置记录
     * （必填留痕）；returnerId 经 updated_by 审计列承载。
     *
     * @param no  不良事件号，非空
     * @param req 退回入参（reason/returnerId 必填），非空
     * @return 退回后出参（回 REPORTED 态可再处置），非空
     * @throws com.fuyun.common.exception.BizException NS-1025（404）/ NS-1026（409 非
     *                 HANDLING 态——未处置无退回面/已关闭）
     */
    AdverseEventVO returnEvent(String no, AdverseEventReturnRequest req);

    /**
     * 分类统计（统计日窗口按类别/病区/等级双维度/班次时段聚合计数 + I/II 级时限合规面）：
     * date 缺省北京钟面当日；类别/病区前置过滤。词表维度零填充（稳定契约形态）。
     *
     * @param category 类别前置过滤（可空），可空；来源：查询参数
     * @param wardId   病区前置过滤（可空），可空；来源：查询参数
     * @param date     统计日（北京钟面，可空=当日），可空；来源：查询参数
     * @return 统计聚合出参（纯计数零个人面），非空
     * @throws com.fuyun.common.exception.BizException NS-1019（400 category 词表外值）
     */
    AdverseEventStatsVO stats(String category, String wardId, LocalDate date);

    /**
     * tick 超时提醒扫描段（TaskOverdueTickListener 挂接，只读不改状态）：扫描 REPORTED
     * 且 report_deadline&lt;now 的 I/II 级行→WS 提醒推送（Task 11 已接线：按病区聚合发布
     * ADVERSE_EVENT_REMIND 帧，无事务上下文经 fallback 立即出站）。非惩罚原则：超时仅
     * 提醒留痕，不迁移状态不拦截处置。
     *
     * @return 本轮超时行数（零=空扫描零副作用；有界扫描 500 行上限，越界行后续 tick 轮转）
     */
    int scanAndRemindOverdue();
}
