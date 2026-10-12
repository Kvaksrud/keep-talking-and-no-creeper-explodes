package no.kvaksrud.nocreeperexplodes;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Settings from config/nocreeperexplodes.properties, which only the server reads. A missing file is written with
 * the defaults and missing keys are added to it. A value that isn't a number falls back to its default, and one
 * out of range is clamped.
 *
 * @param defuseDistance          blocks of empty space allowed between player and creeper to start cutting wires
 * @param successChancePercent    chance that the wire the player cuts is a safe one
 * @param explosionSizeMultiplier how many times bigger than a normal creeper's blast a failed defuse is
 */
public record NoCreeperExplodesConfig(double defuseDistance, double successChancePercent, double explosionSizeMultiplier) {
	private static final Logger LOGGER = LoggerFactory.getLogger("nocreeperexplodes");

	public static final String FILE_NAME = "nocreeperexplodes.properties";
	/** One safe wire out of three, a 1-block reach, and a blast 5 times a normal creeper's. */
	public static final NoCreeperExplodesConfig DEFAULTS = new NoCreeperExplodesConfig(1.0, 33.33, 5.0);

	public static final double MAX_DEFUSE_DISTANCE = 16.0;
	public static final double MAX_EXPLOSION_SIZE_MULTIPLIER = 20.0;

	static final String DEFUSE_DISTANCE = "defuse_distance";
	static final String SUCCESS_CHANCE_PERCENT = "defuse_success_chance_percent";
	static final String EXPLOSION_SIZE_MULTIPLIER = "explosion_size_multiplier";
	private static final List<String> KEYS = List.of(DEFUSE_DISTANCE, SUCCESS_CHANCE_PERCENT, EXPLOSION_SIZE_MULTIPLIER);

	/** Reads the file, writing it first if it is missing or lacks a setting. Never fails: the defaults stand in. */
	public static NoCreeperExplodesConfig load(Path file) {
		Properties properties = new Properties();

		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				properties.load(reader);
			} catch (IOException | IllegalArgumentException e) {
				LOGGER.warn("Could not read {}, using the default settings: {}", file, e.toString());
				return DEFAULTS;
			}
		}

		NoCreeperExplodesConfig config = new NoCreeperExplodesConfig(
				read(properties, file, DEFUSE_DISTANCE, DEFAULTS.defuseDistance, 0.0, MAX_DEFUSE_DISTANCE),
				read(properties, file, SUCCESS_CHANCE_PERCENT, DEFAULTS.successChancePercent, 0.0, 100.0),
				read(properties, file, EXPLOSION_SIZE_MULTIPLIER, DEFAULTS.explosionSizeMultiplier, 0.0, MAX_EXPLOSION_SIZE_MULTIPLIER));

		if (!properties.stringPropertyNames().containsAll(KEYS)) {
			config.save(file);
		}

		return config;
	}

	private static double read(Properties properties, Path file, String key, double fallback, double min, double max) {
		String text = properties.getProperty(key);

		if (text == null) {
			return fallback;
		}

		double value;

		try {
			value = Double.parseDouble(text.trim());
		} catch (NumberFormatException e) {
			value = Double.NaN;
		}

		if (!Double.isFinite(value)) {
			LOGGER.warn("{} in {} is not a number: \"{}\". Using {}.", key, file, text, formatNumber(fallback));
			return fallback;
		}

		double clamped = Math.clamp(value, min, max);

		if (clamped != value) {
			LOGGER.warn("{} in {} must be from {} to {}, so {} counts as {}.", key, file, formatNumber(min), formatNumber(max), text.trim(), formatNumber(clamped));
		}

		return clamped;
	}

	/** Writes every setting with an explanation. A write failure is logged; the settings still apply. */
	public void save(Path file) {
		String text = """
				# Keep Talking and No Creeper Explodes settings. Only the server's copy counts (in singleplayer, this one).
				# Changes take effect the next time a world is opened or the server starts.

				# How close you must be to a creeper to start cutting its wires: blocks of empty space between you and it.
				# Moving more than half a block further away while the panel is open counts as walking away from the bomb.
				# You still have to reach the creeper with a right-click (3 blocks in survival), so large values don't help.
				# Default: %s. Allowed: 0 to %s.
				%s=%s

				# Chance, in percent, that the wire you cut is the safe one.
				# Default: %s (one safe wire out of three). Allowed: 0 to 100.
				%s=%s

				# How many times bigger than a normal creeper's blast it is when defusing fails: the wrong wire,
				# the timer running out, or walking away from the bomb. A charged creeper's blast is twice that again.
				# Default: %s (1.0 is a normal creeper's blast). Allowed: 0 to %s.
				%s=%s
				""".formatted(
				formatSetting(DEFAULTS.defuseDistance), formatNumber(MAX_DEFUSE_DISTANCE), DEFUSE_DISTANCE, formatSetting(this.defuseDistance),
				formatSetting(DEFAULTS.successChancePercent), SUCCESS_CHANCE_PERCENT, formatSetting(this.successChancePercent),
				formatSetting(DEFAULTS.explosionSizeMultiplier), formatNumber(MAX_EXPLOSION_SIZE_MULTIPLIER), EXPLOSION_SIZE_MULTIPLIER, formatSetting(this.explosionSizeMultiplier));

		try {
			Path parent = file.toAbsolutePath().getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			Files.writeString(file, text, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("Could not write {}: {}", file, e.toString());
		}
	}

	/** Whether a cut is safe, given a uniform roll in [0, 1): 0% is never safe, 100% always. */
	public boolean isSafeCut(double roll) {
		return roll * 100.0 < this.successChancePercent;
	}

	/** Shortest plain form: 1, 1.5, 33.33. */
	public static String formatNumber(double value) {
		return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
	}

	/** Like {@link #formatNumber}, but always with a decimal point, so the file shows that fractions are allowed. */
	private static String formatSetting(double value) {
		BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
		return (decimal.scale() < 1 ? decimal.setScale(1) : decimal).toPlainString();
	}
}
