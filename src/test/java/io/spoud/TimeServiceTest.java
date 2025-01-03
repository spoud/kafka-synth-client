package io.spoud;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.logging.Log;
import io.spoud.config.SynthClientConfig;
import io.spoud.config.SynthClientConfigMessages;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TimeServiceTest {

    @Test
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
                return "au.pool.ntp.org";
            }

            @Override
            public SynthClientConfigMessages messages() {
                return null;
            }
        });

        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    timeService.updateClockOffset(); // make sure that this even works without exceptions

                    var offset = timeService.getClockOffset();
                    assertThat(Math.abs(offset)).isGreaterThan(0); // some skew is expected

                    long currentTimeMillis = timeService.currentTimeMillis();
                    assertThat(currentTimeMillis).isEqualTo(System.currentTimeMillis() + offset);
                    Log.infov("Clock offset: {0} ms", timeService.getClockOffset());
                });
    }
}
