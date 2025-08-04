package net.litetex.capes.menu.preview;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.mojang.authlib.GameProfile;

import net.litetex.capes.Capes;
import net.litetex.capes.handler.IdentifierProvider;
import net.litetex.capes.handler.PlayerCapeHandler;
import net.litetex.capes.handler.PlayerCapeHandlerManager;
import net.litetex.capes.i18n.CapesI18NKeys;
import net.litetex.capes.menu.MainMenuScreen;
import net.litetex.capes.menu.preview.render.PlayerDisplayGuiPayload;
import net.litetex.capes.menu.preview.render.PlayerDisplayWidget;
import net.litetex.capes.provider.CapeProvider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;


@SuppressWarnings("checkstyle:MagicNumber")
public class PreviewMenuScreen extends MainMenuScreen
{
	private final PlayerDisplayWidget playerWidget;
	private final ViewModel viewModel = new ViewModel();
	private ButtonWidget refreshCountdownWidget;
	private final Capes capes = Capes.instance();
	
	public PreviewMenuScreen(
		final Screen parent,
		final GameOptions gameOptions)
	{
		super(parent, gameOptions);
		
		final PlayerLimbAnimator playerLimbAnimator = new PlayerLimbAnimator(60);
		this.playerWidget = new PlayerDisplayWidget(
			120,
			120,
			MinecraftClient.getInstance().getLoadedEntityModels(),
			this.viewModel::getPayload,
			models -> playerLimbAnimator.animate(models.player(), 1));
		this.playerWidget.yRotation = 185; // Default view = from behind, facing the cape/elytra
	}
	
	@SuppressWarnings("checkstyle:MagicNumber")
	@Override
	protected void initSelfMangedDrawableChilds()
	{
		super.initSelfMangedDrawableChilds();
		
		int buttonW = 200;
		
		// Add countdown timer showing next refresh time
		this.refreshCountdownWidget = this.addSelfManagedDrawableChild(ButtonWidget.builder(
				this.getCountdownText(),
				button -> {
					// Click to refresh immediately and reset timer
					final Capes capes = Capes.instance();
					if(capes != null)
					{
						try
						{
							capes.refresh();
							this.viewModel.providerChanged(); // Refresh the preview
							button.setMessage(this.getCountdownText());
						}
						catch(final Exception e)
						{
							// Silent error, just log it
							System.err.println("[VoidCapes] Error refreshing capes: " + e.getMessage());
						}
					}
				})
			.position((this.width / 2) - (buttonW / 2), 60)
			.size(buttonW, 20)
			.build());
		
		this.playerWidget.setHeight(Math.clamp(this.height - 120, 25, 180));
		this.playerWidget.setPosition(this.width / 2 - this.playerWidget.getWidth() / 2, 82);
		
		buttonW = 100;
		final int playerWidgetCenterY = this.playerWidget.getY() + (this.playerWidget.getHeight() / 2);
		
		this.addSelfManagedDrawableChild(ButtonWidget.builder(
				Text.translatable(CapesI18NKeys.TOGGLE_ELYTRA),
				b -> this.viewModel.toggleShowElytra())
			.position((this.width / 4) - (buttonW / 2), playerWidgetCenterY - 23)
			.size(buttonW, 20)
			.build());
		
		this.addSelfManagedDrawableChild(ButtonWidget.builder(
				Text.translatable(CapesI18NKeys.TOGGLE_PLAYER),
				b -> this.viewModel.toggleShowBody())
			.position((this.width / 4) - (buttonW / 2), playerWidgetCenterY + 2)
			.size(buttonW, 20)
			.build());
		
		// Add refresh button on the right side
		this.addSelfManagedDrawableChild(ButtonWidget.builder(
				Text.literal("Refresh Capes"),
				b -> {
					final Capes capes = Capes.instance();
					if(capes != null)
					{
						try
						{
							capes.refresh();
							this.viewModel.providerChanged(); // Refresh the preview
							this.refreshCountdownWidget.setMessage(this.getCountdownText());
						}
						catch(final Exception e)
						{
							// Silent error, just log it
							System.err.println("[VoidCapes] Error refreshing capes: " + e.getMessage());
						}
					}
				})
			.position((this.width * 3 / 4) - (buttonW / 2), playerWidgetCenterY - 10)
			.size(buttonW, 20)
			.build());
		
		this.addSelfManagedDrawableChild(this.playerWidget);
	}
	
	@Override
	public void tick()
	{
		super.tick();
		// Update countdown timer every tick
		if(this.refreshCountdownWidget != null)
		{
			this.refreshCountdownWidget.setMessage(this.getCountdownText());
		}
	}
	
	private Text getCountdownText()
	{
		final long timeLeft = capes.getNextAutoRefreshTime() - System.currentTimeMillis();
		if(timeLeft <= 0)
		{
			return Text.literal("§6VoidCube §7(Next refresh: 3:00)");
		}
		
		final long seconds = (timeLeft / 1000) % 60;
		final long minutes = (timeLeft / (1000 * 60)) % 60;
		
		return Text.literal(String.format("§6VoidCube §7(Next refresh: %d:%02d)", minutes, seconds));
	}
	
	static class ViewModel
	{
		private static final Supplier<Identifier> DEFAULT_ELYTRA_SUPPLIER = () -> Capes.DEFAULT_ELYTRA_IDENTIFIER;
		
		private final GameProfile gameProfile;
		private SkinTextures skin;
		private boolean slim;
		
		private List<CapeProvider> capeProviders;
		
		private Supplier<Identifier> capeTextureSupplier;
		private Supplier<Identifier> elytraTextureSupplier = DEFAULT_ELYTRA_SUPPLIER;
		
		private boolean showBody = true;
		private boolean showElytra;
		
		private PlayerDisplayGuiPayload payload;
		
		public ViewModel()
		{
			this.gameProfile = MinecraftClient.getInstance().getGameProfile();
			this.skin = DefaultSkinHelper.getSkinTextures(this.gameProfile);
			
			this.refreshActiveCapeProviders();
			this.rebuildPayload();
			
			MinecraftClient.getInstance().getSkinProvider().fetchSkinTextures(this.gameProfile)
				.thenAcceptAsync(optSkinTextures ->
					optSkinTextures.ifPresent(skinTextures -> {
						this.skin = skinTextures;
						this.slim = SkinTextures.Model.SLIM.equals(this.skin.model());
						
						this.updateCapeAndElytraTexture();
					}));
		}
		
		private void refreshActiveCapeProviders()
		{
			final Capes capes = Capes.instance();
			// Always use VoidCube provider - get it directly from all providers
			final CapeProvider voidcubeProvider = capes.getAllProviders().get("voidcube");
			this.capeProviders = voidcubeProvider != null 
				? List.of(voidcubeProvider)
				: List.of(); // Empty list if VoidCube provider not found
		}
		
		private void updateCapeAndElytraTexture()
		{
			this.capeTextureSupplier = null;
			this.elytraTextureSupplier = null;
			this.rebuildPayload();
			
			final PlayerCapeHandlerManager playerCapeHandlerManager = Capes.instance().playerCapeHandlerManager();
			playerCapeHandlerManager.onLoadTexture(
				this.gameProfile, false, this.capeProviders, () -> {
					final PlayerCapeHandler handler = playerCapeHandlerManager.getProfile(this.gameProfile);
					
					final Supplier<Identifier> determinedCapeTextureSupplier =
						this.determineCapeIdentifierSupplier(handler);
					this.capeTextureSupplier = determinedCapeTextureSupplier;
					
					this.elytraTextureSupplier = handler == null
						|| handler.hasElytraTexture()
						&& Capes.instance().config().isEnableElytraTexture()
						? determinedCapeTextureSupplier
						: DEFAULT_ELYTRA_SUPPLIER;
					
					this.rebuildPayload();
				});
		}
		
		private Supplier<Identifier> determineCapeIdentifierSupplier(final PlayerCapeHandler handler)
		{
			if(handler != null)
			{
				final IdentifierProvider identifierProvider = handler.capeIdentifierProvider().orElse(null);
				if(identifierProvider != null)
				{
					if(identifierProvider.dynamicIdentifier())
					{
						return () -> identifierProvider.identifier();
					}
					
					// Fetch only once
					final Identifier identifier = identifierProvider.identifier();
					return () -> identifier;
				}
			}
			
			final Capes capes = Capes.instance();
			final Optional<CapeProvider> provider = capes.getCapeProviderForSelf();
			// Is all active providers and useDefaultProvider?
			return provider.isEmpty() && capes.isUseDefaultProvider()
				// Default provider is present?
				|| provider.filter(Capes.EXCLUDE_DEFAULT_MINECRAFT_CP).isEmpty()
				? this.skin::capeTexture
				: () -> null;
		}
		
		public void providerChanged()
		{
			this.refreshActiveCapeProviders();
			this.updateCapeAndElytraTexture();
		}
		
		public void toggleShowBody()
		{
			this.showBody = !this.showBody;
			this.rebuildPayload();
		}
		
		public void toggleShowElytra()
		{
			this.showElytra = !this.showElytra;
			this.rebuildPayload();
		}
		
		private void rebuildPayload()
		{
			this.payload = new PlayerDisplayGuiPayload(
				this.showBody ? this.skin.texture() : null,
				this.capeTextureSupplier,
				this.showElytra ? this.elytraTextureSupplier : null,
				this.slim
			);
		}
		
		public PlayerDisplayGuiPayload getPayload()
		{
			return this.payload;
		}
	}
	
	
	static class PlayerLimbAnimator
	{
		private static final float LIMB_DISTANCE = -0.1f;
		private final int msBetweenUpdates;
		private long nextUpdateTimeMs;
		
		private float limbAngle;
		
		public PlayerLimbAnimator(final int fps)
		{
			this.msBetweenUpdates = 1000 / fps;
		}
		
		public void animate(final PlayerEntityModel player, final float tickDelta)
		{
			if(player == null)
			{
				return;
			}
			
			final long currentTimeMs = System.currentTimeMillis();
			if(currentTimeMs > this.nextUpdateTimeMs)
			{
				this.nextUpdateTimeMs = currentTimeMs + this.msBetweenUpdates;
				
				this.limbAngle += LIMB_DISTANCE;
			}
			
			final float calcLimbAngle = this.limbAngle - LIMB_DISTANCE * (1.0f - tickDelta);
			
			final float a = calcLimbAngle * 0.6662f;
			player.rightArm.pitch = MathHelper.cos(a + 3.1415927f) * 2.0f * LIMB_DISTANCE * 0.5f;
			player.leftArm.pitch = MathHelper.cos(a) * 2.0f * LIMB_DISTANCE * 0.5f;
			player.rightLeg.pitch = MathHelper.cos(a) * 1.4f * LIMB_DISTANCE;
			player.leftLeg.pitch = MathHelper.cos(a + 3.1415927f) * 1.4f * LIMB_DISTANCE;
		}
	}
}
