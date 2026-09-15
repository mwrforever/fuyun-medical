package com.fuyun.integration.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.DeadLetterCloseRequest;
import com.fuyun.integration.dto.DeadLetterQuery;
import com.fuyun.integration.entity.DeadLetter;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;

/**
 * 死信管理服务：dead_letter 台账的查询与处置入口（M20 §5 死信处理流程）。
 *
 * <p>状态机（Spec §5 原文）：PENDING → REPLAYED（重放投递成功）；重放失败回到 PENDING 并累加
 * 重放次数；PENDING → CLOSED（关闭，必填原因）；CLOSED 为终态不得再重放。重放/关闭全留痕
 * （handler/handle_note/handled_at/replay_count）。
 */
public interface IDeadLetterService extends IService<DeadLetter> {

    /**
     * 分页查询死信台账（按状态/类型/事件/来源队列过滤，按首次死信时间倒序）。
     *
     * @param query 查询条件，非空；page 0 基、size 1-200
     * @return 分页出参（0 基页码），非空；无匹配时 content 为空清单
     */
    PageResult<DeadLetterVO> query(DeadLetterQuery query);

    /**
     * 读取死信详情（含载荷全文，运维诊断用）。
     *
     * @param id 死信 ID，非空
     * @return 详情出参，非空
     * @throws com.fuyun.common.exception.BizException 死信不存在（INT-1001，404）时触发；
     *                                                  建议处理策略：前端提示记录不存在并刷新列表
     */
    DeadLetterDetailVO detail(Long id);

    /**
     * 重放死信：原帧原文重投 fy.topic（保留原 eventId，靠消费侧幂等防重复），成功置 REPLAYED 并累加
     * 重放次数；投递失败回到 PENDING 并累加次数后抛业务异常（INT-1005）。
     *
     * <p>拒绝条件（不触达投递）：id 不存在（INT-1001）；非 PENDING 状态（INT-1002）；
     * 重放次数已达 {@link com.fuyun.integration.constants.MessagingConstants#DEAD_LETTER_REPLAY_MAX_COUNT}
     * （INT-1003）；无可用路由键或来源队列不在位（INT-1004）。
     *
     * @param id 死信 ID，非空
     * @return 重放后的死信详情，非空；status=REPLAYED、replayCount 已递增、handler/handledAt 已留痕
     * @throws com.fuyun.common.exception.BizException 上述四种拒绝场景与投递失败场景；
     *                                                  建议处理策略：按 errorCode 分支提示运维（状态冲突刷新重试、超限转人工关闭）
     */
    DeadLetterDetailVO replay(Long id);

    /**
     * 关闭死信：置终态 CLOSED 并留痕处理人/备注/时间（PENDING → CLOSED，Spec §5）。
     *
     * @param id      死信 ID，非空
     * @param request 关闭请求，非空；handleNote 必填（关闭原因）
     * @return 关闭后的死信详情，非空；status=CLOSED
     * @throws com.fuyun.common.exception.BizException id 不存在（INT-1001）或非 PENDING 状态（INT-1002，
     *                                                  含并发处置抢先）时触发
     */
    DeadLetterDetailVO close(Long id, DeadLetterCloseRequest request);
}
