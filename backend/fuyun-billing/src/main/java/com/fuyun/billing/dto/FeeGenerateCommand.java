package com.fuyun.billing.dto;

import com.fuyun.billing.enums.ChargeSource;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.enums.VisitType;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 费用生成命令（计价引擎统一入参，事件消费与手工计费复用）。
 *
 * @param patientId  患者主索引，非空
 * @param visitId    CF-3 定长就诊号（服务层 VisitIdValidator 校验），非空
 * @param source     计费来源（ORDER_LINKED/EXEC_LINKED/DAY_CUTOVER/MANUAL/PEIS），非空
 * @param sourceRef  来源单据引用（医嘱/执行单/申请单号；手工=操作者工号），非空——红线 3 可追溯
 * @param trigger    计费点（TriggerType），非空——与 sourceRef+项目+计费日构成 billing_key 防重四要素
 * @param itemCode     收费项目编码，非空
 * @param quantity     数量（>0，服务费量可小数），非空
 * @param visitType    就诊类型 OUT/IN/PEIS，非空
 * @param operator     手工计费操作者工号（source=MANUAL 必填，其余可空，红线 3）
 * @param manualReason 手工计费理由（source=MANUAL 必填，其余可空）
 */
public record FeeGenerateCommand(
        @NotNull Long patientId,
        @NotNull String visitId,
        @NotNull ChargeSource source,
        @NotNull String sourceRef,
        @NotNull TriggerType trigger,
        @NotNull String itemCode,
        @NotNull BigDecimal quantity,
        @NotNull VisitType visitType,
        String operator,
        String manualReason) {}
