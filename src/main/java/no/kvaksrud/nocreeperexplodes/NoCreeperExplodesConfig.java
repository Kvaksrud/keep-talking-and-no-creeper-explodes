package no.kvaksrud.nocreeperexplodes;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Settings from config/nocreeperexplodes.properties, which only the server reads. A missing file is written with
 * the defaults and missing keys are added to it. A value that isn't a number falls back to its default, one out of
 * range is clamped, and a defuse tool that isn't an item id or tag is skipped.
 *
 * @param defuseDistance          blocks of empty space allowed between player and creeper to start cutting wires
 * @param successChancePercent    chance that the wire the player cuts is a safe one
 * @param explosionSizeMultiplier how many times bigger than a normal creeper's blast a failed defuse is
 * @param defuseTools             what may be held, besides an empty hand, to start cutting: item ids
 *                                ({@code minecraft:shears}) and item tags ({@code #minecraft:swords})
 */
public record NoCreeperExplodesConfig(double defuseDistance, double successChancePercent, double explosionSizeMultiplier, List<String> defuseTools) {
	private static final Logger LOGGER = LoggerFactory.getLogger("nocreeperexplodes");

	public static final String FILE_NAME = "nocreeperexplodes.properties";
	/** Tools whose right-click only does something to blocks, so on a creeper it can mean "cut its wires". */
	public static final List<String> DEFAULT_DEFUSE_TOOLS = List.of("#minecraft:swords", "#minecraft:axes", "#minecraft:pickaxes", "#minecraft:shovels", "#minecraft:hoes");
	/** One safe wire out of three, a 1-block reach, a blast 5 times a normal creeper's, and an empty hand or a tool. */
	public static final NoCreeperExplodesConfig DEFAULTS = new NoCreeperExplodesConfig(1.0, 33.33, 5.0, DEFAULT_DEFUSE_TOOLS);

	public static final double MAX_DEFUSE_DISTANCE = 16.0;
	public static final double MAX_EXPLOSION_SIZE_MULTIPLIER = 20.0;

	static final String DEFUSE_DISTANCE = "defuse_distance";
	static final String SUCCESS_CHANCE_PERCENT = "defuse_success_chance_percent";
	static final String EXPLOSION_SIZE_MULTIPLIER = "explosion_size_multiplier";
	static final String DEFUSE_TOOLS = "defuse_tools";
	private static final List<String> KEYS = List.of(DEFUSE_DISTANCE, SUCCESS_CHANCE_PERCENT, EXPLOSION_SIZE_MULTIPLIER, DEFUSE_TOOLS);

	/** An item id or, with a leading #, an item tag; the namespace defaults to minecraft. */
	private static final Pattern ITEM_OR_TAG = Pattern.compile("#?([a-z0-9_.-]+:)?[a-z0-9_./-]+");

	public NoCreeperExplodesConfig {
		defuseTools = List.copyOf(defuseTools);
	}

	/** The default defuse tools, with these numbers. */
	public NoCreeperExplodesConfig(double defuseDistance, double successChancePercent, double explosionSizeMultiplier) {
		this(defuseDistance, successChancePercent, explosionSizeMultiplier, DEFAULT_DEFUSE_TOOLS);
	}

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
				read(properties, file, EXPLOSION_SIZE_MULTIPLIER, DEFAULTS.explosionSizeMultiplier, 0.0, MAX_EXPLOSION_SIZE_MULTIPLIER),
				readItems(properties, file, DEFUSE_TOOLS, DEFAULTS.defuseTools));

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

	/** A comma-separated list of item ids and tags. Empty is allowed (nothing listed); entries that don't parse are skipped. */
	private static List<String> readItems(Properties properties, Path file, String key, List<String> fallback) {
		String text = properties.getProperty(key);

		if (text == null) {
			return fallback;
		}

		List<String> items = new ArrayList<>();

		for (String part : text.split(",")) {
			String item = part.trim();

			if (item.isEmpty()) {
				continue;
			}

			if (ITEM_OR_TAG.matcher(item).matches()) {
				items.add(item);
			} else {
				LOGGER.warn("{} in {} has \"{}\", which isn't an item id (minecraft:shears) or tag (#minecraft:swords). Skipping it.", key, file, item);
			}
		}

		return items;
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

				# What you can hold in your main hand, besides nothing, when you right-click a creeper to cut its wires:
				# item ids (minecraft:shears) and item tags (#minecraft:swords), separated by commas. Leave it empty to
				# only allow an empty hand. Avoid items that do something when used on a creeper, like flint and steel.
				# Default: %s
				%s=%s
				""".formatted(
				formatSetting(DEFAULTS.defuseDistance), formatNumber(MAX_DEFUSE_DISTANCE), DEFUSE_DISTANCE, formatSetting(this.defuseDistance),
				formatSetting(DEFAULTS.successChancePercent), SUCCESS_CHANCE_PERCENT, formatSetting(this.successChancePercent),
				formatSetting(DEFAULTS.explosionSizeMultiplier), formatNumber(MAX_EXPLOSION_SIZE_MULTIPLIER), EXPLOSION_SIZE_MULTIPLIER, formatSetting(this.explosionSizeMultiplier),
				String.join(", ", DEFAULTS.defuseTools), DEFUSE_TOOLS, String.join(", ", this.defuseTools));

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
