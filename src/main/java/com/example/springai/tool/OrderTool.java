package com.example.springai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

/**
 * 工具一：订单查询（只读）。
 * <p>
 * 演示参数校验两道防线：格式校验 + 存在性校验。
 * 可预期错误返回提示文本（模型能理解并转告用户），不抛异常。
 */
public class OrderTool {

    private static final Map<String, String> ORDER_DB = Map.of(
            "ORD-142", "订单 ORD-142（蓝牙耳机）：已发货，8/18 发出，预计 8/20 送达",
            "ORD-143", "订单 ORD-143（智能手表）：运输中，预计 8/22 到达",
            "ORD-145", "订单 ORD-145（双肩背包）：已签收"
    );

    @Tool(description = "根据订单号查询订单的物流状态和预计送达时间")
    public String getOrderStatus(
            @ToolParam(description = "订单号，格式如 ORD-142") String orderId) {
        // 参数校验第一道：格式
        if (orderId == null || !orderId.matches("ORD-\\d+")) {
            return "订单号格式不正确，应为 ORD-数字，例如 ORD-142";
        }
        // 参数校验第二道：存在性
        String info = ORDER_DB.get(orderId);
        return info != null ? info : "未找到订单 " + orderId + "，请确认订单号是否正确";
    }
}
