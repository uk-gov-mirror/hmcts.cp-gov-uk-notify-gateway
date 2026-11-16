package uk.gov.hmcts.cp.notification.logging;

import static ch.qos.logback.classic.Level.INFO;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.PERMANENT_FAILURE;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.SUCCEEDED;
import static uk.gov.hmcts.cp.notification.logging.CapturedLogs.captureLogsOf;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.Dependency.GOV_UK_NOTIFY;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MATERIAL;
import static uk.gov.hmcts.cp.notification.logging.Operation.DOWNLOAD;
import static uk.gov.hmcts.cp.notification.logging.Operation.SEND;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class LetterSendLoggerTest {
    private static final UUID NOTIFICATION_ID = UUID.fromString("5b7e1c3a-9d2f-4e6b-8a1c-2f3d4e5a6b7c");

    @Mock
    private DependencyCallLogger dependencyCallLogger;
    @Captor
    private ArgumentCaptor<DependencyCall> dependencyCallCaptor;
    @Captor
    private ArgumentCaptor<String> messageCaptor;

    private LetterSendLogger letterSendLogger;

    @BeforeEach
    void createLetterSendLoggerWithMockedDependencyCallLogger() {
        letterSendLogger = new LetterSendLogger(dependencyCallLogger);
    }

    @Test
    void sending_should_log_the_notification_and_its_postage_at_info_without_dependency_fields() {
        final List<ILoggingEvent> loggingEvents;
        try (CapturedLogs capturedLogs = captureLogsOf(LetterSendLogger.class)) {
            letterSendLogger.sending(NOTIFICATION_ID, true);
            loggingEvents = capturedLogs.events();
        }

        assertThat(loggingEvents).hasSize(1);
        final ILoggingEvent loggingEvent = loggingEvents.getFirst();
        assertThat(loggingEvent.getLevel()).isEqualTo(INFO);
        assertThat(loggingEvent.getFormattedMessage())
                .isEqualTo("Sending letter for notification '5b7e1c3a-9d2f-4e6b-8a1c-2f3d4e5a6b7c' (first class: 'true')");
        verifyNoInteractions(dependencyCallLogger);
    }

    @Test
    void sent_should_log_a_successful_letter_send_to_gov_uk_notify() {
        letterSendLogger.sent(NOTIFICATION_ID, "notify-letter-ref");

        verify(dependencyCallLogger).info(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Sent letter for notification '5b7e1c3a-9d2f-4e6b-8a1c-2f3d4e5a6b7c'"
                + " via Gov.Notify (reference 'notify-letter-ref')");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.dependency()).isEqualTo(GOV_UK_NOTIFY);
        assertThat(dependencyCall.operation()).isEqualTo(SEND);
        assertThat(dependencyCall.channel()).isEqualTo(LETTER);
        assertThat(dependencyCall.callOutcome()).isEqualTo(SUCCEEDED);
        assertThat(dependencyCall.statusCode()).isEqualTo(empty());
        assertThat(dependencyCall.failureReason()).isEqualTo(empty());
    }

    @Test
    void sendFailedPermanently_should_log_a_permanent_gov_uk_notify_letter_send_failure_with_its_status() {
        letterSendLogger.sendFailedPermanently(NOTIFICATION_ID, of(400));

        verify(dependencyCallLogger).warn(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Permanent letter send failure for notification"
                + " '5b7e1c3a-9d2f-4e6b-8a1c-2f3d4e5a6b7c' (http '400') — marking FAILED");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.dependency()).isEqualTo(GOV_UK_NOTIFY);
        assertThat(dependencyCall.operation()).isEqualTo(SEND);
        assertThat(dependencyCall.channel()).isEqualTo(LETTER);
        assertThat(dependencyCall.callOutcome()).isEqualTo(PERMANENT_FAILURE);
        assertThat(dependencyCall.statusCode()).isEqualTo(of(400));
        assertThat(dependencyCall.failureReason()).isEqualTo(empty());
    }

    @Test
    void sendFailedPermanently_should_say_none_when_there_is_no_status() {
        letterSendLogger.sendFailedPermanently(NOTIFICATION_ID, empty());

        verify(dependencyCallLogger).warn(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Permanent letter send failure for notification"
                + " '5b7e1c3a-9d2f-4e6b-8a1c-2f3d4e5a6b7c' (http 'none') — marking FAILED");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.statusCode()).isEqualTo(empty());
    }

    @Test
    void downloadFailedPermanently_should_log_a_permanent_material_letter_download_failure_with_its_message() {
        letterSendLogger.downloadFailedPermanently(NOTIFICATION_ID, of(404), "Material has no such document (HTTP 404)");

        verify(dependencyCallLogger).warn(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Permanent Material letter download failure for notification"
                + " '5b7e1c3a-9d2f-4e6b-8a1c-2f3d4e5a6b7c' ('Material has no such document (HTTP 404)')"
                + " — marking FAILED, no retry");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.dependency()).isEqualTo(MATERIAL);
        assertThat(dependencyCall.operation()).isEqualTo(DOWNLOAD);
        assertThat(dependencyCall.channel()).isEqualTo(LETTER);
        assertThat(dependencyCall.callOutcome()).isEqualTo(PERMANENT_FAILURE);
        assertThat(dependencyCall.statusCode()).isEqualTo(of(404));
        assertThat(dependencyCall.failureReason()).isEqualTo(empty());
    }
}
