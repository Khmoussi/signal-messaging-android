/**
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package org.whispersystems.signalservice.api.push;

import java.io.InputStream;

/**
 * A class that represents a Java {@link java.security.KeyStore} and
 * its associated password.
 */
public interface TrustStore {
  public InputStream getKeyStoreInputStream();
  public String getKeyStorePassword();

  /**
   * When true, TLS should trust the platform CA store instead of {@link #getKeyStoreInputStream()}.
   */
  default boolean useSystemCaStore() {
    return false;
  }
}

