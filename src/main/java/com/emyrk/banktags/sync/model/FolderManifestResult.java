package com.emyrk.banktags.sync.model;

import javax.annotation.Nullable;

/** Result of a conditional folder-manifest fetch. */
public final class FolderManifestResult
{
	private static final FolderManifestResult NOT_MODIFIED = new FolderManifestResult(null);

	@Nullable
	private final BankTagFolderManifest manifest;

	private FolderManifestResult(@Nullable BankTagFolderManifest manifest)
	{
		this.manifest = manifest;
	}

	public static FolderManifestResult notModified() { return NOT_MODIFIED; }
	public static FolderManifestResult of(BankTagFolderManifest manifest)
	{
		if (manifest == null)
		{
			throw new IllegalArgumentException("manifest");
		}
		return new FolderManifestResult(manifest);
	}
	public boolean isNotModified() { return manifest == null; }
	@Nullable public BankTagFolderManifest getManifest() { return manifest; }
}
