package org.ironrhino.core.redis;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.ironrhino.core.spring.configuration.ApplicationContextPropertiesConditional;
import org.ironrhino.core.util.AppInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.LettuceClientConfigurationBuilder;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ApplicationContextPropertiesConditional(key = "redlock.addresses", value = ApplicationContextPropertiesConditional.ANY)
public class Redlock {

	private static final String DEFAULT_NAMESPACE = "redlock:";

	@Value("${redlock.addresses}")
	private String[] addresses;

	@Value("${redlock.database:0}")
	private int database;

	@Value("${redlock.password:#{null}}")
	private String password;

	@Value("${redlock.connectTimeout:50}")
	private int connectTimeout = 50;

	@Value("${redlock.readTimeout:50}")
	private int readTimeout = 50;

	@Value("${redlock.shutdownTimeout:100}")
	private int shutdownTimeout = 100;

	@Value("${redlock.useSsl:false}")
	private boolean useSsl;

	@Value("${redlock.namespace:" + DEFAULT_NAMESPACE + "}")
	private String namespace = DEFAULT_NAMESPACE;

	private StringRedisTemplate[] redisTemplates;

	private RedisScript<Long> compareAndDeleteScript = new DefaultRedisScript<>(
			"if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
			Long.class);

	@PostConstruct
	public void init() {
		ClientOptions clientOptions = ClientOptions.builder()
				.socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(connectTimeout)).build())
				.timeoutOptions(TimeoutOptions.enabled()).build();
		LettuceClientConfigurationBuilder builder = LettuceClientConfiguration.builder();
		if (useSsl)
			builder.useSsl();
		LettuceClientConfiguration clientConfiguration = builder.clientOptions(clientOptions)
				.commandTimeout(Duration.ofMillis(readTimeout)).shutdownTimeout(Duration.ofMillis(shutdownTimeout))
				.build();
		redisTemplates = new StringRedisTemplate[addresses.length];
		for (int i = 0; i < addresses.length; i++) {
			String address = addresses[i];
			String hostName;
			int port;
			int index = address.lastIndexOf(':');
			if (index > 0) {
				hostName = address.substring(0, index);
				port = Integer.parseInt(address.substring(index + 1));
			} else {
				hostName = address;
				port = 6379;
			}
			RedisStandaloneConfiguration standaloneConfiguration = new RedisStandaloneConfiguration(hostName, port);
			standaloneConfiguration.setDatabase(database);
			if (StringUtils.isNotBlank(password))
				standaloneConfiguration.setPassword(RedisPassword.of(password));
			LettuceConnectionFactory lcf = new LettuceConnectionFactory(standaloneConfiguration, clientConfiguration);
			lcf.afterPropertiesSet();
			redisTemplates[i] = new StringRedisTemplate(lcf);
		}
	}

	@PreDestroy
	public void destroy() {
		for (int i = 0; i < redisTemplates.length; i++) {
			((LettuceConnectionFactory) redisTemplates[i].getConnectionFactory()).destroy();
		}
	}

	public boolean tryLock(String name, long validityTime, TimeUnit unit) {
		int n = redisTemplates.length;
		if (validityTime < unit.convert(n * (connectTimeout + readTimeout), TimeUnit.MILLISECONDS)) {
			log.warn("Please consider increase validityTime: " + validityTime);
		}
		String key = namespace + name;
		String holder = holder();
		int c = 0;
		long time = System.nanoTime();
		for (int i = 0; i < n; i++) {
			long actualValidityTime = validityTime - unit.convert(System.nanoTime() - time, TimeUnit.NANOSECONDS);
			if (actualValidityTime > 0) {
				try {
					Boolean success = redisTemplates[i].opsForValue().setIfAbsent(key, holder, actualValidityTime,
							unit);
					if (success == null)
						throw new RuntimeException("Unexpected null");
					if (success) {
						if ((++c) >= n / 2 + 1)
							return true;
					}
				} catch (Exception e) {
					log.warn(e.getMessage(), e);
				}
			}
		}
		unlock(name);
		return false;
	}

	public void unlock(String name) {
		String key = namespace + name;
		String holder = holder();
		for (int i = 0; i < redisTemplates.length; i++) {
			try {
				redisTemplates[i].execute(compareAndDeleteScript, Collections.singletonList(key), holder);
			} catch (Exception e) {
				log.warn(e.getMessage(), e);
			}
		}
	}

	private static String holder() {
		return AppInfo.getInstanceId() + '$' + Thread.currentThread().getId();
	}

}
