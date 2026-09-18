/**
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package org.whispersystems.signalservice.internal.util;

import org.whispersystems.signalservice.api.push.TrustStore;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import kotlin.Pair;

/**
 * Trust manager that defers to a system X509 trust manager, and
 * additionally rejects certificates if they have a blacklisted
 * serial.
 *
 * @author Moxie Marlinspike
 */
public class BlacklistingTrustManager implements X509TrustManager {

  private static final List<Pair<String, BigInteger>> BLACKLIST = new LinkedList<Pair<String, BigInteger>>() {{
    add(new Pair<>("Open Whisper Systems", new BigInteger("4098")));
  }};

  public static TrustManager[] createFor(TrustManager[] trustManagers) {
    for (TrustManager trustManager : trustManagers) {
      if (trustManager instanceof X509TrustManager) {
        TrustManager[] results = new BlacklistingTrustManager[1];
        results[0] = new BlacklistingTrustManager((X509TrustManager)trustManager);

        return results;
      }
    }

    throw new AssertionError("No X509 Trust Managers!");
  }

  public static TrustManager[] createFor(TrustStore trustStore) {
    try {
      if (trustStore.useSystemCaStore()) {
        TrustManagerFactory systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        systemTmf.init((KeyStore) null);
        return BlacklistingTrustManager.createFor(systemTmf.getTrustManagers());
      }

      InputStream keyStoreInputStream = trustStore.getKeyStoreInputStream();
      KeyStore    keyStore            = KeyStore.getInstance("BKS");

      keyStore.load(keyStoreInputStream, trustStore.getKeyStorePassword().toCharArray());

      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("X509");
      trustManagerFactory.init(keyStore);

      return BlacklistingTrustManager.createFor(trustManagerFactory.getTrustManagers());
    } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private final X509TrustManager trustManager;

  public BlacklistingTrustManager(X509TrustManager trustManager) {
    this.trustManager = trustManager;
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException
  {
    trustManager.checkClientTrusted(chain, authType);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws CertificateException
  {
    trustManager.checkServerTrusted(chain, authType);
    assertNotBlacklisted(chain);
  }

  /**
   * Hostname-aware trust check. Conscrypt discovers and invokes this via reflection when
   * present. Required on Android when {@code network_security_config.xml} contains
   * {@code <domain-config>} entries: the platform {@code RootTrustManager} rejects the
   * 2-arg {@link #checkServerTrusted(X509Certificate[], String)} path with
   * "Domain specific configurations require that hostname aware checkServerTrusted...".
   */
  @SuppressWarnings("unused")
  public List<X509Certificate> checkServerTrusted(X509Certificate[] chain, String authType, String host)
      throws CertificateException
  {
    List<X509Certificate> trusted = delegateCheckServerTrusted(chain, authType, host);
    assertNotBlacklisted(chain);
    return trusted;
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return trustManager.getAcceptedIssuers();
  }

  private List<X509Certificate> delegateCheckServerTrusted(X509Certificate[] chain, String authType, String host)
      throws CertificateException
  {
    try {
      Method method = trustManager.getClass().getMethod(
          "checkServerTrusted",
          X509Certificate[].class,
          String.class,
          String.class
      );
      Object result = method.invoke(trustManager, chain, authType, host);
      if (result instanceof List) {
        @SuppressWarnings("unchecked")
        List<X509Certificate> trusted = (List<X509Certificate>) result;
        return trusted;
      }
      return Arrays.asList(chain);
    } catch (NoSuchMethodException e) {
      trustManager.checkServerTrusted(chain, authType);
      return Arrays.asList(chain);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof CertificateException) {
        throw (CertificateException) cause;
      }
      throw new CertificateException(cause);
    } catch (ReflectiveOperationException e) {
      throw new CertificateException(e);
    }
  }

  private void assertNotBlacklisted(X509Certificate[] chain) throws CertificateException {
    for (X509Certificate certificate : chain) {
      for (Pair<String, BigInteger> blacklistedSerial : BLACKLIST) {
        if (certificate.getIssuerDN().getName().equals(blacklistedSerial.getFirst()) &&
            certificate.getSerialNumber().equals(blacklistedSerial.getSecond()))
        {
          throw new CertificateException("Blacklisted Serial: " + certificate.getSerialNumber());
        }
      }
    }
  }
}
