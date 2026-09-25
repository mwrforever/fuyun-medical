package com.fuyun.inpatient.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.dto.AdmissionCreateRequest;
import com.fuyun.inpatient.dto.AdmissionScheduleRequest;
import com.fuyun.inpatient.dto.VisitRegisterRequest;
import com.fuyun.inpatient.dto.WardAdmitRequest;
import com.fuyun.inpatient.enums.AdmissionStatus;
import com.fuyun.inpatient.vo.AdmissionVO;
import com.fuyun.inpatient.vo.InpatientVisitVO;

/**
 * 入院登记域服务（FU-M04-01）：住院证全生命周期（登记入队/预约/作废/登记确认）与住院就诊
 * 起算两态（登记确认签发 I 型 visit_id / 入科确认）。登记确认为本域红线方法——visit_id 签发、
 * 结构自检与就诊落库同事务成败与共（M02 Spec 红线 1：I 型唯一签发主体 = 本模块）。
 * 床位预占/释放/占床联动（BedService）归 Task 4 随 V903 bed 落地后补齐。
 */
public interface AdmissionService {

    /**
     * 住院证登记（登记即建单入 WAITING 候床队列）：来源/类型词表校验 → 患者归一/拦截（FROZEN
     * 拒 IP-1003）→ 发 AD 号 → 落库（uk_admission_no 兜底转 IP-1023）。
     *
     * @param req 登记入参，非空；来源：医生站/登记台开证
     * @return 建单出参（status=WAITING），非空
     * @throws com.fuyun.common.exception.BizException IP-1022（400 来源/类型词表外或转诊缺门诊
     *         visit_id 引用）/ IP-1003（409 档案冻结拒新就诊）/ IP-1023（409 证号唯一冲突）/
     *         PAT-1001（404 患者档案不存在，patient 侧透传）
     */
    AdmissionVO create(AdmissionCreateRequest req);

    /**
     * 候床队列分页查询：排序权重=急诊优先（admission_type=EMERGENCY 前置）＞预约时段
     * （expect_date 升序、空值排后）＞候床时长（建行时间升序）。
     *
     * @param status 状态过滤（可空=全部状态；候床队列视图常规传 WAITING/SCHEDULED），可空；来源：查询参数
     * @param page   页码（0 基），非负
     * @param size   单页条数（1-200），正
     * @return 分页出参（内容为住院证出参行），非空
     */
    PageResult<AdmissionVO> queue(AdmissionStatus status, int page, int size);

    /**
     * 预约入院/预住院（WAITING→SCHEDULED）：记录目标病区/床位与预约日期。目标床位 RESERVED
     * 预占联动调 BedService.reserveForAdmission 归 Task 4 随 V903 bed 落地后补齐——本方法先
     * 承载 admission 自身状态面。
     *
     * @param admissionNo 住院证号，非空；来源：路径参数
     * @param req         预约入参，非空；来源：登记台签床调度
     * @return 预约后出参（status=SCHEDULED），非空
     * @throws com.fuyun.common.exception.BizException IP-1001（404 住院证不存在）/
     *         IP-1002（409 非 WAITING 态禁止预约——已预约/终态）
     */
    AdmissionVO schedule(String admissionNo, AdmissionScheduleRequest req);

    /**
     * 住院证作废（WAITING/SCHEDULED→CANCELLED，终态）。SCHEDULED 作废时预占床位释放联动
     * 归 Task 4 随 BedService 落地后补齐——本方法先承载 admission 自身状态面。
     *
     * @param admissionNo 住院证号，非空；来源：路径参数
     * @return 作废后出参（status=CANCELLED），非空
     * @throws com.fuyun.common.exception.BizException IP-1001（404 住院证不存在）/
     *         IP-1002（409 终态（COMPLETED/CANCELLED）禁止作废）
     */
    AdmissionVO cancel(String admissionNo);

    /**
     * 入院登记确认（WAITING/SCHEDULED→COMPLETED，<b>红线方法</b>）——单事务全链：患者可用性
     * 二次解析（FROZEN 拒 IP-1003）→ admission CAS 置 COMPLETED → InpatientSeqGate.nextVisitId()
     * 签发 I 型 14 位 visit_id → VisitIdValidator 结构自检（失败抛 IllegalStateException 回滚
     * 全事务）→ inpatient_visit 落 REGISTERED 行（visit_id 与归一 patient_id 同事务同时落库，
     * uk_visit_id 兜底签发幂等）→ 事务内发布 inpatient.visit.registered（AFTER_COMMIT 出 MQ，
     * M13 医保入院办理依据）。
     *
     * @param admissionNo 住院证号，非空；来源：路径参数
     * @param req         登记入参（医保类型），非空；来源：登记台核验医保凭证
     * @return 就诊出参（status=REGISTERED，visitId 已签发），非空
     * @throws com.fuyun.common.exception.BizException IP-1001（404 住院证不存在）/
     *         IP-1003（409 档案冻结拒新就诊）/ IP-1002（409 终态证禁止登记确认）/
     *         IP-1023（409 visit_id 唯一冲突——发号器异常回绕极端并发场景）
     * @throws IllegalStateException visit_id 结构自检失败（发号器异常，回滚全事务——红线护栏）
     */
    InpatientVisitVO register(String admissionNo, VisitRegisterRequest req);

    /**
     * 入科确认（visit REGISTERED→ADMITTED）：登记当前科室/病区/床位、主治医生与护理级别
     * （入科时点库端 now()）→ 事务内发布 inpatient.visit.admitted（M05 病区患者视图维护、
     * M14 设备待绑定提醒）。床位 RESERVED→OCCUPIED 流转与 bed_assign 占用流水开账归 Task 4
     * 随 V903 bed 落地后补齐——本方法先承载 visit 自身状态面。
     *
     * @param visitId 住院就诊号（I 型 14 位），非空；来源：路径参数
     * @param req     入科入参，非空；来源：病区护士站入科单
     * @return 入科后就诊出参（status=ADMITTED），非空
     * @throws com.fuyun.common.exception.BizException IP-1007（404 就诊不存在）/
     *         IP-1008（409 非 REGISTERED 态禁止入科——已入科/已出院/已作废）/
     *         IP-1022（400 护理级别词表外）/ IP-1023（409 CAS 命中后行被并发逻辑删，回读缺失）
     */
    InpatientVisitVO admitWard(String visitId, WardAdmitRequest req);
}
