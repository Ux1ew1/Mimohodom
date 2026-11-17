package app.fastnotes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Минимальный HTTP/1.1 POST поверх сокета. Нужен, чтобы ходить по локальной сети без
 * разрешения «открытый трафик» для всего приложения: тела всё равно зашифрованы (SyncCrypto).
 */
public final class MiniHttp {
  public static final class Response {
    public int status;
    public byte[] body = new byte[0];
  }

  public static final int MAX_BODY = 64 * 1024 * 1024;

  private MiniHttp() {}

  public static Response post(
      String hostPort,
      String path,
      String deviceHeader,
      byte[] body,
      int connectMs,
      int readMs)
      throws IOException {
    int colon = hostPort.lastIndexOf(':');
    if (colon <= 0) throw new IOException("Плохой адрес: " + hostPort);
    String host = hostPort.substring(0, colon);
    int port;
    try {
      port = Integer.parseInt(hostPort.substring(colon + 1));
    } catch (NumberFormatException e) {
      throw new IOException("Плохой порт: " + hostPort);
    }
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), connectMs);
      socket.setSoTimeout(readMs);
      OutputStream out = socket.getOutputStream();
      String head =
          "POST "
              + path
              + " HTTP/1.1\r\nHost: "
              + hostPort
              + "\r\nX-Mimo-Device: "
              + deviceHeader
              + "\r\nContent-Type: application/octet-stream\r\nContent-Length: "
              + body.length
              + "\r\nConnection: close\r\n\r\n";
      out.write(head.getBytes(StandardCharsets.US_ASCII));
      out.write(body);
      out.flush();
      return read(socket.getInputStream());
    }
  }

  static Response read(InputStream in) throws IOException {
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    int b, last4 = 0;
    boolean complete = false;
    // Заголовки до пустой строки (\r\n\r\n).
    while ((b = in.read()) != -1) {
      head.write(b);
      last4 = (last4 << 8) | b;
      if (last4 == 0x0D0A0D0A) {
        complete = true;
        break;
      }
      if (head.size() > 16 * 1024) throw new IOException("Слишком длинные заголовки");
    }
    if (!complete) throw new IOException("Соединение закрыто до ответа");
    String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
    String[] status = lines[0].split(" ", 3);
    if (status.length < 2) throw new IOException("Непонятный ответ");
    Response r = new Response();
    try {
      r.status = Integer.parseInt(status[1]);
    } catch (NumberFormatException e) {
      throw new IOException("Непонятный ответ");
    }
    long length = -1;
    boolean chunked = false;
    for (String line : lines) {
      int c = line.indexOf(':');
      if (c <= 0) continue;
      String key = line.substring(0, c).trim().toLowerCase(java.util.Locale.ROOT);
      String value = line.substring(c + 1).trim();
      if (key.equals("content-length")) {
        try {
          length = Long.parseLong(value);
        } catch (NumberFormatException ignored) {
        }
      } else if (key.equals("transfer-encoding") && value.toLowerCase(java.util.Locale.ROOT).contains("chunked")) {
        chunked = true;
      }
    }
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    byte[] buffer = new byte[16 * 1024];
    if (chunked) {
      while (true) {
        String size = readLine(in);
        int semi = size.indexOf(';');
        if (semi >= 0) size = size.substring(0, semi);
        int n = Integer.parseInt(size.trim(), 16);
        if (n == 0) break;
        if (body.size() + n > MAX_BODY) throw new IOException("Ответ слишком большой");
        copy(in, body, n, buffer);
        readLine(in);
      }
    } else if (length >= 0) {
      if (length > MAX_BODY) throw new IOException("Ответ слишком большой");
      copy(in, body, (int) length, buffer);
    } else {
      int n;
      while ((n = in.read(buffer)) != -1) {
        body.write(buffer, 0, n);
        if (body.size() > MAX_BODY) throw new IOException("Ответ слишком большой");
      }
    }
    r.body = body.toByteArray();
    return r;
  }

  private static void copy(InputStream in, ByteArrayOutputStream out, int count, byte[] buffer)
      throws IOException {
    int left = count;
    while (left > 0) {
      int n = in.read(buffer, 0, Math.min(buffer.length, left));
      if (n < 0) throw new IOException("Ответ оборван");
      out.write(buffer, 0, n);
      left -= n;
    }
  }

  private static String readLine(InputStream in) throws IOException {
    StringBuilder line = new StringBuilder();
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\n') break;
      if (b != '\r') line.append((char) b);
    }
    return line.toString();
  }
}
