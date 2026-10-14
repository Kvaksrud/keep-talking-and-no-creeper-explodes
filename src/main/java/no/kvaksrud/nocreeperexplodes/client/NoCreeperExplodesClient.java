package no.kvaksrud.nocreeperexplodes.client;

import no.kvaksrud.nocreeperexplodes.NoCreeperExplodes;
import no.kvaksrud.nocreeperexplodes.network.OpenWireCutPayload;

import net.minecraft.client.renderer.entity.CreeperRenderer;
import net.minecraft.client.renderer.entity.state.CreeperRenderState;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Creeper;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;

/** The client half of the mod; never loaded on a dedicated server. */
@Mod(value = NoCreeperExplodes.MOD_ID, dist = Dist.CLIENT)
public class NoCreeperExplodesClient {
	/** Copied from the creeper into its render state each frame; drives {@link CreeperTearLayer}. */
	public static final ContextKey<Boolean> DEFUSED = new ContextKey<>(NoCreeperExplodes.id("defused"));

	public NoCreeperExplodesClient(IEventBus modEventBus) {
		modEventBus.addListener(NoCreeperExplodesClient::addTearLayer);
		modEventBus.addListener(NoCreeperExplodesClient::copyDefusedToRenderState);
	}

	/** The server started a bomb timer for this player: open the panel. Registered by {@link NoCreeperExplodes}. */
	public static void openWireCut(OpenWireCutPayload payload) {
		WireCutScreen.open(payload.entityId(), payload.defuseDistance());
	}

	private static void copyDefusedToRenderState(RegisterRenderStateModifiersEvent event) {
		event.<Creeper, CreeperRenderState>registerEntityModifier(CreeperRenderer.class,
				(creeper, state) -> state.setRenderData(DEFUSED, NoCreeperExplodes.isDefused(creeper)));
	}

	private static void addTearLayer(EntityRenderersEvent.AddLayers event) {
		if (event.getRenderer(EntityTypes.CREEPER) instanceof CreeperRenderer creeperRenderer) {
			creeperRenderer.addLayer(new CreeperTearLayer(creeperRenderer));
		}
	}
}
