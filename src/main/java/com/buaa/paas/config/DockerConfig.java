package com.buaa.paas.config;

import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 */
@Configuration
public class DockerConfig {
    @Value("${docker.server.url}")
    private String serverUrl;

    @Bean(name = "dockerClient")
    DockerClient dockerClient() {
        return DefaultDockerClient.builder()
                .uri(URI.create(serverUrl))
//                        .dockerCertificates(new DockerCertificates(Paths.get("D:/")))
                .build();
    }
}
