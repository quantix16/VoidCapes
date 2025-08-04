package net.litetex.capes.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.litetex.capes.Capes;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Command to manually refresh the cape cache.
 * Usage: /caperefresh
 */
public class CapeRefreshCommand
{
	public static void register(final CommandDispatcher<FabricClientCommandSource> dispatcher)
	{
		dispatcher.register(
			literal("caperefresh")
				.executes(CapeRefreshCommand::execute)
		);
	}
	
	private static int execute(final CommandContext<FabricClientCommandSource> context)
	{
		final Capes capes = Capes.instance();
		if(capes == null)
		{
			context.getSource().sendError(Text.literal("§c[VoidCapes] Mod not initialized!"));
			return 0;
		}
		
		// Check if we're connected to a world/server
		final MinecraftClient client = MinecraftClient.getInstance();
		if(client.getNetworkHandler() == null)
		{
			context.getSource().sendError(Text.literal("§c[VoidCapes] You must be connected to a world or server to refresh capes!"));
			return 0;
		}
		
		try
		{
			capes.refresh();
			context.getSource().sendFeedback(Text.literal("§a[VoidCapes] Cape cache refreshed! All visible player capes have been reloaded."));
			return 1;
		}
		catch(final Exception e)
		{
			context.getSource().sendError(Text.literal("§c[VoidCapes] Error refreshing capes: " + e.getMessage()));
			return 0;
		}
	}
}
