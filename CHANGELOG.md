# 更新日志（Aeronautics Fade）

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