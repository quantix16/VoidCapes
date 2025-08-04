package net.litetex.capes;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.authlib.GameProfile;

import net.litetex.capes.config.Config;
import net.litetex.capes.config.ModProviderHandling;
import net.litetex.capes.handler.PlayerCapeHandler;
import net.litetex.capes.handler.PlayerCapeHandlerManager;
import net.litetex.capes.handler.ProfileTextureLoadThrottler;
import net.litetex.capes.provider.CapeProvider;
import net.litetex.capes.provider.CustomProvider;
import net.litetex.capes.provider.DefaultMinecraftCapeProvider;
import net.litetex.capes.provider.ModMetadataProvider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;


public class Capes
{
	private static final Logger LOG = LoggerFactory.getLogger(Capes.class);
	
	public static final String MOD_ID = "cape-provider";
	
	public static final Identifier DEFAULT_ELYTRA_IDENTIFIER =
		Identifier.of("textures/entity/equipment/wings/elytra.png");
	
	public static final Predicate<CapeProvider> EXCLUDE_DEFAULT_MINECRAFT_CP =
		cp -> DefaultMinecraftCapeProvider.INSTANCE != cp;
	
	private static Capes instance;
	
	public static Capes instance()
	{
		return instance;
	}
	
	public static void setInstance(final Capes instance)
	{
		Capes.instance = instance;
	}
	
	private final Config config;
	private final Consumer<Config> saveConfigFunc;
	private final Map<String, CapeProvider> allProviders;
	
	private final boolean validateProfile;
	private final Duration loadThrottleSuppressDuration;
	private final Map<CapeProvider, Set<Integer>> blockedProviderCapeHashes;
	private final int playerCacheSize;
	private final boolean useRealPlayerOnlineValidation;
	
	private final PlayerCapeHandlerManager playerCapeHandlerManager;
	private final ProfileTextureLoadThrottler profileTextureLoadThrottler;
	private final ScheduledExecutorService refreshTimer;
	private volatile long nextAutoRefreshTime; // Track when next auto-refresh will occur
	private boolean shouldRefresh;
	
	@SuppressWarnings("checkstyle:MagicNumber")
	public Capes(
		final Config config,
		final Consumer<Config> saveConfigFunc,
		final Map<String, CapeProvider> allProviders)
	{
		this.config = config;
		this.saveConfigFunc = saveConfigFunc;
		this.allProviders = allProviders;
		
		// Calculate advanced/debug values
		
		this.validateProfile = !Boolean.FALSE.equals(this.config().isValidateProfile());
		LOG.debug("[VoidCapes] validateProfile: {}", this.validateProfile);
		
		this.loadThrottleSuppressDuration = Optional.ofNullable(this.config().getLoadThrottleSuppressSec())
			.map(Duration::ofSeconds)
			.orElse(Duration.ofMinutes(3));
		LOG.debug("[VoidCapes] loadThrottleSuppressDuration: {}", this.loadThrottleSuppressDuration);
		
		this.blockedProviderCapeHashes = Optional.ofNullable(this.config().getBlockedProviderCapeHashes())
			.map(map -> map.entrySet()
				.stream()
				.filter(e -> allProviders.containsKey(e.getKey()))
				.collect(Collectors.toMap(e -> allProviders.get(e.getKey()), Map.Entry::getValue)))
			.orElseGet(Map::of);
		LOG.debug("[VoidCapes] blockedProviderCapeHashes: {}x", this.blockedProviderCapeHashes.size());
		
		final Integer configPlayerCacheSize = this.config.getPlayerCacheSize();
		this.playerCacheSize = configPlayerCacheSize != null
			? Math.clamp(configPlayerCacheSize, 1, 100_000)
			: 1000;
		LOG.debug("[VoidCapes] playerCacheSize: {}", this.playerCacheSize);
		
		this.useRealPlayerOnlineValidation = Boolean.TRUE.equals(config.getUseRealPlayerOnlineValidation());
		LOG.debug("[VoidCapes] useRealPlayerOnlineValidation: {}", this.useRealPlayerOnlineValidation);
		
		this.playerCapeHandlerManager = new PlayerCapeHandlerManager(this);
		this.profileTextureLoadThrottler = new ProfileTextureLoadThrottler(
			this.playerCapeHandlerManager,
			this.playerCacheSize());
		
		// Initialize timer for automatic cache refresh every 3 minutes
		this.refreshTimer = Executors.newSingleThreadScheduledExecutor(r -> {
			final Thread thread = new Thread(r, "CapeProvider-AutoRefresh");
			thread.setDaemon(true);
			return thread;
		});
		
		// Calculate initial next refresh time (3 minutes from now)
		this.nextAutoRefreshTime = System.currentTimeMillis() + (3 * 60 * 1000);
		
		// Start periodic refresh - run every 3 minutes
		this.refreshTimer.scheduleAtFixedRate(
			this::autoRefresh, 
			3, // Initial delay of 3 minutes
			3, // Repeat every 3 minutes
			TimeUnit.MINUTES
		);
		
		final long startMs = System.currentTimeMillis();
		this.postProcessModProviders();
		LOG.debug("[VoidCapes] Post processing mod providers took {}ms", System.currentTimeMillis() - startMs);
	}
	
	protected void postProcessModProviders()
	{
		final ModProviderHandling modProviderHandling = this.config().getModProviderHandling();
		if(modProviderHandling.activateByDefault())
		{
			// Works like this:
			// Mod is present? -> FirstTimeMissing=Instant.MAX
			// Mod was present during last time? -> FirstTimeMissing=NOW
			// Remove all mods where FirstTimeMissing is too old
			final Set<String> providerIdsLoadedByMods = this.getAllProviders().values()
				.stream()
				.filter(ModMetadataProvider.class::isInstance)
				.map(ModMetadataProvider.class::cast)
				.map(CustomProvider::id)
				.collect(Collectors.toCollection(LinkedHashSet::new));
			
			final Instant nullPlaceholder = Instant.MAX; // GSON doesn't serialize nulls by default
			final Instant now = Instant.now();
			final Instant removeOutdated = now.minus(Duration.ofDays(7));
			final Map<String, Instant> knownProviderIdsFirstTimeMissing =
				Optional.ofNullable(this.config().getKnownModProviderIdsFirstTimeMissing())
					.map(Map::entrySet)
					.stream()
					.flatMap(Collection::stream)
					// Remove outdated
					.filter(e -> nullPlaceholder.equals(e.getValue()) || e.getValue().isAfter(removeOutdated))
					.collect(Collectors.toMap(
						Map.Entry::getKey,
						e -> nullPlaceholder.equals(e.getValue()) ? now : e.getValue()));
			
			final Set<String> activeProviderIds = Objects.requireNonNullElseGet(
				this.config().getActiveProviderIds(),
				LinkedHashSet::new);
			providerIdsLoadedByMods.stream()
				.filter(id -> !knownProviderIdsFirstTimeMissing.containsKey(id))
				.forEach(activeProviderIds::add);
			this.config().setActiveProviderIds(activeProviderIds);
			
			providerIdsLoadedByMods.forEach(id -> knownProviderIdsFirstTimeMissing.put(id, nullPlaceholder));
			
			this.config().setKnownModProviderIdsFirstTimeMissing(knownProviderIdsFirstTimeMissing);
			this.saveConfig();
			
			return;
		}
		
		// Reset all known providers due to privacy reasons
		if(this.config().getKnownModProviderIdsFirstTimeMissing() != null)
		{
			this.config().setKnownModProviderIdsFirstTimeMissing(null);
			this.saveConfig();
		}
	}
	
	public void saveConfig()
	{
		this.saveConfigFunc.accept(this.config);
	}
	
	public void saveConfigAndMarkRefresh()
	{
		this.saveConfig();
		this.shouldRefresh = true;
	}
	
	public void refreshIfMarked()
	{
		if(this.shouldRefresh)
		{
			this.refresh();
			this.shouldRefresh = false;
		}
	}
	
	public void refresh()
	{
		LOG.debug("[VoidCapes] Refreshing cape cache...");
		
		// Update next auto-refresh time when manually refreshing
		this.nextAutoRefreshTime = System.currentTimeMillis() + (3 * 60 * 1000);
		
		this.profileTextureLoadThrottler.clearCache();
		this.playerCapeHandlerManager.clearCache();

		final ClientPlayNetworkHandler networkHandler = MinecraftClient.getInstance().getNetworkHandler();
		if(networkHandler != null)
		{
			final int playerCount = networkHandler.getPlayerList().size();
			LOG.debug("[VoidCapes] Refreshing capes for {} visible players", playerCount);
			networkHandler.getPlayerList().forEach(e ->
				this.profileTextureLoadThrottler.loadIfRequired(e.getProfile()));
		}
		LOG.debug("[VoidCapes] Cape cache refresh completed");
	}
	
	/**
	 * Get the time when the next auto-refresh will occur (in milliseconds since epoch).
	 * Used by GUI to show countdown timer.
	 */
	public long getNextAutoRefreshTime()
	{
		return this.nextAutoRefreshTime;
	}	private void autoRefresh()
	{
		try
		{
			// Update next refresh time for GUI synchronization
			this.nextAutoRefreshTime = System.currentTimeMillis() + (3 * 60 * 1000);
			
			// Only auto-refresh if we're connected to a server/world
			final ClientPlayNetworkHandler networkHandler = MinecraftClient.getInstance().getNetworkHandler();
			if(networkHandler != null)
			{
				LOG.info("[VoidCapes] Auto-refreshed cape cache");
				this.refresh();
			}
		}
		catch(final Exception e)
		{
			LOG.warn("[VoidCapes] Error during auto-refresh", e);
		}
	}
	
	public Config config()
	{
		return this.config;
	}
	
	public Map<String, CapeProvider> getAllProviders()
	{
		return this.allProviders;
	}
	
	public Optional<CapeProvider> getCapeProviderForSelf()
	{
		return Optional.ofNullable(this.config.getCurrentPreviewProviderId())
			.map(this.allProviders::get);
	}
	
	public List<CapeProvider> activeCapeProviders()
	{
		return this.config.getActiveProviderIds().stream()
			.map(this.allProviders::get)
			.filter(EXCLUDE_DEFAULT_MINECRAFT_CP)
			.filter(Objects::nonNull)
			.toList();
	}
	
	public boolean isUseDefaultProvider()
	{
		return this.config().isUseDefaultProvider();
	}
	
	public boolean validateProfile()
	{
		return this.validateProfile;
	}
	
	public Duration loadThrottleSuppressDuration()
	{
		return this.loadThrottleSuppressDuration;
	}
	
	public Map<CapeProvider, Set<Integer>> blockedProviderCapeHashes()
	{
		return this.blockedProviderCapeHashes;
	}
	
	public int playerCacheSize()
	{
		return this.playerCacheSize;
	}
	
	public boolean useRealPlayerOnlineValidation()
	{
		return this.useRealPlayerOnlineValidation;
	}
	
	public ProfileTextureLoadThrottler textureLoadThrottler()
	{
		return this.profileTextureLoadThrottler;
	}
	
	public PlayerCapeHandlerManager playerCapeHandlerManager()
	{
		return this.playerCapeHandlerManager;
	}
	
	public boolean overwriteSkinTextures(
		final GameProfile profile,
		final Supplier<SkinTextures> oldTexureSupplier,
		final Consumer<SkinTextures> applyOverwrittenTextures)
	{
		final PlayerCapeHandler handler = this.playerCapeHandlerManager().getProfile(profile);
		if(handler != null)
		{
			final Identifier capeTexture = handler.getCape();
			if(capeTexture != null)
			{
				final SkinTextures oldTextures = oldTexureSupplier.get();
				final Identifier elytraTexture = handler.hasElytraTexture()
					&& this.config().isEnableElytraTexture()
					? capeTexture
					: Capes.DEFAULT_ELYTRA_IDENTIFIER;
				applyOverwrittenTextures.accept(new SkinTextures(
					oldTextures.texture(),
					oldTextures.textureUrl(),
					capeTexture,
					elytraTexture,
					oldTextures.model(),
					oldTextures.secure()));
				return true;
			}
		}
		if(!this.isUseDefaultProvider())
		{
			final SkinTextures oldTextures = oldTexureSupplier.get();
			applyOverwrittenTextures.accept(new SkinTextures(
				oldTextures.texture(),
				oldTextures.textureUrl(),
				null,
				null,
				oldTextures.model(),
				oldTextures.secure()));
			return true;
		}
		return false;
	}
	
	/**
	 * Shuts down the automatic refresh timer.
	 * Should be called when the mod is being unloaded.
	 */
	public void shutdown()
	{
		if(this.refreshTimer != null && !this.refreshTimer.isShutdown())
		{
			LOG.debug("[VoidCapes] Shutting down cape auto-refresh timer");
			this.refreshTimer.shutdown();
			try
			{
				if(!this.refreshTimer.awaitTermination(5, TimeUnit.SECONDS))
				{
					this.refreshTimer.shutdownNow();
				}
			}
			catch(final InterruptedException e)
			{
				this.refreshTimer.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}
}
