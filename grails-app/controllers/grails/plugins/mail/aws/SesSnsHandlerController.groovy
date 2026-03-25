package grails.plugins.mail.aws

import groovy.util.logging.Slf4j

import java.time.Instant

/**
 * SesSnsHandlerController
 * =======================
 * Receives AWS SNS HTTP POST notifications carrying SES bounce, complaint, and
 * delivery events.  All SES event types are routed through a single SNS topic
 * subscription, so this controller handles every message type in one endpoint.
 *
 * Endpoint
 * --------
 * The plugin ships its own UrlMappings (SesMailUrlMappings.groovy) that exposes:
 *
 *   POST /ses/sns
 *
 * This URL is registered automatically when the plugin is present.  No manual
 * UrlMappings entry is needed in the consuming application unless the default
 * path conflicts and needs to be overridden (see plugin.groovy for the config
 * key grails.mail.ses.sns.endpointPath).
 *
 * Action name
 * -----------
 * The single action is named 'handleSnsEvent' – not 'notify', which is a
 * reserved method on java.lang.Object and cannot be used as a Grails action.
 *
 * SNS message flow
 * ----------------
 * AWS SNS delivers three message types to this endpoint:
 *
 *   SubscriptionConfirmation  – sent once when the SNS subscription is first
 *                               created.  The controller visits the SubscribeURL
 *                               to confirm.  No further action is taken.
 *
 *   Notification              – the normal event delivery.  The SNS 'Message'
 *                               field contains a JSON-encoded SES notification
 *                               with a 'notificationType' of Bounce, Complaint,
 *                               or Delivery.
 *
 *   UnsubscribeConfirmation   – sent if someone unsubscribes the topic.  Logged
 *                               and ignored.
 *
 * AWS Signature Verification
 * --------------------------
 * Every SNS HTTP message is cryptographically signed by AWS using an RSA key
 * whose public certificate is fetched from a well-known AWS URL embedded in
 * the payload (SigningCertURL).  The plugin verifies this signature on every
 * inbound request using SnsSignatureVerifier before processing the payload.
 *
 * Why this matters for a generic plugin
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 * Without signature verification the /ses/sns endpoint is an open attack
 * surface: any client can POST a fake SubscriptionConfirmation (causing the
 * plugin to confirm a rogue SNS subscription and redirect outbound event data)
 * or a fake Bounce/Complaint notification (causing legitimate recipient
 * addresses to be permanently suppressed).  Verification ensures only genuine
 * AWS-signed messages are processed.
 *
 * The plugin's role in signing
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 * The plugin does NOT sign outbound mail – AWS SES handles that internally
 * using DKIM when you configure sending identities.  The signing handled here
 * is purely for INBOUND SNS messages: verifying that the POST came from AWS,
 * not from a spoofed source.
 *
 * Verification can be disabled for testing via:
 *   grails.mail.ses.sns.verifySignature = false   (default: true)
 */
@Slf4j
class SesSnsHandlerController {

    static allowedMethods = [handleSnsEvent: 'POST']

    SesBounceSuppressionService sesBounceSuppressionService
    SnsSignatureVerifier        snsSignatureVerifier

    /**
     * Single entry point for all SNS HTTP deliveries.
     *
     * SNS always sets Content-Type to 'text/plain' even though the body is
     * valid JSON, so we read the raw body and parse it ourselves.
     */
    def handleSnsEvent() {
        String rawBody = request.inputStream.text
        log.debug "SNS POST received – body length={}", rawBody.length()

        // ── 1. Parse envelope ────────────────────────────────────────────────
        def envelope
        try {
            envelope = grails.converters.JSON.parse(rawBody)
        } catch (Exception e) {
            log.error "SNS payload is not valid JSON: {}", e.message
            render status: 400, text: 'Bad Request – invalid JSON'
            return
        }

        // ── 2. Verify AWS signature ──────────────────────────────────────────
        // Rejects any POST that was not genuinely signed by AWS SNS.
        // Disable only in test environments via grails.mail.ses.sns.verifySignature=false.
        try {
            snsSignatureVerifier.verify(envelope)
        } catch (SnsSignatureVerifier.SignatureVerificationException e) {
            // verify() throws SignatureVerificationException for every failure
            // path: missing fields, untrusted cert URL, invalid RSA signature.
            log.error "SNS signature verification FAILED – request rejected: {}", e.message
            render status: 403, text: 'Forbidden – invalid SNS signature'
            return
        }

        // ── 3. Route by SNS message type ─────────────────────────────────────
        String messageType = envelope?.Type as String

        switch (messageType) {
            case 'SubscriptionConfirmation':
                handleSubscriptionConfirmation(envelope)
                break
            case 'Notification':
                handleNotification(envelope)
                break
            case 'UnsubscribeConfirmation':
                log.warn "SNS UnsubscribeConfirmation received – topic may have been unsubscribed"
                break
            default:
                log.warn "Unrecognised SNS message Type: '{}'", messageType
        }

        render status: 200, text: 'OK'
    }

    // -------------------------------------------------------------------------
    // SNS message type handlers
    // -------------------------------------------------------------------------

    /**
     * Visits the SubscribeURL to confirm the SNS HTTP subscription.
     * AWS requires this GET before it will deliver Notification messages.
     */
    private void handleSubscriptionConfirmation(def envelope) {
        String subscribeUrl = envelope.SubscribeURL as String
        if (!subscribeUrl) {
            log.warn "SubscriptionConfirmation missing SubscribeURL – cannot confirm"
            return
        }
        // Validate the URL is an AWS domain before following it
        if (!subscribeUrl.startsWith('https://sns.') || !subscribeUrl.contains('.amazonaws.com/')) {
            log.error "SubscribeURL does not look like an AWS SNS URL – rejected: {}", subscribeUrl
            return
        }
        log.info "Confirming SNS subscription: {}", subscribeUrl
        try {
            new URL(subscribeUrl).text
            log.info "SNS subscription confirmed successfully"
        } catch (Exception e) {
            log.error "Failed to confirm SNS subscription: {}", e.message, e
        }
    }

    /**
     * Unwraps the SNS Notification envelope and routes the inner SES event.
     * The SES payload is JSON-encoded as a string inside the SNS 'Message' field.
     */
    private void handleNotification(def envelope) {
        def sesEvent
        try {
            sesEvent = grails.converters.JSON.parse(envelope.Message as String)
        } catch (Exception e) {
            log.error "Could not parse SNS Message field as SES JSON: {}", e.message
            return
        }

        String notificationType = sesEvent.notificationType as String
        log.debug "SES notificationType={}", notificationType

        switch (notificationType) {
            case 'Bounce':
                handleBounce(sesEvent)
                break
            case 'Complaint':
                handleComplaint(sesEvent)
                break
            case 'Delivery':
                handleDelivery(sesEvent)
                break
            default:
                log.warn "Unhandled SES notificationType: '{}'", notificationType
        }
    }

    // -------------------------------------------------------------------------
    // SES event handlers
    // -------------------------------------------------------------------------

    /**
     * Processes a SES Bounce event.
     * Only Permanent bounces are suppressed – Transient bounces (e.g. mailbox
     * full) are logged but not suppressed, since the address may recover.
     */
    private void handleBounce(def sesEvent) {
        def bounce            = sesEvent.bounce
        String bounceType     = bounce?.bounceType    as String   // Permanent / Transient / Undetermined
        String bounceSubtype  = bounce?.bounceSubType as String   // General / NoEmail / Suppressed / etc.
        List<String> addresses = bounce?.bouncedRecipients
                ?.collect { it.emailAddress as String } ?: []

        log.warn "SES Bounce [{}/{}] – {} address(es): {}",
                bounceType, bounceSubtype, addresses.size(), addresses

        if (bounceType == 'Permanent') {
            sesBounceSuppressionService.suppressAddresses(
                    addresses, 'BOUNCE', bounceSubtype, Instant.now())
        } else {
            log.info "Transient/Undetermined bounce – not suppressing: {}", addresses
        }
    }

    /**
     * Processes a SES Complaint event.
     * All complaints result in suppression regardless of feedback type, because
     * continuing to mail a user who filed a spam complaint risks the account's
     * SES sending reputation.
     */
    private void handleComplaint(def sesEvent) {
        def complaint         = sesEvent.complaint
        String feedbackType   = complaint?.complaintFeedbackType as String ?: 'unknown'
        List<String> addresses = complaint?.complainedRecipients
                ?.collect { it.emailAddress as String } ?: []

        log.warn "SES Complaint [{}] – {} address(es): {}",
                feedbackType, addresses.size(), addresses

        sesBounceSuppressionService.suppressAddresses(
                addresses, 'COMPLAINT', feedbackType, Instant.now())
    }

    /**
     * Processes a SES Delivery event.
     * Logged for audit purposes only; no suppression action is taken.
     */
    private void handleDelivery(def sesEvent) {
        List<String> delivered = sesEvent.delivery?.recipients?.collect { it as String } ?: []
        log.info "SES Delivery confirmed – {} recipient(s): {}", delivered.size(), delivered
    }
}
