package com.hellkitchen.win;

import com.hellkitchen.KitchenStyleProvider;
import com.survivalcontract.game.GameStateManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * 胜负判定（需求 11）：
 * <ul>
 *   <li>率先完成指定组数套餐的团队 → 立即获胜（{@code setForcedWinners} + {@code endGame(null)}）</li>
 *   <li>时间结束时 → 分数最高的团队获胜（由 SC 的时间耗尽分支按队伍总分判定）</li>
 * </ul>
 */
public final class WinCondition {

	private WinCondition() {}

	/** 宣布某队获胜并结束对局 */
	public static void declare(MinecraftServer server, GameStateManager.Team winner) {
		GameStateManager gsm = GameStateManager.getInstance();
		if (gsm.getCurrentPhase() != GameStateManager.GamePhase.PLAYING) return;
		List<UUID> winners = gsm.getTeamMembers(winner);
		gsm.setForcedWinners(winners);
		Component god = Component.translatable("hell-kitchen.god", "咯噔");
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			p.sendSystemMessage(Component.translatable("hell-kitchen.msg.win", god, teamName(winner)));
		}
		com.hellkitchen.HellKitchen.LOGGER.info("[HK] {} 队率先完成 {} 组套餐 → 获胜",
				teamName(winner), KitchenStyleProvider.groupsToWin());
		gsm.endGame(null);
	}

	public static String teamName(GameStateManager.Team team) {
		return switch (team) {
			case RED -> "红队";
			case BLUE -> "蓝队";
			default -> "无队";
		};
	}
}
