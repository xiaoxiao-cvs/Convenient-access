package com.shinoyuki.accesshub.deviceauth;

import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.auth.PlayerAuthService;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.deviceauth.net.AuthChannel;
import com.shinoyuki.accesshub.deviceauth.net.S2CChallenge;
import com.shinoyuki.accesshub.event.PlayerAuthListener;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 免密验签服务端. 全部方法在服务器主线程调用 (PlayerLoggedInEvent / 包处理 enqueueWork)。
 *
 * 与密码登录共用唯一汇合点 PlayerAuthService.markAuthed: 验签通过即解冻, 与 AuthCommand.doLogin
 * 成功分支同构。fail-closed: 查不到公钥 / 验签不过 / 任何异常一律不 markAuthed, 静默回退密码登录, 不踢人。
 */
public final class DeviceAuthServer {

    private static final Logger logger = LoggerFactory.getLogger(DeviceAuthServer.class);

    private final DeviceKeyDao deviceKeyDao;
    private final PlayerAuthService authService;
    private final AccessHubConfig config;

    /** 每玩家待应答挑战 (单条, 新挑战覆盖, 应答即消费防重放). 退服清理。 */
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public DeviceAuthServer(DeviceKeyDao deviceKeyDao, PlayerAuthService authService, AccessHubConfig config) {
        this.deviceKeyDao = deviceKeyDao;
        this.authService = authService;
        this.config = config;
    }

    /** 进服: 有公钥 + 客户端在场 -> 发 AUTH 挑战 (best-effort). 失败由既有密码提示兜底。
     *  每个跳过分支落 INFO: 免密是 best-effort 静默回退, 不记日志则线上"为何没免密"无从排查。 */
    public void maybeChallengeOnJoin(ServerPlayer player) {
        String username = player.getGameProfile().getName();
        if (!config.isDeviceAuthEnabled()) {
            logger.info("[免密] {} 跳过: device-auth.enabled=false (免密总开关关闭)", username);
            return;
        }
        if (authService.isAuthed(player.getUUID())) {
            return; // 已认证 (如密码已登录), 无需挑战, 属正常不记
        }
        try {
            if (deviceKeyDao.findPublicKey(username).isEmpty()) {
                logger.info("[免密] {} 跳过: 未登记设备, 走密码登录 (需先 /enroll)", username);
                return; // 没登记设备 -> 走密码
            }
        } catch (Exception e) {
            logger.warn("[免密] {} 查询设备公钥失败, 回退密码", username, e);
            return; // fail-closed: 查不到就不挑战
        }
        if (!AuthChannel.clientHasMod(player)) {
            logger.info("[免密] {} 跳过: 客户端未注册免密通道 (没装本 mod, 或 Connector 下通道协商尚未就绪)", username);
            return; // 没装本 mod -> 走密码
        }
        logger.info("[免密] {} 已发 AUTH 挑战 (serverId={})", username, config.getServerInstanceId());
        sendChallenge(player, DeviceCrypto.PHASE_AUTH, -1L);
    }

    /** /enroll 授权通过后发 ENROLL 挑战. codeId>=0 表示成功后消费该注册码。 */
    public void beginEnroll(ServerPlayer player, long codeId) {
        if (!AuthChannel.clientHasMod(player)) {
            player.sendSystemMessage(Component.literal("§c请用装了本 mod 的客户端登记免密"));
            return;
        }
        sendChallenge(player, DeviceCrypto.PHASE_ENROLL, codeId);
    }

    private void sendChallenge(ServerPlayer player, String phase, long codeId) {
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        pending.put(player.getUUID(), new Pending(phase, nonce, System.currentTimeMillis(), codeId));
        AuthChannel.sendTo(player, new S2CChallenge(phase, nonce, config.getServerInstanceId()));
    }

    /** 收客户端响应 (服务器主线程, sender 为权威身份)。 */
    public void handleResponse(ServerPlayer sender, String phase, byte[] publicKey, byte[] signature) {
        final String username = sender.getGameProfile().getName();
        Pending p = pending.remove(sender.getUUID()); // 一次性消费, 防重放
        if (p == null || !p.phase.equals(phase)) {
            logger.info("[免密] {} 响应被丢弃: 无匹配挑战 (收到 phase={}, 待应答={})",
                    username, phase, p == null ? "无" : p.phase);
            return; // 无挑战 / 阶段不符
        }
        if (System.currentTimeMillis() - p.signedAt > config.getDeviceAuthChallengeTimeoutSeconds() * 1000L) {
            logger.info("[免密] {} 响应被丢弃: 挑战超时 (宽限 {}s, 网络往返过慢)",
                    username, config.getDeviceAuthChallengeTimeoutSeconds());
            return; // 超时 nonce -> 拒绝
        }
        final String serverId = config.getServerInstanceId();
        final MinecraftServer server = sender.getServer();
        if (server == null) {
            return;
        }
        final UUID uuid = sender.getUUID();
        final boolean enroll = DeviceCrypto.PHASE_ENROLL.equals(phase);
        // DB 读/写 + 验签下沉到异步线程池 (与 AuthCommand.doLogin 范式一致, 绝不在主线程做阻塞 DB,
        // 尤其 touchLastUsed/upsert 这类写会抢 WAL 单写者锁, 繁忙库上可等满 busy_timeout 冻整服)。
        // 回主线程只做 markAuthed + 解冻 + 发包, 并复查玩家仍在线 (异步期间可能掉线)。
        CompletableFuture
                .supplyAsync(() -> enroll
                        ? verifyEnroll(username, serverId, p, publicKey, signature)
                        : verifyAuth(username, serverId, p, signature))
                .thenAccept(outcome -> server.execute(() -> {
                    ServerPlayer online = server.getPlayerList().getPlayer(uuid);
                    if (online == null) {
                        return; // 异步期间掉线, 不发包
                    }
                    if (outcome.success) {
                        authService.markAuthed(uuid);
                        PlayerAuthListener.liftRestrictions(online); // 立即解除失明/缓慢/无敌, 不留残留
                    }
                    online.sendSystemMessage(Component.literal((outcome.success ? "§a" : "§c") + outcome.message));
                }))
                .exceptionally(t -> {
                    logger.warn("免密响应处理异常: {}", username, t);
                    return null;
                });
    }

    /** 异步线程: 验签 AUTH (查公钥 + 验签 + 审计写). 不碰主线程对象, 结果回主线程 apply。 */
    private Outcome verifyAuth(String username, String serverId, Pending p, byte[] signature) {
        final String pubB64;
        try {
            Optional<String> opt = deviceKeyDao.findPublicKey(username);
            if (opt.isEmpty()) {
                return new Outcome(false, "未登记设备, 请 /login 密码登录");
            }
            pubB64 = opt.get();
        } catch (Exception e) {
            logger.warn("免密查公钥失败: {}", username, e);
            return new Outcome(false, "免密暂不可用, 请 /login");
        }
        byte[] pub = DeviceCrypto.fromBase64(pubB64);
        byte[] payload = DeviceCrypto.challengePayload(DeviceCrypto.PHASE_AUTH, serverId, p.nonce, username, pub);
        final PublicKey publicKey;
        try {
            publicKey = DeviceCrypto.decodePublic(pub);
        } catch (RuntimeException e) {
            return new Outcome(false, "免密公钥损坏, 请 /login 后重新 /enroll");
        }
        if (!DeviceCrypto.verify(publicKey, payload, signature)) {
            // 验签不过最可能: 客户端密钥与服务端登记的公钥不配 (换机 / 重装 / serverId 变化后客户端用了新密钥文件)
            logger.info("[免密] {} 验签失败 (serverId={}), 回退密码登录", username, serverId);
            return new Outcome(false, "免密验签失败, 请 /login");
        }
        try {
            deviceKeyDao.touchLastUsed(username); // 审计写: 留在异步, 它才是真正可能抢 WAL 写锁的点
        } catch (Exception ignore) {
            // 仅审计字段, 失败不影响认证结论
        }
        logger.info("玩家免密登录成功: {}", username);
        return new Outcome(true, "免密登录成功");
    }

    /** 异步线程: 验签 ENROLL (PoP 自验签) + 写公钥 + 消费码。 */
    private Outcome verifyEnroll(String username, String serverId, Pending p, byte[] publicKey, byte[] signature) {
        if (publicKey == null || publicKey.length == 0) {
            return new Outcome(false, "登记失败: 缺少设备公钥");
        }
        // PoP: 用提交的公钥自验签, 证明客户端确实持有对应私钥 (防塞入第三方公钥冒登记)
        byte[] payload = DeviceCrypto.challengePayload(DeviceCrypto.PHASE_ENROLL, serverId, p.nonce, username, publicKey);
        final PublicKey pub;
        try {
            pub = DeviceCrypto.decodePublic(publicKey);
        } catch (RuntimeException e) {
            return new Outcome(false, "登记失败: 公钥无效");
        }
        if (!DeviceCrypto.verify(pub, payload, signature)) {
            return new Outcome(false, "登记失败: 自验签不通过");
        }
        try {
            deviceKeyDao.upsert(username, DeviceCrypto.toBase64(publicKey));
        } catch (Exception e) {
            logger.error("写设备公钥失败: {}", username, e);
            return new Outcome(false, "登记失败 (系统繁忙)");
        }
        if (p.codeId >= 0) {
            authService.consumeRegistrationCode(p.codeId); // enroll 成功才消费码
        }
        logger.info("玩家登记设备并免密解冻: {}", username);
        return new Outcome(true, "设备登记成功, 此后进服免密");
    }

    /** 玩家退出: 清理待应答挑战。 */
    public void clear(UUID uuid) {
        pending.remove(uuid);
    }

    /** 异步验签结果: 是否认证成功 + 回给玩家的消息。markAuthed/解冻由主线程据此 apply。 */
    private static final class Outcome {
        final boolean success;
        final String message;

        Outcome(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private static final class Pending {
        final String phase;
        final byte[] nonce;
        final long signedAt;
        final long codeId;

        Pending(String phase, byte[] nonce, long signedAt, long codeId) {
            this.phase = phase;
            this.nonce = nonce;
            this.signedAt = signedAt;
            this.codeId = codeId;
        }
    }
}
