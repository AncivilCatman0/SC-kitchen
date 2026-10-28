package com.hellkitchen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地狱厨房配置文件（需求 12）。
 *
 * <p>路径：{@code config/hell-kitchen/kitchen.json}（缺失时自动生成默认文件；解析失败回退默认）。
 * 定义：套餐能出现哪些菜品与价值、每轮套餐刷新时间、烧糊所需停留时间、每次烧糊概率等。
 * 挑战书二级菜单选中的「需要完成的套餐组数」会覆盖本文件的 {@code groupsToWin}。
 */
public final class KitchenConfig {

	public static final int CONFIG_VERSION = 3;

	/** 旧版默认套餐倒计时（秒）——v1 为 10 分钟，用于把老配置迁移到 5 分钟 */
	private static final int LEGACY_BATCH_SECONDS = 600;

	/**
	 * v2 及以前的水族馆西侧 B 组出生点：地图升至 1.0.7（热气球更新）后该点被新增的
	 * 装饰原木占据，冒险模式下无法破块会卡住 → 迁移到相邻同为蓝釉陶瓦地板的空位。
	 */
	private static final List<Integer> LEGACY_AQUA_WEST_B = List.of(-62, 0, -206);

	/** 单个成品菜配置：id 为注册名；ingredients = 合成配方里食材种类数（积分按公式推导）；maxCount = 单次套餐数量上限 */
	public static class Dish {
		public String id = "";
		public String kind = "FOOD";       // FOOD / BOWL / PLATE
		public int ingredients = 1;
		public int score = 0;              // >0 时直接用该分值；否则按 score = 2 + 3*(ingredients-1) 推导
		public int maxCount = 1;           // 碗装/盘装固定 1；其余最多 3

		public Dish() {}
		public Dish(String id, String kind, int ingredients, int score, int maxCount) {
			this.id = id; this.kind = kind; this.ingredients = ingredients; this.score = score; this.maxCount = maxCount;
		}

		/** 实际分值（配置未给分时按公式推导） */
		public int resolvedScore() {
			return score > 0 ? score : 2 + 3 * Math.max(0, ingredients - 1);
		}
	}

	/** 饮品配置（仅允许 4 种白名单饮品，见 {@link #drinks}） */
	public static class Drink {
		public String id = "";
		public int score = 8;
		public int maxCount = 1;

		public Drink() {}
		public Drink(String id, int score) { this.id = id; this.score = score; }
	}

	/** 交付漏斗（需求 7）：坐标为方块中心（浮点），吸入或放入即兑换成分数 */
	public static class Delivery {
		public String key = "";
		public String name = "";
		public double x, y, z;
		/**
		 * 归属侧：west / east；空串时按 x&lt;0 判定（街角 / 水族馆）。
		 * 热气球地图两侧漏斗都在 x&gt;0，必须显式指定（第九十二批）。
		 */
		public String side = "";

		public Delivery() {}
		public Delivery(String key, String name, double x, double y, double z) {
			this.key = key; this.name = name; this.x = x; this.y = y; this.z = z;
		}
		public Delivery(String key, String name, double x, double y, double z, String side) {
			this(key, name, x, y, z);
			this.side = side;
		}
	}

	public int configVersion = CONFIG_VERSION;

	/** 每轮套餐倒计时（秒）；做完并提交可立即刷新。默认 5 分钟 */
	public int batchSeconds = 300;
	/** 菜品在烹饪锅/容器里停留超过该秒数 → 「已烧糊」，不可兑换 */
	public int holdSeconds = 30;
	/** 每次制作完成时直接烧糊的概率（0~1） */
	public double burnChance = 0.10;
	/** 需要完成的套餐组数（1~6；值为 7 表示「无尽」，挑战书二级菜单可覆盖） */
	public int groupsToWin = 3;
	/** 完成一整组套餐的额外加分 */
	public int setBonus = 30;
	/** 套餐超时未完成的扣分 */
	public int timeoutPenalty = -20;

	/** 展示框锁定范围：all（默认，整张地图）/ coordinates（仅白名单坐标） */
	public String frameLock = "all";
	/** 发射器/箱子补货范围：all（默认，整张地图） */
	public List<String> resupplyPoints = new ArrayList<>(List.of("all"));
	/** 视为「烹饪锅」的方块注册名（停留计时用） */
	public List<String> cookingPots = new ArrayList<>(List.of("farmersdelight:cooking_pot"));

	/**
	 * 交付漏斗（需求 7 + 追加）：东侧 = 水族馆 (26.5,-217.5) / 街角 (11.5,20.5) / 热气球 (29.5,-504.5)；
	 * 西侧 = 水族馆 (-54.5,-203.5) / 街角 (-68.5,23.5) / 热气球 (27.5,-448.5)。
	 * 坐标 = 漏斗方块的方块中心（方块整数坐标 + 0.5）。
	 * 归属：优先按 {@link Delivery#side}，未指定时按漏斗 x&lt;0 判定西侧。
	 */
	public List<Delivery> deliveries = defaultDeliveries();

	/** 生成默认交付漏斗（同时用于老配置文件缺条目时的补齐） */
	private static List<Delivery> defaultDeliveries() {
		return new ArrayList<>(List.of(
				new Delivery("aquarium", "水族馆·东", 26.5, 0.0, -217.5),
				new Delivery("street", "街角·东", 11.5, 0.0, 20.5),
				new Delivery("aquariumWest", "水族馆·西", -54.5, 0.0, -203.5),
				new Delivery("streetWest", "街角·西", -68.5, 0.0, 23.5),
				new Delivery("balloon", "热气球·东", 29.5, 2.0, -504.5, "east"),
				new Delivery("balloonWest", "热气球·西", 27.5, 0.0, -448.5, "west")));
	}

	/**
	 * 出生点三套坐标（挑战书二级菜单「地图类型」切换）：
	 * <ul>
	 *   <li>街角（默认）：两队分别 (-62,0,12)（西侧）与 (17,0,9)（东侧），不分组；</li>
	 *   <li>水族馆：每队平分为 A/B 两组 —— 西侧 A(-62,7,-205) / B(-62,0,-207)，
	 *       东侧 A(17,7,-219) / B(18,0,-221)；</li>
	 *   <li>热气球（地图 1.0.7 新增）：每队平分为 A/B 两组 —— 西侧（z≈-454 基地）
	 *       A(35,1,-454) / B(19,1,-454)，东侧（z≈-510 基地）A(37,3,-510) / B(21,3,-510)。</li>
	 * </ul>
	 * 坐标写整数，传送时由 {@code KitchenStyleProvider.place()} 加 0.5 落到方块中心。
	 * 西侧 B 组自地图 1.0.7 起由 (-62,0,-206) 移至 (-62,0,-207)：原坐标被新增装饰原木占据。
	 */
	public Map<String, List<Integer>> spawns = defaultSpawns();

	/** 生成默认出生点（同时用于老配置文件缺键时的回退） */
	private static Map<String, List<Integer>> defaultSpawns() {
		Map<String, List<Integer>> m = new LinkedHashMap<>();
		m.put("streetWest", List.of(-62, 0, 12));
		m.put("streetEast", List.of(17, 0, 9));
		m.put("aquaWestA", List.of(-62, 7, -205));
		m.put("aquaWestB", List.of(-62, 0, -207));
		m.put("aquaEastA", List.of(17, 7, -219));
		m.put("aquaEastB", List.of(18, 0, -221));
		m.put("balloonWestA", List.of(35, 1, -454));
		m.put("balloonWestB", List.of(19, 1, -454));
		m.put("balloonEastA", List.of(37, 3, -510));
		m.put("balloonEastB", List.of(21, 3, -510));
		return m;
	}

	/** 菜品池（成品菜；碗装/盘装数量固定 1，其余最多 3 —— maxCount 即该上限） */
	public List<Dish> dishes = defaultDishes();

	/** 生成默认菜品池（同时用于老配置文件缺条目时的补齐） */
	private static List<Dish> defaultDishes() {
		return new ArrayList<>(List.of(
				// —— 派 / 曲奇 ——
				new Dish("farmersdelight:apple_pie", "FOOD", 4, 0, 3),
				new Dish("farmersdelight:sweet_berry_cheesecake", "FOOD", 4, 0, 3),
				new Dish("farmersdelight:chocolate_pie", "FOOD", 4, 0, 3),
				new Dish("minecraft:pumpkin_pie", "FOOD", 3, 0, 3),
				new Dish("minecraft:cookie", "FOOD", 2, 0, 3),
				new Dish("farmersdelight:honey_cookie", "FOOD", 2, 0, 3),
				new Dish("farmersdelight:sweet_berry_cookie", "FOOD", 2, 0, 3),
				// —— 烤肉 / 早餐 ——
				new Dish("farmersdelight:cooked_bacon", "FOOD", 1, 0, 3),
				new Dish("farmersdelight:bacon_and_eggs", "FOOD", 2, 0, 3),
				new Dish("farmersdelight:barbecue_stick", "FOOD", 3, 0, 3),
				new Dish("farmersdelight:roasted_mutton_chops", "FOOD", 3, 0, 3),
				new Dish("farmersdelight:smoked_ham", "FOOD", 3, 0, 3),
				// —— 汉堡 / 三明治 / 卷饼 / 寿司 ——
				new Dish("farmersdelight:hamburger", "FOOD", 4, 0, 3),
				new Dish("farmersdelight:bacon_sandwich", "FOOD", 3, 0, 3),
				new Dish("farmersdelight:chicken_sandwich", "FOOD", 3, 0, 3),
				new Dish("farmersdelight:egg_sandwich", "FOOD", 3, 0, 3),
				new Dish("farmersdelight:mutton_wrap", "FOOD", 4, 0, 3),
				new Dish("farmersdelight:kelp_roll", "FOOD", 3, 0, 3),
				new Dish("farmersdelight:cod_roll", "FOOD", 3, 0, 3),
				new Dish("farmersdelight:salmon_roll", "FOOD", 3, 0, 3),
				// —— 饺子 / 卷 / 点心 ——
				new Dish("farmersdelight:dumplings", "FOOD", 3, 0, 3),
				new Dish("farmersdelight:cabbage_rolls", "FOOD", 3, 0, 3),
				new Dish("farmersdelight:stuffed_potato", "FOOD", 3, 0, 3),
				new Dish("farmersdelight:melon_popsicle", "FOOD", 2, 0, 3),
				// —— 盘装盛宴成品 ——
				new Dish("farmersdelight:grilled_salmon", "PLATE", 3, 0, 1),
				new Dish("farmersdelight:roast_chicken", "PLATE", 5, 0, 1),
				new Dish("farmersdelight:stuffed_pumpkin", "PLATE", 5, 0, 1),
				new Dish("farmersdelight:honey_glazed_ham", "PLATE", 5, 0, 1),
				new Dish("farmersdelight:shepherds_pie", "PLATE", 5, 0, 1),
				// —— 碗装：意面 / 汤 / 炖 / 沙拉 ——
				new Dish("farmersdelight:squid_ink_pasta", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:vegetable_noodles", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:mushroom_rice", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:fried_rice", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:pasta_with_meatballs", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:pasta_with_mutton_chop", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:steak_and_potatoes", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:beef_stew", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:chicken_soup", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:fish_stew", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:baked_cod_stew", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:bone_broth", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:noodle_soup", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:pumpkin_soup", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:vegetable_soup", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:onion_soup", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:ratatouille", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:mixed_salad", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:fruit_salad", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:nether_salad", "BOWL", 4, 0, 1),
				new Dish("farmersdelight:gleaming_salad", "BOWL", 4, 0, 1),
				new Dish("minecraft:mushroom_stew", "BOWL", 3, 0, 1),
				new Dish("minecraft:rabbit_stew", "BOWL", 4, 0, 1),
				new Dish("minecraft:beetroot_soup", "BOWL", 3, 0, 1)));
	}

	/** 饮品（仅这 4 种）：热可可 / 苹果酒 / 西瓜汁 / 发光浆果蛋奶沙司 */
	public List<Drink> drinks = defaultDrinks();

	/** 生成默认饮品池（同时用于老配置文件缺条目时的补齐） */
	private static List<Drink> defaultDrinks() {
		return new ArrayList<>(List.of(
				new Drink("farmersdelight:hot_cocoa", 8),
				new Drink("farmersdelight:apple_cider", 8),
				new Drink("farmersdelight:melon_juice", 6),
				new Drink("farmersdelight:glow_berry_custard", 10)));
	}

	private static KitchenConfig INSTANCE;

	private KitchenConfig() {}

	public static KitchenConfig get() {
		if (INSTANCE == null) load();
		return INSTANCE;
	}

	/** 配置目录（config/hell-kitchen/），外部地图覆盖文件放这里 */
	public static Path configDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("hell-kitchen");
	}

	/** 外部存档 zip 覆盖（可选）：config/hell-kitchen/kitchen/*.zip */
	public static Path findZip() {
		Path dir = configDir().resolve("kitchen");
		if (!Files.isDirectory(dir)) return null;
		try (var s = Files.list(dir)) {
			return s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".zip"))
					.findFirst().orElse(null);
		} catch (Exception e) {
			return null;
		}
	}

	/** 外部散装文件夹覆盖（可选）：config/hell-kitchen/kitchen/region|entities|poi */
	public static Path folderDir() {
		return configDir().resolve("kitchen");
	}

	public static synchronized void load() {
		Path file = configDir().resolve("kitchen.json");
		try {
			Files.createDirectories(configDir());
			if (!Files.exists(file)) {
				KitchenConfig def = new KitchenConfig();
				Files.writeString(file, GSON.toJson(def), StandardCharsets.UTF_8);
				INSTANCE = def;
				HellKitchen.LOGGER.info("已生成地狱厨房默认配置：{}", file);
				return;
			}
			KitchenConfig cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), KitchenConfig.class);
			if (cfg == null) throw new IllegalStateException("解析结果为空");
			if (migrate(cfg, file)) {
				HellKitchen.LOGGER.info("已升级地狱厨房配置至 v{}（batchSeconds {} → {}）：{}",
						CONFIG_VERSION, LEGACY_BATCH_SECONDS, cfg.batchSeconds, file);
			}
			INSTANCE = cfg;
			HellKitchen.LOGGER.info("已加载地狱厨房配置：{}（batchSeconds={} groupsToWin={} burnChance={}）",
					file, cfg.batchSeconds, cfg.groupsToWin, cfg.burnChance);
		} catch (Exception e) {
			HellKitchen.LOGGER.warn("读取地狱厨房配置失败，使用默认值", e);
			INSTANCE = new KitchenConfig();
		}
	}

	/**
	 * 老配置迁移：
	 * <ul>
	 *   <li>v1 → v2：套餐倒计时默认 10 分钟 → 5 分钟（仅当仍是旧默认值时才改写，
	 *       以免覆盖玩家自定义的时长）</li>
	 *   <li>v2 → v3：水族馆西侧 B 组出生点避开地图 1.0.7 新增的装饰原木</li>
	 * </ul>
	 * 仅当确实发生改写时回写配置文件并提升 configVersion。
	 *
	 * @return true = 发生了迁移（已回写文件）
	 */
	private static boolean migrate(KitchenConfig cfg, Path file) {
		boolean changed = false;
		if (cfg.configVersion < 2 && cfg.batchSeconds == LEGACY_BATCH_SECONDS) {
			cfg.batchSeconds = 300;
			changed = true;
		}
		if (cfg.configVersion < 3 && cfg.spawns != null
				&& LEGACY_AQUA_WEST_B.equals(cfg.spawns.get("aquaWestB"))) {
			cfg.spawns.put("aquaWestB", List.of(-62, 0, -207));
			changed = true;
		}
		if (!changed) return false;
		cfg.configVersion = CONFIG_VERSION;
		try {
			Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8);
		} catch (Exception e) {
			HellKitchen.LOGGER.warn("回写升级后的地狱厨房配置失败：{}", file, e);
		}
		return true;
	}

	/** 出生点（配置缺该键时回退到代码默认坐标——旧配置文件无需手动补键） */
	public List<Integer> spawn(String key) {
		List<Integer> p = spawns == null ? null : spawns.get(key);
		if (p != null && p.size() >= 3) return p;
		List<Integer> def = defaultSpawns().get(key);
		return def != null ? def : List.of(17, 0, 9);
	}

	/** 交付漏斗全集：配置里缺失的默认条目自动补齐（旧配置文件同样能拿到新增的两个漏斗） */
	public List<Delivery> deliveryPoints() {
		Map<String, Delivery> byKey = new LinkedHashMap<>();
		if (deliveries != null) {
			for (Delivery d : deliveries) {
				if (d != null && d.key != null && !d.key.isBlank()) byKey.put(d.key, d);
			}
		}
		for (Delivery def : defaultDeliveries()) byKey.putIfAbsent(def.key, def);
		return new ArrayList<>(byKey.values());
	}

	/** 交付漏斗（按 key，含补齐的默认条目） */
	public Delivery delivery(String key) {
		for (Delivery d : deliveryPoints()) if (key.equals(d.key)) return d;
		return null;
	}

	/** 菜品池全集：配置里缺失的默认菜品自动补齐（旧配置文件同样能拿到新增菜品） */
	public List<Dish> dishPoints() {
		Map<String, Dish> byId = new LinkedHashMap<>();
		if (dishes != null) {
			for (Dish d : dishes) {
				if (d != null && d.id != null && !d.id.isBlank()) byId.putIfAbsent(d.id, d);
			}
		}
		for (Dish def : defaultDishes()) byId.putIfAbsent(def.id, def);
		return new ArrayList<>(byId.values());
	}

	/** 饮品全集：配置里缺失的默认饮品自动补齐（旧配置文件同样能拿到新增饮品） */
	public List<Drink> drinkPoints() {
		Map<String, Drink> byId = new LinkedHashMap<>();
		if (drinks != null) {
			for (Drink d : drinks) {
				if (d != null && d.id != null && !d.id.isBlank()) byId.putIfAbsent(d.id, d);
			}
		}
		for (Drink def : defaultDrinks()) byId.putIfAbsent(def.id, def);
		return new ArrayList<>(byId.values());
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
}
