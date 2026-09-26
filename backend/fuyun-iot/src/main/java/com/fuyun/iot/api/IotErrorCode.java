package com.fuyun.iot.api;

import com.fuyun.common.exception.ErrorCode;

/**
 * M14 医疗设备物联网平台模块错误码枚举（IOT-xxxx，backend 宪法 A.3-4）。
 *
 * <p>落 api 包为宪法 B.1 明文（错误码枚举属对外契约）；实现 common {@link ErrorCode} 契约，
 * 全项目编码唯一（当前 IOT- 前缀无其他占用）。业务异常抛
 * {@code BizException(IotErrorCode.XXX, HttpStatus, message)}，由全局渲染器输出
 * RFC 9457 ProblemDetail（properties.errorCode/traceId），禁止"全 200 + 错误码"。
 *
 * <p>B4.2 仅登记 IOT-1001（兜底通道鉴权失败码，供 B4.3 HTTP 兜底端点使用）；P2 PR-2 Task 1
 * 按 GC12 骨架扩至 IOT-1022（产品/物模型/设备/绑定/告警/命令/联动/遥测查询/消费错误/注册中心
 * 十域词表），码值随 Task 2–11 业务实装逐域消费，后续新增一律接续顺延。
 */
public enum IotErrorCode implements ErrorCode {

    /** 兜底通道鉴权失败（401；X-Iot-Fallback-Token 头缺失或不匹配，独立鉴权不走 M01 令牌） */
    FALLBACK_AUTH_FAILED("IOT-1001"),
    /** 产品不存在（404；product_id 无命中） */
    PRODUCT_NOT_FOUND("IOT-1002"),
    /** 产品状态不允许该操作（409；状态机违例：非草稿态改物模型、已发布再编辑等） */
    PRODUCT_STATE_NOT_ALLOWED("IOT-1003"),
    /** 物模型字典不存在（404；metric_code 在 iot_metric_dict 无命中） */
    METRIC_DICT_NOT_FOUND("IOT-1004"),
    /** 物模型映射冲突（409；metric_code↔IoTDA 属性名映射唯一键冲突） */
    METRIC_MAPPING_CONFLICT("IOT-1005"),
    /** 设备不存在（404；device_id 无命中） */
    DEVICE_NOT_FOUND("IOT-1006"),
    /** 设备状态不允许该操作（409；状态机违例：非 INACTIVE 注册、DISABLED 再操作等） */
    DEVICE_STATE_NOT_ALLOWED("IOT-1007"),
    /** 设备已存在（409；device_id 重复注册） */
    DEVICE_ALREADY_EXISTS("IOT-1008"),
    /** 绑定不存在（404；binding 无命中） */
    BINDING_NOT_FOUND("IOT-1009"),
    /** 绑定状态不允许该操作（409；状态机违例：非 BOUND 解绑、重复解绑等） */
    BINDING_STATE_NOT_ALLOWED("IOT-1010"),
    /** 绑定校验不通过（409；设备停用/visit 不在院/床位无效任一不过） */
    BINDING_CHECK_INVALID("IOT-1011"),
    /** 告警规则不存在（404；rule_id 无命中） */
    ALARM_RULE_NOT_FOUND("IOT-1012"),
    /** 告警规则状态不允许该操作（409；状态机违例） */
    ALARM_RULE_STATE_NOT_ALLOWED("IOT-1013"),
    /** 命令不允许下发（409；白名单未放行/设备离线/治疗级未豁免） */
    COMMAND_NOT_ALLOWED("IOT-1014"),
    /** 命令二次确认非法（400；确认凭证缺失/过期/已用） */
    COMMAND_CONFIRM_INVALID("IOT-1015"),
    /** 命令不存在（404；command_no 无命中） */
    COMMAND_NOT_FOUND("IOT-1016"),
    /** 联动规则不存在（404；rule_id 无命中） */
    LINKAGE_RULE_NOT_FOUND("IOT-1017"),
    /** 联动状态不允许该操作（409；状态机违例） */
    LINKAGE_STATE_NOT_ALLOWED("IOT-1018"),
    /** 遥测查询参数非法（400；时窗/档位非法） */
    TELEMETRY_QUERY_INVALID("IOT-1019"),
    /** 消费错误记录不存在（404；consume_error 无命中） */
    CONSUME_ERROR_NOT_FOUND("IOT-1020"),
    /** 消费错误状态不允许该操作（409；状态机违例） */
    CONSUME_ERROR_STATE_NOT_ALLOWED("IOT-1021"),
    /** 注册中心不可用（503；IoTDA 管理面不可达） */
    REGISTRY_UNAVAILABLE("IOT-1022");

    /** 错误码字符串，格式 {@code <模块助记>-<4位数字>} */
    private final String code;

    IotErrorCode(String code) {
        this.code = code;
    }

    /**
     * 取业务错误码。
     *
     * @return 错误码字符串（如 IOT-1001），非空；经全局渲染输出至 ProblemDetail.properties.errorCode
     */
    @Override
    public String getCode() {
        return code;
    }
}
