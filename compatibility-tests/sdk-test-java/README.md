# sdk-test-java

Compatibility tests for [Floci](https://github.com/hectorvent/floci) using the **AWS SDK for Java v2 (2.31.8)**.

Runs 352 tests across 17 test classes against a live Floci instance — no mocks.

## Services Covered

| Test class                       | Description                                              |
| -------------------------------- | -------------------------------------------------------- |
| `SsmTest`                        | Parameter Store — put, get, path, tags                   |
| `SqsTest`                        | Queues, send/receive/delete, DLQ, visibility             |
| `SnsTest`                        | Topics, subscriptions, publish, SQS delivery             |
| `S3Test`                         | Buckets, objects, tagging, copy, multipart, batch delete |
| `DynamoDbTest`                   | Tables, CRUD, batch, TTL, tags, streams                  |
| `DynamoDbScanConditionTests`     | Scan filter and condition expressions                    |
| `LambdaTest`                     | Create/invoke/update/delete functions                    |
| `IamTest`                        | Users, roles, policies, access keys                      |
| `StsTest`                        | GetCallerIdentity, AssumeRole, GetSessionToken           |
| `SecretsManagerTest`             | Create/get/put/list/delete secrets, versioning, tags     |
| `KmsTest`                        | Keys, aliases, encrypt/decrypt, data keys, sign/verify   |
| `CloudWatchTest`                 | PutMetricData, ListMetrics, GetMetricStatistics, alarms  |
| `CloudFormationVirtualHostTests` | Virtual host style S3 access via CloudFormation          |
| `ApigwSfnJsonataCrudlTests`      | API Gateway + Step Functions JSONata CRUDL integration   |
| `ApiGatewayV2WebSocketAndExtendedOpsTest` | API GW v2 WebSocket APIs, Update ops, Route/Integration Responses, Models, Tagging |
| `Ec2Tests`                       | EC2 instances, VPCs, security groups, subnets            |
| `AppSyncTest`                    | GraphQL API CRUDL, data sources, resolvers, functions, types, API keys, tags, schema validation |
| `EcsTests`                       | ECS clusters, task definitions, services                 |

## Adding a New Test

Create a standard JUnit 5 test class in `src/test/java/com/floci/test/`. Tests run against a live Floci instance using real AWS SDK clients.

## Requirements

- Java 17+
- Maven

## Running

```bash
# All tests
mvn test -q

# Specific test class
mvn test -Dtest=S3Test

# Via just (from compatibility-tests/)
just test-java
```

## Configuration

| Variable         | Default                 | Description             |
| ---------------- | ----------------------- | ----------------------- |
| `FLOCI_ENDPOINT` | `http://localhost:4566` | Floci emulator endpoint |

AWS credentials are always `test` / `test` / `us-east-1`.

## Metric-filter closing gate

Run both classes against a freshly built Floci JVM or native server:

```bash
# From compatibility-tests/sdk-test-java
FLOCI_ENDPOINT=http://127.0.0.1:18084 ../../mvnw test \
  -Dtest=CloudWatchLogsMetricFilterTest,CloudFormationLogsMetricFilterTest

# Against a native server mapped to a different loopback port
FLOCI_ENDPOINT=http://127.0.0.1:18085 ../../mvnw test \
  -Dtest=CloudWatchLogsMetricFilterTest,CloudFormationLogsMetricFilterTest
```

Logs management uses real AWS SDK clients. The current dependency is SDK v2.52.0,
which selects CloudWatch JSON. `MetricFilterQueryAssertions` additionally signs
legacy form-encoded Query requests using the same test credentials, without
introducing an older SDK dependency. Both `GetMetricStatistics` and
`GetMetricData` paths assert identical stored samples, exact sum/count, event
minute and dimension series. Query responses also assert the CloudWatch XML
namespace. The quiet-default alarm check uses bounded condition polling.

Expected publishing values come from the immutable root fixture
`src/test/resources/cloudwatchlogs/metric-filter-publishing-aws.json`, not Floci
output. A byte-identical copy is shipped in this module's test resources and
loaded from the classpath, including inside the module-only Docker image.
The root `CloudWatchLogsMetricFilterFixturePackagingTest` rejects drift in either
SDK module copy. Additional checks cover quiet traffic, JSON public extraction shape,
and CFN replacement/rollback. These commands target Floci only: passing them is
not live AWS verification. For separately acknowledged AWS writes, see the
opt-in stateful replay instructions in `../sdk-test-python/README.md`.

The independent `MetricFilterFixturePackagingTest` has no SDK clients or
`@BeforeAll` network hooks. Its offline image check, from the repository root:

```bash
docker build -t floci-sdk-java-fixtures compatibility-tests/sdk-test-java
docker run --rm --network none --entrypoint mvn floci-sdk-java-fixtures \
  -o -q test -Dtest=MetricFilterFixturePackagingTest
```

## Docker

```bash
docker build -t floci-sdk-java .
docker run --rm --network host floci-sdk-java

# Custom endpoint (macOS/Windows)
docker run --rm -e FLOCI_ENDPOINT=http://host.docker.internal:4566 floci-sdk-java
```
