package uk.gov.hmcts.cp.notification.task;

import static java.util.Optional.ofNullable;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_REJECTED;

import com.azure.storage.blob.models.BlobStorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import uk.gov.hmcts.cp.notification.blob.AttachmentDownloader;
import uk.gov.hmcts.cp.notification.blob.PermanentBlobException;
import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.logging.AttachmentFailureClassifier;
import uk.gov.hmcts.cp.notification.logging.EmailSendLogger;
import uk.gov.hmcts.cp.notification.logging.OutcomeReason;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentDownloader;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentException;
import uk.gov.hmcts.cp.notification.sender.Attachment;
import uk.gov.hmcts.cp.notification.sender.EmailSender;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.GovNotifyFailureClassifier;
import uk.gov.hmcts.cp.notification.sender.Office365Exception;
import uk.gov.hmcts.cp.notification.sender.SendResult;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Task(SendEmailTask.TASK_NAME)
@Component
public class SendEmailTask implements ExecutableTask {
    public static final String TASK_NAME = "SEND_EMAIL";

    private final AttachmentDownloader attachmentDownloader;
    private final MaterialAttachmentDownloader materialAttachmentDownloader;
    private final EmailSender emailSender;
    private final TransientFailurePolicy failurePolicy;
    private final ExecutionService executionService;
    private final ObjectMapper objectMapper;
    private final CpTaskFactory taskFactory;
    private final EmailSendLogger emailSendLogger;
    private final GovNotifyFailureClassifier govNotifyFailureClassifier;
    private final AttachmentFailureClassifier attachmentFailureClassifier;
    private final List<Long> retryDurationsSecs;

    public SendEmailTask(
            final AttachmentDownloader attachmentDownloader,
            final MaterialAttachmentDownloader materialAttachmentDownloader,
            final EmailSender emailSender,
            final TransientFailurePolicy failurePolicy,
            final ExecutionService executionService,
            final ObjectMapper objectMapper,
            final CpTaskFactory taskFactory,
            final EmailSendLogger emailSendLogger,
            final GovNotifyFailureClassifier govNotifyFailureClassifier,
            final AttachmentFailureClassifier attachmentFailureClassifier,
            @Value("${cp.notification.retry.email-durations-secs}") final List<Long> retryDurationsSecs) {
        this.attachmentDownloader = attachmentDownloader;
        this.materialAttachmentDownloader = materialAttachmentDownloader;
        this.emailSender = emailSender;
        this.failurePolicy = failurePolicy;
        this.executionService = executionService;
        this.objectMapper = objectMapper;
        this.taskFactory = taskFactory;
        this.emailSendLogger = emailSendLogger;
        this.govNotifyFailureClassifier = govNotifyFailureClassifier;
        this.attachmentFailureClassifier = attachmentFailureClassifier;
        this.retryDurationsSecs = retryDurationsSecs;
    }

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final SendEmailCommand command = objectMapper.readValue(
                executionInfo.getJobData().toString(), SendEmailCommand.class);
        ExecutionInfo result;
        try {
            result = send(command, downloadAttachment(command), executionInfo);
        } catch (final PermanentBlobException permanentBlobException) {
            final Optional<Integer> statusCode = ofNullable(permanentBlobException.getStatusCode());
            final UUID notificationId = command.notificationId();
            emailSendLogger.attachmentFailedPermanently(notificationId, statusCode);
            final OutcomeReason outcomeReason = attachmentFailureClassifier.classify(statusCode);
            result = failurePolicy.fail(command.notificationId(), permanentBlobException.getStatusCode(),
                    permanentBlobException.getMessage(), outcomeReason, executionInfo);
        } catch (final BlobStorageException blobStorageException) {
            result = failurePolicy.retryOrFail(command.notificationId(), blobStorageException.getStatusCode(),
                    "Attachment download did not recover within the retry window", executionInfo,
                    RetryCause.ATTACHMENT_DOWNLOAD, EMAIL, blobStorageException);
        } catch (final MaterialAttachmentException materialAttachmentException) {
            result = materialAttachmentFailed(command, materialAttachmentException, executionInfo);
        }
        return result;
    }

    private ExecutionInfo materialAttachmentFailed(
            final SendEmailCommand command,
            final MaterialAttachmentException materialAttachmentException,
            final ExecutionInfo executionInfo) {
        final ExecutionInfo result;
        if (materialAttachmentException.isRetryable()) {
            result = failurePolicy.retryOrFail(command.notificationId(), materialAttachmentException.getStatusCode(),
                    "Material attachment download did not recover within the retry window", executionInfo,
                    RetryCause.MATERIAL_DOWNLOAD, EMAIL, materialAttachmentException);
        } else {
            final Optional<Integer> statusCode = ofNullable(materialAttachmentException.getStatusCode());
            final UUID notificationId = command.notificationId();
            final String failureMessage = materialAttachmentException.getMessage();
            emailSendLogger.materialAttachmentFailedPermanently(notificationId, statusCode, failureMessage);
            final OutcomeReason outcomeReason = attachmentFailureClassifier.classify(statusCode);
            result = failurePolicy.fail(command.notificationId(), materialAttachmentException.getStatusCode(),
                    materialAttachmentException.getMessage(), outcomeReason, executionInfo);
        }
        return result;
    }

    // Precedence (legacy parity): a non-blank materialUrl wins over fileUri; a blank one counts as absent.
    private Attachment downloadAttachment(final SendEmailCommand command) {
        Attachment attachment = null;
        if (StringUtils.hasText(command.materialUrl())) {
            attachment = materialAttachmentDownloader.download(command.materialUrl(), command.notificationId());
        } else if (StringUtils.hasText(command.fileUri())) {
            attachment = Attachment.withoutFilename(attachmentDownloader.download(command.fileUri()));
        }
        return attachment;
    }

    private ExecutionInfo send(
            final SendEmailCommand command, final Attachment attachment, final ExecutionInfo executionInfo) {
        ExecutionInfo result;
        try {
            final SendResult sendResult = emailSender.sendEmail(command, attachment);
            // Office 365 has already sent the email, so SENT is recorded by its own task (legacy: mark-as-sent
            // command, no poll); Gov.Notify is polled. Either job commits with this task's completion.
            executionService.executeWith(sendResult.sentOnSubmit()
                    ? taskFactory.createRecordSentJob(command, sendResult)
                    : taskFactory.createCheckStatusJob(command, sendResult));
            result = TransientFailurePolicy.completed(executionInfo);
        } catch (final Office365Exception office365Exception) {
            result = office365SendFailed(command, office365Exception, executionInfo);
        } catch (final GovNotifyException govNotifyException) {
            if (govNotifyFailureClassifier.isTemporary(govNotifyException)) {
                result = failurePolicy.retryOrFail(command.notificationId(), govNotifyException.getHttpStatus(),
                        "Gov.Notify send did not recover within the retry window", executionInfo,
                        RetryCause.GOV_NOTIFY_SEND, EMAIL, govNotifyException);
            } else {
                final Optional<Integer> statusCode = ofNullable(govNotifyException.getHttpStatus());
                final UUID notificationId = command.notificationId();
                emailSendLogger.govNotifySendFailedPermanently(notificationId, statusCode);
                result = failurePolicy.fail(command.notificationId(), govNotifyException.getHttpStatus(),
                        govNotifyException.getMessage(), PROVIDER_REJECTED, executionInfo);
            }
        }
        return result;
    }

    private ExecutionInfo office365SendFailed(
            final SendEmailCommand command, final Office365Exception office365Exception, final ExecutionInfo executionInfo) {
        final ExecutionInfo result;
        if (office365Exception.isPermanent()) {
            final Optional<Integer> statusCode = ofNullable(office365Exception.getHttpStatus());
            final UUID notificationId = command.notificationId();
            emailSendLogger.office365SendFailedPermanently(notificationId, statusCode);
            result = failurePolicy.fail(command.notificationId(), office365Exception.getHttpStatus(),
                    office365Exception.getMessage(), PROVIDER_REJECTED, executionInfo);
        } else {
            result = failurePolicy.retryOrFail(command.notificationId(), office365Exception.getHttpStatus(),
                    "Office 365 send did not recover within the retry window", executionInfo,
                    RetryCause.OFFICE365_SEND, EMAIL, office365Exception);
        }
        return result;
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return Optional.of(retryDurationsSecs);
    }
}
