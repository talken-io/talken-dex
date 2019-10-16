package io.talken.dex.governance.service.bctx.monitor.stellar.dextask.processor;

import io.talken.common.persistence.enums.DexTaskTypeEnum;
import io.talken.common.persistence.jooq.tables.records.DexTaskCreateofferRecord;
import io.talken.common.util.PrefixedLogger;
import io.talken.dex.governance.service.bctx.monitor.stellar.dextask.TaskTransactionProcessError;
import io.talken.dex.governance.service.bctx.monitor.stellar.dextask.TaskTransactionProcessResult;
import io.talken.dex.governance.service.bctx.monitor.stellar.dextask.TaskTransactionProcessor;
import io.talken.dex.governance.service.bctx.monitor.stellar.dextask.TaskTransactionResponse;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static io.talken.common.persistence.jooq.Tables.DEX_TASK_CREATEOFFER;

@Component
public class CreateOfferTaskTransactionProcessor implements TaskTransactionProcessor {
	private static final PrefixedLogger logger = PrefixedLogger.getLogger(CreateOfferTaskTransactionProcessor.class);

	@Autowired
	private DSLContext dslContext;

	@Override
	public DexTaskTypeEnum getDexTaskType() {
		return DexTaskTypeEnum.OFFER_CREATE_SELL;
	}

	@Override
	public TaskTransactionProcessResult process(Long txmId, TaskTransactionResponse taskTxResponse) {
		try {
			Optional<DexTaskCreateofferRecord> opt_taskRecord = dslContext.selectFrom(DEX_TASK_CREATEOFFER).where(DEX_TASK_CREATEOFFER.TASKID.eq(taskTxResponse.getTaskId().getId())).fetchOptional();

			if(!opt_taskRecord.isPresent())
				throw new TaskTransactionProcessError("TaskIdNotFound");

			DexTaskCreateofferRecord taskRecord = opt_taskRecord.get();

			// update task as signed tx catched
			taskRecord.setSignedTxCatchFlag(true);
			taskRecord.update();

		} catch(TaskTransactionProcessError error) {
			return TaskTransactionProcessResult.error(error);
		} catch(Exception ex) {
			return TaskTransactionProcessResult.error("Processing", ex);
		}
		return TaskTransactionProcessResult.success();
	}
}


