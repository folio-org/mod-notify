package org.folio.rest.impl;

import static io.vertx.core.Future.succeededFuture;
import static java.util.Collections.singletonList;
import static org.folio.util.LogUtil.asJson;
import static org.folio.util.LogUtil.loggingResponseHandler;

import java.util.Map;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.client.NoticesClient;
import org.folio.helper.OkapiModulesClientHelper;
import org.folio.rest.jaxrs.model.Error;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.PatronNoticeEntity;
import org.folio.rest.jaxrs.resource.PatronNotice;
import org.folio.rest.tools.messages.MessageConsts;
import org.folio.rest.tools.messages.Messages;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Handler;

public class PatronNoticeResourceImpl implements PatronNotice {

  private static final Logger log = LogManager.getLogger(PatronNoticeResourceImpl.class);
  private final Messages messages = Messages.getInstance();
  private final OkapiModulesClientHelper okapiModulesClientHelper = new OkapiModulesClientHelper();

  @Override
  public void postPatronNotice(String lang, PatronNoticeEntity entity,
    Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    log.debug("postPatronNotice:: parameters lang: {}, entityL {}", () -> lang,
      () -> asJson(entity));

    NoticesClient client = makeNoticesClient(vertxContext, okapiHeaders);
    Handler<AsyncResult<Response>> loggingResultHandler = loggingResponseHandler(
      "postPatronNotice", asyncResultHandler, log);

    client.postTemplateRequest(getOkapiModulesClientHelper().buildTemplateProcessingRequest(entity))
      .map(result -> getOkapiModulesClientHelper().buildNotifySendRequest(result, entity))
      .compose(client::postMessageDelivery)
      .onComplete(res -> {
        if (res.failed()) {
          log.error("postPatronNotice:: Failed to send patron notice");
          Throwable cause = res.cause();
          if (cause.getClass() == BadRequestException.class) {
            log.warn("postPatronNotice:: Failed to send patron notice - Bad request", cause);
            Error error = new Error().withMessage(cause.getMessage());
            Errors errors = new Errors().withErrors(singletonList(error));
            loggingResultHandler.handle(succeededFuture(PostPatronNoticeResponse
              .respond422WithApplicationJson(errors)));
            return;
          }
          loggingResultHandler.handle(succeededFuture(PostPatronNoticeResponse
            .respond500WithTextPlain(messages.getMessage(lang,
              MessageConsts.InternalServerError))));
        } else {
          log.info("postPatronNotice:: Patron notice sent successfully");
          loggingResultHandler.handle(succeededFuture(PostPatronNoticeResponse.respond200()));
        }
      });
  }

  NoticesClient makeNoticesClient(Context vertxContext, Map<String,
    String> okapiHeaders) {

    return new NoticesClient(vertxContext.owner(), okapiHeaders);
  }

  OkapiModulesClientHelper getOkapiModulesClientHelper() {
    return okapiModulesClientHelper;
  }
}
