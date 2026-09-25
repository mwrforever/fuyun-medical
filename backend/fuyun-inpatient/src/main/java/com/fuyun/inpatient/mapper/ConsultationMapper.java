package com.fuyun.inpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.inpatient.entity.Consultation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 会诊单 mapper（V908 consultation）：单表链式能力 + 状态/标记条件更新注解 SQL 全集
 * （GC23：会诊小状态机迁移与逾期标记一律 @Update + 影响行数判定，显式补 deleted=0；
 * 状态字面量与 V908 列值域、ConsultationStatus code 逐字同源）。接单 CAS 兼承载「逾期后
 * 仍可响应」语义——同语句清 overdue_flag（升级动作不阻断响应闭环）；逾期标记 CAS 以
 * overdue_flag = false 旧值限定承载 overdue 动作事件一次的防重发（DB 标记防重发红线）。
 */
@Mapper
public interface ConsultationMapper extends BaseMapper<Consultation> {

    /**
     * 受邀科接单 CAS（REQUESTED→ACCEPTED）：接单时点落库端 now() 并<b>清除逾期标记</b>
     * （超时升级为动作非状态迁移——逾期单仍可响应，Spec FU-M04-09 冻结口径）。
     *
     * @param consultNo 会诊单号，非空；来源：路径参数
     * @param operator  操作者（审计留痕），非空
     * @return 影响行数（0=非 REQUESTED 态并发迁移/行不存在——调用方定性 IP-1020）
     */
    @Update("UPDATE inpatient.consultation SET status = 'ACCEPTED', response_time = now(), "
            + "overdue_flag = FALSE, updated_by = #{operator} "
            + "WHERE consult_no = #{consultNo} AND status = 'REQUESTED' AND deleted = 0")
    int casAccept(@Param("consultNo") String consultNo, @Param("operator") String operator);

    /**
     * 意见提交完成 CAS（ACCEPTED→COMPLETED）：完成时点落库端 now()、会诊意见同语句归档
     * （意见归档供 M09 病历引用）。
     *
     * @param consultNo 会诊单号，非空；来源：路径参数
     * @param opinion   会诊意见文本，非空；来源：受邀科医生意见提交单
     * @param operator  操作者（审计留痕），非空
     * @return 影响行数（0=非 ACCEPTED 态并发迁移/行不存在——调用方定性 IP-1020）
     */
    @Update("UPDATE inpatient.consultation SET status = 'COMPLETED', consult_time = now(), "
            + "opinion = #{opinion}, updated_by = #{operator} "
            + "WHERE consult_no = #{consultNo} AND status = 'ACCEPTED' AND deleted = 0")
    int casComplete(
            @Param("consultNo") String consultNo, @Param("opinion") String opinion, @Param("operator") String operator);

    /**
     * 取消会诊 CAS（REQUESTED/ACCEPTED→CANCELLED 终态）：申请方撤单双合法出边；COMPLETED
     * 终态不可取消（意见已归档）。
     *
     * @param consultNo 会诊单号，非空；来源：路径参数
     * @param operator  操作者（审计留痕），非空
     * @return 影响行数（0=终态/行不存在——调用方定性 IP-1020）
     */
    @Update("UPDATE inpatient.consultation SET status = 'CANCELLED', updated_by = #{operator} "
            + "WHERE consult_no = #{consultNo} AND status IN ('REQUESTED','ACCEPTED') AND deleted = 0")
    int casCancel(@Param("consultNo") String consultNo, @Param("operator") String operator);

    /**
     * 逾期升级标记 CAS（读时惰性逾期判定写面）：REQUESTED 且未标记行置 overdue_flag=true；
     * 旧值 false 限定兜底并发双读窗口——仅首个置位方发布 overdue 动作事件（DB 标记防重发，
     * 二次查询零行不重发）。
     *
     * @param consultNo 会诊单号，非空；来源：列表页行
     * @param operator  操作者（审计留痕；读路径置标记回退 system），非空
     * @return 影响行数（1=本次置位方发 overdue 事件；0=已标记幂等/已非 REQUESTED 态不重发）
     */
    @Update("UPDATE inpatient.consultation SET overdue_flag = TRUE, updated_by = #{operator} "
            + "WHERE consult_no = #{consultNo} AND status = 'REQUESTED' "
            + "AND overdue_flag = FALSE AND deleted = 0")
    int casMarkOverdue(@Param("consultNo") String consultNo, @Param("operator") String operator);
}
