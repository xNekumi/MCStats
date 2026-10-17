package de.mcstats.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.mcstats.config.McStatsConfig;
import de.mcstats.mixin.ServerStatsCounterInvoker;
import de.mcstats.text.Translations;
import de.mcstats.twitch.TwitchService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sammelt die Stats aller Spieler, die jemals auf dem Server waren.
 *
 * <p>Offline-Spieler kommen aus world/stats/*.json, Online-Spieler werden regelmäßig direkt aus
 * ihrem {@code ServerStatsCounter} gelesen. Alles, was Minecraft-Objekte anfasst, passiert auf dem
 * Server-Thread; Auswertung und JSON-Erzeugung laufen auf einem eigenen Worker-Thread. Die
 * HTTP-Threads lesen nur die fertigen, unveränderlichen Snapshots.
 */
public final class StatsService {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcstats");
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final int BOARD_SIZE = 25;

	private record OnlinePlayer(UUID uuid, String name) {}

	private record AdvancementCache(long modified, Set<String> done) {}

	private final MinecraftServer server;
	private final McStatsConfig config;
	private final StatsReader reader;
	private final FeedService feed;
	private final TwitchService twitch;
	private final Translations translations;
	private final Baseline baseline;
	private final SkinStore skins;
	private final Path statsDir;
	private final Path advancementsDir;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("mcstats-worker").daemon().factory());

	private final Map<UUID, PlayerRecord> records = new ConcurrentHashMap<>();
	private final Map<UUID, AdvancementCache> advancementCache = new HashMap<>(); // nur Worker-Thread
	private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

	private volatile Set<String> countedAdvancements = Set.of();
	private volatile List<OnlinePlayer> online = List.of();
	private volatile Map<UUID, EnumMap<Metric, Integer>> ranks = Map.of();
	private volatile String snapshotJson = "{}";
	private int ticks;
	private int lastPlayerCount = -1;

	public StatsService(MinecraftServer server, McStatsConfig config, StatsReader reader, FeedService feed, TwitchService twitch, Translations translations, Baseline baseline, SkinStore skins) {
		this.server = server;
		this.config = config;
		this.reader = reader;
		this.feed = feed;
		this.twitch = twitch;
		this.translations = translations;
		this.baseline = baseline;
		this.skins = skins;
		this.statsDir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
		this.advancementsDir = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR);
	}

	// ---------------------------------------------------------------- Server-Thread

	public void start() {
		refreshAdvancementIds();
		worker.execute(this::loadOfflinePlayers);
		refresh();
	}

	/** Aufruf am Ende jedes Server-Ticks. Join/Leave wird über die Spieleranzahl sofort erkannt. */
	public void tick() {
		int playerCount = server.getPlayerList().getPlayerCount();
		if (playerCount != lastPlayerCount || ++ticks % (config.refreshSeconds * 20) == 0) {
			lastPlayerCount = playerCount;
			refresh();
		}
	}

	/** Nur Fortschritte mit Anzeige zählen, keine Rezepte. Neu berechnen nach /reload. */
	public void refreshAdvancementIds() {
		Set<String> ids = new HashSet<>();
		for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
			if (holder.value().display().isPresent()) {
				ids.add(holder.id().toString());
			}
		}
		countedAdvancements = Set.copyOf(ids);
	}

	private void refresh() {
		Runnable capture = captureOnline();
		worker.execute(() -> {
			capture.run();
			rebuild();
		});
	}

	/** Liest die Stats der Online-Spieler (Server-Thread); das Ergebnis wird auf dem Worker übernommen. */
	private Runnable captureOnline() {
		List<OnlinePlayer> nowOnline = new ArrayList<>();
		List<Runnable> updates = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID uuid = player.getUUID();
			String name = player.getGameProfile().name();
			JsonObject stats = StatsReader.statsObject(((ServerStatsCounterInvoker) player.getStats()).mcstats$toJson().getAsJsonObject());
			SkinStore.Skin skin = SkinStore.fromProfile(player.getGameProfile());
			updates.add(() -> {
				store(uuid, name, stats, now);
				skins.update(uuid, skin);
			});
			if (!player.hasDisconnected()) {
				nowOnline.add(new OnlinePlayer(uuid, name));
			}
		}
		return () -> {
			updates.forEach(Runnable::run);
			online = List.copyOf(nowOnline);
			skins.saveIfDirty();
		};
	}

	/**
	 * Setzt den Startpunkt auf die aktuellen Werte aller Spieler und leert den Feed.
	 * Danach zählt nur noch, was neu dazukommt. Aufruf auf dem Server-Thread.
	 *
	 * @param done bekommt null bei Erfolg oder die Fehlermeldung; läuft auf dem Server-Thread
	 */
	public void reset(Consumer<String> done) {
		Runnable capture = captureOnline();
		worker.execute(() -> {
			String error = null;
			try {
				capture.run();
				Map<UUID, Baseline.Entry> entries = new HashMap<>();
				for (PlayerRecord record : records.values()) {
					entries.put(record.uuid(), new Baseline.Entry(record.rawStats().deepCopy(), Set.copyOf(record.rawAdvancements())));
				}
				baseline.create(entries);
				feed.clear();
				recomputeAll();
				LOGGER.info("[MCStats] Stats zurückgesetzt, gezählt wird ab jetzt ({} Spieler)", entries.size());
			} catch (Exception e) {
				LOGGER.error("[MCStats] Reset fehlgeschlagen", e);
				error = e.toString();
			}
			String result = error;
			server.execute(() -> done.accept(result));
		});
	}

	/** Entfernt den Startpunkt wieder, es zählen wieder alle Werte. Der geleerte Feed bleibt leer. */
	public void undoReset(Consumer<String> done) {
		worker.execute(() -> {
			String error = null;
			try {
				baseline.clear();
				recomputeAll();
				LOGGER.info("[MCStats] Reset rückgängig gemacht");
			} catch (Exception e) {
				LOGGER.error("[MCStats] Rückgängig machen fehlgeschlagen", e);
				error = e.toString();
			}
			String result = error;
			server.execute(() -> done.accept(result));
		});
	}

	public Baseline baseline() {
		return baseline;
	}

	public void stop() {
		worker.shutdown();
		try {
			worker.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ---------------------------------------------------------------- Worker-Thread

	private void loadOfflinePlayers() {
		if (!Files.isDirectory(statsDir)) return;
		Map<UUID, String> names = readUserCache();
		int count = 0;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(statsDir, "*.json")) {
			for (Path file : files) {
				String base = file.getFileName().toString().replace(".json", "");
				UUID uuid;
				try {
					uuid = UUID.fromString(base);
				} catch (IllegalArgumentException e) {
					continue;
				}
				if (records.containsKey(uuid)) continue; // ist online, aktueller Stand liegt schon vor
				try {
					JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
					String name = names.getOrDefault(uuid, base.substring(0, 8));
					store(uuid, name, StatsReader.statsObject(root), Files.getLastModifiedTime(file).toMillis());
					count++;
				} catch (Exception e) {
					LOGGER.warn("[MCStats] Stats-Datei {} übersprungen: {}", file, e.toString());
				}
			}
		} catch (IOException e) {
			LOGGER.warn("[MCStats] Konnte {} nicht lesen", statsDir, e);
		}
		LOGGER.info("[MCStats] {} Spieler aus {} geladen", count, statsDir);
		rebuild();
	}

	private static Map<UUID, String> readUserCache() {
		Map<UUID, String> names = new HashMap<>();
		Path file = Path.of("usercache.json");
		if (!Files.exists(file)) return names;
		try {
			for (JsonElement element : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) {
				JsonObject entry = element.getAsJsonObject();
				names.put(UUID.fromString(entry.get("uuid").getAsString()), entry.get("name").getAsString());
			}
		} catch (Exception e) {
			LOGGER.warn("[MCStats] usercache.json konnte nicht gelesen werden: {}", e.toString());
		}
		return names;
	}

	private void store(UUID uuid, String name, JsonObject rawStats, long lastSeen) {
		put(uuid, name, rawStats, doneAdvancements(uuid), lastSeen);
	}

	private void put(UUID uuid, String name, JsonObject rawStats, Set<String> rawAdvancements, long lastSeen) {
		JsonObject stats = baseline.apply(uuid, rawStats);
		int advancements = baseline.apply(uuid, rawAdvancements).size();
		records.put(uuid, new PlayerRecord(uuid, name, reader.compute(stats, advancements), stats, lastSeen, rawStats, rawAdvancements));
	}

	/** Nach Reset/Undo alle Spieler mit dem neuen Startpunkt neu berechnen. */
	private void recomputeAll() {
		for (PlayerRecord record : List.copyOf(records.values())) {
			put(record.uuid(), record.name(), record.rawStats(), record.rawAdvancements(), record.lastSeen());
		}
		rebuild();
	}

	private Set<String> doneAdvancements(UUID uuid) {
		Path file = advancementsDir.resolve(uuid + ".json");
		try {
			if (!Files.exists(file)) return Set.of();
			long modified = Files.getLastModifiedTime(file).toMillis();
			AdvancementCache cached = advancementCache.get(uuid);
			if (cached != null && cached.modified() == modified) {
				return cached.done();
			}
			Set<String> counted = countedAdvancements;
			Set<String> done = new HashSet<>();
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Entry<String, JsonElement> entry : root.entrySet()) {
				if (entry.getValue().isJsonObject() && counted.contains(entry.getKey())) {
					JsonElement flag = entry.getValue().getAsJsonObject().get("done");
					if (flag != null && flag.getAsBoolean()) done.add(entry.getKey());
				}
			}
			Set<String> result = Set.copyOf(done);
			advancementCache.put(uuid, new AdvancementCache(modified, result));
			return result;
		} catch (Exception e) {
			return Set.of();
		}
	}

	private void rebuild() {
		List<PlayerRecord> visible = visiblePlayers();
		Set<UUID> onlineIds = new HashSet<>();
		for (OnlinePlayer player : online) onlineIds.add(player.uuid());

		JsonObject root = new JsonObject();
		root.addProperty("updatedAt", Instant.now().toString());
		root.addProperty("serverName", config.serverName);
		root.addProperty("version", server.getServerVersion());
		root.addProperty("motd", server.getMotd());
		root.addProperty("maxPlayers", server.getPlayerList().getMaxPlayers());
		root.addProperty("playerCount", visible.size());
		Instant countingSince = baseline.createdAt();
		root.addProperty("countingSince", countingSince != null ? countingSince.toString() : null);

		JsonArray onlineArray = new JsonArray();
		for (OnlinePlayer player : online) {
			if (config.isHidden(player.name(), player.uuid().toString())) continue;
			JsonObject entry = playerRef(player.uuid(), player.name());
			twitch.liveFor(player.name()).ifPresent(live -> entry.add("live", live));
			onlineArray.add(entry);
		}
		root.addProperty("onlineCount", onlineArray.size());
		root.add("online", onlineArray);

		JsonArray liveArray = new JsonArray();
		for (PlayerRecord record : visible) {
			twitch.liveFor(record.name()).ifPresent(live -> {
				JsonObject entry = playerRef(record.uuid(), record.name());
				entry.addProperty("online", onlineIds.contains(record.uuid()));
				entry.add("live", live);
				liveArray.add(entry);
			});
		}
		root.add("live", liveArray);

		JsonObject totals = new JsonObject();
		for (Metric metric : Metric.values()) {
			totals.addProperty(metric.key, visible.stream().mapToLong(r -> r.get(metric)).sum());
		}
		root.add("totals", totals);

		JsonObject boards = new JsonObject();
		Map<UUID, EnumMap<Metric, Integer>> newRanks = new HashMap<>();
		for (Metric metric : Metric.values()) {
			List<PlayerRecord> sorted = new ArrayList<>(visible);
			sorted.sort(Comparator.comparingLong((PlayerRecord r) -> r.get(metric)).reversed().thenComparing(PlayerRecord::name, String.CASE_INSENSITIVE_ORDER));
			JsonArray board = new JsonArray();
			for (int i = 0; i < sorted.size(); i++) {
				PlayerRecord record = sorted.get(i);
				if (record.get(metric) <= 0) break;
				newRanks.computeIfAbsent(record.uuid(), k -> new EnumMap<>(Metric.class)).put(metric, i + 1);
				if (i < BOARD_SIZE) {
					JsonObject entry = playerRef(record.uuid(), record.name());
					entry.addProperty("value", record.get(metric));
					board.add(entry);
				}
			}
			boards.add(metric.key, board);
		}
		root.add("boards", boards);
		ranks = newRanks;

		String json = GSON.toJson(root);
		snapshotJson = json;
		for (Consumer<String> listener : listeners) {
			listener.accept(json);
		}
	}

	/** Ohne versteckte Spieler und ohne die, die seit einem Reset noch nicht gespielt haben. */
	private List<PlayerRecord> visiblePlayers() {
		List<PlayerRecord> list = new ArrayList<>();
		for (PlayerRecord record : records.values()) {
			if (record.get(Metric.PLAYTIME) > 0 && !config.isHidden(record.name(), record.uuid().toString())) list.add(record);
		}
		return list;
	}

	private JsonObject playerRef(UUID uuid, String name) {
		JsonObject object = new JsonObject();
		object.addProperty("uuid", uuid.toString());
		object.addProperty("name", config.displayName(name));
		object.addProperty("minecraft", name);
		addSkin(object, skins.get(uuid));
		return object;
	}

	/** Textur-Hash des Skins, damit die Website den echten Kopf zeigen kann. */
	public static void addSkin(JsonObject object, SkinStore.Skin skin) {
		if (skin == null) return;
		object.addProperty("skin", skin.hash());
		if (skin.slim()) object.addProperty("slim", true);
	}

	// ---------------------------------------------------------------- HTTP-Threads

	public String snapshotJson() {
		return snapshotJson;
	}

	public void onSnapshot(Consumer<String> listener) {
		listeners.add(listener);
	}

	public String playersJson() {
		JsonArray array = new JsonArray();
		Set<UUID> onlineIds = new HashSet<>();
		for (OnlinePlayer player : online) onlineIds.add(player.uuid());
		List<PlayerRecord> visible = visiblePlayers();
		visible.sort(Comparator.comparing(PlayerRecord::name, String.CASE_INSENSITIVE_ORDER));
		for (PlayerRecord record : visible) {
			JsonObject entry = playerRef(record.uuid(), record.name());
			entry.addProperty("online", onlineIds.contains(record.uuid()));
			entry.addProperty("playtime", record.get(Metric.PLAYTIME));
			entry.addProperty("lastSeen", Instant.ofEpochMilli(record.lastSeen()).toString());
			array.add(entry);
		}
		JsonObject root = new JsonObject();
		root.add("players", array);
		return GSON.toJson(root);
	}

	/** @param id UUID oder Spielername; null, wenn unbekannt oder versteckt */
	public String playerJson(String id) {
		PlayerRecord record = find(id);
		if (record == null || config.isHidden(record.name(), record.uuid().toString())) return null;

		boolean isOnline = online.stream().anyMatch(p -> p.uuid().equals(record.uuid()));
		JsonObject root = playerRef(record.uuid(), record.name());
		root.addProperty("online", isOnline);
		root.addProperty("lastSeen", Instant.ofEpochMilli(isOnline ? System.currentTimeMillis() : record.lastSeen()).toString());
		twitch.liveFor(record.name()).ifPresent(live -> root.add("live", live));
		String channel = twitch.channelFor(record.name());
		if (channel != null) root.addProperty("twitch", channel);

		JsonObject metrics = new JsonObject();
		for (Metric metric : Metric.values()) metrics.addProperty(metric.key, record.get(metric));
		root.add("metrics", metrics);

		JsonObject rankObject = new JsonObject();
		EnumMap<Metric, Integer> playerRanks = ranks.get(record.uuid());
		if (playerRanks != null) playerRanks.forEach((metric, rank) -> rankObject.addProperty(metric.key, rank));
		root.add("ranks", rankObject);
		root.addProperty("playerCount", visiblePlayers().size());
		Instant countingSince = baseline.createdAt();
		root.addProperty("countingSince", countingSince != null ? countingSince.toString() : null);

		root.add("mined", top(StatsReader.category(record.stats(), "mined"), 12, "block"));
		root.add("killed", top(StatsReader.category(record.stats(), "killed"), 12, "entity"));
		root.add("killedBy", top(StatsReader.category(record.stats(), "killed_by"), 8, "entity"));
		root.add("used", top(StatsReader.category(record.stats(), "used"), 12, "item", "block"));
		root.add("crafted", top(StatsReader.category(record.stats(), "crafted"), 12, "item", "block"));
		root.add("custom", top(StatsReader.category(record.stats(), "custom"), Integer.MAX_VALUE, "stat"));
		root.add("feed", feed.forPlayer(record.uuid().toString(), 20));
		return GSON.toJson(root);
	}

	private PlayerRecord find(String id) {
		try {
			PlayerRecord record = records.get(UUID.fromString(id));
			if (record != null) return record;
		} catch (IllegalArgumentException ignored) {
			// kein UUID, dann nach Namen suchen
		}
		String lower = id.toLowerCase(Locale.ROOT);
		for (PlayerRecord record : records.values()) {
			if (record.name().toLowerCase(Locale.ROOT).equals(lower) || config.displayName(record.name()).toLowerCase(Locale.ROOT).equals(lower)) {
				return record;
			}
		}
		return null;
	}

	/** @param kinds Präfixe der Übersetzungsschlüssel, z.B. "block" für block.minecraft.stone */
	private JsonArray top(JsonObject category, int limit, String... kinds) {
		List<Entry<String, JsonElement>> entries = new ArrayList<>(category.entrySet());
		entries.sort(Comparator.comparingLong((Entry<String, JsonElement> e) -> e.getValue().getAsLong()).reversed());
		JsonArray array = new JsonArray();
		for (Entry<String, JsonElement> entry : entries.subList(0, Math.min(limit, entries.size()))) {
			JsonObject object = new JsonObject();
			object.addProperty("id", entry.getKey());
			object.addProperty("name", nameOf(entry.getKey(), kinds));
			object.addProperty("value", entry.getValue().getAsLong());
			array.add(object);
		}
		return array;
	}

	private String nameOf(String id, String... kinds) {
		String key = id.replace(':', '.');
		String[] keys = new String[kinds.length];
		for (int i = 0; i < kinds.length; i++) keys[i] = kinds[i] + "." + key;
		String name = translations.first(keys);
		if (name != null) return name;
		String path = id.substring(id.indexOf(':') + 1).replace('_', ' ');
		return path.isEmpty() ? id : Character.toUpperCase(path.charAt(0)) + path.substring(1);
	}
}
