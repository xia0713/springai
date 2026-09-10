package com.example.springai.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * SQL 查询工具（Day52）—— NL2SQL 的执行层。
 * <p>
 * 流程：模型生成 SQL → 本工具校验（SqlSafetyValidator）→ 执行 → 格式化 Markdown 表格返回。
 * 每条模型生成的 SQL 记审计日志（Day82 审计要求的雏形）。
 */
@Slf4j
@RequiredArgsConstructor
public class SqlQueryTool {

    private final JdbcTemplate jdbc;

    @Tool(description = "在业务数据库上执行只读 SELECT 查询，返回 Markdown 表格结果。仅用于查询订单业务数据")
    public String queryDatabase(
            @ToolParam(description = "要执行的只读 SELECT SQL 语句，必须带 LIMIT，最多返回 100 行") String sql) {

        log.info(">>> 模型生成的 SQL: {}", sql);   // 审计：记录每条生成的 SQL

        // ① 安全校验（硬约束，不通过抛异常 → ToolExecutionExceptionProcessor 降级回传模型）
        SqlSafetyValidator.validate(sql);

        // ② 执行
        List<Map<String, Object>> rows = jdbc.queryForList(sql);
        if (rows.isEmpty()) {
            return "查询结果为空（0 行）";
        }

        // ③ 格式化成 Markdown 表格（模型对表格的阅读理解比 JSON 更好）
        StringJoiner sj = new StringJoiner("\n");
        List<String> columns = rows.get(0).keySet().stream().toList();
        sj.add("| " + String.join(" | ", columns) + " |");
        sj.add("|" + columns.stream().map(c -> "---").collect(Collectors.joining("|")) + "|");
        for (Map<String, Object> row : rows) {
            sj.add("| " + columns.stream()
                    .map(c -> String.valueOf(row.get(c)))
                    .collect(Collectors.joining(" | ")) + " |");
        }
        return "查询结果（" + rows.size() + " 行）：\n" + sj;
    }
}
