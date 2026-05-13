package com.shinoyuki.accesshub;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(AccessHubMod.MOD_ID)
public final class AccessHubMod {
    public static final String MOD_ID = "accesshub";

    private static final Logger LOGGER = LogUtils.getLogger();

    public AccessHubMod() {
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("AccessHub v{} loading on Forge 1.20.1", "0.2.0");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // v2 阶段占位: 后续接管旧 ConvenientAccessPlugin.onEnable 的初始化职责
        // (Database -> Cache -> Auth -> WhitelistManager -> HttpServer)
        LOGGER.info("AccessHub server-starting hook fired");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // v2 阶段占位: 后续接管 onDisable 的关闭职责 (HttpServer.stop, executor shutdown 等)
        LOGGER.info("AccessHub server-stopping hook fired");
    }
}
