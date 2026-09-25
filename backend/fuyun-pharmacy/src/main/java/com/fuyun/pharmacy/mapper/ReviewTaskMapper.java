package com.fuyun.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.pharmacy.entity.ReviewTask;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 审方任务 mapper：单表链式能力 + 三态小状态机 CAS 条件更新（注解 SQL 显式补 deleted=0；
 * 状态字面量与 ReviewTaskStatus code 逐字同源；0 行=并发被抢/非待审态，调用方定性拒绝）。
 */
@Mapper
public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {

    /**
     * 决策 CAS（PENDING 唯一可决出边）：通过/驳回共用，决策留痕四值随迁移一并落行
     * （pharmacist_id=审方药师工号、opinion=药师意见、decided_at=决策时刻）。
     *
     * @param id           任务 id
     * @param to           目标态 code（APPROVED/REJECTED），非空
     * @param pharmacistId 审方药师工号，非空
     * @param opinion      药师意见（驳回必填由服务侧前置守卫；通过可空），可空
     * @param decidedAt    决策时刻，非空
     * @return 影响行数（0=非 PENDING 并发被抢/状态违例）
     */
    @Update("UPDATE pharmacy.review_task SET status = #{to}, pharmacist_id = #{pharmacistId}, "
            + "opinion = #{opinion}, decided_at = #{decidedAt} "
            + "WHERE id = #{id} AND status = 'PENDING' AND deleted = 0")
    int casDecide(
            @Param("id") long id,
            @Param("to") String to,
            @Param("pharmacistId") String pharmacistId,
            @Param("opinion") String opinion,
            @Param("decidedAt") OffsetDateTime decidedAt);

    /**
     * 重提重开 CAS（REJECTED→PENDING）：同任务复位非新建（uk_review_medication 一快照一任务），
     * 决策留痕三值清空（decided_at/opinion/pharmacist_id）供新一轮审方重写。
     *
     * @param id 任务 id
     * @return 影响行数（0=他方已先复位/非 REJECTED 态——调用方按现状收敛不上抛）
     */
    @Update("UPDATE pharmacy.review_task SET status = 'PENDING', pharmacist_id = NULL, "
            + "opinion = NULL, decided_at = NULL "
            + "WHERE id = #{id} AND status = 'REJECTED' AND deleted = 0")
    int casReopen(@Param("id") long id);
}
