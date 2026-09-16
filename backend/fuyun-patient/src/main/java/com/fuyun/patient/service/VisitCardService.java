package com.fuyun.patient.service;

import com.fuyun.common.exception.BizException;
import com.fuyun.patient.dto.CardBindRequest;
import com.fuyun.patient.dto.CardIssueRequest;
import com.fuyun.patient.dto.CardReplaceRequest;
import com.fuyun.patient.vo.CardVO;

/**
 * 就诊卡全生命周期服务（FU-M02-04）：发卡/绑定/挂失/补卡/解绑状态机
 * （介质本体 = patient_identifier 的 VISIT_CARD 标识行，卡号即标识值）
 * 与一卡通账户联动（发卡开户/挂失冻结），状态变更经 identifier.changed 事件广播。
 */
public interface VisitCardService {

    /**
     * 发卡并绑定档案（新建 VISIT_CARD 标识 ACTIVE；一卡通启用时联动开户）。
     *
     * @param request 发卡请求，非空
     * @return 卡出参，非空
     * @throws BizException PAT-1012（409）卡号已登记（attach 撞唯一约束的 PAT-1002 兜底转义）
     */
    CardVO issue(CardIssueRequest request);

    /**
     * 绑定既有无主卡到档案（按卡号查任意状态行改挂；仅未挂接的无主卡可绑定，有主卡一律拒绝——
     * 防 LOST/DISABLED 卡经 bind 复活绕过挂失状态机，LOST 找回路径见 TASK.md D-14）。
     *
     * @param request 绑定请求，非空
     * @return 卡出参，非空
     * @throws BizException PAT-1011（404）卡号无命中 / PAT-1012（409）卡已挂接档案（含同档）
     */
    CardVO bind(CardBindRequest request);

    /**
     * 挂失（ACTIVE→LOST，解析立即失效；一卡通账户联动冻结；identifier.changed LOST）。
     *
     * @param cardNo 卡面号，非空
     * @throws BizException PAT-1011（404）卡号无命中 / PAT-1012（409）非 ACTIVE 卡不可挂失
     */
    void loss(String cardNo);

    /**
     * 补卡（旧卡 LOST→REPLACED 终态；新卡发号绑定同档案；identifier.changed REPLACED；
     * 账户挂患者不动，余额零迁移动作）。
     *
     * @param request 补卡请求（旧卡号+新卡号），非空
     * @return 新卡出参，非空
     * @throws BizException PAT-1011（404）旧卡无命中 / PAT-1012（409）旧卡非 LOST /
     *                      PAT-1002（409）新卡号已存在（唯一约束兜底原样透传）
     */
    CardVO replace(CardReplaceRequest request);

    /**
     * 解绑（ACTIVE→DISABLED 终态，解析失效；账户不销户；identifier.changed UNBOUND）。
     *
     * @param cardNo 卡面号，非空
     * @throws BizException PAT-1011（404）卡号无命中 / PAT-1012（409）非 ACTIVE 卡不可解绑
     */
    void unbind(String cardNo);

    /**
     * 按卡号取卡出参。
     *
     * @param cardNo 卡面号，非空
     * @return 卡出参，非空
     * @throws BizException PAT-1011（404）卡号无命中
     */
    CardVO getByCardNo(String cardNo);
}
