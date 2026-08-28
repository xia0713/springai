package com.example.springai.component;

import com.example.springai.model.DocumentBatchTask;
import com.example.springai.model.DocumentBatchTask.FileResult;
import com.example.springai.service.DocumentProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchDocumentProcessor {

    private final DocumentProcessingService processingService;
    private final BatchTaskStore taskStore;

    /**
     * 后台异步执行整个批次。
     * 注意：这个 @Async 方法在独立 Bean 里，被 BatchDocumentService 跨 Bean 调用，代理才生效。
     */
    @Async("documentTaskExecutor")
    public void processAsync(String taskId, List<Path> files, List<String> filenames) {
        DocumentBatchTask task = taskStore.get(taskId);

        for (int i = 0; i < files.size(); i++) {
            String name = filenames.get(i);
            FileResult result;
            try {
                // 内部自带 3 次重试，重试耗尽仍失败会抛异常
                int chunks = processingService.processAndStore(files.get(i), name);
                result = new FileResult(name, true, chunks, null);
            } catch (Exception e) {
                log.error("文件处理失败: {}", name, e);
                result = new FileResult(name, false, 0, e.getMessage());
            }
            task.markCompleted(result);   // 每个文件完成都更新进度

            log.info("批次 {} 进度 {}/{}（成功 {} 失败 {}）",
                    taskId, task.getCompleted(), task.getTotal(),
                    task.getSuccessCount(), task.getFailedCount());
        }
    }
}