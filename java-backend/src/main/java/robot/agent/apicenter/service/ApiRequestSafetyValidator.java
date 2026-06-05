package robot.agent.apicenter.service;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

@Component
public class ApiRequestSafetyValidator {

    public void validateRequestUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("请求URL格式不正确", exception);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("请求URL仅支持 HTTP/HTTPS");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("请求URL必须包含主机名");
        }
        validatePublicHost(host);
    }

    private void validatePublicHost(String host) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalizedHost) || normalizedHost.endsWith(".localhost")) {
            throw new IllegalArgumentException("禁止访问内网或本机地址");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isBlockedAddress(address)) {
                    throw new IllegalArgumentException("禁止访问内网或本机地址");
                }
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("请求URL主机解析失败", exception);
        }
    }

    private boolean isBlockedAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isCarrierGradeNat(bytes)
                || isCloudMetadata(bytes);
    }

    private boolean isCarrierGradeNat(byte[] bytes) {
        return bytes.length == 4 && unsigned(bytes[0]) == 100 && unsigned(bytes[1]) >= 64 && unsigned(bytes[1]) <= 127;
    }

    private boolean isCloudMetadata(byte[] bytes) {
        return bytes.length == 4 && unsigned(bytes[0]) == 169 && unsigned(bytes[1]) == 254 && unsigned(bytes[2]) == 169 && unsigned(bytes[3]) == 254;
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }
}
