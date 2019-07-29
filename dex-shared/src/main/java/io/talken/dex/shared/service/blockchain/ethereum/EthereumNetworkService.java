package io.talken.dex.shared.service.blockchain.ethereum;


import io.talken.common.util.PrefixedLogger;
import io.talken.dex.shared.DexSettings;
import io.talken.dex.shared.service.blockchain.RandomServerPicker;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Convert;

import javax.annotation.PostConstruct;
import java.math.BigInteger;

@Service
@Scope("singleton")
@RequiredArgsConstructor
public class EthereumNetworkService {
	private static final PrefixedLogger logger = PrefixedLogger.getLogger(EthereumNetworkService.class);

	private final DexSettings dexSettings;

	private final RandomServerPicker serverPicker = new RandomServerPicker();

	@PostConstruct
	private void init() {
		if(dexSettings.getBcnode().getEthereum().getNetwork().equalsIgnoreCase("test")) {
			logger.info("Using Ethereum TEST Network.");
		} else {
			logger.info("Using Ethereum PUBLIC Network.");
		}
		for(String _s : dexSettings.getBcnode().getEthereum().getServerList()) {
			logger.info("Ethereum jsonrpc endpoint {} added.", _s);
			serverPicker.add(_s);
		}
	}

	public Web3j newClient() {
		return Web3j.build(new HttpService(serverPicker.pick()));
	}

	public BigInteger getGasPrice(Web3j web3j) {
		// TODO : calculate or get proper value
		return Convert.toWei("20", Convert.Unit.GWEI).toBigInteger();
//
//		try {
//			return web3j.ethGasPrice().send().getGasPrice();
//		} catch(Exception ex) {
//
//		}
	}

	public BigInteger getGasLimit(Web3j web3j) {
		// TODO : calculate or get proper value
		return new BigInteger("250000");

//		EthBlock.Block lastBlock = web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send().getBlock();
//		return lastBlock.getGasLimit();
	}
}
