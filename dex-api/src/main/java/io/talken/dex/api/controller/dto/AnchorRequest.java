package io.talken.dex.api.controller.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * The type Anchor request.
 */
@Data
public class AnchorRequest {
	@NotEmpty
	private String privateWalletAddress;
	@NotEmpty
	private String assetCode;
	@NotNull
	private BigDecimal amount;
	@NotNull
	private BigDecimal networkFee;
}
