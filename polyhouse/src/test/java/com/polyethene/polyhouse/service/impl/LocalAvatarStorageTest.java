package com.polyethene.polyhouse.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 所有文件仅写入测试临时目录，不读取或清理真实用户头像。 */
class LocalAvatarStorageTest {
    @TempDir Path directory;

    @Test
    void usesRandomNamesAndNeverDeletesOutsideManagedNamespace() throws Exception {
        var storage = new LocalAvatarStorage(directory.toString());
        String url = storage.save(new byte[]{1, 2, 3});
        assertThat(url).matches("/api/media/avatars/[a-f0-9]{32}\\.jpg");
        Path file = directory.resolve(url.substring(url.lastIndexOf('/') + 1));
        storage.deleteEventually("/api/media/avatars/../" + file.getFileName());
        storage.deleteEventually("https://example.test/" + file.getFileName());
        assertThat(file).exists();
        storage.deleteEventually(url);
        assertThat(file).doesNotExist();
    }

    @Test
    void persistsCleanupMarkerAndRetriesAfterRestart() throws Exception {
        String name = "a".repeat(32) + ".jpg";
        Path blocked = Files.createDirectory(directory.resolve(name));
        Path child = Files.writeString(blocked.resolve("fixture"), "fixture");
        new LocalAvatarStorage(directory.toString()).deleteEventually("/api/media/avatars/" + name);
        assertThat(directory.resolve(".cleanup").resolve(name)).exists();
        Files.delete(child);
        new LocalAvatarStorage(directory.toString()).retryCleanup();
        assertThat(blocked).doesNotExist();
        assertThat(directory.resolve(".cleanup").resolve(name)).doesNotExist();
    }

    @Test
    void emptyConfigurationNeverFallsBackToSystemTemp() {
        assertThatThrownBy(() -> new LocalAvatarStorage("").save(new byte[]{1})).hasMessage("头像存储尚未配置");
    }
}
