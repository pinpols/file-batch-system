package io.github.pinpols.batch.e2e.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 测试 SMTP 服务接收完整 DATA 内容后，在最终回复前断开连接。 */
public final class SmtpAcceptThenDropServer implements AutoCloseable {

  private final ServerSocket server;
  private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "e2e-smtp-accept-then-drop");
    thread.setDaemon(true);
    return thread;
  });
  private final CountDownLatch accepted = new CountDownLatch(1);
  private final CountDownLatch releaseAfterAcceptance = new CountDownLatch(1);
  private final AtomicInteger acceptedMessages = new AtomicInteger();
  private final ConcurrentLinkedQueue<String> protocolEvents = new ConcurrentLinkedQueue<>();
  private volatile Socket acceptedSocket;
  private final boolean pauseAfterAcceptance;

  public SmtpAcceptThenDropServer() throws IOException {
    this(false);
  }

  public SmtpAcceptThenDropServer(boolean pauseAfterAcceptance) throws IOException {
    this.pauseAfterAcceptance = pauseAfterAcceptance;
    server = new ServerSocket(0);
    executor.submit(this::serveOneMessage);
  }

  public int port() {
    return server.getLocalPort();
  }

  public boolean awaitAcceptedMessage(Duration timeout) throws InterruptedException {
    return accepted.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  public int acceptedMessageCount() {
    return acceptedMessages.get();
  }

  public List<String> protocolEvents() {
    return List.copyOf(protocolEvents);
  }

  public void releaseAfterAcceptance() {
    releaseAfterAcceptance.countDown();
  }

  private void serveOneMessage() {
    while (!server.isClosed()) {
      try (Socket socket = server.accept()) {
        acceptedSocket = socket;
        boolean messageAccepted = serveConnection(socket);
        if (messageAccepted) {
          return;
        }
      } catch (IOException ignoredAfterSocketClose) {
        if (server.isClosed()) {
          return;
        }
        protocolEvents.add("<IO-ERROR:" + ignoredAfterSocketClose.getClass().getSimpleName() + ">");
      } catch (InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
        return;
      } finally {
        acceptedSocket = null;
      }
    }
  }

  private boolean serveConnection(Socket socket) throws IOException, InterruptedException {
    protocolEvents.add("<CONNECTION-ACCEPTED>");
    socket.setSoTimeout(30_000);
    BufferedReader reader = new BufferedReader(
        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
    PrintWriter writer = new PrintWriter(
        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
    reply(writer, "220 localhost ESMTP ready");
    protocolEvents.add("<GREETING-SENT>");
    String line;
    while ((line = reader.readLine()) != null) {
      String command = line.toUpperCase(Locale.ROOT);
      protocolEvents.add(command.startsWith("AUTH ") ? "<AUTH>" : command);
      if (command.startsWith("EHLO") || command.startsWith("HELO")) {
        reply(writer, "250-localhost");
        reply(writer, "250-AUTH PLAIN LOGIN");
        reply(writer, "250 SIZE 10485760");
      } else if (command.startsWith("AUTH ")) {
        reply(writer, "235 2.7.0 authenticated");
      } else if (command.startsWith("MAIL FROM:") || command.startsWith("RCPT TO:")) {
        reply(writer, "250 2.1.0 accepted");
      } else if (command.equals("DATA")) {
        reply(writer, "354 end with dot");
        while ((line = reader.readLine()) != null && !".".equals(line)) {
          // 先读完完整消息体，再模拟 SMTP 最终响应丢失。
        }
        if (!".".equals(line)) {
          protocolEvents.add("<DATA-EOF>");
          return false;
        }
        protocolEvents.add("<DATA-END>");
        acceptedMessages.incrementAndGet();
        accepted.countDown();
        if (pauseAfterAcceptance) {
          releaseAfterAcceptance.await(30, TimeUnit.SECONDS);
        }
        socket.setSoLinger(true, 0);
        return true;
      } else if (command.startsWith("QUIT")) {
        reply(writer, "221 2.0.0 closing");
        return false;
      } else if (command.length() == 0) {
        continue;
      } else {
        reply(writer, "250 2.0.0 accepted");
      }
    }
    return false;
  }

  private static void reply(PrintWriter writer, String response) {
    writer.print(response + "\r\n");
    writer.flush();
  }

  @Override
  public void close() throws Exception {
    releaseAfterAcceptance();
    server.close();
    Socket socket = acceptedSocket;
    if (socket != null) {
      socket.close();
    }
    executor.shutdownNow();
    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("SMTP test server did not stop");
    }
  }
}
