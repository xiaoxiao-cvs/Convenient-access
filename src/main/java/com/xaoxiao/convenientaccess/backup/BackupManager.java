package com.xaoxiao.convenientaccess.backup;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xaoxiao.convenientaccess.ConvenientAccessPlugin;
import com.xaoxiao.convenientaccess.cache.CacheManager;

/**
 * 数据库备份管理器
 * 负责定时备份数据库文件，并管理备份文件的保留策略
 */
public class BackupManager {
    private static final Logger logger = LoggerFactory.getLogger(BackupManager.class);
    
    private final ConvenientAccessPlugin plugin;
    private final CacheManager cacheManager;
    private final ScheduledExecutorService scheduler;
    private final File backupFolder;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    
    // 配置项
    private boolean enabled;
    private int backupHour;  // 备份时间（小时，0-23）
    private int backupMinute;  // 备份时间（分钟，0-59）
    private int retentionDays;  // 备份保留天数
    private boolean compressBackup;  // 是否压缩备份
    
    public BackupManager(ConvenientAccessPlugin plugin, CacheManager cacheManager) {
        this.plugin = plugin;
        this.cacheManager = cacheManager;
        this.backupFolder = new File(plugin.getDataFolder(), "backup");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "BackupManager-Thread");
            thread.setDaemon(true);
            return thread;
        });
        
        // 确保备份文件夹存在
        if (!backupFolder.exists()) {
            backupFolder.mkdirs();
            logger.info("创建备份文件夹: {}", backupFolder.getAbsolutePath());
        }
    }
    
    /**
     * 初始化备份管理器
     */
    public void initialize() {
        // 从配置文件读取配置
        loadConfiguration();
        
        if (!enabled) {
            logger.info("备份功能已禁用");
            return;
        }
        
        logger.info("备份功能已启用，备份时间: {}:{:02d}, 保留天数: {}", 
                   backupHour, backupMinute, retentionDays);
        
        // 计算到下一次备份的延迟时间
        long initialDelay = calculateInitialDelay();
        
        // 调度定时备份任务（每24小时执行一次）
        scheduler.scheduleAtFixedRate(() -> {
            try {
                performBackup();
            } catch (Exception e) {
                logger.error("执行备份任务时发生异常", e);
            }
        }, initialDelay, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
        
        logger.info("备份任务已调度，首次备份将在 {} 秒后执行", initialDelay);
        
        // 清理过期备份
        cleanupOldBackups();
    }
    
    /**
     * 从配置文件加载配置
     */
    private void loadConfiguration() {
        enabled = plugin.getConfig().getBoolean("backup.enabled", true);
        backupHour = plugin.getConfig().getInt("backup.time.hour", 2);
        backupMinute = plugin.getConfig().getInt("backup.time.minute", 0);
        retentionDays = plugin.getConfig().getInt("backup.retention-days", 7);
        compressBackup = plugin.getConfig().getBoolean("backup.compress", true);
        
        // 验证配置
        if (backupHour < 0 || backupHour > 23) {
            logger.warn("备份小时配置无效 ({}), 使用默认值 2", backupHour);
            backupHour = 2;
        }
        if (backupMinute < 0 || backupMinute > 59) {
            logger.warn("备份分钟配置无效 ({}), 使用默认值 0", backupMinute);
            backupMinute = 0;
        }
        if (retentionDays < 1) {
            logger.warn("备份保留天数配置无效 ({}), 使用默认值 7", retentionDays);
            retentionDays = 7;
        }
    }
    
    /**
     * 计算到下一次备份的初始延迟时间（秒）
     */
    private long calculateInitialDelay() {
        Calendar now = Calendar.getInstance();
        Calendar nextBackup = Calendar.getInstance();
        
        // 设置下一次备份时间
        nextBackup.set(Calendar.HOUR_OF_DAY, backupHour);
        nextBackup.set(Calendar.MINUTE, backupMinute);
        nextBackup.set(Calendar.SECOND, 0);
        nextBackup.set(Calendar.MILLISECOND, 0);
        
        // 如果今天的备份时间已经过了，则调度到明天
        if (nextBackup.before(now) || nextBackup.equals(now)) {
            nextBackup.add(Calendar.DAY_OF_MONTH, 1);
        }
        
        long delayMillis = nextBackup.getTimeInMillis() - now.getTimeInMillis();
        return delayMillis / 1000;
    }
    
    /**
     * 执行备份操作
     */
    public CompletableFuture<Boolean> performBackup() {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("开始执行数据库备份...");
            
            try {
                // 在备份期间，确保缓存已预热，以便白名单系统可以正常工作
                ensureCacheWarmed();
                
                // 获取数据库文件路径
                File databaseFile = new File(plugin.getDataFolder(), "whitelist.db");
                if (!databaseFile.exists()) {
                    logger.warn("数据库文件不存在，跳过备份");
                    return false;
                }
                
                // 生成备份文件名
                String timestamp = dateFormat.format(new Date());
                String backupFileName = compressBackup 
                    ? "whitelist_backup_" + timestamp + ".zip"
                    : "whitelist_backup_" + timestamp + ".db";
                File backupFile = new File(backupFolder, backupFileName);
                
                // 执行备份
                if (compressBackup) {
                    createZipBackup(databaseFile, backupFile);
                } else {
                    createPlainBackup(databaseFile, backupFile);
                }
                
                logger.info("数据库备份成功: {}", backupFile.getName());
                
                // 清理过期备份
                cleanupOldBackups();
                
                return true;
                
            } catch (Exception e) {
                logger.error("数据库备份失败", e);
                return false;
            }
        });
    }
    
    /**
     * 确保缓存已预热
     * 在备份期间，白名单将主要依赖缓存工作
     */
    private void ensureCacheWarmed() {
        if (cacheManager != null) {
            logger.info("备份期间，白名单系统将依赖缓存工作");
            // 缓存管理器应该已经包含了白名单数据的缓存
            // 这里只是做一个提示，实际的缓存预热应该在白名单系统中完成
        }
    }
    
    /**
     * 创建ZIP格式的压缩备份
     */
    private void createZipBackup(File sourceFile, File zipFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos);
             FileInputStream fis = new FileInputStream(sourceFile)) {
            
            // 添加数据库文件到ZIP
            ZipEntry zipEntry = new ZipEntry(sourceFile.getName());
            zos.putNextEntry(zipEntry);
            
            // 复制文件内容
            byte[] buffer = new byte[8192];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                zos.write(buffer, 0, length);
            }
            
            zos.closeEntry();
            
            // 可选：添加备份信息文件
            addBackupInfo(zos);
            
            logger.debug("ZIP备份创建成功: {}", zipFile.getName());
        }
    }
    
    /**
     * 创建普通格式的备份（直接复制）
     */
    private void createPlainBackup(File sourceFile, File backupFile) throws IOException {
        Files.copy(sourceFile.toPath(), backupFile.toPath(), 
                  StandardCopyOption.REPLACE_EXISTING);
        logger.debug("普通备份创建成功: {}", backupFile.getName());
    }
    
    /**
     * 向ZIP文件添加备份信息
     */
    private void addBackupInfo(ZipOutputStream zos) throws IOException {
        ZipEntry infoEntry = new ZipEntry("backup_info.txt");
        zos.putNextEntry(infoEntry);
        
        StringBuilder info = new StringBuilder();
        info.append("Backup Information\n");
        info.append("==================\n");
        info.append("Plugin: ConvenientAccess\n");
        info.append("Backup Time: ").append(new Date()).append("\n");
        info.append("Database File: whitelist.db\n");
        info.append("Server: ").append(plugin.getServer().getName()).append("\n");
        info.append("Version: ").append(plugin.getServer().getVersion()).append("\n");
        
        zos.write(info.toString().getBytes());
        zos.closeEntry();
    }
    
    /**
     * 清理过期的备份文件
     */
    private void cleanupOldBackups() {
        try {
            File[] backupFiles = backupFolder.listFiles((dir, name) -> 
                name.startsWith("whitelist_backup_") && 
                (name.endsWith(".db") || name.endsWith(".zip"))
            );
            
            if (backupFiles == null || backupFiles.length == 0) {
                logger.debug("没有找到备份文件");
                return;
            }
            
            // 计算过期时间
            long expirationTime = System.currentTimeMillis() - 
                                 TimeUnit.DAYS.toMillis(retentionDays);
            
            int deletedCount = 0;
            for (File backupFile : backupFiles) {
                if (backupFile.lastModified() < expirationTime) {
                    if (backupFile.delete()) {
                        deletedCount++;
                        logger.debug("删除过期备份: {}", backupFile.getName());
                    } else {
                        logger.warn("无法删除过期备份: {}", backupFile.getName());
                    }
                }
            }
            
            if (deletedCount > 0) {
                logger.info("清理了 {} 个过期备份文件", deletedCount);
            }
            
        } catch (Exception e) {
            logger.error("清理过期备份时发生异常", e);
        }
    }
    
    /**
     * 手动触发备份
     * @return 备份是否成功
     */
    public CompletableFuture<Boolean> manualBackup() {
        logger.info("手动触发数据库备份");
        return performBackup();
    }
    
    /**
     * 获取所有备份文件列表
     */
    public List<BackupInfo> listBackups() {
        File[] backupFiles = backupFolder.listFiles((dir, name) -> 
            name.startsWith("whitelist_backup_") && 
            (name.endsWith(".db") || name.endsWith(".zip"))
        );
        
        if (backupFiles == null || backupFiles.length == 0) {
            return Collections.emptyList();
        }
        
        return Arrays.stream(backupFiles)
            .map(file -> new BackupInfo(
                file.getName(),
                file.length(),
                new Date(file.lastModified()),
                file.getAbsolutePath()
            ))
            .sorted((a, b) -> b.getCreatedTime().compareTo(a.getCreatedTime()))
            .collect(Collectors.toList());
    }
    
    /**
     * 恢复备份
     * @param backupFileName 备份文件名
     * @return 恢复是否成功
     */
    public CompletableFuture<Boolean> restoreBackup(String backupFileName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                File backupFile = new File(backupFolder, backupFileName);
                if (!backupFile.exists()) {
                    logger.error("备份文件不存在: {}", backupFileName);
                    return false;
                }
                
                File databaseFile = new File(plugin.getDataFolder(), "whitelist.db");
                
                // 创建当前数据库的临时备份
                File tempBackup = new File(plugin.getDataFolder(), 
                                          "whitelist.db.restore_backup");
                if (databaseFile.exists()) {
                    Files.copy(databaseFile.toPath(), tempBackup.toPath(), 
                             StandardCopyOption.REPLACE_EXISTING);
                    logger.info("已创建当前数据库的临时备份");
                }
                
                try {
                    if (backupFileName.endsWith(".zip")) {
                        // 从ZIP恢复
                        restoreFromZip(backupFile, databaseFile);
                    } else {
                        // 从普通备份恢复
                        Files.copy(backupFile.toPath(), databaseFile.toPath(), 
                                 StandardCopyOption.REPLACE_EXISTING);
                    }
                    
                    logger.info("数据库恢复成功: {}", backupFileName);
                    
                    // 删除临时备份
                    if (tempBackup.exists()) {
                        tempBackup.delete();
                    }
                    
                    return true;
                    
                } catch (Exception e) {
                    logger.error("恢复备份失败，尝试回滚", e);
                    
                    // 回滚到临时备份
                    if (tempBackup.exists()) {
                        Files.copy(tempBackup.toPath(), databaseFile.toPath(), 
                                 StandardCopyOption.REPLACE_EXISTING);
                        logger.info("已回滚到恢复前的数据库");
                    }
                    
                    return false;
                }
                
            } catch (Exception e) {
                logger.error("恢复备份时发生异常", e);
                return false;
            }
        });
    }
    
    /**
     * 从ZIP文件恢复数据库
     */
    private void restoreFromZip(File zipFile, File targetFile) throws IOException {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new FileInputStream(zipFile))) {
            
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("whitelist.db")) {
                    // 找到数据库文件，开始恢复
                    try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, length);
                        }
                    }
                    break;
                }
                zis.closeEntry();
            }
        }
    }
    
    /**
     * 关闭备份管理器
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("备份管理器已关闭");
    }
    
    /**
     * 备份信息类
     */
    public static class BackupInfo {
        private final String fileName;
        private final long fileSize;
        private final Date createdTime;
        private final String filePath;
        
        public BackupInfo(String fileName, long fileSize, Date createdTime, String filePath) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.createdTime = createdTime;
            this.filePath = filePath;
        }
        
        public String getFileName() {
            return fileName;
        }
        
        public long getFileSize() {
            return fileSize;
        }
        
        public String getFileSizeFormatted() {
            if (fileSize < 1024) {
                return fileSize + " B";
            } else if (fileSize < 1024 * 1024) {
                return String.format("%.2f KB", fileSize / 1024.0);
            } else {
                return String.format("%.2f MB", fileSize / (1024.0 * 1024.0));
            }
        }
        
        public Date getCreatedTime() {
            return createdTime;
        }
        
        public String getFilePath() {
            return filePath;
        }
    }
}
