package io.talken.dex.governance.service.bctx.monitor.stellar;

import io.talken.common.persistence.enums.BctxStatusEnum;
import io.talken.common.persistence.enums.BlockChainPlatformEnum;
import io.talken.common.persistence.jooq.tables.records.BctxRecord;
import io.talken.common.persistence.jooq.tables.records.DexTaskAnchorRecord;
import io.talken.common.util.PrefixedLogger;
import io.talken.dex.governance.service.TokenMetaGovService;
import io.talken.dex.governance.service.bctx.TxMonitor;
import io.talken.dex.governance.service.bctx.monitor.AbstractAnchorReceiptHandler;
import io.talken.dex.shared.TokenMetaTable;
import io.talken.dex.shared.TransactionBlockExecutor;
import io.talken.dex.shared.service.blockchain.stellar.StellarConverter;
import io.talken.dex.shared.service.blockchain.stellar.StellarTransferReceipt;
import io.talken.dex.shared.service.blockchain.stellar.StellarTxReceipt;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;

import static io.talken.common.persistence.jooq.Tables.DEX_TASK_ANCHOR;

@Service
@Scope("singleton")
public class StellarAnchorReceiptHandler extends AbstractAnchorReceiptHandler implements TxMonitor.ReceiptHandler<Void, StellarTxReceipt, StellarTransferReceipt> {
	private static final PrefixedLogger logger = PrefixedLogger.getLogger(StellarAnchorReceiptHandler.class);

	@Autowired
	private DSLContext dslContext;

	@Autowired
	private TokenMetaGovService tmService;

	@Autowired
	private DataSourceTransactionManager txMgr;

	@Autowired
	private StellarTxMonitor txMonitor;

	public StellarAnchorReceiptHandler() {
		addBcType(BlockChainPlatformEnum.STELLAR);
		addBcType(BlockChainPlatformEnum.STELLAR_TOKEN);
	}

	@PostConstruct
	private void init() {
		txMonitor.addReceiptHandler(this);
	}

	@Override
	public void handle(Void _void, StellarTxReceipt txResult, StellarTransferReceipt receipt) throws Exception {
		// check transfer is to holder
		if(!checkHolder(receipt.getTo())) return;
		logger.info("Transfer to holder detected : {} -> {} : {} {}({})", receipt.getFrom(), receipt.getTo(), receipt.getAmountRaw(), receipt.getTokenSymbol(), receipt.getTokenIssuer());

		// convert amount to stellar raw
		BigDecimal amount = StellarConverter.rawToActual(receipt.getAmountRaw());

		Condition condition = DEX_TASK_ANCHOR.BC_REF_ID.isNull()
				.and(DEX_TASK_ANCHOR.PRIVATEADDR.eq(receipt.getFrom()).and(DEX_TASK_ANCHOR.HOLDERADDR.eq(receipt.getTo())).and(DEX_TASK_ANCHOR.AMOUNT.eq(amount)));

		if(receipt.getTokenIssuer() == null) {
			condition = condition.and(DEX_TASK_ANCHOR.BCTX_TYPE.eq(BlockChainPlatformEnum.STELLAR));
		} else {
			condition = condition.and(DEX_TASK_ANCHOR.BCTX_TYPE.eq(BlockChainPlatformEnum.STELLAR_TOKEN).and(DEX_TASK_ANCHOR.PLATFORM_AUX.eq(receipt.getTokenIssuer())));
		}

		DexTaskAnchorRecord taskRecord = dslContext.selectFrom(DEX_TASK_ANCHOR)
				.where(condition)
				.orderBy(DEX_TASK_ANCHOR.CREATE_TIMESTAMP.desc()) // older request first
				.limit(1)
				.fetchOne();

		// finish if task not found
		if(taskRecord == null) return;

		taskRecord.setBcRefId(receipt.getHash());

		TokenMetaTable.ManagedInfo tm = tmService.getManagedInfo(taskRecord.getAssetcode());

		BctxRecord bctxRecord = new BctxRecord();
		bctxRecord.setStatus(BctxStatusEnum.QUEUED);
		bctxRecord.setBctxType(BlockChainPlatformEnum.STELLAR_TOKEN);
		bctxRecord.setSymbol(taskRecord.getAssetcode());
		bctxRecord.setPlatformAux(tm.getIssuerAddress());
		bctxRecord.setAddressFrom(tm.getIssuerAddress());
		bctxRecord.setAddressTo(taskRecord.getTradeaddr());
		bctxRecord.setAmount(amount);
		bctxRecord.setNetfee(BigDecimal.ZERO);
		bctxRecord.setTxAux(taskRecord.getTaskid());
		dslContext.attach(bctxRecord);

		TransactionBlockExecutor.of(txMgr).transactional(() -> {
			bctxRecord.store();
			taskRecord.setIssueBctxId(bctxRecord.getId());
			logger.info("Anchor task {} issuing bctx queued : #{}", taskRecord.getTaskid(), bctxRecord.getId());
			taskRecord.store();
		});
	}
}
