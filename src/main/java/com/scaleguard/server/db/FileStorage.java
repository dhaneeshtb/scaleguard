package com.scaleguard.server.db;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;

public class FileStorage {

    static {
        try {
            createTable();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void createTable() throws Exception {
        try (Connection conn = ConnectionUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            String sql = "CREATE TABLE IF NOT EXISTS cert_files (\n" +
                    "                            id SERIAL PRIMARY KEY,\n" +
                    "                            certificate_id TEXT,\n" +
                    "                            filename TEXT,\n" +
                    "                            data TEXT\n" +
                    "                        )";
            if (!ConnectionUtil.isPostgres()) {
                sql = "CREATE TABLE IF NOT EXISTS cert_files (\n" +
                        "                            id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                        "                            certificate_id TEXT,\n" +
                        "                            filename TEXT,\n" +
                        "                            data TEXT\n" +
                        "                        )";
            }
            stmt.execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static void storeFileAsString(String certificateId, String filename, String content) {
        String sql = "INSERT INTO cert_files (certificate_id, filename, data) VALUES (?, ?, ?)";
        try (Connection conn = ConnectionUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, certificateId);
            pstmt.setString(2, filename);
            pstmt.setString(3, content);
            pstmt.executeUpdate();
            System.out.println("File stored successfully as String in db");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String retrieveFileAsString(String certificateId,String filename) {
        String sql = "SELECT data FROM cert_files WHERE certificate_id = ? and filename=?";
        try (Connection conn = ConnectionUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, certificateId);
            pstmt.setString(2, filename);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("data");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void main(String[] args) {
        storeFileAsString( "4c5a0c7e-0313-4113-9d3c-ade234f96106", "server.crt", "-----BEGIN CERTIFICATE-----\n" +
                "MIIFJDCCBAygAwIBAgISBt6stwSXBHtHu95cGLU8k+EFMA0GCSqGSIb3DQEBCwUA\n" +
                "MDMxCzAJBgNVBAYTAlVTMRYwFAYDVQQKEw1MZXQncyBFbmNyeXB0MQwwCgYDVQQD\n" +
                "EwNSMTEwHhcNMjUwMzI1MTEwMTU1WhcNMjUwNjIzMTEwMTU0WjAdMRswGQYDVQQD\n" +
                "ExJhcGkuZW5qb3lwYWRhaS5jb20wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEK\n" +
                "AoIBAQCpGT+dzBIfMg1pm3noeUgQA8K93Gvp4+WzRBbDbX92/vtRKNKqp/lzyBRy\n" +
                "i5aiFSWuue064vPPoGn0zbjAFHPRsDN9gqyps3e5YG1XvVxeCP1dZpjuSUBTgLNi\n" +
                "0czLSOqSjJuFCyrhaQvrXi8qQttlzEZkpMY15XvR8Z1Ya851xU2dlV2MPL0BD0vv\n" +
                "feDPa/f9Mrax1dC7cfWbe4nlZ0Cdv3y9jhrjlxwODA0YGCHOMOhpNv2gygQyVlTr\n" +
                "hna1hxbJVJlctMN3T5UOlCjP4WDFSDGp9eo6/jveDq/RFvo8/d6fd2RwF50uI0/A\n" +
                "wNDtcJFeThYBMoTUpI5cvtJNWVwdAgMBAAGjggJGMIICQjAOBgNVHQ8BAf8EBAMC\n" +
                "BaAwHQYDVR0lBBYwFAYIKwYBBQUHAwEGCCsGAQUFBwMCMAwGA1UdEwEB/wQCMAAw\n" +
                "HQYDVR0OBBYEFEPds0I0GF5H3t057ijBEfaePF2vMB8GA1UdIwQYMBaAFMXPRqTq\n" +
                "9MPAemyVxC2wXpIvJuO5MFcGCCsGAQUFBwEBBEswSTAiBggrBgEFBQcwAYYWaHR0\n" +
                "cDovL3IxMS5vLmxlbmNyLm9yZzAjBggrBgEFBQcwAoYXaHR0cDovL3IxMS5pLmxl\n" +
                "bmNyLm9yZy8wHQYDVR0RBBYwFIISYXBpLmVuam95cGFkYWkuY29tMBMGA1UdIAQM\n" +
                "MAowCAYGZ4EMAQIBMC0GA1UdHwQmMCQwIqAgoB6GHGh0dHA6Ly9yMTEuYy5sZW5j\n" +
                "ci5vcmcvNi5jcmwwggEFBgorBgEEAdZ5AgQCBIH2BIHzAPEAdwDM+w9qhXEJZf6V\n" +
                "m1PO6bJ8IumFXA2XjbapflTA/kwNsAAAAZXNLONFAAAEAwBIMEYCIQCekaLpPD/r\n" +
                "LIxrQZf50M3tsTQeRr8NMZAC5hWuXXOylAIhAP3mrxkcX5K+fbmf1Uw0ZvWI+kni\n" +
                "t9kCu0fspCpZ8RW/AHYAouMK5EXvva2bfjjtR2d3U9eCW4SU1yteGyzEuVCkR+cA\n" +
                "AAGVzSzrGgAABAMARzBFAiEAk4hOBCNuBoXS3yUB5K9LL0VUpwdMf5uq0NVrbPw5\n" +
                "rnACIGCaKW09t5U0yCdCWKVrH9j+Yt2zOIQW/znExFZLMiNKMA0GCSqGSIb3DQEB\n" +
                "CwUAA4IBAQAj5f9MVDcRfucdGojBkUyg6HTv8E6EOBWj2zKbGpZtr6KwGX6D/LYx\n" +
                "LDY95oFIe3e8FyVt2QaelP3sa3SZWTtQVAiUgk1EzB7a0X5UAJf2v8jP/UdMOqqd\n" +
                "ReVkQwm0JE/g1fVhNTFlwFsACRAZTFx7MHtHZu9tzubYR/TvuTdP2smNuqHXpx+u\n" +
                "3COB3/8a2fgQxIWQ4vzIkiJvRTS/HLESS+fz1+Hi3QC9ySHyeQdvA9uZNbgcQ9mF\n" +
                "5IxN3rcgebrfXVsAF0H3XvBRhKfVj8pSauFz12feayAhlHfFUE7OdSLA6VqkZoBr\n" +
                "Ik5bf9z0YzNXqd3hCvC4vRkMFR5JdVdi\n" +
                "-----END CERTIFICATE-----\n" +
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIFBjCCAu6gAwIBAgIRAIp9PhPWLzDvI4a9KQdrNPgwDQYJKoZIhvcNAQELBQAw\n" +
                "TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh\n" +
                "cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMjQwMzEzMDAwMDAw\n" +
                "WhcNMjcwMzEyMjM1OTU5WjAzMQswCQYDVQQGEwJVUzEWMBQGA1UEChMNTGV0J3Mg\n" +
                "RW5jcnlwdDEMMAoGA1UEAxMDUjExMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIB\n" +
                "CgKCAQEAuoe8XBsAOcvKCs3UZxD5ATylTqVhyybKUvsVAbe5KPUoHu0nsyQYOWcJ\n" +
                "DAjs4DqwO3cOvfPlOVRBDE6uQdaZdN5R2+97/1i9qLcT9t4x1fJyyXJqC4N0lZxG\n" +
                "AGQUmfOx2SLZzaiSqhwmej/+71gFewiVgdtxD4774zEJuwm+UE1fj5F2PVqdnoPy\n" +
                "6cRms+EGZkNIGIBloDcYmpuEMpexsr3E+BUAnSeI++JjF5ZsmydnS8TbKF5pwnnw\n" +
                "SVzgJFDhxLyhBax7QG0AtMJBP6dYuC/FXJuluwme8f7rsIU5/agK70XEeOtlKsLP\n" +
                "Xzze41xNG/cLJyuqC0J3U095ah2H2QIDAQABo4H4MIH1MA4GA1UdDwEB/wQEAwIB\n" +
                "hjAdBgNVHSUEFjAUBggrBgEFBQcDAgYIKwYBBQUHAwEwEgYDVR0TAQH/BAgwBgEB\n" +
                "/wIBADAdBgNVHQ4EFgQUxc9GpOr0w8B6bJXELbBeki8m47kwHwYDVR0jBBgwFoAU\n" +
                "ebRZ5nu25eQBc4AIiMgaWPbpm24wMgYIKwYBBQUHAQEEJjAkMCIGCCsGAQUFBzAC\n" +
                "hhZodHRwOi8veDEuaS5sZW5jci5vcmcvMBMGA1UdIAQMMAowCAYGZ4EMAQIBMCcG\n" +
                "A1UdHwQgMB4wHKAaoBiGFmh0dHA6Ly94MS5jLmxlbmNyLm9yZy8wDQYJKoZIhvcN\n" +
                "AQELBQADggIBAE7iiV0KAxyQOND1H/lxXPjDj7I3iHpvsCUf7b632IYGjukJhM1y\n" +
                "v4Hz/MrPU0jtvfZpQtSlET41yBOykh0FX+ou1Nj4ScOt9ZmWnO8m2OG0JAtIIE38\n" +
                "01S0qcYhyOE2G/93ZCkXufBL713qzXnQv5C/viOykNpKqUgxdKlEC+Hi9i2DcaR1\n" +
                "e9KUwQUZRhy5j/PEdEglKg3l9dtD4tuTm7kZtB8v32oOjzHTYw+7KdzdZiw/sBtn\n" +
                "UfhBPORNuay4pJxmY/WrhSMdzFO2q3Gu3MUBcdo27goYKjL9CTF8j/Zz55yctUoV\n" +
                "aneCWs/ajUX+HypkBTA+c8LGDLnWO2NKq0YD/pnARkAnYGPfUDoHR9gVSp/qRx+Z\n" +
                "WghiDLZsMwhN1zjtSC0uBWiugF3vTNzYIEFfaPG7Ws3jDrAMMYebQ95JQ+HIBD/R\n" +
                "PBuHRTBpqKlyDnkSHDHYPiNX3adPoPAcgdF3H2/W0rmoswMWgTlLn1Wu0mrks7/q\n" +
                "pdWfS6PJ1jty80r2VKsM/Dj3YIDfbjXKdaFU5C+8bhfJGqU3taKauuz0wHVGT3eo\n" +
                "6FlWkWYtbt4pgdamlwVeZEW+LM7qZEJEsMNPrfC03APKmZsJgpWCDWOKZvkZcvjV\n" +
                "uYkQ4omYCTX5ohy+knMjdOmdH9c7SpqEWBDC86fiNex+O0XOMEZSa8DA\n" +
                "-----END CERTIFICATE-----\n");
        String fileContent = retrieveFileAsString( "4c5a0c7e-0313-4113-9d3c-ade234f96106","server.crt");
        if (fileContent != null) {
            System.out.println("Retrieved File Content:\n" + fileContent);
        } else {
            System.out.println("File not found.");
        }
    }
}
