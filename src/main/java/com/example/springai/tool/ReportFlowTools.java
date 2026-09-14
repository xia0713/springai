package com.example.springai.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报表流程工具集（Day54）：查数据 → 存草稿 → 发邮件。
 * <p>
 * 设计要点：
 * - 中间结果两种传法：统计数据小 → 上下文传递；报告草稿大 → 存 store 只给 draftId（引用传递）
 * - 不可逆操作（发邮件）放流程最后
 * - SQL 写死参数化（对照 Day52 模型写 SQL：固定报表不需要模型生成 SQL，无注入面）
 * - 每个工具进出打 [step] 日志（审计雏形）
 */
@Slf4j
@RequiredArgsConstructor
public class ReportFlowTools {

    private final JdbcTemplate jdbc;

    /** 草稿存储（中间结果显式存储；生产换 DB/Redis） */
    private static final Map<String, String> DRAFTS = new ConcurrentHashMap<>();

    /** 邮件白名单（Day51 的安全原则延续） */
    private static final Set<String> ALLOWED_RECEIVERS = Set.of("张三", "李四", "王五");

    /** 已发送记录（演示用） */
    public static final CopyOnWriteArrayList<String> SENT_EMAILS = new CopyOnWriteArrayList<>();

    // ---------- 工具 1：查数据（读，幂等） ----------

    @Tool(description = "查询指定月份的销售统计数据（订单数、总销售额、最畅销商品）。月份格式如 2026-08 或 8月")
    public String querySalesStats(
            @ToolParam(description = "月份，格式如 2026-08 或 8月") String month) {

        // 参数归一化：模型可能传 "8月" 或 "2026-08"，适配层统一成 YYYY-MM
        String ym = normalizeMonth(month);
        if (ym == null) {
            return "月份格式不支持：" + month + "，请用 2026-08 或 8月 这样的格式";
        }

        // SQL 写死 + 参数化（无注入面），排除已取消订单
        Map<String, Object> stats = jdbc.queryForMap("""
                SELECT COUNT(*) AS order_count, COALESCE(SUM(amount), 0) AS total_sales
                FROM demo_orders
                WHERE to_char(order_date, 'YYYY-MM') = ? AND status <> '已取消'
                """, ym);
        List<Map<String, Object>> top = jdbc.queryForList("""
                SELECT product, SUM(amount) AS sales
                FROM demo_orders
                WHERE to_char(order_date, 'YYYY-MM') = ? AND status <> '已取消'
                GROUP BY product ORDER BY sales DESC LIMIT 1
                """, ym);

        String topProduct = top.isEmpty()
                ? "无"
                : top.get(0).get("product") + "（" + top.get(0).get("sales") + " 元）";
        log.info("[step] querySalesStats({}) → 订单 {} 笔，销售额 {}", ym,
                stats.get("order_count"), stats.get("total_sales"));

        return ym + " 月销售统计：订单 " + stats.get("order_count") + " 笔，总销售额 "
                + stats.get("total_sales") + " 元，最畅销商品：" + topProduct;
    }

    // ---------- 工具 2：存草稿（写，但幂等/可覆盖） ----------

    @Tool(description = "保存报表草稿，返回草稿 ID。发送邮件前必须先保存草稿")
    public String saveReportDraft(
            @ToolParam(description = "报表标题") String title,
            @ToolParam(description = "报表正文（基于销售统计数据撰写的分析）") String content) {

        if (content == null || content.isBlank()) {
            throw new IllegalStateException("报表内容为空");
        }
        String draftId = "RP-" + UUID.randomUUID().toString().substring(0, 8);
        DRAFTS.put(draftId, "标题: " + title + "\n\n" + content);
        log.info("[step] saveReportDraft({}) → {}", title, draftId);
        return "草稿已保存，ID: " + draftId;
    }

    // ---------- 工具 3：发邮件（写，不可逆 → 放流程最后） ----------

    @Tool(description = "把已保存的报表草稿通过邮件发送给指定收件人。收件人必须是白名单内的人。发送失败时不要重复重试，先告知用户")
    public String sendReportEmail(
            @ToolParam(description = "草稿 ID（来自 saveReportDraft 的返回）") String draftId,
            @ToolParam(description = "收件人姓名") String receiver) {

        // 安全控制：白名单
        if (!ALLOWED_RECEIVERS.contains(receiver)) {
            return "拒绝发送：" + receiver + " 不在允许的收件人名单内";
        }
        // 依赖前一步结果：草稿必须存在（这就是"中间结果引用传递"）
        String draft = DRAFTS.get(draftId);
        if (draft == null) {
            return "草稿 " + draftId + " 不存在，请先调用 saveReportDraft 保存草稿";
        }

        SENT_EMAILS.add(receiver + " 收到: " + draftId);
        log.info("[step] sendReportEmail({} → {}) 成功", draftId, receiver);
        return "已将报表（" + draftId + "）发送给 " + receiver;
    }

    /** 月份归一化："8月"/"8 月" → 2026-08；"2026-8"/"2026-08" → 2026-08 */
    private String normalizeMonth(String month) {
        if (month == null) {
            return null;
        }
        String m = month.strip();
        Matcher shortM = Pattern.compile("^(\\d{1,2})月?$").matcher(m);
        if (shortM.matches()) {
            return "2026-" + String.format("%02d", Integer.parseInt(shortM.group(1)));
        }
        Matcher fullM = Pattern.compile("^(\\d{4})-(\\d{1,2})$").matcher(m);
        if (fullM.matches()) {
            return fullM.group(1) + "-" + String.format("%02d", Integer.parseInt(fullM.group(2)));
        }
        return null;
    }

    /** 供测试/调试查看草稿 */
    public static Map<String, String> drafts() {
        return DRAFTS;
    }
}
