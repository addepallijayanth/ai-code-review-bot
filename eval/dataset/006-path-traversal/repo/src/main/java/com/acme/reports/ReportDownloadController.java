package com.acme.reports;

import java.net.MalformedURLException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportDownloadController {

    private final Path reportsDir;

    public ReportDownloadController(@Value("${reports.dir}") Path reportsDir) {
        this.reportsDir = reportsDir;
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam String file) throws MalformedURLException {
        Path path = reportsDir.resolve(file);
        Resource resource = new UrlResource(path.toUri());
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + path.getFileName() + "\"")
                .body(resource);
    }
}
