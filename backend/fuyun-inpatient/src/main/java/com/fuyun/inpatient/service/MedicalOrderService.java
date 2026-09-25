package com.fuyun.inpatient.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.dto.OrderCreateRequest;
import com.fuyun.inpatient.enums.OrderClass;
import com.fuyun.inpatient.vo.MedicalOrderVO;
import com.fuyun.inpatient.vo.OrderDetailVO;

/**
 * 住院医嘱域服务（FU-M04-04/05）——P2 PR-1 医嘱闭环门面。接口先行冻结（Task 4 转科编排
 * 消费面 stopAllForTransfer），实现归 Task 5（MedicalOrderServiceImpl）随 V904 医嘱三表
 * 落地；停嘱面 stop（Task 6 端点消费）与 stopAllForTransfer（转科编排消费）共用实现。
 */
public interface MedicalOrderService {

    /**
     * 医嘱开立（POST /visits/{visitId}/orders）：就诊在院校验 → 开立校验四层（执业授权→
     * 过敏→明细/频次有效性→嘱托限定）→ 发 MO 号主子表同事务落库（CREATED）→ 事务内发布
     * inpatient.order.created（routing key 携带类型子键，drug 子键驱动 M06 审方任务）。
     *
     * @param visitId 住院就诊号（I 型 14 位），非空；来源：路径参数
     * @param req     开立入参（头+项列表），非空；来源：医生站开单
     * @return 开立后医嘱出参（status=CREATED），非空
     * @throws com.fuyun.common.exception.BizException IP-1007（404 就诊不存在）/IP-1008（409
     *                 非在院禁开立）/IP-1012（403 执业授权未过）/IP-1013（409 过敏强阳性）/
     *                 IP-1011（400 药品行剂量/途径缺失或长期缺频次）/IP-1021（404 频次不存在）/
     *                 IP-1022（400 嘱托仅长期可用/操作者标识非数字）/IP-1023（409 医嘱号唯一冲突）
     */
    MedicalOrderVO create(String visitId, OrderCreateRequest req);

    /**
     * 医嘱分页查询（GET /orders?visitId=&class=&page=&size=）：按就诊号过滤（必填——
     * 住院医嘱视图以就诊为轴），可叠加医嘱分类过滤；按开立时间倒序（最新医嘱在前）。
     *
     * @param visitId 住院就诊号（I 型 14 位，必填过滤键），非空；来源：查询参数
     * @param clazz   医嘱分类过滤（LONG/STAT；可空=全部分类），可空；来源：查询参数
     * @param page    页码（0 基），非负
     * @param size    单页条数，正
     * @return 医嘱分页出参，非空
     * @throws com.fuyun.common.exception.BizException IP-1007（404 就诊不存在——查询键校验）
     */
    PageResult<MedicalOrderVO> list(String visitId, OrderClass clazz, int page, int size);

    /**
     * 医嘱详情（GET /orders/{no}）：医嘱头 + 明细行全集（item_seq 升序），闭环追溯取数入口。
     *
     * @param orderNo 医嘱号，非空；来源：路径参数
     * @return 医嘱详情出参（含项），非空
     * @throws com.fuyun.common.exception.BizException IP-1007（404 关联就诊不存在——出参
     *                 visitId 号映射面）/IP-1009（404 医嘱不存在）
     */
    OrderDetailVO detail(String orderNo);

    /**
     * 医嘱停嘱（POST /orders/{no}/stop，Task 6 端点消费）：与 stopAllForTransfer 共用实现
     * ——STOPPED 状态机迁移（合法态 AUDITED/TRANSFERRED/EXECUTING，非法 IP-1010）+ 停嘱
     * 时点（服务器时间）与原因落值 + 未来执行计划批量作废（数据面归 Task 7 回接）+ 事务内
     * 发布 inpatient.order.stopped。
     *
     * @param orderNo 医嘱号，非空；来源：路径参数
     * @param reason  停嘱原因（医生停嘱理由），非空；来源：停嘱端点入参
     * @throws com.fuyun.common.exception.BizException IP-1009（404 医嘱不存在）/IP-1010（409
     *                 停嘱状态机违例——CREATED/AUDIT_REJECTED/终态不可停）
     */
    void stop(String orderNo, String reason);

    /**
     * 转科自动停嘱（转科编排阶段①，调用方 TransferService.transfer 编排事务内）：转出病区
     * 该就诊全部长期医嘱（order_class=long、非终态）置 STOPPED（stop_reason=转科，停嘱时间=
     * 服务器时间），联动发布 inpatient.order.stopped（M13 按转科时间线截断转出侧持续性费用、
     * M05 撤销未执行执行单）；待执行长期计划随停嘱作废归计划服务（Task 7/8 转科钩子）。
     * 实现须满足编排事务语义：停嘱失败抛异常整体回滚转科事务。
     *
     * @param visitId 住院就诊主键（inpatient_visit.id，非 I 型号），非空；来源：编排起始就诊行
     * @param reason  停嘱原因（转科路径固定「转科」；出院清理复用本接口时另传），非空；来源：编排方
     * @throws com.fuyun.common.exception.BizException IP-1010（409）停嘱链状态机违例（医嘱行
     *                 并发迁移至不可停态的窗口——快照后 CAS 失配整体回滚编排）
     */
    void stopAllForTransfer(Long visitId, String reason);
}
