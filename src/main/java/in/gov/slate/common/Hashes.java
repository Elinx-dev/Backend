package in.gov.slate.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class Hashes {

    private Hashes() {
    }

    public static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sha256(String input) {
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(byte[] bytes) {
        return bytes == null ? null : "0x" + HexFormat.of().formatHex(bytes);
    }

    /** Salted Aadhaar hash. The number itself is never stored, logged or returned. */
    public static byte[] aadhaarHash(String aadhaar, String salt) {
        return sha256(salt + "|" + aadhaar);
    }

    /**
     * Merkle root over the supplied leaves, duplicating the last leaf on odd levels.
     * Matches the verification performed by EvidenceAnchor consumers.
     */
    public static byte[] merkleRoot(List<byte[]> leaves) {
        if (leaves.isEmpty()) {
            return sha256(new byte[0]);
        }
        List<byte[]> level = new ArrayList<>(leaves);
        while (level.size() > 1) {
            List<byte[]> next = new ArrayList<>();
            for (int i = 0; i < level.size(); i += 2) {
                byte[] left = level.get(i);
                byte[] right = (i + 1 < level.size()) ? level.get(i + 1) : left;
                byte[] pair = new byte[left.length + right.length];
                System.arraycopy(left, 0, pair, 0, left.length);
                System.arraycopy(right, 0, pair, left.length, right.length);
                next.add(sha256(pair));
            }
            level = next;
        }
        return level.get(0);
    }
}
