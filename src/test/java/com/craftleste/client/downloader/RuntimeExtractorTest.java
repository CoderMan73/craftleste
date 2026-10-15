package com.craftleste.client.downloader;

import com.github.luben.zstd.ZstdOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip probe for the runtime extraction: an archive is built here with the same two
 * libraries the downloader reads it with, so the pipeline (zstd stream -> tar -> directory, with
 * the traversal guard) is verified without a network fetch (PLAN.md M2 Probe 2, the
 * standalone-probe pattern).
 */
class RuntimeExtractorTest {

	@Test
	void extractsNestedFilesAndDirectories(@TempDir Path work) throws IOException {
		Path archive = work.resolve("fixture.tar.zst");
		writeArchive(archive, entry -> {
			entry.file("index.html", "<html>build</html>");
			entry.file("_framework/dotnet.native.test.wasm0", new byte[]{0x00, 0x61, 0x73, 0x6d, 0x7f});
		});

		Path target = work.resolve("out");
		int entries = RuntimeExtractor.extractTarZst(archive, target);

		assertTrue(Files.isRegularFile(target.resolve("index.html")));
		assertArrayEquals(new byte[]{0x00, 0x61, 0x73, 0x6d, 0x7f},
				Files.readAllBytes(target.resolve("_framework/dotnet.native.test.wasm0")));
		assertTrue(Files.isDirectory(target.resolve("_framework")));
		assertEquals(2, entries);
	}

	@Test
	void stripsTheArchiversLeadingDotSlash(@TempDir Path work) throws IOException {
		Path archive = work.resolve("fixture.tar.zst");
		writeArchive(archive, entry -> {
			entry.file("./index.html", "<html>build</html>");
			entry.file("./Content/Dialog/prologue.txt", "chapter one");
		});

		Path target = work.resolve("out");
		RuntimeExtractor.extractTarZst(archive, target);

		assertTrue(Files.isRegularFile(target.resolve("index.html")));
		assertTrue(Files.isRegularFile(target.resolve("Content/Dialog/prologue.txt")));
		assertFalse(Files.exists(work.resolve("index.html")));
	}

	@Test
	void refusesAnEntryThatEscapesTheTarget(@TempDir Path work) throws IOException {
		Path archive = work.resolve("fixture.tar.zst");
		writeArchive(archive, entry -> entry.directory("../escape.txt"));

		assertThrows(ZipException.class, () -> RuntimeExtractor.extractTarZst(archive, work.resolve("out")));
	}

	private interface ArchiveWriter {
		void writeTo(ArchiveBuilder builder) throws IOException;
	}

	private record ArchiveBuilder(TarArchiveOutputStream tar) {
		void file(String name, String content) throws IOException {
			byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
			TarArchiveEntry entry = new TarArchiveEntry(name);
			entry.setSize(bytes.length);
			tar.putArchiveEntry(entry);
			tar.write(bytes);
			tar.closeArchiveEntry();
		}

		void file(String name, byte[] content) throws IOException {
			TarArchiveEntry entry = new TarArchiveEntry(name);
			entry.setSize(content.length);
			tar.putArchiveEntry(entry);
			tar.write(content);
			tar.closeArchiveEntry();
		}

		void directory(String name) throws IOException {
			TarArchiveEntry entry = new TarArchiveEntry(name + "/");
			entry.setSize(0);
			tar.putArchiveEntry(entry);
			tar.closeArchiveEntry();
		}
	}

	private void writeArchive(Path archive, ArchiveWriter writer) throws IOException {
		try (OutputStream file = Files.newOutputStream(archive);
				OutputStream buffered = new BufferedOutputStream(file);
				ZstdOutputStream zstd = new ZstdOutputStream(buffered);
				TarArchiveOutputStream tar = new TarArchiveOutputStream(zstd)) {
			writer.writeTo(new ArchiveBuilder(tar));
			tar.finish();
		}
	}
}
