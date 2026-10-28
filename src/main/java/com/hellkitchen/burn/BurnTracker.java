package com.hellkitchen.burn;

import com.hellkitchen.HellKitchen;
import com.hellkitchen.KitchenConfig;
import com.hellkitchen.KitchenMap;
import com.hellkitchen.dish.DishPool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 烧糊判定（需求 10 + 第九十四批白名单）：
 * <ul>
 *   <li>成品菜在烹饪锅停留超过配置秒数（默认 30s）→ 打上「已烧糊」标签，不再能兑换分数</li>
 *   <li>每次制作完成时 10% 概率直接「已烧糊」</li>
 *   <li>第九十四批：**只有工作台配方、没有厨锅配方**的原版食物（南瓜派/曲奇/蘑菇煲/兔肉煲/甜菜根汤）
 *       豁免烧糊——玩家没法用厨锅做出这些食物，烧糊逻辑跳过</li>
 * </ul>
 * 「已烧糊」以物品自定义名（§c已烧糊 §r 原名）呈现，便于玩家直接看到标签；
 * 同时客户端 tooltip 会在物品名下方追加独立的"§c已烧糊"行（见 HellKitchenClient）。
 */
public final class BurnTracker {

	/** 烧糊标签文本（自定义名前缀） */
	public static final String BURNT_LABEL = "已烧糊";

	/** 已知/被使用过的烹饪锅坐标（UseBlock 注册；只对玩家用到的锅计时，避免全图扫描） */
	private static final Set<BlockPos> POTS = new HashSet<>();
	/** 锅槽位 → 首次见到该成品的时间（gameTime） */
	private static final Map<String, Long> SLOT_FIRST_SEEN = new HashMap<>();
	/** 已掷过「直接烧糊」的槽位/实体（避免重复掷骰） */
	private static final Set<String> ROLLED = new HashSet<>();
	/** 掉落物实体 → 首次见到时间 */
	private static final Map<UUID, Long> ENTITY_FIRST_SEEN = new HashMap<>();
	/** 直接烧糊掷骰用随机源（Level.random 为 protected，不能直接访问） */
	private static final java.util.Random RND = new java.util.Random();

	private BurnTracker() {}

	public static void registerPot(BlockPos pos) {
		if (pos != null) POTS.add(pos.immutable());
	}

	public static void reset() {
		POTS.clear();
		SLOT_FIRST_SEEN.clear();
		ROLLED.clear();
		ENTITY_FIRST_SEEN.clear();
	}

	/** 是否已被标记「已烧糊」 */
	public static boolean isBurnt(ItemStack s) {
		if (s == null || s.isEmpty()) return false;
		if (!s.has(DataComponents.CUSTOM_NAME)) return false;
		return s.getHoverName().getString().contains(BURNT_LABEL);
	}

	/** 打上「已烧糊」标签（幂等） */
	public static void markBurnt(ItemStack s) {
		if (s == null || s.isEmpty() || isBurnt(s)) return;
		Component base = s.getHoverName();
		s.set(DataComponents.CUSTOM_NAME, Component.literal("§c" + BURNT_LABEL + " §r").append(base));
	}

	/** 每秒（20 tick）检查一次：锅内容物停留计时 + 掉落物新生成掷骰 */
	public static void tick(MinecraftServer server) {
		ServerLevel level = KitchenMap.level(server);
		if (level == null) return;
		if (server.getTickCount() % 20 != 0) return;
		long now = level.getGameTime();
		long holdTicks = Math.max(1, KitchenConfig.get().holdSeconds) * 20L;
		double burnChance = KitchenConfig.get().burnChance;

		// ---- 1) 烹饪锅内容物 ----
		Iterator<BlockPos> it = POTS.iterator();
		while (it.hasNext()) {
			BlockPos pos = it.next();
			if (!level.isLoaded(pos)) continue;
			if (!(level.getBlockEntity(pos) instanceof Container c)) { it.remove(); continue; }
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack st = c.getItem(i);
				// 第九十四批：工作台独配的原版食物跳过烧糊判定
				if (st.isEmpty() || !DishPool.isFinishedFood(st.getItem())
						|| DishPool.isWorkbenchOnly(st.getItem())) {
					SLOT_FIRST_SEEN.remove(key(pos, i));
					ROLLED.remove(key(pos, i));
					continue;
				}
				if (isBurnt(st)) continue;
				String k = key(pos, i);
				// 制作完成瞬间：10% 直接烧糊（该槽位首次出现成品时掷一次）
				if (ROLLED.add(k)) {
					if (burnChance > 0 && RND.nextDouble() < burnChance) {
						markBurnt(st);
						c.setChanged();
						continue;
					}
				}
				Long first = SLOT_FIRST_SEEN.get(k);
				if (first == null) {
					SLOT_FIRST_SEEN.put(k, now);
				} else if (now - first >= holdTicks) {
					markBurnt(st);
					c.setChanged();
					HellKitchen.LOGGER.info("[HK] {} 在锅中停留超过 {}s → 已烧糊",
							st.getItem(), KitchenConfig.get().holdSeconds);
				}
			}
		}

		// ---- 2) 成品掉落物：新出现时掷「直接烧糊」 ----
		for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, ALL)) {
			ItemStack st = ie.getItem();
			// 第九十四批：工作台独配的原版食物跳过烧糊判定
			if (st.isEmpty() || !DishPool.isFinishedFood(st.getItem())
					|| DishPool.isWorkbenchOnly(st.getItem()) || isBurnt(st)) {
				ENTITY_FIRST_SEEN.remove(ie.getUUID());
				continue;
			}
			if (ENTITY_FIRST_SEEN.putIfAbsent(ie.getUUID(), now) == null) {
				if (burnChance > 0 && RND.nextDouble() < burnChance) {
					markBurnt(st);
					ie.setItem(st);
				}
			}
		}
		if (ENTITY_FIRST_SEEN.size() > 4096) ENTITY_FIRST_SEEN.clear();
	}

	private static String key(BlockPos pos, int slot) {
		return pos.asLong() + "|" + slot;
	}

	/** 全维度扫描包围盒（仅已加载区块的实体参与遍历） */
	private static final AABB ALL = new AABB(
			new Vec3(-30_000_000, -64, -30_000_000), new Vec3(30_000_000, 320, 30_000_000));
}
