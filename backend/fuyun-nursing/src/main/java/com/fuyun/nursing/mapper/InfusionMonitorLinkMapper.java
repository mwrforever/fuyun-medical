package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.InfusionMonitorLink;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 输液监测挂接 mapper（V1106 infusion_monitor_link）：单表链式能力 + 摆药签收衔接幂等插入
 * 与输液闭环四支 CAS（激活/收口/挂单刷新/复位）注解 SQL 全集（GC26：@Update + 影响行数判定
 * + 显式 deleted=0——注解 SQL 不继承 @TableLogic）。必须标注 @Mapper 供 app 侧扫描。
 */
@Mapper
public interface InfusionMonitorLinkMapper extends BaseMapper<InfusionMonitorLink> {

    /**
     * 幂等插入（摆药签收衔接 PIVAS 升格建链）：撞 uk_monitor_link_execution 部分唯一索引
     * 即整行放弃（DO NOTHING）——重复投递/乱序补发零副作用（0 行即已被唯一索引吞掉），
     * 与「先查后插」相比无查插间隙竞态。
     *
     * @param row 待插入挂接行（executionNo/bagLabelCode/startedAt/linkStatus/审计列已置值），非空
     * @return 影响行数：1=建链成功；0=该执行单在册挂接已存在（重复事件幂等达成）
     */
    @Insert("INSERT INTO nursing.infusion_monitor_link ("
            + "id, execution_no, bag_label_code, link_status, started_at, created_by, updated_by) VALUES ("
            + "#{id}, #{executionNo}, #{bagLabelCode}, #{linkStatus}, #{startedAt}, #{createdBy}, #{updatedBy}) "
            + "ON CONFLICT (execution_no) WHERE deleted = 0 DO NOTHING")
    int insertIgnoreExecutionConflict(InfusionMonitorLink row);

    /**
     * 开始输注激活 CAS（Task 6 start 的 INFUSION 分支）：MONITORING 挂接行刷新 started_at
     * （摆药签收链建链时点 → 真实开始输注时点）并回填 iot_device_id（COALESCE——deviceId
     * 缺席保留既有值，PDA 未扫码不清空已回填设备）。非 MONITORING 行（ENDED/RELEASED）
     * 由谓词滤除（0 行=挂接已收口，调用方 NS-1024 fail-closed）。
     *
     * @param executionNo 执行单号（uk_monitor_link_execution 定位），非空
     * @param startedAt   开始输注时点（北京钟面 now——执行单 started_at 同源单钟），非空
     * @param deviceId    输液泵设备标识（可空——空则保留既有 iot_device_id），可空
     * @param updatedBy   操作者（审计留痕），非空
     * @return 影响行数（0=无 MONITORING 挂接，调用方 NS-1024 拒绝）
     */
    @Update("UPDATE nursing.infusion_monitor_link SET started_at = #{startedAt}, "
            + "iot_device_id = COALESCE(#{deviceId}, iot_device_id), updated_by = #{updatedBy} "
            + "WHERE execution_no = #{executionNo} AND link_status = 'MONITORING' AND deleted = 0")
    int casActivate(
            @Param("executionNo") String executionNo,
            @Param("startedAt") OffsetDateTime startedAt,
            @Param("deviceId") String deviceId,
            @Param("updatedBy") String updatedBy);

    /**
     * 拔针收口 CAS（Task 6 needle-out）：MONITORING→ENDED 迁移并落 ended_at（结束监测时点）。
     * 非 MONITORING 行由谓词滤除（0 行=挂接缺位或已收口，调用方 NS-1024 fail-closed——
     * 拔针对无在途监测的执行单属数据不一致，宁拒不静默）。
     *
     * @param executionNo 执行单号，非空
     * @param endedAt     结束监测时点（=拔针时点同刻），非空
     * @param updatedBy   操作者（审计留痕），非空
     * @return 影响行数（0=无 MONITORING 挂接，调用方 NS-1024 拒绝）
     */
    @Update("UPDATE nursing.infusion_monitor_link SET link_status = 'ENDED', ended_at = #{endedAt}, "
            + "updated_by = #{updatedBy} WHERE execution_no = #{executionNo} "
            + "AND link_status = 'MONITORING' AND deleted = 0")
    int casEnd(
            @Param("executionNo") String executionNo,
            @Param("endedAt") OffsetDateTime endedAt,
            @Param("updatedBy") String updatedBy);

    /**
     * 告警挂单刷新 CAS（Task 6 IotAlarmExecutionListener triggered/escalated 路）：MONITORING
     * 挂接行 latest_alarm_no 刷新（挂接维挂单锚，与执行单行 latest_alarm_no 双落）。
     *
     * @param executionNo 执行单号，非空
     * @param alarmNo     告警业务号（刷新值），非空
     * @param updatedBy   操作者（MQ 链路 SYSTEM 桥接），非空
     * @return 影响行数（0=挂接非 MONITORING——终局后告警不再挂接，幂等零副作用）
     */
    @Update("UPDATE nursing.infusion_monitor_link SET latest_alarm_no = #{alarmNo}, "
            + "updated_by = #{updatedBy} WHERE execution_no = #{executionNo} "
            + "AND link_status = 'MONITORING' AND deleted = 0")
    int casMarkAlarm(
            @Param("executionNo") String executionNo,
            @Param("alarmNo") String alarmNo,
            @Param("updatedBy") String updatedBy);

    /**
     * 告警关闭复位 CAS（Task 6 IotAlarmExecutionListener closed 路）：按告警号反查挂接行
     * latest_alarm_no 复位为 NULL（与执行单行复位路同构；重复关闭 0 行幂等）。
     *
     * @param alarmNo   告警业务号（复位定位键），非空
     * @param updatedBy 操作者（MQ 链路 SYSTEM 桥接），非空
     * @return 影响行数（0=该告警无挂接行/重复关闭——幂等达成）
     */
    @Update("UPDATE nursing.infusion_monitor_link SET latest_alarm_no = NULL, updated_by = #{updatedBy} "
            + "WHERE latest_alarm_no = #{alarmNo} AND deleted = 0")
    int casResetAlarmByAlarmNo(@Param("alarmNo") String alarmNo, @Param("updatedBy") String updatedBy);
}
