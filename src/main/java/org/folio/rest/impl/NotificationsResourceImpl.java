package org.folio.rest.impl;

import static io.vertx.core.Future.succeededFuture;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.client.OkapiModulesClient;
import org.folio.client.impl.OkapiModulesClientImpl;
import org.folio.cql2pgjson.CQL2PgJSON;
import org.folio.cql2pgjson.exception.FieldException;
import org.folio.helper.OkapiModulesClientHelper;
import org.folio.rest.RestVerticle;
import org.folio.rest.annotations.Validate;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.Message;
import org.folio.rest.jaxrs.model.Notification;
import org.folio.rest.jaxrs.model.NotifyCollection;
import org.folio.rest.jaxrs.resource.Notify;
import org.folio.rest.persist.PgUtil;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.tools.client.HttpClientFactory;
import org.folio.rest.tools.client.interfaces.HttpClientInterface;
import org.folio.rest.tools.messages.MessageConsts;
import org.folio.rest.tools.messages.Messages;
import org.folio.rest.tools.utils.TenantTool;
import org.folio.rest.tools.utils.ValidationHelper;
import org.folio.util.StringUtil;
import org.folio.util.UuidUtil;

import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

// We have a few repeated strings, which SQ complains about.
@java.lang.SuppressWarnings({"squid:S1192"})
public class NotificationsResourceImpl implements Notify {
  private final Logger logger = LogManager.getLogger("modnotify");
  private final Messages messages = Messages.getInstance();
  private static final String NOTIFY_TABLE = "notify_data";
  private static final String LOCATION_PREFIX = "/notify/";
  private static final int DAYS_TO_KEEP_SEEN_NOTIFICATIONS = 365;

  private final OkapiModulesClientHelper okapiModulesClientHelper = new OkapiModulesClientHelper();

  private CQLWrapper getCQL(String query, int limit, int offset) throws FieldException {
    return new CQLWrapper(new CQL2PgJSON(NOTIFY_TABLE + ".jsonb"), query, limit, offset);
  }

  /**
   * Helper to make a message for the "500 Internal error". Mostly to keep
   * SonarQube happy about code complexity.
   *
   * @param e The exception we caught
   * @param lang
   * @return The message string
   */
  private String internalErrorMsg(Exception e, String lang) {
    if (e != null) {
      logger.error(e.getMessage(), e);
    }
    String message = messages.getMessage(lang, MessageConsts.InternalServerError);
    if (e != null && e.getCause() != null && e.getCause().getClass().getSimpleName()
      .endsWith("CQLParseException")) {
      message = " CQL parse error " + e.getLocalizedMessage();
    }
    return message;
  }

  @Override
  @Validate
  public void getNotify(String query,
    int offset, int limit,
    String lang,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    getNotifyBoth(false, query, offset, limit, okapiHeaders,
      asyncResultHandler, vertxContext);
  }

  @Override
  @Validate
  public void getNotifyUserSelf(String query, int offset, int limit,
    String lang, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    getNotifyBoth(true, query, offset, limit,
      okapiHeaders, asyncResultHandler, vertxContext);
  }

  /**
   * Helper to add the 'self' clause to the query.
   *
   * @param query
   * @param okapiHeaders
   * @param asyncResultHandler
   * @return the modified query, or null, in which case the handler has been
   * called.
   */
  private String selfGetQuery(String query, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler) {
    String userId = okapiHeaders.get(RestVerticle.OKAPI_USERID_HEADER);
    if (userId == null) {
      logger.error("No userId for getNotesSelf");
      asyncResultHandler.handle(succeededFuture(GetNotifyResponse.
        respond400WithTextPlain("No UserId")));
      return null;
    }
    String selfQuery = "recipientId=" + userId;
    if (query == null) {
      query = selfQuery;
    } else {
      query = selfQuery + " and (" + query + ")";
    }
    logger.debug("Getting self notes. new query:" + query);
    return query;
  }

  /**
   * Helper to get a list of notifies, optionally limited to _self
   */
  @java.lang.SuppressWarnings({"squid:S00107"}) // 8 parameters, I know
  private void getNotifyBoth(boolean self, String query, int offset, int limit,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    CQLWrapper cql = null;
    try {
      logger.debug("Getting notes. self=" + self + " "
        + offset + "+" + limit + " q=" + query);
      if (self) {
        query = selfGetQuery(query, okapiHeaders, asyncResultHandler);
        if (query == null) {
          return; // error already handled
        }
      }
      cql = getCQL(query, limit, offset);
    } catch (Exception e) {
      ValidationHelper.handleError(e, asyncResultHandler);
    }
    getPostgresClient(vertxContext, okapiHeaders)
      .get(NOTIFY_TABLE, Notification.class, new String[]{"*"}, cql,
        true /*get count too*/, false /* set id */,
        reply -> {
          if (reply.succeeded()) {
            NotifyCollection notes = new NotifyCollection();
            @SuppressWarnings("unchecked")
            List<Notification> notifylist
              = reply.result().getResults();
            notes.setNotifications(notifylist);
            Integer totalRecords = reply.result().getResultInfo().getTotalRecords();
            notes.setTotalRecords(totalRecords);
            asyncResultHandler.handle(succeededFuture(
              GetNotifyResponse.respond200WithApplicationJson(notes)));
          } else {
            ValidationHelper.handleError(reply.cause(), asyncResultHandler);
          }
        });
  }

  HttpClientInterface getHttpClient(String okapiURL, String tenantId) {
    return HttpClientFactory.getHttpClient(okapiURL, tenantId);
  }

  PostgresClient getPostgresClient(Context context, Map<String, String> okapiHeaders) {
    return PgUtil.postgresClient(context, okapiHeaders);
  }

  OkapiModulesClient getOkapiModulesClient(Context context, Map<String, String> okapiHeaders) {
    return new OkapiModulesClientImpl(context.owner(), okapiHeaders);
  }

  @Override
  @Validate
  public void postNotifyUsernameByUsername(String userName, String lang,
          Notification notification, Map<String, String> okapiHeaders,
          Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    logger.debug("postNotifyUseridByUid starting userId='" + userName + "'");
    String tenantId = TenantTool.calculateTenantId(
      okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
    String okapiURL = okapiHeaders.get("X-Okapi-Url");
    HttpClientInterface client = getHttpClient(okapiURL, tenantId);
    String cql = userName == null ? "" : userName;
    // mask special CQL characters: \ " * ? ^
    cql.replace("\\", "\\\\").replace("\"", "\\\"").replace("*", "\\*").replace("?", "\\?").replace("^", "\\^");
    cql = "username==\"" + cql + "\"";
    String url = "/users?query=" + StringUtil.urlEncode(cql);
    try {
      logger.debug("Looking up user " + url);
      CompletableFuture<org.folio.rest.tools.client.Response> response
        = client.request(url, okapiHeaders);
      response.whenComplete((resp, ex)
        -> handleLookupUserResponse(resp, notification, okapiHeaders,
          asyncResultHandler, userName, vertxContext, lang)
      );
    } catch (Exception e) {
      ValidationHelper.handleError(e, asyncResultHandler);
    }
  }

  private void handleLookupUserResponse(
    org.folio.rest.tools.client.Response resp, Notification notification,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    String userName, Context vertxContext, String lang) {
    switch (resp.getCode()) {
      case 200:
        logger.debug("Received user " + resp.getBody());
        JsonObject userResp = resp.getBody();
        if (userResp.getInteger("totalRecords", 0) > 0) {
          if (userResp.containsKey("users")
            && !userResp.getJsonArray("users").isEmpty()
            && userResp.getJsonArray("users").getJsonObject(0).containsKey("id")) {
            String id = userResp.getJsonArray("users").getJsonObject(0).getString("id");
            notification.setRecipientId(id);
            postNotify(lang, notification, okapiHeaders, asyncResultHandler, vertxContext);
          } else {
            logger.error("User lookup failed for " + userName + ". Bad response");
            logger.error(Json.encodePrettily(resp));
            asyncResultHandler.handle(succeededFuture(PostNotifyUsernameByUsernameResponse
              .respond400WithTextPlain("User lookup failed for " + userName + ". "
                + "Bad response " + userResp)));
          }
        } else {  // Can not use ValidationHelper here, we have HTTP responses
          logger.error("User lookup failed for " + userName);
          logger.error(Json.encodePrettily(resp));
          asyncResultHandler.handle(succeededFuture(PostNotifyUsernameByUsernameResponse
            .respond400WithTextPlain("User lookup failed. "
              + "Can not find user " + userName)));
        }
        break;
      case 403:
        logger.error("Permission problem: User lookup failed for " + userName);
        logger.error(Json.encodePrettily(resp));
        asyncResultHandler.handle(succeededFuture(PostNotifyUsernameByUsernameResponse
          .respond400WithTextPlain("User lookup failed with 403. " + userName
            + " " + Json.encode(resp.getError()))));
        break;
      default:
        logger.error("User lookup failed with " + resp.getCode());
        logger.error(Json.encodePrettily(resp));
        asyncResultHandler.handle(
          succeededFuture(PostNotifyUsernameByUsernameResponse
            .respond500WithTextPlain(internalErrorMsg(null, lang))));
        break;
    }
  }

  @Override
  @Validate
  public void postNotify(String lang,
    Notification entity,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context context) {

    String recip = entity.getRecipientId();
    if (recip == null || recip.isEmpty()) {
      Errors valErr = ValidationHelper.createValidationErrorMessage(
        "recipientId", "", "Required");
      asyncResultHandler.handle(succeededFuture(PostNotifyResponse
        .respond422WithApplicationJson(valErr)));
      return;
    }
    String id = entity.getId();
    if (id == null || id.trim().isEmpty()) {
      id = UUID.randomUUID().toString();
    }
    if (respond422IfIdIsNotValid(id, asyncResultHandler)) {
      return;
    }
    getPostgresClient(context, okapiHeaders).save(NOTIFY_TABLE,
      id, entity,
      reply -> {
        if (reply.succeeded()) {
          if (entity.getEventConfigName() == null) {
            String ret = reply.result();
            entity.setId(ret);
            asyncResultHandler.handle(succeededFuture(PostNotifyResponse
              .respond201WithApplicationJson(entity, PostNotifyResponse.
                headersFor201().withLocation(LOCATION_PREFIX + ret))));
          } else {
            OkapiModulesClient client = getOkapiModulesClient(context, okapiHeaders);

            client.getEventConfig(entity.getEventConfigName())
              .compose(eventEntity -> CompositeFuture.all(eventEntity.getTemplates()
                .stream()
                .map(template -> client.postTemplateRequest(
                  okapiModulesClientHelper.buildTemplateProcessingRequest(template, entity))
                  .map(result -> new Message()
                    .withHeader(result.getResult().getHeader())
                    .withBody(result.getResult().getBody())
                    .withDeliveryChannel(template.getDeliveryChannel())
                    .withOutputFormat(template.getOutputFormat())))
                .collect(Collectors.toList())))
              .map(results -> okapiModulesClientHelper.buildNotifySendRequest(results.list()
                .stream()
                .map(o -> (Message) o)
                .collect(Collectors.toList()), entity))
              .compose(client::postMessageDelivery)
              .onComplete(event -> {
                if (event.succeeded()) {
                  asyncResultHandler.handle(succeededFuture(PostNotifyResponse.respond201WithApplicationJson(
                    entity, PostNotifyResponse.headersFor201())));
                } else if (event.cause().getClass() == BadRequestException.class) {
                  asyncResultHandler.handle(succeededFuture(
                    PostNotifyResponse.respond400WithTextPlain(event.cause().getMessage())));
                } else {
                  asyncResultHandler.handle(succeededFuture(PostNotifyResponse.respond500WithTextPlain(event.cause())));
                }
              });
          }
        } else {
          ValidationHelper.handleError(reply.cause(), asyncResultHandler);
        }
      });
  }

  /**
   * Post to _self is not supported, but RMB builds this anyway.
   *
   */
  @Override
  @Validate
  public void postNotifyUserSelf(String lang, Notification entity,
    Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {
    throw new UnsupportedOperationException("Not supported.");
  }

  /**
   * Helper to delete old, seen notifications.
   */
  private void deleteAllOldNotifications(String userId, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Void>> asyncResultHandler,
    Context vertxContext) {
    String query;
    String selfQuery = "recipientId=\"" + userId + "\""
       + " and seen=true";
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime limit = now.minus(DAYS_TO_KEEP_SEEN_NOTIFICATIONS, ChronoUnit.DAYS);
    // This is not right, hard coding the time limit. We need a better way to
    // purge old notifications. Some day when we know what we do with other
    // housekeeping jobs
    String olderthan = limit.format(DateTimeFormatter.ISO_DATE);
    query = selfQuery + " and (metadata.updatedDate<" + olderthan + ")";
    logger.debug(" deleteAllOldNotifications: new query:" + query);
    CQLWrapper cql = null;
    try {
      cql = getCQL(query, -1, -1);
    } catch (Exception e) {
      logger.error("Deleting old notifys failed with " + e.getMessage());
      // Ignore the error, we catch them later
      asyncResultHandler.handle(succeededFuture());
      return;
    }
    getPostgresClient(vertxContext, okapiHeaders)
      .delete(NOTIFY_TABLE, cql,
        reply -> asyncResultHandler.handle(succeededFuture())
      // Ignore all errors, we will catch old notifies the next time
      );
  }

  private String selfDelQuery(String olderthan, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler) {
    String userId = okapiHeaders.get(RestVerticle.OKAPI_USERID_HEADER);
    logger.debug("Trying to delete _self notifies for "
      + userId + " since " + olderthan);
    if (userId == null) {
      logger.error("No userId for deleteNotesSelf");
      asyncResultHandler.handle(succeededFuture(GetNotifyResponse
        .respond400WithTextPlain("No UserId")));
      return null;
    }
    String query;
    String selfQuery = "recipientId=\"" + userId + "\""
      + " and seen=true";
    if (olderthan == null) {
      query = selfQuery;
    } else {
      query = selfQuery + " and (metadata.updatedDate<" + olderthan + ")";
    }
    return query;
  }

  @Override
  @Validate
  public void deleteNotifyUserSelf(String olderthan, String lang,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {
    String query = selfDelQuery(olderthan, okapiHeaders, asyncResultHandler);
    if (query == null) {
      return; // error has been handled already
    }
    logger.debug("Deleting self notifications. new query:" + query);
    CQLWrapper cql;
    try {
      cql = getCQL(query, -1, -1);
    } catch (Exception e) {
      ValidationHelper.handleError(e, asyncResultHandler);
      return;
    }
    getPostgresClient(vertxContext, okapiHeaders)
      .delete(NOTIFY_TABLE, cql,
        reply -> {
          if (reply.succeeded()) {
            if (reply.result().rowCount() > 0) {
              logger.info("Deleted " + reply.result().rowCount() + " notifies");
              asyncResultHandler.handle(succeededFuture(
                DeleteNotifyUserSelfResponse.respond204()));
            } else {
              logger.info("Deleted no notifications");
              logger.error(messages.getMessage(lang,
                MessageConsts.DeletedCountError, 1, reply.result().rowCount()));
              asyncResultHandler.handle(succeededFuture(DeleteNotifyUserSelfResponse
                .respond404WithTextPlain(messages.getMessage(lang,
                  MessageConsts.DeletedCountError, 1, reply.result().rowCount()))));
            }
          } else {
            ValidationHelper.handleError(reply.cause(), asyncResultHandler);
          }
        });
  }

  @Override
  @Validate
  public void getNotifyById(String id, String lang, Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler, Context context) {

    if (respond422IfIdIsNotValid(id, asyncResultHandler)) {
      return;
    }
    getPostgresClient(context, okapiHeaders).getById(NOTIFY_TABLE, id, Notification.class, reply -> {
      if (reply.failed()) {
        ValidationHelper.handleError(reply.cause(), asyncResultHandler);
        return;
      }
      if (reply.result() == null) {
        asyncResultHandler.handle(succeededFuture(GetNotifyByIdResponse.respond404WithTextPlain(id)));
        return;
      }
      asyncResultHandler.handle(succeededFuture(GetNotifyByIdResponse.respond200WithApplicationJson(reply.result())));
    });
  }

  @Override
  @Validate
  public void deleteNotifyById(String id, String lang, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {

    if (respond422IfIdIsNotValid(id, asyncResultHandler)) {
      return;
    }
    getPostgresClient(vertxContext, okapiHeaders)
      .delete(NOTIFY_TABLE, id,
        reply -> {
          if (reply.succeeded()) {
            if (reply.result().rowCount() == 1) {
              asyncResultHandler.handle(succeededFuture(
                DeleteNotifyByIdResponse.respond204()));
            } else {
              logger.error(messages.getMessage(lang,
                MessageConsts.DeletedCountError, 1, reply.result().rowCount()));
              asyncResultHandler.handle(succeededFuture(DeleteNotifyByIdResponse
                .respond404WithTextPlain(messages.getMessage(lang,
                  MessageConsts.DeletedCountError, 1, reply.result().rowCount()))));
            }
          } else {
            ValidationHelper.handleError(reply.cause(), asyncResultHandler);
          }
        });
  }

  @Override
  @Validate
  public void putNotifyById(String id, String lang,
    Notification entity,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    logger.debug("PUT notify " + id + " " + Json.encode(entity));
    if (respond422IfIdIsNotValid(id, asyncResultHandler)) {
      return;
    }
    String noteId = entity.getId();
    if (noteId != null && !noteId.equals(id)) {
      logger.error("Trying to change note Id from " + id + " to " + noteId);
      Errors valErr = ValidationHelper.createValidationErrorMessage("id", noteId,
        "Can not change the id");
      asyncResultHandler.handle(succeededFuture(PutNotifyByIdResponse
        .respond422WithApplicationJson(valErr)));
      return;
    }
    String recip = entity.getRecipientId();
    if (recip == null || recip.isEmpty()) {
      Errors valErr = ValidationHelper.createValidationErrorMessage(
        "recipientId", "", "Required");
      asyncResultHandler.handle(succeededFuture(PutNotifyByIdResponse
        .respond422WithApplicationJson(valErr)));
      return;
    }
    String userId = okapiHeaders.get(RestVerticle.OKAPI_USERID_HEADER);
    getPostgresClient(vertxContext, okapiHeaders)
      .update(NOTIFY_TABLE, entity, id,
        reply -> {
          if (reply.succeeded()) {
            if (reply.result().rowCount() == 0) {
              asyncResultHandler.handle(succeededFuture(PutNotifyByIdResponse
                .respond404WithTextPlain(id)));
            } else { // all ok
              deleteAllOldNotifications(userId, okapiHeaders,
                dres -> asyncResultHandler.handle(succeededFuture(
                  PutNotifyByIdResponse.respond204())),
                 vertxContext);
            }
          } else {
            ValidationHelper.handleError(reply.cause(), asyncResultHandler);
          }
        });
  }

  private boolean respond422IfIdIsNotValid(
    String id, Handler<AsyncResult<Response>> asyncResultHandler) {

    if (!UuidUtil.isUuid(id)) {
      Errors valErr = ValidationHelper.createValidationErrorMessage(
        "id", id, "invalid input syntax for type uuid");
      asyncResultHandler.handle(succeededFuture(PostNotifyResponse
        .respond422WithApplicationJson(valErr)));
      return true;
    }
    return false;
  }
}
