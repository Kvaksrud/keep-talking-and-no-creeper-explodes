package no.kvaksrud.nocreeperexplodes.test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.InputConstants;
import no.kvaksrud.nocreeperexplodes.DefuseSession;
import no.kvaksrud.nocreeperexplodes.NoCreeperExplodes;
import no.kvaksrud.nocreeperexplodes.NoCreeperExplodesConfig;
import no.kvaksrud.nocreeperexplodes.client.WireCutScreen;
import org.joml.Vector2i;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Plays the client side through in a real game window, for {@code ./gradlew runClientSmokeTest} (needs a display, so
 * it isn't part of CI): a fresh flat creative world, the bomb panel opened with a real right-click, a wire cut through
 * the panel, the defused flag reaching the client and the tear, the beeps, walking away with Esc and by getting out of
 * reach. Screenshots land in build/run/clientSmokeTest/screenshots (prefixed nce_). Then the game quits: exit code 0
 * if everything worked, 1 if not, so the Gradle task fails with it.
 *
 * <p>Runs on a thread of its own, which waits in between and hands every step to the client or server thread.
 * Does nothing unless that run sets the {@value #PROPERTY} system property. The game is muted: sounds are still
 * played as far as the game is concerned (and counted), just not out loud.
 */
public class NoCreeperExplodesClientSmokeTest implements ClientModInitializer {
	public static final String PROPERTY = "nocreeperexplodes.clientSmokeTest";

	private static final Logger LOGGER = LoggerFactory.getLogger("nocreeperexplodes client smoke test");
	private static final NoCreeperExplodesConfig DEFAULTS = NoCreeperExplodesConfig.DEFAULTS;
	private static final String WORLD_NAME = "nce-client-smoke-test";
	/** Gives up on a step if the client thread doesn't answer in this time. */
	private static final long TASK_TIMEOUT_SECONDS = 60;

	private final AtomicLong clientTicks = new AtomicLong();
	private final AtomicInteger beeps = new AtomicInteger();
	private Minecraft client;

	@Override
	public void onInitializeClient() {
		if (System.getProperty(PROPERTY) == null) {
			return;
		}

		this.client = Minecraft.getInstance();
		ClientTickEvents.END_CLIENT_TICK.register(client -> this.clientTicks.incrementAndGet());
		Thread thread = new Thread(this::run, "Client smoke test");
		thread.setDaemon(true);
		thread.start();
	}

	private void run() {
		try {
			this.playThrough();
			LOGGER.info("CLIENT SMOKE TEST PASSED");
			this.runOnClient(() -> this.client.stop());
		} catch (Throwable e) {
			LOGGER.error("CLIENT SMOKE TEST FAILED: {}", e.getMessage(), e);

			try {
				this.screenshot("nce_failure");
			} catch (Throwable screenshotFailure) {
				LOGGER.warn("Could not take a screenshot of the failure", screenshotFailure);
			}

			System.exit(1);
		}
	}

	private void playThrough() {
		this.step("Creating a flat creative world");
		this.waitFor("the game to finish loading", 20 * 300, () -> this.client.getOverlay() == null && this.client.screen != null);
		this.runOnClient(() -> {
			this.client.options.pauseOnLostFocus = false;
			this.client.options.onboardAccessibility = false;
			// No tutorial toasts in the screenshots.
			this.client.options.tutorialStep = TutorialSteps.NONE;
			this.client.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
			this.client.getSoundManager().addListener(this.beepCounter());
		});
		this.client.execute(this::createWorld);
		this.waitFor("the world to open", 20 * 300, () -> this.client.level != null && this.client.player != null && this.client.screen == null);
		this.waitFor("the chunks to render", 20 * 60, () -> this.client.levelRenderer.hasRenderedAllSections());
		UUID playerId = this.onClient(() -> this.client.player.getUUID());
		this.onServer(server -> {
			server.overworld().setDayTime(6000);
			return null;
		});

		this.step("Checking the settings file");
		Path configFile = FabricLoader.getInstance().getConfigDir().resolve(NoCreeperExplodesConfig.FILE_NAME);
		check(Files.isRegularFile(configFile), "Expected the settings file at " + configFile);
		check(NoCreeperExplodesConfig.load(configFile).equals(DEFAULTS), "Expected the settings file to hold the defaults");
		check(NoCreeperExplodes.config().equals(DEFAULTS), "Expected the default settings, got " + NoCreeperExplodes.config());

		this.step("Right-clicking a creeper with a shovel (on the defuse_tools list) opens the bomb panel");
		Creeper lucky = this.spawnCreeperInFront(playerId, 0);
		this.holdInMainHand(playerId, new ItemStack(Items.DIAMOND_SHOVEL));
		this.rightClick(lucky.getId());
		this.waitFor("the bomb panel to open", 40, () -> this.client.screen instanceof WireCutScreen);
		check(this.onServer(server -> lucky.hasAttached(NoCreeperExplodes.DEFUSE_SESSION)), "Right-click should start a defuse session on the server");
		this.waitTicks(10);
		this.screenshot("nce_01_panel");

		this.step("Cutting a wire defuses it, and the client hears about it");
		NoCreeperExplodes.setConfig(new NoCreeperExplodesConfig(DEFAULTS.defuseDistance(), 100.0, DEFAULTS.explosionSizeMultiplier()));
		this.runOnClient(() -> {
			WireCutScreen panel = (WireCutScreen) this.client.screen;
			Vector2i wire = panel.wireCenter(1);
			check(panel.mouseClicked(wire.x, wire.y, InputConstants.MOUSE_BUTTON_LEFT), "The panel should take a click on a wire");
		});
		this.waitFor("the client-side creeper to be defused", 40, () -> this.client.level.getEntity(lucky.getId()) instanceof Creeper creeper && NoCreeperExplodes.isDefused(creeper));
		NoCreeperExplodes.setConfig(DEFAULTS);
		check(this.onServer(server -> lucky.isAlive() && NoCreeperExplodes.isDefused(lucky) && !lucky.hasAttached(NoCreeperExplodes.DEFUSE_SESSION)), "The server-side creeper should be defused");
		this.waitTicks(3);
		check(this.onClient(() -> this.client.screen instanceof WireCutScreen), "The panel should stay up a moment to say the creeper is defused");
		this.screenshot("nce_02_defused_panel");
		this.waitFor("the panel to close after defusing", 60, () -> this.client.screen == null);
		this.holdInMainHand(playerId, ItemStack.EMPTY);
		this.runOnClient(() -> {
			this.client.options.hideGui = true;
			this.client.player.lookAt(EntityAnchorArgument.Anchor.EYES, this.creeperOnClient(lucky.getId()).position().add(0.0, 1.35, 0.0));
		});
		this.waitTicks(5);
		this.screenshot("nce_03_tear");
		this.runOnClient(() -> this.client.options.hideGui = false);

		// The blasts from here on are normal-sized, so the craters stay small.
		NoCreeperExplodes.setConfig(new NoCreeperExplodesConfig(DEFAULTS.defuseDistance(), DEFAULTS.successChancePercent(), 1.0));

		this.step("The bomb beeps, and walking away with Esc sets it off");
		Creeper leftBehind = this.spawnCreeperInFront(playerId, 32);
		this.rightClick(leftBehind.getId());
		this.waitFor("the bomb panel to open", 40, () -> this.client.screen instanceof WireCutScreen);
		this.beeps.set(0);
		this.waitTicks(40);
		check(this.beeps.get() >= 2, "Expected the bomb to beep while defusing, heard " + this.beeps.get() + " beeps in 2 seconds");
		LOGGER.info("{} beeps in 2 seconds while defusing", this.beeps.get());
		this.runOnClient(() -> {
			check(this.client.screen instanceof WireCutScreen, "The panel should still be open");
			this.client.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
			check(this.client.screen == null, "Esc should close the panel");
		});
		long escAt = this.onServer(server -> server.overworld().getGameTime());
		DefuseSession armed = this.waitForServer("the server to see the walk-away", 10, server -> {
			DefuseSession session = leftBehind.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
			return session != null && session.walkedAway() ? session : null;
		});
		check(armed.expiresAt() - escAt <= DefuseSession.WALK_AWAY_TICKS + 2, "Walking away should leave at most 3 seconds, got " + (armed.expiresAt() - escAt) + " ticks");
		this.beeps.set(0);
		this.waitTicks(20);
		check(this.beeps.get() >= 7, "Expected frantic beeping after walking away, heard " + this.beeps.get() + " beeps in a second");
		LOGGER.info("{} beeps in a second after walking away", this.beeps.get());
		this.screenshot("nce_04_walked_away");
		long blewUpAt = this.waitForServer("the bomb to go off", DefuseSession.WALK_AWAY_TICKS + 20, server -> leftBehind.isRemoved() ? server.overworld().getGameTime() : null);
		check(blewUpAt - escAt <= DefuseSession.WALK_AWAY_TICKS + 5, "Should go off within 3 seconds of walking away, took " + (blewUpAt - escAt) + " ticks");
		this.waitFor("the creeper to be gone on the client too", 20, () -> this.client.level.getEntity(leftBehind.getId()) == null);

		this.step("Getting out of reach closes the panel and sets the bomb off");
		Creeper pushedOff = this.spawnCreeperInFront(playerId, 64);
		this.rightClick(pushedOff.getId());
		this.waitFor("the bomb panel to open", 40, () -> this.client.screen instanceof WireCutScreen);
		this.waitTicks(5);
		this.onServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			player.teleportTo(server.overworld(), player.getX(), player.getY(), player.getZ() - 4.0, player.getYRot(), player.getXRot());
			return null;
		});
		this.waitFor("the panel to close by itself out of reach", 20, () -> this.client.screen == null);
		this.waitForServer("the server to see the walk-away", 10, server -> {
			DefuseSession session = pushedOff.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
			return session != null && session.walkedAway() ? session : null;
		});
		this.waitForServer("the bomb to go off", DefuseSession.WALK_AWAY_TICKS + 20, server -> pushedOff.isRemoved() ? Boolean.TRUE : null);
		NoCreeperExplodes.setConfig(DEFAULTS);

		this.step("Leaving the world");
		this.runOnClient(() -> {
			this.client.level.disconnect();
			this.client.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
			this.client.setScreen(new TitleScreen());
		});
		this.waitFor("the world to close", 20 * 60, () -> this.client.level == null && this.client.getSingleplayerServer() == null);
	}

	private void createWorld() {
		GameRules rules = new GameRules();
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
		rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
		rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
		LevelSettings settings = new LevelSettings(WORLD_NAME, GameType.CREATIVE, false, Difficulty.EASY, true, rules, WorldDataConfiguration.DEFAULT);
		this.client.createWorldOpenFlows().createFreshLevel(WORLD_NAME, settings, new WorldOptions(0L, false, false),
				registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
				new TitleScreen());
	}

	/**
	 * Puts the player on fresh ground at this x, facing south, with a creeper (no AI) 1 block in front of them,
	 * facing back. Returns the server's creeper, once the client has it too.
	 */
	private Creeper spawnCreeperInFront(UUID playerId, int x) {
		Creeper creeper = this.onServer(server -> {
			ServerLevel level = server.overworld();
			level.getChunkAt(new BlockPos(x, 0, 0));
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, 0);
			server.getPlayerList().getPlayer(playerId).teleportTo(level, x + 0.5, y, 0.5, 0.0F, 0.0F);
			Creeper c = EntityType.CREEPER.create(level);
			c.moveTo(x + 0.5, y, 1.5, 180.0F, 0.0F);
			c.setYHeadRot(180.0F);
			c.setYBodyRot(180.0F);
			c.setNoAi(true);
			c.setPersistenceRequired();
			check(level.addFreshEntity(c), "Could not spawn a creeper");
			return c;
		});
		this.waitFor("the creeper and the player's new spot to reach the client", 40, () -> this.client.level.getEntity(creeper.getId()) instanceof Creeper
				&& this.client.player.position().distanceTo(new Vec3(x + 0.5, this.client.player.getY(), 0.5)) < 0.01);
		this.waitFor("the chunks to render", 20 * 30, () -> this.client.levelRenderer.hasRenderedAllSections());
		return creeper;
	}

	/** Aims at the creeper and presses the use key, the way a player's right-click goes. */
	private void rightClick(int creeperId) {
		this.runOnClient(() -> {
			Creeper creeper = this.creeperOnClient(creeperId);
			this.client.player.lookAt(EntityAnchorArgument.Anchor.EYES, creeper.position().add(0.0, 1.0, 0.0));
			this.client.gameRenderer.pick(1.0F);
			check(this.client.hitResult instanceof EntityHitResult hit && hit.getEntity() == creeper, "Expected the crosshair on the creeper, got " + this.client.hitResult);
			KeyMapping.click(KeyBindingHelper.getBoundKeyOf(this.client.options.keyUse));
		});
	}

	private Creeper creeperOnClient(int id) {
		if (this.client.level.getEntity(id) instanceof Creeper creeper) {
			return creeper;
		}

		throw new AssertionError("The client has no creeper " + id);
	}

	private SoundEventListener beepCounter() {
		return (sound, soundEvents, range) -> {
			if (sound.getLocation().equals(NoCreeperExplodes.DEFUSE_BEEP.getLocation())) {
				this.beeps.incrementAndGet();
			}
		};
	}

	/** Saves the last frame to screenshots/{@code name}.png in the run directory and waits until it is written. */
	private void screenshot(String name) {
		CompletableFuture<Component> saved = new CompletableFuture<>();
		this.runOnClient(() -> Screenshot.grab(this.client.gameDirectory, name + ".png", this.client.getMainRenderTarget(), saved::complete));

		try {
			saved.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new AssertionError("Could not save screenshot " + name, e);
		}

		File file = new File(new File(this.client.gameDirectory, "screenshots"), name + ".png");
		check(file.length() > 0, "Expected a screenshot at " + file);
		LOGGER.info("Saved {}", file);
	}

	private void step(String description) {
		LOGGER.info("Client smoke test: {}", description);
	}

	private void runOnClient(Runnable task) {
		this.onClient(() -> {
			task.run();
			return null;
		});
	}

	private <T> T onClient(Supplier<T> task) {
		return join(this.client.submit(task), "the client thread");
	}

	/** Puts this in the player's main hand on the server, then waits until the client's inventory shows it too. */
	private void holdInMainHand(UUID playerId, ItemStack stack) {
		this.onServer(server -> {
			server.getPlayerList().getPlayer(playerId).setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
			return null;
		});
		this.waitFor("the client to see the item in hand", 40, () -> ItemStack.matches(this.client.player.getMainHandItem(), stack));
	}

	private <T> T onServer(Function<MinecraftServer, T> task) {
		MinecraftServer server = this.onClient(() -> this.client.getSingleplayerServer());
		check(server != null, "Expected a singleplayer server");
		return join(server.submit(() -> task.apply(server)), "the server thread");
	}

	private static <T> T join(CompletableFuture<T> future, String where) {
		try {
			return future.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (java.util.concurrent.ExecutionException e) {
			throw e.getCause() instanceof AssertionError failure ? failure : new AssertionError("Failed on " + where, e.getCause());
		} catch (Exception e) {
			throw new AssertionError("Gave up waiting for " + where, e);
		}
	}

	private void waitTicks(int ticks) {
		long until = this.clientTicks.get() + ticks;
		long gaveUpAt = System.currentTimeMillis() + ticks * 100L + 30_000L;

		while (this.clientTicks.get() < until) {
			check(System.currentTimeMillis() < gaveUpAt, "The client stopped ticking");
			sleep();
		}
	}

	/** Checks the condition on the client thread every tick, for at most this many ticks. */
	private void waitFor(String what, int maxTicks, BooleanSupplier condition) {
		long until = this.clientTicks.get() + maxTicks;
		// The client doesn't tick while it loads a world, so wall-clock time counts too.
		long gaveUpAt = System.currentTimeMillis() + maxTicks * 100L + 30_000L;

		while (!this.onClient(condition::getAsBoolean)) {
			check(this.clientTicks.get() <= until && System.currentTimeMillis() < gaveUpAt, "Timed out waiting for " + what);
			sleep();
		}
	}

	/** Like {@link #waitFor}, but asks the server, and returns its first non-null answer. */
	private <T> T waitForServer(String what, int maxTicks, Function<MinecraftServer, T> condition) {
		long until = this.clientTicks.get() + maxTicks;
		long gaveUpAt = System.currentTimeMillis() + maxTicks * 100L + 30_000L;

		while (true) {
			T answer = this.onServer(condition);

			if (answer != null) {
				return answer;
			}

			check(this.clientTicks.get() <= until && System.currentTimeMillis() < gaveUpAt, "Timed out waiting for " + what);
			sleep();
		}
	}

	private static void sleep() {
		try {
			Thread.sleep(5);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted", e);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
