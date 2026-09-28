package com.fuyun.iot.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JSONB 类型处理器单测（P2 PR-2 Task 8）：写入走 Types.OTHER 未指定类型通道（pgjdbc 默认
 * VARCHAR 绑定 JSONB 列将报表达式类型不符），读取走 rs.getString 文本原值（原文透传），SQL NULL
 * 双向映射 null（BaseTypeHandler 外层承接）。
 */
class JsonbTypeHandlerTest {

    @Test
    @DisplayName("非空 JSON 文本绑定：setObject(Types.OTHER) 未指定类型通道，服务端按 JSONB 解析")
    void setNonNullParameterBindsViaTypesOther() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);

        new JsonbTypeHandler().setNonNullParameter(statement, 1, "{\"mode\":3}", null);

        verify(statement).setObject(1, "{\"mode\":3}", Types.OTHER);
    }

    @Test
    @DisplayName("按列名/下标读取：JSONB 文本原值返回，SQL NULL 映射 null")
    void getNullableResultReadsTextAndMapsNull() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("command_params")).thenReturn("{\"mode\":3}");
        when(resultSet.getString(2)).thenReturn(null);

        JsonbTypeHandler handler = new JsonbTypeHandler();

        assertThat(handler.getNullableResult(resultSet, "command_params")).isEqualTo("{\"mode\":3}");
        assertThat(handler.getNullableResult(resultSet, 2)).isNull();
    }
}
