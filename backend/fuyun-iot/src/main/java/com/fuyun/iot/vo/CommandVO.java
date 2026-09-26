package com.fuyun.iot.vo;

import com.fuyun.iot.entity.IotProductCommandEntity;
import com.fuyun.iot.enums.CommandSafetyLevel;

/**
 * 命令安全等级视图（PUT /api/v1/iot/products/{id}/commands 出参元素）：命令白名单标注面
 * （FU-M14-09 下发闸门消费同源数据）。
 *
 * @param id          标注行雪花 id，非空（替换重建后为新 id）
 * @param productId   注册中心产品标识，非空
 * @param commandName 命令名称，非空
 * @param serviceId   所属服务 ID，可空
 * @param safetyLevel 命令安全等级，非空
 * @param allowed     是否放行下发（缺省按级别：SAFETY=true/TREATMENT=false），非空
 */
public record CommandVO(
        Long id,
        String productId,
        String commandName,
        String serviceId,
        CommandSafetyLevel safetyLevel,
        Boolean allowed) {

    /**
     * 实体 → 视图工厂映射。
     *
     * @param entity 命令标注实体，非空
     * @return 命令视图，非空
     */
    public static CommandVO from(IotProductCommandEntity entity) {
        return new CommandVO(
                entity.getId(),
                entity.getProductId(),
                entity.getCommandName(),
                entity.getServiceId(),
                entity.getSafetyLevel(),
                entity.getAllowed());
    }
}
