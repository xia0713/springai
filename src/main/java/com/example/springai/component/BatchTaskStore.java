package com.example.springai.component;

import com.example.springai.model.DocumentBatchTask;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class BatchTaskStore {

    // 内存存储：taskId -> 批次任务。进程重启会丢，生产用 Redis/DB。
    private final ConcurrentHashMap<String, DocumentBatchTask> tasks = new ConcurrentHashMap<>();

    public void put(DocumentBatchTask task) {
        tasks.put(task.getTaskId(), task);
    }

    public DocumentBatchTask get(String taskId) {
        return tasks.get(taskId);
    }
}
