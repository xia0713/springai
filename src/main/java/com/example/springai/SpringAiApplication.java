package com.example.springai;

import com.example.springai.service.KnowledgeService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
public class SpringAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiApplication.class, args);
    }
    /**
     * 启动时写入一批测试数据
     */
    @Bean
    CommandLineRunner initData(KnowledgeService knowledgeService) {
        return args -> {
            // 写入一些 Java/AI 相关的知识文本
            knowledgeService.addKnowledge(List.of(
                    "Spring AI 是 Spring 生态的 AI 应用开发框架，提供统一的 ChatClient 和 VectorStore API",
                    "Pgvector 是 PostgreSQL 的向量扩展，支持高效的相似度搜索",
                    "RAG 通过检索相关知识再生成答案，有效减少大模型幻觉",
                    "Embedding 模型将文本转换为高维向量，语义相近的文本向量距离也近",
                    "Function Calling 让大模型能够调用外部工具和 API，扩展了 AI 的能力边界",
                    "HNSW 是一种基于图的近似最近邻搜索算法，查询速度快但内存占用较高"
            ), "tech");

            System.out.println("测试数据初始化完成！");
        };
    }
}
