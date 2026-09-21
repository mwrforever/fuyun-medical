package com.fuyun.pharmacy.service.impl;

import com.fuyun.pharmacy.api.PrescriptionCancelPort;
import com.fuyun.pharmacy.service.IPrescriptionService;
import lombok.extern.slf4j.Slf4j;

/**
 * 处方作废端口实现（PrescriptionCancelPort 唯一实现，装配归 PharmacyWebConfig @Import）：纯转调
 * 既有 IPrescriptionService.cancel 主链（状态守卫/费用联动/事件发布零旁路，禁第二套作废逻辑）。
 * REQUIRED 传播加入调用方事务；业务拒绝（PH-1004/1005/1014）原样上抛，引导退费链语义不变。
 * 线程安全：无状态单例。
 */
@Slf4j
public class PrescriptionCancelPortImpl implements PrescriptionCancelPort {

    private final IPrescriptionService prescriptionService;

    /**
     * 全参构造器（装配归 PharmacyWebConfig @Import，backend 宪法 B.1）。
     *
     * @param prescriptionService 处方服务（既有作废主链唯一权威源），非空；cancel 承担状态守卫与费用联动
     */
    public PrescriptionCancelPortImpl(IPrescriptionService prescriptionService) {
        this.prescriptionService = prescriptionService;
    }

    /**
     * 作废转调（异常原样上抛不吞）：跨模块调用节点 info 留痕（含 rxNo 业务锚点），转调失败时本方法
     * 无返回路径、异常携带 PH 码直出。
     *
     * @param rxNo   处方号，非空
     * @param reason 作废原因，非空白
     * @throws BizException PH-1004/PH-1005/PH-1014 既有语义原样透传（见接口 javadoc）
     */
    @Override
    public void cancel(String rxNo, String reason) {
        // 数据库写操作（经 cancel 主链，同事务 REQUIRED 传播）：未缴费作废+PENDING 费用行联动
        prescriptionService.cancel(rxNo, reason);
        log.info("处方作废端口转调完成：rxNo={}", rxNo);
    }
}
