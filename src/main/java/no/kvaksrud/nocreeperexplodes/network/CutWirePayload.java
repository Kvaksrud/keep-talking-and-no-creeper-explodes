package no.kvaksrud.nocreeperexplodes.network;

import no.kvaksrud.nocreeperexplodes.NoCreeperExplodes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the player cut a wire, or walked away from the bomb ({@link #CANCEL}). */
public record CutWirePayload(int entityId, int wire) implements CustomPacketPayload {
	public static final int CANCEL = -1;

	public static final Type<CutWirePayload> TYPE = new Type<>(NoCreeperExplodes.id("cut_wire"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CutWirePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, CutWirePayload::entityId,
			ByteBufCodecs.VAR_INT, CutWirePayload::wire,
			CutWirePayload::new);

	@Override
	public Type<CutWirePayload> type() {
		return TYPE;
	}
}
