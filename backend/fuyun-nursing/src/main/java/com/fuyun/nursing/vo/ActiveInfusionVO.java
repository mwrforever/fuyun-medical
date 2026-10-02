package com.fuyun.nursing.vo;

import com.fuyun.nursing.entity.InfusionMonitorLink;
import com.fuyun.nursing.entity.OrderExecution;
import java.time.OffsetDateTime;

/**
 * 在途输注出参（GET /api/v1/nursing/infusions/active?wardId=）：病区在途输液监测清单——
 * EXECUTING 输液执行单 × MONITORING 挂接行聚合（无挂接行因建链 fail-closed 不存在于在途面）。
 * 大屏/工作台输液看板与告警挂单定位的只读面。
 *
 * @param executionNo   执行单业务号
 * @param patientId     患者主索引
 * @param visitId       住院就诊号
 * @param wardId        病区编码
 * @param bedNo         床位号（冗余展示，可空）
 * @param execItemName  执行项目名称（摆药回填后为真实项目名）
 * @param startedAt     开始输注时点（挂接激活时点）
 * @param bagLabelCode  输液袋标签码（PIVAS 摆药贴签溯源）
 * @param iotDeviceId   挂接 IoT 设备标识（PDA 扫码回填，可空——未绑定为 null）
 * @param latestAlarmNo 最新关联告警号（escalation 挂单锚，可空）
 * @param escalationCount 升级次数（告警升级累计，closed 复位保留追溯）
 */
public record ActiveInfusionVO(
        String executionNo,
        Long patientId,
        String visitId,
        String wardId,
        String bedNo,
        String execItemName,
        OffsetDateTime startedAt,
        String bagLabelCode,
        String iotDeviceId,
        String latestAlarmNo,
        Integer escalationCount) {

    /**
     * 执行单行 × 挂接行聚合构造（服务侧装配专用）。
     *
     * @param execution 在途输液执行单行（status=EXECUTING、type=INFUSION），非空
     * @param link      监测挂接行（link_status=MONITORING），非空
     * @return 在途输注出参，非空
     */
    public static ActiveInfusionVO of(OrderExecution execution, InfusionMonitorLink link) {
        return new ActiveInfusionVO(
                execution.getExecutionNo(),
                execution.getPatientId(),
                execution.getVisitId(),
                execution.getWardId(),
                execution.getBedNo(),
                execution.getExecItemName(),
                link.getStartedAt() != null ? link.getStartedAt() : execution.getStartedAt(),
                link.getBagLabelCode(),
                link.getIotDeviceId(),
                link.getLatestAlarmNo(),
                execution.getEscalationCount());
    }
}
