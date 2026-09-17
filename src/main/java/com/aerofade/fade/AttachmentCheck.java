package com.aerofade.fade;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 判断一个物理结构「和哪些结构物理上连在一起」。
 *
 * <h2>为什么需要这个</h2>
 * <b>物理轴承会把挂上去的那部分变成另一个子维度(SubLevel)。</b>
 * 挖掘机的机臂、坦克的炮塔、起重机吊臂都是这样 ——
 * 它们和宿主（车身）是<b>两个独立的结构</b>。
 *
 * <p>而铭牌只命名了宿主。于是不做处理的话，机臂/炮塔会被当成
 * 「没人要的垃圾」单独虚化掉 —— 表现就是<b>挖掘机开着开着机臂突然消失</b>。
 *
 * <h2>数据从哪来</h2>
 * 轴承方块实体自己就知道它驱动的是哪个子维度
 * （{@code SwivelBearingBlockEntity#getSubLevelID()}，已用 javap 核对过字节码）。
 * 所以只要在宿主结构的绘图区里扫到轴承，就能顺藤摸瓜拿到机臂那个子维度的 UUID。
 *
 * <p>另外也尝试 Sable 自己的通用 API
 * {@code BlockEntitySubLevelActor#sable$getConnectionDependencies()}，
 * 这样其它模组实现的连接机构也能被覆盖到。
 *
 * <p>关系按<b>无向</b>处理：不管轴承方块是坐在宿主那一侧还是挂载那一侧，
 * 最终效果都是「两个结构被豁免一起传染」，方向不重要。
 */
public final class AttachmentCheck {

    /** 扫描结果为空时的共享常量，避免每次 new。 */
    private static final Set<UUID> EMPTY = Set.of();

    /**
     * 和这个结构物理相连的其它结构。
     *
     * @param state 用来缓存扫描结果；可以为 null（那就每次都真扫）
     * @param now   当前游戏刻
     */
    public static Set<UUID> linkedSubLevels(ServerSubLevel sub, FadeState state, long now) {
        if (state != null && state.linksCheckedAt != Long.MIN_VALUE
                && now - state.linksCheckedAt < com.aerofade.AeroFadeConfig.NAMEPLATE_SCAN_INTERVAL_TICKS.get()) {
            return state.links;
        }

        final Set<UUID> links = scan(sub);

        if (state != null) {
            state.linksCheckedAt = now;
            state.links = links;
        }
        return links;
    }

    /**
     * 真的去扫一遍宿主绘图区里的方块实体。
     *
     * <p><b>必须用 {@code plot.getLoadedChunks()} 拿区块</b>，
     * 不能用 {@code getChunkMin/Max} 配 {@code getChunk()} ——
     * 那两个的坐标空间不一致，会永远返回 null。
     * 这个坑在 {@link NameplateCheck} 里已经踩过一次了。
     */
    private static Set<UUID> scan(ServerSubLevel sub) {
        final ServerLevelPlot plot;
        try {
            plot = sub.getPlot();
        } catch (final Throwable ignored) {
            return EMPTY;
        }
        if (plot == null) {
            return EMPTY;
        }

        final Collection<PlotChunkHolder> holders;
        try {
            holders = plot.getLoadedChunks();
        } catch (final Throwable ignored) {
            return EMPTY;
        }
        if (holders == null || holders.isEmpty()) {
            return EMPTY;
        }

        final Set<UUID> links = new LinkedHashSet<>();
        for (final PlotChunkHolder holder : holders) {
            final LevelChunk chunk;
            try {
                chunk = holder.getChunk();
            } catch (final Throwable ignored) {
                continue;
            }
            if (chunk == null) {
                continue;
            }
            for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                collectFromBlockEntity(blockEntity, links);
            }
        }
        return links.isEmpty() ? EMPTY : links;
    }

    /** 从一个方块实体上尽量榨出「它连着哪个子维度」。所有取值都单独兜异常。 */
    private static void collectFromBlockEntity(BlockEntity blockEntity, Set<UUID> links) {
        // ---- 主路径：Simulated 的物理轴承 ----
        if (blockEntity instanceof final SwivelBearingBlockEntity bearing) {
            try {
                final UUID mounted = bearing.getSubLevelID();
                if (mounted != null) {
                    links.add(mounted);
                }
            } catch (final Throwable ignored) {
                // 拿不到就算了，下面还有通用路径
            }
        }

        // ---- 通用路径：Sable 自己的「连接依赖」接口 ----
        if (blockEntity instanceof final BlockEntitySubLevelActor actor) {
            try {
                for (final SubLevel dependency : actor.sable$getConnectionDependencies()) {
                    if (dependency != null) {
                        links.add(dependency.getUniqueId());
                    }
                }
            } catch (final Throwable ignored) {
                // 同上
            }
        }
    }

    private AttachmentCheck() {
    }
}