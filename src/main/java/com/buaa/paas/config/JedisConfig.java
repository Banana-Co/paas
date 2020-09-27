package com.buaa.paas.config;

import com.buaa.paas.commons.util.jedis.JedisClientCluster;
import com.buaa.paas.commons.util.jedis.JedisClientPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Protocol;

import java.util.HashSet;
import java.util.Set;

/**
 */
@Configuration
public class JedisConfig {
    @Value("${redis.standalone.host}")
    private String STANDALONE_HOST;

    @Value("${redis.standalone.port}")
    private Integer STANDALONE_PORT;

    @Value("${redis.standalone.password}")
    private String STANDALONE_PASSWORD;

    @Bean
    public JedisClientPool jedisClientPool() {
        JedisClientPool jedisClientPool = new JedisClientPool();
        jedisClientPool.setJedisPool(jedisPool());

        return jedisClientPool;
    }

    @Bean
    public JedisPool jedisPool() {
        return new JedisPool(new GenericObjectPoolConfig(), STANDALONE_HOST, STANDALONE_PORT,
                Protocol.DEFAULT_TIMEOUT, STANDALONE_PASSWORD, Protocol.DEFAULT_DATABASE, null);
    }
}