package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.dto.SpecialEventRequest;
import com.fuyun.nursing.entity.TemperatureChartEntry;
import com.fuyun.nursing.entity.TemperatureChartPage;
import com.fuyun.nursing.enums.ChartEntryType;
import com.fuyun.nursing.enums.ChartPageStatus;
import com.fuyun.nursing.enums.SpecialEventType;
import com.fuyun.nursing.mapper.TemperatureChartEntryMapper;
import com.fuyun.nursing.mapper.TemperatureChartPageMapper;
import com.fuyun.nursing.service.ITemperatureChartService;
import com.fuyun.nursing.vo.ChartEntryVO;
import com.fuyun.nursing.vo.TemperatureChartVO;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 体温单域服务实现（V802 temperature_chart_page/entry 业务面）。ensurePage 查—无则插 + 唯一
 * 索引冲突重查兜底并发建页；条目写入主链为 insert（type_key 由服务按条目类型写入：VITAL=体温
 * 部位（空部位落空串）/ SPECIAL_EVENT=事件类型 / DAILY_VALUE=日行值类型），唯一约束冲突一律
 * 转 NS-1016 幂等拒绝（不覆盖首值）；文书业务时间一律服务器时间（GC25，vital 条目时点取体征域
 * 权威测量时点）。getChart 三段分组各自按 entryTime 升序（服务端排序兜底，前端直渲染）。
 * 线程安全：无状态 singleton；写操作 @Transactional 收口。
 */
@Slf4j
public class TemperatureChartServiceImpl extends ServiceImpl<TemperatureChartPageMapper, TemperatureChartPage>
        implements ITemperatureChartService {

    /** 移出/系统链路等无登录上下文场景的操作者回退值（与 V802 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    private final TemperatureChartEntryMapper entryMapper;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param pageMapper  体温单月页 mapper，非空；ServiceImpl 基座 mapper
     * @param entryMapper 体温单条目 mapper，非空；条目写入与引用回填
     */
    public TemperatureChartServiceImpl(TemperatureChartPageMapper pageMapper, TemperatureChartEntryMapper entryMapper) {
        this.entryMapper = entryMapper;
    }

    /**
     * 体温单月页查询：month 显式解析（禁裸 parse，格式违例 NS-1019）→ 月页定位（未创建返回
     * 空出参）→ 条目按页取全后三段分组、各段按 entryTime 升序（id 兜底稳定序）。
     *
     * @param visitId 住院就诊号，非空；来源：查询参数
     * @param month   体温单月页（yyyy-MM），非空；来源：查询参数
     * @return 月页出参，非空（三段恒非 null）
     * @throws BizException NS-1019（400 month 格式非法）
     */
    @Override
    @Transactional(readOnly = true)
    public TemperatureChartVO getChart(String visitId, String month) {
        YearMonth chartMonth = parseMonth(month);
        TemperatureChartPage page = locatePage(visitId, chartMonth);
        if (page == null) {
            // 月页未创建：空出参（三段空清单），不视作错误（未产生过条目属常态）
            return new TemperatureChartVO(visitId, chartMonth.toString(), null, null, List.of(), List.of(), List.of());
        }
        // 数据库读操作：整页条目（entry_type 分组在服务端完成；排序兜底保证 VO 段内升序）
        List<TemperatureChartEntry> entries = entryMapper.selectList(
                Wrappers.<TemperatureChartEntry>lambdaQuery().eq(TemperatureChartEntry::getPageId, page.getId()));
        List<ChartEntryVO> sorted = entries.stream()
                .sorted(Comparator.comparing(TemperatureChartEntry::getEntryTime)
                        .thenComparing(TemperatureChartEntry::getId))
                .map(ChartEntryVO::from)
                .toList();
        List<ChartEntryVO> vitals = new ArrayList<>();
        List<ChartEntryVO> specialEvents = new ArrayList<>();
        List<ChartEntryVO> dailyValues = new ArrayList<>();
        for (ChartEntryVO entry : sorted) {
            if (ChartEntryType.VITAL.getCode().equals(entry.entryType())) {
                vitals.add(entry);
            } else if (ChartEntryType.SPECIAL_EVENT.getCode().equals(entry.entryType())) {
                specialEvents.add(entry);
            } else {
                dailyValues.add(entry);
            }
        }
        log.info(
                "体温单查询：visitId={}，chartMonth={}，vitals={}，specialEvents={}，dailyValues={}",
                visitId,
                chartMonth,
                vitals.size(),
                specialEvents.size(),
                dailyValues.size());
        return new TemperatureChartVO(
                visitId, page.getChartMonth(), page.getId(), page.getStatus(), vitals, specialEvents, dailyValues);
    }

    /**
     * 特殊事件录入（SPECIAL_EVENT 条目）：事件类型显式格式校验（非法值 NS-1019）→ ensurePage →
     * insert（type_key=事件类型，entry_time=服务器时间 GC25）→ 唯一冲突转 NS-1016 幂等拒绝。
     *
     * @param visitId 住院就诊号，非空；来源：路径参数
     * @param req     事件入参，非空；来源：操作者工作站表单
     * @return 事件条目出参，非空
     * @throws BizException NS-1019（400 事件类型 code 非法）/ NS-1016（409 条目唯一冲突）
     */
    @Override
    @Transactional
    public ChartEntryVO addSpecialEvent(String visitId, SpecialEventRequest req) {
        SpecialEventType eventType = SpecialEventType.fromCode(req.eventType());
        if (eventType == null) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "特殊事件类型 code 非法：" + req.eventType());
        }
        String operator = operator();
        TemperatureChartEntry entry = new TemperatureChartEntry();
        entry.setPageId(ensurePage(visitId, YearMonth.now()));
        entry.setEntryTime(OffsetDateTime.now());
        entry.setEntryType(ChartEntryType.SPECIAL_EVENT.getCode());
        entry.setSpecialEventType(eventType.getCode());
        entry.setTypeKey(eventType.getCode());
        entry.setRemark(req.remark());
        entry.setRecorderId(operator);
        entry.setCreatedBy(operator);
        entry.setUpdatedBy(operator);
        insertEntryOrConflict(entry, visitId);
        log.info("体温单特殊事件录入：visitId={}，eventType={}，operator={}", visitId, eventType.getCode(), operator);
        return ChartEntryVO.from(entry);
    }

    /**
     * 体征条目写入（Task 5 体征转正消费冻结面）：ensurePage（月页取条目时点所在月）+ insert
     * （entry_type=VITAL、type_key=COALESCE(tempSite,'')——空部位落空串键，使同刻不同体温部位
     * 可并存且未测体温条目同刻唯一）；同键重复写入（事件重放）唯一冲突转 NS-1016 幂等拒绝。
     *
     * @param visitId   住院就诊号，非空；来源：体征记录链
     * @param entryTime 体征测量时点，非空；来源：体征域测量时间（服务端权威）
     * @param vitalRef  体征记录引用（vital_sign_record.id），非空；来源：体征域转正行
     * @param tempSite  体温部位 code（AXILLARY/ORAL/RECTAL；空=未测体温落空串键），可空
     * @throws BizException NS-1016（409 同键条目已存在，幂等拒绝）
     */
    @Override
    @Transactional
    public void appendVitalEntry(String visitId, Instant entryTime, Long vitalRef, String tempSite) {
        TemperatureChartEntry entry = new TemperatureChartEntry();
        entry.setPageId(
                ensurePage(visitId, YearMonth.from(LocalDateTime.ofInstant(entryTime, ZoneId.systemDefault()))));
        entry.setEntryTime(OffsetDateTime.ofInstant(entryTime, ZoneId.systemDefault()));
        entry.setEntryType(ChartEntryType.VITAL.getCode());
        entry.setVitalRef(vitalRef);
        entry.setTypeKey(tempSite == null ? "" : tempSite);
        insertEntryOrConflict(entry, visitId);
        log.info("体温单体征条目写入：visitId={}，entryTime={}，vitalRef={}，tempSite={}", visitId, entryTime, vitalRef, tempSite);
    }

    /**
     * 日行值写入（Task 6 出入量小结/24h 总结消费冻结面）：类型/文本空白显式拒 NS-1019 →
     * ensurePage + insert（entry_type=DAILY_VALUE、type_key=dailyValueType、entry_time=服务器
     * 时间）→ 唯一冲突转 NS-1016 幂等拒绝。
     *
     * @param visitId        住院就诊号，非空；来源：小结链
     * @param dailyValueType 日行值类型（如 IO_SUMMARY_24H），非空；来源：小结域
     * @param valueText      日行值文本（如「入 2500 / 出 2100」），非空；来源：小结域
     * @param recorderId     记录人业务标识，非空；来源：小结操作者
     * @throws BizException NS-1019（400 类型/文本空白）/ NS-1016（409 条目唯一冲突）
     */
    @Override
    @Transactional
    public void appendDailyValue(String visitId, String dailyValueType, String valueText, String recorderId) {
        if (dailyValueType == null || dailyValueType.isBlank() || valueText == null || valueText.isBlank()) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "日行值类型与文本不能为空：visitId=" + visitId + "，dailyValueType=" + dailyValueType);
        }
        TemperatureChartEntry entry = new TemperatureChartEntry();
        entry.setPageId(ensurePage(visitId, YearMonth.now()));
        entry.setEntryTime(OffsetDateTime.now());
        entry.setEntryType(ChartEntryType.DAILY_VALUE.getCode());
        entry.setDailyValueType(dailyValueType);
        entry.setTypeKey(dailyValueType);
        entry.setValueText(valueText);
        entry.setRecorderId(recorderId);
        entry.setCreatedBy(recorderId);
        entry.setUpdatedBy(recorderId);
        insertEntryOrConflict(entry, visitId);
        log.info(
                "体温单日行值写入：visitId={}，dailyValueType={}，valueText={}，recorderId={}",
                visitId,
                dailyValueType,
                valueText,
                recorderId);
    }

    /**
     * 月页自动创建/取回：(visit_id, chart_month) 查—无则 insert；并发建页命中唯一索引冲突时
     * 重查兜底取回既有页（重查仍无定性 NS-1016，防御性不可达分支）。
     *
     * @param visitId 住院就诊号，非空
     * @param month   住院月页，非空
     * @return 月页 id，非空
     * @throws BizException NS-1016（409 并发建页兜底重查仍失败）
     */
    @Override
    @Transactional
    public Long ensurePage(String visitId, YearMonth month) {
        TemperatureChartPage existing = locatePage(visitId, month);
        if (existing != null) {
            return existing.getId();
        }
        TemperatureChartPage page = new TemperatureChartPage();
        page.setVisitId(visitId);
        page.setChartMonth(month.toString());
        page.setStatus(ChartPageStatus.ACTIVE.getCode());
        page.setCreatedBy(SYSTEM_OPERATOR);
        page.setUpdatedBy(SYSTEM_OPERATOR);
        try {
            // 数据库写操作：月页创建（uk_chart_page_visit_month 兜底并发）
            baseMapper.insert(page);
        } catch (DuplicateKeyException e) {
            // 并发建页兜底：唯一索引冲突重查取回既有页（幂等语义，不视为错误）
            TemperatureChartPage concurrent = locatePage(visitId, month);
            if (concurrent == null) {
                throw new BizException(
                        NursingErrorCode.CONFLICT,
                        HttpStatus.CONFLICT,
                        "体温单月页并发创建冲突且重查失败：visitId=" + visitId + "，chartMonth=" + month);
            }
            return concurrent.getId();
        }
        log.info("体温单月页创建：visitId={}，chartMonth={}", visitId, page.getChartMonth());
        return page.getId();
    }

    /** 条目写入 + 唯一冲突翻译：uk_chart_entry_key 冲突转 NS-1016 幂等拒绝（不覆盖首值）。 */
    private void insertEntryOrConflict(TemperatureChartEntry entry, String visitId) {
        try {
            // 数据库写操作：体温单条目落库（(page_id, entry_time, entry_type, type_key) 唯一兜底）
            entryMapper.insert(entry);
        } catch (DuplicateKeyException e) {
            throw new BizException(
                    NursingErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "体温单条目已存在（幂等拒绝，不覆盖首值）：visitId=" + visitId + "，entryType=" + entry.getEntryType() + "，typeKey="
                            + entry.getTypeKey() + "，entryTime=" + entry.getEntryTime());
        }
    }

    /** 月页定位（逻辑删由 @TableLogic 自动过滤；未命中返回 null 交调用方定性）。 */
    private TemperatureChartPage locatePage(String visitId, YearMonth month) {
        return baseMapper.selectOne(Wrappers.<TemperatureChartPage>lambdaQuery()
                .eq(TemperatureChartPage::getVisitId, visitId)
                .eq(TemperatureChartPage::getChartMonth, month.toString()));
    }

    /** month 参数权威解析（禁裸 parse 先例 W-22⑦：格式违例显式拒 NS-1019）。 */
    private YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "month 格式必须为 yyyy-MM：" + month);
        }
    }

    /** 操作者取值（免登录上下文回退 system，与审计列默认同源）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
