package com.aerofade.fade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aerofade.AeroFade;
import com.aerofade.AeroFadeConfig;
import com.aerofade.network.AeroFadeNetwork;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * 虚化的核心状态机。每个维度（{@link ServerLevel}）一个实例。
 *
 * <h2>整体流程</h2>
 * <pre>
 *   每 checkIntervalTicks 刻跑一次：
 *     对每个物理结构(ServerSubLevel)：
 *       1. 被铭牌命名了？          -> 进度归零，本轮结束（永不虚化）
 *       2. 质量超过阈值？          -> 进度归零，本轮结束（仅在开关打开时）
 *       3. 有玩家站在上面？        -> 刷新「最后一次互动」
 *       4. 位姿变了？（可配置）    -> 刷新「最后一次互动」
 *       5. 空闲时长 = now - lastInteraction
 *          空闲 <= idleTicks      -> 进度 0
 *          否则                   -> 进度 = (空闲 - idleTicks) / fadeTicks
 *       6. 进度 >= 1              -> 从 Sable 里删除这个结构
 *       7. 否则                   -> 把进度同步给附近客户端（用于渲染透明度）
 * </pre>
 *
 * 「方块被放置/破坏」这一条不在这里轮询，而是由
 * {@code ServerLevelPlotMixin} 勾住 Sable 的 {@code ServerLevelPlot#onBlockChange}
 * 直接回调 {@link #onBlockChanged}，这样能拿到最权威的信号。
 */
public final class FadeManager {

    private static final Map<ResourceKey<Level>, FadeManager> MANAGERS = new HashMap<>();

    /** 进度变化小于这个值就不发包。1/64 已经足够平滑了。 */
    private static final float SYNC_EPSILON = 1.0F / 64.0F;

    /**
     * 「倒计时被重置」这类日志的节流窗口（tick）。默认 600 = 30 秒。
     *
     * <h2>为什么需要节流</h2>
     * 原来这条日志的判据是「重置原因和上一次不同」。听起来没问题，实际上会刷屏：
     * 一个<b>被铭牌命名</b>的船上如果还跑着机械，每一秒会发生
     * <pre>
     *   onBlockChanged  -> 原因变成「方块变化」
     *   tick 里的铭牌豁免 -> 原因变回「被铭牌命名」
     * </pre>
     * 两个原因互相覆盖，于是<b>每秒稳定打两行</b>。
     * 实测一次会话里 134 行日志有 91 行是这种噪声 —— 而本模组的定位是
     * 「装上之后几乎感觉不到它存在」，日志刷屏是明确违背这个原则的。
     *
     * <p>改成「原因变化 <b>且</b> 距上次日志超过这个窗口」之后，
     * 每个结构最多 30 秒一行，排查时照样看得出是谁在刷新计时器。
     */
    private static final long LOG_RESET_COOLDOWN_TICKS = 600L;

    /**
     * 「豁免沿物理连接传播」最多迭代几轮。
     *
     * <p>轴承可以让连接形成链：车身 -> 机臂 -> 挖斗。
     * 每一轮传播一层，所以轮数 = 能覆盖的最大挂载层级。
     * 取 4 足够覆盖现实中的机械，同时保证不会因为环形连接而无限循环
     * （每轮只要没有新增就提前退出）。
     */
    private static final int MAX_ATTACH_ROUNDS = 4;

    /** 垂直向下余量。 */
    private static final double STAND_SLACK_DOWN = 0.5D;
    /**
     * 垂直向上余量。
     *
     * <p>注意 {@code BoundingBox3ic} 的 max 是<b>闭区间</b>的方块坐标，
     * 那个方块占据的世界体积到 {@code max + 1}。所以站在最上层甲板时脚在
     * {@code maxY + 1}，这里再留 0.5 的富余。
     */
    private static final double STAND_SLACK_UP = 0.5D;

    private final Map<UUID, FadeState> states = new HashMap<>();

    private long lastCheck = Long.MIN_VALUE / 4;

    /** 总开关被关掉的那一次用来清一次客户端状态，避免每轮都发包。 */
    private boolean disabledAcknowledged;

    // ------------------------------------------------------------------ 生命周期

    public static FadeManager get(ServerLevel level) {
        return MANAGERS.computeIfAbsent(level.dimension(), key -> new FadeManager());
    }

    /** 服务器关掉时清空，避免把上一个存档的状态带到下一个存档。 */
    public static void clearAll() {
        MANAGERS.clear();
    }

    // ------------------------------------------------------------------ 外部交互信号

    /**
     * 某个结构里的方块被放置或破坏了。由 {@code ServerLevelPlotMixin} 调用。
     *
     * <p>这里判断的是 {@code interrupt.onBlockChange} 这个开关。
     * 之所以能直接判断：Sable 的 {@code ServerLevelPlot#onBlockChange} 只在
     * 方块真的发生变化时才会被调用，不区分是玩家放的还是活塞推的 ——
     * 对本模组来说「结构发生了变化」本身就是一种互动，所以不需要再细分。
     */
    public static void onBlockChanged(ServerSubLevel sub) {
        if (!AeroFadeConfig.INTERRUPT_ON_BLOCK_CHANGE.get()) {
            return;
        }
        if (sub == null || sub.isRemoved()) {
            return;
        }
        final ServerLevel level = sub.getLevel();
        final long now = level.getGameTime();
        final FadeManager manager = get(level);
        manager.markInteraction(level, sub.getUniqueId(), now, ResetReason.BLOCK_CHANGE);
    }

    /** 手动刷新某个结构的「最后一次互动」时间。 */
    public void markInteraction(ServerLevel level, UUID id, long now) {
        this.markInteraction(level, id, now, ResetReason.COMMAND);
    }

    /**
     * 刷新「最后一次互动」时间，并记录原因。
     *
     * <p>记录原因纯粹是为了可诊断：虚化不生效时最难受的就是「看不出是谁在刷新计时器」，
     * {@code /fade why} 会把这个原因打出来。
     */
    public void markInteraction(ServerLevel level, UUID id, long now, ResetReason reason) {
        final FadeState state = this.states.get(id);
        if (state != null) {
            // 只在「原因发生变化」时打一条日志，避免玩家站在船上时每刻刷屏。
            // 有了这条，服务器日志里就能直接看出是谁在刷新计时器。
            if (shouldLogReset(state, reason, now)) {
                AeroFade.LOGGER.info("[aerofade] 结构 {} 的虚化倒计时被重置，原因：{}",
                        id, reason.description());
            }
            state.lastInteraction = now;
            state.lastResetReason = reason;
            state.lastResetAt = now;
            if (state.progress != 0.0F) {
                state.progress = 0.0F;
                // 立刻把「归零」推给客户端，否则结构会保持半透明直到下一次同步周期
                state.lastSyncedProgress = Float.NaN;
            }
            state.fadeLogged = false;
        }
        FadeSavedData.get(level).setLastInteraction(id, now);
    }

    // ------------------------------------------------------------------ 主循环

    public void tick(ServerLevel level) {
        final long now = level.getGameTime();
        final long interval = AeroFadeConfig.CHECK_INTERVAL_TICKS.get();
        if (now - this.lastCheck < interval) {
            return;
        }
        this.lastCheck = now;

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        final List<ServerSubLevel> subLevels = container.getAllSubLevels();

        // ---- 第 1 步：清理已经不在世界里的结构 ----
        final Set<UUID> alive = new HashSet<>(Math.max(16, subLevels.size() * 2));
        for (final ServerSubLevel sub : subLevels) {
            alive.add(sub.getUniqueId());
        }

        final Iterator<Map.Entry<UUID, FadeState>> iterator = this.states.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, FadeState> entry = iterator.next();
            if (!alive.contains(entry.getKey())) {
                iterator.remove();
                AeroFadeNetwork.sendClear(level, entry.getKey());
            }
        }

        // ---- 第 2 步：逐个推进 ----
        final List<ServerSubLevel> toRemove = new ArrayList<>();
        final FadeSavedData savedData = FadeSavedData.get(level);
        final boolean enabled = AeroFadeConfig.isEnabled();

        // 总开关刚被关掉的那一次，把客户端上残留的虚化进度清干净
        if (!enabled && !this.disabledAcknowledged) {
            this.disabledAcknowledged = true;
            for (final UUID tracked : this.states.keySet()) {
                AeroFadeNetwork.sendClear(level, tracked);
            }
        } else if (enabled) {
            this.disabledAcknowledged = false;
        }
        // ---- 第 1.5 步：先算出「被豁免的结构」，再沿物理连接传播 ----
        //
        // 为什么需要传播：物理轴承会把「挂上去的那部分」变成**另一个子维度**。
        // 挖掘机的机臂、坦克的炮塔都是这样 —— 它们和宿主是两个独立的结构，
        // 而铭牌只命名了宿主。不做传播的话，机臂/炮塔就会被当成
        // 「没人要的垃圾」单独虚化掉，表现就是挖掘机开着开着机臂凭空消失。
        //
        // 所以先把「铭牌豁免」和「质量豁免」算出来，再沿轴承/关节连接传染开去。
        final Map<UUID, ResetReason> exempt = new HashMap<>();
        if (enabled) {
            for (final ServerSubLevel sub : subLevels) {
                final UUID id = sub.getUniqueId();
                final FadeState subState = this.states.computeIfAbsent(id,
                        key -> new FadeState(savedData.getLastInteraction(key, now)));
                if (NameplateCheck.isNamed(sub, subState, now)) {
                    exempt.put(id, ResetReason.NAMEPLATE);
                } else if (isMassExempt(sub)) {
                    exempt.put(id, ResetReason.MASS_THRESHOLD);
                }
            }

            // 一轮传播一层挂载：车身 -> 机臂 -> 挖斗
            //
            // ★ 必须【双向】传播，这是上一版没生效的原因：
            //   连接信息是记在**宿主**身上的 —— 宿主扫到轴承，拿到的是
            //   「被挂载子维度」的 UUID，而机臂自己的绘图区里没有轴承，
            //   它的连接集是**空的**。
            //   所以只写「非豁免结构看自己连接里有没有豁免的」永远不会生效：
            //   机臂根本没有连接可看。必须同时做「豁免结构把自己挂载的东西也豁免掉」。
            for (int round = 0; round < MAX_ATTACH_ROUNDS; round++) {
                boolean changed = false;
                for (final ServerSubLevel sub : subLevels) {
                    final UUID id = sub.getUniqueId();
                    final Set<UUID> links = AttachmentCheck.linkedSubLevels(
                            sub, this.states.get(id), now);
                    if (links.isEmpty()) {
                        continue;
                    }

                    if (exempt.containsKey(id)) {
                        // 方向 A：我已被豁免 -> 把挂在我身上的也一起豁免
                        for (final UUID mounted : links) {
                            if (!exempt.containsKey(mounted)) {
                                exempt.put(mounted, ResetReason.ATTACHED);
                                changed = true;
                            }
                        }
                    } else {
                        // 方向 B：我还没被豁免 -> 如果连接的另一端已豁免，那我也豁免
                        // （覆盖「连接信息恰好记在挂载侧」的情况，方向无所谓）
                        for (final UUID other : links) {
                            if (exempt.containsKey(other)) {
                                exempt.put(id, ResetReason.ATTACHED);
                                changed = true;
                                break;
                            }
                        }
                    }
                }
                if (!changed) {
                    break;
                }
            }
        }



        for (final ServerSubLevel sub : subLevels) {
            final UUID id = sub.getUniqueId();
            final FadeState state = this.states.computeIfAbsent(id,
                    key -> new FadeState(savedData.getLastInteraction(key, now)));

            // 2.0 总开关关闭
            //
            // 注意这里**仍然要把计时器往前推**（走 resetState），
            // 而不是简单地 return。否则「关掉一周再打开」的时候，
            // 所有结构都会发现自己已经一周没互动了，于是一夜之间被全部删光。
            if (!enabled) {
                this.resetState(level, sub, state, now, ResetReason.DISABLED);
                continue;
            }

            // 2.1 / 2.2 豁免判定
            //
            // 三种豁免都在第 1.5 步算好了：
            //   - 铭牌命名、质量达到阈值  -> 直接豁免；
            //   - 挂在被豁免的结构上      -> 沿轴承/关节传染过来的豁免
            //                                （机臂、炮塔就靠这个活下来）。
            final ResetReason exemption = exempt.get(id);
            if (exemption != null) {
                this.resetState(level, sub, state, now, exemption);
                continue;
            }

            // 2.3 有玩家站在上面
            if (AeroFadeConfig.INTERRUPT_ON_PLAYER_STANDING.get() && !playersOnStructure(sub).isEmpty()) {
                this.markInteraction(level, id, now, ResetReason.PLAYER_STANDING);
            }

            // 2.4 自身发生了运动（默认关闭）
            if (AeroFadeConfig.INTERRUPT_ON_MOVEMENT.get()) {
                final Pose3dc pose = sub.logicalPose();
                if (state.hasPose && !pose.withinTolerance(
                        state.lastPose,
                        AeroFadeConfig.MOVEMENT_TRANSLATION_TOLERANCE.get(),
                        AeroFadeConfig.MOVEMENT_ROTATION_TOLERANCE.get())) {
                    this.markInteraction(level, id, now, ResetReason.MOVEMENT);
                }
                state.lastPose.set(pose);
                state.hasPose = true;
            } else {
                state.hasPose = false;
            }

            // 2.5 计算进度
            final long idle = now - state.lastInteraction;
            final long idleTicks = AeroFadeConfig.idleTicks();

            float progress;
            if (idle <= idleTicks) {
                progress = 0.0F;
                state.fadeLogged = false;
            } else {
                final long fadeTicks = Math.max(1L, AeroFadeConfig.fadeTicks());
                progress = Math.min(1.0F, (idle - idleTicks) / (float) fadeTicks);

                if (!state.fadeLogged) {
                    state.fadeLogged = true;
                    if (AeroFadeConfig.LOG_FADE_EVENTS.get()) {
                        AeroFade.LOGGER.info(
                                "[aerofade] 结构 {} 开始虚化（已 {} 秒无互动），预计 {} 秒后消失。pos={}",
                                describe(sub), idle / 20, AeroFadeConfig.FADE_SECONDS.get(), describePos(sub));
                    }
                }
            }

            state.progress = progress;

            // 2.6 到点了就删
            if (progress >= 1.0F) {
                toRemove.add(sub);
                continue;
            }

            // 2.7 同步给客户端
            this.syncIfNeeded(level, sub, state, now, false);
        }

        // ---- 第 3 步：真正删除 ----
        for (final ServerSubLevel sub : toRemove) {
            if (AeroFadeConfig.LOG_FADE_EVENTS.get()) {
                AeroFade.LOGGER.info("[aerofade] 结构 {} 已完全虚化，正在删除。pos={}",
                        describe(sub), describePos(sub));
            }
            this.removeNow(level, sub);
        }
    }

    /** 走完整的删除路径：从 Sable 移除、清状态、清存档、通知客户端。 */
    private void removeNow(ServerLevel level, ServerSubLevel sub) {
        final UUID id = sub.getUniqueId();

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            container.removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
        }

        this.states.remove(id);
        FadeSavedData.get(level).remove(id);
        AeroFadeNetwork.sendClear(level, id);
    }

    // ------------------------------------------------------------------ 供 /fade 指令使用

    /**
     * 收集当前所有物理结构的快照，给 {@code /fade list} 用。
     *
     * <p>排序规则刻意做得确定：先按「距离消失还剩多少刻」升序，再按 UUID 兜底。
     * 这样最紧急的排在最前面，而且两次调用之间顺序不会乱跳 ——
     * 因为 {@code /fade force <索引>} 要靠这个顺序来定位结构。
     */
    public List<FadeEntry> collect(ServerLevel level) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return List.of();
        }

        final long now = level.getGameTime();
        final long idleTicks = AeroFadeConfig.idleTicks();
        final long fadeTicks = Math.max(1L, AeroFadeConfig.fadeTicks());
        final boolean massEnabled = AeroFadeConfig.massThresholdEnabled();
        final double massLimit = AeroFadeConfig.MASS_THRESHOLD.get();

        final List<FadeEntry> result = new ArrayList<>();

        for (final ServerSubLevel sub : container.getAllSubLevels()) {
            final UUID id = sub.getUniqueId();
            final FadeState state = this.states.get(id);

            final double mass = sub.getMassTracker().getMass();
            final boolean massExempt = massEnabled && mass >= massLimit;
            final boolean named = NameplateCheck.isNamedForQuery(sub, state, now);

            final long lastInteraction = state != null ? state.lastInteraction : now;
            final long idle = Math.max(0L, now - lastInteraction);

            float progress = 0.0F;
            long remaining;
            if (named || massExempt || !AeroFadeConfig.isEnabled()) {
                remaining = Long.MAX_VALUE;
            } else if (idle <= idleTicks) {
                remaining = idleTicks - idle;
            } else {
                progress = Math.min(1.0F, (idle - idleTicks) / (float) fadeTicks);
                remaining = (long) ((1.0F - progress) * fadeTicks);
            }

            final ResetReason reason = state != null ? state.lastResetReason : ResetReason.NEW;
            final long resetAgo = state != null && state.lastResetAt != Long.MIN_VALUE
                    ? Math.max(0L, now - state.lastResetAt)
                    : Long.MIN_VALUE;

            // 「谁站在上面」——这是诊断「计时被反复重置」时最关键的一条信息
            final List<ServerPlayer> onBoard = playersOnStructure(sub);
            final StringBuilder names = new StringBuilder();
            for (final ServerPlayer player : onBoard) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(player.getGameProfile().getName());
            }

            final var pos = sub.logicalPose().position();
            result.add(new FadeEntry(id, sub.getName(), pos.x(), pos.y(), pos.z(),
                    mass, named, massExempt, progress > 0.0F, progress, idle, remaining,
                    reason, resetAgo, onBoard.size(), names.toString()));
        }

        // 用显式 lambda 而不是 Comparator.comparingLong(...)：少一个 import，
        // 而且这个比较逻辑一眼能看懂
        result.sort((a, b) -> {
            final int byRemaining = Long.compare(a.remainingTicks(), b.remainingTicks());
            if (byRemaining != 0) {
                return byRemaining;
            }
            return a.id().toString().compareTo(b.id().toString());
        });
        return result;
    }

    /** 按 UUID 找一个还活着的结构；找不到返回 null。 */
    public ServerSubLevel find(ServerLevel level, UUID id) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        for (final ServerSubLevel sub : container.getAllSubLevels()) {
            if (sub.getUniqueId().equals(id) && !sub.isRemoved()) {
                return sub;
            }
        }
        return null;
    }

    /** 把某个结构的倒计时重置成「刚刚互动过」。 */
    public boolean resetTimer(ServerLevel level, UUID id) {
        if (find(level, id) == null) {
            return false;
        }
        this.markInteraction(level, id, level.getGameTime(), ResetReason.COMMAND);
        final FadeState state = this.states.get(id);
        if (state != null) {
            state.progress = 0.0F;
            state.fadeLogged = false;
        }
        return true;
    }

    /**
     * 立刻让它进入虚化阶段（进度从 0 起算）。
     *
     * <p>做法是把「最后一次互动」往前推到刚好跨过 idle 阈值的那一刻，
     * 而不是直接改 progress —— 这样后续的进度推进仍然由正常的状态机负责，
     * 不会出现「手工设了进度、下一轮又被算回去」的割裂。
     */
    public boolean startFadingNow(ServerLevel level, UUID id) {
        if (find(level, id) == null) {
            return false;
        }
        final long now = level.getGameTime();
        final FadeState state = this.states.computeIfAbsent(id,
                key -> new FadeState(FadeSavedData.get(level).getLastInteraction(key, now)));

        state.lastInteraction = now - AeroFadeConfig.idleTicks();
        state.progress = 0.0F;
        state.fadeLogged = false;
        state.lastSyncedProgress = Float.NaN;
        FadeSavedData.get(level).setLastInteraction(id, state.lastInteraction);
        return true;
    }

    /** 立刻删除一个结构（走和正常虚化完成完全一样的删除路径）。 */
    public boolean deleteNow(ServerLevel level, UUID id) {
        final ServerSubLevel sub = find(level, id);
        if (sub == null) {
            return false;
        }
        if (AeroFadeConfig.LOG_FADE_EVENTS.get()) {
            AeroFade.LOGGER.info("[aerofade] 结构 {} 被 /fade 指令立即删除。pos={}",
                    describe(sub), describePos(sub));
        }
        this.removeNow(level, sub);
        return true;
    }

    // ------------------------------------------------------------------ 玩家判定

    /**
     * 这个玩家是不是「站在这个结构上」。
     *
     * <h2>正确的 API：{@code SableCompanion#getTrackingOrVehicleSubLevel(Entity)}</h2>
     * 它读的是实体自己身上存的一个引用（{@code EntityMovementExtension#sable$getTrackingSubLevel()}），
     * 由 Sable 的碰撞/移动系统在实体被某个子维度支撑时写入。
     * <b>这是引擎自己的「这个实体正踩在哪个结构上」状态</b>，精确、且自动处理旋转与倾斜。
     *
     * <h2>⚠️ 两个「tracking」不是一回事，别搞混</h2>
     * <table>
     *   <tr><th>API</th><th>语义</th></tr>
     *   <tr><td>{@code ServerSubLevel#getTrackingPlayers()}</td>
     *       <td><b>网络同步范围</b>。Sable 的 {@code SubLevelTrackingSystem#collectPlayers}
     *           用 {@code shouldLoad(player, pos)} 填充，那是纯距离判定
     *           （{@code distanceSquared < SUB_LEVEL_TRACKING_RANGE²}）。
     *           离结构原点够近的玩家<b>全都</b>在里面 —— 站在船旁边几十格也算。</td></tr>
     *   <tr><td>{@code SableCompanion#getTrackingSubLevel(Entity)}</td>
     *       <td>per-实体的「踩在哪个子维度上」。<b>这才是本模组要的。</b></td></tr>
     * </table>
     * 之前用前者导致「玩家站在旁边也被判成站在船上」，倒计时被无限刷新、结构永不虚化。
     *
     * <h2>几何兜底</h2>
     * 万一 Sable 的 tracking 状态因为某些原因没写进去（比如刚落地的那一瞬），
     * 再加一道<b>很紧</b>的几何判定：玩家的脚必须落在结构的实际体积内或顶面上。
     * 水平方向不留余量，所以不会像原来那样误伤旁边的玩家。
     */
    private static boolean isPlayerOnStructure(ServerSubLevel sub, ServerPlayer player) {
        // ---- 主判定：引擎自己的 per-实体 tracking 状态 ----
        try {
            final var tracked = SableCompanion.INSTANCE.getTrackingOrVehicleSubLevel(player);
            if (tracked != null && tracked.getUniqueId().equals(sub.getUniqueId())) {
                return true;
            }
        } catch (final Throwable ignored) {
            // companion 不可用等异常情况：退到下面的几何判定
        }

        // ---- 兜底：几何判定（紧）----
        final BoundingBox3dc bounds = sub.boundingBox();
        if (bounds == null) {
            return false;
        }
        // 包围盒的 max 是闭区间方块坐标，那个方块占据的世界体积到 max + 1 为止。
        // 所以站在最上层甲板时脚在 maxY + 1。
        final double x = player.getX();
        final double y = player.getY();
        final double z = player.getZ();
        return x >= bounds.minX() && x <= bounds.maxX() + 1.0D
                && y >= bounds.minY() - STAND_SLACK_DOWN && y <= bounds.maxY() + 1.0D + STAND_SLACK_UP
                && z >= bounds.minZ() && z <= bounds.maxZ() + 1.0D;
    }

    /** 站在这个结构上的所有玩家（旁观者不算）。 */
    private static List<ServerPlayer> playersOnStructure(ServerSubLevel sub) {
        final List<ServerPlayer> result = new ArrayList<>();
        for (final ServerPlayer player : sub.getLevel().players()) {
            // 旁观者在穿墙飞行，不算「站在上面」
            if (player.isSpectator()) {
                continue;
            }
            if (isPlayerOnStructure(sub, player)) {
                result.add(player);
            }
        }
        return result;
    }
    // ------------------------------------------------------------------ 工具

    /**
     * 让一个结构「免疫」虚化：进度归零，并且<b>把最后互动时间推到当前</b>。
     *
     * <p>为什么必须推时间：想象一条被命名了 3 小时的船，玩家这时把铭牌敲了。
     * 如果豁免期间不刷新计时器，它下一轮检查就会发现自己「已经 3 小时没互动」，
     * 直接判定 100% 虚化并当场删除 —— 玩家会看到船在敲掉铭牌的瞬间凭空消失。
     * 把时间推到当前之后，倒计时是从「失去豁免」那一刻重新开始算的。
     *
     * <p>质量阈值豁免同理：重型结构被拆小之后也应该重新计时，而不是立刻消失。
     */
    /**
     * 这条「倒计时被重置」的日志现在该不该打。
     *
     * <p>两个条件都要满足：<b>原因确实变了</b>（不然日志没有信息量），
     * 且<b>距上次日志超过冷却窗口</b>（不然会在原因来回切换时刷屏，见
     * {@link #LOG_RESET_COOLDOWN_TICKS}）。
     *
     * <p><b>刻意只影响日志，不影响任何行为</b> —— 无论打不打日志，
     * 重置时间戳都是照常写入的。
     */
    private static boolean shouldLogReset(FadeState state, ResetReason reason, long now) {
        if (!AeroFadeConfig.LOG_FADE_EVENTS.get()) {
            return false;
        }
        if (state.lastResetReason == reason) {
            return false;
        }
        if (state.lastResetLoggedAt != Long.MIN_VALUE
                && now - state.lastResetLoggedAt < LOG_RESET_COOLDOWN_TICKS) {
            return false;
        }
        state.lastResetLoggedAt = now;
        return true;
    }

    /** 这个结构是否因为质量达到阈值而豁免。0 = 此功能关闭。 */
    private static boolean isMassExempt(ServerSubLevel sub) {
        if (!AeroFadeConfig.massThresholdEnabled()) {
            return false;
        }
        try {
            return sub.getMassTracker().getMass() >= AeroFadeConfig.MASS_THRESHOLD.get();
        } catch (final Throwable ignored) {
            return false;
        }
    }

    private void resetState(ServerLevel level, ServerSubLevel sub, FadeState state, long now, ResetReason reason) {
        final boolean wasFading = state.progress != 0.0F;

        if (shouldLogReset(state, reason, now)) {
            AeroFade.LOGGER.info("[aerofade] 结构 {} 的虚化倒计时被重置，原因：{}",
                    describe(sub), reason.description());
        }

        state.lastInteraction = now;
        state.lastResetReason = reason;
        state.lastResetAt = now;
        state.progress = 0.0F;
        state.fadeLogged = false;

        if (wasFading) {
            // 把 lastSyncedProgress 设成 NaN，强制下一行真的发出一个「进度归零」的包，
            // 否则结构会保持半透明直到下一次同步周期。
            state.lastSyncedProgress = Float.NaN;
            this.syncIfNeeded(level, sub, state, now, true);
        }

        FadeSavedData.get(level).setLastInteraction(sub.getUniqueId(), now);
    }

    private void syncIfNeeded(ServerLevel level, ServerSubLevel sub, FadeState state, long now, boolean force) {
        final float last = state.lastSyncedProgress;
        final boolean changed = Float.isNaN(last) || Math.abs(last - state.progress) >= SYNC_EPSILON;

        // 周期重发只在「确实有东西要显示」时才做。
        // 否则世界里每一个不虚化的结构都会每 10 秒白收一个包 —— 结构一多就是纯浪费。
        final boolean stale = state.progress > 0.0F
                && now - state.lastSyncedAt >= AeroFadeConfig.RESYNC_INTERVAL_TICKS.get();

        if (!force && !changed && !stale) {
            return;
        }

        state.lastSyncedProgress = state.progress;
        state.lastSyncedAt = now;
        AeroFadeNetwork.sendProgress(level, sub, state.progress);
    }

    private static String describe(ServerSubLevel sub) {
        final String name = sub.getName();
        return name == null || name.isBlank() ? sub.getUniqueId().toString() : name;
    }

    private static String describePos(ServerSubLevel sub) {
        final var position = sub.logicalPose().position();
        return String.format("(%.1f, %.1f, %.1f)", position.x(), position.y(), position.z());
    }
}
