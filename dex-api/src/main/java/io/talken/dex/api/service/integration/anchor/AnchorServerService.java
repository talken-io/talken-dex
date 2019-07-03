package io.talken.dex.api.service.integration.anchor;

import io.talken.common.util.PrefixedLogger;
import io.talken.common.util.integration.AbstractRestApiService;
import io.talken.common.util.integration.RestApiResult;
import io.talken.dex.api.ApiSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

@Service
@Scope("singleton")
public class AnchorServerService extends AbstractRestApiService {
	private static final PrefixedLogger logger = PrefixedLogger.getLogger(AnchorServerService.class);

	@Autowired
	private ApiSettings apiSettings;

	private static String anchoringApiUrl;
	private static String deanchoringApiUrl;

	@PostConstruct
	private void init() {
		anchoringApiUrl = apiSettings.getIntegration().getAncAddress() + "/exchange/anchor/asset/anchor";
		deanchoringApiUrl = apiSettings.getIntegration().getAncAddress() + "/exchange/anchor/asset/deanchor";
	}

	public RestApiResult<AncServerAnchorResponse> requestAnchor(AncServerAnchorRequest request) {
		return requestPost(anchoringApiUrl, request, AncServerAnchorResponse.class);
	}

	public RestApiResult<AncServerDeanchorResponse> requestDeanchor(AncServerDeanchorRequest request) {
		return requestPost(deanchoringApiUrl, request, AncServerDeanchorResponse.class);
	}
}
