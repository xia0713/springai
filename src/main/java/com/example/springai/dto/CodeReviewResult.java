package com.example.springai.dto;

import java.util.List;

/**
 * 代码审查结果。
 * 关键设计：Issue 里强制带 line 字段 —— 逼模型定位到具体行号，
 * 定位不到的问题大概率是幻觉（Day 28 的核心认知）。
 * @param verdict
 * @param issues
 * @param summary
 */
public record CodeReviewResult(
        String verdict,          // PASS（无问题）/ WARN（有中低风险）/ FAIL（有高危问题）
        List<Issue> issues,      // 问题列表，无问题时为空数组
        String summary           // 一句话总结
) {
    public record Issue(
            String severity,     // CRITICAL / HIGH / MEDIUM / LOW
            String category,     // SECURITY / CONCURRENCY / NULL_POINTER / LOGIC / PERFORMANCE / RESOURCE
            Integer line,        // 问题所在行号（无法定位则报 null 并说明）
            String description,  // 问题描述
            String suggestion    // 修复建议（附代码片段）
    ) {}
}

