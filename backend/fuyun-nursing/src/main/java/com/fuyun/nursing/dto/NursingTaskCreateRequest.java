package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/**
 * 护理任务创建入参（POST /api/v1/nursing/tasks，手工开立；Task 8 评估高危联动经服务面直调
 * 同参形态）。taskNo 服务端发号（NursingSeqGate nextNo("TK")，禁客户端传入）；planTime 为
 * 计划执行时刻（逾期判定基准），早于当前 24 小时以上拒 NS-1016（禁补录历史任务）。
 *
 * @param patientId     患者主索引，必填；来源：操作者工作站当前患者
 * @param visitId       住院就诊号（I 型 14 位），必填；来源：操作者工作站当前患者
 * @param wardId        病区编码，必填；来源：操作者当前登录病区
 * @param bedNo         床位号（冗余展示），可空；来源：患者当前床位
 * @param taskType      任务类型 code（TaskType 九值词表），必填，非法值 NS-1019；来源：任务表单选择
 * @param source        任务来源 code（TaskSource 词表），可空，空缺省 MANUAL；来源：表单默认值
 * @param sourceRef     来源引用（执行单号/评估单号等），可空；来源：上游单据
 * @param planTime      计划时间，必填（逾期判定基准；早于当前 24 小时以上拒 NS-1016）；来源：操作者指定
 * @param assignedNurse 责任护士，可空（空=未指派，由任务列表按责任组展示）；来源：表单选择
 * @param priority      优先级 code（HIGH/NORMAL/LOW），可空，空缺省 NORMAL，非法值 NS-1019；来源：表单选择
 */
public record NursingTaskCreateRequest(
        @NotNull(message = "patientId 不能为空") Long patientId,
        @NotBlank(message = "visitId 不能为空") String visitId,
        @NotBlank(message = "wardId 不能为空") String wardId,
        String bedNo,
        @NotBlank(message = "taskType 不能为空") String taskType,
        String source,
        String sourceRef,
        @NotNull(message = "planTime 不能为空") OffsetDateTime planTime,
        String assignedNurse,
        String priority) {}
