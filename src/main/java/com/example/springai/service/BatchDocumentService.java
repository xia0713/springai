package com.example.springai.service;

import com.example.springai.component.BatchDocumentProcessor;
import com.example.springai.component.BatchTaskStore;
import com.example.springai.exception.AiException;
import com.example.springai.model.DocumentBatchTask;
import com.example.springai.model.DocumentBatchTask.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BatchDocumentService {

    private final BatchDocumentProcessor processor;
    private final BatchTaskStore taskStore;

    /**
     * 提交批量任务：先把文件落地到本地临时目录，再异步处理，立即返回 taskId。
     *
     * ⚠️ 第三大坑（必考）：为什么必须先落地？
     * HTTP 请求一结束，Servlet 容器会立刻删除 MultipartFile 对应的临时文件。
     * 如果直接把 MultipartFile 传给异步线程，处理时文件早没了，报 FileNotFoundException。
     * 所以要在【请求线程内】先把文件 transferTo 到自己的临时目录，再把 Path 交给异步线程。
     */
    public String submit(List<MultipartFile> files, String owner) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("至少上传一个文件");
        }

        String taskId = UUID.randomUUID().toString();
        DocumentBatchTask task = new DocumentBatchTask(taskId, files.size());
        task.setStatus(TaskStatus.PROCESSING);
        taskStore.put(task);

        // 请求线程内同步落地文件（安全），只存 Path 和文件名给异步线程
        Path tempDir = Files.createTempDirectory("doc-batch-" + taskId);
        List<Path> paths = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename();
            Path target = tempDir.resolve(name == null ? "unnamed" : name);
            f.transferTo(target);
            paths.add(target);
            names.add(name);
        }

        // 跨 Bean 调用，@Async 真正生效；立即返回，不等处理完
        processor.processAsync(taskId, paths, names, owner);

        return taskId;
    }

    /** 查进度 */
    public DocumentBatchTask getStatus(String taskId) {
        DocumentBatchTask task = taskStore.get(taskId);
        if (task == null) {
            throw new AiException("任务不存在: " + taskId);
        }
        return task;
    }
}