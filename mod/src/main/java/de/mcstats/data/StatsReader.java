package de.mcstats.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Berechnet die {@link Metric Kennzahlen} aus dem Stats-JSON von Minecraft. */
public final class StatsReader {
	private static final Set<String> DIAMOND_ORES = Set.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore");

	private final Set<String> foodItems;

	/** @param foodItems IDs aller essbaren Items (für "Mahlzeiten") */
	public StatsReader(Set<String> foodItems) {
		this.foodItems = foodItems;
	}

	/** @param root komplettes Datei-JSON oder das "stats"-Objekt */
	public static JsonObject statsObject(JsonObject root) {
		JsonElement stats = root.get("stats");
		return stats != null && stats.isJsonObject() ? stats.getAsJsonObject() : root;
	}

	public EnumMap<Metric, Long> compute(JsonObject stats, int advancements) {
		JsonObject custom = category(stats, "custom");
		JsonObject mined = category(stats, "mined");
		JsonObject used = category(stats, "used");

		EnumMap<Metric, Long> m = new EnumMap<>(Metric.class);
		m.put(Metric.PLAYTIME, get(custom, "play_time") / 20);
		m.put(Metric.DEATHS, get(custom, "deaths"));
		m.put(Metric.DIAMONDS, DIAMOND_ORES.stream().mapToLong(id -> get(mined, id)).sum());
		m.put(Metric.BLOCKS_MINED, sum(mined));
		m.put(Metric.MOB_KILLS, get(custom, "mob_kills"));
		m.put(Metric.PLAYER_KILLS, get(custom, "player_kills"));
		m.put(Metric.TOTEMS, get(used, "totem_of_undying"));
		m.put(Metric.DAMAGE_DEALT, get(custom, "damage_dealt"));
		m.put(Metric.DAMAGE_TAKEN, get(custom, "damage_taken"));
		m.put(Metric.JUMPS, get(custom, "jump"));
		m.put(Metric.FISH_CAUGHT, get(custom, "fish_caught"));
		m.put(Metric.TRADES, get(custom, "traded_with_villager"));
		m.put(Metric.ANIMALS_BRED, get(custom, "animals_bred"));
		m.put(Metric.ITEMS_CRAFTED, sum(category(stats, "crafted")));
		m.put(Metric.ADVANCEMENTS, (long) advancements);

		long cm = 0;
		for (Map.Entry<String, JsonElement> entry : custom.entrySet()) {
			String key = entry.getKey();
			// Fallen zählt nicht als zurückgelegte Strecke.
			if (key.endsWith("_one_cm") && !key.endsWith("fall_one_cm")) {
				cm += asLong(entry.getValue());
			}
		}
		m.put(Metric.DISTANCE, cm / 100);

		long food = get(custom, "eat_cake_slice");
		for (Map.Entry<String, JsonElement> entry : used.entrySet()) {
			if (foodItems.contains(normalize(entry.getKey()))) {
				food += asLong(entry.getValue());
			}
		}
		m.put(Metric.FOOD_EATEN, food);
		return m;
	}

	public static JsonObject category(JsonObject stats, String name) {
		JsonElement element = stats.get("minecraft:" + name);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
	}

	private static long get(JsonObject category, String id) {
		JsonElement value = category.get(normalize(id));
		return value == null ? 0 : asLong(value);
	}

	private static long sum(JsonObject category) {
		long total = 0;
		for (Map.Entry<String, JsonElement> entry : category.entrySet()) {
			total += asLong(entry.getValue());
		}
		return total;
	}

	private static long asLong(JsonElement element) {
		try {
			return element.getAsLong();
		} catch (RuntimeException e) {
			return 0;
		}
	}

	private static String normalize(String id) {
		return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
	}
}
