package de.mcstats.tab;

import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Schreibt TPS (und optional MSPT) als Fußzeile unter die Tab-Liste. Die Liste selbst bleibt im
 * Minecraft-Standarddesign; eine Kopfzeile gibt es nicht.
 */
public final class TabListFooter {
	private final boolean showMspt;
	private int ticks;

	public TabListFooter(boolean showMspt) {
		this.showMspt = showMspt;
	}

	/** Aufruf am Ende jedes Server-Ticks; aktualisiert einmal pro Sekunde. */
	public void tick(MinecraftServer server) {
		if (++ticks % 20 != 0 || server.getPlayerList().getPlayerCount() == 0) return;
		ClientboundTabListPacket packet = new ClientboundTabListPacket(Component.empty(), footer(server));
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.connection.send(packet);
		}
	}

	/** z.B. "TPS: 20.0 · MSPT: 3.1", die Werte grün, gelb oder rot je nach Auslastung. */
	public MutableComponent footer(MinecraftServer server) {
		float targetTps = server.tickRateManager().tickrate();
		double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
		double tps = mspt > 0 ? Math.min(targetTps, 1000.0 / mspt) : targetTps;
		double budget = 1000.0 / targetTps; // ms, die ein Tick höchstens brauchen darf

		ChatFormatting tpsColor = tps >= targetTps * 0.95 ? ChatFormatting.GREEN : tps >= targetTps * 0.75 ? ChatFormatting.GOLD : ChatFormatting.RED;
		MutableComponent footer = Component.literal("TPS: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(String.format(Locale.ROOT, "%.1f", tps)).withStyle(tpsColor));
		if (showMspt) {
			ChatFormatting msptColor = mspt <= budget * 0.8 ? ChatFormatting.GREEN : mspt <= budget ? ChatFormatting.GOLD : ChatFormatting.RED;
			footer.append(Component.literal("  ·  MSPT: ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal(String.format(Locale.ROOT, "%.1f", mspt)).withStyle(msptColor));
		}
		return footer;
	}
}
