package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 医嘱开立明细行入参（OrderCreateRequest.items 元素）：药品行（itemType=DRUG）参与过敏
 * 拦截（itemCode 命中过敏项药物 code 拒 IP-1013）与剂量/单位/途径必填校验（缺任一拒
 * IP-1011）；行序号（item_seq）与延续标志缺省值由服务层统一生成/回填。
 *
 * @param itemType     行项目类型（与 OrderType 同词表：DRUG/LAB/EXAM/SURGERY/BLOOD/NURSING/DIET/CONSULT/DISCHARGE_MED），必填；来源：医生站项目选择
 * @param itemCode     项目编码（药品/检验/检查等项目字典编码），必填；来源：项目字典选择
 * @param itemName     项目名称（名称快照誊写源，落 name_snapshot 并入事件载荷——药品通用名非敏感项），必填；来源：项目字典带出
 * @param dosage       剂量（数值字符串如 0.5），药品行必填（服务层拒 IP-1011）；来源：医生站录入
 * @param dosageUnit   剂量单位（如 g/ml），药品行必填（服务层拒 IP-1011）；来源：医生站录入
 * @param route        给药途径（M01 medication.route 字典 code），药品行必填（服务层拒 IP-1011）；来源：医生站选择
 * @param dripRate     滴速（如 40 滴/分），可空——静滴类医嘱携带；来源：医生站录入
 * @param quantity     数量（正数），必填；来源：医生站录入
 * @param execDeptId   执行科室编码（M01 组织机构 code），可空——LIS/PACS 等执行归口；来源：医生站指定
 * @param skinTestFlag 皮试标记，可空缺省 false——药品行执行前须皮试；来源：医生站勾选
 * @param oralFlag     抢救口头医嘱补录标记，可空缺省 false——Task 6 oral-confirm 补录确认面消费；来源：抢救场景勾选
 * @param continueFlag 延续标志（成组医嘱组内延续执行标记），可空缺省 false；来源：成组开单勾选
 */
public record OrderItemRequest(
        @NotBlank(message = "itemType 不能为空")
        @Pattern(regexp = "DRUG|LAB|EXAM|SURGERY|BLOOD|NURSING|DIET|CONSULT|DISCHARGE_MED", message = "itemType 词表外")
        String itemType,

        @NotBlank(message = "itemCode 不能为空") String itemCode,

        @NotBlank(message = "itemName 不能为空") String itemName,

        String dosage,

        String dosageUnit,

        String route,

        String dripRate,

        @Positive(message = "quantity 须为正数") BigDecimal quantity,

        String execDeptId,

        Boolean skinTestFlag,

        Boolean oralFlag,

        Boolean continueFlag) {}
