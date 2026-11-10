# 更新日志（Aeronautics Fade）

---

## 0.2.0 — 豁免会跟着「接触」和「包络」走了

**新增**

* **接触传播**（`propagateOnContact`，默认开启）：结构被豁免时，**贴在它身上**的东西也一起豁免 —— 停在命名航母甲板上的舰载机现在是安全的。
  **这同时修掉了强力胶那个问题**：强力胶只能粘相邻方块，所以被胶粘住的结构必然包围盒相接，现在会被自动连上。
* **包络传播**（`propagateOnEnvelope`，默认开启）：**整个位于**被豁免结构内部的东西也一起豁免 —— 停命名空中基地内部、与基地没有物理连接的飞艇不会再被单独清理。
* `contactTolerance`（默认 0.25 格）：多近算「接触」。
* `/fade why` 现在会把「接触」和「包络」作为独立原因显示，能看出是哪一条规则救了它。

**改动**

* 豁免传播变成两个阶段：先机制连接（轴承/关节/绳子/对接器），再几何关系（接触/包络）。机制连接在原因显示上优先。

**说明**

* 包络用的是包围盒，比较粗：从大型框架中间穿过去的东西也会被算作「在里面」。这是**安全方向**的取舍 —— 宁可多留，不可错删。
* 两条新规则只会让**更多**东西被豁免，**不会导致任何额外的删除**。

---
## 0.1.0 — Initial Release

First public release.

### Features

* Unnamed physics structures (Sable sub-levels) slowly **fade out and are deleted** after a configurable idle time, cutting server tick cost and save bloat.
* **Nameplate-named structures are exempt forever.** The exemption propagates across physical connections — bearings, ropes, docking connectors, handles, springs and merging glue — so a whole machine (arm, turret, bucket) is treated as one.
* Optional interaction interrupts: block placed/broken (**on**), player standing or riding (**on**), structure moving or rotating (**off** by default).
* Mass threshold exemption for large unnamed builds (disabled by default).
* Ghost-name protection: if a nameplate is destroyed, the structure correctly resumes its countdown.
* Full in-game control via `/fade`: `status`, `list`, `why`, `set`, `on` / `off`, `force fade`, `reset`. Settings written with `/fade set` persist to the config file.
* Client-side translucent fade rendering. **The client mod is optional** — without it structures simply stay visible until they are removed, and unmodded clients can still join the server.
* Only **loaded** structures count down. Timers persist across server restarts and pause on chunk unload.
* Quiet by design: no chat messages, no sounds, no particles. Event logging is off by default.

### Known issues

* Blocks that use their own texture alpha and render in the translucent layer — for example a disguise panel fitted with glass — **darken slightly at the very start of the fade**, then fade normally. Plain glass is unaffected. This is cosmetic only and never affects whether a structure is cleaned up correctly. To avoid it entirely, set `enableTransparency = false` in `.minecraft/config/aerofade-client.toml`.

### Requirements

* Minecraft **1.21.1** · NeoForge **21.1.x** · **Java 21**
* Required: **Create**, **Create: Aeronautics** — Aeronautics includes Sable and Simulated
* Works on any Sable physics structure, so other Sable-based contraptions are cleaned up too

---

## 0.1.0 — 首个公开版本

**功能**

* 无人互动的物理结构（Sable 子维度）在可配置的空闲时间后**逐渐淡出并被删除**，降低服务器 tick 开销、控制存档体积。
* **插了铭牌并写了名字的结构永久豁免。** 豁免会沿物理连接传播 —— 轴承、绳子、对接器、把手、弹簧、合并胶 —— 整台机器（机臂、炮塔、挖斗）被当作一个整体。
* 可选的互动打断：方块被放置/破坏（**默认开**）、玩家站在或骑在上面（**默认开**）、结构自身位移或旋转（**默认关**）。
* 质量阈值豁免，给没命名的大型建筑留保险（默认关闭）。
* **幽灵名字防护**：铭牌被破坏后，结构会正确恢复倒计时。
* 完整的游戏内指令控制：`/fade status`、`list`、`why`、`set`、`on`/`off`、`force fade`、`reset`。用 `/fade set` 改的设置会写入配置文件并跨重启保留。
* 客户端半透明淡出渲染。**客户端模组是可选的** —— 没装只是看不到淡出效果，结构照样会被正常清理；**原版客户端也能进服**。
* 只有**被加载**的结构才倒计时；计时跨服务器重启保留，区块卸载时暂停。
* **安静是设计原则**：没有聊天提示、没有音效、没有粒子；事件日志默认关闭。

**已知问题**

* 使用贴图自身透明度、且走半透明层的方块 —— 例如装了玻璃的伪装板 —— 在**虚化刚开始时会略微发暗**，之后正常淡出。纯玻璃不受影响。这只是观感问题，不影响结构是否被正确清理。想完全避免，把 `.minecraft/config/aerofade-client.toml` 里的 `enableTransparency` 设为 `false`。

**前置要求**

* Minecraft **1.21.1** · NeoForge **21.1.x** · **Java 21**
* 必需：**Create**、**Create: 航空学** —— 航空学自带 Sable 与 Simulated
* 适用于任何 Sable 物理结构，所以其它基于 Sable 的载具同样会被清理