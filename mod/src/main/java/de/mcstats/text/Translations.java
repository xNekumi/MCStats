package de.mcstats.text;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.locale.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Übersetzungen für Todesnachrichten und Fortschritte. Ein dedizierter Server kennt nur Englisch,
 * deshalb wird die gewünschte Sprachdatei (z.B. de_de.json) einmalig von Mojangs Asset-Server
 * geladen und unter config/mcstats/lang/ zwischengespeichert. Eigene Dateien dort haben Vorrang.
 */
public final class Translations {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcstats");
	private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
	private static final String RESOURCES = "https://resources.download.minecraft.net/";

	private volatile Map<String, String> entries = Map.of();

	public String get(String key, String fallback) {
		String value = entries.get(key);
		if (value != null) {
			return value;
		}
		return Language.getInstance().getOrDefault(key, fallback != null ? fallback : key);
	}

	/** Erster vorhandener Schlüssel, sonst null. */
	public String first(String... keys) {
		Map<String, String> map = entries;
		for (String key : keys) {
			String value = map.get(key);
			if (value != null) return value;
			if (Language.getInstance().has(key)) return Language.getInstance().getOrDefault(key);
		}
		return null;
	}

	/** Lädt die Sprache im Hintergrund; bis dahin wird Englisch verwendet. */
	public void loadAsync(String language, Path langDir) {
		if (language.equalsIgnoreCase("en_us")) {
			return;
		}
		Thread.ofVirtual().name("mcstats-lang").start(() -> {
			try {
				Path file = langDir.resolve(language.toLowerCase() + ".json");
				if (!Files.exists(file)) {
					download(language.toLowerCase(), file);
				}
				Map<String, String> map = new HashMap<>();
				try (InputStream in = Files.newInputStream(file)) {
					Language.loadFromJson(in, map::put);
				}
				entries = Map.copyOf(map);
				LOGGER.info("[MCStats] Sprache {} geladen ({} Einträge)", language, map.size());
			} catch (Exception e) {
				LOGGER.warn("[MCStats] Sprache {} konnte nicht geladen werden, nutze Englisch: {}", language, e.toString());
			}
		});
	}

	private static void download(String language, Path target) throws IOException, InterruptedException {
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
		String versionId = SharedConstants.getCurrentVersion().id();

		JsonObject manifest = getJson(http, MANIFEST);
		String versionUrl = null;
		for (var element : manifest.getAsJsonArray("versions")) {
			JsonObject version = element.getAsJsonObject();
			if (version.get("id").getAsString().equals(versionId)) {
				versionUrl = version.get("url").getAsString();
				break;
			}
		}
		if (versionUrl == null) {
			throw new IOException("Version " + versionId + " nicht im Manifest gefunden");
		}
		String assetIndexUrl = getJson(http, versionUrl).getAsJsonObject("assetIndex").get("url").getAsString();
		JsonObject object = getJson(http, assetIndexUrl).getAsJsonObject("objects").getAsJsonObject("minecraft/lang/" + language + ".json");
		if (object == null) {
			throw new IOException("Sprache " + language + " gibt es nicht");
		}
		String hash = object.get("hash").getAsString();

		HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(RESOURCES + hash.substring(0, 2) + "/" + hash)).build(), HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " beim Laden der Sprachdatei");
		}
		Files.createDirectories(target.getParent());
		Files.write(target, response.body());
		LOGGER.info("[MCStats] Sprachdatei {} heruntergeladen", target);
	}

	private static JsonObject getJson(HttpClient http, String url) throws IOException, InterruptedException {
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " für " + url);
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}
}
