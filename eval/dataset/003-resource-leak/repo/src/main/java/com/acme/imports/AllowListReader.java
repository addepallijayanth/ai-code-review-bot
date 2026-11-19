package com.acme.imports;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads one entry per line; blank lines and lines starting with # are ignored. */
public class AllowListReader {

    public List<String> read(Path file) throws IOException {
        BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        List<String> entries = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            entries.add(entry.toLowerCase());
        }
        return entries;
    }
}
