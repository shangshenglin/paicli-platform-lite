package com.paicli.platform.server.web;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;

public final class NetworkPolicy {
    private NetworkPolicy() { }

    public static URI requirePublicHttpUrl(String value) {
        URI uri;
        try { uri = URI.create(value); }
        catch (Exception e) { throw new IllegalArgumentException("invalid URL"); }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("only http and https URLs are allowed");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("URL must have a host and no user info");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!isPublic(address)) throw new IllegalArgumentException("private or local network targets are blocked");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("URL host could not be resolved");
        }
        return uri;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = bytes[0] & 255;
            int b = bytes[1] & 255;
            return a != 0 && a != 10 && a != 127 && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && b == 168) && !(a == 198 && (b == 18 || b == 19))
                    && a < 224;
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 255;
            return (first & 0xfe) != 0xfc && !(first == 0xfe && (bytes[1] & 0xc0) == 0x80);
        }
        return false;
    }
}
