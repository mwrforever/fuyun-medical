package com.fuyun.nursing.service;

import com.fuyun.nursing.vo.RoutineTaskGenerateVO;
import java.time.LocalDate;

/**
 * 常规模板批量生成服务（P2 PR-3 Task 9，M05 FU-M05-07 任务工作台面）：按
 * ward_config.routine_task_templates 模板（翻身 q2h/巡视等周期能力，V1107 JSONB 列契约）
 * + 当前在区患者投影（ward_patient deleted=0 即在区——W-34 退役后无 status 列）批量生成
 * 当日任务。幂等 = （templateCode+visitId+planTime）业务去重（nursing_task 无对应业务
 * uk——查重集合判重，勿加迁移）。调用入口：POST /api/v1/nursing/tasks/generate-routine
 * （任务工作台/病区管理触发；启动播种可选未落——端点触发已满足生成面，报告注记）。
 *
 * <p>线程安全：无状态 singleton；逐行独立落库（无外层事务——PG 约束冲突不毒化批次，
 * Task 4 终清零事务先例）。
 */
public interface IRoutineTaskGenerator {

    /**
     * 按病区常规模板批量生成任务：病区配置行定位（缺行 NS-1016）→ 模板 JSONB 解析（不合规
     * NS-1019）→ 在区患者投影读取 → 生成窗口切片（date 当日自 00:00 北京钟面起按
     * frequencyMinutes 步进切片，仅保留不早于当前时刻的「当日余下」片）→ 既有业务键行
     * （templateCode+visitId+planTime，Instant 归一）跳过 → 逐行落库（source=ROUTINE、
     * sourceRef=templateCode、PENDING 态、零事件发布——批量生成非任务生命周期动作，
     * 巡视打卡同款口径）。
     *
     * @param wardId 病区编码，非空；来源：生成请求（任务工作台触发）
     * @param date   生成日期，可空（空=北京钟面当日——HEALTHCARE_TZ 时区红线）
     * @return 生成结果（本轮实际新生成行数——幂等去重后），非空
     * @throws BizException NS-1016（404 侧语义 409：ward_config 无该病区配置行）/
     *                      NS-1019（400 模板 JSONB 不合规或模板字段非法——配置数据错误显式暴露）
     */
    RoutineTaskGenerateVO generateForWard(String wardId, LocalDate date);
}
