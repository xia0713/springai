package com.example.springai.model;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DocumentBatchTask {

    public enum TaskStatus { PENDING, PROCESSING, SUCCESS, PARTIAL_SUCCESS, FAILED }

    public record FileResult(String filename, boolean success, int chunkCount, String error) {}

    private final String taskId;
    private final int total;
    private final long startTime = System.currentTimeMillis();

    private volatile TaskStatus status = TaskStatus.PENDING;
    private volatile int completed = 0;
    private volatile int successCount = 0;
    private volatile int failedCount = 0;
    private volatile long endTime;

    // CopyOnWriteArrayList：多线程并发往里加结果也安全
    private final List<FileResult> results = new CopyOnWriteArrayList<>();

    public DocumentBatchTask(String taskId, int total) {
        this.taskId = taskId;
        this.total = total;
    }

    /** 每个文件处理完就调一次，自动累加计数并在全部完成时收敛终态 */
    public synchronized void markCompleted(FileResult result) {
        results.add(result);
        completed++;
        if (result.success()) {
            successCount++;
        } else {
            failedCount++;
        }
        if (completed >= total) {
            endTime = System.currentTimeMillis();
            if (failedCount == 0) {
                status = TaskStatus.SUCCESS;
            } else if (successCount == 0) {
                status = TaskStatus.FAILED;
            } else {
                status = TaskStatus.PARTIAL_SUCCESS;
            }
        }
    }

    // getters...
    public String getTaskId() { return taskId; }
    public int getTotal() { return total; }
    public TaskStatus getStatus() { return status; }
    public int getCompleted() { return completed; }
    public int getSuccessCount() { return successCount; }
    public int getFailedCount() { return failedCount; }
    public List<FileResult> getResults() { return results; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }

    public void setStatus(TaskStatus status) { this.status = status; }
}