package com.fuyun.nursing.service;

import com.fuyun.nursing.dto.IoRecordCreateRequest;
import com.fuyun.nursing.dto.IoSummaryCreateRequest;
import com.fuyun.nursing.vo.IoRecordVO;
import com.fuyun.nursing.vo.IoSummaryVO;
import java.time.LocalDate;
import java.util.List;

/**
 * 出入量域服务（V804 io_record/io_summary 业务面）：明细账（手工/PDA 录入）+ 班次小结与
 * 24 小时总结（调研依据 6：每班小结出入量，大夜班每 24 小时总结一次并记录在体温单相应栏内，
 * 红双线标识由前端渲染）。小结经同事务链落两处——io_summary 行 + 体温单 DAILY_VALUE 条目
 * （ITemperatureChartService#appendDailyValue，Task 4 冻结面）并回填条目引用；小结幂等键
 * (visit_id, summary_type, period_start, shift_key)，重复小结返回既有行。数量出入参 string
 * 承载、落库 BigDecimal NUMERIC（D-18）；业务时间一律服务器时间（GC25）。
 *
 * <p>线程安全：无状态 singleton；写操作 @Transactional 收口（实现侧）。
 */
public interface IIoRecordService {

    /**
     * 出入量明细录入：类型/项目/数据源词表校验（非法或项目与类型不一致 NS-1019）→ 数量解析
     * （非数字/非正数 NS-1019，NUMERIC(10,2) 规整）→ 在区校验（查无在区行定性 NS-1004）→
     * insert（occur_at=服务器时间，itemName 服务端按词表冗余落库）。
     *
     * @param req 录入入参，非空；来源：操作者工作站/PDA 表单
     * @return 明细行出参，非空
     * @throws BizException NS-1019（400 ioType/itemCode/source 词表外或预留源、数量非法）/
     *                      NS-1004（409 患者不在区）
     */
    IoRecordVO create(IoRecordCreateRequest req);

    /**
     * 按住院就诊号列出入量明细（发生时间升序）；date 非空时收敛为服务器时区当日窗口
     * [当日 00:00, 次日 00:00)。
     *
     * @param visitId 住院就诊号，非空；来源：查询参数
     * @param date    发生日（yyyy-MM-dd 语义），可空（空=全部）；来源：查询参数
     * @return 明细出参清单（无行返回空清单，非 null）；按发生时间升序
     */
    List<IoRecordVO> listByVisit(String visitId, LocalDate date);

    /**
     * 出入量小结（班次小结/24h 总结）四步链：①幂等检查（(visit_id, summary_type,
     * period_start, shift_key) 查既有，命中直接返回）→ ②聚合（sumByTypeAndPeriod 窗口
     * [from, to) 按类型求和，balance=总入量-总出量）→ ③insert 小结行（并发同周期唯一冲突
     * NS-1016 幂等拒绝，不覆盖首值）→ ④appendDailyValue 写体温单 DAILY_VALUE 条目并同事务
     * 回填 chart_entry_ref。SHIFT 周期取病区班次定义当日窗（shiftCode 必填且须在定义内）；
     * 24H 周期取当日 00:00–24:00（shiftCode 不承载，强制置空）。
     *
     * @param req 小结入参，非空；来源：操作者工作站表单
     * @return 小结行出参，非空
     * @throws BizException NS-1019（400 summaryType/shiftCode 非法或班次不在定义内）/
     *                      NS-1004（409 患者不在区）/ NS-1016（409 并发同周期小结冲突）
     */
    IoSummaryVO summarize(IoSummaryCreateRequest req);

    /**
     * 按住院就诊号列出入量小结（周期起升序）；date 非空时仅取 period_start 落在服务器时区
     * 当日窗口 [当日 00:00, 次日 00:00) 的小结。
     *
     * @param visitId 住院就诊号，非空；来源：查询参数
     * @param date    统计日（yyyy-MM-dd 语义），可空（空=全部）；来源：查询参数
     * @return 小结出参清单（无行返回空清单，非 null）；按周期起升序
     */
    List<IoSummaryVO> summaries(String visitId, LocalDate date);
}
