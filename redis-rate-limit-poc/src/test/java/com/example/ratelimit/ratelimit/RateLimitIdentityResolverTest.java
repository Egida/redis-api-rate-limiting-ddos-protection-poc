package com.example.ratelimit.ratelimit;

import java.time.Duration;
import java.util.List;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.Identity;
import com.example.ratelimit.config.RateLimitProperties.Policy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Identity resolution: policy-driven strategy, proxy trust, and no trust when none is configured. */
class RateLimitIdentityResolverTest {

    private static final Policy IP_POLICY = new Policy("products-read", "GET", "/api/products", 100,
            Duration.ofMinutes(1), Identity.IP, null);
    private static final Policy USER_POLICY = new Policy("order-create", "POST", "/api/orders", 30,
            Duration.ofMinutes(1), Identity.USER, null);

    private static RateLimitProperties properties(String... trusted) {
        var props = new RateLimitProperties();
        props.setTrustedProxies(List.of(trusted));
        return props;
    }

    @Test
    void ignoresForwardedHeadersWhenNoProxyTrusted() {
        var resolver = new RateLimitIdentityResolver(properties());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        assertThat(resolver.clientIp(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void usesForwardedHeaderOnlyFromTrustedProxy() {
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));
        MockHttpServletRequest trusted = new MockHttpServletRequest();
        trusted.setRemoteAddr("10.0.0.5");
        trusted.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.5");
        assertThat(resolver.clientIp(trusted)).isEqualTo("203.0.113.7");

        MockHttpServletRequest untrusted = new MockHttpServletRequest();
        untrusted.setRemoteAddr("198.51.100.4");
        untrusted.addHeader("X-Forwarded-For", "203.0.113.7");
        assertThat(resolver.clientIp(untrusted)).isEqualTo("198.51.100.4");
    }

    @Test
    void ignoresAClientForgedLeftmostHopInAProxyChain() {
        // The attack: the client sends its own X-Forwarded-For, the trusted proxy appends the peer
        // it actually saw. Scanning must start at the right, so the forged left entry is discarded.
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8, 203.0.113.7, 10.0.0.5");

        assertThat(resolver.clientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void aForgedHopCannotBeReusedToEscapeTheLimit() {
        // Same forged prefix, different every attempt: the resolved identity must not move.
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));
        for (String forged : List.of("1.1.1.1", "2.2.2.2", "3.3.3.3")) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.5");
            request.addHeader("X-Forwarded-For", forged + ", 203.0.113.7, 10.0.0.5");
            assertThat(resolver.clientIp(request)).isEqualTo("203.0.113.7");
        }
    }

    @Test
    void picksTheNearestUntrustedHopAcrossMultipleTrustedProxies() {
        // client -> 10.0.0.7 (edge) -> 10.0.0.9 (inner) -> app. Both hops are trusted, so the scan
        // passes them and stops at the real client.
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.7, 10.0.0.9");

        assertThat(resolver.clientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void stopsAtTheNearestUntrustedHopEvenWhenAnOuterProxyIsTrusted() {
        // Only 10.0.0.0/28 (10.0.0.0-15) is trusted, so 10.0.0.200 is a genuine hop boundary and the
        // 203.0.113.x address to its left, whatever wrote it, must not be chosen.
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/28"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.200, 10.0.0.9");

        assertThat(resolver.clientIp(request)).isEqualTo("10.0.0.200");
    }

    @Test
    void blankAndEmptyHopsFallBackToThePeer() {
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));

        MockHttpServletRequest empty = new MockHttpServletRequest();
        empty.setRemoteAddr("10.0.0.5");
        empty.addHeader("X-Forwarded-For", "   ");
        assertThat(resolver.clientIp(empty)).isEqualTo("10.0.0.5");

        MockHttpServletRequest onlyCommas = new MockHttpServletRequest();
        onlyCommas.setRemoteAddr("10.0.0.5");
        onlyCommas.addHeader("X-Forwarded-For", ", , ,");
        assertThat(resolver.clientIp(onlyCommas)).isEqualTo("10.0.0.5");

        MockHttpServletRequest absent = new MockHttpServletRequest();
        absent.setRemoteAddr("10.0.0.5");
        assertThat(resolver.clientIp(absent)).isEqualTo("10.0.0.5");
    }

    @Test
    void anAllTrustedChainFallsBackToThePeer() {
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "10.0.0.7, 10.0.0.9, 10.0.0.5");

        assertThat(resolver.clientIp(request)).isEqualTo("10.0.0.5");
    }

    @Test
    void aMalformedHopNeverBecomesAnIdentity() {
        // Arbitrary header text must not create rate-limit keys. Scanning stops at the bad hop and
        // uses the socket peer instead of continuing left into client-controlled text.
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "1.2.3.4, not-an-ip, 10.0.0.5");

        assertThat(resolver.clientIp(request)).isEqualTo("10.0.0.5");
    }

    @Test
    void malformedHeaderTextCannotMintUnlimitedDistinctKeys() {
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));
        // Every value is distinct, yet all of them must collapse onto the same identity.
        for (String junk : List.of("attacker-1", "attacker-2", "<script>", "999.999.999.999")) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.5");
            request.addHeader("X-Forwarded-For", junk + ", 10.0.0.5");
            assertThat(resolver.clientIp(request)).isEqualTo("10.0.0.5");
        }
    }

    @Test
    void nonCanonicalIpSpellingsAreRejected() {
        // InetAddress.getByName accepts all of these, but each spelling would hash to a different
        // Redis key, letting a caller mint unlimited identities for one host. Only canonical forms
        // are accepted; the rest fall back to the socket peer.
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));
        for (String spelling : List.of("2130706433", "1.2.3", "010.0.0.1", "127.1",
                "0x7f000001", "2001:db8::1%eth0")) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.5");
            request.addHeader("X-Forwarded-For", spelling + ", 10.0.0.5");
            assertThat(resolver.clientIp(request))
                    .as("non-canonical '%s' must not become an identity", spelling)
                    .isEqualTo("10.0.0.5");
        }
    }

    @Test
    void ipv4MappedIpv6CollapsesOntoItsDottedQuadForm() {
        // ::ffff:127.0.0.1 addresses the same host as 127.0.0.1 and must not be a second identity.
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "::ffff:127.0.0.1, 10.0.0.5");
        assertThat(resolver.clientIp(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void canonicalIpv4AndIpv6AreStillAccepted() {
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));
        for (String canonical : List.of("203.0.113.7", "0.0.0.0", "255.255.255.255",
                "2001:db8::1", "::1")) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.5");
            request.addHeader("X-Forwarded-For", canonical + ", 10.0.0.5");
            assertThat(resolver.clientIp(request))
                    .as("canonical '%s' must still resolve", canonical)
                    .isEqualTo(canonical);
        }
    }

    @Test
    void distinctSpellingsOfOneAddressProduceAtMostTwoIdentities() {
        // The concrete attack: if every spelling resolved to itself, an attacker could rotate them
        // for a fresh allowance per request while still addressing one host. Canonical dotted quads
        // and IPv4-mapped IPv6 must collapse; everything else falls back to the peer.
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));
        var seen = new java.util.TreeSet<String>();
        for (String spelling : List.of("127.0.0.1", "2130706433", "127.1", "0x7f000001",
                "::ffff:127.0.0.1")) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.5");
            request.addHeader("X-Forwarded-For", spelling + ", 10.0.0.5");
            seen.add(resolver.clientIp(request));
        }
        assertThat(seen).containsExactly("10.0.0.5", "127.0.0.1");
    }

    @Test
    void aMalformedRealIpHeaderFallsBackToThePeer() {
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Real-IP", "spoofed-value");
        assertThat(resolver.clientIp(request)).isEqualTo("10.0.0.5");
    }

    @Test
    void anUntrustedPeerCannotInfluenceIdentityWithEitherHeader() {
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.4");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("X-Real-IP", "203.0.113.8");
        assertThat(resolver.clientIp(request)).isEqualTo("198.51.100.4");
    }

    @Test
    void ignoresClientPrependedForwardedForValues() {
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        // The client supplied the leftmost value; the trusted proxy appended the real peer.
        request.addHeader("X-Forwarded-For", "198.51.100.99, 203.0.113.7, 10.0.0.6");

        assertThat(resolver.clientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void usesRealIpHeaderFromTrustedProxy() {
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.0/8"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Real-IP", "203.0.113.8");
        assertThat(resolver.clientIp(request)).isEqualTo("203.0.113.8");
    }

    @Test
    void ipv6ProxyTrustRespectsCompressedLiteralsAndPrefixLength() {
        var resolver = new RateLimitIdentityResolver(properties("2001:db8::/64"));

        MockHttpServletRequest inside = new MockHttpServletRequest();
        inside.setRemoteAddr("2001:db8::1");
        inside.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(resolver.clientIp(inside)).as("2001:db8::1 is inside 2001:db8::/64").isEqualTo("203.0.113.9");

        MockHttpServletRequest samePrefixButDifferentGroup = new MockHttpServletRequest();
        // Same first 64 bits of prefix, last group differs: only a correct IPv6 parser gets this right.
        samePrefixButDifferentGroup.setRemoteAddr("2001:db8::abcd");
        samePrefixButDifferentGroup.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(resolver.clientIp(samePrefixButDifferentGroup)).isEqualTo("203.0.113.9");

        MockHttpServletRequest outside = new MockHttpServletRequest();
        outside.setRemoteAddr("2001:db9::1");
        outside.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(resolver.clientIp(outside)).as("2001:db9::1 is outside 2001:db8::/64")
                .isEqualTo("2001:db9::1");
    }

    @Test
    void bareCidrMeansSingleAddress() {
        var resolver = new RateLimitIdentityResolver(properties("10.0.0.5"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Real-IP", "203.0.113.3");
        assertThat(resolver.clientIp(request)).isEqualTo("203.0.113.3");

        MockHttpServletRequest sibling = new MockHttpServletRequest();
        sibling.setRemoteAddr("10.0.0.6");
        sibling.addHeader("X-Real-IP", "203.0.113.3");
        assertThat(resolver.clientIp(sibling)).isEqualTo("10.0.0.6");
    }

    @Test
    void rejectsMalformedCidrAtStartup() {
        assertThatThrownBy(() -> new RateLimitIdentityResolver(properties("not-an-ip/24")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitIdentityResolver(properties("10.0.0.0/99")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rate-limit.trusted-proxies");
    }

    @Test
    void ipPolicyStaysPerIpEvenWhenTheCallerIsAuthenticated() {
        var resolver = new RateLimitIdentityResolver(properties());
        authenticate("alice");
        var request = new MockHttpServletRequest("GET", "/api/products");
        request.setRemoteAddr("203.0.113.9");

        var identity = resolver.resolve(request, IP_POLICY);
        assertThat(identity.type()).isEqualTo("IP");
        assertThat(identity.value()).isEqualTo("203.0.113.9");
    }

    @Test
    void userPolicyUsesThePrincipalName() {
        var resolver = new RateLimitIdentityResolver(properties());
        authenticate("alice");
        var request = new MockHttpServletRequest("POST", "/api/orders");
        request.setRemoteAddr("203.0.113.9");

        var identity = resolver.resolve(request, USER_POLICY);
        assertThat(identity.type()).isEqualTo("USER");
        assertThat(identity.value()).isEqualTo("alice");
    }

    @Test
    void userPolicyFallsBackToIpWhenNobodyIsAuthenticated() {
        var resolver = new RateLimitIdentityResolver(properties());
        SecurityContextHolder.clearContext();
        var request = new MockHttpServletRequest("POST", "/api/orders");
        request.setRemoteAddr("203.0.113.9");

        var identity = resolver.resolve(request, USER_POLICY);
        assertThat(identity.type()).as("anonymous traffic must still be limited").isEqualTo("IP");
        assertThat(identity.value()).isEqualTo("203.0.113.9");
    }

    @Test
    void anonymousAuthenticationTokenIsTreatedAsAnonymous() {
        var resolver = new RateLimitIdentityResolver(properties());
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        var request = new MockHttpServletRequest("POST", "/api/orders");
        request.setRemoteAddr("203.0.113.9");

        assertThat(resolver.resolve(request, USER_POLICY).value()).isEqualTo("203.0.113.9");
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String name) {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(name, "n/a", List.of()));
    }
}
