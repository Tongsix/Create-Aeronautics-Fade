package com.aerofade;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 「航空学：虚化」的客户端配置 —— 只影响渲染。
 *
 * <p>配置文件位置：{@code .minecraft/config/aerofade-client.toml}
 *
 * <p>注意：服务端下发的是「进度 0~1」，最终渲染成多透明由客户端自己决定，
 * 这样服务器管理员不需要关心玩家的视觉偏好。
 */
public final class AeroFadeClientConfig {

    public static final ModConfigSpec SPEC;

    /** 是否启用「越虚化越透明」的渲染。关掉之后结构会到点直接消失。 */
    public static final ModConfigSpec.BooleanValue ENABLE_TRANSPARENCY;

    /** 进度 100% 时的 alpha。0.0 = 完全看不见。 */
    public static final ModConfigSpec.DoubleValue FINAL_ALPHA;

    /**
     * 渲染路径诊断日志。<b>默认关闭</b>，只在排查「虚化渲染不对」时打开。
     *
     * <p>打开后每 5 秒会往日志里写一行，内容是这一段时间里见过的所有
     * {@code RenderType}、以及每个正在虚化的结构一帧大约被画了几次。
     * 排查完记得关掉 —— 它不符合本模组「安静」的原则。
     */
    public static final ModConfigSpec.BooleanValue DEBUG_LOG_RENDER_PATHS;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        ENABLE_TRANSPARENCY = builder
                .comment("是否让正在虚化的物理结构真的变透明。默认开启。",
                        "关闭后，结构会保持正常外观直到被服务端删除。")
                .define("enableTransparency", true);

        FINAL_ALPHA = builder
                .comment("虚化进度达到 100% 时的 alpha 值。0.0 = 完全看不见，0.15 会留一点残影。")
                .defineInRange("finalAlpha", 0.0D, 0.0D, 1.0D);

        DEBUG_LOG_RENDER_PATHS = builder
                .comment("渲染路径诊断日志。默认关闭。",
                        "打开后每 5 秒输出一次：见过的 RenderType 列表，以及每个虚化结构每帧被绘制的次数。",
                        "仅在排查渲染问题时开启，排查完请关掉。")
                .define("debugLogRenderPaths", false);

        SPEC = builder.build();
    }

    private AeroFadeClientConfig() {
    }
}
