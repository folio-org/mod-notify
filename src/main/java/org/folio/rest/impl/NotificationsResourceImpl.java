package org.folio.rest.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import static io.vertx.core.Future.succeededFuture;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.ws.rs.core.Response;
import org.apache.commons.io.IOUtils;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.Notification;
import org.folio.rest.jaxrs.model.NotifyCollection;
import org.folio.rest.jaxrs.resource.Notify;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.Criteria.Limit;
import org.folio.rest.persist.Criteria.Offset;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.tools.client.HttpClientFactory;
import org.folio.rest.tools.client.interfaces.HttpClientInterface;
import org.folio.rest.tools.messages.MessageConsts;
import org.folio.rest.tools.messages.Messages;
import org.folio.rest.tools.utils.TenantTool;
import org.folio.rest.tools.utils.ValidationHelper;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSON;
import org.z3950.zing.cql.cql2pgjson.FieldException;
import org.z3950.zing.cql.cql2pgjson.SchemaException;
import org.folio.rest.annotations.Validate;

// We have a few repeated strings, which SQ complains about.
@java.lang.SuppressWarnings({"squid:S1192"})
public class NotificationsResourceImpl implements Notify {
  private final Logger logger = LoggerFactory.getLogger("modnotify");
  private final Messages messages = Messages.getInstance();
  private static final String NOTIFY_TABLE = "notify_data";
  private static final String LOCATION_PREFIX = "/notify/";
  private static final String IDFIELDNAME = "id";
  private String notifySchema = null;
  private static final String NOTIFY_SCHEMA_NAME = "ramls/notify.json";
  private static final int DAYS_TO_KEEP_SEEN_NOTIFICATIONS = 365;

  private void initCQLValidation() {
    try {
      notifySchema = IOUtils.toString(getClass().getClassLoader().getResourceAsStream(NOTIFY_SCHEMA_NAME), "UTF-8");
    } catch (Exception e) {
      logger.error("unable to load schema - " + NOTIFY_SCHEMA_NAME + ", validation of query fields will not be active");
    }
  }

  public NotificationsResourceImpl(Vertx vertx, String tenantId) {
    if (notifySchema == null) {
      initCQLValidation();
    }
    PostgresClient.getInstance(vertx, tenantId).setIdField(IDFIELDNAME);
  }

  private CQLWrapper getCQL(String query, int limit, int offset)
    throws FieldException, IOException, SchemaException {
    CQL2PgJSON cql2pgJson;
    if (notifySchema != null) {
      cql2pgJson = new CQL2PgJSON(NOTIFY_TABLE + ".jsonb", notifySchema);
    } else {
      cql2pgJson = new CQL2PgJSON(NOTIFY_TABLE + ".jsonb");
    }
    CQLWrapper wrap = new CQLWrapper(cql2pgJson, query);
    if (limit >= 0) {
      wrap.setLimit(new Limit(limit));
    }
    if (offset >= 0) {
      wrap.setOffset(new Offset(offset));
    }
    return wrap;
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
  public void getNotifySelf(String query, int offset, int limit,
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

    String tenantId = TenantTool.calculateTenantId(
      okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));

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
    PostgresClient.getInstance(vertxContext.owner(), tenantId)
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
    HttpClientInterface client = HttpClientFactory.getHttpClient(okapiURL, tenantId);
    String url = "/users?query=username=" + userName;
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
    String userId, Context vertxContext, String lang) {
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
            logger.error("User lookup failed for " + userId + ". Bad response");
            logger.error(Json.encodePrettily(resp));
            asyncResultHandler.handle(succeededFuture(PostNotifyUsernameByUsernameResponse
              .respond400WithTextPlain("User lookup failed for " + userId + ". "
                + "Bad response " + userResp)));
          }
        } else {  // Can not use ValidationHelper here, we have HTTP responses
          logger.error("User lookup failed for " + userId);
          logger.error(Json.encodePrettily(resp));
          asyncResultHandler.handle(succeededFuture(PostNotifyUsernameByUsernameResponse
            .respond400WithTextPlain("User lookup failed. "
              + "Can not find user " + userId)));
        }
        break;
      case 403:
        logger.error("Permission problem: User lookup failed for " + userId);
        logger.error(Json.encodePrettily(resp));
        asyncResultHandler.handle(succeededFuture(PostNotifyUsernameByUsernameResponse
          .respond400WithTextPlain("User lookup failed with 403. " + userId
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
    String tenantId = TenantTool.calculateTenantId(
      okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
    String id = entity.getId();
    PostgresClient.getInstance(context.owner(), tenantId).save(NOTIFY_TABLE,
      id, entity,
      reply -> {
        if (reply.succeeded()) {
          String ret = reply.result();
          entity.setId(ret);
          asyncResultHandler.handle(succeededFuture(PostNotifyResponse
            .respond201WithApplicationJson(entity, PostNotifyResponse.
              headersFor201().withLocation(LOCATION_PREFIX + ret))));
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
  public void postNotifySelf(String lang, Notification entity,
    Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {
    throw new UnsupportedOperationException("Not supported.");
  }

  /**
   * Helper to delete old, seen notifications.
   *
   * @param tenantId
   * @param userId
   */
  private void deleteAllOldNotifications(String tenantId, String userId,
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
    PostgresClient.getInstance(vertxContext.owner(), tenantId)
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
  public void deleteNotifySelf(String olderthan, String lang,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {
    String tenantId = TenantTool.calculateTenantId(okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
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
    PostgresClient.getInstance(vertxContext.owner(), tenantId)
      .delete(NOTIFY_TABLE, cql,
        reply -> {
          if (reply.succeeded()) {
            if (reply.result().getUpdated() > 0) {
              logger.info("Deleted " + reply.result().getUpdated() + " notifies");
              asyncResultHandler.handle(succeededFuture(
                DeleteNotifySelfResponse.respond204()));
            } else {
              logger.info("Deleted no notifications");
              logger.error(messages.getMessage(lang,
                MessageConsts.DeletedCountError, 1, reply.result().getUpdated()));
              asyncResultHandler.handle(succeededFuture(DeleteNotifySelfResponse
                .respond404WithTextPlain(messages.getMessage(lang,
                  MessageConsts.DeletedCountError, 1, reply.result().getUpdated()))));
            }
          } else {
            ValidationHelper.handleError(reply.cause(), asyncResultHandler);
          }
        });
  }


  @Override
  @Validate
  public void getNotifyById(String id,
    String lang, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context context) {
    if (id.equals("_self")) {
        // The _self endpoint has already handled this request
        return;
      }
    String tenantId = TenantTool.calculateTenantId(
      okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
    Criterion c = new Criterion(
      new Criteria().addField(IDFIELDNAME).setJSONB(false)
        .setOperation("=").setValue("'" + id + "'"));

    PostgresClient.getInstance(context.owner(), tenantId)
      .get(NOTIFY_TABLE, Notification.class, c, true,
        reply -> {
          if (reply.succeeded()) {
            @SuppressWarnings("unchecked")
            List<Notification> notifylist
            = (List<Notification>) reply.result().getResults();
            if (notifylist.isEmpty()) {
              asyncResultHandler.handle(succeededFuture(GetNotifyByIdResponse
                .respond404WithTextPlain(id)));
            } else {
              asyncResultHandler.handle(succeededFuture(GetNotifyByIdResponse
                .respond200WithApplicationJson(notifylist.get(0))));
            }
          } else {
            ValidationHelper.handleError(reply.cause(), asyncResultHandler);
          }
        });
  }

  @Override
  @Validate
  public void deleteNotifyById(String id, String lang, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    String tenantId = TenantTool.calculateTenantId(
      okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
    if (id.equals("_self")) {
      // The _self endpoint has already handled this request
      return;
    }

    PostgresClient.getInstance(vertxContext.owner(), tenantId)
      .delete(NOTIFY_TABLE, id,
        reply -> {
          if (reply.succeeded()) {
            if (reply.result().getUpdated() == 1) {
              asyncResultHandler.handle(succeededFuture(
                DeleteNotifyByIdResponse.respond204()));
            } else {
              logger.error(messages.getMessage(lang,
                MessageConsts.DeletedCountError, 1, reply.result().getUpdated()));
              asyncResultHandler.handle(succeededFuture(DeleteNotifyByIdResponse
                .respond404WithTextPlain(messages.getMessage(lang,
                  MessageConsts.DeletedCountError, 1, reply.result().getUpdated()))));
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
    String tenantId = TenantTool.calculateTenantId(
      okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
    String userId = okapiHeaders.get(RestVerticle.OKAPI_USERID_HEADER);
    PostgresClient.getInstance(vertxContext.owner(), tenantId)
      .update(NOTIFY_TABLE, entity, id,
        reply -> {
          if (reply.succeeded()) {
            if (reply.result().getUpdated() == 0)
              asyncResultHandler.handle(succeededFuture(PutNotifyByIdResponse
                .respond404WithTextPlain(id)));
            else { // all ok
              deleteAllOldNotifications(tenantId, userId,
                dres -> asyncResultHandler.handle(succeededFuture(
                  PutNotifyByIdResponse.respond204())),
                 vertxContext);
            }
          } else {
            ValidationHelper.handleError(reply.cause(), asyncResultHandler);
          }
        });
  }

}
