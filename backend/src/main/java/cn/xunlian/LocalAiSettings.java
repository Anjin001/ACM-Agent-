package cn.xunlian;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

import static cn.xunlian.Models.Account;

@Service
public class LocalAiSettings {
    private static final int IV_LENGTH = 12;
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final ObjectMapper json;
    private final Path directory;
    private final SecureRandom random = new SecureRandom();

    public LocalAiSettings(ObjectMapper json, @Value("${xunlian.ai-settings-directory:./data/ai-settings}") Path directory) {
        this.json = json;
        this.directory = directory;
    }

    public record Update(String baseUrl, String model,
                         @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String apiKey) {
        @Override
        public String toString() { return "AI settings update"; }
    }

    public record Status(String baseUrl, String model, boolean hasLocalKey, boolean unreadable) {}

    record Credentials(String baseUrl, String model, String apiKey) {
        @Override
        public String toString() { return "AI service credentials"; }
    }

    Status status(Account account) {
        try {
            Credentials saved = read(account);
            return saved == null ? new Status("", "", false, false)
                    : new Status(saved.baseUrl(), saved.model(), true, false);
        } catch (ResponseStatusException ex) {
            return new Status("", "", false, true);
        }
    }

    Credentials read(Account account) {
        if (account.demo()) return null;
        try {
            Path file = file(account);
            if (!Files.exists(file)) return null;
            byte[] stored = Files.readAllBytes(file);
            if (stored.length <= IV_LENGTH + 16) throw new IOException("Invalid settings file");
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, readKey(), Arrays.copyOf(stored, IV_LENGTH), file);
            return json.readValue(cipher.doFinal(stored, IV_LENGTH, stored.length - IV_LENGTH), Credentials.class);
        } catch (IOException | GeneralSecurityException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法读取本机模型配置，请清除后重新填写");
        }
    }

    synchronized void save(Account account, Update update) {
        String base = normalizeUrl(update.baseUrl());
        String model = trim(update.model());
        if (model.isEmpty() || model.length() > 160) bad("请输入有效的模型名称");
        String apiKey = trim(update.apiKey());
        if (apiKey.isEmpty()) {
            Credentials previous = read(account);
            if (previous == null || !base.equals(previous.baseUrl())) bad("首次配置或更换服务地址时，请输入 API Key");
            apiKey = previous.apiKey();
        }
        if (apiKey.length() > 1024 || apiKey.chars().anyMatch(character -> character < 33 || character > 126)) {
            bad("请输入有效的 API Key");
        }
        try {
            Files.createDirectories(directory);
            Path file = file(account);
            Path keyFile = directory.resolve("storage.key");
            if (Files.notExists(keyFile)) {
                byte[] key = new byte[32];
                random.nextBytes(key);
                try {
                    Files.write(keyFile, key, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException ignored) {
                    // Another local service instance already initialized this directory.
                }
            }
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            byte[] plaintext = json.writeValueAsBytes(new Credentials(base, model, apiKey));
            byte[] encrypted;
            try {
                encrypted = cipher(Cipher.ENCRYPT_MODE, readKey(), iv, file).doFinal(plaintext);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
            byte[] stored = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, stored, iv.length, encrypted.length);
            Path pending = Files.createTempFile(directory, "settings-", ".tmp");
            try {
                Files.write(pending, stored);
                Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(pending);
            }
        } catch (IOException | GeneralSecurityException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存模型配置，请检查本机数据目录");
        }
    }

    synchronized void clear(Account account) {
        try {
            Files.deleteIfExists(file(account));
        } catch (IOException | GeneralSecurityException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法清除本机模型配置");
        }
    }

    private Path file(Account account) throws GeneralSecurityException {
        // A fresh database can reuse numeric IDs. Bind local settings to this account instance.
        byte[] identity = (account.id() + ":" + account.username() + ":" + account.passwordHash())
                .getBytes(StandardCharsets.UTF_8);
        String name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity));
        return directory.resolve(name + ".enc");
    }

    private byte[] readKey() throws IOException {
        byte[] key = Files.readAllBytes(directory.resolve("storage.key"));
        if (key.length != 32) throw new IOException("Invalid encryption key");
        return key;
    }

    private Cipher cipher(int mode, byte[] key, byte[] iv, Path file) throws GeneralSecurityException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
            return cipher;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static String normalizeUrl(String value) {
        String base = trim(value);
        if (base.length() > 500) bad("模型服务地址过长");
        try {
            URI uri = URI.create(base);
            String host = uri.getHost();
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            boolean localHttp = "http".equalsIgnoreCase(uri.getScheme()) && host != null
                    && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
            if ((!secure && !localHttp) || host == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() > 65535) {
                bad("请填写 HTTPS 模型服务地址。本机模型可使用 HTTP");
            }
            String normalized = uri.toASCIIString().replaceAll("/+$", "");
            if (normalized.endsWith("/chat/completions")) {
                normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
            }
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型服务地址无效");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.strip();
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
