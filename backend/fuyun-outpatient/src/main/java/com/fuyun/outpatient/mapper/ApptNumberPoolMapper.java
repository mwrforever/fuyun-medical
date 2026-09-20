package com.fuyun.outpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.outpatient.entity.ApptNumberPool;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 号源池行 mapper：单表操作（放号插入）经 BaseMapper 链式能力，另声明池行 CAS 五支注解 SQL——
 * 扣减/回补/加号为 Spec 3.2 双道闸第二道（预约单落库同事务条件更新），停诊/恢复为整池批量联动，
 * 余量查询为对外可约号源过滤投影（条件更新一律 @Update + 影响行数判定，FeeRecordMapper 实证
 * 形态）。必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface ApptNumberPoolMapper extends BaseMapper<ApptNumberPool> {

    /**
     * 池行占用 CAS（Spec 3.2 双道闸第二道：预约单落库同事务执行）：version 乐观锁 + 余量谓词
     * used_count &lt; total_quota 双条件收口，超卖库层不可达。影响行数 0 即重读重试至多 2 次
     * 后判 OP-1003（预约侧消费随 Task 5）。
     *
     * <p>status 字面量与 {@link com.fuyun.outpatient.enums.PoolStatus} code 同源；deleted=0
     * 显式补齐（注解 SQL 不继承 @TableLogic）；updated_by 固定 'system'（扣减无人工操作者语义）。
     *
     * @param poolId  池行主键；来源：预约请求定位的号源行
     * @param version 重读取得的乐观锁版本；来源：扣减前 SELECT 读回
     * @return 影响行数：1=占用成功；0=版本冲突/余量不足/非 ACTIVE（调用方重试或判 OP-1003）
     */
    @Update("UPDATE outpatient.appt_number_pool SET used_count = used_count + 1, version = version + 1, "
            + "updated_by = 'system', updated_at = now() WHERE id = #{poolId} AND deleted = 0 "
            + "AND status = 'ACTIVE' AND used_count < total_quota AND version = #{version}")
    int casOccupy(@Param("poolId") long poolId, @Param("version") int version);

    /**
     * 池行回补 CAS（退号/取消/超时释放回池，与 casOccupy 对称）：带 used_count &gt; 0 谓词防负
     * 余量（重复回补/对账漂移兜底）。
     *
     * <p>status 字面量与 {@link com.fuyun.outpatient.enums.PoolStatus} code 同源；deleted=0
     * 显式补齐（注解 SQL 不继承 @TableLogic）；updated_by 固定 'system'。
     *
     * @param poolId 池行主键；来源：退号/释放载荷定位的号源行
     * @return 影响行数：1=回补成功；0=行不存在/非 ACTIVE/余量已为 0（幂等达成或数据异常留痕）
     */
    @Update("UPDATE outpatient.appt_number_pool SET used_count = used_count - 1, version = version + 1, "
            + "updated_by = 'system', updated_at = now() WHERE id = #{poolId} AND deleted = 0 "
            + "AND status = 'ACTIVE' AND used_count > 0")
    int casRelease(@Param("poolId") long poolId);

    /**
     * 加号授权 CAS（POST /number-pools/{id}/extra-quota）：total_quota 增量 count——加号额度
     * 即总量增量（预约侧 used_count &lt; total_quota 谓词自然放行加号段），加号占用计数走
     * extra_used（Task 5 挂号时按号段归入）。
     *
     * <p>status 字面量与 {@link com.fuyun.outpatient.enums.PoolStatus} code 同源；deleted=0
     * 显式补齐（注解 SQL 不继承 @TableLogic）；updated_by 固定 'system'（操作者留痕经审计切面）。
     *
     * @param poolId 池行主键；来源：加号端点路径参数
     * @param count  加号数量（服务层守卫 1~50，OP-1019 判定前置）；来源：端点入参
     * @return 影响行数：1=加号生效；0=池行不存在或非 ACTIVE（调用方判 OP-1002）
     */
    @Update("UPDATE outpatient.appt_number_pool SET total_quota = total_quota + #{count}, "
            + "version = version + 1, updated_by = 'system', updated_at = now() "
            + "WHERE id = #{poolId} AND deleted = 0 AND status = 'ACTIVE'")
    int casAddExtraQuota(@Param("poolId") long poolId, @Param("count") int count);

    /**
     * 停诊整池联动（stop 同事务批量）：ACTIVE→STOPPED 条件迁移，影响行数即停用池行数（日志留痕）。
     *
     * <p>status 字面量与 {@link com.fuyun.outpatient.enums.PoolStatus} code 同源；deleted=0
     * 显式补齐（注解 SQL 不继承 @TableLogic）。
     *
     * @param scheduleId 排班日历主键；来源：stop 端点路径参数（CAS 成功后联动）
     * @param operator   操作者标识（OperatorContextHolder）；来源：认证拦截器注入
     * @return 影响行数：停用池行数；0=该排班下无 ACTIVE 池行（幂等达成）
     */
    @Update("UPDATE outpatient.appt_number_pool SET status = 'STOPPED', updated_by = #{operator}, updated_at = now() "
            + "WHERE schedule_id = #{scheduleId} AND deleted = 0 AND status = 'ACTIVE'")
    int markStoppedByScheduleId(@Param("scheduleId") long scheduleId, @Param("operator") String operator);

    /**
     * 恢复整池联动（resume 同事务批量）：STOPPED→ACTIVE 条件迁移，与 markStoppedByScheduleId 对称。
     *
     * <p>status 字面量与 {@link com.fuyun.outpatient.enums.PoolStatus} code 同源；deleted=0
     * 显式补齐（注解 SQL 不继承 @TableLogic）。
     *
     * @param scheduleId 排班日历主键；来源：resume 端点路径参数（CAS 成功后联动）
     * @param operator   操作者标识（OperatorContextHolder）；来源：认证拦截器注入
     * @return 影响行数：恢复池行数；0=该排班下无 STOPPED 池行（幂等达成）
     */
    @Update("UPDATE outpatient.appt_number_pool SET status = 'ACTIVE', updated_by = #{operator}, updated_at = now() "
            + "WHERE schedule_id = #{scheduleId} AND deleted = 0 AND status = 'STOPPED'")
    int markActiveByScheduleId(@Param("scheduleId") long scheduleId, @Param("operator") String operator);

    /**
     * 可约号源查询（GET /number-pools/available，供全渠道与 M18 复用）：排班日历关联过滤
     * dept_code+sched_date，仅 ACTIVE 且 used_count &lt; total_quota 行（停诊联动 STOPPED 后
     * 自然出局），按 slot_start 升序（候诊时段序，A.4.3-17 唯一顺序）。
     *
     * <p>谓词即过滤语义：STOPPED/EXHAUSTED（已约满）行不出网；appt_type 可空（NULL=全部号别，
     * jdbcType=VARCHAR 显式声明防 pgjdbc 空参类型漂移）。
     *
     * @param deptCode  开诊科室编码；来源：查询入参（必填）
     * @param schedDate 排班日期；来源：查询入参（必填）
     * @param apptType  号别 code（ApptType）；来源：查询入参（可空=全部号别）
     * @return 可约池行集（slot_start 升序）；无可约号源返回空列表
     */
    @Select("SELECT p.* FROM outpatient.appt_number_pool p "
            + "JOIN outpatient.schedule s ON s.id = p.schedule_id AND s.deleted = 0 "
            + "WHERE s.dept_code = #{deptCode} AND s.sched_date = #{schedDate} "
            + "AND (#{apptType,jdbcType=VARCHAR} IS NULL OR p.appt_type = #{apptType}) "
            + "AND p.deleted = 0 AND p.status = 'ACTIVE' AND p.used_count < p.total_quota "
            + "ORDER BY p.slot_start")
    List<ApptNumberPool> selectAvailable(
            @Param("deptCode") String deptCode,
            @Param("schedDate") LocalDate schedDate,
            @Param("apptType") String apptType);
}
