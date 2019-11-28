package io.talken.dex.governance.scheduler.swap.worker;

import ch.qos.logback.core.encoder.ByteArrayUtil;
import io.talken.common.persistence.enums.DexSwapStatusEnum;
import io.talken.common.persistence.enums.DexTaskTypeEnum;
import io.talken.common.persistence.jooq.tables.records.DexTaskSwapRecord;
import io.talken.common.util.GSONWriter;
import io.talken.common.util.UTCUtil;
import io.talken.common.util.collection.ObjectPair;
import io.talken.common.util.integration.IntegrationResult;
import io.talken.dex.governance.scheduler.swap.SwapTaskWorker;
import io.talken.dex.governance.scheduler.swap.WorkerProcessResult;
import io.talken.dex.governance.service.TokenMeta;
import io.talken.dex.shared.DexTaskId;
import io.talken.dex.shared.exception.SigningException;
import io.talken.dex.shared.service.blockchain.stellar.StellarChannelTransaction;
import io.talken.dex.shared.service.blockchain.stellar.StellarConverter;
import io.talken.dex.shared.service.blockchain.stellar.StellarSignerTSS;
import io.talken.dex.shared.service.integration.anchor.AncServerDeanchorRequest;
import io.talken.dex.shared.service.integration.anchor.AncServerDeanchorResponse;
import io.talken.dex.shared.service.integration.anchor.AnchorServerService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.stellar.sdk.PaymentOperation;
import org.stellar.sdk.responses.SubmitTransactionResponse;

import java.io.IOException;
import java.time.Duration;

// Service Disabled [TKN-1426]
//@Component
@Scope("singleton")
@RequiredArgsConstructor
public class SwapRefundWorker extends SwapTaskWorker {
	private final AnchorServerService anchorServerService;

	@Override
	protected int getRetryCount() {
		return 5;
	}

	@Override
	protected Duration getRetryInterval() {
		return Duration.ofMinutes(1);
	}

	@Override
	public DexSwapStatusEnum getStartStatus() {
		return DexSwapStatusEnum.REFUND_DEANCHOR_QUEUED;
	}

	@Override
	public WorkerProcessResult proceed(DexTaskSwapRecord record) {
		// generate dexTaskId
		DexTaskId dexTaskId = DexTaskId.generate_taskId(DexTaskTypeEnum.SWAP_REFUND);
		record.setDeancTaskid(dexTaskId.getId());

		TokenMeta.ManagedInfo sourceMeta;
		try {
			sourceMeta = tmService.getManaged(record.getSourceassetcode());
		} catch(Exception ex) {
			retryOrFail(record);
			return new WorkerProcessResult.Builder(this, record).exception("get meta", ex);
		}

		// build channel tx
		StellarChannelTransaction.Builder sctxBuilder;
		try {
			sctxBuilder = stellarNetworkService.newChannelTxBuilder().setMemo(dexTaskId.getId())
					.addOperation(
							new PaymentOperation.Builder(
									sourceMeta.getBaseaddress(),
									sourceMeta.getAssetType(),
									StellarConverter.rawToActualString(record.getSourceamountraw())
							)
									.setSourceAccount(record.getSwapperaddr())
									.build()
					).addSigner(new StellarSignerTSS(signServerService, record.getSwapperaddr()));
		} catch(Exception ex) {
			retryOrFail(record);
			return new WorkerProcessResult.Builder(this, record).exception("build ops", ex);
		}

		SubmitTransactionResponse txResponse;
		try(StellarChannelTransaction sctx = sctxBuilder.build()) {

			// request deanchor monitor
			AncServerDeanchorRequest deanchor_request = new AncServerDeanchorRequest();
			deanchor_request.setTaskId(dexTaskId.getId());
			deanchor_request.setUid(String.valueOf(record.getUserId()));
			deanchor_request.setSymbol(record.getSourceassetcode());
			deanchor_request.setHash(ByteArrayUtil.toHexString(sctx.getTx().hash()));
			deanchor_request.setFrom(record.getSwapperaddr());
			deanchor_request.setTo(sourceMeta.getBaseaddress());
			deanchor_request.setAddress(record.getPrivatesourceaddr());
			deanchor_request.setValue(StellarConverter.rawToActual(record.getSourceamountraw()).doubleValue());
			deanchor_request.setMemo(UTCUtil.getNow().toString());

			IntegrationResult<AncServerDeanchorResponse> deanchorResult = anchorServerService.requestDeanchor(deanchor_request);

			if(!deanchorResult.isSuccess()) {
				retryOrFail(record);
				return new WorkerProcessResult.Builder(this, record).error("request deanchor", deanchorResult.getErrorCode(), deanchorResult.getErrorMessage());
			}

			// update tx info before submit
			record.setRefundFlag(true);
			record.setStatus(DexSwapStatusEnum.REFUND_DEANCHOR_REQUESTED);
			record.setDeancTaskid(dexTaskId.getId());
			record.setDeancTxSeq(sctx.getTx().getSequenceNumber());
			record.setDeancTxHash(ByteArrayUtil.toHexString(sctx.getTx().hash()));
			record.setDeancTxEnvelopexdr(sctx.getTx().toEnvelopeXdrBase64());
			record.setDeancIndex(deanchorResult.getData().getData().getIndex());
			record.update();

			try {
				txResponse = sctx.submit();
			} catch(IOException ex) {
				retryOrFail(record);
				return new WorkerProcessResult.Builder(this, record).exception("submit tx", ex);
			}

		} catch(SigningException | IOException ex) {
			retryOrFail(record);
			return new WorkerProcessResult.Builder(this, record).exception("build tx", ex);
		}

		record.setDeancTxResultxdr(txResponse.getResultXdr().get());
		if(txResponse.getExtras() != null)
			record.setDeancTxResultextra(GSONWriter.toJsonStringSafe(txResponse.getExtras()));

		if(!txResponse.isSuccess()) {
			retryOrFail(record);
			ObjectPair<String, String> resultCodes = StellarConverter.getResultCodesFromExtra(txResponse);
			return new WorkerProcessResult.Builder(this, record).error("tx response", resultCodes.first(), resultCodes.second());
		}

		record.setStatus(DexSwapStatusEnum.REFUND_DEANCHOR_SENT);
		record.update();

		return new WorkerProcessResult.Builder(this, record).success();
	}

	private void retryOrFail(DexTaskSwapRecord record) {
		if(record.getDeancRetryCount() < getRetryCount()) {
			record.setStatus(DexSwapStatusEnum.REFUND_DEANCHOR_QUEUED);
			record.setDeancRetryCount(record.getDeancRetryCount() + 1);
			record.setScheduleTimestamp(UTCUtil.getNow().plus(getRetryInterval()));
		} else {
			record.setStatus(DexSwapStatusEnum.REFUND_DEANCHOR_FAILED);
			record.setScheduleTimestamp(null);
			record.setFinishFlag(true);
		}
		record.update();
	}
}
