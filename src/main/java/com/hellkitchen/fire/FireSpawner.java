package com.hellkitchen.fire;

import com.hellkitchen.HellKitchen;
import com.hellkitchen.KitchenMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 随机点火：
 * <ul>
 *   <li>每 30~45 秒（第九十三批：原为 15~20 秒）：在地图里的「黄色混凝土 / 橙色陶瓦 / 蓝色带釉陶瓦」中随机选一种、再随机选其中的一个方块，
 *       在其**上方**生成燃烧的火焰；火焰以十字形铺开（中心 + 四邻，共至多 5 格）→ 视觉上比单格火焰更大</li>
 *   <li>每簇火焰存续 **60 秒**，到点由本类主动清除（{@link #extinguishExpired}）——
 *       不依赖原版烧尽逻辑，避免火焰在地板上长期残留</li>
 *   <li>本玩法已关闭原版火焰蔓延（26.2 gamerule {@code fire_spread_radius_around_player=0}，
 *       见 {@link KitchenMap#applyRules}），所以火焰只在该点燃烧、不会向外扩散</li>
 * </ul>
 *
 * <p>候选方块坐标**不做全量地图扫描**（该地图区块数约六千，全量加载会长时间卡顿并爆内存），
 * 而是复用「区块加载即扫描」路径（与 {@code SupplyManager} 相同）增量收集玩家所到之处的目标方块。
 */
public final class FireSpawner {

	/** 点火间隔下限：30 秒（第九十三批：原为 15 秒） */
	private static final int MIN_INTERVAL_TICKS = 30 * 20;
	/** 点火间隔上限：45 秒（第九十三批：原为 20 秒） */
	private static final int MAX_INTERVAL_TICKS = 45 * 20;
	/** 单次点火最多尝试挑选的位置数（避开上方非空的位置） */
	private static final int MAX_ATTEMPTS = 32;
	/** 火焰存续时长：60 秒（到点自动熄灭） */
	public static final int FIRE_LIFETIME_TICKS = 60 * 20;

	/** 已点燃的火焰坐标 → 预定熄灭的 tick */
	private static final Map<BlockPos, Long> FIRES = new HashMap<>();

	/** 三种目标方块（26.x 起彩色方块统一收进 ColorCollection） */
	private static Block yellowConcrete() { return Blocks.CONCRETE.yellow(); }
	private static Block orangeTerracotta() { return Blocks.DYED_TERRACOTTA.orange(); }
	private static Block blueGlazedTerracotta() { return Blocks.GLAZED_TERRACOTTA.blue(); }

	private static final List<BlockPos> YELLOW_CONCRETE = new ArrayList<>();
	private static final List<BlockPos> ORANGE_TERRACOTTA = new ArrayList<>();
	private static final List<BlockPos> BLUE_GLAZED = new ArrayList<>();

	private static final Random RND = new Random();
	/** 下次点火的时间点（tick）；&lt;0 表示未排程 */
	private static long nextFireTick = -1;

	private FireSpawner() {}

	/** 开局：清空候选并排定首次点火时间 */
	public static void start(MinecraftServer server) {
		reset();
		scheduleNext(server.getTickCount());
	}

	/** 清空全部候选与排程（对局结束 / 维度重建后调用——坐标已随维度重建失效） */
	public static void reset() {
		YELLOW_CONCRETE.clear();
		ORANGE_TERRACOTTA.clear();
		BLUE_GLAZED.clear();
		FIRES.clear();
		nextFireTick = -1;
	}

	private static void scheduleNext(long now) {
		nextFireTick = now + MIN_INTERVAL_TICKS + RND.nextInt(MAX_INTERVAL_TICKS - MIN_INTERVAL_TICKS + 1);
	}

	/**
	 * 区块加载时扫描该区块内的三种目标方块（由 {@code KitchenEvents} 的 CHUNK_LOAD 回调调用）。
	 * 走 {@code ChunkAccess.findBlocks}，内部按 section 调色板预筛，成本极低。
	 */
	public static void scanChunk(LevelChunk chunk) {
		if (chunk == null) return;
		try {
			chunk.findBlocks(FireSpawner::isTarget, (pos, state) -> {
				Block b = state.getBlock();
				if (b == yellowConcrete()) YELLOW_CONCRETE.add(pos.immutable());
				else if (b == orangeTerracotta()) ORANGE_TERRACOTTA.add(pos.immutable());
				else if (b == blueGlazedTerracotta()) BLUE_GLAZED.add(pos.immutable());
			});
		} catch (Exception e) {
			HellKitchen.LOGGER.debug("[HK] 随机点火候选扫描失败：{}", e.toString());
		}
	}

	private static boolean isTarget(BlockState state) {
		Block b = state.getBlock();
		return b == yellowConcrete() || b == orangeTerracotta() || b == blueGlazedTerracotta();
	}

	/** 每 tick：先处理到点的火焰熄灭，再按排程点火（内部各自门控） */
	public static void tick(MinecraftServer server) {
		long now = server.getTickCount();
		extinguishExpired(server, now);
		if (nextFireTick < 0) return;
		if (now < nextFireTick) return;
		scheduleNext(now);
		ServerLevel level = KitchenMap.level(server);
		if (level == null) return;
		if (!hasCandidates()) {
			// 候选为空（玩家尚未走到含目标方块的区域）→ 兜底再扫一遍玩家附近区块，等下一周期
			recoveryScan(level, server);
			return;
		}
		igniteRandom(level);
	}

	/** 到点熄灭：火焰存续满 {@link #FIRE_LIFETIME_TICKS}（60 秒）即清除；已自行烧完的顺带移除记录 */
	private static void extinguishExpired(MinecraftServer server, long now) {
		if (FIRES.isEmpty()) return;
		ServerLevel level = KitchenMap.level(server);
		if (level == null) {
			FIRES.clear();
			return;
		}
		var it = FIRES.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			BlockPos p = e.getKey();
			if (!level.isLoaded(p)) continue; // 区块未加载：等加载后再处理，避免留下永久火焰
			if (level.getBlockState(p).getBlock() != Blocks.FIRE) {
				it.remove(); // 已被原版烧尽 / 被其它逻辑替换
				continue;
			}
			if (now < e.getValue()) continue;
			level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			it.remove();
		}
	}

	private static boolean hasCandidates() {
		return !YELLOW_CONCRETE.isEmpty() || !ORANGE_TERRACOTTA.isEmpty() || !BLUE_GLAZED.isEmpty();
	}

	/** 从三种方块中随机取一种的候选列表（只取非空的） */
	private static List<BlockPos> pickPool() {
		List<List<BlockPos>> pools = new ArrayList<>(3);
		if (!YELLOW_CONCRETE.isEmpty()) pools.add(YELLOW_CONCRETE);
		if (!ORANGE_TERRACOTTA.isEmpty()) pools.add(ORANGE_TERRACOTTA);
		if (!BLUE_GLAZED.isEmpty()) pools.add(BLUE_GLAZED);
		return pools.isEmpty() ? List.of() : pools.get(RND.nextInt(pools.size()));
	}

	private static void igniteRandom(ServerLevel level) {
		List<BlockPos> pool = pickPool();
		if (pool.isEmpty()) return;
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			BlockPos base = pool.get(RND.nextInt(pool.size()));
			if (!level.isLoaded(base)) continue;
			BlockPos center = base.above();
			if (!canIgnite(level, center)) continue; // 上方非空（被墙/天花板占住）→ 换一个
			int placed = ignite(level, center);
			if (placed > 0) {
				HellKitchen.LOGGER.info("[HK] 随机点火：{}（{}）上方生成火焰 {} 格",
						base, level.getBlockState(base).getBlock(), placed);
				return;
			}
		}
	}

	/** 以 center 为中心铺火焰：中心 + 可放的四邻（十字形）→ 视觉上比单格火焰更大；蔓延已关闭，不会外扩 */
	private static int ignite(ServerLevel level, BlockPos center) {
		List<BlockPos> spots = new ArrayList<>(5);
		if (canIgnite(level, center)) spots.add(center);
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = center.relative(d);
			if (canIgnite(level, n)) spots.add(n);
		}
		if (spots.isEmpty()) return 0;
		int placed = 0;
		long expireAt = level.getServer() == null ? 0L
				: level.getServer().getTickCount() + FIRE_LIFETIME_TICKS;
		for (BlockPos p : spots) {
			if (level.setBlock(p, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL)) {
				FIRES.put(p.immutable(), expireAt);
				placed++;
			}
		}
		return placed;
	}

	/** 该位置能否放置火焰（已加载 + 是空气/可替换方块） */
	private static boolean canIgnite(ServerLevel level, BlockPos pos) {
		if (!level.isLoaded(pos)) return false;
		BlockState st = level.getBlockState(pos);
		return st.isAir() || st.canBeReplaced();
	}

	/** 兜底：候选为空时，强制加载一名非观战玩家所在区块及其八邻并扫描（区块多已加载，成本极低） */
	private static void recoveryScan(ServerLevel level, MinecraftServer server) {
		List<ServerPlayer> here = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!p.isSpectator() && p.level().dimension().equals(KitchenMap.KITCHEN_LEVEL)) here.add(p);
		}
		if (here.isEmpty()) return;
		ServerPlayer p = here.get(RND.nextInt(here.size()));
		int cx = p.getBlockX() >> 4;
		int cz = p.getBlockZ() >> 4;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				scanChunk(level.getChunk(cx + dx, cz + dz));
			}
		}
		HellKitchen.LOGGER.info("[HK] 随机点火候选为空 → 已补充扫描玩家附近区块（当前候选 {} 个）",
				YELLOW_CONCRETE.size() + ORANGE_TERRACOTTA.size() + BLUE_GLAZED.size());
	}
}
