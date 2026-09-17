package com.aerofade.command;

import java.util.List;
import java.util.UUID;

import com.aerofade.AeroFade;
import com.aerofade.AeroFadeConfig;
import com.aerofade.fade.FadeEntry;
import com.aerofade.fade.FadeManager;
import com.aerofade.fade.MixinSelfCheck;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code /fade} —— 运行期调试与查询指令。
 *
 * <pre>
 *   /fade status                                   查看当前配置
 *   /fade list [all|waiting|fading]                列出物理结构
 *   /fade set idle &lt;秒&gt;                          多久没互动后开始虚化
 *   /fade set duration &lt;秒&gt;                      从开始虚化到被删除的耗时
 *   /fade set massthreshold &lt;值&gt;                 质量阈值（0 = 关闭）
 *   /fade set interrupt blockchange &lt;true|false&gt;  方块被放置/破坏能否打断
 *   /fade set interrupt playerstanding &lt;true|false&gt;
 *   /fade set interrupt movement &lt;true|false&gt;
 *   /fade reset &lt;索引|all&gt;                       把倒计时重置为「刚刚互动过」
 *   /fade force fade &lt;索引|all&gt;                 立刻进入虚化阶段
 *   /fade force delete &lt;索引|all&gt;               立刻删除
 * </pre>
 *
 * <p>索引以 {@code /fade list} 的输出为准（从 1 开始）。
 * 列表的排序是确定的（按「距离消失还剩多少刻」升序，再按 UUID），
 * 所以只要世界没变化，两次调用之间索引不会乱跳。
 *
 * <p><b>为什么这些指令值得存在</b>：默认配置下要等 10 分钟才开始虚化、再 5 分钟才消失。
 * 没有 {@code set} 和 {@code force}，验证一次视觉效果要等 15 分钟 ——
 * 实际上等于没法测。
 */
public final class FadeCommand {

    /** 权限等级 2 = 服务器 OP。查询和修改都要求 OP，避免普通玩家窥探/改动。 */
    private static final int PERMISSION_LEVEL = 2;

    /** {@code list} 最多显示多少条，防止刷屏。 */
    private static final int MAX_LIST = 20;

    private enum Interrupt {
        BLOCK_CHANGE,
        PLAYER_STANDING,
        MOVEMENT
    }

    private enum ListFilter {
        ALL,
        WAITING,
        FADING
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("fade")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.literal("status").executes(FadeCommand::status))
                .then(Commands.literal("on").executes(ctx -> setEnabled(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> setEnabled(ctx, false)))
                .then(Commands.literal("why")
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(FadeCommand::why)))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx, ListFilter.ALL))
                        .then(Commands.literal("all").executes(ctx -> list(ctx, ListFilter.ALL)))
                        .then(Commands.literal("waiting").executes(ctx -> list(ctx, ListFilter.WAITING)))
                        .then(Commands.literal("fading").executes(ctx -> list(ctx, ListFilter.FADING))))
                .then(Commands.literal("set")
                        .then(Commands.literal("enabled")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(ctx -> setEnabled(ctx,
                                                BoolArgumentType.getBool(ctx, "value")))))
                        .then(Commands.literal("idle")
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(0))
                                        .executes(FadeCommand::setIdle)))
                        .then(Commands.literal("duration")
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                                        .executes(FadeCommand::setDuration)))
                        .then(Commands.literal("massthreshold")
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0D))
                                        .executes(FadeCommand::setMassThreshold)))
                        .then(Commands.literal("interrupt")
                                .then(interruptBranch("blockchange", Interrupt.BLOCK_CHANGE))
                                .then(interruptBranch("playerstanding", Interrupt.PLAYER_STANDING))
                                .then(interruptBranch("movement", Interrupt.MOVEMENT))))
                .then(Commands.literal("reset")
                        .then(Commands.literal("all").executes(FadeCommand::resetAll))
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(FadeCommand::resetOne)))
                .then(Commands.literal("force")
                        .then(Commands.literal("fade")
                                .then(Commands.literal("all").executes(ctx -> force(ctx, true, true)))
                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                        .executes(ctx -> force(ctx, true, false))))
                        .then(Commands.literal("delete")
                                .then(Commands.literal("all").executes(ctx -> force(ctx, false, true)))
                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                        .executes(ctx -> force(ctx, false, false)))))
        );
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> interruptBranch(
            String name, Interrupt which) {
        return Commands.literal(name)
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> setInterrupt(ctx, which)));
    }

    // ------------------------------------------------------------------ status

    private static int status(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("=== 航空学：虚化 / 当前配置 ===")
                .withStyle(ChatFormatting.GOLD), false);

        final boolean enabled = AeroFadeConfig.isEnabled();
        source.sendSuccess(() -> Component.literal("总开关：")
                .append(Component.literal(enabled ? "已启用" : "已关闭（不虚化、不删除）")
                        .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED)), false);

        final long idle = AeroFadeConfig.IDLE_SECONDS.get();
        final long fade = AeroFadeConfig.FADE_SECONDS.get();
        source.sendSuccess(() -> Component.literal(
                "时间线：最后互动后 %d 秒开始虚化，再过 %d 秒被删除（合计 %d 秒）"
                        .formatted(idle, fade, idle + fade)), false);

        final double mass = AeroFadeConfig.MASS_THRESHOLD.get();
        source.sendSuccess(() -> Component.literal(mass > 0.0D
                ? "质量阈值：%.1f kpg（达到或超过则不虚化）".formatted(mass)
                : "质量阈值：已关闭（0）—— 任何大小的结构都会虚化"), false);

        source.sendSuccess(() -> Component.literal("打断虚化的行为："), false);
        sendInterrupt(source, "方块被放置/破坏", AeroFadeConfig.INTERRUPT_ON_BLOCK_CHANGE.get());
        sendInterrupt(source, "玩家站在结构上", AeroFadeConfig.INTERRUPT_ON_PLAYER_STANDING.get());
        sendInterrupt(source, "结构自身发生运动", AeroFadeConfig.INTERRUPT_ON_MOVEMENT.get());

        source.sendSuccess(() -> Component.literal(
                "铭牌校验：%s（每 %d 刻扫描一次）".formatted(
                        AeroFadeConfig.REQUIRE_NAMEPLATE_BLOCK.get() ? "开启" : "关闭",
                        AeroFadeConfig.NAMEPLATE_SCAN_INTERVAL_TICKS.get())), false);

        // Mixin 注入状态。
        // 放这里是因为：注入成功时日志是静默的（本模组默认不打扰人），
        // 所以需要一个「按需可查」的地方。失败时则会在启动日志里大声报警。
        final boolean mixinOk = MixinSelfCheck.isServerPlotMixinApplied();
        source.sendSuccess(() -> Component.literal("服务端 Mixin：")
                .append(Component.literal(mixinOk
                                ? "已注入"
                                : "未注入 —— 「方块变化」将不再算作互动，其余功能正常")
                        .withStyle(mixinOk ? ChatFormatting.GREEN : ChatFormatting.RED)), false);

        return 1;
    }

    private static void sendInterrupt(CommandSourceStack source, String label, boolean enabled) {
        source.sendSuccess(() -> Component.literal("  · " + label + "：")
                .append(Component.literal(enabled ? "可以打断" : "不打断")
                        .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)), false);
    }

    // ------------------------------------------------------------------ list

    private static int list(CommandContext<CommandSourceStack> ctx, ListFilter filter) {
        final CommandSourceStack source = ctx.getSource();
        final ServerLevel level = source.getLevel();
        final List<FadeEntry> all = FadeManager.get(level).collect(level);

        int waiting = 0;
        int fading = 0;
        int exempt = 0;
        int matched = 0;
        for (final FadeEntry entry : all) {
            if (entry.fading()) {
                fading++;
            } else if (entry.exempt()) {
                exempt++;
            } else {
                waiting++;
            }
            if (matches(entry, filter)) {
                matched++;
            }
        }

        // 这些计数要放进 lambda 里发给玩家，所以必须先拷成 final 局部变量
        final int totalCount = all.size();
        final int waitingCount = waiting;
        final int fadingCount = fading;
        final int exemptCount = exempt;
        final int matchedCount = matched;

        source.sendSuccess(() -> Component.literal(
                "=== 物理结构：共 %d（等待虚化 %d / 正在虚化 %d / 被豁免 %d）==="
                        .formatted(totalCount, waitingCount, fadingCount, exemptCount))
                .withStyle(ChatFormatting.GOLD), false);

        if (matchedCount == 0) {
            source.sendSuccess(() -> Component.literal("  （没有符合条件的结构）")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        // 显示的编号永远是「全量列表里的序号」，这样它和 /fade force <索引> 是同一套编号。
        // 用 list waiting 只看一部分时，编号会跳号 —— 这是刻意的。
        int index = 0;
        int displayed = 0;
        for (final FadeEntry entry : all) {
            index++;
            if (!matches(entry, filter)) {
                continue;
            }
            if (displayed >= MAX_LIST) {
                continue;
            }
            displayed++;
            final int shownIndex = index;
            source.sendSuccess(() -> Component.literal(formatEntry(shownIndex, entry)), false);
        }

        if (matchedCount > MAX_LIST) {
            final int hidden = matchedCount - MAX_LIST;
            source.sendSuccess(() -> Component.literal(
                    "  …还有 %d 条未显示（编号仍以全量列表为准）".formatted(hidden))
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return matchedCount;
    }

    private static boolean matches(FadeEntry entry, ListFilter filter) {
        return switch (filter) {
            case ALL -> true;
            case WAITING -> entry.waiting();
            case FADING -> entry.fading();
        };
    }

    private static String formatEntry(int index, FadeEntry entry) {
        final StringBuilder sb = new StringBuilder();
        sb.append('[').append(index).append("] ").append(entry.displayName());
        sb.append("  pos=").append(entry.posText());

        if (!AeroFadeConfig.isEnabled()) {
            sb.append("  —— 总开关已关闭");
            return sb.toString();
        }

        if (entry.exempt()) {
            sb.append("  —— 豁免（").append(entry.exemptReason()).append("）");
        } else if (entry.fading()) {
            sb.append("  —— 正在虚化 ").append(String.format("%.0f%%", entry.progress() * 100.0F));
            sb.append("，约 ").append(String.format("%.0f", entry.remainingSeconds())).append(" 秒后消失");
        } else {
            sb.append("  —— 等待中，约 ").append(String.format("%.0f", entry.remainingSeconds()))
                    .append(" 秒后开始虚化");
        }

        // 如果最近 10 秒内刚被重置过，把原因一并打出来。
        // 这正是「剩余时间一直不动」时最需要看到的信息。
        if (entry.lastResetAgoTicks() != Long.MIN_VALUE && entry.lastResetAgoTicks() <= 200) {
            sb.append("  ← 刚刚被重置：").append(entry.lastResetReason().description());
            if (entry.trackingPlayers() > 0) {
                sb.append("（").append(entry.trackingPlayerNames()).append("）");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ set

    private static int setIdle(CommandContext<CommandSourceStack> ctx) {
        final int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
        AeroFadeConfig.IDLE_SECONDS.set(seconds);
        return saveAndReply(ctx, "已设置：最后互动后 %d 秒开始虚化（已写入配置文件）".formatted(seconds));
    }

    private static int setDuration(CommandContext<CommandSourceStack> ctx) {
        final int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
        AeroFadeConfig.FADE_SECONDS.set(seconds);
        return saveAndReply(ctx, "已设置：从开始虚化到被删除耗时 %d 秒（已写入配置文件）".formatted(seconds));
    }

    private static int setMassThreshold(CommandContext<CommandSourceStack> ctx) {
        final double value = DoubleArgumentType.getDouble(ctx, "value");
        AeroFadeConfig.MASS_THRESHOLD.set(value);
        if (value <= 0.0D) {
            return saveAndReply(ctx, "质量阈值已关闭（0）—— 任何大小的结构都会虚化");
        }
        return saveAndReply(ctx, "已设置质量阈值：达到或超过 %.1f kpg 的结构不会虚化（已写入配置文件）".formatted(value));
    }

    private static int setEnabled(CommandContext<CommandSourceStack> ctx, boolean value) {
        AeroFadeConfig.ENABLED.set(value);
        if (value) {
            return saveAndReply(ctx, "总开关：虚化功能已【启用】（已写入配置文件）");
        }
        return saveAndReply(ctx, "总开关：虚化功能已【关闭】—— 不再虚化、不再删除任何结构。"
                + "所有倒计时也同时停止累计，所以重新启用时不会突然被清空。");
    }

    private static int setInterrupt(CommandContext<CommandSourceStack> ctx, Interrupt which) {
        final boolean value = BoolArgumentType.getBool(ctx, "value");
        final String label = switch (which) {
            case BLOCK_CHANGE -> {
                AeroFadeConfig.INTERRUPT_ON_BLOCK_CHANGE.set(value);
                yield "方块被放置/破坏";
            }
            case PLAYER_STANDING -> {
                AeroFadeConfig.INTERRUPT_ON_PLAYER_STANDING.set(value);
                yield "玩家站在结构上";
            }
            case MOVEMENT -> {
                AeroFadeConfig.INTERRUPT_ON_MOVEMENT.set(value);
                yield "结构自身发生运动";
            }
        };
        return saveAndReply(ctx, "已设置：%s —— %s（已写入配置文件）"
                .formatted(label, value ? "可以打断虚化" : "不再打断虚化"));
    }

    // ------------------------------------------------------------------ why（诊断）

    /**
     * 这个指令的存在理由：虚化"看起来没生效"时，从外面只能看到「剩余时间不动」，
     * 但原因可能是「被豁免」也可能是「计时器一直被刷新」—— 两者表现一样。
     * 这里把每一项判定条件都摊开，直接给出结论。
     */
    private static int why(CommandContext<CommandSourceStack> ctx) {
        final int index = IntegerArgumentType.getInteger(ctx, "index");
        final FadeEntry entry = resolve(ctx, index);
        if (entry == null) {
            return 0;
        }

        final CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("=== [%d] %s 的虚化判定 ===".formatted(index, entry.displayName()))
                .withStyle(ChatFormatting.GOLD), false);

        line(source, "位置", entry.posText());
        line(source, "质量", "%.1f kpg".formatted(entry.mass()));
        line(source, "铭牌", entry.named() ? "有 → 豁免虚化" : "无");
        line(source, "质量阈值", AeroFadeConfig.massThresholdEnabled()
                ? "%.1f kpg%s".formatted(AeroFadeConfig.MASS_THRESHOLD.get(),
                        entry.massExempt() ? " → 已达到，豁免" : " → 未达到")
                : "已关闭（0）");
        line(source, "总开关", AeroFadeConfig.isEnabled() ? "启用" : "关闭");
        line(source, "空闲时长", "%.1f 秒".formatted(entry.idleTicks() / 20.0D));
        line(source, "站在结构上的玩家", entry.trackingPlayers() == 0
                ? "无"
                : "%d 人（%s）".formatted(entry.trackingPlayers(), entry.trackingPlayerNames()));

        final double ago = entry.lastResetAgoSeconds();
        line(source, "最近一次重置", ago < 0.0D
                ? "（还没有记录）"
                : "%.1f 秒前 —— %s".formatted(ago, entry.lastResetReason().description()));

        final String verdict;
        if (!AeroFadeConfig.isEnabled()) {
            verdict = "总开关已关闭，不做任何虚化";
        } else if (entry.named()) {
            verdict = "被铭牌豁免，永远不会虚化";
        } else if (entry.massExempt()) {
            verdict = "质量达到阈值，被豁免";
        } else if (entry.fading()) {
            verdict = "正在虚化，约 %.0f 秒后消失".formatted(entry.remainingSeconds());
        } else {
            verdict = "等待中，约 %.0f 秒后开始虚化".formatted(entry.remainingSeconds());
        }
        source.sendSuccess(() -> Component.literal("结论：").append(
                Component.literal(verdict).withStyle(ChatFormatting.YELLOW)), false);

        if (entry.trackingPlayers() > 0 && AeroFadeConfig.INTERRUPT_ON_PLAYER_STANDING.get()) {
            source.sendSuccess(() -> Component.literal(
                    "⚠ 有人站在结构上，而「玩家站在结构上」正在打断虚化 —— "
                            + "这就是剩余时间不动的原因。想让它虚化，请走开，"
                            + "或执行 /fade set interrupt playerstanding false")
                    .withStyle(ChatFormatting.RED), false);
        }
        return 1;
    }

    private static void line(CommandSourceStack source, String label, String value) {
        source.sendSuccess(() -> Component.literal("  %s：%s".formatted(label, value)), false);
    }

    // ------------------------------------------------------------------ reset / force

    private static int resetAll(CommandContext<CommandSourceStack> ctx) {
        final ServerLevel level = ctx.getSource().getLevel();
        final FadeManager manager = FadeManager.get(level);
        int count = 0;
        for (final FadeEntry entry : manager.collect(level)) {
            if (manager.resetTimer(level, entry.id())) {
                count++;
            }
        }
        return reply(ctx, "已重置 %d 个结构的虚化倒计时".formatted(count));
    }

    private static int resetOne(CommandContext<CommandSourceStack> ctx) {
        final FadeEntry entry = resolve(ctx, IntegerArgumentType.getInteger(ctx, "index"));
        if (entry == null) {
            return 0;
        }
        final ServerLevel level = ctx.getSource().getLevel();
        FadeManager.get(level).resetTimer(level, entry.id());
        return reply(ctx, "已重置「%s」的虚化倒计时".formatted(entry.displayName()));
    }

    private static int force(CommandContext<CommandSourceStack> ctx, boolean fade, boolean all) {
        final ServerLevel level = ctx.getSource().getLevel();
        final FadeManager manager = FadeManager.get(level);

        if (all) {
            int count = 0;
            for (final FadeEntry entry : manager.collect(level)) {
                // 「立即虚化」不该作用在豁免结构上 —— 那等于绕过铭牌保护
                if (fade && entry.exempt()) {
                    continue;
                }
                final boolean ok = fade
                        ? manager.startFadingNow(level, entry.id())
                        : manager.deleteNow(level, entry.id());
                if (ok) {
                    count++;
                }
            }
            return reply(ctx, fade
                    ? "已让 %d 个结构立即进入虚化阶段".formatted(count)
                    : "已立即删除 %d 个结构".formatted(count));
        }

        final FadeEntry entry = resolve(ctx, IntegerArgumentType.getInteger(ctx, "index"));
        if (entry == null) {
            return 0;
        }
        if (fade && entry.exempt()) {
            ctx.getSource().sendFailure(Component.literal(
                    "「%s」被豁免（%s），不会虚化。要强制删除请用 /fade force delete"
                            .formatted(entry.displayName(), entry.named() ? "有铭牌" : "质量超过阈值")));
            return 0;
        }

        final UUID id = entry.id();
        final boolean ok = fade ? manager.startFadingNow(level, id) : manager.deleteNow(level, id);
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal("操作失败：结构可能刚刚被移除了"));
            return 0;
        }
        return reply(ctx, fade
                ? "「%s」已进入虚化阶段，约 %d 秒后消失".formatted(entry.displayName(), AeroFadeConfig.FADE_SECONDS.get())
                : "「%s」已被立即删除".formatted(entry.displayName()));
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 把 1-based 索引解析成一个结构。
     *
     * <p>注意：索引是在「当前这一刻」重新算出来的。因为列表按剩余时间排序，
     * 而上一条指令（比如 reset）会改变剩余时间，
     * 所以连续用索引时要重新看一眼 {@code /fade list}。
     */
    private static FadeEntry resolve(CommandContext<CommandSourceStack> ctx, int index) {
        final ServerLevel level = ctx.getSource().getLevel();
        final List<FadeEntry> entries = FadeManager.get(level).collect(level);
        if (index < 1 || index > entries.size()) {
            ctx.getSource().sendFailure(Component.literal(
                    "索引 %d 超出范围（当前共有 %d 个结构，用 /fade list 查看）"
                            .formatted(index, entries.size())));
            return null;
        }
        return entries.get(index - 1);
    }

    /**
     * 保存配置并回显。
     *
     * <p><b>必须显式 save()。</b>NeoForge 的 {@code ModConfigSpec.ConfigValue#set()}
     * 只改内存里的 {@code CommentedConfig} 和缓存值，<b>不会写文件</b>
     * （把它的字节码从头看到尾，一次 save 调用都没有）。
     *
     * <p>不显式保存的话，指令改的值只有"正常关服"时才会落盘 ——
     * 崩溃、强杀、断电就全丢了，表现就是「改了，下次打开还是默认值」。
     */
    private static int saveAndReply(CommandContext<CommandSourceStack> ctx, String message) {
        try {
            AeroFadeConfig.SPEC.save();
        } catch (final Throwable t) {
            AeroFade.LOGGER.warn("[aerofade] 保存配置文件失败：{}", t.toString());
            ctx.getSource().sendFailure(Component.literal(
                    "配置已在本局生效，但写入文件失败：" + t.getMessage()));
            return 0;
        }
        return reply(ctx, message);
    }
    private static int reply(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), true);
        // 指令回显只在 DEBUG 记一份：玩家/管理员已经在聊天框看到了，没必要再写一遍控制台
        AeroFade.LOGGER.debug("[aerofade] /fade: {}", message);
        return 1;
    }

    private FadeCommand() {
    }
}
