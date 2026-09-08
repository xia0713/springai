package com.example.springai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 天气查询工具（Day50 第一个工具）。
 * <p>
 * 演示 Function Calling 最小闭环：模型判断要查天气 → 请求调用 getWeather →
 * Spring AI 反射执行 → 结果回传 → 模型生成最终回答。
 * <p>
 * 注意：模型不执行函数，只输出调用指令；真正调 API 的是本方法。
 */
public class WeatherTool {

    @Tool(description = "查询指定城市的实时天气，返回天气状况和气温")
    public String getWeather(
            @ToolParam(description = "城市名称，例如：北京、上海、深圳") String city) {

        // TODO 真实场景：调用和风天气 / OpenWeatherMap 等天气 API
        // 这里先用静态数据演示 Function Calling 的完整流程
        return switch (city) {
            case "北京" -> "北京今天晴，气温 25°C";
            case "上海" -> "上海今天多云，气温 28°C";
            case "深圳" -> "深圳今天阵雨，气温 30°C";
            default -> city + "今天晴，气温 24°C";
        };
    }
}
