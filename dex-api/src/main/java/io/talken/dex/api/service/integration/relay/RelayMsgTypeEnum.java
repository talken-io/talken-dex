package io.talken.dex.api.service.integration.relay;

public enum RelayMsgTypeEnum {
	ANCHOR("2003"),
	DEANCHOR("2004"),
	CREATEOFFER("2005"),
	DELETEOFFER("2006"),
	SWAP("2007");

	private final String msgType;

	RelayMsgTypeEnum(String msgType) {
		this.msgType = msgType;
	}

	public String getMsgType() {
		return msgType;
	}
}
