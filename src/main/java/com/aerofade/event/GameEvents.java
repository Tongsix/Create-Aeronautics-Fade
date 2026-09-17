package com.aerofade.event;

import com.aerofade.AeroFade;
import com.aerofade.command.FadeCommand;
import com.aerofade.fade.FadeManager;
import com.aerofade.fade.MixinSelfCheck;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** 服务端游戏事件。 */
public final class GameEvents {

    /** 自检只做一次。 */
    private static boolean selfCheckDone;

    /**
     * 每个维度每刻都会被调用。
     *
     * <p>{@link FadeManager#tick} 内部自己做了节流（默认每 20 刻真正跑一次），
     * 所以这里每刻调用是没问题的。
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!selfCheckDone) {
            selfCheckDone = true;
            runSelfCheck();
        }

        if (event.getLevel() instanceof final ServerLevel serverLevel) {
            FadeManager.get(serverLevel).tick(serverLevel);
        }
    }

    /**
     * 主动确认 Mixin 到底有没有注入成功。
     *
     * <p>因为 mixin 配置里是 {@code defaultRequire = 0}（宁可降级也不崩服），
     * 注入失败是<b>完全静默</b>的。这里把结果明确写进日志，
     * 免得出现「模组在跑但某个功能悄悄失效」这种最难查的情况。
     */
    private static void runSelfCheck() {
        if (MixinSelfCheck.isServerPlotMixinApplied()) {
            // 成功走 DEBUG：安静的模组不该在每次开服时刷存在感。
            // 想确认的时候用 /fade status 看，或者临时打开 debug 日志。
            AeroFade.LOGGER.debug("[aerofade] 自检通过：ServerLevelPlotMixin 已注入 ServerLevelPlot。");
        } else {
            AeroFade.LOGGER.warn("[aerofade] 自检失败：ServerLevelPlotMixin 没有注入成功。"
                    + "本模组仍会按时间虚化并删除结构，但「方块被放置/破坏」将不再算作互动。"
                    + "通常意味着 Sable 的 ServerLevelPlot#load / #onBlockChange 签名变了，需要更新本模组。");
        }
    }

    /** 注册 {@code /fade} 调试指令。 */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        FadeCommand.register(event.getDispatcher());
    }

    /**
     * 服务器停了就把内存里的状态清空。
     *
     * <p>必须清 —— 因为 {@code FadeManager} 是按「维度」缓存的静态表，
     * 不清的话退出到主菜单再进另一个存档，会把上一个存档的计时器带过去。
     * 真正的持久化数据在 {@code FadeSavedData} 里，不受影响。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        FadeManager.clearAll();
        selfCheckDone = false;
    }

    private GameEvents() {
    }
}
