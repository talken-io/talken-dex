package io.talken.dex.governance.service.bctx.processor;

import io.talken.common.persistence.enums.BlockChainPlatformEnum;
import io.talken.common.persistence.jooq.tables.pojos.Bctx;
import io.talken.common.persistence.jooq.tables.pojos.BctxLog;
import io.talken.common.util.PrefixedLogger;
import io.talken.dex.governance.service.TokenMeta;
import org.springframework.stereotype.Component;

@Component
public class EthereumTxSender extends AbstractEthereumTxSender {
	private static final PrefixedLogger logger = PrefixedLogger.getLogger(EthereumTxSender.class);

	public EthereumTxSender() {
		super(BlockChainPlatformEnum.ETHEREUM, logger);
	}

	@Override
	public void sendTx(TokenMeta meta, Bctx bctx, BctxLog log) throws Exception {
		sendEthereumTx(null, bctx, log);
	}
}
