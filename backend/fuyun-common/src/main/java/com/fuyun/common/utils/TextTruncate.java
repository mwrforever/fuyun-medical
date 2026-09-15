package com.fuyun.common.utils;

/**
 * 文本列宽截断工具：把超长文本按数据库列宽截断，null 原样返回。
 *
 * <p>存在原因（TASK.md W-6① 实证）：畸形帧的异常消息、x-death 来源队列等可超出 VARCHAR 列宽，
 * 直接落库触发整行写入失败——留痕静默丢失违背 M20「不合规信封拒收留痕」红线。截断保留头部
 * （不合规标注在前部，截断后仍可识别违规类型）。
 *
 * <p>无状态静态工具（纯函数），线程安全；common 共享内核承载，各模块复用（禁止各自复制实现）。
 */
public final class TextTruncate {

    /** 纯静态工具类，禁止实例化（backend 宪法 A.2-6）。 */
    private TextTruncate() {}

    /**
     * 按最大字符数截断文本。
     *
     * @param text      原文，可空；null 表示可空列语义，原样返回
     * @param maxLength 最大保留字符数（对应 DB 列宽），必须为正
     * @return 长度不超过 maxLength 的文本；入参为 null 返回 null
     */
    public static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
