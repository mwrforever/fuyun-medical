package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.CreateMetricRequest;
import com.fuyun.iot.entity.IotMetricDictEntity;
import com.fuyun.iot.enums.MetricCategory;
import com.fuyun.iot.mapper.IotMetricDictMapper;
import com.fuyun.iot.service.IMetricDictService;
import com.fuyun.iot.vo.MetricDictVO;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * MDC 术语字典服务实现（iot.iot_metric_dict 唯一写入口）：模块自管专业字典（不经 M01）的
 * 清单查询与登记。硬删除口径：字典表无 deleted 列，登记冲突走前置存在性校验拒绝（409）。
 *
 * <p>错误码说明：重复登记的 IOT-1005 为 Task 1 冻结词表内唯一"唯一键冲突"码位（其字面语义
 * 为映射冲突，此处借承字典主键冲突场景并显式留消息区分）；如需专属码位走顺延提案修订词表。
 *
 * <p>装配归 IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）；JaCoCo 核心包
 * （com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class MetricDictServiceImpl extends ServiceImpl<IotMetricDictMapper, IotMetricDictEntity>
        implements IMetricDictService {

    /** 构造器（装配归 IotConfig @Import；ServiceImpl 泛型 mapper 由容器注入基类字段） */
    public MetricDictServiceImpl() {}

    @Override
    @Transactional(readOnly = true)
    public List<MetricDictVO> list(MetricCategory category) {
        // 数据库读操作：类别过滤缺席即全量；metric_code 升序稳定（自然键字典序）
        return lambdaQuery()
                .eq(category != null, IotMetricDictEntity::getCategory, category)
                .orderByAsc(IotMetricDictEntity::getMetricCode)
                .list()
                .stream()
                .map(MetricDictVO::from)
                .toList();
    }

    @Override
    @Transactional
    public MetricDictVO create(CreateMetricRequest request) {
        // 唯一键前置拒绝：metric_code 已存在即 IOT-1005（写库前拦截；硬删除表无并发软删窗口）
        if (baseMapper.selectById(request.metricCode()) != null) {
            log.warn("字典登记拒绝：metric_code 已存在：metricCode={}", request.metricCode());
            throw new BizException(
                    IotErrorCode.METRIC_MAPPING_CONFLICT,
                    HttpStatus.CONFLICT,
                    "MDC 字典编码已存在，禁止重复登记：" + request.metricCode());
        }
        IotMetricDictEntity entity = new IotMetricDictEntity();
        entity.setMetricCode(request.metricCode());
        entity.setMetricName(request.metricName());
        entity.setCategory(request.category());
        entity.setDataType(request.dataType());
        entity.setUnit(request.unit());
        entity.setPhysioMin(request.physioMin());
        entity.setPhysioMax(request.physioMax());
        entity.setDefaultLevel(request.defaultLevel());
        baseMapper.insert(entity);
        log.info(
                "MDC 字典登记完成：metricCode={}，category={}",
                entity.getMetricCode(),
                entity.getCategory().getCode());
        return MetricDictVO.from(entity);
    }
}
