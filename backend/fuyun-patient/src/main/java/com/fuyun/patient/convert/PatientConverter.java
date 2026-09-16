package com.fuyun.patient.convert;

import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.entity.PatientIdentifier;
import com.fuyun.patient.vo.IdentifierVO;
import com.fuyun.patient.vo.PatientVO;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * 患者域 MapStruct 转换器（A.7-4）：实体→出参 VO 映射集中点；Task 6 起按需追加映射方法
 * （金额/状态等关键业务字段映射必须手写或单测全覆盖——本域出参均为直映字段）。
 */
@Mapper
public interface PatientConverter {

    /** 实体→档案出参直映（敏感三列不映射——明文只在 service 内解密后经脱敏引擎回填） */
    PatientVO toVO(Patient entity);

    /** 实体清单→出参清单 */
    List<PatientVO> toVOList(List<Patient> entities);

    /** 标识实体→出参（identifierValue 密文/盲索引两列不映射——值禁出接口层） */
    IdentifierVO toVO(PatientIdentifier entity);
}
