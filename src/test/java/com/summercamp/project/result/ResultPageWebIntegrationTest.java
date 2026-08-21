package com.summercamp.project.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "bot.enabled=false",
            "result-page.public-base-url=http://127.0.0.1"
        })
class ResultPageWebIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ResultPageService resultPageService;

    @Test
    void servesTheCreatedCalculationResultOverHttp() throws Exception {
        CalculationResultPage page = resultPageService.create(
                "计算结果", "125 * 36", "4500");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/results/" + page.id()))
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow()
                .startsWith("text/html"));
        assertTrue(response.body().contains("125 * 36"));
        assertTrue(response.body().contains("4500"));
    }
}
