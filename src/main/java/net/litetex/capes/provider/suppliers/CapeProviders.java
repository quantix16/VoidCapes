package net.litetex.capes.provider.suppliers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.litetex.capes.provider.CapeProvider;
import net.litetex.capes.provider.DefaultMinecraftCapeProvider;
import net.litetex.capes.provider.VoidcubeProvider;


public final class CapeProviders
{
	public static Map<String, CapeProvider> findAllProviders()
	{
		return Stream.of(
				// Hardcoded Voidcube provider
				VoidcubeProvider.INSTANCE,
				// Default Minecraft cape provider (keep for vanilla cape support)
				DefaultMinecraftCapeProvider.INSTANCE)
			.map(CapeProvider.class::cast)
			// Use LinkedHashMap to keep order
			.collect(Collectors.toMap(
				CapeProvider::id,
				Function.identity(),
				(e1, e2) -> e2,
				LinkedHashMap::new));
	}
	
	private CapeProviders()
	{
	}
}
