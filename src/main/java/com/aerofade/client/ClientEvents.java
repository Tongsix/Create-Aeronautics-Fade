package com.aerofade.client;

import com.aerofade.AeroFade;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** 客户端游戏事件。带 {@code value = Dist.CLIENT} 的类不会被专职服务端加载。 */
@EventBusSubscriber(modid = AeroFade.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {

    private static boolean selfCheckDone;

    /** 断开连接时清空缓存，避免把上一个服务器的虚化状态带到下一个服务器。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientFadeCache.clear();
    }

    /**
     * 进游戏后第一次 tick 时做一次 Mixin 自检，把结果写进日志。
     *
     * <p>放在 tick 而不是构造期，是因为这时候目标类才一定已经可以被安全加载。
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (selfCheckDone) {
            return;
        }
        selfCheckDone = true;
        ClientMixinSelfCheck.logResult();
    }

    private ClientEvents() {
    }
}
