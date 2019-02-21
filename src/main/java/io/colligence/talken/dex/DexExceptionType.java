package io.colligence.talken.dex;

import io.colligence.talken.common.CLGException;

public enum DexExceptionType implements CLGException.ExceptionType {
	INTERNAL_SERVER_ERROR(0),
	UNAUTHORIZED(1),
	PARAMETER_VIOLATION(2),
	TASK_NOT_FOUND(3),
	STELLAR_EXCEPTION(4),
	API_RETURNED_ERROR(5),
	TASK_INTEGRITY_CHECK_FAILED(6),
	ACCOUNT_NOT_FOUND(7),
	TX_HASH_NOT_MATCH(8),
	TRANSACTION_RESULT_PROCESSING_ERROR(9),
	SIGNATURE_VERIFICATION_FAILED(10),
	ASSET_CONVERT_ERROR(11),
	// FROM MAS
	TOKEN_META_DATA_NOT_FOUND(50),
	CANNOT_UPDATE_HOLDER_STATUS(51),
	ACTIVE_ASSET_HOLDER_NOT_FOUND(52),
	SIGNING_ERROR(53);

	private final int eCode;
	private final String messageKey;

	DexExceptionType(int eCode) {
		this.eCode = CLGException.buildErrorCode(CLGException.Module.DEX, eCode);
		this.messageKey = CLGException.buildMessageKey(CLGException.Module.DEX.toString(), this.toString());
	}

	@Override
	public int getCode() {
		return eCode;
	}

	@Override
	public String getMessageKey() {
		return messageKey;
	}
}
