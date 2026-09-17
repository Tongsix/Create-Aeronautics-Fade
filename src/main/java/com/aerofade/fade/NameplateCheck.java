package com.aerofade.fade;

import com.aerofade.AeroFadeConfig;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import com.aerofade.AeroFade;
import dev.simulated_team.simulated.content.blocks.nameplate.NameplateBlockEntity;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import java.util.Collection;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 判断一个物理结构是否「被铭牌命名」。
 *
 * <h2>原版机制（已核对 Simulated 的源码与字节码）</h2>
 * Simulated 的铭牌方块 {@code NameplateBlockEntity} 在玩家编辑名字时会调用
 * {@code SubLevel.setName(名字)}，也就是说<b>名字是写在子维度对象上的</b>。
 * 所以最便宜的判定就是 {@code subLevel.getName() != null && !isBlank()}。
 *
 * <p>另外有两个细节：
 * <ul>
 *   <li>{@code waxed}（上蜡）的铭牌不会把名字写进子维度；</li>
 *   <li><b>铭牌被破坏时不会清空子维度的名字</b> —— 会留下一个「幽灵名字」。</li>
 * </ul>
 * 第二个细节会导致一个很反直觉的现象：玩家把铭牌敲了，结构却永远不会虚化。
 * 所以本类提供了 {@code verifyNameplateStillExists}（默认开启）：
 * 定期扫描结构里是否真的还存在一个带名字的铭牌方块。
 */
public final class NameplateCheck {

    /** 「扫描拿不到数据」的警告每个会话只报一次，避免刷屏。 */
    private static boolean warnedScanUnavailable;

    /**
     * @param sub       要判断的结构
     * @param state     它的运行时状态（用来缓存扫描结果）
     * @param gameTime  当前游戏刻
     * @return true 表示「被命名」，也就是永不虚化
     */
    public static boolean isNamed(ServerSubLevel sub, FadeState state, long gameTime) {
        final String name = sub.getName();

        if (name == null || name.isBlank()) {
            // 名字是空的 —— 没有任何歧义，就是没被命名
            state.named = false;
            state.namedCheckedAt = gameTime;
            return false;
        }

        if (!AeroFadeConfig.REQUIRE_NAMEPLATE_BLOCK.get()) {
            // 省事模式：信任子维度上的名字
            state.named = true;
            state.namedCheckedAt = gameTime;
            return true;
        }

        final long interval = AeroFadeConfig.NAMEPLATE_SCAN_INTERVAL_TICKS.get();
        if (state.namedCheckedAt != Long.MIN_VALUE && gameTime - state.namedCheckedAt < interval) {
            // 缓存还有效
            return state.named;
        }

        state.namedCheckedAt = gameTime;
        state.named = hasNamedNameplate(sub);
        return state.named;
    }

    /**
     * {@link #isNamed} 的「只读」版本，专门给 {@code /fade list} 这类查询用。
     *
     * <p>区别只有一点：它在缓存失效时<b>不会回写缓存</b>，
     * 也不会因为一次查询就打乱真正的检查节奏。
     */
    public static boolean isNamedForQuery(ServerSubLevel sub, FadeState state, long gameTime) {
        final String name = sub.getName();
        if (name == null || name.isBlank()) {
            return false;
        }
        if (!AeroFadeConfig.REQUIRE_NAMEPLATE_BLOCK.get()) {
            return true;
        }
        if (state != null
                && state.namedCheckedAt != Long.MIN_VALUE
                && gameTime - state.namedCheckedAt < AeroFadeConfig.NAMEPLATE_SCAN_INTERVAL_TICKS.get()) {
            return state.named;
        }
        return hasNamedNameplate(sub);
    }

    /**
     * 扫描结构所在绘图区里的方块实体，看有没有一个带非空名字的铭牌。
     *
     * <h2>⚠️ 必须用 {@code getLoadedChunks()}，不要用 {@code getChunkMin/Max}</h2>
     * 这里踩过一个非常隐蔽的坑：
     * <ul>
     *   <li>{@code LevelPlot#getChunkMin()} 返回的是 <b>plotPos &lt;&lt; logSize</b>，
     *       也就是<b>全局绘图区坐标</b>（例如 (16, 32)）；</li>
     *   <li>而 {@code LevelPlot#getChunk(pos)} 内部走 {@code getChunkHolder(pos)}，
     *       后者第一件事就是越界检查 {@code 0 &lt;= pos &lt; (1 &lt;&lt; logSize)} ——
     *       它要的是<b>局部坐标</b>。</li>
     * </ul>
     * 两者坐标空间不一致，所以拿 min/max 去 {@code getChunk()} 会<b>永远拿到 null</b>。
     * 于是扫描"一个 chunk 都没读到"，触发下面的保守兜底，
     * 结果就是「铭牌被炸掉之后仍然被当成有铭牌」。
     *
     * <p>改用 {@code getLoadedChunks()} 直接拿 {@code PlotChunkHolder#getChunk()}，
     * 完全绕开坐标空间的问题。
     */
    private static boolean hasNamedNameplate(ServerSubLevel sub) {
        final ServerLevelPlot plot = sub.getPlot();
        if (plot == null) {
            return true;
        }

        final Collection<PlotChunkHolder> holders = plot.getLoadedChunks();
        if (holders.isEmpty()) {
            // 拿不准（结构没有已加载的区块）-> 保守地认为它还被命名着。
            // 注意：这条路径正常情况**不应该**出现；一旦出现说明扫描逻辑有问题，
            // 所以这里大声报一次，不要再像以前那样把 bug 藏起来。
            warnScanUnavailableOnce(sub);
            return true;
        }

        for (final PlotChunkHolder holder : holders) {
            final LevelChunk chunk = holder.getChunk();
            if (chunk == null) {
                continue;
            }
            for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (blockEntity instanceof final NameplateBlockEntity nameplate) {
                    final String plateName = nameplate.getName();
                    if (plateName != null && !plateName.isBlank()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 扫描拿不到数据时报警 —— **每个会话只报一次**。
     *
     * <p>这条日志存在的意义：以前扫描失败会静默地返回「还被命名」，
     * 导致「铭牌被炸掉却永远豁免」这个 bug 藏了很久都没人发现。
     * 保守兜底可以留，但必须留下痕迹。
     */
    private static void warnScanUnavailableOnce(ServerSubLevel sub) {
        if (warnedScanUnavailable) {
            return;
        }
        warnedScanUnavailable = true;
        AeroFade.LOGGER.warn("[aerofade] 无法读取结构 {} 的绘图区区块，铭牌校验暂时退化为「假定仍被命名」。"
                + "如果你看到这条日志，说明 Sable 的绘图区 API 变了，请反馈。", sub.getUniqueId());
    }
    private NameplateCheck() {
    }
}
