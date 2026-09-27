package com.fuyun.ward.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.ward.entity.WardCallEntity;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 呼叫行 mapper：呼叫状态机写面的 CAS 唯一入口（GC23 形态：状态迁移与聚合计数一律
 * {@code @Update} + 影响行数判定，显式补 deleted=0——并发双写以旧值限定兜底）。
 *
 * <p>CAS 全集（合法迁移唯一裁决面在 WardCallServiceImpl 状态机表，本层承担行级原子性）：
 * 应答（casAnswer，含转接后回答）、处理（casProgress）、完成（casComplete）、转接（casTransfer）、
 * 取消（casCancel）、升级防重发（casEscalate）、同床位合并取消（cancelActiveByBed）、
 * 拔针复位（cancelActiveInfusionByPatient）、输液落行防重查询（countActiveInfusionBySourceRef）。
 * 必须标注 {@code @Mapper}：app 侧
 * MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface WardCallMapper extends BaseMapper<WardCallEntity> {

    /**
     * 应答 CAS：CREATED/TRANSFERRED → ANSWERED（主链首跳 + 转接侧支回路）。
     *
     * @param callNo   呼叫业务号，非空；来源：应答端点路径变量
     * @param operator 应答人（操作者上下文，无登录上下文回退 system），非空
     * @return 影响行数（1=应答成功；0=非法前置态/已终态/记录不存在——调用方定性 WD-1002/WD-1001）
     */
    @Update("UPDATE ward.ward_call SET status = 'ANSWERED', answered_at = now(), updated_by = #{operator} "
            + "WHERE call_no = #{callNo} AND status IN ('CREATED','TRANSFERRED') AND deleted = 0")
    int casAnswer(@Param("callNo") String callNo, @Param("operator") String operator);

    /**
     * 处理 CAS：ANSWERED → IN_PROGRESS（可选中间态，记录处理人）。
     *
     * @param callNo   呼叫业务号，非空；来源：处理端点路径变量
     * @param operator 处理人（操作者上下文），非空
     * @return 影响行数（1=进入处理中；0=非法前置态/已终态/记录不存在）
     */
    @Update("UPDATE ward.ward_call SET status = 'IN_PROGRESS', processed_by = #{operator}, "
            + "updated_by = #{operator} "
            + "WHERE call_no = #{callNo} AND status = 'ANSWERED' AND deleted = 0")
    int casProgress(@Param("callNo") String callNo, @Param("operator") String operator);

    /**
     * 完成 CAS：ANSWERED/IN_PROGRESS → COMPLETED（IN_PROGRESS 为可选中间态，应答后可直接完成）。
     *
     * @param callNo   呼叫业务号，非空；来源：完成端点路径变量
     * @param summary  处理结果摘要，非空；来源：完成请求体（服务层空值已借承 WD-1005 拒绝）
     * @param operator 完成人（操作者上下文），非空
     * @return 影响行数（1=完成成功；0=非法前置态/已终态/记录不存在）
     */
    @Update("UPDATE ward.ward_call SET status = 'COMPLETED', completed_at = now(), "
            + "result_summary = #{summary}, updated_by = #{operator} "
            + "WHERE call_no = #{callNo} AND status IN ('ANSWERED','IN_PROGRESS') AND deleted = 0")
    int casComplete(
            @Param("callNo") String callNo, @Param("summary") String summary, @Param("operator") String operator);

    /**
     * 转接 CAS：CREATED/ANSWERED → TRANSFERRED（侧支出边；IN_PROGRESS 不可转接——处理中先完成）。
     *
     * @param callNo   呼叫业务号，非空；来源：转接端点路径变量
     * @param operator 转接人（操作者上下文），非空
     * @return 影响行数（1=转接成功；0=非法前置态/已终态/记录不存在）
     */
    @Update("UPDATE ward.ward_call SET status = 'TRANSFERRED', updated_by = #{operator} "
            + "WHERE call_no = #{callNo} AND status IN ('CREATED','ANSWERED') AND deleted = 0")
    int casTransfer(@Param("callNo") String callNo, @Param("operator") String operator);

    /**
     * 取消 CAS：CREATED/TRANSFERRED → CANCELLED（终态迁移；ANSWERED/IN_PROGRESS 不可人工取消）。
     *
     * @param callNo   呼叫业务号，非空；来源：取消端点路径变量
     * @param operator 取消人（操作者上下文），非空
     * @return 影响行数（1=取消成功；0=非法前置态/已终态/记录不存在）
     */
    @Update("UPDATE ward.ward_call SET status = 'CANCELLED', updated_by = #{operator} "
            + "WHERE call_no = #{callNo} AND status IN ('CREATED','TRANSFERRED') AND deleted = 0")
    int casCancel(@Param("callNo") String callNo, @Param("operator") String operator);

    /**
     * 升级 CAS（读时惰性判定写面，inpatient 会诊逾期标记同款形态）：升级次数+1；escalation_count=0
     * 旧值限定兜底并发双读窗口——仅首个判定方递增（DB 字段防重发），状态不变仍可应答。
     *
     * @param callNo   呼叫业务号，非空；来源：列表/详情读路径行
     * @param cutoff   升级时限阈值（created_at 早于该值才递增；来源：now-300s 服务层计算），非空
     * @param operator 操作者（读路径判定回退 system 审计留痕），非空
     * @return 影响行数（1=本次判定方（已超时且未升级）；0=未超时/已升级幂等/已终态不重发）
     */
    @Update("UPDATE ward.ward_call SET escalation_count = escalation_count + 1, updated_by = #{operator} "
            + "WHERE call_no = #{callNo} AND escalation_count = 0 AND created_at < #{cutoff} "
            + "AND status IN ('CREATED','ANSWERED','IN_PROGRESS','TRANSFERRED') AND deleted = 0")
    int casEscalate(
            @Param("callNo") String callNo, @Param("cutoff") OffsetDateTime cutoff, @Param("operator") String operator);

    /**
     * 同床位合并取消（创建时调用，brief 冻结合并语义）：该床位全部活跃旧呼叫批量置 CANCELLED。
     *
     * @param bedId    床位 ID，非空；来源：新建呼叫请求体
     * @param operator 操作者（审计留痕），非空
     * @return 影响行数（被合并取消的活跃行数，可为 0——无旧活跃呼叫场景）
     */
    @Update("UPDATE ward.ward_call SET status = 'CANCELLED', updated_by = #{operator} "
            + "WHERE bed_id = #{bedId} AND status IN ('CREATED','ANSWERED','IN_PROGRESS','TRANSFERRED') "
            + "AND deleted = 0")
    int cancelActiveByBed(@Param("bedId") Long bedId, @Param("operator") String operator);

    /**
     * 拔针复位（nursing.infusion.completed 消费链）：该患者全部活跃输液呼叫批量置 CANCELLED
     * （系统动作非人工操作，不受状态机人工出边约束——复位即呼叫失效合并语义；V800 id 63 载荷
     * 无设备锚，patient_id 为唯一可用复位键，executionNo 进消费日志留痕）。
     *
     * @param patientId 患者主索引，非空；来源：输注结束载荷（载荷 patientId 为空的帧仅留痕不复位）
     * @return 影响行数（被复位的活跃行数，可为 0——无活跃输液呼叫场景）
     */
    @Update("UPDATE ward.ward_call SET status = 'CANCELLED', updated_by = 'system' "
            + "WHERE patient_id = #{patientId} AND call_type = 'INFUSION' "
            + "AND status IN ('CREATED','ANSWERED','IN_PROGRESS','TRANSFERRED') AND deleted = 0")
    int cancelActiveInfusionByPatient(@Param("patientId") long patientId);

    /**
     * 输液告急落行防重查询：同 source_ref（告警号）的活跃 INFUSION 行计数
     * （uk_ward_call_infusion_active 部分唯一索引的并发前置防线，应用层先查后插减冲突概率）。
     *
     * @param sourceRef 告警号，非空；来源：iot.alarm.triggered 载荷 alarmNo
     * @return 活跃行计数（>0=已存在活跃行，跳过落行；0=可落行）
     */
    @Select("SELECT count(*) FROM ward.ward_call WHERE call_type = 'INFUSION' AND source_ref = #{sourceRef} "
            + "AND status IN ('CREATED','ANSWERED','IN_PROGRESS','TRANSFERRED') AND deleted = 0")
    long countActiveInfusionBySourceRef(@Param("sourceRef") String sourceRef);
}
