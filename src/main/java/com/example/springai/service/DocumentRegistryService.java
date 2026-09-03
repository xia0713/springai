package com.example.springai.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DocumentRegistryService {

    private final JdbcTemplate jdbc;

    /** 启动时自动建表（幂等） */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS document_registry (
                    doc_id      VARCHAR(255) PRIMARY KEY,
                    filename    VARCHAR(255) NOT NULL,
                    category    VARCHAR(100),
                    file_hash   VARCHAR(128),
                    chunk_count INT DEFAULT 0,
                    create_time BIGINT,
                    update_time BIGINT
                )
                """);
    }

    /** 注册或更新一条文档记录（docId 相同则覆盖） */
    public void upsert(String docId, String filename, String category,
                       String fileHash, int chunkCount) {
        long now = Instant.now().toEpochMilli();
        // 先查是否已有 create_time，有就保留，没有就用当前时间
        // ⚠️ queryForObject 查不到记录会抛 EmptyResultDataAccessException，必须捕获
        Long createTime;
        try {
            createTime = jdbc.queryForObject(
                    "SELECT create_time FROM document_registry WHERE doc_id = ?",
                    Long.class, docId);
        } catch (EmptyResultDataAccessException e) {
            createTime = null;   // 首次插入，无旧记录
        }
        long ct = (createTime != null) ? createTime : now;

        jdbc.update("""
                INSERT INTO document_registry
                    (doc_id, filename, category, file_hash, chunk_count, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (doc_id) DO UPDATE SET
                    filename = EXCLUDED.filename,
                    category = EXCLUDED.category,
                    file_hash = EXCLUDED.file_hash,
                    chunk_count = EXCLUDED.chunk_count,
                    update_time = EXCLUDED.update_time
                """, docId, filename, category, fileHash, chunkCount, ct, now);
    }

    /** 按 docId 查 fileHash（更新时判断内容是否真的变了） */
    public String getFileHash(String docId) {
        try {
            return jdbc.queryForObject(
                    "SELECT file_hash FROM document_registry WHERE doc_id = ?",
                    String.class, docId);
        } catch (EmptyResultDataAccessException e) {
            return null;   // 没这条记录
        }
    }

    /** 删除注册记录（注意：只删表，向量由调用方删） */
    public void delete(String docId) {
        jdbc.update("DELETE FROM document_registry WHERE doc_id = ?", docId);
    }

    /** 列出所有文档（管理界面用） */
    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList("""
                SELECT doc_id, filename, category, chunk_count, create_time, update_time
                FROM document_registry ORDER BY update_time DESC
                """);
    }
}