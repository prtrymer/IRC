package com.example.irc;

import lombok.Builder;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "irc.server")
@Data
public class ServerConfig {
    private int defaultPort;

    @Builder
    public static class ServerConfigBuilder {
        private int port;

        public ServerConfigBuilder port(int port) {
            this.port = port;
            return this;
        }

        public ServerConfig build() {
            ServerConfig config = new ServerConfig();
            config.defaultPort = this.port > 0 ? this.port : 6667;
            return config;
        }
    }
}