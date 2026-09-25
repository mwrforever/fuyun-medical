package com.fuyun.inpatient.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.dto.ConsultationCreateRequest;
import com.fuyun.inpatient.dto.ConsultationOpinionRequest;
import com.fuyun.inpatient.enums.ConsultationStatus;
import com.fuyun.inpatient.vo.ConsultationVO;

/**
 * 会诊管理域服务（FU-M04-09，V908 consultation 业务面）——院内会诊闭环门面：会诊申请
 * （独立申请路径，响应时限按紧急程度服务端计算：急会诊 +30min / 普通 +24h，发布 requested）、
 * 受邀科接单（REQUESTED→ACCEPTED，<b>逾期单仍可接单</b>——接单清 overdue_flag，发布
 * accepted）、会诊意见提交（ACCEPTED→COMPLETED，意见归档供 M09 引用，发布 completed）、
 * 取消（REQUESTED/ACCEPTED→CANCELLED，发布 cancelled）与列表查询（<b>读时惰性逾期</b>：
 * REQUESTED 且越过响应截止且未标记行——置 overdue_flag[CAS] + 发布 overdue 动作事件一次
 * [DB 标记防重发] + 升级动作 warn 留痕；状态停留 REQUESTED 仍可被响应——动作非状态迁移）。
 * 会诊为模块内独立小状态机（不经 OrderStateMachineService），迁移唯一经 ConsultationMapper
 * CAS 条件更新 + 影响行数判定（GC23）。CONSULT 类医嘱审核自动建草稿钩子归
 * OrderAuditServiceImpl（业务流转归会诊流程——审核域收口，本服务不反向承载）。
 */
public interface ConsultationService {

    /**
     * 会诊申请（POST /consultations，独立申请路径）：守卫（就诊在院 ADMITTED 且已入科——
     * 申请科室取 current_dept_id）→ 紧急程度/级别词表裁决 → 响应截止计算（申请时点+时限：
     * URGENT +30min / NORMAL +24h）→ 会诊单落库（CS 号签发）→ 事务内发布
     * inpatient.consultation.requested（V901 id 68 载荷，申请态子集）。
     *
     * @param req 申请入参（就诊号/受邀科室/紧急程度/级别/原因），非空；来源：医生站会诊申请单
     * @return 申请后出参（status=REQUESTED，响应截止已计算），非空
     * @throws com.fuyun.common.exception.BizException IP-1007（404 就诊不存在）/IP-1008（409
     *                 非在院禁申请）/IP-1022（400 词表外紧急程度或级别/操作者标识非数字）/
     *                 IP-1023（409 在院就诊缺当前科室——数据不一致）
     */
    ConsultationVO create(ConsultationCreateRequest req);

    /**
     * 受邀科接单（POST /consultations/{no}/accept）：REQUESTED→ACCEPTED CAS（接单时点库端
     * now() 落值并<b>清除逾期标记</b>——逾期升级为动作非状态迁移，超时单仍可响应）→ 回读
     * → 事务内发布 inpatient.consultation.accepted（V901 id 69 载荷）。
     *
     * @param consultNo 会诊单号，非空；来源：路径参数
     * @return 接单后出参（status=ACCEPTED），非空
     * @throws com.fuyun.common.exception.BizException IP-1019（404 会诊单不存在）/IP-1020
     *                 （409 非 REQUESTED 态——已被接单/取消/完成）/IP-1022（400 操作者标识
     *                 非数字）
     */
    ConsultationVO accept(String consultNo);

    /**
     * 会诊意见提交（POST /consultations/{no}/opinion）：ACCEPTED→COMPLETED CAS（完成时点
     * 库端 now() 与意见同语句归档）→ 回读 → 事务内发布 inpatient.consultation.completed
     * （V901 id 70 载荷）；意见归档供 M09 病历引用。
     *
     * @param consultNo 会诊单号，非空；来源：路径参数
     * @param req       意见入参（意见文本），非空；来源：受邀科医生会诊意见单
     * @return 完成后出参（status=COMPLETED），非空
     * @throws com.fuyun.common.exception.BizException IP-1019（404 会诊单不存在）/IP-1020
     *                 （409 非 ACCEPTED 态——未接单/已取消/已完成）/IP-1022（400 操作者标识
     *                 非数字）
     */
    ConsultationVO opinion(String consultNo, ConsultationOpinionRequest req);

    /**
     * 取消会诊（POST /consultations/{no}/cancel）：REQUESTED/ACCEPTED→CANCELLED CAS
     * （双合法出边；COMPLETED 终态已归档意见不可取消）→ 事务内发布
     * inpatient.consultation.cancelled（V901 id 72 载荷，reason 取单面申请原因）。
     *
     * @param consultNo 会诊单号，非空；来源：路径参数
     * @return 取消后出参（status=CANCELLED），非空
     * @throws com.fuyun.common.exception.BizException IP-1019（404 会诊单不存在）/IP-1020
     *                 （409 终态不可取消/并发迁移零行）/IP-1022（400 操作者标识非数字）
     */
    ConsultationVO cancel(String consultNo);

    /**
     * 会诊单分页查询（GET /consultations，读时惰性逾期承载面）：状态/科室过滤分页 → 当前页
     * REQUESTED 行逐行惰性逾期判定（now&gt;response_deadline 且未标记 → casMarkOverdue 置位
     * [CAS] + 发布 inpatient.consultation.overdue 动作事件一次 + warn 升级留痕；状态停留
     * REQUESTED）。overdue 事件须在事务内 publishEvent 承载（AFTER_COMMIT 出 MQ）——本方法
     * 事务为写事务（非只读）。科室过滤口径：申请或受邀任一侧命中即入列（申请方/受邀方双视角）。
     *
     * @param status 状态过滤（可空=全部状态），可空；来源：查询参数
     * @param deptId 科室编码过滤（M01 组织 code，申请/受邀任一侧命中；可空=不过滤），可空；
     *               来源：查询参数
     * @param page   页码（0 基），非负
     * @param size   单页条数，正
     * @return 分页出参（行内 overdueFlag 为置位后实态），非空
     */
    PageResult<ConsultationVO> list(ConsultationStatus status, String deptId, int page, int size);
}
