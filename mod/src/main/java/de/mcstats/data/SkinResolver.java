package de.mcstats.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schlägt Skins bei Mojang nach, wenn der Server sie nicht selbst kennt: für Spieler, die seit der
 * Installation nicht online waren, und auf Offline-Mode-Servern (dort fehlen die Skin-Daten im Profil).
 *
 * <p>Online-Mode-UUIDs (Version 4) werden direkt abgefragt, Offline-UUIDs (Version 3) über den Namen.
 * Es werden höchstens {@value #PER_RUN} Spieler pro Minute abgefragt, damit Mojangs Limits nie greifen.
 */
public final class SkinResolver {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcstats");
	private static final int PER_RUN = 30;
	private static final long RECHECK_MS = TimeUnit.HOURS.toMillis(12);

	private final SkinStore skins;
	private final Supplier<Map<UUID, String>> players;
	private final Runnable onChange;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final Map<UUID, Long> checked = new ConcurrentHashMap<>(); // UUID -> letzte Abfrage (ms)
	private ScheduledExecutorService scheduler;
	private boolean warned;

	/**
	 * @param players  alle bekannten Spieler (UUID -> Minecraft-Name)
	 * @param onChange wird aufgerufen, wenn neue Skins gefunden wurden
	 */
	public SkinResolver(SkinStore skins, Supplier<Map<UUID, String>> players, Runnable onChange) {
		this.skins = skins;
		this.players = players;
		this.onChange = onChange;
	}

	public void start() {
		scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("mcstats-skins").daemon().factory());
		scheduler.scheduleWithFixedDelay(this::run, 5, 60, TimeUnit.SECONDS);
	}

	public void stop() {
		if (scheduler != null) scheduler.shutdownNow();
	}

	private void run() {
		int requests = 0;
		int found = 0;
		long now = System.currentTimeMillis();
		try {
			for (Map.Entry<UUID, String> player : players.get().entrySet()) {
				if (requests >= PER_RUN) break;
				UUID uuid = player.getKey();
				if (skins.isFromServer(uuid)) continue;
				Long last = checked.get(uuid);
				if (last != null && now - last < RECHECK_MS) continue;
				checked.put(uuid, now);
				requests++;
				SkinStore.Skin skin = lookup(uuid, player.getValue());
				if (skin != null && !skin.equals(skins.get(uuid))) {
					skins.update(uuid, skin);
					found++;
				}
			}
			warned = false;
		} catch (RateLimitedException e) {
			// beim nächsten Durchlauf weiter
		} catch (Exception e) {
			if (!warned) LOGGER.warn("[MCStats] Skins konnten nicht bei Mojang nachgeschlagen werden: {}", e.toString());
			warned = true;
		}
		if (found > 0) {
			skins.saveIfDirty();
			LOGGER.info("[MCStats] {} Skin(s) bei Mojang nachgeschlagen", found);
			onChange.run();
		}
	}

	private SkinStore.Skin lookup(UUID uuid, String name) throws Exception {
		String id = uuid.version() == 4 ? uuid.toString().replace("-", "") : idForName(name);
		if (id == null) return null;
		JsonObject profile = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id);
		if (profile == null || !profile.has("properties")) return null;
		for (JsonElement element : profile.getAsJsonArray("properties")) {
			JsonObject property = element.getAsJsonObject();
			if ("textures".equals(property.get("name").getAsString())) {
				return SkinStore.fromTextures(property.get("value").getAsString());
			}
		}
		return null;
	}

	/** Account-ID zu einem Namen, null wenn es keinen gekauften Account mit diesem Namen gibt. */
	private String idForName(String name) throws Exception {
		if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) return null; // z.B. Bedrock-Namen mit Präfix
		JsonObject profile = get("https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(name, StandardCharsets.UTF_8));
		return profile != null && profile.has("id") ? profile.get("id").getAsString() : null;
	}

	private JsonObject get(String url) throws Exception {
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 429) throw new RateLimitedException();
		if (response.statusCode() == 204 || response.statusCode() == 404) return null;
		if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode() + " von " + URI.create(url).getHost());
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	private static final class RateLimitedException extends Exception {
		RateLimitedException() {
			super(null, null, false, false);
		}
	}
}
