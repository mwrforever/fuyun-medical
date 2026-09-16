package com.fuyun.patient.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.entity.PatientIdentifier;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.mapper.PatientIdentifierMapper;
import com.fuyun.patient.service.IPatientIdentifierService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

/**
 * 患者标识注册表实现（patient.patient_identifier 主表）。
 *
 * <p>并发兜底：(identifier_type, value_hash) 部分唯一索引——并发重复挂接以 DuplicateKeyException
 * 捕获转 PAT-1002 业务失败（数据库唯一约束为最终保证，A.5-6 口径）。
 */
@Slf4j
public class PatientIdentifierServiceImpl extends ServiceImpl<PatientIdentifierMapper, PatientIdentifier>
        implements IPatientIdentifierService {

    private final PatientFieldCrypto crypto;

    /**
     * 全参构造器（装配归 PatientWebConfig @Import）。
     *
     * @param crypto 加密构件（标识值密文与盲索引），非空
     */
    public PatientIdentifierServiceImpl(PatientFieldCrypto crypto) {
        this.crypto = crypto;
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
}
