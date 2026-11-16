package uk.gov.hmcts.cp.notification.logging;

import static java.lang.String.format;
import static java.util.Optional.empty;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.PERMANENT_FAILURE;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.SUCCEEDED;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.Dependency.GOV_UK_NOTIFY;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MATERIAL;
import static uk.gov.hmcts.cp.notification.logging.Operation.DOWNLOAD;
import static uk.gov.hmcts.cp.notification.logging.Operation.SEND;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The log lines for downloading a letter from Material and sending it to Gov.Notify, each outcome with its
 * {@link DependencyCall} fields (PEG-3720), so that the send task only says what happened.
 */
@Component
@RequiredArgsConstructor
public class LetterSendLogger {
    private final Logger logger = LoggerFactory.getLogger(LetterSendLogger.class);

    private final DependencyCallLogger dependencyCallLogger;

    public void sending(final UUID notificationId, final boolean firstClass) {
        final String message = format("Sending letter for notification '%s' (first class: '%s')", notificationId, firstClass);
        logger.info(message);
    }

    public void sent(final UUID notificationId, final String reference) {
        final DependencyCall dependencyCall = new DependencyCall(GOV_UK_NOTIFY, SEND, LETTER, SUCCEEDED, empty(), empty());
        final String message = format(
                "Sent letter for notification '%s' via Gov.Notify (reference '%s')", notificationId, reference);
        dependencyCallLogger.info(dependencyCall, message);
    }

    public void sendFailedPermanently(final UUID notificationId, final Optional<Integer> statusCode) {
        final DependencyCall dependencyCall =
                new DependencyCall(GOV_UK_NOTIFY, SEND, LETTER, PERMANENT_FAILURE, statusCode, empty());
        final Optional<String> statusCodeText = statusCode.map(String::valueOf);
        final String message = format(
                "Permanent letter send failure for notification '%s' (http '%s') — marking FAILED",
                notificationId, statusCodeText.orElse("none"));
        dependencyCallLogger.warn(dependencyCall, message);
    }

    public void downloadFailedPermanently(
            final UUID notificationId, final Optional<Integer> statusCode, final String failureMessage) {
        final DependencyCall dependencyCall =
                new DependencyCall(MATERIAL, DOWNLOAD, LETTER, PERMANENT_FAILURE, statusCode, empty());
        final String message = format(
                "Permanent Material letter download failure for notification '%s' ('%s') — marking FAILED, no retry",
                notificationId, failureMessage);
        dependencyCallLogger.warn(dependencyCall, message);
    }
}
