package com.craftleste.client.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural probe for the pages the mod ships: the script inside each text block must parse.
 *
 * <p>A boot-time syntax error in a page is invisible in a review of the Java around it, and it
 * presents to the player as a page that never advances past its first line of text - the bootstrap
 * sat on "checking storage..." forever because of one stray brace. The check walks the script the
 * way a JavaScript tokenizer does - tracking strings, template literals and comments so bracket
 * counting is not fooled by text inside them - and requires every bracket kind to balance and every
 * function call to close. It is a structural check, not a parser, but the failures this pipeline has
 * actually produced are exactly the ones it catches.
 */
class BootstrapPagesTest {

	private static final Pattern BLOCK = Pattern.compile("(\\w+)\\s*=\\s*\"\"\"([\\s\\S]*?)\"\"\";");
	private static final Pattern SCRIPT = Pattern.compile("<script[^>]*>([\\s\\S]*?)</script>");

	private static final Path PAGE_SOURCE = Path.of("src", "client", "java", "com", "craftleste",
			"client", "bootstrap", "BootstrapPages.java");

	private static Path pageSource() {
		// Tests run with the project root as the working directory; the fallback keeps the check
		// honest when something runs it from elsewhere.
		return Files.isRegularFile(PAGE_SOURCE)
				? PAGE_SOURCE
				: Path.of("").toAbsolutePath().resolve(PAGE_SOURCE);
	}

	@Test
	void everyShipsScriptIsStructurallySound() throws IOException {
		Path source = pageSource();
		assertTrue(Files.isRegularFile(source), "cannot find " + source);

		String text = Files.readString(source, StandardCharsets.UTF_8);
		Matcher blocks = BLOCK.matcher(text);
		int pages = 0;
		while (blocks.find()) {
			String name = blocks.group(1);
			if (!name.endsWith("_HTML")) {
				continue;
			}
			pages++;
			String html = unescapeJavaTextBlock(blocks.group(2));
			Matcher scripts = SCRIPT.matcher(html);
			int found = 0;
			while (scripts.find()) {
				found++;
				validateScript(name + " script #" + found, scripts.group(1));
			}
			assertTrue(found > 0, name + " contains no script");
		}
		assertTrue(pages > 0, "no page blocks found in " + source);
	}

	@Test
	void theBootstrapRunsItsOwnEntryPoint() throws IOException {
		String script = scriptOf("BOOTSTRAP_HTML");
		assertTrue(script.contains("run().catch"), "the bootstrap never starts run()");
		assertTrue(script.contains("function run()"), "the bootstrap has no run()");
		assertTrue(script.contains("removePatcherOutputs"),
				"the bootstrap no longer invalidates the patcher's output");
		assertTrue(script.contains("patchIsCurrent"),
				"the bootstrap no longer checks whether the patch is current");
	}

	@Test
	void theThreadedProbeReportsWithoutAGesture() throws IOException {
		String script = scriptOf("THREADED_WASM_PROBE_HTML");
		assertTrue(script.contains("quickFacts();"),
				"the threaded probe no longer reports its facts on load");
	}

	private static String scriptOf(String blockName) throws IOException {
		Path source = pageSource();
		String html = unescapeJavaTextBlock(blockOf(Files.readString(source, StandardCharsets.UTF_8), blockName));
		Matcher scripts = SCRIPT.matcher(html);
		assertTrue(scripts.find(), blockName + " contains no script");
		return scripts.group(1);
	}

	private static String blockOf(String text, String name) {
		Matcher blocks = BLOCK.matcher(text);
		while (blocks.find()) {
			if (name.equals(blocks.group(1))) {
				return blocks.group(2);
			}
		}
		throw new AssertionError("no " + name + " block");
	}

	/** What Java's text blocks do to escape sequences, so the check sees what the browser sees. */
	private static String unescapeJavaTextBlock(String raw) {
		Matcher escapes = Pattern.compile("\\\\(u([0-9a-fA-F]{4})|b|t|n|f|r|s|'|\"|\\\\)").matcher(raw);
		StringBuilder result = new StringBuilder();
		while (escapes.find()) {
			String sequence = escapes.group(1);
			String replacement = switch (sequence) {
				case "b" -> "\b";
				case "t" -> "\t";
				case "n" -> "\n";
				case "f" -> "\f";
				case "r" -> "\r";
				case "s" -> " ";
				case "'" -> "'";
				case "\"" -> "\"";
				case "\\" -> "\\";
				default -> escapes.group(2) != null
						? String.valueOf((char) Integer.parseInt(escapes.group(2), 16))
						: escapes.group();
			};
			escapes.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		escapes.appendTail(result);
		return result.toString();
	}

	private static void validateScript(String label, String script) {
		int curly = 0;
		int round = 0;
		int square = 0;
		char quote = 0;
		boolean lineComment = false;
		boolean blockComment = false;
		boolean escaped = false;

		for (int index = 0; index < script.length(); index++) {
			char character = script.charAt(index);
			char next = index + 1 < script.length() ? script.charAt(index + 1) : '\0';

			if (lineComment) {
				if (character == '\n') {
					lineComment = false;
				}
				continue;
			}
			if (blockComment) {
				if (character == '*' && next == '/') {
					blockComment = false;
					index++;
				}
				continue;
			}
			if (quote != 0) {
				if (escaped) {
					escaped = false;
				} else if (character == '\\') {
					escaped = true;
				} else if (character == quote) {
					quote = 0;
				}
				continue;
			}

			switch (character) {
				case '/' -> {
					if (next == '/') {
						lineComment = true;
						index++;
					} else if (next == '*') {
						blockComment = true;
						index++;
					}
				}
				case '\'', '"', '`' -> quote = character;
				case '{' -> curly++;
				case '}' -> curly--;
				case '(' -> round++;
				case ')' -> round--;
				case '[' -> square++;
				case ']' -> square--;
				default -> {
				}
			}
		}

		String problems = "";
		if (quote != 0) {
			problems += " unterminated " + quote + " string;";
		}
		if (lineComment || blockComment) {
			problems += " unterminated comment;";
		}
		if (curly != 0) {
			problems += " braces out of balance by " + curly + ";";
		}
		if (round != 0) {
			problems += " parentheses out of balance by " + round + ";";
		}
		if (square != 0) {
			problems += " brackets out of balance by " + square + ";";
		}
		assertTrue(problems.isEmpty(), label + " is malformed:" + problems);
	}
}
