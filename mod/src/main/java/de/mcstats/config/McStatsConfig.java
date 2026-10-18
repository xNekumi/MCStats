package de.mcstats.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Konfiguration aus {@code config/mcstats/config.json}. Fehlende Felder behalten ihren Standardwert,
 * die Datei wird beim Start mit allen Feldern neu geschrieben.
 */
public final class McStatsConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcstats");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Adresse, an die die Daten-API gebunden wird. "127.0.0.1" = nur lokal, "0.0.0.0" = alle Interfaces. */
	public String bindAddress = "127.0.0.1";
	/** Port der Daten-API. */
	public int port = 8765;
	/**
	 * Geheimer Schlüssel für die API. Ist er gesetzt, muss jede Anfrage den Header
	 * "Authorization: Bearer &lt;token&gt;" mitschicken. Leer = keine Prüfung.
	 */
	public String apiToken = "";
	/** Access-Control-Allow-Origin, falls Browser die API direkt abfragen sollen. Leer = kein CORS. */
	public String corsOrigin = "";
	/** Wie oft (Sekunden) die Stats der Online-Spieler neu eingelesen werden. */
	public int refreshSeconds = 30;
	/** Wie viele Feed-Einträge (Tode, Fortschritte) gespeichert werden. */
	public int feedSize = 100;
	/** Maximale Anzahl gleichzeitiger Live-Verbindungen (Server-Sent Events). */
	public int maxLiveClients = 500;
	/** Sprache für Todesnachrichten und Fortschritte, z.B. "de_de". Die Sprachdatei wird bei Bedarf von Mojang geladen. */
	public String language = "de_de";
	/** Name des Servers, wird auf der Website angezeigt. */
	public String serverName = "Mein Server";
	/** Spieler (Name oder UUID), die nirgends auftauchen sollen, z.B. Admins oder Bots. */
	public List<String> hiddenPlayers = new ArrayList<>();
	/** Optionale Anzeigenamen: Minecraft-Name -> Name auf der Website. */
	public Map<String, String> displayNames = new LinkedHashMap<>();
	public Twitch twitch = new Twitch();
	public TabList tabList = new TabList();

	public static final class TabList {
		/**
		 * TPS als Fußzeile unter der Tab-Liste anzeigen; die Liste bleibt im Minecraft-Standarddesign.
		 * Ausschalten, wenn ein anderer Mod (z.B. Styled Player List) die Tab-Liste gestaltet.
		 */
		public boolean showTps = true;
		/** Zusätzlich die MSPT (Millisekunden pro Tick) anzeigen. */
		public boolean showMspt = true;
	}

	public static final class Twitch {
		/** Twitch-Integration aktivieren ("Live"-Anzeige). Braucht eine App auf dev.twitch.tv. */
		public boolean enabled = false;
		public String clientId = "";
		public String clientSecret = "";
		/** Wie oft (Sekunden) Twitch abgefragt wird. */
		public int pollSeconds = 60;
		/** Minecraft-Name -> Twitch-Login. */
		public Map<String, String> channels = new LinkedHashMap<>();
	}

	public static McStatsConfig load(Path file) {
		McStatsConfig config = new McStatsConfig();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				McStatsConfig loaded = GSON.fromJson(reader, McStatsConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (IOException | JsonParseException e) {
				LOGGER.error("[MCStats] Konnte {} nicht lesen, nutze Standardwerte", file, e);
				return config;
			}
		}
		config.sanitize();
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			LOGGER.warn("[MCStats] Konnte {} nicht schreiben", file, e);
		}
		return config;
	}

	private void sanitize() {
		if (hiddenPlayers == null) hiddenPlayers = new ArrayList<>();
		if (displayNames == null) displayNames = new LinkedHashMap<>();
		if (twitch == null) twitch = new Twitch();
		if (twitch.channels == null) twitch.channels = new LinkedHashMap<>();
		if (tabList == null) tabList = new TabList();
		refreshSeconds = Math.max(5, refreshSeconds);
		feedSize = Math.clamp(feedSize, 10, 1000);
		maxLiveClients = Math.max(1, maxLiveClients);
		twitch.pollSeconds = Math.max(30, twitch.pollSeconds);
		if (language == null || language.isBlank()) language = "en_us";
		if (serverName == null) serverName = "";
		if (corsOrigin == null) corsOrigin = "";
		if (apiToken == null) apiToken = "";
	}

	public boolean isHidden(String name, String uuid) {
		for (String entry : hiddenPlayers) {
			if (entry.equalsIgnoreCase(name) || entry.equalsIgnoreCase(uuid)) {
				return true;
			}
		}
		return false;
	}

	public String displayName(String name) {
		return displayNames.getOrDefault(name, name);
	}
}
