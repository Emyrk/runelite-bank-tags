package com.emyrk.banktags.sync.model;

import javax.annotation.Nullable;

/**
 * Result of a conditional manifest fetch: either the server reported {@code 304 Not Modified}
 * or it returned a manifest.
 */
public final class ManifestResult
{
	private static final ManifestResult NOT_MODIFIED = new ManifestResult(null);

	@Nullable
	private final BankTagManifest manifest;

	private ManifestResult(@Nullable BankTagManifest manifest)
	{
		this.manifest = manifest;
	}

	public static ManifestResult notModified()
	{
		return NOT_MODIFIED;
	}

	public static ManifestResult of(BankTagManifest manifest)
	{
		if (manifest == null)
		{
			throw new IllegalArgumentException("manifest");
		}
		return new ManifestResult(manifest);
	}

	public boolean isNotModified()
	{
		return manifest == null;
	}

	@Nullable
	public BankTagManifest getManifest()
	{
		return manifest;
	}

	@Override
	public String toString()
	{
		return manifest == null ? "ManifestResult{notModified}" : "ManifestResult{" + manifest + '}';
	}
}
