package io.spoud.kafka;

import io.quarkus.logging.Log;
import io.spoud.MetricService;
import io.spoud.TimeService;
import io.spoud.config.SynthClientConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class MessageConsumer implements Runnable, AutoCloseable {

    private final SynthClientConfig config;
    private final KafkaConsumer<Long, byte[]> consumer;
    private final MetricService metricService;
    private final TimeService timeService;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<Instant> lastReport = new AtomicReference<>(Instant.now());
    private final AtomicLong counter = new AtomicLong(0);

    public MessageConsumer(KafkaFactory kafkaFactory,
                           SynthClientConfig config,
                           MetricService metricService,
                           TimeService timeService) {
        this.config = config;
        this.metricService = metricService;
        this.timeService = timeService;
        consumer = kafkaFactory.createConsumer();
    }

    @Override
    public void close() {
        try {
            running.set(false);
            consumer.wakeup();
        } catch (Exception e) {
            Log.error("Error while closing consumer", e);
        }
    }

    @Override
    public void run() {
        Log.infov("Subscribing to topic {0}", config.topic());
        consumer.subscribe(List.of(config.topic()), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> collection) {
                if (!collection.isEmpty()) {
                    Log.infov("Revoked partitions [{0}] {1}", collection.size(), collection);
                }
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> collection) {
                if (!collection.isEmpty()) {
                    Log.infov("Assigned partitions [{0}] {1}", collection.size(), collection);
                }
            }
        });

        try {
            while (running.get()) {
                ConsumerRecords<Long, byte[]> records = consumer.poll(Duration.ofSeconds(1));
                for (ConsumerRecord<Long, byte[]> message : records) {
                    long produceTime = message.timestamp();
                    long consumeTime = timeService.currentTimeMillis();
                    String fromRack = Optional.of(message)
                            .map(ConsumerRecord::headers)
                            .map(h -> h.lastHeader(MessageProducer.HEADER_RACK))
                            .map(Header::value)
                            .map(String::new)
                            .orElse("unknown");
                    metricService.recordLatency(message.topic(), message.partition(), consumeTime - produceTime, fromRack);
                    lastReport.updateAndGet(last -> {
                        if (Duration.between(last, Instant.now()).getSeconds() > 10) {
                            Log.infov("Consumed {0} messages/seconds", counter.getAndSet(0) / 10.0);
                            return Instant.now();
                        }
                        return last;
                    });
                    counter.incrementAndGet();
                }
            }
        } catch (WakeupException e) {
            // Ignore
        } catch (Exception e) {
            Log.error("Error while consuming messages", e);
        } finally {
            Log.infov("Closing consumer for topic {0}", config.topic());
            consumer.close();
        }
    }
}
