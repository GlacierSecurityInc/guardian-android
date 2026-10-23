package com.glaciersecurity.glaciermessenger.http;

import static com.glaciersecurity.glaciermessenger.utils.Random.SECURE_RANDOM;

import android.content.Context;
import android.os.Build;

import com.glaciersecurity.glaciermessenger.BuildConfig;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.crypto.TrustManagers;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.services.AbstractConnectionManager;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;
import com.glaciersecurity.glaciermessenger.utils.TLSSocketFactory;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;

import org.apache.http.conn.ssl.StrictHostnameVerifier;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

public class HttpConnectionManager extends AbstractConnectionManager {

    private final List<HttpDownloadConnection> downloadConnections = new ArrayList<>();
    private final List<HttpUploadConnection> uploadConnections = new ArrayList<>();

    public static final Executor EXECUTOR = Executors.newFixedThreadPool(4);

    private static final OkHttpClient OK_HTTP_CLIENT = OkHttpClientProvider.getOkHttpClient();


    public static String getUserAgent() {
        return String.format("%s/%s", BuildConfig.APP_NAME, BuildConfig.VERSION_NAME);
    }

    public HttpConnectionManager(XmppConnectionService service) {
        super(service);
    }

    public void createNewDownloadConnection(Message message) {
        this.createNewDownloadConnection(message, false);
    }

    public void createNewDownloadConnection(final Message message, boolean interactive) {
        synchronized (this.downloadConnections) {
            for (HttpDownloadConnection connection : this.downloadConnections) {
                if (connection.getMessage() == message) {
                    return;
                }
            }
            final HttpDownloadConnection connection = new HttpDownloadConnection(message, this);
            connection.init(interactive);
            this.downloadConnections.add(connection);
        }
    }

    public void createNewUploadConnection(final Message message, boolean delay) {
        synchronized (this.uploadConnections) {
            for (HttpUploadConnection connection : this.uploadConnections) {
                if (connection.getMessage() == message) {
                    return;
                }
            }
            HttpUploadConnection connection = new HttpUploadConnection(message, Method.determine(message.getConversation().getAccount()), this);
            connection.init(delay);
            this.uploadConnections.add(connection);
        }
    }

    void finishConnection(HttpDownloadConnection connection) {
        synchronized (this.downloadConnections) {
            this.downloadConnections.remove(connection);
        }
    }

    void finishUploadConnection(HttpUploadConnection httpUploadConnection) {
        synchronized (this.uploadConnections) {
            this.uploadConnections.remove(httpUploadConnection);
        }
    }

    OkHttpClient buildHttpClient() {
        return buildHttpClient(30);
    }

    OkHttpClient buildHttpClient(int readTimeout) {
        final OkHttpClient.Builder builder = OK_HTTP_CLIENT.newBuilder();
        builder.writeTimeout(30, TimeUnit.SECONDS);
        builder.readTimeout(readTimeout, TimeUnit.SECONDS);
        setupTrustManager(builder);
        return builder.build();
    }

    private void setupTrustManager(final OkHttpClient.Builder builder) {
        final X509TrustManager trustManager =
                mXmppConnectionService.getMemorizingTrustManager().getNonInteractive();
        try {
            final SSLSocketFactory sf = new TLSSocketFactory(new X509TrustManager[]{trustManager}, SECURE_RANDOM);
            builder.sslSocketFactory(sf, trustManager);
            builder.hostnameVerifier(new StrictHostnameVerifier());
        } catch (final KeyManagementException | NoSuchAlgorithmException ignored) {
        }
    }

    public static InputStream open(final String url) throws IOException {
        return open(HttpUrl.get(url));
    }

    public static InputStream open(final HttpUrl httpUrl) throws IOException {
        final OkHttpClient.Builder builder = OK_HTTP_CLIENT.newBuilder();
        final OkHttpClient client = builder.build();
        final Request request = new Request.Builder().get().url(httpUrl).build();
        final ResponseBody body = client.newCall(request).execute().body();
        if (body == null) {
            throw new IOException("No response body found");
        }
        return body.byteStream();
    }


    public static OkHttpClient okHttpClient(final Context context) {
        final OkHttpClient.Builder builder = HttpConnectionManager.OK_HTTP_CLIENT.newBuilder();
        try {
            final X509TrustManager trustManager;
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
                trustManager = TrustManagers.defaultWithBundledLetsEncrypt(context);
            } else {
                trustManager = TrustManagers.createDefaultTrustManager();
            }
            final SSLSocketFactory socketFactory =
                    new TLSSocketFactory(new X509TrustManager[] {trustManager}, SECURE_RANDOM);
            builder.sslSocketFactory(socketFactory, trustManager);
        } catch (final IOException
                       | KeyManagementException
                       | NoSuchAlgorithmException
                       | KeyStoreException
                       | CertificateException e) {
            throw new RuntimeException(e);
        }
        return builder.build();
    }
}
