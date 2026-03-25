package grails.plugins.mail.aws

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.InitializingBean

import java.net.URL
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * SnsSignatureVerifier
 * ====================
 * Verifies the cryptographic signature that AWS SNS attaches to every HTTP
 * POST notification before the payload is trusted by SesSnsHandlerController.
 *
 * Why this is necessary
 * ----------------------
 * The /ses/sns endpoint is publicly reachable (AWS SNS must be able to POST
 * to it).  Without signature verification, any client can forge a request:
 *
 *   - Fake SubscriptionConfirmation: tricks the plugin into confirming a
 *     subscription to a rogue SNS topic, redirecting SES event data.
 *
 *   - Fake Bounce/Complaint Notification: causes the plugin to suppress
 *     legitimate recipient addresses, silently blocking future mail delivery.
 *
 * How SNS signing works
 * ----------------------
 * 1. AWS constructs a canonical string from selected message fields
 *    (varies by message type – see buildSigningString()).
 * 2. AWS signs the canonical string with an RSA private key and encodes
 *    the result as Base64 in the 'Signature' field.
 * 3. The corresponding X.509 public certificate URL is in 'SigningCertURL'.
 * 4. Verification: fetch the cert, extract the public key, verify the
 *    Base64-decoded Signature against the canonical string using SHA1withRSA.
 *
 * Certificate caching
 * --------------------
 * Certificates are fetched from AWS once per unique URL and cached in memory
 * (ConcurrentHashMap) for the lifetime of the application.  SNS rotates
 * certificates infrequently; caching avoids a remote HTTP call on every POST.
 *
 * Disabling for tests
 * --------------------
 * Set verifySignature = false (via grails.mail.ses.sns.verifySignature = false)
 * to bypass all verification.  Never set this in production.
 *
 * References
 * ----------
 * https://docs.aws.amazon.com/sns/latest/dg/sns-verify-signature-of-message.html
 */
@Slf4j
class SnsSignatureVerifier implements InitializingBean {

    /**
     * When false, signature verification is skipped entirely.
     * Injected from grails.mail.ses.sns.verifySignature (default: true).
     * Set false only in development / test environments.
     */
    boolean verifySignature = true

    /** In-memory certificate cache keyed by SigningCertURL. */
    private final Map<String, PublicKey> certCache = new ConcurrentHashMap<>()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    @Override
    void afterPropertiesSet() {
        if (!verifySignature) {
            log.warn "SnsSignatureVerifier: signature verification is DISABLED – do not use in production"
        }
    }

    /**
     * Verifies the SNS message signature.
     *
     * @param envelope  Parsed JSON of the top-level SNS envelope (grails JSONElement).
     * @throws SignatureVerificationException if the signature is invalid or cannot be checked.
     */
    void verify(def envelope) {
        if (!verifySignature) {
            log.debug "SnsSignatureVerifier: skipping verification (verifySignature=false)"
            return
        }

        String signingCertUrl = envelope.SigningCertURL as String
        String signature      = envelope.Signature      as String
        String messageType    = envelope.Type           as String

        if (!signingCertUrl || !signature) {
            throw new SignatureVerificationException(
                    "SNS envelope is missing SigningCertURL or Signature field")
        }

        // Guard: only accept certificates from genuine AWS SNS domains
        validateCertUrl(signingCertUrl)

        PublicKey publicKey = getPublicKey(signingCertUrl)

        String signingString = buildSigningString(messageType, envelope)
        log.debug "SNS signing string for type={}:\n{}", messageType, signingString

        boolean valid
        try {
            Signature sig = Signature.getInstance("SHA1withRSA")
            sig.initVerify(publicKey)
            sig.update(signingString.bytes)
            valid = sig.verify(Base64.decoder.decode(signature))
        } catch (Exception e) {
            throw new SignatureVerificationException(
                    "Signature verification threw an exception: ${e.message}", e)
        }

        if (!valid) {
            throw new SignatureVerificationException(
                    "SNS signature is invalid for messageType=${messageType}")
        }

        log.debug "SNS signature verified OK – type={}", messageType
    }

    // -------------------------------------------------------------------------
    // Signing string construction
    //
    // AWS specifies which fields are included and in what order for each
    // message type.  Fields must appear in the exact canonical order below;
    // each field name is followed by a newline, then the value, then a newline.
    // -------------------------------------------------------------------------

    protected static String buildSigningString(String messageType, def env) {
        switch (messageType) {
            case 'Notification':
                // Subject is optional per the AWS spec – omitted when absent,
                // included as-is when present. The standard null-skip in
                // buildString() handles this without special-casing.
                return buildString([
                        'Message'         , env.Message,
                        'MessageId'       , env.MessageId,
                        'Subject'         , env.Subject,
                        'Timestamp'       , env.Timestamp,
                        'TopicArn'        , env.TopicArn,
                        'Type'            , env.Type,
                ])

            case 'SubscriptionConfirmation':
            case 'UnsubscribeConfirmation':
                return buildString([
                        'Message'         , env.Message,
                        'MessageId'       , env.MessageId,
                        'SubscribeURL'    , env.SubscribeURL,
                        'Timestamp'       , env.Timestamp,
                        'Token'           , env.Token,
                        'TopicArn'        , env.TopicArn,
                        'Type'            , env.Type,
                ])

            default:
                throw new SignatureVerificationException(
                        "Cannot build signing string for unknown message type: ${messageType}")
        }
    }

    /**
     * Builds the canonical signing string from a flat list of [key, value, key, value, ...] pairs.
     * Keys whose value is null/empty are omitted unless listed in includeIfNull.
     * AWS spec: each included pair contributes two lines: the key, then the value.
     */
    private static String buildString(List kvPairs) {
        StringBuilder sb = new StringBuilder()
        for (int i = 0; i < kvPairs.size(); i += 2) {
            String key   = kvPairs[i] as String
            String value = kvPairs[i + 1] as String
            if (value == null) continue    // omit fields absent from the envelope
            sb.append(key).append('\n')
            sb.append(value).append('\n')
        }
        sb.toString()
    }

    // -------------------------------------------------------------------------
    // Certificate fetch and cache
    // -------------------------------------------------------------------------

    private PublicKey getPublicKey(String certUrl) {
        certCache.computeIfAbsent(certUrl) { url ->
            log.info "Fetching SNS signing certificate from: {}", url
            try {
                InputStream certStream = new URL(url).openStream()
                CertificateFactory cf  = CertificateFactory.getInstance("X.509")
                X509Certificate cert   = cf.generateCertificate(certStream) as X509Certificate
                log.info "SNS certificate fetched and cached – subject: {}",
                        cert.subjectX500Principal.name
                cert.publicKey
            } catch (Exception e) {
                throw new SignatureVerificationException(
                        "Failed to fetch SNS signing certificate from ${url}: ${e.message}", e)
            }
        }
    }

    /**
     * Rejects certificate URLs that do not belong to official AWS SNS domains.
     * This prevents an attacker from supplying a certificate URL they control,
     * which would allow them to sign arbitrary payloads.
     *
     * Valid AWS SNS signing cert URL pattern (from AWS documentation):
     *   https://sns.<region>.amazonaws.com/...pem
     */
    private static void validateCertUrl(String certUrl) {
        if (!certUrl.startsWith('https://')) {
            throw new SignatureVerificationException(
                    "SigningCertURL must use HTTPS: ${certUrl}")
        }
        // Must be a *.amazonaws.com host under the sns. subdomain
        URL parsed
        try {
            parsed = new URL(certUrl)
        } catch (Exception e) {
            throw new SignatureVerificationException(
                    "SigningCertURL is not a valid URL: ${certUrl}")
        }
        String host = parsed.host
        if (!host.startsWith('sns.') || !host.endsWith('.amazonaws.com')) {
            throw new SignatureVerificationException(
                    "SigningCertURL host is not a trusted AWS SNS domain: ${host}")
        }
        if (!certUrl.endsWith('.pem')) {
            throw new SignatureVerificationException(
                    "SigningCertURL does not point to a .pem file: ${certUrl}")
        }
    }

    // -------------------------------------------------------------------------
    // Exception type
    // -------------------------------------------------------------------------

    static class SignatureVerificationException extends RuntimeException {
        SignatureVerificationException(String msg)                   { super(msg) }
        SignatureVerificationException(String msg, Throwable cause)  { super(msg, cause) }
    }
}
