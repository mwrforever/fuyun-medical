package com.fuyun.system.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.convert.DictConverter;
import com.fuyun.system.dto.DictTypeCreateRequest;
import com.fuyun.system.entity.DictTypeEntity;
import com.fuyun.system.mapper.DictTypeMapper;
import com.fuyun.system.service.IDictTypeService;
import com.fuyun.system.vo.DictTypeVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 字典类型服务实现（system.dict_type 数据访问与类型创建执行点）。
 *
 * <p>创建语义：typeCode 唯一性前置校验（SYS-1014/409），并发兜底为
 * uk_dict_type_type_code 部分唯一索引（V301）；国标标记 null 按 false 落库。
 * 单表链式操作（宪法 A.4.3-13）+ select 精确投影（A.4.3-14）。
 *
 * <p>装配说明：com.fuyun.system 不在组件扫描范围，Bean 注册点为 SystemWebConfig @Import。
 */
@Slf4j
public class DictTypeServiceImpl extends ServiceImpl<DictTypeMapper, DictTypeEntity> implements IDictTypeService {

    private final DictConverter dictConverter;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param dictConverter 字典域转换器，非空；创建响应组装
     */
    public DictTypeServiceImpl(DictConverter dictConverter) {
        this.dictConverter = dictConverter;
    }

    /**
     * 创建字典类型（POST /api/v1/system/dict-types 执行点）：typeCode 全局唯一的新类型登记。
     *
     * <p>执行流程：typeCode 唯一性前置校验（已删行不占用唯一性，@TableLogic 条件自动携带
     * deleted=0）→ 实体组装（国标标记 null 收口为 false）→ 落库 → 转 VO。前置校验挡常态
     * 重复，并发窗口由 uk_dict_type_type_code 部分唯一索引（V301）兜底回滚。
     *
     * @param request 创建请求，非空（typeCode 小写点分格式、typeName 非空由 controller 层
     *                @Valid 保证）；nationalStandard 空=false、true=国标字典编码不可修改
     *                （FU-M01-06）；remark 为自由备注
     * @return 类型出参（含落库后雪花 ID），非空
     * @throws BizException SYS-1014（字典类型编码已存在，HTTP 409，建议更换编码或改用既有类型）
     */
    @Override
    @Transactional
    public DictTypeVO createType(DictTypeCreateRequest request) {
        // 唯一性前置校验（已删行不占用唯一性：@TableLogic 条件自动携带 deleted=0）
        boolean exists = this.lambdaQuery()
                .eq(DictTypeEntity::getTypeCode, request.typeCode())
                .select(DictTypeEntity::getId)
                .exists();
        if (exists) {
            log.warn("字典类型创建被拒（编码已存在）：typeCode={}", request.typeCode());
            throw new BizException(
                    SystemErrorCode.DICT_TYPE_CODE_EXISTS, HttpStatus.CONFLICT, "字典类型编码已存在：" + request.typeCode());
        }
        DictTypeEntity entity = new DictTypeEntity();
        entity.setTypeCode(request.typeCode());
        entity.setTypeName(request.typeName());
        // 国标标记缺省为 false（Boolean 入参可空语义收口）
        entity.setNationalStandard(request.nationalStandard() != null && request.nationalStandard());
        entity.setRemark(request.remark());
        this.save(entity);
        log.info(
                "字典类型创建完成：typeCode={}，id={}，nationalStandard={}",
                entity.getTypeCode(),
                entity.getId(),
                entity.getNationalStandard());
        return dictConverter.toTypeVO(entity);
    }

    /**
     * 按编码查询字典类型（跨表查询经服务入口，A.4.3-21）：字典版本创建等用例的类型存在性
     * 校验通道。select 精确投影仅含 id/typeCode 两列——typeName 等其余字段为 null，调用方
     * 仅可作存在性判定与 typeCode 取值，不得当作完整实体使用。
     *
     * @param typeCode 字典类型编码，非空；来源：上游业务入参（如 createVersion 的路径参数）
     * @return 类型实体（id + typeCode 投影）；不存在或已逻辑删（@TableLogic 自动过滤）返回
     *         null，由调用方按业务口径处置（如抛 SYS-1011）
     */
    @Override
    @Transactional(readOnly = true)
    public DictTypeEntity getByTypeCode(String typeCode) {
        return this.lambdaQuery()
                .eq(DictTypeEntity::getTypeCode, typeCode)
                .select(DictTypeEntity::getId, DictTypeEntity::getTypeCode)
                .one();
    }
}
