package com.hellkitchen.tool;

import com.hellkitchen.dish.DishPool;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 冒险模式「放下菜肴」放行。
 *
 * <p>本模式强制冒险模式（需求 3），而部分菜肴（农夫乐事的派 / 盛宴方块，如
 * {@code roast_chicken_block}、{@code stuffed_pumpkin_block}、{@code apple_pie}）必须先
 * **右键放下**才能用碗盛装，冒险模式禁止放置方块 → 这类菜根本无法交付。
 *
 * <p>做法：右键方块时若手持**可放置菜肴**，在**同一 tick 内临时**把玩家切成生存模式以放行放置，
 * 下一 tick 由 {@link #revertPending(MinecraftServer)} 恢复冒险模式。
 * 白名单只覆盖菜肴类方块（农夫乐事方块 + 菜品池里的方块菜 + 原版蛋糕），
 * 不影响普通建材在冒险模式下的放置限制。
 *
 * <p>可行性依据：服务端的放置限制由 {@code Abilities.mayBuild}（冒险模式下为 false，
 * 由 {@code GameType.updatePlayerAbilities} 写入）判定；Fabric 的 {@code UseBlockCallback}
 * 在原版 {@code ServerPlayerGameMode.useItemOn} 的入口处触发，早于任何放置判定，
 * 因此此处改模式能真正放行本次放置。
 */
public final class PlaceGuard {

	/** 临时生存窗口：玩家 uuid → 恢复 tick（到点恢复冒险模式） */
	private static final Map<UUID, Integer> PENDING = new HashMap<>();

	private PlaceGuard() {}

	/** 是否为「可放置的菜肴」（必须是可放置方块，且属于菜肴范围） */
	public static boolean isPlaceableDish(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		Item item = stack.getItem();
		if (!(item instanceof BlockItem)) return false;
		String id = BuiltInRegistries.ITEM.getKey(item).toString();
		// 农夫乐事的派 / 盛宴方块（注册名均在 farmersdelight 命名空间）+
		// 菜品池里可放置的方块菜（含 minecraft:pumpkin_pie）+ 原版蛋糕
		return id.startsWith("farmersdelight:") || DishPool.isPoolItem(id) || id.equals("minecraft:cake");
	}

	/**
	 * 放行判定（纯函数，便于自测）：仅当**冒险模式**且手持**可放置菜肴**时才需要临时放行。
	 */
	public static boolean shouldAllowPlace(GameType mode, ItemStack held) {
		return mode == GameType.ADVENTURE && isPlaceableDish(held);
	}

	/**
	 * 冒险模式下手持可放置菜肴时，临时切生存以放行放置。
	 *
	 * @param player 玩家
	 * @param held   手持物品
	 * @param tick   当前服务端 tick
	 * @return true = 已临时切换为生存（原版放置流程会继续执行）
	 */
	public static boolean tryAllowPlace(ServerPlayer player, ItemStack held, int tick) {
		if (player == null) return false;
		if (!shouldAllowPlace(player.gameMode.getGameModeForPlayer(), held)) return false;
		player.setGameMode(GameType.SURVIVAL);
		PENDING.put(player.getUUID(), tick + 1); // 下一 tick 恢复（放置已在本次事件内完成）
		return true;
	}

	/** 该玩家是否正处于「临时生存放行」窗口内（自测用） */
	public static boolean isPending(ServerPlayer player) {
		return player != null && PENDING.containsKey(player.getUUID());
	}

	/**
	 * 恢复：到点的玩家若仍是生存模式 → 切回冒险模式。
	 * 只在「当前是生存」时恢复，避免误改管理员自己切走的模式（创造/观察者等）。
	 */
	public static void revertPending(MinecraftServer server) {
		if (server == null || PENDING.isEmpty()) return;
		int now = server.getTickCount();
		var it = PENDING.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			if (now < e.getValue()) continue;
			it.remove();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null) continue;
			if (p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
				p.setGameMode(GameType.ADVENTURE);
			}
		}
	}

	/** 清空临时窗口（对局结束 / 自测收尾） */
	public static void reset() { PENDING.clear(); }
}
