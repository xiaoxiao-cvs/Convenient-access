package com.shinoyuki.accesshub.deviceauth;

import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.auth.PlayerAuthService;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.deviceauth.DeviceAuthSession.Challenge;
import com.shinoyuki.accesshub.deviceauth.net.AuthChannel;
import com.shinoyuki.accesshub.deviceauth.net.S2CChallenge;
import com.shinoyuki.accesshub.event.PlayerAuthListener;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 免密验签服务端. 全部方法在服务器主线程调用 (PlayerLoggedInEvent / PlayerTickEvent / 包处理 enqueueWork)。
 *
 * 与密码登录共用唯一汇合点 PlayerAuthService.markAuthed: 验签通过即解冻, 与 AuthCommand.doLogin
 * 成功分支同构。fail-closed: 查不到公钥 / 验签不过 / 任何异常一律不 markAuthed, 静默回退密码登录, 不踢人。
 *
 * 触发挑战有三条路径, 互为补位 (握手时序不可靠是本模块最主要的线上故障源):
 *  1. 进服 (onPlayerJoin): 通道已就绪时立即发, 最快;
 *  2. 客户端 hello (onClientHello): 客户端进入 PLAY 且本地玩家已创建后主动宣告, 不依赖服务端的通道视图,
 *     是 Connector/兼容层下唯一可靠的路径;
 *  3. 每 tick 复检 (tick): 通道晚就绪时补发, 挑战超时后重发, 直到 MAX_AUTH_ATTEMPTS 用尽。
 */
public final class DeviceAuthServer {

    private static final Logger logger = LoggerFactory.getLogger(DeviceAuthServer.class);

    private final DeviceKeyDao deviceKeyDao;
    private final PlayerAuthService authService;
    private final AccessHubConfig config;

    /** 每玩家握手会话 (挑战队列 + 重试计数). 进服/hello 建, 退服清。 */
    private final Map<UUID, DeviceAuthSession> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public DeviceAuthServer(DeviceKeyDao deviceKeyDao, PlayerAuthService authService, AccessHubConfig config) {
        this.deviceKeyDao = deviceKeyDao;
        this.authService = authService;
        this.config = config;
    }

    /** 进服: 判定免密资格并尝试首发挑战 (best-effort). 失败由既有密码提示兜底。 */
    public void onPlayerJoin(ServerPlayer player) {
        // hello 有可能先于 PlayerLoggedInEvent 到达 (本机/低延迟连接), 那时会话已由 hello 建好, 这里复用
        DeviceAuthSession session = sessions.computeIfAbsent(player.getUUID(), k -> new DeviceAuthSession());
        session.markJoined(evaluateEligible(player));
        tryIssueAuth(player, session);
    }

    /**
     * 客户端进入 PLAY 且本地玩家已创建后的主动宣告 (C2SHello)。
     *
     * 这是唯一不依赖服务端 isRemotePresent 时序的触发路径: 收到 hello 就确证对端装了本 mod 且已能处理挑战。
     */
    public void onClientHello(ServerPlayer player) {
        DeviceAuthSession session = sessions.computeIfAbsent(player.getUUID(), k -> new DeviceAuthSession());
        if (!session.markHello()) {
            return; // 重复 hello: 忽略
        }
        logger.info("[免密] {} 客户端免密通道已就绪 (收到 hello)", player.getGameProfile().getName());
        tryIssueAuth(player, session);
    }

    /**
     * 每 tick 复检 (仅未认证玩家, 由 PlayerAuthListener.onPlayerTick 驱动)。
     *
     * 做两件事: 清理超时挑战并落日志; 通道刚就绪 / 上一条挑战超时时补发。开销为一次 map 查询加几个布尔判断。
     */
    public void tick(ServerPlayer player) {
        DeviceAuthSession session = sessions.get(player.getUUID());
        if (session == null) {
            return;
        }
        int expired = session.expire(System.currentTimeMillis(), challengeTimeoutMillis());
        if (expired > 0) {
            logger.info("[免密] {} 挑战超时未收到应答 (宽限 {}s, 已发 {} 次): 客户端可能仍在加载, 或本机密钥不可用",
                    player.getGameProfile().getName(), config.getDeviceAuthChallengeTimeoutSeconds(),
                    session.authAttempts());
        }
        tryIssueAuth(player, session);
    }

    /** /enroll 授权通过后发 ENROLL 挑战. codeId>=0 表示成功后消费该注册码。 */
    public void beginEnroll(ServerPlayer player, long codeId) {
        DeviceAuthSession session = sessions.computeIfAbsent(player.getUUID(), k -> new DeviceAuthSession());
        if (!clientReady(player, session)) {
            player.sendSystemMessage(Component.literal("§c请用装了本 mod 的客户端登记免密"));
            return;
        }
        sendChallenge(player, session, DeviceCrypto.PHASE_ENROLL, codeId);
    }

    /** 玩家退出: 清理握手会话。 */
    public void clear(UUID uuid) {
        sessions.remove(uuid);
    }

    /**
     * 该玩家本次进服是否具备免密资格。
     *
     * 每个跳过分支落 INFO: 免密是 best-effort 静默回退, 不记日志则线上"为何没免密"无从排查。
     */
    private boolean evaluateEligible(ServerPlayer player) {
        String username = player.getGameProfile().getName();
        if (!config.isDeviceAuthEnabled()) {
            logger.info("[免密] {} 跳过: device-auth.enabled=false (免密总开关关闭)", username);
            return false;
        }
        if (authService.isAuthed(player.getUUID())) {
            return false; // 已认证 (如密码已登录), 无需挑战, 属正常不记
        }
        try {
            if (deviceKeyDao.findPublicKey(username).isEmpty()) {
                logger.info("[免密] {} 跳过: 未登记设备, 走密码登录 (需先 /enroll)", username);
                return false;
            }
        } catch (Exception e) {
            logger.warn("[免密] {} 查询设备公钥失败, 回退密码", username, e);
            return false; // fail-closed: 查不到就不挑战
        }
        return true;
    }

    /** 发 AUTH 挑战的唯一入口 (进服 / hello / tick 复检共用)。 */
    private void tryIssueAuth(ServerPlayer player, DeviceAuthSession session) {
        String username = player.getGameProfile().getName();
        if (session.authAttemptsExhausted()) {
            logger.info("[免密] {} 连续 {} 次挑战均无有效应答, 停止免密, 请 /login 密码登录",
                    username, DeviceAuthSession.MAX_AUTH_ATTEMPTS);
            session.finishAuth();
            return;
        }
        if (!session.canIssueAuth()) {
            return;
        }
        if (authService.isAuthed(player.getUUID())) {
            session.finishAuth(); // 等待期间已用密码登录, 不必再挑战
            return;
        }
        if (!clientReady(player, session)) {
            if (session.shouldLogWaitingChannel()) {
                logger.info("[免密] {} 客户端免密通道尚未就绪, 继续等待 hello 或通道协商 "
                        + "(没装本 mod 则一直等不到, 走密码登录)", username);
            }
            return;
        }
        int attempt = sendChallenge(player, session, DeviceCrypto.PHASE_AUTH, -1L);
        logger.info("[免密] {} 已发 AUTH 挑战 (第 {}/{} 次, serverId={}, 宽限 {}s)",
                username, attempt, DeviceAuthSession.MAX_AUTH_ATTEMPTS, config.getServerInstanceId(),
                config.getDeviceAuthChallengeTimeoutSeconds());
    }

    /**
     * 客户端是否可以收挑战。
     *
     * 收到过 hello 即确证装了本 mod; 否则退回服务端侧的通道登记判定 —— 后者在 Connector 下可能长时间
     * 甚至永远为假, 所以它只是加速路径, 不再是唯一依据 (老客户端不发 hello, 仍靠它)。
     */
    private boolean clientReady(ServerPlayer player, DeviceAuthSession session) {
        return session.helloSeen() || AuthChannel.clientHasMod(player);
    }

    private int sendChallenge(ServerPlayer player, DeviceAuthSession session, String phase, long codeId) {
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        int attempt = session.issue(phase, nonce, System.currentTimeMillis(), codeId);
        AuthChannel.sendTo(player, new S2CChallenge(phase, nonce, config.getServerInstanceId()));
        return attempt;
    }

    private long challengeTimeoutMillis() {
        return config.getDeviceAuthChallengeTimeoutSeconds() * 1000L;
    }

    /** 收客户端响应 (服务器主线程, sender 为权威身份)。 */
    public void handleResponse(ServerPlayer sender, String phase, byte[] publicKey, byte[] signature) {
        final String username = sender.getGameProfile().getName();
        final UUID uuid = sender.getUUID();
        DeviceAuthSession session = sessions.get(uuid);
        if (session == null) {
            logger.info("[免密] {} 应答被丢弃: 无握手会话 (phase={})", username, phase);
            return;
        }
        // 一次性取走该阶段全部挑战 (防重放). 重试期间可能有多条在飞, 逐条试签, 任一条通过即算数
        List<Challenge> candidates = session.consume(phase, System.currentTimeMillis(), challengeTimeoutMillis());
        if (candidates.isEmpty()) {
            if (authService.isAuthed(uuid)) {
                return; // 多条挑战时后到的那条应答, 玩家已认证, 属正常不记
            }
            logger.info("[免密] {} 应答被丢弃: 无匹配挑战或全部超时 (phase={}, 宽限 {}s)",
                    username, phase, config.getDeviceAuthChallengeTimeoutSeconds());
            return;
        }
        final String serverId = config.getServerInstanceId();
        final MinecraftServer server = sender.getServer();
        if (server == null) {
            return;
        }
        final boolean enroll = DeviceCrypto.PHASE_ENROLL.equals(phase);
        // DB 读/写 + 验签下沉到异步线程池 (与 AuthCommand.doLogin 范式一致, 绝不在主线程做阻塞 DB,
        // 尤其 touchLastUsed/upsert 这类写会抢 WAL 单写者锁, 繁忙库上可等满 busy_timeout 冻整服)。
        // 回主线程只做 markAuthed + 解冻 + 发包, 并复查玩家仍在线 (异步期间可能掉线)。
        CompletableFuture
                .supplyAsync(() -> enroll
                        ? verifyEnroll(username, serverId, candidates, publicKey, signature)
                        : verifyAuth(username, serverId, candidates, signature))
                .thenAccept(outcome -> server.execute(() -> {
                    // 成功即终结; AUTH 失败也终结 —— 验签不过说明客户端密钥与库内公钥不配, 再重发是同样结果,
                    // 停手交给密码登录。ENROLL 失败不影响 AUTH 重试节奏, 故不动。
                    if (outcome.success || !enroll) {
                        session.finishAuth();
                    }
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

    /**
     * 异步线程: 验签 AUTH (查公钥 + 逐条 nonce 试签 + 审计写). 不碰主线程对象, 结果回主线程 apply。
     *
     * 逐条试签是重发机制的配套: 客户端对任意一条仍在有效期内的挑战作答都算数, 否则重发反而会把
     * 慢客户端的合法应答判成验签失败。公钥只查一次, 试签本身是纯 CPU 的 Ed25519 verify。
     */
    private Outcome verifyAuth(String username, String serverId, List<Challenge> candidates, byte[] signature) {
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
        final PublicKey publicKey;
        try {
            publicKey = DeviceCrypto.decodePublic(pub);
        } catch (RuntimeException e) {
            return new Outcome(false, "免密公钥损坏, 请 /login 后重新 /enroll");
        }
        for (Challenge c : candidates) {
            byte[] payload = DeviceCrypto.challengePayload(DeviceCrypto.PHASE_AUTH, serverId, c.nonce, username, pub);
            if (!DeviceCrypto.verify(publicKey, payload, signature)) {
                continue;
            }
            try {
                deviceKeyDao.touchLastUsed(username); // 审计写: 留在异步, 它才是真正可能抢 WAL 写锁的点
            } catch (Exception ignore) {
                // 仅审计字段, 失败不影响认证结论
            }
            logger.info("玩家免密登录成功: {}", username);
            return new Outcome(true, "免密登录成功");
        }
        // 验签不过最可能: 客户端密钥与服务端登记的公钥不配 (换机 / 重装 / serverId 变化后客户端用了新密钥文件)
        logger.info("[免密] {} 验签失败 (serverId={}, 已试 {} 条在飞挑战), 回退密码登录",
                username, serverId, candidates.size());
        return new Outcome(false, "免密验签失败, 请 /login");
    }

    /** 异步线程: 验签 ENROLL (PoP 自验签) + 写公钥 + 消费码。 */
    private Outcome verifyEnroll(String username, String serverId, List<Challenge> candidates,
                                 byte[] publicKey, byte[] signature) {
        if (publicKey == null || publicKey.length == 0) {
            return new Outcome(false, "登记失败: 缺少设备公钥");
        }
        final PublicKey pub;
        try {
            pub = DeviceCrypto.decodePublic(publicKey);
        } catch (RuntimeException e) {
            return new Outcome(false, "登记失败: 公钥无效");
        }
        // PoP: 用提交的公钥自验签, 证明客户端确实持有对应私钥 (防塞入第三方公钥冒登记)
        Challenge matched = null;
        for (Challenge c : candidates) {
            byte[] payload = DeviceCrypto.challengePayload(
                    DeviceCrypto.PHASE_ENROLL, serverId, c.nonce, username, publicKey);
            if (DeviceCrypto.verify(pub, payload, signature)) {
                matched = c;
                break;
            }
        }
        if (matched == null) {
            return new Outcome(false, "登记失败: 自验签不通过");
        }
        try {
            deviceKeyDao.upsert(username, DeviceCrypto.toBase64(publicKey));
        } catch (Exception e) {
            logger.error("写设备公钥失败: {}", username, e);
            return new Outcome(false, "登记失败 (系统繁忙)");
        }
        if (matched.codeId >= 0) {
            authService.consumeRegistrationCode(matched.codeId); // enroll 成功才消费码
        }
        logger.info("玩家登记设备并免密解冻: {}", username);
        return new Outcome(true, "设备登记成功, 此后进服免密");
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
}
