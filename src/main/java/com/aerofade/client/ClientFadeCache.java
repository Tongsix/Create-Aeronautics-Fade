package com.aerofade.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端保存的「每个物理结构当前虚化进度」。
 *
 * <p>这是一个纯渲染侧的数据，不参与任何逻辑判定 ——
 * 真正决定删不删的是服务端的 {@code FadeManager}。
 *
 * <p>性能考虑：{@link #getAlpha(UUID)} 会被渲染循环每帧调用很多次，
 * 所以第一行做了「一个都没在虚化就直接返回 1.0」的短路。
 * 绝大多数存档里同时在虚化的结构是 0 个，所以这条短路基本吃掉了全部开销。
 */
public final class ClientFadeCache {

    private static final Map<UUID, Float> PROGRESS = new HashMap<>();

    /** 由网络包调用。{@code progress < 0} 表示清除。 */
    public static void update(UUID subLevelId, float progress) {
        if (progress <= 0.0F) {
            // 进度归零（被互动打断了）或负数（结构没了）-> 都当作「不需要虚化渲染」
            PROGRESS.remove(subLevelId);
            return;
        }
        PROGRESS.put(subLevelId, Math.min(1.0F, progress));
    }

    /**
     * 该结构当前应该使用的 alpha 值。
     *
     * @return 1.0 = 完全不透明（没有在虚化）
     */
    public static float getAlpha(UUID subLevelId) {
        if (PROGRESS.isEmpty()) {
            return 1.0F;
        }
        final Float progress = PROGRESS.get(subLevelId);
        if (progress == null) {
            return 1.0F;
        }
        final float finalAlpha = (float) com.aerofade.AeroFadeClientConfig.FINAL_ALPHA.get().doubleValue();
        // progress 0 -> 1 时，alpha 从 1.0 线性降到 finalAlpha
        return 1.0F - progress * (1.0F - finalAlpha);
    }

    /** 是否真的有任何结构正在虚化。用于渲染侧的快速判断。 */
    public static boolean isAnyFading() {
        return !PROGRESS.isEmpty();
    }

    public static void clear() {
        PROGRESS.clear();
    }

    private ClientFadeCache() {
    }
}
