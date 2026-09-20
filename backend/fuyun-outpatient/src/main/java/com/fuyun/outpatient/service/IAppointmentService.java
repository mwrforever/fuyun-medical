package com.fuyun.outpatient.service;

import com.fuyun.outpatient.api.AppointmentTimeoutPayload;
import com.fuyun.outpatient.dto.AppointmentCreateRequest;
import com.fuyun.outpatient.vo.AppointmentVO;
import com.fuyun.outpatient.vo.VisitVO;

/**
 * 预约/当日挂号服务（M03 FU-M03-02/03 写路径唯一入口）：统一预约主流程七步（患者归一冻结拦截→
 * 爽约限约→限购→池行复核→Redis 预扣→appointment 落库+池行 CAS→渠道分流占位/直达 TAKEN）、预约取号
 * 与支付超时释放（延迟队列消费业务面）。聚合型服务不继承 IService（A.4.3-20）。
 */
public interface IAppointmentService {

    /**
     * 统一预约/当日挂号。
     *
     * @param request 预约请求（patientId/poolId/channel），非空；契约校验由 @Valid 承载
     * @return 预约单出参（窗口/自助直达 TAKEN 携 visit_id；portal 为 RESERVED+payDeadline），非空
     * @throws com.fuyun.common.exception.BizException OP-1002（404 号源池不存在）/ OP-1003（409 号源不足）/
     *                                                 OP-1004（409 停诊或排班状态违例）/ OP-1005（409 同日同科限购）/
     *                                                 OP-1006（409 爽约限约期内）/ OP-1007（409 患者冻结拦截）/
     *                                                 OP-1019（400 渠道词表外或 P1 未开放）时触发；
     *                                                 建议处理策略：按 errorCode 提示用户
     */
    AppointmentVO book(AppointmentCreateRequest request);

    /**
     * 预约取号（RESERVED→TAKEN，同事务签发 visit 并删支付占位键）。
     *
     * @param apptNo 预约单业务号，非空；来源：窗口/自助扫码或输入
     * @return 就诊记录出参（REGISTERED），非空；TAKEN 态重复取号幂等返回既有 visit
     * @throws com.fuyun.common.exception.BizException OP-1008（409 支付时限已过）/
     *                                                 OP-1009（409 预约单不存在或状态不允许取号）时触发；
     *                                                 建议处理策略：超时单引导窗口人工处置
     */
    VisitVO take(String apptNo);

    /**
     * 支付超时释放（延迟队列消费业务面，消费幂等三段式之外的业务态幂等守卫）：RESERVED→NO_SHOW CAS
     * 影响 1 行才执行释放面（version 条件回池+Redis 回补+删占位键+爽约信用记录）；0 行=已取号/已取消/
     * 已释放，幂等跳过禁二次释放。
     *
     * @param payload 超时回调载荷（apptNo/patientId/poolId），非空；来源：超时监听器解析
     * @throws IllegalStateException 预约单按 appt_no 定位失败时触发（数据异常，交容器拒收进死信留痕）
     */
    void markTimeout(AppointmentTimeoutPayload payload);
}
