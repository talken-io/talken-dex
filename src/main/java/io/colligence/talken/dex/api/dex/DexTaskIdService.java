package io.colligence.talken.dex.api.dex;

import ch.qos.logback.core.encoder.ByteArrayUtil;
import io.colligence.talken.common.util.PrefixedLogger;
import io.colligence.talken.dex.DexSettings;
import io.colligence.talken.dex.exception.TaskIntegrityCheckFailedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

@Service
@Scope("singleton")
public class DexTaskIdService {
	private static final PrefixedLogger logger = PrefixedLogger.getLogger(DexTaskIdService.class);

	@Autowired
	private DexSettings dexSettings;

	private static String symbols;
	private static char[] sc;
	private static int sl;
	private static final char padding = '-';
	private static final SecureRandom random = new SecureRandom();

	@PostConstruct
	private void init() {
		symbols = dexSettings.getRandomStringTable();
		sc = symbols.toCharArray();
		sl = symbols.length();
	}

	/* GENERATION & VERIFICATION */
	public DexTaskId generate_taskId(DexTaskId.Type type) {
		long genTime = System.currentTimeMillis();

		StringBuilder sb = new StringBuilder("TALKEN");
		sb.append(type.getCode());

		for(int i = 0; i < 7; i++)
			sb.append(sc[random.nextInt(sc.length)]);

		StringBuilder sb2 = new StringBuilder();
		long num = genTime;
		for(int i = 0; i < 8; i++) {
			if(num != 0) {
				sb2.append(sc[(int) (num % sl)]);
				num /= sl;
			} else {
				sb2.append(padding);
			}
		}
		sb.append(sb2.reverse());

		String head = sb.toString();

		byte[] bytes = head.getBytes(StandardCharsets.UTF_8);

		byte parity = bytes[0];

		for(int i = 1; i < bytes.length; i++)
			parity ^= bytes[i];

		String s = Integer.toHexString((parity & 0xff));
		if(s.length() == 1) s = "0" + s;

		return new DexTaskId(type, head + s, genTime);
	}

	public DexTaskId decode_taskId(String taskId) throws TaskIntegrityCheckFailedException {
		try {
			if(taskId == null || taskId.length() != 24) throw new TaskIntegrityCheckFailedException(taskId);

			char[] ca = taskId.toCharArray();

			if(ca[0] != 'T') throw new TaskIntegrityCheckFailedException(taskId);
			if(ca[1] != 'A') throw new TaskIntegrityCheckFailedException(taskId);
			if(ca[2] != 'L') throw new TaskIntegrityCheckFailedException(taskId);
			if(ca[3] != 'K') throw new TaskIntegrityCheckFailedException(taskId);
			if(ca[4] != 'E') throw new TaskIntegrityCheckFailedException(taskId);
			if(ca[5] != 'N') throw new TaskIntegrityCheckFailedException(taskId);

			DexTaskId.Type type = DexTaskId.Type.fromCode(ca[6]);
			if(type == null) throw new TaskIntegrityCheckFailedException(taskId);

			byte[] bytes = taskId.substring(0, 22).getBytes(StandardCharsets.UTF_8);

			byte parity = bytes[0];
			for(int i = 1; i < bytes.length; i++)
				parity ^= bytes[i];

			if(parity != ByteArrayUtil.hexStringToByteArray(taskId.substring(22, 24))[0])
				throw new TaskIntegrityCheckFailedException(taskId);

			long num = 0;
			for(char ch : taskId.substring(14, 22).toCharArray()) {
				if(ch == padding) continue;
				num *= sl;
				num += symbols.indexOf(ch);
			}
			if(num < 0) throw new TaskIntegrityCheckFailedException(taskId);

			return new DexTaskId(type, taskId, num);
		} catch(TaskIntegrityCheckFailedException e) {
			throw e;
		} catch(Exception e) {
			throw new TaskIntegrityCheckFailedException(taskId);
		}
	}
}
