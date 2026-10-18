package cn.iocoder.yudao.module.mes.service.assistant;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;

/** Secrets remain encrypted in MySQL; the separate owner-only master key is never returned by an API. */
@Component
public class MesAssistantSecretStore {
    private final Path keyPath;
    private final SecureRandom random = new SecureRandom();
    private byte[] masterKey;

    public MesAssistantSecretStore(Environment environment) {
        keyPath = Path.of(environment.getProperty("mes.assistant.master-key-file",
                Path.of(System.getProperty("user.home"), ".fazemes", "assistant", "master.key").toString())).toAbsolutePath();
    }

    public String encrypt(String secret, long tenant) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(Long.toString(tenant).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (Exception exception) { throw MesAssistantErrors.model("模型密钥无法安全保存，请联系管理员检查密钥文件权限"); }
    }

    public String decrypt(String encoded, long tenant) {
        if (encoded == null || encoded.isBlank()) return "";
        try {
            if (!encoded.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] data = Base64.getDecoder().decode(encoded.substring(3));
            if (data.length < 29) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, data, 0, 12));
            cipher.updateAAD(Long.toString(tenant).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (Exception exception) { throw MesAssistantErrors.model("模型密钥无法解密，请由管理员重新配置"); }
    }

    private synchronized byte[] key() throws Exception {
        if (masterKey != null) return masterKey;
        if (!Files.exists(keyPath)) {
            Files.createDirectories(keyPath.getParent());
            restrictToOwner(keyPath.getParent(), true);
            byte[] generated = new byte[32]; random.nextBytes(generated);
            try {
                Files.writeString(keyPath, Base64.getEncoder().encodeToString(generated), StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                restrictToOwner(keyPath, false);
            } catch (FileAlreadyExistsException ignored) { /* A concurrent process created the shared key. */ }
        }
        if (Files.size(keyPath) > 100) throw new IllegalStateException();
        byte[] loaded = Base64.getDecoder().decode(Files.readString(keyPath, StandardCharsets.US_ASCII).strip());
        if (loaded.length != 32) throw new IllegalStateException();
        masterKey = loaded;
        return masterKey;
    }

    private static void restrictToOwner(Path path, boolean directory) throws Exception {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl == null) throw new IllegalStateException("Owner-only file permissions unavailable");
        var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        acl.setAcl(List.of(entry.build()));
    }
}
