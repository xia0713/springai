package com.example.springai.controller;

import com.example.springai.util.AiUtil;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/embedding")
public class EmbeddingController {

    private final EmbeddingModel embeddingModel;

    public EmbeddingController(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 获取单段文本的向量
     */
    @GetMapping("/text")
    public Map<String, Object> getEmbedding(@RequestParam String text) {
        EmbeddingResponse response = embeddingModel.embedForResponse(List.of(text));

        float[] vector = response.getResults().get(0).getOutput();

        return Map.of(
                "text", text,
                "vectorDimension", vector.length,
                "first10Values", AiUtil.first10(vector)
        );
    }

    /**
     * 计算两段文本的余弦相似度
     */
    @GetMapping("/similarity")
    public Map<String, Object> calculateSimilarity(
            @RequestParam String text1,
            @RequestParam String text2) {

        EmbeddingResponse resp1 = embeddingModel.embedForResponse(List.of(text1));
        EmbeddingResponse resp2 = embeddingModel.embedForResponse(List.of(text2));

        float[] vec1 = resp1.getResults().get(0).getOutput();
        float[] vec2 = resp2.getResults().get(0).getOutput();

        double similarity = AiUtil.cosineSimilarity(vec1, vec2);

        return Map.of(
                "text1", text1,
                "text2", text2,
                "similarity", String.format("%.4f", similarity),
                "dimension", vec1.length
        );
    }
}
