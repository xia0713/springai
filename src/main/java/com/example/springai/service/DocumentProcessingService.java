package com.example.springai.service;

import com.example.springai.config.TextCleanTransformer;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
public class DocumentProcessingService {

    private final TextCleanTransformer textCleanTransformer;

    private final VectorStore vectorStore;

    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(800)
            .withMinChunkSizeChars(350)
            .withMinChunkLengthToEmbed(50)
            .withKeepSeparator(true)
            .build();

    public DocumentProcessingService(TextCleanTransformer textCleanTransformer, VectorStore vectorStore) {
        this.textCleanTransformer = textCleanTransformer;
        this.vectorStore = vectorStore;
    }

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
}