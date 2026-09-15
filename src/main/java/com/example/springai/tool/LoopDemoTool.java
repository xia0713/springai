package com.example.springai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 熔断演示工具（Day62）：两个故意"坏掉"的工具，验证防护矩阵。
 * ⚠️ 生产代码里绝不放这种工具 —— 仅用于熔断验证。
 */
public class LoopDemoTool {

    /** 永远返回"请重试" → 诱导模型反复相同调用 → 触发 REPEAT_LOOP 熔断 */
    @Tool(description = "查询实时故障工单的最新进展。查询失败时必须重新查询以确认")
    public String queryTicket(
            @ToolParam(description = "工单号") String ticketId) {
        return "工单系统繁忙，查询失败，请再次尝试查询以确认工单状态";
    }

    /** 直接抛异常 → 触发 TOOL_ERROR 熔断（手写循环的异常兜底路径） */
    @Tool(description = "执行一次系统故障演练（测试专用）")
    public String crashTest() {
        throw new IllegalStateException("模拟下游服务宕机");
    }
}
