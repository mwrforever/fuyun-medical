package com.fuyun.pharmacy.vo;

import com.fuyun.pharmacy.entity.Dispense;
import com.fuyun.pharmacy.entity.DispenseItem;
import java.util.List;

/**
 * 调剂单出参（from(entity, items) 手写映射，PrescriptionVO 同型——关键业务字段禁 MapStruct）。
 * 组件清单为 Task 7/11/12 依赖的冻结面：双签留痕 picker/verifier/issuer 直出、时间类字段不出网
 * （issuedAt 等审计时刻不出工作台回显面）。
 */
public record DispenseVO(
        Long id,
        String dispenseNo,
        String dispenseType,
        String rxNo,
        Long patientId,
        String visitId,
        String storehouse,
        String picker,
        String verifier,
        String issuer,
        String status,
        List<DispenseItemVO> items) {

    /**
     * 实体→出参静态工厂（items 随行装载，明细数量 toPlainString、追溯码 JSON 还原码集）。
     *
     * @param entity 调剂单行，非空
     * @param items  调剂明细实体清单（调用方按调剂单 id 装载），非空
     * @return 出参，非空
     */
    public static DispenseVO from(Dispense entity, List<DispenseItem> items) {
        return new DispenseVO(
                entity.getId(),
                entity.getDispenseNo(),
                entity.getDispenseType(),
                entity.getRxNo(),
                entity.getPatientId(),
                entity.getVisitId(),
                entity.getStorehouse(),
                entity.getPicker(),
                entity.getVerifier(),
                entity.getIssuer(),
                entity.getStatus(),
                items.stream().map(DispenseItemVO::from).toList());
    }
}
