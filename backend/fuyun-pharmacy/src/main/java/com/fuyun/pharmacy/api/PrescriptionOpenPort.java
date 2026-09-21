package com.fuyun.pharmacy.api;

/**
 * 处方开立对接端口（M03 医生站开方动作的进程内唯一入口，api 包契约；PrescriptionFeePort 先例同型
 * ——禁 HTTP 自调用）。语义与 REST {@code POST /api/v1/pharmacy/prescriptions} 逐字同源（复用既有
 * create 主链，预检/落库/事件发布零旁路），REQUIRED 传播加入调用方事务——开方与 M03 RX_REF 引用行
 * 同事务原子（backend 宪法 B.2-4）。纵深防御（裁决 9，Spec :226）：消费方 M03 入口执业授权校验
 * （OP-1017）与 create 内校验（PH-1017）构成两层。
 */
public interface PrescriptionOpenPort {

    /**
     * 开方转调：命令对象镜像映射为开方入参后进既有 create 主链（同事务 REQUIRED 传播），处方生效
     * 即发布 pharmacy.prescription.created（药品计费行权威携带，M-4 裁决）。
     *
     * @param cmd 开方命令（api 面镜像载体，与 pharmacy dto 镜像同构——dto 禁外引），非空
     * @return 开方结果（rxNo/status/reviewLevel/skinTestRequired 四组件，Task 12 IT 依赖冻结面），非空
     * @throws com.fuyun.common.exception.BizException PH-* 既有码语义原样透传：PH-1006（明细/类型
     *                                                  非法）/PH-1007（就诊号非法）/PH-1003（药品
     *                                                  停用）/PH-1015（途径集外）/PH-1017（执业授权
     *                                                  未过，403——处方权/抗菌药分级/麻精权纵深校验）
     */
    PrescriptionOpenResult open(PrescriptionOpenCommand cmd);
}
