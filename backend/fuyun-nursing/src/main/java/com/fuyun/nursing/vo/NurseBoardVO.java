package com.fuyun.nursing.vo;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 护士站大屏快照出参（FU-M05-08，Task 11——前端 Task 17 消费契约冻结面）：<b>组件名逐字照
 * brief 冻结</b>（wardId/beds/overdueTasks/admissions/criticalValues/generatedAt 六段+嵌套组件名），
 * 任何组件改名均属前端契约变更，禁自行增删。
 *
 * <p>四段聚合来源（brief 冻结口径）：beds=病区患者投影行×护理级别×责任护士（assignments
 * 既有面）×风险标记（投影 risk_flags 展示镜像）；overdueTasks=逾期标记在途任务
 * （overdue_flag=true 且 status=PENDING，plan_time 升序有界）；admissions=近 24h 出入院动态
 * 投影时间线（ADMIT 取 admitted_at、DISCHARGE 取逻辑删行 updated_at 近似出院时点——投影表
 * 无出院时点列，派发上下文 §3 注记口径）；criticalValues=<b>固定空数组</b>（M07 检验危急值
 * 模块未建——缺位降级明示，M07 落地后回填，勿删组件）。
 *
 * <p>刷新契约：≤2s 面=WS 主通道（/topic/nursing/board/{wardId} 增量推送），本 REST 快照为
 * 首屏与断连兜底（前端 10s 轮询惯例）；服务端缓存 read-through
 * {@code fy:nursing:snapshot:board:{wardId}} TTL 5s（GC13 红线）。
 *
 * <p>脱敏口径：组件清单不含 patientName（brief 冻结面无该组件——床号墙以投影行+护理级别
 * 为主，禁自加敏感字段；确需展示名走 PatientNameQuery 脱敏，但组件清单冻结不加）。
 *
 * @param wardId         病区编码（M01 组织机构病区 code），非空
 * @param beds           床位总览墙行清单（床号升序），非空（无在册行返回空清单）
 * @param overdueTasks   任务逾期清单（计划时间升序有界），非空
 * @param admissions     近 24h 出入院动态时间线（时点降序有界），非空
 * @param criticalValues 危急值段——固定空数组（M07 缺位降级明示，组件占位待回填），非空
 * @param generatedAt    快照生成时点（北京钟面），非空
 */
public record NurseBoardVO(
        String wardId,
        List<BedRow> beds,
        List<OverdueTaskRow> overdueTasks,
        List<AdmissionRow> admissions,
        List<Object> criticalValues,
        OffsetDateTime generatedAt) {

    /** 出入院动态行类型：入科（brief 冻结词表 ADMIT/DISCHARGE） */
    public static final String TYPE_ADMIT = "ADMIT";

    /** 出入院动态行类型：出院 */
    public static final String TYPE_DISCHARGE = "DISCHARGE";

    /**
     * 床位总览墙行（brief 冻结七组件，禁改名）。
     *
     * @param bedNo        床位号（床号文本，admitted 空占位待 bed.changed 补齐），可空
     * @param visitId      住院就诊号（I 型 14 位），非空
     * @param patientId    患者主索引（全局 Long→String 序列化出网，A.3-8），非空
     * @param nursingLevel 护理级别 code（SPECIAL/CRITICAL/NORMAL），可空
     * @param admittedAt   入区时点，可空
     * @param assigneeName 责任护士（assignments 既有面承载 nurse_id 标识——nursing 无姓名解析
     *                     面以 M01 用户标识出网；BED 型管床匹配优先、PRIMARY 型责任组回退、
     *                     未指派为 null）
     * @param riskFlags    风险标记（逗号分隔展示镜像：FALL/PRESSURE，评估域 appendRiskFlag 回写）
     */
    public record BedRow(
            String bedNo,
            String visitId,
            Long patientId,
            String nursingLevel,
            OffsetDateTime admittedAt,
            String assigneeName,
            String riskFlags) {}

    /**
     * 任务逾期清单行（brief 冻结四组件，禁改名）。
     *
     * @param taskNo          任务业务号（TK+yyyyMMdd+5 位流水），非空
     * @param taskType        任务类型 code（TaskType 词表），非空
     * @param planTime        计划时间（逾期判定基准），非空
     * @param escalationCount 升级次数（1=责任护士档、2=护士长档封顶——tick 动作式递增）
     */
    public record OverdueTaskRow(String taskNo, String taskType, OffsetDateTime planTime, Integer escalationCount) {}

    /**
     * 出入院动态时间线行（brief 冻结四组件，禁改名）。
     *
     * @param visitId 住院就诊号，非空
     * @param bedNo   床位号（出院行取逻辑删前床号文本快照），可空
     * @param at      动态时点（ADMIT=admitted_at；DISCHARGE=updated_at 近似——投影表无出院时点
     *                列，逻辑删 UPDATE 触发器刷新值承载），非空
     * @param type    动态类型（ADMIT/DISCHARGE 冻结词表），非空
     */
    public record AdmissionRow(String visitId, String bedNo, OffsetDateTime at, String type) {}
}
