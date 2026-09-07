package com.gitutility.service;

import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.HttpConnectionFactory2;
import org.eclipse.jgit.transport.http.JDKHttpConnection;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitWireByteMeterTest {

  private HttpConnectionFactory previousFactory;

  @AfterEach
  void restoreFactory() {
    GitWireByteMeter.resetForTests();
    if (previousFactory != null) {
      HttpTransport.setConnectionFactory(previousFactory);
    }
  }

  @Test
  void jdkSessionConfigureAcceptsMeteringConnection() throws Exception {
    previousFactory = HttpTransport.getConnectionFactory();
    HttpTransport.setConnectionFactory(new JDKHttpConnectionFactory());

    try (GitWireByteMeter meter = GitWireByteMeter.open()) {
      HttpConnectionFactory2 factory = (HttpConnectionFactory2) HttpTransport.getConnectionFactory();
      HttpConnectionFactory2.GitSession session = factory.newSession();
      try {
        HttpConnection connection = factory.create(URI.create("https://example.com/repo.git").toURL());
        assertTrue(connection instanceof JDKHttpConnection);
        HttpConnection configured = session.configure(connection, true);
        assertTrue(configured instanceof JDKHttpConnection);
        assertEquals(connection, configured);
      } finally {
        session.close();
      }
    }
  }

  @Test
  void countsHttpReadAndWriteBytesForActiveMeter() throws Exception {
    previousFactory = HttpTransport.getConnectionFactory();
    HttpTransport.setConnectionFactory(new StubHttpConnectionFactory());

    try (GitWireByteMeter meter = GitWireByteMeter.open()) {
      HttpConnection connection = HttpTransport.getConnectionFactory().create(URI.create("https://example.com/repo.git").toURL());
      try (InputStream in = connection.getInputStream();
           OutputStream out = connection.getOutputStream()) {
        byte[] payload = new byte[512];
        int totalRead = 0;
        int n;
        while ((n = in.read(payload)) > 0) {
          totalRead += n;
        }
        out.write(new byte[64]);
        assertEquals(256, totalRead);
      }
      assertEquals(256, meter.readBytes());
      assertEquals(64, meter.writeBytes());
      assertEquals(320, meter.gitWireBytes());
    }
  }

  @Test
  void formatTransferVolumeSeparatesGitWireLfsAndObjects() {
    assertEquals("1.0 KB git wire · 12 objects",
        GitSyncEngine.formatTransferVolume(1024, 0, 12));
    assertEquals("2.0 KB git wire + 1.0 KB LFS · 3 objects",
        GitSyncEngine.formatTransferVolume(2048, 1024, 3));
    assertEquals("0 objects", GitSyncEngine.formatTransferVolume(0, 0, 0));
  }

  private static final class StubHttpConnectionFactory implements HttpConnectionFactory {
    @Override
    public HttpConnection create(URL url) {
      return new StubHttpConnection();
    }

    @Override
    public HttpConnection create(URL url, Proxy proxy) {
      return new StubHttpConnection();
    }
  }

  private static final class StubHttpConnection implements HttpConnection {
  private final byte[] body = new byte[256];

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(body);
    }

    @Override
    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public int getResponseCode() {
      return HTTP_OK;
    }

    @Override
    public URL getURL() {
      try {
        return URI.create("https://example.com/repo.git").toURL();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public String getResponseMessage() {
      return "OK";
    }

    @Override
    public Map<String, List<String>> getHeaderFields() {
      return Map.of();
    }

    @Override
    public void setRequestProperty(String key, String value) {
    }

    @Override
    public void setRequestMethod(String method) {
    }

    @Override
    public void setUseCaches(boolean useCaches) {
    }

    @Override
    public void setConnectTimeout(int timeout) {
    }

    @Override
    public void setReadTimeout(int timeout) {
    }

    @Override
    public String getContentType() {
      return "application/x-git-upload-pack-result";
    }

    @Override
    public String getHeaderField(String name) {
      return null;
    }

    @Override
    public List<String> getHeaderFields(String name) {
      return List.of();
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public void setInstanceFollowRedirects(boolean followRedirects) {
    }

    @Override
    public void setDoOutput(boolean doOutput) {
    }

    @Override
    public void setFixedLengthStreamingMode(int contentLength) {
    }

    @Override
    public void setChunkedStreamingMode(int chunklen) {
    }

    @Override
    public String getRequestMethod() {
      return "POST";
    }

    @Override
    public boolean usingProxy() {
      return false;
    }

    @Override
    public void connect() throws IOException {
    }

    @Override
    public void configure(KeyManager[] km, TrustManager[] tm, SecureRandom random)
        throws NoSuchAlgorithmException, KeyManagementException {
    }

    @Override
    public void setHostnameVerifier(HostnameVerifier hostnameVerifier)
        throws NoSuchAlgorithmException, KeyManagementException {
    }
  }
}
