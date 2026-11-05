package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The service and task definition settings an ECS stack declares reach ECS, so DescribeServices
 * and DescribeTaskDefinition report them. Measured on AWS (eu-west-1) with this template's
 * shape: a value a {@code String} parameter gives an {@code AWS::ECS::Service} comes back typed,
 * {@code maximumPercent} 150, {@code enable} true and {@code weight} 2.
 */
@QuarkusTest
class CloudFormationEcsForwardedSettingsIntegrationTest {

    private static final String ECS_TARGET = "AmazonEC2ContainerServiceV20141113";

    private static final String TEMPLATE = """
            {
              "Parameters": {
                "MaxPercent": {"Type": "String", "Default": "150"},
                "Flag": {"Type": "String", "Default": "true"},
                "Weight": {"Type": "String", "Default": "2"}
              },
              "Resources": {
                "Namespace": {
                  "Type": "AWS::ServiceDiscovery::HttpNamespace",
                  "Properties": {"Name": "%1$s"}
                },
                "Registry": {
                  "Type": "AWS::ServiceDiscovery::Service",
                  "Properties": {"Name": "app", "NamespaceId": {"Ref": "Namespace"}}
                },
                "Cluster": {
                  "Type": "AWS::ECS::Cluster",
                  "Properties": {"ClusterName": "%1$s"}
                },
                "TaskDef": {
                  "Type": "AWS::ECS::TaskDefinition",
                  "Properties": {
                    "Family": "%1$s-td",
                    "Cpu": "256",
                    "Memory": "512",
                    "NetworkMode": "awsvpc",
                    "RequiresCompatibilities": ["FARGATE"],
                    "RuntimePlatform": {"CpuArchitecture": "X86_64", "OperatingSystemFamily": "LINUX"},
                    "ContainerDefinitions": [{
                      "Name": "app",
                      "Image": "app:v1",
                      "User": "1000",
                      "HealthCheck": {"Command": ["CMD-SHELL", "true"], "Interval": 30, "Retries": 3}
                    }]
                  }
                },
                "Service": {
                  "Type": "AWS::ECS::Service",
                  "Properties": {
                    "ServiceName": "%1$s-svc",
                    "Cluster": {"Ref": "Cluster"},
                    "TaskDefinition": {"Ref": "TaskDef"},
                    "DesiredCount": 1,
                    "CapacityProviderStrategy": [{"CapacityProvider": "FARGATE", "Weight": {"Ref": "Weight"}}],
                    "DeploymentConfiguration": {
                      "MaximumPercent": {"Ref": "MaxPercent"},
                      "MinimumHealthyPercent": 0,
                      "DeploymentCircuitBreaker": {
                        "Enable": {"Ref": "Flag"},
                        "Rollback": true,
                        "ResetOnHealthyTask": false,
                        "ThresholdConfiguration": {"Type": "COUNT", "Value": 3}
                      },
                      "Alarms": {"Fn::Select": [0, [{"AlarmNames": ["high-errors"], "Enable": false, "Rollback": false}]]}
                    },
                    "ServiceRegistries": [{"RegistryArn": {"Fn::GetAtt": ["Registry", "Arn"]}}],
                    "NetworkConfiguration": {"AwsvpcConfiguration": {"Subnets": ["%2$s"]}}
                  }
                }
              }
            }
            """;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createStack_ecsServiceAndTaskDefinition_reportTheDeclaredSettings() {
        String name = stackName();
        submit("CreateStack", name, null);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());

        ecs("DescribeTaskDefinition", "{\"taskDefinition\": \"" + name + "-td\"}")
            .body("taskDefinition.runtimePlatform.cpuArchitecture", equalTo("X86_64"))
            .body("taskDefinition.runtimePlatform.operatingSystemFamily", equalTo("LINUX"))
            .body("taskDefinition.containerDefinitions[0].user", equalTo("1000"))
            .body("taskDefinition.containerDefinitions[0].healthCheck.command", equalTo(List.of("CMD-SHELL", "true")))
            .body("taskDefinition.containerDefinitions[0].healthCheck.interval", equalTo(30))
            .body("taskDefinition.containerDefinitions[0].healthCheck.retries", equalTo(3));
        describeService(name)
            .body("services[0].deploymentConfiguration.maximumPercent", equalTo(150))
            .body("services[0].deploymentConfiguration.minimumHealthyPercent", equalTo(0))
            .body("services[0].deploymentConfiguration.deploymentCircuitBreaker.enable", equalTo(true))
            .body("services[0].deploymentConfiguration.deploymentCircuitBreaker.rollback", equalTo(true))
            .body("services[0].deploymentConfiguration.deploymentCircuitBreaker.resetOnHealthyTask", equalTo(false))
            .body("services[0].deploymentConfiguration.deploymentCircuitBreaker.thresholdConfiguration.type",
                    equalTo("COUNT"))
            .body("services[0].deploymentConfiguration.deploymentCircuitBreaker.thresholdConfiguration.value",
                    equalTo(3))
            .body("services[0].deploymentConfiguration.alarms.alarmNames", equalTo(List.of("high-errors")))
            .body("services[0].capacityProviderStrategy[0].capacityProvider", equalTo("FARGATE"))
            .body("services[0].capacityProviderStrategy[0].weight", equalTo(2))
            .body("services[0].capacityProviderStrategy[0].base", equalTo(0))
            .body("services[0].serviceRegistries[0].registryArn", containsString(":servicediscovery:us-east-1:"));
    }

    @Test
    void updateStack_ecsService_appliesAChangedSettingAndKeepsTheOthers() {
        String name = stackName();
        submit("CreateStack", name, null);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());

        submit("UpdateStack", name, "200");
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());

        describeService(name)
            .body("services[0].deploymentConfiguration.maximumPercent", equalTo(200))
            .body("services[0].capacityProviderStrategy[0].weight", equalTo(2))
            .body("services[0].serviceRegistries[0].registryArn", containsString(":servicediscovery:us-east-1:"));
    }

    private String stackName() {
        String name = "cfn-ecs-settings-" + Long.toString(System.nanoTime(), 36);
        cleanup.register(() -> CfnStackWaits.deleteStack(name));
        return name;
    }

    /** Submits the template for {@code stackName}, with {@code maxPercent} as its MaxPercent when not null. */
    private static void submit(String action, String stackName, String maxPercent) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", TEMPLATE.formatted(stackName, Ec2Service.defaultSubnetId("us-east-1", "a")));
        if (maxPercent != null) {
            request.formParam("Parameters.member.1.ParameterKey", "MaxPercent")
                .formParam("Parameters.member.1.ParameterValue", maxPercent);
        }
        request
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static ValidatableResponse describeService(String name) {
        return ecs("DescribeServices", "{\"cluster\": \"" + name + "\", \"services\": [\"" + name + "-svc\"]}");
    }

    private static ValidatableResponse ecs(String action, String body) {
        return RestAssuredJsonUtils.awsAction(ECS_TARGET, action, body)
        .then()
            .statusCode(200);
    }
}
