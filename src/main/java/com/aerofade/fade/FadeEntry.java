package com.aerofade.fade;

import java.util.UUID;

/**
 * 一个物理结构在「查询」时的快照，给 {@code /fade list} / {@code /fade why} 这类指令用。
 *
 * <p>刻意做成不可变的 record：查完就走，不持有任何活对象的引用。
 */
public record FadeEntry(
        UUID id,
        String name,
        double x, double y, double z,
        double mass,
        /** 因为「被铭牌命名」而豁免虚化。 */
        boolean named,
        /** 因为「质量超过阈值」而豁免虚化。 */
        boolean massExempt,
        /** 是否已经进入虚化阶段（progress > 0）。 */
        boolean fading,
        /** 虚化进度 0~1。 */
        float progress,
        /** 距离最后一次互动过了多少刻。 */
        long idleTicks,
        /** 距离「开始虚化」或「被删除」还剩多少刻（豁免的结构为 Long.MAX_VALUE）。 */
        long remainingTicks,
        /** 最近一次倒计时被重置的原因。 */
        ResetReason lastResetReason,
        /** 距离那次重置过了多少刻；{@code Long.MIN_VALUE} 表示从未重置过。 */
        long lastResetAgoTicks,
        /** 当前站在这个结构上的玩家数量（Sable 的 tracking）。 */
        int trackingPlayers,
        /** 站在上面的玩家名字，逗号分隔；没有则为空串。 */
        String trackingPlayerNames) {

    /** 是否被豁免（永不虚化）。 */
    public boolean exempt() {
        return this.named || this.massExempt;
    }

    /** 是否正在「等待虚化」：没被豁免，也还没开始虚化。 */
    public boolean waiting() {
        return !this.fading && !this.exempt();
    }

    /** 结构显示名：没名字就用 UUID 前 8 位。 */
    public String displayName() {
        if (this.name != null && !this.name.isBlank()) {
            return this.name;
        }
        return this.id.toString().substring(0, 8);
    }

    public String posText() {
        return String.format("%.1f, %.1f, %.1f", this.x, this.y, this.z);
    }

    /** 剩余秒数；豁免结构返回 -1。 */
    public double remainingSeconds() {
        if (this.remainingTicks == Long.MAX_VALUE) {
            return -1.0D;
        }
        return this.remainingTicks / 20.0D;
    }

    /** 距离上次重置过了多少秒；从未重置返回 -1。 */
    public double lastResetAgoSeconds() {
        if (this.lastResetAgoTicks == Long.MIN_VALUE) {
            return -1.0D;
        }
        return this.lastResetAgoTicks / 20.0D;
    }

    /** 被豁免的原因文字。 */
    public String exemptReason() {
        if (this.named && this.massExempt) {
            return "有铭牌 + 质量 %.1f kpg".formatted(this.mass);
        }
        if (this.named) {
            return "有铭牌";
        }
        if (this.massExempt) {
            return "质量 %.1f kpg".formatted(this.mass);
        }
        return "";
    }
}
