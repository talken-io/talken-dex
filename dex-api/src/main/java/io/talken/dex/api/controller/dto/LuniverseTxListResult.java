package io.talken.dex.api.controller.dto;

import io.talken.dex.shared.service.blockchain.ethereum.EthereumTxReceipt;
import lombok.Data;

import java.util.List;

@Data
public class LuniverseTxListResult {
	private String status = "1";
	private String message = "OK";

	private List<EthereumTxReceipt> result;
}