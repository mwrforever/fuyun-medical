package com.fuyun.patient.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.patient.entity.PatientIdentifier;

/**
 * 患者标识注册表 IService（A.4.3-20）：attach 本任务交付；解析/挂失/补卡/解绑随 Task 7/10 扩充。
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
}
