package com.fuyun.patient.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.api.PatientIdentifierChangedPayload;
import com.fuyun.patient.constants.PatientMessagingConstants;
import com.fuyun.patient.entity.PatientIdentifier;
import com.fuyun.patient.internal.PatientDomainEvent;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.mapper.PatientIdentifierMapper;
import com.fuyun.patient.service.IPatientIdentifierService;
import com.fuyun.patient.service.IPatientService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 患者标识注册表实现（patient.patient_identifier 主表）：挂接加密落库、ACTIVE 等值解析
 * （挂失/解绑/替换即解析失效）、按档案展开清单与 identifier.changed 应用事件发布
 * （发布点统一收口，供补挂与 Task 10 卡操作复用；缓存失效依据）。
 *
 * <p>并发兜底：(identifier_type, value_hash) 部分唯一索引——并发重复挂接以 DuplicateKeyException
 * 捕获转 PAT-1002 业务失败（数据库唯一约束为最终保证，A.5-6 口径）。
 *
 * <p>敏感红线：标识值明文仅在本类方法参数生命周期内存活，禁入日志；对外事件载荷只携盲索引
 * 摘要 valueHash（M02 红线 3）。
 */
@Slf4j
public class PatientIdentifierServiceImpl extends ServiceImpl<PatientIdentifierMapper, PatientIdentifier>
        implements IPatientIdentifierService {

    private final PatientFieldCrypto crypto;

    // TODO(card-ops): 患者主索引服务依赖，供 Task 10 卡操作（挂失/补卡/解绑）解析收敛视图复用
    private final IPatientService patientService;

    /** 应用事件发布（identifier.changed 事务内发布，发布器 AFTER_COMMIT 转 MQ），构造器注入 */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 全参构造器（装配归 PatientWebConfig @Import）。
     *
     * @param crypto          加密构件（标识值密文与盲索引），非空
     * @param patientService  患者主索引服务（卡操作链路解析收敛视图用），非空
     * @param eventPublisher  Spring 应用事件发布器，非空（容器供给）
     */
    public PatientIdentifierServiceImpl(
            PatientFieldCrypto crypto, IPatientService patientService, ApplicationEventPublisher eventPublisher) {
        this.crypto = crypto;
        this.patientService = patientService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 挂接标识：加密落库 + 唯一约束兜底；主标识且卡类介质时 cardNo 必须为空校验交由调用方契约。
     *
     * @param patientId       主索引 id，非空
     * @param identifierType  标识类型词表值，非空
     * @param identifierValue 标识值明文，非空（仅本方法生命周期内存活）
     * @param cardNo          卡面号，可空
     * @param primary         是否主标识
     * @return 标识行 id
     * @throws BizException PAT-1002（409）标识已挂接其他档案
     */
    @Override
    public Long attach(long patientId, String identifierType, String identifierValue, String cardNo, boolean primary) {
        PatientIdentifier identifier = new PatientIdentifier();
        identifier.setPatientId(patientId);
        identifier.setIdentifierType(identifierType);
        identifier.setIdentifierValueCipher(crypto.encrypt(identifierValue));
        identifier.setValueHash(crypto.hash(identifierValue));
        identifier.setCardNo(cardNo);
        identifier.setStatus("ACTIVE");
        identifier.setIsPrimary(primary);
        try {
            save(identifier);
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底命中：一标识只可挂一档（M02 §3.2），转业务失败提示人工核对
            log.warn("标识挂接撞唯一约束，疑似重复场景：patientId={}，identifierType={}", patientId, identifierType);
            throw new BizException(PatientErrorCode.IDENTIFIER_ALREADY_BOUND, HttpStatus.CONFLICT, "该标识已登记在其他患者档案");
        }
        return identifier.getId();
    }

    /**
     * 标识解析（ACTIVE 等值查）。
     *
     * @param identifierType  标识类型词表值，非空
     * @param identifierValue 标识值明文，非空
     * @return ACTIVE 标识行，非空
     * @throws BizException PAT-1001 无 ACTIVE 命中（挂失/解绑/替换即解析失效语义）
     */
    @Override
    @Transactional(readOnly = true)
    public PatientIdentifier resolveActive(String identifierType, String identifierValue) {
        // 盲索引等值查（value_hash 为 HMAC 摘要，明文不作查询条件不入日志）；list 取首行替代 one()，
        // 脏数据多行命中时报 TooManyResults 的风险不引入解析主链路
        List<PatientIdentifier> hits = lambdaQuery()
                .eq(PatientIdentifier::getIdentifierType, identifierType)
                .eq(PatientIdentifier::getValueHash, crypto.hash(identifierValue))
                .list();
        PatientIdentifier identifier = hits.isEmpty() ? null : hits.get(0);
        if (identifier == null || !"ACTIVE".equals(identifier.getStatus())) {
            // 非 ACTIVE 命中同样按未解析处理：挂失/解绑后解析立即失效（M02 §5 标识状态机）
            throw new BizException(PatientErrorCode.PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "标识未登记或已失效");
        }
        return identifier;
    }

    /**
     * 按档案展开标识清单。
     *
     * @param patientId 患者主索引，非空
     * @return 清单（无则空清单）
     */
    @Override
    @Transactional(readOnly = true)
    public List<PatientIdentifier> listByPatient(long patientId) {
        return lambdaQuery()
                .eq(PatientIdentifier::getPatientId, patientId)
                .orderByDesc(PatientIdentifier::getBoundAt)
                .list();
    }

    /** identifier.changed 应用事件发布（载荷只携 valueHash 不携明文） */
    @Override
    public void publishChanged(long patientId, String identifierType, String identifierValue, String changeType) {
        eventPublisher.publishEvent(new PatientDomainEvent(
                PatientMessagingConstants.EVENT_IDENTIFIER_CHANGED,
                new PatientIdentifierChangedPayload(
                        patientId, identifierType, crypto.hash(identifierValue), changeType)));
    }
}
