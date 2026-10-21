package io.github.gouz.pdfj.net;

import java.nio.file.Path;

/**
 * Which images, stylesheets and fonts a document may load. HTML may be untrusted, so by default only
 * inline {@code data:} URLs load:
 * <ul>
 *   <li>local files only if {@code baseDir} is set, and only inside it (no ../, absolute path or symlink escape)</li>
 *   <li>http(s) only when remote resources are enabled (see {@link Remote} for the guards)</li>
 *   <li>any other scheme (file:, ftp:, ...) never</li>
 * </ul>
 *
 * @param remote http(s) resources: null = refused
 */
public record ImagePolicy(Path baseDir, Remote.Config remote) {
    public static final ImagePolicy NONE = new ImagePolicy(null, null);
}
