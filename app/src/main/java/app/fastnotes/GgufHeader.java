package app.fastnotes;

import java.io.IOException;

public final class GgufHeader {
  public static void validate(byte[] header, int size) throws IOException {
    if (size != 8
        || header.length < 8
        || header[0] != 'G'
        || header[1] != 'G'
        || header[2] != 'U'
        || header[3] != 'F'
        || (header[4] != 2 && header[4] != 3)
        || header[5] != 0
        || header[6] != 0
        || header[7] != 0) throw new IOException("Нужен GGUF версии 2 или 3");
  }
}
