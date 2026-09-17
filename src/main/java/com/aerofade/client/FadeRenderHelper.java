package com.aerofade.client;

import com.mojang.blaze3d.platform.GlStateManager;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.renderer.RenderType;
import org.lwjgl.opengl.GL14;

/**
 * 虚化渲染的工具方法 —— 让正在虚化的物理结构真的变透明。
 *
 * <h2>怎么做到「不改着色器、不重建区块」</h2>
 * 区块几何体是<b>预烘焙</b>进 GPU 缓冲的，每帧只 {@code bind() + draw()}，
 * 所以没法靠「包一层 VertexConsumer 改顶点 alpha」来做。
 * 唯一能动的就是<b>绘制时的 GL 状态</b>，于是做法是：
 * 打开混合，并用 GL 的<b>常量 alpha</b> 作为混合因子。
 *
 * <h2>⚠️ 踩过的三个坑（都实测过，别再走回头路）</h2>
 *
 * <h3>坑 1：靠 {@code renderType} 的相等判断来决定「这一层画不画」</h3>
 * 曾经的实现是「跳过不透明层、等 translucent 那一趟再补画」。
 * 它依赖 {@code renderType} 一定是 {@code RenderType.solid()} 这类具体常量；
 * 一旦某条路径下判断成立、而半透明层那趟没跑到，
 * 结构就是「跳过了再也补不回来」——<b>整个结构凭空消失</b>。
 *
 * <h3>坑 2：用 {@code SRC_ALPHA} 当混合因子</h3>
 * 这个因子的值取自<b>片元 alpha</b>。Sable 子维度几何体在不透明层里
 * 看不出问题（因为那时混合是关的，alpha 根本不参与运算），
 * 但一旦打开混合，贡献就趋近 0 —— 结构<b>彻底消失</b>而不是变透明。
 * 压 {@code ColorModulator} 同样不可靠，因为 Sable 会覆盖区块着色器。
 * <b>改用 GL 常量 alpha 可以把这两个依赖一起绕开。</b>
 *
 * <h3>坑 3：关掉深度写入（{@code _depthMask(false)}）</h3>
 * 最隐蔽的一个。理论上关掉深度写入能避免「未排序造成的内部遮挡」，
 * 实测结果却是：结构<b>整个看不见</b> —— 因为不写深度，
 * 同一层里随后绘制的地形会直接盖在它上面。
 * <b>表现极具迷惑性</b>：结构不虚化时完好无损，一开始虚化就凭空消失，
 * 而服务端日志、alpha 同步、绘制次数、异常记录<b>全部正常</b>。
 * <b>结论：宁可保留一点未排序的半透明内部遮挡，也绝不能让结构消失。</b>
 */
public final class FadeRenderHelper {

    /** 区块渲染的五个层，顺序与 {@code RenderType.chunkBufferLayers()} 一致。 */
    private static final List<RenderType> CHUNK_LAYERS = List.of(
            RenderType.solid(),
            RenderType.cutoutMipped(),
            RenderType.cutout(),
            RenderType.translucent(),
            RenderType.tripwire());

    /** 延后绘制前，最多允许多少次绘制没见过半透明层（见 {@link #canDefer()}）。 */
    private static final long DEFER_CONFIDENCE_DRAWS = 600L;

    private static long drawCounter;
    private static long lastTranslucentAt;

    public static List<RenderType> chunkLayers() {
        return CHUNK_LAYERS;
    }

    /**
     * 这一层是不是「半透明层」，也就是我们用来做延后绘制的那一趟。
     *
     * <p>刻意做得比 {@code renderType == RenderType.translucent()} 宽松：
     * 除了引用相等，还看类型名里有没有 {@code translucent}。
     * 原因：Sable 在装了 Veil 时可能传下来包装类型，
     * 引用相等会失效，而类型名仍然含 translucent。
     */
    public static boolean isTranslucentLayer(RenderType type) {
        if (type == null) {
            return false;
        }
        if (type == RenderType.translucent()) {
            return true;
        }
        final String name = String.valueOf(type).toLowerCase(Locale.ROOT);
        return name.contains("translucent");
    }

    /** 每次绘制都记一笔，用来判断「半透明层到底有没有在跑」。 */
    public static void noteLayer(RenderType type) {
        drawCounter++;
        if (isTranslucentLayer(type)) {
            lastTranslucentAt = drawCounter;
        }
    }

    /**
     * 现在能不能安全地把虚化结构延后到半透明层去画。
     *
     * <h2>为什么需要这个判断</h2>
     * 延后绘制的做法是「在本层不画，等半透明层再补画」——
     * 一旦半透明层那一趟没有跑到，结构就是「跳过了再也补不回来」，
     * <b>整个结构凭空消失</b>。这正是早期版本最严重的问题。
     *
     * <p>所以这里要求：<b>最近确实见过半透明层</b>。
     * 只要近 {@value #DEFER_CONFIDENCE_DRAWS} 次绘制里出现过半透明层，
     * 就认为它可以信赖；否则退化成「就地绘制」——
     * 宁可保留一点遮挡，也绝不让结构消失。
     */
    public static boolean canDefer() {
        return lastTranslucentAt > 0L && (drawCounter - lastTranslucentAt) <= DEFER_CONFIDENCE_DRAWS;
    }

    /**
     * 开始绘制一个正在虚化的结构。
     *
     * @param alpha 0~1，1 = 完全不透明，0 = 完全看不见
     */
    public static void beginFadeDraw(float alpha) {
        // 常量 alpha：结果 = 方块颜色 * alpha + 背景颜色 * (1 - alpha)
        // 不依赖片元 alpha，也不依赖 ColorModulator 是否被着色器采纳。
        GL14.glBlendColor(1.0F, 1.0F, 1.0F, alpha);
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(
                GlStateManager.SourceFactor.CONSTANT_ALPHA.value,
                GlStateManager.DestFactor.ONE_MINUS_CONSTANT_ALPHA.value,
                GlStateManager.SourceFactor.CONSTANT_ALPHA.value,
                GlStateManager.DestFactor.ONE_MINUS_CONSTANT_ALPHA.value);
        // 这里刻意不碰深度掩码 —— 原因见类注释里的「坑 3」。
    }

    /**
     * 结束一个正在虚化的结构的绘制。
     *
     * <p><b>必须把混合函数还原成默认值。</b>
     * 因为 Sable 是在一次 {@code renderSectionLayer} 调用里把<b>所有结构</b>画完的：
     * 如果留着 {@code CONSTANT_ALPHA} 和我这个结构的 alpha 不管，
     * 同一层里随后绘制的<b>其它结构也会被一起淡化</b>，
     * 表现就是「一个结构虚化，旁边的结构跟着变透明」。
     *
     * <p>还原成 {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA} 之后，
     * alpha = 1 的结构恰好等价于「不混合」，所以对后续绘制无害。
     */
    public static void endFadeDraw() {
        GlStateManager._blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA.value,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA.value,
                GlStateManager.SourceFactor.ONE.value,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA.value);
    }

    /**
     * 把一个 RenderType 描述成人能看懂的名字，只给调试日志用。
     *
     * <p>之所以需要它：如果一个类型既不是 {@code RenderType.solid()}
     * 也不是已知的那几个，光看 {@code toString()} 是分不出来的。
     * 标出「未知类型！」是为了让「类型假设错了」这类问题<b>立刻暴露</b>，
     * 而不是被静默吞掉。
     */
    public static String describe(RenderType type) {
        if (type == null) {
            return "null";
        }
        final StringBuilder sb = new StringBuilder(type.getClass().getSimpleName());
        if (type == RenderType.solid()) {
            sb.append("(solid)");
        } else if (type == RenderType.cutout()) {
            sb.append("(cutout)");
        } else if (type == RenderType.cutoutMipped()) {
            sb.append("(cutoutMipped)");
        } else if (type == RenderType.translucent()) {
            sb.append("(translucent)");
        } else if (type == RenderType.tripwire()) {
            sb.append("(tripwire)");
        } else {
            sb.append("(未知类型！)");
        }
        return sb.toString();
    }

    private FadeRenderHelper() {
    }
}