package io.colligence.talken.dex.exception;

import io.colligence.talken.dex.config.auth.AuthenticationException;

public class UnauthorizedException extends DexException {
	private static final long serialVersionUID = -7795823407692828677L;

	public UnauthorizedException(AuthenticationException cause) {
		super(cause, DexExceptionTypeEnum.UNAUTHORIZED, cause.getMessage());
	}
}
