package com.aerofade.fade;

/**
 * 「倒计时为什么被重置了」的原因。
 *
 * <p>加这个东西是因为一个很现实的问题：虚化不生效时，从外面看只有两种可能 ——
 * 「没在重置」和「一直在重置」，而这两种表现一模一样（剩余时间显示不动）。
 *
 * <p>把它记下来之后，{@code /fade why &lt;索引&gt;} 就能直接说出到底是谁在刷新计时器，
 * 不用靠猜。
 */
public enum ResetReason {

    /** 刚登记（第一次看见这个结构）。 */
    NEW("刚登记"),

    /**
     * 结构上的方块发生了变化。
     *
     * <p>注意这**不区分是人还是机械** —— 这是刻意的：运转中的机械本身就是
     * 「这个结构还活着」的信号。详见 README 的「为什么机械动方块也算互动」。
     */
    BLOCK_CHANGE("结构上的方块发生了变化（玩家操作，或运转中的机械）"),

    /** 有玩家站在结构上（Sable 的 tracking 机制）。 */
    PLAYER_STANDING("有玩家站在结构上"),

    /** 结构自身发生了位移或旋转。 */
    MOVEMENT("结构自身发生了运动"),

    /** 因为被铭牌命名而豁免。 */
    NAMEPLATE("被铭牌命名（豁免）"),

    /** 因为质量达到阈值而豁免。 */
    MASS_THRESHOLD("质量达到阈值（豁免）"),

    /**
     * 因为「挂在了一个被豁免的结构上」而豁免。
     *
     * <p>物理轴承会把挂上去的那部分变成<b>另一个子维度</b> ——
     * 挖掘机的机臂、坦克的炮塔都是这样。它们和宿主是两个独立结构，
     * 铭牌只命名了宿主，所以如果不做传播，机臂/炮塔就会被当成垃圾虚化掉。
     */
    ATTACHED("挂在被豁免的结构上（轴承/关节连接）"),

    /** 总开关被关闭。 */
    DISABLED("总开关已关闭"),

    /** 被 {@code /fade reset} 手动重置。 */
    COMMAND("被指令手动重置");

    private final String description;

    ResetReason(String description) {
        this.description = description;
    }

    public String description() {
        return this.description;
    }
}
