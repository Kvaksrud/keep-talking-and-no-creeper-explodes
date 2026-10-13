package no.kvaksrud.nocreeperexplodes.client;

import no.kvaksrud.nocreeperexplodes.NoCreeperExplodes;
import no.kvaksrud.nocreeperexplodes.network.OpenWireCutPayload;

import net.minecraft.client.renderer.entity.CreeperRenderer;
import net.minecraft.world.entity.EntityType;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** The client half of the mod; never loaded on a dedicated server. */
@Mod(value = NoCreeperExplodes.MOD_ID, dist = Dist.CLIENT)
public class NoCreeperExplodesClient {
	public NoCreeperExplodesClient(IEventBus modEventBus) {
		modEventBus.addListener(NoCreeperExplodesClient::addTearLayer);
	}

	/** The server started a bomb timer for this player: open the panel. Registered by {@link NoCreeperExplodes}. */
	public static void openWireCut(OpenWireCutPayload payload) {
		WireCutScreen.open(payload.entityId(), payload.defuseDistance());
	}

	private static void addTearLayer(EntityRenderersEvent.AddLayers event) {
		if (event.getRenderer(EntityType.CREEPER) instanceof CreeperRenderer creeperRenderer) {
			creeperRenderer.addLayer(new CreeperTearLayer(creeperRenderer));
		}
	}
}
