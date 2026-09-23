package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.dto.IoRecordCreateRequest;
import com.fuyun.nursing.dto.IoSummaryCreateRequest;
import com.fuyun.nursing.entity.IoRecord;
import com.fuyun.nursing.entity.IoSummary;
import com.fuyun.nursing.entity.TemperatureChartEntry;
import com.fuyun.nursing.enums.ChartEntryType;
import com.fuyun.nursing.enums.IoItemCode;
import com.fuyun.nursing.enums.IoSource;
import com.fuyun.nursing.enums.IoSummaryType;
import com.fuyun.nursing.enums.IoType;
import com.fuyun.nursing.mapper.IoRecordMapper;
import com.fuyun.nursing.mapper.IoSummaryMapper;
import com.fuyun.nursing.mapper.TemperatureChartEntryMapper;
import com.fuyun.nursing.service.IIoRecordService;
import com.fuyun.nursing.service.ITemperatureChartService;
import com.fuyun.nursing.service.IWardMetaService;
import com.fuyun.nursing.vo.IoRecordVO;
import com.fuyun.nursing.vo.IoSummaryVO;
import com.fuyun.nursing.vo.WardConfigVO;
import com.fuyun.nursing.vo.WardConfigVO.ShiftDefinition;
import com.fuyun.nursing.vo.WardPatientDetailVO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出入量域服务实现（V804 io_record/io_summary 业务面）。明细录入守卫链：类型/项目词表校验
 * （项目与类型一致性 NS-1019）→ 数据源校验（P1 生产者仅 MANUAL/PDA，预留源显式拒收防落卡
 * 后悬置）→ 数量解析（D-18 string 承载，NUMERIC(10,2) 规整）→ 在区校验（NS-1004 翻译）→
 * insert（明细账无唯一约束；occur_at/itemName 服务端权威——GC25 服务器时间 + 词表冗余落库，
 * shift_code/source_ref P1 手工链无写入方恒空）。summarize 四步链：①幂等前置检查
 * ((visit_id, summary_type, period_start, shift_key)，命中直接返回) → ②聚合（窗口 [from, to)
 * 按类型求和，balance=总入量-总出量）→ ③insert 小结行（并发同周期唯一冲突 NS-1016 幂等拒绝，
 * PG 同事务重查不可达不做冲突后回查）→ ④appendDailyValue 写体温单 DAILY_VALUE 条目并同事务
 * 回填 chart_entry_ref（失败上抛不吞，整体回滚）。SHIFT 周期取病区班次定义当日窗（跨零点
 * 班次环绕窗、「24:00」种子约定止于次日零点）；24H 周期取当日 00:00–24:00；数量一律
 * BigDecimal 且两位小数规整（D-18 VO 出参 toPlainString）。
 * 线程安全：无状态 singleton；写操作 @Transactional 收口。
 */
@Slf4j
public class IoRecordServiceImpl extends ServiceImpl<IoRecordMapper, IoRecord> implements IIoRecordService {

    /** 移出/系统链路等无登录上下文场景的操作者回退值（与 V804 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 缺省单位（V804 unit 列默认 'ml' 同源） */
    private static final String DEFAULT_UNIT = "ml";

    /** 班次定义结束时刻种子约定「24:00」（语义=次日零点，LocalTime 不可直接解析） */
    private static final String SHIFT_END_MIDNIGHT = "24:00";

    /** 出入量小结 mapper（小结行写入与幂等检查） */
    private final IoSummaryMapper summaryMapper;

    /** 体温单条目 mapper（DAILY_VALUE 条目引用回填定位） */
    private final TemperatureChartEntryMapper entryMapper;

    /** 病区元数据服务（在区校验与班次定义读取，Task 3 面） */
    private final IWardMetaService wardMetaService;

    /** 体温单服务（DAILY_VALUE 条目写入，Task 4 冻结面） */
    private final ITemperatureChartService chartService;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param recordMapper  出入量明细 mapper，非空；ServiceImpl 基座 mapper
     * @param summaryMapper 出入量小结 mapper，非空；小结行写入与幂等检查
     * @param entryMapper   体温单条目 mapper，非空；DAILY_VALUE 条目引用回填定位
     * @param wardMetaService 病区元数据服务，非空；在区校验与班次定义读取（Task 3 面）
     * @param chartService  体温单服务，非空；DAILY_VALUE 条目写入（Task 4 冻结面）
     */
    public IoRecordServiceImpl(
            IoRecordMapper recordMapper,
            IoSummaryMapper summaryMapper,
            TemperatureChartEntryMapper entryMapper,
            IWardMetaService wardMetaService,
            ITemperatureChartService chartService) {
        this.summaryMapper = summaryMapper;
        this.entryMapper = entryMapper;
        this.wardMetaService = wardMetaService;
        this.chartService = chartService;
    }

    /**
     * 出入量明细录入守卫链：①类型/项目词表校验（词表外或项目与类型不一致 NS-1019）→
     * ②数据源校验（空缺省 MANUAL；P1 生产者仅 MANUAL/PDA，INFUSION_AUTO 等预留源 P1 无
     * 处理路径显式拒 NS-1019，VitalSource.IOT 同款口径）→ ③数量解析（D-18 string 承载，
     * 非数字/非正数 NS-1019；NUMERIC(10,2) 两位小数规整）→ ④在区校验（detail 查无在区行
     * 定性 NS-1004 患者不在区，patient_id/ward_id 服务端装配不信客户端）→ ⑤insert
     * （occur_at=服务器时间 GC25、itemName=词表冗余展示名、班次/来源引用 P1 手工链恒空）。
     *
     * @param req 录入入参，非空；来源：操作者工作站/PDA 表单
     * @return 明细行出参，非空
     * @throws BizException NS-1019（400 ioType/itemCode/source 词表外或预留源、数量非法）/
     *                      NS-1004（409 患者不在区）
     */
    @Override
    @Transactional
    public IoRecordVO create(IoRecordCreateRequest req) {
        // 守卫链①：类型/项目词表校验（禁裸值入库，项目必须归属所录类型）
        IoType ioType = IoType.fromCode(req.ioType());
        if (ioType == null) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "出入量类型 code 非法：" + req.ioType());
        }
        IoItemCode item = IoItemCode.fromCode(req.itemCode());
        if (item == null) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "出入量项目 code 非法：" + req.itemCode());
        }
        if (item.getIoType() != ioType) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "项目与出入量类型不一致：itemCode=" + req.itemCode() + "，ioType=" + req.ioType());
        }
        // 守卫链②：数据源校验（预留源无 P1 写入方，显式拒收防落卡后悬置）
        IoSource source = req.source() == null ? IoSource.MANUAL : IoSource.fromCode(req.source());
        if (source == null) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "数据源 code 非法：" + req.source());
        }
        if (source != IoSource.MANUAL && source != IoSource.PDA) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "数据源 P1 无写入方（INFUSION_AUTO/TRANSFUSION_AUTO/ICU_AUTO/ICU_MANUAL 归 P2/P4）：" + req.source());
        }
        // 守卫链③：数量解析（D-18：string 承载入参 → NUMERIC(10,2) 规整落库）
        BigDecimal quantity = parseQuantity(req.quantity());
        // 守卫链④：在区校验（patient_id/ward_id 由在区行服务端装配）
        WardPatientDetailVO inWard = requireInWard(req.visitId());
        String operator = operator();
        IoRecord row = new IoRecord();
        row.setVisitId(req.visitId());
        row.setPatientId(inWard.patientId());
        row.setWardId(inWard.wardId());
        // GC25 红线：发生时间一律服务器时间（不设入参组件）
        row.setOccurAt(OffsetDateTime.now());
        row.setIoType(ioType.getCode());
        row.setItemCode(item.getCode());
        // itemName 服务端按词表冗余落库（字典未建时前端直显，禁客户端伪造展示名）
        row.setItemName(item.getDisplayName());
        row.setQuantity(quantity);
        row.setUnit(req.unit() == null || req.unit().isBlank() ? DEFAULT_UNIT : req.unit());
        row.setSource(source.getCode());
        row.setRecorderId(operator);
        row.setRemark(req.remark());
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        // 数据库写操作：出入量明细落库（明细账无唯一约束，无冲突翻译面）
        baseMapper.insert(row);
        log.info(
                "出入量明细录入：visitId={}，patientId={}，ioType={}，itemCode={}，quantity={} ml，source={}，operator={}",
                row.getVisitId(),
                row.getPatientId(),
                row.getIoType(),
                row.getItemCode(),
                row.getQuantity(),
                row.getSource(),
                operator);
        return IoRecordVO.from(row);
    }

    /**
     * 按住院就诊号列出入量明细（发生时间升序）；date 非空时收敛为服务器时区当日窗口
     * [当日 00:00, 次日 00:00)。
     *
     * @param visitId 住院就诊号，非空；来源：查询参数
     * @param date    发生日（yyyy-MM-dd 语义），可空（空=全部）；来源：查询参数
     * @return 明细出参清单（无行返回空清单，非 null）；按发生时间升序
     */
    @Override
    @Transactional(readOnly = true)
    public List<IoRecordVO> listByVisit(String visitId, LocalDate date) {
        var wrapper = Wrappers.<IoRecord>lambdaQuery().eq(IoRecord::getVisitId, visitId);
        if (date != null) {
            // 当日窗口（服务器时区）：含头不含尾，occur_at 为 TIMESTAMPTZ 边界安全
            wrapper.ge(IoRecord::getOccurAt, dayStart(date)).lt(IoRecord::getOccurAt, dayStart(date.plusDays(1)));
        }
        wrapper.orderByAsc(IoRecord::getOccurAt);
        // 数据库读操作：出入量明细清单（发生时间升序；逻辑删由 @TableLogic 自动过滤）
        return baseMapper.selectList(wrapper).stream().map(IoRecordVO::from).toList();
    }

    /**
     * 出入量小结四步链（简报冻结顺序）：守卫（小结类型词表 NS-1019 → 班次 code 规整——SHIFT
     * 必填、24H 强制置空使幂等键班次维度恒空串 → 在区校验 NS-1004 → 周期推导）→ ①幂等前置
     * 检查（uk_io_summary_period 四维查询，命中直接返回既有行——重复小结零重复写）→ ②聚合
     * （sumByTypeAndPeriod 窗口 [from, to) 按类型求和，缺型补零，balance=总入量-总出量）→
     * ③insert 小结行（并发同周期唯一冲突转 NS-1016 幂等拒绝，不覆盖首值，PG 同事务重查
     * 不可达不做冲突后回查）→ ④appendDailyValue 写体温单 DAILY_VALUE 条目（type_key=
     * 词表冻结日行值类型、值文本=「入 X / 出 Y / 平衡 Z」、红双线由前端渲染）并同事务回填
     * chart_entry_ref（条目写入失败上抛不吞，事务整体回滚）。
     *
     * @param req 小结入参，非空；来源：操作者工作站表单
     * @return 小结行出参，非空
     * @throws BizException NS-1019（400 summaryType/shiftCode 非法或班次不在定义内）/
     *                      NS-1004（409 患者不在区）/ NS-1016（409 并发同周期小结冲突、条目回读缺失）
     */
    @Override
    @Transactional
    public IoSummaryVO summarize(IoSummaryCreateRequest req) {
        // 守卫链①：小结类型词表校验
        IoSummaryType type = IoSummaryType.fromCode(req.summaryType());
        if (type == null) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "小结类型 code 非法：" + req.summaryType());
        }
        // 守卫链②：班次 code 规整——SHIFT 必填；24H 不承载班次（强制置空，幂等键第四维恒空串）
        String shiftCode = req.shiftCode() == null || req.shiftCode().isBlank() ? null : req.shiftCode();
        if (type == IoSummaryType.SHIFT && shiftCode == null) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "班次小结必须指定班次 code：visitId=" + req.visitId());
        }
        if (type == IoSummaryType.HOURS_24) {
            shiftCode = null;
        }
        // 守卫链③：在区校验（patient_id/ward_id 由在区行服务端装配）
        WardPatientDetailVO inWard = requireInWard(req.visitId());
        // 周期推导（服务器时区）：SHIFT=病区班次定义当日窗；24H=当日 00:00–次日 00:00 全天
        OffsetDateTime from;
        OffsetDateTime to;
        if (type == IoSummaryType.SHIFT) {
            ShiftPeriod period = resolveShiftPeriod(wardMetaService.wardConfig(inWard.wardId()), shiftCode);
            from = period.from();
            to = period.to();
        } else {
            // 24H 全天窗：当日零点单次取钟（防两次取钟跨零点漂移），止于次日零点
            LocalDate today = LocalDate.now();
            from = dayStart(today);
            to = dayStart(today.plusDays(1));
        }
        String operator = operator();
        // 步骤①：幂等前置检查——(visit_id, summary_type, period_start, shift_key) 命中直接返回
        IoSummary existing = summaryMapper.selectOne(Wrappers.<IoSummary>lambdaQuery()
                .eq(IoSummary::getVisitId, req.visitId())
                .eq(IoSummary::getSummaryType, type.getCode())
                .eq(IoSummary::getPeriodStart, from)
                .eq(shiftCode != null, IoSummary::getShiftCode, shiftCode)
                .isNull(shiftCode == null, IoSummary::getShiftCode));
        if (existing != null) {
            log.info(
                    "出入量小结幂等命中：visitId={}，summaryType={}，periodStart={}，shiftCode={}，id={}",
                    req.visitId(),
                    type.getCode(),
                    from,
                    shiftCode,
                    existing.getId());
            return IoSummaryVO.from(existing);
        }
        // 步骤②：聚合——窗口 [from, to) 按类型求和（缺型补零，两位小数规整）
        BigDecimal intake = BigDecimal.ZERO;
        BigDecimal output = BigDecimal.ZERO;
        for (IoRecord sum : baseMapper.sumByTypeAndPeriod(req.visitId(), from, to)) {
            if (IoType.INTAKE.getCode().equals(sum.getIoType()) && sum.getQuantity() != null) {
                intake = sum.getQuantity();
            } else if (IoType.OUTPUT.getCode().equals(sum.getIoType()) && sum.getQuantity() != null) {
                output = sum.getQuantity();
            }
        }
        intake = intake.setScale(2, RoundingMode.HALF_UP);
        output = output.setScale(2, RoundingMode.HALF_UP);
        BigDecimal balance = intake.subtract(output);
        // 步骤③：小结行落库（chart_entry_ref 待步骤④回填；uk_io_summary_period 兜底并发）
        IoSummary row = new IoSummary();
        row.setVisitId(req.visitId());
        row.setPatientId(inWard.patientId());
        row.setWardId(inWard.wardId());
        row.setSummaryType(type.getCode());
        row.setPeriodStart(from);
        row.setPeriodEnd(to);
        row.setTotalIntake(intake);
        row.setTotalOutput(output);
        row.setBalance(balance);
        row.setShiftCode(shiftCode);
        row.setRecorderId(operator);
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        try {
            // 数据库写操作：小结行落库（并发同周期唯一索引兜底）
            summaryMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发同周期冲突：PG 同事务内语句失败即事务 aborted（重查抛 25P02 不可达），直接
            // 转 NS-1016 幂等拒绝，不覆盖首值
            throw new BizException(
                    NursingErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "同周期出入量小结已存在（幂等拒绝，不覆盖首值）：visitId=" + req.visitId() + "，summaryType=" + type.getCode()
                            + "，periodStart=" + from + "，shiftCode=" + shiftCode);
        }
        // 步骤④：体温单 DAILY_VALUE 条目写入 + 条目引用同事务回填（失败上抛不吞，整体回滚）
        String valueText =
                "入 " + intake.toPlainString() + " / 出 " + output.toPlainString() + " / 平衡 " + balance.toPlainString();
        Long pageId = chartService.ensurePage(req.visitId(), YearMonth.now());
        chartService.appendDailyValue(req.visitId(), type.getChartValueType(), valueText, operator);
        TemperatureChartEntry entry = requireWrittenEntry(pageId, type.getChartValueType(), valueText, operator);
        row.setChartEntryRef(entry.getId());
        // 数据库写操作：chart_entry_ref 引用回填（引用回填类更新，无正文覆盖语义）
        summaryMapper.updateById(row);
        log.info(
                "出入量小结生成：visitId={}，patientId={}，summaryType={}，periodStart={}，periodEnd={}，shiftCode={}，"
                        + "intake={}，output={}，balance={}，chartEntryRef={}，operator={}",
                row.getVisitId(),
                row.getPatientId(),
                row.getSummaryType(),
                row.getPeriodStart(),
                row.getPeriodEnd(),
                row.getShiftCode(),
                intake,
                output,
                balance,
                row.getChartEntryRef(),
                operator);
        return IoSummaryVO.from(row);
    }

    /**
     * 按住院就诊号列出入量小结（周期起升序）；date 非空时仅取 period_start 落在服务器时区
     * 当日窗口 [当日 00:00, 次日 00:00) 的小结。
     *
     * @param visitId 住院就诊号，非空；来源：查询参数
     * @param date    统计日（yyyy-MM-dd 语义），可空（空=全部）；来源：查询参数
     * @return 小结出参清单（无行返回空清单，非 null）；按周期起升序
     */
    @Override
    @Transactional(readOnly = true)
    public List<IoSummaryVO> summaries(String visitId, LocalDate date) {
        var wrapper = Wrappers.<IoSummary>lambdaQuery().eq(IoSummary::getVisitId, visitId);
        if (date != null) {
            // 当日窗口（服务器时区）：period_start 含头不含尾
            wrapper.ge(IoSummary::getPeriodStart, dayStart(date))
                    .lt(IoSummary::getPeriodStart, dayStart(date.plusDays(1)));
        }
        wrapper.orderByAsc(IoSummary::getPeriodStart);
        // 数据库读操作：出入量小结清单（周期起升序；逻辑删由 @TableLogic 自动过滤）
        return summaryMapper.selectList(wrapper).stream().map(IoSummaryVO::from).toList();
    }

    /**
     * 班次统计周期推导（服务器时区当日窗）：start &lt; end 同日窗 [今日 start, 今日 end)；
     * 「24:00」种子约定窗止=次日 00:00（如 EVENING 16:00–24:00 → [今日 16:00, 次日 00:00)）；
     * start &gt;= end 跨零点环绕窗（如 22:00–06:00 → [昨日 22:00, 今日 06:00)）。
     *
     * @param config    病区护理配置（shift_definitions 结构化面），非空
     * @param shiftCode 班次 code，非空
     * @return 统计周期（from 含 / to 不含），非空
     * @throws BizException   NS-1019（400 班次 code 不在病区班次定义内）
     * @throws IllegalStateException 班次时刻文本解析失败（服务端配置数据异常，WardMetaServiceImpl 同款）
     */
    private ShiftPeriod resolveShiftPeriod(WardConfigVO config, String shiftCode) {
        ShiftDefinition definition = config.shifts() == null
                ? null
                : config.shifts().stream()
                        .filter(s -> shiftCode.equals(s.code()))
                        .findFirst()
                        .orElse(null);
        if (definition == null) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "病区班次定义中不存在该班次：shiftCode=" + shiftCode);
        }
        LocalDate today = LocalDate.now();
        try {
            LocalTime start = LocalTime.parse(definition.start());
            LocalDate fromDate = today;
            LocalDate toDate = today;
            LocalTime end;
            if (SHIFT_END_MIDNIGHT.equals(definition.end())) {
                // 「24:00」种子约定：窗口止=次日零点（LocalTime 不可直接解析）
                end = LocalTime.MIDNIGHT;
                toDate = today.plusDays(1);
            } else {
                end = LocalTime.parse(definition.end());
                if (!start.isBefore(end)) {
                    // 跨零点环绕班次：统计周期自昨日 start 起至今日 end 止
                    fromDate = today.minusDays(1);
                }
            }
            return new ShiftPeriod(
                    fromDate.atTime(start).atZone(ZoneId.systemDefault()).toOffsetDateTime(),
                    toDate.atTime(end).atZone(ZoneId.systemDefault()).toOffsetDateTime());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("病区配置班次时刻解析失败：shift=" + definition.code(), e);
        }
    }

    /** 班次统计周期值对象（from 含 / to 不含）。 */
    private record ShiftPeriod(OffsetDateTime from, OffsetDateTime to) {}

    /**
     * 数量解析与规整（D-18：string 承载入参 → NUMERIC(10,2)）：空白/非数字/非正数显式拒
     * NS-1019（禁裸 parse，W-22⑦ 先例）；两位小数规整与列标度一致。
     *
     * @param text 数量数字文本，非空；来源：录入入参
     * @return 两位小数正数量，非空
     * @throws BizException NS-1019（400 空白/非数字文本/非正数）
     */
    private BigDecimal parseQuantity(String text) {
        if (text == null || text.isBlank()) {
            throw new BizException(NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "数量不能为空");
        }
        BigDecimal quantity;
        try {
            quantity = new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            throw new BizException(NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "数量必须为数字文本：" + text);
        }
        if (quantity.signum() <= 0) {
            throw new BizException(NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "数量必须为正数：" + text);
        }
        return quantity.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 定位刚写入的体温单日行值条目（chart_entry_ref 回填锚）：同事务内自身插入可见，按
     * （月页 + DAILY_VALUE + type_key + 值文本 + 记录人）锚定取最新条目；回读缺失定性 NS-1016。
     *
     * @param pageId    体温单月页 id，非空
     * @param typeKey   日行值类型键，非空
     * @param valueText 值文本，非空
     * @param operator  记录人，非空
     * @return 体温单条目，非空
     * @throws BizException NS-1016（409 条目回读缺失——引用不落悬置值）
     */
    private TemperatureChartEntry requireWrittenEntry(Long pageId, String typeKey, String valueText, String operator) {
        TemperatureChartEntry entry = entryMapper.selectOne(Wrappers.<TemperatureChartEntry>lambdaQuery()
                .eq(TemperatureChartEntry::getPageId, pageId)
                .eq(TemperatureChartEntry::getEntryType, ChartEntryType.DAILY_VALUE.getCode())
                .eq(TemperatureChartEntry::getTypeKey, typeKey)
                .eq(TemperatureChartEntry::getValueText, valueText)
                .eq(TemperatureChartEntry::getRecorderId, operator)
                .orderByDesc(TemperatureChartEntry::getEntryTime)
                .last("LIMIT 1"));
        if (entry == null) {
            throw new BizException(
                    NursingErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "体温单日行值条目回读缺失：pageId=" + pageId + "，typeKey=" + typeKey);
        }
        return entry;
    }

    /** 按 visit_id 校验在区（IWardMetaService detail；查无在区行定性 NS-1004 患者不在区）。 */
    private WardPatientDetailVO requireInWard(String visitId) {
        try {
            return wardMetaService.detail(visitId);
        } catch (BizException e) {
            // 在区行不存在（detail 定性 NS-1001）→ 出入量域语境转 NS-1004 患者不在区
            if (NursingErrorCode.WARD_PATIENT_NOT_FOUND.equals(e.getErrorCode())) {
                throw new BizException(
                        NursingErrorCode.PATIENT_BLOCKED, HttpStatus.CONFLICT, "患者不在区，禁止出入量操作：visitId=" + visitId);
            }
            throw e;
        }
    }

    /** 服务器时区当日零点（当日窗口含头边界）。 */
    private OffsetDateTime dayStart(LocalDate date) {
        return date.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
    }

    /** 操作者取值（免登录上下文回退 system，与审计列默认同源）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
