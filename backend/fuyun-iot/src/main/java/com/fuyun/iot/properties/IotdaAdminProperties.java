package com.fuyun.iot.properties;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * IoTDA 管理面配置属性（fuyun.iot.admin.* 前缀，P2 PR-2 Task 4）：HuaweiIotdaRegistry 的
 * v5 端点与凭证三要素。启用校验机制与 {@link IotProperties.Amqp#validateAmqpEnabled()} 同款
 * 分组形态——Default 组绑定期零约束（enabled 默认 false，未启用时空值合法放行，CI/单测走
 * SimulatedRegistry）；启用组 {@link AdminEnabled} 约束四要素非空，由装配方（IotRegistryConfig
 * Huawei bean 方法）在建 Bean 前调用 {@link #validateAdminEnabled()} 激活 fail-fast。
 *
 * <p>凭证红线：endpoint/projectId/accessKey/accessSecret 仅承 IOTDA_ADMIN_* 环境变量注入，
 * 任何 profile 禁明文默认值；校验失败消息只含字段路径不携带凭证值；toString 固定脱敏
 * （W-5 等保三级纵深防御）。
 */
@Validated
@ConfigurationProperties(prefix = "fuyun.iot.admin")
public record IotdaAdminProperties(
        @DefaultValue("false") boolean enabled,

        @NotBlank(message = "endpoint 缺失（启用 IoTDA 管理面时必填）", groups = AdminEnabled.class)
        String endpoint,

        @NotBlank(message = "projectId 缺失（启用 IoTDA 管理面时必填）", groups = AdminEnabled.class)
        String projectId,

        @NotBlank(message = "accessKey 缺失（启用 IoTDA 管理面时必填）", groups = AdminEnabled.class)
        String accessKey,

        @NotBlank(message = "accessSecret 缺失（启用 IoTDA 管理面时必填）", groups = AdminEnabled.class)
        String accessSecret) {

    /**
     * 启用组标记：仅承载"启用管理面时才生效"的约束（endpoint/projectId/accessKey/accessSecret
     * 非空），绑定期 Default 组校验不触碰本组，由 {@link #validateAdminEnabled()} 显式激活。
     */
    public interface AdminEnabled {}

    /** 启用组校验器：类级单例（ValidatorFactory 构建开销大，随 JVM 生命周期复用） */
    private static final Validator ADMIN_ENABLED_VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * 启用态连接参数 fail-fast 校验：enabled=true 时按启用组校验端点与凭证三要素，任一缺失即
     * 抛出阻断 Huawei bean 装配（宪法 B.4-4：fail-fast 仅限显式启用场景）；enabled=false 直接
     * 放行（空值合法的安全默认姿态）。由 IotRegistryConfig 在建 Huawei bean 前调用。
     *
     * @throws IllegalStateException enabled=true 且 endpoint/projectId/accessKey/accessSecret
     *                               任一缺失；消息只含字段路径与约束描述，不携带凭证值
     */
    public void validateAdminEnabled() {
        if (!enabled) {
            return;
        }
        Set<ConstraintViolation<IotdaAdminProperties>> violations =
                ADMIN_ENABLED_VALIDATOR.validate(this, AdminEnabled.class);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .collect(Collectors.joining("；"));
            throw new IllegalStateException("fuyun.iot.admin.enabled=true 但 IoTDA 管理面参数缺失：" + detail);
        }
    }

    /**
     * 脱敏 toString（W-5，等保三级纵深防御）：accessKey/accessSecret 固定打码，其余字段照常输出。
     *
     * <p>覆写原因：record 默认 toString 会直出全部字段值（含凭证），而「凭证禁入日志」是红线——
     * 不能依赖每个调用方自觉避开整对象打印；管理面凭证（AK/SK）权限高于 AMQP 接入凭证，泄露
     * 影响面更大，打码优先级更高。
     *
     * @return 脱敏文本，非空；凭证字段恒为 ***
     */
    @Override
    public String toString() {
        return "IotdaAdminProperties[enabled=" + enabled
                + ", endpoint=" + endpoint
                + ", projectId=" + projectId
                + ", accessKey=***"
                + ", accessSecret=***"
                + "]";
    }
}
