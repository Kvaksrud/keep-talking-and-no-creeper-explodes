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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

public class NoCreeperExplodes implements ModInitializer {
	public static final String MOD_ID = "nocreeperexplodes";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final int WIRE_COUNT = 3;
	/** Extra slack once defusing has started, so a nudge doesn't count as walking away. */
	public static final double REACH_TOLERANCE = 0.5;

	private static volatile NoCreeperExplodesConfig config = NoCreeperExplodesConfig.DEFAULTS;

	/** Saved with the creeper and synced to every client, so they can draw the tear. */
	public static final AttachmentType<Boolean> DEFUSED = AttachmentRegistry.create(id("defused"),
			builder -> builder.persistent(Codec.BOOL).syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.all()));

	/** Server-only: never saved or synced. The server alone runs the bomb timer and decides each cut. */
	public static final AttachmentType<DefuseSession> DEFUSE_SESSION = AttachmentRegistry.create(id("defuse_session"));

	public static final SoundEvent DEFUSE_BEEP = Registry.register(BuiltInRegistries.SOUND_EVENT, id("defuse_beep"),
			SoundEvent.createVariableRangeEvent(id("defuse_beep")));

	@Override
	public void onInitialize() {
		loadConfig();
		// Read again for every world or server start, so an edit doesn't need a game restart.
		ServerLifecycleEvents.SERVER_STARTING.register(_ -> loadConfig());

		PayloadTypeRegistry.clientboundPlay().register(OpenWireCutPayload.TYPE, OpenWireCutPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CutWirePayload.TYPE, CutWirePayload.CODEC);

		UseEntityCallback.EVENT.register(NoCreeperExplodes::onUseEntity);
		ServerPlayNetworking.registerGlobalReceiver(CutWirePayload.TYPE, NoCreeperExplodes::onCutWire);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static NoCreeperExplodesConfig config() {
		return config;
	}

	/** Replaces the settings until the next world or server start; the game tests use this. */
	public static void setConfig(NoCreeperExplodesConfig newConfig) {
		config = newConfig;
	}

	private static void loadConfig() {
		config = NoCreeperExplodesConfig.load(FabricLoader.getInstance().getConfigDir().resolve(NoCreeperExplodesConfig.FILE_NAME));
	}

	public static boolean isDefused(Creeper creeper) {
		return creeper.getAttachedOrElse(DEFUSED, false);
	}

	/**
	 * The player cutting this creeper's wires right now, or null: nobody is, the time is up, they walked away,
	 * or they have left the game or died.
	 */
	public static @Nullable ServerPlayer activeDefuser(Creeper creeper) {
		DefuseSession session = creeper.getAttached(DEFUSE_SESSION);

		if (session == null || session.walkedAway() || !session.isActive(creeper.level().getGameTime())) {
			return null;
		}

		ServerPlayer player = sessionPlayer(creeper, session);
		return player != null && isStillNextTo(player, creeper) ? player : null;
	}

	/** The session's player while they are online and alive, wherever they are; null once they leave the game or die. */
	public static @Nullable ServerPlayer sessionPlayer(Creeper creeper, DefuseSession session) {
		if (!(creeper.level() instanceof ServerLevel level)) {
			return null;
		}

		ServerPlayer player = level.getServer().getPlayerList().getPlayer(session.player());
		return player != null && player.isAlive() ? player : null;
	}

	/** True while the creeper must not swell: it is defused, or a bomb timer is running on it. */
	public static boolean isHeld(Creeper creeper) {
		return isDefused(creeper) || creeper.hasAttached(DEFUSE_SESSION);
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

	/** At most {@code reach} blocks of empty space between them, in the same world. */
	public static boolean isWithinReach(Player player, Creeper creeper, double reach) {
		return player.level() == creeper.level() && gapBetween(player, creeper) <= reach;
	}

	/** Still close enough to keep cutting: the defuse distance plus a little slack. Further away is walking away. */
	public static boolean isStillNextTo(Player player, Creeper creeper) {
		return isWithinReach(player, creeper, config.defuseDistance() + REACH_TOLERANCE);
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

	/** Starts the minigame for this player, unless someone else got there first, it's already armed, or they're too far away. */
	public static void tryStartDefuse(ServerPlayer player, Creeper creeper) {
		DefuseSession session = creeper.getAttached(DEFUSE_SESSION);

		if (session != null) {
			if (session.walkedAway()) {
				player.sendOverlayMessage(Component.translatable("message.nocreeperexplodes.armed"));
				return;
			}

			ServerPlayer defuser = sessionPlayer(creeper, session);

			if (defuser == player) {
				// Already cutting this one: a repeated click that arrived before the panel opened.
				// Opening a second panel would close the first, and that counts as walking away.
				return;
			}

			if (defuser != null) {
				player.sendOverlayMessage(Component.translatable("message.nocreeperexplodes.busy"));
				return;
			}

			// Its player left the game or died a moment ago; the creeper is free.
		}

		double distance = config.defuseDistance();

		if (!isWithinReach(player, creeper, distance)) {
			String message = distance == 1.0 ? "message.nocreeperexplodes.too_far_one_block" : "message.nocreeperexplodes.too_far";
			player.sendOverlayMessage(Component.translatable(message, NoCreeperExplodesConfig.formatNumber(distance)));
			return;
		}

		creeper.setAttached(DEFUSE_SESSION, DefuseSession.start(player.getUUID(), creeper.level().getGameTime()));
		creeper.setSwellDir(-1);
		creeper.getNavigation().stop();
		ServerPlayNetworking.send(player, new OpenWireCutPayload(creeper.getId(), distance));
	}

	private static void onCutWire(CutWirePayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();

		if (!(player.level().getEntity(payload.entityId()) instanceof Creeper creeper) || !creeper.isAlive()) {
			return;
		}

		DefuseSession session = creeper.getAttached(DEFUSE_SESSION);

		if (session == null || session.walkedAway() || !session.player().equals(player.getUUID())) {
			return;
		}

		if (!player.isAlive()) {
			// The panel closes when its player dies; that lets the creeper go rather than setting it off.
			creeper.removeAttached(DEFUSE_SESSION);
			return;
		}

		if (!session.isActive(creeper.level().getGameTime())) {
			// Too late: the timer ran out before this cut (or Esc) arrived.
			creeper.removeAttached(DEFUSE_SESSION);
			((DefusableCreeper) creeper).nocreeperexplodes$explodeFailedDefuse();
			return;
		}

		if (payload.wire() < 0 || payload.wire() >= WIRE_COUNT || !isStillNextTo(player, creeper)) {
			// Esc, or the panel closed because they got pushed away: they walked away from a live bomb.
			((DefusableCreeper) creeper).nocreeperexplodes$walkAway(player);
			return;
		}

		creeper.removeAttached(DEFUSE_SESSION);

		if (config.isSafeCut(creeper.getRandom().nextDouble())) {
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
