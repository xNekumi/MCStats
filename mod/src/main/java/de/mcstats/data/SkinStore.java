package de.mcstats.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merkt sich den Skin jedes Spielers, so wie ihn der Server im Spielerprofil sieht (auch mit
 * Skin-Mods). Gespeichert wird nur der Textur-Hash von textures.minecraft.net, in
 * world/mcstats/skins.json, damit der Kopf auch angezeigt wird, wenn der Spieler offline ist.
 */
public final class SkinStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcstats");
	private static final Gson GSON = new Gson();
	private static final Pattern HASH = Pattern.compile("[0-9a-f]{16,128}");

	/** @param hash Textur-Hash, @param slim schmale Arme ("Alex"-Modell) */
	public record Skin(String hash, boolean slim) {}

	private final Path file;
	private final Map<UUID, Skin> skins = new ConcurrentHashMap<>();
	private final Set<UUID> fromServer = ConcurrentHashMap.newKeySet(); // Skin kam direkt aus dem Spielerprofil
	private volatile boolean dirty;

	public SkinStore(Path file) {
		this.file = file;
		load();
	}

	public Skin get(UUID uuid) {
		return skins.get(uuid);
	}

	public int size() {
		return skins.size();
	}

	/** Skin aus dem Spielerprofil auf dem Server – hat Vorrang, ein Nachschlagen ist dann unnötig. */
	public void updateFromServer(UUID uuid, Skin skin) {
		if (skin == null) return;
		fromServer.add(uuid);
		update(uuid, skin);
	}

	/** Skin, der bei Mojang nachgeschlagen wurde. */
	public void update(UUID uuid, Skin skin) {
		if (skin != null && !skin.equals(skins.put(uuid, skin))) {
			dirty = true;
		}
	}

	public boolean isFromServer(UUID uuid) {
		return fromServer.contains(uuid);
	}

	/** Liest den Skin aus dem "textures"-Property eines Profils, null wenn keiner gesetzt ist. */
	public static Skin fromProfile(GameProfile profile) {
		for (Property property : profile.properties().get("textures")) {
			Skin skin = fromTextures(property.value());
			if (skin != null) return skin;
		}
		return null;
	}

	/** @param base64 Wert eines "textures"-Properties (aus dem Profil oder von Mojangs Session-Server) */
	public static Skin fromTextures(String base64) {
		try {
			String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
			JsonObject skin = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN");
			if (skin == null) return null;
			String url = skin.get("url").getAsString();
			String hash = url.substring(url.lastIndexOf('/') + 1).toLowerCase();
			if (!HASH.matcher(hash).matches()) return null;
			JsonObject metadata = skin.getAsJsonObject("metadata");
			boolean slim = metadata != null && metadata.has("model") && "slim".equals(metadata.get("model").getAsString());
			return new Skin(hash, slim);
		} catch (RuntimeException e) {
			return null; // kaputtes Property ignorieren
		}
	}

	public synchronized void saveIfDirty() {
		if (!dirty) return;
		dirty = false;
		JsonObject root = new JsonObject();
		skins.forEach((uuid, skin) -> {
			JsonObject object = new JsonObject();
			object.addProperty("hash", skin.hash());
			if (skin.slim()) object.addProperty("slim", true);
			root.add(uuid.toString(), object);
		});
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LOGGER.warn("[MCStats] Skins konnten nicht gespeichert werden", e);
		}
	}

	private void load() {
		if (!Files.exists(file)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
				JsonObject object = entry.getValue().getAsJsonObject();
				String hash = object.get("hash").getAsString();
				if (HASH.matcher(hash).matches()) {
					skins.put(UUID.fromString(entry.getKey()), new Skin(hash, object.has("slim") && object.get("slim").getAsBoolean()));
				}
			}
		} catch (Exception e) {
			LOGGER.warn("[MCStats] {} konnte nicht gelesen werden: {}", file, e.toString());
		}
	}
}
