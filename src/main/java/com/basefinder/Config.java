package com.basefinder;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class Config {
	private Config() {}

	/** Starts OFF every launch; turn on with /esp on. */
	public static boolean enabled = false;
	/** Containers + armor stands needed in ONE chunk before it is even considered. */
	public static int threshold = 8;
	/** Strict: only show bases with farm/growth activity. Relaxed: also show quiet stashes (still hides raided). */
	public static boolean strict = true;

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("basefinder.properties");
	}

	public static void load() {
		Path f = file();
		if (!Files.exists(f)) return;
		try (InputStream in = Files.newInputStream(f)) {
			Properties p = new Properties();
			p.load(in);
			threshold = Integer.parseInt(p.getProperty("threshold", "8"));
			strict = Boolean.parseBoolean(p.getProperty("strict", "true"));
		} catch (IOException | NumberFormatException ignored) {
		}
	}

	public static void save() {
		Properties p = new Properties();
		p.setProperty("threshold", Integer.toString(threshold));
		p.setProperty("strict", Boolean.toString(strict));
		try (OutputStream out = Files.newOutputStream(file())) {
			p.store(out, "Base Finder ESP");
		} catch (IOException ignored) {
		}
	}
}
