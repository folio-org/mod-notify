package org.folio.helper;

import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;

import java.util.HashMap;
import java.util.Map;

public final class WebClientFactory {
  private static final Map<Vertx, WebClient> clients = new HashMap<>();

  private WebClientFactory() {
    throw new UnsupportedOperationException("WebClientFactory");
  }

  /**
   * get WebClient - one instance per Vert.x instance.
   * @param vertx object.
   * @return webclient.
   */
  public static synchronized WebClient getWebClient(Vertx vertx) {
    return clients.computeIfAbsent(vertx, x -> WebClient.create(vertx));
  }
}
