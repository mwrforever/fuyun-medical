package com.fuyun.nursing.service;

import com.fuyun.nursing.dto.PdaPatrolRequest;
import com.fuyun.nursing.vo.NursingTaskVO;
import com.fuyun.nursing.vo.PdaPatientSummaryVO;

/**
 * PDA 护理面服务（Task 10：标识解析患者摘要 + 巡视打卡，无迁移）。P1 床旁双入口的
 * 服务面收口：标识三合一解析（腕带就诊编码直查在区行 / 就诊卡号与证件号经
 * PatientIdentityQuery 盲索引归一主档）+ 档案拦截与合并收敛（PatientContextResolver，
 * FROZEN 拒 NS-1004、MERGED 收敛主档）后聚合摘要或委托巡视打卡。
 *
 * <p>错误码出口唯一：patient 侧 PAT-1001（标识未登记/失效）统一映射 NS-1003，
 * 禁透传 PAT- 前缀错误码。摄像头扫码归 P2（P1 手工录入模拟）。
 *
 * <p>线程安全：无状态 singleton；写路径 @Transactional 收口（实现侧）。
 */
public interface IPdaService {

    /**
     * PDA 患者摘要（床旁速览）：①标识解析归一主档（visit 编码直查在区行 NS-1001 /
     * 卡号与证件号经 PatientIdentityQuery，PAT-1001 → NS-1003）→ ②档案拦截与合并收敛
     * （FROZEN NS-1004 / MERGED 收敛主档）→ ③在区行定位 + 病区详情聚合（IWardMetaService
     * #detail；不在区降级为基本信息，PDA 患者查询不限在区）→ ④过敏实时嵌查与最近一次
     * 体征摘要按收敛主档取数。脱敏输出：姓名掩码出网，证件号/手机号类字段不返回。
     *
     * @param identifier 扫码标识（腕带就诊编码/就诊卡号/证件号三合一），非空；来源：PDA 扫码或手工录入
     * @return 患者摘要出参（不在区时病区上下文字段为空、在途计数 0），非空
     * @throws BizException NS-1019（400 标识空白）/ NS-1003（400 标识未命中在档患者，
     *                      PAT-1001 映射）/ NS-1001（404 腕带就诊编码无在区行）/
     *                      NS-1004（409 档案冻结）
     */
    PdaPatientSummaryVO patientSummary(String identifier);

    /**
     * PDA 巡视打卡：①标识解析归一主档（同 {@link #patientSummary} 口径）→ ②就诊号归属
     * 校验（在区行必须存在且属于扫码患者，扫错腕带拒 NS-1016）→ ③委托
     * {@code INursingTaskService#patrol} 建 PATROL 行直落 COMPLETED（不发领域事件——
     * 打卡记录语义，Task 7 审查核验口径）。
     *
     * @param req 打卡入参（identifier 扫码标识 + visitId 归属校验键），非空；来源：PDA 扫码
     * @return 打卡任务出参（COMPLETED 态，携 taskNo），非空
     * @throws BizException NS-1019（400 标识或就诊号空白）/ NS-1003（400 标识未命中在档患者）/
     *                      NS-1001（404 腕带就诊编码无在区行）/ NS-1004（409 档案冻结）/
     *                      NS-1016（409 就诊不在区或与扫码患者归属不符）
     */
    NursingTaskVO patrol(PdaPatrolRequest req);
}
