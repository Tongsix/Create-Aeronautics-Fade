package com.aerofade.client;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aerofade.AeroFade;
import net.minecraft.client.renderer.RenderType;

/**
 * 渲染路径诊断 —— <b>默认关闭</b>，只在排查「虚化渲染不对」的时候打开。
 *
 * <h2>为什么需要它</h2>
 * 虚化渲染这一步完全依赖别的模组（Sable / Veil）的内部实现，
 * 而 Sable 有两条不同的渲染路径，传下来的 {@code RenderType} 一个是具体类型、
 * 一个是包装类型。光靠读字节码很难确定实际跑的是哪一条、每个结构一帧被画了几次。
 *
 * <p>打开 {@code debugLogRenderPaths} 之后，每 5 秒往日志里写一行：
 * <ul>
 *   <li>这一段时间里见过的所有 RenderType（含"未知类型"标记）；</li>
 *   <li>每个正在虚化的结构一帧大约被画了几次。</li>
 * </ul>
 * 这两条信息基本可以直接定位渲染类问题的根因。
 */
public final class RenderPathLog {

    /** 5 秒汇总一次，避免刷屏。 */
    private static final long WINDOW_MS = 5000L;

    private static final Map<UUID, Integer> DRAWS_PER_STRUCTURE = new LinkedHashMap<>();
    private static final Map<UUID, Set<String>> LAYERS_PER_STRUCTURE = new LinkedHashMap<>();
    private static final Map<UUID, float[]> ALPHA_RANGE = new LinkedHashMap<>();
    private static final long START = System.currentTimeMillis();
    private static final Set<String> SEEN_TYPES = new LinkedHashSet<>();
    private static long windowStart;
    private static final Set<String> FAILURES = new LinkedHashSet<>();
    private static int frames;

    /**
     * 记录一次「正在虚化的结构被画了一次」。
     */
    public static void record(RenderType type, UUID id, float alpha) {
        try {
            SEEN_TYPES.add(FadeRenderHelper.describe(type));
            DRAWS_PER_STRUCTURE.merge(id, 1, Integer::sum);
            LAYERS_PER_STRUCTURE.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(FadeRenderHelper.describe(type));
            final float[] range = ALPHA_RANGE.computeIfAbsent(id, k -> new float[] { Float.MAX_VALUE, -Float.MAX_VALUE, 0.0F });
            range[0] = Math.min(range[0], alpha);
            range[1] = Math.max(range[1], alpha);
            range[2] = alpha;
            if (windowStart == 0L) {
                windowStart = System.currentTimeMillis();
            }
            frames++;
            flushIfDue();
        } catch (final Throwable ignored) {
            // 诊断代码本身绝不能影响渲染
        }
    }

    /**
     * 记录一次虚化渲染路径上的异常。
     *
     * <p><b>存在的意义：之前这些异常被 catch 静默吞掉</b>，
     * 结果表现成「结构在虚化时凭空消失」，而日志里一个字都没有 ——
     * 排查了很久才想到可能根本没画上去。渲染路径上的异常<b>必须留痕</b>。
     */
    public static void recordFailure(String stage, Throwable t) {
        try {
            final String key = stage + ": " + t;
            if (FAILURES.add(key)) {
                AeroFade.LOGGER.warn("[aerofade] 虚化渲染在「{}」阶段抛出异常，本次已退化为原样绘制：{}", stage, key);
                AeroFade.LOGGER.warn("[aerofade] 异常堆栈：", t);
            }
        } catch (final Throwable ignored) {
            // 诊断代码本身绝不能影响渲染
        }
    }

    private static void flushIfDue() {
        final long now = System.currentTimeMillis();
        if (now - windowStart < WINDOW_MS) {
            return;
        }

        final StringBuilder sb = new StringBuilder();
        final long elapsedMs = Math.max(1L, now - windowStart == 0L ? WINDOW_MS : now - windowStart);
        sb.append("[aerofade] 渲染诊断（最近 ").append(WINDOW_MS / 1000).append(" 秒，共 ")
                .append(frames).append(" 次虚化绘制，约 ")
                .append(frames * 1000L / elapsedMs).append(" 次/秒）：\n");
        sb.append("  出现过的 RenderType: ").append(String.join(", ", SEEN_TYPES)).append('\n');
        sb.append("  每个结构：绘制次数 + 覆盖到的层\n");
        for (final Map.Entry<UUID, Integer> e : DRAWS_PER_STRUCTURE.entrySet()) {
            final Set<String> layers = LAYERS_PER_STRUCTURE.getOrDefault(e.getKey(), Set.of());
            final float[] range = ALPHA_RANGE.get(e.getKey());
            sb.append("    ").append(e.getKey())
                    .append(" -> ").append(e.getValue()).append(" 次，")
                    .append(layers.size()).append(" 层: ").append(String.join(", ", layers));
            if (range != null) {
                sb.append("  |  alpha 区间 ").append(String.format("%.3f", range[0]))
                        .append(" ~ ").append(String.format("%.3f", range[1]))
                        .append("  最新 ").append(String.format("%.3f", range[2]));
                if (range[1] < 0.02F) {
                    sb.append("   <-- alpha 几乎为 0：结构会完全看不见！");
                }
            }
            if (layers.size() < 5) {
                sb.append("   <-- 层数偏少，可能有一层没被画到");
            }
            sb.append('\n');
        }        AeroFade.LOGGER.info(sb.toString());

        FAILURES.clear();
        SEEN_TYPES.clear();
        LAYERS_PER_STRUCTURE.clear();
        ALPHA_RANGE.clear();
        DRAWS_PER_STRUCTURE.clear();
        frames = 0;
        windowStart = now;
    }

    private RenderPathLog() {
    }
}