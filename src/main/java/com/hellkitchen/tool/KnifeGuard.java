package com.hellkitchen.tool;

import com.hellkitchen.HellKitchen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 铁厨刀（需求 13）：本模式下所有玩家初始携带一把**不可破坏**的农夫乐事铁厨刀，且**不可丢弃**。
 *
 * <p>实现要点：
 * <ul>
 *   <li>发放：{@link #newKnife()} 由 {@code farmersdelight:iron_knife} 构建，写入 {@code minecraft:unbreakable}
 *       （永不损坏）+ {@code minecraft:custom_data} 标记（{@value #MARK_KEY}），
 *       以此区分玩家自己合成的普通铁刀——只有「本模式发放的那把」受保护。</li>
 *   <li>不可丢弃：Q 键丢弃会生成掉落物实体，{@link #sweepNear}/{@link #sweep} 在实体落地当 tick 即清除
 *       （若丢弃者仍在线则把刀补回其背包），因此刀无法被扔出；死亡掉落走同一路径补回。</li>
 *   <li>不可破坏：{@code minecraft:unbreakable} 保证耐久永不消耗。</li>
 * </ul>
 * 农夫乐事缺失（注册名解析不到）时优雅降级：不发放、不报错，仅打印一条警告。
 */
public final class KnifeGuard {

	/** 农夫乐事铁厨刀的注册名 */
	public static final String KNIFE_ID = "farmersdelight:iron_knife";
	/** 本模式专属标记（写入 custom_data，区分玩家自制的铁刀） */
	public static final String MARK_KEY = "hk_knife";

	/** 全维度扫描包围盒（仅已加载区块的实体参与遍历） */
	private static final AABB ALL = new AABB(
			new Vec3(-30_000_000, -64, -30_000_000), new Vec3(30_000_000, 320, 30_000_000));

	private static boolean resolved;
	private static Item item;

	private KnifeGuard() {}

	/** 铁厨刀物品（未安装农夫乐事时为 null） */
	public static Item item() {
		if (!resolved) {
			resolved = true;
			item = resolve();
			if (item == null) {
				HellKitchen.LOGGER.warn("[HK] 未找到铁厨刀 {}（未安装农夫乐事？）——本模式将不发放铁厨刀", KNIFE_ID);
			}
		}
		return item;
	}

	/** 前置物品是否可用 */
	public static boolean isAvailable() { return item() != null; }

	/** 新建一把「本模式的」铁厨刀（不可破坏 + 专属标记）；不可用时返回空栈 */
	public static ItemStack newKnife() {
		Item it = item();
		return it == null ? ItemStack.EMPTY : mark(new ItemStack(it));
	}

	/** 给任意物品打上「本模式铁厨刀」标记（不可破坏 + custom_data）；自测用它构造替身做断言 */
	public static ItemStack mark(ItemStack s) {
		if (s == null || s.isEmpty()) return ItemStack.EMPTY;
		s.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(MARK_KEY, true);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return s;
	}

	/** 是否为「本模式发放的铁厨刀」 */
	public static boolean isOurs(ItemStack s) {
		if (s == null || s.isEmpty()) return false;
		CustomData data = s.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().getBooleanOr(MARK_KEY, false);
	}

	/** 背包里是否已有本模式的铁厨刀 */
	public static boolean hasKnife(ServerPlayer p) {
		if (p == null) return false;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (isOurs(inv.getItem(i))) return true;
		}
		return false;
	}

	/** 缺失则补发（背包满时本次跳过，下一 tick 重试） */
	public static void ensure(ServerPlayer p) {
		if (p == null || p.isSpectator() || hasKnife(p)) return;
		ItemStack knife = newKnife();
		if (knife.isEmpty()) return;
		p.getInventory().add(knife);
	}

	/** 清除玩家附近的「本模式铁厨刀」掉落物，并把刀补回丢弃者背包（Q 键丢弃当 tick 生效） */
	public static void sweepNear(ServerLevel level, ServerPlayer p) {
		if (level == null || p == null) return;
		AABB box = p.getBoundingBox().inflate(16.0D);
		for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, box)) {
			discardIfOurs(ie, level);
		}
	}

	/** 全维度清查（死后掉落、被扔远等兜底）：清除本模式铁厨刀掉落物并补回在线持有者 */
	public static void sweep(ServerLevel level) {
		if (level == null) return;
		for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, ALL)) {
			discardIfOurs(ie, level);
		}
	}

	// ==================== 内部 ====================

	private static void discardIfOurs(ItemEntity ie, ServerLevel level) {
		if (!isOurs(ie.getItem())) return;
		var owner = ie.getOwner();
		ie.discard();
		if (owner instanceof ServerPlayer p && p.level().dimension().equals(level.dimension())) {
			ensure(p);
		}
	}

	private static Item resolve() {
		try {
			Identifier id = Identifier.fromNamespaceAndPath("farmersdelight", "iron_knife");
			Item it = BuiltInRegistries.ITEM.getValue(id);
			return (it == null || it == Items.AIR) ? null : it;
		} catch (Exception e) {
			return null;
		}
	}
}
