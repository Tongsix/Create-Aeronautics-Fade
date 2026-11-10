package com.aerofade;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 「航空学：虚化」的服务端配置。
 *
 * <p>配置文件位置：{@code <存档>/serverconfig/aerofade-server.toml}
 * （单人游戏在 {@code .minecraft/saves/<存档>/serverconfig/}）。
 *
 * <p>之所以用 SERVER 类型而不是 COMMON，是因为虚化的判定逻辑完全在服务端，
 * 客户端只是照着服务端下发的进度去渲染透明度。
 */
public final class AeroFadeConfig {

    public static final ModConfigSpec SPEC;

    // ------------------------------------------------------------------ 总开关
    public static final ModConfigSpec.BooleanValue ENABLED;

    // ------------------------------------------------------------------ 虚化节奏
    public static final ModConfigSpec.IntValue IDLE_SECONDS;
    public static final ModConfigSpec.IntValue FADE_SECONDS;
    public static final ModConfigSpec.IntValue CHECK_INTERVAL_TICKS;

    // ------------------------------------------------------------------ 打断虚化的行为
    public static final ModConfigSpec.BooleanValue INTERRUPT_ON_BLOCK_CHANGE;
    public static final ModConfigSpec.BooleanValue INTERRUPT_ON_PLAYER_STANDING;
    public static final ModConfigSpec.BooleanValue INTERRUPT_ON_MOVEMENT;
    public static final ModConfigSpec.DoubleValue MOVEMENT_TRANSLATION_TOLERANCE;
    public static final ModConfigSpec.DoubleValue MOVEMENT_ROTATION_TOLERANCE;

    // ------------------------------------------------------------------ 命名 / 铭牌
    public static final ModConfigSpec.BooleanValue REQUIRE_NAMEPLATE_BLOCK;
    public static final ModConfigSpec.IntValue NAMEPLATE_SCAN_INTERVAL_TICKS;

    // ------------------------------------------------------------------ 豁免传播
    public static final ModConfigSpec.BooleanValue PROPAGATE_ON_CONTACT;
    public static final ModConfigSpec.DoubleValue CONTACT_TOLERANCE;
    public static final ModConfigSpec.BooleanValue PROPAGATE_ON_ENVELOPE;

    // ------------------------------------------------------------------ 质量阈值
    public static final ModConfigSpec.DoubleValue MASS_THRESHOLD;

    // ------------------------------------------------------------------ 其它
    public static final ModConfigSpec.IntValue RESYNC_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue SYNC_RADIUS;
    public static final ModConfigSpec.BooleanValue LOG_FADE_EVENTS;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment(
                "总开关。关掉之后本模组完全不做任何虚化与删除，",
                "所有结构的倒计时也停止累计（不会出现「关了一周再打开，一夜之间全被删光」）。").push("general");

        ENABLED = builder
                .comment("是否启用虚化功能。默认 true。",
                        "也可以用游戏内指令 /fade set enabled <true|false> 直接切换。")
                .define("enabled", true);

        builder.pop();

        builder.comment(
                "虚化节奏。",
                "一个「物理结构」= 一个 Sable 子维度(SubLevel) / 物理载具。").push("timing");

        IDLE_SECONDS = builder
                .comment("多久没有任何互动之后，开始虚化（秒）。默认 600 秒 = 10 分钟。")
                .defineInRange("idleSecondsBeforeFade", 600, 0, Integer.MAX_VALUE);

        FADE_SECONDS = builder
                .comment("从「开始虚化」到「完全透明并被删除」的时长（秒）。默认 300 秒 = 5 分钟。",
                        "所以默认情况下，一个结构从最后一次互动算起，15 分钟后会消失。")
                .defineInRange("fadeDurationSeconds", 300, 1, Integer.MAX_VALUE);

        CHECK_INTERVAL_TICKS = builder
                .comment("服务端多久检查一次所有物理结构（tick）。默认 20 = 1 秒。",
                        "调大可省性能，调小则虚化进度更平滑。")
                .defineInRange("checkIntervalTicks", 20, 1, 72000);

        builder.pop();

        builder.comment(
                "哪些行为可以打断虚化（并立刻把进度重置为 0）。",
                "默认只开启「放置/破坏方块」和「玩家站在结构上」两种；",
                "「运动」默认关闭，因为停在水面上的船会一直轻微晃动，开了就等于永不虚化。").push("interrupt");

        INTERRUPT_ON_BLOCK_CHANGE = builder
                .comment("结构上的方块发生变化时打断虚化。默认开启。",
                        "注意：这条不区分是玩家操作还是运转中的机械 —— 这是刻意的，",
                        "因为「机械还在动」本身就说明这个结构还活着，不该删。",
                        "反例：风车 / 水车 / 创造马达不需要燃料，废弃了也不会停，",
                        "遇到这种情况把本项关掉（只靠「玩家站在结构上」判定）。")
                .define("onBlockChange", true);

        INTERRUPT_ON_PLAYER_STANDING = builder
                .comment("有玩家站在（或骑在）物理结构上时打断虚化。默认开启。")
                .define("onPlayerStanding", true);

        INTERRUPT_ON_MOVEMENT = builder
                .comment("物理结构自身发生位移或旋转时打断虚化。默认关闭。")
                .define("onMovement", false);

        MOVEMENT_TRANSLATION_TOLERANCE = builder
                .comment("判定「发生了移动」的位移阈值（方块）。仅在 onMovement 开启时有效。")
                .defineInRange("movementTranslationTolerance", 0.05D, 0.0D, 64.0D);

        MOVEMENT_ROTATION_TOLERANCE = builder
                .comment("判定「发生了旋转」的角度阈值。仅在 onMovement 开启时有效。")
                .defineInRange("movementRotationTolerance", 0.02D, 0.0D, 3.15D);

        builder.pop();

        builder.comment("铭牌（Nameplate）相关的判定。").push("nameplate");

        REQUIRE_NAMEPLATE_BLOCK = builder
                .comment("是否额外确认「铭牌方块真的还在结构上」。默认开启。",
                        "原版实现：铭牌被编辑时会把名字写进子维度(SubLevel.setName)，",
                        "但铭牌被破坏时并不会把名字清掉 —— 也就是说名字会变成「幽灵名字」。",
                        "开启本项后，本模组会定期扫描结构，确认确实存在一个带名字的铭牌方块；",
                        "如果铭牌已经被打掉，结构就会重新开始虚化倒计时。",
                        "关闭它可以省掉这次扫描，但被打掉铭牌的结构将永远不会虚化。")
                .define("verifyNameplateStillExists", true);

        NAMEPLATE_SCAN_INTERVAL_TICKS = builder
                .comment("上面的扫描多久做一次（tick）。默认 100 = 5 秒。")
                .defineInRange("nameplateScanIntervalTicks", 100, 20, 72000);

        builder.pop();

        builder.comment(
                "质量阈值：质量达到或超过阈值的结构不会虚化。",
                "【0 = 此功能关闭】，也就是默认行为：",
                "不管多大的结构，只要没铭牌、太久没互动，一样会虚化消失。").push("mass");

        MASS_THRESHOLD = builder
                .comment("质量阈值（单位 kpg，和 Sable 的 sable:mass 同一单位）。",
                        "0 表示关闭这个功能（默认）。",
                        "参考：Sable 里一个普通方块的默认质量是 1.0 kpg。")
                .defineInRange("massThreshold", 0.0D, 0.0D, Double.MAX_VALUE);

        builder.pop();

        builder.comment(
                "豁免传播：一个已被豁免的结构（铭牌命名 / 质量达标），可以把豁免「传染」给和它相关的结构。",
                "轴承、关节、绳子、对接器这类【机制连接】是一直生效的 ——",
                "否则挖掘机的机臂、坦克的炮塔会被当成垃圾单独删掉。",
                "下面两项是按【几何关系】传播，默认都开启。").push("propagate");

        PROPAGATE_ON_CONTACT = builder
                .comment("接触：两个结构的包围盒相邻或相交时，传播豁免。默认开启。",
                        "典型场景：航母被铭牌命名 -> 停在甲板上的舰载机也跟着安全。",
                        "强力胶粘在一起的结构也属于这一类（胶只能粘相邻方块，",
                        "所以「粘住」必然表现为「包围盒相接」），不需要单独判定胶。",
                        "注意这条会沿「贴在一起」链式传染：一堆互相挨着的残骸会被视为一体，",
                        "一起被豁免。这是安全方向的取舍 —— 少删比错删好。")
                .define("propagateOnContact", true);

        CONTACT_TOLERANCE = builder
                .comment("判定「接触」的容差（方块）。默认 0.25。",
                        "两个包围盒相距小于这个值就算接触。",
                        "留一点容差是因为「面贴面」摆放时，包围盒可能只差一个浮点误差；",
                        "调大 = 更容易判定为接触（更保守，更不容易删东西）。")
                .defineInRange("contactTolerance", 0.25D, 0.0D, 16.0D);

        PROPAGATE_ON_ENVELOPE = builder
                .comment("包络：一个结构整个位于另一个结构内部时，传播豁免。默认开启。",
                        "典型场景：空中基地被铭牌命名 -> 停在它内部、与它没有任何物理连接的飞艇也跟着安全。",
                        "判定方式：包围盒完全包含（三条轴上都覆盖）。",
                        "注意包围盒是粗粒度近似：穿过大型框架结构内部的东西也会被算作「在里面」，",
                        "这是刻意的取舍。")
                .define("propagateOnEnvelope", true);

        builder.pop();

        builder.comment("杂项。").push("misc");

        RESYNC_INTERVAL_TICKS = builder
                .comment("即使进度没变化，也每隔这么久向客户端重发一次状态（tick）。",
                        "用于让后进入视野 / 后登录的玩家也能看到正确的透明度。默认 200 = 10 秒。")
                .defineInRange("resyncIntervalTicks", 200, 20, 72000);

        SYNC_RADIUS = builder
                .comment("向结构周围多大半径内的玩家同步虚化进度（方块）。")
                .defineInRange("syncRadius", 512.0D, 64.0D, 4096.0D);

        LOG_FADE_EVENTS = builder
                .comment("是否在日志里记录「开始虚化 / 结构被删除 / 倒计时被重置」这类事件。",
                        "默认【关闭】—— 本模组的定位是「装上之后几乎感觉不到它存在」，",
                        "服务器上每天清理垃圾如果都写日志，控制台会被刷满。",
                        "需要排查时再打开，或者用 /fade list 与 /fade why 按需查看。")
                .define("logFadeEvents", false);

        builder.pop();

        SPEC = builder.build();
    }

    /** 虚化功能是否启用。 */
    public static boolean isEnabled() {
        return ENABLED.get();
    }

    /** 开始虚化前的等待刻数。 */
    public static long idleTicks() {
        return (long) IDLE_SECONDS.get() * 20L;
    }

    /** 虚化过程的总刻数。 */
    public static long fadeTicks() {
        return (long) FADE_SECONDS.get() * 20L;
    }

    /**
     * 质量阈值是否启用。
     *
     * <p>刻意不做成一个单独的开关：0 就是关闭。
     * 少一个配置项，也少一种「开关关了但阈值还填着」的困惑状态。
     */
    public static boolean massThresholdEnabled() {
        return MASS_THRESHOLD.get() > 0.0D;
    }

    private AeroFadeConfig() {
    }
}
