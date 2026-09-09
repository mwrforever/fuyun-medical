package com.fuyun.integration.handler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * PostgreSQL uuid 列的 java.util.UUID 类型处理器（integration 治理域台账数据层配套）。
 *
 * <p>存在原因：MyBatis/MyBatis-Plus 内置注册表无 UUID 类型处理器，received_event.event_id
 * （PG uuid 列，V3 迁移）在 insert 参数映射构建期即抛 "Type handler was null on parameter
 * mapping for property 'eventId'"（B2.3 端到端 IT 首跑实证，B2.2 申报①"UnknownTypeHandler→
 * setObject 原生写入"推定被复验证伪，PR 描述申报修正）。
 *
 * <p>注册方式：application.yml 的 mybatis-plus.type-handlers-package 全局注册——insert 参数
 * 与 wrapper 查询条件两侧同时生效，后续模块新增 uuid 列零成本复用。
 *
 * <p>线程安全：无状态单例（MyBatis TypeHandler 注册后全局共享）。
 */
@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    /**
     * 非空 UUID 绑定：走 pgjdbc 原生 uuid 绑定通道（强类型，无字符串拼接与注入面）。
     *
     * @param ps        预编译语句，非空；来源：MyBatis 参数写入
     * @param i         参数下标（1 起），非空
     * @param parameter 待绑定 UUID 值，非空（null 由 BaseTypeHandler 外层承接）
     * @param jdbcType  JDBC 类型，可为 null（列类型由 DDL 决定，无需显式指定）
     * @throws SQLException JDBC 绑定失败时抛出；交由 MyBatis/事务层按数据异常处理
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter);
    }

    /**
     * 按列名读取：pgjdbc 原生 uuid 解码，SQL NULL 映射为 null。
     *
     * @param rs         结果集，非空
     * @param columnName 列名，非空
     * @return UUID 值；列值为 SQL NULL 时返回 null
     * @throws SQLException 读取失败时抛出
     */
    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getObject(columnName, UUID.class);
    }

    /**
     * 按列下标读取：pgjdbc 原生 uuid 解码，SQL NULL 映射为 null。
     *
     * @param rs          结果集，非空
     * @param columnIndex 列下标（1 起），非空
     * @return UUID 值；列值为 SQL NULL 时返回 null
     * @throws SQLException 读取失败时抛出
     */
    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getObject(columnIndex, UUID.class);
    }

    /**
     * 存储过程出参读取：pgjdbc 原生 uuid 解码，SQL NULL 映射为 null。
     *
     * @param cs          可调用语句，非空
     * @param columnIndex 出参下标（1 起），非空
     * @return UUID 值；出参为 SQL NULL 时返回 null
     * @throws SQLException 读取失败时抛出
     */
    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getObject(columnIndex, UUID.class);
    }
}
