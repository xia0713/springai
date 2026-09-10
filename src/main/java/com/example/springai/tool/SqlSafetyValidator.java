package com.example.springai.tool;

import java.util.regex.Pattern;

/**
 * SQL 安全校验器（Day52）—— NL2SQL 的硬约束层。
 * <p>
 * 三重校验，缺一不可：
 * ① 白名单：必须以 SELECT 开头
 * ② 黑名单：禁止写操作/DDL 关键字（\b 词边界，防 UPDATED_AT 等列名误伤）
 * ③ 禁分号/注释：防多语句注入（"SELECT 1; DROP TABLE x"）
 * <p>
 * 定位：prompt 约束是软的，这里是硬的；生产的最后防线是只读数据库账号（三层防线）。
 */
public final class SqlSafetyValidator {

    private static final Pattern FORBIDDEN_KEYWORDS = Pattern.compile(
            "(?i)\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|MERGE|EXECUTE|CALL|VACUUM|COPY)\\b");
    private static final Pattern FORBIDDEN_CHARS = Pattern.compile(";|--|/\\*|\\*/");

    private SqlSafetyValidator() {}

    /** 校验不通过直接抛 IllegalArgumentException，由工具降级通道回传模型 */
    public static void validate(String sql) {
        String s = sql.strip();
        if (!s.toUpperCase().startsWith("SELECT")) {
            throw new IllegalArgumentException("只允许 SELECT 查询，拒绝执行该语句");
        }
        if (FORBIDDEN_KEYWORDS.matcher(s).find()) {
            throw new IllegalArgumentException("SQL 包含禁止的关键字（增删改/DDL 均不允许）");
        }
        if (FORBIDDEN_CHARS.matcher(s).find()) {
            throw new IllegalArgumentException("SQL 包含非法字符（分号/注释）");
        }
    }
}
