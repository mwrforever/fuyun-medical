package com.fuyun.inpatient.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.exception.BizException;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import org.springframework.http.HttpStatus;

/**
 * 住院就诊共享访问器（EX-44 下沉唯一收口点，A.4.3-21 共享访问器条款承载）：模块内
 * 「按 visitId/主键 load 行 → null 判 → 抛 IP-1007」手写副本的统一执行面——各业务服务
 * 经依赖注入调用本访问器，消解跨服务复制查询逻辑（A.4.3-21 禁复制查询）。就诊行系
 * 模块内多服务共用的副表面（inpatient_visit 非任何 ServiceImpl 主表），查询统一
 * Wrappers 手构符合 A.4.3-13 副表面口径。对外契约零变化：错误码/状态码统一
 * IP-1007（404），by-visitId 固定文案、by-pk 文案参数化透传（各调用点「数据不一致」
 * 定位键不同）。线程安全：无状态 singleton，mapper 由 MyBatis 线程安全代理供给。
 */
public class InpatientVisitAccessor {

    private final InpatientVisitMapper visitMapper;

    /**
     * 全参构造器（装配归 InpatientWebConfig @Import）。
     *
     * @param visitMapper 住院就诊 mapper，非空；定位查询唯一数据面
     */
    public InpatientVisitAccessor(InpatientVisitMapper visitMapper) {
        this.visitMapper = visitMapper;
    }

    /**
     * 按就诊号定位行（load+check 统一面）：未命中 fail-closed 抛 IP-1007（404）。
     *
     * @param visitId 住院就诊号（I 型 14 位），非空；来源：REST 路径/入参或事件载荷
     * @return 就诊行，非空（逻辑删由 @TableLogic 自动过滤）
     * @throws BizException IP-1007 就诊号无命中行时触发；建议调用方直接放行（用户输入
     *                     定位键场景，404 即终态语义）
     */
    public InpatientVisit requireByVisitId(String visitId) {
        // 数据库读操作：就诊号唯一定位（uk_visit_id；Wrappers 手构——A.4.3-13 副表面）
        InpatientVisit visit =
                visitMapper.selectOne(Wrappers.<InpatientVisit>lambdaQuery().eq(InpatientVisit::getVisitId, visitId));
        if (visit == null) {
            throw new BizException(InpatientErrorCode.VISIT_NOT_FOUND, HttpStatus.NOT_FOUND, "住院就诊不存在：" + visitId);
        }
        return visit;
    }

    /**
     * 按表主键定位行（load+check 统一面）：未命中 fail-closed 抛 IP-1007（404），文案
     * 参数化透传——各调用点定位键（医嘱号/申请单号/主键等）不同，原样传出保持对外契约
     * 零变化（EX-44 要求：错误码一致仅文案差异时按文案参数化设计）。
     *
     * @param visitPk         住院就诊表主键，非空；来源：关联行 visit_id 外键值
     * @param notFoundMessage 未命中异常文案（调用方拼好定位键），非空
     * @return 就诊行，非空（逻辑删由 @TableLogic 自动过滤）
     * @throws BizException IP-1007 主键无命中行时触发；多为关联外键悬空（数据不一致）场景
     */
    public InpatientVisit requireByPk(Long visitPk, String notFoundMessage) {
        // 数据库读操作：表主键定位（ASSIGN_ID 雪花主键）
        InpatientVisit visit = visitMapper.selectById(visitPk);
        if (visit == null) {
            throw new BizException(InpatientErrorCode.VISIT_NOT_FOUND, HttpStatus.NOT_FOUND, notFoundMessage);
        }
        return visit;
    }
}
