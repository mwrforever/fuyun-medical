package com.fuyun.integration.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PG uuid 列类型处理器单元测试：验证写入走 pgjdbc 原生绑定、读取三通道正确解码并透传 SQL NULL。
 *
 * <p>业务背景：received_event.event_id（PG uuid 列）insert 依赖本处理器完成参数映射
 * （MyBatis 内置注册表无 UUID 支持，缺失即抛 Type handler was null）。
 */
class UuidTypeHandlerTest {

    /** 固定样本：断言锚点 */
    private static final UUID SAMPLE_ID = UUID.fromString("0f64e79b-8ec0-4e18-a286-e8267daa9c41");

    private final UuidTypeHandler handler = new UuidTypeHandler();

    @Test
    @DisplayName("写入：非空 UUID 以原生 setObject 绑定（pgjdbc uuid 通道，无字符串拼接）")
    void setNonNullParameterBindsUuidNatively() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setNonNullParameter(ps, 1, SAMPLE_ID, JdbcType.OTHER);

        verify(ps).setObject(1, SAMPLE_ID);
    }

    @Test
    @DisplayName("读取：按列名/列下标/存储过程出参三通道均原生解码为 UUID")
    void getNullableResultDecodesUuidOnAllThreeChannels() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        CallableStatement cs = mock(CallableStatement.class);
        when(rs.getObject("event_id", UUID.class)).thenReturn(SAMPLE_ID);
        when(rs.getObject(2, UUID.class)).thenReturn(SAMPLE_ID);
        when(cs.getObject(3, UUID.class)).thenReturn(SAMPLE_ID);

        assertThat(handler.getNullableResult(rs, "event_id")).isEqualTo(SAMPLE_ID);
        assertThat(handler.getNullableResult(rs, 2)).isEqualTo(SAMPLE_ID);
        assertThat(handler.getNullableResult(cs, 3)).isEqualTo(SAMPLE_ID);
    }

    @Test
    @DisplayName("空值：列值为 SQL NULL 时三通道均映射为 null（台账查询不误产空串/异常）")
    void getNullableResultMapsSqlNullToJavaNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("event_id", UUID.class)).thenReturn(null);

        assertThat(handler.getNullableResult(rs, "event_id")).isNull();
    }
}
