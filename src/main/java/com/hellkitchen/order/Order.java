package com.hellkitchen.order;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.hellkitchen.KitchenConfig;
import com.hellkitchen.dish.DishPool;

/**
 * 一组套餐（需求 5）：2~3 种成品菜肴 + 0~1 种饮品。
 * 碗装/盘装与饮品数量固定 1；其余成品菜 1~3 份（不超过配置 maxCount）。
 * 积分 = Σ（单品分 × 数量）+ 整组加成。
 */
public class Order {

	/** 一个成品需求条目 */
	public static class Entry {
		public final String itemId;
		public final String displayKind;
		public final int need;
		public final int scoreEach;
		public int delivered;

		public Entry(String itemId, String displayKind, int need, int scoreEach) {
			this.itemId = itemId;
			this.displayKind = displayKind;
			this.need = need;
			this.scoreEach = scoreEach;
		}

		public boolean done() { return delivered >= need; }
	}

	private final List<Entry> entries;
	private final int itemScore;
	private final int setBonus;
	/** 本轮截止 tick（服务端 tick 计数） */
	private long deadlineTick;

	public Order(List<Entry> entries, int setBonus) {
		this.entries = entries;
		this.setBonus = setBonus;
		int s = 0;
		for (Entry e : entries) s += e.scoreEach * e.need;
		this.itemScore = s;
	}

	public List<Entry> entries() { return entries; }
	public int itemScore() { return itemScore; }
	public int setBonus() { return setBonus; }
	public int totalScore() { return itemScore + setBonus; }
	public long deadlineTick() { return deadlineTick; }
	public void setDeadlineTick(long tick) { this.deadlineTick = tick; }

	/** 本组是否已全部提交完成 */
	public boolean isComplete() {
		for (Entry e : entries) if (!e.done()) return false;
		return true;
	}

	/**
	 * 尝试交付一个物品：命中未满足的条目 → 记 1 件并返回该件分值；未命中返回 0。
	 */
	public int submit(String itemId) {
		for (Entry e : entries) {
			if (e.itemId.equals(itemId) && !e.done()) {
				e.delivered++;
				return e.scoreEach;
			}
		}
		return 0;
	}

	/** 依配置随机生成一组套餐（需求 5） */
	public static Order roll(KitchenConfig cfg, Random rnd) {
		List<DishPool.Entry> dishes = DishPool.dishes();
		List<DishPool.Entry> drinks = DishPool.drinks();
		List<Entry> out = new ArrayList<>();

		int kinds = dishes.isEmpty() ? 0 : 2 + rnd.nextInt(2); // 2~3 种
		kinds = Math.min(kinds, dishes.size());
		List<DishPool.Entry> pool = new ArrayList<>(dishes);
		java.util.Collections.shuffle(pool, rnd);
		for (int i = 0; i < kinds; i++) {
			DishPool.Entry d = pool.get(i);
			int count = 1;
			if (!d.isServable()) {
				int cap = Math.max(1, Math.min(3, d.maxCount()));
				count = 1 + rnd.nextInt(cap); // 1~cap
			}
			out.add(new Entry(d.id(), d.kind(), count, d.score()));
		}

		// 0~1 种饮品（仅 4 种白名单，见配置）
		if (!drinks.isEmpty() && rnd.nextInt(2) == 0) {
			DishPool.Entry dk = drinks.get(rnd.nextInt(drinks.size()));
			out.add(new Entry(dk.id(), "DRINK", 1, dk.score()));
		}

		if (out.isEmpty()) {
			// 极端兜底（配置为空或全未解析）：至少给一个苹果派
			out.add(new Entry("minecraft:pumpkin_pie", "FOOD", 1, 5));
		}
		return new Order(out, cfg.setBonus);
	}
}
