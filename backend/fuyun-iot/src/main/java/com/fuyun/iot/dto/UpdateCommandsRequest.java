package com.fuyun.iot.dto;

import com.fuyun.iot.enums.CommandSafetyLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 命令安全等级标注请求（PUT /api/v1/iot/products/{id}/commands 请求体）：全量替换该产品
 * 命令白名单标注（逻辑删旧行 + 落新行）。allowed 缺省按级别回填（SAFETY=true/TREATMENT=false，
 * 服务层承担），显式指定即覆盖。
 *
 * @param commands 命令标注清单，非空且至少 1 条；来源：管理台命令登记表单
 */
public record UpdateCommandsRequest(
        @NotEmpty(message = "commands 不能为空") @Valid List<CommandItem> commands) {

    /**
     * 单条命令标注。
     *
     * @param commandName 命令名称（物模型 commands[].name），非空
     * @param serviceId   所属服务 ID，可空
     * @param safetyLevel 命令安全等级，非空
     * @param allowed     是否放行下发，可空（缺省按级别：SAFETY=true/TREATMENT=false）
     */
    public record CommandItem(
            @NotBlank(message = "commandName 不能为空") @Size(max = 128, message = "commandName 最长 128 字符")
            String commandName,

            @Size(max = 64, message = "serviceId 最长 64 字符") String serviceId,
            @NotNull(message = "safetyLevel 不能为空") CommandSafetyLevel safetyLevel,
            Boolean allowed) {}
}
