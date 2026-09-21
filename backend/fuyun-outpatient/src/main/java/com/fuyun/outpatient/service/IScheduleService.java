package com.fuyun.outpatient.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.outpatient.dto.ScheduleGenerateRequest;
import com.fuyun.outpatient.dto.SchedulePageQuery;
import com.fuyun.outpatient.dto.ScheduleTemplateSaveRequest;
import com.fuyun.outpatient.enums.ApptType;
import com.fuyun.outpatient.vo.NumberPoolVO;
import com.fuyun.outpatient.vo.ScheduleTemplateVO;
import com.fuyun.outpatient.vo.ScheduleVO;
import java.time.LocalDate;
import java.util.List;

/**
 * 排班与号源池服务（M03 FU-M03-01 号源池管理，Task 4 冻结面）：排班模板维护、T+N 放号生成、
 * 停诊/恢复、加号授权与可约号源对外查询。号源是全院唯一权威库存（Spec §1 职责①），本服务为
 * 池域写路径唯一入口；预约侧扣减消费（Redis 第一道闸+池行 CAS 第二道闸）随 Task 5 接线。
 * 聚合型接口不继承 IService（A.4.3-20），实现注入三 mapper。
 */
public interface IScheduleService {

    /**
     * 保存排班模板（id 空=登记 ACTIVE 模板；非空=按 id 全量覆盖请求面字段）。
     *
     * @param request 保存请求，非空；格式约束由 JSR-303 承载，slot 起止倒挂服务端显式拒绝
     * @return 保存后的模板出参（登记路径含回填 id），非空
     * @throws com.fuyun.common.exception.BizException OP-1019/400 slotStart 不早于 slotEnd；
     *                                                 OP-1004/409 更新时模板不存在
     */
    ScheduleTemplateVO saveTemplate(ScheduleTemplateSaveRequest request);

    /**
     * 排班模板分页清单（id 升序唯一顺序）。
     *
     * @param page 页码（0 基）
     * @param size 单页条数
     * @return 分页出参 {content,page,size,total}，非空；无匹配返回空 content
     */
    PageResult<ScheduleTemplateVO> listTemplates(int page, int size);

    /**
     * T+N 放号生成：ACTIVE 模板按 week_pattern 位串×日期区间 [endDate-(days-1), endDate] 展开
     * 排班日历与号源池行。幂等语义两段（R1 修复裁定）：窗口内已存在排班键（uk_schedule 三列）
     * 循环前预过滤跳过（warn 留痕，重放零插入）；预过滤后的并发 uk 冲突抛 OP-1004 整批回滚
     * （PG 事务 aborted 语义禁循环内捕获续跑）。池键预热 prime。
     *
     * @param request 放号请求，非空；endDate 为窗口截止日、days 为窗口天数
     * @return 本次实际生成排班行数（预过滤跳过不计入）
     * @throws com.fuyun.common.exception.BizException OP-1004/409 并发放号冲突（整批已回滚，重试即幂等）
     */
    int generate(ScheduleGenerateRequest request);

    /**
     * 排班日历分页清单（sched_date+id 升序唯一顺序，deptCode/dateFrom/dateTo 可选过滤）。
     *
     * @param query 查询对象（过滤条件+分页参数），非空
     * @return 分页出参 {content,page,size,total}，非空
     */
    PageResult<ScheduleVO> listSchedules(SchedulePageQuery query);

    /**
     * 停诊：schedule CAS NORMAL→STOPPED + 整池行批量 STOPPED + 事务内发布 schedule.stopped
     * （AFTER_COMMIT 出 MQ，已约患者改期/退费联动依据）。
     *
     * @param scheduleId 排班主键；来源：stop 端点路径参数
     * @param reason     停诊原因（事件与 stop_reason 留痕载体），非空
     * @throws com.fuyun.common.exception.BizException OP-1004/409 排班不存在或状态违例（CAS 0 行）
     */
    void stop(long scheduleId, String reason);

    /**
     * 恢复停诊：过期排班（sched_date 早于当日）拒绝；schedule CAS STOPPED→NORMAL + 整池行批量
     * 迁回 ACTIVE。恢复无事件面（schedule.stopped 仅停诊方向发布）。
     *
     * @param scheduleId 排班主键；来源：resume 端点路径参数
     * @throws com.fuyun.common.exception.BizException OP-1004/409 排班不存在、已过期或状态违例
     */
    void resume(long scheduleId);

    /**
     * 可约号源查询（余量对外查询，供全渠道与 M18 复用）：仅 ACTIVE 且 used_count&lt;total_quota
     * 行（谓词由 mapper 注解 SQL 承载），按 slot_start 升序。
     *
     * @param deptCode 开诊科室编码，非空
     * @param date     排班日期，非空
     * @param apptType 号别过滤，可空（null=全部号别）
     * @return 可约池行出参集（含服务端计算余量 remaining），非空；无可约号源返回空列表
     */
    List<NumberPoolVO> availablePools(String deptCode, LocalDate date, ApptType apptType);

    /**
     * 加号授权：池行 total_quota 增量 count（预约余量谓词自然放行加号段），加号占用计数走
     * extra_used（Task 5 挂号时按号段归入）；CAS 命中后同步对池键 INCRBY count 并续期 TTL
     * （快路径立即可约，R1 修复——键缺失跳过不造凭空键，Redis 异常不回滚加号交日对账兜底）。
     *
     * @param poolId 池行主键；来源：extra-quota 端点路径参数
     * @param count  加号数量（1~50，契约与服务端双层校验）
     * @throws com.fuyun.common.exception.BizException OP-1019/400 count 越界；OP-1002/404 池行
     *                                                 不存在或非 ACTIVE
     */
    void extraQuota(long poolId, int count);
}
