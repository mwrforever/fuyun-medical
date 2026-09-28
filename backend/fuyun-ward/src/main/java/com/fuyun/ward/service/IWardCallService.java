package com.fuyun.ward.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.ward.dto.CompleteWardCallRequest;
import com.fuyun.ward.dto.CreateWardCallRequest;
import com.fuyun.ward.dto.WardCallQueryRequest;
import com.fuyun.ward.vo.WardCallRouteVO;
import com.fuyun.ward.vo.WardCallVO;

/**
 * 呼叫状态机服务接口（FU-M16-01）：六态合法迁移唯一裁决面 + 动作式升级读时惰性判定 + 同床位
 * 合并取消 + 路由规则解析。实现归 WardCallServiceImpl（装配归 WardWebConfig）。
 */
public interface IWardCallService {

    /**
     * 手工创建呼叫（同床位活跃旧呼叫自动 CANCELLED 合并语义；系统级 INFUSION 落行走事件消费
     * 链不经本入口）。
     *
     * @param request 创建请求，非空；来源：POST /ward-calls 请求体（@Valid 后置）
     * @return 创建后视图（status=CREATED）
     */
    WardCallVO create(CreateWardCallRequest request);

    /**
     * 应答（CREATED/TRANSFERRED → ANSWERED）。
     *
     * @param callNo 呼叫业务号，非空
     * @return 应答后视图（status=ANSWERED）
     */
    WardCallVO answer(String callNo);

    /**
     * 进入处理中（ANSWERED → IN_PROGRESS，可选中间态）。
     *
     * @param callNo 呼叫业务号，非空
     * @return 处理后视图（status=IN_PROGRESS）
     */
    WardCallVO progress(String callNo);

    /**
     * 完成（ANSWERED/IN_PROGRESS → COMPLETED，result_summary 强制）。
     *
     * @param callNo  呼叫业务号，非空
     * @param request 完成请求（摘要必填），非空
     * @return 完成后视图（status=COMPLETED）
     */
    WardCallVO complete(String callNo, CompleteWardCallRequest request);

    /**
     * 转接（CREATED/ANSWERED → TRANSFERRED，纯状态迁移；规则驱动转接走 route）。
     *
     * @param callNo 呼叫业务号，非空
     * @return 转接后视图（status=TRANSFERRED）
     */
    WardCallVO transfer(String callNo);

    /**
     * 转接触发（规则驱动）：CAS 转接（CREATED/ANSWERED → TRANSFERRED）+ 按病区/类型/当前时段
     * 解析目标链（无规则 WD-1003，事务回滚转接不生效）。
     *
     * @param callNo 呼叫业务号，非空
     * @return 路由解析视图（目标链 + 任务转换开关快照）
     */
    WardCallRouteVO route(String callNo);

    /**
     * 取消（CREATED/TRANSFERRED → CANCELLED）。
     *
     * @param callNo 呼叫业务号，非空
     * @return 取消后视图（status=CANCELLED）
     */
    WardCallVO cancel(String callNo);

    /**
     * 呼叫分页（读时惰性升级判定承载面：当前页超时未升级行 CAS 递增 escalation_count 后回读）。
     *
     * @param request 分页查询请求，非空
     * @return 分页出参（0 基页码）
     */
    PageResult<WardCallVO> page(WardCallQueryRequest request);

    /**
     * 呼叫详情（读时惰性升级判定承载面：超时未升级行 CAS 递增后回读实态）。
     *
     * @param callNo 呼叫业务号，非空
     * @return 呼叫视图
     */
    WardCallVO get(String callNo);
}
