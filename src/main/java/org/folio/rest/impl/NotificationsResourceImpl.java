package org.folio.rest.impl;

import static io.vertx.core.Future.succeededFuture;
import static org.folio.util.LogUtil.asJson;
import static org.folio.util.LogUtil.headersAsString;
import static org.folio.util.LogUtil.loggingResponseHandler;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.client.NoticesClient;
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
  private final Logger log = LogManager.getLogger(NotificationsResourceImpl.class);
  private final Messages messages = Messages.getInstance();
  private static final String NOTIFY_TABLE = "notify_data";
  private static final String LOCATION_PREFIX = "/notify/";
  private static final int DAYS_TO_KEEP_SEEN_NOTIFICATIONS = 365;

  private final OkapiModulesClientHelper okapiModulesClientHelper = new OkapiModulesClientHelper();

  private CQLWrapper getCQL(String query, int limit, int offset) throws FieldException {
    log.debug("getCQL:: parameters query: {}, limit: {}, offset: {}", query, limit, offset);
    return new CQLWrapper(new CQL2PgJSON(NOTIFY_TABLE + ".jsonb"), query, limit, offset);
  }

  /**
   * Helper to make a message for the "500 Internal error". Mostly to keep
   * SonarQube happy about code complexity.
   *
   * @param exception The exception we caught
   * @param lang
   * @return The message string
   */
  private String internalErrorMsg(Exception exception, String lang) {
    if (exception != null) {
      log.warn(exception.getMessage(), exception);
    }

    String message = messages.getMessage(lang, MessageConsts.InternalServerError);
    if (exception != null && exception.getCause() != null &&
      exception.getCause().getClass().getSimpleName().endsWith("CQLParseException")) {

      message = " CQL parse error " + exception.getLocalizedMessage();
    }
    log.info("internalErrorMsg:: result: {}", message);
    return message;
  }

  @Override
  @Validate
  public void getNotify(String query, int offset, int limit, String lang,
    Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    log.debug("getNotify:: parameters query: {}, offset: {}, limit: {}, lang: {}, " +
        "okapiHeaders: {}", () -> query, () -> offset, () -> limit, () -> lang,
      () -> headersAsString(okapiHeaders));

    getNotifyBoth(false, query, offset, limit, okapiHeaders,
      asyncResultHandler, vertxContext);
  }

  @Override
  @Validate
  public void getNotifyUserSelf(String query, int offset, int limit,
    String lang, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    log.debug("getNotifyUserSelf:: parameters query: {}, offset: {}, limit: {}, lang: {}, " +
        "okapiHeaders: {}", () -> query, () -> offset, () -> limit, () -> lang,
      () -> headersAsString(okapiHeaders));

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

    String queryParameter = query;
    log.debug("selfGetQuery:: parameters query: {}, okapiHeaders: {}", () -> queryParameter,
      () -> headersAsString(okapiHeaders));

    Handler<AsyncResult<Response>> loggingResponseHandler =
      loggingResponseHandler("selfGetQuery", asyncResultHandler, log);

    String userId = okapiHeaders.get(RestVerticle.OKAPI_USERID_HEADER);
    if (userId == null) {
      log.warn("selfGetQuery:: Failed to get user ID from HTTP headers");
      loggingResponseHandler.handle(succeededFuture(GetNotifyResponse.
        respond400WithTextPlain("No UserId")));
      return null;
    }
    String selfQuery = "recipientId=" + userId;
    if (query == null) {
      query = selfQuery;
    } else {
      query = selfQuery + " and (" + query + ")";
    }
    log.info("selfGetQuery:: result: {}", query);
    return query;
  }

  /**
   * Helper to get a list of notifies, optionally limited to _self
   */
  @java.lang.SuppressWarnings({"squid:S00107"}) // 8 parameters, I know
  private void getNotifyBoth(boolean self, String query, int offset, int limit,
    Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    String queryParameter = query;
    log.debug("getNotifyBoth:: parameters self: {}, query: {}, offset: {}, limit: {}, " +
        "okapiHeaders: {}", () -> self, () -> queryParameter, () -> offset, () -> limit,
      () -> headersAsString(okapiHeaders));

    CQLWrapper cql = null;
    try {
      if (self) {
        log.info("getNotifyBoth:: self is true");
        query = selfGetQuery(query, okapiHeaders, asyncResultHandler);
        if (query == null) {
          log.info("getNotifyBoth:: query is null");
          return; // error already handled
        }
      }
      cql = getCQL(query, limit, offset);
    } catch (Exception e) {
      log.warn("getNotifyBoth:: Failed to create CQL query", e);
      ValidationHelper.handleError(e, asyncResultHandler);
    }
    getPostgresClient(vertxContext, okapiHeaders)
      .get(NOTIFY_TABLE, Notification.class, new String[]{"*"}, cql,
        true /*get count too*/, false /* set id */,
        reply -> {
          if (reply.succeeded()) {
            log.info("getNotifyBoth:: Succeeded to get notifications");
            NotifyCollection notes = new NotifyCollection();
            @SuppressWarnings("unchecked")
            List<Notification> notifylist
              = reply.result().getResults();
            notes.setNotifications(notifylist);
            Integer totalRecords = reply.result().getResultInfo().getTotalRecords();
            notes.setTotalRecords(totalRecords);
            loggingResponseHandler("getNotifyBoth", asyncResultHandler, log).handle(succeededFuture(
              GetNotifyResponse.respond200WithApplicationJson(notes)));
          } else {
            log.warn("getNotifyBoth:: Failed to get notifications", reply.cause());
            ValidationHelper.handleError(reply.cause(), asyncResultHandler);
          }
        });
  }

  HttpClientInterface getHttpClient(String okapiURL, String tenantId) {
    log.debug("getHttpClient:: parameters okapiURL: {}, tenantId: {}", okapiURL, tenantId);
    return HttpClientFactory.getHttpClient(okapiURL, tenantId);
  }

  PostgresClient getPostgresClient(Context context, Map<String, String> okapiHeaders) {
    log.debug("getPostgresClient:: parameters okapiHeaders: {}",
      () -> headersAsString(okapiHeaders));
    return PgUtil.postgresClient(context, okapiHeaders);
  }

  NoticesClient makeNoticesClient(Context context, Map<String, String> okapiHeaders) {
    log.debug("makeNoticesClient:: parameters okapiHeaders: {}",
      () -> headersAsString(okapiHeaders));
    return new NoticesClient(context.owner(), okapiHeaders);
  }

  @Override
  @Validate
  public void postNotifyUsernameByUsername(String userName, String lang, Notification notification,
    Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    log.debug("postNotifyUsernameByUsername:: parameters userName: {}, lang: {}, " +
        "notification: {}, okapiHeaders: {}", () -> userName, () -> lang,
      () -> asJson(notification), () -> headersAsString(okapiHeaders));

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
      log.debug("postNotifyUsernameByUsername:: Looking up user: {}", url);
      client.request(url, okapiHeaders)
        .whenComplete((resp, ex) -> handleLookupUserResponse(resp, notification, okapiHeaders,
          asyncResultHandler, userName, vertxContext, lang));
    } catch (Exception e) {
      log.warn("postNotifyUsernameByUsername:: Failed to fetch users", e);
      ValidationHelper.handleError(e, asyncResultHandler);
    }
  }

  private void handleLookupUserResponse(org.folio.rest.tools.client.Response resp,
    Notification notification, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler, String userName, Context vertxContext,
    String lang) {

    log.debug("handleLookupUserResponse:: parameters resp.code: {}, resp.body: {}, " +
        "notification: {}, okapiHeaders: {}, userName: {}, lang: {}", resp::getCode, resp::getBody,
      () -> notification, () -> headersAsString(okapiHeaders), () -> userName, () -> lang);

    Handler<AsyncResult<Response>> loggingResultHandler = loggingResponseHandler(
      "handleLookupUserResponse", asyncResultHandler, log);

    switch (resp.getCode()) {
      case 200:
        log.debug("handleLookupUserResponse:: Received user {}", () -> asJson(resp.getBody()));
        JsonObject userResp = resp.getBody();
        if (userResp.getInteger("totalRecords", 0) > 0) {
          if (userResp.containsKey("users")
            && !userResp.getJsonArray("users").isEmpty()
            && userResp.getJsonArray("users").getJsonObject(0).containsKey("id")) {

            log.info("handleLookupUserResponse:: User lookup succeeded for {}", userName);
            String id = userResp.getJsonArray("users").getJsonObject(0).getString("id");
            notification.setRecipientId(id);
            postNotify(lang, notification, okapiHeaders, loggingResultHandler, vertxContext);
          } else {
            log.warn("handleLookupUserResponse:: User lookup failed for {}. Bad response: {}",
              () -> userName, () -> asJson(resp.getBody()));
            loggingResultHandler.handle(succeededFuture(PostNotifyUsernameByUsernameResponse
              .respond400WithTextPlain("User lookup failed for " + userName + ". "
                + "Bad response " + userResp)));
          }
        } else {  // Can not use ValidationHelper here, we have HTTP responses
          log.warn("handleLookupUserResponse:: User lookup failed for {}. Response: {}",
            () -> userName, () -> asJson(resp.getBody()));
          loggingResultHandler.handle(succeededFuture(PostNotifyUsernameByUsernameResponse
            .respond400WithTextPlain("User lookup failed. "
              + "Can not find user " + userName)));
        }
        break;
      case 403:
        log.warn("handleLookupUserResponse:: Insufficient permissions (403). User lookup failed " +
            "for {}. Response: {}", () -> userName, () -> asJson(resp.getBody()));
        loggingResultHandler.handle(succeededFuture(PostNotifyUsernameByUsernameResponse
          .respond400WithTextPlain("User lookup failed with 403. " + userName
            + " " + Json.encode(resp.getError()))));
        break;
      default:
        log.warn("handleLookupUserResponse:: User lookup failed with {} code. Response: {}",
          resp::getCode, () -> asJson(resp.getBody()));
        loggingResultHandler.handle(succeededFuture(PostNotifyUsernameByUsernameResponse
          .respond500WithTextPlain(internalErrorMsg(null, lang))));
        break;
    }
  }

  @Override
  @Validate
  public void postNotify(String lang, Notification entity, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler, Context context) {

    log.debug("postNotify:: parameters lang: {}, entity: {}, okapiHeaders: {}",
      () -> lang, () -> asJson(entity), () -> headersAsString(okapiHeaders));

    Handler<AsyncResult<Response>> loggingResultHandler = loggingResponseHandler(
      "postNotify", asyncResultHandler, log);

    String recip = entity.getRecipientId();
    if (recip == null || recip.isEmpty()) {
      log.warn("postNotify:: recipientId is missing");
      Errors valErr = ValidationHelper.createValidationErrorMessage("recipientId", "", "Required");
      loggingResultHandler.handle(succeededFuture(PostNotifyResponse
        .respond422WithApplicationJson(valErr)));
      return;
    }
    String id = entity.getId();
    if (id == null || id.trim().isEmpty()) {
      log.info("postNotify:: Notification ID is null or empty, generating random ID");
      id = UUID.randomUUID().toString();
    }
    if (respond422IfIdIsNotValid(id, loggingResultHandler)) {
      return;
    }
    getPostgresClient(context, okapiHeaders).save(NOTIFY_TABLE, id, entity,
      reply -> {
        if (reply.succeeded()) {
          log.info("postNotify:: Notification saved");
          if (entity.getEventConfigName() == null) {
            log.info("postNotify:: Event config name is null, sending notification");
            String ret = reply.result();
            entity.setId(ret);
            loggingResultHandler.handle(succeededFuture(PostNotifyResponse
              .respond201WithApplicationJson(entity, PostNotifyResponse.
                headersFor201().withLocation(LOCATION_PREFIX + ret))));
          } else {
            NoticesClient client = makeNoticesClient(context, okapiHeaders);

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
                  log.info("postNotify:: Notification sent");
                  loggingResultHandler.handle(succeededFuture(PostNotifyResponse.respond201WithApplicationJson(
                    entity, PostNotifyResponse.headersFor201())));
                } else if (event.cause().getClass() == BadRequestException.class) {
                  log.warn("postNotify:: Failed to send notification (Bad request)", event.cause());
                  loggingResultHandler.handle(succeededFuture(
                    PostNotifyResponse.respond400WithTextPlain(event.cause().getMessage())));
                } else {
                  log.warn("postNotify:: Failed to send notification", event.cause());
                  loggingResultHandler.handle(succeededFuture(PostNotifyResponse.respond500WithTextPlain(event.cause())));
                }
              });
          }
        } else {
          log.warn("postNotify:: Failed to save notification", reply.cause());
          ValidationHelper.handleError(reply.cause(), loggingResultHandler);
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
    Handler<AsyncResult<Void>> asyncResultHandler, Context vertxContext) {

    log.debug("deleteAllOldNotifications:: parameters userId: {}, okapiHeaders: {}",
      () -> userId, () -> headersAsString(okapiHeaders));
    String query;
    String selfQuery = "recipientId=\"" + userId + "\"" + " and seen=true";
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime limit = now.minus(DAYS_TO_KEEP_SEEN_NOTIFICATIONS, ChronoUnit.DAYS);
    // This is not right, hard coding the time limit. We need a better way to
    // purge old notifications. Some day when we know what we do with other
    // housekeeping jobs
    String olderThan = limit.format(DateTimeFormatter.ISO_DATE);
    query = selfQuery + " and (metadata.updatedDate<" + olderThan + ")";
    log.debug("deleteAllOldNotifications: new query: {}", query);
    CQLWrapper cql;
    try {
      log.debug("deleteAllOldNotifications:: query: {}", query);
      cql = getCQL(query, -1, -1);
    } catch (Exception e) {
      log.warn("deleteAllOldNotifications:: Deleting old notifications failed", e);
      // Ignore the error, we catch them later
      asyncResultHandler.handle(succeededFuture());
      log.info("deleteAllOldNotifications:: result: null");
      return;
    }
    getPostgresClient(vertxContext, okapiHeaders)
      .delete(NOTIFY_TABLE, cql, reply -> asyncResultHandler.handle(succeededFuture()));
    // Ignore all errors, we will catch old notifies the next time
  }

  private String selfDelQuery(String olderThan, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler) {

    log.debug("selfDelQuery:: parameters olderThan: {}, okapiHeaders: {}", () -> olderThan,
      () -> headersAsString(okapiHeaders));

    String userId = okapiHeaders.get(RestVerticle.OKAPI_USERID_HEADER);
    if (userId == null) {
      log.warn("selfDelQuery:: No user id, cannot delete self notifications");
      loggingResponseHandler("selfDelQuery", asyncResultHandler, log)
        .handle(succeededFuture(GetNotifyResponse.respond400WithTextPlain("No UserId")));
      return null;
    }
    String query;
    String selfQuery = "recipientId=\"" + userId + "\"" + " and seen=true";
    if (olderThan == null) {
      log.info("selfDelQuery:: olderThan is null, ignoring metadata.updatedDate filter");
      query = selfQuery;
    } else {
      log.info("selfDelQuery:: olderThan is not null, filtering by metadata.updatedDate");
      query = selfQuery + " and (metadata.updatedDate<" + olderThan + ")";
    }
    log.info("selfDelQuery:: result = {}", query);
    return query;
  }

  @Override
  @Validate
  public void deleteNotifyUserSelf(String olderThan, String lang, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {

    log.debug("deleteNotifyUserSelf:: parameters olderThan: {}, lang: {}, okapiHeaders: {}",
      () -> olderThan, () -> lang, () -> headersAsString(okapiHeaders));

    Handler<AsyncResult<Response>> loggingResultHandler = loggingResponseHandler(
      "deleteNotifyUserSelf", asyncResultHandler, log);

    String query = selfDelQuery(olderThan, okapiHeaders, loggingResultHandler);
    if (query == null) {
      log.info("deleteNotifyUserSelf:: result: null");
      return; // error has been handled already
    }

    CQLWrapper cql;
    try {
      log.debug("deleteNotifyUserSelf:: query = {}", query);
      cql = getCQL(query, -1, -1);
    } catch (Exception e) {
      log.warn("deleteNotifyUserSelf:: Deleting self notifications failed", e);
      ValidationHelper.handleError(e, loggingResultHandler);
      return;
    }
    getPostgresClient(vertxContext, okapiHeaders)
      .delete(NOTIFY_TABLE, cql,
        reply -> {
          if (reply.succeeded()) {
            log.info("deleteNotifyUserSelf:: Deleted self notifications");
            int rowCount = reply.result().rowCount();
            if (rowCount > 0) {
              log.info("deleteNotifyUserSelf:: Deleted {} notifications", rowCount);
              loggingResultHandler.handle(succeededFuture(
                DeleteNotifyUserSelfResponse.respond204()));
            } else {
              String message = messages.getMessage(lang, MessageConsts.DeletedCountError, 1,
                rowCount);
              log.warn("deleteNotifyUserSelf:: Failed with message: {}", message);
              loggingResultHandler.handle(succeededFuture(DeleteNotifyUserSelfResponse
                .respond404WithTextPlain(message)));
            }
          } else {
            log.warn("deleteNotifyUserSelf:: Deleting self notifications failed", reply.cause());
            ValidationHelper.handleError(reply.cause(), loggingResultHandler);
          }
        });
  }

  @Override
  @Validate
  public void getNotifyById(String id, String lang, Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler, Context context) {

    log.debug("getNotifyById:: parameters id: {}, lang: {}, okapiHeaders: {}", () -> id,
      () -> lang, () -> headersAsString(okapiHeaders));

    if (respond422IfIdIsNotValid(id, asyncResultHandler)) {
      log.info("getNotifyById:: ID is not valid, result: null");
      return;
    }

    Handler<AsyncResult<Response>> loggingResultHandler = loggingResponseHandler(
      "getNotifyById", asyncResultHandler, log);

    getPostgresClient(context, okapiHeaders).getById(NOTIFY_TABLE, id, Notification.class, reply -> {
      if (reply.failed()) {
        log.warn("getNotifyById:: Failed to get notification by ID: {}", id);
        ValidationHelper.handleError(reply.cause(), loggingResultHandler);
        return;
      }
      if (reply.result() == null) {
        log.warn("getNotifyById:: No notification found by ID: {} ", id);
        loggingResultHandler.handle(succeededFuture(
          GetNotifyByIdResponse.respond404WithTextPlain(id)));
        return;
      }
      loggingResultHandler.handle(succeededFuture(
        GetNotifyByIdResponse.respond200WithApplicationJson(reply.result())));
    });
  }

  @Override
  @Validate
  public void deleteNotifyById(String id, String lang, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {

    log.debug("deleteNotifyById:: parameters id: {}, lang: {}, okapiHeaders: {}", () -> id,
      () -> lang, () -> headersAsString(okapiHeaders));

    if (respond422IfIdIsNotValid(id, asyncResultHandler)) {
      log.info("deleteNotifyById:: ID is not valid, result: null");
      return;
    }

    Handler<AsyncResult<Response>> loggingResultHandler = loggingResponseHandler(
      "deleteNotifyById", asyncResultHandler, log);

    getPostgresClient(vertxContext, okapiHeaders)
      .delete(NOTIFY_TABLE, id,
        reply -> {
          if (reply.succeeded()) {
            log.info("deleteNotifyById:: Deleted notification by ID: {}", id);
            int rowCount = reply.result().rowCount();
            if (rowCount == 1) {
              log.info("deleteNotifyById:: Deleted exactly one notification");
              loggingResultHandler.handle(succeededFuture(DeleteNotifyByIdResponse.respond204()));
            } else {
              String message = messages.getMessage(lang, MessageConsts.DeletedCountError, 1,
                rowCount);
              log.warn("deleteNotifyById:: Failed with message: {}", message);
              loggingResultHandler.handle(succeededFuture(DeleteNotifyByIdResponse
                .respond404WithTextPlain(message)));
            }
          } else {
            log.warn("deleteNotifyById:: Failed to delete notification by ID: {}", id,
              reply.cause());
            ValidationHelper.handleError(reply.cause(), loggingResultHandler);
          }
        });
  }

  @Override
  @Validate
  public void putNotifyById(String id, String lang, Notification entity,
    Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    log.debug("putNotifyById:: parameters id: {}, lang: {}, entity: {}, okapiHeaders: {}",
      () -> id, () -> lang, () -> asJson(entity), () -> headersAsString(okapiHeaders));

    Handler<AsyncResult<Response>> loggingResultHandler = loggingResponseHandler(
      "putNotifyById", asyncResultHandler, log);

    if (respond422IfIdIsNotValid(id, loggingResultHandler)) {
      log.info("putNotifyById:: ID is not valid, result: null");
      return;
    }

    String noteId = entity.getId();
    if (noteId != null && !noteId.equals(id)) {
      log.info("putNotifyById:: Trying to change note ID from {} to {}", id, noteId);
      Errors valErr = ValidationHelper.createValidationErrorMessage("id", noteId,
        "Can not change the id");
      loggingResultHandler.handle(succeededFuture(PutNotifyByIdResponse
        .respond422WithApplicationJson(valErr)));
      return;
    }
    String recip = entity.getRecipientId();
    if (recip == null || recip.isEmpty()) {
      log.info("putNotifyById:: Recipient ID is missing, validation error");
      Errors valErr = ValidationHelper.createValidationErrorMessage("recipientId", "", "Required");
      loggingResultHandler.handle(succeededFuture(PutNotifyByIdResponse
        .respond422WithApplicationJson(valErr)));
      return;
    }
    String userId = okapiHeaders.get(RestVerticle.OKAPI_USERID_HEADER);
    getPostgresClient(vertxContext, okapiHeaders)
      .update(NOTIFY_TABLE, entity, id,
        reply -> {
          if (reply.succeeded()) {
            log.info("putNotifyById:: Updated succeeded");
            int rowCount = reply.result().rowCount();
            if (rowCount == 0) {
              log.info("putNotifyById:: 0 notification updated (notification not found)");
              loggingResultHandler.handle(succeededFuture(PutNotifyByIdResponse
                .respond404WithTextPlain(id)));
            } else { // all ok
              log.info("putNotifyById:: {} notification updated", rowCount);
              deleteAllOldNotifications(userId, okapiHeaders,
                dres -> loggingResultHandler.handle(succeededFuture(
                  PutNotifyByIdResponse.respond204())),
                 vertxContext);
            }
          } else {
            log.warn("putNotifyById:: Update failed", reply.cause());
            ValidationHelper.handleError(reply.cause(), loggingResultHandler);
          }
        });
  }

  private boolean respond422IfIdIsNotValid(String id,
    Handler<AsyncResult<Response>> asyncResultHandler) {

    log.debug("respond422IfIdIsNotValid:: parameters id: {}", id);
    if (!UuidUtil.isUuid(id)) {
      log.warn("respond422IfIdIsNotValid:: ID is not a UUID: {}", id);
      Errors valErr = ValidationHelper.createValidationErrorMessage(
        "id", id, "invalid input syntax for type uuid");
      asyncResultHandler.handle(succeededFuture(PostNotifyResponse
        .respond422WithApplicationJson(valErr)));
      log.warn("respond422IfIdIsNotValid:: result: {}", true);
      return true;
    }
    log.warn("respond422IfIdIsNotValid:: result: {}", false);
    return false;
  }
}
