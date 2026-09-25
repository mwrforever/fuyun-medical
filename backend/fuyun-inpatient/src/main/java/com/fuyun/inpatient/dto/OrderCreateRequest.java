package com.fuyun.inpatient.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * 医嘱开立入参（POST /api/v1/inpatient/visits/{visitId}/orders）：头+项列表两层结构；
 * 开立校验四层（执业授权→过敏→明细/频次有效性→嘱托限定）归服务层（Web 层仅承载词表/
 * 必填结构校验兜底）。开立医生取操作者上下文（OperatorContextHolder，不随请求传——医生站
 * 登录主体即开立主体）；item_seq 由服务层按列表序 1 起递增生成（禁前端错序入参）。
 *
 * @param orderType   医嘱类型（OrderType 九值词表：DRUG/LAB/EXAM/SURGERY/BLOOD/NURSING/DIET/CONSULT/DISCHARGE_MED），必填；来源：医生站开单选择
 * @param orderClass  医嘱分类（LONG 长期/STAT 临时；LONG 须携 freqCode），必填；来源：医生站开单选择
 * @param standbyFlag 备用嘱（嘱托）标记，可空缺省 false——仅 LONG 可 true（STAT+standby 服务层拒 IP-1022）；来源：长期按需执行场景勾选
 * @param groupNo     成组医嘱组号，可空——缺省由服务层回填本医嘱 order_no（单条医嘱自成一组的缺省形态）；来源：成组开单携既有组号
 * @param freqCode    频次编码（order_frequency 七值种子 qd/bid/tid/qid/qn/prn/st），LONG 必填服务层拒 IP-1011、无命中拒 IP-1021；来源：医生站频次字典选择
 * @param items       医嘱明细行列表（至少一行），必填；来源：医生站项目录入
 */
public record OrderCreateRequest(
        @NotBlank(message = "orderType 不能为空")
        @Pattern(regexp = "DRUG|LAB|EXAM|SURGERY|BLOOD|NURSING|DIET|CONSULT|DISCHARGE_MED", message = "orderType 词表外")
        String orderType,

        @NotBlank(message = "orderClass 不能为空") @Pattern(regexp = "LONG|STAT", message = "orderClass 词表外")
        String orderClass,

        Boolean standbyFlag,

        String groupNo,

        @Pattern(regexp = "^[a-zA-Z0-9]{1,16}$", message = "freqCode 须为频次编码（字母/数字 ≤16）")
        String freqCode,

        @NotEmpty(message = "items 不能为空") @Valid List<OrderItemRequest> items) {}
