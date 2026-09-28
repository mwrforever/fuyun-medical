package com.fuyun.iot.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.AlarmQueryRequest;
import com.fuyun.iot.dto.CloseAlarmRequest;
import com.fuyun.iot.vo.AlarmVO;

/**
 * 告警服务（FU-M14-08 告警生命周期面）：分页查询与人工处置（确认/关闭）的唯一业务出口。
 *
 * <p>读时惰性升级不在本服务——抑制⑤升级惰性扫描挂 AlarmEngine.evaluate 调用点（brief 冻结
 * 口径），本服务保持查询与处置的窄职责。确认/关闭一律 CAS（@Update 显式 deleted=0）并发兜底，
 * 关闭同事务发布 iot.alarm.closed 事件（AFTER_COMMIT 出 MQ）。
 *
 * <p>错误码借承申报（MetricDictServiceImpl 先例）：告警行不存在借承 IOT-1012（其字面语义为
 * 告警规则不存在）、状态机违例借承 IOT-1013——两码位为 Task 1 冻结告警域词表内唯一 404/409 码位，
 * 消息显式区分场景；如需专属码位走顺延提案修订词表。
 */
public interface IAlarmService {

    /**
     * 告警分页查询（GET /api/v1/iot/alarms；病区/级别/状态过滤，last_triggered_at 降序稳定输出）。
     *
     * @param request 分页查询请求（page 0 基，可空位缺省），非空
     * @return 分页出参，非空
     */
    PageResult<AlarmVO> page(AlarmQueryRequest request);

    /**
     * 告警确认（POST /api/v1/iot/alarms/{alarmNo}/acknowledge；写操作）：ACTIVE → ACKNOWLEDGED
     * （确认后抑制⑤升级链停止）。
     *
     * @param alarmNo 告警业务号，非空；来源：路径变量
     * @return 确认后的告警视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1012（404 告警不存在——借承）或
     *                                                 IOT-1013（409 仅 ACTIVE 可确认——借承）
     */
    AlarmVO acknowledge(String alarmNo);

    /**
     * 告警关闭（POST /api/v1/iot/alarms/{alarmNo}/close；写操作，原因必填）：ACTIVE/ACKNOWLEDGED
     * → CLOSED 终态，同事务发布 iot.alarm.closed 事件（M05/M16 复位消费）。
     *
     * @param alarmNo 告警业务号，非空；来源：路径变量
     * @param request 关闭请求（原因必填），非空
     * @return 关闭后的告警视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1012（404 告警不存在——借承）或
     *                                                 IOT-1013（409 终态不可再关闭——借承）
     */
    AlarmVO close(String alarmNo, CloseAlarmRequest request);
}
