package org.folio.rest.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import static io.vertx.core.Future.succeededFuture;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
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
import org.folio.rest.jaxrs.resource.NotificationsResource;
import org.folio.rest.jaxrs.model.User;
import org.folio.rest.jaxrs.model.UserdataCollection;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.Criteria.Limit;
import org.folio.rest.persist.Criteria.Offset;
import org.folio.rest.persist.PgExceptionUtil;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLQueryValidationException;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.tools.client.HttpClientFactory;
import org.folio.rest.tools.client.interfaces.HttpClientInterface;
import org.folio.rest.tools.messages.MessageConsts;
import org.folio.rest.tools.messages.Messages;
import org.folio.rest.tools.utils.OutStream;
import org.folio.rest.tools.utils.TenantTool;
import org.folio.rest.tools.utils.ValidationHelper;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSON;


public class NotificationsResourceImpl implements NotificationsResource {
  private final Logger logger = LoggerFactory.getLogger("okapi");
  private final Messages messages = Messages.getInstance();
  private static final String NOTIFY_TABLE = "notify_data";
  private static final String LOCATION_PREFIX = "/notify/";
  private final String idFieldName = "id";
  private static String NOTIFY_SCHEMA = null;
  private static final String NOTIFY_SCHEMA_NAME = "apidocs/raml/notify.json";
  private static final int DAYS_TO_KEEP_SEEN_NOTIFICATIONS = 365;

  private void initCQLValidation() {
    String path = NOTIFY_SCHEMA_NAME;
    try {
      NOTIFY_SCHEMA = IOUtils.toString(
        getClass().getClassLoader().getResourceAsStream(path), "UTF-8");
    } catch (Exception e) {
      logger.error("unable to load schema - " + path
        + ", validation of query fields will not be active");
    }
  }

  public NotificationsResourceImpl(Vertx vertx, String tenantId) {
    if (NOTIFY_SCHEMA == null) {
      //initCQLValidation();  // COmmented out, the validation fails a
      // prerfectly valid query=metaData.createdByUserId=e037b...
    }
    PostgresClient.getInstance(vertx, tenantId).setIdField(idFieldName);
  }

  private CQLWrapper getCQL(String query, int limit, int offset,
    String schema) throws Exception {
    CQL2PgJSON cql2pgJson;
    if (schema != null) {
      cql2pgJson = new CQL2PgJSON(NOTIFY_TABLE + ".jsonb", schema);
    } else {
      cql2pgJson = new CQL2PgJSON(NOTIFY_TABLE + ".jsonb");
    }
    return new CQLWrapper(cql2pgJson, query)
      .setLimit(new Limit(limit))
      .setOffset(new Offset(offset));
  }

  private CQLWrapper getCQL(String query,
    String schema) throws Exception {
    CQL2PgJSON cql2pgJson = null;
    if (schema != null) {
      cql2pgJson = new CQL2PgJSON(NOTIFY_TABLE + ".jsonb", schema);
    } else {
      cql2pgJson = new CQL2PgJSON(NOTIFY_TABLE + ".jsonb");
    }
    return new CQLWrapper(cql2pgJson, query);
  }


  @Override
  public void getNotify(String query,
    int offset, int limit,
    String lang,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) throws Exception {
    try {
      logger.info("Getting notifications. " + offset + "+" + limit + " q=" + query);
      String tenantId = TenantTool.calculateTenantId(
        okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
      CQLWrapper cql = getCQL(query, limit, offset, NOTIFY_SCHEMA);

      PostgresClient.getInstance(vertxContext.owner(), tenantId)
        .get(NOTIFY_TABLE, Notification.class, new String[]{"*"}, cql, true, true,
          reply -> {
            try {
              if (reply.succeeded()) {
                NotifyCollection notifycoll = new NotifyCollection();
                Integer totalRecords = reply.result().getResultInfo().getTotalRecords();
                notifycoll.setTotalRecords(totalRecords);
                @SuppressWarnings("unchecked")
                List<Notification> notifylist
                  = (List<Notification>) reply.result().getResults();
                notifycoll.setNotifications(notifylist);
                asyncResultHandler.handle(succeededFuture(
                  GetNotifyResponse.withJsonOK(notifycoll)));
              } else {
                logger.error(reply.cause().getMessage(), reply.cause());
                asyncResultHandler.handle(succeededFuture(GetNotifyResponse
                  .withPlainBadRequest(reply.cause().getMessage())));
              }
            } catch (Exception e) {
              logger.error(e.getMessage(), e);
              asyncResultHandler.handle(succeededFuture(GetNotifyResponse
                .withPlainInternalServerError(messages.getMessage(
                      lang, MessageConsts.InternalServerError))));
            }
          });
    } catch (CQLQueryValidationException e1) {
      int start = e1.getMessage().indexOf("'");
      int end = e1.getMessage().lastIndexOf("'");
      String field = e1.getMessage();
      if (start != -1 && end != -1) {
        field = field.substring(start + 1, end);
      }
      Errors e = ValidationHelper.createValidationErrorMessage(field,
        "", e1.getMessage());
      asyncResultHandler.handle(succeededFuture(GetNotifyResponse
        .withJsonUnprocessableEntity(e)));
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      String message = messages.getMessage(lang, MessageConsts.InternalServerError);
      if (e.getCause() != null && e.getCause().getClass().getSimpleName()
        .endsWith("CQLParseException")) {
        message = " CQL parse error " + e.getLocalizedMessage();
      }
      asyncResultHandler.handle(succeededFuture(GetNotifyResponse
        .withPlainInternalServerError(message)));
    }
  }

  @Override
  public void postNotifyUseridByUid(String userId, String lang, Notification notification,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext)
    throws Exception {

    logger.info("postNotifyUseridByUid starting userId='" + userId + "'");
    String tenantId = TenantTool.calculateTenantId(
      okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
    String okapiURL = okapiHeaders.get("X-Okapi-Url");
    HttpClientInterface client = HttpClientFactory.getHttpClient(okapiURL, tenantId);
    String url = "/users?query=username=" + userId;
    try {
      logger.debug("Looking up user " + url);
      CompletableFuture<org.folio.rest.tools.client.Response> response
        = client.request(url, okapiHeaders);
      response.whenComplete((resp, ex)
        -> handleLookupUserResponse(resp, notification, okapiHeaders,
          asyncResultHandler, userId, vertxContext, lang)
      );
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      asyncResultHandler.handle(
        succeededFuture(PostNotifyUseridByUidResponse.withPlainInternalServerError(
            messages.getMessage(lang, MessageConsts.InternalServerError))));
    }
  }

  private void handleLookupUserResponse(
    org.folio.rest.tools.client.Response resp, Notification notification,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    String userId, Context vertxContext, String lang) {
    try {
      if (resp.getCode() == 200) {
        logger.info("Received user " + resp.getBody());
        UserdataCollection userlist = (UserdataCollection) resp.convertToPojo(UserdataCollection.class);
        if (userlist.getTotalRecords()>0) {
          User usr = userlist.getUsers().get(0);
          notification.setRecipientId(usr.getId());
          postNotify(lang, notification, okapiHeaders, asyncResultHandler, vertxContext);
        } else {
          logger.error("User lookup failed for " + userId);
          logger.error(Json.encodePrettily(resp));
          asyncResultHandler.handle(succeededFuture(PostNotifyUseridByUidResponse
            .withPlainBadRequest("User lookup failed. "
              + "Can not find user " + userId)));
        }
      } else if (resp.getCode() == 404) { // should not happen
        logger.error("User lookup failed for " + userId);
        logger.error(Json.encodePrettily(resp));
        asyncResultHandler.handle(succeededFuture(PostNotifyUseridByUidResponse
          .withPlainBadRequest("User lookup failed. "
            + "Can not find user " + userId)));
      } else if (resp.getCode() == 403) {
        logger.error("User lookup failed for " + userId);
        logger.error(Json.encodePrettily(resp));
        asyncResultHandler.handle(succeededFuture(PostNotifyUseridByUidResponse
          .withPlainBadRequest("User lookup failed with 403. " + userId
            + " " + Json.encode(resp.getError()))));
      } else {
        logger.error("User lookup failed with " + resp.getCode());
        logger.error(Json.encodePrettily(resp));
        asyncResultHandler.handle(
          succeededFuture(PostNotifyUseridByUidResponse.withPlainInternalServerError(
              messages.getMessage(lang, MessageConsts.InternalServerError))));
      }
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      asyncResultHandler.handle(
        succeededFuture(PostNotifyUseridByUidResponse.withPlainInternalServerError(
            messages.getMessage(lang, MessageConsts.InternalServerError))));
    }

  }

  @Override
  public void postNotify(String lang,
    Notification entity,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context context) throws Exception {

    try {
      String recip = entity.getRecipientId();
      if (recip == null || recip.isEmpty()) {
        Errors valErr = ValidationHelper.createValidationErrorMessage(
          "recipientId", "", "Required");
        asyncResultHandler.handle(succeededFuture(PostNotifyResponse
          .withJsonUnprocessableEntity(valErr)));
        return;
      }

      String tenantId = TenantTool.calculateTenantId(
        okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
      String id = entity.getId();
      PostgresClient.getInstance(context.owner(), tenantId).save(NOTIFY_TABLE,
        id, entity,
        reply -> {
          try {
            if (reply.succeeded()) {
              Object ret = reply.result();
              entity.setId((String) ret);
              OutStream stream = new OutStream();
              stream.setData(entity);
              asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                  .withJsonCreated(LOCATION_PREFIX + ret, stream)));
            } else {
              String msg = reply.cause().getMessage();
              if (msg.contains("duplicate key value violates unique constraint")) {
                Errors valErr = ValidationHelper.createValidationErrorMessage(
                  "id", id, "Duplicate id");
                asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                    .withJsonUnprocessableEntity(valErr)));
              } else {
                String error = PgExceptionUtil.badRequestMessage(reply.cause());
                logger.error(msg, reply.cause());
                if (error == null) {
                  asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                      .withPlainInternalServerError(
                        messages.getMessage(lang, MessageConsts.InternalServerError))));
                } else {
                  asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                      .withPlainBadRequest(error)));
                }
              }
            }
          } catch (Exception e) {
            logger.error(e.getMessage(), e);
            asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                .withPlainInternalServerError(
                  messages.getMessage(lang, MessageConsts.InternalServerError))));
          }
        });

    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      asyncResultHandler.handle(
        succeededFuture(PostNotifyResponse.withPlainInternalServerError(
            messages.getMessage(lang, MessageConsts.InternalServerError)))
      );
    }
  }

  @Override
  public void getNotifySelf(String query, int offset, int limit,
    String lang, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) throws Exception {
    try {
      logger.debug("Getting self notes. " + offset + "+" + limit + " q=" + query);
      String tenantId = TenantTool.calculateTenantId(
        okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
      String userId = okapiHeaders.get(RestVerticle.OKAPI_USERID_HEADER);
      if (userId == null || userId.isEmpty()) {
        logger.error("No userId for getNotesSelf");
        asyncResultHandler.handle(succeededFuture(GetNotifyResponse
          .withPlainBadRequest("No UserId")));
        return;
      }
      String selfQuery = "recipientId=" + userId;
      if (query == null || query.isEmpty()) {
        query = selfQuery;
      } else {
        query = selfQuery + " and (" + query + ")";
      }
      logger.debug("Getting self notes. new query:" + query);
      CQLWrapper cql = getCQL(query, limit, offset, NOTIFY_SCHEMA);

      PostgresClient.getInstance(vertxContext.owner(), tenantId)
        .get(NOTIFY_TABLE, Notification.class, new String[]{"*"}, cql,
          true /*get count too*/, false /* set id */,
          reply -> {
            try {
              if (reply.succeeded()) {
                NotifyCollection notes = new NotifyCollection();
                @SuppressWarnings("unchecked")
                List<Notification> notifylist
                  = (List<Notification>) reply.result().getResults();
                notes.setNotifications(notifylist);
                Integer totalRecords = reply.result().getResultInfo().getTotalRecords();
                notes.setTotalRecords(totalRecords);
                asyncResultHandler.handle(succeededFuture(
                  GetNotifyResponse.withJsonOK(notes)));
              } else {
                logger.error(reply.cause().getMessage(), reply.cause());
                asyncResultHandler.handle(succeededFuture(GetNotifyResponse
                    .withPlainBadRequest(reply.cause().getMessage())));
              }
            } catch (Exception e) {
              logger.error(e.getMessage(), e);
              asyncResultHandler.handle(succeededFuture(GetNotifyResponse
                  .withPlainInternalServerError(messages.getMessage(
                      lang, MessageConsts.InternalServerError))));
            }
          });
    } catch (CQLQueryValidationException e1) {
      int start = e1.getMessage().indexOf("'");
      int end = e1.getMessage().lastIndexOf("'");
      String field = e1.getMessage();
      if (start != -1 && end != -1) {
        field = field.substring(start + 1, end);
      }
      Errors e = ValidationHelper.createValidationErrorMessage(field,
        "", e1.getMessage());
      asyncResultHandler.handle(succeededFuture(GetNotifyResponse
        .withJsonUnprocessableEntity(e)));
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      String message = messages.getMessage(lang, MessageConsts.InternalServerError);
      if (e.getCause() != null && e.getCause().getClass().getSimpleName()
        .endsWith("CQLParseException")) {
        message = " CQL parse error " + e.getLocalizedMessage();
      }
      asyncResultHandler.handle(succeededFuture(GetNotifyResponse
        .withPlainInternalServerError(message)));
    }
  }

  /**
   * Post to _self is not supported, but RMB builds this anyway.
   *
   */
  @Override
  public void postNotifySelf(String lang, Notification entity, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
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
    Context vertxContext) throws Exception {
    String query;
    String selfQuery = "recipientId=\"" + userId + "\""
       + " and seen=true";
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime limit = now.minus(DAYS_TO_KEEP_SEEN_NOTIFICATIONS, ChronoUnit.DAYS);
    // TODO - This is not right, hard coding the time limit. We need a better
    // way to purge old notifications. Some day when we know what we do with other
    // housekeeping jobs
    String olderthan = limit.format(DateTimeFormatter.ISO_DATE);
    query = selfQuery + " and (metadata.updatedDate<" + olderthan + ")";
    logger.info(" deleteAllOldNotifications: new query:" + query);
    CQLWrapper cql = getCQL(query, NOTIFY_SCHEMA);
    PostgresClient.getInstance(vertxContext.owner(), tenantId)
      .delete(NOTIFY_TABLE, cql, reply -> {
        if (reply.succeeded()) {
          logger.info("deleteAllOldNotifications ok. Deleted " + reply.result().getUpdated() + " records");
        } else {
          logger.error("deleteAllOldNotifications failure " + reply.cause());
        }
      // Ignore all errors, we will catch old notifies the next time
        asyncResultHandler.handle(succeededFuture());
      });
  }

  @Override
  public void deleteNotifySelf(String olderthan,
    //String query,
    //int offset, int limit,
    String lang, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) throws Exception {
    try {
      String tenantId = TenantTool.calculateTenantId(
        okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
      String userId = okapiHeaders.get(RestVerticle.OKAPI_USERID_HEADER);
      logger.debug("Trying to delete _self notifies for "
        + tenantId + "/" + userId + " since " + olderthan);
      if (userId == null || userId.isEmpty()) {
        logger.error("No userId for deleteNotesSelf");
        asyncResultHandler.handle(succeededFuture(GetNotifyResponse
          .withPlainBadRequest("No UserId")));
        return;
      }
      String query;
      String selfQuery = "recipientId=\"" + userId + "\""
        + " and seen=true";
      if (olderthan == null || olderthan.isEmpty()) {
        query = selfQuery;
      } else {
        query = selfQuery + " and (metadata.updatedDate<" + olderthan + ")";
      }
      logger.info("Deleting self notifications. new query:" + query);
      CQLWrapper cql = getCQL(query, NOTIFY_SCHEMA);
      PostgresClient.getInstance(vertxContext.owner(), tenantId)
        .delete(NOTIFY_TABLE, cql,
          reply -> {
            if (reply.succeeded()) {
              if (reply.result().getUpdated() > 0) {
                logger.info("Deleted " + reply.result().getUpdated() + " notifies");
                asyncResultHandler.handle(succeededFuture(
                    DeleteNotifyByIdResponse.withNoContent()));
              } else {
                logger.info("Deleted no notifications");
                logger.error(messages.getMessage(lang,
                    MessageConsts.DeletedCountError, 1, reply.result().getUpdated()));
                asyncResultHandler.handle(succeededFuture(DeleteNotifyByIdResponse
                    .withPlainNotFound(messages.getMessage(lang,
                        MessageConsts.DeletedCountError, 1, reply.result().getUpdated()))));
              }
            } else {
              String error = PgExceptionUtil.badRequestMessage(reply.cause());
              logger.error(error, reply.cause());
              if (error == null) {
                asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                    .withPlainInternalServerError(
                      messages.getMessage(lang, MessageConsts.InternalServerError))));
              } else {
                asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                    .withPlainBadRequest(error)));
              }
            }

          });
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      String message = messages.getMessage(lang, MessageConsts.InternalServerError);
      if (e.getCause() != null && e.getCause().getClass().getSimpleName()
        .endsWith("CQLParseException")) {
        message = " CQL parse error " + e.getLocalizedMessage();
      }
      asyncResultHandler.handle(succeededFuture(GetNotifyResponse
        .withPlainInternalServerError(message)));
    }
  }

  @Override
  public void getNotifyById(String id,
    String lang, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context context) throws Exception {
    try {
      if (id.equals("_self")) {
        // The _self endpoint has already handled this request
        return;
      }
      String tenantId = TenantTool.calculateTenantId(
        okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
      Criterion c = new Criterion(
        new Criteria().addField(idFieldName).setJSONB(false)
        .setOperation("=").setValue("'" + id + "'"));

      PostgresClient.getInstance(context.owner(), tenantId)
        .get(NOTIFY_TABLE, Notification.class, c, true,
          reply -> {
            try {
              if (reply.succeeded()) {

                @SuppressWarnings("unchecked")
                List<Notification> notifylist
                  = (List<Notification>) reply.result().getResults();
                if (notifylist.isEmpty()) {
                  asyncResultHandler.handle(succeededFuture(GetNotifyByIdResponse
                      .withPlainNotFound(id)));
                } else {
                  asyncResultHandler.handle(succeededFuture(GetNotifyByIdResponse
                    .withJsonOK(notifylist.get(0))));
                }
              } else {
                String error = PgExceptionUtil.badRequestMessage(reply.cause());
                logger.error(error, reply.cause());
                if (error == null) {
                  asyncResultHandler.handle(succeededFuture(GetNotifyByIdResponse
                      .withPlainInternalServerError(
                        messages.getMessage(lang, MessageConsts.InternalServerError))));
                } else {
                  asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                      .withPlainBadRequest(error)));
                }
              }
            } catch (Exception e) {
              logger.error(e.getMessage(), e);
              asyncResultHandler.handle(succeededFuture(GetNotifyByIdResponse
                  .withPlainInternalServerError(
                    messages.getMessage(lang, MessageConsts.InternalServerError))));
            }
          });
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      asyncResultHandler.handle(succeededFuture(GetNotifyByIdResponse
        .withPlainInternalServerError(
          messages.getMessage(lang, MessageConsts.InternalServerError))));
    }
  }

  @Override
  public void deleteNotifyById(String id, String lang, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    String tenantId = TenantTool.calculateTenantId(
      okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
    try {
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
                    DeleteNotifyByIdResponse.withNoContent()));
              } else {
                logger.error(messages.getMessage(lang,
                    MessageConsts.DeletedCountError, 1, reply.result().getUpdated()));
                asyncResultHandler.handle(succeededFuture(DeleteNotifyByIdResponse
                    .withPlainNotFound(messages.getMessage(lang,
                        MessageConsts.DeletedCountError, 1, reply.result().getUpdated()))));
              }
            } else {
              String error = PgExceptionUtil.badRequestMessage(reply.cause());
              logger.error(error, reply.cause());
              if (error == null) {
                asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                    .withPlainInternalServerError(
                      messages.getMessage(lang, MessageConsts.InternalServerError))));
              } else {
                asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                    .withPlainBadRequest(error)));
              }
            }
          });
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      asyncResultHandler.handle(succeededFuture(DeleteNotifyByIdResponse
        .withPlainInternalServerError(
          messages.getMessage(lang, MessageConsts.InternalServerError))));
    }
  }

  @Override
  public void putNotifyById(String id, String lang,
    Notification entity,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) throws Exception {
    try {
      logger.info("PUT notify " + id + " " + Json.encode(entity));
      String noteId = entity.getId();
      if (noteId != null && !noteId.equals(id)) {
        logger.error("Trying to change note Id from " + id + " to " + noteId);
        Errors valErr = ValidationHelper.createValidationErrorMessage("id", noteId,
          "Can not change the id");
        asyncResultHandler.handle(succeededFuture(PutNotifyByIdResponse
          .withJsonUnprocessableEntity(valErr)));
        return;
      }
      String recip = entity.getRecipientId();
      if (recip == null || recip.isEmpty()) {
        Errors valErr = ValidationHelper.createValidationErrorMessage(
          "recipientId", "", "Required");
        asyncResultHandler.handle(succeededFuture(PutNotifyByIdResponse
          .withJsonUnprocessableEntity(valErr)));
        return;

      }
      String tenantId = TenantTool.calculateTenantId(
        okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
      String userId = okapiHeaders.get(RestVerticle.OKAPI_USERID_HEADER);
      PostgresClient.getInstance(vertxContext.owner(), tenantId).update(NOTIFY_TABLE, entity, id,
        reply -> {
          try {
            if (reply.succeeded()) {
              if (reply.result().getUpdated() == 0) {
                asyncResultHandler.handle(succeededFuture(
                    PutNotifyByIdResponse.withPlainInternalServerError(
                      messages.getMessage(lang, MessageConsts.NoRecordsUpdated))));
              } else { // all ok
                deleteAllOldNotifications(tenantId, userId, dres -> {
                  asyncResultHandler.handle(succeededFuture(
                    PutNotifyByIdResponse.withNoContent()));
                }, vertxContext);
              }
            } else {
              logger.error(reply.cause().getMessage());
              asyncResultHandler.handle(succeededFuture(
                  PutNotifyByIdResponse.withPlainInternalServerError(
                    messages.getMessage(lang, MessageConsts.InternalServerError))));
            }
          } catch (Exception e) {
            logger.error(e.getMessage(), e);
            asyncResultHandler.handle(succeededFuture(
                PutNotifyByIdResponse.withPlainInternalServerError(
                  messages.getMessage(lang, MessageConsts.InternalServerError))));
          }
        });
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      asyncResultHandler.handle(succeededFuture(PutNotifyByIdResponse
        .withPlainInternalServerError(
          messages.getMessage(lang, MessageConsts.InternalServerError))));
    }
  }

}
