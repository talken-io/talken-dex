package io.talken.dex.governance.service.bctx.monitor.stellar;

import io.talken.common.RunningProfile;
import io.talken.common.persistence.enums.BlockChainPlatformEnum;
import io.talken.common.persistence.enums.DexTaskTypeEnum;
import io.talken.common.persistence.jooq.tables.records.DexTxmonRecord;
import io.talken.common.service.ServiceStatusService;
import io.talken.common.util.PrefixedLogger;
import io.talken.common.util.integration.slack.AdminAlarmService;
import io.talken.dex.governance.DexGovStatus;
import io.talken.dex.governance.service.bctx.TxMonitor;
import io.talken.dex.shared.service.blockchain.stellar.StellarConverter;
import io.talken.dex.shared.service.blockchain.stellar.StellarNetworkService;
import io.talken.dex.shared.service.blockchain.stellar.StellarOpReceipt;
import io.talken.dex.shared.service.blockchain.stellar.StellarTxReceipt;
import org.jooq.DSLContext;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Scope;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.stellar.sdk.Server;
import org.stellar.sdk.requests.ErrorResponse;
import org.stellar.sdk.requests.RequestBuilder;
import org.stellar.sdk.responses.Page;
import org.stellar.sdk.responses.TransactionResponse;
import org.stellar.sdk.xdr.OperationType;

import javax.annotation.PostConstruct;
import java.math.BigInteger;
import java.util.*;

import static io.talken.common.persistence.jooq.Tables.DEX_TXMON;

/**
 * Stellar Tx Monitor
 * NOTE : stellar tx monitor is implemented with transaction, not ledger(block)
 */
@Service
@Scope("singleton")
public class StellarTxMonitor extends TxMonitor<Void, StellarTxReceipt, StellarOpReceipt> implements ApplicationContextAware {
	private static final PrefixedLogger logger = PrefixedLogger.getLogger(StellarTxMonitor.class);

	private ApplicationContext applicationContext;

	@Autowired
	private StellarNetworkService stellarNetworkService;

	@Autowired
	private ServiceStatusService<DexGovStatus> ssService;

	@Autowired
	private DSLContext dslContext;

	@Autowired
	private MongoTemplate mongoTemplate;

	@Autowired
	private AdminAlarmService adminAlarmService;

	/**
	 * hold TaskProcessors for PostProcessing DexTask after receipt arrived
	 */
	private HashMap<DexTaskTypeEnum, DexTaskTransactionProcessor> processors = new HashMap<>();

	private static final String COLLECTION_NAME = "stellar_opReceipt";

	private static final int TXREQUEST_LIMIT = 200;

	@PostConstruct
	private void init() {
		// reset status if local
		if(RunningProfile.isLocal()) {
			ssService.status().getTxMonitor().getStellar().setLastPagingToken(null);
			ssService.save();

			mongoTemplate.dropCollection(COLLECTION_NAME);
		}

		if(!mongoTemplate.collectionExists(COLLECTION_NAME)) {
			mongoTemplate.createCollection(COLLECTION_NAME);
			mongoTemplate.indexOps(COLLECTION_NAME).ensureIndex(new Index().on("timeStamp", Sort.Direction.DESC));
			mongoTemplate.indexOps(COLLECTION_NAME).ensureIndex(new Index().on("hash", Sort.Direction.ASC));
			mongoTemplate.indexOps(COLLECTION_NAME).ensureIndex(new Index().on("taskId", Sort.Direction.ASC));
			mongoTemplate.indexOps(COLLECTION_NAME).ensureIndex(new Index().on("operationType", Sort.Direction.ASC));
			mongoTemplate.indexOps(COLLECTION_NAME).ensureIndex(new Index().on("involvedAccounts", Sort.Direction.ASC));
			mongoTemplate.indexOps(COLLECTION_NAME).ensureIndex(new Index().on("involvedAssets", Sort.Direction.ASC));
		}

		Map<String, DexTaskTransactionProcessor> ascBeans = applicationContext.getBeansOfType(DexTaskTransactionProcessor.class);
		ascBeans.forEach((_name, _asc) -> {
			processors.put(_asc.getDexTaskType(), _asc);
			logger.info("DexTaskTransactionProcessor for [{}] registered.", _asc.getDexTaskType());
		});
	}

	@Override
	public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	/**
	 * check new transaction every 3 seconds
	 */
	@Scheduled(fixedDelay = 1000, initialDelay = 5000)
	private void checkTask() {
		if(DexGovStatus.isStopped) return;

		int processed = -1;
		do {
			processed = processNextTransactions();

			if(processed < 0) break; // break if error occured while processing
		} while(processed == TXREQUEST_LIMIT && !DexGovStatus.isStopped);
	}

	@Override
	public BlockChainPlatformEnum[] getBcTypes() {
		return new BlockChainPlatformEnum[]{BlockChainPlatformEnum.STELLAR, BlockChainPlatformEnum.STELLAR_TOKEN};
	}

	@Override
	protected StellarTxReceipt getTransactionReceipt(String txId) {
		Server server = stellarNetworkService.pickServer();

		try {
			return new StellarTxReceipt(server.transactions().transaction(txId), stellarNetworkService.getNetwork());
		} catch(ErrorResponse ex) {
			if(ex.getCode() == 404) logger.debug("Stellar Tx {} is not found.", txId);
			else logger.exception(ex);
		} catch(Exception ex) {
			logger.exception(ex);
		}
		return null;
	}

	private int processNextTransactions() {
		Optional<String> opt_status = Optional.ofNullable(ssService.status().getTxMonitor().getStellar().getLastPagingToken());

		Server server = stellarNetworkService.pickServer();

		Page<TransactionResponse> txPage;
		try {
			if(opt_status.isPresent()) {
				// 200 is maximum
				txPage = server.transactions().order(RequestBuilder.Order.ASC).cursor(opt_status.get()).limit(TXREQUEST_LIMIT).includeFailed(false).execute();
			} else {
				// get last tx for initiation
				logger.info("Stellar tx collection not found, collect last page for initial data.");
				txPage = server.transactions().order(RequestBuilder.Order.DESC).limit(1).includeFailed(false).execute();
			}
		} catch(Exception ex) {
			logger.exception(ex, "Cannot get last tx from stellar network.");
			return -1;
		}

		return processTransactionPage(txPage);
	}
//
//	private int processTransactionPage(Page<TransactionResponse> txPage) {
//		int processed = 0;
//
//		String firstToken = null;
//		String lastToken = null;
//		long lastLedger = 0;
//		Long receipts = 0L;
//
//		long saveTakes = 0;
//		final long started = System.currentTimeMillis();
//
//		for(TransactionResponse txRecord : txPage.getRecords()) {
//			try {
//				lastToken = txRecord.getPagingToken();
//				lastLedger = txRecord.getLedger();
//				if(firstToken == null) firstToken = lastToken;
//
//				StellarTxReceipt txResult = new StellarTxReceipt(txRecord, stellarNetworkService.getNetwork());
//
//				callTxHandlerStack(null, txResult);
//
//				// call dex task post-processor
//				processDexTask(txResult);
//
//				List<StellarOpReceipt> opReceipts = txResult.getOpReceipts();
//
//				for(StellarOpReceipt opReceipt : opReceipts) {
//					if(opReceipt.getOperationType().equals(OperationType.PAYMENT)) {
//						callReceiptHandlerStack(null, txResult, opReceipt);
//					}
//				}
//
//				final long saveStarted = System.currentTimeMillis();
//				mongoTemplate.insert(opReceipts, COLLECTION_NAME);
//				saveTakes += System.currentTimeMillis() - saveStarted;
//
//				checkChainNetworkNode(new BigInteger(txRecord.getPagingToken()));
//
//				ssService.status().getTxMonitor().getStellar().setLastPagingToken(txRecord.getPagingToken());
//				ssService.status().getTxMonitor().getStellar().setLastTokenTimestamp(StellarConverter.toLocalDateTime(txRecord.getCreatedAt()));
//				ssService.save();
//
//				receipts += opReceipts.size();
//
//				processed++;
//			} catch(Exception ex) {
//				logger.exception(ex, "Unidentified exception occured.");
//			}
//		}
//		final long takes = System.currentTimeMillis() - started;
//
//		if(processed > 0)
//			logger.info("{} : LEDGER={}, PAGINGTOKEN = {}, RECEIPTS = {} ({} ms / save {} ms)", "Stellar", lastLedger, lastToken, receipts, takes, saveTakes);
//
//		return processed;
//	}

	private int processTransactionPage(Page<TransactionResponse> txPage) {
		int processed = 0;

		TransactionResponse lastSuccessTransaction = null;
		List<StellarOpReceipt> allReceipts = new ArrayList<>();

		final long started = System.currentTimeMillis();
		try {
			for(TransactionResponse txRecord : txPage.getRecords()) {
				StellarTxReceipt txResult = new StellarTxReceipt(txRecord, stellarNetworkService.getNetwork());

				callTxHandlerStack(null, txResult);

				// call dex task post-processor
				processDexTask(txResult);

				List<StellarOpReceipt> opReceipts = txResult.getOpReceipts();

				for(StellarOpReceipt opReceipt : opReceipts) {
					if(opReceipt.getOperationType().equals(OperationType.PAYMENT)) {
						callReceiptHandlerStack(null, txResult, opReceipt);
						allReceipts.add(opReceipt);
					}
				}

				checkChainNetworkNode(new BigInteger(txRecord.getPagingToken()));

				lastSuccessTransaction = txRecord;
				processed++;
			}
		} catch(Exception ex) {
			processed = processed * -1; // mark error occured
			adminAlarmService.exception(logger, ex, "Error occured while monitor processing stellar transaction");
		}

		final long saveStarted = System.currentTimeMillis();
		mongoTemplate.insert(allReceipts, COLLECTION_NAME);
		final long saveTakes = System.currentTimeMillis() - saveStarted;

		if(lastSuccessTransaction != null) {
			ssService.status().getTxMonitor().getStellar().setLastPagingToken(lastSuccessTransaction.getPagingToken());
			ssService.status().getTxMonitor().getStellar().setLastTokenTimestamp(StellarConverter.toLocalDateTime(lastSuccessTransaction.getCreatedAt()));
			ssService.save();
		}

		final long takes = System.currentTimeMillis() - started;

		if(processed > 0)
			logger.info("{} : LEDGER={}, PAGINGTOKEN = {}, RECEIPTS = {} ({} ms / save {} ms)", "Stellar", lastSuccessTransaction.getLedger(), lastSuccessTransaction.getPagingToken(), allReceipts.size(), takes, saveTakes);

		return processed;
	}


	public void processDexTask(StellarTxReceipt txResult) {
		if(txResult.getTaskId() == null) return;

		Integer count = dslContext.selectCount().from(DEX_TXMON).where(DEX_TXMON.TXHASH.eq(txResult.getResponse().getHash())).fetchOneInto(Integer.class);

		if(count > 0) return;

		DexTxmonRecord txmRecord = new DexTxmonRecord();
		txmRecord.setTxid(txResult.getResponse().getHash());
		txmRecord.setMemotaskid(txResult.getTaskId().getId());
		txmRecord.setTasktype(txResult.getTaskId().getType());
		txmRecord.setTxhash(txResult.getResponse().getHash());
		txmRecord.setLedger(txResult.getResponse().getLedger());
		txmRecord.setCreatedat(StellarConverter.toLocalDateTime(txResult.getResponse().getCreatedAt()));
		txmRecord.setSourceaccount(txResult.getResponse().getSourceAccount());
		txmRecord.setEnvelopexdr(txResult.getResponse().getEnvelopeXdr());
		txmRecord.setResultxdr(txResult.getResponse().getResultXdr());
		txmRecord.setResultmetaxdr(txResult.getResponse().getResultMetaXdr());
		txmRecord.setFeepaid(txResult.getResponse().getFeeCharged());
		dslContext.attach(txmRecord);
		txmRecord.store();

		try {
			// run processor
			if(processors.containsKey(txResult.getTaskId().getType())) {

				DexTaskTransactionProcessor taskTransactionProcessor = processors.get(txResult.getTaskId().getType());
				DexTaskTransactionProcessResult result;
				try {
					logger.info("Catched {} transaction ({}) => {}", txResult.getTaskId(), txResult.getTxHash(), taskTransactionProcessor.getClass().getSimpleName());
					result = taskTransactionProcessor.process(txmRecord.getId(), txResult);
				} catch(Exception ex) {
					result = DexTaskTransactionProcessResult.error("Unknown", ex);
				}

				if(result.isSuccess()) {
					txmRecord.setProcessSuccessFlag(true);
				} else {
					logger.error("{} transaction {} result process error : {} {}", txResult.getTaskId(), txResult.getTxHash(), result.getError().getCode(), result.getError().getMessage());

					// log exception
					if(result.getError().getCause() != null)
						logger.exception(result.getError().getCause());

					txmRecord.setProcessSuccessFlag(false);
					txmRecord.setErrorcode(result.getError().getCode());
					txmRecord.setErrormessage(result.getError().getMessage());
				}

				txmRecord.update();
			} else {
				logger.verbose("No TaskTransactionProcessor for {} registered", txResult.getTaskId().getType());
			}
		} catch(Exception ex) {
			logger.exception(ex);
			txmRecord.setProcessSuccessFlag(false);
			txmRecord.setErrorcode(ex.getClass().getSimpleName());
			txmRecord.setErrormessage(ex.getMessage());
		}

		txmRecord.update();
	}


	@Override
	protected TxReceipt toTxMonitorReceipt(StellarTxReceipt txResult) {

		Map<String, Object> receiptObj = new HashMap<>();

		receiptObj.put("txHash", txResult.getResponse().getHash());
		receiptObj.put("ledger", txResult.getResponse().getLedger());
		receiptObj.put("resultXdr", txResult.getResponse().getResultXdr());
		receiptObj.put("envelopeXdr", txResult.getResponse().getEnvelopeXdr());
		receiptObj.put("resultMetaXdr", txResult.getResponse().getResultMetaXdr());
		receiptObj.put("feePaid", txResult.getResponse().getFeeCharged());
		receiptObj.put("sourceAccount", txResult.getResponse().getSourceAccount());
		receiptObj.put("createdAt", txResult.getResponse().getCreatedAt());

		if(txResult.getResponse().isSuccessful()) {
			return TxReceipt.ofSuccessful(txResult.getResponse().getHash(), receiptObj);
		} else {
			return TxReceipt.ofFailed(txResult.getResponse().getHash(), receiptObj);
		}
	}
}
