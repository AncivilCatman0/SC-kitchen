package com.hellkitchen.supply;

import com.hellkitchen.HellKitchen;
import com.hellkitchen.KitchenMap;
import com.hellkitchen.order.OrderManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 补给（需求 8）：发射器里的物品弹出后、箱子里的物品被取出后，**立即补货**（恢复初始快照）。
 *
 * <p>容器的发现走两条路：区块加载时扫描（发射器通常由按钮触发，不会被右键）；
 * 玩家右键交互时也会登记。配置 {@code resupplyPoints = ["all"]} 表示整张地图生效。
 */
public final class SupplyManager {

	public static final SupplyManager INSTANCE = new SupplyManager();

	/** 容器坐标 → 初始内容快照 */
	private final Map<BlockPos, List<ItemStack>> snapshots = new HashMap<>();

	private SupplyManager() {}

	public void reset() {
		snapshots.clear();
	}

	/** 登记一个容器（幂等；首次登记时记录快照） */
	public void register(ServerLevel level, BlockPos pos, BlockEntity be) {
		if (pos == null || be == null) return;
		if (!isResupplyBlock(be)) return;
		if (!(be instanceof Container c)) return;
		BlockPos key = pos.immutable();
		if (snapshots.containsKey(key)) return;
		List<ItemStack> snap = new ArrayList<>(c.getContainerSize());
		for (int i = 0; i < c.getContainerSize(); i++) snap.add(c.getItem(i).copy());
		snapshots.put(key, snap);
	}

	/** 扫描一个区块内的全部补给容器（CHUNK_LOAD 时调用） */
	public void scanChunk(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
		try {
			for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
				register(level, e.getKey(), e.getValue());
			}
		} catch (Exception ex) {
			HellKitchen.LOGGER.debug("[HK] 补给容器扫描失败：{}", ex.toString());
		}
	}

	/** 每 2 tick 检查一次：被取出/弹出的槽位立即补回初始物品 */
	public void tick(MinecraftServer server) {
		if (!OrderManager.INSTANCE.isActive()) return;
		if (server.getTickCount() % 2 != 0) return;
		ServerLevel level = KitchenMap.level(server);
		if (level == null) return;
		for (Map.Entry<BlockPos, List<ItemStack>> e : snapshots.entrySet()) {
			BlockPos pos = e.getKey();
			if (!level.isLoaded(pos)) continue;
			if (!(level.getBlockEntity(pos) instanceof Container c)) continue;
			List<ItemStack> snap = e.getValue();
			for (int i = 0; i < snap.size() && i < c.getContainerSize(); i++) {
				ItemStack want = snap.get(i);
				if (want.isEmpty()) continue;
				ItemStack cur = c.getItem(i);
				if (cur.isEmpty() || !cur.is(want.getItem()) || cur.getCount() < want.getCount()) {
					c.setItem(i, want.copy());
					c.setChanged();
				}
			}
		}
	}

	/** 该坐标是否已登记（自测用） */
	public boolean isRegistered(BlockPos pos) {
		return snapshots.containsKey(pos.immutable());
	}

	/** 已登记的补给容器数量（自测用） */
	public int size() {
		return snapshots.size();
	}

	public static boolean isResupplyBlock(BlockEntity be) {
		var block = be.getBlockState().getBlock();
		// 铜箱家族（含自然氧化与涂蜡变体）：地图用它做补给箱（东西基地各 1 个，内装喷溅药水）
		if (block instanceof CopperChestBlock) return true;
		return block == Blocks.DISPENSER || block == Blocks.DROPPER || block == Blocks.CHEST
				|| block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL;
	}
}
