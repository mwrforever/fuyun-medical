package com.fuyun.outpatient.service;

import com.fuyun.outpatient.dto.FinishVisitRequest;
import com.fuyun.outpatient.vo.DoctorQueueItemVO;
import com.fuyun.outpatient.vo.VisitVO;
import java.util.List;

/**
 * 门诊医生站就诊服务（M03 FU-M03-05 接诊/诊毕/候诊列表，Task 8 写路径唯一入口）：接诊（票
 * CALLED→SERVING 联动+visit WAITING→IN_CONSULT 状态机迁移）、诊毕（离院去向词表+在途单据显式
 * 确认校验+visit→FINISHED+visit.finished 发布）、医生站候诊列表（本队列 WAITING/CALLED 票+
 * 脱敏摘要）。visit 全部迁移经 {@link OutpatientVisitStateMachine} 单点校验+visit_status_log
 * 每迁必记（红线 5）；FINISHED/CANCELLED 后拒绝一切后续动作（OP-1011）。
 *
 * <p>错误码契约：OP-1001 VISIT_NOT_FOUND/OP-1011 VISIT_STATE_NOT_ALLOWED/OP-1013
 * TICKET_STATE_NOT_ALLOWED/OP-1016 FINISH_CHECK_FAILED/OP-1018 DISPOSITION_INVALID
 * （ProblemDetail properties.errorCode）。事务边界：写方法 @Transactional（visit 迁移、票据联动、
 * 迁移留痕同事务原子）。
 */
public interface IVisitService {

    /**
     * 接诊（叫号≠接诊——visit 保持 WAITING 至本动作）：定位本就诊 CALLED 票并 CAS→SERVING+
     * serve_time 回填（国标接诊时间，分诊域联动归 ITriageService.markServing）→visit 状态机校验
     * WAITING→IN_CONSULT→CAS+admitted_at 回填+visit_status_log 每迁必记。
     *
     * @param visitId 就诊号（O 型 14 位），非空；来源：医生站工作台
     * @return 接诊后就诊出参（status=IN_CONSULT），非空
     * @throws com.fuyun.common.exception.BizException OP-1001（404 就诊不存在）/OP-1011（409 状态
     *                                                  机违例或并发 CAS 落败）/OP-1013（409 无已
     *                                                  叫号票据或票据并发迁移）时触发
     */
    VisitVO admit(String visitId);

    /**
     * 诊毕：离院去向词表校验（OP-1018）→诊毕前置校验（clinic_order 存在 CREATED/PENDING_FEE
     * 在途单据且未显式确认 → OP-1016；M09 文书校验参数化提醒不拦截——not-in-scope 注记）→visit
     * 状态机校验（IN_CONSULT→FINISHED 合法；PENDING_FEE→FINISHED 显式确认路径亦合法）→CAS+
     * finished_at/finish_operator/disposition 回填+每迁必记→发布 outpatient.visit.finished（id 33）。
     *
     * @param visitId 就诊号，非空；来源：医生站工作台
     * @param request 诊毕请求（disposition 离院去向/explicitConfirm 在途单据显式确认），非空
     * @return 诊毕后就诊出参（status=FINISHED），非空
     * @throws com.fuyun.common.exception.BizException OP-1001（404）/OP-1011（409 状态机违例或并发
     *                                                  CAS 落败）/OP-1016（409 在途单据未收敛且未
     *                                                  显式确认）/OP-1018（400 去向词表外）时触发
     */
    VisitVO finish(String visitId, FinishVisitRequest request);

    /**
     * 医生站候诊列表：本队列（deptCode）WAITING/CALLED 票（未指派或指派本医生），优先级分降序+
     * queue_time 升序（同分库端权威，禁应用服务器时钟）；患者姓名经 patient 侧掩码收口（原文不出
     * 模块）；过敏标识位 P1 恒 false（M02 订阅缓存 P-later 注记）。
     *
     * @param deptCode 队列标识（=dept_code），非空；来源：医生站当前诊区
     * @param doctorId 医生 id，非空；来源：登录医生（指派过滤维度）
     * @return 候诊列表行（优先级降序）；空队列返回空列表
     */
    List<DoctorQueueItemVO> patientQueue(String deptCode, String doctorId);
}
