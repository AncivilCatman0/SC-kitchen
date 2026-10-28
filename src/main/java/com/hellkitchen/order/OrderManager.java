package com.hellkitchen.order;

import com.hellkitchen.KitchenConfig;
import com.hellkitchen.KitchenStyleProvider;
import com.hellkitchen.dish.DishPool;
import com.hellkitchen.net.KitchenNet;
import com.hellkitchen.win.WinCondition;
import com.survivalcontract.game.GameStateManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 套餐引擎（需求 5/6/11）：
 * <ul>
 *   <li>红蓝两队各自持有不同套餐，互相不可见（S2C 只下发本方）</li>
 *   <li>每轮套餐倒计时（默认 5 分钟）；做完全部内容并提交 → 立即刷新下一组</li>
 *   <li>时间结束仍未完成 → 扣分并刷新下一组</li>
 *   <li>完成指定组数 → 获胜（{@link WinCondition}）</li>
 * </ul>
 */
public final class OrderManager {

	public static final OrderManager INSTANCE = new OrderManager();

	private static final GameStateManager.Team[] ORDERED = {
			GameStateManager.Team.RED, GameStateManager.Team.BLUE};

	private final Map<GameStateManager.Team, Order> orders = new EnumMap<>(GameStateManager.Team.class);
	private final Map<GameStateManager.Team, Integer> completed = new EnumMap<>(GameStateManager.Team.class);
	private final Map<GameStateManager.Team, Long> deadline = new EnumMap<>(GameStateManager.Team.class);
	/** 交付漏斗 key → 该漏斗所属基地的队伍 */
	private final Map<String, GameStateManager.Team> deliveryTeams = new HashMap<>();
	/** 本轮刚超时的队伍（HUD 闪烁提示，每 20 tick 清一次） */
	private final Set<GameStateManager.Team> timeoutPulse = EnumSet.noneOf(GameStateManager.Team.class);

	private boolean active;
	private Random rnd = new Random();

	private OrderManager() {}

	// ==================== 开局 ====================

	public void start(MinecraftServer server) {
		KitchenConfig cfg = KitchenConfig.get();
		DishPool.reload();
		rnd = new Random(server.overworld().getRandom().nextLong());
		orders.clear();
		completed.clear();
		deadline.clear();
		deliveryTeams.clear();
		timeoutPulse.clear();

		long now = server.getTickCount();
		for (GameStateManager.Team t : ORDERED) {
			orders.put(t, Order.roll(cfg, rnd));
			completed.put(t, 0);
			deadline.put(t, now + (long) cfg.batchSeconds * 20L);
		}
		// 漏斗归属：优先用 Delivery#side 显式指定（热气球两侧漏斗 x 都 >0），未指定时按 x<0（西侧）判定
		GameStateManager.Team west = KitchenStyleProvider.aquariumTeam();
		if (west != GameStateManager.Team.RED && west != GameStateManager.Team.BLUE) {
			west = GameStateManager.Team.RED;
		}
		GameStateManager.Team east = west == GameStateManager.Team.RED
				? GameStateManager.Team.BLUE : GameStateManager.Team.RED;
		for (KitchenConfig.Delivery d : cfg.deliveryPoints()) {
			GameStateManager.Team team = "west".equals(d.side) ? west
					: "east".equals(d.side) ? east
					: (d.x < 0 ? west : east);
			deliveryTeams.put(d.key, team);
		}

		active = true;
		HellKitchenLog("套餐已生成：红队 {}｜蓝队 {}",
				describe(orders.get(GameStateManager.Team.RED)), describe(orders.get(GameStateManager.Team.BLUE)));
		broadcast(server);
	}

	public void reset() {
		active = false;
		orders.clear();
		completed.clear();
		deadline.clear();
		deliveryTeams.clear();
		timeoutPulse.clear();
	}

	/**
	 * 对局结束：停用引擎并给每位在线玩家下发一次「空套餐」同步，令客户端 HUD 立即收起。
	 * <p>否则对局结束回到大厅后，客户端仍保留上局的套餐缓存（{@code KitchenClientState.entries}
	 * 非空），顶部套餐 UI 会一直挂在屏幕上不消失。
	 */
	public void stop(MinecraftServer server) {
		if (server != null) {
			KitchenConfig cfg = KitchenConfig.get();
			KitchenNet.OrderSyncPayload clear = new KitchenNet.OrderSyncPayload(
					List.of(), 0, 0, KitchenStyleProvider.groupsToWinForHud(), cfg.setBonus, true, false);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				KitchenNet.send(p, clear);
			}
		}
		reset();
	}

	public boolean isActive() { return active; }

	// ==================== 每 tick ====================

	public void tick(MinecraftServer server) {
		if (!active) return;
		long now = server.getTickCount();
		for (GameStateManager.Team t : ORDERED) {
			Long dl = deadline.get(t);
			if (dl == null || now < dl) continue;
			Order o = orders.get(t);
			if (o != null && !o.isComplete()) {
				applyTimeout(server, t);
				timeoutPulse.add(t);
			}
			refresh(server, t, now);
		}
		if (now % 20L == 0L) {
			broadcast(server);
			timeoutPulse.clear();
		}
	}

	// ==================== 交付 ====================

	/**
	 * 交付成品（需求 7）：命中本方套餐的件数 → 加分；完成整组 → 额外加分 + 立即刷新（或获胜）。
	 * 超出套餐需求的件数计 0 分（物品已被漏斗吸入消失，与走私交付一致）。
	 *
	 * @param count   件数（≥1）
	 * @param scorer  计分归属者（null = 队内首个成员）
	 * @return 获得的分值（0 = 未命中/未加分）
	 */
	public int deliver(MinecraftServer server, GameStateManager.Team team, ServerPlayer scorer,
	                   String itemId, int count) {
		if (!active || team == null || count <= 0) return 0;
		Order o = orders.get(team);
		if (o == null) return 0;
		GameStateManager gsm = GameStateManager.getInstance();
		ServerPlayer beneficiary = scorer;
		if (beneficiary == null) {
			List<java.util.UUID> members = gsm.getTeamMembers(team);
			beneficiary = members.isEmpty() ? null : server.getPlayerList().getPlayer(members.get(0));
		}
		int gained = 0;
		for (int i = 0; i < count; i++) {
			int s = o.submit(itemId);
			if (s <= 0) break; // 需求已满足 → 其余件数不计分
			gained += s;
		}
		if (gained > 0 && beneficiary != null) {
			gsm.addScore(beneficiary.getUUID(), gained);
			beneficiary.sendSystemMessage(Component.translatable("hell-kitchen.msg.delivered",
					Component.translatable("hell-kitchen.god", "咯噔"), gained));
		}
		if (o.isComplete()) {
			int bonus = o.setBonus();
			int c = completed.merge(team, 1, Integer::sum);
			if (beneficiary != null) gsm.addScore(beneficiary.getUUID(), bonus);
			Component god = Component.translatable("hell-kitchen.god", "咯噔");
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				p.sendSystemMessage(Component.translatable("hell-kitchen.msg.set_done",
						god, WinCondition.teamName(team), c, bonus));
			}
			// 无尽模式：完成任意组数都不判胜，只刷新下一组，直到 SC 倒计时结束
			if (!KitchenStyleProvider.isEndlessGroups()
					&& c >= KitchenStyleProvider.groupsToWin()) {
				WinCondition.declare(server, team);
			} else {
				refresh(server, team, server.getTickCount());
			}
		}
		broadcast(server);
		return gained;
	}

	/** 该漏斗归属的队伍（用于无法判定投掷者的情形） */
	public GameStateManager.Team deliveryTeam(String key) {
		return deliveryTeams.get(key);
	}

	// ==================== 内部 ====================

	private void applyTimeout(MinecraftServer server, GameStateManager.Team team) {
		KitchenConfig cfg = KitchenConfig.get();
		int penalty = cfg.timeoutPenalty;
		Component god = Component.translatable("hell-kitchen.god", "咯噔");
		// 计给队内首个成员一份，使队伍总分恰好变化 |penalty|
		List<java.util.UUID> members = GameStateManager.getInstance().getTeamMembers(team);
		if (!members.isEmpty() && penalty != 0) {
			GameStateManager.getInstance().addScore(members.get(0), penalty);
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			p.sendSystemMessage(Component.translatable("hell-kitchen.msg.timeout",
					god, WinCondition.teamName(team), penalty));
		}
		HellKitchenLog("{} 队套餐超时未完成，扣分 {}", WinCondition.teamName(team), penalty);
	}

	private void refresh(MinecraftServer server, GameStateManager.Team team, long now) {
		KitchenConfig cfg = KitchenConfig.get();
		Order next = Order.roll(cfg, rnd);
		orders.put(team, next);
		deadline.put(team, now + (long) cfg.batchSeconds * 20L);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (GameStateManager.getInstance().getPlayerTeam(p.getUUID()) == team) {
				p.sendSystemMessage(Component.translatable("hell-kitchen.msg.new_set",
						Component.translatable("hell-kitchen.god", "咯噔")));
			}
		}
	}

	/** 给每位玩家下发其本方套餐（需求 6：只能看见自己的套餐） */
	public void broadcast(MinecraftServer server) {
		if (!active) return;
		long now = server.getTickCount();
		int groups = KitchenStyleProvider.groupsToWinForHud();
		KitchenConfig cfg = KitchenConfig.get();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			GameStateManager.Team t = GameStateManager.getInstance().getPlayerTeam(p.getUUID());
			Order o = orders.get(t);
			if (o == null) continue;
			int remain = (int) Math.max(0, (deadline.getOrDefault(t, now) - now) / 20L);
			List<KitchenNet.OrderEntry> entries = new java.util.ArrayList<>();
			for (Order.Entry e : o.entries()) {
				entries.add(new KitchenNet.OrderEntry(e.itemId, e.need, e.delivered, e.scoreEach));
			}
			KitchenNet.send(p, new KitchenNet.OrderSyncPayload(entries, remain,
					completed.getOrDefault(t, 0), groups, cfg.setBonus, false, timeoutPulse.contains(t)));
		}
	}

	private static String describe(Order o) {
		StringBuilder sb = new StringBuilder();
		for (Order.Entry e : o.entries()) {
			if (sb.length() > 0) sb.append(" + ");
			sb.append(e.itemId).append("x").append(e.need);
		}
		return sb.toString();
	}

	private static void HellKitchenLog(String fmt, Object... args) {
		com.hellkitchen.HellKitchen.LOGGER.info("[HK] " + String.format(fmt, args));
	}
}
