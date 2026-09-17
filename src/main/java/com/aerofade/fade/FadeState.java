package com.aerofade.fade;

import java.util.Set;
import java.util.UUID;

import dev.ryanhcode.sable.companion.math.Pose3d;

/**
 * 单个物理结构（Sable 子维度）的虚化运行时状态。
 *
 * <p>只存在于服务端内存里；需要跨重启保留的只有 {@link #lastInteraction}，
 * 那个存在 {@link FadeSavedData} 里。
 */
public final class FadeState {

    /** 最后一次「互动」的游戏刻（level.getGameTime()）。 */
    long lastInteraction;

    /** 最近一次算出来的虚化进度，0 = 完全实体，1 = 应当被删除。 */
    float progress;

    /**
     * 最近一次同步给客户端的进度，用来避免每刻都发包。
     *
     * <p>初值是 0 而不是 NaN：这样「从没虚化过」的结构不会在第一次检查时白发一个 0 进度包。
     * 需要强制发一次「归零」时（{@code resetState}），会把它设成 NaN。
     */
    float lastSyncedProgress = 0.0F;

    /** 最近一次发包的游戏刻。 */
    long lastSyncedAt;

    /** 上一次检查时的位姿，用于「运动」判定。 */
    final Pose3d lastPose = new Pose3d();

    boolean hasPose;

    /** 「是否被命名」的缓存结果与它被计算的时间。 */
    boolean named;

    long namedCheckedAt = Long.MIN_VALUE;

    /** 用于「刚开始虚化」的日志只打一次。 */
    boolean fadeLogged;

    /**
     * 最近一次「倒计时被重置」的原因和时间。
     *
     * <p>纯粹是为了可诊断性：虚化不生效时，{@code /fade why} 靠它直接说出是谁在刷新计时器。
     */
    ResetReason lastResetReason = ResetReason.NEW;

    long lastResetAt = Long.MIN_VALUE;

    /**
     * 最近一次「因为倒计时被重置而打日志」的时间。
     *
     * <p>用来给重置日志做节流，见 {@code FadeManager#shouldLogReset}。
     */
    long lastResetLoggedAt = Long.MIN_VALUE;

    /**
     * 和这个结构物理相连的其它结构（轴承/关节）。缓存，避免每轮都扫方块实体。
     *
     * <p>见 {@link AttachmentCheck}：挖掘机的机臂是独立子维度，
     * 豁免要靠这些连接传染过去。
     */
    Set<UUID> links = Set.of();

    long linksCheckedAt = Long.MIN_VALUE;

    FadeState(long lastInteraction) {
        this.lastInteraction = lastInteraction;
    }
}
