package io.github.gouz.pdfj.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * DNS resolution keeping only public addresses for the hosts a document is fetching (see {@link Remote}):
 * the check happens on every resolution, so a redirect or a DNS answer that changes cannot reach a
 * private address. Other lookups are left alone.
 */
public final class GuardedResolverProvider extends InetAddressResolverProvider {
    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                Stream<InetAddress> all = builtin.lookupByName(host, policy);
                if (!Remote.GUARDED.containsKey(host.toLowerCase(Locale.ROOT))) return all;
                List<InetAddress> pub = all.filter(Remote::isPublic).toList();
                if (pub.isEmpty()) throw new UnknownHostException(host + ": no public address");
                return pub.stream();
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "pdfj-guarded";
    }
}
