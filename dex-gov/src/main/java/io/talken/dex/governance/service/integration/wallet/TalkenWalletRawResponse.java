package io.talken.dex.governance.service.integration.wallet;

import io.talken.common.util.integration.rest.StringRestApiResponse;

public class TalkenWalletRawResponse extends StringRestApiResponse {
	@Override
	public boolean checkHttpResponse(int httpStatus) {
		return true; // always true
	}

	@Override
	public boolean checkResult() {
		return true;
	}

	@Override
	public String resultCode() {
		return "";
	}

	@Override
	public String resultMessage() {
		return getData();
	}
}
