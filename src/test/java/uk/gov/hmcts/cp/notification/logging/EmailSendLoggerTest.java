package uk.gov.hmcts.cp.notification.logging;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.PERMANENT_FAILURE;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.SUCCEEDED;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Dependency.BLOB_STORAGE;
import static uk.gov.hmcts.cp.notification.logging.Dependency.GOV_UK_NOTIFY;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MATERIAL;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MICROSOFT_365;
import static uk.gov.hmcts.cp.notification.logging.Operation.DOWNLOAD;
import static uk.gov.hmcts.cp.notification.logging.Operation.SEND;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class EmailSendLoggerTest {
    private static final UUID NOTIFICATION_ID = UUID.fromString("0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80");

    @Mock
    private DependencyCallLogger dependencyCallLogger;
    @Captor
    private ArgumentCaptor<DependencyCall> dependencyCallCaptor;
    @Captor
    private ArgumentCaptor<String> messageCaptor;

    private EmailSendLogger emailSendLogger;

    @BeforeEach
    void createEmailSendLoggerWithMockedDependencyCallLogger() {
        emailSendLogger = new EmailSendLogger(dependencyCallLogger);
    }

    @Test
    void sent_should_log_a_successful_send_to_the_given_dependency() {
        emailSendLogger.sent(NOTIFICATION_ID, MICROSOFT_365, "Office365Client", "office-reference");

        verify(dependencyCallLogger).info(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Sent notification '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' via 'Office365Client'"
                + " (reference 'office-reference')");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.dependency()).isEqualTo(MICROSOFT_365);
        assertThat(dependencyCall.operation()).isEqualTo(SEND);
        assertThat(dependencyCall.channel()).isEqualTo(EMAIL);
        assertThat(dependencyCall.callOutcome()).isEqualTo(SUCCEEDED);
        assertThat(dependencyCall.statusCode()).isEqualTo(empty());
        assertThat(dependencyCall.failureReason()).isEqualTo(empty());
    }

    @Test
    void attachmentFailedPermanently_should_log_a_permanent_blob_storage_download_failure() {
        emailSendLogger.attachmentFailedPermanently(NOTIFICATION_ID, of(404));

        verify(dependencyCallLogger).warn(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Permanent attachment failure for notification"
                + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' — marking FAILED, no retry");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.dependency()).isEqualTo(BLOB_STORAGE);
        assertThat(dependencyCall.operation()).isEqualTo(DOWNLOAD);
        assertThat(dependencyCall.channel()).isEqualTo(EMAIL);
        assertThat(dependencyCall.callOutcome()).isEqualTo(PERMANENT_FAILURE);
        assertThat(dependencyCall.statusCode()).isEqualTo(of(404));
        assertThat(dependencyCall.failureReason()).isEqualTo(empty());
    }

    @Test
    void materialAttachmentFailedPermanently_should_log_a_permanent_material_download_failure_with_its_message() {
        emailSendLogger.materialAttachmentFailedPermanently(
                NOTIFICATION_ID, empty(), "Material link is not on the Material service");

        verify(dependencyCallLogger).warn(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Permanent Material attachment failure for notification"
                + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' ('Material link is not on the Material service')"
                + " — marking FAILED, no retry");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.dependency()).isEqualTo(MATERIAL);
        assertThat(dependencyCall.operation()).isEqualTo(DOWNLOAD);
        assertThat(dependencyCall.channel()).isEqualTo(EMAIL);
        assertThat(dependencyCall.callOutcome()).isEqualTo(PERMANENT_FAILURE);
        assertThat(dependencyCall.statusCode()).isEqualTo(empty());
        assertThat(dependencyCall.failureReason()).isEqualTo(empty());
    }

    @Test
    void govNotifySendFailedPermanently_should_log_a_permanent_gov_uk_notify_send_failure_with_its_status() {
        emailSendLogger.govNotifySendFailedPermanently(NOTIFICATION_ID, of(400));

        verify(dependencyCallLogger).warn(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Permanent send failure for notification"
                + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (http '400') — marking FAILED");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.dependency()).isEqualTo(GOV_UK_NOTIFY);
        assertThat(dependencyCall.operation()).isEqualTo(SEND);
        assertThat(dependencyCall.channel()).isEqualTo(EMAIL);
        assertThat(dependencyCall.callOutcome()).isEqualTo(PERMANENT_FAILURE);
        assertThat(dependencyCall.statusCode()).isEqualTo(of(400));
        assertThat(dependencyCall.failureReason()).isEqualTo(empty());
    }

    @Test
    void office365SendFailedPermanently_should_log_a_permanent_microsoft_365_send_failure_with_its_status() {
        emailSendLogger.office365SendFailedPermanently(NOTIFICATION_ID, of(403));

        verify(dependencyCallLogger).warn(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Permanent Office 365 send failure for notification"
                + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (http '403') — marking FAILED");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.dependency()).isEqualTo(MICROSOFT_365);
        assertThat(dependencyCall.operation()).isEqualTo(SEND);
        assertThat(dependencyCall.channel()).isEqualTo(EMAIL);
        assertThat(dependencyCall.callOutcome()).isEqualTo(PERMANENT_FAILURE);
        assertThat(dependencyCall.statusCode()).isEqualTo(of(403));
        assertThat(dependencyCall.failureReason()).isEqualTo(empty());
    }

    @Test
    void office365SendFailedPermanently_should_say_none_when_there_is_no_status() {
        emailSendLogger.office365SendFailedPermanently(NOTIFICATION_ID, empty());

        verify(dependencyCallLogger).warn(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Permanent Office 365 send failure for notification"
                + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (http 'none') — marking FAILED");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.statusCode()).isEqualTo(empty());
    }
}
