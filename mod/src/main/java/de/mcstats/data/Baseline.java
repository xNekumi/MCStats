package de.mcstats.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startpunkt für {@code /mcstats reset}: die Werte aller Spieler zum Zeitpunkt des Resets.
 * Gezählt wird danach nur, was seitdem dazugekommen ist. Die Minecraft-Statistiken selbst
 * bleiben unverändert. Gespeichert in world/mcstats/baseline.json.
 */
public final class Baseline {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcstats");
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	/** Werte, die Minecraft selbst zurücksetzt – hier ergibt Abziehen keinen Sinn. */
	private static final Set<String> NOT_CUMULATIVE = Set.of("minecraft:time_since_death", "minecraft:time_since_rest");

	public record Entry(JsonObject stats, Set<String> advancements) {}

	private final Path file;
	private volatile Instant createdAt;
	private volatile Map<UUID, Entry> players = Map.of();

	public Baseline(Path file) {
		this.file = file;
		load();
	}

	/** null, wenn kein Reset aktiv ist. */
	public Instant createdAt() {
		return createdAt;
	}

	public int size() {
		return players.size();
	}

	public void create(Map<UUID, Entry> entries) throws IOException {
		Instant now = Instant.now();
		JsonObject root = new JsonObject();
		root.addProperty("createdAt", now.toString());
		JsonObject playerObject = new JsonObject();
		entries.forEach((uuid, entry) -> {
			JsonObject object = new JsonObject();
			object.add("stats", entry.stats());
			JsonArray advancements = new JsonArray();
			entry.advancements().forEach(advancements::add);
			object.add("advancements", advancements);
			playerObject.add(uuid.toString(), object);
		});
		root.add("players", playerObject);

		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		players = Map.copyOf(entries);
		createdAt = now;
	}

	public void clear() throws IOException {
		Files.deleteIfExists(file);
		players = Map.of();
		createdAt = null;
	}

	/** Aktuelle Stats minus Startwert. Spieler ohne Startwert (neu dazugekommen) zählen voll. */
	public JsonObject apply(UUID uuid, JsonObject stats) {
		Entry entry = players.get(uuid);
		if (entry == null) return stats;
		JsonObject result = new JsonObject();
		for (Map.Entry<String, JsonElement> category : stats.entrySet()) {
			if (!category.getValue().isJsonObject()) continue;
			JsonElement baseCategory = entry.stats().get(category.getKey());
			JsonObject base = baseCategory != null && baseCategory.isJsonObject() ? baseCategory.getAsJsonObject() : new JsonObject();
			JsonObject out = new JsonObject();
			for (Map.Entry<String, JsonElement> stat : category.getValue().getAsJsonObject().entrySet()) {
				long value = asLong(stat.getValue());
				if (!NOT_CUMULATIVE.contains(stat.getKey())) {
					JsonElement baseValue = base.get(stat.getKey());
					value -= baseValue == null ? 0 : asLong(baseValue);
				}
				if (value > 0) out.addProperty(stat.getKey(), value);
			}
			result.add(category.getKey(), out);
		}
		return result;
	}

	/** Fortschritte, die erst nach dem Reset geschafft wurden. */
	public Set<String> apply(UUID uuid, Set<String> advancements) {
		Entry entry = players.get(uuid);
		if (entry == null) return advancements;
		Set<String> result = new HashSet<>(advancements);
		result.removeAll(entry.advancements());
		return result;
	}

	private void load() {
		if (!Files.exists(file)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<UUID, Entry> loaded = new HashMap<>();
			for (Map.Entry<String, JsonElement> player : root.getAsJsonObject("players").entrySet()) {
				JsonObject object = player.getValue().getAsJsonObject();
				Set<String> advancements = new HashSet<>();
				for (JsonElement id : object.getAsJsonArray("advancements")) advancements.add(id.getAsString());
				loaded.put(UUID.fromString(player.getKey()), new Entry(object.getAsJsonObject("stats"), Set.copyOf(advancements)));
			}
			players = Map.copyOf(loaded);
			createdAt = Instant.parse(root.get("createdAt").getAsString());
			LOGGER.info("[MCStats] Gezählt wird seit {} ({} Spieler mit Startwert)", createdAt, loaded.size());
		} catch (Exception e) {
			LOGGER.error("[MCStats] {} ist beschädigt und wird ignoriert", file, e);
		}
	}

	private static long asLong(JsonElement element) {
		try {
			return element.getAsLong();
		} catch (RuntimeException e) {
			return 0;
		}
	}
}
