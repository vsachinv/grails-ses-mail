package grails.plugins.mail.aws

import grails.testing.services.ServiceUnitTest
import spock.lang.Specification

import java.time.Instant

class SesBounceSuppressionServiceSpec extends Specification implements ServiceUnitTest<SesBounceSuppressionService> {

    def "suppressAddresses stores addresses and marks them as suppressed"() {
        when:
        service.suppressAddresses(['user@example.com', 'bad@domain.org'], 'BOUNCE', 'General', Instant.now())

        then:
        service.isSuppressed('user@example.com')
        service.isSuppressed('bad@domain.org')
        !service.isSuppressed('good@domain.org')
    }

    def "isSuppressed is case insensitive"() {
        given:
        service.suppressAddresses(['UPPER@EXAMPLE.COM'], 'BOUNCE', 'Permanent', Instant.now())

        expect:
        service.isSuppressed('upper@example.com')
        service.isSuppressed('UPPER@EXAMPLE.COM')
        service.isSuppressed('Upper@Example.Com')
    }

    def "unsuppress removes the address"() {
        given:
        service.suppressAddresses(['remove@me.com'], 'COMPLAINT', 'abuse', Instant.now())

        when:
        service.unsuppress('remove@me.com')

        then:
        !service.isSuppressed('remove@me.com')
    }

    def "filterSuppressed returns only non-suppressed addresses"() {
        given:
        service.suppressAddresses(['bounce@test.com'], 'BOUNCE', 'General', Instant.now())

        when:
        List<String> result = service.filterSuppressed(['good@test.com', 'bounce@test.com', 'also-good@test.com'])

        then:
        result == ['good@test.com', 'also-good@test.com']
    }

    def "getSuppressionRecord returns full record"() {
        given:
        Instant now = Instant.now()
        service.suppressAddresses(['check@record.com'], 'BOUNCE', 'NoEmail', now)

        when:
        SuppressedAddress record = service.getSuppressionRecord('check@record.com')

        then:
        record != null
        record.emailAddress    == 'check@record.com'
        record.suppressionType == 'BOUNCE'
        record.subtype         == 'NoEmail'
        record.suppressedAt    == now
    }

    def "listener is called when address is suppressed"() {
        given:
        List<SuppressedAddress> captured = []
        service.addSuppressListener { SuppressedAddress addr -> captured << addr }

        when:
        service.suppressAddresses(['listener@test.com'], 'COMPLAINT', 'fraud', Instant.now())

        then:
        captured.size() == 1
        captured[0].emailAddress == 'listener@test.com'
    }

    def "clearAll removes all suppressions"() {
        given:
        service.suppressAddresses(['a@a.com', 'b@b.com'], 'BOUNCE', 'General', Instant.now())

        when:
        service.clearAll()

        then:
        !service.isSuppressed('a@a.com')
        !service.isSuppressed('b@b.com')
        service.allSuppressed.isEmpty()
    }
}
