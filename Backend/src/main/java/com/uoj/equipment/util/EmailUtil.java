package com.uoj.equipment.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component
public class EmailUtil {

    @Value("${brevo.api.key}")
    private String brevoApiKey;

    @Value("${app.mail.from}")
    private String fromEmail;

    @Value("${app.mail.from-name:ERMS}")
    private String fromName;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public void sendHtmlEmail(String to, String subject, String htmlBody) {
        try {
            String json = "{"
                    + "\"sender\":{\"name\":\"" + escapeJson(fromName) + "\",\"email\":\"" + escapeJson(fromEmail) + "\"},"
                    + "\"to\":[{\"email\":\"" + escapeJson(to) + "\"}],"
                    + "\"subject\":\"" + escapeJson(subject) + "\","
                    + "\"htmlContent\":\"" + escapeJson(htmlBody) + "\""
                    + "}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.brevo.com/v3/smtp/email"))
                    .header("Content-Type", "application/json")
                    .header("api-key", brevoApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 201) {
                throw new RuntimeException(
                        "Brevo API error " + response.statusCode() + ": " + response.body());
            }

            System.out.println("[EmailUtil] Email sent to " + to
                    + " | status=" + response.statusCode());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to send email to: " + to, e);
        }
    }

    public void sendTextEmail(String to, String subject, String textBody) {
        String html = "<pre style='font-family: Arial, sans-serif;'>" + escapeHtml(textBody) + "</pre>";
        sendHtmlEmail(to, subject, html);
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}