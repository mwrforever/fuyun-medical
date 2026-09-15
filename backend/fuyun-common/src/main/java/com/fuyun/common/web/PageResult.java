package com.fuyun.common.web;

import java.util.List;

/**
 * 分页响应契约（backend 宪法 A.3-6）：请求 page 为 <b>0 基</b>，响应四字段 {content, page, size, total}。
 *
 * <p>本类为全模块统一分页出参形态的公共载体（共享内核 common），刻意不依赖 MyBatis-Plus：
 * 服务层负责把 IPage 转为本对象，common 保持零数据访问依赖（宪法 B.1 公共模块边界）。
 * 无状态不可变载体（record，A.1-2），可跨线程安全共享。
 *
 * @param content 当前页数据清单，非空；可为空清单（无匹配行场景）
 * @param page    当前页码（0 基，与请求同口径），非空
 * @param size    单页条数（请求期望值），非空
 * @param total   符合条件总条数（跨页累计），非空；用于前端计算总页数
 * @param <T>     数据元素类型
 */
public record PageResult<T>(List<T> content, long page, long size, long total) {

    /**
     * 构造分页出参。
     *
     * @param content 当前页数据清单，非空；可为空清单
     * @param page    当前页码（0 基），非空
     * @param size    单页条数，非空
     * @param total   总条数，非空
     * @param <T>     数据元素类型
     * @return 分页出参，非空
     */
    public static <T> PageResult<T> of(List<T> content, long page, long size, long total) {
        return new PageResult<>(content, page, size, total);
    }
}
