package com.fuyun.pharmacy.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.dto.DispensePlanGenerateRequest;
import com.fuyun.pharmacy.dto.DispenseReturnRequest;
import com.fuyun.pharmacy.entity.DispensePlan;
import com.fuyun.pharmacy.vo.DispensePlanLabelVO;
import com.fuyun.pharmacy.vo.DispensePlanVO;
import java.util.List;

/**
 * 住院摆药计划服务（FU-M06-05，P2 PR-3 Task 8）：计划生成（APPROVED 前置+长期频次分解+
 * plan_type 判定+uk 幂等）、摆药流五步（pick 摆药/verify 核对/issue 出库落 dispense 行与
 * 库存扣减/deliver 配送交接半步/receive 病区签收 CAS+事务内发布 pharmacy.dispense.completed
 * 住院四字段全量载荷）、查询面（分页+PIVAS 贴签数据面）、住院退药（DELIVERED→PART/
 * FULL_RETURNED+批次回补+returned 事件）与终清联动作废（停嘱/出院两路）。
 * 配对纪律（宪法 A.4.3-20）：本服务以 dispense_plan 为主表（实现侧已 extends
 * ServiceImpl&lt;DispensePlanMapper, DispensePlan&gt;），接口侧对应 extends
 * IService&lt;DispensePlan&gt;。状态迁移一律走自有 CAS/编排方法（PH-1024 定性拒绝），
 * 禁经 IService 通用写面绕开并发守卫；作废消费位（casCancel）0 行为幂等跳过面不上抛。
 */
public interface IDispensePlanService extends IService<DispensePlan> {

    /**
     * 生成摆药计划（POST /dispense-plans/generate）：前置校验 order_medication 快照存在且
     * review_task=APPROVED（否则 PH-1025）；长期医嘱按 freq_code 给药时点分解为次日逐时点
     * 计划（qd/bid/tid/qid/qn 与 M04 order_frequency 种子时点逐字同源——pharmacy 禁读
     * inpatient 字典表，内置映射承载）；临时/按需/无固定时点频次（null/st/prn/未知）生成
     * 单次即刻计划，同医嘱已有计划行不再新建（显式请求幂等锚）；plan_type 判定=任一行用法
     * 含静脉或 PIVAS 标记→PIVAS、否则任一行含口服→SINGLE_DOSE、其余 WHOLE；uk_dispense_plan_
     * order_time（m04_order_no+plan_time）承载重复生成幂等（重复时点跳过）。
     *
     * @param req 生成入参（医嘱号+目标病区），非空；wardId 显式入参裁决见 DTO javadoc
     * @return 该医嘱全部未删计划（新建+既有，planTime 升序；重复调用输出稳定幂等），非空
     * @throws BizException PH-1025（400 医嘱非住院来源或未审方通过——快照缺失/任务非 APPROVED）
     */
    List<DispensePlanVO> generate(DispensePlanGenerateRequest req);

    /**
     * 摆药开始（CREATED→PICKING，POST /dispense-plans/{no}/pick）：摆药师留痕随 CAS 落行；
     * 库存充足性预校验（FEFO 只读选批，缺量 PH-1010 整事务回滚——真锁定与扣减在 issue
     * 同事务三连承载，无中间载体列可用）；PIVAS 链生成排批号（DPB+yyyyMMdd+3 位）回填。
     *
     * @param planNo 摆药计划号（uk 唯一），非空；来源：摆药工作台计划清单
     * @throws BizException PH-1023（404 计划缺单）/ PH-1024（409 状态违例或 CAS 并发被抢）/
     *                      PH-1001（404 药品字典无对照）/ PH-1010（409 库存预校验缺量）/
     *                      PH-1016（400 操作者标识非数字）
     */
    void pick(String planNo);

    /**
     * 药师核对（PICKING→PICKED，POST /dispense-plans/{no}/verify）：双人核对第二签留痕
     * （核对人≠摆药师，同人 PH-1011）；PIVAS 链=贴签核对（label_printed 置 true——打印降级
     * 注记：贴签内容经 GET /dispense-plans/{no}/label 出数据面，打印链路归 M01）。
     *
     * @param planNo 摆药计划号，非空
     * @throws BizException PH-1023（404 缺单）/ PH-1011（409 核对与摆药同人双签）/
     *                      PH-1024（409 状态违例或 CAS 并发被抢）/ PH-1016（400 操作者标识非数字）
     */
    void verify(String planNo);

    /**
     * 出库交接（PICKED→CHECKED，POST /dispense-plans/{no}/issue）：落 dispense 调剂行
     * （dispense_type 按 plan_type 映射 SINGLE_DOSE/WHOLE→INPATIENT_DOSE、PIVAS→INPATIENT_PIVA；
     * 住院四列 ward_id/m04_order_no/dispense_plan_no 填充+dispense_type 映射，rx_no 列承载
     * 计划号可读锚、prescription_id 落 0 占位）+ dispense_item 明细行（批次效期回填）+
     * stock_ledger 扣减（选批-锁定-扣减同事务三连，0 行 PH-1010 整事务回滚）。
     *
     * @param planNo 摆药计划号，非空
     * @throws BizException PH-1023（404 缺单）/ PH-1024（409 状态违例/双签重申失败/CAS 被抢）/
     *                      PH-1001（404 药品字典无对照）/ PH-1010（409 批次不足或锁定扣减 0 行）
     */
    void issue(String planNo);

    /**
     * 配送交接（CHECKED 态内 issued_at 时间线半步，POST /dispense-plans/{no}/deliver）：
     * Spec 状态机 CHECKED→DELIVERED 直迁——本端点不迁移状态（测试钉死 deliver 后仍 CHECKED），
     * 仅置 issued_at 配送交接时点；carrier 无落列载体以日志留痕承载；为 receive 的必要前置
     * （未配送 PH-1026 不可签收）。
     *
     * @param planNo  摆药计划号，非空
     * @param carrier 配送人/载体说明，可空 ≤64；来源：药房配送交接登记
     * @throws BizException PH-1023（404 缺单）/ PH-1024（409 非 CHECKED 态或 CAS 并发被抢）
     */
    void deliver(String planNo, String carrier);

    /**
     * 病区签收（CHECKED→DELIVERED CAS，POST /dispense-plans/{no}/receive）：未配送
     * （issued_at 缺位）PH-1026 拒；签收人/时点随 CAS 落行；dispense 调剂行同步
     * CHECKED→DELIVERED；事务内发布 pharmacy.dispense.completed（id 28 + V1111 扩展文本
     * 全量：dispenseNo/dispenseType=INPATIENT_DOSE 或 INPATIENT_PIVA/prescriptionId=null/
     * rxNo=null/lines[]+
     * m04OrderNo/visitId/wardId/dispensePlanNo——M05 签收衔接（nursing DispenseSignoffListener）
     * 与 M13 占用消费）。
     * W-72：签收人=令牌身份（服务端强制落值），请求体 receivedBy 仅为兼容保留、服务端不消费。
     *
     * @param planNo 摆药计划号，非空
     * @throws BizException PH-1023（404 计划缺单）/ PH-1026（409 未配送不可签收）/
     *                      PH-1024（409 状态违例或 CAS 并发被抢）/ PH-1008（404 计划已出库但
     *                      调剂行缺行——数据不一致面）/ PH-1009（409 调剂行状态同步并发被抢）/
     *                      PH-1016（400 操作者标识缺失或非数字）
     */
    void receive(String planNo);

    /**
     * 计划分页查询（GET /dispense-plans?m04OrderNo=&amp;wardId=&amp;status=）：病区工作台
     * 高频过滤面（idx_dispense_plan_ward_status 承载）。
     *
     * @param m04OrderNo 住院医嘱号过滤，可空（空=不过滤）
     * @param wardId     病区编码过滤，可空
     * @param status     计划状态过滤（DispensePlan 状态词表 code），可空
     * @param page       页码（0 基）
     * @param size       页大小
     * @return 分页结果（planTime 升序），非空
     */
    PageResult<DispensePlanVO> page(String m04OrderNo, String wardId, String status, int page, int size);

    /**
     * PIVAS 贴签数据面（GET /dispense-plans/{no}/label）：患者脱敏展示名/病区/排批/调配核对
     * 双人/药品明细——打印归 M01 降级注记（本端点仅数据面）；非 PIVAS 计划无贴签面 PH-1024。
     * 床位无 pharmacy 侧数据源（bedNo 恒 null，衔接注记）。
     *
     * @param planNo 摆药计划号，非空
     * @return 贴签数据面，非空
     * @throws BizException PH-1023（404 缺单）/ PH-1024（409 非 PIVAS 链无贴签数据面）
     */
    DispensePlanLabelVO label(String planNo);

    /**
     * 住院退药受理（POST /dispense-returns 住院形态，req.dispensePlanNo 非空分流）：
     * DELIVERED 态调剂行逐行退（数量≤可退余额 PH-1013、追溯码核验 PH-1012 防回流）→
     * 批次回补 + RETURN_RESTOCK 回补流水同事务 → 调剂行 DELIVERED→PART/FULL_RETURNED →
     * 事务内发布 pharmacy.dispense.returned（id 29 冻结载荷，住院行 prescriptionId/rxNo
     * 承载 null）；计划行状态不迁（V1110 词表无退药态——退药态由调剂行承载）。
     * 病区退药开关校验归 nursing 侧发起端前置（PDA/护士站前端调 nursing 校验端点）——
     * pharmacy 侧不读 nursing_ward_config（跨模块读表禁止，衔接面注记）。
     *
     * @param req 退药受理入参（dispensePlanNo+returnLines[] 住院形态），非空
     * @throws BizException PH-1023（404 计划缺单）/ PH-1008（404 调剂行缺单）/
     *                      PH-1013（409 非 DELIVERED 可退态/缺行/超可退余额/回补 0 行）/
     *                      PH-1012（409 追溯码不一致防回流拒）/ PH-1016（400 退药数量非数字串）
     */
    void acceptInpatientReturn(DispenseReturnRequest req);

    /**
     * 停嘱联动作废（inpatient.order.stopped 消费业务）：该医嘱未摆药计划
     * （CREATED/PICKING）逐行 CANCELLED（cancel_reason=停嘱原因留痕）；CAS 0 行=已终态
     * 幂等跳过（消费位纪律：定性跳过不上抛）。
     *
     * @param m04OrderNo 住院医嘱号，非空；来源：inpatient.order.stopped 载荷（V800 id 44 冻结）
     * @param stopReason 停嘱原因（作废留痕），非空；来源：同上载荷
     */
    void cancelByOrderTerminal(String m04OrderNo, String stopReason);

    /**
     * 出院终清联动作废（inpatient.visit.discharged 消费业务）：该就诊未摆药计划
     * （CREATED/PICKING）同款 CANCELLED；已摆未用计划（PICKED/CHECKED/DELIVERED）不作废
     * ——warn 留痕提示退药人工发起（不自动回补）。
     *
     * @param visitId 住院就诊号（I 型 14 位），非空；来源：inpatient.visit.discharged 载荷
     *                （V800 id 51 冻结）
     */
    void cancelByVisitDischarge(String visitId);
}
