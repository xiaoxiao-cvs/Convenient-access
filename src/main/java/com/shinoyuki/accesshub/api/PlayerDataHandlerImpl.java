package com.shinoyuki.accesshub.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * /api/v1/player 的 Forge 实现, 替代 v1 Bukkit PlayerDataApiController。
 *
 * 用 MinecraftServer / ServerPlayer API 采集在线玩家详细数据; 离线玩家经 GameProfileCache
 * 返回 name+uuid 最小集 (读 playerdata NBT 的完整离线数据留待后续)。
 *
 * 线程模型: HTTP 请求在 Jetty 线程到达, 但读取游戏状态必须在服务器主线程,
 * 故经 server.execute() 提交采集任务 + CompletableFuture 取回结果 (对应 v1 的 Bukkit scheduler 模式)。
 *
 * 注: v1 的详细统计 (playtime/deaths/击杀/伤害) 本版未移植, 其余在线数据 (位置/维度/生命/
 * 饱食/经验/能力/状态/药水/背包/末影箱) 完整覆盖。
 */
public final class PlayerDataHandlerImpl implements PlayerDataHandler {

    private static final Logger logger = LoggerFactory.getLogger(PlayerDataHandlerImpl.class);

    /** 并发查询上限, 防止 HTTP 端拖垮主线程 (对应 v1 的 Semaphore(5))。 */
    private final java.util.concurrent.Semaphore querySemaphore = new java.util.concurrent.Semaphore(5);

    private final MinecraftServer server;
    private final Gson gson = new Gson();

    public PlayerDataHandlerImpl(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void handleGetPlayerData(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String playerName = request.getParameter("name");
        boolean includeOffline = "true".equalsIgnoreCase(request.getParameter("includeOffline"));

        if (playerName == null || playerName.trim().isEmpty()) {
            send(response, 400, ApiResponse.badRequest("玩家名称不能为空, 请使用 ?name=玩家名 参数"));
            return;
        }

        try {
            if (!querySemaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                send(response, 429, ApiResponse.error("查询请求过多, 请稍后再试"));
                return;
            }
            try {
                CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
                // 提交到服务器主线程采集 (读取游戏状态必须主线程)
                server.execute(() -> {
                    try {
                        ServerPlayer online = server.getPlayerList().getPlayerByName(playerName);
                        if (online != null) {
                            future.complete(collectOnline(online));
                        } else if (includeOffline) {
                            future.complete(collectOffline(playerName));
                        } else {
                            future.completeExceptionally(new IllegalStateException(
                                    "玩家不在线 (提示: 加 ?includeOffline=true 查询离线玩家基本信息)"));
                        }
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });

                Map<String, Object> data = future.get(includeOffline ? 5 : 3, TimeUnit.SECONDS);
                if (data == null) {
                    send(response, 404, ApiResponse.notFound("玩家不存在或从未登录过服务器"));
                    return;
                }
                boolean isOnline = Boolean.TRUE.equals(data.get("online"));
                send(response, 200, ApiResponse.success(data, "成功获取玩家数据(" + (isOnline ? "在线" : "离线") + ")"));
            } finally {
                querySemaphore.release();
            }
        } catch (TimeoutException e) {
            logger.warn("获取玩家数据超时: {} (服务器主线程可能繁忙)", playerName);
            send(response, 504, ApiResponse.error("服务器繁忙, 请稍后重试"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(response, 500, ApiResponse.error("查询被中断"));
        } catch (Exception e) {
            // ExecutionException 包裹采集线程抛出的业务异常
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IllegalStateException) {
                send(response, 404, ApiResponse.notFound(cause.getMessage()));
            } else {
                logger.error("获取玩家数据失败: {}", playerName, cause);
                send(response, 500, ApiResponse.error("获取玩家数据失败: " + cause.getMessage()));
            }
        }
    }

    /** 采集在线玩家完整数据 (主线程执行)。 */
    private Map<String, Object> collectOnline(ServerPlayer player) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("playerName", player.getGameProfile().getName());
        data.put("uuid", player.getUUID().toString());
        data.put("online", true);
        data.put("gameMode", player.gameMode.getGameModeForPlayer().getName());
        data.put("ping", player.latency);

        Map<String, Object> location = new LinkedHashMap<>();
        location.put("dimension", player.level().dimension().location().toString());
        location.put("x", player.getX());
        location.put("y", player.getY());
        location.put("z", player.getZ());
        location.put("yaw", player.getYRot());
        location.put("pitch", player.getXRot());
        data.put("location", location);

        Map<String, Object> vitals = new LinkedHashMap<>();
        vitals.put("health", player.getHealth());
        vitals.put("maxHealth", player.getMaxHealth());
        vitals.put("armor", player.getArmorValue());
        FoodData food = player.getFoodData();
        vitals.put("foodLevel", food.getFoodLevel());
        vitals.put("saturation", food.getSaturationLevel());
        vitals.put("exhaustion", food.getExhaustionLevel());
        vitals.put("level", player.experienceLevel);
        vitals.put("exp", player.experienceProgress);
        vitals.put("totalExperience", player.totalExperience);
        vitals.put("remainingAir", player.getAirSupply());
        vitals.put("maximumAir", player.getMaxAirSupply());
        vitals.put("fireTicks", player.getRemainingFireTicks());
        data.put("vitals", vitals);

        Abilities abilities = player.getAbilities();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("flying", abilities.flying);
        state.put("allowFlight", abilities.mayfly);
        state.put("invulnerable", abilities.invulnerable);
        state.put("walkSpeed", abilities.getWalkingSpeed());
        state.put("flySpeed", abilities.getFlyingSpeed());
        state.put("sneaking", player.isShiftKeyDown());
        state.put("sprinting", player.isSprinting());
        state.put("swimming", player.isSwimming());
        state.put("gliding", player.isFallFlying());
        data.put("state", state);

        List<Map<String, Object>> effects = new ArrayList<>();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", String.valueOf(BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect())));
            e.put("amplifier", effect.getAmplifier());
            e.put("duration", effect.getDuration());
            e.put("ambient", effect.isAmbient());
            e.put("visible", effect.isVisible());
            e.put("showIcon", effect.showIcon());
            effects.add(e);
        }
        data.put("potionEffects", effects);

        data.put("inventory", collectInventory(player.getInventory()));
        data.put("enderChest", collectContainer(player.getEnderChestInventory()));

        return data;
    }

    /** 采集离线玩家最小信息 (GameProfileCache 仅有 name+uuid)。 */
    private Map<String, Object> collectOffline(String playerName) {
        Optional<GameProfile> profile = server.getProfileCache().get(playerName);
        if (profile.isEmpty()) {
            return null; // 触发 404
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("playerName", profile.get().getName());
        data.put("uuid", profile.get().getId().toString());
        data.put("online", false);
        data.put("note", "离线玩家仅返回基本信息; 完整数据需玩家在线");
        return data;
    }

    private Map<String, Object> collectInventory(Inventory inv) {
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> main = new ArrayList<>();
        // items 含 36 格主背包 (含快捷栏)
        for (int i = 0; i < inv.items.size(); i++) {
            Map<String, Object> item = convertItem(inv.items.get(i), String.valueOf(i));
            if (item != null) {
                main.add(item);
            }
        }
        result.put("main", main);

        String[] armorSlots = {"feet", "legs", "chest", "head"};
        List<Map<String, Object>> armor = new ArrayList<>();
        for (int i = 0; i < inv.armor.size(); i++) {
            String slot = i < armorSlots.length ? armorSlots[i] : "slot" + i;
            Map<String, Object> item = convertItem(inv.armor.get(i), slot);
            if (item != null) {
                armor.add(item);
            }
        }
        result.put("armor", armor);

        if (!inv.offhand.isEmpty()) {
            Map<String, Object> off = convertItem(inv.offhand.get(0), "offhand");
            if (off != null) {
                result.put("offHand", off);
            }
        }
        return result;
    }

    private List<Map<String, Object>> collectContainer(net.minecraft.world.Container container) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            Map<String, Object> item = convertItem(container.getItem(i), String.valueOf(i));
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /** 转换单个物品; 空气/空槽返回 null。 */
    private Map<String, Object> convertItem(ItemStack stack, String slot) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
        item.put("amount", stack.getCount());
        item.put("slot", slot);
        if (stack.isDamageableItem()) {
            item.put("damage", stack.getDamageValue());
            item.put("maxDurability", stack.getMaxDamage());
        }
        if (stack.hasCustomHoverName()) {
            item.put("displayName", stack.getHoverName().getString());
        }
        Map<?, Integer> enchantments = EnchantmentHelper.getEnchantments(stack);
        if (!enchantments.isEmpty()) {
            Map<String, Integer> ench = new LinkedHashMap<>();
            EnchantmentHelper.getEnchantments(stack).forEach((enchantment, level) ->
                    ench.put(String.valueOf(BuiltInRegistries.ENCHANTMENT.getKey(enchantment)), level));
            item.put("enchantments", ench);
        }
        return item;
    }

    private void send(HttpServletResponse response, int status, ApiResponse<?> body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(gson.toJson(body));
        response.getWriter().flush();
    }
}
