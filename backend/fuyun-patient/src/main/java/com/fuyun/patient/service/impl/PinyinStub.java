package com.fuyun.patient.service.impl;

/**
 * 姓名拼音承载（name_pinyin 列写路径）：PR-2 不引入拼音库（版本红线：表外依赖须申报），
 * 以原样小写文本承载（检索侧 name 精确命中为主路径，name_pinyin 为后续拼音库接入预留列）。
 * 拼音库引入属表外依赖申报事项，随检索优化需求另立决策。
 */
public final class PinyinStub {

    /** 纯静态工具类，禁止实例化 */
    private PinyinStub() {}

    /**
     * 承载姓名检索键：当前返回小写原文（中文原样），保证列非空与索引可用。
     *
     * @param name 姓名，非空
     * @return 检索键文本
     */
    public static String toPinyin(String name) {
        return name == null ? null : name.toLowerCase();
    }
}
