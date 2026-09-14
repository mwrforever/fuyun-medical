/**
 * 公共内核模块（OPEN）：异常基座/全局渲染/审计支撑/工具/消息契约（StandardTelemetryMessage/EventEnvelope 等），
 * 被全部业务模块依赖且不依赖任何业务模块（宪法 B.1）。
 *
 * <p>OPEN 语义（官方存量代码渐进迁移机制）：子包（messaging/utils/exception 等）对全部模块可访问，
 * 免去逐子包 NamedInterface 声明；共享内核即全系统横向能力，开放属职责本身。
 */
@ApplicationModule(type = Type.OPEN)
package com.fuyun.common;

import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.ApplicationModule.Type;
