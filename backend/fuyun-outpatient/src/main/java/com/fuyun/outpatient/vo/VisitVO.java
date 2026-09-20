package com.fuyun.outpatient.vo;

import com.fuyun.outpatient.enums.VisitStatus;
import com.fuyun.outpatient.enums.VisitType;
import java.time.OffsetDateTime;

/**
 * 就诊记录出参（POST /appointments/{no}/take 直出形态）：visit_id 等雪花外业务号 string 承载
 * （A.3-6）；分诊/接诊/诊毕扩展字段随 Task 7/8 消费。
 *
 * @param id           就诊记录主键；来源：落库回填
 * @param visitId      就诊号（O+yyyyMMdd+5 位流水，CF-3 冻结）
 * @param patientId    患者主索引
 * @param apptId       关联预约单 id
 * @param deptCode     开诊科室编码
 * @param doctorId     接诊医生 id（按排班回填）
 * @param visitType    就诊类型 code
 * @param isRevisit    是否复诊（0/1）
 * @param triageLevel  急诊分级（Ⅰ~Ⅳ=1~4；挂号链路为 null，分诊台写入）
 * @param status       就诊状态 code（挂号初始 REGISTERED）
 * @param registeredAt 挂号/取号时间
 */
public record VisitVO(
        Long id,
        String visitId,
        Long patientId,
        Long apptId,
        String deptCode,
        String doctorId,
        VisitType visitType,
        Short isRevisit,
        Integer triageLevel,
        VisitStatus status,
        OffsetDateTime registeredAt) {}
