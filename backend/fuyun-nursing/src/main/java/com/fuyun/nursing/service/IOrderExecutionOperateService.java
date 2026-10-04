package com.fuyun.nursing.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.web.PageResult;
import com.fuyun.nursing.dto.CancelExecutionRequest;
import com.fuyun.nursing.dto.CheckRequest;
import com.fuyun.nursing.dto.FinishRequest;
import com.fuyun.nursing.dto.NeedleOutRequest;
import com.fuyun.nursing.dto.OverrideCheckRequest;
import com.fuyun.nursing.dto.SignReceiveRequest;
import com.fuyun.nursing.dto.StartRequest;
import com.fuyun.nursing.entity.OrderExecution;
import com.fuyun.nursing.vo.OrderExecutionTraceVO;
import com.fuyun.nursing.vo.OrderExecutionVO;
import java.time.LocalDate;
import java.util.List;

/**
 * 医嘱执行单操作域服务（V1106 order_execution 业务面，Task 5）：五环节状态链操作
 * （补签收/三向核对/开始/完成/撤销）+ 工作台分组清单 + 在途占用 + 闭环追溯 + 破码放行
 * 双授权 + 摆药签收衔接（dispense 事件自动 SIGNED/PIVAS 升格）。
 *
 * <p>双路回签（GC17）：finish/输注中断 cancel 主路径=COMPLETED/CANCELLED 事务提交后进程内
 * 同步调 inpatient OrderExecutionConfirmPort.executeConfirm（失败置 confirm_status=
 * COMPENSATING 不抛出——床旁不阻塞；补偿扫描归 ExecutionConfirmCompensator，tick 接线
 * 归 Task 9）；辅路径=事务内发布 nursing.order-execution.completed（id 64 载荷，
 * AFTER_COMMIT 出 MQ）。回签双路仅对 m04PlanNo 非空行生效（长期计划拆分行——临时单
 * m04_plan_no NULL 无计划对账锚，差异注记见实现类注释）。
 *
 * <p>线程安全：无状态 singleton；写操作 @Transactional 收口（实现侧）。
 *
 * <p>配对纪律（宪法 A.4.3-20）：与生成域 {@link IOrderExecutionService} 共享
 * {@code ServiceImpl<OrderExecutionMapper, OrderExecution>} 基座（操作/生成分接口承载，
 * mapper 与主表单点配对）。
 */
public interface IOrderExecutionOperateService extends IService<OrderExecution> {

    /**
     * 执行工作台分组清单：病区+日期窗口（北京钟面 [00:00, 次日 00:00)）分页，shift 班次
     * 过滤按计划时间落班映射（DAY 08:00–16:00 / EVENING 16:00–24:00 / NIGHT 00:00–08:00，
     * 与 M04 计划 shift 落班窗口同源），计划时间升序。
     *
     * @param wardId 病区编码，非空；来源：查询参数（必填）
     * @param date   计划日期（可空=缺省北京钟面当日），可空；来源：查询参数
     * @param shift  班次过滤（DAY/EVENING/NIGHT，可空=全日），可空；来源：查询参数
     * @param status 状态过滤（ExecutionStatus code，可空），可空；来源：查询参数
     * @param page   页码（0 基），非负
     * @param size   单页条数，正数
     * @return 执行单分页出参（计划时间升序），非空
     * @throws com.fuyun.common.exception.BizException NS-1019（400 wardId 缺失/shift/status
     *                 code 非法）
     */
    PageResult<OrderExecutionVO> listWorkbench(
            String wardId, LocalDate date, String shift, String status, int page, int size);

    /**
     * 人工补签收（CREATED→SIGNED）：药品类主入口为 dispense 事件自动 SIGNED，本端点仅
     * 非药品类人工补签（非药品类可跳过签收直接核对——词表冻结口径）。
     *
     * @param executionNo 执行单号，非空；来源：路径参数
     * @param req         补签收入参（receivedNote 审计留痕不落表），非空
     * @return 签收后执行单出参，非空
     * @throws com.fuyun.common.exception.BizException NS-1020（404 执行单不存在）/ NS-1019
     *                 （400 操作者上下文缺失或非数字）/ NS-1021（409 非 CREATED 态）
     */
    OrderExecutionVO signReceive(String executionNo, SignReceiveRequest req);

    /**
     * 三向扫码核对（PASS→CHECKED，dispatch §3 冻结维度）：按 codeType 单维核对——
     * 腕带=visitId 匹配、瓶签=infusion_monitor_link.bag_label_code 匹配、执行单=路径
     * execution_no 匹配。PASS 落 SIGNED→CHECKED CAS（CREATED 直核合同承载）+ 流水落行；
     * FAIL 落流水行（fail_type 判定）不迁移状态并 NS-1022 拒绝。
     *
     * @param executionNo 执行单号，非空；来源：路径参数
     * @param req         核对入参（code 扫码原文 + codeType 核对方式），非空
     * @return 核对通过后执行单出参，非空
     * @throws com.fuyun.common.exception.BizException NS-1020（404）/ NS-1019（400 codeType
     *                 词表外/操作者上下文非法）/ NS-1022（409 核对不匹配——fail_type 落行）/
     *                 NS-1021（409 PASS 后状态前置不满足）
     */
    OrderExecutionVO check(String executionNo, CheckRequest req);

    /**
     * 开始执行（CHECKED→EXECUTING）：时间窗校验（计划时间 ± ward_config.
     * execute_time_window_minutes，缺省 30）——窗外且未破码放行（行 override_flag 或请求
     * overrideTimeWindow 三源合流）NS-1027 拒绝。执行护士一律令牌身份落库（W-72——请求体
     * executorId 兼容保留忽略）。INFUSION 型建/激活监测挂接与 infusion.started 事件归
     * Task 6 在本方法 CAS 成功后段扩展。
     *
     * @param executionNo 执行单号，非空；来源：路径参数
     * @param req         开始入参（executorId 兼容保留忽略/deviceId 可空/overrideTimeWindow 可空），非空
     * @return 开始后执行单出参，非空
     * @throws com.fuyun.common.exception.BizException NS-1020（404）/ NS-1027（409 计划时间
     *                 窗外未破码）/ NS-1021（409 非 CHECKED 态）/ NS-1019（400 操作者上下文
     *                 缺失或非数字——令牌身份定位失败 fail-closed）
     */
    OrderExecutionVO start(String executionNo, StartRequest req);

    /**
     * 执行完成（EXECUTING→COMPLETED + 双路回签）：事务内发布 nursing.order-execution.
     * completed（辅路径）+ 事务提交后进程内调回签端口（主路径，失败置 COMPENSATING 不抛出）。
     * 回执与回签执行人一律令牌身份（W-72——请求体 executorId 兼容保留忽略）。INFUSION 型
     * 完成由 Task 6 拔针端点承接。
     *
     * @param executionNo 执行单号，非空；来源：路径参数
     * @param req         完成入参（executorId 兼容保留忽略/routeCheckResult 可空回签透传），非空
     * @return 完成后执行单出参，非空
     * @throws com.fuyun.common.exception.BizException NS-1020（404）/ NS-1021（409 非
     *                 EXECUTING 态）/ NS-1019（400 操作者上下文缺失或非数字）
     */
    OrderExecutionVO finish(String executionNo, FinishRequest req);

    /**
     * 输液拔针（Task 6 / FU-M05-06，INFUSION 型 finish 承接端点）：腕带三向核对（visitId
     * 匹配，FAIL NS-1022 流水落行）→ 实际输注量守卫（0~5000 越界 NS-1019）→ EXECUTING→
     * COMPLETED CAS（needle_out_at/finished_at 同刻）→ 挂接收口 ENDED（非 MONITORING
     * NS-1024）→ 自动入量行（INFUSION_AUTO/IV_FLUID，quantity=actualVolumeMl）→ 事务内
     * 发布 nursing.infusion.completed（id 63，iot 停监测/ward 呼叫复位消费）→ 回签同 finish
     * 双路（辅路径 id 64 事件 + 主路径事务提交后回签端口）。全链留痕人一律令牌身份（W-72
     * ——请求体 executorId 兼容保留忽略）。
     *
     * @param executionNo 执行单号，非空；来源：路径参数
     * @param req         拔针入参（executorId 兼容保留忽略/actualVolumeMl 必填 0~5000/wristbandCode 必填），非空
     * @return 拔针后执行单出参
     * @throws com.fuyun.common.exception.BizException NS-1020（404）/ NS-1021（409 GENERIC 型
     *                 走 finish 端点/非 EXECUTING 态）/ NS-1022（409 腕带核对不符，FAIL 流水落行）/
     *                 NS-1019（400 实际输注量越界/操作者上下文缺失或非数字）/ NS-1024（409 无在途
     *                 输注监测挂接）/ NS-1004（409 患者不在区——自动入量守卫，事务整体回滚）
     */
    OrderExecutionVO needleOut(String executionNo, NeedleOutRequest req);

    /**
     * 执行单撤销（两分支）：未执行三态（CREATED/SIGNED/CHECKED）→CANCELLED 原因留痕；
     * EXECUTING→CANCELLED 仅 INFUSION 型输注中断（护士长权限近似=操作者角色 ∈
     * override_roles，RBAC 归 PR-4 W-37），落 finished_at 并按部分执行回签（双路，携
     * actualVolumeMl 时回签 routeCheckResult 留痕实际入量）。
     *
     * @param executionNo 执行单号，非空；来源：路径参数
     * @param req         撤销入参（reason 必填/actualVolumeMl 可空），非空
     * @return 撤销后执行单出参，非空
     * @throws com.fuyun.common.exception.BizException NS-1020（404）/ NS-1021（409 GENERIC
     *                 型 EXECUTING 不可撤销/状态前置不满足）/ NS-1023（409 输注中断权限不符）
     */
    OrderExecutionVO cancel(String executionNo, CancelExecutionRequest req);

    /**
     * 在途执行单占用清单（M13 退费前置校验消费）：在途=非终态（CREATED/SIGNED/CHECKED/
     * EXECUTING）行集。
     *
     * @param patientId  患者主索引（与 m04OrderNo 至少一项），可空；来源：查询参数
     * @param m04OrderNo M04 医嘱号（与 patientId 至少一项），可空；来源：查询参数
     * @return 在途执行单出参清单（计划时间升序），非空
     * @throws com.fuyun.common.exception.BizException NS-1019（400 两过滤键均缺失）
     */
    List<OrderExecutionVO> occupancy(Long patientId, String m04OrderNo);

    /**
     * 单条执行单闭环追溯：五环节时点 + 扫码核对流水清单（升序，含 OVERRIDE 行）+ 关联
     * 告警号一屏聚合。
     *
     * @param executionNo 执行单号，非空；来源：路径参数
     * @return 追溯聚合出参，非空
     * @throws com.fuyun.common.exception.BizException NS-1020（404 执行单不存在）
     */
    OrderExecutionTraceVO trace(String executionNo);

    /**
     * 破码放行双授权（pda/override-check）：两人不同（W-72/A-4：服务端比较令牌身份 vs 第二
     * 授权人——请求体 primaryAuthorizerId 兼容保留忽略）+ 操作者角色 ∈ override_roles（第二
     * 授权人角色面无 system 查询 api——当前操作者角色近似 + 审计留痕降级）；通过→
     * override_flag=true + OVERRIDE 流水落行（operator_id=令牌身份），放行后续 start 跳过
     * 时间窗校验。
     *
     * @param req 放行入参（executionNo/双授权人/理由），非空
     * @return 放行后执行单出参，非空
     * @throws com.fuyun.common.exception.BizException NS-1020（404）/ NS-1023（409 双授权
     *                 同一人或角色不在 override_roles）/ NS-1019（400 操作者上下文缺失或非数字）
     */
    OrderExecutionVO overrideCheck(OverrideCheckRequest req);

    /**
     * 摆药签收衔接（DispenseSignoffListener 消费体，pharmacy.dispense.completed）：按
     * m04_order_no 批量签收在途 CREATED 药品执行单（signed_at=now）；PIVAS 载荷
     * （dispenseType=INPATIENT_PIVA）升格 INFUSION 并建 infusion_monitor_link（bag_label_code
     * 承载）。幂等=SIGNED/INFUSION 已达零行更新自然幂等 + 挂接行 ON CONFLICT DO NOTHING。
     *
     * @param m04OrderNo     M04 医嘱号（住院行定位键），非空；来源：dispense.completed 载荷
     * @param dispenseType   发药类型（DispenseType code——INPATIENT_PIVA 触发升格），非空；
     *                       来源：dispense.completed 载荷
     * @param dispensePlanNo 摆药计划号（日志引用留痕——V1106 无承载列），非空；来源：dispense.completed 载荷
     * @param bagLabelCode   输液袋标签码（traceCodes 首位/行摘要回退），非空；来源：dispense.completed 载荷
     */
    void onDispenseCompleted(String m04OrderNo, String dispenseType, String dispensePlanNo, String bagLabelCode);
}
