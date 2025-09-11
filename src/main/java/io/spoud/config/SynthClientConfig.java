package io.spoud.config;

import io.smallrye.config.ConfigMapping;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

@ConfigMapping(prefix = "synth-client")
public interface SynthClientConfig {
    String topic();

    Optional<Pattern> consumerTopicRegex();

    String rack();

    int consumersCount();

    String timeServers();

    SynthClientConfigMessages messages();

    boolean autoCreateTopic();

    int topicReplicationFactor();

    Duration samplingTimeWindow();

    int minSamplesFirstWindow();

    boolean publishHistogramBuckets();

    Double expectedMinLatency();

    Double expectedMaxLatency();
}
