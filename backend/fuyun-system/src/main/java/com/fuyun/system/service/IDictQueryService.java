package com.fuyun.system.service;

import com.fuyun.system.vo.DictVersionVO;

/**
 * 字典契约型读服务（GET /api/v1/system/dicts/{type}，M01 Spec §7 业务读口径）。
 *
 * <p>聚合读接口不继承 IService（宪法 A.4.3-20：聚合/报表型接口注入所需 mapper）。
 */
public interface IDictQueryService {

    /**
     * 读取字典版本（含条目全量清单）：version 为空取当前 PUBLISHED 版本，否则取指定版本
     * （任意状态，供预览/回溯）。条目按 sort 升序返回。
     *
     * <p>分页豁免声明（宪法 A.4.3-17 例外）：契约型读接口——字典消费方（前端/下游模块）
     * 需要版本全量条目做本地装载与对账，M01 Spec §7 明示该接口无分页语义，条目量级
     * 由字典治理约束（P0 无分页参数），豁免理由随本接口 javadoc 固化。
     *
     * @param typeCode 字典类型编码，非空；类型不存在抛 SYS-1011
     * @param version  版本号，可空；null=当前 PUBLISHED 版本，非空=指定版本（任意状态）
     * @return 版本出参（含条目清单），非空
     * @throws com.fuyun.common.exception.BizException SYS-1011（字典类型不存在，404）、
     *                                                 SYS-1012（字典版本不存在/无已发布版本，404）
     */
    DictVersionVO readPublished(String typeCode, Integer version);
}
