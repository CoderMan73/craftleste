package com.craftleste.client;

import com.craftleste.CraftlesteMod;
import com.craftleste.client.browser.BrowserScreen;
import com.craftleste.client.config.CraftlesteConfig;
import com.craftleste.client.downloader.WeblesteDownloader;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.keksuccino.rinku.RinkuBrowser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Client entry point and the mod's command surface.
 *
 * <p>The commands are deliberately the whole M2 flow, because Cloth Config's settings screen is
 * M4: {@code load} copies the player's own Celeste files in, {@code fetch} pulls the official
 * Webleste runtime, and {@code open} (or the keybind) shows the game. The open path needs neither
 * command after the first time: whatever is missing is fetched or reloaded from the remembered
 * path automatically, which is what makes the screen a one-press action day to day.
 */
@Environment(EnvType.CLIENT)
public class CraftlesteClient implements ClientModInitializer {

	public static final String KEY_OPEN_CELESTE = "key.craftleste.open_celeste";

	/**
	 * A category of our own so the binding is not buried in Miscellaneous. Registered through
	 * {@link KeyMapping.Category#register} because it also appends the category to the controls
	 * screen's sort order, which the plain constructor does not.
	 */
	public static final KeyMapping.Category CRAFTLESTE_CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("craftleste", "controls"));

	private static KeyMapping openCelesteKey;

	@Override
	public void onInitializeClient() {
		openCelesteKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				KEY_OPEN_CELESTE,
				InputConstants.KEY_C,
				CRAFTLESTE_CATEGORY
		));

		ClientTickEvents.START_CLIENT_TICK.register(this::onClientTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> CraftlesteSession.getInstance().stop());
		registerCommands();
	}

	private void onClientTick(Minecraft client) {
		// Consume once per press rather than reading isDown every tick, so holding the key cannot
		// rebuild the screen every tick.
		if (openCelesteKey.consumeClick()) {
			send(client, "opening craftleste...");
			CraftlesteSession.getInstance().openWhenReady(client, message -> send(client, message));
		}
	}

	private void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
				dispatcher.register(ClientCommands.literal("craftleste")
						.then(ClientCommands.literal("open").executes(context -> {
							Minecraft client = Minecraft.getInstance();
							CraftlesteSession session = CraftlesteSession.getInstance();
							send(client, "opening craftleste...");
							session.openWhenReady(client, message -> send(client, message));
							return 1;
						}))
						.then(ClientCommands.literal("bootstrap").executes(context -> {
							Minecraft client = Minecraft.getInstance();
							CraftlesteSession session = CraftlesteSession.getInstance();
							if (session.ensureServerRunning()) {
								client.gui.setScreen(new BrowserScreen(session.gameUrl()));
								send(client, "opening the bootstrap page");
							} else {
								send(client, "the local server is not running: " + session.missingRequirement());
							}
							return 1;
						}))
						.then(ClientCommands.literal("probe").executes(context -> {
							CraftlesteSession.getInstance().openCapabilityProbeScreen(Minecraft.getInstance());
							send(Minecraft.getInstance(), "opening Probe 1 (directory picker, OPFS, gamepads)");
							return 1;
						}))
						.then(ClientCommands.literal("threads").executes(context -> {
							Minecraft client = Minecraft.getInstance();
							CraftlesteSession session = CraftlesteSession.getInstance();
							if (session.openThreadedWasmProbeScreen(client)) {
								send(client, "opening Probe 2 (threaded wasm)");
							} else {
								send(client, "threaded-wasm probe needs the local server: "
										+ session.missingRequirement());
							}
							return 1;
						}))
						.then(ClientCommands.literal("opfs").executes(context ->
								evaluateInActivePage(PageInspector.OPFS_INSPECTION)))
						.then(ClientCommands.literal("js")
								.then(ClientCommands.argument("code", StringArgumentType.greedyString())
										.executes(context -> evaluateInActivePage(
												StringArgumentType.getString(context, "code")))))
						.then(ClientCommands.literal("find").executes(context -> {
							CraftlesteSession.getInstance().findCelesteFiles(
									message -> send(Minecraft.getInstance(), message));
							return 1;
						}))
						.then(ClientCommands.literal("fetch").executes(context -> {
							send(Minecraft.getInstance(), "fetching the Webleste runtime from its official origin...");
							CraftlesteSession.getInstance().fetchRuntimeInBackground(
									message -> send(Minecraft.getInstance(), message));
							return 1;
						}))
						.then(ClientCommands.literal("load")
								.executes(context -> {
									String stored = CraftlesteConfig.getInstance().getCelesteSourcePath();
									if (stored == null || stored.isBlank()) {
										send(Minecraft.getInstance(),
												"no Celeste path is stored yet - /craftleste load <Celeste.exe or Content folder>");
										return 1;
									}
									CraftlesteSession.getInstance().loadCelesteInBackground(stored,
											message -> send(Minecraft.getInstance(), message));
									send(Minecraft.getInstance(), "loading your Celeste files...");
									return 1;
								})
								.then(ClientCommands.argument("path", StringArgumentType.greedyString())
										.executes(context -> {
											CraftlesteSession.getInstance().loadCelesteInBackground(
													StringArgumentType.getString(context, "path"),
													message -> send(Minecraft.getInstance(), message));
											send(Minecraft.getInstance(), "loading your Celeste files...");
											return 1;
										})))));
	}

	/**
	 * Runs an async expression in whichever browser page is open and reports the result. The title
	 * channel is length-limited, so chat gets a readable head and the log gets the whole thing.
	 */
	private int evaluateInActivePage(String expression) {
		CraftlesteSession session = CraftlesteSession.getInstance();
		RinkuBrowser browser = session.getActiveBrowser();
		if (browser == null) {
			send(Minecraft.getInstance(), "no browser page is open - open the screen first");
			return 0;
		}
		send(Minecraft.getInstance(), "evaluating in the open page...");
		PageInspector.evaluateAsync(browser, expression, result -> {
			CraftlesteMod.LOGGER.info("page evaluation result: {}", result);
			send(Minecraft.getInstance(), result.length() > 220 ? result.substring(0, 220) + " ..." : result);
			send(Minecraft.getInstance(), "(the full result is in the log)");
		});
		return 1;
	}

	private void send(Minecraft client, String message) {
		if (client.player != null) {
			client.player.sendSystemMessage(Component.literal(message));
		}
	}
}
