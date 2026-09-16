package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnDynamicReferences;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnRollback;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsMetricFilterService;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricTransformation;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * The snapshot fixtures follow snapshotBeforeUpdate at 8787f91e41e0, not an
 * intermediate repair schema. The baseline left these snapshots on successful
 * in-place updates. Every service instance loads a freshly deserialized stack.
 */
@QuarkusTest
class CloudFormationLogsMetricFilterHeadSnapshotIntegrationTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String GROUP = "/cfn/head-snapshot-migration";
    private static final String FILTER = "legacy|count";
    private static final String SNAPSHOT = CfnRollback.METRIC_FILTER_UPDATE_SNAPSHOT_ATTR;
    private static final String STATE = "__FlociMetricFilterState";

    @Inject ObjectMapper mapper;
    @Inject CloudFormationResourceProvisioner dispatcher;
    @Inject CloudWatchLogsService logs;
    @Inject CloudWatchLogsMetricFilterService filters;
    @Inject S3Service s3;
    @Inject SsmService ssm;
    @Inject CfnDynamicReferences dynamicReferences;
    @Inject EmulatorConfig config;
    @Inject RegionResolver resolver;
    @Inject Clock clock;

    private CloudFormationService service;
    private AccountAwareStorageBackend<Stack> stacks;
    private StorageFactory storage;
    private String stackName;
    private JsonNode originalSnapshot;
    private Map<String, JsonNode> unrelated;

    @BeforeEach
    void setup() {
        stackName = "head-snapshot-" + UUID.randomUUID().toString().substring(0, 12);
        stacks = AccountAwareStorageBackend.inMemory(ACCOUNT);
        storage = mock(StorageFactory.class);
        doReturn(stacks).when(storage).create(eq("cloudformation"), eq("cloudformation-stacks.json"), any());
        doReturn(AccountAwareStorageBackend.<String>inMemory(ACCOUNT)).when(storage)
                .create(eq("cloudformation"), eq("cloudformation-exports.json"), any());
        logs.createLogGroup(GROUP, null, null, REGION);
        unrelated = new HashMap<>();
        for (String name : List.of("legacy", "count")) {
            unrelated.put(name, mapper.valueToTree(filters.putMetricFilter(filter(name, "UNRELATED", "9"), REGION)));
        }
    }

    @AfterEach
    void cleanup() {
        if (service != null) {
            service.stop();
        }
        logs.deleteLogGroup(GROUP, REGION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"definition", "absent"})
    void reloadedBaselineSnapshotAllowsConfirmedAbsentDeletion(String variant) throws Exception {
        seedAndReload(variant);
        assertDoesNotThrow(this::deleteStack, "the baseline schema must not fail before checking absence");
        assertExternalResources();
        assertTrue(stacks.keys().isEmpty());
        assertTrue(filters.findMetricFilter(GROUP, FILTER, REGION).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"definition", "absent"})
    void reloadedBaselineSnapshotCannotDeleteBAndRetainsEvidenceAcrossReload(String variant) throws Exception {
        JsonNode b = mapper.valueToTree(filters.putMetricFilter(filter(FILTER, "UNRELATED", "7"), REGION));
        seedAndReload(variant);
        assertUncertainDelete();
        assertEquals(b, actualFilter());
        assertNormalizedUnknown();
        reload();
        assertUncertainDelete();
        assertEquals(b, actualFilter());
        assertNormalizedUnknown();

        filters.deleteMetricFilter(GROUP, FILTER, REGION);
        reload();
        assertDoesNotThrow(this::deleteStack);
        assertExternalResources();
        assertTrue(stacks.keys().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"definition", "absent"})
    void reloadedBaselineUpdateUsesRequestedDefinitionInsteadOfStaleSnapshot(String variant) throws Exception {
        seedAndReload(variant);
        updateStack();
        assertEquals("UPDATE_COMPLETE", currentStack().getStatus(),
                "confirmed absence should allow the requested update, not replay the historical snapshot");
        MetricFilter created = filters.findMetricFilter(GROUP, FILTER, REGION).orElseThrow();
        assertEquals("ERROR", created.getFilterPattern());
        assertEquals("4", created.getMetricTransformations().getFirst().getMetricValue());
        assertEquals("Legacy/Current", created.getMetricTransformations().getFirst().getMetricNamespace());
        assertEquals(FILTER, currentStack().getOutputs().get("FilterRef"));
        assertEquals(FILTER, currentStack().getResources().get("Filter").getPhysicalId());
        reload();
        assertDoesNotThrow(this::deleteStack);
        assertTrue(filters.findMetricFilter(GROUP, FILTER, REGION).isEmpty());
        assertExternalResources();
    }

    @ParameterizedTest
    @ValueSource(strings = {"definition", "absent"})
    void reloadedBaselineUpdateAndLaterDeleteNeverAdoptB(String variant) throws Exception {
        JsonNode b = mapper.valueToTree(filters.putMetricFilter(filter(FILTER, "UNRELATED", "7"), REGION));
        seedAndReload(variant);
        updateStack();
        assertNotEquals("UPDATE_COMPLETE", currentStack().getStatus());
        assertEquals(b, actualFilter());
        assertNormalizedUnknown();
        reload();
        updateStack();
        assertNotEquals("UPDATE_COMPLETE", currentStack().getStatus());
        assertEquals(b, actualFilter());
        assertNormalizedUnknown();
        reload();
        assertUncertainDelete();
        assertEquals(b, actualFilter());

        filters.deleteMetricFilter(GROUP, FILTER, REGION);
        reload();
        assertDoesNotThrow(this::deleteStack);
        assertExternalResources();
    }

    private void seedAndReload(String variant) throws Exception {
        String raw;
        try (InputStream input = getClass().getResourceAsStream("/cloudformation/metric-filter-head-" + variant + ".json")) {
            assertNotNull(input);
            raw = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        originalSnapshot = mapper.readTree(raw);
        Set<String> fields = new HashSet<>();
        originalSnapshot.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("region", "logGroupName", "filterName", variant), fields);
        assertEquals(FILTER, originalSnapshot.path("filterName").asText());

        StackResource resource = new StackResource();
        resource.setLogicalId("Filter");
        resource.setResourceType("AWS::Logs::MetricFilter");
        resource.setPhysicalId(GROUP + "|" + FILTER);
        resource.setStatus("UPDATE_COMPLETE");
        resource.setAttributes(new HashMap<>(Map.of("FlociMetricFilterNameMode", "explicit", SNAPSHOT, raw)));
        Stack stack = new Stack();
        stack.setStackName(stackName);
        stack.setStackId("arn:aws:cloudformation:" + REGION + ":" + ACCOUNT + ":stack/" + stackName + "/" + UUID.randomUUID());
        stack.setAccountId(ACCOUNT);
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_COMPLETE");
        stack.setTemplateBody(template("INFO", "3"));
        stack.setOriginalTemplateBody(stack.getTemplateBody());
        stack.getResources().put("Filter", resource);
        stack.getOutputs().put("FilterRef", GROUP + "|" + FILTER);
        stacks.putForAccount(ACCOUNT, REGION + ":" + stackName, stack);
        reload();
        assertEquals(raw, currentStack().getResources().get("Filter").getAttributes().get(SNAPSHOT),
                "load the actual serialized baseline string, without pre-normalizing it in the fixture");
        assertEquals(GROUP + "|" + FILTER, currentStack().getResources().get("Filter").getPhysicalId());
    }

    private void reload() throws Exception {
        if (service != null) {
            service.stop();
        }
        // A new object graph on every reload, including the escaped snapshot attribute.
        for (var entry : stacks.scanAllAccountEntries(key -> true)) {
            Stack decoded = mapper.readValue(mapper.writeValueAsBytes(entry.value()), Stack.class);
            stacks.putForAccount(entry.accountId(), entry.key(), decoded);
        }
        service = new CloudFormationService(dispatcher, s3, ssm, dynamicReferences, mapper, config, resolver, clock, storage);
        service.loadPersistedState();
    }

    private Stack currentStack() {
        return service.describeStacks(stackName, REGION, ACCOUNT).getFirst();
    }

    private void updateStack() throws Exception {
        String changeSet = "update-" + UUID.randomUUID();
        service.createChangeSet(stackName, changeSet, "UPDATE", template("ERROR", "4"), null,
                Map.of(), List.of(), Map.of(), REGION, ACCOUNT);
        service.executeChangeSet(stackName, changeSet, REGION, ACCOUNT).get(10, TimeUnit.SECONDS);
    }

    private void deleteStack() throws Exception {
        service.deleteStack(stackName, REGION, ACCOUNT).get(10, TimeUnit.SECONDS);
    }

    private void assertUncertainDelete() {
        ExecutionException failure = assertThrows(ExecutionException.class, this::deleteStack);
        assertNotNull(failure.getCause());
        assertEquals("DELETE_FAILED", currentStack().getStatus());
        String reason = currentStack().getResources().get("Filter").getStatusReason();
        assertNotNull(reason);
        assertTrue(reason.contains("uncertain"), reason);
        assertFalse(reason.contains("must be a string"), "schema errors must not block the safe ownership decision");
    }

    private void assertNormalizedUnknown() throws Exception {
        StackResource resource = currentStack().getResources().get("Filter");
        assertEquals(FILTER, resource.getPhysicalId(), "the filter name contains a pipe and must not be split");
        String raw = resource.getAttributes().get(STATE);
        assertNotNull(raw, "retain normalized ownership across service reload");
        JsonNode state = mapper.readTree(raw);
        assertEquals(GROUP, state.path("group").asText());
        assertEquals(FILTER, state.path("name").asText());
        assertEquals("UNKNOWN", state.path("ownership").asText());
        assertEquals(originalSnapshot, state.path("legacySnapshot"), "retain the historical payload, not an active undo");
        assertFalse(resource.getAttributes().containsKey(SNAPSHOT));
    }

    private JsonNode actualFilter() {
        return filters.findMetricFilter(GROUP, FILTER, REGION).<JsonNode>map(mapper::valueToTree).orElse(null);
    }

    private void assertExternalResources() {
        assertTrue(logs.logGroupExists(GROUP, REGION));
        unrelated.forEach((name, expected) -> assertEquals(expected,
                filters.findMetricFilter(GROUP, name, REGION).map(mapper::valueToTree).orElse(null)));
    }

    private static MetricFilter filter(String name, String pattern, String value) {
        MetricTransformation transformation = new MetricTransformation();
        transformation.setMetricName("Count");
        transformation.setMetricNamespace("Legacy/Other");
        transformation.setMetricValue(value);
        MetricFilter filter = new MetricFilter();
        filter.setLogGroupName(GROUP);
        filter.setFilterName(name);
        filter.setFilterPattern(pattern);
        filter.setMetricTransformations(List.of(transformation));
        return filter;
    }

    private static String template(String pattern, String value) {
        return """
                {"Resources":{"Filter":{"Type":"AWS::Logs::MetricFilter","Properties":{
                  "LogGroupName":"%s","FilterName":"%s","FilterPattern":"%s",
                  "MetricTransformations":[{"MetricName":"Count","MetricNamespace":"Legacy/Current","MetricValue":"%s"}]}}},
                 "Outputs":{"FilterRef":{"Value":{"Ref":"Filter"}}}}
                """.formatted(GROUP, FILTER, pattern, value);
    }
}
