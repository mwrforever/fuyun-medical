package com.fuyun.integration.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.web.PageResult;
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
}
