package grails.plugins.mail.aws

import groovy.util.logging.Slf4j

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory implementation of {@link BounceSuppressionStore}.
 *
 * Suitable for single-node deployments or as a fallback when no custom
 * implementation is provided by the consuming application.
 *
 * For clustered deployments, provide your own {@link BounceSuppressionStore}
 * backed by Redis, Hazelcast, or a database and register it as a Spring bean
 * named {@code bounceSuppressionStore}.
 */
@Slf4j
class InMemoryBounceSuppressionStore implements BounceSuppressionStore {

    private final Map<String, SuppressedAddress> suppressionList = new ConcurrentHashMap<>()
    private final List<Closure> suppressListeners = [].asSynchronized() as List<Closure>

    @Override
    void suppressAddresses(List<String> addresses, String type, String subtype, Instant when) {
        for (String addr : addresses) {
            SuppressedAddress record = new SuppressedAddress(
                    emailAddress   : addr.trim().toLowerCase(),
                    suppressionType: type,
                    subtype        : subtype,
                    suppressedAt   : when,
                    reason         : "${type}/${subtype}"
            )
            suppressionList[record.emailAddress] = record
            log.warn "Address suppressed [{}]: {}", type, addr
            for (Closure listener : suppressListeners) {
                listener.call(record)
            }
        }
    }

    @Override
    void unsuppress(String emailAddress) {
        if (suppressionList.remove(emailAddress?.trim()?.toLowerCase())) {
            log.info "Address unsuppressed: {}", emailAddress
        }
    }

    @Override
    boolean isSuppressed(String emailAddress) {
        suppressionList.containsKey(emailAddress?.trim()?.toLowerCase())
    }

    @Override
    SuppressedAddress getSuppressionRecord(String emailAddress) {
        suppressionList[emailAddress?.trim()?.toLowerCase()]
    }

    @Override
    List<String> filterSuppressed(List<String> addresses) {
        addresses.findAll { !isSuppressed(it) }
    }

    @Override
    Map<String, SuppressedAddress> getAllSuppressed() {
        Collections.unmodifiableMap(suppressionList)
    }

    @Override
    void clearAll() {
        suppressionList.clear()
    }

    @Override
    void addSuppressListener(Closure listener) {
        suppressListeners << listener
    }
}