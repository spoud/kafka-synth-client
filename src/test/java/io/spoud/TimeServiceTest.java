package io.spoud;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.spoud.config.SynthClientConfig;
import io.spoud.config.SynthClientConfigMessages;
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
            public int consumersCount() {
                return 0;
            }

            @Override
            public String timeServers() {
                return "0.asia.pool.ntp.org";
            }

            @Override
            public SynthClientConfigMessages messages() {
                return null;
            }
        });

        timeService.updateClockOffset(); // make sure that this even works without exceptions

        var offset = timeService.getClockOffset();
        assertThat(Math.abs(offset)).isGreaterThan(0); // some skew is expected

        long currentTimeMillis = timeService.currentTimeMillis();
        assertThat(currentTimeMillis).isEqualTo(System.currentTimeMillis() + offset);
        System.out.println("Current time: " + currentTimeMillis);
        System.out.println("Clock offset: " + timeService.getClockOffset() + " ms");
    }
}
