package com.smashprofs.server.ranking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/** Server-issued guest identity. Only the hash of its bearer secret is stored. */
public record GuestIdentity(String profileId, String token) {
    private static final SecureRandom RANDOM=new SecureRandom();
    public static GuestIdentity create() {
        byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);
        return new GuestIdentity("guest_"+UUID.randomUUID(),HexFormat.of().formatHex(bytes));
    }
    public static String hash(String token) {
        if(token==null || !token.matches("[0-9a-f]{64}")) { throw new IllegalArgumentException("Invalid guest credential"); }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
