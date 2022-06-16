package org.folio.helper;

import org.folio.okapi.testing.UtilityClassTester;
import org.junit.Test;

public class WebClientFactoryTest {

  @Test
  public void isUtilityClass() {
    UtilityClassTester.assertUtilityClass(WebClientFactory.class);
  }
}
