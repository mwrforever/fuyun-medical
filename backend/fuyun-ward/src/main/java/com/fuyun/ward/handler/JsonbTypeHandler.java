package com.fuyun.ward.handler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * PostgreSQL JSONB 列的 String 类型处理器（ward_call_routing_rule.target_chain 与
 * cold_chain_record.content 数据层配套，iot 侧 JsonbTypeHandler 同款形态——模块内自持副本，
 * B.2-2 禁跨模块引 internal 包）。
 *
 * <p>存在原因：pgjdbc 默认以 VARCHAR 类型绑定 String 参数，直接写入 JSONB 列将报
 * "column is of type jsonb but expression is of type character varying"——本处理器以
 * setObject(Types.OTHER) 走未指定类型绑定通道，由服务端按目标列类型（JSONB）解析入参；
 * 读取侧 rs.getString 对 JSONB 返回文本原值（原文透传红线，禁服务端重排版差异）。
 *
 * <p>注册方式：实体字段 {@code @TableField(typeHandler = JsonbTypeHandler.class)} +
 * {@code @TableName(autoResultMap = true)} 局部注册（fuyun-ward 不在 app 的
 * type-handlers-package 全局清单内，宪法 B.1 装配边界）。
 *
 * <p>线程安全：无状态单例（MyBatis TypeHandler 注册后全局共享）。
 */
@MappedTypes(String.class)
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    /**
     * 非空 JSON 文本绑定：Types.OTHER 未指定类型通道，服务端按 JSONB 目标列解析。
     *
     * @param ps        预编译语句，非空；来源：MyBatis 参数写入
     * @param i         参数下标（1 起），非空
     * @param parameter 待绑定 JSON 文本，非空（null 由 BaseTypeHandler 外层承接）
     * @param jdbcType  JDBC 类型，可为 null（列类型由 DDL 决定，无需显式指定）
     * @throws SQLException JDBC 绑定失败时抛出；交由 MyBatis/事务层按数据异常处理
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    /**
     * 按列名读取：JSONB 文本原值（rs.getString 服务端文本形态），SQL NULL 映射为 null。
     *
     * @param rs         结果集，非空
     * @param columnName 列名，非空
     * @return JSON 文本；列值为 SQL NULL 时返回 null
     * @throws SQLException 读取失败时抛出
     */
    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    /**
     * 按列下标读取：JSONB 文本原值，SQL NULL 映射为 null。
     *
     * @param rs          结果集，非空
     * @param columnIndex 列下标（1 起），非空
     * @return JSON 文本；列值为 SQL NULL 时返回 null
     * @throws SQLException 读取失败时抛出
     */
    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    /**
     * 按存储过程出参读取（本模块无存储过程调用面，实现仅保接口完备）。
     *
     * @param cs          存储过程语句，非空
     * @param columnIndex 列下标（1 起），非空
     * @return JSON 文本；出参为 SQL NULL 时返回 null
     * @throws SQLException 读取失败时抛出
     */
    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
