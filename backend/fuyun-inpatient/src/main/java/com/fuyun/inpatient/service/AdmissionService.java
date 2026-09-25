package com.fuyun.inpatient.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.dto.AdmissionCreateRequest;
import com.fuyun.inpatient.dto.AdmissionScheduleRequest;
import com.fuyun.inpatient.dto.VisitRegisterRequest;
import com.fuyun.inpatient.dto.WardAdmitRequest;
import com.fuyun.inpatient.enums.AdmissionStatus;
import com.fuyun.inpatient.vo.AdmissionVO;
import com.fuyun.inpatient.vo.ArrearsAlarmVO;
import com.fuyun.inpatient.vo.InpatientVisitVO;
import java.util.List;

/**
 * 入院登记域服务（FU-M04-01）：住院证全生命周期（登记入队/预约/作废/登记确认）与住院就诊
 * 起算两态（登记确认签发 I 型 visit_id / 入科确认）。登记确认为本域红线方法——visit_id 签发、
 * 结构自检与就诊落库同事务成败与共（M02 Spec 红线 1：I 型唯一签发主体 = 本模块）。
 * 床位联动三处（Task 4 随 V903 bed 落地补齐）：schedule 预约目标床位置 RESERVED、cancel
 * 宽容联动释放（回读床行实态，仅 RESERVED 才释放，非预占 warn 留痕放行作废）、admitWard
 * 入科床位 RESERVED→OCCUPIED + bed_assign 开流水——均同事务联动（BedService 同源 CAS 权威，
 * 联动失败整体回滚）。Task 10 追加住院计费入口欠费面：押金变动回执驱动的 arrears_flag 本地
 * 标识刷新与病区欠费清单聚合（FU-M04-08——欠费标识为 visit 行本地属性，归本域承载）。
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
     * 预约入院/预住院（WAITING→SCHEDULED）：记录目标病区/床位与预约日期；携目标床位时同事务
     * 联动床位预占（BedService.reserveForAdmission 置 RESERVED——预占失败整体预约事务回滚；
     * 预住院模式无床不联动）。
     *
     * @param admissionNo 住院证号，非空；来源：路径参数
     * @param req         预约入参，非空；来源：登记台签床调度
     * @return 预约后出参（status=SCHEDULED），非空
     * @throws com.fuyun.common.exception.BizException IP-1001（404 住院证不存在）/
     *         IP-1002（409 非 WAITING 态禁止预约——已预约/终态）/ IP-1004（404 目标床位不存在）/
     *         IP-1005（409 目标床位消毒/维修中）/ IP-1006（409 目标床位已被占用）
     */
    AdmissionVO schedule(String admissionNo, AdmissionScheduleRequest req);

    /**
     * 住院证作废（WAITING/SCHEDULED→CANCELLED，终态）。床位联动②取宽容语义：作废 CAS 命中后
     * 回读住院证行权威 target_bed_id 与床行实态，仅 RESERVED 才同事务联动释放预占床位
     * （BedService.releaseForAdmission 置 FREE）；非预占态（预占床已被登记台手工释放/流转
     * 其他态/床位缺失）warn 留痕后放行作废——预占缺失不得阻断住院证终态落定。
     *
     * @param admissionNo 住院证号，非空；来源：路径参数
     * @return 作废后出参（status=CANCELLED），非空
     * @throws com.fuyun.common.exception.BizException IP-1001（404 住院证不存在）/
     *         IP-1002（409 终态（COMPLETED/CANCELLED）禁止作废）/ IP-1005（409 释放瞬间床位
     *         被并发流转的窗口场景，重试作废即自愈）
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
     * （入科时点库端 now()）→ 床位联动（BedService.occupyForAdmission：床位 RESERVED→OCCUPIED
     * CAS + bed_assign 开 ADMISSION 流水，占床失败整体入科事务回滚）→ 事务内发布
     * inpatient.visit.admitted（M05 病区患者视图维护、M14 设备待绑定提醒）。
     *
     * @param visitId 住院就诊号（I 型 14 位），非空；来源：路径参数
     * @param req     入科入参，非空；来源：病区护士站入科单
     * @return 入科后就诊出参（status=ADMITTED），非空
     * @throws com.fuyun.common.exception.BizException IP-1007（404 就诊不存在）/
     *         IP-1008（409 非 REGISTERED 态禁止入科——已入科/已出院/已作废）/
     *         IP-1022（400 护理级别词表外）/ IP-1023（409 CAS 命中后行被并发逻辑删，回读缺失）/
     *         IP-1004（404 床位不存在）/ IP-1005（409 床位消毒/维修中）/ IP-1006（409 床位已被占用）
     */
    InpatientVisitVO admitWard(String visitId, WardAdmitRequest req);

    /**
     * 押金变动回执消费体（FU-M04-08 住院计费入口，billing.deposit.changed 载荷
     * visitId/balance，BillingEventListener 委托）：余额与押金下限阈值
     * （InpatientProperties.depositFloorFen——M04 本地阈值全局一份）本地裁决后 CAS 刷新
     * inpatient_visit.arrears_flag（跌破置 true+warn 日志——护士站欠费标识数据源；回升复位
     * false）。幂等两层：信封 eventId 三段式（IdempotentConsumerSupport）+ 业务级 CAS 目标值
     * 异值限定（重复投递零行 info 直返）。余额仅为事件载荷消费（GC18 零金额输入红线）。
     *
     * @param visitId    CF-3 住院就诊号（I 型 14 位，载荷原值），非空；来源：billing 事件载荷
     * @param balanceFen 变动后押金余额（分，事件载荷原值），非空约束由监听器守卫
     */
    void onDepositChanged(String visitId, long balanceFen);

    /**
     * 病区欠费清单（GET /visits/arrears?wardId=）：arrears_flag=true 的在院就诊聚合
     * （在院三态限定——已出院/已作废不进护士站清单），出参行=就诊号+患者脱敏展示名
     * （PatientNameQuery——姓名原文不出 patient 模块，GC22）+床位号+标识时点
     * （updated_at 近似承载）；按标识时点倒序（最新欠费在前）。五大降级清单②承载面：
     * 欠费提醒=工作站列表可见（M01 通知中心缺位）。
     *
     * @param wardId 病区编码，非空；来源：查询参数（护士站一览）
     * @return 欠费清单行（病区无欠费在院患者返回空清单），非空
     */
    List<ArrearsAlarmVO> arrearsList(String wardId);
}
