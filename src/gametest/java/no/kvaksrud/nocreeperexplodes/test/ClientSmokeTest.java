package no.kvaksrud.nocreeperexplodes.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.InputConstants;
import no.kvaksrud.nocreeperexplodes.DefuseSession;
import no.kvaksrud.nocreeperexplodes.NoCreeperExplodes;
import no.kvaksrud.nocreeperexplodes.NoCreeperExplodesConfig;
import no.kvaksrud.nocreeperexplodes.client.WireCutScreen;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Plays through the client side in a real game window (./gradlew runClientSmokeTest): a fresh flat creative world,
 * the settings file, opening the panel with a right-click, a safe cut and the tear it leaves (seen by the client
 * through the attachment sync), the beeps, walking away with Esc, and getting out of reach. Screenshots land in
 * build/run/clientSmokeTest/screenshots (prefixed nce_). The game exits with 0 when everything passed, 1 otherwise.
 *
 * <p>Does nothing unless the nocreeperexplodes.clientSmokeTest system property is set. The steps run on their own
 * thread, which waits there and hands every game call to the client or server thread.
 */
@EventBusSubscriber(modid = NoCreeperExplodes.MOD_ID, value = Dist.CLIENT)
public final class ClientSmokeTest {
	private static final boolean ENABLED = Boolean.getBoolean("nocreeperexplodes.clientSmokeTest");
	private static final Logger LOGGER = LoggerFactory.getLogger("nocreeperexplodes smoke test");
	private static final String WORLD_NAME = "nce-smoke-test";
	private static final long GIVE_UP_MINUTES = 10;
	/** Slack for "within 3 seconds": client and server ticks don't line up exactly. */
	private static final int MARGIN_TICKS = 10;

	private static final NoCreeperExplodesConfig DEFAULTS = NoCreeperExplodesConfig.DEFAULTS;
	private static final NoCreeperExplodesConfig ALWAYS_SAFE = new NoCreeperExplodesConfig(DEFAULTS.defuseDistance(), 100.0, DEFAULTS.explosionSizeMultiplier());

	private static final AtomicLong clientTicks = new AtomicLong();
	private static final AtomicInteger beeps = new AtomicInteger();
	private static final SoundEventListener BEEP_COUNTER = (sound, sounds, range) -> {
		if (sound.getLocation().equals(NoCreeperExplodes.DEFUSE_BEEP.getId())) {
			beeps.incrementAndGet();
		}
	};
	private static boolean started;

	private ClientSmokeTest() {
	}

	@SubscribeEvent
	static void onClientTick(ClientTickEvent.Post event) {
		if (!ENABLED) {
			return;
		}

		clientTicks.incrementAndGet();

		if (!started) {
			started = true;
			Thread steps = new Thread(ClientSmokeTest::run, "nocreeperexplodes client smoke test");
			steps.setDaemon(true);
			steps.start();
		}
	}

	private static void run() {
		Thread giveUp = new Thread(() -> {
			try {
				Thread.sleep(TimeUnit.MINUTES.toMillis(GIVE_UP_MINUTES));
				LOGGER.error("Client smoke test FAILED: still not done after {} minutes", GIVE_UP_MINUTES);
				Runtime.getRuntime().halt(2);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}, "nocreeperexplodes client smoke test timeout");
		giveUp.setDaemon(true);
		giveUp.start();

		try {
			playThrough(Minecraft.getInstance());
			LOGGER.info("Client smoke test PASSED");
			// Exits the game with status 0.
			onClient(() -> Minecraft.getInstance().stop());
		} catch (Throwable e) {
			LOGGER.error("Client smoke test FAILED: {}", e.getMessage(), e);
			System.exit(1);
		}
	}

	private static void playThrough(Minecraft minecraft) throws InterruptedException {
		waitFor("the title screen", 20 * 60 * 5, () -> client(() -> minecraft.getOverlay() == null
				&& (minecraft.screen instanceof TitleScreen || minecraft.screen instanceof AccessibilityOnboardingScreen)));
		onClient(() -> {
			// The test window is often not in front, and a pause menu would stop the integrated server.
			minecraft.options.pauseOnLostFocus = false;
			minecraft.options.onboardAccessibility = false;
			minecraft.options.tutorialStep = TutorialSteps.NONE;
			minecraft.getTutorial().setStep(TutorialSteps.NONE);
			minecraft.getSoundManager().addListener(BEEP_COUNTER);
		});

		// 1. A fresh flat creative world.
		LOGGER.info("Smoke test: creating a world");
		minecraft.execute(() -> createWorld(minecraft));
		waitFor("the world to load", 20 * 60 * 2, () -> client(() -> minecraft.level != null && minecraft.player != null
				&& minecraft.screen == null && minecraft.getSingleplayerServer() != null));
		waitTicks(40);
		IntegratedServer server = client(minecraft::getSingleplayerServer);
		UUID playerId = client(() -> minecraft.player.getUUID());

		// 2. The settings file was written with the defaults.
		Path file = FMLPaths.CONFIGDIR.get().resolve(NoCreeperExplodesConfig.FILE_NAME);
		check(Files.isRegularFile(file), "Expected the settings file at " + file);
		check(NoCreeperExplodesConfig.load(file).equals(DEFAULTS), "Expected the default settings in " + file);
		check(NoCreeperExplodes.config().equals(DEFAULTS), "Expected the default settings in use, got " + NoCreeperExplodes.config());

		Vec3 start = server(server, () -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			player.serverLevel().setDayTime(6000);
			return new Vec3(Mth.floor(player.getX()) + 0.5, player.getY(), Mth.floor(player.getZ()) + 0.5);
		});

		// 3. A right-click through the client's use key opens the panel.
		LOGGER.info("Smoke test: opening the panel");
		int lucky = spawnCreeperInFront(minecraft, server, playerId, start);
		openPanel(minecraft, lucky);
		check(server(server, () -> session(server, lucky) != null), "The right-click should start a defuse session on the server");
		screenshot(minecraft, "01_wire_panel");

		// 4. At a 100% chance, a click on a wire defuses it, the client learns it through the attachment sync, and it cries.
		LOGGER.info("Smoke test: cutting a wire");
		onServer(server, () -> NoCreeperExplodes.setConfig(ALWAYS_SAFE));

		try {
			onClient(() -> {
				WireCutScreen panel = (WireCutScreen) minecraft.screen;
				Vector2i wire = panel.wireCenter(1);
				check(panel.mouseClicked(wire.x, wire.y, InputConstants.MOUSE_BUTTON_LEFT), "Clicking the middle wire should cut it");
			});
			waitFor("the client to see the creeper defused", 40,
					() -> client(() -> minecraft.level.getEntity(lucky) instanceof Creeper creeper && NoCreeperExplodes.isDefused(creeper)));
		} finally {
			onServer(server, () -> NoCreeperExplodes.setConfig(DEFAULTS));
		}

		check(server(server, () -> server.overworld().getEntity(lucky) instanceof Creeper creeper && creeper.isAlive() && NoCreeperExplodes.isDefused(creeper)),
				"The server should have the creeper defused");
		check(client(() -> minecraft.screen instanceof WireCutScreen), "The panel should show that it's defused for a moment");
		screenshot(minecraft, "02_defused_panel");
		waitFor("the panel to close after defusing", 60, () -> client(() -> minecraft.screen == null));
		onClient(() -> {
			minecraft.options.hideGui = true;
			Entity creeper = minecraft.level.getEntity(lucky);
			minecraft.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(creeper.getX(), creeper.getY() + 1.45, creeper.getZ()));
		});
		screenshot(minecraft, "03_tear_close_up");
		onClient(() -> minecraft.options.hideGui = false);

		// 5. The bomb beeps; Esc walks away from it, and then it beeps frantically and goes off within 3 seconds.
		LOGGER.info("Smoke test: walking away with Esc");
		int leftBehind = spawnCreeperInFront(minecraft, server, playerId, start.add(24.0, 0.0, 0.0));
		beeps.set(0);
		openPanel(minecraft, leftBehind);
		waitTicks(40);
		int timerBeeps = beeps.get();
		LOGGER.info("Smoke test: {} beeps in the first 2 seconds", timerBeeps);
		check(timerBeeps >= 2, "Expected the bomb to beep at least twice in 2 seconds, heard " + timerBeeps);
		onClient(() -> check(minecraft.screen.keyPressed(InputConstants.KEY_ESCAPE, 0, 0), "Esc should close the panel"));
		long escAt = clientTicks.get();
		beeps.set(0);
		waitFor("the panel to close on Esc", 5, () -> client(() -> minecraft.screen == null));
		waitFor("the server to see the walk-away", 10, () -> server(server, () -> {
			DefuseSession session = session(server, leftBehind);
			return session != null && session.walkedAway();
		}));
		long fuse = server(server, () -> session(server, leftBehind).expiresAt() - server.overworld().getGameTime());
		check(fuse <= DefuseSession.WALK_AWAY_TICKS, "Walking away should leave at most 3 seconds, got " + fuse + " ticks");
		screenshot(minecraft, "04_walked_away");
		waitTicks((int) (escAt + 20 - clientTicks.get()));
		int franticBeeps = beeps.get();
		LOGGER.info("Smoke test: {} beeps in the second after walking away", franticBeeps);
		check(franticBeeps >= 7, "Expected frantic beeping (7 or more beeps in a second) after walking away, heard " + franticBeeps);
		waitFor("the walked-away bomb to go off", (int) (escAt + DefuseSession.WALK_AWAY_TICKS + MARGIN_TICKS - clientTicks.get()),
				() -> server(server, () -> server.overworld().getEntity(leftBehind) == null));
		waitTicks(10);
		screenshot(minecraft, "05_crater");

		// 6. Getting out of reach closes the panel by itself (at the server's defuse distance) and walks away too.
		LOGGER.info("Smoke test: getting out of reach");
		Vec3 third = start.add(48.0, 0.0, 0.0);
		int pushedOff = spawnCreeperInFront(minecraft, server, playerId, third);
		openPanel(minecraft, pushedOff);
		onServer(server, () -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			player.teleportTo(player.serverLevel(), third.x, third.y, third.z - 3.0, 0.0F, 0.0F);
		});
		waitFor("the panel to close out of reach", 20, () -> client(() -> minecraft.screen == null));
		waitFor("the server to see the walk-away", 10, () -> server(server, () -> {
			DefuseSession session = session(server, pushedOff);
			return session != null && session.walkedAway();
		}));
		screenshot(minecraft, "06_out_of_reach");
		waitFor("the walked-away bomb to go off", DefuseSession.WALK_AWAY_TICKS + MARGIN_TICKS,
				() -> server(server, () -> server.overworld().getEntity(pushedOff) == null));

		// 7. Leave the world the way the pause menu does.
		LOGGER.info("Smoke test: leaving the world");
		onClient(() -> {
			minecraft.getSoundManager().removeListener(BEEP_COUNTER);
			minecraft.level.disconnect();
			minecraft.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
			minecraft.setScreen(new TitleScreen());
		});
		waitFor("the title screen after leaving", 20 * 30, () -> client(() -> minecraft.level == null && minecraft.screen instanceof TitleScreen));
		// Let the screenshot writer finish.
		waitTicks(20);
	}

	private static void createWorld(Minecraft minecraft) {
		GameRules rules = new GameRules();
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
		rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
		rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
		// Easy, not peaceful: peaceful removes creepers.
		LevelSettings settings = new LevelSettings(WORLD_NAME, GameType.CREATIVE, false, Difficulty.EASY, true, rules, WorldDataConfiguration.DEFAULT);
		minecraft.createWorldOpenFlows().createFreshLevel(WORLD_NAME, settings, new WorldOptions(0L, false, false),
				registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
				new TitleScreen());
	}

	/**
	 * Moves the player to {@code at}, facing south, and puts a creeper without AI 1 block in front of them, facing
	 * them. Returns its entity id once the client has it.
	 */
	private static int spawnCreeperInFront(Minecraft minecraft, IntegratedServer server, UUID playerId, Vec3 at) throws InterruptedException {
		int id = server(server, () -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			ServerLevel level = player.serverLevel();
			player.teleportTo(level, at.x, at.y, at.z, 0.0F, 0.0F);
			Creeper creeper = new Creeper(EntityType.CREEPER, level);
			creeper.moveTo(at.x, at.y, at.z + 1.0, 180.0F, 0.0F);
			creeper.setYHeadRot(180.0F);
			creeper.setYBodyRot(180.0F);
			creeper.setNoAi(true);
			creeper.setPersistenceRequired();
			check(level.addFreshEntity(creeper), "Could not spawn a creeper");
			return creeper.getId();
		});
		waitFor("the client to see the creeper and the player in place", 40,
				() -> client(() -> minecraft.level.getEntity(id) instanceof Creeper && minecraft.player.position().distanceTo(at) < 0.01));
		waitTicks(5);
		return id;
	}

	/** Aims at the creeper and presses the use key, as a player would. */
	private static void openPanel(Minecraft minecraft, int creeperId) throws InterruptedException {
		onClient(() -> minecraft.player.lookAt(EntityAnchorArgument.Anchor.EYES, minecraft.level.getEntity(creeperId).getBoundingBox().getCenter()));
		waitFor("the crosshair on the creeper", 20,
				() -> client(() -> minecraft.hitResult instanceof EntityHitResult hit && hit.getEntity().getId() == creeperId));
		onClient(() -> KeyMapping.click(minecraft.options.keyUse.getKey()));
		waitFor("the wire panel", 40, () -> client(() -> minecraft.screen instanceof WireCutScreen));
	}

	private static @Nullable DefuseSession session(IntegratedServer server, int creeperId) {
		return server.overworld().getEntity(creeperId) instanceof Creeper creeper ? creeper.getExistingDataOrNull(NoCreeperExplodes.DEFUSE_SESSION) : null;
	}

	private static void screenshot(Minecraft minecraft, String name) throws InterruptedException {
		onClient(() -> minecraft.getToasts().clear());
		// A couple of frames, so the picture shows what the game shows now.
		waitTicks(3);
		onClient(() -> Screenshot.grab(minecraft.gameDirectory, "nce_" + name + ".png", minecraft.getMainRenderTarget(),
				message -> LOGGER.info("Smoke test: {}", message.getString())));
	}

	private static void waitTicks(int ticks) throws InterruptedException {
		long until = clientTicks.get() + ticks;

		while (clientTicks.get() < until) {
			Thread.sleep(5);
		}
	}

	private static void waitFor(String what, int maxTicks, BooleanSupplier condition) throws InterruptedException {
		long deadline = clientTicks.get() + maxTicks;

		while (!condition.getAsBoolean()) {
			if (clientTicks.get() > deadline) {
				throw new AssertionError("Timed out after " + maxTicks + " ticks waiting for " + what);
			}

			Thread.sleep(10);
		}
	}

	private static <T> T client(Supplier<T> action) {
		return await(Minecraft.getInstance().submit(action));
	}

	private static void onClient(Runnable action) {
		await(Minecraft.getInstance().submit(action));
	}

	private static <T> T server(IntegratedServer server, Supplier<T> action) {
		return await(server.submit(action));
	}

	private static void onServer(IntegratedServer server, Runnable action) {
		await(server.submit(action));
	}

	private static <T> T await(CompletableFuture<T> future) {
		try {
			return future.get(60, TimeUnit.SECONDS);
		} catch (Exception e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;

			if (cause instanceof AssertionError assertion) {
				throw assertion;
			}

			throw new AssertionError("A game call failed or hung: " + cause, cause);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
