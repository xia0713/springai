package com.example.springai.service;

import com.example.springai.exception.AiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentManagementService {

    private final VectorStore vectorStore;
    private final DocumentRegistryService registryService;
    private final DocumentProcessingService processingService;

    /**
     * 删除文档：先从向量库删掉该 docId 的所有 chunk，再删注册表记录。
     * 顺序建议：先删向量再删注册表 —— 如果删向量成功但删表失败，重试还是幂等的；
     * 反过来先删表会留下“无主向量”，检索会撞到已删除文档。
     */
    public void deleteDocument(String docId) {
        // ① 删向量（按 metadata.docId 过滤，把所有 chunk 一次删干净）
        vectorStore.delete(new FilterExpressionBuilder().eq("docId", docId).build());
        // ② 删注册表记录
        registryService.delete(docId);
        log.info("文档已删除: {}", docId);
    }

    /**
     * 更新文档（改版）：
     * 1. 算新文件 fileHash，和旧注册表里的比；一样就跳过（省 token）
     * 2. 不一样 → 先删旧向量，再重灌 + 更新注册表
     */
    public String updateDocument(String filename, MultipartFile file) throws Exception {
        String category = filename.replaceAll("\\.[^.]+$", "");
        String docId = category + ":" + filename;

        // 落临时文件算 hash（不能在请求线程外读 MultipartFile，参考 Day43 的坑）
        Path tmp = Files.createTempFile("update-", filename);
        file.transferTo(tmp);
        String newHash = md5(tmp.toFile());

        String oldHash = registryService.getFileHash(docId);
        if (newHash.equals(oldHash)) {
            // 内容没变：直接跳过，绝不重灌。这就是增量刷新最省钱的点。
            log.info("内容未变化，跳过更新: {}", docId);
            return "SKIPPED";   // 内容无变化
        }

        // 内容变了 → 先删旧的
        vectorStore.delete(new FilterExpressionBuilder().eq("docId", docId).build());

        // 再灌新的（processAndStore 内部会重新解析+embedding+写入）
        int chunks = processingService.processAndStore(tmp, filename);

        // 更新注册表
        registryService.upsert(docId, filename, category, newHash, chunks);

        // 清理临时文件
        Files.deleteIfExists(tmp);
        return "UPDATED:" + chunks;   // 返回新分块数
    }

    public List<Map<String, Object>> listAll() {
        return registryService.listAll();
    }

    private String md5(File f) throws Exception {
        byte[] bytes = Files.readAllBytes(f.toPath());
        byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}