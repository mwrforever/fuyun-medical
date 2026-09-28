package com.fuyun.iot.controller;

import com.fuyun.iot.dto.SaveAlarmRuleRequest;
import com.fuyun.iot.dto.SimulateAlarmRequest;
import com.fuyun.iot.service.IAlarmRuleService;
import com.fuyun.iot.vo.AlarmRuleVO;
import com.fuyun.iot.vo.SimulateResultVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警规则端点（/api/v1/iot/alarm-rules 五端点，FU-M14-08 规则管理面）：清单（GET）、登记
 * （POST，201）、更新（PUT）、软删（DELETE，204）、历史回放模拟（POST /{id}/simulate，不落库）。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 调用规则服务 + 编排响应；规则型参数条件必填
 * （抖动防护②）归服务层校验；登记/更新/软删挂 WRITE 审计（数据库写操作留痕口径与产品域写端点
 * 同构），simulate 为纯评估无库写不挂审计。装配归 fuyun-app IotConfig @Import（宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/iot/alarm-rules")
public class AlarmRuleController {

    /** 告警规则服务：五端点唯一业务出口 */
    private final IAlarmRuleService alarmRuleService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param alarmRuleService 告警规则服务，非空
     */
    public AlarmRuleController(IAlarmRuleService alarmRuleService) {
        this.alarmRuleService = alarmRuleService;
    }

    /**
     * 规则清单（GET /api/v1/iot/alarm-rules；纯读，id 升序稳定输出）。
     *
     * @return 规则视图清单；200（空表为空数组）
     */
    @GetMapping
    public List<AlarmRuleVO> list() {
        return alarmRuleService.list();
    }

    /**
     * 规则登记（POST /api/v1/iot/alarm-rules；WRITE 审计）。
     *
     * @param request 登记请求体（@Valid），非空
     * @return 落库后的规则视图；201
     * @throws com.fuyun.common.exception.BizException IOT-1013（409 规则型参数缺失——抖动防护②）
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @AuditLog(actionType = AuditActionType.WRITE)
    public AlarmRuleVO create(@Valid @RequestBody SaveAlarmRuleRequest request) {
        return alarmRuleService.create(request);
    }

    /**
     * 规则更新（PUT /api/v1/iot/alarm-rules/{id}；WRITE 审计）。
     *
     * @param id      规则行 id（路径变量）
     * @param request 更新请求体（@Valid），非空
     * @return 更新后的规则视图；200
     * @throws com.fuyun.common.exception.BizException IOT-1012（404）或 IOT-1013（409）
     */
    @PutMapping("/{id}")
    @AuditLog(actionType = AuditActionType.WRITE)
    public AlarmRuleVO update(@PathVariable Long id, @Valid @RequestBody SaveAlarmRuleRequest request) {
        return alarmRuleService.update(id, request);
    }

    /**
     * 规则软删（DELETE /api/v1/iot/alarm-rules/{id}；WRITE 审计；@TableLogic 逻辑删）。
     *
     * @param id 规则行 id（路径变量）
     * @return 204 无体
     * @throws com.fuyun.common.exception.BizException IOT-1012（404 规则不存在）
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void delete(@PathVariable Long id) {
        alarmRuleService.delete(id);
    }

    /**
     * 历史回放模拟（POST /api/v1/iot/alarm-rules/{id}/simulate；纯评估不落库，不挂审计）。
     *
     * @param id      规则行 id（路径变量）
     * @param request 回放时段窗口（@Valid），非空
     * @return 触发明细（扫描行数 + 触发清单）；200
     * @throws com.fuyun.common.exception.BizException IOT-1012（404）/IOT-1013（409 非
     *                                                 THRESHOLD）/IOT-1019（400 时窗非法）
     */
    @PostMapping("/{id}/simulate")
    public SimulateResultVO simulate(@PathVariable Long id, @Valid @RequestBody SimulateAlarmRequest request) {
        return alarmRuleService.simulate(id, request);
    }
}
