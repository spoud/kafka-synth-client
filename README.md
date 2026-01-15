# kafka-synth-client

A simple Kafka client (producer+consumer) that measures end-to-end latency of using Kafka.
The latencies are measured by sending messages to the given Kafka topic via all the brokers, consuming them
and measuring the time it took for the message to be produced and consumed.
The recorded latencies are reported in prometheus format on a metrics endpoint (`/q/metrics`).
The metrics contain values for the median, percentiles like 95th percentile, and 99th percentile of the latencies.

If the client is given the required ACLs, it will report latencies per broker.
It is also able to automatically increase the number of partitions in a topic to match the number of brokers
and automatically reassigns partitions to brokers to ensure that each broker will be produced to and consumed from.

For detailed information see our documentation at https://spoud.github.io/kafka-synth-client/.


## Contributing

We welcome contributions to this project.


## Development

### Running the application in dev mode

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw compile quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

### Packaging and running the application

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

## Docker image

On release publishing, a Docker image is built and published to Docker Hub.

The image is signed using Cosign and the keyless with GitHub Token and the signature is also published as artifact.

You can verify the image signature using the following command:

```shell

cosign verify ghcr.io/spoud/kafka-synth-client:v1.8.2 \
  --certificate-identity https://github.com/spoud/kafka-synth-client/.github/workflows/build-test.yaml@refs/tags/v1.8.2 \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com | jq
```