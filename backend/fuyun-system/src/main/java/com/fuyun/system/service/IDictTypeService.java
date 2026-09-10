package com.fuyun.system.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.system.dto.DictTypeCreateRequest;
import com.fuyun.system.entity.DictTypeEntity;
import com.fuyun.system.vo.DictTypeVO;

/**
 * 字典类型服务（system.dict_type 数据访问与类型创建用例，BRIEF-PR3-01 §3.2）。
 *
 * <p>CRUD 型接口继承 IService（宪法 A.4.3-20）；P0 切片仅提供创建与按编码查询
 * （完整类型管理 CRUD UI/端点属 P0 明确不做范围，简报 §0）。
 */
public interface IDictTypeService extends IService<DictTypeEntity> {

    /**
     * 创建字典类型：typeCode 全局唯一校验（重复抛 SYS-1014/409），成功落库并返回类型出参。
     *
     * @param request 创建请求（typeCode/typeName 非空与编码格式由 @Valid 保证），非空
     * @return 类型出参（含雪花 ID），非空
     * @throws com.fuyun.common.exception.BizException SYS-1014（字典类型编码已存在，409）
     */
    DictTypeVO createType(DictTypeCreateRequest request);

    /**
     * 按编码查询字典类型。
     *
     * @param typeCode 字典类型编码，非空
     * @return 类型实体；不存在或已逻辑删返回 null
     */
    DictTypeEntity getByTypeCode(String typeCode);
}
