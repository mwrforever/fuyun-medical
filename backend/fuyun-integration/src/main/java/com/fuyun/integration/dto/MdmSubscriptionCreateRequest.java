package com.fuyun.integration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 主数据订阅登记请求（POST /api/v1/integration/mdm-subscriptions）。
 *
 * @param topic            主数据主题，非空；取值 dict/org/user/param/practice（服务层按 MdmConstants.TOPICS 复校）
 * @param subscriberModule 订阅方模块域标识，非空且小写（模块名规则与队列命名一致）
 * @param syncMode         同步方式，非空；EVENT_SUBSCRIBE 事件订阅 / API_PULL 接口拉取
 */
public record MdmSubscriptionCreateRequest(
        @NotBlank @Pattern(regexp = "dict|org|user|param|practice", message = "主题仅支持 dict/org/user/param/practice")
        String topic,

        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]*$", message = "订阅方模块标识须为小写字母开头")
        String subscriberModule,

        @NotBlank @Pattern(regexp = "EVENT_SUBSCRIBE|API_PULL", message = "同步方式仅支持 EVENT_SUBSCRIBE 或 API_PULL")
        String syncMode) {}
