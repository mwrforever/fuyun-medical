package com.fuyun.patient.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fuyun.patient.dto.PatientMatchCheckRequest;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.properties.PatientEmpiProperties;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.service.PatientMatchingService;
import com.fuyun.patient.vo.PatientMatchCheckVO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * EMPI 分层匹配引擎实现（M02 §3.1 定稿口径；候选查询仅本模块主表经 IService，A.4.3-13）。
 *
 * <p>分层执行：①强标识（身份证）盲索引等值查——唯一命中且姓名/性别/出生日期一致 → AUTO_MATCH；
 * ②命中但属性矛盾 → SUSPECT 固定满分（安全边界：误配不可逆，任何合并不自动越过人工审核）；
 * ③无强标识命中 → 弱标识候选（同名集）逐项评分（NAME_SEX_BIRTH=95/NAME_MOBILE=90/NAME_SEX=70/
 * NAME_ONLY=60），最高分 ≥ 阈值 → SUSPECT，否则 NO_MATCH。只读事务（A.4.2-7 查询标注只读）。
 */
@Slf4j
public class PatientMatchingServiceImpl implements PatientMatchingService {

    /** 弱标识评分规则值（代码内固定规则表，仅阈值参数化——PatientEmpiProperties javadoc 口径） */
    private static final BigDecimal SCORE_NAME_SEX_BIRTH = new BigDecimal("95");

    private static final BigDecimal SCORE_NAME_MOBILE = new BigDecimal("90");
    private static final BigDecimal SCORE_NAME_SEX = new BigDecimal("70");
    private static final BigDecimal SCORE_NAME_ONLY = new BigDecimal("60");
    private static final BigDecimal SCORE_ID_CARD_CONFLICT = new BigDecimal("100");

    private final IPatientService patientService;

    private final PatientFieldCrypto crypto;

    private final PatientEmpiProperties properties;

    /**
     * 全参构造器（装配归 PatientWebConfig @Import）。
     *
     * @param patientService 患者主表 IService（候选查询），非空
     * @param crypto         加密构件（盲索引计算），非空
     * @param properties     EMPI 阈值参数，非空
     */
    public PatientMatchingServiceImpl(
            IPatientService patientService, PatientFieldCrypto crypto, PatientEmpiProperties properties) {
        this.patientService = patientService;
        this.crypto = crypto;
        this.properties = properties;
    }

    /**
     * 分层匹配预检（只读）。
     *
     * @param request 预检入参，非空；来源：建档表单/建档请求
     * @return 匹配结论，非空；结果仅含规则名与评分，禁含敏感明文
     */
    @Override
    @Transactional(readOnly = true)
    public PatientMatchCheckVO preCheck(PatientMatchCheckRequest request) {
        // 第一层：强标识（身份证）唯一命中即归一；属性矛盾降级待审
        if (request.idCardNo() != null && !request.idCardNo().isBlank()) {
            List<Patient> hits = patientService.list(
                    new LambdaQueryWrapper<Patient>().eq(Patient::getIdCardNoHash, crypto.hash(request.idCardNo())));
            if (!hits.isEmpty()) {
                Patient hit = hits.get(0);
                if (isDemographicsConsistent(hit, request)) {
                    return new PatientMatchCheckVO("AUTO_MATCH", hit.getPatientId(), null, List.of("ID_CARD_EXACT"));
                }
                log.info("强标识命中但人口属性矛盾，降级疑似重复待审：candidatePatientId={}", hit.getPatientId());
                return new PatientMatchCheckVO(
                        "SUSPECT", hit.getPatientId(), SCORE_ID_CARD_CONFLICT, List.of("ID_CARD_CONFLICT"));
            }
        }
        // 第二层：弱标识候选（同名集）评分；无证件无同名时直接新建
        List<Patient> candidates =
                patientService.list(new LambdaQueryWrapper<Patient>().eq(Patient::getName, request.name()));
        Patient best = null;
        BigDecimal bestScore = BigDecimal.ZERO;
        List<String> bestRules = List.of();
        for (Patient candidate : candidates) {
            List<String> rules = new ArrayList<>();
            BigDecimal score = BigDecimal.ZERO;
            if (isDemographicsConsistent(candidate, request)) {
                rules.add("NAME_SEX_BIRTH");
                score = SCORE_NAME_SEX_BIRTH;
            } else {
                if (request.mobile() != null
                        && !request.mobile().isBlank()
                        && Objects.equals(candidate.getMobileHash(), crypto.hash(request.mobile()))) {
                    rules.add("NAME_MOBILE");
                    score = SCORE_NAME_MOBILE;
                }
                if (request.sex() != null && request.sex().equals(candidate.getSex())) {
                    rules.add("NAME_SEX");
                    score = score.max(SCORE_NAME_SEX);
                }
                if (rules.isEmpty()) {
                    rules.add("NAME_ONLY");
                    score = SCORE_NAME_ONLY;
                }
            }
            // 多候选取最高分：评分只升不降，保留首个最高分候选
            if (score.compareTo(bestScore) > 0) {
                best = candidate;
                bestScore = score;
                bestRules = rules;
            }
        }
        if (best == null || bestScore.compareTo(BigDecimal.valueOf(properties.suspectThreshold())) < 0) {
            return new PatientMatchCheckVO("NO_MATCH", null, null, List.of());
        }
        log.info("弱标识评分达疑似阈值，转人工待审：candidatePatientId={}，score={}", best.getPatientId(), bestScore);
        return new PatientMatchCheckVO("SUSPECT", best.getPatientId(), bestScore, List.copyOf(bestRules));
    }

    /** 姓名/性别/出生日期三要素全一致判定（AUTO_MATCH 与 NAME_SEX_BIRTH 共用口径） */
    private boolean isDemographicsConsistent(Patient candidate, PatientMatchCheckRequest request) {
        boolean sexOk = request.sex() != null && request.sex().equals(candidate.getSex());
        boolean birthOk;
        if (candidate.getBirthDate() == null
                || request.birthDate() == null
                || request.birthDate().isBlank()) {
            // 出生日期双方均空视为一致（急诊无名氏等无出生日期档案的归一边界）；单侧空即不一致
            birthOk = candidate.getBirthDate() == null
                    && (request.birthDate() == null || request.birthDate().isBlank());
        } else {
            birthOk = candidate.getBirthDate().equals(LocalDate.parse(request.birthDate()));
        }
        return request.name().equals(candidate.getName()) && sexOk && birthOk;
    }
}
