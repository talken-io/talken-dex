package io.talken.dex.api.config;

import io.talken.common.CommonConsts;
import io.talken.common.service.JWTService;
import io.talken.common.service.MessageService;
import io.talken.common.util.PostLaunchExecutor;
import io.talken.dex.api.ApiSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

@Configuration
@ComponentScan("io.talken.dex.shared")
public class ApiRootConfig {

	@Autowired
	private ApiSettings apiSettings;

	// PostLauncherExecutor
	@Bean
	public PostLaunchExecutor postLaunchExecutor() {
		return new PostLaunchExecutor();
	}

	// Message Source & Service
	@Bean
	public MessageSource messageSource() {
		ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
		messageSource.setBasenames("i18n/message", "i18n/dexException");
		messageSource.setDefaultEncoding(CommonConsts.CHARSET_NAME);

		return messageSource;
	}

	@Bean
	public MessageService messageService() {
		return new MessageService(CommonConsts.LOCALE, messageSource());
	}

	@Bean
	public JWTService jwtService() {
		return new JWTService(apiSettings.getAccessToken().getJwtSecret());
	}
}