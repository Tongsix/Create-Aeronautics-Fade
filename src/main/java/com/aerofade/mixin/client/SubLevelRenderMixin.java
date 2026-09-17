package com.aerofade.mixin.client;

import com.aerofade.AeroFadeClientConfig;
import com.aerofade.client.ClientFadeCache;
import com.aerofade.client.FadeRenderHelper;
import com.aerofade.client.RenderPathLog;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * 让正在虚化的物理结构真的变透明。
 *
 * <h2>一、必须延后到半透明层绘制（否则会挡掉背后的方块）</h2>
 * 实测结论：Sable 是在<b>地形之前</b>绘制子维度的
 * （两个注入点 {@code sable$renderSubLevels} / {@code sable$renderSubLevelLayers}
 * 都打在 {@code ShaderInstance.clear()} 上）。于是同一个结构只有两种下场：
 * <ul>
 *   <li>深度写入<b>关</b> —— 随后绘制的地形直接盖住它，<b>结构整个看不见</b>；</li>
 *   <li>深度写入<b>开</b> —— 它先写入深度，背后地形被深度测试挡掉，
 *       <b>透过结构直接看到天空盒</b>。</li>
 * </ul>
 * 两种都是错的。正确做法是把虚化结构<b>延后到半透明层</b>再画：
 * 那时不透明地形<b>已经在帧缓冲里</b>了，于是既能混合、又不会遮挡背后。
 *
 * <h2>二、但「跳过」必须带保险</h2>
 * 延后的代价是「本层不画」。一旦半透明层那趟没跑到，结构就再也补不回来 ——
 * 所以用 {@link FadeRenderHelper#canDefer()} 把关：
 * <b>最近确实见过半透明层才允许延后</b>，否则就地绘制。
 * 宁可留一点遮挡，也绝不让结构消失。
 *
 * <h2>三、单方块结构必须强制走分区块路径</h2>
 * Sable 把「只有一个方块」的子维度单独优化成了 {@code VanillaSingleSubLevelRenderData}，
 * 走 {@code renderSingleBlock} 并被<b>批量合并成一次绘制</b>
 * （见 {@code renderAfterSections}），根本不经过 {@code renderChunkedSubLevel} ——
 * 所以它们拿不到任何 alpha，表现为「没有虚化过程，直接就没了」。
 * 这里把 {@code isSingleBlock} 恒定为 false，让它们回到分区块路径，
 * 从而复用同一套虚化机制。
 *
 * <h2>关于 require = 0</h2>
 * 这些注入点都打在<b>别的模组</b>（Sable）的内部实现上。一旦 Sable 改签名，
 * 注入会静默跳过，表现退化成「结构不变透明，但到点照样被删除」，
 * 而不是把整合包直接崩掉。
 */
@Mixin(VanillaSubLevelRenderDispatcher.class)
public abstract class SubLevelRenderMixin {

    /** 自检标记：{@code @Unique} 字段名会被 Mixin 原样保留，反射看到它就说明注入成功了。 */
    @Unique
    private boolean aerofade$renderHookInstalled = true;

    /**
     * 让所有单方块子维度也走分区块渲染路径。
     *
     * <p>取舍：放弃了 Sable 针对单方块的优化，每个单方块结构会多占一点显存/构建开销，
     * 换来的是它们<b>也能正常虚化</b>。对一个「清理碎片」用的模组来说，
     * 碎片恰恰经常就是单方块结构，所以这个取舍是值得的。
     */
    @Inject(method = "isSingleBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private static void aerofade$forceChunkedForSingleBlock(final ClientSubLevel subLevel,
                                                            final CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(Boolean.FALSE);
    }

    /**
     * 包住「单个物理结构的实际绘制调用」。
     *
     * <p>目标方法签名（已用 javap 核对 Sable 2.0.5 字节码）：
     * <pre>
     * VanillaChunkedSubLevelRenderData#renderChunkedSubLevel(
     *     RenderType, ShaderInstance, Matrix4f, double, double, double) : void
     * </pre>
     * 它内部用 {@code renderType} 去查这一层的顶点缓冲
     * （{@code CompiledSection#isEmpty(renderType)} / {@code RenderSection#getBuffer(renderType)}），
     * 所以必须原样把 renderType 传下去，不能替换成别的类型。
     */
    @WrapOperation(
            method = "renderSectionLayer",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/sublevel/render/vanilla/VanillaChunkedSubLevelRenderData;"
                            + "renderChunkedSubLevel(Lnet/minecraft/client/renderer/RenderType;"
                            + "Lnet/minecraft/client/renderer/ShaderInstance;Lorg/joml/Matrix4f;DDD)V"))
    private void aerofade$fadeChunkedSubLevel(VanillaChunkedSubLevelRenderData data,
                                              RenderType renderType,
                                              ShaderInstance shader,
                                              Matrix4f modelView,
                                              double camX, double camY, double camZ,
                                              Operation<Void> original) {
        // ---------- 快速路径：没在虚化就原样走，一分钱开销都不多花 ----------
        final UUID id;
        final float alpha;
        try {
            if (!AeroFadeClientConfig.ENABLE_TRANSPARENCY.get()) {
                original.call(data, renderType, shader, modelView, camX, camY, camZ);
                return;
            }
            id = data.getSubLevel().getUniqueId();
            alpha = ClientFadeCache.getAlpha(id);
        } catch (final Throwable ignored) {
            original.call(data, renderType, shader, modelView, camX, camY, camZ);
            return;
        }

        try {
            FadeRenderHelper.noteLayer(renderType);
        } catch (final Throwable ignored) {
            // 记账失败不影响绘制
        }

        if (alpha >= 0.999F) {
            original.call(data, renderType, shader, modelView, camX, camY, camZ);
            return;
        }

        if (AeroFadeClientConfig.DEBUG_LOG_RENDER_PATHS.get()) {
            RenderPathLog.record(renderType, id, alpha);
        }

        final boolean translucentLayer = FadeRenderHelper.isTranslucentLayer(renderType);

        if (!translucentLayer && FadeRenderHelper.canDefer()) {
            // 本层不画，等半透明层连本带利补上（这样才不会挡掉背后的地形）
            return;
        }

        // ---------- 进入虚化绘制 ----------
        final boolean fadeStatePushed;
        try {
            FadeRenderHelper.beginFadeDraw(alpha);
            fadeStatePushed = true;
        } catch (final Throwable t) {
            RenderPathLog.recordFailure("beginFadeDraw", t);
            // ★ 必须先把混合状态恢复回去再原样绘制。
            // 否则会带着「混合开着」的状态画一遍，而 Sable 子维度几何体的
            // 片元 alpha 不参与运算，那样画出来可能就是彻底看不见。
            try {
                FadeRenderHelper.endFadeDraw();
            } catch (final Throwable ignored) {
                // 恢复失败也只能硬着头皮画
            }
            original.call(data, renderType, shader, modelView, camX, camY, camZ);
            return;
        }

        try {
            if (translucentLayer) {
                // 把「本层之外的其它层」也一起补画上：
                // 之前在不透明层把它们全跳过了，这里是唯一一次真正画出来的机会。
                for (final RenderType layer : FadeRenderHelper.chunkLayers()) {
                    if (layer == renderType) {
                        continue;
                    }
                    data.renderChunkedSubLevel(layer, shader, modelView, camX, camY, camZ);
                }
            }
            original.call(data, renderType, shader, modelView, camX, camY, camZ);
        } catch (final Throwable t) {
            RenderPathLog.recordFailure("renderChunkedSubLevel", t);
            // 绘制本身炸了，这一帧这个结构少画一层。
            // 刻意不在这里重试 original.call：重试等于把同一层画两遍，alpha 会叠加。
        } finally {
            if (fadeStatePushed) {
                try {
                    FadeRenderHelper.endFadeDraw();
                } catch (final Throwable t) {
                    RenderPathLog.recordFailure("endFadeDraw", t);
                }
            }
        }
    }
}