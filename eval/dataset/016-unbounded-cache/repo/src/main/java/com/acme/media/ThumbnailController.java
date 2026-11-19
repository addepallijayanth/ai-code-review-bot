package com.acme.media;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ThumbnailController {

    private final ThumbnailService thumbnails;

    public ThumbnailController(ThumbnailService thumbnails) {
        this.thumbnails = thumbnails;
    }

    @GetMapping(value = "/images/{id}/thumbnail", produces = "image/png")
    public byte[] thumbnail(@PathVariable String id, @RequestParam(defaultValue = "256") int width) {
        return thumbnails.thumbnail(id, width);
    }
}
