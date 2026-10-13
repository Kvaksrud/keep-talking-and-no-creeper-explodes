package no.kvaksrud.nocreeperexplodes;

import java.util.function.Supplier;

import com.mojang.serialization.Codec;
import no.kvaksrud.nocreeperexplodes.client.NoCreeperExplodesClient;
import no.kvaksrud.nocreeperexplodes.network.CutWirePayload;
import no.kvaksrud.nocreeperexplodes.network.OpenWireCutPayload;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
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

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

@Mod(NoCreeperExplodes.MOD_ID)
public class NoCreeperExplodes {
	public static final String MOD_ID = "nocreeperexplodes";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final int WIRE_COUNT = 3;
	/** Extra slack once defusing has started, so a nudge doesn't count as walking away. */
	public static final double REACH_TOLERANCE = 0.5;

	private static volatile NoCreeperExplodesConfig config = NoCreeperExplodesConfig.DEFAULTS;

	private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MOD_ID);
	private static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(Registries.SOUND_EVENT, MOD_ID);

	/** Saved with the creeper and synced to every client, so they can draw the tear. */
	public static final Supplier<AttachmentType<Boolean>> DEFUSED = ATTACHMENT_TYPES.register("defused",
			() -> AttachmentType.builder(() -> false).serialize(Codec.BOOL).sync((holder, player) -> hasMod(player), ByteBufCodecs.BOOL).build());

	/**
	 * Server-only: never saved or synced. The server alone runs the bomb timer and decides each cut. There is no default
	 * session: read it with getExistingDataOrNull or hasData.
	 */
	public static final Supplier<AttachmentType<DefuseSession>> DEFUSE_SESSION = ATTACHMENT_TYPES.register("defuse_session",
			() -> AttachmentType.<DefuseSession>builder(() -> {
				throw new IllegalStateException("A defuse session is only ever set explicitly");
			}).build());

	public static final DeferredHolder<SoundEvent, SoundEvent> DEFUSE_BEEP = SOUND_EVENTS.register("defuse_beep", SoundEvent::createVariableRangeEvent);

	public NoCreeperExplodes(IEventBus modEventBus) {
		loadConfig();
		ATTACHMENT_TYPES.register(modEventBus);
		SOUND_EVENTS.register(modEventBus);
		modEventBus.addListener(NoCreeperExplodes::registerPayloads);

		// Read again for every world or server start, so an edit doesn't need a game restart.
		NeoForge.EVENT_BUS.addListener(ServerStartingEvent.class, event -> loadConfig());
		NeoForge.EVENT_BUS.addListener(NoCreeperExplodes::onEntityInteract);
	}

	private static void registerPayloads(RegisterPayloadHandlersEvent event) {
		// Both handlers run on the main thread (the 1.21.1 default).
		event.registrar("1")
				// Only ever handled on the client; the lambda keeps the client class from loading on a dedicated server.
				.playToClient(OpenWireCutPayload.TYPE, OpenWireCutPayload.CODEC, (payload, context) -> NoCreeperExplodesClient.openWireCut(payload))
				.playToServer(CutWirePayload.TYPE, CutWirePayload.CODEC, NoCreeperExplodes::onCutWire);
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}

	public static NoCreeperExplodesConfig config() {
		return config;
	}

	/** Replaces the settings until the next world or server start; the game tests use this. */
	public static void setConfig(NoCreeperExplodesConfig newConfig) {
		config = newConfig;
	}

	private static void loadConfig() {
		config = NoCreeperExplodesConfig.load(FMLPaths.CONFIGDIR.get().resolve(NoCreeperExplodesConfig.FILE_NAME));
	}

	/**
	 * Whether this player's game has the mod, so it can be sent the panel and the defused flag. Fake players have
	 * no network channel at all, and game-test players have one without the mod on the other end.
	 */
	public static boolean hasMod(ServerPlayer player) {
		return player.connection != null && player.connection.getConnection().channel() != null
				&& player.connection.hasChannel(OpenWireCutPayload.TYPE);
	}

	public static boolean isDefused(Creeper creeper) {
		return Boolean.TRUE.equals(creeper.getExistingDataOrNull(DEFUSED));
	}

	/**
	 * The player cutting this creeper's wires right now, or null: nobody is, the time is up, they walked away,
	 * or they have left the game or died.
	 */
	public static @Nullable ServerPlayer activeDefuser(Creeper creeper) {
		DefuseSession session = creeper.getExistingDataOrNull(DEFUSE_SESSION);

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
		return isDefused(creeper) || creeper.hasData(DEFUSE_SESSION);
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

	private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		InteractionResult result = onUseEntity(event.getEntity(), event.getHand(), event.getTarget());

		if (result != InteractionResult.PASS) {
			event.setCancellationResult(result);
			event.setCanceled(true);
		}
	}

	private static InteractionResult onUseEntity(Player player, InteractionHand hand, Entity entity) {
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
				serverPlayer.displayClientMessage(Component.translatable("message.nocreeperexplodes.already_defused"), true);
			}

			return InteractionResult.sidedSuccess(player.level().isClientSide());
		}

		if (!emptyMainHand) {
			return InteractionResult.PASS;
		}

		if (player instanceof ServerPlayer serverPlayer) {
			tryStartDefuse(serverPlayer, creeper);
		}

		// On the client this swings the arm and lets the server decide.
		return InteractionResult.sidedSuccess(player.level().isClientSide());
	}

	/** Starts the minigame for this player, unless someone else got there first, it's already armed, or they're too far away. */
	public static void tryStartDefuse(ServerPlayer player, Creeper creeper) {
		DefuseSession session = creeper.getExistingDataOrNull(DEFUSE_SESSION);

		if (session != null) {
			if (session.walkedAway()) {
				player.displayClientMessage(Component.translatable("message.nocreeperexplodes.armed"), true);
				return;
			}

			ServerPlayer defuser = sessionPlayer(creeper, session);

			if (defuser == player) {
				// Already cutting this one: a repeated click that arrived before the panel opened.
				// Opening a second panel would close the first, and that counts as walking away.
				return;
			}

			if (defuser != null) {
				player.displayClientMessage(Component.translatable("message.nocreeperexplodes.busy"), true);
				return;
			}

			// Its player left the game or died a moment ago; the creeper is free.
		}

		double distance = config.defuseDistance();

		if (!isWithinReach(player, creeper, distance)) {
			String message = distance == 1.0 ? "message.nocreeperexplodes.too_far_one_block" : "message.nocreeperexplodes.too_far";
			player.displayClientMessage(Component.translatable(message, NoCreeperExplodesConfig.formatNumber(distance)), true);
			return;
		}

		creeper.setData(DEFUSE_SESSION, DefuseSession.start(player.getUUID(), creeper.level().getGameTime()));
		creeper.setSwellDir(-1);
		creeper.getNavigation().stop();

		if (hasMod(player)) {
			PacketDistributor.sendToPlayer(player, new OpenWireCutPayload(creeper.getId(), distance));
		}
	}

	private static void onCutWire(CutWirePayload payload, IPayloadContext context) {
		if (context.player() instanceof ServerPlayer player && player.level().getEntity(payload.entityId()) instanceof Creeper creeper) {
			cutWire(player, creeper, payload.wire());
		}
	}

	/**
	 * What the player's panel sent: the wire they cut (0 to {@link #WIRE_COUNT} - 1), or {@link CutWirePayload#CANCEL}
	 * when it closed early. Public so the game tests can cut wires without a client.
	 */
	public static void cutWire(ServerPlayer player, Creeper creeper, int wire) {
		if (!creeper.isAlive()) {
			return;
		}

		DefuseSession session = creeper.getExistingDataOrNull(DEFUSE_SESSION);

		if (session == null || session.walkedAway() || !session.player().equals(player.getUUID())) {
			return;
		}

		if (!player.isAlive()) {
			// The panel closes when its player dies; that lets the creeper go rather than setting it off.
			creeper.removeData(DEFUSE_SESSION);
			return;
		}

		if (!session.isActive(creeper.level().getGameTime())) {
			// Too late: the timer ran out before this cut (or Esc) arrived.
			creeper.removeData(DEFUSE_SESSION);
			((DefusableCreeper) creeper).nocreeperexplodes$explodeFailedDefuse();
			return;
		}

		if (wire < 0 || wire >= WIRE_COUNT || !isStillNextTo(player, creeper)) {
			// Esc, or the panel closed because they got pushed away: they walked away from a live bomb.
			((DefusableCreeper) creeper).nocreeperexplodes$walkAway(player);
			return;
		}

		creeper.removeData(DEFUSE_SESSION);

		if (config.isSafeCut(creeper.getRandom().nextDouble())) {
			defuse(creeper);
		} else {
			((DefusableCreeper) creeper).nocreeperexplodes$explodeFailedDefuse();
		}
	}

	private static void defuse(Creeper creeper) {
		creeper.setData(DEFUSED, true);
		creeper.setSwellDir(-1);

		Level level = creeper.level();
		level.playSound(null, creeper.getX(), creeper.getY(), creeper.getZ(), SoundEvents.FIRE_EXTINGUISH, creeper.getSoundSource(), 0.6F, 1.4F);
		level.playSound(null, creeper.getX(), creeper.getY(), creeper.getZ(), SoundEvents.CREEPER_HURT, creeper.getSoundSource(), 0.5F, 1.7F);
		LOGGER.debug("Creeper {} defused", creeper.getUUID());
	}
}
