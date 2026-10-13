package no.kvaksrud.nocreeperexplodes.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import no.kvaksrud.nocreeperexplodes.DefuseSession;
import no.kvaksrud.nocreeperexplodes.NoCreeperExplodes;
import no.kvaksrud.nocreeperexplodes.NoCreeperExplodesConfig;
import no.kvaksrud.nocreeperexplodes.network.CutWirePayload;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Plays the mod on a headless game test server: the settings file, too far, a longer defuse distance, one defuser at
 * a time, a creeper that holds still, a defuser who leaves the game or dies, defuse, try to relight, defuse twice,
 * save and reload, the blasts for a wrong wire and for running out of time, walking away with Esc or by getting out
 * of reach, and a smaller blast setting. Mock players stand in for real ones; where a client would send a packet,
 * the test calls the server side directly.
 *
 * <p>Tests in a batch run side by side, so each blast gets a batch of its own, and a setting changed for one test is
 * put back before that tick ends (except in the defuse distance batch, which has the server to itself).
 */
@GameTestHolder(NoCreeperExplodes.MOD_ID)
@PrefixGameTestTemplate(false)
public class NoCreeperExplodesGameTest {
	/** A 17x17 grass floor walled in by barriers; see tools/gen_test_structure.py. */
	private static final String ARENA = "arena";
	private static final String DEFUSE_DISTANCE_BATCH = "defuse_distance";
	/** Slack for "within 3 seconds": the creeper and the test check don't run at the same point in a tick. */
	private static final int MARGIN_TICKS = 5;

	private static final NoCreeperExplodesConfig DEFAULTS = NoCreeperExplodesConfig.DEFAULTS;
	private static final NoCreeperExplodesConfig ALWAYS_SAFE = new NoCreeperExplodesConfig(DEFAULTS.defuseDistance(), 100.0, DEFAULTS.explosionSizeMultiplier());
	private static final NoCreeperExplodesConfig NEVER_SAFE = new NoCreeperExplodesConfig(DEFAULTS.defuseDistance(), 0.0, DEFAULTS.explosionSizeMultiplier());

	/** The middle of the arena, standing on its floor (the floor is at y 1; the test's structure block is at 0, 0, 0). */
	private static final Vec3 MIDDLE = new Vec3(8.5, 2.0, 8.5);
	/** Next to a creeper in the middle: 0.4 blocks of empty space between them. */
	private static final Vec3 NEXT_TO_MIDDLE = MIDDLE.add(0.0, 0.0, 1.0);

	@GameTest(template = ARENA)
	public static void settingsFileHoldsTheDefaults(GameTestHelper helper) {
		Path file = FMLPaths.CONFIGDIR.get().resolve(NoCreeperExplodesConfig.FILE_NAME);
		helper.assertTrue(Files.isRegularFile(file), "Expected the settings file at " + file);
		helper.assertValueEqual(NoCreeperExplodesConfig.load(file), DEFAULTS, "the settings in " + file);
		helper.assertValueEqual(NoCreeperExplodes.config(), DEFAULTS, "the settings in use");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = DEFUSE_DISTANCE_BATCH)
	public static void defuseDistanceDecidesHowCloseToStand(GameTestHelper helper) {
		Creeper creeper = creeper(helper, MIDDLE, false);
		ServerPlayer player = player(helper, MIDDLE.add(0.0, 0.0, 3.0));

		// More than 1 block away: nothing starts.
		rightClick(player, creeper);
		helper.assertFalse(creeper.hasData(NoCreeperExplodes.DEFUSE_SESSION), "No defuse session should start from 3 blocks away");

		// With a 3-block defuse distance it does, and standing there doesn't count as walking away.
		NoCreeperExplodes.setConfig(new NoCreeperExplodesConfig(3.0, DEFAULTS.successChancePercent(), DEFAULTS.explosionSizeMultiplier()));
		rightClick(player, creeper);
		helper.assertTrue(creeper.hasData(NoCreeperExplodes.DEFUSE_SESSION), "A 3-block defuse distance should let a right-click from 3 blocks away start defusing");

		helper.startSequence()
				.thenIdle(10)
				.thenExecute(() -> {
					DefuseSession session = session(creeper);
					helper.assertTrue(session != null && !session.walkedAway(), "Defusing should go on 3 blocks away with a 3-block distance");
					// Ends the test bomb without a bang: walking away from it would set it off.
					creeper.discard();
					NoCreeperExplodes.setConfig(DEFAULTS);
					leave(helper, player);
				})
				.thenSucceed();
	}

	/** Puts the default settings back even when the defuse distance test fails halfway. */
	@AfterBatch(batch = DEFUSE_DISTANCE_BATCH)
	public static void restoreDefaultSettings(ServerLevel level) {
		NoCreeperExplodes.setConfig(DEFAULTS);
	}

	@GameTest(template = ARENA)
	public static void oneDefuserAtATimeAndTheCreeperHoldsStill(GameTestHelper helper) {
		Creeper creeper = creeper(helper, MIDDLE, true);
		ServerPlayer defuser = player(helper, NEXT_TO_MIDDLE);
		ServerPlayer other = player(helper, MIDDLE.add(0.0, 0.0, -1.0));

		rightClick(defuser, creeper);
		DefuseSession started = session(creeper);
		helper.assertTrue(started != null && started.player().equals(defuser.getUUID()), "A right-click should start a defuse session");

		rightClick(other, creeper);
		helper.assertTrue(started.equals(session(creeper)), "A second player must not take over the defuse");
		// A laggy double click from the same player must not restart (or walk away from) the defuse.
		rightClick(defuser, creeper);
		helper.assertTrue(started.equals(session(creeper)), "A repeated click must not change the running defuse");

		// Its AI is on, but it stays put. Like the panel opening, give the hold-still goal a moment to kick in first.
		Vec3[] heldAt = new Vec3[1];
		helper.startSequence()
				.thenIdle(5)
				.thenExecute(() -> {
					heldAt[0] = creeper.position();
					creeper.getNavigation().moveTo(creeper.getX() - 6.0, creeper.getY(), creeper.getZ(), 1.0);
				})
				.thenIdle(40)
				.thenExecute(() -> {
					double moved = creeper.position().distanceTo(heldAt[0]);
					helper.assertTrue(moved < 0.05, "The creeper must not move while being defused, but it moved " + moved + " blocks");
					helper.assertTrue(started.equals(session(creeper)), "Defusing should still be going on");
					creeper.discard();
					leave(helper, defuser, other);
				})
				.thenSucceed();
	}

	@GameTest(template = ARENA)
	public static void defuserWhoLeavesTheGameLetsTheCreeperGo(GameTestHelper helper) {
		// A defuser who isn't online at all, one who leaves the game mid-defuse, and a fake player (a machine using
		// an empty hand), which is never in the game and has no client to show the panel on.
		Creeper abandoned = creeper(helper, MIDDLE, false);
		abandoned.setData(NoCreeperExplodes.DEFUSE_SESSION, DefuseSession.start(UUID.randomUUID(), helper.getLevel().getGameTime()));
		Creeper leftBehind = creeper(helper, MIDDLE.add(4.0, 0.0, 0.0), false);
		ServerPlayer leaver = player(helper, MIDDLE.add(4.0, 0.0, 1.0));
		rightClick(leaver, leftBehind);
		helper.assertTrue(leftBehind.hasData(NoCreeperExplodes.DEFUSE_SESSION), "A right-click should start a defuse session");
		leave(helper, leaver);
		Creeper machineUsed = creeper(helper, MIDDLE.add(-4.0, 0.0, 0.0), false);
		FakePlayer machine = FakePlayerFactory.getMinecraft(helper.getLevel());
		moveTo(helper, machine, MIDDLE.add(-4.0, 0.0, 1.0));
		rightClick(machine, machineUsed);
		helper.assertTrue(machineUsed.hasData(NoCreeperExplodes.DEFUSE_SESSION), "A fake player's right-click should start a defuse session too");

		helper.startSequence()
				.thenIdle(1)
				.thenExecute(() -> {
					for (Creeper creeper : new Creeper[] {abandoned, leftBehind, machineUsed}) {
						helper.assertFalse(creeper.hasData(NoCreeperExplodes.DEFUSE_SESSION), "A defuser who left the game should release the creeper the next tick");
						helper.assertTrue(creeper.isAlive() && !NoCreeperExplodes.isHeld(creeper), "A released creeper is an ordinary creeper again");
					}
				})
				.thenSucceed();
	}

	@GameTest(template = ARENA)
	public static void defuserWhoDiesLetsTheCreeperGo(GameTestHelper helper) {
		// The panel closing as its player dies lets go of one creeper; the bomb timer notices for the other.
		Creeper panelClosed = creeper(helper, MIDDLE, false);
		Creeper timerNoticed = creeper(helper, MIDDLE.add(0.0, 0.0, 2.0), false);
		ServerPlayer player = player(helper, NEXT_TO_MIDDLE);
		rightClick(player, panelClosed);
		rightClick(player, timerNoticed);
		helper.assertTrue(panelClosed.hasData(NoCreeperExplodes.DEFUSE_SESSION) && timerNoticed.hasData(NoCreeperExplodes.DEFUSE_SESSION),
				"Right-clicks should start defusing both creepers");

		player.kill();
		helper.assertFalse(player.isAlive(), "The player should be dead");
		NoCreeperExplodes.cutWire(player, panelClosed, CutWirePayload.CANCEL);
		helper.assertFalse(panelClosed.hasData(NoCreeperExplodes.DEFUSE_SESSION), "A dead player's panel closing should release the creeper");

		helper.startSequence()
				.thenIdle(1)
				.thenExecute(() -> {
					for (Creeper creeper : new Creeper[] {panelClosed, timerNoticed}) {
						helper.assertFalse(creeper.hasData(NoCreeperExplodes.DEFUSE_SESSION), "Dying should release the creeper, not walk away from it");
						helper.assertTrue(creeper.isAlive() && !NoCreeperExplodes.isHeld(creeper), "Dying should leave an ordinary creeper");
					}

					leave(helper, player);
				})
				.thenSucceed();
	}

	@GameTest(template = ARENA, timeoutTicks = 200)
	public static void safeCutDefusesForGood(GameTestHelper helper) {
		ServerPlayer player = player(helper, MIDDLE);
		Creeper[] creepers = {
				creeper(helper, MIDDLE.add(-1.0, 0.0, 0.0), false),
				creeper(helper, MIDDLE.add(1.0, 0.0, 0.0), false),
				creeper(helper, MIDDLE.add(0.0, 0.0, 1.0), false),
		};

		// With a 100% chance any wire defuses it.
		NoCreeperExplodes.setConfig(ALWAYS_SAFE);

		try {
			for (int wire = 0; wire < NoCreeperExplodes.WIRE_COUNT; wire++) {
				Creeper creeper = creepers[wire];
				rightClick(player, creeper);
				NoCreeperExplodes.cutWire(player, creeper, wire);
				helper.assertTrue(creeper.isAlive() && NoCreeperExplodes.isDefused(creeper), "Cutting wire " + wire + " at a 100% chance should defuse the creeper");
				helper.assertFalse(creeper.hasData(NoCreeperExplodes.DEFUSE_SESSION), "A defused creeper has no bomb timer running");
			}
		} finally {
			NoCreeperExplodes.setConfig(DEFAULTS);
		}

		// A defused creeper can't be lit any more, by flint and steel or otherwise.
		Creeper lucky = creepers[1];
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
		helper.assertValueEqual(rightClick(player, lucky), InteractionResult.FAIL, "the result of using flint and steel on a defused creeper");
		helper.assertFalse(lucky.isIgnited(), "Flint and steel should not ignite a defused creeper");
		player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		lucky.ignite();
		lucky.setSwellDir(1);

		helper.startSequence()
				.thenIdle(60)
				.thenExecute(() -> {
					helper.assertTrue(lucky.isAlive(), "A defused creeper must never explode");
					helper.assertTrue(!lucky.isIgnited() && lucky.getSwellDir() < 0 && lucky.getSwelling(1.0F) <= 0.0F, "A defused creeper must not swell");

					// ...and it can't be defused a second time.
					rightClick(player, lucky);
					helper.assertFalse(lucky.hasData(NoCreeperExplodes.DEFUSE_SESSION), "A defused creeper must not start a new defuse");
					leave(helper, player);
				})
				.thenSucceed();
	}

	@GameTest(template = ARENA)
	public static void defusedSurvivesSaveAndReload(GameTestHelper helper) {
		Creeper defused = creeper(helper, MIDDLE, false);
		Creeper defusing = creeper(helper, MIDDLE.add(0.0, 0.0, 2.0), false);
		ServerPlayer player = player(helper, NEXT_TO_MIDDLE);

		NoCreeperExplodes.setConfig(ALWAYS_SAFE);

		try {
			rightClick(player, defused);
			NoCreeperExplodes.cutWire(player, defused, 0);
		} finally {
			NoCreeperExplodes.setConfig(DEFAULTS);
		}

		helper.assertTrue(NoCreeperExplodes.isDefused(defused), "A safe cut should defuse the creeper");
		rightClick(player, defusing);
		helper.assertTrue(defusing.hasData(NoCreeperExplodes.DEFUSE_SESSION), "A right-click should start a defuse session");

		// Saved the way a chunk saves its entities, and loaded back into new creepers.
		Creeper reloaded = saveAndLoad(helper, defused);
		helper.assertTrue(NoCreeperExplodes.isDefused(reloaded), "Defused should survive a save and reload");
		// The bomb timer is not saved: a creeper unloaded mid-defuse comes back as an ordinary creeper.
		Creeper reloadedMidDefuse = saveAndLoad(helper, defusing);
		helper.assertFalse(reloadedMidDefuse.hasData(NoCreeperExplodes.DEFUSE_SESSION) || NoCreeperExplodes.isDefused(reloadedMidDefuse),
				"A creeper saved mid-defuse should load as an ordinary creeper");

		defusing.discard();
		leave(helper, player);
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "wrong_wire")
	public static void wrongWireBlowsAFiveTimesBiggerCrater(GameTestHelper helper) {
		Creeper creeper = creeper(helper, MIDDLE, false);
		ServerPlayer player = player(helper, NEXT_TO_MIDDLE);
		rightClick(player, creeper);

		// With a 0% chance any wire blows it up, 5x bigger than normal.
		NoCreeperExplodes.setConfig(NEVER_SAFE);

		try {
			NoCreeperExplodes.cutWire(player, creeper, 0);
		} finally {
			NoCreeperExplodes.setConfig(DEFAULTS);
		}

		helper.assertTrue(creeper.isRemoved(), "A wrong wire should blow the creeper up");
		assertCrater(helper, true);
		leave(helper, player);
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "out_of_time")
	public static void runningOutOfTimeBlowsAFiveTimesBiggerCrater(GameTestHelper helper) {
		Creeper creeper = creeper(helper, MIDDLE, false);
		ServerPlayer player = player(helper, NEXT_TO_MIDDLE);
		rightClick(player, creeper);

		// Fast-forward: one second left on the clock.
		long now = helper.getLevel().getGameTime();
		DefuseSession session = session(creeper);
		helper.assertTrue(session != null, "A right-click should start a defuse session");
		creeper.setData(NoCreeperExplodes.DEFUSE_SESSION, new DefuseSession(session.player(), now + 20, false));

		goesOffWithin(helper, helper.startSequence(), creeper, now, 20, player);
	}

	@GameTest(template = ARENA, batch = "walk_away_esc", timeoutTicks = 200)
	public static void walkingAwayWithEscSetsTheBombOff(GameTestHelper helper) {
		Creeper creeper = creeper(helper, MIDDLE, false);
		ServerPlayer player = player(helper, NEXT_TO_MIDDLE);
		rightClick(player, creeper);

		// Esc closes the panel with time on the clock, which sends a cancel.
		NoCreeperExplodes.cutWire(player, creeper, CutWirePayload.CANCEL);
		long now = helper.getLevel().getGameTime();
		DefuseSession session = session(creeper);
		helper.assertTrue(session != null && session.walkedAway(), "Esc should walk away from the bomb, not defuse or release it");
		helper.assertTrue(session.expiresAt() - now <= DefuseSession.WALK_AWAY_TICKS, "Walking away should leave at most 3 seconds, got " + (session.expiresAt() - now) + " ticks");
		helper.assertTrue(creeper.isAlive(), "Walking away gives a moment to run");

		// There's no way back: right-clicking it again only says it's too late.
		rightClick(player, creeper);
		helper.assertTrue(session.equals(session(creeper)), "Right-clicking a walked-away bomb must not start defusing it again");
		// Not even for someone else once the defuser is gone: an armed bomb stays armed.
		ServerPlayer bystander = player(helper, MIDDLE.add(0.0, 0.0, -1.0));
		player.kill();
		rightClick(bystander, creeper);
		helper.assertTrue(session.equals(session(creeper)), "A bystander must not be able to restart a walked-away bomb after its defuser died");

		goesOffWithin(helper, helper.startSequence(), creeper, now, DefuseSession.WALK_AWAY_TICKS, player, bystander);
	}

	@GameTest(template = ARENA, batch = "walk_away_reach", timeoutTicks = 200)
	public static void gettingOutOfReachSetsTheBombOff(GameTestHelper helper) {
		Creeper creeper = creeper(helper, MIDDLE, false);
		ServerPlayer player = player(helper, NEXT_TO_MIDDLE);
		rightClick(player, creeper);

		// Pushed (or stepping) more than half a block past the defuse distance.
		moveTo(helper, player, MIDDLE.add(0.0, 0.0, 4.0));
		long now = helper.getLevel().getGameTime();

		GameTestSequence sequence = helper.startSequence()
				.thenIdle(1)
				.thenExecute(() -> {
					DefuseSession session = session(creeper);
					helper.assertTrue(session != null && session.walkedAway(), "Getting out of reach should walk away from the bomb");
					helper.assertTrue(creeper.isAlive(), "Walking away gives a moment to run");
				});
		goesOffWithin(helper, sequence, creeper, now, 1 + DefuseSession.WALK_AWAY_TICKS, player);
	}

	@GameTest(template = ARENA, batch = "normal_blast")
	public static void blastSizeOfOneIsANormalCreeperBlast(GameTestHelper helper) {
		Creeper creeper = creeper(helper, MIDDLE, false);
		ServerPlayer player = player(helper, NEXT_TO_MIDDLE);
		rightClick(player, creeper);

		NoCreeperExplodes.setConfig(new NoCreeperExplodesConfig(DEFAULTS.defuseDistance(), 0.0, 1.0));

		try {
			NoCreeperExplodes.cutWire(player, creeper, 0);
		} finally {
			NoCreeperExplodes.setConfig(DEFAULTS);
		}

		helper.assertTrue(creeper.isRemoved(), "A wrong wire should blow the creeper up");
		assertCrater(helper, false);
		leave(helper, player);
		helper.succeed();
	}

	/**
	 * Waits for the creeper to blow up, at most {@code ticks} (and a little slack) after {@code since},
	 * checks for a 5x crater, and ends the test.
	 */
	private static void goesOffWithin(GameTestHelper helper, GameTestSequence sequence, Creeper creeper, long since, int ticks, ServerPlayer... players) {
		sequence
				.thenWaitUntil(() -> helper.assertTrue(creeper.isRemoved(), "Waiting for the creeper to blow up"))
				.thenExecute(() -> {
					long took = helper.getLevel().getGameTime() - since;
					helper.assertTrue(took <= ticks + MARGIN_TICKS, "Expected it to go off within " + ticks + " ticks, but it took " + took);
					assertCrater(helper, true);
					leave(helper, players);
				})
				.thenSucceed();
	}

	/**
	 * The blast always takes out the ground under the creeper. A normal creeper blast can't reach ground 6 blocks
	 * away; a 5x one tears straight through it.
	 */
	private static void assertCrater(GameTestHelper helper, boolean big) {
		BlockPos ground = BlockPos.containing(MIDDLE).below();
		helper.assertTrue(helper.getBlockState(ground).isAir(), "Expected the blast to take out the ground under the creeper");
		int destroyed = 0;

		for (BlockPos pos : new BlockPos[] {ground.east(6), ground.west(6), ground.north(6), ground.south(6)}) {
			if (helper.getBlockState(pos).isAir()) {
				destroyed++;
			}
		}

		if (big) {
			helper.assertTrue(destroyed == 4, "Expected a 5x crater reaching 6 blocks out, but only " + destroyed + "/4 spots were blown away");
		} else {
			helper.assertTrue(destroyed == 0, "Expected a normal-sized crater, but " + destroyed + "/4 spots 6 blocks out were blown away");
		}
	}

	private static Creeper creeper(GameTestHelper helper, Vec3 at, boolean ai) {
		Creeper creeper = helper.spawn(EntityType.CREEPER, at);
		creeper.setNoAi(!ai);
		return creeper;
	}

	/** A player in the game, as far as the server can tell, standing at {@code at}. */
	@SuppressWarnings("removal")
	private static ServerPlayer player(GameTestHelper helper, Vec3 at) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		moveTo(helper, player, at);
		return player;
	}

	private static void moveTo(GameTestHelper helper, ServerPlayer player, Vec3 at) {
		Vec3 pos = helper.absoluteVec(at);
		player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
	}

	/** Takes mock players out of the game again. */
	private static void leave(GameTestHelper helper, ServerPlayer... players) {
		for (ServerPlayer player : players) {
			helper.getLevel().getServer().getPlayerList().remove(player);
		}
	}

	/** An empty-handed (or not) right-click on the creeper, the way the server handles one from a client. */
	private static InteractionResult rightClick(ServerPlayer player, Creeper creeper) {
		return player.interactOn(creeper, InteractionHand.MAIN_HAND);
	}

	private static @Nullable DefuseSession session(Creeper creeper) {
		return creeper.getExistingDataOrNull(NoCreeperExplodes.DEFUSE_SESSION);
	}

	private static Creeper saveAndLoad(GameTestHelper helper, Creeper creeper) {
		CompoundTag saved = new CompoundTag();
		helper.assertTrue(creeper.save(saved), "The creeper should save");
		Entity loaded = EntityType.create(saved, helper.getLevel()).orElse(null);

		if (!(loaded instanceof Creeper reloaded)) {
			throw new GameTestAssertException("Expected a creeper to load back, got " + loaded);
		}

		return reloaded;
	}
}
