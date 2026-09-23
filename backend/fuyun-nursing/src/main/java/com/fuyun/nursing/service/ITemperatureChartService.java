package com.fuyun.nursing.service;

import com.fuyun.nursing.dto.SpecialEventRequest;
import com.fuyun.nursing.vo.ChartEntryVO;
import com.fuyun.nursing.vo.TemperatureChartVO;
import java.time.Instant;
import java.time.YearMonth;

/**
 * 体温单域服务（V802 temperature_chart_page/entry 业务面；Task 5 消费 appendVitalEntry/
 * ensurePage、Task 6 消费 appendDailyValue 冻结面）。条目唯一形态：(page_id, entry_time,
 * entry_type, type_key) 唯一——type_key 由服务按条目类型写入（VITAL=体温部位（空部位落空串）/
 * SPECIAL_EVENT=事件类型 / DAILY_VALUE=日行值类型），使同刻不同体温部位可并存；唯一冲突
 * 一律 NS-1016 幂等拒绝（不覆盖首值）。
 *
 * <p>线程安全：无状态 singleton；写操作 @Transactional 收口（实现侧）。
 */
public interface ITemperatureChartService {

    /**
     * 体温单月页查询：月页定位 + 三类条目三段分组（各段按 entryTime 升序，服务端排序）。
     * month 解析失败（非 yyyy-MM）拒 NS-1019；月页未创建返回空出参（三段空清单）。
     *
     * @param visitId 住院就诊号，非空；来源：查询参数
     * @param month   体温单月页（yyyy-MM），非空；来源：查询参数
     * @return 月页出参，非空
     * @throws BizException NS-1019（400 month 格式非法）
     */
    TemperatureChartVO getChart(String visitId, String month);

    /**
     * 特殊事件录入（SPECIAL_EVENT 条目）：事件类型显式格式校验（非法值 NS-1019）→
     * ensurePage → insert（type_key=事件类型，entry_time=服务器时间）。
     *
     * @param visitId 住院就诊号，非空；来源：路径参数
     * @param req     事件入参，非空；来源：操作者工作站表单
     * @return 事件条目出参，非空
     * @throws BizException NS-1019（400 事件类型 code 非法）/ NS-1016（409 条目唯一冲突）
     */
    ChartEntryVO addSpecialEvent(String visitId, SpecialEventRequest req);

    /**
     * 体征条目写入（Task 5 体征转正消费冻结面）：ensurePage + insert（entry_type=VITAL、
     * type_key=COALESCE(tempSite,'') 空部位落空串）；同键重复写入（事件重放）唯一冲突转
     * NS-1016 幂等拒绝（不覆盖首值）。entryTime 为体征测量时点（体征域服务端权威时间）。
     *
     * @param visitId  住院就诊号，非空；来源：体征记录链
     * @param entryTime 体征测量时点，非空；来源：体征域测量时间
     * @param vitalRef  体征记录引用（vital_sign_record.id），非空；来源：体征域转正行
     * @param tempSite  体温部位 code（AXILLARY/ORAL/RECTAL；空=未测体温落空串键），可空
     * @throws BizException NS-1016（409 同键条目已存在，幂等拒绝）
     */
    void appendVitalEntry(String visitId, Instant entryTime, Long vitalRef, String tempSite);

    /**
     * 日行值写入（Task 6 出入量小结/24h 总结消费冻结面）：ensurePage + insert
     * （entry_type=DAILY_VALUE、type_key=dailyValueType、entry_time=服务器时间）。
     *
     * @param visitId        住院就诊号，非空；来源：小结链
     * @param dailyValueType 日行值类型（如 IO_SUMMARY_24H），非空；来源：小结域
     * @param valueText      日行值文本（如「入 2500 / 出 2100」），非空；来源：小结域
     * @param recorderId     记录人业务标识，非空；来源：小结操作者
     * @throws BizException NS-1019（400 类型/文本空白）/ NS-1016（409 条目唯一冲突）
     */
    void appendDailyValue(String visitId, String dailyValueType, String valueText, String recorderId);

    /**
     * 月页自动创建/取回：(visit_id, chart_month) 查—无则 insert；并发建页命中唯一索引冲突时
     * 转 NS-1016 幂等拒绝（调用方整单重试语义——重试时首查即取回既有页）。
     *
     * @param visitId 住院就诊号，非空
     * @param month   住院月页，非空
     * @return 月页 id，非空
     * @throws BizException NS-1016（409 并发建页同键冲突）
     */
    Long ensurePage(String visitId, YearMonth month);
}
