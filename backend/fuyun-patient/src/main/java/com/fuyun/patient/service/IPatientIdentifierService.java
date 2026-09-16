package com.fuyun.patient.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.patient.entity.PatientIdentifier;
import java.util.List;

/**
 * 患者标识注册表 IService（A.4.3-20）：attach 与解析/清单/identifier.changed 事件发布本任务交付；
 * 挂失/补卡/解绑（标识状态机写侧）随 Task 10 扩充。
 */
public interface IPatientIdentifierService extends IService<PatientIdentifier> {

    /**
     * 挂接一条标识到主索引（建档归一补挂与建档注册共用入口）。
     *
     * @param patientId       主索引 id，非空
     * @param identifierType  标识类型（IdentifierType 词表），非空
     * @param identifierValue 标识值明文（方法内加密，禁止调用方落日志），非空
     * @param cardNo          卡面号（卡类介质），可空
     * @param primary         是否主标识
     * @return 标识行 id
     * @throws com.fuyun.common.exception.BizException PAT-1002（409）标识已被其他档案占用时触发；
     *                                                  建议处理策略：提示操作员走疑似重复人工核对
     */
    Long attach(long patientId, String identifierType, String identifierValue, String cardNo, boolean primary);

    /**
     * 标识解析（盲索引等值查 ACTIVE 行；挂失/解绑/替换标识解析即失效）。
     *
     * @param identifierType  标识类型词表值，非空
     * @param identifierValue 标识值明文（方法内盲索引，禁日志），非空
     * @return 命中的 ACTIVE 标识行，非空
     * @throws com.fuyun.common.exception.BizException PAT-1001（404）无 ACTIVE 命中时触发；
     *                                                  建议处理策略：按介质未登记/已失效提示，禁止重试
     */
    PatientIdentifier resolveActive(String identifierType, String identifierValue);

    /**
     * 按档案展开标识清单（读侧只出 cardNo 不出标识值）。
     *
     * @param patientId 患者主索引，非空
     * @return 标识行清单（无则空清单，非 null）
     */
    List<PatientIdentifier> listByPatient(long patientId);

    /**
     * 发布 patient.identifier.changed 应用事件（事务内发布，AFTER_COMMIT 出 MQ；缓存失效依据）。
     *
     * @param patientId       患者主索引，非空
     * @param identifierType  标识类型，非空
     * @param identifierValue 标识值明文（方法内盲索引取载荷 valueHash），非空
     * @param changeType      变更类型 BOUND/LOST/REPLACED/UNBOUND，非空
     */
    void publishChanged(long patientId, String identifierType, String identifierValue, String changeType);
}
