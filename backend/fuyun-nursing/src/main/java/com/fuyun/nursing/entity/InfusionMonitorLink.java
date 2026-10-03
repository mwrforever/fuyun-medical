package com.fuyun.nursing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 输液监测挂接实体（nursing.infusion_monitor_link，V1106）：执行单↔袋签↔IoT 设备三元关联，
 * M14 告警联动与拔针收口的唯一挂接面。一执行单一挂接（uk_monitor_link_execution）；Task 5
 * 摆药签收衔接（PIVAS 升格）建行——started_at 承载挂接建立时点，开始输注（Task 6）经
 * 「建/激活」二态复用本行（iot_device_id 回填与 infusion.started 事件随 Task 6 实装）。
 */
@Getter
@Setter
@TableName("nursing.infusion_monitor_link")
public class InfusionMonitorLink {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 执行单号（一执行单一挂接；输液类执行单专属；uk_monitor_link_execution 唯一） */
    private String executionNo;

    /** 输液袋标签码（摆药贴签溯源码或护士站补录；M14 告警关联维度之一） */
    private String bagLabelCode;

    /** IoT 设备标识（PDA 扫码回填；输液监控类设备，未绑定为 NULL——Task 6 开始输注面回填） */
    private String iotDeviceId;

    /** 最新告警号（escalation 挂单锚；随 M14 告警事件刷新——Task 6 消费面） */
    private String latestAlarmNo;

    /** 挂接状态（MONITORING 监测中 / ENDED 输注结束（拔针）/ RELEASED 已解除（异常释放）） */
    private String linkStatus;

    /** 开始监测时点（Task 5=挂接建立时点；Task 6 开始输注激活面同源沿用） */
    private OffsetDateTime startedAt;

    /** 结束监测时点（拔针/解除动作联动；未结束为 NULL——Task 6 拔针面落值） */
    private OffsetDateTime endedAt;

    /** 创建时刻（DB now() 默认） */
    private OffsetDateTime createdAt;

    /** 更新时刻（DB now() 默认 + 触发器维护） */
    private OffsetDateTime updatedAt;

    /** 创建者（消费链路 SYSTEM 桥接口径） */
    private String createdBy;

    /** 更新者（消费链路 SYSTEM 桥接口径） */
    private String updatedBy;

    /** 逻辑删标记 */
    @TableLogic
    private Integer deleted;
}
