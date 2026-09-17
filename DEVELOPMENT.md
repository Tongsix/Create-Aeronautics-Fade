# 航空学：虚化 (Aeronautics: Fade)

一个「机械动力：航空学」附属模组。**没有被铭牌命名的物理结构**在长时间无人互动后会逐渐变透明，
最终被自动删除，以此缓解服务端与存档的性能压力。

典型用途：载具残骸与碎片随时间自行消失、无人认领的烂尾载具自动清理。

- **modid**：`aerofade`
- **目标**：Minecraft 1.21.1 / NeoForge 21.1.240
- **前置**：Create 6.0.10+、Sable 2.0+（航空学的物理引擎）、Simulated 1.3+（铭牌在这里）
- **软依赖**：Aeronautics（本模组不 import 它的类，任何用 Sable 的物理载具都适用）
- **作者**：
  - BiliBili@**上升_螺旋** —— 需求与设计
  - **DeepSeek-V4.1-Flash** —— API 逆向、实现、验证

> 关于署名：这个模组的每一处设计取舍（「运动」默认关闭、「0 = 关闭质量阈值」、
> 铭牌幽灵名字的兜底、宁可降级也不崩服）都是人定的方向；
> 而「Sable 的渲染到底走哪条路径」「铭牌怎么把名字写进子维度」这些结论，
> 是把 jar 拆开用 `javap` 一条条读字节码读出来的。
> 两者缺一不可，所以这里并列署名。

---

## 一、行为说明

### 虚化流程

对每一个物理结构（Sable 子维度 / `ServerSubLevel`）：

```
   ┌─ 被铭牌命名？ ──是──> 永不虚化
   │
   ├─ 质量超过阈值？─是──> 永不虚化（该功能默认关闭）
   │
   ├─ 有玩家站在上面？──> 刷新「最后互动时间」
   │
   ├─ 结构上的方块被放置/破坏？──> 刷新「最后互动时间」
   │
   └─ 空闲计时：
        空闲 <= 10 分钟          -> 完全不虚化
        空闲 >  10 分钟          -> 开始虚化，透明度随进度线性上升
        空闲 >= 15 分钟          -> 从世界里删除这个结构
```

默认时间线：**最后一次互动起 10 分钟开始虚化，15 分钟后彻底消失。**

### 什么算「互动」（可配置）

| 行为 | 默认 | 配置项 |
|---|---|---|
| 结构上的方块被放置或破坏 | ✅ 开启 | `interrupt.onBlockChange` |
| 有玩家站在结构上 | ✅ 开启 | `interrupt.onPlayerStanding` |
| 结构自身发生了位移/旋转 | ❌ 关闭 | `interrupt.onMovement` |

> 「运动」默认关闭是有原因的：停在水面上的船会一直轻微晃动，
> 开着就等于永不虚化，这个功能就废了。

### 什么算「被命名」

Simulated 的铭牌方块在被编辑时会把名字写进子维度（`SubLevel.setName`）。
所以判定就是「这个名字非空」。

**但原版有个坑**：铭牌被破坏时**不会**清空子维度的名字，会留下一个「幽灵名字」，
导致玩家把铭牌敲了、结构却永远不虚化。

因此本模组默认开启 `nameplate.verifyNameplateStillExists`：
定期扫描结构里是否真的还存在一个带名字的铭牌方块。扫描只遍历**方块实体**
（不遍历方块），并且只针对「当前有名字」的结构，每 5 秒一次，开销可以忽略。

---

## 二、配置

### 服务端：`<存档>/serverconfig/aerofade-server.toml`

单人游戏在 `.minecraft/saves/<存档>/serverconfig/`。

```toml
[general]
    # 总开关。关掉后完全不做虚化与删除，且所有倒计时停止累计
    # （所以不用担心「关了一周再打开，一夜之间全被删光」）
    enabled = true

[timing]
    # 多久没有互动后开始虚化（秒）
    idleSecondsBeforeFade = 600
    # 从开始虚化到完全透明并被删除的时长（秒）
    fadeDurationSeconds = 300
    # 服务端多久检查一次所有物理结构（tick）
    checkIntervalTicks = 20

[interrupt]
    onBlockChange = true
    onPlayerStanding = true
    onMovement = false
    movementTranslationTolerance = 0.05
    movementRotationTolerance = 0.02

[nameplate]
    verifyNameplateStillExists = true
    nameplateScanIntervalTicks = 100

[mass]
    # 质量达到或超过阈值的结构不会虚化。
    # 【0 = 此功能关闭】（默认）：不论多大的结构都会虚化。
    massThreshold = 0.0

[misc]
    resyncIntervalTicks = 200
    syncRadius = 512.0
    logFadeEvents = true
```

> **⚠️ 从旧版本升级**：早先的配置里有一个 `enableMassThreshold` 开关，
> 而且 `massThreshold` 的默认值是 `1000.0`。
> 现在语义改成了「0 = 关闭」，**旧配置里的 `1000.0` 会变成「生效的质量阈值」**。
> 升级后请把 `massThreshold` 手动改成 `0.0`（或删掉整个
> `<存档>/serverconfig/aerofade-server.toml` 让它重新生成）。

### 客户端：`.minecraft/config/aerofade-client.toml`

```toml
# 是否让正在虚化的结构真的变透明
enableTransparency = true
# 虚化进度 100% 时的 alpha（0 = 完全看不见）
finalAlpha = 0.0
# 远处的结构不做虚化渲染
fadeOutWithDistance = true
```

> 服务端下发的是「进度 0~1」，具体渲染成多透明由客户端决定，
> 这样服务器管理员不用操心玩家的视觉偏好。

### 网络包是可选的

网络包用 `optional()` 注册，**没装本模组的客户端也能进服**，
只是看不到透明效果（结构仍然会按计划被删除）。
对一个主打「减轻服务端压力」的模组来说，这个属性很重要。

---

## 三、游戏内调试指令 `/fade`

全部需要 OP（权限等级 2）。

**指令改的配置会立即生效并写入配置文件**，不用重启服务器。
（实现上就是 `ModConfigSpec.ConfigValue#set()`，NeoForge 负责落盘。）

### 查看当前配置

```
/fade status
/fade on     总开关：启用
/fade off    总开关：关闭
```

### 查询物理结构

```
/fade list            列出全部
/fade list waiting    只看「正在等待虚化」的
/fade list fading     只看「正在虚化」的
```

输出示例：

```
=== 物理结构：共 3（等待虚化 1 / 正在虚化 1 / 被豁免 1）===
[1] 我的小艇   pos=120.5, 64.0, -330.2  —— 正在虚化 62%，约 114 秒后消失
[2] a1b2c3d4  pos=118.0, 63.0, -328.0  —— 等待中，约 402 秒后开始虚化
[3] 主舰       pos=200.0, 70.0, -400.0  —— 豁免（有铭牌）
```

几个约定：

- 方括号里的编号是**全量列表里的序号**，和 `/fade reset <索引>`、`/fade force … <索引>` 是同一套编号
- 排序确定：先按「距离消失还剩多少刻」升序，再按 UUID 兜底。
  所以只要世界没变化，两次调用之间编号不会乱跳
- 用 `list waiting` 时编号会**跳号**（因为它仍然显示全量序号）——
  这是刻意的，否则那个编号没法拿去喂给 `force`
- 没被命名的结构用 UUID 前 8 位显示
### 诊断：为什么某个结构不虚化

```
/fade why <索引>
```

这个指令是专门为「虚化看起来没生效」准备的。
把该结构的每一项判定条件都摊开，并直接给结论：

```
=== [1] 我的小艇 的虚化判定 ===
  位置：120.5, 64.0, -330.2
  质量：1843.2 kpg
  铭牌：无
  质量阈值：已关闭（0）
  总开关：启用
  空闲时长：0.3 秒
  站在结构上的玩家：1 人（上升_螺旋）
  最近一次重置：0.3 秒前 —— 有玩家站在结构上
结论：等待中，约 20 秒后开始虚化
⚠ 有人站在结构上，而「玩家站在结构上」正在打断虚化 —— ...
```

`/fade list` 也会把「刚刚（10 秒内）被重置的原因」直接跟在每一行后面。

### 调节参数

```
/fade set enabled <true|false>                 总开关（等价于 /fade on / off）
/fade set idle <秒>                              多久没互动后开始虚化
/fade set duration <秒>                          从开始虚化到被删除的耗时
/fade set massthreshold <值>                     质量阈值，0 = 关闭此功能
/fade set interrupt blockchange <true|false>     方块被放置/破坏能否打断
/fade set interrupt playerstanding <true|false>  玩家站在结构上能否打断
/fade set interrupt movement <true|false>        结构自身运动能否打断
```

### 调试用（快速验证，不用等 15 分钟）

```
/fade reset <索引|all>          把倒计时重置为「刚刚互动过」
/fade force fade <索引|all>     立刻进入虚化阶段
/fade force delete <索引|all>   立刻删除
```

**一条典型的验证流程**：

```
/fade set idle 10            10 秒不互动就开始虚化
/fade set duration 30        虚化过程 30 秒
/fade list                   记住你想观察的那条的编号
/fade force fade 1           让它立刻进入虚化
                             然后盯着看 30 秒，观察透明度变化
```

> `/fade force fade` **不会作用在被豁免的结构上**（有铭牌 / 质量超阈值），
> 否则就等于绕过了铭牌保护。要强行清掉某个结构就用 `/fade force delete`。

---

## 四、实现要点

### 服务端

| 文件 | 职责 |
|---|---|
| `fade/FadeManager` | 核心状态机，每个维度一个实例 |
| `fade/FadeState` | 单个结构的运行时状态（最后互动时间、进度、上次位姿、命名缓存） |
| `fade/FadeSavedData` | 持久化到存档，按结构 UUID 记「最后互动时间」 |
| `fade/NameplateCheck` | 铭牌判定 + 幽灵名字的兜底扫描 |
| `mixin/ServerLevelPlotMixin` | 捕获「结构内方块变化」 |
| `network/*` | 把进度同步给附近客户端 |

**为什么「方块变化」用 Mixin 而不是 NeoForge 的 `BlockEvent`**：
物理载具的方块实际位于远离玩家的「绘图区」坐标系，玩家在世界里看到的位置
和方块真正所在的位置不是一回事，用事件坐标去反查结构容易漏判。
而 Sable 的 `ServerLevelPlot#onBlockChange` 是结构内部方块**真正发生变化**时必然被调用的地方
（玩家放、玩家拆、活塞推都走这里），既权威又便宜。

**为什么要持久化**：子维度会被卸载/重新加载，服务端重启后内存状态全没了。
不存盘的话每次重启都会把所有结构的倒计时重置一遍，那就永远删不掉。

**玩家站在上面**用的是 `ServerSubLevel#getTrackingPlayers()` —— Sable 会标记
「站在载具上但不在载具绘图区内」的实体为 tracking。

### 客户端渲染

这是整个模组最绕的一块，简单说：

1. 1.21.1 的五个区块着色器结尾都是
   `vec4 color = texture(...) * vertexColor * ColorModulator; fragColor = linear_fog(color, ...)`，
   而 `linear_fog` **保留 alpha**。所以调低 `ColorModulator` 的 alpha 就能让几何体透明 ——
   **不需要改任何着色器文件**。
2. 但 `solid / cutoutMipped / cutout` 这三种 RenderType 的**混合是关掉的**，alpha 不起作用；
   只有 `translucent` 开着 `SRC_ALPHA / ONE_MINUS_SRC_ALPHA`。
3. 区块几何体是**预烘焙**进 GPU 缓冲的，每帧只 `bind() + draw()`，
   所以没法靠「包一层 VertexConsumer 改顶点 alpha」来做。

#### 正确解法：打开混合，并用 GL 的「常量 alpha」当混合因子

```java
GL14.glBlendColor(1.0F, 1.0F, 1.0F, alpha);          // 常量 alpha
GlStateManager._enableBlend();
GlStateManager._blendFuncSeparate(CONSTANT_ALPHA, ONE_MINUS_CONSTANT_ALPHA,
                                  CONSTANT_ALPHA, ONE_MINUS_CONSTANT_ALPHA);
// 然后照常调用原方法绘制
```

正在虚化的结构**在它自己那一层照常画**，alpha 由上面这段 GL 状态决定：

```
结果 = 方块颜色 * alpha + 背景颜色 * (1 - alpha)
```

不依赖片元 alpha，也不依赖 `ColorModulator` 是否被着色器采纳 ——
所以对 Sable 的着色器覆盖、对 Veil、对光影包都更稳。

这个方案**不需要重建任何区块**，也不碰世界数据，而且**有 / 没有 Sodium 走的是同一条路径**
（`ReachAroundSubLevelRenderDispatcher` 继承 `VanillaSubLevelRenderDispatcher`，
渲染相关方法一个都没重写）。

#### ⚠️⚠️ 三个坑，全都是实测踩出来的，别再走回头路

这一节是整个模组**代价最大**的一段：三个看起来都很有道理的方案，
实测全都让「正在虚化的结构**彻底消失**」。
症状一模一样、且极具迷惑性 ——
**结构不虚化时完好无损，一开始虚化就凭空消失**，
而服务端日志、alpha 同步、绘制次数、异常记录**全部正常**。

##### 坑 1：靠 `RenderType` 的相等判断来决定「这一层画不画」

最初的实现是「跳过不透明层、等 `translucent` 那一趟再补画」，判据是
`renderType == RenderType.solid()` / `opaqueChunkLayers().contains(renderType)`。

- **类型假设本身就不可靠**：Sable 的 `LevelRendererMixin` 里有**两个**注入点
  （`sable$renderSubLevels` 和 `sable$renderSubLevelLayers`），
  后者在装了 Veil 时会把 `VeilRenderType$LayeredRenderType` 这种**包装类型**传下来，
  两个判断会同时为 false。
- **更致命的是「跳过」这个动作**：只要某条路径下判断成立、而半透明那一趟没跑到，
  结构就是「跳过了再也补不回来」。

> 实测结论：本开发环境（`Using Vanilla renderer mixins`）传下来的**确实是**
> `solid / cutoutMipped / cutout / translucent / tripwire` 这些具体类型。
> 也就是说这个判据在这里「碰巧成立」—— 但**依赖它才是真正的风险**。
> 所以现在**任何一层都不跳过**。

##### 坑 2：用 `SRC_ALPHA` 当混合因子

`SRC_ALPHA` 这个因子的值取自**片元 alpha**。
不透明层之所以看起来正常，是因为它**混合是关的**，alpha 根本不参与运算；
一旦打开混合，贡献就趋近 0 —— 结构**彻底消失**，而不是变透明。

压 `ColorModulator` 同样不可靠：Sable 会覆盖区块着色器
（启动日志里明确写着 *"Sable contains extensive shader overrides"*）。

##### 坑 3：关掉深度写入（`GlStateManager._depthMask(false)`）—— 最隐蔽的一个

当时的理由很充分：「结构是一次性混合上去的，没做前后排序，
开着深度写入会出现错误的内部遮挡」。

**实测结果是结构整个看不见** —— 因为不写深度，同一层里随后绘制的地形
会直接盖在它上面。定位它的过程值得记下来：
alpha 同步正确（日志实测 0.732~0.816）、五个层全都画到了、没有任何异常，
**只有把这一行去掉才恢复可见**。

> **取舍：宁可保留一点未排序的半透明内部遮挡，也绝不能让结构消失。**

##### 还有一个必须做的还原

`endFadeDraw()` 必须把混合函数**还原成默认的
`SRC_ALPHA / ONE_MINUS_SRC_ALPHA`**。因为 Sable 是在**一次
`renderSectionLayer` 调用里把所有结构画完的**：留着 `CONSTANT_ALPHA`
和我这个结构的 alpha 不管，同一层里随后绘制的**其它结构也会被一起淡化** ——
表现就是「一个结构虚化，旁边的结构跟着变透明」。
#### 坑 4：在「地形之前」绘制 —— 两种下场都是错的

实测确认：Sable 的**两个**注入点（`sable$renderSubLevels` / `sable$renderSubLevelLayers`）
都打在 `ShaderInstance.clear()` 上，也就是说**子维度是在地形之前绘制的**。
于是同一个结构只有两种下场，都不对：

| 深度写入 | 结果 |
| --- | --- |
| **关** | 随后绘制的地形直接盖住它 → **结构整个看不见**（这就是坑 3） |
| **开** | 它先写入深度 → 背后地形被深度测试挡掉 → **透过结构看到天空盒** |

**正确做法：把虚化结构延后到半透明层再画。**
那时不透明地形已经在帧缓冲里了，于是既能混合、又不会遮挡背后。

#### 但「延后」必须带保险，否则就是坑 1 重演

延后的代价是「本层不画」，一旦半透明层那趟没跑到，结构就再也补不回来。
所以用 `FadeRenderHelper#canDefer()` 把关：
**近 600 次绘制里确实见过半透明层，才允许延后**；
否则退化成「就地绘制」—— 宁可留一点遮挡，也绝不让结构消失。

`isTranslucentLayer()` 也刻意做得比 `renderType == RenderType.translucent()` 宽松
（额外看类型名里有没有 `translucent`），以免 Sable 传包装类型时判断失效。

#### 坑 5：单方块结构走的是完全不同的渲染路径

Sable 把「只有一个方块」的子维度单独优化成了 `VanillaSingleSubLevelRenderData`：

```java
if (renderData instanceof VanillaChunkedSubLevelRenderData chunked) {
    chunked.renderChunkedSubLevel(...);          // ← 钩子挂在这里
} else {
    this.singleBlockLayers.addLast(renderType);  // ← 单方块走这里，完全绕过
}
```

它们由 `renderAfterSections` 里的 `renderSingleBlock` 绘制，
并且**被批量合并成一次绘制** —— 既拿不到 `renderChunkedSubLevel` 这个钩子，
也没法按结构分别设 GL 状态。表现就是「**没有虚化过程，直接就没了**」。

修法：把 `VanillaSubLevelRenderDispatcher#isSingleBlock` 恒定成 `false`，
让单方块结构回到分区块路径，从而复用同一套虚化机制。
代价是放弃 Sable 的单方块优化（每个多占一点显存/构建开销）——
而碎片恰恰经常就是单方块结构，所以这个取舍值得。
#### 关于 `require = 0`

注入点是打在**别的模组**（Sable）内部实现上的：
一旦 Sable 改签名就会**静默跳过**，退化成「不变透明但照样会被删」，
而不是把整合包崩掉。这个取舍是刻意的。

#### 诊断开关

如果虚化渲染看起来不对，把客户端配置里的 `debugLogRenderPaths` 打开：

```toml
# .minecraft/config/aerofade-client.toml
debugLogRenderPaths = true
```

它每 5 秒会输出一行，内容是**这一段时间里见过的所有 RenderType**
（认不出来的会标成「未知类型！」）以及**每个虚化结构每帧被画了几次**。
`describe(RenderType)` 之所以要专门标出「未知类型」，
就是为了让「类型假设错了」这类问题**立刻暴露**，而不是被静默吞掉。

排查完记得关掉 —— 默认 `false`，符合本模组「安静」的原则。

---

## 五、已知限制（诚实版）

1. **方块实体（告示牌、铭牌等）不会跟着淡出。**
   它们的顶点是写进 `BufferSource` 之后**延迟统一 flush** 的，
   而 flush 发生在所有结构之后 —— 也就是说「在 per-结构的作用域里设 alpha」根本到不了 flush 那一刻。
   再加上大部分方块实体用的 RenderType 也是关混合的，做了也不会有效果。
   表现：结构淡出时，上面的告示牌会显得「浮在空中」，然后在结构被删除的瞬间一起消失。

2. **Flywheel / Create 的动态机械视觉不会淡出。**
   它们由 Flywheel 自己的实例化渲染器绘制，用的是自己的着色器，
   `ColorModulator` 和上面那套跳过/重画机制都够不着。
   同样是在结构删除的瞬间一起消失。

3. **cutout 类方块会「卡」在 alpha 0.11。**
   cutout 着色器里有 `if (color.a < 0.1) discard;`（mipped 是 0.5），
   所以 alpha 不能一路降到 0，否则这些方块会在中途整块消失（比淡出更难看）。
   本模组把虚化过程中的 alpha 下限钳在 0.11，进度走完时才归零。

4. **Iris 光影包可能让虚化失效。**
   光影包会替换着色器程序，`getUniform("ColorModulator")` 可能返回 null。
   代码里做了 null 检查并**优雅退化**为不虚化（结构照常被删除），不会崩。

5. **没有做前后排序**，重画时关掉了深度写入。所以虚化中的结构看起来是
   «半透明叠加» 的幽灵效果，而不是严格正确的画家算法排序。
   考虑到这本来就是「虚化」的视觉意图，这个取舍是可以接受的。

---

## 六、构建

```powershell
cd aeronautics-fade
.\gradlew.bat build        # 编译打包 -> build/libs/aerofade-1.0.0.jar
.\gradlew.bat runClient    # 起一个带 Create + Sable + 航空学的开发客户端
.\gradlew.bat runServer    # 起一个开发服务器
```

> **如果项目放在百度同步盘/OneDrive 这类会实时扫描的目录里**，
> Gradle 的 `.gradle` 项目缓存会被同步客户端锁住，报
> `fileHashes.lock (另一个程序正在使用此文件)`。
> 加上 `--project-cache-dir E:\gradle-cache\aerofade` 把缓存挪出同步目录即可。

### 首次构建要下什么

Gradle 9.2.1（~130MB）→ JDK 21（~195MB）→ NeoForge/NeoForm 并把 Minecraft 反编译重编译
（~30 分钟、几个 GB）。之后都是增量的，改一行代码约 6 秒。

### 关于 libs-maven/（重要）

`libs-maven/` 是一个**离线本地 Maven 仓库**，里面按标准布局放着 6 个构件：

```
com/simibubi/create/create-1.21.1/6.0.10-280/…-slim.jar
dev/ryanhcode/sable/sable-neoforge-1.21.1/2.0.5/…
dev/ryanhcode/sable/sable-sable_rapier-1.21.1/2.0.5/…
dev/ryanhcode/sable-companion/sable-companion-common-1.21.1/1.6.0/…
dev/eriksonn/aeronautics/aeronautics-neoforge-1.21.1/1.3.2/…
dev/simulated_team/simulated/simulated-neoforge-1.21.1/1.3.2/…
foundry/veil/veil-neoforge-1.21.1/4.3.2/…
```

**为什么需要它**：实测国内链路对 >5MB 的构件只有约 14 KB/s，
Gradle 下 Create 的 19MB jar 连续两次断在 7.7MB / 11.1MB
（`Premature end of Content-Length delimited message body`）。
这几个构件的 1.3.2 版本本来也没发布到 dev Maven，而玩家实际玩的就是 1.3.2。

**换一台网络好的机器**：把 `build.gradle` 里 `LocalBundled` 那个 `maven {}` 块删掉即可，
坐标本身就是官方坐标，会自动回落到官方源。

> ⚠️ 三个坑记录在这里，避免以后重复踩：
> 1. **不能用 `exclusiveContent` 包住 RyanHCode 仓库**。它会把 `dev.eriksonn.aeronautics`
>    这几个组**独占**给那一个仓库，Gradle 就再也不会去 `libs-maven` 里找 ——
>    报错会非常误导（"Searched in the following locations:" 只列一条）。用普通
>    `maven { content { includeGroup … } }` 就对了。
> 2. `libs-maven` 里的 POM 都是**精简版（不声明任何依赖）**，
>    所以每一项需要的传递依赖（veil、rapier 等）必须在 `build.gradle` 里显式写出来。
> 3. **`gradle.properties` 里不能放中文**。Gradle 读它用的是 ISO-8859-1
>    （Java `Properties` 的历史行为），中文会在<b>读取阶段</b>就变成乱码，
>    然后原样写进 jar —— 报错不会有，只是 Mod 列表里作者名变成 `ä¸å_èºæ`。
>    所以中文显示名、作者、credits 都直接写在 `neoforge.mods.toml` 里（那个文件按 UTF-8 读）。
>    另外 `neoforge.mods.toml` 会被 Groovy 模板引擎处理，**注释里也不要写美元符号加大括号**，
>    会被替换成 `null`。

---

## 七、验证状态

这一节严格区分「实际跑过并拿到证据」与「没验证过」。

### ✅ 已验证（有实测证据）

| 项目 | 证据 |
|---|---|
| **编译通过** | `gradlew build` → `BUILD SUCCESSFUL`，产出 `build/libs/aerofade-1.0.0.jar`（30.2 KB，13 个 class） |
| **jar 元数据正确** | `neoforge.mods.toml` 里所有 `${…}` 占位符都正确展开（`aerofade` / `1.21.1` / `[21.1.228,)` …） |
| **模组能被加载** | `runServer` 日志的 Mod List 里出现 `Aeronautics Fade 1.0.0 (aerofade)`，与 Create 6.0.10 / Aeronautics 1.3.2 / Simulated 1.3.2 / Sable 2.0.5 共存无冲突 |
| **Sable 物理后端可运行** | `Sable: Creating physics pipeline … using RapierPhysicsPipelineProvider` / `Rapier scene initialized`（三个维度各一次） |
| **服务端配置生成正确** | `run/server/config/aerofade-server.toml` 完整生成，中文注释与默认值都正确（UTF-8 无乱码） |
| **服务端 Mixin 真正生效** | 自检日志：`[aerofade] 自检通过：ServerLevelPlotMixin 已注入 Sable 的 ServerLevelPlot。` |
| **客户端渲染注入点真正生效** | 自检日志：`渲染注入自检：Mixin类已应用=true  WrapOperation注入点已生效=true` |
| **注入点位置精确** | 曾用 `require = 1` 跑过一次 `runClient`，**没有崩溃** —— 证明目标描述符与处理器签名完全匹配（`require = 0` 只是为将来 Sable 升级留的安全余量） |
| **`/fade` 全部 16 条子指令可执行** | 临时在 `ServerStartedEvent` 里把每条子指令都跑了一遍，日志逐条记录返回值，**零异常**。`list` 系在空世界返回 0、越界索引返回 0（正确走 `sendFailure` 而不是抛异常）；`set` 系返回 1 且配置真的写回了文件 |
| **质量阈值新语义生效** | 删掉旧配置后重新生成，`[mass]` 段变为 `massThreshold = 0.0`，`enableMassThreshold` 已消失 |
| **指令改的配置真的落盘了** | 用指令改了 5 个参数后**强杀所有 java 进程**（不给 NeoForge 关服保存的机会），再读配置文件，5 个值全部还在。修复前 `ConfigValue#set()` 只改内存不写文件，导致「改了下次打开还是默认值」 |

### ❌ 未验证（需要实际游戏操作）

| 项目 | 为什么没验证 | 怎么验 |
|---|---|---|
| **虚化的视觉效果好不好看** | 需要世界里存在物理载具、且它真的进入虚化阶段。无法在无头环境里造出来 | 进游戏造一条船，把 `timing.idleSecondsBeforeFade` 调到 10、`fadeDurationSeconds` 调到 20，然后别碰它，看它是不是逐渐变透明 |
| **虚化进度的时间线是否准确** | 同上（需要真实等待 15 分钟，或改配置缩短） | 同上；顺便验证 `[aerofade] 结构 … 开始虚化` 这条日志 |
| **铭牌豁免 / 幽灵名字兜底** | 需要真的放一个铭牌、命名、再敲掉 | 放铭牌 → 命名 → 等过 `idleSecondsBeforeFade`（应不虚化）→ 敲掉铭牌 → 应重新开始倒计时 |
| **结构真的被删除且不留垃圾** | 同上 | 等到进度 100%，看结构是否消失、`/sable` 里是否还有残留 |
| **NBT 持久化跨重启** | 需要跑满一个自动存档周期再重启 | 造结构 → 等一会儿 → 重启服务器 → 确认倒计时没有归零 |

### 已知的推断（逻辑链完整但未在屏幕上确认）

渲染部分基于两个**从 1.21.1 着色器源码里读出来的事实**：
五个区块着色器都以 `… * ColorModulator` 结尾，且 `linear_fog` **保留 alpha**
（`vec4(mix(inColor.rgb, fogColor.rgb, v*fogColor.a), inColor.a)`）；
再加上 Sable 自己就在 `renderChunkedSubLevel` 里用
`shader.getUniform("SableSkyLightScale")` 做 per-结构的 uniform 设置。

所以「改 `ColorModulator` 的 alpha → 结构变透明」这条链是完整的，
**注入点也已被证明落在了正确的调用位置上**，
但「最终画面看起来对不对」仍然需要一次肉眼确认。



---

## 八、排查：虚化不生效怎么办

最常见的现象是「`/fade list` 里剩余时间一直不动」。
这**不是没在跑**，而是**倒计时在被反复刷新**。用 `/fade why <索引>` 可以直接看出是谁干的。
### ⚠️ 曾经的一个坑：把「网络同步范围」当成了「站在船上」

最初的实现用 `ServerSubLevel#getTrackingPlayers()` 判断「有没有玩家站在结构上」。
**这是错的，而且是这个模组踩过的最大的一个坑。**

它的真实语义是**网络同步范围**。证据在 Sable 自己的字节码里
（`SubLevelTrackingSystem#collectPlayers`）：

```java
for (ServerPlayer player : level.players()) {
    if (shouldLoad(player, pos)) collection.add(player.getUUID());
}
// shouldLoad 是纯距离判定：
//   pos.distanceSquared(player.getX(), player.getY(), player.getZ())
//       < SableConfig.SUB_LEVEL_TRACKING_RANGE 的平方
```

也就是说**离结构原点够近的玩家全部都会进这个集合** ——
玩家站在船旁边、甚至几十格外，都会被判成「站在船上」，
于是倒计时每轮都被刷新，**结构永远不虚化**。
现象就是「`/fade list` 里剩余时间一直不动」。

### 关键：两个「tracking」是完全不同的东西

Sable 里有**两个都叫 tracking** 的概念，含义相反，这是最容易踩的坑：

| API | 实现 | 语义 |
|---|---|---|
| `ServerSubLevel#getTrackingPlayers()` | `SubLevelTrackingSystem#shouldLoad` → 纯距离判定 | **网络同步范围**（谁要收这个结构的包）。离得近的玩家全在里面 ❌ |
| `SableCompanion#getTrackingOrVehicleSubLevel(entity)` | `((EntityMovementExtension) entity).sable$getTrackingSubLevel()` | **实体自己存的「我正踩在哪个子维度上」**，由碰撞系统写入 ✅ |

**修法**：用后者。

```java
final var tracked = SableCompanion.INSTANCE.getTrackingOrVehicleSubLevel(player);
if (tracked != null && tracked.getUniqueId().equals(sub.getUniqueId())) {
    return true;   // 这个玩家真的站在这个结构上
}
```

选它而不是自己算几何，是因为它是**引擎自己的状态**：倾斜的船、旋转的甲板全都自动正确，
不用做坐标变换。几何判定（很紧，水平不留余量）只作为兜底，
覆盖「刚落地那一瞬 tracking 还没写入」之类的情况。

> **给后来改这个模组的人**：`getTrackingPlayers()` 是给网络同步用的，
> 跟「玩家是否在船上」没有任何关系。要判断站没站在结构上，
> 用 `SableCompanion#getTrackingOrVehicleSubLevel`。

### ⚠️ 曾经的一个坑：铭牌扫描用错了坐标空间（「铭牌被炸掉了却仍然豁免」）

`NameplateCheck#hasNamedNameplate` 最初是这么写的：

```java
for (int cx = plot.getChunkMin().x; cx <= plot.getChunkMax().x; cx++)
    for (int cz = plot.getChunkMin().z; cz <= plot.getChunkMax().z; cz++)
        LevelChunk chunk = plot.getChunk(new ChunkPos(cx, cz));   // 永远是 null
```

看起来完全合理，其实是错的，而且错得非常隐蔽：

| API | 坐标空间 |
| --- | --- |
| `LevelPlot#getChunkMin()` | **全局绘图区坐标** —— 返回 `plotPos.x << logSize`（例如 (16, 32)） |
| `LevelPlot#getChunkHolder(pos)` | **局部坐标** —— 第一件事就是越界检查 `0 <= pos < (1 << logSize)`，不在范围内直接 `return null` |

两者**不是同一个坐标空间**，所以 `getChunk(全局坐标)` **每一次都返回 null**。

**真正让这个 bug 藏了很久的，是我给它写的「保守兜底」**：

```java
if (examinedChunks == 0) return true;   // 拿不准 -> 当作"还被命名"
```

扫描一个 chunk 都没读到 → 兜底 → 永远返回「被命名」→
**`verifyNameplateStillExists` 从写下来的第一天起就没有工作过**。
铭牌被爆炸炸掉、幽灵名字还在，检查却说「还有铭牌」。

> **教训：一个静默返回「安全答案」的兜底，会把一个坏掉的功能变成一个看不见的功能。
> 这比直接崩溃更糟。**
> 保守兜底可以留，但**必须留下痕迹** —— 现在它触发时会打一条 WARN（每会话一次），
> 而正常情况这条 WARN 永远不会出现。

修法是改用 `plot.getLoadedChunks()` → `PlotChunkHolder#getChunk()`，
**完全绕开坐标空间的问题**（`getLoadedChunks()` 返回的就是已加载的 `PlotChunkHolder`）。

### 附属结构：豁免必须沿物理连接传播

物理轴承会把「挂上去的那部分」变成**另一个子维度(SubLevel)**。
挖掘机的机臂、坦克的炮塔、起重机吊臂都是这样 ——
**它们和宿主是两个独立的结构，而铭牌只命名了宿主。**

所以不做处理的话，机臂/炮塔会被当成「没人要的垃圾」单独虚化掉，
表现就是**挖掘机开着开着机臂突然消失**。

**数据来源**（已用 javap 核对字节码）：轴承方块实体自己就知道它驱动的是哪个子维度。

```java
// Simulated 的物理轴承
private UUID subLevelID;
public UUID getSubLevelID();                                   // ✓ public
public Iterable<SubLevel> sable$getConnectionDependencies();   // ✓ Sable 通用 API
```

**做法**（`AttachmentCheck` + `FadeManager#tick` 的第 1.5 步）：

1. 扫宿主绘图区里的方块实体，收集「与之相连的子维度 UUID」；
   主路径用 `SwivelBearingBlockEntity#getSubLevelID()`，
   另外也尝试 Sable 通用的 `BlockEntitySubLevelActor#sable$getConnectionDependencies()`。
2. 关系按**无向**处理 —— 轴承方块无论坐在宿主那侧还是挂载那侧，
   效果都是「两个结构一起被豁免」，方向不重要。
3. 先算出「直接豁免」集合（铭牌 / 质量阈值），
   再沿连接**传播 4 轮**（覆盖 车身 -> 机臂 -> 挖斗 这种多级挂载，
   每轮没有新增就提前退出，所以环形连接也不会死循环）。
4. `/fade why` 会显示新的原因：**「挂在被豁免的结构上（轴承/关节连接）」**。

> 扫描用的是 `plot.getLoadedChunks()` —— 和铭牌校验同一套经过验证的写法，
> 不会重蹈 `getChunkMin/Max` 坐标空间的覆辙。结果缓存在
> `FadeState#links`，刷新间隔复用 `nameplateScanIntervalTicks`。

**已知不足**：`/fade list` 里这类结构不会显示成「豁免」
（它的 `named` / `massExempt` 标志确实都是 false，豁免来自传播）。
看 `/fade why` 的「倒计时被重置的原因」才能看到真相。
### ⚠️ 曾经的一个坑：靠 `RenderType` 判断「这一层画不画」

见「客户端渲染」一节。这个坑的表现是
**同时有多个结构在虚化时，只有一两个渲染正确、其余的整个消失** ——
因为「跳过之后没有补回来」。现在改成永不跳过，见那一节的详述。
### 三种原因与对策

| `/fade why` 里的「最近一次重置」 | 含义 | 对策 |
|---|---|---|
| 有玩家站在结构上 | 你（或别人）正站在那条船上。这是设计行为，不是 bug | 走开；或 `/fade set interrupt playerstanding false` |
| 结构上有方块被放置/破坏 | 结构上的方块在变化。**注意这不限于玩家操作** —— 船上任何正在运转的 Create 机械（轴承、活塞、钻头、移动部件）只要动了方块就会算作互动 | `/fade set interrupt blockchange false` |
| 结构自身发生了运动 | 只在你手动开了 `onMovement` 时才会出现。停在水面上的船会一直轻微晃动 | `/fade set interrupt movement false` |
| 被铭牌命名 / 质量达到阈值 | 被**豁免**，永远不会虚化 | 敲掉铭牌；或 `/fade set massthreshold 0` |
| 总开关已关闭 | 之前 `/fade off` 过 | `/fade on` |

### 日志里也有线索

重置原因**发生变化**时，服务端会打一条：

```
[aerofade] 结构 <名字或UUID> 的虚化倒计时被重置，原因：有玩家站在结构上
```

所以直接翻 `logs/latest.log` 也能看出是谁在刷新计时器 —— 不用一直盯着 `/fade list`。

### 想立刻看到效果

```
/fade set idle 10
/fade set duration 30
/fade force fade 1
```

### 为什么「机械动方块」也算互动（这是刻意的，不是 bug）

当前实现挂在 Sable 的 `ServerLevelPlot#onBlockChange` 上，
它只告诉我们「结构里的方块变了」，**不区分是人还是机械**。

这是刻意的设计：**运转中的机械本身就是「这个结构还活着」的信号**。
废弃的载具迟早会因为燃料烧完而停机；而残骸、碎片、烂尾工程本来就没有机械在动。
所以「方块在变 → 先别删」是一个相当好用的近似，
而且**它的错误方向是安全的**：多留一会儿，远比把别人还在用的船删掉好。

技术上的原因也顺带成立：玩家在物理载具上拆方块时，坐标位于远离玩家的「绘图区」里，
用 NeoForge 的 `BlockEvent` 反查属于哪个结构容易漏判；
而 `onBlockChange` 是权威且便宜的信号源。

#### 唯一需要留意的反例：不需要燃料的动力源

| 动力源 | 会不会自己停 |
|---|---|
| 柴油机 / 蒸汽机（烧燃料） | ✅ 会停 |
| 发条轴承（弹簧） | ✅ 会停 |
| **风车轴承 / 水车** | ❌ **只要有风/有水就永远转** |
| **创造马达** | ❌ 永远转 |
| 传送带 / 传动轴 / 旋转部件 | 本来就不改方块，不参与这个判定 |

所以如果一条废弃载具上装了风车或水车，而它的机械恰好在动方块
（比如带着钻头一直挖、部署器一直放方块），它就会**永远不虚化**。

真遇到这种情况：

- `/fade set interrupt blockchange false` —— 改成只靠「玩家站在结构上」判定
- 或者给那条船放个铭牌命名 —— 铭牌就是「我要留着它」的明确声明

#### 这个信号两个方向都会不准

`onBlockChange` 回答的是「结构里的方块**变了没**」，而不是「结构**还活着没**」：

- **可能误判为「活着」**：某个机械只是周期性地改一下方块（比如红石钟推活塞），
  但结构其实早就没人管了。
- **可能误判为「死了」**：传动轴、传送带上的物品、旋转部件全靠 BlockEntity 自己 tick，
  **根本不调用 `setBlockState`**。一条全是传动轴的船，方块一直没变 → 看起来是死的。
  （不过真有人在用的时候，通常会站在船上或者放东西，
  那两条默认开启的判定会兜住。）

总的来说这是一个**廉价、安全方向、但不精确**的"活跃度"近似 —— 对这个模组的目标够用了。

---

## 九、设计原则：安静、不扰人

这个模组的定位是「装上之后几乎感觉不到它存在」。这一条约束了很多设计选择：

### 刻意不做的事

- **不发聊天消息**。不做「你的船将在 5 分钟后消失」这种预警 ——
  预警本身就是打扰。要在服务器上做公告，那是管理员的制度安排，不该由模组硬塞。
- **不加粒子、音效、界面**。虚化只是纯粹的透明度变化，没有任何额外特效。
- **成功时不写日志**。模组加载、Mixin 注入成功 —— 这些都不出声。
- **事件日志默认关闭**（`misc.logFadeEvents = false`）。
  服务器每天清垃圾如果都写一行日志，控制台很快就被刷满。

### 只在「出问题了」或「你主动问」时才出声

| 场景 | 行为 |
|---|---|
| Mixin 注入**失败** | 启动时 WARN，并明确说明哪个功能失效了 |
| 你执行 `/fade …` | 聊天框回复（只有执行者看得到） |
| 你执行 `/fade status` | 打印配置 + Mixin 注入状态 |
| 你执行 `/fade list` / `/fade why` | 按需查看结构状态 |
| 其它一切 | **静默** |

自检成功降到 DEBUG 之后，「注入到底成没成」改由 `/fade status` 按需回答 ——
**安静，但不等于不可知**。

### 性能上的「感觉不到」

- **渲染侧**：没有任何结构在虚化时，`ClientFadeCache.getAlpha()` 第一行就短路返回，
  开销为零。绝大多数存档里同时在虚化的结构都是 0 个。
- **服务端**：每 20 刻遍历一次「已加载的物理结构」列表，O(n) 且 n 很小。
- **铭牌扫描**：只遍历**方块实体**（不是方块），且只对「当前有名字」的结构做，5 秒一次。
- **网络**：只有正在虚化的结构才发包，且进度变化小于 1/64 时不发。
  不虚化的结构**一个包都不发**。

> **改这个模组的时候请守住这条**：
> 任何新增的日志、消息、特效，默认都应该是**关的**。

---

## 图标：两张，用途不同，别搞混

| 路径 | 尺寸 | 用途 |
| --- | --- | --- |
| `aerofade.png`（仓库根目录） | 1000×1000 | **只给 README 显示用**，不参与构建 |
| `src/main/resources/aerofade.png` | 256×256 | **会被打进 jar**，由 `neoforge.mods.toml` 的 `logoFile` 引用，出现在游戏内模组列表 |

改动时**两张要一起换**，否则会出现"GitHub 上是一个图、游戏里是另一个图"。

重建命令：

    gradlew.bat build --project-cache-dir <缓存目录>

产物在 `build/libs/aerofade-<版本>.jar`。