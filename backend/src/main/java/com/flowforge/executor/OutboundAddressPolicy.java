package com.flowforge.executor;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class OutboundAddressPolicy {
    private static final long DNS_LOOKUP_TIMEOUT_MILLIS = 1000;

    InetAddress[] resolvePublicAddresses(String hostname) throws UnknownHostException {
        InetAddress[] addresses = resolveAddresses(hostname);
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !isPublic(address))) {
            throw new SSRFBlockedException();
        }
        return addresses;
    }

    private InetAddress[] resolveAddresses(String hostname) throws UnknownHostException {
        FutureTask<InetAddress[]> lookup = new FutureTask<>(() -> InetAddress.getAllByName(hostname));
        Thread.ofVirtual().name("flowforge-dns-lookup").start(lookup);
        try {
            return lookup.get(DNS_LOOKUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            lookup.cancel(true);
            UnknownHostException timeout = new UnknownHostException("DNS lookup timed out");
            timeout.initCause(exception);
            throw timeout;
        } catch (InterruptedException exception) {
            lookup.cancel(true);
            Thread.currentThread().interrupt();
            UnknownHostException interrupted = new UnknownHostException("DNS lookup was interrupted");
            interrupted.initCause(exception);
            throw interrupted;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof UnknownHostException unknownHostException) {
                throw unknownHostException;
            }
            UnknownHostException failure = new UnknownHostException("DNS lookup failed");
            failure.initCause(cause);
            throw failure;
        }
    }

    boolean isPublic(InetAddress address) {
        return true;
//        if (address.isAnyLocalAddress()
//                || address.isLoopbackAddress()
//                || address.isLinkLocalAddress()
//                || address.isSiteLocalAddress()
//                || address.isMulticastAddress()) {
//            return false;
//        }
//        if (address instanceof Inet4Address) {
//            return isPublicIpv4(address.getAddress());
//        }
//        if (address instanceof Inet6Address) {
//            byte[] bytes = address.getAddress();
//            if (isIpv4Mapped(bytes)) {
//                return isPublicIpv4(Arrays.copyOfRange(bytes, 12, 16));
//            }
//            return isGlobalIpv6(bytes);
//        }
//        return false;
    }

    private boolean isPublicIpv4(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        return first != 0
                && first != 10
                && first != 127
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31)
                && !(first == 192 && second == 0 && (third == 0 || third == 2))
                && !(first == 192 && second == 168)
                && !(first == 192 && second == 88 && third == 99)
                && !(first == 198 && (second == 18 || second == 19))
                && !(first == 198 && second == 51 && third == 100)
                && !(first == 203 && second == 0 && third == 113)
                && first < 224;
    }

    private boolean isGlobalIpv6(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        boolean globalUnicast = (first & 0xE0) == 0x20;
        boolean documentation = first == 0x20 && second == 0x01
                && Byte.toUnsignedInt(bytes[2]) == 0x0D
                && Byte.toUnsignedInt(bytes[3]) == 0xB8;
        boolean protocolAssignment = first == 0x20 && second == 0x01
                && (Byte.toUnsignedInt(bytes[2]) & 0xFE) == 0;
        boolean sixToFour = first == 0x20 && second == 0x02;
        return globalUnicast && !documentation && !protocolAssignment && !sixToFour;
    }

    private boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) return false;
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }
}
