package de.mcstats.mixin;

import com.google.gson.JsonElement;
import net.minecraft.stats.ServerStatsCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Gibt Zugriff auf das gleiche JSON, das Minecraft in world/stats/&lt;uuid&gt;.json schreibt. */
@Mixin(ServerStatsCounter.class)
public interface ServerStatsCounterInvoker {
	@Invoker("toJson")
	JsonElement mcstats$toJson();
}
