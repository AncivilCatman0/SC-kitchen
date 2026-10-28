package com.hellkitchen.client;

import com.hellkitchen.HellKitchen;
import com.hellkitchen.net.KitchenNet;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 地狱厨房 HUD（需求 5）：仿照走私犯的界面——顶部时间条下方显示**本方套餐**内容
 * （仅成品食物：图标 + 名称 + 数量/进度 + 单品分值），并显示套餐倒计时与已完成组数。
 */
public final class KitchenHud {

	private static final int GAP = 16;

	private KitchenHud() {}

	public static void register() {
		Identifier id = Identifier.fromNamespaceAndPath(HellKitchen.MOD_ID, "kitchen_order");
		HudElementRegistry.addLast(id, (ctx, tickCounter) -> render(ctx));
	}

	private static void render(GuiGraphicsExtractor ctx) {
		KitchenClientState cs = KitchenClientState.INSTANCE;
		if (cs.entries.isEmpty()) return;
		var font = Minecraft.getInstance().font;

		// === 己方套餐：图标 + 名称 + 进度 + 分值，居中横排（与走私犯清单同位置）===
		java.util.List<KitchenNet.OrderEntry> list = cs.entries;
		int count = list.size();
		int[] widths = new int[count];
		int totalW = 0;
		for (int i = 0; i < count; i++) {
			KitchenNet.OrderEntry e = list.get(i);
			String name = itemDisplayName(e.itemId());
			String suffix = (e.need() > 1 ? " §7×" + e.need() : "")
					+ (e.delivered() > 0 ? " §7(" + e.delivered() + "/" + e.need() + ")" : "")
					+ (e.delivered() >= e.need() ? " §a✔" : "");
			widths[i] = 20 + 4 + font.width(name) + font.width(suffix)
					+ 4 + font.width("§6+" + e.scoreEach());
			totalW += widths[i];
		}
		totalW += GAP * Math.max(0, count - 1);
		int x = (ctx.guiWidth() - totalW) / 2;
		int y = 42;
		for (int i = 0; i < count; i++) {
			KitchenNet.OrderEntry e = list.get(i);
			String name = itemDisplayName(e.itemId());
			ItemStack stack = iconForItemId(e.itemId());
			if (!stack.isEmpty()) ctx.item(stack, x, y, 0);
			int tx = x + 20;
			ctx.text(font, Component.literal(name), tx, y + 4, 0xFFFFFFFF, true);
			tx += 4 + font.width(name);
			String suffix = (e.need() > 1 ? " §7×" + e.need() : "")
					+ (e.delivered() > 0 ? " §7(" + e.delivered() + "/" + e.need() + ")" : "")
					+ (e.delivered() >= e.need() ? " §a✔" : "");
			if (!suffix.isEmpty()) {
				ctx.text(font, Component.literal(suffix), tx, y + 4, 0xFFFFFFFF, true);
				tx += font.width(suffix);
			}
			ctx.text(font, Component.literal("§6+" + e.scoreEach()), tx + 4, y + 4, 0xFFFFFFFF, true);
			x += widths[i] + GAP;
		}

		// === 套餐倒计时（居中，己方行下）===
		int remain = cs.localRemainSeconds();
		String batch = String.format("§7套餐倒计时：%d:%02d", remain / 60, remain % 60);
		ctx.text(font, Component.literal(batch), (ctx.guiWidth() - font.width(batch)) / 2, y + 18,
				0xFFFFFFFF, true);

		// === 已完成组数 / 目标组数（含额外加成提示）；groupsToWin < 0 表示无尽模式 ===
		String target = cs.groupsToWin < 0 ? "§d无尽" : ("§f" + cs.groupsToWin);
		String sets = String.format("§7已完成套餐：§f%d§7/ %s §7（整组额外 §6+%d§7）",
				cs.completedSets, target, cs.setBonus);
		ctx.text(font, Component.literal(sets), (ctx.guiWidth() - font.width(sets)) / 2, y + 30,
				0xFFFFFFFF, true);

		if (cs.timeoutPulse) {
			String warn = "§c上一组套餐超时未完成，已扣分！";
			ctx.text(font, Component.literal(warn), (ctx.guiWidth() - font.width(warn)) / 2, y + 42,
					0xFFFFFFFF, true);
		}
	}

	/** 物品注册 id → 显示名 */
	private static String itemDisplayName(String itemId) {
		ItemStack stack = iconForItemId(itemId);
		return stack.isEmpty() ? "???" : stack.getHoverName().getString();
	}

	/** 物品注册 id → ItemStack（图标渲染用） */
	private static ItemStack iconForItemId(String itemId) {
		try {
			if (itemId == null || itemId.isEmpty()) return ItemStack.EMPTY;
			String ns = itemId.contains(":") ? itemId.substring(0, itemId.indexOf(':')) : "minecraft";
			String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
			var item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(ns, path));
			return (item == null || item == Items.AIR) ? ItemStack.EMPTY : new ItemStack(item);
		} catch (Exception e) {
			return ItemStack.EMPTY;
		}
	}
}
