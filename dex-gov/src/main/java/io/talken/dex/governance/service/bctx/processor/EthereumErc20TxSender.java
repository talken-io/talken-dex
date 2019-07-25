package io.talken.dex.governance.service.bctx.processor;

import io.talken.common.persistence.enums.BlockChainPlatformEnum;
import io.talken.common.persistence.enums.TokenMetaAuxCodeEnum;
import io.talken.common.persistence.jooq.tables.pojos.Bctx;
import io.talken.common.persistence.jooq.tables.pojos.BctxLog;
import io.talken.common.util.PrefixedLogger;
import io.talken.dex.governance.service.TokenMeta;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;

@Component
public class EthereumErc20TxSender extends AbstractEthereumTxSender {
	private static final PrefixedLogger logger = PrefixedLogger.getLogger(EthereumErc20TxSender.class);

	public EthereumErc20TxSender() {
		super(BlockChainPlatformEnum.ETHEREUM_ERC20_TOKEN, logger);
	}
}