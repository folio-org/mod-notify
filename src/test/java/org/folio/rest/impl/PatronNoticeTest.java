package org.folio.rest.impl;

import com.github.tomakehurst.wiremock.common.ConsoleNotifier;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.Attachment;
import org.folio.rest.jaxrs.model.PatronNoticeEntity;
import org.folio.rest.jaxrs.model.Result;
import org.folio.rest.jaxrs.model.TemplateProcessingResult;
import org.folio.rest.tools.utils.NetworkUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;

import java.util.Collections;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static io.vertx.core.json.JsonObject.mapFrom;
import static javax.ws.rs.core.MediaType.TEXT_HTML;

@RunWith(VertxUnitRunner.class)
public class PatronNoticeTest {

  private static final String RECIPIENT_ID = "76ac0247-6629-4623-97c1-e761a3065878";
  private static final String INCORRECT_RECIPIENT_ID = "bc69b781-93d6-49e4-9f0c-6b24554dd56f";
  private static final String TEMPLATE_ID = "7946c79f-1472-4058-af70-5d5936dc1894";
  private static final String INCORRECT_TEMPLATE_ID = "d7709dcb-f14c-45a1-8837-3c9b2c313ca2";

  private static final String TEMPLATE_ID_JSON_PATH = "templateId";
  private static final String RECIPIENT_ID_JSON_PATH = "recipientUserId";
  private static final String DELIVERY_CHANNEL = "email";

  private static Vertx vertx;
  private static RequestSpecification spec;

  @ClassRule
  public static WireMockRule mockServer = new WireMockRule(
    WireMockConfiguration.wireMockConfig()
      .dynamicPort()
      .notifier(new ConsoleNotifier(true)));

  @BeforeClass
  public static void setUp(TestContext context) {

    vertx = Vertx.vertx();
    int port = NetworkUtils.nextFreePort();

    mockOkapiModules();

    spec = new RequestSpecBuilder()
      .setBaseUri("http://localhost:" + port)
      .addHeader(RestVerticle.OKAPI_HEADER_TENANT, "test")
      .addHeader(RestVerticle.OKAPI_HEADER_TOKEN, "test")
      .addHeader("x-okapi-url", "http://localhost:" + mockServer.port())
      .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
      .build();

    DeploymentOptions options = new DeploymentOptions().setConfig(new JsonObject().put("http.port", port));
    vertx.deployVerticle(RestVerticle.class, options, context.asyncAssertSuccess());
  }


  @AfterClass
  public static void tearDown(TestContext context) {
    vertx.close(context.asyncAssertSuccess());
  }

  @Test
  public void testPostPatronNotice() {

    PatronNoticeEntity entity = new PatronNoticeEntity()
      .withRecipientId(RECIPIENT_ID)
      .withTemplateId(TEMPLATE_ID)
      .withDeliveryChannel(DELIVERY_CHANNEL)
      .withOutputFormat(TEXT_HTML);

    RestAssured.given()
      .spec(spec)
      .body(entity)
      .when()
      .post("/patron-notice")
      .then()
      .statusCode(200);
  }

  @Test
  public void testPostPatronNoticeWithIncorrectTemplateId() {

    PatronNoticeEntity entity = new PatronNoticeEntity()
      .withRecipientId(RECIPIENT_ID)
      .withTemplateId(INCORRECT_TEMPLATE_ID)
      .withDeliveryChannel(DELIVERY_CHANNEL)
      .withOutputFormat(TEXT_HTML);

    RestAssured.given()
      .spec(spec)
      .body(entity)
      .when()
      .post("/patron-notice")
      .then()
      .statusCode(422);
  }

  @Test
  public void testPostPatronNoticeWithIncorrectRecipientId() {

    PatronNoticeEntity entity = new PatronNoticeEntity()
      .withRecipientId(INCORRECT_RECIPIENT_ID)
      .withTemplateId(TEMPLATE_ID)
      .withDeliveryChannel(DELIVERY_CHANNEL)
      .withOutputFormat(TEXT_HTML);

    RestAssured.given()
      .spec(spec)
      .body(entity)
      .when()
      .post("/patron-notice")
      .then()
      .statusCode(422);
  }

  private static void mockOkapiModules() {

    TemplateProcessingResult templateProcessingResult = new TemplateProcessingResult()
      .withTemplateId(TEMPLATE_ID)
      .withResult(new Result()
        .withHeader("header")
        .withBody("body")
        .withAttachments(buildAttachments()));

    mockServer.stubFor(post(urlMatching("/template-request"))
      .withRequestBody(matchingJsonPath(TEMPLATE_ID_JSON_PATH, equalTo(TEMPLATE_ID)))
      .willReturn(okJson(mapFrom(templateProcessingResult).encode())));

    mockServer.stubFor(post(urlMatching("/template-request"))
      .withRequestBody(matchingJsonPath(TEMPLATE_ID_JSON_PATH, equalTo(INCORRECT_TEMPLATE_ID)))
      .willReturn(ok().withStatus(400)));

    mockServer.stubFor(post(urlMatching("/message-delivery"))
      .withRequestBody(matchingJsonPath(RECIPIENT_ID_JSON_PATH, equalTo(RECIPIENT_ID)))
      .willReturn(ok().withStatus(204)));

    mockServer.stubFor(post(urlMatching("/message-delivery"))
      .withRequestBody(matchingJsonPath(RECIPIENT_ID_JSON_PATH, equalTo(INCORRECT_RECIPIENT_ID)))
      .willReturn(ok().withStatus(400)));
  }

  private static List<Attachment> buildAttachments() {
    Attachment attachment = new Attachment()
        .withData("ABCDEFG")
        .withContentType("image/png")
        .withDisposition("inline")
        .withName("test")
        .withContentId("<test>");
    return Collections.singletonList(attachment);
  }
}
