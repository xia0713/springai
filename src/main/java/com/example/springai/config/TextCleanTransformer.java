package com.example.springai.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TextCleanTransformer implements DocumentTransformer {

    @Override
    public List<Document> apply(List<Document> documents) {
        return documents.stream()
                .map(this::clean)
                .filter(doc -> doc.getText() != null && !doc.getText().isBlank())
                .toList();
    }

    private Document clean(Document doc) {
        String text = doc.getText();
        text = text.replaceAll("\\u00A0", " ")          // 不间断空格 → 普通空格
                .replaceAll("\\u200B", "")           // 零宽空格 → 删除
                .replaceAll("[ \\t]+", " ")          // 连续空格/制表符 → 单个空格
                .replaceAll("\\n{3,}", "\n\n")       // 3 个以上换行 → 2 个
                .trim();
        return new Document(text, doc.getMetadata());
    }
}

