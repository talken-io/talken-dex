package io.talken.dex.governance.service.bctx.monitor.stellar.dextask;

import io.talken.dex.shared.DexTaskId;
import io.talken.dex.shared.service.blockchain.stellar.StellarXdrDecoder;
import org.stellar.sdk.responses.TransactionResponse;
import org.stellar.sdk.xdr.*;

public class TaskTransactionResponse {

	private DexTaskId taskId;
	private TransactionResponse response;
	//	private Transaction tx;
	private TransactionResult result;

	public TaskTransactionResponse(DexTaskId taskId, TransactionResponse response) {
		this.taskId = taskId;
		this.response = response;
		parse();
	}

	private void parse() throws TaskTransactionProcessError {
//		try {
//			// build bare xdr
//			tx = Transaction.fromEnvelopeXdr(response.getEnvelopeXdr());
//		} catch(Exception ex) {
//			throw new TaskTransactionProcessError("EnvelopeDecodeError", ex);
//		}

		try {
			// decode result
			this.result = StellarXdrDecoder.decodeResultXdr(response);
		} catch(Exception ex) {
			throw new TaskTransactionProcessError("ResultDecodeError", ex);
		}
	}

	public DexTaskId getTaskId() {
		return taskId;
	}

	public String getTxHash() {return response.getHash();}

	public TransactionResponse getResponse() {
		return response;
	}
//
//	public Transaction getTx() {
//		return tx;
//	}

	public TransactionResult getResult() {
		return result;
	}


	// FIXME : check when applying stellar-sdk 0.9.0
	public Long getOfferIdFromResult() {
		if(result != null) {
			if(result.getResult() == null) return null;
			if(result.getResult().getResults() == null || result.getResult().getResults().length == 0) return null;

			// extract feeResult and offerResult
			ManageSellOfferResult offerResult = null;
			for(OperationResult operationResult : result.getResult().getResults()) {
				if(operationResult.getTr().getDiscriminant() == OperationType.MANAGE_SELL_OFFER) {
					offerResult = operationResult.getTr().getManageSellOfferResult();
				}
			}
			if(offerResult == null) return null;

			if(offerResult.getSuccess() == null) return null;
			if(offerResult.getSuccess().getOffer() == null) return null;
			if(offerResult.getSuccess().getOffer().getOffer() == null) return null;

			return offerResult.getSuccess().getOffer().getOffer().getOfferID().getInt64();

		} else return null;
	}
}
