package no.kvaksrud.nocreeperexplodes.client;

import no.kvaksrud.nocreeperexplodes.network.OpenWireCutPayload;

import net.minecraft.client.renderer.entity.CreeperRenderer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;

public class NoCreeperExplodesClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(OpenWireCutPayload.TYPE, (payload, context) ->
				context.client().setScreen(new WireCutScreen(payload.entityId(), payload.defuseDistance())));

		LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, entityRenderer, registrationHelper, context) -> {
			if (entityRenderer instanceof CreeperRenderer creeperRenderer) {
				registrationHelper.register(new CreeperTearLayer(creeperRenderer));
			}
		});
	}
}
