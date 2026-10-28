package com.hellkitchen;

import com.survivalcontract.dimension.FixedMapInjector;
import com.survivalcontract.dimension.LobbyManager;
import com.survivalcontract.mixin.MinecraftServerAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * 固定地图（街角灶源 1.0.7 主世界）注入与维度绑定（需求 2）：
 * <ul>
 *   <li>SERVER_STARTING：把 jar 内置 .mca 注入 &lt;world&gt;/dimensions/hell-kitchen/kitchen/
 *       （复用 SC 的 {@link FixedMapInjector}，保留 zip → 散装文件夹 → jar 内置 三级优先）</li>
 *   <li>SERVER_STARTED：取维度 ServerLevel 并套用世界规则（禁刷怪 + 锁晴天，复用 SC 的 applyLobbyRules）</li>
 *   <li>每局开局前：{@link #resetDimension} 把维度重置回初始地图（删档 → 重注入 → 重建维度），
 *       避免上一局的建造/破坏残留</li>
 * </ul>
 * 维度靠数据包 {@code data/hell-kitchen/dimension/kitchen.json} + {@code dimension_type/kitchen.json} 注册。
 */
public final class KitchenMap {

	/** 厨房维度 key */
	public static final ResourceKey<Level> KITCHEN_LEVEL = ResourceKey.create(
			Registries.DIMENSION, Identifier.fromNamespaceAndPath(HellKitchen.MOD_ID, "kitchen"));

	/** jar 内资源前缀 */
	private static final String RESOURCE_PREFIX = "data/hell-kitchen/kitchen/";

	/**
	 * 初始地图快照目录（&lt;world&gt;/hell-kitchen-pristine）：
	 * 开局注入并通过校验后落一份，重置时优先用它恢复 —— 这样即使 mod jar 在游戏运行中被替换，
	 * 重置也不依赖 jar，仍能回到「已做好的厨房地图」的初始状态。
	 */
	private static final String PRISTINE_DIR = "hell-kitchen-pristine";

	/**
	 * 内置地图版本（街角灶源 1.0.7）。
	 * <p>{@link FixedMapInjector#verify} 只比对文件大小，而 1.0.6 → 1.0.7 的 .mca
	 * 字节数完全相同、内容不同 → 老存档里的 pristine 快照会被误判为「有效」并继续恢复旧图。
	 * 因此快照目录额外写一份版本标记文件，版本不一致时删除并重建快照。
	 */
	private static final String MAP_VERSION = "1.0.7";

	/** 快照目录里的地图版本标记文件名 */
	private static final String VERSION_FILE = "map-version.txt";

	/** 每个子目录（region/entities/poi）的 jar 内置 .mca 清单（与 FixedMapInjector.MCA_SUBDIRS 同序） */
	private static final String[][] MCA_FILES = {
			// region（1.0.6 起新增北侧远景 r.-3.*）
			{"r.0.0.mca", "r.0.-1.mca", "r.0.-2.mca", "r.0.-3.mca", "r.-1.0.mca", "r.-1.-1.mca", "r.-1.-2.mca",
					"r.-1.-3.mca", "r.-2.0.mca", "r.-2.-1.mca"},
			// entities
			{"r.0.0.mca", "r.0.-1.mca", "r.0.-2.mca", "r.-1.0.mca", "r.-1.-1.mca", "r.-1.-2.mca"},
			// poi
			{"r.0.0.mca", "r.0.-1.mca", "r.0.-2.mca", "r.0.-3.mca", "r.-1.0.mca", "r.-1.-1.mca", "r.-1.-2.mca",
					"r.-1.-3.mca", "r.-2.0.mca", "r.-2.-1.mca"}
	};

	private static ServerLevel cachedLevel;

	private KitchenMap() {}

	public static void register() {
		// 维度加载前注入固定地图
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			KitchenConfig.load();
			boolean ok = FixedMapInjector.inject(server, HellKitchen.MOD_ID, "kitchen", RESOURCE_PREFIX, MCA_FILES,
					KitchenConfig.findZip(), KitchenConfig.folderDir(), "地狱厨房地图");
			if (!ok) {
				HellKitchen.LOGGER.error("地狱厨房地图注入失败或校验不通过：mod jar 可能已在游戏运行中被替换 → 请重启游戏（本次沿用存档里已有的地图）");
			}
		});

		// 维度就绪后绑定 + 世界规则
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			ServerLevel level = server.getLevel(KITCHEN_LEVEL);
			cachedLevel = level;
			if (level == null) {
				HellKitchen.LOGGER.warn("地狱厨房维度未加载（数据包缺失？）");
				return;
			}
			applyRules(level);
			snapshotPristine(server);
			HellKitchen.LOGGER.info("地狱厨房维度已加载：{}", level.dimension());
		});
	}

	/** 取厨房维度（未加载返回 null） */
	public static ServerLevel level(MinecraftServer server) {
		return server == null ? cachedLevel : server.getLevel(KITCHEN_LEVEL);
	}

	public static ServerLevel cachedLevel() { return cachedLevel; }

	/** 世界规则：禁止生成生物 + 天气锁定为晴天（需求 2）+ 关闭火焰蔓延（本玩法） */
	public static void applyRules(ServerLevel level) {
		if (level == null) return;
		LobbyManager.getInstance().applyLobbyRules(level);
		// 本玩法关闭原版火焰蔓延：26.2 起 gamerule doFireTick 已被
		// fire_spread_radius_around_player 取代，置 0 即「玩家周围 0 格内才可蔓延」→ 实际不再扩散
		try {
			level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER,
					0, level.getServer());
		} catch (Exception e) {
			HellKitchen.LOGGER.warn("关闭地狱厨房火焰蔓延失败：{}", e.getMessage());
		}
	}

	/**
	 * 把厨房维度重置为初始状态（恢复初始地图 → 删档 → 重建维度）。
	 *
	 * <p>关键约束：**先把初始地图恢复到暂存目录并逐文件校验**，校验通过才动当前维度。
	 * 恢复失败（例如 mod jar 在游戏运行中被替换导致内置地图读不出来）时，直接保留当前维度，
	 * 绝不把空地图交给超平坦生成器 —— 那会把玩家扔进石头超平坦。
	 *
	 * <ol>
	 *   <li>用 {@code <dim>.tmp} 暂存：优先从初始地图快照 {@link #PRISTINE_DIR} 复制，否则从 jar / 外部覆盖重新注入</li>
	 *   <li>把仍在厨房维度内的玩家（异常流程兜底）送回大厅</li>
	 *   <li>关闭并从 {@code MinecraftServer.levels} 移除当前厨房 ServerLevel</li>
	 *   <li>递归删除维度存档目录，把已校验的暂存地图拷入</li>
	 *   <li>用数据包注册的 LevelStem 重新构造 ServerLevel 并注册回 levels</li>
	 *   <li>重套世界规则（禁刷怪 + 锁晴天）并更新缓存</li>
	 * </ol>
	 */
	public static void resetDimension(MinecraftServer server) {
		if (server == null) return;

		Path dir = dimensionDir(server);
		Path stage = dir.resolveSibling(dir.getFileName() + ".tmp");

		// 1) 先在暂存目录准备好初始地图（失败则当前维度原样保留）
		if (Files.exists(stage)) deleteRecursiveWithRetry(stage);
		if (!restoreTo(stage, server)) {
			HellKitchen.LOGGER.error("地狱厨房初始地图恢复失败（mod jar 可能已在游戏运行中被替换）"
					+ " → 本局保留当前维度，请重启游戏后再开局");
			deleteRecursiveWithRetry(stage);
			return;
		}

		// 2) 防御：若有玩家仍停留在厨房维度（异常流程），先送回大厅，避免持有即将失效的维度引用
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.level().dimension().equals(KITCHEN_LEVEL)) {
				LobbyManager.getInstance().teleportToLobby(p);
			}
		}

		// 3) 关闭并移除当前维度（存在才处理）
		Map<ResourceKey<Level>, ServerLevel> levels = ((MinecraftServerAccessor) server).sc$getLevels();
		ServerLevel old = levels.remove(KITCHEN_LEVEL);
		if (old != null) {
			try {
				old.close(); // 关闭区块缓存/区域文件句柄（不落盘保存——反正要删）
			} catch (IOException e) {
				HellKitchen.LOGGER.warn("关闭地狱厨房维度失败：{}", e.getMessage());
			}
		}
		cachedLevel = null;

		// 4) 删档 → 把已校验的暂存地图搬入
		if (Files.exists(dir)) deleteRecursiveWithRetry(dir);
		boolean installed;
		try {
			Files.createDirectories(dir);
			installed = FixedMapInjector.copyFromFolder(stage, dir) && sameSizes(stage, dir);
		} catch (Exception e) {
			HellKitchen.LOGGER.error("地狱厨房地图搬入失败", e);
			installed = false;
		}
		deleteRecursiveWithRetry(stage);
		if (!installed) {
			HellKitchen.LOGGER.error("地狱厨房地图搬入后校验不通过，本局无法重建维度：{}", dir);
			return;
		}

		// 5) 重建 ServerLevel（数据包注册的 LevelStem）
		LevelStorageSource.LevelStorageAccess storage = ((MinecraftServerAccessor) server).sc$getStorageSource();
		Registry<LevelStem> stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
		LevelStem stem = stems.getValue(ResourceKey.create(Registries.LEVEL_STEM,
				Identifier.fromNamespaceAndPath(HellKitchen.MOD_ID, "kitchen")));
		if (stem == null) {
			HellKitchen.LOGGER.error("地狱厨房维度类型缺失（数据包未加载？），维度重置中止");
			return;
		}
		Executor executor = ((MinecraftServerAccessor) server).sc$getExecutor();
		ServerLevelData overworldData = server.getWorldData().overworldData();
		long obfSeed = BiomeManager.obfuscateSeed(server.overworld().getSeed());
		ServerLevel level = new ServerLevel(server, executor, storage,
				new DerivedLevelData(server.getWorldData(), overworldData),
				KITCHEN_LEVEL, stem, false, obfSeed, List.of(), false);
		levels.put(KITCHEN_LEVEL, level);

		// 6) 世界边界监听 + 世界规则
		level.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());
		server.getPlayerList().addWorldborderListener(level);
		cachedLevel = level;
		applyRules(level);
		HellKitchen.LOGGER.info("地狱厨房维度已重置为初始状态：{}", level.dimension());
	}

	/** 维度存档目录（即 region / entities / poi 的父目录） */
	private static Path dimensionDir(MinecraftServer server) {
		LevelStorageSource.LevelStorageAccess storage =
				((MinecraftServerAccessor) server).sc$getStorageSource();
		return storage.getDimensionPath(KITCHEN_LEVEL);
	}

	/** 把初始地图准备到 stage：优先用开局快照，其次从 jar / 外部覆盖重新注入。返回是否完整可用 */
	private static boolean restoreTo(Path stage, MinecraftServer server) {
		Path pristine = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
				.resolve(PRISTINE_DIR);
		if (pristineCurrent(pristine)) {
			try {
				Files.createDirectories(stage);
				if (FixedMapInjector.copyFromFolder(pristine, stage)
						&& FixedMapInjector.verify(stage, RESOURCE_PREFIX, MCA_FILES)) {
					HellKitchen.LOGGER.info("地狱厨房地图重置：已从初始地图快照恢复（{}）", pristine);
					return true;
				}
				HellKitchen.LOGGER.warn("初始地图快照复制不完整，改用 jar 内置地图重新注入");
			} catch (Exception e) {
				HellKitchen.LOGGER.warn("从初始地图快照恢复失败，改用 jar 内置地图：{}", e.toString());
			}
			deleteRecursiveWithRetry(stage);
		}
		return FixedMapInjector.injectInto(stage, RESOURCE_PREFIX, MCA_FILES,
				KitchenConfig.findZip(), KitchenConfig.folderDir(), "地狱厨房地图");
	}

	/** 开局时落一份「初始地图」快照（内容 = 本次开局注入并通过校验的原始地图） */
	private static void snapshotPristine(MinecraftServer server) {
		Path pristine = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
				.resolve(PRISTINE_DIR);
		if (pristineCurrent(pristine)) return; // 已有、完整且与当前内置地图版本一致
		if (Files.exists(pristine)) {
			HellKitchen.LOGGER.info("地狱厨房初始地图快照版本不是 {} → 删除并重建", MAP_VERSION);
		}
		deleteRecursiveWithRetry(pristine);
		Path dimDir = dimensionDir(server);
		if (!FixedMapInjector.verify(dimDir, RESOURCE_PREFIX, MCA_FILES)) {
			HellKitchen.LOGGER.warn("地狱厨房地图未通过校验，本次不建立初始地图快照（重置时将改为从 jar 重新注入）");
			return;
		}
		try {
			if (FixedMapInjector.copyFromFolder(dimDir, pristine) && sameSizes(dimDir, pristine)) {
				writeVersion(pristine);
				HellKitchen.LOGGER.info("已建立地狱厨房初始地图快照：{}（版本 {}）", pristine, MAP_VERSION);
			} else {
				HellKitchen.LOGGER.warn("地狱厨房初始地图快照不完整，已丢弃：{}", pristine);
				deleteRecursiveWithRetry(pristine);
			}
		} catch (Exception e) {
			HellKitchen.LOGGER.warn("建立地狱厨房初始地图快照失败：{}", e.toString());
			deleteRecursiveWithRetry(pristine);
		}
	}

	/** 快照是否「完整 + 版本与当前内置地图一致」（版本不一致说明是换图前的老快照，必须重建） */
	private static boolean pristineCurrent(Path pristine) {
		if (!FixedMapInjector.verify(pristine, RESOURCE_PREFIX, MCA_FILES)) return false;
		Path vf = pristine.resolve(VERSION_FILE);
		try {
			return Files.isRegularFile(vf)
					&& MAP_VERSION.equals(Files.readString(vf, java.nio.charset.StandardCharsets.UTF_8).trim());
		} catch (IOException e) {
			return false;
		}
	}

	/** 在快照目录写入地图版本标记 */
	private static void writeVersion(Path pristine) {
		Path vf = pristine.resolve(VERSION_FILE);
		try {
			Files.writeString(vf, MAP_VERSION, java.nio.charset.StandardCharsets.UTF_8);
		} catch (IOException e) {
			HellKitchen.LOGGER.warn("写入地狱厨房地图版本标记失败：{}", vf, e);
		}
	}

	/** 两个地图目录是否拥有齐全且大小一致的 .mca（用于复制完整性校验） */
	private static boolean sameSizes(Path a, Path b) {
		for (int i = 0; i < FixedMapInjector.MCA_SUBDIRS.length && i < MCA_FILES.length; i++) {
			for (String name : MCA_FILES[i]) {
				Path fa = a.resolve(FixedMapInjector.MCA_SUBDIRS[i]).resolve(name);
				Path fb = b.resolve(FixedMapInjector.MCA_SUBDIRS[i]).resolve(name);
				try {
					if (!Files.isRegularFile(fa) || !Files.isRegularFile(fb)) return false;
					if (Files.size(fa) != Files.size(fb)) return false;
				} catch (IOException e) {
					return false;
				}
			}
		}
		return true;
	}

	/** 递归删除维度存档文件夹（Windows 下文件句柄释放有延迟 → 最多重试 10 次） */
	private static void deleteRecursiveWithRetry(Path dir) {
		if (!Files.exists(dir)) return;
		for (int attempt = 1; attempt <= 10; attempt++) {
			try (Stream<Path> walk = Files.walk(dir)) {
				walk.sorted(Comparator.reverseOrder()).forEach(p -> {
					try {
						Files.delete(p);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				});
				HellKitchen.LOGGER.info("已删除地狱厨房维度存档：{}", dir);
				return;
			} catch (Exception e) {
				if (attempt == 10) {
					HellKitchen.LOGGER.error("删除地狱厨房维度存档失败（重试 10 次）：{}", dir, e);
				} else {
					try {
						Thread.sleep(200);
					} catch (InterruptedException ignored) {
						Thread.currentThread().interrupt();
					}
				}
			}
		}
	}
}
