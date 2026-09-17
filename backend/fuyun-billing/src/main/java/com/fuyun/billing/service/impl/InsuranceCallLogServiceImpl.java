package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.entity.InsuranceCallLog;
import com.fuyun.billing.enums.InsuranceCallStatus;
import com.fuyun.billing.mapper.InsuranceCallLogMapper;
import com.fuyun.billing.service.IInsuranceCallLogService;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 医保业务调用留痕实现（billing.insurance_call_log，方案 3.2 两级日志之业务级落点）：record 落库
 * （回执摘要钳 512）+ compensate 悬挂补偿状态迁移（TIMEOUT/FAILED→COMPENSATED，BILL-1026 守卫）。
 *
 * <p>线程安全：无状态 singleton；写操作 @Transactional 收口。PR-3 模拟通道零 IO 即时返回不产生
 * TIMEOUT 态，compensate 由 retry 端点真实调用，守卫语义随 P5 真实通道两段式（INIT/SENT→回填）
 * 悬挂补偿复用。
 */
@Slf4j
public class InsuranceCallLogServiceImpl extends ServiceImpl<InsuranceCallLogMapper, InsuranceCallLog>
        implements IInsuranceCallLogService {

    /** response_digest 列宽（V604 VARCHAR(512)）：超宽回执摘要入库前钳断位 */
    private static final int RESPONSE_DIGEST_MAX_LEN = 512;

    /**
     * 留痕行落库：按调用方装配行原样持久化（状态机演进职责在调用方——模拟 SUCCESS 直落 /
     * P5 两段式 INIT 先行），仅统一收口回执摘要列宽钳制。
     *
     * @param row 留痕行，非空；来源：preview 等医保调用点事务内装配
     */
    @Override
    @Transactional
    public void record(InsuranceCallLog row) {
        // 回执摘要按 V604 列宽入库前钳 512（模拟回执 JSON 与真实回执引用摘要同口径，防超长入库失败）
        if (row.getResponseDigest() != null && row.getResponseDigest().length() > RESPONSE_DIGEST_MAX_LEN) {
            row.setResponseDigest(row.getResponseDigest().substring(0, RESPONSE_DIGEST_MAX_LEN));
        }
        // 数据库写操作：留痕行落库（业务级日志，悬挂确认/冲正/补偿驱动数据源）
        save(row);
        log.info(
                "医保调用留痕落库：txnCode={}，visit={}，status={}，centerSerialNo={}",
                row.getTxnCode(),
                row.getVisitId(),
                row.getStatus() == null ? null : row.getStatus().getCode(),
                row.getCenterSerialNo());
    }

    /**
     * 补偿重试：仅 TIMEOUT/FAILED 行可重试（BILL-1026 守卫），迁移 COMPENSATED 并回填结论留痕
     * （WAIVED 人工核销走独立人工动作，不经本入口）。
     *
     * @param id   留痕行 id，非空
     * @param note 补偿结论，非空白
     * @throws BizException BILL-1025（404 缺行）/ BILL-1026（409 状态不允许重试）
     */
    @Override
    @Transactional
    public void compensate(long id, String note) {
        // 数据库读操作：留痕行定位（缺行 404 先于状态守卫）
        InsuranceCallLog row = getById(id);
        if (row == null) {
            throw new BizException(BillingErrorCode.INSURANCE_CALL_NOT_FOUND, HttpStatus.NOT_FOUND, "医保调用留痕不存在：" + id);
        }
        // 状态守卫：仅 TIMEOUT/FAILED 两态进补偿驱动（SUCCESS 成功行/WAIVED 核销终态行拒重试）
        if (row.getStatus() != InsuranceCallStatus.TIMEOUT && row.getStatus() != InsuranceCallStatus.FAILED) {
            log.warn("医保补偿重试状态拦截：id={}，status={}", id, row.getStatus().getCode());
            throw new BizException(
                    BillingErrorCode.INSURANCE_COMPENSATE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "仅 TIMEOUT/FAILED 调用行允许补偿重试，当前状态：" + row.getStatus().getCode());
        }
        // 数据库写操作：补偿完成迁移 + 结论留痕（COMPENSATED 为终态，审计检索键=compensate_note）
        row.setStatus(InsuranceCallStatus.COMPENSATED);
        row.setCompensateNote(note);
        updateById(row);
        log.info("医保调用补偿完成：id={}，txnCode={}，note={}", id, row.getTxnCode(), note);
    }

    /**
     * 留痕分页查询：visitId 可空=全状态全量（运营排查视图）；id 升序=落库行序（A.4.3-17 唯一顺序）。
     *
     * @param visitId CF-3 就诊号，可空
     * @param page    页码（0 基）
     * @param size    单页条数
     * @return 留痕行分页，非空
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<InsuranceCallLog> page(String visitId, int page, int size) {
        // 数据库读操作：0 基请求转 MP 1 基 current；visitId 条件缺席即全量
        Page<InsuranceCallLog> result = lambdaQuery()
                .eq(visitId != null && !visitId.isBlank(), InsuranceCallLog::getVisitId, visitId)
                .orderByAsc(InsuranceCallLog::getId)
                .page(new Page<>(page + 1, size));
        return PageResult.of(result.getRecords(), page, size, result.getTotal());
    }
}
