package net.litetex.capes.fabric;

import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.litetex.capes.Capes;
import net.litetex.capes.command.CapeLoginCommand;
import net.litetex.capes.command.CapeRefreshCommand;
import net.litetex.capes.command.CapeSetCommand;
import net.litetex.capes.command.CapeConfirmCommand;
import net.litetex.capes.command.CapeRemoveCommand;
import net.litetex.capes.config.AnimatedCapesHandling;
import net.litetex.capes.config.Config;
import net.litetex.capes.config.CredentialsManager;
import net.litetex.capes.config.ModProviderHandling;
import net.litetex.capes.menu.preview.render.PlayerDisplayGuiElementRenderer;
import net.litetex.capes.provider.suppliers.CapeProviders;


public class FabricCapes implements ClientModInitializer
{
	private static final Logger LOG = LoggerFactory.getLogger(FabricCapes.class);
	
	@Override
	public void onInitializeClient()
	{
		SpecialGuiElementRegistry.register(ctx -> new PlayerDisplayGuiElementRenderer(ctx.vertexConsumers()));
		
		// Initialize credentials manager
		final Path configDir = FabricLoader.getInstance().getConfigDir().resolve("voidcapes");
		final CredentialsManager credentialsManager = new CredentialsManager(configDir);
		CapeLoginCommand.setCredentialsManager(credentialsManager);
		
		// Register commands
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			CapeRefreshCommand.register(dispatcher);
			CapeLoginCommand.register(dispatcher);
			CapeSetCommand.register(dispatcher);
			CapeConfirmCommand.register(dispatcher);
			CapeRemoveCommand.register(dispatcher);
		});
		
		// Create a simple hardcoded config for Voidcube only
		final Config config = createHardcodedConfig();
		final Capes capes = new Capes(
			config,
			c -> {}, // No-op config saver since we're not using config files
			CapeProviders.findAllProviders()
		);
		Capes.setInstance(capes);
		
		// Add shutdown hook to properly clean up the timer
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			LOG.debug("[VoidCapes] Shutting down Capes mod");
			capes.shutdown();
			CapeSetCommand.shutdown();
			CapeRemoveCommand.shutdown();
		}, "CapeProvider-Shutdown"));
		
		LOG.debug("[VoidCapes] Initialized");
	}
	
	private Config createHardcodedConfig()
	{
		final Config config = new Config();
		config.setCurrentPreviewProviderId("voidcube");
		config.setActiveProviderIds(List.of("voidcube"));
		config.setUseDefaultProvider(true); // Keep vanilla MC capes
		config.setOnlyLoadForSelf(false);
		config.setEnableElytraTexture(true);
		config.setAnimatedCapesHandling(AnimatedCapesHandling.ON);
		config.setCustomProviders(List.of()); // Empty since we hardcoded Voidcube
		config.setModProviderHandling(ModProviderHandling.OFF); // Disable mod providers
		return config;
	}
}
