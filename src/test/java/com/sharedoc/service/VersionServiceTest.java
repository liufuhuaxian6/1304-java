package com.sharedoc.service;

import com.sharedoc.model.DocumentVersion;
import com.sharedoc.model.Response;
import com.sharedoc.server.ServerConfig;
import com.sharedoc.storage.FileStorage;
import com.sharedoc.testutil.TestStateHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionServiceTest {
    private static final String DOC_ID = "D-1";
    private static final String FILE_NAME = "snapshot.md";

    private VersionService versionService;

    @BeforeEach
    void setUp() {
        TestStateHelper.resetState();
        versionService = new VersionService();
    }

    @AfterEach
    void tearDown() {
        TestStateHelper.resetState();
    }

    @Test
    void longPatchChainsArePeriodicallySnapshotted() {
        String initialContent = "content-0";
        String sourcePath = Path.of(ServerConfig.DOCUMENT_STORAGE_PATH, DOC_ID + "_" + FILE_NAME).toString();
        new FileStorage().saveFile(sourcePath, initialContent.getBytes(StandardCharsets.UTF_8));
        assertTrue(versionService.createInitialVersion(DOC_ID, FILE_NAME, "admin", sourcePath).isSuccess());

        // Alternate editors so the merge window never combines two edits into
        // one version: each call must create a separate PATCH version.
        String previousContent = initialContent;
        int editCount = 20;
        for (int edit = 1; edit <= editCount; edit += 1) {
            String newContent = "content-" + edit;
            Response response = versionService.createEditVersion(
                    DOC_ID,
                    FILE_NAME,
                    edit % 2 == 0 ? "alice" : "bob",
                    0,
                    previousContent.length(),
                    previousContent,
                    newContent,
                    edit,
                    edit + 1,
                    "edit " + edit,
                    newContent
            );
            assertTrue(response.isSuccess(), "Edit " + edit + " failed: " + response.getMessage());
            previousContent = newContent;
        }

        List<?> versions = assertInstanceOf(List.class, versionService.listVersions(DOC_ID).getData());
        assertEquals(1 + editCount, versions.size());

        DocumentVersion lastVersion = assertInstanceOf(DocumentVersion.class, versions.get(versions.size() - 1));
        assertEquals("FULL", lastVersion.getStorageType(),
                "The 20th consecutive patch version must be stored as a FULL snapshot");

        DocumentVersion middleVersion = assertInstanceOf(DocumentVersion.class, versions.get(versions.size() - 2));
        assertEquals("PATCH", middleVersion.getStorageType());

        // Reconstruction must stay correct for snapshot and patch versions alike.
        assertEquals("content-" + editCount, downloadedText(lastVersion.getVersionId()));
        assertEquals("content-" + (editCount - 1), downloadedText(middleVersion.getVersionId()));
        assertEquals("content-5", downloadedText("V-6"));
    }

    private String downloadedText(String versionId) {
        Response response = versionService.downloadVersion(DOC_ID, versionId);
        assertTrue(response.isSuccess(), "Download of " + versionId + " failed: " + response.getMessage());
        Map<?, ?> data = assertInstanceOf(Map.class, response.getData());
        return new String(assertInstanceOf(byte[].class, data.get("fileContent")), StandardCharsets.UTF_8);
    }
}
