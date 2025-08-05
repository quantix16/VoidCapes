package net.litetex.capes.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.litetex.capes.Capes;
import net.litetex.capes.config.CredentialsManager;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;


/**
 * Command to store cape provider login credentials.
 */
public class CapeLoginCommand
{
	private static CredentialsManager credentialsManager;
	
	/**
	 * Sets the credentials manager to use.
	 */
	public static void setCredentialsManager(final CredentialsManager manager)
	{
		credentialsManager = manager;
	}
	
	/**
	 * Registers the cape login command.
	 */
	public static void register(final CommandDispatcher<FabricClientCommandSource> dispatcher)
	{
		dispatcher.register(
			literal("capelogin")
				.then(argument("username", StringArgumentType.word())
					.then(argument("password", StringArgumentType.greedyString())
						.executes(CapeLoginCommand::execute)
					)
				)
		);
		
		// Also register a command to check stored credentials
		dispatcher.register(
			literal("capecheck")
				.executes(CapeLoginCommand::executeCheck)
		);
		
		// Also register a command to clear stored credentials
		dispatcher.register(
			literal("capeclear")
				.executes(CapeLoginCommand::executeClear)
		);
	}
	
	private static int execute(final CommandContext<FabricClientCommandSource> context)
	{
		final String username = StringArgumentType.getString(context, "username");
		final String password = StringArgumentType.getString(context, "password");
		
		if (credentialsManager == null)
		{
			context.getSource().sendError(Text.literal("[VoidCapes] Credentials manager not initialized"));
			return 0;
		}
		
		try
		{
			// Store the credentials
			credentialsManager.storeCredentials(username, password);
			
			// Send success message
			context.getSource().sendFeedback(
				Text.literal("[VoidCapes] ")
					.formatted(Formatting.GREEN)
					.append(Text.literal("Credentials saved successfully for user: " + username)
						.formatted(Formatting.WHITE))
			);
			
			// Optionally try to login immediately
			if (Capes.instance() != null)
			{
				context.getSource().sendFeedback(
					Text.literal("[VoidCapes] ")
						.formatted(Formatting.YELLOW)
						.append(Text.literal("Attempting to use credentials with cape providers...")
							.formatted(Formatting.WHITE))
				);
			}
			
			return 1;
		}
		catch (final Exception e)
		{
			context.getSource().sendError(
				Text.literal("[VoidCapes] Failed to save credentials: " + e.getMessage())
			);
			return 0;
		}
	}
	
	private static int executeCheck(final CommandContext<FabricClientCommandSource> context)
	{
		if (credentialsManager == null)
		{
			context.getSource().sendError(Text.literal("[VoidCapes] Credentials manager not initialized"));
			return 0;
		}
		
		try
		{
			if (credentialsManager.hasCredentials())
			{
				final CredentialsManager.CredentialsData credentials = credentialsManager.loadCredentials();
				if (credentials != null)
				{
					context.getSource().sendFeedback(
						Text.literal("[VoidCapes] ")
							.formatted(Formatting.GREEN)
							.append(Text.literal("Stored credentials found for user: " + credentials.getUsername())
								.formatted(Formatting.WHITE))
					);
					
					final long daysAgo = (System.currentTimeMillis() - credentials.getTimestamp()) / (1000 * 60 * 60 * 24);
					context.getSource().sendFeedback(
						Text.literal("[VoidCapes] ")
							.formatted(Formatting.GRAY)
							.append(Text.literal("Saved " + daysAgo + " days ago")
								.formatted(Formatting.WHITE))
					);
				}
				else
				{
					context.getSource().sendFeedback(
						Text.literal("[VoidCapes] ")
							.formatted(Formatting.YELLOW)
							.append(Text.literal("Credentials file exists but could not be decrypted")
								.formatted(Formatting.WHITE))
					);
				}
			}
			else
			{
				context.getSource().sendFeedback(
					Text.literal("[VoidCapes] ")
						.formatted(Formatting.YELLOW)
						.append(Text.literal("No stored credentials found")
							.formatted(Formatting.WHITE))
				);
			}
			return 1;
		}
		catch (final Exception e)
		{
			context.getSource().sendError(
				Text.literal("[VoidCapes] Failed to check credentials: " + e.getMessage())
			);
			return 0;
		}
	}
	
	private static int executeClear(final CommandContext<FabricClientCommandSource> context)
	{
		if (credentialsManager == null)
		{
			context.getSource().sendError(Text.literal("[VoidCapes] Credentials manager not initialized"));
			return 0;
		}
		
		try
		{
			credentialsManager.deleteCredentials();
			context.getSource().sendFeedback(
				Text.literal("[VoidCapes] ")
					.formatted(Formatting.GREEN)
					.append(Text.literal("Stored credentials cleared successfully")
						.formatted(Formatting.WHITE))
			);
			return 1;
		}
		catch (final Exception e)
		{
			context.getSource().sendError(
				Text.literal("[VoidCapes] Failed to clear credentials: " + e.getMessage())
			);
			return 0;
		}
	}
	
	/**
	 * Retrieves stored credentials if available.
	 */
	public static CredentialsManager.CredentialsData getStoredCredentials()
	{
		if (credentialsManager == null)
		{
			return null;
		}
		
		try
		{
			return credentialsManager.loadCredentials();
		}
		catch (final Exception e)
		{
			return null;
		}
	}
}
