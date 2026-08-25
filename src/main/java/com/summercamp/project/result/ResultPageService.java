package com.summercamp.project.result;

import com.summercamp.project.config.ResultPageProperties;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ResultPageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResultPageService.class);
    private static final int MAX_FIELD_LENGTH = 2_000;

    private final ResultPageProperties properties;
    private final Clock clock;
    private final Supplier<String> idSupplier;
    private final ConcurrentMap<String, CalculationResultPage> pages = new ConcurrentHashMap<>();
    private final String baseUrl;

    @Autowired
    public ResultPageService(ResultPageProperties properties) {
        this(properties, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    ResultPageService(
            ResultPageProperties properties,
            Clock clock,
            Supplier<String> idSupplier) {
        this.properties = properties;
        this.clock = clock;
        this.idSupplier = idSupplier;
        properties.validate();
        this.baseUrl = resolveBaseUrl(properties);
    }

    public CalculationResultPage create(String title, String expression, String result) {
        cleanupExpired();
        Instant createdAt = clock.instant();
        CalculationResultPage page = new CalculationResultPage(
                idSupplier.get(),
                normalize(title, "计算结果"),
                normalizeRequired(expression, "表达式"),
                normalizeRequired(result, "计算结果"),
                createdAt,
                createdAt.plus(properties.ttl()));
        pages.put(page.id(), page);
        LOGGER.info("已创建临时计算结果页：{}，有效期至 {}", publicUrl(page), page.expiresAt());
        return page;
    }

    public Optional<CalculationResultPage> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        CalculationResultPage page = pages.get(id);
        if (page == null) {
            return Optional.empty();
        }
        if (!page.expiresAt().isAfter(clock.instant())) {
            pages.remove(id, page);
            return Optional.empty();
        }
        return Optional.of(page);
    }

    public String publicUrl(CalculationResultPage page) {
        return baseUrl + "/results/" + page.id();
    }

    public String publicBaseUrl() {
        return baseUrl;
    }

    private void cleanupExpired() {
        Instant now = clock.instant();
        pages.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private String normalizeRequired(String value, String name) {
        String normalized = normalize(value, "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return normalized;
    }

    private String normalize(String value, String fallback) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            return fallback;
        }
        if (normalized.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException("结果页字段不能超过 " + MAX_FIELD_LENGTH + " 个字符");
        }
        return normalized;
    }

    private static String resolveBaseUrl(ResultPageProperties properties) {
        String configured = properties.publicBaseUrl() == null
                ? ""
                : properties.publicBaseUrl().strip();
        if (!configured.isBlank()) {
            return stripTrailingSlash(configured);
        }
        Optional<String> localAddress = findLanIpv4Address();
        if (localAddress.isEmpty()) {
            LOGGER.warn("没有找到局域网 IPv4 地址，结果页链接将使用 localhost，手机可能无法访问");
        }
        return "http://" + localAddress.orElse("localhost") + ":" + properties.port();
    }

    private static Optional<String> findLanIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return Optional.empty();
            }
            List<AddressCandidate> candidates = new ArrayList<>();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        candidates.add(new AddressCandidate(
                                address.getHostAddress(), interfacePriority(network)));
                    }
                }
            }
            return candidates.stream()
                    .min(Comparator.comparingInt(AddressCandidate::priority))
                    .map(AddressCandidate::address);
        } catch (SocketException exception) {
            LOGGER.warn("读取局域网地址失败：{}", exception.getMessage());
            return Optional.empty();
        }
    }

    private static int interfacePriority(NetworkInterface network) {
        String name = (network.getName() + " " + network.getDisplayName())
                .toLowerCase(Locale.ROOT);
        if (name.contains("wi-fi") || name.contains("wifi") || name.contains("wlan")
                || name.contains("ethernet") || name.contains("以太网")) {
            return 0;
        }
        if (name.contains("virtual") || name.contains("vmware") || name.contains("hyper-v")
                || name.contains("vbox") || name.contains("docker") || name.contains("wsl")) {
            return 2;
        }
        return 1;
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private record AddressCandidate(String address, int priority) {
    }
}
