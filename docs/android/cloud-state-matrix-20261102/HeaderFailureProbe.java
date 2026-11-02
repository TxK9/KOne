import java.net.HttpURLConnection;
import java.net.URL;
public class HeaderFailureProbe {
    public static void main(String[] args) throws Exception {
        String credential = "synthetic\r\nX-Test: value";
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:1").openConnection();
        try {
            connection.setRequestProperty("Authorization", "Bearer " + credential);
            throw new AssertionError("Expected header rejection");
        } catch (IllegalArgumentException failure) {
            boolean reflected = failure.getMessage().contains(credential);
            if (!reflected) throw new AssertionError("Expected synthetic credential in JDK argument failure");
            System.out.println("PASS JDK rejects header before connect; exception message contains synthetic credential: " + reflected);
        } finally { connection.disconnect(); }
    }
}
