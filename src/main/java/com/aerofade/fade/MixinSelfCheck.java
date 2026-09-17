package com.aerofade.fade;

import java.lang.reflect.Field;

import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;

/**
 * Mixin 自检。
 *
 * <h2>为什么需要这个</h2>
 * 本模组的注入点打在 Sable 的**内部实现**上，而 {@code aerofade.mixins.json} 里
 * 故意把 {@code defaultRequire} 设成了 0（理由见那个文件的注释：宁可降级也不要崩服）。
 *
 * <p>副作用是：<b>注入失败是完全静默的</b>。如果哪天 Sable 改了方法名，
 * 模组不会崩、也不会报错，只是「方块改动不再算互动」——
 * 而这种问题在游戏里极难察觉（表现只是「这东西怎么不删」）。
 *
 * <p>所以这里做一次主动自检：引用目标类的 {@code .class} 会强制 JVM 加载它，
 * 也就强制 Mixin 完成注入；然后反射看我们那个 {@code @Unique} 字段在不在里面。
 *
 * <h2>为什么必须"主动加载"</h2>
 * {@code ServerLevelPlot} 只有在世界里真的存在一个物理结构时才会被加载。
 * 一个刚开的新世界里根本没有，光看日志是永远看不到注入结果的。
 */
public final class MixinSelfCheck {

    /** 我们在 {@code ServerLevelPlotMixin} 里声明的 {@code @Unique} 字段名。 */
    private static final String UNIQUE_FIELD = "aerofade$loadingFromDisk";

    /**
     * @return true 表示 {@code ServerLevelPlotMixin} 确实注入到了 {@code ServerLevelPlot} 上
     */
    public static boolean isServerPlotMixinApplied() {
        try {
            // 故意用 .class 字面量：这会强制加载 ServerLevelPlot，从而触发 Mixin 注入
            for (final Field field : ServerLevelPlot.class.getDeclaredFields()) {
                if (UNIQUE_FIELD.equals(field.getName())) {
                    return true;
                }
            }
            return false;
        } catch (final Throwable t) {
            // 连类都加载不了（比如 Sable 没装）——那本来也不是"注入失败"，而是"目标不存在"
            return false;
        }
    }

    private MixinSelfCheck() {
    }
}
