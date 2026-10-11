package no.kvaksrud.nocreeperexplodes;

import com.mojang.serialization.Codec;
import no.kvaksrud.nocreeperexplodes.network.CutWirePayload;
import no.kvaksrud.nocreeperexplodes.network.OpenWireCutPayload;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class NoCreeperExplodes implements ModInitializer {
	public static final String MOD_ID = "nocreeperexplodes";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final int WIRE_COUNT = 3;
	/** How long the bomb timer runs: the creeper holds its fuse while someone is cutting wires. */
	public static final int SESSION_TICKS = 15 * 20;
	/** Most empty space, in blocks, allowed between the player and the creeper to start defusing. */
	public static final double DEFUSE_REACH = 1.0;
	/** Extra slack once defusing has started, so a nudge doesn't cancel it. */
	public static final double REACH_TOLERANCE = 0.5;
	/** A failed defuse (wrong wire, or the timer running out) makes the explosion this many times larger. */
	public static final float FAILED_DEFUSE_EXPLOSION_MULTIPLIER = 5.0F;

	/** Saved with the creeper and synced to every client, so they can draw the tear. */
	public static final AttachmentType<Boolean> DEFUSED = AttachmentRegistry.create(id("defused"),
			builder -> builder.persistent(Codec.BOOL).syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.all()));

	/** Server-only: never saved or synced, so the correct wire can't be read by the client. */
	public static final AttachmentType<DefuseSession> DEFUSE_SESSION = AttachmentRegistry.create(id("defuse_session"));

	public static final SoundEvent DEFUSE_BEEP = Registry.register(BuiltInRegistries.SOUND_EVENT, id("defuse_beep"),
			SoundEvent.createVariableRangeEvent(id("defuse_beep")));

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.clientboundPlay().register(OpenWireCutPayload.TYPE, OpenWireCutPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CutWirePayload.TYPE, CutWirePayload.CODEC);

		UseEntityCallback.EVENT.register(NoCreeperExplodes::onUseEntity);
		ServerPlayNetworking.registerGlobalReceiver(CutWirePayload.TYPE, NoCreeperExplodes::onCutWire);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static boolean isDefused(Creeper creeper) {
		return creeper.getAttachedOrElse(DEFUSED, false);
	}

	/**
	 * The player currently cutting this creeper's wires, or null. A session only counts while its player
	 * is online, alive, close by and inside the time limit, so a disconnect or death frees the creeper at once.
	 */
	public static @Nullable ServerPlayer activeDefuser(Creeper creeper) {
		DefuseSession session = creeper.getAttached(DEFUSE_SESSION);

		if (session == null || !session.isActive(creeper.level().getGameTime())) {
			return null;
		}

		return defuserStillThere(creeper, session);
	}

	/** The session's player, if they are still online, alive and next to the creeper; whether or not time is up. */
	public static @Nullable ServerPlayer defuserStillThere(Creeper creeper, DefuseSession session) {
		if (!(creeper.level() instanceof ServerLevel level)) {
			return null;
		}

		ServerPlayer player = level.getServer().getPlayerList().getPlayer(session.player());

		if (player == null || !player.isAlive() || player.level() != level || !isWithinReach(player, creeper, REACH_TOLERANCE)) {
			return null;
		}

		return player;
	}

	/** True while the creeper must not swell: it is defused, or someone is mid-way through cutting its wires. */
	public static boolean isHeld(Creeper creeper) {
		return isDefused(creeper) || activeDefuser(creeper) != null;
	}

	/** Blocks of empty space between two hitboxes; 0 when they touch or overlap. */
	public static double gapBetween(Entity a, Entity b) {
		AABB x = a.getBoundingBox();
		AABB y = b.getBoundingBox();
		double dx = Math.max(0.0, Math.max(x.minX - y.maxX, y.minX - x.maxX));
		double dy = Math.max(0.0, Math.max(x.minY - y.maxY, y.minY - x.maxY));
		double dz = Math.max(0.0, Math.max(x.minZ - y.maxZ, y.minZ - x.maxZ));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	public static boolean isWithinReach(Player player, Creeper creeper, double tolerance) {
		return gapBetween(player, creeper) <= DEFUSE_REACH + tolerance;
	}

	/** Ticks until the next beep: once a second at the start, speeding up to a frantic beep near zero. */
	public static int beepInterval(long ticksLeft) {
		return 3 + (int) (17 * Math.max(0, ticksLeft) / SESSION_TICKS);
	}

	private static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity, @Nullable EntityHitResult hitResult) {
		if (!(entity instanceof Creeper creeper) || player.isSpectator() || !creeper.isAlive()) {
			return InteractionResult.PASS;
		}

		boolean emptyMainHand = hand == InteractionHand.MAIN_HAND && player.getMainHandItem().isEmpty();

		if (isDefused(creeper)) {
			if (player.getItemInHand(hand).is(ItemTags.CREEPER_IGNITERS)) {
				// A cut fuse can't be lit again; don't let flint and steel waste a use on it either.
				return InteractionResult.FAIL;
			}

			if (!emptyMainHand) {
				return InteractionResult.PASS;
			}

			if (player instanceof ServerPlayer serverPlayer) {
				serverPlayer.sendOverlayMessage(Component.translatable("message.nocreeperexplodes.already_defused"));
			}

			return InteractionResult.SUCCESS;
		}

		if (!emptyMainHand) {
			return InteractionResult.PASS;
		}

		if (player instanceof ServerPlayer serverPlayer) {
			tryStartDefuse(serverPlayer, creeper);
		}

		// On the client this swings the arm and lets the server decide.
		return InteractionResult.SUCCESS;
	}

	/** Starts the minigame for this player, unless someone else got there first or they're too far away. */
	public static void tryStartDefuse(ServerPlayer player, Creeper creeper) {
		ServerPlayer defuser = activeDefuser(creeper);

		if (defuser == player) {
			// Already cutting this one: a repeated click that arrived before the panel opened.
			// Opening a second panel would close the first, and that sends a cancel.
			return;
		}

		if (defuser != null) {
			player.sendOverlayMessage(Component.translatable("message.nocreeperexplodes.busy"));
			return;
		}

		if (!isWithinReach(player, creeper, 0.0)) {
			player.sendOverlayMessage(Component.translatable("message.nocreeperexplodes.too_far"));
			return;
		}

		long now = creeper.level().getGameTime();
		creeper.setAttached(DEFUSE_SESSION, new DefuseSession(player.getUUID(), creeper.getRandom().nextInt(WIRE_COUNT), now + SESSION_TICKS));
		creeper.setSwellDir(-1);
		creeper.getNavigation().stop();
		ServerPlayNetworking.send(player, new OpenWireCutPayload(creeper.getId()));
	}

	private static void onCutWire(CutWirePayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();

		if (!(player.level().getEntity(payload.entityId()) instanceof Creeper creeper) || !creeper.isAlive()) {
			return;
		}

		DefuseSession session = creeper.getAttached(DEFUSE_SESSION);

		if (session == null || !session.player().equals(player.getUUID())) {
			return;
		}

		creeper.removeAttached(DEFUSE_SESSION);

		if (defuserStillThere(creeper, session) != player) {
			// Pushed away (or otherwise gone): the creeper just goes back to being a creeper.
			return;
		}

		if (!session.isActive(creeper.level().getGameTime())) {
			// Too late: the timer ran out before this cut (or cancel) arrived.
			((DefusableCreeper) creeper).nocreeperexplodes$explodeFailedDefuse();
			return;
		}

		if (payload.wire() < 0 || payload.wire() >= WIRE_COUNT) {
			// Backed out in time.
			return;
		}

		if (payload.wire() == session.correctWire()) {
			defuse(creeper);
		} else {
			((DefusableCreeper) creeper).nocreeperexplodes$explodeFailedDefuse();
		}
	}

	private static void defuse(Creeper creeper) {
		creeper.setAttached(DEFUSED, true);
		creeper.setSwellDir(-1);

		Level level = creeper.level();
		level.playSound(null, creeper.getX(), creeper.getY(), creeper.getZ(), SoundEvents.FIRE_EXTINGUISH, creeper.getSoundSource(), 0.6F, 1.4F);
		level.playSound(null, creeper.getX(), creeper.getY(), creeper.getZ(), SoundEvents.CREEPER_HURT, creeper.getSoundSource(), 0.5F, 1.7F);
		LOGGER.debug("Creeper {} defused", creeper.getUUID());
	}
}
