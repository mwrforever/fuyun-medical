package com.fuyun.patient.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.mapper.PatientMapper;
import com.fuyun.patient.service.IPatientService;

/**
 * 患者主索引服务实现（patient.patient 主表）：本任务仅承载 IService 本体；
 * 解析（api PatientContextResolver 实现）/更新/检索/冻结状态机随 Task 6/7 在本类扩充。
 */
public class PatientServiceImpl extends ServiceImpl<PatientMapper, Patient> implements IPatientService {}
