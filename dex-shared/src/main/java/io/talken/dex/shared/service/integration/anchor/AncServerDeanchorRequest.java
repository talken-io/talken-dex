package io.talken.dex.shared.service.integration.anchor;

import lombok.Data;

@Deprecated
@Data
public class AncServerDeanchorRequest {
	private String taskId;
	private String uid;
	private String symbol;
	private String hash;
	private String from;
	private String to;
	private String address;
	private String memo;
	private Double value;
}
