package io.spoud;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.logging.Log;
import io.spoud.config.SynthClientConfig;
import io.spoud.config.SynthClientConfigMessages;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TimeServiceTest {

    @Test
    @DisplayName("Current time is adjusted by the clock offset")
    void currentTimeMillis() {
        TimeService timeService = new TimeService(new SimpleMeterRegistry(), new SynthClientConfig() {
            @Override
            public String topic() {
                return null;
            }

            @Override
            public String rack() {
                return null;
            }

            @Override
            public int consumersCount() {
                return 0;
            }

            @Override
            public String timeServers() {
                return "time.google.com,time.cloudflare.com";
            }

            @Override
            public SynthClientConfigMessages messages() {
                return null;
            }

            @Override
            public boolean autoCreateTopic() {
                return false;
            }

            @Override
            public int topicReplicationFactor() {
                return 1;
            }

            @Override
            public Duration samplingTimeWindow() {
                return null;
            }

            @Override
            public int minSamplesFirstWindow() {
                return 0;
            }

            @Override
            public boolean publishHistogramBuckets() {
                return false;
            }

            @Override
            public Double expectedMinLatency() {
                return 1.0;
            }

            @Override
            public Double expectedMaxLatency() {
                return 5000.0;
            }
        });

        timeService.updateClockOffset(); // make sure that this even works without exceptions

        var offset = timeService.getClockOffset();
        Log.infov("Clock offset: {0} ms", offset);

        // 1ms difference is acceptable since running these commands can also take a miniscule amount of time
        long currentTimeMillis = timeService.currentTimeMillis();
        long expected = System.currentTimeMillis() + offset;
        assertThat(currentTimeMillis).isCloseTo(expected, Offset.offset(1L));
    }
}
