package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.OrderExecution;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 医嘱执行单 mapper（V1106 order_execution）：单表链式能力 + 生成域幂等插入与条件更新
 * 注解 SQL 全集（GC26：@Update + 影响行数判定 + 显式 deleted=0——注解 SQL 不继承
 * @TableLogic）。insertIgnorePlanConflict 为 uk_execution_plan 部分唯一索引（(m04_order_no,
 * m04_plan_no) WHERE deleted=0）配套的 ON CONFLICT DO NOTHING 幂等插入——重复事件/乱序补发
 * 零副作用（0 行即已被唯一索引吞掉），比「先查后插+CAS」少一次往返且无查插间隙竞态；
 * 生成域四支 CAS（撤销批量/按就诊撤销/转科重定向）与操作域九支 CAS（补签收/核对/开始/
 * 完成/撤销两路/破码/对账/摆药签收/PIVAS 升格）同规范承载。
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

    /**
     * 人工补签收 CAS（Task 5 操作域 sign-receive 端点）：CREATED→SIGNED 单行迁移并落签收
     * 时点与签收护士。药品类主入口为 dispense 事件自动 SIGNED（casSignReceiveBatchByOrder），
     * 本 CAS 供非药品类人工补签；重复补签由 CREATED 谓词天然滤除（0 行）。status 字面量与
     * ExecutionStatus code 同源；deleted=0 显式补齐。
     *
     * @param executionNo 执行单号（uk_execution_no 定位），非空；来源：路径参数
     * @param signedAt    签收时点（北京钟面 now），非空
     * @param executorId  签收护士员工 ID（操作者上下文解析），非空
     * @param updatedBy   操作者（审计留痕），非空
     * @return 影响行数（0=非 CREATED 态——已签收/已核对/终态，调用方 NS-1021 拒绝）
     */
    @Update("UPDATE nursing.order_execution SET status = 'SIGNED', signed_at = #{signedAt}, "
            + "executor_id = #{executorId}, updated_by = #{updatedBy} "
            + "WHERE execution_no = #{executionNo} AND status = 'CREATED' AND deleted = 0")
    int casSignReceive(
            @Param("executionNo") String executionNo,
            @Param("signedAt") OffsetDateTime signedAt,
            @Param("executorId") long executorId,
            @Param("updatedBy") String updatedBy);

    /**
     * 扫码核对通过 CAS（Task 5 操作域 check 端点）：SIGNED→CHECKED 单行迁移并落核对通过
     * 时点与核对护士。谓词含 CREATED——非药品类生成即可核对（SIGNED 可跳过，ExecutionStatus
     * 词表冻结口径）；CHECKED 及之后态由谓词滤除（0 行=已核对/执行中/终态）。status 字面量与
     * ExecutionStatus code 同源；deleted=0 显式补齐。
     *
     * @param executionNo 执行单号，非空；来源：路径参数
     * @param checkedAt   核对通过时点（北京钟面 now），非空
     * @param checkerId   核对护士员工 ID（操作者上下文解析），非空
     * @param updatedBy   操作者（审计留痕），非空
     * @return 影响行数（0=状态前置不满足，调用方 NS-1021 拒绝）
     */
    @Update("UPDATE nursing.order_execution SET status = 'CHECKED', checked_at = #{checkedAt}, "
            + "checker_id = #{checkerId}, updated_by = #{updatedBy} "
            + "WHERE execution_no = #{executionNo} AND status IN ('CREATED', 'SIGNED') AND deleted = 0")
    int casCheckPassed(
            @Param("executionNo") String executionNo,
            @Param("checkedAt") OffsetDateTime checkedAt,
            @Param("checkerId") long checkerId,
            @Param("updatedBy") String updatedBy);

    /**
     * 开始执行 CAS（Task 5 操作域 start 端点）：CHECKED→EXECUTING 单行迁移并落开始时点与
     * 执行护士。时间窗外拦截在服务层前置（NS-1027），本 CAS 仅承载状态迁移。INFUSION 型
     * 同走本迁移（建链/激活与 infusion.started 事件归 Task 6 在 CAS 成功后段扩展）。
     * status 字面量与 ExecutionStatus code 同源；deleted=0 显式补齐。
     *
     * @param executionNo 执行单号，非空；来源：路径参数
     * @param startedAt   开始执行时点（北京钟面 now），非空
     * @param executorId  执行护士员工 ID（请求承载），非空
     * @param updatedBy   操作者（审计留痕），非空
     * @return 影响行数（0=非 CHECKED 态——未核对/执行中/终态，调用方 NS-1021 拒绝）
     */
    @Update("UPDATE nursing.order_execution SET status = 'EXECUTING', started_at = #{startedAt}, "
            + "executor_id = #{executorId}, updated_by = #{updatedBy} "
            + "WHERE execution_no = #{executionNo} AND status = 'CHECKED' AND deleted = 0")
    int casStart(
            @Param("executionNo") String executionNo,
            @Param("startedAt") OffsetDateTime startedAt,
            @Param("executorId") long executorId,
            @Param("updatedBy") String updatedBy);

    /**
     * 执行完成 CAS（Task 5 操作域 finish 端点）：EXECUTING→COMPLETED 单行迁移并落完成时点
     * （环节时点集收口）。双路回签（事务内发布回执事件+事务提交后进程内回签端口）在服务层
     * CAS 成功后承载。status 字面量与 ExecutionStatus code 同源；deleted=0 显式补齐。
     *
     * @param executionNo 执行单号，非空；来源：路径参数
     * @param finishedAt  执行完成时点（北京钟面 now），非空
     * @param updatedBy   操作者（审计留痕），非空
     * @return 影响行数（0=非 EXECUTING 态，调用方 NS-1021 拒绝）
     */
    @Update("UPDATE nursing.order_execution SET status = 'COMPLETED', finished_at = #{finishedAt}, "
            + "updated_by = #{updatedBy} WHERE execution_no = #{executionNo} "
            + "AND status = 'EXECUTING' AND deleted = 0")
    int casFinish(
            @Param("executionNo") String executionNo,
            @Param("finishedAt") OffsetDateTime finishedAt,
            @Param("updatedBy") String updatedBy);

    /**
     * 未执行态撤销 CAS（Task 5 操作域 cancel 端点·未执行分支）：CREATED/SIGNED/CHECKED→
     * CANCELLED 单行迁移并落原因。与 {@link #casCancelBatch} 谓词同构（定位键换执行单号——
     * 端点面单行操作）。status 字面量与 ExecutionStatus code 同源；deleted=0 显式补齐。
     *
     * @param executionNo 执行单号，非空；来源：路径参数
     * @param reason      撤销原因（审计留痕必填），非空
     * @param updatedBy   操作者（审计留痕），非空
     * @return 影响行数（0=EXECUTING/终态——EXECUTING 撤销走输注中断专用面，调用方 NS-1021 拒绝）
     */
    @Update("UPDATE nursing.order_execution SET status = 'CANCELLED', cancel_reason = #{reason}, "
            + "updated_by = #{updatedBy} WHERE execution_no = #{executionNo} "
            + "AND status IN ('CREATED', 'SIGNED', 'CHECKED') AND deleted = 0")
    int casCancelPending(
            @Param("executionNo") String executionNo,
            @Param("reason") String reason,
            @Param("updatedBy") String updatedBy);

    /**
     * 输注中断撤销 CAS（Task 5 操作域 cancel 端点·EXECUTING 分支）：EXECUTING 且仅 INFUSION
     * 型可撤销（输注中断特殊面——护士长权限近似校验在服务层前置），撤销同时落 finished_at
     * （执行链路终止时点=部分执行回签 executedAt 基准）。GENERIC 型 EXECUTING 撤销由谓词
     * 滤除（0 行）。status 字面量与 ExecutionStatus/ExecutionType code 同源；deleted=0
     * 显式补齐。
     *
     * @param executionNo 执行单号，非空；来源：路径参数
     * @param reason      撤销原因（输注中断理由，审计留痕必填），非空
     * @param finishedAt  中断时点（北京钟面 now，落 finished_at 承载链路终止），非空
     * @param updatedBy   操作者（审计留痕），非空
     * @return 影响行数（0=非 EXECUTING 态或非 INFUSION 型，调用方 NS-1021 拒绝）
     */
    @Update("UPDATE nursing.order_execution SET status = 'CANCELLED', cancel_reason = #{reason}, "
            + "finished_at = #{finishedAt}, updated_by = #{updatedBy} "
            + "WHERE execution_no = #{executionNo} AND status = 'EXECUTING' "
            + "AND execution_type = 'INFUSION' AND deleted = 0")
    int casCancelInfusionInterrupt(
            @Param("executionNo") String executionNo,
            @Param("reason") String reason,
            @Param("finishedAt") OffsetDateTime finishedAt,
            @Param("updatedBy") String updatedBy);

    /**
     * 破码放行标记 CAS（Task 5 pda/override-check 端点）：override_flag false→true 单行置位。
     * 重复放行由 false 谓词滤除（0 行幂等达成，不构成失败）；置位后 start 端点跳过时间窗
     * 校验（服务层谓词）。deleted=0 显式补齐。
     *
     * @param executionNo 执行单号，非空；来源：请求体
     * @param updatedBy   操作者（审计留痕），非空
     * @return 影响行数（0=已放行幂等/行不存在，调用方以幂等达成口径留痕）
     */
    @Update("UPDATE nursing.order_execution SET override_flag = true, updated_by = #{updatedBy} "
            + "WHERE execution_no = #{executionNo} AND override_flag = false AND deleted = 0")
    int casMarkOverride(@Param("executionNo") String executionNo, @Param("updatedBy") String updatedBy);

    /**
     * 回签对账状态迁移 CAS（Task 5 双路回签/补偿）：confirm_status 单行条件迁移（from 态
     * 限定）。主路径成功 PENDING→CONFIRMED、失败 PENDING→COMPENSATING；补偿 tick 重试成功
     * COMPENSATING→CONFIRMED。from 限定承载并发防覆写（补偿与主路径并发窗口互斥）。
     * status 字面量与 V1106 confirm_status 值域同源；deleted=0 显式补齐。
     *
     * @param executionNo 执行单号，非空
     * @param fromStatus  迁移前对账状态（PENDING/COMPENSATING），非空
     * @param toStatus    迁移后对账状态（CONFIRMED/COMPENSATING），非空
     * @param updatedBy   操作者（REST 链路操作者上下文/MQ 链路 SYSTEM），非空
     * @return 影响行数（0=from 态失配——并发他方已迁移或行不存在）
     */
    @Update("UPDATE nursing.order_execution SET confirm_status = #{toStatus}, updated_by = #{updatedBy} "
            + "WHERE execution_no = #{executionNo} AND confirm_status = #{fromStatus} AND deleted = 0")
    int casMarkConfirmStatus(
            @Param("executionNo") String executionNo,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("updatedBy") String updatedBy);

    /**
     * 摆药签收批量 CAS（Task 5 DispenseSignoffListener）：按 m04_order_no 定位在途 CREATED
     * 药品执行单批量 SIGNED 并落签收时点。药品类谓词=exec_item_name='drug'——生成域快照
     * 契约（itemName=转抄类型子键，CREATED 行未回填前可靠）；SIGNED 已达行由 CREATED 谓词
     * 滤除（0 行自然幂等）。签收护士不落 executor_id（批次动作无单一主体，SYSTEM 触发面经
     * updated_by 审计承载）。status 字面量与 ExecutionStatus code 同源；deleted=0 显式补齐。
     *
     * @param m04OrderNo M04 医嘱号（批量定位键），非空；来源：dispense.completed 载荷
     * @param signedAt   签收时点（北京钟面 now），非空
     * @param updatedBy  操作者（MQ 链路 SYSTEM 桥接），非空
     * @return 影响行数（0=无在途 CREATED 药品单/重复投递已签收——幂等达成）
     */
    @Update("UPDATE nursing.order_execution SET status = 'SIGNED', signed_at = #{signedAt}, "
            + "updated_by = #{updatedBy} WHERE m04_order_no = #{m04OrderNo} AND status = 'CREATED' "
            + "AND exec_item_name = 'drug' AND deleted = 0")
    int casSignReceiveBatchByOrder(
            @Param("m04OrderNo") String m04OrderNo,
            @Param("signedAt") OffsetDateTime signedAt,
            @Param("updatedBy") String updatedBy);

    /**
     * PIVAS 输液升格批量 CAS（Task 5 DispenseSignoffListener）：该医嘱已签收（SIGNED）的
     * GENERIC 执行单批量升格 INFUSION（静脉判定=dispenseType=INPATIENT_PIVA，M04 载荷无
     * 用法字段、升格锚归摆药面）。GENERIC 谓词承载幂等（重复投递 0 行）；升格后建链取数经
     * 升格行查询（execution_type='INFUSION'）承载，两次投递合流至同一批行。execution_type
     * 字面量与 ExecutionType code 同源；deleted=0 显式补齐。
     *
     * @param m04OrderNo M04 医嘱号（批量定位键），非空；来源：dispense.completed 载荷
     * @param updatedBy  操作者（MQ 链路 SYSTEM 桥接），非空
     * @return 影响行数（0=非 PIVAS 载荷不调用/已升格幂等——幂等达成）
     */
    @Update("UPDATE nursing.order_execution SET execution_type = 'INFUSION', "
            + "updated_by = #{updatedBy} WHERE m04_order_no = #{m04OrderNo} AND status = 'SIGNED' "
            + "AND execution_type = 'GENERIC' AND deleted = 0")
    int casUpgradeInfusionByOrder(@Param("m04OrderNo") String m04OrderNo, @Param("updatedBy") String updatedBy);
}
