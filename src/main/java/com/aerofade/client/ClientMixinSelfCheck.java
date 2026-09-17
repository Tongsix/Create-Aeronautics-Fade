package com.aerofade.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.aerofade.AeroFade;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;

/**
 * 客户端 Mixin 自检 —— 确认虚化渲染的注入点真的生效了。
 *
 * <p>理由同 {@code MixinSelfCheck}：{@code defaultRequire = 0} 让注入失败变得静默，
 * 而「结构不变透明」这种问题玩家只会当成 bug 而不是"注入失败"。
 *
 * <p>引用 {@code VanillaSubLevelRenderDispatcher.class} 会强制加载这个类，
 * 从而强制 Mixin 完成注入。
 *
 * <p>判定方式特意做了两种，因为 Mixin 对不同类型的成员命名行为不一样：
 * <ul>
 *   <li>{@code @Unique} 字段：名字<b>原样保留</b>（服务端那个 ServerLevelPlotMixin
 *       就是靠这一点验证通过的）；</li>
 *   <li>注入处理器方法：Mixin 通常会重命名成 {@code handler$<id>$<原名>}，
 *       所以这里用 {@code contains} 而不是 {@code startsWith}。</li>
 * </ul>
 *
 * <p>这个类只能在客户端加载 —— {@code VanillaSubLevelRenderDispatcher} 是客户端类。
 */
public final class ClientMixinSelfCheck {

    /** {@code SubLevelRenderMixin} 里声明的 {@code @Unique} 字段名。 */
    private static final String UNIQUE_FIELD = "aerofade$renderHookInstalled";

    public static boolean isSubLevelRenderMixinApplied() {
        return hasUniqueField() || hasAerofadeHandlerMethod();
    }

    /** {@code @Unique} 字段在不在 —— 证明「Mixin 类本身应用到了目标类上」。 */
    public static boolean hasUniqueField() {
        try {
            for (final Field field : VanillaSubLevelRenderDispatcher.class.getDeclaredFields()) {
                if (UNIQUE_FIELD.equals(field.getName())) {
                    return true;
                }
            }
        } catch (final Throwable ignored) {
        }
        return false;
    }

    /**
     * 注入处理器方法在不在 —— 证明「{@code @WrapOperation} 这个注入点真的落到了目标方法里」。
     *
     * <p>这两件事必须分开看：Mixin 类应用成功、但某个 {@code require = 0} 的注入点失败，
     * 是完全可能的。而视觉效果只依赖后者。
     */
    public static boolean hasAerofadeHandlerMethod() {
        try {
            for (final Method method : VanillaSubLevelRenderDispatcher.class.getDeclaredMethods()) {
                if (method.getName().contains("aerofade$")) {
                    return true;
                }
            }
        } catch (final Throwable ignored) {
        }
        return false;
    }

    public static void logResult() {
        final boolean uniqueField = hasUniqueField();
        final boolean handlerMethod = hasAerofadeHandlerMethod();

        // 成功走 DEBUG：安静的模组不该在每次启动客户端时刷存在感。
        // 失败则继续往下走 WARN 分支 —— 那种情况必须让人看见。
        AeroFade.LOGGER.debug("[aerofade] 渲染注入自检：Mixin类已应用={}  WrapOperation注入点已生效={}",
                uniqueField, handlerMethod);

        if (uniqueField && handlerMethod) {
            return;
        }

        if (uniqueField && !handlerMethod) {
            AeroFade.LOGGER.warn("[aerofade] 自检部分失败：Mixin 类应用成功，但 @WrapOperation 注入点没有生效。"
                    + "服务端的虚化与删除不受影响，但物理结构不会出现逐渐变透明的视觉效果。");
        } else {
            AeroFade.LOGGER.warn("[aerofade] 自检失败：SubLevelRenderMixin 完全没有应用。"
                    + "服务端的虚化与删除不受影响，但物理结构不会出现逐渐变透明的视觉效果。");
        }

        // ---- 诊断信息：把目标类上真实存在的成员打出来，方便定位是哪一步没生效 ----
        try {
            final StringBuilder methods = new StringBuilder();
            for (final Method method : VanillaSubLevelRenderDispatcher.class.getDeclaredMethods()) {
                methods.append(method.getName()).append(' ');
            }
            AeroFade.LOGGER.warn("[aerofade] 诊断 - VanillaSubLevelRenderDispatcher 方法列表: {}", methods);

            final StringBuilder fields = new StringBuilder();
            for (final Field field : VanillaSubLevelRenderDispatcher.class.getDeclaredFields()) {
                fields.append(field.getName()).append(' ');
            }
            AeroFade.LOGGER.warn("[aerofade] 诊断 - VanillaSubLevelRenderDispatcher 字段列表: {}", fields);
        } catch (final Throwable ignored) {
            // 诊断本身不能再抛异常
        }
    }

    private ClientMixinSelfCheck() {
    }
}
