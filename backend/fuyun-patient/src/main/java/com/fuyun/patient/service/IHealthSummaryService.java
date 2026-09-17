package com.fuyun.patient.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.patient.dto.HealthItemCorrectRequest;
import com.fuyun.patient.dto.HealthItemCreateRequest;
import com.fuyun.patient.entity.HealthSummary;
import com.fuyun.patient.vo.HealthItemVO;
import com.fuyun.patient.vo.HealthSummaryVO;

/**
 * 健康档案服务（FU-M02-05）：摘要/明细维护 + 纠错留痕 + api 过敏校验实现。
 */
public interface IHealthSummaryService extends IService<HealthSummary>, com.fuyun.patient.api.AllergyChecker {

    /**
     * 取患者健康档案（无聚合行返回空摘要非 null）。
     *
     * @param patientId 患者主索引，非空
     * @return 摘要出参（items 含全部状态项供留痕展示），非空
     */
    HealthSummaryVO getSummary(long patientId);

    /**
     * 新增健康档案项（刷新 summary_updated_at + health-summary.updated 事件）。
     *
     * @param patientId 患者主索引，非空
     * @param request   新增请求（@Valid），非空
     * @return 明细出参，非空
     * @throws com.fuyun.common.exception.BizException PAT-1001（档案不存在）
     */
    HealthItemVO addItem(long patientId, HealthItemCreateRequest request);

    /**
     * 纠错（旧行置 CORRECTED + 新行回链；事件同 addItem）。
     *
     * @param itemId  被纠错明细 id，非空
     * @param request 纠错请求（@Valid），非空
     * @return 新明细出参，非空
     * @throws com.fuyun.common.exception.BizException PAT-1017（明细不存在）/ PAT-1010 语义复用禁止——
     *                                                  已纠错行再纠错允许（以最新纠错为准），不设状态守卫
     */
    HealthItemVO correct(long itemId, HealthItemCorrectRequest request);
}
