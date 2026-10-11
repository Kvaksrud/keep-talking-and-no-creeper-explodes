package no.kvaksrud.nocreeperexplodes.client.mixin;

import no.kvaksrud.nocreeperexplodes.NoCreeperExplodes;
import no.kvaksrud.nocreeperexplodes.client.NoCreeperExplodesClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.entity.CreeperRenderer;
import net.minecraft.client.renderer.entity.state.CreeperRenderState;
import net.minecraft.world.entity.monster.Creeper;

@Mixin(CreeperRenderer.class)
public abstract class CreeperRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/monster/Creeper;Lnet/minecraft/client/renderer/entity/state/CreeperRenderState;F)V", at = @At("RETURN"))
	private void nocreeperexplodes$extractDefused(Creeper creeper, CreeperRenderState state, float partialTicks, CallbackInfo ci) {
		state.setData(NoCreeperExplodesClient.DEFUSED, NoCreeperExplodes.isDefused(creeper));
	}
}
