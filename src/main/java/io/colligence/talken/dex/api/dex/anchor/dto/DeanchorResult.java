package io.colligence.talken.dex.api.dex.anchor.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;

@Data
public class DeanchorResult {
	@NotEmpty
	private String taskID;
	@NotEmpty
	private String holderAccountAddress;
}
