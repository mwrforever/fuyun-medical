package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.AdverseEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 不良事件 mapper（V1107 adverse_event）：单表链式能力 + 状态机三支 CAS 注解 SQL 全集
 * （GC26：@Update + 影响行数判定 + 显式 deleted=0——注解 SQL 不继承 @TableLogic）。
 * 处置（REPORTED→HANDLING，含超时 deadline_met=false 留痕）/关闭（HANDLING→CLOSED，
 * RCA 与整改措施随关闭落库）/退回（HANDLING→REPORTED，退回原因落处置记录）三支；
 * 零行=状态前置不满足，调用方 NS-1026 拒绝。tick 超时提醒扫描段只读不改状态（无 CAS 面）。
 * 必须标注 @Mapper 供 app 侧扫描。
 */
@Mapper
public interface AdverseEventMapper extends BaseMapper<AdverseEvent> {

    /**
     * 受理处置 CAS（handle 端点）：REPORTED→HANDLING 单行迁移并落处置责任人与处置记录。
     * handlingNote 可空（COALESCE 保留上报时初步处置记录）；deadlineMet 为服务层超时判定
     * 结果——I/II 级 REPORTED 态已超 24h 处置时置 false 留痕（非惩罚原则：超时只留痕不拒绝，
     * 不在谓词内拦截）；III/IV 级行 deadlineMet 传回原值（NULL 透传）。status 字面量与
     * AdverseEventStatus code 同源；deleted=0 显式补齐。
     *
     * @param eventNo     不良事件号（uk_adverse_event_no 定位），非空；来源：路径参数
     * @param handlerId   处置责任人员工 ID，非空；来源：请求体
     * @param handlingNote 处置记录（可空=保留上报时记录），可空；来源：请求体
     * @param deadlineMet 时限达成判定结果（超时置 false/其余透传行原值），非空限定布尔或 null 透传
     * @param updatedBy   操作者（审计留痕），非空
     * @return 影响行数（0=非 REPORTED 态——已处置/已关闭/已退回中，调用方 NS-1026 拒绝）
     */
    @Update("UPDATE nursing.adverse_event SET status = 'HANDLING', handler_id = #{handlerId}, "
            + "handling_note = COALESCE(#{handlingNote}, handling_note), deadline_met = #{deadlineMet}, "
            + "updated_by = #{updatedBy} WHERE event_no = #{eventNo} AND status = 'REPORTED' AND deleted = 0")
    int casHandle(
            @Param("eventNo") String eventNo,
            @Param("handlerId") long handlerId,
            @Param("handlingNote") String handlingNote,
            @Param("deadlineMet") Boolean deadlineMet,
            @Param("updatedBy") String updatedBy);

    /**
     * 关闭 CAS（close 端点）：HANDLING→CLOSED 单行迁移，RCA 根因分析与整改措施随关闭落库
     * （可空 COALESCE 保留行原值——退回重处链上补录不覆盖已录内容）。closedBy 动作主体经
     * updated_by 审计列承载（V1107 无独立 closed_by 列，服务层文本落值）。status 字面量与
     * AdverseEventStatus code 同源；deleted=0 显式补齐。
     *
     * @param eventNo         不良事件号，非空；来源：路径参数
     * @param rcaNote         根因分析记录（可空=保留原值），可空；来源：请求体
     * @param correctiveAction 整改措施（可空=保留原值），可空；来源：请求体
     * @param updatedBy       关闭动作主体（closedBy 文本承载，审计留痕），非空
     * @return 影响行数（0=非 HANDLING 态——未处置不可关闭/已关闭，调用方 NS-1026 拒绝）
     */
    @Update("UPDATE nursing.adverse_event SET status = 'CLOSED', "
            + "rca_note = COALESCE(#{rcaNote}, rca_note), corrective_action = COALESCE(#{correctiveAction}, corrective_action), "
            + "updated_by = #{updatedBy} WHERE event_no = #{eventNo} AND status = 'HANDLING' AND deleted = 0")
    int casClose(
            @Param("eventNo") String eventNo,
            @Param("rcaNote") String rcaNote,
            @Param("correctiveAction") String correctiveAction,
            @Param("updatedBy") String updatedBy);

    /**
     * 处置退回 CAS（return 端点）：HANDLING→REPORTED 侧支迁移，退回原因覆写处置记录
     * （必填承载——退回语义=处置不充分需补充，退回原因为最新处置面事实）。returnerId
     * 动作主体经 updated_by 审计列承载（V1107 无独立退回列）。退回后行回 REPORTED 态可
     * 再处置（重复退回/再处置由状态谓词天然承载）。status 字面量与 AdverseEventStatus
     * code 同源；deleted=0 显式补齐。
     *
     * @param eventNo   不良事件号，非空；来源：路径参数
     * @param reason    退回原因（必填留痕，覆写处置记录），非空；来源：请求体
     * @param updatedBy 退回动作主体（returnerId 文本承载，审计留痕），非空
     * @return 影响行数（0=非 HANDLING 态——未处置无退回面/已关闭，调用方 NS-1026 拒绝）
     */
    @Update("UPDATE nursing.adverse_event SET status = 'REPORTED', handling_note = #{reason}, "
            + "updated_by = #{updatedBy} WHERE event_no = #{eventNo} AND status = 'HANDLING' AND deleted = 0")
    int casReturn(
            @Param("eventNo") String eventNo, @Param("reason") String reason, @Param("updatedBy") String updatedBy);
}
