package com.xaoxiao.convenientaccess.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 玩家数据API控制器
 * 处理玩家详细数据查询的API请求
 */
public class PlayerDataApiController {
    private static final Logger logger = LoggerFactory.getLogger(PlayerDataApiController.class);
    
    private final Gson gson;
    
    public PlayerDataApiController() {
        this.gson = new Gson();
    }
    
    /**
     * 处理GET /api/v1/player?name=玩家名 - 获取玩家详细数据
     */
    public void handleGetPlayerData(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 从查询参数中获取玩家名
        String playerName = request.getParameter("name");
        
        try {
            if (playerName == null || playerName.trim().isEmpty()) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("玩家名称不能为空，请使用 ?name=玩家名 参数"));
                return;
            }
            
            // 首先尝试通过玩家名获取在线玩家
            Player onlinePlayer = Bukkit.getPlayerExact(playerName);
            
            if (onlinePlayer != null) {
                // 玩家在线，获取完整数据
                PlayerData playerData = collectOnlinePlayerData(onlinePlayer);
                sendJsonResponse(response, 200, ApiResponse.success(playerData, "成功获取玩家数据（在线）"));
                logger.info("成功获取在线玩家数据: {}", playerName);
            } else {
                // 玩家离线，尝试获取离线玩家数据
                @SuppressWarnings("deprecation")
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
                
                if (offlinePlayer.hasPlayedBefore()) {
                    PlayerData playerData = collectOfflinePlayerData(offlinePlayer);
                    sendJsonResponse(response, 200, ApiResponse.success(playerData, "成功获取玩家数据（离线）"));
                    logger.info("成功获取离线玩家数据: {}", playerName);
                } else {
                    sendJsonResponse(response, 404, ApiResponse.notFound("玩家不存在或从未登录过服务器"));
                    logger.warn("玩家不存在: {}", playerName);
                }
            }
            
        } catch (Exception e) {
            logger.error("获取玩家数据时发生错误: {}", playerName, e);
            sendJsonResponse(response, 500, ApiResponse.error("获取玩家数据失败: " + e.getMessage()));
        }
    }
    
    /**
     * 收集在线玩家的完整数据
     */
    private PlayerData collectOnlinePlayerData(Player player) {
        PlayerData data = new PlayerData();
        
        // 基本信息
        data.playerName = player.getName();
        data.uuid = player.getUniqueId().toString();
        data.isOnline = true;
        data.hasPlayedBefore = true;
        data.firstPlayed = player.getFirstPlayed();
        data.lastPlayed = player.getLastPlayed();
        data.lastLogin = player.getLastPlayed(); // 使用 lastPlayed 代替 lastLogin
        
        // 游戏模式
        data.gameMode = player.getGameMode().name();
        
        // 位置和维度信息
        Location loc = player.getLocation();
        data.location = new LocationData();
        World world = loc.getWorld();
        if (world != null) {
            data.location.world = world.getName();
            data.location.dimension = world.getEnvironment().name();
        } else {
            data.location.world = "unknown";
        }
        data.location.x = loc.getX();
        data.location.y = loc.getY();
        data.location.z = loc.getZ();
        data.location.yaw = loc.getYaw();
        data.location.pitch = loc.getPitch();
        
        // 生命值和状态
        data.health = player.getHealth();
        data.maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH) != null ? 
            player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue() : 20.0;
        data.foodLevel = player.getFoodLevel();
        data.saturation = player.getSaturation();
        data.exhaustion = player.getExhaustion();
        data.level = player.getLevel();
        data.exp = player.getExp();
        data.totalExperience = player.getTotalExperience();
        
        // 空气值和火焰
        data.remainingAir = player.getRemainingAir();
        data.maximumAir = player.getMaximumAir();
        data.fireTicks = player.getFireTicks();
        
        // 能力
        data.isFlying = player.isFlying();
        data.allowFlight = player.getAllowFlight();
        data.isInvulnerable = player.isInvulnerable();
        data.isSneaking = player.isSneaking();
        data.isSprinting = player.isSprinting();
        data.isSwimming = player.isSwimming();
        data.isGliding = player.isGliding();
        data.isBlocking = player.isBlocking();
        
        // 速度
        data.walkSpeed = player.getWalkSpeed();
        data.flySpeed = player.getFlySpeed();
        
        // 药水效果
        data.potionEffects = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            PotionEffectData effectData = new PotionEffectData();
            effectData.type = effect.getType().getName();
            effectData.amplifier = effect.getAmplifier();
            effectData.duration = effect.getDuration();
            effectData.isAmbient = effect.isAmbient();
            effectData.hasParticles = effect.hasParticles();
            effectData.hasIcon = effect.hasIcon();
            data.potionEffects.add(effectData);
        }
        
        // 物品信息
        data.inventory = collectInventoryData(player.getInventory());
        
        // 末影箱
        data.enderChest = collectEnderChestData(player.getEnderChest());
        
        // 出生点
        Location bedSpawn = player.getBedSpawnLocation();
        if (bedSpawn != null) {
            data.bedSpawnLocation = new LocationData();
            data.bedSpawnLocation.world = bedSpawn.getWorld() != null ? bedSpawn.getWorld().getName() : "unknown";
            data.bedSpawnLocation.x = bedSpawn.getX();
            data.bedSpawnLocation.y = bedSpawn.getY();
            data.bedSpawnLocation.z = bedSpawn.getZ();
        }
        
        // 统计数据
        data.statistics = new StatisticsData();
        try {
            data.statistics.playTime = player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 20; // 转换为秒
            data.statistics.deaths = player.getStatistic(org.bukkit.Statistic.DEATHS);
            data.statistics.mobKills = player.getStatistic(org.bukkit.Statistic.MOB_KILLS);
            data.statistics.playerKills = player.getStatistic(org.bukkit.Statistic.PLAYER_KILLS);
            data.statistics.timeSinceRest = player.getStatistic(org.bukkit.Statistic.TIME_SINCE_REST) / 20; // 转换为秒
            data.statistics.damageTaken = player.getStatistic(org.bukkit.Statistic.DAMAGE_TAKEN) / 10.0; // 转换为心
            data.statistics.damageDealt = player.getStatistic(org.bukkit.Statistic.DAMAGE_DEALT) / 10.0; // 转换为心
        } catch (Exception e) {
            logger.warn("获取玩家统计数据时出错: {}", player.getName(), e);
        }
        
        return data;
    }
    
    /**
     * 收集离线玩家的基本数据
     */
    private PlayerData collectOfflinePlayerData(OfflinePlayer player) {
        PlayerData data = new PlayerData();
        
        // 基本信息
        data.playerName = player.getName();
        data.uuid = player.getUniqueId().toString();
        data.isOnline = false;
        data.hasPlayedBefore = player.hasPlayedBefore();
        data.firstPlayed = player.getFirstPlayed();
        data.lastPlayed = player.getLastPlayed();
        data.lastLogin = player.getLastPlayed(); // 使用 lastPlayed 代替 lastLogin
        
        // 离线玩家只能获取有限的信息
        data.gameMode = "UNKNOWN";
        
        // 出生点
        Location bedSpawn = player.getBedSpawnLocation();
        if (bedSpawn != null) {
            data.bedSpawnLocation = new LocationData();
            data.bedSpawnLocation.world = bedSpawn.getWorld() != null ? bedSpawn.getWorld().getName() : "unknown";
            data.bedSpawnLocation.x = bedSpawn.getX();
            data.bedSpawnLocation.y = bedSpawn.getY();
            data.bedSpawnLocation.z = bedSpawn.getZ();
        }
        
        return data;
    }
    
    /**
     * 收集背包数据
     */
    private InventoryData collectInventoryData(PlayerInventory inventory) {
        InventoryData invData = new InventoryData();
        
        // 主背包物品
        invData.mainInventory = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && !item.getType().isAir()) {
                invData.mainInventory.add(convertItemStack(item, i));
            }
        }
        
        // 装备栏
        invData.armor = new ArrayList<>();
        ItemStack[] armorContents = inventory.getArmorContents();
        for (int i = 0; i < armorContents.length; i++) {
            ItemStack item = armorContents[i];
            if (item != null && !item.getType().isAir()) {
                ItemData itemData = convertItemStack(item, -1);
                itemData.slot = getArmorSlotName(i);
                invData.armor.add(itemData);
            }
        }
        
        // 主手和副手
        ItemStack mainHand = inventory.getItemInMainHand();
        if (!mainHand.getType().isAir()) {
            invData.mainHand = convertItemStack(mainHand, -1);
        }
        
        ItemStack offHand = inventory.getItemInOffHand();
        if (!offHand.getType().isAir()) {
            invData.offHand = convertItemStack(offHand, -1);
        }
        
        return invData;
    }
    
    /**
     * 收集末影箱数据
     */
    private List<ItemData> collectEnderChestData(Inventory enderChest) {
        List<ItemData> items = new ArrayList<>();
        
        for (int i = 0; i < enderChest.getSize(); i++) {
            ItemStack item = enderChest.getItem(i);
            if (item != null && !item.getType().isAir()) {
                items.add(convertItemStack(item, i));
            }
        }
        
        return items;
    }
    
    /**
     * 转换ItemStack为ItemData
     */
    private ItemData convertItemStack(ItemStack item, int slot) {
        ItemData itemData = new ItemData();
        itemData.type = item.getType().name();
        itemData.amount = item.getAmount();
        // 对于有耐久度的物品，计算剩余耐久度
        if (item.getType().getMaxDurability() > 0 && item.hasItemMeta()) {
            org.bukkit.inventory.meta.Damageable damageable = (org.bukkit.inventory.meta.Damageable) item.getItemMeta();
            if (damageable != null) {
                itemData.damage = damageable.getDamage();
                itemData.maxDurability = item.getType().getMaxDurability();
            }
        }
        if (slot >= 0) {
            itemData.slot = String.valueOf(slot);
        }
        
        // 物品显示名称
        if (item.hasItemMeta()) {
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                itemData.displayName = meta.getDisplayName();
            }
        }
        
        // 附魔
        if (!item.getEnchantments().isEmpty()) {
            itemData.enchantments = new HashMap<>();
            item.getEnchantments().forEach((enchantment, level) -> {
                itemData.enchantments.put(enchantment.getKey().getKey(), level);
            });
        }
        
        return itemData;
    }
    
    /**
     * 获取装备栏位名称
     */
    private String getArmorSlotName(int index) {
        return switch (index) {
            case 0 -> "feet";
            case 1 -> "legs";
            case 2 -> "chest";
            case 3 -> "head";
            default -> "unknown";
        };
    }
    
    /**
     * 发送JSON响应
     */
    private void sendJsonResponse(HttpServletResponse response, int status, ApiResponse<?> apiResponse) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(gson.toJson(apiResponse));
        response.getWriter().flush();
    }
    
    // ==================== 数据类 ====================
    
    /**
     * 玩家完整数据
     */
    public static class PlayerData {
        // 基本信息
        public String playerName;
        public String uuid;
        public boolean isOnline;
        public boolean hasPlayedBefore;
        public long firstPlayed;
        public long lastPlayed;
        public long lastLogin;
        public String gameMode;
        
        // 位置和维度
        public LocationData location;
        public LocationData bedSpawnLocation;
        
        // 生命值和状态
        public double health;
        public double maxHealth;
        public int foodLevel;
        public float saturation;
        public float exhaustion;
        public int level;
        public float exp;
        public int totalExperience;
        
        // 空气和火焰
        public int remainingAir;
        public int maximumAir;
        public int fireTicks;
        
        // 能力
        public boolean isFlying;
        public boolean allowFlight;
        public boolean isInvulnerable;
        public boolean isSneaking;
        public boolean isSprinting;
        public boolean isSwimming;
        public boolean isGliding;
        public boolean isBlocking;
        
        // 速度
        public float walkSpeed;
        public float flySpeed;
        
        // 药水效果
        public List<PotionEffectData> potionEffects;
        
        // 物品
        public InventoryData inventory;
        public List<ItemData> enderChest;
        
        // 统计数据
        public StatisticsData statistics;
    }
    
    /**
     * 位置数据
     */
    public static class LocationData {
        public String world;
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
        public String dimension;
    }
    
    /**
     * 药水效果数据
     */
    public static class PotionEffectData {
        public String type;
        public int amplifier;
        public int duration;
        public boolean isAmbient;
        public boolean hasParticles;
        public boolean hasIcon;
    }
    
    /**
     * 背包数据
     */
    public static class InventoryData {
        public List<ItemData> mainInventory;
        public List<ItemData> armor;
        public ItemData mainHand;
        public ItemData offHand;
    }
    
    /**
     * 物品数据
     */
    public static class ItemData {
        public String type;
        public int amount;
        public int damage; // 损坏值
        public short maxDurability; // 最大耐久度
        public String slot;
        public String displayName;
        public Map<String, Integer> enchantments;
    }
    
    /**
     * 统计数据
     */
    public static class StatisticsData {
        public long playTime; // 游戏时长（秒）
        public int deaths; // 死亡次数
        public int mobKills; // 生物击杀数
        public int playerKills; // 玩家击杀数
        public long timeSinceRest; // 距离上次睡觉的时间（秒）
        public double damageTaken; // 受到的伤害（心）
        public double damageDealt; // 造成的伤害（心）
    }
}
