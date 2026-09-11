package lhqm.furimeo.wisper.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.google.protobuf.ByteString;

import lhqm.furimeo.wisper.files.NodeFiles;
import lhqm.furimeo.wisper.proto.v1.CompleteUpload;
import lhqm.furimeo.wisper.proto.v1.FileChunk;
import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.UploadSession;
import lhqm.furimeo.wisper.proto.v1.WriteChunk;

/**
 * Sends a stored deploy archive to the node that is going to build it.
 *
 * <p>Over the same resumable chunked upload the file manager uses, into the node's
 * {@code FILE_ROOT_KIND_UPLOAD_STAGING} root, because a node can reach the panel's gRPC
 * endpoint and is not guaranteed to be able to reach anything else of the panel's
 * (build.proto). There is no HTTP fetch and no shared volume.
 *
 * <p>The session id is the deployment id. One deployment produces one upload, one build
 * and one release, and giving the four the same name means a half-finished upload on a
 * node can be matched to the deployment it belongs to without a lookup table.
 *
 * <p>Each chunk carries its own SHA-256 and the session carries the whole file's, both
 * re-checked by the node. Mobile networks corrupt more than they are given credit for,
 * and this hop is panel-to-node rather than phone-to-node, but a corrupt archive that is
 * silently accepted becomes a published site with a broken file in it.
 */
@Component
public class PushDeploymentArchive {

    private final NodeFiles files;
    private final StoreDeploymentArchive archives;
    private final DeploySettings settings;

    public PushDeploymentArchive(NodeFiles files, StoreDeploymentArchive archives,
                                 DeploySettings settings) {
        this.files = files;
        this.archives = archives;
        this.settings = settings;
    }

    /**
     * Uploads the archive and returns what the node now holds.
     *
     * <p>The session id is {@code deploymentId.toString()}, so the caller already knows
     * it; what it does not know until now is the checksum, which goes into
     * {@code ArchiveSource.sha256} for the node to re-verify before unpacking.
     *
     * @param deploymentId doubles as the upload session id
     * @param relativePath the archive as {@code deployment.archive_path} records it. Its
     *                     size and checksum are read off the disk here rather than
     *                     carried from the upload, because the two are separated by a
     *                     queue and, usually, by a restart
     * @throws lhqm.furimeo.wisper.files.FileOperationFailed if the node refused a chunk
     *         or the assembled file
     * @throws lhqm.furimeo.wisper.node.NodeOffline if the node's file stream is gone
     */
    public StoredArchive toNode(UUID nodeId, UUID deploymentId, String relativePath) {
        StoredArchive archive = archives.inspect(relativePath);
        Path source = archives.resolve(archive.relativePath());
        int chunkSize = (int) Math.min(settings.archiveChunkSize().toBytes(), Integer.MAX_VALUE);
        String sessionId = deploymentId.toString();

        UploadSession session = UploadSession.newBuilder()
                .setSessionId(sessionId)
                .setPath(archive.fileName())
                .setTotalBytes(archive.sizeBytes())
                .setContentSha256(archive.sha256())
                .setChunkSize(chunkSize)
                // A deployment id is unique, so nothing can already be there. True anyway,
                // so a retry of this whole push after a dropped stream is not refused by
                // the partial file the first attempt left.
                .setOverwrite(true)
                .build();

        try (InputStream in = Files.newInputStream(source)) {
            byte[] buffer = new byte[chunkSize];
            long offset = 0;
            long index = 0;
            int read;
            while ((read = in.readNBytes(buffer, 0, chunkSize)) > 0) {
                offset += read;
                boolean last = offset >= archive.sizeBytes();
                files.call(nodeId, write(sessionId, session, buffer, read, index, offset - read,
                        last));
                index++;
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "Could not read the stored archive for deployment " + deploymentId,
                    unreadable);
        }

        files.call(nodeId, FileRequest.newBuilder()
                .setRequestId(sessionId + ":complete")
                .setRootId(settings.stagingRootId())
                .setComplete(CompleteUpload.newBuilder().setSessionId(sessionId))
                .build());
        return archive;
    }

    private FileRequest write(String sessionId, UploadSession session, byte[] buffer, int length,
                              long index, long offset, boolean last) {
        ByteString data = ByteString.copyFrom(buffer, 0, length);
        FileChunk chunk = FileChunk.newBuilder()
                .setOffset(offset)
                // The node cross-checks the two rather than trusting the offset: an
                // upload that believes a client-supplied offset can be made to write
                // anywhere in the file.
                .setChunkIndex(index)
                .setData(data)
                .setLast(last)
                .setSha256(sha256(buffer, length))
                .build();
        return FileRequest.newBuilder()
                .setRequestId(sessionId + ":" + index)
                .setRootId(settings.stagingRootId())
                .setWrite(WriteChunk.newBuilder().setSession(session).setChunk(chunk))
                .build();
    }

    private static String sha256(byte[] buffer, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(buffer, 0, length);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }
}
