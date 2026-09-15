package com.fuyun.integration.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.EventPublicationQuery;
import com.fuyun.integration.vo.EventPublicationVO;

/**
 * 投递台账查询服务（事件溯源·投递侧）：Modulith 事件发布注册表的只读查询面。
 *
 * <p>用途：运维排查「事件已暂存但未完成（监听器失败/实例宕机）」与投递历史；未完成记录的重投与
 * 已完成记录清理归 EventOpsJob（fuyun-app internal，挂 ShedLock），本接口零写语义。
 */
public interface IEventPublicationQueryService {

    /**
     * 分页查询投递记录（按事件类型/完成态/发布时间窗过滤，发布时刻倒序）。
     *
     * @param query 查询条件，非空；page 0 基、size 1-200；status 仅接受 COMPLETED/INCOMPLETE
     * @return 分页出参（0 基页码），非空
     */
    PageResult<EventPublicationVO> query(EventPublicationQuery query);
}
