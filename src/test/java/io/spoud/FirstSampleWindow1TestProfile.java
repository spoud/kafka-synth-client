package io.spoud;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Collections;
import java.util.Map;

public class FirstSampleWindow1TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "%test.synth-client.min-samples-first-window", "1",
                "%test.synth-client.messages.ignore-first-n-messages", "0"
        );
    }
}
