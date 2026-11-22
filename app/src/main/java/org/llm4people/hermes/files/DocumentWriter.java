package org.llm4people.hermes.files;

import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.io.OutputStream;

/** Signal incomplete writes through providers' reliable-fd channel before closing the stream. */
final class DocumentWriter {
    interface Copy { void write(OutputStream destination) throws IOException; }

    static void save(ParcelFileDescriptor document, Copy copy) throws IOException {
        if (document == null) throw new IOException("Destination unavailable");
        try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(document)) {
            try {
                copy.write(out);
                out.flush();
            } catch (IOException | RuntimeException failure) {
                try { document.closeWithError("Incomplete document export"); }
                catch (IOException abortFailure) { failure.addSuppressed(abortFailure); }
                throw failure;
            }
        }
    }

    private DocumentWriter() {}
}
