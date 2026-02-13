package ru.exchanges.factory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.dto.exchanges.ExchangeType;
import ru.exchanges.Exchange;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ExchangeFactory {

    private final Map<ExchangeType, Exchange> exchanges;

    @Autowired
    public ExchangeFactory(List<Exchange> exchangeList) {
        this.exchanges = exchangeList.stream()
                .collect(Collectors.toMap(
                        Exchange::getType,
                        Function.identity()
                ));
        
        log.info("[ExchangeFactory] Initialized with {} exchanges: {}", 
                exchanges.size(), 
                exchanges.keySet());
    }

    public Exchange getExchange(ExchangeType type) {
        Exchange exchange = exchanges.get(type);
        
        if (exchange == null) {
            throw new IllegalArgumentException("Exchange not found: " + type);
        }
        
        return exchange;
    }

    public Exchange getExchange(String name) {
        try {
            ExchangeType type = ExchangeType.valueOf(name.toUpperCase());
            return getExchange(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown exchange: " + name);
        }
    }

    public boolean hasExchange(ExchangeType type) {
        return exchanges.containsKey(type);
    }

    public List<Exchange> getAllExchanges() {
        return List.copyOf(exchanges.values());
    }

    public double getMinimumBalance() {
        return exchanges.values().stream()
                .mapToDouble(e -> e.getBalance().getBalance())
                .min()
                .orElse(0.0);
    }
}