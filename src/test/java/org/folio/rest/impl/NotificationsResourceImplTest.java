package org.folio.rest.impl;

import static io.vertx.core.Future.failedFuture;
import static io.vertx.core.Future.succeededFuture;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.awaitility.Awaitility;
import org.folio.client.OkapiModulesClient;
import org.folio.dbschema.ObjectMapperTool;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.EventEntity;
import org.folio.rest.jaxrs.model.Notification;
import org.folio.rest.jaxrs.model.ResultInfo;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.persist.helpers.LocalRowSet;
import org.folio.rest.persist.interfaces.Results;
import org.folio.rest.tools.client.interfaces.HttpClientInterface;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;

@RunWith(MockitoJUnitRunner.class)
public class NotificationsResourceImplTest {
  private final Logger logger = LogManager.getLogger(NotificationsResourceImplTest.class);

  private static final String USER_ID = UUID.randomUUID().toString();
  private static final String USERNAME = "username";
  private static final String LANG = "en";

  @Spy
  private NotificationsResourceImpl notificationsResource;

  @Mock
  private HttpClientInterface httpClient;

  @Mock
  private PostgresClient postgresClient;

  @Mock
  private OkapiModulesClient okapiModulesClient;

  @Mock
  private Notification notification;

  private boolean handlerIsCalled;
  private Map<String, String> okapiHeaders = new HashMap<>();
  private int status;

  @Before
  public void setUp() {
    handlerIsCalled = false;

    doReturn(httpClient).when(notificationsResource).getHttpClient(any(), any());
    doReturn(postgresClient).when(notificationsResource).getPostgresClient(any(), any());
    doReturn(okapiModulesClient).when(notificationsResource).getOkapiModulesClient(any(), any());
  }

  @Test
  public void shouldReturn200WhenGetNotifySucceeded() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    doAnswer(invocationOnMock -> {
      Results<Notification> results = new Results<>();
      results.setResults(Collections.singletonList(notification));
      results.setResultInfo(new ResultInfo().withTotalRecords(1));
      ((Handler<AsyncResult<Results<Notification>>>) invocationOnMock.getArgument(6))
        .handle(makeAsyncResult(results, true));
      return null;
    }).when(postgresClient)
      .get(any(String.class), any(), any(), any(CQLWrapper.class), any(Boolean.class),
        any(Boolean.class), any(Handler.class));

    notificationsResource.getNotify("", 0, 10, LANG, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(200, status);
  }

  @Test
  public void shouldReturn500WhenGetNotifyFailed() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    doAnswer(invocationOnMock -> {
      ((Handler<AsyncResult<Results<Notification>>>) invocationOnMock.getArgument(6))
        .handle(makeAsyncResult(null, false));
      return null;
    }).when(postgresClient)
      .get(any(String.class), any(), any(), any(CQLWrapper.class), any(Boolean.class),
        any(Boolean.class), any(Handler.class));

    notificationsResource.getNotify("", 0, 10, LANG, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(500, status);
  }

  @Test
  public void shouldReturn200WhenGetNotifySelfSucceeded() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    doAnswer(invocationOnMock -> {
      Results<Notification> results = new Results<>();
      results.setResults(Collections.singletonList(notification));
      results.setResultInfo(new ResultInfo().withTotalRecords(1));
      ((Handler<AsyncResult<Results<Notification>>>) invocationOnMock.getArgument(6))
        .handle(makeAsyncResult(results, true));
      return null;
    }).when(postgresClient)
      .get(any(String.class), any(), any(), any(CQLWrapper.class), any(Boolean.class),
        any(Boolean.class), any(Handler.class));

    okapiHeaders.put(RestVerticle.OKAPI_USERID_HEADER, "user-id");

    notificationsResource.getNotifyNotificationSelf("", 0, 10, LANG, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(200, status);
  }

  @Test
  public void shouldReturn400WhenGetNotifySelfWithoutUserId() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    notificationsResource.getNotifyNotificationSelf("", 0, 10, LANG, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(400, status);
  }

  @Test
  public void shouldReturn201WhenPostNotifyUsernameByUsernameWithNoEventConfig() throws Exception {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    org.folio.rest.tools.client.Response response = new org.folio.rest.tools.client.Response();
    response.setCode(200);
    response.setBody(new JsonObject()
      .put("users", Arrays.asList(new JsonObject().put("id", USER_ID)))
      .put("totalRecords", 1)
    );

    doReturn(completedFuture(response)).when(httpClient).request(any(String.class), any(Map.class));
    doReturn("recipient").when(notification).getRecipientId();
    doAnswer(invocationOnMock -> {
      ((Handler<AsyncResult<String>>) invocationOnMock.getArgument(3))
        .handle(makeAsyncResult("entity-id", true));
      return null;
    }).when(postgresClient).save(any(String.class), any(String.class), any(), any());

    notificationsResource.postNotifyUsernameByUsername(USERNAME, LANG, notification,
      okapiHeaders, handler, null);

    verify(notification, times(1)).setRecipientId(USER_ID);
    verify(notificationsResource, times(1)).postNotify(any(), any(), any(), any(), any());

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(201, status);
  }

  @Test
  public void shouldReturn201WhenPostNotifyUsernameByUsernameWithEventConfig() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    mockServicesForSuccessfulPost();
    doReturn(succeededFuture()).when(okapiModulesClient).postMessageDelivery(any());

    notificationsResource.postNotifyUsernameByUsername(USERNAME, LANG, notification,
      okapiHeaders, handler, null);

    verify(notification, times(1)).setRecipientId(USER_ID);
    verify(notificationsResource, times(1)).postNotify(any(), any(), any(), any(), any());

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(201, status);
  }

  @Test
  public void shouldReturn400WhenPostNotifyUsernameByUsernameWithEventConfigAnd400Returned() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    mockServicesForSuccessfulPost();
    doReturn(failedFuture(new BadRequestException())).when(okapiModulesClient)
      .postMessageDelivery(any());

    notificationsResource.postNotifyUsernameByUsername(USERNAME, LANG, notification,
      okapiHeaders, handler, null);

    verify(notification, times(1)).setRecipientId(USER_ID);
    verify(notificationsResource, times(1)).postNotify(any(), any(), any(), any(), any());

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(400, status);
  }

  @Test
  public void shouldReturn500WhenPostNotifyUsernameByUsernameWithEventConfigAnd500Returned() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    mockServicesForSuccessfulPost();
    doReturn(failedFuture(new Exception())).when(okapiModulesClient)
      .postMessageDelivery(any());

    notificationsResource.postNotifyUsernameByUsername(USERNAME, LANG, notification,
      okapiHeaders, handler, null);

    verify(notification, times(1)).setRecipientId(USER_ID);
    verify(notificationsResource, times(1)).postNotify(any(), any(), any(), any(), any());

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(500, status);
  }

  @Test
  public void shouldReturn400WhenPostNotifyUsernameByUsernameWithNoUsers() throws Exception {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    org.folio.rest.tools.client.Response response = new org.folio.rest.tools.client.Response();
    response.setCode(200);
    response.setBody(new JsonObject()
      .put("users", new JsonObject()));

    doReturn(completedFuture(response)).when(httpClient).request(any(String.class), any(Map.class));

    notificationsResource.postNotifyUsernameByUsername(USERNAME, LANG, notification,
      okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(400, status);
  }

  @Test
  public void shouldReturn400WhenPostNotifyUsernameByUsernameWithoutUsersElement()
    throws Exception {

    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    org.folio.rest.tools.client.Response response = new org.folio.rest.tools.client.Response();
    response.setCode(200);
    response.setBody(new JsonObject()
      .put("totalRecords", 1)
    );

    doReturn(completedFuture(response)).when(httpClient).request(any(String.class), any(Map.class));

    notificationsResource.postNotifyUsernameByUsername(USERNAME, LANG, notification,
      okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(400, status);
  }

  @Test
  public void shouldReturn400WhenPostNotifyUsernameByUsernameUserLookupFailedWith403()
    throws Exception {

    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    org.folio.rest.tools.client.Response response = new org.folio.rest.tools.client.Response();
    response.setCode(403);

    doReturn(completedFuture(response)).when(httpClient).request(any(String.class), any(Map.class));

    notificationsResource.postNotifyUsernameByUsername(USERNAME, LANG, notification,
      okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(400, status);
  }

  @Test
  public void shouldReturn500WhenPostNotifyUsernameByUsernameUserLookupFailedWith500()
    throws Exception {

    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    org.folio.rest.tools.client.Response response = new org.folio.rest.tools.client.Response();
    response.setCode(500);

    doReturn(completedFuture(response)).when(httpClient).request(any(String.class), any(Map.class));

    notificationsResource.postNotifyUsernameByUsername(USERNAME, LANG, notification,
      okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(500, status);
  }

  @Test
  public void shouldReturn400WhenDeleteNotifySelfWithoutUserIdHeader() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    notificationsResource.deleteNotifyNotificationSelf("2020-01-01", LANG, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(400, status);
  }

  @Test
  public void shouldReturn204WhenDeleteNotifySelfSucceeded() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    okapiHeaders.put(RestVerticle.OKAPI_USERID_HEADER, "user-id");
    doAnswer(invocationOnMock -> {
      ((Handler<AsyncResult<RowSet<Row>>>) invocationOnMock.getArgument(2))
        .handle(makeAsyncResult(new LocalRowSet(1), true));
      return null;
    }).when(postgresClient).delete(any(String.class), any(CQLWrapper.class), any());

    notificationsResource.deleteNotifyNotificationSelf("2020-01-01", LANG, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(204, status);
  }

  @Test
  public void shouldReturn404WhenDeleteNotifySelfNotFound() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    okapiHeaders.put(RestVerticle.OKAPI_USERID_HEADER, "user-id");
    doAnswer(invocationOnMock -> {
      ((Handler<AsyncResult<RowSet<Row>>>) invocationOnMock.getArgument(2))
        .handle(makeAsyncResult(new LocalRowSet(0), true));
      return null;
    }).when(postgresClient).delete(any(String.class), any(CQLWrapper.class), any());

    notificationsResource.deleteNotifyNotificationSelf("2020-01-01", LANG, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(404, status);
  }

  @Test
  public void shouldReturn500WhenDeleteNotifySelfFailed() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    okapiHeaders.put(RestVerticle.OKAPI_USERID_HEADER, "user-id");
    doAnswer(invocationOnMock -> {
      ((Handler<AsyncResult<RowSet<Row>>>) invocationOnMock.getArgument(2))
        .handle(makeAsyncResult(new LocalRowSet(0), false));
      return null;
    }).when(postgresClient).delete(any(String.class), any(CQLWrapper.class), any());

    notificationsResource.deleteNotifyNotificationSelf("2020-01-01", LANG, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(500, status);
  }

  @Test
  public void shouldReturn200WhenGetByIdSucceeded() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    doAnswer(invocationOnMock -> {
      ObjectMapper mapper = ObjectMapperTool.getMapper();
      ((Handler<AsyncResult<Notification>>) invocationOnMock.getArgument(3))
        .handle(makeAsyncResult(mapper.readValue("{}", Notification.class), true));
      return null;
    }).when(postgresClient).getById(any(String.class), any(String.class), any(Class.class), any());

    notificationsResource.getNotifyById(UUID.randomUUID().toString(),
      LANG, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(200, status);
  }

  @Test
  public void shouldReturn500WhenGetByIdFailed() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    doAnswer(invocationOnMock -> {
      ObjectMapper mapper = ObjectMapperTool.getMapper();
      ((Handler<AsyncResult<Notification>>) invocationOnMock.getArgument(3))
        .handle(makeAsyncResult(mapper.readValue("{}", Notification.class), false));
      return null;
    }).when(postgresClient).getById(any(String.class), any(String.class), any(Class.class), any());

    notificationsResource.getNotifyById(UUID.randomUUID().toString(),
      LANG, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(500, status);
  }

  @Test
  public void shouldReturn404WhenGetByIdNotFound() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    doAnswer(invocationOnMock -> {
      ((Handler<AsyncResult<Notification>>) invocationOnMock.getArgument(3))
        .handle(makeAsyncResult(null, true));
      return null;
    }).when(postgresClient).getById(any(String.class), any(String.class), any(Class.class), any());

    notificationsResource.getNotifyById(UUID.randomUUID().toString(),
      LANG, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(404, status);
  }

  @Test
  public void shouldReturn204WhenDeleteByIdSucceeded() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    doAnswer(invocationOnMock -> {
      ((Handler<AsyncResult<RowSet<Row>>>) invocationOnMock.getArgument(2))
        .handle(makeAsyncResult(new LocalRowSet(1), true));
      return null;
    }).when(postgresClient).delete(any(String.class), any(String.class), any());

    notificationsResource.deleteNotifyById(UUID.randomUUID().toString(),
      LANG, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(204, status);
  }

  @Test
  public void shouldReturn500WhenDeletedByIdFailed() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    doAnswer(invocationOnMock -> {
      ((Handler<AsyncResult<RowSet<Row>>>) invocationOnMock.getArgument(2))
        .handle(makeAsyncResult(new LocalRowSet(1), false));
      return null;
    }).when(postgresClient).delete(any(String.class), any(String.class), any());

    notificationsResource.deleteNotifyById(UUID.randomUUID().toString(),
      LANG, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(500, status);
  }

  @Test
  public void shouldReturn404WhenDeleteByIdNotFound() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    doAnswer(invocationOnMock -> {
      ((Handler<AsyncResult<RowSet<Row>>>) invocationOnMock.getArgument(2))
        .handle(makeAsyncResult(new LocalRowSet(0), true));
      return null;
    }).when(postgresClient).delete(any(String.class), any(String.class), any());

    notificationsResource.deleteNotifyById(UUID.randomUUID().toString(), LANG, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(404, status);
  }

  @Test
  public void shouldReturn204WhenPutNotifyByIdSucceeded() throws JsonProcessingException {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    doAnswer(invocationOnMock -> {
      ((Handler<AsyncResult<RowSet<Row>>>) invocationOnMock.getArgument(3))
        .handle(makeAsyncResult(new LocalRowSet(1), true));
      return null;
    }).when(postgresClient).update(any(String.class), any(), any(String.class), any());

    doAnswer(invocationOnMock -> {
      ((Handler<AsyncResult<RowSet<Row>>>) invocationOnMock.getArgument(2))
        .handle(makeAsyncResult(new LocalRowSet(1), true));
      return null;
    }).when(postgresClient).delete(any(String.class), any(CQLWrapper.class), any());

    String id = UUID.randomUUID().toString();
    ObjectMapper mapper = ObjectMapperTool.getMapper();
    Notification notification = mapper.readValue(new JsonObject()
        .put("id", id)
        .put("recipientId", "recipient-id")
        .encode(),
      Notification.class);

    notificationsResource.putNotifyById(id, LANG, notification, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(204, status);
  }

  @Test
  public void shouldReturn422WhenPutNotifyByIdWithWrongId() throws JsonProcessingException {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    ObjectMapper mapper = ObjectMapperTool.getMapper();
    Notification notification = mapper.readValue(new JsonObject()
        .put("id", "id")
        .put("recipientId", "recipient-id")
        .encode(),
      Notification.class);

    notificationsResource.putNotifyById("different-id", LANG, notification, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(422, status);
  }

  @Test
  public void shouldReturn422WhenPutNotifyByIdWithNoRecipient() throws JsonProcessingException {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    ObjectMapper mapper = ObjectMapperTool.getMapper();
    Notification notification = mapper.readValue(new JsonObject()
        .put("id", "id")
        .encode(),
      Notification.class);

    notificationsResource.putNotifyById("id", LANG, notification, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(422, status);
  }

  @Test
  public void shouldReturn404WhenPutNotifyByIdZeroUpdated() throws JsonProcessingException {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    doAnswer(invocationOnMock -> {
      ((Handler<AsyncResult<RowSet<Row>>>) invocationOnMock.getArgument(3))
        .handle(makeAsyncResult(new LocalRowSet(0), true));
      return null;
    }).when(postgresClient).update(any(String.class), any(), any(String.class), any());

    String id = UUID.randomUUID().toString();
    ObjectMapper mapper = ObjectMapperTool.getMapper();
    Notification notification = mapper.readValue(new JsonObject()
        .put("id", id)
        .put("recipientId", "recipient-id")
        .encode(),
      Notification.class);

    notificationsResource.putNotifyById(id, LANG, notification, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(404, status);
  }

  @Test
  public void shouldReturn500WhenPutNotifyByIdFailed() throws JsonProcessingException {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;
      status = responseAsyncResult.result().getStatus();
    };

    doAnswer(invocationOnMock -> {
      ((Handler<AsyncResult<RowSet<Row>>>) invocationOnMock.getArgument(3))
        .handle(makeAsyncResult(new LocalRowSet(0), false));
      return null;
    }).when(postgresClient).update(any(String.class), any(), any(String.class), any());

    String id = UUID.randomUUID().toString();
    ObjectMapper mapper = ObjectMapperTool.getMapper();
    Notification notification = mapper.readValue(new JsonObject()
        .put("id", id)
        .put("recipientId", "recipient-id")
        .encode(),
      Notification.class);

    notificationsResource.putNotifyById(id, LANG, notification, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);

    assertEquals(500, status);
  }

  private <T> AsyncResult<T> makeAsyncResult(T result, boolean succeeded) {
    return new AsyncResult<T>() {
      @Override
      public T result() {
        return result;
      }

      @Override
      public Throwable cause() {
        return null;
      }

      @Override
      public boolean succeeded() {
        return succeeded;
      }

      @Override
      public boolean failed() {
        return !succeeded;
      }
    };
  }

  private void mockServicesForSuccessfulPost() {
    org.folio.rest.tools.client.Response response = new org.folio.rest.tools.client.Response();
    response.setCode(200);
    response.setBody(new JsonObject()
      .put("users", Arrays.asList(new JsonObject().put("id", USER_ID)))
      .put("totalRecords", 1)
    );

    try {
      doReturn(completedFuture(response)).when(httpClient)
        .request(any(String.class), any(Map.class));
    }
    catch (Exception e) {
      logger.error(e.getMessage());
    }
    doReturn("recipient").when(notification).getRecipientId();
    doReturn("event-config-name").when(notification).getEventConfigName();
    doReturn(succeededFuture(new EventEntity())).when(okapiModulesClient)
      .getEventConfig(eq("event-config-name"));
    doAnswer(invocationOnMock -> {
      ((Handler<AsyncResult<String>>) invocationOnMock.getArgument(3))
        .handle(makeAsyncResult("entity-id", true));
      return null;
    }).when(postgresClient).save(any(String.class), any(String.class), any(), any());
  }
}
