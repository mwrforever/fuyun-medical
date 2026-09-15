package com.fuyun.integration.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.ReceivedEventQuery;
import com.fuyun.integration.vo.ReceivedEventVO;

/**
 * 消费台账查询服务（M20 FU-M20-06 事件查询台）：received_event 的只读查询面。
 *
 * <p>聚合/报表型接口不继承 IService（backend 宪法 A.4.3-20），实现注入 mapper 承担分页查询；
 * 写路径归 {@code MessageIdempotencyServiceImpl}（两层幂等构件），本接口零写语义。
 */
public interface IReceivedEventQueryService {

    /**
     * 分页查询消费台账（按事件类型/事件 ID/消费者/状态/接收时间窗过滤，接收时间倒序）。
     *
     * @param query 查询条件，非空；page 0 基、size 1-200
     * @return 分页出参（0 基页码），非空
     */
    PageResult<ReceivedEventVO> query(ReceivedEventQuery query);
}
