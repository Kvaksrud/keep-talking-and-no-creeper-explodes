package no.kvaksrud.nocreeperexplodes.client;

import com.mojang.blaze3d.vertex.PoseStack;
import no.kvaksrud.nocreeperexplodes.NoCreeperExplodes;

import net.minecraft.client.model.monster.creeper.CreeperModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.CreeperRenderState;
import net.minecraft.resources.Identifier;

/**
 * Redraws the creeper model with a texture that is transparent except for a tear under its right eye,
 * the same trick vanilla uses for villager profession outfits. Works on top of any resource pack skin.
 */
public class CreeperTearLayer extends RenderLayer<CreeperRenderState, CreeperModel> {
	private static final Identifier TEAR_LOCATION = NoCreeperExplodes.id("textures/entity/creeper/creeper_tear.png");

	public CreeperTearLayer(RenderLayerParent<CreeperRenderState, CreeperModel> renderer) {
		super(renderer);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords, CreeperRenderState state, float yRot, float xRot) {
		if (state.getDataOrDefault(NoCreeperExplodesClient.DEFUSED, false)) {
			coloredCutoutModelCopyLayerRender(this.getParentModel(), TEAR_LOCATION, poseStack, submitNodeCollector, lightCoords, state, -1, 1);
		}
	}
}
