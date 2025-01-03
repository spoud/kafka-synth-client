package io.spoud;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import io.spoud.config.SynthClientConfig;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class TimeService {
    private final AtomicLong clockOffset = new AtomicLong(0);
    private int timeServerIndex = 0;
    private final String[] timeServers;

    public TimeService(MeterRegistry meterRegistry, SynthClientConfig config) {
        timeServers = config.timeServers().split("([,\\s])+");
        meterRegistry.gauge("time.clockOffset", clockOffset, AtomicLong::get);
    }

    public long currentTimeMillis() {
        return System.currentTimeMillis() + clockOffset.get();
    }

    public long getClockOffset() {
        return clockOffset.get();
    }

    @Blocking
    @Scheduled(every = "300s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void updateClockOffset() {
        if (timeServers.length == 0) {
            Log.warn("No time servers configured. Will not update clock offset");
            return;
        }
        try {
            requestTime(timeServers[timeServerIndex % timeServers.length]);
        } catch (IOException e) {
            Log.error("Failed to update clock offset", e);
        }
    }

    void requestTimeFallback(String host) {
        var nextTimeserver = timeServers[(++timeServerIndex) % timeServers.length];
        Log.errorf("Failed to update clock offset using %s. Will try %s next time.", host, nextTimeserver);
    }

    /**
     * Sends an SNTP request to the given host and calculates the offset between our clock and the server's clock.
     *
     * @param host    host name of the server.
     */
    @Retry(maxRetries = 3)
    @ExponentialBackoff(maxDelay = 60_000)
    @Fallback(fallbackMethod = "requestTimeFallback")
    public void requestTime(String host) throws IOException {
        Log.infof("Updating clock offset using %s NTP server", host);
        try (NTPUDPClient client = new NTPUDPClient()) {
            client.open();
            InetAddress hostAddr = InetAddress.getByName(host);
            TimeInfo info = client.getTime(hostAddr);
            info.computeDetails();
            long offset = info.getOffset();
            clockOffset.set(offset);
            var ourTime = System.currentTimeMillis();
            Log.infof("Our time: %d, their time: %d. We are off by %dms.", ourTime, ourTime + offset, offset);
        }
    }
}
