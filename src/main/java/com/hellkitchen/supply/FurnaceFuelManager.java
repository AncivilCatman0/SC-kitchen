package com.hellkitchen.supply;

import com.hellkitchen.HellKitchen;
import com.hellkitchen.KitchenMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashSet;
import java.util.Set;

/**
 * 熔炉/烟熏炉燃料自动补货（第九十三批）：
 * <ul>
 *   <li>区块加载时扫描登记所有 {@link AbstractFurnaceBlockEntity}（熔炉 / 高炉 / 烟熏炉 / 农夫乐事烟熏炉）</li>
 *   <li>每 tick 检查已登记熔炉的燃料槽（index=1），空了就塞一块 {@link Items#COAL}（仅限煤炭）</li>
 * </ul>
 *
 * <p>边界：
 * <ul>
 *   <li>仅补燃料槽（index=1），不碰输入槽 / 输出槽</li>
 *   <li>仅补煤炭；如果玩家放了非煤炭燃料（木头 / 熔岩桶 / 干海带等）→ 不动，尊重玩家选择</li>
 *   <li>只在地狱厨房对局进行中（OrderManager.isActive()）时才补货，大厅 / 结算阶段停摆</li>
 * </ul>
 */
public final class FurnaceFuelManager {

	/** 熔炉燃料槽 index（原版 AbstractFurnaceBlockEntity 定义） */
	private static final int FUEL_SLOT = 1;

	/** 已登记的熔炉坐标集合（加载即扫描；区块卸载不会从集合里移除，tick 时用 level.isLoaded 门控） */
	private static final Set<BlockPos> FURNACES = new HashSet<>();

	private FurnaceFuelManager() {}

	public static void reset() {
		FURNACES.clear();
	}

	/** 区块加载时扫描登记熔炉（由 KitchenEvents 的 CHUNK_LOAD 回调调用） */
	public static void scanChunk(LevelChunk chunk) {
		if (chunk == null) return;
		try {
			for (var e : chunk.getBlockEntities().entrySet()) {
				if (e.getValue() instanceof AbstractFurnaceBlockEntity) {
					FURNACES.add(e.getKey().immutable());
				}
			}
		} catch (Exception ex) {
			HellKitchen.LOGGER.debug("[HK] 熔炉燃料补货扫描失败：{}", ex.toString());
		}
	}

	/** 每 tick 遍历登记的熔炉，空燃料槽塞煤炭（由 KitchenStyleProvider.onServerTick 调用） */
	public static void tick(MinecraftServer server) {
		if (!com.hellkitchen.order.OrderManager.INSTANCE.isActive()) return;
		ServerLevel level = KitchenMap.level(server);
		if (level == null) return;
		for (BlockPos pos : FURNACES) {
			if (!level.isLoaded(pos)) continue;
			BlockEntity be = level.getBlockEntity(pos);
			if (!(be instanceof AbstractFurnaceBlockEntity f)) continue;
			ItemStack fuel = f.getItem(FUEL_SLOT);
			if (!fuel.isEmpty()) continue; // 玩家放了燃料（哪怕不是煤炭）→ 不动
			f.setItem(FUEL_SLOT, new ItemStack(Items.COAL, 1));
			f.setChanged();
		}
	}

	/** 已登记熔炉数量（自测用） */
	public static int size() { return FURNACES.size(); }

	/** 某坐标是否已登记（自测用） */
	public static boolean isRegistered(BlockPos pos) {
		return FURNACES.contains(pos.immutable());
	}
}
