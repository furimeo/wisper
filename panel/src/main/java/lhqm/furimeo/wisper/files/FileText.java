package lhqm.furimeo.wisper.files;

/**
 * A file's contents as the inline editor receives them.
 *
 * <p>Decoded as UTF-8 with replacement rather than refused. A customer can open a binary
 * file by accident, and an editor that shows replacement characters and refuses to save is
 * a better answer than a stack trace - the mistake is obvious on screen either way.
 *
 * @param byteLength what was read, in bytes, which is not the length of {@link #text()}
 *                   once anything outside ASCII is involved
 * @param truncated  the read hit its ceiling. The editor opens read-only when this is set:
 *                   saving would write the first megabyte over the whole file, which is the
 *                   one way an editor can destroy data without anybody typing anything
 */
public record FileText(String path, String text, int byteLength, boolean truncated) {

    public FileText {
        text = text == null ? "" : text;
    }

    /** Whether the editor may offer a save button. */
    public boolean isEditable() {
        return !truncated;
    }
}
