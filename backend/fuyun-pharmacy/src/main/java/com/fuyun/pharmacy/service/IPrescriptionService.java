package com.fuyun.pharmacy.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.dto.PrescriptionCreateRequest;
import com.fuyun.pharmacy.entity.Prescription;
import com.fuyun.pharmacy.vo.PrescriptionVO;

/**
 * 处方服务（处方主数据唯一权威源，模块红线 1）：开方（同步返回处方号+预检分级）、作废、查询。
 * 配对纪律（宪法 A.4.3-20）：本服务以 prescription 为主表（实现侧已 extends
 * ServiceImpl&lt;PrescriptionMapper, Prescription&gt;），接口侧对应 extends
 * IService&lt;Prescription&gt;——主表通用能力（分页/批量/链式查询等默认方法集）复用 IService
 * 契约面，属契约面扩展；自有方法与 IService 默认方法无同名同参冲突（list 六参为 IService
 * list()/list(Wrapper) 系列的重载、参数列表不同非覆写，与 IDrugService.update(long, ...) 同款）。
 * 开方主链带执业授权纵深校验与状态机同事务编排——IService 通用写面（save/updateById/
 * removeById 等）不承载状态语义与授权语义，PH-1xxx 状态迁移与执业授权校验一律走自有方法：
 * create（CREATED→APPROVED 同事务开方主链：practice/check 执业授权纵深校验——处方权+抗菌药
 * 分级+麻精类 PH-1017 落库前拦截、prescription_item 明细快照同事务落库）；cancel（未缴费 CAS
 * 作废+无条件联动 billing PrescriptionFeePort 作废 PENDING 费用行堵 TOCTOU 资金窗口，已缴费
 * PH-1014 拒并引导退药/退费链）。缴费后处方态迁移（PENDING_FEE/PENDING_DISPENSE/DISPENSED/
 * 退药终态）归 IDispenseService 消费编排，禁经通用写面直写绕开 CAS 与授权守卫。
 */
public interface IPrescriptionService extends IService<Prescription> {

    /**
     * 开方（同步 API：预检恒通过级，CREATED→APPROVED 同事务，Spec :59/:132）。
     *
     * @param req 开方入参，非空
     * @return 处方出参（rxNo 已签发、items 含计费行快照与用法摘要）
     * @throws BizException PH-1006（明细/类型非法）/ PH-1007（就诊号非法）/
     *                      PH-1003（药品停用）/ PH-1015（途径集外）
     */
    PrescriptionVO create(PrescriptionCreateRequest req);

    /**
     * 处方作废（未缴费：联动 billing PENDING 费用作废；已缴费拒并引导退药/退费链）。
     *
     * @param rxNo   处方号；来源：调用方（M03 医生站作废入口/workstation）
     * @param reason 作废原因，必填
     * @throws BizException PH-1004（缺单）/ PH-1005（状态机违例）/
     *                      PH-1014（已缴费——引导收费窗口退费或药房退药受理）
     */
    void cancel(String rxNo, String reason);

    /**
     * 处方分页查询（visitId/patientId/rxNo/status 任意组合；id 升序唯一序）。
     *
     * @param visitId   就诊号，可空
     * @param patientId 患者 id，可空
     * @param rxNo      处方号，可空
     * @param status    状态 code 过滤（药房队列= PENDING_DISPENSE），可空
     * @param page      0 基页码
     * @param size      页大小
     * @return 分页出参
     */
    PageResult<PrescriptionVO> list(String visitId, Long patientId, String rxNo, String status, int page, int size);
}
