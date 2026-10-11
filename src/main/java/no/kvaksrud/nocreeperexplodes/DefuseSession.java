package no.kvaksrud.nocreeperexplodes;

import java.util.UUID;

/** A wire-cutting attempt in progress: who is cutting, which wire is safe, and when the timer runs out. */
public record DefuseSession(UUID player, int correctWire, long expiresAt) {
	public boolean isActive(long gameTime) {
		return gameTime < this.expiresAt;
	}
}
