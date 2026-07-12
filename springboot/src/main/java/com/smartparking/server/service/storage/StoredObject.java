package com.smartparking.server.service.storage;

import lombok.Value;

@Value
public class StoredObject {
    String key;
    String contentType;
    long sizeBytes;
}
