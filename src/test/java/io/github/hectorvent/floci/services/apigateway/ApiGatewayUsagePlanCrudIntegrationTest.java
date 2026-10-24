package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestHTTPEndpoint(ApiGatewayController.class)
class ApiGatewayUsagePlanCrudIntegrationTest {

    @Test
    void testCreateAndRetrieveUsagePlan() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"before\",\"description\":\"old\"}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":["
                        + "{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"after\"},"
                        + "{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"new\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("name", equalTo("after"))
                .body("description", equalTo("new"));

        given()
                .get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("id", equalTo(usagePlanId))
                .body("name", equalTo("after"))
                .body("description", equalTo("new"));
    }

    @Test
    void testUnsupportedPatchPathIsRejected() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"reject-path\",\"description\":\"old\"}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/nope\",\"value\":\"x\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(400);
    }

    @Test
    void aPlanReportsItsStagesFromCreateAndBothReads() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"staged\",\"apiStages\":[{\"apiId\":\"a1\",\"stage\":\"v1\"}]}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .body("apiStages", hasSize(1))
                .body("apiStages[0].apiId", equalTo("a1"))
                .body("apiStages[0].stage", equalTo("v1"))
                .body("apiStages[0].throttle", nullValue())
                .extract()
                .path("id");

        given().get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("apiStages[0].apiId", equalTo("a1"))
                .body("apiStages[0].stage", equalTo("v1"));

        given().get("/usageplans")
                .then()
                .statusCode(200)
                .body("item.find { it.id == '" + usagePlanId + "' }.apiStages[0].stage", equalTo("v1"));
    }

    @Test
    void aPlanWithoutStagesReportsAnEmptyListAndNoLimits() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"bare\"}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .body("apiStages", hasSize(0))
                .body("throttle", nullValue())
                .body("quota", nullValue())
                .extract()
                .path("id");

        given().get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("apiStages", hasSize(0));
    }

    @Test
    void patchesReachTheWireWithApiGatewaysErrors() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"patched\",\"throttle\":{\"burstLimit\":20,\"rateLimit\":10},"
                        + "\"quota\":{\"limit\":1000,\"period\":\"MONTH\"},"
                        + "\"apiStages\":[{\"apiId\":\"a1\",\"stage\":\"v1\","
                        + "\"throttle\":{\"/pets/GET\":{\"burstLimit\":10,\"rateLimit\":5}}}]}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .body("throttle.burstLimit", equalTo(20))
                .body("quota.limit", equalTo(1000))
                .body("quota.offset", equalTo(0))
                .body("apiStages[0].throttle['/pets/GET'].burstLimit", equalTo(10))
                .extract()
                .path("id");

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":["
                        + "{\"op\":\"add\",\"path\":\"/apiStages\",\"value\":\"a1:v2\"},"
                        + "{\"op\":\"replace\",\"path\":\"/apiStages/a1:v1/throttle//pets/GET/burstLimit\",\"value\":\"8\"},"
                        + "{\"op\":\"remove\",\"path\":\"/quota\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("apiStages", hasSize(2))
                .body("apiStages[0].throttle['/pets/GET'].burstLimit", equalTo(8))
                .body("apiStages[1].throttle", nullValue())
                .body("quota", nullValue());

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"add\",\"path\":\"/apiStages\",\"value\":\"a1:v2\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"))
                .body("message", equalTo("API Stage is already defined for Usage Plan: a1:v2"));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"remove\",\"path\":\"/apiStages\",\"value\":\"a1:v9\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Invalid API Stage {api: a1, stage: v9} specified for usageplan "
                        + usagePlanId));

        // A refused patch leaves the plan as it was.
        given().get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("apiStages", hasSize(2));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"remove\",\"path\":\"/apiStages\",\"value\":\"a1:v1\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200);

        given().get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("apiStages", hasSize(1))
                .body("apiStages[0].stage", equalTo("v2"));

        given().contentType(ContentType.JSON)
                .body("{\"name\":\"bad-period\",\"quota\":{\"limit\":1,\"period\":\"day\"}}")
                .post("/usageplans")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void nameAndDescriptionOnlyTakeReplace() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"replace-only\",\"description\":\"kept\"}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        // "apidId" is AWS's own spelling in these lists.
        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"add\",\"path\":\"/name\",\"value\":\"renamed\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid patch path  '/name' specified for op 'add'. Must be one of: "
                        + "[/apiStages, /productCode, /quota/period, /quota/limit, /quota/offset, "
                        + "/throttle/rateLimit, /throttle/burstLimit, "
                        + "/apiStages/<apidId:stageName>/throttle/<resourcePath>/<httpMethod>/rateLimit, "
                        + "/apiStages/<apidId:stageName>/throttle/<resourcePath>/<httpMethod>/burstLimit, "
                        + "/apiStages/<apidId:stageName>/throttle]"));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"remove\",\"path\":\"/description\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid patch path  '/description' specified for op 'remove'. "
                        + "Must be one of: [/apiStages, /quota, /throttle, /productCode, "
                        + "/apiStages/<apidId:stageName>/throttle/<resourcePath>/<httpMethod>, "
                        + "/apiStages/<apidId:stageName>/throttle]"));

        given().get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("name", equalTo("replace-only"))
                .body("description", equalTo("kept"));
    }

    // Measured on API Gateway: GetUsagePlan and UpdateUsagePlan carry "tags": {}, CreateUsagePlan and
    // the GetUsagePlans items leave an empty map out.
    @Test
    void getAndUpdateReportEmptyTagsWhileCreateAndListLeaveThemOut() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"untagged\"}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .body("$", not(hasKey("tags")))
                .extract()
                .path("id");

        given().get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("tags", anEmptyMap());

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"untagged-renamed\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("tags", anEmptyMap());

        given().get("/usageplans")
                .then()
                .statusCode(200)
                .body("item.find { it.id == '" + usagePlanId + "' }", not(hasKey("tags")));
    }

    @Test
    void anUnknownPlanIdIsNotFoundWithApiGatewaysMessage() {
        given().get("/usageplans/{usagePlanId}", "no-such-plan")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Invalid Usage Plan ID specified"));
    }

    @Test
    void anOpOtherThanAddRemoveOrReplaceIsRefusedBeforeItsPath() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"ops-only\"}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        for (String operation : new String[] {
                "{\"op\":\"copy\",\"path\":\"/name\",\"value\":\"x\"}",
                "{\"op\":\"move\",\"path\":\"/name\",\"value\":\"x\"}",
                "{\"op\":\"test\",\"path\":\"/name\",\"value\":\"x\"}",
                "{\"path\":\"/name\",\"value\":\"x\"}",
                "{\"op\":\"move\"}"}) {
            given().contentType(ContentType.JSON)
                    .body("{\"patchOperations\":[" + operation + "]}")
                    .patch("/usageplans/{usagePlanId}", usagePlanId)
                    .then()
                    .statusCode(400)
                    .body("__type", equalTo("BadRequestException"))
                    .body("message", equalTo("Invalid patch operation specified. Must be 'add'|'remove'|'replace'"));
        }

        given().get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("name", equalTo("ops-only"));
    }

    @Test
    void aProductCodeOpIsRefusedAsNotSupported() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"product-code\"}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        for (String operation : new String[] {
                "{\"op\":\"add\",\"path\":\"/productCode\",\"value\":\"code\"}",
                "{\"op\":\"replace\",\"path\":\"/productCode\",\"value\":\"code\"}",
                "{\"op\":\"remove\",\"path\":\"/productCode\"}"}) {
            given().contentType(ContentType.JSON)
                    .body("{\"patchOperations\":[" + operation + "]}")
                    .patch("/usageplans/{usagePlanId}", usagePlanId)
                    .then()
                    .statusCode(400)
                    .body("__type", equalTo("BadRequestException"))
                    .body("message", equalTo("Usage plan product codes are not supported"));
        }

        // An op other than add, remove or replace is still refused before its path is read.
        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"copy\",\"path\":\"/productCode\",\"value\":\"code\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid patch operation specified. Must be 'add'|'remove'|'replace'"));
    }

    @Test
    void aValuelessOffsetOrNameIsRefusedAndLeavesThePlanAsItWas() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"valueless\",\"quota\":{\"limit\":1000,\"offset\":3,\"period\":\"MONTH\"}}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/quota/offset\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Usage Plan quota offset must be numeric and less than the number of time "
                        + "units in the period"));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/name\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Usage Plan name must be specified"));

        given().get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("name", equalTo("valueless"))
                .body("quota.offset", equalTo(3));
    }

    @Test
    void aStageWithoutAnApiIdIsRefusedAndNoPlanIsStored() {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"stage-without-api\",\"apiStages\":[{\"stage\":\"v1\"}]}")
                .post("/usageplans")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid API Stage specified"));

        given().get("/usageplans")
                .then()
                .statusCode(200)
                .body("item.findAll { it.name == 'stage-without-api' }", hasSize(0));
    }

    @Test
    void aWrongJsonTypeOnCreateIsASerializationError() {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"wrong-type\",\"quota\":\"invalid\"}")
                .post("/usageplans")
                .then()
                .statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("Expected null"));
    }

    @Test
    void aStructuredStageIdentifierIsASerializationErrorAndStoresNoPlan() {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"structured-stage\",\"apiStages\":[{\"apiId\":[],\"stage\":{}}]}")
                .post("/usageplans")
                .then()
                .statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("Start of list found where not expected"));

        given().get("/usageplans")
                .then()
                .statusCode(200)
                .body("item.findAll { it.name == 'structured-stage' }", hasSize(0));
    }
}
