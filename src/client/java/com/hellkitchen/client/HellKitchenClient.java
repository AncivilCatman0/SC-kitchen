package com.hellkitchen.client;

import com.hellkitchen.HellKitchen;
import com.hellkitchen.burn.BurnTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;

/**
 * 地狱厨房客户端入口：套餐 HUD（仿走私犯界面）+ 烧糊 tooltip 行。
 */
public class HellKitchenClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		HellKitchen.LOGGER.info("Hell's Kitchen client initializing...");
		KitchenClientState.INSTANCE.registerNetworkHandlers();
		KitchenHud.register();

		// 第九十四批：烧糊 tooltip 行——物品名下方追加独立的「已烧糊」标记（走翻译键）
		ItemTooltipCallback.EVENT.register((stack, context, player, lines) -> {
			if (BurnTracker.isBurnt(stack)) {
				lines.add(Component.translatable("hell-kitchen.tooltip.burnt"));
			}
		});
	}
}
