package org.folio.rest.impl;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.common.ConsoleNotifier;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.jayway.restassured.RestAssured;
import com.jayway.restassured.builder.RequestSpecBuilder;
import com.jayway.restassured.http.ContentType;
import com.jayway.restassured.response.Header;
import com.jayway.restassured.specification.RequestSpecification;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.http.HttpStatus;
import org.folio.rest.RestVerticle;
import org.folio.rest.client.TenantClient;
import org.folio.rest.jaxrs.model.EventEntity;
import org.folio.rest.jaxrs.model.EventEntityCollection;
import org.folio.rest.jaxrs.model.Meta;
import org.folio.rest.jaxrs.model.Notification;
import org.folio.rest.jaxrs.model.Result;
import org.folio.rest.jaxrs.model.Template;
import org.folio.rest.jaxrs.model.TemplateProcessingResult;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.util.Collections;
import java.util.UUID;

@RunWith(VertxUnitRunner.class)
public class SendNotifyTest {

  private static Vertx vertx;
  private static RequestSpecification spec;

  private static final String OKAPI_HEADER_URL = "x-okapi-url";
  private static final String EVENT_CONFIG_PATH_PATTERN = "/eventConfig.*";
  private static final String SEND_NOTIFICATION_PATH = "/message-delivery";
  private static final String PROCESS_TEMPLATE_PATH = "/template-request";
  private static final String POST_NOTIFICATION_PATH = "/notify";
  private static final String LOCALHOST = "http://localhost:";
  private static final String HTTP_PORT_JSON_PATH = "http.port";
  private static final String RESET_PASSWORD_EVENT_NAME = "RESET_PASSWORD_EVENT_NAME";
  private static final String NONEXISTENT_EVENT_NAME = "NONEXISTENT_EVENT_NAME";
  private static final String TEMPLATE_ID = "d7de69f8-c5b4-4425-8ad1-c7511166ff63";
  private static final String RECIPIENT_ID = "a049c22f-694b-41cf-a3b4-8eefd3685cdd";
  private static final String EMAIL_DELIVERY_CHANNEL = "email";
  private static final String PLAIN_TEXT_OUTPUT_FORMAT = "text/plain";
  private static final String PROCESSING_RESULT_HEADER = "Hello message for Alex";
  private static final String PROCESSING_RESULT_BODY = "Hello Alex";
  private static final String TOKEN_STUB = "token_stub";
  private static final String TENANT = "diku";
  private static final String ENGLISH_LANGUAGE_CODE = "en";
  private static final Header OKAPI_HEADER_TOKEN = new Header(RestVerticle.OKAPI_HEADER_TOKEN, TOKEN_STUB);

  private Header mockUrlHeader;

  @Rule
  public WireMockRule userMockServer = new WireMockRule(
    WireMockConfiguration.wireMockConfig()
      .dynamicPort()
      .notifier(new ConsoleNotifier(true)));

  @BeforeClass
  public static void setUp(TestContext context) {
    Async async = context.async();
    vertx = Vertx.vertx();
    int port = NetworkUtils.nextFreePort();

    try {
      PostgresClient.setIsEmbedded(true);
      PostgresClient.getInstance(vertx).startEmbeddedPostgres();
    } catch (Exception e) {
      context.fail(e);
    }

    TenantClient tenantClient = new TenantClient("localhost", port, TENANT, "diku");
    DeploymentOptions options = new DeploymentOptions().setConfig(new JsonObject().put(HTTP_PORT_JSON_PATH, port));
    vertx.deployVerticle(RestVerticle.class.getName(), options, result -> {
      try {
        tenantClient.postTenant(null, x -> async.complete());
      } catch (Exception e) {
        context.fail(e);
        async.complete();
      }
    });

    spec = new RequestSpecBuilder()
      .setContentType(ContentType.JSON)
      .setBaseUri(LOCALHOST + port)
      .addHeader(RestVerticle.OKAPI_HEADER_TENANT, TENANT)
      .build();
  }

  @AfterClass
  public static void tearDown(TestContext context) {
    PostgresClient.stopEmbeddedPostgres();
    vertx.close(context.asyncAssertSuccess());
  }

  @Before
  public void setUp() {
    mockUrlHeader = new Header(OKAPI_HEADER_URL, LOCALHOST + userMockServer.port());
    mockHttpCalls();
  }

  @Test
  public void testValidRequest() {
    RestAssured.given()
      .spec(spec)
      .header(new Header(RestVerticle.OKAPI_HEADER_TOKEN, TOKEN_STUB))
      .header(mockUrlHeader)
      .when()
      .body(buildNotificationEntity(RESET_PASSWORD_EVENT_NAME).toString())
      .post(POST_NOTIFICATION_PATH)
      .then()
      .statusCode(HttpStatus.SC_CREATED);
  }

  @Test
  public void testNonexistentEventConfigId() {
    RestAssured.given()
      .spec(spec)
      .header(OKAPI_HEADER_TOKEN)
      .header(mockUrlHeader)
      .when()
      .body(buildNotificationEntity(NONEXISTENT_EVENT_NAME).toString())
      .post(POST_NOTIFICATION_PATH)
      .then()
      .statusCode(HttpStatus.SC_BAD_REQUEST);
  }

  private void mockHttpCalls() {
    WireMock.stubFor(
      WireMock.get(WireMock.urlMatching(EVENT_CONFIG_PATH_PATTERN))
        .withQueryParam("query", WireMock.equalTo("name==" + RESET_PASSWORD_EVENT_NAME))
        .withHeader(RestVerticle.OKAPI_HEADER_TENANT, WireMock.equalTo(TENANT))
        .withHeader(RestVerticle.OKAPI_HEADER_TOKEN, WireMock.equalTo(TOKEN_STUB))
        .withHeader(HttpHeaders.ACCEPT, WireMock.equalTo(MediaType.APPLICATION_JSON))
        .willReturn(WireMock.okJson(buildEventConfigListEntity().toString()).withStatus(HttpStatus.SC_OK))
    );

    WireMock.stubFor(
      WireMock.get(WireMock.urlMatching(EVENT_CONFIG_PATH_PATTERN))
        .withQueryParam("query", WireMock.equalTo("name==" + NONEXISTENT_EVENT_NAME))
        .withHeader(RestVerticle.OKAPI_HEADER_TENANT, WireMock.equalTo(TENANT))
        .withHeader(RestVerticle.OKAPI_HEADER_TOKEN, WireMock.equalTo(TOKEN_STUB))
        .withHeader(HttpHeaders.ACCEPT, WireMock.equalTo(MediaType.APPLICATION_JSON))
        .willReturn(WireMock.okJson(buildEventConfigListEntity().toString()).withStatus(HttpStatus.SC_NOT_FOUND))
    );

    WireMock.stubFor(
      WireMock.post(PROCESS_TEMPLATE_PATH)
        .withHeader(RestVerticle.OKAPI_HEADER_TENANT, WireMock.equalTo(TENANT))
        .withHeader(RestVerticle.OKAPI_HEADER_TOKEN, WireMock.equalTo(TOKEN_STUB))
        .withHeader(HttpHeaders.ACCEPT, WireMock.equalTo(MediaType.APPLICATION_JSON))
        .withHeader(HttpHeaders.CONTENT_TYPE, WireMock.equalTo(MediaType.APPLICATION_JSON))
        .willReturn(WireMock.okJson(buildTemplateProcessingResult().toString()).withStatus(HttpStatus.SC_OK))
    );

    WireMock.stubFor(
      WireMock.post(SEND_NOTIFICATION_PATH)
        .withHeader(RestVerticle.OKAPI_HEADER_TENANT, WireMock.equalTo(TENANT))
        .withHeader(RestVerticle.OKAPI_HEADER_TOKEN, WireMock.equalTo(TOKEN_STUB))
        .withHeader(HttpHeaders.ACCEPT, WireMock.equalTo(MediaType.TEXT_PLAIN))
        .willReturn(WireMock.noContent())
    );
  }

  private JsonObject buildNotificationEntity(String eventConfigName) {
    Notification notification = new Notification();
    notification.setId(UUID.randomUUID().toString());
    notification.setRecipientId(RECIPIENT_ID);
    notification.setEventConfigName(eventConfigName);
    notification.setLang(ENGLISH_LANGUAGE_CODE);
    notification.setText("");
    return JsonObject.mapFrom(notification);
  }

  private JsonObject buildEventConfigListEntity() {
    Template template = new Template();
    template.setTemplateId(TEMPLATE_ID);
    template.setDeliveryChannel(EMAIL_DELIVERY_CHANNEL);
    template.setOutputFormat(PLAIN_TEXT_OUTPUT_FORMAT);
    EventEntity entity = new EventEntity();
    entity.setTemplates(Collections.singletonList(template));
    EventEntityCollection collection = new EventEntityCollection()
      .withEventEntity(Collections.singletonList(entity))
      .withTotalRecords(1);
    return JsonObject.mapFrom(collection);
  }

  private JsonObject buildTemplateProcessingResult() {
    Result result = new Result();
    result.setHeader(PROCESSING_RESULT_HEADER);
    result.setBody(PROCESSING_RESULT_BODY);
    TemplateProcessingResult templateProcessingResult = new TemplateProcessingResult();
    templateProcessingResult.setResult(result);
    templateProcessingResult.setMeta(new Meta().withOutputFormat(PLAIN_TEXT_OUTPUT_FORMAT));
    return JsonObject.mapFrom(templateProcessingResult);
  }
}
