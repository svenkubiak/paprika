package utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

class AtomicFilesTest {

    @TempDir
    Path directory;

    @Test
    void aWrittenFileReplacesTheOldOneAndLeavesNothingBehind() throws Exception {
        Path target = directory.resolve("file-1");
        AtomicFiles.write(target, bytes("old"));

        AtomicFiles.write(target, bytes("new"));

        assertThat(Files.readString(target), equalTo("new"));
        assertThat(names(), contains("file-1"));
    }

    @Test
    void onlyTheTemporaryNamesOfAWriteAreTemporary() {
        assertThat(AtomicFiles.isTemporary(".file-1.0b8c3f2e.tmp"), is(true));
        assertThat(AtomicFiles.isTemporary("file-1"), is(false));
        assertThat(AtomicFiles.isTemporary("file-1.tmp"), is(false));
        assertThat(AtomicFiles.isTemporary(".file-1"), is(false));
    }

    private List<String> names() throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).toList();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
