package com.fuyun.patient.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.entity.PossibleDuplicate;
import com.fuyun.patient.vo.PossibleDuplicateVO;

/**
 * 疑似重复治理服务（FU-M02-03）：待审列表、人工排除、建档实时生成、批量增量扫描四写读路径。
 */
public interface IPossibleDuplicateService extends IService<PossibleDuplicate> {

    /**
     * 待审列表（默认 PENDING；status 可选 ALL/PENDING/MERGED/EXCLUDED）。
     *
     * @param status 状态过滤词，非空；page 0 基；size 1-200
     * @return 脱敏无关（双 id 对），非空分页
     */
    PageResult<PossibleDuplicateVO> list(String status, int page, int size);

    /**
     * 排除待审对（PENDING→EXCLUDED；理由必填落 review_note；双人后置审批随合并链路）。
     *
     * @param id   待审行 id，非空
     * @param note 排除理由，非空
     * @throws com.fuyun.common.exception.BizException PAT-1009（404）/ PAT-1010（409 已审核）
     */
    void exclude(long id, String note);

    /**
     * 建档实时生成待审行（SUSPECT 结论回填点；a&lt;b 规范化，(a,b) 唯一兜底重复命中幂等静默）。
     *
     * @param newPatientId       新档 id，非空
     * @param candidatePatientId 命中候选 id，非空
     * @param check              匹配结论（评分与规则快照来源），非空
     */
    void recordSuspect(long newPatientId, long candidatePatientId, com.fuyun.patient.vo.PatientMatchCheckVO check);

    /**
     * 批量增量扫描（近 N 天新建/更新档与同名存量比对，达阈值生成待审；同一对患者只生成一条）。
     *
     * @return 本次新增待审行数（任务日志与运维核对锚点）
     */
    int scanBatch();
}
