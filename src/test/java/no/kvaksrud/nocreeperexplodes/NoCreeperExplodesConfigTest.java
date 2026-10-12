package no.kvaksrud.nocreeperexplodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NoCreeperExplodesConfigTest {
	@TempDir
	Path dir;

	private Path file() {
		return this.dir.resolve("config").resolve(NoCreeperExplodesConfig.FILE_NAME);
	}

	private void write(String text) throws IOException {
		Files.createDirectories(this.file().getParent());
		Files.writeString(this.file(), text, StandardCharsets.UTF_8);
	}

	@Test
	void defaultsKeepTheOriginalGame() {
		NoCreeperExplodesConfig defaults = NoCreeperExplodesConfig.DEFAULTS;
		assertEquals(1.0, defaults.defuseDistance());
		assertEquals(33.33, defaults.successChancePercent());
		assertEquals(5.0, defaults.explosionSizeMultiplier());
	}

	@Test
	void missingFileIsWrittenWithTheDefaults() throws IOException {
		assertEquals(NoCreeperExplodesConfig.DEFAULTS, NoCreeperExplodesConfig.load(this.file()));

		String text = Files.readString(this.file());
		assertTrue(text.contains("\ndefuse_distance=1.0\n"), text);
		assertTrue(text.contains("\ndefuse_success_chance_percent=33.33\n"), text);
		assertTrue(text.contains("\nexplosion_size_multiplier=5.0\n"), text);
		assertEquals(NoCreeperExplodesConfig.DEFAULTS, NoCreeperExplodesConfig.load(this.file()), "the written file reads back as the defaults");
	}

	@Test
	void readsEverySetting() throws IOException {
		this.write("""
				# hand-edited
				defuse_distance = 2.5
				defuse_success_chance_percent=75
				explosion_size_multiplier=1.5
				""");

		assertEquals(new NoCreeperExplodesConfig(2.5, 75.0, 1.5), NoCreeperExplodesConfig.load(this.file()));
		assertTrue(Files.readString(this.file()).contains("# hand-edited"), "a complete file is left alone");
	}

	@Test
	void outOfRangeValuesAreClamped() throws IOException {
		this.write("""
				defuse_distance=-3
				defuse_success_chance_percent=150
				explosion_size_multiplier=1000
				""");

		assertEquals(new NoCreeperExplodesConfig(0.0, 100.0, NoCreeperExplodesConfig.MAX_EXPLOSION_SIZE_MULTIPLIER), NoCreeperExplodesConfig.load(this.file()));
	}

	@Test
	void valuesThatAreNotNumbersFallBackToTheirDefaults() throws IOException {
		this.write("""
				defuse_distance=far
				defuse_success_chance_percent=NaN
				explosion_size_multiplier=Infinity
				""");

		assertEquals(NoCreeperExplodesConfig.DEFAULTS, NoCreeperExplodesConfig.load(this.file()));
		assertTrue(Files.readString(this.file()).contains("defuse_distance=far"), "a typo is left for the player to fix");
	}

	@Test
	void missingSettingsAreAddedAndTheRestKept() throws IOException {
		this.write("explosion_size_multiplier=2\n");

		NoCreeperExplodesConfig config = NoCreeperExplodesConfig.load(this.file());
		assertEquals(new NoCreeperExplodesConfig(1.0, 33.33, 2.0), config);

		String text = Files.readString(this.file());
		assertTrue(text.contains("\ndefuse_distance=1.0\n"), text);
		assertTrue(text.contains("\nexplosion_size_multiplier=2.0\n"), text);
		assertEquals(config, NoCreeperExplodesConfig.load(this.file()));
	}

	@Test
	void unreadableFileMeansDefaults() throws IOException {
		Files.createDirectories(this.file());
		assertEquals(NoCreeperExplodesConfig.DEFAULTS, NoCreeperExplodesConfig.load(this.file()));
	}

	@Test
	void successChanceDecidesTheCut() {
		NoCreeperExplodesConfig never = new NoCreeperExplodesConfig(1.0, 0.0, 5.0);
		NoCreeperExplodesConfig always = new NoCreeperExplodesConfig(1.0, 100.0, 5.0);

		for (double roll : new double[] {0.0, 0.25, 0.5, 0.9999999}) {
			assertFalse(never.isSafeCut(roll), "0% must never be safe, roll " + roll);
			assertTrue(always.isSafeCut(roll), "100% must always be safe, roll " + roll);
		}

		int safe = 0;

		for (int i = 0; i < 10_000; i++) {
			if (NoCreeperExplodesConfig.DEFAULTS.isSafeCut((i + 0.5) / 10_000.0)) {
				safe++;
			}
		}

		assertEquals(3_333, safe, "the default is one cut in three");
	}

	@Test
	void numbersAreWrittenPlainly() {
		assertEquals("1", NoCreeperExplodesConfig.formatNumber(1.0));
		assertEquals("1.5", NoCreeperExplodesConfig.formatNumber(1.5));
		assertEquals("33.33", NoCreeperExplodesConfig.formatNumber(33.33));
	}
}
