package com.example.springai.service;

import com.example.springai.config.TextCleanTransformer;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@RequiredArgsConstructor
@Service
public class DocumentProcessingService {

    private final TextCleanTransformer textCleanTransformer;

    private final ChatClient chatClient;

    private final VectorStore vectorStore;

    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(800)
            .withMinChunkSizeChars(350)
            .withMinChunkLengthToEmbed(50)
            .withKeepSeparator(true)
            .build();


    /**
     * 处理上传的文档：解析 → 清洗 → 分块
     */
    public List<Document> process(MultipartFile file) throws IOException {
        // 转成 Resource（临时文件）
        Resource resource = file.getResource();

        // ① 解析（Tika 通用解析）
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> docs = reader.read();

        // ② 清洗（自定义 Transformer）
        docs = clean(docs);

        // ③ 分块（TokenTextSplitter）
        docs = splitter.apply(docs);

        // ④ 补充元数据（来源文件名、分类、创建时间，后面溯源/过滤用）
        String filename = file.getOriginalFilename();
        // 从文件名推导分类：去掉扩展名，比如 "WOS.pdf" -> "WOS"
        String category = filename != null
                ? filename.replaceAll("\\.[^.]+$", "")
                : "unknown";
        long createTime = System.currentTimeMillis();
        docs = docs.stream()
                .map(d -> {
                    d.getMetadata().put("source", filename);
                    d.getMetadata().put("category", category);
                    d.getMetadata().put("createTime", createTime);
                    return d;
                })
                .toList();
        // ⑤ 向量化入库（自动：文本转向量 + 批量写入）
        vectorStore.add(docs);

        return docs;
    }

    private List<Document> clean(List<Document> docs) {

        return textCleanTransformer.apply(docs);

//        return docs.stream()
//                .map(this::cleanOne)
//                .filter(d -> d.getText() != null && !d.getText().isBlank())
//                .toList();
    }

    private Document cleanOne(Document doc) {
        String text = doc.getText()
                .replaceAll("\\u00A0", " ")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return new Document(text, doc.getMetadata());
    }

    public Flux<String> streamsearch1(String query) {

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(3)
                        .similarityThreshold(0.6)              // 相似度阈值，低于此值不返回（默认 0.0，即全部返回）
                        .build()
        );


        List<Message> history = results.stream().<Message>map(document -> new AssistantMessage(document.getText())).toList();

        return chatClient.prompt()
                .system("你是一个客服，根据检索到的内容，回答用户的问题")
                .messages(history)
                .stream()
                .content();
    }

    public String streamsearch(String query) {

        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(3)
                        .similarityThreshold(0.6)              // 相似度阈值，低于此值不返回（默认 0.0，即全部返回）
                        .build()
        );


        // ② 空检索直接拒答（Day 24 学的防幻觉）
        if (docs.isEmpty()) {
            return "抱歉，知识库中暂未收录该问题。";
        }

        // ③ 拼接上下文
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            context.append("[资料").append(i + 1).append("] ")
                    .append(docs.get(i).getText())
                    .append("\n\n");
        }

        // ④ 生成（RAG 生成 Prompt，呼应 Day 25 三原则）
        String prompt = """  
            你是企业知识库助手。请严格基于下方【参考资料】回答。  
  
            【参考资料】  
            %s  
  
            【回答规则】  
            1. 只使用参考资料中的内容，禁止用训练数据补充  
            2. 参考资料中没有答案时，说"抱歉，知识库中暂未收录"  
            3. 关键结论后标注来源，如 [资料1]  
            4. 回答简洁，先给结论再给依据  
  
            【用户问题】  
            %s  
            """.formatted(context, query);

        return chatClient.prompt()
                .user(prompt)
                .options(ChatOptions.builder().temperature(0.2).build())
                .call()
                .content();

    }


    /**
     * 批量任务专用：解析 → 清洗 → 分块 → 向量化入库，整体可重试。
     * 用 @Retryable：embedding API 偶发超时/限流时自动重试 3 次（1s→2s→4s 指数退避）。
     */
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 8000)
    )
    public int processAndStore(Path file, String filename) throws Exception {
        Resource resource = new FileSystemResource(file.toFile());

        // ① 解析（Tika）
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> docs = reader.read();

        // ② 清洗
        docs = textCleanTransformer.apply(docs);

        // ③ 分块
        docs = splitter.apply(docs);

        // ④ 补元数据
        String category = filename != null ? filename.replaceAll("\\.[^.]+$", "") : "unknown";
        long createTime = System.currentTimeMillis();
        docs = docs.stream().map(d -> {
            d.getMetadata().put("source", filename);
            d.getMetadata().put("category", category);
            d.getMetadata().put("createTime", createTime);
            return d;
        }).toList();

        // ⑤ 向量化入库
        vectorStore.add(docs);

        return docs.size();   // 返回分块数，供进度明细展示
    }
}