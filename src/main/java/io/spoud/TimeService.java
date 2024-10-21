package io.spoud;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import io.spoud.config.SynthClientConfig;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

// this implementation is inspired by
public class TimeService {
    private static final int REFERENCE_TIME_OFFSET = 16;
    private static final int ORIGINATE_TIME_OFFSET = 24;
    private static final int RECEIVE_TIME_OFFSET = 32;
    private static final int TRANSMIT_TIME_OFFSET = 40;
    private static final int NTP_PACKET_SIZE = 48;

    private static final int NTP_PORT = 123;
    private static final int NTP_MODE_CLIENT = 3;
    private static final int NTP_VERSION = 3;

    // Number of seconds between Jan 1, 1900 and Jan 1, 1970
    // 70 years plus 17 leap days
    private static final long OFFSET_1900_TO_1970 = ((365L * 70L) + 17L) * 24L * 60L * 60L;

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
            requestTime(timeServers[timeServerIndex % timeServers.length], 5000);
        } catch (IOException e) {
            Log.error("Failed to update clock offset", e);
        }
    }

    void requestTimeFallback(String host, int timeout) {
        var nextTimeserver = timeServers[(++timeServerIndex) % timeServers.length];
        Log.errorf("Failed to update clock offset using %s. Will try %s next time.", host, nextTimeserver);
    }

    /**
     * Sends an SNTP request to the given host and processes the response.
     *
     * @param host    host name of the server.
     * @param timeout network timeout in milliseconds.
     */
    @Retry(maxRetries = 3)
    @ExponentialBackoff(maxDelay = 60_000)
    @Fallback(fallbackMethod = "requestTimeFallback")
    public void requestTime(String host, int timeout) throws IOException {
        Log.infof("Updating clock offset using %s NTP server", host);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeout);
            InetAddress address = InetAddress.getByName(host);
            byte[] buffer = new byte[NTP_PACKET_SIZE];
            DatagramPacket request = new DatagramPacket(buffer, buffer.length, address, NTP_PORT);

            // set mode = 3 (client) and version = 3
            // mode is in low 3 bits of first byte
            // version is in bits 3-5 of first byte
            buffer[0] = NTP_MODE_CLIENT | (NTP_VERSION << 3);

            // get current time and write it to the request packet
            long ourRequestTime = System.currentTimeMillis();

            writeTimeStamp(buffer, TRANSMIT_TIME_OFFSET, ourRequestTime);

            socket.send(request);

            // read the response
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);

            // extract the results
            long ourReceiveTime = System.currentTimeMillis(); // time when we received the response (as perceived by us)
            long ourOriginateTime = readTimeStamp(buffer, ORIGINATE_TIME_OFFSET); // time when the request was sent (as perceived by us)
            long theirReceiveTime = readTimeStamp(buffer, RECEIVE_TIME_OFFSET); // time when the request was received (as perceived by server)
            long theirTransmitTime = readTimeStamp(buffer, TRANSMIT_TIME_OFFSET); // time when the response was sent (as perceived by server)

            // We have two unknowns - the skew and the transit duration. We don't care about transit duration, so we can eliminate it.
            // We want to know the skew. We know that:
            // (1): skew = theirReceiveTime - ourOriginateTime - transit
            // (2): skew = theirTransmitTime + transit - ourReceiveTime
            // (1) + (2): 2*skew = (receiveTime - originateTime - transit) + (transmitTime - responseTime + transit)
            // => skew = ((receiveTime - originateTime) + (transmitTime - responseTime))/2
            long skew = ((theirReceiveTime - ourOriginateTime) + (theirTransmitTime - ourReceiveTime)) / 2;
            clockOffset.set(skew);

            var ourTime = System.currentTimeMillis();
            Log.infof("Our time: %d, their time: %d. We are off by %dms.", ourTime, ourTime + skew, Math.abs(skew));
        }
    }

    /**
     * Reads an unsigned 32 bit big endian number from the given offset in the buffer.
     */
    private long read32(byte[] buffer, int offset) {
        byte b0 = buffer[offset];
        byte b1 = buffer[offset + 1];
        byte b2 = buffer[offset + 2];
        byte b3 = buffer[offset + 3];

        // convert signed bytes to unsigned values
        int i0 = ((b0 & 0x80) == 0x80 ? (b0 & 0x7F) + 0x80 : b0);
        int i1 = ((b1 & 0x80) == 0x80 ? (b1 & 0x7F) + 0x80 : b1);
        int i2 = ((b2 & 0x80) == 0x80 ? (b2 & 0x7F) + 0x80 : b2);
        int i3 = ((b3 & 0x80) == 0x80 ? (b3 & 0x7F) + 0x80 : b3);

        return ((long) i0 << 24) + ((long) i1 << 16) + ((long) i2 << 8) + (long) i3;
    }

    /**
     * Reads the NTP time stamp at the given offset in the buffer and returns
     * it as a system time (milliseconds since January 1, 1970).
     */
    private long readTimeStamp(byte[] buffer, int offset) {
        long seconds = read32(buffer, offset);
        long fraction = read32(buffer, offset + 4);
        return ((seconds - OFFSET_1900_TO_1970) * 1000) + ((fraction * 1000L) / 0x100000000L);
    }

    /**
     * Writes system time (milliseconds since January 1, 1970) as an NTP time stamp
     * at the given offset in the buffer.
     */
    private void writeTimeStamp(byte[] buffer, int offset, long time) {
        long seconds = time / 1000L;
        long milliseconds = time - seconds * 1000L;
        seconds += OFFSET_1900_TO_1970;

        // write seconds in big endian format
        buffer[offset++] = (byte) (seconds >> 24);
        buffer[offset++] = (byte) (seconds >> 16);
        buffer[offset++] = (byte) (seconds >> 8);
        buffer[offset++] = (byte) (seconds >> 0);

        long fraction = milliseconds * 0x100000000L / 1000L;
        // write fraction in big endian format
        buffer[offset++] = (byte) (fraction >> 24);
        buffer[offset++] = (byte) (fraction >> 16);
        buffer[offset++] = (byte) (fraction >> 8);
        // low order bits should be random data
        buffer[offset++] = (byte) (Math.random() * 255.0);
    }
}
