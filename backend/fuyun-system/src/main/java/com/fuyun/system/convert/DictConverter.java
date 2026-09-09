package com.fuyun.system.convert;

import com.fuyun.system.entity.DictItemEntity;
import com.fuyun.system.entity.DictTypeEntity;
import com.fuyun.system.entity.DictVersionEntity;
import com.fuyun.system.vo.DictItemVO;
import com.fuyun.system.vo.DictTypeVO;
import com.fuyun.system.vo.DictVersionVO;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * 字典域 MapStruct 转换器（BRIEF-PR3-01 §3.2）：字典三表实体 → 出参对象映射。
 *
 * <p>componentModel 取默认（非 spring）：Bean 注册点为 SystemWebConfig @Import 经
 * {@link Mappers#getMapper} 装配（宪法 B.1 装配归 app 侧配置）。
 */
@Mapper
public interface DictConverter {

    /** 默认组件模型的生成实现获取入口（单测与装配同源） */
    DictConverter INSTANCE = Mappers.getMapper(DictConverter.class);

    /**
     * 字典类型实体 → 类型出参（创建响应）。
     *
     * @param entity 字典类型实体，非空
     * @return 类型出参，非空
     */
    DictTypeVO toTypeVO(DictTypeEntity entity);

    /**
     * 字典条目实体 → 条目出参（ext_attrs 列 P0 不出参）。
     *
     * @param entity 字典条目实体，非空
     * @return 条目出参，非空
     */
    DictItemVO toItemVO(DictItemEntity entity);

    /**
     * 条目实体清单 → 条目出参清单。
     *
     * @param entities 条目实体清单，非空（可为空清单）
     * @return 条目出参清单，非 null
     */
    List<DictItemVO> toItemVOs(List<DictItemEntity> entities);

    /**
     * 版本出参组装：类型编码（跨表）+ 版本状态与发布时刻 + 条目清单聚合。
     *
     * @param type   所属字典类型实体，非空；取 typeCode
     * @param version 字典版本实体，非空；取 version/status/publishedAt
     * @param items  条目出参清单，非空（可为空清单）
     * @return 版本出参，非空
     */
    @Mapping(target = "typeCode", source = "type.typeCode")
    @Mapping(target = "version", source = "version.version")
    @Mapping(target = "status", source = "version.status")
    @Mapping(target = "publishedAt", source = "version.publishedAt")
    @Mapping(target = "items", source = "items")
    DictVersionVO toVersionVO(DictTypeEntity type, DictVersionEntity version, List<DictItemVO> items);
}
