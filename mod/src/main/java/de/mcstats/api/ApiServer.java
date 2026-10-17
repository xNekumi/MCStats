package de.mcstats.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.mcstats.config.McStatsConfig;
import de.mcstats.data.FeedService;
import de.mcstats.data.StatsService;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daten-API des Mods (JDK-HTTP-Server, ohne Abhängigkeiten). Liefert nur JSON, keine Website.
 *
 * <pre>
 * GET /api/stats          Überblick, Online-Liste, Bestenlisten
 * GET /api/feed           letzte Tode und Fortschritte
 * GET /api/players        alle Spieler
 * GET /api/player/{id}    Details eines Spielers (UUID oder Name)
 * GET /api/live           Server-Sent Events: "hello", "stats", "event", "feed" (Feed ersetzt, z.B. nach Reset)
 * </pre>
 */
public final class ApiServer {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcstats");
	private static final Gson GSON = new Gson();
	private static final String POISON = "";

	private final McStatsConfig config;
	private final StatsService stats;
	private final FeedService feed;
	private final byte[] token;
	private final Set<LinkedBlockingQueue<String>> liveClients = ConcurrentHashMap.newKeySet();
	private HttpServer server;
	private ExecutorService executor;

	public ApiServer(McStatsConfig config, StatsService stats, FeedService feed) {
		this.config = config;
		this.stats = stats;
		this.feed = feed;
		this.token = config.apiToken.isEmpty() ? null : ("Bearer " + config.apiToken).getBytes(StandardCharsets.UTF_8);
		stats.onSnapshot(json -> broadcast(sse("stats", json)));
		feed.onEvent(event -> broadcast(sse("event", GSON.toJson(event))));
		feed.onClear(() -> broadcast(sse("feed", "[]")));
	}

	public void start() throws IOException {
		executor = Executors.newVirtualThreadPerTaskExecutor();
		server = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 64);
		server.setExecutor(executor);
		server.createContext("/api/", this::handle);
		server.start();
		LOGGER.info("[MCStats] Daten-API läuft auf http://{}:{}/api/{}", config.bindAddress, config.port, token == null ? " (ohne Token)" : "");
	}

	public void stop() {
		for (LinkedBlockingQueue<String> client : liveClients) client.offer(POISON);
		if (server != null) server.stop(1);
		if (executor != null) executor.shutdownNow();
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			String method = exchange.getRequestMethod();
			if (!config.corsOrigin.isEmpty()) {
				exchange.getResponseHeaders().set("Access-Control-Allow-Origin", config.corsOrigin);
				exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization");
			}
			if (method.equals("OPTIONS")) {
				exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
				exchange.sendResponseHeaders(204, -1);
				return;
			}
			if (!method.equals("GET")) {
				json(exchange, 405, "{\"error\":\"Nur GET\"}");
				return;
			}
			if (!authorized(exchange)) {
				json(exchange, 401, "{\"error\":\"Token fehlt oder ist falsch\"}");
				return;
			}
			exchange.getResponseHeaders().set("Cache-Control", "no-store");
			route(exchange, exchange.getRequestURI().getRawPath().substring("/api".length()));
		} catch (IOException e) {
			// Client hat die Verbindung geschlossen
		} catch (RuntimeException e) {
			LOGGER.warn("[MCStats] Fehler bei {}", exchange.getRequestURI(), e);
		}
	}

	private boolean authorized(HttpExchange exchange) {
		if (token == null) return true;
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		return header != null && MessageDigest.isEqual(token, header.getBytes(StandardCharsets.UTF_8));
	}

	private void route(HttpExchange exchange, String path) throws IOException {
		switch (path) {
			case "/stats" -> json(exchange, 200, stats.snapshotJson());
			case "/feed" -> json(exchange, 200, feedJson(50));
			case "/players" -> json(exchange, 200, stats.playersJson());
			case "/live" -> live(exchange);
			default -> {
				if (path.startsWith("/player/")) {
					String id = URLDecoder.decode(path.substring("/player/".length()), StandardCharsets.UTF_8);
					String player = id.isBlank() || id.length() > 64 ? null : stats.playerJson(id);
					if (player != null) {
						json(exchange, 200, player);
					} else {
						json(exchange, 404, "{\"error\":\"Spieler nicht gefunden\"}");
					}
				} else {
					json(exchange, 404, "{\"error\":\"Unbekannter Endpunkt\"}");
				}
			}
		}
	}

	private String feedJson(int limit) {
		JsonObject root = new JsonObject();
		root.addProperty("updatedAt", Instant.now().toString());
		root.add("feed", feed.latest(limit));
		return GSON.toJson(root);
	}

	/** Server-Sent Events. Jeder Client bekommt einen eigenen (virtuellen) Thread und eine Queue. */
	private void live(HttpExchange exchange) throws IOException {
		if (liveClients.size() >= config.maxLiveClients) {
			json(exchange, 503, "{\"error\":\"Zu viele Verbindungen\"}");
			return;
		}
		exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		exchange.getResponseHeaders().set("X-Accel-Buffering", "no"); // nginx: nicht puffern
		exchange.sendResponseHeaders(200, 0);

		LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(256);
		liveClients.add(queue);
		try (OutputStream out = exchange.getResponseBody()) {
			String hello = "{\"stats\":" + stats.snapshotJson() + ",\"feed\":" + GSON.toJson(feed.latest(50)) + "}";
			write(out, "retry: 5000\n\n" + sse("hello", hello));
			while (true) {
				String message = queue.poll(20, TimeUnit.SECONDS);
				if (message == POISON) break;
				write(out, message != null ? message : ": ping\n\n");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			liveClients.remove(queue);
		}
	}

	private void broadcast(String message) {
		for (LinkedBlockingQueue<String> client : liveClients) {
			client.offer(message); // langsame Clients verlieren im Zweifel Nachrichten statt den Server zu bremsen
		}
	}

	private static String sse(String event, String data) {
		return "event: " + event + "\ndata: " + data + "\n\n";
	}

	private static void write(OutputStream out, String text) throws IOException {
		out.write(text.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private static void json(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
