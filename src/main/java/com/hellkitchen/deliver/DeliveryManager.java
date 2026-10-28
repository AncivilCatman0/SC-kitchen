package com.hellkitchen.deliver;

import com.hellkitchen.HellKitchen;
import com.hellkitchen.KitchenConfig;
import com.hellkitchen.KitchenMap;
import com.hellkitchen.burn.BurnTracker;
import com.hellkitchen.dish.DishPool;
import com.hellkitchen.order.OrderManager;
import com.survivalcontract.game.GameStateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * 交付漏斗（需求 7）：东侧 = 水族馆 (26.5, 0, -217.5) / 街角 (11.5, 0, 20.5)；
 * 西侧 = 水族馆 (-54.5, 0, -203.5) / 街角 (-68.5, 0, 23.5)。
 * 成品被漏斗「吸入」（掉落在漏斗上方/周围的掉落物）或「放入」（右键放进漏斗容器）即自动消失并兑换成分数。
 *
 * <p>烧糊的成品同样消失但不计分（需求 10）。
 */
public final class DeliveryManager {

	public static final DeliveryManager INSTANCE = new DeliveryManager();

	private DeliveryManager() {}

	public void tick(MinecraftServer server) {
		if (!OrderManager.INSTANCE.isActive()) return;
		ServerLevel level = KitchenMap.level(server);
		if (level == null) return;
		for (KitchenConfig.Delivery d : KitchenConfig.get().deliveryPoints()) {
			// 1) 吸入：掉落物落在漏斗上方/周围
			AABB box = new AABB(d.x - 0.85, d.y - 0.6, d.z - 0.85,
					d.x + 0.85, d.y + 1.9, d.z + 0.85);
			for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, box)) {
				consumeEntity(server, ie, d);
			}
			// 2) 放入：漏斗容器内
			BlockPos bp = BlockPos.containing(d.x, d.y, d.z);
			if (level.isLoaded(bp) && level.getBlockEntity(bp) instanceof Container c) {
				consumeContainer(server, c, d);
			}
		}
	}

	private void consumeEntity(MinecraftServer server, ItemEntity ie, KitchenConfig.Delivery d) {
		ItemStack st = ie.getItem();
		if (st.isEmpty()) return;
		String id = DishPool.itemId(st.getItem());
		if (id == null || !DishPool.isPoolItem(id)) return; // 只兑换池内成品/饮品
		int count = st.getCount();
		boolean burnt = BurnTracker.isBurnt(st);
		// 26.2：ItemEntity.getOwner() 返回 Entity（非 UUID）
		var owner = ie.getOwner();
		java.util.UUID ownerId = owner == null ? null : owner.getUUID();
		ServerPlayer scorer = ownerId == null ? null : server.getPlayerList().getPlayer(ownerId);
		GameStateManager.Team team = teamOf(server, ownerId, d.key);
		ie.discard(); // 吸入即消失
		if (burnt) {
			notifyBurnt(server, team, id);
			return;
		}
		OrderManager.INSTANCE.deliver(server, team, scorer, id, count);
	}

	private void consumeContainer(MinecraftServer server, Container c, KitchenConfig.Delivery d) {
		boolean changed = false;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack st = c.getItem(i);
			if (st.isEmpty()) continue;
			String id = DishPool.itemId(st.getItem());
			if (id == null || !DishPool.isPoolItem(id)) continue; // 只兑换池内成品/饮品
			int count = st.getCount();
			boolean burnt = BurnTracker.isBurnt(st);
			GameStateManager.Team team = OrderManager.INSTANCE.deliveryTeam(d.key);
			c.setItem(i, ItemStack.EMPTY); // 放入即消失
			changed = true;
			if (burnt) {
				notifyBurnt(server, team, id);
				continue;
			}
			OrderManager.INSTANCE.deliver(server, team, null, id, count);
		}
		if (changed) c.setChanged();
	}

	private static void notifyBurnt(MinecraftServer server, GameStateManager.Team team, String itemId) {
		Component god = Component.translatable("hell-kitchen.god", "咯噔");
		for (java.util.UUID id : GameStateManager.getInstance().getTeamMembers(team)) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) {
				p.sendSystemMessage(Component.translatable("hell-kitchen.msg.burnt_delivered", god));
			}
		}
		HellKitchen.LOGGER.info("[HK] 已烧糊的 {} 被交付 → 不计分", itemId);
	}

	/** 判定归属队伍：投掷者在队 → 其队；否则用漏斗所属基地队 */
	private GameStateManager.Team teamOf(MinecraftServer server, java.util.UUID ownerId, String deliveryKey) {
		if (ownerId != null) {
			ServerPlayer p = server.getPlayerList().getPlayer(ownerId);
			if (p != null) {
				GameStateManager.Team t = GameStateManager.getInstance().getPlayerTeam(p.getUUID());
				if (t == GameStateManager.Team.RED || t == GameStateManager.Team.BLUE) return t;
			}
		}
		return OrderManager.INSTANCE.deliveryTeam(deliveryKey);
	}
}
