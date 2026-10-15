package com.craftleste.client.config;

import com.craftleste.client.CraftlestePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The mod's persisted settings, as a plain properties file until the Cloth Config screen lands
 * (PLAN.md M4).
 *
 * <p>Only what M2 needs is here: where the player's Celeste files came from, and which Webleste
 * commit is pinned. The M3/M4 settings (window mode, render scale, frame rate) follow the same
 * pattern.
 */
public class CraftlesteConfig {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftleste");

	private static CraftlesteConfig INSTANCE = null;

	private final Path configPath;
	private final Properties properties;

	private CraftlesteConfig() {
		this.configPath = CraftlestePaths.configFile();
		this.properties = new Properties();
		load();
	}

	public static synchronized CraftlesteConfig getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new CraftlesteConfig();
		}
		return INSTANCE;
	}

	/**
	 * The path the player gave at intake: either the Celeste.exe or its Content directory. Kept so
	 * a fresh game directory can be repopulated without asking again.
	 */
	public String getCelesteSourcePath() {
		return properties.getProperty("celesteSourcePath");
	}

	public void setCelesteSourcePath(String path) {
		properties.setProperty("celesteSourcePath", path);
		save();
	}

	/** The Webleste commit pin of the runtime that is currently fetched and served. */
	public String getWeblestePin() {
		return properties.getProperty("weblestePin");
	}

	public void setWeblestePin(String commit) {
		properties.setProperty("weblestePin", commit);
		save();
	}

	private void load() {
		if (!Files.isRegularFile(configPath)) {
			return;
		}
		try (InputStream in = Files.newInputStream(configPath)) {
			properties.load(in);
		} catch (IOException failure) {
			LOGGER.warn("Could not read {}; defaults are in use", configPath, failure);
		}
	}

	private void save() {
		try {
			Files.createDirectories(configPath.getParent());
			try (OutputStream out = Files.newOutputStream(configPath)) {
				properties.store(out, "craftleste settings");
			}
		} catch (IOException failure) {
			LOGGER.warn("Could not write {}", configPath, failure);
		}
	}
}
