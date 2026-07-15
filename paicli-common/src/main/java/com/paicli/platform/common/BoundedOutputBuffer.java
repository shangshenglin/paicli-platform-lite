package com.paicli.platform.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

public final class BoundedOutputBuffer extends OutputStream {
    private final int limit;
    private final ByteArrayOutputStream retained;
    private long received;

    public BoundedOutputBuffer(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        this.limit = limit;
        this.retained = new ByteArrayOutputStream(limit);
    }

    @Override
    public synchronized void write(int value) {
        received++;
        if (retained.size() < limit) retained.write(value);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException("bytes");
        if (offset < 0 || length < 0 || offset + length > bytes.length) throw new IndexOutOfBoundsException();
        received += length;
        int writable = Math.min(length, limit - retained.size());
        if (writable > 0) retained.write(bytes, offset, writable);
    }

    public synchronized String text(Charset charset) {
        return retained.toString(charset);
    }

    public synchronized boolean truncated() {
        return received > retained.size();
    }

    public synchronized long receivedBytes() {
        return received;
    }
}
