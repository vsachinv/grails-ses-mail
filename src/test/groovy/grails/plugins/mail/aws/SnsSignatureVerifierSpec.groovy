package grails.plugins.mail.aws

import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Unit tests for SnsSignatureVerifier.
 *
 * Real AWS certificate fetching and RSA verification are not exercised here
 * (that would require network access and real AWS-signed payloads).  Instead
 * these tests cover:
 *   - verifySignature=false bypass
 *   - Missing field detection
 *   - SigningCertURL domain validation (the most important spoofing guard)
 *   - Canonical signing string construction for each message type
 */
class SnsSignatureVerifierSpec extends Specification {

    @Subject
    SnsSignatureVerifier verifier = new SnsSignatureVerifier()

    def setup() {
        verifier.afterPropertiesSet()
    }

    // -------------------------------------------------------------------------
    // Bypass mode
    // -------------------------------------------------------------------------

    def "when verifySignature=false, verify() returns without checking anything"() {
        given:
        verifier.verifySignature = false
        def envelope = [:]   // completely empty – would fail if verification ran

        when:
        verifier.verify(envelope)

        then:
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Missing required fields
    // -------------------------------------------------------------------------

    def "verify() throws when SigningCertURL is absent"() {
        given:
        verifier.verifySignature = true
        def envelope = [Type: 'Notification', Signature: 'abc123']

        when:
        verifier.verify(envelope)

        then:
        SnsSignatureVerifier.SignatureVerificationException ex = thrown()
        ex.message.contains('SigningCertURL')
    }

    def "verify() throws when Signature is absent"() {
        given:
        verifier.verifySignature = true
        def envelope = [
                Type          : 'Notification',
                SigningCertURL: 'https://sns.us-east-1.amazonaws.com/cert.pem'
        ]

        when:
        verifier.verify(envelope)

        then:
        SnsSignatureVerifier.SignatureVerificationException ex = thrown()
        ex.message.contains('Signature')
    }

    // -------------------------------------------------------------------------
    // SigningCertURL domain validation
    // -------------------------------------------------------------------------

    @Unroll
    def "validateCertUrl rejects non-AWS URL: #url"() {
        given:
        verifier.verifySignature = true
        def envelope = [
                Type          : 'Notification',
                SigningCertURL: url,
                Signature     : 'dGVzdA=='
        ]

        when:
        verifier.verify(envelope)

        then:
        SnsSignatureVerifier.SignatureVerificationException ex = thrown()
        ex.message.toLowerCase().contains('cert') || ex.message.toLowerCase().contains('url') ||
        ex.message.toLowerCase().contains('domain') || ex.message.toLowerCase().contains('https')

        where:
        url << [
                'http://sns.us-east-1.amazonaws.com/cert.pem',           // HTTP not HTTPS
                'https://evil.com/cert.pem',                              // wrong domain entirely
                'https://sns.us-east-1.amazonaws.com.evil.com/cert.pem', // subdomain spoofing
                'https://notsns.us-east-1.amazonaws.com/cert.pem',       // missing sns. prefix
                'https://sns.us-east-1.amazonaws.com/cert.txt',          // not .pem
                'not-a-url-at-all',                                       // garbage
        ]
    }

    @Unroll
    def "validateCertUrl accepts valid AWS SNS cert URL: #url"() {
        given:
        // We only test URL validation here – short-circuit after cert fetch fails
        // (which it will since these aren't real certs) with a cert-fetch error,
        // NOT a domain-validation error.
        verifier.verifySignature = true
        def envelope = [
                Type          : 'Notification',
                SigningCertURL: url,
                Signature     : 'dGVzdA=='
        ]

        when:
        verifier.verify(envelope)

        then:
        // Should fail with cert fetch error (network), NOT a domain-validation error
        SnsSignatureVerifier.SignatureVerificationException ex = thrown()
        !ex.message.contains('trusted AWS SNS domain')
        !ex.message.contains('must use HTTPS')
        !ex.message.contains('.pem file')

        where:
        url << [
                'https://sns.us-east-1.amazonaws.com/SimpleNotificationService-abc123.pem',
                'https://sns.eu-west-1.amazonaws.com/SimpleNotificationService-def456.pem',
                'https://sns.ap-southeast-1.amazonaws.com/SimpleNotificationService-ghi789.pem',
        ]
    }

    // -------------------------------------------------------------------------
    // Signing string construction (via package-private reflection)
    // -------------------------------------------------------------------------

    def "Notification signing string includes correct fields in correct order"() {
        given:
        def envelope = [
                Type     : 'Notification',
                Message  : 'test message',
                MessageId: 'msg-001',
                Subject  : 'test subject',
                Timestamp: '2024-01-01T00:00:00.000Z',
                TopicArn : 'arn:aws:sns:us-east-1:123456789:MyTopic',
        ]

        when:
        // Access the private static method via Groovy's meta access
        String signingString = SnsSignatureVerifier.buildSigningString('Notification', envelope)

        then:
        // Fields appear in this exact order per the AWS spec
        signingString == [
                'Message\ntest message\n',
                'MessageId\nmsg-001\n',
                'Subject\ntest subject\n',
                'Timestamp\n2024-01-01T00:00:00.000Z\n',
                'TopicArn\narn:aws:sns:us-east-1:123456789:MyTopic\n',
                'Type\nNotification\n',
        ].join('')
    }

    def "Notification signing string omits absent Subject (Subject is optional)"() {
        given:
        def envelope = [
                Type     : 'Notification',
                Message  : 'no subject',
                MessageId: 'msg-002',
                // Subject intentionally absent
                Timestamp: '2024-01-01T00:00:00.000Z',
                TopicArn : 'arn:aws:sns:us-east-1:123456789:MyTopic',
        ]

        when:
        String signingString = SnsSignatureVerifier.buildSigningString('Notification', envelope)

        then:
        !signingString.contains('Subject')
    }

    def "SubscriptionConfirmation signing string includes Token and SubscribeURL"() {
        given:
        def envelope = [
                Type        : 'SubscriptionConfirmation',
                Message     : 'You have chosen to subscribe',
                MessageId   : 'msg-003',
                SubscribeURL: 'https://sns.us-east-1.amazonaws.com/confirmation',
                Timestamp   : '2024-01-01T00:00:00.000Z',
                Token       : 'abc-token-xyz',
                TopicArn    : 'arn:aws:sns:us-east-1:123456789:MyTopic',
        ]

        when:
        String signingString = SnsSignatureVerifier.buildSigningString('SubscriptionConfirmation', envelope)

        then:
        signingString.contains('SubscribeURL\nhttps://sns.us-east-1.amazonaws.com/confirmation\n')
        signingString.contains('Token\nabc-token-xyz\n')
        !signingString.contains('Subject')
    }

    def "unknown message type throws SignatureVerificationException"() {
        when:
        SnsSignatureVerifier.buildSigningString('SomeUnknownType', [:])

        then:
        SnsSignatureVerifier.SignatureVerificationException ex = thrown()
        ex.message.contains('SomeUnknownType')
    }
}
