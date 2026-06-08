package com.ebanking.transaction.kafka;

import com.ebanking.transaction.domain.Transaction;
import com.ebanking.transaction.domain.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Kafka consumer that projects transaction events into the PostgreSQL read model.
 *
 * Scaling note: one replica per Kafka partition is the ceiling — set the deployment
 * replica count to match the topic's partition count.  The API layer scales
 * independently based on HTTP traffic.
 */
@Component
public class TransactionProjector {

    private static final Logger log = LoggerFactory.getLogger(TransactionProjector.class);

    private final TransactionRepository repository;

    public TransactionProjector(TransactionRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(
        topics     = "${app.kafka.topic}",
        groupId    = "${spring.kafka.consumer.group-id}",
        concurrency = "${app.kafka.concurrency:3}"
    )
    @Transactional
    public void onTransaction(
            @Payload TransactionEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC)  String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        if (event.id() == null || event.customerId() == null || event.amount() == null) {
            log.warn("Dropping malformed event: topic={} partition={} offset={}",
                    topic, partition, offset);
            return;
        }

        LocalDate valueDate;
        try {
            valueDate = LocalDate.parse(event.valueDate());
        } catch (DateTimeParseException e) {
            log.warn("Dropping event with unparseable valueDate='{}': offset={}", event.valueDate(), offset);
            return;
        }

        Transaction entity = new Transaction(
                event.id(),
                event.customerId(),
                event.amount(),
                event.currency(),
                event.iban(),
                valueDate,
                event.description() != null ? event.description() : ""
        );

        // JpaRepository.save() issues INSERT or UPDATE (upsert semantics via merge).
        // Idempotent: replaying the topic from offset 0 produces the same read model.
        repository.save(entity);

        log.debug("Projected transaction id={} customerId={} partition={} offset={}",
                event.id(), event.customerId(), partition, offset);
    }
}
