package com.ebanking.transaction;

import com.ebanking.transaction.dto.TransactionPageResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — spins up real Kafka and real PostgreSQL via Testcontainers.
 *
 * Full pipeline under test:
 *   publish JSON event to Kafka topic
 *     → TransactionProjector consumes and writes to Postgres
 *     → REST endpoint reads from Postgres and returns converted totals
 *
 * The only fake is the external FX provider (WireMock), since it is a genuine
 * third-party dependency that we cannot and should not bring up in CI.
 *
 * Eventual consistency: the read-model lags the Kafka topic by the consumer
 * poll interval, so assertions use Awaitility rather than Thread.sleep or
 * immediate assertions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("integration-test")
@Import(TestSecurityConfig.class)
class TransactionFlowIT {

    @Container
    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        // Stub FX rate: 1 GBP = 1.18 CHF on any date.
        wireMock.stubFor(get(urlPathMatching("/.*"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"rates": {"CHF": 1.18}}
                                """)));
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Point FX client at WireMock; resolved after wireMock.start() via lambda.
        registry.add("app.fx.base-url", () -> "http://localhost:" + wireMock.port());
        registry.add("app.kafka.topic", () -> "transactions");
        // Disable real JWT validation — TestSecurityConfig permits all requests.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://localhost:" + wireMock.port() + "/auth");
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void publishedTransactionIsProjectedAndServedWithTotals() throws Exception {
        // ── 1. Publish a GBP credit event to the Kafka topic ──────────────
        publishToKafka("""
                {
                  "id": "it-tx-001",
                  "customerId": "P-IT-0000000001",
                  "amount": "100.00",
                  "currency": "GBP",
                  "iban": "GB00BARC20201530093459",
                  "valueDate": "2020-10-01",
                  "description": "Integration test credit"
                }
                """);

        // ── 2. Poll until the projector has written to Postgres ───────────
        // Awaitility embraces eventual consistency — the consumer poll interval
        // means the row may not appear immediately after publish.
        Awaitility.await()
                .atMost(20, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // ── 3. Call the REST endpoint with a JWT for the same customer ──
                    HttpHeaders headers = new HttpHeaders();
                    // In a real setup supply a signed JWT; here we override security
                    // in the test application context to permit all for the IT profile.
                    headers.set(HttpHeaders.AUTHORIZATION, "Bearer test-token");

                    ResponseEntity<TransactionPageResponse> response = restTemplate.exchange(
                            "/api/v1/transactions?year=2020&month=10&currency=CHF",
                            HttpMethod.GET,
                            new HttpEntity<>(headers),
                            TransactionPageResponse.class);

                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody()).isNotNull();
                    assertThat(response.getBody().content()).hasSize(1);
                    assertThat(response.getBody().totals().currency()).isEqualTo("CHF");
                    // 100 GBP × 1.18 = 118.00 CHF
                    assertThat(response.getBody().totals().totalCredit()).isEqualTo("118.00");
                });
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static void publishToKafka(String json) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("transactions", "it-tx-001", json)).get();
        }
    }
}
