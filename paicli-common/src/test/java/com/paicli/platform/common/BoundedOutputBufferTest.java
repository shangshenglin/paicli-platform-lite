package com.paicli.platform.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedOutputBufferTest {
    @Test
    void retainsOnlyTheConfiguredPrefixWhileConsumingAllBytes() throws Exception {
        BoundedOutputBuffer output = new BoundedOutputBuffer(4);

        output.write("abcdefgh".getBytes(StandardCharsets.UTF_8));

        assertEquals("abcd", output.text(StandardCharsets.UTF_8));
        assertEquals(8, output.receivedBytes());
        assertTrue(output.truncated());
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedOutputBuffer(0));
    }
}
