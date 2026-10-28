package com.hellkitchen.dish;

import com.hellkitchen.HellKitchen;
import com.hellkitchen.KitchenConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜品池（需求 5）：只使用**成品食物**（派、曲奇、烤串、三明治、碗装/盘装食物等）+ 4 种白名单饮品。
 *
 * <p>不引农夫乐事编译依赖：全部按注册名字符串解析，未安装的注册名自动跳过（缺失时打印一条清单）。
 * 积分由配置的 {@code ingredients}（合成配方食材种类丰富度）按公式推导，或直接使用配置的 {@code score}。
 *
 * <p>第九十四批：烧糊白名单——DishPool 里的**原版工作台合成食物**（minecraft:pumpkin_pie、cookie、mushroom_stew、
 * rabbit_stew、beetroot_soup）**只有工作台配方、没有厨锅配方**，这些食物不应该被烧糊。
 * 农夫乐事所有成品同时有工作台和厨锅两种配方，均可被烧糊。
 */
public final class DishPool {

	/** 池中一件成品：kind ∈ FOOD / BOWL / PLATE */
	public record Entry(String id, String kind, int score, int maxCount, Item item) {
		public boolean isServable() { return kind.equals("BOWL") || kind.equals("PLATE"); }
	}

	private static List<Entry> dishes = List.of();
	private static List<Entry> drinks = List.of();

	private DishPool() {}

	/** 依配置重建菜品池（未解析到的注册名跳过） */
	public static synchronized void reload() {
		KitchenConfig cfg = KitchenConfig.get();
		List<Entry> ds = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		for (KitchenConfig.Dish d : cfg.dishPoints()) {
			Item item = resolve(d.id);
			if (item == null) { missing.add(d.id); continue; }
			String kind = d.kind == null ? "FOOD" : d.kind.toUpperCase();
			int cap = kind.equals("BOWL") || kind.equals("PLATE") ? 1 : Math.max(1, Math.min(3, d.maxCount));
			ds.add(new Entry(d.id, kind, d.resolvedScore(), cap, item));
		}
		List<Entry> dk = new ArrayList<>();
		for (KitchenConfig.Drink d : cfg.drinkPoints()) {
			Item item = resolve(d.id);
			if (item == null) { missing.add(d.id); continue; }
			dk.add(new Entry(d.id, "DRINK", d.score, 1, item));
		}
		dishes = List.copyOf(ds);
		drinks = List.copyOf(dk);
		HellKitchen.LOGGER.info("[HK] 菜品池：成品 {} 种、饮品 {} 种（未安装/未解析 {} 个{}）",
				dishes.size(), drinks.size(), missing.size(),
				missing.isEmpty() ? "" : "：" + String.join(", ", missing));
	}

	private static Item resolve(String id) {
		try {
			if (id == null || id.isBlank()) return null;
			String ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
			String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
			Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(ns, path));
			// 注册表缺失项返回 air/缺失 → 视为未安装
			return (item == null || item == net.minecraft.world.item.Items.AIR) ? null : item;
		} catch (Exception e) {
			return null;
		}
	}

	public static List<Entry> dishes() { return dishes; }
	public static List<Entry> drinks() { return drinks; }

	/** 是否为池中的成品菜/饮品（按注册名） */
	public static boolean isPoolItem(String itemId) {
		return find(itemId) != null;
	}

	/** 按注册名查池中条目 */
	public static Entry find(String itemId) {
		for (Entry e : dishes) if (e.id().equals(itemId)) return e;
		for (Entry e : drinks) if (e.id().equals(itemId)) return e;
		return null;
	}

	/** 该物品是否为「成品食物」（用于烧糊判定：只针对菜品与饮品） */
	public static boolean isFinishedFood(Item item) {
		String id = itemId(item);
		return id != null && isPoolItem(id);
	}

	/** 物品 → 注册名 */
	public static String itemId(Item item) {
		try {
			return BuiltInRegistries.ITEM.getKey(item).toString();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 烧糊白名单（第九十四批）：是否为"只有工作台配方、没有厨锅配方"的食物。
	 * 这类食物不应该被烧糊——因为玩家没法用厨锅把它做出来，只能工作台合成。
	 * 农夫乐事所有成品（派/三明治/碗装等）同时有工作台和厨锅配方，不受此豁免。
	 */
	public static boolean isWorkbenchOnly(Item item) {
		String id = itemId(item);
		return id != null && WORKBENCH_ONLY.contains(id);
	}

	/** 只有工作台配方、没有厨锅配方的原版食物集合 */
	private static final java.util.Set<String> WORKBENCH_ONLY = java.util.Set.of(
			"minecraft:pumpkin_pie",
			"minecraft:cookie",
			"minecraft:mushroom_stew",
			"minecraft:rabbit_stew",
			"minecraft:beetroot_soup"
	);
}
