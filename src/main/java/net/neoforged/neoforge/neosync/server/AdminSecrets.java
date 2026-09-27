/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import net.neoforged.neoforge.neosync.protocol.ManagedPaths;
import net.neoforged.neoforge.neosync.protocol.SyncManifest;

/** Local administrator credentials are never exposed by HTTP or included in discovery. */
public final class AdminSecrets {
    private final byte[] passwordHash;
    private final SSLContext tls;
    private final String fingerprint;

    private AdminSecrets(String password, SSLContext tls, String fingerprint) {
        passwordHash = SyncManifest.sha256Digest().digest(password.getBytes(StandardCharsets.US_ASCII));
        this.tls = tls;
        this.fingerprint = fingerprint;
    }

    public static AdminSecrets open(Path directory) throws Exception {
        directory = ManagedPaths.directory(directory, true);
        restrict(directory, true);
        String password = secret(directory.resolve("password.txt"));
        String keyPassword = secret(directory.resolve("keystore-password.txt"));
        Path keyStore = directory.resolve("tls.p12");
        if (!Files.exists(keyStore, LinkOption.NOFOLLOW_LINKS)) generateCertificate(directory, keyStore, keyPassword);
        requireFile(keyStore, 65536);
        restrict(keyStore, false);
        var store = KeyStore.getInstance("PKCS12");
        char[] chars = keyPassword.toCharArray();
        try {
            try (var input = Files.newInputStream(keyStore, LinkOption.NOFOLLOW_LINKS)) {
                store.load(input, chars);
            }
            var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, chars);
            var context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            var certificate = store.getCertificate("neosync-admin");
            if (certificate == null) throw new IOException("The administrator TLS certificate is missing.");
            return new AdminSecrets(password, context, SyncManifest.sha256(certificate.getEncoded()));
        } finally {
            java.util.Arrays.fill(chars, '\0');
        }
    }

    public boolean accepts(String password) {
        return MessageDigest.isEqual(passwordHash, SyncManifest.sha256Digest().digest(password.getBytes(StandardCharsets.UTF_8)));
    }

    public SSLContext tls() {
        return tls;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String secret(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            // The enclosing directory is already owner-only, including on ACL filesystems.
            Files.writeString(path, randomToken() + "\n", StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        }
        requireFile(path, 128);
        restrict(path, false);
        String value = Files.readString(path, StandardCharsets.US_ASCII).strip();
        if (!value.matches("[A-Za-z0-9_-]{43}")) throw new IOException("Invalid administrator secret. Stop the server and remove only the affected secret file to regenerate it.");
        return value;
    }

    private static void requireFile(Path path, long limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit)
            throw new IOException("An administrator credential path is unsafe or oversized.");
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("The administrator secret directory requires owner-only POSIX permissions or a Windows ACL.");
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }

    private static void generateCertificate(Path directory, Path target, String password) throws Exception {
        Path temporary = directory.resolve("tls-" + randomToken() + ".p12");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);
        var addresses = new java.util.TreeSet<String>();
        addresses.add("127.0.0.1");
        addresses.add("::1");
        for (var network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (!address.isAnyLocalAddress() && !address.isMulticastAddress()) addresses.add(address.getHostAddress().split("%", 2)[0]);
            }
        }
        var names = new ArrayList<String>();
        names.add("dns:localhost");
        addresses.stream().limit(64).forEach(address -> names.add("ip:" + address));
        var builder = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "neosync-admin", "-keyalg", "RSA", "-keysize", "3072",
                "-validity", "3650", "-dname", "CN=NeoSync administrator", "-ext", "SAN=" + String.join(",", names), "-storetype", "PKCS12",
                "-keystore", temporary.toString(), "-storepass:env", "NEOSYNC_KEYTOOL_PASSWORD", "-noprompt");
        builder.environment().put("NEOSYNC_KEYTOOL_PASSWORD", password);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IOException("Could not generate administrator TLS identity. A Java 21 installation with keytool is required.");
            requireFile(temporary, 65536);
            restrict(temporary, false);
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            process.destroyForcibly();
            Files.deleteIfExists(temporary);
        }
    }
}
