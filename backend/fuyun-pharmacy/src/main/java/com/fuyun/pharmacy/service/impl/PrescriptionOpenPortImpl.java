package com.fuyun.pharmacy.service.impl;

import com.fuyun.pharmacy.api.PrescriptionOpenCommand;
import com.fuyun.pharmacy.api.PrescriptionOpenPort;
import com.fuyun.pharmacy.api.PrescriptionOpenResult;
import com.fuyun.pharmacy.dto.PrescriptionCreateRequest;
import com.fuyun.pharmacy.dto.RxItemRequest;
import com.fuyun.pharmacy.service.IPrescriptionService;
import com.fuyun.pharmacy.vo.PrescriptionVO;
import lombok.extern.slf4j.Slf4j;

/**
 * 处方开立端口实现（PrescriptionOpenPort 唯一实现，装配归 PharmacyWebConfig @Import）：纯转调既有
 * IPrescriptionService.create 主链，禁第二套开方逻辑（OutpatientBillingPortImpl 先例同型）；命令
 * 对象↔dto 逐组件镜像映射（api 面禁外引 dto）。REQUIRED 传播加入 M03 调用方事务——开方与 RX_REF
 * 引用行一体成败（backend 宪法 B.2-4）。线程安全：无状态单例。
 */
@Slf4j
public class PrescriptionOpenPortImpl implements PrescriptionOpenPort {

    private final IPrescriptionService prescriptionService;

    /**
     * 全参构造器（装配归 PharmacyWebConfig @Import，backend 宪法 B.1）。
     *
     * @param prescriptionService 处方服务（既有开方主链唯一权威源），非空；create 承担预检/落库/事件发布
     */
    public PrescriptionOpenPortImpl(IPrescriptionService prescriptionService) {
        this.prescriptionService = prescriptionService;
    }

    /**
     * 开方转调：命令对象镜像映射进既有 create 主链（同事务），结果四组件映射返回——跨模块调用节点
     * info 留痕（含 rxNo 业务锚点）。
     *
     * @param cmd 开方命令，非空
     * @return 开方结果（rxNo/status/reviewLevel/skinTestRequired），非空
     * @throws BizException PH-* 既有码语义原样透传（转调不吞，见接口 javadoc）
     */
    @Override
    public PrescriptionOpenResult open(PrescriptionOpenCommand cmd) {
        // 数据库写操作（经 create 主链，同事务 REQUIRED 传播）：命令→dto 镜像映射（逐组件一一对应）
        PrescriptionVO vo = prescriptionService.create(new PrescriptionCreateRequest(
                cmd.patientId(),
                cmd.visitId(),
                cmd.rxType(),
                cmd.deptCode(),
                cmd.diagnosisCodes(),
                cmd.skinTestRequired(),
                cmd.items().stream()
                        .map(item -> new RxItemRequest(
                                item.drugId(),
                                item.quantity(),
                                item.unit(),
                                item.singleDose(),
                                item.routeCode(),
                                item.frequency(),
                                item.days(),
                                item.usageNote()))
                        .toList()));
        log.info("处方开立端口转调完成：rxNo={}，status={}，visitId={}", vo.rxNo(), vo.status(), cmd.visitId());
        return new PrescriptionOpenResult(vo.rxNo(), vo.status(), vo.reviewLevel(), vo.skinTestRequired());
    }
}
