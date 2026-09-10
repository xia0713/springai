package com.example.springai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工具三：发送通知（写操作！带白名单安全控制）。
 * <p>
 * 与只读工具的区别：写操作必须设防 ——
 * ① description 里写"仅在用户明确要求时使用"，抑制模型误触发；
 * ② 收件人白名单，名单外直接拒绝；
 * ③ 生产环境还应加：审计日志、AI 生成草稿 → 人工确认 → 执行（Day62 人工介入机制）。
 */
public class NotificationTool {

    /** 收件人白名单：工具层安全控制的最小实现 */
    private static final Set<String> ALLOWED_RECEIVERS = Set.of("张三", "李四", "王五");

    /** 已发送的通知（演示用，生产要落库+审计） */
    public static final List<String> SENT = new CopyOnWriteArrayList<>();

    @Tool(description = "给指定收件人发送一条通知消息。仅在用户明确要求发送通知时使用，不要主动发送")
    public String sendNotification(
            @ToolParam(description = "收件人姓名，必须是公司通讯录里的人") String receiver,
            @ToolParam(description = "通知内容") String content) {
        // 安全控制：白名单外直接拒绝
        if (!ALLOWED_RECEIVERS.contains(receiver)) {
            return "拒绝发送：" + receiver + " 不在允许的收件人名单内";
        }
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("通知内容为空");   // 意外故障 → 走异常降级
        }
        SENT.add(receiver + ": " + content);
        return "已成功发送通知给 " + receiver;
    }
}
