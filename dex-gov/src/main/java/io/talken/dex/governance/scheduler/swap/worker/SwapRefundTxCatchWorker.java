package io.talken.dex.governance.scheduler.swap.worker;

import io.talken.common.persistence.enums.DexSwapStatusEnum;
import io.talken.common.persistence.jooq.tables.records.DexTaskSwapRecord;
import io.talken.dex.governance.scheduler.swap.SwapTaskWorker;
import io.talken.dex.governance.scheduler.swap.WorkerProcessResult;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Scope("singleton")
@RequiredArgsConstructor
public class SwapRefundTxCatchWorker extends SwapTaskWorker {
	@Override
	protected int getRetryCount() {
		return 0;
	}

	@Override
	protected Duration getRetryInterval() {
		return null;
	}

	@Override
	public DexSwapStatusEnum getStartStatus() {
		return DexSwapStatusEnum.REFUND_DEANCHOR_TX_CATCH;
	}

	@Override
	public WorkerProcessResult proceed(DexTaskSwapRecord record) {
		// TODO : confirm deanchor server and mark success
		record.setStatus(DexSwapStatusEnum.REFUND_DEANCHOR_ANCSVR_CONFIRMED);
		record.setFinishFlag(true);
		record.update();
		return new WorkerProcessResult.Builder(this, record).success();
	}
}
