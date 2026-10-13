package no.kvaksrud.nocreeperexplodes.client;

import com.mojang.blaze3d.vertex.PoseStack;
import no.kvaksrud.nocreeperexplodes.NoCreeperExplodes;

import net.minecraft.client.model.CreeperModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.monster.Creeper;

/**
 * Redraws the creeper model with a texture that is transparent except for a tear under its right eye,
 * the same trick vanilla uses for villager profession outfits. Works on top of any resource pack skin.
 */
public class CreeperTearLayer extends RenderLayer<Creeper, CreeperModel<Creeper>> {
	private static final ResourceLocation TEAR_LOCATION = NoCreeperExplodes.id("textures/entity/creeper/creeper_tear.png");

	public CreeperTearLayer(RenderLayerParent<Creeper, CreeperModel<Creeper>> renderer) {
		super(renderer);
	}

	@Override
	public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, Creeper creeper,
			float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
		// The parent model is already posed for this frame; the defused flag reaches the client through the attachment sync.
		if (NoCreeperExplodes.isDefused(creeper) && !creeper.isInvisible()) {
			renderColoredCutoutModel(this.getParentModel(), TEAR_LOCATION, poseStack, bufferSource, packedLight, creeper, -1);
		}
	}
}
