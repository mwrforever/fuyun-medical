package com.fuyun.nursing.service;

import com.fuyun.nursing.dto.NursingTaskCancelRequest;
import com.fuyun.nursing.dto.NursingTaskCreateRequest;
import com.fuyun.nursing.enums.TaskStatus;
import com.fuyun.nursing.vo.NursingTaskVO;
import java.time.LocalDate;
import java.util.List;

/**
 * 护理任务域服务（V805 nursing_task 业务面；Task 8 评估高危联动、Task 9 交接班待续事项、
 * Task 10 PDA 巡视打卡与 Task 3 详情卡在途任务段的消费契约来源）。P1 仅落「任务最小载体」：
 * 创建（发号 + 默认 PENDING + created 事件）、完成/取消（CAS 终态流转 + completed 事件）、
 * 病区清单与患者在途查询（读时惰性逾期判定）、巡视打卡（直落 COMPLETED）。任务工作台
 * （分组/认领/模板批量生成）归 P2——IN_PROGRESS 为 P1 声明态（仅注册迁移对，无迁移入口）。
 * 逾期口径（Spec :127 动作式逾期）：overdue_flag + escalation_count 为动作落点，读时惰性
 * 判定单次递增，P1 不发布 nursing.task.overdue（V800 占位登记，发布随 P2 延迟队列）。
 *
 * <p>线程安全：无状态 singleton；写操作 @Transactional 收口（实现侧）。
 */
public interface INursingTaskService {

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
     * NS-1011），completed_at 随 CAS 盖章；命中后回读行数据并发布 nursing.task.completed
     * （载荷 TaskCompletedPayload，status=COMPLETED）。
     *
     * @param taskNo 任务业务号，非空；来源：路径参数
     * @return 完成后任务出参，非空
     * @throws BizException NS-1011（409 任务不存在或已终态，禁止完成）/
     *                      NS-1016（409 CAS 命中后行被并发逻辑删，回读缺失）
     */
    NursingTaskVO complete(String taskNo);

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
     * 患者在途任务清单（仅 PENDING/IN_PROGRESS，计划时间升序）：Task 9 交接班待续事项与
     * Task 3 详情卡「在途任务」段的消费落点。先查后标再返回，惰性逾期 CAS 同 {@link #list}。
     *
     * @param visitId 住院就诊号，非空；来源：路径/载荷
     * @return 在途任务出参清单（无行返回空清单，非 null）；按计划时间升序
     */
    List<NursingTaskVO> inFlightByVisit(String visitId);

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
