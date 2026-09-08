package com.emyrk.banktags.sync;

/**
 * Observable synchronization state. Menu entries and chat messages are derived from these enums
 * only, never from exceptions or HTTP status codes.
 */
public final class BankTagSyncStatus
{
	private BankTagSyncStatus()
	{
	}

	/**
	 * State of the connection as a whole.
	 */
	public enum GlobalState
	{
		/** Sync is off, unconfigured, or stopped. */
		DISABLED,
		/** Started; no request has completed yet. */
		INITIALIZING,
		/** The last request succeeded. */
		ONLINE,
		/** The last request failed with a network or server error; polls back off exponentially and uploads wait. */
		OFFLINE,
		/** The server answered {@code 401}; nothing is sent until the settings change. */
		INVALID_CREDENTIALS
	}

	/**
	 * State of one tag tab.
	 */
	public enum TagState
	{
		/** The tab has no sync identity (sync is off or the tab was never observed). */
		LOCAL_ONLY,
		/** Local content matches the last state exchanged with the server. */
		SYNCED,
		/** Local changes are waiting to be uploaded (debounce, in flight, or offline). */
		PENDING,
		/** Remote and local diverged; a {@code syncConflict_} record exists and the user must choose. */
		CONFLICTED,
		/** The server refused the last upload with a non-retryable error; retry after changing the tag. */
		REJECTED
	}
}
