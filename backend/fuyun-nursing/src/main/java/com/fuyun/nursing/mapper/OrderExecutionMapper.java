package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.OrderExecution;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 医嘱执行单 mapper（V1106 order_execution）：单表链式能力 + 生成域幂等插入与三支 CAS
 * 条件更新注解 SQL（GC26：@Update + 影响行数判定 + 显式 deleted=0——注解 SQL 不继承
 * @TableLogic）。insertIgnorePlanConflict 为 uk_execution_plan 部分唯一索引（(m04_order_no,
 * m04_plan_no) WHERE deleted=0）配套的 ON CONFLICT DO NOTHING 幂等插入——重复事件/乱序补发
 * 零副作用（0 行即已被唯一索引吞掉），比「先查后插+CAS」少一次往返且无查插间隙竞态。
 * 必须标注 @Mapper 供 app 侧扫描。
 */
@Mapper
public interface OrderExecutionMapper extends BaseMapper<OrderExecution> {

    /**
     * 幂等插入（生成域两路共用：转抄临时单/计划批量单）：撞 uk_execution_plan 部分唯一索引
     * 即整行放弃（DO NOTHING）。临时单 m04_plan_no 为 NULL——PG 唯一索引对 NULL 互异，
     * 临时行天然不走冲突面（重复转抄由 eventId 幂等与 M04 转抄锁定前置拦截）。id/状态等
     * 未列列走 DB 默认或 MP 实体承载；status 字面量与 {@link com.fuyun.nursing.enums.ExecutionStatus}
     * code 同源。
     *
     * @param row 待插入执行单行（execItem/status/planTime 等业务列已由服务侧置值），非空
     * @return 影响行数：1=落库成功；0=同 (m04_order_no, m04_plan_no) 在册行已存在（重复事件幂等达成）
     */
    @Insert("INSERT INTO nursing.order_execution ("
            + "id, execution_no, m04_order_no, m04_plan_no, visit_id, patient_id, ward_id, bed_no, "
            + "execution_type, exec_item_code, exec_item_name, plan_time, status, created_by, updated_by) VALUES ("
            + "#{id}, #{executionNo}, #{m04OrderNo}, #{m04PlanNo}, #{visitId}, #{patientId}, #{wardId}, #{bedNo}, "
            + "#{executionType}, #{execItemCode}, #{execItemName}, #{planTime}, #{status}, #{createdBy}, #{updatedBy}) "
            + "ON CONFLICT (m04_order_no, m04_plan_no) WHERE deleted = 0 DO NOTHING")
    int insertIgnorePlanConflict(OrderExecution row);

    /**
     * 医嘱终态撤销 CAS（onOrderTerminal：停嘱/作废共用）：该医嘱全部未执行态执行单批量
     * CANCELLED 并留原因。未执行三态谓词=CREATED/SIGNED/CHECKED——EXECUTING 不动（Spec :126
     * EXECUTING→CANCELLED 仅限输注中断特殊情形，需护士长权限留痕，长期停嘱由 M04 计划侧联动，
     * 本侧仅撤未执行）。COMPLETED/CANCELLED 由谓词天然滤除，重复投递 0 行幂等达成。
     * status 字面量与 ExecutionStatus code 同源；deleted=0 显式补齐。
     *
     * @param m04OrderNo M04 医嘱号（uk_execution_plan 首要素定位），非空；来源：事件载荷
     * @param reason     撤销原因（停嘱理由/作废理由，审计留痕必填），非空；来源：事件载荷
     * @param updatedBy  操作者（消费链路 system 回退），非空
     * @return 影响行数（0=无未执行执行单/重复投递已撤销——幂等达成）
     */
    @Update("UPDATE nursing.order_execution SET status = 'CANCELLED', cancel_reason = #{reason}, "
            + "updated_by = #{updatedBy} WHERE m04_order_no = #{m04OrderNo} "
            + "AND status IN ('CREATED', 'SIGNED', 'CHECKED') AND deleted = 0")
    int casCancelBatch(
            @Param("m04OrderNo") String m04OrderNo,
            @Param("reason") String reason,
            @Param("updatedBy") String updatedBy);

    /**
     * 出院终清按就诊撤销 CAS（visit.discharged 消费面）：该就诊全部未执行态执行单批量
     * CANCELLED、原因固定「出院终清」。谓词与 {@link #casCancelBatch} 同构（未执行三态，
     * EXECUTING 不动——归输注中断特殊面），定位键换 visit_id（终清面=整就诊在途单，非单医嘱）。
     * status 字面量与 ExecutionStatus code 同源；deleted=0 显式补齐。
     *
     * @param visitId   住院就诊号（终清定位键），非空；来源：事件载荷
     * @param reason    终清原因（固定文案「出院终清」，审计留痕），非空
     * @param updatedBy 操作者（消费链路 system 回退），非空
     * @return 影响行数（0=无未执行执行单/重复投递已终清——幂等达成）
     */
    @Update("UPDATE nursing.order_execution SET status = 'CANCELLED', cancel_reason = #{reason}, "
            + "updated_by = #{updatedBy} WHERE visit_id = #{visitId} "
            + "AND status IN ('CREATED', 'SIGNED', 'CHECKED') AND deleted = 0")
    int casCancelByVisit(
            @Param("visitId") String visitId, @Param("reason") String reason, @Param("updatedBy") String updatedBy);

    /**
     * 转科执行单重定向 CAS（visit.transferred 消费面，「转科三分规则」M04 侧⑤：未执行临时
     * 计划随患者转移）：未执行态执行单 ward_id 批量切到转入病区、bed_no 随事件重定向，计划
     * 时间不动。fromWardId 谓词承载幂等——重复投递时行已在 toWardId，0 行自然达成；跨病区
     * 并发转科由行锁串行化，后到事件 fromWard 不匹配即不误伤。bed_no 为冗余展示列：V800 id 49
     * 冻结载荷仅携 toBedId（床位 id）无床位号，落 id 文本形态承载（床位号权威面在 M04 床位域）。
     * status 字面量与 ExecutionStatus code 同源；deleted=0 显式补齐。
     *
     * @param visitId   住院就诊号（重定向定位键），非空；来源：事件载荷
     * @param fromWardId 转出病区编码（幂等谓词：仅迁出仍属原病区的行），非空；来源：事件载荷
     * @param toWardId  转入病区编码（重定向目标），非空；来源：事件载荷
     * @param toBedNo   转入床位承载值（床位 id 文本形态），非空；来源：事件载荷 toBedId
     * @param updatedBy 操作者（消费链路 system 回退），非空
     * @return 影响行数（0=该就诊无未执行执行单/已重定向到 toWard——幂等达成）
     */
    @Update("UPDATE nursing.order_execution SET ward_id = #{toWardId}, bed_no = #{toBedNo}, "
            + "updated_by = #{updatedBy} WHERE visit_id = #{visitId} AND ward_id = #{fromWardId} "
            + "AND status IN ('CREATED', 'SIGNED', 'CHECKED') AND deleted = 0")
    int casRedirectWard(
            @Param("visitId") String visitId,
            @Param("fromWardId") String fromWardId,
            @Param("toWardId") String toWardId,
            @Param("toBedNo") String toBedNo,
            @Param("updatedBy") String updatedBy);
}
