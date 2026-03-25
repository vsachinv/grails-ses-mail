package grails.plugins.mail.aws

import spock.lang.Specification

import java.time.Instant

class InMemoryBounceSuppressionStoreSpec extends Specification {

    BounceSuppressionStore store = new InMemoryBounceSuppressionStore()

    def "suppressAddresses stores addresses and marks them as suppressed"() {
        when:
        store.suppressAddresses(['user@example.com', 'bad@domain.org'], 'BOUNCE', 'General', Instant.now())

        then:
        store.isSuppressed('user@example.com')
        store.isSuppressed('bad@domain.org')
        !store.isSuppressed('good@domain.org')
    }

    def "isSuppressed is case insensitive"() {
        given:
        store.suppressAddresses(['UPPER@EXAMPLE.COM'], 'BOUNCE', 'Permanent', Instant.now())

        expect:
        store.isSuppressed('upper@example.com')
        store.isSuppressed('UPPER@EXAMPLE.COM')
        store.isSuppressed('Upper@Example.Com')
    }

    def "unsuppress removes the address"() {
        given:
        store.suppressAddresses(['remove@me.com'], 'COMPLAINT', 'abuse', Instant.now())

        when:
        store.unsuppress('remove@me.com')

        then:
        !store.isSuppressed('remove@me.com')
    }

    def "filterSuppressed returns only non-suppressed addresses"() {
        given:
        store.suppressAddresses(['bounce@test.com'], 'BOUNCE', 'General', Instant.now())

        when:
        List<String> result = store.filterSuppressed(['good@test.com', 'bounce@test.com', 'also-good@test.com'])

        then:
        result == ['good@test.com', 'also-good@test.com']
    }

    def "getSuppressionRecord returns full record"() {
        given:
        Instant now = Instant.now()
        store.suppressAddresses(['check@record.com'], 'BOUNCE', 'NoEmail', now)

        when:
        SuppressedAddress record = store.getSuppressionRecord('check@record.com')

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
        store.addSuppressListener { SuppressedAddress addr -> captured << addr }

        when:
        store.suppressAddresses(['listener@test.com'], 'COMPLAINT', 'fraud', Instant.now())

        then:
        captured.size() == 1
        captured[0].emailAddress == 'listener@test.com'
    }

    def "clearAll removes all suppressions"() {
        given:
        store.suppressAddresses(['a@a.com', 'b@b.com'], 'BOUNCE', 'General', Instant.now())

        when:
        store.clearAll()

        then:
        !store.isSuppressed('a@a.com')
        !store.isSuppressed('b@b.com')
        store.allSuppressed.isEmpty()
    }
}