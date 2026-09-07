package com.gitutility.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.HttpConnectionFactory2;
import org.eclipse.jgit.transport.http.JDKHttpConnection;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts bytes read/written on JGit smart-HTTP connections for the current thread.
 * Installs a delegating {@link HttpConnectionFactory} once; nested {@link #open()} scopes stack.
 */
@Slf4j
public final class GitWireByteMeter implements AutoCloseable {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static volatile HttpConnectionFactory delegateFactory = new JDKHttpConnectionFactory();

    private static final ThreadLocal<GitWireByteMeter> ACTIVE = new ThreadLocal<>();

    private final AtomicLong readBytes = new AtomicLong();
    private final AtomicLong writeBytes = new AtomicLong();
    private final GitWireByteMeter previous;

    private GitWireByteMeter() {
        previous = ACTIVE.get();
        ACTIVE.set(this);
    }

    public static GitWireByteMeter open() {
        installIfNeeded();
        return new GitWireByteMeter();
    }

    public long gitWireBytes() {
        return readBytes.get() + writeBytes.get();
    }

    public long readBytes() {
        return readBytes.get();
    }

    public long writeBytes() {
        return writeBytes.get();
    }

    @Override
    public void close() {
        if (previous != null) {
            ACTIVE.set(previous);
        } else {
            ACTIVE.remove();
        }
    }

    private static void installIfNeeded() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        HttpConnectionFactory current = HttpTransport.getConnectionFactory();
        if (current != null && !(current instanceof MeteringHttpConnectionFactory)) {
            delegateFactory = current;
        }
        HttpTransport.setConnectionFactory(new MeteringHttpConnectionFactory(delegateFactory));
        log.debug("Installed JGit HTTP byte metering factory");
    }

    /** Test hook: reset global factory state between tests. */
    static void resetForTests() {
        INSTALLED.set(false);
        delegateFactory = new JDKHttpConnectionFactory();
        ACTIVE.remove();
    }

    private static GitWireByteMeter current() {
        return ACTIVE.get();
    }

    private void recordRead(long bytes) {
        if (bytes > 0) {
            readBytes.addAndGet(bytes);
        }
    }

    private void recordWrite(long bytes) {
        if (bytes > 0) {
            writeBytes.addAndGet(bytes);
        }
    }

    private static HttpConnection wrapNonJdk(HttpConnection connection) {
        GitWireByteMeter meter = current();
        if (meter == null || connection == null || connection instanceof JDKHttpConnection) {
            return connection;
        }
        return new CountingHttpConnection(connection, meter);
    }

    private static boolean useJdkMetering(HttpConnectionFactory delegate) {
        return current() != null && delegate instanceof JDKHttpConnectionFactory;
    }

    private static final class MeteringHttpConnectionFactory implements HttpConnectionFactory2 {
        private final HttpConnectionFactory delegate;

        private MeteringHttpConnectionFactory(HttpConnectionFactory delegate) {
            this.delegate = delegate != null ? delegate : new JDKHttpConnectionFactory();
        }

        @Override
        public HttpConnection create(URL url) throws IOException {
            if (useJdkMetering(delegate)) {
                return new MeteringJdkHttpConnection(url, current());
            }
            return wrapNonJdk(delegate.create(url));
        }

        @Override
        public HttpConnection create(URL url, Proxy proxy) throws IOException {
            if (useJdkMetering(delegate)) {
                return new MeteringJdkHttpConnection(url, proxy, current());
            }
            return wrapNonJdk(delegate.create(url, proxy));
        }

        @Override
        public GitSession newSession() {
            if (delegate instanceof HttpConnectionFactory2 factory2) {
                GitSession inner = factory2.newSession();
                return new GitSession() {
                    @Override
                    public HttpConnection configure(HttpConnection connection, boolean sslVerify)
                            throws IOException, GeneralSecurityException {
                        // JGit configure() requires JDKHttpConnection — do not wrap before this call.
                        return inner.configure(connection, sslVerify);
                    }

                    @Override
                    public void close() {
                        inner.close();
                    }
                };
            }
            throw new UnsupportedOperationException("HTTP connection factory does not support sessions");
        }
    }

    /**
     * Subclass of {@link JDKHttpConnection} so JGit's session {@code configure()} accepts the type
     * while still counting wire bytes on stream I/O.
     */
    private static final class MeteringJdkHttpConnection extends JDKHttpConnection {
        private final GitWireByteMeter meter;

        MeteringJdkHttpConnection(URL url, GitWireByteMeter meter) throws IOException {
            super(url);
            this.meter = meter;
        }

        MeteringJdkHttpConnection(URL url, Proxy proxy, GitWireByteMeter meter) throws IOException {
            super(url, proxy);
            this.meter = meter;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return meter.wrapInput(super.getInputStream());
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return meter.wrapOutput(super.getOutputStream());
        }
    }

    private InputStream wrapInput(InputStream in) {
        return new FilterInputStream(in) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) {
                    recordRead(n);
                }
                return n;
            }

            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) {
                    recordRead(1);
                }
                return b;
            }
        };
    }

    private OutputStream wrapOutput(OutputStream out) {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                out.write(b);
                recordWrite(1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
                recordWrite(len);
            }

            @Override
            public void flush() throws IOException {
                out.flush();
            }

            @Override
            public void close() throws IOException {
                out.close();
            }
        };
    }

    /** Fallback for non-JDK connection factories (tests, custom transports). */
    private static final class CountingHttpConnection implements HttpConnection {
        private final HttpConnection delegate;
        private final GitWireByteMeter meter;

        private CountingHttpConnection(HttpConnection delegate, GitWireByteMeter meter) {
            this.delegate = delegate;
            this.meter = meter;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return meter.wrapInput(delegate.getInputStream());
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return meter.wrapOutput(delegate.getOutputStream());
        }

        @Override
        public int getResponseCode() throws IOException {
            return delegate.getResponseCode();
        }

        @Override
        public URL getURL() {
            return delegate.getURL();
        }

        @Override
        public String getResponseMessage() throws IOException {
            return delegate.getResponseMessage();
        }

        @Override
        public java.util.Map<String, java.util.List<String>> getHeaderFields() {
            return delegate.getHeaderFields();
        }

        @Override
        public void setRequestProperty(String key, String value) {
            delegate.setRequestProperty(key, value);
        }

        @Override
        public void setRequestMethod(String method) throws java.net.ProtocolException {
            delegate.setRequestMethod(method);
        }

        @Override
        public void setUseCaches(boolean useCaches) {
            delegate.setUseCaches(useCaches);
        }

        @Override
        public void setConnectTimeout(int timeout) {
            delegate.setConnectTimeout(timeout);
        }

        @Override
        public void setReadTimeout(int timeout) {
            delegate.setReadTimeout(timeout);
        }

        @Override
        public String getContentType() {
            return delegate.getContentType();
        }

        @Override
        public String getHeaderField(String name) {
            return delegate.getHeaderField(name);
        }

        @Override
        public java.util.List<String> getHeaderFields(String name) {
            return delegate.getHeaderFields(name);
        }

        @Override
        public int getContentLength() {
            return delegate.getContentLength();
        }

        @Override
        public void setInstanceFollowRedirects(boolean followRedirects) {
            delegate.setInstanceFollowRedirects(followRedirects);
        }

        @Override
        public void setDoOutput(boolean doOutput) {
            delegate.setDoOutput(doOutput);
        }

        @Override
        public void setFixedLengthStreamingMode(int contentLength) {
            delegate.setFixedLengthStreamingMode(contentLength);
        }

        @Override
        public void setChunkedStreamingMode(int chunklen) {
            delegate.setChunkedStreamingMode(chunklen);
        }

        @Override
        public String getRequestMethod() {
            return delegate.getRequestMethod();
        }

        @Override
        public boolean usingProxy() {
            return delegate.usingProxy();
        }

        @Override
        public void connect() throws IOException {
            delegate.connect();
        }

        @Override
        public void configure(javax.net.ssl.KeyManager[] km, javax.net.ssl.TrustManager[] tm,
                              java.security.SecureRandom random)
                throws java.security.NoSuchAlgorithmException, java.security.KeyManagementException {
            delegate.configure(km, tm, random);
        }

        @Override
        public void setHostnameVerifier(javax.net.ssl.HostnameVerifier hostnameVerifier)
                throws java.security.NoSuchAlgorithmException, java.security.KeyManagementException {
            delegate.setHostnameVerifier(hostnameVerifier);
        }
    }
}
