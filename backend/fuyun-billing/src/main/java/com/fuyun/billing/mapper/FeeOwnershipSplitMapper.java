package com.fuyun.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.billing.entity.FeeOwnershipSplit;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 费用归属切分 mapper：单表操作经 BaseMapper 链式能力，另声明日切在院就诊扫描语句
 * （锚点存在 + 停费标记不存在的「在院」判定谓词，见 V1001）。必须标注 @Mapper 供 app 侧扫描。
 */
@Mapper
public interface FeeOwnershipSplitMapper extends BaseMapper<FeeOwnershipSplit> {

    /**
     * 全院在院就诊扫描（02:30 床位费日切人群）：有入科起费锚点且无出院停费标记的就诊行——
     * 同一就诊的切分时间线按 split_type 鉴别（ADMIT_START 起点/DISCHARGE_STOP 终点），
     * 「在院」谓词以 NOT EXISTS 反连接承载（出院标记后日切不再生成床位费）。deleted=0
     * 显式补齐（注解 SQL 不继承 @TableLogic）；status 字面量与 FeeSplitType.code 同源。
     *
     * @return 在院就诊锚点行列表（含 visit_id/patient_id 日切命令入参），可为空清单
     */
    @Select("SELECT s.* FROM billing.fee_ownership_split s "
            + "WHERE s.split_type = 'ADMIT_START' AND s.deleted = 0 "
            + "AND NOT EXISTS (SELECT 1 FROM billing.fee_ownership_split d "
            + "WHERE d.visit_id = s.visit_id AND d.split_type = 'DISCHARGE_STOP' AND d.deleted = 0)")
    List<FeeOwnershipSplit> selectDailyChargeableVisits();
}
