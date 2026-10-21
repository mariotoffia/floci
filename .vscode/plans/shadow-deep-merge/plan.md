# Plan: IoT Device Shadow deep merge on UpdateThingShadow

Task: `IOT-SHADOW-DEEP-MERGE` in [../TASKS.md](../TASKS.md). Status: planned, not implemented.

## Context

teos task 27.21 (shadow metadata timestamps) failed verification. After removing the
Floci skips, both teos integration tests fail:

- `TestProcessedPayloadFormatTypeSensorsFiltered`, `go-core/cbtarget/awsdstarget/target_integration_test.go:316`
- `TestAWSDsTargetProcessedPayloadFormatTypeSensorsFiltered`, `go-core/cbtarget/awsdstarget_integration_test.go:158`

Both report `Not equal: expected: float64(-14.1) actual: <nil>`. Observed shadow before and after the update:

```
before: {"state":{"reported":{"sensors":{"IDT_O1":22.3,"ODT":-14.1}}},"metadata":{"reported":{"sensors":{"IDT_O1":{"timestamp":1792591167},"ODT":{"timestamp":1792591167}}}},"version":1}
update: {"state":{"reported":{"sensors":{"IDT_O1":26.3},"tz":"Europe/Stockholm"}}}
after:  {"state":{"reported":{"sensors":{"IDT_O1":26.3},"tz":"Europe/Stockholm"}},"metadata":{"reported":{"sensors":{"IDT_O1":{...}},"tz":{...}}},"version":2}
```

AWS keeps `sensors.ODT = -14.1`. Metadata timestamps themselves are correct.

## Root cause

`src/main/java/io/github/hectorvent/floci/services/iot/IotService.java`,
`private void mergeShadowSection(ObjectNode state, ObjectNode metadata, String section, JsonNode patch, long timestamp)`
(around line 2087), called from `writeShadowUpdate(...)` for `desired` and `reported`
(reached through `updateThingShadow(...)` from both `IotDataController.updateThingShadow`
and the MQTT reserved topic `update` case).

It merges only one level: for each top-level key of the patch it does
`stateSection.set(key, value)` and `metadataSection.set(key, shadowMetadata(value, timestamp))`.
A nested object in the patch therefore replaces the whole stored object, and its metadata
subtree, instead of being merged key by key.

## AWS IoT Device Shadow semantics

Sources:

- Device Shadow service documents: https://docs.aws.amazon.com/iot/latest/developerguide/device-shadow-document.html
- Interacting with shadows (update, delete, delta): https://docs.aws.amazon.com/iot/latest/developerguide/device-shadow-data-flow.html
- Shadow MQTT topics: https://docs.aws.amazon.com/iot/latest/developerguide/reserved-topics.html#reserved-topics-shadow
- UpdateThingShadow API: https://docs.aws.amazon.com/iot/latest/apireference/API_iotdata_UpdateThingShadow.html
- Device Shadow REST API: https://docs.aws.amazon.com/iot/latest/developerguide/device-shadow-rest-api.html

Rules to emulate:

1. Merge: an update merges into the stored `desired`/`reported` sections. Objects merge
   recursively key by key at any depth; fields absent from the update are kept.
2. Arrays and scalars replace the stored value whole (arrays are not merged element-wise).
3. Null deletes: a field set to `null` at any depth is removed with its metadata. Setting
   `desired` or `reported` to `null` removes that section. An object left empty after
   deletes is pruned (verify against AWS; record the result in the test).
4. Metadata: `metadata` mirrors the state tree; each leaf carries `{"timestamp": <epoch seconds>}`
   of its last update. Only leaves present in the update get the new timestamp; untouched
   leaves keep theirs. Arrays carry metadata per element as AWS documents.
5. Version: increments by exactly 1 per accepted update; a request `version` that does not
   match the current version is rejected with `ConflictException` (409).
6. Responses: `update/accepted` (and the API response) echo only the request `state` with
   metadata for the updated leaves; `update/documents` carries the full merged `previous`
   and `current`; `delta` is computed from the merged `desired` vs merged `reported`
   (nested objects compared key by key) and is published only when non-empty.

## Approach

- Replace the one-level loop in `mergeShadowSection` with a recursive
  `mergeShadowNode(ObjectNode stateNode, ObjectNode metadataNode, JsonNode patch, long timestamp)`:
  - `null` value: remove key from state and metadata.
  - object patch onto stored object: recurse into `withObject(key)` on both trees; prune empties.
  - object patch onto a non-object or missing: start from empty objects, then recurse.
  - array or scalar: set value and `shadowMetadata(value, timestamp)`.
- Keep `writeShadowUpdate`, version check, accepted/documents/delta construction unchanged
  except that they read the merged document (they already do).
- Keep the change narrow: no wire-format or endpoint changes; follow AGENTS.md style
  (no `var`, explicit types, braces, no em-dashes).

## Tests

Unit (`src/test/java/io/github/hectorvent/floci/services/iot/IotServiceTest.java`):

- `updateThingShadow_nestedReported_mergesKeys`: teos case, ODT kept at -14.1, IDT_O1 = 26.3, tz added.
- `updateThingShadow_nestedReported_keepsUntouchedLeafTimestamp`: ODT metadata timestamp unchanged, IDT_O1 updated.
- `updateThingShadow_nestedNull_deletesLeafAndMetadata` and pruning of an emptied object.
- `updateThingShadow_sectionNull_removesSection`.
- `updateThingShadow_array_replacesWhole`.
- `updateThingShadow_versionIncrementsByOne` and `updateThingShadow_staleVersion_conflict`.
- `updateThingShadow_nestedDesired_deltaFromMergedDocument`.
- `updateThingShadow_documents_previousAndCurrentAreMerged`.

Integration (`IotDataIntegrationTest.java`, SDK-based `IotDataPlaneClient`):

- Repro of teos: UpdateThingShadow `{"state":{"reported":{"sensors":{"IDT_O1":22.3,"ODT":-14.1}}}}`,
  then `{"state":{"reported":{"sensors":{"IDT_O1":26.3},"tz":"Europe/Stockholm"}}}`, GetThingShadow
  asserts `sensors.ODT == -14.1`, `sensors.IDT_O1 == 26.3`, `version == 2`, per-leaf metadata.
- Same flow over MQTT `$aws/things/<thing>/shadow/update` asserting `update/documents`.

Run: `./mvnw test -Dtest=IotServiceTest`, `./mvnw test -Dtest=IotDataIntegrationTest`, `./mvnw checkstyle:check`.

## Acceptance criteria

1. Nested objects in `desired`/`reported` merge key by key at any depth; untouched fields survive.
2. `null` at any depth deletes the field and its metadata; section-level `null` removes the section.
3. Metadata timestamps change only for updated leaves.
4. Version increments by 1 per update; version conflicts return 409 `ConflictException`.
5. Delta and `update/documents` reflect the merged document; `update/accepted` echoes the request.
6. Unit and integration tests above pass; checkstyle passes.
7. With an image built from the fix, teos `TestProcessedPayloadFormatTypeSensorsFiltered` and
   `TestAWSDsTargetProcessedPayloadFormatTypeSensorsFiltered` pass with the skips removed.
