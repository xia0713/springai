package com.example.springai.util;

public class AiUtil {
    // 辅助方法：只取向量前10个值展示
    public static String first10(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(10, vec.length); i++) {
            sb.append(String.format("%.4f", vec[i])).append(", ");
        }
        sb.append("...]");
        return sb.toString();
    }

    // 余弦相似度计算
    public static double cosineSimilarity(float[] vectorA, float[] vectorB) {
        if (vectorA.length != vectorB.length) {
            throw new IllegalArgumentException("两个向量维度不一致");
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += Math.pow(vectorA[i], 2);
            normB += Math.pow(vectorB[i], 2);
        }

        if (normA == 0 || normB == 0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
