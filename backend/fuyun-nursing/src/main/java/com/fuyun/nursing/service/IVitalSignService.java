package com.fuyun.nursing.service;

import com.fuyun.nursing.dto.VitalSignRecordRequest;
import com.fuyun.nursing.dto.VitalSignRejectRequest;
import com.fuyun.nursing.vo.VitalSignVO;
import java.time.Instant;
import java.util.List;

/**
 * 生命体征域服务（V803 vital_sign_record 业务面；Task 11 体征归集链 IT 的断言口径来源）。
 * 三源归一（方案 3.2）P1 双源落地：手工/PDA 点测录入即 CONFIRMED（Spec :130），IoT 源归 P2
 * （录入面显式拒 NS-1019）；落卡唯一约束 (visit_id, measured_at, 体温部位) 防双写（同键二次
 * 录入 NS-1016 幂等拒绝，不覆盖首值）；转正（CONFIRMED）体征自动归集——越正常范围生成观察行、
 * 全项正常合并当日观察行（Global Constraints 19 阈值量化），并写入体温单 VITAL 条目、发布
 * nursing.vital-sign.recorded（GC8 事务内发布 AFTER_COMMIT 出站）。复核流状态机（Spec :130）：
 * PENDING_REVIEW →（confirm CAS）→ CONFIRMED（补写体温单条目 + 发布事件）／→（reject CAS）→
 * REJECTED（不写条目、不发事件）。
 *
 * <p>线程安全：无状态 singleton；写操作 @Transactional 收口（实现侧）。
 */
public interface IVitalSignService {

    /**
     * 体征录入（手工/PDA 点测）：①生理极限拒收闸门（越界 NS-1005）→ ②在区校验（查无 NS-1004）→
     * ③insert（(visit_id, measured_at, site_key) 唯一冲突 → NS-1016 幂等拒绝）→ ④abnormal 判定
     * （NursingVitalThresholds）→ ⑤观察行归集（正常合并/异常独立落行）→ ⑥体温单 VITAL 条目
     * 写入 → ⑦发布 nursing.vital-sign.recorded。录入即 CONFIRMED（reviewedBy/reviewedAt 随录
     * 盖章）；测量时点一律服务器时间；source 空缺省 MANUAL，IOT 值 P1 拒收。
     *
     * @param req 录入入参，非空；来源：操作者工作站表单/PDA 上传
     * @return 体征记录出参，非空
     * @throws BizException NS-1005（400 超生理极限拒收）/ NS-1004（409 患者不在区）/
     *                      NS-1016（409 同刻同部位重复录入，幂等拒绝）/ NS-1019（400 source/tempSite code 非法）
     */
    VitalSignVO record(VitalSignRecordRequest req);

    /**
     * 按患者主索引列体征记录（测量时点升序）；from/to 均可空（空=不设边界，窗口含头不含尾）。
     * 患者全景（M09 取数）与体征趋势组装的数据源。
     *
     * @param patientId 患者主索引，非空；来源：查询参数
     * @param from      窗口起点（含），可空；来源：查询参数
     * @param to        窗口终点（不含），可空；来源：查询参数
     * @return 体征出参清单（无行返回空清单，非 null）；按测量时点升序
     */
    List<VitalSignVO> listByPatient(long patientId, Instant from, Instant to);

    /**
     * 按患者主索引取最近一次体征（单行点查，ALGO-01）：ORDER BY measured_at DESC, id DESC
     * LIMIT 1——与旧「升序清单取末位」取值语义一致（同为最近测量时点），id DESC 为同刻 tie
     * 的确定性 tie-break（同刻多行收敛取最新落卡行，消除旧路径 DB 无次序保证下的取值漂移）。
     * PDA 患者摘要专用取数面：替代全史拉取取末位的 O(患者终身体征行数) 全量路径为 O(1)
     * 单行回表（配合 PERF-02 idx_vital_sign_patient_time 前导索引）。
     *
     * @param patientId 患者主索引，非空；来源：PDA 摘要标识解析归一后的主档 id
     * @return 最近一次体征出参；患者无体征记录时返回 null（可空语义，与旧路径空清单取 null 对齐）
     */
    VitalSignVO latestByPatient(long patientId);

    /**
     * 病区待复核体征清单（复核工作台数据源）：仅 review_status=PENDING_REVIEW 行，按测量时点
     * 升序。P1 待复核行无生产写入方（IoT 归 P2），本端点随状态机 P1 可达。
     *
     * @param wardId 病区编码，非空；来源：查询参数
     * @return 待复核出参清单（无行返回空清单，非 null）；按测量时点升序
     */
    List<VitalSignVO> pendingReview(String wardId);

    /**
     * 复核转正（PENDING_REVIEW→CONFIRMED）：@Update CAS（GC26，0 行 → NS-1015）→ 补写体温单
     * VITAL 条目（转正入权威栏，Spec :137 流程 4）→ 发布 nursing.vital-sign.recorded（载荷
     * reviewStatus=CONFIRMED，语义=「转正入卡」）。
     *
     * @param id 体征记录 id，非空；来源：路径参数
     * @return 转正后记录出参，非空
     * @throws BizException NS-1015（409 仅待复核行可转正）/ NS-1016（409 同键条目已存在，幂等拒绝）
     */
    VitalSignVO confirm(Long id);

    /**
     * 复核驳回（PENDING_REVIEW→REJECTED）：@Update CAS（GC26，0 行 → NS-1015），驳回原因落
     * remark 留痕；不写体温单条目、不发事件（驳回行不入权威栏，Spec :130）。
     *
     * @param id  体征记录 id，非空；来源：路径参数
     * @param req 驳回入参（原因强制留痕），非空；来源：复核护士录入
     * @return 驳回后记录出参，非空
     * @throws BizException NS-1015（409 仅待复核行可驳回）
     */
    VitalSignVO reject(Long id, VitalSignRejectRequest req);
}
