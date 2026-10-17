package de.mcstats.data;

/** Alle Kennzahlen, die pro Spieler berechnet werden und für die es eine Bestenliste gibt. */
public enum Metric {
	PLAYTIME("playtime"),        // Sekunden
	DIAMONDS("diamonds"),        // abgebaute Diamanterze
	DEATHS("deaths"),
	BLOCKS_MINED("blocksMined"),
	MOB_KILLS("mobKills"),
	PLAYER_KILLS("playerKills"),
	DISTANCE("distance"),        // Meter
	TOTEMS("totems"),
	DAMAGE_DEALT("damageDealt"), // Zehntel halbe Herzen, wie in Minecraft
	DAMAGE_TAKEN("damageTaken"),
	FOOD_EATEN("foodEaten"),
	JUMPS("jumps"),
	FISH_CAUGHT("fishCaught"),
	TRADES("trades"),
	ANIMALS_BRED("animalsBred"),
	ITEMS_CRAFTED("itemsCrafted"),
	ADVANCEMENTS("advancements");

	public final String key;

	Metric(String key) {
		this.key = key;
	}
}
