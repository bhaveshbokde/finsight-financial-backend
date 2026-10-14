package com.finsight.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Fingerprints used for duplicate detection and idempotency keys. */
class HashesTest {

    @Test
    @DisplayName("sha256Hex produces the known digest for a known input")
    void knownVector() {
        // The standard SHA-256 test vector; proves we are hashing what we think.
        assertThat(Hashes.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("sha256Hex is 64 lower-case hex characters, matching the column CHECK")
    void shape() {
        assertThat(Hashes.sha256Hex("anything")).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("fingerprint is stable for identical input")
    void stable() {
        String first = Hashes.fingerprint(1L, "2026-03-14", "EXPENSE", "100.0000", "Groceries", "DMart");
        String second = Hashes.fingerprint(1L, "2026-03-14", "EXPENSE", "100.0000", "Groceries", "DMart");
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("fingerprint ignores case and surrounding whitespace")
    void normalises() {
        // "Big  Bazaar" and "big bazaar" are the same merchant as far as duplicate
        // detection is concerned; a user retyping a row should not defeat it.
        assertThat(Hashes.fingerprint("Big  Bazaar")).isEqualTo(Hashes.fingerprint("  big bazaar "));
    }

    @Test
    @DisplayName("fingerprint distinguishes different field boundaries")
    void noFieldBoundaryCollision() {
        // The classic concatenation bug: joining with a character that can appear
        // in the data makes ("ab","c") and ("a","bc") hash alike. The unit
        // separator used by fingerprint() cannot appear in normalised input.
        assertThat(Hashes.fingerprint("ab", "c")).isNotEqualTo(Hashes.fingerprint("a", "bc"));
        assertThat(Hashes.fingerprint("a|b", "c")).isNotEqualTo(Hashes.fingerprint("a", "b|c"));
    }

    @Test
    @DisplayName("fingerprint treats null and empty alike but keeps position significant")
    void nullHandling() {
        assertThat(Hashes.fingerprint("a", null, "b")).isEqualTo(Hashes.fingerprint("a", "", "b"));
        assertThat(Hashes.fingerprint("a", null, "b")).isNotEqualTo(Hashes.fingerprint("a", "b", null));
    }

    @Test
    @DisplayName("a different amount yields a different fingerprint")
    void amountIsSignificant() {
        String a = Hashes.fingerprint(1L, "2026-03-14", "EXPENSE", "100.0000", "Lunch", null);
        String b = Hashes.fingerprint(1L, "2026-03-14", "EXPENSE", "100.0100", "Lunch", null);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("a different user yields a different fingerprint")
    void userIsSignificant() {
        String a = Hashes.fingerprint(1L, "2026-03-14", "EXPENSE", "100.0000", "Lunch", null);
        String b = Hashes.fingerprint(2L, "2026-03-14", "EXPENSE", "100.0000", "Lunch", null);
        assertThat(a).isNotEqualTo(b);
    }
}
