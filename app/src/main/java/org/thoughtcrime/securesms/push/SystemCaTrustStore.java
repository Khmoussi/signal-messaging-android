package org.thoughtcrime.securesms.push;

import org.whispersystems.signalservice.api.push.TrustStore;

import java.io.InputStream;

/**
 * Marker trust store for self-hosted servers that use public CAs (e.g. Let's Encrypt).
 * {@link org.whispersystems.signalservice.internal.util.BlacklistingTrustManager} treats this
 * as "use the platform default trust managers" instead of Signal's pinned BKS.
 */
public final class SystemCaTrustStore implements TrustStore {

  @Override
  public InputStream getKeyStoreInputStream() {
    throw new UnsupportedOperationException("SystemCaTrustStore uses the platform trust managers");
  }

  @Override
  public String getKeyStorePassword() {
    throw new UnsupportedOperationException("SystemCaTrustStore uses the platform trust managers");
  }

  @Override
  public boolean useSystemCaStore() {
    return true;
  }
}
