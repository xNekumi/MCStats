package de.mcstats.twitch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.mcstats.config.McStatsConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fragt über die Twitch Helix API ab, welche der konfigurierten Kanäle gerade live sind. */
public final class TwitchService {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcstats");

	private final McStatsConfig.Twitch config;
	private final Map<String, String> channels = new HashMap<>(); // Minecraft-Name (klein) -> Twitch-Login (klein)
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private ScheduledExecutorService scheduler;
	private String token;
	private volatile Map<String, JsonObject> live = Map.of(); // Twitch-Login -> Stream-Infos

	public TwitchService(McStatsConfig.Twitch config) {
		this.config = config;
		config.channels.forEach((player, login) -> channels.put(player.toLowerCase(Locale.ROOT), login.toLowerCase(Locale.ROOT)));
	}

	public void start() {
		if (!config.enabled || channels.isEmpty()) return;
		if (config.clientId.isBlank() || config.clientSecret.isBlank()) {
			LOGGER.warn("[MCStats] Twitch ist aktiviert, aber clientId/clientSecret fehlen");
			return;
		}
		scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("mcstats-twitch").daemon().factory());
		scheduler.scheduleWithFixedDelay(this::poll, 0, config.pollSeconds, TimeUnit.SECONDS);
	}

	public void stop() {
		if (scheduler != null) scheduler.shutdownNow();
	}

	public String channelFor(String player) {
		return channels.get(player.toLowerCase(Locale.ROOT));
	}

	public Optional<JsonObject> liveFor(String player) {
		String login = channelFor(player);
		return login == null ? Optional.empty() : Optional.ofNullable(live.get(login));
	}

	private void poll() {
		try {
			if (token == null) token = fetchToken();
			Map<String, JsonObject> result = new HashMap<>();
			List<String> logins = new ArrayList<>(channels.values());
			for (int i = 0; i < logins.size(); i += 100) {
				StringBuilder query = new StringBuilder();
				for (String login : logins.subList(i, Math.min(i + 100, logins.size()))) {
					query.append(query.isEmpty() ? "?" : "&").append("user_login=").append(URLEncoder.encode(login, StandardCharsets.UTF_8));
				}
				HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("https://api.twitch.tv/helix/streams" + query))
						.header("Client-Id", config.clientId)
						.header("Authorization", "Bearer " + token)
						.timeout(Duration.ofSeconds(15))
						.build(), HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 401) {
					token = null; // abgelaufen, beim nächsten Mal neu holen
					return;
				}
				if (response.statusCode() != 200) {
					LOGGER.warn("[MCStats] Twitch antwortet mit HTTP {}", response.statusCode());
					return;
				}
				for (JsonElement element : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("data")) {
					JsonObject stream = element.getAsJsonObject();
					JsonObject info = new JsonObject();
					String login = stream.get("user_login").getAsString().toLowerCase(Locale.ROOT);
					info.addProperty("login", login);
					info.addProperty("title", stream.get("title").getAsString());
					info.addProperty("viewers", stream.get("viewer_count").getAsInt());
					info.addProperty("game", stream.get("game_name").getAsString());
					info.addProperty("startedAt", stream.get("started_at").getAsString());
					result.put(login, info);
				}
			}
			live = Map.copyOf(result);
		} catch (Exception e) {
			LOGGER.warn("[MCStats] Twitch-Abfrage fehlgeschlagen: {}", e.toString());
		}
	}

	private String fetchToken() throws Exception {
		String body = "client_id=" + URLEncoder.encode(config.clientId, StandardCharsets.UTF_8)
				+ "&client_secret=" + URLEncoder.encode(config.clientSecret, StandardCharsets.UTF_8)
				+ "&grant_type=client_credentials";
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("https://id.twitch.tv/oauth2/token"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("Token-Anfrage: HTTP " + response.statusCode());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject().get("access_token").getAsString();
	}
}
