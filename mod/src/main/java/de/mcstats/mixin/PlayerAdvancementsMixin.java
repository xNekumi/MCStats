package de.mcstats.mixin;

import de.mcstats.McStats;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {
	@Shadow
	private ServerPlayer player;

	@Unique
	private boolean mcstats$wasDone;

	@Shadow
	public abstract net.minecraft.advancements.AdvancementProgress getOrStartProgress(AdvancementHolder holder);

	@Inject(method = "award", at = @At("HEAD"))
	private void mcstats$beforeAward(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
		mcstats$wasDone = getOrStartProgress(holder).isDone();
	}

	@Inject(method = "award", at = @At("RETURN"))
	private void mcstats$afterAward(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && !mcstats$wasDone && getOrStartProgress(holder).isDone()) {
			McStats.onAdvancement(player, holder);
		}
	}
}
