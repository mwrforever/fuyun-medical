package com.fuyun.nursing.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.nursing.dto.NursingTaskCancelRequest;
import com.fuyun.nursing.dto.NursingTaskCreateRequest;
import com.fuyun.nursing.dto.TaskClaimRequest;
import com.fuyun.nursing.entity.NursingTask;
import com.fuyun.nursing.enums.TaskStatus;
import com.fuyun.nursing.vo.NursingTaskVO;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 护理任务域服务（V805 nursing_task 业务面；Task 8 评估高危联动、Task 9 交接班待续事项、
 * Task 10 PDA 巡视打卡与 Task 3 详情卡在途任务段的消费契约来源）。P1 仅落「任务最小载体」：
 * 创建（发号 + 默认 PENDING + created 事件）、完成/取消（CAS 终态流转 + completed 事件）、
 * 病区清单与患者在途查询（单查/批量，读时惰性逾期判定）、巡视打卡（直落 COMPLETED）。
 * P2 PR-3 Task 9 任务工作台首批落地：任务认领（PENDING → IN_PROGRESS 迁移入口）与完成扩参
 * 关联执行单回写；常规模板批量生成归 {@link IRoutineTaskGenerator}、tick 逾期升级归
 * {@link ITaskOverdueService}（同包分面承载）。
 * 逾期口径（Spec :127 动作式逾期）：overdue_flag + escalation_count 为动作落点，读时惰性
 * 判定单次递增（查询侧），tick 驱动升级链发布 nursing.task.overdue（P2 PR-3 Task 9 实装）。
 *
 * <p>线程安全：无状态 singleton；写操作 @Transactional 收口（实现侧）。
 *
 * <p>配对纪律（宪法 A.4.3-20）：单主表 nursing_task 与实现侧
 * {@code ServiceImpl<NursingTaskMapper, NursingTask>} 配对，接口侧收拢
 * {@code extends IService<NursingTask>}——主表通用 CRUD 直接复用 IService 契约面；
 * 终态流转（complete/cancel 的 CAS 行数判定 + 事件发布）、认领迁移与读时惰性逾期写为带守卫链的
 * 自有方法承载（禁经 IService 通用面绕行——通用面不盖操作者审计列、不发领域事件）。
 */
public interface INursingTaskService extends IService<NursingTask> {

    /**
     * 护理任务创建（手工开立）：①taskType/source/priority code 显式校验（非法 NS-1019）→
     * ②计划时间补录红线（早于当前 24 小时以上拒 NS-1016，禁补录历史任务）→ ③发号器取 TK 号 →
     * ④insert（任务号唯一冲突兜底转 NS-1016 幂等拒绝）→ ⑤发布 nursing.task.created（事务内
     * 发布 AFTER_COMMIT 出站 GC8，载荷 TaskCreatedPayload）。默认态 PENDING、逾期动作列零值。
     *
     * @param req 创建入参，非空；来源：操作者工作站表单 / Task 8 评估高危联动（服务面直调）
     * @return 任务出参（PENDING 态），非空
     * @throws BizException NS-1019（400 taskType/source/priority code 非法）/
     *                      NS-1016（409 计划时间补录越窗或任务号唯一冲突幂等拒绝）
     */
    NursingTaskVO create(NursingTaskCreateRequest req);

    /**
     * 护理任务完成（PENDING/IN_PROGRESS → COMPLETED）：@Update CAS 单语句（GC26，0 行 →
     * NS-1011），completed_at 随 CAS 盖章；relatedExecutionNo 非空时随完成定格 source_ref
     * （关联执行单引用回写——任务↔执行单追溯链，空值保持原引用零覆盖）；命中后回读行数据并
     * 发布 nursing.task.completed（载荷 TaskCompletedPayload，status=COMPLETED）。
     *
     * @param taskNo             任务业务号，非空；来源：路径参数
     * @param relatedExecutionNo 关联执行单号（完成时回写 source_ref 的单据引用），可空；
     *                           来源：工作站/PDA 完成表单（执行单驱动的任务完成时携带）
     * @return 完成后任务出参，非空
     * @throws BizException NS-1011（409 任务不存在或已终态，禁止完成）/
     *                      NS-1016（409 CAS 命中后行被并发逻辑删，回读缺失）
     */
    NursingTaskVO complete(String taskNo, String relatedExecutionNo);

    /**
     * 护理任务认领（P2 PR-3 Task 9 任务工作台面，PENDING → IN_PROGRESS）：@Update CAS 单语句
     * （GC26，仅 PENDING 可认领，0 行 → NS-1011），assignee 一律登录令牌身份落 assigned_nurse
     * （文本承载，W-72，2026-10-03 裁决——请求体 assigneeId 兼容保留忽略）。在途态内部迁移
     * 非终态——不发任务事件（终态广播语义归 complete/cancel）。
     *
     * @param taskNo 任务业务号，非空；来源：路径参数
     * @param req    认领入参（assigneeId 兼容保留——服务端不消费），非空；来源：任务工作台认领动作
     * @return 认领后任务出参（IN_PROGRESS 态），非空
     * @throws BizException NS-1019（400 操作者上下文缺失或非数字——无法定位认领主体，W-72）/
     *                      NS-1011（409 任务不存在、非 PENDING 或已被逻辑删，禁止认领）/
     *                      NS-1016（409 CAS 命中后行被并发逻辑删，回读缺失）
     */
    NursingTaskVO claim(String taskNo, TaskClaimRequest req);

    /**
     * 护理任务取消（PENDING/IN_PROGRESS → CANCELLED）：取消原因强制留痕（空拒 NS-1019）→
     * @Update CAS 单语句（GC26，0 行 → NS-1011）→ 回读行数据并发布 nursing.task.completed
     * （cancel 同发完成事件，载荷 status=CANCELLED）。
     *
     * @param taskNo 任务业务号，非空；来源：路径参数
     * @param req    取消入参（reason 必填留痕），非空；来源：操作者录入
     * @return 取消后任务出参，非空
     * @throws BizException NS-1019（400 取消原因为空）/ NS-1011（409 任务不存在或已终态，禁止取消）/
     *                      NS-1016（409 CAS 命中后行被并发逻辑删，回读缺失）
     */
    NursingTaskVO cancel(String taskNo, NursingTaskCancelRequest req);

    /**
     * 病区任务清单：wardId 必选，status/date 可选（当日窗口含头不含尾），按计划时间升序。
     * 先查后标再返回：查询后对返回集内越过阈值（NursingProperties.taskOverdueMinutes）的
     * 在途行经 casMarkOverdue 单次置位递增，出参与库态一致（读路径含惰性逾期写，禁 readOnly）。
     *
     * @param wardId 病区编码，非空；来源：查询参数
     * @param status 状态过滤，可空（空=全状态）；来源：查询参数
     * @param date   计划日期过滤，可空（空=不限日期）；来源：查询参数
     * @return 任务出参清单（无行返回空清单，非 null）；按计划时间升序
     */
    List<NursingTaskVO> list(String wardId, TaskStatus status, LocalDate date);

    /**
     * 患者在途任务清单（仅 PENDING/IN_PROGRESS，计划时间升序）：Task 3 详情卡「在途任务」段的
     * 消费落点（Task 9 交接班待续事项已改走 {@link #inFlightByVisits} 批量取数）。先查后标再
     * 返回，惰性逾期 CAS 同 {@link #list}。
     *
     * @param visitId 住院就诊号，非空；来源：路径/载荷
     * @return 在途任务出参清单（无行返回空清单，非 null）；按计划时间升序
     */
    List<NursingTaskVO> inFlightByVisit(String visitId);

    /**
     * 多患者在途任务批量清单（仅 PENDING/IN_PROGRESS，每人计划时间升序）：Task 9 交接班待续
     * 事项的批量取数落点——visitIds 键集前置已知（在区患者视图先行汇总），一次 IN 批查替代
     * 逐患者单查（N+1 消除，宪法 A.4.3-14）。先查后标再返回：守卫判定（在途 + 未标记 + 越阈值）
     * 命中行收敛为单条 id 集批量 CAS（casMarkOverdueBatch），per-row overdue_flag=false 谓词
     * 保持与逐行 {@link #inFlightByVisit} 的仅首次递增语义逐行等价。
     *
     * @param visitIds 住院就诊号键集，非空集合（空集零 SQL 触达直接返回空 Map）；来源：在区患者视图行
     * @return visitId → 在途任务出参清单（键集内无在途任务的患者不出键，非 null）；每人按计划时间升序
     */
    Map<String, List<NursingTaskVO>> inFlightByVisits(Collection<String> visitIds);

    /**
     * 巡视打卡（Task 10 PDA 面 {@code POST /pda/patrol} 落点）：建 PATROL 行并直落 COMPLETED
     * ——assignedNurse=当前操作者、planTime/completedAt=打卡时刻（服务器时间）、sourceRef=扫码
     * 标识留痕。行生而终态：未经历任务生命周期，不发 created/completed 事件（打卡记录语义）。
     *
     * @param patientId  患者主索引，非空；来源：PDA 标识解析（Task 10）
     * @param visitId    住院就诊号，非空；来源：PDA 标识解析（Task 10）
     * @param wardId     病区编码，非空；来源：PDA 当前登录病区（Task 10）
     * @param identifier 扫码标识（腕带号等），非空；来源：PDA 扫码，落 sourceRef 留痕
     * @return 打卡任务出参（COMPLETED 态），非空
     * @throws BizException NS-1016（409 任务号唯一冲突幂等拒绝——PDA 连点防重）
     */
    NursingTaskVO patrol(long patientId, String visitId, String wardId, String identifier);
}
