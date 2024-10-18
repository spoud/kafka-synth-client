package io.spoud.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "synth-client")
public interface SynthClientConfig {
    String topic();

    @WithDefault("1")
    int consumersCount();

    SynthClientConfigMessages messages();
}
