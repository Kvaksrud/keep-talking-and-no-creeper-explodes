package no.kvaksrud.nocreeperexplodes.client;

import no.kvaksrud.nocreeperexplodes.network.OpenWireCutPayload;

import net.minecraft.client.renderer.entity.CreeperRenderer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

public class NoCreeperExplodesClient implements ClientModInitializer {
	/** Copied from the creeper into its render state each frame; drives {@link CreeperTearLayer}. */
	public static final RenderStateDataKey<Boolean> DEFUSED = RenderStateDataKey.create(() -> "nocreeperexplodes defused");

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(OpenWireCutPayload.TYPE, (payload, context) ->
				context.client().setScreen(new WireCutScreen(payload.entityId())));

		LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, entityRenderer, registrationHelper, context) -> {
			if (entityRenderer instanceof CreeperRenderer creeperRenderer) {
				registrationHelper.register(new CreeperTearLayer(creeperRenderer));
			}
		});
	}
}
