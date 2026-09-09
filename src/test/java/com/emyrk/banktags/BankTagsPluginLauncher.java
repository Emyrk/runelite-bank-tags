package com.emyrk.banktags;

import java.util.Arrays;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BankTagsPluginLauncher
{
	public static void main(String[] args) throws Exception
	{
		// RuneLite requires assertions throughout developer-mode startup. Bolt does
		// not preserve a custom launch command reliably, so enable assertions for all
		// classes loaded after this development launcher starts.
		ClassLoader classLoader = BankTagsPluginLauncher.class.getClassLoader();
		classLoader.setDefaultAssertionStatus(true);
		classLoader.setPackageAssertionStatus("net.runelite", true);
		classLoader.setClassAssertionStatus(ExternalPluginManager.class.getName(), true);
		ExternalPluginManager.loadBuiltin(BankTagsPlugin.class);

		// Bolt's RuneLite launcher passes launcher-specific JVM arguments through to
		// the JAR. This development launcher invokes RuneLite directly, so remove
		// those arguments before RuneLite parses its command line.
		String[] runeLiteArgs = Arrays.stream(args)
			.filter(arg -> !arg.startsWith("-J"))
			.toArray(String[]::new);
		RuneLite.main(runeLiteArgs);
	}
}
