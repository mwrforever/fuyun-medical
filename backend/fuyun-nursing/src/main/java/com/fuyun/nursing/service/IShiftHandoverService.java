package com.fuyun.nursing.service;

import com.fuyun.nursing.dto.HandoverCompleteRequest;
import com.fuyun.nursing.dto.HandoverGenerateRequest;
import com.fuyun.nursing.vo.ShiftHandoverVO;
import java.time.LocalDate;
import java.util.List;

/**
 * 交接班域服务（V807 shift_handover 业务面，SBAR 结构化交接班）。generate 自动汇总本班业务
 * 数据生成草稿并盖章交班签名；complete 双签落定并发布 nursing.shift.completed（M19 工作量
 * 统计消费）；未完成不阻塞业务（DRAFT 草稿无任何副作用）。
 *
 * <p>线程安全：无状态 singleton；写操作 @Transactional 收口（实现侧）。
 */
public interface IShiftHandoverService {

    /**
     * 交接班单生成（Spec :138 流程 5）：病区配置校验（无配置行 NS-1016 未知病区——wardConfig
     * 冻结面实况）→ 在区患者视图汇总患者摘要（总数/护理级别分布/病情标记计数）→ 逐患者在途
     * 任务收集待续事项（在途输注/未闭环告警 P1 空数组，M14/M16 缺位注记）→ SBAR 初稿文本
     * 拼装（中文模板，含计数与危重患者床位号列表）→ 发号 HO → insert（status=DRAFT、
     * outgoingSignedAt=生成时刻、outgoingNurseId=当前操作者——双签同刻口径的交班侧）。
     *
     * @param req 生成入参，非空；来源：操作者工作站表单
     * @return 交接班出参（DRAFT 态），非空
     * @throws BizException NS-1016（409 未知病区无配置行 / 交接班单号唯一冲突幂等拒绝）
     */
    ShiftHandoverVO generate(HandoverGenerateRequest req);

    /**
     * 交接班完成（DRAFT/SIGNING → COMPLETED，双签落定）：CAS 单语句（GC26，0 行 → NS-1013
     * 不存在或已完成）→ 接班签名盖章（incomingSignedAt=完成时刻、incomingNurseId=入参——
     * 双签同刻口径的接班侧）→ SBAR 四段空串保留初稿（COALESCE(NULLIF)）→ 事务内发布
     * nursing.shift.completed（载荷 handoverNo/wardId/shiftCode/outgoingNurseId/incomingNurseId）。
     *
     * @param handoverNo 交接班单业务号，非空；来源：路径参数
     * @param req        完成入参（接班护士必填，SBAR 四段可空），非空；来源：操作者补充确认
     * @return 完成后交接班出参，非空
     * @throws BizException NS-1013（409 单不存在或已完成，禁止重复完成）/
     *                      NS-1016（409 CAS 命中后行被并发逻辑删，回读缺失）
     */
    ShiftHandoverVO complete(String handoverNo, HandoverCompleteRequest req);

    /**
     * 病区交接班清单（按日检索，班次升序）：handover_date 精确匹配当日，DB 侧按 shift_code
     * 升序（同日三班交接顺序展示依据）。
     *
     * @param wardId 病区编码，非空；来源：查询参数
     * @param date   交接班日期，非空；来源：查询参数（缺省当日）
     * @return 交接班出参清单（无行返回空清单，非 null）；按班次升序
     */
    List<ShiftHandoverVO> listByWard(String wardId, LocalDate date);
}
