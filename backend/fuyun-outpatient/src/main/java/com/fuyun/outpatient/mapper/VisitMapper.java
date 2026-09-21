package com.fuyun.outpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.outpatient.entity.Visit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 就诊记录 mapper：单表操作（挂号落库/就诊号定位/在途查询）经 BaseMapper 链式能力，另声明状态 CAS
 * 注解 SQL——visit 主状态机迁移的数据面（报到 WAITING 与缴费回执待缴费推进走通用 casStatus；接诊/
 * 诊毕走 casAdmit/casFinish 专用 CAS——状态迁移与国标时间回填单条 UPDATE，时间取库端 now() 与
 * 票面 serve_time 同源，禁应用服务器时钟防多实例漂移）；红线 5：迁移前置校验经状态机单点
 * （OutpatientVisitStateMachine.require），CAS 命中后每迁必记 visit_status_log。必须标注
 * {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface VisitMapper extends BaseMapper<Visit> {

    /**
     * 就诊状态 CAS：影响行数 0=行不存在/并发已迁移/状态违例（调用方重读定性后幂等跳过或拒绝）。
     *
     * <p>status 字面量与 {@link com.fuyun.outpatient.enums.VisitStatus} code 同源（入参传
     * {@code VisitStatus.XXX.getCode()}，且 from→to 须为状态机合法迁移对）；deleted=0 显式补齐
     * （注解 SQL 不继承 @TableLogic）；updated_by 固定 'system'（操作者留痕由 visit_status_log
     * operator 列承载）。消费面：分诊报到（REGISTERED→WAITING）、缴费回执待缴费推进
     * （IN_CONSULT→PENDING_FEE，MQ 消费线程系统驱动）。
     *
     * @param id         就诊记录主键（visit 表 PK）；来源：按 visit_id 定位后取得
     * @param fromStatus 期望迁出态 code（如 REGISTERED）
     * @param toStatus   目标态 code（如 WAITING/PENDING_FEE）
     * @return 影响行数：1=迁移成功（调用方随即写 visit_status_log）；0=行不存在或状态违例/并发落败
     */
    @Update("UPDATE outpatient.visit SET status = #{toStatus}, updated_by = 'system', updated_at = now() "
            + "WHERE id = #{id} AND deleted = 0 AND status = #{fromStatus}")
    int casStatus(@Param("id") long id, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus);

    /**
     * 接诊专用 CAS（状态迁移+admitted_at 国标接诊时间回填单条 UPDATE，库端 now() 与票面 serve_time
     * 同源——禁应用服务器时钟防多实例漂移；替代「CAS+updateById 全字段回写」两段形态，消除并发
     * 全字段覆盖窗口）：影响行数 0=行不存在/并发已迁移/状态违例（调用方判 OP-1011）。
     * updated_by 携带操作者留痕。
     *
     * @param id         就诊记录主键；来源：按 visit_id 定位后取得
     * @param fromStatus 期望迁出态 code（唯一合法来源 WAITING，前置经状态机校验）
     * @param toStatus   目标态 code（IN_CONSULT）
     * @param operator   操作者标识（接诊医生），非空；留痕 updated_by
     * @return 影响行数：1=接诊成功（status/admitted_at 已迁移回填）；0=并发落败或状态违例
     */
    @Update("UPDATE outpatient.visit SET status = #{toStatus}, admitted_at = now(), updated_by = #{operator}, "
            + "updated_at = now() WHERE id = #{id} AND deleted = 0 AND status = #{fromStatus}")
    int casAdmit(
            @Param("id") long id,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("operator") String operator);

    /**
     * 诊毕专用 CAS（状态迁移+finished_at 国标诊毕时间回填+离院去向/诊毕操作者落列单条 UPDATE，
     * 时间取库端 now() 与票面 serve_time 同源——禁应用服务器时钟；替代「CAS+updateById 全字段
     * 回写」两段形态，消除并发全字段覆盖窗口）：影响行数 0=行不存在/并发已迁移/状态违例
     * （调用方判 OP-1011）。
     *
     * @param id          就诊记录主键；来源：按 visit_id 定位后取得
     * @param fromStatus  期望迁出态 code（IN_CONSULT 或显式确认的 PENDING_FEE，前置经状态机校验）
     * @param toStatus    目标态 code（FINISHED）
     * @param operator    操作者标识（诊毕医生），非空；留痕 finish_operator/updated_by
     * @param disposition 离院去向词表值（V705 item_code 全集，调用方前置 OP-1018 校验），非空
     * @return 影响行数：1=诊毕成功（status/finished_at/disposition/finish_operator 已回填）；
     *         0=并发落败或状态违例
     */
    @Update("UPDATE outpatient.visit SET status = #{toStatus}, finished_at = now(), disposition = #{disposition}, "
            + "finish_operator = #{operator}, updated_by = #{operator}, updated_at = now() "
            + "WHERE id = #{id} AND deleted = 0 AND status = #{fromStatus}")
    int casFinish(
            @Param("id") long id,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("operator") String operator,
            @Param("disposition") String disposition);
}
