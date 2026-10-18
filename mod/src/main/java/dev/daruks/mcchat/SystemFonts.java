package dev.daruks.mcchat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;

/**
 * Fonts installed on the OS (e.g. C:\Windows\Fonts). Vanilla only loads TTF
 * fonts from resource packs, so the chosen file is copied into a small pack
 * the mod writes to resourcepacks/ and enables, as font {@link #FONT_ID}.
 */
public final class SystemFonts {
	public static final String FONT_ID = "mcchat:system";
	private static final String PACK_DIR = "mcchat-system-font";
	private static final String PACK_ID = "file/" + PACK_DIR;
	private static final List<String> EXTENSIONS = List.of(".ttf", ".ttc", ".otf", ".otc");

	private static Map<String, Path> cache;

	private SystemFonts() {}

	/** Installed font files by lowercase file name without extension. */
	public static synchronized Map<String, Path> list() {
		if (cache != null) return cache;
		Map<String, Path> out = new TreeMap<>();
		for (Path dir : fontDirs()) {
			if (!Files.isDirectory(dir)) continue;
			try (Stream<Path> files = Files.walk(dir, 3)) {
				files.filter(Files::isRegularFile).forEach(p -> {
					String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
					for (String ext : EXTENSIONS) {
						if (name.endsWith(ext)) {
							out.putIfAbsent(name.substring(0, name.length() - ext.length()), p);
						}
					}
				});
			} catch (IOException | RuntimeException e) {
				McChatClient.LOG.warn("mcchat: could not list fonts in {}", dir, e);
			}
		}
		cache = out;
		return out;
	}

	private static List<Path> fontDirs() {
		List<Path> dirs = new ArrayList<>();
		String home = System.getProperty("user.home");
		String windir = System.getenv("WINDIR");
		String local = System.getenv("LOCALAPPDATA");
		if (windir != null) dirs.add(Path.of(windir, "Fonts"));
		if (local != null) dirs.add(Path.of(local, "Microsoft", "Windows", "Fonts"));
		dirs.add(Path.of("/Library/Fonts"));
		dirs.add(Path.of("/System/Library/Fonts"));
		dirs.add(Path.of("/usr/share/fonts"));
		dirs.add(Path.of("/usr/local/share/fonts"));
		if (home != null) {
			dirs.add(Path.of(home, "Library", "Fonts"));
			dirs.add(Path.of(home, ".local", "share", "fonts"));
			dirs.add(Path.of(home, ".fonts"));
		}
		return dirs;
	}

	/** Finds a font by file name (extension optional, case-insensitive), or a unique prefix of one. */
	public static Path find(String name) {
		Map<String, Path> all = list();
		String key = name.toLowerCase(Locale.ROOT);
		for (String ext : EXTENSIONS) {
			if (key.endsWith(ext)) key = key.substring(0, key.length() - ext.length());
		}
		if (all.containsKey(key)) return all.get(key);
		Path hit = null;
		for (Map.Entry<String, Path> e : all.entrySet()) {
			if (e.getKey().startsWith(key)) {
				if (hit != null) return null;
				hit = e.getValue();
			}
		}
		return hit;
	}

	/**
	 * Writes the font into the mod's resource pack, enables the pack and
	 * reloads resources. Completes when the font can be used.
	 */
	public static CompletableFuture<Void> install(Path font, float size, float oversample) throws IOException {
		Minecraft mc = Minecraft.getInstance();
		Path pack = mc.getResourcePackDirectory().resolve(PACK_DIR);
		Path fontDir = pack.resolve("assets/mcchat/font");
		Files.createDirectories(fontDir);

		int format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
		JsonObject meta = new JsonObject();
		JsonObject p = new JsonObject();
		p.addProperty("description", "MCChat: " + font.getFileName());
		p.addProperty("pack_format", format);
		p.addProperty("min_format", format);
		p.addProperty("max_format", format);
		meta.add("pack", p);
		Files.writeString(pack.resolve("pack.mcmeta"), meta.toString(), StandardCharsets.UTF_8);

		// FreeType reads .ttc/.otf too (the first face of a collection), whatever the name.
		Files.copy(font, fontDir.resolve("system.ttf"), StandardCopyOption.REPLACE_EXISTING);

		JsonObject ttf = new JsonObject();
		ttf.addProperty("type", "ttf");
		ttf.addProperty("file", "mcchat:system.ttf");
		ttf.addProperty("size", size);
		ttf.addProperty("oversample", oversample);
		JsonArray shift = new JsonArray();
		shift.add(0);
		shift.add(0);
		ttf.add("shift", shift);
		// Glyphs the font lacks fall back to the vanilla font.
		JsonObject fallback = new JsonObject();
		fallback.addProperty("type", "reference");
		fallback.addProperty("id", "minecraft:default");
		JsonArray providers = new JsonArray();
		providers.add(ttf);
		providers.add(fallback);
		JsonObject def = new JsonObject();
		def.add("providers", providers);
		Files.writeString(fontDir.resolve("system.json"), def.toString(), StandardCharsets.UTF_8);

		PackRepository repo = mc.getResourcePackRepository();
		repo.reload();
		if (!repo.getSelectedIds().contains(PACK_ID) && repo.addPack(PACK_ID)) {
			// Saves options and reloads, since the selection changed.
			mc.options.updateResourcePacks(repo);
			return mc.reloadResourcePacks().thenApply(v -> null);
		}
		return mc.reloadResourcePacks();
	}
}
