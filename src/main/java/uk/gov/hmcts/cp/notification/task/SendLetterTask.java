package uk.gov.hmcts.cp.notification.task;

import static java.util.Optional.ofNullable;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_REJECTED;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.logging.AttachmentFailureClassifier;
import uk.gov.hmcts.cp.notification.logging.LetterSendLogger;
import uk.gov.hmcts.cp.notification.logging.OutcomeReason;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentDownloader;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentException;
import uk.gov.hmcts.cp.notification.sender.GovNotifyClient;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.GovNotifyFailureClassifier;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Downloads the letter's PDF through Material and sends it to Gov.Notify as a precompiled letter (legacy
 * {@code send-letter}). On success the letter's status is polled by {@link CheckLetterStatusTask}.
 */
@Task(SendLetterTask.TASK_NAME)
@Component
public class SendLetterTask implements ExecutableTask {
    public static final String TASK_NAME = "SEND_LETTER";

    private final MaterialAttachmentDownloader materialAttachmentDownloader;
    private final GovNotifyClient govNotifyClient;
    private final TransientFailurePolicy failurePolicy;
    private final ExecutionService executionService;
    private final CpTaskFactory taskFactory;
    private final LetterSendLogger letterSendLogger;
    private final GovNotifyFailureClassifier govNotifyFailureClassifier;
    private final AttachmentFailureClassifier attachmentFailureClassifier;
    private final List<Long> retryDurationsSecs;

    public SendLetterTask(
            final MaterialAttachmentDownloader materialAttachmentDownloader,
            final GovNotifyClient govNotifyClient,
            final TransientFailurePolicy failurePolicy,
            final ExecutionService executionService,
            final CpTaskFactory taskFactory,
            final LetterSendLogger letterSendLogger,
            final GovNotifyFailureClassifier govNotifyFailureClassifier,
            final AttachmentFailureClassifier attachmentFailureClassifier,
            @Value("${cp.notification.retry.letter-durations-secs}") final List<Long> retryDurationsSecs) {
        this.materialAttachmentDownloader = materialAttachmentDownloader;
        this.govNotifyClient = govNotifyClient;
        this.failurePolicy = failurePolicy;
        this.executionService = executionService;
        this.taskFactory = taskFactory;
        this.letterSendLogger = letterSendLogger;
        this.govNotifyFailureClassifier = govNotifyFailureClassifier;
        this.attachmentFailureClassifier = attachmentFailureClassifier;
        this.retryDurationsSecs = List.copyOf(retryDurationsSecs);
    }

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final LetterJob letter = LetterJob.from(executionInfo.getJobData());
        ExecutionInfo result;
        try {
            final byte[] pdf = materialAttachmentDownloader.download(letter.letterUrl(), letter.notificationId()).content();
            result = send(letter, pdf, executionInfo);
        } catch (final MaterialAttachmentException materialAttachmentException) {
            result = downloadFailed(letter, materialAttachmentException, executionInfo);
        }
        return result;
    }

    private ExecutionInfo send(final LetterJob letter, final byte[] pdf, final ExecutionInfo executionInfo) {
        ExecutionInfo result;
        try {
            final UUID notificationId = letter.notificationId();
            final boolean firstClass = letter.firstClass();
            letterSendLogger.sending(notificationId, firstClass);
            final String reference = govNotifyClient.sendPrecompiledLetter(notificationId, pdf, firstClass);
            letterSendLogger.sent(notificationId, reference);
            executionService.executeWith(taskFactory.createCheckLetterStatusJob(letter, reference));
            result = TransientFailurePolicy.completed(executionInfo);
        } catch (final GovNotifyException govNotifyException) {
            if (govNotifyFailureClassifier.isTemporary(govNotifyException)) {
                result = failurePolicy.retryOrFail(letter.notificationId(), govNotifyException.getHttpStatus(),
                        "Gov.Notify letter send did not recover within the retry window", executionInfo,
                        retryDurationsSecs, RetryCause.GOV_NOTIFY_SEND, LETTER, govNotifyException);
            } else {
                final Optional<Integer> statusCode = ofNullable(govNotifyException.getHttpStatus());
                final UUID notificationId = letter.notificationId();
                letterSendLogger.sendFailedPermanently(notificationId, statusCode);
                result = failurePolicy.fail(letter.notificationId(), govNotifyException.getHttpStatus(),
                        govNotifyException.getMessage(), PROVIDER_REJECTED, executionInfo);
            }
        }
        return result;
    }

    private ExecutionInfo downloadFailed(
            final LetterJob letter,
            final MaterialAttachmentException materialAttachmentException,
            final ExecutionInfo executionInfo) {
        final ExecutionInfo result;
        if (materialAttachmentException.isRetryable()) {
            result = failurePolicy.retryOrFail(letter.notificationId(), materialAttachmentException.getStatusCode(),
                    "Material letter download did not recover within the retry window", executionInfo,
                    retryDurationsSecs, RetryCause.MATERIAL_DOWNLOAD, LETTER, materialAttachmentException);
        } else {
            final Optional<Integer> statusCode = ofNullable(materialAttachmentException.getStatusCode());
            final UUID notificationId = letter.notificationId();
            final String failureMessage = materialAttachmentException.getMessage();
            letterSendLogger.downloadFailedPermanently(notificationId, statusCode, failureMessage);
            final OutcomeReason outcomeReason = attachmentFailureClassifier.classify(statusCode);
            result = failurePolicy.fail(letter.notificationId(), materialAttachmentException.getStatusCode(),
                    materialAttachmentException.getMessage(), outcomeReason, executionInfo);
        }
        return result;
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return Optional.of(retryDurationsSecs);
    }
}
