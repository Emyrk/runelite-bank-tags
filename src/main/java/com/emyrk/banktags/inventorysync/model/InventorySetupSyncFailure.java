package com.emyrk.banktags.inventorysync.model;

import com.emyrk.banktags.sync.model.SyncFailure;
import javax.annotation.Nullable;

/** Structured Inventory Setups protocol failure, including a parsed 409 current value. */
public final class InventorySetupSyncFailure
{
	private final SyncFailure.Kind kind;
	private final int httpStatus;
	@Nullable private final String errorCode;
	@Nullable private final String message;
	@Nullable private final InventorySetupManifest.SetupEntry currentSetup;
	@Nullable private final InventorySetupManifest.SectionEntry currentSection;
	@Nullable private final InventorySetupManifest currentManifest;

	public InventorySetupSyncFailure(SyncFailure.Kind kind, int httpStatus, @Nullable String errorCode,
		@Nullable String message, @Nullable InventorySetupManifest.SetupEntry currentSetup,
		@Nullable InventorySetupManifest.SectionEntry currentSection,
		@Nullable InventorySetupManifest currentManifest)
	{
		this.kind = kind;
		this.httpStatus = httpStatus;
		this.errorCode = errorCode;
		this.message = message;
		this.currentSetup = currentSetup;
		this.currentSection = currentSection;
		this.currentManifest = currentManifest;
	}

	public static InventorySetupSyncFailure network(@Nullable String message)
	{
		return new InventorySetupSyncFailure(SyncFailure.Kind.NETWORK, 0, null, message, null, null, null);
	}

	public SyncFailure.Kind getKind() { return kind; }
	public int getHttpStatus() { return httpStatus; }
	@Nullable public String getErrorCode() { return errorCode; }
	@Nullable public String getMessage() { return message; }
	@Nullable public InventorySetupManifest.SetupEntry getCurrentSetup() { return currentSetup; }
	@Nullable public InventorySetupManifest.SectionEntry getCurrentSection() { return currentSection; }
	@Nullable public InventorySetupManifest getCurrentManifest() { return currentManifest; }

	@Override
	public String toString()
	{
		return "InventorySetupSyncFailure{kind=" + kind + ", httpStatus=" + httpStatus
			+ ", errorCode=" + errorCode + '}';
	}
}
