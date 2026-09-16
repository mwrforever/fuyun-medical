package com.fuyun.patient.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.patient.dto.MergeCreateRequest;
import com.fuyun.patient.entity.MergeRecord;
import com.fuyun.patient.vo.MergeRecordVO;

/**
 * 合并/拆分状态机服务（FU-M02-03，M02 §3.3 指针映射 + §5 merge_record 状态机）。
 */
public interface IMergeRecordService extends IService<MergeRecord> {

    /**
     * 发起合并（建 PROCESSING 记录；经办人取当前操作人）。
     *
     * @param request 发起请求（@Valid），非空
     * @return 合并记录出参，非空
     * @throws com.fuyun.common.exception.BizException PAT-1001（任一方档案不存在）/ PAT-1003（从档已 MERGED）/
     *                                                  PAT-1005（主档 FROZEN 不可作为主档）/ PAT-1008（主从同一档案）
     */
    MergeRecordVO create(MergeCreateRequest request);

    /**
     * 审批并执行合并（双人角色：与发起人不得同人；SPI 在途检查 → 快照 → 补齐 → 重挂 → MERGED →
     * COMPLETED → 缓存失效 → patient.merged；FAILED 记录可重试回 PROCESSING 再执行）。
     *
     * @param id       合并记录 id，非空
     * @param operator 审批操作人（OperatorContextHolder 当前操作人），非空
     * @return 合并记录出参，非空
     * @throws com.fuyun.common.exception.BizException PAT-1006（在途就诊阻断）/ PAT-1007/PAT-1008
     */
    MergeRecordVO approve(long id, String operator);

    /**
     * 拆分恢复（COMPLETED→REVERSED 终态；从档 NORMAL、标识按快照回挂、patient.split、缓存失效）。
     *
     * @param id     合并记录 id，非空
     * @param reason 拆分原因，非空
     * @return 合并记录出参，非空
     * @throws com.fuyun.common.exception.BizException PAT-1007/PAT-1008
     */
    MergeRecordVO split(long id, String reason);
}
