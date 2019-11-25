package io.talken.dex.governance.service.bctx.monitor.ethereum;


import io.talken.common.persistence.enums.BlockChainPlatformEnum;
import io.talken.common.persistence.jooq.tables.records.BctxRecord;
import io.talken.common.persistence.jooq.tables.records.DexTaskAnchorRecord;
import io.talken.common.util.PrefixedLogger;
import io.talken.dex.governance.service.TokenMeta;
import io.talken.dex.governance.service.TokenMetaGovService;
import io.talken.dex.governance.service.bctx.TxMonitor;
import io.talken.dex.shared.TransactionBlockExecutor;
import io.talken.dex.shared.service.blockchain.ethereum.EthereumTxReceipt;
import io.talken.dex.shared.service.blockchain.stellar.StellarConverter;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.web3j.utils.Convert;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static io.talken.common.persistence.jooq.Tables.DEX_TASK_ANCHOR;

public abstract class AbstractEthereumAnchorReceiptHandler implements TxMonitor.ReceiptHandler<EthereumTxReceipt> {
	private PrefixedLogger logger;

	@Autowired
	private DSLContext dslContext;

	@Autowired
	private TokenMetaGovService tmService;

	@Autowired
	private DataSourceTransactionManager txMgr;

	public AbstractEthereumAnchorReceiptHandler(PrefixedLogger logger) {
		this.logger = logger;
	}

	abstract protected Condition getBcTypeCondition(String contractAddr);

	@Override
	public void handle(EthereumTxReceipt receipt) throws Exception {
		// convert amount to actual
		BigDecimal amountValue = new BigDecimal(receipt.getValue());
		BigDecimal amount;
		if(receipt.getTokenDecimal() != null) {
			amount = amountValue.divide(BigDecimal.TEN.pow(Integer.valueOf(receipt.getTokenDecimal())), RoundingMode.FLOOR);
		} else {
			amount = Convert.fromWei(amountValue.toString(), Convert.Unit.ETHER);
		}
		amount = StellarConverter.scale(amount);

//		logger.verbose("{} {} {}", receipt.getValue(), receipt.getTokenDecimal(), amount.stripTrailingZeros().toPlainString());

		// return amount is smaller than zero
		if(amount.compareTo(BigDecimal.ZERO) <= 0) return;

		Condition condition = DEX_TASK_ANCHOR.BC_REF_ID.isNull()
				.and(DEX_TASK_ANCHOR.PRIVATEADDR.eq(receipt.getFrom()).and(DEX_TASK_ANCHOR.HOLDERADDR.eq(receipt.getTo())).and(DEX_TASK_ANCHOR.AMOUNT.eq(amount)))
				.and(getBcTypeCondition(receipt.getContractAddress()));

		DexTaskAnchorRecord taskRecord = dslContext.selectFrom(DEX_TASK_ANCHOR)
				.where(condition)
				.orderBy(DEX_TASK_ANCHOR.CREATE_TIMESTAMP.desc()) // older request first
				.limit(1)
				.fetchOne();

		// finish if task not found
		if(taskRecord == null) return;

		taskRecord.setBcRefId(receipt.getHash());

		TokenMeta.ManagedInfo tm = tmService.getManaged(taskRecord.getAssetcode());

		BctxRecord bctxRecord = new BctxRecord();
		bctxRecord.setBctxType(BlockChainPlatformEnum.STELLAR_TOKEN);
		bctxRecord.setSymbol(taskRecord.getAssetcode());
		bctxRecord.setPlatformAux(tm.getIssueraddress());
		bctxRecord.setAddressFrom(tm.getIssueraddress());
		bctxRecord.setAddressTo(taskRecord.getTradeaddr());
		bctxRecord.setAmount(amount);
		bctxRecord.setNetfee(BigDecimal.ZERO);
		dslContext.attach(bctxRecord);

		TransactionBlockExecutor.of(txMgr).transactional(() -> {
			bctxRecord.store();
			taskRecord.setIssueBctxId(bctxRecord.getId());
			logger.info("Anchor task {} issuing bctx queued : #{}", taskRecord.getTaskid(), bctxRecord.getId());
			taskRecord.store();
		});
	}
}
