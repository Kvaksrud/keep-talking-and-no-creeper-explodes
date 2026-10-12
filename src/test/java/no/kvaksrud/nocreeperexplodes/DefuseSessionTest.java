package no.kvaksrud.nocreeperexplodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class DefuseSessionTest {
	private static final UUID PLAYER = UUID.randomUUID();

	@Test
	void timerRunsFifteenSeconds() {
		DefuseSession session = DefuseSession.start(PLAYER, 1000);

		assertEquals(PLAYER, session.player());
		assertFalse(session.walkedAway());
		assertTrue(session.isActive(1000 + 15 * 20 - 1));
		assertFalse(session.isActive(1000 + 15 * 20));
	}

	@Test
	void walkingAwayGoesOffWithinThreeSeconds() {
		DefuseSession walkedAway = DefuseSession.start(PLAYER, 1000).walkAway(1100);

		assertTrue(walkedAway.walkedAway());
		assertEquals(PLAYER, walkedAway.player());
		assertEquals(1100 + 3 * 20, walkedAway.expiresAt());
		assertTrue(walkedAway.isActive(1100 + 3 * 20 - 1));
		assertFalse(walkedAway.isActive(1100 + 3 * 20));
	}

	@Test
	void walkingAwayNeverBuysExtraTime() {
		DefuseSession lastSecond = DefuseSession.start(PLAYER, 1000).walkAway(1000 + 15 * 20 - 20);
		assertEquals(1000 + 15 * 20, lastSecond.expiresAt(), "with one second left it still goes off on time");
	}

	@Test
	void beepsSpeedUpAndGoFranticOnceWalkedAway() {
		DefuseSession session = DefuseSession.start(PLAYER, 0);

		assertEquals(20, session.beepInterval(0), "once a second at the start");
		assertEquals(3, session.beepInterval(15 * 20 - 1), "fastest near zero");

		for (long t = 1; t < 15 * 20; t++) {
			assertTrue(session.beepInterval(t) <= session.beepInterval(t - 1), "never slows down, tick " + t);
		}

		DefuseSession walkedAway = session.walkAway(20);

		for (long t = 20; t < walkedAway.expiresAt(); t++) {
			assertEquals(DefuseSession.WALK_AWAY_BEEP_INTERVAL, walkedAway.beepInterval(t));
			assertTrue(walkedAway.beepInterval(t) < session.beepInterval(15 * 20 - 1), "faster than the timer ever gets");
		}
	}
}
