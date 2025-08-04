package net.litetex.capes.provider;

import java.util.List;

import com.mojang.authlib.GameProfile;

import net.litetex.capes.provider.antifeature.AntiFeature;
import net.minecraft.client.MinecraftClient;


public class VoidcubeProvider implements CapeProvider
{
	public static final VoidcubeProvider INSTANCE = new VoidcubeProvider();
	
	private VoidcubeProvider()
	{
	}
	
	@Override
	public String id()
	{
		return "voidcube";
	}
	
	@Override
	public String name()
	{
		return "Voidcube";
	}
	
	@Override
	public String getBaseUrl(final GameProfile profile)
	{
		return "https://voidcube.de/capes/" + profile.getId().toString();
	}
	
	@Override
	public boolean isDefaultAnimatedTexture()
	{
		return true;
	}
	
	@Override
	public boolean hasChangeCapeUrl()
	{
		return false;
	}
	
	@Override
	public String changeCapeUrl(final MinecraftClient client)
	{
		return null;
	}
	
	@Override
	public String homepageUrl()
	{
		return "https://voidcube.de";
	}
	
	@Override
	public List<AntiFeature> antiFeatures()
	{
		return List.of();
	}
	
	@Override
	public double rateLimitedReqPerSec()
	{
		return DEFAULT_RATE_LIMIT_REQ_PER_SEC;
	}
}
