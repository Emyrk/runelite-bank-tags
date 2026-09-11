package com.emyrk.banktags.inventorysync.model;

/** A combined manifest response, including the bodyless HTTP 304 case. */
public final class ManifestResponse
{
	private final InventorySetupManifest manifest;
	private final boolean notModified;

	private ManifestResponse(InventorySetupManifest manifest, boolean notModified)
	{
		this.manifest = manifest;
		this.notModified = notModified;
	}

	public static ManifestResponse of(InventorySetupManifest manifest)
	{
		return new ManifestResponse(manifest, false);
	}

	public static ManifestResponse notModified()
	{
		return new ManifestResponse(null, true);
	}

	public InventorySetupManifest getManifest() { return manifest; }
	public boolean isNotModified() { return notModified; }
}
