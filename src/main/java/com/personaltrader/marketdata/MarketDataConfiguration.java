package com.personaltrader.marketdata;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class MarketDataConfiguration {
    @Bean
    public Clock marketDataClock() { return Clock.systemUTC(); }
}
