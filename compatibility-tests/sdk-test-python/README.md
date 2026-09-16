# sdk-test-python

Compatibility tests for [Floci](https://github.com/hectorvent/floci) using **boto3 (1.37.1)**.

## Services Covered

| Group                   | Description                                                              |
| ----------------------- | ------------------------------------------------------------------------ |
| `ssm`                   | Parameter Store — put, get, label, history, path, tags                   |
| `sqs`                   | Queues, send/receive/delete, DLQ, visibility                             |
| `sns`                   | Topics, subscriptions, publish, SQS delivery                             |
| `s3`                    | Buckets, objects, tagging, copy, batch delete                            |
| `s3-cors`               | CORS configuration                                                       |
| `s3-notifications`      | S3 → SQS event notifications                                             |
| `dynamodb`              | Tables, CRUD, batch, TTL, tags                                           |
| `lambda`                | Create/invoke/update/delete functions                                    |
| `iam`                   | Users, roles, policies, access keys                                      |
| `sts`                   | GetCallerIdentity, AssumeRole, GetSessionToken                           |
| `secretsmanager`        | Create/get/put/list/delete secrets, versioning, tags                     |
| `kms`                   | Keys, aliases, encrypt/decrypt, data keys, sign/verify                   |
| `kinesis`               | Streams, shards, PutRecord/GetRecords                                    |
| `cloudwatch-metrics`    | PutMetricData, ListMetrics, GetMetricStatistics, alarms                  |
| `cloudformation-naming` | Auto physical name generation, explicit name precedence, cross-reference |
| `cognito`               | User pools, clients, AdminCreateUser, InitiateAuth, GetUser              |

## Requirements

- Python 3.9+
- pip

## Running

```bash
pip install -r requirements.txt

# All groups
pytest tests/ --junit-xml=test-results/junit.xml

# Specific tests
pytest tests/test_s3.py

# Via just (from compatibility-tests/)
just test-python
```

## Configuration

| Variable         | Default                 | Description             |
| ---------------- | ----------------------- | ----------------------- |
| `FLOCI_ENDPOINT` | `http://localhost:4566` | Floci emulator endpoint |

AWS credentials are always `test` / `test` / `us-east-1`.

## Opt-in stateful metric-filter replay

`metric_filter_replay.py` is a separate executable. Importing it or running its
offline unit checks never contacts AWS. It does not change `conftest.py` or make
`FLOCI_TARGET=aws` select real AWS.

From the repository root, using an environment with the declared requirements:

```bash
python -m pytest compatibility-tests/sdk-test-python/tests/test_metric_filter_replay.py

# Local validation only, with explicit loopback endpoint and dummy credentials.
python compatibility-tests/sdk-test-python/metric_filter_replay.py \
  --endpoint http://127.0.0.1:4566 --region eu-west-1 \
  --timeout 30 --stable-seconds 2 --poll-interval 1
```

Real AWS mode requires all three explicit safety arguments. **This creates billable
CloudWatch metrics and a tagged log group. Metric series cannot be deleted** and
remain until AWS ages them out. Do not run this unless you intend these writes:

```bash
python compatibility-tests/sdk-test-python/metric_filter_replay.py \
  --aws --profile YOUR_DISPOSABLE_TEST_PROFILE --region eu-west-1 \
  --ack-live-writes I_ACCEPT_AWS_WRITES
```

Only known commercial AWS regions are supported. Logs, CloudWatch and STS clients
pin official regional HTTPS endpoints and ignore configured endpoint overrides.
The replay uses unique names and verifies the ownership tag both after creation
and before deleting its group in `finally`. Cleanup failure exits nonzero and
prints the synthetic group name for manual inspection. Interrupted or uncertain
creates may need manual cleanup; the tool never deletes an unverified group.
Raw service errors, credentials, account IDs and profile values are not printed.

The immutable oracle is
`src/test/resources/cloudwatchlogs/metric-filter-publishing-aws.json`: mixed
defaults, separate hit/miss batches, missing/null/nonnumeric extraction,
all-or-none ordinary dimensions, and system dimensions on matches versus
dimensionless pattern-nonmatch defaults. The replay also checks three quiet
nonmatches contributing `Sum=21`, `SampleCount=3`, without closing traffic.
Quiet ingestion runs last, with no subsequent `PutLogEvents` before or during
observation. Each scenario uses its own backdated event-time minute. The next
minute after quiet is a separate, unwritten window that must remain empty.
The replay polls all expected positive controls, absent series and that idle
window together, accepting results only after a
complete stable window, not after the first empty read. AWS defaults are a
240-second deadline and a 30-second stable window. Request timeouts bound a slow
in-flight read separately.

Output is sanitized JSON lines with observations, not a regenerated fixture.
Passing against Floci is not live AWS verification. This replay does not measure
transformer/centralization behavior, inferred combinations of fallback and
dimensions, or crash durability of pending publications.

### Fixture packaging

The module ships `tests/fixtures/metric-filter-publishing-aws.json` so its Docker
image does not depend on repository ancestors. It is a byte-identical copy of the
canonical root oracle, not a second source of expected values.
`CloudWatchLogsMetricFilterFixturePackagingTest` in the root project enforces
byte equality for both SDK module copies. When accepted AWS evidence changes,
copy the canonical file unchanged to both module resource locations and run:

```bash
./mvnw test -Dtest=CloudWatchLogsMetricFilterFixturePackagingTest
docker build -t floci-sdk-python-fixtures compatibility-tests/sdk-test-python
docker run --rm --network none --entrypoint pytest floci-sdk-python-fixtures \
  tests/test_metric_filter_replay.py -q
```

## Docker

```bash
docker build -t floci-sdk-python .
docker run --rm --network host floci-sdk-python

# Custom endpoint (macOS/Windows)
docker run --rm -e FLOCI_ENDPOINT=http://host.docker.internal:4566 floci-sdk-python
```
