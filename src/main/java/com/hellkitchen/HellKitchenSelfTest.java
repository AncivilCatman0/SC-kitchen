package com.hellkitchen;

import com.hellkitchen.burn.BurnTracker;
import com.hellkitchen.deliver.DeliveryManager;
import com.hellkitchen.dish.DishPool;
import com.hellkitchen.fire.FireSpawner;
import com.hellkitchen.order.Order;
import com.hellkitchen.order.OrderManager;
import com.hellkitchen.supply.SupplyManager;
import com.hellkitchen.tool.KnifeGuard;
import com.hellkitchen.tool.PlaceGuard;
import com.mojang.authlib.GameProfile;
import com.survivalcontract.api.StyleOption;
import com.survivalcontract.dimension.FixedMapInjector;
import com.survivalcontract.game.GameStateManager;
import io.netty.channel.ChannelFutureListener;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.gamerules.GameRules;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 地狱厨房服务端自测（启用：环境变量 {@code HK_SELFTEST=1}）。
 * 断言：维度加载、地图注入、菜品池解析、套餐生成规则、单品计分、烧糊标签、
 * 交付漏斗兑换（物品消失 + 完成整组 + 刷新）、超时扣分、达成组数即获胜、
 * 「无尽」套餐组数（不判胜）、随机点火（上方起火 + 十字铺 + 连接态）、关闭火焰蔓延。
 */
public final class HellKitchenSelfTest {

	private static MinecraftServer srv;
	private static int tick = 0;
	private static int step = 0;
	private static final List<String> FAILS = new ArrayList<>();
	private static int passes = 0;
	private static int skips = 0;
	// 阶段 1 → 阶段 2 之间传递的交付场景状态
	private static int spawnTick = 0;
	private static GameStateManager.Team delivTeam = GameStateManager.Team.NONE;
	private static Order orderBeforeDeliver;
	private static UUID deliveredEntityId;
	// 需求 13：丢弃拦截断言（阶段 1 布置掉落物，阶段 2 断言）
	private static UUID knifeEntityId;
	private static UUID plainEntityId;
	private static int knifeCheckTick = 0;
	// 需求 8 补充：铜箱补给断言（阶段 1 取出槽 0 物品，阶段 2 断言已自动补回）
	private static final BlockPos supplyProbePos = new BlockPos(-68, 1, 14);
	private static final BlockPos eastSupplyPos = new BlockPos(12, 1, 11);
	private static ItemStack supplyProbeStack = ItemStack.EMPTY;

	/** 自测是否启用 */
	public static boolean isEnabled() {
		return "1".equals(System.getenv("HK_SELFTEST"));
	}

	public static void maybeRegister() {
		if (!isEnabled()) return;
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			srv = s;
			HellKitchen.LOGGER.info("[HK-TEST] server started, self-test scheduled.");
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> tick());
	}

	private static void tick() {
		if (srv == null) return;
		tick++;
		try {
			// 阶段 1：静态断言 + 布置交付场景（实体需等下一 tick 才进入实体表）
			if (step == 0 && tick >= 40) {
				step = 1;
				runStaticAndSetup();
			}
			// 阶段 2：交付/超时/胜利断言（此时掉落物已被服务端的交付 tick 兑换）；
			// 需求 13 的丢弃拦截断言要等守卫的全维度清查（每 20 tick）跑过之后
			else if (step == 1 && tick >= Math.max(spawnTick + 4, knifeCheckTick)) {
				step = 2;
				runDeliveryAndFinish();
				finish();
			}
		} catch (Throwable t) {
			HellKitchen.LOGGER.error("[HK-TEST][CRASH]", t);
			FAILS.add("CRASH: " + t);
			finish();
		}
	}

	/** 阶段 1：静态断言 + 布置交付场景 */
	private static void runStaticAndSetup() {
		MinecraftServer server = srv;
		ServerLevel level = KitchenMap.level(server);
		check(level != null, "维度加载", level == null ? "kitchen 维度为 null" : level.dimension().toString());

		// 地图注入
		Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
				.resolve("dimensions/hell-kitchen/kitchen".replace("/", java.io.File.separator));
		check(Files.isDirectory(dir.resolve("region")) && hasMca(dir.resolve("region")),
				"固定地图注入", hasMca(dir.resolve("region")) ? "region/*.mca 到位" : "缺失：" + dir);

		// 地图升级 1.0.6：新增北侧远景区域（region / poi 各 2 个）
		check(hasMcaNamed(dir.resolve("region"), "r.0.-3.mca")
						&& hasMcaNamed(dir.resolve("region"), "r.-1.-3.mca")
						&& hasMcaNamed(dir.resolve("poi"), "r.0.-3.mca")
						&& hasMcaNamed(dir.resolve("poi"), "r.-1.-3.mca"),
				"地图升级 1.0.6（北侧远景 r.-3.*）",
				"r.0.-3.mca / r.-1.-3.mca 已注入 region 与 poi");

		// ---- 每局重置地图：删档 → 重新注入原始地图 → 重建维度 ----
		long seedBefore = level == null ? 0 : level.getSeed();
		KitchenMap.resetDimension(server);
		ServerLevel rebuilt = KitchenMap.level(server);
		check(rebuilt != null && rebuilt != level, "每局重置地图：维度已重建",
				rebuilt == null ? "重置后维度为 null" : "新 ServerLevel 实例，dimension=" + rebuilt.dimension());
		check(Files.isDirectory(dir.resolve("region")) && hasMca(dir.resolve("region")),
				"每局重置地图：原始地图重新注入",
				hasMca(dir.resolve("region")) ? "region/*.mca 已回填" : "缺失：" + dir);
		// 重置必须落回「已做好的厨房地图」：文件非空且与内置地图逐字节一致，
		// 否则维度会退化成石头超平坦（曾经出现过整目录写成 0 字节的事故）
		String[] regionNames = {"r.0.0.mca", "r.0.-1.mca", "r.0.-2.mca", "r.0.-3.mca", "r.-1.0.mca",
				"r.-1.-1.mca", "r.-1.-2.mca", "r.-1.-3.mca", "r.-2.0.mca", "r.-2.-1.mca"};
		int regionOk = 0;
		StringBuilder regionBad = new StringBuilder();
		long regionBytes = 0;
		for (String n : regionNames) {
			long expected = FixedMapInjector.resourceSize("data/hell-kitchen/kitchen/region/" + n);
			long actual = -1L;
			try {
				actual = Files.size(dir.resolve("region").resolve(n));
				regionBytes += actual;
			} catch (Exception ignored) {}
			if (expected > 0 && actual == expected) regionOk++;
			else regionBad.append(n).append("(期望").append(expected).append("/实际").append(actual).append(") ");
		}
		check(regionOk == regionNames.length, "重置后地图文件完整且与内置地图一致",
				regionOk + "/" + regionNames.length + "，共 " + (regionBytes / 1024 / 1024) + " MB"
						+ (regionBad.isEmpty() ? "" : "；异常：" + regionBad));
		if (rebuilt != null) {
			check(rebuilt.getSeed() == seedBefore, "每局重置地图：维度种子保持",
					"seed=" + rebuilt.getSeed() + "（原 " + seedBefore + "）");
			level = rebuilt; // 后续断言一律使用重置后的维度
		}

		if (level == null) return;

		// 菜品池
		DishPool.reload();
		check(DishPool.dishes().size() >= 3, "菜品池解析（成品菜）",
				"成品 " + DishPool.dishes().size() + " 种 / 饮品 " + DishPool.drinks().size() + " 种");

		// 套餐生成规则
		KitchenConfig cfg = KitchenConfig.get();
		Random rnd = new Random(12345L);
		boolean kindsOk = true, drinkOk = true, countOk = true, idOk = true, diffOk = false;
		String bad = "";
		Order prev = null;
		for (int i = 0; i < 200; i++) {
			Order o = Order.roll(cfg, rnd);
			int foods = 0, drinks = 0;
			for (Order.Entry e : o.entries()) {
				if (e.displayKind.equals("DRINK")) drinks++;
				else foods++;
				if (!DishPool.isPoolItem(e.itemId)) { idOk = false; bad = "非法 id " + e.itemId; }
				if (e.displayKind.equals("BOWL") || e.displayKind.equals("PLATE")) {
					if (e.need != 1) { countOk = false; bad = e.displayKind + " 数量应为 1，实际 " + e.need; }
				} else if (e.displayKind.equals("FOOD")) {
					if (e.need < 1 || e.need > 3) { countOk = false; bad = "FOOD 数量应 1~3，实际 " + e.need; }
				}
			}
			if (foods < 2 || foods > 3) { kindsOk = false; bad = "成品菜种类应 2~3，实际 " + foods; }
			if (drinks > 1) { drinkOk = false; bad = "饮品种类应 0~1，实际 " + drinks; }
			if (prev != null && !sameShape(prev, o)) diffOk = true;
			prev = o;
		}
		check(kindsOk, "套餐含 2~3 种成品菜", kindsOk ? "200 次生成全部合规" : bad);
		check(drinkOk, "套餐含 0~1 种饮品", drinkOk ? "200 次生成全部合规" : bad);
		check(countOk, "数量上限（碗/盘=1，其余 1~3）", countOk ? "200 次生成全部合规" : bad);
		check(idOk, "套餐条目均来自菜品池", idOk ? "全部为池内注册名" : bad);
		check(diffOk, "套餐随机多样", diffOk ? "200 组套餐存在差异" : "全部相同");

		// 单品计分 + 整组加成
		Order.Entry first = Order.roll(cfg, new Random(7L)).entries().get(0);
		Order probe = new Order(List.of(new Order.Entry(first.itemId, first.displayKind, 2, 5)), 10);
		int s1 = probe.submit(first.itemId);
		int s2 = probe.submit(first.itemId);
		int s3 = probe.submit(first.itemId);
		check(s1 == 5 && s2 == 5 && s3 == 0, "单品计分与需求封顶",
				"第一次 +5 / 第二次 +5 / 超额 +0（实际 " + s1 + "/" + s2 + "/" + s3 + "）");
		check(probe.isComplete() && probe.itemScore() == 10 && probe.totalScore() == 20,
				"整组加成与总分", "itemScore=10 totalScore=20（含 setBonus 10）");

		// 烧糊标签
		ItemStack pie = new ItemStack(Items.PUMPKIN_PIE);
		BurnTracker.markBurnt(pie);
		check(BurnTracker.isBurnt(pie) && pie.getHoverName().getString().contains("已烧糊"),
				"烧糊标签", "自定义名含「已烧糊」：" + pie.getHoverName().getString());
		// 烧糊标签幂等
		BurnTracker.markBurnt(pie);
		check(pie.getHoverName().getString().split("已烧糊", -1).length - 1 == 1,
				"烧糊标签幂等", "不重复打标");

		// ---- 需求 13：铁厨刀（不可破坏 + 不可丢弃） ----
		ItemStack marked = KnifeGuard.mark(new ItemStack(Items.IRON_SWORD)); // 用替身验证标记机制（与前置模组无关）
		check(KnifeGuard.isOurs(marked), "铁厨刀标记识别",
				"custom_data." + KnifeGuard.MARK_KEY + "=true");
		check(!KnifeGuard.isOurs(new ItemStack(Items.IRON_SWORD)), "未标记刀具不误判",
				"玩家自制的普通刀不受保护");
		check(marked.get(DataComponents.UNBREAKABLE) != null, "铁厨刀不可破坏",
				"minecraft:unbreakable 已写入");
		if (KnifeGuard.isAvailable()) {
			ItemStack real = KnifeGuard.newKnife();
			check(KnifeGuard.isOurs(real) && real.get(DataComponents.UNBREAKABLE) != null
							&& real.getItem() == KnifeGuard.item(),
					"开局铁厨刀（农夫乐事 " + KnifeGuard.KNIFE_ID + "）", "已按注册名解析并打标");
			ServerPlayer fake = new ServerPlayer(server, level,
					new com.mojang.authlib.GameProfile(UUID.randomUUID(), "HKKnifeFake"),
					net.minecraft.server.level.ClientInformation.createDefault());
			KnifeGuard.ensure(fake);
			check(KnifeGuard.hasKnife(fake), "开局发放铁厨刀", "背包内已有本模式铁厨刀");
			fake.getInventory().clearContent();
			KnifeGuard.ensure(fake);
			check(KnifeGuard.hasKnife(fake), "铁厨刀丢失自动补发", "清空背包后守卫补回");
		} else {
			skip("开局发放/丢失补发铁厨刀",
					"当前环境未安装农夫乐事（" + KnifeGuard.KNIFE_ID + " 解析不到）→ 本机无法验证，实机由前置模组提供");
		}

		// 丢弃拦截：标记刀掉落物会被守卫清除，普通掉落物不受影响（实体需等下一 tick 进入实体表）
		ItemEntity markedDrop = new ItemEntity(level, 26.5, 8.0, -217.5, KnifeGuard.mark(new ItemStack(Items.IRON_SWORD)));
		ItemEntity plainDrop = new ItemEntity(level, 27.5, 8.0, -217.5, new ItemStack(Items.IRON_SWORD));
		level.addFreshEntity(markedDrop);
		level.addFreshEntity(plainDrop);
		knifeEntityId = markedDrop.getUUID();
		plainEntityId = plainDrop.getUUID();
		// 守卫的全维度清查每 20 tick 一次：断言点安排在下一个 20 tick 边界之后
		knifeCheckTick = ((tick / 20) + 1) * 20 + 1;

		// ---- 玩法运行期初始化 ----
		GameStateManager gsm = GameStateManager.getInstance();
		gsm.setCurrentPhase(GameStateManager.GamePhase.PLAYING);
		gsm.setGameStartTime(server.getTickCount());
		gsm.setGameDurationMinutes(120);
		gsm.selectAddonStyle(KitchenStyleProvider.ID);
		gsm.setStyleOption("groupsToWin", 3);

		// ---- 自动开启「禁用 27 背包格」规则（锁 27 格 = 只留快捷栏）+ 初始不加分 ----
		com.survivalcontract.challenge.ChallengeManager cm =
				com.survivalcontract.challenge.ChallengeManager.getInstance();
		ServerPlayer fakeRule = spawnFake(server, level, "HKSlotLockFake");
		int slotLockDefault = cm.getChallenge("inv_slot_lock").getCustomValue();
		KitchenStyleProvider.enableSlotLock(List.of(fakeRule));
		check(cm.getChallenge("inv_slot_lock").getCustomValue() == 9 && slotLockDefault == 18,
				"自动开启禁用 27 背包格：customValue=9（锁 27 格）",
				"customValue=" + cm.getChallenge("inv_slot_lock").getCustomValue() + "（默认 " + slotLockDefault + "）");
		check(cm.getPlayerChallenges(fakeRule.getUUID()).contains("inv_slot_lock"),
				"自动开启禁用 27 背包格：参战者已启用该规则",
				"challenges=" + cm.getPlayerChallenges(fakeRule.getUUID()));
		check(gsm.calculateInitialScoreExcludingBans(fakeRule.getUUID()) == 0,
				"本模式初始不加分（附属玩法初始分恒为 0）",
				"initialScore=" + gsm.calculateInitialScoreExcludingBans(fakeRule.getUUID()));
		KitchenStyleProvider.restoreSlotLockValue();
		check(cm.getChallenge("inv_slot_lock").getCustomValue() == slotLockDefault,
				"对局结束还原背包锁定为默认值",
				"customValue=" + cm.getChallenge("inv_slot_lock").getCustomValue() + "（应 " + slotLockDefault + "）");

		BurnTracker.reset();
		OrderManager.INSTANCE.start(server);
		check(OrderManager.INSTANCE.isActive(), "套餐引擎启动", "active=true");
		check(KitchenStyleProvider.groupsToWin() == 3, "套餐组数默认 3",
				"groupsToWin=" + KitchenStyleProvider.groupsToWin());
		// 套餐刷新间隔 5 分钟（老配置 v1 的 600 秒会被加载时迁移为 300 秒）
		check(cfg.batchSeconds == 300, "套餐刷新间隔 5 分钟",
				"batchSeconds=" + cfg.batchSeconds + "（应 300）");

		// ---- 需求 11：挑战书「需要完成的套餐组数」新增「无尽」（1~6 或 7=无尽） ----
		StyleOption groupsOpt = null;
		for (StyleOption o : new KitchenStyleProvider().options()) {
			if ("groupsToWin".equals(o.key())) { groupsOpt = o; break; }
		}
		check(groupsOpt != null && groupsOpt.min() == 1
						&& groupsOpt.max() == KitchenStyleProvider.GROUPS_ENDLESS
						&& groupsOpt.valueLabels() != null
						&& groupsOpt.valueLabels().contains("hell-kitchen.groups.endless"),
				"套餐组数选项含「无尽」（1~6 或 7=无尽）",
				groupsOpt == null ? "未找到 groupsToWin 选项"
						: "min=" + groupsOpt.min() + " max=" + groupsOpt.max()
								+ " labels=" + groupsOpt.valueLabels());
		gsm.setStyleOption("groupsToWin", KitchenStyleProvider.GROUPS_ENDLESS);
		check(KitchenStyleProvider.isEndlessGroups()
						&& KitchenStyleProvider.groupsToWinForHud() == KitchenStyleProvider.GROUPS_HUD_ENDLESS,
				"无尽模式判定与 HUD 哨兵值（-1）",
				"isEndless=" + KitchenStyleProvider.isEndlessGroups()
						+ " hudTarget=" + KitchenStyleProvider.groupsToWinForHud());
		gsm.setStyleOption("groupsToWin", 3);

		// ---- 需求 16：本玩法关闭原版火焰蔓延 ----
		// 26.2 起 gamerule doFireTick 已被 fire_spread_radius_around_player 取代：置 0 = 玩家周围 0 格内才可蔓延 → 实际不扩散
		KitchenMap.applyRules(level);
		check(level.getGameRules().get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER) == 0,
				"本玩法关闭火焰蔓延",
				"fire_spread_radius_around_player="
						+ level.getGameRules().get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER) + "（应 0）");

		// ---- 需求 16：随机点火（目标上方生成火焰 + 十字铺 + 连接态刷新 → 视觉膨胀） ----
		FireSpawner.reset();
		BlockPos fbase = new BlockPos(30, 12, -212);
		level.getChunkAt(fbase); // 触发区块加载（findBlocks 只扫已加载区块）
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				level.setBlockAndUpdate(fbase.offset(dx, 0, dz), Blocks.STONE.defaultBlockState()); // 火焰底面（保证可支撑）
				level.setBlockAndUpdate(fbase.offset(dx, 1, dz), Blocks.AIR.defaultBlockState());
				level.setBlockAndUpdate(fbase.offset(dx, 2, dz), Blocks.AIR.defaultBlockState());
			}
		}
		level.setBlockAndUpdate(fbase, Blocks.CONCRETE.yellow().defaultBlockState());
		FireSpawner.scanChunk(level.getChunkAt(fbase));
		int fireCandidates = fireCandidateCount();
		check(fireCandidates > 0, "随机点火：区块扫描收集候选（目标方块）",
				"候选 " + fireCandidates + " 个");
		setFireTargetOnly(fbase);
		setNextFireTick(0L);
		FireSpawner.tick(server);
		int cross = 0;
		for (BlockPos p : fireCross(fbase)) {
			if (level.getBlockState(p).getBlock() == Blocks.FIRE) cross++;
		}
		check(cross >= 3, "随机点火：目标上方起火 + 十字铺（火焰视觉更大）",
				"十字火焰 " + cross + "/5 格（中心 " + fbase.above() + "）");

		// 火焰 60 秒后自动熄灭：把记录里的到期 tick 全部提前，再跑一帧 tick
		int tracked = fireTrackedCount();
		check(tracked > 0, "随机点火：火焰已登记熄灭倒计时", "登记 " + tracked + " 格");
		forceFiresExpired();
		FireSpawner.tick(server);
		int remaining = 0;
		for (BlockPos p : fireCross(fbase)) {
			if (level.getBlockState(p).getBlock() == Blocks.FIRE) remaining++;
		}
		check(remaining == 0 && fireTrackedCount() == 0, "地板火焰 60 秒后自动熄灭",
				"剩余火焰 " + remaining + " 格 / 登记 " + fireTrackedCount() + " 格");
		FireSpawner.reset();

		// 对局结束：stop() 停用引擎并下发「空套餐」同步（客户端 HUD 收起）；随后复位供后续断言
		OrderManager.INSTANCE.stop(server);
		check(!OrderManager.INSTANCE.isActive(), "对局结束停用套餐引擎（HUD 清空）",
				"stop() 后 active=false");
		OrderManager.INSTANCE.start(server);

		// ---- 冒险模式放行「右键放下菜肴」----
		// 白名单只看「可放置方块 + 属于菜肴」：原版蛋糕是方块食物（注册名恰为 minecraft:cake），
		// 农夫乐事的派 / 盛宴方块是典型「右键放下 → 用盘/碗盛装」的菜；
		// 而 minecraft:pumpkin_pie 是纯食物（无对应方块）→ 本就不涉及放置，不该被放行。
		check(PlaceGuard.isPlaceableDish(new ItemStack(Items.CAKE)),
				"冒险放行：可放置菜肴判定（原版蛋糕）",
				"minecraft:cake 是 BlockItem 且属菜肴 → 白名单内");
		check(!PlaceGuard.isPlaceableDish(new ItemStack(Items.STONE)),
				"冒险放行：普通建材不放行", "minecraft:stone 非菜肴 → 保持冒险模式限制");
		check(!PlaceGuard.isPlaceableDish(new ItemStack(Items.COOKIE))
						&& !PlaceGuard.isPlaceableDish(new ItemStack(Items.PUMPKIN_PIE)),
				"冒险放行：非方块食物不误判",
				"cookie / pumpkin_pie 都不是 BlockItem → 不涉及放置");
		check(!PlaceGuard.isPlaceableDish(ItemStack.EMPTY), "冒险放行：空手不触发",
				"empty stack → false");
		check(PlaceGuard.shouldAllowPlace(GameType.ADVENTURE, new ItemStack(Items.CAKE))
						&& !PlaceGuard.shouldAllowPlace(GameType.SURVIVAL, new ItemStack(Items.CAKE))
						&& !PlaceGuard.shouldAllowPlace(GameType.ADVENTURE, new ItemStack(Items.STONE)),
				"冒险放行：仅「冒险模式 + 菜肴」触发",
				"ADVENTURE+蛋糕=true，SURVIVAL+蛋糕=false，ADVENTURE+石头=false");

		// 农夫乐事：盛宴方块菜（farmersdelight:roast_chicken_block）右键放下后才能用盘盛装 → 必须放行；
		// 其盘装成品（farmersdelight:roast_chicken）不是方块 → 不误判
		Item fdFeast = BuiltInRegistries.ITEM.getValue(
				Identifier.fromNamespaceAndPath("farmersdelight", "roast_chicken_block"));
		Item fdPlate = BuiltInRegistries.ITEM.getValue(
				Identifier.fromNamespaceAndPath("farmersdelight", "roast_chicken"));
		if (fdFeast != null && fdFeast != Items.AIR && fdPlate != null && fdPlate != Items.AIR) {
			check(PlaceGuard.isPlaceableDish(new ItemStack(fdFeast))
							&& !PlaceGuard.isPlaceableDish(new ItemStack(fdPlate)),
					"冒险放行：农夫乐事盛宴方块菜",
					"roast_chicken_block 可放行；盘装成品 roast_chicken 非方块不误判");
		} else {
			skip("冒险放行：农夫乐事盛宴方块菜", "未安装农夫乐事 → 本机无法验证");
		}
		// 菜品池内的可放置方块菜：农夫乐事的派既是池内成品又是可放置方块，两条判定都须命中
		Item fdPie = BuiltInRegistries.ITEM.getValue(
				Identifier.fromNamespaceAndPath("farmersdelight", "apple_pie"));
		if (fdPie != null && fdPie != Items.AIR) {
			check(PlaceGuard.isPlaceableDish(new ItemStack(fdPie))
							&& DishPool.isPoolItem("farmersdelight:apple_pie"),
					"冒险放行：菜品池内可放置方块菜",
					"farmersdelight:apple_pie 同时命中池内成品与放行白名单");
		} else {
			skip("冒险放行：菜品池内可放置方块菜", "未安装农夫乐事 → 本机无法验证");
		}

		// 临时生存窗口：冒险模式右键菜肴 → 切生存放行 → 下一 tick 恢复冒险
		ServerPlayer fakePlace = spawnFake(server, server.overworld(), "HKPlaceFake");
		gsm.setScore(fakePlace.getUUID(), 100); // 标记为参战者，避免被 SC 当作中途加入的观战者
		fakePlace.setGameMode(GameType.SURVIVAL);
		check(!PlaceGuard.tryAllowPlace(fakePlace, new ItemStack(Items.CAKE), server.getTickCount()),
				"冒险放行：非冒险模式不触发", "生存模式下 tryAllowPlace=false（不会反复切模式）");
		fakePlace.setGameMode(GameType.ADVENTURE);
		boolean placeAllowed = PlaceGuard.tryAllowPlace(fakePlace,
				new ItemStack(Items.CAKE), server.getTickCount() - 1);
		check(placeAllowed && fakePlace.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
						&& PlaceGuard.isPending(fakePlace),
				"冒险放行：临时切生存以放行放置",
				"tryAllowPlace=true，模式=" + fakePlace.gameMode.getGameModeForPlayer()
						+ "，pending=" + PlaceGuard.isPending(fakePlace));
		PlaceGuard.revertPending(server);
		check(!PlaceGuard.isPending(fakePlace)
						&& fakePlace.gameMode.getGameModeForPlayer() == GameType.ADVENTURE,
				"冒险放行：下一 tick 恢复冒险模式",
				"回滚后 pending=false，模式=" + fakePlace.gameMode.getGameModeForPlayer());

		// 中途加入的观战者落点：接管 = 厨房维度旁观者（对齐内置玩法「进本游戏地图观战」）
		ServerPlayer fakeSpec = spawnFake(server, server.overworld(), "HKSpecFake");
		boolean taken = new KitchenStyleProvider().onSpectatorJoin(server, fakeSpec);
		check(taken && fakeSpec.isSpectator()
						&& fakeSpec.level().dimension().equals(KitchenMap.KITCHEN_LEVEL),
				"中途加入观战者落点：厨房维度旁观者",
				"接管=" + taken + "，模式=" + fakeSpec.gameMode.getGameModeForPlayer()
						+ "，维度=" + fakeSpec.level().dimension());

		// ---- 需求 4/15：地图类型（默认街角，共三套）+ 三套出生点坐标 ----
		check(KitchenStyleProvider.mapType() == KitchenStyleProvider.MAP_STREET, "地图类型默认街角",
				"mapType=" + KitchenStyleProvider.mapType()
						+ "（0=街角 1=水族馆 2=热气球；水族馆=" + KitchenStyleProvider.aquariumMap()
						+ " 热气球=" + KitchenStyleProvider.balloonMap() + "）");
		StyleOption mapOpt = new KitchenStyleProvider().options().stream()
				.filter(o -> KitchenStyleProvider.MAP_TYPE_KEY.equals(o.key())).findFirst().orElse(null);
		check(mapOpt != null && mapOpt.max() == KitchenStyleProvider.MAP_BALLOON
						&& mapOpt.valueLabels().size() == 3,
				"地图类型选项三项（街角 / 水族馆 / 热气球）",
				mapOpt == null ? "选项缺失" : "max=" + mapOpt.max() + " 取值名=" + mapOpt.valueLabels());
		boolean spawnOk = cfg.spawn("streetWest").equals(List.of(-62, 0, 12))
				&& cfg.spawn("streetEast").equals(List.of(17, 0, 9))
				&& cfg.spawn("aquaWestA").equals(List.of(-62, 7, -205))
				&& cfg.spawn("aquaWestB").equals(List.of(-62, 0, -207))
				&& cfg.spawn("aquaEastA").equals(List.of(17, 7, -219))
				&& cfg.spawn("aquaEastB").equals(List.of(18, 0, -221))
				&& cfg.spawn("balloonWestA").equals(List.of(35, 1, -454))
				&& cfg.spawn("balloonWestB").equals(List.of(19, 1, -454))
				&& cfg.spawn("balloonEastA").equals(List.of(37, 3, -510))
				&& cfg.spawn("balloonEastB").equals(List.of(21, 3, -510));
		check(spawnOk, "三套地图出生点坐标（街角 2 点 + 水族馆 4 点 + 热气球 4 点）",
				spawnOk ? "10 个坐标全部匹配需求" : "坐标与需求不符");

		// ---- 需求 7：六个交付漏斗（三张地图各 2 个）+ 按所在侧归属队伍 ----
		List<KitchenConfig.Delivery> pts = cfg.deliveryPoints();
		boolean coordsOk = pts.size() == 6
				&& hasPoint(pts, 26.5, -217.5) && hasPoint(pts, 11.5, 20.5)
				&& hasPoint(pts, -54.5, -203.5) && hasPoint(pts, -68.5, 23.5)
				&& hasPoint(pts, 29.5, -504.5) && hasPoint(pts, 27.5, -448.5);
		check(coordsOk, "交付漏斗 6 个（街角 / 水族馆 / 热气球各 2 个）",
				"count=" + pts.size() + "（西 3 + 东 3）");
		check(OrderManager.INSTANCE.deliveryTeam("aquarium") == OrderManager.INSTANCE.deliveryTeam("street")
						&& OrderManager.INSTANCE.deliveryTeam("aquariumWest")
							== OrderManager.INSTANCE.deliveryTeam("streetWest")
						&& OrderManager.INSTANCE.deliveryTeam("aquarium")
							!= OrderManager.INSTANCE.deliveryTeam("aquariumWest"),
				"街角 / 水族馆漏斗按所在侧归属（东侧一队、西侧一队）",
				"东侧=" + OrderManager.INSTANCE.deliveryTeam("aquarium")
						+ " 西侧=" + OrderManager.INSTANCE.deliveryTeam("aquariumWest"));
		GameStateManager.Team balloonWestTeam = OrderManager.INSTANCE.deliveryTeam("balloonWest");
		GameStateManager.Team balloonEastTeam = OrderManager.INSTANCE.deliveryTeam("balloon");
		check(balloonWestTeam == OrderManager.INSTANCE.deliveryTeam("streetWest")
						&& balloonEastTeam == OrderManager.INSTANCE.deliveryTeam("street")
						&& balloonWestTeam != balloonEastTeam,
				"热气球漏斗按 side 归属（坐标 x 均 >0，不能按 x 判定）",
				"西=" + balloonWestTeam + " 东=" + balloonEastTeam);

		// 六个漏斗坐标均可用（地图 1.0.7 已内置；缺失则补放，保证交付可执行）
		int balloonBuiltIn = 0;
		for (String key : List.of("balloon", "balloonWest")) {
			KitchenConfig.Delivery dp = cfg.delivery(key);
			BlockPos p = BlockPos.containing(dp.x, dp.y, dp.z);
			level.getChunkAt(p);
			if (level.getBlockEntity(p) instanceof net.minecraft.world.Container) balloonBuiltIn++;
		}
		check(balloonBuiltIn == 2, "地图 1.0.7：热气球区域自带两个交付漏斗",
				"就位 " + balloonBuiltIn + "/2（证明当前维度是新版地图而非旧快照）");
		int preExisting = 0;
		for (KitchenConfig.Delivery dp : pts) {
			BlockPos p = BlockPos.containing(dp.x, dp.y, dp.z);
			level.getChunkAt(p);
			if (level.getBlockEntity(p) instanceof net.minecraft.world.Container) preExisting++;
			else level.setBlockAndUpdate(p, net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState());
		}
		int ready = 0;
		for (KitchenConfig.Delivery dp : pts) {
			if (level.getBlockEntity(BlockPos.containing(dp.x, dp.y, dp.z))
					instanceof net.minecraft.world.Container) ready++;
		}
		check(ready == 6, "六个交付漏斗就位可用",
				"就位 " + ready + "/6（地图原有 " + preExisting + " 个）");

		// ---- 需求 8 补充：地图补给箱是涂蜡铜箱（内装 27 瓶喷溅药水），铜箱家族也要补货 ----
		BlockPos[] copperChests = {
				new BlockPos(-68, 1, 14), new BlockPos(12, 1, 11),
				new BlockPos(-69, -1, 23), new BlockPos(11, -1, 20)};
		int copperOk = 0;
		for (BlockPos pp : copperChests) {
			level.getChunkAt(pp);
			BlockEntity pbe = level.getBlockEntity(pp);
			if (pbe != null && SupplyManager.isResupplyBlock(pbe)) copperOk++;
		}
		check(copperOk == 4, "补给容器识别：涂蜡 / 氧化铜箱", "识别 " + copperOk + "/4");
		check(SupplyManager.INSTANCE.isRegistered(supplyProbePos)
						&& SupplyManager.INSTANCE.isRegistered(eastSupplyPos),
				"地图铜箱补给点已登记（东西基地各 1）",
				"西=" + SupplyManager.INSTANCE.isRegistered(supplyProbePos)
						+ " 东=" + SupplyManager.INSTANCE.isRegistered(eastSupplyPos));
		// 取出槽 0 的药水 → 阶段 2 断言已被自动补回
		if (level.getBlockEntity(supplyProbePos) instanceof net.minecraft.world.Container wc) {
			supplyProbeStack = wc.getItem(0).copy();
			wc.setItem(0, ItemStack.EMPTY);
			wc.setChanged();
		}

		// ---- 布置交付场景：漏斗 + 掉落物 ----
		delivTeam = OrderManager.INSTANCE.deliveryTeam("aquarium");
		DishPool.Entry dish = DishPool.dishes().get(0);
		orderBeforeDeliver = new Order(List.of(
				new Order.Entry(dish.id(), dish.kind(), 1, dish.score())), 30);
		setOrderForTest(delivTeam, orderBeforeDeliver);
		KitchenConfig.Delivery d = cfg.delivery("aquarium");
		BlockPos hp = BlockPos.containing(d.x, d.y, d.z);
		level.getChunkAt(hp);
		if (!(level.getBlockEntity(hp) instanceof net.minecraft.world.Container)) {
			level.setBlockAndUpdate(hp, net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState());
		}
		boolean hopperOk = level.getBlockEntity(hp) instanceof net.minecraft.world.Container;
		check(hopperOk, "送货漏斗（漏斗方块）就位", "pos=" + hp + " container=" + hopperOk);

		ItemEntity ie = new ItemEntity(level, d.x, d.y + 1.2, d.z, new ItemStack(dish.item(), 3));
		level.addFreshEntity(ie);
		deliveredEntityId = ie.getUUID();
		spawnTick = tick;
	}

	/** 阶段 2：交付/超时/胜利断言 */
	private static void runDeliveryAndFinish() {
		MinecraftServer server = srv;
		ServerLevel level = KitchenMap.level(server);
		GameStateManager gsm = GameStateManager.getInstance();
		KitchenConfig cfg = KitchenConfig.get();
		if (level == null) return;

		// ---- 交付：吸入即消失 + 完成整组 + 立即刷新 ----
		int completedAfter = completedOf(delivTeam);
		Order after = orderFor(delivTeam);
		check(level.getEntity(deliveredEntityId) == null, "交付物被吸入即消失",
				"掉落物实体已移除");

		// ---- 需求 8 补充：铜箱里取出的物品已被自动补回 ----
		level.getChunkAt(supplyProbePos);
		String supplyMsg = "阶段 1 未取到物品";
		boolean supplyOk = false;
		if (!supplyProbeStack.isEmpty()
				&& level.getBlockEntity(supplyProbePos) instanceof net.minecraft.world.Container sc) {
			ItemStack got = sc.getItem(0);
			supplyOk = got.is(supplyProbeStack.getItem()) && got.getCount() == supplyProbeStack.getCount();
			supplyMsg = "槽 0 = " + got.getCount() + "x" + DishPool.itemId(got.getItem())
					+ "（应 " + supplyProbeStack.getCount() + "x" + DishPool.itemId(supplyProbeStack.getItem()) + "）";
		}
		check(supplyOk, "铜箱取出物品后自动补货", supplyMsg);

		// ---- 需求 13：丢弃的铁厨刀被守卫清除，其他掉落物不受影响 ----
		check(level.getEntity(knifeEntityId) == null, "丢弃的铁厨刀被清除（不可丢弃）",
				"标记掉落物已被守卫清理");
		check(level.getEntity(plainEntityId) != null, "普通掉落物不被误清除",
				"未打标物品仍在地面");

		check(completedAfter == 1, "完成整组套餐计数", "completedSets=" + completedAfter);
		check(after != orderBeforeDeliver, "完成整组后立即刷新新套餐",
				"套餐对象已更换（新条目数=" + after.entries().size() + "）");

		// ---- 超时扣分 ----
		UUID member = UUID.randomUUID();
		gsm.setPlayerTeam(member, delivTeam);
		gsm.setScore(member, 100);
		setDeadlineForTest(delivTeam, 0L);
		OrderManager.INSTANCE.tick(server);
		int raw = gsm.getAllScores().getOrDefault(member, -9999);
		check(raw == 100 + cfg.timeoutPenalty, "套餐超时扣分",
				"分数 " + raw + "（应 100 + " + cfg.timeoutPenalty + "）");
		check(deadlineFor(delivTeam) > server.getTickCount(), "超时后刷新并重置倒计时",
				"新截止 tick=" + deadlineFor(delivTeam));

		// ---- 达成组数 → 获胜 ----
		gsm.setStyleOption("groupsToWin", 1);
		GameStateManager.Team winTeam = OrderManager.INSTANCE.deliveryTeam("street");
		DishPool.Entry dish2 = DishPool.dishes().get(Math.min(1, DishPool.dishes().size() - 1));
		setOrderForTest(winTeam, new Order(List.of(
				new Order.Entry(dish2.id(), dish2.kind(), 1, dish2.score())), 30));
		setCompletedForTest(winTeam, 0);
		OrderManager.INSTANCE.deliver(server, winTeam, null, dish2.id(), 1);
		check(gsm.getCurrentPhase() == GameStateManager.GamePhase.FINISHED,
				"率先完成指定组数 → 结束对局", "phase=" + gsm.getCurrentPhase());
		check(!gsm.getWinnersByScore().isEmpty(), "胜者已按队伍写入",
				"winners=" + gsm.getWinnersByScore().size() + " 人");

		// ---- 需求 11：无尽模式 → 完成任意组数都不判胜（一直玩到 SC 倒计时结束） ----
		gsm.setCurrentPhase(GameStateManager.GamePhase.PLAYING);
		gsm.setStyleOption("groupsToWin", KitchenStyleProvider.GROUPS_ENDLESS);
		GameStateManager.Team endlessTeam = OrderManager.INSTANCE.deliveryTeam("streetWest");
		DishPool.Entry dish3 = DishPool.dishes().get(0);
		setOrderForTest(endlessTeam, new Order(List.of(
				new Order.Entry(dish3.id(), dish3.kind(), 1, dish3.score())), 30));
		setCompletedForTest(endlessTeam, KitchenStyleProvider.GROUPS_ENDLESS); // 已远超任意 1~6 的目标
		OrderManager.INSTANCE.deliver(server, endlessTeam, null, dish3.id(), 1);
		check(gsm.getCurrentPhase() == GameStateManager.GamePhase.PLAYING,
				"无尽模式：完成套餐不判胜（继续游玩到倒计时结束）",
				"completed=" + completedOf(endlessTeam) + "，phase=" + gsm.getCurrentPhase());

		// ---- 收尾 ----
		OrderManager.INSTANCE.reset();
		BurnTracker.reset();
		gsm.setStyleOption("groupsToWin", 3);
		gsm.setPlayStyle(GameStateManager.PlayStyle.NORMAL);
		gsm.setCurrentPhase(GameStateManager.GamePhase.LOBBY);
	}

	// ==================== 反射辅助 ====================

	private static void check(boolean ok, String name, String detail) {
		if (ok) {
			passes++;
			HellKitchen.LOGGER.info("[HK-TEST][PASS] {} — {}", name, detail);
		} else {
			FAILS.add(name + " — " + detail);
			HellKitchen.LOGGER.error("[HK-TEST][FAIL] {} — {}", name, detail);
		}
	}

	/** 本机前置缺失导致无法验证的项：不计 PASS 也不计 FAIL，单独列出（不影响 0 FAIL 判定） */
	private static void skip(String name, String detail) {
		skips++;
		HellKitchen.LOGGER.warn("[HK-TEST][SKIP] {} — {}", name, detail);
	}

	private static boolean hasPoint(List<KitchenConfig.Delivery> pts, double x, double z) {
		for (KitchenConfig.Delivery d : pts) {
			if (Math.abs(d.x - x) < 0.01 && Math.abs(d.z - z) < 0.01) return true;
		}
		return false;
	}

	private static boolean hasMca(Path dir) {
		try (var s = Files.list(dir)) {
			return s.anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".mca"));
		} catch (Exception e) {
			return false;
		}
	}

	/** 指定目录下是否存在某个 .mca 文件 */
	private static boolean hasMcaNamed(Path dir, String name) {
		return Files.isRegularFile(dir.resolve(name));
	}

	/**
	 * 生成一个「可正常改游戏模式」的假玩家：{@code ServerPlayer.setGameMode} 会向
	 * {@code connection} 发包，裸 ServerPlayer（connection 为 null）会 NPE，
	 * 因此必须像 SC 自测那样带一个假连接注册进玩家列表（也便于 revertPending 按 uuid 找回）。
	 */
	private static ServerPlayer spawnFake(MinecraftServer s, ServerLevel level, String name) {
		GameProfile profile = new GameProfile(UUID.randomUUID(), name);
		ServerPlayer player = new ServerPlayer(s, level, profile, ClientInformation.createDefault());
		s.getPlayerList().placeNewPlayer(new FakeConnection(), player,
				net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false));
		return player;
	}

	/** 假连接：所有发包/协议切换为 no-op（与 SC 自测同款） */
	static class FakeConnection extends Connection {
		FakeConnection() {
			super(PacketFlow.SERVERBOUND);
		}

		@Override
		public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T packetListener) {
			// no-op：不接入 netty 管线
		}

		@Override
		public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
			// no-op
		}

		@Override
		public boolean isConnected() {
			return true;
		}

		@Override
		public void send(Packet<?> packet) {
			// no-op：假人不收包
		}

		@Override
		public void send(Packet<?> packet, ChannelFutureListener listener) {
			// no-op
		}

		@Override
		public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
			// no-op
		}

		@Override
		public void flushChannel() {
			// no-op
		}

		@Override
		public void disconnect(net.minecraft.network.chat.Component reason) {
			// no-op
		}

		@Override
		public void disconnect(net.minecraft.network.DisconnectionDetails details) {
			// no-op
		}
	}

	private static boolean sameShape(Order a, Order b) {
		if (a.entries().size() != b.entries().size()) return false;
		for (int i = 0; i < a.entries().size(); i++) {
			if (!a.entries().get(i).itemId.equals(b.entries().get(i).itemId)
					|| a.entries().get(i).need != b.entries().get(i).need) return false;
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	private static Map<GameStateManager.Team, Order> ordersMap() throws Exception {
		var f = OrderManager.class.getDeclaredField("orders");
		f.setAccessible(true);
		return (Map<GameStateManager.Team, Order>) f.get(OrderManager.INSTANCE);
	}

	@SuppressWarnings("unchecked")
	private static Map<GameStateManager.Team, Long> deadlineMap() throws Exception {
		var f = OrderManager.class.getDeclaredField("deadline");
		f.setAccessible(true);
		return (Map<GameStateManager.Team, Long>) f.get(OrderManager.INSTANCE);
	}

	@SuppressWarnings("unchecked")
	private static Map<GameStateManager.Team, Integer> completedMap() throws Exception {
		var f = OrderManager.class.getDeclaredField("completed");
		f.setAccessible(true);
		return (Map<GameStateManager.Team, Integer>) f.get(OrderManager.INSTANCE);
	}

	private static Order orderFor(GameStateManager.Team t) {
		try {
			return ordersMap().get(t);
		} catch (Exception e) {
			return new Order(List.of(new Order.Entry("minecraft:pumpkin_pie", "FOOD", 1, 5)), 0);
		}
	}

	private static void setOrderForTest(GameStateManager.Team t, Order o) {
		try {
			ordersMap().put(t, o);
		} catch (Exception e) {
			FAILS.add("反射设置套餐失败: " + e);
		}
	}

	private static void setDeadlineForTest(GameStateManager.Team t, long tick) {
		try {
			deadlineMap().put(t, tick);
		} catch (Exception e) {
			FAILS.add("反射设置截止失败: " + e);
		}
	}

	private static long deadlineFor(GameStateManager.Team t) {
		try {
			return deadlineMap().getOrDefault(t, 0L);
		} catch (Exception e) {
			return 0L;
		}
	}

	private static int completedOf(GameStateManager.Team t) {
		try {
			return completedMap().getOrDefault(t, 0);
		} catch (Exception e) {
			return -1;
		}
	}

	private static void setCompletedForTest(GameStateManager.Team t, int v) {
		try {
			completedMap().put(t, v);
		} catch (Exception e) {
			FAILS.add("反射设置完成计数失败: " + e);
		}
	}

	// ==================== 反射辅助（随机点火 FireSpawner，自测用） ====================

	/** FireSpawner 三种候选列表的长度之和 */
	@SuppressWarnings("unchecked")
	private static int fireCandidateCount() {
		int n = 0;
		for (String f : List.of("YELLOW_CONCRETE", "ORANGE_TERRACOTTA", "BLUE_GLAZED")) {
			try {
				var fld = FireSpawner.class.getDeclaredField(f);
				fld.setAccessible(true);
				n += ((List<BlockPos>) fld.get(null)).size();
			} catch (Exception e) {
				FAILS.add("反射读取点火候选失败: " + e);
			}
		}
		return n;
	}

	/** 清空全部候选后只放一个「黄色混凝土」候选 → 让点火位置确定可断言 */
	@SuppressWarnings("unchecked")
	private static void setFireTargetOnly(BlockPos pos) {
		for (String f : List.of("YELLOW_CONCRETE", "ORANGE_TERRACOTTA", "BLUE_GLAZED")) {
			try {
				var fld = FireSpawner.class.getDeclaredField(f);
				fld.setAccessible(true);
				((List<BlockPos>) fld.get(null)).clear();
			} catch (Exception e) {
				FAILS.add("反射清空点火候选失败: " + e);
			}
		}
		try {
			var fld = FireSpawner.class.getDeclaredField("YELLOW_CONCRETE");
			fld.setAccessible(true);
			((List<BlockPos>) fld.get(null)).add(pos.immutable());
		} catch (Exception e) {
			FAILS.add("反射设置点火候选失败: " + e);
		}
	}

	/** 强制把下次点火时间点设为 0（跳过 15~20 秒排程，立即点火一次） */
	private static void setNextFireTick(long v) {
		try {
			var fld = FireSpawner.class.getDeclaredField("nextFireTick");
			fld.setAccessible(true);
			fld.setLong(null, v);
		} catch (Exception e) {
			FAILS.add("反射设置点火排程失败: " + e);
		}
	}

	/** FireSpawner.FIRES 登记表里的火焰格数（= 仍在倒计时、尚未熄灭的火焰） */
	@SuppressWarnings("unchecked")
	private static int fireTrackedCount() {
		try {
			var fld = FireSpawner.class.getDeclaredField("FIRES");
			fld.setAccessible(true);
			return ((Map<BlockPos, Long>) fld.get(null)).size();
		} catch (Exception e) {
			FAILS.add("反射读取火焰登记失败: " + e);
			return -1;
		}
	}

	/** 把 FIRES 里全部到期 tick 提前到 0 → 下一次 tick 即判定为已到点（模拟 60 秒存续已满） */
	@SuppressWarnings("unchecked")
	private static void forceFiresExpired() {
		try {
			var fld = FireSpawner.class.getDeclaredField("FIRES");
			fld.setAccessible(true);
			var m = (Map<BlockPos, Long>) fld.get(null);
			for (var k : new ArrayList<>(m.keySet())) m.put(k, 0L);
		} catch (Exception e) {
			FAILS.add("反射提前火焰到期失败: " + e);
		}
	}

	/** 火焰十字铺的 5 个位置（中心 + 四邻） */
	private static List<BlockPos> fireCross(BlockPos base) {
		BlockPos c = base.above();
		List<BlockPos> l = new ArrayList<>(5);
		l.add(c);
		for (Direction d : Direction.Plane.HORIZONTAL) l.add(c.relative(d));
		return l;
	}

	private static void finish() {
		HellKitchen.LOGGER.info("[HK-TEST] ==================================");
		HellKitchen.LOGGER.info("[HK-TEST] 结果: {} PASS / {} FAIL / {} SKIP", passes, FAILS.size(), skips);
		for (String f : FAILS) HellKitchen.LOGGER.error("[HK-TEST][FAIL-汇总] {}", f);
		HellKitchen.LOGGER.info("[HK-TEST] ==================================");
		srv.halt(false);
	}
}
