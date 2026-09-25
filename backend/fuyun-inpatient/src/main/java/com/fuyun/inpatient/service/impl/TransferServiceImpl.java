package com.fuyun.inpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.api.payload.VisitTransferredPayload;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.ChangeBedRequest;
import com.fuyun.inpatient.dto.TransferRequest;
import com.fuyun.inpatient.entity.Bed;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.enums.TransferType;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.BedMapper;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.service.BedService;
import com.fuyun.inpatient.service.MedicalOrderService;
import com.fuyun.inpatient.service.TransferService;
import com.fuyun.inpatient.vo.TransferResultVO;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 护理单元变更编排实现（转科四阶段/转床轻量路径，04-inpatient Spec §3.5 时序冻结）：
 * 单 @Transactional 编排事务 = ①MedicalOrderService.stopAllForTransfer 转出病区长期医嘱
 * 自动停嘱（Task 5 impl，接口先行冻结）→②在途三分（医嘱停嘱由①承载；<b>计划三分数据面
 * 操作——临时[order_class=stat]PENDING 计划保留随患者、长期 PENDING 计划作废——归 Task 7/8
 * 计划服务落地时在计划服务内补挂转科钩子，本编排钩子面已留；费用不改写归 M13 日切切分）→
 * ③床位流转（BedService.transferOut 转出床→DISINFECTING 闭合流水 / occupyForTransfer
 * 目标床 CAS 占床开新流水 / visit current_ward/current_bed 原子 CAS 更新）→④事务内发布
 * VisitTransferredPayload（V800 id 49 六字段）。任一阶段失败异常传播整体回滚（转出床状态
 * 复原由回滚语义保证）。转科/转床均为 ADMITTED 内属性变更，不改就诊状态。
 * 线程安全：无状态 singleton；两编排入口各自 @Transactional 收口。
 */
@Slf4j
public class TransferServiceImpl implements TransferService {

    /** 无登录上下文场景的操作者回退值（与 V902 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 转科停嘱原因（固定文案，Task 5 impl 落 stop_reason） */
    private static final String TRANSFER_STOP_REASON = "转科";

    private final InpatientVisitMapper visitMapper;

    private final BedMapper bedMapper;

    private final BedService bedService;

    private final MedicalOrderService medicalOrderService;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 InpatientWebConfig @Import；MedicalOrderService 实现归 Task 5——
     * MedicalOrderServiceImpl 落地后装配链闭合）。
     *
     * @param visitMapper          住院就诊 mapper，非空；在院态定位与 current_* 原子更新
     * @param bedMapper            床位 mapper，非空；目标床位归属校验（只读）
     * @param bedService           床位管理服务（CAS 流转与流水开账权威），非空
     * @param medicalOrderService  住院医嘱服务（转科自动停嘱，Task 5 impl），非空
     * @param events               进程内事件发布器（AFTER_COMMIT 出 MQ），非空
     */
    public TransferServiceImpl(
            InpatientVisitMapper visitMapper,
            BedMapper bedMapper,
            BedService bedService,
            MedicalOrderService medicalOrderService,
            ApplicationEventPublisher events) {
        this.visitMapper = visitMapper;
        this.bedMapper = bedMapper;
        this.bedService = bedService;
        this.medicalOrderService = medicalOrderService;
        this.events = events;
    }

    /**
     * 转科四阶段编排（单事务，时序冻结见类注）：守卫链（在院态/目标病区异病区/在院有床/目标床
     * 异床且归属相符）→①停嘱→②三分钩子面→③床位流转+定位 CAS→④transferred 事件。
     *
     * @param visitId 住院就诊号（I 型 14 位），非空；来源：路径参数
     * @param req     转科入参（目标科室/病区/床位），非空
     * @return 编排出参（前后定位面与完成时点），非空
     */
    @Override
    @Transactional
    public TransferResultVO transfer(String visitId, TransferRequest req) {
        InpatientVisit visit = requireOngoingVisit(visitId);
        // 同病区禁走转科（床位切换走 change-bed 轻量路径——编排深度裁决面）
        if (Objects.equals(req.toWardId(), visit.getCurrentWardId())) {
            throw sameWardRejected(visitId, req.toWardId());
        }
        requireCurrentBed(visit);
        rejectSameBed(visit, req.toBedId());
        requireTargetBed(req.toBedId(), req.toWardId());
        // 阶段①：转出病区全部长期医嘱自动停嘱（stop_reason=转科，停嘱时间=服务器时间；Task 5 impl）
        medicalOrderService.stopAllForTransfer(visit.getId(), TRANSFER_STOP_REASON);
        // 阶段②：在途三分——医嘱停嘱由①承载；计划三分数据面操作归 Task 7/8 计划服务补挂转科钩子
        // （临时 stat PENDING 计划保留随患者重定向、长期 PENDING 计划作废）；费用不改写归 M13 日切。
        // 阶段③④：床位三段流转 + 定位 CAS + 事件
        OffsetDateTime transferredAt =
                changeLocation(visit, req.toDeptId(), req.toWardId(), req.toBedId(), TransferType.WARD_TRANSFER);
        log.info(
                "转科编排完成：visitId={}，patientId={}，{}({})→{}({})，operator={}",
                visitId,
                visit.getPatientId(),
                visit.getCurrentWardId(),
                visit.getCurrentBedId(),
                req.toWardId(),
                req.toBedId(),
                operator());
        return new TransferResultVO(
                visitId,
                visit.getCurrentWardId(),
                visit.getCurrentBedId(),
                req.toWardId(),
                req.toBedId(),
                transferredAt);
    }

    /**
     * 同病区转床轻量路径（单事务，无停嘱步骤）：守卫链（在院态/在院有床/目标床异床且同病区）→
     * 床位流转 + 定位 CAS + transferred 事件（前后病区相同）。
     *
     * @param visitId 住院就诊号（I 型 14 位），非空；来源：路径参数
     * @param req     转床入参（目标床位），非空
     * @return 编排出参（前后定位面与完成时点），非空
     */
    @Override
    @Transactional
    public TransferResultVO changeBed(String visitId, ChangeBedRequest req) {
        InpatientVisit visit = requireOngoingVisit(visitId);
        requireCurrentBed(visit);
        rejectSameBed(visit, req.toBedId());
        // 转床限定同病区（跨病区走 transfer 四阶段编排）
        Bed targetBed = requireBed(req.toBedId());
        if (!Objects.equals(targetBed.getWardId(), visit.getCurrentWardId())) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "转床仅限同病区（跨病区走转科编排）：visitId="
                            + visitId
                            + "，当前病区="
                            + visit.getCurrentWardId()
                            + "，目标床位病区="
                            + targetBed.getWardId());
        }
        // 轻量路径=③④（无①②）：转出床流转 + 目标床占床（BED_CHANGE 流水）+ 定位 CAS + 事件
        OffsetDateTime transferredAt =
                changeLocation(visit, null, visit.getCurrentWardId(), req.toBedId(), TransferType.BED_CHANGE);
        log.info(
                "同病区转床完成：visitId={}，patientId={}，床位 {}→{}（wardId={}），operator={}",
                visitId,
                visit.getPatientId(),
                visit.getCurrentBedId(),
                req.toBedId(),
                visit.getCurrentWardId(),
                operator());
        return new TransferResultVO(
                visitId,
                visit.getCurrentWardId(),
                visit.getCurrentBedId(),
                visit.getCurrentWardId(),
                req.toBedId(),
                transferredAt);
    }

    /**
     * 阶段③④统一路径：转出床消毒流转（闭合流水）→ 目标床 CAS 占床（开新流水）→ visit 定位
     * 原子 CAS → 事务内发布 transferred（六字段，AFTER_COMMIT 出 MQ）。任一步失败异常传播，
     * 编排事务整体回滚（转出床状态复原）。
     *
     * @param visit     编排起始就诊行（ADMITTED，current_* 为转出定位面），非空
     * @param toDeptId  目标科室编码，可空（转床路径不传——保留原值）
     * @param toWardId  目标病区编码，非空
     * @param toBedId   目标床位 id，非空
     * @param type      编排类型（决定占用流水 assign_type），非空
     * @return 转移完成时点（应用服务器时钟，与事件载荷同源），非空
     */
    private OffsetDateTime changeLocation(
            InpatientVisit visit, String toDeptId, String toWardId, Long toBedId, TransferType type) {
        // 阶段③-1：转出床 OCCUPIED→DISINFECTING + bed_assign 未继行闭合 + bed.changed
        bedService.transferOut(visit.getCurrentBedId(), visit.getVisitId());
        // 阶段③-2：目标床 FREE/RESERVED→OCCUPIED CAS + bed_assign 新流水 + bed.changed
        bedService.occupyForTransfer(toBedId, visit.getVisitId(), visit.getPatientId(), type);
        // 阶段③-3：visit 当前病区/床位原子更新（0 行=并发出院/作废窗口，定性冲突回滚）
        if (visitMapper.casTransferLocation(visit.getVisitId(), toDeptId, toWardId, toBedId, operator()) == 0) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "就诊定位并发冲突（编排期间并发出院/作废），本次变更回滚：visitId=" + visit.getVisitId());
        }
        // 阶段④：transferred 事件（载荷仅定位键与时间线，脱敏红线；M05 重定向/M13 切分/M14 解绑依据）
        OffsetDateTime transferredAt = OffsetDateTime.now();
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_VISIT_TRANSFERRED,
                new VisitTransferredPayload(
                        visit.getVisitId(),
                        visit.getPatientId(),
                        visit.getCurrentWardId(),
                        visit.getCurrentBedId(),
                        toWardId,
                        toBedId,
                        transferredAt.toInstant())));
        return transferredAt;
    }

    /** 在院就诊定位（未命中 IP-1007；非 ADMITTED 态 IP-1008——转科/转床限在院患者）。 */
    private InpatientVisit requireOngoingVisit(String visitId) {
        InpatientVisit visit =
                visitMapper.selectOne(Wrappers.<InpatientVisit>lambdaQuery().eq(InpatientVisit::getVisitId, visitId));
        if (visit == null) {
            throw new BizException(InpatientErrorCode.VISIT_NOT_FOUND, HttpStatus.NOT_FOUND, "住院就诊不存在：" + visitId);
        }
        if (!VisitStatus.ADMITTED.getCode().equals(visit.getStatus())) {
            throw new BizException(
                    InpatientErrorCode.VISIT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "住院就诊状态不允许转科/转床：visitId=" + visitId + "，当前状态=" + visit.getStatus());
        }
        return visit;
    }

    /** 在院床位在位守卫（current_bed_id 缺失=数据不一致，定性冲突禁编排）。 */
    private void requireCurrentBed(InpatientVisit visit) {
        if (visit.getCurrentBedId() == null) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "在院就诊无当前床位（数据不一致），禁止转科/转床：visitId=" + visit.getVisitId());
        }
    }

    /** 目标床位与当前床位相同拒绝（无位移编排，IP-1022）。 */
    private void rejectSameBed(InpatientVisit visit, Long toBedId) {
        if (Objects.equals(toBedId, visit.getCurrentBedId())) {
            throw new BizException(
                    InpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "目标床位与当前床位相同（无位移编排拒绝）：visitId=" + visit.getVisitId() + "，bedId=" + toBedId);
        }
    }

    /** 目标床位定位与归属校验（未命中 IP-1004；归属不符 IP-1023）——转科路径守卫。 */
    private Bed requireTargetBed(Long toBedId, String toWardId) {
        Bed bed = requireBed(toBedId);
        if (!Objects.equals(bed.getWardId(), toWardId)) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "目标床位与目标病区归属不符：bedId=" + toBedId + "，床位病区=" + bed.getWardId() + "，目标病区=" + toWardId);
        }
        return bed;
    }

    /** 床位定位（未命中 IP-1004；逻辑删由 @TableLogic 自动过滤）。 */
    private Bed requireBed(Long bedId) {
        Bed bed = bedMapper.selectById(bedId);
        if (bed == null) {
            throw new BizException(InpatientErrorCode.BED_NOT_FOUND, HttpStatus.NOT_FOUND, "目标床位不存在：" + bedId);
        }
        return bed;
    }

    /** 同病区转科拒绝（IP-1022——同病区床位切换走 change-bed 轻量路径）。 */
    private BizException sameWardRejected(String visitId, String toWardId) {
        return new BizException(
                InpatientErrorCode.PARAM_FORMAT_INVALID,
                HttpStatus.BAD_REQUEST,
                "转科目标病区与当前病区相同（同病区走转床路径）：visitId=" + visitId + "，toWardId=" + toWardId);
    }

    /** 操作者取值（免登录上下文回退 system，与审计列默认同源）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
