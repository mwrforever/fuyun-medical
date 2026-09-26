package com.fuyun.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.CreateMetricRequest;
import com.fuyun.iot.entity.IotMetricDictEntity;
import com.fuyun.iot.enums.MetricCategory;
import com.fuyun.iot.enums.MetricDataType;
import com.fuyun.iot.mapper.IotMetricDictMapper;
import com.fuyun.iot.vo.MetricDictVO;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * MDC 术语字典服务单测（P2 PR-2 Task 4 Step 3）：字典清单类别过滤、登记落行、重复登记
 * IOT-1005 唯一键冲突（409）。字典表无 deleted（硬删除），清单按 metric_code 升序稳定输出。
 */
@ExtendWith(MockitoExtension.class)
class MetricDictServiceImplTest {

    private static final String METRIC_CODE = "MDC_ECG_HEART_RATE";

    @Mock
    private IotMetricDictMapper metricDictMapper;

    @Captor
    private ArgumentCaptor<IotMetricDictEntity> insertCaptor;

    private MetricDictServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotMetricDictEntity.class);
    }

    @BeforeEach
    void setUp() {
        // 无 Spring 上下文直构（Bean 注册归 app 侧 IotConfig @Import）；ServiceImpl 基类字段手工注入
        service = new MetricDictServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", metricDictMapper);
        ReflectionTestUtils.setField(service, "entityClass", IotMetricDictEntity.class);
    }

    @Test
    @DisplayName("清单：类别过滤条件缺席不过滤，实体经 VO 工厂升序出网")
    void listFiltersByCategory() {
        when(metricDictMapper.selectList(any())).thenReturn(List.of(dictEntity()));

        List<MetricDictVO> result = service.list(MetricCategory.VITAL_SIGN);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).metricCode()).isEqualTo(METRIC_CODE);
        assertThat(result.get(0).category()).isEqualTo(MetricCategory.VITAL_SIGN);
    }

    @Test
    @DisplayName("登记成功：字段全量落行（生理极限/单位/默认级别）")
    void createInsertsDictRow() {
        when(metricDictMapper.selectById(METRIC_CODE)).thenReturn(null);

        service.create(new CreateMetricRequest(
                METRIC_CODE,
                "心率",
                MetricCategory.VITAL_SIGN,
                MetricDataType.NUMERIC,
                "次每分",
                new BigDecimal("20"),
                new BigDecimal("300"),
                "WARNING"));

        verify(metricDictMapper).insert(insertCaptor.capture());
        IotMetricDictEntity inserted = insertCaptor.getValue();
        assertThat(inserted.getMetricCode()).isEqualTo(METRIC_CODE);
        assertThat(inserted.getPhysioMin()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(inserted.getPhysioMax()).isEqualByComparingTo(new BigDecimal("300"));
        assertThat(inserted.getDefaultLevel()).isEqualTo("WARNING");
    }

    @Test
    @DisplayName("登记拒绝：metric_code 已存在 IOT-1005（409 唯一键冲突，词表内最近冲突码位）")
    void createRejectsDuplicateMetricCode() {
        when(metricDictMapper.selectById(METRIC_CODE)).thenReturn(dictEntity());

        assertThatThrownBy(() -> service.create(new CreateMetricRequest(
                        METRIC_CODE, "心率", MetricCategory.VITAL_SIGN, MetricDataType.NUMERIC, null, null, null, null)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.METRIC_MAPPING_CONFLICT);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(metricDictMapper, never()).insert(any(IotMetricDictEntity.class));
    }

    /** 字典实体夹具（V1007 种子行同款字段） */
    private static IotMetricDictEntity dictEntity() {
        IotMetricDictEntity entity = new IotMetricDictEntity();
        entity.setMetricCode(METRIC_CODE);
        entity.setMetricName("心率");
        entity.setCategory(MetricCategory.VITAL_SIGN);
        entity.setDataType(MetricDataType.NUMERIC);
        entity.setUnit("次每分");
        entity.setPhysioMin(new BigDecimal("20"));
        entity.setPhysioMax(new BigDecimal("300"));
        entity.setDefaultLevel("WARNING");
        return entity;
    }
}
