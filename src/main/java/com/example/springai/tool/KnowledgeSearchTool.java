package com.example.springai.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库检索工具（Day56）—— 把第 7 周的 RAG 能力包装成工具。
 * <p>
 * ⚠️ 生产必须永远加 owner 过滤（Day45 结论）；当前演示模式允许不过滤，
 * 因为 RagTestCorpus 测试语料没有 owner 元数据，硬过滤会搜不到任何内容。
 * 上线前关闭演示开关（owner 固定传当前登录用户）。
 */
@Slf4j
@RequiredArgsConstructor
public class KnowledgeSearchTool {

    private final VectorStore vectorStore;
    private final String owner;   // null = 演示模式不过滤；非空 = 只搜该用户的文档

    @Tool(description = "搜索企业知识库文档（制度、政策、产品说明、员工手册等）。回答'是什么/怎么规定'类问题用这个")
    public String searchKnowledge(
            @ToolParam(description = "检索关键词或问题") String query) {

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(3)
                .similarityThreshold(0.6);
        if (owner != null && !owner.isBlank()) {
            builder.filterExpression(new FilterExpressionBuilder().eq("owner", owner).build());
        }
        List<Document> docs = vectorStore.similaritySearch(builder.build());

        log.info("[step] searchKnowledge(query={}) → {} 条", query, docs.size());

        if (docs.isEmpty()) {
            return "知识库中未找到相关内容";
        }
        return docs.stream()
                .map(d -> "[资料·来源:%s] %s".formatted(
                        d.getMetadata().getOrDefault("source", "未知"),
                        d.getText()))
                .collect(Collectors.joining("\n\n"));
    }
}
