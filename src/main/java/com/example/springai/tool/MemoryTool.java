package com.example.springai.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 长期记忆工具（Day59）—— 个人知识库式长期记忆。
 * <p>
 * 本质：把用户关键事实当"文档"存向量库（metadata: type=agent_memory + userId），
 * 提问时按相似度召回 —— RAG 思想用在记忆上。
 * <p>
 * 安全三原则：
 * ① 隔离：检索必须 type+userId 双过滤，绝不跨用户召回；
 * ② 可遗忘：用户要求删除必须真删（vectorStore.delete 按 userId）；
 * ③ 防污染：记忆是用户喂的，高危决策不能只信记忆，写入要审计。
 * userId 由构造器注入（来自登录态，绝不让模型传 —— Day45 信任边界原则）。
 */
@Slf4j
@RequiredArgsConstructor
public class MemoryTool {

    private final VectorStore vectorStore;
    private final String userId;   // 演示模式由 Controller 传入；生产从登录态取

    @Tool(description = "把用户告诉你的重要个人信息（偏好、过敏、称呼、习惯等）保存到长期记忆。用户说'记住/帮我记一下'时使用")
    public String remember(
            @ToolParam(description = "要记住的事实，一句话完整表述，如'用户对花生严重过敏'") String fact) {

        Document doc = new Document(fact, Map.of(
                "type", "agent_memory",
                "userId", userId,
                "createTime", System.currentTimeMillis()));
        vectorStore.add(List.of(doc));
        log.info("[memory] remember({}) for user {}", fact, userId);
        return "已记住：" + fact;
    }

    @Tool(description = "从长期记忆中检索用户的个人信息。当问题涉及用户个人偏好/历史信息，而当前对话中没有时使用")
    public String recall(
            @ToolParam(description = "要回忆的内容主题，如'饮食过敏'、'称呼'") String query) {

        // 安全原则之隔离：必须同时过滤 type + userId，绝不跨用户召回
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression filter = b.and(
                b.eq("type", "agent_memory"),
                b.eq("userId", userId)).build();

        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(3)
                        .similarityThreshold(0.5)
                        .filterExpression(filter).build());

        log.info("[memory] recall(query={}) → {} 条", query, docs.size());
        if (docs.isEmpty()) {
            return "长期记忆中没有相关信息";
        }
        return docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("；", "记忆中相关的信息：", ""));
    }
}
