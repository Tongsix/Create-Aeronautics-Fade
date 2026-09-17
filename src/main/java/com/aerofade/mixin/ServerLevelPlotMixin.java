package com.aerofade.mixin;

import com.aerofade.fade.FadeManager;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 捕获「物理结构上的方块被放置/破坏」。
 *
 * <h2>为什么用 Mixin 而不是 NeoForge 的 BlockEvent</h2>
 * 物理载具的方块其实位于远离玩家的「绘图区」坐标系里，玩家在世界里看到的那个位置
 * 和方块真正所在的位置不是一回事。用 {@code BlockEvent.BreakEvent} 拿到的坐标
 * 需要额外换算才知道属于哪个结构，容易漏判。
 *
 * <p>而 Sable 的 {@code ServerLevelPlot#onBlockChange} 是<b>结构内部方块真正发生变化</b>
 * 时必然会被调用的地方 —— 玩家放、玩家拆、活塞推、结构自己变化，全都会走到这里
 * （调用链：{@code LevelChunk#setBlockState} → {@code LevelChunkMixin} → {@code onBlockChange}）。
 *
 * <h2>⚠️ 为什么必须过滤掉「正在从存档加载」</h2>
 * 这是一个实测出来的坑：{@code ServerLevelPlot#load(CompoundTag)}
 * 在从存档恢复结构时会<b>逐方块直接调用 {@code onBlockChange}</b>
 * （已用 javap 确认：load 方法内部 offset 1401 处有一处 onBlockChange 调用）。
 *
 * <p>如果不把它排除掉，那么每一次「结构被加载」都会被当成一次玩家互动，
 * 于是：<b>服务器每重启一次、甚至玩家走近导致区块重新加载一次，
 * 所有结构的虚化倒计时就被重置一遍 —— 残骸永远删不掉，整个模组等于没做。</b>
 *
 * <p>所以这里用一个 {@code @Unique} 字段在 {@code load} 前后打标记，
 * 加载期间的方块变化一律不算互动。
 */
@Mixin(ServerLevelPlot.class)
public abstract class ServerLevelPlotMixin {

    /** 只属于这个绘图区实例的标记：当前是否正在从存档恢复方块。 */
    @Unique
    private boolean aerofade$loadingFromDisk;

    @Inject(method = "load", at = @At("HEAD"))
    private void aerofade$beforeLoad(CompoundTag tag, CallbackInfo ci) {
        this.aerofade$loadingFromDisk = true;
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void aerofade$afterLoad(CompoundTag tag, CallbackInfo ci) {
        this.aerofade$loadingFromDisk = false;
    }

    /**
     * 注入点选 {@code TAIL}：等 Sable 把方块改完、记账做完之后再刷新计时器，
     * 避免万一它在中间抛异常时我们把状态改了。
     */
    @Inject(method = "onBlockChange", at = @At("TAIL"))
    private void aerofade$onBlockChange(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (this.aerofade$loadingFromDisk) {
            // 这是从存档恢复方块，不是互动
            return;
        }
        final ServerSubLevel subLevel = ((ServerLevelPlot) (Object) this).getSubLevel();
        FadeManager.onBlockChanged(subLevel);
    }
}
