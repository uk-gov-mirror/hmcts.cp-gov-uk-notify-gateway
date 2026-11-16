package uk.gov.hmcts.cp.notification.sender;

import com.azure.storage.blob.BlobUrlParts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.logging.Dependency;
import uk.gov.hmcts.cp.notification.logging.EmailSendLogger;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EmailSender {
    private final EmailClientFactory emailClientFactory;
    private final EmailSendLogger emailSendLogger;

    public SendResult sendEmail(final SendEmailCommand command, final Attachment attachment) {
        final SendEmailRequest request = new SendEmailRequest(
                command.notificationId(),
                command.templateId(),
                command.sendToAddress(),
                command.personalisation(),
                attachment == null ? null : attachment.content(),
                attachmentFilename(command, attachment),
                command.replyToAddress(),
                command.replyToAddressId());

        final EmailClient emailClient = emailClientFactory.selectFor(request);
        final SendResult sendResult = emailClient.send(request);
        final Dependency dependency = emailClient.dependency();
        final Class<? extends EmailClient> emailClientClass = emailClient.getClass();
        final String emailClientName = emailClientClass.getSimpleName();
        final UUID notificationId = command.notificationId();
        final String reference = sendResult.reference();
        emailSendLogger.sent(notificationId, dependency, emailClientName, reference);
        return sendResult;
    }

    private static String attachmentFilename(final SendEmailCommand command, final Attachment attachment) {
        String filename = null;
        if (attachment != null) {
            filename = attachment.filename() == null ? filenameFrom(command.fileUri()) : attachment.filename();
        }
        return filename;
    }

    private static String filenameFrom(final String fileUri) {
        String filename = null;
        if (fileUri != null && !fileUri.isBlank()) {
            final String blobName = BlobUrlParts.parse(fileUri).getBlobName();
            if (blobName != null && !blobName.isBlank()) {
                final String segment = blobName.substring(blobName.lastIndexOf('/') + 1);
                if (!segment.isBlank()) {
                    filename = segment;
                }
            }
        }
        return filename;
    }
}
