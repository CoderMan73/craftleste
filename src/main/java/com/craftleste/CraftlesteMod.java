package com.craftleste;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CraftlesteMod implements ModInitializer {
	public static final String MOD_ID = "craftleste";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Before anything can open a browser: this edits CEF's command line, and Rinku starts CEF
		// on the first GUI render, so registering at mod load is the only moment a mod can.
		CraftlesteCefOptions.register();
		LOGGER.info("Craftleste mod initialized");
	}
}
