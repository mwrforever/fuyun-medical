package com.fuyun.iot.service;

import com.fuyun.iot.dto.SaveAlarmRuleRequest;
import com.fuyun.iot.dto.SimulateAlarmRequest;
import com.fuyun.iot.vo.AlarmRuleVO;
import com.fuyun.iot.vo.SimulateResultVO;
import java.util.List;

/**
 * 告警规则服务（FU-M14-08 规则管理面）：规则 CRUD（软删）与 simulate 历史回放模拟的唯一业务出口。
 *
 * <p>抖动防护②在保存面强制：THRESHOLD 规则必须同时配置指标编码/比较方向/阈值/持续时长与恢复带、
 * OFFLINE 规则必须配置离线判定秒，缺失即 IOT-1013 409 拒保存（告警引擎评估侧假定参数齐备）。
 * simulate 仅支持 THRESHOLD 规则（对历史遥测行重放越限回合状态机，返回触发明细不落库）。
 *
 * <p>落 service 契约包（宪法 B.1）：controller 与（未来）管理台共用同一语言。
 */
public interface IAlarmRuleService {

    /**
     * 规则清单（GET /api/v1/iot/alarm-rules；纯读，id 升序稳定输出）。
     *
     * @return 规则视图清单，非空；空表为空清单
     */
    List<AlarmRuleVO> list();

    /**
     * 规则登记（POST /api/v1/iot/alarm-rules；写操作，规则型参数条件必填校验）。
     *
     * @param request 登记请求体，非空；来源：管理台表单（@Valid 基础校验后）
     * @return 落库后的规则视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1013（409 规则型参数缺失——抖动防护②）
     */
    AlarmRuleVO create(SaveAlarmRuleRequest request);

    /**
     * 规则更新（PUT /api/v1/iot/alarm-rules/{id}；写操作，字段全量覆写）。
     *
     * @param id      规则行 id，非空；来源：路径变量
     * @param request 更新请求体，非空
     * @return 更新后的规则视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1012（404 规则不存在）或
     *                                                 IOT-1013（409 规则型参数缺失）
     */
    AlarmRuleVO update(Long id, SaveAlarmRuleRequest request);

    /**
     * 规则软删（DELETE /api/v1/iot/alarm-rules/{id}；@TableLogic 逻辑删，已产告警行不受影响）。
     *
     * @param id 规则行 id，非空；来源：路径变量
     * @throws com.fuyun.common.exception.BizException IOT-1012（404 规则不存在）
     */
    void delete(Long id);

    /**
     * 历史回放模拟（POST /api/v1/iot/alarm-rules/{id}/simulate；纯评估不落库）：对回放时段内
     * 规则匹配的遥测行按越限回合状态机重放评估，返回触发明细。
     *
     * @param id      规则行 id，非空；来源：路径变量
     * @param request 回放时段窗口，非空
     * @return 回放结果（扫描行数 + 触发明细），非空
     * @throws com.fuyun.common.exception.BizException IOT-1012（404 规则不存在）、
     *                                                 IOT-1013（409 非 THRESHOLD 规则不支持回放）或
     *                                                 IOT-1019（400 时窗非法）
     */
    SimulateResultVO simulate(Long id, SimulateAlarmRequest request);
}
