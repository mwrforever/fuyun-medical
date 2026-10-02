package com.fuyun.nursing.service;

import com.fuyun.nursing.entity.OrderExecution;
import com.fuyun.nursing.vo.ActiveInfusionVO;
import java.util.List;

/**
 * 输液闭环域服务（V1106 order_execution × infusion_monitor_link，Task 6 / FU-M05-06 方案 3.4）：
 * 开始输注挂接（建/激活监测链 + infusion.started 事件）、在途输注清单、IoT 告警升级挂单
 * （不新建任务——escalation_count 累计/挂单锚刷新/挂接任务优先级上调）与告警关闭复位。
 *
 * <p>拔针全链（needle-out）归 {@link IOrderExecutionOperateService}（finish 族终态操作，
 * 复用双路回签编排）——本接口承载 start 分支扩展点与告警消费体。patientId 直配裁决：
 * 告警↔执行单关联零新表（nursing 侧按告警载荷 patientId 匹配在途行；escalated/closed 载荷
 * 无 patientId，按 latest_alarm_no 反查——triggered 路已落挂接锚）。
 *
 * <p>线程安全：无状态 singleton；写方法 @Transactional 收口（实现侧）。
 */
public interface IInfusionService {

    /**
     * 开始输注挂接（Task 5 start 端点的 INFUSION 分支扩展点）：激活 MONITORING 挂接行
     * （started_at 刷新为真实开始输注时点、iot_device_id 按 PDA 扫码回填——缺席保留既有值）
     * + 事务内发布 nursing.infusion.started（id 62 载荷五字段，deviceId 不进事件契约）。
     * 建链唯一正规入口=摆药签收 PIVAS 升格（bag_label_code NOT NULL 承载）——挂接缺行/
     * 非 MONITORING 属链路断裂，NS-1024 fail-closed（宁拒不静默）。
     *
     * @param execution 开始输注后的执行单行（CAS 已迁移 EXECUTING、startedAt 已置值），非空
     * @param deviceId  输液泵设备标识（PDA 扫码，可空——空则挂接保留既有 iot_device_id），可空；
     *                  来源：StartRequest.deviceId
     */
    void startInfusion(OrderExecution execution, String deviceId);

    /**
     * 病区在途输注清单（GET /infusions/active）：EXECUTING 输液执行单 × MONITORING 挂接行
     * 聚合（开始输注时点升序），供大屏/工作台输液看板与告警挂单定位。
     *
     * @param wardId 病区编码（必填过滤键），非空；来源：查询参数
     * @return 在途输注出参清单（无在途返回空清单，非 null）
     * @throws com.fuyun.common.exception.BizException NS-1019（400 wardId 缺失）
     */
    List<ActiveInfusionVO> listActive(String wardId);

    /**
     * 告警触发升级挂单（IotAlarmExecutionListener triggered 路消费体）：按 patientId 直配
     * 在途输液执行单（EXECUTING+INFUSION 且挂接 MONITORING）做升级动作不新建任务——
     * escalation_count+1 CAS + latest_alarm_no 刷新（执行单行与挂接行双落）+ 挂接任务
     * （source_ref=告警号）priority 上调。无在途/无挂接零副作用。
     *
     * @param patientId 患者主索引（直配定位键），非空；来源：iot.alarm.triggered 载荷
     * @param alarmNo   告警业务号（挂单锚刷新值），非空；来源：iot.alarm.triggered 载荷
     * @return 升级挂单命中行数（0=无在途输注——零副作用）
     */
    int escalateOnAlarmTriggered(long patientId, String alarmNo);

    /**
     * 告警升级动作累计（IotAlarmExecutionListener escalated 路消费体）：escalated 载荷无
     * patientId——按 latest_alarm_no=告警号反查 triggered 路已挂接的在途行，同款升级动作
     * （escalation_count 再累计、挂接任务优先级上调）。无挂接零副作用。
     *
     * @param alarmNo 告警业务号（latest_alarm_no 反查定位键），非空；来源：iot.alarm.escalated 载荷
     * @return 升级累计命中行数（0=该告警无挂接在途行——零副作用）
     */
    int escalateOnAlarmEscalated(String alarmNo);

    /**
     * 告警关闭复位（IotAlarmExecutionListener closed 路消费体）：按 latest_alarm_no=告警号
     * 复位挂接锚（执行单行与挂接行双复位 NULL；escalation_count 保留追溯）。重复关闭幂等。
     *
     * @param alarmNo 告警业务号（复位定位键），非空；来源：iot.alarm.closed 载荷
     * @return 复位命中行数（0=该告警无挂接行/已复位——幂等达成）
     */
    int resetAlarmClosed(String alarmNo);
}
