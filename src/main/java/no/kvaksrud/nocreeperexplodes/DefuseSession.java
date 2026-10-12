package no.kvaksrud.nocreeperexplodes;

import java.util.UUID;

/**
 * A bomb timer running on a creeper: who is cutting its wires, when it goes off, and whether they walked away
 * from it (then it beeps frantically and blows up within {@link #WALK_AWAY_TICKS}, whoever is around).
 */
public record DefuseSession(UUID player, long expiresAt, boolean walkedAway) {
	/** How long the bomb timer runs: the creeper holds its fuse while someone is cutting wires. */
	public static final int SESSION_TICKS = 15 * 20;
	/** Walk away from the bomb (Esc, or out of reach) and it goes off this soon. */
	public static final int WALK_AWAY_TICKS = 3 * 20;
	/** Ticks between beeps once walked away: faster than even the last second of the timer. */
	public static final int WALK_AWAY_BEEP_INTERVAL = 2;

	public static DefuseSession start(UUID player, long gameTime) {
		return new DefuseSession(player, gameTime + SESSION_TICKS, false);
	}

	public boolean isActive(long gameTime) {
		return gameTime < this.expiresAt;
	}

	/** Left with time on the clock: goes off {@link #WALK_AWAY_TICKS} from now, or sooner if the timer runs out first. */
	public DefuseSession walkAway(long gameTime) {
		return new DefuseSession(this.player, Math.min(this.expiresAt, gameTime + WALK_AWAY_TICKS), true);
	}

	/** Ticks until the next beep. */
	public int beepInterval(long gameTime) {
		return this.walkedAway ? WALK_AWAY_BEEP_INTERVAL : timerBeepInterval(this.expiresAt - gameTime);
	}

	/** Once a second at the start of the timer, speeding up to a frantic beep near zero. */
	public static int timerBeepInterval(long ticksLeft) {
		return 3 + (int) (17 * Math.max(0, ticksLeft) / SESSION_TICKS);
	}
}
