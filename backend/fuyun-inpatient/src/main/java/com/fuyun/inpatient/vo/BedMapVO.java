package com.fuyun.inpatient.vo;

import com.fuyun.inpatient.entity.Bed;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.enums.BedStatus;
import java.time.OffsetDateTime;

/**
 * 床位图行出参（GET /api/v1/inpatient/beds/map?wardId= 聚合行；实体禁直出——出网边界唯一
 * 出口）：五态色标 + 包床标记 + 性别限制 + 占用 visit 摘要。脱敏红线：摘要仅定位键与入科
 * 时点，禁患者姓名/诊断文本。
 *
 * @param bedId         床位 id
 * @param wardId        归属病区编码
 * @param bedNo         床号（图内排序键）
 * @param bedAttr       床位属性 code（BedAttr：NORMAL/PRIVATE 包床标记/EXTRA 加床——计费属性非状态）
 * @param bedStatus     床位状态 code（BedStatus 五态：FREE/RESERVED/OCCUPIED/DISINFECTING/MAINTENANCE）
 * @param allowGender   性别限制（MALE/FEMALE/null 不限）
 * @param occupiedVisit 占用就诊摘要，可空；仅 OCCUPIED 且就诊行在位时非空（就诊行缺失=数据不一致，降级为 null 不阻断床位图）
 */
public record BedMapVO(
        long bedId,
        String wardId,
        String bedNo,
        String bedAttr,
        String bedStatus,
        String allowGender,
        OccupiedVisit occupiedVisit) {

    /**
     * 占用就诊摘要（定位键 + 入科时点；敏感字段零载荷）。
     *
     * @param visitId    住院就诊号（I 型 14 位）
     * @param patientId  患者主索引
     * @param admittedAt 入科时点（床位占用起算参考）
     */
    public record OccupiedVisit(String visitId, long patientId, OffsetDateTime admittedAt) {}

    /**
     * 床位行 + 就诊行 → 床位图行静态工厂（关键业务字段手写映射，禁 MapStruct——backend
     * 宪法 A.1-8 先例；占用摘要仅在 OCCUPIED 态且有就诊行时组装）。
     *
     * @param bed   床位行，非空
     * @param visit 占用就诊行，可空（非占用态或就诊行缺失）
     * @return 床位图行，非空
     */
    public static BedMapVO from(Bed bed, InpatientVisit visit) {
        boolean occupied = BedStatus.OCCUPIED.getCode().equals(bed.getStatus()) && visit != null;
        return new BedMapVO(
                bed.getId(),
                bed.getWardId(),
                bed.getBedNo(),
                bed.getBedAttr(),
                bed.getStatus(),
                bed.getAllowGender(),
                occupied ? new OccupiedVisit(visit.getVisitId(), visit.getPatientId(), visit.getAdmittedAt()) : null);
    }
}
