import java.net.HttpURLConnection;
import java.net.URI;

final class HealthCheck {
    private HealthCheck() {}

    public static void main(String[] args) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create("http://127.0.0.1:1080/mockserver/status").toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        // MockServer 5.15 has no dedicated GET readiness endpoint. Any non-5xx
        // 响应可证明 Netty 已绑定端口并能够处理请求。
        if (connection.getResponseCode() >= 500) {
            throw new IllegalStateException("MockServer is not ready");
        }
    }
}
