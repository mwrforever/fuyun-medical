package com.fuyun.billing.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.billing.dto.FeeGenerateCommand;
import com.fuyun.billing.dto.ManualChargeRequest;
import com.fuyun.billing.dto.QuoteRequest;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.vo.QuoteVO;
import com.fuyun.common.web.PageResult;
import java.util.List;

/**
 * 计价引擎（M13 §3.1 计价核心，billing.fee_record 生成唯一入口）：
 * 事件驱动费用生成与手工计费共用同一入口（无第二套计价路径）、billing_key 防重、
 * 价格快照冻结、组合项目按构成展开、预计价只算不落。
 */
public interface IPricingEngineService extends IService<FeeRecord> {

    /**
     * 生成待收费费用（事件消费逐条与手工计费共同入口，调用方事务内执行）。
     *
     * @param cmd 费用生成命令，非空；来源：领域事件消费方组装 / manualCharge 薄编排组装
     * @return 新费用行 id（组合展开多行时为首成员行 id，全量行以费用查询为准）
     * @throws com.fuyun.common.exception.BizException BILL-1009（409 重复计费）/
     *                 BILL-1008（409 定价不可得：无生效价或组合未维护构成）/
     *                 BILL-1001（404 组合成员缺行）/ BILL-1012（400 手工要素缺失）
     */
    long generateFromSource(FeeGenerateCommand cmd);

    /**
     * 就诊未结算 PENDING 费用清单（Task 13 结算前勾稽 + 已收费用回显）。
     *
     * @param visitId CF-3 就诊号，非空
     * @return 未结算费用行清单，可为空清单（无待收费）
     */
    List<FeeRecord> pendingFees(String visitId);

    /**
     * 就诊费用分页查询（工作站费用查询 GET /fees 消费；全状态，id 升序=事件行序）。
     *
     * @param visitId CF-3 就诊号，非空
     * @param page    页码（0 基）
     * @param size    单页条数（1-200）
     * @return 费用行分页，非空；可为空清单页
     */
    PageResult<FeeRecord> pageByVisit(String visitId, int page, int size);

    /**
     * 手工计费（服务薄编排）：操作者恒取登录上下文（红线 3），组装命令委托统一生成入口。
     *
     * @param req 手工计费请求，非空；来源：收费员工作站补录
     * @return 新费用行 id（组合展开时为首成员行 id）
     * @throws com.fuyun.common.exception.BizException BILL-1012（400 无登录操作者上下文）；
     *                 余同 {@link #generateFromSource}
     */
    long manualCharge(ManualChargeRequest req);

    /**
     * 预计价（不落库、不发消息，供划价界面展示；无对照行显式标自费）。
     *
     * @param req 预计价请求，非空；来源：开单界面/收费处划价勾选
     * @return 预计价结果（金额分），非空
     * @throws com.fuyun.common.exception.BizException BILL-1001（404 项目缺行/停用）/
     *                 BILL-1008（409 无生效价或组合未维护构成）
     */
    QuoteVO quote(QuoteRequest req);

    /**
     * 费用作废（PENDING/CONFIRMED→CANCELLED，未结算更正通道；V602 部分唯一索引对 CANCELLED 行
     * 不占键，作废后同来源可重计；理由经 @AuditLog 留痕不另建列）。
     *
     * @param feeId  费用行 id；来源：工作站费用查询选行
     * @param reason 作废理由，非空白；仅作日志/审计锚点
     * @throws com.fuyun.common.exception.BizException BILL-1010（404 费用不存在）/
     *                 BILL-1011（409 非 PENDING/CONFIRMED 态或状态竞态）
     */
    void cancel(long feeId, String reason);
}
