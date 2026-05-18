package io.spoud;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class IgnoreRecordsTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "%test.synth-client.messages.ignore-first-n-messages", "10",
                "%test.synth-client.messages.messages-per-second", "1"
        );
    }
}
