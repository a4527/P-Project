package com.smartparking.server.service.storage;

import java.io.InputStream;

public interface StorageService {

    StoredObject put(String key, InputStream inputStream, long sizeBytes, String contentType);

    byte[] getBytes(String key);

    InputStream getStream(String key);

    boolean exists(String key);

    void delete(String key);
}
