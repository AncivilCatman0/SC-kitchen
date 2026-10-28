package com.hellkitchen.net;

import com.hellkitchen.HellKitchen;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 地狱厨房网络包：S2C 下发本方套餐（需求 6：双方套餐不同且只能看见自己的套餐）。
 */
public final class KitchenNet {

	/** 套餐内容条目（HUD 渲染用：图标 + 名称 + 已交/需交 + 单品分值） */
	public record OrderEntry(String itemId, int need, int delivered, int scoreEach) {}

	/** S2C：本方套餐 + 换批倒计时 + 已完成组数/目标组数 */
	public record OrderSyncPayload(List<OrderEntry> entries, int remainSeconds,
	                               int completedSets, int groupsToWin, int setBonus,
	                               boolean finished, boolean timeoutPulse) implements CustomPacketPayload {
		public static final Type<OrderSyncPayload> TYPE = new Type<>(
				Identifier.fromNamespaceAndPath(HellKitchen.MOD_ID, "order_sync"));
		public static final StreamCodec<FriendlyByteBuf, OrderSyncPayload> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeVarInt(p.entries().size());
					for (OrderEntry e : p.entries()) {
						buf.writeUtf(e.itemId(), 64);
						buf.writeVarInt(e.need());
						buf.writeVarInt(e.delivered());
						buf.writeVarInt(e.scoreEach());
					}
					buf.writeVarInt(p.remainSeconds());
					buf.writeVarInt(p.completedSets());
					buf.writeVarInt(p.groupsToWin());
					buf.writeVarInt(p.setBonus());
					buf.writeBoolean(p.finished());
					buf.writeBoolean(p.timeoutPulse());
				},
				buf -> {
					int n = buf.readVarInt();
					List<OrderEntry> list = new ArrayList<>(n);
					for (int i = 0; i < n; i++) {
						list.add(new OrderEntry(buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
					}
					return new OrderSyncPayload(list, buf.readVarInt(), buf.readVarInt(),
							buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
				});
		@Override public Type<OrderSyncPayload> type() { return TYPE; }
	}

	private KitchenNet() {}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(OrderSyncPayload.TYPE, OrderSyncPayload.CODEC);
	}

	public static void send(ServerPlayer player, OrderSyncPayload payload) {
		try {
			ServerPlayNetworking.send(player, payload);
		} catch (Exception ignored) {
			// 假人/未接入连接的玩家发送失败不影响对局
		}
	}
}
