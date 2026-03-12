package io.spoud;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.RangeAssignor;
import org.apache.kafka.clients.producer.RoundRobinPartitioner;
import org.apache.kafka.common.metrics.JmxReporter;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.duckdb.DuckDBDriver;

/**
 * Some of the classes that the Kafka client uses are discovered at runtime based on string-valued configuration.
 * We must register them explicitly, else GraalVM will simply 'optimize' them out of the final executable and the
 * application will fail to start.
 */
@RegisterForReflection(targets = {
        JmxReporter.class,
        LongDeserializer.class,
        LongSerializer.class,
        StringSerializer.class,
        StringDeserializer.class,
        RangeAssignor.class,
        CooperativeStickyAssignor.class,
        RoundRobinPartitioner.class,
        DuckDBDriver.class
})
public class ReflectionConfiguration {
}
