package io.talken.dex.api.service;

import ch.qos.logback.core.encoder.ByteArrayUtil;
import io.talken.common.exception.TalkenException;
import io.talken.common.exception.common.DataIdNotFoundException;
import io.talken.common.exception.common.GeneralException;
import io.talken.common.exception.common.TokenMetaNotFoundException;
import io.talken.common.exception.common.TokenMetaNotManagedException;
import io.talken.common.persistence.DexTaskRecord;
import io.talken.common.persistence.enums.DexTaskTypeEnum;
import io.talken.common.persistence.jooq.tables.pojos.DexTaskCreateoffer;
import io.talken.common.persistence.jooq.tables.pojos.User;
import io.talken.common.persistence.jooq.tables.records.DexTaskCreateofferRecord;
import io.talken.common.persistence.jooq.tables.records.DexTaskDeleteofferRecord;
import io.talken.common.util.PrefixedLogger;
import io.talken.common.util.collection.ObjectPair;
import io.talken.dex.api.controller.dto.*;
import io.talken.dex.shared.DexSettings;
import io.talken.dex.shared.DexTaskId;
import io.talken.dex.shared.exception.*;
import io.talken.dex.shared.service.blockchain.stellar.*;
import io.talken.dex.shared.service.integration.signer.SignServerService;
import io.talken.dex.shared.service.tradewallet.TradeWalletInfo;
import io.talken.dex.shared.service.tradewallet.TradeWalletService;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.stellar.sdk.*;
import org.stellar.sdk.requests.ErrorResponse;
import org.stellar.sdk.responses.OfferResponse;
import org.stellar.sdk.responses.SubmitTransactionResponse;
import org.stellar.sdk.responses.SubmitTransactionTimeoutResponseException;
import org.stellar.sdk.xdr.OperationResult;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;

import static io.talken.common.persistence.jooq.Tables.DEX_TASK_CREATEOFFER;

/**
 * The type Offer service.
 */
@Service
@Scope("singleton")
@RequiredArgsConstructor
public class OfferService {
	private static final PrefixedLogger logger = PrefixedLogger.getLogger(OfferService.class);

	// constructor injections
	private final FeeCalculationService feeCalculationService;
	private final StellarNetworkService stellarNetworkService;
	private final TradeWalletService twService;
	private final TokenMetaApiService tmService;
	private final DSLContext dslContext;
	private final SignServerService signServerService;

    /**
     * get offer detail for given offerId from database, not stellar network
     *
     * @param offerId the offer id
     * @return offer detail
     * @throws DataIdNotFoundException           the data id not found exception
     * @throws TaskIntegrityCheckFailedException the task integrity check failed exception
     */
    public OfferDetailResult getOfferDetail(long offerId) throws DataIdNotFoundException, TaskIntegrityCheckFailedException {
		DexTaskCreateofferRecord offerRecord = dslContext.selectFrom(DEX_TASK_CREATEOFFER).where(DEX_TASK_CREATEOFFER.OFFERID.eq(offerId)).fetchOne();
		if(offerRecord == null) throw new DataIdNotFoundException(DexTaskCreateoffer.class, offerId);

		OfferDetailResult rtn = new OfferDetailResult();

		DexTaskId taskId = DexTaskId.decode_taskId(offerRecord.getTaskid());
		rtn.setTaskId(taskId.getId());
		rtn.setTaskType(taskId.getType());
		rtn.setOfferId(offerRecord.getOfferid());
		rtn.setTxHash(offerRecord.getTxHash());
		rtn.setSellAssetCode(offerRecord.getSellassetcode());
		rtn.setBuyAssetCode(offerRecord.getBuyassetcode());
		rtn.setAmount(offerRecord.getAmount());
		rtn.setPrice(offerRecord.getPrice());
		return rtn;
	}

    /**
     * create sell offer
     *
     * @param user    the user
     * @param request the request
     * @return create offer result
     * @throws TokenMetaNotFoundException         the token meta not found exception
     * @throws SigningException                   the signing exception
     * @throws StellarException                   the stellar exception
     * @throws EffectiveAmountIsNegativeException the effective amount is negative exception
     * @throws TradeWalletCreateFailedException   the trade wallet create failed exception
     * @throws TradeWalletRebalanceException      the trade wallet rebalance exception
     * @throws TokenMetaNotManagedException       the token meta not managed exception
     * @throws ParameterViolationException        the parameter violation exception
     */
    public CreateOfferResult createSellOffer(User user, CreateOfferRequest request) throws TokenMetaNotFoundException, SigningException, StellarException, EffectiveAmountIsNegativeException, TradeWalletCreateFailedException, TradeWalletRebalanceException, TokenMetaNotManagedException, ParameterViolationException {
		if(!request.getBuyAssetCode().equalsIgnoreCase(DexSettings.PIVOT_ASSET_CODE))
			throw new ParameterViolationException("Only " + DexSettings.PIVOT_ASSET_CODE + " is available for buying asset");
		return createOffer(user, true, request);
	}

    /**
     * create buy offer
     *
     * @param user    the user
     * @param request the request
     * @return create offer result
     * @throws TokenMetaNotFoundException         the token meta not found exception
     * @throws SigningException                   the signing exception
     * @throws StellarException                   the stellar exception
     * @throws EffectiveAmountIsNegativeException the effective amount is negative exception
     * @throws TradeWalletCreateFailedException   the trade wallet create failed exception
     * @throws TradeWalletRebalanceException      the trade wallet rebalance exception
     * @throws TokenMetaNotManagedException       the token meta not managed exception
     * @throws ParameterViolationException        the parameter violation exception
     */
    public CreateOfferResult createBuyOffer(User user, CreateOfferRequest request) throws TokenMetaNotFoundException, SigningException, StellarException, EffectiveAmountIsNegativeException, TradeWalletCreateFailedException, TradeWalletRebalanceException, TokenMetaNotManagedException, ParameterViolationException {
		if(!request.getSellAssetCode().equalsIgnoreCase(DexSettings.PIVOT_ASSET_CODE))
			throw new ParameterViolationException("Only " + DexSettings.PIVOT_ASSET_CODE + " is available for selling asset");
		return createOffer(user, false, request);
	}

	/**
	 * create offer
	 * 1. create task record
	 * 2. calculate offer fee
	 * 3. rebalance user trade wallet for offer
	 * 4. build offer (+ fee if buying) tx and send
	 * 5. record offer result
	 *
	 * @param user
	 * @param isSell
	 * @param request
	 * @return
	 * @throws TokenMetaNotFoundException
	 * @throws SigningException
	 * @throws StellarException
	 * @throws EffectiveAmountIsNegativeException
	 * @throws TradeWalletCreateFailedException
	 * @throws TradeWalletRebalanceException
	 * @throws TokenMetaNotManagedException
	 */
	private CreateOfferResult createOffer(User user, boolean isSell, CreateOfferRequest request)
            throws TokenMetaNotFoundException, SigningException, StellarException, EffectiveAmountIsNegativeException, TradeWalletCreateFailedException, TradeWalletRebalanceException, TokenMetaNotManagedException {
		final DexTaskTypeEnum taskType = (isSell) ? DexTaskTypeEnum.OFFER_CREATE_SELL : DexTaskTypeEnum.OFFER_CREATE_BUY;
		final DexTaskId dexTaskId = DexTaskId.generate_taskId(taskType);
		final TradeWalletInfo tradeWallet = twService.ensureTradeWallet(user);
		final long userId = user.getId();
		final BigDecimal amount = StellarConverter.scale(request.getAmount());
		final BigDecimal price = request.getPrice();

		String position;

		// create task record
		DexTaskCreateofferRecord taskRecord = new DexTaskCreateofferRecord();
		taskRecord.setTaskid(dexTaskId.getId());
		taskRecord.setUserId(userId);
		taskRecord.setTasktype(taskType);
		taskRecord.setTradeaddr(tradeWallet.getAccountId());
		taskRecord.setSellassetcode(request.getSellAssetCode());
		taskRecord.setBuyassetcode(request.getBuyAssetCode());
		taskRecord.setAmount(amount);
		taskRecord.setPrice(price);
		taskRecord.setFeebytalk(false);
		dslContext.attach(taskRecord);
		taskRecord.store();
		logger.info("{} generated. userId = {}", dexTaskId, userId);

		position = "calc_fee";
		CalculateFeeResult feeResult;
		try {
			// calculate fee
			if(isSell) {
				feeResult = feeCalculationService.calculateSellOfferFee(request.getSellAssetCode(), amount, price);
			} else {
				feeResult = feeCalculationService.calculateBuyOfferFee(request.getBuyAssetCode(), amount, price);
			}

			// set amount log
			taskRecord.setSellamount(feeResult.getSellAmount());
			taskRecord.setBuyamount(feeResult.getBuyAmount());
			taskRecord.setFeeassetcode(feeResult.getFeeAssetCode());
			taskRecord.setFeeamount(feeResult.getFeeAmount());
			taskRecord.setFeecollectoraddr(feeResult.getFeeHolderAccountAddress());
		} catch(TalkenException tex) {
			DexTaskRecord.writeError(taskRecord, position, tex);
			throw tex;
		}

		// Adjust native balance before offer
		position = "rebalance";
		try {
			StellarChannelTransaction.Builder sctxBuilder = stellarNetworkService.newChannelTxBuilder();

			ObjectPair<Boolean, BigDecimal> rebalanced;
			rebalanced = twService.addNativeBalancingOperation(sctxBuilder, tradeWallet, true, request.getSellAssetCode(), request.getBuyAssetCode(), DexSettings.PIVOT_ASSET_CODE);

			if(rebalanced.first()) {
				try {
					logger.debug("Rebalance trade wallet {} (#{}) for offer task.", tradeWallet.getAccountId(), userId);
					SubmitTransactionResponse rebalanceResponse = sctxBuilder.buildAndSubmit();
					if(!rebalanceResponse.isSuccess()) {
						ObjectPair<String, String> errorInfo = StellarConverter.getResultCodesFromExtra(rebalanceResponse);
						logger.error("Cannot rebalance trade wallet {} {} : {} {}", user.getId(), tradeWallet.getAccountId(), errorInfo.first(), errorInfo.second());
						throw new TradeWalletRebalanceException(errorInfo.first());
					}

					taskRecord.setRebalanceamount(rebalanced.second());
					taskRecord.setRebalancetxhash(rebalanceResponse.getHash());
					taskRecord.store();
				} catch(IOException | AccountRequiresMemoException e) {
					throw new StellarException(e);
				}
			}
		} catch(TalkenException tex) {
			DexTaskRecord.writeError(taskRecord, position, tex);
			throw tex;
		}

		position = "build_tx";
		StellarChannelTransaction.Builder sctxBuilder;
		try {
			final Asset sellAssetType = tmService.getAssetType(request.getSellAssetCode());
			final Asset buyAssetType = tmService.getAssetType(request.getBuyAssetCode());

			sctxBuilder = stellarNetworkService.newChannelTxBuilder()
					.setMemo(dexTaskId.getId())
					.addSigner(new StellarSignerAccount(twService.extractKeyPair(tradeWallet)));

			if(isSell) {
				// selling fee will collected after order-matching
				// and will be dealed in txmon
				// now, just make offer
				sctxBuilder.addOperation(
						new ManageSellOfferOperation
								.Builder(sellAssetType, buyAssetType, StellarConverter.rawToActualString(feeResult.getSellAmountRaw()), StellarConverter.actualToString(price))
								.setOfferId(0)
								.setSourceAccount(tradeWallet.getAccountId())
								.build()
				);
			} else {
				// make offer
				sctxBuilder.addOperation(
						new ManageBuyOfferOperation
								.Builder(sellAssetType, buyAssetType, StellarConverter.rawToActualString(feeResult.getBuyAmountRaw()), StellarConverter.actualToString(price))
								.setOfferId(0)
								.setSourceAccount(tradeWallet.getAccountId())
								.build()
				);

				// pre-paid fee for buying
				if(feeResult.getFeeAmountRaw().compareTo(BigInteger.ZERO) > 0) {
					sctxBuilder.addOperation(
							new PaymentOperation
									.Builder(feeResult.getFeeHolderAccountAddress(), feeResult.getFeeAssetType(), StellarConverter.rawToActualString(feeResult.getFeeAmountRaw()))
									.setSourceAccount(tradeWallet.getAccountId())
									.build()
					);
				}
			}
		} catch(TalkenException tex) {
			DexTaskRecord.writeError(taskRecord, position, tex);
			throw tex;
		}

		position = "build_sctx";
		SubmitTransactionResponse txResponse;
		// put tx info and submit tx
		try(StellarChannelTransaction sctx = sctxBuilder.build()) {
			taskRecord.setTxSeq(sctx.getTx().getSequenceNumber());
			taskRecord.setTxHash(ByteArrayUtil.toHexString(sctx.getTx().hash()));
			taskRecord.setTxXdr(sctx.getTx().toEnvelopeXdrBase64());
			taskRecord.store();

			position = "submit_sctx";
			txResponse = sctx.submit();

			if(!txResponse.isSuccess()) {
				throw StellarException.from(txResponse);
			}
		} catch(TalkenException tex) {
			DexTaskRecord.writeError(taskRecord, position, tex);
			throw tex;
        } catch(IOException | AccountRequiresMemoException | SubmitTransactionTimeoutResponseException ioex) {
            StellarException ex = new StellarException(ioex);
            DexTaskRecord.writeError(taskRecord, position, ex);
            throw ex;
        } catch(Exception e) {
            DexTaskRecord.writeError(taskRecord, position, new GeneralException(e));
            throw e;
        }

		position = "process_result";
		try {
			shadow.com.google.common.base.Optional<Long> offerIdFromResult = txResponse.getOfferIdFromResult(0);

			if(offerIdFromResult.isPresent()) {
				final long offerId = offerIdFromResult.get();

				OperationResult offerResult = txResponse.getDecodedTransactionResult().get().getResult().getResults()[0];
				long madeAmountRaw = -1;
				switch(offerResult.getTr().getDiscriminant()) {
					case MANAGE_SELL_OFFER:
						madeAmountRaw = offerResult.getTr().getManageSellOfferResult().getSuccess().getOffer().getOffer().getAmount().getInt64();
						break;
					case MANAGE_BUY_OFFER:
						madeAmountRaw = offerResult.getTr().getManageBuyOfferResult().getSuccess().getOffer().getOffer().getAmount().getInt64();
						break;
				}

				taskRecord.setOfferid(offerId);
				taskRecord.setMadeamount(StellarConverter.rawToActual(madeAmountRaw));
			}

			taskRecord.setPosttxFlag(true);
			taskRecord.store();
		} catch(Exception ex) {
			TalkenException tex;
			if(ex instanceof TalkenException) tex = (TalkenException) ex;
			else tex = new GeneralException(ex);
			DexTaskRecord.writeError(taskRecord, position, tex);
			// and do not throw exception, leave cleaning mess with C/S, user will get success result
			taskRecord.setPosttxFlag(false);
			taskRecord.store();
			logger.error("Post process for CreateOffer#{} is failed : {} {}", taskRecord.getId(), taskRecord.getErrorcode(), taskRecord.getErrormessage());
		}

		logger.info("{} complete. userId = {}", dexTaskId, userId);

		CreateOfferResult result = new CreateOfferResult();
		result.setTaskId(dexTaskId.getId());
		result.setTaskType(taskType);
		result.setSellAssetCode(taskRecord.getSellassetcode());
		result.setBuyAssetCode(taskRecord.getBuyassetcode());
		result.setAmount(amount);
		result.setPrice(price);
		result.setFeeResult(feeResult);
		result.setOfferId(taskRecord.getOfferid());
		result.setMadeAmount(taskRecord.getMadeamount());
		result.setPostTxStatus(taskRecord.getPosttxFlag());
		return result;
	}


    /**
     * delete sell offer
     *
     * @param user    the user
     * @param request the request
     * @return delete offer result
     * @throws SigningException                 the signing exception
     * @throws TokenMetaNotFoundException       the token meta not found exception
     * @throws TradeWalletCreateFailedException the trade wallet create failed exception
     * @throws StellarException                 the stellar exception
     * @throws TokenMetaNotManagedException     the token meta not managed exception
     * @throws OfferNotValidException           the offer not valid exception
     * @throws OwnershipMismatchException       the ownership mismatch exception
     */
    public DeleteOfferResult deleteSellOffer(User user, DeleteOfferRequest request) throws SigningException, TokenMetaNotFoundException, TradeWalletCreateFailedException, StellarException, TokenMetaNotManagedException, OfferNotValidException, OwnershipMismatchException {
		return deleteOffer(user, true, request);
	}

    /**
     * delete buy offer
     *
     * @param user    the user
     * @param request the request
     * @return delete offer result
     * @throws SigningException                 the signing exception
     * @throws TokenMetaNotFoundException       the token meta not found exception
     * @throws TradeWalletCreateFailedException the trade wallet create failed exception
     * @throws StellarException                 the stellar exception
     * @throws TokenMetaNotManagedException     the token meta not managed exception
     * @throws OfferNotValidException           the offer not valid exception
     * @throws OwnershipMismatchException       the ownership mismatch exception
     */
    public DeleteOfferResult deleteBuyOffer(User user, DeleteOfferRequest request) throws SigningException, TokenMetaNotFoundException, TradeWalletCreateFailedException, StellarException, TokenMetaNotManagedException, OfferNotValidException, OwnershipMismatchException {
		return deleteOffer(user, false, request);
	}

	/**
	 * delete offer
	 * 1. validate request
	 * 2. get offer from stellar network
	 * 3. calculate refund amount
	 * 4. create task
	 * 5. build delete offer (+ refund if exists) tx and send
	 *
	 * @param user
	 * @param isSell
	 * @param request
	 * @return
	 * @throws TokenMetaNotFoundException
	 * @throws StellarException
	 * @throws SigningException
	 * @throws TradeWalletCreateFailedException
	 * @throws TokenMetaNotManagedException
	 * @throws OfferNotValidException
	 * @throws OwnershipMismatchException
	 */
	private DeleteOfferResult deleteOffer(User user, boolean isSell, DeleteOfferRequest request) throws TokenMetaNotFoundException, StellarException, SigningException, TradeWalletCreateFailedException, TokenMetaNotManagedException, OfferNotValidException, OwnershipMismatchException {
		final DexTaskTypeEnum taskType = (isSell) ? DexTaskTypeEnum.OFFER_DELETE_SELL : DexTaskTypeEnum.OFFER_DELETE_BUY;
		final DexTaskId dexTaskId = DexTaskId.generate_taskId(taskType);
		final TradeWalletInfo tradeWallet = twService.ensureTradeWallet(user);
		final long userId = user.getId();
		final BigDecimal price = request.getPrice();
		final long offerId = request.getOfferId();

		DexTaskCreateofferRecord createOfferRecord = dslContext.selectFrom(DEX_TASK_CREATEOFFER)
				.where(DEX_TASK_CREATEOFFER.OFFERID.eq(offerId))
				.fetchOne();

		// check offer task record
		if(createOfferRecord == null) {
			throw new OfferNotValidException(offerId, "Trade record for offerId " + offerId + " not found.");
		}

		// check offer type
		if(createOfferRecord.getTasktype().equals(DexTaskTypeEnum.OFFER_CREATE_SELL)) {
			if(!taskType.equals(DexTaskTypeEnum.OFFER_DELETE_SELL)) {
				throw new OfferNotValidException(offerId, "Trade record for offerId " + offerId + " is not 'Buying'");
			}
		}

		// check offer type
		if(createOfferRecord.getTasktype().equals(DexTaskTypeEnum.OFFER_CREATE_BUY)) {
			if(!taskType.equals(DexTaskTypeEnum.OFFER_DELETE_BUY)) {
				throw new OfferNotValidException(offerId, "Trade record for offerId " + offerId + " is not 'Selling'");
			}
		}

		// ownership check
		if(!createOfferRecord.getUserId().equals(user.getId())) {
			throw new OwnershipMismatchException(offerId + " is not created by you. We WILL INVESTIGATE with this ABNORMAL ATTEMPTION.");
		}

		// get offer Information from network
		OfferResponse originalOffer = null;
		try {
			originalOffer = stellarNetworkService.pickServer().offers().offer(offerId);
		} catch(Exception ex) {
			if(ex instanceof ErrorResponse) {
				if(((ErrorResponse) ex).getCode() == 404) {
					throw new OfferNotValidException(offerId, "Offer " + offerId + " not found on network.");
				}
			}
			throw new StellarException(ex);
		}

		if(originalOffer == null) {
			throw new OfferNotValidException(offerId, "Offer " + offerId + " not found on network.");
		}

		BigDecimal remainAmount = StellarConverter.scale(new BigDecimal(originalOffer.getAmount()));
		BigDecimal refundAmount = null;
		if(!isSell) {
			// createOfferRecord.getFeeamount() : createOfferRecord.getSellamount() = refundAmount : remainAmount
			// refundAmount * createOfferRecord.getSellamount() = remainAmount * createOfferRecord.getFeeamount()
			// refundAmount = remainAmount * createOfferRecord.getFeeamount() / createOfferRecord.getSellamount()
			refundAmount = createOfferRecord.getFeeamount().multiply(remainAmount).divide(createOfferRecord.getSellamount(), BigDecimal.ROUND_FLOOR);

			// zero refund if below zero or zero
			if(refundAmount.compareTo(BigDecimal.ZERO) <= 0) refundAmount = null;
		}

		String position;

		// create task record
		DexTaskDeleteofferRecord taskRecord = new DexTaskDeleteofferRecord();
		taskRecord.setTaskid(dexTaskId.getId());
		taskRecord.setUserId(userId);
		taskRecord.setTasktype(taskType);
		taskRecord.setTradeaddr(tradeWallet.getAccountId());
		taskRecord.setOfferid(offerId);
		taskRecord.setSellassetcode(request.getSellAssetCode());
		taskRecord.setBuyassetcode(request.getBuyAssetCode());
		taskRecord.setPrice(price);
		taskRecord.setCreateofferTaskid(createOfferRecord.getTaskid());
		taskRecord.setRemainamount(remainAmount);
		if(refundAmount != null) {
			taskRecord.setRefundassetcode(createOfferRecord.getFeeassetcode());
			taskRecord.setRefundamount(refundAmount);
		}
		dslContext.attach(taskRecord);
		taskRecord.store();
		logger.info("{} generated. userId = {}", dexTaskId, userId);

		position = "build_tx";
		StellarChannelTransaction.Builder sctxBuilder;
		try {
			final Asset sellAssetType = tmService.getAssetType(request.getSellAssetCode());
			final Asset buyAssetType = tmService.getAssetType(request.getBuyAssetCode());

			sctxBuilder = stellarNetworkService.newChannelTxBuilder()
					.setMemo(dexTaskId.getId())
					.addSigner(new StellarSignerAccount(twService.extractKeyPair(tradeWallet)));

			// build manage offer operation
			if(isSell) {
				sctxBuilder.addOperation(
						new ManageSellOfferOperation
								.Builder(sellAssetType, buyAssetType, "0", StellarConverter.actualToString(price))
								.setOfferId(offerId)
								.setSourceAccount(tradeWallet.getAccountId())
								.build()
				);
			} else {
				sctxBuilder.addOperation(
						new ManageBuyOfferOperation
								.Builder(sellAssetType, buyAssetType, "0", StellarConverter.actualToString(price))
								.setOfferId(offerId)
								.setSourceAccount(tradeWallet.getAccountId())
								.build()
				);
				// add refund operation
				if(refundAmount != null) {
					sctxBuilder.addOperation(
							new PaymentOperation
									.Builder(tradeWallet.getAccountId(), feeCalculationService.getPivotAssetManagedInfo().dexAssetType(), StellarConverter.actualToString(refundAmount))
									.setSourceAccount(feeCalculationService.getPivotAssetManagedInfo().getOfferFeeHolderAddress())
									.build()
					).addSigner(new StellarSignerTSS(signServerService, feeCalculationService.getPivotAssetManagedInfo().getOfferFeeHolderAddress()));
				}
			}
		} catch(TalkenException tex) {
			DexTaskRecord.writeError(taskRecord, position, tex);
			throw tex;
		}

		position = "build_sctx";
		// put tx info and submit tx
		try(StellarChannelTransaction sctx = sctxBuilder.build()) {
			taskRecord.setTxSeq(sctx.getTx().getSequenceNumber());
			taskRecord.setTxHash(ByteArrayUtil.toHexString(sctx.getTx().hash()));
			taskRecord.setTxXdr(sctx.getTx().toEnvelopeXdrBase64());
			taskRecord.store();

			position = "submit_sctx";
			SubmitTransactionResponse txResponse = sctx.submit();

			if(!txResponse.isSuccess()) {
				throw StellarException.from(txResponse);
			}
		} catch(TalkenException tex) {
			DexTaskRecord.writeError(taskRecord, position, tex);
			throw tex;
		} catch(IOException | AccountRequiresMemoException ioex) {
			StellarException ex = new StellarException(ioex);
			DexTaskRecord.writeError(taskRecord, position, ex);
			throw ex;
		}

		DeleteOfferResult result = new DeleteOfferResult();
		result.setTaskId(dexTaskId.getId());
		result.setTaskType(taskType);
		result.setOfferId(taskRecord.getOfferid());
		result.setSellAssetCode(taskRecord.getSellassetcode());
		result.setBuyAssetCode(taskRecord.getBuyassetcode());
		result.setPrice(price);
		result.setRefundAssetCode(taskRecord.getRefundassetcode());
		result.setRefundAmount(taskRecord.getRefundamount());

		return result;
	}
}
