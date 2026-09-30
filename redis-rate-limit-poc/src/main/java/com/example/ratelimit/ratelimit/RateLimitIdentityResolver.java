package com.example.ratelimit.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.Policy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Decides what string identifies this request for quota purposes, per the matched policy.
 *
 * <p>The <em>policy's</em> configured identity strategy decides, not who happens to be logged in:
 * an {@code identity: IP} policy stays per-IP even when the caller sends credentials, and an
 * {@code identity: USER} policy falls back to the client IP when nobody is authenticated (so an
 * anonymous flood still cannot bypass the route).
 *
 * <p>Forwarded headers are read only when the socket peer is inside a configured trusted-proxy CIDR.
 * With no trusted proxies configured, a client sending {@code X-Forwarded-For} cannot forge a fresh
 * identity per request.
 *
 * <p><strong>Requirement on the trusted proxies:</strong> each one must <em>append</em> the peer it
 * observed to {@code X-Forwarded-For} (or overwrite the header outright). A proxy that forwards a
 * client-supplied header untouched destroys the right-to-left walk below, because the application
 * can no longer tell which entries its own proxies wrote. Strip inbound {@code X-Forwarded-For} and
 * {@code X-Real-IP} at the edge, then set them yourself.
 */
public class RateLimitIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(RateLimitIdentityResolver.class);

    private final List<CidrBlock> trustedProxies;

    public RateLimitIdentityResolver(RateLimitProperties properties) {
        this.trustedProxies = properties.getTrustedProxies().stream()
                .map(CidrBlock::parse)
                .toList();
    }

    public record Identity(String type, String value) {
    }

    public Identity resolve(HttpServletRequest request, Policy policy) {
        if (policy.identity() == RateLimitProperties.Identity.USER) {
            Authentication auth = authenticatedPrincipal();
            if (auth != null) {
                return new Identity(RateLimitProperties.Identity.USER.name(), auth.getName());
            }
            // No authenticated user: fall back to the client IP rather than skipping the limit.
            return new Identity(RateLimitProperties.Identity.IP.name(), clientIp(request));
        }
        return new Identity(RateLimitProperties.Identity.IP.name(), clientIp(request));
    }

    private Authentication authenticatedPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return auth;
    }

    String clientIp(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer == null) {
            return "unknown";
        }
        if (!isTrustedProxy(peer)) {
            // Direct peer is not a configured proxy: forwarded headers are not evidence of anything.
            return peer;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return realIpOrPeer(request, peer);
        }
        // Proxies append the peer they saw to X-Forwarded-For, so the rightmost entries are the
        // ones our own proxies wrote. Scan right to left, skipping trusted hops, and stop at the
        // first hop that is neither trusted nor a valid IP literal. Anything to the left of that
        // was supplied by the client and is never chosen, so a forged prefix cannot mint a fresh
        // rate-limit identity per request.
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String candidate = hops[i].trim();
            if (candidate.isEmpty() || isTrustedProxy(candidate)) {
                continue;
            }
            String canonical = canonicalIp(candidate);
            if (canonical != null) {
                return canonical;
            }
            // A hop that is not a canonical IP literal makes the rest of the chain untrustworthy.
            // Stop here and use the socket peer rather than continuing leftwards into client-supplied
            // text that would otherwise become a rate-limit identity.
            log.debug("peer {} is a trusted proxy but X-Forwarded-For hop '{}' is not an IP literal",
                    peer, candidate);
            return peer;
        }
        log.debug("peer {} is a trusted proxy but X-Forwarded-For held no untrusted hop", peer);
        return peer;
    }

    private String realIpOrPeer(HttpServletRequest request, String peer) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp == null || realIp.isBlank()) {
            return peer;
        }
        // X-Real-IP holds a single address, never a chain, so it is used only when it parses.
        String canonical = canonicalIp(realIp.trim());
        return canonical != null ? canonical : peer;
    }

    private boolean isTrustedProxy(String ip) {
        return trustedProxies.stream().anyMatch(cidr -> cidr.contains(ip));
    }

    /**
     * Returns the canonical dotted-quad form of an IPv4 address, including an IPv4-mapped IPv6
     * literal such as {@code ::ffff:127.0.0.1}, so every spelling of one host collapses onto one
     * identity. Returns null for anything that is not a canonical IP literal.
     */
    private static String canonicalIp(String value) {
        byte[] bytes;
        try {
            bytes = CidrBlock.parseLiteral(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
        // InetAddress returns 4 bytes for an IPv4-mapped literal such as ::ffff:127.0.0.1, so this
        // branch covers both plain IPv4 and the mapped form. Formatting from the bytes guarantees one
        // spelling per host regardless of how it arrived.
        if (bytes.length == 4) {
            return "%d.%d.%d.%d".formatted(bytes[0] & 0xff, bytes[1] & 0xff,
                    bytes[2] & 0xff, bytes[3] & 0xff);
        }
        // A genuine IPv6 literal keeps its own spelling, which is already unique per address.
        return value;
    }

    /**
     * IPv4/IPv6 CIDR matcher backed by {@link InetAddress}, so compressed IPv6 literals such as
     * {@code 2001:db8::1} parse correctly without a hand-rolled parser.
     */
    record CidrBlock(byte[] network, int prefix) {

        /**
         * Rejects anything that is not an IP literal, so {@link InetAddress} never attempts a DNS
         * lookup, and so header text cannot become an identity.
         */
        private static final Pattern LITERAL = Pattern.compile("[0-9a-fA-F:.%]+");

        /** Dotted quad only: four decimal groups, no leading zeros. Rejects "1", "1.2.3", "010.0.0.1". */
        private static final Pattern IPV4 = Pattern.compile(
                "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])"
                        + "(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}");

        static CidrBlock parse(String cidr) {
            String trimmed = cidr.trim();
            int slash = trimmed.indexOf('/');
            String addr = slash < 0 ? trimmed : trimmed.substring(0, slash);
            byte[] bytes = parseLiteral(addr);
            int bits = bytes.length * 8;
            int prefix;
            try {
                prefix = slash < 0 ? bits : Integer.parseInt(trimmed.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "rate-limit.trusted-proxies: bad prefix in CIDR '" + cidr + "'", e);
            }
            if (prefix < 0 || prefix > bits) {
                throw new IllegalStateException(
                        "rate-limit.trusted-proxies: prefix /" + prefix + " is invalid for '" + cidr
                                + "' (an IPv4 block takes /0-/32, an IPv6 block /0-/128)");
            }
            return new CidrBlock(bytes, prefix);
        }

        boolean contains(String ip) {
            byte[] bytes;
            try {
                bytes = parseLiteral(ip);
            } catch (IllegalArgumentException e) {
                return false;
            }
            if (bytes.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (bytes[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (bytes[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        /**
         * Package-private so the caller can validate a candidate before using it as an identity.
         *
         * <p>Strict on purpose. {@link InetAddress#getByName} accepts several non-literal forms that
         * would otherwise let header text become a rate-limit identity: a bare integer
         * ({@code 2130706433} → {@code 127.0.0.1}), a short dotted form ({@code 1.2.3} →
         * {@code 1.2.0.3}) and an IPv4-mapped IPv6 literal. Each distinct spelling hashes to a
         * distinct Redis key, so a client able to vary the spelling could mint unlimited keys. Only
         * canonical dotted-quad IPv4 and canonical IPv6 are accepted; a zone id ({@code %eth0}) is
         * rejected because it is not present on the wire in a forwarding header.
         */
        static byte[] parseLiteral(String ip) {
            if (!LITERAL.matcher(ip).matches()) {
                throw new IllegalArgumentException("not an IP literal: " + ip);
            }
            if (!ip.contains(":") && !IPV4.matcher(ip).matches()) {
                throw new IllegalArgumentException("not a canonical IPv4 address: " + ip);
            }
            try {
                return InetAddress.getByName(ip).getAddress();
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("not an IP address: " + ip, e);
            }
        }
    }
}
