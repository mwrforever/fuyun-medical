package com.fuyun.inpatient.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.dto.TransferCheckRequest;
import com.fuyun.inpatient.vo.OrderPlanVO;
import com.fuyun.inpatient.vo.TransferWorklistVO;
import java.time.LocalDate;
import java.util.List;

/**
 * 医嘱转抄与执行计划服务（FU-M04-06 上，V906 两表业务面）。四能力面 + 转科钩子：
 * ① 转抄工作台（待转抄列表按病区/班次聚合）；② 批量转抄核对（AUDITED→TRANSFERRED +
 * 台账 + transferred 事件 + 临时医嘱同步单次计划，双人核对强制高危/输血类第二核对人）；
 * ③ 执行计划查询（按日期/病区分页）；④ 嘱托按需触发单次计划（多次触发多次台账）；
 * ⑤ 转科编排计划三分钩子（临时 PENDING 保留随患者重定向病区、长期 PENDING 作废——
 * TransferServiceImpl 阶段②回接面）。一切医嘱状态迁移唯一经 OrderStateMachineService
 * （GC17）；事件事务内发布 AFTER_COMMIT 出 MQ（GC8）。
 */
public interface OrderTransferService {

    /**
     * 转抄工作台待转抄列表：病区内在院就诊（ADMITTED）的 AUDITED 医嘱聚合，开立时间倒序；
     * 班次过滤按开立时点落班（V801 班次定义窗口：DAY 08:00–16:00/EVENING 16:00–24:00/
     * NIGHT 00:00–08:00，窗口左闭右开）。
     *
     * @param wardId 病区编码（必填过滤键），非空；来源：护士站病区上下文
     * @param shift  班次过滤（DAY/EVENING/NIGHT；可空=全部班次），可空；来源：工作台班次切换
     * @param page   页码（0 基），非负
     * @param size   单页条数，正
     * @return 待转抄医嘱分页出参，非空
     * @throws com.fuyun.common.exception.BizException IP-1022 病区缺失/班次词表外
     */
    PageResult<TransferWorklistVO> worklist(String wardId, String shift, int page, int size);

    /**
     * 批量转抄核对：整批单事务——逐条守卫（定位 IP-1009/状态 IP-1010/高危缺第二核对人
     * IP-1016/结论 REJECTED 拦截 IP-1016）→ 状态机迁移 AUDITED→TRANSFERRED（自动留痕）→
     * 转抄台账落行（转抄护士/时点/结论/第二核对人）→ 事务内发布 inpatient.order.transferred
     * （V800 id 42 载荷）→ 临时医嘱（STAT）同步按明细行生成单次执行计划（plan_time=转抄
     * 时点+默认准备窗口，plan_no=PL 流水）。已 TRANSFERRED 医嘱幂等跳过（零副作用）；
     * 任一条守卫不过整批回滚。
     *
     * @param req 批量转抄核对入参，非空；来源：POST /api/v1/inpatient/orders/transfer-check
     * @throws com.fuyun.common.exception.BizException IP-1009/IP-1010/IP-1016/IP-1022/IP-1023
     *                 （词表外医嘱类型脏数据/IP-1007 就诊关联缺失）
     */
    void transferCheck(TransferCheckRequest req);

    /**
     * 执行计划分页查询：按计划日期（plan_time 当日窗口）过滤，病区可叠加，计划时点升序；
     * 行级关联号映射批量承载（医嘱号/就诊号，免行级 N+1）。
     *
     * @param date   计划日期（必填过滤键——计划视图以日为轴），非空；来源：查询参数
     * @param wardId 病区过滤（可空=全部病区），可空；来源：查询参数
     * @param page   页码（0 基），非负
     * @param size   单页条数，正
     * @return 执行计划分页出参，非空
     * @throws com.fuyun.common.exception.BizException IP-1022 日期缺失；IP-1009/IP-1007 关联行缺失（数据不一致）
     */
    PageResult<OrderPlanVO> listPlans(LocalDate date, String wardId, int page, int size);

    /**
     * 嘱托按需触发单次计划：长期备用嘱（standby_flag=true）按明细行生成当次执行计划实例
     * （plan_time=触发时点+默认准备窗口，plan_no=PL 流水，多次触发多次台账各不相同——
     * 不重复计价由 M13 唯一键兜底）；医嘱头状态不迁移（回签面推进，W-33 契约归 Task 8）。
     *
     * @param orderNo 嘱托医嘱号，非空；来源：POST /api/v1/inpatient/order-plans/standby-trigger
     * @return 当次生成的计划出参集（按明细行一至多条），非空
     * @throws com.fuyun.common.exception.BizException IP-1009 医嘱不存在/IP-1022 非嘱托医嘱/
     *                 IP-1010 状态不经转抄/IP-1023 并发重复生成（uk_plan_order_item_time 冲突）/
     *                 IP-1007 就诊关联缺失
     */
    List<OrderPlanVO> standbyTrigger(String orderNo);

    /**
     * 转科编排计划三分钩子（TransferServiceImpl 阶段②回接面，编排事务内调用）：临时
     * （STAT）医嘱 PENDING 计划保留随患者——ward_id 批量重定向至目标病区；长期（LONG）
     * 医嘱 PENDING 计划批量作废（停嘱联动 cancelFuturePlans 仅覆盖停嘱时点后的未来计划，
     * 本面补齐长期在途全量 PENDING 的转科截断——Task 4 javadoc 冻结口径）。医嘱停嘱与
     * 费用切分不在本面（①承载/M13 日切）。
     *
     * @param visitId 住院就诊主键（inpatient_visit.id），非空；来源：转科编排上下文
     * @param toWardId 目标病区编码，非空；来源：转科入参
     * @param operator 操作者（审计留痕），非空；来源：转科编排操作者
     */
    void redirectPlansOnWardTransfer(Long visitId, String toWardId, String operator);
}
