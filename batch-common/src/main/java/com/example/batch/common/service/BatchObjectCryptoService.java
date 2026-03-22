package com.example.batch.common.service;

import com.example.batch.common.config.BatchKmsProperties;
import com.example.batch.common.config.BatchSecurityProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.util.StringUtils;

public class BatchObjectCryptoService {

    private static final byte[] MAGIC = "BATCHENC".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSION = 1;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final BatchSecurityProperties securityProperties;
    private final BatchKmsProperties kmsProperties;

    public BatchObjectCryptoService(BatchSecurityProperties securityProperties, BatchKmsProperties kmsProperties) {
        this.securityProperties = securityProperties;
        this.kmsProperties = kmsProperties;
    }

    public boolean isTestingOpen() {
        return securityProperties.isTestingOpen();
    }

    public boolean shouldEncrypt(Map<String, Object> security) {
        return !securityProperties.isTestingOpen() && truthy(security == null ? null : security.get("content_encryption_enabled"));
    }

    public String resolveKeyRef(Map<String, Object> security) {
        if (security != null) {
            Object keyRef = security.get("encryption_key_ref");
            if (keyRef != null && StringUtils.hasText(String.valueOf(keyRef))) {
                return String.valueOf(keyRef);
            }
        }
        return kmsProperties.getDefaultKeyRef();
    }

    public byte[] encrypt(byte[] plaintext, String keyRef) {
        if (plaintext == null) {
            return null;
        }
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            encrypt(new ByteArrayInputStream(plaintext), outputStream, keyRef);
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to encrypt bytes", exception);
        }
    }

    public byte[] decrypt(byte[] content) {
        if (content == null || content.length == 0 || !isEncrypted(content)) {
            return content;
        }
        try (InputStream inputStream = decryptIfNeeded(new ByteArrayInputStream(content))) {
            return inputStream.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to decrypt bytes", exception);
        }
    }

    public void encrypt(InputStream plainInput, OutputStream encryptedOutput, String keyRef) {
        if (plainInput == null || encryptedOutput == null) {
            throw new IllegalArgumentException("plainInput and encryptedOutput are required");
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(resolveKeyBytes(keyRef), "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            DataOutputStream dataOutput = new DataOutputStream(encryptedOutput);
            dataOutput.write(MAGIC);
            dataOutput.writeByte(VERSION);
            dataOutput.writeUTF(normalizedKeyRef(keyRef));
            dataOutput.writeByte(iv.length);
            dataOutput.write(iv);
            dataOutput.flush();
            try (CipherOutputStream cipherOutput = new CipherOutputStream(encryptedOutput, cipher)) {
                plainInput.transferTo(cipherOutput);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("failed to encrypt content", exception);
        }
    }

    public Path encrypt(Path source, Path target, String keyRef) {
        if (source == null || target == null) {
            throw new IllegalArgumentException("source and target are required");
        }
        try (InputStream inputStream = Files.newInputStream(source);
             OutputStream outputStream = Files.newOutputStream(target)) {
            encrypt(inputStream, outputStream, keyRef);
            return target;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to encrypt file", exception);
        }
    }

    public InputStream decryptIfNeeded(InputStream inputStream) {
        if (inputStream == null) {
            return InputStream.nullInputStream();
        }
        try {
            PushbackInputStream pushbackInputStream = new PushbackInputStream(inputStream, 64);
            byte[] magic = pushbackInputStream.readNBytes(MAGIC.length);
            if (magic.length < MAGIC.length || !java.util.Arrays.equals(magic, MAGIC)) {
                if (magic.length > 0) {
                    pushbackInputStream.unread(magic);
                }
                return pushbackInputStream;
            }
            int version = pushbackInputStream.read();
            if (version != VERSION) {
                throw new IllegalStateException("unsupported encrypted object version: " + version);
            }
            DataInputStream dataInputStream = new DataInputStream(pushbackInputStream);
            String keyRef = dataInputStream.readUTF();
            int ivLength = dataInputStream.readUnsignedByte();
            byte[] iv = dataInputStream.readNBytes(ivLength);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(resolveKeyBytes(keyRef), "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new CipherInputStream(pushbackInputStream, cipher);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to open decrypted stream", exception);
        }
    }

    private byte[] resolveKeyBytes(String keyRef) {
        String resolvedKeyRef = normalizedKeyRef(keyRef);
        String base64 = kmsProperties.getKeys().get(resolvedKeyRef);
        if (!StringUtils.hasText(base64)) {
            throw new IllegalStateException("missing kms key material for keyRef=" + resolvedKeyRef);
        }
        return Base64.getDecoder().decode(base64);
    }

    private String normalizedKeyRef(String keyRef) {
        return StringUtils.hasText(keyRef) ? keyRef.trim() : kmsProperties.getDefaultKeyRef();
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private boolean isEncrypted(byte[] content) {
        if (content.length < MAGIC.length) {
            return false;
        }
        for (int index = 0; index < MAGIC.length; index++) {
            if (content[index] != MAGIC[index]) {
                return false;
            }
        }
        return true;
    }
}
