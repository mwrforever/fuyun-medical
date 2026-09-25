package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 医嘱重整入参（POST /api/v1/inpatient/orders/reorganize）：就诊内正在执行的长期医嘱按新
 * 视图序重排（转科/病危/手术节点重整单，04 Spec §4/§3.3）——只重排视图并留痕，不改任何
 * 医嘱状态与内容（重整留痕行落 order_status_log，from=to 无迁移动作）。列表顺序即重整后
 * 视图序（重整单打印与医生站展示取数依据）。
 *
 * @param visitId  住院就诊号（I 型 14 位），必填；来源：医生站重整操作
 * @param orderNos 重整后视图序的医嘱号列表（至少一条；顺序即新视图序），必填；来源：医生站拖拽排序
 */
public record OrderReorganizeRequest(
        @NotBlank(message = "visitId 不能为空") String visitId,
        @NotEmpty(message = "orderNos 不能为空") List<String> orderNos) {}
