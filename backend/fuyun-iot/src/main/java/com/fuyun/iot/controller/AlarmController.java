package com.fuyun.iot.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.AlarmQueryRequest;
import com.fuyun.iot.dto.CloseAlarmRequest;
import com.fuyun.iot.service.IAlarmService;
import com.fuyun.iot.vo.AlarmVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警端点（/api/v1/iot/alarms 三端点，FU-M14-08 告警生命周期面）：分页（GET，病区/级别/状态
 * 过滤）、确认（POST /{alarmNo}/acknowledge）、关闭（POST /{alarmNo}/close，原因必填）。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 调用告警服务 + 编排响应；状态机 CAS 与关闭
 * 事件发布归服务层；确认/关闭挂 WRITE 审计。读时惰性升级挂 AlarmEngine.evaluate 调用点（非本
 * 端点职责）。装配归 fuyun-app IotConfig @Import（宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/iot/alarms")
public class AlarmController {

    /** 告警服务：三端点唯一业务出口 */
    private final IAlarmService alarmService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param alarmService 告警服务，非空
     */
    public AlarmController(IAlarmService alarmService) {
        this.alarmService = alarmService;
    }

    /**
     * 告警分页（GET /api/v1/iot/alarms；病区/级别/状态过滤，page 0 基）。
     *
     * @param request 分页查询请求（查询参数绑定，@Valid），非空
     * @return 分页出参；200
     */
    @GetMapping
    public PageResult<AlarmVO> page(@Valid AlarmQueryRequest request) {
        return alarmService.page(request);
    }

    /**
     * 告警确认（POST /api/v1/iot/alarms/{alarmNo}/acknowledge；WRITE 审计）。
     *
     * @param alarmNo 告警业务号（路径变量）
     * @return 确认后的告警视图；200
     * @throws com.fuyun.common.exception.BizException IOT-1012（404 借承）或 IOT-1013（409 借承）
     */
    @PostMapping("/{alarmNo}/acknowledge")
    @AuditLog(actionType = AuditActionType.WRITE)
    public AlarmVO acknowledge(@PathVariable String alarmNo) {
        return alarmService.acknowledge(alarmNo);
    }

    /**
     * 告警关闭（POST /api/v1/iot/alarms/{alarmNo}/close；WRITE 审计；原因必填）。
     *
     * @param alarmNo 告警业务号（路径变量）
     * @param request 关闭请求体（@Valid），非空
     * @return 关闭后的告警视图；200
     * @throws com.fuyun.common.exception.BizException IOT-1012（404 借承）或 IOT-1013（409 借承）
     */
    @PostMapping("/{alarmNo}/close")
    @AuditLog(actionType = AuditActionType.WRITE)
    public AlarmVO close(@PathVariable String alarmNo, @Valid @RequestBody CloseAlarmRequest request) {
        return alarmService.close(alarmNo, request);
    }
}
