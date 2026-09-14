package com.example.springai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

/**
 * 运维排障工具集（Day60）—— 行业知识注入方式①：结构化工具。
 * <p>
 * 设计原则：工具只返回【事实】（CPU 92%），结论让模型推（系统过载）——
 * 出错可归因：数据错了修工具，推理错了调 prompt。
 * 数据是 mock 的，生产对接监控系统（Prometheus/SkyWalking）。
 */
@Slf4j
public class OpsTools {

    /** 行业知识：错误码手册（稳定知识 → 做成工具；生产应来自 RAG 知识库或配置中心） */
    private static final Map<String, String> ERROR_BOOK = Map.of(
            "503", "服务暂不可用。常见原因：下游服务过载、正在发布维护、连接池耗尽。建议排查顺序：①看本服务与目标服务的CPU/内存/线程池 ②查依赖服务健康状态 ③查网关/负载均衡日志",
            "502", "网关错误，上游无有效响应。常见原因：下游服务崩溃或重启中。建议：①查下游进程存活 ②查下游启动日志 ③查健康检查配置",
            "504", "网关超时。常见原因：下游处理时间超过网关超时阈值。建议：①查下游接口耗时（慢SQL/外部调用）②核对网关超时配置",
            "429", "请求过于频繁，触发限流。建议：①确认限流阈值是否合理 ②检查是否有突发流量或重试风暴",
            "401", "未授权。建议：①检查token是否过期 ②检查鉴权配置与密钥"
    );

    @Tool(description = "查询 HTTP/业务错误码的运维手册（含义+排查步骤）。拿到报错日志中的错误码后先用这个")
    public String lookupErrorCode(
            @ToolParam(description = "错误码，如 503、504、429") String errorCode) {
        log.info("[ops] lookupErrorCode({})", errorCode);
        String info = ERROR_BOOK.get(errorCode.trim());
        return info != null ? info : "错误码手册中没有 " + errorCode + "，请根据日志上下文自行分析";
    }

    @Tool(description = "查询指定服务的系统指标（CPU/内存/线程池/GC）。排查性能与过载问题时使用")
    public String getSystemMetrics(
            @ToolParam(description = "服务名，如 order-service、pay-service") String serviceName) {
        log.info("[ops] getSystemMetrics({})", serviceName);
        // mock：生产对接 Prometheus
        return switch (serviceName.toLowerCase()) {
            case "order-service" -> "order-service 指标：CPU 92%（高位），内存 78%，线程池 200/200（已满），FULL GC 近1小时 12 次（频繁）";
            case "pay-service" -> "pay-service 指标：CPU 97%（过载），内存 85%，线程池 500/500（已满），平均响应时间 4800ms（严重变慢）";
            default -> serviceName + " 指标：CPU 35%，内存 52%，线程池 40/200，平均响应时间 120ms（正常）";
        };
    }

    @Tool(description = "检查指定服务的下游依赖健康状态（数据库/缓存/下游服务）。怀疑依赖故障时使用")
    public String checkDependency(
            @ToolParam(description = "服务名") String serviceName) {
        log.info("[ops] checkDependency({})", serviceName);
        return switch (serviceName.toLowerCase()) {
            case "order-service" -> "order-service 依赖健康：数据库（正常，连接池 20/50），缓存 Redis（正常），下游 pay-service（异常：最近5分钟超时率 45%）";
            case "pay-service" -> "pay-service 依赖健康：数据库（正常），第三方支付通道（异常：响应超时率 60%，通道疑似限流本方）";
            default -> serviceName + " 依赖健康：全部正常";
        };
    }
}
