package grails.plugins.mail.aws

import org.springframework.mail.MailSendException
import org.springframework.mail.SimpleMailMessage
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse
import software.amazon.awssdk.services.sesv2.model.SesV2Exception
import software.amazon.awssdk.awscore.exception.AwsErrorDetails

import javax.mail.internet.MimeMessage

/**
 * Unit tests for SesApiMailSender.
 *
 * The AWS SesV2Client is always mocked so no real AWS credentials or network
 * access are required.  afterPropertiesSet() is also tested with a mock client
 * injected directly via the private field to avoid SDK credential resolution.
 */
class SesApiMailSenderSpec extends Specification {

    SesMailConfiguration config = new SesMailConfiguration(
            region              : "us-east-1",
            accessKey           : "",   // empty → DefaultCredentialsProvider
            secretKey           : "",
            defaultFrom         : "sender@example.com",
            configurationSetName: ""
    )

    SesV2Client mockSesClient = Mock(SesV2Client)

    @Subject
    // Override buildSesClient() so afterPropertiesSet() never touches the
    // real AWS credential chain – the mock is returned directly.
    SesApiMailSender sender = new SesApiMailSender() {
        @Override
        protected SesV2Client buildSesClient() { mockSesClient }
    }.tap { sesConfiguration = config }

    def setup() {
        sender.afterPropertiesSet()
    }

    // -------------------------------------------------------------------------
    // afterPropertiesSet
    // -------------------------------------------------------------------------

    def "afterPropertiesSet throws when sesConfiguration is null"() {
        given:
        def uninitialised = new SesApiMailSender()

        when:
        uninitialised.afterPropertiesSet()

        then:
        thrown(IllegalStateException)
    }

    // -------------------------------------------------------------------------
    // createMimeMessage
    // -------------------------------------------------------------------------

    def "createMimeMessage returns a non-null MimeMessage"() {
        when:
        MimeMessage msg = sender.createMimeMessage()

        then:
        msg != null
    }

    def "createMimeMessage(InputStream) parses a valid raw MIME stream"() {
        given:
        // Minimal valid MIME message
        String raw = "From: a@b.com\r\nTo: c@d.com\r\nSubject: Test\r\n\r\nBody"
        InputStream stream = new ByteArrayInputStream(raw.bytes)

        when:
        MimeMessage msg = sender.createMimeMessage(stream)

        then:
        msg != null
        msg.subject == "Test"
    }

    def "createMimeMessage(InputStream) wraps parse errors in MailSendException"() {
        given:
        InputStream broken = new InputStream() {
            @Override int read() throws IOException { throw new IOException("simulated") }
        }

        when:
        sender.createMimeMessage(broken)

        then:
        thrown(MailSendException)
    }

    // -------------------------------------------------------------------------
    // send(MimeMessage) – happy path
    // -------------------------------------------------------------------------

    def "send(MimeMessage) calls SES SendRawEmail and succeeds"() {
        given:
        MimeMessage mime = buildMime("from@example.com", "to@example.com", "Hello", "Body text")

        when:
        sender.send(mime)

        then:
        1 * mockSesClient.sendEmail({ SendEmailRequest req ->
            req.content().raw() != null
        } as SendEmailRequest) >> SendEmailResponse.builder()
                .messageId("MSG-001")
                .build()
    }

    def "send(MimeMessage[]) sends every message"() {
        given:
        int count = 3
        MimeMessage[] messages = (1..count).collect {
            buildMime("from@example.com", "to${it}@example.com", "Subject ${it}", "Body ${it}")
        } as MimeMessage[]

        when:
        sender.send(messages)

        then:
        count * mockSesClient.sendEmail(_ as SendEmailRequest) >> SendEmailResponse.builder()
                .messageId("MSG-00X")
                .build()
    }

    // -------------------------------------------------------------------------
    // send(MimeMessagePreparator)
    // -------------------------------------------------------------------------

    def "send(MimeMessagePreparator) prepares and sends the message"() {
        when:
        sender.send({ MimeMessage msg ->
            msg.setFrom("prep@example.com")
            msg.setRecipients(javax.mail.Message.RecipientType.TO,
                    javax.mail.internet.InternetAddress.parse("dest@example.com"))
            msg.setSubject("Prepared")
            msg.setText("Prepared body")
        })

        then:
        1 * mockSesClient.sendEmail(_ as SendEmailRequest) >> SendEmailResponse.builder()
                .messageId("MSG-PREP")
                .build()
    }

    def "send(MimeMessagePreparator) wraps preparator exceptions in MailSendException"() {
        when:
        sender.send({ MimeMessage msg -> throw new RuntimeException("preparator failed") })

        then:
        MailSendException ex = thrown()
        ex.message.contains("preparator failed") || ex.cause.message.contains("preparator failed")
        0 * mockSesClient.sendEmail(_)
    }

    // -------------------------------------------------------------------------
    // send(SimpleMailMessage)
    // -------------------------------------------------------------------------

    def "send(SimpleMailMessage) converts to MimeMessage and sends"() {
        given:
        SimpleMailMessage simple = new SimpleMailMessage(
                from   : "from@example.com",
                to     : ["to@example.com"] as String[],
                subject: "Simple subject",
                text   : "Simple body"
        )

        when:
        sender.send(simple)

        then:
        1 * mockSesClient.sendEmail(_ as SendEmailRequest) >> SendEmailResponse.builder()
                .messageId("MSG-SIMPLE")
                .build()
    }

    def "send(SimpleMailMessage) uses defaultFrom when message has no From"() {
        given:
        SimpleMailMessage simple = new SimpleMailMessage(
                to     : ["to@example.com"] as String[],
                subject: "No from",
                text   : "Body"
                // from intentionally omitted
        )

        when:
        sender.send(simple)

        then:
        1 * mockSesClient.sendEmail(_ as SendEmailRequest) >> SendEmailResponse.builder()
                .messageId("MSG-DEFAULTFROM")
                .build()
    }

    def "send(SimpleMailMessage) throws when neither message nor defaultFrom is set"() {
        given:
        sender.sesConfiguration = new SesMailConfiguration(
                region     : "us-east-1",
                defaultFrom: ""           // no fallback
        )

        SimpleMailMessage simple = new SimpleMailMessage(
                to     : ["to@example.com"] as String[],
                subject: "No from anywhere",
                text   : "Body"
        )

        when:
        sender.send(simple)

        then:
        thrown(MailSendException)
        0 * mockSesClient.sendEmail(_)
    }

    def "send(SimpleMailMessage[]) sends every message"() {
        given:
        SimpleMailMessage[] messages = [
                new SimpleMailMessage(from: "f@x.com", to: ["a@x.com"] as String[], subject: "A", text: "a"),
                new SimpleMailMessage(from: "f@x.com", to: ["b@x.com"] as String[], subject: "B", text: "b"),
        ] as SimpleMailMessage[]

        when:
        sender.send(messages)

        then:
        2 * mockSesClient.sendEmail(_ as SendEmailRequest) >> SendEmailResponse.builder()
                .messageId("MSG-X")
                .build()
    }

    // -------------------------------------------------------------------------
    // Configuration Set attachment
    // -------------------------------------------------------------------------

    def "configurationSetName is attached to the SendEmailRequest when configured"() {
        given:
        sender.sesConfiguration = new SesMailConfiguration(
                region              : "us-east-1",
                defaultFrom         : "f@x.com",
                configurationSetName: "my-config-set"
        )

        MimeMessage mime = buildMime("f@x.com", "t@x.com", "Config set test", "Body")

        when:
        sender.send(mime)

        then:
        1 * mockSesClient.sendEmail({ SendEmailRequest req ->
            req.configurationSetName() == "my-config-set"
        } as SendEmailRequest) >> SendEmailResponse.builder()
                .messageId("MSG-CS")
                .build()
    }

    def "configurationSetName is omitted from the request when not configured"() {
        given:
        // config.configurationSetName is "" by default
        MimeMessage mime = buildMime("f@x.com", "t@x.com", "No config set", "Body")

        when:
        sender.send(mime)

        then:
        1 * mockSesClient.sendEmail({ SendEmailRequest req ->
            req.configurationSetName() == null
        } as SendEmailRequest) >> SendEmailResponse.builder()
                .messageId("MSG-NCS")
                .build()
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    def "SesV2Exception from the SDK is wrapped in MailSendException"() {
        given:
        MimeMessage mime = buildMime("f@x.com", "t@x.com", "Will fail", "Body")
        mockSesClient.sendEmail(_ as SendEmailRequest) >> {
            throw SesV2Exception.builder().message("SendingPausedException").build()
        }

        when:
        sender.send(mime)

        then:
        MailSendException ex = thrown()
        ex.message.contains("SES API error")
    }

    def "SesV2Exception with errorCode ConfigurationSetDoesNotExist produces a descriptive MailSendException"() {
        given:
        // AWS SDK v2 sesv2 does NOT have a ConfigurationSetDoesNotExistException subclass.
        // All service errors surface as SesV2Exception; the specific error is identified
        // by awsErrorDetails().errorCode() == 'ConfigurationSetDoesNotExist'.
        sender.sesConfiguration = new SesMailConfiguration(
                region              : "us-east-1",
                defaultFrom         : "f@x.com",
                configurationSetName: "missing-set"
        )

        MimeMessage mime = buildMime("f@x.com", "t@x.com", "Missing config set", "Body")
        mockSesClient.sendEmail(_ as SendEmailRequest) >> {
            throw SesV2Exception.builder()
                    .message("Configuration set does not exist: missing-set")
                    .awsErrorDetails(AwsErrorDetails.builder()
                            .errorCode("ConfigurationSetDoesNotExist")
                            .errorMessage("Configuration set does not exist: missing-set")
                            .build())
                    .build()
        }

        when:
        sender.send(mime)

        then:
        MailSendException ex = thrown()
        ex.message.contains("missing-set")
        ex.message.contains("ConfigurationSetDoesNotExist")
    }

    // -------------------------------------------------------------------------
    // destroy
    // -------------------------------------------------------------------------

    def "destroy closes the SES client"() {
        when:
        sender.destroy()

        then:
        1 * mockSesClient.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private MimeMessage buildMime(String from, String to, String subject, String text) {
        MimeMessage msg = sender.createMimeMessage()
        new org.springframework.mail.javamail.MimeMessageHelper(msg, false, "UTF-8").tap {
            it.from    = from
            it.setTo(to)
            it.subject = subject
            it.setText(text)
        }
        msg
    }
}
