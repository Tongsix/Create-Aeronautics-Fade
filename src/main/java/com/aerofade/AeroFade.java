package com.aerofade;

import com.aerofade.event.GameEvents;
import com.aerofade.network.AeroFadeNetwork;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * 航空学：虚化 —— Aeronautics: Fade
 *
 * <p>让「没有被铭牌命名的物理结构」在长时间无人互动后逐渐变透明，最终被自动删除，
 * 以此缓解服务端和存档的性能压力。适用于清理载具残骸、无人认领的烂尾载具等。
 *
 * <p>核心逻辑在 {@link com.aerofade.fade.FadeManager}。
 */
@Mod(AeroFade.MOD_ID)
public class AeroFade {

    public static final String MOD_ID = "aerofade";

    public static final Logger LOGGER = LogUtils.getLogger();

    public AeroFade(IEventBus modEventBus, ModContainer modContainer) {
        // 服务端配置（每个存档一份：<存档>/serverconfig/aerofade-server.toml）
        modContainer.registerConfig(ModConfig.Type.SERVER, AeroFadeConfig.SPEC);
        // 客户端配置（.minecraft/config/aerofade-client.toml），只影响渲染
        modContainer.registerConfig(ModConfig.Type.CLIENT, AeroFadeClientConfig.SPEC);

        // 网络包注册
        modEventBus.addListener(AeroFadeNetwork::register);

        // 游戏事件总线（服务端 tick 等）
        NeoForge.EVENT_BUS.register(GameEvents.class);
    }
}
