package grails.plugins.mail.aws

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.mail.MailException
import org.springframework.mail.MailSendException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.mail.javamail.MimeMessagePreparator
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sesv2.model.EmailContent
import software.amazon.awssdk.services.sesv2.model.RawMessage
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SesV2Exception

import javax.mail.Session
import javax.mail.internet.MimeMessage
import java.util.Properties

/**
 * SesApiMailSender
 * ================
 * A JavaMailSender implementation that delivers all outbound mail through
 * the AWS SES v2 API (SendRawEmail) – no SMTP connection is involved.
 *
 * Why API instead of SMTP?
 * ------------------------
 * The grails-mail plugin already provides full SMTP support. This plugin's
 * sole purpose is to offer the native AWS API transport path, which gives:
 *   - No TCP/STARTTLS handshake per message.
 *   - A SES MessageId returned on every send, usable for delivery tracking.
 *   - Native integration with SES Configuration Sets (open/click tracking,
 *     bounce/complaint event destinations via SNS).
 *   - Full MIME fidelity: SendRawEmail accepts the serialised MimeMessage
 *     byte-for-byte, preserving attachments, multipart HTML/text, inline
 *     images and custom headers exactly as grails-mail composed them.
 *
 * Drop-in replacement
 * -------------------
 * Implements Spring's JavaMailSender interface and is registered as the
 * 'mailSender' bean, which is the exact bean name that both grails-mail and
 * asynchronous-mail look up at runtime. No changes are needed in any service
 * or controller code in the consuming application.
 *
 * Credentials (evaluated in priority order)
 * -----------------------------------------
 * 1. grails.mail.ses.accessKey + secretKey  – explicit IAM key pair in config.
 * 2. DefaultCredentialsProvider chain       – env vars AWS_ACCESS_KEY_ID /
 *    AWS_SECRET_ACCESS_KEY, ~/.aws/credentials, EC2/ECS instance role, etc.
 *    This is the recommended approach for applications running on AWS.
 */
@Slf4j
class SesApiMailSender implements JavaMailSender, InitializingBean, DisposableBean {

    /** Populated by GrailsSesMailGrailsPlugin#doWithSpring. */
    SesMailConfiguration sesConfiguration

    /** AWS SES v2 client; initialised in afterPropertiesSet, closed in destroy. */
    private SesV2Client sesClient

    /**
     * Dummy JavaMail Session used solely as a MimeMessage factory.
     * No SMTP connection is ever opened through this session.
     */
    private Session dummySession

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    void afterPropertiesSet() {
        if (!sesConfiguration) {
            throw new IllegalStateException("SesApiMailSender: sesConfiguration must not be null")
        }

        sesClient = buildSesClient()

        // Session with no properties – purely for MimeMessage construction.
        dummySession = Session.getInstance(new Properties())

        log.info "SesApiMailSender initialised – region={}, configurationSet='{}'",
                sesConfiguration.region,
                sesConfiguration.configurationSetName ?: "(none)"
    }

    /**
     * Builds the SesV2Client from sesConfiguration.
     * Package-visible so tests can override it to inject a mock without
     * triggering real AWS credential resolution during afterPropertiesSet().
     */
    protected SesV2Client buildSesClient() {
        def credentialsProvider = (sesConfiguration.accessKey && sesConfiguration.secretKey)
                ? StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(sesConfiguration.accessKey, sesConfiguration.secretKey))
                : DefaultCredentialsProvider.create()
        SesV2Client.builder()
                .region(Region.of(sesConfiguration.region))
                .credentialsProvider(credentialsProvider)
                .build()
    }

    @Override
    void destroy() {
        sesClient?.close()
        log.info "SesApiMailSender destroyed – SES client closed"
    }

    // -------------------------------------------------------------------------
    // JavaMailSender – MimeMessage factory
    //
    // grails-mail's MailMessageBuilder calls createMimeMessage() to get a blank
    // MimeMessage, then populates it (to, cc, bcc, subject, body, attachments)
    // before passing it back to send(). These methods provide that blank message.
    // -------------------------------------------------------------------------

    @Override
    MimeMessage createMimeMessage() {
        new MimeMessage(dummySession)
    }

    @Override
    MimeMessage createMimeMessage(InputStream contentStream) throws MailException {
        try {
            new MimeMessage(dummySession, contentStream)
        } catch (Exception e) {
            throw new MailSendException("Failed to parse MimeMessage from stream", e)
        }
    }

    // -------------------------------------------------------------------------
    // JavaMailSender – send methods
    //
    // Every code path ultimately calls sendRaw(MimeMessage), which serialises
    // the message to raw bytes and submits it to SES via SendRawEmail.
    // -------------------------------------------------------------------------

    @Override
    void send(MimeMessage mimeMessage) throws MailException {
        sendRaw(mimeMessage)
    }

    @Override
    void send(MimeMessage[] mimeMessages) throws MailException {
        for (MimeMessage msg : mimeMessages) {
            sendRaw(msg)
        }
    }

    @Override
    void send(MimeMessagePreparator preparator) throws MailException {
        MimeMessage msg = createMimeMessage()
        try {
            preparator.prepare(msg)
        } catch (Exception e) {
            throw new MailSendException("MimeMessagePreparator.prepare() failed", e)
        }
        sendRaw(msg)
    }

    @Override
    void send(MimeMessagePreparator[] preparators) throws MailException {
        for (MimeMessagePreparator preparator : preparators) {
            send(preparator)
        }
    }

    /**
     * SimpleMailMessage → MimeMessage conversion.
     *
     * grails-mail's sendMail { text "..." } DSL uses SimpleMailMessage for
     * plain-text-only messages. We convert it to a MimeMessage here so all
     * sends travel through the single sendRaw() code path.
     */
    @Override
    void send(SimpleMailMessage simpleMessage) throws MailException {
        MimeMessage mime = createMimeMessage()
        MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8")

        String from = simpleMessage.from ?: sesConfiguration.defaultFrom
        if (!from) {
            throw new MailSendException(
                    "No 'from' address provided and grails.mail.ses.defaultFrom is not configured")
        }
        helper.from = from
        if (simpleMessage.replyTo) helper.replyTo = simpleMessage.replyTo
        if (simpleMessage.to)      helper.setTo(simpleMessage.to)
        if (simpleMessage.cc)      helper.setCc(simpleMessage.cc)
        if (simpleMessage.bcc)     helper.setBcc(simpleMessage.bcc)
        helper.subject = simpleMessage.subject ?: ""
        helper.setText(simpleMessage.text ?: "")

        sendRaw(mime)
    }

    @Override
    void send(SimpleMailMessage[] simpleMessages) throws MailException {
        for (SimpleMailMessage msg : simpleMessages) {
            send(msg)
        }
    }

    // -------------------------------------------------------------------------
    // Core: MimeMessage → raw bytes → SES SendRawEmail API call
    // -------------------------------------------------------------------------

    private void sendRaw(MimeMessage mimeMessage) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192)
        try {
            mimeMessage.writeTo(baos)
        } catch (Exception e) {
            throw new MailSendException("Failed to serialise MimeMessage to bytes", e)
        }

        SendEmailRequest.Builder req = SendEmailRequest.builder()
                .content(EmailContent.builder()
                        .raw(RawMessage.builder()
                                .data(SdkBytes.fromByteArray(baos.toByteArray()))
                                .build())
                        .build())

        if (sesConfiguration.configurationSetName) {
            req.configurationSetName(sesConfiguration.configurationSetName)
        }

        try {
            def response = sesClient.sendEmail(req.build())
            log.debug "SES SendRawEmail succeeded – messageId={}", response.messageId()
        } catch (SesV2Exception e) {
            // The SES v2 SDK does not have named subclasses per error code.
            // Inspect errorCode() to give a targeted message for the most
            // actionable failure: a misconfigured configuration set name.
            String errorCode = e.awsErrorDetails()?.errorCode() ?: ''
            String detail    = e.awsErrorDetails()?.errorMessage() ?: e.message
            if (errorCode == 'ConfigurationSetDoesNotExist') {
                throw new MailSendException(
                        "SES configuration set '${sesConfiguration.configurationSetName}' not found" +
                        " in region '${sesConfiguration.region}' (errorCode=${errorCode})", e)
            }
            log.error "SES SendRawEmail failed – errorCode={}, detail={}", errorCode, detail, e
            throw new MailSendException("SES API error [${errorCode}]: ${detail}", e)
        }
    }
}
