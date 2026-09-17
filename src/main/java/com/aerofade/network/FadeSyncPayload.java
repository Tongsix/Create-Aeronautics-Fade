package com.aerofade.network;

import java.util.UUID;

import com.aerofade.AeroFade;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 -> 客户端：告诉客户端某个物理结构当前的虚化进度。
 *
 * <p>{@code progress} 的含义：
 * <ul>
 *   <li>{@code 0.0} —— 完全不虚化，正常渲染；</li>
 *   <li>{@code 0.0 < p < 1.0} —— 正在虚化，客户端按 {@code alpha = 1 - p} 渲染；</li>
 *   <li>{@link #CLEAR}（负数）—— 这个结构已经没了（被删除 / 被拆掉），客户端把记录清掉。</li>
 * </ul>
 *
 * <p>这里用「负数表示清除」而不是单开一个包，是为了少维护一个 payload 类型。
 */
public record FadeSyncPayload(UUID subLevelId, float progress) implements CustomPacketPayload {

    /** 小于 0 的 progress 都表示「清除记录」。 */
    public static final float CLEAR = -1.0F;

    public static final CustomPacketPayload.Type<FadeSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AeroFade.MOD_ID, "fade_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FadeSyncPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeUUID(payload.subLevelId());
                        buffer.writeFloat(payload.progress());
                    },
                    buffer -> new FadeSyncPayload(buffer.readUUID(), buffer.readFloat()));

    public boolean isClear() {
        return this.progress < 0.0F;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
