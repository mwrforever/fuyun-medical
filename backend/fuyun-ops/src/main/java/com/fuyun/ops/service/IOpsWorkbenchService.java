package com.fuyun.ops.service;

import com.fuyun.ops.vo.WorkbenchEventsVO;
import com.fuyun.ops.vo.WorkbenchOverviewVO;

/**
 * 运营工作台聚合服务（批次 2 册 2 首切片）：两聚合端点的业务编排面——四业务模块统计 Port
 * 只读聚合 + Redis 快照缓存（TTL 5s read-through）+ 事件流五源装配。纯分析只读域（M19
 * 红线 1：对任何业务模块零写、不参与业务事务），方法一律只读语义。
 */
public interface IOpsWorkbenchService {

    /**
     * 工作台总览聚合（指标带六格 + 14 日趋势 + 候诊表）：缓存命中直返（读失败/损坏降级直算），
     * 穿透直算四 Port 并回写缓存（TTL 5s）。
     *
     * @return 总览快照，非空；无业务数据返回零值/空清单视图（不造数）
     */
    WorkbenchOverviewVO overview();

    /**
     * 工作台事件流装配（三 STOMP 主题指引 + 两 HTTP 轮询源派生待办 + 危急值缺位降级段）。
     *
     * @return 事件流快照，非空；无待办返回空清单、危急值段恒空数组并携降级标志
     */
    WorkbenchEventsVO events();
}
