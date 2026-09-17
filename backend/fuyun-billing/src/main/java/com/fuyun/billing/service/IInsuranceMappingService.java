package com.fuyun.billing.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.billing.dto.InsuranceMappingUpsertRequest;
import com.fuyun.billing.entity.InsuranceMapping;

/**
 * 医保对照服务（billing.insurance_mapping，FU-M13-01 贯标载体）：项目级 22 项国家编码对照的
 * 生效行查询（贯标硬校验与取价快照消费）与单 ACTIVE 行 upsert 登记。
 */
public interface IInsuranceMappingService extends IService<InsuranceMapping> {

    /**
     * 取项目当前 ACTIVE 对照（Task 10 取价快照消费——nhsaCode/selfPayRatio/limitPrice 进快照；
     * Task 12 preview 医保分支贯标硬校验消费——BILL-1006 守卫数据前提）。
     *
     * @param chargeItemId 收费项目 id，非空
     * @return ACTIVE 对照行；未贯标或对照已失效返回 null
     */
    InsuranceMapping effectiveMapping(long chargeItemId);

    /**
     * 对照登记（upsert）：存在 ACTIVE 行则原行改写为最新对照，无则插入新 ACTIVE 行。
     *
     * @param req 对照登记请求，非空；来源：物价员对照国家目录录入
     * @return 落库对照行 id（改写为原行 id，新插为回填雪花 id）
     */
    long upsert(InsuranceMappingUpsertRequest req);
}
