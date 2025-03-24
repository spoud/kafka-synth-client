package io.spoud.config;

import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.time.Duration;

public interface SynthClientConfigMessages {
    int messageSizeBytes();
    int messagesPerSecond();
    /**
     * How many messages to exclude from latency reporting per partition after app startup.
     * The first consumption of messages can be slow due to the consumer group rebalance.
     * This adds unnecessary noise to the metrics. By ignoring the first N messages, we can
     * avoid this noise.
     *
     * @return number of messages to ignore
     */
    @WithName("ignore-first-n-messages")
    int ignoreFirstNMessages();
}
