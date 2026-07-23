package com.paicli.platform.server.infrastructure;

public interface ObjectStoragePort {
    void write(String objectKey, byte[] bytes);

    byte[] read(String objectKey);

    boolean delete(String objectKey);
}
