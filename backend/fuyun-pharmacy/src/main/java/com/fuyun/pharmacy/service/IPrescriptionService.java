package com.fuyun.pharmacy.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.dto.PrescriptionCreateRequest;
import com.fuyun.pharmacy.vo.PrescriptionVO;

/**
 * 处方服务（处方主数据唯一权威源，模块红线 1）：开方（同步返回处方号+预检分级）、作废、查询。
 */
public interface IPrescriptionService {

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
