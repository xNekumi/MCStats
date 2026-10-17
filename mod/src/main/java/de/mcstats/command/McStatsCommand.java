package de.mcstats.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import de.mcstats.data.StatsService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * <pre>
 * /mcstats status          ab wann gezählt wird
 * /mcstats reset           Warnung anzeigen
 * /mcstats reset confirm   Startpunkt auf jetzt setzen, Feed leeren
 * /mcstats reset undo      Startpunkt entfernen, wieder alles zählen
 * </pre>
 * Nur für Operatoren. Die Minecraft-Statistiken selbst werden nie verändert.
 */
public final class McStatsCommand {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy 'um' HH:mm", Locale.GERMAN).withZone(ZoneId.systemDefault());

	private McStatsCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<StatsService> stats) {
		dispatcher.register(Commands.literal("mcstats")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("status").executes(ctx -> status(ctx, stats.get())))
				.then(Commands.literal("reset")
						.executes(McStatsCommand::warn)
						.then(Commands.literal("confirm").executes(ctx -> reset(ctx, stats.get())))
						.then(Commands.literal("undo").executes(ctx -> undo(ctx, stats.get())))));
	}

	private static int status(CommandContext<CommandSourceStack> ctx, StatsService stats) {
		if (notReady(ctx, stats)) return 0;
		var since = stats.baseline().createdAt();
		ctx.getSource().sendSuccess(() -> since == null
				? Component.literal("[MCStats] Es zählen alle Werte seit Beginn der Welt.").withStyle(ChatFormatting.GRAY)
				: Component.literal("[MCStats] Gezählt wird seit " + DATE.format(since) + ".").withStyle(ChatFormatting.GRAY), false);
		int known = stats.skins().size();
		int players = stats.knownPlayers().size();
		boolean onlineMode = ctx.getSource().getServer().usesAuthentication();
		ctx.getSource().sendSuccess(() -> Component.literal("[MCStats] Skins: " + known + " von " + players + " Spielern bekannt · Online-Mode: "
				+ (onlineMode ? "an" : "aus (Skins werden per Name nachgeschlagen)")).withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int warn(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendSuccess(() -> Component.literal("[MCStats] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal("Ab jetzt zählen nur noch neue Werte, der Feed wird geleert. "
						+ "Die Statistiken im Spiel bleiben erhalten. Zum Ausführen: ").withStyle(ChatFormatting.YELLOW))
				.append(Component.literal("/mcstats reset confirm").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)), false);
		return 1;
	}

	private static int reset(CommandContext<CommandSourceStack> ctx, StatsService stats) {
		if (notReady(ctx, stats)) return 0;
		CommandSourceStack source = ctx.getSource();
		stats.reset(error -> {
			if (error == null) {
				source.sendSuccess(() -> Component.literal("[MCStats] Stats zurückgesetzt – ab jetzt wird neu gezählt. Rückgängig: /mcstats reset undo")
						.withStyle(ChatFormatting.GREEN), true);
			} else {
				source.sendFailure(Component.literal("[MCStats] Reset fehlgeschlagen: " + error));
			}
		});
		return 1;
	}

	private static int undo(CommandContext<CommandSourceStack> ctx, StatsService stats) {
		if (notReady(ctx, stats)) return 0;
		if (stats.baseline().createdAt() == null) {
			ctx.getSource().sendFailure(Component.literal("[MCStats] Es ist kein Reset aktiv."));
			return 0;
		}
		CommandSourceStack source = ctx.getSource();
		stats.undoReset(error -> {
			if (error == null) {
				source.sendSuccess(() -> Component.literal("[MCStats] Reset rückgängig gemacht, es zählen wieder alle Werte. (Der Feed bleibt leer.)")
						.withStyle(ChatFormatting.GREEN), true);
			} else {
				source.sendFailure(Component.literal("[MCStats] Rückgängig machen fehlgeschlagen: " + error));
			}
		});
		return 1;
	}

	private static boolean notReady(CommandContext<CommandSourceStack> ctx, StatsService stats) {
		if (stats != null) return false;
		ctx.getSource().sendFailure(Component.literal("[MCStats] Noch nicht bereit, bitte gleich nochmal versuchen."));
		return true;
	}
}
