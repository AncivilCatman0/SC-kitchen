package com.hellkitchen;

import com.hellkitchen.burn.BurnTracker;
import com.hellkitchen.frame.FrameLock;
import com.hellkitchen.supply.SupplyManager;
import com.hellkitchen.tool.PlaceGuard;
import com.survivalcontract.game.GameStateManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 地狱厨房事件挂载：补给容器登记/扫描、烹饪锅登记、展示框锁定。
 */
public final class KitchenEvents {

	private KitchenEvents() {}

	public static void register() {
		// ============ 右键方块 ============
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (!(player instanceof ServerPlayer sp) || world.isClientSide()) return InteractionResult.PASS;
			if (!world.dimension().equals(KitchenMap.KITCHEN_LEVEL)) return InteractionResult.PASS;
			if (GameStateManager.getInstance().getCurrentPhase() != GameStateManager.GamePhase.PLAYING) {
				return InteractionResult.PASS;
			}
			BlockPos pos = hitResult.getBlockPos();
			var state = world.getBlockState(pos);

			// 冒险模式放行：手持可放置菜肴（农夫乐事的派/盛宴方块）时，本 tick 临时切生存，
			// 让「右键放下 → 用碗盛装」在冒险模式下也能完成；下一 tick 由 onServerTick 恢复冒险
			if (world.getServer() != null) {
				PlaceGuard.tryAllowPlace(sp, sp.getItemInHand(hand), world.getServer().getTickCount());
			}

			// 需求 10：登记烹饪锅（停留计时用）
			String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			if (KitchenConfig.get().cookingPots.contains(blockId)) {
				BurnTracker.registerPot(pos);
			}

			// 需求 8：右键时登记补给容器
			if (world instanceof ServerLevel level) {
				BlockEntity be = level.getBlockEntity(pos);
				if (be != null) SupplyManager.INSTANCE.register(level, pos, be);
			}
			return InteractionResult.PASS;
		});

		// ============ 右键使用物品（未命中方块：瞄准空气也能放下方块，走 useItem 路径） ============
		// 与上面的放行同理：服务端放置判定同样晚于本回调（Fabric 在 useItem 的 HEAD 注入）
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (!(player instanceof ServerPlayer sp) || world.isClientSide()) return InteractionResult.PASS;
			if (!world.dimension().equals(KitchenMap.KITCHEN_LEVEL)) return InteractionResult.PASS;
			if (GameStateManager.getInstance().getCurrentPhase() != GameStateManager.GamePhase.PLAYING) {
				return InteractionResult.PASS;
			}
			if (world.getServer() != null) {
				PlaceGuard.tryAllowPlace(sp, sp.getItemInHand(hand), world.getServer().getTickCount());
			}
			return InteractionResult.PASS;
		});

		// ============ 左键实体（展示框锁定，需求 9） ============
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClientSide()) return InteractionResult.PASS;
			return FrameLock.onAttack(player, entity);
		});

		// ============ 区块加载：扫描补给容器（需求 8）+ 随机点火候选方块 ============
		// 该 Fabric API 版本的 Load 回调签名为 (ServerLevel, LevelChunk, boolean)
		ServerChunkEvents.CHUNK_LOAD.register((ServerLevel level,
		                                       net.minecraft.world.level.chunk.LevelChunk chunk,
		                                       boolean newlyGenerated) -> {
			if (!level.dimension().equals(KitchenMap.KITCHEN_LEVEL)) return;
			SupplyManager.INSTANCE.scanChunk(level, chunk);
			com.hellkitchen.fire.FireSpawner.scanChunk(chunk);
			// 熔炉燃料补货：登记所有 AbstractFurnaceBlockEntity（熔炉/高炉/烟熏炉/农夫乐事烟熏炉）
			com.hellkitchen.supply.FurnaceFuelManager.scanChunk(chunk);
		});
	}
}
