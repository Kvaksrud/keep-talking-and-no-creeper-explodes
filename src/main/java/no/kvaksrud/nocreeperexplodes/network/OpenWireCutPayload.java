package no.kvaksrud.nocreeperexplodes.network;

import no.kvaksrud.nocreeperexplodes.NoCreeperExplodes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: open the wire-cutting minigame for this creeper. Carries the server's defuse distance,
 * so the panel closes (walking away) at the same point the server sees it.
 */
public record OpenWireCutPayload(int entityId, double defuseDistance) implements CustomPacketPayload {
	public static final Type<OpenWireCutPayload> TYPE = new Type<>(NoCreeperExplodes.id("open_wire_cut"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenWireCutPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, OpenWireCutPayload::entityId,
			ByteBufCodecs.DOUBLE, OpenWireCutPayload::defuseDistance,
			OpenWireCutPayload::new);

	@Override
	public Type<OpenWireCutPayload> type() {
		return TYPE;
	}
}
