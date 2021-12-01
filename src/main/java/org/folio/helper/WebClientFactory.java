package org.folio.helper;

import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;

import java.util.HashMap;
import java.util.Map;

public class WebClientFactory {
  private static final Map<Vertx, WebClient> clients = new HashMap<>();

  /**
   * Create WebClient for Vert.x instance.
   * @param vertx object.
   * @return webclient.
   */
  public static synchronized WebClient getWebClient(Vertx vertx) {
    if (clients.containsKey(vertx)) {
      return clients.get(vertx);
    }
    WebClient webClient = WebClient.create(vertx);
    clients.put(vertx, webClient);
    return webClient;
  }
}
