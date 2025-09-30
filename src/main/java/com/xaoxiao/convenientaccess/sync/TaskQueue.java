package com.xaoxiao.convenientaccess.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 任务队列管理器
 * 支持优先级队列、批量处理和任务合并
 */
public class TaskQueue {
    private static final Logger logger = LoggerFactory.getLogger(TaskQueue.class);
    
    private final PriorityBlockingQueue<SyncTask> taskQueue;
    private final Map<String, SyncTask> taskMap; // 用于快速查找和去重
    private final AtomicLong taskIdGenerator = new AtomicLong(0);
    
    // 任务合并配置
    private static final int MAX_BATCH_SIZE = 50;
    private static final long BATCH_MERGE_WINDOW_MS = 5000; // 5秒内的相似任务可以合并
    
    public TaskQueue() {
        // 使用优先级比较器
        this.taskQueue = new PriorityBlockingQueue<>(100, new TaskPriorityComparator());
        this.taskMap = new ConcurrentHashMap<>();
    }
    
    /**
     * 添加任务到队列
     */
    public synchronized boolean addTask(SyncTask task) {
        if (task == null) {
            return false;
        }
        
        // 生成任务ID
        if (task.getId() == null) {
            task.setId(taskIdGenerator.incrementAndGet());
        }
        
        // 检查是否可以合并任务
        SyncTask mergedTask = tryMergeTask(task);
        if (mergedTask != null) {
            // 任务已合并，更新现有任务
            logger.debug("任务已合并: {} -> {}", task.getId(), mergedTask.getId());
            return true;
        }
        
        // 检查重复任务
        String taskKey = generateTaskKey(task);
        if (taskMap.containsKey(taskKey)) {
            SyncTask existingTask = taskMap.get(taskKey);
            if (existingTask.getStatus() == SyncTask.TaskStatus.PENDING) {
                logger.debug("跳过重复任务: {}", taskKey);
                return false;
            }
        }
        
        // 添加新任务
        taskMap.put(taskKey, task);
        boolean added = taskQueue.offer(task);
        
        if (added) {
            logger.debug("任务已添加到队列: {} (优先级: {})", task.getId(), task.getPriority());
        } else {
            logger.warn("任务添加失败: {}", task.getId());
            taskMap.remove(taskKey);
        }
        
        return added;
    }
    
    /**
     * 获取下一个待处理任务
     */
    public SyncTask pollTask() {
        SyncTask task = taskQueue.poll();
        if (task != null) {
            String taskKey = generateTaskKey(task);
            taskMap.remove(taskKey);
            logger.debug("从队列获取任务: {} (类型: {})", task.getId(), task.getTaskType());
        }
        return task;
    }
    
    /**
     * 批量获取任务
     */
    public List<SyncTask> pollTasks(int maxCount) {
        List<SyncTask> tasks = new ArrayList<>();
        
        for (int i = 0; i < maxCount && !taskQueue.isEmpty(); i++) {
            SyncTask task = pollTask();
            if (task != null) {
                tasks.add(task);
            }
        }
        
        return tasks;
    }
    
    /**
     * 获取可批量处理的任务
     */
    public List<SyncTask> pollBatchableTasks() {
        List<SyncTask> batchTasks = new ArrayList<>();
        SyncTask firstTask = pollTask();
        
        if (firstTask == null) {
            return batchTasks;
        }
        
        batchTasks.add(firstTask);
        
        // 查找相同类型的任务进行批量处理
        if (isBatchableTaskType(firstTask.getTaskType())) {
            while (batchTasks.size() < MAX_BATCH_SIZE) {
                SyncTask nextTask = peekTask();
                if (nextTask != null && 
                    nextTask.getTaskType() == firstTask.getTaskType() &&
                    nextTask.getPriority() == firstTask.getPriority()) {
                    
                    batchTasks.add(pollTask());
                } else {
                    break;
                }
            }
        }
        
        logger.debug("获取批量任务: {} 个 (类型: {})", batchTasks.size(), firstTask.getTaskType());
        return batchTasks;
    }
    
    /**
     * 查看队列头部任务但不移除
     */
    public SyncTask peekTask() {
        return taskQueue.peek();
    }
    
    /**
     * 获取队列大小
     */
    public int size() {
        return taskQueue.size();
    }
    
    /**
     * 检查队列是否为空
     */
    public boolean isEmpty() {
        return taskQueue.isEmpty();
    }
    
    /**
     * 清空队列
     */
    public synchronized void clear() {
        taskQueue.clear();
        taskMap.clear();
        logger.info("任务队列已清空");
    }
    
    /**
     * 获取队列统计信息
     */
    public QueueStats getStats() {
        Map<SyncTask.TaskType, Integer> typeCounts = new HashMap<>();
        Map<SyncTask.TaskStatus, Integer> statusCounts = new HashMap<>();
        Map<Integer, Integer> priorityCounts = new HashMap<>();
        
        for (SyncTask task : taskQueue) {
            // 按类型统计
            typeCounts.merge(task.getTaskType(), 1, Integer::sum);
            
            // 按状态统计
            statusCounts.merge(task.getStatus(), 1, Integer::sum);
            
            // 按优先级统计
            priorityCounts.merge(task.getPriority(), 1, Integer::sum);
        }
        
        return new QueueStats(taskQueue.size(), typeCounts, statusCounts, priorityCounts);
    }
    
    /**
     * 尝试合并任务
     */
    private SyncTask tryMergeTask(SyncTask newTask) {
        if (!isMergeableTaskType(newTask.getTaskType())) {
            return null;
        }
        
        LocalDateTime mergeWindow = LocalDateTime.now().minusNanos(BATCH_MERGE_WINDOW_MS * 1_000_000);
        
        for (SyncTask existingTask : taskQueue) {
            if (canMergeTasks(existingTask, newTask, mergeWindow)) {
                // 合并任务数据
                mergeTaskData(existingTask, newTask);
                return existingTask;
            }
        }
        
        return null;
    }
    
    /**
     * 检查两个任务是否可以合并
     */
    private boolean canMergeTasks(SyncTask existing, SyncTask newTask, LocalDateTime mergeWindow) {
        return existing.getTaskType() == newTask.getTaskType() &&
               existing.getStatus() == SyncTask.TaskStatus.PENDING &&
               existing.getPriority() == newTask.getPriority() &&
               existing.getCreatedAt().isAfter(mergeWindow);
    }
    
    /**
     * 合并任务数据
     */
    private void mergeTaskData(SyncTask existing, SyncTask newTask) {
        // 这里可以根据任务类型实现具体的合并逻辑
        // 目前简单地更新时间戳
        existing.setUpdatedAt(LocalDateTime.now());
        logger.debug("任务数据已合并: {} <- {}", existing.getId(), newTask.getId());
    }
    
    /**
     * 生成任务键用于去重
     */
    private String generateTaskKey(SyncTask task) {
        return task.getTaskType() + ":" + task.getData();
    }
    
    /**
     * 检查任务类型是否支持批量处理
     */
    private boolean isBatchableTaskType(SyncTask.TaskType taskType) {
        return taskType == SyncTask.TaskType.ADD_PLAYER ||
               taskType == SyncTask.TaskType.REMOVE_PLAYER ||
               taskType == SyncTask.TaskType.BATCH_UPDATE;
    }
    
    /**
     * 检查任务类型是否支持合并
     */
    private boolean isMergeableTaskType(SyncTask.TaskType taskType) {
        return taskType == SyncTask.TaskType.BATCH_UPDATE ||
               taskType == SyncTask.TaskType.FULL_SYNC;
    }
    
    /**
     * 任务优先级比较器
     */
    private static class TaskPriorityComparator implements Comparator<SyncTask> {
        @Override
        public int compare(SyncTask t1, SyncTask t2) {
            // 优先级高的排在前面（数字小的优先级高）
            int priorityCompare = Integer.compare(t1.getPriority(), t2.getPriority());
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            
            // 优先级相同时，按创建时间排序（早创建的优先）
            return t1.getCreatedAt().compareTo(t2.getCreatedAt());
        }
    }
    
    /**
     * 队列统计信息类
     */
    public static class QueueStats {
        private final int totalTasks;
        private final Map<SyncTask.TaskType, Integer> typeCounts;
        private final Map<SyncTask.TaskStatus, Integer> statusCounts;
        private final Map<Integer, Integer> priorityCounts;
        
        public QueueStats(int totalTasks, 
                         Map<SyncTask.TaskType, Integer> typeCounts,
                         Map<SyncTask.TaskStatus, Integer> statusCounts,
                         Map<Integer, Integer> priorityCounts) {
            this.totalTasks = totalTasks;
            this.typeCounts = new HashMap<>(typeCounts);
            this.statusCounts = new HashMap<>(statusCounts);
            this.priorityCounts = new HashMap<>(priorityCounts);
        }
        
        public int getTotalTasks() {
            return totalTasks;
        }
        
        public Map<SyncTask.TaskType, Integer> getTypeCounts() {
            return typeCounts;
        }
        
        public Map<SyncTask.TaskStatus, Integer> getStatusCounts() {
            return statusCounts;
        }
        
        public Map<Integer, Integer> getPriorityCounts() {
            return priorityCounts;
        }
        
        @Override
        public String toString() {
            return "QueueStats{" +
                    "totalTasks=" + totalTasks +
                    ", typeCounts=" + typeCounts +
                    ", statusCounts=" + statusCounts +
                    ", priorityCounts=" + priorityCounts +
                    '}';
        }
    }
}