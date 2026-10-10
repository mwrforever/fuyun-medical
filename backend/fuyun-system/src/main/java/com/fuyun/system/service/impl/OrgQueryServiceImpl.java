package com.fuyun.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.system.entity.OrgEntity;
import com.fuyun.system.enums.OrgStatus;
import com.fuyun.system.enums.OrgType;
import com.fuyun.system.mapper.OrgMapper;
import com.fuyun.system.service.IOrgQueryService;
import com.fuyun.system.vo.OrgVO;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织机构清单读服务实现（GET /api/v1/system/orgs 执行点）：sys_org 单表条件读——
 * 类型精确过滤 + 启用态过滤 + sort/orgCode 双键稳定排序。
 *
 * <p>读接口不继承 IService，直接注入 OrgMapper（宪法 A.4.3-20 聚合型接口口径）；条件构造经
 * Wrappers 静态工厂（DictQueryServiceImpl 同款形态），查询 select 精确投影（A.4.3-14），
 * 排序携 orgCode 唯一次序键（A.4.3-17 顺序唯一约束）。逻辑删 deleted=0 由 @TableLogic
 * 自动携带，禁停用机构出网由 status=ACTIVE 显式过滤承载。装配归 SystemWebConfig @Import。
 */
@Slf4j
public class OrgQueryServiceImpl implements IOrgQueryService {

    /** 机构数据访问：清单读唯一数据源（app 侧 @MapperScan 注册） */
    private final OrgMapper orgMapper;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param orgMapper 机构 mapper，非空
     */
    public OrgQueryServiceImpl(OrgMapper orgMapper) {
        this.orgMapper = orgMapper;
    }

    /**
     * 按类型查询启用机构清单：type 转 OrgType（非法 code 由 fromCode 收口 SYS-1031/400），
     * 条目按 sort 升序 + orgCode 唯一次序键映射出参（分页豁免理由见接口 javadoc）。
     */
    @Override
    @Transactional(readOnly = true)
    public List<OrgVO> listByType(String type) {
        // 外部入参转枚举：未知 code 抛 SYS-1031/400（OrgType.fromCode 统一收口，禁静默吞 null）
        OrgType orgType = OrgType.fromCode(type);
        // 单表条件读：类型 + 启用态双过滤，select 精确投影（禁 SELECT *），sort/orgCode 双键稳定排序
        List<OrgEntity> orgs = orgMapper.selectList(Wrappers.<OrgEntity>lambdaQuery()
                .eq(OrgEntity::getOrgType, orgType)
                .eq(OrgEntity::getStatus, OrgStatus.ACTIVE)
                .select(
                        OrgEntity::getId,
                        OrgEntity::getOrgCode,
                        OrgEntity::getOrgName,
                        OrgEntity::getOrgType,
                        OrgEntity::getOrgAttr,
                        OrgEntity::getParentId,
                        OrgEntity::getSort,
                        OrgEntity::getStatus)
                .orderByAsc(OrgEntity::getSort)
                .orderByAsc(OrgEntity::getOrgCode));
        List<OrgVO> result = orgs.stream().map(OrgQueryServiceImpl::toVO).toList();
        log.info("组织机构清单读取完成：type={}，count={}", type, result.size());
        return result;
    }

    /**
     * 实体 → 出参逐列映射（扁平 record 手写直映射，字段一一对应；经 OrgQueryServiceImplTest
     * 逐列断言全覆盖，关键映射单测纪律满足宪法 A.7-4）。
     *
     * @param entity 机构实体投影行，非空
     * @return 机构出参，非空
     */
    private static OrgVO toVO(OrgEntity entity) {
        return new OrgVO(
                entity.getId(),
                entity.getOrgCode(),
                entity.getOrgName(),
                entity.getOrgType(),
                entity.getOrgAttr(),
                entity.getParentId(),
                entity.getSort(),
                entity.getStatus());
    }
}
