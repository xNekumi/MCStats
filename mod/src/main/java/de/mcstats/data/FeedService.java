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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Die letzten Ereignisse (Tode, Fortschritte), gespeichert in world/mcstats/feed.json. */
public final class FeedService {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcstats");
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final Path file;
	private final int maxSize;
	private final Deque<JsonObject> events = new ArrayDeque<>();
	private final List<Consumer<JsonObject>> listeners = new CopyOnWriteArrayList<>();
	private boolean dirty;

	public FeedService(Path file, int maxSize) {
		this.file = file;
		this.maxSize = maxSize;
		load();
	}

	public void onEvent(Consumer<JsonObject> listener) {
		listeners.add(listener);
	}

	public void add(JsonObject event) {
		event.addProperty("at", Instant.now().toString());
		synchronized (this) {
			events.addFirst(event);
			while (events.size() > maxSize) {
				events.removeLast();
			}
			dirty = true;
		}
		for (Consumer<JsonObject> listener : listeners) {
			listener.accept(event);
		}
	}

	/** Neueste Einträge zuerst. */
	public synchronized JsonArray latest(int limit) {
		JsonArray array = new JsonArray();
		for (JsonObject event : events) {
			if (array.size() >= limit) break;
			array.add(event);
		}
		return array;
	}

	public synchronized JsonArray forPlayer(String uuid, int limit) {
		JsonArray array = new JsonArray();
		for (JsonObject event : events) {
			if (array.size() >= limit) break;
			if (involves(event, "player", uuid) || involves(event, "killer", uuid)) {
				array.add(event);
			}
		}
		return array;
	}

	private static boolean involves(JsonObject event, String field, String uuid) {
		JsonElement player = event.get(field);
		return player != null && player.isJsonObject() && uuid.equals(player.getAsJsonObject().get("uuid").getAsString());
	}

	private void load() {
		if (!Files.exists(file)) return;
		try {
			JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			for (JsonElement element : root.getAsJsonArray()) {
				if (element.isJsonObject() && events.size() < maxSize) {
					events.addLast(element.getAsJsonObject());
				}
			}
		} catch (Exception e) {
			LOGGER.warn("[MCStats] Feed {} konnte nicht gelesen werden", file, e);
		}
	}

	public void save() {
		String json;
		synchronized (this) {
			if (!dirty) return;
			dirty = false;
			json = GSON.toJson(new ArrayList<>(events));
		}
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LOGGER.warn("[MCStats] Feed konnte nicht gespeichert werden", e);
		}
	}
}
