package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.GatewayQueryRequest;
import com.fuyun.iot.dto.SaveGatewayRequest;
import com.fuyun.iot.entity.IotGatewayEntity;
import com.fuyun.iot.mapper.IotGatewayMapper;
import com.fuyun.iot.service.IGatewayService;
import com.fuyun.iot.vo.GatewayVO;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 边缘网关管理服务实现（iot.iot_gateway 唯一写入口，FU-M14-12 本地档案面，P2 PR-2 Task 11）：
 * 网关 CRUD（软删）与热备对端校验单点。注册与拓扑经 IoTDA 维护（Registry 直通占位，Spec
 * 「拓扑经 IoTDA 维护」），本实现零 Registry 依赖——禁在档案服务内发起云端管理面调用。
 *
 * <p>standby 校验语义（brief 冻结「校验语义取贴切实现并注记」）：standby_of 归一空白为 null
 * （无双机热备场景）；非空时①对端须存在（{@code @TableLogic} 自动过滤已删行——指向已删对端
 * 同样拒绝）、②禁自引用、③禁成环（沿 standby 链游走，回到保存目标即环；visited 集合防既有
 * 脏数据成环时死循环）。错误码统一 409 IOT-1025（借 404 词表不贴切：属保存请求的字段关系
 * 校验而非资源查询）。删除守卫：他网关以本网关为热备对端时拒删（防悬挂 standby_of 引用，
 * 热备关系须先行解除）。
 *
 * <p>装配归 IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）；JaCoCo 核心包
 * （com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class GatewayServiceImpl implements IGatewayService {

    private final IotGatewayMapper gatewayMapper;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param gatewayMapper 网关档案 mapper，非空；来源：同模块 mapper 包
     */
    public GatewayServiceImpl(IotGatewayMapper gatewayMapper) {
        this.gatewayMapper = gatewayMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<GatewayVO> page(GatewayQueryRequest request) {
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();
        // 数据库读操作：过滤分页（gateway_id 升序稳定输出；@TableLogic 自动携带 deleted=0）
        Page<IotGatewayEntity> result = gatewayMapper.selectPage(
                new Page<>(page + 1L, size),
                Wrappers.<IotGatewayEntity>lambdaQuery()
                        .eq(request.wardId() != null, IotGatewayEntity::getWardId, request.wardId())
                        .eq(request.mode() != null, IotGatewayEntity::getMode, request.mode())
                        .eq(request.status() != null, IotGatewayEntity::getStatus, request.status())
                        .eq(
                                request.gatewayId() != null
                                        && !request.gatewayId().isBlank(),
                                IotGatewayEntity::getGatewayId,
                                request.gatewayId())
                        .orderByAsc(IotGatewayEntity::getGatewayId));
        return PageResult.of(result.getRecords().stream().map(GatewayVO::from).toList(), page, size, result.getTotal());
    }

    @Override
    @Transactional
    public GatewayVO create(SaveGatewayRequest request) {
        // 数据库读操作：自然键唯一性应用层前置（@TableLogic 过滤已删行——软删网关重登记不受此预检拦截）
        if (gatewayMapper.selectById(request.gatewayId()) != null) {
            throw new BizException(
                    IotErrorCode.GATEWAY_ALREADY_EXISTS, HttpStatus.CONFLICT, "网关已存在：" + request.gatewayId());
        }
        IotGatewayEntity entity = new IotGatewayEntity();
        applyRequest(entity, request);
        validateStandby(entity);
        try {
            // 数据库写操作：网关档案落行（自然键直写，@TableId(INPUT)；PK 约束兜底并发与软删占位）
            gatewayMapper.insert(entity);
        } catch (DataIntegrityViolationException e) {
            // PK 冲突翻译 IOT-1024 409：①软删行仍占物理 PK（唯一性预检按逻辑删过滤，探测不到）；
            // ②并发重复登记竞态。软删网关重登记须先恢复原行（运维处置），不做静默物理复活
            throw new BizException(
                    IotErrorCode.GATEWAY_ALREADY_EXISTS,
                    HttpStatus.CONFLICT,
                    "网关标识已存在（含已删除档案占位），如为软删网关须先恢复原行：" + request.gatewayId());
        }
        log.info(
                "边缘网关已登记：gatewayId={}，name={}，mode={}，wardId={}，standbyOf={}",
                entity.getGatewayId(),
                entity.getGatewayName(),
                entity.getMode(),
                entity.getWardId(),
                entity.getStandbyOf());
        return GatewayVO.from(entity);
    }

    @Override
    @Transactional
    public GatewayVO update(String gatewayId, SaveGatewayRequest request) {
        IotGatewayEntity entity = requireGateway(gatewayId);
        // 路径 ID 为定位权威（请求体同名 ID 仅契约回显）：以路径 ID 重构请求后投影，锁死自然键防漂移
        applyRequest(
                entity,
                new SaveGatewayRequest(
                        gatewayId,
                        request.gatewayName(),
                        request.mode(),
                        request.standbyOf(),
                        request.wardId(),
                        request.status()));
        validateStandby(entity);
        // 数据库写操作：字段全量覆写（updated_at 由数据库触发器维护，应用层不触碰审计列）
        gatewayMapper.updateById(entity);
        log.info(
                "边缘网关已更新：gatewayId={}，name={}，status={}，standbyOf={}",
                entity.getGatewayId(),
                entity.getGatewayName(),
                entity.getStatus(),
                entity.getStandbyOf());
        return GatewayVO.from(entity);
    }

    @Override
    @Transactional
    public void delete(String gatewayId) {
        requireGateway(gatewayId);
        // 数据库读操作：删除守卫——他网关 standby_of 反查（部分索引 idx_iot_gateway_standby 准入）
        Long inboundRefs = gatewayMapper.selectCount(Wrappers.<IotGatewayEntity>lambdaQuery()
                .eq(IotGatewayEntity::getStandbyOf, gatewayId)
                .ne(IotGatewayEntity::getGatewayId, gatewayId));
        if (inboundRefs != null && inboundRefs > 0) {
            throw new BizException(
                    IotErrorCode.GATEWAY_STANDBY_INVALID, HttpStatus.CONFLICT, "存在其他网关以本网关为热备对端，须先解除热备关系：" + gatewayId);
        }
        // 数据库写操作：软删（@TableLogic 逻辑删；iot_device.gateway_id 历史引用留痕由设备管理域处置）
        gatewayMapper.deleteById(gatewayId);
        log.info("边缘网关已删除（软删）：gatewayId={}", gatewayId);
    }

    /**
     * 请求字段全量投影到实体（create/update 共用一形）：自然键取请求值（update 路径已由
     * {@code withGatewayId} 锁死为路径 ID）。
     *
     * @param entity  待填充实体，非空
     * @param request 保存请求，非空
     */
    private static void applyRequest(IotGatewayEntity entity, SaveGatewayRequest request) {
        entity.setGatewayId(request.gatewayId());
        entity.setGatewayName(request.gatewayName());
        entity.setMode(request.mode());
        // 热备对端空白归一为 null（无双机热备场景落空列，防空白脏值进 standby 链游走）
        entity.setStandbyOf(request.standbyOf() == null || request.standbyOf().isBlank() ? null : request.standbyOf());
        entity.setWardId(request.wardId());
        entity.setStatus(request.status());
    }

    /**
     * 热备对端校验（存在/不自引用/不成环，IOT-1025 三分支统一 409）：非空 standby_of 沿链游走
     * 途中任一节点回到保存目标即判环。游走以 visited 集合兜底——既有数据若已存在不含保存目标的
     * 环（脏数据），终止游走按无环放行（保存动作不为历史脏数据负责，避免误伤）。
     *
     * @param entity 待校验实体（gatewayId/standbyOf 已投影），非空
     * @throws BizException IOT-1025（409；对端不存在/自引用/成环）
     */
    private void validateStandby(IotGatewayEntity entity) {
        String standbyOf = entity.getStandbyOf();
        if (standbyOf == null) {
            return;
        }
        if (standbyOf.equals(entity.getGatewayId())) {
            throw new BizException(IotErrorCode.GATEWAY_STANDBY_INVALID, HttpStatus.CONFLICT, "热备对端禁自引用：" + standbyOf);
        }
        Set<String> visited = new HashSet<>();
        String cursor = standbyOf;
        while (cursor != null && visited.add(cursor)) {
            // 数据库读操作：游走节点存在性校验（@TableLogic 自动过滤已删行——已删对端等同不存在）
            IotGatewayEntity node = gatewayMapper.selectById(cursor);
            if (node == null) {
                throw new BizException(IotErrorCode.GATEWAY_STANDBY_INVALID, HttpStatus.CONFLICT, "热备对端不存在：" + cursor);
            }
            if (cursor.equals(entity.getGatewayId())) {
                throw new BizException(
                        IotErrorCode.GATEWAY_STANDBY_INVALID,
                        HttpStatus.CONFLICT,
                        "热备对端成环（游走回到本网关）：" + entity.getGatewayId() + "→" + standbyOf);
            }
            cursor = node.getStandbyOf();
        }
    }

    /**
     * 按标识取网关行，不存在即 404（@TableLogic 已删行等同不存在）。
     *
     * @param gatewayId 网关标识，非空
     * @return 网关实体，非空
     * @throws BizException IOT-1023（404；gateway_id 无命中）
     */
    private IotGatewayEntity requireGateway(String gatewayId) {
        IotGatewayEntity entity = gatewayMapper.selectById(gatewayId);
        if (entity == null) {
            throw new BizException(IotErrorCode.GATEWAY_NOT_FOUND, HttpStatus.NOT_FOUND, "网关不存在：" + gatewayId);
        }
        return entity;
    }
}
