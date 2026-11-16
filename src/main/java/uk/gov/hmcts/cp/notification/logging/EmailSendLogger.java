package uk.gov.hmcts.cp.notification.logging;

import static java.lang.String.format;
import static java.util.Optional.empty;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.PERMANENT_FAILURE;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.SUCCEEDED;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Dependency.BLOB_STORAGE;
import static uk.gov.hmcts.cp.notification.logging.Dependency.GOV_UK_NOTIFY;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MATERIAL;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MICROSOFT_365;
import static uk.gov.hmcts.cp.notification.logging.Operation.DOWNLOAD;
import static uk.gov.hmcts.cp.notification.logging.Operation.SEND;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The log lines for sending an email and downloading its attachment, each with its {@link DependencyCall} fields
 * (PEG-3720), so that the sender and the send task only say what happened.
 */
@Component
@RequiredArgsConstructor
public class EmailSendLogger {
    private final DependencyCallLogger dependencyCallLogger;

    public void sent(
            final UUID notificationId, final Dependency dependency, final String emailClientName, final String reference) {
        final DependencyCall dependencyCall = new DependencyCall(dependency, SEND, EMAIL, SUCCEEDED, empty(), empty());
        final String message = format(
                "Sent notification '%s' via '%s' (reference '%s')", notificationId, emailClientName, reference);
        dependencyCallLogger.info(dependencyCall, message);
    }

    public void attachmentFailedPermanently(final UUID notificationId, final Optional<Integer> statusCode) {
        final DependencyCall dependencyCall =
                new DependencyCall(BLOB_STORAGE, DOWNLOAD, EMAIL, PERMANENT_FAILURE, statusCode, empty());
        final String message = format(
                "Permanent attachment failure for notification '%s' — marking FAILED, no retry", notificationId);
        dependencyCallLogger.warn(dependencyCall, message);
    }

    public void materialAttachmentFailedPermanently(
            final UUID notificationId, final Optional<Integer> statusCode, final String failureMessage) {
        final DependencyCall dependencyCall =
                new DependencyCall(MATERIAL, DOWNLOAD, EMAIL, PERMANENT_FAILURE, statusCode, empty());
        final String message = format(
                "Permanent Material attachment failure for notification '%s' ('%s') — marking FAILED, no retry",
                notificationId, failureMessage);
        dependencyCallLogger.warn(dependencyCall, message);
    }

    public void govNotifySendFailedPermanently(final UUID notificationId, final Optional<Integer> statusCode) {
        final DependencyCall dependencyCall =
                new DependencyCall(GOV_UK_NOTIFY, SEND, EMAIL, PERMANENT_FAILURE, statusCode, empty());
        final String message = format(
                "Permanent send failure for notification '%s' (http '%s') — marking FAILED",
                notificationId, statusCodeText(statusCode));
        dependencyCallLogger.warn(dependencyCall, message);
    }

    public void office365SendFailedPermanently(final UUID notificationId, final Optional<Integer> statusCode) {
        final DependencyCall dependencyCall =
                new DependencyCall(MICROSOFT_365, SEND, EMAIL, PERMANENT_FAILURE, statusCode, empty());
        final String message = format(
                "Permanent Office 365 send failure for notification '%s' (http '%s') — marking FAILED",
                notificationId, statusCodeText(statusCode));
        dependencyCallLogger.warn(dependencyCall, message);
    }

    private String statusCodeText(final Optional<Integer> statusCode) {
        final Optional<String> statusCodeText = statusCode.map(String::valueOf);
        return statusCodeText.orElse("none");
    }
}
