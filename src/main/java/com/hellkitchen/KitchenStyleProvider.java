package com.hellkitchen;

import com.survivalcontract.api.StyleOption;
import com.survivalcontract.api.StyleUi;
import com.survivalcontract.api.ScPlayStyleProvider;
import com.survivalcontract.game.GameStateManager;
import com.hellkitchen.tool.KnifeGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 地狱厨房玩法（SC 附属玩法 id = {@code hell-kitchen}）。
 *
 * <p>需求 1：无自选规则（底部 3 个全局开关除外）、职业锁定为「无」、团队箱关闭、无最终目标与专属任务
 * ——由 {@link #ui()} 的抑制开关 + SC 侧落点实现。
 * <p>需求 2：地图内不生成生物、天气保持晴天 ——见 {@link KitchenMap}。
 * <p>需求 3：玩家为冒险模式且有无限时长饱和效果 ——{@link #applyPlayerGuard}。
 * <p>需求 4/15：出生点由挑战书二级菜单的「地图类型」决定（默认街角）——
 * 街角：两队分别 (-62,0,12)（西侧）与 (17,0,9)（东侧）；
 * 水族馆：每队平分为 A/B 两组 —— 西侧 A(-62,7,-205)/B(-62,0,-207)、东侧 A(17,7,-219)/B(18,0,-221)；
 * 热气球（1.0.7 新增）：每队平分为 A/B 两组 —— 西侧 A(35,1,-454)/B(19,1,-454)、东侧 A(37,3,-510)/B(21,3,-510)；
 * 红蓝队随机分配东西两侧。
 * <p>需求 11：挑战书「双禁」位显示「需要完成的套餐组数」（1~6 或「无尽」，默认 3）——见 {@link #options()}；
 * 选「无尽」时不因完成套数获胜，一直玩到 SC 倒计时结束（分数高者胜）。
 * <p>需求 16：每 15~20 秒，地图里随机一处「黄色混凝土 / 橙色陶瓦 / 蓝色带釉陶瓦」上方起火
 * （{@link com.hellkitchen.fire.FireSpawner}）；本玩法关闭原版火焰蔓延（{@link KitchenMap#applyRules}）。
 * <p>需求 13：所有玩家初始携带一把不可破坏、不可丢弃的农夫乐事铁厨刀 ——见
 * {@link com.hellkitchen.tool.KnifeGuard}。
 * <p>需求 14：管理员可在对局进行中切换游戏模式（创造/生存/冒险/观察者）——本模式只对
 * **非管理员**强制冒险模式（{@link #isAdmin}），管理员用原版 {@code /gamemode} 切换后不会被覆盖。
 */
public class KitchenStyleProvider implements ScPlayStyleProvider {

	public static final String ID = "hell-kitchen";

	/** 挑战书二级菜单「地图类型」选项键 */
	public static final String MAP_TYPE_KEY = "mapType";
	/** 地图类型取值：0 = 街角（默认） */
	public static final int MAP_STREET = 0;
	/** 地图类型取值：1 = 水族馆 */
	public static final int MAP_AQUARIUM = 1;
	/** 地图类型取值：2 = 热气球（地图 1.0.7 新增区域，各队分 A/B 两组） */
	public static final int MAP_BALLOON = 2;

	/** 「需要完成的套餐组数」取值上限本身为无尽（一直玩到时间结束） */
	public static final int GROUPS_ENDLESS = 7;
	/** 下发给客户端 HUD 的「无尽」哨兵值（客户端据此显示「无尽」而非数字） */
	public static final int GROUPS_HUD_ENDLESS = -1;

	/** 参战者 → 其出生点（重连/防逃跑兜底用；对局结束清空） */
	private static final Map<UUID, BlockPos> SPAWN_BY_PLAYER = new HashMap<>();
	/** 本局哪支队伍出生在**西侧**基地（x&lt;0 一侧；西侧漏斗也归该队） */
	private static GameStateManager.Team aquariumTeam = GameStateManager.Team.NONE;

	/** 本模式强制开启的挑战（禁用背包格子的可选规则） */
	private static final String SLOT_LOCK_ID = "inv_slot_lock";
	/** inv_slot_lock 的 customValue 语义：9 → 锁 27 格（仅保留快捷栏）；18 → 锁 18 格；27 → 锁 9 格 */
	private static final int SLOT_LOCK_27_VALUE = 9;
	/** 开局前记录的 inv_slot_lock 原值（&lt;0 表示未记录），对局结束还原，避免污染其它玩法 */
	private static int savedSlotLockValue = -1;

	@Override
	public String id() { return ID; }

	@Override
	public String displayNameKey() { return "hell-kitchen.style.name"; }

	@Override
	public String displayNameFallback() { return "地狱厨房"; }

	/** 仅在团队模式下可进行（需求 4/11） */
	@Override
	public boolean forceTeamMode() { return true; }

	/** 需求 1：规则卡隐藏、职业锁定、团队箱关闭、无最终目标、无专属任务 */
	@Override
	public StyleUi ui() {
		return new StyleUi(true, true, true, true, true);
	}

	/**
	 * 挑战书二级菜单的两个整数选项：
	 * ①「双禁」位 = 需要完成的套餐组数（1~6 或「无尽」，默认 3）；无尽模式不判胜，玩到时间结束；
	 * ②「最终目标」位 = 地图类型（街角 / 水族馆 / 热气球，默认街角）。
	 */
	@Override
	public List<StyleOption> options() {
		return List.of(
				new StyleOption("groupsToWin",
						"hell-kitchen.option.groups", "需要完成的套餐组数：%s",
						1, GROUPS_ENDLESS, 3, 1,
						List.of("hell-kitchen.groups.1", "hell-kitchen.groups.2", "hell-kitchen.groups.3",
								"hell-kitchen.groups.4", "hell-kitchen.groups.5", "hell-kitchen.groups.6",
								"hell-kitchen.groups.endless")),
				new StyleOption(MAP_TYPE_KEY,
						"hell-kitchen.option.map_type", "地图类型：%s", MAP_STREET, MAP_BALLOON, MAP_STREET, 1,
						List.of("hell-kitchen.map.street", "hell-kitchen.map.aquarium",
								"hell-kitchen.map.balloon")));
	}

	/** 当前选中的套餐组数（挑战书二级菜单值优先，回退配置文件默认；取值 1~6 或 7=无尽） */
	public static int groupsToWin() {
		return GameStateManager.getInstance()
				.getStyleOption("groupsToWin", KitchenConfig.get().groupsToWin);
	}

	/** 本次开局是否为「无尽」套餐模式（不因完成套数获胜，一直玩到倒计时结束） */
	public static boolean isEndlessGroups() {
		return groupsToWin() >= GROUPS_ENDLESS;
	}

	/** 下发给客户端 HUD 的目标组数：无尽模式返回 {@link #GROUPS_HUD_ENDLESS}（-1），客户端据此显示「无尽」 */
	public static int groupsToWinForHud() {
		return isEndlessGroups() ? GROUPS_HUD_ENDLESS : groupsToWin();
	}

	/** 当前选中的地图类型（0=街角，1=水族馆，2=热气球） */
	public static int mapType() {
		return GameStateManager.getInstance().getStyleOption(MAP_TYPE_KEY, MAP_STREET);
	}

	/** 本次开局是否使用水族馆地图 */
	public static boolean aquariumMap() { return mapType() == MAP_AQUARIUM; }

	/** 本次开局是否使用热气球地图 */
	public static boolean balloonMap() { return mapType() == MAP_BALLOON; }

	public static GameStateManager.Team aquariumTeam() { return aquariumTeam; }

	// ==================== 生命周期 ====================

	@Override
	public void onGameStart(MinecraftServer server, List<ServerPlayer> players) {
		// 每局开局前把厨房维度重置回初始地图，避免上一局的建造/破坏残留（此时玩家仍在大厅）
		KitchenMap.resetDimension(server);

		ServerLevel level = KitchenMap.level(server);
		if (level == null) {
			HellKitchen.LOGGER.error("地狱厨房维度缺失，无法开局");
			// 抛给 SC：由它把阶段回退到 LOBBY 并提示玩家，避免卡在一个没有地图的"假对局"里
			throw new IllegalStateException("hell-kitchen dimension is not loaded");
		}
		KitchenMap.applyRules(level); // 需求 2：禁刷怪 + 锁晴天

		// 本模式自动开启「禁用 27 背包格」规则（锁 27 格 = 只留快捷栏），但初始分恒为 0（不加分）
		enableSlotLock(players);

		SPAWN_BY_PLAYER.clear();
		GameStateManager gsm = GameStateManager.getInstance();

		// 分组（SC 已在 startGame 前补齐队伍分配）
		List<ServerPlayer> red = new ArrayList<>();
		List<ServerPlayer> blue = new ArrayList<>();
		List<ServerPlayer> none = new ArrayList<>();
		for (ServerPlayer p : players) {
			switch (gsm.getPlayerTeam(p.getUUID())) {
				case RED -> red.add(p);
				case BLUE -> blue.add(p);
				default -> none.add(p);
			}
		}

		// 需求 4/15：红蓝队随机分配东西两侧基地；出生点坐标由「地图类型」决定
		int type = mapType();
		boolean westIsRed = ThreadLocalRandom.current().nextBoolean();
		aquariumTeam = westIsRed ? GameStateManager.Team.RED : GameStateManager.Team.BLUE;
		HellKitchen.LOGGER.info("[HK] 本局地图类型：{}；西侧基地归属：{} 队",
				type == MAP_AQUARIUM ? "水族馆" : (type == MAP_BALLOON ? "热气球" : "街角"), aquariumTeam);

		if (type == MAP_AQUARIUM) {
			// 水族馆：每队平分为 A/B 两组，A 组取 y=7 上层点、B 组取下层点
			placeSplit(level, red, westIsRed ? "aquaWestA" : "aquaEastA", westIsRed ? "aquaWestB" : "aquaEastB");
			placeSplit(level, blue, westIsRed ? "aquaEastA" : "aquaWestA", westIsRed ? "aquaEastB" : "aquaWestB");
		} else if (type == MAP_BALLOON) {
			// 热气球（1.0.7 新增）：每队平分为 A/B 两组，西侧 = z≈-454 基地、东侧 = z≈-510 基地
			placeSplit(level, red, westIsRed ? "balloonWestA" : "balloonEastA",
					westIsRed ? "balloonWestB" : "balloonEastB");
			placeSplit(level, blue, westIsRed ? "balloonEastA" : "balloonWestA",
					westIsRed ? "balloonEastB" : "balloonWestB");
		} else {
			// 街角：两队各站自己的基地点，不分组
			placeTeamAt(level, red, westIsRed ? "streetWest" : "streetEast");
			placeTeamAt(level, blue, westIsRed ? "streetEast" : "streetWest");
		}
		String noneKey = westBaseKey();
		for (ServerPlayer p : none) place(level, p, KitchenConfig.get().spawn(noneKey));


		// 需求 13：开局每人一把不可破坏、不可丢弃的铁厨刀
		for (ServerPlayer p : players) KnifeGuard.ensure(p);

		// 套餐引擎 + 烧糊/补给运行时状态
		com.hellkitchen.burn.BurnTracker.reset();
		com.hellkitchen.supply.SupplyManager.INSTANCE.reset();
		com.hellkitchen.supply.FurnaceFuelManager.reset();
		com.hellkitchen.order.OrderManager.INSTANCE.start(server);
		// 随机点火：清空上局残留候选并排定首次点火时间（每 30~45 秒随机一处上方起火；第九十三批：原为 15~20 秒）
		com.hellkitchen.fire.FireSpawner.start(server);

		// 玩法说明（以「咯噔」的名义，彩蛋）
		for (ServerPlayer p : players) {
			p.sendSystemMessage(Component.literal("§6═══════════════════════════════"));
			p.sendSystemMessage(Component.translatable("hell-kitchen.msg.intro1",
					Component.translatable("hell-kitchen.god", "咯噔")));
			p.sendSystemMessage(Component.translatable("hell-kitchen.msg.intro2"));
			p.sendSystemMessage(isEndlessGroups()
					? Component.translatable("hell-kitchen.msg.intro3.endless")
					: Component.translatable("hell-kitchen.msg.intro3", groupsToWin()));
			p.sendSystemMessage(Component.literal("§6═══════════════════════════════"));
		}
	}

	@Override
	public void onServerTick(MinecraftServer server) {
		if (GameStateManager.getInstance().getCurrentPhase() != GameStateManager.GamePhase.PLAYING) return;
		ServerLevel level = KitchenMap.level(server);
		if (level == null) return;

		// 套餐倒计时 / 交付漏斗 / 烧糊 / 补货（每 tick；内部各自门控）
		com.hellkitchen.order.OrderManager.INSTANCE.tick(server);
		com.hellkitchen.deliver.DeliveryManager.INSTANCE.tick(server);
		com.hellkitchen.burn.BurnTracker.tick(server);
		com.hellkitchen.supply.SupplyManager.INSTANCE.tick(server);
		// 熔炉燃料自动补货（仅限煤炭；第九十三批新增）
		com.hellkitchen.supply.FurnaceFuelManager.tick(server);
		// 冒险模式放行回滚：右键放菜肴时临时切了生存，下一 tick 恢复冒险（不影响管理员自选的模式）
		com.hellkitchen.tool.PlaceGuard.revertPending(server);
		// 随机点火：每 30~45 秒在地图里随机一处目标方块上方生成火焰（内部按排程门控；第九十三批：原为 15~20 秒）
		com.hellkitchen.fire.FireSpawner.tick(server);

		// 需求 13：铁厨刀守卫——丢弃即清除（并补回丢弃者）、缺失补发；每 20 tick 全维度兜底清查
		boolean evenTick = server.getTickCount() % 2 == 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.isSpectator()) continue;
			KnifeGuard.sweepNear(level, p);
			if (evenTick) KnifeGuard.ensure(p);
		}
		if (server.getTickCount() % 20 == 0) KnifeGuard.sweep(level);

		// 玩家守卫（冒险模式 + 无限饱和 + 维度兜底）：每 10 tick
		if (server.getTickCount() % 10 != 0) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.isSpectator()) {
				// 观战者维度兜底：中途加入的观战者若被送到本玩法地图之外（登录竞态/传送异常）
				// → 拉回厨房地图的观战点；管理员（可自由切模式/维度）不干预
				if (!isAdmin(p) && !p.level().dimension().equals(KitchenMap.KITCHEN_LEVEL)) {
					BlockPos sp = defaultWestBase();
					p.teleportTo(level, sp.getX() + 0.5, sp.getY() + 1.0, sp.getZ() + 0.5,
							Set.of(), p.getYRot(), p.getXRot(), false);
				}
				continue;
			}
			// 被传送到维度外（重连/死亡兜底）→ 拉回本局出生点
			if (!p.level().dimension().equals(KitchenMap.KITCHEN_LEVEL)) {
				BlockPos bp = SPAWN_BY_PLAYER.get(p.getUUID());
				if (bp == null && !SPAWN_BY_PLAYER.isEmpty()) bp = defaultWestBase();
				if (bp != null) {
					p.teleportTo(level, bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5,
							Set.of(), p.getYRot(), p.getXRot(), false);
				}
			}
			applyPlayerGuard(p);
		}
	}

	@Override
	public void onPlayerJoin(ServerPlayer player) {
		if (GameStateManager.getInstance().getCurrentPhase() != GameStateManager.GamePhase.PLAYING) return;
		if (player.isSpectator()) return;
		applyPlayerGuard(player);
		KnifeGuard.ensure(player);
	}

	/**
	 * 中途加入的观战者落点（SC 观战分流委托）：本玩法用固定地图、没有竞技场，
	 * 交回 SC 默认会把观战者送到竞技场兜底世界（≈普通主世界）——与实际游玩地图脱节。
	 * 这里接管：设为旁观者模式并送进厨房维度观战点，与内置玩法「中途进入自动观战」体验一致。
	 *
	 * @return true = 已接管（SC 不再把该玩家送往竞技场）
	 */
	@Override
	public boolean onSpectatorJoin(MinecraftServer server, ServerPlayer player) {
		ServerLevel level = KitchenMap.level(server);
		if (level == null) return false; // 维度缺失 → 交回 SC 兜底，至少不让流程中断
		BlockPos point = defaultWestBase();
		player.setGameMode(GameType.SPECTATOR);
		player.teleportTo(level, point.getX() + 0.5, point.getY() + 1.0, point.getZ() + 0.5,
				Set.of(), player.getYRot(), player.getXRot(), false);
		return true;
	}

	@Override
	public void onGameEnd(MinecraftServer server) {
		SPAWN_BY_PLAYER.clear();
		aquariumTeam = GameStateManager.Team.NONE;
		com.hellkitchen.tool.PlaceGuard.reset();
		// 还原 inv_slot_lock 的自定义数值，避免污染其它玩法（默认 18 格）
		restoreSlotLockValue();
		// 停用套餐引擎并下发「空套餐」同步 → 客户端顶部套餐 HUD 立即收起（否则回大厅后仍残留）
		com.hellkitchen.order.OrderManager.INSTANCE.stop(server);
		com.hellkitchen.burn.BurnTracker.reset();
		com.hellkitchen.supply.SupplyManager.INSTANCE.reset();
		com.hellkitchen.supply.FurnaceFuelManager.reset();
		// 随机点火候选随维度重建失效 → 一并清空
		com.hellkitchen.fire.FireSpawner.reset();
	}

	// ==================== 内部 ====================

	/**
	 * 本模式自动开启「禁用 27 背包格」规则：把 {@code inv_slot_lock} 的 customValue 强制为 9
	 * （9 → 锁 27 格，仅保留快捷栏），并为每位参战者启用该挑战。
	 * 初始不加分由 SC 侧 {@code calculateInitialScoreExcludingBans} 对附属玩法直接返回 0 保证。
	 * <p>包内可见：供自测直接验证（{@code HellKitchenSelfTest}）。
	 */
	static void enableSlotLock(List<ServerPlayer> players) {
		com.survivalcontract.challenge.ChallengeManager cm =
				com.survivalcontract.challenge.ChallengeManager.getInstance();
		com.survivalcontract.challenge.Challenge slotLock = cm.getChallenge(SLOT_LOCK_ID);
		if (slotLock == null) {
			HellKitchen.LOGGER.warn("挑战 {} 不存在，跳过背包格子锁定", SLOT_LOCK_ID);
			return;
		}
		if (savedSlotLockValue < 0) savedSlotLockValue = slotLock.getCustomValue(); // 仅首次开局记录原值
		slotLock.setCustomValue(SLOT_LOCK_27_VALUE);
		for (ServerPlayer p : players) cm.enableChallenge(p.getUUID(), SLOT_LOCK_ID);
	}

	/** 对局结束还原 inv_slot_lock 的 customValue（玩家侧的挑战集由 SC 的 performResetForLobby 统一清空）。包内可见：供自测验证。 */
	static void restoreSlotLockValue() {
		if (savedSlotLockValue < 0) return;
		com.survivalcontract.challenge.Challenge slotLock =
				com.survivalcontract.challenge.ChallengeManager.getInstance().getChallenge(SLOT_LOCK_ID);
		if (slotLock != null) slotLock.setCustomValue(savedSlotLockValue);
		savedSlotLockValue = -1;
	}

	/**
	 * 需求 3：冒险模式 + 无限时长饱和效果。
	 * <p>需求 14：管理员（GameMaster 权限）可自行用原版 {@code /gamemode} 切换模式，
	 * 这里不对其强制冒险模式，否则每 10 tick 会把管理员的选择覆盖掉。
	 */
	private static void applyPlayerGuard(ServerPlayer p) {
		if (!isAdmin(p) && p.gameMode.getGameModeForPlayer() != GameType.ADVENTURE) {
			p.setGameMode(GameType.ADVENTURE);
		}
		if (!p.hasEffect(MobEffects.SATURATION)) {
			p.addEffect(new MobEffectInstance(MobEffects.SATURATION, -1, 0, false, false, false));
		}
	}

	/** 是否为管理员（可用原版 /gamemode 等 GameMaster 级指令） */
	public static boolean isAdmin(ServerPlayer p) {
		return p != null && p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	/** 整队放到同一个出生点（街角地图类型） */
	private static void placeTeamAt(ServerLevel level, List<ServerPlayer> members, String key) {
		for (ServerPlayer p : members) place(level, p, KitchenConfig.get().spawn(key));
	}

	/**
	 * 分组地图类型（水族馆 / 热气球）：把一队平分为 A/B 两组，分别取 aKey / bKey 出生点。
	 * 人数为奇数时 A 组多一人。
	 */
	private static void placeSplit(ServerLevel level, List<ServerPlayer> members, String aKey, String bKey) {
		int half = (members.size() + 1) / 2;
		for (int i = 0; i < members.size(); i++) {
			place(level, members.get(i), KitchenConfig.get().spawn(i < half ? aKey : bKey));
		}
	}

	private static void place(ServerLevel level, ServerPlayer p, List<Integer> pos) {
		BlockPos bp = new BlockPos(pos.get(0), pos.get(1), pos.get(2));
		p.teleportTo(level, bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5,
				Set.of(), p.getYRot(), p.getXRot(), false);
		if (!isAdmin(p)) p.setGameMode(GameType.ADVENTURE); // 需求 14：管理员保留自己的游戏模式
		p.removeEffect(MobEffects.SATURATION);
		p.addEffect(new MobEffectInstance(MobEffects.SATURATION, -1, 0, false, false, false));
		try {
			p.setRespawnPosition(new net.minecraft.server.level.ServerPlayer.RespawnConfig(
					net.minecraft.world.level.storage.LevelData.RespawnData.of(
							KitchenMap.KITCHEN_LEVEL, bp, p.getYRot(), p.getXRot()),
					true), false);
		} catch (Exception e) {
			HellKitchen.LOGGER.warn("设置重生点失败", e);
		}
		SPAWN_BY_PLAYER.put(p.getUUID(), bp);
	}

	/** 维度外兜底传送点：按当前地图类型取西侧基地出生点 */
	private static BlockPos defaultWestBase() {
		List<Integer> s = KitchenConfig.get().spawn(westBaseKey());
		return new BlockPos(s.get(0), s.get(1), s.get(2));
	}

	/** 当前地图类型的西侧基地出生点键（街角 streetWest / 水族馆 aquaWestA / 热气球 balloonWestA） */
	private static String westBaseKey() {
		return switch (mapType()) {
			case MAP_AQUARIUM -> "aquaWestA";
			case MAP_BALLOON -> "balloonWestA";
			default -> "streetWest";
		};
	}
}
