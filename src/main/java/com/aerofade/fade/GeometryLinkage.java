package com.aerofade.fade;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aerofade.AeroFadeConfig;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

/**
 * 按<b>几何关系</b>传播豁免：接触 与 包络。
 *
 * <h2>为什么需要</h2>
 * {@link AttachmentCheck} 只能顺着<i>机制</i>传播豁免（轴承、关节、绳子、对接器……），
 * 因为那些方块实体自己知道「我连着哪个子维度」。但有两种很常见的情况
 * <b>完全没有方块实体可以查</b>：
 *
 * <ul>
 *   <li><b>接触</b>：航母被铭牌命名了，停在甲板上的舰载机是<b>另一个结构</b>，
 *       两者之间没有任何连接方块 —— 但它显然不该被当成垃圾清理掉。
 *       强力胶也是这一类：它只能粘相邻方块，所以「粘在一起」必然表现为「包围盒相接」。</li>
 *   <li><b>包络</b>：空中基地被命名了，停在它<i>内部</i>、和它没有任何物理连接的飞艇
 *       同样不该被清理。</li>
 * </ul>
 *
 * <h2>数据从哪来</h2>
 * {@code SubLevel#boundingBox()} 返回结构的全局包围盒
 * （{@code dev.ryanhcode.sable.companion.math.BoundingBox3dc}）。
 * 有了所有结构的包围盒，两种关系都能直接算：
 * <ul>
 *   <li>接触 = 两个包围盒相交（允许 {@code contactTolerance} 的容差，处理浮点误差和贴面情况）</li>
 *   <li>包络 = 一个包围盒完全包含另一个</li>
 * </ul>
 *
 * <h2>刻意的取舍：宁可过度豁免</h2>
 * 包围盒是<b>粗粒度</b>的近似 —— 一艘悬停在建筑旁边但并未接触的船，
 * 包围盒也可能和建筑相交，于是被一起豁免。
 * 这是<b>安全方向</b>的错误：少删一个东西，比错删一个东西好得多。
 * 如果某个服务器觉得传染得太广，把 {@code propagateOnContact} 关掉即可。
 */
public final class GeometryLinkage {

    /** 和 {@code FadeManager.MAX_ATTACH_ROUNDS} 保持一致：一次传播一层。 */
    private static final int MAX_ROUNDS = 4;

    /**
     * 把已豁免结构的豁免，沿几何关系传播出去。
     *
     * <p>就地修改 {@code exempt}：被传染到的结构会以
     * {@link ResetReason#CONTACT} 或 {@link ResetReason#ENVELOPE} 记入。
     *
     * @param subLevels 当前所有结构（调用方已经取好，不要在这里再查一次）
     * @param exempt    已豁免的结构及原因；本方法会往里加
     */
    public static void propagate(List<ServerSubLevel> subLevels, Map<UUID, ResetReason> exempt) {
        // 一个都没豁免就没什么可传播的 —— 这是最常见的情况，先挡掉省一次全量遍历
        if (exempt.isEmpty()) {
            return;
        }

        final boolean onContact = AeroFadeConfig.PROPAGATE_ON_CONTACT.get();
        final boolean onEnvelope = AeroFadeConfig.PROPAGATE_ON_ENVELOPE.get();
        if (!onContact && !onEnvelope) {
            return;
        }

        final int count = subLevels.size();
        if (count < 2) {
            return;
        }

        // 包围盒一次性取好：后面要跑好几轮，逐轮重复取没有意义
        final UUID[] ids = new UUID[count];
        final BoundingBox3dc[] boxes = new BoundingBox3dc[count];
        for (int i = 0; i < count; i++) {
            final ServerSubLevel sub = subLevels.get(i);
            ids[i] = sub.getUniqueId();
            try {
                boxes[i] = sub.boundingBox();
            } catch (final Throwable ignored) {
                // 取不到就跳过这个结构，宁可漏传播也不要抛到 tick 外面去
                boxes[i] = null;
            }
        }

        final double tolerance = AeroFadeConfig.CONTACT_TOLERANCE.get();

        for (int round = 0; round < MAX_ROUNDS; round++) {
            boolean changed = false;
            for (int i = 0; i < count; i++) {
                final BoundingBox3dc boxA = boxes[i];
                if (boxA == null) {
                    continue;
                }
                for (int j = i + 1; j < count; j++) {
                    final BoundingBox3dc boxB = boxes[j];
                    if (boxB == null) {
                        continue;
                    }

                    final boolean exemptA = exempt.containsKey(ids[i]);
                    final boolean exemptB = exempt.containsKey(ids[j]);
                    // 两边状态相同 -> 没有可传播的方向（都豁免了，或都没豁免）
                    if (exemptA == exemptB) {
                        continue;
                    }

                    final ResetReason reason = classify(boxA, boxB, onEnvelope, onContact, tolerance);
                    if (reason == null) {
                        continue;
                    }

                    if (exemptA) {
                        exempt.put(ids[j], reason);
                    } else {
                        exempt.put(ids[i], reason);
                    }
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
    }

    /** 判断两个结构之间是哪种关系。包络优先 —— 包含必然也相交，但原因不同。 */
    private static ResetReason classify(BoundingBox3dc a, BoundingBox3dc b,
            boolean onEnvelope, boolean onContact, double tolerance) {
        if (onEnvelope && (contains(a, b) || contains(b, a))) {
            return ResetReason.ENVELOPE;
        }
        if (onContact && intersects(a, b, tolerance)) {
            return ResetReason.CONTACT;
        }
        return null;
    }

    /** {@code outer} 是否完全包住 {@code inner}。 */
    private static boolean contains(BoundingBox3dc outer, BoundingBox3dc inner) {
        return outer.minX() <= inner.minX() && outer.maxX() >= inner.maxX()
                && outer.minY() <= inner.minY() && outer.maxY() >= inner.maxY()
                && outer.minZ() <= inner.minZ() && outer.maxZ() >= inner.maxZ();
    }

    /**
     * 两个包围盒是否相交，允许 {@code tolerance} 的容差。
     *
     * <p>为什么要容差：两个结构「面贴面」放着时，包围盒可能只差一个浮点误差就判定为不相交。
     * 容差让这种情况仍然算接触。
     */
    private static boolean intersects(BoundingBox3dc a, BoundingBox3dc b, double tolerance) {
        return a.minX() <= b.maxX() + tolerance && a.maxX() >= b.minX() - tolerance
                && a.minY() <= b.maxY() + tolerance && a.maxY() >= b.minY() - tolerance
                && a.minZ() <= b.maxZ() + tolerance && a.maxZ() >= b.minZ() - tolerance;
    }

    private GeometryLinkage() {
    }
}
