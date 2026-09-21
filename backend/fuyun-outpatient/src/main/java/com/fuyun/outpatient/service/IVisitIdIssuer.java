package com.fuyun.outpatient.service;

/**
 * 就诊号签发服务（CF-3 冻结结构 {@code O+yyyyMMdd+5 位流水}=14 位字符串，M03 为 O 型唯一签发主体）：
 * 当日流水经 Redis 键 {@code fy:outpatient:visit-seq:{yyyyMMdd}} INCR 承载（TTL=48h，裁决 11；
 * A.5-1 键规范）。
 */
public interface IVisitIdIssuer {

    /**
     * 签发一枚当日 visit_id。
     *
     * @return visit_id 原文（14 位，O+yyyyMMdd+5 位流水），非空；结构经 VisitIdValidator 签发自检
     * @throws IllegalStateException 当日流水超 5 位上限（10 万号）或签发形态自检失败时触发——结构红线
     *                               fail-fast，建议处理策略：立即告警人工介入，禁止违例值落库
     */
    String issue();
}
