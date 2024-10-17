# kafka-synth-client

A dummy Kafka client (producer+consumer) that measures end-to-end latency of using Kafka.
The latencies are measured by sending messages to the given Kafka topic, consuming them
and measuring the time it took for the message to be produced and consumed.
The recorded latencies are reported in prometheus format on a metrics endpoint (`/q/metrics`).
The metrics contain values for the median, 95th percentile, and 99th percentile of the latencies.

If the client is given the required ACLs, it will report latencies per broker.
It is also able to automatically increase the number of partitions in a topic to match the number of brokers
and automatically reassigns partitions to brokers to ensure that each broker will be produced to and consumed from.

## Configuration and Usage

The preferred way to configure the client is to use environment variables.
The following environment variables are specific to the client:

- `MESSAGE_SIZE_BYTES` (default: 8): The size of each Kafka message in bytes.
- `MESSAGES_PER_SECOND` (default: 1): The number of messages to produce per second.
- `KAFKA_TOPIC`: The Kafka topic to produce to and consume from.
- `QUARKUS_HTTP_PORT` (default: 8081): The port on which the metrics endpoint will be exposed.

Additionally, you will need to provide configuration for connecting to Kafka. This is also provided via environment variables.
Use the following rule to convert a config property name to an environment variable name:
- Replace all dots (`.`) with underscores (`_`)
- Convert to uppercase
- Prefix with `KAFKA_`

For example, the Kafka property `bootstrap.servers` would be set as the environment variable `KAFKA_BOOTSTRAP_SERVERS`.

For a list of Kafka configuration options, see the following links:
- [Kafka Producer Configuration](https://docs.confluent.io/platform/current/installation/configuration/producer-configs.html)
- [Kafka Consumer Configuration](https://docs.confluent.io/platform/current/installation/configuration/consumer-configs.html)

Note that key/value serializers and deserializers are already configured and should not be overridden.

With the client running, you can view the metrics at `http://localhost:8081/q/metrics`:

```
$ curl localhost:8081/q/metrics -s | grep synth_client_e2e
# TYPE synth_client_e2e_latency_ms_max gauge
# HELP synth_client_e2e_latency_ms_max End-to-end latency of the synthetic client
synth_client_e2e_latency_ms_max{broker="0"} 365.0
# TYPE synth_client_e2e_latency_ms summary
# HELP synth_client_e2e_latency_ms End-to-end latency of the synthetic client
synth_client_e2e_latency_ms{broker="0",quantile="0.5"} 7.125
synth_client_e2e_latency_ms{broker="0",quantile="0.8"} 11.375
synth_client_e2e_latency_ms{broker="0",quantile="0.9"} 12.375
synth_client_e2e_latency_ms{broker="0",quantile="0.95"} 16.875
synth_client_e2e_latency_ms{broker="0",quantile="0.99"} 367.875
synth_client_e2e_latency_ms_count{broker="0"} 60.0
synth_client_e2e_latency_ms_sum{broker="0"} 825.0
```

## Running the application in dev mode

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw compile quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/kafka-synth-client-0.0.1-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.
