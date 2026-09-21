package com.fuyun.outpatient.service;

import com.fuyun.outpatient.dto.OrderCreateRequest;
import com.fuyun.outpatient.dto.PrescriptionOpenRequest;
import com.fuyun.outpatient.vo.ClinicOrderVO;
import java.util.List;

/**
 * 门诊医生站开单服务（M03 FU-M03-05，Task 8 写路径唯一入口）：开单五步（visit 终态守卫→执业授权
 * 强校验→order_no 签发→CREATED 落库+明细行→事务内发布 order.created AFTER_COMMIT 出 MQ）、开方
 * 衔接（Task 9：openPrescription 经 pharmacy 开方端口同步开方+本域登记 RX_REF 引用行）、作废
 * （CREATED/PENDING_FEE→CANCELLED+PENDING 费用行经 billing 端口逐行作废；CHARGED 拒绝引导退费
 * 链；RX_REF 行引导性 409 经 M06 作废链发起）、按就诊号查询、缴费回执推进（CREATED→PENDING_FEE
 * +visit 待缴费推进）。资金无涉红线（裁决 7）：本服务零金额逻辑，资金动作一律进 M13 权威面。
 *
 * <p>错误码契约：OP-1001/OP-1011/OP-1014 ORDER_NOT_FOUND/OP-1015 ORDER_STATE_NOT_ALLOWED/
 * OP-1017 PRACTICE_CHECK_FAILED/OP-1019 PARAM_FORMAT_INVALID。事务边界：写方法 @Transactional
 * （主单+明细+状态迁移同事务原子；事件发布走事务内 publishEvent→AFTER_COMMIT，A.4.2-7）。
 * 资金动作一律经 {@link com.fuyun.billing.api.OutpatientBillingPort} 进 M13 权威面；开方动作
 * 经 {@link com.fuyun.pharmacy.api.PrescriptionOpenPort} 进 M06 权威面（两端口均进程内同事务）。
 */
public interface IClinicOrderService {

    /**
     * 医生站开单（五步）：①visit 定位（OP-1001）与终态守卫（FINISHED/CANCELLED→OP-1011，红线 5）；
     * ②orderType/quantity 词表与格式显式校验（OP-1019）+开单执业授权强校验（医师开检查/检验/治疗/
     * 处置单统一校验 PRESCRIPTION 处方权，未过 OP-1017 403）；③order_no 签发（OP+yyyyMMdd+6 位
     * 流水，Redis 当日键 INCR TTL 48h）；④CREATED 主单+明细行落库（quantity DECIMAL string 红线）；
     * ⑤发布 outpatient.order.created（id 23，orderId=order_no，lines 逐行 itemCode/quantity）。
     *
     * @param visitId 就诊号（O 型 14 位），非空；来源：医生站工作台
     * @param request 开单请求（orderType 五类词表/items 计费行），非空
     * @return 申请单出参（status=CREATED，含明细行），非空
     * @throws com.fuyun.common.exception.BizException OP-1001（404）/OP-1011（409 就诊终态）/OP-1017
     *                                                  （403 执业授权未过）/OP-1019（400 词表外或
     *                                                  quantity 非数字串/操作者非数字）时触发
     */
    ClinicOrderVO create(String visitId, OrderCreateRequest request);

    /**
     * 开立处方（M03↔M06 衔接动作，Task 9）：①visit 定位（OP-1001）与终态守卫（FINISHED/CANCELLED
     * →OP-1011，红线 5）；②开方执业授权强校验（PRESCRIPTION 处方权，未过 OP-1017 403——与 M06
     * create 内校验构成纵深两层，Spec :226）；③pharmacy 开方端口同步调用（进程内同事务，PH-* 码
     * 透传）；④clinic_order 建 RX_REF 引用行（ext_ref=rxNo、status=CREATED——不复制药品明细
     * （红线 3）且零计费行（M-4 零双头：药品计费行由 pharmacy.prescription.created 权威携带））；
     * ⑤返回引用行出参（extRef 承载 rxNo，items 空）。
     *
     * @param visitId 就诊号（O 型 14 位），非空；来源：医生站工作台
     * @param request 开方请求（处方类型/明细内容面；patientId/deptCode 由 visit 服务端解析），非空
     * @return 引用行出参（orderType=RX_REF、extRef=rxNo、status=CREATED、零明细行），非空
     * @throws com.fuyun.common.exception.BizException OP-1001（404）/OP-1011（409 就诊终态）/
     *                                                  OP-1017（403 执业授权未过）/OP-1019（400
     *                                                  操作者标识非数字）及 pharmacy PH-* 透传
     *                                                  （PH-1006/1007/1003/1015/1017）时触发
     */
    ClinicOrderVO openPrescription(String visitId, PrescriptionOpenRequest request);

    /**
     * 申请单作废：CREATED/PENDING_FEE→CANCELLED（状态机 CAS）+费用作废（billingPort.feesByVisit
     * 定位 sourceRef=orderNo 的 PENDING 行逐行 cancelPendingFee）；CHARGED 态拒绝并引导退费链
     * （OP-1015，refund.approved 逆向随 Task 10）；RX_REF 行引导性 409（作废必须经 M06 作废 API
     * 发起——Spec :119 R2-10，pharmacy.prescription.cancelled 回流驱动本行 CANCELLED 随 Task 10）。
     * 计费端口调用异常一律转译为可读业务错误码（禁裸抛底层异常，W-20 关联面）。
     *
     * @param orderNo 申请单业务号，非空；来源：医生站单据列表
     * @param reason  作废理由（审计留痕锚点），非空白；来源：医生站作废弹窗
     * @return 作废后申请单出参（status=CANCELLED，含明细行），非空
     * @throws com.fuyun.common.exception.BizException OP-1014（404 单据不存在）/OP-1015（409 状态
     *                                                  不允许作废——CHARGED 引导退费链、RX_REF
     *                                                  引导 M06 作废链、并发 CAS 落败）时触发
     */
    ClinicOrderVO cancel(String orderNo, String reason);

    /**
     * 按就诊号查询申请单清单（医生站单据面）：id 降序，明细行批量装配（禁 N+1）。
     *
     * @param visitId 就诊号，非空；来源：医生站工作台
     * @return 申请单出参清单（含明细行）；无单据返回空列表
     */
    List<ClinicOrderVO> listByVisit(String visitId);

    /**
     * 缴费回执推进（billing.fee.created 消费业务体，监听器委托入口）：单据 CAS CREATED→
     * PENDING_FEE（0 行重读定性：已 PENDING_FEE 幂等跳过/其余态 warn 跳过）+visit 推进（IN_CONSULT
     * →PENDING_FEE 状态机迁移+每迁必记；非 IN_CONSULT 态跳过——多单并推与诊毕竞态为正常态）。
     *
     * @param orderNo 申请单业务号（billingKey sourceRef 段解析所得），非空；来源：缴费回执帧
     * @throws IllegalStateException 按 orderNo 定位申请单失败（单据缺失——数据异常，死信留痕人工
     *                                对账）时触发
     */
    void markPendingFee(String orderNo);
}
