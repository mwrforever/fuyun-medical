package com.fuyun.patient.convert;

import org.mapstruct.Mapper;

/**
 * 患者域 MapStruct 转换器（A.7-4）：实体→出参 VO 映射集中点；Task 6 起按需追加映射方法
 * （金额/状态等关键业务字段映射必须手写或单测全覆盖——本域出参均为直映字段）。
 */
@Mapper
public interface PatientConverter {}
