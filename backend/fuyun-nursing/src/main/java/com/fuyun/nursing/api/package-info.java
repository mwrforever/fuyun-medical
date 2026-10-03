/**
 * M05 护理域对外契约唯一出口（backend 宪法 B.1）：错误码、跨模块接口、事件载荷 record。
 * 事件载荷字段 = event_registry 登记契约（V800 id 41–64，P2 增 V1109 id 83），变更属 CF-3/CF-5 契约变更须双向评审。
 */
@NamedInterface("api")
package com.fuyun.nursing.api;

import org.springframework.modulith.NamedInterface;
