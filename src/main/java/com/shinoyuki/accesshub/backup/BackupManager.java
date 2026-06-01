package com.shinoyuki.accesshub.backup;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.config.AccessHubConfig;

/**
 * 数据库备份管理器 (v3 Forge 版, 由 v1 Bukkit BackupManager 重写)。
 *
 * 定时备份 whitelist.db, 支持 ZIP 压缩、保留天数清理、手动备份、列表、恢复 (带临时备份+失败回滚)。
 *
 * v3 改动:
 *  - 去除 ConvenientAccessPlugin / CacheManager 依赖, 构造改为 (File dataFolder, AccessHubConfig)
 *  - 配置经 AccessHubConfig 读取 (backup.schedule "d:h:m" 单一格式), 删除 v1 的 backup.time 旧格式自动升级
 *    (TOML 新配置无历史包袱, 升级逻辑无意义)
 *  - 备份信息文件去除 Bukkit getServer().getName()/getVersion(), 仅记录 mod id + 时间
 *
 * 备份目录: &lt;dataFolder&gt;/backup/ (与 whitelist.db 同级, 即 config/Shinoyuki-Optimize/shinoyuki_accesshub/backup/)
 */
public class BackupManager {
    private static final Logger logger = LoggerFactory.getLogger(BackupManager.class);

    private final File dataFolder;
    private final AccessHubConfig config;
    private final File backupFolder;
    private final ScheduledExecutorService scheduler;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

    private boolean enabled;
    private int backupDays;
    private int backupHours;
    private int backupMinutes;
    private int retentionDays;
    private boolean compressBackup;

    public BackupManager(File dataFolder, AccessHubConfig config) {
        this.dataFolder = dataFolder;
        this.config = config;
        this.backupFolder = new File(dataFolder, "backup");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "AccessHub-Backup");
            thread.setDaemon(true);
            return thread;
        });

        if (!backupFolder.exists() && backupFolder.mkdirs()) {
            logger.info("创建备份目录: {}", backupFolder.getAbsolutePath());
        }
    }

    public void initialize() {
        loadConfiguration();

        if (!enabled) {
            logger.info("数据库自动备份已禁用");
            return;
        }

        String scheduleInfo = backupDays > 0
                ? String.format("每%d天 %02d:%02d", backupDays, backupHours, backupMinutes)
                : String.format("每天 %02d:%02d", backupHours, backupMinutes);
        logger.info("数据库自动备份已启用, 计划: {}, 保留: {}天", scheduleInfo, retentionDays);

        long initialDelay = calculateInitialDelay();
        long intervalSeconds = TimeUnit.DAYS.toSeconds(backupDays > 0 ? backupDays : 1);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                performBackup().join();
            } catch (Exception e) {
                logger.error("执行定时备份任务时发生异常", e);
            }
        }, initialDelay, intervalSeconds, TimeUnit.SECONDS);

        logger.info("备份任务已调度, 首次备份将在 {} 后执行", formatDuration(initialDelay));
        cleanupOldBackups();
    }

    /**
     * 加载备份配置。仅支持 "天:小时:分钟" 单一格式, 解析失败回退每天 02:00。
     */
    private void loadConfiguration() {
        enabled = config.isBackupEnabled();
        retentionDays = config.getBackupRetentionDays();
        compressBackup = config.isBackupCompress();

        if (!parseSchedule(config.getBackupSchedule())) {
            logger.warn("备份计划格式无效 ({}), 回退默认 每天 02:00", config.getBackupSchedule());
            this.backupDays = 0;
            this.backupHours = 2;
            this.backupMinutes = 0;
        }

        if (retentionDays < 1) {
            logger.warn("备份保留天数无效 ({}), 回退默认 7", retentionDays);
            retentionDays = 7;
        }
    }

    /**
     * 解析备份计划 "天:小时:分钟"。
     */
    private boolean parseSchedule(String schedule) {
        if (schedule == null || schedule.isEmpty()) {
            return false;
        }
        try {
            String[] parts = schedule.split(":");
            if (parts.length != 3) {
                return false;
            }
            int days = Integer.parseInt(parts[0].trim());
            int hours = Integer.parseInt(parts[1].trim());
            int minutes = Integer.parseInt(parts[2].trim());
            if (days < 0 || hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
                return false;
            }
            this.backupDays = days;
            this.backupHours = hours;
            this.backupMinutes = minutes;
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private long calculateInitialDelay() {
        Calendar now = Calendar.getInstance();
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, backupHours);
        next.set(Calendar.MINUTE, backupMinutes);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);

        if (backupDays > 0) {
            next.add(Calendar.DAY_OF_MONTH, backupDays);
        }
        if (!next.after(now)) {
            next.add(Calendar.DAY_OF_MONTH, backupDays > 0 ? backupDays : 1);
        }
        return (next.getTimeInMillis() - now.getTimeInMillis()) / 1000;
    }

    private String formatDuration(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("天");
        if (hours > 0) sb.append(hours).append("小时");
        if (minutes > 0) sb.append(minutes).append("分钟");
        if (secs > 0 && days == 0) sb.append(secs).append("秒");
        return sb.length() > 0 ? sb.toString() : "0秒";
    }

    /**
     * 执行一次备份。
     */
    public CompletableFuture<Boolean> performBackup() {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("开始执行数据库备份...");
            try {
                File databaseFile = new File(dataFolder, "whitelist.db");
                if (!databaseFile.exists()) {
                    logger.warn("数据库文件不存在, 跳过备份: {}", databaseFile.getAbsolutePath());
                    return false;
                }

                String timestamp = dateFormat.format(new Date());
                String backupFileName = compressBackup
                        ? "whitelist_backup_" + timestamp + ".zip"
                        : "whitelist_backup_" + timestamp + ".db";
                File backupFile = new File(backupFolder, backupFileName);

                if (compressBackup) {
                    createZipBackup(databaseFile, backupFile);
                } else {
                    Files.copy(databaseFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }

                logger.info("数据库备份成功: {}", backupFile.getName());
                cleanupOldBackups();
                return true;
            } catch (Exception e) {
                logger.error("数据库备份失败", e);
                return false;
            }
        });
    }

    private void createZipBackup(File sourceFile, File zipFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos);
             FileInputStream fis = new FileInputStream(sourceFile)) {

            zos.putNextEntry(new ZipEntry(sourceFile.getName()));
            byte[] buffer = new byte[8192];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                zos.write(buffer, 0, length);
            }
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("backup_info.txt"));
            String info = "AccessHub Backup\n================\n"
                    + "Mod: shinoyuki_accesshub\n"
                    + "Backup Time: " + new Date() + "\n"
                    + "Database File: whitelist.db\n";
            zos.write(info.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private void cleanupOldBackups() {
        try {
            File[] backupFiles = backupFolder.listFiles((dir, name) ->
                    name.startsWith("whitelist_backup_") && (name.endsWith(".db") || name.endsWith(".zip")));
            if (backupFiles == null || backupFiles.length == 0) {
                return;
            }

            long expirationTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays);
            int deletedCount = 0;
            for (File backupFile : backupFiles) {
                if (backupFile.lastModified() < expirationTime) {
                    if (backupFile.delete()) {
                        deletedCount++;
                    } else {
                        logger.warn("无法删除过期备份: {}", backupFile.getName());
                    }
                }
            }
            if (deletedCount > 0) {
                logger.info("清理了 {} 个过期备份", deletedCount);
            }
        } catch (Exception e) {
            logger.error("清理过期备份时发生异常", e);
        }
    }

    public CompletableFuture<Boolean> manualBackup() {
        logger.info("手动触发数据库备份");
        return performBackup();
    }

    public List<BackupInfo> listBackups() {
        File[] backupFiles = backupFolder.listFiles((dir, name) ->
                name.startsWith("whitelist_backup_") && (name.endsWith(".db") || name.endsWith(".zip")));
        if (backupFiles == null || backupFiles.length == 0) {
            return Collections.emptyList();
        }
        return Arrays.stream(backupFiles)
                .map(file -> new BackupInfo(file.getName(), file.length(),
                        new Date(file.lastModified()), file.getAbsolutePath()))
                .sorted((a, b) -> b.getCreatedTime().compareTo(a.getCreatedTime()))
                .collect(Collectors.toList());
    }

    /**
     * 恢复备份, 恢复前对当前库做临时备份, 失败回滚。
     */
    public CompletableFuture<Boolean> restoreBackup(String backupFileName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                File backupFile = new File(backupFolder, backupFileName);
                if (!backupFile.exists()) {
                    logger.error("备份文件不存在: {}", backupFileName);
                    return false;
                }

                File databaseFile = new File(dataFolder, "whitelist.db");
                File tempBackup = new File(dataFolder, "whitelist.db.restore_backup");
                if (databaseFile.exists()) {
                    Files.copy(databaseFile.toPath(), tempBackup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }

                try {
                    if (backupFileName.endsWith(".zip")) {
                        restoreFromZip(backupFile, databaseFile);
                    } else {
                        Files.copy(backupFile.toPath(), databaseFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    logger.info("数据库恢复成功: {}", backupFileName);
                    if (tempBackup.exists() && !tempBackup.delete()) {
                        logger.warn("恢复后无法删除临时备份: {}", tempBackup.getName());
                    }
                    return true;
                } catch (Exception e) {
                    logger.error("恢复失败, 尝试回滚", e);
                    if (tempBackup.exists()) {
                        Files.copy(tempBackup.toPath(), databaseFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
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

    private void restoreFromZip(File zipFile, File targetFile) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("whitelist.db")) {
                    try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, length);
                        }
                    }
                    return;
                }
                zis.closeEntry();
            }
            throw new IOException("备份 ZIP 内未找到 whitelist.db");
        }
    }

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
     * 备份文件元信息。
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

        public String getFileName() { return fileName; }
        public long getFileSize() { return fileSize; }

        public String getFileSizeFormatted() {
            if (fileSize < 1024) {
                return fileSize + " B";
            } else if (fileSize < 1024 * 1024) {
                return String.format("%.2f KB", fileSize / 1024.0);
            }
            return String.format("%.2f MB", fileSize / (1024.0 * 1024.0));
        }

        public Date getCreatedTime() { return createdTime; }
        public String getFilePath() { return filePath; }
    }
}
