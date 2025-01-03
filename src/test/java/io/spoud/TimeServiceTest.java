package io.spoud;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.logging.Log;
import io.spoud.config.SynthClientConfig;
import io.spoud.config.SynthClientConfigMessages;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
                return "time.google.com,time.cloudflare.com";
            }

            @Override
            public SynthClientConfigMessages messages() {
                return null;
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
