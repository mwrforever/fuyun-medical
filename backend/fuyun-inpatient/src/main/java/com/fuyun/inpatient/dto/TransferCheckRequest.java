package com.fuyun.inpatient.dto;

import com.fuyun.inpatient.enums.CheckConclusion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量转抄核对入参（POST /api/v1/inpatient/orders/transfer-check，FU-M04-06）：护士工作台
 * 勾选多条待转抄医嘱一次核对提交——整批单事务成败与共（任一条守卫不过即整批回滚）；
 * 已 TRANSFERRED 医嘱幂等跳过（重复提交/并发窗口容错）。高危/输血类医嘱缺第二核对人
 * 拒 IP-1016（服务层校验面）。
 *
 * @param orderNos        转抄医嘱号集（1–100 条批量上限），非空；来源：转抄工作台勾选
 * @param transferNurseId 转抄护士员工 ID（双人核对的执行转抄方签名），非空；来源：工作台当前护士
 * @param conclusion      核对结论（CheckConclusion：PASSED 放行；REJECTED 应用层拦截 IP-1016），非空
 * @param secondCheckerId 第二核对人员工 ID（高危药/输血类医嘱强制非空，其余可空），可空；来源：双人核对搭档选择
 */
public record TransferCheckRequest(
        @NotEmpty @Size(max = 100) List<String> orderNos,
        @NotBlank @Size(max = 64) String transferNurseId,
        @NotNull CheckConclusion conclusion,
        @Size(max = 64) String secondCheckerId) {}
