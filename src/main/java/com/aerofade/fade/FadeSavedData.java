package com.aerofade.fade;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 把「每个物理结构最后一次被互动的时间」存进存档。
 *
 * <p>为什么要持久化：子维度会被卸载/重新加载，服务端重启后内存里的
 * {@link FadeState} 全都没了。如果不存盘，玩家每次重启服务器都会把
 * 所有结构的虚化倒计时重置一遍，那就永远删不掉了。
 *
 * <p>用 {@code level.getGameTime()}（游戏刻）而不是真实时间戳，
 * 这样时间只会在世界真正运行时推进。
 */
public class FadeSavedData extends SavedData {

    public static final String ID = "aerofade";

    private static final String TAG_ROOT = "LastInteraction";

    private final Map<UUID, Long> lastInteraction = new HashMap<>();

    /**
     * 只构造一次。
     *
     * <p>{@link #get} 会被调用得很频繁（每个结构每次互动都会走一次），
     * 每次都 new 一个 Factory 纯属制造垃圾。用两参构造可以省掉 DataFixTypes。
     */
    private static final SavedData.Factory<FadeSavedData> FACTORY =
            new SavedData.Factory<>(FadeSavedData::new, FadeSavedData::load);

    public static FadeSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static FadeSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        final FadeSavedData data = new FadeSavedData();
        final CompoundTag map = tag.getCompound(TAG_ROOT);
        for (final String key : map.getAllKeys()) {
            try {
                data.lastInteraction.put(UUID.fromString(key), map.getLong(key));
            } catch (final IllegalArgumentException ignored) {
                // 手改坏的存档，忽略这一条
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        final CompoundTag map = new CompoundTag();
        this.lastInteraction.forEach((id, time) -> map.putLong(id.toString(), time));
        tag.put(TAG_ROOT, map);
        return tag;
    }

    public long getLastInteraction(UUID id, long fallback) {
        final Long value = this.lastInteraction.get(id);
        return value == null ? fallback : value;
    }

    public void setLastInteraction(UUID id, long time) {
        final Long previous = this.lastInteraction.put(id, time);
        if (previous == null || previous != time) {
            this.setDirty();
        }
    }

    public void remove(UUID id) {
        if (this.lastInteraction.remove(id) != null) {
            this.setDirty();
        }
    }
}
