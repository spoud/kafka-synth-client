package io.spoud;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class AdvertisedListenerRepository {
    // rack name -> listener URL mapping
    private final Map<String, String> listeners = new ConcurrentHashMap<>();

    public void mapRackToUrl(String rack, String url) {
        if (url != null && !url.isBlank()) {
            listeners.put(rack, url);
        }
    }

    public Map<String, String> getListeners() {
        // read-only view
        return Collections.unmodifiableMap(listeners);
    }
}
