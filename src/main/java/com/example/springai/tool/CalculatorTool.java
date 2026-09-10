package com.example.springai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 工具二：四则运算（只读、无副作用）。
 * <p>
 * 演示意外故障走异常通道：除零抛 IllegalArgumentException，
 * 由 ToolExecutionExceptionProcessor 降级回传模型，模型友好提示。
 */
public class CalculatorTool {

    @Tool(description = "计算两个数字的四则运算（加减乘除）")
    public String calculate(
            @ToolParam(description = "第一个数字") double a,
            @ToolParam(description = "运算符，只能是 + - * / 之一") String op,
            @ToolParam(description = "第二个数字") double b) {
        double result = switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> {
                if (b == 0) throw new IllegalArgumentException("除数不能为 0");
                yield a / b;
            }
            default -> throw new IllegalArgumentException("不支持的运算符: " + op + "，只支持 + - * /");
        };
        return String.format("%s %s %s = %s", a, op, b, result);
    }
}
