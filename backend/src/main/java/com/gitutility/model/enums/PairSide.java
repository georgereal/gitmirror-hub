package com.gitutility.model.enums;

/**
 * Which side of a repo pair originated a ref or pull request: mapping A or mapping B.
 */
public enum PairSide {
    A,
    B;

    public static PairSide fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if ("B".equalsIgnoreCase(v)) {
            return B;
        }
        if ("A".equalsIgnoreCase(v)) {
            return A;
        }
        return null;
    }

    public PairSide opposite() {
        return this == A ? B : A;
    }
}
