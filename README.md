# Hell's Kitchen · 地狱厨房

> Survival Contract 的第一个正式附属玩法（Addon）。Fabric 1.21.x 模组，依赖主模组 `survival-contract` 与 [Farmer's Delight Refabricated](https://modrinth.com/mod/farmers-delight-refabricated)。

---

## 中文

### 摘要

地狱厨房：红蓝两队 5 分钟内完成随机套餐、漏斗交付得分，先达指定组数者胜，伴随随机火灾与烧糊风险。

### 详细介绍

#### 地图与出生

开局维度自动重置回初始状态（避免上一局建造/破坏残留）。三种地图类型可选：

| 地图类型 | 出生分组 | 坐标示例 |
|---|---|---|
| 街角（默认） | 两队各占一个基地 | 西侧 (-62,0,12)、东侧 (17,0,9) |
| 水族馆 | 每队平分为 A/B 两组 | 西侧 A(-62,7,-205) / B(-62,0,-207) |
| 热气球 | 每队平分为 A/B 两组 | 西侧 A(35,1,-454) / B(19,1,-454) |

红蓝队随机分配东西两侧，西侧的交付漏斗归该队。

#### 基础规则

- **冒险模式 + 无限饱和**（管理员可用 `/gamemode` 自由切换）
- **锁 27 背包格**（`inv_slot_lock` 规则自动开启，只留快捷栏）
- **厨房维度禁刷怪 + 锁晴天 + 关闭火焰蔓延**（火焰只在点燃点燃烧，不外扩）
- **每人一把铁厨刀**：不可破坏、不可丢弃；丢弃即被清除并补发

#### 订单系统（核心玩法）

红蓝两队各有一组独立套餐，互相不可见。每组套餐 = 2~3 种成品菜肴 + 0~1 种饮品。

- **倒计时**：默认 5 分钟（可在 `kitchen.json` 配置）
- **计分**：单品得分由食材数量推导，整组完成额外 +30 分
- **超时**：未完成扣 20 分，立即刷新下一组
- **胜利条件**：率先完成指定组数（默认 3 组）立即获胜；设为 7 则"无尽模式"（玩到倒计时结束比总分）

#### 烧糊机制

- **停留超时**：成品在烹饪锅（`farmersdelight:cooking_pot`）停留超过 30 秒 → 打上红色"已烧糊"标签，交付不计分
- **制作概率**：每次新做出的成品 10% 直接烧糊
- **豁免**：南瓜派、曲奇、蘑菇煲等只能工作台合成的原版食物不走烧糊逻辑

#### 交付漏斗

地图预设 6 个交付漏斗（街角/水族馆/热气球各一对，分属东西基地）。两种交付方式：

1. **吸入**：成品掉落物落在漏斗上方/周围方块 → 自动消失并兑换
2. **放入**：右键把成品放进漏斗容器 → 该格物品立即消失并兑换

归属队伍判定：投掷者在队 → 其队伍；否则按漏斗所在基地。

#### 补给系统

发射器、箱子、铜箱（含自然氧化/涂蜡变体）、木桶、陷阱箱——任何被取出或弹出的物品，2 tick 内立即恢复初始快照。熔炉煤炭也会自动补货。

#### 随机火灾

每 30~45 秒，在地图的黄色混凝土 / 橙色陶瓦 / 蓝色带釉陶瓦中随机选一个，在其上方以十字形（中心 + 四邻，最多 5 格）生成火焰。火焰存续 60 秒后主动清除（原版火焰蔓延已关闭，火焰只在点燃点燃烧）。

#### 配置文件

`config/hell-kitchen/kitchen.json` 可自定义：

| 字段 | 默认 | 说明 |
|---|---|---|
| `batchSeconds` | 300 | 每轮套餐倒计时（秒） |
| `holdSeconds` | 30 | 成品在锅中停留多久算烧糊 |
| `burnChance` | 0.10 | 新成品直接烧糊概率（0~1） |
| `groupsToWin` | 3 | 需完成的套餐组数（7 = 无尽） |
| `setBonus` | 30 | 整组完成额外加分 |
| `timeoutPenalty` | -20 | 套餐超时扣分 |
| `dishes` / `drinks` | 内置 | 菜品池 / 饮品池（可增减、改分值） |
| `deliveries` | 内置 | 交付漏斗坐标与归属 |
| `spawns` | 内置 | 出生点坐标 |

---

## English

### Summary

Hell's Kitchen: Red vs Blue teams race randomized order sets in 5 minutes, deliver via hoppers — first to hit target count wins, amid random fires and burning dishes.

### Detailed Introduction

#### Map & Spawn

The kitchen dimension is automatically reset to a pristine copy before each game (no construction/block-breaking carries over). Three map layouts are selectable:

| Layout | Spawn Groups | Example Coords |
|---|---|---|
| Street (default) | One base per team | West (-62,0,12), East (17,0,9) |
| Aquarium | Each team split into A/B subgroups | West A(-62,7,-205) / B(-62,0,-207) |
| Balloon | Each team split into A/B subgroups | West A(35,1,-454) / B(19,1,-454) |

Red/Blue are randomly assigned to East/West; delivery hoppers on that side belong to that team.

#### Core Rules

- **Adventure mode + infinite Saturation effect** (admins can freely switch via `/gamemode`)
- **27 inventory slot lock** (`inv_slot_lock` auto-enabled, hotbar only)
- **Kitchen dimension: no mob spawns, forced clear weather, fire spread disabled** (fires burn only where ignited)
- **Iron chef knife per player**: unbreakable, undroppable; if discarded it vanishes and is immediately replaced

#### Order System (Core Gameplay)

Each team has its own independent order — they cannot see the opponent's. One order = 2–3 finished dishes + 0–1 drink.

- **Countdown**: 5 minutes by default (configurable)
- **Scoring**: Per-item score derived from ingredient count; completing a full set grants +30 bonus points
- **Timeout**: -20 points penalty, order refreshed instantly
- **Win condition**: First team to complete the target set count wins immediately; set to 7 for "Endless Mode" (play until SC timer ends, highest score wins)

#### Burning Mechanic

- **Hold timeout**: Finished dish sitting in a Cooking Pot (`farmersdelight:cooking_pot`) for more than 30 seconds → tagged burnt (red "已烧糊" label), not scorable on delivery
- **Craft probability**: 10% chance a freshly crafted finished dish is burnt outright
- **Exemption**: Pumpkin Pie, Cookie, Mushroom Stew and other workbench-only vanilla foods skip the burn logic

#### Delivery Hoppers

Six delivery hoppers are pre-placed (one pair per team per layout): Street, Aquarium, Balloon. Two delivery methods:

1. **Suck-in**: A finished dish item entity landing on or near a hopper → auto-consumed and scored
2. **Drop-in**: Right-click a finished dish into the hopper container → that slot's contents vanish and score

Team attribution: thrower's team if known; otherwise the hopper's base-side team.

#### Supply System

Dispensers, Chests, Copper Chests (all oxidized/waxed variants), Barrels, Trapped Chests — any item taken out or ejected from these containers is restored to its initial snapshot within 2 ticks. Furnace coal fuel is also auto-refilled.

#### Random Fire

Every 30–45 seconds, one random Yellow Concrete / Orange Terracotta / Blue Glazed Terracotta block on the map is chosen. A cross-shaped fire (center + 4 neighbors, up to 5 blocks) spawns above it. Each fire cluster self-extinguishes after 60 seconds (vanilla fire spread is disabled, fires only burn where ignited).

#### Config File

`config/hell-kitchen/kitchen.json` customization:

| Field | Default | Description |
|---|---|---|
| `batchSeconds` | 300 | Per-order countdown (seconds) |
| `holdSeconds` | 30 | Dish hold time before it burns |
| `burnChance` | 0.10 | Probability a fresh dish burns outright |
| `groupsToWin` | 3 | Target order sets (7 = endless) |
| `setBonus` | 30 | Bonus points for completing a full set |
| `timeoutPenalty` | -20 | Points deducted on order timeout |
| `dishes` / `drinks` | Built-in | Dish/drink pool (add/remove, change scores) |
| `deliveries` | Built-in | Delivery hopper coordinates & side |
| `spawns` | Built-in | Spawn coordinates |

---

## GitHub Wiki（精简版）

### What is Hell's Kitchen?

Hell's Kitchen is a **cooking-race addon** for Survival Contract (Fabric 1.21.x). Red vs Blue teams compete to craft and deliver dishes on a pre-built kitchen map.

**Requires**: `survival-contract` + `FarmersDelight Refabricated`

---

### Quick Setup

1. Install both jars (SC main mod + HK addon) into `mods/`
2. Install `FarmersDelight`
3. Launch → pick Hell's Kitchen style from the Survival Contract lobby

---

### Core Loop

```
Start → teleport to kitchen map (Adventure mode, hotbar-only inventory)
  ↓
Each team gets a secret order (2-3 dishes + optional drink)
  ↓
Craft using FarmersDelight cooking pots
  ↓
Deliver into team's hoppers (2 per map: west + east base)
  ↓
Score points +30 bonus per full set → next order
  ↓
First team to complete N sets (default 3) → WINS
  ↓
Timeout → -20 penalty, order refreshes
```

---

### Risk Factors

| Risk | Detail |
|---|---|
| 🔥 Random fires | Every 30-45s a cross-shaped fire spawns on concrete/terracotta. 60s lifetime. No spread |
| 🥘 Burning | Dish sitting in cooking pot >30s → unscorable red tag. 10% chance fresh dish burns outright |
| 📦 Supply reset | Dispensers/Chests auto-restock to initial snapshot. No hoarding |

---

### Maps

| Layout | Spawns |
|---|---|
| Street | 1 base per team |
| Aquarium | Each team split A/B |
| Balloon | Each team split A/B (1.0.7+) |

East/West assignment is random each game.

---

### Rules Automatically Applied

- ✅ Adventure mode (admins exempt)
- ✅ 27 inventory slot lock (hotbar only)
- ✅ Infinite Saturation effect
- ✅ No mob spawning / clear weather / fire spread off
- ✅ Iron chef knife per player (unbreakable, undroppable)

---

### Config

Path: `config/hell-kitchen/kitchen.json`

| Key | Default | Purpose |
|---|---|---|
| `batchSeconds` | 300 | Order countdown |
| `holdSeconds` | 30 | Burn threshold |
| `burnChance` | 0.10 | Direct burn chance |
| `groupsToWin` | 3 | Target sets (7 = endless) |
| `dishes` / `drinks` | — | Dish & drink pools |
| `deliveries` | — | Hopper coords |
| `spawns` | — | Spawn points |

---

### Integration with Survival Contract

- **Forces team mode** (no solo play)
- **Style options** (set via challenge book menu):
  1. Groups to win (1–6 / Endless)
  2. Map type (Street / Aquarium / Balloon)
- **Addon mode**: skips loading bar, instant teleport
- **End-game cleanup**: dimension reset, slot lock restored, orders cleared

---

### Commands

No custom commands. Use SC's standard team/admin commands (`/sc tp`, `/sc spectate`, etc.).

---

### Trivia 🎭

- The game host's name is "咯噔" (GeDeng), a running joke throughout all in-game messages
- The iron chef knife is themed after FarmersDelight's iron cleaver
- Some vanilla foods (Pumpkin Pie, Cookie) are **exempt** from burning — you can't make them in cooking pots anyway
- Endless Mode plays until SC's game timer expires — highest total score wins
