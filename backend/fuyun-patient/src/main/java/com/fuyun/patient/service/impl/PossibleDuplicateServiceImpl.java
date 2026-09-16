package com.fuyun.patient.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.convert.PatientConverter;
import com.fuyun.patient.dto.PatientMatchCheckRequest;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.entity.PossibleDuplicate;
import com.fuyun.patient.mapper.PossibleDuplicateMapper;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.service.IPossibleDuplicateService;
import com.fuyun.patient.service.PatientMatchingService;
import com.fuyun.patient.vo.PatientMatchCheckVO;
import com.fuyun.patient.vo.PossibleDuplicateVO;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 疑似重复治理实现（patient.possible_duplicate 主表）。
 *
 * <p>幂等口径：(patient_id_a, patient_id_b) 部分唯一索引为最终兜底——建档实时与批量扫描双渠道
 * 重复命中以 DuplicateKeyException 静默幂等（Spec §10 异常项「批量扫描可重跑且同一对患者不重复生成」）。
 *
 * <p>事务口径：list 只读事务；exclude/recordSuspect 单行小事务；scanBatch 禁长事务包裹
 * （A.4.2-7）——外层由调用方任务承担分布式锁，本方法内逐条 recordSuspect 各自独立提交。
 */
@Slf4j
public class PossibleDuplicateServiceImpl extends ServiceImpl<PossibleDuplicateMapper, PossibleDuplicate>
        implements IPossibleDuplicateService {

    /** 批量扫描回看窗口（天）：增量比对新档范围 */
    private static final int SCAN_WINDOW_DAYS = 7;

    /** 状态词表：待人工审核（DuplicateStatus） */
    private static final String STATUS_PENDING = "PENDING";

    /** 状态词表：已排除（DuplicateStatus） */
    private static final String STATUS_EXCLUDED = "EXCLUDED";

    private final IPatientService patientService;

    private final PatientMatchingService matchingService;

    /**
     * 全参构造器（装配归 PatientWebConfig @Import）。
     *
     * @param patientService  患者主表服务（批量扫描近窗档来源），非空
     * @param matchingService EMPI 匹配引擎（同名候选评分），非空
     */
    public PossibleDuplicateServiceImpl(IPatientService patientService, PatientMatchingService matchingService) {
        this.patientService = patientService;
        this.matchingService = matchingService;
    }

    /**
     * 待审列表。
     *
     * @param status 状态过滤词（ALL=不过滤），非空
     * @param page   0 基页码
     * @param size   1-200
     * @return 分页出参，非空
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<PossibleDuplicateVO> list(String status, int page, int size) {
        // ALL 以外的词表才下推状态条件（eq 条件即时求值，3.5.17 陷阱：filter 必须先算好再入 wrapper）
        boolean filter = status != null && !"ALL".equals(status);
        Page<PossibleDuplicate> result = lambdaQuery()
                .eq(filter, PossibleDuplicate::getStatus, status)
                .orderByDesc(PossibleDuplicate::getCreatedAt)
                .page(new Page<>(page + 1, size));
        List<PossibleDuplicateVO> rows = result.getRecords().stream()
                .map(row -> Mappers.getMapper(PatientConverter.class).toVO(row))
                .toList();
        return PageResult.of(rows, page, size, result.getTotal());
    }

    /**
     * 排除待审对（PENDING→EXCLUDED，CAS 条件更新防并发双审）。
     *
     * @param id   待审行 id，非空
     * @param note 排除理由，非空
     * @throws BizException PAT-1009/PAT-1010
     */
    @Override
    @Transactional
    public void exclude(long id, String note) {
        // CAS「where status=PENDING」：受影响行数为 0 = 行不存在或已被处置（并发双审守卫）
        boolean updated = lambdaUpdate()
                .set(PossibleDuplicate::getStatus, STATUS_EXCLUDED)
                .set(PossibleDuplicate::getReviewNote, note)
                .set(PossibleDuplicate::getReviewedAt, OffsetDateTime.now())
                .eq(PossibleDuplicate::getId, id)
                .eq(PossibleDuplicate::getStatus, STATUS_PENDING)
                .update();
        if (!updated) {
            // CAS 未命中细分：行不存在（404）与已审核（409）两种业务语义
            if (getById(id) == null) {
                throw new BizException(PatientErrorCode.DUPLICATE_NOT_FOUND, HttpStatus.NOT_FOUND, "疑似重复记录不存在");
            }
            throw new BizException(PatientErrorCode.DUPLICATE_ALREADY_REVIEWED, HttpStatus.CONFLICT, "疑似重复记录已审核");
        }
        log.info("疑似重复已排除：id={}", id);
    }

    /**
     * 建档实时生成待审行（a&lt;b 规范化 + 唯一兜底幂等）。
     *
     * @param newPatientId       新档 id，非空
     * @param candidatePatientId 命中候选 id，非空
     * @param check              匹配结论，非空
     */
    @Override
    @Transactional
    public void recordSuspect(long newPatientId, long candidatePatientId, PatientMatchCheckVO check) {
        // a<b 规范化：防同对患者两行镜像（应用层约定，唯一索引最终兜底）
        long a = Math.min(newPatientId, candidatePatientId);
        long b = Math.max(newPatientId, candidatePatientId);
        PossibleDuplicate row = new PossibleDuplicate();
        row.setPatientIdA(a);
        row.setPatientIdB(b);
        row.setMatchScore(check.score());
        row.setMatchedRules(String.valueOf(check.matchedRules()));
        row.setSource("REGISTER_SCAN");
        row.setStatus(STATUS_PENDING);
        try {
            save(row);
            log.info("建档实时生成疑似重复待审：a={}，b={}，score={}", a, b, check.score());
        } catch (DuplicateKeyException e) {
            // 同对患者重复命中只留一条（建档实时/批量扫描双渠道幂等，Spec §10）
            log.warn("疑似重复待审已存在，幂等跳过：a={}，b={}", a, b);
        }
    }

    /**
     * 批量增量扫描（近 7 天档 × 同名存量评分；逐条独立小事务，禁长事务包裹）。
     *
     * @return 新增待审行数
     */
    @Override
    public int scanBatch() {
        OffsetDateTime since = OffsetDateTime.now().minusDays(SCAN_WINDOW_DAYS);
        List<Patient> recent =
                patientService.lambdaQuery().ge(Patient::getCreatedAt, since).list();
        int created = 0;
        for (Patient candidate : recent) {
            PatientMatchCheckVO check = matchingService.preCheck(new PatientMatchCheckRequest(
                    candidate.getName(),
                    candidate.getSex(),
                    candidate.getBirthDate() == null
                            ? null
                            : candidate.getBirthDate().toString(),
                    null,
                    null));
            // 仅 SUSPECT 且候选非自身时生成待审行（候选=自身为引擎矛盾兜底，跳过防自合并）
            if ("SUSPECT".equals(check.outcome())
                    && check.candidatePatientId() != null
                    && check.candidatePatientId() != candidate.getPatientId()) {
                long before = countPendingOf(candidate.getPatientId());
                recordSuspect(candidate.getPatientId(), check.candidatePatientId(), check);
                // 以 PENDING 行数增量为新增判据：重复命中幂等静默（行数不变）不计入
                if (countPendingOf(candidate.getPatientId()) > before) {
                    created++;
                }
            }
        }
        log.info("批量增量扫描完成：近{}天档数={}，新增待审={}", SCAN_WINDOW_DAYS, recent.size(), created);
        return created;
    }

    /** 该患者当前 PENDING 行数（扫描计数锚点） */
    private long countPendingOf(long patientId) {
        return lambdaQuery()
                .eq(PossibleDuplicate::getStatus, STATUS_PENDING)
                .and(w -> w.eq(PossibleDuplicate::getPatientIdA, patientId)
                        .or()
                        .eq(PossibleDuplicate::getPatientIdB, patientId))
                .count();
    }
}
