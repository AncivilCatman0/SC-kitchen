package com.hellkitchen.frame;

import com.hellkitchen.KitchenMap;
import com.survivalcontract.game.GameStateManager;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;

/**
 * 展示框锁定（需求 9）：本玩法地图内，物品展示框上的物品**无法左键取下**。
 * 走 Fabric 的攻击实体回调（左键 = 攻击实体），在厨房维度且对局进行中直接拦截。
 */
public final class FrameLock {

	private FrameLock() {}

	/** true = 该次左键应被拦截 */
	public static boolean shouldBlock(Player player, Entity entity) {
		if (!(entity instanceof ItemFrame)) return false;
		if (GameStateManager.getInstance().getCurrentPhase() != GameStateManager.GamePhase.PLAYING) return false;
		return entity.level().dimension().equals(KitchenMap.KITCHEN_LEVEL);
	}

	public static InteractionResult onAttack(Player player, Entity entity) {
		return shouldBlock(player, entity) ? InteractionResult.FAIL : InteractionResult.PASS;
	}
}
