package com.acme.media;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ThumbnailService {

    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
    private final ImageStorage storage;
    private final ImageResizer resizer;

    public ThumbnailService(ImageStorage storage, ImageResizer resizer) {
        this.storage = storage;
        this.resizer = resizer;
    }

    public byte[] thumbnail(String imageId, int width) {
        return cache.computeIfAbsent(imageId + ":" + width, key -> resizer.resize(storage.load(imageId), width));
    }
}
