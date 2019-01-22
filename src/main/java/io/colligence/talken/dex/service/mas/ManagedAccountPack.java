package io.colligence.talken.dex.service.mas;

import io.colligence.talken.common.util.collection.SingleKeyObject;
import lombok.Data;
import org.stellar.sdk.AssetTypeCreditAlphaNum4;
import org.stellar.sdk.KeyPair;

import java.util.List;

@Data
public class ManagedAccountPack implements SingleKeyObject<String> {
	private String code;
	private KeyPair assetIssuer;
	private KeyPair assetBase;
	private List<String> assetHolder;
	private KeyPair offerFeeHolder;
	private KeyPair deanchorFeeHolder;
	private AssetTypeCreditAlphaNum4 assetType;

	@Override
	public String __getSKey__() {
		return this.code;
	}
}
