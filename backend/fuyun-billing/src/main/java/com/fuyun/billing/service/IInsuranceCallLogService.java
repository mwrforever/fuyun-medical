package com.fuyun.billing.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.billing.entity.InsuranceCallLog;
import com.fuyun.common.web.PageResult;

/**
 * 医保业务调用留痕服务（billing.insurance_call_log，方案 3.2 两级日志之业务级）：悬挂确认/冲正/
 * 补偿驱动数据源；摘要列脱敏落库（禁完整明文入日志，等保要求），回执原文引用即该行 id（红线 5）。
 */
public interface IInsuranceCallLogService extends IService<InsuranceCallLog> {

    /**
     * 留痕行落库（调用方装配完整行：PR-3 模拟通道即时 SUCCESS 直落；P5 真实通道两段式先落
     * INIT/SENT 再回填终态）；response_digest 超列宽入库前钳 512 字符。
     *
     * @param row 留痕行，非空（txn_code/request_digest/status 必填）；来源：preview 等医保调用点
     */
    void record(InsuranceCallLog row);

    /**
     * 补偿重试（TIMEOUT/FAILED 行 → COMPENSATED，结论留痕）：retry 端点真实调用。PR-3 模拟通道
     * 零 IO 即时返回不产生 TIMEOUT 态，守卫语义随 P5 真实通道两段式（INIT/SENT→回填）悬挂补偿复用。
     *
     * @param id   留痕行 id，非空
     * @param note 补偿结论，非空白（compensate_note 留痕，审计检索键）
     * @throws com.fuyun.common.exception.BizException BILL-1025（404 留痕行不存在）/
     *                 BILL-1026（409 非 TIMEOUT/FAILED 行拒重试）
     */
    void compensate(long id, String note);

    /**
     * 留痕分页查询（visitId 可空=全部；id 升序=落库行序，运营排查与补偿工作台数据源）。
     *
     * @param visitId CF-3 就诊号，可空（空即不过滤）
     * @param page    页码（0 基）
     * @param size    单页条数（1-200）
     * @return 留痕行分页（实体出参，controller 侧经 VO 静态工厂出网），非空
     */
    PageResult<InsuranceCallLog> page(String visitId, int page, int size);
}
