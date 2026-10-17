package de.mcstats.mixin;

import de.mcstats.McStats;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	// Am Anfang von die(), solange der CombatTracker noch die Todesursache kennt.
	@Inject(method = "die", at = @At("HEAD"))
	private void mcstats$onDeath(DamageSource source, CallbackInfo ci) {
		McStats.onPlayerDeath((ServerPlayer) (Object) this, source);
	}
}
