package com.aerofade.network;

import java.util.UUID;

import com.aerofade.AeroFadeConfig;
import com.aerofade.client.ClientFadeCache;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 服务端 -> 客户端的虚化进度同步。
 *
 * <p>注册用的是 {@code optional()}：客户端没装本模组也能进服，
 * 只是看不到透明效果（结构仍然会正常被删掉）。
 * 对一个「减轻服务端压力」的模组来说这是很重要的属性。
 */
public final class AeroFadeNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static void register(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        registrar.playToClient(FadeSyncPayload.TYPE, FadeSyncPayload.STREAM_CODEC, AeroFadeNetwork::onFadeSync);
    }

    /**
     * 只在客户端执行。
     *
     * <p>这里引用 {@link ClientFadeCache} 是安全的：整个方法只会在客户端被调用，
     * JVM 也是在第一次执行到这里时才去解析那个类，所以专职服务端不会因此崩。
     */
    private static void onFadeSync(FadeSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientFadeCache.update(payload.subLevelId(), payload.progress()));
    }

    /** 把一个结构的虚化进度发给它周围半径 {@code misc.syncRadius} 内的玩家。 */
    public static void sendProgress(ServerLevel level, ServerSubLevel sub, float progress) {
        final Pose3dc pose = sub.logicalPose();
        final var position = pose.position();

        PacketDistributor.sendToPlayersNear(
                level,
                null,
                position.x(), position.y(), position.z(),
                AeroFadeConfig.SYNC_RADIUS.get(),
                new FadeSyncPayload(sub.getUniqueId(), progress));
    }

    /** 结构已经不存在了，让客户端把渲染状态清掉。 */
    public static void sendClear(ServerLevel level, UUID subLevelId) {
        PacketDistributor.sendToPlayersInDimension(
                level,
                new FadeSyncPayload(subLevelId, FadeSyncPayload.CLEAR));
    }

    private AeroFadeNetwork() {
    }
}
