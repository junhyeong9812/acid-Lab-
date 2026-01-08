package com.experiment.acidlab.storage.csv;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 파일 입출력 유틸리티
 * - 파일 읽기/쓰기
 * - fsync 지원 (Durability)
 */
public class CsvStorage {

    private final Path basePath;

    public CsvStorage() {
        this(Paths.get("."));
    }

    public CsvStorage(Path basePath) {
        this.basePath = basePath;
    }

    /**
     * CSV 파일 읽기
     */
    public List<String> readLines(String filePath) {
        Path path = resolvePath(filePath);

        if (!Files.exists(path)) {
            return new ArrayList<>();
        }

        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CsvStorageException("Failed to read file: " + filePath, e);
        }
    }

    public void writeLines(String filePath, List<String> lines) {
        Path path = resolvePath(filePath);

        try {
            Files.createDirectories(path.getParent());

            Files.write(path, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new CsvStorageException("Failed to write file: " + filePath, e);
        }
    }

    public void appendLine(String filePath, String line) {
        Path path = resolvePath(filePath);

        try {
            Files.createDirectories(path.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e ) {
            throw new CsvStorageException("Failed to append to file: " + filePath, e);
        }
    }

    public void sync(String filePath) {
        Path path = resolvePath(filePath);

        try (FileOutputStream fos = new FileOutputStream(path.toFile(), true)) {
            fos.getFD().sync();
        } catch (IOException e) {
            throw new CsvStorageException("Failed to sync file: " + filePath, e);
        }
    }

    public boolean exists(String filePath) {
        return Files.exists(resolvePath(filePath));
    }

    public void delete(String filePath) {
        try {
            Files.deleteIfExists(resolvePath(filePath));
        } catch (IOException e) {
            throw new CsvStorageException("Failed to delete file: " + filePath, e);
        }
    }

    public void backup(String filePath) {
        Path source = resolvePath(filePath);
        Path backup = resolvePath(filePath + ".bak");

        try {
            if (Files.exists(source)) {
                Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new CsvStorageException("Failed to backup file: " + filePath, e);
        }
    }

    private Path resolvePath(String filePath) {
        return basePath.resolve(filePath);
    }

    public static class CsvStorageException extends RuntimeException {
        public CsvStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
