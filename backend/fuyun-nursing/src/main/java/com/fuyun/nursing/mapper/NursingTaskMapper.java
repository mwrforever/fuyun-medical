package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.NursingTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 护理任务 mapper：单表链式能力 + 终态流转/逾期标记 CAS 条件更新注解 SQL（GC26：@Update +
 * 影响行数判定 + 显式 deleted=0）。行写入主链为 insert（唯一约束冲突由服务层转 NS-1016 幂等
 * 拒绝）；complete/cancel 为仅有状态变更面（单表单语句、零级联），并发重复操作由 CAS 行数
 * 判定兜底；casMarkOverdue 的 overdue_flag=false 谓词保证升级次数仅首次递增（读时惰性判定
 * 的并发幂等根基）。
 */
@Mapper
public interface NursingTaskMapper extends BaseMapper<NursingTask> {

    /**
     * 完成 CAS（在途两态 PENDING/IN_PROGRESS 可完成，0 行 → NS-1011）：置 COMPLETED 并盖章
     * completed_at（DB now()，与审计列同源时钟）；并发重复完成由行数判定兜底。
     *
     * @param taskNo   任务业务号，非空
     * @param operator 操作者（OperatorContextHolder 当前操作者），非空
     * @return 影响行数（0=任务不存在、已终态或已被逻辑删，调用方定性 NS-1011）
     */
    @Update("UPDATE nursing.nursing_task SET status = 'COMPLETED', completed_at = now(), updated_by = #{operator} "
            + "WHERE task_no = #{taskNo} AND status IN ('PENDING', 'IN_PROGRESS') AND deleted = 0")
    int casComplete(@Param("taskNo") String taskNo, @Param("operator") String operator);

    /**
     * 取消 CAS（在途两态 PENDING/IN_PROGRESS 可取消，0 行 → NS-1011）：置 CANCELLED 并强制
     * 落取消原因（服务面已做非空校验，留痕不可缺）；并发重复取消由行数判定兜底。
     *
     * @param taskNo   任务业务号，非空
     * @param reason   取消原因（服务面强制非空，医疗审计依据），非空
     * @param operator 操作者（OperatorContextHolder 当前操作者），非空
     * @return 影响行数（0=任务不存在、已终态或已被逻辑删，调用方定性 NS-1011）
     */
    @Update("UPDATE nursing.nursing_task SET status = 'CANCELLED', cancel_reason = #{reason}, updated_by = #{operator} "
            + "WHERE task_no = #{taskNo} AND status IN ('PENDING', 'IN_PROGRESS') AND deleted = 0")
    int casCancel(@Param("taskNo") String taskNo, @Param("reason") String reason, @Param("operator") String operator);

    /**
     * 逾期标记 CAS（读时惰性判定落点，0 行=已标记过或行不可达）：overdue_flag 置 true 且
     * escalation_count 仅在 overdue_flag=false 谓词命中时递增一次——读路径并发重复判定与
     * 重复查询均不重复递增（Spec :127 动作式逾期，仅首次升级计数）。
     *
     * @param id 任务行 id，非空
     * @return 影响行数（0=已标记过、任务不在途或已被逻辑删，调用方不回写内存行）
     */
    @Update("UPDATE nursing.nursing_task SET overdue_flag = true, escalation_count = escalation_count + 1 "
            + "WHERE id = #{id} AND overdue_flag = false AND deleted = 0")
    int casMarkOverdue(@Param("id") long id);
}
