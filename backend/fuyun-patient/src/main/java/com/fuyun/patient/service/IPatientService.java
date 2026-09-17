package com.fuyun.patient.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.dto.PatientSearchQuery;
import com.fuyun.patient.dto.PatientUpdateRequest;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.vo.PatientVO;
import java.util.List;

/**
 * 患者主索引 IService（CRUD 型接口，A.4.3-20）：解析（api PatientContextResolver 实现）随 Task 7 扩充；
 * 查询/更新/冻结状态机已由 Task 6 交付。
 */
public interface IPatientService extends IService<Patient> {

    /** 档案详情（脱敏输出）；PAT-1001（404）档案不存在 */
    PatientVO getDetail(long patientId);

    /** 患者检索（keyword 形态分派 + 脱敏分页），非空分页 */
    PageResult<PatientVO> search(PatientSearchQuery query);

    /** 主数据部分更新（MERGED 拒改）；返回变更字段清单（空清单=无变更静默返回） */
    List<String> update(long patientId, PatientUpdateRequest request);

    /** 冻结档案（NORMAL→FROZEN；拍板 2 最小 API）；PAT-1004/PAT-1005 守卫 */
    void freeze(long patientId, String reason);

    /** 解冻档案（FROZEN→NORMAL）；PAT-1005 非 FROZEN 不可解冻 */
    void unfreeze(long patientId);
}
