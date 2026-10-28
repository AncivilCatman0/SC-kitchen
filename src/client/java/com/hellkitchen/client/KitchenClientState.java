package com.hellkitchen.client;

import com.hellkitchen.net.KitchenNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端套餐状态缓存（S2C {@code OrderSyncPayload} 更新）。
 */
public class KitchenClientState {

	public static final KitchenClientState INSTANCE = new KitchenClientState();

	/** 本方套餐条目（服务端只下发本方，需求 6） */
	public List<KitchenNet.OrderEntry> entries = new ArrayList<>();
	public int remainSeconds = 0;
	public int completedSets = 0;
	public int groupsToWin = 3;
	public int setBonus = 0;
	public boolean finished = false;
	public boolean timeoutPulse = false;
	/** sync 到达时的本地毫秒（HUD 本地递减倒计时用） */
	public long syncAtMillis = 0L;

	public void registerNetworkHandlers() {
		ClientPlayNetworking.registerGlobalReceiver(KitchenNet.OrderSyncPayload.TYPE, (payload, context) -> {
			this.entries = new ArrayList<>(payload.entries());
			this.remainSeconds = payload.remainSeconds();
			this.completedSets = payload.completedSets();
			this.groupsToWin = payload.groupsToWin();
			this.setBonus = payload.setBonus();
			this.finished = payload.finished();
			this.timeoutPulse = payload.timeoutPulse();
			this.syncAtMillis = System.currentTimeMillis();
		});
	}

	/** 本地按 sync 经过时间递减的剩余秒数 */
	public int localRemainSeconds() {
		int elapsed = (int) ((System.currentTimeMillis() - syncAtMillis) / 1000);
		return Math.max(0, remainSeconds - elapsed);
	}
}
