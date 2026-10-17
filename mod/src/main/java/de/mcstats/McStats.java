package de.mcstats;

import com.google.gson.JsonObject;
import de.mcstats.api.ApiServer;
import de.mcstats.command.McStatsCommand;
import de.mcstats.config.McStatsConfig;
import de.mcstats.data.Baseline;
import de.mcstats.data.FeedService;
import de.mcstats.data.SkinResolver;
import de.mcstats.data.SkinStore;
import de.mcstats.data.StatsReader;
import de.mcstats.data.StatsService;
import de.mcstats.text.TextRenderer;
import de.mcstats.text.Translations;
import de.mcstats.twitch.TwitchService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McStats implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("mcstats");

	private static McStatsConfig config;
	private static Translations translations;
	private static TextRenderer text;
	private static FeedService feed;
	private static StatsService stats;
	private static TwitchService twitch;
	private static ApiServer api;
	private static SkinResolver skinResolver;
	private static int ticks;

	@Override
	public void onInitialize() {
		Path configDir = FabricLoader.getInstance().getConfigDir().resolve("mcstats");
		config = McStatsConfig.load(configDir.resolve("config.json"));
		translations = new Translations();
		translations.loadAsync(config.language, configDir.resolve("lang"));
		text = new TextRenderer(translations);

		ServerLifecycleEvents.SERVER_STARTED.register(McStats::start);
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> McStatsCommand.register(dispatcher, () -> stats));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			if (stats != null) stats.refreshAdvancementIds();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (stats == null) return;
			stats.tick();
			if (++ticks % 1200 == 0) Thread.ofVirtual().start(feed::save);
		});
	}

	private static void start(MinecraftServer server) {
		Set<String> food = new HashSet<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (item.components().has(DataComponents.FOOD)) {
				food.add(BuiltInRegistries.ITEM.getKey(item).toString());
			}
		}

		Path dataDir = server.getWorldPath(LevelResource.ROOT).resolve("mcstats");
		feed = new FeedService(dataDir.resolve("feed.json"), config.feedSize);
		twitch = new TwitchService(config.twitch);
		stats = new StatsService(server, config, new StatsReader(Set.copyOf(food)), feed, twitch, translations,
				new Baseline(dataDir.resolve("baseline.json")), new SkinStore(dataDir.resolve("skins.json")));
		skinResolver = new SkinResolver(stats.skins(), stats::knownPlayers, stats::requestRebuild);
		if (!server.usesAuthentication()) {
			LOGGER.info("[MCStats] Server läuft im Offline-Mode – Skins werden per Spielername bei Mojang nachgeschlagen");
		}
		api = new ApiServer(config, stats, feed);
		stats.start();
		twitch.start();
		skinResolver.start();
		try {
			api.start();
		} catch (IOException e) {
			LOGGER.error("[MCStats] API-Server konnte nicht auf {}:{} starten", config.bindAddress, config.port, e);
		}
	}

	private static void stop() {
		if (api != null) api.stop();
		if (twitch != null) twitch.stop();
		if (skinResolver != null) skinResolver.stop();
		if (stats != null) stats.stop();
		if (feed != null) feed.save();
		stats = null;
	}

	/** Aufgerufen vom {@link de.mcstats.mixin.ServerPlayerMixin} auf dem Server-Thread. */
	public static void onPlayerDeath(ServerPlayer player, DamageSource source) {
		if (feed == null || isHidden(player)) return;
		JsonObject event = new JsonObject();
		event.addProperty("type", "death");
		event.add("player", ref(player));
		Entity killer = source.getEntity();
		if (killer == null || killer == player) {
			LivingEntity credit = player.getKillCredit();
			killer = credit != player ? credit : null;
		}
		if (killer instanceof ServerPlayer killerPlayer && !isHidden(killerPlayer)) {
			event.add("killer", ref(killerPlayer));
		} else if (killer != null && !(killer instanceof ServerPlayer)) {
			event.addProperty("killerName", text.render(killer.getDisplayName()));
		}
		event.addProperty("message", text.render(player.getCombatTracker().getDeathMessage()));
		feed.add(event);
	}

	/** Aufgerufen vom {@link de.mcstats.mixin.PlayerAdvancementsMixin}, wenn ein Fortschritt komplett ist. */
	public static void onAdvancement(ServerPlayer player, AdvancementHolder holder) {
		if (feed == null || player == null || isHidden(player)) return;
		DisplayInfo display = holder.value().display().orElse(null);
		if (display == null || !display.announceToChat()) return; // Rezepte & stille Fortschritte
		JsonObject event = new JsonObject();
		event.addProperty("type", "advancement");
		event.add("player", ref(player));
		event.addProperty("advancement", holder.id().toString());
		event.addProperty("frame", display.type().getSerializedName());
		event.addProperty("title", text.render(display.title()));
		event.addProperty("description", text.render(display.description()));
		event.addProperty("message", text.render(display.type().createAnnouncement(holder, player)));
		feed.add(event);
	}

	private static boolean isHidden(ServerPlayer player) {
		return config.isHidden(player.getGameProfile().name(), player.getStringUUID());
	}

	private static JsonObject ref(ServerPlayer player) {
		String name = player.getGameProfile().name();
		JsonObject object = new JsonObject();
		object.addProperty("uuid", player.getStringUUID());
		object.addProperty("name", config.displayName(name));
		object.addProperty("minecraft", name);
		StatsService.addSkin(object, SkinStore.fromProfile(player.getGameProfile()));
		return object;
	}
}
