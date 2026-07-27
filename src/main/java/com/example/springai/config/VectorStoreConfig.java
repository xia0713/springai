package com.example.springai.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.*;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;


//如果你不想用自动配置（比如需要自定义数据源、多数据源场景），可以手动创建 PgVectorStore
//创建多张表？默认自动配置只建 vector_store 一张表，要更多表就手动建 PgVectorStore Bean
//使用时 @Qualifier("knowledgeStore")
//        @Autowired
//        private VectorStore knowledgeStore;  // 搜知识库
//@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024)                    // 向量维度（不填则从模型自动获取）
                .distanceType(COSINE_DISTANCE)       // 距离度量：余弦距离
                .indexType(HNSW)                      // 索引类型：HNSW
                .initializeSchema(true)               // 启动时自动建表
                .schemaName("public")                // schema 名
                .vectorTableName("vector_store")      // 表名
                .maxDocumentBatchSize(10000)          // 批量写入上限
                .build();
    }
}
