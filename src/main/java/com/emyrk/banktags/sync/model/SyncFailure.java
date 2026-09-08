package com.emyrk.banktags.sync.model;

import javax.annotation.Nullable;

/**
 * Immutable description of a failed sync request. {@code httpStatus} is {@code 0} for
 * {@link Kind#NETWORK} and {@link Kind#INVALID_RESPONSE}. {@code errorCode} is the {@code error}
 * field of the protocol error body when one was present. {@code currentTag} or
 * {@code currentManifest} carry the {@code current} field of a {@code 409}: a tag for tag routes,
 * a manifest for the order route.
 */
public final class SyncFailure
{
	public enum Kind
	{
		NETWORK,
		INVALID_RESPONSE,
		UNAUTHORIZED,
		BAD_REQUEST,
		NOT_FOUND,
		CONFLICT,
		PRECONDITION_REQUIRED,
		PAYLOAD_TOO_LARGE,
		SERVER_ERROR
	}

	private final Kind kind;
	private final int httpStatus;
	@Nullable
	private final String errorCode;
	@Nullable
	private final String message;
	@Nullable
	private final SharedBankTag currentTag;
	@Nullable
	private final BankTagManifest currentManifest;

	public SyncFailure(Kind kind, int httpStatus, @Nullable String errorCode, @Nullable String message,
		@Nullable SharedBankTag currentTag, @Nullable BankTagManifest currentManifest)
	{
		this.kind = kind;
		this.httpStatus = httpStatus;
		this.errorCode = errorCode;
		this.message = message;
		this.currentTag = currentTag;
		this.currentManifest = currentManifest;
	}

	public static SyncFailure network(@Nullable String message)
	{
		return new SyncFailure(Kind.NETWORK, 0, null, message, null, null);
	}

	public static SyncFailure invalidResponse(@Nullable String message)
	{
		return new SyncFailure(Kind.INVALID_RESPONSE, 0, null, message, null, null);
	}

	/**
	 * Maps an HTTP status to a failure kind. Unlisted 4xx codes are reported as {@link Kind#BAD_REQUEST};
	 * every other unexpected status is reported as {@link Kind#SERVER_ERROR}.
	 */
	public static Kind kindForStatus(int status)
	{
		switch (status)
		{
			case 400:
				return Kind.BAD_REQUEST;
			case 401:
				return Kind.UNAUTHORIZED;
			case 404:
				return Kind.NOT_FOUND;
			case 409:
				return Kind.CONFLICT;
			case 413:
				return Kind.PAYLOAD_TOO_LARGE;
			case 428:
				return Kind.PRECONDITION_REQUIRED;
			default:
				if (status >= 400 && status < 500)
				{
					return Kind.BAD_REQUEST;
				}
				return Kind.SERVER_ERROR;
		}
	}

	public Kind getKind()
	{
		return kind;
	}

	public int getHttpStatus()
	{
		return httpStatus;
	}

	@Nullable
	public String getErrorCode()
	{
		return errorCode;
	}

	@Nullable
	public String getMessage()
	{
		return message;
	}

	@Nullable
	public SharedBankTag getCurrentTag()
	{
		return currentTag;
	}

	@Nullable
	public BankTagManifest getCurrentManifest()
	{
		return currentManifest;
	}

	@Override
	public String toString()
	{
		return "SyncFailure{kind=" + kind + ", httpStatus=" + httpStatus + ", errorCode=" + errorCode + '}';
	}
}
