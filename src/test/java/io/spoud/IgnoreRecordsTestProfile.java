package io.spoud;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Collections;
import java.util.Map;

public class IgnoreRecordsTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Collections.singletonMap("%test.synth-client.messages.ignore-first-n-messages", "3");
    }
}
