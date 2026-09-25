package com.fuyun.inpatient.api.payload;

import java.time.LocalDate;
import java.util.List;

/**
 * 长期医嘱计划拆分事件载荷（inpatient.order-plan.generated，V800 id 43 冻结契约）：日切
 * 分解任务与当日增量补偿生成计划行后发布（按医嘱逐条）；M05 据此批量生成次日/当日执行单。
 * planNos 与 planTimes 按下标一一对应（同医嘱同批生成面）。脱敏红线：仅定位键与时点序列，
 * 禁患者姓名/诊断文本。
 *
 * @param m04OrderNo 医嘱号，非空；来源：分解/补偿目标长期医嘱业务号
 * @param visitId    住院就诊号（I 型 14 位），非空；来源：医嘱关联就诊
 * @param patientId  患者主索引，非空
 * @param planDate   计划日期（ISO yyyy-MM-dd；日切=次日、补偿=当日），非空
 * @param planNos    本批生成计划号集（PL+yyyyMMdd+5 位流水），非空；与 planTimes 下标对齐
 * @param planTimes  本批计划时点集（HH:mm 二十四小时制，与 planDate 组合定位执行时点），非空；与 planNos 下标对齐
 */
public record OrderPlanGeneratedPayload(
        String m04OrderNo,
        String visitId,
        long patientId,
        LocalDate planDate,
        List<String> planNos,
        List<String> planTimes) {}
