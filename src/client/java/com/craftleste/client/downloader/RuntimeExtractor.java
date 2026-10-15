package com.craftleste.client.downloader;

import com.github.luben.zstd.ZstdInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipException;

/**
 * Extracts a zstd-compressed tar archive into a directory, refusing to write outside it.
 *
 * <p>Isolated from the downloader because it is the one piece of the runtime pipeline that can be
 * verified without a network or a browser: a standalone probe builds an archive with the same
 * libraries and round-trips it through here (PLAN.md M2 Probe 2, the standalone-probe pattern).
 */
public final class RuntimeExtractor {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftleste");

	private RuntimeExtractor() {
	}

	/**
	 * Extracts {@code archive} (a single-frame zstd stream wrapping a tar) into {@code targetDir}.
	 *
	 * @return the number of entries written
	 */
	public static int extractTarZst(Path archive, Path targetDir) throws IOException {
		Path root = targetDir.toAbsolutePath().normalize();
		Files.createDirectories(root);

		int entries = 0;
		try (InputStream fileIn = Files.newInputStream(archive);
				InputStream buffered = new BufferedInputStream(fileIn);
				InputStream zstd = new ZstdInputStream(buffered);
				TarArchiveInputStream tar = new TarArchiveInputStream(zstd)) {
			TarArchiveEntry entry;
			while ((entry = tar.getNextEntry()) != null) {
				String name = normalizeName(entry.getName());
				if (name.isEmpty()) {
					continue;
				}
				Path target = root.resolve(name).normalize();
				if (!target.startsWith(root)) {
					throw new ZipException("Refusing to extract outside the target directory: " + entry.getName());
				}
				if (entry.isDirectory()) {
					Files.createDirectories(target);
					continue;
				}
				if (!entry.isFile()) {
					// Symlinks and hard links are not used by the published build; skipping is
					// safer than recreating links this mod cannot verify.
					continue;
				}
				Files.createDirectories(target.getParent());
				try (OutputStream out = Files.newOutputStream(target)) {
					tar.transferTo(out);
				}
				entries++;
			}
		}
		return entries;
	}

	/**
	 * Strips the leading {@code ./} or {@code /} the archiver adds, so a plain relative name
	 * resolves against the extraction root.
	 */
	private static String normalizeName(String rawName) {
		String name = rawName.replace('\\', '/');
		while (name.startsWith("./")) {
			name = name.substring(2);
		}
		while (name.startsWith("/")) {
			name = name.substring(1);
		}
		if (name.endsWith("/")) {
			name = name.substring(0, name.length() - 1);
		}
		return name;
	}
}
