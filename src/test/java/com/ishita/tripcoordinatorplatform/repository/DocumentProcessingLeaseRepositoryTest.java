package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.model.Trip;
import com.ishita.tripcoordinatorplatform.model.TripType;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

// Run only against an explicitly supplied disposable PostgreSQL database.
@EnabledIfEnvironmentVariable(named = "DOCUMENT_LEASE_TEST_JDBC_URL", matches = ".+")
class DocumentProcessingLeaseRepositoryTest {

    private static SessionFactory factory;
    private final Instant now = Instant.parse("2026-10-06T10:00:00Z");

    @BeforeAll
    static void createSchema() {
        factory = new Configuration()
                .addAnnotatedClass(TravelDocument.class)
                .addAnnotatedClass(Trip.class)
                .setProperty("hibernate.connection.url", System.getenv("DOCUMENT_LEASE_TEST_JDBC_URL"))
                .setProperty("hibernate.connection.username", "lease_test")
                .setProperty("hibernate.connection.password", "lease_test")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
    }

    @AfterAll
    static void closeSchema() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void queuedClaimUpdatesStatusAndTimestampAndSecondClaimLoses() {
        Long id = document(TravelDocumentProcessingStatus.QUEUED, null);

        assertEquals(1, this.<Integer>repository(repo -> repo.claimQueuedForProcessing(id, now)));
        assertEquals(0, this.<Integer>repository(repo -> repo.claimQueuedForProcessing(id, now.plusSeconds(1))));
        assertEquals(0, this.<Integer>repository(repo -> repo.reclaimStaleProcessing(id, now, now.minusSeconds(120))));
        var stored = repository(repo -> repo.findById(id).orElseThrow());
        assertEquals(TravelDocumentProcessingStatus.PROCESSING, stored.getProcessingStatus());
        assertEquals(now, stored.getProcessingStartedAt());
    }

    @Test
    void staleBoundaryIsInclusiveAndReclaimRefreshesTimestamp() {
        Long id = document(TravelDocumentProcessingStatus.PROCESSING, now.minusSeconds(120));

        assertEquals(1, this.<Integer>repository(repo -> repo.reclaimStaleProcessing(id, now, now.minusSeconds(120))));
        assertEquals(0, this.<Integer>repository(repo -> repo.reclaimStaleProcessing(id, now.plusSeconds(1), now.minusSeconds(119))));
        assertEquals(now, repository(repo -> repo.findById(id).orElseThrow().getProcessingStartedAt()));
    }

    @Test
    void processingWithoutTimestampIsNotReclaimed() {
        Long id = document(TravelDocumentProcessingStatus.PROCESSING, null);

        assertEquals(0, this.<Integer>repository(repo -> repo.reclaimStaleProcessing(id, now, now.minusSeconds(120))));
    }

    @ParameterizedTest
    @EnumSource(value = TravelDocumentProcessingStatus.class, names = {"UPLOADED", "REVIEW_REQUIRED", "PROCESSED", "FAILED"})
    void otherStatesCannotBeClaimedOrReclaimed(TravelDocumentProcessingStatus status) {
        Long id = document(status, null);

        assertEquals(0, this.<Integer>repository(repo -> repo.claimQueuedForProcessing(id, now)));
        assertEquals(0, this.<Integer>repository(repo -> repo.reclaimStaleProcessing(id, now, now.minusSeconds(120))));
    }

    @Test
    void cleanupClearsCompletedLeaseButPreservesActiveOwnership() {
        Long completed = document(TravelDocumentProcessingStatus.REVIEW_REQUIRED, now);
        Long active = document(TravelDocumentProcessingStatus.PROCESSING, now);

        assertEquals(1, this.<Integer>repository(repo -> repo.clearCompletedProcessingLease(completed)));
        assertEquals(0, this.<Integer>repository(repo -> repo.clearCompletedProcessingLease(active)));
        assertNull(repository(repo -> repo.findById(completed).orElseThrow().getProcessingStartedAt()));
        assertEquals(now, repository(repo -> repo.findById(active).orElseThrow().getProcessingStartedAt()));
    }

    @Test
    void twoConcurrentQueuedClaimsHaveExactlyOneWinner() throws Exception {
        Long id = document(TravelDocumentProcessingStatus.QUEUED, null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return repository(repo -> repo.claimQueuedForProcessing(id, now));
            });
            var second = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return repository(repo -> repo.claimQueuedForProcessing(id, now));
            });
            start.countDown();

            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
        }
    }

    private Long document(TravelDocumentProcessingStatus status, Instant startedAt) {
        try (EntityManager entityManager = factory.createEntityManager()) {
            entityManager.getTransaction().begin();
            Trip trip = new Trip();
            trip.setName("Lease test");
            trip.setStartDate(LocalDate.of(2026, 12, 10));
            trip.setEndDate(LocalDate.of(2026, 12, 11));
            trip.setTripType(TripType.values()[0]);
            entityManager.persist(trip);
            TravelDocument document = new TravelDocument();
            document.setTrip(trip);
            document.setOriginalFileName("test.pdf");
            document.setStorageKey("trips/test/documents/test.pdf");
            document.setContentType("application/pdf");
            document.setUploadedAt(now);
            document.setProcessingStatus(status);
            document.setProcessingStartedAt(startedAt);
            entityManager.persist(document);
            entityManager.getTransaction().commit();
            return document.getId();
        }
    }

    private <T> T repository(Function<TravelDocumentRepository, T> action) {
        try (EntityManager entityManager = factory.createEntityManager()) {
            entityManager.getTransaction().begin();
            try {
                var repository = new JpaRepositoryFactory(entityManager).getRepository(TravelDocumentRepository.class);
                T result = action.apply(repository);
                entityManager.getTransaction().commit();
                return result;
            } catch (RuntimeException exception) {
                entityManager.getTransaction().rollback();
                throw exception;
            }
        }
    }
}
