package org.folio.rest.impl;

import org.junit.Test;
import com.jayway.restassured.RestAssured;
import static com.jayway.restassured.RestAssured.given;
import com.jayway.restassured.response.Header;
import static org.hamcrest.Matchers.containsString;
import org.folio.rest.tools.PomReader;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.runner.RunWith;

import org.folio.rest.RestVerticle;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.client.test.HttpClientMock2;
import org.junit.After;

/**
 * Interface test for mod-notify. Tests the API with restAssured, directly
 * against the module - without any Okapi in the picture. Since we run with an
 * embedded postgres, we always start with an empty database, and can safely
 * leave test data in it.
 *
 * @author heikki
 */
@RunWith(VertxUnitRunner.class)
public class NotifyTest {

  private final Logger logger = LoggerFactory.getLogger("notifytest");
  private final int port = Integer.parseInt(System.getProperty("port", "8081"));
  private static final String LS = System.lineSeparator();
  private final Header TEN = new Header("X-Okapi-Tenant", "testlib");
  private final Header USER7 = new Header("X-Okapi-User-Id",
    "77777777-7777-7777-7777-777777777777");
  private final Header USER8 = new Header("X-Okapi-User-Id",
    "88888888-8888-8888-8888-888888888888");
  private final Header USER9 = new Header("X-Okapi-User-Id",
    "99999999-9999-9999-9999-999999999999");

  private final Header JSON = new Header("Content-Type", "application/json");
  private String moduleName; // "mod-notify";
  private String moduleVersion; // "0.2.0-SNAPSHOT";
  private String moduleId; // "mod-notify-0.2.0-SNAPSHOT"
  Vertx vertx;
  Async async;

  @Before
  public void setUp(TestContext context) {
    vertx = Vertx.vertx();
    moduleName = PomReader.INSTANCE.getModuleName()
      .replaceAll("_", "-");  // Rmb returns a 'normalized' name, with underscores
    moduleVersion = PomReader.INSTANCE.getVersion();
    moduleId = moduleName + "-" + moduleVersion;

    logger.info("Test setup starting for " + moduleId);

    try {
      PostgresClient.setIsEmbedded(true);
      PostgresClient.getInstance(vertx).startEmbeddedPostgres();
    } catch (Exception e) {
      context.fail(e);
      return;
    }

    JsonObject conf = new JsonObject()
      .put(HttpClientMock2.MOCK_MODE, "true")
      .put("http.port", port);

    logger.info("notifyTest: Deploying "
      + RestVerticle.class.getName() + " "
      + Json.encode(conf));
    DeploymentOptions opt = new DeploymentOptions()
      .setConfig(conf);
    vertx.deployVerticle(RestVerticle.class.getName(),
      opt, context.asyncAssertSuccess());
    RestAssured.port = port;
    logger.info("notifyTest: setup done. Using port " + port);
  }

  @After
  public void tearDown(TestContext context) {
    logger.info("Cleaning up after notifyTest");
    async = context.async();
    PostgresClient.stopEmbeddedPostgres();
    vertx.close(res -> {   // This logs a stack trace, ignore it.
      async.complete();
    });
  }

  /**
   * All the tests. In one long function, because starting up the embedded
   * postgres takes so long and fills the log.
   *
   * @param context
   */
  @Test
  public void tests(TestContext context) {
    logger.info("notifyTest starting");

    // Simple GET request to see the module is running and we can talk to it.
    given()
      .get("/admin/health")
      .then().log().ifValidationFails()
      .statusCode(200);

    // Simple GET request without a tenant
    given()
      .get("/notify")
      .then().log().ifValidationFails()
      .statusCode(400)
      .body(containsString("Tenant"));

    // Simple GET request with a tenant, but before
    // we have invoked the tenant interface, so the
    // call will fail (with lots of traces in the log)
    given()
      .header(TEN)
      .get("/notify")
      .then().log().ifValidationFails()
      .statusCode(500);

    // Call the tenant interface to initialize the database
    String tenants = "{\"module_to\":\"" + moduleId + "\"}";
    logger.info("About to call the tenant interface " + tenants);
    given()
      .header(TEN).header(JSON)
      .body(tenants)
      .post("/_/tenant")
      .then().log().ifValidationFails()
      .statusCode(201);

    // Empty list of notifications
    given()
      .header(TEN)
      .get("/notify")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("\"notifications\" : [ ]"));

    // Post some malformed notifications
    String bad1 = "This is not json";
    given()
      .header(TEN) // no content-type header
      .body(bad1)
      .post("/notify")
      .then().log().ifValidationFails()
      .statusCode(400)
      .body(containsString("Content-type"));

    given()
      .header(TEN).header(JSON)
      .body(bad1)
      .post("/notify")
      .then().log().ifValidationFails()
      .statusCode(400)
      .body(containsString("Json content error"));

    String notify1 = "{"
      + "\"id\" : \"11111111-1111-1111-1111-111111111111\"," + LS
      + "\"recipientId\" : \"77777777-7777-7777-7777-777777777777\"," + LS
      + "\"link\" : \"users/1234\"," + LS
      + "\"text\" : \"First notification\"}" + LS;

    String bad2 = notify1.replaceFirst("}", ")"); // make it invalid json
    given()
      .header(TEN).header(JSON)
      .body(bad2)
      .post("/notify")
      .then().log().ifValidationFails()
      .statusCode(400)
      .body(containsString("Json content error"));

    String bad3 = notify1.replaceFirst("text", "badFieldName");
    given()
      .header(TEN).header(JSON)
      .body(bad3)
      .post("/notify")
      .then().log().ifValidationFails()
      .statusCode(422)
      .body(containsString("Unrecognized field"))
      .body(containsString("badFieldName"));

    String bad4 = notify1.replaceAll("-1111-", "-2-");  // make bad UUID
    given()
      .header(TEN).header(JSON)
      .body(bad4)
      .post("/notify")
      .then().log().ifValidationFails()
      .statusCode(422)
      .body(containsString("invalid input syntax for type uuid"));

    String bad5 = notify1.replaceAll("recipientId", "senderId"); // recip missing
    given()
      .header(TEN).header(JSON)
      .body(bad5)
      .post("/notify")
      .then().log().ifValidationFails()
      .statusCode(422)
      .body(containsString("recipientId"));

    // Post a good notification
    given()
      .header(TEN).header(USER9).header(JSON)
      .body(notify1)
      .post("/notify")
      .then().log().ifValidationFails()
      .log().ifValidationFails()
      .statusCode(201);

    // Fetch the notification in various ways
    given()
      .header(TEN)
      .get("/notify")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("First notification"))
      .body(containsString("-9999-")) // CreatedBy userid in metadata
      .body(containsString("\"totalRecords\" : 1"));

    given()
      .header(TEN)
      .get("/notify/11111111-1111-1111-1111-111111111111")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("First notification"));

    given()
      .header(TEN)
      .get("/notify/777")
      .then().log().ifValidationFails()
      .statusCode(422);

    given()
      .header(TEN)
      .get("/notify?query=text=fiRST")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("First notification"));

    String notify2 = "{"
      + "\"id\" : \"22222222-2222-2222-2222-222222222222\"," + LS
      + "\"recipientId\" : \"77777777-7777-7777-7777-777777777777\"," + LS
      + "\"link\" : \"things/23456\"," + LS
      + "\"seen\" : false,"
      + "\"text\" : \"Notification on a thing\"}" + LS;

    // Post another notification
    given()
      .header(TEN).header(USER8).header(JSON)
      .body(notify2)
      .post("/notify")
      .then().log().ifValidationFails()
      .statusCode(201);

    // duplicate id
    given()
      .header(TEN).header(USER8).header(JSON)
      .body(notify2)
      .post("/notify")
      .then().log().ifValidationFails()
      .body(containsString("duplicate key"))
      .statusCode(422);

    // Get both notifications a few different ways
    given()
      .header(TEN)
      .get("/notify?query=text=notification")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("First notification"))
      .body(containsString("things/23456"));

    // Check seen
    given()
      .header(TEN)
      .get("/notify?query=seen=false")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("First notification"));

    given()
      .header(TEN)
      .get("/notify?query=seen=true")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("\"totalRecords\" : 0"));

    // bad queries
    // Used to return 422, but the new helper in RMB says it is 400.
    given()
      .header(TEN)
      .get("/notify?query=BADQUERY")
      .then().log().ifValidationFails()
      .statusCode(400);
    given()
      .header(TEN)
      .get("/notify?query=BADFIELD=foo")
      .then().log().ifValidationFails()
      .statusCode(400);
    given()
      .header(TEN)
      .get("/notify?query=metadata.BADFIELD=foo")
      .then().log().ifValidationFails()
      .statusCode(400);

    // Update a notification
    String updated1 = "{"
      + "\"id\" : \"11111111-1111-1111-1111-111111111111\"," + LS
      + "\"recipientId\" : \"77777777-7777-7777-7777-777777777777\"," + LS
      + "\"link\" : \"users/1234\"," + LS
      + "\"seen\" : true," + LS
      + "\"text\" : \"First notification with a comment\"}" + LS;

    given()
      .header(TEN).header(USER8).header(JSON)
      .body(updated1)
      .put("/notify/22222222-2222-2222-2222-222222222222") // wrong one
      .then().log().ifValidationFails()
      .statusCode(422)
      .body(containsString("Can not change the id"));

    given()
      .header(TEN).header(USER8).header(JSON)
      .body(updated1)
      .put("/notify/55555555-5555-5555-5555-555555555555") // bad one
      .then().log().ifValidationFails()
      .statusCode(422)
      .body(containsString("Can not change the id"));

    String updated2 = updated1.replaceAll("1", "5");
    given()
      .header(TEN).header(USER8).header(JSON)
      .body(updated2)
      .put("/notify/55555555-5555-5555-5555-555555555555") // unknown one
      .then().log().ifValidationFails()
      .statusCode(404);

    given()
      .header(TEN).header(USER8).header(JSON)
      .body(updated1)
      .put("/notify/11111111-222-1111-2-111111111111") // invalid UUID
      .then().log().ifValidationFails()
      .statusCode(422);

    given()
      .header(TEN).header(USER8).header(JSON)
      .body(updated1.replace("recipientId", "senderId")) // no recipient
      .put("/notify/11111111-1111-1111-1111-111111111111")
      .then().log().ifValidationFails()
      .statusCode(422);

    given()
      .header(TEN).header(USER8).header(JSON)
      .body(updated1.replace("seen", "UNKNOWNFIELD")) // unknown field
      .put("/notify/11111111-1111-1111-1111-111111111111")
      .then().log().ifValidationFails()
      .statusCode(422);

    given() // This should work
      .header(TEN).header(USER8).header(JSON)
      .body(updated1)
      .put("/notify/11111111-1111-1111-1111-111111111111")
      .then().log().ifValidationFails()
      .statusCode(204);

    given()
      .header(TEN)
      .get("/notify/11111111-1111-1111-1111-111111111111")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("\"seen\" : true"))
      .body(containsString("with a comment"))
      .body(containsString("-8888-"));   // updated by

    // post by userId
    // no recipientId here, it should come from the URL
    // no id, should be autogenerated. We need to save the location
    String notify3 = "{"
      + "\"link\" : \"things/34567\"," + LS
      + "\"text\" : \"Notification on a thing, for mockuser9\"}" + LS;

    given()
      .header(TEN).header(USER8).header(JSON)
      .body(notify3)
      .post("/notify/_username/notfound")
      .then().log().ifValidationFails()
      .statusCode(400);
    given()
      .header(TEN).header(USER8).header(JSON)
      .body(notify3)
      .post("/notify/_username/error")
      .then().log().ifValidationFails()
      .statusCode(500);
    given()
      .header(TEN).header(USER8).header(JSON)
      .body(notify3)
      .post("/notify/_username/permissionproblem")
      .then().log().ifValidationFails()
      .body(containsString("User lookup failed with 403"))
      .statusCode(400);

    given() // lookup succeeds, but has no id
      .header(TEN).header(USER8).header(JSON)
      .body(notify3)
      .post("/notify/_username/badmockuser")
      .then().log().ifValidationFails()
      .statusCode(400);

    String notify3Loc = given() // a good one
      .header(TEN).header(USER8).header(JSON)
      .body(notify3)
      .post("/notify/_username/mockuser9")
      .then().log().ifValidationFails()
      .statusCode(201)
      .extract().header("Location");

    given() // get it via its location
      .header(TEN).header(USER7)
      .get(notify3Loc)
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("id")) // auto-generated id field
      .body(containsString("999999"));  // uuid of mockuser9

    given() // get it via the link.
      .header(TEN).header(USER7)
      .get("/notify?query=link=*34567*")
      .then().log().all() // ifValidationFails()
      .statusCode(200)
      .body(containsString("id")) // auto-generated id field
      .body(containsString("999999"));  // uuid of mockuser9

    given() // get it via the createdBy in the metadata
      .header(TEN).header(USER7)
      .get("/notify?query=metadata.createdByUserId=88888888-8888-8888-8888-888888888888")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("\"totalRecords\" : 2"))
      .body(containsString("id")) // auto-generated id field
      .body(containsString("999999"));  // uuid of mockuser9

    // TEMPORARY - list them all
    given()
      .header(TEN).header(USER7)
      .get("/notify")
      .then().log().all()
      .statusCode(200);

    // offsets and limits
    given()
      .header(TEN).header(USER7)
      .get("/notify?offset=0&limit=2")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("things/23456"))
      .body(containsString("users/1234"));
    given()
      .header(TEN).header(USER7)
      .get("/notify?offset=1&limit=2")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("First notification"))
      .body(containsString("mockuser9"));
    given()
      .header(TEN).header(USER7)
      .get("/notify?offset=2&limit=0")
      .then().log().ifValidationFails()
      .statusCode(400)
      .body(containsString("must be greater than or equal to 1"));
    given()
      .header(TEN).header(USER7)
      .get("/notify?limit=0")
      .then().log().ifValidationFails()
      .statusCode(400)
      .body(containsString("must be greater than or equal to 1"));

    // bad offsets
    given()
      .header(TEN).header(USER7)
      .get("/notify?offset=999&limit=1")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("\"totalRecords\" : 0"));
    given()
      .header(TEN).header(USER7)
      .get("/notify?offset=-99&limit=1")
      .then().log().ifValidationFails()
      .statusCode(400)
      .body(containsString("must be greater than or equal to 0"));
    given()
      .header(TEN).header(USER7)
      .get("/notify?offset=2147483647&limit=1")
      .then().log().ifValidationFails()
      .statusCode(400)
      .body(containsString("must be less than or equal to 1000"));

    // bad limits
    given()
      .header(TEN).header(USER7)
      .get("/notify?offset=1&limit=-1")
      .then().log().ifValidationFails()
      .statusCode(400)
      .body(containsString("must be greater than or equal to 1"));
    given()
      .header(TEN).header(USER7)
      .get("/notify?limit=2147483647")
      .then().log().ifValidationFails()
      .statusCode(400)
      .body(containsString("must be less than or equal to 100"));

    // lang
    given()
      .header(TEN).header(USER7)
      .get("/notify?limit=1&lang=UNKNOWNLANG")
      .then().log().ifValidationFails()
      .statusCode(400)
      .body(containsString("parameter is incorrect"));
    // RMB validates lang to match [A-Za-z]{2}, but does not know individual codes
    given()
      .header(TEN).header(USER7)
      .get("/notify?limit=1&lang=ZZ")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("\"totalRecords\" : 3"));


    // _self
    given()
      .header(TEN)
      .get("/notify/_self")
      .then().log().ifValidationFails()
      .statusCode(400)
      .body(containsString("No UserId"));

    given()
      .header(TEN).header(USER7)
      .get("/notify/_self")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("\"totalRecords\" : 2")) // both match recipient 7
      .body(containsString("with a comment"));

    given()
      .header(TEN).header(USER7)
      .get("/notify/_self?query=seen=true")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("\"totalRecords\" : 1"))
      .body(containsString("First"));

    given()
      .header(TEN).header(USER8)
      .get("/notify/_self")
      .then().log().ifValidationFails()
      .body(containsString("\"totalRecords\" : 0")); // none match 8

    // Failed deletes
    given()
      .header(TEN)
      .delete("/notify/11111111-3-1111-333-111111111111") // Bad UUID
      .then().log().ifValidationFails()
      .statusCode(422);

    given()
      .header(TEN)
      .delete("/notify/11111111-2222-3333-4444-555555555555") // not found
      .then().log().ifValidationFails()
      .statusCode(404);

    // self delete 1111
    given()
      .header(TEN).header(USER7)
      .delete("/notify/_self?olderthan=2001-01-01")
      .then().log().ifValidationFails()
      .statusCode(404); // too new

    given()
      .header(TEN)
      .delete("/notify/_self?olderthan=2099-01-01")
      .then().log().ifValidationFails()
      .statusCode(400)
      .body(containsString("No UserId"));

    given()
      .header(TEN).header(USER7)
      .delete("/notify/_self?olderthan=2099-01-01")
      .then().log().ifValidationFails()
      .statusCode(204); // gone!
    given()
      .header(TEN).header(USER7)
      .delete("/notify/_self") // no query
      .then().log().ifValidationFails()
      .statusCode(404); // already gone

    // delete notify3
    given()
      .header(TEN)
      .delete(notify3Loc)
      .then().log().ifValidationFails()
      .statusCode(204);

    // delete 2222
    given()
      .header(TEN)
      .delete("/notify/22222222-2222-2222-2222-222222222222")
      .then().log().ifValidationFails()
      .statusCode(204);

    given() // delete again, not found
      .header(TEN)
      .delete("/notify/22222222-2222-2222-2222-222222222222")
      .then().log().ifValidationFails()
      .statusCode(404);

    given()
      .header(TEN)
      .get("/notify")
      .then().log().ifValidationFails()
      .statusCode(200)
      .body(containsString("\"notifications\" : [ ]"));

    // All done
    logger.info("notifyTest done");
  }

}
