package ru.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component

public class FundingArbitrageContext {

    private final Set<Long> subscriberIds = ConcurrentHashMap.newKeySet();

    public void addSubscriberId(Long chatId) {
        if (subscriberIds.add(chatId)) {
            log.info("New subscriber added: {}", chatId);
        } else {
            log.info("User {} already subscribed", chatId);
        }
    }

    public void removeSubscriberId(Long chatId) {
        if (subscriberIds.remove(chatId)) {
            log.info("Subscriber removed: {}", chatId);
        }
    }

    public Set<Long> getSubscriberIds() {
        return Collections.unmodifiableSet(subscriberIds);
    }

    public boolean isSubscribed(Long chatId) {
        return subscriberIds.contains(chatId);
    }

}
