package com.fuyun.system.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.system.dto.DictItemCreateRequest;
import com.fuyun.system.entity.DictItemEntity;
import com.fuyun.system.vo.DictItemVO;

/**
 * 字典条目服务（system.dict_item 数据访问与条目新增用例，BRIEF-PR3-01 §3.2）。
 *
 * <p>CRUD 型接口继承 IService（宪法 A.4.3-20）；P0 切片仅提供草稿版本内的条目新增
 * （条目编辑/删除随 P1 完整字典管理交付）。
 */
public interface IDictItemService extends IService<DictItemEntity> {

    /**
     * 新增字典条目：所属版本必须存在且为 DRAFT（已发布版本禁改，版本不可变性 M01 Spec §5）。
     *
     * <p>同版本内 itemCode 唯一性由 uk_dict_item_version_code 部分唯一索引兜底（并发安全）。
     *
     * @param versionId 所属字典版本 ID，非空
     * @param request   条目创建请求（itemCode/itemName 非空由 @Valid 保证），非空
     * @return 条目出参，非空
     * @throws com.fuyun.common.exception.BizException SYS-1012（字典版本不存在，404）、
     *                                                 SYS-1013（版本状态不允许维护条目，409，仅 DRAFT）
     */
    DictItemVO addItem(Long versionId, DictItemCreateRequest request);
}
