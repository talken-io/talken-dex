package io.talken.dex.api.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.talken.common.exception.common.TokenMetaNotFoundException;
import io.talken.common.persistence.enums.BlockChainPlatformEnum;
import io.talken.common.util.PrefixedLogger;
import io.talken.dex.shared.TokenMetaServiceInterface;
import io.talken.dex.shared.TokenMetaTable;
import io.talken.dex.shared.exception.ActiveAssetHolderAccountNotFoundException;
import io.talken.dex.shared.exception.BlockChainPlatformNotSupportedException;
import io.talken.dex.shared.exception.TokenMetaLoadException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.stellar.sdk.Asset;
import org.stellar.sdk.AssetTypeCreditAlphaNum;
import org.stellar.sdk.KeyPair;

import javax.annotation.PostConstruct;
import java.util.Optional;

@Service
@Scope("singleton")
public class TokenMetaService implements TokenMetaServiceInterface {
	private static final PrefixedLogger logger = PrefixedLogger.getLogger(TokenMetaService.class);

	@Autowired
	private RedisTemplate<String, Object> redisTemplate;

	private static Long loadTimestamp;

	private TokenMetaTable tmTable = new TokenMetaTable();
	private TokenMetaTable miTable = new TokenMetaTable();

	@PostConstruct
	private void init() throws TokenMetaLoadException {
		checkAndReload();
	}

	@Scheduled(fixedDelay = 1000, initialDelay = 5000)
	private void checkAndReload() throws TokenMetaLoadException {
		try {
			Long redisTmUpdated =
					Optional.ofNullable(redisTemplate.opsForValue().get(TokenMetaTable.REDIS_UDPATED_KEY))
							.map((o) -> Long.valueOf(o.toString()))
							.orElseThrow(() -> new TokenMetaLoadException("cannot find cached meta"));

			if(!redisTmUpdated.equals(loadTimestamp)) {
				ObjectMapper mapper = new ObjectMapper();
				mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

				TokenMetaTable newTmTable = (TokenMetaTable) redisTemplate.opsForValue().get(TokenMetaTable.REDIS_KEY);
				TokenMetaTable newMiTable = new TokenMetaTable();

				newMiTable.setUpdated(newTmTable.getUpdated());
				newTmTable.entrySet().stream()
						.filter(_kv -> _kv.getValue().isManaged())
						.peek(_kv -> _kv.getValue().getManagedInfo().prepareCache())
						.forEach(_kv -> newMiTable.put(_kv.getKey(), _kv.getValue()));

				tmTable = newTmTable;
				miTable = newMiTable;
				loadTimestamp = redisTmUpdated;

				logger.info("Token Meta loaded : all {}, managed {}, timestamp {}", tmTable.size(), miTable.size(), loadTimestamp);
			}
		} catch(TokenMetaLoadException ex) {
			throw ex;
		} catch(Exception ex) {
			throw new TokenMetaLoadException(ex);
		}
	}

	public TokenMetaTable.Meta getTokenMeta(String symbol) throws TokenMetaNotFoundException {
		if(!tmTable.containsKey(symbol.toUpperCase())) throw new TokenMetaNotFoundException(symbol);
		return tmTable.get(symbol.toUpperCase());
	}

	public BlockChainPlatformEnum getTokenBctxPlatform(String symbol) throws TokenMetaNotFoundException, BlockChainPlatformNotSupportedException {
		String platform_name = getTokenMeta(symbol).getPlatform();

		if(platform_name == null) throw new BlockChainPlatformNotSupportedException(symbol);

		try {
			return BlockChainPlatformEnum.valueOf(platform_name);
		} catch(IllegalArgumentException ex) {
			throw new BlockChainPlatformNotSupportedException(symbol);
		}
	}

	public TokenMetaTable getTokenMetaList() {
		return tmTable;
	}

	public TokenMetaTable getManagedInfoList() {
		return miTable;
	}

	public boolean isManagedAsset(Asset asset) {
		if(!(asset instanceof AssetTypeCreditAlphaNum)) return false;

		return miTable.values().stream()
				.anyMatch((_m) ->
						_m.getManagedInfo().getIssuerAddress().equalsIgnoreCase(((AssetTypeCreditAlphaNum) asset).getIssuer())
								&& _m.getManagedInfo().getAssetCode().equalsIgnoreCase(((AssetTypeCreditAlphaNum) asset).getCode())
				);
	}

	private TokenMetaTable.ManagedInfo getPack(String assetCode) throws TokenMetaNotFoundException {
		return Optional.ofNullable(
				Optional.ofNullable(
						miTable.get(assetCode)
				).orElseThrow(() -> new TokenMetaNotFoundException(assetCode))
						.getManagedInfo())
				.orElseThrow(() -> new TokenMetaNotFoundException(assetCode));
	}

	@Override
	public Asset getAssetType(String code) throws TokenMetaNotFoundException {
		return getPack(code).getCache().getAssetType();
	}

	public KeyPair getOfferFeeHolderAccount(String code) throws TokenMetaNotFoundException {
		return getPack(code).getCache().getOfferFeeHolder();
	}

	public KeyPair getDeanchorFeeHolderAccount(String code) throws TokenMetaNotFoundException {
		return getPack(code).getCache().getDeanchorFeeHolder();
	}

	public KeyPair getSwapFeeHolderAccount(String code) throws TokenMetaNotFoundException {
		return getPack(code).getCache().getSwapFeeHolder();
	}

	public KeyPair getBaseAccount(String code) throws TokenMetaNotFoundException {
		return getPack(code).getCache().getAssetBase();
	}

	public String getActiveHolderAccountAddress(String code) throws TokenMetaNotFoundException, ActiveAssetHolderAccountNotFoundException {
		Optional<TokenMetaTable.HolderAccountInfo> opt_aha = getPack(code).getAssetHolderAccounts().stream()
				.filter(TokenMetaTable.HolderAccountInfo::getActiveFlag)
				.findAny();
		if(opt_aha.isPresent()) return opt_aha.get().getAddress();
		else {
			logger.warn("There is no active asset holder account for {}, use random hot account.", code);

			Optional<TokenMetaTable.HolderAccountInfo> opt_ahh = getPack(code).getAssetHolderAccounts().stream()
					.filter(TokenMetaTable.HolderAccountInfo::getHotFlag)
					.findAny();
			if(opt_ahh.isPresent()) return opt_ahh.get().getAddress();
			else throw new ActiveAssetHolderAccountNotFoundException(code);
		}
	}
}
