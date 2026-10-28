package com.hellkitchen;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 地狱厨房（Hell's Kitchen）——生存契约（SC）附属玩法。
 *
 * <p>玩法注册走自定义入口点 {@code survival-contract:playstyle}（见 {@link KitchenStyleProvider}）；
 * 本类只负责固定地图注入、维度绑定与世界规则。
 */
public class HellKitchen implements ModInitializer {

	public static final String MOD_ID = "hell-kitchen";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Hell's Kitchen addon initializing...");
		// 固定地图注入（SERVER_STARTING，维度加载前）+ 维度绑定/世界规则（SERVER_STARTED）
		KitchenMap.register();
		// 网络包（S2C 套餐同步）
		com.hellkitchen.net.KitchenNet.register();
		// 事件：补给/发射器/烹饪锅/展示框
		KitchenEvents.register();
		// 服务端自测（HK_SELFTEST=1 时启用）
		HellKitchenSelfTest.maybeRegister();
		LOGGER.info("Hell's Kitchen addon initialized.");
	}
}
