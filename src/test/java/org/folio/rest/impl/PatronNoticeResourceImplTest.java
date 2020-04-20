package org.folio.rest.impl;


import static io.vertx.core.Future.failedFuture;
import static io.vertx.core.Future.succeededFuture;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.http.HttpHeaders;
import org.awaitility.Awaitility;
import org.folio.client.OkapiModulesClient;
import org.folio.helper.OkapiModulesClientHelper;
import org.folio.rest.jaxrs.model.Context;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.NotifySendRequest;
import org.folio.rest.jaxrs.model.PatronNoticeEntity;
import org.folio.rest.jaxrs.model.TemplateProcessingRequest;
import org.folio.rest.jaxrs.model.TemplateProcessingResult;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;

@RunWith(MockitoJUnitRunner.class)
public class PatronNoticeResourceImplTest {
  private static final String LANG = "lang";

  @Spy
  private PatronNoticeResourceImpl patronNoticeResource;

  @Mock
  private OkapiModulesClient client;

  @Spy
  private OkapiModulesClientHelper okapiModulesClientHelper;

  private Map<String, String> okapiHeaders = new HashMap<>();
  private PatronNoticeEntity entity = makeEntity();
  private TemplateProcessingResult templateProcessingResult = new TemplateProcessingResult();
  private TemplateProcessingRequest templateProcessingRequest = new TemplateProcessingRequest();

  private boolean handlerIsCalled;

  @Before
  public void setUp() {
    doReturn(client).when(patronNoticeResource).makeOkapiModulesClient(any(), any());

    doReturn(succeededFuture(templateProcessingResult)).when(client)
      .postTemplateRequest(templateProcessingRequest);

    doReturn(templateProcessingRequest).when(okapiModulesClientHelper)
      .buildTemplateProcessingRequest(entity);

    doReturn(new NotifySendRequest()).when(okapiModulesClientHelper)
      .buildNotifySendRequest(templateProcessingResult, entity);

    doReturn(okapiModulesClientHelper).when(patronNoticeResource).getOkapiModulesClientHelper();

    handlerIsCalled = false;
  }

  @Test
  public void shouldCallHandlerWith200StatusWhenMessageIsDelivered() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;

      assertTrue(responseAsyncResult.succeeded());
      assertEquals(responseAsyncResult.result().getStatus(), 200);

      verify(okapiModulesClientHelper, times(1)).buildTemplateProcessingRequest(entity);
      verify(okapiModulesClientHelper, times(1)).buildNotifySendRequest(
        templateProcessingResult, entity);
    };

    doReturn(succeededFuture()).when(client).postMessageDelivery(any());

    patronNoticeResource.postPatronNotice(LANG, entity, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);
  }

  @Test
  public void shouldCallHandlerWith500StatusWhenMessageIsNotDelivered() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;

      assertTrue(responseAsyncResult.succeeded());
      assertEquals(responseAsyncResult.result().getStatus(), 500);
      assertEquals(responseAsyncResult.result().getHeaderString(HttpHeaders.CONTENT_TYPE),
        MediaType.TEXT_PLAIN);
      assertEquals(responseAsyncResult.result().getEntity(),
        "Internal Server Error, Please contact System Administrator or try again");
    };

    doReturn(failedFuture(new Exception())).when(client).postMessageDelivery(any());

    patronNoticeResource.postPatronNotice(LANG, entity, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);
  }

  @Test
  public void shouldCallHandlerWith422StatusWhenBadRequest() {
    Handler<AsyncResult<Response>> handler = responseAsyncResult -> {
      handlerIsCalled = true;

      assertTrue(responseAsyncResult.succeeded());
      assertEquals(responseAsyncResult.result().getStatus(), 422);
      assertEquals(responseAsyncResult.result().getHeaderString(HttpHeaders.CONTENT_TYPE),
        MediaType.APPLICATION_JSON);
      assertThat(responseAsyncResult.result().getEntity(), instanceOf(Errors.class));

      Errors errors = (Errors) responseAsyncResult.result().getEntity();

      assertEquals(errors.getErrors().size(), 1);
      assertEquals(errors.getErrors().get(0).getMessage(), "HTTP 400 Bad Request");
    };

    doReturn(failedFuture(new BadRequestException())).when(client).postMessageDelivery(any());

    patronNoticeResource.postPatronNotice(LANG, entity, okapiHeaders, handler, null);

    Awaitility.await()
      .atMost(1, TimeUnit.SECONDS)
      .until(() -> handlerIsCalled);
  }

  PatronNoticeEntity makeEntity() {
    return new PatronNoticeEntity()
      .withContext(new Context())
      .withDeliveryChannel("delivery-channel")
      .withLang(LANG)
      .withOutputFormat("format")
      .withRecipientId("recipient-id")
      .withTemplateId("template-id");
  }
}
